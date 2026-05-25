package com.github.claudecodegui.settings;

/**
 * User-tunable rotation-trigger thresholds. Shared by supervisor and main-AI
 * rotation paths (single source of truth).
 *
 * <p>Persisted alongside other supervisor settings in {@code supervisor-agents.json}
 * under the {@code rotationConfig} key. {@link SupervisorAgentManager} owns
 * read/write; {@code RotationTriggers.evaluate} consumes a snapshot.
 *
 * <p>Field semantics — soft vs hard:
 * <ul>
 *   <li>{@code soft*} crossed → mark rotation requested, severity WARN.</li>
 *   <li>{@code hard*} crossed → same outcome but severity ERROR for telemetry.</li>
 * </ul>
 * Both fall under the 5-minute cooldown in {@code RotationCoordinator} so
 * trigger spam doesn't cause runaway rotations.
 */
public final class RotationConfig {

    public static final double DEFAULT_SOFT_RATIO   = 0.85;
    public static final double DEFAULT_HARD_RATIO   = 0.95;
    public static final int    DEFAULT_SOFT_COMPACT = 3;
    public static final int    DEFAULT_HARD_COMPACT = 5;

    public static final double MIN_RATIO = 0.30;
    public static final double MAX_RATIO = 1.00;
    public static final int    MIN_COMPACT = 1;
    public static final int    MAX_COMPACT = 50;

    public final double softRatio;
    public final double hardRatio;
    public final int    softCompact;
    public final int    hardCompact;

    public RotationConfig(double softRatio, double hardRatio, int softCompact, int hardCompact) {
        this.softRatio = softRatio;
        this.hardRatio = hardRatio;
        this.softCompact = softCompact;
        this.hardCompact = hardCompact;
    }

    public static RotationConfig defaults() {
        return new RotationConfig(
                DEFAULT_SOFT_RATIO,
                DEFAULT_HARD_RATIO,
                DEFAULT_SOFT_COMPACT,
                DEFAULT_HARD_COMPACT);
    }

    /**
     * Safe-fail loader so monitor / decider sites stay branch-light. Any
     * IO failure or null manager returns {@link #defaults()}; concrete
     * validation lives in {@link SupervisorAgentManager#getRotationConfig}.
     */
    public static RotationConfig loadOrDefault(SupervisorAgentManager manager) {
        if (manager == null) return defaults();
        try { return manager.getRotationConfig(); }
        catch (Exception ignored) { return defaults(); }
    }

    /**
     * Range-check and ordering-check. Returns null if valid; otherwise an
     * error string suitable for surfacing to the UI.
     */
    public String validate() {
        if (softRatio < MIN_RATIO || softRatio > MAX_RATIO) {
            return "softRatio out of range [" + MIN_RATIO + "," + MAX_RATIO + "]: " + softRatio;
        }
        if (hardRatio < MIN_RATIO || hardRatio > MAX_RATIO) {
            return "hardRatio out of range [" + MIN_RATIO + "," + MAX_RATIO + "]: " + hardRatio;
        }
        if (softRatio >= hardRatio) {
            return "softRatio (" + softRatio + ") must be < hardRatio (" + hardRatio + ")";
        }
        if (softCompact < MIN_COMPACT || softCompact > MAX_COMPACT) {
            return "softCompact out of range [" + MIN_COMPACT + "," + MAX_COMPACT + "]: " + softCompact;
        }
        if (hardCompact < MIN_COMPACT || hardCompact > MAX_COMPACT) {
            return "hardCompact out of range [" + MIN_COMPACT + "," + MAX_COMPACT + "]: " + hardCompact;
        }
        if (softCompact >= hardCompact) {
            return "softCompact (" + softCompact + ") must be < hardCompact (" + hardCompact + ")";
        }
        return null;
    }

    @Override
    public String toString() {
        return "RotationConfig{softRatio=" + softRatio
                + ", hardRatio=" + hardRatio
                + ", softCompact=" + softCompact
                + ", hardCompact=" + hardCompact + '}';
    }
}
