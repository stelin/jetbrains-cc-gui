# Supervisor Pair Agent 编码方案

**日期**: 2026-05-21
**作者**: Claude Code 协作
**状态**: 待实施
**关联文档**: 配套讨论纪要（双 Agent Pair / 监工模式）

---

## 一、背景

CC GUI 当前是「单会话 + 主 AI」模式，遇到两类痛点：

1. **API 异常终止编码** —— SDK 流断了就断了，需要人工重试。
2. **多步方案需人工逐步喂** —— 设计方案拆 N 步后，每步靠人确认才能推进。

本方案引入 **Supervisor Pair Agent**：在原有主会话之外，可选附加一个或多个「监工 Agent」，与主 AI **平行运行**于同一个会话上下文中：

- 主 AI（Worker）专注执行
- Supervisor（Coordinator + Reviewer）专注调度 + 监工 + 按 skills 规范审查
- 双屏 UI 实时呈现两 Agent 协作过程，长任务可无人值守

---

## 二、决策记录（已拍板）

| 项 | 决策 |
|---|---|
| 文档解析方式 | **纯 LLM 理解**，无模板约束 |
| 进度记录 | **旁路 `progress.json`**，不污染设计文档 |
| 子文档加载策略 | 主文档全载，子文档由 AI 用 `Read` 工具按需读 |
| 设计文档生命周期 | 启动 Pair 时 **snapshot 锁定**，运行中文档变更不影响 |
| 主 AI 偏离方案 | **方案 = 标准答案**，主 AI 不能偏离，只能 escalate 给用户 |
| Supervisor Persona 配置 | IDE 设置 tab 配置多个，默认全自动 |
| 并发 | 1 plan : 1 active pair（不支持同一 plan 并行多 pair） |
| 多文档语法 | `@path.md` 标注语法 |
| 设计文档路径 | 不限路径，IDE 能读取即可 |
| 质量门 | **双门**：verify（命令）+ skill review（LLM） |
| 多 Supervisor 协同 | **主调度 + 协审** 模式（第一个选的为主调度，其余为协审） |
| Supervisor 数量上限 | **3 个**（双屏右侧可装下） |
| 配置存储 | `~/.codemoss/supervisor-agents.json`，复用 Mutagen 同步 |
| 失败上限 | verify/review 各 3 次后升级，可配 |
| 默认模型 | Supervisor 默认 Haiku，可在 Persona 内覆盖 |
| 关闭 Supervisor 后 | 双屏 → 单屏，**保留监工历史**可后查 |

---

## 三、架构总览

```
┌─────────────────────────  插件侧（webview + Java）  ─────────────────────────┐
│                                                                              │
│  ┌────────────────────┐         ┌─────────────────────────────────────────┐ │
│  │  设置面板           │         │  会话窗口（PairLayout）                  │ │
│  │  SupervisorSection │         │  ┌──── 左屏 ────┬──── 右屏 ─────────┐  │ │
│  │  - 名称            │         │  │ Main AI      │ Supervisor View   │  │ │
│  │  - 描述（规范）    │         │  │ (existing    │ ┌─ 主调度 ───────┐ │  │ │
│  │  - 模型            │         │  │  ChatView)   │ │ 全自动监工      │ │  │ │
│  │  - 设为默认        │         │  │              │ ├─ 协审 ─────────┤ │  │ │
│  └─────────┬──────────┘         │  │              │ │ Go 规范守卫    │ │  │ │
│            │                    │  │              │ ├─ 协审 ─────────┤ │  │ │
│  写 ~/.codemoss/                │  │              │ │ 安全审查员      │ │  │ │
│  supervisor-agents.json         │  │              │ └────────────────┘ │  │ │
│            │                    │  └──────┬───────┴─────────┬──────────┘  │ │
│            ▼                    │         │                 │             │ │
│  ┌──────────────────────┐       │         │ user input      │ user input  │ │
│  │ SupervisorAgentMgr   │       │         ▼                 ▼             │ │
│  │ (Java settings/)     │       │  SessionSendService     SupervisorChat  │ │
│  └──────────────────────┘       │         │                 │             │ │
│                                 └─────────┼─────────────────┼─────────────┘ │
│                                           │                 │               │
│                                           ▼                 ▼               │
│                              ┌──────── PairSessionManager（新） ─────┐     │
│                              │  - 创建/销毁 pair                       │     │
│                              │  - 维护 progress.json                  │     │
│                              │  - 持有 ActionRouter                    │     │
│                              │  - Event Bus（订阅主 AI 事件转给 Sup）  │     │
│                              └──────┬──────────────────┬──────────────┘     │
│                                     │                  │                     │
│                                     ▼                  ▼                     │
│                            ClaudeSDKBridge      SupervisorBridge（新）       │
│                              （现有）              （新）                     │
│                                     │                  │                     │
└─────────────────────────────────────┼──────────────────┼─────────────────────┘
                                      │ NDJSON           │ NDJSON
                                      ▼                  ▼
                              ┌─────────────────── ai-bridge daemon ──────────┐
                              │  ┌── claude-channel ─┐ ┌── supervisor-channel ┐│
                              │  │ (existing)        │ │ (新增)               ││
                              │  └───────────────────┘ └──────────────────────┘│
                              │           │                     │              │
                              │           │  events ──────►     │              │
                              │           │  (Event Bus 中转)   │              │
                              │           │  ◄────── actions    │              │
                              └────────────────────────────────────────────────┘
```

---

## 四、配置模型

### 4.1 `supervisor-agents.json` 持久化结构

存放路径：`~/.codemoss/supervisor-agents.json`（跟现有 `agent.json` 同级，复用 `ConfigPathManager`）

