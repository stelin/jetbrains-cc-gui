# 监督者编排工作流 — UI 详细编码方案

> 状态：**待实施**（2026-06-01）
> 配套：本文件是 [`prd.md`](./prd.md) 的 **UI/前端实现细化**，只覆盖 webview(React) 层 + 它依赖的 JS↔Java 通信协议。编排引擎（`SupervisorWorkflowManager`、调度、startPair、监听器）见 PRD §5/§12，本文不重复。
> 技术约定（已核对现有代码）：
> - 弹窗范式：`backdrop + dialog`，CSS Module(`*.module.less`)，主题色一律用 `var(--vscode-*)`（参照 `ChatInputBox/SupervisorToggle/PickerDialog.tsx` + `style.module.less`）。
> - i18n：`react-i18next` 的 `useTranslation()` + `t('...')`，文案进 `webview/src/i18n/locales/{zh,en,...}.json`。
> - JS→Java：`sendBridgeEvent(event, content)`（`utils/bridge.ts`）。Java→JS：`window.onXxx(json)` 回调。
> - 监督者下拉复用 `types/supervisorAgent.ts` 的 `SupervisorAgent`（已有 `window.updateSupervisorAgents` 推送）。

---

## 1. 范围与交付物

本方案交付三块前端能力 + 一份通信协议契约：

1. **入口按钮**：`ChatHeader` 工具栏 `历史` 与 `设置` 之间新增"工作流"按钮。
2. **管理弹窗**（模态）：编辑 + 启动工作流。三区布局：左工作流列表 / 中 DAG 画布 / 右节点抽屉。
3. **运行态非模态条 + 待人工告警**：启动后弹窗收起，顶部常驻迷你状态条；节点转人工时弹非模态告警。
4. **JS↔Java 协议契约**（§9）：前端发起的 `workflow_*` 事件 + Java 推送的 `window.onWorkflow*` 回调。

> **采纳的 UX 决策**（上轮讨论确认）：
> - 依赖编辑 = 抽屉内**勾选清单**（自动防环）。
> - 节点 plan = **内联 textarea** 为主，附"引用 .md 文件路径"可选项。
> - **模态拆分**：弹窗只管"编辑 + 启动"；点 `运行 ▶` 后弹窗自动收起为**非模态状态条**，监控/处理在各节点 tab + 状态条 + 告警里进行（避免模态锁死操作）。
>
> **2026-06-01 增补**：
> - **画布节点可拖拽**：编辑态下可拖动节点重排，位置存入 `WorkflowNode.posX/posY`（拖过的节点用手动位置，其余仍自动分层布局）。
> - **弹窗不透明修复**：JCEF 环境未注入 `--vscode-editor-background` 等背景变量，导致弹窗透出底层聊天内容。
>
> **2026-06-01 重构（整页化）** —— 取代上面的弹窗形态：
> - **弹窗 → 整页视图**：新增 `ViewMode='workflow'`，`WorkflowView` 与 `SettingsView` 同级（`ChatHeader` 在该视图返回 null），整页自带顶栏 + 左侧栏 + 中画布 + **常驻右检查器**。`WorkflowDialog` 降级为指向 `WorkflowView` 的别名。
> - **依赖编辑改为画布连线**：节点左右各一连接点(port)，从右◯拖到目标节点 = `目标.dependsOn += 源`（带 `forbiddenDeps` 防环）；连线 hover 中点出 `×` 删除；检查器里"依赖于"改为**只读 chips（× 移除）**，去掉勾选清单。
> - **配色中性蓝**：全量改用项目自有主题变量（`--bg-*/--text-*/--border-*/--accent-primary`）+ 硬色值回退；画布加点阵网格背景、节点卡顶部状态色条 + 阴影、活跃连线蓝色流向虚线动画。
> - **运行监控整页点亮**：点 `运行` 后不再收弹窗，直接在整页内按 `WorkflowExecution` 状态给节点上色；离开到对话则靠顶部 `RunStatusBar` 跟踪。

---

## 2. 目录结构（新增）

