package com.github.claudecodegui.remotesync.model;

import com.google.gson.JsonObject;

/**
 * Sent to the webview when a remote host's SSH key has not been seen before.
 * Tied to a server-side {@code CompletableFuture<Boolean>} via {@link #id} so
 * the user's accept/reject decision can resolve a pending sync operation.
 */
public final class HostKeyChallenge {

    private final String id;
    private final String host;
    private final int port;
    private final String keyType;        // "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256" …
    private final String fingerprintSha256; // "SHA256:xxx..."
    private final String rawKey;         // Full known_hosts line (for caller to append).

    public HostKeyChallenge(String id, String host, int port, String keyType,
                            String fingerprintSha256, String rawKey) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.keyType = keyType;
        this.fingerprintSha256 = fingerprintSha256;
        this.rawKey = rawKey;
    }

    public String getId() { return id; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getKeyType() { return keyType; }
    public String getFingerprintSha256() { return fingerprintSha256; }
    public String getRawKey() { return rawKey; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("host", host);
        o.addProperty("port", port);
        o.addProperty("keyType", keyType);
        o.addProperty("fingerprintSha256", fingerprintSha256);
        return o;
    }
}
