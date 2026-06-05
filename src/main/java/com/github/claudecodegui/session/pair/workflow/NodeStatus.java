package com.github.claudecodegui.session.pair.workflow;

/**
 * Per-node lifecycle state inside a running workflow execution.
 *
 * <p>Mirrors the front-end {@code NodeStatus} union in
 * {@code webview/src/components/WorkflowOrchestration/types.ts}. Gson serializes
 * each constant to its {@link #name()} (e.g. {@code "PENDING"}), which matches
 * the TypeScript string-literal type exactly — do not rename without updating
 * the webview.
 *
 * <p>See {@code docs/workflow/coding-plan.md} §5.
 */
public enum NodeStatus {
    /** Dependencies not yet satisfied. */
    PENDING,
    /**
     * Dependencies satisfied, but the node has a delay / scheduled start time that
     * is still in the future (D25 — list-status-and-node-scheduling-plan §3). Holds
     * no concurrency permit; a timer flips it to {@link #READY} when due. Mirrored
     * in the front-end {@code NodeStatus} union.
     */
    SCHEDULED,
    /** Deps satisfied, queued waiting for a concurrency slot. */
    READY,
    /** Tab created + pair started. */
    RUNNING,
    /** Escalated / watchdog-stuck → needs a human. */
    WAITING_HUMAN,
    /** Supervisor emitted {@code complete_workflow_node(done)} / all steps done. */
    DONE,
    /** Workflow aborted while this node was still live. */
    ABORTED
}
