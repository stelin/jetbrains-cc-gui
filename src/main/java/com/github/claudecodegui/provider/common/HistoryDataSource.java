package com.github.claudecodegui.provider.common;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over how the plugin reads conversation history.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@code LocalHistoryDataSource} — walks {@code ~/.claude/projects/}
 *       directly (the default behaviour, identical to local mode today).</li>
 *   <li>{@code RemoteHistoryDataSource} — calls the
 *       {@code GET /history/*} endpoints on an
 *       {@code ai-bridge-server}.</li>
 * </ul>
 *
 * <p>Encoded paths are opaque to the caller — for the remote variant they
 * must be obtained from {@link #listProjects()} and passed back unchanged.
 */
public interface HistoryDataSource {

    /** List projects (one per directory under {@code ~/.claude/projects/}). */
    List<ProjectInfo> listProjects();

    /** List sessions inside a project, newest first. */
    List<HistorySessionInfo> listSessions(String encodedProjectPath, int limit, int offset);

    /**
     * Return the raw NDJSON content of a session file.
     *
     * @return raw bytes, or empty if missing / unreadable
     */
    Optional<byte[]> readSessionRaw(String encodedProjectPath, String sessionId);

    /**
     * Return a lightweight summary of a session (title, first/last message).
     */
    Optional<SessionLite> readSessionLite(String encodedProjectPath, String sessionId);

    // =========================================================================
    // Records (Java 16+)
    // =========================================================================

    record ProjectInfo(
            String encodedPath,
            String displayPath,
            long mtime,
            int sessionCount
    ) {}

    record HistorySessionInfo(
            String sessionId,
            String title,
            long startTime,
            long lastTurnTime,
            int messageCount,
            String model
    ) {}

    record SessionLite(
            String sessionId,
            String title,
            String firstUserMsg,
            String lastAssistantMsg,
            int messageCount
    ) {}
}
