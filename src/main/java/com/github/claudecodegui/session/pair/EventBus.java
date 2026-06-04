package com.github.claudecodegui.session.pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Forwards filtered main-AI events to the Supervisor session and dispatches
 * the resulting ACTION to the {@link ActionRouter}.
 *
 * <p>Decoupling note: the event bus owns no state about how main-AI events
 * are produced. Production code (e.g. {@code ClaudeMessageHandler#onComplete})
 * calls {@link #publish(JsonObject)} with a structured event object. Tests and
 * Phase A wiring can also call these publish methods directly.
 *
 * <p>All event forwarding happens on a single-thread executor so multiple
 * sources never interleave a Supervisor turn.
 */
public class EventBus {

    private static final Logger LOG = Logger.getInstance(EventBus.class);

    /** Stable marker thrown by the daemon when its in-memory supervisor runtime
     *  is missing (typically because the Node process was restarted after a
     *  remote-mode crash). Matched here to trigger a lazy supervisor.start. */
    private static final String NOT_FOUND_MARKER = "SUPERVISOR_NOT_FOUND";

    /**
     * 2026-05-25 (FUNDAMENTAL FIX): wall-clock cap on supervisor round-trips
     * was REMOVED. Previous tries (120s → 360s → ...) just shifted at what
     * duration a legitimately-slow turn was misidentified as stuck. The
     * daemon-side cap was removed in the same change (see supervisor-channel.js).
     *
     * <p>Liveness of a supervisor turn is now detected via three orthogonal
     * signals, NONE of which are wall-clock:
     * <ul>
     *   <li>SDK-internal API/network timeouts — Anthropic SDK throws on its
     *       own transport failures; we surface them as ordinary errors.</li>
     *   <li>Manual interrupt — UI Stop button → {@code SupervisorBridge.interrupt()}
     *       → daemon {@code supervisor.interrupt} → {@code runtime.query.interrupt()};
     *       the in-flight {@code query.next()} settles cleanly.</li>
     *   <li>Daemon process death — {@link SupervisorBridge}'s IPC layer
     *       observes stdout EOF and rejects pending futures with a
     *       connection error; the future.get() below sees that as a normal
     *       ExecutionException and falls through to the generic error path.</li>
     * </ul>
     *
     * <p>If a turn appears stuck the operator clicks Stop. We no longer
     * pretend a fixed duration tells us anything reliable about liveness.
     */
    @SuppressWarnings("unused") // retained as documentation anchor / referenced by old comments
    private static final long POST_EVENT_TIMEOUT_SEC = 0;

    /** Stable marker for our own (Java-side) round-trip timeout. Retained so any
     *  log/triage docs that grep for it still resolve to this class; the
     *  marker is no longer emitted by tryPostEvent (no time cap). */
    @SuppressWarnings("unused")
    private static final String POST_EVENT_TIMEOUT_MARKER = "SUPERVISOR_POST_EVENT_TIMEOUT";

    private static final long RESTART_TIMEOUT_SEC = 20;

    private final PairSession pair;
    private final ActionRouter router;
    private final Executor dispatcher;

    public EventBus(PairSession pair, ActionRouter router) {
        this.pair = pair;
        this.router = router;
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "supervisor-eventbus-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Publish a structured event. Filtered, summarized, sent to the
     * Supervisor; the resulting ACTION is routed via {@link ActionRouter}.
     *
     * <p>Phase 1 routing:
     * <ul>
     *   <li>If the pair has a {@link SupervisorMonitor} wired AND the
     *       {@code cc-gui.pair.monitor.enabled} flag is true, the event is
     *       enqueued onto {@link EventCollector} and the call returns
     *       immediately. The monitor's next periodic tick (or urgent
     *       wakeup) consumes the buffer.</li>
     *   <li>Otherwise the legacy synchronous dispatcher path is used:
     *       a single-thread executor calls {@link #forward(JsonObject)}.</li>
     * </ul>
     *
     * <p>The returned future completes once the dispatch decision is made
     * (immediate for collector path). Callers usually do not await — fire-and-forget is fine.
     */
    public CompletableFuture<Void> publish(JsonObject event) {
        if (pair.isDisposed()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!EventFilter.shouldForward(event)) {
            return CompletableFuture.completedFuture(null);
        }
        if (isMonitorPathEnabled()) {
            // 2026-05-25: for user-initiated events (the user just typed a
            // message), eagerly signal "thinking" so the UI shows feedback
            // within ~50ms instead of waiting for the urgent tick (~1s) to
            // call forward() → signalThinking. This collapses perceived
            // latency at the most important moment (first message after
            // IDE startup). signalThinking(false) is still cleared by the
            // eventual forward() → finally block, so no stale-on risk.
            if (isUserInitiatedEvent(event)) {
                try { router.signalThinking(true); }
                catch (Exception ignored) { /* best-effort */ }
            }
            try {
                pair.getEventCollector().publish(event);
            } catch (Exception e) {
                LOG.warn("[EventBus] collector.publish failed: " + e.getMessage());
            }
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> event, dispatcher)
                .thenCompose(this::forward)
                .exceptionally(err -> {
                    LOG.warn("[EventBus] forwarding failed: " + err.getMessage());
                    return null;
                });
    }

    /**
     * Phase 1: invoked by {@link SupervisorMonitor#doTick} to dispatch a
     * composite event built from a drained batch. Reuses the existing
     * {@link #forward(JsonObject)} logic so transport-error reporting +
     * lazy supervisor restart on NOT_FOUND continue to work.
     *
     * <p>Runs on the monitor's scheduler thread (NOT on {@link #dispatcher}),
     * because in monitor mode the dispatcher is otherwise idle.
     */
    public CompletableFuture<Void> forwardComposite(JsonObject compositeEvent) {
        if (pair.isDisposed()) {
            return CompletableFuture.completedFuture(null);
        }
        return forward(compositeEvent);
    }

    /**
     * 2026-05-25: an event is "user-initiated" when the human is actively
     * waiting on the UI for feedback right now — typed message or modal
     * choice. These warrant eager UI affordance (thinking spinner) even
     * before the monitor's urgent tick fires.
     */
    private static boolean isUserInitiatedEvent(JsonObject event) {
        if (event == null || !event.has("type") || event.get("type").isJsonNull()) return false;
        String type = event.get("type").getAsString();
        return "user_input".equals(type) || "human_response".equals(type);
    }

    /** True when a supervisor postEvent error looks like an API rate limit (429). */
    private static boolean is429(String err) {
        if (err == null) return false;
        String lower = err.toLowerCase();
        return lower.contains("429") || lower.contains("rate_limit") || lower.contains("rate limit");
    }

    /**
     * Returns true if the periodic monitor path should handle publishes. The
     * flag is read every call so it can be toggled at runtime via the Registry
     * (Help → Find Action → "Registry") or via the
     * {@code cc-gui.pair.monitor.enabled} system property.
     */
    private boolean isMonitorPathEnabled() {
        if (!pair.isMonitorEnabled()) return false;
        return MonitorFeatureFlag.isEnabled();
    }

    /** Convenience: turn_end with tool use list. Legacy 6-arg signature delegates
     *  to the 7-arg overload with assistantText=null for backward compatibility. */
    public CompletableFuture<Void> publishTurnEnd(int stepIndex, String stepTitle,
                                                  List<ToolUseRecord> toolUses,
                                                  List<String> modifiedInPlan,
                                                  List<String> modifiedOffPlan,
                                                  long durationMs) {
        return publishTurnEnd(stepIndex, stepTitle, toolUses, modifiedInPlan,
                modifiedOffPlan, durationMs, null);
    }

    /** Phase 0 (2026-05-24): includes the main AI's natural-language reply
     *  (truncated by the caller) so the supervisor sees manifest YAML / summary
     *  text that previously was invisible through toolUses+modifiedFiles alone. */
    public CompletableFuture<Void> publishTurnEnd(int stepIndex, String stepTitle,
                                                  List<ToolUseRecord> toolUses,
                                                  List<String> modifiedInPlan,
                                                  List<String> modifiedOffPlan,
                                                  long durationMs,
                                                  String assistantText) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (stepTitle != null) payload.addProperty("stepTitle", stepTitle);
        if (toolUses != null) {
            JsonArray arr = new JsonArray();
            for (ToolUseRecord t : toolUses) arr.add(t.toJson());
            payload.add("toolUses", arr);
        }
        if (modifiedInPlan != null) payload.add("modifiedFilesInPlan", strArray(modifiedInPlan));
        if (modifiedOffPlan != null) payload.add("modifiedFilesOffPlan", strArray(modifiedOffPlan));
        payload.addProperty("durationMs", durationMs);
        if (assistantText != null && !assistantText.isEmpty()) {
            payload.addProperty("assistantText", assistantText);
        }

        return publish(makeEvent("turn_end", payload));
    }

    public CompletableFuture<Void> publishError(int stepIndex, String code, Integer status,
                                                Integer retryAfter, String message, int retryCount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (code != null) payload.addProperty("code", code);
        if (status != null) payload.addProperty("status", status);
        if (retryAfter != null) payload.addProperty("retryAfter", retryAfter);
        if (message != null) payload.addProperty("message", message);
        payload.addProperty("retryCount", retryCount);
        return publish(makeEvent("error", payload));
    }

    public CompletableFuture<Void> publishOffPlan(int stepIndex, List<String> files) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (files != null) payload.add("files", strArray(files));
        return publish(makeEvent("off_plan_detected", payload));
    }

    public CompletableFuture<Void> publishStart(int currentStep, int totalSteps, String stepTitle) {
        JsonObject payload = new JsonObject();
        payload.addProperty("currentStep", currentStep);
        payload.addProperty("totalSteps", totalSteps);
        if (stepTitle != null) payload.addProperty("currentStepTitle", stepTitle);
        return publish(makeEvent("start", payload));
    }

    public CompletableFuture<Void> publishVerifyResult(int stepIndex, String command,
                                                       boolean pass, String stderr, int attempt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (command != null) payload.addProperty("command", command);
        payload.addProperty("pass", pass);
        if (stderr != null) payload.addProperty("stderr", stderr);
        payload.addProperty("attempt", attempt);
        return publish(makeEvent("verify_result", payload));
    }

    public CompletableFuture<Void> publishHumanResponse(String choice, String note) {
        JsonObject payload = new JsonObject();
        if (choice != null) payload.addProperty("choice", choice);
        if (note != null) payload.addProperty("note", note);
        return publish(makeEvent("human_response", payload));
    }

    /**
     * Free-form user message addressed to the Supervisor (NOT to the main AI).
     * Sent when the user types into the right-pane composer.
     */
    public CompletableFuture<Void> publishUserInput(String text) {
        // 2026-05-28: if the user paused the supervisor (plan WAITING with
        // pauseReason=user), this follow-up message is the resume signal. Flip
        // the plan back to ACTIVE BEFORE forwarding, so the supervisor's
        // resulting emit_action is routed against an ACTIVE plan rather than a
        // WAITING one. No-op when the plan wasn't user-paused.
        com.github.claudecodegui.session.pair.plan.PlanStateMachine sm = pair.getPlanStateMachine();
        if (sm != null && sm.onUserResumed()) {
            // Resume actually flipped WAITING→ACTIVE — push a fresh snapshot so
            // the webview re-enables the Stop button without waiting for the
            // 30s periodic push.
            PairStatusPusher pusher = pair.getStatusPusher();
            if (pusher != null) pusher.pushHard();
        }
        JsonObject payload = new JsonObject();
        if (text != null) payload.addProperty("text", text);
        return publish(makeEvent("user_input", payload));
    }

    // ============================================================================
    // Protocol v2 (2026-05-24): structured turn report + subagent visibility +
    // budget tracking + directive ack timeout.
    //
    // These publishers wrap typed payloads sourced from daemon NDJSON lines so
    // the rest of the pipeline (EventCollector → SupervisorMonitor → composite
    // → event-summarizer.js) does not need to know the new schema.
    // ============================================================================

    /**
     * Publish a structured TURN_REPORT received from the main-AI runtime's
     * {@code report_turn_completion} MCP tool. Carries deliverables /
     * verifications / selfAssessment so the supervisor can do confidence-based
     * review triage without parsing free-form assistantText.
     *
     * <p>{@code payload} is the inner JSON object from the daemon's
     * {@code [TURN_REPORT]} line (i.e. the {@code payload} field of the
     * envelope, NOT the envelope itself).
     */
    public CompletableFuture<Void> publishTurnReport(JsonObject reportPayload) {
        if (reportPayload == null) return CompletableFuture.completedFuture(null);
        return publish(makeEvent("turn_report", reportPayload));
    }

    /**
     * Publish a structured SUBAGENT_STOP from the main-AI runtime's
     * SubagentStop hook. Lets the supervisor observe what the main AI's
     * Task subagent did without breaking subagent context isolation.
     */
    public CompletableFuture<Void> publishSubagentStop(JsonObject stopPayload) {
        if (stopPayload == null) return CompletableFuture.completedFuture(null);
        return publish(makeEvent("subagent_stop", stopPayload));
    }

    /**
     * Notify the supervisor that the pair's budget has crossed the 80% warn
     * threshold. Fires at most once per pair (PairBudgetTracker enforces).
     * Supervisor may respond by trimming non-critical remaining steps.
     */
    public CompletableFuture<Void> publishBudgetWarning(double tokenRatio,
                                                        double durationRatio,
                                                        double stepRatio,
                                                        double subagentRatio,
                                                        double maxRatio) {
        JsonObject payload = new JsonObject();
        payload.addProperty("tokenRatio", tokenRatio);
        payload.addProperty("durationRatio", durationRatio);
        payload.addProperty("stepRatio", stepRatio);
        payload.addProperty("subagentRatio", subagentRatio);
        payload.addProperty("maxRatio", maxRatio);
        return publish(makeEvent("budget_warning", payload));
    }

    /**
     * Notify the supervisor that the pair's budget has crossed 100%. The pair
     * will be paused by the Java side immediately after this fires — supervisor
     * should not dispatch new directives, only wrap up.
     */
    public CompletableFuture<Void> publishBudgetExceeded(double maxRatio) {
        JsonObject payload = new JsonObject();
        payload.addProperty("maxRatio", maxRatio);
        return publish(makeEvent("budget_exceeded", payload));
    }

    /**
     * Notify the supervisor that a directive sent {@code timeoutMs} ago has
     * not been acknowledged by the main AI. Supervisor decides whether to
     * retry, skip the step, or escalate.
     */
    public CompletableFuture<Void> publishDirectiveLost(String directiveId,
                                                        String lastObjective,
                                                        long timeoutMs) {
        JsonObject payload = new JsonObject();
        if (directiveId != null) payload.addProperty("directiveId", directiveId);
        if (lastObjective != null) payload.addProperty("lastObjective", lastObjective);
        payload.addProperty("timeoutMs", timeoutMs);
        return publish(makeEvent("directive_lost", payload));
    }

    // ============================================================================
    // Phase 6 (2026-05-24): autonomy control layer events.
    //
    // step_blocked: F2 — N consecutive directive_lost without an intervening
    //               approve_and_continue. Supervisor should mark step blocked +
    //               continue to next step rather than keep retrying.
    // replan_due:   T1 — periodic (every 5 approved steps) OR after a
    //               record_alert. Hints supervisor to call save_plan(source=replan)
    //               and refresh its working plan before continuing.
    // ============================================================================

    /**
     * F2: tell the supervisor that {@code failureCount} consecutive
     * inject_prompt directives went unacknowledged. Recommendation is always
     * "skip_step" — the Java side has already reset the counter. Supervisor
     * is free to ignore (and try yet another directive) if it has reason.
     */
    public CompletableFuture<Void> publishStepBlocked(int failureCount, String lastObjective) {
        JsonObject payload = new JsonObject();
        payload.addProperty("failureCount", failureCount);
        payload.addProperty("recommendation", "skip_step");
        if (lastObjective != null) payload.addProperty("lastObjective", lastObjective);
        return publish(makeEvent("step_blocked", payload));
    }

    /**
     * T1: tell the supervisor it is time to self-evaluate its plan. {@code trigger}
     * is "periodic" (every N approved steps) or "after_alert" (right after a
     * record_alert). Supervisor typically responds by dispatching a planner
     * subagent and calling save_plan(source="replan") if changes are needed.
     */
    public CompletableFuture<Void> publishReplanDue(String trigger, int stepsCompleted) {
        JsonObject payload = new JsonObject();
        if (trigger != null) payload.addProperty("trigger", trigger);
        payload.addProperty("stepsCompleted", stepsCompleted);
        return publish(makeEvent("replan_due", payload));
    }

    // ====================== internal ======================

    private CompletableFuture<Void> forward(JsonObject event) {
        // Tell the right pane "Supervisor is thinking" before we block waiting
        // for daemon → SDK round-trip. ActionRouter.dispatch will clear it.
        try { router.signalThinking(true); } catch (Exception ignored) { /* best-effort */ }
        // Mark a supervisor turn in-flight so WebviewWatchdog grants the
        // streaming (3min) timeout instead of the normal 45s — supervisor
        // IPC bursts otherwise look like a webview stall.
        pair.enterTurn();
        try {
            String firstError = tryPostEvent(event);
            if (firstError == null) {
                return CompletableFuture.completedFuture(null);
            }

            // Quota-reset auto-resume (RateLimitWatcher): a 429 / rate_limit
            // transport error → schedule a backoff auto-resume (5/10/20/40/60min,
            // then hourly). Self-clears once a turn succeeds (ActionRouter.dispatch
            // → onSupervisorTurnText → onNormalTurn).
            if (is429(firstError)) {
                try { pair.getRateLimitWatcher().on429(); }
                catch (Exception ignored) { /* best-effort */ }
            }

            // If the failure is "daemon-side runtime missing" (e.g. remote
            // service restarted while we still hold the PairSession), re-run
            // supervisor.start with the original parameters and retry once.
            if (firstError.contains(NOT_FOUND_MARKER)) {
                LOG.info("[EventBus] supervisor runtime missing on daemon — lazy restart for pair "
                        + pair.getPairId());
                if (restartSupervisor()) {
                    String retryError = tryPostEvent(event);
                    if (retryError == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    pushTransportError("Supervisor 已自动恢复但本次重试仍失败: " + retryError);
                } else {
                    pushTransportError("Supervisor 服务暂时不可达,自动恢复失败,请稍后再发送。");
                }
            } else if (firstError.contains("SUPERVISOR_QUERY_TIMEOUT")) {
                // Only fires when the operator has explicitly set one of the
                // SUPERVISOR_QUERY_TIMEOUT_*_MS env knobs (emergency rollback).
                // Default deployments never reach this branch.
                LOG.warn("[EventBus] supervisor env-timeout for pair " + pair.getPairId() + ": " + firstError);
                pushTransportError("Supervisor 本轮被 env-rollback 超时打断 (" + firstError
                        + ")。下一条事件继续。");
            } else if (firstError.contains("SUPERVISOR_INTERRUPTED")) {
                // 2026-05-25: user clicked Stop. Not an error — just acknowledge.
                LOG.info("[EventBus] supervisor turn interrupted by user for pair " + pair.getPairId());
                pushTransportError("Supervisor 本轮已被用户手动中断。");
            } else {
                pushTransportError("Supervisor 调用失败: " + firstError);
            }
        } catch (Exception e) {
            LOG.warn("[EventBus] forward exception: " + e.getMessage());
            pushTransportError("Supervisor 调用异常: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            try { router.signalThinking(false); } catch (Exception ignored) { /* best-effort */ }
            pair.exitTurn();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Run one supervisor.postEvent round-trip. Returns null on success (action
     * already dispatched to the router); returns an error message string on
     * failure (caller decides what to do).
     *
     * <p>2026-05-25 (FUNDAMENTAL FIX): no wall-clock cap. We block until the
     * daemon returns OR the IPC layer rejects the future (daemon process
     * death). Liveness during legitimately-long turns (Task subagents,
     * autocompact) is the user's call via the Stop button →
     * {@code supervisor.interrupt} RPC. See {@link #POST_EVENT_TIMEOUT_SEC}
     * JavaDoc for the design rationale.
     */
    private String tryPostEvent(JsonObject event) {
        CompletableFuture<JsonObject> future = pair.getSupervisorBridge().postEvent(event);
        try {
            JsonObject actionWrapper = future.get();
            if (actionWrapper != null) {
                router.dispatch(actionWrapper);
                return null;
            }
            return "no action returned";
        } catch (Exception ex) {
            // CompletionException / ExecutionException unwrap: prefer the cause's
            // message which carries the daemon-side error text (set by SupervisorBridge).
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            LOG.warn("[EventBus] supervisor postEvent failed: " + msg);
            return msg;
        }
    }

    /**
     * Re-run supervisor.start on the daemon using the snapshot parameters
     * captured when the Pair was created. Synchronous (blocks the dispatcher
     * thread for up to {@link #RESTART_TIMEOUT_SEC} seconds).
     */
    private boolean restartSupervisor() {
        try {
            Boolean ok = pair.getSupervisorBridge()
                    .start(pair.getAgentName(),
                            pair.getAgentDescription(),
                            pair.getPlanContent(),
                            pair.getProjectSpec(),
                            pair.getModel(),
                            pair.getAutoCompactThreshold(),
                            pair.getReasoningEffort(),
                            pair.isMcpAccess())
                    .get(RESTART_TIMEOUT_SEC, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            LOG.warn("[EventBus] supervisor restart failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
    }

    private void pushTransportError(String message) {
        try { router.dispatchTransportError(message); } catch (Exception ignored) { /* best-effort */ }
    }

    private JsonObject makeEvent(String type, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        if (payload == null) payload = new JsonObject();
        payload.addProperty("timestamp", System.currentTimeMillis());
        long elapsedSec = (System.currentTimeMillis() - pair.getStartedAt()) / 1000;
        payload.addProperty("elapsedSeconds", elapsedSec);
        root.add("payload", payload);
        return root;
    }

    private static JsonArray strArray(List<String> items) {
        JsonArray arr = new JsonArray();
        for (String s : items) arr.add(s);
        return arr;
    }

    /**
     * Tool-use record for turn_end summaries.
     */
    public static final class ToolUseRecord {
        public final String tool;
        public final String path;
        public final boolean ok;
        public ToolUseRecord(String tool, String path, boolean ok) {
            this.tool = tool; this.path = path; this.ok = ok;
        }
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("tool", tool);
            if (path != null) o.addProperty("path", path);
            o.addProperty("ok", ok);
            return o;
        }
    }
}
