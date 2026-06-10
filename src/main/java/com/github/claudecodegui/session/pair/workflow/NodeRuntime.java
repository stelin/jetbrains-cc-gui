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
    /**
     * Session-kind refactor (S6): the node's persistent SUPERVISED container id
     * (registered at workflow start with {@code parentContainerId = wfId}, so it
     * stays hidden behind the workflow container in the supervised history tab).
     * Distinct from {@link #pairId} — that is the internal active-pair id,
     * regenerated on each (re)dispatch; {@code containerId} is stable across
     * re-runs and is the node's L2 + pair_* routing key (PairSession.getL2Key()).
     */
    public String containerId;
    public String windowId;
    public String completionReportPath;
    public String escalationReason;

    /**
     * When {@link #status} is {@link NodeStatus#SCHEDULED}, the absolute instant
     * (epoch ms) this node is due to start (D26). Stored absolute so it survives
     * restart and re-arms correctly on resume. Null otherwise.
     */
    public Long scheduledStartAt;

    /**
     * Epoch ms when this node entered RUNNING (the genuine run start, re-stamped on
     * each fresh (re)dispatch). Null until the node first runs. Persisted + pushed
     * to the webview so the node card can show 开始时间 / 耗时.
     */
    public Long startedAt;

    /**
     * Epoch ms when this node reached DONE. Null while running / not yet done.
     * With {@link #startedAt} the card computes elapsed (单位:分).
     */
    public Long finishedAt;

    /** Files this node created/modified (from the report tool); feeds下游. */
    public List<String> changedFiles;

    /** Completion/blocked说明 (from the report tool); feeds下游. */
    public String summary;

    /**
     * Session resume (SR7, session-resume-plan.md): the main-AI and supervisor SDK
     * session ids captured while this node ran, persisted to {@code execution.json}
     * so a restart can resume both transcripts instead of starting fresh. Null on
     * old executions / nodes that never started. {@link #supervisorGeneration}
     * records the supervisor rotation generation the id belongs to (so resume
     * targets the right one). The front-end ignores these unknown fields.
     */
    public String mainSessionId;
    public String supervisorSessionId;
    public Integer supervisorGeneration;

    /**
     * Session resume display: the pairId this node's supervisor last ran under,
     * persisted so a restart-resume can locate the prior pair's L2 and carry its
     * coordinator-event strip into the resumed pair (the new pair has a different
     * pairId / fresh L2). Null on old executions / nodes that never started.
     */
    public String supervisorPairId;

    public NodeRuntime() {
        /* gson */
    }
}
