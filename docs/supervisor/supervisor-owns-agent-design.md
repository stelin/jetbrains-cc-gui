# Supervisor 自派子 agent 改造方案（讨论记录）

> 状态：**讨论中**，未实施
> 目标：把 Supervisor 派子 agent 的能力从「让主 AI 转手」改为「Supervisor 自己直接派」
> 关联：`code.md`、`design.md`、`ai-bridge-server/ai-bridge/channels/supervisor-channel.js`

---

## 1. 改造动机

### 1.1 现状（code.md / design.md 当前设计）

Supervisor 是「盲眼控制器」：

- 工具集只有 `inject_prompt` / `emit_action` / `escalate_to_human` 等编排工具
- 核心铁律 #2「Supervisor 永不读源文件」
- 所有信息收集（manifest 提取、code review）通过 `inject_prompt` 让**主 AI** 去派子 agent，主 AI 再把 YAML **原文转手**回报

数据流：`Supervisor → 主 AI → 子 agent → 主 AI → Supervisor`（3 跳）

### 1.2 现状问题

1. **主 AI 当"邮递员"**：派 manifest 子 agent 和 review 子 agent 本质是「读类信息收集」，输出给 Supervisor 做决策用，主 AI 转手是无意义中间人
2. **角色冲突**：现状里主 AI 派一个「评判自己产出」的子 agent，评审者和被评审者属于同一 session
3. **每步多耗 1 个主 AI turn**：派子 agent + 转发结果，10 步设计就是 10 turn 浪费；尤其影响主 AI 上下文增长（Supervisor inputStream 已经无限增长，autocompact 反应式）
4. **提示词冗余**：现行设计里大段「请把子 agent 返回的 YAML **原文**上报给我，不要做任何修改」是在打补丁绕过不该存在的转手
5. **UI 角色错位**：用户想给 Supervisor 对话框加「任务 / 子代理」面板，但现状下 Supervisor 没有子 agent，面板永远是空的

### 1.3 改造目标

**Supervisor 自己持有 Agent 工具**，按需开子 agent 做信息收集 / 评审：

`Supervisor → 子 agent → Supervisor`（1 跳）

---

## 2. 现状架构调研（事实清单）

来自代码扫描，作为改造的事实基础：

### 2.1 会话启动

- **Supervisor 是独立的 SDK Query session**（不是 fork 主 AI）
- 启动链路：webview → `PairHandler.handleStart()` → `PairSessionManager.startPair()` → `SupervisorBridge.startWithHandoff()` → daemon 的 `supervisor.start` RPC
- 关键文件：`ai-bridge-server/ai-bridge/channels/supervisor-channel.js`
- 系统 prompt 由 plan + spec + user description 动态组合（`buildSystemPrompt()`，supervisor-channel.js:133-207）

### 2.2 工具白名单

- 当前硬编码：`SUPERVISOR_READ_TOOLS = ['Read', 'Glob', 'Grep']`（`supervisor-channel.js:44`）
- 加上两个本地 MCP 工具：`mcp__supervisor__emit_action` / `mcp__supervisor__update_state`
- **关键事实**：Supervisor **已经能读源码**（Read/Glob/Grep），只是提示词强制它"不要读"（核心铁律 #2）
- 双层防御：SDK `options.allowedTools` + `canUseTool` 回调

### 2.3 inject_prompt 数据流

`emit_action` 返回 → daemon NDJSON `[SUPERVISOR_ACTION]` 行 → Java `ActionRouter` → `WebviewBridgeImpl.onInjectPrompt()` → JS `window.onPairInjectPrompt` → 以 fake-user 方式塞入主 AI session

### 2.4 UI 现状

- **SupervisorPane**：右侧 Tab（不是独立 ToolWindow）
- 组件树：`SupervisorPane` → `PairStatusBar` + N 个 `SupervisorSubPanel`（每个 supervisor agent 一个）+ `SupervisorChatInput`
- 消息渲染复用主 AI 的 `MessageList` / `ContentBlockRenderer`
- **没有 Tasks/子代理面板**（本次要新增）
- 消息流：daemon `[SUPERVISOR_MSG]` 行 → `SupervisorMessageBatcher` → `window.onSupervisorMessageBatch` → webview

---

## 3. 决策清单（Q1-Q9）

讨论过程中提出 9 个不明确点，用户回答如下：

