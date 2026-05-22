package com.github.claudecodegui.session.pair;

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
 * Run a step's verify command (e.g. {@code go build ./...}, {@code npm test --silent})
 * in the project's working directory and report pass/fail to the EventBus.
 *
 * <p>Phase B "Gate 1": Supervisor relies on this to detect functional regressions
 * before triggering a Gate 2 skill review. Verify failures are forwarded back
 * to the main AI via {@code inject_prompt} so the main AI can fix and retry.
 */
public class VerifyExecutor {

    private static final Logger LOG = Logger.getInstance(VerifyExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    public static final class VerifyResult {
        public final boolean pass;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long durationMs;

        public VerifyResult(boolean pass, int exitCode, String stdout, String stderr, long durationMs) {
            this.pass = pass;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.durationMs = durationMs;
        }
    }

    /**
     * Run {@code command} in {@code workingDir}. Returns a future that always
     * completes (never exceptional) — on process failure it returns
     * {@code pass=false} with the error string in {@code stderr}.
     *
     * @param command  shell command; split on spaces. For complex commands use
     *                 {@code sh -c "..."} explicitly.
     * @param workingDir absolute path; must exist.
     * @param timeoutSeconds 0 or negative → use {@link #DEFAULT_TIMEOUT_SECONDS}.
     */
    public CompletableFuture<VerifyResult> verify(String command, String workingDir, long timeoutSeconds) {
        long effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            if (command == null || command.isBlank()) {
                return new VerifyResult(false, -1, "", "(empty verify command)", 0);
            }
            File wd = new File(workingDir);
            if (!wd.isDirectory()) {
                return new VerifyResult(false, -1, "", "working dir not found: " + workingDir, 0);
            }

            List<String> argv = splitCommand(command);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(wd);
            pb.redirectErrorStream(false);

            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                LOG.warn("[VerifyExecutor] start failed: " + e.getMessage());
                return new VerifyResult(false, -1, "", "process start failed: " + e.getMessage(),
                        System.currentTimeMillis() - t0);
            }

            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();

            Thread out = drain(proc.getInputStream(), stdoutBuf);
            Thread err = drain(proc.getErrorStream(), stderrBuf);

            boolean finished;
            try {
                finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                return new VerifyResult(false, -1, stdoutBuf.toString(),
                        "interrupted", System.currentTimeMillis() - t0);
            }

            if (!finished) {
                proc.destroyForcibly();
                joinQuietly(out);
                joinQuietly(err);
                return new VerifyResult(false, -1, stdoutBuf.toString(),
                        "timeout after " + effectiveTimeout + "s\n" + stderrBuf.toString(),
                        System.currentTimeMillis() - t0);
            }

            joinQuietly(out);
            joinQuietly(err);
            int exitCode = proc.exitValue();
            return new VerifyResult(
                    exitCode == 0,
                    exitCode,
                    stdoutBuf.toString(),
                    stderrBuf.toString(),
                    System.currentTimeMillis() - t0
            );
        });
    }

    /**
     * Default-timeout overload.
     */
    public CompletableFuture<VerifyResult> verify(String command, String workingDir) {
        return verify(command, workingDir, DEFAULT_TIMEOUT_SECONDS);
    }

    private static Thread drain(java.io.InputStream is, StringBuilder buf) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    buf.append(line).append('\n');
                    // Cap memory: a single verify shouldn't ever produce >1 MB stdout.
                    if (buf.length() > 1_048_576) {
                        buf.append("... (truncated)\n");
                        break;
                    }
                }
            } catch (IOException ignored) { /* stream closed */ }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try { t.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Split a verify command into argv. Honors simple quoting; uses {@code sh -c}
     * envelope on Unix when the command contains shell operators (pipe, redirect,
     * &&, etc.) so users can write {@code "go test ./... | tee out.log"}.
     */
    private static List<String> splitCommand(String cmd) {
        boolean shelly = cmd.contains("|") || cmd.contains(">") || cmd.contains("<")
                || cmd.contains("&&") || cmd.contains("||") || cmd.contains(";");
        if (shelly) {
            List<String> wrapped = new ArrayList<>(3);
            if (isWindows()) {
                wrapped.add("cmd");
                wrapped.add("/c");
            } else {
                wrapped.add("sh");
                wrapped.add("-c");
            }
            wrapped.add(cmd);
            return wrapped;
        }
        // Plain whitespace split with quote handling.
        List<String> argv = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    argv.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) argv.add(cur.toString());
        return argv;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
