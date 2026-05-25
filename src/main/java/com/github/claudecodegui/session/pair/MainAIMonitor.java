package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.provider.claude.MainAIBridge;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.github.claudecodegui.session.pair.rotation.RotationTriggers;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.RotationConfig;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 6a (2026-05-24): per-pair main-AI observability monitor.
 *
 * <p>Unlike {@link SupervisorMonitor}, this monitor is purely event-driven —
 * there's no 30s tick. The main AI is user-driven (each user message starts
 * a turn; the SDK ends it), so we just hook the turn boundaries from
 * {@code ClaudeMessageHandler}.
 *
 * <p>Phase 6a responsibilities:
 * <ul>
 *   <li>Track turn lifecycle on the pair's L2 sub-state ({@link L2State.MainAIState}).</li>
 *   <li>Arm a {@link StallDetector} on {@code onTurnStart}, cancel on {@code onTurnEnd}.</li>
 *   <li>On stall fire: increment {@code stallCount}, push a WARN alert via
 *       {@link PairStatusPusher}, log. Does NOT auto-interrupt — user decides.</li>
 *   <li>On compaction (when wired from the main-AI SDK side in a follow-up):
 *       increment {@code compactCount} for telemetry. Phase 6a leaves a hook
 *       method ({@link #onCompactBoundary}) that callers can fire when they
 *       have a compact_boundary observation.</li>
 * </ul>
 *
 * <p>Phase 6b will add: actual rotation triggers, getContextUsage on main AI,
 * MainAIRotationCoordinator + swapInnerSession + chat-history boundary marker.
 *
 * <p>Lifecycle: created on demand by {@link PairSessionManager} when a pair's
 * mainSessionId becomes known (either at pair start or after the first SDK
 * response). Disposed when the pair stops.
 */
public class MainAIMonitor {

    private static final Logger LOG = Logger.getInstance(MainAIMonitor.class);

    private final String pairId;
    /**
     * Current main-AI session id. Mutable so that if a pair gets re-attached
     * to a different main session, the monitor follows. Captured into L2 on
     * every turn boundary.
     */
    private volatile String mainSessionId;
    private final L2Store l2Store;
    private final PairStatusPusher statusPusher;
    private final StallDetector stallDetector;

    private final AtomicLong turnStartedAt = new AtomicLong(0L);
    private final AtomicLong lastErrorAt = new AtomicLong(0L);

    /**
     * 2026-05-24: back-references injected by PairSessionManager after
     * construction (the pair / coord / bridge graph is wired top-down). When
     * either is null the auto-rotation evaluation in {@link #onTurnEnd}
     * silently skips — keeps unit-test wiring (manager-less) working.
     */
    private volatile PairSession pair;
    private volatile PairCoordinator coordinator;
    private volatile MainAIBridge mainAIBridge;

    /**
     * Single-threaded executor for async context-usage fetches triggered by
     * turn boundaries. Off-loaded from the SDK callback thread so a slow
     * daemon roundtrip cannot delay further main-AI message processing.
     */
    private final ExecutorService rotationEvalExecutor;

    public MainAIMonitor(String pairId,
                         String initialMainSessionId,
                         L2Store l2Store,
                         PairStatusPusher statusPusher) {
        this.pairId = pairId;
        this.mainSessionId = initialMainSessionId;
        this.l2Store = l2Store;
        this.statusPusher = statusPusher;
        this.stallDetector = new StallDetector("mainAI-" + pairId, this::onStallFire);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "mainai-rotation-eval-" + pairId);
            t.setDaemon(true);
            return t;
        };
        this.rotationEvalExecutor = Executors.newSingleThreadExecutor(tf);
    }

    /** Tear down the stall detector's thread + eval executor. */
    public void dispose() {
        stallDetector.dispose();
        rotationEvalExecutor.shutdown();
        try {
            if (!rotationEvalExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                rotationEvalExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rotationEvalExecutor.shutdownNow();
        }
    }

    /**
     * 2026-05-24: late-bound wiring done by PairSessionManager. Safe to call
     * multiple times if the pair is re-attached.
     */
    public void wireForAutoRotation(PairSession pair,
                                    PairCoordinator coordinator,
                                    MainAIBridge mainAIBridge) {
        this.pair = pair;
        this.coordinator = coordinator;
        this.mainAIBridge = mainAIBridge;
    }

    /**
     * Update the tracked main-AI session id. Called when the SDK assigns a
     * new sessionId mid-pair (e.g. Phase 6b rotation post-swap), so future
     * turn events bind to the correct session.
     */
    public void rebindSessionId(String newSessionId) {
        if (newSessionId == null || newSessionId.isEmpty()) return;
        String old = this.mainSessionId;
        this.mainSessionId = newSessionId;
        if (old != null && !old.equals(newSessionId)) {
            LOG.info("[MainAIMonitor] " + pairId + " rebind " + old + " -> " + newSessionId);
        }
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                s.mainAI.sessionId = newSessionId;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] rebind L2 update failed: " + e.getMessage());
        }
    }

    /** Patch point invoked from ClaudeMessageHandler.handleStreamStart. */
    public void onTurnStart() {
        long now = System.currentTimeMillis();
        turnStartedAt.set(now);
        stallDetector.start();
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                if (s.mainAI.sessionId == null && mainSessionId != null) {
                    s.mainAI.sessionId = mainSessionId;
                }
                s.mainAI.lastTurnStartMs = now;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] onTurnStart L2 update failed: " + e.getMessage());
        }
    }

    /** Patch point invoked from ClaudeMessageHandler.handleStreamEnd. */
    public void onTurnEnd() {
        long now = System.currentTimeMillis();
        stallDetector.stop();
        long startedAt = turnStartedAt.getAndSet(0L);
        long durationMs = startedAt > 0 ? (now - startedAt) : 0L;
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                if (s.mainAI.sessionId == null && mainSessionId != null) {
                    s.mainAI.sessionId = mainSessionId;
                }
                s.mainAI.lastTurnEndMs = now;
                s.mainAI.turnCount += 1;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] onTurnEnd L2 update failed: " + e.getMessage());
        }
        LOG.debug("[MainAIMonitor] " + pairId + " turn end durationMs=" + durationMs);

        // 2026-05-24: kick off async rotation-trigger evaluation. Off-loaded
        // so the SDK callback thread returns immediately; result lands in
        // coordinator.requestMainAIRotation() and the decider picks it up
        // within 1s.
        scheduleRotationEval();
    }

    /**
     * Async path: fetch real SDK context-usage for the current main-AI session,
     * persist it to L2 (so the status pusher / dashboards see it), and check
     * {@link RotationTriggers}. If a trigger fires, set the pair's main-AI
     * rotation flag for {@code RotationDecider}.
     *
     * <p>Skips gracefully when {@link #wireForAutoRotation} hasn't been called
     * yet (unit tests / pre-Phase-6b pairs).
     */
    private void scheduleRotationEval() {
        final PairSession p = this.pair;
        final PairCoordinator coord = this.coordinator;
        final MainAIBridge bridge = this.mainAIBridge;
        final String sid = this.mainSessionId;
        if (p == null || coord == null || bridge == null || sid == null || sid.isEmpty()) {
            return;
        }
        if (rotationEvalExecutor.isShutdown()) return;
        rotationEvalExecutor.submit(() -> evaluateRotation(p, coord, bridge, sid));
    }

    private void evaluateRotation(PairSession p,
                                  PairCoordinator coord,
                                  MainAIBridge bridge,
                                  String sid) {
        Double ratio = null;
        try {
            JsonObject usage = bridge.getContextUsage(sid).get(10, TimeUnit.SECONDS);
            if (usage != null) {
                if (usage.has("ratio") && !usage.get("ratio").isJsonNull()) {
                    ratio = usage.get("ratio").getAsDouble();
                }
                Long used = (usage.has("totalUsed") && !usage.get("totalUsed").isJsonNull())
                        ? usage.get("totalUsed").getAsLong() : null;
                Long limit = (usage.has("contextLimit") && !usage.get("contextLimit").isJsonNull())
                        ? usage.get("contextLimit").getAsLong() : null;
                updateContextUsage(ratio, used, limit);
            }
        } catch (Exception e) {
            LOG.debug("[MainAIMonitor] getContextUsage failed: " + e.getMessage());
            // fall through — compactCount alone may still trigger
        }

        L2State l2;
        try { l2 = l2Store.read(p.getPairId()); }
        catch (Exception e) {
            LOG.warn("[MainAIMonitor] L2 read for trigger eval failed: " + e.getMessage());
            return;
        }

        RotationConfig cfg = RotationConfig.loadOrDefault(
                new CodemossSettingsService().getSupervisorAgentManager());
        RotationTriggers.Trigger trig = RotationTriggers.evaluateMainAI(l2, ratio, cfg);
        if (trig == null) return;
        LOG.info("[MainAIMonitor] " + p.getPairId() + " main-AI rotation trigger: "
                + trig.severity + " " + trig.reason);
        if (statusPusher != null) {
            try {
                statusPusher.pushAlert(
                        trig.severity == RotationTriggers.Severity.HARD
                                ? PairStatusSnapshot.Alert.Severity.ERROR
                                : PairStatusSnapshot.Alert.Severity.WARN,
                        "main AI rotate request: " + trig.reason);
            } catch (Exception ignored) { /* never let pusher break the loop */ }
        }
        coord.requestMainAIRotation();
    }

    /**
     * Capture a verbatim user message into L2's recent-message ring. Called
     * from {@code ClaudeMessageHandler.handleUserMessage} once we've extracted
     * the SDK-echoed user text — gives the main-AI rotation handoff producer
     * a crash-safe fallback when the producer prompt itself fails.
     *
     * <p>Trimming to {@link com.github.claudecodegui.session.pair.l2.L2Schema#MAIN_AI_RECENT_USER_MAX}
     * is handled in {@code L2State.trimRings} which {@code L2Store.update} runs
     * after each mutation.
     */
    public void captureUserMessage(String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                if (s.mainAI.recentUserMessages == null) {
                    s.mainAI.recentUserMessages = new java.util.ArrayList<>();
                }
                s.mainAI.recentUserMessages.add(
                        new L2State.RecentUserMessageEntry(now, text));
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] captureUserMessage L2 update failed: " + e.getMessage());
        }
    }

    /**
     * Patch point invoked from ClaudeMessageHandler error paths. The monitor
     * accumulates an errorCount so Phase 6b can use it for rotation triggers;
     * Phase 6a only updates telemetry + pushes alert at thresholds.
     */
    public void onError(String summary) {
        long now = System.currentTimeMillis();
        lastErrorAt.set(now);
        long newCount;
        try {
            L2State updated = l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                s.mainAI.errorCount += 1;
                return s;
            });
            newCount = updated.mainAI != null ? updated.mainAI.errorCount : 0L;
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] onError L2 update failed: " + e.getMessage());
            return;
        }
        // Phase 6a soft signal — Phase 6b will turn this into a rotation trigger.
        if (statusPusher != null && newCount >= 3) {
            statusPusher.pushAlert(
                    PairStatusSnapshot.Alert.Severity.WARN,
                    "main AI errorCount=" + newCount + (summary == null ? "" : ": " + summary));
        }
    }

    /**
     * Patch point for when the main-AI SDK reports a {@code compact_boundary}.
     * Wired in Phase 6b (currently no main-AI compact-boundary observer
     * exists; ClaudeMessageHandler does not inspect SDK system messages).
     * Exposed today so the path is reserved.
     */
    public void onCompactBoundary() {
        long now = System.currentTimeMillis();
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                s.mainAI.compactCount += 1;
                s.mainAI.lastCompactAt = now;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] onCompactBoundary L2 update failed: " + e.getMessage());
        }
        if (statusPusher != null) {
            statusPusher.pushAlert(
                    PairStatusSnapshot.Alert.Severity.WARN,
                    "main AI auto-compacted");
        }
    }

    /**
     * Updates the cached SDK context-usage ratio + token counts for the main
     * AI. Wired in Phase 6b once a {@code ClaudeSDKBridge.getContextUsage}
     * method is added (the supervisor already has its own).
     */
    public void updateContextUsage(Double ratio, Long used, Long limit) {
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                s.mainAI.lastContextRatio = ratio;
                s.mainAI.lastUsedTokens = used;
                s.mainAI.lastContextLimit = limit;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] updateContextUsage L2 update failed: " + e.getMessage());
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private void onStallFire() {
        long now = System.currentTimeMillis();
        long startedAt = turnStartedAt.get();
        long elapsedSec = startedAt > 0 ? (now - startedAt) / 1000L : 0L;
        try {
            l2Store.update(pairId, s -> {
                if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
                s.mainAI.stallCount += 1;
                s.mainAI.lastStallMs = now;
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[MainAIMonitor] stall L2 update failed: " + e.getMessage());
        }
        if (statusPusher != null) {
            statusPusher.pushAlert(
                    PairStatusSnapshot.Alert.Severity.ERROR,
                    "main AI stalled (" + elapsedSec + "s no turn_end) — interrupt or wait?");
        }
        LOG.warn("[MainAIMonitor] " + pairId + " stall fired elapsedSec=" + elapsedSec);
    }
}
