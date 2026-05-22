package com.github.claudecodegui.session.pair;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads the user-selected plan markdown and copies it into the pair directory
 * as an immutable snapshot. Once snapshotted, the running pair only reads
 * {@code pair_xxx/plan.md} — edits to the original source file have no
 * effect on the in-flight run (Phase A decision: strict authenticity).
 */
public class PlanSnapshotter {

    private static final Logger LOG = Logger.getInstance(PlanSnapshotter.class);

    /**
     * @param source absolute path to the user-selected plan markdown
     * @param pairDir target pair directory (will be created if missing)
     * @return snapshot path inside {@code pairDir}
     */
    public Path snapshot(Path source, Path pairDir) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Plan source not found: " + source);
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Plan source is not a regular file: " + source);
        }
        Files.createDirectories(pairDir);
        Path target = pairDir.resolve("plan.md");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("[PlanSnapshotter] Snapshotted plan: " + source + " -> " + target);
        return target;
    }

    /**
     * Read the plan content from a snapshot path. Returns empty string if
     * unreadable (caller decides whether to fail the pair start).
     */
    public String readSnapshot(Path snapshotPath) {
        try {
            return Files.readString(snapshotPath);
        } catch (IOException e) {
            LOG.warn("[PlanSnapshotter] Failed to read snapshot: " + e.getMessage());
            return "";
        }
    }
}
