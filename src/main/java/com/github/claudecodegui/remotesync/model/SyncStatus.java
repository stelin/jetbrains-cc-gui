package com.github.claudecodegui.remotesync.model;

import com.google.gson.JsonObject;

/**
 * Snapshot of the current mutagen sync session. Mapped 1:1 to the JSON the
 * webview consumes; new states must be reflected in the React {@code
 * SyncStatusBar} component too.
 */
public final class SyncStatus {

    public enum State {
        DISABLED,
        STOPPED,
        STARTING,
        WATCHING,
        SYNCING,
        DISCONNECTED,
        CONFLICT,
        ERROR
    }

    private final State state;
    private final String sessionName;
    private final long stagingProgress;
    private final long stagingTotal;
    private final int conflicts;
    private final String message;
    private final long updatedAt;
    private long dirCount;
    private long fileCount;
    private long fileBytes;

    private SyncStatus(State state, String sessionName, long stagingProgress, long stagingTotal,
                       int conflicts, String message) {
        this.state = state;
        this.sessionName = sessionName;
        this.stagingProgress = stagingProgress;
        this.stagingTotal = stagingTotal;
        this.conflicts = conflicts;
        this.message = message;
        this.updatedAt = System.currentTimeMillis();
    }

    /** Attach inventory counters (mutable so the parser can fill them after construction). */
    public SyncStatus withInventory(long dirCount, long fileCount, long fileBytes) {
        this.dirCount = dirCount;
        this.fileCount = fileCount;
        this.fileBytes = fileBytes;
        return this;
    }

    public static SyncStatus disabled() {
        return new SyncStatus(State.DISABLED, null, 0, 0, 0, null);
    }

    public static SyncStatus stopped(String name) {
        return new SyncStatus(State.STOPPED, name, 0, 0, 0, null);
    }

    public static SyncStatus starting(String name) {
        return new SyncStatus(State.STARTING, name, 0, 0, 0, null);
    }

    public static SyncStatus watching(String name) {
        return new SyncStatus(State.WATCHING, name, 0, 0, 0, null);
    }

    public static SyncStatus syncing(String name, long done, long total) {
        return new SyncStatus(State.SYNCING, name, done, total, 0, null);
    }

    public static SyncStatus disconnected(String name, String reason) {
        return new SyncStatus(State.DISCONNECTED, name, 0, 0, 0, reason);
    }

    public static SyncStatus conflict(String name, int n) {
        return new SyncStatus(State.CONFLICT, name, 0, 0, n, null);
    }

    public static SyncStatus error(String name, String message) {
        return new SyncStatus(State.ERROR, name, 0, 0, 0, message);
    }

    public State getState() { return state; }
    public String getSessionName() { return sessionName; }
    public String getMessage() { return message; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("state", state.name().toLowerCase());
        if (sessionName != null) o.addProperty("sessionName", sessionName);
        o.addProperty("stagingProgress", stagingProgress);
        o.addProperty("stagingTotal", stagingTotal);
        o.addProperty("conflicts", conflicts);
        if (message != null) o.addProperty("message", message);
        o.addProperty("updatedAt", updatedAt);
        o.addProperty("dirCount", dirCount);
        o.addProperty("fileCount", fileCount);
        o.addProperty("fileBytes", fileBytes);
        return o;
    }

    /**
     * Returns true when the new status is meaningfully different from the
     * previous one — used to suppress noisy duplicate broadcasts.
     */
    public boolean isMeaningfullyDifferent(SyncStatus other) {
        if (other == null) return true;
        if (state != other.state) return true;
        if (conflicts != other.conflicts) return true;
        if (stagingTotal != other.stagingTotal) return true;
        if (Math.abs(stagingProgress - other.stagingProgress) > 8192) return true;
        if (!java.util.Objects.equals(message, other.message)) return true;
        return false;
    }
}
