package com.github.claudecodegui.provider.common;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lightweight Server-Sent Events client.
 *
 * <p>Connects to a long-lived SSE endpoint, parses the standard line-based
 * frame format ({@code id:}, {@code event:}, {@code data:}, blank line as
 * record separator, {@code :} as comment/heartbeat), and invokes
 * {@code onData} per event with the decoded data string.
 *
 * <p>Reconnects automatically on transient errors with exponential backoff,
 * carrying the last seen event id in the {@code Last-Event-ID} request header.
 * After exceeding {@link #MAX_RECONNECT_ATTEMPTS} consecutive failures it
 * gives up and notifies {@code onPermanentFailure}.
 *
 * <p>Designed for {@link RemoteBridge}; not project-aware, no heartbeat
 * timeout enforcement on its own — server-side is expected to send periodic
 * SSE comments to keep proxies awake; the JDK HttpClient read times out
 * after the request timeout.
 */
public class SseSubscriber {

    private static final Logger LOG = Logger.getInstance(SseSubscriber.class);
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 20;
    /** Long-poll style — large enough that the read blocks for live data, small
     *  enough that we don't end up waiting forever on a half-open connection. */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(60);

    private final URI url;
    private final AtomicLong lastEventId;
    private final Consumer<String> onData;
    private final Consumer<Throwable> onPermanentFailure;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final HttpClient http;
    private volatile Thread workerThread;

    public SseSubscriber(
            URI url,
            AtomicLong lastEventId,
            Consumer<String> onData,
            Consumer<Throwable> onPermanentFailure
    ) {
        this.url = url;
        this.lastEventId = lastEventId;
        this.onData = onData;
        this.onPermanentFailure = onPermanentFailure;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public void start() {
        Thread t = new Thread(this::loop, "SseSubscriber-" + Integer.toHexString(hashCode()));
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    public void cancel() {
        cancelled.set(true);
        Thread t = workerThread;
        if (t != null) t.interrupt();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // =========================================================================
    // Internal loop
    // =========================================================================

    private void loop() {
        long backoff = INITIAL_BACKOFF_MS;
        int attempts = 0;
        while (!cancelled.get()) {
            try {
                runOnce();
                if (cancelled.get()) return;
                LOG.info("[SseSubscriber] Stream ended cleanly, reconnecting...");
                attempts = 0;
                backoff = INITIAL_BACKOFF_MS;
            } catch (PermanentSseFailure perm) {
                LOG.warn("[SseSubscriber] Permanent failure: " + perm.getMessage());
                try { onPermanentFailure.accept(perm); } catch (Exception ignore) {}
                return;
            } catch (Exception e) {
                if (cancelled.get()) return;
                LOG.warn("[SseSubscriber] Stream error: " + e.getMessage());
                attempts++;
                if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                    LOG.error("[SseSubscriber] Giving up after " + attempts + " consecutive failures");
                    try { onPermanentFailure.accept(e); } catch (Exception ignore) {}
                    return;
                }
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                if (cancelled.get()) return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private static final class PermanentSseFailure extends RuntimeException {
        PermanentSseFailure(String message) { super(message); }
    }

    private void runOnce() throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(url)
                .timeout(READ_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        long lid = lastEventId.get();
        if (lid > 0) b.header("Last-Event-ID", String.valueOf(lid));

        HttpResponse<java.io.InputStream> resp = http.send(
                b.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        int status = resp.statusCode();
        if (status != 200) {
            // Read a snippet of body for diagnostics, then bail.
            String snippet;
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n = err.read(buf);
                if (n > 0) sb.append(buf, 0, n);
                snippet = sb.toString();
            }
            // 404/410 are permanent — the session no longer exists on the server.
            // Don't retry; surface as permanent failure so the bridge can shut down.
            if (status == 404 || status == 410) {
                cancelled.set(true);
                throw new PermanentSseFailure("SSE bad status " + status + ": " + snippet);
            }
            throw new RuntimeException("SSE bad status " + status + ": " + snippet);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String currentId = null;
            StringBuilder dataBuf = new StringBuilder();

            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // End of an event: emit accumulated data
                    if (dataBuf.length() > 0) {
                        if (currentId != null) {
                            try { lastEventId.set(Long.parseLong(currentId)); }
                            catch (NumberFormatException ignored) {}
                        }
                        try { onData.accept(dataBuf.toString()); }
                        catch (Exception e) {
                            LOG.warn("[SseSubscriber] onData handler threw", e);
                        }
                    }
                    currentId = null;
                    dataBuf.setLength(0);
                    continue;
                }
                if (line.charAt(0) == ':') {
                    // Comment / keepalive — ignore.
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon >= 0 ? line.substring(0, colon) : line;
                String value;
                if (colon < 0) value = "";
                else if (colon + 1 < line.length() && line.charAt(colon + 1) == ' ')
                    value = line.substring(colon + 2);
                else value = line.substring(colon + 1);

                switch (field) {
                    case "id":
                        currentId = value;
                        break;
                    case "event":
                        // We don't dispatch by event type; only data matters.
                        break;
                    case "data":
                        if (dataBuf.length() > 0) dataBuf.append('\n');
                        dataBuf.append(value);
                        break;
                    case "retry":
                        // Could honour as backoff hint; ignored for simplicity.
                        break;
                    default:
                        // Unknown field — ignore per spec.
                }
            }
        }
    }
}
