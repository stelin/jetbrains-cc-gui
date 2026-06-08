package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession.Message;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.pair.EventBus;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Claude message callback handler.
 * Processes various message types returned by Claude AI,
 * including thinking content, text responses, and tool call results.
 */
public class ClaudeMessageHandler implements MessageCallback {
    private static final Logger LOG = Logger.getInstance(ClaudeMessageHandler.class);

    private final Project project;
    private final SessionState state;
    private final CallbackHandler callbackHandler;
    private final MessageParser messageParser;
    private final MessageMerger messageMerger;
    private final Gson gson;

    // Content accumulator for the current assistant message
    private final StringBuilder assistantContent = new StringBuilder();

    // Current assistant message object being processed
    private Message currentAssistantMessage = null;

    // Whether the AI is currently in thinking mode
    private boolean isThinking = false;

    // Streaming state tracking — volatile because these fields are read/written across
    // message callback threads and EDT, with no other happens-before guarantee.
    private volatile boolean isStreaming = false;

    private volatile boolean streamEndedThisTurn = false;
    private volatile boolean errorReportedThisTurn = false;
    private volatile String lastReportedError = null;

    // Streaming segment state (used to split text/thinking around tool calls)
    private volatile boolean textSegmentActive = false;
    private volatile boolean thinkingSegmentActive = false;

    // Offset tracking for deduplication after conservative sync.
    // Volatile: same threading pattern as textSegmentActive/thinkingSegmentActive
    // (read/written across SDK callback threads and EDT).
    private volatile int syncedContentOffset = 0;
    private volatile int syncedThinkingOffset = 0;

    // ---- Supervisor Pair turn tracking ----
    // Tool uses and modified files seen since the most recent user message,
    // forwarded as a single turn_end summary when onComplete()/onError()/
    // handleStreamEnd() fires (whichever comes first).
    private final List<EventBus.ToolUseRecord> turnToolUses = new ArrayList<>();
    private final Set<String> turnModifiedFiles = new LinkedHashSet<>();
    private volatile long turnStartedAt = 0;
    private final java.util.concurrent.atomic.AtomicInteger turnRetryCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    // Re-entrance guard: stream_end can fire first, then onComplete arrives — we
    // don't want to emit turn_end to the Supervisor twice for one turn.
    private volatile boolean turnEndPublished = false;

    /**
     * Constructor.
     */
    public ClaudeMessageHandler(
            Project project,
            SessionState state,
            CallbackHandler callbackHandler,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson
    ) {
        this.project = project;
        this.state = state;
        this.callbackHandler = callbackHandler;
        this.messageParser = messageParser;
        this.messageMerger = messageMerger;
        this.gson = gson;
    }

    /**
     * Handle a received message by dispatching to the appropriate handler based on type.
     */
    @Override
    public void onMessage(String type, String content) {
        // Route to the appropriate handler based on message type
        switch (type) {
            case "user":
                handleUserMessage(content);
                break;
            case "assistant":
                handleAssistantMessage(content);
                break;
            case "thinking":
                handleThinkingMessage();
                break;
            case "content":
                // Non-streaming mode: complete content block, update message
                handleContent(content);
                break;
            case "content_delta":
                // Streaming: incremental content, forward to frontend
                handleContentDelta(content);
                break;
            // Streaming: thinking delta
            case "thinking_delta":
                handleThinkingDelta(content);
                break;
            // Streaming: start and end markers
            case "stream_start":
                handleStreamStart();
                break;
            case "stream_end":
                handleStreamEnd();
                break;
            case "session_id":
                handleSessionId(content);
                break;
            case "tool_result":
                handleToolResult(content);
                break;
            case "message_end":
                handleMessageEnd();
                break;
            case "result":
                handleResult(content);
                break;
            case "usage":
                handleUsage(content);
                break;
            case "reasoning_effort_applied":
                handleReasoningEffortApplied(content);
                break;
            case "claude_error_code":
                callbackHandler.notifyClaudeErrorCode(content);
                break;
            case "slash_commands":
                handleSlashCommands(content);
                break;
            case "system":
                handleSystemMessage(content);
                break;
            case "node_log":
                // Forward Node.js logs to frontend console
                callbackHandler.notifyNodeLog(content);
                break;
            // Protocol v2 (2026-05-24): main-AI Pair-mode events from
            // daemon's mcp__main MCP server + SubagentStop hook.
            case "turn_report":
                handleTurnReport(content);
                break;
            case "subagent_stop":
                handleSubagentStop(content);
                break;
        }
    }

    /**
     * Handle an error from the SDK.
     */
    @Override
    public void onError(String error) {
        if (errorReportedThisTurn && error != null && error.equals(lastReportedError)) {
            LOG.debug("Suppressing duplicate error for current Claude turn");
            return;
        }

        boolean wasStreaming = isStreaming;
        isStreaming = false;
        streamEndedThisTurn = false;
        errorReportedThisTurn = true;
        lastReportedError = error;
        textSegmentActive = false;
        thinkingSegmentActive = false;
        syncedContentOffset = 0;
        syncedThinkingOffset = 0;

        // Reset thinking state if still active — same as onComplete() and handleStreamEnd()
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
        }

        state.setError(error);
        state.setBusy(false);
        state.setLoading(false);

