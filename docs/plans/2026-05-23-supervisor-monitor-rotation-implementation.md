# Supervisor Monitor + Rotation 编码方案

**日期**: 2026-05-23
**作者**: Claude Code 协作
**状态**: 待实施
**关联文档**:
- `docs/plans/2026-05-21-supervisor-pair-agent-implementation.md` (Pair Agent 基础)
- `docs/supervisor/design.md`, `docs/supervisor/code.md` (supervisor persona prompt)
- `ai-bridge-server/IMPL-PLUGIN.md` (daemon 协议)

---

## 一、背景

### 1.1 当前 Pair Agent 的四个痛点

Pair Agent 已经能跑，但运行时遇到以下问题，已通过代码 + 设计 review 确认根因：

| 痛点 | 现象 | 根因（含代码位置） |
|---|---|---|
| **P1 假死消息丢失** | supervisor 显示"思考中"但 inject_prompt 永不到主 AI | `supervisor-channel.js:441` `query.next()` 无 timeout；EventBus 单线程 dispatcher 被 `postEvent().join()` 阻塞 |
| **P2 上下文压缩不本质** | 配置 `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=70` 只能反应式触发，summary of summary 逐代降质 | `daemon.js:57-67` 仅环境变量驱动；无 PreCompact hook；无主动 `getContextUsage` 监控 |
| **P3 缺乏健康监控** | supervisor 卡了无人发现 | EventBus 是事件驱动，无周期性检查；webview token% 是粗略 input/output 累计而非真实窗口占比 |
| **P4 单向中转脆弱** | inject_prompt 走 webview handler 路径，handler 未注册时静默丢失 | `SupervisorBridge.java:153-162` `if (handler == null) return` |

### 1.2 演进目标

把当前**事件驱动 + 同步阻塞**模型重构为**周期性 Monitor + 异步入队 + 可 rotation 的会话**模型，达到：

1. supervisor 不会再"沉默假死"，所有异常可观测、可自愈
2. 上下文压力可主动监测，到达阈值时**新建会话**而非依赖 SDK 反复压缩
3. 通过分层记忆 + L2 持久化 state，跨 generation 保真
4. supervisor 配置默认提示词跨多次 rotation 完整不变
5. 主 AI 端走同套机制（Phase 6 / B 大阶段单独立项）

---

## 二、决策记录（已拍板）

| 项 | 决策 | 备注 |
|---|---|---|
| 触发兜底节拍 | **30s** | 用户感知 / cost / 响应速度三角平衡 |
| 紧急事件路径 | **方案 B: cancel + reschedule 1s + 2s 防抖** | 与现有 ScheduledExecutor 模型一致 |
| urgent 事件清单 | error / off_plan_detected / verify_result(pass=false) / plan_complete / human_response | `human_response` 立即响应 (<200ms) |
| 上下文超阈值处理 | **新建会话**（不用 `/clear`） | 彻底丢弃 SDK 内部状态，可换模型 |
| 多次 summary 保真策略 | **分层记忆 (L0-L3) + 每代从 L2 重新水合** | 永远是"第一代 summary" |
| Supervisor 默认 prompt 跨代不变 | **每次 rotate 重新拼装 BASE prompt**，不缓存 | 配置即真相 |
| L2 写盘归属 | **Java 落盘** (daemon STATE_UPDATE → Java) | rotate 时一致性更好 |
| Handoff doc 生成时机 | **老会话先产 → 校验 schema → 再 start 新会话** | 校验失败可降级到 L2 |
| Handoff 校验失败处理 | **重试 1 次，30s 严格 timeout** | 失败走 L2 兜底 |
| Rotation 期间事件 | EventCollector 持续入队，新代第一 tick 加 banner | "你刚接手，以下事件你不知道" |
| L2 崩溃恢复 | **`.bak` 副本**：每次 rotate 后 `cp state.json state.json.bak` | 多一份 IO 换数据安全 |
| 并发锁 | **读写锁** (rwLock)：MAIN_TURN / TICK 共享读锁；ROTATING 排他写锁 | tick 内不能升级锁 |
| Generation 上限 | **5 代** | 之后强制 archive，重新走 INITIAL_BOOTSTRAP |
| 主 AI Monitor | **事件驱动**（不走 30s tick），onTurnStart/End 钩子 | 5min stall 检测 + getContextUsage |
| 可观测性 | **Webview 新增 "Pair 状态" 面板** | 显示状态机、generation、context%、最近 rotation |
| Phase 6 | 单独立项 | 主 AI rotate 风险更大，独立验证 |

---

## 三、架构总览

### 3.1 拓扑图

```
┌─ Pair (pairId 永久身份, 内部 session 可换) ───────────────────────────┐
│                                                                       │
│  ┌─ Java 侧 ────────────────────────────────────────────────────┐    │
│  │                                                              │    │
│  │  事件源 (ClaudeMessageHandler.onComplete / onError / ...)    │    │
│  │         │ publish() 非阻塞                                    │    │
│  │         ▼                                                    │    │
│  │  EventCollector (有界 ring 200 + urgentFlag)                 │    │
│  │         │                                                    │    │
│  │         ▼                                                    │    │
│  │  SupervisorMonitor (ScheduledExecutor, 30s fixedDelay)       │    │
│  │   ├─ tick: 拿读锁 → drain → composite → postEvent(90s) →     │    │
│  │   │       dispatch action → 释读锁                            │    │
│  │   └─ 状态: HEALTHY / DEGRADED / UNHEALTHY                    │    │
│  │                                                              │    │
│  │  RotationDecider (单线程 1s 轮询)                            │    │
│  │   └─ 检测 rotateRequested → 拿写锁 → 委派 RotationCoordinator│    │
│  │                                                              │    │
│  │  RotationCoordinator                                         │    │
│  │   └─ 14 步原子流程 (见 §10)                                  │    │
│  │                                                              │    │
│  │  PairCoordinator                                             │    │
│  │   ├─ rwLock: ReentrantReadWriteLock (非公平, 写优先)         │    │
│  │   └─ state: IDLE / MAIN_TURN / TICK / ROTATING               │    │
│  │                                                              │    │
│  │  L2Store (~/.codemoss/pairs/<pairId>/state.json + .bak)      │    │
│  │   └─ per-pairId 锁, 原子 rename, 启动加载 + 回退 .bak        │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  ┌─ Daemon 侧 (ai-bridge-server) ────────────────────────────────┐   │
│  │  supervisor-channel 扩展接口:                                  │   │
│  │   - postEvent (加 timeout + composite event)                  │   │
│  │   - health (返回 lastActivity / contextUsage / compactCount)  │   │
│  │   - interrupt (调用 query.interrupt())                        │   │
│  │   - produceHandoff (老会话生产 handoff doc)                   │   │
│  │   - getContextUsage (直接代理 SDK)                            │   │
│  │  MCP tools:                                                    │   │
│  │   - emit_action (已有)                                         │   │
│  │   - update_state (新增, 触发 [STATE_UPDATE] 给 Java)          │   │
│  │  hook:                                                         │   │
│  │   - PreCompact (兜底, 即便没 rotate 也 dump L2 snapshot)      │   │
│  └────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流分类

```
[A] 主 AI 事件流（高频, 不阻塞）
    主 AI → ClaudeMessageHandler → EventCollector.publish() → 立即返回

[B] Supervisor 处理流（30s 兜底, urgent 1s）
    Monitor tick → drain buffer → composite summary →
    daemon postEvent(timeout=90s) → SDK iteration → action wrapper →
    ActionRouter dispatch → (inject_prompt | escalate | wait)

[C] 状态持久化流（每 tick + 每 action）
    daemon supervisor update_state MCP tool → [STATE_UPDATE] line →
    Java L2Store.update() → 原子写盘

[D] Rotation 流（写锁排他）
    RotationDecider 1s 轮询 → 拿写锁 → RotationCoordinator.execute() →
    produceHandoff → validate → start newSupervisor → swap → release

[E] Health 流（每 5 tick = 2.5min）
    Monitor.tick → supervisor.getContextUsage / health →
    更新 UsagePushService → 推 webview Pair 状态面板
