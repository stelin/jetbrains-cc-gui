package com.github.claudecodegui.session.pair.prompt;

import com.github.claudecodegui.session.pair.l2.L2State;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 6b (2026-05-24): builds prompts for main-AI rotation. Three flavours
 * mirror {@link SuccessorPromptBuilder} but the schema is richer because the
 * main AI participates in a dialog (user-visible chat history) — losing the
 * exact wording of recent user messages would be more jarring than for the
 * supervisor (whose own "memory" the user never sees).
 *
 * <p>Producer prompt enforces:
 * <ul>
 *   <li>{@code recentUserMessages} — original verbatim, last 10 (NOT summarised).</li>
 *   <li>{@code lastAssistantSummary} — supervisor-style summary of the last
 *       few assistant turns.</li>
 *   <li>{@code currentTask} — what we were working on (free-form).</li>
 *   <li>{@code userPreferences} — extracted from history (string list).</li>
 *   <li>L2 base fields (anchoredFacts / planProgress / fileState / etc.).</li>
 * </ul>
 *
 * <p>Successor prompt is plumbed via {@code options.systemPrompt.append} on
 * the FIRST {@code claude.send} after rotation. The Java side stages it on
 * {@code SessionState.pendingSystemPromptAppend} via
 * {@code ClaudeSession.swapInnerSession}, the next send consumes it once via
 * {@code state.consumePendingSystemPromptAppend()}, and the daemon-side
 * {@code persistent-query-service.buildSystemPromptAppend} concatenates it
 * with the IDE/agentPrompt append before passing both to the SDK.
 */
public final class MainAIHandoffPromptBuilder {

    private static final Gson GSON_PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /**
     * Hard cap on the embedded handoff JSON. Main AI rotation is more
     * UX-sensitive than supervisor — we accept a slightly larger budget
     * (32KB vs 24KB) because user-visible context loss is worse than a
     * few extra prompt tokens.
     */
    public static final int MAX_HANDOFF_JSON_CHARS = 32_000;
    /** Recent-user-messages window. */
    public static final int RECENT_USER_MESSAGE_COUNT = 10;

    private MainAIHandoffPromptBuilder() { /* no instances */ }

    // ─── Producer (sent to the OLD main-AI session pre-rotation) ─────────

    public static String renderProducerInitial(String triggerReason) {
        String reason = (triggerReason == null || triggerReason.isEmpty()) ? "manual" : triggerReason;
        return ""
                + "[SYSTEM TASK: HANDOFF — main AI session rotation]\n"
                + "你即将被一个新的 main AI session 替换 (同一个 Pair / 同一个 chat 窗口, 原因: " + reason + ")。\n"
                + "请产出结构化交接文档, 让继任者无损接管这段对话。\n"
                + "\n"
                + "OUTPUT FORMAT: 严格 JSON (符合 main-AI handoff schema)\n"
                + "\n"
                + "字段约定:\n"
                + "- recentUserMessages: array of objects, 最近 " + RECENT_USER_MESSAGE_COUNT + " 条用户消息原文 (verbatim,\n"
                + "  不要总结, 不要 paraphrase, 不要省略)。每条形如\n"
                + "  {\"ts\": <ms>, \"text\": \"原始消息\"}. 用户消息是对话锚点, 失真会让\n"
                + "  继任者迷失。\n"
                + "- lastAssistantSummary: string, 200-400 字总结你最近 3-5 个 turn 在做什么,\n"
                + "  当前推进到哪一步, 卡在哪里 (如果卡住)。\n"
                + "- currentTask: object, 形如 {\"description\": \"...\", \"filesActive\": [...], \"goals\": [...]}\n"
                + "  描述目前主线任务。\n"
                + "- userPreferences: array of strings, 从对话历史里提炼出的硬约束 / 偏好\n"
                + "  (例如 \"用户偏好 langchaingo, 反对 openai-go\")。每条独立一句话。\n"
                + "- anchoredFacts / planProgress / fileState / recentDecisions / knownConstraints:\n"
                + "  跟 supervisor handoff 同 schema, 但站在 main AI 视角填。如果某字段你不掌握,\n"
                + "  写 null 或空数组, 不要瞎编。\n"
                + "\n"
                + "RULES:\n"
                + "1. 字段独立, 不引用 \"见上文\" / \"同上\"\n"
                + "2. recentUserMessages 必须 verbatim — 这是用户安全感的来源, 不要重写\n"
                + "3. 不确定的事字段写 null 或 \"confidence\": \"low\"\n"
                + "4. 总输出 <= 6000 tokens (main AI 比 supervisor 大一倍)\n"
                + "5. 只输出 JSON 本身, 不要 markdown code fence, 不要 prose 包装\n"
                + "\n"
                + "开始输出 JSON:\n";
    }

    public static String renderProducerRetry(String validationErrors) {
        String errs = validationErrors == null ? "(unknown)" : validationErrors;
        return ""
                + "[VALIDATION FAILED]\n"
                + "你刚才的 handoff JSON 不符 schema:\n"
                + errs + "\n"
                + "\n"
                + "请重新输出完整 JSON (不是 diff)。注意 recentUserMessages 必须 verbatim —\n"
                + "如果你不确定原文, 写 \"text\" 时加 \"confidence\": \"low\" 标注。\n"
                + "仅最后一次机会, 超时将由系统兜底。\n";
    }

    // ─── Successor (system prompt append for the NEW main-AI session) ────

