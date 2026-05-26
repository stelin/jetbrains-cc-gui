# Pair Contract State Machine 编码方案

**日期**: 2026-05-25
**作者**: Claude Code 协作
**状态**: 待实施
**关联文档**:
- `docs/plans/2026-05-21-supervisor-pair-agent-implementation.md` (Pair Agent 基础)
- `docs/plans/2026-05-23-supervisor-monitor-rotation-implementation.md` (Monitor + Rotation)
- `docs/plans/2026-05-24-supervisor-autonomous-collab-implementation.md` (自主协作)

---

## 一、背景

### 1.1 问题现场

远程模式下开启 supervisor 运行一段时间后出现:
- 主 AI 上一轮 turn 正常结束(输出"请发送下一步(Step13+)的模块规格,我将立即按上述并发策略派发")
- supervisor 自治模式下不再下发新指令
- 用户手动触发 supervisor 后,supervisor 下发 Step 13 task_assignment
- 主 AI 收到后**5 分钟一动不动**
- 触发 `MainAIMonitor.onStallFire` 告警(`MainAIMonitor.java:363`)

### 1.2 三种失败模式与根因

| 失败模式 | 现象 | 根因 |
|---|---|---|
| **F1 对话漂移** | 主 AI 以"请发送...""我将立即..."等等待型语句结尾,把执行点挂到对方下一句话上 | 主 AI 系统提示词没有硬约束"每个 turn 必须以 tool_use / report_turn_completion 结尾",模型默认走对话姿势 |
| **F2 双向静默死锁** | 主 AI 静默 → EventCollector 空 → 30s tick 喂给 supervisor 的 composite_summary 是空 → supervisor 看空内容自然 idle → 循环 | `SupervisorMonitor.java:55` 30s 周期 tick **无条件触发**;`supervisor-channel.js:529` `lastActivityAt` 每次成功 tick 都更新,导致 health 看起来"活着" |
| **F3 传输层伪静默** | POST /in 失败但调用方拿不到错误;`handleRequestOutput` 静默丢响应 | `RemoteBridge.java:464-493` `postIn` fire-and-forget,失败仅 warn;`RemoteBridge.java:434-440` 找不到 RequestState 静默丢弃 |

### 1.3 架构层根本错误

```
当前架构: LLM 驱动事件循环
  ↓
LLM 是非确定性 worker,放在事件循环最关键位置
  ↓
任何 prompt 修补 / nudge 兜底都只能降低概率,无法根除

正确架构: 状态机驱动 + LLM 当 worker
  ↓
状态机(确定性)决定"何时唤醒谁"
  ↓
LLM 只回答"现在做什么",不决定"是否该继续"
```

### 1.4 当前已有的 coordinator-shaped 组件清单

不是缺协调者,是协调者**不是 plan 的 owner**。当前 plan 状态在 supervisor LLM context 里,Java 这边只是事后快照到 `L2State.PlanProgressEntry`(supervisor 调 save_plan 才更新):

| 组件 | 当前角色 | 文件 |
|---|---|---|
| `PairCoordinator` | RWLock 守门(IDLE/MAIN_TURN/TICK/ROTATING) | `pair/PairCoordinator.java:28` |
| `SupervisorMonitor` | 30s 周期 tick + drain + composite_summary | `pair/SupervisorMonitor.java:51` |
| `DirectiveTracker` | inject_prompt 的 3s/5min 超时跟踪 | `pair/DirectiveTracker.java:23` |
| `EventCollector` | 200 事件环形缓冲 | `pair/EventCollector.java:29` |
| `RotationDecider` | 1s poll rotation 标志 | `pair/rotation/RotationDecider.java:30` |
| `PairBudgetTracker` | 80%/100% 预算阈值 | `pair/PairBudgetTracker.java:13` |
| `MainAIMonitor` | 5min stall + turn 边界 hook | `pair/MainAIMonitor.java:45` |
| `ActionRouter` | 翻译 supervisor action | `pair/ActionRouter.java:30` |
| `L2State.PlanProgressEntry` | post-hoc 快照 | `l2/L2State.java` |

### 1.5 演进目标

把当前 **"周期性 job + LLM 驱动循环"** 模型重构为 **"Contract 状态机驱动 + LLM 当 worker + 队列吸收时序"** 模型,达到:

1. plan 状态的**权威所有者**从 supervisor LLM context 迁移到 Java `PlanStateMachine`
2. 一切跨 session 的消息(supervisor→主AI / 主AI→supervisor)都包装成 `Contract`,带 `contractId` 和 deadline
3. 取消 SupervisorMonitor 的 30s 固定周期触发,改为 **Contract 状态变化驱动**
4. **不允许任何自动中断 LLM 执行**;一切 retry 通过**队列入队**实现
5. 明确区分 **"plan 完成的正常空闲"** 和 **"plan 进行中的死锁"**,前者不告警
6. 消息重推使用 **retry-with-marker**(不做幂等 dedup),配合 L2State 兜底防重复执行
7. 治理体系不变:R3 升级路径复用现有 `escalate_to_human`

---

## 二、决策记录(已拍板)

### 2.1 协议层决策

| 编号 | 项 | 决策 |
|---|---|---|
| D1 | Plan 状态所有者位置 | **plugin Java side**,持久化到 `L2State`,IDE 重启从 L2 恢复 |
| D2 | Contract 粒度 | **plan-step 一个主 contract**,下面挂 sub-contract(retry / nudge 都是 sub) |
| D3 | DirectiveTracker 处理 | **新写 `ContractRegistry`**,DirectiveTracker 退役(不做 dual-write 兼容) |
| D4 | SupervisorMonitor 改造 | 删 `scheduleWithFixedDelay(30s)`,职责拆出独立 watcher(`HealthWatcher` / `BudgetWatcher` / `RotationWatcher`) |
| D5 | 迁移策略 | **激进路径**,一次切换,旧代码删除 |
| D6 | 中断策略 | **零自动中断**;LLM 正在执行/思考时新消息一律**入队**等当前 turn 结束 |
| D7 | 消息重推 | **retry-with-marker** 不做幂等 dedup;同 `parentStepId` 入队前去重(Java 层);LLM prompt 兜底("如果看到重复消息,以最后一条为准") |
| D8 | 死锁告警条件 | 仅在 `plan.state == ACTIVE && subState == PENDING_DISCHARGE && !anyTurnInProgress && idle > deadline` 时触发 |
| D9 | R3 升级路径 | 给 supervisor 队列入一条 **system message**,内容是"决策一下:reissue / skip / abort / escalate_to_human";supervisor 按治理等级自决 |
| D10 | System message 入队支持 | daemon `supervisor-channel.js:481-489` 改造支持 `role: 'system'` |
| D11 | HealthWatchdog | **不建独立组件**,合入 `PairStatusPusher`(5 行规则);SDK 静默 30min 阈值 → status `CRITICAL` |
| D12 | 队列容量 | **无界**(与现有 `inputStream` 一致) |
| D13 | Contract 持久化粒度 | 每次状态变化即写 L2(不批量),换数据安全 |
| D14 | Rotation 时 contract 继承 | 新 supervisor 接班时**重新评估**当前 open contracts(不直接继承 ack 状态),走"我是新接班的,我看到 plan 在 step X 卡着"流程 |

### 2.2 升级路径决策

| 编号 | 项 | 决策 |
|---|---|---|
| E1 | R1 触发条件 | contract deadline 到 + 接收方无 in-progress turn + 队列中无同 parentStepId pending retry |
| E2 | R2 触发条件 | R1 入队后 contract 仍未 discharge(再过一个 deadline 周期) |
| E3 | R3 触发条件 | R2 后仍未 discharge,supervisor 队列入 system message 要求决策 |
| E4 | retry 上限 | `maxRetries = 2`(R1 + R2),超过走 R3;R3 走完仍未恢复 → `ESCALATED` 终态 |
| E5 | deadline 默认值 | 10 分钟(可由 supervisor 在 issue contract 时覆写) |
| E6 | grace period | R1/R2 触发时如发现接收方刚刚开始 turn(<30s),延后入队 |

### 2.3 数据持久化决策

| 编号 | 项 | 决策 |
|---|---|---|
| P1 | Plan 持久化时机 | 状态变化即写;step 切换、contract issue/discharge、retry 等所有变更点 |
| P2 | Contract 持久化时机 | 同 P1 |
| P3 | L2 schema 版本 | 新增 `schemaVersion: 2`,旧 v1 加载时自动升级(无 plan 字段则初始化为空) |
| P4 | IDE 重启恢复行为 | 加载 L2 → 重建 PlanStateMachine + ContractRegistry → 已超时的 contract 立即触发 R1(不等下一次 deadline 周期) |
| P5 | daemon 重启恢复行为 | daemon 内存无状态;plugin 端通过 `supervisor.start` 重建 supervisor session;ContractRegistry 端的 open contracts 全部走 R1 重推 |

---

## 三、架构总览

### 3.1 新架构拓扑

