# 云效缺陷(Bug)集成 — 详细编码方案

> 项目: `jetbrains-cc-gui` (JetBrains 插件: webview React + Java 服务层 + Node daemon×2)
> 日期: 2026-06-11
> 状态: 设计待评审 → 评审通过后编码

---

## 0. 背景与目标

在插件内打通阿里云**云效(Alibaba Cloud DevOps)** 缺陷管理，使用**新版 OpenAPI**(非 `api-devops-2021-06-25-*` 旧版)。四条需求:

1. **设置页**: 在「提示词库」与「远程模式」之间新增「云效设置」页，配置 `token` + `organizationId`。
2. **我的缺陷列表**: 顶部「查看历史会话」与「工作流」之间新增入口；可选项目，列出指派给我的 Bug。
3. **一键建监督者**: 每条 Bug 一个按钮 → 新建一个监督者标签页(监督者自动选「缺陷监督者」) → 把 Bug 信息**预填进输入框但不发送**；预填文案必须含云效 BUG ID 且指定必须用 `query_bug_details` 工具查详情。
4. **查 Bug 详情工具**: 提供 AI 工具，按 Bug ID 聚合返回**基础信息 + 所有评论 + 附件(含附件评论)**；能并行的并行，每个接口失败重试 3 次。

### 已锁定决策

| # | 决策 |
|---|---|
| 设置字段 | `token` + `organizationId`；明文存 `~/.codemoss/config.json`(同 `remoteServerUrl` 路径) |
| 中心版域名 | `openapi-rdc.aliyuncs.com`，认证头 `x-yunxiao-token` |
| 列表项交互 | 每条 Bug **仅一个**【建监督者】按钮 |
| "我的"识别 | **A1 自动解析**: 用 token 调当前用户接口拿数字 `id` 并按 `(orgId,token)` 缓存 |
| 分页 | `perPage=50` + 「加载更多」，仿 `HistoryView` |
| 工具并行 | 基础/评论/附件三路并发；每路失败重试 3 次后降级 |
| 重试策略 | 仅瞬时错误(超时/429/5xx)重试 3 次+退避；确定性错误(401/403/404)立即失败 |
| 失败分级 | 基础信息(GetWorkitem)失败=工具硬失败；评论/附件失败=软降级(标注缺失) |
| daemon 落点 | **两份都加** `jetbrains-cc-gui/ai-bridge` + `ai-bridge-server/ai-bridge`；本地+远端两模式均支持 |
| 工具可见性 | 注册进 `supervisor` MCP server(缺陷监督者 `mcpAccess=true`)；额外也暴露给主 AI |

---

## 1. 总览架构

```
┌─ webview (React/TS) ──────────────────────────────────────────────┐
│  设置页 YunxiaoSection   缺陷列表 BugListView   建监督者按钮         │
└──────────────┬──────────────────┬───────────────────┬────────────┘
       sendToJava / sendBridgeEvent (IPC)              │
┌──────────────▼──────────────────▼───────────────────▼────────────┐
│  Java 服务层                                                       │
│   SettingsHandler/ProjectConfigHandler/CodemossSettingsService     │ ← 配置存取
│   YunxiaoClient(Java HTTP) ── 直连云效 ── 需求2列表/项目/当前用户    │ ← UI 查询(本地机器, 模式无关)
│   TabHandler/PairHandler ── 建监督者+预填(扩 initialComposerText)    │ ← 需求3
│   buildDaemonEnv + supervisor.start env 注入 YUNXIAO_*              │ ← 需求4 token 透传
└──────────────┬─────────────────────────────────────────────────┘
        NDJSON over stdin/HTTP (params.env)
┌──────────────▼────────────────────────────────────────────────┐
│  Node daemon ×2 (本地 ai-bridge / 远端 ai-bridge-server)          │
│   yunxiao-tools.js: query_bug_details 工具 ── 直连云效并行聚合     │ ← 需求4
│   挂到 supervisor MCP server, 读 process.env.YUNXIAO_TOKEN/ORG_ID  │
└─────────────────────────────────────────────────────────────────┘
```

**两个云效调用点(各自直连，互不依赖):**
- **需求 2(列表)**: Java 侧 `YunxiaoClient` 调用(项目/当前用户/搜缺陷)。Java 始终跑在用户机器上，本地/远端 daemon 模式都不影响。
- **需求 4(详情)**: daemon 内 Node `fetch` 调用(基础/评论/附件)。token 经 `process.env` 注入。

