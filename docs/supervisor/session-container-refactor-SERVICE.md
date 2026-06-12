# 会话种类重构 — 服务侧（Java）编码方案

> 配套文档：`session-container-refactor-UI.md`。两份共享里程碑 **M1–M6**，同一 M = 服务某部分 + UI 某部分一起验收。
>
> 目标（已与用户对齐，见记忆 `supervisor-session-kind-refactor`）：
> - supervisor 从「运行期 toggle 焊接」改为「新建时定型的会话种类」。
> - 新造持久 `containerId` 做身份；`windowId` 退回纯视图。
> - 新立 `SessionRegistry` 独占 `~/.codemoss/sessions`，做三处存储的总账（join 表）。
> - normal 不上 container（零迁移）；supervised / workflow 才有 container。
> - **clean cut**：换新目录 `~/.codemoss/sessions`，旧 `~/.codemoss/pairs` 直接废弃，不做 backfill。`~/.claude/projects/*.jsonl`（用户真实对话）一条不动。
> - workflow 容器稳定单 ID（`containerId == wfId`），子节点 = `kind=supervised` 容器 + `parentContainerId`，对 supervised 历史 tab 隐身。

---

## 0. 现状关节（重构要拆/要改的点）

| 关节 | 现状 file:line | 重构后 |
|---|---|---|
| Pair 路由主键 | `PairSessionManager.mainSessionToPair`(:61) + `findByMainSession`(:773) | 删除，改 `byContainer`（containerId→PairSession）|
| 懒绑定胶水 | `ClaudeMessageHandler.lateBindSessionIdToPair`(:542) + `findAttachedPair` 用 startedAt reduce(:1271) | 删除；mainSessionId 退为 manifest best-effort 回填 |
| 协调态存储 | `L2Store.defaultBaseDir`→`~/.codemoss/pairs`(:71)、`pairDir(pairId)`(:202) | 改 `~/.codemoss/sessions/<projectHash>/<containerId>/l2/` |
| Pair 创建 | `PairHandler.handleStart`(pair_start, :566)→`startPairWired`(:658)→`PairSessionManager.startPair`(:221) | 新增 `session_create_supervised`，创建时先写 manifest 再 startPair |
| 历史读取 | `HistoryLoadService.handleLoadHistoryData`(:49) 扫 `.claude/projects` | 三类：normal（扫 + 排除 claimed）/ supervised / workflow（读 manifest）|
| 项目 hash | `WorkflowStore.projectHash`(:77, SHA-256 前 16 hex) | 抽到共享 util，SessionRegistry 复用 |
| IPC 路由 | `ClaudeChatWindow.handleJavaScriptMessage`(:505) `"type:content"` → `MessageDispatcher.dispatch`(:27) | 信封不变，pair_* 的 content JSON 多带 `containerId` 字段 |
| tab 身份 | `SessionState.windowId`(:62, volatile per-tab UUID) | 新增 `SessionState.containerId`（supervised/workflow 才非空）|

---

## S1 — 基础设施：SessionRegistry + manifest（里程碑 M1，纯服务、无行为变更）

**新增文件**（包 `com.github.claudecodegui.session.registry`）：

### `SessionKind.java`
```java
public enum SessionKind { SUPERVISED, WORKFLOW }
// normal 永不进 registry，故不在枚举内（保持 normal 零侵入）
```

### `SessionManifest.java`（Gson POJO）
```java
public final class SessionManifest {
    public String containerId;            // PK = UUID
    public SessionKind kind;
    public String title;
    public String projectHash;
    public long   createdAt;
    public long   lastActiveAt;
    public String status;                 // "active" | "closed"

    // —— supervised 腿（workflow 子节点也用这组）——
    public String mainSessionId;          // 当前指针（最新一跑）
    public List<String> mainSessionIds;   // 历来全集 → normal tab 排除用（关键！）
    public String pairId;                  // 内部实现 id，不再当目录键
    public String supervisorSessionId;
    public String agentId;
    public Integer supervisorGeneration;

    // —— workflow ——
    public String parentContainerId;      // 非空 = workflow 子节点 → supervised tab 隐身
    public String workflowId;             // kind=WORKFLOW 时 == containerId == wfId
    public List<String> childContainerIds;
}
```

