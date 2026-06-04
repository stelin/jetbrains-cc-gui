package com.github.claudecodegui.session.pair.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full execution snapshot of one workflow run. Pushed verbatim to the webview
 * on every discrete transition (DN7) via {@code window.onWorkflowExecutionUpdate}
 * and persisted as {@code execution.json}.
 *
 * <p>Mirrors the front-end {@code WorkflowExecution}. The {@link #nodes} map is
 * keyed by {@link WorkflowNode#name} (Gson serializes it as a JSON object →
 * {@code Record<string, NodeRuntime>}). See {@code docs/workflow/coding-plan.md} §5.
 *
 * <p>Threading: only the {@code wf-scheduler} thread mutates this object (§15).
 */
public class WorkflowExecution {

    public String workflowId;
    public WorkflowState state = WorkflowState.EDITING;

    /** Effective concurrency cap actually in force (after clamping to ceiling). */
    public int concurrency;

    /** Keyed by node name. Insertion order preserved for stable UI rendering. */
    public Map<String, NodeRuntime> nodes = new LinkedHashMap<>();

    public WorkflowExecution() {
        /* gson */
    }
}
