package com.github.claudecodegui.permission;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.function.Consumer;

/**
 * Bridges remote-daemon {@code _ctrl} messages to the same dialog UIs that
 * local mode uses (via {@link PermissionService}'s registered showers).
 *
 * <p>Three actions are handled:
 * <ul>
 *   <li>{@code permission_request} → {@link PermissionService#showRemotePermissionDialog}</li>
 *   <li>{@code ask_user_question_request} → {@link PermissionService#showRemoteAskUserQuestionDialog}</li>
 *   <li>{@code plan_approval_request} → {@link PermissionService#showRemotePlanApprovalDialog}</li>
 * </ul>
 *
 * <p>For each, this class constructs the matching {@code _ctrl/*_response}
 * JSON envelope and hands it to {@code replySender}, which is the
 * {@link com.github.claudecodegui.provider.common.RemoteBridge}'s
 * {@code POST /session/{id}/in} sender.
 */
public class RemotePermissionAdapter implements ControlMessageHandler {

    private static final Logger LOG = Logger.getInstance(RemotePermissionAdapter.class);

    private final Project project;
    private final String sessionId;

    public RemotePermissionAdapter(Project project, String sessionId) {
        this.project = project;
        this.sessionId = sessionId;
    }

    @Override
    public void onRequest(String action, JsonObject request, Consumer<JsonObject> replySender) {
        if (request == null || replySender == null) return;
        switch (action) {
            case "permission_request":
                handlePermission(request, replySender);
                break;
            case "ask_user_question_request":
                handleAskUserQuestion(request, replySender);
                break;
            case "plan_approval_request":
                handlePlanApproval(request, replySender);
                break;
            default:
                LOG.warn("[RemotePermissionAdapter] Unknown action: " + action);
        }
    }

    // =========================================================================
    // Permission
    // =========================================================================

    private void handlePermission(JsonObject req, Consumer<JsonObject> replySender) {
        String requestId = optString(req, "requestId");
        if (requestId == null) {
            LOG.warn("[RemotePermissionAdapter] permission_request missing requestId");
            return;
        }
        String toolName = optString(req, "toolName");
        JsonObject inputs = req.has("inputs") && req.get("inputs").isJsonObject()
                ? req.getAsJsonObject("inputs") : new JsonObject();
        String cwd = optString(req, "cwd");

        PermissionService svc = PermissionService.getInstance(project, sessionId);
        if (svc == null) {
            LOG.warn("[RemotePermissionAdapter] PermissionService unavailable; auto-deny " + requestId);
            replySender.accept(buildPermissionResponse(requestId, false));
            return;
        }
        svc.showRemotePermissionDialog(toolName, inputs, cwd).whenComplete((allow, ex) -> {
            if (ex != null) {
                LOG.warn("[RemotePermissionAdapter] permission dialog failed: " + ex.getMessage());
                replySender.accept(buildPermissionResponse(requestId, false));
                return;
            }
            replySender.accept(buildPermissionResponse(requestId, Boolean.TRUE.equals(allow)));
        });
    }

