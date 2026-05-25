package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Main-AI subagent stop event. {@code type} is always "subagent_stop". */
public class SubagentStop {

    public String type;
    public String sessionId;
    public String turnId;
    public long ts;
    public SubagentStopPayload payload;

    public SubagentStop() {
    }

    public static SubagentStop fromJson(JsonObject obj) {
        SubagentStop s = new SubagentStop();
        if (obj.has("type") && !obj.get("type").isJsonNull()) {
            s.type = obj.get("type").getAsString();
        }
        if (obj.has("sessionId") && !obj.get("sessionId").isJsonNull()) {
            s.sessionId = obj.get("sessionId").getAsString();
        }
        if (obj.has("turnId") && !obj.get("turnId").isJsonNull()) {
            s.turnId = obj.get("turnId").getAsString();
        }
        if (obj.has("ts") && !obj.get("ts").isJsonNull()) {
            s.ts = obj.get("ts").getAsLong();
        }
        if (obj.has("payload") && !obj.get("payload").isJsonNull()) {
            s.payload = SubagentStopPayload.fromJson(obj.get("payload").getAsJsonObject());
        }
        return s;
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
        obj.addProperty("ts", ts);
        if (payload != null) {
            obj.add("payload", payload.toJson());
        }
        return obj;
    }

    public static class SubagentStopPayload {

        public String agentId;
        public String agentType;
        public String taskSubject;
        public String lastAssistantMessage;
        public String transcriptPath;
        public String parentToolUseId;
        public long durationMs;

        public SubagentStopPayload() {
        }

        public static SubagentStopPayload fromJson(JsonObject obj) {
            SubagentStopPayload p = new SubagentStopPayload();
            if (obj.has("agentId") && !obj.get("agentId").isJsonNull()) {
                p.agentId = obj.get("agentId").getAsString();
            }
            if (obj.has("agentType") && !obj.get("agentType").isJsonNull()) {
                p.agentType = obj.get("agentType").getAsString();
            }
            if (obj.has("taskSubject") && !obj.get("taskSubject").isJsonNull()) {
                p.taskSubject = obj.get("taskSubject").getAsString();
            }
            if (obj.has("lastAssistantMessage") && !obj.get("lastAssistantMessage").isJsonNull()) {
                p.lastAssistantMessage = obj.get("lastAssistantMessage").getAsString();
            }
            if (obj.has("transcriptPath") && !obj.get("transcriptPath").isJsonNull()) {
                p.transcriptPath = obj.get("transcriptPath").getAsString();
            }
            if (obj.has("parentToolUseId") && !obj.get("parentToolUseId").isJsonNull()) {
                p.parentToolUseId = obj.get("parentToolUseId").getAsString();
            }
            if (obj.has("durationMs") && !obj.get("durationMs").isJsonNull()) {
                p.durationMs = obj.get("durationMs").getAsLong();
            }
            return p;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (agentId != null) {
                obj.addProperty("agentId", agentId);
            }
            if (agentType != null) {
                obj.addProperty("agentType", agentType);
            }
            if (taskSubject != null) {
                obj.addProperty("taskSubject", taskSubject);
            }
            if (lastAssistantMessage != null) {
                obj.addProperty("lastAssistantMessage", lastAssistantMessage);
            }
            if (transcriptPath != null) {
                obj.addProperty("transcriptPath", transcriptPath);
            }
            if (parentToolUseId != null) {
                obj.addProperty("parentToolUseId", parentToolUseId);
            }
            obj.addProperty("durationMs", durationMs);
            return obj;
        }
    }
}
