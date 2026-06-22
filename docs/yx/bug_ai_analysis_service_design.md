# 缺陷「AI 分析」详细编码方案 — 第 2 部分 / 服务（Java + daemon 复用）

> 项目: `jetbrains-cc-gui`（Java 服务层 + Node daemon）
> 日期: 2026-06-12
> 配套文档: [`bug_ai_analysis_ui_design.md`](./bug_ai_analysis_ui_design.md)（webview）
> 状态: 设计待评审 → 通过后编码

---

## 0. 背景与目标

webview 发起「AI 分析」后，服务端要：用一个**隔离的 scratch `claude.send`** 跑分析（模型自己调 `query_bug_details` 拉详情、文本优先），把**进度**与**结构化结果**回推 webview。

### 核心结论（已逐条 grounded 到代码）

| 结论 | 依据 |
|---|---|
| **Approach A：零 daemon 改动** | `query_bug_details` 已挂主 AI runtime（所有模式）：`runtime-lifecycle.js:172` + `main-ai-tools.js:97` |
| scratch headless send 可行 | `IBridge.sendCommand(method, params, DaemonOutputCallback)`：`IBridge.java:59-97` |
| 回调拿到的是**解包后行字符串** | `LocalBridge:660` / `RemoteBridge:452`（NDJSON 信封已拆） |
| scratch 会话参数可空 | `buildSendParams` 的 `sessionId` 可任意、`windowId` 传 null 即省略：`ClaudeRequestParamsBuilder:24-87` |
| 凭证齐 | `buildDaemonEnv` 注入 `YUNXIAO_*`（`buildYunxiaoEnv()`）；`ANTHROPIC_*` 走 daemon 进程本身：`ClaudeBridgeUtils:24-41` |
| 无弹窗 | `bypassPermissions` 全放行（=用户日常默认档）：`permission-mode.js:124` |
| 隔离到 runtime 层 | 独立 epoch → `findRuntimeForRequest` 不命中聊天 runtime：`runtime-lifecycle.js:277-292` |
| 本地/远程多态 | `getDaemonBridge()` 按 `RemoteModeContext.isRemote()` 选 Local/Remote：`ClaudeDaemonCoordinator:88-102` |

> **实现注记**
> - 代码片段里的部分 Java 访问器/工具方法名为**示意**（如 `context.getProjectBasePath()` / `escapeJs` / `optString` / `requestParamsBuilder` 字段 / `DaemonOutputCallback.NOOP`），编码时按实际类核对；已确认存在的是 `HandlerContext.getClaudeSDKBridge()`(:91)、`HandlerContext.callJavaScript(...)`(:145)、`ClaudeRequestParamsBuilder.buildSendParams(...)`(:24-87)、`ClaudeBridgeUtils.buildDaemonEnv(...)`(:24)。
> - ⚠️ **载荷关键点**：`claude.resetRuntime` **必须带 scratch 的 `runtimeSessionEpoch`**；传空会重置**全部** runtime（含用户聊天），见 `persistent-query-service.js:525`（`targetEpoch || '(all-runtimes)'`）。cwd 对分析无实质影响（`query_bug_details` 走 HTTP+tmp、`Read` 用 tmp 绝对路径），取任意有效项目目录即可。

---

## 1. 总览调用链

```
webview sendBridgeEvent('analyze_bugs', {...})
  → ClaudeChatWindow 消息分发 (messageDispatcher.dispatch) :674
  → SettingsHandler.handle() switch → case "analyze_bugs"            ★新
  → ProjectConfigHandler.handleAnalyzeBugs(content)                  ★新（镜像 handleLoadYunxiaoBugs）
      ├ 解析 {projectId,bugs,model,reasoningEffort}
      ├ 组 prompt（§7）
      └ context.getClaudeSDKBridge().analyzeBugsHeadless(...)        ★新 public 方法
            ├ 组 scratch params: sessionId="bug-analysis-<uuid>", 新 epoch,
            │    permissionMode="bypassPermissions", model/reasoningEffort 透传,
            │    streaming=true, env=buildDaemonEnv(cwd)
            ├ daemonCoordinator.getDaemonBridge()                    （已有，包内可见）
            │     → LocalBridge | RemoteBridge   ← 本地/远程自动
            ├ bridge.sendCommand("claude.send", params, BugAnalysisCollector)  ★新 collector
            │     stream: [CONTENT_DELTA] 累计正文; [MESSAGE]/[TOOL_RESULT] 驱动进度
            │     → 每步 progress: context.callJavaScript("window.onBugAnalysisProgress", …)
            └ onComplete(success):
                  抽 ```json 围栏 → 解析
                  → context.callJavaScript("window.onBugAnalysisResult", …)
                  → bridge.sendCommand("claude.resetRuntime", {epoch}, noop)   清理 scratch runtime
  → daemon 主 AI runtime（已挂 query_bug_details）逐 bug 调工具 + 输出 JSON
