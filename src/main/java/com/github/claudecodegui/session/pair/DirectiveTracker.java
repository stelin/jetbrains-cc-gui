package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Tracks outstanding inject_prompt directives. Fires a callback when an ack
 *  is missing past {@link #DEFAULT_ACK_TIMEOUT_MS}. Thread-safe. */
public class DirectiveTracker {

    private static final Logger LOG = Logger.getInstance(DirectiveTracker.class);

    public static final long DEFAULT_ACK_TIMEOUT_MS = 5 * 60 * 1000L;

    private final String pairId;
    private final long ackTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final Map<String, DirectiveInfo> pending = new ConcurrentHashMap<>();
    /** Invoked when a directive times out (directiveId, originalPayload).
     *  Wire this to EventBus.publishDirectiveLost on PairSession setup. */
    private volatile BiConsumer<String, JsonObject> onTimeout;
    private volatile boolean disposed = false;

    public DirectiveTracker(String pairId) {
        this(pairId, DEFAULT_ACK_TIMEOUT_MS);
    }

    public DirectiveTracker(String pairId, long ackTimeoutMs) {
        this.pairId = pairId;
        this.ackTimeoutMs = ackTimeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "directive-tracker-" + pairId);
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnTimeout(BiConsumer<String, JsonObject> handler) {
        this.onTimeout = handler;
    }

    /** Register a directive that was just dispatched. If a directive with the
     *  same id was already pending it is replaced (its previous timeout cancelled). */
    public void registerDirective(String directiveId, JsonObject payload) {
        if (directiveId == null || directiveId.isEmpty()) return;
        if (disposed) return;
        DirectiveInfo previous = pending.remove(directiveId);
        if (previous != null && previous.timeoutFuture != null) {
            previous.timeoutFuture.cancel(false);
        }
        DirectiveInfo info = new DirectiveInfo(directiveId, payload, System.currentTimeMillis());
        try {
            info.timeoutFuture = scheduler.schedule(
                    () -> onTimeoutInternal(directiveId),
                    ackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("[DirectiveTracker] " + pairId + " schedule failed (likely disposed): " + e.getMessage());
            return;
        }
        pending.put(directiveId, info);
        // 2026-05-24 (Q4 trace): promote registration to INFO so the inject_prompt
        // chain is visible in the default IDE log level — paired with the
        // markAcked log below to compute end-to-end latency without DEBUG.
        LOG.info("[INJECT_TRACE] DirectiveTracker.register"
                + " pair=" + pairId
                + " directiveId=" + directiveId
                + " pending=" + pending.size()
                + " timeoutMs=" + ackTimeoutMs);
    }

    /** Mark a directive as acknowledged. status is informational
     *  ("received" | "applied" | "failed"). Removes the directive from pending
     *  and cancels its timeout. No-op if directiveId is unknown. */
    public void markAcked(String directiveId, String status) {
        if (directiveId == null || directiveId.isEmpty()) return;
        DirectiveInfo info = pending.remove(directiveId);
        if (info == null) return;
        if (info.timeoutFuture != null) {
            info.timeoutFuture.cancel(false);
        }
        // 2026-05-24 (Q4 trace): promoted to INFO. Paired with register log so
        // tracing the chain only needs `[INJECT_TRACE]` grep on the IDE log.
        LOG.info("[INJECT_TRACE] DirectiveTracker.ack"
                + " pair=" + pairId
                + " directiveId=" + directiveId
                + " status=" + status
                + " elapsedMs=" + (System.currentTimeMillis() - info.registeredAt));
    }

    /** Number of directives currently waiting for ack. */
    public int pendingCount() {
        return pending.size();
    }

    /** Dispose the tracker. Pending directives are dropped (no timeout fires
     *  after dispose). Subsequent register/markAcked calls are no-ops. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (DirectiveInfo info : pending.values()) {
            if (info.timeoutFuture != null) info.timeoutFuture.cancel(false);
        }
        pending.clear();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void onTimeoutInternal(String directiveId) {
        DirectiveInfo info = pending.remove(directiveId);
        if (info == null) return;
        if (disposed) return;
        BiConsumer<String, JsonObject> handler = onTimeout;
        if (handler == null) {
            LOG.warn("[DirectiveTracker] " + pairId + " directive " + directiveId
                    + " timed out but no onTimeout handler wired");
            return;
        }
        try {
            handler.accept(directiveId, info.payload);
        } catch (Exception e) {
            LOG.warn("[DirectiveTracker] " + pairId + " onTimeout handler threw: " + e.getMessage());
        }
    }

    private static final class DirectiveInfo {
        final String directiveId;
        final JsonObject payload;
        final long registeredAt;
        ScheduledFuture<?> timeoutFuture;

        DirectiveInfo(String directiveId, JsonObject payload, long registeredAt) {
            this.directiveId = directiveId;
            this.payload = payload;
            this.registeredAt = registeredAt;
        }
    }
}
