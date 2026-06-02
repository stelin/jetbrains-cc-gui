# 监督者编排工作流（Supervisor Workflow Orchestration）设计方案

> 状态：**讨论已收敛，待实施**（2026-06-01）
> 目标：在本地模式和远程模式下，新增一个"监督者编排工作流"——把多个"监督者 + 主AI"节点按依赖关系（DAG）串/并行编排执行，每个节点对应一个对话 tab。
> 关联代码：`session/pair/PairSessionManager.java`、`session/pair/plan/PlanStateMachine.java`、`session/pair/guard/DeadlockGuard.java`、`handler/PairHandler.java`、`handler/TabHandler.java`、`action/tab/CreateNewTabAction.java`、`settings/SupervisorAgentManager.java`、`bridge/SupervisorBridge.java`、`webview/src/components/SupervisorPair/`
> 关联文档：`docs/supervisor/supervisor-state-machine-design.md`（前置）、`docs/feat/rewind-feature-design.md`

---

## 1. 背景与需求

### 1.1 现状

- 一个 **pair = 一个监督者(supervisor) + 一个主AI(main AI)**，由 `PairSessionManager`（`@Service(PROJECT)` 单例）用 `ConcurrentHashMap<pairId, PairSession>` 管理——**天然支持多个 pair 并存**。
- 每个 pair 通过 `PairSession.ownerWindowId` 绑定到一个 tab（`StartPairParams.ownerWindowId`，`PairSessionManager.java:141`）。监督者在前端不是独立 tab，而是主 tab 的**右侧分栏**（`PairLayout`：左主AI / 右监督者）。
- pair 的生命周期由 `PlanStateMachine` 管理：`INIT → ACTIVE → DONE / WAITING / ABORTED`。完成信号明确：`onPlanCompleted()` → `DONE`（`PlanStateMachine.java:310`），并通过 `wireCompletionReportListener()`（`PairSessionManager.java:944`）在 DONE 时生成 `COMPLETION_REPORT.md`。
- 监督者业务逻辑全在 **Java 层**，通过 `IBridge` 抽象屏蔽 local(daemon.js, stdin/stdout) 与 remote(ai-bridge-server, HTTP/SSE) 的差异。
- 监督者目前**单独手动创建**：用户在某个 tab 里手动选监督者 + 起 pair，一个监督者配一个主AI。

### 1.2 需求

新增一个"监督者编排工作流"：
- 每个节点 = 一个监督者 + 一个主AI = 一个对话 tab；节点取名 = tab 名。
- 节点之间按依赖关系串/并行执行。

### 1.3 目标场景

```
节点A(监督者A：方案A编写)  ─┐
                            ├─→  节点C(监督者C：接口测试)
节点B(监督者B：方案B编写)  ─┘
A、B 无依赖 → 并行执行；A、B 都完成 → 才进入 C
```

---

## 2. 核心设计

新增一个 **per-project 的 Java 编排服务 `SupervisorWorkflowManager`**，**坐在 `PairSessionManager` + tab 基础设施之上**，本质只做四件事：

```
持有 DAG 定义 → 解析依赖 → 满足依赖就"开节点" → 监听节点 DONE → 推进下游
```

**关键架构决策：编排逻辑留在 Java 层，不下沉到 daemon.js。**
理由：daemon 是 per-pair 的，无 tab / 跨 pair 协调概念；Java 已握有 tab(UI)、`PairSessionManager`、`PlanStateMachine` 完成监听器三样东西。放 Java 层 = local/remote **零分叉**，差异由底层 `IBridge` 吃掉。

