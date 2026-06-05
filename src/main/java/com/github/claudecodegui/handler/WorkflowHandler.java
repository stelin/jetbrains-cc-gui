package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.pair.workflow.SupervisorWorkflowManager;
import com.github.claudecodegui.session.pair.workflow.WorkflowDefinition;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Per-tab thin adapter between the workflow webview and the project-scoped
 * {@link SupervisorWorkflowManager} engine (§9). Parses the {@code workflow_*}
 * events, forwards them to the engine, and registers this tab as a broadcast
 * sink so engine state updates land back in this webview.
 *
 * <p>Lifecycle: the sink is unregistered in {@link #dispose()}, which
 * {@code ChatWindowDelegate} calls before {@code MessageDispatcher.clear()} on
 * tab close — so the engine never pushes to a disposed browser (§9 / §12.3).
 */
public class WorkflowHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(WorkflowHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "workflow_list",
            "workflow_save",
            "workflow_delete",
            "workflow_run",
            "workflow_abort",
            "workflow_jump_node",
            "workflow_open_report",
            "workflow_resume",
            "workflow_redispatch_node"
    };

    private final SupervisorWorkflowManager mgr;
    private final Gson gson = new Gson();
    private final HandlerContext.JsCallback sink;

    public WorkflowHandler(HandlerContext context) {
        super(context);
        this.mgr = SupervisorWorkflowManager.getInstance(context.getProject());
        // Wrap the per-tab JS bridge as a broadcast sink. Guards against pushing
        // to a disposed browser (belt-and-suspenders with dispose()).
        this.sink = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                if (context.isDisposed()) return;
                context.callJavaScript(functionName, args);
            }

            @Override
            public String escapeJs(String str) {
                return context.escapeJs(str);
            }
        };
        mgr.registerSink(sink);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        try {
            switch (type) {
                case "workflow_list":
                    mgr.requestList(sink);
                    return true;
                case "workflow_save":
                    mgr.saveDefinition(parseDefinition(content));
                    return true;
                case "workflow_delete":
                    mgr.deleteDefinition(stringField(content, "id"));
                    return true;
                case "workflow_run":
                    mgr.startWorkflow(stringField(content, "id"));
                    return true;
                case "workflow_abort":
                    mgr.abortWorkflow();
                    return true;
                case "workflow_jump_node":
                    mgr.jumpToNode(stringField(content, "nodeName"));
                    return true;
                case "workflow_open_report":
                    mgr.openReport(stringField(content, "nodeName"));
                    return true;
                case "workflow_resume":
                    mgr.resumeWorkflow(stringField(content, "id"));
                    return true;
                case "workflow_redispatch_node":
                    mgr.redispatchNode(stringField(content, "nodeName"), stringField(content, "mode"));
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            LOG.warn("[WorkflowHandler] " + type + " failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return true;   // consumed (the engine surfaces errors via onWorkflowOperationResult)
        }
    }

    /** Unbind the broadcast sink. Called by {@code ChatWindowDelegate.dispose()}. */
    public void dispose() {
        try {
            mgr.unregisterSink(sink);
        } catch (Exception e) {
            LOG.warn("[WorkflowHandler] dispose failed: " + e.getMessage());
        }
    }

    private WorkflowDefinition parseDefinition(String content) {
        return gson.fromJson(content, WorkflowDefinition.class);
    }

    private String stringField(String content, String field) {
        if (content == null || content.isEmpty()) return null;
        JsonObject o = gson.fromJson(content, JsonObject.class);
        if (o == null || !o.has(field) || o.get(field).isJsonNull()) return null;
        return o.get(field).getAsString();
    }
}
