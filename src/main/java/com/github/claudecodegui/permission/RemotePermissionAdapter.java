package com.github.claudecodegui.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
        if (requestId == null) return;
        // The remote envelope carries `questions: [...]`; the local-mode dialog
        // contract takes the same structure wrapped in a JsonObject.
        JsonObject questionsData = new JsonObject();
        if (req.has("questions")) {
            questionsData.add("questions", req.get("questions"));
        }
        String cwd = optString(req, "cwd");

        PermissionService svc = PermissionService.getInstance(project, sessionId);
        if (svc == null) {
            replySender.accept(buildAskResponse(requestId, new JsonArray()));
            return;
        }
        svc.showRemoteAskUserQuestionDialog(requestId, questionsData, cwd).whenComplete((answers, ex) -> {
            if (ex != null) {
                LOG.warn("[RemotePermissionAdapter] ask dialog failed: " + ex.getMessage());
                replySender.accept(buildAskResponse(requestId, new JsonArray()));
                return;
            }
            // The dialog may return either { answers: [...] } or just an array;
            // normalize.
            JsonArray normalized = new JsonArray();
            if (answers != null) {
                JsonElement a = answers.has("answers") ? answers.get("answers") : answers;
                if (a != null && a.isJsonArray()) {
                    normalized = a.getAsJsonArray();
                }
            }
            replySender.accept(buildAskResponse(requestId, normalized));
        });
    }

    private JsonObject buildAskResponse(String requestId, JsonArray answers) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "_ctrl");
        resp.addProperty("action", "ask_user_question_response");
        resp.addProperty("requestId", requestId);
        resp.add("answers", answers);
        return resp;
    }

    // =========================================================================
    // PlanApproval
    // =========================================================================

    private void handlePlanApproval(JsonObject req, Consumer<JsonObject> replySender) {
        String requestId = optString(req, "requestId");
        if (requestId == null) return;
        JsonObject planData = new JsonObject();
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
