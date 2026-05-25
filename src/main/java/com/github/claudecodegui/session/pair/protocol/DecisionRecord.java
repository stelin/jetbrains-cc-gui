package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Single decision record stored in the supervisor L2 ring buffer. {@code confidence}: high|medium|low; {@code category}: A|B|C1|C2|C3; {@code severity}: info|warn|alert. */
public class DecisionRecord {

    public long ts;
    public String action;
    public String reason;
    public JsonObject payload;
    public String result;
    public String confidence;
    public String category;
    public String severity;
    public List<Candidate> candidates;
    public String chosenCandidate;
    public List<Evidence> evidence;
    public Integer stepId;
    public Boolean autoMode;

    public DecisionRecord() {
    }

    public static DecisionRecord fromJson(JsonObject obj) {
        DecisionRecord r = new DecisionRecord();
        if (obj.has("ts") && !obj.get("ts").isJsonNull()) {
            r.ts = obj.get("ts").getAsLong();
        }
        if (obj.has("action") && !obj.get("action").isJsonNull()) {
            r.action = obj.get("action").getAsString();
        }
        if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
            r.reason = obj.get("reason").getAsString();
        }
        if (obj.has("payload") && !obj.get("payload").isJsonNull()) {
            r.payload = obj.get("payload").getAsJsonObject();
        }
        if (obj.has("result") && !obj.get("result").isJsonNull()) {
            r.result = obj.get("result").getAsString();
        }
        if (obj.has("confidence") && !obj.get("confidence").isJsonNull()) {
            r.confidence = obj.get("confidence").getAsString();
        }
        if (obj.has("category") && !obj.get("category").isJsonNull()) {
            r.category = obj.get("category").getAsString();
        }
        if (obj.has("severity") && !obj.get("severity").isJsonNull()) {
            r.severity = obj.get("severity").getAsString();
        }
        if (obj.has("candidates") && !obj.get("candidates").isJsonNull()) {
            JsonArray arr = obj.get("candidates").getAsJsonArray();
            r.candidates = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                r.candidates.add(Candidate.fromJson(el.getAsJsonObject()));
            }
        }
        if (obj.has("chosenCandidate") && !obj.get("chosenCandidate").isJsonNull()) {
            r.chosenCandidate = obj.get("chosenCandidate").getAsString();
        }
        if (obj.has("evidence") && !obj.get("evidence").isJsonNull()) {
            JsonArray arr = obj.get("evidence").getAsJsonArray();
            r.evidence = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                r.evidence.add(Evidence.fromJson(el.getAsJsonObject()));
            }
        }
        if (obj.has("stepId") && !obj.get("stepId").isJsonNull()) {
            r.stepId = obj.get("stepId").getAsInt();
        }
        if (obj.has("autoMode") && !obj.get("autoMode").isJsonNull()) {
            r.autoMode = obj.get("autoMode").getAsBoolean();
        }
        return r;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("ts", ts);
        if (action != null) {
            obj.addProperty("action", action);
        }
        if (reason != null) {
            obj.addProperty("reason", reason);
        }
        if (payload != null) {
            obj.add("payload", payload);
        }
        if (result != null) {
            obj.addProperty("result", result);
        }
        if (confidence != null) {
            obj.addProperty("confidence", confidence);
        }
        if (category != null) {
            obj.addProperty("category", category);
        }
        if (severity != null) {
            obj.addProperty("severity", severity);
        }
        if (candidates != null) {
            JsonArray arr = new JsonArray();
            for (Candidate c : candidates) {
                arr.add(c.toJson());
            }
            obj.add("candidates", arr);
        }
        if (chosenCandidate != null) {
            obj.addProperty("chosenCandidate", chosenCandidate);
        }
        if (evidence != null) {
            JsonArray arr = new JsonArray();
            for (Evidence e : evidence) {
                arr.add(e.toJson());
            }
            obj.add("evidence", arr);
        }
        if (stepId != null) {
            obj.addProperty("stepId", stepId);
        }
        if (autoMode != null) {
            obj.addProperty("autoMode", autoMode);
        }
        return obj;
    }
}
