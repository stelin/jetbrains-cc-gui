# Supervisor 状态机 + Pair Liveness 终态识别 设计方案（讨论记录）

> 状态：**讨论中**，未实施
> 目标：
> 1. 修 Pair Liveness 在 supervisor 完成 plan 后误判"监督者卡死，系统代派"，把任务白白回派给主 AI
> 2. 给 supervisor 加"用户停止 + 补充上下文 + 恢复"工作流（基础设施大部分已就绪，差最后一公里）
>
> 关联：`DeadlockGuard.java`、`ActionRouter.java`、`SupervisorBridge.java`、`EventBus.java`、`PairHandler.java`、`SupervisorPane.tsx`

---

## 1. 两个问题，同一个根因

### 1.1 问题 A：完工误判（白白派单给主 AI）

**复现场景**（来自实际截图）：

```
12:34:01  Disp   Plan PENDING_DECISION → waking supervisor
12:34:53  Ctr    Issued DECISION_REQUEST → SUPERVISOR        (开始 90s 倒计时)
12:36:23  Guard  R3 escalated, supervisor 90s 未 discharge   (deadline 到点)
12:36:23  Guard  Liveness system_takeover → 合成 TASK_ASSIGNMENT 给 MAIN_AI
```

但 supervisor 自己的"思考"日志显示它**没卡死**，只是在琢磨终态怎么表达：

> `emit_action` 工具的 enum 不含 `complete_plan`，只能用 `approve_and_continue + mark_step_complete=7` 走完。

也就是说 supervisor 在最后一步同时做两件重活：① 凑出一个不存在的"完工"动作 ② 合成完工总结表。耗时 > 90s 不稀奇。结果：

- guard 误判"卡死" → 派单给主 AI（实际 Plan 已经全部 done）
- 主 AI 收到一份"系统代派"伪任务 → 与几秒后 supervisor 自己 `approve_and_continue` 的输出撞车

### 1.2 问题 B：用户想暂停 supervisor 补充上下文

**用户视角**：主 AI 已经有 ESC 停止按钮，supervisor 也应该有"停一停我要补充信息"的能力。

**现状盘点**（代码扒了一遍后发现 80% 已就绪）：

| 能力 | 状态 | 位置 |
|---|---|---|
| supervisor 停止按钮 | ✅ 已有 | `SupervisorPane.tsx:82`（右上角 `codicon-debug-stop`） |
| 停止 → 中断 daemon 流 | ✅ 已有 | `PairHandler.java:170` → `SupervisorBridge.interrupt()` → daemon `supervisor.interrupt` RPC → SDK `query.interrupt()` |
| supervisor 输入框 | ✅ 已有 | `SupervisorChatInput.tsx` |
| 用户输入 → 注入 supervisor 上下文 | ✅ 已有 | `PairHandler.java:651` → `EventBus.publishUserInput()` → `user_input` 事件入 supervisor 流 |
| supervisor 忙时排队 + 空闲自动 flush | ✅ 已有 | `SupervisorChatInput.tsx:226`、`PairContext.tsx:725` |

**真正缺的就两块**：

1. **按钮 enable 条件错了**：`SupervisorPane.tsx:87` 用 `coordinatorBusy = thinking || streaming` 判定。截图那种 supervisor 已经停止思考但 guard 倒计时还在跑的场景，按钮 disabled，用户拦不住误派单。
2. **用户点停止后，guard 倒计时没清栈**：现在只中断 daemon 流，guard 的 R3 deadline 仍然在数 → 用户拦住了 supervisor，但 90s 后还是会被 takeover。

### 1.3 统一根因

两个问题本质上是**同一个抽象缺失**：

> Pair Liveness guard 只认识两种结局——"supervisor emit 了派单动作 → discharge" 和 "supervisor 90s 没动 → takeover"。**缺第三种合法的非派单 discharge**。

