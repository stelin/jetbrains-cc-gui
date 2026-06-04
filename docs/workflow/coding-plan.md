# 监督者编排工作流 — 详细编码方案（Implementation Plan）

> 状态：**讨论已收敛，待实施**（2026-06-02，v2 补全）
> 上游：本文件是 [`prd.md`](./prd.md) 的落地版。PRD 定"做什么"，本文件定"怎么编码"——精确到类、方法、协议字段、线程、故障矩阵、测试。
> 关联代码（已核对行号）：
> - 插件 Java：`session/pair/PairSessionManager.java`、`session/pair/ActionRouter.java`(:101 project / :102 pair / :478 switch / :534 handleCompletePlan)、`session/pair/plan/PlanStateMachine.java`(:185 onEscalatedToHuman / :310 onPlanCompleted)、`session/pair/PairSession.java`(:213 getPairDir / :344 getPlanStateMachine)、`handler/PairHandler.java`(:317 后台 startPair / :846 replayActivePairs)、`handler/TabHandler.java`、`handler/SupervisorAgentHandler.java`、`ui/toolwindow/ClaudeChatWindow.java`(:67 windowId / :329 getClaudeSDKBridge)、`ui/ChatWindowDelegate.java`(:278-330 handler 注册)、`settings/RemoteModeContext.java`(isRemote)
> - daemon：`ai-bridge/services/supervisor/supervisor-tools.js`、`ai-bridge/channels/supervisor-channel.js`
> - 远端 server：`ai-bridge-server/src/session-manager.js`(:153-173 透传 / :138 spawnDaemon / :32 DAEMON_PATH)、`src/sse-hub.js`、`src/server.js`
> - 前端：`webview/src/components/WorkflowOrchestration/`（`WorkflowContext.tsx`、`WorkflowView.tsx`、`types.ts`）

---

## 目录

1. 名词与坐标
2. 锁定的设计决策
3. 架构总览
4. JS ↔ Java 协议
5. 数据模型
6. **远端 server：要不要改（定论）**
7. daemon / 上报工具
8. Java 引擎 `SupervisorWorkflowManager`
9. `WorkflowHandler`（per-tab 适配层）
10. `WorkflowStore`（持久化）
11. `DagValidator`（环检测 + 校验）
12. `ActionRouter` 新 case + `ClaudeChatWindow` / `ChatWindowDelegate` 改动
13. 上下文握手
14. 并发 ceiling + 模式判定 + 前端三件套
15. 线程模型
16. 关键流程时序图
17. 故障与边界矩阵
18. 生命周期与清理
19. 可观测性与日志
20. 安全
21. i18n
22. 测试策略
23. 分期实施
24. 文件触点清单
25. 风险与待验证
26. MVP 验收口径

---

## 1. 名词与坐标

| 概念 | 落点 |
|---|---|
| 节点 node | = 一个对话 tab = 一个 pair（1 监督者 + 1 主 AI）；节点名 = tab 名 |
| 工作流 workflow | 一组节点 + 依赖边（DAG） |
| 编排引擎 | `SupervisorWorkflowManager`（新建，`@Service(PROJECT)` 单例） |
| 协议适配 | `WorkflowHandler`（新建，per-tab，薄层） |
| 上报工具 | `emit_action(complete_workflow_node, …)`（新增 action type，**唯一** node→引擎通道） |

---

## 2. 锁定的设计决策（PRD D1–D18 之上本轮新增/细化）

| # | 决策 | 结论 |
|---|---|---|
| **DN1** | node→引擎通信 | **唯一通道 = 新工具 `complete_workflow_node`**（`status: done\|blocked`）。引擎不挂业务监听器 |
| **DN2** | 看门狗"卡死"同步（监督者物理上发不出工具的唯一例外） | **采用 (b)**：一条"只判看门狗 WAITING、仅挂自建 pair"的薄监听器，把这一种同步成 `WAITING_HUMAN`，保证总览不"说谎"。⚠️ 可降级 (a) 见 §8.6 |
| **DN3** | 跨层调度 | **采用 rolling 滚动窗口**：`dependsOn` 全满足即入队，不等整层。同层并发分批由 `Semaphore(N)` 处理。⚠️ strict 开关见 §8.4 |
| **DN4** | 并发 ceiling | 纯配置，硬上限 **3**，默认工作流并发 **2**；编辑器选择器 1–3 |
| **DN5** | 上报指令措辞 | 注入**每节点有效 plan 末尾**，不污染全局 system prompt |
| **DN6** | 存储 | per-project：`~/.codemoss/workflows/<projectHash>/<wfId>/` |
| **DN7** | executionUpdate | 仅离散跃迁推全量快照；无实时 token；广播所有 project webview |
| **DN8** | 远端 | server `src/` **零改动**（透传已证实）；只同步 daemon 副本 + 重建镜像（详见 §6） |
| **DN9** | 远程 daemon 崩溃 | server 发 `_ctrl/DAEMON_DOWN`；引擎把该节点 funnel 成 `WAITING_HUMAN`（§17） |

> 复用 PRD：D1 引擎在 Java、D2 节点=tab=pair、D4 不重试不回滚、D7 不 rewind、D12 单工作流锁、D13 tab 跑完保留、D14 无自动 FAILED、D17 MVP 不续跑。

---

## 3. 架构总览

