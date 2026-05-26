package com.github.claudecodegui.session.pair.watcher;

import com.github.claudecodegui.session.pair.HealthState;
import com.github.claudecodegui.session.pair.PairCoordinator;
import com.github.claudecodegui.session.pair.PairStatusPusher;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contract State Machine v3 (2026-05-25): extracted from SupervisorMonitor
 * to give "supervisor session health" its own owner.
 *
 * <p>Tracks consecutive tick / RPC failure count and transitions through
 * HEALTHY → DEGRADED → UNHEALTHY. On UNHEALTHY, requests a rotation via
 * {@link PairCoordinator#requestRotation()}.
 *
 * <p>Stateless from the outside — callers report success/failure events,
 * watcher decides the rest. Persistence (L2 metrics counters), status-pane
 * notification (PairStatusPusher), and rotation request (PairCoordinator)
 * are all routed automatically.
 *
 * <p>Thread-safe. Multiple recorders can call concurrently without locking;
 * counter is atomic and state transitions are CAS'd.
 */
public class HealthWatcher {

    private static final Logger LOG = Logger.getInstance(HealthWatcher.class);

    /** Consecutive failure count at which we mark UNHEALTHY + request rotation. */
    public static final int UNHEALTHY_THRESHOLD = 2;

    private final String pairId;
    private final PairCoordinator coordinator;
    private final PairStatusPusher statusPusher;
    private final L2Store l2Store;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<HealthState> state = new AtomicReference<>(HealthState.HEALTHY);

    public HealthWatcher(String pairId,
                         PairCoordinator coordinator,
                         PairStatusPusher statusPusher,
                         L2Store l2Store) {
        this.pairId = pairId;
        this.coordinator = coordinator;
        this.statusPusher = statusPusher;
        this.l2Store = l2Store;
    }

    public HealthState getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** Called by an event source when an operation completes successfully. */
    public void recordSuccess() {
        int prev = consecutiveFailures.getAndSet(0);
        if (prev > 0) {
            transition(HealthState.HEALTHY);
        }
    }

    /** Called by an event source when an operation fails. */
    public void recordFailure(String reason) {
        int f = consecutiveFailures.incrementAndGet();
        LOG.warn("[HealthWatcher] " + pairId + " failure #" + f + ": " + reason);
        HealthState target = (f >= UNHEALTHY_THRESHOLD) ? HealthState.UNHEALTHY : HealthState.DEGRADED;
        transition(target);
        if (target == HealthState.UNHEALTHY && coordinator != null) {
            coordinator.requestRotation();
        }
    }

    private void transition(HealthState target) {
        HealthState prev = state.getAndSet(target);
        if (prev == target) return;
        LOG.info("[HealthWatcher] " + pairId + " " + prev + " -> " + target);
        if (statusPusher != null) {
            try {
                statusPusher.onHealthChanged(prev, target);
            } catch (Exception e) {
                LOG.warn("[HealthWatcher] status push on transition failed: " + e.getMessage());
            }
        }
        if (l2Store != null) {
            try {
                l2Store.update(pairId, s -> {
                    if (s.metrics == null) s.metrics = new L2State.Metrics();
                    if (target == HealthState.DEGRADED) s.metrics.degradedCount += 1;
                    if (target == HealthState.UNHEALTHY) s.metrics.unhealthyCount += 1;
                    return s;
                });
            } catch (Exception e) {
                LOG.warn("[HealthWatcher] L2 metrics update failed: " + e.getMessage());
            }
        }
    }
}
