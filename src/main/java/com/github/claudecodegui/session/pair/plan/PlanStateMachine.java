package com.github.claudecodegui.session.pair.plan;

import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authoritative plan state owner for one Pair.
 *
 * <p>Transition rules per docs/plans/2026-05-25-pair-contract-state-machine-implementation.md §5.
 * All mutators are synchronized; listeners fire inside the lock and must
 * return quickly. The {@link Plan} returned by {@link #getCurrent()} is a
 * live reference — callers must treat it as read-only.
 */
public class PlanStateMachine {

    private static final Logger LOG = Logger.getInstance(PlanStateMachine.class);

    private final String pairId;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final List<PlanStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Plan current;

    public PlanStateMachine(String pairId) {
        this.pairId = pairId;
    }

    public void start() {
        started.set(true);
    }

    public void stop() {
        started.set(false);
    }

    public boolean isStarted() {
        return started.get();
    }

    public Plan getCurrent() {
        return current;
    }

    /**
     * Restore from a persisted Plan snapshot (called by L2 hydration).
     * Does NOT fire transition events — this is a load, not a transition.
     */
    public synchronized void restore(Plan plan) {
        this.current = plan;
        LOG.info("[PlanSM] " + pairId + " restored plan="
                + (plan == null ? "null" : plan.id + " state=" + plan.state));
    }

    /** Reset to no-plan state. Fires no listener (caller decides semantics). */
    public synchronized void clear() {
        this.current = null;
    }

    // ─── Transitions ────────────────────────────────────────────────────

    public synchronized void onPlanCreated(List<PlanStep> steps, Map<String, Object> metadata) {
        Plan.PlanState oldState = current == null ? null : current.state;
        Plan.ActiveSubState oldSub = current == null ? null : current.subState;

        Plan p = Plan.create(pairId, steps, metadata);
        p.state = Plan.PlanState.ACTIVE;
        // PENDING_DECISION first: even with steps, supervisor still must dispatch the first contract.
        p.subState = Plan.ActiveSubState.PENDING_DECISION;
        p.lastTransitionAt = System.currentTimeMillis();
        // If first step is already assigned (e.g. has a contract attached), the
        // CONTRACT_ISSUED transition will move us to PENDING_DISCHARGE.
        this.current = p;

        logTransition(PlanTransition.PLAN_CREATED, oldState, oldSub, p);
        notifyListeners(oldState, oldSub, p);
    }

    public synchronized void onContractIssued(String contractId, String stepId, ContractAssignee assignee) {
        if (current == null || current.isTerminal()) return;
        if (current.state != Plan.PlanState.ACTIVE) return;

        PlanStep step = findStep(stepId);
        if (step != null && !step.contractIds.contains(contractId)) {
            step.contractIds.add(contractId);
        }

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        // If a turn is currently EXECUTING, issuing another contract doesn't bump us out.
        if (current.subState != Plan.ActiveSubState.EXECUTING) {
            current.subState = Plan.ActiveSubState.PENDING_DISCHARGE;
            current.lastTransitionAt = System.currentTimeMillis();
        }

        logTransition(PlanTransition.CONTRACT_ISSUED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onTurnStarted(ContractAssignee assignee) {
        if (current == null || current.isTerminal()) return;
        if (current.state != Plan.PlanState.ACTIVE) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.subState = Plan.ActiveSubState.EXECUTING;
        current.lastTransitionAt = System.currentTimeMillis();

        PlanStep step = current.getCurrentStep();
        if (step != null && step.status == PlanStep.StepStatus.TODO) {
            step.status = PlanStep.StepStatus.IN_PROGRESS;
            step.attempts = step.attempts + 1;
        }

        logTransition(PlanTransition.TURN_STARTED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onTurnEnded(ContractAssignee assignee, boolean hasOpenContracts) {
        if (current == null || current.isTerminal()) return;
        if (current.state != Plan.PlanState.ACTIVE) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.subState = hasOpenContracts
                ? Plan.ActiveSubState.PENDING_DISCHARGE
                : Plan.ActiveSubState.PENDING_DECISION;
        current.lastTransitionAt = System.currentTimeMillis();

        logTransition(PlanTransition.TURN_ENDED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onContractDischarged(String contractId, String stepId) {
        if (current == null || current.isTerminal()) return;
        // No state change here on its own — the caller follows up with
        // onTurnEnded (with hasOpenContracts recomputed) or onStepCompleted.
        // We just record it on the step for traceability.
        PlanStep step = findStep(stepId);
        if (step != null) {
            // discharge alone doesn't complete a step (one step may have multiple contracts).
            current.lastTransitionAt = System.currentTimeMillis();
        }
        // No listener fire — sub-state didn't change unless caller follows up.
    }

    public synchronized void onStepCompleted(String stepId) {
        if (current == null || current.isTerminal()) return;
        if (current.state != Plan.PlanState.ACTIVE) return;

        PlanStep step = findStep(stepId);
        if (step != null) {
            step.status = PlanStep.StepStatus.DONE;
            step.completedAt = System.currentTimeMillis();
        }

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;

        // Advance currentStepIndex to the next not-done step
        advanceCurrentStepIndex();

        if (current.allStepsDone()) {
            current.state = Plan.PlanState.DONE;
            current.subState = null;
        } else {
            // No open contract change here — caller should also call onTurnEnded
            // or onContractIssued for the next step. Leave subState alone unless
            // we know nothing is in flight, in which case → PENDING_DECISION.
            if (current.subState != Plan.ActiveSubState.EXECUTING) {
                current.subState = Plan.ActiveSubState.PENDING_DECISION;
            }
        }
        current.lastTransitionAt = System.currentTimeMillis();

        logTransition(PlanTransition.STEP_COMPLETED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onEscalatedToHuman(String reason) {
        if (current == null || current.isTerminal()) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.WAITING;
        current.subState = null;
        current.lastTransitionAt = System.currentTimeMillis();
        if (reason != null) {
            current.metadata.put("escalationReason", reason);
        }

        logTransition(PlanTransition.ESCALATED_TO_HUMAN, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onHumanResumed() {
        if (current == null) return;
        if (current.state != Plan.PlanState.WAITING) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.ACTIVE;
        // After resume, we don't know if there are open contracts.
        // Caller is expected to follow up with the appropriate event.
        // Default to PENDING_DECISION as the safe baseline.
        current.subState = Plan.ActiveSubState.PENDING_DECISION;
        current.lastTransitionAt = System.currentTimeMillis();
        current.metadata.remove("escalationReason");

        logTransition(PlanTransition.HUMAN_RESUMED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    /**
     * 2026-05-28: user clicked the supervisor Stop button. Distinct from
     * {@link #onEscalatedToHuman} (supervisor gave up, needs a human decision):
     * this is the user proactively pausing an otherwise-healthy plan to inject
     * context. Reuses the {@code WAITING} state — which {@code DeadlockGuard}
     * and {@code TransitionDispatcher} already treat as "no decision owed, do
     * not nudge / do not arm a wake" — but marks {@code pauseReason="user"} so
     * {@link #onUserResumed} only resumes user-initiated pauses and never
     * collides with a future escalate-to-human WAITING.
     *
     * <p>No-op unless the plan is currently {@code ACTIVE} (a DONE/ABORTED/
     * already-WAITING plan has nothing to pause).
     */
    public synchronized void onUserPaused() {
        if (current == null || current.isTerminal()) return;
        if (current.state != Plan.PlanState.ACTIVE) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.WAITING;
        current.subState = null;
        current.lastTransitionAt = System.currentTimeMillis();
        current.metadata.put("pauseReason", "user");

        logTransition(PlanTransition.USER_PAUSED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    /**
     * 2026-05-28: counterpart to {@link #onUserPaused}. Fired right before the
     * user's supplementary {@code user_input} is forwarded to the supervisor,
     * so the supervisor's resulting action is routed while the plan is ACTIVE
     * again. Guarded so it only un-pauses a user-initiated pause — a no-op if
     * the plan isn't WAITING, or is WAITING for some other reason (e.g. a real
     * escalation), in which case the dedicated resume path owns the transition.
     *
     * @return true if a user-pause was actually lifted (caller may want to push
     *         a fresh status snapshot); false on the no-op paths.
     */
    public synchronized boolean onUserResumed() {
        if (current == null) return false;
        if (current.state != Plan.PlanState.WAITING) return false;
        if (!"user".equals(current.metadata.get("pauseReason"))) return false;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.ACTIVE;
        current.subState = Plan.ActiveSubState.PENDING_DECISION;
        current.lastTransitionAt = System.currentTimeMillis();
        current.metadata.remove("pauseReason");

        logTransition(PlanTransition.USER_RESUMED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
        return true;
    }

    public synchronized void onUserCancel(String reason) {
        if (current == null) return;
        if (current.isTerminal()) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.ABORTED;
        current.subState = null;
        current.lastTransitionAt = System.currentTimeMillis();
        if (reason != null) {
            current.metadata.put("abortReason", reason);
        }

        logTransition(PlanTransition.USER_CANCEL, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    public synchronized void onPlanReplaced() {
        Plan.PlanState oldState = current == null ? null : current.state;
        Plan.ActiveSubState oldSub = current == null ? null : current.subState;

        current = null;
        logTransition(PlanTransition.PLAN_REPLACED, oldState, oldSub, null);
        notifyListeners(oldState, oldSub, null);
    }

    /**
     * Bug fix 2026-05-26: explicit "supervisor signals plan complete" entry.
     * Fired by {@code ActionRouter} on {@code emit_action(complete_plan)} —
     * the supervisor's protocol-level way to say "all steps terminal, write
     * the COMPLETION_REPORT now". Doesn't require {@code allStepsDone()} to
     * already be true (supervisor may complete even with skipped/blocked
     * steps); the listener that writes the report classifies severity via
     * {@link com.github.claudecodegui.session.pair.CompletionDetector}.
     */
    public synchronized void onPlanCompleted(String summary) {
        if (current == null || current.isTerminal()) return;

        Plan.PlanState oldState = current.state;
        Plan.ActiveSubState oldSub = current.subState;
        current.state = Plan.PlanState.DONE;
        current.subState = null;
        current.lastTransitionAt = System.currentTimeMillis();
        if (summary != null && !summary.isEmpty()) {
            current.metadata.put("completionSummary", summary);
        }

        logTransition(PlanTransition.PLAN_COMPLETED, oldState, oldSub, current);
        notifyListeners(oldState, oldSub, current);
    }

    // ─── Listeners ──────────────────────────────────────────────────────

    public void addListener(PlanStateListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(PlanStateListener l) {
        listeners.remove(l);
    }

    // ─── Internals ──────────────────────────────────────────────────────

    private PlanStep findStep(String stepId) {
        if (current == null || stepId == null) return null;
        for (PlanStep s : current.steps) {
            if (stepId.equals(s.id)) return s;
        }
        return null;
    }

    private void advanceCurrentStepIndex() {
        if (current == null || current.steps == null) return;
        for (int i = 0; i < current.steps.size(); i++) {
            PlanStep s = current.steps.get(i);
            if (s.status == PlanStep.StepStatus.TODO || s.status == PlanStep.StepStatus.IN_PROGRESS) {
                current.currentStepIndex = i;
                return;
            }
        }
        // All done — leave index pointing to last step
        current.currentStepIndex = current.steps.size() - 1;
    }

    private void notifyListeners(Plan.PlanState oldState, Plan.ActiveSubState oldSub, Plan now) {
        List<PlanStateListener> snapshot = new ArrayList<>(listeners);
        for (PlanStateListener l : snapshot) {
            try {
                l.onStateChanged(oldState, oldSub, now);
            } catch (Exception e) {
                LOG.warn("[PlanSM] " + pairId + " listener threw: " + e.getMessage());
            }
        }
    }

    private void logTransition(PlanTransition t, Plan.PlanState oldState, Plan.ActiveSubState oldSub, Plan now) {
        LOG.info("[PlanSM] " + pairId + " " + t
                + " old=" + oldState + "/" + oldSub
                + " new=" + (now == null ? "null" : now.state + "/" + now.subState));
    }
}
