package com.github.claudecodegui.session.pair.workflow;

import java.util.List;

/**
 * Mutable per-node runtime state held inside a {@link WorkflowExecution}.
 *
 * <p>The non-{@code transient} fields are exactly what travels over
 * {@code window.onWorkflowExecutionUpdate} (mirrors the front-end
 * {@code NodeRuntime}). The two {@code transient} fields are engine-internal
 * plumbing fed into downstream nodes' plans (§13) and MUST NOT leak into the
 * wire snapshot — Gson skips {@code transient} fields, and the front-end model
 * has no equivalent. See {@code docs/workflow/coding-plan.md} §5.
 *
 * <p>Threading: all writes happen on the {@code wf-scheduler} thread (§15).
 */
public class NodeRuntime {

    public NodeStatus status = NodeStatus.PENDING;

    public String pairId;
    public String windowId;
    public String completionReportPath;
    public String escalationReason;

    /** Files this node created/modified (from the report tool); feeds下游. Not serialized. */
    public transient List<String> changedFiles;

    /** Completion/blocked说明 (from the report tool); feeds下游. Not serialized. */
    public transient String summary;

    public NodeRuntime() {
        /* gson */
    }
}
