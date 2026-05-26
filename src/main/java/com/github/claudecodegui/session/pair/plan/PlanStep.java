package com.github.claudecodegui.session.pair.plan;

import java.util.ArrayList;
import java.util.List;

public class PlanStep {

    public enum StepOwner { SUPERVISOR, MAIN_AI }

    public enum StepStatus { TODO, IN_PROGRESS, DONE, BLOCKED, SKIPPED }

    public String id;
    public int index;
    public String title;
    public volatile StepOwner owner;
    public volatile StepStatus status;
    public List<String> contractIds = new ArrayList<>();
    public long createdAt;
    public volatile long completedAt;
    public volatile int attempts;
    public volatile String lastError;
    public List<String> filesChanged = new ArrayList<>();

    public PlanStep() {
        /* gson */
    }

    public static PlanStep create(String planId, int index, String title, StepOwner owner) {
        PlanStep s = new PlanStep();
        s.id = "step_" + planId + "_" + index;
        s.index = index;
        s.title = title;
        s.owner = owner;
        s.status = StepStatus.TODO;
        s.createdAt = System.currentTimeMillis();
        return s;
    }
}
