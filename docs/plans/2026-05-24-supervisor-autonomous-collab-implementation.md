# Supervisor 自主协作模式 编码方案

**日期**: 2026-05-24
**作者**: Claude Code 协作
**状态**: 待实施
**关联文档**:
- `docs/plans/2026-05-21-supervisor-pair-agent-implementation.md` (Pair Agent 基础)
- `docs/plans/2026-05-23-supervisor-monitor-rotation-implementation.md` (Monitor + Rotation)
- `docs/supervisor/supervisor-owns-agent-design.md` (supervisor 自派子 agent 讨论稿)
- `src/main/resources/supervisor/design-supervisor.md` (方案 supervisor 提示词)
- `src/main/resources/supervisor/code-supervisor.md` (编码 supervisor 提示词)

---

## 一、背景

### 1.1 当前体系的四个核心痛点

在 Monitor + Rotation 落地后,运行中又暴露出更深层的问题:

| 痛点 | 现象 | 根因(含代码位置) |
|---|---|---|
| **P1 supervisor 看不见主 AI 文本输出** | 主 AI 跑 370s 输出大段 manifest YAML,supervisor 永远只看到 toolUses 列表,不知道主 AI 写了什么 | `ClaudeMessageHandler.java:1313-1342` `publishTurnEndIfPair` 不传 `assistantText`;`event-summarizer.js:182-212` `formatTurnEnd` 只渲染 tools + files |
| **P2 子 agent 是 supervisor 的盲区** | 主 AI 派 Task 子 agent 跑 370s,期间无 turn_end,supervisor 完全失明;子 agent "自作主张"改 memory 文件,supervisor 看到 modified 列表才发现 | 主 AI runtime 没注册 `SubagentStop` hook;`recordTurnToolUse` 把 Bash 的 path 误计入 modifiedFiles |
| **P3 inject_prompt 无 ack 雪崩** | supervisor 下发指令,主 AI busy 时主 AI 没接到;supervisor 等不到响应再下发,daemon 端两个 `executeTurn` 并发 enqueue 同一 `runtime.inputStream`,消息错位 | `App.tsx:89` `injectHandlerRef` 直接调 `executeMessage` 绕过 `handleSubmit` 队列;`SessionHandler.java:104` `handleSendMessage` 没检查 `state.isBusy()` |
| **P4 supervisor 当邮递员浪费 turn** | supervisor 想 review 代码 / 提 manifest → 必须 `inject_prompt` 让主 AI 派子 agent → 主 AI 转手 YAML 回报;3 跳变 1 跳本应足够 | 工具白名单硬编码 `SUPERVISOR_READ_TOOLS = ['Read', 'Glob', 'Grep']`(`supervisor-channel.js:44`),没有 Agent 工具 |

### 1.2 演进目标

把当前 **"双 agent 协作辅助 + 人在回路反应式"** 模型,升级为 **"双 agent 自治协作 + 人异步审计"** 模型,达到:

1. supervisor ↔ 主 AI 跨边界通讯走**结构化协议**(任务派单 + 工作汇报 + ack);双方内部子 agent 走 SDK 原生
2. 主 AI 子 agent 通过 `SubagentStop` hook 让 supervisor 看见(摘要 + transcript path,不强制 Read)
3. supervisor 自己持有 `Agent` 工具,review / 信息收集任务**自派子 agent**,不再绕主 AI
4. supervisor **全自主**按 plan/design 文档推进,A/B/C1/C2 类决策自决,**仅 C3 硬阻断停**
5. 所有自治决策记录到 L2 ring buffer + 实时 UI 时间线,人异步审计
6. cost budget 兜底:跑超 token/时间/子 agent 调用自动暂停
7. 主 AI 自报 `selfAssessment`,减轻 supervisor review 负担 + 提升准确度
8. 周期性 RE-PLAN 检查点:每 5 step / 遇 alert 后 supervisor 自评并调整 plan

---

## 二、决策记录(已拍板)

### 2.1 协议层决策

| 编号 | 项 | 决策 |
|---|---|---|
| D1 | ack 协议传输 | **IPC**(`[MAIN_ACK]` NDJSON 行),响应快 |
| D2 | spill-to-file 阈值 | **8KB**(支持 env 覆盖) |
| D3 | 主 AI 子 agent transcript | **不 mirror**,只在 `SubagentStop` payload 带原 path |
| D4 | `SubagentStop` 是否 urgent | **不 urgent**,跟下个 30s tick 一起 |
| D5 | directiveId 超时未 ack | **5min 报告 supervisor**,supervisor 自决重派/escalate |
| D6 | supervisor Tasks 面板更新源 | **从 `[SUPERVISOR_MSG]` stream 筛 `Agent` 工具的 tool_use/tool_result**,不新增 NDJSON |

### 2.2 自治控制层决策

| 编号 | 项 | 决策 |
|---|---|---|
| F1 | escalate 行为重命名 | 新增 `record_alert` action;旧 `escalate_to_human` 标 deprecated(保留 API 兼容,行为改为 `record_alert`) |
| F2 | retry 上限 | step 内 inject_prompt 失败 **3 次** → skip 该 step + 标 blocked + 继续下一个 |
| F3 | plan 不存在怎么办 | supervisor 新增 `save_plan` MCP 工具,daemon 写到 `.claude/pair/<pairId>/plan.md` |
| F4 | UI 决策时间线 + autonomy 开关 | **都加** |
| F5 | 子 agent 失败 | 沿用 5 次指数退避;5 次失败 → C1 决策(confidence=low 跳过 review),不停 plan |

### 2.3 supervisor 子 agent 决策

| 编号 | 项 | 决策 |
|---|---|---|
| R1 | 子 agent 类型 | **general-purpose**(不预定义),提示词约束角色,简单合理 |
| R2 | supervisor 自己开 Bash | **不开**,需要 Bash 一律走子 agent |
| R3 | 子 agent 并发 | **不限制**(SDK 原生支持) |
| R4 | 子 agent context 上限 | **不处理**(SDK 自管 compact) |
| R5 | 旧会话兼容 | **不兼容,一次切**(用户手动结束运行中旧会话) |

### 2.4 Top 3 优化(全部纳入本期)

| 编号 | 项 | 决策 |
|---|---|---|
| T1 | 周期性 RE-PLAN | 每 5 step / record_alert 后 supervisor 自评 plan,可调整 |
| T2 | cost budget | `pair_start` 时支持 `budget: { maxTokens, maxDurationMs, maxSteps, maxSubagentCalls }`;80% 预警,100% 自动暂停 |
| T3 | 主 AI self-assessment | `report_turn_completion` 必填 `selfAssessment: { confidence, concerns[], suggestedReview }` |

---

## 三、架构总览

### 3.1 四层架构

```
┌─ Layer 4: 自治决策层 ─────────────────────────────────────────┐
│   - 决策矩阵 (A/B/C1/C2/C3 自决边界)                          │
│   - 自治工作循环 (init plan → execute step → review → next)   │
│   - 周期性 RE-PLAN (T1)                                       │
│   - Cost Budget (T2)                                          │
│   - record_alert (高优记录 + fallback 自决,不阻塞)              │
│   - 完工判定 + COMPLETION_REPORT.md                            │
│   - 决策日志 ring buffer (50 条容量 + 24h 时窗)                │
├─ Layer 3: 协作协议层(跨边界) ───────────────────────────────┤
│   主 AI → supervisor:                                          │
│     [TURN_REPORT]    - 工作汇报(deliverables / verifications / │
│                                  selfAssessment)              │
│     [SUBAGENT_STOP]  - 主 AI 子 agent 完成(lastMsg + path)    │
│     [MAIN_ACK]       - 主 AI 收到 / 完成 directive ack         │
│   supervisor → 主 AI:                                          │
│     [SUPERVISOR_ACTION] inject_prompt (任务派单 + directiveId) │
│   公共:                                                        │
│     spill-to-file:.claude/pair/<pairId>/{main_turns,         │
│                                          directives}/         │
├─ Layer 2: 执行层(SDK 原生)──────────────────────────────────┤
│   - supervisor 自己派子 agent (走 SDK Agent 工具)              │
│   - 主 AI 自己派子 agent (Task 工具,SDK 原生 transcript 隔离)  │
│   - 子 agent 与 parent 之间走 SDK 内部 message stream          │
│   - 不强加结构化协议                                            │
├─ Layer 1: 基础设施 ──────────────────────────────────────────┤
│   - daemon IPC (已有 NDJSON)                                  │
│   - SDK hooks (PreCompact 已有,新增 SubagentStop)             │
│   - L2Store (持久化 decisions + plan progress)                │
│   - .claude/pair/<pairId>/ 项目级目录(mutagen 兼容)            │
└────────────────────────────────────────────────────────────────┘
```

### 3.2 协作模式心智模型