```

---

## 四、分层记忆模型

| 层 | 内容 | 谁管 | 是否被 SDK 压缩 | rotation 时去向 |
|---|---|---|---|---|
| **L0** | 任务描述 / agent persona / 监督规则 / 输出格式 / 禁止行为 | system prompt | 否 | 每代从配置模板**完整重建** |
| **L1** | 最近 N turn 原始消息 (CLI 内部) | SDK | 是 (auto-compact) | **丢弃** |
| **L2** | 结构化 state (plan 进度 / 文件 mtime / 决策日志 / 锚定事实) | Java 落盘 | 否 | 注入新会话 systemPromptAppend |
| **L3** | 完整事件 JSONL 归档 (`~/.codemoss/pairs/<pairId>/events/`) | Java 追加写 | 否 | 仅查询用, 不进 prompt |

### 4.1 设计意图

- **L0 永不被触碰**：每次 rotate 都从 `SupervisorAgentManager` 配置 + 模板重新拼装。不依赖任何"前一代记得"
- **L1 是易逝品**：SDK 怎么压怎么压，我们不关心
- **L2 是契约**：跨代连续的"真相"，rotation 的水合源
- **L3 是审计**：调试 / 复盘用，不影响运行时

---

## 五、L2 Durable State Schema

### 5.1 字段定义

文件路径：`~/.codemoss/pairs/<pairId>/state.json`
副本路径：`~/.codemoss/pairs/<pairId>/state.json.bak`

```json
{
  "schemaVersion": 2,
  "pairId": "p_abc123",
  "generation": 3,
  "createdAt": "2026-05-23T14:00:00Z",
  "lastUpdated": "2026-05-23T14:35:12Z",
  "lastRotation": {
    "from": "sup_old_uuid",
    "to": "sup_new_uuid",
    "at": "2026-05-23T14:32:00Z",
    "reason": "ratio>=0.85",
    "handoffSource": "producer | l2_fallback | none"
  },

  "anchoredFacts": {
    "currentStep": 5,
    "totalSteps": 12,
    "currentStepTitle": "实现 ai-bridge supervisor channel timeout",
    "blockedOn": null,
    "lastVerifyCmd": "go test ./...",
    "lastVerifyResult": "pass",
    "lastVerifyAt": "2026-05-23T14:30:45Z"
  },

  "planProgress": [
    {"step": 1, "status": "done", "filesChanged": ["a.go"], "completedAt": "..."},
    {"step": 2, "status": "done", "filesChanged": ["b.go"]},
    {"step": 5, "status": "in_progress", "attempts": 2, "lastError": null}
  ],

  "fileState": {
    "internal/svc/a.go": {
      "mtime": 1716475200000,
      "lastTouchedBy": "step5",
      "linesChanged": 42,
      "confidence": "high"
    }
  },

  "recentDecisions": [
    {
      "ts": "2026-05-23T14:28:00Z",
      "action": "inject_prompt",
      "reason": "off_plan_detected on unrelated.go",
      "payload": {"prompt": "请回到 plan step 5..."},
      "result": "accepted",
      "confidence": "high"
    }
  ],

  "knownConstraints": [
    "必须用 langchaingo 而不是 openai-go",
    "数据库测试不能用 mock (用户偏好, 见 conversation memory)"
  ],

  "compactionHistory": [
    {"at": "2026-05-23T14:20:00Z", "trigger": "sdk_auto", "ratioBefore": 0.71}
  ],

  "rotationCount": 3,
  "metrics": {
    "totalTicks": 120,
    "totalActions": 18,
    "totalInjections": 5,
    "degradedCount": 1,
    "unhealthyCount": 0
  }
}
```

### 5.2 字段约束

- `schemaVersion` 升级时 Java 端 `L2Migration.migrate(old, target)` 负责迁移
- `recentDecisions` 是 ring，**最多保留 50 条**，超出 drop oldest
- `knownConstraints` 单向追加，**到 generation == 5 时人工 review + archive**
- `fileState` 路径 key 用 cwd-relative，不要绝对路径（rotate 不会泄露宿主 fs 信息）
- `confidence` 三档：`high / medium / low`，successor prompt 据此决定要不要 verify

### 5.3 写入时机

| 时机 | 谁触发 | 更新字段 |
|---|---|---|
| 每 tick 成功结束 | Monitor | `lastUpdated`, `metrics.totalTicks`, decision append |
| daemon `update_state` MCP tool | supervisor 自己 | anchoredFacts / planProgress / fileState |
| `compact_boundary` 事件到达 | daemon stream | `compactionHistory` append |
| `PreCompact` hook 触发 | daemon hook | 全量 snapshot 落盘 |
| Rotation 完成 | RotationCoordinator | `generation++`, `lastRotation`, `rotationCount++` |

---

## 六、Prompt 模板

文件路径放在 `docs/supervisor/`，运行时由 Java `SupervisorPromptBuilder` 加载：

| 文件 | 用途 |
|---|---|
| `docs/supervisor/design.md` (已有) | persona/职责 (BASE) |
| `docs/supervisor/code.md` (已有) | persona/职责 (BASE) |
| `docs/supervisor/handoff-producer.md` (新) | 老会话产 handoff doc 的提示词 |
| `docs/supervisor/handoff-successor.md` (新) | 新会话 systemPromptAppend |
| `docs/supervisor/initial-bootstrap.md` (新) | 第一代 systemPromptAppend (无 handoff) |
| `docs/supervisor/composite-runtime.md` (新) | composite summary 渲染模板 |

### 6.1 BASE prompt 拼装（每代都重做）

```
systemPrompt = (
  configured_persona            // 来自 SupervisorAgentManager.getDescription()
  + "\n\n"
  + base_rules_template          // 不变规则: 输出格式 / 禁止行为 / 工具列表
)

systemPromptAppend = (
  handoffDoc != null
    ? SUCCESSOR_TEMPLATE.render({generation, handoffDoc, age_seconds})
    : INITIAL_BOOTSTRAP_TEMPLATE.render({pairId, planSummary})
)
```

### 6.2 HANDOFF_PRODUCER 模板内容（关键）

```
[SYSTEM TASK: HANDOFF]
你即将被一个新的 supervisor session 替换（同一个 Pair，原因：{reason}）。
请产出结构化交接文档，让继任者无损接管。

OUTPUT FORMAT: 严格 JSON, 符合 L2 schema v2

REQUIRED FIELDS:
- anchoredFacts.* 必须填写，未知字段填 null（**不要瞎编**）
- planProgress 至少包含所有已开始的 step
- fileState 列出本会话期间所有 touch 过的文件（路径 + 最后操作）
- recentDecisions 取最近 30 条（按时间倒序）
- knownConstraints 继承前序 + 加新发现的硬约束

RULES:
1. 字段独立, 不引用 "见上文" / "同上"
2. 不确定的事用 "confidence": "low" 标注
3. 每字段单独 <= 300 tokens
4. 总输出 <= 4000 tokens
5. 只输出 JSON 本身, 不要 markdown code fence, 不要 prose 包装

如果某些信息确实没有掌握, 字段写 null 或空数组, 后面继任者会用 Read/Grep 自行核对。

开始输出 JSON:
```

校验失败重试时追加：

```
[VALIDATION FAILED]
你刚才的输出不符 schema:
{errorMessages}

请按上面的错误修正后重新输出完整 JSON（不是 diff，是完整重写）。
仅最后一次机会，超时将由系统兜底。
```

### 6.3 HANDOFF_SUCCESSOR 模板内容

```
你是 Pair {pairId} 的第 {generation} 代 supervisor。
前任在 context 接近上限（{reason}）后正常移交，本文档由前任亲自产生（产生于 {age_seconds} 秒前）。

<HANDOFF_DOC>
{handoff_json_pretty}
</HANDOFF_DOC>

行为指引:
1. 你的"对过去 0~now 的认知"完全来自上面文档, 不要假装记得别的
2. 引用文档外内容时优先用 Read/Grep 验证, 不要凭空回答
3. anchoredFacts 是硬约束, 与新事件冲突时以新事件为准并通过 update_state 工具更新
4. 继续推进 plan, 当前在 step {currentStep} ({currentStepTitle})
5. recentDecisions 是前任决策风格参考, 保持一致性但不必盲从
6. confidence == "low" 的字段需要你主动核实

接下来你会收到正常的 main-AI 事件流, 像往常一样监督。
第一批事件可能包含"你刚接手期间发生但你不知道的事", 会带 [NEW_GENERATION_BANNER] 标记。
```

UNHEALTHY 兜底变体（无 producer 产文档时追加）：

```
[DEGRADED_HANDOFF_NOTICE]
前任未能产生合规 handoff 文档。
以上 JSON 是 {snapshot_age_minutes} 分钟前自动落盘的快照, 可能不是最新状态。
请优先用 Read/Grep 核对文件状态, 用 update_state 修正过时字段。
```

### 6.4 INITIAL_BOOTSTRAP 模板内容（第一代）

```
你是 Pair {pairId} 的初代 supervisor。本会话刚启动, 没有前任。

任务背景:
- Plan: {planSummary}
- Spec: {specSummary}  (可能为空)

接下来你会收到主 AI 的事件流。开始监督前, 第一件事是通过 update_state 工具
初始化 anchoredFacts（currentStep / totalSteps / currentStepTitle）。
```

### 6.5 COMPOSITE_RUNTIME 模板（每 tick 注入 inputStream）

```
=== Events batch ({batchStartTime} ~ {batchEndTime}, {N} events, urgent={U}) ===