```json
{
  "version": 1,
  "defaultAgentId": "full-auto",
  "agents": {
    "full-auto": {
      "id": "full-auto",
      "name": "全自动监工",
      "description": "你是任务调度监工...（system prompt 主体）",
      "model": "claude-haiku-4-5-20251001",
      "applicableProjectTypes": ["*"],
      "createdAt": 1716275000000,
      "updatedAt": 1716275000000
    },
    "go-strict": {
      "id": "go-strict",
      "name": "Go 规范守卫",
      "description": "你是 Go 严格审查员...",
      "model": "claude-haiku-4-5-20251001",
      "applicableProjectTypes": ["go"]
    },
    "security": {
      "id": "security",
      "name": "安全审查员",
      "description": "你是安全审查员...",
      "model": "claude-sonnet-4-6"
    }
  }
}
```

### 4.2 Pair 运行时数据布局

```
{projectBasePath}/.cc-gui/pairs/pair_{uuid8}/
├── main.jsonl              ← 主 AI 会话历史（复用现有 session 存储格式）
├── supervisor_{id}.jsonl   ← 每个 Supervisor 一个会话历史
├── plan.md                 ← snapshot 自原设计文档（启动时锁定）
├── progress.json           ← 步骤进度 + 计数
├── key_events.idx          ← 异常索引（仅 escalate / auto_recover）
└── report.md               ← 完成后生成
```

### 4.3 `progress.json` 结构

```json
{
  "pair_id": "pair_a1b2c3d4",
  "plan_source": "docs/plans/2026-05-21-get-user-by-email.md",
  "started_at": "2026-05-21T22:00:00Z",
  "status": "running",
  "supervisors": [
    {"id": "full-auto", "role": "coordinator"},
    {"id": "go-strict", "role": "reviewer"}
  ],
  "steps": [
    {
      "index": 1,
      "title": "Model 层",
      "status": "done",
      "verify_attempts": 1,
      "review_attempts": 1,
      "completed_at": "..."
    },
    {"index": 2, "title": "DAO 层", "status": "running", ...}
  ],
  "stats": {
    "auto_recover_count": 0,
    "escalate_count": 0,
    "review_reject_count": 0
  }
}
```

---

## 五、改动总览

| Phase | 层 | 模块 | 新增/改动文件数 | 风险 |
|---|---|---|---|---|
| 1 | 配置数据层 | Java settings/ | 3 新增 + 1 改动 | 低 |
| 2 | 配置 UI | webview settings/ | 5 新增 + 2 改动 | 低 |
| 3 | 会话开启入口 | webview ChatInputBox/ | 3 新增 + 2 改动 | 低 |
| 4 | 双屏布局 | webview ChatView/ | 4 新增 + 1 改动 | 中 |
| 5 | ai-bridge | supervisor-channel | 3 新增 + 1 改动 | 中 |
| 6 | Pair 业务逻辑 | Java session/ | 5 新增 + 2 改动 | 高 |
| 7 | Action Router + Event Bus | Java session/ | 3 新增 | 中 |
| 8 | Skill Review 集成 | Java skill/ + ai-bridge | 2 新增 + 1 改动 | 中 |
| 9 | i18n | webview i18n/ | 10 改动（仅 key） | 低 |
| 10 | 远程同步集成 | Java remotesync/ | 1 改动 | 低 |

---

# PART A · IDE UI 编码

## 六、Phase 1 — 配置数据层（Java）

### 6.1 新增 `SupervisorAgentManager.java`

**路径**：`src/main/java/com/github/claudecodegui/settings/SupervisorAgentManager.java`

**职责**：管理 `supervisor-agents.json` 的 CRUD，跟现有 `AgentManager` 解耦（避免与 Claude SDK 的 subagent 概念混淆）。

**关键方法**：

```java
package com.github.claudecodegui.settings;

public class SupervisorAgentManager {
    private static final Logger LOG = Logger.getInstance(SupervisorAgentManager.class);
    private final Gson gson;
    private final ConfigPathManager pathManager;

    public SupervisorAgentManager(Gson gson, ConfigPathManager pathManager) {
        this.gson = gson;
        this.pathManager = pathManager;
    }

    /** 读取全部配置 */
    public JsonObject readConfig() throws IOException { ... }

    /** 写入并触发文件 watch 通知 */
    public void writeConfig(JsonObject config) throws IOException { ... }

    /** 获取单个 agent */
    public JsonObject getAgent(String id) throws IOException { ... }

    /** 增/改 agent */
    public void upsertAgent(JsonObject agent) throws IOException { ... }

    /** 删除 agent（保护：不能删除默认） */
    public void deleteAgent(String id) throws IOException { ... }

    /** 设为默认 */
    public void setDefault(String id) throws IOException { ... }

    /** 预置 3 个开箱即用 agent（首次启动时） */
    public void ensureDefaults() throws IOException { ... }
}
```

### 6.2 改动 `ConfigPathManager.java`

加入 `getSupervisorAgentsFilePath()` 方法：

```java
public Path getSupervisorAgentsFilePath() {
    return getCodemossDir().resolve("supervisor-agents.json");
}
```

### 6.3 新增 `SupervisorAgentHandler.java`

**路径**：`src/main/java/com/github/claudecodegui/handler/SupervisorAgentHandler.java`

仿照现有 `AgentHandler.java`，提供给 webview 的 IPC 接口：

```
supervisorAgent:list      → 返回所有 agents
supervisorAgent:get       → 返回单个
supervisorAgent:upsert    → 增/改
supervisorAgent:delete    → 删除
supervisorAgent:setDefault → 设默认
```

### 6.4 预置 3 个开箱即用 Agent

首次启动时由 `SupervisorAgentManager.ensureDefaults()` 创建，内容见 §17（附录 A · 预置 Persona 模板）。

---

