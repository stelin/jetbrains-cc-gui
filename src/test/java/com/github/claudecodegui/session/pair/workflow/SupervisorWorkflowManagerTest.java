package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.handler.core.HandlerContext;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Scheduler state-machine tests for {@link SupervisorWorkflowManager}
 * (coding-plan §8 / §15 / §22). The EDT / background pool / {@code startPair}
 * are isolated behind a fake {@link NodeLauncher}; the {@code wf-scheduler}
 * thread is replaced by a synchronous executor so each external call runs to
 * completion before the next assertion. All assertions read engine state via
 * the {@code *ForTest} accessors.
 */
public class SupervisorWorkflowManagerTest {

    // ─── test doubles ────────────────────────────────────────────────────

    /** Same-thread scheduler: every submitted task runs inline, deterministically. */
    private static SupervisorWorkflowManager.WorkflowScheduler directScheduler() {
        return new SupervisorWorkflowManager.WorkflowScheduler() {
            @Override public void submit(Runnable task) { task.run(); }
            @Override public void shutdown() { }
        };
    }

    /**
     * Fake launcher: assigns deterministic {@code win-<name>} / {@code pair-<name>}
     * ids and synchronously fires {@code tabCreated} then {@code pairStarted} so
     * the node reaches RUNNING within the launching call. Records stop/focus calls.
     */
    private static final class FakeLauncher implements NodeLauncher {
        final Path tmp;
        final List<String> stopped = new ArrayList<>();
        final List<NodeHandle> focused = new ArrayList<>();
        final List<String> openedReports = new ArrayList<>();
        final Map<String, String> nodeToPair = new LinkedHashMap<>();

        FakeLauncher(Path tmp) { this.tmp = tmp; }

        @Override
        public void launch(WorkflowNode node, Path planPath, Sink sink) {
            String windowId = "win-" + node.name;
            String pairId = "pair-" + node.name;
            nodeToPair.put(node.name, pairId);
            Path pairDir = tmp.resolve("pairs").resolve(node.name);
            try { Files.createDirectories(pairDir); } catch (IOException ignored) { }
            NodeHandle handle = new NodeHandle(windowId, pairDir);
            sink.tabCreated(windowId, handle);
            sink.pairStarted(pairId, pairDir);
        }

        @Override public void stop(String windowId) { stopped.add(windowId); }
        @Override public void focus(NodeHandle handle) { focused.add(handle); }
        @Override public void openReport(String reportPath) { openedReports.add(reportPath); }
    }

    /** Records every push so broadcast wiring can be asserted headlessly. */
    private static final class CapturingSink implements HandlerContext.JsCallback {
        final List<String> fns = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        @Override public void callJavaScript(String fn, String... args) {
            fns.add(fn);
            payloads.add(args != null && args.length > 0 ? args[0] : "");
        }
        @Override public String escapeJs(String s) { return s; }
        int countOf(String fn) { return (int) fns.stream().filter(fn::equals).count(); }
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    private static WorkflowNode node(String name, String... deps) {
        WorkflowNode n = new WorkflowNode();
        n.name = name;
        n.supervisorId = "sup";
        n.plan = "task " + name;
        n.dependsOn = new ArrayList<>(Arrays.asList(deps));
        return n;
    }

    /** Diamond: A, B (roots) → C; concurrency = 2. */
    private static WorkflowDefinition diamond() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wf1";
        d.name = "diamond";
        d.maxConcurrency = 2;
        d.nodes = new ArrayList<>(Arrays.asList(node("A"), node("B"), node("C", "A", "B")));
        return d;
    }

    private SupervisorWorkflowManager newManager(Path tmp, FakeLauncher launcher) {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        return new SupervisorWorkflowManager(
                null, store, launcher, directScheduler(), () -> known, () -> 3);
    }