> 共享的只有**配置**(token+orgId+userId 缓存)，HTTP 客户端各写一套(Java 一套、JS 一套)。

---

## 2. 配置与存储

### 2.1 `~/.codemoss/config.json` 新增字段

```json
{
  "remoteServerUrl": "...",
  "yunxiao": {
    "token": "pt-xxxx",
    "organizationId": "5ebbc0228xxxx",
    "domain": "openapi-rdc.aliyuncs.com"
  }
}
```
- `domain` 默认 `openapi-rdc.aliyuncs.com`(中心版)，预留 Region/专有云覆盖。
- 明文存储(同 `remoteServerUrl` 现状)。**已知风险**: token 明文落盘，见 §10。

### 2.2 `CodemossSettingsService.java` 新增方法

镜像现有 `getRemoteServerUrl()/setRemoteServerUrl()` 范式(读写 `config.json`):
```
getYunxiaoToken() / setYunxiaoToken(String)
getYunxiaoOrgId() / setYunxiaoOrgId(String)
getYunxiaoDomain() / setYunxiaoDomain(String)   // 默认 openapi-rdc.aliyuncs.com
```

### 2.3 当前用户 userId 缓存(需求2 "我的")

- 位置: Java 侧内存缓存(`YunxiaoClient` 持有 `Map<String,String>` 或简单字段)，key=`orgId + ":" + tokenHash`。
  > ⚠️ 云效用户 `id` 是 ObjectId 风格的 hex **字符串**(如 `654458cef717cdf76f826b62`)，**不是数字**。userId 全程按 `String` 处理，`assignedTo` 的 `value` 也传该字符串(§3.1 已是字符串数组)。
- 失效: token 或 orgId 变更时清空(在 `setYunxiaoToken/OrgId` 里清)。
- 填充: 首次需要"我的缺陷"时调当前用户接口，或在设置页「测试连接」时顺带填充。

---

## 3. 云效接口清单(新版, 中心版)

> Host: `https://openapi-rdc.aliyuncs.com`  认证头: `x-yunxiao-token: <token>`
> 路径前缀: `/oapi/v1/projex/organizations/{organizationId}/...`(中心版含 org 段)

> ✅ 2026-06-11 全部 path 已对照官方 `aliyun/alibabacloud-devops-mcp-server`(master `operations/projex/*`、`operations/organization/organization.ts`、`common/utils.ts`)确认。**均为中心版**(含 `organizations/{orgId}` 段);Region/专有云用 org-less 变体,需 `isRegionEdition()` 分支。

| 用途 | 接口 | 方法 & 路径 | 状态 |
|---|---|---|---|
| 当前用户(拿我的 userId) | 当前用户 | `GET .../oapi/v1/platform/user`(仅凭 token, 返回 `{"id":"<hex>"}` **字符串非数字**) | **已确认**(官方 `getCurrentUserFunc`+真 token curl 200) |
| 项目下拉 | SearchProjects | `POST .../oapi/v1/projex/organizations/{orgId}/projects:search`(body 全可选,只带 page/perPage 即返回可见项目**顶层数组**) | **已确认/已修**(原误用 `GET .../projects` → 404 NotFound) |
| 搜缺陷 | SearchWorkitems | `POST .../oapi/v1/projex/organizations/{orgId}/workitems:search` | **已确认** |
| Bug 基础信息 | GetWorkitem | `GET .../oapi/v1/projex/organizations/{orgId}/workitems/{id}` | **已确认**(不含评论/附件) |
| Bug 所有评论 | GetWorkitemCommentList | `GET .../workitems/{id}/comments?page=&perPage=`(评论含附件评论) | **已确认**(官方 `listWorkItemCommentsFunc`) |
| Bug 附件 | ListWorkitemAttachments | `GET .../workitems/{id}/attachments` | **已确认**(官方 `listWorkitemAttachmentsFunc`) |

### 3.1 SearchWorkitems 请求体(已确认字段)

