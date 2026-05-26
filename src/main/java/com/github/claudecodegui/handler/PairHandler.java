package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.SupervisorMessageBatcher;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.path.PathMapper;
import com.github.claudecodegui.path.PathMapperHolder;
import com.github.claudecodegui.session.pair.ActionRouter;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.session.pair.PairStatusPusher;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Webview → Java IPC handler for Pair lifecycle.
 *
 * Supported message types:
 *   - pair_start  { sessionId, agentId, planPath? }     → start a Pair
 *   - pair_stop   { pairId }                             → stop a Pair
 *   - pair_human_response  { pairId, choice, note? }     → forward human decision
 *                                                          to Supervisor via EventBus
 *
 * Webview callbacks emitted back:
 *   - window.onPairStarted        { pairId, agentId, agentName, mainSessionId }
 *   - window.onPairStopFailed     { error }
 *   - window.onPairActionEvent    { ...action wrapper from daemon }
 *   - window.onPairInjectPrompt   { pairId, supervisorId, prompt }
 *   - window.onPairEscalate       { ...payload }
 */
public class PairHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(PairHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "pair_start",
            "pair_stop",
            "pair_human_response",
            "pair_send_user_input",
            "pair_set_model",
            "pair_set_reasoning",
            "pair_set_long_context",
            "pair_set_auto_compact_threshold",
            // Protocol v2 (2026-05-24): autonomy-mode IPC
            "pair_directive_ack",
            // Phase 5 (2026-05-24): autonomy-mode toggle from webview
            "pair_set_autonomy_mode",
            // Phase 6 (2026-05-24): manual resume after budget-triggered pause
            "pair_resume",
            // 2026-05-25 (FUNDAMENTAL FIX): manual user-initiated cancel of the
            // supervisor's current turn. Replaces the wall-clock auto-interrupt
            // that previously fired on monitor-tick timeout.
            "pair_supervisor_interrupt",
            // 2026-05-25 (intermittent-inject fix D): webview signals it's
            // mounted and ready to receive injects. ActionRouter buffers any
            // dispatch that arrived before this and drains on ready.
            "pair_webview_ready"
    };

    private final Gson gson;

    public PairHandler(HandlerContext context) {
        super(context);
        this.gson = new Gson();
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "pair_start":
                handleStart(content);
                return true;
            case "pair_stop":
                handleStop(content);
                return true;
            case "pair_human_response":
                handleHumanResponse(content);
                return true;
            case "pair_send_user_input":
                handleUserInput(content);
                return true;
            case "pair_set_model":
                handleSetModel(content);
                return true;
            case "pair_set_reasoning":
                handleSetReasoning(content);
                return true;
            case "pair_set_long_context":
                handleSetLongContext(content);
                return true;
            case "pair_set_auto_compact_threshold":
                handleSetAutoCompactThreshold(content);
                return true;
            case "pair_directive_ack":
                handleDirectiveAck(content);
                return true;
            case "pair_set_autonomy_mode":
                handleSetAutonomyMode(content);
                return true;
            case "pair_resume":
                handleResume(content);
                return true;
            case "pair_supervisor_interrupt":
                handleSupervisorInterrupt(content);
                return true;
            case "pair_webview_ready":
                handleWebviewReady(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * 2026-05-25 (intermittent-inject fix D): webview signals it's mounted
     * and ready to receive {@code window.onPairInjectPrompt} pushes. Drains
     * any inject that was buffered at the ActionRouter level. Idempotent —
     * a webview reload that re-mounts PairProvider will re-fire this IPC and
     * we treat each one as a drain trigger.
     */
    private void handleWebviewReady(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data != null && data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            PairSession session = resolvePair(pairId);
            if (session == null) {
                // Webview can post ready before the pair_start round-trip
                // finished — that's normal on first mount, not an error. The
                // next inject will use ActionRouter's buffer path until ready
                // arrives, so we just log at info level.
                LOG.info("[PairHandler] pair_webview_ready (no active pair yet) pairId=" + pairId);
                return;
            }
            com.github.claudecodegui.session.pair.ActionRouter router = session.getActionRouter();
            if (router != null) {
                router.markWebviewReady();
            }
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_webview_ready failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * 2026-05-25 (FUNDAMENTAL FIX): user clicked the Stop button in the
     * supervisor pane. Payload: {@code { pairId }}. Calls the existing
     * {@code supervisor.interrupt} daemon RPC via SupervisorBridge — which
     * invokes the SDK's {@code query.interrupt()} so the in-flight
     * {@code query.next()} settles cleanly without us having had to set a
     * wall-clock cap. Fire-and-forget: the daemon emits a
     * {@code [SUPERVISOR_INTERRUPT_RESULT]} line for telemetry, but the
     * webview already knows it asked for the cancel.
     */
    private void handleSupervisorInterrupt(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            PairSession session = resolvePair(pairId);
            if (session == null) {
                LOG.info("[PairHandler] pair_supervisor_interrupt ignored — no active pair for id=" + pairId);
                return;
            }
            LOG.info("[PairHandler] pair " + session.getPairId() + " supervisor interrupt requested by user");
            session.getSupervisorBridge().interrupt(); // fire-and-forget
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_supervisor_interrupt failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * Phase 6 (2026-05-24): manual resume after the pair was paused (typically
     * by budget exceeded). Payload: { pairId, extendBudgetSubagents?, extendBudgetTokens?, ... }.
     * The optional extension hints are not yet enforced server-side — left for
     * a follow-up if the cumulative counters re-trip the pause immediately.
     * For now we just flip the paused flag; if the budget is still exceeded
     * the monitor will pause again on the next tick (which the user can read
     * as "this won't fit, raise the limits explicitly").
     */
    private void handleResume(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            PairSession session = resolvePair(pairId);
            if (session == null) {
                LOG.info("[PairHandler] pair_resume ignored — no active pair for id=" + pairId);
                return;
            }
            boolean transitioned = session.resume();
            // Also clear the F2 directive-failure counter so a stale streak
            // from before the pause doesn't immediately fire step_blocked.
            session.resetDirectiveFailures();
            LOG.info("[PairHandler] pair " + session.getPairId() + " resume transitioned=" + transitioned);
            try {
                PairStatusPusher sp = session.getStatusPusher();
                if (sp != null) sp.pushSoft();
            } catch (Exception ignored) { /* best-effort */ }
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_resume failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * Phase 5 (2026-05-24): webview toggled autonomy level for a pair.
     * Payload: { pairId, mode: "strict" | "mixed" | "full" }.
     * Invalid modes are coerced to "full" by PairSession.setAutonomyMode
     * (matches the 2026-05-25 default).
     */
    private void handleSetAutonomyMode(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            String mode = data.has("mode") && !data.get("mode").isJsonNull()
                    ? data.get("mode").getAsString() : "full";
            PairSession session = resolvePair(pairId);
            if (session == null) {
                LOG.info("[PairHandler] pair_set_autonomy_mode ignored — no active pair for id=" + pairId);
                return;
            }
            session.setAutonomyMode(mode);
            LOG.info("[PairHandler] pair " + session.getPairId() + " autonomyMode -> " + session.getAutonomyMode());
            // Trigger a status push so the webview's AutonomyToggle reflects
            // the resolved value (in case the input was coerced).
            try {
                PairStatusPusher sp = session.getStatusPusher();
                if (sp != null) sp.pushSoft();
            } catch (Exception ignored) { /* best-effort */ }
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_set_autonomy_mode failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    /**
     * Protocol v2 (2026-05-24): webview ack for a previously-injected directive.
     * Payload: { pairId, directiveId, status: "received"|"applied"|"failed" }.
     * Forwarded to ActionRouter.markDirectiveAcked which cancels the
     * DirectiveTracker timeout.
     */
    private void handleDirectiveAck(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            String directiveId = data.has("directiveId") && !data.get("directiveId").isJsonNull()
                    ? data.get("directiveId").getAsString() : "";
            String status = data.has("status") && !data.get("status").isJsonNull()
                    ? data.get("status").getAsString() : "applied";
            if (directiveId.isEmpty()) {
                LOG.warn("[PairHandler] pair_directive_ack missing directiveId, ignoring");
                return;
            }
            PairSession session = resolvePair(pairId);
            if (session == null || session.getActionRouter() == null) {
                LOG.debug("[PairHandler] pair_directive_ack for unknown/disposed pair " + pairId);
                return;
            }
            session.getActionRouter().markDirectiveAcked(directiveId, status);
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_directive_ack failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    private void handleStart(String content) {
        // 2026-05-25: offload to background pool. The JCEF message callback that
        // ends up here runs on (or piggy-backs) the EDT on macOS, and the
        // internal startPair() does a 20s synchronous .get() on a daemon RPC
        // (PairSessionManager.java:311-322) — which froze the IDE entirely
        // (file ops, typing, indexing) until handshake completed. All webview
        // callbacks below (pushToWebview / sendError) are already invokeLater,
        // and the method returns void, so async dispatch is drop-in safe.
        AppExecutorUtil.getAppExecutorService().submit(() -> handleStartImpl(content));
    }

    private void handleStartImpl(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String sessionId = data.has("sessionId") && !data.get("sessionId").isJsonNull()
                    ? data.get("sessionId").getAsString() : null;
            String agentId = data.has("agentId") ? data.get("agentId").getAsString() : null;
            String planPath = data.has("planPath") && !data.get("planPath").isJsonNull()
                    ? data.get("planPath").getAsString() : null;

            // 2026-05-24: webview may ship the resolved effective model (with
            // [1m] suffix already applied), the 1M-toggle state, and the
            // reasoning tier so the daemon SDK is born with the right config.
            // All three are optional; null means "fall back to the agent's
            // defaults from supervisor-agents.json" (kept for back-compat with
            // older webview builds + future direct-API callers).
            String modelOverride = data.has("model") && !data.get("model").isJsonNull()
                    ? data.get("model").getAsString() : null;
            Boolean longContextOverride = (data.has("longContextEnabled")
                    && !data.get("longContextEnabled").isJsonNull())
                    ? data.get("longContextEnabled").getAsBoolean() : null;
            String reasoningOverride = data.has("reasoningEffort") && !data.get("reasoningEffort").isJsonNull()
                    ? data.get("reasoningEffort").getAsString() : null;

            // Protocol v2 (2026-05-24): optional budget caps. null means "no
            // limits" — the tracker is still created so other autonomy-mode
            // surfaces (status panel, completion report) can read counters,
            // they just never trip the warn / pause thresholds.
            com.github.claudecodegui.session.pair.protocol.PairBudget budget =
                    new com.github.claudecodegui.session.pair.protocol.PairBudget();
            if (data.has("budget") && data.get("budget").isJsonObject()) {
                JsonObject b = data.getAsJsonObject("budget");
                if (b.has("maxTokens") && !b.get("maxTokens").isJsonNull())
                    budget.maxTokens = b.get("maxTokens").getAsLong();
                if (b.has("maxDurationMs") && !b.get("maxDurationMs").isJsonNull())
                    budget.maxDurationMs = b.get("maxDurationMs").getAsLong();
                if (b.has("maxSteps") && !b.get("maxSteps").isJsonNull())
                    budget.maxSteps = b.get("maxSteps").getAsInt();
                if (b.has("maxSubagentCalls") && !b.get("maxSubagentCalls").isJsonNull())
                    budget.maxSubagentCalls = b.get("maxSubagentCalls").getAsInt();
            }

            if (agentId == null) throw new IllegalArgumentException("pair_start requires agentId");
            if (context.getProject() == null) throw new IllegalStateException("no project context");
            if (context.getClaudeSDKBridge() == null) {
                throw new IllegalStateException(
                        "Claude SDK bridge unavailable — supervisor requires the Claude provider "
                        + "to be initialised before enabling Pair.");
            }

            PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
            PairSession session = mgr.startPair(
                    new PairSessionManager.StartPairParams(
                            sessionId, agentId, planPath,
                            modelOverride, longContextOverride, reasoningOverride,
                            context.getWindowId()),
                    context.getClaudeSDKBridge()
            );

            // 2026-05-24: bind the live ClaudeSession to the pair so the
            // rotation decider can resolve it on auto-trigger without a
            // separate session registry. Nullable in headless tests.
            if (context.getSession() != null) {
                session.setClaudeSession(context.getSession());
            }

            // Bind webview bridge so ActionRouter can drive UI.
            session.getActionRouter().setWebviewBridge(new WebviewBridgeImpl());

            // Protocol v2 (2026-05-24): wire autonomy-mode trackers. Both are
            // always created — budget without limits is a no-op cost-wise but
            // still tracks counters for the UI / completion report.
            // Contract State Machine v3 (2026-05-25): DirectiveTracker
            // construction removed — ContractRegistry (wired in
            // PairSessionManager) handles all directive timing, retry, and
            // R1/R2/R3 escalation. The supervisor receives a structured
            // DECISION_REQUEST contract instead of the old directive_lost
            // event when a contract goes 3-strikes.
            com.github.claudecodegui.session.pair.PairBudgetTracker bt =
                    new com.github.claudecodegui.session.pair.PairBudgetTracker(session.getPairId(), budget);
            session.setBudgetTracker(bt);
            // Stage C (2026-05-25): BudgetWatcher facade for uniform watcher
            // access alongside HealthWatcher / RotationWatcher.
            session.setBudgetWatcher(
                    new com.github.claudecodegui.session.pair.watcher.BudgetWatcher(bt));

            // v4 unified pipeline: forward each raw SDK message streamed by the
            // daemon during a supervisor turn to the webview. The webview maps
            // content blocks (text, thinking, tool_use, tool_result) into pane
            // entries the same way it does for main-AI messages — no
            // wrapper-side reconstruction needed.
            //
            // We also intercept the assistant/result usage field here and route
            // it through UsagePushService.broadcast — the same entry point the
            // main AI uses — so the TokenIndicator updates mid-turn with the
            // same percentage formula and model-context-limit table. Without
            // this intercept the indicator stays at the baseline while the
            // supervisor is still thinking.
            //
            // 2026-05-24: feed envelopes through a SupervisorMessageBatcher
            // instead of doing one invokeLater(callJavaScript) per message.
            // A 30s tick can emit dozens of envelopes (each Read tool_result
            // up to ~50KB), and on remote mode SSE delivers them in bursts —
            // unthrottled they saturate the EDT and trigger WebviewWatchdog
            // reload, which wipes the supervisor pane.
            final PairSession sessionRef = session;
            SupervisorMessageBatcher batcher = new SupervisorMessageBatcher(
                    gson,
                    arrJson -> pushToWebview("window.onSupervisorMessageBatch", arrJson)
            );
            session.setMessageBatcher(batcher);
            session.getSupervisorBridge().setMessageHandler(rawMsg -> {
                JsonObject sdkUsage = extractSdkUsage(rawMsg);
                if (sdkUsage != null) {
                    UsagePushService.broadcast(
                            sdkUsage,
                            sessionRef.getModel(),
                            "supervisor",
                            sessionRef.getAgentId(),
                            context
                    );
                }
                batcher.enqueue(rawMsg);
            });

            JsonObject result = new JsonObject();
            result.addProperty("pairId", session.getPairId());
            result.addProperty("agentId", session.getAgentId());
            result.addProperty("agentName", session.getAgentName());
            result.addProperty("mainSessionId", session.getMainSessionId() == null ? "" : session.getMainSessionId());
            pushToWebview("window.onPairStarted", gson.toJson(result));

            // Seed the TokenIndicator with a 0-tokens snapshot so the right-pane
            // composer shows the correct context limit (e.g. 1M) immediately,
            // instead of the fallback "0 / 200k" until the first SDK message
            // arrives with usage. Passes null rawUsage so broadcast treats it
            // as "0 tokens used" while still resolving maxTokens from the model.
            UsagePushService.broadcast(
                    null,
                    session.getModel(),
                    "supervisor",
                    session.getAgentId(),
                    context
            );

            // NOTE: We intentionally do NOT auto-publish a `start` event anymore.
            // The Supervisor session is ready and waiting silently. It will only
            // begin coordinating after the user sends their first message via the
            // right-pane composer (pair_send_user_input → publishUserInput).
            // This avoids the "is there a plan?" escalate dialog firing the moment
            // the user clicks Enable.
        } catch (Exception e) {
            // Include full stack so transient NPEs (e.g. daemon not yet ready,
            // missing agent config) surface clearly in idea.log.
            LOG.warn("[PairHandler] pair_start failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()), e);
            String msg = e.getMessage() != null
                    ? e.getMessage()
                    : "Internal error: " + e.getClass().getSimpleName();
            sendError("pair_start", msg);
        }
    }

    private void handleStop(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
            String ownerWindowId = context.getWindowId();

            // Webview rarely tracks pairId; fall back to the most-recently-started
            // pair OWNED BY THIS TAB. Falling back to any project-wide pair would
            // let one tab silently stop another tab's supervisor (cross-tab
            // routing bug, 2026-05-24).
            if (pairId.isEmpty()) {
                PairSession latest = mgr.getActivePairsOwnedBy(ownerWindowId).stream()
                        .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                        .orElse(null);
                if (latest == null) {
                    pushToWebview("window.onPairStopped", "{\"pairId\":\"\"}");
                    return;
                }
                pairId = latest.getPairId();
            } else if (mgr.getOwnedBy(pairId, ownerWindowId) == null) {
                // Explicit pairId from a different tab — refuse rather than stop.
                LOG.info("[PairHandler] pair_stop ignored: pair " + pairId
                        + " not owned by window " + ownerWindowId);
                pushToWebview("window.onPairStopped", "{\"pairId\":\"\"}");
                return;
            }
            mgr.stopPair(pairId);

            JsonObject ok = new JsonObject();
            ok.addProperty("pairId", pairId);
            pushToWebview("window.onPairStopped", gson.toJson(ok));
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_stop failed: " + e.getMessage());
            sendError("pair_stop", e.getMessage());
        }
    }

    /**
     * Right-pane composer model picker → update the model the Supervisor uses
     * on its next restart. The supervisor channel has no live setModel
     * protocol yet, so this does NOT hot-swap — the new value is picked up
     * when {@code EventBus.restartSupervisor()} runs (daemon recovery, or
     * when the user toggles Pair off and back on).
     *
     * <p>Returning a hard error here would surface a red toast on every model
     * tweak, so we accept the request optimistically and just log if we
     * can't find a matching pair.
     */
    private void handleSetModel(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            String model = data.has("model") && !data.get("model").isJsonNull()
                    ? data.get("model").getAsString() : "";

            PairSession session = resolvePair(pairId);
            if (session == null) {
                LOG.info("[PairHandler] pair_set_model ignored — no active pair for id=" + pairId);
                return;
            }
            session.setModel(model);
            LOG.info("[PairHandler] pair_set_model recorded for pair=" + session.getPairId()
                    + " model=" + (model.isEmpty() ? "(default)" : model)
                    + " — applies on next supervisor restart");
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_set_model failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()), e);
            sendError("pair_set_model",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Right-pane composer reasoning picker → like {@link #handleSetModel}, this
     * only stages the value; the supervisor channel has no setReasoning
     * protocol yet, so the new effort takes effect on next supervisor restart.
     */
    private void handleSetReasoning(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            String effort = data.has("effort") && !data.get("effort").isJsonNull()
                    ? data.get("effort").getAsString() : "";

            PairSession session = resolvePair(pairId);
            if (session == null) {
                LOG.info("[PairHandler] pair_set_reasoning ignored — no active pair for id=" + pairId);
                return;
            }
            session.setReasoningEffort(effort);
            LOG.info("[PairHandler] pair_set_reasoning recorded for pair=" + session.getPairId()
                    + " effort=" + (effort.isEmpty() ? "(default)" : effort)
                    + " — applies on next supervisor restart");
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_set_reasoning failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()), e);
            sendError("pair_set_reasoning",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Right-pane composer 1M-context toggle. The webview ships
     * {@code claude-opus-4-7[1m]}-style IDs as the model string (the suffix
     * is applied by {@code apply1MContextSuffix} in ChatInputBox/types.ts),
     * so this handler just forwards to {@link #handleSetModel} with the
     * suffixed value. We keep the message type separate for analytics —
     * users toggling 1M is different from picking a new model family.
     */
    private void handleSetLongContext(String content) {
        // Reuse the model-set flow; the webview always sends the resolved
        // model id (already with/without [1m] suffix) on the same payload.
        handleSetModel(content);
    }

    /**
     * Settings page → daemon: update the autocompact trigger threshold.
     * Persists to supervisor-agents.json (so it survives IDE restart) and
     * pushes through to every active pair so the next supervisor turn sees
     * the new value. The daemon also re-reads {@code process.env} on every
     * shouldAutoCompact() call, so latency is at most one extra turn.
     */
    private void handleSetAutoCompactThreshold(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            int threshold = data.has("threshold") && !data.get("threshold").isJsonNull()
                    ? data.get("threshold").getAsInt() : -1;
            if (threshold < com.github.claudecodegui.settings.SupervisorAgentManager.MIN_AUTO_COMPACT_THRESHOLD
                || threshold > com.github.claudecodegui.settings.SupervisorAgentManager.MAX_AUTO_COMPACT_THRESHOLD) {
                sendError("pair_set_auto_compact_threshold",
                        "threshold out of range (50-95): " + threshold);
                return;
            }

            // 1) Persist.
            new com.github.claudecodegui.settings.CodemossSettingsService()
                    .getSupervisorAgentManager().setAutoCompactThreshold(threshold);

            // 2) Propagate to live pairs so the next turn picks up the new
            //    daemon env. We restart supervisor on next-turn lazy path
            //    (EventBus.restartSupervisor) — no immediate stop/start to
            //    avoid breaking the in-flight conversation.
            PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
            for (PairSession session : mgr.getActivePairs()) {
                session.setAutoCompactThreshold(threshold);
            }
            LOG.info("[PairHandler] autoCompactThreshold updated to " + threshold);
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_set_auto_compact_threshold failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()), e);
            sendError("pair_set_auto_compact_threshold",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Look up an active pair by id, falling back to the most-recently-started
     * pair OWNED BY THIS TAB when the webview didn't track the id (mirrors
     * handleStop / handleUserInput). Filtering by ownerWindowId avoids
     * cross-tab corruption — a tab without its own pair returns null instead
     * of silently steering another tab's pair.
     */
    private PairSession resolvePair(String pairId) {
        PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
        String ownerWindowId = context.getWindowId();
        if (pairId == null || pairId.isEmpty()) {
            return mgr.getActivePairsOwnedBy(ownerWindowId).stream()
                    .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                    .orElse(null);
        }
        return mgr.getOwnedBy(pairId, ownerWindowId);
    }

    private void handleUserInput(String content) {
        // 2026-05-25: offload to background pool. On macOS the JBCefJSQuery
        // onQuery callback runs on AppKit Thread, and EventBus.publish's
        // .thenCompose(this::forward) can race-completion into synchronous
        // execution of tryPostEvent -> future.get() on the caller thread.
        // That parked AppKit for 10-30s, which in turn parked EDT on any
        // operation needing Cocoa (Balloon dispose, JCEF window setVisible).
        // Thread dump 2026-05-25 18:27:28 confirmed: AppKit blocked in
        // EventBus.tryPostEvent:454 on Unsafe.park, EDT blocked 14sec
        // downstream in AWTThreading.executeWaitToolkit. Async dispatch
        // moves the entire publish chain to a pooled thread and frees
        // AppKit immediately. Webview callbacks below (sendError) are
        // already invokeLater, so this is drop-in safe.
        AppExecutorUtil.getAppExecutorService().submit(() -> handleUserInputImpl(content));
    }

    private void handleUserInputImpl(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.has("pairId") && !data.get("pairId").isJsonNull()
                    ? data.get("pairId").getAsString() : "";
            String text = data.has("text") ? data.get("text").getAsString() : "";
            if (text == null || text.isBlank()) {
                sendError("pair_send_user_input", "empty text");
                return;
            }

            // Translate any `@<local-path>` references the composer extracted into
            // remote-path form so the daemon Supervisor only ever sees paths it
            // can resolve. Identity mapper is a no-op for local mode, so this is
            // safe to run unconditionally.
            JsonArray attachments = data.has("attachments") && data.get("attachments").isJsonArray()
                    ? data.getAsJsonArray("attachments")
                    : null;
            text = translateUserInputPaths(text, attachments);

            PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
            String ownerWindowId = context.getWindowId();
            // Scope lookup to this tab. Without this, a webview that adopted
            // another tab's pair via onPairResume would post user input to that
            // tab's daemon, and the response would route back to that tab's
            // webview only — current tab silently sees nothing.
            PairSession session = pairId.isEmpty()
                    ? mgr.getActivePairsOwnedBy(ownerWindowId).stream()
                        .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                        .orElse(null)
                    : mgr.getOwnedBy(pairId, ownerWindowId);
            if (session == null) {
                sendError("pair_send_user_input", "no active pair to receive user input");
                return;
            }
            if (session.getEventBus() != null) {
                session.getEventBus().publishUserInput(text);
            }
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_send_user_input failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()), e);
            sendError("pair_send_user_input",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Rewrite the user-typed body so every {@code @<localPath>} occurrence is
     * substituted with {@code @<remotePath>} based on the active project's
     * {@link PathMapper}. Paths that don't match the configured mapping prefix
     * are returned unchanged (per PathMapper's best-effort contract).
     *
     * <p>Only {@code @}-prefixed occurrences are rewritten; bare path mentions
     * elsewhere in the text are left alone (per the "structured tokens only"
     * design contract — see PathFields.java for the same rule on the main AI
     * side).
     *
     * <p><b>Why this lives here</b> (not as a PathFields.OUTBOUND entry):
     * {@code payload.text} of a {@code user_input} event is free-form prose
     * and the {@link PathFieldVisitor} can only translate STRUCTURED string
     * leaves — calling {@code toRemote(entireText)} would yield garbage. The
     * @-token regex sweep here complements the supervisor.postEvent manifest:
     * structured path fields (modifiedFilesInPlan[*], toolUses[*].path, …)
     * are translated at {@code RemoteBridge.sendCommand}, free-form @-tokens
     * are translated here at the IPC boundary before publish.
     */
    private String translateUserInputPaths(String text, JsonArray attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return text;
        }
        Project project = context.getProject();
        if (project == null) {
            return text;
        }
        PathMapper mapper;
        try {
            mapper = PathMapperHolder.getInstance(project).get();
        } catch (Exception e) {
            LOG.warn("[PairHandler] PathMapper lookup failed: " + e.getMessage());
            return text;
        }
        if (!mapper.isActive()) {
            return text;
        }

        // Sort by descending length so we don't shadow a longer path with a
        // shorter prefix during substitution
        // (e.g. /Users/me/proj/sub vs /Users/me/proj).
        List<String> locals = new ArrayList<>();
        for (JsonElement el : attachments) {
            if (!el.isJsonObject()) continue;
            JsonObject att = el.getAsJsonObject();
            if (!att.has("path") || att.get("path").isJsonNull()) continue;
            String p = att.get("path").getAsString();
            if (p == null || p.isBlank()) continue;
            locals.add(p);
        }
        locals.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String out = text;
        for (String local : locals) {
            String remote = mapper.toRemote(local);
            if (remote == null || remote.equals(local)) {
                // Either unmapped (PathMissTracker will surface) or already remote.
                continue;
            }
            // Replace only when the local path is anchored to a preceding `@`.
            // String#replace is literal and safe for path characters here.
            out = out.replace("@" + local, "@" + remote);
        }
        return out;
    }

    private void handleHumanResponse(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String pairId = data.get("pairId").getAsString();
            String choice = data.has("choice") ? data.get("choice").getAsString() : null;
            String note = data.has("note") && !data.get("note").isJsonNull()
                    ? data.get("note").getAsString() : null;

            PairSession session = PairSessionManager.getInstance(context.getProject())
                    .getOwnedBy(pairId, context.getWindowId());
            if (session == null) {
                sendError("pair_human_response", "pair not found: " + pairId);
                return;
            }
            if (session.getEventBus() != null) {
                session.getEventBus().publishHumanResponse(choice, note);
            }
        } catch (Exception e) {
            LOG.warn("[PairHandler] pair_human_response failed: " + e.getMessage());
            sendError("pair_human_response", e.getMessage());
        }
    }

    /**
     * Re-emit {@code window.onPairResume} for every still-active pair after
     * the webview reloads. Called from {@code ChatWindowDelegate.handleFrontendReady}.
     *
     * <p>Without this hook the user's only recourse after a {@link
     * com.github.claudecodegui.ui.WebviewWatchdog} reload is to re-toggle the
     * supervisor, which stops + restarts the daemon-side session and loses
     * the in-flight context. The Java {@code PairSession} and daemon
     * supervisor are still alive; only React state was wiped.
     *
     * <p>Payload mirrors {@code SelectedSupervisor} so PairContext can restore
     * {@code selected} + {@code pairId} in one shot. The
     * {@code defaultLongContext} / {@code defaultReasoning} fields are
     * resolved from the supervisor-agents config so the restored entry looks
     * identical to one freshly picked from the picker.
     */
    public void replayActivePairs() {
        try {
            Project project = context.getProject();
            if (project == null) return;
            PairSessionManager mgr = PairSessionManager.getInstance(project);
            CodemossSettingsService settings = new CodemossSettingsService();

            // Only replay pairs created by THIS tab. Iterating all project-scoped
            // pairs would let a new tab's frontend_ready inherit another tab's
            // pair via onPairResume — but the underlying SupervisorBridge is
            // bound to the originating tab's ClaudeSDKBridge + webview, so the
            // inheriting tab could post events but never see responses.
            for (PairSession session : mgr.getActivePairsOwnedBy(context.getWindowId())) {
                if (session == null || session.isDisposed()) continue;

                String agentId = session.getAgentId();
                JsonObject agentConfig = null;
                try {
                    agentConfig = settings.getSupervisorAgentManager().getAgent(agentId);
                } catch (Exception ignored) { /* best-effort */ }

                JsonObject payload = new JsonObject();
                payload.addProperty("pairId", session.getPairId());
                payload.addProperty("agentId", agentId);
                payload.addProperty("name", session.getAgentName());
                String model = session.getModel();
                if (model != null && !model.isEmpty()) {
                    payload.addProperty("model", model);
                }
                if (agentConfig != null) {
                    if (agentConfig.has("defaultLongContext")
                            && !agentConfig.get("defaultLongContext").isJsonNull()) {
                        payload.addProperty("defaultLongContext",
                                agentConfig.get("defaultLongContext").getAsBoolean());
                    }
                    if (agentConfig.has("defaultReasoning")
                            && !agentConfig.get("defaultReasoning").isJsonNull()) {
                        payload.addProperty("defaultReasoning",
                                agentConfig.get("defaultReasoning").getAsString());
                    }
                }
                pushToWebview("window.onPairResume", gson.toJson(payload));
                LOG.info("[PairHandler] replayed pair " + session.getPairId()
                        + " (agent=" + agentId + ") after frontend reload");
            }
        } catch (Exception e) {
            LOG.warn("[PairHandler] replayActivePairs failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ====================== helpers ======================

    /**
     * Pull the SDK usage object out of a [SUPERVISOR_MSG] envelope. Two paths
     * the Claude Agent SDK uses:
     *   - assistant messages: envelope.message.message.usage
     *   - result messages:    envelope.message.usage
     * Returns null if neither path resolves to a JSON object.
     */
    private static JsonObject extractSdkUsage(JsonObject envelope) {
        if (envelope == null || !envelope.has("message") || !envelope.get("message").isJsonObject()) {
            return null;
        }
        JsonObject sdkMsg = envelope.getAsJsonObject("message");
        if (sdkMsg.has("message") && sdkMsg.get("message").isJsonObject()) {
            JsonObject inner = sdkMsg.getAsJsonObject("message");
            if (inner.has("usage") && inner.get("usage").isJsonObject()) {
                return inner.getAsJsonObject("usage");
            }
        }
        if (sdkMsg.has("usage") && sdkMsg.get("usage").isJsonObject()) {
            return sdkMsg.getAsJsonObject("usage");
        }
        return null;
    }

    private void pushToWebview(String fn, String jsonPayload) {
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript(fn, escapeJs(jsonPayload)));
    }

    private void sendError(String op, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("operation", op);
        err.addProperty("error", message);
        pushToWebview("window.onPairOperationError", gson.toJson(err));
    }

    /**
     * Webview-side effect adapter used by ActionRouter.
     */
    private final class WebviewBridgeImpl implements ActionRouter.WebviewBridge {
        @Override
        public void onActionEvent(JsonObject event) {
            pushToWebview("window.onPairActionEvent", gson.toJson(event));
        }
        @Override
        public void onInjectPrompt(String pairId, String supervisorId, String prompt) {
            // Legacy path — invoked only when directiveId is unknown.
            JsonObject p = new JsonObject();
            p.addProperty("pairId", pairId);
            p.addProperty("supervisorId", supervisorId);
            p.addProperty("prompt", prompt);
            pushToWebview("window.onPairInjectPrompt", gson.toJson(p));
        }
        @Override
        public void onInjectPromptV2(String pairId, String supervisorId,
                                     String directiveId, String prompt) {
            // Protocol v2 (2026-05-24): directiveId travels alongside prompt so
            // webview can later post pair_directive_ack with the same id.
            JsonObject p = new JsonObject();
            p.addProperty("pairId", pairId);
            p.addProperty("supervisorId", supervisorId);
            p.addProperty("directiveId", directiveId);
            p.addProperty("prompt", prompt);
            // 2026-05-24 (Q4 trace): record the bridge call. Paired with the
            // webview-side `[INJECT_TRACE] webview onPairInjectPrompt` log.
            LOG.info("[INJECT_TRACE] PairHandler.onInjectPromptV2"
                    + " pair=" + pairId + " directiveId=" + directiveId
                    + " promptLen=" + (prompt != null ? prompt.length() : 0));
            pushToWebview("window.onPairInjectPrompt", gson.toJson(p));
        }
        @Override
        public void onPairAlert(JsonObject payload) {
            // Protocol v2 (2026-05-24): record_alert webview surface — toast
            // notification, non-blocking. Webview handler should NOT open a modal.
            pushToWebview("window.onPairAlert", gson.toJson(payload));
        }
        @Override
        public void onPairNotice(JsonObject payload) {
            pushToWebview("window.onPairNotice", gson.toJson(payload));
        }
        @Override
        public void onEscalate(JsonObject payload) {
            pushToWebview("window.onPairEscalate", gson.toJson(payload));
        }
        @Override
        public void onThinking(String supervisorId, boolean thinking) {
            JsonObject p = new JsonObject();
            p.addProperty("supervisorId", supervisorId);
            p.addProperty("thinking", thinking);
            pushToWebview("window.onPairThinking", gson.toJson(p));
        }
        @Override
        public void onPairStatusUpdate(JsonObject snapshot) {
            // Phase 2 (2026-05-24): the snapshot already contains pairId so the
            // webview can route by pair when multiple pairs exist (not yet, but
            // shape is forward-compat).
            pushToWebview("window.onPairStatusUpdate", gson.toJson(snapshot));
        }
    }
}