```
webview/src/
├── components/
│   └── WorkflowOrchestration/
│       ├── index.tsx                 # 对外导出：<WorkflowEntryButton/> + <WorkflowSurface/>
│       ├── WorkflowContext.tsx       # Provider：定义列表 + 运行态 + 回调注册 + actions
│       ├── WorkflowDialog.tsx        # 模态弹窗（三区容器）
│       ├── WorkflowList.tsx          # 左：工作流列表 + 新建
│       ├── DagCanvas.tsx             # 中：DAG 画布（编辑+监控共用）
│       ├── NodeCard.tsx              # 画布上的单个节点卡片
│       ├── DagEdges.tsx              # SVG 连线层
│       ├── NodeDrawer.tsx            # 右：节点编辑/详情抽屉
│       ├── RunStatusBar.tsx          # 非模态运行状态条
│       ├── EscalationToast.tsx       # 待人工非模态告警
│       ├── layout.ts                 # DAG 自动布局算法（纯函数，可单测）
│       ├── types.ts                  # 前端类型（镜像 PRD §4）
│       └── style.module.less
└── types/workflow.ts                 # 跨模块共享类型（可与上面的 types.ts 合并）
```

---

## 3. 入口按钮接线（最小改动）

### 3.1 `ChatHeader.tsx`
新增可选 prop，并在 `历史` 与 `设置` 之间插一个按钮（现状见 `ChatHeader.tsx:163-176`）：

```tsx
// Props 追加
onOpenWorkflow?: () => void;

// header-right：history 之后、settings 之前
<button
  className="icon-button"
  onClick={onOpenWorkflow}
  data-tooltip={t('workflow.entryTooltip')}
>
  <span className="codicon codicon-git-merge" />   {/* 并行→汇聚，贴合 A/B→C */}
</button>
```

> 图标候选：`codicon-git-merge`（推荐）/ `codicon-type-hierarchy-sub`。

### 3.2 `App.tsx`（接线，参照 `App.tsx:688` 现有 `ChatHeader` 调用）

```tsx
const [workflowOpen, setWorkflowOpen] = useState(false);

<ChatHeader
  /* ...现有 props... */
  onOpenWorkflow={() => setWorkflowOpen(true)}
/>

{/* 模态弹窗：仅 chat 视图可开 */}
<WorkflowDialog open={workflowOpen} onClose={() => setWorkflowOpen(false)} />

{/* 非模态运行条 + 告警：始终挂载，由 context 控制是否显示 */}
<RunStatusBar onExpand={() => setWorkflowOpen(true)} />
<EscalationToast />
```

整个 App 外层包一个 `<WorkflowProvider>`（与现有 `PairProvider` 同级）。

---

## 4. 前端类型（`types.ts`，镜像 PRD §4）

```ts
export type NodeStatus = 'PENDING' | 'READY' | 'RUNNING' | 'WAITING_HUMAN' | 'DONE' | 'ABORTED';
export type WorkflowState = 'EDITING' | 'RUNNING' | 'COMPLETED' | 'ABORTED';

export interface WorkflowNode {
  name: string;            // = tab 名，工作流内唯一
  supervisorId: string;    // 复用 SupervisorAgent.id
  plan: string;            // 内联任务文本（与 planPath 二选一）
  planPath?: string;       // 引用 .md 文件（可选）
  model?: string;
  longContext?: boolean;
  reasoning?: string;      // ReasoningEffort 的裸 id
  dependsOn: string[];     // 依赖的节点 name
  posX?: number;           // 画布手动位置（拖拽后），未设则自动布局
  posY?: number;           // 后端只需原样持久化/回传，属纯展示字段
}

export interface WorkflowDefinition {
  id: string;
  name: string;
  nodes: WorkflowNode[];
  updatedAt?: number;
}

export interface NodeRuntime {
  status: NodeStatus;
  pairId?: string | null;
  windowId?: string | null;
  completionReportPath?: string | null;
  escalationReason?: string | null;   // WAITING_HUMAN 时填
  liveOutputTokens?: number;           // RUNNING 时可选展示
}

export interface WorkflowExecution {
  workflowId: string;
  state: WorkflowState;
  nodes: Record<string, NodeRuntime>;  // key = node.name
  concurrency: number;                  // = 2，由 Java 下发，UI 只读展示
}
```

---

## 5. 状态管理：`WorkflowContext`

为什么用 Context 而非弹窗内部 state：**运行状态条/告警在弹窗关闭后仍要存活**，必须提到 App 级。

