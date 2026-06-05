# 工作流「重启恢复 + 节点重新下发」编码方案

> 续 `coding-plan.md` / `cockpit-plan.md`。本文档只覆盖两项新能力，沿用既有线程模型（§15 单 `wf-scheduler` 线程）、持久化布局（§10 `WorkflowStore`）、广播协议（§4 `window.onWorkflow*`）与决策编号体系（D/DN）。

## 0. 背景与目标

现状（已实现，勿重复造）：
- 持久化已就绪：`WorkflowStore` 每次状态跃迁原子落盘 `definition.json` / `execution.json` / `nodes/<name>/plan.md`（`SupervisorWorkflowManager.persist()` `:958`）。
- 重启处理是「作废」：`WorkflowStartupRecovery`（ProjectActivity）→ `recoverStaleExecutionsOnStartup()` `:840` 把遗留 RUNNING 的执行整体标 `ABORTED`（D17：MVP 不续跑）。
- 卡住兜底已有：`sendKickoff()` `:488`、一次性 `scheduleKickoffRetry()` `:512`、看门狗 `attachWatchdogBackstop()` `:730`。

目标：
1. **重启恢复（A 档 + 一键恢复）**：重启后把 DAG 画面与状态机完整还原成一个**已暂停**的执行，**不自动起窗、不自动调度**；用户点一次「恢复运行」才开 cockpit、续跑"安全前沿"。被中断的半成品节点挂 `WAITING_HUMAN` 等人工重新下发。
2. **节点重新下发（自适应）**：点节点 → 抽屉里「重新下发任务」按钮。有存活 pair → 重发 kickoff（轻）；无 pair / 已死 → 杀旧 pair + 整节点重启（重）。

非目标（本期不做）：B 档全自动续跑、C 档远程 reattach、停滞计时/活动提示（"先只实现按钮"）。

## 1. 决策摘要

| 编号 | 决策 | 取值 |
|---|---|---|
| **D18** | 重启恢复档位 | A 档：还原状态 + 安全前沿续跑；**续跑由"恢复运行"手动触发**，非启动自动 |
| **D19** | 中断节点的状态表示 | 复用 `WAITING_HUMAN`（reason=「IDE 重启，任务已中断」），不新增 NodeStatus 枚举 |
| **D20** | 恢复态的执行状态 | 新增 `WorkflowState.PAUSED`：已加载、持有单工作流锁、不调度，等待 resume |
| **D21** | 重新下发语义 | 自适应：`handle.pair` 存活→重发 kickoff；否则→停旧 pair + 整节点重启 |
| **D22** | 并发满时重新下发 | 排队（进 `readyQueue` + `pump()`），不拒绝；UI 提示"已排队" |
| **D23** | slot 占用记账 | 引入显式 `slotHolders` 集合作为唯一真相，替代"按 status 反推" |
| **DN10** | 重新下发前置条件 | 仅在 `state==RUNNING` 时可用；`PAUSED` 时按钮禁用，提示先「恢复运行」 |
| **DN11** | 持久化补全 | `NodeRuntime.changedFiles/summary` 去 `transient`，随 `execution.json` 落盘（下游 plan 组装在重启后仍完整） |
| **DN12** | 「强制重启节点」 | 可选次按钮（mode=restart），MVP 可裁剪；主按钮始终为自适应 |

> 复用既有：D2 节点=tab=pair、D4 不重试不回滚（重新下发是"人在环"的主动重跑，**无回滚**，UI 必须二次确认）、D11 `WAITING_HUMAN` 不释放 slot、D12 单工作流锁、D13 跑完保留。

---

## 2. 状态模型变更

### 2.1 `WorkflowState` 新增 `PAUSED`

Java `WorkflowState.java`：
```java
public enum WorkflowState {
    EDITING,
    RUNNING,
    PAUSED,     // ← 新增：重启后已加载、持锁、未调度，等待用户「恢复运行」
    COMPLETED,
    ABORTED
}
```
前端 `types.ts`：
```ts
export type WorkflowState = 'EDITING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'ABORTED';
```
Gson 按 `name()` 序列化，两侧字面量必须一致（与既有约定相同）。

