package com.github.claudecodegui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level toggle for auto-reloading the IDE's VFS when the AI
 * server reports that files were added / deleted / modified. Default off.
 *
 * <p>Only takes effect in remote mode — in local mode IntelliJ's own
 * FSNotifier already picks up changes from the local SDK process.
 */
@State(
    name = "CCGAutoReloadSettings",
    storages = @Storage("ccgAutoReload.xml")
)
@Service(Service.Level.APP)
public final class AutoReloadSettings implements PersistentStateComponent<AutoReloadSettings.State> {

    public static class State {
        public boolean enabled = false;

        /**
         * Sliding debounce window in <b>milliseconds</b>. Repeated reload
         * requests within this window are coalesced into a single VFS refresh
         * that fires after the last call. Tune up if a big monorepo's refresh
         * IO dominates the perceived latency; tune down for snappier feedback.
         */
        public long debounceMs = 200L;
    }

    private State myState = new State();

    public static AutoReloadSettings getInstance() {
        return ApplicationManager.getApplication().getService(AutoReloadSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public boolean isEnabled() {
        return myState.enabled;
    }

    public void setEnabled(boolean enabled) {
        myState.enabled = enabled;
    }

    /** Debounce window, in milliseconds. Always returns a positive value. */
    public long getDebounceMs() {
        return myState.debounceMs > 0 ? myState.debounceMs : 200L;
    }

    public void setDebounceMs(long debounceMs) {
        myState.debounceMs = debounceMs > 0 ? debounceMs : 200L;
    }
}
