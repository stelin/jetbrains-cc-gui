package com.github.claudecodegui.permission;

import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * Handles {@code _ctrl} control messages emitted by the remote daemon over SSE.
 *
 * <p>Three message kinds need user interaction:
 * <ul>
 *   <li>{@code permission_request} — Edit/Bash/etc. tool requesting permission</li>
 *   <li>{@code ask_user_question_request} — AskUserQuestion tool</li>
 *   <li>{@code plan_approval_request} — ExitPlanMode tool</li>
 * </ul>
 *
 * <p>The handler renders the appropriate UI (typically the same dialogs as
 * local mode), then calls {@code replySender} with the response JSON envelope.
 * The bridge POSTs that JSON back to {@code /session/{id}/in}.
 *
 * <p>Local mode does not use this contract: file IPC under
 * {@code ~/.claude/permissions/} drives the existing
 * {@code PermissionRequestWatcher} → {@code PermissionService} flow.
 */
public interface ControlMessageHandler {

    /**
     * @param action       one of {@code permission_request},
     *                     {@code ask_user_question_request},
     *                     {@code plan_approval_request}
     * @param request      raw JSON envelope from the daemon (contains
     *                     {@code requestId}, {@code action}, plus action-specific
     *                     fields like {@code toolName}, {@code inputs}, {@code cwd},
     *                     {@code questions}, or {@code plan})
     * @param replySender  callback to deliver the response envelope back to the
     *                     daemon. The handler is responsible for constructing
     *                     {@code {type:"_ctrl",action:"*_response",requestId,...}}
     */
    void onRequest(String action, JsonObject request, Consumer<JsonObject> replySender);
}