    private SupervisorWorkflowManager startedDiamond(Path tmp, FakeLauncher launcher) {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, launcher, directScheduler(), () -> known, () -> 3);
        mgr.startWorkflow("wf1");
        return mgr;
    }

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    public void startFansOutRootsAndHoldsAllSlots() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-start");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);

        WorkflowExecution e = mgr.execForTest();
        assertNotNull(e);
        assertEquals(WorkflowState.RUNNING, e.state);
        assertEquals(2, e.concurrency);
        // A and B (no deps) launched immediately → RUNNING; C blocked → PENDING.
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        // Both concurrency slots consumed by A + B.
        assertEquals(0, mgr.availableSlotsForTest());
        assertTrue(mgr.readyQueueForTest().isEmpty());
        // pairId / windowId threaded through.
        assertEquals("pair-A", e.nodes.get("A").pairId);
        assertEquals("win-A", e.nodes.get("A").windowId);
    }

    @Test
    public void rollingScheduleAdvancesJoinNodeOnlyAfterAllDeps() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-rolling");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        // A done → slot freed, but C must NOT start (B still running).
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A ok", Arrays.asList("a.txt"));
        assertEquals(NodeStatus.DONE, e.nodes.get("A").status);
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);

        // B done → both deps satisfied → C enqueues and launches (rolling, DN3).
        mgr.onNodeReport("pair-B", NodeStatus.DONE, "B ok", null);
        assertEquals(NodeStatus.DONE, e.nodes.get("B").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("C").status);
        assertEquals("pair-C", e.nodes.get("C").pairId);
    }

    @Test
    public void completesWhenAllNodesDone() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-complete");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeReport("pair-A", NodeStatus.DONE, null, null);
        mgr.onNodeReport("pair-B", NodeStatus.DONE, null, null);
        mgr.onNodeReport("pair-C", NodeStatus.DONE, null, null);

        assertEquals(NodeStatus.DONE, e.nodes.get("C").status);
        assertEquals(WorkflowState.COMPLETED, e.state);
        // Every acquired slot was released by its node's DONE.
        assertEquals(2, mgr.availableSlotsForTest());
        // Completion report path resolved from the pair dir.
        assertNotNull(e.nodes.get("C").completionReportPath);
        assertTrue(e.nodes.get("C").completionReportPath.endsWith("COMPLETION_REPORT.md"));
    }

    @Test
    public void completionReportPathFlowsFromPairDir() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-report");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeReport("pair-A", NodeStatus.DONE, "done", null);
        String path = e.nodes.get("A").completionReportPath;
        assertNotNull(path);
        assertTrue(path.contains("pairs") && path.endsWith("COMPLETION_REPORT.md"));
    }

    @Test
    public void blockedNodeDoesNotFreeSlotOrAdvanceDownstream() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-blocked");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        // A blocks → WAITING_HUMAN, slot stays held (still 0 free), C stays PENDING.
        mgr.onNodeReport("pair-A", NodeStatus.WAITING_HUMAN, "needs a human", null);
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("needs a human", e.nodes.get("A").escalationReason);
        assertEquals(0, mgr.availableSlotsForTest());
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        assertEquals(WorkflowState.RUNNING, e.state);
        // Escalation auto-focused the blocked node's tab.
        assertEquals(1, launcher.focused.size());

        // B finishing must still NOT advance C (A unsatisfied).
        mgr.onNodeReport("pair-B", NodeStatus.DONE, null, null);
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void blockedThenResolvedResumesScheduling() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-resume");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeReport("pair-A", NodeStatus.WAITING_HUMAN, "blocked", null);
        mgr.onNodeReport("pair-B", NodeStatus.DONE, null, null);
        // Human resolves → supervisor eventually emits done for A.
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "fixed", null);
        assertEquals(NodeStatus.DONE, e.nodes.get("A").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("C").status);

        mgr.onNodeReport("pair-C", NodeStatus.DONE, null, null);
        assertEquals(WorkflowState.COMPLETED, e.state);
    }

    @Test
    public void duplicateReportsAreIdempotent() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-idem");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeReport("pair-A", NodeStatus.DONE, null, null);
        int slotsAfterFirst = mgr.availableSlotsForTest();
        // Second DONE for the same node must be ignored (no double slot release).
        mgr.onNodeReport("pair-A", NodeStatus.DONE, null, null);
        assertEquals(NodeStatus.DONE, e.nodes.get("A").status);
        assertEquals(slotsAfterFirst, mgr.availableSlotsForTest());
    }

    @Test
    public void abortMarksLiveNodesAbortedAndStopsPairs() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-abort");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution e = mgr.execForTest();

        mgr.abortWorkflow();
        assertEquals(WorkflowState.ABORTED, e.state);
        assertEquals(NodeStatus.ABORTED, e.nodes.get("A").status);
        assertEquals(NodeStatus.ABORTED, e.nodes.get("B").status);
        assertEquals(NodeStatus.ABORTED, e.nodes.get("C").status); // was PENDING
        // Both running pairs were asked to stop.
        assertTrue(launcher.stopped.contains("win-A"));
        assertTrue(launcher.stopped.contains("win-B"));

        // A late DONE after abort is dropped (exec no longer RUNNING).
        mgr.onNodeReport("pair-A", NodeStatus.DONE, null, null);
        assertEquals(NodeStatus.ABORTED, e.nodes.get("A").status);
        assertEquals(WorkflowState.ABORTED, e.state);
    }

    @Test
    public void singleWorkflowLockRejectsSecondRun() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-lock");
        FakeLauncher launcher = new FakeLauncher(tmp);
        SupervisorWorkflowManager mgr = startedDiamond(tmp, launcher);
        WorkflowExecution first = mgr.execForTest();

        // Second run while the first is RUNNING must be a no-op on engine state.
        mgr.startWorkflow("wf1");
        assertEquals(first, mgr.execForTest());
        assertEquals(WorkflowState.RUNNING, mgr.execForTest().state);
        assertEquals(NodeStatus.RUNNING, mgr.execForTest().nodes.get("A").status);
    }

    @Test
    public void clampsConcurrencyToCeiling() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-clamp");
        FakeLauncher launcher = new FakeLauncher(tmp);
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        WorkflowDefinition d = diamond();
        d.maxConcurrency = 9;            // exceeds ceiling
        store.save(d);
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, launcher, directScheduler(), () -> known, () -> 3); // ceiling = 3
        mgr.startWorkflow("wf1");
        assertEquals(3, mgr.execForTest().concurrency);
    }

    @Test
    public void invalidGraphIsRejectedBeforeRunning() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-invalid");
        FakeLauncher launcher = new FakeLauncher(tmp);
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wfCycle";
        d.name = "cycle";
        d.maxConcurrency = 2;
        d.nodes = new ArrayList<>(Arrays.asList(node("A", "B"), node("B", "A")));
        store.save(d);
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, launcher, directScheduler(), () -> known, () -> 3);

        mgr.startWorkflow("wfCycle");
        // No execution started for an invalid graph.
        assertEquals(null, mgr.execForTest());
        assertTrue(launcher.nodeToPair.isEmpty());
    }

    @Test
    public void broadcastsExecutionUpdatesToRegisteredSink() throws IOException {
        Path tmp = Files.createTempDirectory("wf-sched-broadcast");
        FakeLauncher launcher = new FakeLauncher(tmp);
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, launcher, directScheduler(), () -> known, () -> 3);

        CapturingSink sink = new CapturingSink();
        mgr.registerSink(sink);

        mgr.startWorkflow("wf1");
        // run → broadcastExec at least once, plus op-result.
        assertTrue(sink.countOf("window.onWorkflowExecutionUpdate") >= 1);
        assertTrue(sink.countOf("window.onWorkflowOperationResult") >= 1);

        mgr.onNodeReport("pair-A", NodeStatus.WAITING_HUMAN, "blocked", null);
        assertTrue(sink.countOf("window.onWorkflowEscalation") >= 1);

        mgr.unregisterSink(sink);
        int before = sink.fns.size();
        mgr.onNodeReport("pair-B", NodeStatus.DONE, null, null);
        // No further pushes after unregister.
        assertEquals(before, sink.fns.size());
    }
}
