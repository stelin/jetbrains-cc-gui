package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.registry.SessionKind;
import com.github.claudecodegui.session.registry.SessionManifest;
import com.github.claudecodegui.session.registry.SessionRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Session-kind refactor (S4): data source for the <b>workflow</b> history tab.
 *
 * <p>Reads {@code SessionRegistry.listByKind(WORKFLOW)} and maps each workflow
 * container onto the existing history-summary shape plus the workflow fields
 * {@code containerId/kind/workflowId/childCount}. One row per workflow (the
 * {@code kind=SUPERVISED} child node containers stay hidden behind it). Pushed
 * to the webview via {@code window.onWorkflowHistory(<json string>)}.
 */
class WorkflowHistoryService {

    private static final Logger LOG = Logger.getInstance(WorkflowHistoryService.class);

    private final HandlerContext context;

    WorkflowHistoryService(HandlerContext context) {
        this.context = context;
    }

    /** Build + push the workflow history summary. Off the IPC thread — the
     *  registry does a one-time lazy disk scan on first read. */
    void list() {
        CompletableFuture.runAsync(() -> {
            try {
                if (context.getProject() == null) return;
                List<SessionManifest> manifests = SessionRegistry.getInstance(context.getProject())
                        .listByKind(SessionKind.WORKFLOW);

                JsonArray sessions = new JsonArray();
                for (SessionManifest m : manifests) {
                    if (m == null || m.containerId == null) continue;
                    JsonObject s = new JsonObject();
                    // Reuse the normal history-summary shape. containerId doubles as
                    // the list key + restore id (workflowId == containerId == wfId).
                    s.addProperty("sessionId", m.containerId);
                    s.addProperty("title", summaryTitle(m));
                    s.addProperty("messageCount", 0);
                    s.addProperty("lastTimestamp", m.lastActiveAt);
                    // —— container / workflow fields ——
                    s.addProperty("containerId", m.containerId);
                    s.addProperty("kind", "workflow");
                    if (m.workflowId != null) s.addProperty("workflowId", m.workflowId);
                    s.addProperty("childCount", m.childContainerIds != null ? m.childContainerIds.size() : 0);
                    sessions.add(s);
                }

                JsonObject out = new JsonObject();
                out.addProperty("success", true);
                out.add("sessions", sessions);
                out.addProperty("total", sessions.size());
                pushToWebview("window.onWorkflowHistory", new Gson().toJson(out));
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] load_workflow_history failed: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
                pushToWebview("window.onWorkflowHistory",
                        "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    /** Display title: explicit title, else the container id — always non-null. */
    private static String summaryTitle(SessionManifest m) {
        if (m.title != null && !m.title.isEmpty()) return m.title;
        return m.containerId;
    }

    /**
     * Push a JSON string to the named webview callback. Mirrors
     * {@code HistoryLoadService}'s base64 + TextDecoder dance so non-ASCII
     * titles and embedded quotes survive the JS string boundary intact.
     */
    private void pushToWebview(String fn, String json) {
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String js = "if (" + fn + ") { try {"
                + "  var b='" + base64 + "';"
                + "  var bin=atob(b); var bytes=new Uint8Array(bin.length);"
                + "  for (var i=0;i<bin.length;i++){bytes[i]=bin.charCodeAt(i);}"
                + "  " + fn + "(new TextDecoder('utf-8').decode(bytes));"
                + "} catch(e){ console.error('[Backend->Frontend] " + fn + " failed:', e); } }";
        context.executeJavaScriptOnEDT(js);
    }

    private static String escapeJson(String s) {
        if (s == null) return "unknown";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
