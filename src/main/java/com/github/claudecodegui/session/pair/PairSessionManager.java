package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.bridge.SupervisorBridge;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.claude.MainAIBridge;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.github.claudecodegui.session.pair.prompt.SuccessorPromptBuilder;
import com.github.claudecodegui.session.pair.rotation.MainAIRotationCoordinator;
import com.github.claudecodegui.session.pair.rotation.RotationCoordinator;
import com.github.claudecodegui.session.pair.rotation.RotationDecider;
import com.github.claudecodegui.session.pair.rotation.RotationTriggers;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-project service that owns every active Supervisor Pair on the project.
 *
 * Responsibilities:
 *   - Create / dispose {@link PairSession} instances on user demand.
 *   - Snapshot the plan markdown into the pair directory.
 *   - Wire {@link EventBus} and {@link ActionRouter} to bridge main AI events
 *     and supervisor actions.
 *   - Tear down everything on project close (via {@link Disposable}).
 */
@Service(Service.Level.PROJECT)
public final class PairSessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(PairSessionManager.class);
    private static final long START_DAEMON_TIMEOUT_SEC = 20;

    private final Project project;
    private final ConcurrentHashMap<String, PairSession> pairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mainSessionToPair = new ConcurrentHashMap<>();
    private final PlanSnapshotter planSnapshotter = new PlanSnapshotter();
    /**
     * Phase 3 (2026-05-24): per-project L2 durable-state store. The default
     * implementation writes under {@code ~/.codemoss/pairs} (shared across
     * projects), which is what we want — a Pair is identified by its
     * generated {@code pairId} UUID so cross-project collisions don't happen.
     */
    private final L2Store l2Store = new L2Store();

    /**
     * Phase 4 (2026-05-24): rotation infrastructure. Coordinator does the
     * 14-step swap; decider polls for {@code requestRotation} flags and
     * runs the coordinator under the pair's write lock. Both share a single
     * background thread so multiple pairs serialize naturally.
     */
    private final RotationCoordinator rotationCoordinator = new RotationCoordinator(l2Store);

    /**
     * 2026-05-24: main-AI rotation is now auto-only (manual entry removed).
     * The decider polls each pair every second; trigger evaluation happens
     * inside {@code MainAIMonitor.onTurnEnd} and inside the post-compact path
     * on {@link PairSessionManager}.
     */
    private final MainAIRotationCoordinator mainAIRotationCoordinator = new MainAIRotationCoordinator(l2Store);
    private final RotationDecider rotationDecider = new RotationDecider(
            pairs::values, rotationCoordinator, mainAIRotationCoordinator);

    public PairSessionManager(@NotNull Project project) {
        this.project = project;
        // Start the decider as soon as the service is instantiated; pairs
        // created later are picked up automatically via the supplier.
        rotationDecider.start();
    }

    /** Phase 3: expose for RotationCoordinator (Phase 4+) and integration tests. */
    public L2Store getL2Store() {
        return l2Store;
    }

    /** Phase 4: expose for tests. */
    public RotationCoordinator getRotationCoordinator() {
        return rotationCoordinator;
    }

    public static PairSessionManager getInstance(@NotNull Project project) {
        return project.getService(PairSessionManager.class);
    }

    /**
     * Parameters for {@link #startPair}.
     */
    public static final class StartPairParams {
        public final String mainSessionId;
        public final String agentId;
        /** Optional explicit path to plan markdown. If null, no plan is snapshotted. */
        @Nullable
        public final String planPath;

        /**
         * 2026-05-24: per-launch overrides resolved by the right-pane composer
         * before the daemon starts the SDK. Null on any field means "fall back
         * to the agent's defaults from supervisor-agents.json".
         *
         * <p>Without these, supervisor startup ignored the user's runtime
         * preferences (1M-context toggle + reasoning tier) and the daemon
         * SDK was born at 200k / SDK-default effort, so the user had to
         * re-toggle 1M after every supervisor start to get the right context
         * window. See {@code ModelProviderHandler#applyLongContextSuffix} for
         * the suffix application — kept identical to webview behaviour.
         */
        @Nullable public final String modelOverride;
        @Nullable public final Boolean longContextOverride;
        @Nullable public final String reasoningOverride;

        /**
         * Window id of the tab requesting this Pair. Carried through to
         * {@link PairSession#getOwnerWindowId()} so {@code PairHandler} can
         * scope replay + input dispatch back to the originating tab. Null
         * disables ownership scoping (legacy / test paths).
         */
        @Nullable public final String ownerWindowId;

        public StartPairParams(
                String mainSessionId,
                String agentId,
                @Nullable String planPath,
                @Nullable String modelOverride,
                @Nullable Boolean longContextOverride,
                @Nullable String reasoningOverride,
                @Nullable String ownerWindowId
        ) {
            this.mainSessionId = mainSessionId;
            this.agentId = agentId;
            this.planPath = planPath;
            this.modelOverride = modelOverride;
            this.longContextOverride = longContextOverride;
            this.reasoningOverride = reasoningOverride;
            this.ownerWindowId = ownerWindowId;
        }

        /** Back-compat: composer overrides without windowId. */
        public StartPairParams(
                String mainSessionId,
                String agentId,
                @Nullable String planPath,
                @Nullable String modelOverride,
                @Nullable Boolean longContextOverride,
                @Nullable String reasoningOverride
        ) {
            this(mainSessionId, agentId, planPath, modelOverride, longContextOverride, reasoningOverride, null);
        }

        /** Back-compat constructor for callers that don't supply runtime overrides
         *  (e.g. tests, future direct-API paths). The agent's defaults apply. */
        public StartPairParams(String mainSessionId, String agentId, @Nullable String planPath) {
            this(mainSessionId, agentId, planPath, null, null, null, null);
        }
    }

    /**
     * Create and start a Pair session.
     *
     * @param sdkBridge  daemon bridge to piggy-back on
     * @return started PairSession
     */
    public PairSession startPair(StartPairParams params, ClaudeSDKBridge sdkBridge) throws IOException {
        // Resolve supervisor agent config + project-applicable skills.
        CodemossSettingsService settings = new CodemossSettingsService();
        JsonObject agentConfig = settings.getSupervisorAgentManager().getAgent(params.agentId);
        if (agentConfig == null) {
            throw new IOException("Supervisor agent not found: " + params.agentId);
        }
        String agentName = agentConfig.has("name") ? agentConfig.get("name").getAsString() : params.agentId;
        String description = agentConfig.has("description") ? agentConfig.get("description").getAsString() : "";

        // 2026-05-24: resolve the supervisor's effective model + 1M flag +
        // reasoning tier at startup so the daemon SDK is born with the right
        // context window and thinking budget. Previously only the raw `model`
        // field was forwarded — `defaultLongContext` / `defaultReasoning` and
        // any user runtime preferences were ignored, so "Opus 4.7 1M + max
        // effort" booted as 200k / SDK-default and required a manual re-toggle.
        //
        // Precedence (highest first):
        //   1. Per-launch override from pair_start payload (params.*Override)
        //   2. Agent's stored defaults in supervisor-agents.json
        // The webview always supplies (1) for the right-pane composer state,
        // so this stays aligned with the user's current toggle.
        String baseModelFromConfig = agentConfig.has("model") && !agentConfig.get("model").isJsonNull()
                ? agentConfig.get("model").getAsString()
                : null;
        String baseModel = (params.modelOverride != null && !params.modelOverride.isEmpty())
                ? com.github.claudecodegui.handler.provider.ModelProviderHandler
                        .stripLongContextSuffix(params.modelOverride)
                : baseModelFromConfig;

        boolean longContext;
        if (params.longContextOverride != null) {
            longContext = params.longContextOverride;
        } else if (params.modelOverride != null && !params.modelOverride.isEmpty()) {
            // Webview shipped a model without an explicit flag — trust the
            // suffix it carries.
            longContext = com.github.claudecodegui.handler.provider.ModelProviderHandler
                    .hasLongContextSuffix(params.modelOverride);
        } else if (agentConfig.has("defaultLongContext")
                && !agentConfig.get("defaultLongContext").isJsonNull()) {
            longContext = agentConfig.get("defaultLongContext").getAsBoolean();
        } else {
            longContext = false;
        }

        String model = com.github.claudecodegui.handler.provider.ModelProviderHandler
                .applyLongContextSuffix(baseModel, longContext);

        String reasoning = (params.reasoningOverride != null && !params.reasoningOverride.isEmpty())
                ? params.reasoningOverride
                : (agentConfig.has("defaultReasoning") && !agentConfig.get("defaultReasoning").isJsonNull()
                        ? agentConfig.get("defaultReasoning").getAsString()
                        : null);

        // Pull project-applicable skills and shape them as a compact markdown
        // checklist the Supervisor can use as review rules. We embed only
        // name + description (not the full skill body) to stay token-light.
        String projectSpec = buildProjectSpecFromSkills(settings);

        // Allocate pair_id and per-project pair directory.
        // Phase 6 (2026-05-24): unified to {project}/.claude/pair/<pairId>/ per
        // plan §4.6 — mutagen-synced, same path daemon side uses for
        // spill-to-file (main_turns/, directives/) and save_plan (plan.md).
        // Before this, PairSessionManager wrote to .cc-gui/pairs/ while daemon
        // wrote to .claude/pair/, producing two stale plan.md files and the
        // supervisor's save_plan output never reaching the main AI's view.
        String pairId = "pair_" + UUID.randomUUID().toString().substring(0, 8);
        String basePath = project.getBasePath();
        if (basePath == null) {
            throw new IOException("Cannot start pair: project has no base path");
        }
        Path pairDir = Paths.get(basePath, ".claude", "pair", pairId);
        Files.createDirectories(pairDir);

        // Snapshot plan if provided.
        Path planSnapshot = null;
        String planContent = "";
        if (params.planPath != null && !params.planPath.isEmpty()) {
            Path src = Paths.get(params.planPath);
            planSnapshot = planSnapshotter.snapshot(src, pairDir);
            planContent = planSnapshotter.readSnapshot(planSnapshot);
        }

        // Initialise progress.
        ProgressManager progress = new ProgressManager(pairDir);
        progress.initialise(pairId, params.planPath, params.agentId, agentName);

        // Read the auto-compact threshold from the supervisor config (global
        // setting, same value for every Pair). We pass this on every start so
        // the daemon can update its env even after a config change without
        // restarting the IDE.
        int autoCompactThreshold;
        try {
            autoCompactThreshold = settings.getSupervisorAgentManager().getAutoCompactThreshold();
        } catch (IOException ioe) {
            LOG.warn("[PairSessionManager] Failed to read autoCompactThreshold; using default: "
                    + ioe.getMessage());
            autoCompactThreshold = com.github.claudecodegui.settings.SupervisorAgentManager.DEFAULT_AUTO_COMPACT_THRESHOLD;
        }

        // Build bridge and persistent session shell.
        SupervisorBridge bridge = new SupervisorBridge(sdkBridge, pairId, params.agentId);
        PairSession session = new PairSession(
                pairId,
                params.mainSessionId,
                params.agentId,
                agentName,
                pairDir,
                planSnapshot,
                bridge,
                progress,
                description,
                planContent,
                projectSpec,
                model,
                params.ownerWindowId
        );
        session.setAutoCompactThreshold(autoCompactThreshold);
        // Seed the resolved reasoning tier so EventBus.restartSupervisor +
        // RotationCoordinator pick it up on next daemon restart (both read
        // session.getReasoningEffort()).
        if (reasoning != null && !reasoning.isEmpty()) {
            session.setReasoningEffort(reasoning);
        }

        // Phase 4 (2026-05-24): generation-0 start uses the same daemon
        // command as rotation (supervisor.start with successorPromptAppend),
        // but with the bootstrap template instead of a handoff doc. This
        // gives the initial supervisor an explicit "you are gen 0, initialise
        // anchoredFacts via update_state" cue inline with BASE — see
        // SuccessorPromptBuilder.renderInitialBootstrap docs.
        String bootstrapAppend = SuccessorPromptBuilder.renderInitialBootstrap(
                pairId, abbreviate(planContent, 800), abbreviate(projectSpec, 400));
        try {
            bridge.startWithHandoff(
                    agentName,
                    description,
                    planContent,
                    projectSpec,
                    model,
                    autoCompactThreshold,
                    reasoning,
                    params.agentId, // generation-0 supervisorId == agentId
                    bootstrapAppend,
                    0
            ).get(START_DAEMON_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting supervisor", ie);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Failed to start supervisor on daemon: " + e.getMessage(), e);
        }

        // Wire EventBus and ActionRouter.
        ActionRouter router = new ActionRouter(project, session);
        EventBus bus = new EventBus(session, router);
        session.setActionRouter(router);
        session.setEventBus(bus);

        // Phase 1 (2026-05-23): wire monitor infrastructure. The monitor is
        // always constructed so disposal is symmetric, but only takes over
        // event routing when the `cc-gui.pair.monitor.enabled` feature flag
        // is on (checked dynamically in EventBus.publish — so it can be
        // toggled at runtime without restarting the pair).
        PairCoordinator coordinator = new PairCoordinator(pairId);
        EventCollector collector = new EventCollector(pairId);
        // Phase 5 (2026-05-24): pass L2Store so the monitor can read
        // compactionHistory for rotation-trigger evaluation and write
        // health-transition counters back. Falls back to no-L2 behaviour
        // for unit tests that construct the monitor directly.
        // Contract State Machine v3 (2026-05-25): pass the 24h interval so
        // the periodic tick is effectively disabled. Supervisor is now woken
        // on Contract state changes via TransitionDispatcher and on urgent
        // events (error / off_plan) via EventCollector's urgent path.
        SupervisorMonitor monitor = new SupervisorMonitor(
                session, collector, coordinator,
                SupervisorMonitor.DEADLOCK_FRIENDLY_INTERVAL_MS,
                l2Store);
        session.setCoordinator(coordinator);
        session.setEventCollector(collector);
        session.setSupervisorMonitor(monitor);

        // Phase 2 (2026-05-24): construct the status pusher and wire the
        // compact-boundary handler so SDK auto-compactions update the
        // counter even before the monitor's first health-check tick.
        // Phase 3: pass the L2Store so snapshot.generation reflects the
        // current rotation generation (instead of always reporting 0).
        PairStatusPusher statusPusher = new PairStatusPusher(session, router, l2Store);
        session.setStatusPusher(statusPusher);

        // Stage C (2026-05-25): independent watchers split off from SupervisorMonitor.
        // HealthWatcher owns supervisor health-state transitions; SupervisorMonitor
        // delegates recordSuccess/recordFailure to it. RotationWatcher consolidates
        // rotation request entry points. BudgetWatcher is a facade over
        // PairBudgetTracker (which PairHandler constructs later, so we wire it
        // when getBudgetTracker() returns non-null on first access).
        com.github.claudecodegui.session.pair.watcher.HealthWatcher healthWatcher =
                new com.github.claudecodegui.session.pair.watcher.HealthWatcher(
                        pairId, coordinator, statusPusher, l2Store);
        com.github.claudecodegui.session.pair.watcher.RotationWatcher rotationWatcher =
                new com.github.claudecodegui.session.pair.watcher.RotationWatcher(pairId, coordinator);
        session.setHealthWatcher(healthWatcher);
        session.setRotationWatcher(rotationWatcher);
        bridge.setCompactBoundaryHandler(payload -> {
            // Phase 3 (2026-05-24): also persist a compaction event into L2
            // so the rotation trigger (Phase 5) can count from disk after a
            // restart, not just from the live in-memory counter.
            L2State postL2 = null;
            try {
                postL2 = l2Store.update(pairId, s -> {
                    L2State.CompactionEntry ce = new L2State.CompactionEntry();
                    ce.at = payload != null && payload.has("ts") && !payload.get("ts").isJsonNull()
                            ? payload.get("ts").getAsLong() : System.currentTimeMillis();
                    ce.trigger = payload != null && payload.has("trigger") && !payload.get("trigger").isJsonNull()
                            ? payload.get("trigger").getAsString() : "sdk_auto";
                    s.compactionHistory.add(ce);
                    return s;
                });
            } catch (Exception e) {
                LOG.warn("[PairSessionManager] L2 compaction append failed: " + e.getMessage());
            }
            statusPusher.onCompactBoundary();

            // Phase 5 (2026-05-24): evaluate triggers immediately after the
            // compactCount changes — soft (>=3) and hard (>=5) cross here.
            // Don't refetch ratio (we don't have one fresh); ratio-only triggers
            // are handled by the monitor's periodic refreshContextUsage path.
            if (postL2 != null) {
                try {
                    com.github.claudecodegui.settings.RotationConfig cfg =
                            com.github.claudecodegui.settings.RotationConfig.loadOrDefault(
                                    new CodemossSettingsService().getSupervisorAgentManager());
                    RotationTriggers.Trigger trig = RotationTriggers.evaluate(postL2, null, cfg);
                    if (trig != null) {
                        LOG.info("[PairSessionManager] " + pairId
                                + " post-compact rotation trigger: " + trig.severity + " " + trig.reason);
                        statusPusher.pushAlert(
                                trig.severity == RotationTriggers.Severity.HARD
                                        ? PairStatusSnapshot.Alert.Severity.ERROR
                                        : PairStatusSnapshot.Alert.Severity.WARN,
                                "rotate request: " + trig.reason);
                        PairCoordinator coord = session.getCoordinator();
                        if (coord != null) coord.requestRotation();
                    }
                } catch (Exception e) {
                    LOG.warn("[PairSessionManager] post-compact trigger eval failed: " + e.getMessage());
                }
            }
        });

        // Phase 3: route supervisor's update_state MCP tool emissions to L2.
        // Each call carries a sparse JSON delta — L2Store does the merge +
        // atomic write, ring trim, validation.
        bridge.setStateUpdateHandler(payload -> {
            try {
                if (payload != null && payload.has("delta") && payload.get("delta").isJsonObject()) {
                    l2Store.applyUpdateStateDelta(pairId, payload.getAsJsonObject("delta"));
                }
            } catch (Exception e) {
                LOG.warn("[PairSessionManager] L2 applyUpdateStateDelta failed: " + e.getMessage());
            }
        });

        // Phase 3: PreCompact safety net — dump a timestamped L2 snapshot
        // before the SDK rewrites memory. Surviving the next compact gives
        // the rotation coordinator a recent fallback if the producer prompt
        // also fails post-compact.
        bridge.setPreCompactHandler(payload -> {
            try { l2Store.writePrecompactSnapshot(pairId); }
            catch (Exception e) {
                LOG.warn("[PairSessionManager] L2 writePrecompactSnapshot failed: " + e.getMessage());
            }
        });

        // Phase 3: ensure L2 file exists with pairId/createdAt before any
        // update_state arrives — keeps subsequent reads cheap (cache hit)
        // and gives rotation coordinator something to read on first launch.
        try {
            l2Store.update(pairId, s -> {
                if (s.pairId == null) s.pairId = pairId;
                if (s.createdAt <= 0) s.createdAt = System.currentTimeMillis();
                return s;
            });
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] L2 init for " + pairId + " failed: " + e.getMessage());
        }

        // Phase 6a (2026-05-24): per-pair main-AI observability monitor.
        // Constructed eagerly; mainSessionId may be null at this point
        // (pair started before the first main-AI message). The monitor
        // tolerates a null sessionId and tracks turns as they arrive —
        // when the SDK assigns a sessionId, callers can use
        // {@link #bindMainSessionIdToPair} to update the L2 binding.
        MainAIMonitor mainAIMonitor = new MainAIMonitor(
                pairId, params.mainSessionId, l2Store, statusPusher);
        session.setMainAIMonitor(mainAIMonitor);

        // Phase 6b (2026-05-24): main-AI rotation RPC façade. Wraps the same
        // ClaudeSDKBridge used by the supervisor channel — there's no parallel
        // transport, the daemon routes by command prefix (mainAi.* vs
        // supervisor.*). Stored on the pair so the rotation decider can
        // resolve it on auto-trigger.
        MainAIBridge mainAIBridge = new MainAIBridge(sdkBridge);
        session.setMainAIBridge(mainAIBridge);

        // 2026-05-24 auto-rotate wiring: the monitor's onTurnEnd async path
        // needs the pair (for L2 + pairId), coordinator (to flip the main-AI
        // request flag), and bridge (to fetch context usage). Wired late so
        // we don't change MainAIMonitor's constructor signature for callers
        // that don't yet have these references.
        mainAIMonitor.wireForAutoRotation(session, coordinator, mainAIBridge);

        // Contract State Machine v3 (2026-05-25): construct the new
        // plan/contract owners. ContractRegistry is now the authoritative
        // source of truth for outstanding tasks (DirectiveTracker deleted).
        // Hydration from L2 happens immediately so an IDE restart picks up
        // any saved plan + contracts.
        com.github.claudecodegui.session.pair.plan.PlanStateMachine planSm =
                new com.github.claudecodegui.session.pair.plan.PlanStateMachine(pairId);
        com.github.claudecodegui.session.pair.contract.ContractRegistry contractRegistry =
                new com.github.claudecodegui.session.pair.contract.ContractRegistry(pairId);
        session.setPlanStateMachine(planSm);
        session.setContractRegistry(contractRegistry);

        // Hydrate plan + open contracts from L2 if present (IDE restart path).
        try {
            L2State persisted = l2Store.read(pairId);
            if (persisted != null && persisted.plan != null) {
                planSm.restore(
                        com.github.claudecodegui.session.pair.plan.PlanPersistence.fromPersisted(
                                persisted.plan, pairId));
            }
            // 2026-05-25 fix: only hydrate contracts when the plan is still
            // ACTIVE. For DONE/ABORTED/WAITING/null plans, the contracts are
            // leftover from a finished or aborted session and should not pin
            // openContracts. ContractRegistry.hydrateFromL2 additionally
            // auto-cancels any contract older than MAX_HYDRATION_AGE_MS as a
            // belt-and-suspenders guard.
            boolean planAllowsContractHydration = persisted != null
                    && persisted.plan != null
                    && "ACTIVE".equals(persisted.plan.state);
            if (planAllowsContractHydration
                    && persisted.openContracts != null
                    && !persisted.openContracts.isEmpty()) {
                contractRegistry.hydrateFromL2(
                        com.github.claudecodegui.session.pair.contract.ContractPersistence.fromPersistedList(
                                persisted.openContracts));
            } else if (persisted != null && persisted.openContracts != null
                    && !persisted.openContracts.isEmpty()) {
                LOG.info("[PairSessionManager] " + pairId
                        + " skipping contract hydration (plan state="
                        + (persisted.plan == null ? "null" : persisted.plan.state) + ")");
            }
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] L2 hydration of plan/contracts failed for "
                    + pairId + ": " + e.getMessage());
        }

        planSm.start();
        com.github.claudecodegui.session.pair.guard.DeadlockGuard deadlockGuard =
                new com.github.claudecodegui.session.pair.guard.DeadlockGuard(session, planSm, contractRegistry);
        com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher transitionDispatcher =
                new com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher(session, planSm);
        session.setDeadlockGuard(deadlockGuard);
        session.setTransitionDispatcher(transitionDispatcher);
        // Contract State Machine v3 (2026-05-25): per-contract deadline timer
        // → DeadlockGuard.onContractDeadline. The guard's evaluate logic decides
        // R1/R2/R3. Wired BEFORE start so the first hydrated-from-L2 deadline
        // firing finds a live callback.
        contractRegistry.setDeadlineCallback(deadlockGuard::onContractDeadline);
        // Wire ActionRouter's ContractRegistry listener so issue/retry events
        // deliver to webview (MAIN_AI) or supervisor (SUPERVISOR) automatically.
        router.attachContractRegistryListener();
        // Contract State Machine v3 (2026-05-25): TransitionDispatcher wake
        // → SupervisorMonitor.wakeForPlanTransition + record coordinator
        // event so the UI strip sees the wake. Replaces the old 30s
        // fixedDelay as the primary driver for "forward accumulated events
        // to supervisor + receive its next action".
        transitionDispatcher.setSupervisorWakeCallback(() -> {
            try {
                statusPusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.DISPATCHER,
                        "supervisor_wake",
                        "Plan PENDING_DECISION → waking supervisor",
                        null);
            } catch (Exception e) {
                LOG.debug("[PairSessionManager] dispatcher wake event record failed: " + e.getMessage());
            }
            monitor.wakeForPlanTransition();
        });
        try {
            deadlockGuard.start();
            transitionDispatcher.start();
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] DeadlockGuard/TransitionDispatcher start failed for "
                    + pairId + ": " + e.getMessage());
        }
        // Contract State Machine v3 (2026-05-25): wire coordinator events
        // (plan transitions + contract lifecycle + dispatcher wake) to the
        // status pusher's CoordinatorEvent ring. Each event triggers a hard
        // push so the CoordinatorEventStrip UI updates in real time.
        wireCoordinatorEventListeners(planSm, contractRegistry, transitionDispatcher, statusPusher);
        // Bug fix 2026-05-26: when the plan transitions to DONE, write the
        // COMPLETION_REPORT (this used to live on a SupervisorMonitor tick
        // that is now effectively disabled — see CompletionReportWriter +
        // CompletionDetector + onStepCompleted being orphaned before this fix).
        wireCompletionReportListener(planSm, session, l2Store, statusPusher);
        // Contract State Machine v3 (2026-05-25): start the pusher's own
        // 30s push so SessionCountStrip / PairStatusBar / DecisionTimeline
        // get fresh snapshots even when SupervisorMonitor's (now event-
        // driven) tick stays idle. Also fires an immediate seed push so
        // the UI doesn't sit blank waiting for the first event.
        try {
            statusPusher.startPeriodicPush();
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] statusPusher.startPeriodicPush failed for "
                    + pairId + ": " + e.getMessage());
        }

        try {
            monitor.start();
        } catch (Exception e) {
            // Don't fail pair creation if the monitor can't start — fall back to legacy path.
            LOG.warn("[PairSessionManager] SupervisorMonitor.start failed; legacy path remains: "
                    + e.getMessage());
        }

        pairs.put(pairId, session);
        // mainSessionId is null when the SDK hasn't assigned one yet (the very
        // common case for "start pair before first turn"). ConcurrentHashMap
        // forbids null keys, so we just skip the index — ClaudeMessageHandler's
        // findAttachedPair falls back to "any active pair" anyway.
        if (params.mainSessionId != null && !params.mainSessionId.isEmpty()) {
            mainSessionToPair.put(params.mainSessionId, pairId);
        }
        LOG.info("[PairSessionManager] Started pair " + pairId + " for session " + params.mainSessionId);

        return session;
    }

    /**
     * Phase 6a (2026-05-24): late-binding entry point for the main-AI
     * session id. {@code ClaudeMessageHandler.handleSessionId} can call this
     * once the SDK assigns the UUID to a pair that started before the first
     * turn — common when the user opens the supervisor pane before sending
     * a message. The map is also kept in sync via the existing index.
     */
    public void bindMainSessionIdToPair(String pairId, String mainSessionId) {
        if (pairId == null || mainSessionId == null || mainSessionId.isEmpty()) return;
        PairSession session = pairs.get(pairId);
        if (session == null) return;
        mainSessionToPair.putIfAbsent(mainSessionId, pairId);
        MainAIMonitor mainAI = session.getMainAIMonitor();
        if (mainAI != null) mainAI.rebindSessionId(mainSessionId);
    }

    /** Find the Pair tracking a given main-session id, or null if none. */
    @Nullable
    public PairSession findByMainSession(String mainSessionId) {
        if (mainSessionId == null) return null;
        String pairId = mainSessionToPair.get(mainSessionId);
        return pairId == null ? null : pairs.get(pairId);
    }

    @Nullable
    public PairSession get(String pairId) {
        return pairId == null ? null : pairs.get(pairId);
    }

    public Collection<PairSession> getActivePairs() {
        return pairs.values();
    }

    /**
     * Active pairs owned by the given tab. Used by {@code PairHandler} so a
     * tab's {@code replayActivePairs} and fallback lookups don't pick up pairs
     * created by other tabs — see the cross-tab routing bug discussed in the
     * 2026-05-24 fix.
     *
     * <p>Returns ALL active pairs when {@code ownerWindowId} is null
     * (back-compat for legacy contexts that built a HandlerContext without a
     * window id). Otherwise filters by exact match — pairs with
     * {@code ownerWindowId == null} (e.g. constructed by tests) are excluded.
     */
    public java.util.List<PairSession> getActivePairsOwnedBy(@Nullable String ownerWindowId) {
        if (ownerWindowId == null) {
            return new java.util.ArrayList<>(pairs.values());
        }
        java.util.List<PairSession> out = new java.util.ArrayList<>();
        for (PairSession s : pairs.values()) {
            if (ownerWindowId.equals(s.getOwnerWindowId())) out.add(s);
        }
        return out;
    }

    /**
     * Ownership-aware {@link #get(String)}. Returns the pair only if it is
     * owned by {@code ownerWindowId} (or if the caller passed {@code null},
     * meaning "no ownership scoping"). Lets {@code PairHandler} reject
     * cross-tab user input without scanning the whole map.
     */
    @Nullable
    public PairSession getOwnedBy(@Nullable String pairId, @Nullable String ownerWindowId) {
        PairSession s = get(pairId);
        if (s == null) return null;
        if (ownerWindowId == null) return s;
        return ownerWindowId.equals(s.getOwnerWindowId()) ? s : null;
    }

    /**
     * Stop every pair created by the given tab. Invoked from
     * {@code ClaudeChatWindow.dispose} so a closed tab doesn't leave its
     * {@link PairSession} dangling in the project-scoped map (where the
     * captured {@code ClaudeSDKBridge} reference would point at a disposed
     * daemon and any cross-tab fallback would try to drive it).
     */
    public void stopPairsOwnedBy(@Nullable String ownerWindowId) {
        if (ownerWindowId == null || ownerWindowId.isEmpty()) return;
        for (PairSession s : new java.util.ArrayList<>(pairs.values())) {
            if (ownerWindowId.equals(s.getOwnerWindowId())) {
                try { stopPair(s.getPairId()); }
                catch (Exception e) {
                    LOG.warn("[PairSessionManager] stopPairsOwnedBy failed for "
                            + s.getPairId() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * True if any active pair has a supervisor turn currently in flight on
     * the daemon round-trip. Used by {@code WebviewWatchdog}'s
     * {@code StreamActiveCheck} so a supervisor IPC burst doesn't trip the
     * 45s stall timeout and reload the webview — the supervisor pane has no
     * Java-side cache the webview can replay from, so a reload manifests as
     * "supervisor window vanished."
     */
    public boolean hasActiveSupervisorTurn() {
        for (PairSession s : pairs.values()) {
            if (s != null && !s.isDisposed() && s.hasInflightTurn()) return true;
        }
        return false;
    }

    /**
     * Stop and clean up a Pair. Removes mapping; calls daemon stop; marks
     * progress.json status=stopped.
     *
     * <p>Split into two phases to avoid EDT freezes on tab close:
     * <ol>
     *   <li><b>Synchronous (callers see immediately):</b> remove from maps,
     *       markDisposed, stop in-memory monitors. After this the pair is no
     *       longer discoverable via {@code findByMainSession} / {@code get}
     *       and any cross-tab fallback ignores it.</li>
     *   <li><b>Async (background):</b> daemon {@code supervisor.stop} round-trip
     *       (up to 5s) and {@code progress.json} write. These do not affect
     *       in-process pair state and would otherwise block the EDT — which
     *       is exactly what happened when {@code ClaudeChatWindow.dispose}
     *       called this on tab close (5s freeze observed when the daemon
     *       was busy retrying a contract).</li>
     * </ol>
     */
    public void stopPair(String pairId) {
        PairSession session = pairs.remove(pairId);
        if (session == null) return;
        String mainSid = session.getMainSessionId();
        if (mainSid != null && !mainSid.isEmpty()) {
            mainSessionToPair.remove(mainSid);
        }
        session.markDisposed();

        // Phase 1: stop the monitor (and its scheduler) before tearing down
        // the daemon side — avoids a final tick firing on a half-stopped pair.
        try {
            SupervisorMonitor monitor = session.getSupervisorMonitor();
            if (monitor != null) monitor.stop();
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] SupervisorMonitor.stop failed: " + e.getMessage());
        }
        // Phase 6a (2026-05-24): release the main-AI monitor's stall-detector
        // scheduler so the IDE doesn't leak the daemon thread on pair stop.
        try {
            MainAIMonitor mainAI = session.getMainAIMonitor();
            if (mainAI != null) mainAI.dispose();
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] MainAIMonitor.dispose failed: " + e.getMessage());
        }

        // Phase 2: offload daemon round-trip + file IO to a background thread
        // so EDT callers (tab close in particular) don't freeze the UI for
        // up to 5 seconds waiting on supervisor.stop.
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                session.getSupervisorBridge().stop().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("[PairSessionManager] Supervisor stop did not complete cleanly: " + e.getMessage());
            }

            // Drain + stop the message batcher scheduler so its daemon thread
            // doesn't linger across project sessions.
            try {
                com.github.claudecodegui.bridge.SupervisorMessageBatcher batcher = session.getMessageBatcher();
                if (batcher != null) batcher.shutdown();
            } catch (Exception e) {
                LOG.warn("[PairSessionManager] message batcher shutdown failed: " + e.getMessage());
            }

            try {
                session.getProgressManager().markStatus("stopped");
            } catch (Exception ignored) { /* best-effort */ }

            LOG.info("[PairSessionManager] Stopped pair " + pairId);
        });
    }

    @Override
    public void dispose() {
        // Phase 4: stop the rotation decider FIRST so no in-flight rotation
        // races with pair shutdown.
        try { rotationDecider.stop(); } catch (Exception ignored) { /* best-effort */ }
        for (String id : pairs.keySet().toArray(new String[0])) {
            stopPair(id);
        }
    }

    /**
     * Phase 4: tiny helper for clipping plan/spec content to a sensible
     * length when embedding into the initial-bootstrap prompt. Avoids
     * blowing the systemPrompt budget on huge plan markdowns. Returns
     * "(none)" for null/empty so the template renders cleanly.
     */
    private static String abbreviate(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "\n... (truncated)";
    }

    /**
     * Contract State Machine v3 (2026-05-25): subscribe coordinator events
     * (plan transitions + contract lifecycle + dispatcher wake) to the
     * pusher's CoordinatorEvent ring. The pusher hard-pushes on each
     * recording so the UI strip updates in real time.
     */
    private static void wireCoordinatorEventListeners(
            com.github.claudecodegui.session.pair.plan.PlanStateMachine planSm,
            com.github.claudecodegui.session.pair.contract.ContractRegistry registry,
            com.github.claudecodegui.session.pair.dispatcher.TransitionDispatcher dispatcher,
            PairStatusPusher pusher) {
        if (planSm == null || registry == null || pusher == null) return;

        planSm.addListener((oldState, oldSub, now) -> {
            String type;
            String message;
            if (now == null) {
                type = "plan_cleared";
                message = "Plan cleared (replaced)";
            } else {
                type = "plan_" + (now.state == null ? "?" : now.state.name().toLowerCase());
                String sub = now.subState == null ? "" : "/" + now.subState.name();
                message = "Plan → " + (now.state == null ? "?" : now.state.name()) + sub;
            }
            pusher.recordCoordinatorEvent(
                    PairStatusSnapshot.CoordinatorEvent.Source.PLAN,
                    type, message,
                    now == null ? null : now.id);
        });

        registry.addListener(new com.github.claudecodegui.session.pair.contract.ContractListener() {
            @Override
            public void onIssued(com.github.claudecodegui.session.pair.contract.Contract c) {
                String typeLabel = c.type == null ? "?" : c.type.name();
                String assignee = c.assignedTo == null ? "?" : c.assignedTo.name();
                String prefix = c.retryCount > 0 ? "Retry " + c.retryCount + " issued " : "Issued ";
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.CONTRACT,
                        "contract_issued",
                        prefix + typeLabel + " → " + assignee,
                        c.id);
            }
            @Override
            public void onDischarged(com.github.claudecodegui.session.pair.contract.Contract c) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.CONTRACT,
                        "contract_discharged",
                        "Discharged " + (c.type == null ? "" : c.type.name()),
                        c.id);
            }
            @Override
            public void onRetried(com.github.claudecodegui.session.pair.contract.Contract original,
                                  com.github.claudecodegui.session.pair.contract.Contract retry) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "contract_retried",
                        "R" + retry.retryCount + " retry (" + (retry.type == null ? "" : retry.type.name()) + ")",
                        retry.id);
            }
            @Override
            public void onEscalated(com.github.claudecodegui.session.pair.contract.Contract c) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.GUARD,
                        "contract_escalated",
                        "R3 escalated — DECISION_REQUEST to supervisor",
                        c.id);
            }
            @Override
            public void onCancelled(com.github.claudecodegui.session.pair.contract.Contract c, String reason) {
                pusher.recordCoordinatorEvent(
                        PairStatusSnapshot.CoordinatorEvent.Source.CONTRACT,
                        "contract_cancelled",
                        "Cancelled" + (reason == null ? "" : ": " + reason),
                        c.id);
            }
        });

        // Dispatcher wake callback is set in startPair (chained with the
        // SupervisorMonitor.wakeForPlanTransition there). See the
        // transitionDispatcher.setSupervisorWakeCallback line in startPair —
        // it explicitly records a coordinator event alongside the wake.
    }

    /**
     * Bug fix 2026-05-26: subscribe to PlanState → DONE transitions and emit
     * the COMPLETION_REPORT.md file via {@link CompletionReportWriter}. This
     * closes the orphaned-completion gap left over from the v3 Contract State
     * Machine refactor — previously the supervisor wrote a wrap-up inject +
     * {@code emit_action(wait)} and waited for "the next monitor tick" to run
     * {@code isComplete}, but that tick was effectively disabled (24h interval)
     * and neither {@code CompletionReportWriter} nor {@code onPlanCompleted}
     * had a caller, so the pair sat in {@code ACTIVE/PENDING_DISCHARGE} forever.
     *
     * <p>Now: any transition into {@code PlanState.DONE} (whether through the
     * new {@code emit_action(complete_plan)} or auto-detected via
     * {@code allStepsDone()}) writes the report and pushes a status snapshot
     * so the UI clearly shows the pair is done.
     */
    private static void wireCompletionReportListener(
            com.github.claudecodegui.session.pair.plan.PlanStateMachine planSm,
            PairSession session,
            L2Store store,
            PairStatusPusher pusher) {
        if (planSm == null || session == null || store == null) return;
        planSm.addListener((oldState, oldSub, now) -> {
            if (now == null) return;
            if (now.state != com.github.claudecodegui.session.pair.plan.Plan.PlanState.DONE) return;
            // Only fire on the transition INTO DONE, not on subsequent listener
            // notifications (which won't happen — DONE is terminal — but cheap to guard).
            if (oldState == com.github.claudecodegui.session.pair.plan.Plan.PlanState.DONE) return;
            try {
                // The classifier reads L2State.planProgress (legacy mirror), but
                // the new architecture's source-of-truth is Plan.steps[]. If the
                // supervisor explicitly completed without updating planProgress,
                // mirror Plan.steps[].status into planProgress so the writer
                // produces a meaningful report.
                L2State state = store.update(session.getPairId(), s -> {
                    if (now.steps != null && !now.steps.isEmpty()
                            && (s.planProgress == null || s.planProgress.isEmpty())) {
                        if (s.planProgress == null) s.planProgress = new java.util.ArrayList<>();
                        for (com.github.claudecodegui.session.pair.plan.PlanStep ps : now.steps) {
                            L2State.PlanProgressEntry e = new L2State.PlanProgressEntry();
                            e.step = ps.index;
                            e.status = ps.status == null ? "done" : ps.status.name().toLowerCase();
                            e.attempts = ps.attempts;
                            s.planProgress.add(e);
                        }
                    }
                    return s;
                });
                java.nio.file.Path written = CompletionReportWriter.writeCompletionReport(session, state);
                if (written != null) {
                    LOG.info("[PairSessionManager] " + session.getPairId()
                            + " plan DONE → completion report written to " + written);
                } else {
                    LOG.info("[PairSessionManager] " + session.getPairId()
                            + " plan DONE → CompletionReportWriter returned null"
                            + " (planProgress empty; transition still committed so deadlock is broken)");
                }
            } catch (Exception e) {
                LOG.warn("[PairSessionManager] writeCompletionReport failed for "
                        + session.getPairId() + ": " + e.getMessage());
            }
            try {
                if (pusher != null) pusher.pushHard();
            } catch (Exception ignored) { /* best-effort */ }
        });
    }

    /**
     * Build a compact markdown spec listing each enabled project skill — name
     * + one-line description — to seed the Supervisor's review rules.
     *
     * <p>We deliberately ship only the metadata, not full skill bodies:
     * Supervisor recognises common skill names like {@code golang-standards}
     * and applies the corresponding rules on its own. Token-light by design.
     *
     * <p>Returns {@code null} when there are no enabled skills (lets the
     * daemon's prompt builder skip the section).
     */
    private static String buildProjectSpecFromSkills(CodemossSettingsService settings) {
        if (settings == null) return null;
        java.util.List<com.google.gson.JsonObject> skills;
        try {
            skills = settings.getSkills();
        } catch (Throwable t) {
            return null;
        }
        if (skills == null || skills.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("项目当前启用了以下技能包/规范。你在 review 主 AI 产出时应当把这些当作\n");
        sb.append("review 检查项；主 AI 违反时立即 inject_prompt 反馈具体违反点。\n");
        sb.append("（你不需要展开每个规范的细节——名字本身就承载了语义，常用规范如\n");
        sb.append(" golang-standards / eino-compose / agent-orch-creator 你已经知道它们意味着什么。）\n\n");

        int kept = 0;
        for (com.google.gson.JsonObject skill : skills) {
            // Honor user toggle if present; treat missing/true as "enabled".
            if (skill.has("enabled") && !skill.get("enabled").isJsonNull()
                    && !skill.get("enabled").getAsBoolean()) {
                continue;
            }
            String name = skill.has("name") && !skill.get("name").isJsonNull()
                    ? skill.get("name").getAsString().trim() : null;
            if (name == null || name.isEmpty()) continue;
            String desc = skill.has("description") && !skill.get("description").isJsonNull()
                    ? skill.get("description").getAsString().trim() : "";
            // Single-line clip — keep prompt compact.
            if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
            desc = desc.replace('\n', ' ').replace('\r', ' ').trim();

            sb.append("- `").append(name).append('`');
            if (!desc.isEmpty()) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
            kept++;
        }
        return kept > 0 ? sb.toString() : null;
    }
}
