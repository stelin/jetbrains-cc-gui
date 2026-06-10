package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.MainAIMonitor;
import com.github.claudecodegui.session.pair.SupervisorMonitor;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
import com.github.claudecodegui.session.registry.SessionKind;
import com.github.claudecodegui.session.registry.SessionRegistry;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Per-project workflow orchestration engine (P1 skeleton). Owns the DAG
 * scheduler: single-workflow lock, rolling ready-queue (DN3), {@link Semaphore}
 * concurrency gate (DN4), and the {@code 建 tab → 组 plan → startPair} launch
 * chain. See {@code docs/workflow/coding-plan.md} §8.
 *
 * <h3>Thread model (§15 — hard constraint)</h3>
 * <ul>
 *   <li>All writes to {@link #exec}/{@link #slots}/{@link #readyQueue}/
 *       {@link #pairToNode} happen on the single {@code wf-scheduler} thread.
 *       Every external entry point ({@link #startWorkflow}, {@link #onNodeReport},
 *       {@link #abortWorkflow}, …) re-marshals onto it via {@link #scheduler}.</li>
 *   <li>Tab build / focus runs on the EDT; {@code startPair} (20s handshake)
 *       runs on the background pool — both isolated behind {@link NodeLauncher}.</li>
 *   <li>{@link #handles} is a {@link ConcurrentHashMap} (EDT put on tab build,
 *       scheduler reads).</li>
 * </ul>
 *
 * <p>The scheduler core is IDE-free and unit-testable: tests inject a
 * synchronous {@link WorkflowScheduler}, a fake {@link NodeLauncher}, and a
 * {@link WorkflowStore} rooted in a temp dir (§22).
 */
@Service(Service.Level.PROJECT)
public final class SupervisorWorkflowManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SupervisorWorkflowManager.class);

    /** DN4: hard upper bound on concurrency, regardless of config. */
    static final int HARD_CAP = 3;
    /** DN4: default global concurrency when nothing is configured. */
    static final int DEFAULT_GLOBAL_CONCURRENCY = 2;
    /** Default per-workflow concurrency when {@code def.maxConcurrency} is null. */
    static final int DEFAULT_WF_CONCURRENCY = 2;

    /** D28: hard ceiling on a node's relative start delay (5 hours). */
    static final int MAX_DELAY_MINUTES = 300;

    /** MVP cap on injected upstream COMPLETION_REPORT.md text (P-future: smarter裁剪). */
    static final int UPSTREAM_REPORT_CAP = 4000;

    /** Resend the node kickoff once if the supervisor hasn't started this long after launch. */
    private static final long KICKOFF_RETRY_DELAY_SEC = 6;

    /** DN2 watchdog backstop — wired in P2; funnels watchdog-driven WAITING → WAITING_HUMAN. */
    private static final boolean DN2_BACKSTOP = true;

    /**
     * Re-marshals work onto the scheduler thread. Production = single
     * {@code wf-scheduler} thread; tests inject a synchronous (same-thread)
     * implementation for deterministic assertions.
     */
    public interface WorkflowScheduler {
        void submit(Runnable task);
        void shutdown();
    }

    private final Project project;                 // nullable in unit tests
    private final Gson gson = new Gson();
    private final WorkflowScheduler scheduler;
    private final WorkflowStore store;
    private final NodeLauncher launcher;
    /** One-shot delayed retries for node kickoff (daemon-readiness race net). */
    private final java.util.concurrent.ScheduledExecutorService kickoffRetry =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wf-kickoff-retry");
                t.setDaemon(true);
                return t;
            });
    private final Supplier<Set<String>> knownSupervisorIdsSupplier;
    private final IntSupplier ceilingSupplier;

    private final List<HandlerContext.JsCallback> uiSinks = new CopyOnWriteArrayList<>();

    private volatile WorkflowDefinition currentDef;
    /** null = no execution loaded; non-null with state RUNNING = active run. */
    private volatile WorkflowExecution exec;

    // ─── scheduler-thread-only state ────────────────────────────────────
    private Semaphore slots;
    /**
     * Explicit set of nodes currently occupying a concurrency permit (D23 —
     * resume-and-redispatch-plan §2.3). The single source of truth for "does this
     * node hold a slot": a node is added when {@link #pump} (or a re-launch via
     * {@link #redispatchNode}) acquires its permit, and removed when its slot is
     * released on DONE. Status alone can no longer answer this — a restored
     * {@code WAITING_HUMAN} node holds no permit (fresh {@link Semaphore}) while a
     * live one does, and {@code onNodeTabClosed} drops the handle but keeps the
     * permit. Mutated only on the {@code wf-scheduler} thread.
     */
    private final Set<String> slotHolders = new HashSet<>();
    private final Deque<String> readyQueue = new ArrayDeque<>();
    private final Map<String, WorkflowNode> nameToNode = new HashMap<>();
    // ────────────────────────────────────────────────────────────────────

    private final Map<String, String> pairToNode = new ConcurrentHashMap<>();
    private final Map<String, NodeHandle> handles = new ConcurrentHashMap<>();

    /** Per-node start timers for SCHEDULED nodes (D25/DN14). Keyed by node name. */
    private final java.util.concurrent.ScheduledExecutorService nodeTimers =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wf-node-timer");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    // ─── node-liveness watchdog (D35-D41) ────────────────────────────────
    /** How often the watchdog checks freeze + pushes per-node activity for display. */
    static final long WATCHDOG_TICK_MS = 5_000L;
    /** Backoff hard cap: once reached, auto-redispatch repeats at most hourly. */
    static final long WATCHDOG_MAX_WINDOW_MS = 60L * 60_000L;
    /**
     * Fixed auto-redispatch backoff ladder (15→20→30→40→60 min). Indexed by the
     * per-node attempt count; once past the last step it repeats hourly. Independent
     * of the configured idle threshold (that only gates on/off + the display).
     */
    static final long[] WATCHDOG_BACKOFF_LADDER_MS = {
            15L * 60_000L, 20L * 60_000L, 30L * 60_000L, 40L * 60_000L, WATCHDOG_MAX_WINDOW_MS
    };

    /** Consecutive auto-redispatch count per node (backoff exponent). Scheduler-thread. */
    private final Map<String, Integer> redispatchAttempts = new ConcurrentHashMap<>();
    /** Epoch of each node's last (re)dispatch — floor for "last active" + backoff base. */
    private final Map<String, Long> lastDispatchAt = new ConcurrentHashMap<>();
    /**
     * Session resume (SR9): nodes whose NEXT pair start resumed prior transcripts,
     * so {@link #kickoffAndReveal} injects the post-resume reprime (anchor the
     * resumed LLM to the fresh coordination state) before the task kickoff.
     * Consumed-and-cleared there. Scheduler-thread.
     */
    private final java.util.Set<String> resumedNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Periodic watchdog; production-only (tests drive {@link #watchdogTick()} directly). */
    private final java.util.concurrent.ScheduledExecutorService nodeWatchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wf-node-watchdog");
                t.setDaemon(true);
                return t;
            });

    // ─── construction ───────────────────────────────────────────────────

    /** Production constructor (invoked by the IntelliJ service container). */
    public SupervisorWorkflowManager(@NotNull Project project) {
        this.project = project;
        this.store = new WorkflowStore(project);
        this.launcher = new IdeNodeLauncher(project);
        this.scheduler = newSingleThreadScheduler();
        this.knownSupervisorIdsSupplier = this::loadKnownSupervisorIds;
        this.ceilingSupplier = this::resolveCeiling;
        // Periodic liveness watchdog + activity-display pusher (production only;
        // tests invoke watchdogTick() synchronously). Re-marshals onto the
        // scheduler thread so it reads exec/handles with no locking.
        nodeWatchdog.scheduleWithFixedDelay(
                () -> submit(this::watchdogTick),
                WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Test seam: inject all collaborators. {@code project} may be null. */
    SupervisorWorkflowManager(@Nullable Project project,
                              @NotNull WorkflowStore store,
                              @NotNull NodeLauncher launcher,
                              @NotNull WorkflowScheduler scheduler,
                              @NotNull Supplier<Set<String>> knownSupervisorIds,
                              @NotNull IntSupplier ceiling) {
        this.project = project;
        this.store = store;
        this.launcher = launcher;
        this.scheduler = scheduler;
        this.knownSupervisorIdsSupplier = knownSupervisorIds;
        this.ceilingSupplier = ceiling;
    }

    public static SupervisorWorkflowManager getInstance(@NotNull Project project) {
        return project.getService(SupervisorWorkflowManager.class);
    }

    private static WorkflowScheduler newSingleThreadScheduler() {
        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wf-scheduler");
            t.setDaemon(true);
            return t;
        });
        return new WorkflowScheduler() {
            @Override public void submit(Runnable task) { es.submit(task); }
            @Override public void shutdown() { es.shutdownNow(); }
        };
    }

    /** Wrap scheduler work so a thrown task can't poison the single thread. */
    private void submit(Runnable task) {
        scheduler.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.warn("[Workflow] scheduler task failed: " + t.getMessage());
            }
        });
    }

    // ─── definition CRUD (handler thread; §10) ──────────────────────────

    public List<WorkflowDefinition> loadAllDefinitions() {
        return store.loadAll();
    }

    public void saveDefinition(@Nullable WorkflowDefinition def) {
        if (def == null || def.id == null || def.id.isEmpty()) {
            broadcastOpResult("save", false, "工作流定义无效");
            return;
        }
        if (def.updatedAt == null) def.updatedAt = System.currentTimeMillis();
        try {
            store.save(def);
            broadcastDefinitions();
            broadcastOpResult("save", true, null);
        } catch (Exception e) {
            broadcastOpResult("save", false, e.getMessage());
        }
    }

    public void deleteDefinition(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            broadcastOpResult("delete", false, "缺少工作流 id");
            return;
        }
        WorkflowExecution e = exec;
        if (isLocked(e) && id.equals(e.workflowId)) {
            broadcastOpResult("delete", false,
                    e.state == WorkflowState.PAUSED ? "工作流待恢复，无法删除" : "工作流正在运行，无法删除");
            return;
        }
        store.delete(id);
        broadcastDefinitions();
        broadcastOpResult("delete", true, null);
    }

    /** Push a full state snapshot to a single (just-connected) sink (§4.1 list). */
    public void requestList(@NotNull HandlerContext.JsCallback sink) {
        pushTo(sink, "window.onWorkflowDefinitions", gson.toJson(store.loadAll()));
        pushTo(sink, "window.onWorkflowCapabilities", capabilitiesJson());
        pushTo(sink, "window.onWorkflowStatuses", statusesJson());   // D24: list badges
        WorkflowExecution e = exec;
        if (isLocked(e)) {   // RUNNING or PAUSED (restored, awaiting resume)
            pushTo(sink, "window.onWorkflowExecutionUpdate", gson.toJson(e));
        }
    }

    /**
     * True when an execution holds the single-workflow lock: actively {@code RUNNING}
     * or {@code PAUSED} (restored after restart, awaiting {@link #resumeWorkflow}).
     * Used by start/delete/abort/list guards so a paused run can't be clobbered.
     */
    private static boolean isLocked(@Nullable WorkflowExecution e) {
        return e != null && (e.state == WorkflowState.RUNNING || e.state == WorkflowState.PAUSED);
    }

    /**
     * Session-kind refactor (S6): per-project session ledger. Null in unit tests
     * that construct the manager without a {@link Project} — callers null-check so
     * the scheduler core stays IDE-free.
     */
    @Nullable
    private SessionRegistry sessionRegistry() {
        return project != null ? SessionRegistry.getInstance(project) : null;
    }

    // ─── run / abort / jump / report (scheduler thread; §8) ──────────────

    /** §8.2 — single-workflow lock + cycle check + clamp concurrency, then pump. */
    public void startWorkflow(@Nullable String id) {
        submit(() -> {
            if (isLocked(exec)) {
                broadcastOpResult("run", false,
                        exec.state == WorkflowState.PAUSED ? "有待恢复的工作流，请先恢复或中止" : "已有工作流在运行");
                // Re-sync any webview that lost the running/paused snapshot (bridge
                // race / opened after the run began): without this it stays on
                // "Editing" and keeps getting rejected with no way to see — or
                // resume/stop — the workflow that holds the lock.
                broadcastExec();
                return;
            }
            WorkflowDefinition def = store.load(id);
            if (def == null) {
                broadcastOpResult("run", false, "工作流不存在");
                return;
            }
            String err = DagValidator.validate(def, knownSupervisorIdsSupplier.get());
            if (err != null) {
                broadcastOpResult("run", false, err);
                return;
            }
            currentDef = def;
            nameToNode.clear();
            for (WorkflowNode n : def.nodesSafe()) nameToNode.put(n.name, n);

            int n = clamp(def.maxConcurrency == null ? DEFAULT_WF_CONCURRENCY : def.maxConcurrency,
                    1, ceiling());
            exec = newExecution(def, n);
            slots = new Semaphore(n);
            slotHolders.clear();
            redispatchAttempts.clear();
            lastDispatchAt.clear();
            readyQueue.clear();
            pairToNode.clear();
            handles.clear();
            exec.state = WorkflowState.RUNNING;

            // Session-kind refactor (S6): the workflow becomes a container (stable
            // id == wfId) + one SUPERVISED child container per node (parent = wfId,
            // so child nodes stay hidden behind the workflow in the supervised tab).
            // Idempotent re-run: the fixed-id workflow register no-ops if present,
            // and rt.containerId is only assigned when still null. WorkflowStore
            // (definition/execution) is untouched — the manifest is purely the
            // history ledger + claimed-set source, aligned by containerId == wfId.
            SessionRegistry registry = sessionRegistry();
            if (registry != null) {
                if (registry.get(def.id) == null) {
                    registry.register(SessionKind.WORKFLOW, null, def.name, null, def.id);
                }
                for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
                    NodeRuntime rt = e.getValue();
                    if (rt != null && rt.containerId == null) {
                        WorkflowNode wn = nameToNode.get(e.getKey());
                        rt.containerId = registry.register(SessionKind.SUPERVISED, def.id,
                                e.getKey(), wn != null ? wn.supervisorId : null, null);
                    }
                }
            }

            LOG.info("[Workflow] start wf=" + def.id + " nodes=" + def.nodesSafe().size()
                    + " concurrency=" + n);

            // Prepare the cockpit with `n` tile slots (= concurrency). Windows
            // open lazily as nodes start (≤ n running at once), giving each
            // running node a VISIBLE host whose JCEF webview mounts; finished
            // windows stay open for history (cockpit-plan §3).
            launcher.openCockpit(n);

            enqueueReady();
            persist();
            broadcastExec();
            broadcastStatuses();   // → RUNNING badge in list
            broadcastOpResult("run", true, null);
            pump();
        });
    }

    /**
     * §8.7 — stop active pairs, mark live nodes ABORTED, freeze execution. Also
     * usable on a {@code PAUSED} (restored-but-not-resumed) execution — there are
     * simply no live handles to stop, so the user can discard a restored run
     * without resuming it first.
     */
    public void abortWorkflow() {
        submit(() -> {
            if (exec == null || !isLocked(exec)) return;
            cancelAllTimers();   // kill any pending SCHEDULED start timers
            for (NodeHandle h : handles.values()) {
                if (h != null && h.windowId != null) launcher.stop(h.windowId);
            }
            for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
                NodeRuntime r = e.getValue();
                if (r.status == NodeStatus.RUNNING || r.status == NodeStatus.READY
                        || r.status == NodeStatus.SCHEDULED
                        || r.status == NodeStatus.WAITING_HUMAN || r.status == NodeStatus.PENDING) {
                    r.status = NodeStatus.ABORTED;
                    r.scheduledStartAt = null;
                    launcher.setNodeStatus(e.getKey(), NodeStatus.ABORTED);   // cockpit: grey border (window kept)
                }
            }
            exec.state = WorkflowState.ABORTED;
            slotHolders.clear();
            redispatchAttempts.clear();
            lastDispatchAt.clear();
            LOG.info("[Workflow] ABORTED wf=" + exec.workflowId);
            persist();
            broadcastExec();   // tabs retained (D13)
            broadcastStatuses();   // → ABORTED badge in list
        });
    }

    /** §8.6 — the single node→engine callback. Idempotent; honors abort. */
    public void onNodeReport(@Nullable String pairId, @NotNull NodeStatus status,
                             @Nullable String summary, @Nullable List<String> changed) {
        submit(() -> {
            if (exec == null || exec.state != WorkflowState.RUNNING) return;
            String name = pairToNode.get(pairId);
            if (name == null) return;                    // not a workflow node — ignore
            NodeRuntime rt = exec.nodes.get(name);
            if (rt == null) return;
            if (rt.status == NodeStatus.DONE || rt.status == NodeStatus.ABORTED) return; // idempotent
            // Already escalated (e.g. the DN2 watchdog backstop raced an explicit
            // blocked report): refresh the reason in the overview but don't fire a
            // second escalation toast/jump.
            if (status == NodeStatus.WAITING_HUMAN && rt.status == NodeStatus.WAITING_HUMAN) {
                if (summary != null && !summary.isEmpty()) {
                    rt.escalationReason = summary;
                    persist();
                    broadcastExec();
                }
                return;
            }

            if (status == NodeStatus.DONE) {
                rt.status = NodeStatus.DONE;
                rt.summary = summary;
                rt.changedFiles = changed;
                rt.finishedAt = System.currentTimeMillis();   // node done → freeze elapsed
                NodeHandle h = handles.get(name);
                if (h != null && h.pairDir != null) {
                    rt.completionReportPath = h.pairDir.resolve("COMPLETION_REPORT.md").toString();
                }
                // Mark the supervisor session finished too, so its time strip stops the
                // live elapsed and shows 结束时间 (the supervisor's job ends with the node).
                if (h != null && h.pair != null) h.pair.setFinishedAt(rt.finishedAt);
                if (slotHolders.remove(name) && slots != null) slots.release();   // D23: only release a permit we hold
                redispatchAttempts.remove(name);   // watchdog: node done, drop its backoff state
                lastDispatchAt.remove(name);
                launcher.setNodeStatus(name, NodeStatus.DONE);   // cockpit: green ✓ (window kept)
                LOG.info("[Workflow] node " + name + " DONE report=" + rt.completionReportPath);
                enqueueReady();
                boolean completed = allNodesDone();
                if (completed) {
                    exec.state = WorkflowState.COMPLETED;
                    LOG.info("[Workflow] COMPLETED wf=" + exec.workflowId);
                }
                persist();
                broadcastExec();
                if (completed) broadcastStatuses();   // → COMPLETED badge in list
                if (exec.state == WorkflowState.RUNNING) pump();
            } else {  // WAITING_HUMAN / blocked — do NOT free the slot, do NOT advance (D11)
                rt.status = NodeStatus.WAITING_HUMAN;
                rt.escalationReason = summary;
                LOG.info("[Workflow] node " + name + " WAITING_HUMAN reason=" + summary);
                escalate(name, summary);
                persist();
                broadcastExec();
            }
        });
    }

    public void jumpToNode(@Nullable String name) {
        submit(() -> focusNode(name));
    }

    public void openReport(@Nullable String name) {
        submit(() -> {
            if (exec == null || name == null) return;
            NodeRuntime rt = exec.nodes.get(name);
            if (rt == null || rt.completionReportPath == null) return;
            if (!isPathAllowed(rt.completionReportPath)) {
                LOG.warn("[Workflow] openReport rejected out-of-bounds path: " + rt.completionReportPath);
                return;
            }
            launcher.openReport(rt.completionReportPath);
        });
    }

    // ─── scheduler internals (scheduler thread only) ─────────────────────

    /**
     * §8.3 — rolling enqueue (DN3): any PENDING node with all deps DONE becomes
     * eligible. With node scheduling (D25), the moment a node's deps first complete
     * we compute its due time once: due now → {@code READY}; due later →
     * {@code SCHEDULED} with a timer (the timer flips it to READY when it fires).
     * Because this only ever promotes PENDING nodes, the relative-delay reference
     * point (last dep DONE) is captured exactly once.
     */
    private void enqueueReady() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
            String name = e.getKey();
            NodeRuntime r = e.getValue();
            if (r.status != NodeStatus.PENDING || !allDepsDone(name)) continue;
            long dueAt = computeDueAt(name, now);
            if (dueAt <= now) {
                r.status = NodeStatus.READY;
                r.scheduledStartAt = null;
                readyQueue.add(name);
            } else {
                r.status = NodeStatus.SCHEDULED;   // DN13: holds no slot until READY
                r.scheduledStartAt = dueAt;
                armTimer(name, dueAt);
            }
        }
    }

    /**
     * Effective start instant for a node whose deps just completed: {@code absolute}
     * → its configured epoch; {@code relative} → {@code now + delayMinutes}
     * (clamped to {@link #MAX_DELAY_MINUTES}); otherwise → {@code now} (immediate).
     */
    private long computeDueAt(String name, long now) {
        WorkflowNode n = nameToNode.get(name);
        if (n == null) return now;
        if ("absolute".equals(n.delayMode) && n.scheduledAt != null) {
            return n.scheduledAt;
        }
        if ("relative".equals(n.delayMode) && n.delayMinutes != null && n.delayMinutes > 0) {
            int m = Math.min(n.delayMinutes, MAX_DELAY_MINUTES);
            return now + m * 60_000L;
        }
        return now;
    }

    // ─── node start timers (D25/DN14) ────────────────────────────────────

    /** Arm (or re-arm) a SCHEDULED node's start timer; fires {@link #fireScheduled} on the scheduler thread. */
    private void armTimer(String name, long dueAt) {
        cancelTimer(name);
        long delay = Math.max(0, dueAt - System.currentTimeMillis());
        try {
            scheduledFutures.put(name, nodeTimers.schedule(
                    () -> submit(() -> fireScheduled(name)), delay, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (Exception ignored) {
            /* nodeTimers shut down (project closing) */
        }
    }

    private void cancelTimer(String name) {
        java.util.concurrent.ScheduledFuture<?> f = scheduledFutures.remove(name);
        if (f != null) f.cancel(false);
    }

    private void cancelAllTimers() {
        for (java.util.concurrent.ScheduledFuture<?> f : scheduledFutures.values()) {
            if (f != null) f.cancel(false);
        }
        scheduledFutures.clear();
    }

    /** Timer callback (scheduler thread): a SCHEDULED node's delay elapsed → READY → pump. Idempotent. */
    private void fireScheduled(String name) {
        if (exec == null || exec.state != WorkflowState.RUNNING) return;
        scheduledFutures.remove(name);
        NodeRuntime r = exec.nodes.get(name);
        if (r == null || r.status != NodeStatus.SCHEDULED) return;   // aborted / redispatched away
        r.status = NodeStatus.READY;
        r.scheduledStartAt = null;
        readyQueue.add(name);
        LOG.info("[Workflow] node " + name + " SCHEDULED → READY (timer fired)");
        persist();
        broadcastExec();
        pump();
    }

    /**
     * Re-arm timers for nodes restored as {@code SCHEDULED} (resume after restart —
     * DN14). A node whose due time already passed during downtime goes straight to
     * {@code READY}; one still in the future is re-armed for the remaining wait.
     * Caller pumps afterwards.
     */
    private void rearmScheduledTimers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
            NodeRuntime r = e.getValue();
            if (r.status != NodeStatus.SCHEDULED) continue;
            long dueAt = r.scheduledStartAt != null ? r.scheduledStartAt : now;
            if (dueAt <= now) {
                r.status = NodeStatus.READY;
                r.scheduledStartAt = null;
                readyQueue.add(e.getKey());
            } else {
                armTimer(e.getKey(), dueAt);
            }
        }
    }

    // ─── node-liveness watchdog (D35-D41) ────────────────────────────────

    /**
     * Per-tick on the {@code wf-scheduler} thread (re-marshaled from
     * {@link #nodeWatchdog}): for every RUNNING node it (1) records the node's
     * effective last-active instant for the silent-time display, and (2) if the
     * node has been silent past its backoff window, auto-redispatches it
     * (假死/死锁 self-heal). Reads {@code exec}/{@code handles} on the same thread
     * that mutates them, so no locking is needed.
     */
    void watchdogTick() {
        WorkflowExecution e = exec;
        if (e == null || e.state != WorkflowState.RUNNING) return;
        long now = System.currentTimeMillis();
        long thresholdMs = freezeThresholdMs();   // ≤0 → watchdog disabled (display still pushed)
        JsonObject activity = new JsonObject();
        List<String> frozen = new ArrayList<>();

        for (Map.Entry<String, NodeRuntime> en : e.nodes.entrySet()) {
            String name = en.getKey();
            if (en.getValue().status != NodeStatus.RUNNING) continue;
            NodeHandle h = handles.get(name);
            if (h == null || h.pair == null || h.pair.isDisposed()) continue;

            long lastActive = effectiveLastActiveAt(name, thresholdMs);
            activity.addProperty(name, lastActive);   // display (always)

            if (thresholdMs <= 0) continue;           // watchdog off
            // Tick-in-progress is no longer a blanket skip — effectiveLastActiveAt
            // counts a live (under-ceiling) think as active, while a wedged tick
            // past the ceiling is allowed to climb so we can recover it.
            if (isRateLimitWaiting(h)) continue;      // legitimate quota wait

            int attempts = redispatchAttempts.getOrDefault(name, 0);
            // Recovery reset: a node that ran healthily for ≥ threshold since its
            // last (re)dispatch is a fresh start — clear the backoff exponent.
            if (attempts > 0
                    && now - lastDispatchAt.getOrDefault(name, 0L) > thresholdMs
                    && now - lastActive < thresholdMs) {
                redispatchAttempts.put(name, 0);
                attempts = 0;
            }
            if (shouldRedispatch(now, lastActive, attempts, thresholdMs)) frozen.add(name);
        }

        if (!activity.entrySet().isEmpty()) {
            broadcast("window.onWorkflowNodeActivity", gson.toJson(activity));
        }
        for (String name : frozen) autoRedispatch(name, now);   // already on scheduler thread
    }

    /**
     * Backoff window for the n-th auto-redispatch: the fixed ladder
     * {@link #WATCHDOG_BACKOFF_LADDER_MS} (15→20→30→40→60 min, hourly thereafter).
     * The {@code thresholdMs} argument only gates on/off in {@link #shouldRedispatch}.
     */
    static long backoffWindowMs(int attempts, long thresholdMs) {
        int idx = Math.min(Math.max(attempts, 0), WATCHDOG_BACKOFF_LADDER_MS.length - 1);
        return WATCHDOG_BACKOFF_LADDER_MS[idx];
    }

    /** Pure decision: has the node been idle past its current backoff window? */
    static boolean shouldRedispatch(long now, long lastActiveAt, int attempts, long thresholdMs) {
        if (thresholdMs <= 0) return false;
        return now - lastActiveAt > backoffWindowMs(attempts, thresholdMs);
    }

    /** Re-dispatch a frozen node (scheduler thread); bumps the backoff exponent. */
    private void autoRedispatch(String name, long now) {
        NodeRuntime rt = exec == null ? null : exec.nodes.get(name);
        if (rt == null || rt.status != NodeStatus.RUNNING) return;
        long thresholdMs = freezeThresholdMs();
        int attempts = redispatchAttempts.getOrDefault(name, 0);
        // Re-confirm still idle: it may have produced output between detect and act.
        if (!shouldRedispatch(now, effectiveLastActiveAt(name, thresholdMs), attempts, thresholdMs)) return;
        int next = attempts + 1;
        redispatchAttempts.put(name, next);
        long idleMin = Math.max(0, (now - effectiveLastActiveAt(name, thresholdMs)) / 60_000L);
        // A tick still "in progress" at this point is wedged (an under-ceiling think
        // wouldn't be idle) → re-launch to recover (a resume input would just queue
        // behind the stuck turn). Otherwise the supervisor is idle-waiting → resume
        // IN PLACE on the existing window (preserve history, no new window).
        NodeHandle h = handles.get(name);
        boolean wedged = h != null && isTickInProgress(h);
        LOG.warn("[Workflow] node " + name + " frozen (idle " + idleMin + "min, wedged=" + wedged
                + ") → auto " + (wedged ? "re-launch" : "resume") + " #" + next);
        broadcastAutoResume(name, next, idleMin);   // soft info toast, NOT a "needs you" alarm
        redispatchNodeInternal(name, wedged ? "restart" : "auto", false);
    }

    /** Soft, non-blocking notice that the watchdog self-healed a node (no user action needed). */
    private void broadcastAutoResume(String nodeName, int attempt, long idleMinutes) {
        JsonObject o = new JsonObject();
        o.addProperty("nodeName", nodeName);
        o.addProperty("attempt", attempt);
        o.addProperty("idleMinutes", idleMinutes);
        broadcast("window.onWorkflowAutoResume", gson.toJson(o));
    }

    /**
     * Node's effective last-active instant (epoch): the latest of its main-AI
     * stream activity, supervisor tick, and last (re)dispatch (the dispatch floor
     * keeps a freshly-(re)started node from reading as idle before its first
     * output). Single source for both the watchdog and the silent-time display.
     */
    long effectiveLastActiveAt(String name, long thresholdMs) {
        long t = lastDispatchAt.getOrDefault(name, 0L);
        NodeHandle h = handles.get(name);
        if (h == null || h.pair == null || h.pair.isDisposed()) return t;
        try {
            MainAIMonitor m = h.pair.getMainAIMonitor();
            if (m != null) t = Math.max(t, m.getLastActivityAt());
            SupervisorMonitor s = h.pair.getSupervisorMonitor();
            if (s != null) {
                t = Math.max(t, Math.max(s.getLastTickStartMs(), s.getLastTickEndMs()));
                // Live supervisor stream activity (any SDK message / usage tick during
                // a turn). The supervisor's LLM turn is async on the daemon — it is
                // NOT a monitor "tick" (status reads "Tick 0 / IDLE" mid-turn), so
                // without this an actively thinking / dispatching supervisor reads as
                // idle and the watchdog counts up against it. This is real per-chunk
                // proof of liveness (unlike isTickInProgress), so no wedge ceiling is
                // needed: a genuinely hung turn stops streaming and can still recover.
                t = Math.max(t, s.getLastStreamActivityMs());
                // A supervisor turn currently IN PROGRESS (thinking / tool calls) is
                // active — there's no per-chunk timestamp, so without this a long
                // think reads as idle. Excludes a wedged tick past the ceiling, so a
                // genuinely-stuck turn can still climb and be recovered.
                if (s.isTickInProgress() && !isWedgedTick(s, thresholdMs)) {
                    t = Math.max(t, System.currentTimeMillis());
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return t;
    }

    /**
     * A supervisor turn that has been "in progress" longer than the wedge ceiling
     * ({@code max(30min, 3×threshold)}). Used so a normal long think counts as
     * active while a genuinely-stuck turn is allowed to trip the watchdog.
     */
    private static boolean isWedgedTick(SupervisorMonitor s, long thresholdMs) {
        if (s == null || !s.isTickInProgress()) return false;
        long start = s.getLastTickStartMs();
        if (start <= 0) return false;
        long ceiling = Math.max(30L * 60_000L, 3L * Math.max(thresholdMs, 0L));
        return System.currentTimeMillis() - start > ceiling;
    }

    private static boolean isRateLimitWaiting(NodeHandle h) {
        try {
            return h.pair != null && h.pair.getRateLimitWatcher() != null
                    && h.pair.getRateLimitWatcher().isWaitingForReset();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTickInProgress(NodeHandle h) {
        try {
            SupervisorMonitor s = h.pair == null ? null : h.pair.getSupervisorMonitor();
            return s != null && s.isTickInProgress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Configured freeze threshold in ms; ≤0 disables the watchdog. Defensive (headless tests). */
    long freezeThresholdMs() {
        try {
            return new CodemossSettingsService().getWorkflowFreezeThresholdMinutes() * 60_000L;
        } catch (Exception e) {
            return com.github.claudecodegui.settings.SupervisorAgentManager.DEFAULT_WORKFLOW_FREEZE_THRESHOLD_MINUTES * 60_000L;
        }
    }

    private boolean allDepsDone(String name) {
        WorkflowNode n = nameToNode.get(name);
        if (n == null) return false;
        for (String dep : n.dependsOnSafe()) {
            NodeRuntime dr = exec.nodes.get(dep);
            if (dr == null || dr.status != NodeStatus.DONE) return false;
        }
        return true;
    }

    /** §8.4 — drain the ready queue while concurrency slots are free. */
    private void pump() {
        if (slots == null) return;
        while (slots.tryAcquire()) {
            String name = readyQueue.poll();
            if (name == null) {
                slots.release();
                break;
            }
            slotHolders.add(name);   // D23: this node now occupies a permit
            LOG.info("[Workflow] pump acquired slot for " + name + ", ready=" + readyQueue);
            startNode(nameToNode.get(name));
        }
    }

    /** §8.5 — assemble plan (scheduler thread), then hand off to the launcher. */
    private void startNode(WorkflowNode node) {
        if (node == null) return;
        final NodeRuntime rt = exec.nodes.get(node.name);
        if (rt == null) return;

        lastDispatchAt.put(node.name, System.currentTimeMillis());   // watchdog: dispatch floor

        Path planPath;
        try {
            planPath = assembleAndWritePlan(node);
        } catch (Exception e) {
            failNodeToHuman(node.name, "组装 plan 失败: " + e.getMessage());
            return;
        }

        final String nodeName = node.name;
        // Session-kind refactor (S6): stage the node's stable container id so the
        // launcher threads it into StartPairParams (→ the pair's L2 / routing key).
        launcher.setPendingContainerId(nodeName, rt.containerId);
        launcher.launch(node, planPath, new NodeLauncher.Sink() {
            @Override
            public void tabCreated(String windowId, NodeHandle handle) {
                submit(() -> {
                    handles.put(nodeName, handle);
                    rt.windowId = windowId;
                    persist();
                    broadcastExec();
                });
            }

            @Override
            public void pairStarted(String pairId, Path pairDir) {
                submit(() -> {
                    NodeHandle h = handles.get(nodeName);
                    if (h != null) h.pairDir = pairDir;
                    rt.pairId = pairId;
                    pairToNode.put(pairId, nodeName);
                    // Only the READY→RUNNING edge; never resurrect a terminal node.
                    if (rt.status == NodeStatus.READY) {
                        rt.status = NodeStatus.RUNNING;
                        rt.startedAt = System.currentTimeMillis();   // run start (re-stamped each dispatch)
                        rt.finishedAt = null;                        // clear any prior finish on re-run
                    }
                    LOG.info("[Workflow] node " + nodeName + " RUNNING pair=" + pairId
                            + " window=" + rt.windowId);
                    if (DN2_BACKSTOP && h != null && h.pair != null) {
                        attachWatchdogBackstop(h.pair.getPlanStateMachine(), nodeName);
                    }
                    if (h != null) kickoffAndReveal(nodeName, h);
                    persist();
                    broadcastExec();
                });
            }

            @Override
            public void failed(String reason) {
                submit(() -> failNodeToHuman(nodeName, reason));
            }
        });
    }

    /**
     * After a node's pair is live, do the two things a webview-driven pair gets
     * for free but a background workflow node does not:
     *
     * <ol>
     *   <li><b>KICKOFF</b> — {@code startPair} only SEEDS the plan into the daemon
     *       bootstrap; the supervisor's first turn still needs an explicit
     *       {@code user_input} (the same trigger the right-pane composer sends).
     *       Without it the node tab opens but the supervisor sits idle.</li>
     *   <li><b>REVEAL</b> — the node tab's JCEF webview is lazy: it never mounts
     *       until the tab is shown, so {@code ActionRouter} buffers every
     *       supervisor→main-AI inject ({@code pendingInjectsBeforeReady}) and the
     *       task never reaches the main AI. Selecting the tab realizes the webview
     *       → {@code pair_webview_ready} → the buffer drains. Done HERE (after the
     *       pair is active) so the webview's {@code replayActivePairs} finds the
     *       live pair instead of racing an in-flight {@code startPair}.</li>
     * </ol>
     *
     * <p>Runs on the scheduler thread; {@code publishUserInput} is async and
     * {@code launcher.focus} marshals to the EDT internally.
     */
    private void kickoffAndReveal(String nodeName, NodeHandle h) {
        // Session resume (SR2/SR9): a resumed node's supervisor already has the
        // task + its prior work in its (resumed) transcript. The kickoff carries a
        // reorientation header so the very FIRST resumed turn is anchored to the
        // FRESH coordination state — otherwise it acts on stale memory ("I'm
        // waiting for contract C") and loops into the wedged escalation. Folding
        // it into the (single) kickoff turn avoids a separate dormant reprime turn.
        boolean resumed = resumedNodes.remove(nodeName);
        sendKickoff(nodeName, h, false, resumed);
        // Realize the node's webview so the supervisor→main-AI inject buffer
        // drains. Cockpit windows are already visible (no-op); only the legacy
        // tab path actually focuses/selects the tab here.
        launcher.revealOnStart(h);
        // Safety net: if the supervisor still shows no activity a few seconds
        // later (first user_input lost to a daemon-readiness race), resend once.
        scheduleKickoffRetry(nodeName, h);
    }

    /** Send the node's effective plan to the supervisor as its first user_input. */
    private void sendKickoff(String nodeName, NodeHandle h, boolean retry) {
        sendKickoff(nodeName, h, retry, false);
    }

    /**
     * Send the node's effective plan as the supervisor's kickoff user_input. When
     * {@code resumed} the plan is prefixed with a restart-reorientation header
     * (SR2/SR9) so the resumed supervisor verifies real state instead of trusting
     * recalled coordination it no longer has.
     */
    private void sendKickoff(String nodeName, NodeHandle h, boolean retry, boolean resumed) {
        if (h == null || h.pair == null) return;
        try {
            com.github.claudecodegui.session.pair.EventBus bus = h.pair.getEventBus();
            String planText = h.pair.getPlanContent();
            if (bus != null && planText != null && !planText.isBlank()) {
                String text = resumed ? (RESUME_REORIENT_HEADER + "\n\n" + planText) : planText;
                bus.publishUserInput(text);
                LOG.info("[Workflow] node " + nodeName + " kickoff" + (retry ? " (retry)" : "")
                        + (resumed ? " (resumed)" : "")
                        + " sent pair=" + h.pair.getPairId() + " (" + text.length() + " chars)");
            } else {
                LOG.warn("[Workflow] node " + nodeName + " kickoff skipped: "
                        + (bus == null ? "no event bus" : "empty plan"));
            }
        } catch (Exception e) {
            LOG.warn("[Workflow] node " + nodeName + " kickoff failed: " + e.getMessage());
        }
    }

    /**
     * Session resume (SR2): reorientation header prepended to a resumed node's
     * kickoff. Tells the supervisor its transcript was resumed but the Java
     * coordination state is fresh, so it must verify real state rather than trust
     * recalled "I dispatched / I'm waiting" beliefs.
     */
    private static final String RESUME_REORIENT_HEADER =
            "【会话已恢复 / 重启续接】你的对话历史已从之前的会话 resume(理论上你能往上看到之前做过的工作)。\n"
            + "⚠️ 请先做一件事,务必照做:用一两句话**复述你在之前这个会话里已经做过的关键步骤/决策**"
            + "(例如『我之前已派单让主 AI 读取并分析了 X,产出了 Y』)。如果你**完全看不到任何之前的历史**,"
            + "请明确写出『⚠️ 未能恢复历史会话,我没有之前的上下文』——这能让我们立刻确认会话恢复是否成功。\n\n"
            + "另外注意:本进程是 IDE/daemon 重启后重新拉起的,Java 侧的协调状态(合同/plan/进度计数)是全新重建的,"
            + "不是你记忆里的那个。不要相信记忆里『我已派单 / 我在等某个合同回执』——那些在新状态里并不存在。\n"
            + "复述完历史后,核对真实情况再决定下一步:用 Read/Grep 看相关改动文件、看主 AI 最近一段产出/仓库 git 状态,"
            + "判断下面这个任务里哪些已经完成、哪些还没做、应从哪里继续;然后正常 emit_action 推进"
            + "(派单 inject_prompt / 收尾 complete_plan / 升级 escalate_to_human),不要 emit wait。\n"
            + "————(以下是本节点要交付的任务,你之前可能已做了一部分)————";

    /**
     * Re-dispatch IN PLACE on the node's existing pair/window (breakpoint-resume):
     * best-effort interrupt any wedged in-flight turn, then publish a "check what's
     * already done and continue the remaining work" instruction into the SAME
     * conversation — so the prior history is preserved and the supervisor resumes
     * from where it stopped instead of redoing everything. Used by the manual
     * re-dispatch (adaptive) and the liveness watchdog when a live pair exists.
     */
    private void sendResumeKickoff(String nodeName, NodeHandle h) {
        if (h == null || h.pair == null) return;
        try {
            // NO interrupt() here: an interrupt reads to the supervisor as a user
            // abort → it goes to await-user and the resume input is swallowed. The
            // resume path only runs when the supervisor is idle-waiting (a wedged
            // in-progress turn is re-launched instead), so a plain publishUserInput
            // wakes it with the continue-instruction — same as the initial kickoff.
            com.github.claudecodegui.session.pair.EventBus bus = h.pair.getEventBus();
            if (bus == null) {
                LOG.warn("[Workflow] node " + nodeName + " resume skipped: no event bus");
                return;
            }
            String resume = buildResumeMessage(nodeName, h.pair.getPlanContent());
            bus.publishUserInput(resume);
            LOG.info("[Workflow] node " + nodeName + " resume-kick sent pair=" + h.pair.getPairId()
                    + " (" + resume.length() + " chars)");
        } catch (Exception e) {
            LOG.warn("[Workflow] node " + nodeName + " resume-kick failed: " + e.getMessage());
        }
    }

    /** Breakpoint-resume instruction (continue, don't restart) injected on re-dispatch. */
    private static String buildResumeMessage(String nodeName, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("【继续未完成的任务】\n");
        sb.append("先检查任务「").append(nodeName)
                .append("」当前已经执行到哪一步、已经产出了哪些结果（查看已有的对话历史与改动），");
        sb.append("然后在已有进度的基础上**继续把剩余未完成的部分做完**，不要从头重做、避免重复已完成的工作。");
        sb.append("全部完成后照常调用 emit_action(complete_workflow_node, …)。\n");
        if (plan != null && !plan.isBlank()) {
            sb.append("\n— 原始任务 —\n").append(plan);
        }
        return sb.toString();
    }

    /**
     * Schedule a single delayed retry of the kickoff, guarded so it never
     * double-sends once the supervisor has actually started a turn. Only armed in
     * monitor mode — the direct-forward path delivers synchronously inside
     * {@code publishUserInput}, so there's no race to cover there.
     */
    private void scheduleKickoffRetry(String nodeName, NodeHandle h) {
        if (h == null || h.pair == null) return;
        boolean monitorMode;
        try {
            monitorMode = h.pair.isMonitorEnabled()
                    && com.github.claudecodegui.session.pair.MonitorFeatureFlag.isEnabled();
        } catch (Exception e) {
            monitorMode = false;
        }
        if (!monitorMode) return;
        try {
            kickoffRetry.schedule(() -> submit(() -> {
                if (exec == null || exec.state != WorkflowState.RUNNING) return;
                NodeRuntime rt = exec.nodes.get(nodeName);
                if (rt == null || rt.status != NodeStatus.RUNNING) return;   // terminal/escalated
                if (supervisorStarted(h)) return;                             // already working
                LOG.warn("[Workflow] node " + nodeName + " supervisor idle "
                        + KICKOFF_RETRY_DELAY_SEC + "s after kickoff — resending once");
                sendKickoff(nodeName, h, true);
            }), KICKOFF_RETRY_DELAY_SEC, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            /* scheduler shut down */
        }
    }

    /** True once the node's supervisor has ticked (or is mid-tick). Unknown → true (don't double-send). */
    private static boolean supervisorStarted(NodeHandle h) {
        try {
            com.github.claudecodegui.session.pair.SupervisorMonitor m =
                    h.pair == null ? null : h.pair.getSupervisorMonitor();
            return m != null && (m.getTickCount() > 0 || m.isTickInProgress());
        } catch (Exception e) {
            return true;
        }
    }

    /** §8.7 — startPair failure / DAEMON_DOWN funnel. Does NOT free the slot (§17). */
    private void failNodeToHuman(String name, String reason) {
        if (exec == null) return;
        NodeRuntime rt = exec.nodes.get(name);
        if (rt == null || rt.status == NodeStatus.DONE || rt.status == NodeStatus.ABORTED) return;
        rt.status = NodeStatus.WAITING_HUMAN;
        rt.escalationReason = reason;
        LOG.info("[Workflow] node " + name + " WAITING_HUMAN reason=" + reason);
        escalate(name, reason);
        persist();
        broadcastExec();
    }

    private boolean allNodesDone() {
        for (NodeRuntime r : exec.nodes.values()) {
            if (r.status != NodeStatus.DONE) return false;
        }
        return true;
    }

    /** Focus a node's tab; assumes scheduler thread. */
    private void focusNode(String name) {
        if (name == null) return;
        launcher.focus(handles.get(name));
    }

    /**
     * §13 (P3) — assemble the node's effective plan and persist it. Returns the
     * on-disk path used as {@code StartPairParams.planPath}. Three sections:
     * <ol>
     *   <li>the node's own task (inline {@code plan}, or {@code planPath} fallback);</li>
     *   <li>{@code ## 上游产出} — one block per {@code dependsOn} node: its
     *       COMPLETION_REPORT.md summary + changed-files list (§13 b / DN16);</li>
     *   <li>the {@code [工作流编排]} footer telling the supervisor to emit
     *       {@code complete_workflow_node} (DN5: injected at the END of the
     *       per-node plan, never into the global system prompt).</li>
     * </ol>
     *
     * <p>Package-private for unit testing. Runs on the {@code wf-scheduler}
     * thread: it only READS scheduler-owned state ({@code exec.nodes},
     * {@code handles}, {@code nameToNode}) and does bounded disk IO — it writes
     * none of the §15-protected structures, so the thread invariant holds. The
     * slow path ({@code startPair}'s 20s handshake) already runs off-thread in
     * the launcher; the reads here are small wrap-up docs and node count is
     * capped at the concurrency ceiling (≤3), so the synchronous IO is
     * negligible. If reports grow large, snapshot the refs on the scheduler
     * thread and move the read+assembly into the launcher's pre-startPair step.
     */
    Path assembleAndWritePlan(WorkflowNode node) throws java.io.IOException {
        String wfId = currentDef != null ? currentDef.id : "wf";
        StringBuilder sb = new StringBuilder();

        // 1. the node's own task
        sb.append(readOwnPlan(node));

        // 2. upstream产出 — only when this node depends on others
        List<String> deps = node.dependsOnSafe();
        if (!deps.isEmpty()) {
            sb.append("\n\n## 上游产出\n");
            for (String dep : deps) {
                WorkflowNode depNode = nameToNode.get(dep);
                String supervisorId = depNode != null ? depNode.supervisorId : null;
                String agentName = resolveAgentName(supervisorId);
                String who = (agentName != null && !agentName.isEmpty())
                        ? agentName
                        : (supervisorId != null ? supervisorId : "");
                sb.append("\n### ").append(dep).append("（监督者：").append(who).append("）\n");

                String reportSummary = readUpstreamReportSummary(dep);
                sb.append(reportSummary != null && !reportSummary.isEmpty()
                        ? reportSummary : "（无完成报告）").append("\n");

                List<String> changed = upstreamChangedFiles(dep);
                sb.append("改动文件：")
                        .append(changed != null && !changed.isEmpty()
                                ? String.join(", ", changed) : "无")
                        .append("\n");
            }
        }

        // 3. workflow-orchestration footer (DN5 — per-node plan tail, not system prompt)
        sb.append("\n---\n");
        sb.append("[工作流编排] 你是工作流节点「").append(node.name).append("」。整体任务完成后**必须**调用\n");
        sb.append("emit_action(action=\"complete_workflow_node\", node_status=\"done\", summary=\"…\", changed_files=[…])；\n");
        sb.append("受阻需人工时调用 node_status=\"blocked\" + summary 说明卡点。\n");

        return store.writeNodePlan(wfId, node.name, sb.toString());
    }

    /** The node's own task text: inline {@code plan}, falling back to {@code planPath}. */
    private String readOwnPlan(WorkflowNode node) {
        String content = node.plan != null ? node.plan : "";
        if (content.isEmpty() && node.planPath != null && !node.planPath.isEmpty()) {
            try {
                content = Files.readString(Paths.get(node.planPath));
            } catch (Exception e) {
                LOG.warn("[Workflow] could not read node.planPath " + node.planPath + ": " + e.getMessage());
                content = "";
            }
        }
        return content;
    }

    /**
     * Read + summarize an upstream node's COMPLETION_REPORT.md. Prefers the
     * runtime's {@code completionReportPath} (set on DONE), else the handle's
     * pairDir. Returns null when missing/unreadable so the caller can degrade
     * gracefully. MVP: first {@link #UPSTREAM_REPORT_CAP} chars (P-future:
     * section-aware裁剪 to curb join-node context bloat).
     */
    private String readUpstreamReportSummary(String dep) {
        Path reportPath = null;
        NodeRuntime drt = exec != null ? exec.nodes.get(dep) : null;
        if (drt != null && drt.completionReportPath != null && !drt.completionReportPath.isEmpty()) {
            reportPath = Paths.get(drt.completionReportPath);
        } else {
            NodeHandle h = handles.get(dep);
            if (h != null && h.pairDir != null) {
                reportPath = h.pairDir.resolve("COMPLETION_REPORT.md");
            }
        }
        if (reportPath == null || !Files.isRegularFile(reportPath)) return null;
        try {
            String full = Files.readString(reportPath);
            if (full == null) return null;
            String t = full.trim();
            if (t.length() <= UPSTREAM_REPORT_CAP) return t;
            return t.substring(0, UPSTREAM_REPORT_CAP) + "\n…（报告已截断，详见 " + reportPath + "）";
        } catch (Exception e) {
            LOG.warn("[Workflow] could not read upstream report " + reportPath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Files an upstream node changed, from the report tool's {@code changed_files}
     * (carried on {@link NodeRuntime#changedFiles}). Null when unknown — the
     * caller renders 「无」.
     */
    private List<String> upstreamChangedFiles(String dep) {
        NodeRuntime drt = exec != null ? exec.nodes.get(dep) : null;
        if (drt != null && drt.changedFiles != null && !drt.changedFiles.isEmpty()) {
            return drt.changedFiles;
        }
        return null;
    }

    /** Resolve a supervisor's display name from config; null on miss (caller falls back). */
    private String resolveAgentName(String supervisorId) {
        if (supervisorId == null || supervisorId.isEmpty()) return null;
        try {
            JsonObject a = new CodemossSettingsService().getSupervisorAgentManager().getAgent(supervisorId);
            if (a != null && a.has("name") && !a.get("name").isJsonNull()) {
                return a.get("name").getAsString();
            }
        } catch (Exception e) {
            LOG.warn("[Workflow] resolveAgentName(" + supervisorId + ") failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Listener on a node's own pair plan, attached from {@code startNode}. Does
     * two things:
     *
     * <ul>
     *   <li><b>Completion safety net (DN1 / approach A)</b>: when the plan reaches
     *       {@code DONE}, report the node DONE to the engine — <i>however</i> the
     *       supervisor got there ({@code complete_plan} or
     *       {@code complete_workflow_node}). This decouples workflow advancement
     *       from the LLM picking one specific action (the failure mode where the
     *       supervisor fell back to {@code approve_and_continue}/{@code complete_plan}
     *       and the workflow stalled at 0/N). Idempotent with the explicit
     *       {@code complete_workflow_node} path — {@link #onNodeReport} no-ops if
     *       the node is already DONE. The explicit action still carries the richer
     *       {@code changed_files}; this is the deterministic backstop.</li>
     *   <li><b>DN2 watchdog backstop</b>: when the {@code DeadlockGuard} drives the
     *       plan to {@code WAITING} (a genuine stall, not a user pause — see
     *       {@link #isWatchdogStall}), funnel the node to {@code WAITING_HUMAN} so
     *       the overview never lies (RUNNING forever).</li>
     * </ul>
     */
    void attachWatchdogBackstop(PlanStateMachine planSm, String nodeName) {
        if (planSm == null) return;
        planSm.addListener((oldState, oldSub, now) -> {
            if (now != null && now.state == Plan.PlanState.DONE) {
                // Resolve the node's pairId from its handle; null in headless
                // tests / before pairStarted → skip (onNodeReport keys by pairId).
                NodeHandle h = handles.get(nodeName);
                String pairId = (h != null && h.pair != null) ? h.pair.getPairId() : null;
                if (pairId != null) onNodeReport(pairId, NodeStatus.DONE, null, null);
                return;
            }
            // Reverse of the WAITING_HUMAN funnel (2026-06-05): when the plan
            // recovers from WAITING back to ACTIVE — the operator handled the node
            // and fed the supervisor a message (EventBus.publishUserInput →
            // onHumanResumed), or it self-healed — flip a WAITING_HUMAN node back
            // to RUNNING. Without this the overview kept showing "待人工" forever
            // after the human had already handled it (the funnel was one-way).
            if (oldState == Plan.PlanState.WAITING && now != null
                    && now.state == Plan.PlanState.ACTIVE) {
                submit(() -> recoverNodeFromWaitingHuman(nodeName));
                return;
            }
            if (!isWatchdogStall(now)) return;
            // Don't escalate while a rate-limit auto-resume is pending: the node
            // is legitimately waiting for the quota reset, not stalled — it stays
            // RUNNING and the RateLimitWatcher re-pushes the task when it clears.
            NodeHandle h2 = handles.get(nodeName);
            if (h2 != null && h2.pair != null) {
                try {
                    if (h2.pair.getRateLimitWatcher().isWaitingForReset()) return;
                } catch (Exception ignored) { /* best-effort */ }
            }
            Object er = now.metadata == null ? null : now.metadata.get("escalationReason");
            String reason = (er instanceof String && !((String) er).isEmpty())
                    ? (String) er : "看门狗：监督者无响应";
            submit(() -> funnelWatchdogStall(nodeName, reason));
        });
    }

    /**
     * True when a plan transition represents a watchdog/system stall that should
     * funnel the node to WAITING_HUMAN: state is {@code WAITING} and it is NOT a
     * user-initiated pause ({@code metadata.pauseReason == "user"}).
     */
    static boolean isWatchdogStall(Plan now) {
        if (now == null) return false;
        if (now.state != Plan.PlanState.WAITING) return false;
        Object pauseReason = now.metadata == null ? null : now.metadata.get("pauseReason");
        return !"user".equals(pauseReason);
    }

    /** Funnel a watchdog-stalled node to WAITING_HUMAN (scheduler thread; idempotent). */
    private void funnelWatchdogStall(String nodeName, String reason) {
        if (exec == null || exec.state != WorkflowState.RUNNING) return;
        NodeRuntime rt = exec.nodes.get(nodeName);
        if (rt == null) return;
        if (rt.status == NodeStatus.WAITING_HUMAN || rt.status == NodeStatus.DONE
                || rt.status == NodeStatus.ABORTED) return;   // idempotent
        rt.status = NodeStatus.WAITING_HUMAN;
        rt.escalationReason = reason;
        LOG.info("[Workflow] node " + nodeName + " WAITING_HUMAN reason=" + reason + " (watchdog backstop)");
        escalate(nodeName, reason);
        persist();
        broadcastExec();
    }

    /**
     * Reverse of {@link #funnelWatchdogStall} (2026-06-05): the plan that had
     * been escalated to a human recovered to ACTIVE (the operator handled the
     * node — fed the supervisor a corrective message, which now flips the plan
     * back via {@code PlanStateMachine.onHumanResumed} — or it self-healed). Move
     * the node from {@code WAITING_HUMAN} back to {@code RUNNING} so the overview
     * reflects reality. Scheduler thread; idempotent (only acts on WAITING_HUMAN).
     *
     * <p>No slot (re)acquire: a node funneled to WAITING_HUMAN from RUNNING kept
     * its concurrency permit (D11 — the funnel never frees the slot), and this
     * listener only fires for a live started pair, so the permit is still held.
     */
    private void recoverNodeFromWaitingHuman(String nodeName) {
        if (exec == null || exec.state != WorkflowState.RUNNING) return;
        NodeRuntime rt = exec.nodes.get(nodeName);
        if (rt == null || rt.status != NodeStatus.WAITING_HUMAN) return;   // idempotent
        rt.status = NodeStatus.RUNNING;
        rt.escalationReason = null;
        lastDispatchAt.put(nodeName, System.currentTimeMillis());   // silent timer restarts at 0
        launcher.setNodeStatus(nodeName, NodeStatus.RUNNING);
        LOG.info("[Workflow] node " + nodeName + " WAITING_HUMAN → RUNNING (plan recovered WAITING→ACTIVE)");
        persist();
        broadcastExec();
    }

    /**
     * DN9 (§16.5) — a node's remote daemon crashed ({@code _ctrl/DAEMON_DOWN}).
     * Resolve the node by {@code pairId} (preferred) or {@code windowId} and funnel
     * it to WAITING_HUMAN so a single dead container doesn't stall the whole flow.
     *
     * <p>Entry point only — see the class-level concern about wiring the actual
     * {@code IBridge.DaemonLifecycleListener.onDaemonDied} signal of the
     * supervisor's dedicated bridge to this method.
     */
    public void onNodeDaemonDown(String windowId, String pairId, String reason) {
        submit(() -> {
            if (exec == null || exec.state != WorkflowState.RUNNING) return;
            String name = nodeNameFor(pairId, windowId);
            if (name == null) return;   // not a workflow node — ignore
            failNodeToHuman(name, reason != null && !reason.isEmpty() ? reason : "远程 daemon 崩溃");
        });
    }

    /**
     * Session resume (SR3/SR7, session-resume-plan.md): record the supervisor's
     * captured SDK session_id into the node's persisted runtime so a future
     * restart can resume its transcript. Called from {@code PairHandler}'s session
     * handler when the daemon emits {@code [SUPERVISOR_SESSION]}. No-op for pairs
     * that aren't workflow nodes. Scheduler thread; idempotent.
     */
    public void onSupervisorSessionCaptured(String pairId, String sessionId) {
        if (pairId == null || sessionId == null || sessionId.isEmpty()) return;
        submit(() -> {
            if (exec == null) return;
            String name = pairToNode.get(pairId);
            if (name == null) {
                // The pair→node mapping is normally set at pairStarted, before the
                // first supervisor turn. If the [SUPERVISOR_SESSION] line wins that
                // race, fall back to matching the live handle by pairId so the id is
                // still persisted — otherwise it is silently dropped and a later
                // restart cannot resume/replay the supervisor (the exact gap that
                // leaves the supervisor pane blank while the main AI restores).
                for (java.util.Map.Entry<String, NodeHandle> e : handles.entrySet()) {
                    NodeHandle nh = e.getValue();
                    if (nh != null && nh.pair != null && pairId.equals(nh.pair.getPairId())) {
                        name = e.getKey();
                        pairToNode.put(pairId, name);   // heal the mapping
                        break;
                    }
                }
            }
            if (name == null) return;   // not a workflow node
            NodeRuntime rt = exec.nodes.get(name);
            if (rt == null) return;
            // Remember the pairId this supervisor ran under so a restart-resume can
            // locate its prior L2 and carry the coordinator-event strip forward.
            boolean pairIdChanged = !pairId.equals(rt.supervisorPairId);
            rt.supervisorPairId = pairId;
            if (sessionId.equals(rt.supervisorSessionId)) {
                if (pairIdChanged) persist();   // id unchanged, but pairId moved
                return;
            }
            rt.supervisorSessionId = sessionId;
            // Session-kind refactor (S6): mirror into the container manifest (the
            // history ledger + restore source). Best-effort; never break capture.
            SessionRegistry registry = sessionRegistry();
            if (registry != null && rt.containerId != null) {
                try { registry.setSupervisorSession(rt.containerId, sessionId, rt.supervisorGeneration); }
                catch (Exception ignored) { /* best-effort */ }
            }
            // Best-effort: also snapshot the main-AI session id if it's already
            // bound (it usually binds later, on the first main-AI turn — see
            // onMainSessionCaptured for that path).
            NodeHandle h = handles.get(name);
            if (h != null && h.pair != null && rt.mainSessionId == null) {
                String msid = h.pair.getMainSessionId();
                if (msid != null && !msid.isEmpty()) {
                    rt.mainSessionId = msid;
                    if (registry != null && rt.containerId != null) {
                        try { registry.bindMainSession(rt.containerId, msid); }
                        catch (Exception ignored) { /* best-effort */ }
                    }
                }
            }
            LOG.info("[Workflow] node " + name + " captured supervisorSessionId=" + sessionId);
            persist();
        });
    }

    /**
     * Session resume (SR7): record the node's main-AI SDK session_id (bound when
     * the main AI takes its first turn — {@code PairSessionManager.bindMainSessionIdToPair}).
     * Wired from {@code ClaudeMessageHandler} alongside the L2 binding. No-op for
     * non-workflow pairs. Scheduler thread; idempotent.
     */
    public void onMainSessionCaptured(String pairId, String mainSessionId) {
        if (pairId == null || mainSessionId == null || mainSessionId.isEmpty()) return;
        submit(() -> {
            if (exec == null) return;
            String name = pairToNode.get(pairId);
            if (name == null) return;
            NodeRuntime rt = exec.nodes.get(name);
            if (rt == null || mainSessionId.equals(rt.mainSessionId)) return;
            rt.mainSessionId = mainSessionId;
            // Session-kind refactor (S6): append to the container's owned main-session
            // set (the FULL set, so a reset-in-place re-run's old main leg stays
            // claimed and never leaks into the normal history tab). Best-effort.
            SessionRegistry registry = sessionRegistry();
            if (registry != null && rt.containerId != null) {
                try { registry.bindMainSession(rt.containerId, mainSessionId); }
                catch (Exception ignored) { /* best-effort */ }
            }
            LOG.info("[Workflow] node " + name + " captured mainSessionId=" + mainSessionId);
            persist();
        });
    }

    /**
     * P5 (§17 / R4) — a node's tab was closed by the user while RUNNING. Funnel
     * the node to WAITING_HUMAN ("tab 被关闭") so the overview reflects that it
     * can no longer make progress. Only self-created workflow nodes are affected;
     * a non-workflow tab close resolves to no node and is a no-op. Idempotent.
     *
     * <p>Invoked from the tab's {@code content.setDisposer} (see IdeNodeLauncher);
     * runs only when an execution is RUNNING, so a tab closed after COMPLETED/
     * ABORTED — or during workflow teardown — is ignored.
     */
    public void onNodeTabClosed(String windowId) {
        if (windowId == null) return;
        submit(() -> {
            if (exec == null || exec.state != WorkflowState.RUNNING) return;
            String name = nodeNameFor(null, windowId);
            if (name == null) return;   // not a workflow node tab
            NodeRuntime rt = exec.nodes.get(name);
            if (rt == null || rt.status == NodeStatus.DONE || rt.status == NodeStatus.ABORTED
                    || rt.status == NodeStatus.WAITING_HUMAN) return;   // idempotent / terminal
            rt.status = NodeStatus.WAITING_HUMAN;
            rt.escalationReason = "tab 被关闭";
            handles.remove(name);   // the tab is gone — don't target it on later jump/focus
            LOG.info("[Workflow] node " + name + " WAITING_HUMAN reason=tab 被关闭");
            // Notice only (no auto-focus) — the tab no longer exists to focus.
            pushEscalationNotice(name, rt.escalationReason);
            persist();
            broadcastExec();
        });
    }

    /**
     * Startup recovery (D18/D20 — resume-and-redispatch-plan §3.1). A workflow
     * left {@code RUNNING} when the IDE shut down has lost all its pairs/cockpit
     * windows; instead of discarding it ({@code ABORTED}, the old D17 behaviour)
     * we reload it into the live {@link #exec} as {@code PAUSED} — the DAG and
     * node statuses are fully restored, the single-workflow lock is held, but the
     * engine does NOT schedule (no cockpit, no pump) until the user clicks 「恢复运行」
     * ({@link #resumeWorkflow}). Node-status mapping:
     * <ul>
     *   <li>{@code DONE} — kept (report on disk, downstream reads it);</li>
     *   <li>{@code RUNNING} — half-done, pair is dead → {@code WAITING_HUMAN} with
     *       an "IDE 重启" reason, awaiting a manual {@link #redispatchNode};</li>
     *   <li>{@code READY} — never started a pair (no side effects) → back to
     *       {@code PENDING} so resume re-queues it cleanly;</li>
     *   <li>{@code PENDING} / {@code WAITING_HUMAN} — kept.</li>
     * </ul>
     *
     * <p>The single-workflow lock means at most one execution is {@code RUNNING};
     * if stale data somehow holds several, the first is restored and the rest are
     * marked {@code ABORTED} on disk ({@link #abortPersistedOnly}). Runs on the
     * scheduler thread; {@link #exec} is null at startup so adopting it is safe.
     */
    public void rehydrateOnStartup() {
        submit(() -> {
            WorkflowExecution chosen = null;
            WorkflowDefinition chosenDef = null;
            for (WorkflowDefinition def : store.loadAll()) {
                if (def == null || def.id == null) continue;
                WorkflowExecution e = store.loadExecution(def.id);
                if (e == null || e.state != WorkflowState.RUNNING) continue;
                if (chosen == null) {
                    chosen = e;
                    chosenDef = def;
                } else {
                    abortPersistedOnly(def.id, e);   // stale duplicate — discard
                }
            }
            if (chosen == null || chosenDef == null) return;

            if (chosen.nodes != null) {
                for (NodeRuntime r : chosen.nodes.values()) {
                    if (r == null) continue;
                    if (r.status == NodeStatus.RUNNING) {
                        r.status = NodeStatus.WAITING_HUMAN;
                        r.escalationReason = "IDE 重启，任务已中断，可重新下发";
                        r.pairId = null;
                        r.windowId = null;
                    } else if (r.status == NodeStatus.READY) {
                        r.status = NodeStatus.PENDING;   // re-queued on resume
                    }
                    // SCHEDULED kept as-is (with scheduledStartAt); resume re-arms its
                    // timer (DN14). No timer is armed while PAUSED.
                }
            }

            currentDef = chosenDef;
            nameToNode.clear();
            for (WorkflowNode n : chosenDef.nodesSafe()) nameToNode.put(n.name, n);
            int n = chosen.concurrency > 0 ? chosen.concurrency : DEFAULT_WF_CONCURRENCY;
            chosen.concurrency = n;
            chosen.state = WorkflowState.PAUSED;
            exec = chosen;
            slots = new Semaphore(n);
            slotHolders.clear();
            redispatchAttempts.clear();
            lastDispatchAt.clear();
            cancelAllTimers();
            readyQueue.clear();
            pairToNode.clear();
            handles.clear();

            persist();
            broadcastExec();   // best-effort; webview补拉 via workflow_list (requestList pushes PAUSED)
            broadcastStatuses();   // → PAUSED badge in list
            LOG.info("[Workflow] rehydrate wf=" + chosen.workflowId + " RUNNING → PAUSED (await resume)");
        });
    }

    /** Discard a (duplicate / unchosen) persisted execution: non-terminal nodes → ABORTED, on disk only. */
    private void abortPersistedOnly(String id, WorkflowExecution e) {
        if (e.nodes != null) {
            for (NodeRuntime r : e.nodes.values()) {
                if (r != null && r.status != NodeStatus.DONE) r.status = NodeStatus.ABORTED;
            }
        }
        e.state = WorkflowState.ABORTED;
        store.saveExecution(id, e);
        LOG.info("[Workflow] startup: extra stale RUNNING wf=" + id + " → ABORTED (single-workflow lock)");
    }

    /**
     * §3.2 — one-click resume of a {@code PAUSED} (restored) execution. Opens the
     * cockpit now (NOT at startup — D18: no auto windows), flips to {@code RUNNING}
     * and pumps the "safe frontier" (never-started {@code PENDING} nodes whose deps
     * are all DONE). Interrupted {@code WAITING_HUMAN} nodes are NOT auto-run — the
     * user re-dispatches them individually ({@link #redispatchNode}); their
     * downstream stays {@code PENDING} until they reach DONE.
     */
    public void resumeWorkflow(@Nullable String id) {
        submit(() -> {
            if (exec == null || exec.state != WorkflowState.PAUSED) {
                broadcastOpResult("resume", false, "没有可恢复的工作流");
                broadcastExec();
                return;
            }
            if (id != null && !id.equals(exec.workflowId)) {
                broadcastOpResult("resume", false, "工作流不匹配");
                return;
            }
            if (currentDef == null) {
                broadcastOpResult("resume", false, "工作流定义缺失，无法恢复");
                return;
            }
            launcher.openCockpit(exec.concurrency);   // open windows only now
            exec.state = WorkflowState.RUNNING;
            slots = new Semaphore(exec.concurrency);
            slotHolders.clear();
            redispatchAttempts.clear();
            lastDispatchAt.clear();
            readyQueue.clear();
            enqueueReady();                            // safe frontier (PENDING deps-done)
            rearmScheduledTimers();                    // restored SCHEDULED nodes (DN14)
            LOG.info("[Workflow] resume wf=" + exec.workflowId + " PAUSED → RUNNING, ready=" + readyQueue);
            persist();
            broadcastExec();
            broadcastStatuses();   // → RUNNING badge in list
            broadcastOpResult("resume", true, null);
            pump();
        });
    }

    /**
     * §3.3 — re-dispatch a stuck/interrupted node (D21/D22/DN10). Adaptive:
     * <ul>
     *   <li>{@code mode="restart"} or no live pair → tear the old pair down and
     *       re-launch the node from scratch (fresh plan assembly picks up the
     *       latest upstream reports);</li>
     *   <li>otherwise (a live pair exists) → just re-send the kickoff to the
     *       existing supervisor (lightweight; the task "didn't land").</li>
     * </ul>
     * Only valid while {@code RUNNING} (DN10 — resume a PAUSED run first). If this
     * node holds no permit and concurrency is full, it is queued (D22) and starts
     * when a slot frees. No rollback (D4) — the front-end confirms first.
     */
    public void redispatchNode(@Nullable String name, @Nullable String mode) {
        submit(() -> redispatchNodeInternal(name, mode, true));   // manual: reset backoff
    }

    /**
     * Core re-dispatch (scheduler thread). {@code manual=true} (user button) resets
     * the watchdog backoff exponent; {@code manual=false} (auto self-heal) keeps the
     * already-bumped exponent. Always refreshes {@link #lastDispatchAt} so the silent
     * timer / watchdog idle restart from 0.
     */
    private void redispatchNodeInternal(@Nullable String name, @Nullable String mode, boolean manual) {
        if (exec == null || exec.state != WorkflowState.RUNNING) {
            broadcastOpResult("redispatch", false, "请先恢复运行工作流");
            return;
        }
        WorkflowNode node = name == null ? null : nameToNode.get(name);
        NodeRuntime rt = name == null ? null : exec.nodes.get(name);
        if (node == null || rt == null) {
            broadcastOpResult("redispatch", false, "节点不存在");
            return;
        }
        if (rt.status == NodeStatus.DONE) {
            broadcastOpResult("redispatch", false, "节点已完成，无需重新下发");
            return;
        }

        if (manual) redispatchAttempts.put(name, 0);            // manual = fresh backoff
        lastDispatchAt.put(name, System.currentTimeMillis());   // silent timer restarts at 0

        // D27: re-dispatch means "run now" — drop any pending start timer/delay.
        cancelTimer(name);
        rt.scheduledStartAt = null;

        NodeHandle h = handles.get(name);
        boolean livePair = h != null && h.pair != null;
        boolean forceRestart = "restart".equals(mode);
        // Session resume (SR8/SR9): when re-dispatching a node that has NO live pair
        // (the common restart-recovery case: the node was interrupted, its pair is
        // gone) AND we captured a session id last time AND the caller didn't force a
        // fresh restart → resume the prior transcripts (supervisor + main AI) on a
        // fresh pair, so the LLMs see their history and decide where to continue.
        // A LIVE node uses resume-in-place below (don't transcript-resume a session
        // that's still running); explicit "restart" always starts fresh (SR6 too:
        // no captured ids ⇒ resumeMode false ⇒ plain re-launch). No webview change
        // needed — the existing re-dispatch button gets history-resume for free.
        boolean resumeMode = !forceRestart && !livePair
                && (rt.supervisorSessionId != null || rt.mainSessionId != null);

        // Ensure this node holds exactly one permit (D22/D23).
        if (!slotHolders.contains(name)) {
            if (slots == null || !slots.tryAcquire()) {
                // Concurrency full → queue; pump() starts it when a slot frees.
                rt.status = NodeStatus.READY;
                rt.escalationReason = null;
                if (!readyQueue.contains(name)) readyQueue.add(name);
                launcher.setNodeStatus(name, NodeStatus.READY);
                persist();
                broadcastExec();
                broadcastOpResult("redispatch", true, "并发已满，已排队");
                pump();
                return;
            }
            slotHolders.add(name);
        }

        rt.escalationReason = null;

        if (livePair && !forceRestart && !resumeMode) {
            // (a) resume IN PLACE — reuse the existing pair/window, inject a
            // breakpoint-resume instruction (continue, don't restart). History
            // is preserved; no new window is opened.
            rt.status = NodeStatus.RUNNING;
            launcher.setNodeStatus(name, NodeStatus.RUNNING);
            launcher.focus(h);   // bring the existing node window to front
            sendResumeKickoff(name, h);
            LOG.info("[Workflow] redispatch(resume-in-place) node=" + name
                    + " pair=" + (h.pair != null ? h.pair.getPairId() : "?"));
        } else {
            // (b) heavyweight re-launch — kill old pair, drop handle/mapping,
            // re-run startNode (it does NOT acquire a slot; we already hold one).
            // Session resume (SR9): stage the captured ids so the new pair starts
            // on the historical transcript, and mark the node so kickoffAndReveal
            // injects the post-resume reprime before the task kickoff.
            if (resumeMode) {
                launcher.setPendingResume(name, rt.supervisorSessionId, rt.mainSessionId, rt.supervisorPairId);
                resumedNodes.add(name);
                LOG.info("[Workflow] redispatch(resume) node=" + name
                        + " supervisorSid=" + rt.supervisorSessionId + " mainSid=" + rt.mainSessionId
                        + " priorPairId=" + rt.supervisorPairId);
            }
            if (h != null && h.windowId != null) launcher.stop(h.windowId);
            handles.remove(name);
            if (rt.pairId != null) pairToNode.remove(rt.pairId);
            rt.pairId = null;
            rt.windowId = null;
            rt.status = NodeStatus.READY;          // pairStarted flips READY→RUNNING
            launcher.setNodeStatus(name, NodeStatus.READY);
            startNode(node);
            LOG.info("[Workflow] redispatch(re-launch) node=" + name
                    + " forceRestart=" + forceRestart + " resume=" + resumeMode);
        }
        persist();
        broadcastExec();
        // Manual re-dispatch shows the generic success toast; the auto (watchdog)
        // path stays quiet here and emits its own soft onWorkflowAutoResume notice.
        if (manual) broadcastOpResult("redispatch", true, null);
    }

    /** Resolve a node name by pairId (preferred) or owning windowId. Scheduler thread. */
    private String nodeNameFor(String pairId, String windowId) {
        String name = pairId != null ? pairToNode.get(pairId) : null;
        if (name == null && windowId != null) {
            for (Map.Entry<String, NodeHandle> e : handles.entrySet()) {
                NodeHandle h = e.getValue();
                if (h != null && windowId.equals(h.windowId)) return e.getKey();
            }
        }
        return name;
    }

    private WorkflowExecution newExecution(WorkflowDefinition def, int concurrency) {
        WorkflowExecution e = new WorkflowExecution();
        e.workflowId = def.id;
        e.state = WorkflowState.EDITING;
        e.concurrency = concurrency;
        for (WorkflowNode n : def.nodesSafe()) {
            e.nodes.put(n.name, new NodeRuntime());   // PENDING by default
        }
        return e;
    }

    // ─── broadcast / escalate / op-result (§8.8) ─────────────────────────

    private void broadcastExec() {
        WorkflowExecution e = exec;
        if (e == null) return;
        broadcast("window.onWorkflowExecutionUpdate", gson.toJson(e));
    }

    private void broadcastDefinitions() {
        broadcast("window.onWorkflowDefinitions", gson.toJson(store.loadAll()));
        broadcastStatuses();   // definitions changed → refresh per-workflow status badges
    }

    /**
     * D24/DN15 — push each workflow's latest execution state ({@code {wfId: state}})
     * so the left list can badge running / paused / completed / aborted across
     * restarts. The live execution uses its in-memory state (freshest); the rest
     * are read from {@code execution.json}. Called only at workflow-level state
     * changes (not per-node) to avoid re-reading every execution on each tick.
     */
    private void broadcastStatuses() {
        broadcast("window.onWorkflowStatuses", statusesJson());
    }

    private String statusesJson() {
        JsonObject o = new JsonObject();
        WorkflowExecution live = exec;
        for (WorkflowDefinition def : store.loadAll()) {
            if (def == null || def.id == null) continue;
            WorkflowState st;
            if (live != null && def.id.equals(live.workflowId)) {
                st = live.state;
            } else {
                WorkflowExecution e = store.loadExecution(def.id);
                st = e != null ? e.state : null;
            }
            if (st != null) o.addProperty(def.id, st.name());
        }
        return gson.toJson(o);
    }

    private void escalate(String name, String reason) {
        pushEscalationNotice(name, reason);
        launcher.setNodeStatus(name, NodeStatus.WAITING_HUMAN);   // cockpit: red border
        focusNode(name);   // bring the escalated node's window/tab to front (D8)
    }

    /** Push the escalation toast/notice without focusing the tab (D8 focus is opt-in). */
    private void pushEscalationNotice(String name, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("nodeName", name);
        if (reason != null) o.addProperty("reason", reason);
        broadcast("window.onWorkflowEscalation", gson.toJson(o));
    }

    private void broadcastOpResult(String op, boolean ok, String err) {
        JsonObject o = new JsonObject();
        o.addProperty("success", ok);
        o.addProperty("operation", op);
        if (err != null) o.addProperty("error", err);
        broadcast("window.onWorkflowOperationResult", gson.toJson(o));
    }

    private String capabilitiesJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode());
        o.addProperty("maxConcurrency", ceiling());
        o.addProperty("freezeThresholdMinutes", freezeThresholdMs() / 60_000L);   // D40: silent-time display threshold
        return gson.toJson(o);
    }

    private void broadcastCapabilities() {
        broadcast("window.onWorkflowCapabilities", capabilitiesJson());
    }

    /**
     * Persist the global node-liveness freeze threshold (minutes; 0 = disabled),
     * then re-broadcast capabilities so every webview's silent-time display + the
     * watchdog pick up the new value. Handler thread.
     */
    public void setFreezeThresholdMinutes(int minutes) {
        try {
            new CodemossSettingsService().setWorkflowFreezeThresholdMinutes(minutes);
            LOG.info("[Workflow] freeze threshold set to " + minutes + "min");
        } catch (Exception e) {
            LOG.warn("[Workflow] setFreezeThresholdMinutes failed: " + e.getMessage());
        }
        broadcastCapabilities();
    }

    private void broadcast(String fn, String json) {
        for (HandlerContext.JsCallback s : uiSinks) {
            pushTo(s, fn, json);
        }
    }

    private void pushTo(HandlerContext.JsCallback sink, String fn, String json) {
        if (sink == null) return;
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null) {
            // Headless / unit-test path: no EDT to marshal onto, push inline.
            try {
                sink.callJavaScript(fn, sink.escapeJs(json));
            } catch (Exception ignored) {
                /* best-effort */
            }
            return;
        }
        app.invokeLater(() -> {
            try {
                sink.callJavaScript(fn, sink.escapeJs(json));
            } catch (Exception e) {
                LOG.warn("[Workflow] push " + fn + " failed: " + e.getMessage());
            }
        });
    }

    public void registerSink(@NotNull HandlerContext.JsCallback sink) {
        uiSinks.add(sink);
    }

    public void unregisterSink(@NotNull HandlerContext.JsCallback sink) {
        uiSinks.remove(sink);
    }

    private void persist() {
        if (exec != null) store.saveExecution(exec.workflowId, exec);
    }

    // ─── config helpers ──────────────────────────────────────────────────

    /** DN4: effective ceiling = min(configured, hard cap 3). */
    int ceiling() {
        return Math.max(1, ceilingSupplier.getAsInt());
    }

    /**
     * P4 (§14.1 / DN4): effective ceiling = {@code min(configured, HARD_CAP)},
     * config read from {@code CodemossSettingsService.getWorkflowMaxConcurrency()}
     * (supervisor config family), falling back to {@link #DEFAULT_GLOBAL_CONCURRENCY}
     * on any read error.
     */
    private int resolveCeiling() {
        int configured;
        try {
            configured = new CodemossSettingsService().getWorkflowMaxConcurrency();
        } catch (Exception e) {
            LOG.warn("[Workflow] resolveCeiling read failed; using default: " + e.getMessage());
            configured = DEFAULT_GLOBAL_CONCURRENCY;
        }
        return clampCeiling(configured);
    }

    /** DN4 clamp: configured concurrency → {@code [1, HARD_CAP]}. */
    static int clampCeiling(int configured) {
        return Math.max(1, Math.min(configured, HARD_CAP));
    }

    private String mode() {
        try {
            RemoteModeContext ctx = RemoteModeContext.getInstance();
            return ctx != null && ctx.isRemote() ? "remote" : "local";
        } catch (Exception e) {
            return "local";
        }
    }

    private Set<String> loadKnownSupervisorIds() {
        Set<String> ids = new HashSet<>();
        try {
            for (JsonObject a : new CodemossSettingsService().getSupervisorAgents()) {
                if (a != null && a.has("id") && !a.get("id").isJsonNull()) {
                    ids.add(a.get("id").getAsString());
                }
            }
        } catch (Exception e) {
            LOG.warn("[Workflow] loadKnownSupervisorIds failed: " + e.getMessage());
        }
        return ids;
    }

    private boolean isPathAllowed(String pathStr) {
        try {
            Path p = java.nio.file.Paths.get(pathStr).toAbsolutePath().normalize();
            Path root = store.getRoot().toAbsolutePath().normalize();
            if (p.startsWith(root)) return true;
            if (project != null && project.getBasePath() != null) {
                Path base = java.nio.file.Paths.get(project.getBasePath()).toAbsolutePath().normalize();
                if (p.startsWith(base)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void dispose() {
        try {
            launcher.closeCockpit();   // dispose any open node windows on project close
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            scheduler.shutdown();
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            kickoffRetry.shutdownNow();
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            nodeTimers.shutdownNow();
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            nodeWatchdog.shutdownNow();
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    // ─── test-only accessors ─────────────────────────────────────────────

    @org.jetbrains.annotations.TestOnly
    WorkflowExecution execForTest() {
        return exec;
    }

    @org.jetbrains.annotations.TestOnly
    int availableSlotsForTest() {
        return slots == null ? -1 : slots.availablePermits();
    }

    @org.jetbrains.annotations.TestOnly
    List<String> readyQueueForTest() {
        return new ArrayList<>(readyQueue);
    }

    /** Drive a SCHEDULED node's timer synchronously (avoids waiting real wall-clock). */
    @org.jetbrains.annotations.TestOnly
    void fireScheduledForTest(String name) {
        submit(() -> fireScheduled(name));
    }
}
