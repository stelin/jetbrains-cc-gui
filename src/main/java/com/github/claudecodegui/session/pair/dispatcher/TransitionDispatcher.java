package com.github.claudecodegui.session.pair.dispatcher;

import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateListener;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activates supervisor when the plan transitions to a state that needs a
 * decision but is not currently executing. The single trigger:
 * {@link Plan.ActiveSubState#PENDING_DECISION} — no open contract, plan
 * has remaining steps → supervisor must propose the next contract.
 *
 * <p>Uses a 30s debounce: a fresh transition cancels any in-flight wake.
 * This protects supervisor from being woken twice for the same idle window
 * (e.g. a contract dischanges and immediately a new one issues — net
 * subState ends up PENDING_DISCHARGE so wake is cancelled).
 *
 * <p>Stage B.1: dispatcher writes a log line on wake. The actual
 * "enqueue a DECISION_REQUEST contract for supervisor" emission is wired
 * in B.2 once ContractRegistry has the daemon-side delivery hook ready.
 */
public class TransitionDispatcher implements PlanStateListener {

    private static final Logger LOG = Logger.getInstance(TransitionDispatcher.class);

    public static final long PENDING_DECISION_WAKE_DELAY_MS = 30_000L;

    private final PairSession pair;
    private final PlanStateMachine planSm;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingWake = new AtomicReference<>();

    /** Callback fired when a wake should happen. Wiring point for B.2. Null OK. */
    private volatile Runnable supervisorWakeCallback;

    public TransitionDispatcher(PairSession pair, PlanStateMachine planSm) {
        this.pair = pair;
        this.planSm = planSm;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transition-dispatcher-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    public void setSupervisorWakeCallback(Runnable callback) {
        this.supervisorWakeCallback = callback;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        planSm.addListener(this);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        planSm.removeListener(this);
        cancelPendingWake();
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onStateChanged(Plan.PlanState oldState, Plan.ActiveSubState oldSub, Plan now) {
        if (!started.get()) return;
        if (pair.isDisposed()) return;
        if (now == null || now.state != Plan.PlanState.ACTIVE
                || now.subState != Plan.ActiveSubState.PENDING_DECISION) {
            cancelPendingWake();
            return;
        }
        scheduleWake(now);
    }

    private void scheduleWake(Plan plan) {
        cancelPendingWake();
        try {
            ScheduledFuture<?> f = scheduler.schedule(
                    () -> fireWake(plan.id),
                    PENDING_DECISION_WAKE_DELAY_MS,
                    TimeUnit.MILLISECONDS);
            pendingWake.set(f);
            LOG.info("[TransitionDispatcher] " + pair.getPairId()
                    + " scheduled supervisor wake in " + PENDING_DECISION_WAKE_DELAY_MS + "ms (plan=" + plan.id + ")");
        } catch (Exception e) {
            LOG.warn("[TransitionDispatcher] " + pair.getPairId() + " schedule failed: " + e.getMessage());
        }
    }

    private void cancelPendingWake() {
        ScheduledFuture<?> prev = pendingWake.getAndSet(null);
        if (prev != null) prev.cancel(false);
    }

    private void fireWake(String planId) {
        if (!started.get() || pair.isDisposed()) return;
        Plan current = planSm.getCurrent();
        if (current == null || !planId.equals(current.id)) {
            // Plan changed since we scheduled.
            return;
        }
        if (current.state != Plan.PlanState.ACTIVE
                || current.subState != Plan.ActiveSubState.PENDING_DECISION) {
            // Already moved on.
            return;
        }
        Runnable cb = supervisorWakeCallback;
        if (cb == null) {
            LOG.info("[TransitionDispatcher] " + pair.getPairId()
                    + " wake fired but no callback wired (Stage B.1 placeholder)");
            return;
        }
        try {
            cb.run();
        } catch (Exception e) {
            LOG.warn("[TransitionDispatcher] " + pair.getPairId() + " wake callback threw: " + e.getMessage());
        }
    }
}
