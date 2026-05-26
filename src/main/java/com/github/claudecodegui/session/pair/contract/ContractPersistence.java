package com.github.claudecodegui.session.pair.contract;

import com.github.claudecodegui.session.pair.l2.L2State;

import java.util.ArrayList;
import java.util.List;

public final class ContractPersistence {

    private ContractPersistence() { /* no instances */ }

    public static L2State.PersistedContract toPersisted(Contract c) {
        if (c == null) return null;
        L2State.PersistedContract pc = new L2State.PersistedContract();
        pc.id = c.id;
        pc.parentStepId = c.parentStepId;
        pc.type = c.type == null ? null : c.type.name();
        pc.assignedTo = c.assignedTo == null ? null : c.assignedTo.name();
        pc.status = c.status == null ? null : c.status.name();
        pc.issuedAt = c.issuedAt;
        pc.deadlineMs = c.deadlineMs;
        pc.lastActivityAt = c.lastActivityAt;
        pc.payloadJson = c.payloadJson;
        pc.retryCount = c.retryCount;
        pc.maxRetries = c.maxRetries;
        pc.retryOf = c.retryOf;
        pc.history = new ArrayList<>();
        if (c.history != null) {
            for (ContractEvent e : c.history) {
                pc.history.add(eventToPersisted(e));
            }
        }
        return pc;
    }

    public static Contract fromPersisted(L2State.PersistedContract pc) {
        if (pc == null) return null;
        Contract c = new Contract();
        c.id = pc.id;
        c.parentStepId = pc.parentStepId;
        c.type = parseType(pc.type);
        c.assignedTo = parseAssignee(pc.assignedTo);
        c.status = parseStatus(pc.status);
        c.issuedAt = pc.issuedAt;
        c.deadlineMs = pc.deadlineMs;
        c.lastActivityAt = pc.lastActivityAt;
        c.payloadJson = pc.payloadJson;
        c.retryCount = pc.retryCount;
        c.maxRetries = pc.maxRetries;
        c.retryOf = pc.retryOf;
        c.history = new ArrayList<>();
        if (pc.history != null) {
            for (L2State.PersistedContractEvent e : pc.history) {
                c.history.add(eventFromPersisted(e));
            }
        }
        return c;
    }

    public static List<L2State.PersistedContract> toPersistedList(List<Contract> contracts) {
        List<L2State.PersistedContract> out = new ArrayList<>();
        if (contracts != null) {
            for (Contract c : contracts) out.add(toPersisted(c));
        }
        return out;
    }

    public static List<Contract> fromPersistedList(List<L2State.PersistedContract> persisted) {
        List<Contract> out = new ArrayList<>();
        if (persisted != null) {
            for (L2State.PersistedContract pc : persisted) out.add(fromPersisted(pc));
        }
        return out;
    }

    private static L2State.PersistedContractEvent eventToPersisted(ContractEvent e) {
        L2State.PersistedContractEvent pe = new L2State.PersistedContractEvent();
        pe.ts = e.ts;
        pe.type = e.type == null ? null : e.type.name();
        pe.evidence = e.evidence;
        pe.note = e.note;
        return pe;
    }

    private static ContractEvent eventFromPersisted(L2State.PersistedContractEvent pe) {
        ContractEvent e = new ContractEvent();
        e.ts = pe.ts;
        e.type = parseEventType(pe.type);
        e.evidence = pe.evidence;
        e.note = pe.note;
        return e;
    }

    private static ContractType parseType(String s) {
        if (s == null) return null;
        try { return ContractType.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static ContractAssignee parseAssignee(String s) {
        if (s == null) return null;
        try { return ContractAssignee.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static ContractStatus parseStatus(String s) {
        if (s == null) return ContractStatus.OPEN;
        try { return ContractStatus.valueOf(s); } catch (IllegalArgumentException e) { return ContractStatus.OPEN; }
    }

    private static ContractEvent.EventType parseEventType(String s) {
        if (s == null) return null;
        try { return ContractEvent.EventType.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }
}
