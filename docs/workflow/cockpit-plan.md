# 工作流多窗口看板（Workflow Cockpit）编码方案

> 目标：解决"同层多个节点并发执行时，靠 `kickoffAndReveal → launcher.focus(tab)` 只能显示一个 tab、其余节点 webview 不挂载、注入不到 main AI"的问题。
> 方案：每个节点用一个**独立浮动窗口**（复用现有 `DetachedChatFrame` 基建），并发节点的窗口同屏平铺；可见的 JFrame 会真正 realize JCEF webview → 方案 C 的接线生效 → 节点正常推进。

## 0. 已确定的产品决策（来自用户）

| # | 决策 |
|---|---|
| 多显示器 | 有副屏时，**优先把节点窗甩到副屏**；只有单屏时才落在主屏。 |
| 打扰度 | 一次弹 N 个窗**不抢焦点**（`setAutoRequestFocus(false)` + 落副屏）。 |
| 完成行为 | **完成后不关闭窗口**，保留以查看实时/历史记录。 |
| 可读性 | 5–6 平铺可接受（每格较小 OK）。 |
| 升级聚焦 | 节点 `escalate` 时把**对应窗口置顶**（`toFront`），替代现在的工具窗 tab 聚焦。 |

## 1. 总体架构

```
SupervisorWorkflowManager (engine, wf-scheduler 单线程)
   │  startWorkflow → 先开"看板"(所有节点的平铺浮动窗，占位态)
   │  startNode     → 把活的 ClaudeChatWindow 装进该节点的窗 + startPairWired + kickoff
   │  focus/escalate→ cockpit.bringToFront(node)
   ▼
NodeLauncher (boundary, 可注入 Fake 测试)
   ▼
WorkflowCockpit (per-project)         ← 新增：管理一组节点窗
   ├─ WorkflowWindowLayout            ← 新增：纯函数，算平铺 bounds + 选屏
   └─ WorkflowNodeFrame[] (JFrame)    ← 新增：托管单个节点的 ClaudeChatWindow
```

**关键机制**：浮动窗 `setVisible(true)` 即可见 → JCEF realize → `pair_webview_ready` → `markWebviewReady` → 方案 C 接好的 `WebviewBridgeImpl`/`messageHandler` 通道 drain。**这一步是整个特性成立的根本**（工具窗 tab 只有选中那个才 realize；浮动窗每个都 realize）。

**窗口何时创建**：工作流启动时，给**每个节点**先开一个浮动窗（占位 Swing 面板，"节点 X · 排队中/等待上游 A、B"），不创建 JCEF。引擎按 DAG+并发调度到某节点时，才创建该节点的 `ClaudeChatWindow` 并 swap 进窗 → 此时窗已可见 → JCEF 挂载。这样：
- 满足"提前弹 N 个窗、拼图平铺"的诉求；
- 占位面板很轻，**把昂贵的 JCEF 创建推迟到节点真正开跑**（避免 N 个 daemon/浏览器一次性起来）。

## 2. 新增组件

### 2.1 `WorkflowWindowLayout`（纯工具类，可单测）
`session/pair/workflow/WorkflowWindowLayout.java`

```java
final class WorkflowWindowLayout {
    record Slot(int x, int y, int w, int h) {}

    /** 平铺网格维度：偏宽。1→1x1, 2→1x2(左右各半), 3-4→2x2, 5-6→2x3, 更多→ceil(sqrt)。*/
    static int[] grid(int n) {            // 返回 {rows, cols}
        if (n <= 1) return new int[]{1, 1};
        if (n == 2) return new int[]{1, 2};
        if (n <= 4) return new int[]{2, 2};
        if (n <= 6) return new int[]{2, 3};
        int cols = (int) Math.ceil(Math.sqrt(n));
        return new int[]{(int) Math.ceil((double) n / cols), cols};
    }

    /** 在 area 内把 n 个窗平铺成网格（纯函数，无 IDE 依赖 → 单测覆盖）。*/
    static java.util.List<Slot> tile(java.awt.Rectangle area, int n) {
        int[] rc = grid(n); int rows = rc[0], cols = rc[1];
        int cw = area.width / cols, ch = area.height / rows;
        var slots = new java.util.ArrayList<Slot>(n);
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            // 末行不满时把该行剩余宽度均摊（避免右侧留白），可选 polish
            slots.add(new Slot(area.x + c * cw, area.y + r * ch, cw, ch));
        }
        return slots;
    }

    /** 选目标屏：>1 屏时优先选"非 IDE 所在屏"；否则 IDE 屏。*/
    static GraphicsConfiguration targetScreen(Project project) { /* §5 */ }

    /** 可用区域 = 屏幕 bounds 减去任务栏/Dock 的 insets。*/
    static Rectangle usableBounds(GraphicsConfiguration gc) {
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top,
                b.width - in.left - in.right, b.height - in.top - in.bottom);
    }
}
```