```
┌─ Pair (pairId 永久身份) ─────────────────────────────────────────────┐
│                                                                       │
│  ┌─ Java 侧 ──────────────────────────────────────────────────────┐   │
│  │                                                                │   │
│  │  ╔═══════════════════════════════════════════════════════╗     │   │
│  │  ║  PlanStateMachine (权威所有者)                          ║     │   │
│  │  ║  state: INIT/ACTIVE.{EXEC/PEND_DISCHARGE/PEND_DECISION}║     │   │
│  │  ║         WAITING/DONE/ABORTED                          ║     │   │
│  │  ║  steps[]: PlanStep                                    ║     │   │
│  │  ║  currentStepIndex: int                                ║     │   │
│  │  ╚═══════════════════════════════════════════════════════╝     │   │
│  │                ↓ owns                                          │   │
│  │  ╔═══════════════════════════════════════════════════════╗     │   │
│  │  ║  ContractRegistry                                      ║     │   │
│  │  ║  open[]: Contract                                     ║     │   │
│  │  ║  closed[]: Contract (rolling, max 200)                ║     │   │
│  │  ║  deadlineScheduler: ScheduledExecutorService          ║     │   │
│  │  ╚═══════════════════════════════════════════════════════╝     │   │
│  │     ↑ 创建/discharge       ↓ deadline 到 / 状态变化            │   │
│  │     │                       ↓                                  │   │
│  │  ┌──┴──────────────────┐ ┌──┴──────────────────────┐           │   │
│  │  │ DeadlockGuard       │ │ TransitionDispatcher    │           │   │
│  │  │ (30s 自检,纯入队)    │ │ (决定唤醒哪个 session)   │           │   │
│  │  │ R1 → R2 → R3        │ │ 仅在 PENDING_DECISION   │           │   │
│  │  └──┬──────────────────┘ │ 或 R3 时唤醒 supervisor │           │   │
│  │     │                    └─────┬───────────────────┘           │   │
│  │     │                          │ enqueue                       │   │
│  │     ▼                          ▼                               │   │
│  │  ┌─────────────────────────────────────────────────┐           │   │
│  │  │ ActionRouter (改造:Contract dedup + retry)       │           │   │
│  │  │ ClaudeMessageHandler (改造:onComplete 检查      │           │   │
│  │  │                       discharge,未 discharge   │           │   │
│  │  │                       自动入队 retry)             │           │   │
│  │  │ SupervisorBridge (改造:出口加 contractId)        │           │   │
│  │  └─────────────────────────────────────────────────┘           │   │
│  │                                                                │   │
│  │  ┌─────────────────────────────────────────────────┐           │   │
│  │  │ PairStatusPusher (合入 HealthWatchdog 规则)     │           │   │
│  │  │   if (sdkInactiveMs > 30min) status = CRITICAL  │           │   │
│  │  └─────────────────────────────────────────────────┘           │   │
│  │                                                                │   │
│  │  ┌─────────────────────────────────────────────────┐           │   │
│  │  │ 拆分自原 SupervisorMonitor:                       │           │   │
│  │  │   HealthWatcher  (supervisor.health ping)        │           │   │
│  │  │   BudgetWatcher  (token/duration 阈值)            │           │   │
│  │  │   RotationWatcher (context ratio 触发 rotation)  │           │   │
│  │  └─────────────────────────────────────────────────┘           │   │
│  │                                                                │   │
│  │  ┌─────────────────────────────────────────────────┐           │   │
│  │  │ L2Store (扩展 schema v2,加 plan 持久化)          │           │   │
│  │  │   ~/.codemoss/pairs/<pairId>/state.json          │           │   │
│  │  │     + plan: {...}                                │           │   │
│  │  │     + openContracts: [...]                       │           │   │
│  │  └─────────────────────────────────────────────────┘           │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  ┌─ Daemon 侧 ───────────────────────────────────────────────────┐    │
│  │  supervisor-channel.js (改造):                                 │    │
│  │   - postEvent 支持 system message role                        │    │
│  │   - 其他保持(start / stop / health / interrupt / produceHandoff)│   │
│  │  其他基础设施(IPC / spill / SDK hook)不变                       │    │
│  └───────────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────────┘

事件流(从前) vs 现在:
  从前: ClaudeMessageHandler → publish event → EventCollector ring →
        SupervisorMonitor 30s tick drain → composite_summary →
        supervisor SDK → action → ActionRouter → inject_prompt → 主 AI
  
  现在: 主 AI turn 结束 → onComplete 检查 contract discharge:
            ├ 已 discharge → PlanStateMachine 推进
            └ 未 discharge → ContractRegistry 自动入队 retry
        deadline 触发 → DeadlockGuard.tick → R1/R2/R3 入队
        PENDING_DECISION → TransitionDispatcher.wake(supervisor)
        所有事件→queue,LLM自然消费,无中断
```

### 3.2 与旧架构的关键差异

| 维度 | 旧 | 新 |
|---|---|---|
| plan 所有者 | supervisor LLM context | Java `PlanStateMachine`(确定性) |
| supervisor 触发 | 每 30s 必触发 | 仅在 PENDING_DECISION / R3 时触发 |
| 空闲状态 | 含糊("都在等") | 明确 5 种(EXEC/PEND_DISCHARGE/PEND_DECISION/WAITING/DONE) |
| 超时处理 | 仅 inject_prompt 5min 直接报 `directive_lost` | 所有 contract 都有 deadline,R1→R2→R3 自动升级 |
| 中断能力 | `MainAIMonitor.StallDetector` 5min 触发告警 | **无自动中断**,队列吸收 |
| 接收方在 turn 中 | 需要复杂 turnInProgress 检查 | 入队即可,turn 完了自动消费 |
| 死锁判定 | 双方都不说话 5min | plan ACTIVE + PENDING_DISCHARGE + 接收方 idle + deadline 到 |
| rotation 接班 | 新 supervisor 读 L2 快照 | 新 supervisor **重新评估** open contracts |

---

## 四、核心数据结构

### 4.1 Plan & PlanStep

```java
// pair/plan/Plan.java (新增)
public class Plan {
    private final String id;                       // plan_<pairId>_<ts>
    private final String pairId;
    private volatile PlanState state;              // INIT / ACTIVE / WAITING / DONE / ABORTED
    private volatile ActiveSubState subState;      // 仅 state == ACTIVE 时有效
    private final List<PlanStep> steps;            // 顺序步骤
    private volatile int currentStepIndex;
    private final long createdAt;
    private volatile long lastTransitionAt;
    private final Map<String, Object> metadata;    // 模型、autonomy level、模板等

    public enum PlanState {
        INIT,           // supervisor 未生成 plan
        ACTIVE,         // 有未完成 step
        WAITING,        // 显式等待外部(escalate / human input)
        DONE,           // 所有 step 完成
        ABORTED         // 用户取消 / 不可恢复
    }

    public enum ActiveSubState {
        EXECUTING,            // 某方 turn 在跑
        PENDING_DISCHARGE,    // 有 open contract,无人在跑 ★ 死锁高危区
        PENDING_DECISION      // 无 open contract,有未完 step → 需 supervisor 出新 step
    }
}

// pair/plan/PlanStep.java (新增)
public class PlanStep {
    private final String id;                       // step_<plan_id>_<index>
    private final String title;
    private final int index;
    private volatile StepOwner owner;              // SUPERVISOR / MAIN_AI
    private volatile StepStatus status;
    private final List<String> contractIds;        // 该 step 关联的所有 contract
    private final long createdAt;
    private volatile long completedAt;
    private volatile int attempts;
    private volatile String lastError;
    private final List<String> filesChanged;       // 兼容 L2.PlanProgressEntry

    public enum StepOwner { SUPERVISOR, MAIN_AI }

    public enum StepStatus {
        TODO,
        IN_PROGRESS,
        DONE,
        BLOCKED,            // 显式阻塞(等外部)
        SKIPPED             // 跳过(R3 决策为 skip)
    }
}
```

### 4.2 Contract

```java
// pair/contract/Contract.java (新增)
public class Contract {
    private final String id;                       // ctr_<stepId>_<ts>_<rand>
    private final String parentStepId;
    private final ContractType type;
    private final ContractAssignee assignedTo;
    private volatile ContractStatus status;
    private final long issuedAt;
    private final long deadlineMs;                 // 相对值,默认 10min
    private volatile long lastActivityAt;          // 用于 deadlock 检测
    private final String payloadJson;              // 原始消息载荷
    private volatile int retryCount;
    private final int maxRetries;                  // 默认 2(R1 + R2)
    private final String retryOf;                  // 如果是 retry,指向上一个 contract id
    private final List<ContractEvent> history;

    public enum ContractType {
        TASK_ASSIGNMENT,       // supervisor → 主 AI 派任务
        DECISION_REQUEST,      // R3 → supervisor 决策请求
        SYSTEM_NUDGE,          // R2 system inject
        APPROVAL_REQUEST,      // 主 AI → supervisor 请求批准
        STATE_REPORT_REQUEST   // supervisor → 主 AI 请求状态报告
    }

    public enum ContractAssignee { MAIN_AI, SUPERVISOR }

    public enum ContractStatus {
        OPEN,                  // 已发出,未 discharge
        RECEIVED,              // 接收方已确认收到(非必须,可跳过)
        DISCHARGED,            // 正常完成
        EXPIRED_RETRIED,       // deadline 过期,已触发 retry
        EXPIRED_ESCALATED,     // 多次 retry 后 R3 走完仍未恢复
        CANCELLED              // plan abort / 上游取消
    }
}

// pair/contract/ContractEvent.java (新增)
public class ContractEvent {
    public final long ts;
    public final EventType type;
    public final String evidence;     // 文件路径 / message id / etc.
    public final String note;

    public enum EventType {
        ISSUED, RECEIVED, RETRIED, DISCHARGED, ESCALATED, CANCELLED
    }
}
```

