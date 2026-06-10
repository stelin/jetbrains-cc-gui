package com.github.claudecodegui.util;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Shared path / hashing helpers for the {@code ~/.codemoss} on-disk layout.
 *
 * <p>Session-kind refactor (containerId model): the {@link #projectHash} logic
 * was lifted out of {@code WorkflowStore} so both the workflow store and the new
 * {@code SessionRegistry} derive the same per-project subdirectory name. The
 * session registry roots at {@link #sessionsRoot()} → {@code ~/.codemoss/sessions}
 * (a clean-cut new directory; the legacy {@code ~/.codemoss/pairs} is abandoned).
 */
public final class CodemossPaths {

    private CodemossPaths() { }

    /**
     * Stable per-project subdirectory name derived from the project base path.
     * SHA-256 → first 16 hex chars (collision-safe enough, filesystem-safe,
     * deterministic across restarts). Null/blank base path → {@code "default"}.
     */
    public static String projectHash(@Nullable String basePath) {
        if (basePath == null || basePath.isEmpty()) return "default";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(basePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fall back to a sanitized hashCode — still deterministic per run.
            return Integer.toHexString(basePath.hashCode());
        }
    }

    /** Root of the session-kind registry: {@code ~/.codemoss/sessions}. */
    public static Path sessionsRoot() {
        return Paths.get(PlatformUtils.getHomeDirectory(), ".codemoss", "sessions");
    }

    /** {@code ~/.codemoss/sessions/<projectHash>/<containerId>}. */
    public static Path containerDir(String projectHash, String containerId) {
        return sessionsRoot().resolve(projectHash).resolve(containerId);
    }
}
