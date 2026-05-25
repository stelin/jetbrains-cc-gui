package com.github.claudecodegui.session.pair.prompt;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Phase 4 (2026-05-24): renders the {@code systemPrompt} suffix injected when
 * starting a new supervisor session (Generation N+1, or the initial one).
 *
 * <p>Two flavours:
 * <ul>
 *   <li>{@link #renderHandoff} — for rotation; embeds the L2 snapshot inside a
 *       {@code <HANDOFF_DOC>} block + behavioural directives.</li>
 *   <li>{@link #renderInitialBootstrap} — for {@code generation == 0}; tells
 *       the supervisor it's the first generation and to bootstrap
 *       anchoredFacts via the {@code update_state} tool.</li>
 * </ul>
 *
 * <p>Single source of truth at runtime; the markdown copies in
 * {@code docs/supervisor/handoff-successor.md} and
 * {@code docs/supervisor/initial-bootstrap.md} are for human reference.
 */
public final class SuccessorPromptBuilder {

    private static final Gson GSON_PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /** Hard cap on the embedded handoff JSON to keep systemPrompt token budget bounded. */
    public static final int MAX_HANDOFF_JSON_CHARS = 24_000;

    private SuccessorPromptBuilder() { /* no instances */ }

    /**
     * @param pairId            pair UUID
     * @param newGeneration     the generation number the new supervisor will run as (after rotate completes)
     * @param triggerReason     why rotation fired (ratio>=0.85 / compactCount>=3 / manual / ...)
     * @param ageSeconds        seconds between handoff doc creation and successor.start
     * @param snapshot          L2 state to embed (validated upstream)
     * @param degraded          true if the snapshot is L2-fallback rather than producer-fresh
     * @param snapshotAgeMinutes only used when {@code degraded == true}; how old the snapshot is in minutes
     */
    public static String renderHandoff(
            String pairId,
            int newGeneration,
            String triggerReason,
            long ageSeconds,
            L2State snapshot,
            boolean degraded,
            long snapshotAgeMinutes
    ) {
        String json = GSON_PRETTY.toJson(snapshot);
        if (json.length() > MAX_HANDOFF_JSON_CHARS) {
            // Truncate the embedded JSON if it would blow past the budget. We
            // keep the BEGINNING (which contains anchoredFacts + planProgress,
            // the hot fields) and truncate the tail. Marker in-place so the
            // model knows it was cut.
            json = json.substring(0, MAX_HANDOFF_JSON_CHARS) + "\n... (truncated)";
        }
        String currentStep = snapshot != null && snapshot.anchoredFacts != null
                && snapshot.anchoredFacts.currentStep != null
                ? snapshot.anchoredFacts.currentStep.toString() : "?";
        String currentStepTitle = snapshot != null && snapshot.anchoredFacts != null
                && snapshot.anchoredFacts.currentStepTitle != null
                ? snapshot.anchoredFacts.currentStepTitle : "(unspecified)";
        String reason = triggerReason == null || triggerReason.isEmpty() ? "manual" : triggerReason;

        StringBuilder sb = new StringBuilder(json.length() + 1024);
        sb.append("你是 Pair ").append(pairId == null ? "?" : pairId)
          .append(" 的第 ").append(newGeneration).append(" 代 supervisor。\n")
          .append("前任在 context 接近上限 (").append(reason).append(") 后正常移交, ")
          .append("本文档由前任亲自产生 (产生于 ").append(ageSeconds).append(" 秒前)。\n")
          .append("\n")
          .append("<HANDOFF_DOC>\n")
          .append(json).append("\n")
          .append("</HANDOFF_DOC>\n")
          .append("\n")
          .append("行为指引:\n")
          .append("1. 你的\"对过去 0~now 的认知\"完全来自上面文档, 不要假装记得别的\n")
          .append("2. 引用文档外内容时优先用 Read/Grep 验证, 不要凭空回答\n")
          .append("3. anchoredFacts 是硬约束, 与新事件冲突时以新事件为准并通过 update_state 工具更新\n")
          .append("4. 继续推进 plan, 当前在 step ").append(currentStep).append(" (").append(currentStepTitle).append(")\n")
          .append("5. recentDecisions 是前任决策风格参考, 保持一致性但不必盲从\n")
          .append("6. confidence == \"low\" 的字段需要你主动核实\n")
          .append("\n")
          .append("接下来你会收到正常的 main-AI 事件流, 像往常一样监督。\n")
          .append("第一批事件可能包含\"你刚接手期间发生但你不知道的事\", 会带 [NEW_GENERATION_BANNER] 标记。\n");

        if (degraded) {
            sb.append("\n")
              .append("[DEGRADED_HANDOFF_NOTICE]\n")
              .append("前任未能产生合规 handoff 文档。\n")
              .append("以上 JSON 是 ").append(snapshotAgeMinutes).append(" 分钟前自动落盘的快照, 可能不是最新状态。\n")
              .append("请优先用 Read/Grep 核对文件状态, 用 update_state 修正过时字段。\n");
        }
        return sb.toString();
    }

    /**
     * @param pairId        pair UUID
     * @param planSummary   short plan summary (e.g. first 800 chars of plan.md, or a one-line abstract)
     * @param specSummary   nullable; short project spec summary
     */
    public static String renderInitialBootstrap(String pairId, String planSummary, String specSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Pair ").append(pairId == null ? "?" : pairId)
          .append(" 的初代 supervisor。本会话刚启动, 没有前任。\n")
          .append("\n")
          .append("任务背景:\n")
          .append("- Plan: ").append(planSummary == null || planSummary.isEmpty() ? "(none)" : planSummary).append("\n");
        if (specSummary != null && !specSummary.isEmpty()) {
            sb.append("- Spec: ").append(specSummary).append("\n");
        } else {
            sb.append("- Spec: (none)\n");
        }
        sb.append("\n")
          .append("接下来你会收到主 AI 的事件流。开始监督前, 第一件事是通过 update_state 工具\n")
          .append("初始化 anchoredFacts (currentStep / totalSteps / currentStepTitle)。\n");
        return sb.toString();
    }
}