```
supervisor                            主 AI
(像 PM / 架构师)                     (像 senior engineer)
   │                                     │
   │  ① 任务派单 (directiveId=d1)        │
   │    {objective, deliverables,        │
   │     acceptance}                     │
   │  ─────────────────────────────────► │
   │                                     │
   │                                     │  自决怎么实现
   │                                     │  (要不要派子 agent
   │                                     │   是它内部的事)
   │                                     │
   │   [MAIN_ACK] {d1, received}         │
   │ ◄─────────────────────────────────  │
   │                                     │
   │                                     │  ★ 派子 agent 时
   │                                     │    [SUBAGENT_STOP] 上报
   │                                     │    supervisor 旁观
   │                                     │
   │  ② 工作汇报 [TURN_REPORT]           │
   │    {summary, deliverables[],        │
   │     verifications[],                │
   │     selfAssessment: {confidence}}   │
   │ ◄─────────────────────────────────  │
   │                                     │
   │  self review (按 selfAssessment 分诊):│
   │   - confidence=high + allPass → 直接通过 │
   │   - confidence=low → 派 code_reviewer  │
   │   - 简单 → 自己 Read 验证               │
   │                                     │
   │  decisionAppend(verdict=pass)        │
   │  → planProgress.step=done            │
   │                                     │
   │  ③ 下一 step 派单 (directiveId=d2)   │
   │  ─────────────────────────────────► │
   │                                     │
   └──────────────── 循环 ───────────────┘
                     │
                     │ 终止条件:
                     │  - 所有 step done → COMPLETION_REPORT.md
                     │  - cost budget 100% → PAUSED_REPORT.md
                     │  - C3 硬阻断 → PAUSED_REPORT.md
```

### 3.3 文件中介范围

`.claude/pair/<pairId>/` 项目级目录(mutagen 同步,plugin 端可见):

| 子路径 | 内容 | 写者 | 读者 |
|---|---|---|---|
| `plan.md` | supervisor `save_plan` 生成的步骤计划 | supervisor(MCP 工具) | supervisor 自读;主 AI 可参考 |
| `main_turns/turn_<n>.md` | 主 AI `report_turn_completion` 的 raw assistantText(>8KB spill) | daemon(主 AI 端) | supervisor Read |
| `directives/dir_<id>.md` | supervisor inject_prompt 长指令(>8KB spill) | daemon(supervisor 端) | 主 AI Read |
| `decisions/` | (可选,后期)决策日志归档 | Java L2Store | UI |
| `COMPLETION_REPORT.md` | 完工总结 | Java(自治终止时) | 用户 |
| `PAUSED_REPORT.md` | 暂停报告(预算用完 / C3 阻断) | Java | 用户 |

**不写文件**:
- supervisor 自己派的子 agent transcript(SDK 自管,只 supervisor session 内访问)
- 主 AI 派的子 agent transcript(SDK 自管,supervisor 通过 `SubagentStop` payload 拿 path 后按需 Read)

---

## 四、协议契约(开发对照基准)

> 本节是开发的唯一真相源。所有 daemon / Java / 提示词 / UI 都按此节对接,接口签名不允许私下变更——要改先改本节。

### 4.1 NDJSON 协议汇总

所有 NDJSON 行由 daemon `stdout` 写出,Java `SupervisorBridge` 等通过 request-id-tagging 路由。

#### 4.1.1 主 AI → supervisor

```jsonc
// [TURN_REPORT] —— 主 AI 一个 turn 完成后通过 report_turn_completion MCP 工具发出
{
  "type": "turn_report",
  "sessionId": "<main-ai-session-id>",
  "turnId": "t_1716540000_abc123",
  "directiveId": "d_xxx",                  // 关联到 supervisor 派单(初始 turn 无)
  "ts": 1716540000000,
  "payload": {
    "summary": "已完成 Step 3:实现 UserService.createUser",
    "deliverables": [
      {
        "path": "user/service/user_service.go",  // 相对项目根
        "change": "新增 createUser 方法,含参数校验"
      }
    ],
    "verifications": [
      { "command": "go build ./...",       "pass": true,  "stderrTail": null },
      { "command": "go test ./user/...",   "pass": true,  "stderrTail": null }
    ],
    "selfAssessment": {                       // ★ T3 必填
      "confidence": "high",                   // high | medium | low
      "concerns": [
        "createUser 没加并发测试"
      ],
      "suggestedReview": "focus on user_dao.go:42-58"
    },
    "subagentSummary": {
      "count": 2,
      "totalDurationMs": 23000
    },
    "durationMs": 45000,
    "spilledPath": ".claude/pair/p1/main_turns/turn_007.md"   // assistantText >8KB 时
  }
}

// [SUBAGENT_STOP] —— 主 AI 端 SubagentStop hook 触发
{
  "type": "subagent_stop",
  "sessionId": "<main-ai-session-id>",
  "turnId": "t_xxx",                       // 触发该子 agent 的主 AI turn
  "ts": 1716540000000,
  "payload": {
    "agentId": "sub_abc",
    "agentType": "general-purpose",         // SDK 给的 agent_type
    "taskSubject": "扫描 deps 找未用包",     // 主 AI 派子 agent 时 Task.subject
    "lastAssistantMessage": "已扫描完成,发现 3 个未引用包: ...",
    "transcriptPath": "/Users/stelin/.claude/projects/<hash>/agent_xxx.jsonl",
    "parentToolUseId": "toolu_xxx",
    "durationMs": 35000
  }
}

// [MAIN_ACK] —— 主 AI 收到或完成 directive
{
  "type": "main_ack",
  "sessionId": "<main-ai-session-id>",
  "directiveId": "d_xxx",
  "ts": 1716540000000,
  "status": "received" | "applied" | "failed",
  "details": null | {                       // status=failed 时
    "reason": "context overflow",
    "willRetry": false
  }
}

// [SUBAGENT_START] —— 可选,主 AI 派子 agent 时(本期不上,workload 大)
```

#### 4.1.2 supervisor → 主 AI

```jsonc
// [SUPERVISOR_ACTION] —— supervisor emit_action 触发(已有,扩展 directiveId)
{
  "type": "supervisor_action",
  "pairId": "p1",
  "supervisorId": "code-supervisor",
  "turnId": "supervisor_turn_xxx",
  "ts": 1716540000000,
  "naturalText": "",
  "reasoningText": "",
  "action": {
    "action": "inject_prompt",
    "reason": "派单 Step 4",
    "directiveId": "d_1716540000_xyz",      // ★ 新增,必填
    "payload": {
      "kind": "task_assignment",            // ★ 新增,枚举 task_assignment | review_feedback | acknowledgement | bootstrap
      "objective": "Step 4: 实现 UserController.createUser HTTP handler",
      "context": {
        "previousStep": "Step 3 已完成 service 层 createUser",
        "relatedFiles": ["user/service/user_service.go"]
      },
      "expectedDeliverables": [
        "user/controller/user_controller.go 新增 CreateUser handler",
        "通过 go build"
      ],
      "acceptanceCriteria": [
        "请求体 JSON 解析失败返回 400",
        "service 层错误透传"
      ],
      "inlinePrompt": "...短指令文本(<8KB)" | null,
      "spilledPath": ".claude/pair/p1/directives/dir_d_xxx.md" | null
    }
  }
}
```

#### 4.1.3 公共 / 已有事件(沿用,不改)

- `[SUPERVISOR_MSG]`, `[SUPERVISOR_HEALTH]`, `[CONTEXT_USAGE]`, `[STATE_UPDATE]`, `[COMPACT_BOUNDARY]`, `[HANDOFF_DOC]`, `[PRE_COMPACT]`, `[SUPERVISOR_INTERRUPT_RESULT]` —— 沿用,不动

### 4.2 MCP 工具签名

#### 4.2.1 supervisor 端工具(在 supervisor-channel.js 的 mcpServers 注册)

```javascript
// emit_action —— 已有,扩展 directiveId 字段(必填于 inject_prompt)
mcp__supervisor__emit_action({
  action: 'inject_prompt' | 'retry_with_hint' | 'approve_and_continue' |
          'escalate_to_human' | 'record_alert' |   // ★ 新增 record_alert
          'request_amendment' | 'wait',
  reason: string,
  directiveId?: string,                         // ★ inject_prompt / retry_with_hint 必填
  payload: {
    // 当 action === 'inject_prompt':
    kind?: 'task_assignment' | 'review_feedback' | 'acknowledgement' | 'bootstrap',
    objective?: string,
    context?: { previousStep?, relatedFiles? },
    expectedDeliverables?: string[],
    acceptanceCriteria?: string[],
    inlinePrompt?: string,                      // 二选一
    spilledPath?: string,                       // 二选一
    
    // 当 action === 'record_alert':
    severity: 'warn' | 'alert',
    category: 'C1' | 'C2',
    fallbackChoice: string,                     // supervisor 决定的回退方案
    
    // 当 action === 'escalate_to_human' (deprecated, 兼容用):
    // 自动转换为 record_alert + severity='alert' + category='C2'
    
    // 其它已有字段保留
  }
})

// update_state —— 已有,decisionAppend schema 扩展
mcp__supervisor__update_state({
  decisionAppend?: {
    ts?: number,
    action: string,
    reason?: string,
    payload?: any,
    result?: string,
    confidence?: 'high' | 'medium' | 'low',
    
    // ★ 新增字段(自治模式)
    category?: 'A' | 'B' | 'C1' | 'C2' | 'C3',
    severity?: 'info' | 'warn' | 'alert',
    candidates?: Array<{ option: string, score?: number }>,
    chosenCandidate?: string,
    evidence?: Array<{
      kind: 'file_read' | 'subagent' | 'main_turn' | 'verification',
      path?: string,
      lines?: string,
      agentId?: string,
      turnId?: string,
      output?: string                            // 摘要,≤500 字
    }>,
    stepId?: number,
    autoMode?: boolean                           // true = 自治决策, false = 人工触发
  },
  // 其它已有字段沿用
})

// save_plan —— ★ 新增(F3)
mcp__supervisor__save_plan({
  content: string,                              // plan.md 完整文本
  reason: string,                               // 为什么生成 plan
  source: 'design_doc' | 'replan' | 'manual'    // T1 的 replan 也走这个
})
// 返回: { saved: true, path: '.claude/pair/<pairId>/plan.md', version: 1 }
// 版本号自增,每次 save 自动备份到 .claude/pair/<pairId>/plan_v<n>.md.bak
```

