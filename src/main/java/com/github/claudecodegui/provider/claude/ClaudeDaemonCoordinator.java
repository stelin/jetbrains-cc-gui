package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.permission.ControlMessageHandler;
import com.github.claudecodegui.provider.common.IBridge;
import com.github.claudecodegui.provider.common.LocalBridge;
import com.github.claudecodegui.provider.common.RemoteBridge;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns daemon lifecycle, retry windows, prewarm, and runtime reset operations.
 */
class ClaudeDaemonCoordinator {

    private static final long DAEMON_RETRY_DELAY_MS = 60_000;

    private final Logger log;
    private final NodeDetector nodeDetector;
    private final Supplier<BridgeDirectoryResolver> directoryResolverSupplier;
    private final EnvironmentConfigurator envConfigurator;
    private final Project project;        // null when caller has no project context

    private volatile IBridge daemonBridge;
    private final Object daemonLock = new Object();
    private volatile long daemonRetryAfter = 0;
    private volatile CompletableFuture<?> prewarmFuture;
    private volatile ControlMessageHandler controlMessageHandler;
    private volatile IBridge.DaemonLifecycleListener lifecycleListener;
    private volatile String lastStartFailureCode;
    private volatile String lastStartFailureMessage;

    /** Last RemoteBridge start failure, for callers that surface UI errors. */
    public String getLastStartFailureCode()    { return lastStartFailureCode; }
    public String getLastStartFailureMessage() { return lastStartFailureMessage; }

    ClaudeDaemonCoordinator(
            Logger log,
            NodeDetector nodeDetector,
            Supplier<BridgeDirectoryResolver> directoryResolverSupplier,
            EnvironmentConfigurator envConfigurator
    ) {
        this(log, nodeDetector, directoryResolverSupplier, envConfigurator, null);
    }

    ClaudeDaemonCoordinator(
            Logger log,
            NodeDetector nodeDetector,
            Supplier<BridgeDirectoryResolver> directoryResolverSupplier,
            EnvironmentConfigurator envConfigurator,
            Project project
    ) {
        this.log = log;
        this.nodeDetector = nodeDetector;
        this.directoryResolverSupplier = directoryResolverSupplier;
        this.envConfigurator = envConfigurator;
        this.project = project;
    }

    IBridge getDaemonBridge() {
        IBridge current = daemonBridge;
        if (current != null && current.isAlive()) {
            return current;
        }
        if (System.currentTimeMillis() < daemonRetryAfter) {
            return null;
        }

        synchronized (daemonLock) {
            current = daemonBridge;
            if (current != null && current.isAlive()) {
                return current;
            }

            daemonRetryAfter = System.currentTimeMillis() + DAEMON_RETRY_DELAY_MS;
            try {
                if (current != null) {
                    current.stop();
                }

                IBridge newBridge;
                RemoteModeContext rmCtx = RemoteModeContext.getInstance();
                if (rmCtx != null && rmCtx.isRemote()) {
                    String url = rmCtx.remoteServerUrl();
                    log.info("[DaemonCoordinator] Remote mode enabled, baseUrl=" + url
                            + " project=" + (project != null ? project.getBasePath() : "<none>"));
                    newBridge = new RemoteBridge(url, project);
                } else {
                    newBridge = new LocalBridge(
                            nodeDetector,
                            directoryResolverSupplier.get(),
                            envConfigurator
                    );
                }
                ControlMessageHandler handler = controlMessageHandler;
                if (handler != null) {
                    newBridge.setControlMessageHandler(handler);
                }
                IBridge.DaemonLifecycleListener ll = lifecycleListener;
                if (ll != null) {
                    try { newBridge.setLifecycleListener(ll); }
                    catch (Exception e) { log.debug("setLifecycleListener failed: " + e.getMessage()); }
                }
                if (newBridge.start()) {
                    daemonBridge = newBridge;
                    daemonRetryAfter = 0;
                    lastStartFailureCode = null;
                    lastStartFailureMessage = null;
                    log.info("[DaemonCoordinator] Daemon bridge started successfully");
                    return newBridge;
                }
                // Capture the structured failure from RemoteBridge so callers
                // can show the actual reason instead of a generic fallback msg.
                if (newBridge instanceof RemoteBridge) {
                    RemoteBridge rb = (RemoteBridge) newBridge;
                    lastStartFailureCode = rb.getLastStartFailureCode();
                    lastStartFailureMessage = rb.getLastStartFailureMessage();
                }
                log.warn("[DaemonCoordinator] Failed to start daemon"
                        + (lastStartFailureCode != null ? " code=" + lastStartFailureCode : "")
                        + (lastStartFailureMessage != null ? " msg=" + lastStartFailureMessage : "")
                        + ", using per-process mode");
            } catch (Exception e) {
                log.debug("[DaemonCoordinator] Daemon init failed: " + e.getMessage());
            }
            return null;
        }
    }

    IBridge getCurrentDaemonBridge() {
        return daemonBridge;
    }

    /**
     * Inject the control-message handler used by remote-mode bridges. Safe to
     * call before or after {@link #getDaemonBridge()} — applied to any
     * existing bridge and remembered for future bridge instances.
     */
    void setControlMessageHandler(ControlMessageHandler handler) {
        this.controlMessageHandler = handler;
        IBridge current = daemonBridge;
        if (current != null) {
            try { current.setControlMessageHandler(handler); }
            catch (Exception e) { log.debug("setControlMessageHandler failed: " + e.getMessage()); }
        }
    }

