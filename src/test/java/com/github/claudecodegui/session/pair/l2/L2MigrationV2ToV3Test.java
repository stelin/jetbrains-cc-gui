package com.github.claudecodegui.session.pair.l2;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class L2MigrationV2ToV3Test {

    @Test
    public void v2ToV3_addsPlanNullAndEmptyContracts() {
        // v2 JSON shape: no plan, no openContracts, schemaVersion=2.
        JsonObject v2 = new JsonObject();
        v2.addProperty("schemaVersion", 2);
        v2.addProperty("pairId", "pair_test");
        v2.addProperty("generation", 0);
        v2.addProperty("createdAt", 1000L);
        v2.addProperty("lastUpdated", 2000L);

        L2State state = L2State.fromJson(v2.toString());
        assertEquals(2, state.schemaVersion);
        assertNull(state.plan);

        L2State migrated = L2Migration.migrate(state);
        assertEquals(L2Schema.CURRENT_VERSION, migrated.schemaVersion);
        assertEquals(3, migrated.schemaVersion);
        assertNull(migrated.plan);
        assertNotNull(migrated.openContracts);
        assertTrue(migrated.openContracts.isEmpty());
    }

    @Test
    public void v2ToV3_preservesExistingFields() {
        JsonObject v2 = new JsonObject();
        v2.addProperty("schemaVersion", 2);
        v2.addProperty("pairId", "pair_x");
        v2.addProperty("generation", 3);
        v2.addProperty("createdAt", 1000L);
        v2.addProperty("lastUpdated", 2000L);
        v2.addProperty("rotationCount", 2);

        L2State state = L2State.fromJson(v2.toString());
        L2State migrated = L2Migration.migrate(state);
        assertEquals("pair_x", migrated.pairId);
        assertEquals(3, migrated.generation);
        assertEquals(2, migrated.rotationCount);
        assertEquals(1000L, migrated.createdAt);
    }

    @Test
    public void migrate_alreadyV3_isNoOp() {
        L2State state = L2State.initial("pair_q");
        state.schemaVersion = 3;
        state.openContracts.add(new L2State.PersistedContract());
        L2State migrated = L2Migration.migrate(state);
        assertEquals(3, migrated.schemaVersion);
        assertEquals(1, migrated.openContracts.size());
    }

    @Test(expected = L2Validator.L2ValidationException.class)
    public void migrate_unknownOlderVersion_throws() {
        L2State state = L2State.initial("p");
        state.schemaVersion = 1;
        L2Migration.migrate(state);
    }

    @Test(expected = L2Validator.L2ValidationException.class)
    public void migrate_newerThanCurrent_throws() {
        L2State state = L2State.initial("p");
        state.schemaVersion = L2Schema.CURRENT_VERSION + 1;
        L2Migration.migrate(state);
    }
}
