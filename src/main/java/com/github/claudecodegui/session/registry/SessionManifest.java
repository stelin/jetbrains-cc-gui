package com.github.claudecodegui.session.registry;

import java.util.ArrayList;
import java.util.List;

/**
 * Session-kind refactor: the persistent "ledger" / join-table entry for one
 * supervised or workflow session container. Serialized as
 * {@code ~/.codemoss/sessions/<projectHash>/<containerId>/manifest.json} by
 * {@link SessionRegistry} (the single owner — nothing else writes it).
 *
 * <p>It is the only place that knows the three otherwise-independent stores are
 * one session: the main-AI transcript ({@code .claude/projects/<mainSessionId>.jsonl}),
 * the supervisor daemon session ({@code supervisorSessionId}) and the
 * coordination state ({@code <containerId>/l2/}). {@code mainSessionId}/{@code pairId}/
 * {@code supervisorSessionId} are internal pointers; {@code containerId} is the
 * stable identity everything routes by.
 */
public final class SessionManifest {

    /** Primary key — stable container identity (UUID, or wfId for workflows). */
    public String containerId;
    public SessionKind kind;
    public String title;
    public String projectHash;
    public long createdAt;
    public long lastActiveAt;
    /** "active" | "closed". */
    public String status;

    // ── supervised leg (workflow child nodes use this group too) ──

    /** Current main-AI session id pointer (latest run). */
    public String mainSessionId;
    /**
     * Every main-AI session id this container has EVER owned. The normal-history
     * exclusion filter uses the full set (not just the current pointer) so a
     * re-run's orphaned prior session never leaks into the normal tab.
     */
    public List<String> mainSessionIds = new ArrayList<>();
    /** Internal active-pair id; no longer a directory/storage key. */
    public String pairId;
    public String supervisorSessionId;
    public String agentId;
    public Integer supervisorGeneration;

    // ── workflow ──

    /** Non-null ⇒ this is a workflow child node → hidden from the supervised tab. */
    public String parentContainerId;
    /** For {@code kind=WORKFLOW}: equals containerId (== wfId). */
    public String workflowId;
    /** For {@code kind=WORKFLOW}: child node container ids. */
    public List<String> childContainerIds = new ArrayList<>();
}