```
┌─ 编辑器 tab webview（WorkflowView / WorkflowContext.tsx，已实现）──────────┐
│  → workflow_list/save/delete/run/abort/jump_node/open_report               │
│  ← onWorkflowDefinitions / onWorkflowExecutionUpdate / onWorkflowEscalation │
│  ← onWorkflowOperationResult / onWorkflowCapabilities(新增)                 │
└───────────────┬────────────────────────────────────────────────────────────┘
                │ window.sendToJava("workflow_*:json")   （经 ClaudeChatWindow → MessageDispatcher）
        ┌───────▼──────────────────────────┐
        │ WorkflowHandler (per-tab 薄适配)  │ 新建  §9
        │  · 解析 workflow_* → 调引擎       │
        │  · 注册自身为 UI sink（广播目标）  │
        └───────┬──────────────────────────┘
                │ SupervisorWorkflowManager.getInstance(project).xxx(...)
        ┌───────▼─────────────────────────────────────────────┐
        │ SupervisorWorkflowManager  @Service(PROJECT) 单例     │ 新建  §8
        │  单线程调度 executor + Semaphore(N) rolling           │
        │  startNode:(EDT)建tab → (后台)startPair               │
        │  onNodeReport(pairId, DONE|WAITING_HUMAN,…)           │
        │  abort / jump / openReport / 环检测 / 持久化 / 广播     │
        └───┬─────────────────────────────┬────────────────────┘
   startPair│                             │ onNodeReport(...)
   ┌────────▼──────────┐        ┌──────────▼────────────────────┐
   │ PairSessionManager│        │ ActionRouter (per-pair)        │ §12
   │ (现有 startPair)  │        │ case "complete_workflow_node"  │ 新增 case
   └───────────────────┘        └──────────▲─────────────────────┘
                                            │ [SUPERVISOR_ACTION] NDJSON
                            本地 stdout │ 远程 SSE（server 透传，§6）
                                            │
                       daemon.js · supervisor-tools.js（新 action type，§7）
                       本地 = 插件 ai-bridge｜远程 = 容器内同一份（sync）
```

闭环：引擎拼 plan（含"完成请 emit complete_workflow_node"）→ startPair → 监督者干活 → emit 工具 → daemon NDJSON → Java ActionRouter → `onNodeReport` → 推进 DAG + 广播快照。

---

## 4. JS ↔ Java 协议（前端已钉死，后端实现）

### 4.1 前端 → Java（`sendBridgeEvent(event, jsonContent)` → `"event:content"`）

| event | payload | 引擎动作 |
|---|---|---|
| `workflow_list` | `""` | 推 `onWorkflowDefinitions` + `onWorkflowCapabilities`；若有 RUNNING 工作流再补一份 `onWorkflowExecutionUpdate` |
| `workflow_save` | `WorkflowDefinition` | 写 `definition.json`，回推 `onWorkflowDefinitions` |
| `workflow_delete` | `{id}` | 删目录，回推 `onWorkflowDefinitions`；若删的是 RUNNING 的 → 拒绝并回 `onWorkflowOperationResult` |
| `workflow_run` | `{id}` | `startWorkflow(id)`（锁 + 环检测） |
| `workflow_abort` | `{}` | `abortWorkflow()` |
| `workflow_jump_node` | `{nodeName}` | 聚焦节点 tab |
| `workflow_open_report` | `{nodeName}` | 打开 `COMPLETION_REPORT.md` |

### 4.2 Java → 前端（`callJavaScript(fn, escapeJs(json))`，仿 `SupervisorAgentHandler.pushToWebview` :297）

| 回调 | payload | 时机 |
|---|---|---|
| `window.onWorkflowDefinitions` | `WorkflowDefinition[]` | list/save/delete 后；**后端权威**覆盖 localStorage |
| `window.onWorkflowExecutionUpdate` | `WorkflowExecution` 全量快照 | 每次节点/工作流跃迁（DN7） |
| `window.onWorkflowEscalation` | `{nodeName, reason?}` | 节点进入 WAITING_HUMAN |
| `window.onWorkflowOperationResult` | `{success, operation, error?}` | run/save/delete 回执 |
| `window.onWorkflowCapabilities` | `{mode, maxConcurrency}` | **新增**；`workflow_list` 时推 |

> 仅 `onWorkflowCapabilities` 是新增 Java→JS 回调；其余前端已接好（`WorkflowContext.tsx:122-176`）。前端目前用 localStorage 兜底，后端一推 `onWorkflowDefinitions` 即接管。

---

## 5. 数据模型（Java 侧，镜像 `types.ts`）

新建包 `com.github.claudecodegui.session.pair.workflow`：

```java
class WorkflowDefinition { String id; String name; Integer maxConcurrency; List<WorkflowNode> nodes; Long updatedAt; }
class WorkflowNode {
    String name;            // = tab 名，工作流内唯一
    String supervisorId;    // 复用 supervisor-agents.json 的 agentId
    String plan;            // 内联任务 markdown
    String planPath;        // 可选：磁盘 .md 引用（拼装时优先用拼装结果）
    String model;           // → StartPairParams.modelOverride
    Boolean longContext;    // → StartPairParams.longContextOverride
    String reasoning;       // → StartPairParams.reasoningOverride
    List<String> dependsOn; // 依赖节点名
    Double posX, posY;      // 画布坐标，引擎透传
}
enum NodeStatus { PENDING, READY, RUNNING, WAITING_HUMAN, DONE, ABORTED }
enum WorkflowState { EDITING, RUNNING, COMPLETED, ABORTED }
class NodeRuntime {
    NodeStatus status = PENDING;
    String pairId, windowId, completionReportPath, escalationReason;
    transient List<String> changedFiles;  // 上报工具带来，喂下游；不进协议
    transient String summary;
}
class WorkflowExecution { String workflowId; WorkflowState state; int concurrency; Map<String,NodeRuntime> nodes; }
```

Gson 序列化（与 `SupervisorAgentHandler` 一致）；`transient` 字段不进 `onWorkflowExecutionUpdate`（前端 `NodeRuntime` 也没有它们）。

---

## 6. 远端 server：要不要改？——**定论：`src/` 零业务改动**

用户关切点。结论基于对 `ai-bridge-server/src/` 的核查，证据如下。

### 6.1 出站（daemon → 插件）是**完全透传**，无类型白名单
`src/session-manager.js:153-173` 逐行读 daemon stdout 后 `s.hub.publish(trimmed)` 原样发布到 SSE，源码注释明言 *"the wire data to subscribers is byte-identical"*。**新 `[SUPERVISOR_ACTION]`（含新 action type）原样穿过 SSE 到插件**，无需任何 server 改动。

### 6.2 入站（插件 → daemon）同样透传
`server.js` 路由 `POST /session/{id}/in` 把行写进 daemon stdin（监督者输入与主 AI 输入复用同一 session 的 stdin，由 daemon 内部按 NDJSON 的 `id` 路由）。无类型解析，新行透传。

### 6.3 跑的就是同步过来的 daemon
`src/session-manager.js:32` `DAEMON_PATH = ../ai-bridge/daemon.js`——server 启动的是它**自带的 `ai-bridge/` 副本**（含 `services/supervisor/supervisor-tools.js`）。所以"远端改动"= 同步那份副本 + 重建镜像，**不是改 `src/`**。

