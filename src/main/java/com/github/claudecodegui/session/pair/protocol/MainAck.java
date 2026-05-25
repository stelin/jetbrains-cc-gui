package com.github.claudecodegui.session.pair.protocol;

import com.google.gson.JsonObject;

/** Main-AI ack of a supervisor directive. {@code type} is always "main_ack"; {@code status}: received|applied|failed. */
public class MainAck {

    public String type;
    public String sessionId;
    public String directiveId;
    public long ts;
    public String status;
    public MainAckDetails details;

    public MainAck() {
    }

    public static MainAck fromJson(JsonObject obj) {
        MainAck a = new MainAck();
        if (obj.has("type") && !obj.get("type").isJsonNull()) {
            a.type = obj.get("type").getAsString();
        }
        if (obj.has("sessionId") && !obj.get("sessionId").isJsonNull()) {
            a.sessionId = obj.get("sessionId").getAsString();
        }
        if (obj.has("directiveId") && !obj.get("directiveId").isJsonNull()) {
            a.directiveId = obj.get("directiveId").getAsString();
        }
        if (obj.has("ts") && !obj.get("ts").isJsonNull()) {
            a.ts = obj.get("ts").getAsLong();
        }
        if (obj.has("status") && !obj.get("status").isJsonNull()) {
            a.status = obj.get("status").getAsString();
        }
        if (obj.has("details") && !obj.get("details").isJsonNull()) {
            a.details = MainAckDetails.fromJson(obj.get("details").getAsJsonObject());
        }
        return a;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (type != null) {
            obj.addProperty("type", type);
        }
        if (sessionId != null) {
            obj.addProperty("sessionId", sessionId);
        }
        if (directiveId != null) {
            obj.addProperty("directiveId", directiveId);
        }
        obj.addProperty("ts", ts);
        if (status != null) {
            obj.addProperty("status", status);
        }
        if (details != null) {
            obj.add("details", details.toJson());
        }
        return obj;
    }

    public static class MainAckDetails {

        public String reason;
        public boolean willRetry;

        public MainAckDetails() {
        }

        public static MainAckDetails fromJson(JsonObject obj) {
            MainAckDetails d = new MainAckDetails();
            if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
                d.reason = obj.get("reason").getAsString();
            }
            if (obj.has("willRetry") && !obj.get("willRetry").isJsonNull()) {
                d.willRetry = obj.get("willRetry").getAsBoolean();
            }
            return d;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (reason != null) {
                obj.addProperty("reason", reason);
            }
            obj.addProperty("willRetry", willRetry);
            return obj;
        }
    }
}
