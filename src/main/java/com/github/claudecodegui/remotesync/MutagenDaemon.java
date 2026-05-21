package com.github.claudecodegui.remotesync;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Idempotent wrapper around {@code mutagen daemon start/stop}. The daemon is a
 * long-lived background process that owns sync sessions; running CLI commands
 * before it's up will silently spawn it, but doing so explicitly gives us
 * clearer error reporting and warm-up timing control.
 */
@Service(Service.Level.APP)
public final class MutagenDaemon {

    private static final Logger LOG = Logger.getInstance(MutagenDaemon.class);

    public static MutagenDaemon getInstance() {
        return ApplicationManager.getApplication().getService(MutagenDaemon.class);
    }

    public synchronized boolean ensureRunning() {
        if (!MutagenBinary.getInstance().isAvailable()) {
            LOG.warn("[MutagenDaemon] mutagen binary not installed");
            return false;
        }
        if (isRunning()) return true;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "daemon", "start");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOG.warn("[MutagenDaemon] daemon start timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                LOG.warn("[MutagenDaemon] daemon start failed: " + out);
                return false;
            }
            LOG.info("[MutagenDaemon] daemon started");
            return true;
        } catch (Exception e) {
            LOG.warn("[MutagenDaemon] daemon start error: " + e.getMessage());
            return false;
        }
    }

    /** Returns true when the daemon socket responds. */
    public boolean isRunning() {
        if (!MutagenBinary.getInstance().isAvailable()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", "list");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void stop() {
        if (!MutagenBinary.getInstance().isAvailable()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "daemon", "stop");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("[MutagenDaemon] daemon stop failed: " + e.getMessage());
        }
    }

    private static String readAll(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException ignored) {}
        return sb.toString();
    }
}
