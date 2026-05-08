package com.github.claudecodegui.path;

import com.github.claudecodegui.settings.PathMappingConfig;

import java.util.Locale;

/**
 * Prefix-replacement path translator.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Canonicalize both the input path and the configured local/remote root
 *       (unify separators to {@code '/'}, strip trailing separators, optionally
 *       lower-case for non-case-sensitive OSes).</li>
 *   <li>Verify the canonicalized input starts with the canonical root
 *       <em>at a path-segment boundary</em> (so {@code /foo/bar} doesn't match
 *       {@code /foo/barbaz}).</li>
 *   <li>Substitute the root and re-style the trailing segment with the target
 *       OS's separator. The original case of the configured root is preserved
 *       in the output.</li>
 * </ol>
 *
 * <p>Misses are recorded in {@link PathMissTracker} but never throw — the
 * original path is returned so the caller can decide whether to bail out or
 * pass it through.
 */
public final class DefaultPathMapper implements PathMapper {

    private final PathMappingConfig cfg;
    private final PathMissTracker missTracker;
    private final String localRootCanonical;
    private final String remoteRootCanonical;

    public DefaultPathMapper(PathMappingConfig cfg, PathMissTracker missTracker) {
        this.cfg = cfg;
        this.missTracker = missTracker;
        this.localRootCanonical  = cfg.isUsable() ? canonical(cfg.localRoot,  cfg.localOs)  : "";
        this.remoteRootCanonical = cfg.isUsable() ? canonical(cfg.remoteRoot, cfg.remoteOs) : "";
    }

    @Override
    public boolean isActive() {
        return cfg.isUsable();
    }

    @Override
    public String toRemote(String localPath) {
        if (!isActive() || localPath == null || localPath.isEmpty()) {
            return localPath;
        }
        String p = canonical(localPath, cfg.localOs);
        if (!hasRootPrefix(p, localRootCanonical)) {
            if (missTracker != null) missTracker.recordOutboundMiss(localPath);
            return localPath;
        }
        String tail = p.substring(localRootCanonical.length());
        return joinWithStyle(cfg.remoteRoot, tail, cfg.remoteOs);
    }

    @Override
    public String toLocal(String remotePath) {
        if (!isActive() || remotePath == null || remotePath.isEmpty()) {
            return remotePath;
        }
        String p = canonical(remotePath, cfg.remoteOs);
        if (!hasRootPrefix(p, remoteRootCanonical)) {
            if (missTracker != null) missTracker.recordInboundMiss(remotePath);
            return remotePath;
        }
        String tail = p.substring(remoteRootCanonical.length());
        return joinWithStyle(cfg.localRoot, tail, cfg.localOs);
    }

    /**
     * Canonicalize a path for prefix comparison: unify separators, strip a
     * trailing separator (except the root), and lowercase on non-case-sensitive
     * OSes. The result is only used for matching — not for output.
     */
    static String canonical(String raw, OsType os) {
        if (raw == null) return "";
        String s = raw.replace('\\', '/');
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return os.isCaseSensitive() ? s : s.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code pathCanonical} starts with {@code rootCanonical} at a path-segment
     * boundary (either equal, or followed by a separator).
     */
    static boolean hasRootPrefix(String pathCanonical, String rootCanonical) {
        if (rootCanonical.isEmpty()) return false;
        if (!pathCanonical.startsWith(rootCanonical)) return false;
        return pathCanonical.length() == rootCanonical.length()
                || pathCanonical.charAt(rootCanonical.length()) == '/';
    }

    /**
     * Combine the original-cased root with the trailing segment, restyling
     * separators to match the target OS.
     */
    static String joinWithStyle(String rootRaw, String tail, OsType targetOs) {
        String r = stripTrailing(rootRaw);
        String t = tail.replace('\\', '/');
        if (targetOs.isWindows()) {
            return r + t.replace('/', '\\');
        }
        return r + t;
    }

    private static String stripTrailing(String s) {
        if (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
