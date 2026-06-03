package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.remotesync.KnownHostsManager;
import com.github.claudecodegui.remotesync.MutagenBinary;
import com.github.claudecodegui.remotesync.MutagenEventBus;
import com.github.claudecodegui.remotesync.MutagenInstaller;
import com.github.claudecodegui.remotesync.MutagenMonitor;
import com.github.claudecodegui.remotesync.MutagenSyncService;
import com.github.claudecodegui.remotesync.SyncCredentialsStore;
import com.github.claudecodegui.remotesync.model.HostKeyChallenge;
import com.github.claudecodegui.remotesync.model.MutagenSdkStatus;
import com.github.claudecodegui.remotesync.model.SyncStatus;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges the "remote sync" settings tab to the underlying mutagen services.
 * Owns one {@link MutagenEventBus} subscription per WebView so status updates
 * stream straight into the page.
 */
public class RemoteSyncHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(RemoteSyncHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "get_remote_sync_state",
            "download_mutagen",
            "cancel_mutagen_download",
            "set_remote_sync_github_proxy",
            "set_remote_sync_config",
            "set_remote_sync_password",
            "test_remote_sync_connection",
            "start_remote_sync",
            "stop_remote_sync",
            "pause_remote_sync",
            "resume_remote_sync",
            "confirm_host_key",
            "get_remote_sync_diagnostics"
    };

    private final Gson gson = new Gson();
    private final Map<String, CompletableFuture<Boolean>> pendingHostKeys = new ConcurrentHashMap<>();
    private final MutagenEventBus.Listener busListener;

    public RemoteSyncHandler(HandlerContext context) {
        super(context);
        // Forward live mutagen status into the webview as it arrives.
        this.busListener = status -> pushSyncStatus(status);
        MutagenEventBus.getInstance().subscribe(busListener);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_remote_sync_state":
                handleGetState();
                return true;
            case "download_mutagen":
                handleDownload();
                return true;
            case "cancel_mutagen_download":
                handleCancelDownload();
                return true;
            case "set_remote_sync_github_proxy":
                handleSetGithubProxy(content);
                return true;
            case "set_remote_sync_config":
                handleSetConfig(content);
                return true;
            case "set_remote_sync_password":
                handleSetPassword(content);
                return true;
            case "test_remote_sync_connection":
                handleTest();
                return true;
            case "start_remote_sync":
                handleStart();
                return true;
            case "stop_remote_sync":
                handleStop();
                return true;
            case "pause_remote_sync":
                handlePause();
                return true;
            case "resume_remote_sync":
                handleResume();
                return true;
            case "confirm_host_key":
                handleConfirmHostKey(content);
                return true;
            case "get_remote_sync_diagnostics":
                handleDiagnostics();
                return true;
            default:
                return false;
        }
    }

    private void handleGetState() {
        pushFullState();
    }

    /* ============================== SDK download ============================== */

    private void handleDownload() {
        MutagenInstaller installer = MutagenInstaller.getInstance();
        String proxy = settings().getRemoteSyncGithubProxy();
        String url = MutagenInstaller.archiveUrl(proxy);
        if (installer.isDownloading()) {
            LOG.info("[RemoteSyncHandler] download already in progress");
            pushSdkStatus(MutagenSdkStatus.downloading(0, 0, MutagenInstaller.TARGET_VERSION, url));
            return;
        }

        LOG.info("[RemoteSyncHandler] starting mutagen download: " + url);
        pushSdkStatus(MutagenSdkStatus.downloading(0, 0, MutagenInstaller.TARGET_VERSION, url));

        installer.install(proxy, (read, total) ->
                pushSdkStatus(MutagenSdkStatus.downloading(read, total, MutagenInstaller.TARGET_VERSION, url))
        ).whenComplete((path, ex) -> {
            if (ex != null) {
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                LOG.warn("[RemoteSyncHandler] download failed: " + msg);
                pushSdkStatus(MutagenSdkStatus.error(msg, MutagenInstaller.TARGET_VERSION, url));
                return;
            }
            pushSdkStatus(currentSdkStatus());
        });
    }

    private void handleCancelDownload() {
        MutagenInstaller.getInstance().cancel();
    }

    /* ============================== config ============================== */

    private void handleSetGithubProxy(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String proxy = (json != null && json.has("githubProxy") && !json.get("githubProxy").isJsonNull())
                    ? json.get("githubProxy").getAsString() : "";
            settings().setRemoteSyncGithubProxy(proxy);
            pushSdkStatus(currentSdkStatus());
        } catch (Exception e) {
            LOG.warn("[RemoteSyncHandler] setGithubProxy failed: " + e.getMessage());
        }
    }

    private void handleSetConfig(String content) {
        try {
            JsonObject patch = gson.fromJson(content, JsonObject.class);
            if (patch == null) patch = new JsonObject();
            patch.remove("password");
            patch.remove("githubProxy");
            settings().updateRemoteSyncConfig(patch);
            pushFullState();
        } catch (Exception e) {
            LOG.warn("[RemoteSyncHandler] setConfig failed: " + e.getMessage());
        }
    }

    private void handleSetPassword(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String password = (json != null && json.has("password") && !json.get("password").isJsonNull())
                    ? json.get("password").getAsString() : "";
            if (password.isEmpty()) {
                SyncCredentialsStore.clear();
            } else {
                SyncCredentialsStore.save(password);
            }
            pushFullState();
        } catch (Exception e) {
            LOG.warn("[RemoteSyncHandler] setPassword failed: " + e.getMessage());
        }
    }

    /* ============================== sync lifecycle ============================== */

    private void handleTest() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                MutagenSyncService.FormData form = buildFormFromSettings();
                String validation = form.validate();
                if (validation != null) {
                    pushTestResult(false, validation);
                    return;
                }
                // Docker has no SSH host key to verify; only SSH transport prompts.
                if (!form.isDocker() && !ensureHostTrusted(form)) {
                    pushTestResult(false, "host key not trusted");
                    return;
                }
                MutagenSyncService.TestResult r = MutagenSyncService.getInstance()
                        .testConnection(form, SyncCredentialsStore.load()).join();
                pushTestResult(r.ok, r.message);
            } catch (Throwable t) {
                LOG.warn("[RemoteSyncHandler] handleTest failed", t);
                pushTestResult(false, exMessage(t));
            }
        });
    }

    private void handleStart() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                MutagenSyncService.FormData form = buildFormFromSettings();
                String validation = form.validate();
                if (validation != null) {
                    pushTestResult(false, validation);
                    return;
                }
                // Docker has no SSH host key to verify; only SSH transport prompts.
                if (!form.isDocker() && !ensureHostTrusted(form)) {
                    pushTestResult(false, "host key not trusted");
                    return;
                }
                MutagenEventBus.getInstance().publish(SyncStatus.starting(form.name));
                MutagenSyncService.TestResult r = MutagenSyncService.getInstance()
                        .start(form, SyncCredentialsStore.load()).join();
                if (r.ok) {
                    MutagenMonitor.getInstance().start(form.name);
                } else {
                    MutagenEventBus.getInstance().publish(SyncStatus.error(form.name, r.message));
                }
                pushTestResult(r.ok, r.message);
            } catch (Throwable t) {
                LOG.warn("[RemoteSyncHandler] handleStart failed", t);
                MutagenEventBus.getInstance().publish(SyncStatus.error("", exMessage(t)));
                pushTestResult(false, exMessage(t));
            }
        });
    }

    private static String exMessage(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String m = cause.getMessage();
        return (m == null || m.isEmpty()) ? cause.getClass().getSimpleName() : m;
    }

    private void handleStop() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String name = settings().getRemoteSyncConfig().get("name").getAsString();
            MutagenMonitor.getInstance().stop();
            MutagenSyncService.TestResult r = MutagenSyncService.getInstance().stop(name).join();
            pushTestResult(r.ok, r.message);
        });
    }

    private void handlePause() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String name = settings().getRemoteSyncConfig().get("name").getAsString();
            MutagenSyncService.TestResult r = MutagenSyncService.getInstance().pause(name).join();
            pushTestResult(r.ok, r.message);
        });
    }

    private void handleResume() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String name = settings().getRemoteSyncConfig().get("name").getAsString();
            MutagenSyncService.TestResult r = MutagenSyncService.getInstance().resume(name).join();
            pushTestResult(r.ok, r.message);
        });
    }

    private void handleDiagnostics() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String name = settings().getRemoteSyncConfig().get("name").getAsString();
            String text = com.github.claudecodegui.remotesync.MutagenSyncService.getInstance()
                    .diagnostics(name);
            JsonObject root = new JsonObject();
            JsonObject d = new JsonObject();
            d.addProperty("at", System.currentTimeMillis());
            d.addProperty("text", text);
            root.add("diagnostics", d);
            ApplicationManager.getApplication().invokeLater(() ->
                    callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
            );
        });
    }

    private void handleConfirmHostKey(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String id = json.has("id") ? json.get("id").getAsString() : "";
            boolean trust = json.has("trust") && json.get("trust").getAsBoolean();
            CompletableFuture<Boolean> pending = pendingHostKeys.remove(id);
            if (pending != null) pending.complete(trust);
        } catch (Exception e) {
            LOG.warn("[RemoteSyncHandler] confirmHostKey failed: " + e.getMessage());
        }
    }

    /**
     * Ensure the remote SSH host appears in {@code ~/.ssh/known_hosts}. When
     * absent, probes with {@code ssh-keyscan}, sends the fingerprint to the
     * webview, and blocks (up to 5 minutes) on the user's accept/reject reply.
     */
    private boolean ensureHostTrusted(MutagenSyncService.FormData form) {
        if (KnownHostsManager.isHostKnown(form.remoteHost, form.remotePort)) {
            return true;
        }
        HostKeyChallenge challenge = KnownHostsManager.probe(form.remoteHost, form.remotePort);
        if (challenge == null) {
            pushTestResult(false, "ssh-keyscan failed — host unreachable or ssh-keyscan missing");
            return false;
        }
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        pendingHostKeys.put(challenge.getId(), pending);
        pushHostKeyChallenge(challenge);
        try {
            Boolean trust = pending.get(5, TimeUnit.MINUTES);
            if (trust == null || !trust) return false;
            KnownHostsManager.trust(challenge);
            return true;
        } catch (TimeoutException te) {
            pendingHostKeys.remove(challenge.getId());
            pushTestResult(false, "host key confirmation timed out");
            return false;
        } catch (Exception e) {
            pendingHostKeys.remove(challenge.getId());
            pushTestResult(false, "host key error: " + e.getMessage());
            return false;
        }
    }

    private MutagenSyncService.FormData buildFormFromSettings() {
        JsonObject cfg = settings().getRemoteSyncConfig();
        MutagenSyncService.FormData f = new MutagenSyncService.FormData();
        f.name        = cfg.get("name").getAsString();
        f.localPath   = cfg.get("localPath").getAsString();
        f.remoteUser  = cfg.get("remoteUser").getAsString();
        f.remoteHost  = cfg.get("remoteHost").getAsString();
        f.remotePort  = cfg.get("remotePort").getAsInt();
        f.remotePath  = cfg.get("remotePath").getAsString();
        f.mode        = cfg.get("mode").getAsString();
        f.remoteOs    = cfg.has("remoteOs") ? cfg.get("remoteOs").getAsString() : "auto";
        f.transport   = cfg.has("transport") ? cfg.get("transport").getAsString() : "ssh";
        f.dockerContainer = cfg.has("dockerContainer") ? cfg.get("dockerContainer").getAsString() : "";
        return f;
    }

    /* ============================== status push ============================== */

    private MutagenSdkStatus currentSdkStatus() {
        String proxy = settings().getRemoteSyncGithubProxy();
        MutagenBinary bin = MutagenBinary.getInstance();
        if (!bin.isAvailable()) {
            return MutagenSdkStatus.missing(MutagenInstaller.TARGET_VERSION, MutagenInstaller.archiveUrl(proxy));
        }
        String version = bin.versionString();
        return MutagenSdkStatus.installed(
                version == null ? "?" : version,
                bin.executablePath().toString(),
                MutagenInstaller.TARGET_VERSION
        );
    }

    private void pushSdkStatus(MutagenSdkStatus status) {
        JsonObject root = new JsonObject();
        root.add("sdk", status.toJson());
        root.addProperty("githubProxy", settings().getRemoteSyncGithubProxy());
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
        );
    }

    private void pushSyncStatus(SyncStatus status) {
        JsonObject root = new JsonObject();
        root.add("syncStatus", status.toJson());
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
        );
    }

    private void pushTestResult(boolean ok, String message) {
        JsonObject root = new JsonObject();
        JsonObject r = new JsonObject();
        r.addProperty("ok", ok);
        r.addProperty("message", message == null ? "" : message);
        r.addProperty("at", System.currentTimeMillis());
        root.add("testResult", r);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
        );
    }

    private void pushHostKeyChallenge(HostKeyChallenge challenge) {
        JsonObject root = new JsonObject();
        root.add("hostKey", challenge.toJson());
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
        );
    }

    private void pushFullState() {
        JsonObject root = new JsonObject();
        root.add("sdk", currentSdkStatus().toJson());
        JsonObject cfg = settings().getRemoteSyncConfig();
        root.addProperty("githubProxy", cfg.has("githubProxy") ? cfg.get("githubProxy").getAsString() : "");
        root.add("config", cfg);
        root.addProperty("hasPassword", SyncCredentialsStore.exists());
        root.add("syncStatus", MutagenEventBus.getInstance().current().toJson());
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateRemoteSyncState", escapeJs(root.toString()))
        );
    }

    private CodemossSettingsService settings() {
        return context.getSettingsService();
    }
}