**"开一个节点"= 一条已有通路的组合，零新协议：**
1. 程序化创建 tab（命名为节点名）——复用 `CreateNewTabAction` / `TabHandler.handleCreateNewTab` 的建 tab 逻辑（`new ClaudeChatWindow(project, true)` + `ContentFactory.createContent(window.getContent(), nodeName, false)` + `contentManager.addContent`）。
2. 组装该节点的**有效 plan**（本节点 plan + 上游产出摘要，见 §6），写成 plan 快照。
3. 在该 tab 的主 session 上起 pair：`PairSessionManager.startPair(StartPairParams, sdkBridge)`，入参 `(mainSessionId, agentId, planPath, modelOverride, longContextOverride, reasoningOverride, ownerWindowId)`——**节点配置几乎一对一映射到这个已有入参**（`PairSessionManager.java:143`）。
4. 给该 pair 的 `PlanStateMachine` **多挂一个监听器**：`DONE` → 标记节点完成、推进下游；`WAITING`(非 user-pause 的 escalate) → 弹窗"需要人工"；`ABORTED` → 节点中止。

---

## 3. 已锁定的设计决策

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 编排逻辑落点 | Java 层 `SupervisorWorkflowManager`，**不下沉 daemon**，local/remote 共用 |
| D2 | 节点↔tab↔pair | 一节点 = 一 tab = 一 pair；节点名 = tab 名 |
| D3 | 上下文握手 | **(a) 共享文件系统 + (b) 注入上游完成摘要** 混合（§6） |
| D4 | 失败模型 | **不重试、不回滚**；失败 → 阻塞等人 → 人在该节点对话框处理到 DONE → **自动推进下游**（§7） |
| D5 | 失败检测来源 | ① 监督者自己上报（emit `escalate_to_human`）② 看门狗（`SupervisorMonitor` + `DeadlockGuard`）——两者都收敛到 `WAITING` |
| D6 | 看门狗终态动作 | escalate to human（**`DeadlockGuard` 已移除 `system_takeover`，现成行为**，`DeadlockGuard.java:242`） |
| D7 | checkpoint/rewind | **不做**（无重试就不需要回滚干净起点） |
| D8 | "需要人工"提示 | **弹窗** + 总览面板标 ⚠ + 自动切到该节点 tab |
| D9 | 并发上限 | 工作流内部 **最多 2 个并发节点**；手动 pair 不计数、不管 |
| D10 | 调度方式 | **滚动窗口**（一个完腾出槽位，下一个就绪节点补进来），非严格按层 ⚠️*见 §16 待确认* |
| D11 | 阻塞节点占不占槽 | **占**（一直占到人把它救到 DONE，全程最多 2 个活跃节点，注意力可控） |
| D12 | 单工作流锁 | 全局同时只有 1 个工作流在跑；上一个结束才能开下一个 |
| D13 | tab 生命周期 | 节点完成 **tab 保留不关**（要的就是能回看）；工作流跑完所有 tab 保留 |
| D14 | 工作流终态 | 只有"全部跑完(COMPLETED)"或"用户手动中止(ABORTED)"两种；**无自动 FAILED**（一切卡住都 funnel 到人工） |
| D15 | "标记完成并继续"逃生口 | **不加**；严格只认监督者 emit 的 DONE |
| D16 | 上下文注入粒度 | 上游 `COMPLETION_REPORT.md` **摘要** + 改动文件清单（非全文，避免 join 节点撑爆上下文） |
| D17 | IDE 重启续跑 | MVP **不做断点续跑**；重启后标"已中断"，用户从已完成节点之后重启 |
| D18 | 总览面板 | 常驻控制中心，画 DAG + 节点状态；tab 懒创建（节点启动时才弹） |

---

## 4. 数据模型

### 4.1 工作流定义（持久化，用户编辑）

```
WorkflowDefinition {
  id            : String
  name          : String
  nodes         : List<WorkflowNode>
}

WorkflowNode {
  name          : String          // = tab 名，工作流内唯一
  supervisorId  : String          // 复用 SupervisorAgentManager 的 agentId
  plan          : String          // 该节点任务（markdown），监督者据此驱动主AI
  model         : String?         // → StartPairParams.modelOverride
  longContext   : Boolean?        // → StartPairParams.longContextOverride
  reasoning     : String?         // → StartPairParams.reasoningOverride
  dependsOn     : List<String>    // 依赖的节点名 → 决定串/并行
}
```

