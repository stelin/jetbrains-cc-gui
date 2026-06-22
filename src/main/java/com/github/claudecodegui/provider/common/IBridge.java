package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.permission.ControlMessageHandler;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Common contract for bridge implementations that talk to the AI daemon.
 *
 * Two implementations exist:
 *   - {@link LocalBridge}  — spawns a local Node.js daemon child process and
 *                            communicates via stdin/stdout NDJSON.
 *   - {@link RemoteBridge} — talks to an ai-bridge-server over HTTP + SSE
 *                            (POST {@code /session/{id}/in} and
 *                            GET {@code /session/{id}/events}).
 *
 * The interface is intentionally identical to the original {@code DaemonBridge}
 * public API, so existing callers do not need behavioural changes — they just
 * declare {@code IBridge} instead of the concrete class.
 */
public interface IBridge {

    /**
     * Start the bridge. Blocks until the daemon signals ready or timeout.
     *
     * @return true on success
     */
    boolean start();

    /**
     * Stop the bridge and clean up resources.
     */
    void stop();

    /**
     * Whether the bridge currently has a live, ready daemon.
     */
    boolean isAlive();

    /**
     * Start the bridge if not already alive. Safe to call repeatedly.
     */
    boolean ensureRunning();

    /**
     * Cancel any in-flight request immediately.
     */
    void sendAbort();

    /**
     * Send a request and stream output lines into the supplied callback.
     *
     * @param method    daemon method, e.g. {@code claude.send}, {@code heartbeat}
     * @param params    method-specific JSON parameters
     * @param callback  called for each output line / stderr / completion
     * @return future completed with success boolean (or completed exceptionally on transport failure)
     */
    CompletableFuture<Boolean> sendCommand(
            String method,
            JsonObject params,
            DaemonOutputCallback callback
    );

    /**
     * Subscribe to lifecycle events (ready / dead).
     */
    void setLifecycleListener(DaemonLifecycleListener listener);

    /**
     * Whether the daemon has finished pre-loading the SDK.
     */
    boolean isSdkPreloaded();

    /**
     * Inject a handler for {@code _ctrl} control messages from the daemon
     * (permission/ask/plan requests). Only consumed by remote-mode bridges;
     * local-mode implementations may use a no-op since file IPC is in effect.
     */
    default void setControlMessageHandler(ControlMessageHandler handler) {
        // default: no-op. Local mode uses file IPC for permission/ask/plan
        // and does not need this hook.
    }

    // =========================================================================
    // Inner Types (shared between Local and Remote implementations)
    // =========================================================================

    /**
     * Callback interface for receiving daemon output.
     */
    interface DaemonOutputCallback {
        void onLine(String line);
        void onStderr(String text);
        void onError(String error);
        void onComplete(boolean success);

        /**
         * No-op instance for fire-and-forget commands where the caller has no
         * interest in output (e.g. {@code claude.resetRuntime} scratch cleanup).
         */
        DaemonOutputCallback NOOP = new DaemonOutputCallback() {
            @Override public void onLine(String line) {}
            @Override public void onStderr(String text) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(boolean success) {}
        };
    }

    /**
     * Lifecycle listener for daemon events.
     */
    interface DaemonLifecycleListener {
        void onDaemonReady();
        void onDaemonDied();
    }

    /**
     * Optional extension of {@link DaemonLifecycleListener} that receives a
     * structured error code when the bridge fails to start. RemoteBridge uses
     * this to surface project-related errors (no project open, project path
     * not accessible on server, etc.) directly to the UI.
     *
     * <p>Listeners that don't implement this interface still receive a generic
     * {@link #onDaemonDied()} for backwards compatibility.
     */
    interface DaemonLifecycleListenerWithError extends DaemonLifecycleListener {
        /**
         * @param code machine-readable error tag (e.g. {@code PROJECT_NOT_OPEN},
         *             {@code PROJECT_PATH_NOT_ACCESSIBLE},
         *             {@code PROJECT_PATH_REQUIRED},
         *             {@code SESSION_CREATE_FAILED})
         * @param message human-readable message
         */
        void onDaemonStartFailed(String code, String message);
    }
}
