package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.contract.Contract;
import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.github.claudecodegui.session.pair.contract.ContractIssueRequest;
import com.github.claudecodegui.session.pair.contract.ContractListener;
import com.github.claudecodegui.session.pair.contract.ContractRegistry;
import com.github.claudecodegui.session.pair.contract.ContractType;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import com.github.claudecodegui.session.pair.workflow.NodeStatus;
import com.github.claudecodegui.session.pair.workflow.SupervisorWorkflowManager;
import com.github.claudecodegui.session.pair.workflow.WorkflowActionParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

// Project is currently retained for forward-compat (notifications, etc.); see field comment.

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Translates Supervisor {@code ACTION} payloads into effects:
 *   - {@code inject_prompt}     → fire {@code onInjectPrompt} (webview routes as fake-user input)
 *   - {@code retry_with_hint}   → schedule a delayed inject_prompt
 *   - {@code escalate_to_human} → fire {@code onEscalate} (webview opens decision dialog)
 *   - {@code approve_and_continue} → bump progress; emit a passive note
 *   - {@code request_amendment} → record in progress.json + escalate to user
 *   - {@code wait}              → no-op
 *
 * <p>The router itself is free of webview/UI dependencies. Effects are delivered
 * via {@link WebviewBridge} callbacks injected by the caller (Phase B IPC handler).
 * Each lambda is invoked off the EDT — the bridge is responsible for thread hops.
 */
public class ActionRouter {

    private static final Logger LOG = Logger.getInstance(ActionRouter.class);

    /**
     * Adapter interface — concrete impl lives next to the IPC handler that
     * owns the JBCefBrowser, and translates events into webview JS calls.
     */
    public interface WebviewBridge {
        /** Push a Supervisor action card to the right pane. */
        void onActionEvent(JsonObject event);

        /** Inject a fake-user prompt into the main session (Supervisor-attributed).
         *  Legacy 3-arg signature. */
        void onInjectPrompt(String pairId, String supervisorId, String prompt);

        /** Protocol v2 (2026-05-24): same as {@link #onInjectPrompt} but with a
         *  {@code directiveId} so the webview can later post a matching
         *  {@code pair_directive_ack} IPC. Default delegates to the 3-arg form
         *  for back-compat with bridges that don't override (the directive will
         *  then time out client-side, surfacing as {@code directive_lost}). */
        default void onInjectPromptV2(String pairId, String supervisorId,
                                      String directiveId, String prompt) {
            onInjectPrompt(pairId, supervisorId, prompt);
        }

        /** Open the escalation decision dialog. */
        void onEscalate(JsonObject payload);

        /**
         * Toggle the "Supervisor is thinking" indicator in the right pane.
         * Called true when an event has been forwarded to the daemon and we
         * are awaiting an ACTION; called false once any ACTION arrives.
         */
        default void onThinking(String supervisorId, boolean thinking) { /* optional */ }

        /**
         * Phase 2 (2026-05-24): push a {@link PairStatusSnapshot} JSON
         * payload to the right-pane status panel. Throttled upstream by
         * {@link PairStatusPusher} so impl can do a direct webview call
         * without further coalescing.
         */
        default void onPairStatusUpdate(JsonObject snapshot) { /* optional */ }

        /**
         * Protocol v2 (2026-05-24): non-blocking alert from {@code record_alert}.
         * Replaces the modal {@code onEscalate} for autonomy-mode C1/C2 decisions
         * — supervisor records what it decided to do and continues, the UI shows
         * a toast/notification the user can review asynchronously.
         */
        default void onPairAlert(JsonObject payload) { /* optional */ }

        /**
         * Periodic system notice (e.g. supervisor health-check heartbeat) that
         * should NOT enter the supervisor chat or interrupt in-flight thinking.
         * Rendered on the right pane's PeriodicNoticeStrip — a separate history
         * row that sits between the autonomy controls and the decision timeline.
         * Payload: {@code { ts, kind, message, details? }}.
         */
        default void onPairNotice(JsonObject payload) { /* optional */ }
    }

    @SuppressWarnings("unused") // reserved for future Project-scoped effects (notifications, file watchers)
    private final Project project;
    private final PairSession pair;
    private final ScheduledExecutorService scheduler;
    private volatile WebviewBridge webview = NoopWebviewBridge.INSTANCE;

    /**
     * 2026-05-25: webview readiness gate. Flips to true the first time
     * {@link #markWebviewReady} fires (driven by the {@code pair_webview_ready}
     * IPC the PairProvider posts on mount). Until then, inject pushes are
     * buffered in {@link #pendingInjectsBeforeReady} so a cold-start dispatch
     * that races the React tree mount isn't lost. Drained synchronously
     * inside {@link #markWebviewReady}.
     */
    private volatile boolean webviewReady = false;
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingInject> pendingInjectsBeforeReady
            = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Cap so a permanently-unready webview can't bloat memory. */
    private static final int MAX_PENDING_INJECTS_BEFORE_READY = 64;

    /**
     * Plan A (2026-05-26 v3.2): consecutive {@code emit_action(wait)} rejections
     * for the same "no open MAIN_AI contract" reason. Bumped each time
     * {@link #handleWaitGuard} rejects; reset to 0 whenever any non-wait action
     * makes it past the dispatch switch. Used by the rejection message builder
     * to escalate the suggestion text — by the 3rd consecutive rejection the
     * supervisor is told the Liveness Guard will hand off to system auto-dispatch.
     */
    private volatile int consecutiveWaitRejections = 0;

    public ActionRouter(Project project, PairSession pair) {
        this.project = project;
        this.pair = pair;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "supervisor-actionrouter-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Contract State Machine v3 (2026-05-25): wire ContractRegistry events
     * into the existing delivery channels. Call once after both
     * {@code ActionRouter} and {@code ContractRegistry} are attached to the
     * {@link PairSession}. Idempotent.
     *
     * <p>Routing rules:
     * <ul>
     *   <li>{@code assignedTo == MAIN_AI}: deliver via {@code webview.onInjectPromptV2}
     *       using contract.id as the directiveId.</li>
     *   <li>{@code assignedTo == SUPERVISOR}: deliver via
     *       {@code SupervisorBridge.postEvent(payload, "system")} so the
     *       message lands as a system-role input on the supervisor SDK.</li>
     * </ul>
     */
    public void attachContractRegistryListener() {
        ContractRegistry registry = pair.getContractRegistry();
        if (registry == null) return;
        registry.addListener(new ContractListener() {
            @Override
            public void onIssued(Contract c) {
                deliverContract(c);
            }
            @Override
            public void onRetried(Contract original, Contract newRetry) {
                deliverContract(newRetry);
            }
        });
    }

    /**
     * Route a contract to its assignee's delivery channel. Called on
     * {@code onIssued} and {@code onRetried} so initial dispatches and
     * framework-driven retries both flow through the same code path.
     */
    private void deliverContract(Contract c) {
        if (c == null || c.payloadJson == null) return;
        if (pair.isDisposed()) return;
        try {
            JsonObject payload = JsonParser.parseString(c.payloadJson).getAsJsonObject();
            if (c.assignedTo == ContractAssignee.MAIN_AI) {
                deliverToMainAi(c, payload);
            } else if (c.assignedTo == ContractAssignee.SUPERVISOR) {
                deliverToSupervisor(c, payload);
            }
        } catch (Exception e) {
            LOG.warn("[ActionRouter] deliverContract " + c.id + " failed: " + e.getMessage());
        }
    }

    private void deliverToMainAi(Contract c, JsonObject payload) {
        String prompt = resolvePromptFromPayload(payload);
        if (prompt == null || prompt.isEmpty()) {
            LOG.warn("[ActionRouter] deliverToMainAi empty prompt for contract " + c.id);
            return;
        }
        // Retries get a "[系统提示]" prefix so the LLM sees what's going on.
        if (c.retryCount > 0) {
            prompt = buildRetryHint(c) + "\n\n" + prompt;
        }
        invokeInjectOnWebview(c.id, prompt);
    }

    private void deliverToSupervisor(Contract c, JsonObject payload) {
        com.github.claudecodegui.bridge.SupervisorBridge bridge = pair.getSupervisorBridge();
        if (bridge == null) {
            LOG.warn("[ActionRouter] deliverToSupervisor: no SupervisorBridge for contract " + c.id);
            return;
        }
        // DECISION_REQUEST and other supervisor-bound contracts go as system-role
        // input so they don't pollute the conversation history as a user turn.
        JsonObject event = new JsonObject();
        event.addProperty("type", c.type == null ? "system_message" : c.type.name().toLowerCase());
        event.addProperty("contractId", c.id);
        event.addProperty("retryCount", c.retryCount);
        event.add("payload", payload);
        try {
            bridge.postEvent(event, "system");
            LOG.info("[ActionRouter] supervisor-bound contract delivered: " + c.id + " type=" + c.type);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] supervisor delivery failed for " + c.id + ": " + e.getMessage());
        }
    }

