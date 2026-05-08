# 远程模式路径映射 + 远端项目路径绑定 编码方案

> 配套 `ai-bridge-server/DESIGN.md v3` + `IMPL-PLUGIN.md`。本文档面向插件 + server 两侧开发者，端到端说明：
>
> 1. 在远程模式下，如何在插件侧透明完成本地路径与远端路径的双向翻译；
> 2. 在 server 侧把每个 daemon 的 cwd / 环境变量绑定到 IDE 当前打开的项目（取代现状中"daemon 落在 server WORKDIR"的旧行为）。
>
> **核心原则**：
> - 所有路径翻译只在插件内部进行；server 接收的路径是它自己系统的、返回的也是它自己系统的。
> - 插件输入框 / 聊天 UI 看到的是本地系统的（@file token 例外，见 §1.7）。
> - daemon 进程的 `process.cwd()` 与 `process.env.IDEA_PROJECT_PATH` 永远等于"当前 IDE 项目映射后的远端路径"——session 生命周期内稳定不变。

---

## 0. 适用前提

| 项 | 取值 |
|---|---|
| 改造范围（插件） | jetbrains-cc-gui（`src/main/java/com/github/claudecodegui/`）+ webview 设置面板 |
| 改造范围（server） | ai-bridge-server（`src/session-manager.js` + `ai-bridge/daemon.js`），约 30 行 |
| 本地模式 | **完全不动** —— 由 `IdentityPathMapper` 兜底，全链路 no-op |
| 远程模式（无映射配置） | 插件侧走 `IdentityPathMapper`（远端=本地原值），server 侧仍按 §9 的"必填 projectPath"流程 spawn |
| 远程模式（有映射配置） | 启用 `DefaultPathMapper`，按本文档接入 |
| 是否考虑老插件向下兼容 | **否**——projectPath 是 server `POST /session` 的必填字段；老插件升上来直接 400 报错，引导用户更新 |

## 1. 已敲定的设计约束

下列约束是讨论收敛后的最终决定，编码时**不得擅自变更**：

1. **`@file` 终止符**：`@` 后吃到下一个空白才停止——使用正则 `(?<=^|\s)@(\S+)`。
2. **不带 `@` 的路径**：用户在对话正文中手敲的裸路径（例 `看下 D:\foo\bar`）**不翻译**，遵守"free-text 不翻"原则。
3. **`localOs` 不自动检测**：以用户在 UI 配置为准，不读 `System.getProperty("os.name")`。
4. **角标 / Miss Tracker 作用域**：**Project 级**——每个 IntelliJ 工程独立持有自己的映射配置和 miss 计数。
5. **配置变更后强制重启 session**：写入新映射配置后，立即 `claudeSDKBridge.shutdownDaemon()`，下条消息按新配置重建 daemon。
6. **未配置映射 = 不翻译**：`pathMapping.enabled=false` 或字段缺失时，远端/本地路径**完全相同**，所有翻译入口走 `IdentityPathMapper` no-op 通道。
7. **方案 a（输入翻译）**：drag-drop 文件时立即翻译为 remote 路径，输入框内显示并发送的都是 remote 路径；用户在已发送消息气泡中也看到 remote 路径。
8. **入站翻译边界**：`SseSubscriber` 收到 SSE event JSON 后、历史 JSONL 解析每行后、tool_use input 字段交付前，统一调 `toLocal`。
9. **`projectPath` 在 `POST /session` 中必填**：插件构造 RemoteBridge 时若 `project.getBasePath() == null` 立即 fail；server 收到空值或缺失字段直接 400 拒绝。**不保留兜底走 server WORKDIR 的旧行为**。
10. **没打开项目时插件不创建 RemoteBridge**：UI（聊天 ToolWindow / 设置面板）显示"请先打开一个项目"提示，按钮置灰。
11. **server 改造与插件同步落地**：spawn 时注入 `cwd` + `IDEA_PROJECT_PATH` + `PROJECT_PATH`；`daemon.js` 的 per-request env 加"等值跳过"逻辑，避免清空 spawn 时设的值。

## 2. 架构总览

```
┌───────────────────────────────  插件侧  ───────────────────────────────┐
│                                                                          │
│   ┌────────────────┐    drag-drop / 输入        ┌───────────────────┐   │
│   │  WebView UI    │ ─────────────────────────▶│  SessionHandler    │   │
│   │  - chat input  │                            │  - translate@      │   │
│   │  - settings    │                            │  - fileTags toRemote│  │
│   └─────▲──────────┘                            └───────┬───────────┘   │
│         │ inbound 渲染：toLocal                          │               │
│         │                                                ▼               │
│   ┌─────┴────────────┐  toLocal     ┌───────────────────────────────┐   │
│   │ ClaudeMessageHandler ◄──────────│ RemoteBridge.onSseEvent       │   │
│   │ - tool_use 翻译   │              │  - PathFieldVisitor.toLocal   │   │
│   └──────────────────┘              └───────▲───────────────────────┘   │
│                                              │                           │
│                                              │ SSE events                │
│                                              │                           │
│   ┌──────────────────────────┐               │                           │
│   │ ClaudeRequestParamsBuilder│ ─────────────┼─────────────────────────▶│
│   └──────────────────────────┘               │  POST /session/{id}/in   │
│                                              │  PathFieldVisitor.toRemote│
│                              ┌───────────────┴───────┐                   │
│                              │      RemoteBridge      │                   │
│                              │      (per-Project)     │                   │
│                              └───────▲───────────────┘                   │
│                                      │ POST /session                     │
│                              ┌───────┴────────┐                          │
│   ┌───────────────────┐      │ PathMapper     │     ┌──────────────┐    │
│   │ PathMapperHolder  │─────▶│ (Default/Identity)│  │ PathMissTracker│   │
│   │  @Service.PROJECT │      └────────┬───────┘     │ @Service.PROJECT│  │
│   └───────────────────┘               │              └──────────────┘    │
│         ▲                              │                                  │
│         │ rebuild on config change     │                                  │
│   ┌─────┴────────────────────┐         │                                  │
│   │ PathMappingConfig         │         │                                  │
│   │  (per-project)            │         │                                  │
│   └──────────────────────────┘          │                                  │
└──────────────────────────────────────────┼─────────────────────────────────┘
                                           │
                                  POST /session  body:{ "projectPath": "/home/devuser/projects/proj-x" }
                                           │
                              ┌────────────▼─────────────┐
                              │   ai-bridge-server         │
                              │                            │
                              │  session-manager.js#create │
                              │  ① 解析 body               │
                              │  ② fs.statSync 校验        │
                              │  ③ spawn(daemon, {         │
                              │       cwd: projectPath,    │
                              │       env: {               │
                              │         IDEA_PROJECT_PATH, │
                              │         PROJECT_PATH       │
                              │       }                    │
                              │     })                     │
                              │                            │
                              │  daemon.js#handleRequest   │
                              │  per-request env 等值跳过  │
                              └───────────────────────────┘
```