### 4.3 队列消息载荷格式

入队的所有消息都按这个 envelope:

```json
{
  "contractId": "ctr_step12_1716638400_abc",
  "parentStepId": "step_plan42_12",
  "type": "TASK_ASSIGNMENT",
  "isRetry": false,
  "retryOf": null,
  "retryCount": 0,
  "issuedAt": 1716638400000,
  "deadlineMs": 600000,
  "payload": {
    "task": "...",
    "context": "...",
    "expectedOutput": "tool_use | report_turn_completion"
  },
  "hint": "(retry 时附加)这是第 N 次重推。如果你已经完成,直接调 report_turn_completion 报状态;如果你正在做,继续即可,无需重启;如果没开始,立即开始。"
}
```

接收方(LLM)看到 `isRetry: true` 时,通过 prompt 约束自动处理"我之前是否做过"的逻辑。

---

## 五、Plan 状态机详细设计

### 5.1 状态转换图

```
       ┌──────┐
       │ INIT │  ← Pair 创建,supervisor 未生成 plan
       └───┬──┘
           │ planCreated(steps[])
           ▼
   ┌───────────────────────────────────────────────┐
   │                  ACTIVE                       │
   │   ┌─────────────────────────────────────┐     │
   │   │ EXECUTING                           │     │
   │   │  - 某方 turn 在跑                    │     │
   │   │  - 无需 DeadlockGuard 介入           │     │
   │   └──┬──────────────────────────────────┘     │
   │      │ turnEnded(noOpenContract)              │
   │      ▼                                        │
   │   ┌─────────────────────────────────────┐     │
   │   │ PENDING_DECISION                    │     │
   │   │  - 无 open contract                  │     │
   │   │  - plan 有未完 step                  │     │
   │   │  - TransitionDispatcher 自动唤醒    │     │
   │   │    supervisor 出新 step              │     │
   │   └──┬──────────────────────────────────┘     │
   │      │ supervisor.emit_action(task_assignment)│
   │      │ → new contract created                 │
   │      ▼                                        │
   │   ┌─────────────────────────────────────┐     │
   │   │ PENDING_DISCHARGE                   │ ★   │
   │   │  - 有 open contract                  │     │
   │   │  - 接收方未开始 turn 或 turn 已结束    │     │
   │   │  - DeadlockGuard 监控 deadline      │     │
   │   └──┬──────────────────────────────────┘     │
   │      │ turnStarted(assignedTo)                │
   │      └────► EXECUTING                          │
   │      │ deadline.expired                       │
   │      └────► (R1/R2 入队,subState 不变)         │
   │      │ R3.escalated                          │
   │      └────► (依 supervisor 决策走 abort/skip/ │
   │              reissue/escalate_to_human)       │
   └──┬──┬──┬──┬───────────────────────────────────┘
      │  │  │  │
      │  │  │  └─► WAITING (escalate_to_human / supervisor 显式 wait)
      │  │  │
      │  │  └────► DONE (allStepsDone)
      │  │
      │  └───────► ABORTED (userCancel / unrecoverable)
      │
      └──── 任何状态 ─── planReplaced/planReset ──┐
                                                  │
                                                  ▼
                                              新 plan 进 INIT
```

### 5.2 各状态进入条件 / 退出条件 / 监控行为

| 状态 | 进入条件 | 退出条件 | DeadlockGuard 行为 | 其他副作用 |
|---|---|---|---|---|
| `INIT` | Pair 创建,plan 未生成 | `supervisor.proposeFirstPlan()` 完成 | 不监控 | TransitionDispatcher.wake(supervisor) 一次 |
| `ACTIVE.EXECUTING` | 任一 session `turnStarted` | `turnEnded` | 不监控 | - |
| `ACTIVE.PENDING_DISCHARGE` | 有 open contract,无 in-progress turn | `discharge` / `expired_escalated` / `cancelled` | 监控 contract deadline | - |
| `ACTIVE.PENDING_DECISION` | 无 open contract,有未完 step | supervisor 创建新 contract | 不监控(走 dispatcher) | TransitionDispatcher.wake(supervisor) |
| `WAITING` | `escalate_to_human` action / supervisor 显式 wait | 人工 resume | 不监控 | UI 显示等待状态 |
| `DONE` | 所有 step `DONE`/`SKIPPED` | (terminal) | 不监控 | UI 显示完成,记录 metrics |
| `ABORTED` | 用户 cancel / R3 决策 abort | (terminal) | 不监控 | cancel 所有 open contracts |

### 5.3 状态转移触发表

```java
// pair/plan/PlanTransition.java (新增)
public enum PlanTransition {
    PLAN_CREATED,          // INIT → ACTIVE.PENDING_DECISION (initial,先让 supervisor 派 step 1)
    CONTRACT_ISSUED,       // any → PENDING_DISCHARGE
    TURN_STARTED,          // PENDING_DISCHARGE → EXECUTING
    TURN_ENDED,            // EXECUTING → (check contracts)
                           //   no open → PENDING_DECISION
                           //   has open → PENDING_DISCHARGE
    CONTRACT_DISCHARGED,   // → (check) all steps done? → DONE; else recompute
    STEP_COMPLETED,        // 推进 currentStepIndex
    ESCALATED_TO_HUMAN,    // any → WAITING
    HUMAN_RESUMED,         // WAITING → ACTIVE.(recompute)
    USER_CANCEL,           // any → ABORTED
    PLAN_REPLACED          // any → INIT (新 plan,清空 open contracts)
}
```

---

## 六、Contract 生命周期详细设计

### 6.1 生命周期图

```
              ┌─────────┐
              │ CREATED │  enqueue 到接收方 inputStream
              └────┬────┘
                   │ inputStream.enqueue 完成
                   ▼
              ┌─────────┐
              │  OPEN   │  ContractRegistry 启动 deadline timer
              └────┬────┘
                   │
        ┌──────────┼──────────────────────┐
        │          │                      │
   discharge   deadline 到            user_cancel
        │          │                      │
        ▼          ▼                      ▼
  ┌──────────┐ ┌─────────────────┐ ┌───────────┐
  │DISCHARGED│ │ DeadlockGuard   │ │ CANCELLED │
  │(terminal)│ │ 评估:            │ │ (terminal)│
  └──────────┘ │  - planState?   │ └───────────┘
               │  - inProgress?  │
               │  - retryCount?  │
               └────┬────────────┘
                    │
       ┌────────────┼────────────────┐
       │            │                │
   retry<2     retry==2           retry>=2
       │            │                │
       ▼            ▼                ▼
   入队 R1 retry  入队 R2 nudge   入 R3 system msg
   (创建子 ctr)    (创建子 ctr)   to supervisor
   retryCount++   retryCount++   原 ctr → EXPIRED_ESCALATED
   原 ctr →       原 ctr →
   EXPIRED_       EXPIRED_
   RETRIED        RETRIED
       │            │
       └────────────┴───→ 新 contract 进入 OPEN(新的 deadline)
```

### 6.2 ContractRegistry API