#### 4.2.2 主 AI 端工具(★ 新增)

```javascript
// report_turn_completion —— ★ 新增(T3)
// 注册在主 AI runtime 的 mcpServers 里
mcp__main__report_turn_completion({
  summary: string,                              // 1-2 句任务级摘要
  deliverables: Array<{
    path: string,                               // 相对项目根
    change: string,                             // ≤200 字
    confidence?: 'high' | 'medium' | 'low'      // 此产出的自信度
  }>,
  verifications?: Array<{
    command: string,
    pass: boolean,
    stderrTail?: string                         // 失败时附 ≤1KB 错误尾部
  }>,
  selfAssessment: {                             // ★ 必填
    confidence: 'high' | 'medium' | 'low',
    concerns: string[],                         // 主 AI 自己觉得不踏实的点
    suggestedReview?: string                    // 建议 supervisor 重点 review
  }
})
// 返回: { recorded: true, turnId: '...' }
// daemon 收到后发 [TURN_REPORT] NDJSON 给 Java
```

### 4.3 spill-to-file 规范

```javascript
// daemon utils/spill-to-file.js
export function maybeSpill({ content, kind, pairId, name }) {
  const SPILL_THRESHOLD = parseInt(process.env.PAIR_SPILL_THRESHOLD_BYTES) || 8192;
  if (Buffer.byteLength(content, 'utf8') <= SPILL_THRESHOLD) {
    return { inline: content, spilledPath: null };
  }
  const dir = path.join(getProjectCwd(), '.claude/pair', pairId,
    kind === 'turn' ? 'main_turns' : 'directives');
  fs.mkdirSync(dir, { recursive: true });
  const filename = `${name}.md`;
  fs.writeFileSync(path.join(dir, filename), content, 'utf8');
  return { inline: null, spilledPath: path.posix.join('.claude/pair', pairId,
    kind === 'turn' ? 'main_turns' : 'directives', filename) };
}
```

调用点:
- `supervisor-channel.js` 的 `inject_prompt` 处理(payload.inlinePrompt vs payload.spilledPath)
- `claude-channel.js` 或新的 `main-ai-tools.js` 的 `report_turn_completion`(assistantText)

### 4.4 L2 决策 ring buffer 调整

```java
// L2State.java
public static class Decisions {
  public List<Decision> recent = new ArrayList<>();    // ring,容量 + 时窗双限
  public static final int MAX_CAPACITY = 100;          // ★ 从 50 升到 100
  public static final long MAX_WINDOW_MS = 24 * 3600_000L;  // 24h
  
  public void append(Decision d) {
    long now = System.currentTimeMillis();
    recent.add(d);
    // 容量限制
    while (recent.size() > MAX_CAPACITY) recent.remove(0);
    // 时窗限制
    recent.removeIf(x -> now - x.ts > MAX_WINDOW_MS);
  }
}

public static class Decision {
  public long ts;
  public String action;
  public String reason;
  public Object payload;
  public String result;
  public String confidence;
  
  // ★ 新增
  public String category;            // A | B | C1 | C2 | C3
  public String severity;            // info | warn | alert
  public List<Candidate> candidates;
  public String chosenCandidate;
  public List<Evidence> evidence;
  public Integer stepId;
  public Boolean autoMode;
}
```

### 4.5 Cost Budget 接口

```javascript
// pair_start IPC payload 扩展
{
  // ... 已有字段
  budget: {
    maxTokens: 500_000,         // supervisor + 主 AI 累计
    maxDurationMs: 3_600_000,   // 1h
    maxSteps: 50,
    maxSubagentCalls: 20
  } | null                       // null = 无预算限制(沿用旧行为)
}
```

```java
// Java PairBudgetTracker.java (★ 新增)
public class PairBudgetTracker {
  private final PairBudget budget;
  private final AtomicLong tokenUsed = new AtomicLong(0);
  private final AtomicInteger stepsCompleted = new AtomicInteger(0);
  private final AtomicInteger subagentCalls = new AtomicInteger(0);
  private final long startedAt;
  
  public BudgetStatus check() {
    return new BudgetStatus(
      ratio('tokens', tokenUsed.get(), budget.maxTokens),
      ratio('duration', System.currentTimeMillis() - startedAt, budget.maxDurationMs),
      ratio('steps', stepsCompleted.get(), budget.maxSteps),
      ratio('subagents', subagentCalls.get(), budget.maxSubagentCalls)
    );
  }
  
  public boolean shouldWarn() {
    return check().anyAbove(0.80);    // 80% → warn 推 supervisor
  }
  
  public boolean shouldPause() {
    return check().anyAbove(1.00);    // 100% → 强制暂停
  }
}
```

事件:
- `budget_warning` event(80% 时一次性推给 supervisor,supervisor 自决"缩减剩余 step")
- `budget_exceeded` event(100% 时 Java 调 `pair.pause()`,写 PAUSED_REPORT.md)

### 4.6 项目级 `.claude/pair/<pairId>/` 目录布局

```
<projectCwd>/
└── .claude/
    └── pair/
        └── <pairId>/                            ← pairId 是稳定身份
            ├── plan.md                          ← supervisor save_plan 生成
            ├── plan_v1.md.bak                   ← 每次 save 自动备份
            ├── plan_v2.md.bak
            ├── main_turns/
            │   ├── turn_001.md                  ← 大 assistantText spill
            │   └── turn_002.md
            ├── directives/
            │   ├── dir_d_abc.md                 ← 大 instruction spill
            │   └── dir_d_xyz.md
            ├── COMPLETION_REPORT.md             ← 完工时写
            └── PAUSED_REPORT.md                 ← 暂停时写
```

- 目录由 Java `PairSession.start()` 时创建(已有 cwd 解析逻辑)
- mutagen 自动同步(项目级目录)
- pair stop 时**不删除**(留给用户审计 / 复盘)
- 后台清理由用户/IDE 主动触发,不自动

---

## 五、Layer 4 自治控制层详细设计

### 5.1 决策矩阵(A/B/C1/C2/C3)

```
┌──────┬──────────────────────────────────┬────────────────────────────┐
│ 类别  │ 场景例子                          │ 行为                       │
├──────┼──────────────────────────────────┼────────────────────────────┤
│ A    │ 变量命名 / 函数内部实现顺序 /        │ 自决                       │
│      │ 错误信息文案 / import 顺序          │ 记录(可选,默认记)         │
├──────┼──────────────────────────────────┼────────────────────────────┤
│ B    │ struct 拆合 / error vs panic /     │ 自决                       │
│      │ 日志级别 / 锁实现 /                 │ 必须记录 + confidence       │
│      │ 辅助函数边界                        │                            │
├──────┼──────────────────────────────────┼────────────────────────────┤
│ C1   │ plan 与现状不符(版本 / 路径 / 已存在  │ 自决                       │
│      │ 文件冲突)/ 推断业务规则空缺            │ 必须记录 + confidence=low  │
│      │                                   │ + 加大 review 力度          │
├──────┼──────────────────────────────────┼────────────────────────────┤
│ C2   │ 数据库表结构 / API 签名 / 分层归属 / │ 拒绝主动执行                │
│      │ 引入新依赖 / 改 plan 步骤顺序        │ 记 high-severity alert     │
│      │                                   │ skip 该 step + 继续下一个   │
│      │                                   │ (不暂停整 plan)            │
├──────┼──────────────────────────────────┼────────────────────────────┤
│ C3   │ API quota 耗尽 / 磁盘满 /          │ 真正暂停 plan               │
│      │ SDK 持续崩溃 / 文件系统不可写        │ 写 PAUSED_REPORT.md         │
│      │                                   │ 等用户重启会话               │
└──────┴──────────────────────────────────┴────────────────────────────┘
```

> 注:旧 code-supervisor.md 中"C 类必须 escalate_to_human"在自治模式下拆为 C1/C2/C3,前两者自决留痕,后者真停。

### 5.2 自治工作循环(伪代码)

