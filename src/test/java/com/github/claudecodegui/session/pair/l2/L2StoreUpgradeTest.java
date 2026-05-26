package com.github.claudecodegui.session.pair.l2;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class L2StoreUpgradeTest {

    private Path tmp;
    private L2Store store;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("l2-store-upgrade-test-");
        store = new L2Store(tmp);
    }

    @After
    public void tearDown() throws IOException {
        if (tmp != null && Files.exists(tmp)) {
            try (Stream<Path> paths = Files.walk(tmp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    public void readingV2File_returnsV3StateWithEmptyContracts() throws IOException {
        String pairId = "pair_v2";
        writeRawStateFile(pairId, makeV2Json(pairId));

        L2State loaded = store.read(pairId);
        assertEquals(L2Schema.CURRENT_VERSION, loaded.schemaVersion);
        assertNull(loaded.plan);
        assertNotNull(loaded.openContracts);
        assertTrue(loaded.openContracts.isEmpty());
    }

    @Test
    public void readingV2_preservesExistingFields() throws IOException {
        String pairId = "pair_v2_keep";
        JsonObject v2 = makeV2Json(pairId);
        v2.addProperty("generation", 4);
        v2.addProperty("rotationCount", 2);
        writeRawStateFile(pairId, v2);

        L2State loaded = store.read(pairId);
        assertEquals(4, loaded.generation);
        assertEquals(2, loaded.rotationCount);
    }

    @Test
    public void update_persistsToDiskWithV3Schema() throws IOException {
        String pairId = "pair_v3_write";
        // First read (no file → fresh L2State at CURRENT_VERSION).
        store.read(pairId);

        // Mutate: add an open contract.
        store.update(pairId, s -> {
            L2State.PersistedContract c = new L2State.PersistedContract();
            c.id = "ctr_1";
            c.parentStepId = "step_1";
            c.type = "TASK_ASSIGNMENT";
            c.assignedTo = "MAIN_AI";
            c.status = "OPEN";
            c.issuedAt = System.currentTimeMillis();
            s.openContracts.add(c);
            return s;
        });

        // Force reload from disk.
        store.invalidateCache(pairId);
        L2State reloaded = store.read(pairId);
        assertEquals(L2Schema.CURRENT_VERSION, reloaded.schemaVersion);
        assertEquals(1, reloaded.openContracts.size());
        assertEquals("ctr_1", reloaded.openContracts.get(0).id);
    }

    @Test
    public void update_persistsPlanField() throws IOException {
        String pairId = "pair_plan_write";
        store.read(pairId);

        store.update(pairId, s -> {
            L2State.PersistedPlan p = new L2State.PersistedPlan();
            p.id = "plan_q";
            p.state = "ACTIVE";
            p.subState = "PENDING_DECISION";
            p.createdAt = 100L;
            p.lastTransitionAt = 200L;
            s.plan = p;
            return s;
        });

        store.invalidateCache(pairId);
        L2State reloaded = store.read(pairId);
        assertNotNull(reloaded.plan);
        assertEquals("plan_q", reloaded.plan.id);
        assertEquals("ACTIVE", reloaded.plan.state);
        assertEquals("PENDING_DECISION", reloaded.plan.subState);
    }

    private JsonObject makeV2Json(String pairId) {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", 2);
        o.addProperty("pairId", pairId);
        o.addProperty("generation", 0);
        o.addProperty("createdAt", 1000L);
        o.addProperty("lastUpdated", 2000L);
        return o;
    }

    private void writeRawStateFile(String pairId, JsonObject content) throws IOException {
        Path pairDir = tmp.resolve(pairId);
        Files.createDirectories(pairDir);
        Path state = pairDir.resolve(L2Schema.STATE_FILE);
        Files.writeString(state, content.toString());
    }
}
