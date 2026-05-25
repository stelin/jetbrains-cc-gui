# Handoff Producer Prompt (Phase 4)

**用途**：rotation 流程第 5 步，老 supervisor 在被替换前产出一份结构化交接文档（L2 schema v2 JSON）。继任会话用这份文档水合 anchoredFacts / planProgress / fileState / recentDecisions / knownConstraints，以无损接管。

**运行时来源**：`HandoffProducerPromptBuilder.java`。本 markdown 与 Java 常量保持同步。

---

## 首次尝试模板

```
[SYSTEM TASK: HANDOFF]
你即将被一个新的 supervisor session 替换（同一个 Pair, 原因: {reason}）。
请产出结构化交接文档, 让继任者无损接管。

OUTPUT FORMAT: 严格 JSON, 符合 L2 schema v2

REQUIRED FIELDS:
- anchoredFacts.* 必须填写, 未知字段填 null (**不要瞎编**)
- planProgress 至少包含所有已开始的 step
- fileState 列出本会话期间所有 touch 过的文件 (路径 + 最后操作)
- recentDecisions 取最近 30 条 (按时间倒序)
- knownConstraints 继承前序 + 加新发现的硬约束

RULES:
1. 字段独立, 不引用 "见上文" / "同上"
2. 不确定的事用 "confidence": "low" 标注
3. 每字段单独 <= 300 tokens
4. 总输出 <= 4000 tokens
5. 只输出 JSON 本身, 不要 markdown code fence, 不要 prose 包装

如果某些信息确实没有掌握, 字段写 null 或空数组, 后面继任者会用 Read/Grep 自行核对。

开始输出 JSON:
```

`{reason}` 占位会替换为触发 rotation 的具体原因（例如 `ratio>=0.85` / `compactCount>=3` / `manual` / `unhealthy_2_ticks`）。

---

## 校验失败重试模板（第二次, 最多）

```
[VALIDATION FAILED]
你刚才的输出不符 schema:
{errorMessages}

请按上面的错误修正后重新输出完整 JSON (不是 diff, 是完整重写)。
仅最后一次机会, 超时将由系统兜底。
```

---

## 失败兜底

第二次仍校验失败 → 走 L2 上次落盘的 snapshot；successor prompt 会被附上 `[DEGRADED_HANDOFF_NOTICE]`。

第一代直接 UNHEALTHY → 跳过 producer, 直接用 L2 snapshot；successor prompt 同样降级。

---

## 严格 30s timeout

`RotationCoordinator` 每次 produceHandoff 单次 30s 超时；首次失败 + 重试一次共最多 ~60s。超过即视为失败，走 L2 fallback。