### 2.2 中断节点 = `WAITING_HUMAN`（D19）

不动 `NodeStatus` 枚举。rehydrate 时把落盘为 `RUNNING` 的节点改写为 `WAITING_HUMAN`，`escalationReason` 写「IDE 重启，任务已中断，可重新下发」。好处：前端 `WAITING_HUMAN` 的红框/抽屉提示/升级 toast 全部复用；重新下发按钮天然对 `WAITING_HUMAN` 可见。

> 可选打磨（非 MVP）：`NodeRuntime` 加一个布尔 `interrupted`，让前端把"重启中断"与"真实升级"在文案/颜色上区分。本期不做。

### 2.3 slot 显式记账（D23）

现状 slot 是隐式的：`pump()` 里 `tryAcquire`，`onNodeReport(DONE)` 里 `release`，`WAITING_HUMAN` 不释放（`:337`）。"某节点是否占着一个 permit"目前只能靠 status 反推 —— 但恢复场景把这个前提打破（重启后新 `Semaphore` 是满的，活跑中的 `WAITING_HUMAN` 占 slot、恢复出来的 `WAITING_HUMAN` 不占），`onNodeTabClosed` 又会移除 handle 却仍持 slot。因此引入：

```java
// scheduler-thread-only，与 slots 同生命周期
private final Set<String> slotHolders = new HashSet<>();
```
统一规则（全部在 scheduler 线程）：
- `pump()` 成功 `tryAcquire` 后：`slotHolders.add(name)`；
- `onNodeReport(DONE)` `release` 前：`slotHolders.remove(name)`；
- `redispatchNode` 重启节点：若 `!slotHolders.contains(name)` 才 `tryAcquire + add`，否则复用；
- `rehydrate` / `resumeWorkflow`：`slotHolders.clear()` 且 `slots = new Semaphore(concurrency)`。

判断"是否已占 slot"一律查 `slotHolders`，不再看 status/handle。

### 2.4 持久化补全（DN11）

`NodeRuntime.java` 去掉两个 `transient`：
```java
public List<String> changedFiles;  // 去 transient
public String summary;             // 去 transient
```
原因：rehydrate 后下游节点 `assembleAndWritePlan` 的「上游产出 / 改动文件」需要它们；`changedFiles` 在磁盘 COMPLETION_REPORT.md 里无结构化来源，必须随 `execution.json` 落盘。
代价：这两个字段会出现在 `window.onWorkflowExecutionUpdate` 线上数据里（仅 DONE 节点非空），前端忽略未知字段，无害。`coding-plan.md` §5 中"MUST NOT leak to wire"的注释一并更新。

---

## 3. 引擎改造（`SupervisorWorkflowManager`）

### 3.1 `rehydrate`：替换 `recoverStaleExecutionsOnStartup`

启动钩子 `WorkflowStartupRecovery` 调用入口不变，方法体改为"加载成 PAUSED"而非"标 ABORTED"。