    /**
     * @param pairId            pair UUID
     * @param newGeneration     new generation number (mainAI.generation post-swap)
     * @param triggerReason     why rotation fired
     * @param ageSeconds        seconds between handoff doc creation and successor start
     * @param l2                pair's L2 snapshot (for anchoredFacts / planProgress)
     * @param mainAIHandoffJson the producer's JSON output (already validated / parsed)
     * @param degraded          true if mainAIHandoffJson is L2-fallback (producer failed)
     * @param snapshotAgeMinutes when {@code degraded}, how old the L2 snapshot is
     */
    public static String renderSuccessor(
            String pairId,
            int newGeneration,
            String triggerReason,
            long ageSeconds,
            L2State l2,
            String mainAIHandoffJson,
            boolean degraded,
            long snapshotAgeMinutes
    ) {
        String json = mainAIHandoffJson == null ? "{}" : mainAIHandoffJson;
        if (json.length() > MAX_HANDOFF_JSON_CHARS) {
            json = json.substring(0, MAX_HANDOFF_JSON_CHARS) + "\n... (truncated)";
        }
        String l2Pretty = (l2 == null) ? "{}" : GSON_PRETTY.toJson(l2);
        if (l2Pretty.length() > MAX_HANDOFF_JSON_CHARS / 2) {
            l2Pretty = l2Pretty.substring(0, MAX_HANDOFF_JSON_CHARS / 2) + "\n... (truncated)";
        }
        String reason = (triggerReason == null || triggerReason.isEmpty()) ? "manual" : triggerReason;
        String currentStep = (l2 != null && l2.anchoredFacts != null
                && l2.anchoredFacts.currentStep != null)
                ? l2.anchoredFacts.currentStep.toString() : "?";

        StringBuilder sb = new StringBuilder(json.length() + l2Pretty.length() + 1024);
        sb.append("你是 Pair ").append(pairId == null ? "?" : pairId)
          .append(" 的第 ").append(newGeneration).append(" 代 main AI session。\n")
          .append("用户和你的 chat 窗口是连续的 (历史消息仍在屏幕上), 但你的运行时上下文是 fresh —\n")
          .append("原因: ").append(reason).append(", 距前任产出 handoff ").append(ageSeconds).append(" 秒。\n")
          .append("\n")
          .append("<MAIN_AI_HANDOFF_DOC>\n")
          .append(json).append("\n")
          .append("</MAIN_AI_HANDOFF_DOC>\n")
          .append("\n")
          .append("<PAIR_L2_SNAPSHOT>\n")
          .append(l2Pretty).append("\n")
          .append("</PAIR_L2_SNAPSHOT>\n")
          .append("\n")
          .append("行为指引:\n")
          .append("1. 你的\"对过去对话的认知\"完全来自上面两个文档 + 用户接下来的输入。\n")
          .append("   屏幕上的旧消息你能看到但你的 SDK context 没有它们 —\n")
          .append("   如果用户引用 \"我之前说过 X\", 优先信 recentUserMessages.\n")
          .append("2. 引用具体代码 / 文件状态时, 一律先 Read 再说。\n")
          .append("3. userPreferences 是硬约束, 一定遵守。\n")
          .append("4. 当前主线任务在 currentTask. 继续推进, plan 进度在 step ").append(currentStep).append(".\n")
          .append("5. 如果 anchoredFacts / fileState 跟你 Read 出来的不一致, 以文件实际状态为准.\n")
          .append("\n")
          .append("用户的下一条消息会立刻到来. 请直接进入工作状态, 不要先回顾 \"我重新启动了\" 之类的 meta 话.\n");

        if (degraded) {
            sb.append("\n")
              .append("[DEGRADED_HANDOFF_NOTICE]\n")
              .append("前任未能产生合规 handoff 文档.\n")
              .append("以上 MAIN_AI_HANDOFF_DOC 是 ").append(snapshotAgeMinutes).append(" 分钟前自动落盘的快照, 不是 fresh 交接.\n")
              .append("请第一件事用 Read / Grep 核对 fileState 是否仍然准确.\n");
        }
        return sb.toString();
    }

    // ─── Initial bootstrap (gen-0 main AI; informational, not strictly necessary) ──

    public static String renderInitialBootstrap(String pairId) {
        return "你是 Pair " + (pairId == null ? "?" : pairId)
                + " 的初代 main AI session. 没有 handoff, 这是用户第一次对话.\n"
                + "你的 SDK context 现在是 fresh. 直接按用户消息开工.\n";
    }

    // ─── Helper types for callers that want to build the doc programmatically ──

    /**
     * Minimal POJO for {@code recentUserMessages} — Java construction
     * helper. The producer LLM generates these on its own; this exists
     * only for the rare path where Java assembles a fallback from
     * already-known user messages (e.g. after a hard crash).
     */
    public static final class RecentUserMessage {
        public long ts;
        public String text;
        public String confidence; // "high" / "medium" / "low" — nullable

        public RecentUserMessage(long ts, String text) {
            this.ts = ts;
            this.text = text;
        }
    }

    /**
     * Assemble an empty-but-valid handoff JSON when we have nothing else to
     * fall back to — caller passes whatever last user messages it has.
     */
    public static String renderFallbackHandoffJson(List<RecentUserMessage> recent, String currentTask) {
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("recentUserMessages", recent == null ? Collections.emptyList() : new ArrayList<>(recent));
        doc.put("lastAssistantSummary", "");
        java.util.Map<String, Object> task = new java.util.LinkedHashMap<>();
        task.put("description", currentTask == null ? "" : currentTask);
        task.put("filesActive", Collections.emptyList());
        task.put("goals", Collections.emptyList());
        doc.put("currentTask", task);
        doc.put("userPreferences", Collections.emptyList());
        doc.put("anchoredFacts", null);
        doc.put("planProgress", Collections.emptyList());
        doc.put("fileState", Collections.emptyMap());
        doc.put("recentDecisions", Collections.emptyList());
        doc.put("knownConstraints", Collections.emptyList());
        return GSON_PRETTY.toJson(doc);
    }
}
