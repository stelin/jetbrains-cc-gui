package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.registry.SessionRegistry;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service for loading and enhancing history data.
 * Handles loading history records, deep search (cache clear + reload),
 * and enriching data with favorites and custom titles.
 */
class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;

    HistoryLoadService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
    }

    /**
     * Load and inject history data into the frontend (including favorite info).
     *
     * @param provider the provider identifier ("claude" or "codex")
     */
    void handleLoadHistoryData(String provider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载历史数据 ========== provider=" + provider);

            try {
                String historyJson;

                // Get current project path
                String projectPath = context.getProject().getBasePath();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    return;
                }

                // Choose a different reader based on the provider
                if ("codex".equals(provider)) {
                    // Use CodexHistoryReader to read Codex sessions (filtered by project)
                    LOG.info("[HistoryHandler] 使用 CodexHistoryReader 读取 Codex 会话 (项目: " + projectPath + ")");
                    CodexHistoryReader codexReader = new CodexHistoryReader();
                    historyJson = codexReader.getSessionsForProjectAsJson(projectPath);
                    LOG.info("[HistoryHandler] CodexHistoryReader 返回的 JSON 长度: " + historyJson.length());
                } else {
                    // Default: use ClaudeHistoryReader to read Claude sessions
                    RemoteModeContext remoteCtx = RemoteModeContext.getInstance();
                    if (remoteCtx.isRemote()) {
                        String remoteUrl = remoteCtx.remoteServerUrl();
                        LOG.info("[HistoryHandler] 远程模式：从 ai-bridge-server 获取会话列表 (项目: " + projectPath + ", url: " + remoteUrl + ")");
                        historyJson = fetchRemoteProjectData(remoteUrl, projectPath);
                    } else {
                        LOG.info("[HistoryHandler] 使用 ClaudeHistoryReader 读取 Claude 会话");
                        ClaudeHistoryReader historyReader = new ClaudeHistoryReader();
                        historyJson = historyReader.getProjectDataAsJson(projectPath);
                    }
                }

                // Session-kind refactor (S4): the normal history tab must not leak
                // the main leg of any supervised / workflow session. This is the
                // single exclusion point — placed AFTER the local/remote merge
                // (remote bodies are already path-translated by fetchRemoteProjectData,
                // local needs none) and BEFORE the webview push, so both modes share
                // it. sessionId is a UUID (unaffected by path translation) in the same
                // id space as the claimed set, so the subtraction is exact.
                // claimedMainSessionIds() is the union of EVERY main-session id a
                // container ever owned (full mainSessionIds[] set, not just the current
                // pointer) — a re-run's orphaned prior session is excluded too.
                historyJson = filterClaimedMainSessions(historyJson);

                // Load favorite data and merge into history data
                String enhancedJson = enhanceHistoryWithFavorites(historyJson, provider);
                LOG.info("[HistoryHandler] enhanceHistoryWithFavorites 完成，JSON 长度: " + enhancedJson.length());

                // Load custom titles and merge into history data
                String finalJson = enhanceHistoryWithTitles(enhancedJson);
                LOG.info("[HistoryHandler] enhanceHistoryWithTitles 完成，JSON 长度: " + finalJson.length());

                // Use Base64 encoding to avoid JavaScript string escaping issues
                String base64Json = Base64.getEncoder().encodeToString(finalJson.getBytes(StandardCharsets.UTF_8));
                LOG.info("[HistoryHandler] Base64 编码完成，长度: " + base64Json.length());

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject history data');" +
                                            "if (window.setHistoryData) { " +
                                            "  try { " +
                                            "    var base64Str = '" + base64Json + "'; " +
                                            "    console.log('[Backend->Frontend] Base64 length:', base64Str.length); " +
                                            // Use TextDecoder to properly decode UTF-8 Base64 strings (avoid garbled non-ASCII characters)
                                            "    var binaryStr = atob(base64Str); " +
                                            "    var bytes = new Uint8Array(binaryStr.length); " +
                                            "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                                            "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                                            "    console.log('[Backend->Frontend] Decoded JSON length:', jsonStr.length); " +
                                            "    var data = JSON.parse(jsonStr); " +
                                            "    console.log('[Backend->Frontend] Parsed data, sessions:', data.sessions ? data.sessions.length : 0); " +
                                            "    window.setHistoryData(data); " +
                                            "    console.log('[Backend->Frontend] setHistoryData called successfully'); " +
                                            "  } catch(e) { " +
                                            "    console.error('[Backend->Frontend] Failed to parse/set history data:', e); " +
                                            "    window.setHistoryData({ success: false, error: '解析历史数据失败: ' + e.message }); " +
                                            "  } " +
                                            "} else { " +
                                            "  console.error('[Backend->Frontend] setHistoryData not available!'); " +
                                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                    LOG.info("[HistoryHandler] JavaScript 代码已注入");
                });

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载历史数据失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.setHistoryData) { " +
                                            "  window.setHistoryData({ success: false, error: '" + errorMsg + "' }); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Deep search history records.
     * Clears cache and reloads complete history from the file system.
     *
     * @param provider the provider identifier ("claude" or "codex")
     */
    void handleDeepSearchHistory(String provider) {
        String projectPath = context.getProject().getBasePath();
        LOG.info("[HistoryHandler] ========== 开始深度搜索 ========== provider=" + provider);

        try {
            if ("codex".equals(provider)) {
                SessionIndexCache.getInstance().clearAllCodexCache();
                SessionIndexManager.getInstance().clearAllCodexIndex();
            } else if (projectPath != null) {
                SessionIndexCache.getInstance().clearProject(projectPath);
                SessionIndexManager.getInstance().clearProjectIndex("claude", projectPath);
            }

            LOG.info("[HistoryHandler] 缓存清理完成，开始重新加载历史数据...");

        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 清理缓存时出错（继续加载）: " + e.getMessage());
        }

        // 3. Reload history data (using existing method)
        handleLoadHistoryData(provider);
    }

    /**
     * Enhance history data: add favorite info to each session.
     */
    private String enhanceHistoryWithFavorites(String historyJson, String currentProvider) {
        try {
            // Load favorite data
            String favoritesJson = nodeJsServiceCaller.callNodeJsFavoritesService("loadFavorites", "");

            // Parse history data and favorite data
            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject favorites = new Gson().fromJson(favoritesJson, JsonObject.class);

            // Add favorite info and provider info to each session
            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    // Add provider info
                    session.addProperty("provider", currentProvider);

                    if (favorites.has(sessionId)) {
                        JsonObject favoriteInfo = favorites.getAsJsonObject(sessionId);
                        session.addProperty("isFavorited", true);
                        session.addProperty("favoritedAt", favoriteInfo.get("favoritedAt").getAsLong());
                    } else {
                        session.addProperty("isFavorited", false);
                    }
                }
            }

            // Also add favorite data to the history data
            history.add("favorites", favorites);

            return new Gson().toJson(history);

        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强历史数据失败，返回原始数据: " + e.getMessage());
            return historyJson;
        }
    }

    /**
     * Enhance history data: add custom titles to each session.
     */
    private String enhanceHistoryWithTitles(String historyJson) {
        try {
            // Load title data
            String titlesJson = nodeJsServiceCaller.callNodeJsTitlesService("loadTitles");

            // Parse history data and title data
            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject titles = new Gson().fromJson(titlesJson, JsonObject.class);

            // Add custom title to each session
            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    if (titles.has(sessionId)) {
                        JsonObject titleInfo = titles.getAsJsonObject(sessionId);
                        // If a custom title exists, override the original title
                        if (titleInfo.has("customTitle")) {
                            String customTitle = titleInfo.get("customTitle").getAsString();
                            session.addProperty("title", customTitle);
                            session.addProperty("hasCustomTitle", true);
                        }
                    }
                }
            }

            return new Gson().toJson(history);

        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强标题数据失败，返回原始数据: " + e.getMessage());
            return historyJson;
        }
    }

    /**
     * Session-kind refactor (S4): drop every session whose id was claimed by a
     * supervised / workflow container (its main leg), so the normal history tab
     * shows only bare sessions. No-op when no containers exist (empty claimed set)
     * or none of their legs appear in this list. Fail-soft: on any parse error the
     * unfiltered JSON is returned (better a stray entry than an empty normal tab).
     */
    /**
     * Daemon-assembled supervisor event frames (ai-bridge {@code event-summarizer.js})
     * all start with one of these markers, so a supervisor session's default title
     * (its first frame) carries one of these signatures — used to drop supervisor
     * leaks that have no manifest claim.
     *
     * <p>Plan A (2026-06-10) prepends a {@code ## [PLANNING_REQUIRED]} / {@code ## [RESUME]}
     * directive AHEAD of the {@code ## USER MESSAGE [} frame on the first / resume
     * event — which shifted the title's leading signature and let those sessions
     * leak into the normal tab. Hence the extra entries.
     */
    private static final String[] SUPERVISOR_TITLE_SIGNATURES = {
        "## USER MESSAGE [",
        "## [PLANNING_REQUIRED]",
        "## [RESUME]",
    };

    /** True when {@code title} begins with any supervisor event-frame signature. */
    private static boolean matchesSupervisorSignature(String title) {
        if (title == null) return false;
        String t = title.trim();
        for (String sig : SUPERVISOR_TITLE_SIGNATURES) {
            if (t.startsWith(sig)) return true;
        }
        return false;
    }

    private String filterClaimedMainSessions(String historyJson) {
        try {
            if (context.getProject() == null) return historyJson;
            // Two reasons a session is a supervised leg, not a real normal chat:
            //  (a) its id is claimed by a container manifest (precise; new sessions);
            //  (b) its default title carries the supervisor prompt signature (catches
            //      LEGACY supervisor sessions that predate the manifest — no claimed
            //      entry exists for them). We must NOT early-return on an empty claimed
            //      set, or (b) would never run for the existing leaks.
            Set<String> claimed = SessionRegistry.getInstance(context.getProject())
                    .claimedMainSessionIds();

            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            if (history == null || !history.has("sessions") || !history.get("sessions").isJsonArray()) {
                return historyJson;
            }
            JsonArray sessions = history.getAsJsonArray("sessions");
            JsonArray kept = new JsonArray();
            int removedClaimed = 0, removedSignature = 0;
            for (int i = 0; i < sessions.size(); i++) {
                JsonObject s = sessions.get(i).getAsJsonObject();
                String sid = s.has("sessionId") && !s.get("sessionId").isJsonNull()
                        ? s.get("sessionId").getAsString() : null;
                if (sid != null && claimed.contains(sid)) {
                    removedClaimed++;
                    continue;
                }
                // Runs BEFORE enhanceHistoryWithTitles, so `title` is still the raw
                // first-message text (not a user custom title) — the signature holds.
                String title = s.has("title") && !s.get("title").isJsonNull()
                        ? s.get("title").getAsString() : null;
                if (matchesSupervisorSignature(title)) {
                    removedSignature++;
                    continue;
                }
                kept.add(s);
            }
            int removed = removedClaimed + removedSignature;
            if (removed == 0) return historyJson;
            history.add("sessions", kept);
            // Keep the count fields (if present) consistent with the filtered list.
            if (history.has("total")) history.addProperty("total", kept.size());
            if (history.has("sessionCount")) history.addProperty("sessionCount", kept.size());
            LOG.info("[HistoryHandler] normal-tab filter: removed " + removedClaimed
                    + " claimed main leg(s) + " + removedSignature
                    + " supervisor-signature session(s)");
            return new Gson().toJson(history);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] normal-history filter failed (returning unfiltered): "
                    + e.getMessage());
            return historyJson;
        }
    }

    /**
     * Fetch project history data from a remote ai-bridge-server.
     * Returns JSON in the same shape as {@code ClaudeHistoryReader.getProjectDataAsJson()}.
     *
     * <p>Path mapping: the local project path is translated to its remote-side
     * form before being sent (so the server looks up history under the right
     * directory), and any path-bearing fields in the response are translated
     * back to local form before the JSON is rendered by the UI.
     */
    private String fetchRemoteProjectData(String remoteUrl, String projectPath) throws Exception {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalStateException("Remote mode enabled but remoteServerUrl is empty");
        }
        String base = remoteUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        com.github.claudecodegui.path.PathMapper mapper = context.getProject() != null
                ? com.github.claudecodegui.path.PathMapperHolder.getInstance(context.getProject()).get()
                : com.github.claudecodegui.path.IdentityPathMapper.INSTANCE;
        String wireProjectPath = mapper.toRemote(projectPath);

        String url = base + "/history/project-data?projectPath="
                + URLEncoder.encode(wireProjectPath, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        String body = resp.body();
        return mapper.isActive() ? translateHistoryPaths(body, mapper) : body;
    }

    /**
     * Walk the JSON returned by /history/project-data and rewrite any path
     * fields that look like they came from a JSONL message line — see the
     * {@code __history_line__} entry in {@link com.github.claudecodegui.path.PathFields}.
     */
    private static String translateHistoryPaths(String json,
                                                com.github.claudecodegui.path.PathMapper mapper) {
        if (json == null || json.isEmpty()) return json;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (!el.isJsonObject() && !el.isJsonArray()) return json;
            // The response wraps message lines under various keys depending on the
            // server implementation. Rather than guess the wrapper, recursively
            // walk every nested object and apply the line-level manifest.
            walkAndTranslate(el, mapper);
            return new com.google.gson.Gson().toJson(el);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] history path translation failed: " + e.getMessage());
            return json;
        }
    }

    private static void walkAndTranslate(com.google.gson.JsonElement el,
                                         com.github.claudecodegui.path.PathMapper mapper) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                walkAndTranslate(child, mapper);
            }
            return;
        }
        if (el.isJsonObject()) {
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            // Apply manifest to this object — fail-soft.
            try {
                com.github.claudecodegui.path.PathFieldVisitor.applyInbound(
                        "__history_line__", obj, mapper::toLocal);
            } catch (Exception ignore) {}
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                walkAndTranslate(e.getValue(), mapper);
            }
        }
    }
}
