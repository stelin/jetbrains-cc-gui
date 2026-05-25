package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Evidence backing a supervisor decision. Legal {@code kind} values: file_read | subagent | main_turn | verification. */
public class Evidence {

    public String kind;
    public String path;
    public String lines;
    public String agentId;
    public String turnId;
    public String output;

    public Evidence() {
    }

    public static Evidence fromJson(JsonObject obj) {
        Evidence e = new Evidence();
        if (obj.has("kind") && !obj.get("kind").isJsonNull()) {
            e.kind = obj.get("kind").getAsString();
        }
        if (obj.has("path") && !obj.get("path").isJsonNull()) {
            e.path = obj.get("path").getAsString();
        }
        if (obj.has("lines") && !obj.get("lines").isJsonNull()) {
            e.lines = obj.get("lines").getAsString();
        }
        if (obj.has("agentId") && !obj.get("agentId").isJsonNull()) {
            e.agentId = obj.get("agentId").getAsString();
        }
        if (obj.has("turnId") && !obj.get("turnId").isJsonNull()) {
            e.turnId = obj.get("turnId").getAsString();
        }
        if (obj.has("output") && !obj.get("output").isJsonNull()) {
            e.output = obj.get("output").getAsString();
        }
        return e;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (kind != null) {
            obj.addProperty("kind", kind);
        }
        if (path != null) {
            obj.addProperty("path", path);
        }
        if (lines != null) {
            obj.addProperty("lines", lines);
        }
        if (agentId != null) {
            obj.addProperty("agentId", agentId);
        }
        if (turnId != null) {
            obj.addProperty("turnId", turnId);
        }
        if (output != null) {
            obj.addProperty("output", output);
        }
        return obj;
    }
}