## 七、Phase 2 — 配置 UI（webview · 设置面板）

### 7.1 在 `SettingsSidebar` 新增菜单项

**文件**：`webview/src/components/settings/SettingsSidebar/index.tsx`

在 `RemoteSyncSection` 菜单项**之后**插入：

```tsx
{
  id: 'supervisor',
  label: t('settings.supervisor.title'), // "Supervisor 监工"
  icon: 'codicon-eye-watch',
}
```

### 7.2 新增 `SupervisorSection/` 组件目录

**新增文件**：

```
webview/src/components/settings/SupervisorSection/
├── index.tsx                  ← 入口（List + Detail 双栏布局）
├── AgentList.tsx              ← 左侧列表（含搜索、+ 添加、设为默认徽章）
├── AgentDetailForm.tsx        ← 右侧详情表单（名称/描述/模型）
├── DescriptionEditor.tsx      ← 描述字段（带语法高亮的 markdown 文本框）
└── styles.module.less
```

### 7.3 `index.tsx` 主体结构

```tsx
export const SupervisorSection: React.FC = () => {
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [defaultId, setDefaultId] = useState<string | null>(null);

  useEffect(() => {
    window.ideBridge.invoke('supervisorAgent:list').then(res => {
      setAgents(res.agents);
      setDefaultId(res.defaultAgentId);
    });
  }, []);

  const handleSave = async (agent: SupervisorAgent) => {
    await window.ideBridge.invoke('supervisorAgent:upsert', agent);
    // refresh
  };

  return (
    <div className="supervisor-section">
      <AgentList
        agents={agents}
        defaultId={defaultId}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onAdd={() => setSelectedId('__new__')}
      />
      <AgentDetailForm
        agent={agents.find(a => a.id === selectedId)}
        isDefault={selectedId === defaultId}
        onSave={handleSave}
        onDelete={(id) => window.ideBridge.invoke('supervisorAgent:delete', { id })}
        onSetDefault={(id) => window.ideBridge.invoke('supervisorAgent:setDefault', { id })}
      />
    </div>
  );
};
```

### 7.4 `AgentDetailForm.tsx` 字段

| 字段 | 控件 | 必填 | 说明 |
|---|---|---|---|
| 名称 | `<input>` | 是 | ≤ 30 字符 |
| 描述 | `<DescriptionEditor>` | 是 | Markdown 文本，无字符上限 |
| 模型 | `<select>` | 否 | 默认 Haiku，可选 Haiku/Sonnet |
| 设为默认 | `<button>` | - | 当前 default 时显示徽章 |

### 7.5 `DescriptionEditor.tsx` 增强

- 多行文本框（textarea-autosize）
- 支持 `@path.md` 高亮（点击可预览引用文件）
- 模板插入：右上角"插入模板"下拉，选 A/B/C 模板（见 §17）

### 7.6 i18n key 新增

`webview/src/i18n/locales/{zh,en}/settings.json`：

```json
{
  "supervisor": {
    "title": "Supervisor 监工",
    "agentList": "已配置的 Agent",
    "newAgent": "新建 Agent",
    "fields": {
      "name": "名称",
      "description": "描述（角色 + 规范定义）",
      "model": "模型",
      "setAsDefault": "设为默认"
    },
    "templates": {
      "insertTemplate": "插入模板",
      "roleOnly": "纯职责式",
      "roleWithRules": "职责 + 规范",
      "complete": "完整版"
    }
  }
}
```

---

## 八、Phase 3 — 会话开启入口（webview · ChatInputBox）

### 8.1 `ContextBar.tsx` 扩展

**文件**：`webview/src/components/ChatInputBox/ContextBar.tsx`

#### 8.1.1 新增 props

```tsx
interface ContextBarProps {
  // ... 现有 props
  selectedSupervisors?: SupervisorAgent[];
  onOpenSupervisorPicker?: () => void;
  onCloseSupervisor?: (id: string) => void;
}
```

#### 8.1.2 在"文件上下文"右侧渲染按钮

参考用户截图位置（`📎 ⊙10% 📁 文件上下文` 这一行末尾）：

```tsx
{selectedSupervisors && selectedSupervisors.length > 0 ? (
  <SupervisorChip
    agents={selectedSupervisors}
    onRemove={onCloseSupervisor}
  />
) : (
  <button
    className="supervisor-toggle"
    onClick={onOpenSupervisorPicker}
    title={t('chatInput.supervisor.openTooltip')}
  >
    <i className="codicon codicon-eye-watch" />
    <span>{t('chatInput.supervisor.label')}</span>
    <i className="codicon codicon-chevron-down" />
  </button>
)}
```

### 8.2 新增 `SupervisorChip.tsx`

显示已启用 Supervisor，单 Supervisor 显示名称，多个时显示 `名称 + N`，hover 展开列表。

### 8.3 新增 `SupervisorPickerDialog.tsx`

**路径**：`webview/src/components/ChatInputBox/SupervisorPickerDialog.tsx`

```tsx
export const SupervisorPickerDialog: React.FC<Props> = ({
  open, agents, defaultId, onConfirm, onCancel
}) => {
  const [checked, setChecked] = useState<Set<string>>(
    defaultId ? new Set([defaultId]) : new Set()
  );

  const handleConfirm = () => {
    // 第一个勾选的为 coordinator，其余为 reviewer
    const ordered = agents.filter(a => checked.has(a.id));
    onConfirm(ordered);
  };

  return (
    <Dialog open={open} onClose={onCancel}>
      <h2>{t('chatInput.supervisor.pickerTitle')}</h2>
      <ul>
        {agents.map(a => (
          <li key={a.id}>
            <Checkbox
              checked={checked.has(a.id)}
              onChange={() => toggle(a.id)}
              label={a.name}
              badge={a.id === defaultId ? 'default' : null}
            />
            <p className="muted">{a.description.slice(0, 80)}...</p>
          </li>
        ))}
      </ul>
      <p className="hint">
        {t('chatInput.supervisor.pickerHint')} {/* 最多选 3 个，第一个为主调度 */}
      </p>
      <button onClick={openManager}>⚙ {t('common.manage')}</button>
      <button onClick={onCancel}>{t('common.cancel')}</button>
      <button onClick={handleConfirm} disabled={checked.size === 0 || checked.size > 3}>
        {t('common.enable')}
      </button>
    </Dialog>
  );
};
```

