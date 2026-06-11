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
    /**
     * Acceptance criteria authored at plan time (by the supervisor's emit_plan
     * turn). The supervisor verifies real artifacts against these on its review
     * turn — navigation-only trust (a main-AI self-report never substitutes for
     * checking the file). Empty for legacy snapshots / synthetic fallback steps.
     */
    public List<String> acceptanceCriteria = new ArrayList<>();
    /**
     * Path to the most-recent main-AI turn report spilled to disk for this step,
     * or null when the report was small enough to inline. Set by the
     * {@code [TURN_REPORT]} handler; lets the resume preamble point the supervisor
     * at per-step evidence. Daemon-managed (not in the project tree).
     */
    public volatile String reportPath;

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

    /** Overload carrying acceptance criteria — used by the emit_plan path. */
    public static PlanStep create(String planId, int index, String title, StepOwner owner,
                                  List<String> acceptanceCriteria) {
        PlanStep s = create(planId, index, title, owner);
        if (acceptanceCriteria != null) {
            s.acceptanceCriteria = new ArrayList<>(acceptanceCriteria);
        }
        return s;
    }
}
