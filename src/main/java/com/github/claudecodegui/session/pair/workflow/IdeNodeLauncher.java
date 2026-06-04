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

    public IdeNodeLauncher(Project project) {
        this.project = project;
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
            PairSessionManager.StartPairParams params = new PairSessionManager.StartPairParams(
                    null, node.supervisorId, planPath == null ? null : planPath.toString(),
                    node.model, node.longContext, node.reasoning, win.getWindowId());
            if (win.getChatWindowDelegate() == null
                    || win.getChatWindowDelegate().getPairHandler() == null) {
                sink.failed("节点窗口未就绪，无法启动监督者");
                return;
            }
            PairSession pair = win.getChatWindowDelegate().getPairHandler().startPairWired(
                    params, new com.github.claudecodegui.session.pair.protocol.PairBudget());
            handle.pair = pair;
            handle.pairDir = pair.getPairDir();
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