```
启动 (pair_start)
  ├─► Java 创建 .claude/pair/<pairId>/
  ├─► supervisor SDK session 启动,加载提示词
  ├─► 第一个事件: bootstrap composite_summary(含 user_input 或 plan 路径)

主循环 (supervisor 推理 turn):
  while True:
    收到 composite_summary 或 turn_report:
    
    ┌─ 阶段 A: 初始化 / RE-PLAN 检查 ─────────────────┐
    │  if plan 不存在 OR 完整性不足:                   │
    │    派 general-purpose 子 agent(读 design 文档,  │
    │    分析当前 codebase),输出步骤计划              │
    │    → save_plan(content, source='design_doc')   │
    │    → 进入"派单"阶段                              │
    │                                                 │
    │  if 已完成 step 数 % 5 == 0 OR 上次有 alert:    │
    │    [T1] RE-PLAN:派子 agent 重新评估 plan        │
    │    if 建议变更:                                  │
    │      save_plan(new_content, source='replan')   │
    │      update_state(decisionAppend(action='replan'))│
    └─────────────────────────────────────────────────┘
    
    ┌─ 阶段 B: 派单 / 等汇报 ──────────────────────────┐
    │  next_step = pickNextStep(plan, planProgress)   │
    │  if next_step == null:                          │
    │    → 进入"完工"阶段                              │
    │                                                 │
    │  directive = buildTaskAssignment(next_step)     │
    │  emit_action(inject_prompt,                     │
    │              directiveId=new,                   │
    │              payload=directive)                 │
    │                                                 │
    │  等下个 turn(可能多个 composite 直到 turn_report):│
    │    if 收到 [TURN_REPORT] with directiveId:      │
    │      进入"review"阶段                            │
    │    if 5min 无 ack:                              │
    │      [D5] directive_lost event → 自决重派 or    │
    │           record_alert(C1)                     │
    └─────────────────────────────────────────────────┘
    
    ┌─ 阶段 C: review / 推进 ──────────────────────────┐
    │  report = parseTurnReport(...)                  │
    │                                                 │
    │  [T3] 按 selfAssessment 分诊:                    │
    │    if report.selfAssessment.confidence=='high'  │
    │       AND report.verifications.allPass:         │
    │      → 直接通过 (信任主 AI)                       │
    │    elif report.selfAssessment.confidence=='low'│
    │       OR report.selfAssessment.concerns非空:    │
    │      → 派 general-purpose 子 agent 做 code review│
    │      → 必 review report.selfAssessment.         │
    │         suggestedReview 提到的文件               │
    │    else:                                        │
    │      → 自读 Read 验证(简单步骤)                  │
    │                                                 │
    │  if review.pass:                                │
    │    update_state({                              │
    │      planProgressDelta: [{step, status='done'}],│
    │      decisionAppend: {action='approve',         │
    │                       category='A',             │
    │                       autoMode=true}            │
    │    })                                          │
    │    → 回到阶段 A 循环                              │
    │  else:                                          │
    │    if retryCount < 3:                          │
    │      emit_action(inject_prompt,                │
    │                  payload={kind:'review_feedback'│
    │                          violations,...})       │
    │      retryCount++                              │
    │    else:                                        │
    │      [F2] skip step + 标 blocked:                │
    │        update_state({                          │
    │          planProgressDelta:[{step,blocked,attempts=3}],│
    │          decisionAppend:{action='skip_after_retry'}│
    │        })                                      │
    │        emit_action(record_alert, severity='alert',│
    │                    category='C1')               │
    │      → 回到阶段 A 循环                            │
    └─────────────────────────────────────────────────┘

终止:
  ├─ 所有 step done → 写 COMPLETION_REPORT.md
  ├─ skip step > 30% → 写 PARTIAL_REPORT.md
  ├─ budget_exceeded → 写 PAUSED_REPORT.md
  └─ C3 阻断 → 写 PAUSED_REPORT.md + 等用户重启
```

### 5.3 record_alert 行为

```
旧:  escalate_to_human → 阻塞等用户回答
新:  record_alert → 不阻塞继续
  ├─► update_state(decisionAppend({severity, category, candidates, chosenCandidate}))
  ├─► daemon 推 [SUPERVISOR_ACTION] action='record_alert'
  ├─► Java ActionRouter 转 webview window.onPairAlert(severity, payload)
  ├─► webview 显示通知 + 入决策时间线
  └─► supervisor 自己按 fallbackChoice 继续

兼容:  旧的 escalate_to_human 提示词 → 内部 alias 到 record_alert + category='C2'
       (不破坏现有 prompt 文本里的引用,但行为变了)
```

### 5.4 Cost Budget 决策点

```
Java SupervisorMonitor.doTick():
  每次 tick 调 budgetTracker.check()
  
  if budget.shouldPause():                  // 100%
    pair.pause()
    bus.publish(makeEvent('budget_exceeded', {...}))
    daemon 收到 → supervisor 还能跑最后一个 turn 写收尾
    Java 写 PAUSED_REPORT.md
    停 monitor / decider
  
  elif budget.shouldWarn() AND !warned80:   // 80%
    warned80 = true
    bus.publish(makeEvent('budget_warning', {ratio, projectedOverrun}))
    supervisor 下个 turn 会看到 warning,可选:
      - 缩减剩余 step (调 save_plan 改 plan)
      - 跳过非关键 step
      - 提前完工
```

### 5.5 完工判定与报告

```java
// Java CompletionDetector.java (★ 新增)
public boolean isComplete(L2State state) {
  if (state.plan == null) return false;
  List<PlanStep> steps = state.plan.steps;
  if (steps.isEmpty()) return false;
  
  long doneCount = steps.stream()
    .filter(s -> "done".equals(s.status)).count();
  long skippedCount = steps.stream()
    .filter(s -> "skipped".equals(s.status) || "blocked".equals(s.status)).count();
  
  // 所有非 todo/in_progress 的 step 都完结
  return doneCount + skippedCount == steps.size();
}

public String classifyCompletion(L2State state) {
  long doneCount = ...;
  long skippedCount = ...;
  double skipRatio = (double) skippedCount / (doneCount + skippedCount);
  
  if (skipRatio == 0) return "FULL_SUCCESS";
  if (skipRatio < 0.30) return "MOSTLY_SUCCESS";
  return "PARTIAL_SUCCESS";
}
```

报告内容(`COMPLETION_REPORT.md`):

```markdown
# Pair 完工报告

- pairId: p1
- 开始: 2026-05-24 10:00:00
- 结束: 2026-05-24 14:30:00
- 总耗时: 4h 30min
- 总 turn: supervisor 78, 主 AI 56
- 总 token: input 1.2M, output 320K
- Plan 步骤: 12 步 (done 10, skipped 1, blocked 1)
- Subagent 调用: 主 AI 18 次, supervisor 5 次

## 各 Step 摘要

### Step 1: 数据模型设计 ✓
- Deliverables:
  - user/model/user.go (新增 User struct)
- Verifications: go build ✓

### Step 2: ... 
...

### Step 11: ⚠ SKIPPED (3 次重试失败)
- 原因: API 设计需要 Step 10 输出,但 Step 10 也 blocked
- supervisor decision: skip_after_retry (decisionId xxx)

## 关键决策(按时间)

| 时间 | step | action | category | confidence | reason |
|---|---|---|---|---|---|
| 10:15 | 3 | replan | B | high | 发现遗漏 cache 层,加 step |
| 10:32 | 5 | record_alert | C2 | medium | API 签名冲突,跳过该 step |
...

## 子 Agent transcript 索引(供事后审计)

- 主 AI Subagent sub_abc: /Users/.../agent_xxx.jsonl (耗时 35s)
- supervisor code_reviewer: 在 supervisor session message 流内
```

---

## 六、supervisor 提示词改造

### 6.1 design-supervisor.md(方案监督者)改造

**改动量**: 约 15%

**保留不变**(无需改):
- 启动协议(三步)
- 核心铁律 1, 2, 3, 5, 8(原文档编号)
- 三色标记(🟢🟡🔴)与 decisions[] 留痕
- 下游契约(编码方案 / 适用技能包硬规则汇总两章节)
- 跳过-继续机制(C 类不立即 escalate,文末汇总)—— **跟自治模式天然兼容**

**修改项**:

| 位置 | 原内容 | 新内容 |
|---|---|---|
| 启动协议第三步 | "再次 inject_prompt 让主 AI 提取硬规则摘要" | 改为 supervisor 自派 general-purpose 子 agent 提取(R1):"用 Agent 工具派一个子 agent,brief: 角色=技能包硬规则摘要提取器,输入=技能包路径列表..." |
| 核心铁律 6 "自决必留痕" | 仅"通过 emit_action.decisions[] 字段记录" | 改为通过 `update_state(decisionAppend={...})` 记录(本期协议升级,decisionAppend 是规范字段),废弃 emit_action.decisions[] |
| 核心铁律 7 "下游契约硬要求" | "最终方案文档必须含两个二级章节" | 新增第三条:"方案文档**编码方案章节**还要含 'expectedDeliverables' 列表(每步,绝对路径或相对项目根),供下游 supervisor 验证 turn_report.deliverables 用" |
| escalate_to_human 引用 | 多处出现 `escalate_to_human` | 全部替换为 `record_alert`(行为见 §5.3);**deprecated 旧名也行**(daemon 内部 alias) |