```java
/** 启动恢复（D18/D20）：把遗留 RUNNING 的执行加载成 PAUSED，等待用户「恢复运行」。 */
public void rehydrateOnStartup() {
    submit(() -> {
        WorkflowExecution chosen = null;
        WorkflowDefinition chosenDef = null;
        for (WorkflowDefinition def : store.loadAll()) {
            if (def == null || def.id == null) continue;
            WorkflowExecution e = store.loadExecution(def.id);
            if (e == null || e.state != WorkflowState.RUNNING) continue;
            // 单工作流锁：理论只会有一个 RUNNING。若有多个（跨版本脏数据），
            // 取最近更新的那个加载，其余直接标 ABORTED 落盘。
            if (chosen == null) { chosen = e; chosenDef = def; }
            else { abortPersistedOnly(def.id, e); }
        }
        if (chosen == null) return;

        // 节点状态映射：DONE 保留；RUNNING/READY → 处理；PENDING 保留。
        for (Map.Entry<String, NodeRuntime> en : chosen.nodes.entrySet()) {
            NodeRuntime r = en.getValue();
            if (r == null) continue;
            switch (r.status) {
                case RUNNING:                       // 半成品，pair 已死 → 等人工重新下发
                    r.status = NodeStatus.WAITING_HUMAN;
                    r.escalationReason = "IDE 重启，任务已中断，可重新下发";
                    r.pairId = null; r.windowId = null;
                    break;
                case READY:                         // 没起过 pair，无副作用 → 回 PENDING 重排
                    r.status = NodeStatus.PENDING;
                    break;
                default:                            // DONE / PENDING / WAITING_HUMAN 保留
                    break;
            }
        }

        // 挂回内存，但 PAUSED：不开 cockpit、不 pump。
        currentDef = chosenDef;
        nameToNode.clear();
        for (WorkflowNode n : chosenDef.nodesSafe()) nameToNode.put(n.name, n);
        chosen.state = WorkflowState.PAUSED;
        exec = chosen;
        slots = new Semaphore(chosen.concurrency > 0 ? chosen.concurrency : DEFAULT_WF_CONCURRENCY);
        slotHolders.clear();
        readyQueue.clear();
        pairToNode.clear();
        handles.clear();

        persist();
        broadcastExec();   // best-effort；webview 未就绪时前端 workflow_list 会补拉（requestList 已含 PAUSED）
        LOG.info("[Workflow] rehydrate wf=" + chosen.workflowId + " → PAUSED (await resume)");
    });
}

private void abortPersistedOnly(String id, WorkflowExecution e) {
    if (e.nodes != null) for (NodeRuntime r : e.nodes.values())
        if (r != null && r.status != NodeStatus.DONE) r.status = NodeStatus.ABORTED;
    e.state = WorkflowState.ABORTED;
    store.saveExecution(id, e);
}
```

`WorkflowStartupRecovery.execute` 改调 `rehydrateOnStartup()`，注释更新（不再是"标 ABORTED"）。

### 3.2 `resumeWorkflow`：一键恢复（D18）

```java
/** 一键恢复：开 cockpit、置 RUNNING、续跑安全前沿。中断节点仍 WAITING_HUMAN 等人工。 */
public void resumeWorkflow(@Nullable String id) {
    submit(() -> {
        if (exec == null || exec.state != WorkflowState.PAUSED) {
            broadcastOpResult("resume", false, "没有可恢复的工作流");
            broadcastExec();
            return;
        }
        if (id != null && !id.equals(exec.workflowId)) {
            broadcastOpResult("resume", false, "工作流不匹配");
            return;
        }
        // currentDef/nameToNode 已在 rehydrate 时重建；防御性再校验一次定义仍在。
        if (currentDef == null) { broadcastOpResult("resume", false, "工作流定义缺失"); return; }

        launcher.openCockpit(exec.concurrency);   // 此刻才起窗（不自动）
        exec.state = WorkflowState.RUNNING;
        slots = new Semaphore(exec.concurrency);
        slotHolders.clear();
        readyQueue.clear();
        enqueueReady();                            // 仅安全前沿（依赖已 DONE 的 PENDING）
        persist();
        broadcastExec();
        broadcastOpResult("resume", true, null);
        pump();
    });
}
```

要点：
- resume 只 pump"安全前沿"。中断的 `WAITING_HUMAN` 节点不会被 pump（它不在 readyQueue），其下游因依赖未 DONE 仍 PENDING —— 必须靠用户逐个「重新下发」推进。
- cockpit 只在 resume 时开 → 满足"重启后不自动起窗"。

### 3.3 `redispatchNode`：自适应重新下发（D21/D22/DN10）

