package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.provider.common.IBridge;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * P5 polish tests (coding-plan §17 / §16.5 / §24): user-closed node tab →
 * WAITING_HUMAN, IDE-restart stale-execution recovery → ABORTED, and the DN9
 * daemon-died callback chain (a mock {@link IBridge} firing {@code onDaemonDied}
 * routes through the registered listener into
 * {@link SupervisorWorkflowManager#onNodeDaemonDown}).
 */
public class SupervisorWorkflowManagerP5Test {

    private static SupervisorWorkflowManager.WorkflowScheduler directScheduler() {
        return new SupervisorWorkflowManager.WorkflowScheduler() {
            @Override public void submit(Runnable task) { task.run(); }
            @Override public void shutdown() { }
        };
    }

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

    /** Minimal IBridge double: captures the lifecycle listener and can fire it. */
    private static final class FakeBridge implements IBridge {
        private DaemonLifecycleListener listener;
        @Override public boolean start() { return true; }
        @Override public void stop() { }
        @Override public boolean isAlive() { return true; }
        @Override public boolean ensureRunning() { return true; }
        @Override public void sendAbort() { }
        @Override public CompletableFuture<Boolean> sendCommand(String method, JsonObject params, DaemonOutputCallback callback) {
            return CompletableFuture.completedFuture(true);
        }
        @Override public void setLifecycleListener(DaemonLifecycleListener l) { this.listener = l; }
        @Override public boolean isSdkPreloaded() { return false; }
        void fireDaemonDied() { if (listener != null) listener.onDaemonDied(); }
    }

    private static WorkflowNode node(String name, String... deps) {
        WorkflowNode n = new WorkflowNode();
        n.name = name;
        n.supervisorId = "sup";
        n.plan = "task " + name;
        n.dependsOn = new ArrayList<>(Arrays.asList(deps));
        return n;
    }

    private static WorkflowDefinition diamond() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wf1";
        d.name = "diamond";
        d.maxConcurrency = 2;
        d.nodes = new ArrayList<>(Arrays.asList(node("A"), node("B"), node("C", "A", "B")));
        return d;
    }

    private SupervisorWorkflowManager startedDiamond(Path tmp) {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);
        mgr.startWorkflow("wf1");
        return mgr;
    }

    // ─── tab close → WAITING_HUMAN (§17 / R4) ───────────────────────────

    @Test
    public void closingRunningNodeTabFunnelsToWaitingHuman() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-tabclose");
        SupervisorWorkflowManager mgr = startedDiamond(tmp);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);

        mgr.onNodeTabClosed("win-A");

        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("tab 被关闭", e.nodes.get("A").escalationReason);
        // slot held (not released), downstream not advanced, workflow still RUNNING
        assertEquals(0, mgr.availableSlotsForTest());
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void closingUnknownOrTerminalTabIsNoOp() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-tabclose-noop");
        SupervisorWorkflowManager mgr = startedDiamond(tmp);
        WorkflowExecution e = mgr.execForTest();

        // unknown window → no node → no change
        mgr.onNodeTabClosed("win-ghost");
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);

        // node done → closing its tab must NOT revert it to WAITING_HUMAN
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "done", null);
        mgr.onNodeTabClosed("win-A");
        assertEquals(NodeStatus.DONE, e.nodes.get("A").status);
    }

    @Test
    public void closingTabIsIdempotentAfterWaitingHuman() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-tabclose-idem");
        SupervisorWorkflowManager mgr = startedDiamond(tmp);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeTabClosed("win-A");
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        // second close (e.g. daemon-died after dispose) is a no-op
        mgr.onNodeTabClosed("win-A");
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("tab 被关闭", e.nodes.get("A").escalationReason);
    }

    // ─── IDE restart recovery (§17 / D17) ───────────────────────────────

    @Test
    public void staleRunningExecutionMarkedAbortedOnStartup() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-recover");
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());

        // Persist a stale RUNNING execution (as if the IDE crashed mid-run).
        WorkflowExecution stale = new WorkflowExecution();
        stale.workflowId = "wf1";
        stale.state = WorkflowState.RUNNING;
        stale.concurrency = 2;
        NodeRuntime a = new NodeRuntime(); a.status = NodeStatus.DONE;
        NodeRuntime b = new NodeRuntime(); b.status = NodeStatus.RUNNING;
        NodeRuntime c = new NodeRuntime(); c.status = NodeStatus.PENDING;
        stale.nodes.put("A", a); stale.nodes.put("B", b); stale.nodes.put("C", c);
        store.saveExecution("wf1", stale);

        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);

        mgr.recoverStaleExecutionsOnStartup();

        WorkflowExecution recovered = store.loadExecution("wf1");
        assertNotNull(recovered);
        assertEquals(WorkflowState.ABORTED, recovered.state);
        // DONE preserved; non-terminal → ABORTED
        assertEquals(NodeStatus.DONE, recovered.nodes.get("A").status);
        assertEquals(NodeStatus.ABORTED, recovered.nodes.get("B").status);
        assertEquals(NodeStatus.ABORTED, recovered.nodes.get("C").status);
    }

    @Test
    public void completedExecutionUntouchedByRecovery() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-recover-done");
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());

        WorkflowExecution done = new WorkflowExecution();
        done.workflowId = "wf1";
        done.state = WorkflowState.COMPLETED;
        NodeRuntime a = new NodeRuntime(); a.status = NodeStatus.DONE;
        done.nodes.put("A", a);
        store.saveExecution("wf1", done);

        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);

        mgr.recoverStaleExecutionsOnStartup();

        WorkflowExecution after = store.loadExecution("wf1");
        assertEquals(WorkflowState.COMPLETED, after.state);
        assertEquals(NodeStatus.DONE, after.nodes.get("A").status);
    }

    // ─── DN9 daemon-died callback chain (§16.5) ─────────────────────────

    @Test
    public void daemonDiedListenerRoutesToOnNodeDaemonDown() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-dn9");
        SupervisorWorkflowManager mgr = startedDiamond(tmp);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);

        // Wire EXACTLY as PairSessionManager.startPair does: register a lifecycle
        // listener that calls onNodeDaemonDown, then simulate the daemon dying.
        FakeBridge bridge = new FakeBridge();
        bridge.setLifecycleListener(new IBridge.DaemonLifecycleListener() {
            @Override public void onDaemonReady() { }
            @Override public void onDaemonDied() { mgr.onNodeDaemonDown("win-A", "pair-A", "远程 daemon 崩溃"); }
        });
        bridge.fireDaemonDied();   // RemoteBridge gateway_error / SSE-close path

        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("远程 daemon 崩溃", e.nodes.get("A").escalationReason);
        assertEquals(0, mgr.availableSlotsForTest());   // slot held (failNodeToHuman)
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void daemonDiedForNonWorkflowPairIsNoOp() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p5-dn9-nonwf");
        SupervisorWorkflowManager mgr = startedDiamond(tmp);
        WorkflowExecution e = mgr.execForTest();

        FakeBridge bridge = new FakeBridge();
        bridge.setLifecycleListener(new IBridge.DaemonLifecycleListener() {
            @Override public void onDaemonReady() { }
            @Override public void onDaemonDied() { mgr.onNodeDaemonDown("win-ghost", "pair-ghost", "远程 daemon 崩溃"); }
        });
        bridge.fireDaemonDied();

        // No workflow node matched → nothing changes.
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }
}