{eventListRendered}

{droppedNotice if any dropped}
```

`eventListRendered` 示例：

```
- [T+5s, NORMAL]   turn_end #12: 编辑 a.go (-3,+15 行), 工具调用 4 次, 850 tokens
- [T+18s, NORMAL]  turn_end #13: 运行 go test, pass
- [T+22s, URGENT]  off_plan_detected: 改动了 unrelated.go (不在 step5 计划内)
- [T+25s, URGENT]  verify_result: go vet 失败 (2 warnings)
```

Rotation 后首次 tick 前置 banner：

```
[NEW_GENERATION_BANNER]
你刚接管这个 pair (gen={N}). 在你产生 handoff 到现在的 {T} 秒里,
主 AI 又跑了 {K} 个 turn, 以下事件是你接管前发生但你刚才不知道的:
  - turn 14 @ +5s: ...
  - turn 15 @ +20s: ...

正常事件流随后开始。
```

---

## 七、并发与状态机

### 7.1 PairCoordinator 设计

```java
public class PairCoordinator {
    public enum State { IDLE, MAIN_TURN, TICK, ROTATING }

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean rotateRequested = new AtomicBoolean(false);
    private final Logger LOG;
    private final String pairId;

    /** 拿读锁: 主 AI turn / supervisor tick 互不阻塞, 但 rotation 时全部 block. */
    public void enterNormalOp(State op) throws InterruptedException {
        if (op == State.ROTATING) throw new IllegalArgumentException();
        boolean acquired = rwLock.readLock().tryLock(60, TimeUnit.SECONDS);
        if (!acquired) throw new IllegalStateException("readLock timeout, rotation may be stuck");
        state.compareAndSet(State.IDLE, op);
    }

    public void exitNormalOp() {
        state.compareAndSet(state.get(), State.IDLE);
        rwLock.readLock().unlock();
    }

    /** 拿写锁: 排他, 会等所有 reader 释放. 60s timeout. */
    public boolean enterRotation() throws InterruptedException {
        boolean ok = rwLock.writeLock().tryLock(60, TimeUnit.SECONDS);
        if (!ok) {
            LOG.warn("[PairCoordinator] enterRotation timeout for " + pairId);
            return false;
        }
        state.set(State.ROTATING);
        return true;
    }

    public void exitRotation() {
        state.set(State.IDLE);
        rwLock.writeLock().unlock();
    }

    public boolean isRotating() { return state.get() == State.ROTATING; }
    public State getState() { return state.get(); }

    public void requestRotation() { rotateRequested.set(true); }
    public boolean takeRotationRequest() { return rotateRequested.compareAndSet(true, false); }
}
```

### 7.2 状态转移

```
                    user 发消息
        IDLE ────────────────────────► MAIN_TURN
         ▲                                  │
         │ turn_end                         │
         └──────────────────────────────────┘

         ▲ monitor 定时 / urgent
         │
        IDLE ────────────────────────► TICK
         ▲                                  │
         │ tick 结束                        │
         └──────────────────────────────────┘

         ▲ RotationDecider 拿写锁成功
         │
        IDLE ────────────────────────► ROTATING
         ▲                                  │
         │ rotate 完成                      │
         └──────────────────────────────────┘
```

互斥规则：
- `MAIN_TURN` 和 `TICK` **可并发**（都是读锁，两条独立线程）
- `ROTATING` 排他，与上述两者**互斥**
- `tick` 内**不允许**升级为 `ROTATING`（会死锁），只能 `requestRotation()` 后退出，由 `RotationDecider` 独立拿写锁

### 7.3 RotationDecider 单独存在的原因

不能让 `Monitor.tick` 在持读锁时尝试拿写锁（ReentrantReadWriteLock 不支持升级）。所以拆出独立组件：

```java
public class RotationDecider {
    // 共享 scheduler 即可, 1s 一次
    void scheduledCheck() {
        for (PairSession pair : sessions) {
            if (pair.coordinator.takeRotationRequest()) {
                executor.submit(() -> {
                    if (pair.coordinator.enterRotation()) {
                        try { rotationCoordinator.execute(pair); }
                        finally { pair.coordinator.exitRotation(); }
                    } else {
                        pair.coordinator.requestRotation(); // 重排
                    }
                });
            }
        }
    }
}
```

---

## 八、核心组件设计

### 8.1 EventCollector

```java
public class EventCollector {
    private static final int MAX_BUFFER = 200;
    private static final Set<String> URGENT_TYPES = Set.of(
        "error", "off_plan_detected", "plan_complete", "human_response"
        // verify_result 单独判断 (pass=false 才 urgent)
    );

    private final Deque<JsonObject> buffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger droppedCount = new AtomicInteger();
    private final AtomicLong lastUrgentFireMs = new AtomicLong();
    private final SupervisorMonitor monitor;  // back-ref for cancel/reschedule

    public void publish(JsonObject event) {
        // 入队 (bounded)
        while (buffer.size() >= MAX_BUFFER) {
            buffer.pollFirst();
            droppedCount.incrementAndGet();
        }
        buffer.addLast(event);

        // urgent path: B + 2s 防抖
        if (isUrgent(event) && !monitor.isTickInProgress()) {
            long now = System.currentTimeMillis();
            long debounce = "human_response".equals(getType(event)) ? 200 : 2000;
            if (now - lastUrgentFireMs.get() > debounce) {
                if (lastUrgentFireMs.compareAndSet(lastUrgentFireMs.get(), now)) {
                    monitor.urgentReschedule();
                }
            }
        }
    }

    /** Monitor.tick 调用, 原子 drain + 重置 dropped 计数. */
    public DrainResult drainAll() {
        List<JsonObject> batch = new ArrayList<>();
        JsonObject e;
        while ((e = buffer.pollFirst()) != null) batch.add(e);
        int dropped = droppedCount.getAndSet(0);
        return new DrainResult(batch, dropped);
    }

    private boolean isUrgent(JsonObject event) {
        String type = getType(event);
        if (URGENT_TYPES.contains(type)) return true;
        if ("verify_result".equals(type)) {
            JsonObject payload = event.getAsJsonObject("payload");
            return payload != null && !payload.get("pass").getAsBoolean();
        }
        return false;
    }
}
```

### 8.2 SupervisorMonitor

```java
public class SupervisorMonitor {
    private static final long TICK_INTERVAL_MS = 30_000;
    private static final long POST_EVENT_TIMEOUT_MS = 90_000;
    private static final int HEALTH_CHECK_EVERY_N_TICKS = 5;

    public enum Health { HEALTHY, DEGRADED, UNHEALTHY }

    private final PairSession pair;
    private final EventCollector collector;
    private final SupervisorBridge bridge;
    private final ScheduledExecutorService scheduler;
    private final L2Store l2Store;
    private final AtomicReference<Health> health = new AtomicReference<>(Health.HEALTHY);
    private final AtomicBoolean tickInProgress = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger tickCounter = new AtomicInteger();
    private volatile ScheduledFuture<?> nextTickFuture;

    public void start() {
        scheduleNextTick(TICK_INTERVAL_MS);
    }

    public boolean isTickInProgress() { return tickInProgress.get(); }

    /** EventCollector 调用: urgent 事件来了, 重排. */
    public void urgentReschedule() {
        ScheduledFuture<?> cur = nextTickFuture;
        if (cur != null && cur.cancel(false)) {
            scheduleNextTick(1000);
        }
    }

    private void scheduleNextTick(long delayMs) {
        nextTickFuture = scheduler.schedule(this::runTick, delayMs, TimeUnit.MILLISECONDS);
    }

    private void runTick() {
        if (!tickInProgress.compareAndSet(false, true)) return;
        try {
            doTick();
            consecutiveFailures.set(0);
            transitionHealth(Health.HEALTHY);
        } catch (TimeoutException te) {
            int f = consecutiveFailures.incrementAndGet();
            if (f == 1) {
                transitionHealth(Health.DEGRADED);
                tryInterrupt();
            } else if (f >= 2) {
                transitionHealth(Health.UNHEALTHY);
                pair.coordinator.requestRotation();
            }
        } catch (Exception e) {
            LOG.warn("[Monitor] tick failed: " + e.getMessage());
            transitionHealth(Health.DEGRADED);
        } finally {
            tickInProgress.set(false);
            // 检查 generation 上限
            if (l2Store.read(pair.getPairId()).generation >= 5) {
                pair.coordinator.requestRotation();
            }
            scheduleNextTick(TICK_INTERVAL_MS);
        }
    }

