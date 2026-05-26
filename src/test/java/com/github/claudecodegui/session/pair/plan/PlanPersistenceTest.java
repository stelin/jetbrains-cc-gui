package com.github.claudecodegui.session.pair.plan;

import com.github.claudecodegui.session.pair.l2.L2State;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PlanPersistenceTest {

    @Test
    public void roundtrip_preservesAllFields() {
        List<PlanStep> steps = new ArrayList<>();
        PlanStep s = PlanStep.create("plan_x", 0, "build foo", PlanStep.StepOwner.MAIN_AI);
        s.status = PlanStep.StepStatus.DONE;
        s.completedAt = 1500L;
        s.attempts = 2;
        s.lastError = "transient";
        s.contractIds.add("ctr_a");
        s.filesChanged.add("a.java");
        steps.add(s);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", "claude-opus-4-7");
        Plan original = Plan.create("p1", steps, meta);
        original.state = Plan.PlanState.ACTIVE;
        original.subState = Plan.ActiveSubState.PENDING_DISCHARGE;
        original.currentStepIndex = 0;

        L2State.PersistedPlan persisted = PlanPersistence.toPersisted(original);
        Plan loaded = PlanPersistence.fromPersisted(persisted, "p1");

        assertEquals(original.id, loaded.id);
        assertEquals("p1", loaded.pairId);
        assertEquals(original.state, loaded.state);
        assertEquals(original.subState, loaded.subState);
        assertEquals(1, loaded.steps.size());
        assertEquals(PlanStep.StepStatus.DONE, loaded.steps.get(0).status);
        assertEquals(2, loaded.steps.get(0).attempts);
        assertEquals("transient", loaded.steps.get(0).lastError);
        assertEquals(1, loaded.steps.get(0).contractIds.size());
        assertEquals("claude-opus-4-7", loaded.metadata.get("model"));
    }

    @Test
    public void toPersisted_nullPlan_returnsNull() {
        assertNull(PlanPersistence.toPersisted(null));
    }

    @Test
    public void fromPersisted_nullInput_returnsNull() {
        assertNull(PlanPersistence.fromPersisted(null, "p"));
    }

    @Test
    public void unknownEnumStrings_areLenient() {
        L2State.PersistedPlan p = new L2State.PersistedPlan();
        p.id = "plan_q";
        p.state = "GARBAGE";
        p.subState = "ALSO_GARBAGE";
        L2State.PersistedPlanStep ps = new L2State.PersistedPlanStep();
        ps.id = "s";
        ps.owner = "WHO";
        ps.status = "INVALID";
        p.steps.add(ps);

        Plan loaded = PlanPersistence.fromPersisted(p, "pq");
        assertNotNull(loaded);
        assertNull(loaded.state);
        assertNull(loaded.subState);
        assertNull(loaded.steps.get(0).owner);
        // status defaults to TODO so the plan keeps moving
        assertEquals(PlanStep.StepStatus.TODO, loaded.steps.get(0).status);
    }
}
