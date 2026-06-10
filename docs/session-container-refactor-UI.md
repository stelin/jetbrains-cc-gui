# 会话种类重构 — UI 侧（webview / React+TS）编码方案

> 配套文档：`session-container-refactor-SERVICE.md`。两份共享里程碑 **M1–M6**。UI 的某些 part 依赖服务侧同 M 的 part（标注在每节）。
>
> UI 目标：
> - 「新建」分三个独立入口：普通会话 / 监督者会话 / 工作流（用户决策 4：workflow 是第三个顶级入口）。
> - 「历史」分三个独立 tab，物理隔离（用户决策 2）。
> - 新建监督者会话 = 建完即定型，主 AI + supervisor 一体；**删 SupervisorToggle**，agent 选择器搬到「新建监督者会话」对话框；**删 SupervisorPane 关闭按钮**（轮换保留）。
> - 每个 supervised/workflow tab 自创建/加载起持有 `containerId`，盖到每条 pair_* IPC（normal tab 不盖，照旧）。

---

## 0. 现状关节（UI）

| 关节 | 现状 file:line | 重构后 |
|---|---|---|
| 顶层视图 | `useSessionManagement.ts` `ViewMode='chat'\|'history'\|'settings'\|'workflow'`；`App.tsx` 按 currentView 切换(:685) | 历史拆三类；新建拆三类入口 |
| 新建入口 | `ChatHeader` 单个「+」`onNewSession`→`create_new_session`(:156)；`onNewTab`→`create_new_tab`(:159) | 「+」改菜单：普通/监督者/工作流 |
| supervisor 开关 | `SupervisorToggle/index.tsx`（toggle + 关闭 icon :97-107，`pair_stop`）；`PickerDialog.tsx` 选 agent | **整删**；picker 搬到新建监督者对话框 |
| pair 状态 | `PairContext.tsx` `selected/pairId/messagesByAgentId`，`startSupervisorPair`→`pair_start`(:541)，`onPairStarted`(:897)/`onPairStopped`(:938) | 以 `containerId` 为会话键；新建即建 pair |
| supervisor 面板 | `SupervisorPane.tsx`（中断按钮 :81 + 关闭按钮 setSelected([])） | 删关闭按钮；面板随 supervised 会话恒显 |
| 历史列表 | `HistoryView.tsx`（props historyData/onLoadSession...），`useHistoryLoader.ts`→`load_history_data`(:23) | 三 tab：normal/supervised/workflow |
| 历史类型 | `types/index.ts` `HistorySessionSummary`(:59) | 加 `containerId?/kind?/agentId?` |
| 会话切换 | `loadHistorySession`(:189)→`load_session`(sessionId) | supervised/workflow 改传 `{containerId,kind}` |
| IPC | `bridge.ts` `sendBridgeEvent(event,content)`→`"event:content"`、`sendToJava(msg,payload)` JSON | 不变；pair_* payload 多带 containerId |

---

## U1 — 新建入口拆三类 + 监督者创建对话框（M2，依赖服务 S2）

**改 `ChatHeader.tsx`**：把单个「+」`onNewSession`(:154) 换成下拉菜单（或三个按钮），三项：
- 新建普通会话 → 现有 `createNewSession()`（不变）
- 新建监督者会话 → 打开新组件 `NewSupervisedDialog`
- 新建工作流 → `setCurrentView('workflow')` + `newWorkflow()`（复用现有）

**新增 `components/SupervisorPair/NewSupervisedDialog.tsx`**（把 `PickerDialog.tsx` 的 agent 选择逻辑搬过来）：
- agent 列表来源不变：`window.updateSupervisorAgents` 回调 + `sendToJava('get_supervisor_agents:')`（原 `SupervisorToggle/index.tsx:50-69` 逻辑迁入）。
- 确认时发 **新 IPC**：
  ```ts
  sendToJava(`session_create_supervised:${JSON.stringify({
    title, agentId, model?, longContextEnabled, reasoningEffort
  })}`);
  ```
  （payload 字段沿用 `startSupervisorPair` 里 `pair_start` 的解析逻辑 `PairContext.tsx:535`，把 1M 后缀/reasoning tier 解析整段搬来。）

