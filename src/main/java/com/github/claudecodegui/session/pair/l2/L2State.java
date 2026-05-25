package com.github.claudecodegui.session.pair.l2;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 (2026-05-24): on-disk L2 durable state for one Pair.
 *
 * <p>Designed for survival across daemon restarts and supervisor rotations:
 * everything load-bearing about the Pair's "where am I, what have I decided"
 * lives here so a fresh supervisor session can be re-hydrated without
 * relying on any in-memory recall.
 *
 * <p>Field shape matches docs/plans/2026-05-23-supervisor-monitor-rotation-implementation.md
 * §5 — keep them aligned when schema changes.
 *
 * <p>Mutability: this POJO is mutable by design; {@link L2Store.update} runs
 * a mutator function on a deep copy and writes the result atomically, so
 * external callers never see partial state.
 */
public class L2State {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    public int schemaVersion = L2Schema.CURRENT_VERSION;
    public String pairId;
    public int generation = 0;
    public long createdAt;
    public long lastUpdated;

    /** Optional snapshot of the most-recent rotation. Null until first rotation. */
    public RotationInfo lastRotation;

    public AnchoredFacts anchoredFacts = new AnchoredFacts();

    public List<PlanProgressEntry> planProgress = new ArrayList<>();

    /** Path → state. Use LinkedHashMap to preserve insertion order across (de)serialisation. */
    public Map<String, FileStateEntry> fileState = new LinkedHashMap<>();

    /** Ring; oldest evicted at {@link L2Schema#RECENT_DECISIONS_MAX}. */
    public List<DecisionEntry> recentDecisions = new ArrayList<>();

    /** Hard rules accumulated from user/feedback. */
    public List<String> knownConstraints = new ArrayList<>();

    /** Ring; oldest evicted at {@link L2Schema#COMPACTION_HISTORY_MAX}. */
    public List<CompactionEntry> compactionHistory = new ArrayList<>();

    public int rotationCount = 0;

    public Metrics metrics = new Metrics();

    /**
     * Phase 6a (2026-05-24): main-AI observability sub-state. Nullable —
     * Pairs that haven't yet linked to a main-AI session don't have it.
     * Lives inside the pair's L2 file (not a separate file) because main AI
     * meaningful state is always pair-scoped in the current model.
     */
    public MainAIState mainAI;

    public static L2State initial(String pairId) {
        L2State s = new L2State();
        s.pairId = pairId;
        long now = System.currentTimeMillis();
        s.createdAt = now;
        s.lastUpdated = now;
        return s;
    }

    /**
     * Deep copy via JSON round-trip. Used by {@link L2Store#update} so the
     * mutator works on an isolated copy and we never publish a partially-mutated state.
     */
    public L2State deepCopy() {
        return GSON.fromJson(GSON.toJson(this), L2State.class);
    }

    /** Defensive ring-buffer trim helpers — call after mutators that append.
     *  Phase 3 v2 (2026-05-24): recentDecisions also has a 24h time-window trim
     *  to prevent unbounded growth when capacity hasn't yet hit the cap. */
    public void trimRings() {
        while (recentDecisions.size() > L2Schema.RECENT_DECISIONS_MAX) {
            recentDecisions.remove(0);
        }
        long now = System.currentTimeMillis();
        recentDecisions.removeIf(d -> d.ts > 0 && (now - d.ts) > L2Schema.RECENT_DECISIONS_MAX_WINDOW_MS);
        while (compactionHistory.size() > L2Schema.COMPACTION_HISTORY_MAX) {
            compactionHistory.remove(0);
        }
        if (mainAI != null && mainAI.recentUserMessages != null) {
            while (mainAI.recentUserMessages.size() > L2Schema.MAIN_AI_RECENT_USER_MAX) {
                mainAI.recentUserMessages.remove(0);
            }
        }
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }

    public static L2State fromJson(String json) {
        return GSON.fromJson(json, L2State.class);
    }

    // ─── Nested types ────────────────────────────────────────────────────

    public static class AnchoredFacts {
        public Integer currentStep;
        public Integer totalSteps;
        public String currentStepTitle;
        public String blockedOn;
        public String lastVerifyCmd;
        public String lastVerifyResult;
        public Long lastVerifyAt;
    }

    public static class PlanProgressEntry {
        public int step;
        /** "todo" | "in_progress" | "done" | "blocked" | "skipped" */
        public String status;
        public List<String> filesChanged = new ArrayList<>();
        public Integer attempts;
        public String lastError;
        public Long completedAt;
    }

