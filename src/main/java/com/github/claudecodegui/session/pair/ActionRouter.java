package com.github.claudecodegui.session.pair;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

// Project is currently retained for forward-compat (notifications, etc.); see field comment.

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Translates Supervisor {@code ACTION} payloads into effects:
 *   - {@code inject_prompt}     → fire {@code onInjectPrompt} (webview routes as fake-user input)
 *   - {@code retry_with_hint}   → schedule a delayed inject_prompt
 *   - {@code escalate_to_human} → fire {@code onEscalate} (webview opens decision dialog)
 *   - {@code approve_and_continue} → bump progress; emit a passive note
 *   - {@code request_amendment} → record in progress.json + escalate to user
 *   - {@code wait}              → no-op
 *
 * <p>The router itself is free of webview/UI dependencies. Effects are delivered
 * via {@link WebviewBridge} callbacks injected by the caller (Phase B IPC handler).
 * Each lambda is invoked off the EDT — the bridge is responsible for thread hops.
 */
public class ActionRouter {

    private static final Logger LOG = Logger.getInstance(ActionRouter.class);

    /**
     * Adapter interface — concrete impl lives next to the IPC handler that
     * owns the JBCefBrowser, and translates events into webview JS calls.
     */
    public interface WebviewBridge {
        /** Push a Supervisor action card to the right pane. */
        void onActionEvent(JsonObject event);

        /** Inject a fake-user prompt into the main session (Supervisor-attributed). */
        void onInjectPrompt(String pairId, String supervisorId, String prompt);

        /** Open the escalation decision dialog. */
        void onEscalate(JsonObject payload);

        /**
         * Toggle the "Supervisor is thinking" indicator in the right pane.
         * Called true when an event has been forwarded to the daemon and we
         * are awaiting an ACTION; called false once any ACTION arrives.
         */
        default void onThinking(String supervisorId, boolean thinking) { /* optional */ }
    }

    @SuppressWarnings("unused") // reserved for future Project-scoped effects (notifications, file watchers)
    private final Project project;
    private final PairSession pair;
    private final ScheduledExecutorService scheduler;
    private volatile WebviewBridge webview = NoopWebviewBridge.INSTANCE;

    public ActionRouter(Project project, PairSession pair) {
        this.project = project;
        this.pair = pair;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "supervisor-actionrouter-" + pair.getPairId());
            t.setDaemon(true);
            return t;
        });
    }

    public void setWebviewBridge(WebviewBridge bridge) {
        this.webview = bridge == null ? NoopWebviewBridge.INSTANCE : bridge;
    }

    /**
     * Notify the right pane that we're awaiting Supervisor output. The bridge
     * relays this to {@code window.onPairThinking}.
     */
    public void signalThinking(boolean thinking) {
        webview.onThinking(pair.getAgentId(), thinking);
    }

    /**
     * Dispatch an action wrapper as emitted by the daemon
     * ({@code [SUPERVISOR_ACTION] {...}} line, already parsed).
     *
     * Shape: { pairId, supervisorId, naturalText, action: {action, reason, payload}, ... }
     */
    public void dispatch(JsonObject actionWrapper) {
        if (actionWrapper == null) return;

        // Any ACTION arrival means thinking ended.
        webview.onThinking(pair.getAgentId(), false);

        // First, surface the natural-language + action card to the right pane.
        webview.onActionEvent(actionWrapper);

        JsonObject action = actionWrapper.has("action") && actionWrapper.get("action").isJsonObject()
                ? actionWrapper.getAsJsonObject("action") : null;
        if (action == null) return;

        String type = action.has("action") ? action.get("action").getAsString() : "wait";
        JsonObject payload = action.has("payload") && action.get("payload").isJsonObject()
                ? action.getAsJsonObject("payload") : new JsonObject();

        switch (type) {
            case "inject_prompt":
                handleInjectPrompt(payload, /*delaySec*/ 0);
                break;
            case "retry_with_hint":
                int wait = payload.has("wait_seconds") && !payload.get("wait_seconds").isJsonNull()
                        ? payload.get("wait_seconds").getAsInt() : 0;
                pair.getProgressManager().incrementCounter("auto_recover_count");
                handleInjectPrompt(payload, Math.max(0, wait));
                break;
            case "approve_and_continue":
                if (payload.has("mark_step_complete") && !payload.get("mark_step_complete").isJsonNull()) {
                    int step = payload.get("mark_step_complete").getAsInt();
                    pair.getProgressManager().markStepStatus(step, "done");
                }
                break;
            case "escalate_to_human":
                pair.getProgressManager().incrementCounter("escalate_count");
                webview.onEscalate(action);
                break;
            case "request_amendment":
                // Strict-plan policy: amendments are NOT auto-applied. Escalate the
                // request to the user as a special-flavored escalation.
                pair.getProgressManager().incrementCounter("escalate_count");
                JsonObject wrapped = new JsonObject();
                wrapped.addProperty("kind", "amendment_request");
                if (action.has("reason")) wrapped.add("reason", action.get("reason"));
                if (payload.has("proposal")) wrapped.add("proposal", payload.get("proposal"));
                webview.onEscalate(wrapped);
                break;
            case "wait":
            default:
                // intentionally no-op
                break;
        }
    }

    private void handleInjectPrompt(JsonObject payload, long delaySeconds) {
        String prompt = payload.has("prompt") ? payload.get("prompt").getAsString() : "";
        if (prompt.isEmpty()) {
            LOG.warn("[ActionRouter] inject_prompt with empty payload.prompt — skipping");
            return;
        }
        if (delaySeconds <= 0) {
            webview.onInjectPrompt(pair.getPairId(), pair.getAgentId(), prompt);
        } else {
            scheduler.schedule(
                    () -> {
                        if (pair.isDisposed()) return;
                        webview.onInjectPrompt(pair.getPairId(), pair.getAgentId(), prompt);
                    },
                    delaySeconds,
                    TimeUnit.SECONDS
            );
        }
    }

    /** Stub bridge for testing / pre-wiring. */
    private static final class NoopWebviewBridge implements WebviewBridge {
        static final NoopWebviewBridge INSTANCE = new NoopWebviewBridge();
        @Override public void onActionEvent(JsonObject event) { }
        @Override public void onInjectPrompt(String pairId, String supervisorId, String prompt) { }
        @Override public void onEscalate(JsonObject payload) { }
    }
}
