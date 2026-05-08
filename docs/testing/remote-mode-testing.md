# 远程模式（Remote Bridge）测试指南

> 配套 `ai-bridge-server/IMPL-PLUGIN.md`。本文档说明在按 IMPL-PLUGIN.md 完成插件改造后，如何在**不污染日常 IDEA**的前提下逐层验证。

## TL;DR

不需要把插件装进你日常用的 IDEA。三层测试覆盖足够：

| 层级 | 命令                                           | 验证什么 | 何时跑 |
|---|----------------------------------------------|---|---|
| 1. 单元测试 | `./gradlew test`                             | 纯 Java 逻辑（SSE 解析、HTTP 客户端、JSON 路由） | 每次改完代码立刻跑 |
| 2. 沙箱 IDE | `./gradlew runIde`                           | 端到端交互（UI 灰态、权限弹窗、对话流） | 每个 P 阶段完成后跑 |
| 3. 真实 IDE 冒烟 | `./gradlew buildPlugin` + Install from Disk+ | 仅最终发布前确认 | 可选 |

`./gradlew runIde` 会下载独立的 IntelliJ Community 2024.3.1 到 `build/idea-sandbox/`，配置目录隔离，**不会影响你日常的 IDEA 配置和插件**。
' ./gradlew buildPlugin -x checkstyleMain -x checkstyleTest'
---

## 1. 单元测试层

### 1.1 覆盖范围

这一层测**纯 Java 逻辑**，不启动 IDE，不依赖 ai-bridge-server。

| 测试类 | 覆盖 |
|---|---|
| `SseSubscriberTest` | 分帧解析（id/event/data/comment）、`Last-Event-ID` 透传、断线指数退避、`MAX_RECONNECT_ATTEMPTS` 终止回调、`EVENT_TIMEOUT_MS` 超时检测 |
| `RemoteBridgeTest` | `start()` 完整 ready 流程、`sendCommand` 的 id 路由、`_ctrl` 派发到 `ControlMessageHandler`、`daemon shutdown` 触发 `onDeath`、`sendAbort` POST /in 格式 |
| `RemoteHistoryDataSourceTest` | `/history/projects` / `/history/sessions` / `/history/session` JSON 反序列化、URL 编码、HTTP 错误降级返回空集合/Optional.empty |
| `LocalHistoryDataSourceTest` | 用临时目录构造 jsonl 会话文件，验证包装现有 reader 的语义与改造前一致 |
| `RemotePermissionAdapterTest` | `permission_request` / `ask_user_question_request` / `plan_approval_request` 三类 _ctrl 的字段映射；响应 JSON shape 正确 |

### 1.2 mock SSE / HTTP

用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 起一个本地端口，按 IMPL-SERVER.md 协议返回固定响应。例：

```java
HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
server.createContext("/session", ex -> {
    String body = "{\"sessionId\":\"test-sid\"}";
    ex.sendResponseHeaders(200, body.length());
    ex.getResponseBody().write(body.getBytes());
    ex.close();
});
server.createContext("/session/test-sid/events", ex -> {
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    var os = ex.getResponseBody();
    os.write("id: 1\ndata: {\"type\":\"daemon\",\"event\":\"ready\",\"sdkPreloaded\":true}\n\n".getBytes());
    os.flush();
    // ... 后续按测试场景写更多事件
});
server.start();
String baseUrl = "http://localhost:" + server.getAddress().getPort();
```

测试 `RemoteBridge`：

```java
RemoteBridge bridge = new RemoteBridge(baseUrl);
assertTrue(bridge.start());
assertTrue(bridge.isAlive());
assertTrue(bridge.isSdkPreloaded());
```

### 1.3 运行

```bash
./gradlew test
./gradlew test --tests "*RemoteBridgeTest*"          # 单类
./gradlew test --tests "*SseSubscriberTest.testReconnect*"   # 单方法
```

---

## 2. 沙箱 IDE 层（`runIde`）

### 2.1 准备：本机起 ai-bridge-server

```bash
cd /Users/stelin/Develop/GolandProject/ai-project/ai-bridge-server
npm install   # 首次
node src/server.js   # 默认监听 :3284
```

或用 docker（按 `ai-bridge-server/docker/` 下的 compose）。

健康检查：

```bash
curl http://localhost:3284/health
# {"status":"ok",...}
```

### 2.2 启动沙箱 IDE

```bash
cd /Users/stelin/Develop/GolandProject/ai-project/jetbrains-cc-gui
./gradlew runIde
```

首次会下载 IDEA Community 2024.3.1 到 Gradle 缓存（~1 GB）。
启动后是一个**全新独立**的 IDE 实例，配置存在 `build/idea-sandbox/`，与日常 IDEA 完全隔离。

> 修改代码后 `Ctrl-C` 关闭沙箱，重跑 `runIde` 即可，无需 `buildPlugin`。

### 2.3 本地模式回归（先跑！这是零回归底线）

切换 Settings → Codemoss → Remote Server → Mode = **Local**，跑一遍：

- [ ] 普通对话流（发"Hi" → 收到完整 streaming 响应）
- [ ] Edit/Write 工具触发权限弹窗 → 同意/拒绝都正确执行
- [ ] AskUserQuestion 弹问答对话框 → 答案传回
- [ ] Plan 模式弹审批 → approved / edited 都能传回
- [ ] 历史会话面板能列出 `~/.claude/projects/` 下的会话
- [ ] 点历史会话能 resume
- [ ] Rewind 可用
- [ ] Skills / MCP / Provider 面板可编辑
- [ ] 附件按钮、图片粘贴可用

