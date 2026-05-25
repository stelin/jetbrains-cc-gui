package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * Supervisor Agent management message handler.
 * Bridges webview ↔ {@link com.github.claudecodegui.settings.SupervisorAgentManager}.
 */
public class SupervisorAgentHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SupervisorAgentHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "get_supervisor_agents",
            "get_supervisor_agent",
            "add_supervisor_agent",
            "update_supervisor_agent",
            "delete_supervisor_agent",
            "get_default_supervisor_agent",
            "set_default_supervisor_agent",
            "set_rotation_config"
    };

    private final CodemossSettingsService settingsService;
    private final Gson gson;

    public SupervisorAgentHandler(HandlerContext context) {
        super(context);
        this.settingsService = new CodemossSettingsService();
        this.gson = new Gson();
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_supervisor_agents":
                handleGetAgents();
                return true;
            case "get_supervisor_agent":
                handleGetAgent(content);
                return true;
            case "add_supervisor_agent":
                handleAddAgent(content);
                return true;
            case "update_supervisor_agent":
                handleUpdateAgent(content);
                return true;
            case "delete_supervisor_agent":
                handleDeleteAgent(content);
                return true;
            case "get_default_supervisor_agent":
                handleGetDefault();
                return true;
            case "set_default_supervisor_agent":
                handleSetDefault(content);
                return true;
            case "set_rotation_config":
                handleSetRotationConfig(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * Push the full list of supervisor agents to webview.
     * Payload: { agents: [...], defaultAgentId: string|null }
     */
    private void handleGetAgents() {
        try {
            List<JsonObject> agents = settingsService.getSupervisorAgents();
            String defaultId = settingsService.getDefaultSupervisorAgentId();

            JsonArray agentsArray = new JsonArray();
            for (JsonObject a : agents) {
                agentsArray.add(a);
            }
            JsonObject payload = new JsonObject();
            payload.add("agents", agentsArray);
            if (defaultId != null) {
                payload.addProperty("defaultAgentId", defaultId);
            } else {
                payload.add("defaultAgentId", null);
            }
            // v3: expose the global auto-compact threshold so the Settings UI
            // can render its current value without a second roundtrip.
            try {
                int threshold = settingsService.getSupervisorAgentManager().getAutoCompactThreshold();
                payload.addProperty("autoCompactThreshold", threshold);
            } catch (Exception ignored) {
                payload.addProperty("autoCompactThreshold",
                        com.github.claudecodegui.settings.SupervisorAgentManager.DEFAULT_AUTO_COMPACT_THRESHOLD);
            }
            // 2026-05-24: expose rotation-trigger thresholds (supervisor +
            // main-AI share these). UI renders four inputs; persistence goes
            // through set_rotation_config.
            try {
                com.github.claudecodegui.settings.RotationConfig rc =
                        settingsService.getSupervisorAgentManager().getRotationConfig();
                JsonObject rcObj = new JsonObject();
                rcObj.addProperty("softRatio", rc.softRatio);
                rcObj.addProperty("hardRatio", rc.hardRatio);
                rcObj.addProperty("softCompact", rc.softCompact);
                rcObj.addProperty("hardCompact", rc.hardCompact);
                payload.add("rotationConfig", rcObj);
            } catch (Exception ignored) {
                com.github.claudecodegui.settings.RotationConfig rc =
                        com.github.claudecodegui.settings.RotationConfig.defaults();
                JsonObject rcObj = new JsonObject();
                rcObj.addProperty("softRatio", rc.softRatio);
                rcObj.addProperty("hardRatio", rc.hardRatio);
                rcObj.addProperty("softCompact", rc.softCompact);
                rcObj.addProperty("hardCompact", rc.hardCompact);
                payload.add("rotationConfig", rcObj);
            }
            pushToWebview("window.updateSupervisorAgents", gson.toJson(payload));
        } catch (Exception e) {
            LOG.error("[SupervisorAgentHandler] Failed to get agents: " + e.getMessage(), e);
            pushToWebview("window.updateSupervisorAgents",
                    "{\"agents\":[],\"defaultAgentId\":null,\"autoCompactThreshold\":70}");
        }
    }

    private void handleGetAgent(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject agent = settingsService.getSupervisorAgent(id);

            JsonObject result = new JsonObject();
            if (agent != null) {
                result.add("agent", agent);
            } else {
                result.add("agent", null);
            }
            pushToWebview("window.onSupervisorAgentReceived", gson.toJson(result));
        } catch (Exception e) {
            LOG.error("[SupervisorAgentHandler] Failed to get agent: " + e.getMessage(), e);
            pushToWebview("window.onSupervisorAgentReceived", "{\"agent\":null}");
        }
    }

    private void handleAddAgent(String content) {
        try {
            JsonObject agent = gson.fromJson(content, JsonObject.class);
            settingsService.addSupervisorAgent(agent);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                pushToWebview("window.supervisorAgentOperationResult",
                        "{\"success\":true,\"operation\":\"add\"}");
            });
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentHandler] Failed to add agent: " + e.getMessage());
            sendOperationError("add", e.getMessage());
        }
    }

    private void handleUpdateAgent(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");
            settingsService.updateSupervisorAgent(id, updates);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                pushToWebview("window.supervisorAgentOperationResult",
                        "{\"success\":true,\"operation\":\"update\"}");
            });
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentHandler] Failed to update agent: " + e.getMessage());
            sendOperationError("update", e.getMessage());
        }
    }

    private void handleDeleteAgent(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            boolean deleted = settingsService.deleteSupervisorAgent(id);
            if (deleted) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    handleGetAgents();
                    pushToWebview("window.supervisorAgentOperationResult",
                            "{\"success\":true,\"operation\":\"delete\"}");
                });
            } else {
                sendOperationError("delete", "Agent not found");
            }
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentHandler] Failed to delete agent: " + e.getMessage());
            sendOperationError("delete", e.getMessage());
        }
    }

    private void handleGetDefault() {
        try {
            String defaultId = settingsService.getDefaultSupervisorAgentId();
            JsonObject result = new JsonObject();
            if (defaultId != null) {
                result.addProperty("defaultAgentId", defaultId);
            } else {
                result.add("defaultAgentId", null);
            }
            pushToWebview("window.onDefaultSupervisorAgentReceived", gson.toJson(result));
        } catch (Exception e) {
            LOG.error("[SupervisorAgentHandler] Failed to get default agent: " + e.getMessage(), e);
            pushToWebview("window.onDefaultSupervisorAgentReceived", "{\"defaultAgentId\":null}");
        }
    }

    private void handleSetDefault(String content) {
        try {
            String agentId = null;
            if (content != null && !content.isEmpty() && !content.equals("null")) {
                JsonObject data = gson.fromJson(content, JsonObject.class);
                if (data != null && data.has("id") && !data.get("id").isJsonNull()) {
                    agentId = data.get("id").getAsString();
                }
            }
            settingsService.setDefaultSupervisorAgentId(agentId);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            if (agentId != null) {
                result.addProperty("defaultAgentId", agentId);
            }
            pushToWebview("window.onDefaultSupervisorAgentChanged", gson.toJson(result));
            // Also refresh list so UI default-badge updates
            ApplicationManager.getApplication().invokeLater(this::handleGetAgents);
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentHandler] Failed to set default agent: " + e.getMessage());
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
            pushToWebview("window.onDefaultSupervisorAgentChanged", gson.toJson(result));
        }
    }

    /**
     * Settings page → daemon: update the rotation-trigger thresholds. Persists
     * to supervisor-agents.json. New values take effect on the next
     * health-check tick (no restart required — {@code SupervisorMonitor}
     * re-loads via {@code RotationConfig.loadOrDefault} each tick).
     */
    private void handleSetRotationConfig(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            if (data == null) throw new IllegalArgumentException("empty payload");
            double softRatio = data.has("softRatio") && !data.get("softRatio").isJsonNull()
                    ? data.get("softRatio").getAsDouble()
                    : com.github.claudecodegui.settings.RotationConfig.DEFAULT_SOFT_RATIO;
            double hardRatio = data.has("hardRatio") && !data.get("hardRatio").isJsonNull()
                    ? data.get("hardRatio").getAsDouble()
                    : com.github.claudecodegui.settings.RotationConfig.DEFAULT_HARD_RATIO;
            int softCompact = data.has("softCompact") && !data.get("softCompact").isJsonNull()
                    ? data.get("softCompact").getAsInt()
                    : com.github.claudecodegui.settings.RotationConfig.DEFAULT_SOFT_COMPACT;
            int hardCompact = data.has("hardCompact") && !data.get("hardCompact").isJsonNull()
                    ? data.get("hardCompact").getAsInt()
                    : com.github.claudecodegui.settings.RotationConfig.DEFAULT_HARD_COMPACT;

            com.github.claudecodegui.settings.RotationConfig next =
                    new com.github.claudecodegui.settings.RotationConfig(
                            softRatio, hardRatio, softCompact, hardCompact);
            String err = next.validate();
            if (err != null) {
                sendOperationError("set_rotation_config", err);
                return;
            }
            settingsService.getSupervisorAgentManager().setRotationConfig(next);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                pushToWebview("window.supervisorAgentOperationResult",
                        "{\"success\":true,\"operation\":\"update\"}");
            });
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentHandler] set_rotation_config failed: " + e.getMessage());
            sendOperationError("set_rotation_config", e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private void pushToWebview(String fn, String jsonPayload) {
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript(fn, escapeJs(jsonPayload)));
    }

    private void sendOperationError(String operation, String errorMessage) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("operation", operation);
        errorResult.addProperty("error", errorMessage);
        pushToWebview("window.supervisorAgentOperationResult", gson.toJson(errorResult));
    }
}