```java
/** mode: "auto"（自适应）| "restart"（强制整节点重启，DN12 可选） */
public void redispatchNode(@Nullable String name, @Nullable String mode) {
    submit(() -> {
        if (exec == null || exec.state != WorkflowState.RUNNING) {      // DN10
            broadcastOpResult("redispatch", false, "请先恢复运行工作流");
            return;
        }
        WorkflowNode node = nameToNode.get(name);
        NodeRuntime rt = name == null ? null : exec.nodes.get(name);
        if (node == null || rt == null) { broadcastOpResult("redispatch", false, "节点不存在"); return; }
        if (rt.status == NodeStatus.DONE) { broadcastOpResult("redispatch", false, "节点已完成"); return; }

        NodeHandle h = handles.get(name);
        boolean livePair = h != null && h.pair != null;
        boolean forceRestart = "restart".equals(mode);

        // —— 确保占住一个 slot（D22：满则排队）——
        if (!slotHolders.contains(name)) {
            if (slots == null || !slots.tryAcquire()) {
                rt.status = NodeStatus.READY;
                rt.escalationReason = null;
                if (!readyQueue.contains(name)) readyQueue.add(name);
                persist(); broadcastExec();
                broadcastOpResult("redispatch", true, "并发已满，已排队");
                pump();   // 若恰好有空位则立即起
                return;
            }
            slotHolders.add(name);
        }

        rt.escalationReason = null;

        if (livePair && !forceRestart) {
            // —— (a) 轻量重发：复用现有 pair，重投同一份 plan ——
            rt.status = NodeStatus.RUNNING;
            launcher.setNodeStatus(name, NodeStatus.RUNNING);   // cockpit 复位
            sendKickoff(name, h, true);                          // EventBus.publishUserInput(getPlanContent())
            scheduleKickoffRetry(name, h);
            LOG.info("[Workflow] redispatch(re-kick) node=" + name + " pair=" + (h.pair != null ? h.pair.getPairId() : "?"));
        } else {
            // —— (b) 重量重启：杀旧 pair + 清 handle/映射 + 重新 startNode（slot 已持有，不再 acquire）——
            if (h != null && h.windowId != null) launcher.stop(h.windowId);
            handles.remove(name);
            if (rt.pairId != null) pairToNode.remove(rt.pairId);
            rt.pairId = null; rt.windowId = null;
            rt.status = NodeStatus.READY;     // startNode 的 pairStarted 会 READY→RUNNING
            launcher.setNodeStatus(name, NodeStatus.READY);
            startNode(node);                  // 内部不 acquire slot；重新 assembleAndWritePlan 吃最新上游
            LOG.info("[Workflow] redispatch(re-launch) node=" + name + " forceRestart=" + forceRestart);
        }
        persist();
        broadcastExec();
        broadcastOpResult("redispatch", true, null);
    });
}
```

关键正确性：
- **不双占 slot**：`startNode` 自身不获取 slot（acquire 只在 `pump`）。重启分支因已通过 `slotHolders` 持有 slot，故直接 `startNode` 复用；排队分支才走 `pump` 去 acquire（需在 `pump` 成功后 `slotHolders.add`，见 §2.3）。
- **不被旧 pair 误释放**：重启前移除 `pairToNode[oldPairId]`，旧 pair 即便回调 `onNodeReport(oldPairId,…)` 也因查不到 name 而被忽略（`:301-303`），不会错误 `release`。
- **re-kick 用旧 plan**：`sendKickoff` 读 `h.pair.getPlanContent()`（启动时已 seed），对"任务没接住"足够；要吃最新上游产出请走重启（DN12 强制重启）。

### 3.4 `pump()` 补 slotHolders

```java
private void pump() {
    if (slots == null) return;
    while (slots.tryAcquire()) {
        String n = readyQueue.poll();
        if (n == null) { slots.release(); break; }
        slotHolders.add(n);          // ← 新增
        startNode(nameToNode.get(n));
    }
}
```
`onNodeReport(DONE)` 释放处补 `slotHolders.remove(name)`（在 `slots.release()` 前）。

### 3.5 锁/守卫位点改动清单（PAUSED 视同持锁）

抽一个 `private static boolean isLocked(WorkflowExecution e)`：`e != null && (e.state == RUNNING || e.state == PAUSED)`，并在以下位点替换原 `state == RUNNING` 判断：