### `CodemossPaths.java`（共享 util；把 `WorkflowStore.projectHash` 抽出来复用）
```java
public static String projectHash(@Nullable String basePath)   // 移植 WorkflowStore:77 实现
public static Path sessionsRoot()                              // ~/.codemoss/sessions
public static Path containerDir(String projectHash, String containerId)
```
> `WorkflowStore.projectHash`(:77) 改为委托 `CodemossPaths.projectHash`，行为不变。

### `SessionRegistry.java`（`@Service(Service.Level.PROJECT)`，独占读写）
```java
@Service(Service.Level.PROJECT)
public final class SessionRegistry {
    public static SessionRegistry getInstance(Project project);

    // 创建：原子写盘(.tmp→ATOMIC_MOVE，照搬 WorkflowStore.atomicWriteString:245)，先于任何 daemon 调用
    public String register(SessionKind kind, @Nullable String parentContainerId,
                           String title, @Nullable String agentId);   // 返回 containerId(UUID)

    public SessionManifest get(String containerId);
    public void bindMainSession(String containerId, String mainSessionId);      // 设指针 + append 到 mainSessionIds[]（去重）
    public void setSupervisorSession(String containerId, String sid, Integer generation);
    public void setPairId(String containerId, String pairId);
    public void updateTitle(String containerId, String title);
    public void touch(String containerId);                                       // lastActiveAt = now
    public void close(String containerId);                                       // status=closed
    public void delete(String containerId);                                      // rm -rf containerDir

    // —— 历史三 tab 数据源 ——
    public List<SessionManifest> listByKind(SessionKind kind);                   // 当前 project；SUPERVISED 自动剔除 parentContainerId!=null
    public List<SessionManifest> listChildren(String parentContainerId);
    public Set<String> claimedMainSessionIds();                                  // ∪ 所有 manifest.mainSessionIds[]（含历史全集）→ normal tab 排除

    // —— 路径 ——
    public Path containerDir(String containerId);                                // ~/.codemoss/sessions/<projectHash>/<containerId>
    public Path l2Dir(String containerId);                                       // containerDir/l2
}
```
**实现要点**：内存缓存 `ConcurrentHashMap<String,SessionManifest>` + 启动时 lazy 扫描本 project 子目录；所有写走原子写 + 更新缓存；目录按 `<projectHash>/<containerId>` 分层，故 `listByKind`/`claimedMainSessionIds` 只扫本 project 子目录，便宜。

**验收 M1**：纯白盒单测 `SessionRegistryTest`（用临时目录注入 root）：register→get round-trip、bindMainSession 去重 append、listByKind 剔除 parent、claimedMainSessionIds 取全集、delete 清目录。**不接线任何现有流程**，零行为变更。

---

## S2 — supervised 出生即定型（M2，服务）

**改 `SessionState.java`**：新增
```java
private volatile String containerId;          // supervised/workflow 才非空；normal 恒 null
public String getContainerId();  public void setContainerId(String id);
```

**改 `PairSession.java`**（构造 :184）：新增 `final String containerId` 字段 + `getContainerId()`，构造函数追加该参数（末尾，保持其余顺序）。

**改 `PairSessionManager.java`**：
```java
private final ConcurrentHashMap<String, PairSession> byContainer = new ConcurrentHashMap<>();  // 新主键
// startPair(:221) 末尾：byContainer.put(params.containerId, session)；保留 pairs（pairId→session）供 rotation/内部用
public PairSession getByContainer(String containerId) { return byContainer.get(containerId); }
```
`StartPairParams`(:113) 新增 `public final String containerId;`（加一个带 containerId 的构造，旧构造暂留）。

