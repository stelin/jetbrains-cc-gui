package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.pair.plan.PlanStep;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P2 tests for {@link SupervisorWorkflowManager}: the DN2 watchdog backstop
 * (WAITING → WAITING_HUMAN, with the user-pause exception), DN9 daemon-down
 * funnel, escalation idempotency, and the production node-report path that the
 * ActionRouter {@code complete_workflow_node} case drives via
 * {@link WorkflowActionParser} (coding-plan §8.6 / §16.5 / §12.1).
 */
public class SupervisorWorkflowManagerP2Test {

    // ─── test doubles (mirror P1 test harness) ──────────────────────────

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
            String windowId = "win-" + node.name;
            String pairId = "pair-" + node.name;
            Path pairDir = tmp.resolve("pairs").resolve(node.name);
            try { Files.createDirectories(pairDir); } catch (IOException ignored) { }
            sink.tabCreated(windowId, new NodeHandle(windowId, pairDir));
            sink.pairStarted(pairId, pairDir);
        }
        @Override public void stop(String windowId) { }
        @Override public void focus(NodeHandle handle) { }
        @Override public void openReport(String reportPath) { }
    }

    private static final class CapturingSink implements HandlerContext.JsCallback {
        final List<String> fns = new ArrayList<>();
        @Override public void callJavaScript(String fn, String... args) { fns.add(fn); }
        @Override public String escapeJs(String s) { return s; }
        int countOf(String fn) { return (int) fns.stream().filter(fn::equals).count(); }
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

    private SupervisorWorkflowManager startedDiamond(Path tmp, CapturingSink sink) {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(diamond());
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);
        if (sink != null) mgr.registerSink(sink);
        mgr.startWorkflow("wf1");
        return mgr;
    }

    private static PlanStateMachine activePlan(String pairId) {
        PlanStateMachine sm = new PlanStateMachine(pairId);
        sm.start();
        sm.onPlanCreated(new ArrayList<PlanStep>(), new HashMap<>()); // → ACTIVE
        return sm;
    }

    // ─── isWatchdogStall predicate ──────────────────────────────────────

    @Test
    public void watchdogStallPredicate() {
        Plan waiting = new Plan();
        waiting.state = Plan.PlanState.WAITING;
        assertTrue("plain WAITING is a stall", SupervisorWorkflowManager.isWatchdogStall(waiting));

        Plan userPaused = new Plan();
        userPaused.state = Plan.PlanState.WAITING;
        userPaused.metadata = new LinkedHashMap<>();
        userPaused.metadata.put("pauseReason", "user");
        assertFalse("user pause is NOT a stall",
                SupervisorWorkflowManager.isWatchdogStall(userPaused));

        Plan escalated = new Plan();
        escalated.state = Plan.PlanState.WAITING;
        escalated.metadata = new LinkedHashMap<>();
        escalated.metadata.put("escalationReason", "supervisor gave up");
        assertTrue("escalation WAITING is a stall",
                SupervisorWorkflowManager.isWatchdogStall(escalated));

        Plan active = new Plan();
        active.state = Plan.PlanState.ACTIVE;
        assertFalse(SupervisorWorkflowManager.isWatchdogStall(active));

        Plan done = new Plan();
        done.state = Plan.PlanState.DONE;
        assertFalse(SupervisorWorkflowManager.isWatchdogStall(done));

        assertFalse(SupervisorWorkflowManager.isWatchdogStall(null));
    }

    // ─── DN2 watchdog backstop end-to-end ───────────────────────────────

    @Test
    public void watchdogEscalationFunnelsNodeToWaitingHuman() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-watchdog");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);

        // Wire the backstop to a real plan SM (as startNode does for self-pairs).
        PlanStateMachine sm = activePlan("pair-A");
        mgr.attachWatchdogBackstop(sm, "A");

        // DeadlockGuard-style escalation drives the plan to WAITING (non-user).
        sm.onEscalatedToHuman("supervisor unresponsive");

        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("supervisor unresponsive", e.nodes.get("A").escalationReason);
        // Slot NOT released (D11) and downstream not advanced.
        assertEquals(0, mgr.availableSlotsForTest());
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void watchdogIgnoresUserInitiatedPause() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-userpause");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();

        PlanStateMachine sm = activePlan("pair-A");
        mgr.attachWatchdogBackstop(sm, "A");

        sm.onUserPaused(); // healthy user pause → metadata.pauseReason = "user"

        // Node must stay RUNNING — a user pause is not a watchdog stall.
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void watchdogIsIdempotentWithExplicitBlockedReport() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-idem");
        CapturingSink sink = new CapturingSink();
        SupervisorWorkflowManager mgr = startedDiamond(tmp, sink);
        WorkflowExecution e = mgr.execForTest();

        PlanStateMachine sm = activePlan("pair-A");
        mgr.attachWatchdogBackstop(sm, "A");

        // Watchdog funnels first (1 escalation).
        sm.onEscalatedToHuman("watchdog reason");
        int escalationsAfterWatchdog = sink.countOf("window.onWorkflowEscalation");
        assertEquals(1, escalationsAfterWatchdog);

        // Then the explicit blocked report arrives — must refresh the reason but
        // NOT fire a second escalation.
        mgr.onNodeReport("pair-A", NodeStatus.WAITING_HUMAN, "explicit blocked reason", null);
        assertEquals(1, sink.countOf("window.onWorkflowEscalation"));
        assertEquals("explicit blocked reason", e.nodes.get("A").escalationReason);
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
    }

    // ─── DN9 daemon-down funnel ─────────────────────────────────────────

    @Test
    public void daemonDownByPairIdFunnelsNode() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-daemon-pair");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeDaemonDown(null, "pair-A", "远程 daemon 崩溃");
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("远程 daemon 崩溃", e.nodes.get("A").escalationReason);
        // failNodeToHuman does NOT free the slot.
        assertEquals(0, mgr.availableSlotsForTest());
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    @Test
    public void daemonDownByWindowIdResolvesNodeAndUsesDefaultReason() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-daemon-win");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeDaemonDown("win-B", null, null);
        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("B").status);
        assertEquals("远程 daemon 崩溃", e.nodes.get("B").escalationReason);
    }

    @Test
    public void daemonDownForUnknownNodeIsNoOp() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-daemon-unknown");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();

        mgr.onNodeDaemonDown("win-ghost", "pair-ghost", "boom");
        // Nothing changed.
        assertEquals(NodeStatus.RUNNING, e.nodes.get("A").status);
        assertEquals(NodeStatus.RUNNING, e.nodes.get("B").status);
        assertEquals(WorkflowState.RUNNING, e.state);
    }

    // ─── ActionRouter production path: parser → onNodeReport ─────────────

    @Test
    public void parserDrivenDoneReportClosesLoop() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-router-done");
        SupervisorWorkflowManager mgr = startedDiamond(tmp, null);
        WorkflowExecution e = mgr.execForTest();

        // Simulate ActionRouter: parse the daemon payload, then onNodeReport.
        JsonObject payload = new JsonObject();
        payload.addProperty("node_status", "done");
        payload.addProperty("summary", "A finished");
        com.google.gson.JsonArray files = new com.google.gson.JsonArray();
        files.add("x.java");
        payload.add("changed_files", files);

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(payload);
        mgr.onNodeReport("pair-A", r.status, r.summary, r.changedFiles);

        assertEquals(NodeStatus.DONE, e.nodes.get("A").status);
        assertNotNull(e.nodes.get("A").completionReportPath);
        assertTrue(e.nodes.get("A").completionReportPath.endsWith("COMPLETION_REPORT.md"));
    }

    @Test
    public void parserDrivenBlockedReportEscalates() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p2-router-blocked");
        CapturingSink sink = new CapturingSink();
        SupervisorWorkflowManager mgr = startedDiamond(tmp, sink);
        WorkflowExecution e = mgr.execForTest();

        JsonObject payload = new JsonObject();
        payload.addProperty("node_status", "blocked");
        payload.addProperty("summary", "need a human");

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(payload);
        mgr.onNodeReport("pair-A", r.status, r.summary, r.changedFiles);

        assertEquals(NodeStatus.WAITING_HUMAN, e.nodes.get("A").status);
        assertEquals("need a human", e.nodes.get("A").escalationReason);
        assertTrue(sink.countOf("window.onWorkflowEscalation") >= 1);
        // Slot held, downstream not advanced.
        assertEquals(0, mgr.availableSlotsForTest());
        assertEquals(NodeStatus.PENDING, e.nodes.get("C").status);
    }
}
