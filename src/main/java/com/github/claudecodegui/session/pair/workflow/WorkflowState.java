package com.github.claudecodegui.session.pair.workflow;

/**
 * Overall lifecycle state of a workflow execution.
 *
 * <p>Mirrors the front-end {@code WorkflowState} union in
 * {@code webview/src/components/WorkflowOrchestration/types.ts}; Gson serializes
 * each constant to its {@link #name()}. See {@code docs/workflow/coding-plan.md} §5.
 */
public enum WorkflowState {
    /** Definition being edited; no execution running. */
    EDITING,
    /** At least one node is live; the engine is actively scheduling. */
    RUNNING,
    /** Every node reached {@link NodeStatus#DONE}. */
    COMPLETED,
    /** Execution aborted by the user (or startup recovery). */
    ABORTED
}