### 2.2 `WorkflowNodeFrame`（单节点浮动窗）
`ui/detached/WorkflowNodeFrame.java`（复用 `DetachedChatFrame` 的主题/JCEF-host 模式，但生命周期为工作流语义）

职责：
- `extends JFrame`，标题 `节点「X」· 监督者 Code Supervisor`。
- 初始显示**占位面板**（`JBLabel`：排队中 / 等待上游 A、B）。
- `attach(ClaudeChatWindow win)`：把占位换成 `win.getContent()`，`revalidate()` → JCEF 挂载。
- `setStatus(NodeStatus)`：改标题前缀 + 边框色（PENDING 灰 / RUNNING 蓝 / WAITING_HUMAN 红 / DONE 绿 / ABORTED 灰）。
- `bringToFront()`：`setAutoRequestFocus(true); toFront(); requestFocus();` + 短暂高亮边框（escalation/jump）。
- 不抢焦点：构造后 `setAutoRequestFocus(false)`，`setVisible(true)` 不抢焦点。
- 关闭：`windowClosing` → 回调 `onUserClose(windowId)`（引擎漏斗 `onNodeTabClosed`），**不弹** `DetachedChatFrame` 那个 Yes/No/Cancel 模态。
- 主题：复用 `DetachedChatFrame.applyIdeTheme/updateThemeColors`（可抽到共享基类 `ChatHostFrame`，或直接拷模式）。

### 2.3 `WorkflowCockpit`（per-project，管理一组节点窗）
`session/pair/workflow/WorkflowCockpit.java`

```java
final class WorkflowCockpit {
    // EDT 上调用
    void open(Project project, List<WorkflowNode> nodes);        // 建 N 个平铺占位窗
    NodeFrameHandle attach(String nodeName, ClaudeChatWindow win);// 节点开跑：装活窗，返回 windowId/handle
    void bringToFront(String nodeName);                          // 升级/jump 置顶
    void setStatus(String nodeName, NodeStatus status);          // 状态边框
    void closeAll();                                             // 手动关看板/teardown
    void dispose();                                              // 工程关闭
}
```

内部：`Map<String, WorkflowNodeFrame> frames`。窗口创建/复位走 `ApplicationManager.getApplication().invokeLater`（EDT）。沿用 `DetachedWindowManager` 思路（按 projectKey 跟踪、`disposeAllDetached`）做工程级清理，避免内存泄漏。

## 3. 引擎集成（`SupervisorWorkflowManager`）

| 现状 | 改为 |
|---|---|
| `startWorkflow` 直接 `enqueueReady → pump` | 先 `launcher.openCockpit(def.nodesSafe())`（EDT 建所有节点平铺占位窗），再 `pump` |
| `startNode` → `launcher.launch`(建 tab + startPair) | `launcher.launch` 改为"把活窗 attach 进该节点既有的看板格 + startPairWired + kickoff"（不再建工具窗 tab） |
| `kickoffAndReveal → launcher.focus(h)` | 节点开跑无需 reveal（窗已可见）；`focus` 改名/改为 `bringToFront`，仅 jump/escalate 用 |
| `escalate → focusNode(name)` | `escalate → launcher.bringToFront(name)` + 红边框 |
| `jumpToNode` | `launcher.bringToFront(name)` |
| 完成 `COMPLETED` | `cockpit.setStatus(done)`，**不关窗** |
| `abortWorkflow` | 停 pair；`setStatus(ABORTED)`；窗保留（可手动关） |
| `onNodeTabClosed(windowId)` | 仍是窗关闭漏斗（窗 X → WAITING_HUMAN），不变 |

`kickoffAndReveal` 里 `launcher.focus(h)` 这行删除/改为不调用（节点窗已可见，无需 reveal 来 drain 注入缓冲）。这同时让那个 stale 测试 `blockedNodeDoesNotFreeSlotOrAdvanceDownstream` 的"启动期 focus"消失。

## 4. `NodeLauncher` 接口重构