    /**
     * Inject a daemon lifecycle listener (ready / died). Like
     * {@link #setControlMessageHandler}: applied to any existing bridge and
     * remembered for future bridge instances. Used by DN9 (§16.5) so a
     * supervisor pair's dead daemon funnels its workflow node to WAITING_HUMAN.
     */
    void setLifecycleListener(IBridge.DaemonLifecycleListener listener) {
        this.lifecycleListener = listener;
        IBridge current = daemonBridge;
        if (current != null) {
            try { current.setLifecycleListener(listener); }
            catch (Exception e) { log.debug("setLifecycleListener failed: " + e.getMessage()); }
        }
    }

    void shutdownDaemon() {
        CompletableFuture<?> runningPrewarm = prewarmFuture;
        if (runningPrewarm != null) {
            runningPrewarm.cancel(true);
            prewarmFuture = null;
        }

        IBridge current = daemonBridge;
        if (current != null) {
            current.stop();
            daemonBridge = null;
        }
        // Always clear the retry cooldown — a config change (path mapping,
        // remote URL, etc.) should let the very next request retry, even when
        // the previous start() left daemonBridge null due to failure.
        daemonRetryAfter = 0;
        lastStartFailureCode = null;
        lastStartFailureMessage = null;
    }

    void prewarmDaemonAsync(String cwd, String runtimeSessionEpoch) {
        // 2026-05-24: previously early-returned in remote mode with "credentials
        // live on the server" — but claude.preconnect is daemon-side auth +
        // SDK load, the client doesn't ship any credentials. Skipping prewarm
        // pushed the entire RemoteBridge.start() (HTTP POST /session + 30s
        // readyLatch.await) plus the SDK first-load onto the FIRST daemon RPC,
        // which in the supervisor pair_start path runs on the JCEF callback
        // thread and stalls the webview IPC for 30-60s. Prewarm runs on a
        // background pool so the cost is paid silently at project open.
        // Outbound path fields for claude.preconnect (cwd / env.*) are
        // registered in PathFields.OUTBOUND, so the local cwd argument is
        // translated to the remote form before transport.
        CompletableFuture<?> previous = prewarmFuture;
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }

        prewarmFuture = CompletableFuture.runAsync(() -> {
            try {
                IBridge daemon = getDaemonBridge();
                if (daemon == null) {
                    log.info("[DaemonCoordinator] Daemon prewarm skipped (daemon unavailable)");
                    return;
                }

                JsonObject params = new JsonObject();
                params.addProperty("cwd", cwd != null ? cwd : "");
                params.addProperty("sessionId", "");
                params.addProperty("runtimeSessionEpoch", runtimeSessionEpoch != null ? runtimeSessionEpoch : "");
                params.addProperty("permissionMode", "");
                params.addProperty("model", "");
                params.addProperty("streaming", true);
                params.add("env", ClaudeBridgeUtils.buildDaemonEnv(cwd));

                CompletableFuture<Boolean> preconnectFuture = daemon.sendCommand(
                        "claude.preconnect",
                        params,
                        new IBridge.DaemonOutputCallback() {
                            @Override
                            public void onLine(String line) {
                                if (line.startsWith("[SEND_ERROR]")) {
                                    log.warn("[DaemonCoordinator] Daemon preconnect error line: " + line);
                                }
                            }

                            @Override
                            public void onStderr(String text) {
                                log.debug("[DaemonCoordinator] Daemon preconnect stderr: " + text);
                            }

                            @Override
                            public void onError(String error) {
                                log.warn("[DaemonCoordinator] Daemon preconnect failed: " + error);
                            }

                            @Override
                            public void onComplete(boolean success) {
                                log.info("[DaemonCoordinator] Daemon preconnect completed: success=" + success);
                            }
                        }
                );

                preconnectFuture.get(45, TimeUnit.SECONDS);
                log.info("[DaemonCoordinator] Daemon prewarm completed for epoch="
                        + (runtimeSessionEpoch != null ? runtimeSessionEpoch : "(none)"));
            } catch (Exception e) {
                log.debug("[DaemonCoordinator] Daemon prewarm failed: " + e.getMessage());
            }
        });
    }

    void resetPersistentRuntime(String runtimeSessionEpoch) {
        IBridge daemon = daemonBridge;
        if (daemon == null || !daemon.isAlive()) {
            log.info("[DaemonCoordinator] Skip runtime reset; daemon unavailable for epoch="
                    + (runtimeSessionEpoch != null ? runtimeSessionEpoch : "(none)"));
            return;
        }

        try {
            JsonObject params = new JsonObject();
            params.addProperty("runtimeSessionEpoch", runtimeSessionEpoch != null ? runtimeSessionEpoch : "");
            CompletableFuture<Boolean> resetFuture = daemon.sendCommand(
                    "claude.resetRuntime",
                    params,
                    new IBridge.DaemonOutputCallback() {
                        @Override
                        public void onLine(String line) {
                            if (line != null && !line.isBlank()) {
                                log.info("[DaemonCoordinator] Runtime reset line: " + line);
                            }
                        }

                        @Override
                        public void onStderr(String text) {
                            if (text != null && !text.isBlank()) {
                                log.debug("[DaemonCoordinator] Runtime reset stderr: " + text);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            log.warn("[DaemonCoordinator] Runtime reset error: " + error);
                        }

                        @Override
                        public void onComplete(boolean success) {
                            log.info("[DaemonCoordinator] Runtime reset completed: success=" + success
                                    + ", epoch=" + (runtimeSessionEpoch != null ? runtimeSessionEpoch : "(none)"));
                        }
                    }
            );
            resetFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[DaemonCoordinator] Runtime reset failed: " + e.getMessage());
        }
    }

}
