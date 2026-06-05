package com.github.claudecodegui.session.pair.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * A single workflow node: one conversation tab = one Pair (1 supervisor + 1 main
 * AI). The node name doubles as the tab name and is unique within the workflow.
 *
 * <p>Gson-serializable mirror of the front-end {@code WorkflowNode}
 * ({@code webview/.../WorkflowOrchestration/types.ts}). Field names must stay in
 * sync with the TypeScript model. See {@code docs/workflow/coding-plan.md} §5.
 */
public class WorkflowNode {

    /** = tab name; unique within the workflow. */
    public String name;

    /** Reuses {@code SupervisorAgent.id} from {@code supervisor-agents.json}. */
    public String supervisorId;

    /** Inline task markdown. Used unless {@link #planPath} is set. */
    public String plan;

    /** Optional: reference a {@code .md} file on disk instead of the inline plan. */
    public String planPath;

    /** Optional model override → {@code StartPairParams.modelOverride}. */
    public String model;

    /** Optional 1M-context toggle → {@code StartPairParams.longContextOverride}. */
    public Boolean longContext;

    /** Optional reasoning tier (bare id) → {@code StartPairParams.reasoningOverride}. */
    public String reasoning;

    /** Names of upstream nodes this node depends on; defines the串/并行 edges. */
    public List<String> dependsOn = new ArrayList<>();

    /**
     * Execution timing (D25 — list-status-and-node-scheduling-plan §3.1):
     * {@code "none"} (or null) = start immediately when deps are done;
     * {@code "relative"} = wait {@link #delayMinutes} after the last dep completes;
     * {@code "absolute"} = start at {@link #scheduledAt}.
     */
    public String delayMode;

    /** Relative mode: minutes to wait after upstream completes, clamped to [0, 300] (5h). */
    public Integer delayMinutes;

    /** Absolute mode: target start instant (epoch ms; picked in local time, stored as epoch). */
    public Long scheduledAt;

    /** Manual canvas position;透传 through the engine, never interpreted. */
    public Double posX;
    public Double posY;

    public WorkflowNode() {
        /* gson */
    }

    /**
     * Null-safe accessor for {@link #dependsOn} — gson may leave it null when the
     * field is absent from the JSON payload.
     */
    public List<String> dependsOnSafe() {
        return dependsOn == null ? java.util.Collections.emptyList() : dependsOn;
    }
}