```tsx
interface WorkflowContextValue {
  // 数据
  definitions: WorkflowDefinition[];
  selectedId: string | null;
  execution: WorkflowExecution | null;     // null = 当前无运行
  agents: SupervisorAgent[];               // 复用现有 window.updateSupervisorAgents
  // 选择/编辑（本地草稿）
  draft: WorkflowDefinition | null;        // 当前编辑中的定义副本
  selectWorkflow(id: string | null): void;
  newWorkflow(): void;
  updateDraft(patch: Partial<WorkflowDefinition>): void;
  upsertNode(node: WorkflowNode, originalName?: string): void;
  removeNode(name: string): void;
  // 持久化/运行（→ Java）
  saveDraft(): void;                       // workflow_save
  deleteWorkflow(id: string): void;        // workflow_delete
  runWorkflow(id: string): void;           // workflow_run
  abortWorkflow(): void;                   // workflow_abort
  jumpToNode(name: string): void;          // workflow_jump_node
  openReport(name: string): void;          // workflow_open_report
  // 派生
  isRunning: boolean;                      // execution?.state === 'RUNNING'
  runningCount: number; doneCount: number; totalCount: number;
  waitingNode: string | null;              // 第一个 WAITING_HUMAN 节点
}
```

**回调注册**（在 `WorkflowProvider` 的 `useEffect` 里，参照 `PairContext.tsx` 的注册/清理范式，保存前值并在卸载时还原）：

```ts
window.onWorkflowDefinitions = (json) => setDefinitions(JSON.parse(json));
window.onWorkflowExecutionUpdate = (json) => setExecution(JSON.parse(json));
window.onWorkflowEscalation = (json) => pushEscalation(JSON.parse(json));      // {nodeName, reason}
window.onWorkflowOperationResult = (json) => handleOpResult(JSON.parse(json)); // {success, error?}
```

挂载时主动拉一次：`sendBridgeEvent('workflow_list')`。

---

## 6. 组件树与职责

```
<WorkflowProvider>
 ├─ <WorkflowDialog open>                         模态，open 时渲染
 │   └─ backdrop > dialog(grid 3 列)
 │       ├─ <WorkflowList/>        左 ~180px
 │       ├─ <DagCanvas/>           中 自适应（编辑+监控共用）
 │       │    ├─ <DagEdges/>        SVG 连线层（绝对定位铺底）
 │       │    └─ <NodeCard/> × N    依 layout.ts 定位
 │       └─ <NodeDrawer/>          右 ~300px，选中节点才渲染
 ├─ <RunStatusBar/>                非模态，isRunning 时显示
 └─ <EscalationToast/>             非模态，有待人工节点时显示
```

### 6.1 `WorkflowDialog`
- 顶栏：工作流名（可编辑）+ 状态徽标 + 右侧动作区（`运行 ▶` / `中止 ■` / `保存` / `×`）。
- `运行 ▶` 点击 → `runWorkflow(selectedId)` → 成功后 `onClose()`（弹窗收起，交给 `RunStatusBar`）。
- 单工作流锁：`isRunning && execution.workflowId !== selectedId` 时 `运行 ▶` 置灰，tooltip = `t('workflow.lockedRunning')`。
- 容器用 CSS grid：`grid-template-columns: 180px 1fr auto;`（抽屉无选中时第三列 0）。

### 6.2 `WorkflowList`
- 列表项：名字 + 节点数 + 运行徽标（若该定义正在跑）。
- 底部 `＋ 新建工作流` → `newWorkflow()`。
- 选中项高亮（复用 `agentRow`/`hover` 风格）。

### 6.3 `DagCanvas`
- 调 `layout(nodes)` 得到每个节点的 `{x,y}`，绝对定位渲染 `NodeCard`。
- `DagEdges` 在底层用一张 `<svg>` 覆盖整画布，按 `dependsOn` 画贝塞尔连线。
- 编辑态/监控态同一套渲染；区别仅是 `NodeCard` 读 `execution?.nodes[name]?.status` 上色。
- 画布可滚动（`overflow:auto`），节点多时不挤压。

### 6.4 `NodeCard`
- 固定尺寸（建议 `150×56`）。展示：节点名、监督者名、状态徽标。
- 监控态附加：RUNNING 显示脉冲点/`liveOutputTokens`；DONE 显示 `✓`；WAITING_HUMAN 显示 `⚠待人工`。
- 单击 → `selectNode(name)`（打开抽屉）；双击（运行态）→ `jumpToNode(name)`。

