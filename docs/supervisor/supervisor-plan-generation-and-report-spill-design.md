# 监督者生成计划 + 主AI执行汇总落盘 —— 详细编码方案

> 状态：**待评审 / 待编码**（2026-06-10）
> 作者讨论记录：见本仓 memory `supervisor-plan-generation-and-report-spill`
> 关联前置：`docs/supervisor/supervisor-state-machine-design.md`（ACTIVE/IDLE + 去 takeover）、`docs/plans/2026-06-01-supervisor-workflow-orchestration-design.md`（工作流编排）、`docs/plans/2026-05-23-supervisor-monitor-rotation-implementation.md`（L2 + update_state + rotation）
> 关联代码：`session/pair/plan/*`、`session/pair/l2/*`、`session/pair/ActionRouter.java`、`session/pair/guard/DeadlockGuard.java`、`bridge/SupervisorBridge.java`、`ai-bridge/services/supervisor/*`、`ai-bridge/services/claude/main-ai-tools.js`

---

## 0. 一句话目标

让**每个监督者**在收到首条任务时**先生成结构化计划**（每步带验收标准），把现状里**分裂成两套、且本地 daemon 半接线**的 plan 模型**收敛成单一真相源**，并让**主AI每轮执行汇总按需落盘**（daemon 托管），监督者把汇总**只当导航**、仍核查真实产物；崩溃/继续时从计划未完成处续跑，对**执行中**的步骤做存量对账。

**本质 = 统一 + 接线 + 补两块（生成、验收标准），而非新建一套 plan 系统。**

---

## 1. 现状（编码前必须对齐的真实基线）

### 1.1 plan 被存了两份，无单一真相源

| 表示 | 谁写 | 用途 | 位置 |
|---|---|---|---|
| `PersistedPlan plan`（Java `Plan`/`PlanStep`） | **机器**：`ensurePlanAndStep` 每次 inject_prompt **反应式造一个假步骤** | 喂 `DeadlockGuard`/合同状态机 | `L2State.java:89`、`ActionRouter.java:1302`、`plan/PlanStateMachine.java:67` |
| `planProgress` + `anchoredFacts` | **监督者**：经 `update_state` 自维护 | 喂 rotation 交接 / 人看 / UI | `L2State.java:45-47,148-166` |

`PlanProgressEntry` 已含 `{step,status,filesChanged,attempts,lastError,completedAt}`，`AnchoredFacts` 已含 `{currentStep,totalSteps,currentStepTitle,blockedOn,lastVerifyCmd/Result/At}` —— **几乎就是我们想要的 PlanStep 模型**，但和 `PersistedPlan` 并存、各写各的。

### 1.2 `update_state` 设计完整、Java 已接、**但本地 daemon 没接线**

- 设计：`docs/plans/2026-05-23...:1088`，schema `{anchoredFactsDelta, planProgressDelta, fileStateDelta, decisionAppend, constraintAdd}`。
- Java 已实现：`L2Store.applyUpdateStateDelta`（`L2Store.java:311`）、`SupervisorBridge.java:73/178/536`。
- **本地 daemon 缺**：`ai-bridge/services/supervisor/supervisor-tools.js` **只注册 `emit_action`**，没有 `update_state`，也没有 `emit_plan`。且 emit_action 仍带被 `2026-05-24` 文档废弃的 `decisions[]`（`supervisor-tools.js:112`），未迁 `decisionAppend`。
- 注意：`planProgressDelta` 当前**不含 title/验收标准**（`L2Store.java:333-354`），承载不了"初始结构化计划"。

### 1.3 续跑/状态机 已部分就位

- 跨代续跑（rotation handoff producer/successor）**已实现**："继续推进 plan, 当前在 step N"（`docs/supervisor/handoff-successor.md:23`）。
- `PlanStateMachine.restore(plan)` 重启回灌（`PlanStateMachine.java:54`），`PersistedPlan` 已随 L2 落盘。
- `DeadlockGuard` **已移除 system_takeover**，到点直接 escalate（`DeadlockGuard.java:242`）。
- 监督者模型来自配置：`supervisor-channel.js:45` `DEFAULT_MODEL=haiku` **仅是兜底**，`runtime.model = model || DEFAULT_MODEL`，`model` 由 Java（`StartPairParams.modelOverride`/agent 配置）传入。

---

## 2. 已锁定的设计决策

