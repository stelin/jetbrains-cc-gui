/**
 * Supervisor planning tool registry.
 *
 * Defines `emit_plan` — the tool the Supervisor LLM calls ONCE, on its first
 * turn for a task (signalled by a [PLANNING_REQUIRED] marker), to turn the
 * task into a structured, locked plan: an ordered list of steps, each carrying
 * acceptance criteria the supervisor will later verify real artifacts against.
 *
 * This replaces the old "synthetic, reactive" plan bootstrap (Java's
 * ActionRouter.ensurePlanAndStep, which fabricated one step per inject_prompt).
 * With a real upfront plan the PlanStateMachine has a forward route map, so
 * step-level resume ("continue from the first non-DONE step") finally works.
 *
 * Design contract (see docs/supervisor/supervisor-plan-generation-and-report-spill-design.md):
 *   - The plan STRUCTURES the externally-provided task; it does NOT invent the
 *     goal. Once emitted it is LOCKED — later changes go through emit_action
 *     (request_amendment), not a re-emit_plan.
 *   - Per-step acceptanceCriteria is the supervisor's review checklist; a main
 *     AI self-report is navigation only and never substitutes for checking files.
 *
 * Mirrors supervisor-tools.js conventions: schema fields are permissive and
 * cross-field requirements are enforced in normalizePlan() so the model can
 * self-correct via an error result rather than the SDK rejecting the call
 * before we ever see it. zod is injected (it lives in the SDK install dir, not
 * ai-bridge/node_modules) — see ../../utils/sdk-loader.js#loadZod.
 */

import { SUPERVISOR_MCP_NAME } from './supervisor-tools.js';

export const EMIT_PLAN_TOOL_NAME = 'emit_plan';
/** Fully-qualified tool name the API sees (mcp__<server>__<tool>). */
export const QUALIFIED_EMIT_PLAN = `mcp__${SUPERVISOR_MCP_NAME}__${EMIT_PLAN_TOOL_NAME}`;

/**
 * Build the zod input schema for emit_plan. `steps` is modelled permissively
 * (optional inner fields) so a slightly-malformed call reaches normalizePlan
 * and gets a corrective error instead of a hard SDK rejection.
 */
function buildEmitPlanSchema(z) {
    return {
        steps: z.array(z.object({
            title: z.string().describe('步骤标题（一句话，命令式）。'),
            owner: z.enum(['MAIN_AI', 'SUPERVISOR']).optional().describe(
                '该步执行者，默认 MAIN_AI（监督者派给主 AI 做）。'
            ),
            acceptanceCriteria: z.array(z.string()).optional().describe(
                '该步验收标准（可多条）。你之后据此 Read 真实产物逐条核验——'
                + '不是主 AI 自述说做了就算。尽量可检验（文件/函数/命令/行为）。'
            ),
        })).describe(
            '结构化步骤列表，按执行顺序。把外部任务拆解为可执行步骤，**不要发明目标**。'
        ),
        rationale: z.string().optional().describe(
            '可选：拆解思路（1-3 句），解释为什么这样分步。'
        ),
    };
}

/**
 * Normalize + cross-field-validate the emit_plan input into the wrapper the
 * Java side (PlanStateMachine.onPlanCreated) consumes.
 *
 * @returns {{ plan: {steps: Array, rationale: string}, error: string|null }}
 */
export function normalizePlan(args) {
    let error = null;
    const rationale = typeof args.rationale === 'string' ? args.rationale : '';
    const rawSteps = Array.isArray(args.steps) ? args.steps : [];

    if (rawSteps.length === 0) {
        return { plan: { steps: [], rationale }, error: 'emit_plan requires a non-empty `steps` array' };
    }

    const steps = [];
    for (let i = 0; i < rawSteps.length; i++) {
        const s = rawSteps[i] || {};
        const title = typeof s.title === 'string' ? s.title.trim() : '';
        if (!title) {
            error = `step #${i} is missing a non-empty \`title\``;
            break;
        }
        const owner = s.owner === 'SUPERVISOR' ? 'SUPERVISOR' : 'MAIN_AI';
        const acceptanceCriteria = Array.isArray(s.acceptanceCriteria)
            ? s.acceptanceCriteria.filter((c) => typeof c === 'string' && c.trim().length > 0)
            : [];
        steps.push({ index: i, title, owner, acceptanceCriteria });
    }

    return { plan: { steps, rationale }, error };
}

/**
 * Build the emit_plan SDK tool. The supplied onCapturePlan callback receives the
 * normalized {steps, rationale} wrapper on every successful call. Returns the
 * tool object so the caller can add it to the shared supervisor MCP server.
 *
 * @param {object} sdk - resolved @anthropic-ai/claude-agent-sdk module
 * @param {object} z   - resolved zod `z` API
 * @param {(plan: object) => void} onCapturePlan
 */
export function buildEmitPlanTool(sdk, z, onCapturePlan) {
    return sdk.tool(
        EMIT_PLAN_TOOL_NAME,
        'Emit the structured plan for the current task. Call this EXACTLY ONCE, '
        + 'on your first turn after a [PLANNING_REQUIRED] marker, BEFORE any '
        + 'emit_action. Break the task into ordered steps, each with acceptance '
        + 'criteria you will later verify. The plan is then LOCKED — change it '
        + 'later via emit_action(request_amendment), not by calling emit_plan again.',
        buildEmitPlanSchema(z),
        async (args) => {
            const result = normalizePlan(args);
            if (result.error) {
                return {
                    isError: true,
                    content: [{
                        type: 'text',
                        text: `Validation error: ${result.error}. Call emit_plan again with corrected steps.`,
                    }],
                };
            }
            try { onCapturePlan(result.plan); } catch { /* best-effort capture */ }
            return {
                content: [{
                    type: 'text',
                    text: `Plan recorded (${result.plan.steps.length} steps). It is now locked; `
                        + `proceed to supervise. Use emit_action(request_amendment) if it must change.`,
                }],
            };
        }
    );
}
