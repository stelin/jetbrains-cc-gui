package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link NodeLauncher}: builds a node's host (a tiled floating
 * "cockpit" window, or — beyond {@link #MAX_COCKPIT_TILES} / as a fallback — a
 * tool-window tab) on the EDT, then starts its Pair on the background pool (the
 * {@code startPair} 20s synchronous handshake must never run on the EDT — see
 * {@code PairHandler:317} and {@code docs/workflow/coding-plan.md} §8.5 / §15).
 *
 * <p><b>Why floating windows</b> (cockpit-plan §1): a tool-window tab only
 * realizes its JCEF webview when SELECTED, so concurrent same-layer nodes could
 * never all mount → their supervisor→main-AI injects never drained. A visible
 * {@link com.github.claudecodegui.ui.detached.WorkflowNodeFrame} realizes its
 * webview immediately, so every node runs.
 */
public final class IdeNodeLauncher implements NodeLauncher {

    private static final Logger LOG = Logger.getInstance(IdeNodeLauncher.class);

    private final Project project;
    /** Non-null while a cockpit-mode run is active. */
    private volatile WorkflowCockpit cockpit;

    /**
     * Session resume (SR4/SR10): nodeName → {supervisorSessionId, mainSessionId,
     * priorPairId} staged by {@link #setPendingResume} before a resume-mode
     * re-launch, consumed and cleared by the next {@link #startNodePair}. Any
     * entry may be null.
     */
    private final Map<String, String[]> pendingResume = new ConcurrentHashMap<>();
    /**
     * Session-kind refactor (S6): nodeName → persistent container id, staged by
     * {@link #setPendingContainerId} before each launch and consumed by the next
     * {@link #startNodePair} (→ {@code StartPairParams.containerId}).
     */
    private final Map<String, String> pendingContainerId = new ConcurrentHashMap<>();

    public IdeNodeLauncher(Project project) {
        this.project = project;
    }

    @Override
    public void setPendingResume(String nodeName, String supervisorSessionId,
                                 String mainSessionId, String priorPairId) {
        if (nodeName == null) return;
        if (supervisorSessionId == null && mainSessionId == null && priorPairId == null) {
            pendingResume.remove(nodeName);
            return;
        }
        pendingResume.put(nodeName, new String[]{ supervisorSessionId, mainSessionId, priorPairId });
    }

    @Override
    public void setPendingContainerId(String nodeName, String containerId) {
        if (nodeName == null) return;
        if (containerId == null || containerId.isEmpty()) {
            pendingContainerId.remove(nodeName);
            return;
        }
        pendingContainerId.put(nodeName, containerId);
    }

    @Override
    public void openCockpit(int slotCount) {
        // Closing any prior run's windows first (new run = fresh board).
        WorkflowCockpit prev = this.cockpit;
        if (prev != null) prev.closeAll();
        WorkflowCockpit c = new WorkflowCockpit(project, slotCount, windowId -> {
            try {
                SupervisorWorkflowManager.getInstance(project).onNodeTabClosed(windowId);
            } catch (Exception ignored) {
                /* best-effort: window-close funnel is advisory */
            }
        });
        this.cockpit = c;
        c.open();
    }

