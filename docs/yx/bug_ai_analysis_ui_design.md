# 缺陷「AI 分析」详细编码方案 — 第 1 部分 / UI（webview）

> 项目: `jetbrains-cc-gui`（webview React/TS）
> 日期: 2026-06-12
> 配套文档: [`bug_ai_analysis_service_design.md`](./bug_ai_analysis_service_design.md)（Java 服务 + daemon 复用）
> 状态: 设计待评审 → 通过后编码

---

## 0. 背景与目标

在「我的缺陷」页，批量模式下新增【🔬AI分析】按钮：选中一批 bug → 跑一个**隔离的** Claude 会话（本地/远程模式都支持）→ 产出每个 bug 是否**明确/不明确** + 按 **功能/同页面/同接口** 关联分组 → 展示。

### 已锁定决策（前序讨论）

| # | 决策 |
|---|---|
| 路线 | **Approach A**：复用 `claude.send`，**零 daemon 改动**；本地/远程自动双支持 |
| 判定依据 | 文本优先（标题+描述+评论），信息不足才让模型 `Read` 截图 |
| 模型/思考 | **继承当前会话配置**（`selectedModel` + `reasoningEffort`），弹窗 header 回显 |
| 呈现 | **不弹窗**：缺陷页内 **Tab 切换** —「缺陷列表」/「AI分析」 |
| 结果布局 | **单页上下两段滚动**：① 明确性清单 ② 关联分组 |
| 分组模型 | **M1**：一个 bug 归一个主组，卡片带维度标签（📄同页面/🔌同接口/🧩同功能） |
| 隔离 | 分析是独立 `sessionId`+epoch 的 scratch 会话，**绝不写入正常对话** |
| 状态存活 | **App 级**（`useBugAnalysis`），切走缺陷页也不丢、完成必落盘 |
| 持久化 | **按 `projectId` 分键** localStorage：切项目即清（显示）+ 每项目各留最近一次 |
| 不明确的 bug | 仅列「缺失项」，无额外按钮 |
| 派单入口 | 组内【批量建监督者】【批量建会话】+ 底部跨组勾选批量；默认全不选 |
| 进度 | 逐 bug `✓/⟳/◌`，解析输出流工具事件（best-effort，有兜底） |

---

## 1. 总览（组件树与状态归属）

```
App.tsx
 ├─ useModelProviderState()         → selectedModel / reasoningEffort（已有）
 ├─ useBugAnalysis()  ★新           → 分析状态机 + window 回调 + 持久化（App 级，跨视图存活）
 │    registers: window.onBugAnalysisProgress / window.onBugAnalysisResult
 └─ currentView==='bug-list' →
      <BugListView
         analysis={useBugAnalysis 返回值}      ★新 prop
         model={selectedModel} reasoning={reasoningEffort}  ★新 prop
         ... 现有 props />
       ├─ useYunxiaoBugs()（已有，持有 selectedProjectId/bugs）
       ├─ Tab 栏: [缺陷列表] [AI分析]   ★新
       ├─ 「缺陷列表」面板 = 现有全部 UI（项目下拉/筛选/批量/列表/建监督者/建会话/详情）
       │     批量工具栏 +【🔬AI分析】按钮 ★新
       └─ 「AI分析」面板 = <BugAnalysisPanel analysis=… onGroupDispatch=… />  ★新
```

**关键**：`useBugAnalysis` 挂在 **App 级**（不是 BugListView 内），因为：
- `window.onBugAnalysisProgress/Result` 回调要在 BugListView 卸载（切到聊天视图）后仍然存活，否则分析完成时结果**没人接、落不了盘**。
- BugListView 只是**消费** `analysis`（读状态、调 `analysis.start/cancel/reAnalyze`），并通过 effect 把当前 `selectedProjectId` 同步给 hook（用于持久化分键）。

---

## 2. 缺陷页 Tab 重构（`BugListView.tsx`）

### 2.1 内部 Tab 状态

```ts
type BugTab = 'list' | 'analysis';
const [activeTab, setActiveTab] = useState<BugTab>('list');
```

### 2.2 头部结构调整

现有 header（`BugListView.tsx:627-712`）含：← 返回 / 标题 / 批量区 / 筛选 / 项目下拉。改造为：

