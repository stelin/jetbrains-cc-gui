package com.github.claudecodegui.remotesync;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Stores the remote sync SSH password in the IDE's PasswordSafe, which is
 * backed by the OS keychain on macOS/Windows and KWallet/Secret Service on
 * Linux. The password never lives in {@code codemoss-settings.json}.
 */
public final class SyncCredentialsStore {

    private static final Logger LOG = Logger.getInstance(SyncCredentialsStore.class);
    private static final String SERVICE_NAME = "codemoss.remoteSync";
    private static final String USER_NAME = "password";

    private SyncCredentialsStore() {}

    private static CredentialAttributes attributes() {
        return new CredentialAttributes(SERVICE_NAME, USER_NAME);
    }

    /** Persist (or clear, when {@code password} is null/empty) the SSH password. */
    public static void save(String password) {
        try {
            if (password == null || password.isEmpty()) {
                PasswordSafe.getInstance().set(attributes(), null);
                return;
            }
            PasswordSafe.getInstance().set(attributes(), new Credentials(USER_NAME, password));
        } catch (Exception e) {
            LOG.warn("[SyncCredentialsStore] save failed: " + e.getMessage());
        }
    }

    /** Returns the stored password or null when nothing is stored. */
    public static String load() {
        try {
            Credentials c = PasswordSafe.getInstance().get(attributes());
            if (c == null) return null;
            String pw = c.getPasswordAsString();
            return (pw == null || pw.isEmpty()) ? null : pw;
        } catch (Exception e) {
            LOG.warn("[SyncCredentialsStore] load failed: " + e.getMessage());
            return null;
        }
    }

    /** True when a non-empty password is stored. */
    public static boolean exists() {
        return load() != null;
    }

    public static void clear() {
        save(null);
    }
}