### 6.4 无 session 数上限 → ceiling 纯资源约束
`session-manager` 用 `Map` 持有多 session（active/freezing/frozen/destroying + idle/maxFrozen 定时器），**无 maxSession 上限**。N 个节点 = N 个 session = N 个 daemon 子进程，仅受容器 CPU/内存约束。这坐实"ceiling 纯配置 + P0 探安全值"。

### 6.5 远端独有故障：`DAEMON_DOWN`（DN9，方案此前漏项）
`session-manager.js:220-228`：daemon 在 active 态崩溃时，server 发 `{type:'_ctrl', action:'gateway_error', code:'DAEMON_DOWN'}` 并销毁 session。该控制消息插件侧已有消费点（`ChatWindowDelegate` / `ControlMessageHandler` / `RemoteBridge`）。**工作流必须把"某节点 daemon 崩溃"映射为该节点 `WAITING_HUMAN`**（funnel 人工，符合 D14），见 §17。

### 6.6 所以"远端"清单
| 动作 | 内容 |
|---|---|
| 同步 daemon | `cd ai-bridge-server && ./scripts/sync-from-upstream.sh --apply`（`supervisor-tools.js` 非保护文件，正常覆盖；保护名单仅 `daemon.js`、`permission-ipc.js`） |
| 重建镜像 | 按 `ai-bridge-server/docker/` 重建并发布容器镜像 |
| **server `src/`** | **不改** |
| 引擎对 DAEMON_DOWN 的处理 | §17，在插件 Java 侧做 |
| （v-next，可选）`GET /capabilities` | server 暴露"最大可并发 session / 容器规格"，供 ceiling 动态探测。MVP 不做，用纯配置 |

---

## 7. daemon / 上报工具

### 7.1 `ai-bridge/services/supervisor/supervisor-tools.js`（本地源，非保护文件）
1. `ACTION_TYPES` 追加 `'complete_workflow_node'`。
2. Zod schema 追加：
   ```js
   node_status:   z.enum(['done','blocked']).optional()
       .describe('Required when action is complete_workflow_node.'),
   changed_files: z.array(z.string()).optional()
       .describe('complete_workflow_node + done: files this node created/modified.'),
   // summary 复用 complete_plan 已有字段，作完成/受阻说明
   ```
3. `validateAndBuild` switch 加：
   ```js
   case 'complete_workflow_node':
     if (args.node_status !== 'done' && args.node_status !== 'blocked')
       error = 'complete_workflow_node requires node_status = done|blocked';
     else {
       payload.node_status = args.node_status;
       if (typeof args.summary === 'string') payload.summary = args.summary;
       if (args.node_status === 'done' && Array.isArray(args.changed_files))
         payload.changed_files = args.changed_files;
     }
     break;
   ```

### 7.2 不改全局 system prompt（DN5）
`supervisor-channel.js` 的全局动作枚举**保持不变**——监督者靠每节点 plan 末尾的注入指令（§13）得知此工具；schema enum 已让工具对模型可见。R3（§25）：P2 实测模型是否仅凭 schema + 注入就会调用；若不稳定，再在 channel 加一行**条件**提示（仅当任务文本含工作流标记时）。

### 7.3 同步到远端
见 §6.6。逻辑路由全在 Java `ActionRouter`，两模式共用。

---

## 8. Java 引擎 `SupervisorWorkflowManager`

`@Service(Service.Level.PROJECT)`，`getInstance(Project)`（仿 `PairSessionManager:105`）。

### 8.1 字段
```java
private final Project project;
private final Gson gson = new Gson();
private final ExecutorService scheduler =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "wf-scheduler"));
private final List<HandlerContext.JsCallback> uiSinks = new CopyOnWriteArrayList<>();
private final WorkflowStore store;                          // §10
private volatile WorkflowDefinition currentDef;
private volatile WorkflowExecution exec;                    // null = 无运行中工作流
private Semaphore slots;
private final Deque<String> readyQueue = new ArrayDeque<>();// 仅 scheduler 线程访问
private final Map<String,String> pairToNode = new ConcurrentHashMap<>();
private final Map<String,NodeHandle> handles = new ConcurrentHashMap<>(); // nodeName→{Content,ClaudeChatWindow,PairSession}
private static final boolean DN2_BACKSTOP = true;           // §8.6 看门狗薄兜底开关
```
`NodeHandle { Content content; ClaudeChatWindow win; volatile PairSession pair; }`

### 8.2 单工作流锁 + 启动
```java
public void startWorkflow(String id) { scheduler.submit(() -> {
  if (exec != null && exec.state == RUNNING) { opResult("run", false, "已有工作流在运行"); return; }
  WorkflowDefinition def = store.load(id);
  if (def == null) { opResult("run", false, "工作流不存在"); return; }
  String err = DagValidator.validate(def, knownSupervisorIds());  // §11
  if (err != null) { opResult("run", false, err); return; }
  currentDef = def;
  int n = clamp(def.maxConcurrency == null ? 2 : def.maxConcurrency, 1, ceiling());
  exec = newExecution(def, n);                 // 所有节点 PENDING
  slots = new Semaphore(n);
  readyQueue.clear(); pairToNode.clear(); handles.clear();
  exec.state = RUNNING;
  enqueueReady();
  store.saveExecution(id, exec); broadcastExec(); opResult("run", true, null);
  pump();
}); }
```

### 8.3 就绪判定（rolling，DN3）
```java
private void enqueueReady() {                  // scheduler 线程
  for (var e : exec.nodes.entrySet()) {
    NodeRuntime r = e.getValue();
    if (r.status == PENDING && allDepsDone(e.getKey())) { r.status = READY; readyQueue.add(e.getKey()); }
  }
}
private boolean allDepsDone(String name) {
  return node(name).dependsOn.stream().allMatch(d -> exec.nodes.get(d).status == DONE);
}
```

### 8.4 调度主循环
```java
private void pump() {                           // scheduler 线程
  while (slots.tryAcquire()) {
    String name = readyQueue.poll();
    if (name == null) { slots.release(); break; }
    startNode(node(name));                      // 占槽，直到该节点 DONE 才 release
  }
}
```
> **strict 开关（替代 DN3）**：把 `enqueueReady` 改成按拓扑层灌入、整层 DONE 才放下一层；`pump`/`Semaphore` 不变。

