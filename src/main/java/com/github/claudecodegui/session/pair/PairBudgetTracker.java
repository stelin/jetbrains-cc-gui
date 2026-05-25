package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.protocol.BudgetStatus;
import com.github.claudecodegui.session.pair.protocol.PairBudget;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks per-pair cumulative usage against a {@link PairBudget} cap.
 *  Provides 80% warn / 100% pause signals. Thread-safe. */
public class PairBudgetTracker {

    private static final Logger LOG = Logger.getInstance(PairBudgetTracker.class);

    private static final double WARN_THRESHOLD = 0.80;
    private static final double PAUSE_THRESHOLD = 1.00;

    private final String pairId;
    private final PairBudget budget;
    private final long startedAt;
    private final AtomicLong tokensUsed = new AtomicLong(0);
    private final AtomicInteger stepsCompleted = new AtomicInteger(0);
    private final AtomicInteger subagentCalls = new AtomicInteger(0);
    private final AtomicBoolean warnedAt80 = new AtomicBoolean(false);
    private final AtomicBoolean exceededReported = new AtomicBoolean(false);

    public PairBudgetTracker(String pairId, PairBudget budget) {
        this.pairId = pairId;
        this.budget = budget != null ? budget : new PairBudget();
        this.startedAt = System.currentTimeMillis();
    }

    public boolean hasAnyLimit() {
        return budget.hasAnyLimit();
    }

    public void addTokens(long delta) {
        if (delta > 0) tokensUsed.addAndGet(delta);
    }

    public void incrementSteps() {
        stepsCompleted.incrementAndGet();
    }

    public void incrementSubagentCalls() {
        subagentCalls.incrementAndGet();
    }

    public long getTokensUsed() { return tokensUsed.get(); }
    public int getStepsCompleted() { return stepsCompleted.get(); }
    public int getSubagentCalls() { return subagentCalls.get(); }
    public long getStartedAt() { return startedAt; }

    /** Compute current usage status. */
    public BudgetStatus check() {
        BudgetStatus s = new BudgetStatus();
        s.tokenRatio = ratio(tokensUsed.get(), budget.maxTokens);
        s.durationRatio = ratio(System.currentTimeMillis() - startedAt, budget.maxDurationMs);
        s.stepRatio = ratio(stepsCompleted.get(), budget.maxSteps);
        s.subagentRatio = ratio(subagentCalls.get(), budget.maxSubagentCalls);
        return s;
    }

    /** True the FIRST time any ratio crosses 80%. Subsequent calls return false
     *  even if still above 80%. Use to fire a single warn event per pair. */
    public boolean shouldWarn() {
        if (!budget.hasAnyLimit()) return false;
        if (warnedAt80.get()) return false;
        BudgetStatus s = check();
        if (s.anyAbove(WARN_THRESHOLD)) {
            return warnedAt80.compareAndSet(false, true);
        }
        return false;
    }

    /** True when any ratio is at-or-above 100%. Idempotent caller (the pause action
     *  itself is one-shot) should track its own "already paused" flag, or use
     *  {@link #markExceededReported()} for a built-in one-shot. */
    public boolean shouldPause() {
        if (!budget.hasAnyLimit()) return false;
        return check().anyAbove(PAUSE_THRESHOLD);
    }

    /** Returns true the FIRST time the pause threshold is observed reported via
     *  this method. Use to ensure the budget_exceeded event fires exactly once
     *  per pair lifetime. */
    public boolean markExceededReported() {
        return exceededReported.compareAndSet(false, true);
    }

    private static double ratio(long used, Long max) {
        if (max == null || max <= 0) return 0.0;
        return (double) used / (double) max;
    }

    private static double ratio(int used, Integer max) {
        if (max == null || max <= 0) return 0.0;
        return (double) used / (double) max;
    }
}
