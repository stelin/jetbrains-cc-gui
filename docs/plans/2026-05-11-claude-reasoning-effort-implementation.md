# Claude Reasoning Effort 接入编码方案

**日期**: 2026-05-11
**作者**: Claude Code 协作
**状态**: 待实施
**关联参考**: 上游开源仓库 `jetbrains-cc-gui-origin`

---

## 一、背景

Fork 仓库当前已为 **Codex 通道**实现了 Reasoning Effort(思考深度)选择器,UI 显示 4 档(Low / Medium / High / Max[实为 xhigh]),并通过 `set_reasoning_effort` bridge event 持久化到 `SessionState.reasoningEffort`,最终在 `CodexSDKBridge` 写入 stdin JSON。

上游 origin 项目把该能力扩展到了 **Claude 通道**,支持完整的 5 档(low / medium / high / xhigh / max),并按 Claude 模型差异显示对应可用档位:

| 模型 | 可用档位 |
|---|---|
| Claude Opus 4.7 | low / medium / high / xhigh / max |
| Claude Opus 4.6 / Opus 4.6[1m] / Sonnet 4.6 | low / medium / high / max(无 xhigh) |
| Claude Haiku 4.5 / 其他老模型 | 选择器隐藏 |
| Codex(所有模型) | low / medium / high / xhigh(无 max) |

本方案将 origin 的能力移植到 Fork,且 **不破坏远程模式**(Fork 特有特性,`RemoteBridge` 把 params JSON 上送给远端 `ai-bridge-server`)。

---

## 二、决策记录(已拍板)

| 项 | 决策 |
|---|---|
| xhigh label 文案变更(老 "Max" 改回 "XHigh") | 不写 CHANGELOG 迁移说明 |
| Claude / Codex effort 状态 | **共享同一个 state**(不按 provider 拆分) |
| `useModelProviderState.ts` 默认值 | **`'max'`**(从 `'medium'` 改成 `'max'`) |
| `ClaudeSDKBridge` 重载扩展策略 | **只扩展最常用重载**(`:353` 那个 12 参重载),其他重载转发 `null` |
| 远程 ai-bridge-server | 复用本仓库 `ai-bridge/`,Phase 5 一次性改完即可 |
| Codex max 档 | 不加,Codex 仍是 4 档 |

---

## 三、改动总览

| 阶段 | 层 | 文件数 | 风险 |
|---|---|---|---|
| Phase 1 | webview 数据层 (`types.ts`) | 1 | 低 |
| Phase 2 | webview UI (`ReasoningSelect` / `ButtonArea` / `useModelProviderState`) | 3 | 低 |
| Phase 3 | webview i18n locales | 10 | 低(仅新增 key) |
| Phase 4 | Java 桥接层(Claude 链路 5 文件) | 5 | 中(签名扩展) |
| Phase 5 | ai-bridge Node 层 | 2 | 中(SDK options 互斥) |

---

## 四、Phase 1 — Webview 数据层

### 文件: `webview/src/components/ChatInputBox/types.ts`

#### 4.1.1 新增 3 个模型 Set 常量

在 `AVAILABLE_PROVIDERS` 之后(约第 412 行后)、`ReasoningEffort` 类型之前插入:

```ts
/**
 * Claude 模型 → 支持自适应思考(effort 参数)的模型集合
 * 参考: https://code.claude.com/docs/en/model-config#adjust-effort-level
 */
export const EFFORT_SUPPORTED_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
  'claude-opus-4-6',
  'claude-opus-4-6[1m]',
  'claude-sonnet-4-6',
]);

/**
 * Claude 模型 → 额外支持 'xhigh' 档位的模型(目前仅 Opus 4.7)
 */
export const XHIGH_EFFORT_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
]);

/**
 * Claude 模型 → 支持 'max' 档位的模型
 */
export const MAX_EFFORT_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
  'claude-opus-4-6',
  'claude-opus-4-6[1m]',
  'claude-sonnet-4-6',
]);
```

⚠️ **校对动作**:开工前先到 `CLAUDE_MODELS`(同文件)或 `ModelSelect.tsx` 确认 Fork 实际使用的模型 id 字符串。若 1M-context 版本写法与 `claude-opus-4-6[1m]` 不一致,需调整 Set。

#### 4.1.2 扩展 `ReasoningEffort` 类型

第 419 行附近,加入 `'max'`:

```ts
export type ReasoningEffort = 'low' | 'medium' | 'high' | 'xhigh' | 'max';
```