### 8.5 startNode（EDT 建 tab → 后台 startPair）
```java
private void startNode(WorkflowNode node) {     // scheduler 线程进入
  NodeRuntime rt = exec.nodes.get(node.name);
  ToolWindowManager.getInstance(project).invokeLater(() -> {   // —— EDT ——
    ClaudeChatWindow win = new ClaudeChatWindow(project, true);
    ContentManager cm = toolWindow().getContentManager();
    Content content = ContentFactory.getInstance()
        .createContent(win.getContent(), uniqueTabName(node.name, cm), false);
    content.setCloseable(true); win.setParentContent(content); content.setDisposer(win::dispose);
    cm.addContent(content);
    handles.put(node.name, new NodeHandle(content, win));
    rt.windowId = win.getWindowId();            // §12.2 新增 accessor

    AppExecutorUtil.getAppExecutorService().submit(() -> {     // —— 后台（startPair 内含 20s 同步握手, PairHandler:317）——
      try {
        Path planPath = assembleAndWritePlan(node);            // §13
        PairSession pair = PairSessionManager.getInstance(project).startPair(
            new PairSessionManager.StartPairParams(
                null, node.supervisorId, planPath.toString(),
                node.model, node.longContext, node.reasoning, win.getWindowId()),
            win.getClaudeSDKBridge());
        handles.get(node.name).pair = pair;
        rt.pairId = pair.getPairId();
        pairToNode.put(pair.getPairId(), node.name);
        if (DN2_BACKSTOP) attachWatchdogBackstop(pair, node.name);
        scheduler.submit(() -> { rt.status = RUNNING; persist(); broadcastExec(); });
      } catch (Exception ex) {
        scheduler.submit(() -> failNodeToHuman(node.name, "启动失败: " + ex.getMessage()));
      }
    });
  });
}
```
要点：① 节点 tab React 后挂载发 `pair_webview_ready` 时，`PairHandler.replayActivePairs`（按 ownerWindowId 过滤，:846）回放已起 pair——**引擎不等 webview**。② `uniqueTabName` 复用 `ClaudeSDKToolWindow.getNextTabName` 去重，避免撞 AI1/AI2。

### 8.6 唯一回调 `onNodeReport` + 看门狗薄兜底
```java
public void onNodeReport(String pairId, NodeStatus status, String summary, List<String> changed) {
  scheduler.submit(() -> {
    if (exec == null || exec.state != RUNNING) return;
    String name = pairToNode.get(pairId);
    if (name == null) return;                   // 非工作流节点（普通 pair）→ 忽略
    NodeRuntime rt = exec.nodes.get(name);
    if (rt.status == DONE || rt.status == ABORTED) return;   // 幂等：重复上报忽略
    if (status == DONE) {
      rt.status = DONE; rt.summary = summary; rt.changedFiles = changed;
      NodeHandle h = handles.get(name);
      if (h != null && h.pair != null)
        rt.completionReportPath = h.pair.getPairDir().resolve("COMPLETION_REPORT.md").toString();
      slots.release();
      enqueueReady();
      if (allNodesDone()) exec.state = COMPLETED;
      persist(); broadcastExec();
      if (exec.state == RUNNING) pump();
    } else { // WAITING_HUMAN
      rt.status = WAITING_HUMAN; rt.escalationReason = summary;
      escalate(name, summary); persist(); broadcastExec();   // 不释放槽、不推进（D11）
    }
  });
}

// DN2：监督者卡死时它发不出工具，只能 Java 侧补。仅挂自建 pair。
private void attachWatchdogBackstop(PairSession pair, String nodeName) {
  pair.getPlanStateMachine().addListener((o, oSub, now) -> {
    if (now == null || now.state != Plan.PlanState.WAITING || "user".equals(now.pauseReason)) return;
    scheduler.submit(() -> {
      if (exec == null) return;
      NodeRuntime rt = exec.nodes.get(nodeName);
      if (rt == null || rt.status == WAITING_HUMAN || rt.status == DONE || rt.status == ABORTED) return;
      rt.status = WAITING_HUMAN; rt.escalationReason = "看门狗：监督者无响应";
      escalate(nodeName, rt.escalationReason); persist(); broadcastExec();
    });
  });
}
```
> 受阻 → 人工在该 tab 处理好 → 监督者照常 emit `complete_workflow_node(done)` → 走 DONE 路径自动推进，无额外按钮（D4/D15）。
> ⚠️ DN2 降级 (a)：去掉 `attachWatchdogBackstop`，看门狗卡死时总览短暂显示 RUNNING（节点 tab 仍有 escalation UI）。

### 8.7 abort / jump / openReport / 共用
```java
public void abortWorkflow() { scheduler.submit(() -> {
  if (exec == null || exec.state != RUNNING) return;
  for (NodeHandle h : handles.values())
    PairSessionManager.getInstance(project).stopPairsOwnedBy(h.win.getWindowId());
  for (NodeRuntime r : exec.nodes.values())
    if (r.status == RUNNING || r.status == READY || r.status == WAITING_HUMAN || r.status == PENDING)
      r.status = ABORTED;
  exec.state = ABORTED; persist(); broadcastExec();   // tab 保留（D13）
}); }

public void jumpToNode(String name) { ApplicationManager.getApplication().invokeLater(() -> {
  NodeHandle h = handles.get(name);
  if (h != null) { toolWindow().getContentManager().setSelectedContent(h.content); toolWindow().show(null); }
}); }

public void openReport(String name) {
  NodeRuntime rt = exec == null ? null : exec.nodes.get(name);
  if (rt != null && rt.completionReportPath != null) openFileInEditor(rt.completionReportPath); // 复用现有 open_file 通路
}

private void failNodeToHuman(String name, String reason) { // startPair 失败 / DAEMON_DOWN 共用
  NodeRuntime rt = exec.nodes.get(name);
  if (rt == null || rt.status == DONE || rt.status == ABORTED) return;
  rt.status = WAITING_HUMAN; rt.escalationReason = reason;
  escalate(name, reason); persist(); broadcastExec();
}
```

