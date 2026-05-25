package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 1 (2026-05-23): classifies events as urgent (need to wake the monitor
 * before the next 30s tick) vs normal (wait for the next scheduled tick).
 *
 * <p>Urgent events fire a "cancel + reschedule" on the monitor's scheduler so
 * the next tick runs after a short debounce instead of 30s. Debounce per type
 * prevents bursts (e.g. 5 verify failures in 200ms) from causing 5 reschedules.
 *
 * <p>This is a pure utility — no state.
 */
public final class UrgentPolicy {

    private UrgentPolicy() { /* no instances */ }

    /** Per-type debounce window in ms. Bursts within this window collapse into one urgent tick. */
    public static final Map<String, Long> DEBOUNCE_MS;

    static {
        Map<String, Long> m = new HashMap<>();
        m.put("human_response",     200L);   // user is waiting in the UI
        // 2026-05-25: user_input was missing from this list, so the very first
        // message after IDE startup sat in EventCollector for up to 30s
        // (waiting for the monitor's scheduled tick) before any "thinking"
        // indicator or response appeared — the IDE looked frozen. User input
        // is even more time-critical than human_response (the user is actively
        // typing and expecting feedback), so same 200ms debounce window.
        m.put("user_input",         200L);
        m.put("error",             1000L);   // main AI failed; faster reaction is better
        m.put("off_plan_detected", 1000L);   // main AI drifting; correct quickly
        m.put("verify_result",     2000L);   // only when pass=false; can burst
        // Phase 3 (2026-05-24): autonomy v2 — directive timeout + budget pause
        // are time-critical (supervisor must re-decide quickly so we don't burn
        // budget waiting for a dead directive).
        m.put("directive_lost",    1000L);
        m.put("budget_exceeded",    500L);   // pair will be paused; wrap up now
        // Phase 6 (2026-05-24): F2 step_blocked — Java already counted 3 fails,
        // supervisor should advance the plan rather than keep waiting.
        m.put("step_blocked",      1000L);
        // Note: budget_warning / turn_report / subagent_stop / replan_due are
        // intentionally NOT urgent — they can ride the next 30s tick.
        DEBOUNCE_MS = Collections.unmodifiableMap(m);
    }

    /**
     * @return true if the event should trigger an urgent wakeup
     */
    public static boolean isUrgent(JsonObject event) {
        if (event == null || !event.has("type") || event.get("type").isJsonNull()) return false;
        String type = event.get("type").getAsString();
        if (!DEBOUNCE_MS.containsKey(type)) return false;

        if ("verify_result".equals(type)) {
            JsonObject p = event.has("payload") && !event.get("payload").isJsonNull()
                    ? event.getAsJsonObject("payload") : null;
            if (p == null || !p.has("pass") || p.get("pass").isJsonNull()) return false;
            return !p.get("pass").getAsBoolean();
        }
        return true;
    }

    /**
     * @return the debounce window (ms) to apply for an urgent event of this type.
     *         Returns 2000 as a safe default for unknown types (shouldn't happen
     *         because {@link #isUrgent(JsonObject)} guards entry).
     */
    public static long debounceMs(JsonObject event) {
        if (event == null || !event.has("type")) return 2000L;
        String type = event.get("type").getAsString();
        Long v = DEBOUNCE_MS.get(type);
        return v == null ? 2000L : v;
    }
}
