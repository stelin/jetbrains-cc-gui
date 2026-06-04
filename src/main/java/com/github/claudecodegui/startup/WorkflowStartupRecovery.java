package com.github.claudecodegui.startup;

import com.github.claudecodegui.session.pair.workflow.SupervisorWorkflowManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Workflow startup recovery (coding-plan §17 / §24 / D17): a supervisor
 * workflow left {@code RUNNING} when the IDE shut down has lost all its pairs
 * and tabs and is NOT auto-resumed. On project open, mark any such persisted
 * execution {@code ABORTED} so the overview reflects reality on next view.
 *
 * <p>Delegates to {@link SupervisorWorkflowManager#recoverStaleExecutionsOnStartup()},
 * which runs the scan on its own scheduler thread over on-disk copies only.
 */
public class WorkflowStartupRecovery implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(WorkflowStartupRecovery.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            SupervisorWorkflowManager.getInstance(project).recoverStaleExecutionsOnStartup();
        } catch (Exception e) {
            LOG.warn("[Workflow] startup recovery dispatch failed: " + e.getMessage());
        }
        return Unit.INSTANCE;
    }
}
