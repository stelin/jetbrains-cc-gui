package com.github.claudecodegui.provider.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.IBridge;
import com.github.claudecodegui.provider.common.SDKResult;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude Agent SDK bridge.
 * Handles Java to Node.js SDK communication, supports async and streaming responses.
 */
public class ClaudeSDKBridge extends BaseSDKBridge {

    private final ClaudeStreamAdapter streamAdapter;
    private final ClaudeRequestParamsBuilder requestParamsBuilder;
    private final ClaudeJsonOutputExtractor jsonOutputExtractor;
    private final ClaudeDaemonCoordinator daemonCoordinator;
    private final ClaudeProcessInvoker processInvoker;
    private final ClaudeQueryExecutor queryExecutor;
    private final ClaudeSessionQueryService sessionQueryService;
    private final ClaudeMcpQueryService mcpQueryService;
    private final ClaudeRewindService rewindService;
    private final ClaudeDaemonRequestExecutor daemonRequestExecutor;

    /** In-flight headless bug analysis sessions keyed by projectId (design §3/§8). */
    private final ConcurrentHashMap<String, AnalysisHandle> activeAnalyses = new ConcurrentHashMap<>();

    /**
     * Legacy constructor — keep for callers that have no Project on hand. In
     * remote mode the resulting bridge will fail with PROJECT_NOT_OPEN, since
     * projectPath is mandatory at session creation time.
     */
    public ClaudeSDKBridge() {
        this(null);
    }

    public ClaudeSDKBridge(Project project) {
        super(ClaudeSDKBridge.class);

        // Shared dependencies extracted once to avoid repeated lambda allocation
        java.util.function.Supplier<File> sdkDirSupplier = () -> getDirectoryResolver().findSdkDir();

        this.streamAdapter = new ClaudeStreamAdapter(gson);
        this.requestParamsBuilder = new ClaudeRequestParamsBuilder(gson);
        this.jsonOutputExtractor = new ClaudeJsonOutputExtractor();
        ClaudeLogSanitizer logSanitizer = new ClaudeLogSanitizer();

        this.daemonCoordinator = new ClaudeDaemonCoordinator(
                LOG, nodeDetector, this::getDirectoryResolver, envConfigurator, project
        );
        this.processInvoker = new ClaudeProcessInvoker(
                LOG, gson, nodeDetector, sdkDirSupplier, processManager,
                envConfigurator, requestParamsBuilder, logSanitizer, streamAdapter
        );
        this.queryExecutor = new ClaudeQueryExecutor(
                gson, nodeDetector, sdkDirSupplier, processManager,
                envConfigurator, jsonOutputExtractor
        );
        this.sessionQueryService = new ClaudeSessionQueryService(
                LOG, gson, nodeDetector, sdkDirSupplier,
                envConfigurator, jsonOutputExtractor
        );
        this.mcpQueryService = new ClaudeMcpQueryService(
                LOG, gson, nodeDetector, sdkDirSupplier, processManager,
                envConfigurator, jsonOutputExtractor
        );
        this.rewindService = new ClaudeRewindService(
                LOG, gson, nodeDetector, sdkDirSupplier, processManager,
                envConfigurator, jsonOutputExtractor
        );
        this.daemonRequestExecutor = new ClaudeDaemonRequestExecutor(
                LOG, requestParamsBuilder, streamAdapter, jsonOutputExtractor
        );
    }

    /**
     * Shut down the daemon process.
     */
    public void shutdownDaemon() {
        daemonCoordinator.shutdownDaemon();
    }

    /**
     * Format the most recent RemoteBridge start failure (if any) for inclusion
     * in user-facing error messages. Returns null when no structured failure
     * was recorded.
     */
    private String formatStartFailure() {
        String code = daemonCoordinator.getLastStartFailureCode();
        String msg  = daemonCoordinator.getLastStartFailureMessage();
        if (code == null && msg == null) return null;
        StringBuilder sb = new StringBuilder();
        if (code != null) sb.append(code);
        if (msg != null) {
            if (sb.length() > 0) sb.append(": ");
            sb.append(msg);
        }
        // Append actionable hints for the most common error codes.
        if ("PROJECT_PATH_NOT_ACCESSIBLE".equals(code)) {
            sb.append("。请在「设置 → 远程模式 → 路径映射」配置本地→远端的根目录映射，"
                    + "或确认 server 端确实存在该项目目录。");
        } else if ("PROJECT_PATH_REQUIRED".equals(code)) {
            sb.append("。Server 要求 projectPath，请检查插件版本与 server 是否匹配。");
        } else if ("PROJECT_NOT_OPEN".equals(code)) {
            sb.append("。请先在 IDE 中打开一个项目。");
        }
        return sb.toString();
    }

