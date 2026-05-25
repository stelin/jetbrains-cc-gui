package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Structured payload for supervisor inject_prompt directives. {@code kind}: task_assignment|review_feedback|acknowledgement|bootstrap. */
public class DirectivePayload {

    public String kind;
    public String objective;
    public DirectiveContext context;
    public List<String> expectedDeliverables;
    public List<String> acceptanceCriteria;
    public String inlinePrompt;
    public String spilledPath;

    public DirectivePayload() {
    }

    public static DirectivePayload fromJson(JsonObject obj) {
        DirectivePayload p = new DirectivePayload();
        if (obj.has("kind") && !obj.get("kind").isJsonNull()) {
            p.kind = obj.get("kind").getAsString();
        }
        if (obj.has("objective") && !obj.get("objective").isJsonNull()) {
            p.objective = obj.get("objective").getAsString();
        }
        if (obj.has("context") && !obj.get("context").isJsonNull()) {
            p.context = DirectiveContext.fromJson(obj.get("context").getAsJsonObject());
        }
        if (obj.has("expectedDeliverables") && !obj.get("expectedDeliverables").isJsonNull()) {
            JsonArray arr = obj.get("expectedDeliverables").getAsJsonArray();
            p.expectedDeliverables = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                p.expectedDeliverables.add(el.getAsString());
            }
        }
        if (obj.has("acceptanceCriteria") && !obj.get("acceptanceCriteria").isJsonNull()) {
            JsonArray arr = obj.get("acceptanceCriteria").getAsJsonArray();
            p.acceptanceCriteria = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                p.acceptanceCriteria.add(el.getAsString());
            }
        }
        if (obj.has("inlinePrompt") && !obj.get("inlinePrompt").isJsonNull()) {
            p.inlinePrompt = obj.get("inlinePrompt").getAsString();
        }
        if (obj.has("spilledPath") && !obj.get("spilledPath").isJsonNull()) {
            p.spilledPath = obj.get("spilledPath").getAsString();
        }
        return p;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (kind != null) {
            obj.addProperty("kind", kind);
        }
        if (objective != null) {
            obj.addProperty("objective", objective);
        }
        if (context != null) {
            obj.add("context", context.toJson());
        }
        if (expectedDeliverables != null) {
            JsonArray arr = new JsonArray();
            for (String s : expectedDeliverables) {
                arr.add(s);
            }
            obj.add("expectedDeliverables", arr);
        }
        if (acceptanceCriteria != null) {
            JsonArray arr = new JsonArray();
            for (String s : acceptanceCriteria) {
                arr.add(s);
            }
            obj.add("acceptanceCriteria", arr);
        }
        if (inlinePrompt != null) {
            obj.addProperty("inlinePrompt", inlinePrompt);
        }
        if (spilledPath != null) {
            obj.addProperty("spilledPath", spilledPath);
        }
        return obj;
    }

    public static class DirectiveContext {

        public String previousStep;
        public List<String> relatedFiles;

        public DirectiveContext() {
        }

        public static DirectiveContext fromJson(JsonObject obj) {
            DirectiveContext c = new DirectiveContext();
            if (obj.has("previousStep") && !obj.get("previousStep").isJsonNull()) {
                c.previousStep = obj.get("previousStep").getAsString();
            }
            if (obj.has("relatedFiles") && !obj.get("relatedFiles").isJsonNull()) {
                JsonArray arr = obj.get("relatedFiles").getAsJsonArray();
                c.relatedFiles = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    c.relatedFiles.add(el.getAsString());
                }
            }
            return c;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (previousStep != null) {
                obj.addProperty("previousStep", previousStep);
            }
            if (relatedFiles != null) {
                JsonArray arr = new JsonArray();
                for (String s : relatedFiles) {
                    arr.add(s);
                }
                obj.add("relatedFiles", arr);
            }
            return obj;
        }
    }
}
