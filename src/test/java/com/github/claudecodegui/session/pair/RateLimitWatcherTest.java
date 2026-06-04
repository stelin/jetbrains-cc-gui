package com.github.claudecodegui.session.pair;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the session-limit reset-time parser — the format-sensitive part of the
 * rate-limit auto-resume watchdog ({@link RateLimitWatcher}).
 */
public class RateLimitWatcherTest {

    @Test
    public void parsesTheClaudeCodeSessionLimitMessage() {
        Instant i = RateLimitWatcher.parseResetInstant(
                "You've hit your session limit - resets 10:20am (America/Los_Angeles)");
        assertNotNull(i);
        ZonedDateTime z = i.atZone(ZoneId.of("America/Los_Angeles"));
        assertEquals(10, z.getHour());
        assertEquals(20, z.getMinute());
        assertTrue("reset must be the next occurrence (future)", i.isAfter(Instant.now()));
    }

    @Test
    public void parsesPmAndMissingMinutes() {
        Instant pm = RateLimitWatcher.parseResetInstant("resets 3pm (UTC)");
        assertNotNull(pm);
        ZonedDateTime z = pm.atZone(ZoneId.of("UTC"));
        assertEquals(15, z.getHour());
        assertEquals(0, z.getMinute());
    }

    @Test
    public void handles12HourBoundaries() {
        Instant midnight = RateLimitWatcher.parseResetInstant("resets 12:00am (UTC)");
        Instant noon = RateLimitWatcher.parseResetInstant("resets 12:30pm (UTC)");
        assertNotNull(midnight);
        assertNotNull(noon);
        assertEquals(0, midnight.atZone(ZoneId.of("UTC")).getHour());
        assertEquals(12, noon.atZone(ZoneId.of("UTC")).getHour());
        assertEquals(30, noon.atZone(ZoneId.of("UTC")).getMinute());
    }

    @Test
    public void returnsNullForNonLimitTextNullAndBadZone() {
        assertNull(RateLimitWatcher.parseResetInstant(null));
        assertNull(RateLimitWatcher.parseResetInstant("normal supervisor output, nothing wrong"));
        assertNull(RateLimitWatcher.parseResetInstant("I will reset the config and continue"));
        assertNull(RateLimitWatcher.parseResetInstant("resets 10:20am (Not/AZone)"));
        assertNull(RateLimitWatcher.parseResetInstant("resets 25:99am (UTC)"));
    }
}