    public void prewarmDaemonAsync(String cwd) {
        prewarmDaemonAsync(cwd, null);
    }

    /**
     * Prewarm daemon asynchronously to reduce first-message latency.
     */
    public void prewarmDaemonAsync(String cwd, String runtimeSessionEpoch) {
        daemonCoordinator.prewarmDaemonAsync(cwd, runtimeSessionEpoch);
    }

    public void resetPersistentRuntime(String runtimeSessionEpoch) {
        daemonCoordinator.resetPersistentRuntime(runtimeSessionEpoch);
    }

    /**
     * Inject the {@link com.github.claudecodegui.permission.ControlMessageHandler}
     * used by remote-mode bridges to surface permission/ask/plan dialogs.
     * Only effective when running with a {@link com.github.claudecodegui.provider.common.RemoteBridge};
     * local mode silently ignores it.
     */
    public void setControlMessageHandler(com.github.claudecodegui.permission.ControlMessageHandler handler) {
        daemonCoordinator.setControlMessageHandler(handler);
    }

    /**
     * Inject a daemon lifecycle listener (ready / died), applied to the current
     * daemon bridge and remembered for future ones. Used by DN9 (§16.5): the
     * supervisor's dedicated bridge funnels its workflow node to WAITING_HUMAN
     * when its daemon dies (RemoteBridge gateway_error/SSE-close or local exit).
     */
    public void setLifecycleListener(IBridge.DaemonLifecycleListener listener) {
        daemonCoordinator.setLifecycleListener(listener);
    }

    @Override
    public void cleanupAllProcesses() {
        shutdownDaemon();
        super.cleanupAllProcesses();
    }

    /**
     * Interrupt a channel. In daemon mode, sends an abort command to cancel the
     * active request. Also delegates to ProcessManager for per-process fallback.
     */
    @Override
    public void interruptChannel(String channelId) {
        IBridge db = daemonCoordinator.getCurrentDaemonBridge();
        if (db != null && db.isAlive()) {
            LOG.info("[ClaudeSDKBridge] Sending daemon abort for channel: " + channelId);
            try {
                db.sendAbort();
            } catch (Exception e) {
                LOG.error("[ClaudeSDKBridge] Daemon abort failed: " + e.getMessage());
            }
        }
        // Also try per-process interrupt (covers per-process fallback mode)
        super.interruptChannel(channelId);
    }

    // ============================================================================
    // Abstract method implementations
    // ============================================================================

    @Override
    protected String getProviderName() {
        return "claude";
    }