## 3. 配置模型

### 3.1 持久化结构（`~/.codemoss/config.json`）

按 §1 约束 4，映射配置是 **Project 级**——按本地项目路径作 key 存储：

```json
{
  "daemonMode": "remote",
  "remoteServerUrl": "http://localhost:3284",
  "projectConfigs": {
    "D:\\www\\ai\\proj-x": {
      "pathMapping": {
        "enabled": true,
        "localOs":  "WIN",
        "localRoot":  "D:\\www\\ai\\proj-x",
        "remoteOs": "LINUX",
        "remoteRoot": "/home/devuser/projects/proj-x"
      }
    },
    "D:\\www\\other\\proj-y": {
      "pathMapping": { "enabled": false }
    }
  }
}
```

> `projectConfigs.{key}` 沿用 `WorkingDirectoryManager` 的"按项目路径分桶"模式，确保多 Project 互不干扰。

### 3.2 `settings/PathMappingConfig.java` (新增)

```java
package com.github.claudecodegui.settings;

import com.github.claudecodegui.path.OsType;

public class PathMappingConfig {
    public boolean enabled;
    public OsType  localOs;
    public String  localRoot;
    public OsType  remoteOs;
    public String  remoteRoot;

    public boolean isUsable() {
        return enabled
                && localOs  != null && remoteOs != null
                && notBlank(localRoot) && notBlank(remoteRoot);
    }

    private static boolean notBlank(String s) { return s != null && !s.isEmpty(); }

    public static PathMappingConfig disabled() {
        PathMappingConfig c = new PathMappingConfig();
        c.enabled = false;
        return c;
    }
}
```

### 3.3 `path/OsType.java` (新增)

```java
package com.github.claudecodegui.path;

public enum OsType {
    WIN, LINUX, MAC;

    public boolean isWindows()       { return this == WIN; }
    public boolean isCaseSensitive() { return this == LINUX; }   // macOS 默认不敏感
    public char    separator()       { return this == WIN ? '\\' : '/'; }
}
```

> 不提供 `detectLocal()`，按 §1 约束 3。

### 3.4 `CodemossSettingsService` 增加 API

```java
public PathMappingConfig getPathMappingConfig(String localProjectPath) {
    try {
        JsonObject root = readConfig();
        JsonObject all  = optObject(root, "projectConfigs");
        JsonObject proj = all == null ? null : optObject(all, localProjectPath);
        if (proj == null || !proj.has("pathMapping") || proj.get("pathMapping").isJsonNull()) {
            return PathMappingConfig.disabled();
        }
        return gson.fromJson(proj.get("pathMapping"), PathMappingConfig.class);
    } catch (IOException e) {
        return PathMappingConfig.disabled();
    }
}

public void setPathMappingConfig(String localProjectPath, PathMappingConfig cfg) throws IOException {
    JsonObject root = readConfig();
    JsonObject all  = root.has("projectConfigs") && root.get("projectConfigs").isJsonObject()
            ? root.getAsJsonObject("projectConfigs") : new JsonObject();
    JsonObject proj = all.has(localProjectPath) && all.get(localProjectPath).isJsonObject()
            ? all.getAsJsonObject(localProjectPath) : new JsonObject();
    proj.add("pathMapping", gson.toJsonTree(cfg));
    all.add(localProjectPath, proj);
    root.add("projectConfigs", all);
    writeConfig(root);
}
```

## 4. 核心翻译模块（插件侧）

### 4.1 `path/PathMapper.java` (接口)

```java
package com.github.claudecodegui.path;

public interface PathMapper {
    /** 入站：远端路径 → 本地路径，未命中前缀返回原值 */
    String toLocal(String remotePath);

    /** 出站：本地路径 → 远端路径，未命中前缀返回原值 */
    String toRemote(String localPath);

    /** 是否启用真正的翻译（false 时所有翻译入口可短路） */
    boolean isActive();
}
```

### 4.2 `path/IdentityPathMapper.java` (新增)

```java
package com.github.claudecodegui.path;

public final class IdentityPathMapper implements PathMapper {
    public static final IdentityPathMapper INSTANCE = new IdentityPathMapper();
    private IdentityPathMapper() {}
    public String toLocal(String p)  { return p; }
    public String toRemote(String p) { return p; }
    public boolean isActive() { return false; }
}
```

> 按 §1 约束 6，未配置 / 未启用时全部走这条。本地模式同样走这条。

### 4.3 `path/DefaultPathMapper.java` (新增)

```java
package com.github.claudecodegui.path;

import com.github.claudecodegui.settings.PathMappingConfig;

import java.util.Locale;

public final class DefaultPathMapper implements PathMapper {

    private final PathMappingConfig cfg;
    private final PathMissTracker missTracker;
    private final String localRootCanonical;
    private final String remoteRootCanonical;

    public DefaultPathMapper(PathMappingConfig cfg, PathMissTracker missTracker) {
        this.cfg = cfg;
        this.missTracker = missTracker;
        this.localRootCanonical  = cfg.isUsable() ? canonical(cfg.localRoot,  cfg.localOs)  : "";
        this.remoteRootCanonical = cfg.isUsable() ? canonical(cfg.remoteRoot, cfg.remoteOs) : "";
    }

    @Override public boolean isActive() { return cfg.isUsable(); }

    @Override
    public String toRemote(String localPath) {
        if (!isActive() || localPath == null || localPath.isEmpty()) return localPath;
        String p = canonical(localPath, cfg.localOs);
        if (!hasRootPrefix(p, localRootCanonical)) {
            missTracker.recordOutboundMiss(localPath);
            return localPath;
        }
        String tail = p.substring(localRootCanonical.length());     // "/zz/ddd" 或 ""
        return joinWithStyle(cfg.remoteRoot, tail, cfg.remoteOs);
    }

    @Override
    public String toLocal(String remotePath) {
        if (!isActive() || remotePath == null || remotePath.isEmpty()) return remotePath;
        String p = canonical(remotePath, cfg.remoteOs);
        if (!hasRootPrefix(p, remoteRootCanonical)) {
            missTracker.recordInboundMiss(remotePath);
            return remotePath;
        }
        String tail = p.substring(remoteRootCanonical.length());
        return joinWithStyle(cfg.localRoot, tail, cfg.localOs);
    }

    /**
     * 归一化（仅用于前缀比较，不作最终输出）：
     *   1) 反斜杠统一成 '/'
     *   2) 去末尾 '/'（保留单根 "/" 或 "C:/"）
     *   3) Windows / macOS 大小写不敏感时全转小写
     */
    private static String canonical(String raw, OsType os) {
        String s = raw.replace('\\', '/');
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return os.isCaseSensitive() ? s : s.toLowerCase(Locale.ROOT);
    }

    /** 必须是路径段边界，避免 /foo/bar 误匹 /foo/barbaz */
    private static boolean hasRootPrefix(String pathCanonical, String rootCanonical) {
        if (!pathCanonical.startsWith(rootCanonical)) return false;
        return pathCanonical.length() == rootCanonical.length()
                || pathCanonical.charAt(rootCanonical.length()) == '/';
    }

    /** 用 target OS 风格的分隔符把 root + tail 拼起来 */
    private static String joinWithStyle(String rootRaw, String tail, OsType targetOs) {
        String t = tail.replace('\\', '/');
        String r = stripTrailing(rootRaw);
        return targetOs.isWindows() ? r + t.replace('/', '\\') : r + t;
    }

    private static String stripTrailing(String s) {
        if (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
```

