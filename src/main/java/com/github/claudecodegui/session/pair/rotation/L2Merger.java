package com.github.claudecodegui.session.pair.rotation;

import com.github.claudecodegui.session.pair.l2.L2Schema;
import com.github.claudecodegui.session.pair.l2.L2State;

/**
 * Phase 4 (2026-05-24): merges a producer-generated {@link L2State} (the
 * handoff doc the old supervisor emits) into the pre-rotation snapshot from
 * L2Store. Returns a fresh {@link L2State} suitable for the new generation.
 *
 * <p>Merge policy:
 * <ul>
 *   <li><b>pairId / createdAt</b> — always from snapshot (immutable).</li>
 *   <li><b>schemaVersion</b> — always {@link L2Schema#CURRENT_VERSION}.</li>
 *   <li><b>generation / rotationCount / metrics</b> — kept from snapshot; the
 *       coordinator increments them as the rotation completes.</li>
 *   <li><b>anchoredFacts / planProgress / fileState / recentDecisions /
 *       knownConstraints</b> — taken from producer if non-empty, else snapshot.
 *       This preserves the supervisor's fresh judgment while letting it omit
 *       fields it doesn't want to touch.</li>
 *   <li><b>compactionHistory</b> — kept from snapshot (durable counter).</li>
 *   <li><b>lastRotation</b> — overwritten by the coordinator after swap.</li>
 * </ul>
 */
public final class L2Merger {

    private L2Merger() { /* no instances */ }

    /**
     * Produce the next-generation state from the snapshot + producer output.
     * Neither argument is mutated.
     *
     * @param snapshot       the pre-rotation L2 (always non-null)
     * @param producer       parsed handoff doc from the old supervisor (may be null
     *                       in unhealthy / fallback paths; merger then returns
     *                       a copy of snapshot)
     * @return a deep-copied, merged L2State
     */
    public static L2State merge(L2State snapshot, L2State producer) {
        if (snapshot == null) {
            throw new IllegalArgumentException("L2Merger: snapshot required");
        }
        L2State base = snapshot.deepCopy();
        if (producer == null) {
            // Fallback path — keep snapshot as-is.
            return base;
        }

        // anchoredFacts: shallow merge (producer wins per-field if non-null)
        if (producer.anchoredFacts != null) {
            if (base.anchoredFacts == null) base.anchoredFacts = new L2State.AnchoredFacts();
            if (producer.anchoredFacts.currentStep != null)
                base.anchoredFacts.currentStep = producer.anchoredFacts.currentStep;
            if (producer.anchoredFacts.totalSteps != null)
                base.anchoredFacts.totalSteps = producer.anchoredFacts.totalSteps;
            if (producer.anchoredFacts.currentStepTitle != null)
                base.anchoredFacts.currentStepTitle = producer.anchoredFacts.currentStepTitle;
            // blockedOn: explicit null = clear; producer doesn't distinguish — accept both
            base.anchoredFacts.blockedOn = producer.anchoredFacts.blockedOn;
            if (producer.anchoredFacts.lastVerifyCmd != null)
                base.anchoredFacts.lastVerifyCmd = producer.anchoredFacts.lastVerifyCmd;
            if (producer.anchoredFacts.lastVerifyResult != null)
                base.anchoredFacts.lastVerifyResult = producer.anchoredFacts.lastVerifyResult;
            if (producer.anchoredFacts.lastVerifyAt != null)
                base.anchoredFacts.lastVerifyAt = producer.anchoredFacts.lastVerifyAt;
        }

        // planProgress: producer wins when non-empty
        if (producer.planProgress != null && !producer.planProgress.isEmpty()) {
            base.planProgress.clear();
            base.planProgress.addAll(producer.planProgress);
        }

        // fileState: producer wins when non-empty (key set may shrink — producer is authoritative)
        if (producer.fileState != null && !producer.fileState.isEmpty()) {
            base.fileState.clear();
            base.fileState.putAll(producer.fileState);
        }

        // recentDecisions: producer wins (it knows what's "recent" by its lights)
        if (producer.recentDecisions != null && !producer.recentDecisions.isEmpty()) {
            base.recentDecisions.clear();
            base.recentDecisions.addAll(producer.recentDecisions);
        }

        // knownConstraints: union (producer should normally inherit, but de-dup just in case)
        if (producer.knownConstraints != null) {
            for (String c : producer.knownConstraints) {
                if (c != null && !c.isEmpty() && !base.knownConstraints.contains(c)) {
                    base.knownConstraints.add(c);
                }
            }
        }

        // Never accept producer's schemaVersion / pairId / createdAt overrides.
        base.schemaVersion = L2Schema.CURRENT_VERSION;
        // pairId / createdAt stayed from snapshot via deepCopy — verify.
        if (snapshot.pairId != null) base.pairId = snapshot.pairId;

        base.trimRings();
        return base;
    }
}
