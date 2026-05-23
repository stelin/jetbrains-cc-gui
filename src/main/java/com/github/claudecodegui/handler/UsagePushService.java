package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.github.claudecodegui.util.EditorFileUtils;
import com.github.claudecodegui.util.IgnoreRuleMatcher;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

/**
 * Handles usage statistics push and context bar refresh operations.
 */
public class UsagePushService {

    private static final Logger LOG = Logger.getInstance(UsagePushService.class);
    private static final Gson STATIC_GSON = new Gson();

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public UsagePushService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Single entry point for pushing a usage snapshot to the webview, shared by
     * both the main AI and the supervisor pane. Centralising computation here
     * means {@link SettingsHandler#getModelContextLimit} and the Claude usage
     * formula ({@link TokenUsageUtils#extractUsedTokens}) drive both panes from
     * the same code path — when one changes, the other follows automatically.
     *
     * <p>The webview receives a single callback, {@code window.onUsageUpdate},
     * with a {@code scope} discriminator. Payload schema:
     * <pre>
     *   { scope: "main" | "supervisor",
     *     supervisorId?: string,   // present when scope = "supervisor"
     *     model: string,
     *     percentage: int,
     *     usedTokens: int, totalTokens: int,  // alias, kept for compatibility
     *     maxTokens:  int, limit:       int   // alias, kept for compatibility
     *   }
     * </pre>
     *
     * @param rawSdkUsage SDK-shaped usage object (snake_case input_tokens etc.);
     *                    null is allowed and treated as "0 tokens used"
     *                    (used by supervisor baseline push at pair_start).
     * @param model       Model id whose context window we measure against.
     * @param scope       "main" or "supervisor".
     * @param supervisorId Agent id when scope=supervisor; ignored otherwise.
     * @param context     Handler context — provides browser + EDT plumbing.
     */
    public static void broadcast(
            JsonObject rawSdkUsage,
            String model,
            String scope,
            String supervisorId,
            HandlerContext context
    ) {
        if (context == null) return;
        int usedTokens = rawSdkUsage == null
                ? 0
                : TokenUsageUtils.extractUsedTokens(rawSdkUsage, "claude");
        int maxTokens = SettingsHandler.getModelContextLimit(model);
        int percentage = Math.min(100, maxTokens > 0 ? (int) ((usedTokens * 100.0) / maxTokens) : 0);

        JsonObject payload = new JsonObject();
        payload.addProperty("scope", scope);
        if (supervisorId != null && !supervisorId.isEmpty()) {
            payload.addProperty("supervisorId", supervisorId);
        }
        if (model != null) {
            payload.addProperty("model", model);
        }
        payload.addProperty("percentage", percentage);
        payload.addProperty("usedTokens", usedTokens);
        payload.addProperty("totalTokens", usedTokens);
        payload.addProperty("maxTokens", maxTokens);
        payload.addProperty("limit", maxTokens);

        String json = STATIC_GSON.toJson(payload);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (context.getBrowser() == null || context.isDisposed()) return;
            String js = "(function() {" +
                    "  if (typeof window.onUsageUpdate === 'function') {" +
                    "    window.onUsageUpdate('" + context.escapeJs(json) + "');" +
                    "  }" +
                    "})();";
            context.getBrowser().getCefBrowser().executeJavaScript(
                    js, context.getBrowser().getCefBrowser().getURL(), 0);
        });
    }

    /**
     * Push usage update after model switch.
     * Recalculates percentage and maxTokens based on the new model's context limit.
     */
    public void pushUsageUpdateAfterModelChange(int newMaxTokens) {
        try {
            ClaudeSession session = context.getSession();
            if (session == null) {
                // Even without a session, send update so frontend knows the new maxTokens
                sendUsageUpdate(0, newMaxTokens);
                return;
            }

            // Extract the latest usage information from the current session
            List<ClaudeSession.Message> messages = session.getMessages();
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromSessionMessages(messages);
            if (lastUsage == null) {
                // No usage data available yet — send update with zero used tokens
                sendUsageUpdate(0, newMaxTokens);
                return;
            }
            int usedTokens = TokenUsageUtils.extractUsedTokens(lastUsage, context.getCurrentProvider());

            // Send update
            sendUsageUpdate(usedTokens, newMaxTokens);

        } catch (Exception e) {
            LOG.error("[UsagePushService] Failed to push usage update after model change: " + e.getMessage(), e);
        }
    }

    /**
     * Send usage update to the frontend.
     */
    public void sendUsageUpdate(int usedTokens, int maxTokens) {
        int percentage = Math.min(100, maxTokens > 0 ? (int) ((usedTokens * 100.0) / maxTokens) : 0);

        LOG.info("[UsagePushService] Sending usage update: usedTokens=" + usedTokens + ", maxTokens=" + maxTokens + ", percentage=" + percentage + "%");

        // Build usage update data
        JsonObject usageUpdate = new JsonObject();
        usageUpdate.addProperty("percentage", percentage);
        usageUpdate.addProperty("totalTokens", usedTokens);
        usageUpdate.addProperty("limit", maxTokens);
        usageUpdate.addProperty("usedTokens", usedTokens);
        usageUpdate.addProperty("maxTokens", maxTokens);

        String usageJson = gson.toJson(usageUpdate);

        // Push to frontend (must be executed on the EDT thread)
        ApplicationManager.getApplication().invokeLater(() -> {
            if (context.getBrowser() != null && !context.isDisposed()) {
                String js = "(function() {" +
                        "  if (typeof window.onUsageUpdate === 'function') {" +
                        "    window.onUsageUpdate('" + context.escapeJs(usageJson) + "');" +
                        "  }" +
                        "})();";
                context.getBrowser().getCefBrowser().executeJavaScript(js, context.getBrowser().getCefBrowser().getURL(), 0);
            } else {
                LOG.warn("[UsagePushService] Cannot send usage update: browser is null or disposed");
            }
        });
    }

    /**
     * Refresh the context bar with the currently open editor file.
     */
    public void refreshContextBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (context.getProject() == null) {
                    return;
                }

                // Check if auto-open file is enabled
                String projectPath = context.getProject().getBasePath();
                if (projectPath != null) {
                    CodemossSettingsService settingsService = new CodemossSettingsService();
                    boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                    if (!autoOpenFileEnabled) {
                        // If auto-open file is disabled, clear the ContextBar display
                        context.callJavaScript("clearSelectionInfo");
                        return;
                    }
                }

                // Get cached .gitignore matcher for filtering sensitive files
                IgnoreRuleMatcher gitIgnoreMatcher = IgnoreRuleMatcher.forProjectSafe(projectPath);

                FileEditorManager editorManager = FileEditorManager.getInstance(context.getProject());
                Editor editor = editorManager.getSelectedTextEditor();
                String selectionInfo = null;

                if (editor != null) {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                    if (file != null) {
                        String path = file.getPath();

                        // Filter out .gitignore'd files to prevent sensitive files from being auto-opened
                        if (gitIgnoreMatcher != null && gitIgnoreMatcher.isFileIgnored(path)) {
                            context.callJavaScript("clearSelectionInfo");
                            return;
                        }

                        selectionInfo = "@" + path;

                        SelectionModel selectionModel = editor.getSelectionModel();
                        if (selectionModel.hasSelection()) {
                            int startLine = editor.getDocument().getLineNumber(selectionModel.getSelectionStart()) + 1;
                            int endLine = editor.getDocument().getLineNumber(selectionModel.getSelectionEnd()) + 1;

                            if (endLine > startLine
                                    && editor.offsetToLogicalPosition(selectionModel.getSelectionEnd()).column == 0) {
                                endLine--;
                            }
                            selectionInfo += "#L" + startLine + "-" + endLine;
                        }
                    }
                } else {
                    VirtualFile[] files = editorManager.getSelectedFiles();
                    if (files.length > 0 && files[0] != null) {
                        String path = files[0].getPath();

                        // Filter out .gitignore'd files
                        if (gitIgnoreMatcher != null && gitIgnoreMatcher.isFileIgnored(path)) {
                            context.callJavaScript("clearSelectionInfo");
                            return;
                        }

                        selectionInfo = "@" + path;
                    }
                }

                if (selectionInfo != null && !selectionInfo.isEmpty()) {
                    context.callJavaScript("addSelectionInfo", context.escapeJs(selectionInfo));
                } else {
                    context.callJavaScript("clearSelectionInfo");
                }
            } catch (Exception e) {
                LOG.warn("[UsagePushService] Failed to refresh context bar: " + e.getMessage());
            }
        });
    }
}