### 8.4 `ChatInputBox.tsx` 状态扩展

注入新 state：

```tsx
const [selectedSupervisors, setSelectedSupervisors] = useState<SupervisorAgent[]>([]);
const [pickerOpen, setPickerOpen] = useState(false);

// 启用 Supervisor 时通知 Java 启动 Pair
const handleSupervisorConfirm = async (agents: SupervisorAgent[]) => {
  setSelectedSupervisors(agents);
  setPickerOpen(false);
  await window.ideBridge.invoke('pair:start', {
    sessionId: currentSessionId,
    supervisors: agents.map(a => a.id),
  });
};
```

---

## 九、Phase 4 — 双屏布局（webview · ChatView）

### 9.1 新增 `PairLayout/` 组件

**路径**：`webview/src/components/ChatView/PairLayout/`

```
PairLayout/
├── index.tsx               ← 容器（响应式：宽屏双屏，窄屏堆叠/抽屉）
├── SupervisorPane.tsx      ← 右侧整体 Pane
├── SupervisorSubPanel.tsx  ← 右侧内部单个 Supervisor 视图
├── ActionCard.tsx          ← ACTION JSON 卡片渲染
├── EscalateDialog.tsx      ← 升级决策弹窗
└── styles.module.less
```

### 9.2 `index.tsx`

```tsx
export const PairLayout: React.FC<Props> = ({ children, supervisors }) => {
  const isPairActive = supervisors.length > 0;

  if (!isPairActive) return <>{children}</>;

  return (
    <div className="pair-layout">
      <div className="pair-left">{children}</div>
      <div className="pair-divider" />
      <SupervisorPane supervisors={supervisors} />
    </div>
  );
};
```

### 9.3 `SupervisorPane.tsx`

按角色拆分主调度 + 协审：

```tsx
const coordinator = supervisors.find(s => s.role === 'coordinator');
const reviewers = supervisors.filter(s => s.role === 'reviewer');

return (
  <div className="supervisor-pane">
    {coordinator && (
      <SupervisorSubPanel agent={coordinator} role="主调度" expanded />
    )}
    {reviewers.map(r => (
      <SupervisorSubPanel key={r.id} agent={r} role="协审" />
    ))}
  </div>
);
```

### 9.4 `SupervisorSubPanel.tsx`

订阅来自 Java 的 supervisor 事件流（NDJSON），渲染为：

- 💭 思考段（普通气泡）
- ✓/⚠️/⚡ 状态标记（图标+文字）
- ```ACTION``` 块 → 渲染为 `<ActionCard>`（绿色卡片，不显示 JSON 原文）
- 升级请求 → 弹出 `<EscalateDialog>`

### 9.5 `ActionCard.tsx` 渲染规则

| ACTION | 卡片样式 |
|---|---|
| `inject_prompt` | 绿色 → "已注入指令到主 AI"，附 prompt 摘要，箭头动画指向左屏 |
| `retry_with_hint` | 黄色 → "自愈中（等 Xs）"，倒计时 |
| `approve_and_continue` | 灰色 → "推进 step N+1" |
| `escalate_to_human` | 红色 → "请用户决策"，弹窗 |
| `wait` | 隐藏（避免噪声） |

### 9.6 修改 `ChatView/index.tsx`

外层包一个 `<PairLayout>`：

```tsx
return (
  <PairLayout supervisors={selectedSupervisors}>
    <div className="chat-view">
      {/* 原有 ChatView 内容不动 */}
    </div>
  </PairLayout>
);
```

### 9.7 主 AI 侧渲染 Supervisor 注入的 prompt

复用现有消息列表，但对 `metadata.fromSupervisor === true` 的消息：

- 头像换成 🤖 Supervisor 标识
- 气泡颜色蓝绿色（区别于用户的灰色气泡）
- hover 显示"由 Supervisor `{name}` 注入"

**关键文件**：`webview/src/components/MessageItem/` 增加 `MessageOrigin` 字段渲染。

---

## 十、Phase 9 — i18n

新增以下 key（中英文均补）：

```
chatInput.supervisor.label            "Supervisor"
chatInput.supervisor.openTooltip      "启用监工 Agent"
chatInput.supervisor.pickerTitle      "选择 Supervisor"
chatInput.supervisor.pickerHint       "最多选 3 个，第一个为主调度"
chatInput.supervisor.activeChip       "{count} 个 Supervisor 已启用"

settings.supervisor.title             "Supervisor 监工"
settings.supervisor.agentList         "已配置的 Agent"
settings.supervisor.newAgent          "新建 Agent"
... (见 7.6)

pairLayout.coordinator                "主调度"
pairLayout.reviewer                   "协审"
pairLayout.escalate.title             "Supervisor 请求用户决策"
pairLayout.escalate.confirmAccept     "接受"
pairLayout.escalate.confirmReject     "拒绝"
pairLayout.escalate.confirmManual     "手动处理"
```

---

## 十一、Phase 10 — 远程同步集成（修订后：no-op）

**结论**：本 Phase 不需要代码改动。