**改 `PairHandler.java`**：新增 IPC `session_create_supervised`（content JSON: `{title?, agentId, model?, longContextEnabled?, reasoningEffort?}`）：
```
1. containerId = SessionRegistry.register(SUPERVISED, null, title, agentId);   // 先写 manifest
2. SessionState.setContainerId(containerId);                                    // 盖到本 tab
3. params = StartPairParams(containerId, agentId, ...);                          // mainSessionId 仍可为 null
4. startPairWired(params, budget);  SessionRegistry.setPairId(containerId, pair.getPairId());
5. 回推 webview：window.onSessionCreated({containerId, kind:'supervised', agentId, ...})
```
> `pair_start`(:566) 在 M2/M3 过渡期保留可用（内部补一个 containerId）；M3 删 SupervisorToggle 后此入口废弃。

**验收 M2**：新建监督者会话能起 pair，`~/.codemoss/sessions/<hash>/<cid>/manifest.json` 立即生成（首条消息前就有），`byContainer.get(cid)` 命中。

---

## S3 — 路由反转 + 删胶水（M3，服务）

**改 `PairHandler` 所有 `pair_*` handler**：参数 JSON 改用 `containerId` 定位 PairSession（`mgr.getByContainer(containerId)`），不再用 pairId 反查。过渡期 content 同时带 `pairId`+`containerId`，优先 containerId。涉及：`handleStop/handleHumanResponse/handleUserInput/handleSetModel/handleSetReasoning/handleResume/handleSupervisorInterrupt/handleWebviewReady/...`。

**改 `ClaudeMessageHandler.java`**：
```java
// handleSessionId(:525)：保留 state.setSessionId；新增：
String cid = state.getContainerId();
if (cid != null) SessionRegistry.getInstance(project).bindMainSession(cid, content);  // best-effort 回填，off 关键路径
// —— 删除 —— lateBindSessionIdToPair(:542) 整个方法
// —— 删除 —— findAttachedPair(:1271) 里的 startedAt reduce 回退；若仍需 attached pair，改 mgr.getByContainer(state.getContainerId())
```

**改 `PairSessionManager`**：删除 `mainSessionToPair`(:61)、`findByMainSession`(:773)、`bindMainSessionIdToPair`(:762) 对 map 的写（该方法若有别处调用，改成转发 `SessionRegistry.bindMainSession`）。`getActivePairsOwnedBy(windowId)` 可保留供 cockpit 视图用，但不再做身份回退。

**改 `L2Store.java`**：根从 `~/.codemoss/pairs` 改 `SessionRegistry.l2Dir(containerId)`。最小改法：`pairDir(String id)` 的 `id` 语义从 pairId 改为 containerId，root 改 `~/.codemoss/sessions/<projectHash>`；所有 `l2Store.read/update(pairId,...)` 调用点改传 `containerId`（PairSession 已有 getContainerId）。rotation 模块（`RotationCoordinator`/`L2Store` 调用）同步改传 containerId。

**验收 M3**：grep 确认 `mainSessionToPair`/`findByMainSession`/`lateBindSessionIdToPair`/startedAt-reduce 全部消失；supervised 会话发消息、切模型、中断、webview_ready 重放全部经 containerId 命中；多 tab 并发不串话。

---

## S4 — 三类历史读取（M4，服务）

**改 `HistoryHandler.java`** 新增事件：
- `load_supervised_history` → `SupervisedHistoryService.list()`
- `load_workflow_history` → `WorkflowHistoryService.list()`
- （normal 仍走 `load_history_data`）

**新增 `SupervisedHistoryService`**：`SessionRegistry.listByKind(SUPERVISED)` → 映射为前端 summary JSON（复用现有 history summary 形状 + 加 `containerId`、`kind`、`agentId`）。

**新增 `WorkflowHistoryService`**：`SessionRegistry.listByKind(WORKFLOW)` → summary（带 `workflowId`、子节点数）。