```json
{
  "category": "Bug",
  "spaceId": "<项目id>",
  "spaceType": "Project",
  "conditions": "{\"conditionGroups\":[[{\"fieldIdentifier\":\"assignedTo\",\"operator\":\"CONTAINS\",\"value\":[\"<我的userId>\"],\"className\":\"user\",\"format\":\"list\"}]]}",
  "page": 1,
  "perPage": 50
}
```
- `category=Bug` 过滤缺陷；`spaceId` 必填且不支持跨项目；`assignedTo` 走 `conditions` 传具体 userId；`perPage` 0–200。

### 3.2 关键返回字段
- SearchWorkitems / GetWorkitem: `identifier`(=workitemId, 工具用)、`serialNumber`(BUG 编号, 显示用)、`subject`(标题)、`status`、`assignedTo`、`gmtCreate/gmtModified`。
- **双标识约定**: 列表项同时带 `identifier` 和 `serialNumber`。UI 显示 `serialNumber`，建监督者按钮把 `identifier` 传给工具/预填文案。

---

## 4. IPC 协议(webview ↔ Java)

格式沿用现状: 前端 `sendToJava("type:" + JSON)` / `sendBridgeEvent("type", payload)`；Java 回调 `window.xxx(json)`。

| 方向 | 消息 | 载荷 | 说明 |
|---|---|---|---|
| →Java | `get_yunxiao_config` | — | 读配置回 `window.updateYunxiaoConfig` |
| →Java | `set_yunxiao_config` | `{token,organizationId,domain}` | 写配置 |
| →Java | `yunxiao_test_connection` | `{token,organizationId,domain}` | 调当前用户接口校验+缓存 userId，回 `window.onYunxiaoTestResult` |
| →Java | `load_yunxiao_projects` | — | 拉项目回 `window.onYunxiaoProjects` |
| →Java | `load_yunxiao_bugs` | `{projectId,page,perPage}` | 拉我的缺陷回 `window.onYunxiaoBugs`(含 hasMore) |
| →Java | `create_new_supervised_tab` | **(扩展)** `{agentId?,initialComposerText?}` | 见 §6.3，向后兼容(无载荷=原行为) |
| Java→ | `window.onSessionCreated` | **(扩展)** `+ initialComposerText?` | 预填用 |
| Java→ | `window.onRequestNewSupervisedWith` | `{agentId,initialComposerText}` | **(新)** 跳过 picker 的定向建会话 |

---

## 5. 各需求调用链(已 grounded 到代码)

### 5.1 需求1 设置页

**前端**
- `settings/SettingsSidebar/index.tsx`:
  - `SettingsTab` 联合类型(L4)加 `'yunxiao'`。
  - `sidebarItems`(L12–28)在 `{key:'remote'}`(L23)**之前**插入 `{key:'yunxiao', icon:'codicon-cloud-download', labelKey:'settings.yunxiao.title'}` → 顺序变 `…prompts, skills, yunxiao, remote…`(满足"远程模式上面")。
- `settings/index.tsx`: 引入新组件 `YunxiaoSection`，在 `RemoteServerSection` 面板**之前**按 `currentTab==='yunxiao'` 渲染。
- 新建 `settings/YunxiaoSection/{index.tsx, style.module.less}`，照抄 `RemoteServerSection` 范式: `componentDidMount` 发 `get_yunxiao_config`，输入框 onChange 暂存，保存发 `set_yunxiao_config`，「测试连接」发 `yunxiao_test_connection`。
- i18n: `i18n/locales/{zh,en}.json` 加 `settings.yunxiao.*`。

**Java**
- `handler/SettingsHandler.java`: 支持类型数组 + `handle()` switch 加 `get/set_yunxiao_config`、`yunxiao_test_connection` → 委派 `ProjectConfigHandler`。
- `handler/ProjectConfigHandler.java`: 新增 `handleGetYunxiaoConfig/handleSetYunxiaoConfig/handleYunxiaoTestConnection`(镜像 `handleGetRemoteMode/handleSetRemoteMode`)。
- `settings/CodemossSettingsService.java`: §2.2 的 get/set。

### 5.2 需求2 缺陷列表