**原因**：`MutagenSyncService` 同步的是**项目代码目录**到远端 ai-bridge-server，目标是远程开发场景下让远端能访问本地代码。它**不同步** `~/.codemoss/` 用户配置目录。

`supervisor-agents.json` 跟 `agent.json`、`prompt.json`、`config.json` 一样位于 `~/.codemoss/`，属于**机器级用户配置**，跟项目代码同步无关。

如果未来要支持跨机器同步 Supervisor 配置，需要独立设计（如 dotfile 同步 / Cloud Sync），不在 Mutagen 体系内。

---

# PART B · 业务代码编码

## 十二、Phase 5 — ai-bridge `supervisor-channel.js`

### 12.1 新增文件

**路径**：`ai-bridge/channels/supervisor-channel.js`

**职责**：管理 Supervisor 的 Claude SDK Query，接收事件、产出 ACTION。

```javascript
import { query } from '@anthropic-ai/claude-agent-sdk';

const supervisorSessions = new Map(); // sessionId -> Supervisor instance

export async function startSupervisorSession({ pairId, supervisorId, systemPrompt, planContent, model }) {
  const session = {
    pairId,
    supervisorId,
    model,
    history: [],
    iterator: null,
  };
  // System prompt 拼接：persona + plan + 标准监工指令
  const fullSystemPrompt = buildSystemPrompt(systemPrompt, planContent);
  session.iterator = query({
    prompt: '',  // 启动时不发用户消息，等第一个事件
    options: {
      model: model || 'claude-haiku-4-5-20251001',
      systemPrompt: fullSystemPrompt,
      // 不需要 tools，supervisor 是纯判断者
      allowedTools: [],
    },
  });
  supervisorSessions.set(`${pairId}:${supervisorId}`, session);
  return session;
}

export async function postEventToSupervisor({ pairId, supervisorId, event }) {
  const key = `${pairId}:${supervisorId}`;
  const session = supervisorSessions.get(key);
  if (!session) throw new Error(`No supervisor session ${key}`);

  // 把 event 摘要塞入 prompt
  const summary = buildEventSummary(event);
  // 调用 SDK 多轮接口（具体 API 参照现有 persistent-query-service.js）
  const response = await sendToSupervisor(session, summary);
  return parseSupervisorResponse(response);  // { naturalText, action }
}

export async function stopSupervisorSession({ pairId, supervisorId }) { ... }

function buildSystemPrompt(persona, planContent) {
  return `${persona}

# 当前任务方案
${planContent}

# 输出格式
你的输出包含两部分：
1. 自然语言段（💭 ✓ → ⚡ ⚠️ 等符号 + 思考描述）
2. 一个 \`\`\`ACTION 代码块包裹的 JSON

ACTION 必须是以下之一：
- inject_prompt: { prompt: "..." }
- retry_with_hint: { wait_seconds: N, prompt: "..." }
- approve_and_continue: {}
- escalate_to_human: { question, choices: [], context_files: [] }
- wait: {}

每次决策只产出一个 ACTION。`;
}
```

### 12.2 修改 `ai-bridge/daemon.js`

在 daemon 启动时 import 并注册 supervisor 相关命令：

```javascript
import {
  startSupervisorSession,
  postEventToSupervisor,
  stopSupervisorSession,
} from './channels/supervisor-channel.js';

// 在命令分发处添加
case 'supervisor.start':
  result = await startSupervisorSession(params);
  break;
case 'supervisor.postEvent':
  result = await postEventToSupervisor(params);
  break;
case 'supervisor.stop':
  result = await stopSupervisorSession(params);
  break;
```

### 12.3 新增事件摘要器

**路径**：`ai-bridge/services/supervisor/event-summarizer.js`

负责把主 AI 的原始 NDJSON 事件**裁剪 + 摘要**后再喂给 Supervisor：

- `tool_use` 单条 → 不进 Supervisor（仅汇总进 turn_end）
- `content_delta` → 不进
- `turn_end` → 汇总该 turn 内的 tool_use 列表、modified_files、duration、是否成功
- `error` → 原样进 + 分类标签（transient/permanent/auth/overflow）
- `idle_timeout` → 进

输出格式见 §15.3。

### 12.4 ACTION 解析器

**路径**：`ai-bridge/services/supervisor/action-parser.js`

从 Supervisor 输出中抽 `\`\`\`ACTION ... \`\`\`` 代码块并 JSON.parse，校验 schema，失败时降级为 `wait` action 并打告警日志。

---

## 十三、Phase 6 — Java Pair 业务层

### 13.1 新增 `PairSessionManager.java`

**路径**：`src/main/java/com/github/claudecodegui/session/PairSessionManager.java`

**职责**：单例 service（`@Service.PROJECT`），管理一个 Project 内的所有 active pair。

```java
@Service(Service.Level.PROJECT)
public final class PairSessionManager implements Disposable {
    private final Map<String, PairSession> activePairs = new ConcurrentHashMap<>();
    private final Project project;

    public PairSessionManager(Project project) {
        this.project = project;
    }

    /** 启动一个 pair（webview 调用） */
    public PairSession startPair(StartPairParams params) {
        // 1. 解析 planPath → 读取 + snapshot 到 .cc-gui/pairs/pair_xxx/plan.md
        // 2. 初始化 progress.json
        // 3. 启动 coordinator Supervisor (via SupervisorBridge.start)
        // 4. 启动 N 个 reviewer Supervisor
        // 5. 注册 EventBus 订阅（主 AI 事件 → 转发给所有 Supervisor）
        // 6. 持久化 PairSession 状态
        // 7. 返回 pairId 给 webview
    }

    public void stopPair(String pairId) { ... }

    public PairSession getPair(String pairId) { ... }
}
```

### 13.2 新增 `PairSession.java`（POJO）