### 4.2 运行态（持久化，运行时维护）

```
WorkflowExecution {
  workflowId    : String
  state         : EDITING | RUNNING | COMPLETED | ABORTED
  nodes         : Map<nodeName, NodeRuntime>
}

NodeRuntime {
  status        : PENDING | READY | RUNNING | WAITING_HUMAN | DONE
  pairId        : String?         // 运行后由 startPair 返回
  windowId      : String?         // 该节点 tab 的 windowId
  completionReportPath : String?  // DONE 后的 COMPLETION_REPORT.md
}
```

### 4.3 节点状态机

```
PENDING(依赖未满足)
   → READY(依赖满足，排队等并发槽)
   → RUNNING(已建 tab + 起 pair)
   → WAITING_HUMAN(escalate/卡死 → 弹窗，⚠待人工)  ←→  RUNNING(人工恢复)
   → DONE(监督者 emit complete_plan / 全 step 完成)
```

### 4.4 工作流状态机

```
EDITING → RUNNING → COMPLETED(所有汇聚节点 DONE)
                  → ABORTED(用户手动中止)
```

> 没有自动 FAILED 终态（D14）。

---

## 5. 编排器 `SupervisorWorkflowManager`

`@Service(Service.Level.PROJECT)`，与 `PairSessionManager` 平级，持有对它的引用。

### 5.1 职责

- 持有当前 `WorkflowDefinition` + `WorkflowExecution`（**单工作流锁**，D12）。
- 调度：维护就绪队列 + **`Semaphore(2)`**（D9/D10）；节点 DONE 时释放槽、把新满足依赖的节点入队。
- 对每个运行中的节点 pair 的 `PlanStateMachine` 挂监听器，按下述契约推进。

### 5.2 推进契约（编排器挂的监听器）

监听 `PlanStateMachine.addListener((oldState, oldSub, now) -> ...)`：

| `now.state` | 含义 | 编排器动作 |
|---|---|---|
| `DONE` | 节点完成 | 标记节点 DONE、记录 `COMPLETION_REPORT.md` 路径、**释放并发槽**、检查下游哪些 `dependsOn` 已全满足 → 入就绪队列；若全部汇聚节点 DONE → 工作流 COMPLETED |
| `WAITING` (排除 `pauseReason="user"`) | escalate / 看门狗判卡死 | 节点置 `WAITING_HUMAN`、**弹窗**提示、总览标 ⚠、自动切到该 tab；**不释放槽**（D11）、**不推进下游** |
| `ABORTED` | 节点被中止 | 视为用户中止该节点；不自动推进（人工决定继续或中止整流程） |

> **关键简化**：人工在该节点对话框里把问题处理好后，节点会照常走到 `DONE`，触发上面第一行的同一条 DONE 路径——**自动推进下游**，无需额外按钮（D4/D15）。

### 5.3 调度伪码（滚动窗口）

```
startWorkflow(def):
  if (current != null && current.state == RUNNING) reject("已有工作流在运行")  // D12
  exec = init(def); exec.state = RUNNING
  enqueueReady()    // 入度为0的节点 → READY 队列
  pump()

pump():               // 在 sem 有空位时尽量开节点
  while (sem.tryAcquire() && readyQueue.nonEmpty()):
     node = readyQueue.poll()
     startNode(node)  // 建 tab + 组装 plan + startPair + 挂监听器

onNodeDone(node):
  node.status = DONE
  sem.release()       // 腾槽（D11：仅 DONE 腾，WAITING 不腾）
  for d in downstream(node): if depsAllDone(d): readyQueue.add(d)
  if allSinkNodesDone(): exec.state = COMPLETED
  else: pump()
```

---

## 6. 上下文握手（D3 / D16）

