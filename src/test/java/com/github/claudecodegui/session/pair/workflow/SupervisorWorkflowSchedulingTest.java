package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Node delay / scheduled-execution tests (D25–D28): a node whose deps are done
 * but whose start time is in the future goes {@link NodeStatus#SCHEDULED} (no
 * slot held) until its timer fires; absolute past times run immediately;
 * re-dispatch / abort cancel the timer; restart keeps SCHEDULED and resume
 * re-arms from the persisted absolute instant.
 */
public class SupervisorWorkflowSchedulingTest {

    private static SupervisorWorkflowManager.WorkflowScheduler directScheduler() {
        return new SupervisorWorkflowManager.WorkflowScheduler() {
            @Override public void submit(Runnable task) { task.run(); }
            @Override public void shutdown() { }
        };
    }

    /** Fake launcher: drives tab+pair to "started" synchronously with stable ids. */
    private static final class FakeLauncher implements NodeLauncher {
        final Path tmp;
        FakeLauncher(Path tmp) { this.tmp = tmp; }
        @Override public void launch(WorkflowNode node, Path planPath, Sink sink) {
            Path pairDir = tmp.resolve("pairs").resolve(node.name);
            try { Files.createDirectories(pairDir); } catch (IOException ignored) { }
            sink.tabCreated("win-" + node.name, new NodeHandle("win-" + node.name, pairDir));
            sink.pairStarted("pair-" + node.name, pairDir);
        }
        @Override public void stop(String windowId) { }
        @Override public void focus(NodeHandle handle) { }
        @Override public void openReport(String reportPath) { }
    }

    private static WorkflowNode node(String name, String... deps) {
        WorkflowNode n = new WorkflowNode();
        n.name = name;
        n.supervisorId = "sup";
        n.plan = "task " + name;
        n.dependsOn = new ArrayList<>(Arrays.asList(deps));
        return n;
    }

