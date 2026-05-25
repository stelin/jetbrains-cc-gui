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

/** Tracks outstanding inject_prompt directives. Two-stage timeout:
 *  - {@link #receivedTimeoutMs} (3s default): if the webview hasn't ack'd
 *    "received" by then, {@link #onReceivedTimeout} fires (ActionRouter
 *    retries the inject up to MAX_RECEIVED_RETRIES times before falling
 *    through to the slow timeout).
 *  - {@link #ackTimeoutMs} (5min default): if the webview hasn't ack'd
 *    "applied" by then, {@link #onTimeout} fires (publishes directive_lost
 *    so the supervisor can choose to retry or skip the step).
 *  Thread-safe. */
public class DirectiveTracker {

    private static final Logger LOG = Logger.getInstance(DirectiveTracker.class);

    public static final long DEFAULT_ACK_TIMEOUT_MS = 5 * 60 * 1000L;
    public static final long DEFAULT_RECEIVED_TIMEOUT_MS = 3_000L;

    private final String pairId;
    private final long ackTimeoutMs;
    private final long receivedTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final Map<String, DirectiveInfo> pending = new ConcurrentHashMap<>();
    /** Invoked when a directive times out (directiveId, originalPayload).
     *  Wire this to EventBus.publishDirectiveLost on PairSession setup. */
    private volatile BiConsumer<String, JsonObject> onTimeout;
    /** 2026-05-25: invoked when the webview doesn't ack "received" within
     *  {@link #receivedTimeoutMs}. ActionRouter wires this to retry the
     *  inject (covers the JBCef-bridge-drop intermittent-failure path). */
    private volatile BiConsumer<String, JsonObject> onReceivedTimeout;
    private volatile boolean disposed = false;

    public DirectiveTracker(String pairId) {
        this(pairId, DEFAULT_ACK_TIMEOUT_MS, DEFAULT_RECEIVED_TIMEOUT_MS);
    }

    public DirectiveTracker(String pairId, long ackTimeoutMs) {
        this(pairId, ackTimeoutMs, DEFAULT_RECEIVED_TIMEOUT_MS);
    }

    public DirectiveTracker(String pairId, long ackTimeoutMs, long receivedTimeoutMs) {
        this.pairId = pairId;
        this.ackTimeoutMs = ackTimeoutMs;
        this.receivedTimeoutMs = receivedTimeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "directive-tracker-" + pairId);
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnTimeout(BiConsumer<String, JsonObject> handler) {
        this.onTimeout = handler;
    }

    public void setOnReceivedTimeout(BiConsumer<String, JsonObject> handler) {
        this.onReceivedTimeout = handler;
    }

    /** Register a directive that was just dispatched. If a directive with the
     *  same id was already pending it is replaced (its previous timeout cancelled).
     *  Schedules BOTH the fast received-timeout and the slow applied-timeout. */
    public void registerDirective(String directiveId, JsonObject payload) {
        if (directiveId == null || directiveId.isEmpty()) return;
        if (disposed) return;
        DirectiveInfo previous = pending.remove(directiveId);
        if (previous != null) {
            if (previous.timeoutFuture != null) previous.timeoutFuture.cancel(false);
            if (previous.receivedTimeoutFuture != null) previous.receivedTimeoutFuture.cancel(false);
        }
        DirectiveInfo info = new DirectiveInfo(directiveId, payload, System.currentTimeMillis());
        try {
            info.timeoutFuture = scheduler.schedule(
                    () -> onTimeoutInternal(directiveId),
                    ackTimeoutMs, TimeUnit.MILLISECONDS);
            info.receivedTimeoutFuture = scheduler.schedule(
                    () -> onReceivedTimeoutInternal(directiveId),
                    receivedTimeoutMs, TimeUnit.MILLISECONDS);
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
                + " receivedTimeoutMs=" + receivedTimeoutMs
                + " ackTimeoutMs=" + ackTimeoutMs);
    }