节点启动时，编排器组装**有效 plan**：

```
<本节点 plan 原文>

## 上游产出
### 节点A（监督者A）
<A 的 COMPLETION_REPORT.md 摘要>
改动文件：path/a.go, path/b.go ...

### 节点B（监督者B）
<B 的 COMPLETION_REPORT.md 摘要>
改动文件：...
```

- **(a) 共享文件系统**：所有节点共享同一工作目录，上游写出的真实代码/文件，下游主AI 直接读。
- **(b) 注入摘要**：把每个上游 `COMPLETION_REPORT.md` 的**摘要**（非全文）+ 改动文件清单拼进下游 plan。
- 组装后写成 plan 快照，经 `StartPairParams.planPath` 喂入——**复用现有通路，零协议改动**。
- join 节点（C 依赖 A+B）注入两份摘要；用摘要不用全文，避免上下文膨胀（监督者 1M 长上下文兜底）。

---

## 7. 失败 / 转人工模型（D4–D7）

**不重试、不回滚，阻塞 → 人工 → 自动续跑。** 几乎完全复用现有机制：

| 语义 | 复用的现有机制 |
|---|---|
| 节点出问题 → 阻塞等人 | `escalate_to_human` → `PlanStateMachine.onEscalatedToHuman()` → `WAITING`（`PlanStateMachine.java:185`） |
| 人在对话框里处理 | 主AI 对话 + 监督者右栏（节点 tab 本身就是工作区） |
| 处理完自动进下一节点 | 人把节点带到 `DONE` → 编排器 DONE 监听器照常触发 → 推进下游 |

**两个失败来源都收敛到 `WAITING`**：
1. **监督者自己上报**：emit `escalate_to_human`（现成动作，无需新增）。
2. **看门狗**：`SupervisorMonitor`（30s 健康检查）+ `DeadlockGuard`（R1/R2/R3 deadline）。
    - ✅ **`DeadlockGuard` 已移除 `system_takeover`**，到点直接 escalate to human（`DeadlockGuard.java:242`、395-449 注释），这正是工作流要的行为——**guard 层无需再改**。

> 因为不重试，**checkpoint/rewind 不做**（D7）。

---

## 8. tab 生命周期（D13）

- 节点完成后 tab **保留不关**，供回看。
- 工作流跑完，所有 tab 保留，用户逐个 review。
- tab 懒创建：节点启动那一刻才弹（D18），动态出现。
- 命名冲突：节点名与手动 tab（AI1/AI2，`ClaudeSDKToolWindow.getNextTabName`）撞名时自动去重。

---

## 9. 本地 / 远程模式

- 编排服务在 Java 层（D1）→ 两种模式**共用同一套代码**，差异由 `IBridge`（LocalBridge / RemoteBridge）吃掉。
- 工作流定义 + 运行态：像 L2 一样**本地持久化**（建议 `~/.codemoss/workflows/<id>/`：`definition.json` + `execution.json`），Java 持有，两种模式一致。
- 待验证：远程模式同样支持多 tab / 多 pair 并存（pair 系统两端都跑，tab 是纯 IDE-UI 概念，理论上都支持）。

---

## 10. 持久化与续跑

- `definition.json`：工作流拓扑、各节点配置。
- `execution.json`：节点状态、pairId/windowId 映射、各节点 `COMPLETION_REPORT.md` 路径。
- **续跑（D17）**：MVP 不做。IDE 重启后 pairs/tabs 都没了，把 RUNNING 工作流标"已中断"，让用户从已完成节点之后重启。续跑放 v-next。

---

## 11. UI

- **工作流总览面板**（常驻控制中心，D18）：画 DAG + 各节点状态（PENDING/READY/RUNNING/⚠WAITING_HUMAN/DONE）；提供"开始/中止工作流"；点节点可聚焦/切到其 tab。落点建议：一个常驻（不可关）的首 tab 或独立 tool window。
- **工作流编辑器**：增删节点、设监督者/plan/模型、连依赖边。MVP 可先用 JSON 配置起步，可视化编辑器后置。
- 复用现成的 `onPairEscalate` 弹窗能力做"需要人工"提示（D8）。

