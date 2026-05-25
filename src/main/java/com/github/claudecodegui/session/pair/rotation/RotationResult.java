package com.github.claudecodegui.session.pair.rotation;

/**
 * Phase 4 (2026-05-24): outcome of a single {@code RotationCoordinator.execute}
 * call. Discriminated by {@link #kind}; the other fields are populated
 * accordingly. Immutable.
 */
public final class RotationResult {

    public enum Kind {
        /** Rotation completed cleanly; {@link #newSupervisorId} populated. */
        SUCCESS,
        /** Rotation skipped — last rotation < cooldown threshold. */
        COOLDOWN,
        /** Rotation aborted before swap; supervisor unchanged. {@link #reason} explains. */
        FAILED,
        /** Rotation aborted because pair was disposed or coordinator could not be entered. */
        ABORTED
    }

    public final Kind kind;
    public final String newSupervisorId;
    public final String reason;
    public final long durationMs;

    private RotationResult(Kind kind, String newSupervisorId, String reason, long durationMs) {
        this.kind = kind;
        this.newSupervisorId = newSupervisorId;
        this.reason = reason;
        this.durationMs = durationMs;
    }

    public static RotationResult success(String newSupervisorId, long durationMs) {
        return new RotationResult(Kind.SUCCESS, newSupervisorId, null, durationMs);
    }

    public static RotationResult cooldown(long remainingMs) {
        return new RotationResult(Kind.COOLDOWN, null, "cooldown remaining " + remainingMs + "ms", 0);
    }

    public static RotationResult failed(String reason, long durationMs) {
        return new RotationResult(Kind.FAILED, null, reason, durationMs);
    }

    public static RotationResult aborted(String reason) {
        return new RotationResult(Kind.ABORTED, null, reason, 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RotationResult{").append(kind);
        if (newSupervisorId != null) sb.append(" newSupervisorId=").append(newSupervisorId);
        if (reason != null) sb.append(" reason=").append(reason);
        sb.append(" durationMs=").append(durationMs).append('}');
        return sb.toString();
    }
}
