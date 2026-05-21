package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.APP)
public final class MutagenBinary {

    private static final Logger LOG = Logger.getInstance(MutagenBinary.class);

    public static MutagenBinary getInstance() {
        return ApplicationManager.getApplication().getService(MutagenBinary.class);
    }

    /** Root install directory: ~/.codemoss/mutagen/ */
    public static Path installDir() {
        return Paths.get(PlatformUtils.getHomeDirectory(), ".codemoss", "mutagen");
    }

    /** Full path to the mutagen executable (does not check existence). */
    public Path executablePath() {
        String name = PlatformUtils.isWindows() ? "mutagen.exe" : "mutagen";
        return installDir().resolve(name);
    }

    /** True when the executable file exists and is runnable. */
    public boolean isAvailable() {
        Path p = executablePath();
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /**
     * Run `mutagen version` and return the version string (e.g. "0.18.3"),
     * or null when the binary is missing or the command fails.
     */
    public String versionString() {
        if (!isAvailable()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(executablePath().toString(), "version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                out = sb.toString();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return parseVersion(out);
        } catch (Exception e) {
            LOG.warn("[MutagenBinary] versionString failed: " + e.getMessage());
            return null;
        }
    }

    private static String parseVersion(String out) {
        if (out == null || out.isEmpty()) return null;
        String trimmed = out.trim();
        int sp = trimmed.indexOf(' ');
        String first = sp > 0 ? trimmed.substring(0, sp) : trimmed;
        if (first.startsWith("v")) first = first.substring(1);
        return first;
    }
}