### 6.5 `NodeDrawer`（核心表单，见 §7）

### 6.6 `RunStatusBar`（非模态，见 §8）

### 6.7 `EscalationToast`（非模态，见 §8）

---

## 7. 节点抽屉表单 `NodeDrawer`

```
名称(=tab名)  [______]        必填、工作流内唯一、≤30、与建 tab 命名规则一致
监督者        <SupervisorAgentSelect/>   复用现有组件/agents 列表
模型          [下拉]          可空 → 用监督者默认
1M 上下文     [开关]   推理 [下拉 ReasoningEffort]
依赖于        勾选清单（其它节点）       自动防环：见下
任务来源      ( ) 内联文本  ( ) 引用文件
  内联：       <textarea 等宽>           plan
  文件：       [路径输入]                planPath
[删除节点]                         [完成]
```

**校验规则**：
- 名称非空、唯一（与其它节点比对）、长度 ≤ 30；空格去重，撞 `AI1/AI2` 由 Java 建 tab 时再兜底去重。
- 监督者必选。
- `plan` 与 `planPath` 至少一项非空。

**依赖防环**（编辑节点 X 的 `dependsOn` 时）：
```ts
// 计算"X 的所有下游可达集合"，这些节点不可被 X 依赖（否则成环），连同 X 自己一并 disable
function forbiddenDeps(x, nodes): Set<string> {
  const down = new Set<string>([x.name]);
  let grow = true;
  while (grow) {
    grow = false;
    for (const n of nodes)
      if (n.dependsOn.some(d => down.has(d)) && !down.has(n.name)) { down.add(n.name); grow = true; }
  }
  return down; // 清单里这些项置灰
}
```

---

## 8. 非模态：运行状态条 + 待人工告警

### 8.1 `RunStatusBar`
- 仅 `isRunning` 时渲染，固定在工具窗顶部（`header` 下方一条）。
- 内容：`{工作流名} · 运行中 · 完成 {done}/{total} · 并发 {concurrency}`，有待人工时追加 `· ⚠{n} 待人工`。
- 右侧：`展开`（→ 重新打开弹窗的只读监控视图）、`中止 ■`（→ `abortWorkflow()` 二次确认）。

### 8.2 `EscalationToast`
- 监听 `window.onWorkflowEscalation`（`{nodeName, reason}`）→ 入一个 toast 队列。
- 非模态卡片：`⚠ 节点「{nodeName}」需要你处理` + `原因摘要` + `[跳转到 tab ▶]`（→ `jumpToNode`）+ `[知道了]`。
- 复用现有 `PairAlert`/`SupervisorAlertToast` 的视觉与堆叠逻辑（`SupervisorPair/SupervisorAlertToast.tsx`），保证与监督者告警风格统一。

> 与 PRD D8 一致：转人工 = 弹窗(此 toast) + 画布标 ⚠ + 自动切 tab（`jumpToNode` 即切 tab）。

---

## 9. JS↔Java 通信协议契约（新增）

> 与现有 `pair_*` / `updateSupervisorAgents` 同构。Java 侧由新增 `WorkflowHandler`（仿 `PairHandler`/`TabHandler`）承接，`SupervisorWorkflowManager` 通过 `callJavaScript(...)` 回推。

### 9.1 前端 → Java（`sendBridgeEvent(event, JSON)`）

| event | payload | 含义 |
|---|---|---|
| `workflow_list` | — | 拉取全部定义 + 当前运行态 |
| `workflow_save` | `WorkflowDefinition` | 新建/更新定义（id 为空=新建） |
| `workflow_delete` | `{ id }` | 删除定义 |
| `workflow_run` | `{ id }` | 启动（Java 校验单工作流锁） |
| `workflow_abort` | `{}` | 中止当前运行 |
| `workflow_jump_node` | `{ nodeName }` | 切到该节点 tab（无 tab 则忽略） |
| `workflow_open_report` | `{ nodeName }` | 打开该节点 `COMPLETION_REPORT.md` |

### 9.2 Java → 前端（`window.onXxx(json)`）