    private static SupervisorWorkflowManager mgr(Path tmp, WorkflowStore store) {
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        return new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);
    }

    /** A → B, B configured with a timing rule. Returns a started manager (A RUNNING). */
    private SupervisorWorkflowManager startChain(Path tmp, WorkflowNode b) throws IOException {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wf1";
        d.name = "chain";
        d.maxConcurrency = 2;
        d.nodes = new ArrayList<>(Arrays.asList(node("A"), b));
        store.save(d);
        SupervisorWorkflowManager mgr = mgr(tmp, store);
        mgr.startWorkflow("wf1");
        return mgr;
    }

    // ─── relative delay → SCHEDULED, then timer fires ───────────────────

    @Test
    public void relativeDelaySchedulesNodeWithoutHoldingSlot() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-rel");
        WorkflowNode b = node("B", "A");
        b.delayMode = "relative";
        b.delayMinutes = 60;
        SupervisorWorkflowManager mgr = startChain(tmp, b);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);

        long before = System.currentTimeMillis();
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "ok", null);

        NodeRuntime rb = e.nodes.get("B");
        assertEquals(NodeStatus.SCHEDULED, rb.status);
        assertNotNull(rb.scheduledStartAt);
        assertTrue("due ~60min out", rb.scheduledStartAt >= before + 59 * 60_000L
                && rb.scheduledStartAt <= before + 61 * 60_000L);
        assertEquals(2, mgr.availableSlotsForTest());   // A released, B holds none

        // Timer fires → B becomes READY and starts, consuming one slot.
        mgr.fireScheduledForTest("B");
        assertEquals(NodeStatus.RUNNING, rb.status);
        assertEquals("pair-B", rb.pairId);
        assertNull(rb.scheduledStartAt);
        assertEquals(1, mgr.availableSlotsForTest());
    }

    @Test
    public void relativeDelayClampedToFiveHours() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-clamp");
        WorkflowNode b = node("B", "A");
        b.delayMode = "relative";
        b.delayMinutes = 999;   // > 300
        SupervisorWorkflowManager mgr = startChain(tmp, b);
        long before = System.currentTimeMillis();
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "ok", null);

        NodeRuntime rb = mgr.execForTest().nodes.get("B");
        assertEquals(NodeStatus.SCHEDULED, rb.status);
        assertTrue("clamped to 300min", rb.scheduledStartAt <= before + 301 * 60_000L);
    }

    @Test
    public void absolutePastRunsImmediately() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-past");
        WorkflowNode b = node("B", "A");
        b.delayMode = "absolute";
        b.scheduledAt = System.currentTimeMillis() - 60_000L;   // already past
        SupervisorWorkflowManager mgr = startChain(tmp, b);
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "ok", null);

        NodeRuntime rb = mgr.execForTest().nodes.get("B");
        assertEquals(NodeStatus.RUNNING, rb.status);   // dueAt<=now → straight to ready→run
        assertEquals("pair-B", rb.pairId);
    }

    // ─── redispatch / abort cancel the timer ────────────────────────────

    @Test
    public void redispatchScheduledRunsImmediately() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-redispatch");
        WorkflowNode b = node("B", "A");
        b.delayMode = "absolute";
        b.scheduledAt = System.currentTimeMillis() + 60 * 60_000L;   // 1h out
        SupervisorWorkflowManager mgr = startChain(tmp, b);
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "ok", null);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.SCHEDULED, e.nodes.get("B").status);

        mgr.redispatchNode("B", "auto");   // D27: run now, skip remaining delay
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);
        assertNull(e.nodes.get("B").scheduledStartAt);
        assertEquals(1, mgr.availableSlotsForTest());
    }

    @Test
    public void abortCancelsScheduledNode() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-abort");
        WorkflowNode b = node("B", "A");
        b.delayMode = "absolute";
        b.scheduledAt = System.currentTimeMillis() + 60 * 60_000L;
        SupervisorWorkflowManager mgr = startChain(tmp, b);
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "ok", null);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.SCHEDULED, e.nodes.get("B").status);

        mgr.abortWorkflow();
        assertEquals(WorkflowState.ABORTED, e.state);
        assertEquals(NodeStatus.ABORTED, e.nodes.get("B").status);
        assertNull(e.nodes.get("B").scheduledStartAt);
    }

    // ─── restart keeps SCHEDULED; resume re-arms from absolute epoch ─────

    @Test
    public void rehydrateKeepsScheduledAndResumeFiresOverdue() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-restart");
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wf1";
        d.name = "chain";
        d.maxConcurrency = 2;
        WorkflowNode b = node("B", "A");
        b.delayMode = "absolute";
        d.nodes = new ArrayList<>(Arrays.asList(node("A"), b));
        store.save(d);

        // Persist a stale RUNNING run: A done, B SCHEDULED with a due time already
        // in the past (as if the IDE was off past the scheduled instant).
        WorkflowExecution stale = new WorkflowExecution();
        stale.workflowId = "wf1";
        stale.state = WorkflowState.RUNNING;
        stale.concurrency = 2;
        NodeRuntime a = new NodeRuntime(); a.status = NodeStatus.DONE;
        NodeRuntime rb = new NodeRuntime();
        rb.status = NodeStatus.SCHEDULED;
        rb.scheduledStartAt = System.currentTimeMillis() - 60_000L;   // overdue
        stale.nodes.put("A", a); stale.nodes.put("B", rb);
        store.saveExecution("wf1", stale);

        SupervisorWorkflowManager mgr = mgr(tmp, store);
        mgr.rehydrateOnStartup();
        WorkflowExecution e = mgr.execForTest();
        assertEquals(WorkflowState.PAUSED, e.state);
        assertEquals(NodeStatus.SCHEDULED, e.nodes.get("B").status);   // kept across restart

        mgr.resumeWorkflow("wf1");
        // Overdue during downtime → resume runs it straight away.
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);
        assertEquals("pair-B", e.nodes.get("B").pairId);
        assertNull(e.nodes.get("B").scheduledStartAt);
    }
}