### 8.8 广播 / escalate / 回执
```java
private void broadcastExec() {
  if (exec == null) return;
  String json = gson.toJson(exec);
  for (var s : uiSinks) ApplicationManager.getApplication().invokeLater(() ->
      s.callJavaScript("window.onWorkflowExecutionUpdate", s.escapeJs(json)));
}
private void escalate(String name, String reason) {
  JsonObject o = new JsonObject(); o.addProperty("nodeName", name);
  if (reason != null) o.addProperty("reason", reason);
  String json = gson.toJson(o);
  for (var s : uiSinks) ApplicationManager.getApplication().invokeLater(() ->
      s.callJavaScript("window.onWorkflowEscalation", s.escapeJs(json)));
  jumpToNode(name);                              // 自动切到该 tab（D8）
}
private void opResult(String op, boolean ok, String err) { /* 推 window.onWorkflowOperationResult */ }
void registerSink(HandlerContext.JsCallback s){ uiSinks.add(s);} void unregisterSink(HandlerContext.JsCallback s){ uiSinks.remove(s);}
private void persist(){ if (exec!=null) store.saveExecution(exec.workflowId, exec); }
```

---

## 9. `WorkflowHandler`（per-tab 适配层，仿 `SupervisorAgentHandler`）

```java
public class WorkflowHandler extends BaseMessageHandler {
  private static final String[] TYPES = {
    "workflow_list","workflow_save","workflow_delete","workflow_run",
    "workflow_abort","workflow_jump_node","workflow_open_report" };
  private final SupervisorWorkflowManager mgr;
  private final HandlerContext.JsCallback sink;   // = context 的 js 回调

  public WorkflowHandler(HandlerContext ctx) {
    super(ctx);
    this.mgr = SupervisorWorkflowManager.getInstance(ctx.getProject());
    this.sink = /* 包装 ctx.callJavaScript/escapeJs 成 JsCallback */;
    mgr.registerSink(sink);                       // 该 tab 成为广播目标
  }
  @Override public String[] getSupportedTypes(){ return TYPES; }
  @Override public boolean handle(String type, String content) {
    switch (type) {
      case "workflow_list":      pushDefinitions(); pushCapabilities(); mgr.pushCurrentExecutionIfRunning(); return true;
      case "workflow_save":      mgr.saveDefinition(parse(content)); pushDefinitions(); return true;
      case "workflow_delete":    mgr.deleteDefinition(idOf(content)); pushDefinitions(); return true;
      case "workflow_run":       mgr.startWorkflow(idOf(content)); return true;
      case "workflow_abort":     mgr.abortWorkflow(); return true;
      case "workflow_jump_node": mgr.jumpToNode(nodeNameOf(content)); return true;
      case "workflow_open_report": mgr.openReport(nodeNameOf(content)); return true;
      default: return false;
    }
  }
  // dispose 时 mgr.unregisterSink(sink)（在 ChatWindowDelegate 的 handler 清理路径里调）
}
```
- **生命周期**：tab 关闭 → `MessageDispatcher.clear()`（`ClaudeChatWindow:704`）→ 需在此前 `unregisterSink`。建议 `WorkflowHandler` 实现一个 `dispose()`，由 delegate 清理时调用；或 sink 用弱引用 + 推送前判 `context.isDisposed()`。
- **广播鲁棒性**：编辑器 tab 关了→开 → 新 `WorkflowHandler` 再 `registerSink` + `workflow_list` 补一份快照，状态续上（DN7）。

---

## 10. `WorkflowStore`（持久化，DN6）

- 根：`~/.codemoss/workflows/<projectHash>/`，`<projectHash>` = project basePath 的 hash（对齐现有 per-project 惯例）。
- 每工作流：`<wfId>/definition.json`、`<wfId>/execution.json`、`<wfId>/nodes/<nodeName>/plan.md`。
- API：`List<WorkflowDefinition> loadAll()`、`WorkflowDefinition load(id)`、`void save(def)`、`void delete(id)`、`void saveExecution(id, exec)`、`WorkflowExecution loadExecution(id)`。
- **原子写**：写 `xxx.json.tmp` 再 `Files.move(ATOMIC_MOVE)`，避免崩溃半写。
- **并发**：定义读写只在 handler 线程（EDT 派发）；execution 写只在 scheduler 线程。两者不同文件，无冲突。
- **节点名→目录名**：`nodeName` 可能含特殊字符 → 落盘前做文件名安全化（保留映射，或用 nodeName 的 hash 作目录、内放 `name.txt`）。

---

## 11. `DagValidator`（环检测 + 校验，后端必做，不信前端）

`String validate(WorkflowDefinition def, Set<String> knownSupervisorIds)`，返回 null=通过 / 否则错误消息：
1. 节点非空；`name` 全工作流唯一、非空。
2. 每个 `dependsOn` 指向**存在**的节点名；不依赖自身。
3. `supervisorId` 非空且 ∈ `knownSupervisorIds`（来自 `SupervisorAgentManager`）。
4. **无环**：Kahn 拓扑（入度队列）或 DFS 三色法；有环返回参与环的节点名。
5. 至少一个入度为 0 的节点（否则全是环，已被 4 覆盖）。

> 前端 `layout.ts:forbiddenDeps` 只是连边时的 UX 防呆；引擎以本校验为准。

---

## 12. `ActionRouter` 新 case + `ClaudeChatWindow` / `ChatWindowDelegate`

### 12.1 `session/pair/ActionRouter.java`（`dispatch` switch，~:478）
```java
case "complete_workflow_node": {
  String st = payload.has("node_status") ? payload.get("node_status").getAsString() : "done";
  String summary = payload.has("summary") ? payload.get("summary").getAsString() : "";
  var mgr = SupervisorWorkflowManager.getInstance(project);   // project 字段已存在(:101)
  if ("done".equals(st)) {
    handleCompletePlan(payload);                               // 复用(:534)：→DONE+写报告+推状态
    List<String> files = payload.has("changed_files")
        ? toStringList(payload.getAsJsonArray("changed_files")) : java.util.List.of();
    mgr.onNodeReport(pair.getPairId(), NodeStatus.DONE, summary, files);
  } else {
    pair.getPlanStateMachine().onEscalatedToHuman(summary);    // 复用(:185)：节点 tab 亮 escalation
    mgr.onNodeReport(pair.getPairId(), NodeStatus.WAITING_HUMAN, summary, null);
  }
  break;
}
```
`ActionRouter` 已持 `project`(:101)+`pair`(:102)，无需改构造。

