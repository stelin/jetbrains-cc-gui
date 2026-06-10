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
 * Session-kind refactor (S4): data source for the <b>supervised</b> history tab.
 *
 * <p>Reads the {@link SessionRegistry} ledger — {@code listByKind(SUPERVISED)},
 * which already excludes workflow child nodes ({@code parentContainerId != null})
 * — and maps each manifest onto the existing history-summary shape (the same
 * {@code sessionId/title/messageCount/lastTimestamp} fields a normal session
 * carries, so the list renders identically) plus the container fields
 * {@code containerId/kind/agentId}. The result is pushed to the webview via
 * {@code window.onSupervisedHistory(<json string>)} — the channel HistoryView
 * subscribed to (it {@code JSON.parse}es the string into {@code HistoryData}).
 */
class SupervisedHistoryService {

    private static final Logger LOG = Logger.getInstance(SupervisedHistoryService.class);

    private final HandlerContext context;

    SupervisedHistoryService(HandlerContext context) {
        this.context = context;
    }

    /** Build + push the supervised history summary. Off the IPC thread — the
     *  registry does a one-time lazy disk scan on first read. */
    void list() {
        CompletableFuture.runAsync(() -> {
            try {
                if (context.getProject() == null) return;
                List<SessionManifest> manifests = SessionRegistry.getInstance(context.getProject())
                        .listByKind(SessionKind.SUPERVISED);

                JsonArray sessions = new JsonArray();
                for (SessionManifest m : manifests) {
                    if (m == null || m.containerId == null) continue;
                    JsonObject s = new JsonObject();
                    // Reuse the normal history-summary shape. containerId doubles as
                    // the list key + restore id (S5 routes restore by containerId).
                    s.addProperty("sessionId", m.containerId);
                    s.addProperty("title", summaryTitle(m));
                    s.addProperty("messageCount", 0);
                    s.addProperty("lastTimestamp", m.lastActiveAt);
                    // —— container fields ——
                    s.addProperty("containerId", m.containerId);
                    s.addProperty("kind", "supervised");
                    if (m.agentId != null) s.addProperty("agentId", m.agentId);
                    sessions.add(s);
                }

                JsonObject out = new JsonObject();
                out.addProperty("success", true);
                out.add("sessions", sessions);
                out.addProperty("total", sessions.size());
                pushToWebview("window.onSupervisedHistory", new Gson().toJson(out));
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] load_supervised_history failed: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
                pushToWebview("window.onSupervisedHistory",
                        "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    /** Display title: explicit title, else the supervisor agent id, else the
     *  container id — always non-null (the summary's title field is required). */
    private static String summaryTitle(SessionManifest m) {
        if (m.title != null && !m.title.isEmpty()) return m.title;
        if (m.agentId != null && !m.agentId.isEmpty()) return m.agentId;
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
