package com.github.claudecodegui.session.pair.contract;

import com.github.claudecodegui.session.pair.l2.L2Schema;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan §14.2 / §17.3 user case 7: IDE restart recovers plan + open contracts
 * from L2 and re-arms deadline timers (including immediate fire for already-
 * expired contracts).
 */
public class ContractRegistryIdeRestartTest {

    private Path tmp;
    private L2Store store;
    private ContractRegistry registry;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("ide-restart-test-");
        store = new L2Store(tmp);
        registry = new ContractRegistry("pair_restart");
    }

    @After
    public void tearDown() throws IOException {
        if (registry != null) registry.dispose();
        if (tmp != null && Files.exists(tmp)) {
            try (Stream<Path> paths = Files.walk(tmp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    public void hydrate_freshlyPersistedContract_armsTimerWithRemainingTime() throws InterruptedException {
        // Phase 1: persist a contract via L2.
        Contract c = Contract.create(
                "ctr_persist_1", "step_x", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{\"objective\":\"do thing\"}", 200L, 2, null, 0);
        // Simulate "just issued" — issuedAt is now, deadline = 200ms.
        c.issuedAt = System.currentTimeMillis();
        c.lastActivityAt = c.issuedAt;
        L2State.PersistedContract pc = ContractPersistence.toPersisted(c);

        store.update("pair_restart", s -> {
            s.openContracts.add(pc);
            return s;
        });

        // Phase 2: "IDE restart" — invalidate cache + read back, hydrate.
        store.invalidateCache("pair_restart");
        L2State reloaded = store.read("pair_restart");
        assertEquals(L2Schema.CURRENT_VERSION, reloaded.schemaVersion);
        assertEquals(1, reloaded.openContracts.size());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Contract> fired = new AtomicReference<>();
        registry.setDeadlineCallback(contract -> {
            fired.set(contract);
            latch.countDown();
        });

        // Reconstruct runtime contracts from persisted shape.
        List<Contract> rehydrated = ContractPersistence.fromPersistedList(reloaded.openContracts);
        registry.hydrateFromL2(rehydrated);

        // Contract is open + deadline is still ~200ms; timer should fire within a second.
        assertEquals(1, registry.openCount());
        assertTrue("expected deadline to fire", latch.await(2, TimeUnit.SECONDS));
        assertNotNull(fired.get());
        assertEquals("ctr_persist_1", fired.get().id);
    }

    @Test
    public void hydrate_alreadyExpiredContract_firesCallbackImmediately() throws InterruptedException {
        Contract c = Contract.create(
                "ctr_old_1", "step_y", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 100L, 2, null, 0);
        c.issuedAt = System.currentTimeMillis() - 60_000L;  // 1 min ago (well under 30min stale cap)
        c.lastActivityAt = c.issuedAt;
        L2State.PersistedContract pc = ContractPersistence.toPersisted(c);

        store.update("pair_restart", s -> {
            s.openContracts.add(pc);
            return s;
        });

        store.invalidateCache("pair_restart");
        L2State reloaded = store.read("pair_restart");

        CountDownLatch latch = new CountDownLatch(1);
        registry.setDeadlineCallback(contract -> latch.countDown());

        registry.hydrateFromL2(ContractPersistence.fromPersistedList(reloaded.openContracts));

        // Already past deadline → must fire within tens of ms (scheduled at 1ms in hydrate).
        assertTrue("expected immediate deadline fire", latch.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void hydrate_staleContract_isAutoCancelled() {
        // Contract issued >30min ago → hydrate should auto-cancel as stale.
        Contract stale = Contract.create(
                "ctr_stale", "step_z", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 600_000L, 2, null, 0);
        stale.issuedAt = System.currentTimeMillis() - (60L * 60L * 1000L);  // 1 hour ago
        stale.lastActivityAt = stale.issuedAt;

        Contract fresh = Contract.create(
                "ctr_fresh", "step_z2", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 600_000L, 2, null, 0);
        fresh.issuedAt = System.currentTimeMillis();
        fresh.lastActivityAt = fresh.issuedAt;

        List<Contract> toHydrate = new ArrayList<>();
        toHydrate.add(stale);
        toHydrate.add(fresh);
        registry.hydrateFromL2(toHydrate);

        // Only fresh remains open. totalIssued counts the kept ones (consistency).
        assertEquals(1, registry.openCount());
        assertNotNull(registry.findById("ctr_fresh"));
        assertEquals(1, registry.getTotalIssued());

        // Stale lives in closed history with CANCELLED status.
        Contract foundStale = registry.findById("ctr_stale");
        assertNotNull(foundStale);
        assertEquals(ContractStatus.CANCELLED, foundStale.status);
    }

    @Test
    public void hydrate_bumpsTotalIssuedForCounterConsistency() {
        // Bug fix 2026-05-25: hydrated contracts must count toward totalIssued
        // so the UI doesn't show "Open=N, 累计=0" (impossible math).
        Contract c = Contract.create(
                "ctr_bump", "step_q", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 600_000L, 2, null, 0);
        c.issuedAt = System.currentTimeMillis();
        c.lastActivityAt = c.issuedAt;

        assertEquals(0, registry.getTotalIssued());
        List<Contract> toHydrate = new ArrayList<>();
        toHydrate.add(c);
        registry.hydrateFromL2(toHydrate);
        assertEquals(1, registry.getTotalIssued());

        // Discharge it → totalDischarged=1, openCount=0, totalIssued=1. Math checks.
        registry.discharge("ctr_bump", null);
        assertEquals(0, registry.openCount());
        assertEquals(1, registry.getTotalIssued());
        assertEquals(1, registry.getTotalDischarged());
    }

    @Test
    public void persistThenHydrate_preservesPlanAndContract() throws IOException {
        // Phase 1: build a plan + contract, persist.
        L2State.PersistedPlan plan = new L2State.PersistedPlan();
        plan.id = "plan_persist";
        plan.state = "ACTIVE";
        plan.subState = "PENDING_DISCHARGE";
        plan.currentStepIndex = 0;
        plan.createdAt = 1000L;
        plan.lastTransitionAt = 2000L;
        L2State.PersistedPlanStep step = new L2State.PersistedPlanStep();
        step.id = "step_x";
        step.index = 0;
        step.title = "first step";
        step.owner = "MAIN_AI";
        step.status = "IN_PROGRESS";
        step.attempts = 1;
        plan.steps.add(step);

        L2State.PersistedContract c = new L2State.PersistedContract();
        c.id = "ctr_persist";
        c.parentStepId = "step_x";
        c.type = "TASK_ASSIGNMENT";
        c.assignedTo = "MAIN_AI";
        c.status = "OPEN";
        c.issuedAt = System.currentTimeMillis();
        c.deadlineMs = 600000L;
        c.lastActivityAt = c.issuedAt;
        c.maxRetries = 2;

        store.update("pair_restart", s -> {
            s.plan = plan;
            s.openContracts = new ArrayList<>();
            s.openContracts.add(c);
            return s;
        });

        // Phase 2: invalidate + reload, verify all fields preserved.
        store.invalidateCache("pair_restart");
        L2State reloaded = store.read("pair_restart");

        assertNotNull(reloaded.plan);
        assertEquals("plan_persist", reloaded.plan.id);
        assertEquals("ACTIVE", reloaded.plan.state);
        assertEquals("PENDING_DISCHARGE", reloaded.plan.subState);
        assertEquals(1, reloaded.plan.steps.size());
        assertEquals("first step", reloaded.plan.steps.get(0).title);
        assertEquals("IN_PROGRESS", reloaded.plan.steps.get(0).status);
        assertEquals(1, reloaded.plan.steps.get(0).attempts);

        assertEquals(1, reloaded.openContracts.size());
        L2State.PersistedContract loadedC = reloaded.openContracts.get(0);
        assertEquals("ctr_persist", loadedC.id);
        assertEquals("step_x", loadedC.parentStepId);
        assertEquals("TASK_ASSIGNMENT", loadedC.type);
    }
}
