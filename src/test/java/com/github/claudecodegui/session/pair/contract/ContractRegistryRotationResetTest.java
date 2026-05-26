package com.github.claudecodegui.session.pair.contract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Plan §14.2 / §17 RotationContractReset: rotation cancels SUPERVISOR-bound
 * contracts but keeps MAIN_AI-bound ones (main AI is still working).
 *
 * <p>This unit test exercises the cancel pattern used by
 * {@link com.github.claudecodegui.session.pair.rotation.RotationCoordinator}
 * directly on ContractRegistry, isolated from the full rotation pipeline.
 */
public class ContractRegistryRotationResetTest {

    private ContractRegistry registry;

    @Before
    public void setUp() {
        registry = new ContractRegistry("pair_rot_test");
    }

    @After
    public void tearDown() {
        if (registry != null) registry.dispose();
    }

    @Test
    public void rotationReset_cancelsSupervisorBoundOnly() {
        Contract mainContract = registry.issue(ContractIssueRequest.builder()
                .parentStepId("step_1")
                .type(ContractType.TASK_ASSIGNMENT)
                .assignedTo(ContractAssignee.MAIN_AI)
                .payloadJson("{}")
                .build());
        Contract supContract1 = registry.issue(ContractIssueRequest.builder()
                .parentStepId("step_1")
                .type(ContractType.DECISION_REQUEST)
                .assignedTo(ContractAssignee.SUPERVISOR)
                .payloadJson("{}")
                .replaceExisting(false)
                .build());
        Contract supContract2 = registry.issue(ContractIssueRequest.builder()
                .parentStepId("step_2")
                .type(ContractType.STATE_REPORT_REQUEST)
                .assignedTo(ContractAssignee.SUPERVISOR)
                .payloadJson("{}")
                .build());

        assertEquals(3, registry.openCount());

        // Simulate rotation: scan and cancel SUPERVISOR-bound.
        int cancelled = 0;
        for (Contract c : registry.getOpenContracts()) {
            if (c.assignedTo == ContractAssignee.SUPERVISOR) {
                registry.cancel(c.id, "supervisor rotation: test");
                cancelled++;
            }
        }

        assertEquals(2, cancelled);
        // Only the main AI contract remains open.
        assertEquals(1, registry.openCount());
        assertEquals(mainContract.id, registry.getOpenContracts().get(0).id);
        // Supervisor contracts are now in closed history with CANCELLED status.
        Contract sup1 = registry.findById(supContract1.id);
        Contract sup2 = registry.findById(supContract2.id);
        assertNotNull(sup1);
        assertNotNull(sup2);
        assertEquals(ContractStatus.CANCELLED, sup1.status);
        assertEquals(ContractStatus.CANCELLED, sup2.status);
        assertEquals(ContractStatus.OPEN, registry.findById(mainContract.id).status);
    }

    @Test
    public void rotationReset_emptyRegistry_noOp() {
        assertEquals(0, registry.openCount());
        for (Contract c : registry.getOpenContracts()) {
            if (c.assignedTo == ContractAssignee.SUPERVISOR) {
                registry.cancel(c.id, "noop");
            }
        }
        assertEquals(0, registry.openCount());
    }
}
