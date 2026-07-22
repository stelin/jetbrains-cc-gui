package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.permission.PermissionRequest;

import java.util.List;

/**
 * Callback handler.
 * Dispatches various session callback notifications.
 */
public class CallbackHandler {
    private ClaudeSession.SessionCallback callback;

    public void setCallback(ClaudeSession.SessionCallback callback) {
        this.callback = callback;
    }

    /**
     * Notify of a message update.
     */
    public void notifyMessageUpdate(List<ClaudeSession.Message> messages) {
        if (callback != null) {
            callback.onMessageUpdate(messages);
        }
    }

    /**
     * Notify of a state change.
     */
    public void notifyStateChange(boolean busy, boolean loading, String error) {
        if (callback != null) {
            callback.onStateChange(busy, loading, error);
        }
    }

    /**
     * Notify status message (e.g., reconnecting notices).
     */
    public void notifyStatusMessage(String message) {
        if (callback != null) {
            callback.onStatusMessage(message);
        }
    }

    /**
     * Notify that a session ID was received.
     */
    public void notifySessionIdReceived(String sessionId) {
        if (callback != null) {
            callback.onSessionIdReceived(sessionId);
        }
    }

    /**
     * Notify of a permission request.
     */
    public void notifyPermissionRequested(PermissionRequest request) {
        if (callback != null) {
            callback.onPermissionRequested(request);
        }
    }

    /**
     * Notify of a thinking status change.
     */
    public void notifyThinkingStatusChanged(boolean isThinking) {
        if (callback != null) {
            callback.onThinkingStatusChanged(isThinking);
        }
    }

    /**
     * Notify that slash commands were received.
     */
    public void notifySlashCommandsReceived(List<String> slashCommands) {
        if (callback != null) {
            callback.onSlashCommandsReceived(slashCommands);
        }
    }

    /**
     * Notify of a Node.js log (forwarded to frontend console).
     */
    public void notifyNodeLog(String log) {
        if (callback != null) {
            callback.onNodeLog(log);
        }
    }

    public void notifySummaryReceived(String summary) {
        if (callback != null) {
            callback.onSummaryReceived(summary);
        }
    }

    /**
     * Notify of a raw SDK {@code task_*} system event (Workflow / background
     * task lifecycle), forwarded verbatim to the webview status panel.
     */
    public void notifyTaskEvent(String taskEventJson) {
        if (callback != null) {
            callback.onTaskEvent(taskEventJson);
        }
    }
    // ===== Streaming notification methods =====

    /**
     * Notify that streaming has started.
     */
    public void notifyStreamStart() {
        if (callback != null) {
            callback.onStreamStart();
        }
    }

    /**
     * Notify that streaming has ended.
     */
    public void notifyStreamEnd() {
        if (callback != null) {
            callback.onStreamEnd();
        }
    }

    /**
     * Notify of a content delta (handled by the existing onContentDelta callback).
     */
    public void notifyContentDelta(String delta) {
        if (callback != null) {
            callback.onContentDelta(delta);
        }
    }

    /**
     * Notify of a thinking delta.
     */
    public void notifyThinkingDelta(String delta) {
        if (callback != null) {
            callback.onThinkingDelta(delta);
        }
    }

    /**
     * Notify of a usage update. {@code outputTokens} is the generated-output
     * count for the in-flight turn (for the CLI-style live "↓ N tokens" counter);
     * {@code usedTokens} remains the whole-context total driving the % indicator.
     */
    public void notifyUsageUpdate(int usedTokens, int maxTokens, int outputTokens) {
        if (callback != null) {
            callback.onUsageUpdate(usedTokens, maxTokens, outputTokens);
        }
    }

    /**
     * Notify that the daemon confirmed an effort tier was applied to the SDK call
     * (echoed from "[REASONING_EFFORT] ✓ ... applied options.effort=xxx" log line).
     */
    public void notifyReasoningEffortApplied(String effort) {
        if (callback != null) {
            callback.onReasoningEffortApplied(effort);
        }
    }

    /**
     * Notify that the bridge classified a [SEND_ERROR] with a known code
     * (e.g. {@code LONG_CONTEXT_NOT_ENTITLED}).
     */
    public void notifyClaudeErrorCode(String code) {
        if (callback != null) {
            callback.onClaudeErrorCode(code);
        }
    }

    /**
     * Notify that a specific message received its provider UUID.
     */
    public void notifyUserMessageUuidPatched(String content, String uuid) {
        if (callback != null) {
            callback.onUserMessageUuidPatched(content, uuid);
        }
    }
}