**前端入口**(顶部按钮，"历史会话之后/工作流之前")
- `ChatHeader/ChatHeader.tsx`: 在「查看历史会话」(`onHistory`)与「工作流」(`onOpenWorkflow`)按钮之间，新增按钮，props 加 `onBugList?:()=>void`。
- `App.tsx`: `currentView` 联合类型(L231)加 `'bug-list'`；给 `ChatHeader` 传 `onBugList={()=>setCurrentView('bug-list')}`(参照 L750 `onOpenWorkflow`)；视图渲染链(L760–916)加 `bug-list` 分支渲染 `BugListView`。
- 新建 `components/BugList/BugListView.tsx` + hook `hooks/useYunxiaoBugs.ts`(仿 `HistoryView`/`useHistoryLoader`):
  - 顶部项目下拉(复用 `ChatInputBox/Dropdown`)，选中发 `load_yunxiao_bugs`。
  - 列表用 `history/VirtualList.tsx` 渲染；每项右侧一个【建监督者】按钮。
  - 「加载更多」: `page+1` 再发 `load_yunxiao_bugs`，结果单调追加(`window.onYunxiaoBugs` 带 `hasMore`)。
  - 空/错态: 未配 token/orgId → 引导去「云效设置」；未选项目 → 提示先选。

**Java 数据**
- `handler/SettingsHandler.java`(或新 `YunxiaoHandler`): 路由 `load_yunxiao_projects/load_yunxiao_bugs`。
- 新建 `client/YunxiaoClient.java`(用 golang-lib 习惯对应的 Java HTTP；本项目用现成 HTTP 工具/`HttpClient`):
  - `getCurrentUserId()`: 调当前用户接口，缓存。
  - `listProjects()` → 回 `window.onYunxiaoProjects`。
  - `searchMyBugs(projectId,page,perPage)`: 组 §3.1 body(assignedTo=缓存 userId)，回 `window.onYunxiaoBugs`。

### 5.3 需求3 建监督者 + 预填(不发送)

**现有"新监督者标签页"链(已核实):**
```
ChatHeader.onNewSupervised
  → App.tsx:751  sendBridgeEvent('create_new_supervised_tab')
  → TabHandler.java:48  → handleCreateNewTab(true):70
  → preMount: win.setPendingSupervised(true):72  (ClaudeChatWindow.pendingSupervised:88)
  → 新 tab 首次 frontend_ready
  → ChatWindowDelegate.java:565  consumePendingSupervised() → callJavaScript("onRequestNewSupervised"):567
  → App.tsx:536  window.onRequestNewSupervised = ()=>setShowNewSupervisedDialog(true)
  → NewSupervisedDialog(picker) → createSupervisedSession(agent)  [PairContext.tsx:646]
  → IPC session_create_supervised → PairHandler.java:133/664/668
  → 组 created{containerId,agentId,pairId,title}:723-726 → pushToWebview("window.onSessionCreated"):726
  → PairContext.tsx:1521 onSessionCreated: setContainerId/setPairId/setSelected(agent)
```

**改造(定向建会话, 跳过 picker, 携带预填文本):**
1. **前端按钮**: `BugListView` 的【建监督者】点击 →
   `sendBridgeEvent('create_new_supervised_tab', { agentId:'bug-supervisor', initialComposerText: <模板文案> })`。
2. **TabHandler.java**: `handleCreateNewTab` 接收可选 payload；preMount 改为 `win.setPendingSupervisedPayload(json)`(在 `ClaudeChatWindow` 把 `pendingSupervised` 升级为可带 `{agentId,initialComposerText}` 的 payload；无 payload 时等价原 `true`)。
3. **ChatWindowDelegate.java**: `consumePendingSupervised()` 返回 payload；
   - 有 `agentId` → `callJavaScript("onRequestNewSupervisedWith", json)`；
   - 无 → 维持 `onRequestNewSupervised`(向后兼容)。
4. **前端 App.tsx**: 注册 `window.onRequestNewSupervisedWith = ({agentId, initialComposerText}) => {…}`:
   - 确保监督者列表已加载(必要时先 `get_supervisor_agents`)，按 `agentId==='bug-supervisor'` 找到 agent；
   - 直接调 `createSupervisedSession(agent, initialComposerText)`(**不**弹 dialog)。
5. **PairContext.tsx**: `createSupervisedSession` 签名扩为 `(agent, initialComposerText?)`(类型 L199、实现 L646)；把 `initialComposerText` 并入 `session_create_supervised` 载荷。
6. **PairHandler.java** `handleCreateSupervisedImpl`(:668): 解析 `initialComposerText`；在组 `created` 处(:723-725)`created.addProperty("initialComposerText", text)` 再 `pushToWebview`(:726)。
7. **PairContext.tsx** `onSessionCreated`(:1521): 解析 `o.initialComposerText`，若有则 `setSupervisorDraft(o.agentId, o.initialComposerText)`(:291/943)——draft 按 agentId 落入 `draftByAgentId`，composer 挂载即显示，**不自动发送**。

