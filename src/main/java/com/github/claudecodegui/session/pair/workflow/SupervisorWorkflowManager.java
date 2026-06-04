package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.plan.Plan;
import com.github.claudecodegui.session.pair.plan.PlanStateMachine;
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
    private final Deque<String> readyQueue = new ArrayDeque<>();
    private final Map<String, WorkflowNode> nameToNode = new HashMap<>();
    // ────────────────────────────────────────────────────────────────────

    private final Map<String, String> pairToNode = new ConcurrentHashMap<>();
    private final Map<String, NodeHandle> handles = new ConcurrentHashMap<>();

    // ─── construction ───────────────────────────────────────────────────

    /** Production constructor (invoked by the IntelliJ service container). */
    public SupervisorWorkflowManager(@NotNull Project project) {
        this.project = project;
        this.store = new WorkflowStore(project);
        this.launcher = new IdeNodeLauncher(project);
        this.scheduler = newSingleThreadScheduler();
        this.knownSupervisorIdsSupplier = this::loadKnownSupervisorIds;
        this.ceilingSupplier = this::resolveCeiling;
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
        if (e != null && e.state == WorkflowState.RUNNING && id.equals(e.workflowId)) {
            broadcastOpResult("delete", false, "工作流正在运行，无法删除");
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
        WorkflowExecution e = exec;
        if (e != null && e.state == WorkflowState.RUNNING) {
            pushTo(sink, "window.onWorkflowExecutionUpdate", gson.toJson(e));
        }
    }

    // ─── run / abort / jump / report (scheduler thread; §8) ──────────────

    /** §8.2 — single-workflow lock + cycle check + clamp concurrency, then pump. */
    public void startWorkflow(@Nullable String id) {
        submit(() -> {
            if (exec != null && exec.state == WorkflowState.RUNNING) {
                broadcastOpResult("run", false, "已有工作流在运行");
                // Re-sync any webview that lost the running snapshot (bridge race
                // / opened after the run began): without this it stays on
                // "Editing" and keeps getting rejected with no way to see — or
                // stop — the workflow that holds the lock.
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
            readyQueue.clear();
            pairToNode.clear();
            handles.clear();
            exec.state = WorkflowState.RUNNING;

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
            broadcastOpResult("run", true, null);
            pump();
        });
    }

    /** §8.7 — stop active pairs, mark live nodes ABORTED, freeze execution. */
    public void abortWorkflow() {
        submit(() -> {
            if (exec == null || exec.state != WorkflowState.RUNNING) return;
            for (NodeHandle h : handles.values()) {
                if (h != null && h.windowId != null) launcher.stop(h.windowId);
            }
            for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
                NodeRuntime r = e.getValue();
                if (r.status == NodeStatus.RUNNING || r.status == NodeStatus.READY
                        || r.status == NodeStatus.WAITING_HUMAN || r.status == NodeStatus.PENDING) {
                    r.status = NodeStatus.ABORTED;
                    launcher.setNodeStatus(e.getKey(), NodeStatus.ABORTED);   // cockpit: grey border (window kept)
                }
            }
            exec.state = WorkflowState.ABORTED;
            LOG.info("[Workflow] ABORTED wf=" + exec.workflowId);
            persist();
            broadcastExec();   // tabs retained (D13)
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
                NodeHandle h = handles.get(name);
                if (h != null && h.pairDir != null) {
                    rt.completionReportPath = h.pairDir.resolve("COMPLETION_REPORT.md").toString();
                }
                if (slots != null) slots.release();
                launcher.setNodeStatus(name, NodeStatus.DONE);   // cockpit: green ✓ (window kept)
                LOG.info("[Workflow] node " + name + " DONE report=" + rt.completionReportPath);
                enqueueReady();
                if (allNodesDone()) {
                    exec.state = WorkflowState.COMPLETED;
                    LOG.info("[Workflow] COMPLETED wf=" + exec.workflowId);
                }
                persist();
                broadcastExec();
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

    /** §8.3 — rolling enqueue (DN3): any PENDING node with all deps DONE → READY. */
    private void enqueueReady() {
        for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
            NodeRuntime r = e.getValue();
            if (r.status == NodeStatus.PENDING && allDepsDone(e.getKey())) {
                r.status = NodeStatus.READY;
                readyQueue.add(e.getKey());
            }
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
            LOG.info("[Workflow] pump acquired slot for " + name + ", ready=" + readyQueue);
            startNode(nameToNode.get(name));
        }
    }

    /** §8.5 — assemble plan (scheduler thread), then hand off to the launcher. */
    private void startNode(WorkflowNode node) {
        if (node == null) return;
        final NodeRuntime rt = exec.nodes.get(node.name);
        if (rt == null) return;

        Path planPath;
        try {
            planPath = assembleAndWritePlan(node);
        } catch (Exception e) {
            failNodeToHuman(node.name, "组装 plan 失败: " + e.getMessage());
            return;
        }

        final String nodeName = node.name;
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
                    if (rt.status == NodeStatus.READY) rt.status = NodeStatus.RUNNING;
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
        sendKickoff(nodeName, h, false);
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
        if (h == null || h.pair == null) return;
        try {
            com.github.claudecodegui.session.pair.EventBus bus = h.pair.getEventBus();
            String planText = h.pair.getPlanContent();
            if (bus != null && planText != null && !planText.isBlank()) {
                bus.publishUserInput(planText);
                LOG.info("[Workflow] node " + nodeName + " kickoff" + (retry ? " (retry)" : "")
                        + " sent pair=" + h.pair.getPairId() + " (" + planText.length() + " chars)");
            } else {
                LOG.warn("[Workflow] node " + nodeName + " kickoff skipped: "
                        + (bus == null ? "no event bus" : "empty plan"));
            }
        } catch (Exception e) {
            LOG.warn("[Workflow] node " + nodeName + " kickoff failed: " + e.getMessage());
        }
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
            if (!isWatchdogStall(now)) return;
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
     * Startup recovery (§17 / D17): a workflow left {@code RUNNING} when the IDE
     * shut down has lost all its pairs/tabs and is NOT auto-resumed — mark its
     * persisted execution (and any non-terminal node) {@code ABORTED}. Runs on
     * the scheduler thread over the on-disk copies only; never touches the live
     * {@link #exec} (which is null at startup).
     */
    public void recoverStaleExecutionsOnStartup() {
        submit(() -> {
            for (WorkflowDefinition def : store.loadAll()) {
                if (def == null || def.id == null) continue;
                WorkflowExecution e = store.loadExecution(def.id);
                if (e == null || e.state != WorkflowState.RUNNING) continue;
                if (e.nodes != null) {
                    for (NodeRuntime r : e.nodes.values()) {
                        if (r != null && r.status != NodeStatus.DONE) r.status = NodeStatus.ABORTED;
                    }
                }
                e.state = WorkflowState.ABORTED;
                store.saveExecution(def.id, e);
                LOG.info("[Workflow] startup recovery: wf=" + def.id
                        + " stale RUNNING → ABORTED (pairs/tabs gone after restart)");
            }
        });
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
        return gson.toJson(o);
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
}
