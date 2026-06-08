# 工作流节点「会话级恢复」编码方案（Session Resume）

> 续 `resume-and-redispatch-plan.md`（重启恢复 A 档 + 节点重新下发）。本文档只新增一项能力：**重启/重启 daemon 后，恢复节点时让 supervisor 与主 AI 续接各自之前的会话（逐字历史），再把任务重新下发进同一会话**。沿用既有线程模型（单 `wf-scheduler`）、持久化布局（`WorkflowStore`）、广播协议（`window.onWorkflow*`）、决策编号体系（D/DN）。

## 0. 背景与目标

### 现状（已核实，勿重复造）
- **主 AI 会话本就可 resume**：session_id 已捕获回传（`persistent-query-service.js:334` `finalSessionId` → `onSessionIdReceived`），transcript 落盘 `~/.claude/projects/<project>/<id>.jsonl`（`LocalHistoryDataSource.readSessionRaw`），resume 走 `params.sessionId → options.resume`（`persistent-query-service.js:136/:169`）。节点 `mainSessionId` 已绑进 L2（`PairSessionManager.bindMainSessionIdToPair:684`）。
- **重启恢复已就绪（A 档）**：`WorkflowStartupRecovery` → 还原为 `WorkflowState.PAUSED`，中断节点挂 `WAITING_HUMAN`，「恢复运行」`resumeWorkflow` + 节点「重新下发」`redispatchNode`（`SupervisorWorkflowManager:1365`）。
- **重新下发当前 = 全新会话**：重量级 re-launch 杀旧 pair + `startNode`（`redispatchNodeInternal:1431-1442`），supervisor 与主 AI 都从零开始 —— **这正是本方案要解决的痛点**。

### 现状缺口（本方案补齐）
- **supervisor 会话不可 resume**：turn 循环只在 `result` 取了 `usage` 就 break（`supervisor-channel.js:957-959`），**从不捕获 SDK 的 `session_id`**；`system/init` 消息也不处理；`startSupervisorSession` 的 query 没有 `resume` 选项（`:457-517`），`SupervisorBridge.startWithHandoff` 没有 resume 入参（`:349-388`）。
- **NodeRuntime 不持久化任何 session_id**（只有 `pairId/windowId/...`）。
- **Java 协调层（PlanStateMachine / ContractRegistry）是内存态**，重启归零 —— resume 回来的 LLM「记忆」与「全新协调状态」会脱节（同压缩死循环根因）。

### 目标
1. 恢复节点时，supervisor 与主 AI **各自 resume 之前的会话**（逐字历史进上下文），用户重新下发的任务进入**同一会话**。
2. 「执行了多少 / 哪些完成 / 从哪开始」**交给 LLM 判断**（不做合同/进度对账），但提供一段**重启重定向**让它别只信记忆、去核对真实状态。
3. 失败可回退：transcript 缺失 / resume 不被接受 → 优雅退化为「全新会话 + handoff 摘要」，绝不卡死。

### 非目标
- 不做 plan 状态机 / ContractRegistry 的精确持久化与重建（交给 LLM）。
- 不做跨机器 / 跨项目路径迁移恢复（transcript 依赖本地磁盘 + 项目路径不变）。
- 不改 supervisor 的事件摘要管线（它的「历史」本就是事件摘要 + emit_action 决策流，resume 即可看到）。

---

## 1. 决策摘要（SR = Session Resume）

| 编号 | 决策 | 取值 |
|---|---|---|
| **SR1** | 恢复粒度 | 双会话 transcript-resume（主 AI + supervisor），不做摘要重建（除非回退） |
| **SR2** | 进度对账 | **不做**，交给 LLM；仅提供重启重定向 prompt + 一次权威状态重注入（复用 `ActionRouter.reprimeAfterCompaction`） |
| **SR3** | supervisor session_id 捕获 | daemon turn 循环捕获 `msg.session_id`（init/任意消息，首见即存 `runtime.sessionId`），经 NDJSON `[SUPERVISOR_SESSION]` 行回传 Java |
| **SR4** | supervisor resume 入参 | `startSupervisorSession` 接 `resumeSessionId` → query `options.resume`；systemPrompt 仍传（resume 时 SDK 重放 transcript，persona 已内嵌，重传幂等/再断言） |
| **SR5** | resume 是否成功的判定 | 起会话后首个消息回报的 `session_id` **等于**请求的 id → 视为续接成功；不等（SDK 新建了会话）→ 判为 resume 未生效，走回退 |
| **SR6** | 回退策略 | 任一会话 transcript 缺失/路径不符/resume 未生效 → 该会话退化为全新启动；supervisor 退化时附 handoff 摘要（复用 `startWithHandoff` + 落盘 L2/决策史） |
| **SR7** | 持久化字段 | `NodeRuntime` 增 `mainSessionId` / `supervisorSessionId` / `supervisorGeneration`，随 `execution.json` 落盘 |
| **SR8** | 恢复触发 | 复用现有「恢复运行 + 重新下发」；重新下发在「有持久化 session_id」时默认走 **resume 模式**（新 mode=`resume`），否则按既有自适应（restart/auto） |
| **SR9** | 重新下发语义 | resume 模式：起带 `resume` 的 pair → 注入重定向 → 把节点任务作为 kickoff 进同一会话（**不是新会话**） |
| **SR10** | 主 AI resume 落点 | 节点窗口的 `ClaudeSession` 首轮带 `params.sessionId = mainSessionId`（复用现成 resume），由 supervisor 的 inject_prompt 触发 |
| **SR11** | 远程/容器 | 远程模式 transcript 在远端容器磁盘；resume 走远端 `readSessionRaw` 同源（`RemoteHistoryDataSource`），本期仅保证本地档；远程标记为 follow-up |