    public static class FileStateEntry {
        public Long mtime;
        public String lastTouchedBy; // e.g. "step5"
        public Integer linesChanged;
        /** "high" | "medium" | "low" — successor will verify low-confidence facts. */
        public String confidence = "high";
    }

    public static class DecisionEntry {
        public long ts;
        public String action;
        public String reason;
        public JsonObject payload;
        public String result;
        public String confidence = "high";
        // Protocol v2 (2026-05-24): autonomy-mode fields. All nullable so legacy
        // records loaded from older L2 files stay intact (Gson leaves missing
        // fields as Java defaults). Schema docs at:
        //   docs/plans/2026-05-24-supervisor-autonomous-collab-implementation.md §4.4
        public String category;             // A | B | C1 | C2 | C3
        public String severity;             // info | warn | alert
        public java.util.List<CandidateEntry> candidates;
        public String chosenCandidate;
        public java.util.List<EvidenceEntry> evidence;
        public Integer stepId;
        public Boolean autoMode;
    }

    public static class CandidateEntry {
        public String option;
        public Double score;
    }

    public static class EvidenceEntry {
        public String kind;                 // file_read | subagent | main_turn | verification
        public String path;
        public String lines;
        public String agentId;
        public String turnId;
        public String output;
    }

    public static class CompactionEntry {
        public long at;
        public String trigger; // "sdk_auto" | "manual" | "rotation"
        public Double ratioBefore;
    }

    public static class RotationInfo {
        public String from;
        public String to;
        public long at;
        public String reason;
        /** "producer" | "l2_fallback" | "l2_unhealthy" | "none" */
        @SerializedName("handoffSource")
        public String handoffSource;
    }

    public static class Metrics {
        public long totalTicks;
        public long totalActions;
        public long totalInjections;
        public long degradedCount;
        public long unhealthyCount;
    }

    /**
     * Phase 6a (2026-05-24): main-AI observability fields. Updated by
     * {@code MainAIMonitor} on every turn boundary so post-restart
     * dashboards / Phase 6b rotation triggers can read history.
     *
     * <p>NOT a complete handoff doc — Phase 6b will add fields like
     * {@code recentUserMessages}, {@code lastAssistantSummary}, and
     * {@code currentTask} for the eventual rotation pipeline. Today this is
     * pure telemetry + stall accounting.
     */
    public static class MainAIState {
        /** SDK-assigned session UUID; null until the first response arrives. */
        public String sessionId;
        public long lastTurnStartMs;
        public long lastTurnEndMs;
        public long turnCount;
        public long errorCount;
        public long stallCount;
        /** Wall-clock ms of the most recent stall fire (5min no turn_end). */
        public long lastStallMs;
        /** Most recent SDK-reported context-window usage ratio 0..1. */
        public Double lastContextRatio;
        public Long lastUsedTokens;
        public Long lastContextLimit;
        /** Cumulative {@code compact_boundary} events observed on main AI. */
        public int compactCount;
        public Long lastCompactAt;
        /**
         * 2026-05-24: Phase 6b auto-rotation bookkeeping. {@code lastRotation}
         * is what the cooldown check reads (instead of falling back to
         * {@code lastStallMs}, which was a temporary heuristic).
         */
        public RotationInfo lastRotation;
        public int rotationCount;
        /**
         * Bounded ring of recent verbatim user-message snapshots, used as a
         * crash-safe fallback when the producer prompt fails to emit a
         * compliant {@code recentUserMessages} field. Captured by
         * {@code MainAIMonitor.captureUserMessage}; trimmed at
         * {@link L2Schema#MAIN_AI_RECENT_USER_MAX}.
         */
        public java.util.List<RecentUserMessageEntry> recentUserMessages
                = new java.util.ArrayList<>();
    }

    /**
     * Verbatim user-message record for main-AI handoff fallback. Mirrors the
     * JSON shape the producer prompt asks for, so the coordinator can drop
     * this directly into {@code MainAIHandoffPromptBuilder.renderFallbackHandoffJson}.
     */
    public static class RecentUserMessageEntry {
        public long ts;
        public String text;

        public RecentUserMessageEntry() { /* gson */ }
        public RecentUserMessageEntry(long ts, String text) {
            this.ts = ts;
            this.text = text;
        }
    }
}
