package com.github.claudecodegui.session.pair.plan;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.l2.L2Store;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;

/**
 * Projects the authoritative {@link Plan} (owned by {@link PlanStateMachine})
 * into the L2 {@code anchoredFacts} + {@code planProgress} fields and re-renders
 * {@code plan.md}.
 *
 * <p>Plan generation (2026-06-10): the Plan is the SINGLE source of truth for
 * plan structure + step status. {@code anchoredFacts}/{@code planProgress} used
 * to be maintained independently by the supervisor via {@code update_state} —
 * that split let the two drift. Now they are a DERIVED view written through here
 * on every transition; the supervisor no longer writes them. Consumers (rotation
 * handoff, UI, the supervisor's resume Read of plan.md) read this projection.
 *
 * <p>{@link PlanStateMachine#restore} does NOT fire listeners (it's a load, not a
 * transition), so callers that hydrate a plan on IDE restart must invoke
 * {@link #project(Plan)} once manually.
 */
public final class PlanProjectionListener implements PlanStateListener {

    private static final Logger LOG = Logger.getInstance(PlanProjectionListener.class);

    private final L2Store l2Store;
    private final String l2Key;
    private final String pairId;

    public PlanProjectionListener(L2Store l2Store, String l2Key, String pairId) {
        this.l2Store = l2Store;
        this.l2Key = l2Key;
        this.pairId = pairId;
    }

    @Override
    public void onStateChanged(Plan.PlanState oldState, Plan.ActiveSubState oldSub, Plan now) {
        project(now);
    }

    /**
     * Project {@code plan} into L2 anchoredFacts/planProgress and render plan.md.
     * Best-effort and null-safe — a projection failure never breaks the caller.
     */
    public void project(Plan plan) {
        if (plan == null) return;
        try {
            l2Store.update(l2Key, s -> {
                if (s.anchoredFacts == null) s.anchoredFacts = new L2State.AnchoredFacts();
                int total = plan.steps == null ? 0 : plan.steps.size();
                s.anchoredFacts.totalSteps = total;
                // 1-based for human display (matches event-summarizer's "step N/M").
                s.anchoredFacts.currentStep = plan.currentStepIndex + 1;
                PlanStep cur = plan.getCurrentStep();
                s.anchoredFacts.currentStepTitle = cur == null ? null : cur.title;

                // Rebuild planProgress from the plan — this is a projection, so we
                // overwrite rather than merge (the Plan is authoritative).
                s.planProgress = new ArrayList<>();
                if (plan.steps != null) {
                    for (PlanStep st : plan.steps) {
                        L2State.PlanProgressEntry e = new L2State.PlanProgressEntry();
                        e.step = st.index;
                        e.status = st.status == null ? "todo" : st.status.name().toLowerCase();
                        e.attempts = st.attempts;
                        e.lastError = st.lastError;
                        e.completedAt = st.completedAt > 0 ? st.completedAt : null;
                        e.filesChanged = st.filesChanged == null
                                ? new ArrayList<>() : new ArrayList<>(st.filesChanged);
                        s.planProgress.add(e);
                    }
                }
                return s;
            });
            PlanMarkdownRenderer.render(pairId, plan);
        } catch (Exception e) {
            LOG.warn("[PlanProjection] " + pairId + " projection failed: " + e.getMessage());
        }
    }
}