```
┌ ← 返回   我的缺陷            [缺陷列表] [AI分析 ◌] ┐   ← 始终显示（标题行 + Tab 栏）
├──────────────────────────────────────────────────┤
│  activeTab==='list'：  批量区 + 筛选 + 项目下拉      │   ← 仅 list Tab 显示这些控件
│  activeTab==='analysis'：BugAnalysisPanel            │
└──────────────────────────────────────────────────┘
```

- ← 返回 + 标题 + **Tab 栏**：始终渲染。
- 批量区/状态筛选/项目下拉：**仅 `activeTab==='list'`** 时渲染（移入 list 面板顶部，或对这三块做 `activeTab==='list' && (...)` 条件渲染——后者改动更小，推荐）。
- Tab 栏「AI分析」标签带状态徽标：`idle→◌`、`running→⟳(转)`、`done→✓`、`error→⚠️`，由 `analysis.status` 决定。

### 2.3 批量工具栏新增按钮

现有批量工具栏（`BugListView.tsx:649-664`，含【建监督者】【建会话】）追加：

```tsx
<button
  type="button"
  className={styles.analyzeBtn}
  disabled={selectedBugs.length === 0}
  onClick={handleBatchAnalyze}
>
  🔬 {t('bugList.aiAnalyze')}
</button>
```

```ts
const handleBatchAnalyze = () => {
  if (selectedBugs.length === 0) return;
  // 快照：identifier/serialNumber/subject/status（status 供分组派单复用 buildPrefillMulti）
  const snapshot = selectedBugs.map((b) => ({
    identifier: b.identifier || b.id || '',
    serialNumber: serialText(b),
    subject: b.subject || '',
    status: statusText(b.status),
  }));
  analysis.start(snapshot, model, reasoning);  // App 级 hook
  setActiveTab('analysis');                     // 立即切到 AI分析 Tab
  exitBatch();                                  // 退出批量选择
};
```

> 【🔬AI分析】**仅批量模式显示**（与建监督者/建会话同处工具栏，非批量模式整条工具栏不出现）。

---

## 3. App 级状态机：`hooks/useBugAnalysis.ts`（新建）

### 3.1 状态形状

```ts
export type AnalysisStatus = 'idle' | 'running' | 'done' | 'error';

export interface BugSnapshot { identifier: string; serialNumber: string; subject: string; status: string; }

export interface ClarityItem {
  serialNumber: string; identifier: string; subject: string;
  clarity: 'clear' | 'unclear';
  reason?: string;
  missing?: string[];          // 仅 unclear
}
export interface GroupItem {
  dimension: 'page' | 'api' | 'feature';
  label: string;
  members: string[];           // serialNumber[]
  rootCauseGuess?: string;
}
export interface AnalysisResult { bugs: ClarityItem[]; groups: GroupItem[]; }

export interface AnalysisProgress {
  total: number;
  doneIds: string[];           // 已完成的 bug identifier
  currentId: string | null;    // 正在分析的 identifier
  toolCalls: number;           // 兜底：已观察到的工具调用次数
}

export interface BugAnalysisState {
  status: AnalysisStatus;
  projectId: string;           // 当前绑定的项目
  snapshot: BugSnapshot[];     // 本次/上次分析的 bug 快照（重新分析 & 派单都靠它）
  progress: AnalysisProgress;
  result: AnalysisResult | null;
  raw: string | null;          // 解析失败兜底原文
  model: string; reasoning: string;
  analyzedAt: number | null;
  error: string | null;
}
```

### 3.2 暴露的动作

```ts
interface UseBugAnalysis {
  state: BugAnalysisState;
  bindProject: (projectId: string) => void;                 // BugListView effect 同步当前项目
  start: (snapshot: BugSnapshot[], model: string, reasoning: string) => void;
  reAnalyze: () => void;                                     // 复用 state.snapshot 重跑
  cancel: () => void;
  clear: () => void;                                         // 切项目时清显示
}
```

### 3.3 状态机（与持久化）

```
 [bindProject(pid)] ── pid 变化 ──► hydrate(pid)
      │ localStorage 有该项目存档 → status='done'(载入 result/snapshot/model…)
      │ 无 → status='idle'（清空显示）
      ▼
   idle ──start(snapshot,model,reasoning)──► running
      ▲                                         │ onBugAnalysisProgress → 更新 progress
      │                                         │ onBugAnalysisResult(ok) ──► done ──写盘──┐
      │                                         │ onBugAnalysisResult(!ok)──► error(raw)─写盘┤
   done/error ──reAnalyze()──► running          │ cancel() ──► 回退上一份存档(或 idle)      │
      └───────────────────────────────────────◄┴──────────────────────────────────────────┘
```

