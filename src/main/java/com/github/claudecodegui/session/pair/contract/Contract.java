package com.github.claudecodegui.session.pair.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-boundary obligation between supervisor and main AI. Owned by
 * {@link ContractRegistry}; persisted by L2 as {@code PersistedContract}.
 *
 * <p>Mutable POJO with volatile mutation fields so the registry's reader
 * paths (no lock) see consistent status / counts. Structural fields
 * ({@code id}, {@code parentStepId}, ...) are effectively-final after
 * construction.
 */
public class Contract {

    public String id;
    public String parentStepId;
    public ContractType type;
    public ContractAssignee assignedTo;
    public volatile ContractStatus status;
    public long issuedAt;
    public long deadlineMs;
    public volatile long lastActivityAt;
    public String payloadJson;
    public volatile int retryCount;
    public int maxRetries;
    public String retryOf;
    public List<ContractEvent> history = new ArrayList<>();

    public Contract() {
        /* gson */
    }

    public static Contract create(
            String id,
            String parentStepId,
            ContractType type,
            ContractAssignee assignedTo,
            String payloadJson,
            long deadlineMs,
            int maxRetries,
            String retryOf,
            int retryCount) {
        Contract c = new Contract();
        c.id = id;
        c.parentStepId = parentStepId;
        c.type = type;
        c.assignedTo = assignedTo;
        c.status = ContractStatus.OPEN;
        c.issuedAt = System.currentTimeMillis();
        c.lastActivityAt = c.issuedAt;
        c.payloadJson = payloadJson;
        c.deadlineMs = deadlineMs;
        c.maxRetries = maxRetries;
        c.retryOf = retryOf;
        c.retryCount = retryCount;
        return c;
    }

    public boolean isOpen() {
        return status == ContractStatus.OPEN || status == ContractStatus.RECEIVED;
    }

    public boolean isTerminal() {
        return status == ContractStatus.DISCHARGED
                || status == ContractStatus.CANCELLED
                || status == ContractStatus.EXPIRED_ESCALATED;
    }

    public long ageMs(long now) {
        return now - issuedAt;
    }

    public long idleMs(long now) {
        return now - lastActivityAt;
    }
}
