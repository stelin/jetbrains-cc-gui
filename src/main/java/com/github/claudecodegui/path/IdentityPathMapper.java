package com.github.claudecodegui.path;

/**
 * No-op mapper: returns every path unchanged. Used in local mode and in
 * remote mode when no path mapping is configured for the current project.
 */
public final class IdentityPathMapper implements PathMapper {

    public static final IdentityPathMapper INSTANCE = new IdentityPathMapper();

    private IdentityPathMapper() {}

    @Override public String toLocal(String p)  { return p; }
    @Override public String toRemote(String p) { return p; }
    @Override public boolean isActive() { return false; }
}
