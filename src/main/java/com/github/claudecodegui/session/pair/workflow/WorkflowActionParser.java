package com.github.claudecodegui.session.pair.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure parser for the {@code complete_workflow_node} emit_action payload
 * (coding-plan §7.1 / §12.1). Lives in the workflow package so the
 * {@code ActionRouter} case stays a thin call and the parse logic is unit-testable
 * headlessly.
 *
 * <p>Daemon payload shapes (from {@code supervisor-tools.js} normalizeAction):
 * <ul>
 *   <li>done: {@code {node_status:"done", summary?, changed_files?}}</li>
 *   <li>blocked: {@code {node_status:"blocked", summary?}}</li>
 * </ul>
 * Missing/blank {@code node_status} defaults to {@code done} (matches §12.1).
 */
public final class WorkflowActionParser {

    private WorkflowActionParser() {
    }

    /** Parsed, normalized view of a {@code complete_workflow_node} payload. */
    public static final class ParsedNodeReport {
        /** {@link NodeStatus#DONE} or {@link NodeStatus#WAITING_HUMAN}. */
        public final NodeStatus status;
        /** Completion / blocked说明; never null (empty string when absent). */
        public final String summary;
        /** done: files this node changed (never null, possibly empty); blocked: null. */
        public final List<String> changedFiles;

        ParsedNodeReport(NodeStatus status, String summary, List<String> changedFiles) {
            this.status = status;
            this.summary = summary;
            this.changedFiles = changedFiles;
        }
    }

    public static ParsedNodeReport parse(JsonObject payload) {
        String st = strField(payload, "node_status", "done");
        String summary = strField(payload, "summary", "");

        if ("blocked".equals(st)) {
            return new ParsedNodeReport(NodeStatus.WAITING_HUMAN, summary, null);
        }
        // done (default): collect changed_files if present.
        List<String> files = new ArrayList<>();
        if (payload != null && payload.has("changed_files") && payload.get("changed_files").isJsonArray()) {
            JsonArray arr = payload.getAsJsonArray("changed_files");
            for (JsonElement el : arr) {
                if (el != null && !el.isJsonNull()) {
                    files.add(el.getAsString());
                }
            }
        }
        return new ParsedNodeReport(NodeStatus.DONE, summary, Collections.unmodifiableList(files));
    }

    private static String strField(JsonObject payload, String field, String fallback) {
        if (payload != null && payload.has(field) && !payload.get(field).isJsonNull()) {
            return payload.get(field).getAsString();
        }
        return fallback;
    }
}