### 4.4 `path/PathMapperHolder.java` (新增，Project 级)

```java
package com.github.claudecodegui.path;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.PathMappingConfig;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
public final class PathMapperHolder {

    private static final Logger LOG = Logger.getInstance(PathMapperHolder.class);

    private final Project project;
    private volatile PathMapper current = IdentityPathMapper.INSTANCE;

    public PathMapperHolder(Project project) {
        this.project = project;
        rebuild();
    }

    public static PathMapperHolder getInstance(Project project) {
        return project.getService(PathMapperHolder.class);
    }

    public PathMapper get() { return current; }

    public void rebuild() {
        try {
            boolean remote = RemoteModeContext.getInstance().isRemote();
            String  base   = project.getBasePath();
            if (!remote || base == null) {
                current = IdentityPathMapper.INSTANCE;
                return;
            }
            PathMappingConfig cfg = new CodemossSettingsService().getPathMappingConfig(base);
            if (!cfg.isUsable()) {
                current = IdentityPathMapper.INSTANCE;        // §1 约束 6
                return;
            }
            current = new DefaultPathMapper(cfg, PathMissTracker.getInstance(project));
            LOG.info("[PathMapperHolder] Rebuilt: project=" + base
                    + " " + cfg.localOs + ":" + cfg.localRoot
                    + " <-> " + cfg.remoteOs + ":" + cfg.remoteRoot);
        } catch (Exception e) {
            LOG.warn("[PathMapperHolder] rebuild failed: " + e.getMessage());
            current = IdentityPathMapper.INSTANCE;
        }
    }
}
```

### 4.5 `path/PathMissTracker.java` (新增，Project 级)

```java
package com.github.claudecodegui.path;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class PathMissTracker {

    private static final int MAX_SAMPLES = 200;

    private final Set<String> outbound = ConcurrentHashMap.newKeySet();
    private final Set<String> inbound  = ConcurrentHashMap.newKeySet();

    public static PathMissTracker getInstance(Project project) {
        return project.getService(PathMissTracker.class);
    }

    public void recordOutboundMiss(String p) {
        if (p != null && outbound.size() < MAX_SAMPLES) outbound.add(p);
    }

    public void recordInboundMiss(String p) {
        if (p != null && inbound.size() < MAX_SAMPLES) inbound.add(p);
    }

    public int outboundCount() { return outbound.size(); }
    public int inboundCount()  { return inbound.size(); }
    public List<String> outboundSamples(int max) { return outbound.stream().limit(max).toList(); }
    public void clear() { outbound.clear(); inbound.clear(); }
}
```

> 角标只展示 `outboundCount()`；入站不命中常见于 `~/.claude/...`、`/tmp/...`，仅日志诊断。

## 5. 字段清单（Field Manifest）

### 5.1 `path/PathFields.java` (新增)

```java
package com.github.claudecodegui.path;

import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

public final class PathFields {

    /** 出站：method 前缀 → 字段表达式（简易 JSONPath） */
    public static final Map<String, List<String>> OUTBOUND = Map.ofEntries(
        entry("__session_create__", List.of("$.projectPath")),

        entry("claude.send", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH",
            "$.params.openedFiles.*",
            "$.params.attachments[*].path"
        )),
        entry("claude.sendWithAttachments", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH",
            "$.params.attachments[*].path"
        )),
        entry("claude.preconnect", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH"
        )),
        entry("claude.resetRuntime", List.of("$.params.cwd")),
        entry("claude.rewindFiles",  List.of("$.params.cwd")),
        entry("claude.getMcpServerStatus", List.of("$.params.cwd")),
        entry("codex.send", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH"
        ))
    );

    /** 入站：event 标签 → 字段表达式 */
    public static final Map<String, List<String>> INBOUND = Map.ofEntries(
        entry("daemon.ready", List.of("$.cwd")),

        entry("_ctrl.permission_request", List.of(
            "$.cwd",
            "$.inputs.file_path",
            "$.inputs.path",
            "$.inputs.filePath",
            "$.inputs.dir",
            "$.inputs.paths[*]",
            "$.inputs.files[*]"
        )),
        entry("_ctrl.ask_user_question_request", List.of("$.cwd")),
        entry("_ctrl.plan_approval_request",     List.of("$.cwd")),

        entry("__tool_use_input__", List.of(
            "$.file_path",
            "$.path",
            "$.filePath",
            "$.dir",
            "$.paths[*]",
            "$.files[*]",
            "$.cwd"
            // Bash.command 不翻 —— free-text 原则
        )),

        entry("__history_line__", List.of(
            "$.cwd",
            "$.message.content[*].input.file_path",
            "$.message.content[*].input.path",
            "$.message.content[*].input.filePath",
            "$.message.content[*].input.paths[*]",
            "$.message.content[*].input.files[*]",
            "$.message.content[*].input.cwd"
        ))
    );

    private PathFields() {}
}
```

### 5.2 `path/PathFieldVisitor.java` (新增)

实现一个**最小化** JSONPath 子集（仅支持 `$.a.b.c`、`$.a[*].b`、`$.a.*`），无第三方依赖：

