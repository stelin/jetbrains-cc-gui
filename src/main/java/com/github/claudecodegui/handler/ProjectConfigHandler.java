package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.client.YunxiaoClient;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.util.FontConfigService;
import com.github.claudecodegui.util.ThemeConfigService;
import com.github.claudecodegui.provider.claude.BugAnalysisHandlerCallbacks;
import com.github.claudecodegui.provider.claude.BugAnalysisPrompt;
import com.github.claudecodegui.ui.detached.BugAnalysisFrame;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.github.claudecodegui.util.JBCefBrowserFactory;
import com.intellij.openapi.project.Project;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.concurrent.CompletableFuture;

/**
 * Handles project-level configuration: working directory, streaming, sandbox mode,
 * auto-open file, send shortcut, commit prompt, IDE theme, editor font config, and usage statistics.
 */
public class ProjectConfigHandler {

    private static final Logger LOG = Logger.getInstance(ProjectConfigHandler.class);
    static final String SEND_SHORTCUT_PROPERTY_KEY = "claude.code.send.shortcut";

    private final HandlerContext context;
    private final CodemossSettingsService settingsService;
    private final Gson gson = new Gson();

    public ProjectConfigHandler(HandlerContext context) {
        this.context = context;
        this.settingsService = context.getSettingsService();
    }

