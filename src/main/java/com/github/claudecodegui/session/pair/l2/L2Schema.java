package com.github.claudecodegui.session.pair.l2;

/**
 * Phase 3 (2026-05-24): constants for the L2 durable state subsystem.
 *
 * <p>Centralised so callers / migration code / docs all reference the same
 * version numbers and limits without drift.
 */
public final class L2Schema {

    private L2Schema() { /* no instances */ }

    /** Current on-disk schema version. */
    public static final int CURRENT_VERSION = 2;

    /** Bounded ring cap for {@link L2State#recentDecisions}.
     *  Raised from 50 (v1) to 100 in Protocol v2 (2026-05-24) — autonomy mode
     *  emits more per-step decisions (A=trivial, B=meaningful-trace), and a
     *  long-running pair can exceed 50 within one session. Combined with
     *  {@link #RECENT_DECISIONS_MAX_WINDOW_MS}, whichever evicts first wins. */
    public static final int RECENT_DECISIONS_MAX = 100;

    /** Protocol v2: keep decisions for at most this many ms when trimming the
     *  ring. Defaults to 24 hours. */
    public static final long RECENT_DECISIONS_MAX_WINDOW_MS = 24L * 3600_000L;

    /** Bounded ring cap for {@link L2State#compactionHistory}. */
    public static final int COMPACTION_HISTORY_MAX = 50;

    /** Bounded ring cap for {@link L2State.MainAIState#recentUserMessages}. */
    public static final int MAIN_AI_RECENT_USER_MAX = 10;

    /** Maximum {@code generation} value before {@code knownConstraints} gets archived + reset. */
    public static final int GENERATION_RESET_THRESHOLD = 5;

    /** File name under each pair's directory. */
    public static final String STATE_FILE = "state.json";
    /** Rotation-time backup file name. */
    public static final String STATE_BAK_FILE = "state.json.bak";
    /** Pre-compact snapshot file name pattern (suffix with timestamp). */
    public static final String PRECOMPACT_SNAPSHOT_PREFIX = "state-precompact-";
    /** Archive directory under each pair's directory for rotated knownConstraints. */
    public static final String ARCHIVE_DIR = "archive";
}
