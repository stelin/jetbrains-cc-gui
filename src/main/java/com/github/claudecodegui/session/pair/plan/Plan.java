package com.github.claudecodegui.session.pair.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class Plan {

    public enum PlanState {
        INIT,
        ACTIVE,
        WAITING,
        DONE,
        ABORTED
    }

    public enum ActiveSubState {
        EXECUTING,
        PENDING_DISCHARGE,
        PENDING_DECISION
    }

    public String id;
    public String pairId;
    public volatile PlanState state;
    public volatile ActiveSubState subState;
    public List<PlanStep> steps = new ArrayList<>();
    public volatile int currentStepIndex;
    public long createdAt;
    public volatile long lastTransitionAt;
    public Map<String, Object> metadata = new LinkedHashMap<>();

    public Plan() {
        /* gson */
    }

    public static Plan create(String pairId, List<PlanStep> steps, Map<String, Object> metadata) {
        Plan p = new Plan();
        long now = System.currentTimeMillis();
        p.id = "plan_" + pairId + "_" + now + "_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        p.pairId = pairId;
        p.state = PlanState.INIT;
        p.subState = null;
        p.steps = steps != null ? new ArrayList<>(steps) : new ArrayList<>();
        p.currentStepIndex = 0;
        p.createdAt = now;
        p.lastTransitionAt = now;
        p.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        return p;
    }

    public PlanStep getCurrentStep() {
        if (steps == null || currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    public boolean isTerminal() {
        return state == PlanState.DONE || state == PlanState.ABORTED;
    }

    public boolean allStepsDone() {
        if (steps == null || steps.isEmpty()) return false;
        for (PlanStep s : steps) {
            if (s.status != PlanStep.StepStatus.DONE && s.status != PlanStep.StepStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }
}