```java
// pair/contract/ContractRegistry.java (新增)
public class ContractRegistry {
    private final String pairId;
    private final Map<String, Contract> openContracts = new ConcurrentHashMap<>();
    private final Deque<Contract> closedContracts = new ConcurrentLinkedDeque<>(); // rolling 200
    private final ScheduledExecutorService deadlineScheduler;
    private final Map<String, ScheduledFuture<?>> deadlineFutures = new ConcurrentHashMap<>();
    private final L2Store l2Store;
    private final PlanStateMachine planSm;
    private final List<ContractListener> listeners = new CopyOnWriteArrayList<>();

    // ─── 创建 contract ───
    public Contract issue(ContractIssueRequest req);
        // 1. 生成 contractId
        // 2. 保存到 openContracts
        // 3. 通知 PlanStateMachine(可能触发 PENDING_DISCHARGE)
        // 4. 入队到接收方 (经 ActionRouter / SupervisorBridge)
        // 5. 启动 deadline timer
        // 6. 持久化到 L2
        // 7. 通知 listeners

    // ─── discharge contract ───
    public void discharge(String contractId, String dischargeNote);
        // 1. 取消 deadline timer
        // 2. status = DISCHARGED
        // 3. 移到 closedContracts
        // 4. 通知 PlanStateMachine(可能触发 PENDING_DECISION / DONE)
        // 5. 持久化 L2
        // 6. 通知 listeners

    // ─── retry (R1/R2) ───
    public Contract retry(String contractId, ContractType retryType, String hint);
        // 1. 原 contract → EXPIRED_RETRIED
        // 2. 创建新 contract(payload 同原,加 isRetry=true / retryOf=原id / retryCount+1)
        // 3. 入队
        // 4. 启动新 deadline timer

    // ─── R3 escalate ───
    public void escalate(String contractId);
        // 1. 原 contract → EXPIRED_ESCALATED
        // 2. 入队 DECISION_REQUEST contract to supervisor (system message)
        // 3. 该 DECISION_REQUEST contract 自身也有 deadline,过期同样升级

    // ─── cancel ───
    public void cancel(String contractId, String reason);
        // 1. 取消 deadline timer
        // 2. status = CANCELLED
        // 3. 持久化 + 通知

    // ─── 查询 ───
    public List<Contract> getOpenContracts();
    public Contract findById(String contractId);
    public Optional<Contract> findOldestOpen();
    public boolean hasPendingFor(String parentStepId, ContractAssignee assignee);
        // 用于 DeadlockGuard / dedup

    // ─── 持久化 ───
    public void hydrateFromL2();
        // IDE 重启时调用,从 L2State 重建 openContracts
        // 已超时的立即触发 R1
}
```

### 6.3 Contract 创建的统一入口

所有"supervisor → 主 AI"或"主 AI → supervisor"的跨边界消息都必须通过 `ContractRegistry.issue()`:

```
旧:
  ActionRouter.handleInjectPrompt(payload)
    → directiveTracker.register(directiveId)
    → webview.onInjectPromptV2(...)

新:
  ActionRouter.handleInjectPrompt(payload)
    → Contract ctr = registry.issue(
        ContractIssueRequest.builder()
          .parentStepId(currentStepId)
          .type(TASK_ASSIGNMENT)
          .assignedTo(MAIN_AI)
          .payload(payload)
          .deadlineMs(payload.getDeadlineMs().orElse(DEFAULT_DEADLINE))
          .build())
    // registry.issue 内部已经入队 + 启动 timer
```

---

## 七、DeadlockGuard 详细设计

### 7.1 触发逻辑(伪代码)

```java
// pair/guard/DeadlockGuard.java (新增)
public class DeadlockGuard {
    private final PairSession pair;
    private final ContractRegistry registry;
    private final PlanStateMachine planSm;
    private final ScheduledExecutorService scheduler;
    private static final long CHECK_INTERVAL_MS = 30_000;
    private static final long GRACE_PERIOD_MS = 30_000;  // 接收方 turn 刚开始的宽限

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick,
            CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, MILLISECONDS);
    }

    private void tick() {
        // ★ 核心 guard:plan 不在 ACTIVE → 一律不监控
        Plan plan = planSm.getCurrent();
        if (plan.getState() != PlanState.ACTIVE) return;
        if (plan.getSubState() != ActiveSubState.PENDING_DISCHARGE) return;

        long now = System.currentTimeMillis();
        for (Contract c : registry.getOpenContracts()) {
            evaluate(c, now);
        }
    }

    private void evaluate(Contract c, long now) {
        // 已经在 R3 escalated 链路上 → 不重复触发
        if (c.getStatus() != ContractStatus.OPEN) return;

        // 接收方有 turn in progress → 队列会自然消费,不动
        if (isAssigneeTurnInProgress(c.getAssignedTo())) return;

        // 接收方刚开始 turn(<30s)→ grace period
        if (assigneeJustStarted(c.getAssignedTo(), now)) return;

        // 队列里已经有同 parentStepId 的 retry pending → 不重复入队
        if (registry.hasPendingFor(c.getParentStepId(), c.getAssignedTo())) return;

        long idleMs = now - c.getLastActivityAt();
        if (idleMs < c.getDeadlineMs()) return;  // 还没到 deadline

        // ─── 触发升级 ───
        if (c.getRetryCount() < c.getMaxRetries()) {
            // R1 或 R2
            ContractType retryType = c.getRetryCount() == 0
                ? c.getType()                  // R1 = 重发原类型
                : ContractType.SYSTEM_NUDGE;   // R2 = 更明确的 system 提示
            String hint = buildHint(c, retryType);
            registry.retry(c.getId(), retryType, hint);
            log("R" + (c.getRetryCount()+1) + " enqueued for " + c.getId());
        } else {
            // R3 - 给 supervisor 队列入 system message 求决策
            registry.escalate(c.getId());
            log("R3 escalated to supervisor for decision: " + c.getId());
        }
    }

    private boolean isAssigneeTurnInProgress(ContractAssignee a) {
        if (a == MAIN_AI) {
            return pair.getMainAIMonitor().isTurnInProgress();
        } else {
            // 通过 daemon supervisor.health() 取得
            SupervisorHealth h = pair.getSupervisorBridge().getCachedHealth();
            return h != null && h.currentTurnInProgress;
        }
    }
}
```

### 7.2 R1/R2/R3 入队内容模板

#### R1 retry message(同原类型,加 hint)

```
原 payload 完全保留,在最前面插入:
"[系统提示:这是 contract <contractId> 的第 1 次重推]
你之前可能没有收到这条消息或没有完成它。请按以下原则处理:
- 如果你已经完成了:直接调用 report_turn_completion(contractId='<id>', status='done')
- 如果你正在做:无需重启,继续执行,完成后正常 report
- 如果你没有开始:立即开始执行下面的任务,不要再发任何文字解释

[原任务内容]
..."
```

#### R2 system_nudge message

```
"[系统提示:contract <contractId> 已重推 1 次仍未 discharge]
你的下一个 turn 必须以下面三种之一结尾,不允许纯文本对话结尾:
1. 至少一个 tool_use(实际执行任务)
2. report_turn_completion(contractId='<id>', status='done'|'blocked'|'need_clarification')
3. 调用 ask_clarification 工具(显式声明需要澄清)

请立即处理。原任务内容:..."
```

#### R3 DECISION_REQUEST to supervisor(system message)

```json
{
  "role": "system",
  "content": "[Pair 系统] contract <contractId> 在 <X 分钟> 内 R1/R2 重试 2 次仍未被主 AI discharge。\n\n当前状态:\n- Plan: <planId>, Step <currentStep>/<totalSteps>: <stepTitle>\n- Open contracts: <list>\n- Recent events (main AI): <last 5 events>\n- Main AI 最后一次 turn 内容摘要: <last_assistant_text 200 chars>\n\n请通过 emit_action 决策一项:\n(a) reissue_with_clarification: 重新下发任务(附带更明确的提示词)\n(b) skip_step: 跳过当前 step,标记 blocked\n(c) abort_plan: 中止整个 plan(用户需重新规划)\n(d) escalate_to_human: 你无法决策,交给人工(走现有 escalate 流程)\n\n请仅在 LLM 真的无法决策时选 (d)。"
}
```

### 7.3 进入 PENDING_DECISION 时的自动唤醒

不属于 DeadlockGuard 职责,由 `TransitionDispatcher` 独立处理:

```java
// pair/dispatcher/TransitionDispatcher.java (新增)
public class TransitionDispatcher implements PlanStateListener {
    private static final long PENDING_DECISION_WAKE_DELAY_MS = 30_000;
    private ScheduledFuture<?> pendingWake;

    @Override
    public void onStateChanged(PlanState old, Plan now) {
        if (now.getState() == ACTIVE && now.getSubState() == PENDING_DECISION) {
            // 30s 防抖:给上一轮事件 settle 的时间
            scheduleWake(now);
        } else {
            if (pendingWake != null) pendingWake.cancel(false);
        }
    }

    private void scheduleWake(Plan plan) {
        pendingWake = scheduler.schedule(() -> {
            // 还在 PENDING_DECISION → 入队让 supervisor 出新 step
            if (planSm.getCurrent().getSubState() != PENDING_DECISION) return;
            Contract c = registry.issue(ContractIssueRequest.builder()
                .parentStepId(plan.getCurrentStep().getId())
                .type(ContractType.DECISION_REQUEST)
                .assignedTo(SUPERVISOR)
                .payload(buildDecisionRequest(plan))
                .build());
        }, PENDING_DECISION_WAKE_DELAY_MS, MILLISECONDS);
    }
}
```

---

## 八、队列模型与去重

### 8.1 入队前去重(方式 A)

`ContractRegistry.issue` 内部:

```java
public Contract issue(ContractIssueRequest req) {
    // 同 parentStepId + 同 assignedTo + 状态为 OPEN 的 contract → 替换
    Optional<Contract> existing = openContracts.values().stream()
        .filter(c -> c.getParentStepId().equals(req.parentStepId))
        .filter(c -> c.getAssignedTo() == req.assignedTo)
        .filter(c -> c.getStatus() == OPEN)
        .findFirst();
    
    if (existing.isPresent() && req.replaceExisting) {
        // 取消旧的,创建新的(retry 链路就是这种)
        cancel(existing.get().getId(), "replaced by new issue");
    }
    
    Contract newCtr = buildContract(req);
    openContracts.put(newCtr.getId(), newCtr);
    enqueueToReceiver(newCtr);
    scheduleDeadline(newCtr);
    persistToL2();
    return newCtr;
}
```

### 8.2 LLM prompt 兜底(方式 B)

在主 AI 和 supervisor 的 system prompt 中各加一段:

```
[Contract 协议补充约束]

你收到的每条消息都附带 contractId(系统协议头)。如果你看到同一个 parentStepId 的消息出现多次,以**时间戳最大的那条**为准,前面的版本视为过期。

每个 turn 必须以下三者之一结尾,不允许以"请发送..." / "我将立即..." / "等待..." 等等待型语句结尾:
1. 至少一个 tool_use(执行了实际操作)
2. report_turn_completion(contractId, status) MCP 工具调用
3. ask_clarification(contractId, question) MCP 工具调用

如果你看到 contract 上有 isRetry: true,按 hint 中的三段式处理(已完成/进行中/没开始)。
```

### 8.3 queue 容量与背压

- 沿用现有 `runtime.inputStream`(daemon)和 `webview` 端的队列实现,**无界**
- 如果出现 retry 风暴(DeadlockGuard bug 导致同一 contract 无限 retry):由 `maxRetries=2 + EXPIRED_ESCALATED` 终态自然保护
- L2 持久化的 openContracts 也作为额外限制:每 pair 最多 50 个 open contract,超过则拒绝 issue 并 alert UI

---

## 九、System Message 入队(daemon 改造)

### 9.1 现状

`ai-bridge-server/ai-bridge/channels/supervisor-channel.js:481-489` 当前 hardcoded:

```javascript
runtime.inputStream.enqueue({
    type: 'user',
    session_id: '',
    parent_tool_use_id: null,
    message: {
        role: 'user',
        content: [{ type: 'text', text: summary }],
    },
});
```

### 9.2 改造

添加 `role` 参数,支持 system message:

```javascript
// supervisor-channel.js
export async function postEventToSupervisor(params) {
    const { pairId, supervisorId, event, role = 'user' } = params || {};
    // ...
    runtime.inputStream.enqueue({
        type: role === 'system' ? 'system' : 'user',
        session_id: '',
        parent_tool_use_id: null,
        message: {
            role: role,
            content: [{ type: 'text', text: summary }],
        },
    });
}
```

### 9.3 SDK 兼容性

需验证 Claude Agent SDK 的 `inputStream` 是否接受 `role: 'system'`。如果不接受,降级方案:

- `role: 'user'`,但内容 prefix `[SYSTEM] ` 标识
- supervisor system prompt 里说明:"以 [SYSTEM] 开头的 user message 是系统消息,不是用户输入"

落地时先实测 SDK 行为,二选一。

### 9.4 Java 端调用

`SupervisorBridge.postEvent` 增加 `role` 参数:

```java
public CompletableFuture<Void> postEvent(JsonObject event, String role);
public CompletableFuture<Void> postEvent(JsonObject event); // 默认 role='user',向后兼容
```

`ContractRegistry.escalate` 调用时传 `role='system'`。

---

## 十、组件改造/退役清单

### 10.1 新增组件

| 组件 | 文件 | 行数估计 |
|---|---|---|
| `Plan` | `pair/plan/Plan.java` | ~200 |
| `PlanStep` | `pair/plan/PlanStep.java` | ~120 |
| `PlanStateMachine` | `pair/plan/PlanStateMachine.java` | ~400 |
| `PlanTransition` | `pair/plan/PlanTransition.java` | ~30 |
| `PlanStateListener` | `pair/plan/PlanStateListener.java` | ~20 |
| `Contract` | `pair/contract/Contract.java` | ~200 |
| `ContractEvent` | `pair/contract/ContractEvent.java` | ~30 |
| `ContractRegistry` | `pair/contract/ContractRegistry.java` | ~500 |
| `ContractIssueRequest` | `pair/contract/ContractIssueRequest.java` | ~80 |
| `ContractListener` | `pair/contract/ContractListener.java` | ~20 |
| `DeadlockGuard` | `pair/guard/DeadlockGuard.java` | ~200 |
| `TransitionDispatcher` | `pair/dispatcher/TransitionDispatcher.java` | ~150 |
| `HealthWatcher` | `pair/watcher/HealthWatcher.java` | ~120 |
| `BudgetWatcher` | `pair/watcher/BudgetWatcher.java` | ~120 |
| `RotationWatcher` | `pair/watcher/RotationWatcher.java` | ~150 |

### 10.2 改造组件

| 组件 | 文件 | 改造内容 |
|---|---|---|
| `PairSession` | `pair/PairSession.java` | 加 `getPlanStateMachine()`, `getContractRegistry()`, `getDeadlockGuard()`, `getTransitionDispatcher()` |
| `PairCoordinator` | `pair/PairCoordinator.java` | 加 `PLAN_TRANSITIONING` 状态(plan 状态变化期间的短期排他) |
| `ActionRouter` | `pair/ActionRouter.java` | `handleInjectPrompt` 改为通过 `ContractRegistry.issue()`,不再直接调 `webview.onInjectPromptV2` |
| `ClaudeMessageHandler` | `session/ClaudeMessageHandler.java` | `onComplete`(line 877) 检查 contract discharge;未 discharge 自动入队 retry |
| `SupervisorBridge` | `pair/SupervisorBridge.java` | `postEvent` 加 `role` 参数;`getCachedHealth()` 暴露给 DeadlockGuard |
| `PairStatusPusher` | `pair/PairStatusPusher.java` | 加 `if (sdkInactiveMs > 30min) healthLevel = CRITICAL` |
| `L2State` | `l2/L2State.java` | 加 `plan` 字段、`openContracts` 字段,schemaVersion 升 v2 |
| `L2Store` | `l2/L2Store.java` | 加 v1→v2 自动升级 |
| `RotationCoordinator` | `pair/rotation/RotationCoordinator.java` | 新 supervisor 接班时**重置** open contracts 状态(全部 cancel + 让新 supervisor 重新评估) |
| `SupervisorMonitor` | `pair/SupervisorMonitor.java` | **删除** `scheduleWithFixedDelay(30s)`,职责拆出 |
| `MainAIMonitor` | `pair/MainAIMonitor.java` | **删除** `StallDetector`,逻辑融入 Contract deadline |
| `EventCollector` | `pair/EventCollector.java` | **保留**类,但**删除** `forwardComposite` 调用路径;只保留"事件日志"角色 |
| `DirectiveTracker` | `pair/DirectiveTracker.java` | **删除整个类** |

### 10.3 daemon 改造

| 文件 | 改造 |
|---|---|
| `ai-bridge/channels/supervisor-channel.js:481-489` | `inputStream.enqueue` 支持 `role: 'system'` |
| `ai-bridge/channels/supervisor-channel.js:442` | `postEventToSupervisor` 接受 `role` 参数 |

---

## 十一、数据持久化(L2 schema v2)

### 11.1 schema v2 新增字段

```json
{
  "schemaVersion": 2,
  "pairId": "...",
  "supervisorId": "...",
  "mainSessionId": "...",
  "generation": 3,
  
  // ─── 新增:plan ───
  "plan": {
    "id": "plan_xxx_1716638400",
    "state": "ACTIVE",
    "subState": "PENDING_DISCHARGE",
    "currentStepIndex": 12,
    "createdAt": 1716000000000,
    "lastTransitionAt": 1716638500000,
    "steps": [
      {
        "id": "step_xxx_0",
        "index": 0,
        "title": "Build AuditContract DTO",
        "owner": "MAIN_AI",
        "status": "DONE",
        "contractIds": ["ctr_step0_xxx"],
        "createdAt": 1716000000000,
        "completedAt": 1716100000000,
        "attempts": 1,
        "filesChanged": ["AuditContract.java"]
      },
      // ...
    ],
    "metadata": {
      "autonomyMode": "full",
      "model": "claude-opus-4-7"
    }
  },
  
  // ─── 新增:openContracts ───
  "openContracts": [
    {
      "id": "ctr_step12_1716638400_abc",
      "parentStepId": "step_xxx_12",
      "type": "TASK_ASSIGNMENT",
      "assignedTo": "MAIN_AI",
      "status": "OPEN",
      "issuedAt": 1716638400000,
      "deadlineMs": 600000,
      "lastActivityAt": 1716638400000,
      "payloadJson": "{...}",
      "retryCount": 1,
      "maxRetries": 2,
      "retryOf": "ctr_step12_1716637800_def",
      "history": [
        {"ts": 1716637800000, "type": "ISSUED", "evidence": null, "note": null},
        {"ts": 1716638400000, "type": "RETRIED", "evidence": "deadline expired",
         "note": "R1 enqueued"}
      ]
    }
  ],
  
  // ─── 既有字段保留 ───
  "anchoredFacts": { ... },
  "decisions": [ ... ],
  "planProgress": [ ... ],  // 兼容字段,从 plan.steps 派生填充
  "mainAIState": { ... },
  "compactionHistory": [ ... ]
}
```