        Message errorMessage = new Message(Message.Type.ERROR, error);
        state.addMessage(errorMessage);
        callbackHandler.notifyMessageUpdate(state.getMessages());
        if (wasStreaming) {
            callbackHandler.notifyStreamEnd();
        }
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());

        // Show error in status bar
        ClaudeNotifier.showError(project, error);

        // Notify attached Supervisor Pair (if any).
        publishErrorIfPair(error);
        // Phase 6a: main-AI observability — accumulates errorCount, surfaces alert
        // at threshold. Does NOT short-circuit the existing error UI.
        notifyMainAIMonitorError(error);
    }

    /**
     * Handle completion of a response turn.
     */
    @Override
    public void onComplete(SDKResult result) {
        if (streamEndedThisTurn) {
            streamEndedThisTurn = false;
            errorReportedThisTurn = false;
            lastReportedError = null;
            // Safety net: ensure loading state is cleared even when stream_end
            // was received normally.  handleStreamEnd() already calls
            // notifyStateChange, but the async JCEF chain may drop it.
            // This redundant call is harmless (idempotent) and prevents the UI
            // from getting stuck in "responding" state.
            state.setBusy(false);
            state.setLoading(false);
            callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            // Notify attached Supervisor Pair (turn really ended; reset retry counter).
            turnRetryCount.set(0);
            // Phase 6a: main-AI monitor — same hook as handleStreamEnd, idempotent.
            notifyMainAIMonitorTurnEnd();
            // Contract State Machine v3 (2026-05-25 fix): plan SM transition.
            notifyPlanStateMachineTurnEnd();
            publishTurnEndIfPair();
            return;
        }

        // If streaming was active but [STREAM_END] was never received (e.g., SDK error,
        // timeout, or process interruption), we must explicitly end the stream here.
        // Without this, the StreamMessageCoalescer remains in streamActive=true state,
        // which causes SessionCallbackAdapter.onStateChange() to suppress showLoading(false),
        // leaving the UI stuck in "responding" state forever.
        // This mirrors the same pattern used in onError() above.
        boolean wasStreaming = isStreaming;
        isStreaming = false;
        textSegmentActive = false;
        thinkingSegmentActive = false;
        syncedContentOffset = 0;
        syncedThinkingOffset = 0;

        // Reset thinking state if still active
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
        }

        errorReportedThisTurn = false;
        lastReportedError = null;
        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();

        if (wasStreaming) {
            LOG.warn("onComplete called without prior stream_end — forcing stream cleanup");
            callbackHandler.notifyMessageUpdate(state.getMessages());
            callbackHandler.notifyStreamEnd();
        }

        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());

        // Notify attached Supervisor Pair (turn really ended; reset retry counter).
        turnRetryCount.set(0);
        // Phase 6a: main-AI monitor — same hook as handleStreamEnd, idempotent.
        notifyMainAIMonitorTurnEnd();
        publishTurnEndIfPair();
    }

    // ===== Private methods: handle different message types =====

    /**
     * Handle an assistant message in full JSON format.
     */
    private void handleAssistantMessage(String content) {
        if (!content.startsWith("{")) {
            return;
        }
        // Deadlock fix (2026-06-04): an assistant message is main-AI activity.
        noteMainAIActivity();

        try {
            // Parse the complete JSON message
            JsonObject messageJson = gson.fromJson(content, JsonObject.class);
            JsonObject previousRaw = currentAssistantMessage != null ? currentAssistantMessage.raw : null;
            JsonObject mergedRaw = messageMerger.mergeAssistantMessage(previousRaw, messageJson);

            if (currentAssistantMessage == null) {
                currentAssistantMessage = new Message(Message.Type.ASSISTANT, "", mergedRaw);
                state.addMessage(currentAssistantMessage);
            } else {
                currentAssistantMessage.raw = mergedRaw;
            }

            // Streaming mode: do not overwrite accumulated streaming content with the full message
            //   (tool call messages typically don't contain text)
            // Non-streaming mode: rebuild content from the full message text
            String aggregatedText = messageParser.extractMessageContent(mergedRaw);
            if (!isStreaming) {
                assistantContent.setLength(0);
                if (aggregatedText != null) {
                    assistantContent.append(aggregatedText);
                }
                currentAssistantMessage.content = assistantContent.toString();
            } else if (aggregatedText != null && aggregatedText.length() > assistantContent.length()) {
                // Conservative sync: if full text is longer, update accumulator (prevents delta loss edge cases)
                assistantContent.setLength(0);
                assistantContent.append(aggregatedText);
                currentAssistantMessage.content = assistantContent.toString();
                syncedContentOffset = assistantContent.length();
            }
            currentAssistantMessage.raw = mergedRaw;

            // Streaming: check if the message contains tool calls
            // If tool_use is present, we need to update messages even in streaming mode to render tool blocks
            boolean hasToolUse = false;
            if (mergedRaw.has("message") && mergedRaw.getAsJsonObject("message").has("content")) {
                var contentArray = mergedRaw.getAsJsonObject("message").get("content");
                if (contentArray.isJsonArray()) {
                    // Inbound path translation for tool_use input fields. The path
                    // mapper is project-scoped; in local mode (or unmapped remote
                    // mode) it is the identity mapper and short-circuits.
                    com.github.claudecodegui.path.PathMapper pathMapper =
                            (project != null
                                    ? com.github.claudecodegui.path.PathMapperHolder.getInstance(project).get()
                                    : com.github.claudecodegui.path.IdentityPathMapper.INSTANCE);

                    for (var element : contentArray.getAsJsonArray()) {
                        if (element.isJsonObject() && element.getAsJsonObject().has("type")) {
                            var blockObj = element.getAsJsonObject();
                            String type = blockObj.get("type").getAsString();
                            if ("tool_use".equals(type)) {
                                hasToolUse = true;
                                // Record for Supervisor turn_end summary (Pair only — no-op when no Pair attached).
                                recordTurnToolUse(blockObj);
                                if (pathMapper.isActive() && blockObj.has("input")
                                        && blockObj.get("input").isJsonObject()) {
                                    try {
                                        com.github.claudecodegui.path.PathFieldVisitor.applyInbound(
                                                "__tool_use_input__",
                                                blockObj.getAsJsonObject("input"),
                                                pathMapper::toLocal
                                        );
                                    } catch (Exception ex) {
                                        LOG.debug("tool_use input translation failed: " + ex.getMessage());
                                    }
                                }
                                // A-phase: collect paths and schedule a project VFS reload.
                                // Bash falls back to project base path (its cwd isn't in input).
                                if (project != null) {
                                    try {
                                        scheduleProjectReloadFromToolUse(blockObj);
                                    } catch (Exception ex) {
                                        LOG.debug("schedule project reload failed: " + ex.getMessage());
                                    }
                                }
                                // Don't break — translate every tool_use block in this message.
                            }
                        }
                    }
                }
            }

            // Tool calls act as segment boundaries: subsequent text/thinking should go into new blocks
            if (hasToolUse) {
                textSegmentActive = false;
                thinkingSegmentActive = false;
            }

            // Streaming: skip full message update in streaming mode unless there is a tool call
            if (!isStreaming || hasToolUse) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
                if (hasToolUse) {
                    LOG.debug("Streaming active but tool_use detected, sending message update");
                }
            } else {
                LOG.debug("Streaming active, skipping full message update in handleAssistantMessage");
            }

            // Update status bar with usage from the final assistant message (matches CLI's PP1 behavior).
            // This ensures the displayed value matches what resume shows from JSONL history.
            // The assistant message's usage field is the authoritative final value.
            //
            // IMPORTANT: This update MUST happen in BOTH streaming and non-streaming modes:
            // - In streaming mode: [USAGE] tags provide intermediate updates for real-time feedback,
            //   but the assistant message's usage is the authoritative final value that must overwrite
            //   any intermediate values to ensure consistency with JSONL history and CLI behavior.
            // - In non-streaming mode: This is the primary path to update token usage.
            //
            // DO NOT add !isStreaming check here - it was previously introduced in commit 03640408
            // and caused incorrect token display in streaming mode (see commit history for details).
            if (mergedRaw.has("message") && mergedRaw.get("message").isJsonObject()) {
                JsonObject messageObj = mergedRaw.getAsJsonObject("message");
                if (messageObj.has("usage") && messageObj.get("usage").isJsonObject()) {
                    JsonObject usage = messageObj.getAsJsonObject("usage");
                    int usedTokens = TokenUsageUtils.extractUsedTokens(usage, state.getProvider());
                    int maxTokens = SettingsHandler.getModelContextLimit(state.getModel());
                    ClaudeNotifier.setTokenUsage(project, usedTokens, maxTokens);
                    callbackHandler.notifyUsageUpdate(usedTokens, maxTokens, TokenUsageUtils.extractOutputTokens(usage));
                    LOG.debug("Updated token usage from assistant message: " + usedTokens);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse assistant message JSON: " + e.getMessage());
        }
    }

    /**
     * Handle the thinking message indicating AI is reasoning.
     */
    private void handleThinkingMessage() {
        if (!isThinking) {
            isThinking = true;
            callbackHandler.notifyThinkingStatusChanged(true);
            // Update StatusBar to show thinking status
            ClaudeNotifier.setThinking(project);
            LOG.debug("Thinking started");
        }
    }

    /**
     * Handle complete content in non-streaming mode.
     */
    private void handleContent(String content) {
        // If previously thinking, content output means thinking is complete
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
            ClaudeNotifier.setGenerating(project);
            LOG.debug("Thinking completed, generating response");
        }

        assistantContent.append(content);

        if (currentAssistantMessage == null) {
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, assistantContent.toString());
            state.addMessage(currentAssistantMessage);
        } else {
            currentAssistantMessage.content = assistantContent.toString();
        }

        // Streaming: skip full message update in streaming mode
        if (!isStreaming) {
            callbackHandler.notifyMessageUpdate(state.getMessages());
        } else {
            LOG.debug("Streaming active, skipping full message update in handleContent");
        }
    }

    /**
     * Handle incremental content delta in streaming mode.
     */
    private void handleContentDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        // Deadlock fix (2026-06-04): streaming text is main-AI activity.
        noteMainAIActivity();
        // If previously thinking, content output means thinking is complete
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
            // Update StatusBar to show generating status
            ClaudeNotifier.setGenerating(project);
            LOG.debug("Thinking completed, generating response");
        }

        // Content output means the current thinking segment has ended
        thinkingSegmentActive = false;

        // Dedup: skip if delta was already included via conservative sync.
        // Heuristic: checks if assistantContent ends with the delta. This may produce
        // false positives for very short deltas (1-2 chars) that coincidentally match
        // the suffix, but the SDK sends deltas in token-level chunks (typically whole
        // words) making this extremely rare in practice.
        // CRITICAL: Do NOT notify frontend when dedup triggers - frontend has no dedup
        // and will accumulate the delta, causing content duplication.
        if (syncedContentOffset > 0
                && assistantContent.length() >= content.length()
                && assistantContent.substring(assistantContent.length() - content.length()).equals(content)) {
            LOG.debug("Skipping duplicate content delta (len=" + content.length() + ")");
            if (!isStreaming) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
            }
            return;
        }

        // Accumulate content for the final message
        assistantContent.append(content);

        ensureCurrentAssistantMessageExists();
        currentAssistantMessage.content = assistantContent.toString();
        applyTextDeltaToRaw(content);
        syncedContentOffset = assistantContent.length();
        textSegmentActive = true;

        callbackHandler.notifyContentDelta(content);
        if (!isStreaming) {
            callbackHandler.notifyMessageUpdate(state.getMessages());
        }
    }

    /**
     * Handle session ID received from the SDK.
     */
    private void handleSessionId(String content) {
        state.setSessionId(content);
        callbackHandler.notifySessionIdReceived(content);
        LOG.info("Captured session ID: " + content);
        // Phase 6b (2026-05-24): a pair started before the first turn was
        // wired with mainSessionId=null; now that the SDK has assigned one,
        // late-bind it on the manager so MainAIMonitor's L2 records carry
        // the right sid and findByMainSession works for subsequent lookups.
        // Best-effort — never break the main turn flow.
        lateBindSessionIdToPair(content);
    }

    /**
     * Phase 6b helper. Finds the attached pair (via the fallback "most recent
     * active" path, since findByMainSession would still miss — the index isn't
     * populated yet) and asks PairSessionManager to wire the sid.
     */
    private void lateBindSessionIdToPair(String newSessionId) {
        if (project == null || newSessionId == null || newSessionId.isEmpty()) return;
        try {
            PairSession pair = findAttachedPair();
            if (pair == null) return;
            String boundSid = pair.getMainSessionId();
            if (newSessionId.equals(boundSid)) return; // already bound (started with sid)
            PairSessionManager.getInstance(project)
                    .bindMainSessionIdToPair(pair.getPairId(), newSessionId);
            // Session resume (SR7): persist the node's main-AI session id so a
            // restart can resume its transcript. No-op for non-workflow pairs.
            com.github.claudecodegui.session.pair.workflow.SupervisorWorkflowManager
                    .getInstance(project)
                    .onMainSessionCaptured(pair.getPairId(), newSessionId);
        } catch (Throwable ignored) { /* never propagate */ }
    }

    /**
     * Handle user message from SDK.
     * SDK-returned user messages contain a uuid that needs to be applied to existing user messages.
     * Messages containing tool_result need to be added to the message list.
     */
    private void handleUserMessage(String content) {
        if (!content.startsWith("{")) {
            return;
        }

        try {
            JsonObject userMsg = gson.fromJson(content, JsonObject.class);

            // Check if the message contains a tool_result
            if (messageParser.hasToolResult(userMsg)) {
                // This is a user message with tool_result; add it to the message list
                Message toolResultMessage = new Message(Message.Type.USER, "[tool_result]", userMsg);
                state.addMessage(toolResultMessage);
                LOG.debug("Added tool_result user message to state");
                callbackHandler.notifyMessageUpdate(state.getMessages());

                // B-phase: walk tool_result blocks and replay refresh per tool_use_id.
                if (project != null) {
                    try {
                        replayReloadForToolResults(userMsg);
                    } catch (Exception ex) {
                        LOG.debug("replay reload from user-message tool_result failed: " + ex.getMessage());
                    }
                }
                return;
            }

            // Extract uuid (used for rewind functionality)
            String uuid = userMsg.has("uuid") ? userMsg.get("uuid").getAsString() : null;
            if (uuid == null) {
                LOG.debug("User message from SDK has no uuid, skipping update");
                return;
            }

            String userText = messageParser.extractMessageContent(userMsg);
            if (userText == null || userText.isEmpty()) {
                LOG.debug("User message from SDK has no text content, skipping uuid patch");
                return;
            }

            // 2026-05-24: Phase 6b — push the verbatim user text into the main-AI
            // monitor's bounded ring on L2 so an eventual fallback handoff doc
            // has the last few user messages available even when the producer
            // prompt fails to emit them.
            notifyMainAIMonitorUserMessage(userText);

            // Find the latest unresolved matching user message and patch its uuid.
            List<Message> messages = state.getMessagesReference();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg.type != Message.Type.USER) {
                    continue;
                }
                if (!userText.equals(msg.content)) {
                    continue;
                }
                if (msg.raw == null) {
                    msg.raw = new JsonObject();
                }
                if (msg.raw.has("uuid") && !msg.raw.get("uuid").isJsonNull()) {
                    continue;
                }
                msg.raw.addProperty("uuid", uuid);
                LOG.info("Updated user message with uuid: " + uuid);
                callbackHandler.notifyUserMessageUuidPatched(msg.content != null ? msg.content : "", uuid);
                break;
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse user message from SDK: " + e.getMessage());
        }
    }

    /**
     * Handle a tool call result.
     */
    private void handleToolResult(String content) {
        if (!content.startsWith("{")) {
            return;
        }
        // Deadlock fix (2026-06-04): a tool_result means the main AI got data
        // back and is resuming — it's activity, and it clears any awaiting-user
        // latch (e.g. an AskUserQuestion answer just came back).
        clearMainAIAwaitingUser();

        try {
            JsonObject toolResultBlock = gson.fromJson(content, JsonObject.class);
            String toolUseId = toolResultBlock.has("tool_use_id")
                    ? toolResultBlock.get("tool_use_id").getAsString()
                    : null;

            if (toolUseId != null) {
                // Build a user message containing the tool_result
                JsonArray contentArray = new JsonArray();
                contentArray.add(toolResultBlock);

                JsonObject messageObj = new JsonObject();
                messageObj.add("content", contentArray);

                JsonObject rawUser = new JsonObject();
                rawUser.addProperty("type", "user");
                rawUser.add("message", messageObj);

                // Create the user message and add it to the message list
                Message toolResultMessage = new Message(Message.Type.USER, "[tool_result]", rawUser);
                state.addMessage(toolResultMessage);

                LOG.debug("Tool result received for tool_use_id: " + toolUseId);
                callbackHandler.notifyMessageUpdate(state.getMessages());

                // B-phase: replay refresh for paths captured in A-phase.
                if (project != null) {
                    try {
                        com.github.claudecodegui.service.ProjectReloadService
                                .getInstance(project)
                                .schedulePathsForToolUseId(toolUseId);
                    } catch (Exception ex) {
                        LOG.debug("schedule project reload (B) failed: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse tool_result JSON: " + e.getMessage());
        }
    }

    /**
     * Walk a SDK user message and replay reload for every contained
     * tool_result's {@code tool_use_id}. Used when tool results arrive
     * embedded in a user message rather than as standalone events.
     */
    private void replayReloadForToolResults(JsonObject userMsg) {
        if (userMsg == null || !userMsg.has("message")) return;
        var msg = userMsg.get("message");
        if (!msg.isJsonObject()) return;
        var messageObj = msg.getAsJsonObject();
        if (!messageObj.has("content")) return;
        var contentEl = messageObj.get("content");
        if (!contentEl.isJsonArray()) return;

        var reloadService = com.github.claudecodegui.service.ProjectReloadService.getInstance(project);
        for (var el : contentEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            if (!block.has("type")) continue;
            if (!"tool_result".equals(block.get("type").getAsString())) continue;
            if (!block.has("tool_use_id") || block.get("tool_use_id").isJsonNull()) continue;
            String id = block.get("tool_use_id").getAsString();
            if (!id.isEmpty()) reloadService.schedulePathsForToolUseId(id);
        }
    }

    /**
     * A-phase: extract paths from a single tool_use block and schedule a
     * project VFS reload. The tool_use_id → paths mapping is also recorded so
     * the B-phase (tool_result) can re-fire a refresh once the write actually
     * lands.
     *
     * <p>Bash is treated specially because its {@code input.command} is
     * intentionally not parsed for paths — we schedule the project base path
     * (effectively "refresh whatever Bash touched in cwd") instead.
     */
    private void scheduleProjectReloadFromToolUse(JsonObject blockObj) {
        String toolName = blockObj.has("name") && !blockObj.get("name").isJsonNull()
                ? blockObj.get("name").getAsString()
                : "";
        String toolUseId = blockObj.has("id") && !blockObj.get("id").isJsonNull()
                ? blockObj.get("id").getAsString()
                : null;

        java.util.Set<String> paths = new java.util.HashSet<>();
        if ("Bash".equals(toolName)) {
            String base = project.getBasePath();
            if (base != null && !base.isEmpty()) paths.add(base);
        } else if (blockObj.has("input") && blockObj.get("input").isJsonObject()) {
            com.github.claudecodegui.path.PathFieldVisitor.collectInbound(
                    "__tool_use_input__",
                    blockObj.getAsJsonObject("input"),
                    p -> { if (p != null && !p.isEmpty()) paths.add(p); }
            );
        }

        if (paths.isEmpty()) return;

        var reloadService = com.github.claudecodegui.service.ProjectReloadService.getInstance(project);
        if (toolUseId != null && !toolUseId.isEmpty()) {
            reloadService.recordToolUse(toolUseId, paths);
        }
        reloadService.schedulePaths(paths);
    }

    /**
     * Handle the end of a message.
     */
    private void handleMessageEnd() {
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
        }
        ClaudeNotifier.clearStatus(project);

        // FIX: handleMessageEnd should not reset loading/busy state.
        // Regardless of streaming or non-streaming mode, state reset should be handled uniformly by:
        // - Streaming mode: onStreamEnd
        // - Non-streaming mode: onComplete
        // This prevents state from being unexpectedly reset during message processing.
        LOG.debug("message_end received, deferring state cleanup to onComplete/onStreamEnd");
    }

    /**
     * Handle the result message as a fallback for non-streaming mode.
     * In streaming mode, usage is updated via handleUsage() from [USAGE] tags.
     * In non-streaming mode, [USAGE] tags may not be emitted, so result.usage
     * serves as the fallback data source to ensure token usage is displayed.
     */
    private void handleResult(String content) {
        if (content == null || !content.startsWith("{")) {
            LOG.debug("Result message received (non-JSON, skipping)");
            return;
        }
        try {
            JsonObject resultJson = gson.fromJson(content, JsonObject.class);
            LOG.debug("Result message received");
            // Fallback: only update usage from result if no usage was received via [USAGE] tag or assistant message
            if (resultJson.has("usage") && resultJson.get("usage").isJsonObject()
                    && currentAssistantMessage != null && currentAssistantMessage.raw != null) {
                JsonObject msg = currentAssistantMessage.raw.has("message")
                        && currentAssistantMessage.raw.get("message").isJsonObject()
                        ? currentAssistantMessage.raw.getAsJsonObject("message") : null;
                boolean hasExistingUsage = msg != null && msg.has("usage") && msg.get("usage").isJsonObject();
                if (!hasExistingUsage) {
                    JsonObject usageJson = resultJson.getAsJsonObject("usage");
                    if (msg != null) {
                        msg.add("usage", usageJson);
                    }
                    int usedTokens = TokenUsageUtils.extractUsedTokens(usageJson, state.getProvider());
                    int maxTokens = SettingsHandler.getModelContextLimit(state.getModel());
                    ClaudeNotifier.setTokenUsage(project, usedTokens, maxTokens);
                    callbackHandler.notifyUsageUpdate(usedTokens, maxTokens, TokenUsageUtils.extractOutputTokens(usageJson));
                    LOG.debug("Fallback: updated token usage from result message: " + usedTokens);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse result message: " + e.getMessage());
        }
    }

    /**
     * Handle the list of available slash commands.
     */
    private void handleSlashCommands(String content) {
        try {
            JsonArray commandsArray = gson.fromJson(content, JsonArray.class);
            List<String> commands = new ArrayList<>();
            for (int i = 0; i < commandsArray.size(); i++) {
                commands.add(commandsArray.get(i).getAsString());
            }
            state.setSlashCommands(commands);
            LOG.debug("Received " + commands.size() + " slash commands");
            callbackHandler.notifySlashCommandsReceived(commands);
        } catch (Exception e) {
            LOG.warn("Failed to parse slash commands: " + e.getMessage());
        }
    }

    /**
     * Handle a system-level message (not from AI, but from the system).
     */
    private void handleSystemMessage(String content) {
        LOG.debug("System message: " + content);

        // Parse slash_commands field from the system message
        try {
            JsonObject systemObj = gson.fromJson(content, JsonObject.class);
            if (systemObj.has("slash_commands") && systemObj.get("slash_commands").isJsonArray()) {
                JsonArray commandsArray = systemObj.getAsJsonArray("slash_commands");
                List<String> commands = new ArrayList<>();
                for (int i = 0; i < commandsArray.size(); i++) {
                    commands.add(commandsArray.get(i).getAsString());
                }
                state.setSlashCommands(commands);
                LOG.debug("Extracted " + commands.size() + " slash commands from system message");
                callbackHandler.notifySlashCommandsReceived(commands);
            }
            // Phase 6b (2026-05-24): detect main-AI SDK auto-compactions so the
            // per-pair MainAIMonitor's compactCount in L2 stays in sync. The
            // daemon also tag-prints a [COMPACT_BOUNDARY] line for telemetry,
            // but that goes to the command callback (not the message stream).
            // The system message is the authoritative signal on the SDK-stream side.
            if (systemObj.has("subtype") && !systemObj.get("subtype").isJsonNull()
                    && "compact_boundary".equals(systemObj.get("subtype").getAsString())) {
                notifyMainAIMonitorCompactBoundary();
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract slash commands from system message: " + e.getMessage());
        }
    }

    // ===== Streaming message handlers =====

    /**
     * Handle stream start event. Notifies the frontend to prepare for incremental content.
     */
    private void handleStreamStart() {
        LOG.debug("Stream started");
        isStreaming = true;  // Mark streaming as active
        streamEndedThisTurn = false;
        errorReportedThisTurn = false;
        lastReportedError = null;
        textSegmentActive = false;
        thinkingSegmentActive = false;
        syncedContentOffset = 0;
        syncedThinkingOffset = 0;
        // Protocol v2 (2026-05-24): reset the per-turn token delta baseline so
        // mid-turn cumulative [USAGE] tags translate to correct deltas next turn.
        lastReportedUsedTokens = 0;
        // Supervisor Pair: stream_start is the earliest authoritative "new turn"
        // marker, even when the main AI produces no tool_use this round.
        // Re-arming here makes sure stream_end can publish even for tool-less turns.
        turnEndPublished = false;
        if (turnStartedAt == 0) turnStartedAt = System.currentTimeMillis();
        // Phase 6a (2026-05-24): main-AI observability monitor hook.
        notifyMainAIMonitorTurnStart();
        // Contract State Machine v3 (2026-05-25 fix): plan SM transition into
        // EXECUTING so TransitionDispatcher / DeadlockGuard see correct state.
        notifyPlanStateMachineTurnStart();
        callbackHandler.notifyStreamStart();
    }

    /**
     * Handle stream end event. Notifies the frontend that the message is complete.
     */
    private void handleStreamEnd() {
        LOG.debug("Stream ended");
        isStreaming = false;  // Mark streaming as inactive
        streamEndedThisTurn = true;
        textSegmentActive = false;
        thinkingSegmentActive = false;
        syncedContentOffset = 0;
        syncedThinkingOffset = 0;

        // Reset thinking state — stream end is the definitive boundary for a turn.
        // If thinking was active when the stream ended (e.g., extended thinking without
        // subsequent content), it must be cleared here to prevent the frontend from being
        // stuck in "thinking" state.
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
        }

        // Ensure raw blocks are consistent with the accumulated content before sending the final update.
        // Conservative sync may leave raw text/thinking blocks shorter than assistantContent
        // if deltas arrived after the sync but before stream end.
        ensureRawBlocksConsistency();

        // After streaming ends, send a final message update to ensure the message list is in sync
        callbackHandler.notifyMessageUpdate(state.getMessages());
        callbackHandler.notifyStreamEnd();
        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());

        // Notify attached Supervisor Pair. Stream end is the authoritative turn
        // boundary in streaming mode — earlier than (or instead of) onComplete.
        // publishTurnEndIfPair is idempotent so a subsequent onComplete is safe.
        turnRetryCount.set(0);
        // Phase 6a (2026-05-24): main-AI observability monitor hook — fire BEFORE
        // publishTurnEndIfPair so the monitor's L2 update lands ahead of the
        // supervisor's event-bus dispatch (independent paths, but log order matters).
        notifyMainAIMonitorTurnEnd();
        // Contract State Machine v3 (2026-05-25 fix): plan SM transition out of
        // EXECUTING. When no MAIN_AI contracts are still open, plan moves to
        // PENDING_DECISION → TransitionDispatcher wakes supervisor automatically.
        // This is what was missing and caused the "supervisor 派单后主 AI 没收到
        // Step 2" stall the user reported.
        notifyPlanStateMachineTurnEnd();
        publishTurnEndIfPair();
    }

    /**
     * Handle an incremental thinking delta. Forwards it to the frontend for real-time display.
     */
    private void handleThinkingDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        // Ensure thinking state is enabled
        if (!isThinking) {
            isThinking = true;
            callbackHandler.notifyThinkingStatusChanged(true);
        }
        // Write thinking delta to raw to prevent data loss after stream ends
        ensureCurrentAssistantMessageExists();
        boolean applied = applyThinkingDeltaToRaw(content);
        if (applied) {
            // Note: uses += (not absolute assignment like syncedContentOffset)
            // because there is no thinkingContent StringBuilder to take length from
            syncedThinkingOffset += content.length();
            thinkingSegmentActive = true;
            // CRITICAL: Only notify frontend when delta was actually applied.
            // Frontend has no dedup and will accumulate, causing duplication.
            callbackHandler.notifyThinkingDelta(content);
            if (!isStreaming) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
            }
        } else {
            LOG.debug("Skipping duplicate thinking delta (len=" + content.length() + ")");
        }
    }

    private void ensureCurrentAssistantMessageExists() {
        if (currentAssistantMessage == null) {
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "assistant");
            JsonObject messageObj = new JsonObject();
            messageObj.add("content", new JsonArray());
            raw.add("message", messageObj);
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, "", raw);
            state.addMessage(currentAssistantMessage);
        }
        if (currentAssistantMessage.raw == null) {
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "assistant");
            JsonObject messageObj = new JsonObject();
            messageObj.add("content", new JsonArray());
            raw.add("message", messageObj);
            currentAssistantMessage.raw = raw;
        }
    }

    private JsonArray ensureAssistantContentArray() {
        ensureCurrentAssistantMessageExists();
        JsonObject raw = currentAssistantMessage.raw;
        JsonObject message = raw.has("message") && raw.get("message").isJsonObject()
                ? raw.getAsJsonObject("message")
                : new JsonObject();
        JsonArray content = message.has("content") && message.get("content").isJsonArray()
                ? message.getAsJsonArray("content")
                : new JsonArray();
        message.add("content", content);
        raw.add("message", message);
        currentAssistantMessage.raw = raw;
        return content;
    }

    private boolean applyTextDeltaToRaw(String delta) {
        if (delta == null || delta.isEmpty()) {
            return false;
        }
        JsonArray contentArray = ensureAssistantContentArray();
        JsonObject target = null;

        if (textSegmentActive) {
            for (int i = contentArray.size() - 1; i >= 0; i--) {
                if (!contentArray.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject block = contentArray.get(i).getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString())) {
                    target = block;
                    break;
                }
            }
        }

        if (target == null) {
            target = new JsonObject();
            target.addProperty("type", "text");
            target.addProperty("text", "");
            contentArray.add(target);
        }

        String existing = target.has("text") && !target.get("text").isJsonNull()
                ? target.get("text").getAsString()
                : "";

        // Dedup: skip if delta was already included after the last conservative sync
        if (syncedContentOffset > 0 && existing.endsWith(delta)) {
            return false;
        }

        target.addProperty("text", existing + delta);
        return true;
    }

    /**
     * Ensure raw text blocks are consistent with the accumulated assistantContent.
     * Conservative sync may leave the last raw text block shorter than the actual
     * streamed content when deltas arrive after the sync. This safety net runs
     * before the final notifyMessageUpdate to guarantee the frontend receives complete data.
     *
     * <p>Since assistantContent accumulates ALL text deltas (concatenation of all text blocks),
     * we calculate the total length of preceding text blocks and use only the tail portion
     * of assistantContent to fix the last block. This prevents incorrectly overwriting
     * the last block with the full concatenated content when multiple text blocks exist.</p>
     *
     * <p>Note: Only text blocks are fixed here because assistantContent is the
     * authoritative accumulator for text. Thinking content has no separate
     * accumulator — it is written directly to raw blocks — so there is no
     * external source of truth to compare against.</p>
     */
    private void ensureRawBlocksConsistency() {
        if (this.currentAssistantMessage == null || this.currentAssistantMessage.raw == null) {
            return;
        }
        JsonObject raw = this.currentAssistantMessage.raw;
        JsonObject message = raw.has("message") && raw.get("message").isJsonObject()
                ? raw.getAsJsonObject("message") : null;
        if (message == null || !message.has("content") || !message.get("content").isJsonArray()) {
            return;
        }
        JsonArray contentArray = message.getAsJsonArray("content");

        String accumulatedText = this.assistantContent.toString();
        if (accumulatedText.isEmpty()) {
            return;
        }

        // Find the last text block and calculate total text length from all preceding text blocks.
        // We need this because assistantContent is the concatenation of ALL text deltas,
        // but each text block should only contain its respective portion.
        JsonObject lastTextBlock = null;
        int precedingTextLength = 0;
        for (int i = 0; i < contentArray.size(); i++) {
            if (!contentArray.get(i).isJsonObject()) {
                continue;
            }
            JsonObject block = contentArray.get(i).getAsJsonObject();
            String blockType = block.has("type") && !block.get("type").isJsonNull()
                    ? block.get("type").getAsString() : "";
            if ("text".equals(blockType)) {
                lastTextBlock = block;
                precedingTextLength += block.has("text") && !block.get("text").isJsonNull()
                        ? block.get("text").getAsString().length() : 0;
            }
        }

        // The last iteration added the last block's length to precedingTextLength,
        // so subtract it to get the actual preceding length.
        if (lastTextBlock != null) {
            String lastBlockText = lastTextBlock.has("text") && !lastTextBlock.get("text").isJsonNull()
                    ? lastTextBlock.get("text").getAsString() : "";
            precedingTextLength -= lastBlockText.length();

            // Invariant: assistantContent must cover all preceding text blocks.
            // A violation indicates raw blocks and the accumulator drifted, which is
            // worth surfacing for diagnosis rather than silently producing an empty tail.
            if (accumulatedText.length() < precedingTextLength) {
                LOG.warn("ensureRawBlocksConsistency: accumulatedText (" + accumulatedText.length()
                        + ") shorter than precedingTextLength (" + precedingTextLength
                        + "); raw blocks may be out of sync with assistantContent");
                return;
            }

            // The expected content for the last block is the tail of assistantContent
            // starting from the end of all preceding text blocks.
            String expectedLastBlockText = accumulatedText.substring(precedingTextLength);
            if (lastBlockText.length() < expectedLastBlockText.length()) {
                lastTextBlock.addProperty("text", expectedLastBlockText);
            }
        }
    }

    /**
     * Handle usage data from the [USAGE] tag emitted by ai-bridge during streaming.
     */
    private void handleUsage(String content) {
        if (content == null || content.isEmpty() || !content.startsWith("{")) return;
        try {
            JsonObject usageJson = gson.fromJson(content, JsonObject.class);
            int usedTokens = TokenUsageUtils.extractUsedTokens(usageJson, state.getProvider());
            int maxTokens = SettingsHandler.getModelContextLimit(state.getModel());
            ClaudeNotifier.setTokenUsage(project, usedTokens, maxTokens);
            // Notify webview of usage update (outputTokens drives the live "↓ N tokens" counter)
            callbackHandler.notifyUsageUpdate(usedTokens, maxTokens, TokenUsageUtils.extractOutputTokens(usageJson));
            // Ensure assistant message exists before backfilling usage
            ensureCurrentAssistantMessageExists();
            backfillUsageToAssistantMessage(usageJson);
            // Protocol v2 (2026-05-24): feed the (delta of) used tokens to the
            // attached pair's budget tracker. usedTokens here is the cumulative
            // count for the current turn — we compute the delta from the last
            // reported value to avoid double-counting across mid-stream updates.
            notifyBudgetTrackerTokens(usedTokens);
            LOG.debug("Updated token usage from [USAGE] tag: " + usedTokens);
        } catch (Exception e) {
            LOG.warn("Failed to parse usage data: " + e.getMessage());
        }
    }

    /**
     * Protocol v2 (2026-05-24): push the token-usage delta to the attached
     * pair's budget tracker. {@code usedTokens} is a cumulative value emitted
     * mid-stream and at turn end; we subtract the last seen value to avoid
     * double-counting. Resets to 0 on a new turn (stream_start clears
     * {@link #lastReportedUsedTokens}).
     */
    private volatile int lastReportedUsedTokens = 0;
    private void notifyBudgetTrackerTokens(int usedTokens) {
        try {
            PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.PairBudgetTracker bt = pair.getBudgetTracker();
            if (bt == null) return;
            int delta = usedTokens - lastReportedUsedTokens;
            if (delta > 0) bt.addTokens(delta);
            lastReportedUsedTokens = usedTokens;
        } catch (Throwable ignored) { /* never break the main flow */ }
    }

    /**
     * Handle the daemon's confirmation that an effort tier was applied to the SDK
     * (parsed from "[REASONING_EFFORT] ✓ ... applied options.effort=xxx" lines).
     * This is the authoritative value — the WaitingIndicator displays it in place of
     * whatever the user-facing selector currently shows.
     */
    private void handleReasoningEffortApplied(String effort) {
        if (effort == null || effort.isEmpty()) return;
        callbackHandler.notifyReasoningEffortApplied(effort);
        LOG.debug("Applied reasoning effort: " + effort);
    }

    /**
     * Backfill usage data into the current assistant message's raw JSON.
     * Always updates during streaming to capture accumulating usage data.
     *
     * IMPORTANT: This method does NOT perform monotonic increase checks.
     * - The assistant message's final usage value (from handleAssistantMessage) is the authoritative
     *   value that will overwrite any intermediate values from [USAGE] tags.
     * - Monotonic checks were previously added in commit 03640408 but removed because they prevented
     *   the authoritative final value from being applied when messages arrive out of order.
     * - Allowing overwrites ensures consistency with JSONL history and CLI behavior.
     */
    private void backfillUsageToAssistantMessage(JsonObject usageJson) {
        if (currentAssistantMessage == null || currentAssistantMessage.raw == null) return;
        JsonObject message = currentAssistantMessage.raw.has("message") && currentAssistantMessage.raw.get("message").isJsonObject()
                ? currentAssistantMessage.raw.getAsJsonObject("message") : null;
        if (message == null) return;

        // Always update usage during streaming to capture accumulating values
        message.add("usage", usageJson);
        LOG.debug("Updated assistant message usage from [USAGE] tag");
    }

    private boolean applyThinkingDeltaToRaw(String delta) {
        if (delta == null || delta.isEmpty()) {
            return false;
        }
        JsonArray contentArray = ensureAssistantContentArray();
        JsonObject target = null;

        if (thinkingSegmentActive) {
            for (int i = contentArray.size() - 1; i >= 0; i--) {
                if (!contentArray.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject block = contentArray.get(i).getAsJsonObject();
                if (block.has("type") && "thinking".equals(block.get("type").getAsString())) {
                    target = block;
                    break;
                }
            }
        }

        if (target == null) {
            target = new JsonObject();
            target.addProperty("type", "thinking");
            target.addProperty("thinking", "");
            contentArray.add(target);
        }

        String existing = target.has("thinking") && !target.get("thinking").isJsonNull()
                ? target.get("thinking").getAsString()
                : "";

        // Dedup: skip if delta was already included after the last conservative sync
        if (syncedThinkingOffset > 0 && existing.endsWith(delta)) {
            return false;
        }

        target.addProperty("thinking", existing + delta);
        return true;
    }

    // ============================================================================
    // Supervisor Pair integration
    // ============================================================================

    /**
     * Find the {@link PairSession} (if any) attached to the current main session.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Exact match on {@code state.sessionId} (set after SDK assigns one).</li>
     *   <li>Fallback to the most-recently-started active pair <em>owned by
     *       this tab</em>. The SDK-assigned sessionId only arrives on the
     *       first response, so a pair started <em>before</em> the first turn
     *       would otherwise miss its early events. The owner-window filter
     *       keeps the fallback from picking up a pair created by a different
     *       tab — see the 2026-05-25 cross-tab event leakage fix where
     *       Tab A's supervisor was hearing Tab B's main-AI events.</li>
     * </ol>
     *
     * <p>If no tab windowId is available (legacy / test contexts that built
     * the session without one), the fallback returns {@code null} rather than
     * scanning project-wide — better to miss the first event than to route it
     * to the wrong tab.
     */
    private PairSession findAttachedPair() {
        if (project == null) return null;
        try {
            PairSessionManager mgr = PairSessionManager.getInstance(project);
            String sid = state != null ? state.getSessionId() : null;
            if (sid != null && !sid.isEmpty()) {
                PairSession exact = mgr.findByMainSession(sid);
                if (exact != null) return exact;
            }
            // Window-scoped fallback: only consider pairs owned by THIS tab.
            // Without a windowId we deliberately give up rather than guess —
            // a missed first-turn event is better than a cross-tab miss-route.
            String windowId = state != null ? state.getWindowId() : null;
            if (windowId == null) return null;
            return mgr.getActivePairsOwnedBy(windowId).stream()
                    .filter(p -> !p.isDisposed())
                    .reduce((a, b) -> a.getStartedAt() > b.getStartedAt() ? a : b)
                    .orElse(null);
        } catch (Throwable t) {
            // PairSessionManager service may not exist in pure unit tests.
            return null;
        }
    }

    /**
     * Phase 6a (2026-05-24): hook the main-AI observability monitor on every
     * authoritative turn boundary. The monitor lives on the attached pair
     * (per design — main-AI monitoring is currently pair-scoped). Best-effort
     * — never break the main turn flow, even if the monitor throws.
     */
    private void notifyMainAIMonitorTurnStart() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.onTurnStart();
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private void notifyMainAIMonitorTurnEnd() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.onTurnEnd();
        } catch (Throwable ignored) { /* never propagate */ }
    }

    /**
     * Contract State Machine v3 (2026-05-25 fix): main-AI turn boundary needs
     * to drive PlanStateMachine transitions too — without onTurnStarted /
     * onTurnEnded, the plan stays in PENDING_DISCHARGE forever after the
     * first contract is issued, which means TransitionDispatcher never sees
     * a PENDING_DECISION transition → supervisor never gets auto-woken →
     * Step 2+ never dispatched (the user-reported "supervisor 说派单了但主 AI 没收到").
     */
    private void notifyPlanStateMachineTurnStart() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.plan.PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm != null) {
                sm.onTurnStarted(com.github.claudecodegui.session.pair.contract.ContractAssignee.MAIN_AI);
            }
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private void notifyPlanStateMachineTurnEnd() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.plan.PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm == null) return;
            // Recompute hasOpenContracts for the main-AI assignee. Discharge
            // happens via ActionRouter.markDirectiveAcked BEFORE stream_end
            // (report_turn_completion is typically the last tool call), so by
            // now the registry reflects the new state.
            com.github.claudecodegui.session.pair.contract.ContractRegistry r = pair.getContractRegistry();
            boolean hasOpen = false;
            if (r != null) {
                for (com.github.claudecodegui.session.pair.contract.Contract c : r.getOpenContracts()) {
                    if (c.assignedTo == com.github.claudecodegui.session.pair.contract.ContractAssignee.MAIN_AI) {
                        hasOpen = true;
                        break;
                    }
                }
            }
            sm.onTurnEnded(com.github.claudecodegui.session.pair.contract.ContractAssignee.MAIN_AI, hasOpen);
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private void notifyMainAIMonitorError(String error) {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.onError(error);
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private void notifyMainAIMonitorCompactBoundary() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.onCompactBoundary();
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private void notifyMainAIMonitorUserMessage(String text) {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.captureUserMessage(text);
        } catch (Throwable ignored) { /* never propagate */ }
    }

    /**
     * Deadlock fix (2026-06-04): bump the attached pair's main-AI activity clock
     * so {@code DeadlockGuard} can tell honest work from a silent / parked turn.
     * No-op when no pair is attached.
     */
    private void noteMainAIActivity() {
        // Throttle: this fires per content-delta (per token); ms granularity is
        // plenty for the guard's minutes-scale silence threshold, so skip the
        // pair lookup if we already noted activity in the last second.
        long now = System.currentTimeMillis();
        if (now - lastActivityNoteMs < 1000L) return;
        lastActivityNoteMs = now;
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.noteActivity();
        } catch (Throwable ignored) { /* never propagate */ }
    }

    /** Throttle marker for {@link #noteMainAIActivity()} (single-threaded per session). */
    private long lastActivityNoteMs = 0L;

    /**
     * Deadlock fix (2026-06-04): a tool_result means the main AI got data back
     * and is resuming — count it as activity and drop the awaiting-user latch
     * (e.g. an AskUserQuestion answer just landed).
     */
    private void clearMainAIAwaitingUser() {
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) {
                m.noteActivity();
                m.clearAwaitingUser();
            }
        } catch (Throwable ignored) { /* never propagate */ }
    }

    /**
     * Deadlock fix (2026-06-04): a main-AI tool_use that can block on the user
     * (AskUserQuestion / ExitPlanMode plan-approval) suspends the SDK turn
     * without ever firing turn_end. We only set the awaiting-user LATCH here and
     * let {@code DeadlockGuard}'s tick be the waker.
     *
     * <p>2026-06-04 fix: we deliberately do NOT wake the supervisor eagerly from
     * the tool_use. The tool may be denied by a PreToolUse hook (e.g.
     * {@code AskUserQuestion denied}) or otherwise return immediately — in which
     * case a tool_result lands within ~1s and {@link #clearMainAIAwaitingUser}
     * drops the latch. An eager wake fired a false "main AI is blocked" signal
     * that made the supervisor try to wait on an already-discharged contract.
     * The guard tick (≤30s) reads {@code isAwaitingUser()} AFTER any such quick
     * clear, so it acts only on a turn that is GENUINELY parked. No-op for
     * non-blocking tools / no pair.
     */
    private void notifyMainAIAwaitingUserFromTool(String toolName, JsonObject input) {
        if (toolName == null) return;
        String lower = toolName.toLowerCase();
        String kind;
        if (lower.contains("askuserquestion") || lower.contains("ask_user")) {
            kind = "ask_user";
        } else if (lower.contains("exitplanmode") || lower.contains("exit_plan")) {
            kind = "plan_approval";
        } else {
            return;
        }
        try {
            com.github.claudecodegui.session.pair.PairSession pair = findAttachedPair();
            if (pair == null) return;
            String question = extractAwaitingUserQuestion(kind, input);
            com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
            if (m != null) m.markAwaitingUser(kind, question);
        } catch (Throwable ignored) { /* never propagate */ }
    }

    private static String extractAwaitingUserQuestion(String kind, JsonObject input) {
        if (input == null) return null;
        try {
            if ("plan_approval".equals(kind) && input.has("plan") && !input.get("plan").isJsonNull()) {
                return input.get("plan").getAsString();
            }
            // AskUserQuestion: input.questions[].question
            if (input.has("questions") && input.get("questions").isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (var el : input.getAsJsonArray("questions")) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject q = el.getAsJsonObject();
                    if (q.has("question") && !q.get("question").isJsonNull()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(q.get("question").getAsString());
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
            if (input.has("question") && !input.get("question").isJsonNull()) {
                return input.get("question").getAsString();
            }
        } catch (Exception ignored) { /* best-effort */ }
        return null;
    }

    /**
     * Deadlock fix (2026-06-04): arm the awaiting-user latch when a turn ends
     * with a "本轮待确认事项" section AND there is still an open MAIN_AI contract
     * (i.e. the supervisor hasn't been notified by a clean discharge). The
     * turn_end event already carries the text; this latch is the backstop so
     * DeadlockGuard wakes the supervisor rather than re-injecting a nudge.
     */
    private void maybeMarkAwaitingUserFromText(PairSession pair, String assistantText) {
        if (pair == null || assistantText == null || assistantText.isEmpty()) return;
        if (!assistantText.contains("本轮待确认事项")) return;
        com.github.claudecodegui.session.pair.contract.ContractRegistry registry = pair.getContractRegistry();
        boolean hasOpenMainAi = false;
        if (registry != null) {
            for (com.github.claudecodegui.session.pair.contract.Contract c : registry.getOpenContracts()) {
                if (c.assignedTo == com.github.claudecodegui.session.pair.contract.ContractAssignee.MAIN_AI) {
                    hasOpenMainAi = true;
                    break;
                }
            }
        }
        // Contract already discharged → the supervisor was woken via turn_end;
        // no backstop needed.
        if (!hasOpenMainAi) return;
        com.github.claudecodegui.session.pair.MainAIMonitor m = pair.getMainAIMonitor();
        if (m != null) {
            int idx = assistantText.indexOf("本轮待确认事项");
            String excerpt = idx >= 0 ? assistantText.substring(idx) : assistantText;
            m.markAwaitingUser("pending_confirmation", excerpt);
        }
    }

    /**
     * Append a tool_use record to the in-flight turn buffer. Called from
     * {@link #handleAssistantMessage(String)} whenever a tool_use block is seen.
     */
    private void recordTurnToolUse(JsonObject toolBlock) {
        if (toolBlock == null) return;
        if (turnStartedAt == 0) {
            turnStartedAt = System.currentTimeMillis();
            // Fresh turn detected — re-arm the publish guard so stream_end /
            // onComplete for THIS turn can emit again.
            turnEndPublished = false;
        }
        String name = toolBlock.has("name") && !toolBlock.get("name").isJsonNull()
                ? toolBlock.get("name").getAsString() : "?";
        // Deadlock fix (2026-06-04): a tool_use is main-AI activity; and a
        // user-blocking tool (AskUserQuestion / ExitPlanMode) parks the turn
        // without a turn_end — flag it + wake the supervisor immediately.
        noteMainAIActivity();
        JsonObject toolInput = toolBlock.has("input") && toolBlock.get("input").isJsonObject()
                ? toolBlock.getAsJsonObject("input") : null;
        notifyMainAIAwaitingUserFromTool(name, toolInput);
        String path = null;
        if (toolBlock.has("input") && toolBlock.get("input").isJsonObject()) {
            JsonObject input = toolBlock.getAsJsonObject("input");
            // Common path field names across tools (Read/Edit/Write/Bash use different keys).
            for (String key : new String[]{"path", "file_path", "filePath", "filename", "target"}) {
                if (input.has(key) && !input.get(key).isJsonNull()) {
                    path = input.get(key).getAsString();
                    break;
                }
            }
        }
        // Defer ok=true until tool_result arrives; for turn_end summary we assume success
        // unless we later see a tool_result indicating an error. Refinement is future work.
        turnToolUses.add(new EventBus.ToolUseRecord(name, path, true));
        if (path != null && !path.isEmpty()) {
            turnModifiedFiles.add(path);
        }
    }

    /**
     * Protocol v2 (2026-05-24): handle a TURN_REPORT NDJSON line emitted by
     * the daemon's mcp__main MCP server. Extracts the inner payload and
     * forwards to the attached Pair's EventBus via {@code publishTurnReport}.
     *
     * <p>This is the structured replacement for the legacy turn_end path —
     * carries deliverables / verifications / selfAssessment that supervisor
     * uses for confidence-based review triage.
     */
    private void handleTurnReport(String content) {
        if (content == null || !content.startsWith("{")) return;
        try {
            JsonObject envelope = gson.fromJson(content, JsonObject.class);
            if (envelope == null || !envelope.has("payload")
                    || !envelope.get("payload").isJsonObject()) {
                LOG.warn("[Supervisor] turn_report missing payload, dropping");
                return;
            }
            PairSession pair = findAttachedPair();
            if (pair == null || pair.getEventBus() == null) return;
            JsonObject reportPayload = envelope.getAsJsonObject("payload");
            // Carry directiveId on the payload so supervisor can correlate.
            if (envelope.has("directiveId") && !envelope.get("directiveId").isJsonNull()) {
                reportPayload.add("directiveId", envelope.get("directiveId"));
            }
            // turnId for downstream cross-reference (UI cards / decision logs)
            if (envelope.has("turnId") && !envelope.get("turnId").isJsonNull()) {
                reportPayload.add("turnId", envelope.get("turnId"));
            }
            pair.getEventBus().publishTurnReport(reportPayload);
            // Mark turnEndPublished so the legacy publishTurnEndIfPair path doesn't
            // double-publish for the same turn (the report is the authoritative signal).
            turnEndPublished = true;
        } catch (Exception e) {
            LOG.warn("[Supervisor] handleTurnReport parse failed: " + e.getMessage());
        }
    }

    /**
     * Protocol v2 (2026-05-24): handle a SUBAGENT_STOP NDJSON line emitted by
     * the daemon's SDK SubagentStop hook (registered only on Pair-mode runtimes).
     * Forwards the inner payload to the supervisor so it can observe what the
     * main AI's Task subagent did — without breaking subagent context isolation.
     */
    private void handleSubagentStop(String content) {
        if (content == null || !content.startsWith("{")) return;
        try {
            JsonObject envelope = gson.fromJson(content, JsonObject.class);
            if (envelope == null || !envelope.has("payload")
                    || !envelope.get("payload").isJsonObject()) {
                LOG.warn("[Supervisor] subagent_stop missing payload, dropping");
                return;
            }
            PairSession pair = findAttachedPair();
            if (pair == null || pair.getEventBus() == null) return;
            // Deadlock fix (2026-06-04): a subagent boundary is main-AI activity
            // — keeps a long multi-subagent turn from tripping the guard's
            // silence detection.
            com.github.claudecodegui.session.pair.MainAIMonitor mon = pair.getMainAIMonitor();
            if (mon != null) mon.noteActivity();
            // Protocol v2 (2026-05-24): bump budget counter so cost tracking
            // reflects subagent calls (each is a separate model call → tokens).
            com.github.claudecodegui.session.pair.PairBudgetTracker bt = pair.getBudgetTracker();
            if (bt != null) bt.incrementSubagentCalls();
            pair.getEventBus().publishSubagentStop(envelope.getAsJsonObject("payload"));
        } catch (Exception e) {
            LOG.warn("[Supervisor] handleSubagentStop parse failed: " + e.getMessage());
        }
    }

    /**
     * Forward this turn's accumulated activity to the Supervisor EventBus
     * (if a Pair is attached). Idempotent within one turn: stream_end and a
     * subsequent onComplete will only emit once.
     *
     * <p>Protocol v2 fallback path: if the main AI called {@code report_turn_completion},
     * {@link #handleTurnReport(String)} has already set {@code turnEndPublished}
     * and this method becomes a no-op. Only fires for legacy turns that didn't
     * report (e.g. main AI prompt missing or model ignored the instruction).
     */
    private void publishTurnEndIfPair() {
        if (turnEndPublished) {
            // Already sent for this turn — ignore the follow-up trigger.
            return;
        }
        PairSession pair = findAttachedPair();
        if (pair == null || pair.getEventBus() == null) {
            resetTurnBuffer();
            return;
        }
        long now = System.currentTimeMillis();
        long startedAt = turnStartedAt == 0 ? now : turnStartedAt;
        long durationMs = now - startedAt;
        List<String> modifiedFiles = new ArrayList<>(turnModifiedFiles);

        // Phase 0 (2026-05-24): include the main AI's natural-language reply so
        // the supervisor sees manifest YAML / summaries / etc that previously
        // were invisible behind toolUses+modifiedFiles alone. Capped to 8000
        // chars matching the Phase 2 spill-to-file threshold; full text spill
        // arrives via the report_turn_completion MCP tool in Phase 2.
        String assistantText = assistantContent.toString();
        final int MAX_ASSISTANT_TEXT_CHARS = 8000;
        if (assistantText.length() > MAX_ASSISTANT_TEXT_CHARS) {
            int truncated = assistantText.length() - MAX_ASSISTANT_TEXT_CHARS;
            assistantText = assistantText.substring(0, MAX_ASSISTANT_TEXT_CHARS)
                    + "\n…(truncated " + truncated + " chars)";
        }

        try {
            // No plan-aware in/off split here — that's the Supervisor's job using plan.md.
            pair.getEventBus().publishTurnEnd(
                    /*stepIndex*/ 0,
                    /*stepTitle*/ null,
                    new ArrayList<>(turnToolUses),
                    modifiedFiles,
                    /*modifiedOffPlan*/ null,
                    durationMs,
                    assistantText
            );
            turnEndPublished = true;
        } catch (Exception e) {
            LOG.debug("[Supervisor] publishTurnEnd failed: " + e.getMessage());
        }

        // Contract State Machine v3 (2026-05-25): if this turn produced no
        // tool_use AND there are still open MAIN_AI contracts that weren't
        // discharged (i.e. main AI replied with prose only — conversational
        // drift like "请发送下一步..."), trigger immediate retry instead of
        // waiting for the 10min contract deadline. Targets the specific
        // 2026-05-25 stall scenario from docs/plans/2026-05-25-...md §17.1.
        checkContractDischargeOnTurnEnd(pair);

        // Deadlock fix (2026-06-04): a turn that ends with a "本轮待确认事项"
        // section is the design-supervisor.md prescribed "I need the user to
        // confirm" path. Arm the awaiting-user latch so DeadlockGuard wakes the
        // supervisor as a backstop (instead of re-injecting a nudge that would
        // clobber the question) if the turn_end wake is lost.
        maybeMarkAwaitingUserFromText(pair, assistantText);

        resetTurnBuffer();
    }

    /**
     * Contract State Machine v3 (2026-05-25): detect conversational drift.
     * If turn ended with NO tool_use and there are open MAIN_AI contracts,
     * the main AI clearly ignored its task — fire immediate retry per
     * contract so the user doesn't wait the full deadline.
     */
    private void checkContractDischargeOnTurnEnd(PairSession pair) {
        if (!turnToolUses.isEmpty()) {
            return;  // Tool calls happened — main AI is working, even if not yet discharged.
        }
        com.github.claudecodegui.session.pair.contract.ContractRegistry registry =
                pair.getContractRegistry();
        if (registry == null) return;
        java.util.List<com.github.claudecodegui.session.pair.contract.Contract> open =
                registry.getOpenContracts();
        for (com.github.claudecodegui.session.pair.contract.Contract c : open) {
            if (c.assignedTo != com.github.claudecodegui.session.pair.contract.ContractAssignee.MAIN_AI) {
                continue;
            }
            // Skip if already at max retries — DeadlockGuard will escalate.
            if (c.retryCount >= c.maxRetries) continue;
            try {
                LOG.warn("[ClaudeMessageHandler] conversational drift detected — "
                        + "auto-retry contract " + c.id + " (retryCount=" + c.retryCount + ")");
                // Use SYSTEM_NUDGE type so the hint hammers home "you must tool_use".
                registry.retry(c.id,
                        com.github.claudecodegui.session.pair.contract.ContractType.SYSTEM_NUDGE,
                        "main AI turn ended with no tool_use and no discharge — conversational drift");
            } catch (Exception e) {
                LOG.warn("[ClaudeMessageHandler] auto-retry failed for " + c.id + ": " + e.getMessage());
            }
        }
    }

    /**
     * Forward an error event to the Supervisor EventBus (if a Pair is attached).
     * Best-effort classification: code is set from the prefix of the error
     * message; the Supervisor LLM does the final triage.
     */
    private void publishErrorIfPair(String error) {
        PairSession pair = findAttachedPair();
        if (pair == null || pair.getEventBus() == null) {
            resetTurnBuffer();
            return;
        }
        String code = classifyErrorCode(error);
        Integer status = null;
        if (code != null && code.matches("\\d{3}")) {
            try { status = Integer.parseInt(code); } catch (NumberFormatException ignored) { /* fall-through */ }
        }
        int attempt = turnRetryCount.incrementAndGet();
        try {
            pair.getEventBus().publishError(
                    /*stepIndex*/ 0,
                    code,
                    status,
                    /*retryAfter*/ null,
                    error,
                    attempt
            );
        } catch (Exception e) {
            LOG.debug("[Supervisor] publishError failed: " + e.getMessage());
        }
        resetTurnBuffer();
    }

    private void resetTurnBuffer() {
        turnToolUses.clear();
        turnModifiedFiles.clear();
        turnStartedAt = 0;
    }

    private static String classifyErrorCode(String error) {
        if (error == null) return null;
        String e = error.toLowerCase();
        if (e.contains("429") || e.contains("rate_limit") || e.contains("rate limit")) return "429";
        if (e.contains("401") || e.contains("unauthorized")) return "401";
        if (e.contains("403") || e.contains("forbidden")) return "403";
        if (e.contains("500") || e.contains("502") || e.contains("503") || e.contains("504")) return "5xx";
        if (e.contains("timeout") || e.contains("timed out")) return "timeout";
        if (e.contains("context") && (e.contains("overflow") || e.contains("too long") || e.contains("limit"))) {
            return "context_overflow";
        }
        return null;
    }
}