### 12.2 `ui/toolwindow/ClaudeChatWindow.java`
字段 `windowId`(:67) 加 public：`public String getWindowId() { return windowId; }`

### 12.3 `ui/ChatWindowDelegate.java`（:278-330）
```java
WorkflowHandler workflowHandler = new WorkflowHandler(handlerContext);
messageDispatcher.registerHandler(workflowHandler);
// 在 handler 清理路径（messageDispatcher.clear 之前）调用 workflowHandler.dispose() 解绑 sink
```

---

## 13. 上下文握手（PRD §6 / D3 / D16）

`assembleAndWritePlan(node)` 产出有效 plan：
```
<node.plan 原文>

## 上游产出
### <依赖节点A>（监督者：<agentName>）
<A 的 COMPLETION_REPORT.md 摘要>            ← 读 handles[A].pair.getPairDir()/COMPLETION_REPORT.md
改动文件：<A 的 changedFiles>               ← 来自上报工具 payload，缺省回落报告里清单
### <依赖节点B> …

---
[工作流编排] 你是工作流节点「<node.name>」。整体任务完成后**必须**调用
emit_action(action="complete_workflow_node", node_status="done", summary="…", changed_files=[…])；
受阻需人工时调用 node_status="blocked" + summary 说明卡点。
```
- (a) 共享文件系统：所有节点 tab 同一工作目录，上游真实产出下游主 AI 直接读。
- (b) 注入摘要：join 节点注入多份；用摘要 + 文件清单不用全文，防上下文膨胀（监督者 1M 兜底）。
- 写到 `<wfId>/nodes/<nodeName>/plan.md`，作 `StartPairParams.planPath` 传入（复用现有通路）。
- **摘要来源**：优先 COMPLETION_REPORT.md 的开头若干段 / 指定小节；上报工具的 `summary` 作补充。MVP 可先注入报告全文（节点少时可接受），P3 再做摘要裁剪。

---

## 14. 并发 ceiling + 模式判定 + 前端三件套（DN4）

### 14.1 ceiling（纯配置）
- 配置位加到 `CodemossSettingsService`（同 supervisor 配置族）：`workflow.maxConcurrency`，**默认 2，硬上限 3**。
- `int ceiling()` = `min(配置值, 3)`。

### 14.2 模式判定
- `String mode()` = `RemoteModeContext.getInstance() != null && RemoteModeContext.getInstance().isRemote() ? "remote" : "local"`（见 `ClaudeSDKBridge:435-437` 同款用法）。

### 14.3 capabilities 推送
```java
void pushCapabilities() {
  JsonObject o = new JsonObject();
  o.addProperty("mode", mode());
  o.addProperty("maxConcurrency", ceiling());
  push("window.onWorkflowCapabilities", gson.toJson(o));
}
```

### 14.4 前端改动（纳入本方案）
- `types.ts`：`WorkflowDefinition` 加 `maxConcurrency?: number`。
- `WorkflowContext.tsx`：监听 `window.onWorkflowCapabilities` 存 `{mode, maxConcurrency}`；`saveDraft` 带上 `maxConcurrency`（默认 2）。
- `WorkflowView.tsx` 顶栏：加并发选择器，范围 `1..capabilities.maxConcurrency`，运行中只读。
- 运行态 `WorkflowExecution.concurrency`（已有）展示实际生效值。

---

## 15. 线程模型（务必遵守）

| 工作 | 线程 |
|---|---|
| 调度推进（readyQueue / semaphore / nodes 读写） | **`wf-scheduler` 单线程**，串行 |
| 建 tab / 切 tab / 打开报告 | **EDT**（`ToolWindowManager.invokeLater` / `ApplicationManager.invokeLater`） |
| `startPair`（20s 同步握手） | **后台池** `AppExecutorUtil`（绝不在 EDT，见 `PairHandler:317`） |
| `onNodeReport` / 看门狗监听器回调 | 入口第一步 `scheduler.submit(...)` 转回调度线程 |
| 推 webview | `invokeLater` + `callJavaScript`（仿 `SupervisorAgentHandler:297`） |

不变式：`exec` / `slots` / `readyQueue` / `pairToNode` 的**写**只在 scheduler 线程；`handles` 用 `ConcurrentHashMap`（EDT 建 tab 时 put、后台 startPair 后改 pair 字段）。

---

## 16. 关键流程时序图

### 16.1 启动 + 扇出（A、B 无依赖；C 依赖 A、B；并发=2）
```
webview        WorkflowHandler   Manager(scheduler)        EDT            后台池          PairMgr/daemon
  │ run{id} ──────►│ startWorkflow ─►│ 锁+环检测,exec=RUNNING
  │                │                 │ enqueueReady→[A,B]; pump
  │                │                 │ tryAcquire×2 → startNode(A),startNode(B)
  │                │                 │   ├─ invokeLater ─────►│ 建 tabA
  │                │                 │   │                    │ submit ───►│ assemblePlan,startPair(A.bridge)─►│ pairA
  │                │                 │   │                    │            │ rt(A)=RUNNING (scheduler.submit)
  │ onExecUpdate ◄─┤ broadcastExec ◄─┤◄──────────────────────────────────────────────────┘
  │                │                 │   └─ (B 同上)
```

### 16.2 节点完成 → 推进下游
```
daemon(A) emit complete_workflow_node(done,summary,files)
   → [SUPERVISOR_ACTION] (本地 stdout / 远程 SSE 透传)
   → Java ActionRouter.dispatch → case → handleCompletePlan(写报告) + mgr.onNodeReport(pairA,DONE,...)
   → scheduler: rt(A)=DONE, slots.release, enqueueReady (C 仍缺 B → 不入队), broadcastExec, pump(无就绪)
（B 完成同理 → 此时 C 的 deps 全 DONE → C 入 readyQueue → pump → startNode(C)，plan 注入 A、B 摘要）
全部 DONE → exec.state=COMPLETED → broadcastExec
```