### 11.2 schema v1 → v2 自动升级

`L2Store.loadAndUpgrade`:

```java
public L2State loadAndUpgrade(String pairId) {
    JsonObject raw = readJsonFile(stateFile(pairId));
    int version = raw.has("schemaVersion") ? raw.get("schemaVersion").getAsInt() : 1;
    
    if (version == 1) {
        // 升级:加 plan = null,加 openContracts = []
        raw.add("plan", JsonNull.INSTANCE);
        raw.add("openContracts", new JsonArray());
        raw.addProperty("schemaVersion", 2);
        writeJsonFile(stateFile(pairId), raw);
        backupV1(pairId);
    }
    
    return parseV2(raw);
}
```

### 11.3 IDE 重启恢复流程

```
PairSessionManager.boot():
  for each L2 file in ~/.codemoss/pairs/:
    state = L2Store.loadAndUpgrade(pairId)
    
    pair = new PairSession(pairId, ...)
    pair.start()
    
    if (state.plan != null):
      pair.getPlanStateMachine().restore(state.plan)
    
    if (state.openContracts not empty):
      pair.getContractRegistry().hydrateFromL2(state.openContracts)
      // hydrateFromL2 内部:
      //   - 重建 openContracts map
      //   - 重启 deadline timer(剩余时间 = deadline - (now - issuedAt))
      //   - 如果已经过期(now > issuedAt + deadline):
      //     立即触发 evaluate() 走 R1/R2/R3
    
    pair.getDeadlockGuard().start()
    pair.getTransitionDispatcher().start()
```

### 11.4 持久化时机(写放大控制)

| 事件 | 写 L2? | 备注 |
|---|---|---|
| Plan 状态变化(state/subState) | ✓ | 同步写 |
| Plan step 切换 | ✓ | 同步写 |
| Contract issue | ✓ | 同步写 |
| Contract discharge | ✓ | 同步写 |
| Contract retry / escalate / cancel | ✓ | 同步写 |
| Contract lastActivityAt 更新 | ✗ | 内存即可,IDE 重启会重新计算 |
| Health / Budget 状态 | ✗ | 由各自 watcher 独立持久化 |

每次写 L2 沿用现有的"原子 rename + .bak 副本"机制(`L2Store.atomicWrite`)。

---

## 十二、关键代码改造点

### 12.1 ClaudeMessageHandler.onComplete (line 877 区域)

```java
// session/ClaudeMessageHandler.java
public void handleStreamEnd(...) {
    // 既有逻辑:notifyMainAIMonitorTurnEnd / publishTurnEndIfPair
    
    // ★ 新增:检查 contract discharge
    PairSession pair = getCurrentPair();
    if (pair == null) return;
    
    ContractRegistry registry = pair.getContractRegistry();
    List<Contract> dischargeable = registry.getOpenContracts().stream()
        .filter(c -> c.getAssignedTo() == MAIN_AI)
        .filter(c -> c.getParentStepId().equals(currentStepId()))
        .collect(toList());
    
    if (dischargeable.isEmpty()) {
        // 主 AI 完成 turn 但没有任何 open contract → 正常(可能是 free-form 对话)
        return;
    }
    
    // 检查这次 turn 有没有显式 discharge(通过 MCP tool report_turn_completion)
    boolean explicitDischarge = lastTurnHadDischargeCall(dischargeable);
    
    if (!explicitDischarge) {
        // 没显式 discharge,也没有 tool_use → 视为 invalid turn
        boolean hadToolUse = !lastTurnToolUses().isEmpty();
        if (!hadToolUse) {
            // 主 AI 只输出了文字,没有任何 tool use → 自动触发 retry
            for (Contract c : dischargeable) {
                registry.retry(c.getId(), c.getType(),
                    "你的上一 turn 没有调用任何工具,也没有 report_turn_completion。" +
                    "这是对话漂移。请立即按下面的内容执行,产出 tool_use 或调 report_turn_completion。");
            }
        }
        // 如果有 tool_use 但没 discharge,可能任务还没做完,不动(让 deadline timer 走正常流程)
    }
}
```

### 12.2 ActionRouter.handleInjectPrompt (line 231 区域)

```java
// pair/ActionRouter.java
private void handleInjectPrompt(JsonObject payload) {
    String directiveId = payload.get("directiveId").getAsString();
    String prompt = payload.get("prompt").getAsString();
    
    // 旧:directly call webview / register directive
    // 新:走 ContractRegistry
    ContractRegistry registry = pair.getContractRegistry();
    PlanStep currentStep = pair.getPlanStateMachine().getCurrentStep();
    
    Contract c = registry.issue(ContractIssueRequest.builder()
        .contractIdHint(directiveId)       // supervisor 可指定,否则自动生成
        .parentStepId(currentStep.getId())
        .type(ContractType.TASK_ASSIGNMENT)
        .assignedTo(ContractAssignee.MAIN_AI)
        .payloadJson(payload.toString())
        .deadlineMs(payload.has("deadlineMs")
            ? payload.get("deadlineMs").getAsLong()
            : DEFAULT_DEADLINE_MS)
        .build());
    
    // registry.issue 内部已经入队(via webview.onInjectPromptV2)
}
```

### 12.3 SupervisorBridge.postEvent (改造现有,加 role 参数)

```java
// pair/SupervisorBridge.java
public CompletableFuture<Void> postEvent(JsonObject event, String role) {
    JsonObject params = new JsonObject();
    params.addProperty("pairId", pairId);
    params.addProperty("supervisorId", supervisorId);
    params.add("event", event);
    if (role != null && !"user".equals(role)) {
        params.addProperty("role", role);
    }
    return sdkBridge.sendDaemonCommand("supervisor.postEvent", params, callback);
}

// 向后兼容
public CompletableFuture<Void> postEvent(JsonObject event) {
    return postEvent(event, "user");
}
```

### 12.4 daemon supervisor-channel.js

```javascript
// ai-bridge/channels/supervisor-channel.js:442 起
export async function postEventToSupervisor(params) {
    const { pairId, supervisorId, event, role = 'user' } = params || {};
    // ...
    
    runtime.inputStream.enqueue({
        type: role === 'system' ? 'system' : 'user',
        session_id: '',
        parent_tool_use_id: null,
        message: {
            role: role,
            content: [{ type: 'text', text: summary }],
        },
    });
    // ...其他逻辑不变
}
```

### 12.5 PairStatusPusher 加健康规则

```java
// pair/PairStatusPusher.java
public void pushSoft() {
    // 既有逻辑:context ratio / used tokens / health state / etc.
    
    // ★ 新增:HealthWatchdog 规则(替代独立组件)
    SupervisorHealth h = pair.getSupervisorBridge().getCachedHealth();
    if (h != null && h.alive) {
        long sdkInactiveMs = System.currentTimeMillis() - h.lastActivityAt;
        if (sdkInactiveMs > Duration.ofMinutes(30).toMillis()) {
            statusBuilder.healthLevel(HealthLevel.CRITICAL);
            statusBuilder.healthReason("SDK 30min 无活动,疑似进程级故障");
        }
    }
    
    // 主 AI 同理
    long mainSdkInactiveMs = pair.getClaudeSession().getSdkInactiveMs();
    if (mainSdkInactiveMs > Duration.ofMinutes(30).toMillis()) {
        statusBuilder.healthLevel(HealthLevel.CRITICAL);
        statusBuilder.healthReason("主 AI SDK 30min 无活动,疑似进程级故障");
    }
}
```

---

## 十三、实施阶段

### 13.1 阶段划分(激进路径)

虽然决策是激进迁移,但为了**单次代码审查可控**和**回滚边界清晰**,分 3 个原子提交:

