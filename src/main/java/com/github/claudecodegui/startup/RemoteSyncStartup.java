package com.github.claudecodegui.startup;

import com.github.claudecodegui.remotesync.MutagenBinary;
import com.github.claudecodegui.remotesync.MutagenDaemon;
import com.github.claudecodegui.remotesync.MutagenMonitor;
import com.github.claudecodegui.remotesync.MutagenSyncService;
import com.github.claudecodegui.remotesync.SyncCredentialsStore;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * If the user previously enabled remote sync, transparently resume the session
 * after the IDE restarts: ensure the daemon is up, resume (or recreate) the
 * mutagen sync session, and start the monitor that drives the status bar.
 *
 * <p>Guarded by an AtomicBoolean so multiple project-opens don't race.
 */
public class RemoteSyncStartup implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(RemoteSyncStartup.class);
    private static final AtomicBoolean RESUMED = new AtomicBoolean(false);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!RESUMED.compareAndSet(false, true)) {
            return Unit.INSTANCE;
        }
        ApplicationManager.getApplication().executeOnPooledThread(this::resume);
        return Unit.INSTANCE;
    }

    private void resume() {
        try {
            CodemossSettingsService settings = new CodemossSettingsService();
            JsonObject cfg = settings.getRemoteSyncConfig();
            if (!cfg.get("enabled").getAsBoolean()) {
                LOG.info("[RemoteSyncStartup] sync disabled — skip");
                return;
            }
            if (!MutagenBinary.getInstance().isAvailable()) {
                LOG.info("[RemoteSyncStartup] mutagen not installed — skip");
                return;
            }
            MutagenSyncService.FormData form = new MutagenSyncService.FormData();
            form.name        = cfg.get("name").getAsString();
            form.localPath   = cfg.get("localPath").getAsString();
            form.remoteUser  = cfg.get("remoteUser").getAsString();
            form.remoteHost  = cfg.get("remoteHost").getAsString();
            form.remotePort  = cfg.get("remotePort").getAsInt();
            form.remotePath  = cfg.get("remotePath").getAsString();
            form.mode        = cfg.get("mode").getAsString();
            form.remoteOs    = cfg.has("remoteOs") ? cfg.get("remoteOs").getAsString() : "auto";
            String err = form.validate();
            if (err != null) {
                LOG.warn("[RemoteSyncStartup] config invalid: " + err);
                return;
            }
            if (!MutagenDaemon.getInstance().ensureRunning()) {
                LOG.warn("[RemoteSyncStartup] daemon start failed");
                return;
            }
            String password = SyncCredentialsStore.load();
            MutagenSyncService svc = MutagenSyncService.getInstance();
            if (svc.sessionExists(form.name)) {
                MutagenSyncService.TestResult r = svc.resume(form.name).join();
                LOG.info("[RemoteSyncStartup] resume: " + r.ok + " " + r.message);
            } else if (password != null) {
                MutagenSyncService.TestResult r = svc.start(form, password).join();
                LOG.info("[RemoteSyncStartup] start: " + r.ok + " " + r.message);
            } else {
                LOG.info("[RemoteSyncStartup] no saved password — skip auto-start");
                return;
            }
            MutagenMonitor.getInstance().start(form.name);
        } catch (Exception e) {
            LOG.warn("[RemoteSyncStartup] resume failed: " + e.getMessage());
        }
    }
}
