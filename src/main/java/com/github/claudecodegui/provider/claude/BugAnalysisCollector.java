package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.common.IBridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stream collector for a headless bug analysis run (design §4/§5).
 *
 * <p>Receives unpacked line strings from {@link IBridge} (NDJSON envelope already stripped
 * by {@code LocalBridge:660} / {@code RemoteBridge:452}). Prefixes drive the state machine:
 * <ul>
 *   <li>{@code [CONTENT_DELTA] "..."} — JSON-encoded string delta; appended to the assistant
 *       buffer (for final JSON extraction) AND forwarded to the live process feed.</li>
 *   <li>{@code [THINKING_DELTA] "..."} — JSON-encoded thinking delta; forwarded to the live
 *       process feed only (NOT part of the final structured result).</li>
 *   <li>{@code [MESSAGE] {...}} — full assistant message JSON; scans {@code content[]} for every
 *       {@code tool_use} block: emits a structured tool card to the live feed (so the webview
 *       renders it exactly like a normal chat transcript), and for {@code query_bug_details} /
 *       sub-agent ({@code Task}/{@code Agent}) spawns also drives progress
 *       ({@link #currentId} / {@link #toolCalls}).</li>
 *   <li>{@code [TOOL_RESULT] {...}} — forwards the tool_result payload (tool_use_id + content +
 *       is_error) to the live feed (attached to the matching tool card), moves
 *       {@link #currentId} into {@link #doneIds}, and pushes progress.</li>
 *   <li>{@code [SEND_ERROR]} — records a send-error flag + message for the fallback error string.</li>
 *   <li>All other prefixes ({@code [STREAM_*]}, {@code [USAGE]}, etc.) — ignored.</li>
 * </ul>
 *
 * <p>The live process feed (thinking + content deltas + structured tool_use/tool_result blocks)
 * is pushed to {@code window.onBugAnalysisStream} so the「AI分析」Tab can show the model's
 * reasoning and tool activity in real time during the「分析中」phase, rendered with the same
 * components as a normal conversation (read-only, no input). It is ephemeral — never persisted.
 *
 * <p>On {@link #onComplete(boolean)}: if the handle is not canceled, calls
 * {@link JsonExtract#fromFenced(String)} on the accumulated text; on success calls
 * {@link BugAnalysisHandlerCallbacks#onResult}, on failure calls
 * {@link BugAnalysisHandlerCallbacks#onParseFallback}.
 *
 * <p>§5.3 兜底: if {@code [MESSAGE]} parsing fails (unexpected format), the state machine
 * degrades gracefully — {@link #toolCalls} still counts every attempted parse and the final
 * result extraction is unaffected.
 */
public final class BugAnalysisCollector implements IBridge.DaemonOutputCallback {

    private static final Logger log = LoggerFactory.getLogger(BugAnalysisCollector.class);
    private static final Gson GSON = new Gson();

    private final AnalysisHandle handle;
    private final int total;
    private final BugAnalysisHandlerCallbacks cb;
    private final String projectId;

    /** Accumulated assistant text built from [CONTENT_DELTA] lines. */
    private final StringBuilder assistant = new StringBuilder();

    /** Bug IDs for which a tool_result has been received (insertion-ordered, stable JSON output). */
    private final Set<String> doneIds = new LinkedHashSet<>();

    /** Bug ID currently being queried (tool_use seen; result not yet received). Null when idle. */
    private String currentId = null;

    /** Total tool_use calls observed (§5.3 fallback counter shown in progress). */
    private int toolCalls = 0;

    /** True if a [SEND_ERROR] line was received. */
    private boolean sendError = false;

    /** Last [SEND_ERROR] raw line, used as error message in the parse-fallback path. */
    private String lastError = null;

    public BugAnalysisCollector(AnalysisHandle handle, JsonArray bugs, BugAnalysisHandlerCallbacks cb) {
        this.handle = handle;
        this.total = bugs != null ? bugs.size() : 0;
        this.cb = cb;
        this.projectId = handle.projectId;
    }

    // ── DaemonOutputCallback ───────────────────────────────────────────

    @Override
    public void onLine(String line) {
        if (handle.canceled) {
            return;
        }
        if (line.startsWith("[CONTENT_DELTA] ")) {
            try {
                String decoded = GSON.fromJson(line.substring(16), String.class);
                if (decoded != null) {
                    assistant.append(decoded);
                    pushStream("content", decoded);
                }
            } catch (Exception e) {
                log.debug("[BugAnalysis] CONTENT_DELTA parse error: {}", e.getMessage());
            }
        } else if (line.startsWith("[THINKING_DELTA] ")) {
            // Live feed only — thinking is NOT part of the final structured result.
            try {
                pushStream("thinking", GSON.fromJson(line.substring(17), String.class));
            } catch (Exception e) {
                log.debug("[BugAnalysis] THINKING_DELTA parse error: {}", e.getMessage());
            }
        } else if (line.startsWith("[MESSAGE] ")) {
            maybeMarkToolUse(line.substring(10));
        } else if (line.startsWith("[TOOL_RESULT]")) {
            pushToolResult(line.substring("[TOOL_RESULT]".length()).trim());
            markToolDone();
        } else if (line.startsWith("[SEND_ERROR]")) {
            sendError = true;
            lastError = line;
        }
        // [STREAM_START/END], [USAGE], etc.: intentionally ignored
    }

    @Override
    public void onStderr(String text) {
        log.warn("[BugAnalysis] daemon stderr: {}", text);
    }

    @Override
    public void onError(String error) {
        cb.onTransportError(error);
    }

    @Override
    public void onComplete(boolean success) {
        if (handle.canceled) {
            return;
        }
        String text = assistant.toString();
        JsonExtract.AnalysisResult result = JsonExtract.fromFenced(text);
        if (result != null) {
            cb.onResult(result);
        } else {
            cb.onParseFallback(text, sendError ? lastError : "无法解析结构化结果");
        }
    }

    // ── §5.1 progress helpers ──────────────────────────────────────────

    /**
     * Parse an assistant [MESSAGE] JSON for a {@code query_bug_details} tool_use block.
     * Sets {@link #currentId} and increments {@link #toolCalls} on success; pushes progress
     * and a tool chip into the live feed. On any parse failure, logs at DEBUG and returns
     * without updating state (§5.3 fallback).
     */
    private void maybeMarkToolUse(String json) {
        try {
            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonArray content = msg.getAsJsonArray("content");
            if (content == null) {
                return;
            }
            // Iterate ALL tool_use blocks (a coordinator turn may emit several Task spawns at once
            // for the concurrency batch — don't stop at the first).
            for (JsonElement el : content) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject block = el.getAsJsonObject();
                JsonElement typeEl = block.get("type");
                if (typeEl == null || !"tool_use".equals(typeEl.getAsString())) {
                    continue;
                }
                JsonElement nameEl = block.get("name");
                if (nameEl == null) {
                    continue;
                }
                String name = nameEl.getAsString();
                // Live process feed: emit a structured tool card for EVERY tool call so the
                // webview renders it exactly like a normal chat transcript (tool_use → tool
                // card + its tool_result output; Task → sub-agent status card).
                pushToolUse(block);
                // Progress tracking (drives the per-bug ✓/⟳/◌ rows + degraded fallback bar):
                // query_bug_details tracks currentId/doneIds; sub-agent spawns count tool calls.
                // Compatible with both "query_bug_details" and "mcp__main__query_bug_details".
                if (name.endsWith("query_bug_details")) {
                    String bugId = null;
                    if (block.has("input") && block.get("input").isJsonObject()) {
                        JsonObject input = block.getAsJsonObject("input");
                        if (input.has("bug_id") && !input.get("bug_id").isJsonNull()) {
                            bugId = input.get("bug_id").getAsString();
                        }
                    }
                    currentId = bugId;
                    toolCalls++;
                    pushProgress();
                } else if (isSubagentTool(name)) {
                    toolCalls++;
                    pushProgress();
                }
            }
        } catch (Exception e) {
            // §5.3: degrade to toolCalls counter — result extraction is unaffected
            log.debug("[BugAnalysis] MESSAGE parse failed (toolCalls={}, fallback ok): {}",
                    toolCalls, e.getMessage());
        }
    }

    /**
     * Mark the current tool call as done: move {@link #currentId} into {@link #doneIds} and push progress.
     */
    private void markToolDone() {
        if (currentId != null) {
            doneIds.add(currentId);
            currentId = null;
        }
        pushProgress();
    }

    /**
     * Push a progress snapshot to {@code window.onBugAnalysisProgress} (design §5.2).
     *
     * <pre>{ projectId, total, doneIds: [...], currentId: "..." | null, toolCalls: N }</pre>
     */
    private void pushProgress() {
        JsonObject p = new JsonObject();
        p.addProperty("projectId", projectId);
        p.addProperty("total", total);
        p.add("doneIds", GSON.toJsonTree(doneIds));
        p.addProperty("currentId", currentId);
        p.addProperty("toolCalls", toolCalls);
        cb.context.callJavaScript("window.onBugAnalysisProgress", cb.context.escapeJs(GSON.toJson(p)));
    }

    /**
     * Forward one live "analysis process" text segment to {@code window.onBugAnalysisStream}.
     *
     * <pre>{ projectId, kind: "thinking" | "content", text }</pre>
     *
     * The webview merges same-kind deltas into one block (thinking block / Markdown body).
     * Ephemeral — never persisted; only shown during the「分析中」phase.
     */
    private void pushStream(String kind, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject s = new JsonObject();
        s.addProperty("projectId", projectId);
        s.addProperty("kind", kind);
        s.addProperty("text", text);
        cb.context.callJavaScript("window.onBugAnalysisStream", cb.context.escapeJs(GSON.toJson(s)));
    }

    /**
     * Forward a structured tool_use block to the live feed so the webview renders it as a
     * chat-style tool card (identical to a normal conversation): query_bug_details → generic
     * tool card with its output, Task/Agent → sub-agent status card.
     *
     * <pre>{ projectId, kind: "tool_use", id, name, input }</pre>
     */
    private void pushToolUse(JsonObject block) {
        try {
            JsonObject s = new JsonObject();
            s.addProperty("projectId", projectId);
            s.addProperty("kind", "tool_use");
            JsonElement idEl = block.get("id");
            s.addProperty("id", (idEl != null && !idEl.isJsonNull()) ? idEl.getAsString() : "");
            JsonElement nameEl = block.get("name");
            s.addProperty("name", (nameEl != null && !nameEl.isJsonNull()) ? nameEl.getAsString() : "");
            if (block.has("input") && block.get("input").isJsonObject()) {
                s.add("input", block.getAsJsonObject("input"));
            } else {
                s.add("input", new JsonObject());
            }
            cb.context.callJavaScript("window.onBugAnalysisStream", cb.context.escapeJs(GSON.toJson(s)));
        } catch (Exception e) {
            log.debug("[BugAnalysis] pushToolUse failed: {}", e.getMessage());
        }
    }

    /**
     * Forward a tool_result payload (the raw {@code [TOOL_RESULT]} JSON) to the live feed so the
     * webview attaches the output to the matching tool card (keyed by tool_use_id).
     *
     * <pre>{ projectId, kind: "tool_result", id, content, is_error }</pre>
     */
    private void pushToolResult(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            JsonObject block = JsonParser.parseString(payload).getAsJsonObject();
            JsonObject s = new JsonObject();
            s.addProperty("projectId", projectId);
            s.addProperty("kind", "tool_result");
            JsonElement idEl = block.get("tool_use_id");
            s.addProperty("id", (idEl != null && !idEl.isJsonNull()) ? idEl.getAsString() : "");
            if (block.has("content") && !block.get("content").isJsonNull()) {
                s.add("content", block.get("content"));
            }
            JsonElement errEl = block.get("is_error");
            if (errEl != null && !errEl.isJsonNull() && errEl.isJsonPrimitive()) {
                s.addProperty("is_error", errEl.getAsBoolean());
            }
            cb.context.callJavaScript("window.onBugAnalysisStream", cb.context.escapeJs(GSON.toJson(s)));
        } catch (Exception e) {
            log.debug("[BugAnalysis] pushToolResult parse failed: {}", e.getMessage());
        }
    }

    /** The built-in sub-agent spawn tool (see permission-mode.js: "Agent"/"Task"). */
    private static boolean isSubagentTool(String name) {
        return "Task".equals(name) || "Agent".equals(name)
                || name.endsWith("__Task") || name.endsWith("__Agent");
    }
}
