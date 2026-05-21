package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and installs the pinned version of mutagen into ~/.codemoss/mutagen/.
 * Network IO is done off the EDT; callers should observe the returned future.
 */
@Service(Service.Level.APP)
public final class MutagenInstaller {

    private static final Logger LOG = Logger.getInstance(MutagenInstaller.class);

    /** Pinned mutagen release. Bump when validating against a newer version. */
    public static final String TARGET_VERSION = "0.18.1";

    /** Raw GitHub release base. The configured proxy is prepended to this. */
    public static final String GITHUB_RELEASE_BASE =
            "https://github.com/mutagen-io/mutagen/releases/download/";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile CancellationToken activeToken;

    public static MutagenInstaller getInstance() {
        return ApplicationManager.getApplication().getService(MutagenInstaller.class);
    }

    /**
     * Resolve the archive URL for the current platform without starting a download.
     * When {@code githubProxy} is non-empty the proxy prefix is prepended:
     * {@code https://ghfast.top/https://github.com/...}.
     */
    public static String archiveUrl(String githubProxy) {
        return applyProxy(githubProxy, rawArchiveUrl());
    }

    private static String rawArchiveUrl() {
        Asset asset = resolveAsset(TARGET_VERSION);
        return GITHUB_RELEASE_BASE + "v" + TARGET_VERSION + "/" + asset.archiveName;
    }

    private static String rawChecksumUrl() {
        return GITHUB_RELEASE_BASE + "v" + TARGET_VERSION
                + "/mutagen_v" + TARGET_VERSION + "_SHA256SUMS";
    }

    private static String applyProxy(String githubProxy, String rawUrl) {
        if (githubProxy == null) return rawUrl;
        String trimmed = githubProxy.trim();
        if (trimmed.isEmpty()) return rawUrl;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "/" + rawUrl;
    }

    public boolean isDownloading() {
        return inFlight.get();
    }

