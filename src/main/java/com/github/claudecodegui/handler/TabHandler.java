package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.github.claudecodegui.settings.TabStateService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;


/**
 * Tab management handler
 * Handles creating new chat tabs in the tool window
 */
public class TabHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(TabHandler.class);

    private static final String[] SUPPORTED_TYPES = {
        "create_new_tab",
        "create_new_supervised_tab",
        "open_history_in_new_tab"
    };

    public TabHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if ("create_new_tab".equals(type)) {
            LOG.debug("[TabHandler] Processing create_new_tab");
            handleCreateNewTab(false, content);
            return true;
        }
        if ("create_new_supervised_tab".equals(type)) {
            LOG.debug("[TabHandler] Processing create_new_supervised_tab");
            handleCreateNewTab(true, content);
            return true;
        }
        if ("open_history_in_new_tab".equals(type)) {
            LOG.debug("[TabHandler] Processing open_history_in_new_tab");
            handleOpenHistoryInNewTab(content);
            return true;
        }
        return false;
    }

    /**
     * Create a new chat tab in the tool window.
     *
     * @param supervised when {@code true}, the new tab is born as a supervisor
     *                   session (staged via {@code setPendingSupervised} so the
     *                   agent picker auto-opens on first {@code frontend_ready}).
     *                   Keeps the born-at-birth session-kind contract: supervised
     *                   sessions only ever live in their own dedicated tab.
     * @param payload    optional JSON payload. For a <b>supervised</b> tab:
     *                   {@code {agentId, initialComposerText}} — staged via
     *                   {@code setPendingSupervisedPayload} (skip picker + prefilled
     *                   draft); null/blank/{@code {}} falls back to the legacy plain
     *                   supervised tab ({@code setPendingSupervised(true)} → picker).
     *                   For a <b>normal</b> tab: {@code {initialComposerText}} — seeds
     *                   the composer (unsent) via {@code setPendingComposerText} (云效
     *                   「建会话」); absent → plain empty tab.
     */
    private void handleCreateNewTab(boolean supervised, String payload) {
        final String tabPayload = (payload != null && !payload.trim().isEmpty()
                && !"{}".equals(payload.trim())) ? payload.trim() : null;
        java.util.function.Consumer<ClaudeChatWindow> preMount;
        if (!supervised) {
            final String composerText = extractInitialComposerText(tabPayload);
            preMount = (composerText != null)
                    ? (win -> win.setPendingComposerText(composerText))
                    : (win -> { });
        } else if (tabPayload != null) {
            preMount = (win -> win.setPendingSupervisedPayload(tabPayload));
        } else {
            preMount = (win -> win.setPendingSupervised(true));
        }
        createTab(preMount, null);
    }

    /** Pull {@code initialComposerText} from a normal-tab payload, or null when absent/empty/invalid. */
    private static String extractInitialComposerText(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            if (o.has("initialComposerText") && !o.get("initialComposerText").isJsonNull()) {
                String t = o.get("initialComposerText").getAsString();
                return (t != null && !t.isEmpty()) ? t : null;
            }
        } catch (Exception e) {
            LOG.warn("[TabHandler] create_new_tab: bad payload: " + e.getMessage());
        }
        return null;
    }

    /**
     * Open a history session (normal or supervised) in a fresh tab. Mirrors the
     * born-at-birth path: create the tab, stage the load payload, and let the new
     * tab's webview run its own loadHistorySession on {@code frontend_ready} (via
     * {@code window.onRequestLoadHistory}). This is why opening from the history
     * list never needs to pre-open / reuse a tab of a particular kind — the new
     * tab simply becomes whatever kind the loaded session is.
     */
    private void handleOpenHistoryInNewTab(String content) {
        String sessionId, containerId, kind, title;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
            sessionId = strOrNull(o, "sessionId");
            containerId = strOrNull(o, "containerId");
            kind = strOrNull(o, "kind");
            title = strOrNull(o, "title");
        } catch (Exception e) {
            LOG.warn("[TabHandler] open_history_in_new_tab: bad payload: " + e.getMessage());
            return;
        }
        // ASCII-safe load payload handed to onRequestLoadHistory (UUIDs + kind
        // enum only — title is intentionally excluded and used as the tab name
        // instead, so callJavaScript's naive single-quote arg wrapping is safe).
        com.google.gson.JsonObject load = new com.google.gson.JsonObject();
        if (sessionId != null) load.addProperty("sessionId", sessionId);
        if (containerId != null) load.addProperty("containerId", containerId);
        load.addProperty("kind", kind != null ? kind : "normal");
        final String loadJson = load.toString();
        final String tabName = (title != null && !title.trim().isEmpty()) ? title.trim() : null;
        createTab(win -> win.setPendingHistoryLoad(loadJson), tabName);
    }

    private static String strOrNull(com.google.gson.JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /**
     * Shared new-tab creation. {@code preMount} stages any pending markers on the
     * window before its webview mounts (supervisor picker / history load).
     * {@code preferredName}, when non-null, names the tab (e.g. the session title);
     * otherwise the saved/auto name is used.
     */
    private void createTab(java.util.function.Consumer<ClaudeChatWindow> preMount, String preferredName) {
        Project project = context.getProject();

        ToolWindowManager.getInstance(project).invokeLater(() -> {
            try {
                ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(ClaudeSDKToolWindow.TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    LOG.error("[TabHandler] Tool window not found");
                    callJavaScript("addErrorMessage", escapeJs("无法找到 CCG 工具窗口"));
                    return;
                }

                // Create a new chat window instance with skipRegister=true (don't replace the main instance)
                ClaudeChatWindow newChatWindow = new ClaudeChatWindow(project, true);

                // Stage pending markers (supervisor picker / history load) BEFORE
                // the webview mounts so handleFrontendReady() can act on them.
                if (preMount != null) {
                    preMount.accept(newChatWindow);
                }

                ContentManager contentManager = toolWindow.getContentManager();
                int tabIndex = contentManager.getContentCount();

                TabStateService tabStateService = TabStateService.getInstance(project);
                String savedName = tabStateService.getTabName(tabIndex);

                // Tab name: explicit preferred (session title) wins, else saved, else auto.
                String tabName;
                if (preferredName != null && !preferredName.isEmpty()) {
                    tabName = preferredName.length() > 40 ? preferredName.substring(0, 40) : preferredName;
                } else if (savedName != null && !savedName.isEmpty()) {
                    tabName = savedName;
                    LOG.info("[TabHandler] Restored tab name from storage: " + tabName);
                } else {
                    tabName = ClaudeSDKToolWindow.getNextTabName(toolWindow);
                }

                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(newChatWindow.getContent(), tabName, false);
                content.setCloseable(true);
                newChatWindow.setParentContent(content);
                content.setDisposer(newChatWindow::dispose);

                contentManager.addContent(content);
                contentManager.setSelectedContent(content);

                toolWindow.show(null);

                LOG.info("[TabHandler] Created new tab: " + tabName);
            } catch (Exception e) {
                LOG.error("[TabHandler] Error creating new tab: " + e.getMessage(), e);
                callJavaScript("addErrorMessage", escapeJs("创建新标签页失败: " + e.getMessage()));
            }
        });
    }
}