```java
public class PairSession {
    private String pairId;
    private String mainSessionId;
    private String planSnapshotPath;
    private List<SupervisorInstance> supervisors;
    private PairProgress progress;
    private Path pairDir;  // .cc-gui/pairs/pair_xxx/
    // ... getters/setters
}

public class SupervisorInstance {
    private String agentId;
    private String role;  // "coordinator" | "reviewer"
    private SupervisorBridge bridge;
}
```

### 13.3 新增 `SupervisorBridge.java`

**路径**：`src/main/java/com/github/claudecodegui/bridge/SupervisorBridge.java`

**职责**：跟 `ai-bridge` daemon 的 `supervisor-channel` 通信，复用现有 daemon 进程的 NDJSON 协议。

```java
public class SupervisorBridge {
    private final ProcessManager processManager;
    private final String pairId;
    private final String supervisorId;

    public void start(String systemPrompt, String planContent, String model) throws IOException {
        processManager.sendCommand(JsonObject.of(
            "method", "supervisor.start",
            "params", JsonObject.of(
                "pairId", pairId,
                "supervisorId", supervisorId,
                "systemPrompt", systemPrompt,
                "planContent", planContent,
                "model", model
            )
        ));
    }

    public CompletableFuture<SupervisorAction> postEvent(JsonObject event) { ... }

    public void stop() { ... }
}
```

### 13.4 新增 `ProgressManager.java`

**路径**：`src/main/java/com/github/claudecodegui/session/pair/ProgressManager.java`

读写 `progress.json`，提供：

```java
public void markStepDone(int stepIndex);
public void incrementAttempt(int stepIndex, String kind /* verify|review */);
public void recordAutoRecover();
public void recordEscalate(String reason);
public PairProgress snapshot();
```

### 13.5 新增 `PlanSnapshotter.java`

**职责**：启动 pair 时，把设计文档 snapshot 到 pair 目录。

```java
public class PlanSnapshotter {
    public Path snapshot(Path planSource, Path pairDir) throws IOException {
        Path target = pairDir.resolve("plan.md");
        Files.copy(planSource, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
```

---

## 十四、Phase 7 — Action Router + Event Bus

### 14.1 新增 `EventBus.java`

**路径**：`src/main/java/com/github/claudecodegui/session/pair/EventBus.java`

**职责**：订阅主 AI 的 NDJSON 事件流，过滤分级后转发给所有 Supervisor。

```java
public class EventBus {
    private final String pairId;
    private final List<SupervisorInstance> supervisors;
    private final EventFilter filter;

    /** 在 ClaudeMessageHandler / CodexMessageHandler 中调用 */
    public void onMainEvent(JsonObject event) {
        if (!filter.shouldForward(event)) return;
        JsonObject summary = filter.summarize(event);
        for (SupervisorInstance sup : supervisors) {
            sup.bridge.postEvent(summary).thenAccept(action -> {
                ActionRouter.dispatch(pairId, sup.agentId, action);
            });
        }
    }
}
```

### 14.2 新增 `EventFilter.java`

实现 §12.3 的过滤规则：

```java
public boolean shouldForward(JsonObject event) {
    String type = event.get("event").getAsString();
    return Set.of("turn_end", "error", "idle_timeout", "off_plan_detected").contains(type);
}
```

### 14.3 新增 `ActionRouter.java`

**路径**：`src/main/java/com/github/claudecodegui/session/pair/ActionRouter.java`

```java
public class ActionRouter {
    private final SessionSendService sendService;
    private final ProgressManager progress;
    private final NotificationService notifier;

    public void dispatch(String pairId, String supervisorId, SupervisorAction action) {
        switch (action.getType()) {
            case "inject_prompt":
                injectAsUser(pairId, action.getPrompt(), supervisorId);
                break;
            case "retry_with_hint":
                scheduleRetry(pairId, action.getWaitSeconds(), action.getPrompt());
                break;
            case "escalate_to_human":
                notifier.showEscalateDialog(action);
                break;
            case "approve_and_continue":
                progress.markStepDone(action.getStepIndex());
                break;
            case "wait":
                /* no-op */
                break;
            default:
                LOG.warn("Unknown action type: " + action.getType());
        }
    }

    /** 关键：以"伪用户输入"通道注入 prompt，保留 metadata 标记 */
    private void injectAsUser(String pairId, String prompt, String supervisorId) {
        SendMessageParams params = SendMessageParams.builder()
            .text(prompt)
            .metadata(Map.of(
                "fromSupervisor", true,
                "supervisorId", supervisorId
            ))
            .build();
        sendService.send(params);
    }
}
```

### 14.4 改动 `ClaudeMessageHandler.java` 和 `CodexMessageHandler.java`

在事件出口处，多发一份到 `EventBus`：

```java
public void onMessage(JsonObject event) {
    // ... 原有逻辑 ...

    // 新增：通知 PairSession（如果当前 session 在某个 pair 里）
    PairSessionManager pairMgr = project.getService(PairSessionManager.class);
    PairSession pair = pairMgr.findByMainSession(currentSessionId);
    if (pair != null) {
        pair.getEventBus().onMainEvent(event);
    }
}
```

### 14.5 改动 `SessionSendService.java`

接受 metadata 字段，存到消息历史里：

```java
public class SessionSendService {
    public void send(SendMessageParams params) {
        // 现有逻辑保持
        // 把 params.metadata 一并存入 message history JSON
    }
}
```

---

## 十五、Phase 8 — Skill Review 双门集成

### 15.1 流程整合

`turn_end` 事件到达后，**调度型 Supervisor 触发 VERIFY，VERIFY 通过后触发 SKILL REVIEW**：

