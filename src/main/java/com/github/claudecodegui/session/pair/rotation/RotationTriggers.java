package com.github.claudecodegui.session.pair.rotation;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.settings.RotationConfig;

/**
 * Phase 5 (2026-05-24): pure utility that evaluates whether the current L2
 * state + freshly-sampled context ratio warrant requesting a rotation.
 *
 * <p>Two trigger families, each with soft / hard variants. Both produce the
 * same outcome at the request level — they call {@code requestRotation()}
 * on the pair coordinator, and {@code RotationCoordinator} respects its
 * own 5-minute cooldown. The soft/hard distinction is for telemetry only
 * — it tells the user (via the status alert) WHY we asked.
 *
 * <p>Hard thresholds exist because if context blows the model rejects the
 * next turn entirely — losing the chance to produce a clean handoff. So
 * we want to react before the SDK's own compaction window closes.
 *
 * <p>2026-05-24 update: thresholds are no longer compile-time constants.
 * Callers pass a {@link RotationConfig} snapshot loaded from
 * {@code SupervisorAgentManager} (single source of truth for supervisor and
 * main-AI rotation alike). Old constants remain as defaults inside
 * {@link RotationConfig}.
 */
public final class RotationTriggers {

    private RotationTriggers() { /* no instances */ }

    public enum Severity { SOFT, HARD }

    /**
     * Immutable trigger result.
     */
    public static final class Trigger {
        public final Severity severity;
        public final String reason;

        public Trigger(Severity severity, String reason) {
            this.severity = severity;
            this.reason = reason;
        }
    }

    /**
     * Supervisor-side trigger evaluation. Uses {@code l2.compactionHistory.size()}
     * as the compact count.
     *
     * @param l2     pair's current L2 state (compactionHistory used for compact count)
     * @param ratio  fresh getContextUsage ratio, or null if unknown — null skips ratio triggers
     * @param config thresholds; null falls back to {@link RotationConfig#defaults()}
     * @return non-null trigger, or null when no threshold is crossed
     */
    public static Trigger evaluate(L2State l2, Double ratio, RotationConfig config) {
        if (l2 == null) return null;
        int compactCount = l2.compactionHistory == null ? 0 : l2.compactionHistory.size();
        return evaluateInternal(ratio, compactCount, config);
    }

    /**
     * Main-AI-side trigger evaluation. Uses {@code l2.mainAI.compactCount}
     * — separate counter from the supervisor's, populated by
     * {@code MainAIMonitor.onCompactBoundary}.
     */
    public static Trigger evaluateMainAI(L2State l2, Double ratio, RotationConfig config) {
        if (l2 == null) return null;
        int compactCount = (l2.mainAI != null) ? l2.mainAI.compactCount : 0;
        return evaluateInternal(ratio, compactCount, config);
    }

    private static Trigger evaluateInternal(Double ratio, int compactCount, RotationConfig config) {
        RotationConfig cfg = (config != null) ? config : RotationConfig.defaults();

        // Hard first — they win if both apply (more user-actionable telemetry).
        if (ratio != null && ratio >= cfg.hardRatio) {
            return new Trigger(Severity.HARD, "ratio>=" + fmt(cfg.hardRatio)
                    + " (actual=" + fmt(ratio) + ")");
        }
        if (compactCount >= cfg.hardCompact) {
            return new Trigger(Severity.HARD, "compactCount>=" + cfg.hardCompact
                    + " (actual=" + compactCount + ")");
        }
        if (ratio != null && ratio >= cfg.softRatio) {
            return new Trigger(Severity.SOFT, "ratio>=" + fmt(cfg.softRatio)
                    + " (actual=" + fmt(ratio) + ")");
        }
        if (compactCount >= cfg.softCompact) {
            return new Trigger(Severity.SOFT, "compactCount>=" + cfg.softCompact
                    + " (actual=" + compactCount + ")");
        }
        return null;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
