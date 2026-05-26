package com.github.claudecodegui.session.pair.guard;

import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.contract.Contract;
import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.github.claudecodegui.session.pair.contract.ContractIssueRequest;
import com.github.claudecodegui.session.pair.contract.ContractRegistry;
import com.github.claudecodegui.session.pair.contract.ContractStatus;
import com.github.claudecodegui.session.pair.contract.ContractType;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeadlockGuardTest {

    private PairSession pair;
    private PlanStateMachine planSm;
    private ContractRegistry registry;
    private DeadlockGuard guard;

    @Before
    public void setUp() {
        // Legacy constructor — nulls for parts the guard doesn't touch.
        pair = new PairSession(
                "pair_dg_test",
                /*mainSessionId*/ null,
                /*agentId*/ "agent_x",
                /*agentName*/ "Test Agent",
                /*pairDir*/ null,
                /*planSnapshotPath*/ null,
                /*supervisorBridge*/ null,
                /*progressManager*/ null,
                /*agentDescription*/ "test",
                /*planContent*/ null,
                /*projectSpec*/ null,
                /*model*/ null
        );
        planSm = new PlanStateMachine(pair.getPairId());
        registry = new ContractRegistry(pair.getPairId());
        pair.setPlanStateMachine(planSm);
        pair.setContractRegistry(registry);
        planSm.start();
        guard = new DeadlockGuard(pair, planSm, registry);
    }

    @After
    public void tearDown() {
        if (guard != null) guard.stop();
        if (registry != null) registry.dispose();
        if (pair != null) pair.markDisposed();
    }

    @Test
    public void tick_planInInit_noAction() {
        // Plan not created yet.
        Contract c = issueOpenContract("step_x", 10L);
        // No PlanSM transition — state is null.
        guard.tickForTest();
        // Contract status unchanged.
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
    }

    @Test
    public void tick_planDone_noAction() {
        planSm.onPlanCreated(makeSteps(1), null);
        planSm.onStepCompleted(planSm.getCurrent().steps.get(0).id);
        assertEquals(Plan.PlanState.DONE, planSm.getCurrent().state);

        // Even with an open contract — plan is DONE, guard ignores.
        Contract c = issueOpenContract("step_x", 10L);
        sleep(50);
        guard.tickForTest();
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
    }

    @Test
    public void tick_executing_noAction() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 10L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);
        planSm.onTurnStarted(ContractAssignee.MAIN_AI);
        assertEquals(Plan.ActiveSubState.EXECUTING, planSm.getCurrent().subState);

        sleep(50);
        guard.tickForTest();
        // EXECUTING means a turn is in flight — registry not touched.
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
    }

    @Test
    public void tick_pendingDischargeBeforeDeadline_noAction() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        // Long deadline so we definitely haven't hit it.
        Contract c = issueOpenContract(stepId, 60_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        guard.tickForTest();
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
    }

    @Test
    public void tick_pendingDischargeAfterDeadline_triggersR1Retry() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 10L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);
        sleep(40);
        guard.tickForTest();

        // Original contract is now EXPIRED_RETRIED, new retry contract is OPEN.
        Contract orig = registry.findById(c.id);
        assertNotNull(orig);
        assertEquals(ContractStatus.EXPIRED_RETRIED, orig.status);
        List<Contract> open = registry.getOpenContracts();
        assertEquals(1, open.size());
        Contract retry = open.get(0);
        assertNotEquals(c.id, retry.id);
        assertEquals(c.id, retry.retryOf);
        assertEquals(1, retry.retryCount);
        assertEquals(ContractType.TASK_ASSIGNMENT, retry.type);
    }

    @Test
    public void tick_secondDeadline_triggersR2SystemNudge() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 10L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        // R1
        sleep(40);
        guard.tickForTest();
        Contract r1 = registry.getOpenContracts().get(0);
        assertEquals(1, r1.retryCount);

        // R2 — wait for R1 to expire too.
        sleep(40);
        guard.tickForTest();
        List<Contract> open = registry.getOpenContracts();
        assertEquals(1, open.size());
        Contract r2 = open.get(0);
        assertEquals(2, r2.retryCount);
        assertEquals(ContractType.SYSTEM_NUDGE, r2.type);
    }

    @Test
    public void tick_thirdDeadline_triggersR3EscalateAndDecisionRequest() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 10L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        sleep(40); guard.tickForTest();   // R1
        sleep(40); guard.tickForTest();   // R2
        sleep(40); guard.tickForTest();   // R3 escalate + DECISION_REQUEST

        // The MAIN_AI retry chain is escalated; no more main-AI contracts open.
        boolean foundMainAiEscalated = false;
        for (Contract closed : registry.getClosedContractsCopy()) {
            if (closed.status == ContractStatus.EXPIRED_ESCALATED
                    && closed.assignedTo == ContractAssignee.MAIN_AI) {
                foundMainAiEscalated = true;
                break;
            }
        }
        assertTrue("expected an EXPIRED_ESCALATED main-AI contract", foundMainAiEscalated);

        // DECISION_REQUEST contract is now open, addressed to SUPERVISOR.
        Contract decisionReq = null;
        for (Contract open : registry.getOpenContracts()) {
            if (open.type == ContractType.DECISION_REQUEST
                    && open.assignedTo == ContractAssignee.SUPERVISOR) {
                decisionReq = open;
                break;
            }
        }
        assertNotNull("expected a DECISION_REQUEST contract for supervisor", decisionReq);
        assertEquals(stepId, decisionReq.parentStepId);
        assertNotNull(decisionReq.payloadJson);
        // Payload should mention "决策" or "Pair 系统" so the supervisor knows
        // why it's been pulled in.
        assertTrue("payload should contain decision request marker",
                decisionReq.payloadJson.contains("Pair") || decisionReq.payloadJson.contains("决策"));
    }

    @Test
    public void onContractDeadline_isCallable() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 1_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        // Direct callback invocation (simulating ContractRegistry.deadlineCallback
        // firing in production). Plan is in PENDING_DISCHARGE so evaluate runs.
        // Idle is < deadline so nothing changes — but the method must not throw.
        guard.onContractDeadline(c);
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
    }

    private Contract issueOpenContract(String stepId, long deadlineMs) {
        return registry.issue(ContractIssueRequest.builder()
                .parentStepId(stepId)
                .type(ContractType.TASK_ASSIGNMENT)
                .assignedTo(ContractAssignee.MAIN_AI)
                .payloadJson("{}")
                .deadlineMs(deadlineMs)
                .build());
    }

    private List<PlanStep> makeSteps(int n) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(PlanStep.create("dg_test", i, "step " + i, PlanStep.StepOwner.MAIN_AI));
        }
        return steps;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
