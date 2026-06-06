# 工作流「导入 / 导出 / 规则」编码方案

> 续前几份工作流方案（决策编号接 D29/DN15 之后）。本特性**纯前端**：导出=序列化内存里的定义并复制;导入=粘贴 JSON→规范化→设为未保存草稿,复用现有 `workflow_save`(后端 `DagValidator`)做最终落地校验。后端零改动。

## 0. 背景与目标

左侧工作流列表新增「导入 / 导出 / 查看规则」三个入口:
1. **导出**:把选中的工作流定义导成可读 JSON(弹窗 + 复制),便于备份/分享。
2. **导入**:粘贴 JSON → 规范化 → 作为**未保存草稿**载入编辑器,用户复核后保存。
3. **查看规则**:弹窗展示「定义 JSON 规则 + 字段约束 + 本机监督者清单 + 示例」,整段喂给 LLM 即可生成合法工作流。

## 1. 决策摘要

| 编号 | 决策 | 取值 |
|---|---|---|
| **D30** | 传输方式 | 文本弹窗 + 复制/粘贴(纯前端,无文件 IO) |
| **D31** | 导出格式 | 信封 `{kind,version,workflow}`;节点用 `supervisorName`(+ 可选 `supervisorId`);剔除 `id/updatedAt/posX/posY/执行态` |
| **D32** | 导入落地 | 规范化后设为**未保存草稿**(复用编辑器 + 保存闸门),不做独立预览弹窗 |
| **D33** | 监督者解析 | `supervisorId` 本机存在→保留;否则 `supervisorName` 匹配→重映射;都不在→**置空 + 警告** |
| **D34** | LLM 用 name | 规则弹窗指引 LLM 填 `supervisorName`,并**动态列出本机监督者**;`supervisorId` 仅作精确匹配 |
| **DN16** | 健壮性 | 信封可选(兼容裸定义);未知字段忽略;`delayMinutes` clamp 0–300;`id` 重生成;重名加后缀;悬空依赖丢弃并警告 |

## 2. 数据格式(导出信封)

```json
{
  "kind": "cc-gui.workflow",
  "version": 1,
  "workflow": {
    "name": "方案双写 + 接口测试",
    "maxConcurrency": 2,
    "nodes": [
      { "name": "方案A", "supervisorName": "Code Supervisor", "supervisorId": "uuid-…",
        "plan": "实现方案 A。", "model": "claude-opus-4-8", "reasoning": "high",
        "dependsOn": [], "delayMode": "none" },
      { "name": "接口测试", "supervisorName": "Code Supervisor",
        "plan": "联调 A、B 的接口。", "dependsOn": ["方案A", "方案B"],
        "delayMode": "relative", "delayMinutes": 5 }
    ]
  }
}
```

字段取舍:
- **含**:`name`、`supervisorName`、`supervisorId`(可选)、`plan`/`planPath`、`model`/`longContext`/`reasoning`(有才写)、`dependsOn`、`delayMode`/`delayMinutes`/`scheduledAt`(有才写)。
- **不含**:`id`、`updatedAt`、`posX`/`posY`、`WorkflowExecution`(执行态)。
- 空/默认值省略,保持 JSON 干净、LLM 易仿写。

## 3. 纯函数模块 `portability.ts`(新增,可单测)

```ts
// webview/src/components/WorkflowOrchestration/portability.ts
import type { WorkflowDefinition, WorkflowNode } from './types';
import type { SupervisorAgent } from '../../types/supervisorAgent';

export const WF_KIND = 'cc-gui.workflow';
export const WF_VERSION = 1;

/** 选中定义 → 信封 JSON 字符串(pretty)。 */
export function buildExportEnvelope(def: WorkflowDefinition, agents: SupervisorAgent[]): string;

/** 解析导入文本。兼容信封与裸定义;失败给可读 error。 */
export function parseImport(text: string):
  { ok: true; workflow: any; versionWarn?: string } | { ok: false; error: string };

/** 规范化为可载入的草稿,并收集警告(监督者缺失 / planPath / 绝对时间 / 悬空依赖 / 重名)。 */
export function normalizeImported(
  raw: any, agents: SupervisorAgent[], existingNames: string[],
): { def: WorkflowDefinition; warnings: string[] };

/** 规则弹窗正文(静态规则 + 动态监督者清单 + 示例),整段可复制喂 LLM。 */
export function buildRulesText(agents: SupervisorAgent[]): string;
```

