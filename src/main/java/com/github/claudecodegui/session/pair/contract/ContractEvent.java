package com.github.claudecodegui.session.pair.contract;

public class ContractEvent {

    public enum EventType {
        ISSUED,
        RECEIVED,
        RETRIED,
        DISCHARGED,
        ESCALATED,
        CANCELLED
    }

    public long ts;
    public EventType type;
    public String evidence;
    public String note;

    public ContractEvent() {
        /* gson */
    }

    public ContractEvent(long ts, EventType type, String evidence, String note) {
        this.ts = ts;
        this.type = type;
        this.evidence = evidence;
        this.note = note;
    }

    public static ContractEvent of(EventType type, String note) {
        return new ContractEvent(System.currentTimeMillis(), type, null, note);
    }
}
