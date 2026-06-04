package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P4 test for the DN4 concurrency-ceiling clamp (coding-plan §14.1): the
 * configured {@code workflowMaxConcurrency} is bounded into {@code [1, HARD_CAP=3]}.
 * (The configured value itself is also clamped to [1,3] at the settings layer;
 * this guards the engine end regardless.)
 */
public class CeilingClampTest {

    @Test
    public void clampsConfiguredValueIntoOneToThree() {
        assertEquals(1, SupervisorWorkflowManager.clampCeiling(1));
        assertEquals(2, SupervisorWorkflowManager.clampCeiling(2));
        assertEquals(3, SupervisorWorkflowManager.clampCeiling(3));
        // above the hard cap → 3
        assertEquals(3, SupervisorWorkflowManager.clampCeiling(5));
        assertEquals(3, SupervisorWorkflowManager.clampCeiling(99));
        // below the floor → 1
        assertEquals(1, SupervisorWorkflowManager.clampCeiling(0));
        assertEquals(1, SupervisorWorkflowManager.clampCeiling(-4));
    }

    @Test
    public void hardCapIsThreeAndDefaultIsTwo() {
        assertEquals(3, SupervisorWorkflowManager.HARD_CAP);
        assertEquals(2, SupervisorWorkflowManager.DEFAULT_GLOBAL_CONCURRENCY);
    }
}
