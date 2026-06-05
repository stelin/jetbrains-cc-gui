package com.github.claudecodegui.session.pair.workflow;

import java.util.List;

/**
 * Mutable per-node runtime state held inside a {@link WorkflowExecution}.
 *
 * <p>All fields travel over {@code window.onWorkflowExecutionUpdate} (mirrors the
 * front-end {@code NodeRuntime}). {@link #changedFiles} / {@link #summary} feed
 * downstream nodes' plans (§13); they used to be {@code transient}, but to keep
 * the downstream context complete after an IDE restart they are now persisted to
 * {@code execution.json} too (DN11 — see
 * {@code docs/workflow/resume-and-redispatch-plan.md} §2.4). They only carry data
 * on DONE nodes, so the extra wire bytes are negligible and the front-end simply
 * ignores the unknown fields.
 *
 * <p>Threading: all writes happen on the {@code wf-scheduler} thread (§15).
 */
public class NodeRuntime {

    public NodeStatus status = NodeStatus.PENDING;

    public String pairId;
    public String windowId;
    public String completionReportPath;
    public String escalationReason;

    /**
     * When {@link #status} is {@link NodeStatus#SCHEDULED}, the absolute instant
     * (epoch ms) this node is due to start (D26). Stored absolute so it survives
     * restart and re-arms correctly on resume. Null otherwise.
     */
    public Long scheduledStartAt;

    /** Files this node created/modified (from the report tool); feeds下游. */
    public List<String> changedFiles;

    /** Completion/blocked说明 (from the report tool); feeds下游. */
    public String summary;

    public NodeRuntime() {
        /* gson */
    }
}