    @Override
    protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        env.put("CLAUDE_USE_STDIN", "true");
    }

    @Override
    protected void processOutputLine(
            String line,
            MessageCallback callback,
            SDKResult result,
            StringBuilder assistantContent,
            boolean[] hadSendError,
            String[] lastNodeError
    ) {
        if (line.startsWith("[STDIN_ERROR]")
                || line.startsWith("[STDIN_PARSE_ERROR]")
                || line.startsWith("[GET_SESSION_ERROR]")
                || line.startsWith("[PERSIST_ERROR]")) {
            LOG.warn("[Node.js ERROR] " + line);
        }
        streamAdapter.processOutputLine(line, callback, result, assistantContent, hadSendError, lastNodeError);
    }

    // ============================================================================
    // Node.js detection methods (Claude-specific extensions)
    // ============================================================================

    /**
     * Detect Node.js and return detailed results.
     */
    public NodeDetectionResult detectNodeWithDetails() {
        return nodeDetector.detectNodeWithDetails();
    }

    /**
     * Clear Node.js detection cache.
     */
    public void clearNodeCache() {
        nodeDetector.clearCache();
    }

    /**
     * Verify Node.js path and return version.
     */
    public String verifyNodePath(String path) {
        return nodeDetector.verifyNodePath(path);
    }

    /**
     * Get cached Node.js version.
     */
    public String getCachedNodeVersion() {
        return nodeDetector.getCachedNodeVersion();
    }

    /**
     * Get cached Node.js path.
     */
    public String getCachedNodePath() {
        return nodeDetector.getCachedNodePath();
    }

    /**
     * Verify and cache Node.js path.
     */
    public NodeDetectionResult verifyAndCacheNodePath(String path) {
        return nodeDetector.verifyAndCacheNodePath(path);
    }

    // ============================================================================
    // Bridge directory methods
    // ============================================================================

    /**
     * Set claude-bridge directory path manually.
     */
    public void setSdkTestDir(String path) {
        getDirectoryResolver().setSdkDir(path);
    }

    /**
     * Get current claude-bridge directory.
     */
    public File getSdkTestDir() {
        return getDirectoryResolver().getSdkDir();
    }

    // ============================================================================
    // Sync query methods (Claude-specific)
    // ============================================================================

    /**
     * Execute query synchronously (blocking).
     */
    public SDKResult executeQuerySync(String prompt) {
        return executeQuerySync(prompt, 60);
    }

    /**
     * Execute query synchronously with timeout.
     */
    public SDKResult executeQuerySync(String prompt, int timeoutSeconds) {
        return queryExecutor.executeQuerySync(prompt, timeoutSeconds);
    }

    /**
     * Execute query asynchronously.
     */
    public CompletableFuture<SDKResult> executeQueryAsync(String prompt) {
        return queryExecutor.executeQueryAsync(prompt);
    }

    /**
     * Execute query with streaming.
     */
    public CompletableFuture<SDKResult> executeQueryStream(String prompt, MessageCallback callback) {
        return queryExecutor.executeQueryStream(prompt, callback);
    }

    /**
     * Send a raw daemon command (NDJSON method + params) on the active daemon
     * bridge and stream output lines to the supplied callback.
     *
     * <p>Used by features that piggy-back on the existing daemon process — e.g.
     * the Supervisor channel ({@code supervisor.start} / {@code supervisor.postEvent}
     * / {@code supervisor.stop}). Returns a failed future if no daemon is running.
     *
     * @param method  e.g. {@code supervisor.postEvent}
     * @param params  command parameters; may include {@code env} which the daemon honours
     * @param callback NDJSON output callback
     */
    public CompletableFuture<Boolean> sendDaemonCommand(
            String method,
            com.google.gson.JsonObject params,
            com.github.claudecodegui.provider.common.IBridge.DaemonOutputCallback callback
    ) {
        com.github.claudecodegui.provider.common.IBridge bridge = daemonCoordinator.getDaemonBridge();
        if (bridge == null) {
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "Daemon bridge not available — cannot send method " + method));
            return failed;
        }
        return bridge.sendCommand(method, params, callback);
    }

    // ============================================================================
    // Headless bug analysis (design §3 / §8)
    // ============================================================================

    /**
     * Run a one-shot headless bug analysis in an isolated scratch session.
     *
     * <p>Uses an independent {@code sessionId + runtimeSessionEpoch} so the scratch
     * runtime never touches the user's chat session. On completion the scratch runtime
     * is cleaned up via {@code claude.resetRuntime}.
     *
     * @param projectId      cloud project id — used as analysis key and echoed in progress/result events
     * @param message        full analysis prompt (built by {@link BugAnalysisPrompt})
     * @param bugs           bug snapshot for the collector's {@code total} counter
     * @param model          model id to use (may include {@code [1m]} suffix — passed through as-is)
     * @param reasoningEffort reasoning effort level; {@code null} to omit
     * @param cwd            working directory (any valid project dir — used for {@code IDEA_PROJECT_PATH})
     * @param cb             terminal callbacks for result / fallback / transport-error push
     */
    public void analyzeBugsHeadless(String projectId, String message, JsonArray bugs,
                                    String model, String reasoningEffort, String cwd,
                                    BugAnalysisHandlerCallbacks cb) {
        IBridge bridge = daemonCoordinator.getDaemonBridge();
        if (bridge == null) {
            cb.onTransportError("daemon 不可用");
            return;
        }

        // A scratch analysis is a brand-new, throwaway session — it must NEVER resume.
        // Any non-empty sessionId makes the daemon run `claude -p --resume <sessionId>`, and
        // the CLI rejects an id that isn't a real UUID / existing session title
        // ("--resume requires a valid session ID ... is not a UUID"). So pass an EMPTY
        // sessionId (= new session, no --resume) and isolate purely via the unique
        // runtimeSessionEpoch: findRuntimeForRequest looks an empty-sessionId request up
        // only in anonymousRuntimesBySignature (signature includes the epoch), so a unique
        // epoch yields a fresh runtime that never reuses/evicts the chat runtime, and it
        // stays the reset key below (resetRuntime matches by runtimeSessionEpoch).
        String scratchEpoch = "epoch-" + UUID.randomUUID();

        JsonObject params = requestParamsBuilder.buildSendParams(
                message,
                "",                  // sessionId: EMPTY → new session, never --resume
                scratchEpoch,        // runtimeSessionEpoch: unique → isolated runtime + reset key
                cwd,
                "bypassPermissions", // no dialogs
                model,
                null,                // attachments
                null,                // openedFiles
                null,                // agentPrompt
                Boolean.TRUE,        // streaming
                null,                // disableThinking (let reasoningEffort take effect)
                reasoningEffort,
                null,                // systemPromptAppend
                null                 // windowId: not tied to any tab
        );
        params.add("env", ClaudeBridgeUtils.buildDaemonEnv(cwd));

        AnalysisHandle handle = new AnalysisHandle(projectId, scratchEpoch);
        activeAnalyses.put(projectId, handle);

        BugAnalysisCollector collector = new BugAnalysisCollector(handle, bugs, cb);
        bridge.sendCommand("claude.send", params, collector)
              .whenComplete((ok, err) -> {
                  activeAnalyses.remove(projectId, handle);
                  // Release the scratch runtime to prevent accumulation (design §8)
                  JsonObject reset = new JsonObject();
                  reset.addProperty("runtimeSessionEpoch", scratchEpoch);
                  bridge.sendCommand("claude.resetRuntime", reset, IBridge.DaemonOutputCallback.NOOP);
              });
    }

    /**
     * Cancel an in-flight headless bug analysis (design §8).
     *
     * <p>Sets {@code handle.canceled = true} so the collector skips the result push on
     * completion. If the analysis is the daemon's current active request, also sends
     * {@code abort} to interrupt it immediately. If the analysis is still queued behind
     * a chat turn, only the flag is set — abort is intentionally skipped to avoid
     * interrupting the wrong request; the collector short-circuits on first {@code onLine}
     * check when the queued turn eventually starts.
     *
     * @param projectId cloud project id matching the key passed to {@link #analyzeBugsHeadless}
     */
    public void cancelBugAnalysis(String projectId) {
        AnalysisHandle handle = activeAnalyses.get(projectId);
        if (handle == null) {
            return;
        }
        handle.canceled = true;
        IBridge db = daemonCoordinator.getCurrentDaemonBridge();
        if (db != null && db.isAlive()) {
            try {
                db.sendAbort();
            } catch (Exception e) {
                LOG.warn("[ClaudeSDKBridge] cancelBugAnalysis abort failed: " + e.getMessage());
            }
        }
    }

    // ============================================================================
    // Multi-turn interaction support
    // ============================================================================

    /**
     * Send message in existing channel (streaming response).
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            MessageCallback callback
    ) {
        return sendMessage(channelId, message, sessionId, null, cwd, attachments, null, null, null, null, null, false, null, null, null, callback);
    }

    /**
     * Send message in existing channel (streaming response, with permission mode and model selection).
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String model,
            JsonObject openedFiles,
            String agentPrompt,
            MessageCallback callback
    ) {
        return sendMessage(channelId, message, sessionId, null, cwd, attachments, permissionMode, model, openedFiles, agentPrompt, null, false, null, null, null, callback);
    }

    /**
     * Send message in existing channel (streaming response, with all options including streaming flag).
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String model,
            JsonObject openedFiles,
            String agentPrompt,
            Boolean streaming,
            MessageCallback callback
    ) {
        return sendMessage(channelId, message, sessionId, null, cwd, attachments, permissionMode, model, openedFiles, agentPrompt, streaming, false, null, null, null, callback);
    }

    /**
     * Send message in existing channel (streaming response, with all options including streaming flag and disableThinking).
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String model,
            JsonObject openedFiles,
            String agentPrompt,
            Boolean streaming,
            Boolean disableThinking,
            MessageCallback callback
    ) {
        return sendMessage(channelId, message, sessionId, null, cwd, attachments, permissionMode,
                model, openedFiles, agentPrompt, streaming, disableThinking, null, null, null, callback);
    }

    /**
     * Send message in existing channel (streaming response, with all options including streaming flag, disableThinking and reasoningEffort).
     *
     * <p>Phase 6c (2026-05-24): {@code systemPromptAppend} carries a one-shot
     * handoff prompt staged by {@code ClaudeSession.swapInnerSession}. Most
     * callers pass {@code null}; only {@code SessionSendService.sendToClaude}
     * threads a non-null value after consuming it from {@link SessionState}.
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String runtimeSessionEpoch,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String model,
            JsonObject openedFiles,
            String agentPrompt,
            Boolean streaming,
            Boolean disableThinking,
            String reasoningEffort,
            String systemPromptAppend,
            String windowId,
            MessageCallback callback
    ) {
        // Try daemon mode first (avoids per-request Node.js process spawning)
        IBridge db = daemonCoordinator.getDaemonBridge();
        if (db != null) {
            return sendMessageViaDaemon(db, channelId, message, sessionId, runtimeSessionEpoch, cwd,
                    attachments, permissionMode, model, openedFiles, agentPrompt,
                    streaming, disableThinking, reasoningEffort, systemPromptAppend, windowId, callback);
        }

        // In remote mode the per-process fallback would silently spawn a local
        // Node.js process and run the SDK on the user's machine — defeating the
        // purpose of remote mode. Surface a clear error instead.
        com.github.claudecodegui.settings.RemoteModeContext rmCtx =
                com.github.claudecodegui.settings.RemoteModeContext.getInstance();
        if (rmCtx != null && rmCtx.isRemote()) {
            String url = rmCtx.remoteServerUrl();
            String reason = formatStartFailure();
            String err = "Remote ai-bridge-server not available at " + url
                    + (reason != null ? " — " + reason : " (session not started or daemon failed to become ready)")
                    + ". Per-process fallback is disabled in remote mode.";
            LOG.warn("[ClaudeSDKBridge] " + err);
            CompletableFuture<SDKResult> failed = new CompletableFuture<>();
            SDKResult result = new SDKResult();
            result.success = false;
            result.error = err;
            try { callback.onError(err); } catch (Exception ignore) {}
            failed.complete(result);
            return failed;
        }

        // Local-mode fallback: per-process mode (spawns a new Node.js process per request)
        LOG.info("[ClaudeSDKBridge] Using per-process mode (daemon not available)");
        return processInvoker.sendMessage(
                channelId,
                message,
                sessionId,
                runtimeSessionEpoch,
                cwd,
                attachments,
                permissionMode,
                model,
                openedFiles,
                agentPrompt,
                streaming,
                disableThinking,
                reasoningEffort,
                systemPromptAppend,
                windowId,
                callback
        );
    }

    /**
     * Get session history messages.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return sessionQueryService.getSessionMessages(sessionId, cwd);
    }

    public JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
        return sessionQueryService.getLatestUserMessage(sessionId, cwd);
    }

    /**
     * Get MCP server connection status.
     */
    public CompletableFuture<List<JsonObject>> getMcpServerStatus(String cwd) {
        return mcpQueryService.getMcpServerStatus(cwd);
    }

    /**
     * Get MCP server tools list.
     */
    public CompletableFuture<JsonObject> getMcpServerTools(String serverId) {
        return mcpQueryService.getMcpServerTools(serverId);
    }

    // ============================================================================
    // Rewind files support
    // ============================================================================

    /**
     * Rewind files to a specific user message state.
     * Uses the SDK's rewindFiles() API to restore files to their state at a given message.
     *
     * @param sessionId The session ID
     * @param userMessageId The user message UUID to rewind to
     * @param cwd Working directory for the session
     * @return CompletableFuture with the result
     */
    public CompletableFuture<JsonObject> rewindFiles(String sessionId, String userMessageId, String cwd) {
        return rewindService.rewindFiles(sessionId, userMessageId, cwd);
    }

    public CompletableFuture<JsonObject> rewindFiles(String sessionId, String userMessageId) {
        return rewindFiles(sessionId, userMessageId, null);
    }

    // ============================================================================
    // Daemon mode message sending
    // ============================================================================

    /**
     * Send message via the long-running daemon process.
     * This avoids the ~5-10s overhead of spawning a new Node.js process per request.
     */
    private CompletableFuture<SDKResult> sendMessageViaDaemon(
            IBridge daemon,
            String channelId,
            String message,
            String sessionId,
            String runtimeSessionEpoch,
            String cwd,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String model,
            JsonObject openedFiles,
            String agentPrompt,
            Boolean streaming,
            Boolean disableThinking,
            String reasoningEffort,
            String systemPromptAppend,
            String windowId,
            MessageCallback callback
    ) {
        return daemonRequestExecutor.sendMessageViaDaemon(
                daemon,
                channelId,
                message,
                sessionId,
                runtimeSessionEpoch,
                cwd,
                attachments,
                permissionMode,
                model,
                openedFiles,
                agentPrompt,
                streaming,
                disableThinking,
                reasoningEffort,
                systemPromptAppend,
                windowId,
                callback
        );
    }
}