    private String buildRetryHint(Contract c) {
        if (c.retryCount >= 2) {
            return "[系统提示] contract " + c.id + " 已重推 " + (c.retryCount - 1)
                    + " 次仍未 discharge。你的下一个 turn 必须以下三者之一结尾,"
                    + "不允许纯文本对话:(a) 至少一个 tool_use;"
                    + "(b) report_turn_completion;(c) ask_clarification 工具。立即处理。";
        }
        return "[系统提示] 这是 contract " + c.id + " 的第 " + c.retryCount + " 次重推。"
                + "如果你已经完成,直接 report_turn_completion;"
                + "如果你正在做,继续;如果你没开始,立即按原任务执行,不要再发文字解释。";
    }

    public void setWebviewBridge(WebviewBridge bridge) {
        this.webview = bridge == null ? NoopWebviewBridge.INSTANCE : bridge;
    }

    /**
     * Phase 2: expose the current bridge so siblings (e.g. {@link PairStatusPusher})
     * can push their own message types without each holding their own copy.
     * Returns the no-op stub if no bridge has been wired.
     */
    public WebviewBridge getWebview() {
        return webview;
    }

    /**
     * Notify the right pane that we're awaiting Supervisor output. The bridge
     * relays this to {@code window.onPairThinking}.
     */
    public void signalThinking(boolean thinking) {
        webview.onThinking(pair.getAgentId(), thinking);
    }

    /**
     * Push a periodic-event notice to the right-pane strip without touching
     * the supervisor pipeline. Used by {@link SupervisorMonitor} on idle
     * health-check ticks so the user sees a heartbeat without the supervisor
     * being forced to produce a no-op turn.
     */
    public void pushNotice(JsonObject notice) {
        if (notice == null) return;
        try {
            webview.onPairNotice(notice);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onPairNotice failed: " + e.getMessage());
        }
    }

    /**
     * Surface a transport / lifecycle failure as a visible bubble in the right
     * pane. We piggy-back on the regular action-event channel so the user sees
     * the same message styling — there's no point inventing a second renderer
     * for a rare path. The action is downgraded to {@code wait} so no effect
     * fires; the message text carries the explanation.
     */
    public void dispatchTransportError(String message) {
        webview.onThinking(pair.getAgentId(), false);

        JsonObject action = new JsonObject();
        action.addProperty("action", "wait");
        action.addProperty("reason", "transport_error");
        action.add("payload", new JsonObject());

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("pairId", pair.getPairId());
        wrapper.addProperty("supervisorId", pair.getAgentId());
        wrapper.addProperty("naturalText", "⚠️ " + (message != null ? message : "未知错误"));
        wrapper.addProperty("reasoningText", "");
        wrapper.add("action", action);
        wrapper.addProperty("parseError", "transport_error");
        wrapper.addProperty("rawText", message != null ? message : "");

        try { webview.onActionEvent(wrapper); } catch (Exception e) {
            LOG.warn("[ActionRouter] dispatchTransportError webview push failed: " + e.getMessage());
        }
    }