| # | 决策 | 结论 |
|---|---|---|
| D1 | 计划可变性 | 生成后**锁定**，改走 `request_amendment`；规划轮只把外部任务**结构化**为步骤，不发明目标 |
| D2 | 触发时机 | **首条任务事件**触发（非出生时）。单页=首条 `user_input`；workflow=handler 自动推送的首条任务。**首条统一规划**（即使像"你好"） |
| D3 | 汇总落盘 | **daemon 托管 spill**，主AI 只管结构化上报；不让主AI Write md 进仓库 |
| D4 | 信任模型 | 汇总**只当导航**（指明去核查哪些文件/行），**仍须 Read 真实产物再判定** |
| D5 | 真相源 | **`PersistedPlan`/`PlanStep` 为唯一真相源**；`planProgress`/`anchoredFacts` 降级为**投影**（projection），监督者不再经 update_state 写 plan 结构/进度 |
| D6 | **规划轮模型** | **用监督者自己配置的模型 `runtime.model`，不默认 haiku、不为规划单独抬档/换模型**。规划轮=同一 supervisor 持久会话上的普通一轮 |
| D7 | Guard 与规划 | 计划存在前（规划中）= **非派单 ACTIVE**，`DeadlockGuard` 的 PENDING_DECISION 倒计时**不 arm**，避免规划被误判卡死 escalate |
| D8 | 续跑核验 | DONE 不查 / TODO 不查 / **IN_PROGRESS 必对账**（Read 现场对照验收标准，判断实际完成度→续做/标完成/重做） |
| D9 | 验收标准 | `PlanStep` 新增 `acceptanceCriteria`，规划轮产出=判官轮逐条核对清单 |

---

## 3. 目标架构

```
                       ┌──────────────────────── 单一真相源 ────────────────────────┐
首条任务事件            │   PlanStateMachine  →  Plan / PlanStep(+acceptanceCriteria)  │
(planContent/user_input)│            ▲                          │                      │
   │ [PLANNING_REQUIRED] │            │ onPlanCreated(steps)     │ PlanStateListener     │
   ▼                     │   emit_plan │                          ▼ (write-through 投影)  │
[规划轮] supervisor      │  (新 daemon │              L2: anchoredFacts / planProgress   │
  emit_plan(steps) ──────┼────工具)────┘              (投影:rotation/UI/人看 只读)         │
                         └──────────────┬────────────────────────────────────────────┘
                                        │ 渲染
                                        ▼
                              plan.md(session 目录, 非仓库, 带 ✅/⏳/⬜) ← 监督者 resume 时 Read

执行循环：主AI → report_turn_completion ──(daemon 按大小内联/spill)──▶ spilledPath
         挂到对应 PlanStep.reportPath
                                        │
                                        ▼
判官轮：监督者 Read spilledPath(导航) → 据该步 acceptanceCriteria Read 真实产物核验
        → emit_action(mark_step_complete / request_amendment / inject_prompt / complete_plan)

崩溃/继续：restore(PersistedPlan) → 合成"恢复前言+进度+IN_PROGRESS对账指令" → 从首个非DONE续跑
```

**核心**：写路径只有一条（emit_plan 建结构 → emit_action 推进状态）；`planProgress`/`anchoredFacts` 由 `PlanStateListener` 投影写出，**监督者不再直接写它们**，二源合一。

---

## 4. 数据模型改动

### 4.1 `PlanStep`（`plan/PlanStep.java`）新增字段

```java
public List<String> acceptanceCriteria = new ArrayList<>();   // 新增：该步验收标准（判官核对清单）
public volatile String reportPath;                            // 新增：该步最近一次主AI汇总 spill 路径(null=内联)
```

`PlanStep.create(...)` 增加可选重载携带 `acceptanceCriteria`。

### 4.2 `PlanPersistence` + `L2State.PersistedPlanStep`

- `L2State.PersistedPlanStep` 加 `List<String> acceptanceCriteria`、`String reportPath`。
- `PlanPersistence.stepToPersisted/stepFromPersisted`（`PlanPersistence.java:65/83`）补两字段的双向拷贝（容错：旧快照无该字段 → 空列表/null）。

### 4.3 `planProgress`/`anchoredFacts` 降级为投影

- 不删字段（rotation/UI 仍读）。新增 **`PlanProjectionListener implements PlanStateListener`**：监听 `PlanStateMachine` 每次 transition，把当前 `Plan` 投影写入 L2：
  - `anchoredFacts.currentStep/totalSteps/currentStepTitle` ← `plan.currentStepIndex`、`steps.size()`、当前步 title
  - `planProgress[]` ← 各 `PlanStep` 的 `{index,status,filesChanged,attempts,lastError,completedAt}`
