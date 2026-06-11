package com.github.claudecodegui.session.pair.plan;

import com.github.claudecodegui.session.pair.l2.L2State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bidirectional conversion between the runtime {@link Plan} POJO (held by
 * {@link PlanStateMachine}) and the persisted shape {@link L2State.PersistedPlan}.
 *
 * <p>The persisted shape uses strings for enum-valued fields so adding new
 * enum values doesn't break old serializations. Conversion is lenient on
 * read: unknown enum strings default to a safe value and are logged by the
 * caller.
 */
public final class PlanPersistence {

    private PlanPersistence() { /* no instances */ }

    public static L2State.PersistedPlan toPersisted(Plan plan) {
        if (plan == null) return null;
        L2State.PersistedPlan p = new L2State.PersistedPlan();
        p.id = plan.id;
        p.state = plan.state == null ? null : plan.state.name();
        p.subState = plan.subState == null ? null : plan.subState.name();
        p.currentStepIndex = plan.currentStepIndex;
        p.createdAt = plan.createdAt;
        p.lastTransitionAt = plan.lastTransitionAt;
        p.steps = new ArrayList<>();
        if (plan.steps != null) {
            for (PlanStep step : plan.steps) {
                p.steps.add(stepToPersisted(step));
            }
        }
        p.metadata = plan.metadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(plan.metadata);
        return p;
    }

    public static Plan fromPersisted(L2State.PersistedPlan persisted, String pairId) {
        if (persisted == null) return null;
        Plan p = new Plan();
        p.id = persisted.id;
        p.pairId = pairId;
        p.state = parsePlanState(persisted.state);
        p.subState = parseSubState(persisted.subState);
        p.currentStepIndex = persisted.currentStepIndex;
        p.createdAt = persisted.createdAt;
        p.lastTransitionAt = persisted.lastTransitionAt;
        p.steps = new ArrayList<>();
        if (persisted.steps != null) {
            for (L2State.PersistedPlanStep ps : persisted.steps) {
                p.steps.add(stepFromPersisted(ps));
            }
        }
        p.metadata = persisted.metadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(persisted.metadata);
        return p;
    }

    private static L2State.PersistedPlanStep stepToPersisted(PlanStep step) {
        L2State.PersistedPlanStep ps = new L2State.PersistedPlanStep();
        ps.id = step.id;
        ps.index = step.index;
        ps.title = step.title;
        ps.owner = step.owner == null ? null : step.owner.name();
        ps.status = step.status == null ? null : step.status.name();
        ps.contractIds = step.contractIds == null
                ? new ArrayList<>() : new ArrayList<>(step.contractIds);
        ps.createdAt = step.createdAt;
        ps.completedAt = step.completedAt;
        ps.attempts = step.attempts;
        ps.lastError = step.lastError;
        ps.filesChanged = step.filesChanged == null
                ? new ArrayList<>() : new ArrayList<>(step.filesChanged);
        ps.acceptanceCriteria = step.acceptanceCriteria == null
                ? new ArrayList<>() : new ArrayList<>(step.acceptanceCriteria);
        ps.reportPath = step.reportPath;
        return ps;
    }

    private static PlanStep stepFromPersisted(L2State.PersistedPlanStep ps) {
        PlanStep s = new PlanStep();
        s.id = ps.id;
        s.index = ps.index;
        s.title = ps.title;
        s.owner = parseStepOwner(ps.owner);
        s.status = parseStepStatus(ps.status);
        s.contractIds = ps.contractIds == null
                ? new ArrayList<>() : new ArrayList<>(ps.contractIds);
        s.createdAt = ps.createdAt;
        s.completedAt = ps.completedAt;
        s.attempts = ps.attempts;
        s.lastError = ps.lastError;
        s.filesChanged = ps.filesChanged == null
                ? new ArrayList<>() : new ArrayList<>(ps.filesChanged);
        s.acceptanceCriteria = ps.acceptanceCriteria == null
                ? new ArrayList<>() : new ArrayList<>(ps.acceptanceCriteria);
        s.reportPath = ps.reportPath;
        return s;
    }

    private static Plan.PlanState parsePlanState(String s) {
        if (s == null) return null;
        try { return Plan.PlanState.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static Plan.ActiveSubState parseSubState(String s) {
        if (s == null) return null;
        try { return Plan.ActiveSubState.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static PlanStep.StepOwner parseStepOwner(String s) {
        if (s == null) return null;
        try { return PlanStep.StepOwner.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static PlanStep.StepStatus parseStepStatus(String s) {
        if (s == null) return PlanStep.StepStatus.TODO;
        try { return PlanStep.StepStatus.valueOf(s); } catch (IllegalArgumentException e) { return PlanStep.StepStatus.TODO; }
    }
}
