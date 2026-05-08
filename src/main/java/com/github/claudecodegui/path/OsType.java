package com.github.claudecodegui.path;

/**
 * Operating-system flavor used by {@link DefaultPathMapper} to decide path
 * separator style and case-sensitivity for prefix comparison.
 *
 * <p>Per the design constraint, this enum is selected by the user in the
 * remote-mode settings panel — the plugin does not auto-detect either side.
 */
public enum OsType {
    WIN,
    LINUX,
    MAC;

    public boolean isWindows() {
        return this == WIN;
    }

    public boolean isCaseSensitive() {
        return this == LINUX;
    }

    public char separator() {
        return this == WIN ? '\\' : '/';
    }
}