    /** Cancel the in-flight download, if any. */
    public void cancel() {
        CancellationToken t = activeToken;
        if (t != null) t.cancel();
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public CompletableFuture<Path> install(String githubProxy, ProgressListener listener) {
        if (!inFlight.compareAndSet(false, true)) {
            CompletableFuture<Path> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("download already in progress"));
            return f;
        }
        CancellationToken token = new CancellationToken();
        activeToken = token;

        CompletableFuture<Path> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path exe = doInstall(githubProxy, listener, token);
                future.complete(exe);
            } catch (CancelledException ce) {
                future.cancel(false);
            } catch (Throwable t) {
                LOG.warn("[MutagenInstaller] install failed", t);
                future.completeExceptionally(t);
            } finally {
                activeToken = null;
                inFlight.set(false);
            }
        });
        return future;
    }

    private Path doInstall(String githubProxy, ProgressListener listener, CancellationToken token)
            throws IOException, CancelledException {
        Asset asset = resolveAsset(TARGET_VERSION);
        String archiveUrl = applyProxy(githubProxy, rawArchiveUrl());
        String checksumUrl = applyProxy(githubProxy, rawChecksumUrl());

        Path installRoot = MutagenBinary.installDir();
        Files.createDirectories(installRoot);
        Path tmp = Files.createTempFile(installRoot, "download-", "." + asset.extension());

        LOG.info("[MutagenInstaller] downloading " + archiveUrl);
        try {
            downloadTo(archiveUrl, tmp, listener, token);
            checkCancelled(token);

            String expectedSha = fetchExpectedSha(checksumUrl, asset.archiveName, token);
            if (expectedSha != null) {
                String actual = sha256Hex(tmp);
                if (!expectedSha.equalsIgnoreCase(actual)) {
                    throw new IOException("checksum mismatch: expected " + expectedSha + ", actual " + actual);
                }
                LOG.info("[MutagenInstaller] checksum OK");
            } else {
                LOG.warn("[MutagenInstaller] checksum file unavailable, skipping verification");
            }

            Path exe = MutagenBinary.getInstance().executablePath();
            Files.deleteIfExists(exe);
            extract(tmp, installRoot, asset);
            if (!Files.isRegularFile(exe)) {
                throw new IOException("mutagen executable not found after extraction: " + exe);
            }
            makeExecutable(exe);
            return exe;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /* ------------------------- download / extraction ------------------------- */

    private void downloadTo(String url, Path dest, ProgressListener listener, CancellationToken token)
            throws IOException, CancelledException {
        HttpURLConnection conn = openConnection(url);
        long contentLength = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long readSoFar = 0;
            long lastNotify = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                checkCancelled(token);
                out.write(buf, 0, n);
                readSoFar += n;
                if (listener != null && (readSoFar - lastNotify >= 262_144 || readSoFar == contentLength)) {
                    listener.onProgress(readSoFar, contentLength);
                    lastNotify = readSoFar;
                }
            }
            if (listener != null) listener.onProgress(readSoFar, contentLength);
        } finally {
            conn.disconnect();
        }
    }

    private String fetchExpectedSha(String url, String archiveName, CancellationToken token)
            throws CancelledException {
        try {
            HttpURLConnection conn = openConnection(url);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    checkCancelled(token);
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 2) continue;
                    String name = parts[parts.length - 1];
                    if (name.equals(archiveName) || name.equals("*" + archiveName)) {
                        return parts[0];
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (CancelledException ce) {
            throw ce;
        } catch (Exception e) {
            LOG.warn("[MutagenInstaller] fetch checksum failed: " + e.getMessage());
        }
        return null;
    }

    private void extract(Path archive, Path destDir, Asset asset) throws IOException {
        if ("zip".equals(asset.extension())) {
            extractZip(archive, destDir, asset.binaryNameInArchive);
        } else {
            extractTarGz(archive, destDir, asset.binaryNameInArchive);
        }
    }

    private void extractZip(Path archive, Path destDir, String binaryName) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = baseName(entry.getName());
                if (name.equalsIgnoreCase(binaryName)) {
                    Files.copy(zip, destDir.resolve(binaryName), StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("mutagen binary not found inside zip");
    }

    private void extractTarGz(Path archive, Path destDir, String binaryName) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             GZIPInputStream gz = new GZIPInputStream(in);
             TarInputStream tar = new TarInputStream(gz)) {
            TarInputStream.Entry entry;
            while ((entry = tar.nextEntry()) != null) {
                if (entry.isDirectory) continue;
                String name = baseName(entry.name);
                if (name.equals(binaryName)) {
                    Files.copy(tar, destDir.resolve(binaryName), StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("mutagen binary not found inside tar.gz");
    }

    /* ------------------------- helpers ------------------------- */

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "codemoss-jetbrains-plugin");
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void makeExecutable(Path p) {
        try {
            if (PlatformUtils.isWindows()) return;
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception e) {
            LOG.warn("[MutagenInstaller] chmod failed: " + e.getMessage());
        }
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void checkCancelled(CancellationToken token) throws CancelledException {
        if (token.isCancelled()) throw new CancelledException();
    }

    /* ------------------------- asset resolution ------------------------- */

    private static Asset resolveAsset(String version) {
        String osArch = osArch();
        String archiveName;
        String binary = PlatformUtils.isWindows() ? "mutagen.exe" : "mutagen";
        if (PlatformUtils.isWindows()) {
            archiveName = "mutagen_windows_" + osArch + "_v" + version + ".zip";
        } else if (PlatformUtils.isMac()) {
            archiveName = "mutagen_darwin_" + osArch + "_v" + version + ".tar.gz";
        } else {
            archiveName = "mutagen_linux_" + osArch + "_v" + version + ".tar.gz";
        }
        return new Asset(archiveName, binary);
    }

    private static String osArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        return "amd64";
    }

    private static final class Asset {
        final String archiveName;
        final String binaryNameInArchive;

        Asset(String archiveName, String binaryNameInArchive) {
            this.archiveName = archiveName;
            this.binaryNameInArchive = binaryNameInArchive;
        }

        String extension() {
            return archiveName.endsWith(".zip") ? "zip" : "tgz";
        }
    }

    /* ------------------------- cancellation ------------------------- */

    public static final class CancellationToken {
        private volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static final class CancelledException extends RuntimeException {}

    /* ------------------------- minimal tar reader ------------------------- *
     * Avoids adding the commons-compress dependency for a single use case.
     * Supports ustar (the format mutagen releases use): 512-byte header,
     * payloads padded to 512-byte boundary.
     * --------------------------------------------------------------------- */

    private static final class TarInputStream extends InputStream {
        private final InputStream in;
        private long remaining;
        private long pad;
        private boolean eof;

        TarInputStream(InputStream in) {
            this.in = in;
        }

        Entry nextEntry() throws IOException {
            skipFully(remaining + pad);
            remaining = 0;
            pad = 0;
            while (true) {
                byte[] header = new byte[512];
                int read = readFully(in, header);
                if (read < 512) { eof = true; return null; }
                if (isEmpty(header)) {
                    // Two consecutive empty blocks mark end of archive.
                    byte[] second = new byte[512];
                    readFully(in, second);
                    eof = true;
                    return null;
                }
                String name = readString(header, 0, 100);
                long size = readOctal(header, 124, 12);
                char type = (char) header[156];
                // Long-link / pax extended headers — skip payload and continue.
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    long padBytes = (512 - (size % 512)) % 512;
                    skipFully(size + padBytes);
                    continue;
                }
                boolean isDirectory = type == '5' || name.endsWith("/");
                remaining = size;
                pad = (512 - (size % 512)) % 512;
                return new Entry(name, size, isDirectory);
            }
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException { in.close(); }

        private void skipFully(long bytes) throws IOException {
            while (bytes > 0) {
                long skipped = in.skip(bytes);
                if (skipped <= 0) {
                    if (in.read() < 0) return;
                    bytes--;
                } else {
                    bytes -= skipped;
                }
            }
        }

        private static int readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) return off;
                off += n;
            }
            return off;
        }

        private static boolean isEmpty(byte[] block) {
            for (byte b : block) if (b != 0) return false;
            return true;
        }

        private static String readString(byte[] buf, int off, int len) {
            int end = off;
            int max = off + len;
            while (end < max && buf[end] != 0) end++;
            return new String(buf, off, end - off, StandardCharsets.UTF_8);
        }

        private static long readOctal(byte[] buf, int off, int len) {
            long value = 0;
            for (int i = off; i < off + len; i++) {
                byte b = buf[i];
                if (b == 0 || b == ' ') {
                    if (value == 0) continue;
                    break;
                }
                if (b < '0' || b > '7') break;
                value = value * 8 + (b - '0');
            }
            return value;
        }

        static final class Entry {
            final String name;
            final long size;
            final boolean isDirectory;

            Entry(String name, long size, boolean isDirectory) {
                this.name = name;
                this.size = size;
                this.isDirectory = isDirectory;
            }
        }
    }
}
