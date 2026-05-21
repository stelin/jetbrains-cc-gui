package com.github.claudecodegui.remotesync.model;

import com.google.gson.JsonObject;

public final class MutagenSdkStatus {

    private final boolean installed;
    private final String installedVersion;
    private final String installedPath;
    private final String targetVersion;
    private final boolean downloading;
    private final long progress;
    private final long total;
    private final String error;
    private final String downloadUrl;

    private MutagenSdkStatus(boolean installed, String installedVersion, String installedPath,
                             String targetVersion, boolean downloading, long progress, long total,
                             String error, String downloadUrl) {
        this.installed = installed;
        this.installedVersion = installedVersion;
        this.installedPath = installedPath;
        this.targetVersion = targetVersion;
        this.downloading = downloading;
        this.progress = progress;
        this.total = total;
        this.error = error;
        this.downloadUrl = downloadUrl;
    }

    public static MutagenSdkStatus installed(String version, String path, String targetVersion) {
        return new MutagenSdkStatus(true, version, path, targetVersion, false, 0, 0, null, null);
    }

    public static MutagenSdkStatus missing(String targetVersion, String downloadUrl) {
        return new MutagenSdkStatus(false, null, null, targetVersion, false, 0, 0, null, downloadUrl);
    }

    public static MutagenSdkStatus downloading(long progress, long total, String targetVersion, String downloadUrl) {
        return new MutagenSdkStatus(false, null, null, targetVersion, true, progress, total, null, downloadUrl);
    }

    public static MutagenSdkStatus error(String error, String targetVersion, String downloadUrl) {
        return new MutagenSdkStatus(false, null, null, targetVersion, false, 0, 0, error, downloadUrl);
    }

    public boolean isInstalled() { return installed; }
    public String getInstalledVersion() { return installedVersion; }
    public String getInstalledPath() { return installedPath; }
    public String getTargetVersion() { return targetVersion; }
    public boolean isDownloading() { return downloading; }
    public long getProgress() { return progress; }
    public long getTotal() { return total; }
    public String getError() { return error; }
    public String getDownloadUrl() { return downloadUrl; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("installed", installed);
        if (installedVersion != null) o.addProperty("installedVersion", installedVersion);
        if (installedPath != null) o.addProperty("installedPath", installedPath);
        o.addProperty("targetVersion", targetVersion);
        o.addProperty("downloading", downloading);
        o.addProperty("progress", progress);
        o.addProperty("total", total);
        if (error != null) o.addProperty("error", error);
        if (downloadUrl != null) o.addProperty("downloadUrl", downloadUrl);
        return o;
    }
}
