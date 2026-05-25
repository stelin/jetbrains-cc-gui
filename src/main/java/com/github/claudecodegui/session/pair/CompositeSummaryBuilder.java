package com.github.claudecodegui.session.pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Phase 1 (2026-05-23): builds the {@code composite_summary} event that the
 * monitor sends to the daemon on each tick.
 *
 * <p>Java's role is only to assemble a structured payload describing the
 * batch: the list of original events + drop count + timing metadata. The
 * daemon's {@code event-summarizer.js} owns the rendering — it iterates the
 * child events through the existing per-type formatters so the supervisor
 * sees the same wording as before, just bundled into one user message.
 *
 * <p>This keeps formatting logic in one place (daemon) while letting Java
 * own the batching / lifecycle.
 */
public final class CompositeSummaryBuilder {

    private CompositeSummaryBuilder() { /* no instances */ }

    /** Phase 1 entry point: no banner. Phase 4+ prefer the 6-arg variant. */
    public static JsonObject build(EventCollector.DrainResult batch,
                                   long batchStartMs,
                                   long batchEndMs,
                                   long tickNumber,
                                   boolean healthCheck) {
        return build(batch, batchStartMs, batchEndMs, tickNumber, healthCheck, null);
    }

    /**
     * @param batch        events drained from {@link EventCollector}
     * @param batchStartMs wall-clock ms when collection window started
     * @param batchEndMs   wall-clock ms when collection window ended (drain time)
     * @param tickNumber   monotonic tick counter (for logging)
     * @param healthCheck  true if this is a periodic health-check tick (idle ok)
     * @param generationBanner Phase 4: optional one-shot text the daemon's
     *                     event-summarizer prepends to the rendered output
     *                     so the new-generation supervisor sees an
     *                     "you just inherited" banner inline with normal events
     * @return a composite event with type "composite_summary" ready for
     *         {@code SupervisorBridge.postEvent}
     */
    public static JsonObject build(EventCollector.DrainResult batch,
                                   long batchStartMs,
                                   long batchEndMs,
                                   long tickNumber,
                                   boolean healthCheck,
                                   String generationBanner) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "composite_summary");

        JsonObject payload = new JsonObject();
        payload.addProperty("batchStartMs", batchStartMs);
        payload.addProperty("batchEndMs", batchEndMs);
        payload.addProperty("tick", tickNumber);
        payload.addProperty("droppedSincePrevious", batch.droppedSincePrevious);
        payload.addProperty("healthCheck", healthCheck);
        payload.addProperty("eventCount", batch.events.size());
        payload.addProperty("urgentCount", countUrgent(batch.events));
        if (generationBanner != null && !generationBanner.isEmpty()) {
            payload.addProperty("generationBanner", generationBanner);
        }

        JsonArray arr = new JsonArray();
        for (JsonObject e : batch.events) {
            arr.add(e);
        }
        payload.add("events", arr);

        event.add("payload", payload);
        return event;
    }

    private static int countUrgent(List<JsonObject> events) {
        int n = 0;
        for (JsonObject e : events) {
            if (UrgentPolicy.isUrgent(e)) n++;
        }
        return n;
    }
}
