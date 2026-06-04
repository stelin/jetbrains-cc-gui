package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.intellij.ui.content.Content;

import java.nio.file.Path;

/**
 * Engine-internal handle to the live IDE resources backing one running node:
 * its tab {@link Content}, its {@link ClaudeChatWindow}, and (once started) its
 * {@link PairSession}. Held in {@code SupervisorWorkflowManager.handles} keyed by
 * node name so {@code jump}/{@code openReport}/{@code abort} can reach the tab.
 *
 * <p>Never serialized — this is purely in-memory plumbing (the wire model is
 * {@link NodeRuntime}). See {@code docs/workflow/coding-plan.md} §8.1.
 *
 * <p>Threading: created on the EDT (tab build); {@link #pair}/{@link #pairDir}
 * are assigned later from the background pool after {@code startPair} — hence
 * {@code volatile}. The map itself is a {@code ConcurrentHashMap}.
 */
public final class NodeHandle {

    /** Tab content. May be null in headless tests. */
    public final Content content;

    /** Owning chat window. May be null in headless tests. */
    public final ClaudeChatWindow win;

    /** Per-tab window id (stable). Mirrored into {@link NodeRuntime#windowId}. */
    public volatile String windowId;

    /**
     * The workflow node name this handle backs. Set by {@code IdeNodeLauncher} so
     * the cockpit can resolve a node's floating window from its handle
     * ({@code focus} → {@code WorkflowCockpit.bringToFront(nodeName)}). Null in
     * headless tests / the legacy tab path that doesn't need it.
     */
    public volatile String nodeName;

    /** Started pair; null until the background {@code startPair} completes. */
    public volatile PairSession pair;

    /** Pair working directory; source of the COMPLETION_REPORT.md path. */
    public volatile Path pairDir;

    public NodeHandle(Content content, ClaudeChatWindow win) {
        this.content = content;
        this.win = win;
        this.windowId = win != null ? win.getWindowId() : null;
    }

    /** Test/headless constructor: no IDE objects, explicit ids. */
    public NodeHandle(String windowId, Path pairDir) {
        this.content = null;
        this.win = null;
        this.windowId = windowId;
        this.pairDir = pairDir;
    }
}
