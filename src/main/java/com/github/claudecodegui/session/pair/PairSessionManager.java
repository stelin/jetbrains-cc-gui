package com.github.claudecodegui.session.pair;

import com.github.claudecodegui.bridge.SupervisorBridge;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-project service that owns every active Supervisor Pair on the project.
 *
 * Responsibilities:
 *   - Create / dispose {@link PairSession} instances on user demand.
 *   - Snapshot the plan markdown into the pair directory.
 *   - Wire {@link EventBus} and {@link ActionRouter} to bridge main AI events
 *     and supervisor actions.
 *   - Tear down everything on project close (via {@link Disposable}).
 */
@Service(Service.Level.PROJECT)
public final class PairSessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(PairSessionManager.class);
    private static final long START_DAEMON_TIMEOUT_SEC = 20;

    private final Project project;
    private final ConcurrentHashMap<String, PairSession> pairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mainSessionToPair = new ConcurrentHashMap<>();
    private final PlanSnapshotter planSnapshotter = new PlanSnapshotter();

    public PairSessionManager(@NotNull Project project) {
        this.project = project;
    }

    public static PairSessionManager getInstance(@NotNull Project project) {
        return project.getService(PairSessionManager.class);
    }

    /**
     * Parameters for {@link #startPair}.
     */
    public static final class StartPairParams {
        public final String mainSessionId;
        public final String agentId;
        /** Optional explicit path to plan markdown. If null, no plan is snapshotted. */
        @Nullable
        public final String planPath;

        public StartPairParams(String mainSessionId, String agentId, @Nullable String planPath) {
            this.mainSessionId = mainSessionId;
            this.agentId = agentId;
            this.planPath = planPath;
        }
    }

    /**
     * Create and start a Pair session.
     *
     * @param sdkBridge  daemon bridge to piggy-back on
     * @return started PairSession
     */
    public PairSession startPair(StartPairParams params, ClaudeSDKBridge sdkBridge) throws IOException {
        // Resolve supervisor agent config + project-applicable skills.
        CodemossSettingsService settings = new CodemossSettingsService();
        JsonObject agentConfig = settings.getSupervisorAgentManager().getAgent(params.agentId);
        if (agentConfig == null) {
            throw new IOException("Supervisor agent not found: " + params.agentId);
        }
        String agentName = agentConfig.has("name") ? agentConfig.get("name").getAsString() : params.agentId;
        String description = agentConfig.has("description") ? agentConfig.get("description").getAsString() : "";
        String model = agentConfig.has("model") && !agentConfig.get("model").isJsonNull()
                ? agentConfig.get("model").getAsString()
                : null;

        // Pull project-applicable skills and shape them as a compact markdown
        // checklist the Supervisor can use as review rules. We embed only
        // name + description (not the full skill body) to stay token-light.
        String projectSpec = buildProjectSpecFromSkills(settings);

        // Allocate pair_id and directory under {project}/.cc-gui/pairs/.
        String pairId = "pair_" + UUID.randomUUID().toString().substring(0, 8);
        String basePath = project.getBasePath();
        if (basePath == null) {
            throw new IOException("Cannot start pair: project has no base path");
        }
        Path pairDir = Paths.get(basePath, ".cc-gui", "pairs", pairId);
        Files.createDirectories(pairDir);

        // Snapshot plan if provided.
        Path planSnapshot = null;
        String planContent = "";
        if (params.planPath != null && !params.planPath.isEmpty()) {
            Path src = Paths.get(params.planPath);
            planSnapshot = planSnapshotter.snapshot(src, pairDir);
            planContent = planSnapshotter.readSnapshot(planSnapshot);
        }

        // Initialise progress.
        ProgressManager progress = new ProgressManager(pairDir);
        progress.initialise(pairId, params.planPath, params.agentId, agentName);

        // Build bridge and persistent session shell.
        SupervisorBridge bridge = new SupervisorBridge(sdkBridge, pairId, params.agentId);
        PairSession session = new PairSession(
                pairId,
                params.mainSessionId,
                params.agentId,
                agentName,
                pairDir,
                planSnapshot,
                bridge,
                progress,
                description,
                planContent,
                projectSpec,
                model
        );

        // Start daemon-side supervisor.
        try {
            bridge.start(agentName, description, planContent, projectSpec, model)
                    .get(START_DAEMON_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting supervisor", ie);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Failed to start supervisor on daemon: " + e.getMessage(), e);
        }

        // Wire EventBus and ActionRouter.
        ActionRouter router = new ActionRouter(project, session);
        EventBus bus = new EventBus(session, router);
        session.setActionRouter(router);
        session.setEventBus(bus);

        pairs.put(pairId, session);
        // mainSessionId is null when the SDK hasn't assigned one yet (the very
        // common case for "start pair before first turn"). ConcurrentHashMap
        // forbids null keys, so we just skip the index — ClaudeMessageHandler's
        // findAttachedPair falls back to "any active pair" anyway.
        if (params.mainSessionId != null && !params.mainSessionId.isEmpty()) {
            mainSessionToPair.put(params.mainSessionId, pairId);
        }
        LOG.info("[PairSessionManager] Started pair " + pairId + " for session " + params.mainSessionId);

        return session;
    }

    /** Find the Pair tracking a given main-session id, or null if none. */
    @Nullable
    public PairSession findByMainSession(String mainSessionId) {
        if (mainSessionId == null) return null;
        String pairId = mainSessionToPair.get(mainSessionId);
        return pairId == null ? null : pairs.get(pairId);
    }

    @Nullable
    public PairSession get(String pairId) {
        return pairId == null ? null : pairs.get(pairId);
    }

    public Collection<PairSession> getActivePairs() {
        return pairs.values();
    }

    /**
     * Stop and clean up a Pair. Removes mapping; calls daemon stop; marks
     * progress.json status=stopped.
     */
    public void stopPair(String pairId) {
        PairSession session = pairs.remove(pairId);
        if (session == null) return;
        String mainSid = session.getMainSessionId();
        if (mainSid != null && !mainSid.isEmpty()) {
            mainSessionToPair.remove(mainSid);
        }
        session.markDisposed();

        try {
            session.getSupervisorBridge().stop().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("[PairSessionManager] Supervisor stop did not complete cleanly: " + e.getMessage());
        }

        try {
            session.getProgressManager().markStatus("stopped");
        } catch (Exception ignored) { /* best-effort */ }

        LOG.info("[PairSessionManager] Stopped pair " + pairId);
    }

    @Override
    public void dispose() {
        for (String id : pairs.keySet().toArray(new String[0])) {
            stopPair(id);
        }
    }

    /**
     * Build a compact markdown spec listing each enabled project skill — name
     * + one-line description — to seed the Supervisor's review rules.
     *
     * <p>We deliberately ship only the metadata, not full skill bodies:
     * Supervisor recognises common skill names like {@code golang-standards}
     * and applies the corresponding rules on its own. Token-light by design.
     *
     * <p>Returns {@code null} when there are no enabled skills (lets the
     * daemon's prompt builder skip the section).
     */
    private static String buildProjectSpecFromSkills(CodemossSettingsService settings) {
        if (settings == null) return null;
        java.util.List<com.google.gson.JsonObject> skills;
        try {
            skills = settings.getSkills();
        } catch (Throwable t) {
            return null;
        }
        if (skills == null || skills.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("项目当前启用了以下技能包/规范。你在 review 主 AI 产出时应当把这些当作\n");
        sb.append("review 检查项；主 AI 违反时立即 inject_prompt 反馈具体违反点。\n");
        sb.append("（你不需要展开每个规范的细节——名字本身就承载了语义，常用规范如\n");
        sb.append(" golang-standards / eino-compose / agent-orch-creator 你已经知道它们意味着什么。）\n\n");

        int kept = 0;
        for (com.google.gson.JsonObject skill : skills) {
            // Honor user toggle if present; treat missing/true as "enabled".
            if (skill.has("enabled") && !skill.get("enabled").isJsonNull()
                    && !skill.get("enabled").getAsBoolean()) {
                continue;
            }
            String name = skill.has("name") && !skill.get("name").isJsonNull()
                    ? skill.get("name").getAsString().trim() : null;
            if (name == null || name.isEmpty()) continue;
            String desc = skill.has("description") && !skill.get("description").isJsonNull()
                    ? skill.get("description").getAsString().trim() : "";
            // Single-line clip — keep prompt compact.
            if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
            desc = desc.replace('\n', ' ').replace('\r', ' ').trim();

            sb.append("- `").append(name).append('`');
            if (!desc.isEmpty()) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
            kept++;
        }
        return kept > 0 ? sb.toString() : null;
    }
}
