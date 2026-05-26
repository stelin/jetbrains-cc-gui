package com.github.claudecodegui.session.pair.watcher;

import com.github.claudecodegui.session.pair.PairBudgetTracker;

/**
 * Contract State Machine v3 (2026-05-25): identifier facade over
 * {@link PairBudgetTracker} so the watcher pattern is uniform across
 * health / budget / rotation concerns.
 *
 * <p>{@code PairBudgetTracker} already owns the budget invariants
 * (token / step / duration / subagent caps + 80% warn + 100% pause), so
 * BudgetWatcher is a thin pass-through. Future evolutions that want to
 * decorate budget behaviour (e.g. add a per-step soft budget) plug in here
 * without touching the underlying tracker.
 */
public class BudgetWatcher {

    private final PairBudgetTracker tracker;

    public BudgetWatcher(PairBudgetTracker tracker) {
        this.tracker = tracker;
    }

    public PairBudgetTracker getTracker() {
        return tracker;
    }

    public boolean hasAnyLimit() {
        return tracker != null && tracker.hasAnyLimit();
    }

    public boolean shouldPause() {
        return tracker != null && tracker.shouldPause();
    }
}