```

---

## 2. IPC 路由（Java 入口）

### 2.1 `handler/SettingsHandler.java`

- `SUPPORTED_TYPES` 数组（:27-95）追加 `"analyze_bugs"`、`"cancel_bug_analysis"`。
- `handle()` switch（:127-328）追加（紧挨 `load_yunxiao_bugs`）：

```java
case "analyze_bugs":
    projectConfigHandler.handleAnalyzeBugs(content);
    return true;
case "cancel_bug_analysis":
    projectConfigHandler.handleCancelBugAnalysis(content);
    return true;
```

### 2.2 `handler/ProjectConfigHandler.java`（镜像 `handleLoadYunxiaoBugs`）

```java
public void handleAnalyzeBugs(String content) {
    CompletableFuture.runAsync(() -> {
        try {
            JsonObject req = JsonParser.parseString(content).getAsJsonObject();
            String projectId = req.get("projectId").getAsString();
            JsonArray bugs = req.getAsJsonArray("bugs");
            String model = optString(req, "model");
            String reasoning = optString(req, "reasoningEffort");

            ClaudeSDKBridge sdk = context.getClaudeSDKBridge();
            if (sdk == null) { pushAnalysisError(projectId, "Claude bridge 不可用"); return; }

            String prompt = BugAnalysisPrompt.build(bugs);     // §7
            String cwd = context.getProjectBasePath();         // 任意有效项目目录即可
            sdk.analyzeBugsHeadless(projectId, prompt, bugs, model, reasoning, cwd,
                    new BugAnalysisHandlerCallbacks(context, projectId, bugs)); // §5/§6
        } catch (Exception e) {
            // best-effort：projectId 取不到时无法定向回推，只记日志
            log.warn("[BugAnalysis] handleAnalyzeBugs failed: " + e.getMessage());
        }
    });
}

