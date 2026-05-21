package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates a one-shot script that echoes the SSH password from an environment
 * variable. The script lives in a temp file with 0700 permissions on Unix and
 * is deleted via {@link Context#close()} when the operation finishes.
 *
 * <p>Two cross-platform footguns this avoids:
 * <ul>
 *   <li>OpenSSH only consults SSH_ASKPASS when there's no controlling tty —
 *       Unix callers must wrap their process in {@code setsid} (or equivalent)
 *       and set {@code DISPLAY=:0} so old OpenSSH versions also cooperate.
 *   <li>The password is passed via env (never argv), so it doesn't show up in
 *       {@code ps}/{@code Task Manager} listings.
 * </ul>
 */
public final class SshAskpass {

    private static final Logger LOG = Logger.getInstance(SshAskpass.class);

    private SshAskpass() {}

    public static Context prepare(String password) throws IOException {
        if (password == null) password = "";
        Path script = writeScript();
        Map<String, String> env = new HashMap<>();
        env.put("SSH_ASKPASS", script.toString());
        env.put("SSH_ASKPASS_REQUIRE", "force");
        env.put("MUTAGEN_SSH_PASSWORD", password);
        if (!PlatformUtils.isWindows()) {
            // Old OpenSSH (< 8.4) requires DISPLAY to even consult askpass.
            env.put("DISPLAY", ":0");
        }
        return new Context(script, env);
    }

    private static Path writeScript() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"));
        if (PlatformUtils.isWindows()) {
            Path script = Files.createTempFile(dir, "codemoss-askpass-", ".bat");
            Files.writeString(script, "@echo off\r\necho %MUTAGEN_SSH_PASSWORD%\r\n",
                    StandardCharsets.UTF_8);
            return script;
        }
        Path script = Files.createTempFile(dir, "codemoss-askpass-", ".sh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$MUTAGEN_SSH_PASSWORD\"\n",
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception e) {
            LOG.warn("[SshAskpass] chmod failed: " + e.getMessage());
        }
        return script;
    }

    /** Returned to the caller so they can wire env vars and clean up. */
    public static final class Context implements AutoCloseable {
        private final Path script;
        private final Map<String, String> env;

        private Context(Path script, Map<String, String> env) {
            this.script = script;
            this.env = env;
        }

        public Map<String, String> envOverrides() {
            return env;
        }

        public Path scriptPath() {
            return script;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(script);
            } catch (IOException e) {
                LOG.warn("[SshAskpass] cleanup failed: " + e.getMessage());
            }
        }
    }
}
