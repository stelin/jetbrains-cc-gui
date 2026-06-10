package com.github.claudecodegui.session.registry;

import com.github.claudecodegui.util.CodemossPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-kind refactor: per-project owner of the {@code ~/.codemoss/sessions}
 * ledger. Single writer of every {@link SessionManifest} — {@code PairSessionManager}
 * / {@code SupervisorWorkflowManager} register/lookup through it instead of each
 * touching disk, so the "total account" of which stores belong to one session
 * has exactly one owner (no glue scattered across managers).
 *
 * <p>Layout (clean cut; legacy {@code ~/.codemoss/pairs} abandoned):
 * <pre>
 *   ~/.codemoss/sessions/&lt;projectHash&gt;/&lt;containerId&gt;/manifest.json
 *   ~/.codemoss/sessions/&lt;projectHash&gt;/&lt;containerId&gt;/l2/        (coordination state)
 * </pre>
 *
 * <p>Threading: an in-memory {@link ConcurrentHashMap} cache fronts the disk;
 * every mutation is serialized on {@link #lock} and atomically persisted
 * (tmp → ATOMIC_MOVE). Reads after the one-time lazy scan are lock-free.
 */
@Service(Service.Level.PROJECT)
public final class SessionRegistry {

    private static final Logger LOG = Logger.getInstance(SessionRegistry.class);
    private static final String MANIFEST_FILE = "manifest.json";

    private final String projectHash;
    private final Path sessionsRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<String, SessionManifest> cache = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile boolean loaded = false;

    /** Production light-service constructor. */
    public SessionRegistry(@NotNull Project project) {
        this(CodemossPaths.projectHash(project.getBasePath()), CodemossPaths.sessionsRoot());
    }

    /** Explicit-root constructor (tests / custom locations). */
    SessionRegistry(@NotNull String projectHash, @NotNull Path sessionsRoot) {
        this.projectHash = projectHash;
        this.sessionsRoot = sessionsRoot;
    }

    public static SessionRegistry getInstance(@NotNull Project project) {
        return project.getService(SessionRegistry.class);
    }

    // ─── registration / mutation ────────────────────────────────────────

    /** Register a new container (generated UUID). */
    public String register(@NotNull SessionKind kind, @Nullable String parentContainerId,
                           @Nullable String title, @Nullable String agentId) {
        return register(kind, parentContainerId, title, agentId, null);
    }

    /**
     * Register a container, optionally with a fixed id (workflows pass {@code fixedId=wfId}
     * so {@code containerId == wfId} is stable across runs). Idempotent for a fixed id:
     * if it already exists the existing containerId is returned untouched.
     * Writes the manifest atomically <b>before</b> any daemon call so the session
     * is a complete, listable, restorable entity from creation.
     */
    public String register(@NotNull SessionKind kind, @Nullable String parentContainerId,
                           @Nullable String title, @Nullable String agentId, @Nullable String fixedId) {
        synchronized (lock) {
            ensureLoaded();
            String cid = (fixedId != null && !fixedId.isEmpty()) ? fixedId : UUID.randomUUID().toString();
            SessionManifest existing = cache.get(cid);
            if (existing != null) {
                return cid; // idempotent (fixed-id workflow re-register)
            }
            long now = System.currentTimeMillis();
            SessionManifest m = new SessionManifest();
            m.containerId = cid;
            m.kind = kind;
            m.title = title;
            m.agentId = agentId;
            m.parentContainerId = parentContainerId;
            m.projectHash = projectHash;
            m.createdAt = now;
            m.lastActiveAt = now;
            m.status = "active";
            if (kind == SessionKind.WORKFLOW) {
                m.workflowId = cid;
            }
            persist(m);
            cache.put(cid, m);

            // Link into the parent's child list (workflow node ↔ workflow container).
            if (parentContainerId != null && !parentContainerId.isEmpty()) {
                SessionManifest parent = cache.get(parentContainerId);
                if (parent != null && !parent.childContainerIds.contains(cid)) {
                    parent.childContainerIds.add(cid);
                    persist(parent);
                }
            }
            return cid;
        }
    }

    /** Best-effort backfill: set current main-session pointer + append to the owned-set. */
    public void bindMainSession(@NotNull String containerId, @Nullable String mainSessionId) {
        if (mainSessionId == null || mainSessionId.isEmpty()) return;
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.mainSessionId = mainSessionId;
            if (m.mainSessionIds == null) m.mainSessionIds = new ArrayList<>();
            if (!m.mainSessionIds.contains(mainSessionId)) m.mainSessionIds.add(mainSessionId);
            m.lastActiveAt = System.currentTimeMillis();
            persist(m);
        }
    }

    public void setSupervisorSession(@NotNull String containerId, @Nullable String sid, @Nullable Integer generation) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.supervisorSessionId = sid;
            if (generation != null) m.supervisorGeneration = generation;
            m.lastActiveAt = System.currentTimeMillis();
            persist(m);
        }
    }

    public void setPairId(@NotNull String containerId, @Nullable String pairId) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.pairId = pairId;
            persist(m);
        }
    }

    public void updateTitle(@NotNull String containerId, @Nullable String title) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.title = title;
            persist(m);
        }
    }

    public void touch(@NotNull String containerId) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.lastActiveAt = System.currentTimeMillis();
            persist(m);
        }
    }

    public void close(@NotNull String containerId) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.get(containerId);
            if (m == null) return;
            m.status = "closed";
            m.lastActiveAt = System.currentTimeMillis();
            persist(m);
        }
    }

    /** Recursively delete the container directory + drop it from cache / parent links. */
    public void delete(@NotNull String containerId) {
        synchronized (lock) {
            ensureLoaded();
            SessionManifest m = cache.remove(containerId);
            if (m != null && m.parentContainerId != null) {
                SessionManifest parent = cache.get(m.parentContainerId);
                if (parent != null && parent.childContainerIds.remove(containerId)) {
                    persist(parent);
                }
            }
            deleteDir(containerDir(containerId));
        }
    }

    // ─── queries (history tab data sources) ──────────────────────────────

    @Nullable
    public SessionManifest get(@NotNull String containerId) {
        ensureLoaded();
        return cache.get(containerId);
    }

    /**
     * All manifests of a kind for this project, newest-active first.
     * SUPERVISED automatically excludes workflow children (parentContainerId != null)
     * so node sessions stay hidden behind their workflow container.
     */
    public List<SessionManifest> listByKind(@NotNull SessionKind kind) {
        ensureLoaded();
        List<SessionManifest> out = new ArrayList<>();
        for (SessionManifest m : cache.values()) {
            if (m.kind != kind) continue;
            if (kind == SessionKind.SUPERVISED && m.parentContainerId != null) continue;
            out.add(m);
        }
        out.sort(Comparator.comparingLong((SessionManifest m) -> m.lastActiveAt).reversed());
        return out;
    }

    public List<SessionManifest> listChildren(@NotNull String parentContainerId) {
        ensureLoaded();
        List<SessionManifest> out = new ArrayList<>();
        for (SessionManifest m : cache.values()) {
            if (parentContainerId.equals(m.parentContainerId)) out.add(m);
        }
        out.sort(Comparator.comparingLong((SessionManifest m) -> m.createdAt));
        return out;
    }

    /**
     * Union of every main-session id ever owned by any container — the normal
     * history tab subtracts this so a supervised/workflow main leg never shows
     * up as a bare normal session.
     */
    public Set<String> claimedMainSessionIds() {
        ensureLoaded();
        Set<String> out = new LinkedHashSet<>();
        for (SessionManifest m : cache.values()) {
            if (m.mainSessionId != null) out.add(m.mainSessionId);
            if (m.mainSessionIds != null) out.addAll(m.mainSessionIds);
        }
        return out;
    }

    // ─── paths ───────────────────────────────────────────────────────────

    public Path containerDir(@NotNull String containerId) {
        return sessionsRoot.resolve(projectHash).resolve(containerId);
    }

    public Path l2Dir(@NotNull String containerId) {
        return containerDir(containerId).resolve("l2");
    }

    // ─── internals ─────────────────────────────────────────────────────

    private Path projectDir() {
        return sessionsRoot.resolve(projectHash);
    }

    private Path manifestFile(String containerId) {
        return containerDir(containerId).resolve(MANIFEST_FILE);
    }

    /** One-time lazy scan of this project's container dirs into the cache. */
    private void ensureLoaded() {
        if (loaded) return;
        synchronized (lock) {
            if (loaded) return;
            Path dir = projectDir();
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> dirs = Files.list(dir)) {
                    for (Path cDir : (Iterable<Path>) dirs::iterator) {
                        if (!Files.isDirectory(cDir)) continue;
                        Path mf = cDir.resolve(MANIFEST_FILE);
                        if (!Files.isRegularFile(mf)) continue;
                        SessionManifest m = readManifest(mf);
                        if (m != null && m.containerId != null) cache.put(m.containerId, m);
                    }
                } catch (IOException e) {
                    LOG.warn("[SessionRegistry] scan failed: " + e.getMessage());
                }
            }
            loaded = true;
        }
    }

    private void persist(SessionManifest m) {
        try {
            atomicWriteString(manifestFile(m.containerId), gson.toJson(m));
        } catch (IOException e) {
            LOG.warn("[SessionRegistry] persist failed for " + m.containerId + ": " + e.getMessage());
        }
    }

    @Nullable
    private SessionManifest readManifest(Path file) {
        try {
            return gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), SessionManifest.class);
        } catch (Exception e) {
            LOG.warn("[SessionRegistry] failed to read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ex) { LOG.warn("[SessionRegistry] delete " + p + ": " + ex.getMessage()); }
            });
        } catch (IOException e) {
            LOG.warn("[SessionRegistry] deleteDir failed for " + dir + ": " + e.getMessage());
        }
    }

    /** Atomic write: {@code <file>.tmp} → ATOMIC_MOVE (fallback REPLACE_EXISTING). */
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
