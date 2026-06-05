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
    /**
     * Loaded from {@code execution.json} after an IDE restart that interrupted a
     * RUNNING run (D20). The execution holds the single-workflow lock and its DAG
     * is fully restored, but the engine is NOT scheduling: no cockpit windows are
     * open and no node is pumped until the user clicks 「恢复运行」 (→ {@code resumeWorkflow}).
     * See {@code docs/workflow/resume-and-redispatch-plan.md} §2.1 / §3.2.
     */
    PAUSED,
    /** Every node reached {@link NodeStatus#DONE}. */
    COMPLETED,
    /** Execution aborted by the user (or startup recovery). */
    ABORTED
}
