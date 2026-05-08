package com.github.claudecodegui.path;

/**
 * Two-way path translator between the IDE-local filesystem view and the
 * ai-bridge-server's filesystem view.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link IdentityPathMapper} — no-op. Used for local mode and for remote
 *       mode when no mapping is configured (per design §1 rule 6).</li>
 *   <li>{@link DefaultPathMapper} — prefix-replacement based, configured via
 *       {@link com.github.claudecodegui.settings.PathMappingConfig}.</li>
 * </ul>
 *
 * <p>Translation is best-effort: paths that do not match the configured root
 * are returned unchanged and recorded in {@link PathMissTracker} so the UI can
 * surface a hint.
 */
public interface PathMapper {

    /** Inbound: remote path → local path. Returns the original on miss. */
    String toLocal(String remotePath);

    /** Outbound: local path → remote path. Returns the original on miss. */
    String toRemote(String localPath);

    /** {@code true} when a non-identity mapping is in effect. */
    boolean isActive();
}