```
Stage A (准备 + 数据结构): ~2-3 天
  ├─ 新增 Plan / PlanStep / Contract / ContractEvent POJO
  ├─ 新增 PlanStateMachine / ContractRegistry(纯实现,无调用方)
  ├─ L2State schema v2 升级(向后兼容加载)
  ├─ 单元测试:状态机转换 + ContractRegistry CRUD
  └─ 验证:旧代码完全不变,新代码不被调用,系统行为无变化

Stage B (核心切换): ~3-4 天
  ├─ 改造 PairSession,wire Plan / ContractRegistry
  ├─ 改造 ActionRouter.handleInjectPrompt 走 ContractRegistry
  ├─ 改造 ClaudeMessageHandler.onComplete 检查 discharge + 自动 retry
  ├─ 改造 SupervisorBridge.postEvent 支持 role
  ├─ daemon supervisor-channel.js 支持 system role
  ├─ 新增 DeadlockGuard + TransitionDispatcher
  ├─ 删除 DirectiveTracker(替换所有引用)
  ├─ 删除 MainAIMonitor.StallDetector
  ├─ 删除 SupervisorMonitor.scheduleWithFixedDelay(30s)
  ├─ 集成测试:模拟你截图那次故障,验证 R1/R2/R3 流程
  └─ 验证:plan 推进、deadlock 自愈、退役组件无残留调用

Stage C (拆 watcher + 清理): ~2 天
  ├─ 拆 SupervisorMonitor 副作用:HealthWatcher / BudgetWatcher / RotationWatcher
  ├─ 删除 EventCollector.composite_summary 调用路径(只保留 ring buffer)
  ├─ PairStatusPusher 加 HealthWatchdog 规则
  ├─ RotationCoordinator 加 contract reset 逻辑
  ├─ 清理所有 deprecated 字段、方法、import
  └─ 验证:rotation / budget / health 监控行为完整保留
```

### 13.2 每阶段验收标准

#### Stage A 验收
- [ ] `PlanStateMachine` 单元测试覆盖所有状态转移
- [ ] `ContractRegistry` 单元测试覆盖 CRUD / retry / cancel / hydrate
- [ ] L2 v1 文件加载后自动升级为 v2,旧字段全部保留
- [ ] 集成测试:旧流程(无 contract)端到端跑通,行为与 main 分支一致

#### Stage B 验收
- [ ] 用例 1:主 AI 输出"请发送下一步..."无 tool_use → 自动触发 R1
- [ ] 用例 2:主 AI 收到 contract 后正常 tool_use + discharge → contract 关闭,plan 推进
- [ ] 用例 3:接收方 turn in progress 时 deadline 到 → 不入队 retry(因 grace period)
- [ ] 用例 4:R1/R2 都过期 → R3 触发,supervisor 收到 system message DECISION_REQUEST
- [ ] 用例 5:plan state DONE 时 contract 还在 open(理论不该发生)→ 不告警
- [ ] 用例 6:supervisor 卡死时 deadline 到 → R3 不能给自己,直接 escalate UI
- [ ] 用例 7:IDE 重启后 plan/contract 状态完整恢复

#### Stage C 验收
- [ ] Health/Budget/Rotation 监控行为与 main 分支一致(独立 watcher 跑)
- [ ] Rotation 后 open contracts 全部 reset,新 supervisor 重新评估
- [ ] HealthWatchdog 规则在 30min SDK 静默时正确标 CRITICAL
- [ ] 没有任何残留的 DirectiveTracker / StallDetector / 30s tick 引用

### 13.3 回滚边界

| 阶段完成 | 回滚成本 | 回滚方式 |
|---|---|---|
| A 后 | 极低 | 删除新文件,L2 文件保持 v2 也能正常用(v1 兼容字段在) |
| B 后 | 中 | 单 commit revert + L2 文件需要回退到 v1 schema(脚本工具) |
| C 后 | 高 | 不建议回滚;如必须,需 revert B + C 两个 commit |

---

## 十四、测试策略

### 14.1 单元测试

```
test/pair/plan/
  PlanStateMachineTest.java        - 所有状态转移
  PlanTest.java                    - getter/setter,序列化
  PlanStepTest.java                - 同上

test/pair/contract/
  ContractRegistryTest.java        - issue / discharge / retry / cancel / hydrate
  ContractTest.java                - getter/setter,序列化
  ContractDedupeTest.java          - 同 parentStepId 替换逻辑

test/pair/guard/
  DeadlockGuardTest.java           - 触发条件 / R1/R2/R3 升级
  DeadlockGuardGuardTest.java      - 各种"不应触发"的 guard 条件

test/pair/dispatcher/
  TransitionDispatcherTest.java    - PENDING_DECISION 自动唤醒 supervisor

test/l2/
  L2StoreV1ToV2UpgradeTest.java    - schema 升级
  L2RestoreContractsTest.java      - hydrate 已过期 contract 立即 R1
```

### 14.2 集成测试(关键场景)

```
test/integration/pair/
  ConversationalDriftRecoveryTest.java
    场景:主 AI 输出"请发送下一步..."
    断言:onComplete 检测到 contract 未 discharge + 无 tool_use → 自动 R1 入队
  
  DoubleSilentDeadlockRecoveryTest.java
    场景:模拟 5min 双静默
    断言:plan ACTIVE.PENDING_DISCHARGE → DeadlockGuard 触发 R1 → 仍未恢复 → R2 → R3
  
  NormalIdleNotAlarmTest.java
    场景:plan 所有 step 完成,大家都 idle
    断言:plan DONE → DeadlockGuard tick 时直接 return,不告警
  
  GracePeriodTest.java
    场景:接收方刚开始 turn(<30s)时 deadline 到
    断言:跳过 R1 入队,下一周期再 evaluate
  
  IDERestartRecoveryTest.java
    场景:Plan ACTIVE,有 2 个 open contract,IDE 重启
    断言:启动后 plan 状态完整恢复,deadline timer 重新启动
    断言:已过期的 contract 立即触发 R1
  
  RotationContractResetTest.java
    场景:rotation 发生时有 3 个 open contract
    断言:rotation 完成后 contracts 全部 cancel,新 supervisor 收到 plan 状态请它重新评估

test/integration/daemon/
  SystemMessageEnqueueTest.js
    场景:postEventToSupervisor 带 role='system'
    断言:inputStream.enqueue 收到 role: 'system'
    断言:SDK 正常接收处理(or 降级为 user + [SYSTEM] prefix)
```

### 14.3 烟雾测试(原故障复现)

复现 2026-05-25 故障(本文档对应的故障):

```
preconditions:
  - 远程模式
  - autonomy = full
  - 已有 plan: Step 1-12 完成,正在做 Step 13

action:
  1. 主 AI 完成 Step 12,输出"请发送下一步(Step13+)的模块规格,我将立即按上述并发策略派发"
  2. 不做任何手动操作,等 15 分钟

expected (新架构):
  - T+0:onComplete 检测到主 AI 无 tool_use 且 step12 contract 未 discharge
         → 自动入队 R1 retry("你的上一 turn 没调工具,立即执行 step12")
  - T+(几秒):主 AI 消费 R1,要么 tool_use 要么 report_turn_completion
  - 不应该出现 "main AI stalled (300s no turn_end)" 告警

expected (退路):
  即使 R1/R2 都失败,T+10min 仍未恢复 → R3 触发,supervisor 收到 DECISION_REQUEST
  supervisor 决策(autonomy=full 应该会选 reissue_with_clarification)
  
  保底:T+30min SDK 仍无活动 → PairStatusPusher 标 healthLevel=CRITICAL
```

---

## 十五、风险与缓解

### 15.1 已识别风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| SDK 不接受 `role: 'system'` 入队 | 中 | 中 | 降级方案:`role: 'user'` + `[SYSTEM] ` prefix(已在 §9.3 规划) |
| LLM 看到 retry 重复消息后困惑 | 低 | 低 | prompt 兜底("以最新一条为准") + Java 层去重(双保险) |
| Plan 状态机和 supervisor 内部 plan 认知不一致 | 中 | 中 | supervisor 在 emit_action 时强制带 stepId / contractId;不一致 → reject + system message 纠正 |
| L2 schema 升级丢数据 | 低 | 高 | 升级前自动备份 v1 为 `state.json.v1.bak`;升级后 7 天保留备份 |
| IDE 重启后已过期 contract 触发 R1 风暴(几十个一起) | 低 | 中 | hydrate 时按 priority 排序,每秒最多触发 5 个 |
| Rotation 时新 supervisor 重新评估失败 | 中 | 高 | 重新评估失败 → plan 转 WAITING,UI alert,人工介入 |
| `EventCollector` 删除 composite_summary 路径后,事件日志没人消费 | 中 | 低 | 保留 ring buffer 给 supervisor 用作"最近事件"上下文(只读) |

### 15.2 灰度策略

虽然激进路径,但首批用户(内部 dogfood)开启时:
- 默认 deadline 调短到 5min(更快暴露问题)
- DeadlockGuard 触发时同时打 UI alert(可观测)
- L2 写盘加 verbose log
- 跑稳 1 周后调回 10min deadline + 收敛 log

### 15.3 不在本期范围

| 项 | 原因 |
|---|---|
| Plan step 并行执行(同时多个 in_progress) | 当前一次只有一个 currentStep,并行需要重新设计 contract 路由 |
| Cross-pair plan(多 Pair 共享 plan) | 现在每个 Pair 独立 plan |
| Plan template / 复用 | 留给后续 |
| Contract 跨 process 持久化(daemon 端也存一份) | 当前 plugin 是权威源,daemon 重启可以从 plugin 重建 |

---

## 十六、关键决策追踪

为方便未来追溯,所有"为什么这么做"的关键决策都记在这里:

