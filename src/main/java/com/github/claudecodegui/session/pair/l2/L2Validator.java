package com.github.claudecodegui.session.pair.l2;

/**
 * Phase 3 (2026-05-24): cheap consistency checks on an {@link L2State} before
 * persisting. Throws {@link L2ValidationException} so callers can decide to
 * reject the write / fall back to a previous good copy / log + repair.
 *
 * <p>Deliberately not a full JSON-schema check — only invariants whose
 * violation would corrupt the rotation pipeline:
 * <ul>
 *   <li>schemaVersion matches the code version (or earlier — migration job).</li>
 *   <li>pairId is non-empty and consistent.</li>
 *   <li>Ring caps respected (defensive: we trim, but enforce on read too).</li>
 *   <li>generation, rotationCount are non-negative.</li>
 * </ul>
 */
public final class L2Validator {

    private L2Validator() { /* no instances */ }

    public static class L2ValidationException extends RuntimeException {
        public L2ValidationException(String message) { super(message); }
    }

    public static void validate(L2State state) {
        if (state == null) {
            throw new L2ValidationException("L2State is null");
        }
        if (state.schemaVersion <= 0) {
            throw new L2ValidationException("schemaVersion must be > 0, got " + state.schemaVersion);
        }
        if (state.schemaVersion > L2Schema.CURRENT_VERSION) {
            throw new L2ValidationException(
                    "L2 schemaVersion " + state.schemaVersion + " is newer than this code supports ("
                            + L2Schema.CURRENT_VERSION + "); refusing to write to avoid losing fields");
        }
        if (state.pairId == null || state.pairId.isEmpty()) {
            throw new L2ValidationException("pairId is empty");
        }
        if (state.generation < 0) {
            throw new L2ValidationException("generation < 0: " + state.generation);
        }
        if (state.rotationCount < 0) {
            throw new L2ValidationException("rotationCount < 0: " + state.rotationCount);
        }
        if (state.createdAt <= 0) {
            throw new L2ValidationException("createdAt missing");
        }
        if (state.recentDecisions != null
                && state.recentDecisions.size() > L2Schema.RECENT_DECISIONS_MAX) {
            throw new L2ValidationException(
                    "recentDecisions exceeds cap " + L2Schema.RECENT_DECISIONS_MAX
                            + " (size=" + state.recentDecisions.size() + ")");
        }
        if (state.compactionHistory != null
                && state.compactionHistory.size() > L2Schema.COMPACTION_HISTORY_MAX) {
            throw new L2ValidationException(
                    "compactionHistory exceeds cap " + L2Schema.COMPACTION_HISTORY_MAX
                            + " (size=" + state.compactionHistory.size() + ")");
        }
    }
}
