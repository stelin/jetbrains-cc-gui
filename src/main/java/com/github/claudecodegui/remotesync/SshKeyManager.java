package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Authenticates the remote sync target using a dedicated SSH key pair. Used
 * when the local OpenSSH client doesn't support {@code ControlMaster} (notably
 * Windows). Flow:
 * <ol>
 *   <li>Generate {@code ~/.codemoss/remoteSync/id_ed25519_codemoss} once.</li>
 *   <li>Use {@link SshAskpass} + the saved password to ssh into the remote and
 *       append the public key to {@code authorized_keys}. Windows admin
 *       accounts also get the key written to
 *       {@code C:\ProgramData\ssh\administrators_authorized_keys} with the
 *       correct ACL — Windows OpenSSH ignores the user file for admins.</li>
 *   <li>Append a marked Host block to {@code ~/.ssh/config} pointing at the
 *       generated key, so mutagen-spawned SSH authenticates silently.</li>
 * </ol>
 */
@Service(Service.Level.APP)
public final class SshKeyManager {

    private static final Logger LOG = Logger.getInstance(SshKeyManager.class);
    private static final String KEY_FILENAME = "id_ed25519_codemoss";
    private static final String CONFIG_MARKER_BEGIN = "# >>> codemoss-remote-sync (managed) >>>";
    private static final String CONFIG_MARKER_END = "# <<< codemoss-remote-sync (managed) <<<";

    public static SshKeyManager getInstance() {
        return ApplicationManager.getApplication().getService(SshKeyManager.class);
    }

    public Path keyDir() {
        return Path.of(PlatformUtils.getHomeDirectory(), ".codemoss", "remoteSync");
    }

    public Path privateKeyPath() {
        return keyDir().resolve(KEY_FILENAME);
    }

    public Path publicKeyPath() {
        return keyDir().resolve(KEY_FILENAME + ".pub");
    }

