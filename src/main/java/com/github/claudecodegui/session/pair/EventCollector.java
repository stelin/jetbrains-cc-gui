package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 1 (2026-05-23): bounded, lock-free ring buffer of events headed for
 * the supervisor.
 *
 * <p>The event source (typically {@code ClaudeMessageHandler.onComplete})
 * calls {@link #publish(JsonObject)} and returns immediately — no blocking
 * round-trip with the daemon. The supervisor's {@link SupervisorMonitor}
 * drains the buffer on its periodic tick (or sooner via urgent reschedule).
 *
 * <p>Drop policy is "drop oldest" because the supervisor is a reviewer, not
 * a real-time copilot: missing an old turn_end is acceptable; losing a
 * recent urgent error is not. We surface the cumulative drop count in the
 * next {@link DrainResult} so the composite-summary builder can annotate
 * "you missed N events" for the supervisor to know.
 */
public class EventCollector {

    private static final Logger LOG = Logger.getInstance(EventCollector.class);

    public static final int DEFAULT_MAX_BUFFER = 200;

    private final String pairId;
    private final int maxBuffer;
    private final Deque<JsonObject> buffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();          // tracked separately since CLD has O(n) size()
    private final AtomicInteger droppedSinceDrain = new AtomicInteger();
    private final AtomicInteger droppedTotal = new AtomicInteger();
    private final AtomicLong lastUrgentFireMs = new AtomicLong(0L);

    /**
     * Callback to fire when an urgent event arrives. Wired by the monitor.
     * Null until set (early publishes before monitor wiring just enqueue).
     */
    private volatile UrgentWakeup urgentWakeup;

    public EventCollector(String pairId) {
        this(pairId, DEFAULT_MAX_BUFFER);
    }

    public EventCollector(String pairId, int maxBuffer) {
        this.pairId = pairId;
        this.maxBuffer = maxBuffer;
    }

    public void setUrgentWakeup(UrgentWakeup wakeup) {
        this.urgentWakeup = wakeup;
    }

    /**
     * Enqueue an event. Always returns immediately — never blocks the caller.
     */
    public void publish(JsonObject event) {
        if (event == null) return;

        // drop-oldest when over cap
        while (size.get() >= maxBuffer) {
            JsonObject evicted = buffer.pollFirst();
            if (evicted == null) break;
            size.decrementAndGet();
            droppedSinceDrain.incrementAndGet();
            int total = droppedTotal.incrementAndGet();
            if (total == 1 || total == 10 || total % 100 == 0) {
                LOG.warn("[EventCollector] " + pairId + " bounded ring overflow, total dropped=" + total);
            }
        }
        buffer.addLast(event);
        size.incrementAndGet();

        // urgent path: cancel + reschedule with per-type debounce
        if (UrgentPolicy.isUrgent(event)) {
            UrgentWakeup hook = urgentWakeup;
            if (hook == null) return;
            long debounce = UrgentPolicy.debounceMs(event);
            long now = System.currentTimeMillis();
            long prev = lastUrgentFireMs.get();
            if (now - prev > debounce && lastUrgentFireMs.compareAndSet(prev, now)) {
                try {
                    hook.urgentWakeup(event);
                } catch (Exception e) {
                    LOG.warn("[EventCollector] urgent wakeup hook threw: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Atomically drain all currently-queued events plus the cumulative drop
     * count accumulated since the last drain.
     */
    public DrainResult drainAll() {
        List<JsonObject> batch = new ArrayList<>();
        JsonObject e;
        while ((e = buffer.pollFirst()) != null) {
            batch.add(e);
            size.decrementAndGet();
        }
        int dropped = droppedSinceDrain.getAndSet(0);
        return new DrainResult(Collections.unmodifiableList(batch), dropped);
    }

    /** Diagnostic accessors. */
    public int currentSize() { return size.get(); }
    public int totalDropped() { return droppedTotal.get(); }
    public int maxBuffer() { return maxBuffer; }

    /** Hook invoked from {@link #publish(JsonObject)} when an urgent event is observed. */
    public interface UrgentWakeup {
        void urgentWakeup(JsonObject event);
    }

    /** Result of {@link #drainAll()}. */
    public static final class DrainResult {
        public final List<JsonObject> events;
        public final int droppedSincePrevious;

        public DrainResult(List<JsonObject> events, int droppedSincePrevious) {
            this.events = events;
            this.droppedSincePrevious = droppedSincePrevious;
        }

        public boolean isEmpty() {
            return events.isEmpty() && droppedSincePrevious == 0;
        }
    }
}
