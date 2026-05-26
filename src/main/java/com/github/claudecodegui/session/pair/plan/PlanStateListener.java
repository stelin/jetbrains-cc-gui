package com.github.claudecodegui.session.pair.plan;

public interface PlanStateListener {

    /**
     * Fired AFTER a state transition is committed. {@code now} is the live
     * Plan reference — do not mutate. Notification happens inside the
     * PlanStateMachine's synchronization block, so listeners must return
     * quickly and must not call back into PlanStateMachine on the same thread.
     */
    void onStateChanged(Plan.PlanState oldState, Plan.ActiveSubState oldSub, Plan now);
}
