package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.path.PathMapper;
import com.github.claudecodegui.path.PathMapperHolder;
import com.github.claudecodegui.session.pair.ActionRouter;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

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
            "pair_set_auto_compact_threshold"
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
            default:
                return false;
        }
    }

    private void handleStart(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String sessionId = data.has("sessionId") && !data.get("sessionId").isJsonNull()
                    ? data.get("sessionId").getAsString() : null;
            String agentId = data.has("agentId") ? data.get("agentId").getAsString() : null;
            String planPath = data.has("planPath") && !data.get("planPath").isJsonNull()
                    ? data.get("planPath").getAsString() : null;

            if (agentId == null) throw new IllegalArgumentException("pair_start requires agentId");
            if (context.getProject() == null) throw new IllegalStateException("no project context");
            if (context.getClaudeSDKBridge() == null) {
                throw new IllegalStateException(
                        "Claude SDK bridge unavailable — supervisor requires the Claude provider "
                        + "to be initialised before enabling Pair.");
            }

            PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
            PairSession session = mgr.startPair(
                    new PairSessionManager.StartPairParams(sessionId, agentId, planPath),
                    context.getClaudeSDKBridge()
            );

            // Bind webview bridge so ActionRouter can drive UI.
            session.getActionRouter().setWebviewBridge(new WebviewBridgeImpl());

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
            final PairSession sessionRef = session;
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
                pushToWebview("window.onSupervisorMessage", gson.toJson(rawMsg));
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

            // Webview rarely tracks pairId; fall back to the last started pair
            // for this project. If multiple pairs are active in future, prefer
            // explicit pairId.
            if (pairId.isEmpty()) {
                PairSession latest = mgr.getActivePairs().stream()
                        .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                        .orElse(null);
                if (latest == null) {
                    pushToWebview("window.onPairStopped", "{\"pairId\":\"\"}");
                    return;
                }
                pairId = latest.getPairId();
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
     * pair when the webview didn't track the id (mirrors handleStop / handleUserInput).
     */
    private PairSession resolvePair(String pairId) {
        PairSessionManager mgr = PairSessionManager.getInstance(context.getProject());
        if (pairId == null || pairId.isEmpty()) {
            return mgr.getActivePairs().stream()
                    .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                    .orElse(null);
        }
        return mgr.get(pairId);
    }

    private void handleUserInput(String content) {
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
            PairSession session = pairId.isEmpty()
                    ? mgr.getActivePairs().stream()
                        .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                        .orElse(null)
                    : mgr.get(pairId);
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

            PairSession session = PairSessionManager.getInstance(context.getProject()).get(pairId);
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
            JsonObject p = new JsonObject();
            p.addProperty("pairId", pairId);
            p.addProperty("supervisorId", supervisorId);
            p.addProperty("prompt", prompt);
            pushToWebview("window.onPairInjectPrompt", gson.toJson(p));
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
    }
}
