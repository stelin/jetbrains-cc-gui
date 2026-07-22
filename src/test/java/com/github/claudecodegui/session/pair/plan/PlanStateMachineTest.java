package com.github.claudecodegui.session.pair.plan;

import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlanStateMachineTest {

    private PlanStateMachine sm;

    @Before
    public void setUp() {
        sm = new PlanStateMachine("p1");
        sm.start();
    }

    @Test
    public void initState_isNull() {
        assertNull(sm.getCurrent());
    }

    @Test
    public void planCreated_movesToActivePendingDecision() {
        sm.onPlanCreated(makeSteps(3), null);
        Plan p = sm.getCurrent();
        assertNotNull(p);
        assertEquals(Plan.PlanState.ACTIVE, p.state);
        assertEquals(Plan.ActiveSubState.PENDING_DECISION, p.subState);
        assertEquals(3, p.steps.size());
    }

    @Test
    public void contractIssued_movesPendingDecisionToPendingDischarge() {
        sm.onPlanCreated(makeSteps(2), null);
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI);
        assertEquals(Plan.ActiveSubState.PENDING_DISCHARGE, sm.getCurrent().subState);
        assertTrue(sm.getCurrent().steps.get(0).contractIds.contains("c1"));
    }

    @Test
    public void turnStarted_movesPendingDischargeToExecuting() {
        sm.onPlanCreated(makeSteps(2), null);
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        assertEquals(Plan.ActiveSubState.EXECUTING, sm.getCurrent().subState);
        assertEquals(PlanStep.StepStatus.IN_PROGRESS, sm.getCurrent().steps.get(0).status);
        assertEquals(1, sm.getCurrent().steps.get(0).attempts);
    }

    @Test
    public void turnEnded_withOpenContracts_movesExecutingToPendingDischarge() {
        sm.onPlanCreated(makeSteps(2), null);
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        sm.onTurnEnded(ContractAssignee.MAIN_AI, true);
        assertEquals(Plan.ActiveSubState.PENDING_DISCHARGE, sm.getCurrent().subState);
    }

    @Test
    public void turnEnded_withoutOpenContracts_movesExecutingToPendingDecision() {
        sm.onPlanCreated(makeSteps(2), null);
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        sm.onTurnEnded(ContractAssignee.MAIN_AI, false);
        assertEquals(Plan.ActiveSubState.PENDING_DECISION, sm.getCurrent().subState);
    }

    @Test
    public void stepCompleted_lastStep_movesToDone() {
        sm.onPlanCreated(makeSteps(2), null);
        sm.onStepCompleted(sm.getCurrent().steps.get(0).id);
        assertEquals(Plan.PlanState.ACTIVE, sm.getCurrent().state);
        sm.onStepCompleted(sm.getCurrent().steps.get(1).id);
        assertEquals(Plan.PlanState.DONE, sm.getCurrent().state);
        assertNull(sm.getCurrent().subState);
    }

    @Test
    public void escalateToHuman_movesToWaiting() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onEscalatedToHuman("LLM cannot decide");
        assertEquals(Plan.PlanState.WAITING, sm.getCurrent().state);
        assertNull(sm.getCurrent().subState);
        assertEquals("LLM cannot decide", sm.getCurrent().metadata.get("escalationReason"));
    }

    @Test
    public void humanResumed_movesWaitingToActive() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onEscalatedToHuman("test");
        sm.onHumanResumed();
        assertEquals(Plan.PlanState.ACTIVE, sm.getCurrent().state);
        assertNull(sm.getCurrent().metadata.get("escalationReason"));
    }

    @Test
    public void userCancel_fromActive_movesToAborted() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onUserCancel("user pressed stop");
        assertEquals(Plan.PlanState.ABORTED, sm.getCurrent().state);
        assertEquals("user pressed stop", sm.getCurrent().metadata.get("abortReason"));
    }

    @Test
    public void userCancel_fromWaiting_movesToAborted() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onEscalatedToHuman("test");
        sm.onUserCancel(null);
        assertEquals(Plan.PlanState.ABORTED, sm.getCurrent().state);
    }

    @Test
    public void planReplaced_clearsPlan() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onPlanReplaced();
        assertNull(sm.getCurrent());
    }

    @Test
    public void terminalState_ignoresFurtherTransitions() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onUserCancel(null);
        Plan beforeNoop = sm.getCurrent();
        sm.onContractIssued("c1", beforeNoop.steps.get(0).id, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        assertEquals(Plan.PlanState.ABORTED, sm.getCurrent().state);
        assertNull(sm.getCurrent().subState);
    }

    @Test
    public void listener_firesOnEveryStateChange() {
        AtomicInteger count = new AtomicInteger();
        sm.addListener((oldState, oldSub, now) -> count.incrementAndGet());

        sm.onPlanCreated(makeSteps(1), null);         // 1
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI); // 2
        sm.onTurnStarted(ContractAssignee.MAIN_AI);                  // 3
        sm.onTurnEnded(ContractAssignee.MAIN_AI, true);              // 4
        sm.onStepCompleted(stepId);                                  // 5 (also goes to DONE)
        assertEquals(5, count.get());
    }

    @Test
    public void listener_removeStopsNotifications() {
        AtomicInteger count = new AtomicInteger();
        PlanStateListener l = (oldState, oldSub, now) -> count.incrementAndGet();
        sm.addListener(l);
        sm.onPlanCreated(makeSteps(1), null);
        assertEquals(1, count.get());
        sm.removeListener(l);
        sm.onContractIssued("c1", sm.getCurrent().steps.get(0).id, ContractAssignee.MAIN_AI);
        assertEquals(1, count.get());
    }

    @Test
    public void restore_loadsPlanWithoutFiringListeners() {
        AtomicInteger count = new AtomicInteger();
        sm.addListener((oldState, oldSub, now) -> count.incrementAndGet());

        Plan p = new Plan();
        p.id = "plan_external";
        p.pairId = "p1";
        p.state = Plan.PlanState.ACTIVE;
        p.subState = Plan.ActiveSubState.PENDING_DISCHARGE;
        p.steps = new ArrayList<>();
        sm.restore(p);

        assertEquals(p, sm.getCurrent());
        assertEquals(0, count.get());
    }

    @Test(timeout = 5_000)
    public void concurrent_callsDoNotDeadlock() throws InterruptedException {
        sm.onPlanCreated(makeSteps(5), null);
        List<String> stepIds = new ArrayList<>();
        for (PlanStep s : sm.getCurrent().steps) stepIds.add(s.id);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    sm.onContractIssued("c1_" + i, stepIds.get(i % stepIds.size()), ContractAssignee.MAIN_AI);
                }
            } catch (InterruptedException ignored) {
            } finally { done.countDown(); }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    sm.onTurnStarted(ContractAssignee.MAIN_AI);
                    sm.onTurnEnded(ContractAssignee.MAIN_AI, i % 2 == 0);
                }
            } catch (InterruptedException ignored) {
            } finally { done.countDown(); }
        });
        t1.start();
        t2.start();
        start.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNotNull(sm.getCurrent());
        assertEquals(Plan.PlanState.ACTIVE, sm.getCurrent().state);
    }

    @Test
    public void contractIssued_inExecutingState_doesNotChangeSubState() {
        sm.onPlanCreated(makeSteps(1), null);
        String stepId = sm.getCurrent().steps.get(0).id;
        sm.onContractIssued("c1", stepId, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        assertEquals(Plan.ActiveSubState.EXECUTING, sm.getCurrent().subState);
        sm.onContractIssued("c2", stepId, ContractAssignee.MAIN_AI);
        // Issuing during execution doesn't yank back to PENDING_DISCHARGE
        assertEquals(Plan.ActiveSubState.EXECUTING, sm.getCurrent().subState);
        assertTrue(sm.getCurrent().steps.get(0).contractIds.contains("c2"));
    }

    @Test
    public void allStepsDone_skippedCounts() {
        sm.onPlanCreated(makeSteps(2), null);
        sm.getCurrent().steps.get(0).status = PlanStep.StepStatus.SKIPPED;
        sm.onStepCompleted(sm.getCurrent().steps.get(1).id);
        assertEquals(Plan.PlanState.DONE, sm.getCurrent().state);
    }

    // ─── H7 fix (2026-06-26): reopenForNewStep ──────────────────────────

    @Test
    public void reopenForNewStep_fromDone_movesToActivePendingDecision() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onStepCompleted(sm.getCurrent().steps.get(0).id);
        assertEquals(Plan.PlanState.DONE, sm.getCurrent().state);

        sm.reopenForNewStep();
        assertEquals(Plan.PlanState.ACTIVE, sm.getCurrent().state);
        assertEquals(Plan.ActiveSubState.PENDING_DECISION, sm.getCurrent().subState);
    }

    @Test
    public void reopenForNewStep_firesListener() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onStepCompleted(sm.getCurrent().steps.get(0).id);   // → DONE
        AtomicInteger count = new AtomicInteger();
        sm.addListener((oldState, oldSub, now) -> count.incrementAndGet());
        sm.reopenForNewStep();
        assertEquals(1, count.get());
    }

    @Test
    public void reopenForNewStep_nonTerminal_isNoop() {
        sm.onPlanCreated(makeSteps(2), null);                  // ACTIVE/PENDING_DECISION
        AtomicInteger count = new AtomicInteger();
        sm.addListener((oldState, oldSub, now) -> count.incrementAndGet());
        sm.reopenForNewStep();
        assertEquals(Plan.PlanState.ACTIVE, sm.getCurrent().state);
        assertEquals(0, count.get());
    }

    @Test
    public void reopenForNewStep_aborted_staysAborted() {
        sm.onPlanCreated(makeSteps(1), null);
        sm.onUserCancel("stop");
        sm.reopenForNewStep();
        assertEquals(Plan.PlanState.ABORTED, sm.getCurrent().state);
    }

    @Test
    public void reopenForNewStep_thenContractIssued_progressesAgain() {
        // H7: a plan that auto-reached DONE but gains a new injected step must
        // accept contracts again — on a terminal plan these would all no-op and
        // the step could never progress (the "premature DONE → wedge" symptom).
        sm.onPlanCreated(makeSteps(1), null);
        sm.onStepCompleted(sm.getCurrent().steps.get(0).id);   // → DONE
        PlanStep added = PlanStep.create("auto", sm.getCurrent().steps.size(),
                "extra step", PlanStep.StepOwner.MAIN_AI);
        sm.getCurrent().steps.add(added);
        sm.getCurrent().currentStepIndex = sm.getCurrent().steps.size() - 1;
        sm.reopenForNewStep();

        sm.onContractIssued("c2", added.id, ContractAssignee.MAIN_AI);
        sm.onTurnStarted(ContractAssignee.MAIN_AI);
        assertEquals(Plan.ActiveSubState.EXECUTING, sm.getCurrent().subState);
        assertEquals(PlanStep.StepStatus.IN_PROGRESS, added.status);
        assertTrue(added.contractIds.contains("c2"));
    }

    private List<PlanStep> makeSteps(int n) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(PlanStep.create("plan_test", i, "step " + i, PlanStep.StepOwner.MAIN_AI));
        }
        return steps;
    }
}
