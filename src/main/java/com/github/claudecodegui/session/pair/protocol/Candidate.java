package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Candidate option considered by supervisor when making a decision. */
public class Candidate {

    public String option;
    public Double score;

    public Candidate() {
    }

    public static Candidate fromJson(JsonObject obj) {
        Candidate c = new Candidate();
        if (obj.has("option") && !obj.get("option").isJsonNull()) {
            c.option = obj.get("option").getAsString();
        }
        if (obj.has("score") && !obj.get("score").isJsonNull()) {
            c.score = obj.get("score").getAsDouble();
        }
        return c;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (option != null) {
            obj.addProperty("option", option);
        }
        if (score != null) {
            obj.addProperty("score", score);
        }
        return obj;
    }
}
