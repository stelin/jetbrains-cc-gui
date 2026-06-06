# 工作流「节点假死自愈 watchdog + 静默时长显示」编码方案

> 续前几份工作流方案（决策编号接 D34/DN16 之后）。两个特性同源：都建立在「节点距上次活跃多久」这一指标上 —— watchdog 用它判定假死并自动重发，卡片用它显示静默时长。一份计算（`effectiveLastActiveAt`）两处用。

## 0. 背景与目标

1. **假死自愈**：监控每个 RUNNING 节点；当监督者与主 AI 双双静默超过阈值（默认 10min），判定为假死/死锁，自动重发该节点；重发间隔按退避增长（10→20→40→60min 封顶，永不停手），节点复活则归零。
2. **静默时长显示**：在画布节点卡片上显示「静默时长 / 阈值」（如 `3:20 / 10:00`）；正常运行为 `0:00`，重发（手动或自动）归零。

## 1. 决策摘要

| 编号 | 决策 | 取值 |
|---|---|---|
| **D35** | 假死判定信号 | `effectiveLastActiveAt = max(mainAI.getLastActivityAt(), sup.getLastTickStartMs/EndMs(), lastDispatchAt)`；`idle = now - effectiveLastActiveAt`（本地 SDK / 远程 server 输出都经 `ClaudeMessageHandler` 汇入 `MainAIMonitor`，统一 mode-agnostic） |
| **D36** | 退避策略 | `window(n) = min(threshold × 2^n, 60min)` → 10→20→40→60→60…；**无硬上限**；每次自动重发推可见提示 + 日志 |
| **D37** | 复活归零 | 重发后持续健康 ≥ threshold → `attempts=0`；**手动重发也归零**；自动重发 `attempts++` |
| **D38** | 豁免 / 计入 | 豁免 `RateLimitWatcher.isWaitingForReset()`、`SupervisorMonitor.isTickInProgress()`；**awaiting-user 计入假死**（后台节点无人应答）；`SCHEDULED/WAITING_HUMAN/DONE` 跳过 |
| **D39** | 重发动作 | 复用 `redispatchNode` 的重启分支（杀旧 pair + 重启）；抽出 `redispatchNodeInternal`（on-thread 核心） |
| **D40** | 静默显示 | **节点卡片 + 监督者面板**都显示，格式「当前/阈值」`m:ss / m:ss`（超 1h 用 `h:mm:ss`）；`idle<15s` 视为活跃显示 `0:00`；颜色随 `idle/threshold` 由绿转橙转红 |
| **D41** | 监督者面板取数 | 节点窗口的 webview 本就注册了 `WorkflowHandler` sink（`ChatWindowDelegate:311`），故 `onWorkflowNodeActivity`/`onWorkflowExecutionUpdate`/`capabilities` 都到得了。监督者面板用 `pairStatus.pairId` 反查 `execution.nodes[*].pairId` 定位自身节点，用**同一份** `effectiveLastActiveAt`（非面板自带 `lastActivityAgoMs`）→ 显示与判定永远一致 |
| **DN17** | 调度 | `SupervisorWorkflowManager` 新增 5s 周期 `ScheduledExecutorService`，每 tick `submit(watchdogTick)` 回 wf-scheduler；同一 tick 内：推活动(显示) + 查假死(自愈) |
| **DN18** | 活动推送 | 新广播 `window.onWorkflowNodeActivity = {nodeName: effectiveLastActiveAt}`（仅 RUNNING 节点）；前端本地每 1s tick 平滑显示 |
| **DN19** | 阈值配置 | `settings.workflow.freezeThresholdMinutes` 默认 10；`≤0` 关闭 watchdog（显示退化为裸静默时长） |

## 2. 共享基础（引擎）

`SupervisorWorkflowManager` 新增两个 scheduler-thread 内存态（不持久化——重启后 pair 全新、本就该重置）：
```java
private final Map<String, Integer> redispatchAttempts = new ConcurrentHashMap<>();
private final Map<String, Long>    lastDispatchAt     = new ConcurrentHashMap<>();
```
`lastDispatchAt` 在**每次（再）下发**时刷新：`pump()` 初次启动、`redispatchNodeInternal` 重发。它既是退避基准，也让"刚启动/刚重发 → idle=0"（避开新 pair `getLastActivityAt()=0` 的虚高）。