- 经 `L2Store.update(...)` 写穿（复用现有写盘 + .bak）。

### 4.4 `PersistedPlanStep.acceptanceCriteria` 进 `planProgress`？

不进。验收标准是**计划结构**的一部分，留在 `PersistedPlan`；`planProgress` 投影只表达进度。监督者读验收标准从 plan.md（见 §6.3）或 `PersistedPlan`。

---

## 5. 协议 / daemon 改动（`ai-bridge`）

### 5.1 新增 `emit_plan` MCP 工具（`ai-bridge/services/supervisor/plan-tools.js`，新文件）

与 `emit_action` 平级注册进 supervisor 的 in-process MCP server（`supervisor-channel.js:516` 的 `mcpServers`）。zod schema：

```js
// emit_plan —— 规划轮专用，一次性产出结构化计划
{
  steps: z.array(z.object({
    title: z.string().describe('步骤标题(一句话)'),
    owner: z.enum(['MAIN_AI', 'SUPERVISOR']).optional().describe('默认 MAIN_AI'),
    acceptanceCriteria: z.array(z.string()).describe('该步验收标准, 判官据此核验真实产物'),
  })).min(1).describe('结构化步骤; 把外部任务拆解, 不发明目标'),
  rationale: z.string().optional().describe('拆解思路(1-3句)'),
}
```

- handler 校验后 `onCapture(plan)` 写入 `runtime.lastCapturedPlan`。
- `postEventToSupervisor`（`supervisor-channel.js:565`）turn 结束后：若 `lastCapturedPlan` 非空，额外写一行 `[SUPERVISOR_PLAN] {pairId,supervisorId,turnId,steps,rationale}`（与 `[SUPERVISOR_ACTION]` 同 turnId）。
- allowedTools（`supervisor-channel.js:471`）+ `canUseTool`（:524）放行 `mcp__supervisor__emit_plan`。
- **模型不特殊处理**：规划轮跑 `runtime.model`（D6）。

### 5.2 接线 `update_state` 到本地 daemon（补欠债）

新增 `ai-bridge/services/supervisor/update-state-tool.js`，schema 对齐 `L2Store.applyUpdateStateDelta`，**但仅暴露 `fileStateDelta / decisionAppend / constraintAdd`**——`planProgressDelta / anchoredFactsDelta` **不再开放给监督者写**（D5：投影只读）。daemon 调用后写 `[STATE_UPDATE] {...}` 行，Java `SupervisorBridge` 既有 handler 落盘。

> 顺带把 `emit_action` 的 `decisions[]`（`supervisor-tools.js:112`）迁到 `update_state(decisionAppend)`，与 `2026-05-24` 文档对齐；保留 `decisions[]` 一个版本做兼容，prompt 引导用 decisionAppend。

### 5.3 主AI汇总 spill（`ai-bridge/services/claude/main-ai-tools.js`）

把 `report_turn_completion`（`main-ai-tools.js:66`）从"写死内联 `spilledPath:null`"改为**按大小托管 spill**：

```js
const payloadObj = { summary, deliverables, verifications, selfAssessment, durationMs };
const json = JSON.stringify(payloadObj);
let spilledPath = null;
if (json.length > SPILL_THRESHOLD /* 8KB, 对齐 inject_prompt 反向 spill */) {
  spilledPath = writeManagedReport(runtimeRef.sessionId, turnId, json); // 写 session 托管目录, 非仓库
}
process.stdout.write('[TURN_REPORT] ' + JSON.stringify({
  ..., payload: { ...(spilledPath ? {summary} : payloadObj), spilledPath }
}) + '\n');
```

- 托管目录：`~/.codemoss/pairs/<pairId>/reports/turn-<turnId>.json`（与既有 directive-spill 同机制；clean 随 pair 生命周期）。
- spill 时 NDJSON 行只留 `summary` + `spilledPath`，避免 IPC 行膨胀（复用 50KB cap 思路）。

---

## 6. Java 改动

### 6.1 `emit_plan` 落地：替换 `ensurePlanAndStep` 假计划

- `SupervisorBridge`/`PairHandler` 接 `[SUPERVISOR_PLAN]` 行 → 转 `List<PlanStep>`（带 acceptanceCriteria，owner 默认 MAIN_AI）→ `PlanStateMachine.onPlanCreated(steps, meta)`（`PlanStateMachine.java:67`）。
- `ActionRouter.ensurePlanAndStep`（`ActionRouter.java:1302`）**降级为兜底**：仅当 `sm.getCurrent()==null`（监督者没产出 plan）时才造单步，且打 WARN 日志。正常路径下不再触发。

