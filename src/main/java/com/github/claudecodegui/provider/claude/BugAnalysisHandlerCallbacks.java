package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Terminal result / transport callbacks for a single headless bug analysis run (design §5/§6).
 *
 * <p>Holds the {@link HandlerContext} needed to push JavaScript events, plus per-run
 * metadata ({@code projectId}, {@code model}, {@code reasoning}).  The {@code bugs}
 * snapshot is kept for structural completeness but not used in the push payloads.
 *
 * <p>{@link BugAnalysisCollector} accesses {@link #context} directly (same package) to
 * push progress events; only terminal results go through the methods below.
 */
public final class BugAnalysisHandlerCallbacks {

    /** Package-visible so {@link BugAnalysisCollector} can call {@code context.callJavaScript}. */
    final HandlerContext context;

    private final String projectId;
    private final String model;
    private final String reasoning;

    private static final Gson GSON = new Gson();

    /**
     * @param context    handler context for JavaScript callbacks
     * @param projectId  cloud project id (used as key in webview state)
     * @param bugs       bug snapshot submitted by webview (kept for completeness)
     * @param model      model id passed to scratch session (threaded through to §6.2 result)
     * @param reasoning  reasoningEffort value (threaded through to §6.2 result)
     */
    public BugAnalysisHandlerCallbacks(HandlerContext context, String projectId,
                                       JsonArray bugs, String model, String reasoning) {
        this.context = context;
        this.projectId = projectId;
        this.model = model != null ? model : "";
        this.reasoning = reasoning != null ? reasoning : "";
    }

    /**
     * Push a successful analysis result (design §6.2 success schema).
     *
     * <pre>{ "ok": true, "projectId": "...", "model": "...", "reasoning": "...",
     *   "result": { "bugs":[...], "groups":[...] } }</pre>
     */
    public void onResult(JsonExtract.AnalysisResult result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("projectId", projectId);
        payload.addProperty("model", model);
        payload.addProperty("reasoning", reasoning);
        payload.add("result", result.toJson());
        push("window.onBugAnalysisResult", GSON.toJson(payload));
    }

    /**
     * Push a parse-fallback result when JSON extraction fails (design §6.2 failure schema).
     *
     * <pre>{ "ok": false, "projectId": "...", "raw": "<assistant text>", "error": "..." }</pre>
     */
    public void onParseFallback(String raw, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("projectId", projectId);
        payload.addProperty("raw", raw != null ? raw : "");
        payload.addProperty("error", error != null ? error : "无法解析结构化结果");
        push("window.onBugAnalysisResult", GSON.toJson(payload));
    }

    /**
     * Push a transport-level error (design §4 onError branch).
     */
    public void onTransportError(String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("projectId", projectId);
        payload.addProperty("raw", "");
        payload.addProperty("error", error != null ? error : "传输错误");
        push("window.onBugAnalysisResult", GSON.toJson(payload));
    }

    private void push(String fn, String json) {
        context.callJavaScript(fn, context.escapeJs(json));
    }
}
