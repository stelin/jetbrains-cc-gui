package com.github.claudecodegui.session.pair.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link WorkflowActionParser} — the {@code complete_workflow_node}
 * payload parser that the ActionRouter case delegates to (coding-plan §7.1 / §12.1).
 */
public class WorkflowActionParserTest {

    @Test
    public void parsesDoneWithChangedFiles() {
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "done");
        p.addProperty("summary", "built the thing");
        JsonArray files = new JsonArray();
        files.add("a.java");
        files.add("b.java");
        p.add("changed_files", files);

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(NodeStatus.DONE, r.status);
        assertEquals("built the thing", r.summary);
        assertEquals(2, r.changedFiles.size());
        assertTrue(r.changedFiles.contains("a.java"));
        assertTrue(r.changedFiles.contains("b.java"));
    }

    @Test
    public void parsesDoneWithoutChangedFilesYieldsEmptyList() {
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "done");
        p.addProperty("summary", "done, nothing changed");

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(NodeStatus.DONE, r.status);
        assertTrue(r.changedFiles.isEmpty());
    }

    @Test
    public void parsesBlockedWithNullChangedFiles() {
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "blocked");
        p.addProperty("summary", "stuck on missing creds");

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(NodeStatus.WAITING_HUMAN, r.status);
        assertEquals("stuck on missing creds", r.summary);
        assertNull("blocked carries no changed_files", r.changedFiles);
    }

    @Test
    public void blockedIgnoresChangedFilesEvenIfPresent() {
        // Daemon never emits changed_files on blocked, but be defensive.
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "blocked");
        JsonArray files = new JsonArray();
        files.add("a.java");
        p.add("changed_files", files);

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(NodeStatus.WAITING_HUMAN, r.status);
        assertNull(r.changedFiles);
    }

    @Test
    public void missingNodeStatusDefaultsToDone() {
        JsonObject p = new JsonObject();
        p.addProperty("summary", "implicit done");
        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(NodeStatus.DONE, r.status);
        assertEquals("implicit done", r.summary);
    }

    @Test
    public void missingSummaryYieldsEmptyString() {
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "done");
        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals("", r.summary);
    }

    @Test
    public void nullPayloadDefaultsToDoneEmpty() {
        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(null);
        assertEquals(NodeStatus.DONE, r.status);
        assertEquals("", r.summary);
        assertTrue(r.changedFiles.isEmpty());
    }

    @Test
    public void skipsNullEntriesInChangedFiles() {
        JsonObject p = new JsonObject();
        p.addProperty("node_status", "done");
        JsonArray files = new JsonArray();
        files.add("a.java");
        files.add((String) null); // gson renders JsonNull
        p.add("changed_files", files);

        WorkflowActionParser.ParsedNodeReport r = WorkflowActionParser.parse(p);
        assertEquals(1, r.changedFiles.size());
        assertEquals("a.java", r.changedFiles.get(0));
    }
}