    @Override
    public void launch(WorkflowNode node, Path planPath, Sink sink) {
        final WorkflowCockpit c = this.cockpit;
        // —— EDT: build the node's host (cockpit window or fallback tab) ——
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            try {
                ClaudeChatWindow win = new ClaudeChatWindow(project, true);
                win.setOriginalTabName(node.name);
                NodeHandle handle;
                if (c != null) {
                    // —— cockpit mode: open a floating window for this node now ——
                    c.attach(node.name, node.supervisorId, win);
                    handle = new NodeHandle(null, win);   // no tool-window Content
                } else {
                    // —— legacy tab mode (fallback / > MAX nodes) ——
                    ToolWindow tw = toolWindow();
                    if (tw == null) {
                        sink.failed("CC GUI 工具窗口不可用，无法创建节点窗口");
                        win.dispose();
                        return;
                    }
                    ContentManager cm = tw.getContentManager();
                    String tabName = uniqueTabName(node.name, cm);
                    Content content = ContentFactory.getInstance()
                            .createContent(win.getContent(), tabName, false);
                    content.setCloseable(true);
                    win.setParentContent(content);
                    win.setOriginalTabName(tabName);
                    // P5 (§17 / R4): a user-closed node tab funnels its node to
                    // WAITING_HUMAN before tearing down the tab.
                    content.setDisposer(() -> {
                        try {
                            SupervisorWorkflowManager.getInstance(project).onNodeTabClosed(win.getWindowId());
                        } catch (Exception ignored) {
                            /* best-effort */
                        }
                        win.dispose();
                    });
                    cm.addContent(content);
                    handle = new NodeHandle(content, win);
                }
                handle.nodeName = node.name;
                sink.tabCreated(win.getWindowId(), handle);

                // —— background pool: startPairWired (20s handshake; same both modes) ——
                AppExecutorUtil.getAppExecutorService().submit(() -> startNodePair(node, planPath, win, handle, sink));
            } catch (Exception e) {
                LOG.warn("[Workflow] node window creation failed for " + node.name + ": " + e.getMessage());
                sink.failed("创建节点窗口失败: " + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    /**
     * Start the node's Pair through its OWN PairHandler.startPairWired so the
     * supervisor gets the identical webview transport wiring as a composer pair
     * (message batches, live usage, ActionRouter UI injects, onPairStarted) —
     * bound to this node window's context. A bare {@code startPair()} skips all
     * that, leaving the pane blank. Runs on the background pool.
     */
    private void startNodePair(WorkflowNode node, Path planPath, ClaudeChatWindow win,
                               NodeHandle handle, Sink sink) {
        try {
            // Session resume (SR4/SR10): consume any staged resume ids for this node.
            String[] resume = pendingResume.remove(node.name);
            String resumeSupervisorSid = resume != null ? resume[0] : null;
            String resumeMainSid = resume != null ? resume[1] : null;
            String priorPairId = resume != null && resume.length > 2 ? resume[2] : null;

            // Session-kind refactor (S6): the node's stable container id, staged at
            // workflow start. Threaded into StartPairParams so the pair's L2 +
            // pair_* routing key by it (PairSession.getL2Key() now returns it
            // instead of the pairId fallback used before S6).
            String containerId = pendingContainerId.remove(node.name);

            PairSessionManager.StartPairParams params = new PairSessionManager.StartPairParams(
                    null, node.supervisorId, planPath == null ? null : planPath.toString(),
                    node.model, node.longContext, node.reasoning, win.getWindowId(),
                    resumeSupervisorSid, containerId);
            // Carry the prior pair's persisted coordinator-event strip forward.
            params.priorPairId = priorPairId;
            if (win.getChatWindowDelegate() == null
                    || win.getChatWindowDelegate().getPairHandler() == null) {
                sink.failed("节点窗口未就绪，无法启动监督者");
                return;
            }
            // Session-kind refactor: stamp the container id onto the node window's
            // main-AI SessionState so SessionSendService.prependPairContextMarker
            // injects the pair-context marker → daemon mounts mcp__main → the node's
            // main AI calls report_turn_completion (parity with the manual
            // session_create_supervised path, PairHandler.handleCreateSupervisedImpl).
            // The resume branch below also rebuilds the session WITH the container id
            // (loadHistorySession), but a FRESH node never resumes — without this
            // stamp its main leg would carry a null container and never report.
            if (containerId != null && !containerId.isEmpty()
                    && win.getSession() != null && win.getSession().getState() != null) {
                win.getSession().getState().setContainerId(containerId);
                LOG.info("[Workflow] node " + node.name + " stamped containerId on main-AI session: " + containerId);
            }
            // SR10: seed the node window's main-AI session to resume its transcript
            // on its first turn (triggered by the supervisor's first inject_prompt).
            // Best-effort: only if the window exposes a resume hook. Logged so the
            // E2E test (§9.3) can confirm the main-AI resume fired.
            if (resumeMainSid != null && !resumeMainSid.isEmpty()) {
                try {
                    win.resumeMainSession(resumeMainSid, containerId);
                    LOG.info("[Workflow] node " + node.name + " main-AI resume seeded sessionId=" + resumeMainSid);
                } catch (Throwable t) {
                    LOG.warn("[Workflow] node " + node.name + " main-AI resume seed failed (will start fresh): " + t.getMessage());
                }
            }
            PairSession pair = win.getChatWindowDelegate().getPairHandler().startPairWired(
                    params, new com.github.claudecodegui.session.pair.protocol.PairBudget());
            // Session-kind refactor (S6): record the live pairId on the node's
            // container manifest (internal pointer; no longer the directory key).
            if (containerId != null && !containerId.isEmpty() && project != null) {
                try {
                    com.github.claudecodegui.session.registry.SessionRegistry.getInstance(project)
                            .setPairId(containerId, pair.getPairId());
                } catch (Exception e) {
                    LOG.warn("[Workflow] node " + node.name + " setPairId failed: " + e.getMessage());
                }
            }
            handle.pair = pair;
            handle.pairDir = pair.getPairDir();

            // Seed the node window's MAIN AI (left pane) so BOTH legs (main AI +
            // supervisor) run the SAME model + thinking depth the node resolved to.
            // Built from the supervisor's RESOLVED values (pair.getModel() /
            // getReasoningEffort()) — these already fold in the node override OR the
            // agent default, so the main AI matches even when the node stored no
            // explicit override (raw node.model/reasoning are null in that case). The
            // model carries the [1m] suffix when 1M is on; the webview derives the
            // toggle from it. Pushed now (post-handshake → webview mounted, handler
            // registered) so it lands; a frontend_ready-time push would race React.
            String mainAiConfig = buildNodeMainAiConfig(pair.getModel(), pair.getReasoningEffort());
            if (mainAiConfig != null) {
                win.applyNodeMainAiConfig(mainAiConfig);
                LOG.info("[Workflow] node " + node.name + " seeded main-AI model/reasoning: " + mainAiConfig);
            }

            sink.pairStarted(pair.getPairId(), pair.getPairDir());
        } catch (Exception ex) {
            LOG.warn("[Workflow] startPair failed for node " + node.name + ": " + ex.getMessage());
            sink.failed("启动失败: " + (ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    @Override
    public void stop(String windowId) {
        if (windowId == null || windowId.isEmpty()) return;
        try {
            PairSessionManager.getInstance(project).stopPairsOwnedBy(windowId);
        } catch (Exception e) {
            LOG.warn("[Workflow] stop pairs for window " + windowId + " failed: " + e.getMessage());
        }
    }

    @Override
    public void revealOnStart(NodeHandle handle) {
        // Cockpit windows are already visible — no reveal needed. Only the legacy
        // tab path must select the tab so its lazy webview mounts.
        if (cockpit != null) return;
        focus(handle);
    }

    @Override
    public void setNodeStatus(String nodeName, NodeStatus status) {
        WorkflowCockpit c = this.cockpit;
        if (c != null) c.setStatus(nodeName, status);
    }

    @Override
    public void closeCockpit() {
        WorkflowCockpit c = this.cockpit;
        if (c != null) c.closeAll();
        this.cockpit = null;
    }

    @Override
    public void focus(NodeHandle handle) {
        if (handle == null) return;
        WorkflowCockpit c = this.cockpit;
        if (c != null && handle.nodeName != null && c.hasFrame(handle.nodeName)) {
            c.bringToFront(handle.nodeName);   // jump / escalation → raise the node window
            return;
        }
        if (handle.content == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindow tw = toolWindow();
                if (tw == null) return;
                tw.getContentManager().setSelectedContent(handle.content);
                tw.show(null);
            } catch (Exception e) {
                LOG.warn("[Workflow] focus tab failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void openReport(String reportPath) {
        if (reportPath == null || reportPath.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Path p = Paths.get(reportPath);
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p);
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true);
                } else {
                    LOG.warn("[Workflow] completion report not found: " + reportPath);
                }
            } catch (Exception e) {
                LOG.warn("[Workflow] openReport failed: " + e.getMessage());
            }
        });
    }

    private ToolWindow toolWindow() {
        return ToolWindowManager.getInstance(project)
                .getToolWindow(ClaudeSDKToolWindow.TOOL_WINDOW_ID);
    }

    /**
     * Build the node's main-AI seed JSON ({@code {model, reasoningEffort}}) from the
     * supervisor's RESOLVED model + reasoning (node override folded in, else the agent
     * default). Returns {@code null} when neither is known (main AI then keeps the
     * user's global selection). The model keeps its {@code [1m]} suffix so the webview
     * can derive the 1M toggle — no separate longContext field needed.
     */
    private static String buildNodeMainAiConfig(String resolvedModel, String resolvedReasoning) {
        com.google.gson.JsonObject cfg = new com.google.gson.JsonObject();
        boolean any = false;
        if (resolvedModel != null && !resolvedModel.isEmpty()) {
            cfg.addProperty("model", resolvedModel);
            any = true;
        }
        if (resolvedReasoning != null && !resolvedReasoning.isEmpty()) {
            cfg.addProperty("reasoningEffort", resolvedReasoning);
            any = true;
        }
        return any ? cfg.toString() : null;
    }

    /**
     * Prefer the node name verbatim; if a tab with that display name already
     * exists (e.g. a manual {@code AI*} tab or a re-run), append {@code " (n)"}
     * until unique. Avoids clobbering existing tabs (§8.5).
     */
    private static String uniqueTabName(String base, ContentManager cm) {
        String candidate = (base == null || base.trim().isEmpty()) ? "node" : base.trim();
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Content c : cm.getContents()) {
            if (c.getDisplayName() != null) existing.add(c.getDisplayName());
        }
        if (!existing.contains(candidate)) return candidate;
        for (int i = 2; i < 1000; i++) {
            String next = candidate + " (" + i + ")";
            if (!existing.contains(next)) return next;
        }
        return candidate + " (" + java.util.UUID.randomUUID().toString().substring(0, 4) + ")";
    }
}
