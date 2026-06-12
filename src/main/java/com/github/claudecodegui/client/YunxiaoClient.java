package com.github.claudecodegui.client;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin HTTP client for the Alibaba Cloud DevOps (云效) <b>new</b> OpenAPI
 * (central edition), used by the in-IDE "我的缺陷" list (需求2). Always runs on
 * the user's machine, so it is independent of the local/remote daemon mode.
 *
 * <p>Auth: header {@code x-yunxiao-token: <token>}; host is
 * {@link CodemossSettingsService#getYunxiaoDomain()} (default
 * {@code openapi-rdc.aliyuncs.com}). Token / organizationId are read lazily from
 * {@link CodemossSettingsService} on every call so config changes take effect
 * without rebuilding the client.
 *
 * <p>Scope (Step 1 — basics only): {@link #getCurrentUserId()},
 * {@link #listProjects()}, {@link #searchMyBugs(String, int, int)}. Retry /
 * graceful-degradation is the daemon tool's concern (Step 4) and is intentionally
 * not implemented here — non-2xx responses throw {@link IOException}.
 *
 * <p>All paths used here (current-user {@code /oapi/v1/platform/user},
 * {@code projects:search}, {@code workitems:search}) are pinned against the official
 * 云效 OpenAPI / MCP server. Note these are all <b>central-edition</b> paths (with the
 * {@code organizations/{orgId}} segment); a Region/private-cloud edition uses the
 * org-less variants and would need an {@code isRegionEdition()} branch.
 */
public class YunxiaoClient {

    private static final Logger LOG = Logger.getInstance(YunxiaoClient.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_PER_PAGE = 50;
    private static final int MAX_PER_PAGE = 200;

    /**
     * Fields kept when normalising a workitem into a stable list-item contract.
     * 云效 WorkItemSchema 的内部标识字段是 {@code id}（GetWorkitem 的 key），不是
     * {@code identifier}（后者 SearchWorkitems 根本不返回）。两者都保留，并在
     * {@link #normalizeBug} 里把 {@code id} 兜底映射成 {@code identifier}。
     */
    private static final String[] BUG_FIELDS = {
            "id", "identifier", "serialNumber", "subject", "status", "workitemType",
            "assignedTo", "gmtCreate", "gmtModified"
    };

    private final CodemossSettingsService settings;
    private final HttpClient http;

    public YunxiaoClient() {
        this(new CodemossSettingsService());
    }

    public YunxiaoClient(CodemossSettingsService settings) {
        this.settings = settings != null ? settings : new CodemossSettingsService();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolve the current user id for the configured token (token-only call).
     * 云效 returns an ObjectId-style hex <em>string</em> (e.g.
     * {@code "654458cef717cdf76f826b62"}), NOT a numeric id — so this is a
     * {@link String}.
     *
     * <p>The resolved id is <b>persisted</b> to config ({@code yunxiao.userId}) and
     * reused on subsequent calls / IDE restarts, so the缺陷 list's {@code assignedTo}
     * filter doesn't re-hit the current-user API on every query. The persisted id is
     * cleared whenever token/orgId change (see
     * {@link CodemossSettingsService#setYunxiaoToken}/{@code setYunxiaoOrgId}), so a
     * credential switch forces a fresh resolve.
     */
    public String getCurrentUserId() throws IOException {
        // Persisted id is the cache: present → skip the API call entirely.
        String persisted = settings.getYunxiaoUserId();
        if (persisted != null && !persisted.isBlank()) {
            return persisted.trim();
        }

        String token = requireToken();
        String domain = settings.getYunxiaoDomain();
        // 当前用户接口仅凭 token, 返回 {"id":"<hex>", ...}（已对照官方 OpenAPI/MCP server 确认）
        String url = "https://" + domain + "/oapi/v1/platform/user";

        JsonElement resp = execute(authed(url, token).GET().build());
        String id = extractUserId(resp);
        persistUserId(id);
        LOG.info("[Yunxiao] Resolved current user id=" + id);
        return id;
    }

    /** Persist the resolved current-user id (best-effort; a write failure only costs a re-fetch). */
    private void persistUserId(String id) {
        try {
            settings.setYunxiaoUserId(id);
        } catch (IOException e) {
            LOG.warn("[Yunxiao] Failed to persist current user id: " + e.getMessage());
        }
    }

    /**
     * List projects under the configured organization (for the缺陷 list project
     * dropdown). Returns the raw project objects; the handler layer (Step 2) maps
     * them to the UI shape.
     */
    public List<JsonObject> listProjects() throws IOException {
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        // 新版无 GET 列表端点：ListProjects = POST .../projects:search（已对照官方 MCP server
        // searchProjectsFunc 确认）。body 全可选；只带分页即返回当前用户可见的项目（顶层数组）。
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId + "/projects:search";

        JsonObject body = new JsonObject();
        body.addProperty("page", 1);
        body.addProperty("perPage", MAX_PER_PAGE);
        JsonElement resp = execute(authed(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
        JsonArray arr = firstArray(resp, "result", "data", "projects", "items");
        List<JsonObject> projects = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    projects.add(el.getAsJsonObject());
                }
            }
        }
        return projects;
    }

    /**
     * Search缺陷 (Bug) assigned to the current user within a single project
     * (§3.1 / §3.2). {@code spaceId} is mandatory and cross-project search is not
     * supported; {@code assignedTo} is passed through {@code conditions} using the
     * cached current-user id. Returns the parsed bugs plus a {@code hasMore} hint
     * for the "加载更多" pager.
     *
     * @param projectId 项目 id (=spaceId)
     * @param page      1-based page index
     * @param perPage   page size (clamped to {@code [1, 200]}; 0 → default 50)
     */
    public BugPage searchMyBugs(String projectId, int page, int perPage) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            throw new IOException("projectId required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String userId = getCurrentUserId();

        int safePage = Math.max(1, page);
        int safePerPage = perPage <= 0 ? DEFAULT_PER_PAGE : Math.min(perPage, MAX_PER_PAGE);

        JsonObject body = new JsonObject();
        body.addProperty("category", "Bug");
        body.addProperty("spaceId", projectId);
        body.addProperty("spaceType", "Project");
        body.addProperty("conditions", buildAssignedToConditions(userId));
        body.addProperty("page", safePage);
        body.addProperty("perPage", safePerPage);

        // SearchWorkitems —— 已确认 path
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId + "/workitems:search";
        JsonElement resp = execute(authed(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());

        JsonArray arr = firstArray(resp, "workitems", "result", "data", "items");
        List<JsonObject> bugs = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    bugs.add(normalizeBug(el.getAsJsonObject()));
                }
            }
        }

        boolean hasMore = computeHasMore(resp, safePage, safePerPage, bugs.size());
        return new BugPage(bugs, hasMore, safePage);
    }

    /** One page of缺陷 results plus the pager's {@code hasMore} hint. */
    public static final class BugPage {
        public final List<JsonObject> bugs;
        public final boolean hasMore;
        public final int page;

        public BugPage(List<JsonObject> bugs, boolean hasMore, int page) {
            this.bugs = bugs;
            this.hasMore = hasMore;
            this.page = page;
        }
    }

    // =========================================================================
    // Status change (列表内改状态): 工作流可选状态 + 更新状态
    // =========================================================================

    /**
     * <b>All</b> statuses defined for a bug's workitem-type workflow (the full set the
     * user can switch to, not transition-filtered). Each returned object is
     * {@code {id, name, color}} (id = 状态Id used by {@link #updateWorkItemStatus}).
     * {@code currentStatusId} is accepted for logging/compat but not used to filter.
     */
    public List<JsonObject> getWorkItemStatuses(String projectId, String workItemTypeId, String currentStatusId)
            throws IOException {
        if (projectId == null || projectId.isBlank() || workItemTypeId == null || workItemTypeId.isBlank()) {
            throw new IOException("projectId/workItemTypeId required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/projects/" + projectId.trim() + "/workitemTypes/" + workItemTypeId.trim() + "/workflows";
        JsonElement resp = execute(authed(url, token).GET().build());

        // 列出全部状态(不按流转过滤,用户要看到所有可选状态)。云效 GetWorkitemWorkflow 的状态数组
        // 字段是 `statuses`(MiniStatus: {id,name,displayName,nameEn}) —— 已对照官方
        // workitem.swagger.json + 老版 GetWorkItemWorkFlowInfo 文档确认(旧代码只认 `states` 故恒空,
        // 显示"无可切换的状态")。其余字段名 states/workflowStates/... 作兜底。数组可能在 top-level
        // 或 result/data 包裹下(MCP server getWorkItemWorkflowFunc 取 result),firstArray 都容错。
        JsonArray states = firstArray(resp, "statuses", "states", "workflowStates", "statusList", "items", "data");
        List<JsonObject> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (states != null) {
            for (JsonElement el : states) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject s = el.getAsJsonObject();
                String id = strField(s, "id");
                if (id.isEmpty()) {
                    id = strField(s, "statusId");      // 兜底字段名
                }
                if (id.isEmpty()) {
                    id = strField(s, "identifier");    // 老版 GetWorkItemWorkFlowInfo 用数字 identifier
                }
                if (id.isEmpty() || !seen.add(id)) {
                    continue;
                }
                JsonObject out = new JsonObject();
                out.addProperty("id", id);
                // 展示名优先 displayName(中文标签,与列表项 statusText 一致),再兜 name/nameEn。
                String name = strField(s, "displayName");
                if (name.isEmpty()) {
                    name = strField(s, "name");
                }
                if (name.isEmpty()) {
                    name = strField(s, "nameEn");
                }
                out.addProperty("name", name.isEmpty() ? id : name);
                out.addProperty("color", strField(s, "color"));
                result.add(out);
            }
        }
        if (currentStatusId != null) {
            LOG.info("[Yunxiao] statuses for type=" + workItemTypeId + " count=" + result.size());
        }
        return result;
    }

    /** Change a bug's status (PUT UpdateWorkItem with {@code {status: <状态Id>}}). */
    public void updateWorkItemStatus(String workItemId, String statusId) throws IOException {
        if (workItemId == null || workItemId.isBlank() || statusId == null || statusId.isBlank()) {
            throw new IOException("workItemId/statusId required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/workitems/" + workItemId.trim();
        JsonObject body = new JsonObject();
        body.addProperty("status", statusId.trim());
        LOG.info("[Yunxiao] update status workitem=" + workItemId.trim() + " status=" + statusId.trim());
        execute(authed(url, token).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /**
     * Reassign a bug's 负责人 (PUT UpdateWorkItem with {@code {assignedTo: <userId>}}).
     * {@code assignedToUserId} is the platform userId (= MemberInfoSchema.userId,亦即
     * UpdateWorkItemFieldSchema.assignedTo "指派人userId",单值字符串非数组,已对照官方确认)。
     */
    public void updateWorkItemAssignee(String workItemId, String assignedToUserId) throws IOException {
        if (workItemId == null || workItemId.isBlank() || assignedToUserId == null || assignedToUserId.isBlank()) {
            throw new IOException("workItemId/assignedTo required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/workitems/" + workItemId.trim();
        JsonObject body = new JsonObject();
        body.addProperty("assignedTo", assignedToUserId.trim());
        LOG.info("[Yunxiao] update assignee workitem=" + workItemId.trim() + " assignedTo=" + assignedToUserId.trim());
        execute(authed(url, token).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    // =========================================================================
    // Members (评论 @ 人:列出/搜索企业成员)
    // =========================================================================

    /**
     * Search organization members for the comment「@」picker. Uses
     * {@code POST .../platform/organizations/{orgId}/members:search}(已对照官方
     * {@code searchOrganizationMembersFunc} 确认);空 {@code query} 返回全部(分页首页)。
     * 每条归一为 {@code {userId, name}}(userId 取 MemberInfoSchema.userId,缺则兜 id;
     * name 取 name,缺则兜 userId)。
     */
    public List<JsonObject> searchMembers(String query, int page, int perPage) throws IOException {
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        int safePage = Math.max(1, page);
        int safePerPage = perPage <= 0 ? 100 : Math.min(perPage, MAX_PER_PAGE);
        String url = "https://" + domain + "/oapi/v1/platform/organizations/" + orgId + "/members:search";

        JsonObject body = new JsonObject();
        body.addProperty("page", safePage);
        body.addProperty("perPage", safePerPage);
        if (query != null && !query.isBlank()) {
            body.addProperty("query", query.trim());
        }
        JsonElement resp = execute(authed(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());

        JsonArray arr = firstArray(resp, "members", "result", "data", "items");
        List<JsonObject> members = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject m = el.getAsJsonObject();
                String userId = strField(m, "userId");
                if (userId.isEmpty()) {
                    userId = strField(m, "id");
                }
                String name = strField(m, "name");
                if (name.isEmpty()) {
                    name = strField(m, "displayName");
                }
                if (name.isEmpty()) {
                    name = userId;
                }
                if (name.isEmpty() || !seen.add(userId.isEmpty() ? name : userId)) {
                    continue;
                }
                JsonObject out = new JsonObject();
                out.addProperty("userId", userId);
                out.addProperty("name", name);
                members.add(out);
            }
        }
        return members;
    }

    // =========================================================================
    // Comments (详情弹窗底部发评论 + 图片粘贴上传)
    // =========================================================================

    /** Post a comment on a bug (CreateWorkItemComment). {@code content} may be markdown. */
    public void createComment(String workItemId, String content) throws IOException {
        if (workItemId == null || workItemId.isBlank() || content == null || content.isBlank()) {
            throw new IOException("workItemId/content required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/workitems/" + workItemId.trim() + "/comments";
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        execute(authed(url, token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /**
     * Upload a pasted image as a workitem attachment (multipart) and return a
     * markdown image tag to embed in a comment. Prefers the server's
     * {@code embedMarkdown}; falls back to building one from {@code embedUrl}/{@code url}.
     */
    public String uploadCommentImage(String workItemId, byte[] data, String fileName, String contentType)
            throws IOException {
        if (workItemId == null || workItemId.isBlank() || data == null || data.length == 0) {
            throw new IOException("workItemId/image data required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/workitems/" + workItemId.trim() + "/attachments";
        String name = (fileName == null || fileName.isBlank()) ? "image.png" : fileName.trim();
        String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType.trim();
        String boundary = "YunxiaoBoundary" + UUID.randomUUID().toString().replace("-", "");
        final String crlf = "\r\n";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"" + crlf)
                    .getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Type: " + ct + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(data);
            baos.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("构建上传请求失败: " + e.getMessage(), e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("x-yunxiao-token", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Yunxiao HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            JsonObject o = unwrap(JsonParser.parseString(resp.body()));
            String md = strField(o, "embedMarkdown");
            if (!md.isEmpty()) {
                return md;
            }
            String embedUrl = strField(o, "embedUrl");
            if (!embedUrl.isEmpty()) {
                String alt = strField(o, "name");
                return "![" + (alt.isEmpty() ? "image" : alt.replaceAll("[\\[\\]]", "")) + "](" + embedUrl + ")";
            }
            String dl = strField(o, "url");
            if (!dl.isEmpty()) {
                return "![image](" + dl + ")";
            }
            throw new IOException("上传成功但未返回可嵌入地址");
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("上传中断", e);
        } catch (Exception e) {
            throw new IOException("上传失败: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Bug detail (查看详情弹窗): 基础信息 + 描述(内联图片) + 附件
    // =========================================================================

    private static final int MAX_INLINE_IMAGES = 30;
    private static final int MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024; // 8MB/张
    private static final long TOTAL_INLINE_BUDGET = 16L * 1024 * 1024; // ~16MB base64 total（防 callJavaScript 巨串）
    /** Matches {@code src="url"} (HTML) and {@code ![alt](url)} (markdown) image refs. */
    private static final Pattern IMG_URL_PATTERN = Pattern.compile(
            "src\\s*=\\s*[\"']([^\"']+)[\"']|!\\[[^\\]]*\\]\\(([^)\\s]+)\\)",
            Pattern.CASE_INSENSITIVE);
    /** Extracts the file id from an embed-proxy URL ({@code .../file/url?fileIdentifier=<id>}). */
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("fileIdentifier=([A-Za-z0-9_-]+)");

    /**
     * Aggregate a bug's detail for the「查看详情」modal: basic info + description
     * (with 云效-hosted images inlined as data URIs so they render in the webview
     * despite needing the {@code x-yunxiao-token} header) + attachment list.
     *
     * @param workItemId 工作项内部 id（= 列表项 identifier）
     */
    public JsonObject getBugDetail(String workItemId) throws IOException {
        if (workItemId == null || workItemId.isBlank()) {
            throw new IOException("workItemId required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String wid = workItemId.trim();
        String workitemBase = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId + "/workitems/" + wid;

        // 基础信息 (GetWorkitem) — 硬失败
        JsonObject info = unwrap(execute(authed(workitemBase, token).GET().build()));

        JsonObject basic = new JsonObject();
        basic.addProperty("identifier", wid);
        basic.addProperty("serialNumber", strField(info, "serialNumber"));
        basic.addProperty("subject", strField(info, "subject"));
        basic.addProperty("status", displayOf(info, "status"));
        basic.addProperty("assignedTo", displayOf(info, "assignedTo"));
        basic.addProperty("creator", displayOf(info, "creator"));
        basic.addProperty("priority", displayOf(info, "priority"));
        if (info.has("gmtCreate") && info.get("gmtCreate").isJsonPrimitive()) {
            basic.add("gmtCreate", info.get("gmtCreate"));
        }
        if (info.has("gmtModified") && info.get("gmtModified").isJsonPrimitive()) {
            basic.add("gmtModified", info.get("gmtModified"));
        }

        // 描述可能是 {"htmlValue":"<html>","jsonMLValue":[...]} 的 JSON（云效富文本编辑器格式）;
        // 解出 htmlValue 当 HTML 渲染，否则按原 formatType 处理。
        String rawDesc = strField(info, "description");
        String formatType = strField(info, "formatType");
        String htmlFromJson = extractHtmlFromDescription(rawDesc);
        String description = (htmlFromJson != null) ? htmlFromJson : rawDesc;
        if (htmlFromJson != null) {
            formatType = "RICHTEXT";
        }
        String inlined = stripWhiteBackgrounds(inlineDescriptionImages(description, token, domain, orgId, wid));

        // 附件 (ListWorkitemAttachments) — 软降级
        JsonArray attachments = new JsonArray();
        try {
            JsonElement attResp = execute(authed(workitemBase + "/attachments", token).GET().build());
            JsonArray arr = firstArray(attResp, "result", "data", "attachments", "items");
            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject a = el.getAsJsonObject();
                    JsonObject out = new JsonObject();
                    out.addProperty("id", strField(a, "id"));
                    String name = strField(a, "fileName");
                    out.addProperty("name", name.isEmpty() ? strField(a, "name") : name);
                    if (a.has("size") && a.get("size").isJsonPrimitive()) {
                        out.add("size", a.get("size"));
                    }
                    out.addProperty("suffix", strField(a, "suffix"));
                    attachments.add(out);
                }
            }
        } catch (Exception e) {
            LOG.warn("[Yunxiao] list attachments failed: " + e.getMessage());
        }

        // 评论 (GetWorkitemCommentList) — 软降级；评论内容同样解 htmlValue + 内联图 + 剥白底
        JsonArray comments = new JsonArray();
        try {
            JsonElement cmtResp = execute(authed(workitemBase + "/comments?page=1&perPage=50", token).GET().build());
            JsonArray arr = firstArray(cmtResp, "result", "data", "comments", "items");
            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject c = el.getAsJsonObject();
                    // 评论 content 可能是 字符串(JSON-wrapped htmlValue 或 html) 或 直接是对象
                    // {htmlValue, jsonMLValue}（新版 API）。两者都要解，否则内容/图都丢。
                    String content = commentContentHtml(c);
                    content = stripWhiteBackgrounds(inlineDescriptionImages(content, token, domain, orgId, wid));

                    String author = displayOf(c, "user");
                    if (author.isEmpty()) {
                        author = displayOf(c, "creator");
                    }
                    if (author.isEmpty()) {
                        author = displayOf(c, "author");
                    }

                    JsonObject out = new JsonObject();
                    out.addProperty("content", content == null ? "" : content);
                    out.addProperty("author", author);
                    if (c.has("gmtCreate") && c.get("gmtCreate").isJsonPrimitive()) {
                        out.add("gmtCreate", c.get("gmtCreate"));
                    } else if (c.has("createdAt") && c.get("createdAt").isJsonPrimitive()) {
                        out.add("gmtCreate", c.get("createdAt"));
                    }
                    comments.add(out);
                }
            }
        } catch (Exception e) {
            LOG.warn("[Yunxiao] list comments failed: " + e.getMessage());
        }

        JsonObject r = new JsonObject();
        r.add("basic", basic);
        r.addProperty("formatType", formatType);
        r.addProperty("description", inlined == null ? "" : inlined);
        r.add("attachments", attachments);
        r.add("comments", comments);
        return r;
    }

    /**
     * Resolve a fresh (short-lived) OSS download URL for an attachment by re-listing
     * the workitem's attachments and matching {@code id} — the listed {@code url} is
     * regenerated each call, so it won't be expired by the time the browser opens it.
     */
    public String getAttachmentDownloadUrl(String workItemId, String attachmentId) throws IOException {
        if (workItemId == null || workItemId.isBlank() || attachmentId == null || attachmentId.isBlank()) {
            throw new IOException("workItemId/attachmentId required");
        }
        String token = requireToken();
        String orgId = requireOrgId();
        String domain = settings.getYunxiaoDomain();
        String attUrl = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                + "/workitems/" + workItemId.trim() + "/attachments";
        JsonElement resp = execute(authed(attUrl, token).GET().build());
        JsonArray arr = firstArray(resp, "result", "data", "attachments", "items");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject a = el.getAsJsonObject();
                if (attachmentId.trim().equals(strField(a, "id"))) {
                    String dl = strField(a, "url");
                    if (!dl.isEmpty()) {
                        return dl;
                    }
                }
            }
        }
        throw new IOException("未找到附件或下载地址已失效");
    }

    /**
     * Replace 云效-hosted image URLs in a workitem description with base64 data URIs.
     * 云效 embeds images via a token-gated proxy ({@code /api/workitem/file/url?...})
     * the webview's {@code <img>} can't authenticate against, so we fetch each (with
     * the token) and inline it. Best-effort, bounded by {@link #MAX_INLINE_IMAGES} /
     * {@link #MAX_INLINE_IMAGE_BYTES}; a fetch failure leaves the original URL intact.
     */
    private String inlineDescriptionImages(String description, String token, String domain, String orgId, String wid) {
        if (description == null || description.isEmpty()) {
            return description;
        }
        Matcher m = IMG_URL_PATTERN.matcher(description);
        Map<String, String> replacements = new LinkedHashMap<>();
        long budget = 0;
        while (m.find() && replacements.size() < MAX_INLINE_IMAGES && budget < TOTAL_INLINE_BUDGET) {
            String url = m.group(1) != null ? m.group(1) : m.group(2);
            if (url == null || url.startsWith("data:") || replacements.containsKey(url)) {
                continue;
            }
            String fetchUrl;
            if (url.startsWith("/")) {
                fetchUrl = "https://" + domain + url;          // relative proxy path
            } else if (url.startsWith("http")) {
                fetchUrl = url;
            } else {
                continue;
            }
            if (!isYunxiaoImageUrl(fetchUrl, domain)) {
                continue;                                       // leave external/public images as-is
            }
            String dataUri = resolveImageDataUri(fetchUrl, token, orgId, domain, wid);
            if (dataUri != null) {
                replacements.put(url, dataUri);
                budget += dataUri.length();
            }
        }
        String result = description;
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    /** Only inline images served by 云效 / Aliyun OSS / the workitem-file proxy. */
    private static boolean isYunxiaoImageUrl(String url, String domain) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains(domain.toLowerCase()) || u.contains("aliyuncs.com")
                || u.contains("/api/workitem/file") || u.contains("/oapi/");
    }

    /**
     * Turn a description image URL into a data URI. The 云效 embed URL
     * ({@code https://devops.aliyun.com/projex/api/workitem/file/url?fileIdentifier=<id>})
     * is a <b>web-console</b> proxy authenticated by login cookie, NOT the OpenAPI
     * token — fetching it with {@code x-yunxiao-token} fails. So when a
     * {@code fileIdentifier} is present, resolve it through the OpenAPI
     * {@code GetWorkitemFile} endpoint (which the token DOES authenticate) to a
     * fresh signed OSS URL, then inline that. Falls back to a direct fetch otherwise.
     */
    private String resolveImageDataUri(String url, String token, String orgId, String domain, String wid) {
        Matcher fm = FILE_ID_PATTERN.matcher(url);
        if (fm.find()) {
            String ossUrl = resolveWorkitemFileUrl(orgId, domain, wid, fm.group(1), token);
            if (ossUrl != null) {
                String dataUri = fetchImageAsDataUri(ossUrl, token);
                if (dataUri != null) {
                    return dataUri;
                }
            }
            // else fall through to a best-effort direct fetch
        }
        return fetchImageAsDataUri(url, token);
    }

    /** Resolve a workitem file id to a fresh OSS download URL via the OpenAPI GetWorkitemFile endpoint. */
    private String resolveWorkitemFileUrl(String orgId, String domain, String wid, String fileId, String token) {
        try {
            String url = "https://" + domain + "/oapi/v1/projex/organizations/" + orgId
                    + "/workitems/" + wid + "/files/" + fileId;
            JsonObject o = unwrap(execute(authed(url, token).GET().build()));
            String dl = strField(o, "url");
            return dl.isEmpty() ? null : dl;
        } catch (Exception e) {
            LOG.warn("[Yunxiao] resolve workitem file url failed (" + fileId + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Strip white/near-white inline backgrounds from 云效 rich-text HTML. The editor
     * emits {@code highlight:#ffffff} as a white {@code background-color}, which shows
     * as ugly white boxes behind text on the dark modal — drop only white (colored
     * highlights are kept).
     */
    private static String stripWhiteBackgrounds(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return html.replaceAll(
                "(?i)background(-color)?\\s*:\\s*(#fff(fff)?|white|rgb\\(\\s*255\\s*,\\s*255\\s*,\\s*255\\s*\\))\\s*;?",
                "");
    }

    /**
     * Extract a comment's HTML, tolerating 云效's variations: {@code content} may be a
     * string (JSON-wrapped {@code {htmlValue}} or raw HTML/markdown) OR a nested object
     * {@code {htmlValue, jsonMLValue}} (new OpenAPI). Falls back to a top-level
     * {@code htmlValue} or other text fields. Returns "" when nothing usable is found.
     */
    private static String commentContentHtml(JsonObject c) {
        if (c.has("content") && !c.get("content").isJsonNull()) {
            JsonElement el = c.get("content");
            if (el.isJsonPrimitive()) {
                String s = el.getAsString();
                String html = extractHtmlFromDescription(s);
                String r = (html != null) ? html : s;
                if (!r.isEmpty()) {
                    return r;
                }
            } else if (el.isJsonObject()) {
                String r = htmlFromObj(el.getAsJsonObject());
                if (!r.isEmpty()) {
                    return r;
                }
            }
        }
        if (c.has("htmlValue") && c.get("htmlValue").isJsonPrimitive()) {
            return c.get("htmlValue").getAsString();
        }
        for (String k : new String[]{"text", "description", "body"}) {
            if (c.has(k) && c.get(k).isJsonPrimitive()) {
                String s = c.get(k).getAsString();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    /** Pull HTML out of a rich-text object ({@code {htmlValue|html|value|content|text}}). */
    private static String htmlFromObj(JsonObject o) {
        for (String k : new String[]{"htmlValue", "html", "value", "content", "text"}) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                String v = o.get(k).getAsString();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return "";
    }

    /**
     * If the description is a JSON object from 云效's rich-text editor
     * ({@code {"htmlValue":"<html>","jsonMLValue":[...]}}), return the HTML; else null
     * so the caller keeps the original description + format type.
     */
    private static String extractHtmlFromDescription(String desc) {
        if (desc == null) {
            return null;
        }
        String t = desc.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(t).getAsJsonObject();
            for (String k : new String[]{"htmlValue", "html", "value", "content"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    String v = o.get(k).getAsString();
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
        } catch (Exception ignore) {
            // not a recognised JSON description — treat as plain HTML/markdown
        }
        return null;
    }

    /** Fetch an image with the token and return a {@code data:<mime>;base64,...} URI, or null on failure. */
    private String fetchImageAsDataUri(String url, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("x-yunxiao-token", token)
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0 || body.length > MAX_INLINE_IMAGE_BYTES) {
                return null;
            }
            String ct = resp.headers().firstValue("content-type").orElse("");
            if (ct.isEmpty() || !ct.toLowerCase().startsWith("image/")) {
                ct = guessImageType(url);
            }
            return "data:" + ct + ";base64," + Base64.getEncoder().encodeToString(body);
        } catch (Exception e) {
            LOG.warn("[Yunxiao] inline image failed (" + truncate(url) + "): " + e.getMessage());
            return null;
        }
    }

    /** Best-effort MIME from a URL extension; defaults to png (proxy/OSS urls often lack one). */
    private static String guessImageType(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (u.endsWith(".gif")) {
            return "image/gif";
        }
        if (u.endsWith(".webp")) {
            return "image/webp";
        }
        if (u.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (u.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    private static JsonObject unwrap(JsonElement resp) {
        if (resp == null || !resp.isJsonObject()) {
            return new JsonObject();
        }
        JsonObject o = resp.getAsJsonObject();
        if (o.has("subject") || o.has("serialNumber") || o.has("description") || o.has("url")) {
            return o;
        }
        for (String w : new String[]{"result", "data"}) {
            if (o.has(w) && o.get(w).isJsonObject()) {
                return o.getAsJsonObject(w);
            }
        }
        return o;
    }

    private static String strField(JsonObject o, String key) {
        if (o != null && o.has(key) && o.get(key).isJsonPrimitive()) {
            return o.get(key).getAsString();
        }
        return "";
    }

    /** Coerce a field that may be a string or a {@code {displayName|name|nickName}} object into a display string. */
    private static String displayOf(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            JsonObject obj = e.getAsJsonObject();
            for (String k : new String[]{"displayName", "name", "nickName"}) {
                if (obj.has(k) && obj.get(k).isJsonPrimitive()) {
                    return obj.get(k).getAsString();
                }
            }
        }
        return "";
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    /**
     * Build the stringified {@code conditions} payload (§3.1) that filters by
     * {@code assignedTo CONTAINS [userId]}. 云效 expects this nested condition
     * tree serialized as a JSON <em>string</em> inside the search body.
     */
    private static String buildAssignedToConditions(String userId) {
        JsonObject condition = new JsonObject();
        condition.addProperty("fieldIdentifier", "assignedTo");
        condition.addProperty("operator", "CONTAINS");
        JsonArray value = new JsonArray();
        value.add(userId);
        condition.add("value", value);
        condition.addProperty("className", "user");
        condition.addProperty("format", "list");

        JsonArray group = new JsonArray();
        group.add(condition);
        JsonArray conditionGroups = new JsonArray();
        conditionGroups.add(group);

        JsonObject conditions = new JsonObject();
        conditions.add("conditionGroups", conditionGroups);
        return conditions.toString();
    }

    // =========================================================================
    // HTTP plumbing
    // =========================================================================

    private HttpRequest.Builder authed(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("x-yunxiao-token", token)
                .header("Content-Type", "application/json");
    }

    /** Send a request, returning the parsed JSON body; throws on non-2xx. */
    private JsonElement execute(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code / 100 != 2) {
                throw new IOException("Yunxiao HTTP " + code + ": " + extractErrorMessage(resp.body()));
            }
            String responseBody = resp.body();
            if (responseBody == null || responseBody.isEmpty()) {
                return JsonNull.INSTANCE;
            }
            return JsonParser.parseString(responseBody);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Yunxiao request interrupted", e);
        } catch (Exception e) {
            throw new IOException("Yunxiao request failed: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    private static String extractUserId(JsonElement resp) throws IOException {
        if (resp == null || !resp.isJsonObject()) {
            throw new IOException("Yunxiao user response is not a JSON object");
        }
        JsonObject obj = resp.getAsJsonObject();
        String id = readId(obj);
        if (id == null && obj.has("result") && obj.get("result").isJsonObject()) {
            id = readId(obj.getAsJsonObject("result"));
        }
        if (id == null && obj.has("data") && obj.get("data").isJsonObject()) {
            id = readId(obj.getAsJsonObject("data"));
        }
        if (id == null) {
            throw new IOException("Yunxiao user response missing id");
        }
        return id;
    }

    /**
     * Read the user {@code id}. 云效 returns an ObjectId-style hex string
     * (e.g. {@code "654458cef717cdf76f826b62"}), so it is read as a string —
     * never coerced to a number (the old numeric parse is what produced the
     * "missing numeric id" failure).
     */
    private static String readId(JsonObject o) {
        if (o == null || !o.has("id") || o.get("id").isJsonNull()) {
            return null;
        }
        try {
            String id = o.get("id").getAsString().trim();
            return id.isEmpty() ? null : id;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Keep only the contract fields so the list-item shape stays stable. */
    private static JsonObject normalizeBug(JsonObject raw) {
        JsonObject bug = new JsonObject();
        for (String field : BUG_FIELDS) {
            if (raw.has(field) && !raw.get(field).isJsonNull()) {
                bug.add(field, raw.get(field));
            }
        }
        // 工作项的 GetWorkitem key 是 `id`；列表/预填/query_bug_details 统一用 `identifier`，
        // 所以当 SearchWorkitems 没回 `identifier`（常态）时，用 `id` 兜底填上，否则预填里
        // 的 bug id 会是空的（监督者只能拿显示编号 serialNumber 兜底 → 404）。
        if ((!bug.has("identifier") || bug.get("identifier").isJsonNull())
                && raw.has("id") && !raw.get("id").isJsonNull()) {
            bug.add("identifier", raw.get("id"));
        }
        return bug;
    }

    /**
     * Locate the items array in a search/list response, tolerating both a
     * top-level array and an object wrapping the array under one of {@code keys}
     * (optionally nested under a {@code result}/{@code data}/{@code workflow}
     * envelope, including a {@code result.workflow} double-wrap — the workflow
     * status list shows up as {@code {workflow:{statuses:[]}}} on the legacy shape).
     * Exact container key is pending OpenAPI pin; this stays robust to either shape.
     */
    private static JsonArray firstArray(JsonElement resp, String... keys) {
        if (resp == null) {
            return null;
        }
        if (resp.isJsonArray()) {
            return resp.getAsJsonArray();
        }
        if (!resp.isJsonObject()) {
            return null;
        }
        JsonObject obj = resp.getAsJsonObject();
        JsonArray hit = arrayUnder(obj, keys);
        if (hit != null) {
            return hit;
        }
        for (String wrapper : new String[]{"result", "data", "workflow"}) {
            if (obj.has(wrapper) && obj.get(wrapper).isJsonObject()) {
                JsonObject inner = obj.getAsJsonObject(wrapper);
                hit = arrayUnder(inner, keys);
                if (hit != null) {
                    return hit;
                }
                // one extra level: e.g. result.workflow.statuses
                if (inner.has("workflow") && inner.get("workflow").isJsonObject()) {
                    hit = arrayUnder(inner.getAsJsonObject("workflow"), keys);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    /** Return the first {@code keys} entry that is a JSON array directly under {@code obj}, else null. */
    private static JsonArray arrayUnder(JsonObject obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                return obj.getAsJsonArray(key);
            }
        }
        return null;
    }

    /**
     * Derive {@code hasMore}: prefer a server-reported total when present,
     * otherwise fall back to "a full page implies more".
     */
    private static boolean computeHasMore(JsonElement resp, int page, int perPage, int pageCount) {
        Long total = findTotal(resp);
        if (total != null) {
            return (long) page * perPage < total;
        }
        return pageCount >= perPage;
    }

    private static Long findTotal(JsonElement resp) {
        if (resp == null || !resp.isJsonObject()) {
            return null;
        }
        JsonObject obj = resp.getAsJsonObject();
        Long total = readLong(obj, "totalCount", "total");
        if (total != null) {
            return total;
        }
        for (String wrapper : new String[]{"result", "data"}) {
            if (obj.has(wrapper) && obj.get(wrapper).isJsonObject()) {
                total = readLong(obj.getAsJsonObject(wrapper), "totalCount", "total");
                if (total != null) {
                    return total;
                }
            }
        }
        return null;
    }

    private static Long readLong(JsonObject o, String... keys) {
        for (String key : keys) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                try {
                    return o.get(key).getAsLong();
                } catch (Exception ignore) {
                    // try next key
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Config / misc
    // =========================================================================

    private String requireToken() throws IOException {
        String token = settings.getYunxiaoToken();
        if (token == null || token.isBlank()) {
            throw new IOException("云效 token 未配置");
        }
        return token.trim();
    }

    private String requireOrgId() throws IOException {
        String orgId = settings.getYunxiaoOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new IOException("云效 organizationId 未配置");
        }
        return orgId.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /**
     * Pull a human-readable message out of a 云效 error body
     * ({@code errorMessage}/{@code errorMsg}/{@code message}/...), so a failed status
     * change surfaces e.g. "不允许的状态流转" instead of a raw JSON blob. Falls back to
     * the truncated body when it isn't recognisable JSON.
     */
    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                for (String k : new String[]{"errorMessage", "errorMsg", "message", "errMsg", "msg"}) {
                    if (o.has(k) && o.get(k).isJsonPrimitive()) {
                        String v = o.get(k).getAsString();
                        if (!v.isBlank()) {
                            return v;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // not JSON — fall through to the raw (truncated) body
        }
        return truncate(body);
    }
}