| 回调 | payload | 触发时机 |
|---|---|---|
| `onWorkflowDefinitions` | `WorkflowDefinition[]` | 启动拉取 / 增删改后 |
| `onWorkflowExecutionUpdate` | `WorkflowExecution`（全量快照） | 启动、每次节点状态变化、终态 |
| `onWorkflowEscalation` | `{ nodeName, reason }` | 节点进入 `WAITING_HUMAN` |
| `onWorkflowOperationResult` | `{ success, operation?, error? }` | save/delete/run/abort 的结果 |

> **全量快照优先**：`onWorkflowExecutionUpdate` 每次推完整 `WorkflowExecution`，前端直接 `setExecution(...)` 覆盖，免增量合并的复杂度（节点数量小，开销可忽略）。

### 9.3 `global.d.ts` 追加声明
```ts
interface Window {
  onWorkflowDefinitions?: (json: string) => void;
  onWorkflowExecutionUpdate?: (json: string) => void;
  onWorkflowEscalation?: (json: string) => void;
  onWorkflowOperationResult?: (json: string) => void;
}
```

---

## 10. DAG 自动布局（`layout.ts`，纯函数，可单测）

分层（最长路径）+ 列内堆叠：

```ts
const CARD_W = 150, CARD_H = 56, COL_GAP = 80, ROW_GAP = 24;

export interface Placed { name: string; x: number; y: number; level: number; }

export function layout(nodes: WorkflowNode[]): { placed: Placed[]; width: number; height: number } {
  const byName = new Map(nodes.map(n => [n.name, n]));
  const memo = new Map<string, number>();
  const level = (name: string, seen = new Set<string>()): number => {
    if (memo.has(name)) return memo.get(name)!;
    if (seen.has(name)) return 0;            // 防御：理论上无环（编辑期已防环）
    seen.add(name);
    const deps = byName.get(name)?.dependsOn ?? [];
    const lv = deps.length ? Math.max(...deps.map(d => level(d, seen) + 1)) : 0;
    memo.set(name, lv); return lv;
  };
  const cols = new Map<number, string[]>();
  for (const n of nodes) { const lv = level(n.name); (cols.get(lv) ?? cols.set(lv, []).get(lv)!).push(n.name); }
  const placed: Placed[] = [];
  let maxRows = 0;
  for (const [lv, names] of [...cols.entries()].sort((a, b) => a[0] - b[0])) {
    names.forEach((name, row) => placed.push({
      name, level: lv,
      x: lv * (CARD_W + COL_GAP),
      y: row * (CARD_H + ROW_GAP),
    }));
    maxRows = Math.max(maxRows, names.length);
  }
  const width = (Math.max(...placed.map(p => p.level)) + 1) * (CARD_W + COL_GAP);
  const height = maxRows * (CARD_H + ROW_GAP);
  return { placed, width, height };
}
```

`DagEdges`：对每条 `child.dependsOn = parent`，从 `parent` 右中点 `(px+CARD_W, py+CARD_H/2)` 到 `child` 左中点 `(cx, cy+CARD_H/2)` 画三次贝塞尔：
```
M px,py  C (px+40),py  (cx-40),cy  cx,cy
```
箭头用 `<marker>`。线色 `var(--vscode-panel-border)`；当前活跃前沿（parent=DONE 且 child=READY/RUNNING）可高亮 `var(--vscode-focusBorder)`。

---

## 11. 样式与状态色（`style.module.less`）

沿用 `PickerDialog` 的 `backdrop/dialog/iconButton/primaryButton/secondaryButton` 等类。状态色统一映射 vscode token：

| 状态 | 边框/文字 token | 徽标 |
|---|---|---|
| PENDING | `--vscode-descriptionForeground` | ◷ 灰，"依赖未满足" |
| READY | `--vscode-charts-yellow` | ◷ "排队中" |
| RUNNING | `--vscode-charts-blue` / `--vscode-progressBar-background` | ▶ 脉冲 |
| WAITING_HUMAN | `--vscode-editorWarning-foreground` / `--vscode-charts-orange` | ⚠ "待人工" |
| DONE | `--vscode-testing-iconPassed` / `--vscode-charts-green` | ✓ |
| ABORTED | `--vscode-errorForeground` | ✕ |

