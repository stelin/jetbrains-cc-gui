package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local-mode bridge: spawns a long-running Node.js daemon child process and
 * communicates over stdin/stdout NDJSON.
 *
 * <p>This is the original {@code DaemonBridge} class, renamed and adjusted to
 * implement {@link IBridge} so it can be transparently swapped with
 * {@link RemoteBridge} for the remote / docker deployment mode.
 *
 * <p>Permission / AskUser / Plan IPC under local mode flows via the existing
 * file IPC under {@code ~/.claude/permissions/} watched by
 * {@code PermissionRequestWatcher}; therefore {@link #setControlMessageHandler}
 * is a no-op for this implementation.
 *
 * <p>Behaviour and protocol are otherwise identical to the original
 * implementation — no business-logic changes.
 *
 * Protocol:
 * - Java writes JSON requests to daemon's stdin (one per line)
 * - Daemon writes JSON responses to stdout (one per line, tagged with request ID)
 * - Daemon lifecycle events have type="daemon"
 * - Command output lines have an "id" field matching the request
 * - Command completion is signaled by {"id":"X","done":true}
 */
public class LocalBridge implements IBridge {

    private static final Logger LOG = Logger.getInstance(LocalBridge.class);
    private static final String DAEMON_SCRIPT = "daemon.js";
    private static final long DAEMON_START_TIMEOUT_MS = 30_000;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000; // 3 missed heartbeats = dead
    private static final long ACTIVE_REQUEST_HEARTBEAT_TIMEOUT_MS = 180_000;
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long RESTART_WINDOW_MS = 30_000; // Reset restart counter after this period of stability

    /**
     * Maximum number of self-heal attempts per {@link #start()} call when the
     * daemon reports stale capabilities. One retry is enough in practice — if
     * a fresh extraction still produces a stale daemon, the bundle itself is
     * broken and further retries won't help.
     */
    private static final int MAX_SELF_HEAL_ATTEMPTS = 1;

    private final NodeDetector nodeDetector;
    private final BridgeDirectoryResolver directoryResolver;
    private final EnvironmentConfigurator envConfigurator;
    // Daemon process state
    private volatile Process daemonProcess;
    private volatile BufferedWriter daemonStdin;
    private volatile Thread readerThread;
    private volatile Thread heartbeatThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean sdkPreloaded = new AtomicBoolean(false);
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private final AtomicLong lastSuccessfulStart = new AtomicLong(0);
    private final AtomicLong lastHeartbeatResponse = new AtomicLong(0);
    private final AtomicLong lastDaemonActivity = new AtomicLong(0);
    private final AtomicInteger activeRequestCount = new AtomicInteger(0);
    private final Object startLock = new Object();

    /**
     * Latest {@code version} string the daemon reported in its {@code starting}
     * or {@code ready} event. {@code null} when no version has been observed
     * yet (e.g. a pre-supervisor daemon that doesn't emit the field).
     */
    private final java.util.concurrent.atomic.AtomicReference<String> reportedDaemonVersion =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Whether the daemon advertised {@code supervisorSupport: true} in its
     * ready event. {@code null} when the field was absent — which itself
     * indicates an old daemon and triggers self-heal.
     */
    private final java.util.concurrent.atomic.AtomicReference<Boolean> reportedSupervisorSupport =
            new java.util.concurrent.atomic.AtomicReference<>();

    // Pending request handlers: requestId -> handler
    private final ConcurrentHashMap<String, RequestHandler> pendingRequests = new ConcurrentHashMap<>();

    // Lifecycle listener
    private volatile IBridge.DaemonLifecycleListener lifecycleListener;

    public LocalBridge(
            NodeDetector nodeDetector,
            BridgeDirectoryResolver directoryResolver,
            EnvironmentConfigurator envConfigurator
    ) {
        this.nodeDetector = nodeDetector;
        this.directoryResolver = directoryResolver;
        this.envConfigurator = envConfigurator;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start the daemon process. Blocks until the daemon signals "ready"
     * or the timeout expires.
     *
     * <p>Wraps {@link #startInternal()} with a one-shot self-heal: if the
     * freshly-started daemon doesn't advertise {@code supervisorSupport=true},
     * we treat the bridge directory as stale, force a fresh extraction via
     * {@link BridgeDirectoryResolver#invalidateAndForceReExtract()}, and try
     * one more time. This is the runtime safety net for the case where
     * {@code findSdkDir()} returned a structurally-valid-but-stale path that
     * we couldn't catch at resolution time.
     *
     * @return true if the daemon is running (even if stale after exhausting self-heal)
     */
    @Override
    public boolean start() {
        // Outer synchronization on startLock keeps multiple concurrent start()
        // calls from interleaving self-heal restarts (which would observe each
        // other's daemons and trigger spurious re-extractions). Java's
        // synchronized is reentrant, so startInternal()'s own startLock
        // acquisition inside this block is safe.
        synchronized (startLock) {
            for (int attempt = 0; attempt <= MAX_SELF_HEAL_ATTEMPTS; attempt++) {
                // Reset capability flags before each attempt so we observe THIS daemon.
                reportedDaemonVersion.set(null);
                reportedSupervisorSupport.set(null);

                boolean started = startInternal();
                if (!started) {
                    return false;
                }

                if (isDaemonHealthy()) {
                    return true;
                }

                if (attempt >= MAX_SELF_HEAL_ATTEMPTS) {
                    LOG.error("[LocalBridge] Self-heal budget exhausted; daemon remains stale "
                            + "(version=" + reportedDaemonVersion.get()
                            + ", supervisorSupport=" + reportedSupervisorSupport.get()
                            + "). Supervisor-channel features will fail until the bridge is refreshed manually.");
                    // Return true: the daemon IS running and most features work. Only
                    // supervisor-channel calls will fail, and they now surface a clear
                    // user-facing message via SupervisorBridge.translateDaemonError.
                    return true;
                }

                LOG.warn("[LocalBridge] Stale daemon detected on attempt " + (attempt + 1)
                        + " (version=" + reportedDaemonVersion.get()
                        + ", supervisorSupport=" + reportedSupervisorSupport.get()
                        + "). Self-healing…");

                // Tear down the stale daemon and force a fresh bridge extraction.
                // stop() sets isRunning=false before destroyForcibly(), so the
                // reader-thread death path (handleDaemonDeath) is short-circuited
                // and won't race with our explicit restart below.
                stop();
                File reExtracted = directoryResolver.invalidateAndForceReExtract();
                if (reExtracted == null) {
                    LOG.error("[LocalBridge] Force re-extract failed; aborting self-heal");
                    return false;
                }
            }
            // Unreachable — the loop always returns inside.
            return false;
        }
    }

    /**
     * Internal daemon-spawn that does NOT self-heal. Called from {@link #start()}.
     */
    private boolean startInternal() {
        synchronized (startLock) {
            if (isRunning.get()) {
                LOG.info("[LocalBridge] Daemon already running");
                return true;
            }

            LOG.info("[LocalBridge] Starting daemon process...");
            CountDownLatch latch = new CountDownLatch(1);
            readyLatch = latch;

            try {
                File bridgeDir = directoryResolver.findSdkDir();
                if (bridgeDir == null) {
                    LOG.error("[LocalBridge] Bridge directory not found");
                    return false;
                }

                File daemonScript = new File(bridgeDir, DAEMON_SCRIPT);
                if (!daemonScript.exists()) {
                    LOG.error("[LocalBridge] daemon.js not found at: " + daemonScript.getAbsolutePath());
                    return false;
                }
                // Log which daemon.js we're about to spawn — invaluable when a stale
                // daemon mysteriously responds with "Unknown provider: supervisor"
                // (proves whether the bridge directory we resolved is actually the
                // one whose daemon ends up running).
                logDaemonScriptIdentity(daemonScript);

                String nodePath = nodeDetector.findNodeExecutable();
                if (nodePath == null) {
                    LOG.error("[LocalBridge] Node.js not found");
                    return false;
                }

                ProcessBuilder pb = new ProcessBuilder(nodePath, daemonScript.getAbsolutePath());
                pb.directory(bridgeDir);

                // Configure environment
                Map<String, String> env = pb.environment();
                envConfigurator.updateProcessEnvironment(pb, nodePath);

                // Keep stderr separate for debugging
                pb.redirectErrorStream(false);

                daemonProcess = pb.start();
                isRunning.set(true);
                lastSuccessfulStart.set(System.currentTimeMillis());
                markDaemonActivity();

                LOG.info("[LocalBridge] Daemon process started, PID: " + daemonProcess.pid());

                // Setup stdin writer
                daemonStdin = new BufferedWriter(
                        new OutputStreamWriter(daemonProcess.getOutputStream(), StandardCharsets.UTF_8));

                // Start stdout reader thread
                startReaderThread();

                // Start stderr reader thread (for debugging)
                startStderrReaderThread();

                // Wait for "ready" event, but fail fast if process exits early.
                boolean ready = false;
                long deadline = System.currentTimeMillis() + DAEMON_START_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (latch.await(200, TimeUnit.MILLISECONDS)) {
                        ready = true;
                        break;
                    }
                    if (daemonProcess == null || !daemonProcess.isAlive() || !isRunning.get()) {
                        LOG.error("[LocalBridge] Daemon exited before signaling ready");
                        isRunning.set(false);
                        return false;
                    }
                }
                if (!ready) {
                    LOG.warn("[LocalBridge] Daemon did not signal ready within timeout");
                    if (daemonProcess == null || !daemonProcess.isAlive() || !isRunning.get()) {
                        LOG.error("[LocalBridge] Daemon is not alive after ready timeout");
                        isRunning.set(false);
                        return false;
                    }
                }

                // Start heartbeat thread
                startHeartbeatThread();

                LOG.info("[LocalBridge] Daemon is ready. SDK preloaded: " + sdkPreloaded.get());
                return true;

            } catch (Exception e) {
                LOG.error("[LocalBridge] Failed to start daemon", e);
                isRunning.set(false);
                return false;
            }
        }
    }

    /**
     * Log the absolute path, file length, and declared {@code DAEMON_VERSION}
     * of the daemon.js we're about to launch. Critical for diagnosing the
     * "I see the new plugin code but a stale daemon is responding" class of
     * bugs on Windows.
     */
    private void logDaemonScriptIdentity(File daemonScript) {
        try {
            long len = daemonScript.length();
            String version = "<unparsed>";
            try (java.io.FileInputStream in = new java.io.FileInputStream(daemonScript)) {
                byte[] buf = new byte[8192];
                int read = 0;
                int n;
                while (read < buf.length && (n = in.read(buf, read, buf.length - read)) > 0) {
                    read += n;
                }
                if (read > 0) {
                    String head = new String(buf, 0, read, StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "DAEMON_VERSION\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(head);
                    if (m.find()) version = m.group(1);
                }
            }
            LOG.warn("[LocalBridge] About to spawn daemon: path=" + daemonScript.getAbsolutePath()
                    + " size=" + len + " bytes, DAEMON_VERSION='" + version + "'");
        } catch (Exception e) {
            LOG.warn("[LocalBridge] Could not inspect daemon.js identity: " + e.getMessage());
        }
    }

    /**
     * Whether the most recently-started daemon is fresh enough to handle all
     * features. Treats a daemon as stale if it does not advertise
     * {@code supervisorSupport=true}; the exact version string can describe any
     * newer bridge feature set (for example "session-resume"), so capability is
     * the source of truth.
     */
    private boolean isDaemonHealthy() {
        Boolean supSupport = reportedSupervisorSupport.get();
        return supSupport != null && supSupport;
    }

    /**
     * Stop the daemon process gracefully.
     */
    @Override
    public void stop() {
        LOG.info("[LocalBridge] Stopping daemon...");
        isRunning.set(false);

        // Cancel all pending requests
        for (Map.Entry<String, RequestHandler> entry : pendingRequests.entrySet()) {
            entry.getValue().onError("Daemon stopped");
        }
        pendingRequests.clear();
        activeRequestCount.set(0);

        // Send shutdown command before closing stdin (allows daemon to flush)
        try {
            if (daemonStdin != null) {
                JsonObject shutdown = new JsonObject();
                shutdown.addProperty("id", "shutdown");
                shutdown.addProperty("method", "shutdown");
                synchronized (daemonStdin) {
                    daemonStdin.write(shutdown.toString());
                    daemonStdin.newLine();
                    daemonStdin.flush();
                }
            }
        } catch (IOException e) {
            LOG.debug("[LocalBridge] Error sending shutdown command: " + e.getMessage());
        }

        // Close stdin (triggers daemon shutdown if command wasn't received)
        try {
            if (daemonStdin != null) {
                daemonStdin.close();
            }
        } catch (IOException e) {
            LOG.debug("[LocalBridge] Error closing stdin: " + e.getMessage());
        }

        // Kill process if still alive and wait for termination
        if (daemonProcess != null && daemonProcess.isAlive()) {
            daemonProcess.destroyForcibly();
            try { daemonProcess.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Interrupt and join threads
        if (readerThread != null) {
            readerThread.interrupt();
            try { readerThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            try { heartbeatThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        LOG.info("[LocalBridge] Daemon stopped");
    }

    /**
     * Send an abort command to cancel the currently executing request.
     * The abort bypasses the daemon's command queue and is processed immediately.
     * Also completes all pending request futures so Java-side blocking calls unblock.
     */
    @Override
    public void sendAbort() {
        // Send abort command to daemon so it stops the active SDK query
        try {
            if (daemonStdin != null && isRunning.get()) {
                JsonObject abort = new JsonObject();
                abort.addProperty("id", "abort-" + System.currentTimeMillis());
                abort.addProperty("method", "abort");
                synchronized (daemonStdin) {
                    daemonStdin.write(abort.toString());
                    daemonStdin.newLine();
                    daemonStdin.flush();
                }
                LOG.info("[LocalBridge] Sent abort command");
            }
        } catch (IOException e) {
            LOG.debug("[LocalBridge] Error sending abort command: " + e.getMessage());
        }

        // Complete all pending request futures so Java-side callers unblock
        for (Map.Entry<String, RequestHandler> entry : pendingRequests.entrySet()) {
            entry.getValue().onError("Request aborted by user");
            entry.getValue().future.complete(false);
        }
        pendingRequests.clear();
        activeRequestCount.set(0);
    }

    /**
     * Check if the daemon is running and healthy.
     */
    @Override
    public boolean isAlive() {
        return isRunning.get() && daemonProcess != null && daemonProcess.isAlive();
    }

    /**
     * Ensure the daemon is running, starting it if necessary.
     */
    @Override
    public boolean ensureRunning() {
        if (isAlive()) return true;
        return start();
    }

    // =========================================================================
    // Request Execution
    // =========================================================================

    /**
     * Send a command to the daemon and process output lines via callback.
     *
     * This method is non-blocking. Output lines are delivered to the callback
     * as they arrive from the daemon. The returned future completes when the
     * daemon signals "done" for this request.
     *
     * @param method   Command method (e.g., "claude.send")
     * @param params   Command parameters (JSON object)
     * @param callback Callback for processing output lines
     * @return CompletableFuture that completes when the command finishes
     */
    @Override
    public CompletableFuture<Boolean> sendCommand(
            String method,
            JsonObject params,
            IBridge.DaemonOutputCallback callback
    ) {
        if (!ensureRunning()) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(new IOException("Daemon not running"));
            return f;
        }

        String requestId = String.valueOf(requestIdCounter.incrementAndGet());
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean countsAsActiveRequest = !"heartbeat".equals(method) && !"status".equals(method);

        RequestHandler handler = new RequestHandler(callback, future);
        pendingRequests.put(requestId, handler);
        if (countsAsActiveRequest) {
            activeRequestCount.incrementAndGet();
        }
        markDaemonActivity();

        // Ensure cleanup when future completes (e.g., via timeout or cancellation)
        future.whenComplete((result, ex) -> {
            pendingRequests.remove(requestId);
            if (countsAsActiveRequest) {
                activeRequestCount.updateAndGet(current -> Math.max(0, current - 1));
            }
        });

        // Build request JSON
        JsonObject request = new JsonObject();
        request.addProperty("id", requestId);
        request.addProperty("method", method);
        request.add("params", params);

        try {
            synchronized (daemonStdin) {
                daemonStdin.write(request.toString());
                daemonStdin.newLine();
                daemonStdin.flush();
            }
            LOG.info("[LocalBridge] Sent request " + requestId + ": " + method);
        } catch (IOException e) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(e);
            LOG.error("[LocalBridge] Failed to send request: " + e.getMessage());
        }

        return future;
    }

    // =========================================================================
    // Reader Threads
    // =========================================================================

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(daemonProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleDaemonOutput(line);
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    LOG.error("[LocalBridge] Reader thread error: " + e.getMessage());
                }
            } finally {
                handleDaemonDeath();
            }
        }, "LocalBridge-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void startStderrReaderThread() {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(daemonProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug("[LocalBridge:stderr] " + line);
                }
            } catch (IOException e) {
                // Expected on shutdown
            }
        }, "LocalBridge-Stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void startHeartbeatThread() {
        // Initialize heartbeat baseline so the first check doesn't trigger timeout
        long now = System.currentTimeMillis();
        lastHeartbeatResponse.set(now);
        lastDaemonActivity.set(now);

        heartbeatThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!isAlive()) break;

                    // Check if daemon is unresponsive (no heartbeat response for too long)
                    long currentTime = System.currentTimeMillis();
                    long heartbeatAgeMs = currentTime - lastHeartbeatResponse.get();
                    long activityAgeMs = currentTime - lastDaemonActivity.get();
                    int activeRequests = activeRequestCount.get();
                    if (shouldTreatAsUnresponsive(heartbeatAgeMs, activityAgeMs, activeRequests)) {
                        LOG.warn("[LocalBridge] Daemon unresponsive (heartbeatAgeMs=" + heartbeatAgeMs
                                + ", activityAgeMs=" + activityAgeMs
                                + ", activeRequests=" + activeRequests + "), treating as dead");
                        handleDaemonDeath();
                        break;
                    }

                    // Send heartbeat
                    JsonObject hb = new JsonObject();
                    hb.addProperty("id", "hb-" + System.currentTimeMillis());
                    hb.addProperty("method", "heartbeat");
                    synchronized (daemonStdin) {
                        daemonStdin.write(hb.toString());
                        daemonStdin.newLine();
                        daemonStdin.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    LOG.warn("[LocalBridge] Heartbeat failed: " + e.getMessage());
                    handleDaemonDeath();
                    break;
                }
            }
        }, "LocalBridge-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    // =========================================================================
    // Output Parsing
    // =========================================================================

    private void handleDaemonOutput(String jsonLine) {
        markDaemonActivity();
        // Skip non-JSON lines (SDK debug output, permission logs, etc.)
        String trimmed = jsonLine.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            LOG.debug("[LocalBridge] Non-JSON output: " + trimmed);
            return;
        }

        try {
            JsonElement element = JsonParser.parseString(trimmed);
            if (!element.isJsonObject()) return;
            JsonObject obj = element.getAsJsonObject();

            // --- Daemon lifecycle events ---
            if (obj.has("type")) {
                String type = obj.get("type").getAsString();

                if ("daemon".equals(type)) {
                    handleDaemonEvent(obj);
                    return;
                }

                if ("heartbeat".equals(type)) {
                    // Heartbeat response — daemon is alive
                    lastHeartbeatResponse.set(System.currentTimeMillis());
                    markDaemonActivity();
                    return;
                }

                if ("status".equals(type)) {
                    // Status response
                    return;
                }
            }

            // --- Request-tagged output ---
            if (!obj.has("id")) return;
            String id = obj.get("id").getAsString();

            // Skip heartbeat responses
            if (id.startsWith("hb-")) return;

            RequestHandler handler = pendingRequests.get(id);
            if (handler == null) {
                LOG.debug("[LocalBridge] No handler for request " + id);
                return;
            }

            // Command completion
            if (obj.has("done")) {
                boolean success = obj.has("success") && obj.get("success").getAsBoolean();
                if (!success && obj.has("error")) {
                    handler.onError(obj.get("error").getAsString());
                }
                handler.onComplete(success);
                pendingRequests.remove(id);
                return;
            }

            // Output line from the command
            if (obj.has("line")) {
                handler.callback.onLine(obj.get("line").getAsString());
                return;
            }

            // Stderr output
            if (obj.has("stderr")) {
                handler.callback.onStderr(obj.get("stderr").getAsString());
            }

        } catch (Exception e) {
            LOG.error("[LocalBridge] Failed to parse daemon output: " + jsonLine, e);
        }
    }

    private void handleDaemonEvent(JsonObject obj) {
        String event = obj.has("event") ? obj.get("event").getAsString() : "unknown";
        LOG.info("[LocalBridge] Daemon event: " + event);

        switch (event) {
            case "starting":
                // Surface daemon version + capability flags early so stale-bridge
                // diagnoses don't require digging through file contents.
                String startingVersion = obj.has("version") ? obj.get("version").getAsString() : null;
                LOG.info("[LocalBridge] Daemon starting, version=" + (startingVersion != null ? startingVersion : "<absent>"));
                if (startingVersion != null) {
                    reportedDaemonVersion.set(startingVersion);
                }
                break;

            case "ready":
                if (obj.has("sdkPreloaded")) {
                    sdkPreloaded.set(obj.get("sdkPreloaded").getAsBoolean());
                }
                String readyVersion = obj.has("version") ? obj.get("version").getAsString() : null;
                if (readyVersion != null) {
                    reportedDaemonVersion.set(readyVersion);
                }
                // Newer daemons report supervisor capability explicitly. Absence
                // of this field means the running daemon predates the supervisor
                // channel — start() will self-heal if so.
                Boolean supervisorSupport = obj.has("supervisorSupport")
                        ? obj.get("supervisorSupport").getAsBoolean()
                        : null;
                reportedSupervisorSupport.set(supervisorSupport);
                if (supervisorSupport == null || !supervisorSupport) {
                    LOG.warn("[LocalBridge] Daemon ready but supervisorSupport=" + supervisorSupport
                            + (readyVersion != null ? " (version=" + readyVersion + ")" : "")
                            + ". Will trigger self-heal.");
                }
                readyLatch.countDown();
                if (lifecycleListener != null) {
                    lifecycleListener.onDaemonReady();
                }
                break;

            case "sdk_loaded":
                sdkPreloaded.set(true);
                LOG.info("[LocalBridge] SDK pre-loaded successfully");
                break;

            case "sdk_load_error":
                String error = obj.has("error") ? obj.get("error").getAsString() : "unknown";
                LOG.warn("[LocalBridge] SDK pre-load failed: " + error);
                break;

            case "shutdown":
                LOG.info("[LocalBridge] Daemon shutting down");
                break;

            default:
                LOG.debug("[LocalBridge] Unhandled daemon event: " + event);
        }
    }

    // =========================================================================
    // Daemon Death & Auto-Restart
    // =========================================================================

    private void handleDaemonDeath() {
        if (!isRunning.compareAndSet(true, false)) return;

        LOG.warn("[LocalBridge] Daemon process died");

        // Forcefully kill the old process if still alive (e.g., heartbeat timeout)
        Process oldProcess = daemonProcess;
        if (oldProcess != null && oldProcess.isAlive()) {
            LOG.info("[LocalBridge] Forcefully killing unresponsive daemon process (PID: "
                    + oldProcess.pid() + ")");
            oldProcess.destroyForcibly();
            try { oldProcess.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Fail all pending requests
        for (Map.Entry<String, RequestHandler> entry : pendingRequests.entrySet()) {
            entry.getValue().onError("Daemon process died unexpectedly");
        }
        pendingRequests.clear();
        activeRequestCount.set(0);

        // Notify listener
        if (lifecycleListener != null) {
            lifecycleListener.onDaemonDied();
        }

        // Auto-restart if within limit.
        // If the daemon ran stably for RESTART_WINDOW_MS before dying, reset the
        // counter so transient failures don't exhaust attempts permanently.
        long uptime = System.currentTimeMillis() - lastSuccessfulStart.get();
        if (uptime > RESTART_WINDOW_MS) {
            restartAttempts.set(0);
        }

        int attempts = restartAttempts.incrementAndGet();
        if (attempts <= MAX_RESTART_ATTEMPTS) {
            LOG.info("[LocalBridge] Attempting restart (" + attempts + "/" + MAX_RESTART_ATTEMPTS
                    + ", last uptime=" + uptime + "ms)");
            start();
        } else {
            LOG.error("[LocalBridge] Max restart attempts reached (" + attempts
                    + " within " + RESTART_WINDOW_MS + "ms window). Daemon will not be restarted.");
        }
    }

    // =========================================================================
    // Setters
    // =========================================================================

    @Override
    public void setLifecycleListener(IBridge.DaemonLifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    @Override
    public boolean isSdkPreloaded() {
        return sdkPreloaded.get();
    }

    // setControlMessageHandler — uses default no-op from IBridge interface.
    // Local mode drives permission/ask/plan via file IPC, not _ctrl messages.

    static boolean shouldTreatAsUnresponsive(long heartbeatAgeMs, long activityAgeMs, int activeRequestCount) {
        if (activeRequestCount <= 0) {
            return heartbeatAgeMs > HEARTBEAT_TIMEOUT_MS;
        }
        long livenessAgeMs = Math.min(heartbeatAgeMs, activityAgeMs);
        return livenessAgeMs > ACTIVE_REQUEST_HEARTBEAT_TIMEOUT_MS;
    }

    private void markDaemonActivity() {
        lastDaemonActivity.set(System.currentTimeMillis());
    }

    // =========================================================================
    // Inner Types (private — public ones moved to IBridge)
    // =========================================================================

    /**
     * Internal handler that wraps callback + future for a pending request.
     */
    private static class RequestHandler {
        final IBridge.DaemonOutputCallback callback;
        final CompletableFuture<Boolean> future;

        RequestHandler(IBridge.DaemonOutputCallback callback, CompletableFuture<Boolean> future) {
            this.callback = callback;
            this.future = future;
        }

        void onError(String error) {
            callback.onError(error);
            future.completeExceptionally(new RuntimeException(error));
        }

        void onComplete(boolean success) {
            callback.onComplete(success);
            future.complete(success);
        }
    }
}
