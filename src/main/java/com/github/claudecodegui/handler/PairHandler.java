package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.ActionRouter;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

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
            "pair_send_user_input"
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

            JsonObject result = new JsonObject();
            result.addProperty("pairId", session.getPairId());
            result.addProperty("agentId", session.getAgentId());
            result.addProperty("agentName", session.getAgentName());
            result.addProperty("mainSessionId", session.getMainSessionId() == null ? "" : session.getMainSessionId());
            pushToWebview("window.onPairStarted", gson.toJson(result));

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