要点：
- **只在 `done`/`error` 落地时写 localStorage**（`running` 不写）。
- `start`：`sendBridgeEvent('analyze_bugs', {...})` + 本地置 `running` + `progress={total:snapshot.length, doneIds:[], currentId:null, toolCalls:0}`。
- `cancel`：`sendBridgeEvent('cancel_bug_analysis', {projectId})` + 回退到「上一份已存档结果」（hydrate 当前项目）；没有存档则 `idle`。
- `reAnalyze`：用 `state.snapshot` 再发一次 `analyze_bugs`（即使列表数据已变也不受影响）。

### 3.4 window 回调注册（App 级）

在 `App.tsx` 现有 `window.onRequestNewSupervisedWith` 注册处附近（`App.tsx:108`）一并注册，卸载时清理（`:147`）。回调体在 `useBugAnalysis` 内实现并通过 hook 注册：

```ts
useEffect(() => {
  window.onBugAnalysisProgress = (json) => { /* setState 合并 progress（带 projectId 守卫） */ };
  window.onBugAnalysisResult   = (json) => { /* ok → done+写盘 / !ok → error+写盘 */ };
  return () => { delete window.onBugAnalysisProgress; delete window.onBugAnalysisResult; };
}, [/* stable refs */]);
```

> **projectId 守卫**：回调 payload 带 `projectId`，与 `state.projectId` 不一致时丢弃（防止分析途中切项目串台）。

---

## 4. 持久化（localStorage，按项目分键）

### 4.1 键与值

```
键:  "bug-analysis-last:<projectId>"        // 每项目各一份，互不覆盖
值（JSON）:
{
  "projectId": "...", "analyzedAt": 1718200000000,
  "model": "claude-opus-4-8[1m]", "reasoning": "max",
  "snapshot": [ {identifier, serialNumber, subject, status} ],
  "result": { "bugs": [...], "groups": [...] } | null,
  "raw": null            // 解析失败兜底原文（result 为 null 时有值）
}
```

### 4.2 读写时机

| 时机 | 动作 |
|---|---|
| `bindProject(pid)` / 进入缺陷页 | `hydrate(pid)`：读 `bug-analysis-last:<pid>`，有则进 `done`/`error` 展示，无则 `idle` |
| 切项目（pid 变化） | 先 `clear()`（不显示旧项目结果）→ 再 `hydrate(新 pid)` |
| `onBugAnalysisResult` 落地 | 写 `bug-analysis-last:<当前 pid>` |

- **「切项目即清」=** 显示层只看当前项目的键；旧项目结果仍在其自己的键里（未删），回到该项目可见 → 同时满足「持久化最近一次」。
- JCEF 的 localStorage 落磁盘，**IDE 重启后仍在**（与现有 `model-selection-state` 一致）。单次结果几 KB，无压力。

---

## 5. `components/BugList/BugAnalysisPanel.tsx`（新建）

按 `analysis.state.status` 渲染四态。所有派单按钮复用 §6 的工具。

### 5.1 空态（idle）

```
╭ 本次还没有分析 ──────────────────────────────────╮
│  请到「缺陷列表」勾选缺陷后点 [🔬AI分析] 开始     │
╰──────────────────────────────────────────────────╯
```

### 5.2 分析中（running）

```
本次分析：claude-opus-4.8 (1M) · 思考:最高        [ 取消分析 ]
▰▰▰▰▰▰▰▱▱▱   正在分析  {doneIds.length} / {total}
────────────────────────────────────────────────────
✓ BUG-AAXE-1060  集团视角交叉巡检任务整改单来源…      （identifier ∈ doneIds）
⟳ BUG-AAXE-918   门店列表未按辖管权限获取    ← 拉取详情中（=== currentId）
◌ BUG-AAXE-848   任务转交后巡检人信息错误     （排队）
────────────────────────────────────────────────────
ⓘ 独立隔离会话，不写入你的任何对话；可随时切回「缺陷列表」
```

- 行来自 `state.snapshot`；标记规则：`doneIds.includes(id)→✓`、`id===currentId→⟳`、其余 `◌`。
- 进度退化兜底：若工具事件解析不到（见服务文档 §5），改用 `progress.toolCalls` 显示「已完成 N 次工具调用」+ 不带逐行标记的通用进度条。

### 5.3 结果（done，单页上下两段滚动）

