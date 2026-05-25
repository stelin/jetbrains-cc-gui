package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.common.IBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 6b (2026-05-24): Java-side facade for the daemon's {@code mainAi.*}
 * commands. Mirrors the {@code SupervisorBridge} pattern but targets the
 * persistent main-AI runtime registered by session id.
 *
 * <p>Each pair owns one instance. Internally talks to the shared
 * {@link ClaudeSDKBridge#sendDaemonCommand} entry point, so the main-AI
 * rotation pipeline coexists with normal {@code sendMessage} traffic on
 * the same daemon process without a parallel transport.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@link #produceHandoff(String, String)} — enqueue a producer prompt
 *       into the main-AI runtime tied to {@code sessionId}, return the
 *       parsed {@code [HANDOFF_DOC]} envelope.</li>
 *   <li>{@link #getContextUsage(String)} — fetch the SDK's real
 *       per-category context-window usage for the same runtime.</li>
 * </ul>
 *
 * <p>Compact-boundary observation does NOT go through this class — it is
 * reported via SDK system messages on the {@code sendMessage} response
 * stream, which {@code ClaudeMessageHandler.handleSystemMessage} inspects
 * and forwards to {@code MainAIMonitor.onCompactBoundary} directly. That
 * sidesteps adding a long-lived listener on the bridge.
 */
public class MainAIBridge {

    private static final Logger LOG = Logger.getInstance(MainAIBridge.class);

    /** Phase 6b response prefix for {@link #produceHandoff}. */
    public static final String HANDOFF_DOC_PREFIX = "[HANDOFF_DOC]";
    /** Phase 6b response prefix for {@link #getContextUsage}. */
    public static final String CONTEXT_USAGE_PREFIX = "[CONTEXT_USAGE]";

    private final ClaudeSDKBridge sdkBridge;

    public MainAIBridge(ClaudeSDKBridge sdkBridge) {
        this.sdkBridge = sdkBridge;
    }

    /**
     * Ask the main-AI runtime tied to {@code sessionId} to emit a structured
     * handoff document, given the supplied producer prompt. The future
     * resolves to the parsed envelope ({@code {sessionId, valid, json, raw, ...}}),
     * or completes exceptionally on transport / lookup failure.
     */
    public CompletableFuture<JsonObject> produceHandoff(String sessionId, String prompt) {
        if (sessionId == null || sessionId.isEmpty()) {
            return failed("mainAi.produceHandoff requires sessionId");
        }
        if (prompt == null || prompt.isEmpty()) {
            return failed("mainAi.produceHandoff requires non-empty prompt");
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.addProperty("prompt", prompt);
        return singleLineRequest("mainAi.produceHandoff", HANDOFF_DOC_PREFIX, "produceHandoff", params);
    }

    /**
     * Phase 6b: cheap (~100ms-1s) SDK round-trip into the active runtime.
     * Returns the {@code [CONTEXT_USAGE]} envelope ({@code {sessionId, ratio,
     * totalUsed, contextLimit, breakdown}}), or null if the line never landed.
     */
    public CompletableFuture<JsonObject> getContextUsage(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return failed("mainAi.getContextUsage requires sessionId");
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        return singleLineRequest("mainAi.getContextUsage", CONTEXT_USAGE_PREFIX, "getContextUsage", params);
    }

    // ── internals ────────────────────────────────────────────────────────

    private static CompletableFuture<JsonObject> failed(String message) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalArgumentException(message));
        return f;
    }

    private CompletableFuture<JsonObject> singleLineRequest(String method, String linePrefix, String opTag, JsonObject params) {
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        AtomicReference<String> capturedError = new AtomicReference<>();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();

        CompletableFuture<Boolean> sendFuture = sdkBridge.sendDaemonCommand(
                method, params,
                new IBridge.DaemonOutputCallback() {
                    @Override public void onLine(String line) {
                        if (line == null) return;
                        String trimmed = line.trim();
                        int idx = trimmed.indexOf(linePrefix);
                        if (idx < 0) return;
                        String jsonText = trimmed.substring(idx + linePrefix.length()).trim();
                        try {
                            captured.set(JsonParser.parseString(jsonText).getAsJsonObject());
                        } catch (Exception e) {
                            LOG.warn("[MainAIBridge:" + opTag + "] parse " + linePrefix
                                    + " failed: " + e.getMessage() + " | line=" + trimmed);
                        }
                    }
                    @Override public void onStderr(String text) {
                        if (text != null && !text.isBlank()) {
                            LOG.debug("[MainAIBridge:" + opTag + ":stderr] " + text);
                        }
                    }
                    @Override public void onError(String error) {
                        LOG.warn("[MainAIBridge:" + opTag + "] daemon error: " + error);
                        if (error != null) capturedError.set(error);
                    }
                    @Override public void onComplete(boolean success) {
                        if (success) {
                            result.complete(captured.get());
                        } else {
                            String msg = capturedError.get();
                            result.completeExceptionally(new RuntimeException(
                                    msg != null
                                        ? method + " failed: " + msg
                                        : method + " did not complete successfully"));
                        }
                    }
                });
        sendFuture.whenComplete((ok, err) -> {
            if (err != null && !result.isDone()) {
                result.completeExceptionally(err);
            }
        });
        return result;
    }
}