    /**
     * Dispatch an action wrapper as emitted by the daemon
     * ({@code [SUPERVISOR_ACTION] {...}} line, already parsed).
     *
     * Shape: { pairId, supervisorId, naturalText, action: {action, reason, payload}, ... }
     */
    public void dispatch(JsonObject actionWrapper) {
        if (actionWrapper == null) return;

        // Quota-reset auto-resume (RateLimitWatcher): classify this completed
        // supervisor turn from its natural text BEFORE the (possibly downgraded
        // `wait`) action processing flips the plan to WAITING — so the resume is
        // scheduled first and the workflow DN2 watchdog keeps the node RUNNING.
        // A normal turn here clears any pending rate-limit state (self-heal).
        try {
            String natural = actionWrapper.has("naturalText") && !actionWrapper.get("naturalText").isJsonNull()
                    ? actionWrapper.get("naturalText").getAsString() : null;
            pair.getRateLimitWatcher().onSupervisorTurnText(natural);
        } catch (Exception ignored) { /* best-effort */ }

        // Any ACTION arrival means thinking ended.
        webview.onThinking(pair.getAgentId(), false);

        // v4 unified pipeline: tool_use cards, compaction notices, and usage
        // snapshots all flow live via [SUPERVISOR_MSG] (handled by PairHandler)
        // — no per-wrapper side-channel dispatch needed. Usage in particular
        // is now broadcast through UsagePushService.broadcast on every raw SDK
        // message, so the TokenIndicator updates mid-turn rather than only at
        // the closing [SUPERVISOR_ACTION].

        JsonObject action = actionWrapper.has("action") && actionWrapper.get("action").isJsonObject()
                ? actionWrapper.getAsJsonObject("action") : null;
        if (action == null) {
            // No structured action — render the wrapper as-is (parse error /
            // narration-only paths) and bail; nothing flows through the
            // contract pipeline so there's no "successful dispatch" claim.
            webview.onActionEvent(actionWrapper);
            return;
        }

        String type = action.has("action") ? action.get("action").getAsString() : "wait";
        JsonObject payload = action.has("payload") && action.get("payload").isJsonObject()
                ? action.getAsJsonObject("payload") : new JsonObject();

        // Plan A (2026-05-26 v3.2): supervisor broke out of the wait loop by
        // emitting *anything* other than `wait` — reset the consecutive counter.
        // We don't gate on whether the action will succeed (empty inject_prompt
        // gets rejected below); the signal we care about is "supervisor tried
        // something different this turn".
        if (!"wait".equals(type)) {
            consecutiveWaitRejections = 0;
            // 2026-05-28: a decisive (non-wait) action means the supervisor took
            // its turn and answered whatever woke it — including a Liveness/R3
            // DECISION_REQUEST. Discharge any open SUPERVISOR-assigned contract
            // now so a nudge the supervisor DID respond to can't later hit its
            // deadline and fire a spurious system_takeover (the dangling-contract
            // mis-escalation). Bare `wait` deliberately does NOT discharge — that
            // preserves the takeover safety net for a genuine wait-loop hang.
            dischargeOpenSupervisorContracts("supervisor emitted " + type);
        }

        // Route C (2026-05-26): dispatch-type actions get their payload
        // validated at the entry. Reject WITHOUT rendering the "已注入指令到
        // 主 AI" card so the UI never shows a successful injection that did
        // not happen. Replaces the late-stage check in handleInjectPrompt
        // that fired after the card had already been pushed.
        boolean isInjectType = "inject_prompt".equals(type) || "retry_with_hint".equals(type);
        if (isInjectType) {
            String prompt = resolvePromptFromPayload(payload);
            if (prompt == null || prompt.isEmpty()) {
                LOG.warn("[ActionRouter] " + type + " rejected at dispatch entry: empty payload (no inlinePrompt/prompt/spilledPath)");
                sendActionRejectionToSupervisor(
                        type + " rejected: payload 缺少 prompt 内容(inlinePrompt / prompt / spilledPath 都为空)。"
                                + "只有 reason 字段是不够的 —— reason 只用于显示卡片,主 AI 看不到。",
                        "本轮重新 emit_action(" + type + ", payload={inlinePrompt: '<给主 AI 的具体指令>', "
                                + "objective: '...', expectedDeliverables: [...]})。"
                                + "注意: save_plan 只是登记 plan 结构,不会自动派单 —— 派单必须靠 emit_action(inject_prompt) 带 inlinePrompt 字段。"
                                + "如果是 ping/echo 类轻量任务, inlinePrompt 写出你要主 AI 回复什么即可。");
                return;
            }
        }

        // Route A (2026-05-26): for inject-type actions, defer the action-card
        // render until AFTER ContractRegistry.issue() succeeds (see
        // handleInjectPrompt). For everything else, render now — they don't
        // go through the registry so there's no truth source to wait for.
        if (!isInjectType) {
            webview.onActionEvent(actionWrapper);
        }

        // v3: a `decisions[]` array may ride along ANY action type. Process it
        // before the action switch so counters and webview cards are updated
        // even if the action itself is a no-op (e.g. wait). Decisions are
        // self-contained records of supervisor mini-judgments; they're
        // independent of whether dispatch succeeds, so render immediately.
        dispatchDecisions(payload);

        switch (type) {
            case "inject_prompt":
                handleInjectPrompt(payload, /*delaySec*/ 0, actionWrapper);
                break;
            case "retry_with_hint":
                int wait = payload.has("wait_seconds") && !payload.get("wait_seconds").isJsonNull()
                        ? payload.get("wait_seconds").getAsInt() : 0;
                pair.getProgressManager().incrementCounter("auto_recover_count");
                handleInjectPrompt(payload, Math.max(0, wait), actionWrapper);
                break;
            case "approve_and_continue":
                if (payload.has("mark_step_complete") && !payload.get("mark_step_complete").isJsonNull()) {
                    int step = payload.get("mark_step_complete").getAsInt();
                    pair.getProgressManager().markStepStatus(step, "done");
                    // Protocol v2 (2026-05-24): bump budget step counter so cost
                    // tracking reflects forward progress. Also useful for the
                    // completion report's stats section.
                    PairBudgetTracker bt = pair.getBudgetTracker();
                    int stepsCompleted = 0;
                    if (bt != null) {
                        bt.incrementSteps();
                        stepsCompleted = bt.getStepsCompleted();
                    }
                    // Phase 6 (2026-05-24): F2 — step advanced cleanly, reset the
                    // consecutive-directive-failure streak.
                    pair.resetDirectiveFailures();
                    // Phase 6 (2026-05-24): T1 — periodic RE-PLAN nudge. Every
                    // 5 completed steps, hint the supervisor to self-evaluate
                    // its plan (it may call save_plan(source="replan")).
                    if (stepsCompleted > 0 && stepsCompleted % 5 == 0
                            && pair.getEventBus() != null) {
                        try {
                            pair.getEventBus().publishReplanDue("periodic", stepsCompleted);
                        } catch (Exception e) {
                            LOG.debug("[ActionRouter] periodic replan publish failed: " + e.getMessage());
                        }
                    }
                }
                break;
            case "escalate_to_human":
                // 2026-05-31: route by blocking-ness, not just autonomy mode. A
                // blocking escalation (explicit payload.blocking / category C3 /
                // carries a choices[] list) ALWAYS surfaces as a modal — even in
                // full/mixed autonomy — so a genuine "I need the human" is never
                // swallowed by the non-blocking toast path. Strict keeps its
                // always-modal behaviour. Non-blocking escalations fall through
                // to the alert toast.
                {
                    boolean strict = "strict".equals(pair.getAutonomyMode());
                    if (strict || isBlockingEscalation(payload)) {
                        routeEscalateAsModal(action);
                    } else {
                        if (!payload.has("category")) payload.addProperty("category", "C2");
                        if (!payload.has("severity")) payload.addProperty("severity", "alert");
                        dispatchAlertFromAction(action, payload);
                    }
                }
                break;
            case "record_alert":
                // Protocol v2 (2026-05-24): non-blocking alert. Supervisor decided
                // a C1/C2 fallback; UI shows a toast, supervisor continues.
                //
                // 2026-05-31: the remote/server daemon aliases escalate_to_human →
                // record_alert(legacyEscalate=true) before it reaches us. When such
                // an aliased alert is actually a blocking decision (explicit
                // blocking / category C3 / carries choices), or autonomy is strict,
                // promote it back to a modal so remote-mode escalations pop up the
                // same way local ones do. A genuine record_alert (no legacyEscalate)
                // stays a toast even in strict — it is non-blocking by design.
                {
                    boolean fromEscalate = payload.has("legacyEscalate")
                            && !payload.get("legacyEscalate").isJsonNull()
                            && payload.get("legacyEscalate").getAsBoolean();
                    boolean strict = "strict".equals(pair.getAutonomyMode());
                    if (fromEscalate && (strict || isBlockingEscalation(payload))) {
                        routeEscalateAsModal(action);
                    } else {
                        dispatchAlertFromAction(action, payload);
                    }
                }
                break;
            case "request_amendment":
                // Strict-plan policy: amendments are NOT auto-applied. Escalate the
                // request to the user as a special-flavored escalation.
                pair.getProgressManager().incrementCounter("escalate_count");
                JsonObject wrapped = new JsonObject();
                wrapped.addProperty("kind", "amendment_request");
                if (action.has("reason")) wrapped.add("reason", action.get("reason"));
                if (payload.has("proposal")) wrapped.add("proposal", payload.get("proposal"));
                webview.onEscalate(wrapped);
                break;
            case "complete_plan":
                // v3.1 (2026-05-26): supervisor explicitly signals "plan done".
                // Transitions PlanState → DONE; the wired listener in
                // PairSessionManager writes COMPLETION_REPORT.md.
                handleCompletePlan(payload);
                break;
            case "complete_workflow_node":
                // P2 (coding-plan §12.1): the SINGLE node→engine channel (DN1).
                // done  → reuse complete_plan (writes COMPLETION_REPORT + plan→DONE),
                //         then report DONE + changed_files to the workflow engine.
                // blocked → escalate this pair to a human, then report WAITING_HUMAN.
                // onNodeReport no-ops when this pair isn't a workflow node, so a
                // stray emit from a regular supervisor degrades to complete_plan /
                // escalate behaviour without touching the engine.
                handleCompleteWorkflowNode(payload);
                break;
            case "wait_for_contract":
                // v3.1 (2026-05-26): typed wait — supervisor declares it's
                // waiting for a specific OPEN contract to come back. Rejected
                // (Layer 2) if the named contract doesn't exist in OPEN state.
                handleWaitForContract(payload);
                break;
            case "wait":
                // v3.1 (2026-05-26): generic wait now goes through a state-
                // machine guard. Rejected when the plan is ACTIVE / PENDING_DECISION
                // with no open MAIN_AI contract — that combo means "supervisor
                // owes a dispatch decision but emitted wait instead" (the
                // narrate-says-派单-but-action-says-wait hallucination class).
                handleWaitGuard(payload, actionWrapper);
                break;
            default:
                // intentionally no-op
                break;
        }
    }

