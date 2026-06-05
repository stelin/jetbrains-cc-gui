# 工作流「列表完成状态 + 节点延迟/定时 + 节点高度」编码方案

> 续 `resume-and-redispatch-plan.md`（决策编号接其后：D24+/DN13+）。沿用既有线程模型（单 `wf-scheduler` 线程，外部入口 `submit()` re-marshal）、持久化（`WorkflowStore`：`definition.json` / `execution.json`）、广播协议（`window.onWorkflow*`）。

## 0. 背景与目标

三个独立需求，共用现有工作流引擎与前端三件套：

1. **左侧列表显示完成状态**：列表现在只有 running 徽标（来自单个活跑执行）。要让 `COMPLETED` / `ABORTED` / `PAUSED` 也在列表显示，且**重开页面 / 重启 IDE 后仍在**。
2. **节点延迟 / 定时执行**：每个节点新增「执行时机」：立即（默认）/ 上游完成后延迟 N 分钟 / 指定具体时间（datetime）执行。
3. **节点卡片高度调高 1/3**。

## 1. 决策摘要

| 编号 | 决策 | 取值 |
|---|---|---|
| **D24** | 列表状态来源 | 新广播 `window.onWorkflowStatuses`，携带 `{wfId: WorkflowState}`（活跑用内存 `exec.state`，其余读 `execution.json`）。覆盖全部工作流、可持久 |
| **DN15** | statuses 推送时机 | 仅在**工作流级**状态变化点推送（start/resume/rehydrate/abort/complete）+ `requestList` + `broadcastDefinitions`；节点级中间跃迁不重推（避免每次跃迁重读 N 个文件） |
| **D25** | 节点调度模型 | `delayMode: 'none'\|'relative'\|'absolute'` + `delayMinutes?` + `scheduledAt?`（epoch ms）；新增节点状态 `SCHEDULED` |
| **D26** | 调度时刻存绝对 epoch | 运行态 `NodeRuntime.scheduledStartAt`（epoch）。参照点只在 PENDING→SCHEDULED 那一刻算一次；存绝对值 → 重启/停机不丢、可正确重排 |
| **DN13** | SCHEDULED 不占 slot | SCHEDULED 不进 `slotHolders`；到点变 READY 被 `pump` 选中才 acquire（D23 语义不变） |
| **DN14** | 定时器仅 RUNNING 期间存活 | `PAUSED` 不 arm；`resumeWorkflow` 按 `scheduledStartAt` 统一重排（已过点→立即 READY） |
| **D27** | 重新下发跳过延迟 | 对 SCHEDULED 节点 redispatch = 取消定时器 + 立即跑 |
| **D28** | 延迟上限 / 时区 | 相对 `delayMinutes ∈ [0, 300]`（5h）；绝对 datetime 不套 5h 上限，必须选时间，允许过去（过点即跑）。输入/显示一律用本地（IDE）时区 |
| **D29** | 节点高度 +1/3 | `layout.ts` `CARD_H` 60 → 80（单常量级联） |

> 复用：D2 节点=tab=pair、D4 无回滚、D12 单工作流锁、D20 PAUSED、D23 slotHolders。

---

## 2. 需求一：列表完成状态（statuses map）

### 2.1 引擎（`SupervisorWorkflowManager`）

```java
/** {wfId: WorkflowState} —— 活跑用内存 exec.state（最新），其余读磁盘 execution.json。 */
private String statusesJson() {
    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
    WorkflowExecution live = exec;
    for (WorkflowDefinition def : store.loadAll()) {
        if (def == null || def.id == null) continue;
        WorkflowState st;
        if (live != null && def.id.equals(live.workflowId)) {
            st = live.state;
        } else {
            WorkflowExecution e = store.loadExecution(def.id);
            st = e != null ? e.state : null;
        }
        if (st != null) o.addProperty(def.id, st.name());
    }
    return gson.toJson(o);
}

private void broadcastStatuses() { broadcast("window.onWorkflowStatuses", statusesJson()); }
```