    private void doTick() throws Exception {
        pair.coordinator.enterNormalOp(PairCoordinator.State.TICK);
        try {
            EventCollector.DrainResult batch = collector.drainAll();
            int tickN = tickCounter.incrementAndGet();
            boolean shouldHealthCheck = tickN % HEALTH_CHECK_EVERY_N_TICKS == 0;

            if (batch.events.isEmpty() && !shouldHealthCheck) {
                return; // 真正空 tick
            }

            // composite summary
            String summary = CompositeSummaryBuilder.build(batch, pair);

            // 发 daemon
            CompletableFuture<JsonObject> fut = bridge.postEvent(summary);
            JsonObject actionWrapper = fut.get(POST_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // dispatch
            pair.getActionRouter().dispatch(actionWrapper);

            // health check (idle tick 也要做)
            if (shouldHealthCheck) {
                JsonObject usage = bridge.getContextUsage().get(5, TimeUnit.SECONDS);
                pair.getUsagePushService().updateContextUsage(usage);
                checkRotationTriggers(usage);
            }
        } finally {
            pair.coordinator.exitNormalOp();
        }
    }

    private void checkRotationTriggers(JsonObject usage) {
        double ratio = usage.get("ratio").getAsDouble();
        int compactCount = l2Store.read(pair.getPairId()).compactionHistory.size();
        if (ratio >= 0.95 || compactCount >= 5) {
            pair.coordinator.requestRotation(); // 硬
        } else if (ratio >= 0.85 || compactCount >= 3) {
            // 软: 等到下个 IDLE 再 rotate, 已通过 requestRotation 排队
            pair.coordinator.requestRotation();
        }
    }

    private void tryInterrupt() {
        try { bridge.interrupt().get(5, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
    }

    private void transitionHealth(Health newH) {
        Health old = health.getAndSet(newH);
        if (old != newH) {
            pair.getStatusPusher().push(newH);
            LOG.info("[Monitor] " + pair.getPairId() + " health: " + old + " -> " + newH);
        }
    }
}
```

### 8.3 RotationCoordinator

见 §10 详细 14 步流程。

### 8.4 L2Store

```java
public class L2Store {
    private final Path baseDir; // ~/.codemoss/pairs
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, L2State> cache = new ConcurrentHashMap<>();
    private final Gson gson = createGson();

    public L2State read(String pairId) {
        return cache.computeIfAbsent(pairId, this::loadFromDisk);
    }

    public void update(String pairId, Function<L2State, L2State> mutator) {
        ReentrantLock lock = locks.computeIfAbsent(pairId, k -> new ReentrantLock());
        lock.lock();
        try {
            L2State old = cache.get(pairId);
            L2State next = mutator.apply(old.deepCopy());
            L2Validator.validate(next); // schema check
            writeAtomic(pairId, next);
            cache.put(pairId, next);
        } finally {
            lock.unlock();
        }
    }

    /** Rotation 后立刻调用: cp state.json -> state.json.bak. */
    public void snapshotBackup(String pairId) {
        ReentrantLock lock = locks.computeIfAbsent(pairId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path src = stateFile(pairId);
            Path bak = bakFile(pairId);
            if (Files.exists(src)) {
                Files.copy(src, bak, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("L2 bak failed for " + pairId);
        } finally {
            lock.unlock();
        }
    }

    private L2State loadFromDisk(String pairId) {
        Path state = stateFile(pairId);
        Path bak = bakFile(pairId);
        try {
            if (Files.exists(state)) return parseAndValidate(state);
        } catch (Exception primary) {
            LOG.warn("L2 primary load failed, trying bak: " + primary);
            try { if (Files.exists(bak)) return parseAndValidate(bak); }
            catch (Exception secondary) {
                LOG.error("L2 bak also failed: " + secondary);
            }
        }
        return L2State.initial(pairId);
    }

    private void writeAtomic(String pairId, L2State state) {
        try {
            Path target = stateFile(pairId);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            String json = gson.toJson(state);
            Files.writeString(tmp, json, StandardOpenOption.CREATE,
                              StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("L2 write failed for " + pairId, e);
        }
    }
}
```

### 8.5 MainAIMonitor

```java
public class MainAIMonitor {
    private static final long STALL_MS = 5 * 60_000;

    private final ClaudeSession session;
    private final ScheduledExecutorService scheduler;
    private final L2Store l2Store;
    private final ClaudeSDKBridge sdkBridge;
    private final AtomicLong currentTurnStartedAt = new AtomicLong();
    private final AtomicReference<ScheduledFuture<?>> stallDetector = new AtomicReference<>();
    private final AtomicInteger compactCount = new AtomicInteger();

    public void onTurnStart() {
        currentTurnStartedAt.set(System.currentTimeMillis());
        ScheduledFuture<?> old = stallDetector.getAndSet(
            scheduler.schedule(this::onStall, STALL_MS, TimeUnit.MILLISECONDS));
        if (old != null) old.cancel(false);
    }

    public void onTurnEnd() {
        ScheduledFuture<?> f = stallDetector.getAndSet(null);
        if (f != null) f.cancel(false);
        // async getContextUsage + 更新 mainL2
        sdkBridge.getContextUsage(session.getSessionId()).thenAccept(usage -> {
            l2Store.update(session.getPairId(), s -> {
                s.mainAIContext = usage;
                return s;
            });
            checkRotationTriggers(usage);
        });
    }

    public void onCompactBoundary() {
        int n = compactCount.incrementAndGet();
        l2Store.update(session.getPairId(), s -> {
            s.compactionHistory.add(new Compaction(Instant.now(), "main_ai_sdk_auto"));
            return s;
        });
    }

    private void onStall() {
        long elapsed = System.currentTimeMillis() - currentTurnStartedAt.get();
        StatusBus.push(session.getPairId(),
            new StallAlert(elapsed, List.of("interrupt", "wait")));
        // 不自动 interrupt, 让用户决定
    }
}
```

---

## 九、触发条件矩阵

| 触发源 | 条件 | 紧急度 | handoff 方式 | rotate 类型 |
|---|---|---|---|---|
| Monitor health check | `ratio >= 0.85` | 软 | producer 正常 | 计划性 |
| Monitor health check | `compactCount >= 3` | 软 | producer 正常 | 计划性 |
| Monitor health check | `ratio >= 0.95` OR `compactCount >= 5` | 硬 | producer 正常 | 优先 |
| Monitor tick fail | UNHEALTHY 持续 2 tick | 紧急 | **L2 兜底**, 无 producer | 救火 |
| L2 read | `generation >= 5` | 软 | producer + **archive knownConstraints** | 周期性 |
| 用户手动 | 点 "重置 supervisor" | 立即 | producer 正常 | 手工 |
| 冷却保护 | 上次 rotate < 5min | **推迟所有软触发** | - | - |

冷却实现：`L2State.lastRotation.at` 距今 < 5min 时，`RotationCoordinator.execute` 在 step 0 检查 + 拒绝。

---

## 十、Rotation 原子流程（14 步详解）

```java
public class RotationCoordinator {

    public RotationResult execute(PairSession pair) {
        String pairId = pair.getPairId();
        L2State l2 = l2Store.read(pairId);

        // === 0. 冷却检查 ===
        if (l2.lastRotation != null
            && Duration.between(l2.lastRotation.at, Instant.now()).toMinutes() < 5) {
            LOG.info("rotate cooldown for " + pairId);
            return RotationResult.cooldown();
        }

        // === 1. CAS gate (已经在 RotationDecider 拿到写锁) ===
        // 进入这里时 PairCoordinator.state == ROTATING, 新事件继续入 buffer 但 tick 阻塞

        // === 2. 暂停 EventCollector publish 之外的处理 ===
        // (collector.publish 不停, 但 monitor.runTick 在 enterNormalOp 时已被 block)

        // === 3. 等当前 tick 完成 ===
        // 因为 enterRotation 已经拿到 writeLock, 上一个 tick (readLock) 必然已释放
        // 这里只需 sanity check
        if (pair.getMonitor().isTickInProgress()) {
            LOG.error("invariant broken: tick in progress under writeLock");
            return RotationResult.failed("invariant");
        }

        // === 4. 读 L2 snapshot ===
        L2State snapshot = l2.deepCopy();

        // === 5. 老会话产 handoff doc ===
        String handoffJson = null;
        String handoffSource = "producer";
        Health curHealth = pair.getMonitor().getHealth();
        if (curHealth != UNHEALTHY) {
            try {
                handoffJson = produceHandoffWithRetry(pair, snapshot);
            } catch (Exception e) {
                LOG.warn("produceHandoff failed, fallback to L2: " + e.getMessage());
                handoffJson = null;
            }
        }
        if (handoffJson == null) {
            handoffJson = gson.toJson(snapshot);
            handoffSource = curHealth == UNHEALTHY ? "l2_unhealthy" : "l2_fallback";
        }

        // === 6. 校验 + 合并到 snapshot ===
        L2State merged;
        try {
            L2State fromProducer = gson.fromJson(handoffJson, L2State.class);
            L2Validator.validate(fromProducer);
            merged = L2Merger.mergeProducerIntoSnapshot(snapshot, fromProducer);
        } catch (Exception e) {
            LOG.warn("handoff validate failed, using raw L2");
            merged = snapshot;
        }

        // === 7. archive knownConstraints if generation >= 5 ===
        if (merged.generation >= 5) {
            archiveKnownConstraints(pairId, merged);
            merged.knownConstraints = new ArrayList<>();
            merged.generation = 0; // 重置, 但保留 anchoredFacts
        }

        // === 8. 生成 successor prompt ===
        String successorPrompt = SuccessorPromptBuilder.render(merged, handoffSource);

        // === 9. start 新 supervisor ===
        String newSupervisorId = UUID.randomUUID().toString();
        Boolean started;
        try {
            started = pair.getSupervisorBridge().startWithHandoff(
                pair.getAgentName(),
                pair.getAgentDescription(),
                pair.getPlanContent(),
                pair.getProjectSpec(),
                pair.getModel(),
                pair.getAutoCompactThreshold(),
                newSupervisorId,
                successorPrompt
            ).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("new supervisor start failed: " + e.getMessage());
            // 老 supervisor 还活着, 不切, 解锁让正常流程继续
            return RotationResult.failed("new_start: " + e.getMessage());
        }
        if (!Boolean.TRUE.equals(started)) {
            return RotationResult.failed("new_start returned false");
        }

        // === 10. stop 老 supervisor ===
        String oldSupervisorId = pair.getSupervisorId();
        try {
            pair.getSupervisorBridge().stopById(oldSupervisorId).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("old supervisor stop failed (will GC): " + e.getMessage());
            // 不阻塞, daemon 进程 GC 会兜底
        }

        // === 11. 原子 swap supervisorId ===
        pair.swapSupervisorId(newSupervisorId);

        // === 12. 更新 L2: generation++, lastRotation ===
        merged.generation += 1;
        merged.rotationCount += 1;
        merged.lastRotation = new RotationInfo(
            oldSupervisorId, newSupervisorId, Instant.now(),
            inferReason(snapshot), handoffSource
        );
        l2Store.update(pairId, s -> merged);

        // === 13. snapshot .bak ===
        l2Store.snapshotBackup(pairId);

        // === 14. 通知 webview + 设置 NEW_GENERATION_BANNER ===
        pair.getStatusPusher().pushRotationBoundary(merged.generation, inferReason(snapshot));
        pair.getMonitor().pendingGenerationBanner(merged.generation);
        // 不在这里 resume / unlock — 由 RotationDecider 的 finally 块 exitRotation
        return RotationResult.success(newSupervisorId);
    }

    private String produceHandoffWithRetry(PairSession pair, L2State snapshot) throws Exception {
        // 第 1 次尝试: 30s timeout
        String json = pair.getSupervisorBridge()
            .produceHandoff(snapshot)
            .get(30, TimeUnit.SECONDS);
        try {
            L2Validator.validate(gson.fromJson(json, L2State.class));
            return json;
        } catch (Exception e) {
            // 第 2 次 (1 次重试): 30s timeout, prompt 里附 errorMessages
            String retried = pair.getSupervisorBridge()
                .produceHandoffRetry(snapshot, e.getMessage())
                .get(30, TimeUnit.SECONDS);
            L2Validator.validate(gson.fromJson(retried, L2State.class));
            return retried;
        }
    }
}
```

边界情况处理：
- step 5-6 失败 → 走 L2 兜底，不阻塞
- step 9 失败 → 老 supervisor 不动，return failed，让用户/下次 tick 重试
- step 10 失败 → 不阻塞 swap，daemon 进程 GC 兜底
- step 11 之后失败 → L2 不一致，但 supervisor 已经切，下次 tick 会基于新代继续

---

## 十一、urgent 事件清单 + 防抖参数

```java
public final class UrgentPolicy {
    // 不可变, 调试时改这里
    public static final Map<String, Long> DEBOUNCE_MS = Map.of(
        "human_response",        200L,   // 用户主动操作, 几乎立即
        "error",                1000L,   // 主 AI 报错
        "off_plan_detected",    1000L,   // 偏离 plan
        "plan_complete",        1000L,   // 用户等切下一 plan
        "verify_result_fail",   2000L    // verify 失败可能连发, 略加防抖
    );

    public static boolean isUrgent(JsonObject event) {
        String type = event.get("type").getAsString();
        if (DEBOUNCE_MS.containsKey(type)) return true;
        if ("verify_result".equals(type)) {
            JsonObject p = event.getAsJsonObject("payload");
            return p != null && p.has("pass") && !p.get("pass").getAsBoolean();
        }
        return false;
    }

    public static long debounceMs(JsonObject event) {
        String type = event.get("type").getAsString();
        if ("verify_result".equals(type)) return DEBOUNCE_MS.get("verify_result_fail");
        return DEBOUNCE_MS.getOrDefault(type, 2000L);
    }
}
```

---

## 十二、异常恢复矩阵

| 异常 | 检测 | 第一响应 | 升级 | 用户可见 |
|---|---|---|---|---|
| `query.next()` hang | tick 超时 90s | 调 `query.interrupt()`, 下 tick 重试 | 连 2 次 → 触发 rotate | DEGRADED 状态 + 日志 |
| daemon 进程死 | `postEvent` 抛 `SUPERVISOR_NOT_FOUND` | EventBus 已有的 lazy restart | restart 失败 → rotate | "supervisor 服务暂时不可达" |
| handoff producer timeout 30s | RotationCoordinator step 5 | 走 L2 兜底, 加 degraded handoff notice | - | "前任 handoff 不合规, 用快照" |
| handoff schema 不合 | step 6 validate fail | 重试 1 次 (附 errorMessages) | 仍失败 → L2 | 同上 |
| new supervisor start 失败 | step 9 timeout/error | 不 swap, 老 supervisor 保留 | 下个 tick rotate 标记仍在, 会重试 | "rotation 失败, 已回退" |
| old supervisor stop 失败 | step 10 timeout | 忽略, swap 继续 | daemon 进程 GC | 不可见 |
| L2 write 失败 | step 12 IOException | 缓存仍有效, 报警 | - | "L2 持久化失败" |
| L2 文件损坏 | startup parse fail | 回退 `.bak` | `.bak` 也坏 → 视为新 pair | 启动日志 |
| StallDetector 触发 (主 AI) | 5min 无 onTurnEnd | 推 webview, 提供 [中断]/[等] | 用户选择 | 提示横幅 |
| `compact_boundary` 接连 5+ | L2 compactionHistory 计数 | requestRotation 软 | - | Pair 面板红字 |
| generation >= 5 | tick 末检查 | requestRotation + archive constraints | - | "已 archive 历史约束" |

---

## 十三、接口定义

### 13.1 Daemon supervisor-channel 扩展接口

新增/修改：

```js
// 新增: 健康查询
supervisor.health(pairId, supervisorId) -> {
  alive: true,
  lastActivityMs: 12345,
  contextUsage: {ratio, breakdown: {systemPrompt, tools, messages, ...}},
  compactCount: 2,
  inputStreamPending: 0
}

// 新增: 手动 interrupt
supervisor.interrupt(pairId, supervisorId) -> {interrupted: true}

// 新增: produce handoff doc
supervisor.produceHandoff(pairId, supervisorId, {currentL2}) -> {
  handoffJson: "...",
  durationMs: 12345
}

// 新增: produce handoff 重试 (附 errors)
supervisor.produceHandoffRetry(pairId, supervisorId, {currentL2, lastErrors}) -> {...}

// 新增: 直接拿 SDK getContextUsage
supervisor.getContextUsage(pairId, supervisorId) -> SDKControlGetContextUsageResponse

// 修改: start 接受 successorPrompt
supervisor.start({
  ...existing,
  successorPromptAppend: string | null,   // 新代用
  initialBootstrapPromptAppend: string | null,  // 初代用
  generation: number
})

// 修改: postEvent 强制带 timeout (内部包 Promise.race)
supervisor.postEvent({pairId, supervisorId, eventBatch})  // 注意: 现在传 batch
```

新增 MCP tool：

```js
// 在 supervisor-tools.js 注册
{
  name: "update_state",
  description: "Update specific L2 fields. Use for anchored facts / plan progress / file state changes.",
  inputSchema: {
    anchoredFacts: {currentStep, currentStepTitle, blockedOn, lastVerifyCmd, lastVerifyResult},
    planProgressDelta: [{step, status, ...}],
    fileStateDelta: {"path": {mtime, lastTouchedBy, linesChanged}},
    decisionAppend: {action, reason, payload, result, confidence}
  }
}
```

调用 `update_state` 时 daemon 写 stdout：
```
[STATE_UPDATE] {"pairId":"...", "delta":{...}}
```

Java 端 SupervisorBridge 监听这条 line，转发给 `L2Store.update`。

### 13.2 Java 内部 API

新增类：

```
com.github.claudecodegui.session.pair.coordinator
├── PairCoordinator.java
├── EventCollector.java
└── UrgentPolicy.java

com.github.claudecodegui.session.pair.monitor
├── SupervisorMonitor.java
├── MainAIMonitor.java
├── StallDetector.java
└── HealthState.java (enum)

com.github.claudecodegui.session.pair.rotation
├── RotationDecider.java
├── RotationCoordinator.java
├── RotationResult.java
├── RotationInfo.java
└── L2Merger.java

com.github.claudecodegui.session.pair.l2
├── L2Store.java
├── L2State.java (POJO)
├── L2Validator.java
├── L2Migration.java
└── L2Schema.java (常量)

com.github.claudecodegui.session.pair.prompt
├── SupervisorPromptBuilder.java
├── SuccessorPromptBuilder.java
├── HandoffProducerPromptBuilder.java
└── CompositeSummaryBuilder.java

com.github.claudecodegui.session.pair.status
├── PairStatusPusher.java
├── PairStatusSnapshot.java
└── RotationBoundaryEvent.java
```

修改类：

```
SupervisorBridge.java
  + startWithHandoff(...)
  + stopById(...)
  + interrupt(...)
  + health(...)
  + getContextUsage(...)
  + produceHandoff(...) / produceHandoffRetry(...)
  + listens to [STATE_UPDATE] lines

EventBus.java
  publish() 改为入队 EventCollector, 不再阻塞同步
  保留 publishSync() 给手动 / 测试用 (灰度迁移)

PairSession.java
  + coordinator: PairCoordinator
  + monitor: SupervisorMonitor
  + eventCollector: EventCollector
  + swapSupervisorId(newId) 方法

PairSessionManager.java
  + 启动时为每个 pair 创建 monitor + decider
  + 销毁时关 scheduler

ClaudeMessageHandler.java
  onTurnStart / onTurnEnd 加 MainAIMonitor 钩子
```

### 13.3 Webview 事件 (Java → webview)

```typescript
window.onPairStatusUpdate(json: {
  pairId: string,
  state: "IDLE" | "MAIN_TURN" | "TICK" | "ROTATING",
  health: "HEALTHY" | "DEGRADED" | "UNHEALTHY",
  generation: number,
  contextRatio: {supervisor: number, mainAI: number},
  compactCount: {supervisor: number, mainAI: number},
  lastActivityAgoMs: number,
  pendingEvents: number,
  recentAlerts: Alert[],
  recentRotations: RotationInfo[]
})

window.onPairRotationBoundary(json: {
  pairId: string,
  fromGen: number, toGen: number,
  reason: string,
  handoffSource: "producer" | "l2_fallback" | "l2_unhealthy",
  at: string
})

window.onPairStall(json: {
  pairId: string,
  elapsedMs: number,
  actions: ["interrupt", "wait"]
})
```

---

## 十四、可观测性：Pair 状态面板

### 14.1 UI 位置

webview 右侧 supervisor 面板顶部，可折叠的小卡片：

```
┌─ Pair p_abc12345                            Generation 2 ─┐
│ State: TICK (processing 3 events)            ●HEALTHY      │
│ Context: sup 67% (cx1)  /  main 42% (cx0)                 │
│ Last activity: 12s ago | Pending: 3 (1 urgent)            │
│                                                            │
│ [▼ Recent rotations]                                       │
│   gen 0 → 1   14:12  reason=ratio>=0.85   src=producer    │
│   gen 1 → 2   14:38  reason=compact>=3    src=producer    │
│                                                            │
│ [▼ Recent alerts]                                          │
│   14:35  tick timeout (recovered via interrupt)            │
│   14:36  handoff schema invalid (retried, ok)             │
│                                                            │
│ [Force rotate] [Open L2 viewer]                            │
└────────────────────────────────────────────────────────────┘
```

### 14.2 数据来源

`PairStatusPusher` 每 5s 推送一次完整快照（不变才不推），rotation/stall 实时推。

### 14.3 日志规范

```
[Monitor]      tick 生命周期 (start/end/timeout/health change)
[Rotation]     14 步流程, 每步 ENTER/EXIT 一行
[L2]           write/read/bak/load/validate
[Coordinator]  锁 acquire/release/timeout
[Bridge]       postEvent/produceHandoff/interrupt 的 latency
```

所有日志带 `pairId` + `generation` 前缀，便于 grep。

---

## 十五、分阶段实施

每个 Phase 独立可验收、可上线、可回滚。

### Phase 0 — 止血（1-2 天，无架构改动）

**目标**: 消除当前的沉默假死，让现有 Pair 用户立即受益。

**文件清单**:

```
修改:
  ai-bridge-server/ai-bridge/channels/supervisor-channel.js
    L441: query.next() 包 Promise.race + 90s timeout
    L325-333: enqueue 前检查 inputStream.size, 限 200
    L286-363: postEvent 末尾 finally 块强制 release runtime.busy
  
  ai-bridge-server/ai-bridge/utils/async-stream.js
    新增 maxSize 参数, queue.length >= maxSize 时 shift 并发 [DROPPED] event
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/EventBus.java
    L195: .join() 改 .orTimeout(120, SECONDS)
    catch TimeoutException → router.dispatchTransportError + signalThinking(false)
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/bridge/SupervisorBridge.java
    L153-162: messageHandler == null 时改为 push to bufferQueue, 而非 silent drop
```

**关键代码**:

```js
// supervisor-channel.js postEvent 改造
const QUERY_NEXT_TIMEOUT_MS = 90_000;

async function nextWithTimeout(query) {
  return Promise.race([
    query.next(),
    new Promise((_, rej) => setTimeout(
      () => rej(new Error('SUPERVISOR_QUERY_TIMEOUT')), QUERY_NEXT_TIMEOUT_MS
    ))
  ]);
}

// collectAssistantTurn 内:
next = await nextWithTimeout(runtime.query);
```

```js
// async-stream.js 改造
export class AsyncStream {
  constructor(maxSize = 200) {
    this.queue = [];
    this.maxSize = maxSize;
    this.dropped = 0;
    // ...
  }
  
  enqueue(value) {
    if (this.queue.length >= this.maxSize) {
      this.queue.shift();
      this.dropped++;
      process.stdout.write(`[STREAM_DROPPED] count=${this.dropped}\n`);
    }
    // ... existing
  }
}
```

**验收标准**:
- 手动场景 1: kill -STOP daemon process，120s 内 webview 收到 transport_error，不卡 supervisor pane
- 手动场景 2: 让 supervisor 进入死循环（mock 一个会让 SDK 反复调工具的事件），看到 90s 内 SUPERVISOR_QUERY_TIMEOUT 日志 + Java 端收到错误
- 测试场景 3: 暴力 publish 1000 个 event，buffer 不爆，[STREAM_DROPPED] 计数正常

**回滚**: 一个 git revert commit。

---

### Phase 1 — Monitor 骨架（3-5 天）

**目标**: 把同步阻塞模型换成 collector + monitor 周期处理，引入 HEALTHY/DEGRADED 状态机。

**文件清单**:

```
新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/coordinator/
    PairCoordinator.java
    EventCollector.java
    UrgentPolicy.java
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/monitor/
    SupervisorMonitor.java
    HealthState.java
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/prompt/
    CompositeSummaryBuilder.java
  
  jetbrains-cc-gui/test/java/.../EventCollectorTest.java
  jetbrains-cc-gui/test/java/.../SupervisorMonitorTest.java (用 FakeClock)
  jetbrains-cc-gui/test/java/.../PairCoordinatorConcurrencyTest.java

修改:
  EventBus.java
    publish() 路径: 改为 collector.publish() 后立即返回
    保留 publishSync() (灰度: 通过 Registry.is("pair.monitor.enabled") 切)
  
  PairSession.java
    新增 coordinator/monitor/collector 字段, 构造时 wire 起来
  
  PairSessionManager.java
    创建 pair 时 start monitor
    destroy pair 时 monitor.stop() + scheduler shutdown
  
  ai-bridge-server/ai-bridge/channels/supervisor-channel.js
    postEvent 接受 composite event (eventBatch 字段)
    summarizeEvent 路径不变, 但 inputStream 注入 batch 整体
```

**关键代码**:

见 §8.1 / §8.2 完整实现。

**验收标准**:
- 单元测试: FakeClock 推进 30s，tick 自动 fire；publish urgent → 1s 内 fire
- 集成测试: 真 daemon，rapid publish 10 个 turn_end，monitor 合并成 1 个 tick 处理
- 故障测试: tick 内部强制抛 TimeoutException，连续 2 次后 health 变 UNHEALTHY 且 `pair.coordinator.takeRotationRequest()` 返回 true（但 Phase 1 没 RotationCoordinator，只验标记）
- 性能: collector.publish() 微基准 < 100us

**回滚**: Registry flag 切回 `publishSync`。

---

### Phase 2 — 上下文感知（2-3 天）

**目标**: 真实 context% 替换粗略估算，compact_count 入 L2（提前用 inMemory map，Phase 3 落盘）。

**文件清单**:

```
新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/status/
    PairStatusPusher.java
    PairStatusSnapshot.java

修改:
  ai-bridge-server/ai-bridge/channels/supervisor-channel.js
    新增 supervisor.health 接口
    新增 supervisor.getContextUsage 接口 (直接 await runtime.query.getContextUsage())
    compact_boundary 事件: 写 [COMPACT_BOUNDARY] line
  
  SupervisorBridge.java
    + health() + getContextUsage() + interrupt() 方法
    + 监听 [COMPACT_BOUNDARY] line, 累加 compactCount
  
  SupervisorMonitor.java
    tick 末尾每 5 次调 getContextUsage, 推 PairStatusPusher
  
  UsagePushService.java
    替换粗略估算, 使用 SDK 真实 ratio
  
  webview/src/components/SupervisorPair/PairStatusBar.tsx (新建)
  webview/src/App.tsx 注册 onPairStatusUpdate handler
```

**关键代码**:

```js
// supervisor-channel.js
export async function getContextUsage({ pairId, supervisorId }) {
  const runtime = runtimes.get(key(pairId, supervisorId));
  if (!runtime) throw new Error('SUPERVISOR_NOT_FOUND');
  return await runtime.query.getContextUsage();
}

// 在 collectAssistantTurn 内识别 compact_boundary:
if (msg.type === 'system' && msg.subtype === 'compact_boundary') {
  process.stdout.write(`[COMPACT_BOUNDARY] ${JSON.stringify({
    pairId, supervisorId, ts: Date.now(), metadata: msg.compact_metadata
  })}\n`);
}
```

**验收标准**:
- webview 显示真实 ratio（与 daemon 日志比对）
- 触发一次 compact_boundary，webview Pair 面板 cx 计数 +1
- monitor 在 ratio >= 0.85 时设 rotateRequested = true（Phase 2 验观察）

---

### Phase 3 — L2 持久化（3-4 天）

**目标**: L2State 落盘，daemon 通过 update_state MCP tool 触发 Java 写盘。

**文件清单**:

```
新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/l2/
    L2Store.java
    L2State.java (POJO + Gson 适配)
    L2Validator.java
    L2Migration.java
    L2Schema.java
  
  ai-bridge-server/ai-bridge/services/supervisor/
    update-state-tool.js (新 MCP tool, 注册到 supervisor-tools.js)
    pre-compact-hook.js (PreCompact hook)

修改:
  SupervisorBridge.java
    监听 [STATE_UPDATE] line → L2Store.update()
  
  SupervisorAgentManager.java
    SupervisorAgent 类追加 baseRulesTemplate 字段
  
  supervisor-channel.js startSupervisorSession
    options.hooks.PreCompact = [{ hooks: [preCompactHook] }]
  
  webview L2 viewer 简单只读 (Phase 3 可选, Phase 4 必做)
```

**关键代码**:

```js
// update-state-tool.js
export const updateStateTool = {
  name: "update_state",
  description: "Update L2 state. Use after anchored fact changes / plan progress / file edits.",
  inputSchema: {
    type: "object",
    properties: {
      anchoredFactsDelta: { type: "object" },
      planProgressDelta: { type: "array" },
      fileStateDelta: { type: "object" },
      decisionAppend: { type: "object" },
      constraintAdd: { type: "string" }
    }
  },
  handler: async (input, ctx) => {
    process.stdout.write(`[STATE_UPDATE] ${JSON.stringify({
      pairId: ctx.pairId, supervisorId: ctx.supervisorId, delta: input
    })}\n`);
    return { ok: true };
  }
};

// pre-compact-hook.js
export async function preCompactHook(input, ctx) {
  // SDK 即将压缩, 把当前 L2 snapshot 落盘
  process.stdout.write(`[PRE_COMPACT] ${JSON.stringify({
    pairId: ctx.pairId, supervisorId: ctx.supervisorId, ts: Date.now()
  })}\n`);
  return { continue: true };
}
```

**验收标准**:
- supervisor 调 update_state，Java 端 L2 文件被写入 + .bak 同步
- 强制 kill JVM 在写中途，重启后 L2 能从 .bak 恢复
- 故意写一份非法 JSON 到 state.json，启动时自动回退 .bak
- L2Migration v1 -> v2 可工作（即便 v1 不存在，做个 dummy test）

---

### Phase 4 — Rotation 协议（4-5 天）

**目标**: 完整 14 步 rotation 流程实现，手动触发可用。

**文件清单**:

```
新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/rotation/
    RotationDecider.java
    RotationCoordinator.java
    RotationResult.java
    RotationInfo.java
    L2Merger.java
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/prompt/
    SupervisorPromptBuilder.java
    SuccessorPromptBuilder.java
    HandoffProducerPromptBuilder.java
  
  docs/supervisor/handoff-producer.md
  docs/supervisor/handoff-successor.md
  docs/supervisor/initial-bootstrap.md
  docs/supervisor/composite-runtime.md

修改:
  SupervisorBridge.java
    + startWithHandoff(successorPromptAppend) 方法
    + stopById(supervisorId) 方法
    + produceHandoff(currentL2) 方法
    + produceHandoffRetry(currentL2, errors) 方法
  
  supervisor-channel.js
    startSupervisorSession 接受 successorPromptAppend / initialBootstrapPromptAppend
    SystemPrompt 拼装走新 builder
    新增 produceHandoff handler: enqueue producer prompt, 等下个 turn, 解析 JSON 返回
  
  PairSession.java
    + swapSupervisorId(newId) 原子方法
  
  PairSessionManager.java
    + 启动 RotationDecider scheduler
  
  webview/src/components/SupervisorPair/PairStatusBar.tsx
    + "Force rotate" 按钮 → IPC -> Java RotationCoordinator.execute
    + 显示 RotationBoundary 消息
```

**关键代码**:

见 §10 完整 14 步 + §6 prompt 模板内容。

**验收标准**:
- 手动点 "Force rotate"，看到 14 步日志按序，generation+1，webview 显示 boundary 消息
- 模拟 producer fail (mock SDK 抛 timeout)，自动走 L2 兜底，successor 收到 degraded notice
- 并发触发 2 次 rotate（quickly click 2 次），第 2 次被冷却挡掉
- rotate 期间发 publish，新代第一个 tick 收到含 NEW_GENERATION_BANNER 的 composite
- generation 到 5 强制 archive knownConstraints，archive 文件写到 `~/.codemoss/pairs/<pairId>/archive/constraints-<ts>.json`

---

### Phase 5 — 自动 rotate + UNHEALTHY 升级（2-3 天）

**目标**: monitor 自动决策 rotate，UNHEALTHY 走兜底分支。

**文件清单**:

```
修改:
  SupervisorMonitor.java
    checkRotationTriggers 接入条件 (ratio / compactCount / health)
    UNHEALTHY 持续 2 tick → requestRotation
  
  RotationCoordinator.java
    health == UNHEALTHY 时跳过 produceHandoff, 直接 L2 兜底
  
  L2State.java
    metrics.degradedCount / unhealthyCount 计数
```

**验收标准**:
- 跑长任务（人造 30min），观察 ratio 上升到 0.85 时自动 rotate
- 故意让 supervisor SDK 报错（mock query fail），观察 DEGRADED → UNHEALTHY → rotate
- generation 走完 1 个完整周期 (0 → 5 → reset)，状态正确

---

### Phase 6 — 主 AI 端（独立大阶段，3-5 天）

**目标**: ClaudeSession 也接入同套机制，可 rotate 主 AI 会话。

**风险**: 用户能看到 chat history，UX 容错性低。建议先在内部 dogfood 2 周，再推用户。

**文件清单**:

```
新增:
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/monitor/
    MainAIMonitor.java
    StallDetector.java
  
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/rotation/
    MainAIRotationCoordinator.java (复用 RotationCoordinator 大部分逻辑)
    MainAIHandoffProducer.java
  
  docs/supervisor/main-ai-handoff-producer.md
  docs/supervisor/main-ai-handoff-successor.md

修改:
  ClaudeMessageHandler.java
    onTurnStart/onTurnEnd 钩 MainAIMonitor
    onCompactBoundary 钩
  
  ClaudeSession.java
    + swapInnerSession(newClaudeSid) 方法 (跟 swapSupervisorId 对称)
  
  webview ChatView
    渲染 rotation boundary 系统消息 ("🔄 上下文已 refresh, 完整历史已交接")
    保留 chat history (UI 不丢)
```

**主 AI handoff doc 多带的字段**:

```json
{
  ...L2State 基础字段...,
  "mainAI": {
    "recentUserMessages": [
      {"ts": "...", "text": "用户原文 1"},
      {"ts": "...", "text": "用户原文 2"}
    ],
    "lastAssistantSummary": "我刚才在做 X, 完成了 Y, 卡在 Z",
    "userPreferences": [
      "用户偏好用 langchaingo 而不是 openai-go"
    ],
    "currentTask": {
      "description": "...",
      "filesActive": ["a.go", "b.go"]
    }
  }
}
```

**验收标准**:
- 主 AI 跑长任务自动 rotate，chat history UI 不丢
- 5min stall 时弹提示，用户点中断走 rotate
- rotate 后用户继续提问，新代基于 handoff doc 答得连贯（人工评估）

---

## 十六、测试策略

### 16.1 单元测试

```
PairCoordinatorTest
  - 并发 enterNormalOp (5 线程) + enterRotation, 验排他
  - tick 内 requestRotation, decider 拿写锁顺利
  - writeLock 60s timeout 行为
  - 锁泄露检测 (tryLock 失败时不应递增计数)

EventCollectorTest
  - publish 速率 > MAX_BUFFER, 老事件被 drop
  - urgent 防抖: 1s 内连发 3 个 error, 只 reschedule 1 次
  - human_response 200ms 内立即 fire
  - drainAll 原子 (publish 与 drain 并发不丢)

L2StoreTest
  - 并发 update 同一 pairId, 序列化正确
  - write 中途模拟 IOException, .tmp 不污染原文件
  - load 时 state.json 损坏, 回退 .bak
  - schema v1 -> v2 migration

RotationCoordinatorTest (用 mock SupervisorBridge)
  - 全成功路径: 14 步全 OK
  - step 5 (produceHandoff) timeout → L2 fallback
  - step 6 validate fail → 重试 1 次成功
  - step 6 validate fail 2 次 → L2 fallback
  - step 9 (new start) fail → 不 swap, 返回 failed
  - step 10 fail → 继续 swap, log warn
  - 冷却: 5min 内拒绝
  - generation == 5 → archive + reset

SupervisorMonitorTest (FakeClock)
  - 30s 推 1 tick
  - urgent event → 1s 内 tick
  - tick timeout → DEGRADED + interrupt
  - 连续 2 timeout → UNHEALTHY + requestRotation
```

### 16.2 集成测试 (真 daemon, testcontainers)

```
scenario_normal_30s_tick
scenario_urgent_path
scenario_supervisor_hang_recovery
scenario_planned_rotation_ratio_trigger
scenario_planned_rotation_compact_trigger
scenario_emergency_rotation_unhealthy
scenario_handoff_producer_timeout
scenario_handoff_schema_invalid_retry_ok
scenario_handoff_schema_invalid_fallback
scenario_concurrent_rotate_requests
scenario_generation_5_archive_reset
scenario_l2_corruption_recovery
scenario_main_ai_rotation_ux (Phase 6)
```

### 16.3 长跑稳定性测试

- 跑 4 小时连续场景（mock 持续 turn_end），观察：
  - 内存 leak (heap diff < 50MB)
  - L2 文件大小线性受控（recentDecisions ring 工作）
  - Rotation 间隔健康（不应每 5min rotate 一次）

---

## 十七、迁移与回滚

### 17.1 灰度策略

Phase 1 引入 Registry flag `pair.monitor.enabled` (默认 false)，可逐步开启。Phase 2-5 都在 flag 内做。Phase 5 完成 + 2 周稳定后改默认 true。Phase 6 单独 flag `pair.monitor.mainAI.enabled`。

### 17.2 数据迁移

- `L2State.schemaVersion` 字段控制
- 启动 `L2Migration.migrate` 处理老版本
- Phase 3 之前没有 L2，Phase 3 启动时新建空 L2

### 17.3 回滚预案

| Phase | 回滚方法 |
|---|---|
| 0 | git revert |
| 1 | Registry flag `pair.monitor.enabled = false` |
| 2 | Registry flag `pair.contextAware.enabled = false`, fallback 老 UsagePushService 估算 |
| 3 | L2 文件保留, 但 SupervisorMonitor 不读它 |
| 4 | "Force rotate" 按钮隐藏, RotationDecider 不启动 |
| 5 | requestRotation 永远返回 false |
| 6 | MainAIMonitor 不挂钩 ClaudeMessageHandler |

---

## 十八、未决事项 / 待用户确认

1. **L2 文件路径**：`~/.codemoss/pairs/<pairId>/` 还是 `~/.cc-bridge/pairs/`？前者跟现有 `~/.codemoss/` 配置同级，后者跟 daemon session-store 同级。**推荐前者**（Java 侧资产，跟配置一起）
2. **archive 文件保留多久**：generation >= 5 archive 后的 `constraints-<ts>.json` 是否需要定期清理？建议保留 30 天，超期日志提示用户手动清
3. **Phase 6 触发条件**：主 AI rotation 是否复用 supervisor 同套阈值 (ratio 0.85 / compact 3)？还是更保守 (0.90 / 5)，因为用户对主 AI rotation 更敏感？**建议更保守**
4. **`successorPromptAppend` 长度上限**：handoff doc 序列化后塞 systemPromptAppend 是否会触发 SDK 自身 token 限制？需要预估 + 加 hard cap（建议 6000 tokens）
5. **Pair 状态面板默认折叠还是展开**：用户讨厌干扰还是欢迎可见？**建议默认折叠**，alerts 出现时自动展开
6. **是否暴露 L2 viewer 给最终用户**：还是只在 IDE debug 模式可见？**建议** debug 模式可见，普通用户从 Pair 面板看摘要即可
7. **handoff doc 4000 token 上限是否够**：复杂任务可能不够，是否分级（轻量 4000 / 复杂 8000）？需要 dogfood 数据

---

## 附录 A：关键文件最终清单

```
新增 Java:
  src/main/java/com/github/claudecodegui/session/pair/coordinator/
    PairCoordinator.java
    EventCollector.java
    UrgentPolicy.java
  src/main/java/com/github/claudecodegui/session/pair/monitor/
    SupervisorMonitor.java
    MainAIMonitor.java
    StallDetector.java
    HealthState.java
  src/main/java/com/github/claudecodegui/session/pair/rotation/
    RotationDecider.java
    RotationCoordinator.java
    MainAIRotationCoordinator.java
    RotationResult.java
    RotationInfo.java
    L2Merger.java
  src/main/java/com/github/claudecodegui/session/pair/l2/
    L2Store.java
    L2State.java
    L2Validator.java
    L2Migration.java
    L2Schema.java
  src/main/java/com/github/claudecodegui/session/pair/prompt/
    SupervisorPromptBuilder.java
    SuccessorPromptBuilder.java
    HandoffProducerPromptBuilder.java
    CompositeSummaryBuilder.java
    MainAIHandoffProducer.java
  src/main/java/com/github/claudecodegui/session/pair/status/
    PairStatusPusher.java
    PairStatusSnapshot.java
    RotationBoundaryEvent.java

新增 daemon JS:
  ai-bridge-server/ai-bridge/services/supervisor/
    update-state-tool.js
    pre-compact-hook.js
    handoff-producer.js
    composite-summary.js

新增 webview:
  webview/src/components/SupervisorPair/PairStatusBar.tsx
  webview/src/components/SupervisorPair/RotationBoundaryRow.tsx
  webview/src/components/SupervisorPair/StallAlertDialog.tsx
  webview/src/components/SupervisorPair/L2Viewer.tsx (debug 模式)

新增 prompt 模板:
  docs/supervisor/handoff-producer.md
  docs/supervisor/handoff-successor.md
  docs/supervisor/initial-bootstrap.md
  docs/supervisor/composite-runtime.md
  docs/supervisor/main-ai-handoff-producer.md
  docs/supervisor/main-ai-handoff-successor.md

主要修改:
  ai-bridge-server/ai-bridge/channels/supervisor-channel.js
  ai-bridge-server/ai-bridge/utils/async-stream.js
  ai-bridge-server/ai-bridge/services/supervisor/supervisor-tools.js
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/bridge/SupervisorBridge.java
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/EventBus.java
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairSession.java
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/session/pair/PairSessionManager.java
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/handler/ClaudeMessageHandler.java
  jetbrains-cc-gui/src/main/java/com/github/claudecodegui/service/UsagePushService.java
  jetbrains-cc-gui/webview/src/components/SupervisorPair/PairContext.tsx
  jetbrains-cc-gui/webview/src/App.tsx
```

---

## 附录 B：进度跟踪建议

按 Phase 拆 GitHub issue，每个 Phase 对应一个 milestone。Phase 0 当周内合入，后续每 Phase 控制在 5 工作日内单 PR（如超出拆子任务），所有 PR 必须含集成测试 + 文档更新。