**新增回调 `window.onSessionCreated`**（在 `registerCallbacks` 链注册）：`{containerId, kind, agentId, ...}` → `beginSessionTransition`-style 切到新会话、记下 `containerId`（见 U2 的 containerId 持有）。

**验收 M2(UI)**：点「新建监督者会话」→ 选 agent → 确认 → 新 tab 出现且 supervisor 面板已在（无需再 toggle）。

---

## U2 — containerId 贯通 IPC + PairContext 以 containerId 为键（M3，依赖服务 S3）

**持有 containerId**：在 `PairContext.tsx` 新增 state `containerId: string | null`，由 `onSessionCreated` / `onPairStarted` / `onPairResume` 写入（这些回调 payload 加 `containerId` 字段；后端 S2/S3 已带）。

**盖到每条 pair_* IPC**：现有发送点（`PairContext.tsx`）payload 统一加 `containerId`：
- `pair_send_user_input`(:722)、`pair_set_model`(:475)、`pair_set_reasoning`(:487)、`pair_human_response`(:704)、`pair_supervisor_interrupt`(SupervisorPane:81)、`pair_webview_ready`(:1500)。
- 过渡期 `pairId` 与 `containerId` 同时带，后端优先 containerId（对齐服务 S3）。

**消息分组键**：`messagesByAgentId` 等保持按 agentId（一个 supervised 会话一个 coordinator agent，仍唯一）；containerId 用于 IPC 寻址，不替换 UI 内分组。workflow 子节点各自一个 tab/containerId，天然隔离。

**验收 M3(UI)**：多个 supervised tab 并存互不串话；切模型/中断/重放均生效。

---

## U3 — 删 SupervisorToggle + 删 SupervisorPane 关闭按钮（M3，依赖服务 S3）

**删除** `components/ChatInputBox/SupervisorToggle/`（`index.tsx` + `PickerDialog.tsx` + `style.module.less`）；移除 `ChatInputBox` 里对它的引用。agent 选择能力已搬到 U1 的 `NewSupervisedDialog`。

**改 `SupervisorPane.tsx`**：
- 删头部「关闭」按钮（原 `setSelected([])` 那个，:99-114 区域内）；**保留中断按钮**（`pair_supervisor_interrupt`, :81）。
- 面板显示条件从 `selected.length > 0`(:51) 改为「本 tab 是 supervised 会话」（即 `containerId != null && kind==='supervised'`）——supervised 会话恒显面板，不可手动关。
- 轮换（rotation）UI 不动（服务端自动，`onPairStatusUpdate` 的 rotation 计数照常显示）。

> 用户决策 3「关闭按钮 vs 轮换」后续单议——本 part 先删「禁用到 0」的关闭，轮换保留；若后续要手动换 agent，再加「换监督者」入口（不影响本方案）。

**验收 M3(UI)**：ChatInputBox 不再有 Supervisor toggle；supervised 会话面板常驻无关闭；普通会话无面板。

---

## U4 — 历史拆三个独立 tab（M4，依赖服务 S4）

**改 `useSessionManagement.ts`** `ViewMode`(:6) 增加：`'history' | 'history_supervised' | 'history_workflow'`（或保留单 `'history'` + 子 tab 状态，推荐后者更省改动）。推荐做法：`HistoryView` 内部加一个 `historyKind: 'normal'|'supervised'|'workflow'` 子 tab 切换。

**改 `useHistoryLoader.ts`**：按当前 historyKind 发不同事件：
- normal → `load_history_data`（不变）
- supervised → `load_supervised_history`
- workflow → `load_workflow_history`

**改回调**：新增 `window.onSupervisedHistory(json)` / `window.onWorkflowHistory(json)`（或复用 `setHistoryData` 带 `kind` 字段，推荐复用 + kind 分流到对应列表 state）。