推送点（DN15）——在每个 `exec.state = …` 赋值之后、以及定义增删/列表请求处补一行 `broadcastStatuses()`：
- `startWorkflow`（→RUNNING）、`resumeWorkflow`（→RUNNING）、`rehydrateOnStartup`（→PAUSED）、`abortWorkflow`（→ABORTED）、`onNodeReport` 的 `COMPLETED` 分支；
- `saveDefinition` / `deleteDefinition`（已调 `broadcastDefinitions`，紧随其后加）；
- `requestList`（向单个 sink）：`pushTo(sink, "window.onWorkflowStatuses", statusesJson());`

节点级中间跃迁（workflow 仍 RUNNING）**不**调用 `broadcastStatuses`。

### 2.2 前端

`WorkflowContext.tsx`：
```ts
const [executionStatuses, setExecutionStatuses] = useState<Record<string, WorkflowState>>({});
// 在 Java→JS 回调注册块新增：
window.onWorkflowStatuses = (json) => {
  prevStatuses?.(json);
  try { const m = JSON.parse(json) as Record<string, WorkflowState>; if (m && typeof m === 'object') setExecutionStatuses(m); } catch { /* ignore */ }
};
```
并入 context value（含依赖数组）。`isRunning/isPaused/runningOf` 维持不变（仍由单个 `execution` 驱动 DAG）。

`WorkflowView.tsx` → `WorkflowList`：把 `runningId` 换成 `statuses={executionStatuses}`。

`WorkflowList.tsx`：按 `statuses[def.id]` 渲染徽标：
```tsx
const st = statuses[def.id];
{st === 'RUNNING'   && <span className={styles.runningBadge}><span className={styles.runningDot}/> {t('workflow.state.running','Running')}</span>}
{st === 'PAUSED'    && <span className={styles.pausedBadge}><span className="codicon codicon-debug-pause"/> {t('workflow.state.paused','Paused')}</span>}
{st === 'COMPLETED' && <span className={styles.completedBadge}><span className="codicon codicon-check"/> {t('workflow.state.completed','Completed')}</span>}
{st === 'ABORTED'   && <span className={styles.abortedBadge}><span className="codicon codicon-circle-slash"/> {t('workflow.state.aborted','Aborted')}</span>}
```
`style.module.less` 新增 `.pausedBadge/.completedBadge/.abortedBadge`（仿 `.runningBadge` 配色：completed 绿、aborted 灰、paused 橙）。i18n `workflow.state.*` 已存在（paused 已在上个方案补过）。

---

## 3. 需求二：节点延迟 / 定时执行

### 3.1 模型

`WorkflowNode.java`（定义层，随 `definition.json` 持久化）：
```java
/** 执行时机：none=立即(默认) / relative=上游完成后延迟 / absolute=指定时间。 */
public String delayMode;
/** relative：延迟分钟数，[0, 300]。 */
public Integer delayMinutes;
/** absolute：指定启动时刻（epoch ms，本地时区选取后转换）。 */
public Long scheduledAt;
```
`types.ts` 同步：
```ts
delayMode?: 'none' | 'relative' | 'absolute';
delayMinutes?: number;
scheduledAt?: number;
```

`NodeStatus`（Java 枚举 + TS union）新增 `SCHEDULED`（依赖已满足，等定时点）。
`NodeRuntime`（运行态，随 `execution.json` 持久化）：
```java
public Long scheduledStartAt;   // 该节点实际应启动的绝对时刻（epoch）；SCHEDULED 时非空
```
`types.ts` `NodeRuntime` 加 `scheduledStartAt?: number | null;`。

常量：`static final int MAX_DELAY_MINUTES = 300;`

### 3.2 调度接入：`enqueueReady` 算 dueAt