| # | 问题 | 决策 | 备注 |
|---|---|---|---|
| Q1 | 子 agent 类型用预定义还是 general-purpose？ | **未答（R1 待决）** | 影响代码改动量 |
| Q2 | code_reviewer Bash 权限怎么收紧？ | **完全放开**，靠 prompt 约束 | 开发沙盒环境 |
| Q3 | Supervisor 何时直接 Read/Glob/Grep？ | **直接读取结果数据时** = 自读；搜索 / 评审类 = 派子 agent | |
| Q4 | UI 子代理面板归属 | **Supervisor 和主 AI 各自独立维护** | 不共享 |
| Q5 | Supervisor 子 agent 消息呈现 | **折叠卡片**（与主 AI 一致） | |
| Q6 | inject_prompt 保留用途 | **实施下发 / review 修复反馈 / 待确认回执** 三类 | 删除"让主 AI 派子 agent"用途 |
| Q7 | Agent 工具失败重试 | **纳入现有 5 次指数退避** | 与 429/timeout 同等级 |
| Q8 | 迁移策略 | **一次性切换**：daemon + UI + 提示词全改 | 不灰度 |
| Q9 | 安全边界 | **Edit/Write/Bash 禁止**（Supervisor 自身），**Read/Glob/Grep/Agent 放开** | |

---

## 4. 综合后的完整设计

### 4.1 Supervisor 的三种行为模式（语义分层）

```
┌─────────────────────────────────────────────────────┐
│ 1. 自己读（轻量验证）                                  │
│    工具：Read / Glob / Grep                          │
│    场景：验收期 Grep 下游契约关键字、查看具体某段代码      │
│    边界：目标是验证某条具体结论                          │
│    上下文成本：小                                     │
├─────────────────────────────────────────────────────┤
│ 2. 派子 agent（信息收集 / 评审 / 检索）                │
│    工具：Agent                                       │
│    场景：manifest 提取、code review、多文件检索        │
│    边界：目标是收集 / 评判一组事实                       │
│    上下文成本：仅子 agent 返回的 YAML                  │
├─────────────────────────────────────────────────────┤
│ 3. 派主 AI（要求修改代码 / 跑实施任务）                 │
│    工具：emit_action(inject_prompt)                  │
│    场景：实施步骤下发、review 修复指令、待确认回执        │
│    上下文成本：仅主 AI 的回执文本                       │
└─────────────────────────────────────────────────────┘
```

**关键规则**：行为 1 和 2 之间的边界靠提示词显式划定——"目标是验证某条具体结论 → 自读；目标是收集 / 评判一组事实 → 派子 agent"。

### 4.2 子 agent 类型

预定义两个类型（R1 待用户确认）：

| 类型 | 工具白名单 | 用途 |
|---|---|---|
| `manifest_extractor` | Read / Glob / Grep | 读 design 文档提取结构化 manifest |
| `code_reviewer` | Read / Glob / Grep / Bash | 读产出代码做评审，含 `go build` / `go vet` / `golangci-lint` |

Bash 完全放开（Q2 决策），靠 prompt 在 brief 里约束"只跑下列命令"。

### 4.3 UI 架构

```
SupervisorPane（右侧已有）
├── PairStatusBar（已有）
├── SupervisorSubPanel × N（已有，每个 supervisor agent 一个）
│   ├── 消息列表（已有）
│   │   └── 含折叠的 Agent 工具调用卡片（★ 新增渲染逻辑）
│   ├── ★ Tasks/子代理面板（★ 新增）
│   │   └── 显示该 supervisor 派的子 agent 实时状态
│   │       - manifest_extractor (running / done verdict=pass)
│   │       - code_reviewer step=3 (running)
│   └── SupervisorChatInput（已有）
```

**与主 AI 子代理面板的关系**（Q4）：
- 主 AI 的 Tasks 面板在主窗口左侧（已有），数据源 = 主 AI session 的 Agent 工具调用事件
- Supervisor 的 Tasks 面板在 SupervisorPane 内（新增），数据源 = Supervisor session 的 Agent 工具调用事件
- **两套独立**，不共享，不混淆「哪个 session 派的」

**数据通路**：daemon 已有 `[SUPERVISOR_MSG]` NDJSON 推送 Supervisor 的 content blocks。需要新增的是 webview 端从 message stream 里筛选 `tool_use(name="Agent")` 和对应的 `tool_result`，组装成子代理状态卡片。

