package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Supervisor Agent Manager.
 * Manages supervisor agent configurations (supervisor-agents.json).
 * Each supervisor agent has: id, name, description, model.
 * Supports a default agent that is auto-selected when enabling supervisor on a session.
 */
public class SupervisorAgentManager {
    private static final Logger LOG = Logger.getInstance(SupervisorAgentManager.class);

    public static final int CONFIG_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 30;
    public static final int MAX_DESCRIPTION_LENGTH = 100_000;
    /**
     * Default model for built-in supervisor personas. Opus 4.8 paired with the
     * built-in {@code defaultLongContext=true} / {@code defaultReasoning="max"}
     * fields gives the supervisor enough room (1M ctx) and judgment depth (max
     * effort) to handle the multi-step review protocol baked into the v3 prompt.
     * The bare base id is stored here; the [1m] suffix is applied at send time
     * by the webview when {@code longContextEnabled} is on.
     */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    /** Default reasoning-effort tier seeded on built-in supervisor agents. */
    public static final String DEFAULT_REASONING_EFFORT = "max";
    /** Default 1M-context flag seeded on built-in supervisor agents. */
    public static final boolean DEFAULT_LONG_CONTEXT = true;

    /** Legacy DEFAULT_MODEL value, retained so {@link #isKnownPresetModel}
     * recognises old Haiku-seeded agents and migrates them to the new default. */
    private static final String LEGACY_HAIKU_MODEL_ID = "claude-haiku-4-5-20251001";

    /** Default auto-compact trigger as a % of context window (CLI default ≈ 95). */
    public static final int DEFAULT_AUTO_COMPACT_THRESHOLD = 70;
    public static final int MIN_AUTO_COMPACT_THRESHOLD = 50;
    public static final int MAX_AUTO_COMPACT_THRESHOLD = 95;

    private final Gson gson;
    private final ConfigPathManager pathManager;

    public SupervisorAgentManager(Gson gson, ConfigPathManager pathManager) {
        this.gson = gson;
        this.pathManager = pathManager;
    }

