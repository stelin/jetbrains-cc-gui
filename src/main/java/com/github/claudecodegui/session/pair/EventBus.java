package com.github.claudecodegui.session.pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Forwards filtered main-AI events to the Supervisor session and dispatches
 * the resulting ACTION to the {@link ActionRouter}.
 *
 * <p>Decoupling note: the event bus owns no state about how main-AI events
 * are produced. Production code (e.g. {@code ClaudeMessageHandler#onComplete})
 * calls {@link #publish(JsonObject)} with a structured event object. Tests and
 * Phase A wiring can also call these publish methods directly.
 *
 * <p>All event forwarding happens on a single-thread executor so multiple
 * sources never interleave a Supervisor turn.
 */
public class EventBus {

    private static final Logger LOG = Logger.getInstance(EventBus.class);

    private final PairSession pair;
    private final ActionRouter router;
    private final Executor dispatcher;

    public EventBus(PairSession pair, ActionRouter router) {
        this.pair = pair;
        this.router = router;
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "supervisor-eventbus-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Publish a structured event. Filtered, summarized, sent to the
     * Supervisor; the resulting ACTION is routed via {@link ActionRouter}.
     *
     * <p>The returned future completes once the ACTION has been dispatched
     * (or skipped). Callers usually do not await — fire-and-forget is fine.
     */
    public CompletableFuture<Void> publish(JsonObject event) {
        if (pair.isDisposed()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!EventFilter.shouldForward(event)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> event, dispatcher)
                .thenCompose(this::forward)
                .exceptionally(err -> {
                    LOG.warn("[EventBus] forwarding failed: " + err.getMessage());
                    return null;
                });
    }

    /** Convenience: turn_end with tool use list. */
    public CompletableFuture<Void> publishTurnEnd(int stepIndex, String stepTitle,
                                                  List<ToolUseRecord> toolUses,
                                                  List<String> modifiedInPlan,
                                                  List<String> modifiedOffPlan,
                                                  long durationMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (stepTitle != null) payload.addProperty("stepTitle", stepTitle);
        if (toolUses != null) {
            JsonArray arr = new JsonArray();
            for (ToolUseRecord t : toolUses) arr.add(t.toJson());
            payload.add("toolUses", arr);
        }
        if (modifiedInPlan != null) payload.add("modifiedFilesInPlan", strArray(modifiedInPlan));
        if (modifiedOffPlan != null) payload.add("modifiedFilesOffPlan", strArray(modifiedOffPlan));
        payload.addProperty("durationMs", durationMs);

        return publish(makeEvent("turn_end", payload));
    }

    public CompletableFuture<Void> publishError(int stepIndex, String code, Integer status,
                                                Integer retryAfter, String message, int retryCount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (code != null) payload.addProperty("code", code);
        if (status != null) payload.addProperty("status", status);
        if (retryAfter != null) payload.addProperty("retryAfter", retryAfter);
        if (message != null) payload.addProperty("message", message);
        payload.addProperty("retryCount", retryCount);
        return publish(makeEvent("error", payload));
    }

    public CompletableFuture<Void> publishOffPlan(int stepIndex, List<String> files) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (files != null) payload.add("files", strArray(files));
        return publish(makeEvent("off_plan_detected", payload));
    }

    public CompletableFuture<Void> publishStart(int currentStep, int totalSteps, String stepTitle) {
        JsonObject payload = new JsonObject();
        payload.addProperty("currentStep", currentStep);
        payload.addProperty("totalSteps", totalSteps);
        if (stepTitle != null) payload.addProperty("currentStepTitle", stepTitle);
        return publish(makeEvent("start", payload));
    }

    public CompletableFuture<Void> publishVerifyResult(int stepIndex, String command,
                                                       boolean pass, String stderr, int attempt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("step", stepIndex);
        if (command != null) payload.addProperty("command", command);
        payload.addProperty("pass", pass);
        if (stderr != null) payload.addProperty("stderr", stderr);
        payload.addProperty("attempt", attempt);
        return publish(makeEvent("verify_result", payload));
    }

    public CompletableFuture<Void> publishHumanResponse(String choice, String note) {
        JsonObject payload = new JsonObject();
        if (choice != null) payload.addProperty("choice", choice);
        if (note != null) payload.addProperty("note", note);
        return publish(makeEvent("human_response", payload));
    }

    /**
     * Free-form user message addressed to the Supervisor (NOT to the main AI).
     * Sent when the user types into the right-pane composer.
     */
    public CompletableFuture<Void> publishUserInput(String text) {
        JsonObject payload = new JsonObject();
        if (text != null) payload.addProperty("text", text);
        return publish(makeEvent("user_input", payload));
    }

    // ====================== internal ======================

    private CompletableFuture<Void> forward(JsonObject event) {
        // Tell the right pane "Supervisor is thinking" before we block waiting
        // for daemon → SDK round-trip. ActionRouter.dispatch will clear it.
        try { router.signalThinking(true); } catch (Exception ignored) { /* best-effort */ }
        try {
            JsonObject actionWrapper = pair.getSupervisorBridge().postEvent(event)
                    .exceptionally(err -> {
                        LOG.warn("[EventBus] supervisor postEvent failed: " + err.getMessage());
                        return null;
                    })
                    .join();
            if (actionWrapper != null) {
                router.dispatch(actionWrapper);
            } else {
                // Failed silently — make sure thinking indicator clears.
                try { router.signalThinking(false); } catch (Exception ignored) { /* best-effort */ }
            }
        } catch (Exception e) {
            LOG.warn("[EventBus] forward exception: " + e.getMessage());
            try { router.signalThinking(false); } catch (Exception ignored) { /* best-effort */ }
        }
        return CompletableFuture.completedFuture(null);
    }

    private JsonObject makeEvent(String type, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        if (payload == null) payload = new JsonObject();
        payload.addProperty("timestamp", System.currentTimeMillis());
        long elapsedSec = (System.currentTimeMillis() - pair.getStartedAt()) / 1000;
        payload.addProperty("elapsedSeconds", elapsedSec);
        root.add("payload", payload);
        return root;
    }

    private static JsonArray strArray(List<String> items) {
        JsonArray arr = new JsonArray();
        for (String s : items) arr.add(s);
        return arr;
    }

    /**
     * Tool-use record for turn_end summaries.
     */
    public static final class ToolUseRecord {
        public final String tool;
        public final String path;
        public final boolean ok;
        public ToolUseRecord(String tool, String path, boolean ok) {
            this.tool = tool; this.path = path; this.ok = ok;
        }
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("tool", tool);
            if (path != null) o.addProperty("path", path);
            o.addProperty("ok", ok);
            return o;
        }
    }
}
