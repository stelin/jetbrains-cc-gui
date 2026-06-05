package com.github.claudecodegui.session.pair.guard;

import com.github.claudecodegui.session.pair.MainAIMonitor;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.contract.Contract;
import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.github.claudecodegui.session.pair.contract.ContractIssueRequest;
import com.github.claudecodegui.session.pair.contract.ContractRegistry;
import com.github.claudecodegui.session.pair.contract.ContractStatus;
import com.github.claudecodegui.session.pair.contract.ContractType;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Deadlock fix (2026-06-04): the watchdog patch. When the main AI is parked
 * waiting for the USER (AskUserQuestion / plan-approval / 待确认事项), the guard
 * must wake the SUPERVISOR with a DECISION_REQUEST instead of either abstaining
 * forever ({@code isAssigneeTurnInProgress} blind spot) or re-injecting a nudge
 * that would clobber the main AI's question.
 *
 * <p>Unlike {@link DeadlockGuardTest}, this test wires a real
 * {@link MainAIMonitor} onto the pair so the awaiting-user branch is exercised.
 * It is deterministic — no sleeps / no real deadline timers.
 */
public class DeadlockGuardAwaitingUserTest {

    private PairSession pair;
    private PlanStateMachine planSm;
    private ContractRegistry registry;
    private MainAIMonitor monitor;
    private DeadlockGuard guard;

    @Before
    public void setUp() {
        pair = new PairSession(
                "pair_dg_await_test",
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
        // l2Store / statusPusher null — the methods this test calls don't touch them.
        monitor = new MainAIMonitor(pair.getPairId(), null, null, null);
        pair.setPlanStateMachine(planSm);
        pair.setContractRegistry(registry);
        pair.setMainAIMonitor(monitor);
        planSm.start();
        guard = new DeadlockGuard(pair, planSm, registry);
        // tick() early-returns until the guard is armed; tickForTest() calls the
        // same gated tick(), so we must start() first.
        guard.start();
    }

    @After
    public void tearDown() {
        if (guard != null) guard.stop();
        if (registry != null) registry.dispose();
        if (pair != null) pair.markDisposed();
    }

    /**
     * Core deadlock fix: main AI parked on the user → guard wakes the
     * supervisor (DECISION_REQUEST) and leaves the main-AI contract untouched
     * (no R1 re-inject that would clobber the question).
     */
    @Test
    public void awaitingUser_wakesSupervisor_andDoesNotRetryMainAi() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        // LONG deadline so a passing test cannot be confused with the normal
        // R1-after-deadline path — the only thing that can act here is the
        // awaiting-user branch.
        Contract c = issueOpenContract(stepId, 600_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        // Main AI stopped and is waiting for the user.
        monitor.markAwaitingUser("ask_user", "确认要删库吗？");

        guard.tickForTest();

        // Main-AI contract is NOT retried — still OPEN, not EXPIRED_RETRIED.
        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);

        // A DECISION_REQUEST addressed to the SUPERVISOR was issued.
        Contract decisionReq = findOpenSupervisorDecisionRequest();
        assertNotNull("expected a DECISION_REQUEST for the supervisor", decisionReq);
        assertEquals(stepId, decisionReq.parentStepId);
        assertNotNull(decisionReq.payloadJson);
        assertTrue("payload should explain the awaiting-user wait",
                decisionReq.payloadJson.contains("等待用户")
                        || decisionReq.payloadJson.contains("main_ai_awaiting_user"));
    }

    /** Dedup: a still-parked main AI must not spawn a new DECISION_REQUEST every tick. */
    @Test
    public void awaitingUser_isDeduped_acrossTicks() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 600_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);
        monitor.markAwaitingUser("pending_confirmation", "本轮待确认事项: ...");

        guard.tickForTest();
        guard.tickForTest();
        guard.tickForTest();

        int supervisorRequests = 0;
        for (Contract open : registry.getOpenContracts()) {
            if (open.type == ContractType.DECISION_REQUEST
                    && open.assignedTo == ContractAssignee.SUPERVISOR) {
                supervisorRequests++;
            }
        }
        assertEquals("exactly one supervisor request for one stuck episode",
                1, supervisorRequests);
    }

    /**
     * Backward-compat: when the main AI is NOT awaiting the user and its turn is
     * not in progress, the awaiting-user branch must stay out of the way (no
     * supervisor DECISION_REQUEST gets fabricated on a healthy, before-deadline
     * contract).
     */
    @Test
    public void notAwaiting_beforeDeadline_noSupervisorRequest() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 600_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);
        // monitor present but no awaiting-user, no turn in progress, no activity.

        guard.tickForTest();

        assertEquals(ContractStatus.OPEN, registry.findById(c.id).status);
        assertNull("no supervisor request when the main AI is not blocked",
                findOpenSupervisorDecisionRequest());
    }

    /** Once the wait is cleared (user answered / new turn), the latch is gone. */
    @Test
    public void clearAwaitingUser_disarmsTheBranch() {
        planSm.onPlanCreated(makeSteps(1), null);
        String stepId = planSm.getCurrent().steps.get(0).id;
        Contract c = issueOpenContract(stepId, 600_000L);
        planSm.onContractIssued(c.id, stepId, ContractAssignee.MAIN_AI);

        monitor.markAwaitingUser("ask_user", "Q?");
        assertTrue(monitor.isAwaitingUser());
        monitor.clearAwaitingUser();

        guard.tickForTest();

        assertNull("cleared latch must not wake the supervisor",
                findOpenSupervisorDecisionRequest());
    }

    private Contract findOpenSupervisorDecisionRequest() {
        for (Contract open : registry.getOpenContracts()) {
            if (open.type == ContractType.DECISION_REQUEST
                    && open.assignedTo == ContractAssignee.SUPERVISOR) {
                return open;
            }
        }
        return null;
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
            steps.add(PlanStep.create("dg_await_test", i, "step " + i, PlanStep.StepOwner.MAIN_AI));
        }
        return steps;
    }
}
