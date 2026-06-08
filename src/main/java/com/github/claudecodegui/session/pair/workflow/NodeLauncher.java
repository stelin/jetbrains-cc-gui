package com.github.claudecodegui.session.pair.workflow;

import java.nio.file.Path;

/**
 * Side-effecting boundary between the (single-threaded, IDE-free) scheduler core
 * in {@code SupervisorWorkflowManager} and the IDE world (build a tab on the EDT,
 * start a Pair on the background pool). Extracting it lets the scheduler state
 * machine be unit-tested with a fake launcher — see
 * {@code docs/workflow/coding-plan.md} §15 / §22.
 *
 * <p>Contract: {@link #launch} MUST NOT block the calling (scheduler) thread —
 * it kicks off the EDT/background work and returns immediately, reporting
 * progress back through the supplied {@link Sink} from whatever thread the work
 * lands on. The manager's {@link Sink} implementation re-marshals every callback
 * onto the scheduler thread, so launcher implementations need no locking.
 */
public interface NodeLauncher {

    /**
     * Create the node's tab and start its Pair.
     *
     * @param node     the node to launch
     * @param planPath assembled effective-plan markdown path (→ {@code StartPairParams.planPath})
     * @param sink     progress callback (tab created → pair started → or failed)
     */
    void launch(WorkflowNode node, Path planPath, Sink sink);

    /** Abort: stop the Pair + resources owned by {@code windowId}. No-op if unknown/null. */
    void stop(String windowId);

    /**
     * Session resume (SR4/SR10, session-resume-plan.md): stage the session ids to
     * resume on the NEXT {@link #launch} of {@code nodeName} — the supervisor's
     * prior transcript ({@code supervisorSessionId}), the main AI's
     * ({@code mainSessionId}), and the prior {@code pairId} whose persisted L2
     * coordinator-event strip should be carried into the resumed pair. Consumed-
     * and-cleared by that launch. Any arg may be null (resume only what's
     * available). Default no-op so test fakes and the legacy single-tab path
     * compile unchanged; only {@code IdeNodeLauncher} honours it.
     */
    default void setPendingResume(String nodeName, String supervisorSessionId,
                                  String mainSessionId, String priorPairId) { }

    /** Jump: focus the node's tab (EDT). No-op when the handle has no live tab. */
    void focus(NodeHandle handle);

    /** Open the node's completion report markdown in the editor (EDT). */
    void openReport(String reportPath);

    // ─── workflow cockpit (docs/workflow/cockpit-plan.md) ────────────────
    // Default no-ops so existing test fakes keep compiling unchanged; only the
    // production IdeNodeLauncher overrides them.

    /**
     * Prepare the cockpit with {@code slotCount} tile slots (= the run's
     * concurrency) at workflow start. Windows are opened lazily by {@link #launch}
     * as nodes start (≤ slotCount running at once); finished windows stay open
     * for history. A visible window realizes its JCEF webview so concurrent
     * nodes' injects drain. Default no-op = legacy single-tab behaviour.
     */
    default void openCockpit(int slotCount) { }

    /**
     * Reveal a node when its pair starts. Legacy tab mode focuses the tab so its
     * lazy webview mounts; cockpit mode is a no-op (the window is already
     * visible). Default no-op (used by headless tests — no reveal recorded).
     */
    default void revealOnStart(NodeHandle handle) { }

    /** Reflect a node status transition on its cockpit window (border + glyph). Default no-op. */
    default void setNodeStatus(String nodeName, NodeStatus status) { }

    /** Dispose all node windows (workflow teardown / project close). Default no-op. */
    default void closeCockpit() { }

    /**
     * Progress callbacks for a single {@link #launch}. Implemented by the manager;
     * every method re-marshals onto the {@code wf-scheduler} thread.
     */
    interface Sink {
        /** EDT finished building the tab. */
        void tabCreated(String windowId, NodeHandle handle);

        /** Background pool finished {@code startPair}. */
        void pairStarted(String pairId, Path pairDir);

        /** Tab build or {@code startPair} threw. */
        void failed(String reason);
    }
}
