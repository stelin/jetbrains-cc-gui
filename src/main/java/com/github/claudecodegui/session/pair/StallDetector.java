package com.github.claudecodegui.session.pair;

import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 6a (2026-05-24): single-shot timer that fires when a main-AI turn
 * has been in flight longer than a wall-clock threshold (default 5 min).
 *
 * <p>Usage:
 * <pre>
 *   stall.start();              // call from onTurnStart
 *   stall.stop();               // call from onTurnEnd; safe to call repeatedly
 * </pre>
 *
 * <p>On fire, invokes a caller-supplied {@link Runnable} (the
 * {@code MainAIMonitor} translates it into a status alert + telemetry).
 * Does NOT auto-cancel the turn — the user is expected to decide whether
 * to interrupt or wait. Phase 6b will add an opt-in auto-interrupt.
 *
 * <p>Thread model: owns a single-thread scheduler that auto-shuts down on
 * {@link #dispose()}. Concurrent start() calls cancel any prior pending fire
 * and re-arm, so the detector always represents the current turn.
 */
public class StallDetector {

    private static final Logger LOG = Logger.getInstance(StallDetector.class);
    public static final long DEFAULT_STALL_MS = 5 * 60_000L;

    private final String name;
    private final long stallMs;
    private final Runnable onStall;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private volatile boolean disposed = false;

    public StallDetector(String name, Runnable onStall) {
        this(name, DEFAULT_STALL_MS, onStall);
    }

    public StallDetector(String name, long stallMs, Runnable onStall) {
        this.name = name;
        this.stallMs = stallMs;
        this.onStall = onStall;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "stall-detector-" + name);
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    /** Arm (or re-arm) the detector. Cancels any pending fire before scheduling. */
    public void start() {
        if (disposed) return;
        ScheduledFuture<?> prev = pending.getAndSet(null);
        if (prev != null) prev.cancel(false);
        try {
            ScheduledFuture<?> next = scheduler.schedule(this::fire, stallMs, TimeUnit.MILLISECONDS);
            pending.set(next);
        } catch (Exception e) {
            // scheduler may be shutting down — ignore.
            LOG.debug("[StallDetector] " + name + " schedule failed: " + e.getMessage());
        }
    }

    /** Cancel any pending fire. Idempotent. */
    public void stop() {
        ScheduledFuture<?> prev = pending.getAndSet(null);
        if (prev != null) prev.cancel(false);
    }

    /** Release the underlying scheduler. The detector cannot be reused after this. */
    public synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        stop();
        scheduler.shutdownNow();
    }

    private void fire() {
        if (disposed) return;
        try {
            onStall.run();
        } catch (Exception e) {
            LOG.warn("[StallDetector] " + name + " onStall threw: " + e.getMessage());
        }
    }
}