**新增章节**(在"自决与升级边界"后):

```markdown
# 与下游编码 supervisor 的协作

完工后产出的方案文档会被下游编码 supervisor 消费。编码 supervisor 已升级为
**自治模式**(可见 docs/plans/2026-05-24-supervisor-autonomous-collab-implementation.md),
其执行不再"等用户拍板每个 C 类决策",而是 C1/C2 自决留痕,仅 C3 真停。

因此你输出的方案文档应:
1. **明确每步的 acceptanceCriteria**(可验证条件,不要写"实现正确")
2. **明确每步 expectedDeliverables 路径**(相对项目根)
3. **decisions[] 的 C 类项**注明 "fallback_choice"(如果下游遇到此点又找不到人,
   该走哪条路)——这是为自治模式预留的兜底
```

### 6.2 code-supervisor.md(编码监督者)改造

**改动量**: 约 35%(大改)

**完全删除**:
- 核心铁律 #2 "Supervisor 永不读源文件" 整条 —— supervisor 现在可读
- Step 0 "派 manifest 子 agent" 段落里 60+ 行 brief 模板 —— 改为 supervisor 自派
- Review 步骤 1 "派 review 子 agent" 段落里 70+ 行 brief 模板 —— 同上
- review 子 agent 报告格式异常处理里 "让主 AI 重派" 逻辑 —— supervisor 自己重派
- "你不做的事" 章节里:"不读 design 原文"、"不直读产出代码文件" 两条

**修改项**:

| 位置 | 原内容 | 新内容 |
|---|---|---|
| 开头职责描述 | "你接收上游方案文档,按 manifest 推进主 AI 编码" | 升级为"...自主推进编码,周期性 RE-PLAN,可信任主 AI selfAssessment 跳过 review" |
| 核心铁律 3 "每步必派 review 子 agent" | "未收到 verdict=pass 不推进" | 改为"按主 AI 的 selfAssessment.confidence 分诊:high+allPass 信任,low/concerns 必派 reviewer,medium 自读 Read 验证" |
| 核心铁律 6 "自决必留痕" | "emit_action.decisions[]" | 改为 `update_state(decisionAppend={...})`(协议规范) |
| Step 0 manifest | 让主 AI 派 manifest 子 agent | 改为 supervisor 用 Agent 工具自派 general-purpose 子 agent,brief 嵌入提示词 |
| Step 0 unresolved C 项 escalate | "立即 escalate_to_human" | 改为 `record_alert(C1, fallback='按 manifest 继续推进')` |
| Step 0 plan 不存在 | (没处理) | 新增分支:"若 design 文档没 'plan' 章节,supervisor 自派子 agent 重新设计步骤 → `save_plan(source='design_doc')`" |
| Review 步骤 1 | 让主 AI 派 review 子 agent | 改为 supervisor 自派(brief 嵌入正文);**前置加 selfAssessment 分诊**(详见§5.2) |
| Review 步骤 2c 兜底 | "强制降级为 fail" | 不变,但 supervisor 现在直接 Read 验证(可读源文件) |
| 自决边界 C 类 | "必须 escalate" | 按 §5.1 拆为 C1/C2/C3,只有 C3 真停 |
| inject_prompt 写法 | (没说必带 directiveId) | 增加:"每个 inject_prompt 必带 directiveId(supervisor 自生成或框架自动),`emit_action` 工具会拒绝缺 directiveId 的 inject_prompt" |
| inject_prompt 格式 | 自由 prompt 文本 | 强制结构化:`payload.kind` + `objective` + `expectedDeliverables` + `acceptanceCriteria`(详见§4.2.1) |
| escalate_to_human 多处 | (多处) | 全部替换为 `record_alert` + 对应 category |

**新增章节**:

```markdown
# 自治控制循环(★ 本版本新增)

你现在是**自主推进者**——不等用户拍板,按以下循环工作:

## 工作循环

1. 收到 composite_summary / turn_report
2. 检查阶段(A → B → C),详见 docs/plans/2026-05-24-supervisor-autonomous-collab-implementation.md §5.2
3. 决策必通过 update_state(decisionAppend) 记录

## 决策矩阵(★ 替换原 A/B/C)

详见 §5.1。要点:
- A:自决可记
- B:自决必记 + confidence
- **C1**(可补救偏差):自决记录 confidence=low + 加大 review 力度
- **C2**(不可恢复但可跳过):record_alert(severity=alert) + skip step + 继续
- **C3**(硬阻断,如 quota 耗尽):pause plan,等用户介入

## RE-PLAN 触发(★ T1)

每完成 5 个 step 后,或刚发出过 record_alert 后:
1. 派 general-purpose 子 agent,brief: "请评估当前 plan vs 实际进展,
   输出 plan_changes 数组(add_step / remove_step / modify_step / reorder)"
2. 收到子 agent 输出后,supervisor 自评:
   - 显然合理的变更 → 直接 save_plan(source='replan')
   - 大幅变更 → record_alert(C1) 留痕,小幅调整继续

## 信任主 AI(★ T3)

收到 turn_report 后**先看 selfAssessment**:
- confidence='high' + verifications.allPass → 直接通过,**不派 reviewer**
  (节省一次子 agent 调用,decisionAppend(action='trusted_pass'))
- confidence='medium' → 自读 Read 关键文件验证
- confidence='low' OR concerns 非空 →
  必派 code_reviewer 子 agent,brief 中必含 selfAssessment.suggestedReview

## Cost Budget(★ T2)

收到 budget_warning 事件(80% 阈值)时:
1. 评估剩余 step,识别可裁剪的
2. 若可裁剪:save_plan(remove 非关键 step) + record_alert(severity=warn)
3. 不可裁剪:继续推进,准备 partial completion 报告

收到 budget_exceeded 事件(100% 阈值)时:
1. 不再下发新 step
2. 写一个总结性 inject_prompt 让主 AI 收尾 (可选)
3. 等 Java 强制 pause
```

### 6.3 提示词文件改动汇总

| 文件 | 删除 | 修改 | 新增 |
|---|---|---|---|
| `design-supervisor.md` | 0 段 | 启动第三步 + 核心铁律 6/7 + escalate 引用 | "与下游协作" 章节 |
| `code-supervisor.md` | 核心铁律 #2 + Step 0/Review 两段 brief 模板 + "你不做的事" 两条 | 大量 escalate → record_alert / selfAssessment 分诊 / decision schema 引用 | "自治控制循环" 章节 |

---

## 七、Phase 划分

### Phase 0 — turn_end 带 assistantText(1h)

**目标**: 用最小改动验证"supervisor 拿到文本后是否真就能看见 manifest"。

**文件清单**:

```
修改:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/ClaudeMessageHandler.java
    publishTurnEndIfPair: 把 assistantContent.toString() 截断到 8KB 后塞进 payload.assistantText
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/EventBus.java
    publishTurnEnd: 加 assistantText 参数
  
  ai-bridge-server/ai-bridge/services/supervisor/event-summarizer.js
    formatTurnEnd: 渲染 "## 主 AI 本轮自然语言回复(截断)" 段
```

**验收**:
- 跑一个会让主 AI 输出长 YAML 的 prompt,看 daemon log `[supervisor-diag]` 是否含主 AI 的 YAML 内容
- supervisor 是否能在 review 时引用 YAML 内容

**风险**: 几乎为零,改动孤立。这是真正的"快速反馈实验"。

---

### Phase 1 — 协议契约冻结 + 工具链准备(0.5d)

**目标**: 把 §四 协议契约文档化为 daemon 端 schema 常量,Java 端镜像类。

**文件清单**:

```
新增:
  ai-bridge-server/ai-bridge/utils/spill-to-file.js
    maybeSpill({content, kind, pairId, name}) → {inline, spilledPath}
  
  ai-bridge-server/ai-bridge/services/supervisor/protocol-v2.js
    导出 NDJSON 事件类型常量 + 直接可用的 schema
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/protocol/
    TurnReport.java
    SubagentStop.java
    MainAck.java
    DirectivePayload.java
    DecisionRecord.java                   (含新字段)
    Candidate.java
    Evidence.java
    PairBudget.java
    BudgetStatus.java
```

**验收**: 单测覆盖每个 schema 的 JSON 序列化反序列化。

---

### Phase 2 — daemon 协议升级(2d)

**目标**: 实现所有新 NDJSON 事件 + 新 MCP 工具 + spill-to-file。

**文件清单**:

```
修改:
  ai-bridge-server/ai-bridge/channels/supervisor-channel.js
    + SUPERVISOR_READ_TOOLS 加 'Agent' (R1: general-purpose 即可,
      不预定义 manifest_extractor / code_reviewer)
    + canUseTool 放行 Agent
    + emit_action 处理:
      - inject_prompt 必 directiveId,长 prompt spill-to-file
      - record_alert 新增 case
      - escalate_to_human 内部 alias 到 record_alert
    + 新增 save_plan MCP 工具(buildSavePlanTool)
  
  ai-bridge-server/ai-bridge/services/supervisor/update-state-tool.js
    decisionAppend schema 加新字段(category/severity/candidates/...)
  
  ai-bridge-server/ai-bridge/services/claude/runtime-lifecycle.js
    主 AI runtime acquireRuntime 时:
      + 注册 SubagentStop hook
      + 注册 mcp__main MCP server (含 report_turn_completion)
  
  ai-bridge-server/ai-bridge/services/claude/persistent-query-service.js
    buildRequestContext 把 mcp__main server 写到 SDK options
  
  ai-bridge-server/ai-bridge/services/supervisor/event-summarizer.js
    formatTurnEnd: 渲染 deliverables / verifications / selfAssessment
    新增 formatSubagentStop
    新增 formatBudgetWarning / formatBudgetExceeded
  
  ai-bridge-server/ai-bridge/channels/claude-channel.js
    新增 RPC 处理: confirmAckReceived (Java 主动 ack 同步)

新增:
  ai-bridge-server/ai-bridge/services/supervisor/save-plan-tool.js
    MCP 工具: 写 .claude/pair/<pairId>/plan.md + 备份
  
  ai-bridge-server/ai-bridge/services/supervisor/subagent-stop-hook.js
    SDK hook 实现, 推 [SUBAGENT_STOP] NDJSON
  
  ai-bridge-server/ai-bridge/services/claude/main-ai-tools.js
    定义 mcp__main MCP server,含 report_turn_completion 工具实现
    工具入参经 spill-to-file 后推 [TURN_REPORT] NDJSON
```

**关键代码 - SubagentStop hook**:

```javascript
// services/supervisor/subagent-stop-hook.js
export function buildSubagentStopHook(ctx) {
  return async function subagentStopHook(input) {
    try {
      process.stdout.write('[SUBAGENT_STOP] ' + JSON.stringify({
        sessionId: ctx.sessionId,
        turnId: ctx.currentTurnId,
        ts: Date.now(),
        payload: {
          agentId: input.agent_id,
          agentType: input.agent_type,
          taskSubject: input.task_subject,       // 来自 TaskCreated 关联
          lastAssistantMessage: input.last_assistant_message,
          transcriptPath: input.agent_transcript_path,
          parentToolUseId: input.parent_tool_use_id,
          durationMs: input.duration_ms
        }
      }) + '\n');
    } catch (_) { /* stdout closed */ }
    return { continue: true };
  };
}
```

**关键代码 - report_turn_completion**:

```javascript
// services/claude/main-ai-tools.js
export function buildMainAiMcpServer(sdk, zod, runtimeRef) {
  const z = zod?.z ?? zod;
  return sdk.createSdkMcpServer({
    name: 'main',
    version: '1.0.0',
    tools: [
      sdk.tool('report_turn_completion',
        '主 AI 每个 turn 结束前**必须**调用,汇报本轮工作产出 + 自评。',
        {
          summary: z.string(),
          deliverables: z.array(z.object({
            path: z.string(),
            change: z.string(),
            confidence: z.enum(['high', 'medium', 'low']).optional()
          })),
          verifications: z.array(z.object({
            command: z.string(),
            pass: z.boolean(),
            stderrTail: z.string().optional()
          })).optional(),
          selfAssessment: z.object({
            confidence: z.enum(['high', 'medium', 'low']),
            concerns: z.array(z.string()),
            suggestedReview: z.string().optional()
          })
        },
        async (args) => {
          // 大 assistantText 不在 args 里(daemon 端 collectAssistantTurn 已有);
          // 这里只把 args 推 [TURN_REPORT]
          const turnId = `t_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
          process.stdout.write('[TURN_REPORT] ' + JSON.stringify({
            type: 'turn_report',
            sessionId: runtimeRef.sessionId,
            turnId,
            directiveId: runtimeRef.activeDirectiveId,
            ts: Date.now(),
            payload: args
          }) + '\n');
          return { content: [{ type: 'text', text: 'turn completion recorded' }] };
        }
      )
    ]
  });
}
```

**验收**:
- supervisor 能用 Agent 工具派子 agent
- 主 AI 调 report_turn_completion 后 Java 收到 [TURN_REPORT]
- 主 AI 派 Task 子 agent 后 Java 收到 [SUBAGENT_STOP]
- supervisor 调 save_plan 后 .claude/pair/<id>/plan.md 写入

---

### Phase 3 — Java EventBus + ack 协议 + cost budget(2d)

**目标**: 接收新协议 + 维护 directive 状态 + budget 监控。

**文件清单**:

```
修改:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/ClaudeMessageHandler.java
    + 监听 [TURN_REPORT] → 解析为 TurnReport → 复用 EventBus.publishTurnReport
    + 监听 [SUBAGENT_STOP] → EventBus.publishSubagentStop
    + 监听 [MAIN_ACK] → DirectiveTracker.markAcked
    - 删除老 publishTurnEndIfPair 路径(被 [TURN_REPORT] 替代);
      保留 fallback: 主 AI 若没调 report_turn_completion(老版本兼容),
      Java 自己组装一个最小 TurnReport(只有 summary=空 + deliverables=空)
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/EventBus.java
    + publishTurnReport(TurnReport) → composite_summary 新事件类型
    + publishSubagentStop(SubagentStop)
    + publishBudgetWarning(double ratio)
    + publishBudgetExceeded()
    + publishDirectiveLost(directiveId)
    - publishTurnEnd(老签名) 标 deprecated,内部转发到 publishTurnReport
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/CompositeSummaryBuilder.java
    适配 TurnReport / SubagentStop / budget 事件
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/bridge/SupervisorBridge.java
    + postEvent 内部:inject_prompt 出去时挂 directiveId(自生成)
    + 监听 daemon 的 [SUPERVISOR_ACTION] action='record_alert' → ActionRouter.dispatchAlert
    + 监听 daemon 的 [SUPERVISOR_ACTION] action='inject_prompt':
      → DirectiveTracker.registerDirective(directiveId, payload)
      → 通过现有 onInjectPrompt 链推 webview
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/ActionRouter.java
    + dispatchAlert(severity, payload) → WebviewBridge.onPairAlert
    + markDirectiveAcked(directiveId, status)
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/handler/PairHandler.java
    + 新增 pair_directive_ack IPC handler (webview 主 AI 完成后回 ack)
    + pair_start payload 支持 budget 字段
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairSession.java
    + budgetTracker: PairBudgetTracker
    + getBudget() / pause()
    + tokenUsage 累加(从已有 token 计数基础设施)

新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/DirectiveTracker.java
    维护 Map<directiveId, DirectiveInfo>,5min 超时 → publishDirectiveLost
    线程安全(用 ConcurrentHashMap + ScheduledExecutor)
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairBudgetTracker.java
    见 §4.5
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/CompletionDetector.java
    isComplete(L2State) + classifyCompletion(L2State)
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/CompletionReportWriter.java
    把 L2State + 历史决策渲染为 COMPLETION_REPORT.md
```

**关键代码 - DirectiveTracker**:

```java
public class DirectiveTracker {
  private final ConcurrentHashMap<String, DirectiveInfo> pending = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final EventBus bus;
  private static final long ACK_TIMEOUT_MS = 5 * 60 * 1000;
  
  public void registerDirective(String directiveId, JsonObject payload) {
    DirectiveInfo info = new DirectiveInfo(directiveId, payload, System.currentTimeMillis());
    pending.put(directiveId, info);
    info.timeoutFuture = scheduler.schedule(() -> onTimeout(directiveId),
                                            ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }
  
  public void markAcked(String directiveId, String status) {
    DirectiveInfo info = pending.remove(directiveId);
    if (info != null && info.timeoutFuture != null) {
      info.timeoutFuture.cancel(false);
    }
    // status=applied 时调 budgetTracker.incrementSteps()
  }
  
  private void onTimeout(String directiveId) {
    DirectiveInfo info = pending.remove(directiveId);
    if (info == null) return;
    bus.publishDirectiveLost(directiveId, info.payload);
  }
}
```

**关键代码 - PairBudgetTracker 集成**:

```java
// 在 SupervisorMonitor.doTick() 末尾追加
private void checkBudget() {
  PairBudgetTracker tracker = pair.getBudgetTracker();
  if (tracker == null) return;            // 无预算限制,跳过
  BudgetStatus status = tracker.check();
  if (status.anyAbove(1.00) && !pair.isPaused()) {
    pair.getEventBus().publishBudgetExceeded(status);
    pair.pause();
    CompletionReportWriter.writePausedReport(pair, "budget exceeded");
  } else if (status.anyAbove(0.80) && !tracker.warned80()) {
    tracker.markWarned80();
    pair.getEventBus().publishBudgetWarning(status);
  }
}
```

**关键代码 - webview ack 回路**:

```typescript
// App.tsx PairAppBridge
useEffect(() => {
  registerInjectPromptHandler((pairId, supervisorId, payload) => {
    const directiveId = payload.directiveId;
    if (directiveId) {
      // Step 1: 主 AI "已收到" ack
      sendBridgeEvent('pair_directive_ack', JSON.stringify({
        pairId, directiveId, status: 'received'
      }));
    }
    // Step 2: 走 handleSubmit(队列化,避免主 AI busy 时并发)
    const promptText = payload.inlinePrompt
      ?? `请按 ${payload.spilledPath} 中的指令执行(${payload.objective})`;
    handleSubmit(promptText, []);
    // Step 3: 主 AI turn 真正结束(loading 转 false 时), 由 ChatView 监听
    //         loading transition → 回 pair_directive_ack(applied)
  });
}, [registerInjectPromptHandler]);
```

**验收**:
- supervisor 派 directiveId=d1 → webview 收到 → 立即回 received
- 主 AI 跑完一 turn → webview 回 applied → DirectiveTracker 清理 d1
- 5min 主 AI 没完成 → DirectiveTracker 超时 → supervisor 收 directive_lost
- 跑超 5 steps → budget warning
- 跑超 100% → budget exceeded → pair pause

---

### Phase 4 — 提示词重写(1d)

**目标**: 把 §六 的改动应用到两份 supervisor 提示词。

**文件清单**:

```
修改:
  jetbrains-cc-gui/src/main/resources/supervisor/design-supervisor.md
    按 §6.1 改动(约 15%)
  
