package com.github.claudecodegui.session.pair.prompt;

/**
 * Phase 4 (2026-05-24): builds the "produce handoff doc" prompt sent to the
 * old supervisor session right before rotation. Single source of truth at
 * runtime — docs/supervisor/handoff-producer.md is the human-readable copy.
 */
public final class HandoffProducerPromptBuilder {

    private HandoffProducerPromptBuilder() { /* no instances */ }

    /** First attempt — fresh produce request. */
    public static String renderInitial(String triggerReason) {
        String reason = triggerReason == null || triggerReason.isEmpty() ? "manual" : triggerReason;
        return ""
                + "[SYSTEM TASK: HANDOFF]\n"
                + "你即将被一个新的 supervisor session 替换 (同一个 Pair, 原因: " + reason + ")。\n"
                + "请产出结构化交接文档, 让继任者无损接管。\n"
                + "\n"
                + "OUTPUT FORMAT: 严格 JSON, 符合 L2 schema v2\n"
                + "\n"
                + "REQUIRED FIELDS:\n"
                + "- anchoredFacts.* 必须填写, 未知字段填 null (**不要瞎编**)\n"
                // 2026-06-10: planProgress is now a PROJECTION of the authoritative
                // Plan (persisted independently in PersistedPlan and restored by the
                // successor on its own), so the producer no longer needs to re-report
                // it. Kept optional for back-compat; focus effort on the soft context.
                + "- planProgress 可省略/简述：计划进度已由系统从权威 plan 自动投影并独立持久化, 继任会自行从 plan 恢复\n"
                + "- fileState 列出本会话期间所有 touch 过的文件 (路径 + 最后操作)\n"
                + "- recentDecisions 取最近 30 条 (按时间倒序)\n"
                + "- knownConstraints 继承前序 + 加新发现的硬约束\n"
                + "\n"
                + "RULES:\n"
                + "1. 字段独立, 不引用 \"见上文\" / \"同上\"\n"
                + "2. 不确定的事用 \"confidence\": \"low\" 标注\n"
                + "3. 每字段单独 <= 300 tokens\n"
                + "4. 总输出 <= 4000 tokens\n"
                + "5. 只输出 JSON 本身, 不要 markdown code fence, 不要 prose 包装\n"
                + "\n"
                + "如果某些信息确实没有掌握, 字段写 null 或空数组, 后面继任者会用 Read/Grep 自行核对。\n"
                + "\n"
                + "开始输出 JSON:\n";
    }

    /** Retry attempt — appended after the first failed validation. */
    public static String renderRetry(String validationErrors) {
        String errs = validationErrors == null ? "(unknown)" : validationErrors;
        return ""
                + "[VALIDATION FAILED]\n"
                + "你刚才的输出不符 schema:\n"
                + errs + "\n"
                + "\n"
                + "请按上面的错误修正后重新输出完整 JSON (不是 diff, 是完整重写)。\n"
                + "仅最后一次机会, 超时将由系统兜底。\n";
    }
}