> 缺陷监督者已内置(`resources/supervisor/bug-supervisor.md` + `SupervisorAgentManager`，`mcpAccess=true`)，无需新建 agent。

### 5.4 需求4 查 Bug 详情工具(并行聚合 + 重试降级)

**新建 `services/supervisor/yunxiao-tools.js`(两份 daemon 各一份):**
```js
export const SUPERVISOR_MCP_NAME = 'supervisor';            // 与 supervisor-tools.js 一致
export const QUERY_BUG_TOOL = 'query_bug_details';
export const QUALIFIED_QUERY_BUG = `mcp__${SUPERVISOR_MCP_NAME}__${QUERY_BUG_TOOL}`;

export function buildQueryBugDetailsTool(sdk, z) {
  return sdk.tool(
    QUERY_BUG_TOOL,
    '查询云效缺陷的全部信息: 基础信息 + 所有评论 + 附件(含附件评论)。传入云效工作项 identifier。',
    { bug_id: z.string().describe('云效工作项 identifier(非 serialNumber)') },
    async (args) => {
      const token = process.env.YUNXIAO_TOKEN;
      const orgId = process.env.YUNXIAO_ORG_ID;
      const domain = process.env.YUNXIAO_DOMAIN || 'openapi-rdc.aliyuncs.com';
      if (!token || !orgId) return { isError:true, content:[{type:'text', text:'云效未配置 token/organizationId'}] };

      const base = `https://${domain}/oapi/v1/projex/organizations/${orgId}/workitems/${args.bug_id}`;
      const h = { 'x-yunxiao-token': token, 'Content-Type':'application/json' };

      // 三路并发, 每路独立重试/降级
      const [info, comments, files] = await Promise.all([
        fetchRetry(`${base}`,            { headers:h }, { hard:true  }),  // 基础信息: 硬失败
        fetchRetry(`${base}/comments`,   { headers:h }, { hard:false }),  // 评论: 软降级
        fetchRetry(`${base}/attachments`,{ headers:h }, { hard:false }),  // 附件: 软降级
      ]);
      if (info.error) return { isError:true, content:[{type:'text', text:`基础信息获取失败: ${info.error}`}] };

      const payload = {
        basic: info.data,
        comments: comments.error ? `(评论获取失败: ${comments.error})` : comments.data,
        attachments: files.error ? `(附件获取失败: ${files.error})` : files.data,
      };
      return { content:[{ type:'text', text: JSON.stringify(payload, null, 2) }] };
    }
  );
}