    /** Mark a directive as acknowledged. status is informational
     *  ("received" | "applied" | "failed").
     *  - "received": cancels the fast received-timeout but the directive stays
     *    pending for the slow applied-timeout.
     *  - "applied" / "failed": removes the directive from pending and cancels
     *    both timeouts.
     *  No-op if directiveId is unknown. */
    public void markAcked(String directiveId, String status) {
        if (directiveId == null || directiveId.isEmpty()) return;
        boolean isReceived = "received".equals(status);
        long now = System.currentTimeMillis();
        if (isReceived) {
            DirectiveInfo info = pending.get(directiveId);
            if (info == null) return;
            if (info.receivedTimeoutFuture != null) {
                info.receivedTimeoutFuture.cancel(false);
                info.receivedTimeoutFuture = null;
            }
            info.receivedAt = now;
            LOG.info("[INJECT_TRACE] DirectiveTracker.ack"
                    + " pair=" + pairId
                    + " directiveId=" + directiveId
                    + " status=received"
                    + " elapsedMs=" + (now - info.registeredAt));
            return;
        }
        DirectiveInfo info = pending.remove(directiveId);
        if (info == null) return;
        if (info.timeoutFuture != null) info.timeoutFuture.cancel(false);
        if (info.receivedTimeoutFuture != null) info.receivedTimeoutFuture.cancel(false);
        LOG.info("[INJECT_TRACE] DirectiveTracker.ack"
                + " pair=" + pairId
                + " directiveId=" + directiveId
                + " status=" + status
                + " elapsedMs=" + (now - info.registeredAt));
    }

    /** Number of directives currently waiting for ack. */
    public int pendingCount() {
        return pending.size();
    }

    /** Re-arm the fast received-timeout without touching the slow applied-timeout.
     *  Called by {@link ActionRouter} after a retry-inject, so the next retry
     *  window starts from now. No-op if the directive is unknown or already
     *  received-ack'd. */
    public boolean rearmReceivedTimeout(String directiveId) {
        if (directiveId == null || directiveId.isEmpty()) return false;
        if (disposed) return false;
        DirectiveInfo info = pending.get(directiveId);
        if (info == null) return false;
        if (info.receivedAt > 0L) return false;
        if (info.receivedTimeoutFuture != null) info.receivedTimeoutFuture.cancel(false);
        try {
            info.receivedTimeoutFuture = scheduler.schedule(
                    () -> onReceivedTimeoutInternal(directiveId),
                    receivedTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            LOG.warn("[DirectiveTracker] " + pairId
                    + " rearmReceivedTimeout failed (likely disposed): " + e.getMessage());
            return false;
        }
    }

    /** Dispose the tracker. Pending directives are dropped (no timeout fires
     *  after dispose). Subsequent register/markAcked calls are no-ops. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (DirectiveInfo info : pending.values()) {
            if (info.timeoutFuture != null) info.timeoutFuture.cancel(false);
            if (info.receivedTimeoutFuture != null) info.receivedTimeoutFuture.cancel(false);
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
        if (info.receivedTimeoutFuture != null) info.receivedTimeoutFuture.cancel(false);
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

    private void onReceivedTimeoutInternal(String directiveId) {
        // Stay in pending — the slow applied-timeout still owns the directive.
        DirectiveInfo info = pending.get(directiveId);
        if (info == null) return;
        if (disposed) return;
        if (info.receivedAt > 0L) return; // received ack already arrived
        BiConsumer<String, JsonObject> handler = onReceivedTimeout;
        LOG.warn("[INJECT_TRACE] DirectiveTracker.receivedTimeout"
                + " pair=" + pairId
                + " directiveId=" + directiveId
                + " elapsedMs=" + (System.currentTimeMillis() - info.registeredAt));
        if (handler == null) return;
        try {
            handler.accept(directiveId, info.payload);
        } catch (Exception e) {
            LOG.warn("[DirectiveTracker] " + pairId
                    + " onReceivedTimeout handler threw: " + e.getMessage());
        }
    }

    private static final class DirectiveInfo {
        final String directiveId;
        final JsonObject payload;
        final long registeredAt;
        ScheduledFuture<?> timeoutFuture;
        ScheduledFuture<?> receivedTimeoutFuture;
        volatile long receivedAt = 0L;

        DirectiveInfo(String directiveId, JsonObject payload, long registeredAt) {
            this.directiveId = directiveId;
            this.payload = payload;
            this.registeredAt = registeredAt;
        }
    }
}
