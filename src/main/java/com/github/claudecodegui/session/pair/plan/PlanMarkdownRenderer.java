package com.github.claudecodegui.session.pair.plan;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Renders the authoritative {@link Plan} into a human/LLM-readable {@code plan.md}
 * under {@code ~/.codemoss/pairs/<pairId>/plan.md} (NOT in the project tree, so it
 * never pollutes the repo / git diff). This is the file the supervisor Reads on
 * resume to see "what's the plan and where am I". Always a projection of the live
 * Plan — re-rendered by {@link PlanProjectionListener} on every transition, so it
 * never drifts from the single source of truth.
 */
public final class PlanMarkdownRenderer {

    private static final Logger LOG = Logger.getInstance(PlanMarkdownRenderer.class);

    private PlanMarkdownRenderer() { /* no instances */ }

    /** {@code ~/.codemoss/pairs/<pairId>/plan.md} */
    public static Path planPath(String pairId) {
        // PlatformUtils.getHomeDirectory() (not System.getProperty) — IDEA may
        // override user.home; checkstyle rule NoUserHomeProperty enforces this.
        return Paths.get(com.github.claudecodegui.util.PlatformUtils.getHomeDirectory(),
                ".codemoss", "pairs", pairId, "plan.md");
    }

    public static void render(String pairId, Plan plan) {
        if (pairId == null || plan == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Plan ").append(plan.id == null ? "" : plan.id).append("\n\n");
            sb.append("state: ").append(plan.state)
                    .append(plan.subState == null ? "" : (" / " + plan.subState)).append("\n");
            sb.append("progress: step ").append(plan.currentStepIndex + 1).append("/")
                    .append(plan.steps == null ? 0 : plan.steps.size()).append("\n\n");

            if (plan.steps != null) {
                for (PlanStep st : plan.steps) {
                    sb.append(symbolFor(st.status)).append(" ").append(st.index).append(". ")
                            .append(st.title == null ? "" : st.title)
                            .append("  [").append(st.owner == null ? "MAIN_AI" : st.owner.name()).append("]\n");
                    if (st.acceptanceCriteria != null && !st.acceptanceCriteria.isEmpty()) {
                        for (String c : st.acceptanceCriteria) {
                            sb.append("   - 验收: ").append(c).append("\n");
                        }
                    }
                    if (st.reportPath != null && !st.reportPath.isEmpty()) {
                        sb.append("   - report: ").append(st.reportPath).append("\n");
                    }
                    if (st.filesChanged != null && !st.filesChanged.isEmpty()) {
                        sb.append("   - files: ").append(String.join(", ", st.filesChanged)).append("\n");
                    }
                }
            }

            Path p = planPath(pairId);
            Files.createDirectories(p.getParent());
            Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.warn("[PlanMarkdownRenderer] " + pairId + " render failed: " + e.getMessage());
        }
    }

    private static String symbolFor(PlanStep.StepStatus s) {
        if (s == null) return "⬜"; // ⬜
        switch (s) {
            case DONE: return "✅";        // ✅
            case IN_PROGRESS: return "⏳"; // ⏳
            case BLOCKED: return "⛔";     // ⛔
            case SKIPPED: return "⏭";     // ⏭
            default: return "⬜";          // ⬜ (TODO)
        }
    }
}