// 重试: 仅瞬时错误(超时/429/5xx)重试3次+退避(200/400/800ms); 确定性(401/403/404)立即失败
async function fetchRetry(url, opts, { hard }) {
  let lastErr = '';
  for (let i = 0; i < 3; i++) {
    try {
      const r = await fetch(url, opts);              // 参照 mcp-status/http-verifier.js
      if (r.ok) return { data: await r.json(), error: null };
      if ([401,403,404].includes(r.status)) return { data:null, error:`HTTP ${r.status}` }; // 不重试
      lastErr = `HTTP ${r.status}`;                  // 429/5xx → 重试
    } catch (e) { lastErr = e.message; }             // 网络/超时 → 重试
    await new Promise(s => setTimeout(s, 200 * 2**i));
  }
  return { data:null, error:lastErr };
}
```

**挂载(`channels/supervisor-channel.js`, 两份各改):**
- import `{ buildQueryBugDetailsTool, QUALIFIED_QUERY_BUG }`。
- 现 `buildSupervisorMcpServer(sdk, zod, onCapture, [emitPlanTool, updateStateTool])`(:558-563)→ extraTools 追加 `buildQueryBugDetailsTool(sdk, z)`。
- `allowedToolList`(:568-576)追加 `QUALIFIED_QUERY_BUG`。
- `canUseTool`(:662-686)在按名放行分支(:663, 与 `QUALIFIED_EMIT_ACTION` 并列)追加 `QUALIFIED_QUERY_BUG`。

> `buildSupervisorMcpServer` 本身(`services/supervisor/supervisor-tools.js:245/292`)无需改——它已透传 `extraTools` 到 `tools:[emitActionTool, ...extraTools]`。

**token 透传到 daemon(关键, 两条路径):**
- daemon 已支持 `params.env → process.env` 合并+还原(本地 `daemon.js:337`、远端 `:340`)——工具读 `process.env.YUNXIAO_*` 在两份/两模式都成立。
- **主 AI 路径**: `ClaudeBridgeUtils.buildDaemonEnv(cwd)`(:23)加 `YUNXIAO_TOKEN/ORG_ID/DOMAIN`(从 `CodemossSettingsService` 读)。消费点已确认: `ClaudeDaemonCoordinator:223`、`ClaudeDaemonRequestExecutor:81`。
- **监督者路径(bug-supervisor 是主调用方, 必须覆盖)**: env 不经 `buildDaemonEnv`。三个 `supervisor.start` 站点共享 `PairSession` 参数快照(`PairSession.java:78`):
  - 初始 `PairSessionManager`、交接 `RotationCoordinator`、懒重启 `EventBus.restartSupervisor`。
  - 在 `supervisor.start` 的 `params` 组装处注入 `env:{YUNXIAO_*}`，并纳入 `PairSession` 快照(让交接/懒重启自动重放)。
  - **待核实**: 监督者每轮 `supervisor.postEvent` 是否也带 `params.env`。若每轮带→token 改了即时生效；若只 start 带→改 token 需重开监督者会话(可接受，文档注明)。

---

## 6. 预填提示词模板 + bug-supervisor.md

### 6.1 预填文案(需求3, 写入 composer, 不发送)
```
请帮我诊断并修复云效缺陷 BUG-{serialNumber}（标题：{subject}，状态：{status}）。
第一步必须调用 query_bug_details 工具，传入 bug id「{identifier}」拉取完整的基础信息、
所有评论和附件，理解清楚后再制定修复计划。
```
- 展示用 `serialNumber`；工具入参用 `identifier`(双标识，见 §3.2)。
- 工具名用模型可见名 `query_bug_details`(MCP 全名 `mcp__supervisor__query_bug_details`，提示词写短名即可)。

### 6.2 `resources/supervisor/bug-supervisor.md`(双保险)
加一句铁律: "若任务涉及云效 BUG，**第一步必须** `query_bug_details` 拉全量信息(基础+评论+附件)再动手"——不强依赖预填，监督者自查也会用。

---

## 7. 改动文件清单

### 7.1 webview (React/TS)
| 文件 | 改动 |
|---|---|
| `settings/SettingsSidebar/index.tsx` | `SettingsTab` 加 `'yunxiao'`；`sidebarItems` 在 `remote` 前插 yunxiao |
| `settings/index.tsx` | 引入并渲染 `YunxiaoSection` |
| `settings/YunxiaoSection/index.tsx`(新) + `style.module.less`(新) | token/orgId 表单 + 测试连接 |
| `ChatHeader/ChatHeader.tsx` | 历史/工作流间加【缺陷】按钮，props `onBugList` |
| `App.tsx` | `currentView` 加 `'bug-list'`；接 `onBugList`；渲染 `BugListView`；注册 `onRequestNewSupervisedWith` |
| `components/BugList/BugListView.tsx`(新) | 项目下拉 + 虚拟列表 + 加载更多 + 建监督者按钮 |
| `hooks/useYunxiaoBugs.ts`(新) | IPC 拉取 + 分页状态 |
| `SupervisorPair/PairContext.tsx` | `createSupervisedSession(agent, initialComposerText?)`；`onSessionCreated` 读 `initialComposerText` 调 `setSupervisorDraft` |
| `global.d.ts` | 加 `onRequestNewSupervisedWith`、`onYunxiaoProjects/onYunxiaoBugs/updateYunxiaoConfig/onYunxiaoTestResult` 声明 |
| `i18n/locales/{zh,en}.json` | `settings.yunxiao.*`、缺陷列表文案 |

### 7.2 Java
| 文件 | 改动 |
|---|---|
| `settings/CodemossSettingsService.java` | get/set Yunxiao token/orgId/domain；set 时清 userId 缓存 |
| `handler/SettingsHandler.java` | 路由 `get/set_yunxiao_config`、`yunxiao_test_connection`、`load_yunxiao_projects`、`load_yunxiao_bugs` |
| `handler/ProjectConfigHandler.java` | 上述 handler 实现(镜像 remote-mode) |
| `client/YunxiaoClient.java`(新) | 当前用户/项目/搜缺陷 HTTP + userId 缓存 |
| `handler/TabHandler.java` | `create_new_supervised_tab` 接 payload；preMount 传 `setPendingSupervisedPayload` |
| `ui/toolwindow/ClaudeChatWindow.java` | `pendingSupervised` 升级为可带 payload；`consumePendingSupervised` 返回 payload |
| `ui/ChatWindowDelegate.java` | 有 agentId → `onRequestNewSupervisedWith`，否则 `onRequestNewSupervised` |
| `handler/PairHandler.java` | `handleCreateSupervisedImpl` 解析并回显 `initialComposerText` |
| `provider/claude/ClaudeBridgeUtils.java` | `buildDaemonEnv` 注入 `YUNXIAO_*`(主 AI 路径) |
| `session/pair/PairSessionManager.java` + `rotation/RotationCoordinator.java` + `session/pair/EventBus.java` + `PairSession.java` | `supervisor.start` 注入 `env:{YUNXIAO_*}` 并入快照(监督者路径) |

### 7.3 daemon(**两份都改**: `jetbrains-cc-gui/ai-bridge/` 和 `ai-bridge-server/ai-bridge/`)
| 文件 | 改动 |
|---|---|
| `services/supervisor/yunxiao-tools.js`(新) | `query_bug_details` 工具(并行+重试+降级) |
| `channels/supervisor-channel.js` | import + extraTools 追加 + `allowedToolList` + `canUseTool` 放行 |

---

## 8. 验证点 & 打包

1. **编译**: Java `compileJava`；webview `tsc --noEmit` + `vite build`；daemon `node --check yunxiao-tools.js`(两份)。
2. **冒烟**:
   - 设置页存/读 token+orgId；「测试连接」返回当前用户 id。
   - 选项目→出我的缺陷列表→「加载更多」单调追加。
   - 点【建监督者】→新标签页自动选缺陷监督者→输入框已预填文案且**未发送**。
   - 监督者发送后调用 `query_bug_details`→返回基础+评论+附件聚合 JSON(可断网某接口验降级、改 token 验硬失败)。
3. **打包**: daemon 改动需重打包(本地 `ai-bridge` 嵌 JAR；远端 `ai-bridge-server` 单独部署/重启)。沙箱无 JDK 时仅 `compileJava` 验证、运行期人工验。
4. **两份 daemon 一致性**: `yunxiao-tools.js` 与 channel 改动两份逐字对齐，防漂移。

---

## 9. 风险与待核实点

| 项 | 说明 | 处置 |
|---|---|---|
| 云效新版 path 未全 pin | 当前用户/ListProjects/评论/附件 的精确新版 path 仅"能力确认"，需 OpenAPI 调试台 pin | 编码第一步: 用真实 token 在调试台逐个跑通再落代码 |
| 监督者 postEvent env | 每轮是否带 `params.env` 未核实 | 编码时核实；若否，注明"改 token 需重开会话" |
| 两份 daemon 漂移 | 历史上 `ai-bridge` 落后 `ai-bridge-server` 一代 | 改动两份对齐 + 验证实际运行的那份 |
| token 明文落盘 | 决策接受 | 同 `remoteServerUrl` 现状；后续可迁 PasswordSafe(预留 `getYunxiaoToken` 抽象，迁移不动调用方) |
| canUseTool 双白名单 | 漏登记则模型被 deny | `allowedToolList` + `canUseTool` 两处都加 `QUALIFIED_QUERY_BUG` |
| "我的"过滤 | assignedTo 需具体 userId，无 current-user 魔法值 | A1 自动解析 + 缓存(§2.3) |

---

## 10. 编码顺序建议
1. 云效接口 path 在调试台 pin 全 + `YunxiaoClient.java` 跑通(当前用户/项目/搜缺陷)。
2. 需求1 设置页(token/orgId/测试连接) — 其余都依赖它。
3. 需求2 列表(顶部入口 + BugListView + 分页)。
4. 需求4 工具(yunxiao-tools.js 两份 + channel 挂载 + env 注入两路径)。
5. 需求3 建监督者预填(IPC 扩展全链)。
6. bug-supervisor.md + i18n + 冒烟 + 重打包。
