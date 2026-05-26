package com.github.claudecodegui.session.pair.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlanTest {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    @Test
    public void getCurrentStep_noSteps_returnsNull() {
        Plan p = Plan.create("p1", new ArrayList<>(), null);
        assertNull(p.getCurrentStep());
    }

    @Test
    public void getCurrentStep_indexInBounds_returnsStep() {
        Plan p = makePlanWithSteps(3);
        p.currentStepIndex = 1;
        assertNotNull(p.getCurrentStep());
        assertEquals("step_" + p.id + "_1", p.getCurrentStep().id);
    }

    @Test
    public void getCurrentStep_indexOutOfBounds_returnsNull() {
        Plan p = makePlanWithSteps(2);
        p.currentStepIndex = 5;
        assertNull(p.getCurrentStep());
    }

    @Test
    public void isTerminal_doneAndAborted() {
        Plan p = makePlanWithSteps(1);
        p.state = Plan.PlanState.ACTIVE;
        assertFalse(p.isTerminal());
        p.state = Plan.PlanState.WAITING;
        assertFalse(p.isTerminal());
        p.state = Plan.PlanState.DONE;
        assertTrue(p.isTerminal());
        p.state = Plan.PlanState.ABORTED;
        assertTrue(p.isTerminal());
    }

    @Test
    public void allStepsDone_emptyList_false() {
        Plan p = Plan.create("p1", new ArrayList<>(), null);
        assertFalse(p.allStepsDone());
    }

    @Test
    public void allStepsDone_mixedStatuses() {
        Plan p = makePlanWithSteps(3);
        p.steps.get(0).status = PlanStep.StepStatus.DONE;
        p.steps.get(1).status = PlanStep.StepStatus.TODO;
        p.steps.get(2).status = PlanStep.StepStatus.DONE;
        assertFalse(p.allStepsDone());
        p.steps.get(1).status = PlanStep.StepStatus.SKIPPED;
        assertTrue(p.allStepsDone());
    }

    @Test
    public void gsonRoundtrip_preservesAllFields() {
        Plan original = makePlanWithSteps(2);
        original.state = Plan.PlanState.ACTIVE;
        original.subState = Plan.ActiveSubState.PENDING_DISCHARGE;
        original.currentStepIndex = 1;
        original.steps.get(0).status = PlanStep.StepStatus.DONE;
        original.steps.get(0).completedAt = 12345L;
        original.steps.get(0).filesChanged = new ArrayList<>(Arrays.asList("a.java", "b.java"));
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("model", "claude-opus-4-7");
        md.put("autonomy", "full");
        original.metadata = md;

        String json = GSON.toJson(original);
        Plan loaded = GSON.fromJson(json, Plan.class);

        assertEquals(original.id, loaded.id);
        assertEquals(original.pairId, loaded.pairId);
        assertEquals(original.state, loaded.state);
        assertEquals(original.subState, loaded.subState);
        assertEquals(original.currentStepIndex, loaded.currentStepIndex);
        assertEquals(original.createdAt, loaded.createdAt);
        assertEquals(original.steps.size(), loaded.steps.size());
        assertEquals(original.steps.get(0).status, loaded.steps.get(0).status);
        assertEquals(original.steps.get(0).completedAt, loaded.steps.get(0).completedAt);
        assertEquals(original.steps.get(0).filesChanged, loaded.steps.get(0).filesChanged);
        assertEquals("claude-opus-4-7", loaded.metadata.get("model"));
    }

    private Plan makePlanWithSteps(int n) {
        List<PlanStep> steps = new ArrayList<>();
        Plan p = Plan.create("p1", new ArrayList<>(), null);
        for (int i = 0; i < n; i++) {
            steps.add(PlanStep.create(p.id, i, "step " + i, PlanStep.StepOwner.MAIN_AI));
        }
        p.steps = steps;
        return p;
    }
}
