/**
 * Supervisor durable-state tool: `update_state`.
 *
 * Lets the supervisor append sparse deltas to its pair's L2 memory — a decision
 * log entry (A/B-level self-decision留痕) or a newly-discovered hard constraint.
 * Emits a `[STATE_UPDATE] { delta: {...} }` line; Java's
 * {@code L2Store.applyUpdateStateDelta} merges it (atomic write + ring trim).
 *
 * Wired into the LOCAL daemon for the first time (2026-06-10) — previously only
 * the remote ai-bridge-server exposed it, so local supervisors couldn't log
 * decisions. Deliberately does NOT expose {@code planProgressDelta} /
 * {@code anchoredFactsDelta}: plan structure + progress are PROJECTED from the
 * plan the supervisor emits via emit_plan (single source of truth), so they are
 * read-only to the supervisor here. See
 * docs/supervisor/supervisor-plan-generation-and-report-spill-design.md (D5).
 *
 * The handler writes the line directly (fire-and-forget, may be called multiple
 * times per turn). It runs inside the active postEvent turn, so the daemon wraps
 * its stdout with the active request id and Java routes it to the right pair.
 */

import { SUPERVISOR_MCP_NAME } from './supervisor-tools.js';

export const UPDATE_STATE_TOOL_NAME = 'update_state';
/** Fully-qualified tool name the API sees (mcp__<server>__<tool>). */
export const QUALIFIED_UPDATE_STATE = `mcp__${SUPERVISOR_MCP_NAME}__${UPDATE_STATE_TOOL_NAME}`;

function buildSchema(z) {
    return {
        decisionAppend: z.object({
            action: z.string().optional().describe('决策类型，如 inject_prompt / approve / replan。'),
            reason: z.string().optional().describe('一句话原因。'),
            category: z.string().optional().describe('A | B（C 级必须 escalate，不在此记录）。'),
            severity: z.string().optional().describe('info | warn | alert。'),
            step: z.number().optional().describe('相关步骤序号。'),
        }).optional().describe('追加一条自决记录到 L2 recentDecisions（A/B 级自决留痕）。'),
        constraintAdd: z.string().optional().describe('追加一条新发现的硬约束到 L2 knownConstraints。'),
    };
}

/**
 * Build the update_state SDK tool. No capture callback — the handler writes the
 * [STATE_UPDATE] line itself. Returns the tool object so the caller can add it to
 * the shared supervisor MCP server.
 *
 * @param {object} sdk - resolved @anthropic-ai/claude-agent-sdk module
 * @param {object} z   - resolved zod `z` API
 */
export function buildUpdateStateTool(sdk, z) {
    return sdk.tool(
        UPDATE_STATE_TOOL_NAME,
        'Append a sparse durable-state delta (decision log entry / hard constraint) '
        + 'to this pair\'s L2 memory. Plan structure & progress are NOT writable here '
        + '— they are projected from the plan you produce via emit_plan. Safe to call '
        + 'multiple times per turn.',
        buildSchema(z),
        async (args) => {
            const delta = {};
            if (args && args.decisionAppend && typeof args.decisionAppend === 'object') {
                delta.decisionAppend = args.decisionAppend;
            }
            if (args && typeof args.constraintAdd === 'string' && args.constraintAdd.trim()) {
                delta.constraintAdd = args.constraintAdd.trim();
            }
            if (Object.keys(delta).length === 0) {
                return { content: [{ type: 'text', text: 'no-op (empty delta)' }] };
            }
            try {
                process.stdout.write('[STATE_UPDATE] ' + JSON.stringify({ delta }) + '\n');
            } catch (_) { /* stdout closed */ }
            return { content: [{ type: 'text', text: 'state updated' }] };
        }
    );
}
