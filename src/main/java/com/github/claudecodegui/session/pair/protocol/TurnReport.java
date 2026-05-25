package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Main-AI turn report event. {@code type} is always "turn_report". */
public class TurnReport {

    public String type;
    public String sessionId;
    public String turnId;
    public String directiveId;
    public long ts;
    public TurnReportPayload payload;

    public TurnReport() {
    }

    public static TurnReport fromJson(JsonObject obj) {
        TurnReport r = new TurnReport();
        if (obj.has("type") && !obj.get("type").isJsonNull()) {
            r.type = obj.get("type").getAsString();
        }
        if (obj.has("sessionId") && !obj.get("sessionId").isJsonNull()) {
            r.sessionId = obj.get("sessionId").getAsString();
        }
        if (obj.has("turnId") && !obj.get("turnId").isJsonNull()) {
            r.turnId = obj.get("turnId").getAsString();
        }
        if (obj.has("directiveId") && !obj.get("directiveId").isJsonNull()) {
            r.directiveId = obj.get("directiveId").getAsString();
        }
        if (obj.has("ts") && !obj.get("ts").isJsonNull()) {
            r.ts = obj.get("ts").getAsLong();
        }
        if (obj.has("payload") && !obj.get("payload").isJsonNull()) {
            r.payload = TurnReportPayload.fromJson(obj.get("payload").getAsJsonObject());
        }
        return r;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (type != null) {
            obj.addProperty("type", type);
        }
        if (sessionId != null) {
            obj.addProperty("sessionId", sessionId);
        }
        if (turnId != null) {
            obj.addProperty("turnId", turnId);
        }
        if (directiveId != null) {
            obj.addProperty("directiveId", directiveId);
        }
        obj.addProperty("ts", ts);
        if (payload != null) {
            obj.add("payload", payload.toJson());
        }
        return obj;
    }

    public static class TurnReportPayload {

        public String summary;
        public List<Deliverable> deliverables;
        public List<Verification> verifications;
        public SelfAssessment selfAssessment;
        public SubagentSummary subagentSummary;
        public long durationMs;
        public String spilledPath;

        public TurnReportPayload() {
        }