---

## 12. 与现有代码的集成点

| 集成动作 | 现有 API / 位置 |
|---|---|
| 起 pair | `PairSessionManager.startPair(StartPairParams, sdkBridge)`（`PairSessionManager.java:186`） |
| 节点配置映射 | `StartPairParams(mainSessionId, agentId, planPath, modelOverride, longContextOverride, reasoningOverride, ownerWindowId)`（`:143`） |
| 完成/转人工监听 | `PlanStateMachine.addListener(...)`，参照 `wireCompletionReportListener`（`PairSessionManager.java:944`） |
| 完成信号 | `Plan.PlanState.DONE`；`onPlanCompleted()`（`PlanStateMachine.java:310`） |
| 转人工信号 | `WAITING`；`onEscalatedToHuman()`（`PlanStateMachine.java:185`） |
| 上游产出 | `COMPLETION_REPORT.md`（`CompletionReportWriter`，DONE 时自动产出） |
| 程序化建 tab | `TabHandler.handleCreateNewTab` / `CreateNewTabAction`（`new ClaudeChatWindow(project, true)` + `ContentFactory.createContent(...)` + `contentManager.addContent`） |
| 监督者配置 | `SupervisorAgentManager`（`supervisor-agents.json` 的 CRUD） |
| 看门狗（已就绪） | `SupervisorMonitor` + `DeadlockGuard`（已无 takeover，直接 escalate） |

---

## 13. 前置依赖

- `docs/supervisor/supervisor-state-machine-design.md`（监督者显式 `complete_plan` + ACTIVE/IDLE + 看门狗别乱 takeover）。
    - 编排强依赖"DONE 信号可靠"。**部分已落地**：`onPlanCompleted()`(`complete_plan`)、`DeadlockGuard` 去 takeover 都在代码里了。
    - **接口对齐项**：编排器到底监听哪个事件判 DONE，需与该工作的最终落地形态对齐（由另一对话处理，待其确定后回填本节 §16）。

---

## 14. 分期实施计划

- **P0（前置）**：确认监督者显式完成（`complete_plan`/IDLE）信号最终形态，保证 DONE 可靠。
- **P1**：`SupervisorWorkflowManager` + 数据模型 + 本地持久化；**先做线性链 A→B→C**；打通"程序化建 tab + 组装 plan + startPair + 挂监听器"非 UI 触发链路。
- **P2**：DAG 并行扇出/汇合（依赖边）+ `Semaphore(2)` 滚动窗口调度 + 单工作流锁。
- **P3**：上下文握手——注入上游 `COMPLETION_REPORT.md` 摘要 + 改动文件清单。
- **P4**：失败/转人工——`WAITING` → 弹窗 + 总览 ⚠ + 自动切 tab；人工处理到 DONE 自动续跑。
- **P5**：UI——工作流总览面板 + 编辑器（先 JSON 配置，再可视化）。

---

## 15. 开放问题

- **Q1（接口对齐）**：编排器监听的"节点完成"事件，与 supervisor-state-machine 工作的最终形态对齐（另一对话处理中）。
- **Q2（节点超时阈值）**：除监督者自报 + 看门狗外，是否给节点设一个"整体超时"兜底？阈值多少？（倾向：先不设，靠看门狗的 R3 deadline 兜，观察后再加。）
- **Q3（远程多 tab 验证）**：远程模式多 tab/多 pair 并存的实测确认。

---

## 16. 待确认（开工前请拍板）

- **D10 滚动窗口 vs 严格按层**：本方案默认**滚动窗口**（一个完即补下一个就绪节点，仍满足"同时最多 2 个"）。前两轮讨论未见异议，如需"严格按层"（整层做完才开下层）请在此圈出。
