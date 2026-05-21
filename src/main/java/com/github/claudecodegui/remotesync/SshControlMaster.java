package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Keeps a long-lived authenticated SSH "ControlMaster" connection to the
 * remote host. Subsequent SSH invocations (including mutagen's) reuse this
 * already-authenticated channel and are never prompted for a password.
 *
 * <p>Why this exists: mutagen's background daemon disables its own password
 * prompter, so a session that requires password auth fails with
 * {@code "prompter not found"}. Using ControlMaster, we authenticate ONCE
 * with the user-supplied password (via {@link SshAskpass}) and then mutagen
 * never has to authenticate at all.
 *
 * <p>The master process lives until either the system reboots or the
 * {@code ControlPersist} timer expires. On expiry we transparently
 * re-establish using the saved password.
 */
@Service(Service.Level.APP)
public final class SshControlMaster {

    private static final Logger LOG = Logger.getInstance(SshControlMaster.class);
    private static final String CONFIG_MARKER_BEGIN = "# >>> codemoss-remote-sync (managed) >>>";
    private static final String CONFIG_MARKER_END = "# <<< codemoss-remote-sync (managed) <<<";

    public static SshControlMaster getInstance() {
        return ApplicationManager.getApplication().getService(SshControlMaster.class);
    }

    public Path socketDir() {
        return Path.of(PlatformUtils.getHomeDirectory(), ".codemoss", "remoteSync");
    }

    /** Path to the ControlMaster socket for a given (host,port). Kept short
     *  because POSIX socket names cap around 104 bytes. */
    public Path socketPath(String host, int port) {
        return socketDir().resolve("cm-" + host + "-" + port);
    }

    /* ------------------------- core: liveness ------------------------- */

    /** {@code ssh -O check} probes the master socket — returns true if alive. */
    public boolean isMasterAlive(String user, String host, int port) {
        Path socket = socketPath(host, port);
        if (!PlatformUtils.isWindows() && !Files.exists(socket)) return false;
        List<String> cmd = List.of(
                "ssh", "-O", "check",
                "-S", socket.toString(),
                "-p", String.valueOf(port),
                user + "@" + host
        );
        ProcessOutcome out = runShort(cmd, null);
        return out.exitCode == 0;
    }

    /* ------------------------- core: open ------------------------- */

    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    /**
     * Make sure a ControlMaster connection is live for the host. Re-uses an
     * existing one when possible; otherwise authenticates with the supplied
     * password and forks a backgrounded master process.
     */
    public Result ensure(String user, String host, int port, String password) {
        try {
            if (isMasterAlive(user, host, port)) {
                return Result.ok("master already alive");
            }
            Files.createDirectories(socketDir());
            if (!PlatformUtils.isWindows()) {
                try {
                    Files.setPosixFilePermissions(socketDir(),
                            PosixFilePermissions.fromString("rwx------"));
                } catch (Exception ignored) {}
            }
            Path socket = socketPath(host, port);
            // Clean any stale file/socket at the path so ssh -M can recreate it.
            try { Files.deleteIfExists(socket); } catch (IOException ignored) {}

            try (SshAskpass.Context askpass = SshAskpass.prepare(password == null ? "" : password)) {
                List<String> cmd = List.of(
                        "ssh",
                        "-fNTM",                              // background, no command, no tty, master mode
                        "-S", socket.toString(),
                        "-o", "ControlPersist=24h",           // keep master alive for a day after exit
                        "-o", "ServerAliveInterval=60",
                        "-o", "StrictHostKeyChecking=yes",
                        "-o", "NumberOfPasswordPrompts=1",
                        "-o", "PreferredAuthentications=password,keyboard-interactive",
                        "-p", String.valueOf(port),
                        user + "@" + host
                );
                LOG.info("[SshControlMaster] opening master: " + String.join(" ", cmd));
                ProcessOutcome out = runShort(cmd, askpass);
                LOG.info("[SshControlMaster] open exit=" + out.exitCode + " output:\n" + out.combined);
                if (out.exitCode != 0) {
                    return Result.fail("master open failed: " + firstLine(out.combined));
                }
            }
            // Master forks to background; give it a beat to settle then verify.
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (!isMasterAlive(user, host, port)) {
                return Result.fail("master process exited immediately — check the password and that "
                        + "the server allows password auth");
            }
            return Result.ok("master established");
        } catch (Exception e) {
            LOG.warn("[SshControlMaster] ensure failed", e);
            return Result.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Send {@code ssh -O exit} to a running master. Safe to call when no master. */
    public void close(String user, String host, int port) {
        Path socket = socketPath(host, port);
        if (!Files.exists(socket)) return;
        List<String> cmd = List.of(
                "ssh", "-O", "exit",
                "-S", socket.toString(),
                "-p", String.valueOf(port),
                user + "@" + host
        );
        runShort(cmd, null);
        try { Files.deleteIfExists(socket); } catch (IOException ignored) {}
    }

    /* ------------------------- ~/.ssh/config ------------------------- */

    /**
     * Write a Host block that points the host at the ControlMaster socket. With
     * this in place, any {@code ssh user@host} invocation (including mutagen's
     * spawned SSH) silently multiplexes the existing connection.
     */
    public void writeSshConfigEntry(String host, String user, int port) throws IOException {
        Path config = Path.of(PlatformUtils.getHomeDirectory(), ".ssh", "config");
        Files.createDirectories(config.getParent());
        String existing = Files.exists(config)
                ? Files.readString(config, StandardCharsets.UTF_8)
                : "";

        String stripped = stripManagedBlock(existing);
        String block = renderBlock(host, user, port);
        String next = stripped;
        if (!next.isEmpty() && !next.endsWith("\n")) next += "\n";
        if (!next.isEmpty()) next += "\n";
        next += block;

        Files.writeString(config, next, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            if (!PlatformUtils.isWindows()) {
                Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception ignored) {}
        LOG.info("[SshControlMaster] wrote ~/.ssh/config entry for " + host);
    }

    private String renderBlock(String host, String user, int port) {
        StringBuilder sb = new StringBuilder();
        sb.append(CONFIG_MARKER_BEGIN).append('\n');
        sb.append("Host ").append(host).append('\n');
        sb.append("    HostName ").append(host).append('\n');
        sb.append("    User ").append(user).append('\n');
        sb.append("    Port ").append(port).append('\n');
        sb.append("    ControlMaster auto\n");
        sb.append("    ControlPath ").append(socketPath(host, port)).append('\n');
        sb.append("    ControlPersist 24h\n");
        sb.append("    StrictHostKeyChecking yes\n");
        sb.append(CONFIG_MARKER_END).append('\n');
        return sb.toString();
    }

    private String stripManagedBlock(String text) {
        if (text.isEmpty()) return text;
        Pattern p = Pattern.compile(
                Pattern.quote(CONFIG_MARKER_BEGIN) + ".*?" + Pattern.quote(CONFIG_MARKER_END) + "\\n?",
                Pattern.DOTALL);
        String stripped = p.matcher(text).replaceAll("");
        while (stripped.endsWith("\n\n\n")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    public boolean configHasEntry(String host) {
        Path config = Path.of(PlatformUtils.getHomeDirectory(), ".ssh", "config");
        if (!Files.exists(config)) return false;
        try {
            String text = Files.readString(config, StandardCharsets.UTF_8);
            return text.contains(CONFIG_MARKER_BEGIN) && text.contains("Host " + host);
        } catch (IOException e) {
            return false;
        }
    }

    /* ------------------------- helpers ------------------------- */

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "(no output)";
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return s;
    }

    private static ProcessOutcome runShort(List<String> cmd, SshAskpass.Context askpass) {
        ProcessOutcome out = new ProcessOutcome();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            File nullDev = new File(PlatformUtils.isWindows() ? "NUL" : "/dev/null");
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDev));
            if (askpass != null) {
                pb.environment().putAll(askpass.envOverrides());
            }
            Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) { sb.append(line).append('\n'); }
                    }
                } catch (IOException ignored) {}
            }, "ssh-cm-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(45, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(2000);
                out.exitCode = 124;
                synchronized (sb) {
                    out.combined = sb.toString() + "\n(timeout)";
                }
                return out;
            }
            reader.join(3000);
            out.exitCode = p.exitValue();
            synchronized (sb) { out.combined = sb.toString(); }
            return out;
        } catch (IOException | InterruptedException e) {
            out.exitCode = 1;
            out.combined = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return out;
        }
    }

    private static final class ProcessOutcome {
        int exitCode;
        String combined = "";
    }
}
