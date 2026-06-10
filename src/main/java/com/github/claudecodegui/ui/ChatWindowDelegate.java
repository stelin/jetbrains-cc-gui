package com.github.claudecodegui.ui;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.handler.AgentHandler;
import com.github.claudecodegui.handler.SupervisorAgentHandler;
import com.github.claudecodegui.handler.PairHandler;
import com.github.claudecodegui.handler.ClipboardHandler;
import com.github.claudecodegui.handler.CodexMcpServerHandler;
import com.github.claudecodegui.handler.DependencyHandler;
import com.github.claudecodegui.handler.DiffHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.history.HistoryHandler;
import com.github.claudecodegui.handler.McpServerHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.PermissionHandler;
import com.github.claudecodegui.handler.PromptEnhancerHandler;
import com.github.claudecodegui.handler.PromptHandler;
import com.github.claudecodegui.handler.RemoteSyncHandler;
import com.github.claudecodegui.handler.provider.ProviderHandler;
import com.github.claudecodegui.handler.RewindHandler;
import com.github.claudecodegui.handler.SessionHandler;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.handler.SkillHandler;
import com.github.claudecodegui.handler.TabHandler;
import com.github.claudecodegui.handler.WindowEventHandler;
import com.github.claudecodegui.handler.WorkflowHandler;
import com.github.claudecodegui.handler.file.FileExportHandler;
import com.github.claudecodegui.handler.file.FileHandler;
import com.github.claudecodegui.handler.file.UndoFileHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.permission.RemotePermissionAdapter;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.MessageJsonConverter;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delegates for initialization setup and runtime operations:
 * handler registration, permission setup, tab status, QuickFix, and frontend ready handling.
 */
public class ChatWindowDelegate {

    private static final Logger LOG = Logger.getInstance(ChatWindowDelegate.class);
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";
    private static final int STATUS_RESET_DELAY_SECONDS = 5;

    public enum TabAnswerStatus {
        IDLE,
        ANSWERING,
        COMPLETED
    }

    public interface DelegateHost {
        Project getProject();
        /**
         * Per-tab stable id (UUID) used to scope Supervisor Pairs. Generated
         * once at {@code ClaudeChatWindow} construction.
         */
        String getWindowId();
        ClaudeSDKBridge getClaudeSDKBridge();
        CodexSDKBridge getCodexSDKBridge();
        ClaudeSession getSession();
        CodemossSettingsService getSettingsService();
        JPanel getMainPanel();
        JBCefBrowser getBrowser();
        boolean isDisposed();
        void callJavaScript(String fn, String... args);
        Content getParentContent();
        String getOriginalTabName();
        void setOriginalTabName(String name);
        String getSessionId();
        HandlerContext getHandlerContext();
        void setHandlerContext(HandlerContext ctx);
        void setMessageDispatcher(MessageDispatcher d);
        void setPermissionHandler(PermissionHandler h);
        void setHistoryHandler(HistoryHandler h);
        SessionLifecycleManager getSessionLifecycleManager();
        StreamMessageCoalescer getStreamCoalescer();
        WebviewWatchdog getWebviewWatchdog();
        PermissionHandler getPermissionHandler();
        void interruptDueToPermissionDenial();
        boolean isFrontendReady();
        void setFrontendReady(boolean ready);
        void setSlashCommandsFetched(boolean fetched);
        void setFetchedSlashCommandsCount(int count);
        void persistTabSessionState();
        /**
         * Read-and-clear the born-at-birth supervisor-tab marker. {@code true}
         * exactly once, on the first {@code frontend_ready} of a "新监督者标签页".
         */
        boolean consumePendingSupervised();
        /**
         * Read-and-clear the staged history-load JSON ({@code {sessionId,
         * containerId, kind}}). Non-null exactly once, on the first
         * {@code frontend_ready} of a tab opened via {@code open_history_in_new_tab}.
         */
        String consumePendingHistoryLoad();
    }

