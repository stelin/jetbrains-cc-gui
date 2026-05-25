package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan §9.1: completion classification.
 *
 * Outcome boundaries:
 *   - PAUSED        — paused flag wins regardless of plan state
 *   - IN_PROGRESS   — any step still todo/in_progress, OR plan empty/null
 *   - FULL_SUCCESS  — all done, zero skip/block
 *   - MOSTLY_SUCCESS — skip+block ratio < 30%
 *   - PARTIAL_SUCCESS — skip+block ratio >= 30%
 */
public class CompletionDetectorTest {

    private static L2State stateWithSteps(String... statuses) {
        L2State s = L2State.initial("p1");
        s.planProgress = new ArrayList<>();
        int idx = 1;
        for (String status : statuses) {
            L2State.PlanProgressEntry e = new L2State.PlanProgressEntry();
            e.step = idx++;
            e.status = status;
            s.planProgress.add(e);
        }
        return s;
    }

    @Test
    public void isComplete_nullOrEmptyPlan_isFalse() {
        assertFalse(CompletionDetector.isComplete(null));
        assertFalse(CompletionDetector.isComplete(L2State.initial("p1")));
    }

    @Test
    public void isComplete_anyInProgress_isFalse() {
        L2State s = stateWithSteps("done", "in_progress", "done");
        assertFalse(CompletionDetector.isComplete(s));
    }

    @Test
    public void isComplete_anyTodo_isFalse() {
        L2State s = stateWithSteps("done", "done", "todo");
        assertFalse(CompletionDetector.isComplete(s));
    }

    @Test
    public void isComplete_allTerminal_isTrue() {
        L2State s = stateWithSteps("done", "skipped", "blocked");
        assertTrue(CompletionDetector.isComplete(s));
    }

    @Test
    public void classify_pausedWinsRegardless() {
        L2State s = stateWithSteps("done", "done", "done");
        assertEquals(CompletionDetector.Outcome.PAUSED,
                CompletionDetector.classify(s, true));
    }

    @Test
    public void classify_inProgress_whenAnyNonTerminal() {
        L2State s = stateWithSteps("done", "todo", "done");
        assertEquals(CompletionDetector.Outcome.IN_PROGRESS,
                CompletionDetector.classify(s, false));
    }

    @Test
    public void classify_emptyPlan_isInProgress() {
        assertEquals(CompletionDetector.Outcome.IN_PROGRESS,
                CompletionDetector.classify(L2State.initial("p1"), false));
    }

    @Test
    public void classify_allDone_isFullSuccess() {
        L2State s = stateWithSteps("done", "done", "done", "done");
        assertEquals(CompletionDetector.Outcome.FULL_SUCCESS,
                CompletionDetector.classify(s, false));
    }

    @Test
    public void classify_oneSkipOutOfFive_isMostlySuccess() {
        // 1 / 5 = 20%, below the 30% threshold.
        L2State s = stateWithSteps("done", "done", "done", "done", "skipped");
        assertEquals(CompletionDetector.Outcome.MOSTLY_SUCCESS,
                CompletionDetector.classify(s, false));
    }

    @Test
    public void classify_twoSkipsOutOfFive_isPartialSuccess() {
        // 2 / 5 = 40%, at-or-above the 30% threshold.
        L2State s = stateWithSteps("done", "done", "done", "skipped", "blocked");
        assertEquals(CompletionDetector.Outcome.PARTIAL_SUCCESS,
                CompletionDetector.classify(s, false));
    }

    @Test
    public void classify_blockedAndSkippedBothCountAsNonDone() {
        // 3 / 6 = 50% — partial.
        L2State s = stateWithSteps("done", "done", "done", "skipped", "blocked", "blocked");
        assertEquals(CompletionDetector.Outcome.PARTIAL_SUCCESS,
                CompletionDetector.classify(s, false));
    }
}
