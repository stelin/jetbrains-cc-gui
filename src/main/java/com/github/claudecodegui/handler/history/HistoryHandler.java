package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.session.pair.workflow.SupervisorWorkflowManager;
import com.github.claudecodegui.session.registry.SessionManifest;
import com.github.claudecodegui.session.registry.SessionRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.intellij.openapi.diagnostic.Logger;

/**
 * History data handler.
 * Routes history-related messages to dedicated service classes.
 */
public class HistoryHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(HistoryHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "load_history_data",
            "load_session",
            "delete_session",  // Delete session
            "export_session",  // Export session
            "toggle_favorite", // Toggle favorite status
            "update_title",    // Update session title
            "delete_title",    // Delete orphaned custom title (B-011)
            "deep_search_history", // Deep search (clear cache and reload)
            // Session-kind refactor (S4): the supervised / workflow history tabs.
            "load_supervised_history",
            "load_workflow_history"
    };

    // Session load callback interface
    public interface SessionLoadCallback {
        void onLoadSession(String sessionId, String projectPath);
    }

    /**
     * Session-kind refactor (S5): window-bound restore operations, implemented by
     * {@code ChatWindowDelegate} (which owns the ClaudeChatWindow + this tab's
     * PairHandler). {@link SupervisedRestoreService} drives the manifest read +
     * state stamping itself and calls back here for the two operations it cannot
     * reach from the handler context.
     */
    public interface SupervisedRestoreBridge {
        /**
         * Resume this tab's main-AI leg from a prior transcript, re-stamping the
         * persistent {@code containerId} onto the rebuilt session so pair routing
         * (and thus supervisor notification) survives the session swap. No-op on
         * null/empty mainSessionId.
         */
        void resumeMainSession(String mainSessionId, String containerId);
        /** Start (resume) the supervisor pair wired to this tab. Null on failure. */
        PairSession startSupervisorPair(PairSessionManager.StartPairParams params);
    }

    private SessionLoadCallback sessionLoadCallback;
    private SupervisedRestoreBridge supervisedRestoreBridge;
    private String currentProvider = "claude"; // Default to claude

    private final HistoryLoadService historyLoadService;
    private final HistoryDeleteService historyDeleteService;
    private final HistoryExportService historyExportService;
    private final HistoryMessageInjector historyMessageInjector;
    private final HistoryMetadataService historyMetadataService;
    // Session-kind refactor (S4): supervised / workflow history tab data sources.
    private final SupervisedHistoryService supervisedHistoryService;
    private final WorkflowHistoryService workflowHistoryService;
    // Session-kind refactor (S5): manifest-driven supervised restore.
    private final SupervisedRestoreService supervisedRestoreService;

    public HistoryHandler(HandlerContext context) {
        super(context);
        NodeJsServiceCaller nodeJsServiceCaller = new NodeJsServiceCaller(context);
        this.historyLoadService = new HistoryLoadService(context, nodeJsServiceCaller);
        this.historyDeleteService = new HistoryDeleteService(context, nodeJsServiceCaller, historyLoadService);
        this.historyExportService = new HistoryExportService(context);
        this.historyMessageInjector = new HistoryMessageInjector(context);
        this.historyMetadataService = new HistoryMetadataService(context, nodeJsServiceCaller);
        this.supervisedHistoryService = new SupervisedHistoryService(context);
        this.workflowHistoryService = new WorkflowHistoryService(context);
        this.supervisedRestoreService = new SupervisedRestoreService(context);
    }

    public void setSessionLoadCallback(SessionLoadCallback callback) {
        this.sessionLoadCallback = callback;
    }

    /** Session-kind refactor (S5): wire the window-bound supervised restore bridge. */
    public void setSupervisedRestoreBridge(SupervisedRestoreBridge bridge) {
        this.supervisedRestoreBridge = bridge;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "load_history_data":
                LOG.debug("[HistoryHandler] 处理: load_history_data, provider=" + content);
                this.currentProvider = content != null && !content.isEmpty() ? content : "claude";
                historyLoadService.handleLoadHistoryData(currentProvider);
                return true;
            case "load_session":
                LOG.debug("[HistoryHandler] 处理: load_session");
                handleLoadSession(content);
                return true;
            case "delete_session":
                LOG.info("[HistoryHandler] 处理: delete_session, sessionId=" + content);
                historyDeleteService.handleDeleteSession(content, currentProvider);
                return true;
            case "export_session":
                LOG.info("[HistoryHandler] 处理: export_session, sessionId=" + content);
                historyExportService.handleExportSession(content, currentProvider);
                return true;
            case "toggle_favorite":
                LOG.info("[HistoryHandler] 处理: toggle_favorite, sessionId=" + content);
                historyMetadataService.handleToggleFavorite(content);
                return true;
            case "update_title":
                LOG.info("[HistoryHandler] 处理: update_title");
                historyMetadataService.handleUpdateTitle(content);
                return true;
            case "delete_title":
                LOG.info("[HistoryHandler] 处理: delete_title, sessionId=" + content);
                historyMetadataService.handleDeleteTitle(content);
                return true;
            case "deep_search_history":
                LOG.info("[HistoryHandler] 处理: deep_search_history, provider=" + content);
                this.currentProvider = content != null && !content.isEmpty() ? content : "claude";
                historyLoadService.handleDeepSearchHistory(currentProvider);
                return true;
            case "load_supervised_history":
                LOG.debug("[HistoryHandler] 处理: load_supervised_history");
                supervisedHistoryService.list();
                return true;
            case "load_workflow_history":
                LOG.debug("[HistoryHandler] 处理: load_workflow_history");
                workflowHistoryService.list();
                return true;
            default:
                return false;
        }
    }

    /**
     * Session-kind refactor (S5): route load_session three ways by payload. Normal
     * sends a bare sessionId string (unchanged path); supervised / workflow send a
     * JSON object {@code {containerId, kind}} and route by the persistent container id.
     */
    private void handleLoadSession(String content) {
        String containerId = null;
        String sessionId = null;
        String kind = null;
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("{")) {
            // Supervised / workflow restore payload.
            try {
                JsonObject o = JsonParser.parseString(trimmed).getAsJsonObject();
                containerId = strOrNull(o, "containerId");
                sessionId = strOrNull(o, "sessionId");
                kind = strOrNull(o, "kind");
            } catch (Exception e) {
                sessionId = content; // malformed JSON — fall back to bare sessionId
            }
        } else {
            sessionId = content; // bare sessionId string — the normal path
        }

        if (containerId != null && !containerId.isEmpty()) {
            if ("workflow".equals(kind)) {
                workflowRestore(containerId);
            } else {
                supervisedRestoreService.restore(containerId, supervisedRestoreBridge);
            }
        } else {
            historyMessageInjector.handleLoadSession(sessionId, currentProvider, sessionLoadCallback);
        }
    }

    /**
     * Session-kind refactor (S5): resume a workflow by its container id. For a
     * workflow container {@code containerId == workflowId == wfId}; the existing
     * {@code SupervisorWorkflowManager.resumeWorkflow} is reused unchanged.
     */
    private void workflowRestore(String containerId) {
        try {
            if (context.getProject() == null) return;
            SessionManifest m = SessionRegistry.getInstance(context.getProject()).get(containerId);
            String workflowId = (m != null && m.workflowId != null && !m.workflowId.isEmpty())
                    ? m.workflowId : containerId;
            SupervisorWorkflowManager.getInstance(context.getProject()).resumeWorkflow(workflowId);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] workflow restore failed for " + containerId + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
