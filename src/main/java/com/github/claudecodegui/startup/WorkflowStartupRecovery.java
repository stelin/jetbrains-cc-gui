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
 * Workflow startup recovery (D18/D20 — resume-and-redispatch-plan §3.1): a
 * supervisor workflow left {@code RUNNING} when the IDE shut down has lost all
 * its pairs/cockpit windows. On project open, reload it into a {@code PAUSED}
 * state — the DAG and node statuses are fully restored and the lock is held, but
 * nothing runs until the user clicks 「恢复运行」. (Previously this discarded the
 * run as {@code ABORTED}, the old D17 behaviour.)
 *
 * <p>Delegates to {@link SupervisorWorkflowManager#rehydrateOnStartup()}, which
 * runs on its own scheduler thread and adopts the on-disk copy into the live
 * execution.
 */
public class WorkflowStartupRecovery implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(WorkflowStartupRecovery.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            SupervisorWorkflowManager.getInstance(project).rehydrateOnStartup();
        } catch (Exception e) {
            LOG.warn("[Workflow] startup recovery dispatch failed: " + e.getMessage());
        }
        return Unit.INSTANCE;
    }
}
