package com.github.claudecodegui.service;

import com.github.claudecodegui.settings.AutoReloadSettings;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-level service that batches file-refresh requests triggered by AI
 * tool_use / tool_result events and replays them via
 * {@link LocalFileSystem#refreshIoFiles}.
 *
 * <p>Two coalescing tiers:
 * <ul>
 *   <li>Sliding debounce (configurable via
 *       {@link AutoReloadSettings#getDebounceMs()}, default 200 ms) — repeated
 *       calls within the window merge into one refresh that fires after the
 *       last call</li>
 *   <li>Max-wait safety (10× debounce) — if the timer keeps resetting,
 *       force-flush once total pending age exceeds the cap (prevents
 *       live-lock on burst edits)</li>
 * </ul>
 *
 * <p>Two layered gates:
 * <ol>
 *   <li>Remote mode active — local mode already has FSNotifier coverage</li>
 *   <li>{@link AutoReloadSettings#isEnabled()} — user opt-in, default off</li>
 * </ol>
 */
@Service(Service.Level.PROJECT)
public final class ProjectReloadService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ProjectReloadService.class);

    private static final int TOOL_USE_MAP_MAX_SIZE = 256;

    private final Project project;

    private final Object pathsLock = new Object();
    private final Set<String> pendingPaths = new HashSet<>();
    private final AtomicLong firstPendingAt = new AtomicLong(-1L);
    private final AtomicReference<ScheduledFuture<?>> pendingTask = new AtomicReference<>();

    @SuppressWarnings("serial")
    private final Map<String, Set<String>> toolUsePaths =
            Collections.synchronizedMap(new LinkedHashMap<String, Set<String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    return size() > TOOL_USE_MAP_MAX_SIZE;
                }
            });

    public ProjectReloadService(@NotNull Project project) {
        this.project = project;
    }

    public static ProjectReloadService getInstance(@NotNull Project project) {
        return project.getService(ProjectReloadService.class);
    }

    /** Schedule a debounced refresh of the given paths. */
    public void schedulePaths(@NotNull Collection<String> paths) {
        if (!shouldRun()) return;
        if (paths.isEmpty()) return;

        boolean added = false;
        synchronized (pathsLock) {
            for (String p : paths) {
                if (p != null && !p.isEmpty()) {
                    added |= pendingPaths.add(p);
                }
            }
        }
        if (!added) return;

        firstPendingAt.compareAndSet(-1L, System.currentTimeMillis());
        rescheduleFlush();
    }

    /** A-phase: remember the paths claimed by a tool_use so B-phase can replay them. */
    public void recordToolUse(@NotNull String toolUseId, @NotNull Set<String> paths) {
        if (toolUseId.isEmpty() || paths.isEmpty()) return;
        toolUsePaths.put(toolUseId, new HashSet<>(paths));
    }

    /** B-phase: replay the schedule for a completed tool_use. */
    public void schedulePathsForToolUseId(@NotNull String toolUseId) {
        Set<String> paths = toolUsePaths.remove(toolUseId);
        if (paths != null && !paths.isEmpty()) {
            schedulePaths(paths);
        }
    }

    private boolean shouldRun() {
        if (project.isDisposed()) return false;
        if (!RemoteModeContext.getInstance().isRemote()) return false;
        if (!AutoReloadSettings.getInstance().isEnabled()) return false;
        return true;
    }

    private void rescheduleFlush() {
        long debounceMs = AutoReloadSettings.getInstance().getDebounceMs();
        long maxWaitMs = debounceMs * 10L;

        long now = System.currentTimeMillis();
        long firstAt = firstPendingAt.get();
        long delay = (firstAt > 0 && now - firstAt >= maxWaitMs) ? 0L : debounceMs;

        ScheduledFuture<?> next = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(this::flush, delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = pendingTask.getAndSet(next);
        if (prev != null) prev.cancel(false);
    }

    private void flush() {
        if (project.isDisposed()) {
            synchronized (pathsLock) { pendingPaths.clear(); }
            firstPendingAt.set(-1L);
            return;
        }
        List<File> files;
        synchronized (pathsLock) {
            if (pendingPaths.isEmpty()) {
                firstPendingAt.set(-1L);
                return;
            }
            files = new ArrayList<>(pendingPaths.size());
            for (String p : pendingPaths) files.add(new File(p));
            pendingPaths.clear();
        }
        firstPendingAt.set(-1L);

        try {
            LocalFileSystem.getInstance().refreshIoFiles(files, true, true, null);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Reloaded " + files.size() + " path(s) from disk");
            }
        } catch (Exception e) {
            LOG.warn("refreshIoFiles failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> task = pendingTask.getAndSet(null);
        if (task != null) task.cancel(false);
        synchronized (pathsLock) { pendingPaths.clear(); }
        toolUsePaths.clear();
        firstPendingAt.set(-1L);
    }
}