```java
/** 该节点"最后活跃时刻"（epoch）：主 AI 流活动 / 监督者 tick / 本次下发时刻 取最大。 */
long effectiveLastActiveAt(String name) {
    long t = lastDispatchAt.getOrDefault(name, 0L);
    NodeHandle h = handles.get(name);
    if (h != null && h.pair != null && !h.pair.isDisposed()) {
        try {
            MainAIMonitor m = h.pair.getMainAIMonitor();
            if (m != null) t = Math.max(t, m.getLastActivityAt());
            SupervisorMonitor s = h.pair.getSupervisorMonitor();
            if (s != null) t = Math.max(t, Math.max(s.getLastTickStartMs(), s.getLastTickEndMs()));
        } catch (Exception ignored) { /* best-effort */ }
    }
    return t;
}
```
清理：`startWorkflow`/`resumeWorkflow`/`abortWorkflow`/`rehydrateOnStartup` 的复位处 `redispatchAttempts.clear(); lastDispatchAt.clear();`。`onNodeReport(DONE)` 时 `remove(name)`。

## 3. 调度器（DN17，5s tick）

```java
private final ScheduledExecutorService nodeWatchdog =
    Executors.newSingleThreadScheduledExecutor(daemon("wf-node-watchdog"));

// 构造后 start：
nodeWatchdog.scheduleWithFixedDelay(() -> submit(this::watchdogTick),
    WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);   // WATCHDOG_TICK_MS = 5_000
```
`submit(watchdogTick)` → 在 wf-scheduler 单线程上读 `exec.nodes`（与所有 mutation 同线程，**无并发问题**；monitor 读是 AtomicLong，极快）。`dispose()` 加 `nodeWatchdog.shutdownNow()`。

```java
private void watchdogTick() {
    WorkflowExecution e = exec;
    if (e == null || e.state != WorkflowState.RUNNING) return;
    long now = System.currentTimeMillis();
    long thresholdMs = freezeThresholdMs();           // settings；≤0 关闭自愈
    JsonObject activity = new JsonObject();
    List<String> frozen = new ArrayList<>();

    for (Map.Entry<String, NodeRuntime> en : e.nodes.entrySet()) {
        String name = en.getKey();
        if (en.getValue().status != NodeStatus.RUNNING) continue;
        NodeHandle h = handles.get(name);
        if (h == null || h.pair == null || h.pair.isDisposed()) continue;

        long lastActive = effectiveLastActiveAt(name);
        activity.addProperty(name, lastActive);        // 显示用（始终推）

        if (thresholdMs <= 0) continue;                // watchdog 关闭，仅显示
        if (isRateLimitWaiting(h) || isTickInProgress(h)) continue;   // 豁免

        int attempts = redispatchAttempts.getOrDefault(name, 0);
        // 复活归零：重发后已健康运行 ≥ threshold（距上次下发够久且当前不静默）
        if (attempts > 0 && now - lastDispatchAt.getOrDefault(name, 0L) > thresholdMs
                && now - lastActive < thresholdMs) {
            redispatchAttempts.put(name, 0);
            attempts = 0;
        }
        long window = Math.min(thresholdMs * (1L << Math.min(attempts, 12)), 60 * 60_000L);
        if (now - lastActive > window) frozen.add(name);
    }

    if (!activity.entrySet().isEmpty()) broadcast("window.onWorkflowNodeActivity", gson.toJson(activity));

    for (String name : frozen) autoRedispatch(name, now);   // 已在 wf-scheduler 线程
}

private void autoRedispatch(String name, long now) {
    NodeRuntime rt = exec.nodes.get(name);
    if (rt == null || rt.status != NodeStatus.RUNNING) return;
    long window = Math.min(freezeThresholdMs() * (1L << Math.min(redispatchAttempts.getOrDefault(name, 0), 12)), 60 * 60_000L);
    if (now - effectiveLastActiveAt(name) <= window) return;     // 检测→执行间已复活
    int attempts = redispatchAttempts.merge(name, 1, Integer::sum);
    long idleMin = (now - effectiveLastActiveAt(name)) / 60_000L;
    pushEscalationNotice(name, "节点疑似假死（静默 " + idleMin + " 分钟），已自动重新下发（第 " + attempts + " 次）");
    LOG.warn("[Workflow] node " + name + " frozen → auto re-dispatch #" + attempts);
    redispatchNodeInternal(name, "restart");                    // 内部设 lastDispatchAt=now
}
```

## 4. `redispatchNode` 重构（DN18）

把现有 `redispatchNode` 的 `submit(...)` 内核抽成 `private void redispatchNodeInternal(String name, String mode)`（不含 submit），开头 `lastDispatchAt.put(name, now)`：
- `public void redispatchNode(name, mode)`（手动）→ `submit(() -> { redispatchAttempts.put(name, 0); redispatchNodeInternal(name, mode); })`（手动重发归零退避）。
- `autoRedispatch` → 直接调 `redispatchNodeInternal`（已在线程上；attempts 已自增）。