**预期**：与改造前完全一致。任何一项异常说明 P0（DaemonBridge → LocalBridge）改名引入了回归，立即排查。

### 2.4 远程模式核心路径

切到 Mode = **Remote**，URL = `http://localhost:3284`：

- [ ] 点"测试连接"按钮 → 显示"连接正常 ✓"（health + session create + delete 三步成功）
- [ ] 发一条"Hi" → 收到完整 streaming 响应
- [ ] 触发 Edit 工具 → 弹权限对话框 → 同意/拒绝都能正确传回 daemon
- [ ] AskUserQuestion → 弹问答对话框 → 答案传回
- [ ] Plan 模式 → 弹审批对话框 → approved/edited 传回
- [ ] 历史面板能列出**容器/server 主机**上 `~/.claude/projects/` 下的会话
- [ ] 点历史会话能 resume

### 2.5 远程模式灰态验证

- [ ] Settings 中 API Key / Base URL 字段 disabled，hover 提示"远程模式下不支持"
- [ ] MCP 服务器面板显示"远程模式下不支持，请在容器内配置 ~/.claude.json"
- [ ] Skills 面板灰态
- [ ] Provider 面板灰态
- [ ] Rewind 按钮 disabled，tooltip 提示
- [ ] 附件按钮隐藏 / 拖拽图片被拦截

### 2.6 远程模式异常路径

- [ ] **网络抖动**：`iptables` 或临时停 server 5–10 秒再恢复 → SSE 自动重连，事件不丢（`Last-Event-ID` 生效）
- [ ] **server 进程崩溃**：`kill` server，UI 应感知 `onDeath` 并提示
- [ ] **daemon 在 server 端崩溃**：触发 `_ctrl` action=`gateway_error` → UI 提示
- [ ] **超过 MAX_RECONNECT_ATTEMPTS**：长时间停 server（>20 次重连）→ 最终触发 `onSseClosed` → UI 标记不可用
- [ ] **切换模式**：local ↔ remote 切换后，旧 bridge 正确 `stop()`，新 bridge 正常工作（看是否有泄漏的线程 / 连接）

### 2.7 调试技巧

**看插件日志**：沙箱 IDE 内 Help → Show Log in Finder，或直接看 `build/idea-sandbox/system/log/idea.log`。

**看 SSE 流原始数据**：

```bash
curl -N http://localhost:3284/session/<sid>/events
```

**断点调试**：在 IDEA 里 Run → Edit Configurations → Gradle，建一个跑 `runIde` 的配置，然后 Debug 模式启动，断点会停在 `RemoteBridge` / `SseSubscriber` 中。

---

## 3. 真实 IDE 冒烟（可选）

仅在准备发预览版给团队前做：

```bash
./gradlew buildPlugin
# 产物：build/distributions/codemoss-<version>.zip
```

在你日常用的 IDEA：Settings → Plugins → 齿轮图标 → Install Plugin from Disk → 选 zip → 重启。

> 沙箱版本 = 2024.3.1。如果你日常 IDEA 是其他版本（如 2025.x），冒烟才有意义；否则沙箱已经是同版本，跳过即可。
> **不要**在日常 IDEA 上跑 2.4–2.6 的全量 checklist，沙箱已经覆盖。

---

## 4. 按 IMPL-PLUGIN.md 阶段对照

| 阶段 | 推荐测试 |
|---|---|
| P0 DaemonBridge → LocalBridge 改名 | `./gradlew test` + `runIde` 跑 §2.3 全量本地回归 |
| P1 SseSubscriber + RemoteBridge | 单元测试为主（`SseSubscriberTest` / `RemoteBridgeTest`） |
| P2 ControlMessageHandler + RemotePermissionAdapter | 单元测试 + `runIde` 手点 §2.4 中权限/Ask/Plan |
| P3 HistoryDataSource | `LocalHistoryDataSourceTest` / `RemoteHistoryDataSourceTest` + `runIde` 看历史面板 |
| P4 RemoteModeContext + 灰态 | `runIde` 跑 §2.5 |
| P5 ClaudeSDKBridge / CodexSDKBridge 工厂改造 | `runIde` 跑 §2.6 中 mode 切换 |
| P6 联调 | `runIde` 全量 §2.3 + §2.4 + §2.5 + §2.6 |

每个阶段完成后**至少跑一次 §2.3 本地回归**，确保零回归承诺持续成立。

---

## 5. 常见问题

**Q：runIde 启动慢 / 卡在下载**
A：首次需下载 IDEA Community（~1 GB）和 JBR。配 `~/.gradle/gradle.properties` 走代理或镜像，或预先放进 Gradle cache。

**Q：webview 在沙箱里白屏**
A：检查 `build.gradle:213` 的 `-Djcef.sandbox.enable=false` 是否生效；如果是 macOS，确认 Gradle 用的是 JBR 而非系统 JDK。

**Q：远程模式下点测试连接超时**
A：先 `curl http://localhost:3284/health` 确认 server 起着；再确认沙箱 IDE 与 server 在同一网络命名空间（docker 时注意端口映射）。

**Q：SSE 经常断**
A：先看 server 端 nginx/反代是否设了短的 `proxy_read_timeout`（建议 ≥ 75 s 或关 buffering）；再看防火墙是否切 idle 连接。`SseSubscriber` 的 `EVENT_TIMEOUT_MS = 30s` 假设 server 至少 30 s 内必有事件（含 `: heartbeat`）。

**Q：要不要 push 沙箱配置进 git**
A：不要。`build/idea-sandbox/` 是 Gradle 产物，已在 `.gitignore` 里。
