package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Session state management.
 * Maintains all state information for a conversation session.
 */
public class SessionState {

    /**
     * Canonical whitelist of valid permission modes.
     * Shared across SessionHandler (payload validation) and ClaudeSession (mode resolution).
     */
    public static final Set<String> VALID_PERMISSION_MODES;
    static {
        Set<String> modes = new HashSet<>();
        modes.add("default");
        modes.add("plan");
        modes.add("acceptEdits");
        modes.add("autoEdit");
        modes.add("bypassPermissions");
        VALID_PERMISSION_MODES = Collections.unmodifiableSet(modes);
    }

    /**
     * Check whether the given mode string is a recognized permission mode.
     */
    public static boolean isValidPermissionMode(String mode) {
        return mode != null && VALID_PERMISSION_MODES.contains(mode.trim());
    }

    // Session identifiers
    private String sessionId;
    private String channelId;
    private volatile String runtimeSessionEpoch = UUID.randomUUID().toString();

    /**
     * Per-tab window id, propagated from {@code ClaudeChatWindow} at construction.
     * Used by {@code ClaudeMessageHandler.findAttachedPair} and
     * {@code SessionSendService.prependPairContextMarker} to scope Pair lookup
     * to pairs owned by the originating tab — fixes the 2026-05-25 cross-tab
     * event leakage where Tab A's supervisor heard Tab B's main-AI events
     * because the fallback picked the most-recently-started pair project-wide.
     *
     * <p>Volatile: set on the construction thread (EDT for live windows;
     * arbitrary for SessionLifecycleManager rotation paths) and read on the
     * SDK callback thread inside ClaudeMessageHandler with no other
     * happens-before guarantee.
     *
     * <p>May be null for legacy / test callers that built ClaudeSession without
     * a window id — in that case Pair attachment falls back to "no pair"
     * (deliberately strict to prevent cross-tab routing).
     */
    private volatile String windowId;

    /**
     * Phase 6c (2026-05-24): one-shot system-prompt append staged by
     * {@code ClaudeSession#swapInnerSession} for the next outgoing daemon send.
     * Cleared atomically by {@link #consumePendingSystemPromptAppend} once
     * delivered. Volatile because the staging caller (main-AI rotation
     * coordinator on a background thread) and the consuming caller
     * (SessionSendService on the EDT/send thread) have no other happens-before.
     */
    private volatile String pendingSystemPromptAppend;

    // Session state — accessed only on EDT / single handler thread, no volatile needed.
    private boolean busy = false;
    private boolean loading = false;
    private String error = null;

    // Message history
    private final List<ClaudeSession.Message> messages = new ArrayList<>();

    // Session metadata — cwd is written in handler thread before send(), read inside send();
    // the happens-before from CompletableFuture.runAsync guarantees visibility, so volatile is not required.
    private String summary = null;
    private long lastModifiedTime = System.currentTimeMillis();
    private String cwd = null;

    // Configuration fields below are volatile because set_mode / set_model / set_provider
    // and send_message may execute on different async handler threads with no other
    // happens-before guarantee between them.
    private volatile String permissionMode = "bypassPermissions";
    private volatile String model = "claude-sonnet-4-6";
    private volatile String provider = "claude";
    // Reasoning effort (thinking depth) — must stay in sync with webview default in
    // useModelProviderState.ts (currently 'max'). The webview doesn't auto-push its
    // initial state to Java, so this default applies until the user clicks the selector.
    private volatile String reasoningEffort = "max";

    // Slash commands — volatile for cross-thread visibility (same reason as permissionMode/model/provider)
    private volatile List<String> slashCommands = new ArrayList<>();

    // PSI context collection toggle
    private boolean psiContextEnabled = true;

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getChannelId() {
        return channelId;
    }

    /** Per-tab window id; see field doc. May be null. */
    public String getWindowId() {
        return windowId;
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getError() {
        return error;
    }

    public List<ClaudeSession.Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public List<ClaudeSession.Message> getMessagesReference() {
        return messages;
    }

    public String getSummary() {
        return summary;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public String getCwd() {
        return cwd;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public String getRuntimeSessionEpoch() {
        return runtimeSessionEpoch;
    }

    public List<String> getSlashCommands() {
        return new ArrayList<>(slashCommands);
    }



    public boolean isPsiContextEnabled() {
        return psiContextEnabled;
    }

    // Setters
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * Set the per-tab window id. Normally called once by {@link ClaudeSession}'s
     * constructor; setter exists so rotation / history-load paths that pass
     * windowId through can keep using the no-arg state slot.
     */
    public void setWindowId(String windowId) {
        this.windowId = windowId;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public void setPermissionMode(String permissionMode) {
        if (permissionMode != null && !VALID_PERMISSION_MODES.contains(permissionMode.trim())) {
            // Reject unrecognized modes silently to prevent injection of arbitrary strings
            return;
        }
        this.permissionMode = permissionMode;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public void setRuntimeSessionEpoch(String runtimeSessionEpoch) {
        if (runtimeSessionEpoch == null || runtimeSessionEpoch.trim().isEmpty()) {
            this.runtimeSessionEpoch = UUID.randomUUID().toString();
            return;
        }
        this.runtimeSessionEpoch = runtimeSessionEpoch;
    }

    public String rotateRuntimeSessionEpoch() {
        String newEpoch = UUID.randomUUID().toString();
        this.runtimeSessionEpoch = newEpoch;
        return newEpoch;
    }

    /** Phase 6c: stage a system-prompt append for the next send. Null clears. */
    public void setPendingSystemPromptAppend(String append) {
        this.pendingSystemPromptAppend = (append == null || append.isEmpty()) ? null : append;
    }

    /** Phase 6c: non-destructive peek. Use {@link #consumePendingSystemPromptAppend} when sending. */
    public String getPendingSystemPromptAppend() {
        return pendingSystemPromptAppend;
    }

    /**
     * Phase 6c: take-and-clear. Called by the send path so the appended prompt
     * is delivered exactly once.
     */
    public synchronized String consumePendingSystemPromptAppend() {
        String v = pendingSystemPromptAppend;
        pendingSystemPromptAppend = null;
        return v;
    }

    public void setSlashCommands(List<String> slashCommands) {
        this.slashCommands = new ArrayList<>(slashCommands);
    }



    public void setPsiContextEnabled(boolean psiContextEnabled) {
        this.psiContextEnabled = psiContextEnabled;
    }

    /**
     * Add a message to the history.
     */
    public void addMessage(ClaudeSession.Message message) {
        messages.add(message);
    }

    /**
     * Clear all messages.
     */
    public void clearMessages() {
        messages.clear();
    }

    /**
     * Update the last modified time to the current time.
     */
    public void updateLastModifiedTime() {
        this.lastModifiedTime = System.currentTimeMillis();
    }
}
