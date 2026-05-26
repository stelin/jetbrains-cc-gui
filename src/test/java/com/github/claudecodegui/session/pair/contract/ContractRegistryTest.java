package com.github.claudecodegui.session.pair.contract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ContractRegistryTest {

    private ContractRegistry registry;

    @Before
    public void setUp() {
        registry = new ContractRegistry("pair_test");
    }

    @After
    public void tearDown() {
        if (registry != null) registry.dispose();
    }

    // ─── Issue ───────────────────────────────────────────────────────────

    @Test
    public void issue_createsOpenContract() {
        Contract c = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        assertNotNull(c.id);
        assertEquals(ContractStatus.OPEN, c.status);
        assertEquals("step_1", c.parentStepId);
        assertEquals(1, registry.openCount());
        assertEquals(1, c.history.size());
        assertEquals(ContractEvent.EventType.ISSUED, c.history.get(0).type);
    }

    @Test
    public void issue_replaceExistingTrue_cancelsPriorOpenForSameStepAndAssignee() {
        Contract first = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        Contract second = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        assertEquals(1, registry.openCount());
        Contract found = registry.findById(first.id);
        assertNotNull(found);
        assertEquals(ContractStatus.CANCELLED, found.status);
        assertNotEquals(first.id, second.id);
    }

    @Test
    public void issue_replaceExistingFalse_keepsBoth() {
        registry.issue(reqBuilder("step_1", ContractAssignee.MAIN_AI).replaceExisting(false).build());
        registry.issue(reqBuilder("step_1", ContractAssignee.MAIN_AI).replaceExisting(false).build());
        assertEquals(2, registry.openCount());
    }

    @Test
    public void issue_differentStepOrAssignee_doesNotReplace() {
        registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        registry.issue(req("step_2", ContractAssignee.MAIN_AI));
        registry.issue(req("step_1", ContractAssignee.SUPERVISOR));
        assertEquals(3, registry.openCount());
    }

    @Test
    public void issue_withHint_usesProvidedId() {
        Contract c = registry.issue(reqBuilder("step_1", ContractAssignee.MAIN_AI)
                .contractIdHint("my_custom_id")
                .build());
        assertEquals("my_custom_id", c.id);
    }

    // ─── Discharge ───────────────────────────────────────────────────────

    @Test
    public void discharge_marksDischargedAndMovesToClosed() {
        Contract c = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        assertTrue(registry.discharge(c.id, "done"));
        assertEquals(0, registry.openCount());
        Contract found = registry.findById(c.id);
        assertNotNull(found);
        assertEquals(ContractStatus.DISCHARGED, found.status);
    }

    @Test
    public void discharge_unknownId_returnsFalse() {
        assertFalse(registry.discharge("nope", null));
    }

    // ─── Retry ───────────────────────────────────────────────────────────

    @Test
    public void retry_createsNewContractWithRetryOfPointer() {
        Contract original = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        Contract retry = registry.retry(original.id, ContractType.SYSTEM_NUDGE, "deadline expired");

        assertNotEquals(original.id, retry.id);
        assertEquals(original.id, retry.retryOf);
        assertEquals(ContractType.SYSTEM_NUDGE, retry.type);
        assertEquals(1, retry.retryCount);
        assertEquals(original.parentStepId, retry.parentStepId);
        assertEquals(original.assignedTo, retry.assignedTo);
        assertEquals(original.payloadJson, retry.payloadJson);

        // original now closed and EXPIRED_RETRIED
        Contract foundOriginal = registry.findById(original.id);
        assertEquals(ContractStatus.EXPIRED_RETRIED, foundOriginal.status);
        assertEquals(1, registry.openCount());
    }

    @Test
    public void retry_unknownId_throws() {
        try {
            registry.retry("nope", ContractType.TASK_ASSIGNMENT, null);
            fail();
        } catch (IllegalArgumentException expected) { /* good */ }
    }

    // ─── Escalate ────────────────────────────────────────────────────────

    @Test
    public void escalate_marksEscalatedAndMovesToClosed() {
        Contract c = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        registry.escalate(c.id);
        assertEquals(0, registry.openCount());
        Contract found = registry.findById(c.id);
        assertEquals(ContractStatus.EXPIRED_ESCALATED, found.status);
    }

    // ─── Cancel ──────────────────────────────────────────────────────────

    @Test
    public void cancel_marksCancelledAndMovesToClosed() {
        Contract c = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        registry.cancel(c.id, "user abort");
        Contract found = registry.findById(c.id);
        assertEquals(ContractStatus.CANCELLED, found.status);
        assertEquals(0, registry.openCount());
    }

    // ─── Queries ─────────────────────────────────────────────────────────

    @Test
    public void hasPendingFor_matchesStepAndAssignee() {
        registry.issue(req("step_A", ContractAssignee.MAIN_AI));
        assertTrue(registry.hasPendingFor("step_A", ContractAssignee.MAIN_AI));
        assertFalse(registry.hasPendingFor("step_A", ContractAssignee.SUPERVISOR));
        assertFalse(registry.hasPendingFor("step_B", ContractAssignee.MAIN_AI));
    }

    @Test
    public void findOldestOpen_returnsEarliestIssued() throws InterruptedException {
        Contract first = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        // Ensure timestamps differ.
        Thread.sleep(2);
        registry.issue(req("step_2", ContractAssignee.MAIN_AI));
        Optional<Contract> oldest = registry.findOldestOpen();
        assertTrue(oldest.isPresent());
        assertEquals(first.id, oldest.get().id);
    }

    @Test
    public void getOpenContracts_returnsDefensiveCopy() {
        registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        List<Contract> snapshot = registry.getOpenContracts();
        snapshot.clear();
        assertEquals(1, registry.openCount());
    }

    // ─── Deadline callback ───────────────────────────────────────────────

    @Test
    public void deadlineCallback_firesWhenTimerExpires() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Contract> seen = new AtomicReference<>();
        registry.setDeadlineCallback(c -> {
            seen.set(c);
            latch.countDown();
        });

        Contract c = registry.issue(reqBuilder("step_1", ContractAssignee.MAIN_AI)
                .deadlineMs(80L)
                .build());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(seen.get());
        assertEquals(c.id, seen.get().id);
    }

    @Test
    public void dischargeCancelsDeadlineTimer() throws InterruptedException {
        AtomicInteger fires = new AtomicInteger();
        registry.setDeadlineCallback(c -> fires.incrementAndGet());

        Contract c = registry.issue(reqBuilder("step_1", ContractAssignee.MAIN_AI)
                .deadlineMs(150L)
                .build());
        registry.discharge(c.id, "early");
        Thread.sleep(300);
        assertEquals(0, fires.get());
    }

    // ─── Hydration ───────────────────────────────────────────────────────

    @Test
    public void hydrate_restoresOpenContractsAndArmsTimers() throws InterruptedException {
        Contract loaded = Contract.create(
                "ctr_loaded_1", "step_99", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 100L, 2, null, 0);
        // Simulate it was issued "just now" so remaining ≈ 100ms.
        loaded.issuedAt = System.currentTimeMillis();
        loaded.lastActivityAt = loaded.issuedAt;

        CountDownLatch latch = new CountDownLatch(1);
        registry.setDeadlineCallback(c -> latch.countDown());

        List<Contract> toHydrate = new ArrayList<>();
        toHydrate.add(loaded);
        registry.hydrateFromL2(toHydrate);

        assertEquals(1, registry.openCount());
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void hydrate_alreadyExpired_firesCallbackImmediately() throws InterruptedException {
        Contract loaded = Contract.create(
                "ctr_old_1", "step_99", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 100L, 2, null, 0);
        // Pretend it was issued long ago — remaining ≤ 0
        loaded.issuedAt = System.currentTimeMillis() - 10_000;
        loaded.lastActivityAt = loaded.issuedAt;

        CountDownLatch latch = new CountDownLatch(1);
        registry.setDeadlineCallback(c -> latch.countDown());

        List<Contract> toHydrate = new ArrayList<>();
        toHydrate.add(loaded);
        registry.hydrateFromL2(toHydrate);

        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void hydrate_skipsNonOpenContracts() {
        Contract closed = Contract.create(
                "ctr_done", "step", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 1000L, 2, null, 0);
        closed.status = ContractStatus.DISCHARGED;
        Contract open = Contract.create(
                "ctr_open", "step", ContractType.TASK_ASSIGNMENT,
                ContractAssignee.MAIN_AI, "{}", 1000L, 2, null, 0);

        List<Contract> toHydrate = new ArrayList<>();
        toHydrate.add(closed);
        toHydrate.add(open);
        registry.hydrateFromL2(toHydrate);
        assertEquals(1, registry.openCount());
        assertNotNull(registry.findById("ctr_open"));
        assertNull(registry.findById("ctr_done"));
    }

    // ─── Listener notifications ──────────────────────────────────────────

    @Test
    public void listener_receivesIssuedDischargedRetriedEscalatedCancelled() {
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger discharged = new AtomicInteger();
        AtomicInteger retried = new AtomicInteger();
        AtomicInteger escalated = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        registry.addListener(new ContractListener() {
            @Override public void onIssued(Contract c) { issued.incrementAndGet(); }
            @Override public void onDischarged(Contract c) { discharged.incrementAndGet(); }
            @Override public void onRetried(Contract a, Contract b) { retried.incrementAndGet(); }
            @Override public void onEscalated(Contract c) { escalated.incrementAndGet(); }
            @Override public void onCancelled(Contract c, String r) { cancelled.incrementAndGet(); }
        });

        Contract c1 = registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        registry.discharge(c1.id, null);

        Contract c2 = registry.issue(req("step_2", ContractAssignee.MAIN_AI));
        Contract retry = registry.retry(c2.id, ContractType.SYSTEM_NUDGE, null);
        registry.escalate(retry.id);

        Contract c3 = registry.issue(req("step_3", ContractAssignee.MAIN_AI));
        registry.cancel(c3.id, "test");

        assertEquals(4, issued.get());     // c1, c2, retry, c3
        assertEquals(1, discharged.get()); // c1
        assertEquals(1, retried.get());    // c2 → retry
        assertEquals(1, escalated.get());  // retry
        assertEquals(1, cancelled.get());  // c3
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    @Test
    public void dispose_cleansUpScheduler() {
        registry.issue(req("step_1", ContractAssignee.MAIN_AI));
        registry.dispose();
        assertEquals(0, registry.openCount());
        // Issue after dispose throws
        try {
            registry.issue(req("step_2", ContractAssignee.MAIN_AI));
            fail();
        } catch (IllegalStateException expected) { /* good */ }
    }

    @Test
    public void closedHistory_boundedByMax() {
        for (int i = 0; i < ContractRegistry.CLOSED_HISTORY_MAX + 10; i++) {
            Contract c = registry.issue(reqBuilder("step_" + i, ContractAssignee.MAIN_AI).build());
            registry.discharge(c.id, null);
        }
        assertEquals(ContractRegistry.CLOSED_HISTORY_MAX, registry.getClosedContractsCopy().size());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private ContractIssueRequest req(String stepId, ContractAssignee assignee) {
        return reqBuilder(stepId, assignee).build();
    }

    private ContractIssueRequest.Builder reqBuilder(String stepId, ContractAssignee assignee) {
        return ContractIssueRequest.builder()
                .parentStepId(stepId)
                .type(ContractType.TASK_ASSIGNMENT)
                .assignedTo(assignee)
                .payloadJson("{}");
    }
}
