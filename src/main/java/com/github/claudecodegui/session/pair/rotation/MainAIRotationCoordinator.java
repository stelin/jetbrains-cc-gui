package com.github.claudecodegui.session.pair.rotation;

import com.github.claudecodegui.provider.claude.MainAIBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairStatusPusher;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.github.claudecodegui.session.pair.prompt.MainAIHandoffPromptBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 6b (2026-05-24): orchestrates a main-AI session rotation. Mirrors
 * {@link RotationCoordinator} (supervisor) but the daemon-side swap is simpler:
 *
 * <p>The main AI runtime is daemon-owned per ClaudeSession. To "swap" the
 * underlying session we don't need an explicit start RPC — we just dispose
 * the existing runtime via epoch rotation (and stage the successor prompt
 * on SessionState). The NEXT user message naturally creates a fresh
 * runtime with the new sessionId. SessionSendService consumes the staged
 * append once via {@code state.consumePendingSystemPromptAppend()}, threads
 * it through {@code claude.send}'s {@code systemPromptAppend} param, and the
 * daemon-side {@code persistent-query-service.buildSystemPromptAppend}
 * concatenates it with the IDE/agentPrompt append before feeding both to
 * the SDK's {@code options.systemPrompt.append}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Cooldown check — based on {@code mainAI.lastRotation.at}.</li>
 *   <li>Lazy-swap guard — defer if the user is mid-turn (busy or thinking)
 *       so we don't drop the in-flight reply.</li>
 *   <li>Read pair's L2 snapshot.</li>
 *   <li>Run {@code mainAi.produceHandoff} on the current main session
 *       (30s timeout). Validation failure → fallback path.</li>
 *   <li>If producer failed, synthesise a fallback handoff carrying the
 *       L2-captured {@code recentUserMessages} so the successor sees verbatim
 *       user text even when no producer JSON survived.</li>
 *   <li>Render successor prompt via {@link MainAIHandoffPromptBuilder}.</li>
 *   <li>Call {@link ClaudeSession#swapInnerSession} — atomic local swap +
 *       stage prompt. Old runtime will be disposed by the daemon's epoch
 *       check on the next send.</li>
 *   <li>Update L2 (lastRotation + rotationCount); snapshot .bak.</li>
 *   <li>Push rotation boundary alert via {@link PairStatusPusher}.</li>
 * </ol>
 */
public class MainAIRotationCoordinator {

    private static final Logger LOG = Logger.getInstance(MainAIRotationCoordinator.class);

    /** Soft trigger throttle — same window as supervisor rotation. */
    public static final long ROTATION_COOLDOWN_MS = 5 * 60_000L;
    public static final long HANDOFF_PRODUCER_TIMEOUT_SEC = 30L;

    private final L2Store l2Store;

    public MainAIRotationCoordinator(L2Store l2Store) {
        this.l2Store = l2Store;
    }

    /**
     * Run the rotation synchronously on the caller thread.
     *
     * @param pair          target pair
     * @param session       its bound ClaudeSession (must be non-null)
     * @param mainAIBridge  RPC façade for {@code mainAi.*} commands
     * @param triggerReason e.g. "ratio>=0.85", "compactCount>=3", "stall_recovery"
     */
    public RotationResult execute(PairSession pair,
                                  ClaudeSession session,
                                  MainAIBridge mainAIBridge,
                                  String triggerReason) {
        long t0 = System.currentTimeMillis();
        if (pair == null || session == null) {
            return RotationResult.aborted("pair or session is null");
        }
        if (pair.isDisposed()) return RotationResult.aborted("pair disposed");

        String pairId = pair.getPairId();
        String oldSid = session.getSessionId();
        if (oldSid == null || oldSid.isEmpty()) {
            return RotationResult.aborted("main AI session id not yet assigned (no turns run)");
        }

        // ── 0. Cooldown check (5min since last completed rotation) ───────
        L2State l2 = l2Store.read(pairId);
        L2State.MainAIState mainAI = l2.mainAI;
        if (mainAI != null && mainAI.lastRotation != null) {
            long sinceLast = System.currentTimeMillis() - mainAI.lastRotation.at;
            if (sinceLast < ROTATION_COOLDOWN_MS) {
                long remaining = ROTATION_COOLDOWN_MS - sinceLast;
                LOG.info("[MainAIRotation] " + pairId + " cooldown — " + remaining + "ms remaining");
                return RotationResult.cooldown(remaining);
            }
        }

        // ── 1. Lazy-swap guard ───────────────────────────────────────────
        // The user may be mid-turn; the swap itself is lazy (next send picks
        // up the new epoch / staged prompt), but we should not flip session
        // state from underneath an active SDK turn — that risks dropping the
        // streaming reply. Defer; the decider's next tick will retry, and the
        // cooldown is not started since we never rotated.
        if (session.getState().isBusy()) {
            LOG.info("[MainAIRotation] " + pairId + " deferred — session busy (turn in flight)");
            return RotationResult.aborted("session busy (will retry on next decider tick)");
        }

        // ── 4. Snapshot L2 + 5. Produce handoff doc ──────────────────────
        L2State snapshot = l2.deepCopy();
        String handoffJson = null;
        boolean degraded = false;
        try {
            String producerPrompt = MainAIHandoffPromptBuilder.renderProducerInitial(triggerReason);
            JsonObject envelope = mainAIBridge.produceHandoff(oldSid, producerPrompt)
                    .get(HANDOFF_PRODUCER_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (envelope != null && envelope.has("valid") && envelope.get("valid").getAsBoolean()
                    && envelope.has("json") && !envelope.get("json").isJsonNull()) {
                handoffJson = envelope.get("json").getAsString();
            } else {
                degraded = true;
                LOG.warn("[MainAIRotation] " + pairId + " producer returned invalid handoff; using L2 fallback");
            }
        } catch (TimeoutException te) {
            degraded = true;
            LOG.warn("[MainAIRotation] " + pairId + " producer timeout");
        } catch (Exception e) {
            degraded = true;
            LOG.warn("[MainAIRotation] " + pairId + " producer failed: " + e.getMessage());
        }
        if (handoffJson == null) {
            // Fallback: synthesise a handoff doc from L2's verbatim
            // recentUserMessages ring (captured every time the SDK echoed a
            // user message back; bounded to MAIN_AI_RECENT_USER_MAX = 10).
            // Empty ring → empty list (graceful).
            List<MainAIHandoffPromptBuilder.RecentUserMessage> recent =
                    new ArrayList<>();
            if (snapshot.mainAI != null && snapshot.mainAI.recentUserMessages != null) {
                for (L2State.RecentUserMessageEntry e : snapshot.mainAI.recentUserMessages) {
                    if (e == null || e.text == null) continue;
                    recent.add(new MainAIHandoffPromptBuilder.RecentUserMessage(e.ts, e.text));
                }
            }
            handoffJson = MainAIHandoffPromptBuilder.renderFallbackHandoffJson(
                    recent,
                    snapshot.anchoredFacts != null ? snapshot.anchoredFacts.currentStepTitle : null);
        }

        // ── 8. Render successor prompt ───────────────────────────────────
        int currentGen = (mainAI != null) ? mainAI.rotationCount : 0;
        int newGen = currentGen + 1;
        long snapshotAgeMin = snapshot.lastUpdated > 0
                ? (System.currentTimeMillis() - snapshot.lastUpdated) / 60_000L : 0;
        String successorPrompt = MainAIHandoffPromptBuilder.renderSuccessor(
                pairId, newGen, triggerReason,
                /* ageSeconds */ 0,
                snapshot, handoffJson, degraded, snapshotAgeMin);

        // ── 11. Atomic local swap on the session ────────────────────────
        String swappedOldSid = session.swapInnerSession(successorPrompt);
        LOG.info("[MainAIRotation] " + pairId + " session swapped — oldSid=" + swappedOldSid
                + " (will dispose on next send via epoch rotation)");

        // ── 12. Update L2: lastRotation + rotationCount++ ────────────────
        final String handoffSource = degraded ? "l2_fallback" : "producer";
        final long now = System.currentTimeMillis();
        l2Store.update(pairId, s -> {
            if (s.mainAI == null) s.mainAI = new L2State.MainAIState();
            // Cleared so the next [SESSION_ID] from the daemon is treated as new
            // (ClaudeMessageHandler.handleSessionId will publish the new id).
            s.mainAI.sessionId = null;
            L2State.RotationInfo ri = new L2State.RotationInfo();
            ri.from = swappedOldSid;
            ri.to = null; // unknown until daemon emits new SESSION_ID
            ri.at = now;
            ri.reason = triggerReason;
            ri.handoffSource = handoffSource;
            s.mainAI.lastRotation = ri;
            s.mainAI.rotationCount += 1;
            return s;
        });

        // ── 13. snapshot .bak ────────────────────────────────────────────
        l2Store.snapshotBackup(pairId);

        // ── 14. Notify status pusher ─────────────────────────────────────
        PairStatusPusher pusher = pair.getStatusPusher();
        if (pusher != null) {
            try {
                pusher.pushMainAIRotationBoundary(triggerReason, handoffSource);
            } catch (Exception e) {
                LOG.warn("[MainAIRotation] status push failed: " + e.getMessage());
            }
        }

        long dur = System.currentTimeMillis() - t0;
        LOG.info("[MainAIRotation] " + pairId + " SUCCESS newGen=" + newGen
                + " source=" + handoffSource + " duration=" + dur + "ms");
        return RotationResult.success("(pending)", dur);
    }
}
