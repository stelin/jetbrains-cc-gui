package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairSessionManager;
import com.github.claudecodegui.session.registry.SessionManifest;
import com.github.claudecodegui.session.registry.SessionRegistry;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Session-kind refactor (S5): manifest-driven restore of a <b>supervised</b>
 * session.
 *
 * <p>Replaces the old fragile resume (see memory {@code supervisor-history-restore}:
 * a resume-gated {@code .jsonl} replay plus in-memory ids that got lost). The
 * container manifest is the durable source of truth, so restore is deterministic:
 * <ol>
 *   <li>read the manifest by {@code containerId};</li>
 *   <li>stamp {@code containerId} back onto this tab's SessionState (so the S3
 *       pair_* routing + {@code handleSessionId} backfill resolve the container);</li>
 *   <li>resume the main-AI leg from {@code mainSessionId} — reuses the same
 *       {@code loadHistorySession} path the normal restore + workflow-node SR10
 *       launcher use; null-safe (a session that never ran a main turn just skips
 *       it and restores only the supervisor leg);</li>
 *   <li>start the supervisor pair with {@code resumeSupervisorSessionId =
 *       supervisorSessionId} so the daemon resumes the supervisor transcript and
 *       L2 rebuilds from {@code l2Dir(containerId)}; {@code startPairWired} stages
 *       the pending-replay id so {@code pair_webview_ready} re-renders the pane.</li>
 * </ol>
 *
 * <p>The two window-bound steps (main resume + pair start) go through
 * {@link HistoryHandler.SupervisedRestoreBridge}, implemented by
 * {@code ChatWindowDelegate} (which owns the window + this tab's PairHandler).
 */
class SupervisedRestoreService {

    private static final Logger LOG = Logger.getInstance(SupervisedRestoreService.class);

    private final HandlerContext context;

    SupervisedRestoreService(HandlerContext context) {
        this.context = context;
    }

    void restore(String containerId, HistoryHandler.SupervisedRestoreBridge bridge) {
        if (containerId == null || containerId.isEmpty()) return;
        if (bridge == null) {
            LOG.warn("[HistoryHandler] supervised restore: no restore bridge wired for " + containerId);
            return;
        }
        // Off the IPC thread — startPairWired does a ~20s daemon handshake.
        CompletableFuture.runAsync(() -> {
            try {
                if (context.getProject() == null) return;
                SessionManifest m = SessionRegistry.getInstance(context.getProject()).get(containerId);
                if (m == null) {
                    LOG.warn("[HistoryHandler] supervised restore: no manifest for container " + containerId);
                    return;
                }

                // Stamp the container onto this tab's state so pair_* routing (S3)
                // and handleSessionId's manifest backfill resolve the right container.
                if (context.getSession() != null && context.getSession().getState() != null) {
                    context.getSession().getState().setContainerId(containerId);
                }

                // Main-AI leg: resume the prior transcript. No-op when mainSessionId
                // is null (session created but never ran a main turn) — only the
                // supervisor leg is restored in that case.
                bridge.resumeMainSession(m.mainSessionId, containerId);

                // Supervisor leg: resumeSupervisorSessionId is threaded through
                // startPair → SupervisorBridge.startWithHandoff (no workflow gating),
                // and startPairWired stages the pending-replay id from it. L2 rebuilds
                // from l2Dir(containerId). mainSessionId stays null on the params — the
                // main leg lives on the window's resumed ClaudeSession, not the pair.
                PairSessionManager.StartPairParams params = new PairSessionManager.StartPairParams(
                        null, m.agentId, null,
                        null, null, null,
                        context.getWindowId(), m.supervisorSessionId, containerId);
                PairSession pair = bridge.startSupervisorPair(params);

                // Defensive (startPairWired already stages this when
                // resumeSupervisorSessionId is non-null): ensure the supervisor pane
                // replays its history once the webview signals ready.
                if (pair != null && m.supervisorSessionId != null && !m.supervisorSessionId.isEmpty()) {
                    pair.setPendingHistoryReplaySessionId(m.supervisorSessionId);
                }
                LOG.info("[HistoryHandler] supervised restore done: container=" + containerId
                        + " agent=" + m.agentId + " mainSid=" + m.mainSessionId
                        + " supSid=" + m.supervisorSessionId);
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] supervised restore failed for " + containerId + ": "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }
        });
    }
}