```
turn_end → Supervisor(coordinator):
   1. 判断步骤完成度
   2. 若完成 → Java ActionRouter 触发 VerifyExecutor
   3. VerifyExecutor 跑 plan 里写的 verify 命令（go build / go test）
      - 失败 → ActionRouter 注入"修复 verify 失败"prompt 给主 AI → 等下一轮
      - 通过 ↓
   4. ActionRouter 触发所有 reviewer Supervisor：
      - 每个 reviewer 收到 step_completed 事件 + 当步 diff
      - 各自产出 review 结果（pass / fail + issues）
   5. coordinator 汇总 reviewer 结果：
      - 全 pass → inject_prompt 推进下一步
      - 任一 fail → inject_prompt 反馈具体问题给主 AI
```

### 15.2 新增 `VerifyExecutor.java`

**路径**：`src/main/java/com/github/claudecodegui/session/pair/VerifyExecutor.java`

```java
public class VerifyExecutor {
    public CompletableFuture<VerifyResult> verify(String verifyCommand, Path projectDir) {
        // 复用现有 ProcessManager 跑 shell 命令
        // 30 秒超时
        // 返回 stdout/stderr/exitCode
    }
}
```

### 15.3 事件摘要 → Supervisor 的格式

```
## EVENT [T+{elapsed}s | turn_end]
step: {N} ({title})
tool_uses:
  - Read {path} ✓
  - Edit {path} ✓
modified_files_in_plan: [...]
modified_files_off_plan: [...]
duration_ms: {N}
verify_status: pass | fail | skipped | pending
verify_output: |
  ...
review_pending_count: {N}
```

### 15.4 Reviewer 输入格式

```
## REVIEW_REQUEST
step: {N}
diff:
  --- a/dao/user_dao.go
  +++ b/dao/user_dao.go
  @@ ...
expected_outputs:
  - {从 plan 里抽出的本步预期}

请按你的规范审查，输出 ACTION:
- approve_and_continue: 通过
- escalate_to_human: 严重违规直接升级
- inject_prompt: 把问题反馈给主 AI 让其修正
```

### 15.5 关联 skills 怎么注入到 reviewer

`SupervisorAgentManager.upsertAgent()` 不强制 skills 字段。skills 由 Supervisor 的 **description 字段自身**承载 —— 用户在描述里写「按 Uber Go Style Guide / 项目分层规范审查」即可，Supervisor LLM 自己理解。

如果用户想精细化，可在描述里 `@.claude/skills/golang-standards.md` 引用，Supervisor 通过 Read 工具按需读（但默认 Supervisor 不开 Read 工具——若需要请在 `supervisor-channel.js` 启用 `allowedTools: ['Read']`）。

---

## 十六、实施顺序（推荐 Sprint 划分）

| Sprint | Phase | 验收标准 |
|---|---|---|
| Sprint 1（1 周）| Phase 1 + Phase 2 | 设置面板可增/改/删 Supervisor，配置文件正确写入 |
| Sprint 2（1 周）| Phase 3 + Phase 9 | ContextBar 出现 ▽ 按钮，选择对话框工作，但启用后还无效果 |
| Sprint 3（1.5 周）| Phase 5 + Phase 6 | ai-bridge `supervisor.start/postEvent` 能跑通，单 Supervisor 能产出 ACTION |
| Sprint 4（1.5 周）| Phase 7 | EventBus + ActionRouter 打通，`inject_prompt` 能影响主 AI 行为 |
| Sprint 5（1 周）| Phase 4 | 双屏 UI 渲染，Supervisor 输出可视化 |
| Sprint 6（1 周）| Phase 8 | VERIFY + REVIEW 双门串通 |
| Sprint 7（0.5 周）| Phase 10 + 联调 | 远程同步、回归测试、夜跑长任务实测 |

**总计 ~7.5 周**单人工作量。

**MVP 最小可演示版本**：完成 Sprint 1 + 2 + 3 + 4 即可演示（不含双屏 UI，先用 console log 验证逻辑），约 5 周。

---

## 十七、附录 A · 预置 Persona 模板

`SupervisorAgentManager.ensureDefaults()` 写入：

### 17.1 `full-auto` — 全自动监工（默认）

```
你是任务调度监工，负责按设计方案 plan.md 推进主 AI 完成编码任务。

# 职责
1. 监听主 AI 事件流，识别每步完成、异常、跑偏
2. 步骤完成且 verify 通过后，自动推进下一步
3. API 限流（429/5xx）自愈重试，最多 3 次
4. 主 AI 修改了 plan 外的文件 → 立即升级给用户
5. verify 或 review 连续失败 3 次 → 升级给用户

# 原则
- 方案就是标准答案，主 AI 不能擅自偏离
- 升级给用户的频率越低越好，但不可纵容偏离
- 只输出一个 ACTION，不输出多余解释

# 输出
每次决策按以下格式：
💭 观察：（一句话）
✓ 判断：（一句话）
→ 行动：

```ACTION
{"action": "...", "payload": {...}}
```
```

### 17.2 `go-strict` — Go 规范守卫

```
你是 Go 代码严格审查员。

# 强制规范
- 错误处理：所有外部错误必须用 errs.New(ErrXxx) 包装，不能直接返回 sql.ErrNoRows 等原始错误
- 上下文传递：DAO/Service 层函数签名必须以 (ctx context.Context, ...) 开头
- 分层：Logic 不能直接调 SQL，必须经 DAO；Controller 不能直接调 DAO
- 命名：导出函数大驼峰，包内变量小驼峰，常量全大写
- 日志：禁止 fmt.Println，必须用项目的 log 包

# 职责
每收到 REVIEW_REQUEST 事件后：
- 扫描 diff，逐条比对强制规范
- 全部通过 → approve_and_continue
- 发现违规 → inject_prompt，反馈中必须包含：违反的规范名、文件:行号、建议改法

# 输出格式
参照监工指令。
```

