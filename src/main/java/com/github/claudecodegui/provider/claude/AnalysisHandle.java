package com.github.claudecodegui.provider.claude;

/**
 * Lightweight handle for a running bug analysis scratch session (design §3/§8).
 *
 * <p>Stored in {@code ClaudeSDKBridge.activeAnalyses} so that
 * {@code cancelBugAnalysis(projectId)} can set {@code canceled = true}
 * and the collector can short-circuit on first poll.
 */
public final class AnalysisHandle {

    public final String projectId;
    public final String scratchEpoch;

    /** Set to {@code true} by {@code cancelBugAnalysis}; read by {@link BugAnalysisCollector}. */
    public volatile boolean canceled;

    public AnalysisHandle(String projectId, String scratchEpoch) {
        this.projectId = projectId;
        this.scratchEpoch = scratchEpoch;
    }
}