| 位点 | 现状 | 改为 |
|---|---|---|
| `startWorkflow` `:225` | `exec.state == RUNNING` → 拒绝 | `isLocked(exec)` → 拒绝"已有工作流在运行/待恢复" |
| `deleteDefinition` `:201` | `RUNNING && id 匹配` → 拒绝 | `isLocked(exec) && id 匹配` → 拒绝 |
| `abortWorkflow` `:277` | `state != RUNNING` → return | 允许 `RUNNING || PAUSED`（恢复态也可直接放弃） |
| `requestList` `:215` | 仅 RUNNING 推 exec | `RUNNING || PAUSED` 都推（前端能看到待恢复条） |
| `onNodeReport/onNodeTabClosed/onNodeDaemonDown/funnelWatchdogStall` | `state != RUNNING` → return | 维持仅 RUNNING（PAUSED 下无活 pair，不应触发） |

`abortWorkflow` 在 PAUSED 分支无活 handle 可停，直接把非终态节点标 ABORTED、`state=ABORTED`、persist、broadcast。

---

## 4. 协议 / 事件

新增两个 `workflow_*` 入站事件，对齐 `WorkflowHandler.SUPPORTED_TYPES`：

| 事件 | payload | 引擎入口 |
|---|---|---|
| `workflow_resume` | `{ id }` | `mgr.resumeWorkflow(id)` |
| `workflow_redispatch_node` | `{ nodeName, mode? }` | `mgr.redispatchNode(nodeName, mode)` |

出站复用既有：`window.onWorkflowExecutionUpdate`（PAUSED/状态变更全量快照）、`window.onWorkflowOperationResult`（`operation` 取值新增 `resume` / `redispatch`）。无需新增出站通道。

`WorkflowHandler.java`：
```java
private static final String[] SUPPORTED_TYPES = {
    "workflow_list","workflow_save","workflow_delete","workflow_run",
    "workflow_abort","workflow_jump_node","workflow_open_report",
    "workflow_resume","workflow_redispatch_node"          // ← 新增
};
// handle():
case "workflow_resume":
    mgr.resumeWorkflow(stringField(content, "id")); return true;
case "workflow_redispatch_node":
    mgr.redispatchNode(stringField(content, "nodeName"), stringField(content, "mode")); return true;
```

---

## 5. 前端改造

### 5.1 `WorkflowContext.tsx`
- 新动作：
  ```ts
  const resumeWorkflow = useCallback((id: string) =>
    wfSend('workflow_resume', JSON.stringify({ id })), [wfSend]);
  const redispatchNode = useCallback((nodeName: string, mode: 'auto'|'restart' = 'auto') =>
    wfSend('workflow_redispatch_node', JSON.stringify({ nodeName, mode })), [wfSend]);
  ```
- 派生态：`const isPaused = execution?.state === 'PAUSED';`，并入 context value（供状态条/抽屉用）。
- `onWorkflowOperationResult` 已有的 toast 分支补 `resume` / `redispatch` 的成功提示（含"已排队"走 `success===true` 且 `error` 非空时按 info 提示）。
- `isRunning` 维持 `state==='RUNNING'`（PAUSED 不算 running，避免误触发只在 running 显示的 UI）。

### 5.2 `RunStatusBar.tsx`（工作流级「恢复运行」入口）
当前 `if (!isRunning || !execution) return null;` 改为 `RUNNING || PAUSED` 都渲染：
- `PAUSED`：文案「{name} · 已恢复，{done}/{total} 完成，{waiting} 个节点待处理」，主按钮 **「恢复运行」**→ `resumeWorkflow(execution.workflowId)`；保留「Abort」。
- `RUNNING`：维持现状。

### 5.3 `NodeDrawer.tsx`（节点级「重新下发」按钮）
在 `readOnly` 分支的 footer（`:234-244`）增加：
```tsx
{(runtime?.status === 'RUNNING' || runtime?.status === 'WAITING_HUMAN') && !isPaused && (
  <button className={styles.secondaryBtn} onClick={onRedispatch}>
    <span className="codicon codicon-refresh" /> {t('workflow.redispatch', '重新下发任务')}
  </button>
)}
{/* DN12 可选：有活 pair 时再给一个强制重启 */}
{canForceRestart && (
  <button className={styles.secondaryBtn} onClick={onForceRestart}>
    <span className="codicon codicon-debug-restart" /> {t('workflow.redispatch.restart', '强制重启节点')}
  </button>
)}
```
- `onRedispatch`/`onForceRestart` 由 `WorkflowView` 透传，内部先 `window.confirm`（D4 幂等警告）：
  「该节点可能已产生部分改动，重新下发会让监督者在当前仓库状态上重跑，且不会回滚。确认继续？」