#### 4.1.3 修改 `REASONING_LEVELS` 数组(第 434 行附近)

- 把现有 `xhigh` 项 label 从 `'Max'` 改回 `'XHigh'`,icon 改为 `codicon-rocket`
- 新增 `max` 项放在最后

```ts
export const REASONING_LEVELS: ReasoningInfo[] = [
  { id: 'low',    label: 'Low',    icon: 'codicon-circle-small',        description: 'Quick responses with basic reasoning' },
  { id: 'medium', label: 'Medium', icon: 'codicon-circle-filled',       description: 'Balanced thinking (default)' },
  { id: 'high',   label: 'High',   icon: 'codicon-circle-large-filled', description: 'Deep reasoning for complex tasks' },
  { id: 'xhigh',  label: 'XHigh',  icon: 'codicon-rocket',              description: 'Extra deep reasoning for demanding tasks' },
  { id: 'max',    label: 'Max',    icon: 'codicon-flame',               description: 'Maximum reasoning depth' },
];
```

---

## 五、Phase 2 — Webview UI 层

### 5.1 文件: `webview/src/components/ChatInputBox/selectors/ReasoningSelect.tsx`

#### 5.1.1 import 扩展(第 3 行)

```ts
import {
  REASONING_LEVELS,
  EFFORT_SUPPORTED_CLAUDE_MODELS,
  MAX_EFFORT_CLAUDE_MODELS,
  XHIGH_EFFORT_CLAUDE_MODELS,
  type ReasoningEffort,
} from '../types';
```

#### 5.1.2 Props 接口扩展

```ts
interface ReasoningSelectProps {
  value: ReasoningEffort;
  onChange: (effort: ReasoningEffort) => void;
  disabled?: boolean;
  selectedModel?: string;
  currentProvider?: string;
}
```

#### 5.1.3 函数体顶部插入可见性与档位过滤逻辑

```ts
const isVisible =
  currentProvider !== 'claude' ||
  !selectedModel ||
  EFFORT_SUPPORTED_CLAUDE_MODELS.has(selectedModel);

const availableLevels = REASONING_LEVELS.filter(level => {
  if (currentProvider !== 'claude') {
    return level.id !== 'max';            // Codex: 屏蔽 max
  }
  if (!selectedModel) return true;
  if (level.id === 'xhigh') return XHIGH_EFFORT_CLAUDE_MODELS.has(selectedModel);
  if (level.id === 'max')   return MAX_EFFORT_CLAUDE_MODELS.has(selectedModel);
  return true;
});

const currentLevel =
  availableLevels.find(l => l.id === value)
  || availableLevels[availableLevels.length - 2]
  || availableLevels[0];

// 模型切换导致 value 不在可用列表 → 自动 fallback
useEffect(() => {
  if (!isVisible || availableLevels.some(l => l.id === value)) return;
  if (currentLevel) onChange(currentLevel.id);
}, [availableLevels, currentLevel, isVisible, onChange, value]);
```

#### 5.1.4 隐藏逻辑

```ts
if (!isVisible) return null;
```
放在原 `return (` 之前。

### 5.2 文件: `webview/src/components/ChatInputBox/ButtonArea.tsx`

第 264-266 行去掉 `currentProvider === 'codex'` 守卫,改为始终渲染并透传:

```tsx
<ReasoningSelect
  value={reasoningEffort}
  onChange={handleReasoningChange}
  selectedModel={selectedModel}
  currentProvider={currentProvider}
/>
```

可见性已下沉至组件内。

### 5.3 文件: `webview/src/hooks/useModelProviderState.ts`

**默认值改成 `'max'`**(第 37 行):

```ts
const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('max');
```

其他位置(`handleReasoningChange` 第 274-277 行)无需改动,继续共用 `set_reasoning_effort` bridge event。

⚠️ **副作用提醒**:默认 `'max'` 会让首次会话用最高思考预算,需要确认模型若不支持 `max`(例如 Haiku)是否会被 ReasoningSelect 的 `useEffect` fallback 自动纠正。结论:**会**,因为 ReasoningSelect 的 fallback effect 会在不支持时自动调用 `onChange` 回退到 `availableLevels` 末尾倒数第二档(即 high)。

---

## 六、Phase 3 — Webview i18n

### 涉及文件(10 个)

`webview/src/i18n/locales/{en,zh,zh-TW,ja,ko,fr,es,pt-BR,ru,hi}.json`

### 改动模式