```java
package com.github.claudecodegui.path;

import com.google.gson.*;

import java.util.*;
import java.util.function.UnaryOperator;

public final class PathFieldVisitor {

    public static void applyOutbound(String methodKey, JsonObject root, UnaryOperator<String> toRemote) {
        apply(methodKey, root, toRemote, PathFields.OUTBOUND);
    }

    public static void applyInbound(String eventKey, JsonObject root, UnaryOperator<String> toLocal) {
        apply(eventKey, root, toLocal, PathFields.INBOUND);
    }

    private static void apply(String key, JsonObject root, UnaryOperator<String> fn,
                              Map<String, List<String>> manifest) {
        List<String> exprs = manifest.get(key);
        if (exprs == null || root == null) return;
        for (String expr : exprs) walk(root, splitTokens(expr), 0, fn);
    }

    /** "$.a.b[*].c" → ["a", "b", "[*]", "c"]；"$.a.*" → ["a", "*"] */
    private static List<String> splitTokens(String expr) { /* 实现略，单测覆盖 */ }

    private static void walk(JsonElement node, List<String> tokens, int idx, UnaryOperator<String> fn) {
        // 递归到叶子；叶子若是 String 则替换；非 String 跳过；遇 "*" 对 map 全 key 重命名+递归
    }

    private PathFieldVisitor() {}
}
```

> 单元测试覆盖：嵌套对象、`[*]` 数组、`.*` map 重命名、字段缺失、null 容错。

## 6. 出站接入点（Plugin → Server）

### 6.1 `RemoteBridge.java` 改造

#### 6.1.1 构造 + no-project 守卫（line 65 附近）

```java
private final PathMapper mapper;
private final String localProjectPath;

public RemoteBridge(String baseUrl, Project project) {
    if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.baseUrl = trimmed;
    this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.mapper = project != null
            ? PathMapperHolder.getInstance(project).get()
            : IdentityPathMapper.INSTANCE;
    this.localProjectPath = project != null ? project.getBasePath() : null;
}
```

#### 6.1.2 `start()` 强制 projectPath + 错误反馈（替换 line 86-126）

```java
@Override
public boolean start() {
    if (alive.get()) return true;

    // §1 约束 9 + 10：必须有项目
    if (localProjectPath == null || localProjectPath.isEmpty()) {
        LOG.warn("[RemoteBridge] cannot start: no project open");
        notifyStartFailed("PROJECT_NOT_OPEN", "请先打开一个项目");
        return false;
    }

    readyLatch = new CountDownLatch(1);
    try {
        // POST /session 必带 projectPath
        JsonObject createBody = new JsonObject();
        createBody.addProperty("projectPath", mapper.toRemote(localProjectPath));

        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(createBody)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (resp.statusCode() == 400) {
            JsonObject err = safeParse(resp.body());
            String code = optString(err, "code");
            String msg  = optString(err, "error");
            LOG.warn("[RemoteBridge] POST /session 400 code=" + code + " msg=" + msg);
            notifyStartFailed(code != null ? code : "BAD_REQUEST", msg);
            return false;
        }
        if (resp.statusCode() != 200) {
            LOG.warn("[RemoteBridge] POST /session failed: " + resp.statusCode() + " " + resp.body());
            notifyStartFailed("SESSION_CREATE_FAILED", "HTTP " + resp.statusCode());
            return false;
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        sessionId = body.get("sessionId").getAsString();
        LOG.info("[RemoteBridge] Session created: " + sessionId
                + " projectPath=" + createBody.get("projectPath").getAsString());

        startSseSubscriber();
        alive.set(true);

        if (!readyLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LOG.warn("[RemoteBridge] Daemon did not signal ready within " + START_TIMEOUT_MS + "ms");
            stop();
            return false;
        }
        return true;
    } catch (Exception e) {
        LOG.warn("[RemoteBridge] start() failed: " + e.getMessage(), e);
        return false;
    }
}

private void notifyStartFailed(String code, String message) {
    DaemonLifecycleListener l = lifecycleListener;
    if (l instanceof DaemonLifecycleListenerWithError) {
        ((DaemonLifecycleListenerWithError) l).onDaemonStartFailed(code, message);
    } else if (l != null) {
        try { l.onDaemonDied(); } catch (Exception ignore) {}
    }
}
```

> `DaemonLifecycleListenerWithError` 是新增的可选接口（继承 `DaemonLifecycleListener`），允许 UI 拿到结构化 code 给出针对性错误提示。已实现的旧 listener 仍兼容。

#### 6.1.3 `sendCommand()` 出站翻译（line 194 起）

```java
@Override
public CompletableFuture<Boolean> sendCommand(String method, JsonObject params, DaemonOutputCallback callback) {
    if (mapper.isActive() && params != null) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("params", params);
        PathFieldVisitor.applyOutbound(method, wrapper, mapper::toRemote);
    }
    // ...原有逻辑不变...
}
```

#### 6.1.4 `onSseEvent()` 入站翻译（line 254 起）

```java
private void onSseEvent(String data) {
    if (data == null || data.isEmpty()) return;
    JsonObject msg;
    try {
        JsonElement parsed = JsonParser.parseString(data);
        if (!parsed.isJsonObject()) return;
        msg = parsed.getAsJsonObject();
    } catch (Exception e) { LOG.debug("Invalid SSE: " + data); return; }

    if (mapper.isActive()) {
        String tag = inboundTag(msg);
        if (tag != null) PathFieldVisitor.applyInbound(tag, msg, mapper::toLocal);
    }
    // ...原派发逻辑不变...
}

private static String inboundTag(JsonObject msg) {
    String type = optString(msg, "type");
    String action = optString(msg, "action");
    if ("daemon".equals(type)) return "daemon." + optString(msg, "event");
    if ("_ctrl".equals(type))  return "_ctrl." + action;
    return null;
}
```

### 6.2 `ClaudeDaemonCoordinator.java:74` 改造

```java
newBridge = new RemoteBridge(url, this.project);
```

> 若 `ClaudeDaemonCoordinator` 当前未持有 Project 引用，从其构造或 `getDaemonBridge()` 入参注入。

### 6.3 `@file` token 翻译（方案 a）

#### 位置

`handler/SessionHandler.java` line 173 处（`final String finalPrompt = prompt;` 之前）。

#### 实现