    /**
     * Read the supervisor-agents.json file.
     */
    public JsonObject readConfig() throws IOException {
        Path filePath = pathManager.getSupervisorAgentFilePath();
        File file = filePath.toFile();

        if (!file.exists()) {
            JsonObject config = new JsonObject();
            config.addProperty("version", CONFIG_VERSION);
            config.add("agents", new JsonObject());
            return config;
        }

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            if (!config.has("agents")) {
                config.add("agents", new JsonObject());
            }
            if (!config.has("version")) {
                config.addProperty("version", CONFIG_VERSION);
            }
            return config;
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentManager] Failed to read supervisor-agents.json: " + e.getMessage());
            JsonObject config = new JsonObject();
            config.addProperty("version", CONFIG_VERSION);
            config.add("agents", new JsonObject());
            return config;
        }
    }

    /**
     * Write the supervisor-agents.json file.
     */
    public void writeConfig(JsonObject config) throws IOException {
        pathManager.ensureConfigDirectory();

        Path filePath = pathManager.getSupervisorAgentFilePath();
        try (FileWriter writer = new FileWriter(filePath.toFile(), StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
            LOG.info("[SupervisorAgentManager] Wrote supervisor-agents.json");
        } catch (Exception e) {
            LOG.warn("[SupervisorAgentManager] Failed to write supervisor-agents.json: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Get all supervisor agents, sorted by creation time descending (newest first).
     */
    public List<JsonObject> getAgents() throws IOException {
        List<JsonObject> result = new ArrayList<>();
        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");

        for (String key : agents.keySet()) {
            JsonObject agent = agents.getAsJsonObject(key);
            if (!agent.has("id")) {
                agent.addProperty("id", key);
            }
            result.add(agent);
        }

        result.sort((a, b) -> {
            long timeA = a.has("createdAt") ? a.get("createdAt").getAsLong() : 0;
            long timeB = b.has("createdAt") ? b.get("createdAt").getAsLong() : 0;
            return Long.compare(timeB, timeA);
        });

        return result;
    }

    /**
     * Get a single supervisor agent by ID.
     */
    public JsonObject getAgent(String id) throws IOException {
        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");
        if (!agents.has(id)) {
            return null;
        }
        JsonObject agent = agents.getAsJsonObject(id);
        if (!agent.has("id")) {
            agent.addProperty("id", id);
        }
        return agent;
    }

    /**
     * Add a new supervisor agent.
     */
    public void addAgent(JsonObject agent) throws IOException {
        String validationError = validateAgent(agent);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");
        String id = agent.get("id").getAsString();

        if (agents.has(id)) {
            throw new IllegalArgumentException("Supervisor agent with id '" + id + "' already exists");
        }

        if (!agent.has("createdAt")) {
            agent.addProperty("createdAt", System.currentTimeMillis());
        }
        agent.addProperty("updatedAt", System.currentTimeMillis());

        // Default model if missing
        if (!agent.has("model") || agent.get("model").isJsonNull()) {
            agent.addProperty("model", DEFAULT_MODEL);
        }

        agents.add(id, agent);
        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Added supervisor agent: " + id);
    }

    /**
     * Update an existing supervisor agent.
     */
    public void updateAgent(String id, JsonObject updates) throws IOException {
        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");

        if (!agents.has(id)) {
            throw new IllegalArgumentException("Supervisor agent with id '" + id + "' not found");
        }

        JsonObject agent = agents.getAsJsonObject(id);

        for (String key : updates.keySet()) {
            // id and createdAt are immutable
            if (key.equals("id") || key.equals("createdAt")) {
                continue;
            }
            if (updates.get(key).isJsonNull()) {
                agent.remove(key);
            } else {
                agent.add(key, updates.get(key));
            }
        }
        agent.addProperty("updatedAt", System.currentTimeMillis());

        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Updated supervisor agent: " + id);
    }

    /**
     * Delete a supervisor agent. If the deleted agent is the default, the default is cleared.
     */
    public boolean deleteAgent(String id) throws IOException {
        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");

        if (!agents.has(id)) {
            return false;
        }
        agents.remove(id);

        // Clear default if it pointed to the deleted agent
        if (config.has("defaultAgentId")
                && !config.get("defaultAgentId").isJsonNull()
                && id.equals(config.get("defaultAgentId").getAsString())) {
            config.remove("defaultAgentId");
        }

        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Deleted supervisor agent: " + id);
        return true;
    }

    /**
     * Get the default supervisor agent ID (used when user clicks "enable supervisor" without explicit choice).
     */
    public String getDefaultAgentId() throws IOException {
        JsonObject config = readConfig();
        if (config.has("defaultAgentId") && !config.get("defaultAgentId").isJsonNull()) {
            return config.get("defaultAgentId").getAsString();
        }
        return null;
    }

    /**
     * Read the auto-compact threshold for the daemon (a % of the model's
     * context window — when prompt token count crosses this, the CLI triggers
     * its built-in autocompact). Returns {@link #DEFAULT_AUTO_COMPACT_THRESHOLD}
     * when unset or invalid.
     */
    public int getAutoCompactThreshold() throws IOException {
        JsonObject config = readConfig();
        if (config.has("autoCompactThreshold") && !config.get("autoCompactThreshold").isJsonNull()) {
            try {
                int v = config.get("autoCompactThreshold").getAsInt();
                if (v >= MIN_AUTO_COMPACT_THRESHOLD && v <= MAX_AUTO_COMPACT_THRESHOLD) {
                    return v;
                }
            } catch (Exception ignored) { /* fall through to default */ }
        }
        return DEFAULT_AUTO_COMPACT_THRESHOLD;
    }

    /**
     * Persist the auto-compact threshold. Out-of-range values throw — caller
     * is expected to validate at the UI layer.
     */
    public void setAutoCompactThreshold(int threshold) throws IOException {
        if (threshold < MIN_AUTO_COMPACT_THRESHOLD || threshold > MAX_AUTO_COMPACT_THRESHOLD) {
            throw new IllegalArgumentException(
                "autoCompactThreshold must be between "
                + MIN_AUTO_COMPACT_THRESHOLD + " and " + MAX_AUTO_COMPACT_THRESHOLD);
        }
        JsonObject config = readConfig();
        config.addProperty("autoCompactThreshold", threshold);
        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Set autoCompactThreshold=" + threshold);
    }

    /**
     * Read the rotation-trigger thresholds shared by supervisor and main-AI
     * rotation paths. Returns defaults when the file lacks a {@code rotationConfig}
     * object or any field is out of range / inconsistent.
     */
    public RotationConfig getRotationConfig() throws IOException {
        JsonObject config = readConfig();
        if (!config.has("rotationConfig") || !config.get("rotationConfig").isJsonObject()) {
            return RotationConfig.defaults();
        }
        JsonObject obj = config.getAsJsonObject("rotationConfig");
        double softR = readDouble(obj, "softRatio", RotationConfig.DEFAULT_SOFT_RATIO);
        double hardR = readDouble(obj, "hardRatio", RotationConfig.DEFAULT_HARD_RATIO);
        int softC = readInt(obj, "softCompact", RotationConfig.DEFAULT_SOFT_COMPACT);
        int hardC = readInt(obj, "hardCompact", RotationConfig.DEFAULT_HARD_COMPACT);
        RotationConfig candidate = new RotationConfig(softR, hardR, softC, hardC);
        if (candidate.validate() != null) {
            LOG.warn("[SupervisorAgentManager] Invalid persisted rotationConfig — using defaults");
            return RotationConfig.defaults();
        }
        return candidate;
    }

    public void setRotationConfig(RotationConfig next) throws IOException {
        if (next == null) {
            throw new IllegalArgumentException("rotationConfig must not be null");
        }
        String err = next.validate();
        if (err != null) {
            throw new IllegalArgumentException("rotationConfig invalid: " + err);
        }
        JsonObject config = readConfig();
        JsonObject obj = new JsonObject();
        obj.addProperty("softRatio", next.softRatio);
        obj.addProperty("hardRatio", next.hardRatio);
        obj.addProperty("softCompact", next.softCompact);
        obj.addProperty("hardCompact", next.hardCompact);
        config.add("rotationConfig", obj);
        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Set " + next);
    }

    private static double readDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception ignored) { return fallback; }
    }

    private static int readInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try { return obj.get(key).getAsInt(); }
        catch (Exception ignored) { return fallback; }
    }

    /**
     * Set the default supervisor agent ID. Pass null/empty to clear.
     */
    public void setDefaultAgentId(String agentId) throws IOException {
        JsonObject config = readConfig();
        if (agentId == null || agentId.isEmpty()) {
            config.remove("defaultAgentId");
        } else {
            JsonObject agents = config.getAsJsonObject("agents");
            if (!agents.has(agentId)) {
                throw new IllegalArgumentException("Cannot set default to non-existent agent: " + agentId);
            }
            config.addProperty("defaultAgentId", agentId);
        }
        writeConfig(config);
        LOG.info("[SupervisorAgentManager] Set default supervisor agent: " + agentId);
    }

    /**
     * Validate a supervisor agent object.
     * @return null if valid, error message otherwise
     */
    public String validateAgent(JsonObject agent) {
        if (agent == null) {
            return "Supervisor agent data is null";
        }
        if (!agent.has("id") || agent.get("id").isJsonNull()) {
            return "Missing required field: id";
        }
        if (!agent.has("name") || agent.get("name").isJsonNull()) {
            return "Missing required field: name";
        }
        String name = agent.get("name").getAsString();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return "Supervisor agent name must be 1-" + MAX_NAME_LENGTH + " characters";
        }
        if (!agent.has("description") || agent.get("description").isJsonNull()) {
            return "Missing required field: description";
        }
        String description = agent.get("description").getAsString();
        if (description.isEmpty()) {
            return "Supervisor agent description must not be empty";
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            return "Supervisor agent description must be less than " + MAX_DESCRIPTION_LENGTH + " characters";
        }
        return null;
    }

    /** Legacy built-in agent ids replaced by v3's single code-supervisor. */
    private static final String[] LEGACY_BUILTIN_IDS = { "full-auto", "go-strict", "security" };

    /**
     * Ensure the single built-in supervisor agent ("code-supervisor") exists,
     * and refresh its description to the current plugin's preset if stale.
     *
     * <p>v3 migration: legacy built-in agents (full-auto / go-strict /
     * security) that are still {@code builtIn=true} get removed — they've
     * been collapsed into a single code-supervisor persona. User-customised
     * copies (where {@code builtIn} was unset) are preserved untouched.
     *
     * <p>"Stale" rule: a built-in agent whose description doesn't contain
     * the current preset's first-line marker is refreshed — this lets plugin
     * updates ship improved persona text without forcing the user to copy
     * it from the docs.
     *
     * <p>To opt out of refresh, un-set {@code builtIn} on the agent (then
     * it becomes "owned" by the user).
     */
    public void ensureDefaults() throws IOException {
        JsonObject config = readConfig();
        JsonObject agents = config.getAsJsonObject("agents");
        long now = System.currentTimeMillis();
        boolean dirty = false;

        // v3 migration: drop legacy built-in agents superseded by code-supervisor.
        for (String legacyId : LEGACY_BUILTIN_IDS) {
            if (!agents.has(legacyId)) continue;
            JsonObject legacy = agents.getAsJsonObject(legacyId);
            boolean wasBuiltIn = legacy.has("builtIn")
                    && !legacy.get("builtIn").isJsonNull()
                    && legacy.get("builtIn").getAsBoolean();
            if (wasBuiltIn) {
                agents.remove(legacyId);
                dirty = true;
                LOG.info("[SupervisorAgentManager] Removed legacy built-in agent: " + legacyId);
            }
        }

        dirty |= ensureOrRefresh(agents, "code-supervisor",
                "编码监督者 / Code Supervisor",
                loadPreset("code-supervisor"),
                DEFAULT_MODEL,
                now,
                false);

        // v3.1: second built-in persona — upstream "design / plan supervisor"
        // that consumes a raw design doc and emits the structured plan that
        // code-supervisor downstream consumes. Lives next to code-supervisor in
        // the picker; default still points at code-supervisor (see below).
        dirty |= ensureOrRefresh(agents, "design-supervisor",
                "方案监督者 / Design Supervisor",
                loadPreset("design-supervisor"),
                DEFAULT_MODEL,
                now,
                false);

        // v1: third built-in persona — bug supervisor. Takes a bug
        // description from the user's first message (plain text / URL / both)
        // and drives main AI through: diagnose (read-only) → branch decision
        // (auto-pick or escalate based on candidates count + complexity + risk
        // + scope) → apply_fix → three-layer verification (scope check +
        // regression reviewer + compile, with sandbox-aware fallback).
        // Standalone — no upstream/downstream supervisor coupling; produces
        // its own fix summary via complete_plan for Java-side archival.
        dirty |= ensureOrRefresh(agents, "bug-supervisor",
                "缺陷监督者 / Bug Supervisor",
                loadPreset("bug-supervisor"),
                DEFAULT_MODEL,
                now,
                true);  // 2026-06-01: MCP access for data-aware diagnose/verify

        // v1: fourth built-in persona — unit-test supervisor. Standalone, like
        // bug-supervisor: takes a test target (package / file / function) from
        // the user's first message and drives main AI through: skill-match gate
        // → baseline run (attribute pre-existing vs introduced failures) →
        // test plan → write tests → run + zero-human self-heal loop (test-code
        // bug vs product-code bug, auto-fixed and only recorded; unfixable items
        // quarantined, never escalated) → complete_plan with a coverage + fixed-
        // bug + quarantine report. Decision matrix is A/B/Q/T (no human-facing
        // action) instead of the A/B/C1/C2/C3 used by code/design/bug.
        dirty |= ensureOrRefresh(agents, "unit-test-supervisor",
                "单元测试监督者 / Unit Test Supervisor",
                loadPreset("unit-test-supervisor"),
                DEFAULT_MODEL,
                now,
                true);  // 2026-06-01: MCP access for data-aware test self-heal

        // v1: fifth built-in persona — API/interface-test supervisor.
        // Standalone: takes a service target (Java/Go) from the user's first
        // message and drives main AI through: skill-match gate + ServiceSpec
        // inference + read-only verify-MCP self-check → endpoint inventory →
        // start service (code/config failures self-heal; missing middleware →
        // terminal-partial) → per-endpoint mock + curl (queue side-effects
        // default-success) → verify + zero-human self-heal loop where the
        // supervisor itself runs read-only MySQL/Redis MCP to confirm side
        // effects (anti-hallucination) → complete_plan after tearing the
        // service down. Same A/B/Q/T matrix; never escalates.
        dirty |= ensureOrRefresh(agents, "api-test-supervisor",
                "接口测试监督者 / API Test Supervisor",
                loadPreset("api-test-supervisor"),
                DEFAULT_MODEL,
                now,
                true);  // 2026-06-01: MCP access for read-only DB/Redis verify

        // Default points at code-supervisor unless the user picked something
        // else that still exists. If the previous default referenced a removed
        // legacy id, redirect.
        String currentDefault = config.has("defaultAgentId") && !config.get("defaultAgentId").isJsonNull()
                ? config.get("defaultAgentId").getAsString() : null;
        boolean defaultMissing = currentDefault == null || !agents.has(currentDefault);
        if (defaultMissing) {
            config.addProperty("defaultAgentId", "code-supervisor");
            dirty = true;
        }

        if (dirty) {
            writeConfig(config);
            LOG.info("[SupervisorAgentManager] Built-in supervisor agents seeded/refreshed");
        }
    }

    /**
     * Load a built-in supervisor persona from the classpath at
     * {@code /supervisor/<name>.md}. Returns empty string on miss/error;
     * {@link #ensureOrRefresh} treats empty preset as a no-op.
     */
    private static String loadPreset(String name) {
        String path = "/supervisor/" + name + ".md";
        try (InputStream is = SupervisorAgentManager.class.getResourceAsStream(path)) {
            if (is == null) {
                LOG.warn("[SupervisorAgentManager] Preset resource not found: " + path);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[SupervisorAgentManager] Failed to load preset " + path + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * Insert or refresh a built-in agent in place.
     *
     * <p>Refresh triggers on any drift between the persisted agent and the
     * preset — description, defaultLongContext, defaultReasoning, or mcpAccess
     * differs.
     * Earlier versions used a first-line marker to detect "is the description
     * still the old copy", but that silently failed whenever a prompt rewrite
     * kept the same opening line; full-content comparison is more reliable.
     *
     * <p>Model is only refreshed when the persisted value is still a known
     * preset model (so users who switched to e.g. Sonnet don't get yanked
     * back to the default).
     *
     * @return true if the agents object was mutated (caller decides whether to write).
     */
    private boolean ensureOrRefresh(JsonObject agents, String id, String name,
                                    String preset, String model, long ts, boolean mcpAccess) {
        // Defensive: an empty preset (e.g. resource lookup failed at runtime)
        // would match nothing and trigger refresh every startup; skip instead.
        if (preset == null || preset.isEmpty()) {
            LOG.warn("[SupervisorAgentManager] Empty preset for: " + id + " — skipping ensure/refresh");
            return false;
        }

        if (!agents.has(id)) {
            agents.add(id, buildBuiltInAgent(id, name, preset, model, ts, mcpAccess));
            return true;
        }

        JsonObject existing = agents.getAsJsonObject(id);
        boolean isBuiltIn = existing.has("builtIn")
                && !existing.get("builtIn").isJsonNull()
                && existing.get("builtIn").getAsBoolean();
        if (!isBuiltIn) {
            // User has taken ownership — don't touch.
            return false;
        }

        String currentDesc = existing.has("description") && !existing.get("description").isJsonNull()
                ? existing.get("description").getAsString() : "";
        boolean descMatches = currentDesc.equals(preset);
        boolean longCtxMatches = existing.has("defaultLongContext")
                && !existing.get("defaultLongContext").isJsonNull()
                && existing.get("defaultLongContext").getAsBoolean() == DEFAULT_LONG_CONTEXT;
        boolean reasoningMatches = existing.has("defaultReasoning")
                && !existing.get("defaultReasoning").isJsonNull()
                && DEFAULT_REASONING_EFFORT.equals(existing.get("defaultReasoning").getAsString());
        // 2026-06-01: mcpAccess drift — seed/refresh so built-ins pick up the
        // flag on a plugin update (true for bug/unit-test/api-test).
        boolean mcpAccessMatches = existing.has("mcpAccess")
                && !existing.get("mcpAccess").isJsonNull()
                && existing.get("mcpAccess").getAsBoolean() == mcpAccess;
        if (descMatches && longCtxMatches && reasoningMatches && mcpAccessMatches) {
            return false;
        }

        existing.addProperty("description", preset);
        existing.addProperty("defaultLongContext", DEFAULT_LONG_CONTEXT);
        existing.addProperty("defaultReasoning", DEFAULT_REASONING_EFFORT);
        existing.addProperty("mcpAccess", mcpAccess);
        existing.addProperty("updatedAt", ts);
        // Refresh model only if user hasn't customised it away from a known preset model.
        if (!existing.has("model") || existing.get("model").isJsonNull()
                || isKnownPresetModel(existing.get("model").getAsString())) {
            existing.addProperty("model", model);
        }
        LOG.info("[SupervisorAgentManager] Refreshed built-in supervisor: " + id);
        return true;
    }

    private static boolean isKnownPresetModel(String m) {
        return DEFAULT_MODEL.equals(m)
                || LEGACY_HAIKU_MODEL_ID.equals(m)
                || "claude-sonnet-4-6".equals(m)
                || "claude-opus-4-8".equals(m)
                || "claude-opus-4-7".equals(m)
                || "claude-opus-4-6".equals(m)
                || "gpt-5.5".equals(m);
    }

    private JsonObject buildBuiltInAgent(String id, String name, String description, String model,
                                         long ts, boolean mcpAccess) {
        JsonObject agent = new JsonObject();
        agent.addProperty("id", id);
        agent.addProperty("name", name);
        agent.addProperty("description", description);
        agent.addProperty("model", model);
        agent.addProperty("defaultLongContext", DEFAULT_LONG_CONTEXT);
        agent.addProperty("defaultReasoning", DEFAULT_REASONING_EFFORT);
        agent.addProperty("mcpAccess", mcpAccess);
        agent.addProperty("builtIn", true);
        agent.addProperty("createdAt", ts);
        agent.addProperty("updatedAt", ts);
        return agent;
    }

    /**
     * Read the {@code mcpAccess} flag from an agent config. When true (seeded on
     * bug-supervisor / unit-test-supervisor / api-test-supervisor), the daemon
     * attaches ALL {@code claude mcp add} servers to that supervisor session.
     */
    public static boolean isMcpAccess(JsonObject agent) {
        return agent != null && agent.has("mcpAccess")
                && !agent.get("mcpAccess").isJsonNull()
                && agent.get("mcpAccess").getAsBoolean();
    }

    // v3: built-in supervisor personas live as classpath resources under
    // /supervisor/<id>.md. The `code-supervisor` preset mirrors the doc at
    // docs/supervisor/code.md so the markdown source-of-truth and the
    // packaged persona stay in lock-step. Loaded lazily via loadPreset().
    //
    // Legacy v2 PRESET_* constants below are no longer referenced; they're
    // retained for one release as a reference for users migrating off the
    // old built-ins, and will be removed once the migration window closes.
    @SuppressWarnings("unused")
    private static final String PRESET_FULL_AUTO_DESCRIPTION =
            "你是任务调度监工兼代码审查员（v2）。严格按用户提供的方案推进主 AI，并在每一步完成后强制 review。\n\n" +
            "# 核心铁律\n" +
            "1. **方案 = 唯一真相**。用户给的方案/plan 是标准答案，主 AI 必须 100% 按方案执行。\n" +
            "2. **绝对禁止发散**。不要添加方案外的步骤、文件、检查、优化、重构、命名建议、日志输出、错误处理改进、注释建议、测试用例、文档生成、依赖升级、配置调整……一切方案没明确要求的都不做。\n" +
            "3. **每步必 review，未通过不推进**。主 AI 一步完成后，强制按方案要求 + 项目技能包做 review；有问题先反馈让主 AI 修复，修复后再 review，直到通过才能推进下一步或 escalate 验收。\n" +
            "4. **API 错误必自愈，不要直接停**。主 AI turn 因 API 错误中断时，必须输出 retry_with_hint 让会话继续；除非连续 retry 3 次仍失败或是 401/auth 类不可恢复错误，否则**绝不**输出 escalate_to_human。\n" +
            "5. **方案外修改 = 立即升级**。主 AI 修改方案外文件/范围 → 立即 escalate_to_human。\n" +
            "6. **方案未提供 = 立即升级**。用户没给方案前，禁止 inject_prompt；用 escalate_to_human 要用户先提供方案。\n\n" +
            "# Review 强制流程（最重要）\n" +
            "收到 turn_end 事件后，**先 review 再决定下一步**。Review 检查项必须包括：\n" +
            "  A) 方案符合性：本步骤产出是否完整覆盖方案对应章节的要求？文件、产出物、命名、范围是否一致？\n" +
            "  B) 项目技能包规范：system prompt「项目适用规范 / 技能包」段列出的每个技能包，是否都被遵守？\n" +
            "     例如 `golang-standards` → 错误处理 / 日志 / 命名；`spring-boot3-service-creator` → 模板文件/配置/目录结构。\n" +
            "  C) 实际工具调用：主 AI 是否用了正确的工具（Read 探查模板、Write 落地产物等）？有没有遗漏关键文件？\n\n" +
            "Review 结论分支：\n" +
            "  ✓ **通过** → inject_prompt 推进方案的下一步（或全部完成时 escalate 验收）\n" +
            "  ✗ **有问题** → inject_prompt 反馈具体问题给主 AI，让它修复。反馈格式严格如下：\n" +
            "    ```\n" +
            "    Review 未通过，请按以下问题修正（不要做其它改动）：\n" +
            "    1. [规范名 或 方案章节] 文件:行号（如适用） — 问题描述\n" +
            "       建议修正：<明确的一句话指引>\n" +
            "    2. ...\n" +
            "    修正后无需自行 verify，等我下一轮 review。\n" +
            "    ```\n" +
            "  ⚠ **同一步骤 review 连续失败 ≥ 3 次** → escalate_to_human，附上历次失败原因\n\n" +
            "# inject_prompt 写法约束（推进型）\n" +
            "- 内容必须**直接引用方案原文** + 当前步骤编号\n" +
            "- 一句话格式：「请按方案的步骤 N 执行：<方案原文摘录>」\n" +
            "- 禁止 \"如有需要可以...\" / \"建议你...\" / \"考虑一下...\" 这类发散语\n\n" +
            "# inject_prompt 写法约束（review 反馈型）\n" +
            "- 用上面给的 Review 反馈格式\n" +
            "- 每条问题必须指出违反的规范名 / 方案章节名\n" +
            "- 如有可能，给文件名 + 行号或字段名\n" +
            "- 不要批评，给出具体修正动作\n\n" +
            "# API 错误处理决策表（必背）\n" +
            "  429 / 5xx                 → retry_with_hint (wait_seconds: 15)\n" +
            "  timeout                   → retry_with_hint (wait_seconds: 5)\n" +
            "  context_overflow          → retry_with_hint，prompt 提示主 AI 简化输入或分批\n" +
            "  401 / auth                → escalate_to_human（用户必须重新配置 API key）\n" +
            "  同一 error code 连续 3 次  → escalate_to_human\n" +
            "  其它未知错误               → retry_with_hint 1 次，仍失败再 escalate\n" +
            "**绝不**在第一次 API 错误就 escalate。\n\n" +
            "# 你不做的事\n" +
            "- 不修改方案、不补全方案空白\n" +
            "- 不向主 AI 建议方案外的工具调用\n" +
            "- 不评价代码风格（除非方案或技能包明确包含该规范）\n" +
            "- 不主动发起方案外的 refactor / cleanup\n\n" +
            "# 输出格式\n" +
            "每次决策只输出一个 ACTION，附简短 reason。不要长篇大论。\n";

    @SuppressWarnings("unused")
    private static final String PRESET_GO_STRICT_DESCRIPTION =
            "你是 Go 代码严格审查员。\n\n" +
            "# 强制规范\n" +
            "- 错误处理：所有外部错误必须用 errs.New(ErrXxx) 包装，不能直接返回 sql.ErrNoRows 等原始错误\n" +
            "- 上下文传递：DAO/Service 层函数签名必须以 (ctx context.Context, ...) 开头\n" +
            "- 分层：Logic 不能直接调 SQL，必须经 DAO；Controller 不能直接调 DAO\n" +
            "- 命名：导出函数大驼峰，包内变量小驼峰，常量全大写\n" +
            "- 日志：禁止 fmt.Println，必须用项目的 log 包\n\n" +
            "# 职责\n" +
            "每收到 REVIEW_REQUEST 事件后：\n" +
            "- 扫描 diff，逐条比对强制规范\n" +
            "- 全部通过 → approve_and_continue\n" +
            "- 发现违规 → inject_prompt，反馈中必须包含：违反的规范名、文件:行号、建议改法\n";

    @SuppressWarnings("unused")
    private static final String PRESET_SECURITY_DESCRIPTION =
            "你是代码安全审查员。\n\n" +
            "# 重点关注\n" +
            "- SQL 注入：禁止字符串拼接 SQL，必须用占位符\n" +
            "- 命令注入：禁止 exec.Command 拼接用户输入\n" +
            "- 敏感信息：禁止把 password/token/key 写入日志\n" +
            "- 输入验证：所有外部输入必须校验长度、格式\n" +
            "- 错误信息：不能把内部细节（堆栈、SQL）返回给客户端\n\n" +
            "# 职责\n" +
            "每收到 REVIEW_REQUEST 事件后：\n" +
            "- 扫描 diff 中的安全风险点\n" +
            "- 无风险 → approve_and_continue\n" +
            "- 有风险 → escalate_to_human（安全问题不通过反馈解决，直接让人审）\n";
}