public void handleCancelBugAnalysis(String content) {
    String projectId = JsonParser.parseString(content).getAsJsonObject().get("projectId").getAsString();
    context.getClaudeSDKBridge().cancelBugAnalysis(projectId);   // §8
}
```

> `context.getClaudeSDKBridge()`（`HandlerContext:91`）、`context.callJavaScript(fn, escapeJs(json))`（`HandlerContext:145`，底层 `ClaudeChatWindow.callJavaScript:591-637`，带 `typeof===function` 守卫）。

---

## 3. Scratch headless send（`provider/claude/ClaudeSDKBridge.java` 新增 public 方法）

仿现有 `executeQuerySync(prompt,timeout)` 暴露范式，但走 daemon（带工具）而非 fresh 进程。

```java
/** 隔离一次性分析：scratch 会话，绝不复用/污染聊天 runtime。 */
public void analyzeBugsHeadless(String projectId, String message, JsonArray bugs,
                                String model, String reasoningEffort, String cwd,
                                BugAnalysisHandlerCallbacks cb) {
    IBridge bridge = daemonCoordinator.getDaemonBridge();
    if (bridge == null) { cb.onTransportError("daemon 不可用"); return; }

    // ⚠️ sessionId 必须为空：非空 sessionId 会让 daemon 执行 `claude -p --resume <id>`，
    // CLI 拒绝非 UUID/不存在的 id（"--resume requires a valid session ID..."）。空 sessionId
    // = 新会话不 resume；隔离与清理全靠唯一 epoch（findRuntimeForRequest 对空 sessionId 只按
    // signature 查 anonymousRuntimesBySignature，signature 含 epoch → 唯一 epoch 必生成全新
    // runtime，绝不复用/驱逐聊天 runtime；resetRuntime 也按 epoch 匹配）。
    String scratchEpoch = "epoch-" + UUID.randomUUID();

    JsonObject params = requestParamsBuilder.buildSendParams(
            message,
            "",                    // sessionId：空 → 新会话，绝不 --resume
            scratchEpoch,          // runtimeSessionEpoch：唯一 → 隔离 runtime + reset key
            cwd,
            "bypassPermissions",   // permissionMode：无弹窗（=日常默认档）
            model,                 // 继承会话模型（含 [1m] 后缀照传）
            null,                  // attachments
            null,                  // openedFiles
            null,                  // agentPrompt
            Boolean.TRUE,          // streaming
            null,                  // disableThinking（让 reasoningEffort 生效）
            reasoningEffort,       // 继承思考档
            null,                  // systemPromptAppend
            null);                 // windowId=null → 省略（不挂任何 tab/窗口）
    params.add("env", ClaudeBridgeUtils.buildDaemonEnv(cwd));   // YUNXIAO_* + 项目路径

    // 记录在册：cancel/清理用
    AnalysisHandle h = new AnalysisHandle(projectId, scratchEpoch);
    activeAnalyses.put(projectId, h);                 // ConcurrentHashMap<String,AnalysisHandle>

    BugAnalysisCollector collector = new BugAnalysisCollector(h, bugs, cb);  // §4/§5
    bridge.sendCommand("claude.send", params, collector)
          .whenComplete((ok, err) -> {
              activeAnalyses.remove(projectId, h);
              // 清理 scratch runtime（释放，防累积）
              JsonObject reset = new JsonObject();
              reset.addProperty("runtimeSessionEpoch", scratchEpoch);
              bridge.sendCommand("claude.resetRuntime", reset, IBridge.DaemonOutputCallback.NOOP);
          });
}
```

> `buildSendParams` 入参全部已确认（`ClaudeRequestParamsBuilder:24-87`）：`message` 必填，其余可空；`sessionId/runtimeSessionEpoch/cwd/permissionMode/model` 总是写入，`windowId` 仅非空才写。

---

## 4. 流收集器（`provider/claude/BugAnalysisCollector.java` 新建，实现 `IBridge.DaemonOutputCallback`）

回调拿到的是**已解包行字符串**（`[CONTENT_DELTA] "..."` / `[MESSAGE] {...}` / `[TOOL_RESULT] {...}` / …）。本收集器只需三件事：累计正文、驱动进度、终态抽 JSON。

```java
public void onLine(String line) {
    if (line.startsWith("[CONTENT_DELTA] ")) {
        // payload 是 JSON-encoded string
        assistant.append(gson.fromJson(line.substring(16), String.class));
    } else if (line.startsWith("[MESSAGE] ")) {
        maybeMarkToolUse(line.substring(10));     // §5：从 tool_use 块取 bug_id → currentId/⟳
    } else if (line.startsWith("[TOOL_RESULT]")) {
        markToolDone();                            // §5：把 currentId 移入 doneIds/✓
    } else if (line.startsWith("[SEND_ERROR]")) {
        sendError = true; lastError = line;
    }
    // [STREAM_START/END]/[USAGE]/[THINKING*] 等：忽略
}
public void onStderr(String text) { /* 记日志 */ }
public void onError(String error) { cb.onTransportError(error); }
public void onComplete(boolean success) {
    if (handle.canceled) return;                   // 取消则不回推结果
    AnalysisResult r = JsonExtract.fromFenced(assistant.toString());  // §6
    if (r != null) cb.onResult(r);
    else           cb.onParseFallback(assistant.toString(), sendError ? lastError : "无法解析结构化结果");
}
```

---

## 5. 进度解析与推送（best-effort）

### 5.1 解析规则

- `[MESSAGE] <json>`：取 assistant 消息 `content[]` 里 `type==='tool_use'` 且 `name` **以 `query_bug_details` 结尾**（兼容 `mcp__main__query_bug_details`）的块，读 `input.bug_id` → 设为 `currentId`（该行标 ⟳），`toolCalls++`。
- `[TOOL_RESULT]`：把当前 `currentId` 移入 `doneIds`（标 ✓），`currentId=null`。

### 5.2 推送（多次）

每次进度变化：
```java
JsonObject p = new JsonObject();
p.addProperty("projectId", projectId);
p.addProperty("total", total);
p.add("doneIds", gson.toJsonTree(doneIds));
p.addProperty("currentId", currentId);
p.addProperty("toolCalls", toolCalls);
context.callJavaScript("window.onBugAnalysisProgress", context.escapeJs(gson.toJson(p)));
```

### 5.3 兜底

`[MESSAGE]` 的 tool_use 时序/字段若与预期不符（**运行期须实测一次确认**），逐行 `✓/⟳/◌` 退化为「已完成 N 次工具调用」（`toolCalls`），**不影响最终结果正确性**。webview 已按此兜底渲染（UI 文档 §5.2）。

---

## 6. 结果抽取与推送

### 6.1 围栏抽取（`provider/claude/JsonExtract.java`）

```
1) 取最后一个 ```json ... ``` 围栏块 → JSON.parse
2) 失败 → 退而取最后一个平衡的 { ... } 子串 → parse
3) 再失败 → 返回 null（走兜底原文）
```

### 6.2 推送（终态一次）

```java
// 成功
{ "ok": true, "projectId": "...", "model": "...", "reasoning": "...",
  "result": { "bugs":[...], "groups":[...] } }