```java
public interface NodeLauncher {
    /** 工作流启动(EDT)：开所有节点的平铺占位窗。*/
    void openCockpit(java.util.List<WorkflowNode> nodes);

    /** 节点被调度：把活窗装进既有看板格，startPairWired + 回报。*/
    void launch(WorkflowNode node, Path planPath, Sink sink);   // 语义：attach + startPair

    /** 置顶节点窗（jump / escalate）。替代旧 focus()。*/
    void bringToFront(NodeHandle handle);

    void stop(String windowId);
    void openReport(String reportPath);

    /** 手动关看板 / teardown。*/
    void closeCockpit();

    interface Sink { void tabCreated(String windowId, NodeHandle handle);
                     void pairStarted(String pairId, Path pairDir);
                     void failed(String reason); }
}
```

`IdeNodeLauncher` 改造：
- `openCockpit`：`new WorkflowCockpit().open(project, nodes)`（持有 cockpit 引用）。
- `launch`：EDT 上 `new ClaudeChatWindow(project, true)` → `cockpit.attach(node.name, win)`（装进占位窗，**不** `cm.addContent`）→ `sink.tabCreated` → bg `win.getChatWindowDelegate().getPairHandler().startPairWired(...)`（方案 C，不变）→ `sink.pairStarted`。
- `bringToFront`：`cockpit.bringToFront(nodeNameOf(handle))` 或直接 `handle.win` 对应 frame `toFront`。
- `stop`/`openReport`：不变。
- `closeCockpit`：`cockpit.closeAll()`。

> `FakeLauncher`（测试）：`openCockpit/closeCockpit` 记录；`launch` 仍同步 `tabCreated → pairStarted`；`focus` → `bringToFront`（记录到 `broughtToFront`）。

## 5. 多显示器选屏（§2.1 `targetScreen`）

```java
static GraphicsConfiguration targetScreen(Project project) {
    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsDevice[] devices = ge.getScreenDevices();
    GraphicsDevice ideDev = null;
    Frame ideFrame = WindowManager.getInstance().getFrame(project);
    if (ideFrame != null && ideFrame.getGraphicsConfiguration() != null)
        ideDev = ideFrame.getGraphicsConfiguration().getDevice();
    if (devices.length > 1 && ideDev != null) {
        for (GraphicsDevice d : devices)
            if (!d.equals(ideDev)) return d.getDefaultConfiguration();   // 优先副屏
    }
    return (ideDev != null ? ideDev : ge.getDefaultScreenDevice()).getDefaultConfiguration();
}
```

平铺：`Rectangle area = usableBounds(targetScreen(project)); List<Slot> slots = tile(area, nodes.size());`。

## 6. 不抢焦点

- 每个 `WorkflowNodeFrame`：构造后 `setFocusableWindowState(true); setAutoRequestFocus(false);` → `setVisible(true)` 不抢焦点。
- 落副屏（§5）进一步避免盖住 IDE。
- 全部显示完，可 `ideFrame.toFront()` 兜底，确保 IDE 仍持焦点。
- **例外**：`bringToFront`（escalation/jump）时临时 `setAutoRequestFocus(true)` 再 `toFront()`——这是用户主动想要的聚焦。
- 跨平台注意：macOS Spaces / Linux WM 对 `setAutoRequestFocus` 行为不一，需各 OS 实测（§11 风险）。

## 7. 升级聚焦（escalate → toFront）

`escalate(name, reason)` 改：
```java
private void escalate(String name, String reason) {
    pushEscalationNotice(name, reason);     // 不变：toast/notice
    launcher.bringToFront(name);            // 旧: focusNode(name)
}
```
`WorkflowNodeFrame.bringToFront()`：`toFront()` + 边框红色脉冲（`Timer` 闪 1–2 次）提示"这个节点要人"。同时 `setStatus(WAITING_HUMAN)` 已把边框置红。

## 8. 生命周期

| 事件 | 处理 |
|---|---|
| 工作流启动 | `openCockpit`：N 个平铺占位窗（副屏、不抢焦点） |
| 节点 READY→RUNNING | `attach` 活窗 + startPairWired + kickoff；`setStatus(RUNNING)` |
| 节点 DONE | `setStatus(DONE)`；**窗保留** |
| 节点 WAITING_HUMAN | `setStatus(WAITING_HUMAN)` + `bringToFront` + 红框脉冲 |
| 工作流 COMPLETED | 所有窗保留；标题加 ✓；可加"全部关闭"按钮 |
| Abort | 停 pair；`setStatus(ABORTED)`；窗保留 |
| 用户关单个窗 (X) | `onNodeTabClosed(windowId)`（运行中→WAITING_HUMAN）；从 cockpit 移除 |
| 关看板（手动/工作流页操作） | `closeCockpit()` dispose 所有窗 |
| 工程/IDE 关闭 | `WorkflowCockpit.dispose()`（沿用 `DetachedWindowManager.disposeAllDetached` 模式） |

## 9. 与方案 C 的衔接（不重写）

