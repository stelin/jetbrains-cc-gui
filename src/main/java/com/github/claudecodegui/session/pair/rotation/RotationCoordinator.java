package com.github.claudecodegui.session.pair.rotation;

import com.github.claudecodegui.bridge.SupervisorBridge;
import com.github.claudecodegui.session.pair.HealthState;
import com.github.claudecodegui.session.pair.PairCoordinator;
import com.github.claudecodegui.session.pair.PairSession;
import com.github.claudecodegui.session.pair.PairStatusPusher;
import com.github.claudecodegui.session.pair.PairStatusSnapshot;
import com.github.claudecodegui.session.pair.SupervisorMonitor;
import com.github.claudecodegui.session.pair.l2.L2Schema;
import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.github.claudecodegui.session.pair.l2.L2Validator;
import com.github.claudecodegui.session.pair.prompt.HandoffProducerPromptBuilder;
import com.github.claudecodegui.session.pair.prompt.SuccessorPromptBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 4 (2026-05-24): executes the 14-step atomic rotation flow described in
 * {@code docs/plans/2026-05-23-supervisor-monitor-rotation-implementation.md}
 * §10.
 *
 * <p>Entered by {@link RotationDecider} after it observes a
 * {@link PairCoordinator#takeRotationRequest()} and successfully takes the
 * write lock. Returns a {@link RotationResult} describing the outcome.
 *
 * <p>Crash-safety: failures before swap (steps 5–9) leave the old supervisor
 * intact; the L2 cooldown check at step 0 will throttle retry attempts.
 * Failure between step 9 (new start) and step 11 (swap) — i.e. new
 * supervisor alive but pair not yet pointing at it — is the most dangerous
 * window; we minimise it by doing only the local swap between.
 */
public class RotationCoordinator {

    private static final Logger LOG = Logger.getInstance(RotationCoordinator.class);

    /** Soft trigger throttle — at most one rotation per this window. */
    public static final long ROTATION_COOLDOWN_MS = 5 * 60_000L;

    public static final long HANDOFF_PRODUCER_TIMEOUT_SEC = 30L;
    public static final long NEW_START_TIMEOUT_SEC = 30L;
    public static final long OLD_STOP_TIMEOUT_SEC = 10L;

    private final L2Store l2Store;

    public RotationCoordinator(L2Store l2Store) {
        this.l2Store = l2Store;
    }

    /**
     * Run the rotation. Caller is expected to already hold the pair's
     * coordinator write lock (entered by {@link RotationDecider#run}).
     *
     * @param pair          the pair to rotate
     * @param triggerReason e.g. "ratio>=0.85", "compactCount>=3", "manual",
     *                      "unhealthy_2_ticks"
     */
    public RotationResult execute(PairSession pair, String triggerReason) {
        long t0 = System.currentTimeMillis();
        String pairId = pair.getPairId();
        // Session-kind refactor (S3): L2 durable state is keyed by the persistent
        // container id (pairId for legacy/workflow pairs). pairId stays for logs +
        // daemon supervisorId derivation.
        String l2Key = pair.getL2Key();
        SupervisorBridge bridge = pair.getSupervisorBridge();
        PairStatusPusher statusPusher = pair.getStatusPusher();
        SupervisorMonitor monitor = pair.getSupervisorMonitor();

        // ── 0. Cooldown check ────────────────────────────────────────────
        L2State l2 = l2Store.read(l2Key);
        if (l2.lastRotation != null) {
            long sinceLast = System.currentTimeMillis() - l2.lastRotation.at;
            if (sinceLast < ROTATION_COOLDOWN_MS) {
                long remaining = ROTATION_COOLDOWN_MS - sinceLast;
                LOG.info("[Rotation] " + pairId + " cooldown — " + remaining + "ms remaining");
                return RotationResult.cooldown(remaining);
            }
        }
        if (pair.isDisposed()) {
            return RotationResult.aborted("pair disposed");
        }

        // Steps 1–3 are guaranteed by the writeLock contract — the decider
        // entered it before calling us; no in-flight tick can hold the
        // readLock once we got in.

        // ── 4. Snapshot L2 (cache already primed, just deepCopy via read) ─
        L2State snapshot = l2.deepCopy();

        // ── 5. Old session produces handoff doc (with 1 retry) ───────────
        boolean degraded = false;
        L2State producerL2 = null;
        HealthState health = monitor != null ? monitor.getHealth() : HealthState.HEALTHY;

        if (health == HealthState.UNHEALTHY) {
            // Don't even try producer — supervisor is hung, prompt round-trip will time out.
            LOG.info("[Rotation] " + pairId + " skipping producer (UNHEALTHY) — using L2 fallback");
            degraded = true;
        } else {
            try {
                producerL2 = produceHandoffWithRetry(bridge, triggerReason);
            } catch (Exception e) {
                LOG.warn("[Rotation] " + pairId + " producer failed, fallback to L2: " + e.getMessage());
                degraded = true;
                if (statusPusher != null) {
                    statusPusher.pushAlert(PairStatusSnapshot.Alert.Severity.WARN,
                            "rotation: handoff producer failed, using L2 snapshot");
                }
            }
        }

        // ── 6. Validate + merge into a fresh next-gen state ──────────────
        L2State merged;
        try {
            merged = L2Merger.merge(snapshot, producerL2);
            L2Validator.validate(merged);
        } catch (Exception e) {
            // If even the merged result fails validation, surrender — old supervisor stays.
            LOG.error("[Rotation] " + pairId + " merge/validate failed: " + e.getMessage());
            return RotationResult.failed("merge/validate: " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }

        // ── 7. Archive knownConstraints if at the generation reset threshold ──
        boolean wasReset = false;
        if (merged.generation >= L2Schema.GENERATION_RESET_THRESHOLD) {
            try {
                l2Store.archiveKnownConstraints(l2Key);
                // Reload fresh snapshot post-archive (clears knownConstraints + persists).
                L2State postArchive = l2Store.read(l2Key);
                merged.knownConstraints = postArchive.knownConstraints;
                merged.generation = 0;
                wasReset = true;
                LOG.info("[Rotation] " + pairId + " generation reset (was >= "
                        + L2Schema.GENERATION_RESET_THRESHOLD + ")");
            } catch (Exception e) {
                LOG.warn("[Rotation] " + pairId + " archive failed (continuing): " + e.getMessage());
            }
        }

        int newGeneration = merged.generation + 1;
        if (wasReset) newGeneration = 1; // reset → next gen is 1

        // ── 8. Render successor prompt ───────────────────────────────────
        long handoffAgeSec = 0; // producer was just-now; snapshot is moments old
        long snapshotAgeMin = snapshot.lastUpdated > 0
                ? (System.currentTimeMillis() - snapshot.lastUpdated) / 60_000L : 0;
        String successorPrompt = SuccessorPromptBuilder.renderHandoff(
                pairId, newGeneration, triggerReason, handoffAgeSec,
                merged, degraded, snapshotAgeMin);

        // ── 9. Start new daemon supervisor ───────────────────────────────
        String oldSupervisorId = bridge.getSupervisorId();
        String newSupervisorId = generateSupervisorId(pair.getAgentId(), newGeneration);
        try {
            Boolean started = bridge.startWithHandoff(
                    pair.getAgentName(),
                    pair.getAgentDescription(),
                    pair.getPlanContent(),
                    pair.getProjectSpec(),
                    pair.getModel(),
                    pair.getAutoCompactThreshold(),
                    pair.getReasoningEffort(),
                    newSupervisorId,
                    successorPrompt,
                    newGeneration,
                    pair.isMcpAccess()
            ).get(NEW_START_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(started)) {
                return RotationResult.failed("new start returned false",
                        System.currentTimeMillis() - t0);
            }
        } catch (Exception e) {
            LOG.error("[Rotation] " + pairId + " new start failed: " + e.getMessage());
            return RotationResult.failed("new start: " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }

        // ── 10. Stop old daemon supervisor (best-effort) ─────────────────
        try {
            bridge.stopById(oldSupervisorId).get(OLD_STOP_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Don't fail the rotation — daemon GC will eventually clean it up.
            LOG.warn("[Rotation] " + pairId + " old stop failed (will GC): " + e.getMessage());
        }

        // ── 11. Atomic swap supervisorId on the pair ─────────────────────
        pair.swapSupervisorId(newSupervisorId);

        // Contract State Machine v3 (2026-05-25): cancel SUPERVISOR-bound open
        // contracts. The recipient changed — DECISION_REQUESTs that the old
        // supervisor was supposed to handle would be ambiguous to the new
        // supervisor; safer to drop them. MAIN_AI contracts stay intact
        // (main AI is still working on whatever was assigned to it).
        com.github.claudecodegui.session.pair.contract.ContractRegistry contractRegistry =
                pair.getContractRegistry();
        if (contractRegistry != null) {
            int cancelled = 0;
            for (com.github.claudecodegui.session.pair.contract.Contract c
                    : contractRegistry.getOpenContracts()) {
                if (c.assignedTo == com.github.claudecodegui.session.pair.contract.ContractAssignee.SUPERVISOR) {
                    contractRegistry.cancel(c.id, "supervisor rotation: " + triggerReason);
                    cancelled++;
                }
            }
            if (cancelled > 0) {
                LOG.info("[Rotation] " + pairId + " cancelled " + cancelled
                        + " SUPERVISOR-bound contracts on rotation");
            }
        }

        // ── 12. Update L2: generation++, lastRotation, rotationCount++ ──
        final String handoffSource = degraded
                ? (health == HealthState.UNHEALTHY ? "l2_unhealthy" : "l2_fallback")
                : "producer";
        final int finalGen = newGeneration;
        l2Store.update(l2Key, s -> {
            s.generation = finalGen;
            s.rotationCount = s.rotationCount + 1;
            L2State.RotationInfo ri = new L2State.RotationInfo();
            ri.from = oldSupervisorId;
            ri.to = newSupervisorId;
            ri.at = System.currentTimeMillis();
            ri.reason = triggerReason;
            ri.handoffSource = handoffSource;
            s.lastRotation = ri;
            // Adopt merged content fields so the on-disk file matches what
            // the successor prompt was built from.
            s.anchoredFacts = merged.anchoredFacts;
            s.planProgress = merged.planProgress;
            s.fileState = merged.fileState;
            s.recentDecisions = merged.recentDecisions;
            s.knownConstraints = merged.knownConstraints;
            return s;
        });

        // ── 13. snapshot .bak — last-known-good for next generation ──────
        l2Store.snapshotBackup(l2Key);

        // ── 14. Notify status pusher + queue banner for next monitor tick ─
        if (statusPusher != null) {
            try {
                statusPusher.pushRotationBoundary(newGeneration, triggerReason, handoffSource);
            } catch (Exception e) {
                LOG.warn("[Rotation] " + pairId + " status push failed: " + e.getMessage());
            }
        }
        if (monitor != null) {
            monitor.setPendingGenerationBanner(buildBannerText(newGeneration, triggerReason, handoffSource));
        }

        long dur = System.currentTimeMillis() - t0;
        LOG.info("[Rotation] " + pairId + " SUCCESS gen=" + newGeneration
                + " new=" + newSupervisorId + " source=" + handoffSource
                + " duration=" + dur + "ms");
        return RotationResult.success(newSupervisorId, dur);
    }

    /**
     * Try produceHandoff; on validation failure, try once more with the
     * error appended to the prompt. Each attempt has its own 30s timeout.
     *
     * @return parsed L2State, or throws on irrecoverable failure
     */
    private L2State produceHandoffWithRetry(SupervisorBridge bridge, String triggerReason) throws Exception {
        String initialPrompt = HandoffProducerPromptBuilder.renderInitial(triggerReason);
        JsonObject doc;
        try {
            doc = bridge.produceHandoff(initialPrompt)
                    .get(HANDOFF_PRODUCER_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new Exception("producer timeout (initial)");
        }
        L2State parsed = parseProducerOutput(doc);
        Exception firstErr = validateProducerOutput(parsed);
        if (firstErr == null) return parsed;

        // Retry once
        String retryPrompt = HandoffProducerPromptBuilder.renderRetry(firstErr.getMessage());
        try {
            doc = bridge.produceHandoff(retryPrompt)
                    .get(HANDOFF_PRODUCER_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new Exception("producer timeout (retry)");
        }
        parsed = parseProducerOutput(doc);
        Exception retryErr = validateProducerOutput(parsed);
        if (retryErr == null) return parsed;
        throw new Exception("producer validation failed twice: " + retryErr.getMessage());
    }

    /**
     * Daemon emits {@code [HANDOFF_DOC] { ..., "json": "<raw JSON the supervisor produced>" }}.
     * Java parses {@code json} into a candidate L2State.
     */
    private L2State parseProducerOutput(JsonObject envelope) {
        if (envelope == null) throw new IllegalStateException("producer emitted no doc");
        if (!envelope.has("json")) throw new IllegalStateException("producer doc missing 'json' field");
        String raw = envelope.get("json").getAsString();
        try {
            return L2State.fromJson(raw);
        } catch (Exception e) {
            throw new IllegalStateException("producer JSON parse failed: " + e.getMessage());
        }
    }

    private Exception validateProducerOutput(L2State candidate) {
        try {
            L2Validator.validate(candidate);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static String generateSupervisorId(String agentId, int newGeneration) {
        String agentTag = (agentId == null || agentId.isEmpty()) ? "agent" : agentId;
        return agentTag + "_gen" + newGeneration + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String buildBannerText(int newGeneration, String reason, String handoffSource) {
        return "[NEW_GENERATION_BANNER] gen=" + newGeneration
                + " reason=" + (reason == null ? "?" : reason)
                + " source=" + (handoffSource == null ? "?" : handoffSource);
    }
}
