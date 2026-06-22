package com.github.claudecodegui.ui.detached;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.util.HtmlLoader;
import com.github.claudecodegui.util.JBCefBrowserFactory;
import com.github.claudecodegui.util.JsUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.regex.Pattern;

/**
 * 缺陷「AI 分析」的【独立分离窗口】(脱离 IDE 主窗的 JFrame),自带一个 JBCefBrowser
 * 载入同一份 claude-chat.html。批量选中缺陷后从主窗发 {@code open_bug_analysis_window}
 * 打开本窗口;窗口关闭即取消分析 + 销毁 browser,数据不留存(ephemeral)。
 *
 * <p>设计见 docs/yx/bug_ai_analysis_*_design.md。要点:
 * <ul>
 *   <li><b>引导</b>: 载入前把 webview 载荷注入 {@code window.__BUG_ANALYSIS_BOOT__},
 *       main.tsx 据此只渲染 BugAnalysisStandalone(不引导整个 App)。</li>
 *   <li><b>隔离</b>: 本窗口自带 {@link HandlerContext}(callJavaScript 指向【本窗口】browser),
 *       故 analyze 的进度/直播/结果都推回本窗口;分析跑在隔离 scratch 会话,不碰主对话。</li>
 *   <li><b>派单跨窗</b>: 结果里的 建监督者/建会话(create_new_*)转发给 {@link #mainWindow}
 *       的 dispatcher → 在 IDE 主工具窗开 tab(本窗口无 tab 容器)。</li>
 * </ul>
 */
public final class BugAnalysisFrame extends JFrame {

    private static final Logger LOG = Logger.getInstance(BugAnalysisFrame.class);
    private static final Gson GSON = new Gson();
    /** Same safety guard as ClaudeChatWindow.callJavaScript — only constant callback names are used. */
    private static final Pattern SAFE_FN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*$");

    private final Project project;
    private final ClaudeChatWindow mainWindow;
    private final JBCefBrowser browser;
    private final JBCefJSQuery jsQuery;
    private final ProjectConfigHandler configHandler;
    /** Cloud project id of this analysis — used to cancel on close. */
    private final String projectId;
    /** HTML (with boot data injected); loaded in {@link #open()} after the frame is realized. */
    private final String html;

    private volatile boolean disposed = false;

