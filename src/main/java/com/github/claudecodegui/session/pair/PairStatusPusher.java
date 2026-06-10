package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 2 (2026-05-24): collects per-pair status fields from multiple
 * sources (monitor tick, compact-boundary events, alert pushes) and pushes
 * a coalesced {@link PairStatusSnapshot} JSON to the webview.
 *
 * <p>Throttling rule:
 * <ul>
 *   <li>"Soft" updates (tick-end health refresh) collapse — at most one
 *       push per {@link #MIN_PUSH_INTERVAL_MS}.</li>
 *   <li>"Hard" updates (compaction observed, health degradation, alert)
 *       bypass the throttle and push immediately.</li>
 * </ul>
 *
 * <p>The pusher does NOT own a scheduler — it pushes inline on the caller's
 * thread (typically the monitor scheduler or daemon IPC reader). The
 * webview-side adapter is responsible for hopping to the EDT.
 *
 * <p>State accumulators (alert ring + last-known fields) live here so the
 * monitor only has to call setters when something changes; consumers
 * always get the merged latest view.
 */
public class PairStatusPusher {

    private static final Logger LOG = Logger.getInstance(PairStatusPusher.class);

    /** Soft-push throttle. Hard pushes ignore this. */
    public static final long MIN_PUSH_INTERVAL_MS = 1000L;
    /** Recent-alert ring cap. */
    public static final int MAX_RECENT_ALERTS = 10;

    private final PairSession pair;
    private final ActionRouter router;
    /**
     * Phase 3 (2026-05-24): optional. When non-null, snapshot.generation is
     * read from the durable L2 state instead of being hardcoded to 0. Kept
     * optional so Phase 1/2-only test wiring still works without an L2Store.
     */
    private final L2Store l2Store;

    private final AtomicLong lastPushedMs = new AtomicLong(0L);
    private final AtomicReference<String> lastPushedJson = new AtomicReference<>("");

    // ---- accumulators set by various callers, read at push-time ----
    private volatile Double contextRatio;
    private volatile Long usedTokens;
    private volatile Long contextLimit;
    private volatile int compactCount;
    private volatile long lastSupervisorActivityMs = 0L;

    /** Contract State Machine v3 (2026-05-25): HealthWatchdog threshold —
     *  supervisor SDK silent for this long → mark CRITICAL alert. */
    public static final long SDK_CRITICAL_SILENCE_MS = 30L * 60L * 1000L;

    /** Latch tracking the last wall-clock ms a CRITICAL silence alert fired.
     *  Reset to 0 whenever supervisor activity resumes so the next silence
     *  window can alert again. */
    private volatile long criticalHealthAlertFiredAt = 0L;
    private final Deque<PairStatusSnapshot.Alert> alertRing = new ArrayDeque<>();

    /** Contract State Machine v3 (2026-05-25): ring for coordinator events
     *  surfaced to the CoordinatorEventStrip UI. Cap kept small (15) so
     *  the snapshot payload stays light. */
    public static final int MAX_RECENT_COORDINATOR_EVENTS = 15;
    private final Deque<PairStatusSnapshot.CoordinatorEvent> coordinatorEventRing = new ArrayDeque<>();

    /** Contract State Machine v3 (2026-05-25): own periodic push, decoupled
     *  from SupervisorMonitor.tick which after B.3.3 only runs on Contract
     *  events (not on a fixed schedule). 30s matches the previous monitor
     *  cadence — UI sees a fresh snapshot at least every 30s regardless of
     *  whether the supervisor is active. */
    public static final long PERIODIC_PUSH_INTERVAL_MS = 30_000L;

    private volatile ScheduledExecutorService pushScheduler;
    private volatile ScheduledFuture<?> periodicPushFuture;
    private final AtomicBoolean periodicStarted = new AtomicBoolean(false);

    public PairStatusPusher(PairSession pair, ActionRouter router) {
        this(pair, router, null);
    }

    public PairStatusPusher(PairSession pair, ActionRouter router, L2Store l2Store) {
        this.pair = pair;
        this.router = router;
        this.l2Store = l2Store;
    }

    /**
     * Contract State Machine v3 (2026-05-25): start the periodic 30s push
     * that keeps the UI snapshot fresh independently of SupervisorMonitor's
     * (now event-driven) tick. Idempotent — second call is a no-op.
     *
     * <p>Also fires an immediate {@link #pushHard()} so the UI sees an
     * initial snapshot within seconds of pair start, instead of waiting for
     * the first compaction / rotation / decision event.
     */
    public void startPeriodicPush() {
        if (!periodicStarted.compareAndSet(false, true)) return;
        pushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pair-status-pusher-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
        // Seed the UI immediately.
        try {
            pushHard();
        } catch (Exception e) {
            LOG.warn("[PairStatusPusher] initial seed push failed: " + e.getMessage());
        }
        periodicPushFuture = pushScheduler.scheduleWithFixedDelay(() -> {
            try { pushSoft(); }
            catch (Exception e) { LOG.warn("[PairStatusPusher] periodic push failed: " + e.getMessage()); }
        }, PERIODIC_PUSH_INTERVAL_MS, PERIODIC_PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("[PairStatusPusher] " + pair.getPairId() + " periodic push started at "
                + PERIODIC_PUSH_INTERVAL_MS + "ms");
    }

    public void stopPeriodicPush() {
        if (!periodicStarted.compareAndSet(true, false)) return;
        ScheduledFuture<?> f = periodicPushFuture;
        if (f != null) f.cancel(false);
        ScheduledExecutorService s = pushScheduler;
        if (s != null) {
            s.shutdownNow();
            try { s.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Update SDK context-usage fields (called from supervisor.getContextUsage
     * response). Does NOT push by itself — the monitor follows up with a
     * {@link #pushSoft()} on the same tick.
     */
    public void updateContextUsage(Double ratio, Long used, Long limit) {
        this.contextRatio = ratio;
        this.usedTokens = used;
        this.contextLimit = limit;
    }

    /**
     * Called from {@link SupervisorBridge}'s compact-boundary handler. Pushes
     * immediately as a "hard" update because compaction is user-visible.
     */
    public void onCompactBoundary() {
        this.compactCount = this.compactCount + 1;
        addAlert(PairStatusSnapshot.Alert.Severity.WARN,
                "Context auto-compacted (#" + compactCount + ")");
        pushHard();
    }

    /** Phase 2: called by monitor after each successful turn end. */
    public void onSupervisorTurnComplete() {
        this.lastSupervisorActivityMs = System.currentTimeMillis();
    }

    /** Health transition: push immediately so the UI reacts to DEGRADED/UNHEALTHY without waiting. */
    public void onHealthChanged(HealthState old, HealthState now) {
        if (old == now) return;
        PairStatusSnapshot.Alert.Severity sev =
                (now == HealthState.UNHEALTHY) ? PairStatusSnapshot.Alert.Severity.ERROR
              : (now == HealthState.DEGRADED)  ? PairStatusSnapshot.Alert.Severity.WARN
              :                                  PairStatusSnapshot.Alert.Severity.INFO;
        addAlert(sev, "health: " + old + " -> " + now);
        pushHard();
    }

    /**
     * Phase 4 (2026-05-24): announce a successful rotation. Adds a structured
     * alert + hard-pushes a new snapshot (which will carry the bumped
     * generation read from L2).
     */
    public void pushRotationBoundary(int newGeneration, String reason, String handoffSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("rotation: gen → ").append(newGeneration);
        if (reason != null && !reason.isEmpty()) sb.append(" (").append(reason).append(')');
        if (handoffSource != null && !handoffSource.isEmpty()) {
            sb.append(" via ").append(handoffSource);
        }
        addAlert(
            "l2_unhealthy".equals(handoffSource)
                    ? PairStatusSnapshot.Alert.Severity.WARN
                    : PairStatusSnapshot.Alert.Severity.INFO,
            sb.toString()
        );
        pushHard();
    }

    /**
     * Phase 6b (2026-05-24): announce a successful main-AI rotation. Distinct
     * from {@link #pushRotationBoundary} because the new session id isn't
     * known yet — the daemon assigns it on the next user turn — so callers
     * pass only reason + handoffSource. The WARN severity for an L2-fallback
     * handoff matches the supervisor-side convention.
     */
    public void pushMainAIRotationBoundary(String reason, String handoffSource) {
        StringBuilder sb = new StringBuilder("main AI rotation");
        if (reason != null && !reason.isEmpty()) sb.append(" (").append(reason).append(')');
        if (handoffSource != null && !handoffSource.isEmpty()) {
            sb.append(" via ").append(handoffSource);
        }
        sb.append(" — next user message starts fresh runtime");
        addAlert(
            "l2_fallback".equals(handoffSource)
                    ? PairStatusSnapshot.Alert.Severity.WARN
                    : PairStatusSnapshot.Alert.Severity.INFO,
            sb.toString()
        );
        pushHard();
    }

    /** Add a free-form alert (e.g. tick timeout recovered, restart succeeded). */
    public void pushAlert(PairStatusSnapshot.Alert.Severity sev, String message) {
        addAlert(sev, message);
        pushHard();
    }

    /** Throttled snapshot push — used by the monitor's normal tick end. */
    public void pushSoft() {
        long now = System.currentTimeMillis();
        long last = lastPushedMs.get();
        if (now - last < MIN_PUSH_INTERVAL_MS) return;
        checkSdkSilenceWatchdog(now);
        doPush(now);
    }

    /**
     * Contract State Machine v3 (2026-05-25): HealthWatchdog inline rule.
     * If supervisor SDK has been silent for >30 minutes, surface a CRITICAL
     * alert so the UI can flag suspected process-level failure. Fires once
     * per silence window (tracked by {@link #criticalHealthAlertFiredAt} so
     * we don't spam every snapshot push).
     */
    private void checkSdkSilenceWatchdog(long now) {
        if (lastSupervisorActivityMs <= 0) return;
        long inactiveMs = now - lastSupervisorActivityMs;
        if (inactiveMs < SDK_CRITICAL_SILENCE_MS) {
            // Reset latch so the next silence window can alert again.
            criticalHealthAlertFiredAt = 0L;
            return;
        }
        // Already alerted within this silence window (lastSupervisorActivity
        // hasn't moved since the last alert) — don't spam.
        if (criticalHealthAlertFiredAt >= lastSupervisorActivityMs) return;
        addAlert(PairStatusSnapshot.Alert.Severity.ERROR,
                "Supervisor SDK silent for " + (inactiveMs / 60_000L)
                        + "min — suspected process-level failure");
        criticalHealthAlertFiredAt = now;
    }

    /** Unconditional snapshot push — used by health transitions, compaction, alerts. */
    public void pushHard() {
        doPush(System.currentTimeMillis());
    }

    private void doPush(long now) {
        PairStatusSnapshot snap = buildSnapshot(now);
        JsonObject json = snap.toJson();
        String jsonStr = json.toString();
        // Suppress no-op pushes: identical JSON to the previous push.
        if (jsonStr.equals(lastPushedJson.get())) {
            return;
        }
        lastPushedJson.set(jsonStr);
        lastPushedMs.set(now);
        try {
            router.getWebview().onPairStatusUpdate(json);
        } catch (Exception e) {
            LOG.warn("[PairStatusPusher] webview push failed: " + e.getMessage());
        }
    }

    private PairStatusSnapshot buildSnapshot(long now) {
        SupervisorMonitor monitor = pair.getSupervisorMonitor();
        EventCollector collector = pair.getEventCollector();
        PairCoordinator coordinator = pair.getCoordinator();
        // 2026-05-28: authoritative plan state for the webview Stop-button gate.
        com.github.claudecodegui.session.pair.plan.PlanStateMachine planSm = pair.getPlanStateMachine();
        com.github.claudecodegui.session.pair.plan.Plan plan = planSm != null ? planSm.getCurrent() : null;

        Long ago = lastSupervisorActivityMs > 0
                ? Math.max(0L, now - lastSupervisorActivityMs) : null;

        List<PairStatusSnapshot.Alert> alerts;
        synchronized (alertRing) {
            alerts = new ArrayList<>(alertRing);
        }
        List<PairStatusSnapshot.CoordinatorEvent> coordEvents;
        synchronized (coordinatorEventRing) {
            coordEvents = new ArrayList<>(coordinatorEventRing);
        }

        int generation = 0;
        // 2026-05-25: rotation/compaction counts for both sides, surfaced in
        // the SessionCountStrip UI. supervisor counts live at the top level
        // of L2State; main AI counts are nested under l2.mainAI (nullable
        // when the rotation/compaction subsystem hasn't initialised yet).
        int supervisorRotationCount = 0;
        int mainAiRotationCount = 0;
        int mainAiCompactCount = 0;
        // Phase 5 (2026-05-24): pull recent decisions out of L2 for the
        // webview's DecisionTimeline. Last 20 entries (newest at end) keeps
        // payload small while giving enough scroll-back for a UI panel.
        java.util.List<L2State.DecisionEntry> recentDecisions = java.util.Collections.emptyList();
        if (l2Store != null) {
            try {
                // Session-kind refactor (S3): L2 keyed by the persistent container id.
                L2State s = l2Store.read(pair.getL2Key());
                if (s != null) {
                    generation = s.generation;
                    supervisorRotationCount = s.rotationCount;
                    if (s.mainAI != null) {
                        mainAiRotationCount = s.mainAI.rotationCount;
                        mainAiCompactCount = s.mainAI.compactCount;
                    }
                    if (s.recentDecisions != null && !s.recentDecisions.isEmpty()) {
                        int from = Math.max(0, s.recentDecisions.size() - 20);
                        recentDecisions = new ArrayList<>(s.recentDecisions.subList(from, s.recentDecisions.size()));
                    }
                    // Coordinator-event strip is L2-authoritative (like recentDecisions)
                    // so it survives a webview reload AND multiple restart-resumes.
                    // The in-memory ring stays a write-buffer / no-L2 fallback only.
                    if (s.recentCoordinatorEvents != null && !s.recentCoordinatorEvents.isEmpty()) {
                        List<PairStatusSnapshot.CoordinatorEvent> fromL2 = new ArrayList<>();
                        for (L2State.PersistedCoordinatorEvent pe : s.recentCoordinatorEvents) {
                            if (pe == null) continue;
                            PairStatusSnapshot.CoordinatorEvent.Source src;
                            try { src = PairStatusSnapshot.CoordinatorEvent.Source.valueOf(pe.source); }
                            catch (Exception ignore) { src = PairStatusSnapshot.CoordinatorEvent.Source.DISPATCHER; }
                            fromL2.add(new PairStatusSnapshot.CoordinatorEvent(
                                    pe.ts, src, pe.type, pe.message, pe.detail));
                        }
                        if (!fromL2.isEmpty()) coordEvents = fromL2;
                    }
                }
            } catch (Exception e) {
                LOG.debug("[PairStatusPusher] L2 read for generation/decisions failed: " + e.getMessage());
            }
        }

        // Phase 5: budget status (null when no limits set — UI hides the section).
        com.github.claudecodegui.session.pair.protocol.BudgetStatus budgetStatus = null;
        PairBudgetTracker bt = pair.getBudgetTracker();
        if (bt != null && bt.hasAnyLimit()) {
            try { budgetStatus = bt.check(); } catch (Exception e) {
                LOG.debug("[PairStatusPusher] budget check failed: " + e.getMessage());
            }
        }

        return PairStatusSnapshot.builder(pair.getPairId())
                .generation(generation)
                .state(coordinator != null ? coordinator.getState() : null)
                .health(monitor != null ? monitor.getHealth() : HealthState.HEALTHY)
                .supervisorContextRatio(contextRatio)
                .supervisorUsedTokens(usedTokens)
                .supervisorContextLimit(contextLimit)
                .compactCount(compactCount)
                .supervisorRotationCount(supervisorRotationCount)
                .mainAiRotationCount(mainAiRotationCount)
                .mainAiCompactCount(mainAiCompactCount)
                .lastActivityAgoMs(ago)
                .pendingEvents(collector != null ? collector.currentSize() : 0)
                .totalDroppedEvents(collector != null ? collector.totalDropped() : 0)
                .tickCount(monitor != null ? monitor.getTickCount() : 0)
                .lastTickStartMs(monitor != null ? monitor.getLastTickStartMs() : 0)
                .lastTickEndMs(monitor != null ? monitor.getLastTickEndMs() : 0)
                .recentAlerts(alerts)
                .recentDecisions(recentDecisions)
                .autonomyMode(pair.getAutonomyMode())
                .budgetStatus(budgetStatus)
                .paused(pair.isPaused())
                .recentCoordinatorEvents(coordEvents)
                .openContractCount(pair.getContractRegistry() != null
                        ? pair.getContractRegistry().openCount() : 0)
                .totalIssuedContracts(pair.getContractRegistry() != null
                        ? pair.getContractRegistry().getTotalIssued() : 0L)
                .totalRetriedContracts(pair.getContractRegistry() != null
                        ? pair.getContractRegistry().getTotalRetried() : 0L)
                .totalDischargedContracts(pair.getContractRegistry() != null
                        ? pair.getContractRegistry().getTotalDischarged() : 0L)
                .totalEscalatedContracts(pair.getContractRegistry() != null
                        ? pair.getContractRegistry().getTotalEscalated() : 0L)
                .planState(plan != null && plan.state != null ? plan.state.name() : null)
                .planSubState(plan != null && plan.subState != null ? plan.subState.name() : null)
                .supervisorStartedAt(pair.getStartedAt())
                .supervisorFinishedAt(pair.getFinishedAt())
                .build();
    }

    private void addAlert(PairStatusSnapshot.Alert.Severity sev, String message) {
        PairStatusSnapshot.Alert a = new PairStatusSnapshot.Alert(
                System.currentTimeMillis(), sev, message);
        synchronized (alertRing) {
            while (alertRing.size() >= MAX_RECENT_ALERTS) alertRing.pollFirst();
            alertRing.offerLast(a);
        }
    }

    /**
     * Contract State Machine v3 (2026-05-25): record a coordinator event
     * (plan transition / contract issue / discharge / retry / etc.) for
     * the CoordinatorEventStrip UI. Triggers a hard push so the strip
     * updates without waiting for the periodic 30s tick — operators
     * watching the strip want to see events land immediately.
     */
    public void recordCoordinatorEvent(PairStatusSnapshot.CoordinatorEvent.Source source,
                                       String type, String message, String detail) {
        PairStatusSnapshot.CoordinatorEvent e = new PairStatusSnapshot.CoordinatorEvent(
                System.currentTimeMillis(), source, type, message, detail);
        synchronized (coordinatorEventRing) {
            while (coordinatorEventRing.size() >= MAX_RECENT_COORDINATOR_EVENTS) {
                coordinatorEventRing.pollFirst();
            }
            coordinatorEventRing.offerLast(e);
        }
        // Persist into L2 (state.json) so the strip survives a webview reload and
        // can be carried into a resumed pair after IDE restart. Best-effort; the
        // in-memory ring stays the live source for buildSnapshot.
        if (l2Store != null) {
            try {
                l2Store.update(pair.getL2Key(), s -> {
                    s.recentCoordinatorEvents.add(new L2State.PersistedCoordinatorEvent(
                            e.ts, e.source.name(), e.type, e.message, e.detail));
                    while (s.recentCoordinatorEvents.size()
                            > com.github.claudecodegui.session.pair.l2.L2Schema.RECENT_COORDINATOR_EVENTS_MAX) {
                        s.recentCoordinatorEvents.remove(0);
                    }
                    return s;
                });
            } catch (Exception ex) {
                LOG.debug("[PairStatusPusher] coord-event L2 persist failed: " + ex.getMessage());
            }
        }
        try { pushHard(); }
        catch (Exception ex) { LOG.debug("[PairStatusPusher] coord-event push failed: " + ex.getMessage()); }
    }

    /**
     * Force the next snapshot through even if its JSON is identical to the last
     * push. Used when the webview (re)mounts (reload / node recovery): the Java
     * ring + L2 are intact but React state was wiped, so the dedup in
     * {@link #doPush} would otherwise suppress the re-delivery and leave the
     * status panel (incl. the coordinator strip) blank until the next change.
     */
    public void pushForce() {
        lastPushedJson.set("");
        pushHard();
    }

    /** Diagnostic getter. */
    public int getCompactCount() { return compactCount; }
}