// 失败兜底
{ "ok": false, "projectId": "...", "raw": "<assistant 原文>", "error": "..." }
```
`context.callJavaScript("window.onBugAnalysisResult", escapeJs(json))`。

---

## 7. Prompt 全文（`provider/claude/BugAnalysisPrompt.java`）

```
你是云效缺陷分析助手。下面是我选中的 {N} 个缺陷，请逐个分析并最终输出结构化结果。

缺陷清单（每行：编号 / 内部 identifier / 标题）：
1. BUG-{serialNumber}  (id「{identifier}」)  {subject}
2. ...

【对每个缺陷】
1. 调用 query_bug_details 工具，bug_id 传该缺陷的 identifier，拉取 基础信息 + 描述 + 所有评论。
2. 默认仅依据【标题 + 描述 + 评论文本】判断该缺陷是否「明确」：
   - 明确(clear)：有清晰的现象/复现路径/期望结果，足以直接动手定位。
   - 不明确(unclear)：缺关键信息。请在 reason 写为什么不明确，在 missing 列出缺什么
     （如：复现步骤 / 期望结果 / 涉及账号或权限 / 截图 / 接口或页面 等）。
3. 仅当文本不足以判断时，才用 Read 工具查看 query_bug_details 返回的截图本地路径；
   能用文本判断就不要读图（节省时间）。

【关联分组】把这些缺陷按关联性分组，每个缺陷**只归入一个最贴切的主组**：
- dimension 取值：page(同一页面) / api(同一接口) / feature(同一功能点或同一处根因)。
- 每组给 label（如「整改单列表/筛选页」）、members（该组的 serialNumber 列表）、
  rootCauseGuess（疑似共同根因，一句话）。

【约束】这是只读分析任务：禁止 Write/Edit、禁止执行 Bash 命令、禁止修改任何文件。

【输出】最后只输出一个 ```json 围栏代码块，不要任何额外解释文字，schema 如下：
（见 §下方 JSON schema）
```

### JSON schema（模型产出契约）

```jsonc
{
  "bugs": [
    { "serialNumber": "AAXE-1060", "identifier": "<id>", "subject": "...",
      "clarity": "clear" | "unclear",
      "reason": "明确依据 / 不明确原因",
      "missing": ["复现步骤", "期望结果"] }     // 仅 unclear；clear 可省略或空数组
  ],
  "groups": [
    { "dimension": "page" | "api" | "feature",
      "label": "整改单列表/筛选页",
      "members": ["AAXE-1060", "AAXE-1052"],     // serialNumber
      "rootCauseGuess": "整改单列表数据源/筛选条件拼装" }
  ]
}
```

---

## 8. 取消与清理

- **取消**（`cancelBugAnalysis(projectId)`）：从 `activeAnalyses` 取 `AnalysisHandle`，置 `canceled=true`；
  - 若该分析是 daemon **当前活动轮** → 发 `abort`（`daemon.js:515` 旁路队列，中止 activeRequestId），其 `onComplete` 因 `canceled` 不回推结果。
  - 若它**还排在某个聊天轮后面没开跑** → `abort` 会打到活动的聊天轮，**不可取**；故仅置 `canceled` 标记，待其出队开跑时 collector 首行即检查 `canceled` 短路返回（轻量空跑），不回推。
  > 串行队列下「分析=活动轮」是绝大多数情况；排队边界用标记兜住，避免误伤聊天轮。
- **清理**：每次 `sendCommand("claude.send")` 完成后 `whenComplete` 里发 `claude.resetRuntime{runtimeSessionEpoch:scratchEpoch}`（`daemon.js:365`）释放 scratch runtime，防止一次分析留一个 runtime 累积。

---

## 9. 隔离与并发（如实说明）

| 维度 | 结论 |
|---|---|
| **上下文/历史隔离** | ✅ 完全隔离：独立 `sessionId`+`epoch` → 独立 runtime（`runtime-lifecycle.js:277-292`），绝不写入聊天 transcript，跑完 `resetRuntime` |
| **执行并发** | ⚠️ daemon 命令队列**全局串行**（`daemon.js:557`，`activeRequestId` 单活）。分析与聊天/Pair **互相排队**——这不是分析新引入的，多 tab 聊天/Pair 现状即如此。UI 已提示「分析期间请勿在聊天发送」 |

---

## 10. 本地 / 远程