  jetbrains-cc-gui/src/main/resources/supervisor/code-supervisor.md
    按 §6.2 改动(约 35%)

新增:
  jetbrains-cc-gui/src/main/resources/main-ai-system-prompt-append.md
    主 AI system prompt 追加段(由 PairHandler.handleStart 注入到主 AI session):
    
    "在 Supervisor Pair 模式下,你**每个 turn 结束前必须**调用
     mcp__main__report_turn_completion 工具汇报本轮工作。
     - summary: 一句话概括
     - deliverables[]: 你新增/修改的文件(相对项目根路径)
     - verifications[]: 你跑过的验证命令及结果(可选)
     - selfAssessment: 必填
       - confidence: 你对本轮工作的自信度
       - concerns[]: 你自己觉得可能有问题的点(列具体)
       - suggestedReview: 建议 supervisor 重点 review 哪里(可选)
     
     未调用此工具的 turn 会被 supervisor 视为 'incomplete report',
     可能触发 review 重检。"

修改:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/handler/PairHandler.java
    pair_start 时:
      读取 main-ai-system-prompt-append.md
      通过 ClaudeSDKBridge 的某种机制 append 到主 AI 的 systemPromptAppend
      (现有 systemPromptAppend 字段已经在 claude-channel send 参数里支持)
```

**验收**:
- 启动 pair 后,主 AI session 的 system prompt 末尾确有这段
- 主 AI 第一个 turn 自动调用 report_turn_completion
- supervisor 提示词修改后,跑一遍简单任务,supervisor 行为符合自治循环

---

### Phase 5 — UI Tasks 面板 + 决策时间线 + Autonomy 开关(1.5d)

**目标**: webview 端可视化新能力。

**文件清单**:

```
新增:
  jetbrains-cc-gui/webview/src/components/SupervisorPair/SubagentPanel.tsx
    展示当前 supervisor session 派的子 agent 实时状态
    数据源: 从 SupervisorMessages 中筛 tool_use(name='Agent') + 对应 tool_result
  
  jetbrains-cc-gui/webview/src/components/SupervisorPair/DecisionTimeline.tsx
    展示 pairStatus.decisions 时间线
    每条决策卡片含: ts / action / category / confidence / candidates / chosen
    支持过滤(severity / category / autoMode)
  
  jetbrains-cc-gui/webview/src/components/SupervisorPair/AutonomyToggle.tsx
    开关组件: strict / mixed / full
    strict: 旧行为, escalate 仍弹窗
    mixed: 默认, C2 自决, C3 停, C1 自决但 alert
    full: 全自决, 仅 quota/磁盘等 C3 停
    选择持久化到 pair config
  
  jetbrains-cc-gui/webview/src/components/SupervisorPair/AlertNotifier.tsx
    收到 window.onPairAlert 时弹 toast(severity warn/alert/error 三色)
    不阻塞,事后可在 DecisionTimeline 中查看

修改:
  jetbrains-cc-gui/webview/src/components/SupervisorPair/SupervisorPane.tsx
    嵌入 SubagentPanel + DecisionTimeline (折叠抽屉)
  
  jetbrains-cc-gui/webview/src/components/SupervisorPair/PairContext.tsx
    + window.onPairAlert handler
    + pairStatus.decisions 字段(从 PairStatusSnapshot 接出)
    + autonomyMode state
  
  jetbrains-cc-gui/webview/src/global.d.ts
    + onPairAlert: (json: string) => void

  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairStatusSnapshot.java
    + List<DecisionRecord> recentDecisions(裁剪到 20 条最近)
    + AutonomyMode autonomyMode
    + BudgetStatus budgetStatus
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairStatusPusher.java
    push 时带上 recentDecisions / budgetStatus
```

**验收**:
- supervisor 派子 agent → SubagentPanel 实时显示
- supervisor 写决策 → DecisionTimeline 增加一条
- supervisor record_alert → AlertNotifier 弹 toast
- 切换 AutonomyMode 后,Java 侧 PairSession.autonomyMode 字段同步更新

---

### Phase 6 — 自治控制层 + Top 1-3(2d)

**目标**: 主 AI 端 self-assessment 联调 + supervisor 端 RE-PLAN + budget 路径全联调。

**关键工作**:

1. **T3 selfAssessment 联调**(0.5d)
   - 跑一个简单 task,确认主 AI 真的能按 prompt 调用 report_turn_completion
   - 验证 supervisor 真的能按 confidence 分诊(信任 high / 派 reviewer low)
   - 如果主 AI 不调 → 检查 system prompt append 是否正确注入

2. **T1 RE-PLAN 联调**(0.5d)
   - 人造一个"已完成 5 步"的状态(L2 mock),触发 supervisor 自评
   - 验证 supervisor 会派子 agent 重新评估 plan
   - 验证 save_plan(source='replan') 真的更新 plan.md + 备份

3. **T2 Cost Budget 联调**(0.5d)
   - pair_start 带 budget={maxTokens: 50_000} 故意设低
   - 跑任务,观察 80% / 100% 触发
   - 验证 PAUSED_REPORT.md 生成

4. **F1-F5 自治分支联调**(0.5d)
   - 人造 step 失败 3 次 → 验证 skip + record_alert
   - 人造 C1/C2/C3 各一次 → 验证行为差异

---

### Phase 7 — 联调 + 调试(2d)

**目标**: 端到端跑一个真实任务。

**测试 task**:
- 准备一个简单的 design doc(2-3 步)
- 启动 design supervisor pair → 产出方案
- 启动 code supervisor pair → 消费方案 → 全自动跑完
- 观察 COMPLETION_REPORT.md 内容

**回归点**:
- inject_prompt 雪崩(P3)是否消失
- supervisor 是否能看到 manifest YAML(P1)
- 主 AI 派子 agent 后 supervisor 是否拿到 SubagentStop(P2)
- supervisor 是否真的不绕主 AI 派 review(P4)

---

## 八、文件清单总览

```
新增文件 (15):
  ai-bridge-server/ai-bridge/
    utils/spill-to-file.js
    services/supervisor/protocol-v2.js
    services/supervisor/save-plan-tool.js
    services/supervisor/subagent-stop-hook.js
    services/claude/main-ai-tools.js
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/
    session/pair/protocol/TurnReport.java
    session/pair/protocol/SubagentStop.java
    session/pair/protocol/MainAck.java
    session/pair/protocol/DirectivePayload.java
    session/pair/protocol/PairBudget.java
    session/pair/DirectiveTracker.java
    session/pair/PairBudgetTracker.java
    session/pair/CompletionDetector.java
    session/pair/CompletionReportWriter.java
  
  jetbrains-cc-gui/src/main/resources/main-ai-system-prompt-append.md
  
  jetbrains-cc-gui/webview/src/components/SupervisorPair/
    SubagentPanel.tsx
    DecisionTimeline.tsx
    AutonomyToggle.tsx
    AlertNotifier.tsx

修改文件 (16):
  ai-bridge-server/ai-bridge/
    channels/supervisor-channel.js
    channels/claude-channel.js
    services/supervisor/update-state-tool.js
    services/supervisor/event-summarizer.js
    services/claude/runtime-lifecycle.js
    services/claude/persistent-query-service.js
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/
    session/ClaudeMessageHandler.java
    session/pair/EventBus.java
    session/pair/CompositeSummaryBuilder.java
    session/pair/ActionRouter.java
    session/pair/PairSession.java
    session/pair/PairStatusSnapshot.java
    session/pair/PairStatusPusher.java
    session/pair/l2/L2State.java
    bridge/SupervisorBridge.java
    handler/PairHandler.java
  
  jetbrains-cc-gui/src/main/resources/supervisor/
    design-supervisor.md
    code-supervisor.md
  
