package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.remotesync.model.SyncStatus;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a long-lived {@code mutagen sync monitor --long} subprocess. Output is
 * absorbed by {@link MutagenOutputParser} and published through
 * {@link MutagenEventBus}. Restarted on its own thread; {@link #stop()} kills
 * the child cleanly.
 */
@Service(Service.Level.APP)
public final class MutagenMonitor implements Disposable {

    private static final Logger LOG = Logger.getInstance(MutagenMonitor.class);

    public static MutagenMonitor getInstance() {
        return ApplicationManager.getApplication().getService(MutagenMonitor.class);
    }

    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private volatile Thread workerThread;
    private volatile String sessionName;

    public synchronized void start(String name) {
        if (name == null || name.isEmpty()) return;
        if (workerThread != null && workerThread.isAlive()
                && name.equals(sessionName)) {
            return;
        }
        // Silent stop so the UI doesn't flash 'disabled' between sessions.
        stopSilent();
        sessionName = name;
        Thread t = new Thread(this::runLoop, "mutagen-monitor-" + name);
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    /** Stop the monitor thread WITHOUT publishing a final status — used when
     *  we're about to replace it with a fresh monitor and don't want a
     *  cosmetic "disabled" flash in the UI. */
    public synchronized void stopSilent() {
        Process p = processRef.getAndSet(null);
        if (p != null) p.destroyForcibly();
        Thread t = workerThread;
        if (t != null) {
            workerThread = null;
            t.interrupt();
        }
        sessionName = null;
    }

    /** Stop the monitor AND announce the session as disabled — for user-
     *  initiated stop / disable actions. */
    public synchronized void stop() {
        stopSilent();
        MutagenEventBus.getInstance().publish(SyncStatus.disabled());
    }

    @Override
    public void dispose() {
        stopSilent();
    }

    private static final long POLL_INTERVAL_MS = 2_000;
    private static final long POLL_BACKOFF_MS = 8_000;

    /**
     * Poll {@code mutagen sync list --long <name>} every couple of seconds.
     * We previously streamed {@code mutagen sync monitor --long}, but that
     * command emits a TUI redraw with ANSI escapes and was unparseable by
     * line. {@code sync list --long} is plain text and trivially parseable.
     */
    private void runLoop() {
        String name = sessionName;
        int consecutiveErrors = 0;
        while (!Thread.currentThread().isInterrupted() && name != null && name.equals(sessionName)) {
            try {
                if (!MutagenBinary.getInstance().isAvailable()) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                ProcessBuilder pb = new ProcessBuilder(
                        MutagenBinary.getInstance().executablePath().toString(),
                        "sync", "list", "--long", name);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                processRef.set(p);

                MutagenOutputParser parser = new MutagenOutputParser();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        parser.absorb(line, name);
                    }
                }
                int exit = p.waitFor();
                processRef.set(null);

                if (exit == 0) {
                    SyncStatus status = parser.currentStatus(name);
                    MutagenEventBus.getInstance().publish(status);
                    consecutiveErrors = 0;
                } else {
                    consecutiveErrors++;
                    if (consecutiveErrors <= 3) {
                        LOG.warn("[MutagenMonitor] sync list --long exit=" + exit
                                + " for " + name);
                    }
                    // Don't immediately publish error — a transient list failure
                    // (e.g. daemon restart) shouldn't flip the UI to red.
                    if (consecutiveErrors >= 5) {
                        MutagenEventBus.getInstance().publish(
                                SyncStatus.error(name, "sync list --long exits " + exit));
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                consecutiveErrors++;
                LOG.warn("[MutagenMonitor] poll error: " + e.getMessage());
            }

            long sleep = consecutiveErrors >= 3 ? POLL_BACKOFF_MS : POLL_INTERVAL_MS;
            try { Thread.sleep(sleep); } catch (InterruptedException ie) { break; }
        }
    }
}
