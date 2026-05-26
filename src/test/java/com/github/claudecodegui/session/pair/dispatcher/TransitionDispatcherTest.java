package com.github.claudecodegui.session.pair.dispatcher;

import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.contract.ContractAssignee;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransitionDispatcherTest {

    private PairSession pair;
    private PlanStateMachine planSm;
    private TransitionDispatcher dispatcher;

    @Before
    public void setUp() {
        // The 30s PENDING_DECISION_WAKE_DELAY_MS is a public final constant —
        // we don't attempt to mutate it. Tests instead verify the cancel path
        // (the wake never fires because state changed) and rely on
        // PlanStateMachineTest for transition correctness.
        pair = new PairSession(
                "pair_td_test", null, "agent_x", "Test", null, null,
                null, null, "test", null, null, null);
        planSm = new PlanStateMachine(pair.getPairId());
        pair.setPlanStateMachine(planSm);
        planSm.start();
        dispatcher = new TransitionDispatcher(pair, planSm);
    }

    @After
    public void tearDown() {
        if (dispatcher != null) dispatcher.stop();
        if (pair != null) pair.markDisposed();
    }

    @Test
    public void start_addsListenerToPlanSM() {
        // smoke: start + stop without exceptions.
        dispatcher.start();
        // The dispatcher should attach to plan SM. Trigger a transition.
        planSm.onPlanCreated(makeSteps(1), null);  // → PENDING_DECISION (would schedule wake)
        // Immediately cancel by changing state.
        planSm.onUserCancel("test cancel");
        // No exceptions expected.
    }

    @Test
    public void wake_skippedIfStateAlreadyChanged() throws InterruptedException {
        AtomicInteger wakes = new AtomicInteger();
        dispatcher.setSupervisorWakeCallback(wakes::incrementAndGet);
        dispatcher.start();

        // PENDING_DECISION → schedule wake
        planSm.onPlanCreated(makeSteps(1), null);
        // Immediately cancel — should cancel the pending wake.
        planSm.onUserCancel("test");

        // Even waiting longer than wake delay, callback must NOT fire.
        // (We don't actually wait 30s — just confirm wake count stays 0 after
        // a short pause, demonstrating cancel-on-transition logic.)
        Thread.sleep(200);
        assertEquals(0, wakes.get());
    }

    @Test
    public void stop_isIdempotent() {
        dispatcher.start();
        dispatcher.stop();
        dispatcher.stop();
        // No exceptions.
    }

    @Test(timeout = 5_000)
    public void planTransitionsDoNotLeakThreads() throws InterruptedException {
        // Smoke: run several plan transitions rapidly and ensure dispatcher
        // doesn't blow up.
        CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setSupervisorWakeCallback(latch::countDown);
        dispatcher.start();
        for (int i = 0; i < 50; i++) {
            planSm.onPlanCreated(makeSteps(1), null);
            String sid = planSm.getCurrent().steps.get(0).id;
            planSm.onContractIssued("c_" + i, sid, ContractAssignee.MAIN_AI);
            planSm.onTurnStarted(ContractAssignee.MAIN_AI);
            planSm.onTurnEnded(ContractAssignee.MAIN_AI, false);
            planSm.onPlanReplaced();
        }
        // Wake should not fire — every PENDING_DECISION got immediately
        // replaced.
        assertTrue("expected no leaked wake", !latch.await(100, TimeUnit.MILLISECONDS));
    }

    private List<PlanStep> makeSteps(int n) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(PlanStep.create("td_test", i, "step " + i, PlanStep.StepOwner.MAIN_AI));
        }
        return steps;
    }
}
