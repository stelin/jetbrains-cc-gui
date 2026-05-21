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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Façade over the {@code mutagen sync …} subcommands. Every public method
 * runs off the EDT; callers receive a {@link CompletableFuture} so they can
 * choose to wait or fire-and-forget.
 *
 * <p>Sync-mode mapping:
 * <ul>
 *   <li>{@code two-way-safe} → default, conflicts pause the session
 *   <li>{@code two-way-resolved} → local always wins on conflict
 *   <li>{@code one-way-replica} → local pushes to remote, remote read-only
 * </ul>
 */
@Service(Service.Level.APP)
public final class MutagenSyncService {

    private static final Logger LOG = Logger.getInstance(MutagenSyncService.class);

    /** Excluded by default; not user-configurable in this revision. */
    private static final List<String> DEFAULT_IGNORES = List.of(
            ".git/", ".idea/", ".vscode/",
            "node_modules/", "target/", "build/", "dist/", "out/",
            "*.log", "*.pyc", "__pycache__/", ".DS_Store",
            ".gradle/", ".mvn/"
    );

    public static MutagenSyncService getInstance() {
        return ApplicationManager.getApplication().getService(MutagenSyncService.class);
    }

    /** Latest `mutagen sync create` output, surfaced by the diagnostics view. */
    private volatile String lastCreateLog = "";
    private volatile String lastCreateCmd = "";
    private volatile long lastCreateStartedAt = 0;
    private volatile long lastCreateFinishedAt = 0;

    /* ------------------------------ form bean ------------------------------ */

    public static final class FormData {
        public String name;
        public String localPath;
        public String remoteUser;
        public String remoteHost;
        public int remotePort = 22;
        public String remotePath;
        public String mode = "two-way-safe";
        /** "windows" / "unix" / "auto" (default). Drives endpoint path normalisation. */
        public String remoteOs = "auto";

        public String validate() {
            if (isBlank(name)) return "sync name is required";
            if (isBlank(localPath)) return "local path is required";
            if (isBlank(remoteUser)) return "remote user is required";
            if (isBlank(remoteHost)) return "remote host is required";
            if (isBlank(remotePath)) return "remote path is required";
            if (remotePort < 1 || remotePort > 65535) return "remote port out of range";
            return null;
        }

        private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

        /**
         * Construct the mutagen endpoint URL.
         * <ul>
         *   <li>Default port 22 → {@code user@host:path} (mutagen splits on first ':' and
         *       passes the rest verbatim, so Windows drive letters work as-is).
         *   <li>Custom port → {@code ssh://user@host:port/path}. The URI form requires
         *       backslashes converted to forward slashes and a leading '/', so Windows
         *       paths become {@code /D:/code/foo}.
         * </ul>
         */
        public String remoteEndpoint() {
            String path = (remotePath == null) ? "" : remotePath.trim();
            boolean isWindows = isWindowsRemote(path);

            if (remotePort == 22) {
                return remoteUser + "@" + remoteHost + ":" + path;
            }
            String uriPath = isWindows ? path.replace('\\', '/') : path;
            if (!uriPath.startsWith("/")) uriPath = "/" + uriPath;
            return "ssh://" + remoteUser + "@" + remoteHost + ":" + remotePort + uriPath;
        }