### 6.2 规划轮触发：`[PLANNING_REQUIRED]` 标记

- 在事件进入 supervisor 前（Java 侧，握有 `PlanStateMachine`）：若 `pair.getPlanStateMachine().getCurrent()==null` 且本事件是**该 pair 的首条事件** → 给事件附 `planningRequired=true`。
- daemon `event-summarizer.js` 渲染时前置一段指令：
  > `[PLANNING_REQUIRED] 这是本任务首条事件。你的第一步：调用 emit_plan 把任务拆成结构化步骤(每步含 owner + 验收标准)，之后再开始监督。`
- 监督者 system prompt（`supervisor-channel.js:290 buildSystemPrompt`）补"规划协议"段：收到 `[PLANNING_REQUIRED]` 必先 `emit_plan`；计划锁定后改动走 `request_amendment`。

### 6.3 plan.md 投影渲染（监督者 resume 可 Read）

- 新增 `PlanMarkdownRenderer`：从 `Plan` 渲染 markdown（步骤 + 状态符 ✅/⏳/⬜ + 验收标准 + reportPath），写 `~/.codemoss/pairs/<pairId>/plan.md`。
- 由 `PlanProjectionListener`（§4.3）在每次 transition 后顺带重渲染——**单一真相源驱动，零漂移**，非仓库文件。

### 6.4 续跑 + IN_PROGRESS 对账

- 重启：既有 `PlanStateMachine.restore(persistedPlan)` 回灌（`PlanStateMachine.java:54`）。
- 监督者恢复后**首条事件**注入"恢复前言"（Java 从 restore 后的 `Plan` 合成）：
  > 你正在恢复。进度：step0 ✅ / step1 ⏳(执行中) / step2 ⬜ …
  > 规则：已 DONE 步信任不复查；未开始步不查；**对 step1(执行中) 先 Read 现场，对照其验收标准判断实际完成度（可能已部分完成），再决定：续做剩余 / 已达标则 mark_step_complete / 偏差大则重做**。
  > 附：plan.md=<path>；各步汇总 reportPath 见计划。
- 不需要新状态；IN_PROGRESS 来自 `PlanStep.status`，对账输入=`attempts/filesChanged/lastError` + 该步 `reportPath`。

### 6.5 DeadlockGuard 规划期豁免

- `DeadlockGuard.evaluatePendingDecisionLiveness()`：当 `plan==null`（规划尚未产出）→ **直接 return，不 arm R3**（D7）。与 ACTIVE/IDLE 设计一致：规划=非派单 ACTIVE。

### 6.6 turn-report → 挂步 + 投影

- 既有 `[TURN_REPORT]` handler：把 `spilledPath` 写入当前 IN_PROGRESS `PlanStep.reportPath`；`deliverables[].path` 并入 `PlanStep.filesChanged`（供投影 + 判官导航）。
- `selfAssessment.suggestedReview` 透传给监督者下一轮事件摘要（导航提示），但 prompt 仍强约束"须 Read 真实产物"（D4，沿用 `supervisor-channel.js:333`）。

---

## 7. 时序

### 7.1 规划

```
首条任务 ─Java(plan==null,首条)→ 附 planningRequired
  → daemon summarizer 前置 [PLANNING_REQUIRED]
  → supervisor(runtime.model) 规划轮 → emit_plan(steps+验收标准)
  → [SUPERVISOR_PLAN] → Java onPlanCreated → PersistedPlan(真相源)
  → PlanProjectionListener 投影 anchoredFacts/planProgress + 渲染 plan.md
  → 计划锁定; 进入执行循环
```

### 7.2 执行 + 汇总 + 核验

```
supervisor inject_prompt(step k, objective+验收标准) → 主AI 干活
  → report_turn_completion → daemon 按大小内联/spill → [TURN_REPORT](spilledPath?)
  → Java: spilledPath→PlanStep[k].reportPath, deliverables→filesChanged
  → 唤醒 supervisor: 事件摘要带 suggestedReview + spilledPath
  → 判官轮: Read(spilledPath) 导航 → 据 step k 验收标准 Read 真实产物核验
  → emit_action: mark_step_complete(k) / request_amendment / inject_prompt(补) 
  → 末步 complete_plan → Plan DONE → COMPLETION_REPORT.md(既有)
```

### 7.3 续跑