    public void handleGetWorkingDirectory() {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.updateWorkingDirectory", "{}"));
                return;
            }
            String customWorkingDir = new CodemossSettingsService().getCustomWorkingDirectory(projectPath);
            JsonObject response = new JsonObject();
            response.addProperty("projectPath", projectPath);
            response.addProperty("customWorkingDir", customWorkingDir != null ? customWorkingDir : "");
            String json = gson.toJson(response);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.updateWorkingDirectory", context.escapeJs(json)));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get working directory: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("获取工作目录配置失败: " + e.getMessage())));
        }
    }

    public void handleSetWorkingDirectory(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("无法获取项目路径")));
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String customWorkingDir = (json != null && json.has("customWorkingDir") && !json.get("customWorkingDir").isJsonNull())
                ? json.get("customWorkingDir").getAsString() : null;
            if (customWorkingDir != null && !customWorkingDir.trim().isEmpty()) {
                java.io.File workingDirFile = new java.io.File(customWorkingDir);
                if (!workingDirFile.isAbsolute()) {
                    workingDirFile = new java.io.File(projectPath, customWorkingDir);
                }
                if (!workingDirFile.exists() || !workingDirFile.isDirectory()) {
                    final String errorPath = workingDirFile.getAbsolutePath();
                    ApplicationManager.getApplication().invokeLater(() ->
                        context.callJavaScript("window.showError", context.escapeJs("工作目录不存在: " + errorPath)));
                    return;
                }
            }
            new CodemossSettingsService().setCustomWorkingDirectory(projectPath, customWorkingDir);
            LOG.info("[ProjectConfigHandler] Set custom working directory: " + customWorkingDir);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showSuccess", context.escapeJs("工作目录配置已保存")));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set working directory: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存工作目录配置失败: " + e.getMessage())));
        }
    }

    public void handleGetStreamingEnabled() {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject r = new JsonObject();
                    r.addProperty("streamingEnabled", true);
                    context.callJavaScript("window.updateStreamingEnabled", context.escapeJs(gson.toJson(r)));
                });
                return;
            }
            boolean streamingEnabled = new CodemossSettingsService().getStreamingEnabled(projectPath);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("streamingEnabled", streamingEnabled);
                context.callJavaScript("window.updateStreamingEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get streaming enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("streamingEnabled", true);
                context.callJavaScript("window.updateStreamingEnabled", context.escapeJs(gson.toJson(r)));
            });
        }
    }

    public void handleSetStreamingEnabled(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("无法获取项目路径")));
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean streamingEnabled = (json == null || !json.has("streamingEnabled") || json.get("streamingEnabled").isJsonNull())
                || json.get("streamingEnabled").getAsBoolean();
            new CodemossSettingsService().setStreamingEnabled(projectPath, streamingEnabled);
            LOG.info("[ProjectConfigHandler] Set streaming enabled: " + streamingEnabled);
            final boolean finalVal = streamingEnabled;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("streamingEnabled", finalVal);
                context.callJavaScript("window.updateStreamingEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set streaming enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存流式传输配置失败: " + e.getMessage())));
        }
    }

    public void handleGetCodexSandboxMode() {
        try {
            String projectPath = context.getProject().getBasePath();
            String sandboxMode = settingsService.getCodexSandboxMode(projectPath);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("sandboxMode", sandboxMode);
                context.callJavaScript("window.updateCodexSandboxMode", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get Codex sandbox mode: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("sandboxMode", "danger-full-access");
                context.callJavaScript("window.updateCodexSandboxMode", context.escapeJs(gson.toJson(r)));
            });
        }
    }

    public void handleSetCodexSandboxMode(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String sandboxMode = (json != null && json.has("sandboxMode") && !json.get("sandboxMode").isJsonNull())
                ? json.get("sandboxMode").getAsString() : "danger-full-access";
            settingsService.setCodexSandboxMode(projectPath, sandboxMode);
            LOG.info("[ProjectConfigHandler] Set Codex sandbox mode: " + sandboxMode);
            final String finalMode = sandboxMode;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("sandboxMode", finalMode);
                context.callJavaScript("window.updateCodexSandboxMode", context.escapeJs(gson.toJson(r)));
                context.callJavaScript("window.showSuccessI18n", "toast.saveSuccess");
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set Codex sandbox mode: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save Codex sandbox mode: " + e.getMessage())));
        }
    }

    public void handleGetAutoOpenFileEnabled() {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject r = new JsonObject();
                    r.addProperty("autoOpenFileEnabled", false);
                    context.callJavaScript("window.updateAutoOpenFileEnabled", context.escapeJs(gson.toJson(r)));
                });
                return;
            }
            boolean enabled = new CodemossSettingsService().getAutoOpenFileEnabled(projectPath);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("autoOpenFileEnabled", enabled);
                context.callJavaScript("window.updateAutoOpenFileEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get auto open file enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("autoOpenFileEnabled", false);
                context.callJavaScript("window.updateAutoOpenFileEnabled", context.escapeJs(gson.toJson(r)));
            });
        }
    }

    public void handleSetAutoOpenFileEnabled(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("无法获取项目路径")));
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = json != null && json.has("autoOpenFileEnabled") && !json.get("autoOpenFileEnabled").isJsonNull()
                && json.get("autoOpenFileEnabled").getAsBoolean();
            new CodemossSettingsService().setAutoOpenFileEnabled(projectPath, enabled);
            LOG.info("[ProjectConfigHandler] Set auto open file enabled: " + enabled);
            final boolean finalVal = enabled;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("autoOpenFileEnabled", finalVal);
                context.callJavaScript("window.updateAutoOpenFileEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set auto open file enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存自动打开文件配置失败: " + e.getMessage())));
        }
    }

    public void handleGetSendShortcut() {
        try {
            String sendShortcut = PropertiesComponent.getInstance().getValue(SEND_SHORTCUT_PROPERTY_KEY, "enter");
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("sendShortcut", sendShortcut);
                context.callJavaScript("window.updateSendShortcut", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get send shortcut: " + e.getMessage(), e);
        }
    }

    public void handleSetSendShortcut(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String sendShortcut = (json != null && json.has("sendShortcut") && !json.get("sendShortcut").isJsonNull())
                ? json.get("sendShortcut").getAsString() : "enter";
            if (!"enter".equals(sendShortcut) && !"cmdEnter".equals(sendShortcut)) {
                sendShortcut = "enter";
            }
            PropertiesComponent.getInstance().setValue(SEND_SHORTCUT_PROPERTY_KEY, sendShortcut);
            SendShortcutSync.sync(sendShortcut);
            LOG.info("[ProjectConfigHandler] Set send shortcut: " + sendShortcut);
            final String finalShortcut = sendShortcut;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("sendShortcut", finalShortcut);
                context.callJavaScript("window.updateSendShortcut", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set send shortcut: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存发送快捷键设置失败: " + e.getMessage())));
        }
    }

    public void handleGetCommitPrompt() {
        try {
            String commitPrompt = new CodemossSettingsService().getCommitPrompt();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("commitPrompt", commitPrompt);
                context.callJavaScript("window.updateCommitPrompt", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get commit prompt: " + e.getMessage(), e);
        }
    }

    public void handleSetCommitPrompt(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null || !json.has("prompt")) {
                LOG.warn("[ProjectConfigHandler] Invalid commit prompt request: missing prompt field");
                return;
            }
            String prompt = json.get("prompt").getAsString();
            if (prompt == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("提示词不能为空")));
                return;
            }
            prompt = prompt.trim();
            final int MAX_PROMPT_LENGTH = 10000;
            if (prompt.length() > MAX_PROMPT_LENGTH) {
                LOG.warn("[ProjectConfigHandler] Commit prompt too long: " + prompt.length() + " characters");
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("提示词长度不能超过 " + MAX_PROMPT_LENGTH + " 字符")));
                return;
            }
            final String validatedPrompt = prompt;
            new CodemossSettingsService().setCommitPrompt(validatedPrompt);
            LOG.info("[ProjectConfigHandler] Set commit prompt, length: " + validatedPrompt.length());
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("commitPrompt", validatedPrompt);
                r.addProperty("saved", true);
                context.callJavaScript("window.updateCommitPrompt", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set commit prompt: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存 Commit 提示词失败: " + e.getMessage())));
        }
    }

    public void handleGetIdeTheme() {
        try {
            String themeConfigJson = ThemeConfigService.getIdeThemeConfig().toString();
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onIdeThemeReceived", context.escapeJs(themeConfigJson)));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get IDE theme: " + e.getMessage(), e);
        }
    }

    public void handleGetEditorFontConfig() {
        try {
            String fontConfigJson = FontConfigService.getEditorFontConfig().toString();
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onEditorFontConfigReceived", context.escapeJs(fontConfigJson)));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get editor font config: " + e.getMessage(), e);
        }
    }

    public void handleGetUiFontConfig() {
        dispatchUiFontConfigUpdate();
    }

    public void handleSetUiFontConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String mode = json != null && json.has("mode") && !json.get("mode").isJsonNull()
                ? json.get("mode").getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
            String customFontPath = json != null && json.has("customFontPath") && !json.get("customFontPath").isJsonNull()
                ? json.get("customFontPath").getAsString()
                : null;

            if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(mode)) {
                FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(customFontPath);
                if (!validation.valid()) {
                    final String errorMessage = validation.errorMessage();
                    ApplicationManager.getApplication().invokeLater(() ->
                        context.callJavaScript("window.showError", context.escapeJs("Invalid font file: " + errorMessage)));
                    return;
                }
            }

            settingsService.setUiFontConfig(mode, customFontPath);
            dispatchUiFontConfigUpdate();
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set UI font config: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save font config: " + e.getMessage())));
        }
    }

    public void handleBrowseUiFontFile() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileChooserDescriptor descriptor = new FileChooserDescriptor(
                    true, false, false, false, false, false
                )
                    .withFileFilter(file -> {
                        String extension = file.getExtension();
                        return extension != null && (
                            extension.equalsIgnoreCase("ttf") ||
                            extension.equalsIgnoreCase("otf")
                        );
                    })
                    .withTitle("Select Font File")
                    .withDescription("Select a TTF or OTF font file");

                VirtualFile initialFile = null;
                try {
                    JsonObject persistedUiFont = settingsService.getUiFontConfig();
                    if (persistedUiFont.has("customFontPath") && !persistedUiFont.get("customFontPath").isJsonNull()) {
                        initialFile = LocalFileSystem.getInstance()
                            .findFileByPath(persistedUiFont.get("customFontPath").getAsString());
                    }
                } catch (Exception e) {
                    LOG.warn("[ProjectConfigHandler] Failed to resolve current custom font path: " + e.getMessage());
                }

                FileChooser.chooseFile(descriptor, context.getProject(), initialFile, file -> {
                    if (file == null) {
                        return;
                    }

                    String path = file.getPath();
                    FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(path);
                    if (!validation.valid()) {
                        context.callJavaScript("window.showError",
                            context.escapeJs("Invalid font file: " + validation.errorMessage()));
                        return;
                    }

                    try {
                        settingsService.setUiFontConfig(FontConfigService.UI_FONT_MODE_CUSTOM_FILE, path);
                        dispatchUiFontConfigUpdate();
                        context.callJavaScript("window.showSuccessI18n", context.escapeJs("toast.saveSuccess"));
                    } catch (Exception e) {
                        LOG.error("[ProjectConfigHandler] Failed to save selected font file: " + e.getMessage(), e);
                        context.callJavaScript("window.showError", context.escapeJs("Failed to save font config: " + e.getMessage()));
                    }
                });
            } catch (Exception e) {
                LOG.error("[ProjectConfigHandler] Failed to open font file chooser: " + e.getMessage(), e);
            }
        });
    }

    // ==================== AI Feature Toggle ====================

    public void handleGetCommitGenerationEnabled() {
        try {
            boolean enabled = settingsService.getCommitGenerationEnabled();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("commitGenerationEnabled", enabled);
                context.callJavaScript("window.updateCommitGenerationEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get commit generation enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("commitGenerationEnabled", true);
                context.callJavaScript("window.updateCommitGenerationEnabled", context.escapeJs(gson.toJson(r)));
            });
        }
    }

    public void handleSetCommitGenerationEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = json == null || !json.has("commitGenerationEnabled") || json.get("commitGenerationEnabled").isJsonNull()
                || json.get("commitGenerationEnabled").getAsBoolean();
            settingsService.setCommitGenerationEnabled(enabled);
            LOG.info("[ProjectConfigHandler] Set commit generation enabled: " + enabled);
            final boolean finalVal = enabled;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("commitGenerationEnabled", finalVal);
                context.callJavaScript("window.updateCommitGenerationEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set commit generation enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存 AI 生成 Commit 配置失败")));
        }
    }

    public void handleGetStatusBarWidgetEnabled() {
        try {
            boolean enabled = settingsService.getStatusBarWidgetEnabled();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("statusBarWidgetEnabled", enabled);
                context.callJavaScript("window.updateStatusBarWidgetEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get status bar widget enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("statusBarWidgetEnabled", true);
                context.callJavaScript("window.updateStatusBarWidgetEnabled", context.escapeJs(gson.toJson(r)));
            });
        }
    }

    public void handleSetStatusBarWidgetEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = json == null || !json.has("statusBarWidgetEnabled") || json.get("statusBarWidgetEnabled").isJsonNull()
                || json.get("statusBarWidgetEnabled").getAsBoolean();
            settingsService.setStatusBarWidgetEnabled(enabled);
            LOG.info("[ProjectConfigHandler] Set status bar widget enabled: " + enabled);
            final boolean finalVal = enabled;
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("statusBarWidgetEnabled", finalVal);
                context.callJavaScript("window.updateStatusBarWidgetEnabled", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set status bar widget enabled: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存状态栏配置失败")));
        }
    }

    // ──────────────── Auto Reload from Disk (remote mode only) ────────────────

    public void handleGetAutoReload() {
        try {
            com.github.claudecodegui.settings.AutoReloadSettings s =
                    com.github.claudecodegui.settings.AutoReloadSettings.getInstance();
            boolean enabled = s.isEnabled();
            long debounceMs = s.getDebounceMs();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("enabled", enabled);
                r.addProperty("debounceMs", debounceMs);
                context.callJavaScript("window.updateAutoReload", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleGetAutoReload failed: " + e.getMessage(), e);
        }
    }

    public void handleSetAutoReload(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            com.github.claudecodegui.settings.AutoReloadSettings s =
                    com.github.claudecodegui.settings.AutoReloadSettings.getInstance();

            if (json != null && json.has("enabled") && !json.get("enabled").isJsonNull()) {
                s.setEnabled(json.get("enabled").getAsBoolean());
            }
            if (json != null && json.has("debounceMs") && !json.get("debounceMs").isJsonNull()) {
                s.setDebounceMs(json.get("debounceMs").getAsLong());
            }
            LOG.info("[ProjectConfigHandler] Set autoReload enabled=" + s.isEnabled()
                    + " debounceMs=" + s.getDebounceMs());

            final boolean enabled = s.isEnabled();
            final long debounceMs = s.getDebounceMs();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("enabled", enabled);
                r.addProperty("debounceMs", debounceMs);
                context.callJavaScript("window.updateAutoReload", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleSetAutoReload failed: " + e.getMessage(), e);
        }
    }

    // ──────────────── Remote Mode (ai-bridge-server) ────────────────

    public void handleGetRemoteMode() {
        try {
            String mode = settingsService.getDaemonMode();
            String url = settingsService.getRemoteServerUrl();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("daemonMode", mode);
                r.addProperty("remoteServerUrl", url);
                context.callJavaScript("window.updateRemoteMode", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleGetRemoteMode failed: " + e.getMessage(), e);
        }
    }

    public void handleSetRemoteMode(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String mode = (json != null && json.has("daemonMode") && !json.get("daemonMode").isJsonNull())
                    ? json.get("daemonMode").getAsString() : "local";
            String url = (json != null && json.has("remoteServerUrl") && !json.get("remoteServerUrl").isJsonNull())
                    ? json.get("remoteServerUrl").getAsString() : settingsService.getRemoteServerUrl();

            String oldMode = settingsService.getDaemonMode();
            String oldUrl = settingsService.getRemoteServerUrl();
            boolean changed = !oldMode.equals(mode) || !java.util.Objects.equals(oldUrl, url);

            settingsService.setDaemonMode(mode);
            settingsService.setRemoteServerUrl(url);
            LOG.info("[ProjectConfigHandler] Set daemonMode=" + mode + " remoteServerUrl=" + url
                    + " (changed=" + changed + ")");

            // Rebuild path mapper since remote-mode toggle affects which mapper is active.
            try {
                com.github.claudecodegui.path.PathMapperHolder.getInstance(context.getProject()).rebuild();
            } catch (Exception ignore) {}

            // If mode or URL changed, tear down current Claude daemon so the
            // next request lazily rebuilds via the new code path. Codex spawns
            // per-request and doesn't maintain a persistent daemon, so no
            // shutdown is needed for it.
            if (changed) {
                CompletableFuture.runAsync(() -> {
                    try {
                        if (context.getClaudeSDKBridge() != null) {
                            context.getClaudeSDKBridge().shutdownDaemon();
                            LOG.info("[ProjectConfigHandler] ClaudeSDKBridge daemon shut down for mode switch");
                        }
                    } catch (Exception se) {
                        LOG.warn("[ProjectConfigHandler] ClaudeSDKBridge shutdown failed: " + se.getMessage());
                    }
                });
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("daemonMode", settingsService.getDaemonMode());
                r.addProperty("remoteServerUrl", settingsService.getRemoteServerUrl());
                r.addProperty("rebuilt", changed);
                context.callJavaScript("window.updateRemoteMode", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleSetRemoteMode failed: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存远程模式配置失败")));
        }
    }

    public void handleTestRemoteConnection(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String url;
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                url = (json != null && json.has("remoteServerUrl") && !json.get("remoteServerUrl").isJsonNull())
                        ? json.get("remoteServerUrl").getAsString().trim() : settingsService.getRemoteServerUrl();
                if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5)).build();
                java.net.http.HttpResponse<String> resp = client.send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(url + "/health"))
                                .timeout(java.time.Duration.ofSeconds(5))
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString()
                );
                boolean ok = resp.statusCode() == 200;
                r.addProperty("ok", ok);
                r.addProperty("status", resp.statusCode());
                r.addProperty("body", resp.body() == null ? "" : resp.body());
                if (ok) {
                    try {
                        java.net.http.HttpResponse<String> v = client.send(
                                java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(url + "/version"))
                                        .timeout(java.time.Duration.ofSeconds(5))
                                        .GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString()
                        );
                        r.addProperty("version", v.body());
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.updateRemoteConnectionTest", context.escapeJs(gson.toJson(r))));
        });
    }

    // ──────────────── Yunxiao (Alibaba Cloud DevOps) Bug Integration ────────────────

    /** Read the saved yunxiao config and push it to the settings page. */
    public void handleGetYunxiaoConfig() {
        try {
            String token = settingsService.getYunxiaoToken();
            String orgId = settingsService.getYunxiaoOrgId();
            String domain = settingsService.getYunxiaoDomain();
            String userId = settingsService.getYunxiaoUserId();
            String defaultProjectId = settingsService.getYunxiaoDefaultProjectId();
            String defaultProjectName = settingsService.getYunxiaoDefaultProjectName();
            String appendPrompt = settingsService.getYunxiaoAppendPrompt();
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("token", token);
                r.addProperty("organizationId", orgId);
                r.addProperty("domain", domain);
                r.addProperty("userId", userId);
                r.addProperty("defaultProjectId", defaultProjectId);
                r.addProperty("defaultProjectName", defaultProjectName);
                r.addProperty("appendPrompt", appendPrompt);
                context.callJavaScript("window.updateYunxiaoConfig", context.escapeJs(gson.toJson(r)));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleGetYunxiaoConfig failed: " + e.getMessage(), e);
        }
    }

    /** Persist token/organizationId/domain, then echo the normalised state back. */
    public void handleSetYunxiaoConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String token = (json != null && json.has("token") && !json.get("token").isJsonNull())
                    ? json.get("token").getAsString() : settingsService.getYunxiaoToken();
            String orgId = (json != null && json.has("organizationId") && !json.get("organizationId").isJsonNull())
                    ? json.get("organizationId").getAsString() : settingsService.getYunxiaoOrgId();
            String domain = (json != null && json.has("domain") && !json.get("domain").isJsonNull())
                    ? json.get("domain").getAsString() : settingsService.getYunxiaoDomain();

            // setYunxiaoToken/setYunxiaoOrgId clear derived state (userId, default project)
            // only when the value actually changes — a plain re-save keeps them.
            settingsService.setYunxiaoToken(token);
            settingsService.setYunxiaoOrgId(orgId);
            settingsService.setYunxiaoDomain(domain);
            // Default project is persisted AFTER the credential setters so it survives an
            // unchanged-credential save (and a credential change correctly drops it first).
            if (json != null && json.has("defaultProjectId") && !json.get("defaultProjectId").isJsonNull()) {
                settingsService.setYunxiaoDefaultProjectId(json.get("defaultProjectId").getAsString());
                String pName = (json.has("defaultProjectName") && !json.get("defaultProjectName").isJsonNull())
                        ? json.get("defaultProjectName").getAsString() : "";
                settingsService.setYunxiaoDefaultProjectName(pName);
            }
            // Append prompt is a preference (not credential-derived); persist whenever present.
            if (json != null && json.has("appendPrompt") && !json.get("appendPrompt").isJsonNull()) {
                settingsService.setYunxiaoAppendPrompt(json.get("appendPrompt").getAsString());
            }
            LOG.info("[ProjectConfigHandler] Saved yunxiao config orgId=" + settingsService.getYunxiaoOrgId()
                    + " domain=" + settingsService.getYunxiaoDomain()
                    + " tokenLen=" + (token == null ? 0 : token.trim().length())
                    + " defaultProjectId=" + settingsService.getYunxiaoDefaultProjectId());

            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject r = new JsonObject();
                r.addProperty("token", settingsService.getYunxiaoToken());
                r.addProperty("organizationId", settingsService.getYunxiaoOrgId());
                r.addProperty("domain", settingsService.getYunxiaoDomain());
                // userId survives an unchanged-credential save; hidden only after a real change.
                r.addProperty("userId", settingsService.getYunxiaoUserId());
                r.addProperty("defaultProjectId", settingsService.getYunxiaoDefaultProjectId());
                r.addProperty("defaultProjectName", settingsService.getYunxiaoDefaultProjectName());
                r.addProperty("appendPrompt", settingsService.getYunxiaoAppendPrompt());
                context.callJavaScript("window.updateYunxiaoConfig", context.escapeJs(gson.toJson(r)));
                context.callJavaScript("window.showSuccessI18n", "toast.saveSuccess");
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleSetYunxiaoConfig failed: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存云效配置失败")));
        }
    }

    /**
     * Validate the entered credentials by resolving the current user id (§4).
     * {@link YunxiaoClient#getCurrentUserId()} reads token/orgId/domain from
     * settings, and §2.3 specifies the userId cache is primed at「测试连接」time —
     * so we persist the entered values first (which also drops any stale cached
     * id), then fetch. Success returns the numeric id; failure returns the error.
     */
    public void handleYunxiaoTestConnection(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                if (json != null) {
                    if (json.has("token") && !json.get("token").isJsonNull()) {
                        settingsService.setYunxiaoToken(json.get("token").getAsString());
                    }
                    if (json.has("organizationId") && !json.get("organizationId").isJsonNull()) {
                        settingsService.setYunxiaoOrgId(json.get("organizationId").getAsString());
                    }
                    if (json.has("domain") && !json.get("domain").isJsonNull()) {
                        settingsService.setYunxiaoDomain(json.get("domain").getAsString());
                    }
                }
                String userId = new YunxiaoClient(settingsService).getCurrentUserId();
                r.addProperty("ok", true);
                r.addProperty("userId", userId);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoTestResult", context.escapeJs(gson.toJson(r))));
        });
    }

    /** Load the project list for the缺陷 dropdown (需求2). */
    public void handleLoadYunxiaoProjects() {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            try {
                java.util.List<JsonObject> projects = new YunxiaoClient(settingsService).listProjects();
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (JsonObject p : projects) arr.add(p);
                r.addProperty("ok", true);
                r.add("projects", arr);
                // Carry the configured default so the list / settings dropdown can auto-select it.
                r.addProperty("defaultProjectId", settingsService.getYunxiaoDefaultProjectId());
                // Carry the append prompt so the list's「建会话」/「建监督者」can suffix it.
                r.addProperty("appendPrompt", settingsService.getYunxiaoAppendPrompt());
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoProjects", context.escapeJs(gson.toJson(r))));
        });
    }

    /**
     * Search缺陷 assigned to the current user within a project (需求2). The
     * response echoes the requested {@code page} + {@code hasMore} so the
     * frontend can page1=replace / page>1=append monotonically.
     */
    public void handleLoadYunxiaoBugs(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            int page = 1;
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                String projectId = (json != null && json.has("projectId") && !json.get("projectId").isJsonNull())
                        ? json.get("projectId").getAsString() : "";
                page = (json != null && json.has("page") && !json.get("page").isJsonNull())
                        ? json.get("page").getAsInt() : 1;
                int perPage = (json != null && json.has("perPage") && !json.get("perPage").isJsonNull())
                        ? json.get("perPage").getAsInt() : 50;

                YunxiaoClient.BugPage bugPage = new YunxiaoClient(settingsService).searchMyBugs(projectId, page, perPage);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (JsonObject b : bugPage.bugs) arr.add(b);
                r.addProperty("ok", true);
                r.addProperty("page", bugPage.page);
                r.addProperty("hasMore", bugPage.hasMore);
                r.add("bugs", arr);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("page", page);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoBugs", context.escapeJs(gson.toJson(r))));
        });
    }

    /**
     * Load a single bug's detail for the「查看详情」modal: basic info + description
     * (images inlined) + attachments. Replies to {@code window.onYunxiaoBugDetail}.
     */
    public void handleLoadYunxiaoBugDetail(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String bugId = "";
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                bugId = (json != null && json.has("bugId") && !json.get("bugId").isJsonNull())
                        ? json.get("bugId").getAsString() : "";
                JsonObject detail = new YunxiaoClient(settingsService).getBugDetail(bugId);
                r.addProperty("ok", true);
                r.addProperty("bugId", bugId);
                r.add("detail", detail);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("bugId", bugId);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoBugDetail", context.escapeJs(gson.toJson(r))));
        });
    }

    /**
     * Resolve a fresh download URL for a bug attachment and hand it back to the
     * frontend (which opens it in the system browser). Replies to
     * {@code window.onYunxiaoAttachmentUrl}.
     */
    public void handleDownloadYunxiaoAttachment(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                String bugId = (json != null && json.has("bugId") && !json.get("bugId").isJsonNull())
                        ? json.get("bugId").getAsString() : "";
                String attachmentId = (json != null && json.has("attachmentId") && !json.get("attachmentId").isJsonNull())
                        ? json.get("attachmentId").getAsString() : "";
                String fileName = (json != null && json.has("name") && !json.get("name").isJsonNull())
                        ? json.get("name").getAsString() : "";
                String url = new YunxiaoClient(settingsService).getAttachmentDownloadUrl(bugId, attachmentId);
                r.addProperty("ok", true);
                r.addProperty("url", url);
                r.addProperty("name", fileName);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoAttachmentUrl", context.escapeJs(gson.toJson(r))));
        });
    }

    /** Load the selectable target statuses for a bug (workitem-type workflow). Replies to {@code window.onYunxiaoStatuses}. */
    public void handleLoadYunxiaoStatuses(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String bugId = "";
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                bugId = strOf(json, "bugId");
                String projectId = strOf(json, "projectId");
                String workItemTypeId = strOf(json, "workItemTypeId");
                String currentStatusId = strOf(json, "currentStatusId");
                java.util.List<JsonObject> statuses = new YunxiaoClient(settingsService)
                        .getWorkItemStatuses(projectId, workItemTypeId, currentStatusId);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (JsonObject s : statuses) arr.add(s);
                r.addProperty("ok", true);
                r.addProperty("bugId", bugId);
                r.add("statuses", arr);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("bugId", bugId);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoStatuses", context.escapeJs(gson.toJson(r))));
        });
    }

    /** Change a bug's status. Replies to {@code window.onYunxiaoStatusUpdated} with the applied status. */
    public void handleUpdateYunxiaoStatus(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String bugId = "";
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                bugId = strOf(json, "bugId");
                String statusId = strOf(json, "statusId");
                String statusName = strOf(json, "statusName");
                new YunxiaoClient(settingsService).updateWorkItemStatus(bugId, statusId);
                r.addProperty("ok", true);
                r.addProperty("bugId", bugId);
                r.addProperty("statusId", statusId);
                r.addProperty("statusName", statusName);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("bugId", bugId);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoStatusUpdated", context.escapeJs(gson.toJson(r))));
        });
    }

    /**
     * Search org members. Shared by the comment「@」picker (callback {@code onYunxiaoMembers})
     * and the list's 改负责人 picker (callback {@code onYunxiaoAssigneeMembers}) — the caller
     * names the callback so the two pickers don't clobber a single shared global. Callback is
     * whitelisted to those two names to avoid arbitrary {@code window.*} invocation. Echoes
     * query for race-safety.
     */
    public void handleLoadYunxiaoMembers(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            JsonObject json = null;
            try {
                json = gson.fromJson(content, JsonObject.class);
            } catch (Exception ignore) {
                // malformed payload → treat as empty query, default callback
            }
            String query = strOf(json, "query");
            final String cb = "onYunxiaoAssigneeMembers".equals(strOf(json, "callback"))
                    ? "onYunxiaoAssigneeMembers" : "onYunxiaoMembers";
            try {
                java.util.List<JsonObject> members = new YunxiaoClient(settingsService).searchMembers(query, 1, 100);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (JsonObject m : members) arr.add(m);
                r.addProperty("ok", true);
                r.addProperty("query", query);
                r.add("members", arr);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("query", query);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window." + cb, context.escapeJs(gson.toJson(r))));
        });
    }

    /** Reassign a bug's 负责人 (UpdateWorkItem assignedTo). Replies to {@code window.onYunxiaoAssigneeUpdated}. */
    public void handleUpdateYunxiaoAssignee(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String bugId = "";
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                bugId = strOf(json, "bugId");
                String userId = strOf(json, "userId");
                String name = strOf(json, "name");
                new YunxiaoClient(settingsService).updateWorkItemAssignee(bugId, userId);
                r.addProperty("ok", true);
                r.addProperty("bugId", bugId);
                r.addProperty("userId", userId);
                r.addProperty("name", name);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("bugId", bugId);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoAssigneeUpdated", context.escapeJs(gson.toJson(r))));
        });
    }

    private static String strOf(JsonObject json, String key) {
        return (json != null && json.has(key) && !json.get(key).isJsonNull()) ? json.get(key).getAsString() : "";
    }

    /** Post a comment on a bug. Replies to {@code window.onYunxiaoCommentAdded}. */
    public void handleSubmitYunxiaoComment(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            String bugId = "";
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                bugId = strOf(json, "bugId");
                String text = strOf(json, "content");
                new YunxiaoClient(settingsService).createComment(bugId, text);
                r.addProperty("ok", true);
                r.addProperty("bugId", bugId);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("bugId", bugId);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoCommentAdded", context.escapeJs(gson.toJson(r))));
        });
    }

    /** Upload a pasted image and return a markdown embed tag. Replies to {@code window.onYunxiaoCommentImage}. */
    public void handleUploadYunxiaoCommentImage(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject r = new JsonObject();
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                String bugId = strOf(json, "bugId");
                String fileName = strOf(json, "fileName");
                String contentType = strOf(json, "contentType");
                String dataBase64 = strOf(json, "dataBase64");
                byte[] data = java.util.Base64.getDecoder().decode(dataBase64);
                String markdown = new YunxiaoClient(settingsService)
                        .uploadCommentImage(bugId, data, fileName, contentType);
                r.addProperty("ok", true);
                r.addProperty("markdown", markdown);
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onYunxiaoCommentImage", context.escapeJs(gson.toJson(r))));
        });
    }

    // ──────────────── Bug AI Analysis ────────────────

    /**
     * Launch a headless bug analysis scratch session (design §2.2 / §3).
     * Parses {projectId, bugs, model, reasoningEffort} from the webview payload,
     * builds the prompt, and delegates to {@code ClaudeSDKBridge.analyzeBugsHeadless}.
     * Progress and results are pushed back via {@link BugAnalysisHandlerCallbacks}.
     */
    public void handleAnalyzeBugs(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject req = gson.fromJson(content, JsonObject.class);
                String projectId = strOf(req, "projectId");
                JsonArray bugs = (req != null && req.has("bugs") && req.get("bugs").isJsonArray())
                        ? req.getAsJsonArray("bugs") : new JsonArray();
                String model = strOf(req, "model");
                String reasoning = strOf(req, "reasoningEffort");
                // 最大并发子智能体数(选项 3/4/5;默认 3);防御性夹紧到 [1,5]。
                int concurrency = 3;
                try {
                    if (req != null && req.has("concurrency") && !req.get("concurrency").isJsonNull()) {
                        concurrency = Math.max(1, Math.min(5, req.get("concurrency").getAsInt()));
                    }
                } catch (Exception ignored) {
                    // keep default 3
                }

                BugAnalysisHandlerCallbacks cb =
                        new BugAnalysisHandlerCallbacks(context, projectId, bugs, model, reasoning);
                if (context.getClaudeSDKBridge() == null) {
                    cb.onTransportError("Claude bridge 不可用");
                    return;
                }

                String prompt = BugAnalysisPrompt.build(bugs, concurrency);
                String cwd = context.getProject() != null ? context.getProject().getBasePath() : null;
                context.getClaudeSDKBridge()
                       .analyzeBugsHeadless(projectId, prompt, bugs, model, reasoning, cwd, cb);
            } catch (Exception e) {
                LOG.warn("[BugAnalysis] handleAnalyzeBugs failed: " + e.getMessage());
            }
        });
    }

    /**
     * Cancel an in-flight bug analysis (design §2.2 / §8).
     * Sets the canceled flag on the active handle; if it is the current active daemon
     * request, also sends abort to interrupt it early.
     */
    public void handleCancelBugAnalysis(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String projectId = strOf(json, "projectId");
            if (context.getClaudeSDKBridge() != null) {
                context.getClaudeSDKBridge().cancelBugAnalysis(projectId);
            }
        } catch (Exception e) {
            LOG.warn("[BugAnalysis] handleCancelBugAnalysis failed: " + e.getMessage());
        }
    }

    /**
     * Open the standalone, detached「AI 分析」window (脱离 IDE 的 JFrame, 自带 webview).
     * The window runs an isolated analysis session and is ephemeral — closing it cancels
     * the analysis and discards all data. 建监督者/建会话 inside it are forwarded to the
     * MAIN tool window so tabs open in the IDE (跨窗转发). content = the raw webview payload
     * {projectId, bugs, model, reasoning, appendPrompt}, injected as the window's boot data.
     */
    public void handleOpenBugAnalysisWindow(String content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Project project = context.getProject();
                if (project == null) {
                    LOG.warn("[BugAnalysis] open window: no project");
                    return;
                }
                if (!JBCefBrowserFactory.isJcefSupported()) {
                    LOG.warn("[BugAnalysis] open window: JCEF not supported");
                    return;
                }
                ClaudeChatWindow mainWindow = ClaudeSDKToolWindow.getChatWindow(project);
                BugAnalysisFrame frame = new BugAnalysisFrame(
                        project, mainWindow,
                        context.getClaudeSDKBridge(), context.getCodexSDKBridge(),
                        settingsService, content);
                frame.open();
            } catch (Exception e) {
                LOG.warn("[BugAnalysis] handleOpenBugAnalysisWindow failed: " + e.getMessage());
            }
        });
    }

    // ==================== Path Mapping ====================

    public void handleGetPathMapping() {
        try {
            String base = context.getProject().getBasePath();
            com.github.claudecodegui.settings.PathMappingConfig cfg = base != null
                    ? settingsService.getPathMappingConfig(base)
                    : com.github.claudecodegui.settings.PathMappingConfig.disabled();
            JsonObject r = new JsonObject();
            r.addProperty("enabled", cfg.enabled);
            r.addProperty("localOs",  cfg.localOs  != null ? cfg.localOs.name()  : "");
            r.addProperty("localRoot",  cfg.localRoot  != null ? cfg.localRoot  : "");
            r.addProperty("remoteOs", cfg.remoteOs != null ? cfg.remoteOs.name() : "");
            r.addProperty("remoteRoot", cfg.remoteRoot != null ? cfg.remoteRoot : "");
            String json = gson.toJson(r);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.updatePathMapping", context.escapeJs(json)));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleGetPathMapping failed: " + e.getMessage(), e);
        }
    }

    public void handleSetPathMapping(String content) {
        try {
            String base = context.getProject().getBasePath();
            if (base == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("请先打开一个项目再配置路径映射")));
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            com.github.claudecodegui.settings.PathMappingConfig cfg =
                    new com.github.claudecodegui.settings.PathMappingConfig();
            cfg.enabled = json != null && json.has("enabled") && json.get("enabled").getAsBoolean();
            cfg.localOs  = parseOs(optStr(json, "localOs"));
            cfg.localRoot  = optStr(json, "localRoot");
            cfg.remoteOs = parseOs(optStr(json, "remoteOs"));
            cfg.remoteRoot = optStr(json, "remoteRoot");
            settingsService.setPathMappingConfig(base, cfg);
            LOG.info("[ProjectConfigHandler] Saved path mapping for project=" + base
                    + " enabled=" + cfg.enabled);

            // Rebuild mapper + force daemon restart so the next message uses the new mapping.
            com.github.claudecodegui.path.PathMapperHolder.getInstance(context.getProject()).rebuild();
            CompletableFuture.runAsync(() -> {
                try {
                    if (context.getClaudeSDKBridge() != null) {
                        context.getClaudeSDKBridge().shutdownDaemon();
                        LOG.info("[ProjectConfigHandler] Daemon restarted for path-mapping change");
                    }
                } catch (Exception se) {
                    LOG.warn("[ProjectConfigHandler] Daemon shutdown failed: " + se.getMessage());
                }
            });

            // Echo back the saved state so the UI re-syncs.
            handleGetPathMapping();
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleSetPathMapping failed: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存路径映射失败")));
        }
    }

    public void handleGetPathMisses() {
        try {
            com.github.claudecodegui.path.PathMissTracker tracker =
                    com.github.claudecodegui.path.PathMissTracker.getInstance(context.getProject());
            JsonObject r = new JsonObject();
            r.addProperty("outboundCount", tracker.outboundCount());
            r.addProperty("inboundCount",  tracker.inboundCount());
            com.google.gson.JsonArray samples = new com.google.gson.JsonArray();
            for (String s : tracker.outboundSamples(50)) samples.add(s);
            r.add("outboundSamples", samples);
            String json = gson.toJson(r);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.updatePathMisses", context.escapeJs(json)));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleGetPathMisses failed: " + e.getMessage(), e);
        }
    }

    public void handleClearPathMisses() {
        try {
            com.github.claudecodegui.path.PathMissTracker.getInstance(context.getProject()).clear();
            handleGetPathMisses();
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] handleClearPathMisses failed: " + e.getMessage(), e);
        }
    }

    private static String optStr(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static com.github.claudecodegui.path.OsType parseOs(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return com.github.claudecodegui.path.OsType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private void dispatchUiFontConfigUpdate() {
        try {
            String uiFontConfigJson = FontConfigService.getResolvedUiFontConfigJson(settingsService);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.onUiFontConfigReceived", context.escapeJs(uiFontConfigJson));
                context.callJavaScript("window.applyUiFontConfig", context.escapeJs(uiFontConfigJson));
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to dispatch UI font config: " + e.getMessage(), e);
        }
    }

    /** Get usage statistics. Supports both Claude and Codex providers. */
    public void handleGetUsageStatistics(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                String projectPath = "all";
                String provider = "claude";
                long cutoffTime = 0;
                if (content != null && !content.isEmpty() && !content.equals("{}")) {
                    try {
                        JsonObject json = gson.fromJson(content, JsonObject.class);
                        if (json.has("scope")) {
                            projectPath = "current".equals(json.get("scope").getAsString())
                                ? context.getProject().getBasePath() : "all";
                        }
                        if (json.has("provider")) {
                            provider = json.get("provider").getAsString();
                        }
                        if (json.has("dateRange")) {
                            String dateRange = json.get("dateRange").getAsString();
                            long now = System.currentTimeMillis();
                            if ("7d".equals(dateRange)) cutoffTime = now - 7L * 24 * 60 * 60 * 1000;
                            else if ("30d".equals(dateRange)) cutoffTime = now - 30L * 24 * 60 * 60 * 1000;
                        }
                    } catch (Exception e) {
                        projectPath = "current".equals(content) ? context.getProject().getBasePath() : content;
                    }
                }
                String json;
                if ("codex".equals(provider)) {
                    CodexHistoryReader reader = new CodexHistoryReader();
                    CodexHistoryReader.ProjectStatistics stats = reader.getProjectStatistics(projectPath, cutoffTime);
                    LOG.info("[ProjectConfigHandler] Codex statistics - sessions: " + stats.totalSessions +
                             ", cost: " + stats.estimatedCost + ", total tokens: " + stats.totalUsage.totalTokens);
                    json = gson.toJson(stats);
                } else {
                    ClaudeHistoryReader reader = new ClaudeHistoryReader();
                    json = gson.toJson(reader.getProjectStatistics(projectPath, cutoffTime));
                }
                final String statsJson = json;
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.updateUsageStatistics", context.escapeJs(statsJson)));
            } catch (Exception e) {
                LOG.error("[ProjectConfigHandler] Failed to get usage statistics: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("获取统计数据失败: " + e.getMessage())));
            }
        });
    }
}
