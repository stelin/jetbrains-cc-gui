package com.github.claudecodegui.session.pair;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Phase 1 (2026-05-23): gates the new monitor + EventCollector path.
 *
 * <p>Resolution order (first match wins):
 * <ol>
 *   <li>System property {@code cc-gui.pair.monitor.enabled} — primarily for
 *       tests and CI overrides. Set with {@code -Dcc-gui.pair.monitor.enabled=true}.</li>
 *   <li>IntelliJ Registry key {@code cc-gui.pair.monitor.enabled} — user-facing
 *       toggle exposed via Help → Find Action → "Registry". Defaults to false
 *       until Phase 1 has soaked in dogfood.</li>
 *   <li>Default {@code false}.</li>
 * </ol>
 *
 * <p>The Registry lookup is wrapped in a try/catch so unit tests outside the
 * IntelliJ runtime can still call this without an initialised platform.
 */
public final class MonitorFeatureFlag {

    public static final String KEY = "cc-gui.pair.monitor.enabled";

    private static final Logger LOG = Logger.getInstance(MonitorFeatureFlag.class);

    private MonitorFeatureFlag() { /* no instances */ }

    public static boolean isEnabled() {
        String sysProp = System.getProperty(KEY);
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        try {
            return com.intellij.openapi.util.registry.Registry.is(KEY, false);
        } catch (Throwable t) {
            // Registry not initialised (e.g. headless test) — fall through.
            LOG.debug("[MonitorFeatureFlag] Registry unavailable: " + t.getMessage());
            return false;
        }
    }
}
