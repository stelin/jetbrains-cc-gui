package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P3 tests for {@link SupervisorWorkflowManager#assembleAndWritePlan} (coding-plan
 * §13 / §26.3): the effective plan = node task + {@code ## 上游产出} (upstream
 * COMPLETION_REPORT summary + changed-files) + the {@code [工作流编排]} footer
 * instructing the supervisor to emit {@code complete_workflow_node} (DN5).
 *
 * <p>The scheduler is synchronous and the launcher is faked, so driving a node
 * to DONE populates {@code handles[dep].pairDir} + {@code NodeRuntime[dep]}
 * exactly as production would, letting us assemble a downstream node's plan and
 * assert its contents off the real on-disk reports.
 */
public class AssembleAndWritePlanTest {

    private static SupervisorWorkflowManager.WorkflowScheduler directScheduler() {
        return new SupervisorWorkflowManager.WorkflowScheduler() {
            @Override public void submit(Runnable task) { task.run(); }
            @Override public void shutdown() { }
        };
    }

    /** Fake launcher: pairDir = {@code <tmp>/pairs/<name>}, synchronous tab+pair. */
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

    private static SupervisorWorkflowManager managerFor(Path tmp, WorkflowDefinition def) {
        WorkflowStore store = new WorkflowStore(tmp.resolve("store"));
        store.save(def);
        Set<String> known = new HashSet<>(Arrays.asList("sup"));
        SupervisorWorkflowManager mgr = new SupervisorWorkflowManager(
                null, store, new FakeLauncher(tmp), directScheduler(), () -> known, () -> 3);
        mgr.startWorkflow(def.id);
        return mgr;
    }

    private static WorkflowDefinition def(String id, WorkflowNode... nodes) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = id;
        d.name = id;
        d.maxConcurrency = 2;
        d.nodes = new ArrayList<>(Arrays.asList(nodes));
        return d;
    }

    private static void writeReport(Path tmp, String node, String body) throws IOException {
        Path dir = tmp.resolve("pairs").resolve(node);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("COMPLETION_REPORT.md"), body);
    }

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    public void injectsSingleUpstreamSummaryAndChangedFiles() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-single");
        WorkflowDefinition d = def("wf1", node("A"), node("B", "A"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        writeReport(tmp, "A", "A built the API layer.\nEndpoints: /foo /bar.");
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A summary", Arrays.asList("Api.java", "Router.java"));

        Path planB = mgr.assembleAndWritePlan(node("B", "A"));
        String c = Files.readString(planB);

        assertTrue("own task present", c.contains("task B"));
        assertTrue("upstream section header", c.contains("## 上游产出"));
        assertTrue("dep node + supervisor header", c.contains("### A（监督者：sup）"));
        assertTrue("upstream report summary", c.contains("A built the API layer."));
        assertTrue("changed files line", c.contains("改动文件：Api.java, Router.java"));
        assertTrue("footer present", c.contains("[工作流编排]"));
        assertTrue("footer names this node", c.contains("「B」"));
        assertTrue("footer tool name", c.contains("complete_workflow_node"));
    }

    @Test
    public void injectsBothUpstreamsForJoinNode() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-join");
        WorkflowDefinition d = def("wf1", node("A"), node("B"), node("C", "A", "B"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        writeReport(tmp, "A", "A did the backend.");
        writeReport(tmp, "B", "B did the frontend.");
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A done", Arrays.asList("a.java"));
        mgr.onNodeReport("pair-B", NodeStatus.DONE, "B done", Arrays.asList("b.tsx"));

        Path planC = mgr.assembleAndWritePlan(node("C", "A", "B"));
        String c = Files.readString(planC);

        assertTrue(c.contains("### A（监督者：sup）"));
        assertTrue(c.contains("A did the backend."));
        assertTrue(c.contains("改动文件：a.java"));
        assertTrue(c.contains("### B（监督者：sup）"));
        assertTrue(c.contains("B did the frontend."));
        assertTrue(c.contains("改动文件：b.tsx"));
        // both upstream blocks come before the footer
        assertTrue(c.indexOf("### A") < c.indexOf("[工作流编排]"));
        assertTrue(c.indexOf("### B") < c.indexOf("[工作流编排]"));
    }

    @Test
    public void footerAlwaysPresentWithNodeNameAndBothStatuses() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-footer");
        WorkflowDefinition d = def("wf1", node("Solo"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        Path plan = mgr.assembleAndWritePlan(node("Solo"));
        String c = Files.readString(plan);

        assertTrue(c.contains("[工作流编排]"));
        assertTrue(c.contains("「Solo」"));
        assertTrue(c.contains("node_status=\"done\""));
        assertTrue(c.contains("node_status=\"blocked\""));
        assertTrue(c.contains("emit_action(action=\"complete_workflow_node\""));
    }

    @Test
    public void rootNodeHasNoUpstreamSection() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-root");
        WorkflowDefinition d = def("wf1", node("A"), node("B", "A"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        // A has no dependencies → no "## 上游产出" section, but footer + own task.
        Path planA = mgr.assembleAndWritePlan(node("A"));
        String c = Files.readString(planA);

        assertTrue(c.contains("task A"));
        assertFalse("root node must not get an upstream section", c.contains("## 上游产出"));
        assertTrue(c.contains("[工作流编排]"));
        assertTrue(c.contains("「A」"));
    }

    @Test
    public void degradesGracefullyWhenUpstreamReportMissing() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-noreport");
        WorkflowDefinition d = def("wf1", node("A"), node("B", "A"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        // A done but NO COMPLETION_REPORT.md written; changedFiles known.
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A done", Arrays.asList("only.java"));

        Path planB = mgr.assembleAndWritePlan(node("B", "A"));
        String c = Files.readString(planB);

        assertTrue("missing report degrades, not throws", c.contains("（无完成报告）"));
        assertTrue("still lists changed files", c.contains("改动文件：only.java"));
        assertTrue(c.contains("### A（监督者：sup）"));
    }

    @Test
    public void changedFilesShowsNoneWhenAbsent() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-nofiles");
        WorkflowDefinition d = def("wf1", node("A"), node("B", "A"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        writeReport(tmp, "A", "A finished, no file list provided.");
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A done", null); // no changed files

        Path planB = mgr.assembleAndWritePlan(node("B", "A"));
        String c = Files.readString(planB);

        assertTrue(c.contains("A finished, no file list provided."));
        assertTrue(c.contains("改动文件：无"));
    }

    @Test
    public void truncatesOversizedUpstreamReport() throws IOException {
        Path tmp = Files.createTempDirectory("wf-p3-trunc");
        WorkflowDefinition d = def("wf1", node("A"), node("B", "A"));
        SupervisorWorkflowManager mgr = managerFor(tmp, d);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < SupervisorWorkflowManager.UPSTREAM_REPORT_CAP + 500; i++) big.append('x');
        writeReport(tmp, "A", big.toString());
        mgr.onNodeReport("pair-A", NodeStatus.DONE, "A done", Arrays.asList("a.java"));

        Path planB = mgr.assembleAndWritePlan(node("B", "A"));
        String c = Files.readString(planB);
        assertTrue("oversized report is truncated", c.contains("报告已截断"));
    }
}