```java
private String translateAtMentionsAndFileTags(String prompt, java.util.List<String> fileTagPaths) {
    PathMapper m = PathMapperHolder.getInstance(context.getProject()).get();
    if (!m.isActive()) return prompt;

    // §1 约束 1：吃到下个空白才停
    Pattern p = Pattern.compile("(?<=^|\\s)@(\\S+)");
    Matcher matcher = p.matcher(prompt);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
        String local  = matcher.group(1);
        String remote = m.toRemote(local);
        matcher.appendReplacement(sb, Matcher.quoteReplacement("@" + remote));
    }
    matcher.appendTail(sb);

    if (fileTagPaths != null) {
        for (int i = 0; i < fileTagPaths.size(); i++) {
            fileTagPaths.set(i, m.toRemote(fileTagPaths.get(i)));
        }
    }
    return sb.toString();
}
```

调用：

```java
prompt = translateAtMentionsAndFileTags(prompt, fileTagPaths);
final String finalPrompt = prompt;
```

> 第二个调用点 `SessionHandler.java:322`（resume 路径）同步处理。

### 6.4 `customWorkingDirectory` 不在此处翻译

`SessionHandler.determineWorkingDirectory()` 返回的仍是 local 路径，存入 `state.setCwd(local)`。最终翻译由 `RemoteBridge.sendCommand()` 出站 manifest 命中 `$.params.cwd` 完成。**Local 模式无变化**。

## 7. 入站接入点（Server → Plugin）

### 7.1 SSE 事件

由 §6.1.4 完成。

### 7.2 `tool_use` 输入字段

`session/ClaudeMessageHandler.java` line 281-295：

```java
if ("tool_use".equals(type)) {
    PathMapper m = PathMapperHolder.getInstance(project).get();
    if (m.isActive() && block.has("input") && block.get("input").isJsonObject()) {
        PathFieldVisitor.applyInbound(
            "__tool_use_input__",
            block.getAsJsonObject("input"),
            m::toLocal
        );
    }
    // ...原有处理...
}
```

> `tool_result.content` 是自由文本，**不翻**（free-text 原则）。

### 7.3 历史读取链路

#### 7.3.1 `path/HistoryProjectPathEncoder.java` (新增)

```java
package com.github.claudecodegui.path;

import com.intellij.openapi.project.Project;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class HistoryProjectPathEncoder {

    /** local 项目路径 → 远端形态 → base64-url-no-pad（对应 ~/.claude/projects/{encoded}/ 目录名） */
    public static String encode(Project project, String localProjectPath) {
        PathMapper m = project != null
                ? PathMapperHolder.getInstance(project).get()
                : IdentityPathMapper.INSTANCE;
        String wirePath = m.toRemote(localProjectPath);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(wirePath.getBytes(StandardCharsets.UTF_8));
    }

    private HistoryProjectPathEncoder() {}
}
```

#### 7.3.2 替换调用点

- `provider/claude/ClaudeHistoryIndexService.java`：编码改用 `HistoryProjectPathEncoder.encode(project, projectPath)`。
- `provider/codex/CodexHistoryIndexService.java`：同步改造。
- 现有缓存 key 仍用 `projectPath`（local 形态）；配置改动后通过 `PathMapperHolder.rebuild()` + `claudeSDKBridge.shutdownDaemon()` 联合触发缓存自然失效。

#### 7.3.3 JSONL 行级翻译

每条 JSONL 行解析后立即 `PathFieldVisitor.applyInbound("__history_line__", line, mapper::toLocal)`。接入位置：`provider/claude/ClaudeHistoryReader`（或 message-merger）的"逐行 parse JSON"位置。

### 7.4 Diff / Rewind / 文件打开

`handler/DiffHandler.java`、`handler/RewindHandler.java`：远端拿到的 path 字段在交付给"打开文件 / 渲染 diff"前 `mapper.toLocal()`，然后用 `LocalFileSystem.findFileByPath(localPath)` 打开本地实际文件。

## 8. UI 改造（webview）

### 8.1 `RemoteServerSection/index.tsx` 增加路径映射子面板

| 控件 | 类型 | 说明 |
|---|---|---|
| 启用映射 | switch | 关闭时整个面板灰化 |
| 本地系统 | select [Win, Linux, Mac] | 必填，无默认（§1 约束 3） |
| 本地根目录 | text + "选择..." 按钮 | 选择按钮唤起 IDEA 文件夹选择器 |
| 远端系统 | select [Win, Linux, Mac] | 必填 |
| 远端根目录 | text | 纯文本，无法本机校验 |
| 保存 | button | 触发 `set_path_mapping` |
| 角标 | `⚠ N 条路径未命中` | 点开 popover 列出 outbound miss 样本 |

#### 双向消息

```ts
sendToJava('get_path_mapping');
sendToJava('set_path_mapping', JSON.stringify(cfg));
sendToJava('get_path_misses');
sendToJava('clear_path_misses');

window.updatePathMapping = (json) => { ... };
window.updatePathMisses  = (json) => { ... };
```

### 8.2 错误反馈 UI（§1 约束 9 + 10）

聊天 ToolWindow 头部加一条彩色 banner：

| 触发 | 文案 | 操作按钮 |
|---|---|---|
| `PROJECT_NOT_OPEN` | "请先打开一个项目" | "Open Project" → 触发 IDEA 打开项目对话框 |
| `PROJECT_PATH_NOT_ACCESSIBLE` | "远端不可访问 `<path>`" | "去配置映射" → 跳设置面板 |
| `PROJECT_PATH_REQUIRED` | "插件未发送项目路径，可能是版本不匹配" | "查看日志" |
| 其他 `BAD_REQUEST` | server 返回的 `error` 字符串 | – |

### 8.3 `handler/ProjectConfigHandler.java` 新增 4 个方法

```java
public void handleGetPathMapping();
public void handleSetPathMapping(String content);    // 写入 → PathMapperHolder.rebuild() → claudeSDKBridge.shutdownDaemon()
public void handleGetPathMisses();
public void handleClearPathMisses();
```

> §1 约束 5：`handleSetPathMapping` 内强制 `shutdownDaemon()`，下条消息按新配置重建 daemon。

### 8.4 `handler/SettingsHandler.java` 注册新消息

`SUPPORTED_MESSAGES` 列表 + dispatch switch 追加：`get_path_mapping / set_path_mapping / get_path_misses / clear_path_misses`。

## 9. Server 侧改造（ai-bridge-server）

> 仓库：`ai-bridge-server`。改动 2 个文件，约 30 行。**与插件 PR 同步合入**——任意一侧单独上线都会破坏端到端流程（插件强制带 projectPath，server 强制要求 projectPath）。

### 9.1 `src/session-manager.js#create` 重写（替换 line 20-72）

