package com.github.claudecodegui.session.pair.workflow;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-project persistence for workflow definitions + executions (DN6).
 *
 * <p>Layout, rooted at {@code ~/.codemoss/workflows/<projectHash>/}:
 * <pre>
 *   &lt;wfId&gt;/definition.json
 *   &lt;wfId&gt;/execution.json
 *   &lt;wfId&gt;/nodes/&lt;safeNodeName&gt;/plan.md
 * </pre>
 *
 * <p>All JSON writes are atomic — write {@code xxx.tmp} then
 * {@link Files#move} with {@link StandardCopyOption#ATOMIC_MOVE} (falling back
 * to a plain replace where the filesystem lacks atomic-move support) — so an
 * IDE/JVM crash mid-write never leaves a half-written file.
 *
 * <p>Threading (§10): definition reads/writes happen on the handler dispatch
 * thread; execution writes happen on the {@code wf-scheduler} thread. The two
 * touch different files, so no locking is required.
 *
 * <p>See {@code docs/workflow/coding-plan.md} §10.
 */
public final class WorkflowStore {

    private static final Logger LOG = Logger.getInstance(WorkflowStore.class);

    private static final String DEFINITION_FILE = "definition.json";
    private static final String EXECUTION_FILE = "execution.json";
    private static final String NODES_DIR = "nodes";
    private static final String PLAN_FILE = "plan.md";
    /** Stored alongside the safe dir so the original node name is recoverable. */
    private static final String NODE_NAME_FILE = "name.txt";

    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Production: root = {@code ~/.codemoss/workflows/<projectHash>/}. */
    public WorkflowStore(@NotNull Project project) {
        this(defaultRoot(project));
    }

    /** Explicit-root constructor (tests / custom locations). */
    public WorkflowStore(@NotNull Path root) {
        this.root = root;
    }

    private static Path defaultRoot(@NotNull Project project) {
        String home = PlatformUtils.getHomeDirectory();
        return Paths.get(home, ".codemoss", "workflows", projectHash(project.getBasePath()));
    }

    /**
     * Stable per-project subdirectory name derived from the project base path.
     * SHA-256 → first 16 hex chars (collision-safe enough, filesystem-safe,
     * deterministic across restarts). Null/blank base path → {@code "default"}.
     */
    static String projectHash(@Nullable String basePath) {
        if (basePath == null || basePath.isEmpty()) return "default";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(basePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fall back to a sanitized hashCode — still deterministic per run.
            return Integer.toHexString(basePath.hashCode());
        }
    }

    // ─── definitions ────────────────────────────────────────────────────

