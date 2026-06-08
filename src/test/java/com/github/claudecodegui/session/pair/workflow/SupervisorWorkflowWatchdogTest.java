package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the node-liveness watchdog backoff (D35/D36):
 * {@link SupervisorWorkflowManager#backoffWindowMs} and
 * {@link SupervisorWorkflowManager#shouldRedispatch}. No IDE / pair needed.
 */
public class SupervisorWorkflowWatchdogTest {

    private static final long MIN = 60_000L;
    private static final long THRESHOLD = 15 * MIN;   // default 15min

    @Test
    public void backoffFollowsFixedLadderThenRepeatsHourly() {
        // Fixed ladder 15→20→30→40→60, hourly thereafter; independent of threshold.
        assertEquals(15 * MIN, SupervisorWorkflowManager.backoffWindowMs(0, THRESHOLD));
        assertEquals(20 * MIN, SupervisorWorkflowManager.backoffWindowMs(1, THRESHOLD));
        assertEquals(30 * MIN, SupervisorWorkflowManager.backoffWindowMs(2, THRESHOLD));
        assertEquals(40 * MIN, SupervisorWorkflowManager.backoffWindowMs(3, THRESHOLD));
        assertEquals(60 * MIN, SupervisorWorkflowManager.backoffWindowMs(4, THRESHOLD));
        assertEquals(60 * MIN, SupervisorWorkflowManager.backoffWindowMs(10, THRESHOLD));  // hourly holds
        // Ladder ignores the configured threshold entirely.
        assertEquals(15 * MIN, SupervisorWorkflowManager.backoffWindowMs(0, 99 * MIN));
    }

    @Test
    public void shouldRedispatchOnlyPastTheWindow() {
        long now = 1_000_000_000_000L;
        // attempt 0, window 15min: idle 14min → no; idle 16min → yes
        assertFalse(SupervisorWorkflowManager.shouldRedispatch(now, now - 14 * MIN, 0, THRESHOLD));
        assertTrue(SupervisorWorkflowManager.shouldRedispatch(now, now - 16 * MIN, 0, THRESHOLD));
        // attempt 1, window 20min: idle 16min → no longer enough; idle 21min → yes
        assertFalse(SupervisorWorkflowManager.shouldRedispatch(now, now - 16 * MIN, 1, THRESHOLD));
        assertTrue(SupervisorWorkflowManager.shouldRedispatch(now, now - 21 * MIN, 1, THRESHOLD));
    }

    @Test
    public void disabledWhenThresholdNonPositive() {
        long now = 1_000_000_000_000L;
        assertFalse(SupervisorWorkflowManager.shouldRedispatch(now, now - 999 * MIN, 0, 0L));
        assertFalse(SupervisorWorkflowManager.shouldRedispatch(now, now - 999 * MIN, 0, -1L));
    }
}
