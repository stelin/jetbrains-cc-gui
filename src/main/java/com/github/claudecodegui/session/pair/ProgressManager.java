package com.github.claudecodegui.session.pair;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Read/write per-Pair runtime state in {@code pair_xxx/progress.json}.
 *
 * <p>The file is the only mutable artifact of a Pair — the plan snapshot is
 * read-only. Schema is intentionally permissive: stats counters and step
 * arrays may be missing or undefined; readers must apply sensible defaults.
 */
public class ProgressManager {

    private static final Logger LOG = Logger.getInstance(ProgressManager.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path progressFile;
    private final ReentrantLock lock = new ReentrantLock();

    public ProgressManager(Path pairDir) {
        this.progressFile = pairDir.resolve("progress.json");
    }

    public Path getProgressFile() { return progressFile; }

    /**
     * Initialise a fresh {@code progress.json}. Called by PairSessionManager
     * once at start; further updates go through {@link #mutate(java.util.function.Consumer)}.
     */
    public void initialise(String pairId, String planSource, String agentId, String agentName) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(progressFile.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("pair_id", pairId);
            root.addProperty("plan_source", planSource);
            root.addProperty("agent_id", agentId);
            root.addProperty("agent_name", agentName);
            root.addProperty("started_at", System.currentTimeMillis());
            root.addProperty("status", "running");
            root.add("steps", new JsonArray());
            JsonObject stats = new JsonObject();
            stats.addProperty("auto_recover_count", 0);
            stats.addProperty("escalate_count", 0);
            stats.addProperty("verify_fail_count", 0);
            stats.addProperty("review_reject_count", 0);
            root.add("stats", stats);
            writeUnlocked(root);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read a snapshot of progress.json. Returns a fresh JsonObject on read
     * failure (defensive — never throws).
     */
    public JsonObject snapshot() {
        lock.lock();
        try {
            return readUnlocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Apply a mutating function atomically. The mutator receives the parsed
     * root and may modify it in place; this manager re-serialises after.
     */
    public void mutate(java.util.function.Consumer<JsonObject> mutator) {
        lock.lock();
        try {
            JsonObject root = readUnlocked();
            mutator.accept(root);
            try {
                writeUnlocked(root);
            } catch (IOException e) {
                LOG.warn("[ProgressManager] Write failed: " + e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    public void incrementCounter(String counterName) {
        mutate(root -> {
            JsonObject stats = root.has("stats") ? root.getAsJsonObject("stats") : new JsonObject();
            int prev = stats.has(counterName) && !stats.get(counterName).isJsonNull()
                    ? stats.get(counterName).getAsInt() : 0;
            stats.addProperty(counterName, prev + 1);
            root.add("stats", stats);
        });
    }

    public void markStatus(String status) {
        mutate(root -> root.addProperty("status", status));
    }

    public void markStepStatus(int stepIndex, String status) {
        mutate(root -> {
            JsonArray steps = root.has("steps") && root.get("steps").isJsonArray()
                    ? root.getAsJsonArray("steps")
                    : new JsonArray();
            // Ensure index exists
            while (steps.size() < stepIndex) {
                JsonObject placeholder = new JsonObject();
                placeholder.addProperty("index", steps.size() + 1);
                placeholder.addProperty("status", "pending");
                steps.add(placeholder);
            }
            JsonObject step;
            if (steps.size() > stepIndex - 1) {
                step = steps.get(stepIndex - 1).getAsJsonObject();
            } else {
                step = new JsonObject();
                step.addProperty("index", stepIndex);
                steps.add(step);
            }
            step.addProperty("status", status);
            if ("done".equals(status)) {
                step.addProperty("completed_at", System.currentTimeMillis());
            }
            root.add("steps", steps);
        });
    }

    // ====================== internal ======================

    private JsonObject readUnlocked() {
        if (!Files.exists(progressFile)) {
            return new JsonObject();
        }
        try (FileReader r = new FileReader(progressFile.toFile(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("[ProgressManager] Read failed: " + e.getMessage());
            return new JsonObject();
        }
    }

    private void writeUnlocked(JsonObject root) throws IOException {
        try (FileWriter w = new FileWriter(progressFile.toFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }
}