```js
import fs from 'node:fs';

async function create(req, res) {
  // ① 解析 body（POST 当前完全没解析）
  let body = '';
  try {
    for await (const chunk of req) {
      body += chunk;
      if (body.length > 64 * 1024) {
        return sendJSON(res, 413, { code: 'BODY_TOO_LARGE', error: 'body too large' });
      }
    }
  } catch (e) {
    return sendJSON(res, 400, { code: 'BAD_BODY', error: `read body failed: ${e.message}` });
  }

  let projectPath = null;
  if (body.trim()) {
    try {
      const json = JSON.parse(body);
      if (typeof json.projectPath === 'string' && json.projectPath) {
        projectPath = json.projectPath;
      }
    } catch (e) {
      return sendJSON(res, 400, { code: 'BAD_JSON', error: `invalid JSON body: ${e.message}` });
    }
  }

  // ② projectPath 必填（§1 约束 9）
  if (!projectPath) {
    return sendJSON(res, 400, {
      code: 'PROJECT_PATH_REQUIRED',
      error: 'projectPath 是 POST /session 的必填字段',
    });
  }

  // ③ 校验路径在 server 端可访问
  try {
    const st = fs.statSync(projectPath);
    if (!st.isDirectory()) throw new Error('not a directory');
  } catch (e) {
    return sendJSON(res, 400, {
      code: 'PROJECT_PATH_NOT_ACCESSIBLE',
      error: `projectPath 在 server 端不可访问: ${projectPath}`,
      projectPath,
    });
  }

  // ④ spawn 时绑 cwd 和 env
  const sid = crypto.randomUUID();
  let child;
  try {
    child = spawn(process.execPath, [DAEMON_PATH], {
      cwd: projectPath,
      env: {
        ...process.env,
        AI_BRIDGE_REMOTE_MODE: '1',
        IDEA_PROJECT_PATH: projectPath,
        PROJECT_PATH:      projectPath,
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (e) {
    logger.error('Failed to spawn daemon', e);
    return sendJSON(res, 500, { code: 'SPAWN_FAILED', error: `spawn failed: ${e.message}` });
  }

  // 后续逻辑（hub 创建、stdout/stderr 接管、idle timer 等）保持不变
  const hub = createSseHub({ bufferSize: 1000, tag: sid.slice(0, 8) });
  const session = { sid, child, hub, idleTimer: null, createdAt: Date.now(), projectPath };
  sessions.set(sid, session);
  // ...原有代码...
  logger.info(`session created sid=${sid} pid=${child.pid} projectPath=${projectPath}`);
  sendJSON(res, 200, { sessionId: sid, pid: child.pid, projectPath });
}
```

> `session.projectPath` 字段顺手存进 session 对象，便于 `listSessions()` 调试时返回。

### 9.2 `ai-bridge/daemon.js` per-request env 等值跳过（line 307-315）

```js
if (params.env && typeof params.env === 'object') {
  for (const [key, value] of Object.entries(params.env)) {
    if (value === undefined || value === null) continue;
    const v = String(value);
    if (process.env[key] === v) continue;       // ← 新增：等值跳过
    savedEnv[key] = process.env[key];
    process.env[key] = v;
  }
}
```

**为什么必须改这里**：spawn 时已设 `IDEA_PROJECT_PATH`；每次 `claude.send` 又拿同样的值 set，finally 里 `delete process.env[key]` —— 等于第一次请求结束就把 spawn 时设的环境变量清空了。等值跳过后两边和谐共存。

### 9.3 `listSessions()` 顺手暴露 projectPath（可选，便于排查）

```js
function listSessions(res) {
  const out = [];
  for (const [sid, s] of sessions) {
    out.push({
      sessionId: sid,
      pid: s.child.pid,
      createdAt: s.createdAt,
      projectPath: s.projectPath,            // ← 新增
      subscribers: s.hub.subscriberCount(),
      alive: s.child.exitCode === null && !s.child.killed,
    });
  }
  sendJSON(res, 200, out);
}
```

### 9.4 server 侧不做的事

- **不实现路径映射逻辑**：server 完全按"自身系统路径"工作；翻译是插件的事。
- **不校验 IDEA_PROJECT_PATH 与 cwd 一致**：spawn 已统一注入，没机会不一致。
- **不做 docker volume 检查**：挂载是用户运维的事，server 只能 stat 路径，stat 通过即认为合法。
- **不存历史 mapping 记录**：跨 session 隔离，每个 session 自带 projectPath。

## 10. 文件改动清单

### 10.1 新增（11 个，全部插件侧）

```
src/main/java/com/github/claudecodegui/
├── path/
│   ├── OsType.java                          约 25 行
│   ├── PathMapper.java                      约 10 行
│   ├── IdentityPathMapper.java              约 12 行
│   ├── DefaultPathMapper.java               约 80 行
│   ├── PathMapperHolder.java                约 50 行（@Service.PROJECT）
│   ├── PathMissTracker.java                 约 45 行（@Service.PROJECT）
│   ├── PathFields.java                      约 60 行
│   ├── PathFieldVisitor.java                约 100 行
│   └── HistoryProjectPathEncoder.java       约 25 行
└── settings/
    └── PathMappingConfig.java               约 25 行

src/test/java/com/github/claudecodegui/path/
├── DefaultPathMapperTest.java               约 200 行
├── PathFieldVisitorTest.java                约 120 行
└── PathMappingConfigSerdeTest.java          约 80 行
```

### 10.2 修改（插件侧，10 个）

| 文件 | 改动要点 | 估行数 |
|---|---|---|
| `settings/CodemossSettingsService.java` | 加 `getPathMappingConfig` / `setPathMappingConfig` | +35 |
| `handler/ProjectConfigHandler.java` | 加 4 个 handler；`handleSetPathMapping` 后 rebuild + `shutdownDaemon` | +90 |
| `handler/SettingsHandler.java` | 注册 4 个 message + dispatch | +12 |
| `handler/SessionHandler.java` | line 173 / 322 加 `translateAtMentionsAndFileTags()` | +30 |
| `provider/common/RemoteBridge.java` | 构造接 Project；start() 加守卫 + 携 projectPath + 错误反馈；sendCommand 出站；onSseEvent 入站 | +75 |
| `provider/common/IBridge.java` | 加 `DaemonLifecycleListenerWithError`（可选扩展） | +12 |
| `provider/claude/ClaudeDaemonCoordinator.java` | line 74 `new RemoteBridge(url, project)` | +1 |
| `provider/claude/ClaudeHistoryIndexService.java` | 编码改用 `HistoryProjectPathEncoder` | +5 |
| `provider/codex/CodexHistoryIndexService.java` | 同上 | +5 |
| `session/ClaudeMessageHandler.java` | line 281 处 tool_use input 入站翻译 | +12 |
| `provider/claude/ClaudeHistoryReader.java`（或 MessageMerger） | 历史 JSONL 行级翻译 | +10 |
| `webview/.../RemoteServerSection/index.tsx` | 路径映射子面板 + 角标 + 错误 banner | +220 |
| `webview/.../RemoteServerSection/style.module.less` | 配套样式 | +60 |