节点窗里的 `ClaudeChatWindow` 仍自带 `PairHandler`；`launch` 仍调 `getChatWindowDelegate().getPairHandler().startPairWired(...)`。**唯一变化**：宿主从"工具窗 tab"变成"浮动 JFrame"。因为 JFrame 可见 → JCEF realize → `pair_webview_ready` → `markWebviewReady` → 方案 C 接好的传输 drain。**方案 C + 看板 = 并发节点真正可跑可见的完整闭环**。

## 10. 前端改动：几乎为零

- 节点窗"未开跑"的占位是**纯 Swing 面板**（在 `WorkflowNodeFrame` 里），不碰 React。
- 节点开跑后就是标准 `ClaudeChatWindow` 的 React 应用，走 `onPairResume`/`onSupervisorMessageBatch`（已有）。
- 可选 polish：给节点窗的 React 加一个"工作流节点"水印/badge（非必须）。

## 11. 测试

- **`WorkflowWindowLayoutTest`（纯单测）**：`grid(1..8)`、`tile(area,n)` 的格子数/坐标/不重叠/铺满；2→左右各半。
- **引擎测试**：更新 `FakeLauncher`（`openCockpit/closeCockpit` no-op、`bringToFront` 记录）。修 stale 测试 `blockedNodeDoesNotFreeSlotOrAdvanceDownstream`：断言**升级带来一次 `bringToFront(A)`**（delta 或 last == A），不再数启动期 focus。
- **窗口类**（`WorkflowNodeFrame`/`WorkflowCockpit`）：Swing/JCEF 难纯单测 → 逻辑尽量下沉到 `WorkflowWindowLayout`；窗口本身靠 IDE 手测（§13 验收）。

## 12. 风险 / 边界

| 风险 | 处理 |
|---|---|
| 单显示器 | 节点窗会盖住 IDE（用户接受）。可加"看板开关"或"贴边/缩小"选项。 |
| N > 6 | 太小不可读。**上限 `MAX_COCKPIT_TILES`（如 6）**：超过则回退到现状（tab + reveal，单跑）或分页看板。需提示用户。 |
| N 个 JCEF 内存 | 占位推迟 JCEF 到开跑，缓解启动峰值；完成不关窗 → 最终仍最多 N 个 JCEF。给"完成后自动折叠/关闭非活跃窗"可选项。 |
| `setAutoRequestFocus` 跨平台 | macOS/Linux WM 行为差异，逐 OS 实测。 |
| 远程模式 | 不受影响（daemon 侧；窗口只是宿主）。 |
| dispose 时序 | 窗关 vs pair 停 vs 引擎态：复用 `onNodeTabClosed` 漏斗 + cockpit dispose-all。 |
| 与"docked tab"并存 | 加 setting：工作流运行模式 = `cockpit`(浮窗) | `tabs`(现状)，默认 cockpit，并发>1 时强制 cockpit。 |

## 13. 分阶段实施（建议顺序）

- **P1**：`WorkflowWindowLayout` + 单测（grid/tile/选屏纯逻辑）。**零风险、先落地可测。**
- **P2**：`WorkflowNodeFrame`（JFrame 宿主、占位、主题、status 边框、toFront、关闭漏斗、不抢焦点）。
- **P3**：`WorkflowCockpit`（open/attach/bringToFront/setStatus/closeAll/dispose）。
- **P4**：`NodeLauncher` 接口重构 + `IdeNodeLauncher`（openCockpit / launch=attach+startPairWired / bringToFront / closeCockpit）。
- **P5**：`SupervisorWorkflowManager` 接入（startWorkflow→openCockpit；startNode→attach；focus/escalate→bringToFront；生命周期；删 kickoffAndReveal 的 focus）。
- **P6**：测试更新（FakeLauncher、修 stale 测试）+ setting 开关 + `MAX_COCKPIT_TILES` 回退。
- **P7**：polish（escalation 红框脉冲、末行铺满、完成后"全部关闭"按钮、可选 reattach 回工具窗）。

## 14. 验收（IDE 手测，沙箱无法跑）

1. 设计 2 节点同层并发工作流 → 运行 → 副屏左右各半两个窗，IDE 不失焦；两个节点的 Supervisor 同时 tick、各自向 main AI 下发。
2. 设计 5 节点 → 5 窗平铺；带依赖的下游节点先占位"等待上游"，上游完成后自动 attach 开跑。
3. 某节点 escalate → 其窗置顶 + 红框，其余窗不动。
4. 全部完成 → 窗不关，可逐个回看记录。
5. 关单个节点窗 → 该节点 WAITING_HUMAN。
