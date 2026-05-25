package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Whitelist filter applied at the boundary between the main AI event stream
 * and a Supervisor session. Drops noisy events (per-token deltas, individual
 * tool_use events) and keeps only the ones a Supervisor must decide on.
 *
 * <p>The filter operates on the abstract event objects flowing through
 * {@link EventBus} — not on raw NDJSON lines.
 */
public final class EventFilter {

    /** Event {@code type} values that get forwarded to a Supervisor. */
    public static final Set<String> FORWARDED_TYPES = Set.of(
            "start",
            "turn_end",
            "error",
            "idle_timeout",
            "off_plan_detected",
            "verify_result",
            "review_result",
            "human_response",
            "user_input",
            // Phase 3 (Protocol v2, 2026-05-24): structured turn report + subagent
            // visibility + budget tracking + directive ack timeout.
            "turn_report",
            "subagent_stop",
            "budget_warning",
            "budget_exceeded",
            "directive_lost",
            // Phase 6 (2026-05-24): autonomy control layer.
            // step_blocked: F2 — 3 consecutive directive_lost, supervisor should skip step.
            // replan_due:   T1 — periodic (every 5 steps) / after_alert nudge.
            "step_blocked",
            "replan_due"
    );

    private EventFilter() { /* static-only */ }

    public static boolean shouldForward(JsonObject event) {
        if (event == null || !event.has("type") || event.get("type").isJsonNull()) {
            return false;
        }
        String type = event.get("type").getAsString();
        return FORWARDED_TYPES.contains(type);
    }
}
