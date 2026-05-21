package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.remotesync.model.HostKeyChallenge;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Probes a remote host with {@code ssh-keyscan} and updates
 * {@code ~/.ssh/known_hosts}. Used to pre-trust a host before mutagen connects,
 * so the user only sees one fingerprint prompt (in our own UI) instead of
 * SSH's interactive stdin prompt.
 */
public final class KnownHostsManager {

    private static final Logger LOG = Logger.getInstance(KnownHostsManager.class);

    private KnownHostsManager() {}

    public static Path knownHostsFile() {
        return Path.of(PlatformUtils.getHomeDirectory(), ".ssh", "known_hosts");
    }

    /** True when the host (matched by host[:port] entry) is already trusted. */
    public static boolean isHostKnown(String host, int port) {
        Path file = knownHostsFile();
        if (!Files.isRegularFile(file)) return false;
        String marker = port == 22 ? host : ("[" + host + "]:" + port);
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String hostsField = line.substring(0, sp);
                for (String h : hostsField.split(",")) {
                    if (h.equals(marker)) return true;
                    if (h.equals(host) && port == 22) return true;
                }
            }
        } catch (IOException e) {
            LOG.warn("[KnownHosts] read failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Run {@code ssh-keyscan} against the host and synthesise a challenge the
     * user can accept or reject. Returns null when ssh-keyscan is missing or
     * the host cannot be reached.
     */
    public static HostKeyChallenge probe(String host, int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ssh-keyscan", "-T", "5", "-p", String.valueOf(port), host);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    out.append(line).append('\n');
                }
            }
            // Drain stderr to avoid blocking the child.
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* ignore */ }
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String raw = out.toString().trim();
            if (raw.isEmpty()) return null;

            // Prefer ed25519 > ecdsa > rsa.
            String chosen = pickPreferred(raw);
            String[] parts = chosen.split("\\s+");
            if (parts.length < 3) return null;
            String keyType = parts[1];
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(parts[2]);
            } catch (IllegalArgumentException e) {
                return null;
            }
            String fingerprint = sha256Fingerprint(keyBytes);
            return new HostKeyChallenge(UUID.randomUUID().toString(),
                    host, port, keyType, fingerprint, chosen);
        } catch (Exception e) {
            LOG.warn("[KnownHosts] ssh-keyscan failed: " + e.getMessage());
            return null;
        }
    }

    /** Append the raw {@code known_hosts} line. Creates the directory if needed. */
    public static void trust(HostKeyChallenge challenge) throws IOException {
        Path file = knownHostsFile();
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
            try {
                if (!PlatformUtils.isWindows()) {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                }
            } catch (Exception ignored) {}
        }
        String line = challenge.getRawKey();
        if (!line.endsWith("\n")) line = line + "\n";
        // Rewrite the host prefix so the entry is keyed by [host]:port when non-standard.
        String prefix = challenge.getPort() == 22
                ? challenge.getHost()
                : "[" + challenge.getHost() + "]:" + challenge.getPort();
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length == 2) {
            line = prefix + " " + parts[1] + "\n";
        }
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        LOG.info("[KnownHosts] trusted " + challenge.getHost() + ":" + challenge.getPort()
                + " (" + challenge.getKeyType() + ")");
    }

    private static String pickPreferred(String multilineOutput) {
        String rsa = null;
        String ecdsa = null;
        for (String line : multilineOutput.split("\\R")) {
            String t = line.trim();
            if (t.contains(" ssh-ed25519 ")) return t;
            if (ecdsa == null && t.matches(".+ ecdsa-sha2-\\S+ .+")) ecdsa = t;
            if (rsa == null && t.contains(" ssh-rsa ")) rsa = t;
        }
        if (ecdsa != null) return ecdsa;
        if (rsa != null) return rsa;
        return multilineOutput.split("\\R")[0].trim();
    }

    private static String sha256Fingerprint(byte[] keyBytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            // OpenSSH style: base64 without padding.
            String b64 = Base64.getEncoder().withoutPadding().encodeToString(hash);
            return "SHA256:" + b64;
        } catch (Exception e) {
            return "SHA256:?";
        }
    }
}
