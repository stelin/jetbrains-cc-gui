package com.github.claudecodegui.session.pair;

import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Phase 1 (2026-05-23): per-pair concurrency coordinator.
 *
 * <p>Reads (main AI turn / supervisor monitor tick) share a read lock so they
 * can run concurrently. Writes (rotation, in Phase 4+) take an exclusive
 * write lock — the lock is fair + write-preferring so a rotation request
 * does not starve behind new reader churn.
 *
 * <p>Rationale: main-AI turn and supervisor tick are logically independent
 * (the only data flow between them is via {@link EventCollector}, which is
 * already thread-safe). The single thing that must be globally exclusive is
 * a session rotation, which atomically swaps the underlying supervisor /
 * main-AI session. That's what the write lock protects.
 *
 * <p>Phase 1 does not yet implement rotation, but the coordinator is wired in
 * so the lock discipline can be tested. Monitor and event sources acquire the
 * read lock in this phase already.
 */
public class PairCoordinator {

    private static final Logger LOG = Logger.getInstance(PairCoordinator.class);

    /**
     * Contract State Machine v3 (2026-05-25): {@code PLAN_TRANSITIONING} added
     * for observability — surfaces "PlanStateMachine is mid-transition" in
     * status snapshots. It does NOT take the write lock (PlanStateMachine has
     * its own internal synchronized methods); it's a display-only state.
     */
    public enum State { IDLE, MAIN_TURN, TICK, ROTATING, PLAN_TRANSITIONING }

    /** Default 60s upper bound on rotation waiting for readers. */
    public static final long DEFAULT_ROTATION_WAIT_SEC = 60;

    /** Default 60s upper bound on a normal op waiting for an in-progress rotation. */
    public static final long DEFAULT_NORMAL_OP_WAIT_SEC = 60;

    private final String pairId;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean rotateRequested = new AtomicBoolean(false);
    /**
     * Phase 6b-auto (2026-05-24): main-AI rotation request flag, distinct
     * from {@link #rotateRequested} (which is supervisor-only). The decider
     * polls both flags every tick. Kept on PairCoordinator (not MainAIMonitor)
     * because the decider already iterates pairs and the API surface stays
     * symmetric with supervisor.
     */
    private final AtomicBoolean mainAIRotateRequested = new AtomicBoolean(false);

    public PairCoordinator(String pairId) {
        this.pairId = pairId;
    }

    public State getState() {
        return state.get();
    }

    public boolean isRotating() {
        return state.get() == State.ROTATING;
    }

    /**
     * Acquire the read lock representing a normal (non-rotation) operation.
     * Throws {@link IllegalStateException} if the lock cannot be obtained
     * within the timeout — typically because a rotation is in progress and
     * not completing.
     *
     * <p>Caller MUST call {@link #exitNormalOp()} in a finally block.
     */
    public void enterNormalOp(State op) throws InterruptedException {
        if (op == null || op == State.IDLE || op == State.ROTATING) {
            throw new IllegalArgumentException("invalid normal-op state: " + op);
        }
        boolean acquired = rwLock.readLock().tryLock(DEFAULT_NORMAL_OP_WAIT_SEC, TimeUnit.SECONDS);
        if (!acquired) {
            throw new IllegalStateException(
                    "PairCoordinator[" + pairId + "] readLock timeout — rotation may be stuck");
        }
        // best-effort state tracking for UI; CAS so we don't clobber overlapping ops
        state.compareAndSet(State.IDLE, op);
    }

    public void exitNormalOp() {
        // Reset to IDLE only if we're still in the same op state. Multi-reader
        // case: don't reset while another reader is still running.
        if (rwLock.getReadHoldCount() == 1) {
            // last reader on this thread — try to flip back to IDLE
            state.compareAndSet(state.get(), State.IDLE);
        }
        rwLock.readLock().unlock();
    }

    /**
     * Try to acquire the write lock for a rotation. Returns true if obtained
     * within {@link #DEFAULT_ROTATION_WAIT_SEC}, false otherwise (caller
     * decides whether to retry or escalate).
     *
     * <p>On success the caller MUST call {@link #exitRotation()} in finally.
     */
    public boolean enterRotation() throws InterruptedException {
        boolean ok = rwLock.writeLock().tryLock(DEFAULT_ROTATION_WAIT_SEC, TimeUnit.SECONDS);
        if (!ok) {
            LOG.warn("[PairCoordinator] " + pairId + " enterRotation timeout");
            return false;
        }
        state.set(State.ROTATING);
        return true;
    }

    public void exitRotation() {
        state.set(State.IDLE);
        rwLock.writeLock().unlock();
    }

    /** Mark "rotation needed" — observed by an external decider, never escalated from inside a read lock. */
    public void requestRotation() {
        rotateRequested.set(true);
    }

    /** Atomically read-and-clear the rotation request flag. */
    public boolean takeRotationRequest() {
        return rotateRequested.compareAndSet(true, false);
    }

    public boolean isRotationRequested() {
        return rotateRequested.get();
    }

    /**
     * 2026-05-24: mark "main-AI rotation needed" — observed by
     * {@code RotationDecider} alongside the supervisor request.
     */
    public void requestMainAIRotation() {
        mainAIRotateRequested.set(true);
    }

    public boolean takeMainAIRotationRequest() {
        return mainAIRotateRequested.compareAndSet(true, false);
    }

    public boolean isMainAIRotationRequested() {
        return mainAIRotateRequested.get();
    }
}