- `sendCommand` 由 `getDaemonBridge()` 返回的 `LocalBridge`/`RemoteBridge` 实现，**调用方无需分支** → 本地/远程自动双支持。
- **远程 server parity（部署 checklist）**：Approach A 不改 daemon，但远端 `ai-bridge-server` 的**主 AI runtime 必须已挂 `query_bug_details`**（云效集成时两份一起加；本地无法验远端那台是否已部署到位）。**远程模式首测**：发起一次分析，确认模型成功调用了 `query_bug_details`（进度有 ⟳/✓）。

---

## 11. 文件清单（服务）

| 文件 | 改动 |
|---|---|
| `handler/SettingsHandler.java` | `SUPPORTED_TYPES` + switch 加 `analyze_bugs` / `cancel_bug_analysis` |
| `handler/ProjectConfigHandler.java` | `handleAnalyzeBugs` / `handleCancelBugAnalysis`（镜像 yunxiao handlers） |
| `provider/claude/ClaudeSDKBridge.java` | public `analyzeBugsHeadless(...)` / `cancelBugAnalysis(...)` + `activeAnalyses` Map |
| `provider/claude/BugAnalysisCollector.java` ★新 | 实现 `DaemonOutputCallback`：累计正文 + 进度 + 终态抽 JSON |
| `provider/claude/BugAnalysisPrompt.java` ★新 | 组 prompt（§7） |
| `provider/claude/JsonExtract.java` ★新 | 围栏 JSON 容错抽取（§6.1） |
| `provider/common/IBridge.java` | （如无）补 `DaemonOutputCallback.NOOP` 常量 |
| **daemon** | **无改动**（`query_bug_details` 已在主 AI runtime；`claude.send`/`resetRuntime`/`abort` 均现成） |

---

## 12. 风险与残留（4 条 + 成本）

| 项 | 处置 |
|---|---|
| 逐 bug 进度可靠性（`[MESSAGE]` tool_use 时序/字段） | 高信心，**运行期实测一次**；兜底已就绪（toolCalls 计数） |
| 取消遇排队边界 | `canceled` 标记 + collector 首行短路；活动轮走 `abort`（§8） |
| scratch runtime 累积 | 完成即 `resetRuntime` 释放（§8） |
| 远程 server 未跟上版本 | 部署 checklist + 远程首测验 `query_bug_details` 可用（§10） |
| token 成本 | 10 bug × 详情 + opus/max 可能较贵/慢；已由 `MAX_BATCH=10` 封顶 + 文本优先 + 进度可取消缓解 |

---

## 13. 验证点 & 打包

1. **编译**：Java `compileJava`（沙箱无 JDK 时仅此步 + 运行期人工验）。
2. **冒烟（本地）**：批量选 3~5 个 bug → 分析 → 进度推进 → 出结构化结果；断网某接口验工具软降级仍出结果；故意让模型不吐 JSON 验兜底原文。
3. **冒烟（远程）**：切远程模式重跑，验 `query_bug_details` 在远端主 AI 可用（§10 checklist）。
4. **隔离验证**：分析进行中在聊天发消息——确认排队而非串台；分析完成后聊天会话上下文无任何分析痕迹。
5. **打包**：**本方案不改 daemon**，无需重打包 daemon；仅 Java + webview 构建。

---

## 14. 追加（2026-06-13）：实时分析过程直播

`BugAnalysisCollector` 在原有「累计正文 + 进度」之外，新增把模型实时输出转发给 webview 直播（只读），驱动「分析中」面板的过程区。依据 `stream-event-processor.js:52-74`：daemon 持久路径在 streaming 模式下逐 token 发 `[CONTENT_DELTA]` / `[THINKING_DELTA]`。

- `[CONTENT_DELTA]`：累计进 `assistant`（最终 JSON 抽取用）**并** `pushStream("content", delta)`。
- `[THINKING_DELTA]`（**新解析分支**，原先忽略）：仅 `pushStream("thinking", delta)`——**不**进最终结果。
- `[MESSAGE]` 命中 `query_bug_details` 的 tool_use：原进度推送外加 `pushStream("tool", toolLabel(bugId))`；`toolLabel` 用构造期建的 `identifier→serialNumber` 表给出 `BUG-<serial>`。
- 推送：`pushStream(kind,text)` → `cb.context.callJavaScript("window.onBugAnalysisStream", escapeJs({projectId,kind,text}))`（与进度同款通道，空文本跳过，`handle.canceled` 时 `onLine` 已提前 return 不再推）。
- 纯 Java（仅 `BugAnalysisCollector`，`BugAnalysisHandlerCallbacks` 不变）；**不改 daemon**；compileJava + webview build + 重启即可。
