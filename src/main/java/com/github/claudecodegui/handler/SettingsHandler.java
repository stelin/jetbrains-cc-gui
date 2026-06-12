package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.provider.ModelProviderHandler;

import com.github.claudecodegui.util.ThemeConfigService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Settings and usage statistics message handler.
 * Delegates to focused sub-handlers for each concern.
 */
public class SettingsHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SettingsHandler.class);

    private final InputHistoryHandler inputHistoryHandler;
    private final SoundSettingsHandler soundSettingsHandler;
    private final UsagePushService usagePushService;
    private final PermissionModeHandler permissionModeHandler;
    private final ModelProviderHandler modelProviderHandler;
    private final NodePathHandler nodePathHandler;
    private final ProjectConfigHandler projectConfigHandler;

    private static final String[] SUPPORTED_TYPES = {
        "get_mode",
        "set_mode",
        "set_model",
        "set_provider",
        "set_reasoning_effort",
        "get_node_path",
        "set_node_path",
        "get_usage_statistics",
        "get_working_directory",
        "set_working_directory",
        "get_editor_font_config",
        "get_ui_font_config",
        "set_ui_font_config",
        "browse_ui_font_file",
        "get_streaming_enabled",
        "set_streaming_enabled",
        "get_codex_sandbox_mode",
        "set_codex_sandbox_mode",
        "get_send_shortcut",
        "set_send_shortcut",
        "get_auto_open_file_enabled",
        "set_auto_open_file_enabled",
        "get_commit_generation_enabled",
        "set_commit_generation_enabled",
        "get_status_bar_widget_enabled",
        "set_status_bar_widget_enabled",
        "get_ide_theme",
        "get_commit_prompt",
        "set_commit_prompt",
        "get_input_history",
        "record_input_history",
        "delete_input_history_item",
        "clear_input_history",
        // Sound notification configuration
        "get_sound_notification_config",
        "set_sound_notification_enabled",
        "set_sound_only_when_unfocused",
        "set_selected_sound",
        "set_custom_sound_path",
        "test_sound",
        "browse_sound_file",
        // Remote mode (ai-bridge-server)
        "get_remote_mode",
        "set_remote_mode",
        "test_remote_connection",
        // Yunxiao (Alibaba Cloud DevOps) bug integration
        "get_yunxiao_config",
        "set_yunxiao_config",
        "yunxiao_test_connection",
        "load_yunxiao_projects",
        "load_yunxiao_bugs",
        "load_yunxiao_bug_detail",
        "download_yunxiao_attachment",
        "load_yunxiao_statuses",
        "update_yunxiao_status",
        "load_yunxiao_members",
        "update_yunxiao_assignee",
        "submit_yunxiao_comment",
        "upload_yunxiao_comment_image",
        // Path mapping (per-project, remote mode only)
        "get_path_mapping",
        "set_path_mapping",
        "get_path_misses",
        "clear_path_misses",
        // Auto reload from disk on AI file changes (remote mode only)
        "get_auto_reload",
        "set_auto_reload"
    };

    public SettingsHandler(HandlerContext context) {
        super(context);
        this.inputHistoryHandler = new InputHistoryHandler(context);
        this.soundSettingsHandler = new SoundSettingsHandler(context);
        this.usagePushService = new UsagePushService(context);
        this.permissionModeHandler = new PermissionModeHandler(context);
        this.modelProviderHandler = new ModelProviderHandler(context, usagePushService);
        this.nodePathHandler = new NodePathHandler(context);
        this.projectConfigHandler = new ProjectConfigHandler(context);
        // Register theme change listener to automatically notify frontend when IDE theme changes
        registerThemeChangeListener();
    }

    /**
     * Register theme change listener.
     */
    private void registerThemeChangeListener() {
        ThemeConfigService.registerThemeChangeListener(themeConfig -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.onIdeThemeChanged", escapeJs(themeConfig.toString()));
            });
        });
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            // Permission mode
            case "get_mode":
                permissionModeHandler.handleGetMode();
                return true;
            case "set_mode":
                permissionModeHandler.handleSetMode(content);
                return true;
            // Model and provider
            case "set_model":
                modelProviderHandler.handleSetModel(content);
                return true;
            case "set_provider":
                modelProviderHandler.handleSetProvider(content);
                return true;
            case "set_reasoning_effort":
                modelProviderHandler.handleSetReasoningEffort(content);
                return true;
            // Node path
            case "get_node_path":
                nodePathHandler.handleGetNodePath();
                return true;
            case "set_node_path":
                nodePathHandler.handleSetNodePath(content);
                return true;
            // Project configuration
            case "get_usage_statistics":
                projectConfigHandler.handleGetUsageStatistics(content);
                return true;
            case "get_working_directory":
                projectConfigHandler.handleGetWorkingDirectory();
                return true;
            case "set_working_directory":
                projectConfigHandler.handleSetWorkingDirectory(content);
                return true;
            case "get_editor_font_config":
                projectConfigHandler.handleGetEditorFontConfig();
                return true;
            case "get_ui_font_config":
                projectConfigHandler.handleGetUiFontConfig();
                return true;
            case "set_ui_font_config":
                projectConfigHandler.handleSetUiFontConfig(content);
                return true;
            case "browse_ui_font_file":
                projectConfigHandler.handleBrowseUiFontFile();
                return true;
            case "get_streaming_enabled":
                projectConfigHandler.handleGetStreamingEnabled();
                return true;
            case "set_streaming_enabled":
                projectConfigHandler.handleSetStreamingEnabled(content);
                return true;
            case "get_codex_sandbox_mode":
                projectConfigHandler.handleGetCodexSandboxMode();
                return true;
            case "set_codex_sandbox_mode":
                projectConfigHandler.handleSetCodexSandboxMode(content);
                return true;
            case "get_send_shortcut":
                projectConfigHandler.handleGetSendShortcut();
                return true;
            case "set_send_shortcut":
                projectConfigHandler.handleSetSendShortcut(content);
                return true;
            case "get_auto_open_file_enabled":
                projectConfigHandler.handleGetAutoOpenFileEnabled();
                return true;
            case "set_auto_open_file_enabled":
                projectConfigHandler.handleSetAutoOpenFileEnabled(content);
                return true;
            case "get_commit_generation_enabled":
                projectConfigHandler.handleGetCommitGenerationEnabled();
                return true;
            case "set_commit_generation_enabled":
                projectConfigHandler.handleSetCommitGenerationEnabled(content);
                return true;
            case "get_status_bar_widget_enabled":
                projectConfigHandler.handleGetStatusBarWidgetEnabled();
                return true;
            case "set_status_bar_widget_enabled":
                projectConfigHandler.handleSetStatusBarWidgetEnabled(content);
                return true;
            case "get_ide_theme":
                projectConfigHandler.handleGetIdeTheme();
                return true;
            case "get_commit_prompt":
                projectConfigHandler.handleGetCommitPrompt();
                return true;
            case "set_commit_prompt":
                projectConfigHandler.handleSetCommitPrompt(content);
                return true;
            // Input history
            case "get_input_history":
                inputHistoryHandler.handleGetInputHistory();
                return true;
            case "record_input_history":
                inputHistoryHandler.handleRecordInputHistory(content);
                return true;
            case "delete_input_history_item":
                inputHistoryHandler.handleDeleteInputHistoryItem(content);
                return true;
            case "clear_input_history":
                inputHistoryHandler.handleClearInputHistory();
                return true;
            // Sound notification configuration
            case "get_sound_notification_config":
                soundSettingsHandler.handleGetSoundNotificationConfig();
                return true;
            case "set_sound_notification_enabled":
                soundSettingsHandler.handleSetSoundNotificationEnabled(content);
                return true;
            case "set_sound_only_when_unfocused":
                soundSettingsHandler.handleSetSoundOnlyWhenUnfocused(content);
                return true;
            case "set_selected_sound":
                soundSettingsHandler.handleSetSelectedSound(content);
                return true;
            case "set_custom_sound_path":
                soundSettingsHandler.handleSetCustomSoundPath(content);
                return true;
            case "test_sound":
                soundSettingsHandler.handleTestSound(content);
                return true;
            case "browse_sound_file":
                soundSettingsHandler.handleBrowseSoundFile();
                return true;
            // Remote mode
            case "get_remote_mode":
                projectConfigHandler.handleGetRemoteMode();
                return true;
            case "set_remote_mode":
                projectConfigHandler.handleSetRemoteMode(content);
                return true;
            case "test_remote_connection":
                projectConfigHandler.handleTestRemoteConnection(content);
                return true;
            // Yunxiao (Alibaba Cloud DevOps) bug integration
            case "get_yunxiao_config":
                projectConfigHandler.handleGetYunxiaoConfig();
                return true;
            case "set_yunxiao_config":
                projectConfigHandler.handleSetYunxiaoConfig(content);
                return true;
            case "yunxiao_test_connection":
                projectConfigHandler.handleYunxiaoTestConnection(content);
                return true;
            case "load_yunxiao_projects":
                projectConfigHandler.handleLoadYunxiaoProjects();
                return true;
            case "load_yunxiao_bugs":
                projectConfigHandler.handleLoadYunxiaoBugs(content);
                return true;
            case "load_yunxiao_bug_detail":
                projectConfigHandler.handleLoadYunxiaoBugDetail(content);
                return true;
            case "download_yunxiao_attachment":
                projectConfigHandler.handleDownloadYunxiaoAttachment(content);
                return true;
            case "load_yunxiao_statuses":
                projectConfigHandler.handleLoadYunxiaoStatuses(content);
                return true;
            case "update_yunxiao_status":
                projectConfigHandler.handleUpdateYunxiaoStatus(content);
                return true;
            case "load_yunxiao_members":
                projectConfigHandler.handleLoadYunxiaoMembers(content);
                return true;
            case "update_yunxiao_assignee":
                projectConfigHandler.handleUpdateYunxiaoAssignee(content);
                return true;
            case "submit_yunxiao_comment":
                projectConfigHandler.handleSubmitYunxiaoComment(content);
                return true;
            case "upload_yunxiao_comment_image":
                projectConfigHandler.handleUploadYunxiaoCommentImage(content);
                return true;
            // Path mapping
            case "get_path_mapping":
                projectConfigHandler.handleGetPathMapping();
                return true;
            case "set_path_mapping":
                projectConfigHandler.handleSetPathMapping(content);
                return true;
            case "get_path_misses":
                projectConfigHandler.handleGetPathMisses();
                return true;
            case "clear_path_misses":
                projectConfigHandler.handleClearPathMisses();
                return true;
            // Auto reload from disk
            case "get_auto_reload":
                projectConfigHandler.handleGetAutoReload();
                return true;
            case "set_auto_reload":
                projectConfigHandler.handleSetAutoReload(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * Expose getModelContextLimit for callers that previously used the static method on SettingsHandler.
     */
    public static int getModelContextLimit(String model) {
        return ModelProviderHandler.getModelContextLimit(model);
    }
}