### 10.3 修改（server 侧，2 个）

| 文件 | 改动要点 | 估行数 |
|---|---|---|
| `ai-bridge-server/src/session-manager.js` | `create()` 改写：body 解析 + 校验 + spawn 注入；`listSessions()` 加 projectPath | +60 |
| `ai-bridge-server/ai-bridge/daemon.js` | per-request env 等值跳过 | +3 |

### 10.4 测试（server 侧）

```
ai-bridge-server/test/
└── session-create.test.js     // 新增，覆盖 PROJECT_PATH_REQUIRED / NOT_ACCESSIBLE / 成功 spawn
```

**预计 Java 总改动 ~700 行（含测试），webview ~280 行，server ~70 行。**

## 11. 实施顺序（5 PR）

### PR 1：插件 - 核心模块 + 配置（不接通业务）

**目标**：构建独立 `path/*` 模块，UT 100% 覆盖；`CodemossSettingsService` 增加读写；`ProjectConfigHandler` 增加 4 个 handler。**不**修改 RemoteBridge / SessionHandler / 历史链路 / server。

**验收**：UT 全绿；手动调用 `set_path_mapping` 能持久化、`get_path_mapping` 能回读；业务流程零变化。

### PR 2：Server - projectPath 必填 + spawn 注入

**目标**：

1. `session-manager.js#create` 解析 body + 校验 + spawn 时绑 cwd / env。
2. `daemon.js` per-request env 等值跳过。
3. server 单测覆盖。

**验收**：旧插件（不传 projectPath）请求 `POST /session` 直接 400 PROJECT_PATH_REQUIRED；带合法 projectPath 的请求 → daemon 启动后 `process.cwd()` 与 `process.env.IDEA_PROJECT_PATH` 都是该值。

> **PR 2 与 PR 3 必须同一窗口期合并**——单独合 PR 2，旧插件全部失联；单独合 PR 3，新插件请求被旧 server 忽略 projectPath、行为不一致。

### PR 3：插件 - 出站接入

**目标**：

1. `RemoteBridge` 接 Project + no-project 守卫 + start() 携带 projectPath + 错误 banner。
2. `sendCommand` 出站翻译。
3. `SessionHandler.translateAtMentionsAndFileTags`。

**验收**：

- 远程模式下抓包验证 `params.cwd / params.env / openedFiles / @file` 全为 remote 路径。
- 关掉所有 Project（welcome screen）→ ToolWindow 显示"请先打开一个项目"banner。
- 故意输错 mapping → server 返回 PROJECT_PATH_NOT_ACCESSIBLE → UI banner 显示并指向设置面板。
- 本地模式 zero diff。

### PR 4：插件 - 入站接入

**目标**：

1. `RemoteBridge.onSseEvent` 入站翻译。
2. `ClaudeMessageHandler` tool_use 翻译。
3. `RemoteHistoryDataSource` 调用方改走 `HistoryProjectPathEncoder.encode`。
4. JSONL 行级翻译。

**验收**：远程模式下触发权限弹窗、Read/Edit/Bash 工具调用、查看历史，UI 全部展示 local 路径；本地模式 zero diff。

### PR 5：插件 - UI + 角标 + 联调

**目标**：

1. `RemoteServerSection` 路径映射子面板上线。
2. 角标 + miss 详情 popover。
3. `handleSetPathMapping` 触发 `shutdownDaemon()` 联调验证。
4. 4 OS 组合（Win→Linux / Linux→Win / Linux→Linux / Mac→Linux）端到端冒烟。

**验收**：用户可在 UI 完成全套配置；改完不重启 IDE 立即生效；角标在拖入项目外文件时累计。

## 12. 测试用例

### 12.1 `DefaultPathMapperTest`（关键）

```java
// Win → Linux
@Test void winToLinux_basic() {
    assertEquals("/home/devuser/projects/proj-x/zz/ddd",
                 mapper.toRemote("D:\\www\\ai\\proj-x\\zz\\ddd"));
}

@Test void winToLinux_caseInsensitive() {
    assertEquals("/home/devuser/projects/proj-x/zz/ddd",
                 mapper.toRemote("d:/WWW/AI/PROJ-X/zz/ddd"));
}

@Test void winToLinux_rootSelf() {
    assertEquals("/home/devuser/projects/proj-x", mapper.toRemote("D:\\www\\ai\\proj-x"));
}

@Test void miss_returnsOriginal_andTracked() {
    assertEquals("E:\\other\\file", mapper.toRemote("E:\\other\\file"));
    assertEquals(1, tracker.outboundCount());
}

@Test void boundaryGuard_noPartialPrefixMatch() {
    // localRoot="D:\\www\\ai\\proj-x" 不应匹配 "D:\\www\\ai\\proj-x-foo"
    assertEquals("D:\\www\\ai\\proj-x-foo\\file",
                 mapper.toRemote("D:\\www\\ai\\proj-x-foo\\file"));
}

@Test void roundTrip() {
    String local = "D:\\www\\ai\\proj-x\\zz\\ddd";
    assertEquals(local, mapper.toLocal(mapper.toRemote(local)));
}

@Test void identity_neverTranslates() {
    PathMapper m = IdentityPathMapper.INSTANCE;
    assertEquals("D:\\foo", m.toRemote("D:\\foo"));
    assertFalse(m.isActive());
}
```

### 12.2 `PathFieldVisitorTest`

- 嵌套 `$.a.b.c` / 数组 `$.a[*].path` / map 全 key 替换 `$.openedFiles.*`
- 字段缺失 / null 容错 / 非字符串叶子跳过

### 12.3 `RemoteBridgeNoProjectTest`

```java
@Test void start_withNullProject_failsImmediately() {
    RemoteBridge b = new RemoteBridge("http://x", null);
    StubListener l = new StubListener();
    b.setLifecycleListener(l);
    assertFalse(b.start());
    assertEquals("PROJECT_NOT_OPEN", l.lastErrorCode);
}

@Test void start_with400ProjectPathNotAccessible_propagatesError() {
    // mock server 返回 400 code=PROJECT_PATH_NOT_ACCESSIBLE
    // 验证 listener 收到对应 code
}
```