> 复用既有：D2 节点=tab=pair、D11 `WAITING_HUMAN` 不释放 slot、D18 重启还原 PAUSED、D19 中断节点=WAITING_HUMAN、D21 重新下发自适应、`reprimeAfterCompaction`（2026-06-05）、`onHumanResumed`（2026-06-05 刚接线）。

---

## 2. 架构总览

### 2.1 两会话的不对称（一句话）
- **主 AI**：现成可 resume，只需把 `mainSessionId` 透传到节点首轮。**改动几乎为零。**
- **supervisor**：底层同一 SDK、transcript 大概率已落盘（SDK 默认，无关闭项），但 **session_id 没人存、query 没 resume**。补这两处接线即可。**唯一运行时未知**：streaming-input + 自定义字符串 systemPrompt 能否干净 resume —— 用 SR5 的「session_id 一致性」自检 + SR6 回退兜住，不阻塞落地。

### 2.2 端到端数据流（恢复一个中断节点）

```
重启 → WorkflowStartupRecovery 读 execution.json(含 session_id) → PAUSED
  → 用户「恢复运行」(resumeWorkflow) → 中断节点 = WAITING_HUMAN
  → 用户/自动「重新下发」(redispatchNode, mode=resume)
      → IdeNodeLauncher.startNodePair(resumeSupervisorSessionId, mainSessionId)
          → PairHandler.startPairWired(StartPairParams + resumeSupervisorSessionId)
              → SupervisorBridge.startWithHandoff(... resumeSessionId)
                  → daemon supervisor.start { resumeSessionId } → query{ options.resume }
                  → 首消息 session_id == 请求 id ? 续接成功 : 回退(SR6)
              → 注入重启重定向(reprime 同款) 
          → 主 AI: 节点窗口 ClaudeSession 首轮 params.sessionId = mainSessionId(resume)
      → sendResumeKickoff(节点任务) 进同一会话
  → 节点 WAITING_HUMAN → RUNNING(复用 onHumanResumed → 看门狗反向映射)
```

---

## 3. 持久化 schema 变更（SR7）

### 3.1 `NodeRuntime.java`
```java
public String mainSessionId;          // 主 AI 会话 id（resume 用）
public String supervisorSessionId;    // supervisor 会话 id（resume 用）
public Integer supervisorGeneration;  // 轮换代（决定 resume 哪一代/handoff 基线）
```
- Gson 默认随 `execution.json` 落盘（与既有 `pairId/windowId` 同位置，`WorkflowStore.persist`）。null 兼容旧档。
- 写入时机：见 §5.1（捕获）+ §5.4（rotation 更新 generation）。

### 3.2 不新增枚举、不新增文件
- 复用 `WorkflowState.PAUSED` / `NodeStatus.WAITING_HUMAN`。
- 重新下发新增的 `mode=resume` 是字符串参数，不动枚举。

---

## 4. Daemon 改动（`ai-bridge/channels/supervisor-channel.js`）

### 4.1 捕获 session_id（SR3）
在 `collectAssistantTurn` 的 `for await (const msg of runtime.query)` 循环顶部：
```js
if (msg && msg.session_id && runtime.sessionId !== msg.session_id) {
    runtime.sessionId = msg.session_id;
    // 首见即回传，供 Java 持久化（标签行,daemon.js 透传给 Java IPC）
    process.stdout.write(`[SUPERVISOR_SESSION] ${JSON.stringify({
        pairId: runtime.pairId, supervisorId: runtime.supervisorId, sessionId: msg.session_id,
    })}\n`);
}
```
- `SupervisorRuntime` 加字段 `sessionId = null`（构造器 §426 附近）。
- 同时新增导出 `getSupervisorSessionId({pairId, supervisorId})`（spike/测试与 Java 兜底读取用）。

