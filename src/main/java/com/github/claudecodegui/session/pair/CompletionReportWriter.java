package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.github.claudecodegui.session.pair.protocol.BudgetStatus;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Renders L2State + budget snapshot into a human-readable Markdown report
 *  written to {@code .claude/pair/<pairId>/{COMPLETION,PAUSED,PARTIAL}_REPORT.md}.
 *  Format is plain text — supervisor / users / mutagen-synced plugin clients
 *  can all read it without any tooling. */
public final class CompletionReportWriter {

    private static final Logger LOG = Logger.getInstance(CompletionReportWriter.class);
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private CompletionReportWriter() { /* no instances */ }

    /** Write the right report kind based on outcome classification. Returns
     *  the path written, or null on failure. */
    public static Path writeCompletionReport(PairSession pair, L2State state) {
        if (pair == null || state == null) return null;
        CompletionDetector.Outcome outcome = CompletionDetector.classify(state, pair.isPaused());
        String filename;
        switch (outcome) {
            case FULL_SUCCESS:
            case MOSTLY_SUCCESS:
                filename = "COMPLETION_REPORT.md";
                break;
            case PARTIAL_SUCCESS:
                filename = "PARTIAL_REPORT.md";
                break;
            case PAUSED:
                filename = "PAUSED_REPORT.md";
                break;
            case IN_PROGRESS:
            default:
                LOG.debug("[CompletionReportWriter] skip — pair " + pair.getPairId() + " still in progress");
                return null;
        }
        Path target = pair.getPairDir().resolve(filename);
        try {
            Files.createDirectories(target.getParent());
            String body = renderMarkdown(pair, state, outcome);
            Files.write(target, body.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("[CompletionReportWriter] wrote " + target + " (outcome=" + outcome + ")");
            return target;
        } catch (IOException e) {
            LOG.warn("[CompletionReportWriter] write failed for " + target + ": " + e.getMessage());
            return null;
        }
    }

    /** Convenience for paused-by-budget path — writes PAUSED_REPORT.md with
     *  the budget snapshot inlined in the preamble. */
    public static Path writePausedReport(PairSession pair, L2State state, String reason) {
        if (pair == null) return null;
        Path target = pair.getPairDir().resolve("PAUSED_REPORT.md");
        try {
            Files.createDirectories(target.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Pair Paused\n\n");
            sb.append("- pairId: ").append(pair.getPairId()).append('\n');
            sb.append("- pausedAt: ").append(ISO.format(new Date())).append('\n');
            sb.append("- reason: ").append(reason == null ? "(unspecified)" : reason).append('\n');
            sb.append('\n');
            appendBudgetSection(sb, pair);
            if (state != null) {
                sb.append('\n');
                appendPlanProgress(sb, state);
                sb.append('\n');
                appendDecisions(sb, state);
            }
            Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("[CompletionReportWriter] wrote " + target + " (paused, reason=" + reason + ")");
            return target;
        } catch (IOException e) {
            LOG.warn("[CompletionReportWriter] paused-report write failed for " + target + ": " + e.getMessage());
            return null;
        }
    }

    // ---- internal ----

    private static String renderMarkdown(PairSession pair, L2State state, CompletionDetector.Outcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Pair Completion Report\n\n");
        sb.append("- pairId: ").append(pair.getPairId()).append('\n');
        sb.append("- outcome: ").append(outcome).append('\n');
        sb.append("- startedAt: ").append(ISO.format(new Date(pair.getStartedAt()))).append('\n');
        sb.append("- finishedAt: ").append(ISO.format(new Date())).append('\n');
        long durationMs = System.currentTimeMillis() - pair.getStartedAt();
        sb.append("- duration: ").append(formatDuration(durationMs)).append('\n');
        sb.append('\n');

        appendBudgetSection(sb, pair);
        sb.append('\n');
        appendPlanProgress(sb, state);
        sb.append('\n');
        appendDecisions(sb, state);
        return sb.toString();
    }

    private static void appendBudgetSection(StringBuilder sb, PairSession pair) {
        PairBudgetTracker tracker = pair.getBudgetTracker();
        if (tracker == null) return;
        sb.append("## Budget Usage\n");
        if (!tracker.hasAnyLimit()) {
            sb.append("- (no budget limits set; counters only)\n");
        }
        sb.append("- tokensUsed: ").append(tracker.getTokensUsed()).append('\n');
        sb.append("- stepsCompleted: ").append(tracker.getStepsCompleted()).append('\n');
        sb.append("- subagentCalls: ").append(tracker.getSubagentCalls()).append('\n');
        if (tracker.hasAnyLimit()) {
            BudgetStatus s = tracker.check();
            sb.append("- maxRatio: ").append(String.format("%.0f%%", s.maxRatio() * 100)).append('\n');
        }
    }

    private static void appendPlanProgress(StringBuilder sb, L2State state) {
        sb.append("## Plan Progress\n\n");
        if (state.planProgress == null || state.planProgress.isEmpty()) {
            sb.append("(no plan recorded)\n");
            return;
        }
        // Defensive snapshot — same rationale as appendDecisions.
        List<L2State.PlanProgressEntry> steps =
                new java.util.ArrayList<>(state.planProgress);
        sb.append("| step | status | attempts | filesChanged |\n");
        sb.append("|---|---|---|---|\n");
        for (L2State.PlanProgressEntry e : steps) {
            if (e == null) continue;
            sb.append("| ").append(e.step)
              .append(" | ").append(e.status == null ? "todo" : e.status)
              .append(" | ").append(e.attempts == null ? 0 : e.attempts)
              .append(" | ").append(e.filesChanged == null ? "" : String.join(", ", e.filesChanged))
              .append(" |\n");
        }
    }

    private static void appendDecisions(StringBuilder sb, L2State state) {
        sb.append("## Decisions (recent)\n\n");
        if (state.recentDecisions == null || state.recentDecisions.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        // Defensive snapshot: caller may pass a state shared with L2Store mutation
        // threads; iterating the live ArrayList would race with trimRings() →
        // ConcurrentModificationException. Cheap (decisions are <=100 entries).
        List<L2State.DecisionEntry> decisions =
                new java.util.ArrayList<>(state.recentDecisions);
        sb.append("| ts | action | category | confidence | reason |\n");
        sb.append("|---|---|---|---|---|\n");
        for (L2State.DecisionEntry d : decisions) {
            if (d == null) continue;
            sb.append("| ").append(d.ts > 0 ? ISO.format(new Date(d.ts)) : "—")
              .append(" | ").append(d.action == null ? "" : d.action)
              .append(" | ").append(d.category == null ? "" : d.category)
              .append(" | ").append(d.confidence == null ? "" : d.confidence)
              .append(" | ").append(d.reason == null ? "" : escapeCell(d.reason))
              .append(" |\n");
        }
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    private static String escapeCell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