`pump()` 启动节点处加 `lastDispatchAt.put(name, now)`。

## 5. 与现有看门狗的分工（不冲突）
- `DeadlockGuard`（30s/60s/180s）先温和 nudge / 升级；`attachWatchdogBackstop` 把 plan→WAITING 的节点 funnel 成 `WAITING_HUMAN` → 退出 RUNNING → 本 watchdog 自动跳过。
- 只有"监督者 tick 都死、升级机制都没触发"的彻底假死才轮到本 watchdog 硬重启。
- 远程 `DAEMON_DOWN`（硬崩）仍走 `onNodeDaemonDown`→人工；本 watchdog 管"进程活着但假死"。

## 6. 静默时长显示（前端）

### 6.1 协议
- `window.onWorkflowNodeActivity`（新）：`{ [nodeName]: effectiveLastActiveAt(epoch) }`，watchdog tick 每 5s 推。
- 阈值随 `window.onWorkflowCapabilities` 下发：`WorkflowCapabilities` 加 `freezeThresholdMinutes`（`capabilitiesJson()` 补字段）。

### 6.2 前端 — 共享
- `global.d.ts` 加 `onWorkflowNodeActivity?`。
- `WorkflowContext`：state `nodeActivity: Record<string, number>` + 回调；`capabilities` 加 `freezeThresholdMinutes`；并入 value。
- 抽一个共享 `formatIdle(idleMs)`（`m:ss`，超 1h `h:mm:ss`）+ `idleLevel(idleMs, thresholdMs)`（`ok|warn|danger`，按比例），供卡片和监督者面板共用（可放 `portability.ts` 旁的小工具或 `layout.ts`）。

### 6.3 前端 — 节点卡片（画布总览）
- `WorkflowView` → `DagCanvas`：传 `nodeActivity` + `freezeThresholdMs`。
- `DagCanvas`：`showStatus` 时开**单个 1s ticker**（`nowTs`），逐节点算
  `idleMs = (status==='RUNNING' && nodeActivity[name]) ? max(0, nowTs - nodeActivity[name]) : undefined`，传给 `NodeCard`。
- `NodeCard`：`status==='RUNNING' && idleMs!=null` 时渲染一行
  `formatIdle(idleMs)` + (`thresholdMs>0` ? ` / formatIdle(thresholdMs)` : '')；
  `idleMs < 15_000` → 绿色 `0:00`；否则按 `idleLevel` 配色。

### 6.4 前端 — 监督者面板（节点窗口，D41）
- 入口组件（`SessionCountStrip.tsx`，紧挨「Tick N · X 前活跃」；或 `PairStatusBar.tsx`）同时取：
  - `usePairContext()` → `pairStatus.pairId`；
  - `useWorkflowContext()` → `execution`、`nodeActivity`、`capabilities.freezeThresholdMinutes`。
- 反查自身节点：`const nodeName = execution && Object.keys(execution.nodes).find(n => execution.nodes[n].pairId === pairStatus.pairId)`。
- 若 `nodeName` 存在且 `execution.nodes[nodeName].status === 'RUNNING'`：本地 1s ticker 算
  `idleMs = max(0, now - nodeActivity[nodeName])`，渲染「静默 `formatIdle(idleMs)` / `formatIdle(thresholdMs)`」，配色同卡片。非工作流 pair（查不到节点）→ 不渲染（保持普通 pair 行为不变）。
- 两个 provider 都在 `App.tsx` 树内（`<WorkflowProvider><PairProvider>`），监督者面板组件可同时用；WorkflowProvider 始终挂载，其 `execution`/`nodeActivity` 在节点窗口里也被广播填充。

### 6.5 i18n
`workflow.node.silentTip`（hover 提示，如「距上次活跃；达阈值将自动重新下发」）；`workflow.node.silent`（监督者面板前缀「静默」）。数字本身无需翻译。

## 7. 配置（settings）
- `CodemossSettingsService.getWorkflowFreezeThresholdMinutes()` 默认 10；持久化键 `workflow.freezeThresholdMinutes`。
- settings UI（工作流/监督者区）加一个数字输入（默认 10，0=关闭）。`capabilitiesJson()` 读它下发前端。