    public void ensureKeyPair() throws IOException {
        Path priv = privateKeyPath();
        if (Files.isRegularFile(priv) && Files.isRegularFile(publicKeyPath())) {
            // Files exist — re-tighten ACL in case a previous version left them
            // permissive (OpenSSH rejects loose key file ACLs on Windows).
            if (PlatformUtils.isWindows()) {
                hardenWindowsAcl(keyDir());
                hardenWindowsAcl(priv);
                hardenWindowsAcl(publicKeyPath());
            }
            return;
        }
        Files.createDirectories(keyDir());
        if (!PlatformUtils.isWindows()) {
            try {
                Files.setPosixFilePermissions(keyDir(), PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {}
        }
        Files.deleteIfExists(priv);
        Files.deleteIfExists(publicKeyPath());

        List<String> cmd = List.of(
                "ssh-keygen", "-t", "ed25519", "-N", "",
                "-C", "codemoss-remote-sync",
                "-f", priv.toString(),
                "-q"
        );
        ProcessOutcome out = runShort(cmd, null);
        if (out.exitCode != 0 || !Files.isRegularFile(priv)) {
            throw new IOException("ssh-keygen failed: " + out.combined);
        }
        if (!PlatformUtils.isWindows()) {
            try {
                Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
        } else {
            hardenWindowsAcl(keyDir());
            hardenWindowsAcl(priv);
            hardenWindowsAcl(publicKeyPath());
        }
        LOG.info("[SshKeyManager] generated key pair at " + priv);
    }

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

    public Result deployToRemote(String user, String host, int port, String password,
                                 String remoteOs, String remotePath) {
        try {
            ensureKeyPair();
            String pubKey = Files.readString(publicKeyPath(), StandardCharsets.UTF_8).trim();
            boolean isWindows = pickIsWindows(remoteOs, remotePath);

            if (probeKeyAuth(user, host, port)) {
                return Result.ok("key already accepted");
            }

            try (SshAskpass.Context askpass = SshAskpass.prepare(password == null ? "" : password)) {
                List<String> cmd = buildInstallCommand(user, host, port, pubKey, isWindows);
                LOG.info("[SshKeyManager] installing key");
                ProcessOutcome out = runShort(cmd, askpass);
                LOG.info("[SshKeyManager] install exit=" + out.exitCode + " output:\n" + out.combined);
                if (out.exitCode != 0) {
                    return Result.fail("key install failed: " + firstLine(out.combined));
                }
            }

            if (!probeKeyAuth(user, host, port)) {
                return Result.fail("key installed but the remote did not accept it — "
                        + "ensure the user can write authorized_keys and (on Windows admin) the SSH service can read administrators_authorized_keys");
            }
            return Result.ok("key deployed");
        } catch (Exception e) {
            LOG.warn("[SshKeyManager] deploy failed", e);
            return Result.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private List<String> buildInstallCommand(String user, String host, int port,
                                             String pubKey, boolean isWindows) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-p"); cmd.add(String.valueOf(port));
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=yes");
        cmd.add("-o"); cmd.add("NumberOfPasswordPrompts=1");
        cmd.add("-o"); cmd.add("BatchMode=no");
        cmd.add("-o"); cmd.add("PreferredAuthentications=password,keyboard-interactive");
        cmd.add(user + "@" + host);

        if (isWindows) {
            String script = buildWindowsPowershellScript(pubKey);
            String encoded = Base64.getEncoder().encodeToString(
                    script.getBytes(Charset.forName("UTF-16LE")));
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-EncodedCommand");
            cmd.add(encoded);
        } else {
            String escaped = pubKey.replace("'", "'\\''");
            String sh = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                    + "grep -qF '" + escaped + "' ~/.ssh/authorized_keys 2>/dev/null || "
                    + "echo '" + escaped + "' >> ~/.ssh/authorized_keys && "
                    + "chmod 600 ~/.ssh/authorized_keys";
            cmd.add(sh);
        }
        return cmd;
    }

    private String buildWindowsPowershellScript(String pubKey) {
        String quotedKey = pubKey.replace("'", "''");
        return "$ErrorActionPreference = 'Stop'\n"
                + "$key = '" + quotedKey + "'\n"
                + "$ok = $false\n"
                + "try {\n"
                + "  $userSsh = Join-Path $env:USERPROFILE '.ssh'\n"
                + "  if (-not (Test-Path $userSsh)) { New-Item -ItemType Directory -Path $userSsh -Force | Out-Null }\n"
                + "  $userAuth = Join-Path $userSsh 'authorized_keys'\n"
                + "  $existing = if (Test-Path $userAuth) { Get-Content $userAuth -ErrorAction SilentlyContinue } else { @() }\n"
                + "  if (-not ($existing -contains $key)) { Add-Content -Path $userAuth -Value $key -Encoding ascii }\n"
                + "  Write-Output ('user-file=' + $userAuth)\n"
                + "  $ok = $true\n"
                + "} catch { Write-Output ('user-file-error=' + $_.Exception.Message) }\n"
                + "try {\n"
                + "  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()\n"
                + "  $principal = New-Object Security.Principal.WindowsPrincipal($identity)\n"
                + "  $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)\n"
                + "  if ($isAdmin) {\n"
                + "    $adminDir = 'C:\\ProgramData\\ssh'\n"
                + "    if (-not (Test-Path $adminDir)) { New-Item -ItemType Directory -Path $adminDir -Force | Out-Null }\n"
                + "    $adminAuth = Join-Path $adminDir 'administrators_authorized_keys'\n"
                + "    $existing = if (Test-Path $adminAuth) { Get-Content $adminAuth -ErrorAction SilentlyContinue } else { @() }\n"
                + "    if (-not ($existing -contains $key)) { Add-Content -Path $adminAuth -Value $key -Encoding ascii }\n"
                + "    icacls $adminAuth /inheritance:r /grant 'SYSTEM:F' /grant 'BUILTIN\\Administrators:F' | Out-Null\n"
                + "    Write-Output ('admin-file=' + $adminAuth)\n"
                + "    $ok = $true\n"
                + "  }\n"
                + "} catch { Write-Output ('admin-file-error=' + $_.Exception.Message) }\n"
                + "if (-not $ok) { Write-Error 'no authorized_keys location was writable'; exit 1 }\n"
                + "Write-Output 'done'\n";
    }

    public boolean probeKeyAuth(String user, String host, int port) {
        if (!Files.isRegularFile(privateKeyPath())) return false;
        List<String> cmd = List.of(
                "ssh",
                "-i", privateKeyPath().toString(),
                "-p", String.valueOf(port),
                "-o", "IdentitiesOnly=yes",
                "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=8",
                "-o", "StrictHostKeyChecking=yes",
                user + "@" + host,
                "exit"
        );
        ProcessOutcome out = runShort(cmd, null);
        return out.exitCode == 0;
    }

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
        if (PlatformUtils.isWindows()) {
            // Strip any inherited groups (e.g. CodexSandboxUsers) — OpenSSH on
            // Windows refuses ~/.ssh/config when other principals can read it.
            hardenWindowsAcl(config.getParent());
            hardenWindowsAcl(config);
        }
        LOG.info("[SshKeyManager] wrote ~/.ssh/config entry for " + host);
    }

    private String renderBlock(String host, String user, int port) {
        StringBuilder sb = new StringBuilder();
        sb.append(CONFIG_MARKER_BEGIN).append('\n');
        sb.append("Host ").append(host).append('\n');
        sb.append("    HostName ").append(host).append('\n');
        sb.append("    User ").append(user).append('\n');
        sb.append("    Port ").append(port).append('\n');
        sb.append("    IdentityFile ").append(privateKeyPath()).append('\n');
        sb.append("    IdentitiesOnly yes\n");
        sb.append("    PreferredAuthentications publickey\n");
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

    private boolean pickIsWindows(String remoteOs, String remotePath) {
        if ("windows".equalsIgnoreCase(remoteOs)) return true;
        if ("unix".equalsIgnoreCase(remoteOs)) return false;
        if (remotePath == null) return false;
        String p = remotePath.trim();
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') return true;
        return p.contains("\\");
    }

    /**
     * Windows OpenSSH refuses to use {@code ~/.ssh/config} or private keys
     * that grant access to anyone besides the owner / SYSTEM. The default
     * Windows ACL on files created in the user profile is inherited from the
     * parent and often includes extra local groups (here:
     * {@code SpaceShipAI\CodexSandboxUsers}). We disable inheritance and
     * rewrite the DACL with only the current user and SYSTEM.
     */
    private static void hardenWindowsAcl(Path p) {
        if (!PlatformUtils.isWindows()) return;
        if (!Files.exists(p)) return;
        try {
            String user = System.getProperty("user.name");
            if (user == null || user.isBlank()) user = "Administrators";
            List<String> cmd = List.of(
                    "icacls", p.toString(),
                    "/inheritance:r",
                    "/grant", user + ":(F)",
                    "/grant", "SYSTEM:(F)"
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                LOG.warn("[SshKeyManager] icacls timed out for " + p);
                return;
            }
            LOG.info("[SshKeyManager] icacls " + p.getFileName()
                    + " exit=" + proc.exitValue() + " out=" + out.toString().trim());
        } catch (Exception e) {
            LOG.warn("[SshKeyManager] icacls failed for " + p + ": " + e.getMessage());
        }
    }

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
            }, "ssh-key-reader");
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
