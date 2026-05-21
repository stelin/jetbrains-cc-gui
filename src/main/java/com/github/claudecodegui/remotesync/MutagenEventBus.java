package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.remotesync.model.SyncStatus;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level pub/sub for sync status. The status bar (shared by every
 * chat tab) and the settings panel both subscribe; whichever screen is open
 * gets fresh updates.
 */
@Service(Service.Level.APP)
public final class MutagenEventBus {

    public static MutagenEventBus getInstance() {
        return ApplicationManager.getApplication().getService(MutagenEventBus.class);
    }

    public interface Listener {
        void onStatus(SyncStatus status);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile SyncStatus last = SyncStatus.disabled();

    public void subscribe(Listener l) {
        listeners.add(l);
        // Hand the new subscriber the most recent status synchronously.
        l.onStatus(last);
    }

    public void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    public SyncStatus current() {
        return last;
    }

    public void publish(SyncStatus status) {
        if (status == null) return;
        // Always notify — even when nothing 'meaningful' changed, downstream
        // consumers want a fresh updatedAt timestamp so the UI can show that
        // sync is still alive. The frontend dedups its own activity log via a
        // signature so this doesn't produce spam there either.
        last = status;
        for (Listener l : listeners) {
            try { l.onStatus(status); } catch (Exception ignored) {}
        }
    }
}