## 8. 边界与并发
| 场景 | 处理 |
|---|---|
| 读 `exec.nodes` 并发 | watchdogTick 经 `submit` 在 wf-scheduler 上跑，与 mutation 同线程，无竞态 |
| 检测→执行间节点复活 | `autoRedispatch` 二次确认仍超窗才发 |
| 退避溢出 | `1L << min(attempts,12)` + `min(...,60min)` 封顶 |
| 长静默 Task 子代理 | 10min 阈值足够保守（DeadlockGuard 自身用 180s）；仍超则确属异常 |
| 刚启动/刚重发 idle 虚高 | `lastDispatchAt` 作为 `effectiveLastActiveAt` 下限 |
| watchdog 关闭(≤0) | 仍推 activity 供显示；显示退化为裸静默时长（无 /阈值） |
| 节点 DONE/abort | 清 `redispatchAttempts`/`lastDispatchAt`；显示停止 |

## 9. 测试（引擎，注入可控时钟/手动 tick）
> 为可测：把 watchdog 的判定核心抽成纯方法 `boolean shouldRedispatch(name, now, thresholdMs, attempts)`（基于注入的 `effectiveLastActiveAt`），或暴露 `watchdogTickForTest(now)`。
1. `effectiveLastActiveAt` 取三者最大；新 pair 无活动时回落到 `lastDispatchAt`。
2. idle < window → 不发；> window → 发，`attempts++`，`lastDispatchAt` 刷新。
3. 退避序列：attempts 0/1/2/3 → window 10/20/40/60min（封顶）。
4. 复活归零：重发后健康 > threshold → `attempts` 回 0。
5. 豁免：rate-limit 等待 / tick-in-progress → 不发。
6. 手动 `redispatchNode` → `attempts` 归零。
7. 活动推送：RUNNING 节点出现在 `onWorkflowNodeActivity`，DONE 节点不出现。
前端：`fmt` 格式化；NodeCard RUNNING 渲染 `idle/threshold` 且 `idle<15s` 显示 0:00；context 收 `onWorkflowNodeActivity` 更新。

## 10. 分阶段 + 文件清单
**P1｜共享基础**：`effectiveLastActiveAt`、`lastDispatchAt`/`redispatchAttempts` map、`pump`/复位处接线、`redispatchNodeInternal` 重构。
**P2｜watchdog**：`nodeWatchdog` 调度 + `watchdogTick` + `autoRedispatch` + `dispose`；豁免/退避/复活。
**P3｜显示协议**：`onWorkflowNodeActivity` 广播 + `capabilities.freezeThresholdMinutes`。
**P4｜前端显示**：共享 `formatIdle`/`idleLevel`；`global.d.ts`/`types.ts`/`WorkflowContext`/`WorkflowView`/`DagCanvas`/`NodeCard`/less/i18n（卡片）；`SupervisorPair/SessionCountStrip.tsx`（或 `PairStatusBar.tsx`）（监督者面板，pairId→node 反查，D41）。
**P5｜settings + 测试**：settings 键 + UI；引擎/前端单测。

```
src/main/java/.../workflow/SupervisorWorkflowManager.java     # 基础+watchdog+推送
src/main/java/.../settings/CodemossSettingsService.java       # 阈值键
src/main/java/.../settings/... (settings UI handler)          # 配置项
webview/src/global.d.ts
webview/src/components/WorkflowOrchestration/types.ts          # capabilities 字段
webview/src/components/WorkflowOrchestration/WorkflowContext.tsx
webview/src/components/WorkflowOrchestration/WorkflowView.tsx
webview/src/components/WorkflowOrchestration/DagCanvas.tsx
webview/src/components/WorkflowOrchestration/NodeCard.tsx
webview/src/components/WorkflowOrchestration/style.module.less
webview/src/components/SupervisorPair/SessionCountStrip.tsx   # 监督者面板显示(D41)
webview/src/i18n/locales/zh.json, en.json
webview/src/components/settings/... (阈值输入，若放前端 settings)
```

## 11. 风险与回退
| 风险 | 缓解 |
|---|---|
| 自动重发在半成品上重跑（D4 无回滚） | 仅"双双静默超阈值"才触发；退避 + 可见提示;手动可随时接管 |
| 无限重启刷屏 | 退避封顶每小时；每次推提示;复活归零 |
| 误判长任务 | 10min 保守阈值 + 可配；in-flight 但有流→`lastActivityAt` 前进不误判 |
| 5s tick 开销 | 仅 exec RUNNING 时；读 AtomicLong + 小 JSON;可调 `WATCHDOG_TICK_MS` |
| 显示与判定不一致 | 同一 `effectiveLastActiveAt`，物理上不可能不一致 |

回退：`freezeThresholdMinutes=0` 关闭自愈（仅保留显示）；移除卡片那一行即去掉显示；watchdog 调度器独立，停掉不影响其余。