弹窗尺寸建议：`width: 900px; max-width: 94vw; height: 600px; max-height: 84vh;`，`dialog` 用 `display:grid`。

---

## 12. i18n keys（`locales/*.json` 追加 `workflow` 命名空间）

```jsonc
"workflow": {
  "entryTooltip": "监督者编排工作流",
  "title": "工作流编排",
  "newWorkflow": "新建工作流",
  "run": "运行", "abort": "中止", "save": "保存", "expand": "展开",
  "lockedRunning": "已有工作流正在运行",
  "addNode": "添加节点",
  "node": { "name": "名称（= 标签页名）", "supervisor": "监督者", "model": "模型",
            "longContext": "1M 上下文", "reasoning": "推理", "dependsOn": "依赖于",
            "planSource": "任务来源", "planInline": "内联文本", "planFile": "引用文件",
            "delete": "删除节点", "done": "完成" },
  "status": { "pending": "依赖未满足", "ready": "排队中", "running": "进行中",
              "waitingHuman": "待人工", "done": "已完成", "aborted": "已中止" },
  "runBar": "{{name}} · 运行中 · 完成 {{done}}/{{total}} · 并发 {{conc}}",
  "escalation": "节点「{{node}}」需要你处理",
  "jumpToTab": "跳转到标签页", "viewReport": "查看完成报告",
  "confirmAbort": "确定中止当前工作流？已运行的标签页会保留。"
}
```
zh/zh-TW/en/… 同步补齐（en 由译文）。

---

## 13. 边界与校验（前端兜底）

- **单工作流锁**：`runWorkflow` 前端先判 `isRunning`，置灰按钮；最终以 Java `onWorkflowOperationResult.success=false` 为准并 toast。
- **空工作流/孤儿环**：保存前校验至少 1 节点、无环（编辑期防环已基本杜绝，保存再兜一次）。
- **名称唯一**：抽屉 `完成` 时校验。
- **运行态只读**：`isRunning` 时抽屉表单只读，仅保留 `跳转/查看报告`。
- **回调清理**：Provider 卸载时还原 `window.onWorkflow*` 前值（防 HMR/重载泄漏，参照 PairContext 范式）。

---

## 14. 前端分步实施清单（对应 PRD §14 的 P5）

| 步 | 内容 | 产出 |
|---|---|---|
| U1 | 脚手架：目录、`types.ts`、`WorkflowContext` 空壳 + 回调注册 + `global.d.ts` 声明 | 能收发 `workflow_list`/`onWorkflowDefinitions` |
| U2 | 入口按钮：`ChatHeader` + `App.tsx` 接线 | 点按钮开空弹窗 |
| U3 | `WorkflowList` + `WorkflowDialog` 顶栏 + 新建/选择/保存/删除 | 定义级 CRUD 跑通 |
| U4 | `NodeDrawer` 表单 + 校验 + 防环 + 复用 `SupervisorAgentSelect` | 能编辑节点与依赖 |
| U5 | `layout.ts` + `DagCanvas` + `NodeCard` + `DagEdges` | 静态 DAG 可视 |
| U6 | 运行：`运行 ▶` → `workflow_run`，`onWorkflowExecutionUpdate` 驱动节点上色 | 监控态可视 |
| U7 | `RunStatusBar`（非模态）+ 收起逻辑 + 中止 | 运行期不锁操作 |
| U8 | `EscalationToast` + `jumpToNode` + `openReport` | 待人工闭环 |
| U9 | i18n 全语言补齐 + 样式打磨 + 主题适配 | 收尾 |
| U10 | 单测：`layout.ts`、`forbiddenDeps`；交互冒烟 | 质量门 |

> Java 侧 `WorkflowHandler` + `SupervisorWorkflowManager` 的协议端实现是 U1/U6 的前置依赖，按 PRD §5/§14 的 P1–P4 推进；前端可先用 mock 的 `window.onWorkflow*` 自测 U1–U5。

---

## 15. 待确认

- **图标**：`codicon-git-merge`（推荐）vs `codicon-type-hierarchy-sub`。
- **状态条位置**：工具窗顶部独立一条 vs 复用 `header` 右侧塞一个胶囊。本方案默认独立一条。
- 其余 UX 决策（依赖勾选 / plan 内联+文件 / 模态拆分）已在 §1 采纳，如需调整在此圈出。