### 4.4 提示词重写要点

#### code.md 的改动

**删除的段**：

- 核心铁律 #2「Supervisor 永不读源文件」整条
- Step 0「派 manifest 子 agent」段落里 60 行 self-contained brief 模板（改为 Supervisor 直接 Agent 调用）
- Review 步骤 1「派 review 子 agent」段落里 70 行 brief 模板（同上）
- review 子 agent 报告格式异常处理里「让主 AI 重派」的逻辑（改为 Supervisor 直接重派）
- 「你不做的事」里两条：「不读 design 原文」「不直读产出代码文件」

**新增的段**：

- 「Supervisor 工具使用准则」：Read/Glob/Grep/Agent 各自的使用场景与边界
- 「Step 0：直接派 manifest_extractor」：Supervisor 用 Agent 工具，brief 嵌入提示词正文
- 「Review 流程：直接派 code_reviewer」：Supervisor 用 Agent 工具，brief 嵌入提示词正文
- 「子 agent 失败处理」：Agent 工具异常直接重试（不绕主 AI）
- 「你不做的事」里新增：「不 Edit / 不 Write / 不 Bash」

**保留不变的段**：

- 自决与升级边界（A/B/C 三类）
- decisions[] 字段约定
- API / Turn 错误处理表
- 验收 escalate 格式

#### design.md 的改动

- 启动协议第一步「让主 AI 列出技能包清单」**保留**（探查涉及决策语义，留主 AI 痕迹好）
- 启动协议第三步「让主 AI 提取硬规则摘要」**可改为 Supervisor 自己派 manifest_extractor 提取**——更直接
- review 阶段的「轨道一：硬规则核对」**不动**（本来就是 Supervisor 自己 Grep，符合新模型）
- 跳过-继续机制、decisions[] 字段、三色标记 **不动**

整体 design.md 改动比 code.md 小。

### 4.5 错误处理统一化

| 失败场景 | 旧路径 | 新路径 |
|---|---|---|
| Agent 工具 SDK 错误 | 不存在（主 AI 自己派） | 纳入 5 次指数退避 |
| 子 agent 返回 YAML 解析失败 | inject_prompt 让主 AI 重派 | Supervisor 直接重新 Agent 调用 |
| 子 agent verdict=fail | inject_prompt 让主 AI 修复 | inject_prompt 让主 AI 修复（**不变**） |
| 主 AI 不响应 | inject_prompt 重试 | inject_prompt 重试（**不变**） |

简化点：少了「主 AI 转手错误」这一类失败模式。

### 4.6 inject_prompt 用途收窄

新设计下 inject_prompt 用途只剩三类（Q6）：

1. ✅ 下发实施步骤（「请按 manifest 的步骤 N 执行...」）
2. ✅ 反馈 review violations 给主 AI 修复
3. ✅ 待确认事项裁决回执（「已采纳 X = Y...」）

删除的用途：

4. ❌ ~~让主 AI 派 manifest 子 agent~~
5. ❌ ~~让主 AI 派 review 子 agent~~

---

## 5. 改造影响面预估

| 文件 | 改动量 | 风险 |
|---|---|---|
| `supervisor-channel.js` | +30 行（Agent 工具白名单 + subagent 定义） | 低 |
| `supervisor-tools.js` | 无 | — |
| `code.md` | 重写约 25% | 中（逻辑变了，要重新对齐） |
| `design.md` | 重写约 15% | 中 |
| `SupervisorPane.tsx` + 子组件 | +新增 Tasks/子代理面板组件（可复用主 AI 现有组件） | 低 |
| `ActionRouter` / `WebviewBridge` | 可能扩展事件类型（Supervisor 子代理状态） | 低 |

---

## 6. 待决问题（R1-R5）

### R1. 子 agent 类型用预定义还是 general-purpose？（Q1 未答的补问）

| 选项 | 说明 |
|---|---|
| A. 预定义两个类型（**推荐**） | daemon 端 SDK 配置注册 `manifest_extractor` / `code_reviewer`，工具白名单代码级隔离 |
| B. 用 general-purpose | 每次派 agent 写完整角色 brief，工具白名单靠 prompt 约束 |

**推荐 A** 的理由：
1. 工具白名单代码级隔离更可靠
2. role description 固化在 daemon，提示词不用每次重复
3. 跨项目通用，不依赖用户项目目录的 `.claude/agents/` 文件