### 4.2 resume 选项（SR4 / SR5）
`startSupervisorSession(params)` 解构加 `resumeSessionId`；query options 加：
```js
options: {
    ...,
    systemPrompt: runtime.systemPrompt,
    ...(resumeSessionId && { resume: resumeSessionId }),
}
```
- 启动后记 `runtime.requestedResumeId = resumeSessionId`；首消息捕获到 `session_id` 时与之比对，**不等则 `process.stdout.write('[SUPERVISOR_RESUME_MISS] ...')`**，Java 据此判 SR6 回退。
- `[supervisor] started` 日志补 `resume=<id|none>`。

### 4.3 「daemon 重启懒启动」对齐
`postEventToSupervisor` 已有「runtime 不存在 → 触发 Java 侧 lazy start」钩子（`:540-543`）。Java 侧 lazy start 时若该 pair 有持久化 `supervisorSessionId`，应带 `resumeSessionId` 重启（见 §5.3）。

---

## 5. Java 改动

### 5.1 捕获并上抛 supervisor session_id
- `SupervisorBridge`：解析 daemon 的 `[SUPERVISOR_SESSION]` / `[SUPERVISOR_RESUME_MISS]` 行 → 回调 `onSupervisorSession(sessionId)` / `onResumeMiss()`。
- `PairSession`：加 `supervisorSessionId` 字段 + `setSupervisorSessionId/getSupervisorSessionId`；`PairHandler.startPairWired` 里把 `SupervisorBridge` 的 `onSupervisorSession` 接到 `session.setSupervisorSessionId(...)`，并通知工作流层。
- `SupervisorWorkflowManager`：新增 `onSupervisorSessionCaptured(pairId, sessionId)` → `submit(() -> { 把 sessionId 写进对应 NodeRuntime.supervisorSessionId; persist(); })`。主 AI 侧复用既有 `mainSessionId`（节点 pair 已绑），同样落进 `NodeRuntime.mainSessionId`。

### 5.2 透传 resume 入参（StartPairParams → bridge）
- `StartPairParams` 加 `@Nullable String resumeSupervisorSessionId`（已有 `mainSessionId`）。
- `PairHandler.startPairWired` → `PairSessionManager.startPair` → `SupervisorBridge.startWithHandoff(... , resumeSessionId)`（新增形参，透到 daemon params）。
- `IdeNodeLauncher.startNodePair`：当 `mode=resume`，从 NodeHandle/NodeRuntime 读出 `supervisorSessionId` 填入 `StartPairParams.resumeSupervisorSessionId`；主 AI 侧把 `mainSessionId` 透给节点窗口 `ClaudeSession`（SR10）。

### 5.3 重新下发 resume 模式（SR8/SR9）
`SupervisorWorkflowManager.redispatchNodeInternal` 增 `mode=resume` 分支（与既有 `auto`/`restart` 并列）：
- 前置：`NodeRuntime.supervisorSessionId != null`（否则 fallback 既有 restart）。
- 行为：**重量级 re-launch 路线**（杀旧 pair + `startNode`），但 `startNode → startNodePair` 带上 `resumeSupervisorSessionId` + `mainSessionId`，使新 pair 起在历史会话上。
- 起好后：先注入「重启重定向」（§5.5），再 `sendResumeKickoff`（节点任务进同一会话）。
- daemon lazy-start（§4.3）走同一透传路径。

### 5.4 主 AI resume（SR10）
- 节点窗口 `ClaudeSession` 首轮请求带 `params.sessionId = mainSessionId`（复用 `persistent-query-service` 的 `resume`）。落点：`IdeNodeLauncher` 创建节点 `ClaudeChatWindow` 后，给其 session 设「待 resume 的 sessionId」，由 supervisor 首个 `inject_prompt` 触发的主 AI 首轮消费。
- 失败回退（SR6）：transcript 不存在 → 首轮自然新建会话（`requestedSessionId` 对应的 .jsonl 缺失时 SDK 行为需确认；保守做法：Java 先用 `LocalHistoryDataSource` 探测 `<project>/<mainSessionId>.jsonl` 存在性，不存在则不传 resume）。

