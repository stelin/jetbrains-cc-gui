package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Owns message-send orchestration while ClaudeSession remains the public session facade.
 */
public class SessionSendService {

    private static final Logger LOG = Logger.getInstance(SessionSendService.class);

    private final Project project;
    private final SessionState state;
    private final SessionCallbackFacade callbackFacade;
    private final MessageParser messageParser;
    private final MessageMerger messageMerger;
    private final Gson gson;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final SessionContextService contextService;

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson,
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            SessionContextService contextService
    ) {
        this.project = project;
        this.state = state;
        this.callbackFacade = callbackFacade;
        this.messageParser = messageParser;
        this.messageMerger = messageMerger;
        this.gson = gson;
        this.claudeSDKBridge = claudeSDKBridge;
        this.codexSDKBridge = codexSDKBridge;
        this.contextService = contextService;
    }

    public void prepareContextCollector(EditorContextCollector contextCollector) {
        contextCollector.setPsiContextEnabled(state.isPsiContextEnabled());
        contextCollector.setAutoOpenFileEnabled(readAutoOpenFileEnabled());
    }

    public void updateSessionStateForSend(ClaudeSession.Message userMessage, String normalizedInput) {
        state.addMessage(userMessage);
        callbackFacade.notifyMessageUpdate(state.getMessages());

        if (state.getSummary() == null) {
            String baseSummary = (userMessage.content != null && !userMessage.content.isEmpty())
                    ? userMessage.content
                    : normalizedInput;
            String newSummary = baseSummary.length() > 45 ? baseSummary.substring(0, 45) + "..." : baseSummary;
            state.setSummary(newSummary);
            callbackFacade.notifySummaryReceived(newSummary);
        }

        state.updateLastModifiedTime();
        state.setError(null);
        state.setBusy(true);
        state.setLoading(true);
        ClaudeNotifier.setWaiting(project);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    public CompletableFuture<Void> sendMessageToProvider(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String externalAgentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
    ) {
        String agentPrompt = externalAgentPrompt;
        if (agentPrompt == null) {
            agentPrompt = getAgentPrompt();
            LOG.info("[Agent] Using agent from global setting (fallback)");
        } else {
            LOG.info("[Agent] Using agent from message (per-tab selection)");
        }

        String currentProvider = state.getProvider();
        String sessionModeBeforeSend = state.getPermissionMode();
        String normalizedRequestedMode = normalizeRequestedPermissionMode(requestedPermissionMode);
        String effectivePermissionMode = resolveEffectivePermissionMode(
                currentProvider,
                normalizedRequestedMode,
                sessionModeBeforeSend
        );

        LOG.info(
                "[ModeSync][Backend] provider=" + currentProvider
                        + ", requested=" + (normalizedRequestedMode != null ? normalizedRequestedMode : "(none)")
                        + ", session=" + (sessionModeBeforeSend != null ? sessionModeBeforeSend : "(none)")
                        + ", effective=" + effectivePermissionMode
        );

        if ("codex".equals(currentProvider)) {
            return sendToCodex(
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode
            );
        }

        return sendToClaude(channelId, input, attachments, openedFilesJson, agentPrompt, effectivePermissionMode);
    }

    public static String normalizeRequestedPermissionMode(String mode) {
        if (mode == null) {
            return null;
        }
        String trimmed = mode.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SessionState.isValidPermissionMode(trimmed)) {
            return trimmed;
        }
        LOG.warn("[ModeSync][Backend] Invalid requested permissionMode ignored: " + mode);
        return null;
    }

    public static String resolveEffectivePermissionMode(String provider, String requestedMode, String sessionMode) {
        String resolvedMode = requestedMode;
        if (resolvedMode == null) {
            resolvedMode = normalizeRequestedPermissionMode(sessionMode);
        }
        if (resolvedMode == null) {
            resolvedMode = "default";
        }

        if ("codex".equals(provider) && "plan".equals(resolvedMode)) {
            return "default";
        }
        return resolvedMode;
    }

    public static String getCodexRuntimeAccessError(String accessMode) {
        if (CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED.equals(accessMode)
                || CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN.equals(accessMode)) {
            return null;
        }
        return ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized");
    }

    private CompletableFuture<Void> sendToCodex(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode
    ) {
        CodexMessageHandler handler = new CodexMessageHandler(state, callbackFacade.getCallbackHandler());
        String accessMode = CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE;
        try {
            accessMode = new CodemossSettingsService().getCodexRuntimeAccessMode();
        } catch (Exception e) {
            LOG.warn("[Codex] Failed to resolve runtime access mode: " + e.getMessage());
        }

        String accessError = getCodexRuntimeAccessError(accessMode);
        if (accessError != null) {
            handler.onError(accessError);
            return CompletableFuture.completedFuture(null);
        }

        String contextAppend = contextService.buildCodexContextAppend(openedFilesJson, fileTagPaths);
        String finalInput = (input != null ? input : "") + contextAppend;

        return codexSDKBridge.sendMessage(
                channelId,
                finalInput,
                state.getSessionId(),
                state.getCwd(),
                attachments,
                effectivePermissionMode,
                state.getModel(),
                agentPrompt,
                state.getReasoningEffort(),
                handler
        ).thenApply(result -> null);
    }

    private CompletableFuture<Void> sendToClaude(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            String effectivePermissionMode
    ) {
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                project,
                state,
                callbackFacade.getCallbackHandler(),
                messageParser,
                messageMerger,
                gson
        );

        Boolean streaming = readStreamingEnabled();
        final String runtimeSessionEpoch = state.getRuntimeSessionEpoch();
        final String currentModel = state.getModel();
        // Phase 6c (2026-05-24): consume the one-shot rotation handoff staged
        // by ClaudeSession.swapInnerSession. Take-and-clear semantics ensure
        // the handoff is delivered exactly once even if the send is retried
        // by an upper layer (retry would re-enter this method with append=null).
        String systemPromptAppend = state.consumePendingSystemPromptAppend();

        // Protocol v2 (2026-05-24): if this session is attached to a Pair, inject
        // a pair-context marker at the START of systemPromptAppend so the daemon
        // can extract it without us having to add a new field through 5 layers
        // of IPC. Daemon's buildRequestContext peels off the marker, sets
        // requestContext.pairContext, and passes the cleaned remainder as the
        // actual system prompt append. Marker format:
        //   <!--pair-context:{"pairId":"p1","activeDirectiveId":"d_..."}-->
        // followed by the original append. No marker = legacy non-Pair flow.
        systemPromptAppend = prependPairContextMarker(systemPromptAppend);

        LOG.info("[Lifecycle] sendToClaude sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", epoch=" + runtimeSessionEpoch
                + ", cwd=" + state.getCwd()
                + ", model=" + currentModel
                + ", appendBytes=" + (systemPromptAppend == null ? 0 : systemPromptAppend.length()));

        return claudeSDKBridge.sendMessage(
                        channelId,
                        input,
                        state.getSessionId(),
                        runtimeSessionEpoch,
                        state.getCwd(),
                        attachments,
                        effectivePermissionMode,
                        currentModel,
                        openedFilesJson,
                        agentPrompt,
                        streaming,
                        false,
                        state.getReasoningEffort(),
                        systemPromptAppend,
                        handler
                ).thenApply(result -> null);
    }

    /**
     * Protocol v2 (2026-05-24): If this session is attached to a Pair, prepend
     * a {@code <!--pair-context:{...}-->} marker to {@code systemPromptAppend}
     * so the daemon side can extract {@code pairContext} without us adding a
     * new field through every layer of IPC. The daemon's
     * {@code buildRequestContext} peels off the marker, sets
     * {@code requestContext.pairContext}, and uses the cleaned remainder as
     * the actual append. Idempotent — if no pair is attached, returns the
     * input unchanged. If a marker is already present (would only happen via
     * a buggy upper layer), returns input unchanged to avoid duplication.
     */
    private String prependPairContextMarker(String currentAppend) {
        try {
            if (project == null) return currentAppend;
            String sessionId = state.getSessionId();
            // Find attached pair: by main session id first, fall back to
            // most-recently-started active pair (covers the "pair started
            // before first response" window).
            com.github.claudecodegui.session.pair.PairSessionManager mgr =
                    com.github.claudecodegui.session.pair.PairSessionManager.getInstance(project);
            com.github.claudecodegui.session.pair.PairSession pair = null;
            if (sessionId != null && !sessionId.isEmpty()) {
                pair = mgr.findByMainSession(sessionId);
            }
            if (pair == null) {
                pair = mgr.getActivePairs().stream()
                        .filter(p -> !p.isDisposed())
                        .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                        .orElse(null);
            }
            if (pair == null) return currentAppend;
            String existing = currentAppend == null ? "" : currentAppend;
            if (existing.startsWith("<!--pair-context:")) return currentAppend;

            JsonObject pc = new JsonObject();
            pc.addProperty("pairId", pair.getPairId());
            // activeDirectiveId: most-recent inject_prompt the supervisor sent.
            // We don't have a clean read for this on PairSession today; null is
            // safe — daemon mcp__main tool emits TURN_REPORT with directiveId=null
            // when not set, and Java side falls back to "no directive correlation"
            // (turn still gets recorded, just not tied to a specific directive).
            // Future: track latest directiveId on PairSession.
            String marker = "<!--pair-context:" + gson.toJson(pc) + "-->\n";
            return marker + existing;
        } catch (Throwable ignored) {
            return currentAppend;
        }
    }

    private boolean readAutoOpenFileEnabled() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = new CodemossSettingsService();
                boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                LOG.info("[EditorContext] Auto open file enabled: " + autoOpenFileEnabled);
                return autoOpenFileEnabled;
            }
        } catch (Exception e) {
            LOG.warn("[EditorContext] Failed to read autoOpenFileEnabled setting: " + e.getMessage());
        }
        return false;
    }

    private Boolean readStreamingEnabled() {
        Boolean streaming = null;
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = new CodemossSettingsService();
                streaming = settingsService.getStreamingEnabled(projectPath);
                LOG.info("[Streaming] Read streaming config: " + streaming);
            }
        } catch (Exception e) {
            LOG.warn("[Streaming] Failed to read streaming config: " + e.getMessage());
        }
        return streaming;
    }

    private String getAgentPrompt() {
        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            String selectedAgentId = settingsService.getSelectedAgentId();
            LOG.info("[Agent] Checking selected agent ID: " + (selectedAgentId != null ? selectedAgentId : "null"));

            if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                JsonObject agent = settingsService.getAgent(selectedAgentId);
                if (agent != null && agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    String agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[Agent] ✓ Found agent: " + agentName);
                    LOG.info("[Agent] ✓ Prompt length: " + agentPrompt.length() + " chars");
                    LOG.info("[Agent] ✓ Prompt preview: "
                            + (agentPrompt.length() > 100 ? agentPrompt.substring(0, 100) + "..." : agentPrompt));
                    return agentPrompt;
                }
                LOG.info("[Agent] ✗ Agent found but no prompt configured");
            } else {
                LOG.info("[Agent] ✗ No agent selected");
            }
        } catch (Exception e) {
            LOG.warn("[Agent] ✗ Failed to get agent prompt: " + e.getMessage());
        }
        return null;
    }
}
