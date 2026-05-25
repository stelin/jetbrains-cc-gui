package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
    private final Deque<PairStatusSnapshot.Alert> alertRing = new ArrayDeque<>();

    public PairStatusPusher(PairSession pair, ActionRouter router) {
        this(pair, router, null);
    }

    public PairStatusPusher(PairSession pair, ActionRouter router, L2Store l2Store) {
        this.pair = pair;
        this.router = router;
        this.l2Store = l2Store;
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
        doPush(now);
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

        Long ago = lastSupervisorActivityMs > 0
                ? Math.max(0L, now - lastSupervisorActivityMs) : null;

        List<PairStatusSnapshot.Alert> alerts;
        synchronized (alertRing) {
            alerts = new ArrayList<>(alertRing);
        }

        int generation = 0;
        // Phase 5 (2026-05-24): pull recent decisions out of L2 for the
        // webview's DecisionTimeline. Last 20 entries (newest at end) keeps
        // payload small while giving enough scroll-back for a UI panel.
        java.util.List<L2State.DecisionEntry> recentDecisions = java.util.Collections.emptyList();
        if (l2Store != null) {
            try {
                L2State s = l2Store.read(pair.getPairId());
                if (s != null) {
                    generation = s.generation;
                    if (s.recentDecisions != null && !s.recentDecisions.isEmpty()) {
                        int from = Math.max(0, s.recentDecisions.size() - 20);
                        recentDecisions = new ArrayList<>(s.recentDecisions.subList(from, s.recentDecisions.size()));
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

    /** Diagnostic getter. */
    public int getCompactCount() { return compactCount; }
}
