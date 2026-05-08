package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.path.IdentityPathMapper;
import com.github.claudecodegui.path.PathFieldVisitor;
import com.github.claudecodegui.path.PathMapper;
import com.github.claudecodegui.path.PathMapperHolder;
import com.github.claudecodegui.permission.ControlMessageHandler;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remote-mode bridge: speaks HTTP to an {@code ai-bridge-server} instance.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@code POST /session} — server spawns a daemon; returns {@code sessionId}.</li>
 *   <li>{@code GET /session/{id}/events} — opens an SSE stream for daemon output
 *       (handled by {@link SseSubscriber}).</li>
 *   <li>{@code POST /session/{id}/in} — every Java→daemon message (request,
 *       heartbeat, abort, control response) is written here verbatim.</li>
 *   <li>{@code DELETE /session/{id}} — kills the remote daemon on stop.</li>
 * </ol>
 *
 * <p>Mirrors {@link LocalBridge}'s public contract via {@link IBridge}; existing
 * higher-level callers (executor, coordinator, sdk bridge) work unchanged.
 *
 * <p>Permission / AskUserQuestion / Plan approval flow as {@code _ctrl} messages
 * over the same SSE/POST channel; deliver them to a {@link ControlMessageHandler}
 * via {@link #setControlMessageHandler}.
 */
public class RemoteBridge implements IBridge {

    private static final Logger LOG = Logger.getInstance(RemoteBridge.class);
    private static final long START_TIMEOUT_MS = 30_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final HttpClient http;

    private volatile String sessionId;
    private volatile SseSubscriber sseSubscriber;
    private final AtomicLong lastEventId = new AtomicLong(0);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final AtomicBoolean sdkPreloaded = new AtomicBoolean(false);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);

    private final ConcurrentHashMap<String, RequestState> active = new ConcurrentHashMap<>();
    private volatile DaemonLifecycleListener lifecycleListener;
    private volatile ControlMessageHandler controlHandler;

    private final Project project;             // may be null only for legacy callers
    private final String localProjectPath;     // captured at construction; null when no project open
    private volatile String lastStartFailureCode;
    private volatile String lastStartFailureMessage;

    /**
     * Legacy constructor. Prefer {@link #RemoteBridge(String, Project)} so the
     * bridge can carry projectPath and use the project-scoped path mapper.
     */
    public RemoteBridge(String baseUrl) {
        this(baseUrl, null);
    }

    public RemoteBridge(String baseUrl, Project project) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl required");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.baseUrl = trimmed;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.project = project;
        this.localProjectPath = project != null ? project.getBasePath() : null;
    }

    public String getBaseUrl() { return baseUrl; }
    public String getSessionId() { return sessionId; }

    /** Last-start failure tag, or null when start has not failed since last reset. */
    public String getLastStartFailureCode() { return lastStartFailureCode; }
    public String getLastStartFailureMessage() { return lastStartFailureMessage; }

    /** Resolve the active path mapper just-in-time so config changes take effect. */
    private PathMapper currentMapper() {
        return project != null
                ? PathMapperHolder.getInstance(project).get()
                : IdentityPathMapper.INSTANCE;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean start() {
        if (alive.get()) {
            LOG.info("[RemoteBridge] Already running");
            return true;
        }

        // Reset structured failure state for this attempt.
        lastStartFailureCode = null;
        lastStartFailureMessage = null;

        // Mandatory project guard (design §1 rules 9 + 10).
        if (localProjectPath == null || localProjectPath.isEmpty()) {
            LOG.warn("[RemoteBridge] cannot start: no project open");
            notifyStartFailed("PROJECT_NOT_OPEN", "请先打开一个项目");
            return false;
        }

        readyLatch = new CountDownLatch(1);
        try {
            // 1. POST /session with mandatory projectPath (translated to remote form).
            JsonObject createBody = new JsonObject();
            String wirePath = currentMapper().toRemote(localProjectPath);
            createBody.addProperty("projectPath", wirePath);

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/session"))
                            .timeout(HTTP_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(createBody)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() == 400) {
                JsonObject err = safeParse(resp.body());
                String code = optString(err, "code");
                String msg  = optString(err, "error");
                LOG.warn("[RemoteBridge] POST /session 400 code=" + code + " msg=" + msg);
                notifyStartFailed(code != null && !code.isEmpty() ? code : "BAD_REQUEST",
                        msg != null && !msg.isEmpty() ? msg : "Bad request");
                return false;
            }
            if (resp.statusCode() != 200) {
                LOG.warn("[RemoteBridge] POST /session failed: " + resp.statusCode() + " " + resp.body());
                notifyStartFailed("SESSION_CREATE_FAILED", "HTTP " + resp.statusCode());
                return false;
            }
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            sessionId = body.get("sessionId").getAsString();
            LOG.info("[RemoteBridge] Session created: " + sessionId + " projectPath=" + wirePath);

            // 2. Subscribe SSE
            startSseSubscriber();
            alive.set(true);

            // 3. Wait for daemon ready event
            if (!readyLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOG.warn("[RemoteBridge] Daemon did not signal ready within " + START_TIMEOUT_MS + "ms");
                stop();
                return false;
            }
            LOG.info("[RemoteBridge] Ready (sdkPreloaded=" + sdkPreloaded.get() + ")");
            return true;
        } catch (Exception e) {
            LOG.warn("[RemoteBridge] start() failed: " + e.getMessage(), e);
            notifyStartFailed("SESSION_CREATE_FAILED", e.getMessage() != null ? e.getMessage() : "transport error");
            return false;
        }
    }

    @Override
    public void stop() {
        // Cancel SSE unconditionally — alive may already be false (e.g. SSE
        // permanent failure path), but the worker thread can still be alive
        // and reconnecting. Failing to cancel here leaks 404 retries forever.
        SseSubscriber sub = sseSubscriber;
        if (sub != null) {
            try { sub.cancel(); } catch (Exception ignore) {}
            sseSubscriber = null;
        }

        boolean firstStop = alive.compareAndSet(true, false);
        ready.set(false);

        // Best-effort DELETE only on the first stop — the server doesn't need
        // duplicate destroy calls and the sessionId is cleared below.
        String sid = sessionId;
        if (firstStop && sid != null) {
            try {
                http.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/session/" + sid))
                                .timeout(SHORT_TIMEOUT)
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.discarding()
                ).orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> {
                    LOG.debug("[RemoteBridge] DELETE /session error: " + e.getMessage());
                    return null;
                });
            } catch (Exception ignore) {}
        }
        sessionId = null;

        if (!firstStop) return;

        // Fail any in-flight requests so callers unblock
        failAllPending("Bridge stopped");

        DaemonLifecycleListener l = lifecycleListener;
        if (l != null) {
            try { l.onDaemonDied(); } catch (Exception ignore) {}
        }
    }

    @Override
    public boolean isAlive() {
        return alive.get();
    }

    @Override
    public boolean ensureRunning() {
        return isAlive() || start();
    }

    @Override
    public void sendAbort() {
        if (sessionId == null) return;
        JsonObject abort = new JsonObject();
        abort.addProperty("id", "abort-" + System.currentTimeMillis());
        abort.addProperty("method", "abort");
        postIn(abort);
        // Also fail in-flight futures so Java side unblocks immediately
        failAllPending("Request aborted by user");
    }

    @Override
    public CompletableFuture<Boolean> sendCommand(
            String method,
            JsonObject params,
            DaemonOutputCallback callback
    ) {
        if (!ensureRunning()) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(new IOException("Remote bridge not running"));
            return f;
        }

        String id = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        active.put(id, new RequestState(callback, future));

        // Cleanup on completion (timeout/cancellation/normal)
        future.whenComplete((r, ex) -> active.remove(id));

        JsonObject req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("method", method);
        if (params != null) req.add("params", params);

        // Outbound path translation — translate every path-bearing field
        // declared in PathFields.OUTBOUND for this method, in place. The
        // wrapper object is only used to resolve $.params.* expressions.
        PathMapper mapper = currentMapper();
        if (mapper.isActive()) {
            try {
                PathFieldVisitor.applyOutbound(method, req, mapper::toRemote);
            } catch (Exception e) {
                LOG.warn("[RemoteBridge] outbound translation failed: " + e.getMessage());
            }
        }

        if (!postIn(req)) {
            active.remove(id);
            future.completeExceptionally(new IOException("Failed to POST /in"));
        }
        return future;
    }

    @Override
    public void setLifecycleListener(DaemonLifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    @Override
    public boolean isSdkPreloaded() {
        return sdkPreloaded.get();
    }

    @Override
    public void setControlMessageHandler(ControlMessageHandler handler) {
        this.controlHandler = handler;
    }

    // =========================================================================
    // SSE event router
    // =========================================================================

    private void startSseSubscriber() {
        URI url = URI.create(baseUrl + "/session/" + sessionId + "/events");
        sseSubscriber = new SseSubscriber(
                url,
                lastEventId,
                this::onSseEvent,
                this::onSseClosed
        );
        sseSubscriber.start();
    }

    private void onSseEvent(String data) {
        if (data == null || data.isEmpty()) return;
        JsonObject msg;
        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (!parsed.isJsonObject()) return;
            msg = parsed.getAsJsonObject();
        } catch (Exception e) {
            LOG.debug("[RemoteBridge] Invalid SSE data: " + data);
            return;
        }

        // Inbound path translation — translate path-bearing fields declared in
        // PathFields.INBOUND for the matched event tag, in place. Tool-use
        // input fields are translated downstream in ClaudeMessageHandler since
        // they live nested inside `line` payloads.
        PathMapper mapper = currentMapper();
        if (mapper.isActive()) {
            String tag = inboundTag(msg);
            if (tag != null) {
                try {
                    PathFieldVisitor.applyInbound(tag, msg, mapper::toLocal);
                } catch (Exception e) {
                    LOG.warn("[RemoteBridge] inbound translation failed: " + e.getMessage());
                }
            }
        }

        String type = optString(msg, "type");
        if ("daemon".equals(type)) {
            handleDaemonEvent(msg);
            return;
        }
        if ("_ctrl".equals(type)) {
            handleCtrl(msg);
            return;
        }
        if ("heartbeat".equals(type)) {
            // ignore — server sends comment-style ping; this branch covers an
            // explicit heartbeat envelope if the daemon emits one.
            return;
        }
        if (msg.has("id")) {
            handleRequestOutput(msg);
        }
    }

    private void onSseClosed(Throwable t) {
        LOG.warn("[RemoteBridge] SSE permanently closed: " + (t != null ? t.getMessage() : ""));
        // Treat as daemon dead — fail in-flight, notify lifecycle, and clear
        // sessionId so subsequent ensureRunning() will create a new session
        // rather than POST /in to a dead one.
        if (alive.compareAndSet(true, false)) {
            sessionId = null;
            ready.set(false);
            failAllPending("Remote daemon lost (SSE closed)");
            DaemonLifecycleListener l = lifecycleListener;
            if (l != null) {
                try { l.onDaemonDied(); } catch (Exception ignore) {}
            }
        }
    }

    private void handleDaemonEvent(JsonObject msg) {
        String event = optString(msg, "event");
        if ("ready".equals(event)) {
            if (msg.has("sdkPreloaded") && !msg.get("sdkPreloaded").isJsonNull()) {
                sdkPreloaded.set(msg.get("sdkPreloaded").getAsBoolean());
            }
            ready.set(true);
            readyLatch.countDown();
            DaemonLifecycleListener l = lifecycleListener;
            if (l != null) {
                try { l.onDaemonReady(); } catch (Exception ignore) {}
            }
        } else if ("shutdown".equals(event)) {
            LOG.info("[RemoteBridge] daemon shutdown event received");
            // SSE will close; lifecycle will fire from onSseClosed.
        }
    }

    private void handleCtrl(JsonObject msg) {
        String action = optString(msg, "action");
        if ("gateway_error".equals(action)) {
            LOG.warn("[RemoteBridge] gateway_error: " + msg);
            // Treat as daemon dead.
            if (alive.compareAndSet(true, false)) {
                failAllPending("Remote daemon error: " + optString(msg, "message"));
                DaemonLifecycleListener l = lifecycleListener;
                if (l != null) {
                    try { l.onDaemonDied(); } catch (Exception ignore) {}
                }
            }
            return;
        }
        // permission_request / ask_user_question_request / plan_approval_request
        ControlMessageHandler h = controlHandler;
        if (h == null) {
            LOG.warn("[RemoteBridge] No ControlMessageHandler configured; ignoring " + action);
            return;
        }
        try {
            h.onRequest(action, msg, this::postIn);
        } catch (Exception e) {
            LOG.warn("[RemoteBridge] Control handler threw", e);
        }
    }

    private void handleRequestOutput(JsonObject msg) {
        String id = msg.get("id").getAsString();
        RequestState rs = active.get(id);
        if (rs == null) {
            // Already completed or not ours.
            return;
        }

        if (msg.has("done") && msg.get("done").getAsBoolean()) {
            boolean success = !msg.has("success") || msg.get("success").getAsBoolean();
            if (!success && msg.has("error")) {
                rs.callback.onError(msg.get("error").getAsString());
            }
            rs.callback.onComplete(success);
            rs.future.complete(success);
            return;
        }
        if (msg.has("line")) {
            rs.callback.onLine(msg.get("line").getAsString());
            return;
        }
        if (msg.has("stderr")) {
            rs.callback.onStderr(msg.get("stderr").getAsString());
        }
    }

    // =========================================================================
    // POST /in
    // =========================================================================

    private boolean postIn(JsonObject body) {
        String sid = sessionId;
        if (sid == null) {
            LOG.warn("[RemoteBridge] postIn called with no sessionId");
            return false;
        }
        try {
            http.sendAsync(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/session/" + sid + "/in"))
                            .timeout(HTTP_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            ).exceptionally(e -> {
                LOG.warn("[RemoteBridge] POST /in failed: " + e.getMessage());
                return null;
            });
            return true;
        } catch (Exception e) {
            LOG.warn("[RemoteBridge] POST /in throw: " + e.getMessage());
            return false;
        }
    }

    private void failAllPending(String reason) {
        for (RequestState rs : active.values()) {
            try { rs.callback.onError(reason); } catch (Exception ignore) {}
            try { rs.callback.onComplete(false); } catch (Exception ignore) {}
            try { rs.future.complete(false); } catch (Exception ignore) {}
        }
        active.clear();
    }

    private static String optString(JsonObject o, String k) {
        if (o == null) return null;
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }

    /**
     * Compute the manifest key for an inbound SSE event. Returns null for
     * events that have no path-bearing fields registered (request-output
     * lines, heartbeats, etc.).
     */
    private static String inboundTag(JsonObject msg) {
        String type = optString(msg, "type");
        if ("daemon".equals(type)) {
            String event = optString(msg, "event");
            return event != null && !event.isEmpty() ? "daemon." + event : null;
        }
        if ("_ctrl".equals(type)) {
            String action = optString(msg, "action");
            return action != null && !action.isEmpty() ? "_ctrl." + action : null;
        }
        return null;
    }

    /** Best-effort JSON parse — returns null on any failure. */
    private static JsonObject safeParse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JsonElement e = JsonParser.parseString(s);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Notify the lifecycle listener of a structured start failure. If the
     * listener implements {@link DaemonLifecycleListenerWithError}, it gets
     * the {@code code}/{@code message} pair; otherwise the legacy
     * {@link DaemonLifecycleListener#onDaemonDied()} fallback fires.
     */
    private void notifyStartFailed(String code, String message) {
        // Persist for getLastStartFailure*() so callers (e.g. ClaudeSDKBridge)
        // that don't register a lifecycle listener can still surface the
        // structured reason.
        this.lastStartFailureCode = code;
        this.lastStartFailureMessage = message;

        DaemonLifecycleListener l = lifecycleListener;
        if (l == null) return;
        try {
            if (l instanceof DaemonLifecycleListenerWithError) {
                ((DaemonLifecycleListenerWithError) l).onDaemonStartFailed(code, message);
            } else {
                l.onDaemonDied();
            }
        } catch (Exception ignore) {}
    }

    private static class RequestState {
        final DaemonOutputCallback callback;
        final CompletableFuture<Boolean> future;
        RequestState(DaemonOutputCallback cb, CompletableFuture<Boolean> f) {
            this.callback = cb;
            this.future = f;
        }
    }
}
