package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.bridge.SupervisorBridge;
import com.github.claudecodegui.bridge.SupervisorMessageBatcher;
import com.github.claudecodegui.session.pair.protocol.PairBudget;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregate state for one running Supervisor Pair.
 *
 * <p>A Pair always has exactly one main session ({@link #mainSessionId}) and
 * one Supervisor agent in the current iteration (single-select picker).
 * The model is structured to allow future N-supervisor extension without
 * breaking changes — see {@link #getSupervisorIds()}.
 */
public class PairSession {

    private final String pairId;
    private final String mainSessionId;
    private final String agentId;
    private final String agentName;
    private final Path pairDir;
    private final Path planSnapshotPath;
    private final SupervisorBridge supervisorBridge;
    private final ProgressManager progressManager;
    private final long startedAt = System.currentTimeMillis();
    /**
     * Epoch ms when this supervisor session's work finished (set when the owning
     * workflow node reaches DONE). Null while running. Surfaced in the snapshot so
     * the supervisor time strip can show 结束时间 / freeze 耗时.
     */
    private volatile Long finishedAt;
    /**
     * Session resume (SR3, session-resume-plan.md): the supervisor's SDK-assigned
     * session_id, captured from the daemon's {@code [SUPERVISOR_SESSION]} line on
     * the first turn (wired in {@code PairHandler.startPairWired}). Mutable — it
     * isn't known at construction. Persisted into {@code NodeRuntime} so a restart
     * can resume this supervisor's transcript. Null until the first turn / on a
     * resume-miss.
     */
    private volatile String supervisorSessionId;
    /**
     * Session resume display (session-resume-plan.md): the prior supervisor
     * session_id whose transcript should be REPLAYED into the supervisor pane once
     * the webview is ready, so the operator can SEE the supervisor's history (the
     * SDK `resume` only loads it into the model's context, it does not re-render
     * the pane). Set when the pair is started in resume mode; consumed once by
     * {@code PairHandler.handleWebviewReady}. Null on non-resume starts.
     */
    private volatile String pendingHistoryReplaySessionId;

    /**
     * Id of the tab that created this Pair. Used by {@link PairSessionManager}
     * and {@code PairHandler} to scope replay + input dispatch back to the
     * originating tab — the SupervisorBridge captured the originating tab's
     * {@code ClaudeSDKBridge} and the {@code SupervisorMessageBatcher} was
     * wired to push messages to the originating tab's webview, so a
     * different tab adopting this Pair would post events to the right
     * daemon but never see the response. Nullable for legacy/test paths
     * that build a session without window context.
     */
    private final String ownerWindowId;

    // Snapshot of the parameters that started the daemon-side supervisor runtime.
    // EventBus uses these to lazily re-run supervisor.start after a daemon restart
    // (in remote mode the Node process's in-memory runtime Map is lost on crash).
    // `model` and `reasoningEffort` are mutable so the right-pane composer can
    // change them mid-session; the new values are picked up on the next daemon
    // lazy-restart (immediate hot-swap is not supported by the supervisor channel
    // yet — would need a new supervisor.setModel daemon protocol).
    private final String agentDescription;
    private final String planContent;
    private final String projectSpec;
    private volatile String model;
    private volatile String reasoningEffort;
    /**
     * Auto-compact threshold (% of context window) to apply on the daemon when
     * this Pair starts or restarts. Null means "leave whatever the daemon has".
     * Stored on the session so {@link EventBus#restartSupervisor()} can replay
     * it after a daemon recovery — and so the user-facing setting flows in
     * from {@link com.github.claudecodegui.settings.SupervisorAgentManager}.
     */
    private volatile Integer autoCompactThreshold;
    /**
     * 2026-06-01: whether this supervisor opted into MCP access (seeded true on
     * bug / unit-test / api-test supervisors). Stored on the session so all
     * three supervisor.start sites — initial ({@link PairSessionManager}),
     * handoff ({@link com.github.claudecodegui.session.pair.rotation.RotationCoordinator})
     * and lazy restart ({@link EventBus#restartSupervisor()}) — replay it.
     */
    private volatile boolean mcpAccess = false;

    private volatile EventBus eventBus;       // wired by PairSessionManager
    private volatile ActionRouter actionRouter;
    private volatile RateLimitWatcher rateLimitWatcher;   // lazy; see getRateLimitWatcher()
    private volatile boolean disposed = false;

    // Protocol v2 (2026-05-24): autonomy-mode trackers.
    // budgetTracker is non-null even with no limits (PairBudget defaults all
    // fields to null, hasAnyLimit() returns false; calling check() is cheap).
    // paused is set by Java when budget exceeds 100% or supervisor escalates
    // a hard C3 — subsequent inject_prompt dispatches are NO-OPed.
    // Contract State Machine v3 (2026-05-25): directiveTracker removed.
    // ContractRegistry is now the single source of truth for outstanding
    // tasks (see {@link #contractRegistry}).
    private volatile PairBudgetTracker budgetTracker;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    // Phase 5 (2026-05-24): autonomy mode.
    // "full" (default, 2026-05-25): C1/C2/C3 全部自决 — alert 走 toast 但不阻塞。
    // "mixed": C1/C2 自决 + alert toast, C3 真停.
    // "strict": 退回 modal escalate (escalate_to_human 仍 modal even when daemon aliases it).
    private volatile String autonomyMode = "full";

    // Phase 6 (2026-05-24): F2 — consecutive directive-ack failures since the
    // last approve_and_continue. When this reaches 3, the supervisor is told
    // to skip the current step (step_blocked event) and the counter resets.
    private final AtomicInteger consecutiveDirectiveFailures = new AtomicInteger(0);

    /**
     * Number of supervisor postEvent round-trips currently in flight on this
     * pair. Incremented by {@link EventBus} when it enters the daemon round-trip,
     * decremented in the finally block. Read by
     * {@code PairSessionManager.hasActiveSupervisorTurn()} so the
     * {@code WebviewWatchdog} can extend its stall timeout while the supervisor
     * is hammering IPC — analogous to how the main AI's
     * {@code StreamMessageCoalescer.isStreamActive()} guards reload during
     * streaming.
     */
    private final AtomicInteger inflightTurnCount = new AtomicInteger(0);

    // Phase 1 (2026-05-23): periodic-monitor infrastructure. Optional — when
    // the `cc-gui.pair.monitor.enabled` flag is off, these stay null and the
    // legacy single-thread-dispatcher path inside EventBus is used directly.
    private volatile PairCoordinator coordinator;
    private volatile EventCollector eventCollector;
    private volatile SupervisorMonitor supervisorMonitor;

    // Phase 2 (2026-05-24): status panel publisher. Always constructed
    // alongside the monitor so it can collect compact-boundary events
    // even before the user opens the panel.
    private volatile PairStatusPusher statusPusher;

    // Phase 6a (2026-05-24): per-pair main-AI observability monitor.
    // Wired by PairSessionManager once mainSessionId is known (usually at
    // pair start, but may be late-bound after the first SDK response).
    private volatile MainAIMonitor mainAIMonitor;

    // Phase 6b (2026-05-24): main-AI rotation infrastructure.
    // - mainAIBridge: thin façade over ClaudeSDKBridge for mainAi.* RPCs.
    //   PairSessionManager constructs it from the same sdkBridge it uses
    //   for the supervisor channel.
    // - claudeSession: bound by PairHandler.handleStart so RotationDecider
    //   can resolve the live session without a separate registry lookup.
    //   Nullable in unit tests / pre-handler-wire intervals.
    private volatile com.github.claudecodegui.provider.claude.MainAIBridge mainAIBridge;
    private volatile com.github.claudecodegui.session.ClaudeSession claudeSession;

    // Contract State Machine v3 (2026-05-25): authoritative plan + contract
    // owner. PairSessionManager constructs and starts these alongside the
    // monitor infrastructure. ContractRegistry replaces the legacy
    // DirectiveTracker (deleted) as the single source of truth for
    // outstanding tasks; deadlines + R1/R2/R3 escalation are driven by
    // DeadlockGuard.
    private volatile com.github.claudecodegui.session.pair.plan.PlanStateMachine planStateMachine;
    private volatile com.github.claudecodegui.session.pair.contract.ContractRegistry contractRegistry;
    private volatile com.github.claudecodegui.session.pair.guard.DeadlockGuard deadlockGuard;
    private volatile com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher transitionDispatcher;

    // Stage C (2026-05-25): split SupervisorMonitor's side responsibilities.
    // HealthWatcher owns supervisor health-state transitions (DEGRADED /
    // UNHEALTHY → rotation). BudgetWatcher is a facade over PairBudgetTracker
    // for uniform watcher access. RotationWatcher consolidates rotation
    // request entry points across the distributed trigger sources.
    private volatile com.github.claudecodegui.session.pair.watcher.HealthWatcher healthWatcher;
    private volatile com.github.claudecodegui.session.pair.watcher.BudgetWatcher budgetWatcher;
    private volatile com.github.claudecodegui.session.pair.watcher.RotationWatcher rotationWatcher;

    public PairSession(
            String pairId,
            String mainSessionId,
            String agentId,
            String agentName,
            Path pairDir,
            Path planSnapshotPath,
            SupervisorBridge supervisorBridge,
            ProgressManager progressManager,
            String agentDescription,
            String planContent,
            String projectSpec,
            String model,
            String ownerWindowId
    ) {
        this.pairId = pairId;
        this.mainSessionId = mainSessionId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.pairDir = pairDir;
        this.planSnapshotPath = planSnapshotPath;
        this.supervisorBridge = supervisorBridge;
        this.progressManager = progressManager;
        this.agentDescription = agentDescription;
        this.planContent = planContent;
        this.projectSpec = projectSpec;
        this.model = model;
        this.ownerWindowId = ownerWindowId;
    }

    /** Back-compat: legacy callers (synthetic tests) that have no window id. */
    public PairSession(
            String pairId,
            String mainSessionId,
            String agentId,
            String agentName,
            Path pairDir,
            Path planSnapshotPath,
            SupervisorBridge supervisorBridge,
            ProgressManager progressManager,
            String agentDescription,
            String planContent,
            String projectSpec,
            String model
    ) {
        this(pairId, mainSessionId, agentId, agentName, pairDir, planSnapshotPath,
                supervisorBridge, progressManager, agentDescription, planContent,
                projectSpec, model, null);
    }

    public String getPairId() { return pairId; }
    public String getMainSessionId() { return mainSessionId; }
    /** Session resume (SR3): supervisor SDK session_id, or null until captured. */
    public String getSupervisorSessionId() { return supervisorSessionId; }
    public void setSupervisorSessionId(String id) {
        if (id != null && !id.isEmpty()) this.supervisorSessionId = id;
    }
    /** Session resume display: stage a prior session_id to replay into the pane. */
    public synchronized void setPendingHistoryReplaySessionId(String id) { this.pendingHistoryReplaySessionId = id; }
    /** Non-consuming read of the staged replay id (null if none). */
    public synchronized String peekPendingHistoryReplaySessionId() { return pendingHistoryReplaySessionId; }
    /**
     * Consume-once: returns the staged replay id and clears it (null if none).
     * Synchronized so the two trigger sites ({@code handleWebviewReady} and the
     * tail of {@code startPairWired}) can't both read the same id and double-replay.
     */
    public synchronized String consumePendingHistoryReplaySessionId() {
        String id = pendingHistoryReplaySessionId;
        pendingHistoryReplaySessionId = null;
        return id;
    }
    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public Path getPairDir() { return pairDir; }
    public Path getPlanSnapshotPath() { return planSnapshotPath; }
    public SupervisorBridge getSupervisorBridge() { return supervisorBridge; }
    public ProgressManager getProgressManager() { return progressManager; }
    public long getStartedAt() { return startedAt; }
    /** Epoch ms the supervisor session finished (node DONE), or null while running. */
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long ts) { this.finishedAt = ts; }
    /** Window id of the tab that created this Pair; nullable. See field doc. */
    public String getOwnerWindowId() { return ownerWindowId; }

    public EventBus getEventBus() { return eventBus; }
    public void setEventBus(EventBus eventBus) { this.eventBus = eventBus; }

    public ActionRouter getActionRouter() { return actionRouter; }
    public void setActionRouter(ActionRouter router) { this.actionRouter = router; }

    /**
     * Per-pair rate-limit auto-resume watchdog (lazily created on first use, so a
     * pair that never hits a limit costs nothing). See {@link RateLimitWatcher}.
     */
    public RateLimitWatcher getRateLimitWatcher() {
        RateLimitWatcher w = rateLimitWatcher;
        if (w == null) {
            synchronized (this) {
                w = rateLimitWatcher;
                if (w == null) {
                    w = new RateLimitWatcher(this);
                    rateLimitWatcher = w;
                }
            }
        }
        return w;
    }

    public boolean isDisposed() { return disposed; }
    public void markDisposed() {
        this.disposed = true;
        RateLimitWatcher rlw = this.rateLimitWatcher;
        if (rlw != null) {
            try { rlw.dispose(); } catch (Exception ignored) { }
        }
        // Contract State Machine v3 (2026-05-25): DirectiveTracker removed —
        // ContractRegistry below owns all directive timing now.
        // Contract State Machine v3 (2026-05-25): dispose order matters —
        // dispatcher first (removes its plan listener), then guard (stops
        // scheduler), then registry (cancels deadline timers), then plan SM
        // (clears listeners). Each is null-safe.
        com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher td = this.transitionDispatcher;
        if (td != null) {
            try { td.stop(); } catch (Exception ignored) { }
        }
        com.github.claudecodegui.session.pair.guard.DeadlockGuard dg = this.deadlockGuard;
        if (dg != null) {
            try { dg.stop(); } catch (Exception ignored) { }
        }
        com.github.claudecodegui.session.pair.contract.ContractRegistry cr = this.contractRegistry;
        if (cr != null) {
            try { cr.dispose(); } catch (Exception ignored) { }
        }
        com.github.claudecodegui.session.pair.plan.PlanStateMachine sm = this.planStateMachine;
        if (sm != null) {
            try { sm.stop(); } catch (Exception ignored) { }
        }
        // Contract State Machine v3 (2026-05-25): stop pusher's own scheduler.
        PairStatusPusher sp = this.statusPusher;
        if (sp != null) {
            try { sp.stopPeriodicPush(); } catch (Exception ignored) { }
        }
    }

    // ---- Protocol v2 (2026-05-24): trackers + pause ----

    public PairBudgetTracker getBudgetTracker() { return budgetTracker; }
    public void setBudgetTracker(PairBudgetTracker tracker) { this.budgetTracker = tracker; }

    /** True after pause() — supervisor monitor + event publishers should stop
     *  dispatching new directives / events. The tracker remains alive for late
     *  ack arrivals to be cancelled cleanly. */
    public boolean isPaused() { return paused.get(); }

    /** Idempotent pause. Returns true the first time it transitions; false if
     *  already paused. */
    public boolean pause() {
        return paused.compareAndSet(false, true);
    }

    /** Phase 6 (2026-05-24): idempotent resume. Returns true the first time it
     *  transitions paused→running; false if already running. Caller is
     *  responsible for taking any compensating action (e.g. extending the
     *  budget) so the monitor does not immediately re-pause. */
    public boolean resume() {
        return paused.compareAndSet(true, false);
    }

    // Phase 6 (2026-05-24): F2 — consecutive inject_prompt failure counter.
    // Incremented on directive_lost timeout; reset on approve_and_continue
    // (step advanced) or pair_resume. When 3 are observed in a row,
    // PairHandler emits step_blocked so the supervisor can skip the step.
    public int incrementDirectiveFailure() { return consecutiveDirectiveFailures.incrementAndGet(); }
    public void resetDirectiveFailures() { consecutiveDirectiveFailures.set(0); }
    public int getDirectiveFailures() { return consecutiveDirectiveFailures.get(); }

    public String getAutonomyMode() { return autonomyMode; }
    /** Set autonomy mode. Valid: "strict" | "mixed" | "full". Invalid values
     *  are coerced to "full" (matches the new 2026-05-25 default). Idempotent. */
    public void setAutonomyMode(String mode) {
        if ("strict".equals(mode) || "mixed".equals(mode) || "full".equals(mode)) {
            this.autonomyMode = mode;
        } else {
            this.autonomyMode = "full";
        }
    }

    /** Tracks supervisor round-trips so watchdog can grace IPC bursts. */
    public int enterTurn() { return inflightTurnCount.incrementAndGet(); }
    public int exitTurn() { return Math.max(0, inflightTurnCount.decrementAndGet()); }
    public boolean hasInflightTurn() { return inflightTurnCount.get() > 0; }

    /**
     * Per-pair IPC batcher that coalesces {@code [SUPERVISOR_MSG]} envelopes
     * before pushing them to the webview. Owned by {@code PairHandler}
     * (created in {@code handleStart}); {@code PairSessionManager.stopPair}
     * shuts it down so the scheduler thread doesn't leak after dispose.
     */
    private volatile SupervisorMessageBatcher messageBatcher;
    public SupervisorMessageBatcher getMessageBatcher() { return messageBatcher; }
    public void setMessageBatcher(SupervisorMessageBatcher batcher) { this.messageBatcher = batcher; }

    // ---- Phase 1: monitor infrastructure accessors ----

    public PairCoordinator getCoordinator() { return coordinator; }
    public void setCoordinator(PairCoordinator coordinator) { this.coordinator = coordinator; }

    public EventCollector getEventCollector() { return eventCollector; }
    public void setEventCollector(EventCollector eventCollector) { this.eventCollector = eventCollector; }

    public SupervisorMonitor getSupervisorMonitor() { return supervisorMonitor; }
    public void setSupervisorMonitor(SupervisorMonitor monitor) { this.supervisorMonitor = monitor; }

    public PairStatusPusher getStatusPusher() { return statusPusher; }
    public void setStatusPusher(PairStatusPusher pusher) { this.statusPusher = pusher; }

    public MainAIMonitor getMainAIMonitor() { return mainAIMonitor; }
    public void setMainAIMonitor(MainAIMonitor monitor) { this.mainAIMonitor = monitor; }

    public com.github.claudecodegui.provider.claude.MainAIBridge getMainAIBridge() { return mainAIBridge; }
    public void setMainAIBridge(com.github.claudecodegui.provider.claude.MainAIBridge bridge) { this.mainAIBridge = bridge; }

    public com.github.claudecodegui.session.ClaudeSession getClaudeSession() { return claudeSession; }
    public void setClaudeSession(com.github.claudecodegui.session.ClaudeSession session) {
        this.claudeSession = session;
    }

    // ---- Contract State Machine v3 (2026-05-25) accessors ----

    public com.github.claudecodegui.session.pair.plan.PlanStateMachine getPlanStateMachine() {
        return planStateMachine;
    }
    public void setPlanStateMachine(com.github.claudecodegui.session.pair.plan.PlanStateMachine sm) {
        this.planStateMachine = sm;
    }

    public com.github.claudecodegui.session.pair.contract.ContractRegistry getContractRegistry() {
        return contractRegistry;
    }
    public void setContractRegistry(com.github.claudecodegui.session.pair.contract.ContractRegistry r) {
        this.contractRegistry = r;
    }

    public com.github.claudecodegui.session.pair.guard.DeadlockGuard getDeadlockGuard() {
        return deadlockGuard;
    }
    public void setDeadlockGuard(com.github.claudecodegui.session.pair.guard.DeadlockGuard g) {
        this.deadlockGuard = g;
    }

    public com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher getTransitionDispatcher() {
        return transitionDispatcher;
    }
    public void setTransitionDispatcher(com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher d) {
        this.transitionDispatcher = d;
    }

    public com.github.claudecodegui.session.pair.watcher.HealthWatcher getHealthWatcher() {
        return healthWatcher;
    }
    public void setHealthWatcher(com.github.claudecodegui.session.pair.watcher.HealthWatcher w) {
        this.healthWatcher = w;
    }

    public com.github.claudecodegui.session.pair.watcher.BudgetWatcher getBudgetWatcher() {
        return budgetWatcher;
    }
    public void setBudgetWatcher(com.github.claudecodegui.session.pair.watcher.BudgetWatcher w) {
        this.budgetWatcher = w;
    }

    public com.github.claudecodegui.session.pair.watcher.RotationWatcher getRotationWatcher() {
        return rotationWatcher;
    }
    public void setRotationWatcher(com.github.claudecodegui.session.pair.watcher.RotationWatcher w) {
        this.rotationWatcher = w;
    }

    /**
     * Phase 4 (2026-05-24): atomic swap of the daemon-side supervisor session
     * key. Called by {@code RotationCoordinator} after a successful new-start
     * + old-stop. The pair's stable identity ({@link #pairId} / {@link #agentId})
     * never changes; only the per-generation runtime key on the daemon does.
     */
    public void swapSupervisorId(String newSupervisorId) {
        if (newSupervisorId == null || newSupervisorId.isEmpty()) {
            throw new IllegalArgumentException("newSupervisorId required");
        }
        supervisorBridge.setSupervisorId(newSupervisorId);
    }

    /**
     * True when the periodic-monitor path is wired and active. EventBus.publish
     * routes through {@link EventCollector} in this mode; otherwise the legacy
     * single-thread dispatcher is used.
     */
    public boolean isMonitorEnabled() {
        return supervisorMonitor != null && eventCollector != null && coordinator != null;
    }

    public String getAgentDescription() { return agentDescription; }
    public String getPlanContent() { return planContent; }
    public String getProjectSpec() { return projectSpec; }
    public String getModel() { return model; }

    /**
     * Replace the model used on next supervisor restart. Pass null/empty to
     * clear the override and fall back to the agent's configured default on
     * the daemon side. Does NOT trigger an immediate restart — the new value
     * takes effect when {@code EventBus.restartSupervisor()} fires next
     * (typically on daemon crash recovery or when the user toggles the
     * supervisor off/on).
     */
    public void setModel(String model) {
        this.model = (model != null && !model.isEmpty()) ? model : null;
    }

    public String getReasoningEffort() { return reasoningEffort; }

    /**
     * Replace the reasoning-effort hint used on next supervisor restart.
     * Like {@link #setModel(String)}, this is not hot-swapped — the daemon
     * supervisor channel currently has no setReasoning protocol.
     */
    public void setReasoningEffort(String effort) {
        this.reasoningEffort = (effort != null && !effort.isEmpty()) ? effort : null;
    }

    public Integer getAutoCompactThreshold() { return autoCompactThreshold; }
    public void setAutoCompactThreshold(Integer v) { this.autoCompactThreshold = v; }

    public boolean isMcpAccess() { return mcpAccess; }
    public void setMcpAccess(boolean v) { this.mcpAccess = v; }

    /**
     * Current Supervisor agent ids (always size 1 in current iteration).
     * Returned as a list to leave room for multi-supervisor mode later.
     */
    public List<String> getSupervisorIds() {
        return Collections.singletonList(agentId);
    }
}