### 16.3 受阻（blocked）
```
daemon emit complete_workflow_node(blocked,summary)
 → ActionRouter: onEscalatedToHuman(节点tab亮UI) + mgr.onNodeReport(WAITING_HUMAN)
 → scheduler: rt=WAITING_HUMAN, escalate(push onWorkflowEscalation + jumpToNode), 不腾槽不推进
人工在该 tab 处理 → 监督者 emit complete_workflow_node(done) → 走 16.2 DONE 路径自动推进
```

### 16.4 看门狗卡死（DN2 兜底）
```
监督者无响应 → DeadlockGuard → PlanStateMachine→WAITING (Java侧, 监督者发不出工具)
 → attachWatchdogBackstop 监听器命中 → scheduler: rt=WAITING_HUMAN, escalate
（与 16.3 后续相同：人工救活 → done → 推进）
```

### 16.5 远程 daemon 崩溃（DN9）
```
容器内 daemon(节点X) 崩溃 → server publish _ctrl/DAEMON_DOWN(SSE)
 → 插件 RemoteBridge/ControlMessageHandler 收到 → 路由到 Manager.onNodeDaemonDown(windowId/pairId)
 → scheduler: failNodeToHuman(X, "远程 daemon 崩溃") → WAITING_HUMAN + escalate
```

### 16.6 中止
```
abort → scheduler: 每节点 stopPairsOwnedBy(windowId); 活动节点→ABORTED; exec=ABORTED; broadcast (tab 保留)
```

---

## 17. 故障与边界矩阵

| 场景 | 处理 |
|---|---|
| `startPair` 抛异常 | `failNodeToHuman(node, "启动失败:…")` → WAITING_HUMAN（不腾槽） |
| 监督者一直不上报、看门狗也没触发 | 节点停在 RUNNING（D14：无自动 FAILED，人工从 tab 介入；DN2 兜住看门狗那类） |
| 重复 `complete_workflow_node` | `onNodeReport` 幂等：DONE/ABORTED 节点再报直接 return |
| `done` 在 abort 之后到达 | `exec.state != RUNNING` → `onNodeReport` 早退 |
| 用户运行中**手动关掉**某节点 tab | `content.setDisposer(win::dispose)` → `stopPairsOwnedBy(windowId)`（`ClaudeChatWindow:664`）→ pair 停。该节点不会再上报 → 停在 RUNNING；建议：监听 tab 关闭 → 该节点置 WAITING_HUMAN（"tab 被关闭"）。**MVP 可先不特判，记为已知行为** |
| 用户删除**正在运行**的工作流 | `workflow_delete` 检查：若 = 当前 RUNNING 的 id → 拒绝，回 `onWorkflowOperationResult{success:false}` |
| 运行中 `workflow_save`（改定义） | 不影响当前 `exec`（引擎已快照 `currentDef`）；保存只更新磁盘 definition，下次 run 生效 |
| 远程 daemon 崩溃 `DAEMON_DOWN` | §16.5 → WAITING_HUMAN |
| 节点名含路径特殊字符 | `WorkflowStore` 文件名安全化（§10） |
| 节点名与手动 tab 撞名 | `uniqueTabName` 去重（§8.5） |
| `supervisorId` 在 run 前被删 | `DagValidator` 在 run 时校验 `knownSupervisorIds`，缺失则拒绝运行 |
| IDE 重启时有 RUNNING 工作流 | 启动钩子：`loadExecution`，pairs/tabs 已随 IDE 消失 → 标 `ABORTED`（DN/D17，MVP 不续跑） |
| webview 未就绪时广播 | `invokeLater` + `context.isDisposed()` 判空；sink 推送 best-effort，无 sink 时静默（前端 `workflow_list` 时补拉） |
| 两节点同时 DONE（并发回调） | 都经 `scheduler` 串行化，无竞态 |

---

## 18. 生命周期与清理

- **节点 tab**：完成/中止后**保留不关**（D13）。`handles` 在工作流结束后保留，供 jump/openReport；下次 `startWorkflow` 时 `handles.clear()`（旧 tab 仍在 UI 上，但不再被引擎引用）。
- **pair**：abort 时 `stopPairsOwnedBy`；正常完成的 pair 由其 tab 生命周期管理（用户关 tab 时 `dispose`）。
- **sink**：`WorkflowHandler.dispose()` 在 tab 关闭、`MessageDispatcher.clear()` 前 `unregisterSink`，防止向已 dispose 的 browser 推送。
- **scheduler executor**：随 project service dispose（`Disposable`）`shutdownNow()`。
- **pairToNode / handles**：`startWorkflow` 开头清空；abort/complete 不清（保留供 jump），下次 run 覆盖。

---

## 19. 可观测性与日志

统一前缀 `[Workflow]`：
- run/abort/complete：`[Workflow] start wf=<id> nodes=<n> concurrency=<N>`、`[Workflow] node <name> RUNNING pair=<id> window=<id>`、`[Workflow] node <name> DONE report=<path>`、`[Workflow] node <name> WAITING_HUMAN reason=<…>`、`[Workflow] COMPLETED|ABORTED wf=<id>`。
- 调度：`pump acquired/released slot, ready=<queue>`。
- 异常：startPair 失败、DAEMON_DOWN、环检测拒绝（带具体环节点）。
- 总览面板已是控制中心，日志为辅；不打 token 级高频日志（DN7）。

---

## 20. 安全

- **路径**：`completionReportPath` / `planPath` 必须在 `~/.codemoss/workflows/<projectHash>/` 或项目目录内；`openReport` 前做 canonical-path 前缀校验（前端 `utils/bridge.ts` 已有 traversal 防护，后端再校验一层）。
- **supervisorId**：run 前校验存在（§11），避免注入未知 agent。
- **plan 文本**：原样写文件、作 planPath 传 daemon，无 shell 拼接。
- 远程：沿用 server 既有部署侧鉴权（DESIGN §1.3：nginx/VPN）；本方案不引入新端点（capabilities 端点 v-next 再议鉴权）。

---

## 21. i18n

前端已用 `t('workflow.*')` 键（`WorkflowView.tsx`）。需在 `webview/src` 语言资源补齐：`workflow.title/run/abort/state.{editing,running,completed,aborted}/lockedRunning/pickOrCreate/inspectorEmpty/concurrency.*` 等。新增并发选择器需要 `workflow.concurrency.label` 等键（中/英）。

