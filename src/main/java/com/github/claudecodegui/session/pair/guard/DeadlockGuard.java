package com.github.claudecodegui.session.pair.guard;

import com.github.claudecodegui.bridge.SupervisorBridge;
import com.github.claudecodegui.session.pair.MainAIMonitor;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.contract.Contract;
import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.github.claudecodegui.session.pair.contract.ContractIssueRequest;
import com.github.claudecodegui.session.pair.contract.ContractRegistry;
import com.github.claudecodegui.session.pair.contract.ContractStatus;
import com.github.claudecodegui.session.pair.contract.ContractType;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects contract deadlock and triggers R1/R2/R3 escalation per
 * docs/plans/2026-05-25-pair-contract-state-machine-implementation.md §7.
 *
 * <p>Periodic 30s self-check. Acts ONLY when plan is in
 * {@link Plan.ActiveSubState#PENDING_DISCHARGE} — other states are normal
 * (plan complete / supervisor thinking / execution in progress).
 *
 * <p>R1 = re-issue original contract type as retry with hint prefix.
 * R2 = re-issue as {@link ContractType#SYSTEM_NUDGE} with hard rule hint.
 * R3 = escalate original; caller may enqueue a {@link ContractType#DECISION_REQUEST}
 * to supervisor — this is wired in B.2 / B.4 once ActionRouter routes
 * registry-issued contracts to the daemon.
 *
 * <p>Stage B.1 status: timer scaffolding + decision logic complete. Actual
 * delivery of R1/R2 hint text into the queue happens once Stage B.2 plumbs
 * ContractRegistry through ActionRouter to the webview / supervisor.
 */
public class DeadlockGuard {

    private static final Logger LOG = Logger.getInstance(DeadlockGuard.class);

    public static final long CHECK_INTERVAL_MS = 30_000L;
    public static final long GRACE_PERIOD_MS = 30_000L;
    /**
     * Liveness watchdog (2026-05-26): how long the plan may sit in
     * {@code PENDING_DECISION} with zero open contracts before we treat it as
     * a stuck "supervisor woken but produced no emit_action" event. Picked at
     * 2× the {@code TransitionDispatcher} wake debounce (30s) so the regular
     * wake always gets first crack before the guard fires.
     */
    public static final long PENDING_DECISION_STUCK_MS = 60_000L;

    /**
     * Deadlock fix / watchdog patch (2026-06-04): a main-AI turn that is
     * nominally "in progress" but has produced zero stream activity for this
     * long is treated as parked — a silent hang, or a blocking dialog the
     * webview didn't flag — rather than "busy doing honest work". Generous
     * (3 min) so long silent Task subagents (which don't refresh activity
     * between SubagentStop boundaries) are not misjudged; the recovery
     * (waking the supervisor) is non-destructive anyway.
     */
    public static final long MAIN_AI_SILENCE_STUCK_MS = 180_000L;
    /**
     * Min interval between supervisor wakes for one ongoing awaiting-user stall,
     * so a still-parked main AI does not spawn a DECISION_REQUEST every tick.
     */
    public static final long AWAITING_USER_RENUDGE_MS = 120_000L;

    private final PairSession pair;
    private final PlanStateMachine planSm;
    private final ContractRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> tickFuture;
    /**
     * Dedupe handle for the liveness watchdog. We nudge once per stuck event
     * — keyed by {@code plan.lastTransitionAt}. When the supervisor responds
     * (or any other event advances the plan), {@code lastTransitionAt} bumps
     * forward and the guard becomes eligible to fire again on the next stall.
     */
    private volatile long lastLivenessNudgeForTransitionAt = 0L;
    /** Dedupe handle for the awaiting-user supervisor wake (see
     *  {@link #escalateAwaitingUserToSupervisor}). */
    private volatile long lastAwaitingUserNudgeAt = 0L;

    public DeadlockGuard(PairSession pair, PlanStateMachine planSm, ContractRegistry registry) {
        this.pair = pair;
        this.planSm = planSm;
        this.registry = registry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deadlock-guard-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        tickFuture = scheduler.scheduleWithFixedDelay(
                this::tick, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("[DeadlockGuard] " + pair.getPairId() + " started, interval=" + CHECK_INTERVAL_MS + "ms");
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        ScheduledFuture<?> f = tickFuture;
        if (f != null) f.cancel(false);
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Visible for tests — call directly instead of waiting for the scheduler. */
    public void tickForTest() {
        tick();
    }

    private void tick() {
        if (!started.get()) return;
        if (pair.isDisposed()) return;
        try {
            Plan plan = planSm.getCurrent();
            if (plan == null) return;
            if (plan.state != Plan.PlanState.ACTIVE) return;

            long now = System.currentTimeMillis();
            // Liveness branch (2026-05-26): PENDING_DECISION + Open=0 is the
            // "supervisor woken but emitted nothing" stuck state. The
            // contract-loop below only handles PENDING_DISCHARGE, so without
            // this branch the plan can sit here indefinitely — no contract
            // for the loop to nudge, no state change for TransitionDispatcher
            // to re-fire on, and the ActionRouter wait-guard only fires if
            // supervisor emits SOMETHING (a no-op turn leaves it idle).
            if (plan.subState == Plan.ActiveSubState.PENDING_DECISION) {
                evaluatePendingDecisionLiveness(plan, now);
                return;
            }
            if (plan.subState != Plan.ActiveSubState.PENDING_DISCHARGE) return;

            List<Contract> open = registry.getOpenContracts();
            for (Contract c : open) {
                evaluate(c, now);
            }
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] " + pair.getPairId() + " tick threw: " + e.getMessage());
        }
    }

    /**
     * Liveness branch of the watchdog (2026-05-26).
     *
     * <p>Triggered when the plan is in {@code ACTIVE/PENDING_DECISION} with
     * zero open contracts for longer than {@link #PENDING_DECISION_STUCK_MS}.
     * That combination only happens if the supervisor was woken to decide
     * the next dispatch but produced a turn with no {@code emit_action} (pure
     * narration / hallucinated "已下发" without actually calling the tool).
     *
     * <p>Recovery: issue a {@code DECISION_REQUEST} to the supervisor with
     * explicit instructions to either dispatch, complete, or escalate. Once
     * the request lands, the plan moves to {@code PENDING_DISCHARGE} and the
     * existing R1/R2/R3 path takes over if the supervisor still doesn't act.
     *
     * <p>De-duplicated by {@code plan.lastTransitionAt} — one nudge per
     * stuck event. The supervisor itself producing a state transition resets
     * the dedupe (transition time advances past our marker).
     */
    private void evaluatePendingDecisionLiveness(Plan plan, long now) {
        // Don't preempt an in-flight supervisor turn — it may be the one that
        // will produce the missing emit_action.
        if (pair.hasInflightTurn()) return;
        // Grace period: TransitionDispatcher schedules its wake at +30s; we
        // wait another 30s so the regular dispatch path gets first crack.
        if (now - plan.lastTransitionAt < PENDING_DECISION_STUCK_MS) return;
        // Defensive: PENDING_DECISION implies no open contracts, but a race
        // between issue() and onContractIssued could in principle leave
        // contracts open while substate hasn't moved yet. Skip in that case.
        if (!registry.getOpenContracts().isEmpty()) return;
        // Dedupe: only nudge once per stuck event. lastTransitionAt advances
        // whenever the plan changes substate, which resets the gate.
        if (lastLivenessNudgeForTransitionAt >= plan.lastTransitionAt) return;
        lastLivenessNudgeForTransitionAt = plan.lastTransitionAt;

        long idleMs = now - plan.lastTransitionAt;
        try {
            issueLivenessDecisionRequest(plan, idleMs);
            LOG.warn("[DeadlockGuard] " + pair.getPairId()
                    + " pending-decision liveness nudge enqueued (idle=" + idleMs + "ms)");
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] liveness nudge failed: " + e.getMessage());
        }
    }

    private void evaluate(Contract c, long now) {
        // OPEN or RECEIVED both qualify — RECEIVED means the receiver ack'd
        // delivery but hasn't discharged the contract yet.
        if (!c.isOpen()) return;

        // Deadlock fix / watchdog patch (2026-06-04): the main AI can stop and
        // wait for the USER (AskUserQuestion / plan-approval / permission, or a
        // turn that ended with a "本轮待确认事项" section). In that state the SDK
        // turn stays "in progress" forever, so the isAssigneeTurnInProgress bail
        // below used to make BOTH watchdog paths (30s tick + per-contract
        // deadline) abstain indefinitely — the supervisor, parked on
        // wait_for_contract, was never woken → permanent deadlock. We now detect
        // it and wake the SUPERVISOR (the coordinator that should decide), NOT
        // the main AI: the normal R1/R2 re-inject would clobber the very
        // question the main AI is waiting on.
        if (c.assignedTo == ContractAssignee.MAIN_AI && isMainAiBlockedOnUser(now)) {
            escalateAwaitingUserToSupervisor(c, now);
            return;
        }

        if (isAssigneeTurnInProgress(c.assignedTo)) {
            return;
        }
        if (assigneeJustStarted(c.assignedTo, now)) {
            return;
        }
        // Don't double-enqueue if a retry/sibling for the same step+assignee is pending.
        if (anotherOpenForSameStep(c)) {
            return;
        }
        long idle = now - c.lastActivityAt;
        if (idle < c.deadlineMs) return;

        if (c.retryCount < c.maxRetries) {
            // R1 (retryCount 0 → 1) keeps original type. R2 escalates to SYSTEM_NUDGE.
            boolean isR2 = c.retryCount >= 1;
            ContractType retryType = isR2 ? ContractType.SYSTEM_NUDGE : c.type;
            String hint = buildHint(c, isR2);
            try {
                registry.retry(c.id, retryType, hint);
                LOG.warn("[DeadlockGuard] " + pair.getPairId()
                        + " R" + (c.retryCount + 1) + " retry enqueued for "
                        + c.id + " (idle=" + idle + "ms, deadline=" + c.deadlineMs + "ms)");
            } catch (Exception e) {
                LOG.warn("[DeadlockGuard] retry failed for " + c.id + ": " + e.getMessage());
            }
        } else {
            // R3: mark original escalated.
            // - If the failed contract is itself a DECISION_REQUEST, the
            //   supervisor is the bottleneck (already given a recovery prompt
            //   and still stuck). Issuing yet another DECISION_REQUEST would
            //   cascade forever. Plan B (2026-05-26 v3.2): bypass the
            //   supervisor — system synthesizes a TASK_ASSIGNMENT for MAIN_AI
            //   so the deadlock breaks via main-AI-driven activity. Once
            //   MAIN_AI replies, the supervisor gets fresh context and
            //   (per the v3.2 hard rules in code-supervisor.md) should
            //   re-enter a healthy decision cycle.
            // - Otherwise (MAIN_AI contract failure), do the normal supervisor
            //   escalation: issue DECISION_REQUEST so supervisor can pick
            //   reissue/skip/abort.
            try {
                registry.escalate(c.id);
                if (c.type == ContractType.DECISION_REQUEST) {
                    // Transport-backstop policy (2026-06-01): the supervisor was
                    // already nudged with an explicit DECISION_REQUEST and still
                    // didn't discharge it within deadline. We no longer fabricate
                    // a MAIN_AI task to "break the deadlock" — that synthesized
                    // work has no real owner (the main AI doesn't decide the next
                    // step), so it could only reply "nothing to do", which
                    // advanced lastTransitionAt and re-armed this guard → the
                    // observed 6th/7th-dispatch livelock. A wedged supervisor is
                    // now treated as a genuine failure: pause the plan + escalate
                    // to the human, fabricate nothing.
                    escalateWedgedSupervisorToHuman(c);
                    LOG.warn("[DeadlockGuard] " + pair.getPairId()
                            + " R3 on DECISION_REQUEST " + c.id + " — supervisor wedged, escalating to human (no system_takeover)");
                } else {
                    issueDecisionRequest(c);
                    LOG.warn("[DeadlockGuard] " + pair.getPairId()
                            + " R3 escalated " + c.id + " (idle=" + idle + "ms) — DECISION_REQUEST enqueued for supervisor");
                }
            } catch (Exception e) {
                LOG.warn("[DeadlockGuard] escalate failed for " + c.id + ": " + e.getMessage());
            }
        }
    }

    /**
     * Deadlock fix (2026-06-04): the main AI is "blocked on the user" when it
     * either explicitly raised a confirmation
     * ({@link MainAIMonitor#isAwaitingUser()} — AskUserQuestion / plan-approval
     * / permission / 待确认事项), OR its turn is nominally in progress but has
     * produced NO stream activity for longer than {@link #MAIN_AI_SILENCE_STUCK_MS}
     * (a silent hang, or a blocking dialog the webview didn't flag). Long honest
     * work keeps refreshing {@code lastActivityAt}, so it does not trip the
     * silence branch.
     */
    private boolean isMainAiBlockedOnUser(long now) {
        MainAIMonitor m = pair.getMainAIMonitor();
        if (m == null) return false;
        if (m.isAwaitingUser()) return true;
        if (!m.isTurnInProgress()) return false;
        long last = m.getLastActivityAt();
        if (last <= 0L) return false;
        return (now - last) >= MAIN_AI_SILENCE_STUCK_MS;
    }

    /**
     * Deadlock fix (2026-06-04): wake the supervisor when the main AI is parked
     * waiting for the user. Issues a {@link ContractType#DECISION_REQUEST} to the
     * SUPERVISOR (the coordinator that should decide) rather than re-injecting a
     * nudge to the main AI. Deduped so one stuck episode produces at most one
     * outstanding supervisor request:
     * <ul>
     *   <li>skip while a supervisor turn is already in flight;</li>
     *   <li>skip while ANY supervisor-assigned contract is still open;</li>
     *   <li>otherwise rate-limit to one issue per {@link #AWAITING_USER_RENUDGE_MS}.</li>
     * </ul>
     * If the woken supervisor itself stalls, the DECISION_REQUEST's own deadline
     * carries it into the existing R3 → {@link #escalateWedgedSupervisorToHuman}
     * path, so the pair always converges to either progress or a human.
     */
    private void escalateAwaitingUserToSupervisor(Contract mainAiContract, long now) {
        // Supervisor already thinking → it will produce something; don't pile on.
        if (pair.hasInflightTurn()) return;
        // Already asked the supervisor and waiting for its answer.
        for (Contract other : registry.getOpenContracts()) {
            if (other.assignedTo == ContractAssignee.SUPERVISOR) return;
        }
        // Rate-limit repeated wakes for the same ongoing stall.
        if (now - lastAwaitingUserNudgeAt < AWAITING_USER_RENUDGE_MS) return;
        lastAwaitingUserNudgeAt = now;

        MainAIMonitor m = pair.getMainAIMonitor();
        String kind = m != null && m.getAwaitingUserKind() != null
                ? m.getAwaitingUserKind() : "pending_confirmation";
        String question = m != null && m.getAwaitingUserQuestion() != null
                ? m.getAwaitingUserQuestion() : "(主 AI 未给出具体问题文本,请读它最近一轮回复末尾)";
        long since = m != null && m.getAwaitingUserSince() > 0 ? m.getAwaitingUserSince() : now;
        long waitedSec = Math.max(1L, (now - since) / 1000L);

        Plan plan = planSm.getCurrent();
        StringBuilder planSummary = new StringBuilder();
        if (plan == null) {
            planSummary.append("(no plan loaded)");
        } else {
            planSummary.append("Plan ").append(plan.id)
                    .append(" state=").append(plan.state)
                    .append("/").append(plan.subState);
            PlanStep cur = plan.getCurrentStep();
            if (cur != null) {
                planSummary.append("\n- Step ").append(cur.index + 1)
                        .append("/").append(plan.steps.size())
                        .append(": ").append(cur.title);
            }
        }

        String text = "[Pair 守护] 主 AI 已停下、正在等待用户确认 (" + waitedSec + " 秒, kind=" + kind
                + "),它不会自己继续;而你(监督者)在等它的 turn_end —— 这是双向等待死锁。\n\n"
                + "主 AI 的待确认内容:\n" + truncate(question, 1500) + "\n\n"
                + "当前状态:\n- " + planSummary + "\n\n"
                + "请本轮必须 emit_action,三选一,禁止仅 narration、禁止 wait:\n"
                + "(a) emit_action(inject_prompt, {inlinePrompt:'<对每条待确认项的裁决/答复>', objective:'...'}) "
                + "—— 你能裁决就直接答复主 AI,让它继续推进;\n"
                + "(b) emit_action(escalate_to_human, {question:'<把待确认项转给用户>', choices:[...], blocking:true}) "
                + "—— 需要真人拍板时升级;\n"
                + "(c) emit_action(complete_plan, {summary:'...'}) —— 若这些确认意味着工作已可收尾。";

        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        payload.addProperty("reason", "main_ai_awaiting_user");
        payload.addProperty("kind", kind);
        payload.addProperty("awaitingUserMs", now - since);

        try {
            registry.issue(ContractIssueRequest.builder()
                    .parentStepId(mainAiContract.parentStepId)
                    .type(ContractType.DECISION_REQUEST)
                    .assignedTo(ContractAssignee.SUPERVISOR)
                    .payloadJson(payload.toString())
                    .deadlineMs(5L * 60L * 1000L)
                    .replaceExisting(false)
                    .maxRetries(1)
                    .build());
            LOG.warn("[DeadlockGuard] " + pair.getPairId()
                    + " main AI awaiting user (" + kind + ", " + waitedSec
                    + "s) — DECISION_REQUEST enqueued for supervisor");
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] awaiting-user escalation failed: " + e.getMessage());
        }

        // Surface in the coordinator strip so the operator sees why the
        // supervisor got woken.
        try {
            com.github.claudecodegui.session.pair.PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        com.github.claudecodegui.session.pair.PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "main_ai_awaiting_user",
                        "主 AI 在等用户确认,已唤醒监督者裁决 (kind=" + kind + ")",
                        mainAiContract.id);
            }
        } catch (Exception ignored) { /* best-effort */ }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(截断 " + (s.length() - max) + " 字)";
    }

    private boolean isAssigneeTurnInProgress(ContractAssignee assignee) {
        if (assignee == ContractAssignee.MAIN_AI) {
            MainAIMonitor m = pair.getMainAIMonitor();
            return m != null && m.isTurnInProgress();
        }
        // Supervisor turn — track via PairSession.hasInflightTurn (counts in-flight
        // postEvent round-trips). True while EventBus is mid-call to daemon.
        return pair.hasInflightTurn();
    }

    private boolean assigneeJustStarted(ContractAssignee assignee, long now) {
        if (assignee == ContractAssignee.MAIN_AI) {
            MainAIMonitor m = pair.getMainAIMonitor();
            if (m == null) return false;
            long startedAt = m.getTurnStartedAt();
            return startedAt > 0 && (now - startedAt) < GRACE_PERIOD_MS;
        }
        // Supervisor: hasInflightTurn already covers in-progress. No separate grace.
        return false;
    }

    private boolean anotherOpenForSameStep(Contract c) {
        // If another OPEN/RECEIVED contract exists for the same step+assignee
        // (e.g. a retry we already issued this tick), skip — don't double-issue.
        for (Contract other : registry.getOpenContracts()) {
            if (other == c) continue;
            if (!other.isOpen()) continue;
            if (other.parentStepId == null) continue;
            if (other.parentStepId.equals(c.parentStepId) && other.assignedTo == c.assignedTo) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per-contract deadline callback. Called by {@link ContractRegistry} when
     * a contract's deadline timer fires. Falls through to the same
     * {@link #evaluate} logic the periodic tick uses, so retry/escalate
     * policy lives in one place.
     */
    public void onContractDeadline(Contract c) {
        if (!started.get() || pair.isDisposed() || c == null) return;
        try {
            Plan plan = planSm.getCurrent();
            // Same plan-state guard as tick — deadline fires might predate
            // a transition to DONE/WAITING/ABORTED. Don't act in those cases.
            if (plan != null && plan.state == Plan.PlanState.ACTIVE) {
                evaluate(c, System.currentTimeMillis());
            }
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] onContractDeadline threw for " + c.id + ": " + e.getMessage());
        }
    }

    private String buildHint(Contract c, boolean r2) {
        if (r2) {
            return "[系统提示] contract " + c.id + " 已重推 1 次仍未 discharge。"
                    + "你的下一个 turn 必须以下三者之一结尾,不允许纯文本对话:"
                    + " (a) 至少一个 tool_use;"
                    + " (b) report_turn_completion;"
                    + " (c) ask_clarification 工具。立即处理。";
        }
        return "[系统提示] 这是 contract " + c.id + " 的第 1 次重推。"
                + "如果你已经完成,直接 report_turn_completion;"
                + " 如果你正在做,继续;"
                + " 如果你没开始,立即按原任务执行,不要再发文字解释。";
    }

    /**
     * R3: enqueue a DECISION_REQUEST contract addressed to supervisor so it
     * can decide what to do about the deadlocked main-AI contract.
     * Supervisor will choose: reissue with clarification / skip step /
     * abort plan / escalate_to_human (existing escalate path).
     *
     * <p>Deadline 5min — much shorter than main-AI contract default because
     * the supervisor is now the bottleneck. If supervisor itself stalls,
     * R3 on the DECISION_REQUEST surfaces an UI alert via the existing
     * escalate_to_human → PairStatusPusher chain (no further auto-escalation).
     */
    private void issueDecisionRequest(Contract failed) {
        Plan plan = planSm.getCurrent();
        StringBuilder planSummary = new StringBuilder();
        if (plan == null) {
            planSummary.append("(no plan loaded)");
        } else {
            planSummary.append("Plan ").append(plan.id)
                    .append(" state=").append(plan.state)
                    .append("/").append(plan.subState);
            PlanStep cur = plan.getCurrentStep();
            if (cur != null) {
                planSummary.append("\n- Step ").append(cur.index + 1)
                        .append("/").append(plan.steps.size())
                        .append(": ").append(cur.title);
            }
        }

        long ageMin = Math.max(1L, failed.ageMs(System.currentTimeMillis()) / 60_000L);
        String text = "[Pair 系统决策请求] contract " + failed.id
                + " 在 " + ageMin + " 分钟内 R1/R2 重试 " + failed.retryCount
                + " 次仍未被主 AI discharge。\n\n"
                + "当前状态:\n- " + planSummary + "\n\n"
                + "请通过 emit_action 决策一项:\n"
                + "(a) reissue_with_clarification: 重新下发任务(附带更明确的提示词)\n"
                + "(b) skip_step: 跳过当前 step,标记 blocked\n"
                + "(c) abort_plan: 中止整个 plan(用户需重新规划)\n"
                + "(d) escalate_to_human: 你无法决策,交给人工\n\n"
                + "仅在 LLM 真的无法决策时选 (d)。";

        JsonObject decisionPayload = new JsonObject();
        decisionPayload.addProperty("text", text);
        decisionPayload.addProperty("failedContractId", failed.id);
        decisionPayload.addProperty("failedRetryCount", failed.retryCount);

        try {
            registry.issue(ContractIssueRequest.builder()
                    .parentStepId(failed.parentStepId)
                    .type(ContractType.DECISION_REQUEST)
                    .assignedTo(ContractAssignee.SUPERVISOR)
                    .payloadJson(decisionPayload.toString())
                    .deadlineMs(5L * 60L * 1000L)
                    .replaceExisting(false)
                    .maxRetries(1)
                    .build());
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] failed to issue DECISION_REQUEST for "
                    + failed.id + ": " + e.getMessage());
        }
    }

    /**
     * Liveness nudge (2026-05-26): issue a {@code DECISION_REQUEST} to the
     * supervisor when the plan has been stuck in {@code PENDING_DECISION}
     * with no open contracts. Distinct from {@link #issueDecisionRequest}
     * which escalates a specific failed contract — this one is for "no
     * contract was ever created because supervisor didn't emit".
     *
     * <p>v3.2 (2026-05-26): deadline 90s + {@code maxRetries=0} so this
     * contract falls straight through to R3 if the supervisor doesn't
     * discharge it. v4 (2026-06-01): R3 on a {@code DECISION_REQUEST} now
     * routes to {@link #escalateWedgedSupervisorToHuman} — the plan is paused
     * and escalated to the operator. The old "system_takeover" that fabricated
     * a MAIN_AI task was removed because that synthesized work had no real
     * owner and re-armed this guard into a livelock.
     */
    private void issueLivenessDecisionRequest(Plan plan, long idleMs) {
        long idleSec = Math.max(1L, idleMs / 1000L);
        StringBuilder planSummary = new StringBuilder();
        planSummary.append("Plan ").append(plan.id)
                .append(" state=").append(plan.state)
                .append("/").append(plan.subState);
        PlanStep cur = plan.getCurrentStep();
        if (cur != null) {
            planSummary.append("\n- Step ").append(cur.index + 1)
                    .append("/").append(plan.steps.size())
                    .append(": ").append(cur.title);
        }

        String text = "[Pair Liveness 守护] plan 已在 PENDING_DECISION 状态停留 "
                + idleSec + " 秒,且当前 open contract = 0。\n\n"
                + "诊断: 你上一轮被唤醒后只输出了 narration、没有 emit_action,"
                + "或者 emit_action 被路由层拒绝。无论哪种,主 AI 都没有收到任何任务,"
                + "整个 pair 处于双向等待死锁。\n\n"
                + "当前状态:\n- " + planSummary + "\n\n"
                + "请本轮必须 emit_action,从下面三选一,禁止仅输出 narration:\n"
                + "(a) emit_action(inject_prompt, {inlinePrompt: '<给主 AI 的具体指令>', "
                + "objective: '...'}) —— 派单给主 AI 推进 plan\n"
                + "(b) emit_action(complete_plan, {summary: '...'}) —— 收尾当前 plan\n"
                + "(c) emit_action(escalate_to_human, ...) —— 你无法决策时升级给用户\n\n"
                + "⚠️ 90 秒内如果你仍未 emit 真实 action,Pair Liveness 守护会判定监督者卡死,"
                + "暂停当前 plan 并升级给人工介入(不会再凭空代派主 AI)。";

        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        payload.addProperty("reason", "pending_decision_stuck");
        payload.addProperty("idleMs", idleMs);

        registry.issue(ContractIssueRequest.builder()
                .parentStepId(cur != null ? cur.id : null)
                .type(ContractType.DECISION_REQUEST)
                .assignedTo(ContractAssignee.SUPERVISOR)
                .payloadJson(payload.toString())
                .deadlineMs(90L * 1000L)
                .replaceExisting(false)
                .maxRetries(0)
                .build());
    }

    /**
     * Transport-backstop terminal (2026-06-01). Replaces the old
     * {@code issueMainAiRecoveryDispatch} "system_takeover" path.
     *
     * <p>Fires when a {@link ContractType#DECISION_REQUEST} reaches R3 — the
     * supervisor was woken, given explicit recovery choices, and STILL produced
     * no usable action within deadline. Previously we synthesized a
     * {@link ContractType#TASK_ASSIGNMENT} to MAIN_AI to break the deadlock
     * without a human, but that fabricated work has no real owner: the main AI
     * doesn't decide the next step, so it could only reply "nothing to do",
     * which advanced {@code lastTransitionAt} and re-armed this guard → the
     * observed livelock (6th/7th identical dispatch).
     *
     * <p>New policy: a wedged supervisor is a genuine failure. We move the plan
     * to {@code WAITING} (which disarms the guard — {@link #tick} only acts on
     * {@code ACTIVE} plans), record a coordinator event, and surface a
     * non-blocking alert so the operator can intervene. Nothing is fabricated,
     * so there is no work for the main AI to bounce back and re-arm us with.
     */
    private void escalateWedgedSupervisorToHuman(Contract failedDecision) {
        // Best-effort: unstick a frozen supervisor turn so its pane spinner
        // clears. Fire-and-forget — escalation proceeds regardless of outcome.
        try {
            SupervisorBridge sb = pair.getSupervisorBridge();
            if (sb != null) sb.interrupt();
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] interrupt before escalate failed: " + e.getMessage());
        }

        String reason = "supervisor DECISION_REQUEST "
                + (failedDecision != null ? failedDecision.id : "(none)")
                + " 未在 deadline 内 discharge — 监督者卡死";

        // WAITING disarms the guard and marks "needs human". Safe no-op if the
        // plan already reached a terminal state in the meantime.
        try {
            if (planSm != null) planSm.onEscalatedToHuman(reason);
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] onEscalatedToHuman failed: " + e.getMessage());
        }

        // Coordinator-event log so the operator sees it in the status strip
        // alongside the Plan/Ctr/Guard rows. Best-effort.
        try {
            com.github.claudecodegui.session.pair.PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        com.github.claudecodegui.session.pair.PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "supervisor_wedged",
                        "监督者卡死,已暂停 plan 并升级给人工(不再代派主 AI): " + reason,
                        failedDecision != null ? failedDecision.id : null);
            }
        } catch (Exception ignored) { /* best-effort */ }

        // Non-blocking alert so the user notices without a forced modal.
        try {
            com.github.claudecodegui.session.pair.ActionRouter router = pair.getActionRouter();
            if (router != null && router.getWebview() != null) {
                JsonObject alert = new JsonObject();
                alert.addProperty("pairId", pair.getPairId());
                alert.addProperty("supervisorId", pair.getAgentId());
                alert.addProperty("severity", "alert");
                alert.addProperty("category", "C2");
                alert.addProperty("reason", "监督者卡死,Pair 已暂停并等待人工介入");
                alert.addProperty("question", reason);
                alert.addProperty("ts", System.currentTimeMillis());
                router.getWebview().onPairAlert(alert);
            }
        } catch (Exception ignored) { /* best-effort */ }
    }
}