    public BugAnalysisFrame(Project project,
                            ClaudeChatWindow mainWindow,
                            ClaudeSDKBridge claudeSDKBridge,
                            CodexSDKBridge codexSDKBridge,
                            CodemossSettingsService settingsService,
                            String bootJson) {
        super("缺陷 AI 分析");
        this.project = project;
        this.mainWindow = mainWindow;
        this.projectId = extractProjectId(bootJson);

        this.browser = JBCefBrowserFactory.create();

        // JS → Java bridge: window.sendToJava(msg) routes into handleMessage.
        this.jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        jsQuery.addHandler((msg) -> {
            handleMessage(msg);
            return new JBCefJSQuery.Response("ok");
        });

        // HandlerContext bound to THIS browser → analyze pushes (progress/stream/result)
        // land in this window, not the main tool window.
        HandlerContext context = new HandlerContext(
                project, "bug-analysis-window", claudeSDKBridge, codexSDKBridge, settingsService,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        BugAnalysisFrame.this.callJavaScript(functionName, args);
                    }

                    @Override
                    public String escapeJs(String str) {
                        return JsUtils.escapeJs(str);
                    }
                });
        try {
            context.setBrowser(browser);
        } catch (Throwable ignored) {
            // setBrowser is best-effort; callJavaScript goes through the JsCallback above regardless.
        }
        this.configHandler = new ProjectConfigHandler(context);

        // Inject window.sendToJava once the page loads.
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame != null && !frame.isMain()) {
                    return;
                }
                String injection = "window.sendToJava = function(msg) { " + jsQuery.inject("msg") + " };";
                cefBrowser.executeJavaScript(injection, cefBrowser.getURL(), 0);
            }
        }, browser.getCefBrowser());

        this.html = injectBoot(new HtmlLoader(BugAnalysisFrame.class).loadChatHtml(), bootJson);

        setupUI();
        setupClose();
    }

    private void setupUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(browser.getComponent(), BorderLayout.CENTER);
        setContentPane(root);
        setSize(1100, 820);

        Frame ide = WindowManager.getInstance().getFrame(project);
        if (ide != null) {
            int offset = DetachedWindowManager.getDetachedWindowCount(project) * 30;
            Point p = ide.getLocation();
            setLocation(p.x + 60 + offset, p.y + 60 + offset);
        } else {
            setLocationRelativeTo(null);
        }
        // We dispose explicitly in windowClosing so we can cancel the analysis first.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    }

    private void setupClose() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAndDispose();
            }
        });
    }

    /** Realize the window, then load the HTML (JCEF renders once the heavyweight component is shown). */
    public void open() {
        setVisible(true);
        browser.loadHTML(html);
    }

    // ── JS message handling ────────────────────────────────────────────

    private void handleMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        // Ignore webview console logs forwarded over the bridge.
        if (message.startsWith("{\"type\":\"console.")) {
            return;
        }
        int colon = message.indexOf(':');
        String type = colon >= 0 ? message.substring(0, colon) : message;
        String content = colon >= 0 ? message.substring(colon + 1) : "";

        switch (type) {
            case "analyze_bugs":
                configHandler.handleAnalyzeBugs(content);
                break;
            case "cancel_bug_analysis":
                configHandler.handleCancelBugAnalysis(content);
                break;
            case "create_new_tab":
            case "create_new_supervised_tab":
                // 派单跨窗:在 IDE 主工具窗开 tab。
                if (mainWindow != null) {
                    final String raw = message;
                    ApplicationManager.getApplication().invokeLater(() -> mainWindow.dispatchWebviewMessage(raw));
                }
                break;
            default:
                // The standalone analysis webview only emits the messages above; ignore the rest
                // (heartbeat, model sync, etc. are not used in this window).
                break;
        }
    }

    /** Replica of ClaudeChatWindow.callJavaScript for THIS frame's browser (args pre-escaped by caller). */
    public void callJavaScript(String functionName, String... args) {
        if (disposed) {
            return;
        }
        if (functionName == null || !SAFE_FN.matcher(functionName).matches()) {
            LOG.warn("[BugAnalysisFrame] rejected JS function name: " + functionName);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) {
                return;
            }
            try {
                String callee = functionName.contains(".") ? functionName : ("window." + functionName);
                StringBuilder argsJs = new StringBuilder();
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) {
                            argsJs.append(", ");
                        }
                        argsJs.append("'").append(args[i] == null ? "" : args[i]).append("'");
                    }
                }
                String js = "(function(){try{ if(typeof " + callee + " === 'function'){ "
                        + callee + "(" + argsJs + "); } }catch(e){ console.error('[BugAnalysisFrame] call "
                        + functionName + " failed', e); }})();";
                browser.getCefBrowser().executeJavaScript(js, browser.getCefBrowser().getURL(), 0);
            } catch (Exception ex) {
                LOG.warn("[BugAnalysisFrame] callJavaScript failed: " + ex.getMessage());
            }
        });
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    private void closeAndDispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // Cancel the in-flight analysis (best-effort) so the daemon turn is aborted.
        try {
            if (projectId != null && !projectId.isEmpty()) {
                JsonObject c = new JsonObject();
                c.addProperty("projectId", projectId);
                configHandler.handleCancelBugAnalysis(GSON.toJson(c));
            }
        } catch (Throwable t) {
            LOG.warn("[BugAnalysisFrame] cancel on close failed: " + t.getMessage());
        }
        try {
            Disposer.dispose(browser);
        } catch (Throwable t) {
            LOG.warn("[BugAnalysisFrame] browser dispose failed: " + t.getMessage());
        }
        dispose();
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Inject the boot payload so main.tsx renders the standalone analysis (not the full App). */
    private static String injectBoot(String html, String bootJson) {
        String json = (bootJson == null || bootJson.isEmpty()) ? "{}" : bootJson;
        // Prevent a "</script>" inside any string field from breaking out of the inline script.
        String safe = json.replace("</", "<\\/");
        String script = "<script>window.__BUG_ANALYSIS_BOOT__ = " + safe + ";</script>";
        // Insert as the FIRST node in <head> so this classic inline script runs before Vite's
        // (deferred) module bundle — main.tsx must see __BUG_ANALYSIS_BOOT__ at module eval time.
        int headOpen = html.indexOf("<head>");
        if (headOpen >= 0) {
            int after = headOpen + "<head>".length();
            return html.substring(0, after) + script + html.substring(after);
        }
        int headClose = html.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + script + html.substring(headClose);
        }
        int body = html.indexOf("<body>");
        if (body >= 0) {
            int after = body + "<body>".length();
            return html.substring(0, after) + script + html.substring(after);
        }
        return script + html;
    }

    private static String extractProjectId(String bootJson) {
        try {
            JsonObject o = GSON.fromJson(bootJson, JsonObject.class);
            if (o != null && o.has("projectId") && !o.get("projectId").isJsonNull()) {
                return o.get("projectId").getAsString();
            }
        } catch (Exception ignored) {
            // malformed boot json → no project id (cancel-on-close becomes a no-op)
        }
        return "";
    }
}