```
本次分析：claude-opus-4.8(1M)·思考:最高   共{N} 明确✅{x} 不明确⚠️{y}   [重新分析]
═══════════════ ① 明确性清单 ═══════════════
⚠️ 不明确 ({y})         ← unclear 排前
   ⚠️ BUG-AAXE-1060  整改单来源显示不正确
      原因：{reason}
      缺失：{missing.join(' · ')}
✅ 明确 ({x})
   ✅ BUG-AAXE-1050  到店巡检报告获取不到数据
      依据：{reason}
═══════════════ ② 关联分组 ═══════════════
📄 同页面 · {label}                              {members.length} 个
   ◻ AAXE-1060  ◻ AAXE-1052  ◻ AAXE-1044
   疑似根因：{rootCauseGuess}
        [ 批量建监督者 ▸ ]  [ 批量建会话 ▸ ]
🔌 同接口 · {label}                              …
🧩 同功能 · {label}                              …
────────────────────────────────────────────────────
[◻ 全选]  [对选中批量建监督者]  [对选中批量建会话]
```

- 维度图标：`page→📄同页面`、`api→🔌同接口`、`feature→🧩同功能`。
- 不明确项**只列缺失项**，无按钮。
- 底部跨组勾选：`◻` 选中态为「该 serialNumber 是否入选」的本地 `Set<string>`，**默认全不选**；底部按钮对选中集合派单。

### 5.4 错误兜底（error）

```
⚠️ 结构化结果解析失败，已展示原始分析文本：       [重新分析]
╭──────────────────────────────────────────────────╮
│ {state.raw}（可滚动）                              │
╰──────────────────────────────────────────────────╯
```

---

## 6. 分组派单（复用现有逻辑）

现有 `buildPrefillMulti`（`BugListView.tsx:368-385`）与 `buildPrefill` 抽到共享工具 **`utils/bugPrefill.ts`**（纯函数，入参 `bug[] + appendPrompt`），供两处使用：

- BugListView 批量工具栏（现状）
- BugAnalysisPanel 分组派单（新）

派单实现（与现有批量按钮同款 IPC）：

```ts
// 组内 / 底部选中 → bugs: BugSnapshot[]（从 result.groups[].members 映射回 state.snapshot）
const dispatchSupervisor = (bugs: BugSnapshot[]) =>
  sendBridgeEvent('create_new_supervised_tab',
    JSON.stringify({ agentId: 'bug-supervisor', initialComposerText: buildPrefillMulti(bugs, appendPrompt) }));
const dispatchSession = (bugs: BugSnapshot[]) =>
  sendBridgeEvent('create_new_tab',
    JSON.stringify({ initialComposerText: buildPrefillMulti(bugs, appendPrompt) }));
```

> `appendPrompt` 来自 `useYunxiaoBugs`（云效设置里配置的追加文案）；BugListView 把它透传给 BugAnalysisPanel。
> `members` 是 `serialNumber[]`；映射回 `BugSnapshot`（取 identifier）后再拼 prefill。

---

## 7. IPC 协议（webview 侧）

> 完整服务端实现见配套服务文档 §2/§5/§6。

| 方向 | 消息 | 载荷 | 说明 |
|---|---|---|---|
| →Java | `analyze_bugs` | `{ projectId, bugs:[{identifier,serialNumber,subject,status}], model, reasoningEffort }` | 发起隔离分析 |
| →Java | `cancel_bug_analysis` | `{ projectId }` | 取消进行中的分析 |
| Java→ | `window.onBugAnalysisProgress(json)` | `{ projectId, total, doneIds:[], currentId, toolCalls }` | 进度（多次） |
| Java→ | `window.onBugAnalysisResult(json)` | 成功 `{ ok:true, projectId, model, reasoning, result:{bugs,groups} }`；失败 `{ ok:false, projectId, raw, error }` | 终态（一次） |

---

## 8. i18n（`i18n/locales/{zh,en,zh-TW}.json`）

`bugList.*` 追加 + 新增 `bugAnalysis.*`：