    /**
     * 2026-05-28: discharge every OPEN contract assigned to the SUPERVISOR.
     * Called when the supervisor emits a decisive (non-wait) action — the turn
     * that just happened IS the response to whatever woke it (a Liveness or R3
     * {@code DECISION_REQUEST}), so the contract is answered and must be closed.
     * Without this, a DECISION_REQUEST the supervisor actually responded to
     * stays OPEN and the DeadlockGuard escalates it on deadline → spurious
     * {@code system_takeover}. {@link ContractRegistry#discharge} no-ops on
     * unknown/closed ids, and {@code getOpenContracts()} returns a copy, so
     * iterating-then-discharging is safe.
     */
    private void dischargeOpenSupervisorContracts(String reason) {
        ContractRegistry registry = pair.getContractRegistry();
        if (registry == null) return;
        for (Contract c : registry.getOpenContracts()) {
            if (c.assignedTo != ContractAssignee.SUPERVISOR) continue;
            try {
                registry.discharge(c.id, reason);
            } catch (Exception e) {
                LOG.warn("[ActionRouter] discharge supervisor contract " + c.id
                        + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * v3.1 (2026-05-26): handle {@code emit_action(complete_plan)}. Transitions
     * the plan to DONE; {@code PairSessionManager.wireCompletionReportListener}
     * fires the report write off the resulting state-change event.
     */
    private void handleCompletePlan(JsonObject payload) {
        PlanStateMachine sm = pair.getPlanStateMachine();
        if (sm == null) {
            LOG.warn("[ActionRouter] complete_plan: no PlanStateMachine for pair " + pair.getPairId());
            return;
        }
        Plan current = sm.getCurrent();
        if (current == null) {
            LOG.warn("[ActionRouter] complete_plan: no current plan to complete for pair " + pair.getPairId());
            sendActionRejectionToSupervisor(
                    "complete_plan rejected: no active plan exists.",
                    "If you intended to acknowledge completion without a plan, just emit_action(wait_for_user_input) or emit_action(escalate_to_human).");
            return;
        }
        if (current.isTerminal()) {
            LOG.info("[ActionRouter] complete_plan ignored: plan " + current.id + " already terminal=" + current.state);
            return;
        }
        String summary = payload != null && payload.has("summary") && !payload.get("summary").isJsonNull()
                ? payload.get("summary").getAsString() : null;
        sm.onPlanCompleted(summary);
        LOG.info("[ActionRouter] " + pair.getPairId() + " complete_plan → plan " + current.id + " transitioned to DONE");
    }

    /**
     * P2 (coding-plan §12.1): the supervisor's {@code complete_workflow_node}
     * report — the single node→engine channel (DN1). This is the first production
     * caller of {@link SupervisorWorkflowManager#onNodeReport}, closing the loop
     * the P1 skeleton stubbed out.
     *
     * <p>done → reuse {@link #handleCompletePlan} (writes COMPLETION_REPORT, plan
     * → DONE) then report DONE + changed_files. blocked → escalate this pair to a
     * human ({@code onEscalatedToHuman}) then report WAITING_HUMAN. The engine
     * ignores the report when this pair is not a workflow node.
     */
    private void handleCompleteWorkflowNode(JsonObject payload) {
        WorkflowActionParser.ParsedNodeReport report = WorkflowActionParser.parse(payload);
        SupervisorWorkflowManager mgr = SupervisorWorkflowManager.getInstance(project);
        if (report.status == NodeStatus.DONE) {
            handleCompletePlan(payload);
            mgr.onNodeReport(pair.getPairId(), NodeStatus.DONE, report.summary, report.changedFiles);
            LOG.info("[ActionRouter] " + pair.getPairId()
                    + " complete_workflow_node(done) → onNodeReport DONE, changed="
                    + (report.changedFiles == null ? 0 : report.changedFiles.size()));
        } else {
            PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm != null) {
                sm.onEscalatedToHuman(report.summary);
            }
            mgr.onNodeReport(pair.getPairId(), NodeStatus.WAITING_HUMAN, report.summary, null);
            LOG.info("[ActionRouter] " + pair.getPairId()
                    + " complete_workflow_node(blocked) → onNodeReport WAITING_HUMAN");
        }
    }

    /**
     * v3.1 (2026-05-26): handle {@code emit_action(wait_for_contract, {contractId})}.
     * Validates that the named contract is OPEN; if not, rejects so the
     * supervisor must pick a real outstanding contract or use a different action.
     */
    private void handleWaitForContract(JsonObject payload) {
        String contractId = payload != null && payload.has("contractId") && !payload.get("contractId").isJsonNull()
                ? payload.get("contractId").getAsString() : null;
        if (contractId == null || contractId.isEmpty()) {
            sendActionRejectionToSupervisor(
                    "wait_for_contract rejected: missing payload.contractId.",
                    "Specify the contract id you are waiting for, e.g. {\"contractId\":\"ctr_step1_xxx\"}, or emit_action(complete_plan) if all done.");
            return;
        }
        ContractRegistry registry = pair.getContractRegistry();
        Contract c = registry == null ? null : registry.findById(contractId);
        if (c == null) {
            sendActionRejectionToSupervisor(
                    "wait_for_contract rejected: contract " + contractId + " not found.",
                    "Use emit_action(inject_prompt) to issue a real contract first, or emit_action(complete_plan) if the plan is finished.");
            return;
        }
        if (!c.isOpen()) {
            sendActionRejectionToSupervisor(
                    "wait_for_contract rejected: contract " + contractId + " is not OPEN (status=" + c.status + ").",
                    "That contract already terminated. Either decide the next step now (emit_action inject_prompt / complete_plan), or wait on a different OPEN contract.");
            return;
        }
        LOG.info("[ActionRouter] " + pair.getPairId() + " wait_for_contract acknowledged contractId=" + contractId);
    }

    /**
     * v3.1 (2026-05-26): Layer 2 guard for the generic {@code emit_action(wait)}.
     * Rejects when the supervisor "narrated dispatch but actually waited" —
     * detected as: plan ACTIVE (any sub-state) and **no open MAIN_AI contracts**.
     * In that state the next correct action is inject_prompt / complete_plan /
     * escalate_to_human — wait is by definition wrong because there's literally
     * nothing main-AI-side to wait for.
     *
     * <p>2026-05-26 fix: this used to gate only on PENDING_DECISION sub-state,
     * but a transient PENDING_DISCHARGE-with-Open=0 happens whenever discharge
     * races ahead of the next onTurnEnded recomputation (and even AFTER the
     * markDirectiveAcked auto-onTurnEnded fix, the supervisor's wake might
     * latch the stale sub-state). Reject regardless of sub-state — the
     * authoritative signal is "no open MAIN_AI contract".
     */
    private void handleWaitGuard(JsonObject payload, JsonObject actionWrapper) {
        PlanStateMachine sm = pair.getPlanStateMachine();
        if (sm == null) return;
        Plan current = sm.getCurrent();
        if (current == null) return; // no plan → no rule to enforce
        if (current.state != Plan.PlanState.ACTIVE) return; // WAITING/DONE/ABORTED wait is fine

        ContractRegistry registry = pair.getContractRegistry();
        boolean hasOpenMainAi = false;
        if (registry != null) {
            for (Contract c : registry.getOpenContracts()) {
                if (c.assignedTo == ContractAssignee.MAIN_AI) {
                    hasOpenMainAi = true;
                    break;
                }
            }
        }
        if (hasOpenMainAi) {
            // Legitimate wait — supervisor is actually waiting for an in-flight
            // contract. Treat this like any non-wait action: reset the consecutive
            // counter so a later wait→reject cycle starts fresh.
            consecutiveWaitRejections = 0;
            return;
        }

        // Hallucination caught — narration may claim dispatch but no contract was issued.
        consecutiveWaitRejections++;
        int attempt = consecutiveWaitRejections;

        String narrate = "";
        try {
            if (actionWrapper != null && actionWrapper.has("naturalText") && !actionWrapper.get("naturalText").isJsonNull()) {
                narrate = actionWrapper.get("naturalText").getAsString();
            }
        } catch (Exception ignored) { /* best-effort */ }
        boolean narrateClaimsDispatch = narrate.contains("派单") || narrate.contains("inject")
                || narrate.contains("下发") || narrate.contains("已发送") || narrate.contains("已派")
                || narrate.contains("已注入");

        String stateSnapshot = buildWaitRejectionStateSnapshot(current, registry);
        String narrateHint = narrateClaimsDispatch
                ? "\n【narration 检测】本轮 narration 含'派单/下发/inject/已注入'字样,但 emit_action 是 wait —— "
                + "这就是上一节 v3.2 硬规则 #3 禁止的不一致。narration 不会派单,只有 emit_action(inject_prompt) 派单。"
                : "";
        String escalationNotice = buildEscalationNotice(attempt);

        sendActionRejectionToSupervisor(
                "wait rejected: plan is ACTIVE (sub=" + current.subState + ") with no open MAIN_AI contract — "
                        + "literally nothing for main AI to do, so wait is invalid."
                        + narrateHint
                        + "\n" + stateSnapshot
                        + escalationNotice,
                "本轮必须三选一: (a) emit_action(inject_prompt, {inlinePrompt:'<具体指令>', objective:'...'}) 派单给主 AI; "
                        + "(b) emit_action(complete_plan, {summary:'...'}) 收尾; "
                        + "(c) emit_action(escalate_to_human, ...) 升级。"
                        + "禁止再 emit wait —— 主 AI 当前没有任何 OPEN 合同,等不到任何回执。");
    }

    /**
     * Post-compaction re-prime (2026-06-05). After the supervisor's context is
     * SDK-auto-compacted, the generated summary tends to freeze a transient
     * "I just dispatched, now I wait for the main AI's report" mental state into
     * a standing "remain in wait" instruction, and the SDK continuation prompt
     * ("resume as if the break never happened") reinforces it. But the
     * authoritative coordination state (open contracts / plan sub-state) lives
     * OUTSIDE the LLM context, so a resumed supervisor can emit {@code wait}
     * when the MAIN_AI contract is in fact already closed and it owes a decision.
     * That wait is rejected by {@link #handleWaitGuard}; the supervisor — anchored
     * to the stale summary — keeps re-waiting until {@link
     * com.github.claudecodegui.session.pair.guard.DeadlockGuard} escalates it as
     * "wedged" → plan WAITING → human. From the user's seat the supervisor just
     * spins forever.
     *
     * <p>Fix A: inject the real-state snapshot as the freshest {@code system}
     * message right at the compaction boundary, so the resumed/next turn is
     * anchored to reality instead of the summary. Only fires in the exact danger
     * window — plan ACTIVE with no open MAIN_AI contract ("you owe a decision,
     * nothing to wait for"). In any other state a post-compaction wait is
     * legitimate, so we stay silent. Called from the compact-boundary handler in
     * {@code PairSessionManager}.
     */
    public void reprimeAfterCompaction() {
        try {
            PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm == null) return;
            Plan current = sm.getCurrent();
            if (current == null || current.state != Plan.PlanState.ACTIVE) return;

            ContractRegistry registry = pair.getContractRegistry();
            if (registry != null) {
                for (Contract c : registry.getOpenContracts()) {
                    // An open MAIN_AI contract means waiting IS legitimate — the
                    // supervisor really is waiting for an in-flight task. Leave it be.
                    if (c.assignedTo == ContractAssignee.MAIN_AI) return;
                }
            }

            com.github.claudecodegui.bridge.SupervisorBridge bridge = pair.getSupervisorBridge();
            if (bridge == null) return;

            String snapshot = buildWaitRejectionStateSnapshot(current, registry);
            JsonObject event = new JsonObject();
            event.addProperty("type", "post_compaction_reprime");
            event.addProperty("reason",
                    "你的上下文刚被自动压缩。压缩摘要可能把你'派单后正在等待主 AI 报告'的旧心智固化成了"
                    + "'remain in wait / 继续等待'—— 那是过期状态,不要再相信摘要里的'继续等'。"
                    + "以下面这份实时状态为准:\n" + snapshot);
            event.addProperty("suggestion",
                    "当前没有任何 OPEN 的 MAIN_AI 合同 → 没有任何回执可等。本轮必须三选一,禁止 emit wait: "
                    + "(a) emit_action(inject_prompt, {inlinePrompt:'<给主 AI 的下一步具体指令>', objective:'...'}) 派单; "
                    + "(b) emit_action(complete_plan, {summary:'...'}) 收尾; "
                    + "(c) emit_action(escalate_to_human, ...) 升级。");
            event.addProperty("ts", System.currentTimeMillis());
            bridge.postEvent(event, "system");

            // Fresh, anchored start: the loop that drove consecutiveWaitRejections
            // up was the stale-summary one we just corrected. Reset so the next
            // (now-informed) wait, if any, is judged on its own — the DeadlockGuard
            // PENDING_DECISION liveness path still backstops to a human if the
            // re-prime doesn't take.
            consecutiveWaitRejections = 0;

            LOG.warn("[ActionRouter] " + pair.getPairId()
                    + " post-compaction reprime injected (plan ACTIVE, no open MAIN_AI contract)");

            PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "post_compaction_reprime",
                        "压缩后已重注入真实协调状态,防止 supervisor 照旧 wait",
                        null);
            }
        } catch (Exception e) {
            LOG.warn("[ActionRouter] reprimeAfterCompaction failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * Post-resume re-prime (SR2/SR5, session-resume-plan.md). The seam between
     * "the supervisor's transcript was resumed from history" and "the Java
     * coordination layer (contracts / plan state machine) was rebuilt fresh on
     * restart". The resumed LLM remembers "I dispatched contract C and I'm
     * waiting", but the fresh ContractRegistry has no such contract — so without
     * this it would emit a stale {@code wait}, get rejected, and loop into the
     * wedged-supervisor escalation (same failure class as post-compaction).
     *
     * <p>Injects an authoritative-state snapshot as the freshest {@code system}
     * message right after a resume, telling the supervisor: your coordination
     * state is fresh, do NOT trust your recalled "I'm waiting", VERIFY the real
     * repo/contract state, then decide. Same shape as {@link #reprimeAfterCompaction}
     * (reuses {@link #buildWaitRejectionStateSnapshot}); only fires in the danger
     * window (plan ACTIVE, no open MAIN_AI contract). Called by
     * {@code SupervisorWorkflowManager} right after a resume-mode node launch,
     * before the re-dispatch kickoff.
     */
    public void reprimeAfterResume() {
        try {
            PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm == null) return;
            Plan current = sm.getCurrent();
            if (current == null || current.state != Plan.PlanState.ACTIVE) return;

            ContractRegistry registry = pair.getContractRegistry();
            if (registry != null) {
                for (Contract c : registry.getOpenContracts()) {
                    if (c.assignedTo == ContractAssignee.MAIN_AI) return;   // legit wait window
                }
            }

            com.github.claudecodegui.bridge.SupervisorBridge bridge = pair.getSupervisorBridge();
            if (bridge == null) return;

            String snapshot = buildWaitRejectionStateSnapshot(current, registry);
            JsonObject event = new JsonObject();
            event.addProperty("type", "post_resume_reprime");
            event.addProperty("reason",
                    "你的会话刚从历史 resume(你能看到之前的对话/决策)——但 Java 协调状态(合同/plan)"
                    + "是重启后全新重建的,不是你记忆里的那个。不要相信记忆里『我在等合同 C / 已派单』,"
                    + "那些在新的协调状态里并不存在。以下面这份真实状态为准:\n" + snapshot);
            event.addProperty("suggestion",
                    "本轮先核对真实情况再决策(禁止 emit wait): 用 Read/Grep 看改动文件、看主 AI 最近一段输出,"
                    + "判断哪些已完成、从哪继续。然后三选一: "
                    + "(a) emit_action(inject_prompt, {inlinePrompt:'<给主 AI 的下一步具体指令>', objective:'...'}) 派单; "
                    + "(b) emit_action(complete_plan, {summary:'...'}) 收尾; "
                    + "(c) emit_action(escalate_to_human, ...) 升级。");
            event.addProperty("ts", System.currentTimeMillis());
            bridge.postEvent(event, "system");
            consecutiveWaitRejections = 0;

            LOG.warn("[ActionRouter] " + pair.getPairId()
                    + " post-resume reprime injected (plan ACTIVE, no open MAIN_AI contract)");

            PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "post_resume_reprime",
                        "会话已从历史恢复,已重注入真实协调状态(防止 supervisor 凭旧记忆 wait)",
                        null);
            }
        } catch (Exception e) {
            LOG.warn("[ActionRouter] reprimeAfterResume failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * Plan A (2026-05-26 v3.2): build a "what is actually true right now"
     * snapshot the supervisor must see to break out of its stale mental model
     * ("I dispatched ping #N, contract still OPEN" when in fact no contract
     * exists). Includes plan state + open contract list + a hint about the most
     * recently closed MAIN_AI contract so the supervisor can see what it just
     * lost track of.
     */
    private String buildWaitRejectionStateSnapshot(Plan plan, ContractRegistry registry) {
        StringBuilder sb = new StringBuilder();
        sb.append("【真实状态快照(以这份为准,不要再相信你自己的记忆)】\n");
        sb.append("- Plan: ").append(plan.id)
                .append(" state=").append(plan.state)
                .append("/").append(plan.subState).append("\n");

        PlanStep cur = plan.getCurrentStep();
        if (cur != null) {
            sb.append("- 当前 step: #").append(cur.index + 1)
                    .append("/").append(plan.steps.size())
                    .append(" \"").append(cur.title).append("\"")
                    .append(" status=").append(cur.status).append("\n");
        } else {
            sb.append("- 当前 step: (无)\n");
        }

        if (registry == null) {
            sb.append("- ContractRegistry: 未挂载\n");
            return sb.toString();
        }

        // Open contracts (split by assignee for clarity)
        int openMainAi = 0;
        int openSupervisor = 0;
        StringBuilder openIds = new StringBuilder();
        for (Contract c : registry.getOpenContracts()) {
            if (c.assignedTo == ContractAssignee.MAIN_AI) openMainAi++;
            else if (c.assignedTo == ContractAssignee.SUPERVISOR) openSupervisor++;
            if (openIds.length() > 0) openIds.append(", ");
            openIds.append(c.id).append("(").append(c.type).append("/").append(c.assignedTo).append(")");
        }
        sb.append("- OPEN 合同: 主 AI=").append(openMainAi)
                .append(" 监督者=").append(openSupervisor);
        if (openIds.length() > 0) {
            sb.append(" → [").append(openIds).append("]");
        }
        sb.append("\n");

        if (openMainAi == 0) {
            sb.append("  ⚠️ 主 AI 没有任何 OPEN 合同 —— 你以为存在的'ping #N contract' / 'step contract' 已不存在");
            sb.append("(要么从未通过 emit_action(inject_prompt) 创建,要么已 DISCHARGE)。\n");
        }

        // Most-recently-closed MAIN_AI contract to help supervisor anchor
        Contract lastClosedMainAi = findMostRecentClosedMainAiContract(registry);
        if (lastClosedMainAi != null) {
            sb.append("- 最近一次主 AI 合同 (已 ")
                    .append(lastClosedMainAi.status)
                    .append("): ").append(lastClosedMainAi.id);
            if (lastClosedMainAi.parentStepId != null) {
                sb.append(" step=").append(lastClosedMainAi.parentStepId);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private Contract findMostRecentClosedMainAiContract(ContractRegistry registry) {
        Contract latest = null;
        try {
            for (Contract c : registry.getClosedContractsCopy()) {
                if (c.assignedTo != ContractAssignee.MAIN_AI) continue;
                if (latest == null || c.lastActivityAt > latest.lastActivityAt) {
                    latest = c;
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return latest;
    }

    /**
     * Plan A (2026-05-26 v3.2): escalating notice text appended to the
     * rejection. By the 3rd consecutive rejection the supervisor is told the
     * Liveness Guard will take over with system auto-dispatch.
     */
    private String buildEscalationNotice(int attempt) {
        if (attempt <= 1) {
            return "";
        }
        if (attempt == 2) {
            return "\n【⚠️ 连续第 " + attempt + " 次 wait 被拒】"
                    + "再 wait 一轮,Pair Liveness 守护会判定为持续死锁,暂停 plan 并升级给人工。";
        }
        return "\n【🚨 连续第 " + attempt + " 次 wait 被拒】"
                + "Pair Liveness 守护即将判定监督者卡死 —— 会暂停当前 plan 并升级给人工介入(不再凭空代派主 AI)。"
                + "本轮如果还不 emit_action(inject_prompt / complete_plan),plan 将被挂起等待用户。";
    }

    /**
     * v3.1 (2026-05-26): send a system-role event back to the supervisor to
     * surface a rejected action. Supervisor sees this on its next turn and
     * is expected to re-emit a valid action.
     */
    private void sendActionRejectionToSupervisor(String reason, String suggestion) {
        com.github.claudecodegui.bridge.SupervisorBridge bridge = pair.getSupervisorBridge();
        if (bridge == null) {
            LOG.warn("[ActionRouter] sendActionRejectionToSupervisor: no SupervisorBridge for pair " + pair.getPairId());
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "action_rejected");
        event.addProperty("reason", reason);
        if (suggestion != null) event.addProperty("suggestion", suggestion);
        event.addProperty("ts", System.currentTimeMillis());
        try {
            bridge.postEvent(event, "system");
            LOG.warn("[ActionRouter] " + pair.getPairId() + " action rejected → supervisor: " + reason);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] sendActionRejectionToSupervisor postEvent failed: " + e.getMessage());
        }
        // Record a coordinator event so the operator sees the rejection in the strip.
        try {
            PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "action_rejected",
                        "Action rejected: " + reason,
                        null);
            }
        } catch (Exception ignored) { /* best-effort */ }
    }

    /**
     * 2026-05-31: decide whether a human-facing escalation must block the user
     * with a modal dialog (vs. a non-blocking toast). True when:
     * <ul>
     *   <li>the supervisor explicitly set {@code payload.blocking};</li>
     *   <li>the decision category is {@code C3}; or</li>
     *   <li>(fallback heuristic) the escalation carries an explicit
     *       {@code choices[]} list — "pick one" only makes sense as a blocking
     *       prompt, so a choice list implies the user must answer.</li>
     * </ul>
     */
    private boolean isBlockingEscalation(JsonObject payload) {
        if (payload == null) return false;
        if (payload.has("blocking") && !payload.get("blocking").isJsonNull()) {
            return payload.get("blocking").getAsBoolean();
        }
        if (payload.has("category") && !payload.get("category").isJsonNull()
                && "C3".equals(payload.get("category").getAsString())) {
            return true;
        }
        return payload.has("choices") && payload.get("choices").isJsonArray()
                && payload.getAsJsonArray("choices").size() > 0;
    }

    /**
     * 2026-05-31: surface a blocking escalation as a modal decision dialog.
     * Bumps the escalate counter and attaches the latest progress snapshot
     * (stats/steps) so the dialog can show the session summary before the user
     * decides. Shared by the {@code escalate_to_human} and aliased
     * {@code record_alert} dispatch branches.
     */
    private void routeEscalateAsModal(JsonObject action) {
        pair.getProgressManager().incrementCounter("escalate_count");
        JsonObject snapshot = pair.getProgressManager().snapshot();
        if (snapshot.has("stats")) action.add("stats", snapshot.get("stats"));
        if (snapshot.has("steps")) action.add("steps", snapshot.get("steps"));
        webview.onEscalate(action);
    }

    /**
     * Protocol v2 (2026-05-24): non-blocking alert dispatch (replaces modal
     * escalation for autonomy-mode C1/C2 decisions). Pushes a webview toast
     * with severity + category + fallback_choice so the user can audit later
     * without supervisor pausing the plan.
     */
    private void dispatchAlertFromAction(JsonObject action, JsonObject payload) {
        pair.getProgressManager().incrementCounter("alert_count");
        JsonObject alertPayload = new JsonObject();
        alertPayload.addProperty("pairId", pair.getPairId());
        alertPayload.addProperty("supervisorId", pair.getAgentId());
        if (action != null && action.has("reason")) {
            alertPayload.add("reason", action.get("reason"));
        }
        if (payload != null) {
            if (payload.has("severity")) alertPayload.add("severity", payload.get("severity"));
            if (payload.has("category")) alertPayload.add("category", payload.get("category"));
            if (payload.has("fallback_choice")) alertPayload.add("fallback_choice", payload.get("fallback_choice"));
            if (payload.has("question")) alertPayload.add("question", payload.get("question"));
        }
        alertPayload.addProperty("ts", System.currentTimeMillis());
        try {
            webview.onPairAlert(alertPayload);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onPairAlert failed: " + e.getMessage());
        }
        // Phase 6 (2026-05-24): T1 — alerts are inflection points worth a plan
        // re-check. Fire replan_due("after_alert") so the supervisor's next
        // tick considers whether the rest of the plan still makes sense.
        if (pair.getEventBus() != null) {
            try {
                PairBudgetTracker bt = pair.getBudgetTracker();
                int stepsCompleted = bt != null ? bt.getStepsCompleted() : 0;
                pair.getEventBus().publishReplanDue("after_alert", stepsCompleted);
            } catch (Exception e) {
                LOG.debug("[ActionRouter] after_alert replan publish failed: " + e.getMessage());
            }
        }
    }

    /**
     * Contract State Machine v3 (2026-05-25): called by {@link PairHandler}
     * when the webview posts {@code pair_directive_ack}. Delegates to the
     * pair's {@link ContractRegistry} so its deadline timer is cancelled
     * (for "applied"/"failed") or status flipped to RECEIVED (for
     * "received"). Status is one of "received" | "applied" | "failed".
     *
     * <p>Legacy DirectiveTracker call is gone — registry is now the
     * single source of truth for outstanding contract state.
     */
    public void markDirectiveAcked(String directiveId, String status) {
        if (directiveId == null || directiveId.isEmpty()) return;
        ContractRegistry registry = pair.getContractRegistry();
        if (registry == null) return;
        boolean handled;
        if ("received".equals(status)) {
            handled = registry.markReceived(directiveId, null);
        } else if ("applied".equals(status) || "failed".equals(status)) {
            handled = registry.discharge(directiveId,
                    "failed".equals(status) ? "main AI reported failure" : null);
            // Plan SM: the discharge may free the plan to move on.
            PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm != null && handled) {
                Contract c = registry.findById(directiveId);
                String stepId = c != null ? c.parentStepId : null;
                sm.onContractDischarged(directiveId, stepId);
                // Bug fix 2026-05-26: discharge typically fires AFTER handleStreamEnd
                // (ack arrives after the main-AI turn has already ended). In that
                // case, handleStreamEnd's onTurnEnded saw hasOpenContracts=true and
                // moved the plan to PENDING_DISCHARGE. Now that the contract is
                // gone, we must recompute hasOpenContracts and re-fire onTurnEnded
                // so the plan transitions to PENDING_DECISION — otherwise the plan
                // sits in PENDING_DISCHARGE forever and the supervisor (now woken
                // by TransitionDispatcher) sees stale state and may hallucinate
                // "already dispatched, waiting" instead of choosing the next action.
                boolean hasOpenMainAi = false;
                for (Contract other : registry.getOpenContracts()) {
                    if (other.assignedTo == ContractAssignee.MAIN_AI) {
                        hasOpenMainAi = true;
                        break;
                    }
                }
                if (!hasOpenMainAi) {
                    sm.onTurnEnded(ContractAssignee.MAIN_AI, false);
                }
            }
        } else {
            return;
        }
        // Successfully handled — retryCounters from the legacy fast-retry path
        // was removed with DirectiveTracker (Stage B.3.1).
    }

    /**
     * Resolve the human-readable prompt string from a structured payload
     * (inlinePrompt > prompt > spilledPath stub). Used by
     * {@link #deliverContract} when routing a Contract to the webview.
     */
    private String resolvePromptFromPayload(JsonObject payload) {
        if (payload.has("inlinePrompt") && !payload.get("inlinePrompt").isJsonNull()
                && !payload.get("inlinePrompt").getAsString().isEmpty()) {
            return payload.get("inlinePrompt").getAsString();
        }
        if (payload.has("prompt") && !payload.get("prompt").isJsonNull()) {
            return payload.get("prompt").getAsString();
        }
        if (payload.has("spilledPath") && !payload.get("spilledPath").isJsonNull()) {
            String spilled = payload.get("spilledPath").getAsString();
            String objective = payload.has("objective") && !payload.get("objective").isJsonNull()
                    ? payload.get("objective").getAsString() : "(no objective)";
            return "请按 `" + spilled + "` 中的完整指令执行(objective: " + objective + ")";
        }
        return "";
    }

    /**
     * v3: surface each self-decision record as its own webview card so the user
     * can scrub through the supervisor's micro-judgments after the session,
     * and bump the running counters so the verification dialog reflects them.
     *
     * <p>Records are pushed individually (rather than as one combined payload)
     * so the existing right-pane event log keeps its "one card per row"
     * cadence — the renderer just needs to recognize the new {@code kind}.
     */
    private void dispatchDecisions(JsonObject payload) {
        if (payload == null || !payload.has("decisions") || !payload.get("decisions").isJsonArray()) {
            return;
        }
        JsonArray decisions = payload.getAsJsonArray("decisions");
        for (JsonElement el : decisions) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject d = el.getAsJsonObject();

            pair.getProgressManager().incrementCounter("decision_count");
            boolean reviewFlag = d.has("review_flag")
                    && !d.get("review_flag").isJsonNull()
                    && d.get("review_flag").getAsBoolean();
            if (reviewFlag) {
                pair.getProgressManager().incrementCounter("decision_review_flag_count");
            }

            JsonObject evt = new JsonObject();
            evt.addProperty("kind", "decision_record");
            evt.addProperty("pairId", pair.getPairId());
            evt.addProperty("supervisorId", pair.getAgentId());
            evt.add("decision", d);
            try {
                webview.onActionEvent(evt);
            } catch (Exception e) {
                LOG.warn("[ActionRouter] decision push failed: " + e.getMessage());
            }
        }
    }

    private void handleInjectPrompt(JsonObject payload, long delaySeconds, JsonObject actionWrapper) {
        // Route C check (empty payload) already happened at dispatch() entry;
        // this defensive duplicate exists only for callers that bypass dispatch().
        String prompt = resolvePromptFromPayload(payload);
        if (prompt == null || prompt.isEmpty()) {
            LOG.warn("[ActionRouter] handleInjectPrompt invoked with empty payload — caller bypassed dispatch() validation");
            return;
        }

        // Contract State Machine v3 (2026-05-25): all inject_prompt deliveries
        // now flow through ContractRegistry. The registry's onIssued listener
        // (wired in attachContractRegistryListener) handles actual webview
        // delivery, so we just describe the work as a Contract here.
        ContractRegistry registry = pair.getContractRegistry();
        if (registry == null) {
            // Fallback: registry not wired (legacy path / synthetic tests).
            // Push direct so the user doesn't lose the inject; render the
            // action card alongside since we won't get a registry callback.
            LOG.warn("[ActionRouter] no ContractRegistry — falling back to direct push");
            if (actionWrapper != null) {
                try { webview.onActionEvent(actionWrapper); } catch (Exception e) {
                    LOG.warn("[ActionRouter] fallback onActionEvent push failed: " + e.getMessage());
                }
            }
            String fallbackId = payload.has("directiveId") && !payload.get("directiveId").isJsonNull()
                    ? payload.get("directiveId").getAsString() : null;
            if (delaySeconds <= 0) {
                invokeInjectOnWebview(fallbackId, prompt);
            } else {
                scheduler.schedule(() -> {
                    if (!pair.isDisposed()) invokeInjectOnWebview(fallbackId, prompt);
                }, delaySeconds, TimeUnit.SECONDS);
            }
            return;
        }

        String directiveId = payload.has("directiveId") && !payload.get("directiveId").isJsonNull()
                ? payload.get("directiveId").getAsString() : null;
        String stepId = ensurePlanAndStep(payload, prompt);
        long deadlineMs = payload.has("deadlineMs") && !payload.get("deadlineMs").isJsonNull()
                ? payload.get("deadlineMs").getAsLong() : 0L;

        String preview = prompt.length() > 80
                ? prompt.substring(0, 80).replace('\n', ' ') + "…"
                : prompt.replace('\n', ' ');
        LOG.info("[INJECT_TRACE] ActionRouter.handleInjectPrompt"
                + " pair=" + pair.getPairId()
                + " directiveId=" + (directiveId != null ? directiveId : "(auto)")
                + " stepId=" + stepId
                + " delaySec=" + delaySeconds
                + " promptLen=" + prompt.length()
                + " preview=\"" + preview + "\"");

        Runnable doIssue = () -> {
            if (pair.isDisposed()) return;
            try {
                Contract issued = registry.issue(ContractIssueRequest.builder()
                        .contractIdHint(directiveId)
                        .parentStepId(stepId)
                        .type(ContractType.TASK_ASSIGNMENT)
                        .assignedTo(ContractAssignee.MAIN_AI)
                        .payloadJson(payload.toString())
                        .deadlineMs(deadlineMs)
                        .build());
                // Route A (2026-05-26): render the "已注入指令到主 AI" card
                // ONLY now that registry.issue() has accepted the contract.
                // This makes the UI a derivative of registry truth rather
                // than supervisor narration — if issuance never happens,
                // the user never sees a misleading dispatch confirmation.
                if (actionWrapper != null) {
                    try {
                        webview.onActionEvent(actionWrapper);
                    } catch (Exception ex) {
                        LOG.warn("[ActionRouter] deferred onActionEvent push failed: " + ex.getMessage());
                    }
                }
                PlanStateMachine sm = pair.getPlanStateMachine();
                if (sm != null) {
                    sm.onContractIssued(issued.id, stepId, ContractAssignee.MAIN_AI);
                }
            } catch (Exception e) {
                LOG.warn("[ActionRouter] registry.issue failed: " + e.getMessage());
                // Route A (2026-05-26): issuance failed — do NOT render the
                // success card; tell supervisor explicitly so its next turn
                // re-emits instead of waiting on a contract that doesn't exist.
                sendActionRejectionToSupervisor(
                        "inject_prompt rejected: ContractRegistry.issue() 抛异常 — " + e.getMessage(),
                        "本轮重试 emit_action(inject_prompt, ...);如果反复失败,emit_action(escalate_to_human)。");
            }
        };
        if (delaySeconds <= 0) {
            doIssue.run();
        } else {
            scheduler.schedule(doIssue, delaySeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Contract State Machine v3 (2026-05-25): synthetic plan/step bootstrap.
     * The supervisor doesn't yet emit explicit plan_create / step_create
     * actions, so we auto-create a single-step plan on first inject_prompt
     * and append a new step for each subsequent inject_prompt. This gives
     * the PlanStateMachine + DeadlockGuard the structure they need to
     * monitor PENDING_DISCHARGE without requiring supervisor prompt changes.
     *
     * <p>Future work: replace this with explicit supervisor-driven plan
     * management once the supervisor prompt grows save_plan / save_step MCP
     * tools that emit plan transitions directly.
     */
    private String ensurePlanAndStep(JsonObject payload, String prompt) {
        PlanStateMachine sm = pair.getPlanStateMachine();
        if (sm == null) {
            return "step_synthetic_" + System.currentTimeMillis();
        }
        Plan plan = sm.getCurrent();
        if (plan == null) {
            // First inject_prompt: bootstrap a one-step plan.
            java.util.List<PlanStep> steps = new java.util.ArrayList<>();
            steps.add(PlanStep.create("auto", 0, summarizeTitle(payload, prompt), PlanStep.StepOwner.MAIN_AI));
            sm.onPlanCreated(steps, null);
            plan = sm.getCurrent();
        }
        PlanStep current = plan.getCurrentStep();
        // If the current step is already DONE/SKIPPED, append a new step.
        if (current != null
                && (current.status == PlanStep.StepStatus.DONE
                || current.status == PlanStep.StepStatus.SKIPPED)) {
            int next = plan.steps.size();
            PlanStep added = PlanStep.create("auto", next, summarizeTitle(payload, prompt), PlanStep.StepOwner.MAIN_AI);
            plan.steps.add(added);
            plan.currentStepIndex = next;
            current = added;
        }
        return current != null ? current.id : "step_synthetic_" + System.currentTimeMillis();
    }

    private String summarizeTitle(JsonObject payload, String prompt) {
        if (payload.has("objective") && !payload.get("objective").isJsonNull()) {
            String obj = payload.get("objective").getAsString();
            if (obj.length() > 80) return obj.substring(0, 80);
            return obj;
        }
        if (prompt.length() <= 60) return prompt;
        return prompt.substring(0, 60).replace('\n', ' ') + "…";
    }

    /**
     * Push the prompt to the webview. Uses the V2 signature when a directive
     * id is available so the webview can post a matching ack; falls back to
     * the legacy 3-arg form when there is no id (e.g. test paths or daemons
     * that predate v2). On dispatch failure we DON'T cancel the directive —
     * letting it time out naturally surfaces directive_lost to the supervisor.
     *
     * <p>2026-05-25: if the webview hasn't signalled readiness yet, buffer
     * the push instead of firing — {@link #markWebviewReady} drains the
     * buffer once the IPC arrives. This prevents a cold-start dispatch from
     * being lost when the React tree hasn't mounted yet.
     */
    private void invokeInjectOnWebview(String directiveId, String prompt) {
        if (!webviewReady) {
            if (pendingInjectsBeforeReady.size() >= MAX_PENDING_INJECTS_BEFORE_READY) {
                LOG.warn("[INJECT_TRACE] ActionRouter.bufferOverflow"
                        + " pair=" + pair.getPairId()
                        + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                        + " — pre-ready buffer full ("
                        + MAX_PENDING_INJECTS_BEFORE_READY + "), dropping oldest");
                pendingInjectsBeforeReady.pollFirst();
            }
            pendingInjectsBeforeReady.offerLast(new PendingInject(directiveId, prompt));
            LOG.info("[INJECT_TRACE] ActionRouter.bufferedBeforeReady"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                    + " bufferSize=" + pendingInjectsBeforeReady.size());
            return;
        }
        pushInjectNow(directiveId, prompt);
    }

    private void pushInjectNow(String directiveId, String prompt) {
        try {
            // 2026-05-24 (Q4 trace): record the push attempt. The webview-side
            // counterpart logs `[INJECT_TRACE] webview onPairInjectPrompt`. If
            // this line shows here but the webview line never appears, the
            // JBCef bridge dropped the call.
            LOG.info("[INJECT_TRACE] ActionRouter→webview push"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                    + " v2=" + (directiveId != null && !directiveId.isEmpty()));
            // Append the report mandate to the task itself (in the main AI's
            // immediate context), not just the system prompt. Empirically the
            // system-prompt rule was applied inconsistently across parallel
            // workflow nodes (one reported, one didn't); putting the instruction
            // in the dispatched task makes report_turn_completion reliable.
            String finalPrompt = withReportMandate(prompt);
            if (directiveId != null && !directiveId.isEmpty()) {
                webview.onInjectPromptV2(pair.getPairId(), pair.getAgentId(), directiveId, finalPrompt);
            } else {
                webview.onInjectPrompt(pair.getPairId(), pair.getAgentId(), finalPrompt);
            }
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onInjectPrompt(V2) failed: " + e.getMessage());
        }
    }

    /**
     * Sentinel that marks an already-appended mandate, so re-pushes / retries
     * don't stack it. Must stay in sync with the suffix in {@link #REPORT_MANDATE}.
     */
    private static final String REPORT_MANDATE_SENTINEL = "[系统要求] 本轮任务完成后必须上报";

    /**
     * Mandatory tail appended to every supervisor→main-AI dispatched task. Keeps
     * report_turn_completion reliable even when the model would otherwise treat a
     * pure analysis/read turn as exempt. The main AI only has this tool when the
     * pair-context marker mounted mcp__main (see SessionSendService /
     * ClaudeMessageHandler windowId fallback); when present it must be called.
     */
    private static final String REPORT_MANDATE =
            "\n\n---\n" + REPORT_MANDATE_SENTINEL + ":\n"
            + "本轮无论是分析 / 读取 / 调研还是编码,完成后你**必须**调用 "
            + "`mcp__main__report_turn_completion` 工具上报本轮结果"
            + "(summary 写任务级结论;deliverables 写新增/修改或被分析的文件,没有改文件则给空数组;selfAssessment 必填)。"
            + "**禁止**仅以纯文本结论结尾而不调用该工具——否则 supervisor 收不到结构化回执,无法 review。";

    /** Idempotently append the report mandate to a dispatched task prompt. */
    private String withReportMandate(String prompt) {
        String p = prompt == null ? "" : prompt;
        if (p.contains(REPORT_MANDATE_SENTINEL)) return p;
        return p + REPORT_MANDATE;
    }

    /**
     * 2026-05-25: called by {@link com.github.claudecodegui.handler.PairHandler}
     * when the webview posts {@code pair_webview_ready}. Flips the readiness
     * gate and drains any injects that were buffered while we were waiting.
     * Idempotent — subsequent calls drain the buffer (covers webview reload
     * where state was cleared but the Java pair is still alive).
     */
    /** Whether {@code pair_webview_ready} has fired for this pair's webview. */
    public boolean isWebviewReady() {
        return webviewReady;
    }

    public void markWebviewReady() {
        webviewReady = true;
        if (pendingInjectsBeforeReady.isEmpty()) {
            LOG.info("[INJECT_TRACE] ActionRouter.webviewReady"
                    + " pair=" + pair.getPairId() + " bufferEmpty=true");
            return;
        }
        int drained = 0;
        PendingInject p;
        while ((p = pendingInjectsBeforeReady.pollFirst()) != null) {
            drained++;
            pushInjectNow(p.directiveId, p.prompt);
        }
        LOG.info("[INJECT_TRACE] ActionRouter.webviewReady"
                + " pair=" + pair.getPairId() + " drained=" + drained);
    }

    private static final class PendingInject {
        final String directiveId;
        final String prompt;
        PendingInject(String directiveId, String prompt) {
            this.directiveId = directiveId;
            this.prompt = prompt;
        }
    }

    /** Stub bridge for testing / pre-wiring. */
    private static final class NoopWebviewBridge implements WebviewBridge {
        static final NoopWebviewBridge INSTANCE = new NoopWebviewBridge();
        @Override public void onActionEvent(JsonObject event) { }
        @Override public void onInjectPrompt(String pairId, String supervisorId, String prompt) { }
        @Override public void onEscalate(JsonObject payload) { }
    }
}