    /** Load every workflow definition under the root (skips unreadable ones). */
    public List<WorkflowDefinition> loadAll() {
        List<WorkflowDefinition> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (java.util.stream.Stream<Path> dirs = Files.list(root)) {
            for (Path wfDir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(wfDir)) continue;
                Path def = wfDir.resolve(DEFINITION_FILE);
                if (!Files.isRegularFile(def)) continue;
                WorkflowDefinition d = readJson(def, WorkflowDefinition.class);
                if (d != null && d.id != null) out.add(d);
            }
        } catch (IOException e) {
            LOG.warn("[WorkflowStore] loadAll failed: " + e.getMessage());
        }
        return out;
    }

    /** Load one definition by id, or null if absent/unreadable. */
    @Nullable
    public WorkflowDefinition load(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        Path def = wfDir(id).resolve(DEFINITION_FILE);
        if (!Files.isRegularFile(def)) return null;
        return readJson(def, WorkflowDefinition.class);
    }

    /** Persist a definition atomically. The workflow id must be set. */
    public void save(@NotNull WorkflowDefinition def) {
        if (def.id == null || def.id.isEmpty()) {
            throw new IllegalArgumentException("WorkflowDefinition.id is required to save");
        }
        try {
            Path dir = wfDir(def.id);
            Files.createDirectories(dir);
            atomicWriteString(dir.resolve(DEFINITION_FILE), gson.toJson(def));
        } catch (IOException e) {
            LOG.warn("[WorkflowStore] save failed for " + def.id + ": " + e.getMessage());
            throw new RuntimeException("Failed to save workflow " + def.id, e);
        }
    }

    /** Recursively delete a workflow's entire directory. Best-effort. */
    public void delete(@Nullable String id) {
        if (id == null || id.isEmpty()) return;
        Path dir = wfDir(id);
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            // delete children before parents
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ex) { LOG.warn("[WorkflowStore] delete " + p + ": " + ex.getMessage()); }
            });
        } catch (IOException e) {
            LOG.warn("[WorkflowStore] delete failed for " + id + ": " + e.getMessage());
        }
    }

    // ─── executions ─────────────────────────────────────────────────────

    public void saveExecution(@NotNull String id, @NotNull WorkflowExecution exec) {
        try {
            Path dir = wfDir(id);
            Files.createDirectories(dir);
            atomicWriteString(dir.resolve(EXECUTION_FILE), gson.toJson(exec));
        } catch (IOException e) {
            LOG.warn("[WorkflowStore] saveExecution failed for " + id + ": " + e.getMessage());
        }
    }

    @Nullable
    public WorkflowExecution loadExecution(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        Path f = wfDir(id).resolve(EXECUTION_FILE);
        if (!Files.isRegularFile(f)) return null;
        return readJson(f, WorkflowExecution.class);
    }

    // ─── node plan files ────────────────────────────────────────────────

    /**
     * Resolve (and create the parent dir of) the assembled-plan file for a node.
     * The node name is sanitized for the filesystem (§10) and the original name
     * is recorded next to it in {@code name.txt} for traceability.
     */
    public Path nodePlanPath(@NotNull String wfId, @NotNull String nodeName) throws IOException {
        Path nodeDir = wfDir(wfId).resolve(NODES_DIR).resolve(safeName(nodeName));
        Files.createDirectories(nodeDir);
        Path nameFile = nodeDir.resolve(NODE_NAME_FILE);
        if (!Files.exists(nameFile)) {
            try { Files.writeString(nameFile, nodeName, StandardCharsets.UTF_8); }
            catch (IOException ignored) { /* best-effort traceability only */ }
        }
        return nodeDir.resolve(PLAN_FILE);
    }

    /** Write the assembled plan markdown for a node atomically; return its path. */
    public Path writeNodePlan(@NotNull String wfId, @NotNull String nodeName, @NotNull String content)
            throws IOException {
        Path planPath = nodePlanPath(wfId, nodeName);
        atomicWriteString(planPath, content);
        return planPath;
    }

    // ─── helpers ────────────────────────────────────────────────────────

    public Path getRoot() {
        return root;
    }

    private Path wfDir(String id) {
        return root.resolve(safeName(id));
    }

    /**
     * Filesystem-safe name: keep word chars / dash / dot, replace everything
     * else with {@code _}. Empty/dot-only names fall back to a hash so we never
     * produce {@code ""}, {@code "."} or {@code ".."}. Long names are truncated
     * with a hash suffix to stay unique.
     */
    static String safeName(String raw) {
        if (raw == null || raw.isEmpty()) return "_";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Avoid path-traversal / dot-only directory names.
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")
                || cleaned.chars().allMatch(c -> c == '.')) {
            cleaned = "n_" + Integer.toHexString(raw.hashCode());
        }
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 90) + "_" + Integer.toHexString(raw.hashCode());
        }
        return cleaned;
    }

    @Nullable
    private <T> T readJson(Path file, Class<T> type) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return gson.fromJson(json, type);
        } catch (Exception e) {
            LOG.warn("[WorkflowStore] failed to read " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Atomic write: {@code <file>.tmp} → {@link Files#move} with
     * {@code ATOMIC_MOVE}. Falls back to {@code REPLACE_EXISTING} when the
     * target filesystem does not support atomic moves.
     */
    private static void atomicWriteString(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
