package com.github.claudecodegui.session.pair.contract;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContractTest {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    @Test
    public void isOpen_openOrReceived() {
        Contract c = newContract();
        c.status = ContractStatus.OPEN;
        assertTrue(c.isOpen());
        c.status = ContractStatus.RECEIVED;
        assertTrue(c.isOpen());
        c.status = ContractStatus.DISCHARGED;
        assertFalse(c.isOpen());
        c.status = ContractStatus.CANCELLED;
        assertFalse(c.isOpen());
    }

    @Test
    public void isTerminal_dischargedCancelledEscalated() {
        Contract c = newContract();
        c.status = ContractStatus.OPEN;
        assertFalse(c.isTerminal());
        c.status = ContractStatus.DISCHARGED;
        assertTrue(c.isTerminal());
        c.status = ContractStatus.CANCELLED;
        assertTrue(c.isTerminal());
        c.status = ContractStatus.EXPIRED_ESCALATED;
        assertTrue(c.isTerminal());
        c.status = ContractStatus.EXPIRED_RETRIED;
        assertFalse(c.isTerminal());
    }

    @Test
    public void ageMs_idleMs() {
        Contract c = newContract();
        c.issuedAt = 1000L;
        c.lastActivityAt = 1500L;
        assertEquals(2000L, c.ageMs(3000L));
        assertEquals(1500L, c.idleMs(3000L));
    }

    @Test
    public void gsonRoundtrip_preservesAllFields() {
        Contract c = newContract();
        c.id = "ctr_x_123";
        c.parentStepId = "step_p_5";
        c.type = ContractType.TASK_ASSIGNMENT;
        c.assignedTo = ContractAssignee.MAIN_AI;
        c.status = ContractStatus.OPEN;
        c.issuedAt = 100L;
        c.lastActivityAt = 200L;
        c.deadlineMs = 600000L;
        c.payloadJson = "{\"k\":\"v\"}";
        c.retryCount = 1;
        c.maxRetries = 2;
        c.retryOf = "ctr_x_122";
        c.history.add(ContractEvent.of(ContractEvent.EventType.ISSUED, "initial"));
        c.history.add(ContractEvent.of(ContractEvent.EventType.RETRIED, "deadline"));

        String json = GSON.toJson(c);
        Contract loaded = GSON.fromJson(json, Contract.class);

        assertEquals(c.id, loaded.id);
        assertEquals(c.parentStepId, loaded.parentStepId);
        assertEquals(c.type, loaded.type);
        assertEquals(c.assignedTo, loaded.assignedTo);
        assertEquals(c.status, loaded.status);
        assertEquals(c.issuedAt, loaded.issuedAt);
        assertEquals(c.lastActivityAt, loaded.lastActivityAt);
        assertEquals(c.deadlineMs, loaded.deadlineMs);
        assertEquals(c.payloadJson, loaded.payloadJson);
        assertEquals(c.retryCount, loaded.retryCount);
        assertEquals(c.maxRetries, loaded.maxRetries);
        assertEquals(c.retryOf, loaded.retryOf);
        assertEquals(2, loaded.history.size());
        assertEquals(ContractEvent.EventType.ISSUED, loaded.history.get(0).type);
        assertEquals("initial", loaded.history.get(0).note);
        assertEquals(ContractEvent.EventType.RETRIED, loaded.history.get(1).type);
    }

    @Test
    public void create_factory_setsDefaults() {
        Contract c = Contract.create(
                "id1", "step1", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 5000L, 2, null, 0);
        assertNotNull(c.history);
        assertEquals(ContractStatus.OPEN, c.status);
        assertEquals(5000L, c.deadlineMs);
        assertEquals(2, c.maxRetries);
        assertEquals(0, c.retryCount);
        assertTrue(c.issuedAt > 0);
        assertEquals(c.issuedAt, c.lastActivityAt);
    }

    private Contract newContract() {
        return Contract.create("id", "step", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 1000L, 2, null, 0);
    }
}
