package com.github.claudecodegui.session.pair.contract;

public interface ContractListener {

    default void onIssued(Contract c) { }

    default void onDischarged(Contract c) { }

    default void onRetried(Contract original, Contract newRetry) { }

    default void onEscalated(Contract c) { }

    default void onCancelled(Contract c, String reason) { }
}
