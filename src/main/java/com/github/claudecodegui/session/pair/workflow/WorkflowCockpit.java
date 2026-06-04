package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.ui.detached.WorkflowNodeFrame;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The floating node windows for one workflow run — the "cockpit" (see
 * {@code docs/workflow/cockpit-plan.md}).
 *
 * <h3>Lazy, concurrency-bounded layout</h3>
 * Windows are NOT all opened upfront. The screen is split into {@code slotCount}
 * tiles (= the run's concurrency, e.g. 2 → left/right halves). A node's window
 * is created only when the node actually starts ({@link #attach}); it takes a
 * free slot. When the node finishes (DONE/ABORTED) its slot frees so the next
 * scheduled node opens there — but the finished window <b>stays open</b> so its
 * history stays viewable. Successive windows on the same slot cascade slightly
 * so an older one peeks out from behind the current one.
 *
 * <p>Slot occupancy mirrors the engine's concurrency semaphore: a slot is busy
 * while its node is RUNNING or WAITING_HUMAN (both hold a semaphore permit), and
 * frees on DONE/ABORTED. So a running node always finds a free slot.
 *
 * <p>Threading: structural changes marshal onto the EDT and all slot state is
 * touched there; the engine (wf-scheduler thread) never blocks on the EDT.
 */
public final class WorkflowCockpit {

    private static final Logger LOG = Logger.getInstance(WorkflowCockpit.class);

    /** Cascade step / cap for stacked windows reusing one slot (history peek). */
    private static final int CASCADE_STEP = 28;
    private static final int CASCADE_MAX = 6;

    private final Project project;
    private final Consumer<String> onNodeWindowClosed;
    private final int slotCount;

    private final Map<String, WorkflowNodeFrame> frames = new ConcurrentHashMap<>();
    private final Map<String, Integer> nodeSlot = new ConcurrentHashMap<>();
    // EDT-only state:
    private List<WorkflowWindowLayout.Slot> slots;
    private boolean[] slotBusy;
    private int[] slotUse;

    public WorkflowCockpit(Project project, int slotCount, Consumer<String> onNodeWindowClosed) {
        this.project = project;
        this.slotCount = Math.max(1, slotCount);
        this.onNodeWindowClosed = onNodeWindowClosed != null ? onNodeWindowClosed : id -> { };
    }

    /** Compute the slot grid for {@code slotCount} on the target screen (EDT). */
    public void open() {
        invokeOnEdt(() -> {
            slots = WorkflowWindowLayout.slotsFor(project, slotCount);
            slotBusy = new boolean[slots.size()];
            slotUse = new int[slots.size()];
            LOG.info("[WorkflowCockpit] ready: " + slots.size() + " tile slot(s)");
        });
    }

    /** Whether a window currently exists for the node (running or finished). */
    public boolean hasFrame(String nodeName) {
        return frames.containsKey(nodeName);
    }

    /**
     * Open a floating window for a node that's starting, host its live chat
     * window, and mark it RUNNING. Takes a free slot (cascaded if that slot has
     * older finished windows). EDT-marshalled.
     */
    public void attach(String nodeName, String agentLabel, ClaudeChatWindow win) {
        invokeOnEdt(() -> {
            if (slots == null || slots.isEmpty()) {
                LOG.warn("[WorkflowCockpit] attach before open(); node=" + nodeName);
                return;
            }
            int si = firstFreeSlot();
            slotBusy[si] = true;
            nodeSlot.put(nodeName, si);
            int cascade = Math.min(slotUse[si], CASCADE_MAX) * CASCADE_STEP;
            slotUse[si] = slotUse[si] + 1;
            WorkflowWindowLayout.Slot s = slots.get(si);

            WorkflowNodeFrame frame = new WorkflowNodeFrame(project, nodeName, agentLabel);
            frame.setOnUserClose(() -> handleUserClose(nodeName));
            frame.setBounds(s.x() + cascade, s.y() + cascade,
                    Math.max(320, s.w() - cascade), Math.max(240, s.h() - cascade));
            frame.attach(win);
            frame.setStatus(NodeStatus.RUNNING);
            frames.put(nodeName, frame);
            frame.setVisible(true);   // visible but non-focus-stealing
            LOG.info("[WorkflowCockpit] opened window for node " + nodeName + " at slot " + si);
        });
    }

    /** Raise a node's window (jump / escalation). No-op if no window. */
    public void bringToFront(String nodeName) {
        WorkflowNodeFrame frame = frames.get(nodeName);
        if (frame != null) frame.bringToFront();
    }

    /**
     * Reflect a status transition. On DONE/ABORTED the slot frees (so the next
     * scheduled node can open there) but the window is kept for history.
     */
    public void setStatus(String nodeName, NodeStatus status) {
        WorkflowNodeFrame frame = frames.get(nodeName);
        if (frame != null) frame.setStatus(status);
        if (status == NodeStatus.DONE || status == NodeStatus.ABORTED) {
            freeSlot(nodeName);
        }
    }

    /** Dispose every node window (manual close / teardown / project close). */
    public void closeAll() {
        invokeOnEdt(() -> {
            for (WorkflowNodeFrame f : frames.values()) {
                try { f.dispose(); } catch (Exception e) {
                    LOG.warn("[WorkflowCockpit] dispose frame failed: " + e.getMessage());
                }
            }
            frames.clear();
            nodeSlot.clear();
            if (slotBusy != null) java.util.Arrays.fill(slotBusy, false);
            LOG.info("[WorkflowCockpit] closed all node windows");
        });
    }

    private void freeSlot(String nodeName) {
        invokeOnEdt(() -> {
            Integer si = nodeSlot.remove(nodeName);
            if (si != null && slotBusy != null && si < slotBusy.length) slotBusy[si] = false;
        });
    }

    /**
     * Pick a free slot, preferring the least-used one so windows spread across
     * slots (e.g. a linear chain fills left, then right, then cascades) instead
     * of always stacking on slot 0. Falls back to the least-used slot overall if
     * none is free (shouldn't happen: running nodes ≤ slotCount). EDT.
     */
    private int firstFreeSlot() {
        int best = -1;
        for (int i = 0; i < slotBusy.length; i++) {
            if (slotBusy[i]) continue;
            if (best == -1 || slotUse[i] < slotUse[best]) best = i;
        }
        if (best != -1) return best;
        best = 0;
        for (int i = 1; i < slotUse.length; i++) {
            if (slotUse[i] < slotUse[best]) best = i;
        }
        return best;
    }

    private void handleUserClose(String nodeName) {
        WorkflowNodeFrame frame = frames.remove(nodeName);
        freeSlot(nodeName);
        if (frame == null) return;
        String windowId = frame.getChatWindow() != null ? frame.getChatWindow().getWindowId() : null;
        try {
            if (windowId != null) onNodeWindowClosed.accept(windowId);
        } catch (Exception e) {
            LOG.warn("[WorkflowCockpit] onNodeWindowClosed failed: " + e.getMessage());
        }
        invokeOnEdt(frame::dispose);
    }

    private static void invokeOnEdt(Runnable r) {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null) { r.run(); return; }
        if (app.isDispatchThread()) r.run(); else app.invokeLater(r);
    }
}
