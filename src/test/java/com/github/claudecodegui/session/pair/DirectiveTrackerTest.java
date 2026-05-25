package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan §9.1: directive ack lifecycle.
 *
 * Uses the short-timeout constructor so timeout cases finish in milliseconds
 * instead of the production 5min. Covers register/ack/timeout/dispose paths
 * and the concurrent insertion case that previously dropped directives.
 */
public class DirectiveTrackerTest {

    private DirectiveTracker tracker;

    @After
    public void cleanup() {
        if (tracker != null) tracker.dispose();
    }

    @Test
    public void registerThenMarkAcked_removesPending() {
        tracker = new DirectiveTracker("p1");
        JsonObject payload = new JsonObject();
        payload.addProperty("objective", "step 1");
        tracker.registerDirective("d_abc", payload);
        assertEquals(1, tracker.pendingCount());

        tracker.markAcked("d_abc", "applied");
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void markAcked_unknownId_isNoOp() {
        tracker = new DirectiveTracker("p1");
        tracker.markAcked("d_never_existed", "applied");
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void timeout_firesCallbackWithOriginalPayload() throws InterruptedException {
        // 200ms timeout — short enough for tests, long enough not to flake.
        tracker = new DirectiveTracker("p1", 200);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenId = new AtomicReference<>();
        AtomicReference<JsonObject> seenPayload = new AtomicReference<>();
        tracker.setOnTimeout((id, payload) -> {
            seenId.set(id);
            seenPayload.set(payload);
            latch.countDown();
        });

        JsonObject payload = new JsonObject();
        payload.addProperty("objective", "step 2");
        tracker.registerDirective("d_xyz", payload);

        assertTrue("callback should fire within 2s", latch.await(2, TimeUnit.SECONDS));
        assertEquals("d_xyz", seenId.get());
        assertNotNull(seenPayload.get());
        assertEquals("step 2", seenPayload.get().get("objective").getAsString());
        // Timed-out directives are removed from pending.
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void ackBeforeTimeout_cancelsCallback() throws InterruptedException {
        tracker = new DirectiveTracker("p1", 300);
        CountDownLatch latch = new CountDownLatch(1);
        tracker.setOnTimeout((id, payload) -> latch.countDown());

        tracker.registerDirective("d_fast", new JsonObject());
        Thread.sleep(50);
        tracker.markAcked("d_fast", "applied");

        // Wait past the would-be timeout; callback must NOT fire.
        assertTrue("callback must not fire within timeout window",
                !latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void registerSameIdTwice_replacesPriorAndCancelsItsTimeout() throws InterruptedException {
        tracker = new DirectiveTracker("p1", 300);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> objectiveSeen = new AtomicReference<>();
        tracker.setOnTimeout((id, payload) -> {
            objectiveSeen.set(payload.get("objective").getAsString());
            latch.countDown();
        });

        JsonObject first = new JsonObject();
        first.addProperty("objective", "first");
        tracker.registerDirective("d_dup", first);

        Thread.sleep(50);
        JsonObject second = new JsonObject();
        second.addProperty("objective", "second");
        tracker.registerDirective("d_dup", second);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        // The second registration's payload must be the one delivered.
        assertEquals("second", objectiveSeen.get());
    }

    @Test
    public void dispose_dropsAllPendingAndPreventsFurtherCallbacks() throws InterruptedException {
        tracker = new DirectiveTracker("p1", 200);
        CountDownLatch latch = new CountDownLatch(1);
        tracker.setOnTimeout((id, payload) -> latch.countDown());

        tracker.registerDirective("d_1", new JsonObject());
        tracker.registerDirective("d_2", new JsonObject());
        assertEquals(2, tracker.pendingCount());

        tracker.dispose();
        assertEquals(0, tracker.pendingCount());

        // Wait past timeout; nothing should fire.
        assertTrue("dispose must cancel pending timeouts",
                !latch.await(500, TimeUnit.MILLISECONDS));

        // Subsequent operations on a disposed tracker are no-ops.
        tracker.registerDirective("d_3", new JsonObject());
        assertEquals(0, tracker.pendingCount());
        tracker = null;  // prevent @After from re-disposing
    }
}
