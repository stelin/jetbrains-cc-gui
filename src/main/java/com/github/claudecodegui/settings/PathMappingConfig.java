package com.github.claudecodegui.settings;

import com.github.claudecodegui.path.OsType;

/**
 * Per-project path mapping configuration. Persisted under
 * {@code projectConfigs.{localProjectPath}.pathMapping} in
 * {@code ~/.codemoss/config.json}.
 *
 * <p>A single mapping describes the root folders on both sides; sub-paths are
 * derived by prefix substitution at runtime. {@link #isUsable()} returns
 * {@code true} only when every required field is populated, so partial
 * configurations naturally degrade to the no-op identity mapper.
 */
public class PathMappingConfig {

    public boolean enabled;
    public OsType  localOs;
    public String  localRoot;
    public OsType  remoteOs;
    public String  remoteRoot;

    public boolean isUsable() {
        return enabled
                && localOs != null && remoteOs != null
                && notBlank(localRoot) && notBlank(remoteRoot);
    }

    public static PathMappingConfig disabled() {
        PathMappingConfig c = new PathMappingConfig();
        c.enabled = false;
        return c;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
