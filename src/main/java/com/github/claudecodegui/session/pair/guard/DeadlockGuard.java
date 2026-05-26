package com.github.claudecodegui.session.pair.guard;

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
                    issueMainAiRecoveryDispatch(planSm.getCurrent(), c,
                            "supervisor DECISION_REQUEST " + c.id + " R3 (deadline " + c.deadlineMs
                                    + "ms 内未 discharge,supervisor 卡死)");
                    LOG.warn("[DeadlockGuard] " + pair.getPairId()
                            + " R3 on DECISION_REQUEST " + c.id + " — system_takeover: auto-dispatch to MAIN_AI");
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
     * discharge it. R3 on a {@code DECISION_REQUEST} triggers Plan B
     * ({@link #issueMainAiRecoveryDispatch}) — system takeover that
     * dispatches a recovery task to MAIN_AI to break the deadlock without
     * any human intervention.
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
                + "⚠️ 90 秒内如果你仍未 emit 真实 action,Pair Liveness 守护会进入 system_takeover —— "
                + "直接合成 TASK_ASSIGNMENT 派给主 AI,绕过你打破死锁(全自治模式无人干预)。";

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
     * Plan B (2026-05-26 v3.2): system-takeover auto-dispatch.
     *
     * <p>Fires when a {@link ContractType#DECISION_REQUEST} reaches R3 —
     * meaning the supervisor was nudged once, given the explicit recovery
     * choices, and STILL didn't emit a usable action. At that point we treat
     * the supervisor as the bottleneck and synthesize a {@link ContractType#TASK_ASSIGNMENT}
     * directly to MAIN_AI so the deadlock breaks without any human in the loop.
     *
     * <p>The synthesized prompt deliberately gives MAIN_AI agency rather than
     * trying to guess the next step from narration (which is unreliable):
     * MAIN_AI either continues based on the sequential pattern it sees, or
     * reports its own state so the supervisor gets fresh context on the next
     * wake. Either path produces a state transition that lifts the plan out
     * of PENDING_DECISION.
     *
     * <p>Marked with {@code systemTakeover=true} in the payload + a
     * coordinator-event log entry so the operator can audit. The supervisor
     * will see the resulting MAIN_AI TURN_REPORT on its next wake and (per
     * the v3.2 hard rules in {@code code-supervisor.md}) should snap back
     * onto the correct emit_action(inject_prompt) path.
     */
    private void issueMainAiRecoveryDispatch(Plan plan, Contract failedDecision, String reason) {
        StringBuilder planSummary = new StringBuilder();
        if (plan == null) {
            planSummary.append("(no plan loaded)");
        } else {
            planSummary.append("Plan ").append(plan.id)
                    .append(" state=").append(plan.state)
                    .append("/").append(plan.subState);
            PlanStep cur = plan.getCurrentStep();
            if (cur != null) {
                planSummary.append("; 当前 step #").append(cur.index + 1)
                        .append("/").append(plan.steps.size())
                        .append(" \"").append(cur.title).append("\"")
                        .append(" status=").append(cur.status);
            }
        }

        String inlinePrompt = "[Pair Liveness 系统代派 - 监督者卡死,系统接管派单]\n\n"
                + "你刚才完成上一轮任务并 report_turn_completion 后,监督者陷入 wait 循环 ("
                + "narration 说\"下发\"但实际未 emit_action(inject_prompt),且 Pair Liveness DECISION_REQUEST "
                + "也未被正确响应)。为避免双向死锁,Pair 守护直接派单让你继续。\n\n"
                + "触发原因: " + reason + "\n"
                + "当前状态: " + planSummary + "\n\n"
                + "请这样处理:\n"
                + "1. 检视你刚完成的任务模式 —— 如果是序列化模式(如 ping #N → ping #N+1, "
                + "Step N → Step N+1, 测试 N → 测试 N+1),直接执行下一步,正常调用 "
                + "report_turn_completion 汇报。\n"
                + "2. 如果不是序列化模式 / 不确定下一步:\n"
                + "   - 简述你刚完成什么 + 你看到的下一步候选(1-3 个)\n"
                + "   - 调 report_turn_completion(selfAssessment={confidence:'low', "
                + "concerns:['监督者卡死,系统代派,需要监督者重新对齐']})\n"
                + "   - 这会让监督者获得新上下文重新决策\n"
                + "3. 不论哪种,本轮**必须**调用至少一个有副作用的 tool 或 report_turn_completion,"
                + "禁止纯文字回复(否则会触发主 AI 侧的合同 R1/R2/R3)。";

        JsonObject payload = new JsonObject();
        payload.addProperty("inlinePrompt", inlinePrompt);
        payload.addProperty("objective", "[系统代派] 监督者卡死,打破死锁继续推进");
        payload.addProperty("systemTakeover", true);
        payload.addProperty("triggeredBy", failedDecision != null ? failedDecision.id : "(none)");
        payload.addProperty("reason", reason);

        String parentStepId = null;
        if (plan != null) {
            PlanStep cur = plan.getCurrentStep();
            if (cur != null) parentStepId = cur.id;
        }
        if (parentStepId == null && failedDecision != null) {
            parentStepId = failedDecision.parentStepId;
        }

        try {
            registry.issue(ContractIssueRequest.builder()
                    .parentStepId(parentStepId)
                    .type(ContractType.TASK_ASSIGNMENT)
                    .assignedTo(ContractAssignee.MAIN_AI)
                    .payloadJson(payload.toString())
                    .deadlineMs(5L * 60L * 1000L)
                    .replaceExisting(false)
                    .maxRetries(2)
                    .build());
        } catch (Exception e) {
            LOG.warn("[DeadlockGuard] system_takeover issue failed: " + e.getMessage());
            return;
        }

        // Coordinator-event log so the operator sees system_takeover in the
        // status strip alongside Plan/Ctr/Guard rows. Best-effort — never let
        // a logging failure mask the actual dispatch.
        try {
            com.github.claudecodegui.session.pair.PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        com.github.claudecodegui.session.pair.PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "system_takeover",
                        "Liveness system_takeover: " + reason
                                + " → 已合成 TASK_ASSIGNMENT 派给主 AI",
                        null);
            }
        } catch (Exception ignored) { /* best-effort */ }
    }
}