```
IDE 重启 → restore(PersistedPlan) → supervisor resume(resumeSessionId/rotation)
  → 首事件注入恢复前言(进度 + IN_PROGRESS 对账指令)
  → supervisor 对 IN_PROGRESS 步 Read 现场比对验收标准
  → 续做/标完成/重做; DONE/TODO 不动
```

---

## 8. 分期实施（每期独立可测）

| 期 | 内容 | 验收 |
|---|---|---|
| **P0** | `PlanStep.acceptanceCriteria/reportPath` + `PersistedPlanStep` + `PlanPersistence` 双向 + 旧快照容错 | `PlanPersistenceTest` 扩用例：含/不含新字段 round-trip 绿 |
| **P1** | daemon `emit_plan` 工具 + `[SUPERVISOR_PLAN]` 行 + Java 接 → `onPlanCreated`；`ensurePlanAndStep` 降兜底 | daemon 冒烟：规划轮产出 steps；`PlanStateMachineTest` 验真步骤取代合成步 |
| **P2** | `[PLANNING_REQUIRED]` 触发 + system prompt 规划协议段；D7 Guard 规划期豁免 | 首条事件触发 emit_plan；`DeadlockGuardTest` 加"规划期不 arm"用例 |
| **P3** | `PlanProjectionListener`（投影 anchoredFacts/planProgress）+ `PlanMarkdownRenderer`（plan.md）；本地接 `update_state`(仅 fileState/decision/constraint) | 投影与 Plan 一致单测；plan.md 渲染快照；update_state 写盘集成 |
| **P4** | 主AI `report_turn_completion` spill（`main-ai-tools.js`）+ Java `spilledPath→reportPath`、`deliverables→filesChanged` | 大汇总 spill / 小汇总内联 两路；挂步单测 |
| **P5** | 续跑恢复前言 + IN_PROGRESS 对账规则注入 | 重启场景集成：DONE 跳过 / IN_PROGRESS 触发对账 |
| **P6** | 协议清理：`decisions[]`→`decisionAppend` 迁移；handoff producer 去重 planProgress（改读投影/PersistedPlan） | rotation 集成不回归 |

> P0–P2 打通"生成"，P3 打通"统一/投影"，P4 打通"汇总落盘"，P5 打通"续跑"，P6 收尾欠债。

---

## 9. 测试清单

- 单测：`PlanPersistenceTest`(新字段)、`PlanStateMachineTest`(emit_plan 取代合成步 / 锁定后 amendment)、`DeadlockGuardTest`(规划期豁免)、`PlanProjectionListener`(投影一致性)、`PlanMarkdownRenderer`(渲染)、report 挂步。
- daemon 冒烟（Node vm，对齐既有 supervisor 冒烟）：emit_plan 捕获 + `[SUPERVISOR_PLAN]` 行；report spill 阈值两路。
- 集成：首条任务→规划→执行→核验→complete_plan 全链；IDE 重启续跑 + IN_PROGRESS 对账；rotation 不回归。

---

## 10. 风险 / 开放问题

1. **规划质量=新单点**：计划锁定后只能 amendment 纠。**把 amendment 频率当质量监控信号**；若高，回看规划轮 prompt/模型档位（但据 D6 用监督者既有模型，不在本期引入"规划专用模型"）。
2. **首条事件识别**：以"plan==null 且该 pair 首条事件"为准；workflow 首条由 handler 推送（`SupervisorWorkflowManager` startNode → assembleAndWritePlan 产物作规划输入）。需确认 workflow 与单页两路都只触发一次。
3. **rotation 与投影**：unification 后 `PersistedPlan` 已随 L2 落盘且 restore 可读，handoff doc 的 planProgress 变冗余（P6 去重）。需确认继任水合优先 `PersistedPlan`、handoff doc 仅承载软上下文（decisions/fileState/constraints）。
4. **L2 schema 兼容**：新增字段走"旧快照缺字段→默认值"容错（`PlanPersistence`/`L2Migration`）。
5. **assembleAndWritePlan 角色转变**：其产物（节点任务+上游摘要）从"注入 system prompt 的锁定答案"改为"喂规划轮的原材料"，需确认 workflow 路径改接入点（plan 不再直接进 system prompt 锁定段，而由 emit_plan 结构化后经 plan.md 投影）。

---

## 11. 不做（本期 Non-Goals）

- 规划专用模型/effort 自动抬档（D6 明确否决）。
- 计划"可演进/在线重规划"（D1：锁定 + amendment）。
- 把汇总文件落进项目仓库（D3：daemon 托管，非仓库）。
- workflow 编排本身的改动（沿用 `2026-06-01` 方案；本期仅改"节点 plan 如何被监督者消费"）。
