package com.github.claudecodegui.session.pair.l2;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Phase 3 (2026-05-24): forward-only L2 schema migrations.
 *
 * <p>{@link L2Schema#CURRENT_VERSION} is 2 (Phase 3 is the first persisted
 * version), so today this is largely a stub. The pattern stays in place so
 * Phase 4+ can introduce v3 without restructuring callers.
 *
 * <p>Migration policy: a state that fails to migrate is treated like a
 * corrupted file — caller falls back to {@code .bak} or starts fresh.
 */
public final class L2Migration {

    private static final Logger LOG = Logger.getInstance(L2Migration.class);

    private L2Migration() { /* no instances */ }

    /**
     * Bring {@code state} up to the current schema version in-place.
     * Returns the same instance (mutated) on success.
     *
     * @throws L2Validator.L2ValidationException if the version is unknown or
     *         migration would lose data.
     */
    public static L2State migrate(L2State state) {
        if (state == null) {
            throw new L2Validator.L2ValidationException("L2 migrate: state is null");
        }
        int from = state.schemaVersion;
        if (from == L2Schema.CURRENT_VERSION) {
            return state;
        }
        if (from > L2Schema.CURRENT_VERSION) {
            // Forward-only — we don't downgrade.
            throw new L2Validator.L2ValidationException(
                    "L2 migrate: cannot downgrade from v" + from + " to v" + L2Schema.CURRENT_VERSION);
        }
        // v2 → v3 (2026-05-25): adds Contract State Machine fields.
        // Existing fields (anchoredFacts, planProgress, fileState, recentDecisions,
        // mainAI, etc.) are unchanged. Gson has already populated them from the
        // raw JSON; we just initialize the new fields.
        if (from == 2) {
            state.plan = null;
            if (state.openContracts == null) {
                state.openContracts = new java.util.ArrayList<>();
            }
            state.schemaVersion = 3;
            LOG.info("[L2Migration] " + state.pairId + " v2 → v3: added plan=null, openContracts=[]");
            return state;
        }
        // No further migration paths. Treat unknown older versions as un-migratable.
        throw new L2Validator.L2ValidationException(
                "L2 migrate: no migration path from v" + from + " to v" + L2Schema.CURRENT_VERSION);
    }

    /**
     * Helper for callers that want to "best-effort migrate" — return null
     * instead of throwing.
     */
    public static L2State tryMigrate(L2State state) {
        try {
            return migrate(state);
        } catch (Exception e) {
            LOG.warn("[L2Migration] migrate failed: " + e.getMessage());
            return null;
        }
    }
}
