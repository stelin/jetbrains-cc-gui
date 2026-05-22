/**
 * Supervisor tool registry.
 *
 * Defines the single tool the Supervisor LLM uses to emit an ACTION decision.
 * Using a tool (instead of a free-form ```ACTION JSON code block) makes the
 * schema enforced by the Anthropic API: tool_use inputs are validated against
 * the Zod-derived JSON Schema server-side, so the model cannot deliver
 * malformed JSON to us. This eliminates the
 * `(downgraded) invalid JSON in ACTION block` dead-lock path entirely.
 *
 * Flow per turn:
 *   1. SDK invokes the Anthropic API; model decides to call emit_action.
 *   2. The SDK invokes the handler defined here; we capture the validated
 *      action into the runtime via the onCapture callback.
 *   3. Handler returns a short ack so the model knows the call landed and
 *      can finish its turn.
 *   4. supervisor-channel reads runtime.lastCapturedAction after the turn
 *      ends and emits the SUPERVISOR_ACTION line to Java.
 *
 * If the handler's cross-field validation fails (e.g. inject_prompt with no
 * prompt), we return isError: true with a corrective hint; the model sees
 * the error and re-calls emit_action with the missing field.
 */

// zod is not statically imported here because it lives in the Claude SDK
// install dir (~/.codemoss/dependencies/claude-sdk/node_modules/zod), not in
// ai-bridge/node_modules. The caller passes a resolved zod module into
// buildSupervisorMcpServer; see ../../utils/sdk-loader.js#loadZod.

export const SUPERVISOR_MCP_NAME = 'supervisor';
export const EMIT_ACTION_TOOL_NAME = 'emit_action';
/** Fully-qualified tool name the API sees (mcp__<server>__<tool>). */
export const QUALIFIED_EMIT_ACTION = `mcp__${SUPERVISOR_MCP_NAME}__${EMIT_ACTION_TOOL_NAME}`;

const ACTION_TYPES = [
    'inject_prompt',
    'retry_with_hint',
    'approve_and_continue',
    'escalate_to_human',
    'request_amendment',
    'wait',
];

/**
 * Build the Zod input schema given an injected zod module. All payload
 * fields are optional at the schema level; cross-field requirements are
 * enforced in {@link normalizeAction} so the model can self-correct via the
 * error path rather than having the SDK reject the call before we see it.
 */
function buildEmitActionSchema(z) {
    return {
        action: z.enum(ACTION_TYPES).describe(
            'Action type. Determines which other fields are required.'
        ),
        reason: z.string().optional().describe(
            'Short rationale (1-2 sentences) explaining this decision.'
        ),
        prompt: z.string().optional().describe(
            'Required when action is inject_prompt or retry_with_hint. The message injected into the main AI as a fake-user input.'
        ),
        wait_seconds: z.number().optional().describe(
            'Optional delay in seconds before injecting prompt. Only honored for retry_with_hint.'
        ),
        question: z.string().optional().describe(
            'Required when action is escalate_to_human. The question shown in the human decision dialog.'
        ),
        choices: z.array(z.string()).optional().describe(
            'Optional choice list shown alongside the escalation question.'
        ),
        context_files: z.array(z.string()).optional().describe(
            'Optional file paths attached to the escalation as context.'
        ),
        proposal: z.string().optional().describe(
            'Used when action is request_amendment. The proposed plan change.'
        ),
        mark_step_complete: z.number().optional().describe(
            'Used when action is approve_and_continue. Step index to mark as done.'
        ),
    };
}

/**
 * Normalize the validated tool input into the {action, reason, payload}
 * wrapper that {@code ActionRouter.java} consumes. Performs cross-field
 * validation that Zod cannot express directly.
 *
 * @returns {{ action: {action: string, reason: string, payload: object}, error: string|null }}
 */
export function normalizeAction(args) {
    const action = args.action;
    const reason = typeof args.reason === 'string' ? args.reason : '';
    const payload = {};
    let error = null;

    switch (action) {
        case 'inject_prompt':
            if (typeof args.prompt !== 'string' || args.prompt.length === 0) {
                error = 'inject_prompt requires non-empty `prompt`';
            } else {
                payload.prompt = args.prompt;
            }
            break;
        case 'retry_with_hint':
            if (typeof args.prompt !== 'string') {
                error = 'retry_with_hint requires `prompt`';
            } else {
                payload.prompt = args.prompt;
            }
            if (typeof args.wait_seconds === 'number') {
                payload.wait_seconds = args.wait_seconds;
            }
            break;
        case 'escalate_to_human':
            if (typeof args.question !== 'string' || args.question.length === 0) {
                error = 'escalate_to_human requires non-empty `question`';
            } else {
                payload.question = args.question;
            }
            if (Array.isArray(args.choices)) payload.choices = args.choices;
            if (Array.isArray(args.context_files)) payload.context_files = args.context_files;
            break;
        case 'request_amendment':
            if (typeof args.proposal === 'string') payload.proposal = args.proposal;
            break;
        case 'approve_and_continue':
            if (typeof args.mark_step_complete === 'number') {
                payload.mark_step_complete = args.mark_step_complete;
            }
            break;
        case 'wait':
        default:
            break;
    }

    return {
        action: { action, reason, payload },
        error,
    };
}

/**
 * Build the in-process MCP server that exposes emit_action. The supplied
 * onCapture callback receives the {action, reason, payload} wrapper on every
 * successful call.
 *
 * @param {object} sdk - resolved @anthropic-ai/claude-agent-sdk module
 * @param {object} zod - resolved zod module (loaded via sdk-loader.loadZod())
 * @param {(action: object) => void} onCapture
 * @returns {object} mcp server config compatible with the query() option
 */
export function buildSupervisorMcpServer(sdk, zod, onCapture) {
    if (typeof sdk?.createSdkMcpServer !== 'function' || typeof sdk?.tool !== 'function') {
        throw new Error('Claude Agent SDK does not expose createSdkMcpServer/tool — please upgrade to >= 0.2.0');
    }
    const z = zod?.z ?? zod?.default?.z ?? zod;
    if (typeof z?.enum !== 'function' || typeof z?.string !== 'function') {
        throw new Error('zod module did not expose the expected z.* API');
    }

    const emitActionTool = sdk.tool(
        EMIT_ACTION_TOOL_NAME,
        'Emit your final ACTION decision for this turn. Call exactly once per turn; after a successful call, your turn is complete and you must not emit further text or tool calls.',
        buildEmitActionSchema(z),
        async (args) => {
            const result = normalizeAction(args);
            if (result.error) {
                return {
                    isError: true,
                    content: [{
                        type: 'text',
                        text: `Validation error: ${result.error}. Call emit_action again with the missing field.`,
                    }],
                };
            }
            try { onCapture(result.action); } catch { /* best-effort capture */ }
            return {
                content: [{ type: 'text', text: 'ACTION recorded. Turn complete.' }],
            };
        }
    );

    return sdk.createSdkMcpServer({
        name: SUPERVISOR_MCP_NAME,
        version: '1.0.0',
        tools: [emitActionTool],
    });
}
