package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link WorkflowStore} (coding-plan §10 / §22): atomic write,
 * round-trip load, loadAll, delete, and node-name filesystem safety.
 */
public class WorkflowStoreTest {

    private static WorkflowDefinition def(String id, String name) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = id;
        d.name = name;
        d.maxConcurrency = 2;
        WorkflowNode n = new WorkflowNode();
        n.name = "A";
        n.supervisorId = "sup";
        n.plan = "do the thing";
        d.nodes.add(n);
        d.updatedAt = 123L;
        return d;
    }

    @Test
    public void savesAndLoadsRoundTrip() throws IOException {
        Path root = Files.createTempDirectory("wf-store");
        WorkflowStore store = new WorkflowStore(root);

        WorkflowDefinition d = def("wf1", "First");
        store.save(d);

        WorkflowDefinition loaded = store.load("wf1");
        assertNotNull(loaded);
        assertEquals("wf1", loaded.id);
        assertEquals("First", loaded.name);
        assertEquals(Integer.valueOf(2), loaded.maxConcurrency);
        assertEquals(1, loaded.nodesSafe().size());
        assertEquals("A", loaded.nodes.get(0).name);

        // The committed file exists; the temp file must NOT linger.
        Path defFile = root.resolve("wf1").resolve("definition.json");
        assertTrue(Files.isRegularFile(defFile));
        assertFalse(Files.exists(root.resolve("wf1").resolve("definition.json.tmp")));
    }

    @Test
    public void loadAllReturnsEverySavedWorkflow() throws IOException {
        Path root = Files.createTempDirectory("wf-store-all");
        WorkflowStore store = new WorkflowStore(root);
        store.save(def("wf1", "First"));
        store.save(def("wf2", "Second"));
        store.save(def("wf3", "Third"));

        List<WorkflowDefinition> all = store.loadAll();
        assertEquals(3, all.size());
    }

    @Test
    public void loadAllOnMissingRootReturnsEmpty() {
        WorkflowStore store = new WorkflowStore(java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"), "wf-does-not-exist-" + System.nanoTime()));
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void deleteRemovesWorkflowDirectory() throws IOException {
        Path root = Files.createTempDirectory("wf-store-del");
        WorkflowStore store = new WorkflowStore(root);
        store.save(def("wf1", "First"));
        store.save(def("wf2", "Second"));

        store.delete("wf1");

        assertNull(store.load("wf1"));
        assertFalse(Files.exists(root.resolve("wf1")));
        // Other workflows untouched.
        assertNotNull(store.load("wf2"));
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void overwriteIsAtomicAndReplacesContent() throws IOException {
        Path root = Files.createTempDirectory("wf-store-overwrite");
        WorkflowStore store = new WorkflowStore(root);
        store.save(def("wf1", "Before"));
        store.save(def("wf1", "After"));

        WorkflowDefinition loaded = store.load("wf1");
        assertNotNull(loaded);
        assertEquals("After", loaded.name);
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void executionRoundTrip() throws IOException {
        Path root = Files.createTempDirectory("wf-store-exec");
        WorkflowStore store = new WorkflowStore(root);

        WorkflowExecution exec = new WorkflowExecution();
        exec.workflowId = "wf1";
        exec.state = WorkflowState.RUNNING;
        exec.concurrency = 2;
        NodeRuntime rt = new NodeRuntime();
        rt.status = NodeStatus.RUNNING;
        rt.pairId = "pair-A";
        exec.nodes.put("A", rt);

        store.saveExecution("wf1", exec);
        WorkflowExecution loaded = store.loadExecution("wf1");
        assertNotNull(loaded);
        assertEquals(WorkflowState.RUNNING, loaded.state);
        assertEquals(2, loaded.concurrency);
        assertEquals(NodeStatus.RUNNING, loaded.nodes.get("A").status);
        assertEquals("pair-A", loaded.nodes.get("A").pairId);
    }

    @Test
    public void transientFieldsNotPersisted() throws IOException {
        Path root = Files.createTempDirectory("wf-store-transient");
        WorkflowStore store = new WorkflowStore(root);

        WorkflowExecution exec = new WorkflowExecution();
        exec.workflowId = "wf1";
        exec.state = WorkflowState.RUNNING;
        NodeRuntime rt = new NodeRuntime();
        rt.summary = "secret summary";
        rt.changedFiles = java.util.Arrays.asList("a.txt");
        exec.nodes.put("A", rt);
        store.saveExecution("wf1", exec);

        String json = Files.readString(root.resolve("wf1").resolve("execution.json"));
        assertFalse("transient summary must not serialize", json.contains("secret summary"));
        assertFalse("transient changedFiles must not serialize", json.contains("a.txt"));
    }

    @Test
    public void writeNodePlanSanitizesNodeName() throws IOException {
        Path root = Files.createTempDirectory("wf-store-plan");
        WorkflowStore store = new WorkflowStore(root);

        // Node name with path-hostile characters must not escape the store root.
        Path planPath = store.writeNodePlan("wf1", "a/b:c\\..", "PLAN BODY");
        assertTrue(Files.isRegularFile(planPath));
        assertEquals("PLAN BODY", Files.readString(planPath));
        assertTrue("plan file must stay under the workflow dir",
                planPath.toAbsolutePath().normalize()
                        .startsWith(root.resolve("wf1").toAbsolutePath().normalize()));
    }

    @Test
    public void projectHashIsStableAndFilesystemSafe() {
        String h1 = WorkflowStore.projectHash("/Users/me/proj");
        String h2 = WorkflowStore.projectHash("/Users/me/proj");
        assertEquals(h1, h2);
        assertTrue(h1.matches("[a-f0-9]+"));
        assertEquals("default", WorkflowStore.projectHash(null));
        assertEquals("default", WorkflowStore.projectHash(""));
    }
}