**`normalizeImported` 算法**(D33/DN16):
1. `def.id = uid('wf')`;`def.updatedAt = Date.now()`;`def.maxConcurrency = clampInt(raw.maxConcurrency ?? 2, 1, 99)`(后端再按 ceiling clamp)。
2. `def.name = (raw.name||'').trim() || '导入的工作流'`;若 `existingNames` 含之 → 加后缀「(导入)」「(导入2)」直至唯一。
3. 逐节点:
   - 监督者:`agents` 有 `n.supervisorId` → 保留;否则按 `n.supervisorName`(trim/小写)匹配 → 取其 id;都没有 → `supervisorId=''` 且 `warnings.push("节点「X」监督者未匹配,请重新选择")`。
   - `delayMode`:`'relative'` → `delayMinutes = clampInt(…,0,300)`;`'absolute'` → 保留 `scheduledAt`(缺失则置 none + 警告);其余 → `'none'`,清 `delayMinutes/scheduledAt`。
   - `planPath` 非空 → 警告「节点「X」引用了本地文件路径,跨机可能失效」;`scheduledAt` 存在 → 警告「绝对时间为导出时设定,请确认」。
   - 丢弃 `posX/posY`;`dependsOn` 规整为字符串数组。
4. 节点名去重(沿用 `WorkflowContext` 已有的 `dedupeNodeNames` 逻辑,抽到本模块共用),并**丢弃指向不存在节点的依赖** + 警告。
5. 返回 `{ def, warnings }`。

**`parseImport`**:`JSON.parse`(失败→`error:"JSON 解析失败:…"`);`workflow = obj.workflow ?? obj`;校验 `workflow.nodes` 是数组(否则→`error:"不是有效的工作流定义"`);`obj.kind && obj.kind!==WF_KIND` → 仍尝试解析但 `error` 提示类型不符;`obj.version>WF_VERSION` → `versionWarn`。

## 4. `WorkflowContext.tsx` 改动

```ts
// 接口新增
exportWorkflowJson(id: string): string;                 // 给导出弹窗
importWorkflowDraft(text: string):                       // 导入成草稿
  { ok: boolean; warnings: string[]; error?: string };
```

实现:
- `exportWorkflowJson(id)`:`const def = definitions.find(d=>d.id===id) ?? (draft?.id===id?draft:null)`;`return def ? buildExportEnvelope(def, agents) : ''`。
- `importWorkflowDraft(text)`:
  ```ts
  const p = parseImport(text);
  if (!p.ok) return { ok:false, warnings:[], error:p.error };
  const { def, warnings } = normalizeImported(p.workflow, agents, definitions.map(d=>d.name));
  setDraft(def); setSelectedId(def.id);     // 未保存草稿(id 不在 definitions → isDirty/可保存)
  return { ok:true, warnings: p.versionWarn ? [p.versionWarn, ...warnings] : warnings };
  ```
  关键:**不调用 `workflow_save`** —— 这正是「未保存草稿」。draft 的新 id 不在 `definitions` → `isSaved=false`、`isDirty=true` → 保存按钮亮起,缺监督者的节点过不了 `validateWorkflowDef`,逼用户补选后再保存(D32,复用现有闸门)。镜像 `newWorkflow` 的行为。
- 两者加入 context value(对象 + 依赖数组)。

## 5. UI 改动

### 5.1 列表工具条 `WorkflowList.tsx`
顶部加一行小工具条(与底部「新建工作流」呼应):
- **导入**、**查看规则**:始终可点。
- **导出**:仅 `selectedId` 非空可点。
新增 props:`onImport()`、`onExport()`、`onShowRules()`;导出按钮 `disabled={!selectedId}`。

### 5.2 弹窗 `WorkflowPortabilityDialog.tsx`(新增,单组件三模式)
用 antd `Modal` + `ConfigProvider`(深浅色跟随 `data-theme`,同 DatePicker 那次);`mode: 'import'|'export'|'rules'`:
- **export**:只读 `textarea` 展示 `exportWorkflowJson(id)` + 「复制」(`copyToClipboard`,`utils/copyUtils.ts`)。
- **rules**:只读展示 `buildRulesText(agents)` + 「复制全部」。
- **import**:可编辑 `textarea` + 「导入」按钮 → `importWorkflowDraft(text)`;`ok` → 关闭 + 汇总 toast(`已导入,请复核后保存`,有 warnings 再补一条 warning toast);`!ok` → 弹窗内红字显示 `error`。

### 5.3 `WorkflowView.tsx`
持有 `dialog: null|'import'|'export'|'rules'` 状态,渲染 `WorkflowPortabilityDialog`,把 `onImport/onExport/onShowRules` 透传给 `WorkflowList`;导出时把 `selectedId` 传入。toast 用现有 `addToast`(provider 已注入)。

## 6. 导入端到端流程

