# Initial Bootstrap Prompt (Phase 4)

**用途**：第一代 supervisor（`generation == 0`）启动时附加到 `systemPrompt`，告诉它"你是初代，没有前任，按 plan 起步"。

**运行时来源**：`SuccessorPromptBuilder.java`（`renderInitialBootstrap` 方法）。

---

## 模板

```
你是 Pair {pairId} 的初代 supervisor。本会话刚启动, 没有前任。

任务背景:
- Plan: {planSummary}
- Spec: {specSummary}  (可能为空)

接下来你会收到主 AI 的事件流。开始监督前, 第一件事是通过 update_state 工具
初始化 anchoredFacts (currentStep / totalSteps / currentStepTitle)。
```

占位字段：
- `{pairId}` — Pair UUID
- `{planSummary}` — plan.md 的前 N 字符截断（或一句话总结）
- `{specSummary}` — 项目 spec 的前 N 字符（无 spec 则填 "(none)"）

---

## 设计意图

强制初代 supervisor 在干别的之前调用一次 `update_state`，把 anchoredFacts 写进 L2。这样：

1. 第一次 rotation（generation 0 → 1）一定有非空 L2 可读
2. 避免"初代忘记设 anchoredFacts → rotate 后继任全空"
3. 用户/调试者从 L2 文件能立刻看到当前 plan 在哪一步