```java
private void enqueueReady() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
        String name = e.getKey();
        NodeRuntime r = e.getValue();
        if (r.status != NodeStatus.PENDING || !allDepsDone(name)) continue;   // 只看 PENDING → 参照点只算一次
        long dueAt = computeDueAt(name, now);
        if (dueAt <= now) {
            r.status = NodeStatus.READY;
            r.scheduledStartAt = null;
            readyQueue.add(name);
        } else {
            r.status = NodeStatus.SCHEDULED;        // DN13：不进 slotHolders
            r.scheduledStartAt = dueAt;
            armTimer(name, dueAt);
        }
    }
}

/** absolute=指定时刻；relative=now+min*60s（clamp 5h）；none/0=now。 */
private long computeDueAt(String name, long now) {
    WorkflowNode n = nameToNode.get(name);
    if (n == null) return now;
    if ("absolute".equals(n.delayMode) && n.scheduledAt != null) return n.scheduledAt;
    if ("relative".equals(n.delayMode) && n.delayMinutes != null && n.delayMinutes > 0) {
        int m = Math.min(n.delayMinutes, MAX_DELAY_MINUTES);
        return now + m * 60_000L;
    }
    return now;
}
```
绝对时间是「不早于」闸门：依赖未完成不会触发（节点还在 PENDING）；依赖完成那刻若已过点（`dueAt<=now`）→ 立即 READY。

### 3.3 定时器 arm / fire / cancel

新增（仿现有 `kickoffRetry` daemon 单线程）：
```java
private final java.util.concurrent.ScheduledExecutorService nodeTimers =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wf-node-timer"); t.setDaemon(true); return t; });
private final Map<String, java.util.concurrent.ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

private void armTimer(String name, long dueAt) {
    cancelTimer(name);
    long delay = Math.max(0, dueAt - System.currentTimeMillis());
    try {
        scheduledFutures.put(name, nodeTimers.schedule(
            () -> submit(() -> fireScheduled(name)), delay, java.util.concurrent.TimeUnit.MILLISECONDS));
    } catch (Exception ignored) { /* 已 shutdown */ }
}

private void cancelTimer(String name) {
    java.util.concurrent.ScheduledFuture<?> f = scheduledFutures.remove(name);
    if (f != null) f.cancel(false);
}

private void cancelAllTimers() {
    for (java.util.concurrent.ScheduledFuture<?> f : scheduledFutures.values()) if (f != null) f.cancel(false);
    scheduledFutures.clear();
}

/** 到点：SCHEDULED→READY→pump。已 abort/redispatch 走则 no-op。 */
private void fireScheduled(String name) {
    if (exec == null || exec.state != WorkflowState.RUNNING) return;
    scheduledFutures.remove(name);
    NodeRuntime r = exec.nodes.get(name);
    if (r == null || r.status != NodeStatus.SCHEDULED) return;   // 幂等防御
    r.status = NodeStatus.READY;
    r.scheduledStartAt = null;
    readyQueue.add(name);
    persist();
    broadcastExec();
    pump();
}
```

### 3.4 重启 / 恢复重排（DN14）

- `rehydrateOnStartup` 节点映射表新增一行：**`SCHEDULED` → 保持 `SCHEDULED`**（连同 `scheduledStartAt` 一起保留；PAUSED 期间不 arm）。其余映射不变（RUNNING→WAITING_HUMAN、READY→PENDING）。
- `resumeWorkflow` 在 `enqueueReady()` 之后增加一遍重排：
```java
private void rearmScheduledTimers() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, NodeRuntime> e : exec.nodes.entrySet()) {
        NodeRuntime r = e.getValue();
        if (r.status != NodeStatus.SCHEDULED) continue;
        long dueAt = r.scheduledStartAt != null ? r.scheduledStartAt : now;
        if (dueAt <= now) {                 // 停机期间已过点 → 直接就绪
            r.status = NodeStatus.READY; r.scheduledStartAt = null; readyQueue.add(e.getKey());
        } else {
            armTimer(e.getKey(), dueAt);
        }
    }
}
```
  resume 顺序：置 RUNNING → 新 `slots` → `enqueueReady()`（新 PENDING-deps-done 节点按延迟变 SCHEDULED+arm）→ `rearmScheduledTimers()`（还原此前的 SCHEDULED）→ persist/broadcast → `pump()`。

