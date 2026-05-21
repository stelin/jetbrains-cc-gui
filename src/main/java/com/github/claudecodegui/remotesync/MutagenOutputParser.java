package com.github.claudecodegui.remotesync;

import com.github.claudecodegui.remotesync.model.SyncStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful parser for {@code mutagen sync monitor --long} output. Each line is
 * absorbed and the latest known fields are merged into a fresh {@link SyncStatus}
 * via {@link #currentStatus(String)}.
 *
 * <p>Format reference (mutagen 0.18.x):
 * <pre>
 * Name: codemoss-sync
 * Identifier: ...
 * Status: Watching for changes
 * Alpha:
 *     URL: /local/path
 *     Connection state: Connected
 * Beta:
 *     URL: user@host:/remote/path
 *     Connection state: Connected
 * </pre>
 *
 * <p>Conflict count appears as {@code Conflicts: N} when present.
 */
public final class MutagenOutputParser {

    private static final Pattern STATUS_LINE = Pattern.compile("^Status:\\s*(.+)$");
    private static final Pattern CONFLICTS_LINE = Pattern.compile("^Conflicts:\\s*(\\d+).*");
    private static final Pattern CONN_STATE = Pattern.compile("^\\s*Connection state:\\s*(.+)$");
    private static final Pattern STAGING_LINE =
            Pattern.compile("Staging files.*?\\(([\\d.]+)\\s*[KMG]B?/([\\d.]+)\\s*[KMG]B?\\)", Pattern.CASE_INSENSITIVE);
    // "96 directories" / "25 files (11 kB)" / "0 symbolic links"
    private static final Pattern DIRS_LINE = Pattern.compile("^(\\d+)\\s+director(?:y|ies)$");
    private static final Pattern FILES_LINE =
            Pattern.compile("^(\\d+)\\s+files?(?:\\s*\\(([\\d.]+)\\s*([KMGT]?)B?\\))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENTS_HEADER = Pattern.compile("^Synchronizable contents:?$");

    private String latestStatus = "";
    private String alphaConn = "";
    private String betaConn = "";
    private int conflicts = 0;
    private long stagingDone = 0;
    private long stagingTotal = 0;
    private long fileCount = 0;
    private long fileBytes = 0;
    private long dirCount = 0;
    private String currentSide = null;  // alpha / beta — drives "Connection state" + contents binding
    private boolean inContentsBlock = false;

    public synchronized SyncStatus absorb(String line, String sessionName) {
        if (line == null) return null;
        String trimmed = line.trim();

        // Side header: switch which "Connection state" is set on the next match.
        // mutagen 0.16+ uses Source/Destination; older versions use Alpha/Beta.
        if (trimmed.startsWith("Alpha:") || trimmed.startsWith("Source:")) {
            currentSide = "alpha";
            inContentsBlock = false;
        } else if (trimmed.startsWith("Beta:") || trimmed.startsWith("Destination:")) {
            currentSide = "beta";
            inContentsBlock = false;
        }

        // Track entry into / exit from the "Synchronizable contents:" subsection.
        if (CONTENTS_HEADER.matcher(trimmed).matches()) {
            inContentsBlock = true;
            return null;
        }
        if (inContentsBlock && !trimmed.isEmpty()) {
            // Indented count lines live inside the contents block. The block ends
            // when we hit a non-indented line — but we use Side headers as the
            // exit signal too. Use Alpha (or default) values as the canonical
            // file count (Alpha + Beta should match in steady state).
            if ("alpha".equals(currentSide) || currentSide == null) {
                Matcher dm = DIRS_LINE.matcher(trimmed);
                if (dm.matches()) {
                    dirCount = parseLongSafe(dm.group(1));
                    return currentStatus(sessionName);
                }
                Matcher fm = FILES_LINE.matcher(trimmed);
                if (fm.matches()) {
                    fileCount = parseLongSafe(fm.group(1));
                    if (fm.group(2) != null) {
                        double value = parseDoubleSafe(fm.group(2));
                        long mult = sizeMultiplier(fm.group(3));
                        fileBytes = (long) (value * mult);
                    }
                    return currentStatus(sessionName);
                }
            }
        }

        Matcher m;

        m = STATUS_LINE.matcher(trimmed);
        if (m.find()) {
            latestStatus = m.group(1).trim();
            stagingDone = 0;
            stagingTotal = 0;
            return currentStatus(sessionName);
        }
        m = CONFLICTS_LINE.matcher(trimmed);
        if (m.find()) {
            try {
                conflicts = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
            return currentStatus(sessionName);
        }
        m = CONN_STATE.matcher(line);
        if (m.find()) {
            String state = m.group(1).trim();
            if ("alpha".equals(currentSide)) alphaConn = state;
            else if ("beta".equals(currentSide)) betaConn = state;
            return currentStatus(sessionName);
        }
        m = STAGING_LINE.matcher(trimmed);
        if (m.find()) {
            try {
                stagingDone = (long) (Double.parseDouble(m.group(1)) * 1024 * 1024);
                stagingTotal = (long) (Double.parseDouble(m.group(2)) * 1024 * 1024);
            } catch (NumberFormatException ignored) {}
            return currentStatus(sessionName);
        }
        return null;
    }

    public synchronized SyncStatus currentStatus(String name) {
        String s = latestStatus.toLowerCase();
        boolean betaDisconnected = betaConn.toLowerCase().contains("disconnect")
                || betaConn.toLowerCase().contains("error");

        SyncStatus base;
        if (conflicts > 0 && !s.contains("staging") && !s.contains("transitioning")) {
            base = SyncStatus.conflict(name, conflicts);
        } else if (s.contains("watching")) {
            base = SyncStatus.watching(name);
        } else if (s.contains("staging") || s.contains("transitioning") || s.contains("scanning") || s.contains("waiting to scan")) {
            base = SyncStatus.syncing(name, stagingDone, stagingTotal);
        } else if (s.contains("connecting")) {
            base = SyncStatus.starting(name);
        } else if (s.contains("halted") || s.contains("error")) {
            base = SyncStatus.error(name, latestStatus);
        } else if (betaDisconnected) {
            base = SyncStatus.disconnected(name, betaConn);
        } else if (s.isEmpty()) {
            base = SyncStatus.starting(name);
        } else {
            base = SyncStatus.watching(name);
        }
        base.withInventory(dirCount, fileCount, fileBytes);
        return base;
    }

    public synchronized void reset() {
        latestStatus = "";
        alphaConn = "";
        betaConn = "";
        conflicts = 0;
        stagingDone = 0;
        stagingTotal = 0;
        fileCount = 0;
        fileBytes = 0;
        dirCount = 0;
        currentSide = null;
        inContentsBlock = false;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private static long sizeMultiplier(String unit) {
        if (unit == null) return 1;
        switch (unit.toUpperCase()) {
            case "K": return 1024L;
            case "M": return 1024L * 1024;
            case "G": return 1024L * 1024 * 1024;
            case "T": return 1024L * 1024 * 1024 * 1024;
            default:  return 1;
        }
    }
}
