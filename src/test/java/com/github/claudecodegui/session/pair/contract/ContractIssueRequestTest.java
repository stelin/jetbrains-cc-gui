package com.github.claudecodegui.session.pair.contract;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ContractIssueRequestTest {

    @Test
    public void build_minimal_succeeds() {
        ContractIssueRequest r = ContractIssueRequest.builder()
                .parentStepId("step_1")
                .type(ContractType.TASK_ASSIGNMENT)
                .assignedTo(ContractAssignee.MAIN_AI)
                .payloadJson("{}")
                .build();
        assertEquals("step_1", r.parentStepId);
        assertEquals(ContractType.TASK_ASSIGNMENT, r.type);
        assertEquals(ContractAssignee.MAIN_AI, r.assignedTo);
        assertEquals("{}", r.payloadJson);
        assertEquals(0L, r.deadlineMs);
        assertTrue(r.replaceExisting);
        assertEquals(0, r.retryCount);
        assertEquals(ContractRegistry.DEFAULT_MAX_RETRIES, r.maxRetries);
    }

    @Test
    public void build_missingParentStepId_throws() {
        try {
            ContractIssueRequest.builder()
                    .type(ContractType.TASK_ASSIGNMENT)
                    .assignedTo(ContractAssignee.MAIN_AI)
                    .payloadJson("{}")
                    .build();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("parentStepId"));
        }
    }

    @Test
    public void build_missingType_throws() {
        try {
            ContractIssueRequest.builder()
                    .parentStepId("s")
                    .assignedTo(ContractAssignee.MAIN_AI)
                    .payloadJson("{}")
                    .build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("type"));
        }
    }

    @Test
    public void build_missingPayloadJson_throws() {
        try {
            ContractIssueRequest.builder()
                    .parentStepId("s")
                    .type(ContractType.TASK_ASSIGNMENT)
                    .assignedTo(ContractAssignee.MAIN_AI)
                    .build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("payloadJson"));
        }
    }

    @Test
    public void build_negativeRetryCount_throws() {
        try {
            ContractIssueRequest.builder()
                    .parentStepId("s")
                    .type(ContractType.TASK_ASSIGNMENT)
                    .assignedTo(ContractAssignee.MAIN_AI)
                    .payloadJson("{}")
                    .retryCount(-1)
                    .build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("retryCount"));
        }
    }
}