  jetbrains-cc-gui/webview/src/
    App.tsx
    components/SupervisorPair/PairContext.tsx
    components/SupervisorPair/SupervisorPane.tsx
    global.d.ts
```

---

## 九、测试策略

### 9.1 单元测试

```
DirectiveTrackerTest
  - registerDirective + markAcked → 移除
  - 5min 超时未 ack → publishDirectiveLost
  - 并发 register + ack 不丢

PairBudgetTrackerTest
  - 80% → shouldWarn 返 true 一次, 第二次不重复 warn
  - 100% → shouldPause
  - tokens/duration/steps/subagents 任一超就报

CompletionDetectorTest
  - 全 done → FULL_SUCCESS
  - skip > 30% → PARTIAL_SUCCESS
  - 有 in_progress → 未完成

CompletionReportWriterTest
  - L2State mock → 渲染 markdown
  - 决策时间线按 ts 升序

SpillToFileTest (Node)
  - <8KB 不 spill
  - >8KB 写文件, 返 path
  - PAIR_SPILL_THRESHOLD_BYTES env 生效

ProtocolSchemaTest
  - 所有 NDJSON 事件类型的 Java 镜像类
    JSON 序列化反序列化幂等
```

### 9.2 集成测试

```
EndToEndAutonomyTest (手动 / Playwright)
  scenario: 一个 3 步 design doc
  expected:
    - supervisor 自派 manifest 子 agent (R1) 成功
    - 派 step 1 → 主 AI report_turn_completion → supervisor 信任 → 推进
    - 派 step 2 → 主 AI confidence=low → supervisor 派 reviewer → fail → 重派 → pass
    - 派 step 3 → 全自动通过
    - COMPLETION_REPORT.md 内容完整

BudgetExhaustTest
  scenario: maxTokens=10_000, 跑大 task
  expected:
    - 80% warn 一次
    - 100% pause + PAUSED_REPORT.md

DirectiveLostTest
  scenario: 主 AI 卡死(mock 不回 report_turn_completion)
  expected:
    - 5min 后 directive_lost
    - supervisor 收到 → 重派一次
    - 仍失败 → record_alert(C1) + skip step

SubagentVisibilityTest
  scenario: 主 AI 调 Task subagent
  expected:
    - SubagentStop hook 触发
    - Java 收到 [SUBAGENT_STOP]
    - composite_summary 含子 agent 摘要
    - supervisor prompt 中可见 lastAssistantMessage

RecordAlertTest
  scenario: supervisor 遇 C2 决策
  expected:
    - emit_action(record_alert) NDJSON
    - Java ActionRouter.dispatchAlert
    - webview AlertNotifier 弹 toast
    - L2 decisions ring 新增一条 severity=alert
```

### 9.3 性能 / 负载测试

```
LargePayloadSpillTest
  - 主 AI 输出 50KB assistantText → spill 后 IPC 行 < 1KB

ConcurrentDirectiveTest
  - 100 个 directive 并发注册 → 5min 后全部超时清理
  - DirectiveTracker 内存不泄漏

LongRunPairTest (overnight)
  - 8h 跑长任务
  - L2 ring buffer 不超 100 条
  - 内存稳定
  - rotation 触发后 directive / budget 状态正确接管
```

---

## 十、风险与回滚

### 10.1 已识别风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 主 AI 不调 report_turn_completion | 中 | 高 | system prompt append 强 wording;Java 有 fallback 兜底 |
| supervisor 滥用 Agent 工具(无意义派 子 agent) | 中 | 中 | 提示词约束"目标是验证→自读;目标是收集→子 agent";cost budget 兜底 |
| spill-to-file 项目 cwd 解析错 | 低 | 高 | 启动时 mkdir 校验;失败回退 inline |
| DirectiveTracker 内存泄漏 | 低 | 中 | 单测覆盖;ScheduledExecutor 5min 超时强清 |
| SubagentStop hook payload 字段缺失(SDK 版本差异) | 中 | 中 | hook 实现里所有字段 null 安全;转译失败仅 log warn |
| record_alert vs escalate_to_human 行为分歧导致 prompt 误判 | 中 | 中 | daemon 端 alias 处理;Phase 7 联调强 prompt 验证 |
| RE-PLAN 触发死循环(supervisor 每次都改 plan) | 中 | 高 | RE-PLAN 后 8 小时内不重派(冷却);连续 3 次 replan 强 pause |

### 10.2 回滚方案

每个 Phase 独立可回滚:

- **Phase 0** 回滚: revert 3 文件,无副作用
- **Phase 1-3** 回滚: 新协议字段 daemon / Java 双侧都加了向后兼容(Java 收不到 [TURN_REPORT] 时 fallback 到 publishTurnEnd 老路径)
- **Phase 4** 回滚: git revert 提示词文件;但运行中的 supervisor session 已用旧 prompt,需要 stop + start
- **Phase 5** 回滚: UI 新组件可 feature flag 关闭(`cc-gui.pair.autonomy.ui.enabled`)
- **Phase 6-7** 回滚: 自治路径有 AutonomyMode 'strict' 开关回到旧行为

### 10.3 灰度策略

不灰度(Q8 决策一次切),但保留**两个安全网**:

1. **AutonomyMode='strict'**: 即使代码全部上线,用户也能切回"旧行为"——
   - escalate_to_human 仍弹窗
   - C1/C2 不自动 skip 改回 escalate
   - 但 selfAssessment / SubagentStop / cost budget 等基础设施依然生效
2. **PAIR_AUTONOMY_DISABLE=1 env**: daemon 启动时设此 env,supervisor 完全退回旧 prompt 行为

---

## 十一、里程碑

```
Day 0:  Phase 0 (turn_end + text) ★ 立即验证 P1
Day 1:  Phase 1 (协议契约 + 工具链)
Day 2-3: Phase 2 (daemon 协议升级)
Day 4-5: Phase 3 (Java EventBus + ack + budget)
Day 6:  Phase 4 (提示词重写)
Day 7-8: Phase 5 (UI Tasks + 时间线 + autonomy 开关)
Day 9-10: Phase 6 (自治 + Top 1-3 联调)
Day 11-12: Phase 7 (端到端 + 调试)

合计: 12 天(单人专注) / 18 天(日常节奏含 review)
```

---

## 十二、不变量(无论怎么改都要守住)

- ✅ **supervisor 不写代码**:Edit / Write / NotebookEdit / Bash 永不开放
- ✅ **主 AI 是唯一实施者**:代码修改、命令执行都走主 AI
- ✅ **decisions 留痕**:所有自决记录到 L2,UI 可见
- ✅ **C3 不绕过**:硬阻断真的 pause,不假装继续
- ✅ **UI 双轨并行**:主 AI 和 Supervisor 各自独立的对话流和子代理面板
- ✅ **rotation 仍可用**:本期不改 rotation 协议,与现有 RotationCoordinator 兼容
- ✅ **mutagen 友好**:所有共享文件都在项目级 `.claude/pair/<pairId>/` 下
- ✅ **可观察性**:每个自动决策都能在 UI 时间线 + COMPLETION_REPORT.md 中找到

---

## 附录 A:与既有方案的关系

| 既有方案 | 关系 |
|---|---|
| Pair Agent 基础(2026-05-21) | **依赖**,本期不改其 IPC 框架 |
| Monitor + Rotation(2026-05-23) | **依赖**,本期不改 monitor 30s tick 和 rotation 14 步流程 |
| supervisor-owns-agent-design.md(讨论稿) | **合并**,本方案是其最终落地版,Q1-Q9/R1-R5 决策全部纳入 |
| code-supervisor.md / design-supervisor.md(现有提示词) | **重写**,详见 §六 |

## 附录 B:接口契约的版本号

本期协议命名为 **Protocol v2**(v1 = Monitor+Rotation 期协议)。

- daemon 在 supervisor.start 时,向 Java 通报支持的 protocol version
- Java 在 pair_start 时,带上要求的 protocol version
- 版本不匹配时 supervisor 退回 v1 行为(向后兼容)

```javascript
// daemon: supervisor-channel.js
const SUPPORTED_PROTOCOL_VERSIONS = ['v1', 'v2'];

// Java: SupervisorBridge.java
public static final String CURRENT_PROTOCOL_VERSION = "v2";
```

---

## 附录 C:fallback 与兼容性

为了让 Phase 之间可以独立 ship + 旧会话不被破坏:

1. **没有 report_turn_completion 调用的主 AI turn**: Java fallback 走老的 turn_end 路径(只有 toolUses + modifiedFiles)
2. **没有 directiveId 的 inject_prompt**(老 prompt): daemon 端拒绝并报错;Java 端 SupervisorBridge 自动补一个 directiveId
3. **没有 selfAssessment 的 turn_report**(主 AI prompt 没更新): supervisor 当成 confidence='medium' 处理
4. **没有 budget 字段的 pair_start**: PairBudgetTracker 跳过,无预算限制
5. **AutonomyMode='strict'**: 关闭所有自治路径,行为退回 Monitor+Rotation 期