对每个 locale,定位 `"reasoning"` 块,执行 2 步:

**Step 1**: 把现有 `xhigh.label` 从 `"Max"` 改成 `"XHigh"`(各语言对应翻译)
**Step 2**: 在 `xhigh` 之后追加 `max` 子项

英文 (`en.json` 第 1484-1502 行)示例:

```json
"reasoning": {
  "title": "Select reasoning depth",
  "low": { "label": "Low", "description": "Quick responses with basic reasoning" },
  "medium": { "label": "Medium", "description": "Balanced thinking (default)" },
  "high": { "label": "High", "description": "Deep reasoning for complex tasks" },
  "xhigh": { "label": "XHigh", "description": "Extra deep reasoning for demanding tasks" },
  "max": { "label": "Max", "description": "Maximum reasoning depth" }
}
```

中文 (`zh.json`) 示例:

```json
"xhigh": { "label": "XHigh", "description": "面向高难任务的额外深度推理" },
"max":   { "label": "Max",   "description": "最大推理深度" }
```

其他语言可参考 origin 仓库对应 locale 文件直接复制。

---

## 七、Phase 4 — Java 桥接层(Claude 链路)

### 7.1 设计原则

**关键策略**:把 `reasoningEffort` 加在 `ClaudeRequestParamsBuilder.buildSendParams` 最末。
- daemon 模式、per-process 模式都调用同一个 `buildSendParams` → 共享输出
- 远程模式由 `RemoteBridge` 把 JsonObject 上送 → **无需单独改 RemoteBridge**
- 因此只要 `buildSendParams` 写入了 `reasoningEffort` 字段,3 种传输模式都自动受益

### 7.2 调用链(改动顺序自底向上)

```
SessionSendService.sendToClaude
  └─> ClaudeSDKBridge.sendMessage   ←【只改最完整的 :353 重载】
        ├─> sendMessageViaDaemon → ClaudeDaemonRequestExecutor
        │     └─> ClaudeRequestParamsBuilder.buildSendParams    ←【核心:写入 JSON 字段】
        └─> processInvoker.sendMessage → ClaudeProcessInvoker
              └─> ClaudeRequestParamsBuilder.buildSendParams    (同上)
```

### 7.3 文件 1: `ClaudeRequestParamsBuilder.java`

`buildSendParams` 方法签名(第 24-36 行)末尾追加 `String reasoningEffort`,在 `disableThinking` 写入逻辑之后追加:

```java
if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
    params.addProperty("reasoningEffort", reasoningEffort);
}
return params;
```

### 7.4 文件 2: `ClaudeDaemonRequestExecutor.java`

`sendMessageViaDaemon` 签名(第 37-52 行)在 `disableThinking` 之后、`callback` 之前插入 `String reasoningEffort`,调 `requestParamsBuilder.buildSendParams(...)` 时把它传入。

### 7.5 文件 3: `ClaudeProcessInvoker.java`

`sendMessage` 签名(第 63-77 行)同样在 `disableThinking` 之后插入 `String reasoningEffort`,第 101 行调 `requestParamsBuilder.buildSendParams(...)` 时透传。

### 7.6 文件 4: `ClaudeSDKBridge.java`

**仅扩展最完整的 :353 重载**:在 `Boolean disableThinking` 之后、`MessageCallback callback` 之前插入 `String reasoningEffort`。

`sendMessageViaDaemon(...)` 和 `processInvoker.sendMessage(...)` 调用处都加上 `reasoningEffort` 参数。

**其他 4 个简化重载(`:302/:316/:334/:374`)保持不变**,在内部转发时传 `null` 占位(实际转发链路:它们最终都会汇集到 :353 重载,因此在那一层把 effort 接进去即可)。

实际查看代码,`:302/:316/:334` 实际转发到 `:353` 那个完整重载,新增 effort 参数后转发处补 `null`:

```java
return sendMessage(channelId, message, sessionId, null, cwd, attachments, permissionMode,
        model, openedFiles, agentPrompt, streaming, disableThinking,
        null,   // ← reasoningEffort (老重载传 null)
        callback);
```

### 7.7 文件 5: `SessionSendService.java`

`sendToClaude` 第 233 行调用处增加 `state.getReasoningEffort()` 参数:

```java
return claudeSDKBridge.sendMessage(
        channelId, input, state.getSessionId(), runtimeSessionEpoch,
        state.getCwd(), attachments, effectivePermissionMode,
        currentModel, openedFilesJson, agentPrompt,
        streaming, false,
        state.getReasoningEffort(),   // ← 新增
        handler
).thenApply(result -> null);
```

