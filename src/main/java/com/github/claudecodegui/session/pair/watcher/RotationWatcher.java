package com.github.claudecodegui.session.pair.watcher;

import com.github.claudecodegui.session.pair.PairCoordinator;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Contract State Machine v3 (2026-05-25): identifier facade over the
 * distributed rotation-trigger sources. Provides a single entry point so
 * callers don't have to know which subsystem produced the request.
 *
 * <p>Rotation triggers can come from several places, all converging on
 * {@link PairCoordinator#requestRotation()}:
 * <ul>
 *   <li>{@link HealthWatcher} — UNHEALTHY threshold reached on
 *       supervisor-side RPCs.</li>
 *   <li>{@code MainAIMonitor.scheduleRotationEval} — after each main-AI
 *       turn the context-window / compaction trigger check runs.</li>
 *   <li>{@code PairSessionManager.bridge.setCompactBoundaryHandler} —
 *       post-compact trigger evaluation (compactCount / ratio threshold).</li>
 *   <li>{@code RotationTriggers.evaluate} (called by all three above).</li>
 * </ul>
 *
 * <p>Methods here are thin wrappers; rotation policy stays in {@code
 * RotationTriggers}. RotationWatcher exists so the Stage C
 * watcher-pattern is uniform across health / budget / rotation.
 */
public class RotationWatcher {

    private static final Logger LOG = Logger.getInstance(RotationWatcher.class);

    private final String pairId;
    private final PairCoordinator coordinator;

    public RotationWatcher(String pairId, PairCoordinator coordinator) {
        this.pairId = pairId;
        this.coordinator = coordinator;
    }

    /**
     * Forward a rotation request to the coordinator. Idempotent — multiple
     * requests within one window collapse to one rotation by
     * {@link com.github.claudecodegui.session.pair.rotation.RotationDecider}.
     */
    public void requestRotation(String reason) {
        if (coordinator == null) {
            LOG.warn("[RotationWatcher] " + pairId + " no coordinator, drop request: " + reason);
            return;
        }
        LOG.info("[RotationWatcher] " + pairId + " request rotation: " + reason);
        coordinator.requestRotation();
    }

    public void requestMainAIRotation(String reason) {
        if (coordinator == null) {
            LOG.warn("[RotationWatcher] " + pairId + " no coordinator, drop main-AI request: " + reason);
            return;
        }
        LOG.info("[RotationWatcher] " + pairId + " request main-AI rotation: " + reason);
        coordinator.requestMainAIRotation();
    }
}