### 5.5 重启重定向 + reprime（SR2，焊缝，不可省）
新增 `ActionRouter.reprimeAfterResume()`（与 `reprimeAfterCompaction` 同形，复用 `buildWaitRejectionStateSnapshot`）：
- 文案：「你的会话已从历史 resume，但 **Java 协调状态（合同/plan）是全新的（PENDING_DECISION）**。不要只信记忆里的『我在等合同 C』；先核对真实状态（git status / 读改动文件 / 看主 AI 最近一段输出），判断哪些已完成、从哪继续，再 emit_action（inject_prompt / complete_plan / escalate）。禁止 wait。」
- 调用点：节点 pair resume 起好、kickoff 前，由 `SupervisorWorkflowManager` 触发一次。
- 同时给主 AI 侧（可选）注入一行「上下文已恢复，先 `git status` 核对再继续」。

---

## 6. 失败模式与回退（SR6，必须覆盖）

| 场景 | 检测 | 处理 |
|---|---|---|
| supervisor transcript 不在/路径变 | 首消息 `session_id != 请求 id` 或 `[SUPERVISOR_RESUME_MISS]` | 该会话退化全新 + handoff 摘要（L2/决策史拼 successorPrompt，复用 `startWithHandoff`） |
| 主 AI .jsonl 缺失 | Java 探测 `<project>/<mainSessionId>.jsonl` 不存在 | 不传 resume，主 AI 全新（节点任务里补「之前已做过部分，先核对仓库」） |
| session_id 从未捕获（老档/启动即崩） | `NodeRuntime.supervisorSessionId == null` | 走既有 restart（全新），不阻塞 |
| streaming+自定义 prompt resume 实测不行 | SR5 一致性自检常态 miss | 全局降级：supervisor 恒走「全新 + handoff 摘要」，主 AI 仍 resume（仍达成 80% 价值） |
| resume 后立刻又压缩 | `compact_boundary` 紧随 resume | 已有 `reprimeAfterCompaction` 兜（与本方案 reprime 同源） |

> 设计原则：**任何 resume 失败都静默退化为现有「全新会话」行为**，绝不卡死、绝不阻塞工作流推进。

---

## 7. 分阶段实施

### Phase 1 — 主 AI resume（小、价值大、零风险）
- §3.1（仅 `mainSessionId`）、§5.1（mainSessionId 落 NodeRuntime）、§5.4（节点窗口主 AI 首轮带 resume）、§5.3 的 resume 模式骨架。
- 出口标准：重启 → 恢复节点 → 主 AI 续上历史、能看到之前编码记录；supervisor 仍全新（暂不动）。

### Phase 2 — supervisor 捕获 + resume（中，含运行时验证）
- §4.1/§4.2（daemon 捕获 + resume 选项 + 一致性自检）、§5.1/§5.2（Java 捕获持久化 + 透传）、§5.5（reprimeAfterResume）。
- 出口标准：重启 → supervisor 续上历史（决策时间线/事件流可见），SR5 自检通过；不通过则 SR6 全局降级且工作流仍正常。

### Phase 3 — 打磨 / 回退完备 / 远程（可选）
- §6 全部回退分支、`supervisorGeneration` 选代、远程 transcript（SR11）、可观测性（§9.4）、UI 标识「已从历史恢复」。

> 「异步到位」= 三阶段都做，但**按此顺序提交**，每阶段独立可测、独立可回退。

---

## 8. 关键风险与待确认

1. **streaming-input + 自定义 systemPrompt 能否 resume**（唯一运行时未知）：用 SR5 自检 + SR6 降级兜住，不阻塞；Phase 2 的集成测试（§9.2）会第一时间暴露。
2. **resume 重放长 transcript 的成本**：与 supervisor「靠压缩省 token」设计相悖；若实测一 resume 即压缩，考虑 SR6 降级或限制只对「近 N 轮」resume（SDK 不支持部分 resume 时退摘要）。
3. **主 AI .jsonl 路径依赖项目路径不变**：换机/换路径必失效 → Phase 1 先做存在性探测再决定传不传 resume。

---

## 9. 测试方案（重点：方便异步验证）

### 9.1 Java 单测（不需 IDE/LLM）
- `WorkflowStoreTest`：`NodeRuntime` 三个新字段 round-trip 落盘/读回（含 null 兼容旧档）。
- `SupervisorWorkflowManagerTest`：
  - `redispatchNode(mode=resume)` 在有/无 `supervisorSessionId` 时分别走 resume / restart 分支（用 fake `NodeLauncher` 断言透传了 `resumeSupervisorSessionId`）。
  - resume 后节点 `WAITING_HUMAN → RUNNING`（复用既有看门狗反向映射测试基建）。
- `ActionRouterTest`（若有）：`reprimeAfterResume()` 在 ACTIVE/无 open MAIN_AI 合同时按文案 `postEvent`。

