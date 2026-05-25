package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Current budget usage status as ratios (used / limit); values may exceed 1.0 when over budget. */
public class BudgetStatus {

    public double tokenRatio;
    public double durationRatio;
    public double stepRatio;
    public double subagentRatio;

    public BudgetStatus() {
    }

    public static BudgetStatus fromJson(JsonObject obj) {
        BudgetStatus s = new BudgetStatus();
        if (obj.has("tokenRatio") && !obj.get("tokenRatio").isJsonNull()) {
            s.tokenRatio = obj.get("tokenRatio").getAsDouble();
        }
        if (obj.has("durationRatio") && !obj.get("durationRatio").isJsonNull()) {
            s.durationRatio = obj.get("durationRatio").getAsDouble();
        }
        if (obj.has("stepRatio") && !obj.get("stepRatio").isJsonNull()) {
            s.stepRatio = obj.get("stepRatio").getAsDouble();
        }
        if (obj.has("subagentRatio") && !obj.get("subagentRatio").isJsonNull()) {
            s.subagentRatio = obj.get("subagentRatio").getAsDouble();
        }
        return s;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokenRatio", tokenRatio);
        obj.addProperty("durationRatio", durationRatio);
        obj.addProperty("stepRatio", stepRatio);
        obj.addProperty("subagentRatio", subagentRatio);
        return obj;
    }

    public boolean anyAbove(double threshold) {
        return tokenRatio > threshold
                || durationRatio > threshold
                || stepRatio > threshold
                || subagentRatio > threshold;
    }

    public double maxRatio() {
        double m = tokenRatio;
        if (durationRatio > m) {
            m = durationRatio;
        }
        if (stepRatio > m) {
            m = stepRatio;
        }
        if (subagentRatio > m) {
            m = subagentRatio;
        }
        return m;
    }
}
