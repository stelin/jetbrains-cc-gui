package com.github.claudecodegui.session.pair.l2;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class L2StateV3SerializationTest {

    @Test
    public void initialState_isV3WithEmptyContracts() {
        L2State s = L2State.initial("pair_a");
        assertEquals(L2Schema.CURRENT_VERSION, s.schemaVersion);
        assertNotNull(s.openContracts);
        assertTrue(s.openContracts.isEmpty());
        assertNull(s.plan);
    }

    @Test
    public void roundtripWithPlanAndContracts_preservesFields() {
        L2State s = L2State.initial("pair_a");

        L2State.PersistedPlan plan = new L2State.PersistedPlan();
        plan.id = "plan_a_1";
        plan.state = "ACTIVE";
        plan.subState = "PENDING_DISCHARGE";
        plan.currentStepIndex = 2;
        plan.createdAt = 1000L;
        plan.lastTransitionAt = 2000L;
        plan.metadata.put("autonomy", "full");

        L2State.PersistedPlanStep step = new L2State.PersistedPlanStep();
        step.id = "step_a_0";
        step.index = 0;
        step.title = "step zero";
        step.owner = "MAIN_AI";
        step.status = "DONE";
        step.contractIds = new ArrayList<>();
        step.contractIds.add("ctr_x");
        step.createdAt = 100L;
        step.completedAt = 500L;
        step.attempts = 1;
        step.filesChanged.add("a.java");
        plan.steps.add(step);

        s.plan = plan;

        L2State.PersistedContract pc = new L2State.PersistedContract();
        pc.id = "ctr_x";
        pc.parentStepId = "step_a_0";
        pc.type = "TASK_ASSIGNMENT";
        pc.assignedTo = "MAIN_AI";
        pc.status = "OPEN";
        pc.issuedAt = 1500L;
        pc.deadlineMs = 600000L;
        pc.lastActivityAt = 1500L;
        pc.payloadJson = "{\"k\":\"v\"}";
        pc.retryCount = 0;
        pc.maxRetries = 2;
        L2State.PersistedContractEvent ev = new L2State.PersistedContractEvent();
        ev.ts = 1500L;
        ev.type = "ISSUED";
        ev.note = "first";
        pc.history.add(ev);
        s.openContracts.add(pc);

        String json = s.toJson().toString();
        L2State loaded = L2State.fromJson(json);

        assertEquals(3, loaded.schemaVersion);
        assertNotNull(loaded.plan);
        assertEquals("plan_a_1", loaded.plan.id);
        assertEquals("ACTIVE", loaded.plan.state);
        assertEquals("PENDING_DISCHARGE", loaded.plan.subState);
        assertEquals(2, loaded.plan.currentStepIndex);
        assertEquals(1, loaded.plan.steps.size());
        assertEquals("step zero", loaded.plan.steps.get(0).title);
        assertEquals("DONE", loaded.plan.steps.get(0).status);
        assertEquals(1, loaded.plan.steps.get(0).contractIds.size());
        assertEquals("full", loaded.plan.metadata.get("autonomy"));

        assertEquals(1, loaded.openContracts.size());
        L2State.PersistedContract loadedCtr = loaded.openContracts.get(0);
        assertEquals("ctr_x", loadedCtr.id);
        assertEquals("TASK_ASSIGNMENT", loadedCtr.type);
        assertEquals("OPEN", loadedCtr.status);
        assertEquals("MAIN_AI", loadedCtr.assignedTo);
        assertEquals("{\"k\":\"v\"}", loadedCtr.payloadJson);
        assertEquals(1, loadedCtr.history.size());
        assertEquals("ISSUED", loadedCtr.history.get(0).type);
        assertEquals("first", loadedCtr.history.get(0).note);
    }

    @Test
    public void roundtripWithNullPlan_remainsNull() {
        L2State s = L2State.initial("pair_b");
        // plan is already null by default
        String json = s.toJson().toString();
        L2State loaded = L2State.fromJson(json);
        assertNull(loaded.plan);
    }

    @Test
    public void deepCopy_preservesV3Fields() {
        L2State s = L2State.initial("pair_c");
        s.plan = new L2State.PersistedPlan();
        s.plan.id = "plan_c";
        s.plan.state = "INIT";
        L2State.PersistedContract c = new L2State.PersistedContract();
        c.id = "ctr_q";
        c.parentStepId = "x";
        c.type = "TASK_ASSIGNMENT";
        c.assignedTo = "MAIN_AI";
        c.status = "OPEN";
        s.openContracts.add(c);

        L2State copy = s.deepCopy();
        assertNotNull(copy.plan);
        assertEquals("plan_c", copy.plan.id);
        assertEquals(1, copy.openContracts.size());
        assertEquals("ctr_q", copy.openContracts.get(0).id);
        // Mutating copy doesn't touch original
        copy.openContracts.clear();
        assertEquals(1, s.openContracts.size());
    }
}