无论是"supervisor 自己走完 plan 不需要再派单"，还是"用户暂停 supervisor 不让它再派单"，对 guard 来说都应该是"这次唤醒不需要派单，停掉倒计时"。

---

## 2. 设计：supervisor 状态机

### 2.1 状态定义

**每个 supervisor 实例**（按 `pairId + supervisorId` keyed）只有两个状态：

```
       ┌────────────── user_input 到达 ──────────────┐
       ▼                                            │
   [ACTIVE]                                      [IDLE]
       │                                            ▲
       └───── emit_action(complete) ────────────────┘
             OR  用户点停止按钮（前端发的事件本质是一个外部强制 complete）
```

### 2.2 状态语义

| 状态 | 含义 | guard 行为 |
|---|---|---|
| **ACTIVE** | supervisor 当前有未完成的对话栈（在思考 / 在等 emit / 在 streaming） | R3 deadline 倒计时生效 |
| **IDLE** | supervisor 当前没有未完成的对话栈，没有"决策义务" | **R3 倒计时不 arm**，guard 不会触发 takeover |

### 2.3 三个场景统一收敛

| 场景 | 触发方 | 状态转移 | 副作用 |
|---|---|---|---|
| supervisor 走完 plan | supervisor 自己 emit `complete` | ACTIVE → IDLE | guard R3 清栈，不派单 |
| 用户中途按"停止" | 前端 stop button | ACTIVE → IDLE | 中断 daemon 流 + guard R3 清栈 |
| 用户补充新内容 | 前端 input box 发 `user_input` | IDLE → ACTIVE | guard R3 重新 arm |
| 主 AI 报告轮次完成，supervisor 决定下一步 | 系统 wake supervisor | 保持 ACTIVE | guard R3 启动 |

**关键 insight**：之前讨论里出现过的 `PAUSED_FOR_USER` 中间态可以**砍掉**。"暂停"在用户视角是"暂停"，在系统视角和"完成"是同一件事——**当前没有未完成的对话栈**。

### 2.4 不同 persona 怎么办

> "不同监督者只是定义的系统提示词不一样"

既然如此，**状态机是 supervisor 实例级的，与 persona 无关**。所有用 `SupervisorAgent` 配置出来的实例都跑同一个 ACTIVE/IDLE，guard 同一种判定，无需 per-role 副作用策略。

reviewer 角色目前是 UI-only（`PairContext.tsx:438-445`，没有 daemon backing），状态机天然只对实际有 daemon 实例的 supervisor 生效；未来 reviewer 接 daemon 时不需要任何协议改动。

---

## 3. 实施改动清单

| # | 模块 | 改动 | 估算 |
|---|---|---|---|
| 1 | 新增 `SupervisorStateRegistry` | `(pairId, supervisorId) → ACTIVE/IDLE` 的并发安全存储 + 事件订阅接口 | ~50 行 |
| 2 | `ActionRouter.routeAction()` | 加 `complete` case：调 `registry.toIdle(pairId, supervisorId)` | ~10 行 |
| 3 | `DeadlockGuard.evaluatePendingDecisionLiveness()` | 检查 `registry.isIdle(...)` 时直接 return，不 arm R3 | ~3 行 |
| 4 | `EventBus.publishUserInput()` | 之前调 `registry.toActive(pairId, supervisorId)` | ~5 行 |
| 5 | `PairHandler.handleSupervisorInterrupt()` | 中断后调 `registry.toIdle(...)` | ~5 行 |
| 6 | `PairStatusSnapshot` / `PairStatusPusher` | 暴露 `supervisorState` 字段，前端订阅 | ~10 行 |
| 7 | `SupervisorPane.tsx` 按钮 disabled 条件 | 改为 `supervisorState !== 'ACTIVE'`；停止后给 input 框 auto-focus | ~5 行 |
| 8 | `emit_action` enum schema | `mcp__supervisor__emit_action` 的 action 字段加 `complete` 选项 | daemon 侧 ~5 行 |

**合计 ~80 行 Java + ~5 行 TS + 极少量 daemon JS。**

