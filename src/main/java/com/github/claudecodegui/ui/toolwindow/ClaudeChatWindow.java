package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.history.HistoryHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.PermissionHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionCallbackAdapter;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.SessionLoadService;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.ChatWindowDelegate;
import com.github.claudecodegui.ui.EditorContextTracker;
import com.github.claudecodegui.ui.WebviewInitializer;
import com.github.claudecodegui.ui.WebviewWatchdog;
import com.github.claudecodegui.util.HtmlLoader;
import com.github.claudecodegui.util.JsUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.jcef.JBCefBrowser;

import javax.swing.*;
import java.awt.*;

/**
 * Chat window instance. Coordinates UI components, session management,
 * and message dispatching. One instance per tab.
 */
public class ClaudeChatWindow {

    private static final Logger LOG = Logger.getInstance(ClaudeChatWindow.class);

    private final JPanel mainPanel;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final Project project;
    private final CodemossSettingsService settingsService;
    private final HtmlLoader htmlLoader;

    private Content parentContent;
    private String originalTabName;
    private volatile String sessionId = null;

    /**
     * Per-tab stable identifier. Generated once at construction; used by
     * {@link PairSessionManager} to scope Supervisor Pairs to the tab that
     * created them so a different tab cannot inherit them via
     * {@code replayActivePairs} or send input to them via the project-shared
     * lookup map. Disposed pairs are reaped from the manager via
     * {@code stopPairsOwnedBy(windowId)} in {@link #dispose()}.
     */
    private final String windowId = java.util.UUID.randomUUID().toString();

    private JBCefBrowser browser;
    private ClaudeSession session;
    private final WebviewWatchdog webviewWatchdog;
    private final StreamMessageCoalescer streamCoalescer;

    private volatile boolean disposed = false;
    private volatile boolean initialized = false;
    private volatile boolean frontendReady = false;
    private volatile boolean slashCommandsFetched = false;
    private volatile int fetchedSlashCommandsCount = 0;

    /**
     * Set when this tab was created as a "新监督者标签页" (new supervisor tab):
     * the tab is born supervised. Consumed once on the first
     * {@code frontend_ready} by {@link ChatWindowDelegate#handleFrontendReady()},
     * which then pushes {@code window.onRequestNewSupervised} so the webview
     * auto-opens the supervisor agent picker. Self-clearing so a webview reload
     * (watchdog/manual) doesn't re-open the picker over an active session.
     */
    private volatile boolean pendingSupervised = false;

    /**
     * Optional payload for a directed supervised tab (需求3): JSON
     * {@code {agentId, initialComposerText}}. When set (alongside
     * {@link #pendingSupervised}), the delegate pushes
     * {@code window.onRequestNewSupervisedWith} instead of the picker, so the new
     * tab creates the named session and prefills (without sending) the composer.
     * Self-clearing in {@link #consumePendingSupervisedPayload()}.
     */
    private volatile String pendingSupervisedPayload = null;

    /**
     * Set when this tab was created to open a history session in a fresh tab
     * (normal or supervised). Holds the ASCII-safe JSON {@code {sessionId,
     * containerId, kind}} to hand to {@code window.onRequestLoadHistory} once the
     * webview is ready, so the new tab loads that session in-place. Consumed once
     * (self-clearing) so a webview reload doesn't re-trigger the load.
     */
    private volatile String pendingHistoryLoad = null;

    /**
     * Set when this (normal) tab was created with a prefilled composer — e.g. the
     * 云效「建会话」button opens a fresh chat tab and seeds the input with the bug
     * prompt (unsent). Handed to {@code window.onRequestComposerPrefill} once the
     * webview is ready. Consumed once (self-clearing) so a reload doesn't re-seed.
     */
    private volatile String pendingComposerText = null;

    private HandlerContext handlerContext;
    private MessageDispatcher messageDispatcher;
    private PermissionHandler permissionHandler;
    private HistoryHandler historyHandler;
    private final SessionLifecycleManager sessionLifecycleManager;

    // Delegates
    private WebviewInitializer webviewInitializer;
    private final EditorContextTracker editorContextTracker;
    private final ChatWindowDelegate chatWindowDelegate;
    private SessionCallbackAdapter sessionCallbackAdapter;

    public ClaudeChatWindow(Project project) {
        this(project, false);
    }