- `isPaused` 时隐藏按钮（DN10：先点「恢复运行」）。`NodeDrawer` props 增加 `runtime?.status` 已有；新增 `onRedispatch`、`onForceRestart?`、`isPaused`、`canForceRestart` 形参，由 `WorkflowView.tsx` 连接 context。

### 5.4 i18n（`messages/` + 前端 t-key，中英各一）
`workflow.resume`=恢复运行/Resume、`workflow.paused`=已恢复，待运行/Restored、`workflow.redispatch`=重新下发任务/Re-dispatch、`workflow.redispatch.restart`=强制重启节点/Force restart、`workflow.redispatch.confirm`=幂等警告文案、`workflow.redispatch.queued`=已排队/Queued。

---

## 6. 持久化兼容

- `execution.json` 结构向后兼容：新增 `PAUSED` 仅出现在重启后；老文件读出仍是 RUNNING → rehydrate 正常处理。
- DN11 去 `transient` 后，旧 `execution.json` 无 `changedFiles/summary` 字段 → Gson 读出为 null，与现状一致，不报错。
- 原子写不变（`atomicWriteString`）。

---

## 7. 时序（重启 → 恢复 → 重新下发）

```
IDE 启动
  └─ WorkflowStartupRecovery → rehydrateOnStartup()
        load execution.json(RUNNING) → 映射节点状态 → exec=PAUSED（不开窗/不pump）→ persist → broadcast
前端打开工作流页 → workflow_list → requestList 推 PAUSED 快照
  └─ RunStatusBar 显示「已恢复，N 个待处理」+「恢复运行」
用户点「恢复运行」 → workflow_resume
  └─ resumeWorkflow(): openCockpit → state=RUNNING → enqueueReady(安全前沿) → pump → broadcast
用户点中断节点 → 抽屉「重新下发任务」→ confirm → workflow_redispatch_node{auto}
  └─ redispatchNode(): 无活pair → 确保占slot(满则排队) → startNode 重启 → pairStarted → RUNNING
        完成 → ActionRouter → onNodeReport(DONE) → release+slotHolders.remove → 推进下游
```

---

## 8. 边界与并发

| 场景 | 处理 |
|---|---|
| 重启时多个执行 RUNNING（脏数据） | 取最近更新者加载为 PAUSED，其余 `abortPersistedOnly` |
| resume 前定义被删 | `resumeWorkflow` 校验 `currentDef`，缺失则 opResult 失败 |
| PAUSED 期间点重新下发 | DN10 按钮禁用 + opResult「请先恢复运行」 |
| 重新下发时并发已满 | D22 排队 readyQueue + pump，opResult「已排队」 |
| 重启节点：旧 pair 延迟回调 | 先移除 `pairToNode[oldPairId]`，回调被 `:301-303` 忽略，不误释放 slot |
| re-kick 后监督者仍空转 | `scheduleKickoffRetry` 6s 再补发一次（复用既有） |
| `onNodeTabClosed` 后再重新下发 | 该节点仍在 `slotHolders`（D11 持 slot）→ 走重启分支复用 slot |
| 两节点同时回调 | 仍由 scheduler 串行，无竞态 |
| 单工作流锁 | PAUSED 视同持锁：`startWorkflow` 拒绝新开 |

---

## 9. 测试计划（沿用 §22 的可注入 store/launcher/scheduler）

