package com.github.claudecodegui.session.pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

// Project is currently retained for forward-compat (notifications, etc.); see field comment.

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        /** Inject a fake-user prompt into the main session (Supervisor-attributed).
         *  Legacy 3-arg signature. */
        void onInjectPrompt(String pairId, String supervisorId, String prompt);

        /** Protocol v2 (2026-05-24): same as {@link #onInjectPrompt} but with a
         *  {@code directiveId} so the webview can later post a matching
         *  {@code pair_directive_ack} IPC. Default delegates to the 3-arg form
         *  for back-compat with bridges that don't override (the directive will
         *  then time out client-side, surfacing as {@code directive_lost}). */
        default void onInjectPromptV2(String pairId, String supervisorId,
                                      String directiveId, String prompt) {
            onInjectPrompt(pairId, supervisorId, prompt);
        }

        /** Open the escalation decision dialog. */
        void onEscalate(JsonObject payload);

        /**
         * Toggle the "Supervisor is thinking" indicator in the right pane.
         * Called true when an event has been forwarded to the daemon and we
         * are awaiting an ACTION; called false once any ACTION arrives.
         */
        default void onThinking(String supervisorId, boolean thinking) { /* optional */ }

        /**
         * Phase 2 (2026-05-24): push a {@link PairStatusSnapshot} JSON
         * payload to the right-pane status panel. Throttled upstream by
         * {@link PairStatusPusher} so impl can do a direct webview call
         * without further coalescing.
         */
        default void onPairStatusUpdate(JsonObject snapshot) { /* optional */ }

        /**
         * Protocol v2 (2026-05-24): non-blocking alert from {@code record_alert}.
         * Replaces the modal {@code onEscalate} for autonomy-mode C1/C2 decisions
         * — supervisor records what it decided to do and continues, the UI shows
         * a toast/notification the user can review asynchronously.
         */
        default void onPairAlert(JsonObject payload) { /* optional */ }

        /**
         * Periodic system notice (e.g. supervisor health-check heartbeat) that
         * should NOT enter the supervisor chat or interrupt in-flight thinking.
         * Rendered on the right pane's PeriodicNoticeStrip — a separate history
         * row that sits between the autonomy controls and the decision timeline.
         * Payload: {@code { ts, kind, message, details? }}.
         */
        default void onPairNotice(JsonObject payload) { /* optional */ }
    }

    @SuppressWarnings("unused") // reserved for future Project-scoped effects (notifications, file watchers)
    private final Project project;
    private final PairSession pair;
    private final ScheduledExecutorService scheduler;
    private volatile WebviewBridge webview = NoopWebviewBridge.INSTANCE;

    /** Max number of received-ack retry attempts before falling through to the
     *  slow applied-timeout (DirectiveTracker.DEFAULT_ACK_TIMEOUT_MS). */
    private static final int MAX_RECEIVED_RETRIES = 3;
    /** Per-directive retry counter. Entries are removed in markDirectiveAcked
     *  (when status is "received") and on dispose. */
    private final ConcurrentHashMap<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    /**
     * 2026-05-25: webview readiness gate. Flips to true the first time
     * {@link #markWebviewReady} fires (driven by the {@code pair_webview_ready}
     * IPC the PairProvider posts on mount). Until then, inject pushes are
     * buffered in {@link #pendingInjectsBeforeReady} so a cold-start dispatch
     * that races the React tree mount isn't lost. Drained synchronously
     * inside {@link #markWebviewReady}.
     */
    private volatile boolean webviewReady = false;
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingInject> pendingInjectsBeforeReady
            = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Cap so a permanently-unready webview can't bloat memory. */
    private static final int MAX_PENDING_INJECTS_BEFORE_READY = 64;

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
     * Phase 2: expose the current bridge so siblings (e.g. {@link PairStatusPusher})
     * can push their own message types without each holding their own copy.
     * Returns the no-op stub if no bridge has been wired.
     */
    public WebviewBridge getWebview() {
        return webview;
    }

    /**
     * Notify the right pane that we're awaiting Supervisor output. The bridge
     * relays this to {@code window.onPairThinking}.
     */
    public void signalThinking(boolean thinking) {
        webview.onThinking(pair.getAgentId(), thinking);
    }

    /**
     * Push a periodic-event notice to the right-pane strip without touching
     * the supervisor pipeline. Used by {@link SupervisorMonitor} on idle
     * health-check ticks so the user sees a heartbeat without the supervisor
     * being forced to produce a no-op turn.
     */
    public void pushNotice(JsonObject notice) {
        if (notice == null) return;
        try {
            webview.onPairNotice(notice);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onPairNotice failed: " + e.getMessage());
        }
    }

    /**
     * Surface a transport / lifecycle failure as a visible bubble in the right
     * pane. We piggy-back on the regular action-event channel so the user sees
     * the same message styling — there's no point inventing a second renderer
     * for a rare path. The action is downgraded to {@code wait} so no effect
     * fires; the message text carries the explanation.
     */
    public void dispatchTransportError(String message) {
        webview.onThinking(pair.getAgentId(), false);

        JsonObject action = new JsonObject();
        action.addProperty("action", "wait");
        action.addProperty("reason", "transport_error");
        action.add("payload", new JsonObject());

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("pairId", pair.getPairId());
        wrapper.addProperty("supervisorId", pair.getAgentId());
        wrapper.addProperty("naturalText", "⚠️ " + (message != null ? message : "未知错误"));
        wrapper.addProperty("reasoningText", "");
        wrapper.add("action", action);
        wrapper.addProperty("parseError", "transport_error");
        wrapper.addProperty("rawText", message != null ? message : "");

        try { webview.onActionEvent(wrapper); } catch (Exception e) {
            LOG.warn("[ActionRouter] dispatchTransportError webview push failed: " + e.getMessage());
        }
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

        // v4 unified pipeline: tool_use cards, compaction notices, and usage
        // snapshots all flow live via [SUPERVISOR_MSG] (handled by PairHandler)
        // — no per-wrapper side-channel dispatch needed. Usage in particular
        // is now broadcast through UsagePushService.broadcast on every raw SDK
        // message, so the TokenIndicator updates mid-turn rather than only at
        // the closing [SUPERVISOR_ACTION].

        // First, surface the natural-language + action card to the right pane.
        webview.onActionEvent(actionWrapper);

        JsonObject action = actionWrapper.has("action") && actionWrapper.get("action").isJsonObject()
                ? actionWrapper.getAsJsonObject("action") : null;
        if (action == null) return;

        String type = action.has("action") ? action.get("action").getAsString() : "wait";
        JsonObject payload = action.has("payload") && action.get("payload").isJsonObject()
                ? action.getAsJsonObject("payload") : new JsonObject();

        // v3: a `decisions[]` array may ride along ANY action type. Process it
        // before the action switch so counters and webview cards are updated
        // even if the action itself is a no-op (e.g. wait).
        dispatchDecisions(payload);

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
                    // Protocol v2 (2026-05-24): bump budget step counter so cost
                    // tracking reflects forward progress. Also useful for the
                    // completion report's stats section.
                    PairBudgetTracker bt = pair.getBudgetTracker();
                    int stepsCompleted = 0;
                    if (bt != null) {
                        bt.incrementSteps();
                        stepsCompleted = bt.getStepsCompleted();
                    }
                    // Phase 6 (2026-05-24): F2 — step advanced cleanly, reset the
                    // consecutive-directive-failure streak.
                    pair.resetDirectiveFailures();
                    // Phase 6 (2026-05-24): T1 — periodic RE-PLAN nudge. Every
                    // 5 completed steps, hint the supervisor to self-evaluate
                    // its plan (it may call save_plan(source="replan")).
                    if (stepsCompleted > 0 && stepsCompleted % 5 == 0
                            && pair.getEventBus() != null) {
                        try {
                            pair.getEventBus().publishReplanDue("periodic", stepsCompleted);
                        } catch (Exception e) {
                            LOG.debug("[ActionRouter] periodic replan publish failed: " + e.getMessage());
                        }
                    }
                }
                break;
            case "escalate_to_human":
                // Protocol v2 + Phase 5 (2026-05-24): autonomy-aware dispatch.
                // - strict mode  → modal escalate (legacy behaviour, even if daemon aliased)
                // - mixed/full   → toast alert (daemon already aliased it; we trust the marker)
                // - daemon-pre-alias (no legacyEscalate flag): modal in strict, toast otherwise
                {
                    String mode = pair.getAutonomyMode();
                    boolean strict = "strict".equals(mode);
                    boolean legacyAliased = payload.has("legacyEscalate")
                            && !payload.get("legacyEscalate").isJsonNull()
                            && payload.get("legacyEscalate").getAsBoolean();
                    if (strict) {
                        pair.getProgressManager().incrementCounter("escalate_count");
                        JsonObject snapshot = pair.getProgressManager().snapshot();
                        if (snapshot.has("stats")) action.add("stats", snapshot.get("stats"));
                        if (snapshot.has("steps")) action.add("steps", snapshot.get("steps"));
                        webview.onEscalate(action);
                    } else if (legacyAliased) {
                        dispatchAlertFromAction(action, payload);
                    } else {
                        // mixed/full + daemon didn't alias — synthesise C2 alert
                        if (!payload.has("category")) payload.addProperty("category", "C2");
                        if (!payload.has("severity")) payload.addProperty("severity", "alert");
                        dispatchAlertFromAction(action, payload);
                    }
                }
                break;
            case "record_alert":
                // Protocol v2 (2026-05-24): non-blocking alert. Supervisor decided
                // a C1/C2 fallback; UI shows a toast, supervisor continues.
                // In strict mode we'd ALSO show a modal, but record_alert is by
                // design non-blocking — leave it as toast even in strict.
                dispatchAlertFromAction(action, payload);
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

    /**
     * Protocol v2 (2026-05-24): non-blocking alert dispatch (replaces modal
     * escalation for autonomy-mode C1/C2 decisions). Pushes a webview toast
     * with severity + category + fallback_choice so the user can audit later
     * without supervisor pausing the plan.
     */
    private void dispatchAlertFromAction(JsonObject action, JsonObject payload) {
        pair.getProgressManager().incrementCounter("alert_count");
        JsonObject alertPayload = new JsonObject();
        alertPayload.addProperty("pairId", pair.getPairId());
        alertPayload.addProperty("supervisorId", pair.getAgentId());
        if (action != null && action.has("reason")) {
            alertPayload.add("reason", action.get("reason"));
        }
        if (payload != null) {
            if (payload.has("severity")) alertPayload.add("severity", payload.get("severity"));
            if (payload.has("category")) alertPayload.add("category", payload.get("category"));
            if (payload.has("fallback_choice")) alertPayload.add("fallback_choice", payload.get("fallback_choice"));
            if (payload.has("question")) alertPayload.add("question", payload.get("question"));
        }
        alertPayload.addProperty("ts", System.currentTimeMillis());
        try {
            webview.onPairAlert(alertPayload);
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onPairAlert failed: " + e.getMessage());
        }
        // Phase 6 (2026-05-24): T1 — alerts are inflection points worth a plan
        // re-check. Fire replan_due("after_alert") so the supervisor's next
        // tick considers whether the rest of the plan still makes sense.
        if (pair.getEventBus() != null) {
            try {
                PairBudgetTracker bt = pair.getBudgetTracker();
                int stepsCompleted = bt != null ? bt.getStepsCompleted() : 0;
                pair.getEventBus().publishReplanDue("after_alert", stepsCompleted);
            } catch (Exception e) {
                LOG.debug("[ActionRouter] after_alert replan publish failed: " + e.getMessage());
            }
        }
    }

    /**
     * Protocol v2 (2026-05-24): called by {@link PairHandler} when the
     * webview posts {@code pair_directive_ack}. Delegates to the pair's
     * {@code DirectiveTracker} so its timeout is cancelled. Status is one of
     * "received" | "applied" | "failed" — "received" only cancels the fast
     * received-timeout; "applied"/"failed" remove the directive entirely.
     */
    public void markDirectiveAcked(String directiveId, String status) {
        if (directiveId == null || directiveId.isEmpty()) return;
        DirectiveTracker tracker = pair.getDirectiveTracker();
        if (tracker == null) return;
        tracker.markAcked(directiveId, status);
        // Drop the retry counter once the webview confirmed receipt — a later
        // retry-timeout from a stale schedule would be a no-op anyway, but
        // freeing the entry keeps the map small.
        if ("received".equals(status) || "applied".equals(status) || "failed".equals(status)) {
            retryCounters.remove(directiveId);
        }
    }

    /**
     * 2026-05-25: invoked by the per-pair {@link DirectiveTracker}'s
     * received-timeout (~3s with no "received" ack). Re-pushes the inject to
     * the webview up to {@link #MAX_RECEIVED_RETRIES} times before giving up
     * and letting the slow applied-timeout fire {@code directive_lost}.
     *
     * <p>The retry covers the JBCef-bridge-drop intermittent failure: the
     * Java side wrote {@code callJavaScript("window.onPairInjectPrompt",...)}
     * but the webview never received the call (cold start, EDT blocked,
     * watchdog reload). Each retry is a fresh {@code pushToWebview} attempt
     * — by the second or third try, the webview has usually finished
     * mounting and accepts the inject.
     */
    public void retryInjectOnReceivedTimeout(String directiveId, JsonObject payload) {
        if (directiveId == null || directiveId.isEmpty() || payload == null) return;
        if (pair.isDisposed()) return;
        AtomicInteger ctr = retryCounters.computeIfAbsent(directiveId, k -> new AtomicInteger(0));
        int attempt = ctr.incrementAndGet();
        if (attempt > MAX_RECEIVED_RETRIES) {
            LOG.warn("[INJECT_TRACE] ActionRouter.retryGaveUp"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + directiveId
                    + " attempts=" + attempt
                    + " — falling through to slow applied-timeout");
            return;
        }
        String prompt = resolvePromptFromPayload(payload);
        if (prompt == null || prompt.isEmpty()) {
            LOG.warn("[INJECT_TRACE] ActionRouter.retrySkippedEmptyPrompt"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + directiveId);
            return;
        }
        LOG.info("[INJECT_TRACE] ActionRouter.retry"
                + " pair=" + pair.getPairId()
                + " directiveId=" + directiveId
                + " attempt=" + attempt + "/" + MAX_RECEIVED_RETRIES);
        invokeInjectOnWebview(directiveId, prompt);
        DirectiveTracker tracker = pair.getDirectiveTracker();
        if (tracker != null) tracker.rearmReceivedTimeout(directiveId);
    }

    /**
     * Mirror of the prompt-resolution logic in {@link #handleInjectPrompt} so
     * retry can use the same final string. Kept private so the live
     * dispatch path remains the single source of truth for shape decisions.
     */
    private String resolvePromptFromPayload(JsonObject payload) {
        if (payload.has("inlinePrompt") && !payload.get("inlinePrompt").isJsonNull()
                && !payload.get("inlinePrompt").getAsString().isEmpty()) {
            return payload.get("inlinePrompt").getAsString();
        }
        if (payload.has("prompt") && !payload.get("prompt").isJsonNull()) {
            return payload.get("prompt").getAsString();
        }
        if (payload.has("spilledPath") && !payload.get("spilledPath").isJsonNull()) {
            String spilled = payload.get("spilledPath").getAsString();
            String objective = payload.has("objective") && !payload.get("objective").isJsonNull()
                    ? payload.get("objective").getAsString() : "(no objective)";
            return "请按 `" + spilled + "` 中的完整指令执行(objective: " + objective + ")";
        }
        return "";
    }

    /**
     * v3: surface each self-decision record as its own webview card so the user
     * can scrub through the supervisor's micro-judgments after the session,
     * and bump the running counters so the verification dialog reflects them.
     *
     * <p>Records are pushed individually (rather than as one combined payload)
     * so the existing right-pane event log keeps its "one card per row"
     * cadence — the renderer just needs to recognize the new {@code kind}.
     */
    private void dispatchDecisions(JsonObject payload) {
        if (payload == null || !payload.has("decisions") || !payload.get("decisions").isJsonArray()) {
            return;
        }
        JsonArray decisions = payload.getAsJsonArray("decisions");
        for (JsonElement el : decisions) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject d = el.getAsJsonObject();

            pair.getProgressManager().incrementCounter("decision_count");
            boolean reviewFlag = d.has("review_flag")
                    && !d.get("review_flag").isJsonNull()
                    && d.get("review_flag").getAsBoolean();
            if (reviewFlag) {
                pair.getProgressManager().incrementCounter("decision_review_flag_count");
            }

            JsonObject evt = new JsonObject();
            evt.addProperty("kind", "decision_record");
            evt.addProperty("pairId", pair.getPairId());
            evt.addProperty("supervisorId", pair.getAgentId());
            evt.add("decision", d);
            try {
                webview.onActionEvent(evt);
            } catch (Exception e) {
                LOG.warn("[ActionRouter] decision push failed: " + e.getMessage());
            }
        }
    }

    private void handleInjectPrompt(JsonObject payload, long delaySeconds) {
        // Protocol v2 (2026-05-24): structured payload may have inlinePrompt OR
        // spilledPath instead of (or alongside) the legacy `prompt` field.
        // For the webview side we still send a single string — supervisor
        // prompt content is the most common case, spill is rare. When only
        // spilledPath is set, we send a stub prompt that points to the file
        // (webview/main AI can Read it).
        String prompt;
        if (payload.has("inlinePrompt") && !payload.get("inlinePrompt").isJsonNull()
                && !payload.get("inlinePrompt").getAsString().isEmpty()) {
            prompt = payload.get("inlinePrompt").getAsString();
        } else if (payload.has("prompt") && !payload.get("prompt").isJsonNull()) {
            prompt = payload.get("prompt").getAsString();
        } else if (payload.has("spilledPath") && !payload.get("spilledPath").isJsonNull()) {
            String spilled = payload.get("spilledPath").getAsString();
            String objective = payload.has("objective") && !payload.get("objective").isJsonNull()
                    ? payload.get("objective").getAsString() : "(no objective)";
            prompt = "请按 `" + spilled + "` 中的完整指令执行(objective: " + objective + ")";
        } else {
            prompt = "";
        }
        if (prompt.isEmpty()) {
            LOG.warn("[ActionRouter] inject_prompt with empty payload — skipping");
            return;
        }

        // Protocol v2: register directive with the per-pair DirectiveTracker so
        // a 5min ack timeout fires if the main AI never responds. directiveId
        // is generated by the daemon (normalizeAction) and surfaces on the
        // payload (also on the wrapper top-level, but payload is more reliable).
        String directiveId = null;
        if (payload.has("directiveId") && !payload.get("directiveId").isJsonNull()) {
            directiveId = payload.get("directiveId").getAsString();
        }
        if (directiveId != null && !directiveId.isEmpty()) {
            DirectiveTracker tracker = pair.getDirectiveTracker();
            if (tracker != null) {
                tracker.registerDirective(directiveId, payload);
            }
        }

        // 2026-05-24 (Q4 trace): observe the prompt entering the dispatch path
        // with directiveId + length so we can correlate against daemon stderr
        // (`[INJECT_TRACE] daemon wrote ...`) and the webview console
        // (`[INJECT_TRACE] webview onPairInjectPrompt ...`).
        String preview = prompt.length() > 80
                ? prompt.substring(0, 80).replace('\n', ' ') + "…"
                : prompt.replace('\n', ' ');
        LOG.info("[INJECT_TRACE] ActionRouter.handleInjectPrompt"
                + " pair=" + pair.getPairId()
                + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                + " delaySec=" + delaySeconds
                + " promptLen=" + prompt.length()
                + " preview=\"" + preview + "\"");

        final String capturedDirectiveId = directiveId;
        final String capturedPrompt = prompt;
        if (delaySeconds <= 0) {
            invokeInjectOnWebview(capturedDirectiveId, capturedPrompt);
        } else {
            scheduler.schedule(
                    () -> {
                        if (pair.isDisposed()) return;
                        invokeInjectOnWebview(capturedDirectiveId, capturedPrompt);
                    },
                    delaySeconds,
                    TimeUnit.SECONDS
            );
        }
    }

    /**
     * Push the prompt to the webview. Uses the V2 signature when a directive
     * id is available so the webview can post a matching ack; falls back to
     * the legacy 3-arg form when there is no id (e.g. test paths or daemons
     * that predate v2). On dispatch failure we DON'T cancel the directive —
     * letting it time out naturally surfaces directive_lost to the supervisor.
     *
     * <p>2026-05-25: if the webview hasn't signalled readiness yet, buffer
     * the push instead of firing — {@link #markWebviewReady} drains the
     * buffer once the IPC arrives. This prevents a cold-start dispatch from
     * being lost when the React tree hasn't mounted yet.
     */
    private void invokeInjectOnWebview(String directiveId, String prompt) {
        if (!webviewReady) {
            if (pendingInjectsBeforeReady.size() >= MAX_PENDING_INJECTS_BEFORE_READY) {
                LOG.warn("[INJECT_TRACE] ActionRouter.bufferOverflow"
                        + " pair=" + pair.getPairId()
                        + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                        + " — pre-ready buffer full ("
                        + MAX_PENDING_INJECTS_BEFORE_READY + "), dropping oldest");
                pendingInjectsBeforeReady.pollFirst();
            }
            pendingInjectsBeforeReady.offerLast(new PendingInject(directiveId, prompt));
            LOG.info("[INJECT_TRACE] ActionRouter.bufferedBeforeReady"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                    + " bufferSize=" + pendingInjectsBeforeReady.size());
            return;
        }
        pushInjectNow(directiveId, prompt);
    }

    private void pushInjectNow(String directiveId, String prompt) {
        try {
            // 2026-05-24 (Q4 trace): record the push attempt. The webview-side
            // counterpart logs `[INJECT_TRACE] webview onPairInjectPrompt`. If
            // this line shows here but the webview line never appears, the
            // JBCef bridge dropped the call.
            LOG.info("[INJECT_TRACE] ActionRouter→webview push"
                    + " pair=" + pair.getPairId()
                    + " directiveId=" + (directiveId != null ? directiveId : "(none)")
                    + " v2=" + (directiveId != null && !directiveId.isEmpty()));
            if (directiveId != null && !directiveId.isEmpty()) {
                webview.onInjectPromptV2(pair.getPairId(), pair.getAgentId(), directiveId, prompt);
            } else {
                webview.onInjectPrompt(pair.getPairId(), pair.getAgentId(), prompt);
            }
        } catch (Exception e) {
            LOG.warn("[ActionRouter] onInjectPrompt(V2) failed: " + e.getMessage());
        }
    }

    /**
     * 2026-05-25: called by {@link com.github.claudecodegui.handler.PairHandler}
     * when the webview posts {@code pair_webview_ready}. Flips the readiness
     * gate and drains any injects that were buffered while we were waiting.
     * Idempotent — subsequent calls drain the buffer (covers webview reload
     * where state was cleared but the Java pair is still alive).
     */
    public void markWebviewReady() {
        webviewReady = true;
        if (pendingInjectsBeforeReady.isEmpty()) {
            LOG.info("[INJECT_TRACE] ActionRouter.webviewReady"
                    + " pair=" + pair.getPairId() + " bufferEmpty=true");
            return;
        }
        int drained = 0;
        PendingInject p;
        while ((p = pendingInjectsBeforeReady.pollFirst()) != null) {
            drained++;
            pushInjectNow(p.directiveId, p.prompt);
        }
        LOG.info("[INJECT_TRACE] ActionRouter.webviewReady"
                + " pair=" + pair.getPairId() + " drained=" + drained);
    }

    private static final class PendingInject {
        final String directiveId;
        final String prompt;
        PendingInject(String directiveId, String prompt) {
            this.directiveId = directiveId;
            this.prompt = prompt;
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