### 12.4 Server `session-create.test.js`

```js
test('POST /session 缺 projectPath → 400 PROJECT_PATH_REQUIRED', async () => {
  const r = await fetch(`${base}/session`, { method: 'POST' });
  expect(r.status).toBe(400);
  expect((await r.json()).code).toBe('PROJECT_PATH_REQUIRED');
});

test('POST /session 路径不存在 → 400 PROJECT_PATH_NOT_ACCESSIBLE', async () => {
  const r = await fetch(`${base}/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectPath: '/nope/nope/nope' }),
  });
  expect(r.status).toBe(400);
  expect((await r.json()).code).toBe('PROJECT_PATH_NOT_ACCESSIBLE');
});

test('POST /session 合法路径 → daemon process.cwd 与 env 正确', async () => {
  const tmp = fs.mkdtempSync('/tmp/ai-bridge-test-');
  const r = await fetch(`${base}/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectPath: tmp }),
  });
  expect(r.status).toBe(200);
  const { sessionId } = await r.json();
  // 通过 claude.send 发个 echo 命令验证 daemon cwd（具体 hook 见 daemon test scaffolding）
});
```

### 12.5 集成冒烟（PR 5）

- Win local + Linux remote：拖文件 → @-token 变远端形态 → server 收到 remote → daemon cwd 在 remote → tool_use Read 成功 → 权限弹窗显示 local 路径。
- 切 Project：A 关闭 → daemon A 被 DELETE → B 创建新 session → daemon B 的 cwd 在 B 的 remote root。
- 改 mapping → daemon 重建 → 新一条消息使用新映射。

## 13. 边界 / 兼容性

| 场景 | 行为 |
|---|---|
| 本地模式 | `PathMapperHolder` 始终 `IdentityPathMapper`，全链路 no-op；server 仍要求 projectPath（local 模式不走 RemoteBridge，无影响） |
| 远程模式 + `pathMapping.enabled=false` | Identity；插件发的 projectPath 是 local 字符串 → server 在自己系统 stat 该字符串。**只在"local OS 与 remote OS 路径碰巧一致"时可用**（§0 拓扑） |
| 远程模式 + 配置不完整 | `cfg.isUsable()=false` → Identity，同上 |
| 路径不在映射 root 下 | 透传原值 + 计入 miss tracker |
| 没打开项目 | `RemoteBridge.start()` 拒绝；UI banner "请先打开一个项目" |
| projectPath 在 server 端 stat 失败 | server 返回 400 PROJECT_PATH_NOT_ACCESSIBLE；UI banner 引导改 mapping 或调整 docker 挂载 |
| 老插件连新 server | server 返回 400 PROJECT_PATH_REQUIRED；用户被引导升级插件 |
| 新插件连老 server | 老 server 不读 body，仍按旧行为 spawn（process.cwd=server WORKDIR）；插件无法察觉。**部署时禁止这个组合**——server PR 与插件 PR 必须同步发布 |
| 配置变更 | `handleSetPathMapping` → `PathMapperHolder.rebuild()` → `claudeSDKBridge.shutdownDaemon()`，下次发送消息重建 daemon |
| Project 切换 / 多 Project 并存 | 每 Project 独立 `PathMapperHolder` + `PathMissTracker` + `RemoteBridge` + 远端 daemon |
| 历史缓存 key | 用 `localProjectPath`，配合 `shutdownDaemon` 自然失效；JSONL 行级翻译保证渲染 |
| `~/.claude/skills/foo` 等系统路径 | 不命中 root → 透传 → UI 展示原值（双方系统中本就不需对应） |
| `Bash.command` 中嵌路径 | **不翻**（free-text 原则） |
| `tool_result.content` 文本 | **不翻**（free-text 原则） |
| Win 大小写 / 末尾分隔符差异 | `canonical()` 用 `toLowerCase` 归一化做前缀比较；输出保留 root 原始大小写 |
| daemon idle 超时 60s | 多 Project 场景下，被切到后台的 ToolWindow 可能 SSE 断 → 60s 后 daemon 被回收；切回时重建。可接受 |

## 14. 与本方案外其他改造的关系

- **本方案以外的远程模式增强**（如 daemon 心跳、Last-Event-ID 重连完善等）独立推进，与本方案不冲突。
- **本地模式**：完全无侵入。
- **`ai-bridge`（daemon 内部）**：除 `daemon.js:307-315` per-request env 等值跳过外，其他全部不动。
- **历史 server (`ai-bridge-server/src/history-server.js`)**：projectPath query 参数不动；插件侧 `HistoryProjectPathEncoder` 处理 base64 编码内容的"远端化"。

## 15. 决策摘要（ADR 级别）

| ID | 决策 | 理由 |
|---|---|---|
| D1 | 单条映射 `localRoot ↔ remoteRoot` | 简化实现；root 下子目录天然继承 |
| D2 | 配置作用域 = Project 级 | 不同 Project 可对应不同远端服务 / 路径 |
| D3 | 翻译边界 = `RemoteBridge` 出/入站 + 少数业务点 | 协议层集中处理，业务层只补结构化点 |
| D4 | manifest 驱动字段翻译，非全 JSON 启发式 | 零假阳性；新增字段需显式登记 |
| D5 | Free-text 不翻 | 假阳性高；用户已确认 |
| D6 | 输入框 / 已发送 prompt 显示 remote（方案 a） | 翻译位置最早；AI 收到的就是 remote 路径 |
| D7 | `localOs` 不自动检测 | 以配置为准避免误判 |
| D8 | 配置变更强制 `shutdownDaemon()` | 已建立 daemon 持有旧 mapper 行为不一致，重建最干净 |
| D9 | 未配置映射 = `IdentityPathMapper` | 兼容 §0 拓扑；零成本兜底 |
| **D10** | **`projectPath` 必填，无向下兼容** | daemon cwd 必须绑定到当前项目；老行为（落 server WORKDIR）所有失效场景必须根治 |
| **D11** | **没打开项目 → 立即拒绝，UI banner 引导** | 没有 cwd 来源就没必要建 RemoteBridge；早 fail 早提示 |
| **D12** | **server PR 与插件 PR 必须同步发布** | 单边升级会破坏端到端流程；CI 上加版本校验拦住误用 |
| **D13** | **server 不实现路径映射，纯按本系统路径工作** | server 无法反向校验 host 路径；翻译只在能拿到双侧信息的插件处发生 |

---

**文档版本**：v2.0 · 2026-05-07 · 整合 server 侧改造与 projectPath 必填决策