### 3.5 与 redispatch / abort / dispose 的耦合

- `redispatchNode`：方法开头加 `cancelTimer(name)` + `rt.scheduledStartAt = null`（D27 跳过延迟）。SCHEDULED 节点无 live pair → 走 re-launch（acquire slot + `startNode`），即"立即跑"。`rt.status==DONE` 拒绝判断对 SCHEDULED 不触发，照常进行。
- `abortWorkflow`：加 `cancelAllTimers()`；标 ABORTED 的状态集合补上 `SCHEDULED`。
- `dispose()`：`nodeTimers.shutdownNow()`（与 `kickoffRetry` 并列）。
- `allNodesDone()`：SCHEDULED≠DONE → 等待期工作流不会误判 COMPLETED，无需改。
- SCHEDULED 节点无 pair/cockpit 窗口（未 startNode）→ 等待期不弹窗；`launcher.setNodeStatus(name, SCHEDULED)` 因无 frame 是 no-op，可不调用。

### 3.6 校验（`DagValidator.validate` + 前端）

后端 `DagValidator`：遍历节点——
- `relative` 且（`delayMinutes==null || <0 || >300`）→ `"节点「X」延迟需在 0–300 分钟之间"`；
- `absolute` 且 `scheduledAt==null` → `"节点「X」未选择定时时间"`。

前端 `validateWorkflowDef`（`WorkflowContext.tsx`）同款校验，保存即拦截。
`canonDef`（脏检查）投影补 `delayMode/delayMinutes/scheduledAt`，否则改这些字段不标 dirty、保存按钮不亮。

### 3.7 前端 UI

`statusMeta`（`NodeCard.tsx`）新增：
```ts
case 'SCHEDULED':
  return { cls: styles.statusScheduled, icon: 'codicon-watch', i18nKey: 'workflow.status.scheduled', fallback: 'Scheduled' };
```
（spin 仍只给 RUNNING；`style.module.less` 加 `.statusScheduled` 配色，建议青/蓝灰）。

`NodeDrawer.tsx` 在任务输入下方加「执行时机」：
- 单选：立即 / 延迟 / 指定时间（仿 `planMode` 的 radio 模式）；切换时清另一字段：
  - 立即 → `commit({ delayMode:'none', delayMinutes:undefined, scheduledAt:undefined })`
  - 延迟 → `delayMode:'relative'`，数字输入 `min=0 max=300`，`commit({ delayMinutes:Number(v) })`
  - 指定时间 → `delayMode:'absolute'`，`<input type="datetime-local">`，`commit({ scheduledAt: new Date(v).getTime() })`
- 只读（运行中）态：显示「延迟 5 分钟」/「定时 06-05 02:00」；若 `runtime.status==='SCHEDULED'` 额外显示 `new Date(runtime.scheduledStartAt).toLocaleString()` 与倒计时。
- 时区：`datetime-local` 与 `toLocaleString` 都走浏览器本地时区（= IDE/系统时区），与 D28 一致。

辅助：epoch ↔ datetime-local 字符串
```ts
const toLocalInput = (ms?: number) => { if (!ms) return ''; const d = new Date(ms);
  const p = (n:number)=>String(n).padStart(2,'0');
  return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`; };
