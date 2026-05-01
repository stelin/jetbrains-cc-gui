package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.common.HistoryDataSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Remote-mode history reader: calls the {@code GET /history/*} endpoints on
 * an {@code ai-bridge-server} instance. Designed to be a drop-in replacement
 * for {@link LocalHistoryDataSource} — same interface, same record shapes.
 *
 * <p>No client-side caching beyond what HttpClient does by default.
 */
public class RemoteHistoryDataSource implements HistoryDataSource {

    private static final Logger LOG = Logger.getInstance(RemoteHistoryDataSource.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LARGE_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient http;

    public RemoteHistoryDataSource(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl required");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.baseUrl = trimmed;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public List<ProjectInfo> listProjects() {
        try {
            JsonElement el = getJson("/history/projects", TIMEOUT);
            if (el == null || !el.isJsonArray()) return List.of();
            List<ProjectInfo> out = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                out.add(new ProjectInfo(
                        getString(o, "encodedPath"),
                        getString(o, "displayPath"),
                        getLong(o, "mtime"),
                        (int) getLong(o, "sessionCount")
                ));
            }
            return out;
        } catch (Exception ex) {
            LOG.warn("[RemoteHistory] listProjects failed: " + ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<HistorySessionInfo> listSessions(String encodedProjectPath, int limit, int offset) {
        if (encodedProjectPath == null) return List.of();
        try {
            String path = "/history/sessions?project=" + urlEnc(encodedProjectPath)
                    + "&limit=" + limit + "&offset=" + offset;
            JsonElement el = getJson(path, TIMEOUT);
            if (el == null || !el.isJsonArray()) return List.of();
            List<HistorySessionInfo> out = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                out.add(new HistorySessionInfo(
                        getString(o, "sessionId"),
                        getString(o, "title"),
                        getLong(o, "startTime"),
                        getLong(o, "lastTurnTime"),
                        (int) getLong(o, "messageCount"),
                        getString(o, "model")
                ));
            }
            return out;
        } catch (Exception ex) {
            LOG.warn("[RemoteHistory] listSessions failed: " + ex.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<byte[]> readSessionRaw(String encodedProjectPath, String sessionId) {
        if (encodedProjectPath == null || sessionId == null) return Optional.empty();
        try {
            String path = "/history/session?project=" + urlEnc(encodedProjectPath)
                    + "&sessionId=" + urlEnc(sessionId);
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
                            .timeout(LARGE_TIMEOUT)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (resp.statusCode() != 200 && resp.statusCode() != 206) {
                LOG.warn("[RemoteHistory] readSessionRaw status " + resp.statusCode());
                return Optional.empty();
            }
            return Optional.of(resp.body());
        } catch (Exception ex) {
            LOG.warn("[RemoteHistory] readSessionRaw failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<SessionLite> readSessionLite(String encodedProjectPath, String sessionId) {
        if (encodedProjectPath == null || sessionId == null) return Optional.empty();
        try {
            String path = "/history/session-lite?project=" + urlEnc(encodedProjectPath)
                    + "&sessionId=" + urlEnc(sessionId);
            JsonElement el = getJson(path, TIMEOUT);
            if (el == null || !el.isJsonObject()) return Optional.empty();
            JsonObject o = el.getAsJsonObject();
            return Optional.of(new SessionLite(
                    getString(o, "sessionId"),
                    getString(o, "title"),
                    getString(o, "firstUserMsg"),
                    getString(o, "lastAssistantMsg"),
                    (int) getLong(o, "messageCount")
            ));
        } catch (Exception ex) {
            LOG.warn("[RemoteHistory] readSessionLite failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JsonElement getJson(String path, Duration timeout) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
        }
        String body = resp.body();
        if (body == null || body.isBlank()) return null;
        return JsonParser.parseString(body);
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String getString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static long getLong(JsonObject o, String key) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) return 0L;
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