    public ClaudeChatWindow(Project project, boolean skipRegister) {
        this.project = project;
        this.claudeSDKBridge = new ClaudeSDKBridge(project);
        this.codexSDKBridge = new CodexSDKBridge();
        this.settingsService = new CodemossSettingsService();
        this.htmlLoader = new HtmlLoader(getClass());
        this.mainPanel = new JPanel(new BorderLayout());

        this.mainPanel.setBackground(com.github.claudecodegui.util.ThemeConfigService.getBackgroundColor());

        this.streamCoalescer = new StreamMessageCoalescer(new StreamMessageCoalescer.JsCallbackTarget() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                ClaudeChatWindow.this.callJavaScript(functionName, args);
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }
        });

        this.webviewWatchdog = new WebviewWatchdog(
                mainPanel,
                () -> browser,
                htmlLoader,
                () -> webviewInitializer.recreateWebview("watchdog_recreate"),
                () -> disposed,
                // Streaming-grace condition: main-AI streaming OR any supervisor
                // turn in flight. Without the latter, a supervisor IPC burst
                // (large Read tool_results, multi-message tick) saturates the
                // EDT, the heartbeat/RAF age trips the 45s timeout, and the
                // watchdog reloads the webview — wiping the supervisor pane.
                () -> streamCoalescer.isStreamActive()
                        || PairSessionManager.getInstance(project).hasActiveSupervisorTurn()
        );

        this.session = new ClaudeSession(project, claudeSDKBridge, codexSDKBridge, windowId);

        this.chatWindowDelegate = new ChatWindowDelegate(createDelegateHost());
        chatWindowDelegate.loadPermissionModeFromSettings();
        chatWindowDelegate.loadNodePathFromSettings();
        chatWindowDelegate.syncActiveProvider();
        chatWindowDelegate.initializeHandlers();
        this.sessionId = chatWindowDelegate.setupPermissionService();

        this.sessionLifecycleManager = new SessionLifecycleManager(new SessionLifecycleManager.SessionHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public void setSession(ClaudeSession s) {
                session = s;
                persistTabSessionState();
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public void clearPendingPermissionRequests() {
                permissionHandler.clearPendingRequests();
            }

            @Override
            public void clearPermissionDecisionMemory() {
                try {
                    if (sessionId != null && !sessionId.isEmpty()) {
                        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
                        permissionService.clearDecisionMemory();
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to clear permission decision memory: " + e.getMessage());
                }
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setupSessionCallbacks() {
                ClaudeChatWindow.this.setupSessionCallbacks();
            }

            @Override
            public void invalidateSessionCallbacks() {
                if (sessionCallbackAdapter != null) {
                    sessionCallbackAdapter.deactivate();
                }
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                fetchedSlashCommandsCount = count;
            }
        });

        this.editorContextTracker = new EditorContextTracker(project, new EditorContextTracker.ContextCallback() {
            @Override
            public void addSelectionInfo(String info) {
                callJavaScript("addSelectionInfo", info);
            }

            @Override
            public void clearSelectionInfo() {
                callJavaScript("clearSelectionInfo");
            }
        });
        editorContextTracker.registerListeners();

        this.webviewInitializer = new WebviewInitializer(createWebviewHost());

        setupSessionCallbacks();
        initializeSessionInfo();

        // Delay JCEF browser creation to avoid service initialization conflicts
        // during JBCefApp$Holder class init (ProxyMigrationService dependency).
        // Operations that depend on browser readiness are also deferred.
        ToolWindowManager.getInstance(this.project).invokeLater(() -> {
            if (!this.disposed) {
                this.webviewInitializer.createUIComponents();
                registerSessionLoadListener();
                this.initialized = true;
                LOG.info("Window instance fully initialized, project: " + this.project.getName());
            }
        });

        if (!skipRegister) {
            registerInstance();
        }
        chatWindowDelegate.initializeStatusBar();
        SendShortcutSync.syncFromSettings();
    }

    // ==================== Public API ====================

    public void setParentContent(Content content) {
        if (this.parentContent != null && this.parentContent != content) {
            ClaudeSDKToolWindow.unregisterContentMapping(this.parentContent);
            LOG.debug("[MultiTab] Unregistered old Content -> ClaudeChatWindow mapping");
        }

        this.parentContent = content;
        if (content != null) {
            ClaudeSDKToolWindow.registerContentMapping(content, this);
            LOG.debug("[MultiTab] Registered Content -> ClaudeChatWindow mapping for: " + content.getDisplayName());

            if (this.originalTabName == null) {
                String displayName = content.getDisplayName();
                this.originalTabName = displayName.endsWith("...")
                        ? displayName.substring(0, displayName.length() - 3)
                        : displayName;
                LOG.debug("[TabLoading] Auto-initialized original tab name: " + this.originalTabName);
            }

            persistTabSessionState();
        }
    }

    public void setOriginalTabName(String name) {
        this.originalTabName = (name != null && name.endsWith("..."))
                ? name.substring(0, name.length() - 3)
                : name;
        LOG.debug("[TabLoading] Set original tab name: " + this.originalTabName);
    }

    public boolean isDisposed() {
        return disposed;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Content getParentContent() {
        return parentContent;
    }

    public JPanel getContent() {
        return mainPanel;
    }

    public ClaudeSDKBridge getClaudeSDKBridge() {
        return claudeSDKBridge;
    }

    /**
     * This tab's delegate. Exposed so a workflow node launch can reach the node
     * tab's own {@code PairHandler} ({@code getChatWindowDelegate().getPairHandler()})
     * and start the node's supervisor through the standard, fully-wired
     * {@code pair_start} path bound to this tab's webview (see IdeNodeLauncher).
     */
    public ChatWindowDelegate getChatWindowDelegate() {
        return chatWindowDelegate;
    }

    /**
     * Per-tab stable identifier (see {@link #windowId}). Exposed so the workflow
     * engine ({@code SupervisorWorkflowManager}) can scope a node's Pair back to
     * the tab it created — used as {@code StartPairParams.ownerWindowId} and as
     * the key for {@code PairSessionManager.stopPairsOwnedBy} on abort.
     * See {@code docs/workflow/coding-plan.md} §12.2.
     */
    public String getWindowId() {
        return windowId;
    }

    /**
     * Mark this tab as a born-at-birth supervisor tab (see {@link #pendingSupervised}).
     * Called by {@code TabHandler} right after construction, before the webview
     * mounts, for the header "新监督者标签页" action.
     */
    public void setPendingSupervised(boolean pending) {
        this.pendingSupervised = pending;
    }

    /**
     * Atomically read-and-clear the pending-supervised marker. Returns {@code true}
     * exactly once per {@link #setPendingSupervised(boolean) staging}, so the
     * supervisor agent picker auto-opens on the first {@code frontend_ready} but
     * not on subsequent webview reloads.
     */
    public synchronized boolean consumePendingSupervised() {
        if (pendingSupervised) {
            pendingSupervised = false;
            return true;
        }
        return false;
    }

    /**
     * Stage a directed supervised tab (需求3): marks the tab supervised AND records
     * the {@code {agentId, initialComposerText}} payload. Equivalent to
     * {@link #setPendingSupervised(boolean) setPendingSupervised(true)} for the
     * boolean gate, but additionally lets the delegate route to
     * {@code onRequestNewSupervisedWith}.
     */
    public void setPendingSupervisedPayload(String json) {
        this.pendingSupervised = true;
        this.pendingSupervisedPayload = json;
    }

    /**
     * Atomically read-and-clear the directed supervised-tab payload. Returns the
     * staged JSON exactly once (null otherwise / for a plain supervised tab), so
     * the directed create fires on the first {@code frontend_ready} but not on a
     * later webview reload.
     */
    public synchronized String consumePendingSupervisedPayload() {
        String v = pendingSupervisedPayload;
        pendingSupervisedPayload = null;
        return v;
    }

    /**
     * Stage a history-load request to run once this tab's webview is ready (see
     * {@link #pendingHistoryLoad}). Called by {@code TabHandler} right after
     * construction, before the webview mounts.
     */
    public void setPendingHistoryLoad(String json) {
        this.pendingHistoryLoad = json;
    }

    /**
     * Atomically read-and-clear the staged history-load JSON. Returns the payload
     * exactly once per staging (null otherwise), so the load fires on the first
     * {@code frontend_ready} but not on subsequent webview reloads.
     */
    public synchronized String consumePendingHistoryLoad() {
        String v = pendingHistoryLoad;
        pendingHistoryLoad = null;
        return v;
    }

    /**
     * Stage prefill text to seed this tab's composer once its webview is ready
     * (see {@link #pendingComposerText}). Called by {@code TabHandler} right after
     * construction, before the webview mounts.
     */
    public void setPendingComposerText(String text) {
        this.pendingComposerText = text;
    }

    /**
     * Atomically read-and-clear the staged composer prefill. Returns the text
     * exactly once per staging (null otherwise), so it seeds on the first
     * {@code frontend_ready} but not on subsequent webview reloads.
     */
    public synchronized String consumePendingComposerText() {
        String v = pendingComposerText;
        pendingComposerText = null;
        return v;
    }

    /**
     * Workflow node main-AI config JSON ({@code {model, reasoningEffort}}), staged by
     * {@link #applyNodeMainAiConfig} once the supervisor pair has started so this node
     * window's MAIN AI (left pane) runs with the SAME model + thinking depth as the
     * supervisor — keeping both legs consistent with the node.
     *
     * <p>NON-consuming: re-pushed to the webview on EVERY {@code frontend_ready} (incl.
     * a watchdog reload), because the main composer's model/reasoning live in webview
     * React state a reload wipes — without re-seeding, the reloaded composer would snap
     * back to the user's global localStorage selection.
     */
    private volatile String nodeMainAiConfig = null;

    /** Current staged node main-AI config JSON, or null for a non-node window. */
    public String getNodeMainAiConfig() {
        return nodeMainAiConfig;
    }

    /**
     * Stage AND immediately push the node main-AI config to the webview. Called by
     * {@code IdeNodeLauncher} once the supervisor pair has started (so we can use the
     * supervisor's RESOLVED model/reasoning — node override OR agent default — instead
     * of the raw, possibly-null node fields). By pair-start the webview is long since
     * mounted, so the push lands (unlike a {@code frontend_ready}-time push, which can
     * race the React handler registration and get silently dropped). Staging also keeps
     * it for re-push on a later reload (handleFrontendReady → onWorkflowNodeMainAi).
     * Safe to call off the EDT — callJavaScript marshals + null/dispose-guards.
     */
    public void applyNodeMainAiConfig(String json) {
        this.nodeMainAiConfig = json;
        if (json == null || json.isEmpty()) {
            return;
        }
        callJavaScript("onWorkflowNodeMainAi", com.github.claudecodegui.util.JsUtils.escapeJs(json));
    }

    public CodexSDKBridge getCodexSDKBridge() {
        return codexSDKBridge;
    }

    /**
     * Get the project associated with this chat window.
     *
     * @return the current project.
     */
    public Project getProject() {
        return this.project;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Session resume (SR10, session-resume-plan.md): resume this window's main-AI
     * session from a prior transcript by session_id — the same path the history
     * view uses to "open a past session and continue" ({@code loadHistorySession}).
     * Used by {@code IdeNodeLauncher} when recovering a workflow node so the node's
     * main AI continues its prior conversation instead of starting fresh. No-op on
     * a blank id; marshalled onto the EDT (loadHistorySession touches the webview).
     */
    public void resumeMainSession(String sessionId) {
        resumeMainSession(sessionId, null);
    }

    /**
     * Session-kind refactor: variant that carries the workflow node's persistent
     * {@code containerId} so the resumed main-AI session keeps its pair routing.
     * Without it loadHistorySession's fresh SessionState drops the containerId and
     * the node's supervisor stops being notified (no report_turn_completion).
     */
    public void resumeMainSession(String sessionId, String containerId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Runnable r = () -> {
            try {
                String projectPath = sessionLifecycleManager.determineWorkingDirectory();
                sessionLifecycleManager.loadHistorySession(sessionId, projectPath, containerId);
            } catch (Exception e) {
                LOG.warn("[ClaudeChatWindow] resumeMainSession failed for " + sessionId + ": " + e.getMessage());
            }
        };
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null || app.isDispatchThread()) r.run(); else app.invokeLater(r);
    }

    public ClaudeSession getSession() {
        return session;
    }

    public void restorePersistedTabSessionState(TabStateService.TabSessionState savedState) {
        if (savedState == null || session == null) {
            return;
        }

        if (savedState.permissionMode != null && !savedState.permissionMode.trim().isEmpty()) {
            session.setPermissionMode(savedState.permissionMode);
        }
        if (savedState.provider != null && !savedState.provider.trim().isEmpty()) {
            session.setProvider(savedState.provider);
        }
        if (savedState.model != null && !savedState.model.trim().isEmpty()) {
            session.setModel(savedState.model);
        }
        if (savedState.reasoningEffort != null && !savedState.reasoningEffort.trim().isEmpty()) {
            session.setReasoningEffort(savedState.reasoningEffort);
        }

        String restoredSessionId = isNonEmpty(savedState.sessionId) ? savedState.sessionId : null;
        String restoredCwd = isNonEmpty(savedState.cwd) ? savedState.cwd : session.getCwd();
        session.setSessionInfo(restoredSessionId, restoredCwd);
        persistTabSessionState();

        LOG.info("[TabRestore] Restored tab session state: provider=" + savedState.provider
                + ", sessionId=" + savedState.sessionId + ", cwd=" + savedState.cwd + ")");
    }

    public void addCodeSnippetFromExternal(String selectionInfo) {
        addCodeSnippet(selectionInfo);
    }

    public void updateTabStatus(ChatWindowDelegate.TabAnswerStatus status) {
        chatWindowDelegate.updateTabStatus(status);
    }

    @Deprecated
    public void updateTabLoadingState(boolean loading) {
        chatWindowDelegate.updateTabLoadingState(loading);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        chatWindowDelegate.sendQuickFixMessage(prompt, isQuickFix, callback);
    }

    public void executeJavaScriptCode(String jsCode) {
        if (this.disposed || this.browser == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!this.disposed && this.browser != null) {
                this.browser.getCefBrowser().executeJavaScript(jsCode, this.browser.getCefBrowser().getURL(), 0);
            }
        });
    }

    // ==================== JavaScript Bridge ====================

    private static final java.util.regex.Pattern SAFE_JS_FUNCTION_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$.]*$");

    void callJavaScript(String functionName, String... args) {
        if (disposed || browser == null) {
            LOG.warn("Cannot call JS function " + functionName + ": disposed=" + disposed + ", browser=" + (browser == null ? "null" : "exists"));
            return;
        }

        if (functionName == null || !SAFE_JS_FUNCTION_NAME.matcher(functionName).matches()) {
            LOG.error("Invalid JavaScript function name rejected: " + functionName);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || browser == null) {
                return;
            }
            try {
                String callee = functionName;
                if (!functionName.contains(".")) {
                    callee = "window." + functionName;
                }

                StringBuilder argsJs = new StringBuilder();
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) argsJs.append(", ");
                        String arg = args[i] == null ? "" : args[i];
                        argsJs.append("'").append(arg).append("'");
                    }
                }

                String checkAndCall =
                        "(function() {" +
                                "  try {" +
                                "    if (typeof " + callee + " === 'function') {" +
                                "      " + callee + "(" + argsJs + ");" +
                                "    }" +
                                "  } catch (e) {" +
                                "    console.error('[Backend->Frontend] Failed to call " + functionName + ":', e);" +
                                "  }" +
                                "})();";

                browser.getCefBrowser().executeJavaScript(checkAndCall, browser.getCefBrowser().getURL(), 0);
            } catch (Exception e) {
                LOG.warn("Failed to call JS function: " + functionName + ", error: " + e.getMessage(), e);
            }
        });
    }

    void handleJavaScriptMessage(String message) {
        if (message.startsWith("{\"type\":\"console.")) {
            try {
                JsonObject json = new Gson().fromJson(message, JsonObject.class);
                String logType = json.get("type").getAsString();
                JsonArray args = json.getAsJsonArray("args");

                StringBuilder logMessage = new StringBuilder("[Webview] ");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) logMessage.append(" ");
                    logMessage.append(args.get(i).toString());
                }

                if ("console.error".equals(logType)) {
                    LOG.warn(logMessage.toString());
                } else if ("console.warn".equals(logType)) {
                    LOG.info(logMessage.toString());
                } else {
                    LOG.debug(logMessage.toString());
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse console log: " + e.getMessage());
            }
            return;
        }

        String[] parts = message.split(":", 2);
        if (parts.length < 1) {
            LOG.error("Invalid message format");
            return;
        }

        String type = parts[0];
        String content = parts.length > 1 ? parts[1] : "";

        if (messageDispatcher.dispatch(type, content)) {
            return;
        }

        LOG.warn("Unknown message type: " + type);
    }

    /**
     * Forward a raw webview message ("type:content") into THIS window's dispatcher.
     *
     * <p>Used by the detached {@code BugAnalysisFrame}: its 建监督者/建会话 (create_new_tab /
     * create_new_supervised_tab) actions are forwarded here so the tabs open in the MAIN
     * tool window, not in the detached analysis window (which has no tab container).
     */
    public void dispatchWebviewMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return;
        }
        handleJavaScriptMessage(rawMessage);
    }

    // ==================== Session Delegates ====================

    private void setupSessionCallbacks() {
        if (this.sessionCallbackAdapter != null) {
            this.sessionCallbackAdapter.deactivate();
        }
        this.sessionCallbackAdapter = new SessionCallbackAdapter(
                streamCoalescer,
                new SessionCallbackAdapter.JsTarget() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        ClaudeChatWindow.this.callJavaScript(functionName, args);
                    }
                },
                permissionHandler,
                () -> slashCommandsFetched,
                this::onStreamEnded
        ) {
            @Override
            public void onSessionIdReceived(String newSessionId) {
                super.onSessionIdReceived(newSessionId);
                sessionId = newSessionId;
                persistTabSessionState();
            }
        };
        session.setCallback(sessionCallbackAdapter);
        persistTabSessionState();
    }

    private void onStreamEnded() {
        if (session == null) {
            return;
        }
        if ("claude".equals(session.getProvider()) && session.getError() == null) {
            com.github.claudecodegui.notifications.ClaudeNotifier.showSuccess(project, "Task completed");
        }
    }

    private void initializeSessionInfo() {
        String workingDirectory = sessionLifecycleManager.determineWorkingDirectory();
        session.setSessionInfo(null, workingDirectory);
        persistTabSessionState();
        LOG.info("Initialized with working directory: " + workingDirectory);
    }

    private void registerSessionLoadListener() {
        SessionLoadService.getInstance().setListener((sessionId, projectPath) -> {
            ApplicationManager.getApplication().invokeLater(() ->
                    sessionLifecycleManager.loadHistorySession(sessionId, projectPath));
        });
    }

    private void registerInstance() {
        ClaudeSDKToolWindow.registerWindow(project, this);
    }

    private void interruptDueToPermissionDenial() {
        this.session.interrupt().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onPermissionDenied");
            callJavaScript("onStreamEnd");
            callJavaScript("showLoading", "false");
            com.github.claudecodegui.notifications.ClaudeNotifier.clearStatus(project);
        }));
    }

    private int getTabIndex() {
        Content content = this.parentContent;
        if (content == null) {
            return -1;
        }
        ContentManager contentManager = content.getManager();
        if (contentManager == null) {
            return -1;
        }
        return contentManager.getIndexOfContent(content);
    }

    private void persistTabSessionState() {
        if (project == null || project.isDisposed() || session == null) {
            return;
        }

        int tabIndex = getTabIndex();
        if (tabIndex < 0) {
            return;
        }

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.provider = session.getProvider();
        snapshot.sessionId = session.getSessionId();
        snapshot.cwd = session.getCwd();
        snapshot.model = session.getModel();
        snapshot.permissionMode = session.getPermissionMode();
        snapshot.reasoningEffort = session.getReasoningEffort();

        TabStateService.getInstance(project).saveTabSessionState(tabIndex, snapshot);
    }

    private boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    // ==================== Code Snippets ====================

    private void addCodeSnippet(String selectionInfo) {
        if (selectionInfo != null && !selectionInfo.isEmpty()) {
            callJavaScript("addCodeSnippet", JsUtils.escapeJs(selectionInfo));
        }
    }

    // ==================== Dispose ====================

    public synchronized void dispose() {
        if (this.disposed) return;
        this.disposed = true;

        chatWindowDelegate.dispose();
        editorContextTracker.dispose();
        streamCoalescer.dispose();
        if (sessionCallbackAdapter != null) {
            sessionCallbackAdapter.dispose();
        }
        webviewWatchdog.stop();

        try {
            if (this.sessionId != null && !this.sessionId.isEmpty()) {
                PermissionService permissionService = PermissionService.getInstance(project, this.sessionId);
                permissionService.unregisterDialogShower(project);
                permissionService.unregisterAskUserQuestionDialogShower(project);
                permissionService.unregisterPlanApprovalDialogShower(project);
                PermissionService.removeInstance(this.sessionId);
                LOG.info("Removed PermissionService instance for sessionId: " + this.sessionId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister dialog showers or remove session instance: " + e.getMessage());
        }

        LOG.info("Starting window resource cleanup, project: " + project.getName());

        handlerContext.setDisposed(true);

        if (parentContent != null) {
            ClaudeSDKToolWindow.unregisterContentMapping(parentContent);
            LOG.debug("[MultiTab] Removed Content -> ClaudeChatWindow mapping during dispose");
        }

        ClaudeSDKToolWindow.unregisterWindow(project, this);

        try {
            if (session != null) session.interrupt();
        } catch (Exception e) {
            LOG.warn("Failed to clean up session: " + e.getMessage());
        }

        // Stop every Pair this tab created BEFORE killing the daemon. The pairs'
        // SupervisorBridge captured this tab's ClaudeSDKBridge, so leaving them
        // alive after the daemon shutdown would leave dangling references in the
        // project-scoped PairSessionManager that no other tab can drive.
        try {
            if (project != null && !project.isDisposed()) {
                PairSessionManager.getInstance(project).stopPairsOwnedBy(windowId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to stop owned pairs on tab dispose: " + e.getMessage());
        }

        try {
            if (claudeSDKBridge != null) {
                int activeCount = claudeSDKBridge.getActiveProcessCount();
                if (activeCount > 0) {
                    LOG.info("Cleaning up " + activeCount + " active Claude process(es)...");
                }
                claudeSDKBridge.cleanupAllProcesses();
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up Claude processes: " + e.getMessage());
        }

        try {
            if (codexSDKBridge != null) {
                int activeCount = codexSDKBridge.getActiveProcessCount();
                if (activeCount > 0) {
                    LOG.info("Cleaning up " + activeCount + " active Codex process(es)...");
                }
                codexSDKBridge.cleanupAllProcesses();
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up Codex processes: " + e.getMessage());
        }

        try {
            if (browser != null) {
                browser.dispose();
                browser = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up browser: " + e.getMessage());
        }

        if (messageDispatcher != null) {
            messageDispatcher.clear();
        }

        LOG.info("Window resources fully cleaned up, project: " + project.getName());
    }

    // ==================== Host Interface Factories ====================

    private WebviewInitializer.WebviewHost createWebviewHost() {
        return new WebviewInitializer.WebviewHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public HtmlLoader getHtmlLoader() {
                return htmlLoader;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setBrowser(JBCefBrowser b) {
                browser = b;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public void handleJavaScriptMessage(String msg) {
                ClaudeChatWindow.this.handleJavaScriptMessage(msg);
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }
        };
    }

    private ChatWindowDelegate.DelegateHost createDelegateHost() {
        return new ChatWindowDelegate.DelegateHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public String getWindowId() {
                return windowId;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public CodemossSettingsService getSettingsService() {
                return settingsService;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public Content getParentContent() {
                return parentContent;
            }

            @Override
            public String getOriginalTabName() {
                return originalTabName;
            }

            @Override
            public void setOriginalTabName(String name) {
                ClaudeChatWindow.this.setOriginalTabName(name);
            }

            @Override
            public String getSessionId() {
                return sessionId;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public void setHandlerContext(HandlerContext ctx) {
                handlerContext = ctx;
            }

            @Override
            public void setMessageDispatcher(MessageDispatcher d) {
                messageDispatcher = d;
            }

            @Override
            public void setPermissionHandler(PermissionHandler h) {
                permissionHandler = h;
            }

            @Override
            public void setHistoryHandler(HistoryHandler h) {
                historyHandler = h;
            }

            @Override
            public SessionLifecycleManager getSessionLifecycleManager() {
                return sessionLifecycleManager;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public PermissionHandler getPermissionHandler() {
                return permissionHandler;
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public void interruptDueToPermissionDenial() {
                ClaudeChatWindow.this.interruptDueToPermissionDenial();
            }

            @Override
            public boolean isFrontendReady() {
                return frontendReady;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                fetchedSlashCommandsCount = count;
            }

            @Override
            public void persistTabSessionState() {
                ClaudeChatWindow.this.persistTabSessionState();
            }

            @Override
            public boolean consumePendingSupervised() {
                return ClaudeChatWindow.this.consumePendingSupervised();
            }

            @Override
            public String consumePendingSupervisedPayload() {
                return ClaudeChatWindow.this.consumePendingSupervisedPayload();
            }

            @Override
            public String consumePendingHistoryLoad() {
                return ClaudeChatWindow.this.consumePendingHistoryLoad();
            }

            @Override
            public String consumePendingComposerText() {
                return ClaudeChatWindow.this.consumePendingComposerText();
            }

            @Override
            public String getNodeMainAiConfig() {
                return ClaudeChatWindow.this.getNodeMainAiConfig();
            }
        };
    }
}