### 7.8 文件 6: `GitCommitMessageService.java`

第 442 行(注释 `// reasoningEffort (use default)` 旁)在新签名对应位置补 `null` 即可:commit message 生成不需要用户的 effort 选择。

### 7.9 不需要改的文件

- `SessionState.java` — `reasoningEffort` 字段、getter/setter 已存在(第 65/126/193 行)
- `TabStateService.java` / `ClaudeChatWindow.java` — 已经做了持久化(第 187/351/580 行)
- `RemoteBridge.java` — 透明转发 JsonObject,无需改动
- `SettingsHandler.java` / `ModelProviderHandler.java` — `set_reasoning_effort` 事件路由已就位

---

## 八、Phase 5 — ai-bridge (Node) 层

### 8.1 文件 1: `ai-bridge/channels/claude-channel.js`

第 31 行解构里加 `reasoningEffort`:

```js
const {
  message, sessionId, cwd, permissionMode, model, openedFiles,
  agentPrompt, streaming, disableThinking,
  reasoningEffort           // ← 新增
} = stdinData;
```

第 41 行(`send` 分支)调用 `claudeSendMessage(...)` 末尾追加 `reasoningEffort || null`。
`sendWithAttachments` 分支已经把整个 `stdinData` 传给 `claudeSendMessageWithAttachments`,无需改 channel,改 sender 即可。

### 8.2 文件 2: `ai-bridge/services/claude/message-sender.js`

#### 8.2.1 顶部新增常量与归一化函数(第 38 行附近)

```js
const SUPPORTED_EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

function normalizeReasoningEffort(v) {
  const e = typeof v === 'string' ? v.trim() : '';
  return SUPPORTED_EFFORT_LEVELS.has(e) ? e : null;
}
```

#### 8.2.2 `sendMessage` 函数(第 391 行)

**Step A**: 签名末尾追加 `reasoningEffort = null`:

```js
export async function sendMessage(message, resumeSessionId = null, cwd = null,
    permissionMode = null, model = null, openedFiles = null,
    agentPrompt = null, streaming = null, reasoningEffort = null) {
```

**Step B**: 第 419 行附近(`resolveThinkingConfig` 之后)改写互斥逻辑:

```js
const normalizedReasoningEffort = normalizeReasoningEffort(reasoningEffort);
const { alwaysThinkingEnabled, maxThinkingTokens: configuredMaxThinkingTokens }
  = resolveThinkingConfig(settings);
// 互斥:设置了 effort 时不再使用 maxThinkingTokens
const maxThinkingTokens = (alwaysThinkingEnabled && !normalizedReasoningEffort)
  ? configuredMaxThinkingTokens
  : undefined;
```

**Step C**: `buildQueryOptions(...)` 调用之后:

```js
if (normalizedReasoningEffort) {
  options.effort = normalizedReasoningEffort;
  console.log('[DEBUG] Set SDK effort:', normalizedReasoningEffort);
}
```

#### 8.2.3 `sendMessageWithAttachments` 函数(第 453 行)

第 485 行附近(`resolveThinkingConfig` 之后)做相同处理,从 `stdinData?.reasoningEffort` 读取:

```js
const normalizedReasoningEffort = normalizeReasoningEffort(stdinData?.reasoningEffort || null);
const { alwaysThinkingEnabled, maxThinkingTokens: configuredMaxThinkingTokens }
  = resolveThinkingConfig(settings);
const maxThinkingTokens = (alwaysThinkingEnabled && !normalizedReasoningEffort)
  ? configuredMaxThinkingTokens
  : undefined;
```

`buildQueryOptions(...)` 之后:

```js
if (normalizedReasoningEffort) {
  options.effort = normalizedReasoningEffort;
}
```

### 8.3 远程 ai-bridge-server

按拍板决策:远程服务复用本仓库 `ai-bridge/`,因此 **Phase 5 改动一次,远程模式自动生效**,无需单独同步。

---

## 九、Commit 拆分建议

建议拆 4 个 commit,每个 commit 落地后均可独立编译、Codex 回归路径不受影响:

| # | Commit Title | 涉及 Phase |
|---|---|---|
| 1 | `feat(types): add max effort + Claude model gating constants` | Phase 1 |
| 2 | `feat(ui): wire ReasoningSelect for Claude with per-model gating` | Phase 2 + Phase 3 |
| 3 | `feat(jvm): plumb reasoningEffort through Claude SDK bridge` | Phase 4 |
| 4 | `feat(ai-bridge): apply reasoning effort to Claude SDK options` | Phase 5 |

