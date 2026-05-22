package com.github.claudecodegui.bridge;

import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.common.IBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-side facade for the daemon's {@code supervisor.*} methods.
 *
 * <p>Each Pair owns one instance. Internally talks to the shared
 * {@link ClaudeSDKBridge#sendDaemonCommand} entry point, so the Supervisor
 * channel coexists with the main Claude channel on the same daemon process.
 */
public class SupervisorBridge {

    private static final Logger LOG = Logger.getInstance(SupervisorBridge.class);

    public static final String ACTION_LINE_PREFIX = "[SUPERVISOR_ACTION]";

    private final ClaudeSDKBridge sdkBridge;
    private final String pairId;
    private final String supervisorId;

    public SupervisorBridge(ClaudeSDKBridge sdkBridge, String pairId, String supervisorId) {
        this.sdkBridge = sdkBridge;
        this.pairId = pairId;
        this.supervisorId = supervisorId;
    }

    public String getPairId() { return pairId; }
    public String getSupervisorId() { return supervisorId; }

    /**
     * Start the supervisor session on the daemon. Returns a future that
     * completes once the daemon acks {@code done}.
     */
    public CompletableFuture<Boolean> start(
            String agentName,
            String description,
            String planContent,
            String specContent,
            String model
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        params.addProperty("name", agentName);
        params.addProperty("description", description);
        params.addProperty("planContent", planContent);
        if (specContent != null && !specContent.isEmpty()) {
            params.addProperty("specContent", specContent);
        }
        if (model != null && !model.isEmpty()) {
            params.addProperty("model", model);
        }
        return sdkBridge.sendDaemonCommand("supervisor.start", params, sinkCallback("start"));
    }

    /**
     * Forward an event to the supervisor and asynchronously receive its next
     * {@code ACTION}. The future resolves to the parsed action JSON (or null
     * if the daemon returned no action line, e.g. on transport failure).
     */
    public CompletableFuture<JsonObject> postEvent(JsonObject event) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        params.add("event", event);

        AtomicReference<JsonObject> captured = new AtomicReference<>();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();

        CompletableFuture<Boolean> sendFuture = sdkBridge.sendDaemonCommand(
                "supervisor.postEvent",
                params,
                new IBridge.DaemonOutputCallback() {
                    @Override
                    public void onLine(String line) {
                        // Lines come pre-stripped of the NDJSON envelope by the daemon;
                        // they may include the request id wrapper. We accept either
                        // "[SUPERVISOR_ACTION] {json}" or a raw JSON object.
                        if (line == null) return;
                        String trimmed = line.trim();
                        int idx = trimmed.indexOf(ACTION_LINE_PREFIX);
                        if (idx >= 0) {
                            String jsonText = trimmed.substring(idx + ACTION_LINE_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                captured.set(parsed);
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse ACTION line: " + e.getMessage()
                                        + " | line=" + trimmed);
                            }
                        }
                    }

                    @Override
                    public void onStderr(String text) {
                        if (text != null && !text.isBlank()) {
                            LOG.debug("[SupervisorBridge:stderr] " + text);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        LOG.warn("[SupervisorBridge] Daemon error: " + error);
                    }

                    @Override
                    public void onComplete(boolean success) {
                        if (success) {
                            result.complete(captured.get());
                        } else {
                            result.completeExceptionally(new RuntimeException(
                                    "supervisor.postEvent did not complete successfully"));
                        }
                    }
                }
        );

        // Propagate transport-level failure (e.g. daemon unavailable) to the result.
        sendFuture.whenComplete((ok, err) -> {
            if (err != null && !result.isDone()) {
                result.completeExceptionally(err);
            }
        });

        return result;
    }

    /**
     * Stop the supervisor session on the daemon. Idempotent.
     */
    public CompletableFuture<Boolean> stop() {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        return sdkBridge.sendDaemonCommand("supervisor.stop", params, sinkCallback("stop"));
    }

    private IBridge.DaemonOutputCallback sinkCallback(String op) {
        return new IBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                LOG.debug("[SupervisorBridge:" + op + "] " + line);
            }
            @Override
            public void onStderr(String text) {
                if (text != null && !text.isBlank()) {
                    LOG.debug("[SupervisorBridge:" + op + ":stderr] " + text);
                }
            }
            @Override
            public void onError(String error) {
                LOG.warn("[SupervisorBridge:" + op + "] " + error);
            }
            @Override
            public void onComplete(boolean success) {
                LOG.debug("[SupervisorBridge:" + op + "] complete success=" + success);
            }
        };
    }
}