引擎单测（`session/pair/workflow` 测试包）：
1. `rehydrate`：构造 RUNNING execution → 断言 exec=PAUSED、RUNNING 节点→WAITING_HUMAN、READY→PENDING、DONE 保留、未开 cockpit（fake launcher 记录 openCockpit 次数=0）。
2. `resume`：PAUSED → 断言 openCockpit 调用、state=RUNNING、安全前沿被 pump、中断节点仍 WAITING_HUMAN。
3. `redispatch(auto, 有活pair)`：fake handle 带 pair → 断言走 re-kick（sendKickoff 调用）、slot 不增。
4. `redispatch(auto, 无pair)`：断言走 startNode、slot 通过 slotHolders 正确占用、旧 pairId 映射已清。
5. `redispatch 并发满`：slots=0 → 断言入 readyQueue、opResult 含"排队"、空出 slot 后 pump 起跑。
6. slot 守恒：重启→恢复→重新下发→DONE 全流程后 `availableSlotsForTest()==concurrency`、`slotHolders` 空。
7. 多 RUNNING 脏数据：断言只一个 PAUSED、其余 ABORTED 落盘。

前端：`WorkflowContext` 对 `resume/redispatch` 的事件发送与 PAUSED 状态渲染快照测试（若仓库有 webview 测试设施）。

---

## 10. 分阶段实施 + 文件改动清单

**P1｜状态与持久化地基**
- `WorkflowState.java`（+PAUSED）、`types.ts`（+PAUSED）
- `NodeRuntime.java`（去 transient，DN11）
- `SupervisorWorkflowManager`：`slotHolders` + `pump/onNodeReport` 记账（D23）、`isLocked` 抽取与守卫位点替换（§3.5）

**P2｜重启恢复**
- `SupervisorWorkflowManager.rehydrateOnStartup()`（替换 `recoverStaleExecutionsOnStartup`）+ `abortPersistedOnly`
- `WorkflowStartupRecovery.java` 改调 + 注释
- `requestList` 推 PAUSED

**P3｜一键恢复 + 重新下发引擎**
- `resumeWorkflow` / `redispatchNode`
- `WorkflowHandler.java`（+2 事件）

**P4｜前端三件套**
- `WorkflowContext.tsx`（resume/redispatch/isPaused）
- `RunStatusBar.tsx`（PAUSED 条 +「恢复运行」）
- `NodeDrawer.tsx` + `WorkflowView.tsx`（重新下发按钮 + confirm + 透传）
- i18n keys

**P5｜测试 + 打磨**
- 引擎单测（§9）、日志、DN12 强制重启按钮（可选）

改动文件总览：
```
src/main/java/.../workflow/WorkflowState.java
src/main/java/.../workflow/NodeRuntime.java
src/main/java/.../workflow/SupervisorWorkflowManager.java
src/main/java/.../handler/WorkflowHandler.java
src/main/java/.../startup/WorkflowStartupRecovery.java
webview/src/components/WorkflowOrchestration/types.ts
webview/src/components/WorkflowOrchestration/WorkflowContext.tsx
webview/src/components/WorkflowOrchestration/RunStatusBar.tsx
webview/src/components/WorkflowOrchestration/NodeDrawer.tsx
webview/src/components/WorkflowOrchestration/WorkflowView.tsx
src/main/resources/messages/*           # i18n
docs/workflow/coding-plan.md            # §5 transient 注释 / §17 D17→D18 更新
```

---

## 11. 风险与回退

| 风险 | 缓解 |
|---|---|
| 重新下发在半成品仓库重跑产生冲突（D4 无回滚） | UI 二次确认；只在人工判断卡死时使用；重启分支会读最新上游报告 |
| slot 记账回归（双占/泄漏） | slotHolders 单一真相 + §9.6 守恒测试 |
| PAUSED 引入后老 UI 路径误判 | `isRunning` 维持仅 RUNNING；新增 `isPaused` 单独驱动恢复条 |
| re-kick 对真死循环 daemon 无效 | DN12 强制重启兜底；自适应默认在无活 pair 时即走重启 |
| 远程模式 daemon 实际尚存却被重启杀掉 | 本期按本地语义（停旧起新）；C 档 reattach 列 v-next |

回退：保留 `recoverStaleExecutionsOnStartup` 旧逻辑为开关（feature flag）一版，问题时切回"重启即 ABORTED"。
