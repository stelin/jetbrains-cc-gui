package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.bridge.SupervisorBridge;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate state for one running Supervisor Pair.
 *
 * <p>A Pair always has exactly one main session ({@link #mainSessionId}) and
 * one Supervisor agent in the current iteration (single-select picker).
 * The model is structured to allow future N-supervisor extension without
 * breaking changes — see {@link #getSupervisorIds()}.
 */
public class PairSession {

    private final String pairId;
    private final String mainSessionId;
    private final String agentId;
    private final String agentName;
    private final Path pairDir;
    private final Path planSnapshotPath;
    private final SupervisorBridge supervisorBridge;
    private final ProgressManager progressManager;
    private final long startedAt = System.currentTimeMillis();

    // Snapshot of the parameters that started the daemon-side supervisor runtime.
    // EventBus uses these to lazily re-run supervisor.start after a daemon restart
    // (in remote mode the Node process's in-memory runtime Map is lost on crash).
    // `model` and `reasoningEffort` are mutable so the right-pane composer can
    // change them mid-session; the new values are picked up on the next daemon
    // lazy-restart (immediate hot-swap is not supported by the supervisor channel
    // yet — would need a new supervisor.setModel daemon protocol).
    private final String agentDescription;
    private final String planContent;
    private final String projectSpec;
    private volatile String model;
    private volatile String reasoningEffort;

    private volatile EventBus eventBus;       // wired by PairSessionManager
    private volatile ActionRouter actionRouter;
    private volatile boolean disposed = false;

    public PairSession(
            String pairId,
            String mainSessionId,
            String agentId,
            String agentName,
            Path pairDir,
            Path planSnapshotPath,
            SupervisorBridge supervisorBridge,
            ProgressManager progressManager,
            String agentDescription,
            String planContent,
            String projectSpec,
            String model
    ) {
        this.pairId = pairId;
        this.mainSessionId = mainSessionId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.pairDir = pairDir;
        this.planSnapshotPath = planSnapshotPath;
        this.supervisorBridge = supervisorBridge;
        this.progressManager = progressManager;
        this.agentDescription = agentDescription;
        this.planContent = planContent;
        this.projectSpec = projectSpec;
        this.model = model;
    }

    public String getPairId() { return pairId; }
    public String getMainSessionId() { return mainSessionId; }
    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public Path getPairDir() { return pairDir; }
    public Path getPlanSnapshotPath() { return planSnapshotPath; }
    public SupervisorBridge getSupervisorBridge() { return supervisorBridge; }
    public ProgressManager getProgressManager() { return progressManager; }
    public long getStartedAt() { return startedAt; }

    public EventBus getEventBus() { return eventBus; }
    public void setEventBus(EventBus eventBus) { this.eventBus = eventBus; }

    public ActionRouter getActionRouter() { return actionRouter; }
    public void setActionRouter(ActionRouter router) { this.actionRouter = router; }

    public boolean isDisposed() { return disposed; }
    public void markDisposed() { this.disposed = true; }

    public String getAgentDescription() { return agentDescription; }
    public String getPlanContent() { return planContent; }
    public String getProjectSpec() { return projectSpec; }
    public String getModel() { return model; }

    /**
     * Replace the model used on next supervisor restart. Pass null/empty to
     * clear the override and fall back to the agent's configured default on
     * the daemon side. Does NOT trigger an immediate restart — the new value
     * takes effect when {@code EventBus.restartSupervisor()} fires next
     * (typically on daemon crash recovery or when the user toggles the
     * supervisor off/on).
     */
    public void setModel(String model) {
        this.model = (model != null && !model.isEmpty()) ? model : null;
    }

    public String getReasoningEffort() { return reasoningEffort; }

    /**
     * Replace the reasoning-effort hint used on next supervisor restart.
     * Like {@link #setModel(String)}, this is not hot-swapped — the daemon
     * supervisor channel currently has no setReasoning protocol.
     */
    public void setReasoningEffort(String effort) {
        this.reasoningEffort = (effort != null && !effort.isEmpty()) ? effort : null;
    }

    /**
     * Current Supervisor agent ids (always size 1 in current iteration).
     * Returned as a list to leave room for multi-supervisor mode later.
     */
    public List<String> getSupervisorIds() {
        return Collections.singletonList(agentId);
    }
}
