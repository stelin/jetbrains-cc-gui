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
    // to defaults (e.g. autonomyMode = 'mixed', empty timeline).
    public final List<L2State.DecisionEntry> recentDecisions;
    public final String autonomyMode;       // "strict" | "mixed" | "full"
    public final BudgetStatus budgetStatus;
    public final Boolean paused;            // true when pair was paused by budget / C3

    private PairStatusSnapshot(Builder b) {
        this.pairId = b.pairId;
        this.generation = b.generation;
        this.state = b.state;
        this.health = b.health;
        this.supervisorContextRatio = b.supervisorContextRatio;
        this.supervisorUsedTokens = b.supervisorUsedTokens;
        this.supervisorContextLimit = b.supervisorContextLimit;
        this.compactCount = b.compactCount;
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
        if (d.payload != null) e.add("payload", d.payload);
        return e;
    }

    public static Builder builder(String pairId) {
        return new Builder(pairId);
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

        private Builder(String pairId) { this.pairId = pairId; }

        public Builder generation(int v) { this.generation = v; return this; }
        public Builder state(PairCoordinator.State v) { this.state = v; return this; }
        public Builder health(HealthState v) { this.health = v; return this; }
        public Builder supervisorContextRatio(Double v) { this.supervisorContextRatio = v; return this; }
        public Builder supervisorUsedTokens(Long v) { this.supervisorUsedTokens = v; return this; }
        public Builder supervisorContextLimit(Long v) { this.supervisorContextLimit = v; return this; }
        public Builder compactCount(int v) { this.compactCount = v; return this; }
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

        public PairStatusSnapshot build() {
            return new PairStatusSnapshot(this);
        }
    }
}
