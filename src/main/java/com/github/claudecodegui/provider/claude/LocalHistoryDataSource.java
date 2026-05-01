package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.common.HistoryDataSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Local-mode history reader: walks {@code ~/.claude/projects/}.
 *
 * <p>Lightweight implementation focused on what {@link HistoryDataSource}
 * exposes. The plugin's existing {@code ClaudeHistoryReader} is unchanged
 * and continues to drive the rest of the local UI.
 */
public class LocalHistoryDataSource implements HistoryDataSource {

    private static final Logger LOG = Logger.getInstance(LocalHistoryDataSource.class);

    private final Path root;

    public LocalHistoryDataSource() {
        this(Paths.get(System.getProperty("user.home"), ".claude", "projects"));
    }

    public LocalHistoryDataSource(Path root) {
        this.root = root;
    }

    @Override
    public List<ProjectInfo> listProjects() {
        if (!Files.isDirectory(root)) return List.of();
        List<ProjectInfo> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    String name = dir.getFileName().toString();
                    long mtime = Files.getLastModifiedTime(dir).toMillis();
                    long count;
                    try (Stream<Path> files = Files.list(dir)) {
                        count = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count();
                    }
                    out.add(new ProjectInfo(
                            Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)),
                            name.replace('-', '/'),
                            mtime,
                            (int) count
                    ));
                } catch (IOException e) {
                    LOG.debug("listProjects: skip " + dir + " — " + e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("listProjects failed", e);
        }
        out.sort(Comparator.comparingLong(ProjectInfo::mtime).reversed());
        return out;
    }

    @Override
    public List<HistorySessionInfo> listSessions(String encodedProjectPath, int limit, int offset) {
        Path dir = resolveProject(encodedProjectPath);
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
        } catch (IOException e) {
            LOG.warn("listSessions failed", e);
            return List.of();
        }
        files.sort(Comparator.comparingLong((Path p) -> {
            try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
        }).reversed());

        int from = Math.max(0, offset);
        int to = Math.min(files.size(), from + Math.max(1, Math.min(limit, 500)));
        if (from >= to) return List.of();

        List<HistorySessionInfo> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            Path p = files.get(i);
            String sessionId = stripExt(p.getFileName().toString());
            long mtime;
            try { mtime = Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { mtime = 0L; }
            String[] meta = readFirstLineMeta(p);
            out.add(new HistorySessionInfo(
                    sessionId,
                    meta[0],          // title
                    mtime,
                    mtime,
                    -1,                // messageCount unknown without full scan
                    meta[1]            // model
            ));
        }
        return out;
    }

    @Override
    public Optional<byte[]> readSessionRaw(String encodedProjectPath, String sessionId) {
        Path file = resolveSession(encodedProjectPath, sessionId);
        if (file == null) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            LOG.warn("readSessionRaw failed: " + file, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<SessionLite> readSessionLite(String encodedProjectPath, String sessionId) {
        Path file = resolveSession(encodedProjectPath, sessionId);
        if (file == null) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            lines.removeIf(s -> s == null || s.isBlank());
            JsonObject first = lines.isEmpty() ? null : safeJson(lines.get(0));
            JsonObject last = lines.isEmpty() ? null : safeJson(lines.get(lines.size() - 1));
            String title = trimTo(extractText(first), 100);
            return Optional.of(new SessionLite(
                    sessionId,
                    title,
                    extractText(first),
                    extractText(last),
                    lines.size()
            ));
        } catch (IOException e) {
            LOG.warn("readSessionLite failed: " + file, e);
            return Optional.empty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Path resolveProject(String encodedProjectPath) {
        if (encodedProjectPath == null || encodedProjectPath.isBlank()) return null;
        try {
            String name = new String(
                    Base64.getUrlDecoder().decode(encodedProjectPath),
                    StandardCharsets.UTF_8
            );
            if (name.contains("..") || name.contains("/") || name.contains("\\")) return null;
            Path p = root.resolve(name);
            if (!p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) return null;
            return p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Path resolveSession(String encodedProjectPath, String sessionId) {
        Path dir = resolveProject(encodedProjectPath);
        if (dir == null || sessionId == null || !sessionId.matches("[A-Za-z0-9_-]+")) return null;
        return dir.resolve(sessionId + ".jsonl");
    }

    private static String[] readFirstLineMeta(Path file) {
        // returns [title, model]
        String title = "(无标题)";
        String model = "";
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] buf = new byte[(int) Math.min(8192, raf.length())];
            raf.readFully(buf);
            String chunk = new String(buf, StandardCharsets.UTF_8);
            int nl = chunk.indexOf('\n');
            String first = nl >= 0 ? chunk.substring(0, nl) : chunk;
            JsonObject obj = safeJson(first);
            if (obj != null) {
                String t = extractText(obj);
                if (!t.isEmpty()) title = trimTo(t, 100);
                if (obj.has("model") && !obj.get("model").isJsonNull()) {
                    model = obj.get("model").getAsString();
                } else if (obj.has("message") && obj.get("message").isJsonObject()) {
                    JsonObject m = obj.getAsJsonObject("message");
                    if (m.has("model") && !m.get("model").isJsonNull()) model = m.get("model").getAsString();
                }
            }
        } catch (IOException e) {
            LOG.debug("readFirstLineMeta: " + file + " — " + e.getMessage());
        }
        return new String[]{title, model};
    }

    private static String extractText(JsonObject obj) {
        if (obj == null) return "";
        JsonElement msg = obj.get("message");
        if (msg != null && msg.isJsonObject()) {
            JsonObject m = msg.getAsJsonObject();
            JsonElement c = m.get("content");
            if (c != null) {
                if (c.isJsonPrimitive()) return c.getAsString();
                if (c.isJsonArray()) {
                    for (JsonElement e : c.getAsJsonArray()) {
                        if (e.isJsonObject()) {
                            JsonObject eo = e.getAsJsonObject();
                            if (eo.has("text") && !eo.get("text").isJsonNull()) {
                                return eo.get("text").getAsString();
                            }
                        }
                    }
                }
            }
        }
        if (obj.has("content") && obj.get("content").isJsonPrimitive()) {
            return obj.get("content").getAsString();
        }
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            return obj.get("text").getAsString();
        }
        return "";
    }

    private static JsonObject safeJson(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