```

---

## 4. 需求三：节点高度 +1/3

`webview/src/components/WorkflowOrchestration/layout.ts`：`export const CARD_H = 60;` → `80`。
单常量级联：卡片渲染（`NodeCard.tsx:63` 内联 `height: CARD_H`）、自动布局 y/总高（`layout.ts`）、画布尺寸（`DagCanvas.tsx`）、连线锚点 `y+CARD_H/2`（`DagEdges.tsx` / `DagCanvas.tsx`）全部跟随。`ROW_GAP` 不变（卡片变高、间距不变）。CSS 无硬编码高度，无需改 less。

---

## 5. 协议 / 事件

| 方向 | 通道 | 变更 |
|---|---|---|
| Java→JS | `window.onWorkflowStatuses` | **新增**，`{wfId: WorkflowState}` |
| Java→JS | `window.onWorkflowExecutionUpdate` | 不变（节点新增 `SCHEDULED` 状态 + `scheduledStartAt` 字段随快照下发） |
| JS→Java | — | 无新增入站事件（延迟/定时是定义字段，走既有 `workflow_save`；执行时机变更随定义保存） |

`WorkflowState` 字面量两侧已含 PAUSED；`NodeStatus` 两侧新增 `SCHEDULED`，Gson 按 `name()` 序列化，必须字面量一致。

---

## 6. 持久化

- `delayMode/delayMinutes/scheduledAt` ∈ definition.json（`workflow_save` 已覆盖）。
- `scheduledStartAt` ∈ execution.json（`persist()` 已在每次跃迁落盘）。
- 向后兼容：旧 JSON 无这些字段 → Gson 读出 null → `delayMode==null` 视同 none、`scheduledStartAt==null` 视同立即，行为与现状一致。

---

## 7. 边界与并发

| 场景 | 处理 |
|---|---|
| 多依赖、完成时刻不一 | 参照点 = 最后一个依赖 DONE（`enqueueReady` 提升那刻），只算一次 |
| 绝对时间已过（配置即过去） | `dueAt<=now` → 立即 READY |
| 停机跨过 scheduledStartAt | resume 时 `rearmScheduledTimers` 判定已过点 → 立即 READY |
| PAUSED 期间 | 不 arm 任何定时器；resume 统一重排 |
| redispatch SCHEDULED | `cancelTimer` + 立即 re-launch（跳过延迟） |
| abort 时有 SCHEDULED | `cancelAllTimers` + 标 ABORTED |
| 根节点（无依赖）带延迟 | 相对=从 start/resume 起算；绝对=到点前 SCHEDULED |
| delayMinutes 超 300 | 前后端校验拒绝；`computeDueAt` 再 clamp 兜底 |
| SCHEDULED 与 slot | 不占 slot；到点 READY 经 pump 才 acquire（D23） |
| COMPLETED 判定 | SCHEDULED≠DONE，等待期不误判 |

---

## 8. 测试计划

引擎单测（注入 fake launcher / 同步 scheduler；定时器需可控——见下）：
1. `computeDueAt`：none→now、relative→now+min（含 >300 clamp）、absolute→scheduledAt。
2. `enqueueReady`：带 relative 延迟的节点 → SCHEDULED + `scheduledStartAt≈now+delay` + 不占 slot；延迟 0 → READY 立即。
3. `fireScheduled`：手动调用 → SCHEDULED→READY→startNode；幂等（重复/已 abort 不重跑）。
4. rehydrate：SCHEDULED 节点保持 SCHEDULED + `scheduledStartAt` 保留；PAUSED 下无定时器。
5. resume `rearmScheduledTimers`：`scheduledStartAt` 过去 → 立即 READY；未来 → arm。
6. redispatch SCHEDULED：取消定时器 + 占 1 slot + startNode。
7. abort：SCHEDULED→ABORTED + 定时器清空。
8. slot 守恒：含 SCHEDULED 的全流程结束后 `slotHolders` 空、permit 全归还。
9. statuses：start/complete/abort 后 `statusesJson()` 对应 wfId 状态正确；活跑用内存态、其余读盘。

> 定时器可控性：把 `nodeTimers` 抽成可注入接口（或暴露 `fireScheduled` 包级方法直接驱动），单测不依赖真实时延。

前端：`statusMeta('SCHEDULED')`、NodeDrawer 执行时机的 commit/校验、WorkflowList 各状态徽标渲染快照、`canonDef` 含新字段（脏检查）。

---

## 9. 分阶段 + 文件改动清单

**P1｜列表完成状态**
- `SupervisorWorkflowManager`：`statusesJson` / `broadcastStatuses` + 各状态点/`requestList`/`broadcastDefinitions` 推送
- `WorkflowContext.tsx`（`executionStatuses` + 回调）、`WorkflowView.tsx`（传 statuses）、`WorkflowList.tsx`（徽标）、`style.module.less`（3 个徽标样式）

**P2｜节点调度模型 + 状态**
- `WorkflowNode.java` / `NodeRuntime.java` / `NodeStatus.java`（+SCHEDULED）/ `types.ts`
- `MAX_DELAY_MINUTES`、`computeDueAt`、`enqueueReady` 改造
- `DagValidator.java`（校验）

**P3｜定时器与重排**
- `SupervisorWorkflowManager`：`nodeTimers`/`scheduledFutures`/`armTimer`/`cancelTimer`/`cancelAllTimers`/`fireScheduled`/`rearmScheduledTimers`
- `rehydrateOnStartup`（+SCHEDULED 保留）、`resumeWorkflow`（+rearm）、`redispatchNode`（+cancelTimer/清 scheduledStartAt）、`abortWorkflow`（+cancelAll/标 ABORTED）、`dispose`（+shutdown）

**P4｜前端 UI**
- `NodeCard.tsx`（statusMeta SCHEDULED）、`NodeDrawer.tsx`（执行时机 + 只读显示）、`WorkflowContext.tsx`（`canonDef`/`validateWorkflowDef` 补字段）
- i18n：`workflow.status.scheduled`、`workflow.node.timing.*`（立即/延迟/指定时间/分钟/到点提示）

**P5｜节点高度 + 测试**
- `layout.ts`（CARD_H 80）
- 引擎单测（§8）、前端测试

文件总览：
```
src/main/java/.../workflow/WorkflowNode.java
src/main/java/.../workflow/NodeRuntime.java
src/main/java/.../workflow/NodeStatus.java
src/main/java/.../workflow/SupervisorWorkflowManager.java
src/main/java/.../workflow/DagValidator.java
webview/src/components/WorkflowOrchestration/types.ts
webview/src/components/WorkflowOrchestration/WorkflowContext.tsx
webview/src/components/WorkflowOrchestration/WorkflowView.tsx
webview/src/components/WorkflowOrchestration/WorkflowList.tsx
webview/src/components/WorkflowOrchestration/NodeCard.tsx
webview/src/components/WorkflowOrchestration/NodeDrawer.tsx
webview/src/components/WorkflowOrchestration/layout.ts
webview/src/components/WorkflowOrchestration/style.module.less
webview/src/i18n/locales/zh.json, en.json
```

---

## 10. 风险与回退

| 风险 | 缓解 |
|---|---|
| 定时器漏触发/重复触发 | `fireScheduled` 幂等校验 status==SCHEDULED；`scheduledFutures` 单一跟踪 + cancel |
| 系统时钟/时区变动 | 一律 epoch（UTC）计算；UI 仅在展示层做本地格式化 |
| 长延迟跨重启丢失 | 存绝对 `scheduledStartAt`，resume 重排兜底；5h 上限只约束相对模式 |
| statuses 每次跃迁读盘抖动 | DN15 仅工作流级变化点推送；活跑用内存态免读盘 |
| SCHEDULED 误占并发 | DN13 不进 slotHolders，§8.8 守恒测试覆盖 |
| 节点变高挤压画布 | 单常量级联，ROW_GAP 不变；如显拥挤可后续微调 ROW_GAP |

回退：statuses 广播、SCHEDULED 调度均为增量；关掉前端「执行时机」入口即退化为"立即执行"，引擎对 `delayMode==null` 天然兼容。