```jsonc
"bugList": { "aiAnalyze": "AI分析", "tabList": "缺陷列表", "tabAnalysis": "AI分析" },
"bugAnalysis": {
  "emptyHint": "请到「缺陷列表」勾选缺陷后点 AI分析 开始",
  "header": "本次分析：{{model}} · 思考:{{reasoning}}",
  "stat": "共 {{n}}　明确 {{clear}}　不明确 {{unclear}}",
  "analyzing": "正在分析 {{done}} / {{total}}",
  "fetchingDetail": "拉取详情中", "queued": "排队",
  "isolatedHint": "独立隔离会话，不写入你的任何对话；可随时切回「缺陷列表」",
  "cancel": "取消分析", "reAnalyze": "重新分析",
  "sectionClarity": "明确性清单", "sectionGroups": "关联分组",
  "clear": "明确", "unclear": "不明确", "reason": "原因", "basis": "依据", "missing": "缺失",
  "dimPage": "同页面", "dimApi": "同接口", "dimFeature": "同功能", "rootCause": "疑似根因",
  "batchSupervisor": "批量建监督者", "batchSession": "批量建会话",
  "selectAll": "全选", "dispatchSelectedSupervisor": "对选中批量建监督者", "dispatchSelectedSession": "对选中批量建会话",
  "parseFailed": "结构化结果解析失败，已展示原始分析文本：",
  "toolCalls": "已完成 {{n}} 次工具调用"
}
```

---

## 9. 文件清单（UI）

| 文件 | 改动 |
|---|---|
| `hooks/useBugAnalysis.ts` ★新 | 状态机 + window 回调 + 持久化 |
| `components/BugList/BugAnalysisPanel.tsx` ★新 | 四态渲染 + 分组派单 |
| `components/BugList/style.module.less` | 追加 Tab 栏 / 进度 / 清单 / 分组卡 / 派单按钮样式 |
| `components/BugList/BugListView.tsx` | Tab 栏 + activeTab；批量工具栏加【AI分析】；条件渲染控件；接 `analysis/model/reasoning` props；同步 `bindProject` |
| `utils/bugPrefill.ts` ★新 | 抽出 `buildPrefill/buildPrefillMulti`（BugListView 改为引用） |
| `App.tsx` | 挂 `useBugAnalysis`；给 `BugListView` 传 `analysis/model/reasoning` |
| `global.d.ts` | 声明 `onBugAnalysisProgress` / `onBugAnalysisResult` |
| `i18n/locales/{zh,en,zh-TW}.json` | `bugList.*` + `bugAnalysis.*` |

---

## 10. 验证点（UI）

1. `tsc --noEmit` + `vite build` 通过。
2. 批量勾选 → 【AI分析】→ 自动切 AI分析 Tab，进入「分析中」，逐 bug 进度推进。
3. 分析中切到「缺陷列表」再切回 → 进度不丢；切到聊天视图再回缺陷页 → 完成的结果已落盘并展示。
4. 完成 → 明确性清单（unclear 在前）+ 三维分组卡；组内 / 底部勾选派单都能开出预填的监督者/会话 Tab。
5. 切项目 → 立即清空显示；切回原项目 → 最近一次结果还在。
6. 解析失败 → 兜底原文 + 重新分析可用。
7. 取消 → 回退到上一份存档（或空态）。

---

## 11. 追加（2026-06-13）：实时分析过程直播（只读）

「分析中」面板在逐 bug 进度下方新增**只读实时过程区**，类似普通会话的思考过程（只看、无输入框）。

- **新 IPC（Java→）**：`window.onBugAnalysisStream(json)` = `{ projectId, kind:'thinking'|'content'|'tool', text }`。
- **`useBugAnalysis` 新增 `stream: StreamSegment[]`**（`{kind,text}`）：thinking/content 与同类相邻段**合并**、tool **离散**成段；仅 `running` 期间累积，**不持久化**（`INITIAL_STATE`/`hydratedState`/`start` 均置空数组；`PersistedAnalysis` 不含 stream）；带 projectId 守卫 + `status==='running'` 守卫防串台/迟到帧。
- **`BugAnalysisPanel.renderRunning`** 渲染 `state.stream`：thinking 暗色斜体、content 常规、tool 渲染为 `🔧 查询缺陷详情 · BUG-xxx` 芯片；`streamRef` + `useEffect([state.stream, state.status])` 自动贴底（像聊天流式）。空时显示 `streamWaiting`。
- **i18n** 追加 `bugAnalysis.{processTitle,toolQuery,streamWaiting}`（zh/en/zh-TW）；**样式** `streamSection/streamTitle/streamFeed/streamWaiting/streamThinking/streamContent/streamTool`（`streamFeed` `max-height:40vh; overflow:auto`）。
- §7 IPC 表追加一行：`window.onBugAnalysisStream`（多次，running 期间）。
- 纯前端 + 一处 Java（见服务文档 §14）；webview build；不改 daemon。