---

## 22. 测试策略

- **单元（无 IDE）**：
  - `DagValidator`：环 / 自依赖 / 悬空依赖 / 未知 supervisorId / 正常拓扑。
  - 调度状态机：给定 DAG + 并发 N，喂 `onNodeReport(DONE/blocked)`，断言节点状态流转、`slots` 占用、`readyQueue` 推进、终态 COMPLETED/ABORTED（把 EDT/后台/startPair 用接口 mock 掉）。
  - `assembleAndWritePlan`：上游摘要拼装、join 多份、注入指令存在。
  - `WorkflowStore`：原子写、loadAll、delete。
- **集成（headless / 现有 pair 测试基建）**：`startPair` 真起一个 pair（参考现有 pair 测试），断言 `ActionRouter` 收到 `complete_workflow_node` 后 `onNodeReport` 被调。
  - ⚠️ 记忆项：`go test` 与此无关；Java 侧用 Gradle test。
- **手动 P0（远程）**：手开 2 tab、远程各起 pair，确认消息不串、`replayActivePairs` 正常、容器扛住；再跑一个 2 节点工作流端到端。
- **手动验收**：§26。

---

## 23. 分期实施（映射 PRD §14）

- **P0｜远程并发实测**：手动 2 pair + 容器规格验证。**D1 赌注硬验证**，不过先补 RemoteBridge/server 多路复用（理论上透传已支持，重在实测）。
- **P1｜骨架直跑**：数据模型 + `WorkflowStore` + `DagValidator` + `SupervisorWorkflowManager`（锁/rolling 调度/startNode/广播）+ `WorkflowHandler` + `ClaudeChatWindow.getWindowId()` + `ChatWindowDelegate` 注册。打通"建 tab→组 plan→startPair"非 UI 链路。
- **P2｜上报工具闭环**：`supervisor-tools.js` 新 action + sync + 重建镜像；`ActionRouter` 新 case；`onNodeReport` DONE/blocked；DN2 看门狗兜底；DN9 DAEMON_DOWN。
- **P3｜上下文握手**：`assembleAndWritePlan` 注入上游摘要 + 文件清单（先全文，再裁剪摘要）。
- **P4｜协议 & 前端三件套**：`onWorkflowExecutionUpdate/Escalation/Capabilities` 广播；前端 `maxConcurrency` 字段 + 选择器；jump/open_report 接通；i18n 键。
- **P5｜打磨**：abort、重启标记、命名/文件名去重、`onWorkflowOperationResult` 回执、tab 手动关闭特判、日志。

---

## 24. 文件触点清单

**新增（Java，新包 `session/pair/workflow/`）**
- `SupervisorWorkflowManager.java`（引擎 `@Service(PROJECT)`，含 `onNodeDaemonDown`）
- `WorkflowDefinition/WorkflowNode/WorkflowExecution/NodeRuntime/NodeStatus/WorkflowState.java`
- `WorkflowStore.java`、`DagValidator.java`、`NodeHandle.java`
- `handler/WorkflowHandler.java`

**改动（Java）**
- `session/pair/ActionRouter.java`：`dispatch` switch 加 `case "complete_workflow_node"`
- `ui/toolwindow/ClaudeChatWindow.java`：加 `public String getWindowId()`
- `ui/ChatWindowDelegate.java`：注册 + dispose 解绑 `WorkflowHandler`
- `settings/*`：加 `workflow.maxConcurrency` 配置位
- 远程控制消息消费点（`ControlMessageHandler` / `ChatWindowDelegate`）：DAEMON_DOWN → `Manager.onNodeDaemonDown`
- 启动钩子（现有 startup 包）：重启时把 RUNNING execution 标 ABORTED

**改动（daemon，会 sync 到远端）**
- `ai-bridge/services/supervisor/supervisor-tools.js`：`ACTION_TYPES` + schema + handler 加 `complete_workflow_node`

**远端（无 src 改动）**
- `ai-bridge-server`：`./scripts/sync-from-upstream.sh --apply` + 重建镜像

**改动（前端）**
- `types.ts`：`WorkflowDefinition.maxConcurrency`
- `WorkflowView.tsx`：并发选择器
- `WorkflowContext.tsx`：`onWorkflowCapabilities` 监听 + save 带 `maxConcurrency`
- i18n 资源：`workflow.*` 补键

---

## 25. 风险与待验证

- **R1（P0 阻断级）**：远程容器多 session + 监督者 pair 端到端——架构透传已支持（§6），但 `IMPL-SERVER.md`/`DESIGN.md` 从未提 supervisor，属未验证，须实测。不过则先补多路复用。
- **R2（DN2 取舍）**：看门狗薄兜底保留与否；放弃则看门狗卡死时总览短暂显示 RUNNING。
- **R3（工具发现性）**：监督者仅凭 schema enum + per-node 注入是否稳定调用新工具；不稳再加 channel 条件提示（§7.2）。
- **R4（tab 手动关闭）**：运行中用户关节点 tab 的语义（MVP 记为已知行为，P5 特判）。
- **R5（摘要裁剪）**：join 节点上下文膨胀；MVP 先全文，P3 裁剪。
- **R6（ceiling 与容器规格）**：远程默认 2，P0 后据容器规格调；v-next 加 `/capabilities` 动态探测。
- **Q（PRD §15）**：节点整体超时兜底——倾向先不加，靠看门狗 + DN2。

---

## 26. MVP 验收口径

1. 编辑器画 `A、B → C`，设并发=2，点 Run。
2. A、B 两 tab 自动出现、各起 pair 并行干活；C 处于 PENDING。
3. A、B 监督者各自 `complete_workflow_node(done)` 后，C 自动起 tab，plan 含 A、B 产出摘要。
4. 任一节点 `blocked` 或看门狗卡死 → 总览 ⚠ + 弹 escalation + 自动切该 tab；人工处理到监督者 emit `done` → 自动推进。
5. 全部 DONE → 工作流 `COMPLETED`，所有 tab 保留可回看。
6. 远程模式（P0 通过后）行为与本地一致；某节点 daemon 崩溃 → 该节点 WAITING_HUMAN，不拖垮整流程。
7. 中止：abort 后所有活动 pair 停、tab 保留、状态 ABORTED。
```
