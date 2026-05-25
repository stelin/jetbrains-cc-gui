package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Budget configuration provided at pair startup. All fields nullable; absent means unlimited. */
public class PairBudget {

    public Long maxTokens;
    public Long maxDurationMs;
    public Integer maxSteps;
    public Integer maxSubagentCalls;

    public PairBudget() {
    }

    public static PairBudget fromJson(JsonObject obj) {
        PairBudget b = new PairBudget();
        if (obj.has("maxTokens") && !obj.get("maxTokens").isJsonNull()) {
            b.maxTokens = obj.get("maxTokens").getAsLong();
        }
        if (obj.has("maxDurationMs") && !obj.get("maxDurationMs").isJsonNull()) {
            b.maxDurationMs = obj.get("maxDurationMs").getAsLong();
        }
        if (obj.has("maxSteps") && !obj.get("maxSteps").isJsonNull()) {
            b.maxSteps = obj.get("maxSteps").getAsInt();
        }
        if (obj.has("maxSubagentCalls") && !obj.get("maxSubagentCalls").isJsonNull()) {
            b.maxSubagentCalls = obj.get("maxSubagentCalls").getAsInt();
        }
        return b;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (maxTokens != null) {
            obj.addProperty("maxTokens", maxTokens);
        }
        if (maxDurationMs != null) {
            obj.addProperty("maxDurationMs", maxDurationMs);
        }
        if (maxSteps != null) {
            obj.addProperty("maxSteps", maxSteps);
        }
        if (maxSubagentCalls != null) {
            obj.addProperty("maxSubagentCalls", maxSubagentCalls);
        }
        return obj;
    }

    public boolean hasAnyLimit() {
        return maxTokens != null || maxDurationMs != null || maxSteps != null || maxSubagentCalls != null;
    }
}
