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
        validatePlan(state);
        validateOpenContracts(state);
    }

    private static void validatePlan(L2State state) {
        if (state.plan == null) return;
        L2State.PersistedPlan p = state.plan;
        if (p.state != null && !isKnownPlanState(p.state)) {
            throw new L2ValidationException("plan.state unknown: " + p.state);
        }
        if (p.subState != null && !isKnownActiveSubState(p.subState)) {
            throw new L2ValidationException("plan.subState unknown: " + p.subState);
        }
        if (p.steps != null) {
            for (L2State.PersistedPlanStep s : p.steps) {
                if (s.owner != null && !isKnownStepOwner(s.owner)) {
                    throw new L2ValidationException("plan.step.owner unknown: " + s.owner);
                }
                if (s.status != null && !isKnownStepStatus(s.status)) {
                    throw new L2ValidationException("plan.step.status unknown: " + s.status);
                }
            }
        }
    }

    private static void validateOpenContracts(L2State state) {
        if (state.openContracts == null) return;
        if (state.openContracts.size() > L2Schema.MAX_OPEN_CONTRACTS_PER_PAIR) {
            throw new L2ValidationException(
                    "openContracts exceeds cap " + L2Schema.MAX_OPEN_CONTRACTS_PER_PAIR
                            + " (size=" + state.openContracts.size() + ")");
        }
        for (L2State.PersistedContract c : state.openContracts) {
            if (c.id == null || c.id.isEmpty()) {
                throw new L2ValidationException("openContract.id is empty");
            }
            if (c.type != null && !isKnownContractType(c.type)) {
                throw new L2ValidationException("openContract.type unknown: " + c.type);
            }
            if (c.status != null && !isKnownContractStatus(c.status)) {
                throw new L2ValidationException("openContract.status unknown: " + c.status);
            }
            if (c.assignedTo != null && !isKnownContractAssignee(c.assignedTo)) {
                throw new L2ValidationException("openContract.assignedTo unknown: " + c.assignedTo);
            }
        }
    }

    private static boolean isKnownPlanState(String s) {
        return "INIT".equals(s) || "ACTIVE".equals(s) || "WAITING".equals(s)
                || "DONE".equals(s) || "ABORTED".equals(s);
    }

    private static boolean isKnownActiveSubState(String s) {
        return "EXECUTING".equals(s) || "PENDING_DISCHARGE".equals(s) || "PENDING_DECISION".equals(s);
    }

    private static boolean isKnownStepOwner(String s) {
        return "SUPERVISOR".equals(s) || "MAIN_AI".equals(s);
    }

    private static boolean isKnownStepStatus(String s) {
        return "TODO".equals(s) || "IN_PROGRESS".equals(s) || "DONE".equals(s)
                || "BLOCKED".equals(s) || "SKIPPED".equals(s);
    }

    private static boolean isKnownContractType(String s) {
        return "TASK_ASSIGNMENT".equals(s) || "DECISION_REQUEST".equals(s)
                || "SYSTEM_NUDGE".equals(s) || "APPROVAL_REQUEST".equals(s)
                || "STATE_REPORT_REQUEST".equals(s);
    }

    private static boolean isKnownContractStatus(String s) {
        return "OPEN".equals(s) || "RECEIVED".equals(s) || "DISCHARGED".equals(s)
                || "EXPIRED_RETRIED".equals(s) || "EXPIRED_ESCALATED".equals(s)
                || "CANCELLED".equals(s);
    }

    private static boolean isKnownContractAssignee(String s) {
        return "MAIN_AI".equals(s) || "SUPERVISOR".equals(s);
    }
}
