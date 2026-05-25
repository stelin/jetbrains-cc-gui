package com.github.claudecodegui.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Batches supervisor SDK messages so a single 30s tick (or a remote-mode SSE
 * burst) doesn't post dozens of {@code invokeLater(callJavaScript)} calls to
 * the EDT. Without this throttle the JCEF IPC saturates, the webview's RAF /
 * heartbeat scheduling stalls, and {@code WebviewWatchdog} reloads the
 * webview — which wipes the supervisor pane (React {@code selected = []}).
 *
 * <p>The batcher buffers raw {@code [SUPERVISOR_MSG]} envelopes; flushes
 * either after {@link #FLUSH_DELAY_MS} of idle or when the queue hits
 * {@link #MAX_QUEUE}. Each flush emits a single JSON array delivered via the
 * caller-supplied {@code sendJsonArray} consumer (typically a wrapper that
 * calls {@code window.onSupervisorMessageBatch}).
 */
public class SupervisorMessageBatcher {

    private static final Logger LOG = Logger.getInstance(SupervisorMessageBatcher.class);
    /** Idle delay before flushing the buffer (ms). Below human-perceptible. */
    private static final long FLUSH_DELAY_MS = 30L;
    /** Hard cap so a runaway burst flushes immediately instead of bloating memory. */
    private static final int MAX_QUEUE = 64;

    private final Gson gson;
    private final Consumer<String> sendJsonArray;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final List<JsonObject> pending = new ArrayList<>();
    private ScheduledFuture<?> flushFuture;
    private volatile boolean disposed = false;

    public SupervisorMessageBatcher(Gson gson, Consumer<String> sendJsonArray) {
        this.gson = gson;
        this.sendJsonArray = sendJsonArray;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "supervisor-batcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void enqueue(JsonObject envelope) {
        if (disposed || envelope == null) return;
        boolean flushNow = false;
        synchronized (lock) {
            pending.add(envelope);
            if (pending.size() >= MAX_QUEUE) {
                flushNow = true;
                cancelScheduledFlushLocked();
            } else if (flushFuture == null) {
                try {
                    flushFuture = scheduler.schedule(this::flush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Scheduler likely shut down — fall back to immediate flush.
                    flushNow = true;
                }
            }
        }
        if (flushNow) flush();
    }

    private void cancelScheduledFlushLocked() {
        if (flushFuture != null) {
            flushFuture.cancel(false);
            flushFuture = null;
        }
    }

    private void flush() {
        List<JsonObject> snapshot;
        synchronized (lock) {
            flushFuture = null;
            if (pending.isEmpty()) return;
            snapshot = new ArrayList<>(pending);
            pending.clear();
        }
        try {
            JsonArray arr = new JsonArray();
            for (JsonObject obj : snapshot) arr.add(obj);
            sendJsonArray.accept(gson.toJson(arr));
        } catch (Exception e) {
            LOG.warn("[SupervisorMessageBatcher] flush failed: " + e.getMessage());
        }
    }

    /** Flush any remaining envelopes synchronously, then stop the scheduler. */
    public void shutdown() {
        if (disposed) return;
        disposed = true;
        try { flush(); } catch (Exception ignore) { /* best-effort */ }
        scheduler.shutdownNow();
    }
}