**改 `HistoryLoadService.handleLoadHistoryData`(:49)**：在 session 列表组装完成、**回推 webview 之前**（source-agnostic 的统一点）过滤：
```java
Set<String> claimed = SessionRegistry.getInstance(project).claimedMainSessionIds();
sessions.removeIf(s -> claimed.contains(s.sessionId));   // 排除被 supervised/workflow 认领的主腿 jsonl
```
> 这步是「normal tab 不泄漏」的唯一防线，务必用 `mainSessionIds[]` 全集而非当前指针。
> **远程模式必读**：normal 历史在远程模式走 `fetchRemoteProjectData`(:258) 从 server 拉**全量**，过滤点必须放在本地/远程两条路汇合之后（解析完 body、translateHistoryPaths 之后），否则远程模式下 supervised 主腿会泄漏进 normal tab。`sessionId` 是 UUID、不受 path translate 影响，与本地 claimed 集同 id 空间，过滤直接成立。

返回 JSON 沿用现有结构（`{success, sessions[], favorites, currentProject, total, sessionCount}`，见 `HistoryLoadService:110`），supervised/workflow 多带 `containerId/kind`。

**验收 M4**：建几个 supervised/workflow 会话后，normal tab 不再出现它们的主腿；supervised tab 列出它们、不含 workflow 子节点；workflow tab 列出工作流。

---

## S5 — 从 manifest 恢复（M5，服务）

**改 `HistoryHandler` 的 `load_session`**：content 改收 `{containerId?, sessionId?, kind?}`：
- 无 containerId（normal）→ 走现有 `HistoryMessageInjector.handleLoadSession`(:37) 不变。
- 有 containerId（supervised）→ 新 `SupervisedRestoreService.restore(containerId)`：
  ```
  m = registry.get(containerId)
  state.setContainerId(containerId)
  win.resumeMainSession(m.mainSessionId)                       // 主腿（复用 IdeNodeLauncher:SR10 同款 resume）
  params = StartPairParams(containerId, m.agentId, resumeSupervisorSessionId=m.supervisorSessionId)
  startPairWired(params)                                       // 监督腿 resume（L2 自动从 l2Dir 重建）
  pair.setPendingHistoryReplaySessionId(m.supervisorSessionId) // webview_ready 时重放(:55 既有机制)
  ```
- 有 containerId（workflow）→ `SupervisorWorkflowManager.resumeWorkflow(m.workflowId)`（既有 :1500），但入口改为按 containerId。

**改 `PairSession.startPair`/构造**：把 `resumeSupervisorSessionId` 贯通（`StartPairParams` 已有该字段 :179，确认 supervised 普通路径也能用，不止 workflow）。

**验收 M5**：点 supervised 历史条目，主腿对话 + 监督腿对话 + plan/协调态全部回来；断点恢复的 workflow 能续跑（对照记忆 `supervisor-history-restore` 的脆弱点已消除）。

---

## S6 — workflow 收进 container（M6，服务）

**改 `NodeRuntime.java`**：新增 `public String containerId;`（子节点容器 id；与 pairId 区分，pairId 仍是内部活跃对 id）。

**改 `SupervisorWorkflowManager.startWorkflow`(:298)**：
```
// 工作流容器：稳定单 ID == wfId
if (registry.get(def.id) == null)
    registry.register(WORKFLOW, null, def.name, null) 时令 containerId = def.id  // 需 register 支持指定 id（见下）
// 每个节点子容器（按 node.name 锚定，稳定）：
for node: if (rt.containerId == null) rt.containerId = registry.register(SUPERVISED, def.id, node.name, node.supervisorId);
```
> `SessionRegistry.register` 增一个重载 `register(kind, parent, title, agentId, @Nullable String fixedId)`，workflow 用 `fixedId=wfId` 保证 `containerId==wfId` 稳定。

**改 `IdeNodeLauncher.startNodePair`(:146)**：`StartPairParams` 传 `node 的 rt.containerId`；pair 起来后 `registry.setPairId(rt.containerId, pairId)`。

