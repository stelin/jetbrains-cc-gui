package com.github.claudecodegui.session.pair.rotation;

import com.github.claudecodegui.provider.claude.MainAIBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.pair.PairCoordinator;
import com.github.claudecodegui.session.pair.PairSession;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Phase 4 (2026-05-24): per-PairSessionManager scheduler that picks up
 * {@code requestRotation} flags and runs the rotation under the write lock.
 *
 * <p>Why a separate thread (not inside the monitor): the monitor runs each
 * tick holding the pair's read lock — it cannot upgrade to a write lock
 * without deadlocking. So the monitor marks "rotate needed" and exits its
 * tick; the decider observes the flag from an independent thread, acquires
 * the write lock cleanly, and calls {@link RotationCoordinator#execute}.
 *
 * <p>Lifecycle: started by {@code PairSessionManager} on construction;
 * stopped on dispose.
 */
public class RotationDecider {

    private static final Logger LOG = Logger.getInstance(RotationDecider.class);
    public static final long POLL_INTERVAL_MS = 1_000L;

    private final Supplier<Collection<PairSession>> pairsSupplier;
    private final RotationCoordinator coordinator;
    /**
     * 2026-05-24: optional main-AI rotation coordinator. When non-null and
     * {@code pair.getClaudeSession()} resolves, main-AI rotation requests
     * are dispatched alongside supervisor ones. Null in unit tests that
     * only exercise the supervisor path.
     */
    private final MainAIRotationCoordinator mainAICoordinator;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> handle;
    private volatile boolean stopped = false;

    public RotationDecider(Supplier<Collection<PairSession>> pairsSupplier, RotationCoordinator coordinator) {
        this(pairsSupplier, coordinator, null);
    }

    public RotationDecider(Supplier<Collection<PairSession>> pairsSupplier,
                           RotationCoordinator coordinator,
                           MainAIRotationCoordinator mainAICoordinator) {
        this.pairsSupplier = pairsSupplier;
        this.coordinator = coordinator;
        this.mainAICoordinator = mainAICoordinator;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rotation-decider");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    public synchronized void start() {
        if (stopped || handle != null) return;
        handle = scheduler.scheduleWithFixedDelay(
                this::tick, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("[RotationDecider] start interval=" + POLL_INTERVAL_MS + "ms");
    }

    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        if (handle != null) handle.cancel(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        LOG.info("[RotationDecider] stop");
    }

    private void tick() {
        if (stopped) return;
        try {
            for (PairSession pair : pairsSupplier.get()) {
                if (stopped) return;
                if (pair == null || pair.isDisposed()) continue;
                PairCoordinator coord = pair.getCoordinator();
                if (coord == null) continue;
                if (coord.takeRotationRequest()) {
                    final PairSession capturedPair = pair;
                    scheduler.submit(() -> runRotation(capturedPair, "auto"));
                }
                // 2026-05-24: main-AI rotation is independent of supervisor
                // rotation — both flags can be set within the same tick and
                // run on the same worker pool. Cooldown lives inside each
                // coordinator, so re-firing the same tick is a no-op there.
                if (mainAICoordinator != null && coord.takeMainAIRotationRequest()) {
                    final PairSession capturedPair = pair;
                    scheduler.submit(() -> runMainAIRotation(capturedPair, "auto"));
                }
            }
        } catch (Exception e) {
            LOG.warn("[RotationDecider] tick swallowed: " + e.getMessage());
        }
    }

    private RotationResult runRotation(PairSession pair, String reason) {
        PairCoordinator coord = pair.getCoordinator();
        if (coord == null) return RotationResult.aborted("no coordinator");
        boolean acquired;
        try {
            acquired = coord.enterRotation();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return RotationResult.aborted("interrupted");
        }
        if (!acquired) {
            // Re-queue for next tick; another rotation may be in-flight or
            // readers haven't released. Don't lose the request.
            coord.requestRotation();
            return RotationResult.aborted("could not acquire writeLock");
        }
        try {
            return coordinator.execute(pair, reason);
        } catch (Exception e) {
            LOG.error("[RotationDecider] execute threw: " + e.getMessage(), e);
            return RotationResult.failed("unhandled: " + e.getMessage(), 0);
        } finally {
            coord.exitRotation();
        }
    }

    /**
     * 2026-05-24: main-AI rotation runs without the pair-level write lock —
     * the swap itself is lazy (epoch rotation + staged prompt; new runtime
     * starts on next user send), so it doesn't need to be globally exclusive
     * with supervisor ticks. The coordinator's own busy / cooldown checks
     * keep it safe; failures simply re-queue via
     * {@link PairCoordinator#requestMainAIRotation()}.
     */
    private RotationResult runMainAIRotation(PairSession pair, String reason) {
        if (mainAICoordinator == null) {
            return RotationResult.aborted("main AI rotation not wired");
        }
        MainAIBridge bridge = pair.getMainAIBridge();
        if (bridge == null) {
            return RotationResult.aborted("main AI bridge not wired");
        }
        ClaudeSession session = pair.getClaudeSession();
        if (session == null) {
            return RotationResult.aborted("ClaudeSession not bound on pair " + pair.getPairId());
        }
        try {
            return mainAICoordinator.execute(pair, session, bridge, reason);
        } catch (Exception e) {
            LOG.error("[RotationDecider] main-AI execute threw: " + e.getMessage(), e);
            // Re-queue so the next tick can retry — the underlying failure may
            // be transient (e.g. busy session deferred us).
            PairCoordinator coord = pair.getCoordinator();
            if (coord != null) coord.requestMainAIRotation();
            return RotationResult.failed("unhandled: " + e.getMessage(), 0);
        }
    }
}
