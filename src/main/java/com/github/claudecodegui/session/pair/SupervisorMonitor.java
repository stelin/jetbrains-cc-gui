package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.github.claudecodegui.session.pair.rotation.RotationTriggers;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.RotationConfig;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 1 (2026-05-23): periodic monitor that drives the supervisor turn loop.
 *
 * <p>Replaces the previous "every main-AI event triggers a synchronous
 * supervisor turn" model with "events accumulate in a bounded ring, monitor
 * drains and sends one composite turn every 30s (or sooner on urgent)".
 *
 * <p>Why this exists: the old model serialized main-AI event flow through a
 * single-thread Java dispatcher that blocked on the daemon SDK iteration —
 * a single hung supervisor turn froze every subsequent event. The monitor
 * model decouples production (event sources) from consumption (supervisor
 * turn), with bounded backpressure (ring) and explicit health tracking.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Constructed by {@link PairSessionManager} after EventBus + ActionRouter are wired.</li>
 *   <li>{@link #start()} schedules the first tick after one tick interval.</li>
 *   <li>{@link #stop()} cancels pending future + shuts down scheduler.</li>
 * </ol>
 *
 * <p>Each tick:
 * <ol>
 *   <li>Acquire the pair's read lock (blocks if a rotation is in progress in Phase 4+).</li>
 *   <li>Drain {@link EventCollector}; if empty and not a health-check tick, skip.</li>
 *   <li>Build a {@code composite_summary} event.</li>
 *   <li>Delegate to {@link EventBus#forwardComposite(JsonObject)} so the existing
 *       transport-error + lazy-restart logic is reused.</li>
 *   <li>On timeout / repeated failure, transition health and (eventually) request rotation.</li>
 * </ol>
 */
public class SupervisorMonitor {

    private static final Logger LOG = Logger.getInstance(SupervisorMonitor.class);

    /** Contract State Machine v3 (2026-05-25): the 30s periodic tick is
     *  effectively disabled by passing a 24h interval (see
     *  {@link #DEADLOCK_FRIENDLY_INTERVAL_MS}). The constant is retained for
     *  legacy callers / tests that may construct the monitor directly. */
    public static final long DEFAULT_TICK_INTERVAL_MS = 30_000L;
    public static final long DEFAULT_URGENT_DELAY_MS  = 1_000L;
    /** Effectively disable the periodic tick in production. Supervisor is
     *  now woken on Contract state changes (via
     *  {@link com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher})
     *  and on urgent events (error / off_plan / etc.). The huge interval
     *  exists as a safety-sweep fallback only — should never actually fire. */
    public static final long DEADLOCK_FRIENDLY_INTERVAL_MS = 24L * 3600L * 1000L;
    // 2026-05-25 (FUNDAMENTAL FIX): wall-clock cap REMOVED. Monitor ticks
    // block until EventBus.forwardComposite resolves naturally — which now
    // happens when the daemon delivers the action OR the IPC layer fails OR
    // the user clicks Stop. See EventBus.POST_EVENT_TIMEOUT_SEC JavaDoc for
    // the design rationale. Field retained as documentation anchor; not used.
    @SuppressWarnings("unused")
    public static final long DEFAULT_FORWARD_TIMEOUT_SEC = 0L;

    /** Periodicity (in ticks) for the idle health-check path (Phase 2 uses it). */
    public static final int HEALTH_CHECK_EVERY_N_TICKS = 5;

    private final PairSession pair;
    private final EventCollector collector;
    private final PairCoordinator coordinator;
    private final long tickIntervalMs;
    private final ScheduledExecutorService scheduler;
    /**
     * Phase 5 (2026-05-24): optional. When non-null, the monitor evaluates
     * {@link RotationTriggers} after each context-usage refresh and may set
     * {@link PairCoordinator#requestRotation()} for the decider to pick up.
     * Optional so Phase 1/2 test wiring keeps working without L2.
     */
    private final L2Store l2Store;

    private final AtomicBoolean tickInProgress = new AtomicBoolean(false);
    private final AtomicReference<HealthState> health = new AtomicReference<>(HealthState.HEALTHY);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong lastTickStartMs = new AtomicLong(0);
    private final AtomicLong lastTickEndMs = new AtomicLong(0);
    /**
     * Wall-clock ms of the supervisor's most recent LIVE stream activity (any SDK
     * message / live-usage tick during a turn). A supervisor LLM turn is driven
     * async by the daemon and is NOT a monitor "tick", so without this a supervisor
     * that is actively thinking / calling tools / dispatching reads as idle and the
     * workflow-node liveness watchdog counts up against it (mirrors
     * {@code MainAIMonitor.noteActivity} for the main AI side).
     */
    private final AtomicLong lastStreamActivityMs = new AtomicLong(0);
    /**
     * Set by {@link #onUrgent} when an urgent event arrives WHILE a tick is
     * already running. The running tick's finally block reads + clears this
     * to decide whether the next tick should run on the urgent delay (1s)
     * instead of the normal interval — without this race-window flag the
     * urgent reschedule would be overwritten by the normal finally-block
     * reschedule. See unit test rationale.
     */
    private final AtomicBoolean urgentDuringTick = new AtomicBoolean(false);
    /**
     * Phase 4 (2026-05-24): one-shot text the next tick prepends to the
     * composite_summary so the new-generation supervisor knows it just
     * inherited the runtime. Set by {@code RotationCoordinator}; read and
     * cleared by {@link #doTick}.
     */
    private final AtomicReference<String> pendingGenerationBanner = new AtomicReference<>(null);
    private final Object scheduleLock = new Object();
    private volatile ScheduledFuture<?> nextTickFuture;
    private volatile boolean started = false;
    private volatile boolean stopped = false;
    private volatile long batchStartMs = System.currentTimeMillis();

    public SupervisorMonitor(PairSession pair,
                             EventCollector collector,
                             PairCoordinator coordinator) {
        this(pair, collector, coordinator, DEFAULT_TICK_INTERVAL_MS, null);
    }

    public SupervisorMonitor(PairSession pair,
                             EventCollector collector,
                             PairCoordinator coordinator,
                             long tickIntervalMs) {
        this(pair, collector, coordinator, tickIntervalMs, null);
    }

    /**
     * Phase 5: full constructor with optional L2Store. PairSessionManager
     * uses this so the monitor can read L2's compactionHistory for trigger
     * evaluation and write degraded/unhealthy counters back.
     */
    public SupervisorMonitor(PairSession pair,
                             EventCollector collector,
                             PairCoordinator coordinator,
                             long tickIntervalMs,
                             L2Store l2Store) {
        this.pair = pair;
        this.collector = collector;
        this.coordinator = coordinator;
        this.tickIntervalMs = tickIntervalMs;
        this.l2Store = l2Store;

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "supervisor-monitor-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    /** Begin scheduling ticks. Idempotent (subsequent calls are no-ops). */
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        // Wire the urgent path: collector calls back into us.
        collector.setUrgentWakeup(this::onUrgent);
        LOG.info("[Monitor] start pair=" + pair.getPairId() + " interval=" + tickIntervalMs + "ms");
        scheduleNextTick(tickIntervalMs);
    }

    /** Stop scheduling; cancel pending tick; await brief shutdown. */
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        synchronized (scheduleLock) {
            if (nextTickFuture != null) nextTickFuture.cancel(false);
            nextTickFuture = null;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        LOG.info("[Monitor] stop pair=" + pair.getPairId());
    }

    public HealthState getHealth() { return health.get(); }
    public long getTickCount() { return tickCounter.get(); }
    public boolean isTickInProgress() { return tickInProgress.get(); }
    public long getLastTickStartMs() { return lastTickStartMs.get(); }
    public long getLastTickEndMs() { return lastTickEndMs.get(); }

    /** Record live supervisor stream activity (called per SDK message / usage tick). */
    public void noteStreamActivity() { lastStreamActivityMs.set(System.currentTimeMillis()); }
    /** Wall-clock ms of the supervisor's most recent live stream activity (0 if none). */
    public long getLastStreamActivityMs() { return lastStreamActivityMs.get(); }

    /**
     * Contract State Machine v3 (2026-05-25): bring the next tick forward to
     * ~1ms. Used by {@link com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher}
     * when the plan transitions to PENDING_DECISION so the supervisor sees the
     * accumulated events promptly without waiting for the (now effectively
     * disabled) 30s timer.
     */
    public void wakeForPlanTransition() {
        if (stopped) return;
        scheduleNextTick(1L);
    }

    /**
     * Phase 4: queue a one-shot banner that the next composite_summary will
     * carry. Used by {@code RotationCoordinator} so the new-generation
     * supervisor reads "you just inherited from gen N-1; here are the events
     * that happened while the handoff was producing" inline with the first
     * normal batch.
     */
    public void setPendingGenerationBanner(String text) {
        pendingGenerationBanner.set(text);
    }

    /** Called by EventCollector on an urgent event. Brings the next tick forward. */
    private void onUrgent(JsonObject event) {
        if (stopped) return;
        if (tickInProgress.get()) {
            // A tick is already running. If it has not yet drained, the urgent
            // event will be included in this batch (good). If it has already
            // drained, the event sits in the buffer and we'd lose the urgency
            // unless we mark the flag — the tick's finally block reads it to
            // pick a 1s reschedule instead of the normal 30s.
            urgentDuringTick.set(true);
            return;
        }
        scheduleNextTick(DEFAULT_URGENT_DELAY_MS);
    }

    private void scheduleNextTick(long delayMs) {
        if (stopped) return;
        synchronized (scheduleLock) {
            if (stopped) return;
            if (nextTickFuture != null) {
                nextTickFuture.cancel(false);
            }
            try {
                nextTickFuture = scheduler.schedule(this::runTick, delayMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // scheduler may have been shut down — ignore silently
                LOG.debug("[Monitor] schedule failed (likely shutdown): " + e.getMessage());
            }
        }
    }

    private void runTick() {
        if (stopped || pair.isDisposed()) return;
        if (!tickInProgress.compareAndSet(false, true)) return; // re-entry guard

        long startMs = System.currentTimeMillis();
        lastTickStartMs.set(startMs);
        long n = tickCounter.incrementAndGet();
        boolean caughtError = false;
        try {
            doTick(n);
            // Stage C (2026-05-25): health-state mgmt now lives in HealthWatcher.
            // Local consecutiveFailures + health state are kept as fall-back
            // for legacy callers that haven't migrated to pair.getHealthWatcher().
            int prev = consecutiveFailures.getAndSet(0);
            if (prev > 0) {
                transitionHealth(HealthState.HEALTHY);
            }
            com.github.claudecodegui.session.pair.watcher.HealthWatcher hw = pair.getHealthWatcher();
            if (hw != null) hw.recordSuccess();
            // Phase 2: signal "supervisor turn complete" so the status pusher
            // updates lastActivityAgoMs. Only on success — failures don't
            // count as activity.
            PairStatusPusher sp = pair.getStatusPusher();
            if (sp != null) sp.onSupervisorTurnComplete();
        } catch (Exception e) {
            // 2026-05-25 (FUNDAMENTAL FIX): TimeoutException catch removed —
            // the monitor no longer time-caps forwardComposite, so the only
            // remaining failure modes are: daemon IPC error, supervisor turn
            // throwing, env-opted-in SUPERVISOR_QUERY_TIMEOUT (rare). All
            // funnel through this generic catch.
            caughtError = true;
            int f = consecutiveFailures.incrementAndGet();
            LOG.warn("[Monitor] " + pair.getPairId() + " tick #" + n + " failed (consec=" + f + "): "
                    + e.getMessage());
            transitionHealth(f >= 2 ? HealthState.UNHEALTHY : HealthState.DEGRADED);
            if (f >= 2) coordinator.requestRotation();
            // Stage C (2026-05-25): also tell HealthWatcher so the new owner
            // sees the failure. transitionHealth above pushes to status pusher
            // + L2 metrics; HealthWatcher does the same independently — slight
            // double-write during the transition period, but cheap and harmless.
            com.github.claudecodegui.session.pair.watcher.HealthWatcher hw = pair.getHealthWatcher();
            if (hw != null) hw.recordFailure(e.getMessage());
        } finally {
            lastTickEndMs.set(System.currentTimeMillis());
            tickInProgress.set(false);
            if (caughtError) {
                // Mark a fresh batch window so the next composite_summary's
                // batchStartMs reflects "after the failure", not "since the
                // beginning of the previous-but-failed window".
                batchStartMs = System.currentTimeMillis();
            }
            // Phase 2: throttled snapshot push regardless of tick success —
            // pendingEvents / tickCount / lastTickEnd always advance and the
            // status panel should reflect them.
            try {
                PairStatusPusher sp = pair.getStatusPusher();
                if (sp != null) sp.pushSoft();
            } catch (Exception ignored) { /* never let pusher break the loop */ }

            // If an urgent event arrived while this tick was running, the
            // event already sits in the collector buffer but the urgency would
            // be lost if we rescheduled at the normal interval — speed up.
            long nextDelay = urgentDuringTick.compareAndSet(true, false)
                    ? DEFAULT_URGENT_DELAY_MS : tickIntervalMs;
            scheduleNextTick(nextDelay);
        }
    }

    /**
     * Protocol v2 (2026-05-24): per-tick budget check. Fires warn event once
     * when any ratio crosses 80%, fires exceeded event + pauses the pair when
     * any ratio crosses 100%. The supervisor sees both as composite_summary
     * children and can adjust plan (trim non-critical steps).
     */
    private void checkBudget() {
        PairBudgetTracker tracker = pair.getBudgetTracker();
        if (tracker == null || !tracker.hasAnyLimit()) return;
        EventBus bus = pair.getEventBus();
        if (bus == null) return;

        if (tracker.shouldPause() && tracker.markExceededReported()) {
            com.github.claudecodegui.session.pair.protocol.BudgetStatus s = tracker.check();
            LOG.warn("[Monitor] " + pair.getPairId() + " budget exceeded (maxRatio="
                    + s.maxRatio() + "), pausing pair");
            bus.publishBudgetExceeded(s.maxRatio());
            pair.pause();
            // The PARTIAL/PAUSED report itself is written by CompletionReportWriter
            // hooked into the rotation-decider / pair lifecycle pipeline — not by
            // this monitor (which must stay short).
            return;
        }

        if (tracker.shouldWarn()) {
            com.github.claudecodegui.session.pair.protocol.BudgetStatus s = tracker.check();
            LOG.info("[Monitor] " + pair.getPairId() + " budget warning (maxRatio="
                    + s.maxRatio() + ")");
            bus.publishBudgetWarning(s.tokenRatio, s.durationRatio, s.stepRatio,
                    s.subagentRatio, s.maxRatio());
        }
    }

    private void doTick(long tickNumber) throws Exception {
        coordinator.enterNormalOp(PairCoordinator.State.TICK);
        try {
            // Protocol v2 (2026-05-24): check budget BEFORE draining events so a
            // paused pair stops dispatching new directives immediately. The
            // pause flag itself is set inside checkBudget on the first 100%
            // crossing; subsequent ticks see it and return early.
            if (pair.isPaused()) {
                LOG.debug("[Monitor] " + pair.getPairId() + " tick #" + tickNumber + " paused — skip");
                return;
            }
            checkBudget();
            if (pair.isPaused()) {
                LOG.info("[Monitor] " + pair.getPairId() + " tick #" + tickNumber + " just paused by budget — skip");
                return;
            }

            EventCollector.DrainResult batch = collector.drainAll();
            long endMs = System.currentTimeMillis();
            long startMs = batchStartMs;
            batchStartMs = endMs;

            boolean isHealthCheck = (tickNumber % HEALTH_CHECK_EVERY_N_TICKS) == 0;
            if (batch.isEmpty() && !isHealthCheck) {
                LOG.debug("[Monitor] " + pair.getPairId() + " tick #" + tickNumber + " empty skip");
                return;
            }

            // Phase 2 (2026-05-24): on idle health-check ticks, refresh real
            // context usage from the SDK BEFORE delegating to the supervisor.
            // Skipped on event-bearing ticks because the postEvent round-trip
            // already takes ~seconds — no need to compound latency.
            if (isHealthCheck && batch.events.isEmpty()) {
                refreshContextUsage();
            }

            // Idle health-check tick (no events, no drops, no pending banner):
            // skip forwardComposite entirely. Previously this path forced the
            // supervisor to consume a synthetic "必须 emit_action(wait)" user
            // message that interrupted in-flight thinking. Now it just pushes
            // a notice to the right-pane strip so the heartbeat is visible
            // without disturbing the supervisor.
            if (isHealthCheck
                    && batch.events.isEmpty()
                    && batch.droppedSincePrevious == 0
                    && pendingGenerationBanner.get() == null) {
                pushHealthCheckNotice(tickNumber, startMs, endMs);
                return;
            }

            String banner = pendingGenerationBanner.getAndSet(null);
            JsonObject composite = CompositeSummaryBuilder.build(
                    batch, startMs, endMs, tickNumber, isHealthCheck, banner);

            // Delegate to EventBus to reuse the existing transport-error +
            // lazy-restart-on-NOT_FOUND logic. forwardComposite blocks until
            // ACTION is dispatched OR the daemon fails. 2026-05-25 (FUNDAMENTAL
            // FIX): no wall-clock cap — the user controls cancel via the
            // supervisor Stop button.
            EventBus bus = pair.getEventBus();
            if (bus == null) {
                throw new IllegalStateException("EventBus not wired on PairSession");
            }
            bus.forwardComposite(composite).get();
        } finally {
            coordinator.exitNormalOp();
        }
    }

    /**
     * Phase 2: cheap, best-effort context-usage refresh. Failures are not
     * fatal — we keep the previous snapshot and try again next health tick.
     *
     * <p>Phase 5 (2026-05-24): after a successful refresh, evaluate rotation
     * triggers against L2 + the fresh ratio. Trigger → push WARN alert +
     * mark requestRotation. The decider's 1s tick picks it up; the
     * coordinator respects the 5-minute cooldown so trigger spam doesn't
     * cause runaway rotations.
     */
    private void refreshContextUsage() {
        PairStatusPusher sp = pair.getStatusPusher();
        if (sp == null) return;
        Double ratio = null;
        try {
            JsonObject usage = pair.getSupervisorBridge()
                    .getContextUsage()
                    .get(10, TimeUnit.SECONDS);
            if (usage != null) {
                ratio = usage.has("ratio") && !usage.get("ratio").isJsonNull()
                        ? usage.get("ratio").getAsDouble() : null;
                Long used = usage.has("totalUsed") && !usage.get("totalUsed").isJsonNull()
                        ? usage.get("totalUsed").getAsLong() : null;
                Long limit = usage.has("contextLimit") && !usage.get("contextLimit").isJsonNull()
                        ? usage.get("contextLimit").getAsLong() : null;
                sp.updateContextUsage(ratio, used, limit);
            }
        } catch (Exception e) {
            LOG.debug("[Monitor] context usage refresh failed: " + e.getMessage());
            // continue to trigger eval — compactCount alone may already qualify
        }

        if (l2Store == null) return;
        try {
            L2State l2 = l2Store.read(pair.getPairId());
            RotationConfig cfg = RotationConfig.loadOrDefault(
                    new CodemossSettingsService().getSupervisorAgentManager());
            RotationTriggers.Trigger trig = RotationTriggers.evaluate(l2, ratio, cfg);
            if (trig != null) {
                LOG.info("[Monitor] " + pair.getPairId() + " rotation trigger: "
                        + trig.severity + " " + trig.reason);
                sp.pushAlert(
                        trig.severity == RotationTriggers.Severity.HARD
                                ? PairStatusSnapshot.Alert.Severity.ERROR
                                : PairStatusSnapshot.Alert.Severity.WARN,
                        "rotate request: " + trig.reason);
                coordinator.requestRotation();
            }
        } catch (Exception e) {
            LOG.warn("[Monitor] trigger eval failed: " + e.getMessage());
        }
    }

    /**
     * Build + push a one-line periodic-event notice for the right-pane strip.
     * Called only on idle health-check ticks (no events, no drops, no banner).
     * Does NOT touch the supervisor input stream.
     */
    private void pushHealthCheckNotice(long tickNumber, long startMs, long endMs) {
        ActionRouter ar = pair.getActionRouter();
        if (ar == null) return;
        JsonObject notice = new JsonObject();
        notice.addProperty("ts", endMs);
        notice.addProperty("kind", "health_check");
        notice.addProperty("message", "健康检查 · 无事件");
        JsonObject details = new JsonObject();
        details.addProperty("tick", tickNumber);
        details.addProperty("windowMs", Math.max(0L, endMs - startMs));
        notice.add("details", details);
        try {
            ar.pushNotice(notice);
        } catch (Exception e) {
            LOG.debug("[Monitor] pushHealthCheckNotice failed: " + e.getMessage());
        }
    }

    private void transitionHealth(HealthState target) {
        HealthState prev = health.getAndSet(target);
        if (prev == target) return;
        LOG.info("[Monitor] " + pair.getPairId() + " health " + prev + " -> " + target);
        // Phase 2: notify the status pusher; it adds an alert + hard pushes.
        PairStatusPusher sp = pair.getStatusPusher();
        if (sp != null) {
            try { sp.onHealthChanged(prev, target); }
            catch (Exception e) {
                LOG.warn("[Monitor] status push on health change failed: " + e.getMessage());
            }
        }
        // Phase 5 (2026-05-24): persist counters into L2.metrics so post-restart
        // and post-rotation observers see lifetime degraded/unhealthy totals.
        if (l2Store == null) return;
        try {
            l2Store.update(pair.getPairId(), s -> {
                if (s.metrics == null) s.metrics = new L2State.Metrics();
                if (target == HealthState.DEGRADED) s.metrics.degradedCount += 1;
                if (target == HealthState.UNHEALTHY) s.metrics.unhealthyCount += 1;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[Monitor] L2 metrics update on health change failed: " + e.getMessage());
        }
    }
}