        private boolean isWindowsRemote(String path) {
            if ("windows".equalsIgnoreCase(remoteOs)) return true;
            if ("unix".equalsIgnoreCase(remoteOs)) return false;
            // Auto-detect from path shape: starts with "X:" (drive letter) or contains '\'.
            if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
                return true;
            }
            return path.contains("\\");
        }
    }

    /* ------------------------------ test ------------------------------ */

    public static final class TestResult {
        public final boolean ok;
        public final String message;

        private TestResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static TestResult ok(String m) { return new TestResult(true, m); }
        public static TestResult fail(String m) { return new TestResult(false, m); }
    }

    public CompletableFuture<TestResult> testConnection(FormData form, String password) {
        return CompletableFuture.supplyAsync(() -> runTest(form, password),
                ApplicationManager.getApplication()::executeOnPooledThread);
    }

    private TestResult runTest(FormData form, String password) {
        String err = form.validate();
        if (err != null) return TestResult.fail(err);
        if (!MutagenDaemon.getInstance().ensureRunning()) {
            return TestResult.fail("mutagen daemon failed to start (is the binary installed?)");
        }
        TestResult conn = ensureControlMaster(form, password);
        if (!conn.ok) return conn;

        String probeName = form.name + "-probe-" + Long.toHexString(System.currentTimeMillis());
        try {
            List<String> cmd = buildCreateCommand(form, probeName, false);
            String quoted = shellQuoteCommand(cmd);
            LOG.info("[MutagenSyncService] probe command: " + quoted);
            lastCreateCmd = quoted;
            lastCreateStartedAt = System.currentTimeMillis();
            lastCreateFinishedAt = 0;
            lastCreateLog = "";
            ProcessOutcome out = run(cmd, null);
            lastCreateFinishedAt = System.currentTimeMillis();
            LOG.info("[MutagenSyncService] probe exit=" + out.exitCode + " output:\n" + out.combined);
            lastCreateLog = "exit=" + out.exitCode + "\noutput:\n" + out.combined;
            if (out.exitCode != 0) {
                return TestResult.fail(humanise(out.combined));
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (!sessionExists(probeName)) {
                return TestResult.fail("mutagen reported success but session not found in sync list. "
                        + "Click '查看诊断信息' for the create output.");
            }
            ProcessOutcome cleanup = run(List.of(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", "terminate", probeName), null);
            if (cleanup.exitCode != 0) {
                LOG.warn("[MutagenSyncService] probe terminate failed: " + cleanup.combined);
            }
            return TestResult.ok("Connection OK");
        } catch (Exception e) {
            LOG.warn("[MutagenSyncService] test threw", e);
            return TestResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Make sure mutagen can authenticate to the remote without ever being
     * prompted (the background daemon has no prompter registered). Two paths:
     * <ul>
     *   <li><b>macOS / Linux client</b>: open a long-lived SSH ControlMaster
     *       once using the password, and write a Host block telling mutagen's
     *       SSH to multiplex it. Zero remote modification.</li>
     *   <li><b>Windows client</b>: ControlMaster isn't supported by Win
     *       OpenSSH (returns {@code getsockname failed: Not a socket}). Fall
     *       back to deploying a managed SSH key — the password is used once
     *       to push the key to the remote, then never again.</li>
     * </ul>
     */
    private TestResult ensureControlMaster(FormData form, String password) {
        try {
            if (PlatformUtils.isWindows()) {
                return ensureSshKey(form, password);
            }
            SshControlMaster cm = SshControlMaster.getInstance();
            SshControlMaster.Result r = cm.ensure(form.remoteUser, form.remoteHost,
                    form.remotePort, password);
            if (!r.ok) {
                // Surface a hint that the user can fall back to keys if ControlMaster
                // fails on a platform we expected to support it.
                return TestResult.fail("ssh connect (ControlMaster): " + r.message);
            }
            cm.writeSshConfigEntry(form.remoteHost, form.remoteUser, form.remotePort);
            return TestResult.ok("connection ready (ControlMaster)");
        } catch (Exception e) {
            LOG.warn("[MutagenSyncService] ensureControlMaster failed", e);
            return TestResult.fail("ssh connect: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private TestResult ensureSshKey(FormData form, String password) {
        try {
            SshKeyManager mgr = SshKeyManager.getInstance();
            if (!mgr.probeKeyAuth(form.remoteUser, form.remoteHost, form.remotePort)) {
                SshKeyManager.Result r = mgr.deployToRemote(form.remoteUser, form.remoteHost,
                        form.remotePort, password, form.remoteOs, form.remotePath);
                if (!r.ok) {
                    return TestResult.fail("ssh key setup: " + r.message);
                }
            }
            mgr.writeSshConfigEntry(form.remoteHost, form.remoteUser, form.remotePort);
            return TestResult.ok("connection ready (SSH key)");
        } catch (Exception e) {
            LOG.warn("[MutagenSyncService] ensureSshKey failed", e);
            return TestResult.fail("ssh key setup: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /* ------------------------------ lifecycle ------------------------------ */

    public CompletableFuture<TestResult> start(FormData form, String password) {
        return CompletableFuture.supplyAsync(() -> runStart(form, password),
                ApplicationManager.getApplication()::executeOnPooledThread);
    }

    private TestResult runStart(FormData form, String password) {
        String err = form.validate();
        if (err != null) return TestResult.fail(err);
        if (!MutagenDaemon.getInstance().ensureRunning()) {
            return TestResult.fail("mutagen daemon failed to start");
        }
        if (sessionExists(form.name)) {
            // Resume the existing one rather than creating a duplicate.
            ProcessOutcome out = run(List.of(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", "resume", form.name), null);
            LOG.info("[MutagenSyncService] resume output:\n" + out.combined);
            if (out.exitCode != 0) {
                return TestResult.fail("resume failed: " + humanise(out.combined));
            }
            return TestResult.ok("resumed");
        }
        TestResult conn = ensureControlMaster(form, password);
        if (!conn.ok) return conn;

        try {
            List<String> cmd = buildCreateCommand(form, form.name, false);
            String quoted = shellQuoteCommand(cmd);
            LOG.info("[MutagenSyncService] create command: " + quoted);
            lastCreateCmd = quoted;
            lastCreateStartedAt = System.currentTimeMillis();
            lastCreateFinishedAt = 0;
            lastCreateLog = "";
            ProcessOutcome out = run(cmd, null);
            lastCreateFinishedAt = System.currentTimeMillis();
            LOG.info("[MutagenSyncService] create exit=" + out.exitCode + " output:\n" + out.combined);
            lastCreateLog = "exit=" + out.exitCode + "\noutput:\n" + out.combined;
            if (out.exitCode != 0) {
                return TestResult.fail(humanise(out.combined));
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (!sessionExists(form.name)) {
                String msg = "mutagen reported success but session not found in sync list. "
                        + "Click '查看诊断信息' for full output.";
                LOG.warn("[MutagenSyncService] " + msg);
                return TestResult.fail(msg);
            }
            return TestResult.ok("started");
        } catch (Exception e) {
            LOG.warn("[MutagenSyncService] create threw", e);
            return TestResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public String lastCreateLog() {
        StringBuilder sb = new StringBuilder();
        if (lastCreateStartedAt == 0) {
            return "(not run yet)";
        }
        sb.append("command (copy-paste safe):\n").append(lastCreateCmd).append("\n\n");
        if (lastCreateFinishedAt == 0) {
            long elapsed = (System.currentTimeMillis() - lastCreateStartedAt) / 1000;
            sb.append("status: still running (").append(elapsed).append("s elapsed)\n");
        } else {
            long durMs = lastCreateFinishedAt - lastCreateStartedAt;
            sb.append("status: finished in ").append(durMs).append(" ms\n");
            sb.append(lastCreateLog);
        }
        return sb.toString();
    }

    /** Quote each arg so the command can be pasted directly into zsh/bash. */
    private static String shellQuoteCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(shellQuote(cmd.get(i)));
        }
        return sb.toString();
    }

    private static String shellQuote(String arg) {
        if (arg.isEmpty()) return "''";
        // Plain ASCII identifiers and a few safe metacharacters need no quoting.
        if (arg.matches("[A-Za-z0-9_./:=@,+-]+")) return arg;
        // Single-quote everything else; embedded single quotes become '\''
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    public CompletableFuture<TestResult> stop(String name) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessOutcome out = run(List.of(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", "terminate", name), null);
            return out.exitCode == 0
                    ? TestResult.ok("stopped")
                    : TestResult.fail(humanise(out.combined));
        }, ApplicationManager.getApplication()::executeOnPooledThread);
    }

    public CompletableFuture<TestResult> pause(String name) {
        return cliAction("pause", name);
    }

    public CompletableFuture<TestResult> resume(String name) {
        return cliAction("resume", name);
    }

    private CompletableFuture<TestResult> cliAction(String sub, String name) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessOutcome out = run(List.of(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", sub, name), null);
            return out.exitCode == 0
                    ? TestResult.ok(sub + "d")
                    : TestResult.fail(humanise(out.combined));
        }, ApplicationManager.getApplication()::executeOnPooledThread);
    }

    /**
     * Returns the raw output of {@code mutagen sync list --long <name>} for
     * diagnostics, plus a brief synthesised summary. Never throws.
     */
    public String diagnostics(String name) {
        StringBuilder sb = new StringBuilder();
        if (!MutagenBinary.getInstance().isAvailable()) {
            return "mutagen binary not installed";
        }
        sb.append("=== mutagen version ===\n");
        ProcessOutcome ver = run(List.of(
                MutagenBinary.getInstance().executablePath().toString(),
                "version"), null);
        sb.append(ver.combined).append('\n');

        sb.append("=== system info ===\n");
        sb.append("os: ").append(System.getProperty("os.name", "?"))
          .append(" ").append(System.getProperty("os.version", "?"))
          .append(" / arch=").append(System.getProperty("os.arch", "?")).append('\n');
        ProcessOutcome sshVer = run(List.of("ssh", "-V"), null);
        sb.append("ssh: ").append(sshVer.combined.trim()).append('\n');
        sb.append('\n');

        sb.append("=== mutagen daemon status ===\n");
        sb.append("running=").append(MutagenDaemon.getInstance().isRunning()).append("\n\n");

        sb.append("=== ssh auth strategy ===\n");
        sb.append("client os: ").append(PlatformUtils.isWindows() ? "Windows (SSH key)"
                : "macOS/Linux (ControlMaster)").append('\n');
        try {
            com.google.gson.JsonObject cfg =
                    new com.github.claudecodegui.settings.CodemossSettingsService()
                            .getRemoteSyncConfig();
            String user = cfg.has("remoteUser") ? cfg.get("remoteUser").getAsString() : "";
            String host = cfg.has("remoteHost") ? cfg.get("remoteHost").getAsString() : "";
            int port = cfg.has("remotePort") ? cfg.get("remotePort").getAsInt() : 22;
            if (!user.isEmpty() && !host.isEmpty()) {
                if (PlatformUtils.isWindows()) {
                    SshKeyManager mgr = SshKeyManager.getInstance();
                    sb.append("key pair: ").append(mgr.privateKeyPath())
                            .append(java.nio.file.Files.isRegularFile(mgr.privateKeyPath())
                                    ? "  (present)" : "  (missing)").append('\n');
                    sb.append("key accepted by remote: ")
                            .append(mgr.probeKeyAuth(user, host, port)).append('\n');
                    sb.append("config block present:   ").append(mgr.configHasEntry(host)).append('\n');
                } else {
                    SshControlMaster cm = SshControlMaster.getInstance();
                    sb.append("socket: ").append(cm.socketPath(host, port)).append('\n');
                    sb.append("alive:  ").append(cm.isMasterAlive(user, host, port)).append('\n');
                    sb.append("config block present: ").append(cm.configHasEntry(host)).append('\n');
                }
            } else {
                sb.append("(remoteUser/remoteHost not configured)\n");
            }
        } catch (Exception e) {
            sb.append("(unable to inspect: ").append(e.getMessage()).append(")\n");
        }
        sb.append('\n');

        sb.append("=== last sync create output ===\n");
        String last = lastCreateLog();
        sb.append(last.isEmpty() ? "(not run yet)\n" : last);
        if (!last.endsWith("\n")) sb.append('\n');
        sb.append('\n');

        sb.append("=== mutagen sync list ===\n");
        ProcessOutcome list = run(List.of(
                MutagenBinary.getInstance().executablePath().toString(),
                "sync", "list"), null);
        sb.append(list.combined).append('\n');

        if (name != null && !name.isEmpty()) {
            sb.append("=== mutagen sync list --long ").append(name).append(" ===\n");
            ProcessOutcome det = run(List.of(
                    MutagenBinary.getInstance().executablePath().toString(),
                    "sync", "list", "--long", name), null);
            sb.append(det.combined).append('\n');
        }
        return sb.toString();
    }

    public boolean sessionExists(String name) {
        if (!MutagenBinary.getInstance().isAvailable()) return false;
        ProcessOutcome out = run(List.of(
                MutagenBinary.getInstance().executablePath().toString(),
                "sync", "list", "--long"), null);
        if (out.exitCode != 0) return false;
        // Long output has either "Name: <name>" lines or short summary lines that
        // begin with the session name. Match both.
        for (String line : out.combined.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("Name:") && t.substring(5).trim().equals(name)) return true;
            if (t.equals(name) || t.startsWith(name + " ")) return true;
        }
        return false;
    }

    /* ------------------------------ build commands ------------------------------ */

    private List<String> buildCreateCommand(FormData form, String name, boolean paused) {
        List<String> cmd = new ArrayList<>();
        cmd.add(MutagenBinary.getInstance().executablePath().toString());
        cmd.add("sync");
        cmd.add("create");
        cmd.add("--name=" + name);
        cmd.add("--sync-mode=" + form.mode);
        cmd.add("--ignore-vcs");
        for (String ignore : DEFAULT_IGNORES) {
            cmd.add("-i");
            cmd.add(ignore);
        }
        if (paused) cmd.add("--paused");
        // Port and host-key trust are baked into the endpoint URL / known_hosts;
        // mutagen 0.18 has no --ssh-flag for passing arbitrary SSH options.
        cmd.add(form.localPath);
        cmd.add(form.remoteEndpoint());
        return cmd;
    }

    /* ------------------------------ process helpers ------------------------------ */

    private static final class ProcessOutcome {
        int exitCode;
        String combined;
    }

    private ProcessOutcome run(List<String> cmd, SshAskpass.Context askpass) {
        ProcessOutcome out = new ProcessOutcome();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // Redirect stdin from /dev/null (or NUL on Windows). With no readable
            // stdin AND SSH_ASKPASS_REQUIRE=force, OpenSSH must invoke askpass
            // instead of prompting on a terminal we don't have. We previously
            // wrapped in `setsid`, but that put the daemon mutagen spawns in the
            // same session group as the short-lived CLI; when the CLI exited the
            // daemon was reaped and the just-registered session disappeared.
            File nullDev = new File(PlatformUtils.isWindows() ? "NUL" : "/dev/null");
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDev));
            if (askpass != null) {
                pb.environment().putAll(askpass.envOverrides());
            }
            Process p = pb.start();
            // Drain stdout on a daemon thread — if we read inline, a hung
            // mutagen never closes stdout and waitFor() can't time out.
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) { sb.append(line).append('\n'); }
                    }
                } catch (IOException ignored) {}
            }, "mutagen-stdout-reader");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(2000);
                out.exitCode = 124;
                synchronized (sb) {
                    out.combined = sb.toString() + "\n(timeout after 60s — process killed)";
                }
                return out;
            }
            reader.join(5000);
            out.exitCode = p.exitValue();
            synchronized (sb) { out.combined = sb.toString(); }
            return out;
        } catch (IOException | InterruptedException e) {
            out.exitCode = 1;
            out.combined = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return out;
        }
    }

    /** Trim mutagen's noisy output to a one-line error summary. */
    private static String humanise(String combined) {
        if (combined == null || combined.isEmpty()) return "(no output)";
        for (String line : combined.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.toLowerCase().startsWith("error:")) {
                return t.substring(6).trim();
            }
            if (t.contains("Permission denied")) return "SSH auth failed: " + t;
            if (t.contains("Connection refused")) return t;
            if (t.contains("Connection timed out")) return t;
            if (t.contains("Host key verification failed")) {
                return "Host key not trusted — clear ~/.ssh/known_hosts entry and retry";
            }
        }
        return combined.split("\\R", 2)[0].trim();
    }
}