整体复杂度：**小**。基础设施（中断 RPC、user_input 注入、停止按钮 UI、输入框、排队、自动 flush）2026-05-25 那轮 "FUNDAMENTAL FIX" 就铺好了，本次只是补上"状态机感知"这一层抽象。

---

## 4. 关键文件指引

| 文件 | 行号 | 角色 |
|---|---|---|
| `src/main/java/.../session/pair/guard/DeadlockGuard.java` | 55、137-150 | `PENDING_DECISION_STUCK_MS = 60_000L` 和 `evaluatePendingDecisionLiveness()`——需要加 state 判断的位置 |
| `src/main/java/.../session/pair/ActionRouter.java` | 26-34 | 现有 action 列表（`inject_prompt / retry_with_hint / escalate_to_human / approve_and_continue / request_amendment / wait`）——加 `complete` 处 |
| `src/main/java/.../session/pair/EventBus.java` | 260 | `publishUserInput()`——加 toActive 副作用处 |
| `src/main/java/.../bridge/SupervisorBridge.java` | 430-432、616-618 | `interrupt()` 现有实现——配合 PairHandler 一起调 toIdle |
| `src/main/java/.../handler/PairHandler.java` | 170-186 | `handleSupervisorInterrupt()`——补 toIdle 调用 |
| `src/main/java/.../session/pair/PairStatusSnapshot.java` / `PairStatusPusher.java` | — | 加字段、推到前端 |
| `webview/src/components/SupervisorPair/SupervisorPane.tsx` | 82-90 | 现有停止按钮 + disabled 条件 |
| `webview/src/components/SupervisorPair/PairContext.tsx` | 649-673 | `sendUserInputToSupervisor`——前端已有，无需改 |

---

## 5. 开放问题（开工前需要拍板）

### Q1：`emit_action(complete)` 是否允许"中途完成"？

即 plan 还有 step 没走完，但 supervisor 觉得"够了"，能不能直接 emit `complete`？

**倾向答案：允许**。supervisor 是有 agency 的角色，它说够了就是够了。未执行的剩余 step 在前端 plan 视图标记为"未执行（supervisor 提前结束）"，让用户知情但不阻止行为。

### Q2：用户在 IDLE 状态发 `user_input`，supervisor 的上下文是延续还是 fresh？

**倾向答案：延续**。supervisor 的对话历史不重置——新 `user_input` 就是历史里的下一条 user message。supervisor 自然能看到之前所有内容（包括它自己之前 emit 的 `complete` 决定）并做出回应。

这样用户的补充总是"在已知背景上追加新需求"，符合自然语义。如果用户想要"完全重新开始"，应该走另一个操作（清空 supervisor session 或者干脆 rotate）。

### Q3：用户点停止时，supervisor 正在 streaming 的 partial response 怎么处理？

**倾向答案：保留可见、标灰、不计入决策**。

- 留可见：让用户清楚知道"它刚才写到哪了"，便于决定下一步
- 标灰：视觉上区别于已完成的轮次
- 不计入决策：不参与后续 `[SUPERVISOR_ACTION]` 路由（partial 显然不包含完整 action JSON）

---

## 6. 实施推荐顺序

1. **`SupervisorStateRegistry` + guard 接入**（改动 #1、#3）——先把"非派单 discharge"这条路径打通
2. **`emit_action(complete)` + ActionRouter**（改动 #2、#8）——让 supervisor 能自然走完工路径（解决问题 A）
3. **`user_input` 触发 toActive**（改动 #4）——让 IDLE 后用户补充能正确回到 ACTIVE
4. **`handleSupervisorInterrupt` 接 toIdle**（改动 #5）——让用户停止按钮 discharge guard
5. **Snapshot 字段 + 前端 disabled 条件**（改动 #6、#7）——把状态暴露给用户，按钮 enable 条件正确化（解决问题 B）

每一步都能独立测试，前一步不依赖后一步。