| 决策 | 原因 |
|---|---|
| 为什么 plan 不放在 supervisor LLM context 而放 Java | LLM context 是非确定性的,会漂会忘;Java 是确定性所有者,可持久化、可查询、可断言 |
| 为什么 contract 粒度选 step 而不是 inject_prompt | step 是业务原子单位;一个 step 可能包含多个 sub-contract(派任务 + 验证 + 报告) |
| 为什么不做完整幂等(dedup + ack cache) | 90% 收益 5% 成本即可达成 → retry-with-marker + L2 兜底已足够;完整幂等需要 IdempotencyDeduper + 跨进程持久化,过度设计 |
| 为什么不允许自动中断 LLM | LLM 中断成本极高:中间状态难以恢复、破坏用户信任、自动中断是非确定性触发非确定性行为(坏处叠加) |
| 为什么 30s 是 DeadlockGuard 的 check interval | 沿用现有 SupervisorMonitor 节拍,熟悉度好;且 deadline 默认 10min,30s 粒度足够 |
| 为什么 R3 走 supervisor 决策而不是直接 UI alert | 复用现有 escalate_to_human 治理体系;supervisor 比固定 UI 模板更灵活,能根据上下文出更对的决策 |
| 为什么 system message 比 user message 更合适 | 不污染对话历史;权重高;接收方 LLM 更容易把它识别为"系统指令"而非"用户突然冒出来的话" |
| 为什么 rotation 时重新评估而不是继承 contract | 避免 ack 状态/上下文冲突(老 supervisor 已经处理到一半的 contract 新 supervisor 不知道前因);重新评估虽损失上下文但更安全 |

---

## 十七、改造前后行为对比

### 17.1 用例 1:主 AI 对话漂移

| 时刻 | 旧行为 | 新行为 |
|---|---|---|
| T₀ | 主 AI 输出"请发送下一步..."(无 tool_use) | 同左 |
| T₀+1s | turn_end 推到 supervisor | onComplete 检查 contract:step12 未 discharge + 无 tool_use → 自动入队 R1 retry |
| T₀+30s | SupervisorMonitor tick → 看到主 AI 已 turn end,无新事件 → 空 composite_summary → supervisor idle | 主 AI 消费 R1 retry,正常 tool_use + discharge → plan 推进 |
| T₀+5min | MainAIMonitor 5min stall 告警 → UI 弹 "main AI stalled (300s no turn_end)" | 早就恢复了,无告警 |
| 总损失时间 | 5+ 分钟 + 人工介入 | < 30 秒,无人工介入 |

### 17.2 用例 2:正常 plan 完成

| 时刻 | 旧行为 | 新行为 |
|---|---|---|
| T₀ | 最后一个 step 完成,主 AI report_turn_completion(status=done) | discharge contract → step status=DONE → plan.state=DONE |
| T₀+30s | SupervisorMonitor tick → 空 composite → supervisor idle | DeadlockGuard tick → plan.state=DONE → 直接 return,不告警 |
| T₀+5min | MainAIMonitor 5min stall 告警 → 误报! | 不告警(plan 已 DONE,本来就该 idle) |
| 总误报 | 是 | 否 |

### 17.3 用例 3:supervisor 卡死

| 时刻 | 旧行为 | 新行为 |
|---|---|---|
| T₀ | supervisor 卡在某个 SDK 调用 | 同左 |
| T₀+30s | SupervisorMonitor tick 调 forwardComposite → 阻塞 → tick 失败 → consecutiveFailures++ | DeadlockGuard 不监控(supervisor 没有 contract 欠主 AI);plan 状态走向取决于是否有 open contract |
| T₀+1min(连续 2 次失败) | SupervisorMonitor 标 UNHEALTHY → 触发 rotation | HealthWatcher(独立)检测到 health degraded → 同样触发 rotation |
| T₀+30min(SDK 还在卡) | rotation 失败(因为旧 supervisor 也接不到 produceHandoff)→ 无解 | PairStatusPusher.healthLevel=CRITICAL → UI alert "30min 无 SDK 活动,疑似进程级故障" → 人决定 |

行为基本对等,但新架构下信号更明确(不混淆"应用层 deadlock"和"进程级故障")。

---

## 十八、文档维护

本文档实施完成后需更新:
- `docs/supervisor/design.md` - 加 Contract 章节
- `docs/supervisor/code.md` - 加 Contract 协议要求(LLM 提示词约束)
- `ai-bridge-server/IMPL-PLUGIN.md` - 加 `supervisor.postEvent` role 参数
- `CLAUDE.md` (项目根) - 加新组件目录索引

---

## 十九、实施记录(2026-05-25 收尾)

### 19.1 实际 schema 版本

方案文档原写"schema v2",实际 L2 仓库当前 `CURRENT_VERSION` 已经是 2(由 Phase 3 占用)。新增 Contract State Machine 字段时升到 **v3**,L2Migration 加 v2→v3 路径(不是 v1→v2)。

### 19.2 EventCollector composite_summary 路径(保留 vs 删除)

方案 §10.2 写"删除 `forwardComposite` 调用路径"。**实际保留**,改造为事件驱动:
- 30s `scheduleWithFixedDelay` 禁用(改用 `DEADLOCK_FRIENDLY_INTERVAL_MS=24h`)
- 触发源改为:`urgent` 事件(error / off_plan)+ `TransitionDispatcher.wake` (PENDING_DECISION)
- composite_summary 仍由 `SupervisorMonitor.runTick` 构造并 forward,只是触发时机改了

**保留理由**:supervisor 仍需要在 PENDING_DECISION 唤醒时看到累积的事件(turn_end 摘要 / 文件变更等)。完全去掉 composite_summary 会让 supervisor 失明。事件驱动比定时驱动更准,但**载荷格式没变**。

### 19.3 PairCoordinator.PLAN_TRANSITIONING

按方案 §10.2 加入了枚举值,但 **不参与读写锁**。仅作为状态显示,因为 PlanStateMachine 自身已经通过 `synchronized` 方法保证一致性,不需要外层 rwLock 介入。

### 19.4 Watcher 拆分实际结构

| Watcher | 实现形态 | 说明 |
|---|---|---|
| `HealthWatcher` | **独立新类**(`pair/watcher/HealthWatcher.java`) | 拆出 SupervisorMonitor 的 `consecutiveFailures` + `transitionHealth` 逻辑;SupervisorMonitor 双写(向旧 monitor 字段 + 向新 watcher)以保证回归安全 |
| `BudgetWatcher` | **facade**(`pair/watcher/BudgetWatcher.java`) | 包装 `PairBudgetTracker`(已有,完全独立);facade 给 watcher 模式提供统一访问点 |
| `RotationWatcher` | **facade**(`pair/watcher/RotationWatcher.java`) | 包装 `PairCoordinator.requestRotation()`;rotation 触发源分布在 MainAIMonitor / HealthWatcher / compactBoundaryHandler,facade 提供统一入口 |

### 19.5 Stage B/C 完成清单

| 子项 | 状态 | 备注 |
|---|---|---|
| Stage A (数据结构) | ✅ | Plan/PlanStep/Contract/ContractRegistry/PlanStateMachine + L2 v3 升级 |
| B.1 (基础设施) | ✅ | DeadlockGuard + TransitionDispatcher + SupervisorBridge.postEvent(role) + daemon 改造 |
| B.2 (核心切换) | ✅ | ActionRouter → ContractRegistry + ClaudeMessageHandler.checkContractDischargeOnTurnEnd |
| B.3 (老组件删除) | ✅ | DirectiveTracker + StallDetector + SupervisorMonitor 30s tick 全部删/禁 |
| B.4 (集成测试) | ✅ | DeadlockGuard R1→R2→R3 + DECISION_REQUEST 生成 |
| C.1 (拆 watcher) | ✅ | HealthWatcher 真实拆分,Budget/Rotation 为 facade |
| C.2 (HealthWatchdog 30min) | ✅ | PairStatusPusher.checkSdkSilenceWatchdog |
| C.3 (Rotation contract reset) | ✅ | RotationCoordinator 第 11.5 步取消 SUPERVISOR-bound contracts |
| C.4 (清理) | ✅ | stale imports / 注释 / @link 引用 |
| Supervisor prompt 更新 | ✅ | code-supervisor.md + design-supervisor.md 加 Contract v3 / DECISION_REQUEST 章节 |
| Main AI prompt 更新 | ✅ | PAIR_MODE_SYSTEM_PROMPT_APPEND 加 4 条规则(turn 收尾约束 / 重推处理 / R2 强约束 / 重复消息) |

### 19.6 已知小限制

1. `ensurePlanAndStep` 在 ActionRouter 里合成 plan(单步 plan,每 inject_prompt 追加新 step)。长期方案是 supervisor 通过 `save_plan` MCP 工具显式管理,Stage C 不动这块。
2. SupervisorMonitor 在 runTick 中既调旧的 `transitionHealth` 又调新的 `hw.recordSuccess/recordFailure`,**双写状态**。两者最终一致,但 L2 metrics 会写两次。等 Stage D 把 `transitionHealth` 删掉就消失。

---

**END**
