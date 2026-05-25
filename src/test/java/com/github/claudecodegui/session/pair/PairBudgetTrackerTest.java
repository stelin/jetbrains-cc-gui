package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.protocol.BudgetStatus;
import com.github.claudecodegui.session.pair.protocol.PairBudget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan §9.1: budget warn / pause / counter increment invariants.
 *
 * Covers the failure modes that broke earlier hand-rolled tests:
 *   - shouldWarn must be one-shot (idempotency)
 *   - thresholds use any-above semantics (one ratio above warn is enough)
 *   - no budget set → never warn / pause (zero-config pairs untouched)
 */
public class PairBudgetTrackerTest {

    private static PairBudget budgetOf(Long maxTokens, Long maxDurationMs,
                                       Integer maxSteps, Integer maxSubagentCalls) {
        PairBudget b = new PairBudget();
        b.maxTokens = maxTokens;
        b.maxDurationMs = maxDurationMs;
        b.maxSteps = maxSteps;
        b.maxSubagentCalls = maxSubagentCalls;
        return b;
    }

    @Test
    public void hasAnyLimit_falseForEmptyBudget() {
        PairBudgetTracker t = new PairBudgetTracker("p1", new PairBudget());
        assertFalse(t.hasAnyLimit());
        assertFalse(t.shouldWarn());
        assertFalse(t.shouldPause());
    }

    @Test
    public void counters_addAndIncrement_areReadable() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        t.addTokens(400);
        t.addTokens(100);
        t.incrementSteps();
        t.incrementSteps();
        t.incrementSubagentCalls();
        assertEquals(500, t.getTokensUsed());
        assertEquals(2, t.getStepsCompleted());
        assertEquals(1, t.getSubagentCalls());
    }

    @Test
    public void addTokens_negativeDeltaIgnored() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        t.addTokens(100);
        t.addTokens(-50);
        assertEquals(100, t.getTokensUsed());
    }

    @Test
    public void shouldWarn_firesOnceAtEightyPercent() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        t.addTokens(800);
        assertTrue("first call past 80% warns", t.shouldWarn());
        assertFalse("second call does not re-warn", t.shouldWarn());
    }

    @Test
    public void shouldWarn_belowEightyPercent_noWarn() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        t.addTokens(700);
        assertFalse(t.shouldWarn());
    }

    @Test
    public void shouldPause_atOrAboveHundredPercent() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        t.addTokens(1000);
        assertTrue(t.shouldPause());
        t.addTokens(500);
        assertTrue(t.shouldPause());
    }

    @Test
    public void markExceededReported_isOneShot() {
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(1000L, null, null, null));
        assertTrue(t.markExceededReported());
        assertFalse(t.markExceededReported());
    }

    @Test
    public void check_anyDimensionTrips_evenWhenOthersUnset() {
        // Only subagent limit set; subagent calls past 80% should warn.
        PairBudgetTracker t = new PairBudgetTracker("p1",
                budgetOf(null, null, null, 10));
        for (int i = 0; i < 8; i++) t.incrementSubagentCalls();
        BudgetStatus s = t.check();
        assertEquals(0.0, s.tokenRatio, 0.0001);
        assertEquals(0.8, s.subagentRatio, 0.0001);
        assertTrue(t.shouldWarn());
    }
}
