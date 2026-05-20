package com.github.claudecodegui.path;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Manifest of every JSON field that carries a filesystem path, indexed by
 * message type. Used by {@link PathFieldVisitor} to translate paths in
 * outbound requests and inbound events.
 *
 * <p>Path-expression syntax (a tiny JSONPath subset):
 * <ul>
 *   <li>{@code $.a.b.c}      — descend object keys</li>
 *   <li>{@code $.a[*].b}     — iterate every element of an array, then descend</li>
 *   <li>{@code $.a.*}        — translate every <em>key</em> of a map (used for
 *       {@code openedFiles} where keys are paths)</li>
 * </ul>
 *
 * <p>New path-bearing fields must be registered here — there is intentionally
 * no JSON-walking heuristic to avoid false positives in free text.
 */
public final class PathFields {

    /** Outbound: method name → path expressions to translate before sending. */
    public static final Map<String, List<String>> OUTBOUND = Map.ofEntries(
        entry("__session_create__", List.of("$.projectPath")),

        entry("claude.send", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH",
            "$.params.openedFiles.*",
            "$.params.attachments[*].path"
        )),
        entry("claude.sendWithAttachments", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH",
            "$.params.attachments[*].path"
        )),
        entry("claude.preconnect", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH"
        )),
        entry("claude.resetRuntime",       List.of("$.params.cwd")),
        entry("claude.rewindFiles",        List.of("$.params.cwd")),
        entry("claude.getMcpServerStatus", List.of("$.params.cwd")),

        entry("codex.send", List.of(
            "$.params.cwd",
            "$.params.env.IDEA_PROJECT_PATH",
            "$.params.env.PROJECT_PATH"
        ))
    );

    /** Inbound: event tag → path expressions to translate after receiving. */
    public static final Map<String, List<String>> INBOUND = Map.ofEntries(
        entry("daemon.ready", List.of("$.cwd")),

        entry("_ctrl.permission_request", List.of(
            "$.cwd",
            "$.inputs.file_path",
            "$.inputs.path",
            "$.inputs.filePath",
            "$.inputs.dir",
            "$.inputs.paths[*]",
            "$.inputs.files[*]"
        )),
        entry("_ctrl.ask_user_question_request", List.of("$.cwd")),
        entry("_ctrl.plan_approval_request",     List.of("$.cwd")),

        // Used by ClaudeMessageHandler when processing content[].type=='tool_use'.
        // Bash.command is intentionally NOT translated (free-text rule).
        entry("__tool_use_input__", List.of(
            "$.file_path",
            "$.path",
            "$.filePath",
            "$.notebook_path",
            "$.dir",
            "$.paths[*]",
            "$.files[*]",
            "$.cwd"
        )),

        // History JSONL — applied per line after JSON parsing.
        entry("__history_line__", List.of(
            "$.cwd",
            "$.message.content[*].input.file_path",
            "$.message.content[*].input.path",
            "$.message.content[*].input.filePath",
            "$.message.content[*].input.notebook_path",
            "$.message.content[*].input.paths[*]",
            "$.message.content[*].input.files[*]",
            "$.message.content[*].input.cwd"
        ))
    );

    private PathFields() {}
}