```
点「导入」→ 弹窗粘贴 JSON → importWorkflowDraft
  parseImport(text)            // 信封/裸定义,JSON 错误 → 红字
  normalizeImported(...)       // 重生成 id、监督者 name→id、剔除/clamp、去重、丢悬空依赖
  setDraft + setSelectedId     // 未保存草稿,载入画布
  → toast: 已导入(+ 警告汇总)
用户在画布复核:补选缺失监督者 → 点「保存」(现有 validateWorkflowDef + workflow_save + DagValidator)
```

## 7. 规则弹窗正文(`buildRulesText`)

一整块、可复制:
1. 指令:「请生成符合以下规则的工作流 JSON(只输出 JSON)」。
2. 信封说明 + 字段表:
   - `name` 必填;`maxConcurrency` 1..(运行时按本机上限收敛)。
   - 节点:`name`(唯一)、`supervisorName`(必须是下方清单之一)、`plan` 或 `planPath` 至少一个、`dependsOn`(引用已存在节点名、不得成环)、`model`/`reasoning`(可选)。
   - 时机:`delayMode`∈`none|relative|absolute`;`relative` 需 `delayMinutes`(0–300);`absolute` 需 `scheduledAt`(本地时刻的 epoch 毫秒)。
3. **本机可用监督者**(动态):逐行 `- 名称：<name>`(可附 id)。
4. 完整示例(§2 的 JSON)。

## 8. i18n(`zh.json` / `en.json`,`workflow.io.*`)
`io.import`/`io.export`/`io.rules`(按钮)、`io.importTitle`/`io.exportTitle`/`io.rulesTitle`、`io.pastePlaceholder`、`io.copy`/`io.copied`、`io.imported`(已导入,请复核后保存)、`io.parseError`、`io.exportEmpty`(请先选择一个工作流)、`io.warn.supervisor`/`io.warn.planPath`/`io.warn.absolute`/`io.warn.depDropped`/`io.warn.renamed`。

## 9. 测试(vitest,`portability.test.ts`)
1. `buildExportEnvelope`:含 `kind/version`;节点带 `supervisorName`;不含 `id/posX/posY`;空字段省略。
2. round-trip:export → parseImport → normalize → 节点/依赖/时机字段一致(除 id/位置)。
3. `parseImport`:坏 JSON → error;裸定义(无信封)→ ok;非工作流对象 → error;高版本 → versionWarn。
4. `normalizeImported`:
   - 监督者 id 命中→保留;仅 name 命中→重映射;都不在→置空 + warning。
   - `delayMinutes` 999→clamp 300;`absolute` 缺 `scheduledAt`→none + warning。
   - 重名工作流→加后缀;重名节点→去重;悬空依赖→丢弃 + warning;`posX/posY` 被剔除;`id` 重生成。
5. `buildRulesText`:含每个传入 agent 的名字、含示例、含约束关键词。

## 10. 分阶段 + 文件清单

**P1｜纯函数** `portability.ts` + `portability.test.ts`(export/parse/normalize/rules,抽出共用的 `dedupeNodeNames`)。
**P2｜context** `WorkflowContext.tsx`(`exportWorkflowJson`/`importWorkflowDraft` + value)。
**P3｜UI** `WorkflowPortabilityDialog.tsx`(新增)、`WorkflowList.tsx`(工具条)、`WorkflowView.tsx`(弹窗状态/透传)、less(工具条 + 弹窗微调)。
**P4｜i18n + 自测** keys;`tsc` + `vitest` + `vite build`。

```
webview/src/components/WorkflowOrchestration/portability.ts            (新增)
webview/src/components/WorkflowOrchestration/portability.test.ts       (新增)
webview/src/components/WorkflowOrchestration/WorkflowPortabilityDialog.tsx (新增)
webview/src/components/WorkflowOrchestration/WorkflowContext.tsx
webview/src/components/WorkflowOrchestration/WorkflowList.tsx
webview/src/components/WorkflowOrchestration/WorkflowView.tsx
webview/src/components/WorkflowOrchestration/style.module.less
webview/src/i18n/locales/zh.json, en.json
```
后端:**无改动**。

## 11. 风险与回退

| 风险 | 缓解 |
|---|---|
| LLM 输出夹带 ```json 围栏/前后缀 | `parseImport` 先 trim,并尝试截取首个 `{` 到末个 `}` 再 `JSON.parse`;失败给可读 error |
| 监督者跨机对不上 | name 重映射 + 置空警告,保存闸门强制补选(D33) |
| 覆盖现有工作流 | 导入一律新 id + 未保存草稿,绝不动现有(D32) |
| 导入超大/恶意 JSON | 限制文本长度(如 256KB),节点数上限提示;仅取已知字段,未知忽略 |
| antd Modal 在 JCEF 主题/层级 | 复用已验证的 `ConfigProvider` 深浅色;弹层挂 body(同 DatePicker) |

回退:三个入口都是新增 UI,移除按钮即完全退场;导入只产草稿、不落盘,无持久副作用。
