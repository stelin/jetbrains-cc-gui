package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.protocol.BudgetStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 2 (2026-05-24): immutable status snapshot pushed from
 * {@link PairStatusPusher} to the webview's Pair status panel.
 *
 * <p>Fields are populated lazily — callers should fill what they have and
 * leave the rest null/zero. {@link #toJson()} omits null fields so the
 * webview can use a deep-equal change detector to suppress no-op renders.
 */
public final class PairStatusSnapshot {

    public final String pairId;
    /** Phase 4+: rotation generation counter. 0 for first-generation pairs. */
    public final int generation;
    /** Coordinator state (IDLE / MAIN_TURN / TICK / ROTATING). */
    public final PairCoordinator.State state;
    /** Monitor health (HEALTHY / DEGRADED / UNHEALTHY). */
    public final HealthState health;

    /** SDK-reported context window usage ratio 0..1, or null if not yet sampled. */
    public final Double supervisorContextRatio;
    /** SDK-reported total used tokens, or null if not yet sampled. */
    public final Long supervisorUsedTokens;
    /** SDK-reported context window size, or null if not yet sampled. */
    public final Long supervisorContextLimit;

    /** Cumulative auto-compactions observed during this supervisor's lifetime. */
    public final int compactCount;
    /** 2026-05-25: cumulative supervisor rotations performed for this pair
     *  (separate from {@link #generation}; rotationCount = generation - 1
     *  for a healthy linear lifetime, but they can diverge during recovery). */
    public final int supervisorRotationCount;
    /** 2026-05-25: cumulative main-AI rotations performed for this pair. */
    public final int mainAiRotationCount;
    /** 2026-05-25: cumulative main-AI auto-compactions observed for this pair. */
    public final int mainAiCompactCount;
    /** Ms since last successful supervisor turn end, or null if no turn yet. */
    public final Long lastActivityAgoMs;
    /** Current EventCollector buffer size. */
    public final int pendingEvents;
    /** Cumulative drop count from the EventCollector ring. */
    public final int totalDroppedEvents;
    /** Cumulative monitor tick count. */
    public final long tickCount;
    /** Wall-clock ms of the most recent tick start; 0 if never. */
    public final long lastTickStartMs;
    /** Wall-clock ms of the most recent tick end; 0 if never. */
    public final long lastTickEndMs;

    /** Recent alerts (oldest → newest). */
    public final List<Alert> recentAlerts;

    // Phase 5 (2026-05-24): autonomy-mode fields surfaced for the webview's
    // DecisionTimeline / AutonomyToggle / status panel. All nullable — pushers
    // that haven't yet wired them leave them null and the webview falls back
    // to defaults (autonomyMode = 'full' since 2026-05-25, empty timeline).
    public final List<L2State.DecisionEntry> recentDecisions;
    public final String autonomyMode;       // "strict" | "mixed" | "full"
    public final BudgetStatus budgetStatus;
    public final Boolean paused;            // true when pair was paused by budget / C3

    /**
     * Contract State Machine v3 (2026-05-25): recent coordinator events
     * (plan transitions / contract issue / discharge / retry / escalate /
     * dispatcher wake). Surfaced to the CoordinatorEventStrip UI so the user
     * can see what DeadlockGuard / TransitionDispatcher / ContractRegistry
     * are doing in real time. Oldest → newest; capped at 15 by the pusher.
     */
    public final List<CoordinatorEvent> recentCoordinatorEvents;

    // Contract State Machine v3 (2026-05-25): live activity counters so the
    // operator sees the system moving even when long-running counters
    // (rotation / compaction) stay at 0. All come from ContractRegistry.
    /** Currently OPEN/RECEIVED contracts in the registry. */
    public final int openContractCount;
    /** Session-lifetime cumulative contracts issued (includes retries). */
    public final long totalIssuedContracts;
    /** Session-lifetime cumulative R1/R2 retries triggered. */
    public final long totalRetriedContracts;
    /** Session-lifetime cumulative DISCHARGED contracts. */
    public final long totalDischargedContracts;
    /** Session-lifetime cumulative R3-escalated contracts. */
    public final long totalEscalatedContracts;

    /**
     * 2026-05-28: authoritative {@link com.github.claudecodegui.session.pair.plan.Plan.PlanState}
     * name (INIT/ACTIVE/WAITING/DONE/ABORTED), or null when no plan exists yet.
     * Distinct from {@link #state} (the coordinator runtime state). The webview
     * gates the supervisor Stop button on this — enabled only while ACTIVE, so
     * the user can interrupt during the "output ended but Liveness guard still
     * counting down" window that the thinking/streaming flags miss.
     */
    public final String planState;
    /** 2026-05-28: active sub-state (EXECUTING/PENDING_DISCHARGE/PENDING_DECISION) or null. */
    public final String planSubState;

    private PairStatusSnapshot(Builder b) {
        this.pairId = b.pairId;
        this.generation = b.generation;
        this.state = b.state;
        this.health = b.health;
        this.supervisorContextRatio = b.supervisorContextRatio;
        this.supervisorUsedTokens = b.supervisorUsedTokens;
        this.supervisorContextLimit = b.supervisorContextLimit;
        this.compactCount = b.compactCount;
        this.supervisorRotationCount = b.supervisorRotationCount;
        this.mainAiRotationCount = b.mainAiRotationCount;
        this.mainAiCompactCount = b.mainAiCompactCount;
        this.lastActivityAgoMs = b.lastActivityAgoMs;
        this.pendingEvents = b.pendingEvents;
        this.totalDroppedEvents = b.totalDroppedEvents;
        this.tickCount = b.tickCount;
        this.lastTickStartMs = b.lastTickStartMs;
        this.lastTickEndMs = b.lastTickEndMs;
        this.recentAlerts = Collections.unmodifiableList(new ArrayList<>(b.recentAlerts));
        this.recentDecisions = b.recentDecisions == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.recentDecisions));
        this.autonomyMode = b.autonomyMode;
        this.budgetStatus = b.budgetStatus;
        this.paused = b.paused;
        this.recentCoordinatorEvents = b.recentCoordinatorEvents == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.recentCoordinatorEvents));
        this.openContractCount = b.openContractCount;
        this.totalIssuedContracts = b.totalIssuedContracts;
        this.totalRetriedContracts = b.totalRetriedContracts;
        this.totalDischargedContracts = b.totalDischargedContracts;
        this.totalEscalatedContracts = b.totalEscalatedContracts;
        this.planState = b.planState;
        this.planSubState = b.planSubState;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("pairId", pairId);
        o.addProperty("generation", generation);
        if (state != null) o.addProperty("state", state.name());
        if (health != null) o.addProperty("health", health.name());
        if (supervisorContextRatio != null) o.addProperty("supervisorContextRatio", supervisorContextRatio);
        if (supervisorUsedTokens != null) o.addProperty("supervisorUsedTokens", supervisorUsedTokens);
        if (supervisorContextLimit != null) o.addProperty("supervisorContextLimit", supervisorContextLimit);
        o.addProperty("compactCount", compactCount);
        o.addProperty("supervisorRotationCount", supervisorRotationCount);
        o.addProperty("mainAiRotationCount", mainAiRotationCount);
        o.addProperty("mainAiCompactCount", mainAiCompactCount);
        if (lastActivityAgoMs != null) o.addProperty("lastActivityAgoMs", lastActivityAgoMs);
        o.addProperty("pendingEvents", pendingEvents);
        o.addProperty("totalDroppedEvents", totalDroppedEvents);
        o.addProperty("tickCount", tickCount);
        o.addProperty("lastTickStartMs", lastTickStartMs);
        o.addProperty("lastTickEndMs", lastTickEndMs);
        JsonArray alerts = new JsonArray();
        for (Alert a : recentAlerts) alerts.add(a.toJson());
        o.add("recentAlerts", alerts);
        // Phase 5 (2026-05-24): autonomy surfaces.
        if (!recentDecisions.isEmpty()) {
            JsonArray dArr = new JsonArray();
            for (L2State.DecisionEntry d : recentDecisions) {
                dArr.add(decisionEntryToJson(d));
            }
            o.add("recentDecisions", dArr);
        }
        if (autonomyMode != null) o.addProperty("autonomyMode", autonomyMode);
        if (budgetStatus != null) {
            JsonObject bs = new JsonObject();
            bs.addProperty("tokenRatio", budgetStatus.tokenRatio);
            bs.addProperty("durationRatio", budgetStatus.durationRatio);
            bs.addProperty("stepRatio", budgetStatus.stepRatio);
            bs.addProperty("subagentRatio", budgetStatus.subagentRatio);
            bs.addProperty("maxRatio", budgetStatus.maxRatio());
            o.add("budgetStatus", bs);
        }
        if (paused != null) o.addProperty("paused", paused);
        if (!recentCoordinatorEvents.isEmpty()) {
            JsonArray ce = new JsonArray();
            for (CoordinatorEvent e : recentCoordinatorEvents) ce.add(e.toJson());
            o.add("recentCoordinatorEvents", ce);
        }
        o.addProperty("openContractCount", openContractCount);
        o.addProperty("totalIssuedContracts", totalIssuedContracts);
        o.addProperty("totalRetriedContracts", totalRetriedContracts);
        o.addProperty("totalDischargedContracts", totalDischargedContracts);
        o.addProperty("totalEscalatedContracts", totalEscalatedContracts);
        if (planState != null) o.addProperty("planState", planState);
        if (planSubState != null) o.addProperty("planSubState", planSubState);
        return o;
    }

    /** Mirrors L2State.DecisionEntry shape but with sparse-field semantics
     *  (omit nulls so the webview's deep-equal change detector behaves). */
    private static JsonObject decisionEntryToJson(L2State.DecisionEntry d) {
        JsonObject e = new JsonObject();
        e.addProperty("ts", d.ts);
        if (d.action != null) e.addProperty("action", d.action);
        if (d.reason != null) e.addProperty("reason", d.reason);
        if (d.result != null) e.addProperty("result", d.result);
        if (d.confidence != null) e.addProperty("confidence", d.confidence);
        if (d.category != null) e.addProperty("category", d.category);
        if (d.severity != null) e.addProperty("severity", d.severity);
        if (d.chosenCandidate != null) e.addProperty("chosenCandidate", d.chosenCandidate);
        if (d.stepId != null) e.addProperty("stepId", d.stepId);
        if (d.autoMode != null) e.addProperty("autoMode", d.autoMode);
        // 2026-05-26: also skip JsonNull — DecisionEntry.payload is now
        // JsonElement so a Java-null payload that round-tripped through a Gson
        // deepCopy comes back as JsonNull, not Java null. Either form means
        // "absent" for snapshot purposes.
        if (d.payload != null && !d.payload.isJsonNull()) e.add("payload", d.payload);
        return e;
    }

    public static Builder builder(String pairId) {
        return new Builder(pairId);
    }

    /**
     * Contract State Machine v3 (2026-05-25): one line in the coordinator
     * event strip. Sources include PlanStateMachine transitions,
     * ContractRegistry issue/discharge/retry/escalate/cancel, DeadlockGuard
     * R1/R2/R3 triggers (which surface as Contract events), and
     * TransitionDispatcher supervisor wakes.
     */
    public static final class CoordinatorEvent {
        public enum Source { PLAN, CONTRACT, GUARD, DISPATCHER }
        public final long ts;
        public final Source source;
        public final String type;
        public final String message;
        public final String detail;          // optional contractId / stepId

        public CoordinatorEvent(long ts, Source source, String type, String message, String detail) {
            this.ts = ts;
            this.source = source;
            this.type = type;
            this.message = message;
            this.detail = detail;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ts", ts);
            o.addProperty("source", source.name());
            o.addProperty("type", type);
            o.addProperty("message", message);
            if (detail != null) o.addProperty("detail", detail);
            return o;
        }
    }

    /** Severity-tagged single line in the right-pane status panel. */
    public static final class Alert {
        public enum Severity { INFO, WARN, ERROR }
        public final long ts;
        public final Severity severity;
        public final String message;

        public Alert(long ts, Severity severity, String message) {
            this.ts = ts;
            this.severity = severity;
            this.message = message;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ts", ts);
            o.addProperty("severity", severity.name());
            o.addProperty("message", message);
            return o;
        }
    }

    public static final class Builder {
        private final String pairId;
        private int generation = 0;
        private PairCoordinator.State state;
        private HealthState health;
        private Double supervisorContextRatio;
        private Long supervisorUsedTokens;
        private Long supervisorContextLimit;
        private int compactCount;
        private int supervisorRotationCount;
        private int mainAiRotationCount;
        private int mainAiCompactCount;
        private Long lastActivityAgoMs;
        private int pendingEvents;
        private int totalDroppedEvents;
        private long tickCount;
        private long lastTickStartMs;
        private long lastTickEndMs;
        private List<Alert> recentAlerts = new ArrayList<>();
        // Phase 5 (2026-05-24): autonomy surfaces.
        private List<L2State.DecisionEntry> recentDecisions;
        private String autonomyMode;
        private BudgetStatus budgetStatus;
        private Boolean paused;
        private List<CoordinatorEvent> recentCoordinatorEvents;
        private int openContractCount;
        private long totalIssuedContracts;
        private long totalRetriedContracts;
        private long totalDischargedContracts;
        private long totalEscalatedContracts;
        private String planState;
        private String planSubState;

        private Builder(String pairId) { this.pairId = pairId; }

        public Builder generation(int v) { this.generation = v; return this; }
        public Builder state(PairCoordinator.State v) { this.state = v; return this; }
        public Builder health(HealthState v) { this.health = v; return this; }
        public Builder supervisorContextRatio(Double v) { this.supervisorContextRatio = v; return this; }
        public Builder supervisorUsedTokens(Long v) { this.supervisorUsedTokens = v; return this; }
        public Builder supervisorContextLimit(Long v) { this.supervisorContextLimit = v; return this; }
        public Builder compactCount(int v) { this.compactCount = v; return this; }
        public Builder supervisorRotationCount(int v) { this.supervisorRotationCount = v; return this; }
        public Builder mainAiRotationCount(int v) { this.mainAiRotationCount = v; return this; }
        public Builder mainAiCompactCount(int v) { this.mainAiCompactCount = v; return this; }
        public Builder lastActivityAgoMs(Long v) { this.lastActivityAgoMs = v; return this; }
        public Builder pendingEvents(int v) { this.pendingEvents = v; return this; }
        public Builder totalDroppedEvents(int v) { this.totalDroppedEvents = v; return this; }
        public Builder tickCount(long v) { this.tickCount = v; return this; }
        public Builder lastTickStartMs(long v) { this.lastTickStartMs = v; return this; }
        public Builder lastTickEndMs(long v) { this.lastTickEndMs = v; return this; }
        public Builder recentAlerts(List<Alert> v) {
            this.recentAlerts = v != null ? v : new ArrayList<>(); return this;
        }
        public Builder recentDecisions(List<L2State.DecisionEntry> v) { this.recentDecisions = v; return this; }
        public Builder autonomyMode(String v) { this.autonomyMode = v; return this; }
        public Builder budgetStatus(BudgetStatus v) { this.budgetStatus = v; return this; }
        public Builder paused(Boolean v) { this.paused = v; return this; }
        public Builder recentCoordinatorEvents(List<CoordinatorEvent> v) {
            this.recentCoordinatorEvents = v; return this;
        }
        public Builder openContractCount(int v) { this.openContractCount = v; return this; }
        public Builder totalIssuedContracts(long v) { this.totalIssuedContracts = v; return this; }
        public Builder totalRetriedContracts(long v) { this.totalRetriedContracts = v; return this; }
        public Builder totalDischargedContracts(long v) { this.totalDischargedContracts = v; return this; }
        public Builder totalEscalatedContracts(long v) { this.totalEscalatedContracts = v; return this; }
        public Builder planState(String v) { this.planState = v; return this; }
        public Builder planSubState(String v) { this.planSubState = v; return this; }

        public PairStatusSnapshot build() {
            return new PairStatusSnapshot(this);
        }
    }
}
