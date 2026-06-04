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
