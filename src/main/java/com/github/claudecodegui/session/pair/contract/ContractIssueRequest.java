package com.github.claudecodegui.session.pair.contract;

public final class ContractIssueRequest {

    public final String contractIdHint;
    public final String parentStepId;
    public final ContractType type;
    public final ContractAssignee assignedTo;
    public final String payloadJson;
    public final long deadlineMs;
    public final boolean replaceExisting;
    public final String retryOf;
    public final int retryCount;
    public final int maxRetries;

    private ContractIssueRequest(Builder b) {
        this.contractIdHint = b.contractIdHint;
        this.parentStepId = b.parentStepId;
        this.type = b.type;
        this.assignedTo = b.assignedTo;
        this.payloadJson = b.payloadJson;
        this.deadlineMs = b.deadlineMs;
        this.replaceExisting = b.replaceExisting;
        this.retryOf = b.retryOf;
        this.retryCount = b.retryCount;
        this.maxRetries = b.maxRetries;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String contractIdHint;
        private String parentStepId;
        private ContractType type;
        private ContractAssignee assignedTo;
        private String payloadJson;
        private long deadlineMs = 0;
        private boolean replaceExisting = true;
        private String retryOf;
        private int retryCount = 0;
        private int maxRetries = ContractRegistry.DEFAULT_MAX_RETRIES;

        public Builder contractIdHint(String v) { this.contractIdHint = v; return this; }
        public Builder parentStepId(String v) { this.parentStepId = v; return this; }
        public Builder type(ContractType v) { this.type = v; return this; }
        public Builder assignedTo(ContractAssignee v) { this.assignedTo = v; return this; }
        public Builder payloadJson(String v) { this.payloadJson = v; return this; }
        public Builder deadlineMs(long v) { this.deadlineMs = v; return this; }
        public Builder replaceExisting(boolean v) { this.replaceExisting = v; return this; }
        public Builder retryOf(String v) { this.retryOf = v; return this; }
        public Builder retryCount(int v) { this.retryCount = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }

        public ContractIssueRequest build() {
            if (parentStepId == null || parentStepId.isEmpty()) {
                throw new IllegalArgumentException("parentStepId is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }
            if (assignedTo == null) {
                throw new IllegalArgumentException("assignedTo is required");
            }
            if (payloadJson == null) {
                throw new IllegalArgumentException("payloadJson is required (may be empty string)");
            }
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount < 0");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries < 0");
            }
            return new ContractIssueRequest(this);
        }
    }
}