    private final DelegateHost host;
    private TabAnswerStatus currentTabStatus = TabAnswerStatus.IDLE;
    private ScheduledFuture<?> statusResetTask;
    private volatile String pendingQuickFixPrompt = null;
    private volatile MessageCallback pendingQuickFixCallback = null;
    /**
     * Kept so {@link #handleFrontendReady()} can re-emit {@code onPairResume}
     * for any active pair after a webview reload. Without this, a watchdog
     * reload wipes the supervisor pane even though the Java/daemon side is
     * still live. Set during {@link #initializeHandlers()}.
     */
    private PairHandler pairHandler;
    /**
     * Kept so {@link #dispose()} can unbind this tab's workflow broadcast sink
     * from {@code SupervisorWorkflowManager} before {@code MessageDispatcher.clear()}
     * runs on tab close — otherwise the engine would push to a disposed browser
     * (coding-plan §9 / §12.3). Set during {@link #initializeHandlers()}.
     */
    private WorkflowHandler workflowHandler;

    public ChatWindowDelegate(DelegateHost host) {
        this.host = host;
    }

    /**
     * This tab's Pair lifecycle handler. Exposed so a workflow node launch can
     * start the node's supervisor through the node tab's own
     * {@link PairHandler#startPairWired} — binding the node's supervisor
     * transport to this tab's webview/context (see IdeNodeLauncher). Non-null
     * after {@link #initializeHandlers()} (run in the ClaudeChatWindow ctor).
     */
    public PairHandler getPairHandler() {
        return pairHandler;
    }

    public void loadNodePathFromSettings() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedNodePath = props.getValue(NODE_PATH_PROPERTY_KEY);

            if (savedNodePath != null && !savedNodePath.trim().isEmpty()) {
                String path = savedNodePath.trim();
                claudeSDKBridge.setNodeExecutable(path);
                codexSDKBridge.setNodeExecutable(path);
                claudeSDKBridge.verifyAndCacheNodePath(path);
                LOG.info("Using manually configured Node.js path: " + path);
            } else {
                LOG.info("No saved Node.js path found, attempting auto-detection...");
                com.github.claudecodegui.model.NodeDetectionResult detected =
                    claudeSDKBridge.detectNodeWithDetails();

                if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                    String detectedPath = detected.getNodePath();
                    String detectedVersion = detected.getNodeVersion();

                    props.setValue(NODE_PATH_PROPERTY_KEY, detectedPath);
                    claudeSDKBridge.setNodeExecutable(detectedPath);
                    codexSDKBridge.setNodeExecutable(detectedPath);
                    claudeSDKBridge.verifyAndCacheNodePath(detectedPath);

                    LOG.info("Auto-detected Node.js: " + detectedPath + " (" + detectedVersion + ")");
                } else {
                    LOG.warn("Failed to auto-detect Node.js path. Error: " +
                        (detected != null ? detected.getErrorMessage() : "Unknown error"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load Node.js path: " + e.getMessage(), e);
        }
    }

    public void loadPermissionModeFromSettings() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            if (savedMode != null && !savedMode.trim().isEmpty()) {
                String mode = savedMode.trim();
                ClaudeSession session = host.getSession();
                if (session != null) {
                    session.setPermissionMode(mode);
                    host.persistTabSessionState();
                    LOG.info("Loaded permission mode from settings: " + mode);
                    com.github.claudecodegui.notifications.ClaudeNotifier.setMode(host.getProject(), mode);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load permission mode: " + e.getMessage());
        }
    }

    public void savePermissionModeToSettings(String mode) {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
            LOG.info("Saved permission mode to settings: " + mode);
        } catch (Exception e) {
            LOG.warn("Failed to save permission mode: " + e.getMessage());
        }
    }

    public void syncActiveProvider() {
        try {
            CodemossSettingsService settingsService = host.getSettingsService();
            if (settingsService.isLocalProviderActive()) {
                LOG.info("[ClaudeSDKToolWindow] Local provider active, skipping startup sync");
                return;
            }
            settingsService.applyActiveProviderToClaudeSettings();
        } catch (Exception e) {
            LOG.warn("Failed to sync active provider on startup: " + e.getMessage());
        }
    }

    public String setupPermissionService() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        Project project = host.getProject();
        String sessionId = claudeSDKBridge.getSessionId();

        if ((sessionId == null || sessionId.isEmpty()) && codexSDKBridge != null) {
            sessionId = codexSDKBridge.getSessionId();
        }