**改 `HistoryView.tsx`**：
- 顶部加三 tab 切换条；每类一份列表 state。
- 列表项渲染复用现有 `renderHistoryItem`(:382)；supervised/workflow 项的徽章从 provider 改显 `kind`/`agentId`/子节点数。
- workflow 项点击展开可显子节点（来自 `listChildren`，可后置）。

**改 `types/index.ts` `HistorySessionSummary`(:59)**：加
```ts
containerId?: string;
kind?: 'normal' | 'supervised' | 'workflow';
agentId?: string;
childCount?: number;       // workflow
```

**新建入口对齐**：U1 的三入口与这里三 tab 一一对应，视觉上成对（新建/历史各三类，物理隔离）。

**验收 M4(UI)**：三 tab 各列各的；normal tab 不含 supervised/workflow 主腿；workflow 子节点不出现在 supervised tab。

---

## U5 — 三类会话的恢复（M5，依赖服务 S5）

**改 `loadHistorySession`(:189)** 与历史项点击：
```ts
// normal（无 containerId）：现状不变
sendBridgeEvent('load_session', sessionId);
// supervised / workflow：
sendToJava('load_session', { containerId, kind });   // 后端按 kind 走 restore
setCurrentView('chat');               // supervised 进 chat 视图
beginSessionTransition(containerId, title);
```
- supervised 恢复后：`onPairResume`(:1300) 带 `containerId` 触发面板回填（既有「不清消息」语义，:1311 复用）。
- workflow 恢复：点 workflow 历史项 → 后端 `resumeWorkflow` → `setCurrentView('workflow')` + `onWorkflowExecutionUpdate` 回填驾驶舱。

**验收 M5(UI)**：点 supervised 历史 → 主腿对话 + supervisor 面板都回来；点 workflow 历史 → 驾驶舱恢复可续跑。

---

## U6 — 工作流作为第三顶级入口（M6，依赖服务 S6）

工作流 UI（`WorkflowOrchestration/*`）**基本复用**，本 part 只做接线：
- U1 的「新建工作流」入口 → `setCurrentView('workflow')` + `WorkflowContext.newWorkflow()`（既有 :57）。
- `runWorkflow`(:74→`workflow_run`)、`resumeWorkflow`(:76)、execution 回填（`onWorkflowExecutionUpdate` :347）全部不变；容器登记在服务侧 S6 完成，UI 无感。
- workflow 历史 tab（U4）列工作流容器；点击恢复（U5）。
- 可选：节点卡片 `NodeCard` 显示该节点的 `containerId` 便于排查（非必须）。

**验收 M6(UI)**：从新建工作流入口建/跑/停/恢复全链路通；workflow 历史 tab 可见、可恢复。

---

## 改动文件清单（UI 侧）

**新增**：`components/SupervisorPair/NewSupervisedDialog.tsx`、（可选）历史子 tab 组件。
**改**：`ChatHeader.tsx`、`PairContext.tsx`（containerId state + IPC 加字段 + onSessionCreated/onPairStarted/onPairResume 接 containerId）、`SupervisorPane.tsx`（删关闭按钮、显示条件改 containerId）、`useSessionManagement.ts`（ViewMode/loadHistorySession）、`useHistoryLoader.ts`（按 kind 发事件）、`HistoryView.tsx`（三 tab）、`types/index.ts`（HistorySessionSummary 加字段）、`registerCallbacks`/`global.d.ts`（新回调声明）。
**删**：`components/ChatInputBox/SupervisorToggle/`（整目录）及其引用。

## 里程碑依赖
U1↔S2、U2/U3↔S3、U4↔S4、U5↔S5、U6↔S6。**强约束**：UI 每个 part 上线前对应服务 part 必须先就绪（containerId/新 IPC/新回调）。建议节奏：先服务 S1，再 (S2+U1)、(S3+U2+U3)、(S4+U4)、(S5+U5)、(S6+U6)。
