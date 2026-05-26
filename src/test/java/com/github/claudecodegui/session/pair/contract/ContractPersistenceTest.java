package com.github.claudecodegui.session.pair.contract;

import com.github.claudecodegui.session.pair.l2.L2State;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ContractPersistenceTest {

    @Test
    public void roundtrip_preservesAllFields() {
        Contract c = Contract.create(
                "ctr_x", "step_5", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{\"k\":1}", 600_000L, 2, null, 0);
        c.history.add(ContractEvent.of(ContractEvent.EventType.ISSUED, "first"));

        L2State.PersistedContract persisted = ContractPersistence.toPersisted(c);
        Contract loaded = ContractPersistence.fromPersisted(persisted);

        assertEquals("ctr_x", loaded.id);
        assertEquals("step_5", loaded.parentStepId);
        assertEquals(ContractType.TASK_ASSIGNMENT, loaded.type);
        assertEquals(ContractAssignee.MAIN_AI, loaded.assignedTo);
        assertEquals(ContractStatus.OPEN, loaded.status);
        assertEquals(c.issuedAt, loaded.issuedAt);
        assertEquals(600_000L, loaded.deadlineMs);
        assertEquals("{\"k\":1}", loaded.payloadJson);
        assertEquals(2, loaded.maxRetries);
        assertEquals(1, loaded.history.size());
        assertEquals(ContractEvent.EventType.ISSUED, loaded.history.get(0).type);
        assertEquals("first", loaded.history.get(0).note);
    }

    @Test
    public void listConversion_roundtrips() {
        List<Contract> contracts = new ArrayList<>();
        contracts.add(Contract.create("a", "s", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 1000L, 2, null, 0));
        contracts.add(Contract.create("b", "s", ContractType.DECISION_REQUEST,
                ContractAssignee.SUPERVISOR, "{}", 1000L, 2, null, 0));

        List<L2State.PersistedContract> persisted = ContractPersistence.toPersistedList(contracts);
        List<Contract> loaded = ContractPersistence.fromPersistedList(persisted);

        assertEquals(2, loaded.size());
        assertEquals("a", loaded.get(0).id);
        assertEquals(ContractType.DECISION_REQUEST, loaded.get(1).type);
    }

    @Test
    public void unknownEnumStrings_useSafeDefaults() {
        L2State.PersistedContract p = new L2State.PersistedContract();
        p.id = "x";
        p.parentStepId = "s";
        p.type = "GARBAGE";
        p.assignedTo = "BAD";
        p.status = "WRONG";

        Contract loaded = ContractPersistence.fromPersisted(p);
        assertNotNull(loaded);
        assertNull(loaded.type);
        assertNull(loaded.assignedTo);
        assertEquals(ContractStatus.OPEN, loaded.status);
    }

    @Test
    public void nullInputs_areNoOps() {
        assertNull(ContractPersistence.toPersisted(null));
        assertNull(ContractPersistence.fromPersisted(null));
        assertEquals(0, ContractPersistence.toPersistedList(null).size());
        assertEquals(0, ContractPersistence.fromPersistedList(null).size());
    }
}