### 17.3 `security` — 安全审查员

```
你是代码安全审查员。

# 重点关注
- SQL 注入：禁止字符串拼接 SQL，必须用占位符
- 命令注入：禁止 exec.Command 拼接用户输入
- 敏感信息：禁止把 password/token/key 写入日志
- 输入验证：所有外部输入必须校验长度、格式
- 错误信息：不能把内部细节（堆栈、SQL）返回给客户端

# 职责
每收到 REVIEW_REQUEST 事件后：
- 扫描 diff 中的安全风险点
- 无风险 → approve_and_continue
- 有风险 → escalate_to_human（安全问题不通过反馈解决，直接让人审）
```

---

## 十八、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Haiku 判断不稳定，误推进 | 主 AI 跑偏 | 加 verify 兜底；Supervisor 描述里强调"保守原则" |
| Supervisor 跟主 AI 死循环互推 | 卡死 | ActionRouter 同步骤同类型 action ≤ 3 次熔断 |
| inject_prompt 污染消息历史 | 回看混乱 | metadata.fromSupervisor 标记，UI 区分渲染 |
| 长任务 token 爆炸 | 跑不完 | 加 milestone compaction（每 N 轮汇总，旧 round 淘汰） |
| 用户改了原设计文档 | 跑出来的代码不是用户想要的 | snapshot 锁定 + file watcher 检测原文件改动后 pause 提示 |
| daemon 崩溃导致 Supervisor 状态丢失 | pair 断流 | 启动时检查 `progress.json`，可从最近 step 恢复（M2 实现） |
| 多 Supervisor 资源占用 | 慢/贵 | 上限 3 个；reviewer 默认 Haiku，仅 Sonnet 兜底复审 |

---

## 十九、测试要点

### 19.1 单元测试

- `SupervisorAgentManager` CRUD（含异常路径）
- `EventFilter` 过滤规则（每种 event type）
- `ActionParser` 容错（非法 JSON、未知 action）
- `ProgressManager` 并发写 `progress.json`

### 19.2 集成测试

- 单 Supervisor + 简单 4 步 plan，全部自动推进完成
- 模拟 API 429 → 验证自愈
- 模拟 off-plan 修改 → 验证升级
- 双 Supervisor（coordinator + reviewer），reviewer 报错 → 反馈给主 AI 修复

### 19.3 端到端

- 在 IDE 真实跑一遍：配置 Supervisor → 写 plan.md → 启动 pair → 完成全部步骤
- 远程模式跑同样流程（验证 Supervisor 数据通过 daemon 跑在远端 ai-bridge-server 也工作）

### 19.4 性能测试

- 50 步 plan 长任务，验证：
  - Supervisor token 消耗 < $1（Haiku 价格）
  - 总耗时 < 单 Supervisor 跑同样任务 + 30%
  - 内存稳定，无泄漏

---

## 二十、向后兼容

- 未启用 Supervisor 的会话**行为完全不变**（PairSessionManager 仅在 Supervisor 启用时介入）
- `supervisor-agents.json` 不存在 = 老插件升级后第一次启动 → 自动 `ensureDefaults()` 创建 3 个预置
- 远程同步：老的 sync 配置不含 `supervisor-agents.json` → MutagenSyncService 启动时自动补加
- 消息历史中的 `metadata.fromSupervisor` 字段：老客户端读历史时 fallback 渲染为普通消息（无视该字段）

---

## 二十一、附录 B · 设计文档模板（用户写 plan.md 时的推荐结构）

```markdown
# {任务标题}

## 目标
{一段话说清楚做什么，期望产出}

## 关联规范（可选）
- @docs/specs/coding-standards.md
- @docs/specs/api-conventions.md

## 文件范围
- 允许修改：model/, dao/, logic/, controller/
- 禁止修改：middleware/, main.go, .env, go.mod

## 步骤

### 步骤 1: Model 层定义
- 在 model/user_req.go 中定义 GetUserByEmailReq
- 在 model/user_resp.go 中定义 GetUserByEmailResp
- 验证: go build ./model/...

### 步骤 2: DAO 层查询方法
- 在 dao/user_dao.go 中实现 GetUserByEmail(ctx, email)
- 必须用 prepared statement，无字符串拼接
- 验证: go test ./dao/... -run TestGetUserByEmail

### 步骤 3: Logic 层业务逻辑
- 在 logic/user_logic.go 中实现业务方法
- 包含邮箱格式校验
- 验证: go test ./logic/...

### 步骤 4: Controller 层路由
- 在 controller/user_controller.go 中注册路由
- 验证: go build ./...

## 完成标准
- 4 步全部完成
- 所有 verify 命令通过
- skill review 无未解决问题
```

注：模板**非强制**，纯 LLM 理解任何 markdown 结构。这里只是给用户参考，降低写设计文档的认知负担。

---

## 二十二、总结

本方案在 `jetbrains-cc-gui` 现有架构上**纯叠加**，不修改 Claude/Codex SDK 核心调用链，不破坏远程模式，复用现有的 Mutagen 远程同步机制。

最终用户能力：

1. **配置层**：IDE 设置面板新增 Supervisor tab，可配置多个监工 Persona
2. **会话层**：在 ContextBar 一键启用 1~3 个 Supervisor
3. **运行层**：双屏 UI 实时呈现主 AI + Supervisor 协作
4. **质量层**：双门（verify + skill review）确保每步符合规范
5. **可观测层**：完整审计链（双 jsonl + progress.json + report.md），长任务夜跑后可回看

落地后，CC GUI 成为**业界首个 IDE 内置双 Agent 实时对话式监工**的工具，区别于 Devin（单 Agent 黑盒）、Cursor（异步后台 Agent）、Aider（无监工）。
