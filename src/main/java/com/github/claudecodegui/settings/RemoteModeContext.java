package com.github.claudecodegui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Single source of truth for "is the plugin running in remote mode?".
 *
 * <p>Used by UI components to decide whether to grey out / hide features that
 * aren't supported when the daemon lives on a remote {@code ai-bridge-server}:
 * Settings editing, MCP/Skills/Provider management, Checkpoint/Rewind, file
 * attachments. The flag is read from {@link CodemossSettingsService} on each
 * call so it picks up runtime changes without requiring a restart.
 */
@Service(Service.Level.APP)
public final class RemoteModeContext {

    private static final Logger LOG = Logger.getInstance(RemoteModeContext.class);

    public static RemoteModeContext getInstance() {
        return ApplicationManager.getApplication().getService(RemoteModeContext.class);
    }

    private volatile CodemossSettingsService settings;

    private CodemossSettingsService settings() {
        CodemossSettingsService s = settings;
        if (s == null) {
            synchronized (this) {
                s = settings;
                if (s == null) {
                    s = new CodemossSettingsService();
                    settings = s;
                }
            }
        }
        return s;
    }

    /** Whether the plugin is currently configured to talk to a remote server. */
    public boolean isRemote() {
        try {
            return settings().isRemoteMode();
        } catch (Exception e) {
            LOG.debug("RemoteModeContext.isRemote failed: " + e.getMessage());
            return false;
        }
    }

    /** Base URL of the configured remote server (null/empty when not in remote mode). */
    public String remoteServerUrl() {
        try {
            return settings().getRemoteServerUrl();
        } catch (Exception e) {
            return "";
        }
    }

    /** Stable tooltip text for any UI element disabled because we're in remote mode. */
    public String unsupportedTooltip() {
        return "远程模式下不支持，请在容器内预配置";
    }

    public String unsupportedTooltip(String featureKey) {
        return "远程模式下不支持 " + featureKey + "，请在容器内预配置";
    }
}