### 9.2 Daemon 集成测试（不需 IDE，需 SDK+鉴权）— **这就是原 spike，固化为可重复测试**
新增 `ai-bridge/test/supervisor-resume.test.mjs`：
1. `startSupervisorSession({pairId, supervisorId, name, planContent: '记住口令: BANANA-42', ...})`。
2. `postEventToSupervisor` 一轮（让 transcript 落内容）。
3. `getSupervisorSessionId()` 取 `sid1`。
4. `stopSupervisorSession()`。
5. `startSupervisorSession({..., resumeSessionId: sid1})`。
6. `postEventToSupervisor({event: '复述刚才的口令'})`，读回 turn 文本。
7. **断言**：`getSupervisorSessionId() === sid1`（SR5 续接成功）**且** 文本含 `BANANA-42`（历史真进上下文）。打印 `PASS/FAIL`。

运行方式（写进文件头注释）：
```
# 需与 daemon 同一鉴权（claude CLI 已登录 或 ANTHROPIC_API_KEY），同一 node 版本
cd ai-bridge && node test/supervisor-resume.test.mjs
# 期望输出: [SUPERVISOR_SESSION] sid=... → RESUME ok=true recall=true → PASS
```
> 这个脚本**就是你要的「临时验证入口」的永久化版本**：通了证明 SR5/2a，整条 supervisor resume 路成立；FAIL 则触发 SR6 全局降级决策。

### 9.3 手动 E2E（真机）
1. 起一个会触发主 AI 实际编码的工作流节点，跑到一半（决策时间线有 ≥2 条、主 AI 改过文件）。
2. **硬杀 IDE**（或 daemon）。
3. 重开 → 工作流 PAUSED → 「恢复运行」→ 节点 WAITING_HUMAN → 「重新下发」(resume)。
4. 验收：
   - 主 AI 面板**能往上翻到重启前的编码记录**；supervisor 面板**能看到重启前的决策/事件**。
   - 节点状态 `WAITING_HUMAN → RUNNING`，继续推进、不卡死、不从头重做已完成步骤。
   - `idea.log` 出现 `resume sessionId=...`（supervisor）、主 AI `resume` 命中、`reprimeAfterResume` 注入一次。

### 9.4 可观测性（排查友好）
- daemon：`[SUPERVISOR_SESSION]` / `[SUPERVISOR_RESUME_MISS]` / `started ... resume=<id>`。
- Java：协调者事件条新增 `session_resumed`（「已从历史恢复 supervisor=<id> / 主AI=<id>」）/ `resume_fallback`（「resume 未生效，已降级全新+摘要」），走 `PairStatusPusher.recordCoordinatorEvent`，在 cockpit 决策条可见。

---

## 10. 落点速查（按文件）

| 文件 | 改动 |
|---|---|
| `ai-bridge/channels/supervisor-channel.js` | 捕获 `session_id`(§4.1)、`resume` 选项 + 一致性自检(§4.2)、`getSupervisorSessionId` 导出 |
| `ai-bridge/test/supervisor-resume.test.mjs` | 新增集成测试(§9.2) |
| `NodeRuntime.java` | 三新字段(§3.1) |
| `SupervisorBridge.java` | `startWithHandoff` 加 `resumeSessionId` 形参；解析 `[SUPERVISOR_SESSION]`/`[SUPERVISOR_RESUME_MISS]` → 回调 |
| `PairSession.java` | `supervisorSessionId` 字段 + getter/setter |
| `PairSessionManager.java` | `StartPairParams.resumeSupervisorSessionId`；`startPair` 透传 |
| `PairHandler.java` | `startPairWired` 接 `onSupervisorSession` → setter + 通知工作流 |
| `IdeNodeLauncher.java` | `startNodePair` 透传 resume id（supervisor + 主 AI） |
| `SupervisorWorkflowManager.java` | `onSupervisorSessionCaptured` 落 NodeRuntime；`redispatchNodeInternal` 加 `mode=resume`；resume 前触发 reprime |
| `ActionRouter.java` | 新增 `reprimeAfterResume()`（复用 `buildWaitRejectionStateSnapshot`） |
| `WorkflowHandler.java` | `workflow_redispatch_node` 透传 `mode` 已支持，无需改（前端传 `resume`） |
| webview `NodeDrawer.tsx` | 「重新下发」按钮在有历史会话时显示「带历史恢复」并传 `mode=resume`（可选打磨） |

---

*本方案对应 commit message 约定：每阶段独立提交，标题前缀 `[session-resume P1/P2/P3]`。*