    private JsonObject buildPermissionResponse(String requestId, boolean allow) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "_ctrl");
        resp.addProperty("action", "permission_response");
        resp.addProperty("requestId", requestId);
        resp.addProperty("allow", allow);
        return resp;
    }

    // =========================================================================
    // AskUserQuestion
    // =========================================================================

    private void handleAskUserQuestion(JsonObject req, Consumer<JsonObject> replySender) {
        String requestId = optString(req, "requestId");
        if (requestId == null) {
            LOG.warn("[RemotePermissionAdapter] ask_user_question_request missing requestId");
            return;
        }

        // Supervisor pair-mode intercept (remote mirror of
        // PermissionService.handleAskUserQuestionRequest). When the
        // originating tab has an active pair session, the user opted into
        // autonomous (no-popup) operation, so we deny AskUserQuestion here
        // instead of surfacing a dialog. The denial flows back through
        // ai-bridge-server's permission-ipc.js as a {denied:true, reason}
        // envelope, which permission-handler.canUseTool converts to SDK
        // {@code behavior:'deny'}; main AI then continues from context.
        String windowId = optString(req, "windowId");
        if (isPairModeActive(windowId)) {
            LOG.info("[RemotePermissionAdapter] Pair active for windowId="
                    + (windowId == null ? "(none)" : windowId)
                    + "; denying AskUserQuestion without popup");
            replySender.accept(buildAskDeniedResponse(requestId,
                    "AskUserQuestion is disabled in autonomous (supervisor) pair mode. "
                            + "Do not retry this tool — make your best judgment from "
                            + "existing context and continue."));
            return;
        }

        // Build the JSON passed to the React AskUserQuestionDialog. Must mirror
        // the local-mode request-file shape so the webview can read
        // request.requestId (it echoes that back on submit; without it the
        // bridge response loses requestId and the daemon's promise never
        // resolves).
        JsonObject questionsData = new JsonObject();
        questionsData.addProperty("requestId", requestId);
        questionsData.addProperty("toolName", "AskUserQuestion");
        if (req.has("questions")) {
            questionsData.add("questions", req.get("questions"));
        }
        String cwd = optString(req, "cwd");

        PermissionService svc = PermissionService.getInstance(project, sessionId);
        if (svc == null) {
            replySender.accept(buildAskResponse(requestId, new JsonObject()));
            return;
        }
        svc.showRemoteAskUserQuestionDialog(requestId, questionsData, cwd).whenComplete((answers, ex) -> {
            if (ex != null) {
                LOG.warn("[RemotePermissionAdapter] ask dialog failed: " + ex.getMessage());
                replySender.accept(buildAskResponse(requestId, new JsonObject()));
                return;
            }
            // PermissionHandler completes the future with the answers map directly
            // ({questionKey: answer}); pass it through unchanged. Matches the
            // local-mode file IPC payload, which writes `answers` as an object.
            replySender.accept(buildAskResponse(requestId, answers != null ? answers : new JsonObject()));
        });
    }

    private JsonObject buildAskResponse(String requestId, JsonObject answers) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "_ctrl");
        resp.addProperty("action", "ask_user_question_response");
        resp.addProperty("requestId", requestId);
        resp.add("answers", answers);
        return resp;
    }

    private JsonObject buildAskDeniedResponse(String requestId, String reason) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "_ctrl");
        resp.addProperty("action", "ask_user_question_response");
        resp.addProperty("requestId", requestId);
        resp.addProperty("denied", true);
        resp.addProperty("reason", reason == null ? "" : reason);
        resp.add("answers", new JsonObject());
        return resp;
    }

    /**
     * True if the originating tab (or project, when {@code windowId} is null)
     * currently has an active (non-disposed) pair session. Best-effort: any
     * unexpected exception falls back to "no pair", so a broken pair manager
     * never blocks a popup.
     */
    private boolean isPairModeActive(String windowId) {
        try {
            if (project == null || project.isDisposed()) return false;
            com.github.claudecodegui.session.pair.PairSessionManager mgr =
                    com.github.claudecodegui.session.pair.PairSessionManager.getInstance(project);
            if (mgr == null) return false;
            if (windowId != null && !windowId.isEmpty()) {
                return mgr.getActivePairsOwnedBy(windowId).stream()
                        .anyMatch(p -> !p.isDisposed());
            }
            return mgr.getActivePairs().stream().anyMatch(p -> !p.isDisposed());
        } catch (Throwable t) {
            LOG.warn("[RemotePermissionAdapter] Pair-mode check failed: " + t.getMessage());
            return false;
        }
    }

    // =========================================================================
    // PlanApproval
    // =========================================================================

    private void handlePlanApproval(JsonObject req, Consumer<JsonObject> replySender) {
        String requestId = optString(req, "requestId");
        if (requestId == null) {
            LOG.warn("[RemotePermissionAdapter] plan_approval_request missing requestId");
            return;
        }
        // Mirror local-mode request-file shape — the React PlanApprovalDialog
        // reads request.requestId to echo back on approve/reject.
        JsonObject planData = new JsonObject();
        planData.addProperty("requestId", requestId);
        planData.addProperty("toolName", "ExitPlanMode");
        if (req.has("plan")) planData.add("plan", req.get("plan"));
        String cwd = optString(req, "cwd");

        PermissionService svc = PermissionService.getInstance(project, sessionId);
        if (svc == null) {
            replySender.accept(buildPlanResponse(requestId, false, null));
            return;
        }
        svc.showRemotePlanApprovalDialog(requestId, planData, cwd).whenComplete((result, ex) -> {
            if (ex != null) {
                LOG.warn("[RemotePermissionAdapter] plan dialog failed: " + ex.getMessage());
                replySender.accept(buildPlanResponse(requestId, false, null));
                return;
            }
            boolean approved = result != null && result.has("approved")
                    && result.get("approved").getAsBoolean();
            String editedPlan = (result != null && result.has("editedPlan") && !result.get("editedPlan").isJsonNull())
                    ? result.get("editedPlan").getAsString() : null;
            replySender.accept(buildPlanResponse(requestId, approved, editedPlan));
        });
    }

    private JsonObject buildPlanResponse(String requestId, boolean approved, String editedPlan) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "_ctrl");
        resp.addProperty("action", "plan_approval_response");
        resp.addProperty("requestId", requestId);
        resp.addProperty("approved", approved);
        if (editedPlan != null) resp.addProperty("editedPlan", editedPlan);
        return resp;
    }

    // =========================================================================
    // Utils
    // =========================================================================

    private static String optString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }
}
