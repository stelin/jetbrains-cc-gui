package com.github.claudecodegui.session.pair.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted workflow definition: a set of nodes + dependency edges (a DAG).
 *
 * <p>Stored as {@code definition.json} under
 * {@code ~/.codemoss/workflows/<projectHash>/<id>/} (see {@link WorkflowStore}).
 * Gson-serializable mirror of the front-end {@code WorkflowDefinition}; the
 * {@link #maxConcurrency} field is the Java-side authority for the per-workflow
 * concurrency (front end adds it in a later phase — see
 * {@code docs/workflow/coding-plan.md} §5 / §14).
 */
public class WorkflowDefinition {

    public String id;
    public String name;

    /**
     * Per-workflow concurrency, clamped to {@code [1, ceiling()]} at run time
     * (DN4). Null means "use the global default" (2).
     */
    public Integer maxConcurrency;

    public List<WorkflowNode> nodes = new ArrayList<>();

    public Long updatedAt;

    public WorkflowDefinition() {
        /* gson */
    }

    /** Null-safe accessor for {@link #nodes}. */
    public List<WorkflowNode> nodesSafe() {
        return nodes == null ? java.util.Collections.emptyList() : nodes;
    }
}