**改 `onSupervisorSessionCaptured`(:1306) / `onMainSessionCaptured`(:1358)**：除写 `NodeRuntime`（既有），同时
```java
registry.setSupervisorSession(rt.containerId, sid, rt.supervisorGeneration);
registry.bindMainSession(rt.containerId, mainSessionId);   // append 全集 → reset-in-place 重跑不泄漏
```
> reset-in-place 重跑：节点拿新 main/supervisor session 时指针更新、旧 id 仍在 `mainSessionIds[]` 全集里被认领。plan.md / l2 单文件覆盖只留最新（用户已确认）。

`WorkflowStore`（`~/.codemoss/workflows/<projectHash>/<wfId>/` definition+execution）**保持不变**——定义/执行状态仍归 WorkflowStore，container manifest 只作历史总账 + 认领集来源，二者用 `containerId==wfId` 对齐。

**验收 M6**：跑一个 workflow，`sessions/<hash>/<wfId>/manifest.json`(kind=workflow) + N 个子 manifest(parentContainerId=wfId) 生成；workflow tab 列出它、supervised tab 不混入子节点；重跑后 normal tab 不泄漏旧节点主腿。

---

## 改动文件清单（服务侧）

**新增**：`session/registry/{SessionKind,SessionManifest,SessionRegistry}.java`、`util/CodemossPaths.java`、`handler/history/{SupervisedHistoryService,WorkflowHistoryService,SupervisedRestoreService}.java`、测试 `SessionRegistryTest`。
**改**：`SessionState`、`PairSession`、`PairSessionManager`、`PairHandler`、`ClaudeMessageHandler`、`L2Store`、`WorkflowStore`(projectHash 委托)、`HistoryHandler`、`HistoryLoadService`、`NodeRuntime`、`SupervisorWorkflowManager`、`IdeNodeLauncher`、rotation 模块（L2 调用改 containerId）。
**删**：`PairSessionManager.mainSessionToPair/findByMainSession`、`ClaudeMessageHandler.lateBindSessionIdToPair` + findAttachedPair 的 startedAt 回退；M3 后 `pair_start` 入口、SupervisorToggle 对应后端分支。

## 里程碑依赖
S1→S2→S3 必须顺序；S4 依赖 S1；S5 依赖 S2+S4；S6 依赖 S2+S5。建议顺序 **S1, S2, S3, S4, S5, S6**。

---

## 远程模式（ai-bridge-server）影响评估

**结论：ai-bridge-server 零改动。** 已核对 `ai-bridge-server`（`server.js`/`session-manager.js`/`history-server.js`）：它是「Claude SDK daemon + 只读历史文件服务 + 代码工作区」薄层，只认 `projectPath`+`sessionId`，历史端点返回裸 `sessionId/title/messageCount`（无任何 type/mode/kind 字段），**对 pair/supervisor/workflow 完全无知**，且不碰 `~/.codemoss/*`。

本重构引入的 container/manifest/SessionRegistry **整层落在插件本地**（与今天 `~/.codemoss/pairs` L2 状态一样在用户机器，非 server）：
- S1–S3、S5、S6：纯本地索引/路由，server 不参与。远程模式下 supervisor 仍工作（supervisor 自己的 daemon 走 RemoteBridge 跑在 server，协调态在插件本地）。
- S4：supervised/workflow 历史来自插件本地 manifest（永远本地，与模式无关）；**normal 历史**远程模式从 `fetchRemoteProjectData` 拉全量，故排除 filter 必须在本地/远程汇合点统一施加（见 S4 远程模式必读）。
- S5 恢复：远程模式下主腿/监督腿 resume 仍由插件编排、daemon 在 server，复用现有 `/history/session?project=&sessionId=` 与 RemoteBridge resume，无新端点。

**已知属性（非回归）**：manifest 跟 L2 一样 per-IDE-机器本地，换机/多客户端不共享——与今天 `~/.codemoss/pairs` 行为一致，不引入新差异。

**可选的未来 server 优化（本次不做）**：若想让 server 端按种类过滤历史（减少全量拉取），需给 `/history/*` 加 `type` 查询参数 + server 解析 jsonl 标记——属增强，非本重构必需。
