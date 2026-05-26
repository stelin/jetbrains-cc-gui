package com.github.claudecodegui.session.pair.watcher;

import com.github.claudecodegui.session.pair.HealthState;
import com.github.claudecodegui.session.pair.PairCoordinator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HealthWatcherTest {

    private PairCoordinator coordinator;
    private HealthWatcher watcher;

    @Before
    public void setUp() {
        coordinator = new PairCoordinator("hw_test");
        // PairStatusPusher and L2Store are nullable — test focuses on state transitions.
        watcher = new HealthWatcher("hw_test", coordinator, null, null);
    }

    @Test
    public void initialState_isHealthy() {
        assertEquals(HealthState.HEALTHY, watcher.getState());
        assertEquals(0, watcher.getConsecutiveFailures());
    }

    @Test
    public void singleFailure_movesToDegraded() {
        watcher.recordFailure("ipc error");
        assertEquals(HealthState.DEGRADED, watcher.getState());
        assertEquals(1, watcher.getConsecutiveFailures());
        assertFalse("rotation should NOT be requested on single failure",
                coordinator.takeRotationRequest());
    }

    @Test
    public void twoFailures_movesToUnhealthyAndRequestsRotation() {
        watcher.recordFailure("ipc error 1");
        watcher.recordFailure("ipc error 2");
        assertEquals(HealthState.UNHEALTHY, watcher.getState());
        assertEquals(2, watcher.getConsecutiveFailures());
        assertTrue("rotation should be requested at threshold",
                coordinator.takeRotationRequest());
    }

    @Test
    public void successAfterFailures_resetsToHealthy() {
        watcher.recordFailure("transient");
        assertEquals(HealthState.DEGRADED, watcher.getState());
        watcher.recordSuccess();
        assertEquals(HealthState.HEALTHY, watcher.getState());
        assertEquals(0, watcher.getConsecutiveFailures());
    }

    @Test
    public void successWithoutPriorFailures_noTransition() {
        // Idempotent — recordSuccess from HEALTHY stays HEALTHY.
        watcher.recordSuccess();
        watcher.recordSuccess();
        assertEquals(HealthState.HEALTHY, watcher.getState());
    }

    @Test
    public void threeFailures_staysUnhealthy() {
        watcher.recordFailure("f1");
        watcher.recordFailure("f2");
        watcher.recordFailure("f3");
        assertEquals(HealthState.UNHEALTHY, watcher.getState());
        assertEquals(3, watcher.getConsecutiveFailures());
    }
}
