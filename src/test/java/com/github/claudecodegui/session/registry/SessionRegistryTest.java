package com.github.claudecodegui.session.registry;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * White-box tests for {@link SessionRegistry} (session-kind refactor S1 / M1).
 * Uses the package-private explicit-root constructor + a temp dir, so no
 * IntelliJ Project fixture is needed and nothing on disk outside the temp dir
 * is touched.
 */
public class SessionRegistryTest {

    private Path root;
    private SessionRegistry reg;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("session-registry-test");
        reg = new SessionRegistry("proj-hash", root);
    }

    @Test
    public void register_then_get_roundTrips() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "My supervised", "agent-1");
        assertNotNull(cid);

        SessionManifest m = reg.get(cid);
        assertNotNull(m);
        assertEquals(cid, m.containerId);
        assertEquals(SessionKind.SUPERVISED, m.kind);
        assertEquals("My supervised", m.title);
        assertEquals("agent-1", m.agentId);
        assertEquals("proj-hash", m.projectHash);
        assertEquals("active", m.status);
        assertTrue(m.createdAt > 0);

        // manifest.json must exist immediately (born-at-birth, before any daemon).
        assertTrue(Files.isRegularFile(reg.containerDir(cid).resolve("manifest.json")));
    }

    @Test
    public void bindMainSession_dedupes_and_keeps_full_set() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "s", "a");

        reg.bindMainSession(cid, "sid-1");
        reg.bindMainSession(cid, "sid-1");   // duplicate → no-op append
        reg.bindMainSession(cid, "sid-2");   // re-run → new pointer, keep history

        SessionManifest m = reg.get(cid);
        assertEquals("sid-2", m.mainSessionId);               // pointer = latest
        assertEquals(2, m.mainSessionIds.size());             // full owned set, deduped
        assertTrue(m.mainSessionIds.contains("sid-1"));
        assertTrue(m.mainSessionIds.contains("sid-2"));
    }

    @Test
    public void listByKind_supervised_excludes_workflow_children() {
        String wf = reg.register(SessionKind.WORKFLOW, null, "wf", null, "wf-fixed-id");
        String childA = reg.register(SessionKind.SUPERVISED, wf, "nodeA", "a");
        String childB = reg.register(SessionKind.SUPERVISED, wf, "nodeB", "b");
        String topSup = reg.register(SessionKind.SUPERVISED, null, "top", "c");

        List<SessionManifest> supervised = reg.listByKind(SessionKind.SUPERVISED);
        assertEquals(1, supervised.size());
        assertEquals(topSup, supervised.get(0).containerId);  // children hidden

        List<SessionManifest> workflows = reg.listByKind(SessionKind.WORKFLOW);
        assertEquals(1, workflows.size());
        assertEquals(wf, workflows.get(0).containerId);

        // Parent tracks its children; listChildren returns both.
        assertEquals(2, reg.listChildren(wf).size());
        assertTrue(reg.get(wf).childContainerIds.contains(childA));
        assertTrue(reg.get(wf).childContainerIds.contains(childB));
    }

    @Test
    public void workflow_fixedId_is_stable_and_idempotent() {
        String first = reg.register(SessionKind.WORKFLOW, null, "wf", null, "wf-1");
        String second = reg.register(SessionKind.WORKFLOW, null, "wf renamed", null, "wf-1");
        assertEquals("wf-1", first);
        assertEquals(first, second);
        assertEquals(1, reg.listByKind(SessionKind.WORKFLOW).size());
        assertEquals("wf-1", reg.get("wf-1").workflowId);
    }

    @Test
    public void claimedMainSessionIds_is_union_of_full_sets() {
        String a = reg.register(SessionKind.SUPERVISED, null, "a", null);
        String b = reg.register(SessionKind.SUPERVISED, null, "b", null);
        reg.bindMainSession(a, "m1");
        reg.bindMainSession(a, "m2");   // a re-ran
        reg.bindMainSession(b, "m3");

        Set<String> claimed = reg.claimedMainSessionIds();
        assertEquals(3, claimed.size());
        assertTrue(claimed.contains("m1"));   // prior run still claimed → no normal-tab leak
        assertTrue(claimed.contains("m2"));
        assertTrue(claimed.contains("m3"));
    }

    @Test
    public void delete_removes_dir_and_cache_entry() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "s", "a");
        Path dir = reg.containerDir(cid);
        assertTrue(Files.isDirectory(dir));

        reg.delete(cid);

        assertNull(reg.get(cid));
        assertFalse(Files.exists(dir));
    }

    @Test
    public void close_marks_status_closed() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "s", "a");
        reg.close(cid);
        assertEquals("closed", reg.get(cid).status);
    }

    @Test
    public void manifests_survive_a_fresh_instance_via_disk_scan() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "persisted", "a");
        reg.bindMainSession(cid, "sid-x");
        reg.setSupervisorSession(cid, "sup-x", 2);

        // A new registry over the same root must lazy-load from disk.
        SessionRegistry reloaded = new SessionRegistry("proj-hash", root);
        SessionManifest m = reloaded.get(cid);
        assertNotNull(m);
        assertEquals("persisted", m.title);
        assertEquals("sid-x", m.mainSessionId);
        assertEquals("sup-x", m.supervisorSessionId);
        assertEquals(Integer.valueOf(2), m.supervisorGeneration);
    }

    @Test
    public void register_isolates_by_project_hash() {
        String cid = reg.register(SessionKind.SUPERVISED, null, "s", "a");
        // Different project hash over the same root sees nothing.
        SessionRegistry other = new SessionRegistry("other-hash", root);
        assertNull(other.get(cid));
        assertTrue(other.listByKind(SessionKind.SUPERVISED).isEmpty());
    }
}
