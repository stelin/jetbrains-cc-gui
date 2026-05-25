package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;

import java.util.List;

/** Inspects an {@link L2State} to determine whether all plan steps have
 *  terminated (done | skipped | blocked). Classifies the outcome severity. */
public final class CompletionDetector {

    private CompletionDetector() { /* no instances */ }

    public enum Outcome {
        /** All steps done — no skips, no blocks. */
        FULL_SUCCESS,
        /** All steps terminal, but skip+block ratio < 30%. */
        MOSTLY_SUCCESS,
        /** All steps terminal, but skip+block ratio >= 30%. */
        PARTIAL_SUCCESS,
        /** Pair was paused (budget exceeded / C3 escalate) before terminal. */
        PAUSED,
        /** Steps still todo/in_progress — not complete yet. */
        IN_PROGRESS
    }

    /** True if every plan step has reached a terminal status. False if any
     *  step is still {@code todo} or {@code in_progress}, or if the plan is
     *  empty. */
    public static boolean isComplete(L2State state) {
        if (state == null || state.planProgress == null || state.planProgress.isEmpty()) {
            return false;
        }
        for (L2State.PlanProgressEntry e : state.planProgress) {
            if (!isTerminal(e.status)) return false;
        }
        return true;
    }

    /** Classify the outcome. Caller should pass the paused flag separately —
     *  L2 doesn't store it. */
    public static Outcome classify(L2State state, boolean paused) {
        if (paused) return Outcome.PAUSED;
        if (state == null || state.planProgress == null || state.planProgress.isEmpty()) {
            return Outcome.IN_PROGRESS;
        }
        List<L2State.PlanProgressEntry> steps = state.planProgress;
        int done = 0;
        int skippedOrBlocked = 0;
        for (L2State.PlanProgressEntry e : steps) {
            String s = e.status;
            if (!isTerminal(s)) return Outcome.IN_PROGRESS;
            if ("done".equals(s)) done++;
            else skippedOrBlocked++;
        }
        if (skippedOrBlocked == 0) return Outcome.FULL_SUCCESS;
        double skipRatio = (double) skippedOrBlocked / (double) (done + skippedOrBlocked);
        return skipRatio < 0.30 ? Outcome.MOSTLY_SUCCESS : Outcome.PARTIAL_SUCCESS;
    }

    private static boolean isTerminal(String status) {
        return "done".equals(status) || "skipped".equals(status) || "blocked".equals(status);
    }
}