---

## 十、验证矩阵

| 场景 | 期望行为 |
|---|---|
| 首次启动(无持久化) | 默认 effort = `max`(useState 初值);若当前模型不支持 max,fallback effect 自动回退 |
| Codex + 任意模型 | 选择器显示 4 档(无 max);提交后 stdin `reasoningEffort` 字段已写入(回归) |
| Claude + Opus 4.7 | 5 档全亮(low/medium/high/xhigh/max) |
| Claude + Opus 4.6 / Sonnet 4.6 | 4 档(low/medium/high/max),无 xhigh |
| Claude + Opus 4.6[1m] | 同上 |
| Claude + Haiku 4.5 / 老模型 | 选择器隐藏 |
| Claude 模型切换导致 value 不在可用列表 | 自动 fallback,无报错 |
| `alwaysThinking=true` 且选了 effort | ai-bridge 日志:`maxThinkingTokens: undefined`、`options.effort: <value>` |
| `alwaysThinking=true` 且未选 effort(为 null) | `maxThinkingTokens` 生效、`options.effort` 未设置 |
| 远程模式 + Claude + 选 max | 远端 ai-bridge-server 日志显示 `reasoningEffort: 'max'` 进 SDK options |
| Tab 切换 / 重启 IDE | effort 持久化恢复(`TabStateService.reasoningEffort` 已在位) |
| 切换 Claude ↔ Codex | effort state 共享(同一个值),由组件侧档位过滤决定可选 |

---

## 十一、回滚预案

若某阶段出现回归:

- Phase 1-3(纯前端)出问题 → revert webview commit,Java/Node 端字段缺失只会被忽略
- Phase 4(Java)出问题 → revert Java commit,Phase 1-3 已部署也无副作用(`reasoningEffort` 字段就不发出去)
- Phase 5(Node)出问题 → revert Node commit,字段会被 Node 端忽略,Claude SDK 行为退回旧版

---

## 十二、风险点汇总

1. **默认值 `'max'` 的合理性**:首次会话即用最高档,token 消耗大;但 fallback effect 会在不支持 max 的模型上自动降级,功能正确性有保证。
2. **`xhigh` label 变化**(老 `'Max'` → 新 `'XHigh'`):无 CHANGELOG 说明,老用户可能感觉"Max 档突然多了一档",依赖产品文档解释。
3. **共享 state 的语义**:Claude 选了 `max`,切到 Codex 后会被档位过滤强行降级回 `xhigh` 或更低,需要 ReasoningSelect 的 fallback effect 兜底,这部分逻辑务必经过手动验证。
4. **互斥逻辑**:`maxThinkingTokens` 和 `options.effort` 不能同时下发给 Claude Agent SDK,Phase 5 的互斥分支是核心,出错会导致 SDK 报错。
5. **模型 id 字符串匹配**:`EFFORT_SUPPORTED_CLAUDE_MODELS` 等 Set 必须与 Fork 实际使用的 model id 完全一致,否则可见性逻辑会失效。开工前先核对 `CLAUDE_MODELS`。

---

## 十三、开工 checklist

- [ ] 校对 Fork 的 `CLAUDE_MODELS` 模型 id 字符串(尤其 1M-context 后缀写法)
- [ ] Phase 1:`types.ts` 加 Set/类型/数组项 → 跑 `tsc --noEmit` 确认类型通过
- [ ] Phase 2:`ReasoningSelect` 改造 + `ButtonArea` 透传 + `useModelProviderState` 默认值
- [ ] Phase 3:10 个 i18n 文件追加 `max` key、修正 `xhigh` label
- [ ] webview 自测:`pnpm dev` 或对应命令启动,人工切模型/切 provider 验证档位
- [ ] Phase 4:Java 5 文件依次扩展,跑 `./gradlew compileJava` 确认编译
- [ ] Java 自测:在 IDE 内开会话、选 Claude+Opus 4.7+max,观察 daemon 日志中 stdin JSON 含 `"reasoningEffort":"max"`
- [ ] Phase 5:`message-sender.js` 互斥逻辑 + `claude-channel.js` 解构
- [ ] 端到端自测:覆盖验证矩阵 11 个场景
- [ ] 远程模式专项验证:在远程 ai-bridge-server 部署同步代码后,跑一次 max 档 Claude 请求