**矛盾点**：Q2 选了「放开 Bash 靠 prompt 约束」，逻辑一致的话 R1 应该选 B。但 manifest_extractor 不需要 Bash，B 会让它有不必要的能力。

### R2. Supervisor 自己需要 Bash 吗？

按 Q9「不改代码但可读」——Bash 不是读，是执行命令（虽然 `go vet` 之类是只读语义）。

| 选项 | 说明 |
|---|---|
| A. Supervisor 不开 Bash（**推荐**） | 编译/验证一律派 code_reviewer 子 agent |
| B. Supervisor 也开 Bash | 简单验证命令（`ls` / `wc -l` / `go vet`）自己跑 |

**推荐 A**：Bash 这个 power 边界要清晰；要跑命令就派子 agent。

### R3. 子 agent 并发调用要不要支持？

review 时如果当前步骤 `parallelizable=true`（多个独立产出），Supervisor 可以**一个 message 里并发派多个 code_reviewer**。

| 选项 | 说明 |
|---|---|
| A. 支持并发评审 | webview 端 tool_use_id 维护并发状态，聚合逻辑放 Supervisor 提示词里 |
| B. 强制串行 | 一次只评一个文件，简单但慢 |

未明确推荐，需要看实际使用场景。

### R4. Supervisor 子 agent 的上下文上限

manifest_extractor 读长 design 文档（几千行）可能 context overflow。

| 选项 | 说明 |
|---|---|
| A. 不处理（**推荐**） | 靠 SDK 自动 context management |
| B. 提示词约束切片 | Supervisor 优先用 Read offset/limit 切片传给子 agent |
| C. 预切分步骤 | 加专门的「长文档预切分」步骤 |

**推荐 A**：先不优化，遇到再说。

### R5. 旧会话兼容性

升级 daemon + UI 后，正在运行的旧 Supervisor 会话怎么办？

| 选项 | 说明 |
|---|---|
| A. 强制重启所有 supervisor 会话 | 暴力 |
| B. 旧会话保留旧逻辑直到自然结束 | 双轨成本高 |
| C. 不管，下次启动就是新逻辑（**推荐**） | 一次性切换的语义就是不兼容 |

**推荐 C**：用户可手动结束运行中的旧会话。

---

## 7. 实施顺序建议（待 R1-R5 确认后细化）

1. **决策对齐**：用户回答 R1-R5
2. **提示词改造**：先改 `code.md` / `design.md`（不动代码也能跑一遍干跑验证）
3. **daemon 改造**：扩展 `SUPERVISOR_READ_TOOLS`、注册 subagent types
4. **UI 改造**：SupervisorPane 加 Tasks/子代理面板，复用主 AI 渲染组件
5. **联调验证**：跑一遍完整的「设计 → 编码」流程，确认 Supervisor 自派 agent 工作正常
6. **回归**：检查 inject_prompt 的三类保留用途未受影响

---

## 8. 关键不变量（无论怎么改都要守住）

- ✅ **Supervisor 不写代码**：Edit / Write / NotebookEdit 永不开放
- ✅ **主 AI 是唯一实施者**：代码修改、命令执行（如果 Supervisor 不开 Bash）都走主 AI
- ✅ **decisions[] 留痕**：所有自决记录不变
- ✅ **escalate 边界**：C 类不可自决、API 5 次失败 escalate 不变
- ✅ **UI 双轨并行**：主 AI 和 Supervisor 各自独立的对话流和子代理面板

---

## 附：相关文件索引

- `code.md` — 编码监督者提示词（需重写约 25%）
- `design.md` — 方案监督者提示词（需重写约 15%）
- `handoff-successor.md` / `handoff-producer.md` — 代际传递（本次不动）
- `initial-bootstrap.md` — 启动引导（本次不动）
- `ai-bridge-server/ai-bridge/channels/supervisor-channel.js` — Supervisor SDK session 配置点
- `ai-bridge-server/ai-bridge/services/supervisor/supervisor-tools.js` — emit_action / update_state MCP
- `jetbrains-cc-gui/webview/src/components/SupervisorPair/SupervisorPane.tsx` — UI 主面板
- `jetbrains-cc-gui/src/main/java/com/github/claudecodegui/handler/PairHandler.java` — 启动 / 消息 / IPC 处理
- `jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/ActionRouter.java` — emit_action 路由
