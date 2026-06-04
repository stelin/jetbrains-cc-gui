package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.intellij.openapi.diagnostic.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-pair "quota reset" auto-resume watchdog. When a supervisor turn is cut
 * short because the Claude Code account hit a limit, this schedules a single
 * follow-up message to the supervisor once the limit should be cleared — so a
 * (workflow or plain) supervisor picks its task back up on its own.
 *
 * <h3>Two cases (same fixed resume message)</h3>
 * <ul>
 *   <li><b>Session limit</b> ("You've hit your session limit - resets 10:20am
 *       (America/Los_Angeles)"): resume just after the parsed reset instant.</li>
 *   <li><b>429 / rate_limit</b>: resume after an exponential backoff — 5, 10, 20,
 *       40, 60 minutes, then capped at 60min/try.</li>
 * </ul>
 *
 * <p>{@link #onNormalTurn()} (any non-limited turn) CLEARS all state, so the 429
 * backoff self-heals once the supervisor is working again. Scoped to supervisor
 * pairs only (a plain main-AI chat has no pair → no watcher). In-memory only:
 * a pending resume is intentionally lost on IDE restart.
 *
 * <p>Workflow: a rate-limited node stays {@code RUNNING} — the engine's DN2
 * watchdog checks {@link #isWaitingForReset()} and skips escalating while a
 * resume is pending.
 */
public final class RateLimitWatcher {

    private static final Logger LOG = Logger.getInstance(RateLimitWatcher.class);

    /** 429 backoff (minutes): 5 → 10 → 20 → 40 → 60, then capped at the last. */
    private static final int[] BACKOFF_MIN = {5, 10, 20, 40, 60};

    /** Fixed resume prompt re-sent to the supervisor after the limit clears. */
    private static final String RESUME_PROMPT =
            "上一轮因额度限制中断，现已可继续。请先检查上一步任务的执行情况，再继续推进任务。";

    /** Small cushion after a session-limit reset instant before resuming. */
    private static final long RESET_CUSHION_MS = 5_000L;

    /** e.g. "resets 10:20am (America/Los_Angeles)" / "reset at 10 AM (UTC)". */
    private static final Pattern RESET_PATTERN = Pattern.compile(
            "reset(?:s|ting)?\\s+(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*([ap])m\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private final PairSession pair;
    private final Object lock = new Object();
    private volatile ScheduledExecutorService scheduler;   // lazy: created on first schedule
    private ScheduledFuture<?> pending;
    private int retry429;

    public RateLimitWatcher(PairSession pair) {
        this.pair = pair;
    }

    /**
     * True while an auto-resume is scheduled. The workflow DN2 watchdog reads
     * this to keep a rate-limited node {@code RUNNING} (legitimately waiting for
     * the quota reset) instead of funnelling it to {@code WAITING_HUMAN}.
     */
    public boolean isWaitingForReset() {
        synchronized (lock) {
            return pending != null && !pending.isDone();
        }
    }

    /**
     * Classify a completed supervisor turn from its natural text. A session-limit
     * message (with a parseable reset time) schedules a resume at that time;
     * anything else is a normal turn → {@link #onNormalTurn()}.
     */
    public void onSupervisorTurnText(String naturalText) {
        Instant reset = parseResetInstant(naturalText);
        if (reset != null) {
            onSessionLimit(reset);
        } else {
            onNormalTurn();
        }
    }

    /** Session limit with a known reset instant → resume just after it. */
    public void onSessionLimit(Instant resetInstant) {
        if (resetInstant == null) return;
        long delay = Math.max(0, Duration.between(Instant.now(), resetInstant).toMillis()) + RESET_CUSHION_MS;
        synchronized (lock) {
            LOG.info("[RateLimit] pair " + pair.getPairId() + " session-limit; resume at " + resetInstant
                    + " (in " + (delay / 1000) + "s)");
            scheduleLocked(delay);
        }
    }

    /** 429 / rate_limit → resume after the exponential backoff delay. */
    public void on429() {
        synchronized (lock) {
            int min = retry429 < BACKOFF_MIN.length ? BACKOFF_MIN[retry429] : BACKOFF_MIN[BACKOFF_MIN.length - 1];
            retry429++;
            LOG.info("[RateLimit] pair " + pair.getPairId() + " 429 #" + retry429 + " → resume in " + min + "min");
            scheduleLocked(min * 60_000L);
        }
    }

    /** A normal (non-limited) turn → clear all state. The 429 backoff self-heals. */
    public void onNormalTurn() {
        synchronized (lock) {
            if (retry429 != 0 || pending != null) {
                LOG.info("[RateLimit] pair " + pair.getPairId() + " recovered — clearing rate-limit state");
            }
            retry429 = 0;
            cancelLocked();
        }
    }

    /** User manually sent a message → cancel any pending auto-resume. */
    public void onUserSend() {
        synchronized (lock) {
            cancelLocked();
        }
    }

    public void dispose() {
        synchronized (lock) {
            cancelLocked();
        }
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
    }

    // ── internals (callers hold `lock` for schedule/cancel) ──────────────

    private void scheduleLocked(long delayMs) {
        cancelLocked();
        pending = scheduler().schedule(this::fireResume, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void cancelLocked() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = scheduler;
        if (s == null) {
            s = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limit-watcher-" + pair.getPairId());
                t.setDaemon(true);
                return t;
            });
            scheduler = s;
        }
        return s;
    }

    private void fireResume() {
        synchronized (lock) {
            pending = null;
        }
        if (pair.isDisposed()) return;
        try {
            // Flip WAITING(non-user) → ACTIVE so the resume turn runs against an
            // active plan (publishUserInput only auto-resumes USER pauses).
            PlanStateMachine sm = pair.getPlanStateMachine();
            if (sm != null) sm.onRateLimitResumed();
            EventBus bus = pair.getEventBus();
            if (bus != null) {
                bus.publishUserInput(RESUME_PROMPT);
                LOG.info("[RateLimit] pair " + pair.getPairId() + " auto-resumed");
            }
        } catch (Exception e) {
            LOG.warn("[RateLimit] resume failed for pair " + pair.getPairId() + ": " + e.getMessage());
        }
    }

    /**
     * Parse "resets 10:20am (America/Los_Angeles)" → the NEXT occurrence of that
     * wall-clock time in that zone, as an {@link Instant}. Null when the text has
     * no recognisable reset clause (→ treated as a normal turn).
     */
    static Instant parseResetInstant(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = RESET_PATTERN.matcher(text);
        if (!m.find()) return null;
        try {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            boolean pm = "p".equalsIgnoreCase(m.group(3));
            String tz = m.group(4).trim();
            if (pm && hour != 12) hour += 12;
            if (!pm && hour == 12) hour = 0;
            if (hour > 23 || minute > 59) return null;
            ZoneId zone = ZoneId.of(tz);
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime reset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            if (!reset.isAfter(now)) reset = reset.plusDays(1);   // next occurrence
            return reset.toInstant();
        } catch (Exception e) {
            return null;   // unknown zone / malformed → ignore
        }
    }
}