        public static TurnReportPayload fromJson(JsonObject obj) {
            TurnReportPayload p = new TurnReportPayload();
            if (obj.has("summary") && !obj.get("summary").isJsonNull()) {
                p.summary = obj.get("summary").getAsString();
            }
            if (obj.has("deliverables") && !obj.get("deliverables").isJsonNull()) {
                JsonArray arr = obj.get("deliverables").getAsJsonArray();
                p.deliverables = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    p.deliverables.add(Deliverable.fromJson(el.getAsJsonObject()));
                }
            }
            if (obj.has("verifications") && !obj.get("verifications").isJsonNull()) {
                JsonArray arr = obj.get("verifications").getAsJsonArray();
                p.verifications = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    p.verifications.add(Verification.fromJson(el.getAsJsonObject()));
                }
            }
            if (obj.has("selfAssessment") && !obj.get("selfAssessment").isJsonNull()) {
                p.selfAssessment = SelfAssessment.fromJson(obj.get("selfAssessment").getAsJsonObject());
            }
            if (obj.has("subagentSummary") && !obj.get("subagentSummary").isJsonNull()) {
                p.subagentSummary = SubagentSummary.fromJson(obj.get("subagentSummary").getAsJsonObject());
            }
            if (obj.has("durationMs") && !obj.get("durationMs").isJsonNull()) {
                p.durationMs = obj.get("durationMs").getAsLong();
            }
            if (obj.has("spilledPath") && !obj.get("spilledPath").isJsonNull()) {
                p.spilledPath = obj.get("spilledPath").getAsString();
            }
            return p;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (summary != null) {
                obj.addProperty("summary", summary);
            }
            if (deliverables != null) {
                JsonArray arr = new JsonArray();
                for (Deliverable d : deliverables) {
                    arr.add(d.toJson());
                }
                obj.add("deliverables", arr);
            }
            if (verifications != null) {
                JsonArray arr = new JsonArray();
                for (Verification v : verifications) {
                    arr.add(v.toJson());
                }
                obj.add("verifications", arr);
            }
            if (selfAssessment != null) {
                obj.add("selfAssessment", selfAssessment.toJson());
            }
            if (subagentSummary != null) {
                obj.add("subagentSummary", subagentSummary.toJson());
            }
            obj.addProperty("durationMs", durationMs);
            if (spilledPath != null) {
                obj.addProperty("spilledPath", spilledPath);
            }
            return obj;
        }
    }

    public static class Deliverable {

        public String path;
        public String change;
        public String confidence;

        public Deliverable() {
        }

        public static Deliverable fromJson(JsonObject obj) {
            Deliverable d = new Deliverable();
            if (obj.has("path") && !obj.get("path").isJsonNull()) {
                d.path = obj.get("path").getAsString();
            }
            if (obj.has("change") && !obj.get("change").isJsonNull()) {
                d.change = obj.get("change").getAsString();
            }
            if (obj.has("confidence") && !obj.get("confidence").isJsonNull()) {
                d.confidence = obj.get("confidence").getAsString();
            }
            return d;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (path != null) {
                obj.addProperty("path", path);
            }
            if (change != null) {
                obj.addProperty("change", change);
            }
            if (confidence != null) {
                obj.addProperty("confidence", confidence);
            }
            return obj;
        }
    }

    public static class Verification {

        public String command;
        public boolean pass;
        public String stderrTail;

        public Verification() {
        }

        public static Verification fromJson(JsonObject obj) {
            Verification v = new Verification();
            if (obj.has("command") && !obj.get("command").isJsonNull()) {
                v.command = obj.get("command").getAsString();
            }
            if (obj.has("pass") && !obj.get("pass").isJsonNull()) {
                v.pass = obj.get("pass").getAsBoolean();
            }
            if (obj.has("stderrTail") && !obj.get("stderrTail").isJsonNull()) {
                v.stderrTail = obj.get("stderrTail").getAsString();
            }
            return v;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (command != null) {
                obj.addProperty("command", command);
            }
            obj.addProperty("pass", pass);
            if (stderrTail != null) {
                obj.addProperty("stderrTail", stderrTail);
            }
            return obj;
        }
    }

    public static class SelfAssessment {

        public String confidence;
        public List<String> concerns;
        public String suggestedReview;

        public SelfAssessment() {
        }

        public static SelfAssessment fromJson(JsonObject obj) {
            SelfAssessment s = new SelfAssessment();
            if (obj.has("confidence") && !obj.get("confidence").isJsonNull()) {
                s.confidence = obj.get("confidence").getAsString();
            }
            if (obj.has("concerns") && !obj.get("concerns").isJsonNull()) {
                JsonArray arr = obj.get("concerns").getAsJsonArray();
                s.concerns = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    s.concerns.add(el.getAsString());
                }
            }
            if (obj.has("suggestedReview") && !obj.get("suggestedReview").isJsonNull()) {
                s.suggestedReview = obj.get("suggestedReview").getAsString();
            }
            return s;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (confidence != null) {
                obj.addProperty("confidence", confidence);
            }
            if (concerns != null) {
                JsonArray arr = new JsonArray();
                for (String c : concerns) {
                    arr.add(c);
                }
                obj.add("concerns", arr);
            }
            if (suggestedReview != null) {
                obj.addProperty("suggestedReview", suggestedReview);
            }
            return obj;
        }
    }

    public static class SubagentSummary {

        public int count;
        public long totalDurationMs;

        public SubagentSummary() {
        }

        public static SubagentSummary fromJson(JsonObject obj) {
            SubagentSummary s = new SubagentSummary();
            if (obj.has("count") && !obj.get("count").isJsonNull()) {
                s.count = obj.get("count").getAsInt();
            }
            if (obj.has("totalDurationMs") && !obj.get("totalDurationMs").isJsonNull()) {
                s.totalDurationMs = obj.get("totalDurationMs").getAsLong();
            }
            return s;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("count", count);
            obj.addProperty("totalDurationMs", totalDurationMs);
            return obj;
        }
    }
}