        if (sessionId == null || sessionId.isEmpty()) {
            LOG.warn("Failed to get session ID from bridges, generating fallback UUID");
            sessionId = java.util.UUID.randomUUID().toString();
        }

        claudeSDKBridge.setSessionId(sessionId);
        if (codexSDKBridge != null) {
            codexSDKBridge.setSessionId(sessionId);
        }
        LOG.info("Unified bridge sessionId for PermissionService routing: " + sessionId);

        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
        permissionService.start();
        permissionService.registerDialogShower(project, (toolName, inputs) ->
            host.getPermissionHandler().showFrontendPermissionDialog(toolName, inputs));
        permissionService.registerAskUserQuestionDialogShower(project, (requestId, questionsData) ->
            host.getPermissionHandler().showAskUserQuestionDialog(requestId, questionsData));
        permissionService.registerPlanApprovalDialogShower(project, (requestId, planData) ->
            host.getPermissionHandler().showPlanApprovalDialog(requestId, planData));
        LOG.info("Started permission service with frontend dialog, AskUserQuestion dialog, and PlanApproval dialog for project: " + project.getName());

        // Remote mode: wire control-message handler so daemon's _ctrl messages
        // (permission/ask/plan) surface in the same dialog UI as local mode.
        if (RemoteModeContext.getInstance().isRemote()) {
            RemotePermissionAdapter adapter = new RemotePermissionAdapter(project, sessionId);
            claudeSDKBridge.setControlMessageHandler(adapter);
            if (codexSDKBridge != null) {
                // CodexSDKBridge would need an analogous setControlMessageHandler;
                // for now Codex remote mode shares the same coordinator pattern.
                LOG.info("Remote mode: control handler wired for ClaudeSDKBridge (Codex pending)");
            } else {
                LOG.info("Remote mode: control handler wired");
            }
        }
        return sessionId;
    }

    public void initializeHandlers() {
        Project project = host.getProject();
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        CodemossSettingsService settingsService = host.getSettingsService();

        HandlerContext.JsCallback jsCallback = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                host.callJavaScript(functionName, args);
            }
            @Override
            public String escapeJs(String str) {
                return JsUtils.escapeJs(str);
            }
        };

        HandlerContext handlerContext = new HandlerContext(
                project, host.getWindowId(), claudeSDKBridge, codexSDKBridge, settingsService, jsCallback);
        handlerContext.setSession(host.getSession());
        host.setHandlerContext(handlerContext);

        MessageDispatcher messageDispatcher = new MessageDispatcher();
        host.setMessageDispatcher(messageDispatcher);

        messageDispatcher.registerHandler(new ProviderHandler(handlerContext));
        messageDispatcher.registerHandler(new McpServerHandler(handlerContext));
        messageDispatcher.registerHandler(new CodexMcpServerHandler(handlerContext, settingsService.getCodexMcpServerManager()));
        messageDispatcher.registerHandler(new SkillHandler(handlerContext));
        messageDispatcher.registerHandler(new FileHandler(handlerContext));
        messageDispatcher.registerHandler(new SettingsHandler(handlerContext));
        messageDispatcher.registerHandler(new SessionHandler(handlerContext));
        messageDispatcher.registerHandler(new FileExportHandler(handlerContext));
        messageDispatcher.registerHandler(new DiffHandler(handlerContext));
        messageDispatcher.registerHandler(new PromptEnhancerHandler(handlerContext));
        messageDispatcher.registerHandler(new AgentHandler(handlerContext));
        messageDispatcher.registerHandler(new SupervisorAgentHandler(handlerContext));
        this.pairHandler = new PairHandler(handlerContext);
        messageDispatcher.registerHandler(this.pairHandler);
        this.workflowHandler = new WorkflowHandler(handlerContext);
        messageDispatcher.registerHandler(this.workflowHandler);
        messageDispatcher.registerHandler(new PromptHandler(handlerContext));
        messageDispatcher.registerHandler(new TabHandler(handlerContext));
        messageDispatcher.registerHandler(new RewindHandler(handlerContext));
        messageDispatcher.registerHandler(new UndoFileHandler(handlerContext));
        messageDispatcher.registerHandler(new DependencyHandler(handlerContext));
        messageDispatcher.registerHandler(new ClipboardHandler(handlerContext));
        messageDispatcher.registerHandler(new RemoteSyncHandler(handlerContext));

        messageDispatcher.registerHandler(new WindowEventHandler(handlerContext, new WindowEventHandler.Callback() {
            @Override public void onHeartbeat(String content) { host.getWebviewWatchdog().handleHeartbeat(content); }
            @Override public void onTabLoadingChanged(boolean loading) { updateTabLoadingState(loading); }
            @Override public void onTabStatusChanged(String statusStr) {
                TabAnswerStatus status;
                switch (statusStr) {
                    case "answering": status = TabAnswerStatus.ANSWERING; break;
                    case "completed": status = TabAnswerStatus.COMPLETED; break;
                    default: status = TabAnswerStatus.IDLE; break;
                }
                updateTabStatus(status);
            }
            @Override public void onCreateNewSession() {
                host.getSessionLifecycleManager().createNewSession();
            }
            @Override public void onFrontendReady() { handleFrontendReady(); }
            @Override public void onRefreshSlashCommands() {
                host.getSessionLifecycleManager().fetchSlashCommandsOnStartup();
            }
        }));

        PermissionHandler permissionHandler = new PermissionHandler(handlerContext);
        permissionHandler.setPermissionDeniedCallback(host::interruptDueToPermissionDenial);
        host.setPermissionHandler(permissionHandler);
        messageDispatcher.registerHandler(permissionHandler);

        HistoryHandler historyHandler = new HistoryHandler(handlerContext);
        historyHandler.setSessionLoadCallback((sessionId, projectPath) ->
            host.getSessionLifecycleManager().loadHistorySession(sessionId, projectPath));
        // Session-kind refactor (S5): supervised restore reaches this tab's window
        // (main-AI leg) + PairHandler (supervisor leg) through ChatWindowDelegate,
        // which owns both. Reuses loadHistorySession (the SR10 resume mechanism, same
        // as the normal sessionLoadCallback above + ClaudeChatWindow.resumeMainSession)
        // and PairHandler.startPairWired — no new resume machinery.
        historyHandler.setSupervisedRestoreBridge(new HistoryHandler.SupervisedRestoreBridge() {
            @Override
            public void resumeMainSession(String mainSessionId, String containerId) {
                if (mainSessionId == null || mainSessionId.isEmpty()) return; // null-safe: skip main leg
                Runnable r = () -> {
                    try {
                        String projectPath = host.getSessionLifecycleManager().determineWorkingDirectory();
                        host.getSessionLifecycleManager().loadHistorySession(mainSessionId, projectPath, containerId);
                    } catch (Exception e) {
                        LOG.warn("[ChatWindowDelegate] supervised main-AI resume failed: " + e.getMessage());
                    }
                };
                com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
                if (app == null || app.isDispatchThread()) r.run(); else app.invokeLater(r);
            }
            @Override
            public com.github.claudecodegui.session.pair.PairSession startSupervisorPair(
                    com.github.claudecodegui.session.pair.PairSessionManager.StartPairParams params) {
                try {
                    return ChatWindowDelegate.this.pairHandler.startPairWired(
                            params, new com.github.claudecodegui.session.pair.protocol.PairBudget());
                } catch (Exception e) {
                    LOG.warn("[ChatWindowDelegate] supervised pair resume failed: " + e.getMessage());
                    return null;
                }
            }
        });
        host.setHistoryHandler(historyHandler);
        messageDispatcher.registerHandler(historyHandler);

        LOG.info("Registered " + messageDispatcher.getHandlerCount() + " message handlers");
    }

    public void initializeStatusBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = host.getProject();
            if (project == null || host.isDisposed()) return;

            ClaudeSession session = host.getSession();
            String mode = session != null ? session.getPermissionMode() : "default";
            com.github.claudecodegui.notifications.ClaudeNotifier.setMode(project, mode);

            String model = session != null ? session.getModel() : "claude-sonnet-4-6";
            com.github.claudecodegui.notifications.ClaudeNotifier.setModel(project, model);

            try {
                CodemossSettingsService settingsService = host.getSettingsService();
                String selectedId = settingsService.getSelectedAgentId();
                if (selectedId != null) {
                    JsonObject agent = settingsService.getAgent(selectedId);
                    if (agent != null) {
                        String agentName = agent.has("name") ? agent.get("name").getAsString() : "Agent";
                        com.github.claudecodegui.notifications.ClaudeNotifier.setAgent(project, agentName);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to set initial agent in status bar: " + e.getMessage());
            }
        });
    }

    public void updateTabStatus(TabAnswerStatus status) {
        Content parentContent = host.getParentContent();
        String originalTabName = host.getOriginalTabName();
        if (parentContent == null || originalTabName == null) {
            LOG.warn("[TabStatus] Cannot update - parentContent or originalTabName is null");
            return;
        }

        if (status == currentTabStatus) {
            LOG.debug("[TabStatus] Skipping redundant update for tab: " + originalTabName);
            return;
        }

        currentTabStatus = status;

        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            String tabName = originalTabName;
            String currentDisplayName = parentContent.getDisplayName();
            if (currentDisplayName != null && !currentDisplayName.startsWith(tabName)) {
                tabName = currentDisplayName.endsWith("...")
                    ? currentDisplayName.substring(0, currentDisplayName.length() - 3)
                    : currentDisplayName;
                host.setOriginalTabName(tabName);
                LOG.debug("[TabStatus] Detected external rename, updated originalTabName to: " + tabName);
            }

            String displayName;
            switch (status) {
                case ANSWERING:
                    displayName = tabName + "...";
                    LOG.debug("[TabStatus] Set answering state for tab: " + displayName);
                    break;
                case COMPLETED:
                    String completedText = ClaudeCodeGuiBundle.message("tab.status.completed");
                    displayName = tabName + " (" + completedText + ")";
                    LOG.debug("[TabStatus] Set completed state for tab: " + displayName);

                    statusResetTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            updateTabStatus(TabAnswerStatus.IDLE);
                        });
                    }, STATUS_RESET_DELAY_SECONDS, TimeUnit.SECONDS);
                    break;
                case IDLE:
                default:
                    displayName = tabName;
                    LOG.debug("[TabStatus] Restored idle state for tab: " + displayName);
                    break;
            }
            parentContent.setDisplayName(displayName);
        });
    }

    @Deprecated
    public void updateTabLoadingState(boolean loading) {
        updateTabStatus(loading ? TabAnswerStatus.ANSWERING : TabAnswerStatus.IDLE);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null) {
            LOG.warn("QuickFix: Session is null, cannot send message");
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not initialized. Please wait for the tool window to fully load.");
            });
            return;
        }

        session.getContextCollector().setQuickFix(isQuickFix);

        if (!host.isFrontendReady()) {
            LOG.info("QuickFix: Frontend not ready, queuing message for later");
            pendingQuickFixPrompt = prompt;
            pendingQuickFixCallback = callback;
            return;
        }

        executeQuickFixInternal(prompt, callback);
    }

    private void executePendingQuickFix(String prompt, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not available");
            });
            return;
        }
        executeQuickFixInternal(prompt, callback);
    }

    private void executeQuickFixInternal(String prompt, MessageCallback callback) {
        String escapedPrompt = JsUtils.escapeJs(prompt);
        host.callJavaScript("addUserMessage", escapedPrompt);
        host.callJavaScript("showLoading", "true");

        host.getSession().send(prompt, null, (String) null).thenRun(() -> {
            List<ClaudeSession.Message> messages = host.getSession().getMessages();
            if (!messages.isEmpty()) {
                ClaudeSession.Message last = messages.get(messages.size() - 1);
                if (last.type == ClaudeSession.Message.Type.ASSISTANT && last.content != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callback.onComplete(SDKResult.success(last.content));
                    });
                }
            }
        }).exceptionally(ex -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError(ex.getMessage());
            });
            return null;
        });
    }

    public void handleFrontendReady() {
        LOG.info("Received frontend_ready signal, frontend is now ready to receive data");
        host.setFrontendReady(true);

        host.getSessionLifecycleManager().sendCurrentPermissionMode();
        replayCurrentSessionStateToFrontend();
        // 2026-05-24: re-bind supervisor pane to any still-alive pair after a
        // webview reload (WebviewWatchdog or manual). The Java PairSession +
        // daemon supervisor are still running; React state was lost so the
        // pane needs the SelectedSupervisor restored.
        if (pairHandler != null) {
            try { pairHandler.replayActivePairs(); }
            catch (Exception e) { LOG.warn("[ChatWindowDelegate] replayActivePairs failed: " + e.getMessage()); }
        }
        host.persistTabSessionState();

        // Born-at-birth supervisor tab ("新监督者标签页"): once the fresh tab's
        // webview is up, auto-open the supervisor agent picker. Consumed once so a
        // later webview reload won't re-prompt over an active session.
        if (host.consumePendingSupervised()) {
            LOG.info("[ChatWindowDelegate] Pending supervisor tab — opening agent picker");
            host.callJavaScript("onRequestNewSupervised");
        }

        // Tab opened to load a history session (normal or supervised): now that
        // its webview is ready, hand it the {sessionId, containerId, kind} payload
        // so it runs its own (in-place) loadHistorySession and the session loads
        // into THIS fresh tab. Consumed once so a reload doesn't re-load.
        String pendingHistoryLoad = host.consumePendingHistoryLoad();
        if (pendingHistoryLoad != null && !pendingHistoryLoad.isEmpty()) {
            LOG.info("[ChatWindowDelegate] Pending history load — " + pendingHistoryLoad);
            host.callJavaScript("onRequestLoadHistory", pendingHistoryLoad);
        }

        if (pendingQuickFixPrompt != null && pendingQuickFixCallback != null) {
            LOG.info("Processing pending QuickFix message after frontend ready");
            String prompt = pendingQuickFixPrompt;
            MessageCallback callback = pendingQuickFixCallback;
            pendingQuickFixPrompt = null;
            pendingQuickFixCallback = null;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                executePendingQuickFix(prompt, callback);
            });
        }

        host.getStreamCoalescer().flush(null);
    }

    private void replayCurrentSessionStateToFrontend() {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            return;
        }

        try {
            String sessionId = session.getSessionId();
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                host.callJavaScript("setSessionId", JsUtils.escapeJs(sessionId));
            }

            List<ClaudeSession.Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                String messagesJson = MessageJsonConverter.convertMessagesToJson(messages);
                host.callJavaScript("updateMessages", JsUtils.escapeJs(messagesJson));
            }

            host.callJavaScript("showLoading", String.valueOf(session.isLoading()));
            host.callJavaScript("showThinkingStatus", String.valueOf(false));

            String summary = session.getSummary();
            if (summary != null && !summary.trim().isEmpty()) {
                host.callJavaScript("showSummary", JsUtils.escapeJs(summary));
            }

            // FIX: Restore streaming state after webview reload.
            // When the watchdog reloads the webview during active streaming, the frontend's
            // isStreamingRef is reset to false, causing all onContentDelta callbacks to be
            // silently dropped.  Re-sending onStreamStart ensures the frontend accepts
            // subsequent streaming deltas and the stall watchdog is properly initialized.
            boolean streamActive = host.getStreamCoalescer().isStreamActive();
            if (streamActive) {
                LOG.debug("Replaying streaming state to frontend (session was actively streaming during reload)");
                host.callJavaScript("onStreamStart");
            }

            LOG.info("Replayed current session state to frontend: sessionId="
                    + (sessionId != null ? sessionId : "(none)")
                    + ", messages=" + messages.size()
                    + ", loading=" + session.isLoading()
                    + ", streaming=" + streamActive);
        } catch (Exception e) {
            LOG.warn("Failed to replay current session state to frontend: " + e.getMessage(), e);
        }
    }

    public void dispose() {
        // Unbind the workflow broadcast sink BEFORE MessageDispatcher.clear()
        // (called later in ClaudeChatWindow.dispose) so the engine stops pushing
        // to this tab's soon-to-be-disposed browser (coding-plan §9 / §12.3).
        if (workflowHandler != null) {
            try { workflowHandler.dispose(); }
            catch (Exception e) { LOG.warn("[Workflow] handler dispose failed: " + e.getMessage()); }
            workflowHandler = null;
        }
        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
            LOG.debug("[TabStatus] Cancelled pending status reset task");
        }
    }
}
