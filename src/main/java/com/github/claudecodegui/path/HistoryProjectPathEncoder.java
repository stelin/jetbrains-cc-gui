package com.github.claudecodegui.path;

import com.intellij.openapi.project.Project;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes a local project path into the base64 form used as the
 * {@code ~/.claude/projects/{encoded}/} directory name on the server.
 *
 * <p>Two steps:
 * <ol>
 *   <li>Translate the local path to its remote-side form via
 *       {@link PathMapper#toRemote} (identity in local mode).</li>
 *   <li>Convert the absolute path to the on-disk directory name by replacing
 *       every non-alphanumeric character with {@code '-'}, matching what
 *       {@code ai-bridge/services/claude/session-service.js} uses when it
 *       writes the JSONL files. So {@code D:\work\demos\cc-bridge-server} →
 *       {@code D--work-demos-cc-bridge-server}.</li>
 * </ol>
 *
 * <p>The result is then base64url-encoded so it can travel through the
 * {@code ?project=...} query parameter on {@code /history/session*}
 * endpoints, which call {@code base64UrlDecode} and pass the result
 * straight to {@code resolveSafe(root, dirName)} — they expect the
 * <em>directory name</em>, not an absolute path.
 */
public final class HistoryProjectPathEncoder {

    private HistoryProjectPathEncoder() {}

    public static String encode(Project project, String localProjectPath) {
        if (localProjectPath == null) return "";
        PathMapper m = project != null
                ? PathMapperHolder.getInstance(project).get()
                : IdentityPathMapper.INSTANCE;
        String wirePath = m.toRemote(localProjectPath);
        String dirName = wirePath.replaceAll("[^a-zA-Z0-9]", "-");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(dirName.getBytes(StandardCharsets.UTF_8));
    }
}
