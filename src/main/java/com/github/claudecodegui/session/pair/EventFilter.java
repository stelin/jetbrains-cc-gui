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
            "user_input"
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
