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
            ProgressManager progressManager
    ) {
        this.pairId = pairId;
        this.mainSessionId = mainSessionId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.pairDir = pairDir;
        this.planSnapshotPath = planSnapshotPath;
        this.supervisorBridge = supervisorBridge;
        this.progressManager = progressManager;
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

    /**
     * Current Supervisor agent ids (always size 1 in current iteration).
     * Returned as a list to leave room for multi-supervisor mode later.
     */
    public List<String> getSupervisorIds() {
        return Collections.singletonList(agentId);
    }
}
