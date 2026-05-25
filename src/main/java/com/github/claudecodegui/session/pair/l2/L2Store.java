package com.github.claudecodegui.session.pair.l2;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Phase 3 (2026-05-24): per-pair durable state store. One singleton per IDE
 * process — the store owns its own file/locking discipline so callers
 * (Bridge / Monitor / RotationCoordinator) just call {@code read} and
 * {@code update} without coordinating writes themselves.
 *
 * <p>Concurrency: each pairId has its own {@link ReentrantLock}; cross-pair
 * operations are independent. Within a pair, all reads/writes serialize.
 * Reads come from an in-memory cache to keep the supervisor's tick-end
 * push fast (no disk I/O on the happy path).
 *
 * <p>Durability: writes use the {@code .tmp + fsync + atomic move} pattern
 * so a crash mid-write cannot leave a half-written {@code state.json}.
 * {@link #snapshotBackup} after rotation copies the current file to
 * {@code state.json.bak} so a corruption in the next generation has a
 * recoverable previous-generation snapshot.
 *
 * <p>Storage layout under {@code ~/.codemoss/pairs/<pairId>/}:
 * <pre>
 *   state.json                 — current (always points at the latest valid)
 *   state.json.bak             — last-good snapshot (refreshed at rotation)
 *   state-precompact-{ts}.json — PreCompact-hook snapshots, kept for 7d
 *   archive/constraints-{ts}.json — archived knownConstraints (gen-5 reset)
 * </pre>
 */
public class L2Store {

    private static final Logger LOG = Logger.getInstance(L2Store.class);
    private static final Gson GSON_PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private final Path baseDir;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, L2State> cache = new ConcurrentHashMap<>();

    public L2Store() {
        this(defaultBaseDir());
    }

    public L2Store(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Default location: {@code ~/.codemoss/pairs}. Matches the user's
     * decision in §18 of the design doc — co-located with
     * {@code supervisor-agents.json} so on-disk state is grouped.
     */
    public static Path defaultBaseDir() {
        String home = PlatformUtils.getHomeDirectory();
        return Paths.get(home, ".codemoss", "pairs");
    }

    /**
     * Read the current state for {@code pairId}. Returns the in-memory cache
     * entry if present; otherwise loads from disk (falling back to .bak on
     * corruption, then to a fresh {@link L2State#initial} as a last resort).
     */
    public L2State read(String pairId) {
        return cache.computeIfAbsent(pairId, this::loadFromDisk);
    }

    /**
     * Apply {@code mutator} to a deep copy of the current state and persist
     * the result atomically. Caller's mutator should NOT keep a reference to
     * the argument after returning — the new state replaces it in cache.
     *
     * <p>If the mutator throws, the previous state is preserved.
     */
    public L2State update(String pairId, UnaryOperator<L2State> mutator) {
        ReentrantLock lock = locks.computeIfAbsent(pairId, k -> new ReentrantLock());
        lock.lock();
        try {
            L2State current = cache.computeIfAbsent(pairId, this::loadFromDisk);
            L2State next = mutator.apply(current.deepCopy());
            // Defensive: caller may have appended without trimming.
            next.trimRings();
            next.lastUpdated = System.currentTimeMillis();
            L2Validator.validate(next);
            writeAtomic(pairId, next);
            cache.put(pairId, next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Copy the current {@code state.json} to {@code state.json.bak}. Called
     * by the rotation coordinator right after a successful rotation so the
     * new generation's failures can recover the previous generation's state.
     */
    public void snapshotBackup(String pairId) {
        ReentrantLock lock = locks.computeIfAbsent(pairId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path src = stateFile(pairId);
            Path bak = bakFile(pairId);
            if (Files.exists(src)) {
                Files.copy(src, bak, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[L2Store] snapshotBackup for " + pairId);
            }
        } catch (IOException e) {
            LOG.warn("[L2Store] snapshotBackup failed for " + pairId + ": " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Write a PreCompact snapshot — a separately-named file so the auto-compact
     * tally doesn't clobber the main state. Returns the path written, or null
     * on failure.
     */
    public Path writePrecompactSnapshot(String pairId) {
        ReentrantLock lock = locks.computeIfAbsent(pairId, k -> new ReentrantLock());
        lock.lock();
        try {
            L2State state = cache.get(pairId);
            if (state == null) state = loadFromDisk(pairId);
            Path dir = pairDir(pairId);
            Files.createDirectories(dir);
            Path snap = dir.resolve(L2Schema.PRECOMPACT_SNAPSHOT_PREFIX
                    + System.currentTimeMillis() + ".json");
            String json = GSON_PRETTY.toJson(state);
            Files.writeString(snap, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            LOG.info("[L2Store] precompact snapshot for " + pairId + " → " + snap);
            return snap;
        } catch (IOException e) {
            LOG.warn("[L2Store] precompact snapshot failed for " + pairId + ": " + e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Archive {@code knownConstraints} into the per-pair archive dir and
     * clear the in-state list. Called by the rotation coordinator when
     * {@code generation >= L2Schema.GENERATION_RESET_THRESHOLD}.
     */
    public void archiveKnownConstraints(String pairId) {
        update(pairId, s -> {
            if (s.knownConstraints == null || s.knownConstraints.isEmpty()) return s;
            try {
                Path dir = pairDir(pairId).resolve(L2Schema.ARCHIVE_DIR);
                Files.createDirectories(dir);
                Path target = dir.resolve("constraints-" + System.currentTimeMillis() + ".json");
                Map<String, Object> envelope = new HashMap<>();
                envelope.put("pairId", pairId);
                envelope.put("archivedAt", System.currentTimeMillis());
                envelope.put("generation", s.generation);
                envelope.put("constraints", s.knownConstraints);
                Files.writeString(target, GSON_PRETTY.toJson(envelope),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
                LOG.info("[L2Store] archived " + s.knownConstraints.size()
                        + " knownConstraints for " + pairId + " → " + target);
            } catch (IOException e) {
                LOG.warn("[L2Store] archiveKnownConstraints io failure: " + e.getMessage());
            }
            s.knownConstraints.clear();
            return s;
        });
    }

    /** Diagnostic: bytes written to {@code state.json} on disk. -1 if missing. */
    public long fileSizeBytes(String pairId) {
        try { return Files.exists(stateFile(pairId)) ? Files.size(stateFile(pairId)) : -1; }
        catch (IOException e) { return -1; }
    }

    /** Test-helper: forget the in-memory cache so the next read goes to disk. */
    public void invalidateCache(String pairId) {
        cache.remove(pairId);
    }

    // ── internals ─────────────────────────────────────────────────────────

    private Path pairDir(String pairId) {
        return baseDir.resolve(pairId);
    }

    private Path stateFile(String pairId) {
        return pairDir(pairId).resolve(L2Schema.STATE_FILE);
    }

    private Path bakFile(String pairId) {
        return pairDir(pairId).resolve(L2Schema.STATE_BAK_FILE);
    }

    private L2State loadFromDisk(String pairId) {
        Path state = stateFile(pairId);
        Path bak = bakFile(pairId);
        try {
            if (Files.exists(state)) {
                return parseAndMigrate(state);
            }
        } catch (Exception primary) {
            LOG.warn("[L2Store] primary load failed for " + pairId + ", trying .bak: "
                    + primary.getMessage());
            try {
                if (Files.exists(bak)) {
                    L2State recovered = parseAndMigrate(bak);
                    // Repair: write the recovered state back as the new primary so
                    // subsequent reads don't hit the corrupt file every time.
                    writeAtomic(pairId, recovered);
                    return recovered;
                }
            } catch (Exception secondary) {
                LOG.error("[L2Store] .bak load also failed for " + pairId + ": " + secondary.getMessage());
            }
        }
        L2State fresh = L2State.initial(pairId);
        try {
            writeAtomic(pairId, fresh);
        } catch (Exception ignored) { /* will write on first update */ }
        return fresh;
    }

    private L2State parseAndMigrate(Path file) throws IOException {
        String json = Files.readString(file);
        L2State state = L2State.fromJson(json);
        if (state.schemaVersion != L2Schema.CURRENT_VERSION) {
            state = L2Migration.migrate(state);
        }
        L2Validator.validate(state);
        return state;
    }

    private void writeAtomic(String pairId, L2State state) {
        try {
            Path target = stateFile(pairId);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            String json = GSON_PRETTY.toJson(state);
            Files.writeString(tmp, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                // Some filesystems (network mounts) don't support atomic rename — fall back.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("L2 write failed for " + pairId, e);
        }
    }

    // ─── delta application helpers used by SupervisorBridge [STATE_UPDATE] handler ───

    /**
     * Apply a JSON delta of the shape emitted by the daemon's {@code update_state}
     * MCP tool. Fields are sparse — only the ones present mutate state.
     *
     * <p>Schema (mirrors update-state-tool.js):
     * <pre>
     * {
     *   "anchoredFactsDelta": { ... }       // shallow merge into anchoredFacts
     *   "planProgressDelta": [{step, status, ...}, ...]
     *   "fileStateDelta": { "path": {mtime, lastTouchedBy, ...}, ... }
     *   "decisionAppend": { action, reason, payload, result, confidence }
     *   "constraintAdd": "new constraint text"
     * }
     * </pre>
     */
    public void applyUpdateStateDelta(String pairId, JsonObject delta) {
        if (delta == null) return;
        update(pairId, s -> {
            if (delta.has("anchoredFactsDelta") && delta.get("anchoredFactsDelta").isJsonObject()) {
                JsonObject af = delta.getAsJsonObject("anchoredFactsDelta");
                if (s.anchoredFacts == null) s.anchoredFacts = new L2State.AnchoredFacts();
                if (af.has("currentStep") && !af.get("currentStep").isJsonNull())
                    s.anchoredFacts.currentStep = af.get("currentStep").getAsInt();
                if (af.has("totalSteps") && !af.get("totalSteps").isJsonNull())
                    s.anchoredFacts.totalSteps = af.get("totalSteps").getAsInt();
                if (af.has("currentStepTitle") && !af.get("currentStepTitle").isJsonNull())
                    s.anchoredFacts.currentStepTitle = af.get("currentStepTitle").getAsString();
                if (af.has("blockedOn"))
                    s.anchoredFacts.blockedOn = af.get("blockedOn").isJsonNull()
                            ? null : af.get("blockedOn").getAsString();
                if (af.has("lastVerifyCmd") && !af.get("lastVerifyCmd").isJsonNull())
                    s.anchoredFacts.lastVerifyCmd = af.get("lastVerifyCmd").getAsString();
                if (af.has("lastVerifyResult") && !af.get("lastVerifyResult").isJsonNull())
                    s.anchoredFacts.lastVerifyResult = af.get("lastVerifyResult").getAsString();
                if (af.has("lastVerifyAt") && !af.get("lastVerifyAt").isJsonNull())
                    s.anchoredFacts.lastVerifyAt = af.get("lastVerifyAt").getAsLong();
            }
            if (delta.has("planProgressDelta") && delta.get("planProgressDelta").isJsonArray()) {
                for (com.google.gson.JsonElement el : delta.getAsJsonArray("planProgressDelta")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject e = el.getAsJsonObject();
                    if (!e.has("step")) continue;
                    int step = e.get("step").getAsInt();
                    L2State.PlanProgressEntry entry = findOrCreatePlanEntry(s, step);
                    if (e.has("status") && !e.get("status").isJsonNull())
                        entry.status = e.get("status").getAsString();
                    if (e.has("attempts") && !e.get("attempts").isJsonNull())
                        entry.attempts = e.get("attempts").getAsInt();
                    if (e.has("lastError"))
                        entry.lastError = e.get("lastError").isJsonNull() ? null : e.get("lastError").getAsString();
                    if (e.has("completedAt") && !e.get("completedAt").isJsonNull())
                        entry.completedAt = e.get("completedAt").getAsLong();
                    if (e.has("filesChanged") && e.get("filesChanged").isJsonArray()) {
                        entry.filesChanged.clear();
                        for (com.google.gson.JsonElement fc : e.getAsJsonArray("filesChanged")) {
                            entry.filesChanged.add(fc.getAsString());
                        }
                    }
                }
            }
            if (delta.has("fileStateDelta") && delta.get("fileStateDelta").isJsonObject()) {
                JsonObject fs = delta.getAsJsonObject("fileStateDelta");
                for (Map.Entry<String, com.google.gson.JsonElement> en : fs.entrySet()) {
                    if (!en.getValue().isJsonObject()) continue;
                    JsonObject v = en.getValue().getAsJsonObject();
                    L2State.FileStateEntry fe = s.fileState.computeIfAbsent(
                            en.getKey(), k -> new L2State.FileStateEntry());
                    if (v.has("mtime") && !v.get("mtime").isJsonNull())
                        fe.mtime = v.get("mtime").getAsLong();
                    if (v.has("lastTouchedBy") && !v.get("lastTouchedBy").isJsonNull())
                        fe.lastTouchedBy = v.get("lastTouchedBy").getAsString();
                    if (v.has("linesChanged") && !v.get("linesChanged").isJsonNull())
                        fe.linesChanged = v.get("linesChanged").getAsInt();
                    if (v.has("confidence") && !v.get("confidence").isJsonNull())
                        fe.confidence = v.get("confidence").getAsString();
                }
            }
            if (delta.has("decisionAppend") && delta.get("decisionAppend").isJsonObject()) {
                JsonObject d = delta.getAsJsonObject("decisionAppend");
                L2State.DecisionEntry de = new L2State.DecisionEntry();
                de.ts = d.has("ts") && !d.get("ts").isJsonNull()
                        ? d.get("ts").getAsLong() : System.currentTimeMillis();
                de.action = d.has("action") ? d.get("action").getAsString() : null;
                de.reason = d.has("reason") ? d.get("reason").getAsString() : null;
                if (d.has("payload") && d.get("payload").isJsonObject())
                    de.payload = d.getAsJsonObject("payload");
                if (d.has("result") && !d.get("result").isJsonNull())
                    de.result = d.get("result").getAsString();
                if (d.has("confidence") && !d.get("confidence").isJsonNull())
                    de.confidence = d.get("confidence").getAsString();
                // Protocol v2 (2026-05-24): autonomy-mode fields. Sparse — only
                // record what the supervisor sent; nulls stay null.
                if (d.has("category") && !d.get("category").isJsonNull())
                    de.category = d.get("category").getAsString();
                if (d.has("severity") && !d.get("severity").isJsonNull())
                    de.severity = d.get("severity").getAsString();
                if (d.has("chosenCandidate") && !d.get("chosenCandidate").isJsonNull())
                    de.chosenCandidate = d.get("chosenCandidate").getAsString();
                if (d.has("stepId") && !d.get("stepId").isJsonNull())
                    de.stepId = d.get("stepId").getAsInt();
                if (d.has("autoMode") && !d.get("autoMode").isJsonNull())
                    de.autoMode = d.get("autoMode").getAsBoolean();
                if (d.has("candidates") && d.get("candidates").isJsonArray()) {
                    de.candidates = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement el : d.getAsJsonArray("candidates")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject co = el.getAsJsonObject();
                        L2State.CandidateEntry ce = new L2State.CandidateEntry();
                        if (co.has("option") && !co.get("option").isJsonNull())
                            ce.option = co.get("option").getAsString();
                        if (co.has("score") && !co.get("score").isJsonNull())
                            ce.score = co.get("score").getAsDouble();
                        de.candidates.add(ce);
                    }
                }
                if (d.has("evidence") && d.get("evidence").isJsonArray()) {
                    de.evidence = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement el : d.getAsJsonArray("evidence")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject ev = el.getAsJsonObject();
                        L2State.EvidenceEntry ee = new L2State.EvidenceEntry();
                        if (ev.has("kind") && !ev.get("kind").isJsonNull())
                            ee.kind = ev.get("kind").getAsString();
                        if (ev.has("path") && !ev.get("path").isJsonNull())
                            ee.path = ev.get("path").getAsString();
                        if (ev.has("lines") && !ev.get("lines").isJsonNull())
                            ee.lines = ev.get("lines").getAsString();
                        if (ev.has("agentId") && !ev.get("agentId").isJsonNull())
                            ee.agentId = ev.get("agentId").getAsString();
                        if (ev.has("turnId") && !ev.get("turnId").isJsonNull())
                            ee.turnId = ev.get("turnId").getAsString();
                        if (ev.has("output") && !ev.get("output").isJsonNull())
                            ee.output = ev.get("output").getAsString();
                        de.evidence.add(ee);
                    }
                }
                s.recentDecisions.add(de);
                s.trimRings();
            }
            if (delta.has("constraintAdd") && !delta.get("constraintAdd").isJsonNull()) {
                String c = delta.get("constraintAdd").getAsString();
                if (c != null && !c.trim().isEmpty() && !s.knownConstraints.contains(c)) {
                    s.knownConstraints.add(c);
                }
            }
            return s;
        });
    }

    private L2State.PlanProgressEntry findOrCreatePlanEntry(L2State s, int step) {
        for (L2State.PlanProgressEntry e : s.planProgress) {
            if (e.step == step) return e;
        }
        L2State.PlanProgressEntry fresh = new L2State.PlanProgressEntry();
        fresh.step = step;
        s.planProgress.add(fresh);
        return fresh;
    }
}
