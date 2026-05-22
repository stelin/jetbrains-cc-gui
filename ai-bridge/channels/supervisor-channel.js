/**
 * Supervisor Channel.
 *
 * Runs a parallel Claude SDK Query session that observes the main AI's event
 * stream (forwarded from Java) and emits structured ACTION decisions via the
 * mcp__supervisor__emit_action tool — see ../services/supervisor/supervisor-tools.js.
 *
 * Protocol (daemon NDJSON):
 *   - supervisor.start   { pairId, supervisorId, name, description, planContent,
 *                          specContent?, model?, allowedTools? }
 *   - supervisor.postEvent { pairId, supervisorId, event: {...} }
 *   - supervisor.stop    { pairId, supervisorId }
 *
 * For each postEvent, this channel:
 *   1. Summarizes the event (see event-summarizer.js)
 *   2. Enqueues it as a user message in the persistent input stream
 *   3. Iterates the SDK query result until turn_end. The model calls
 *      emit_action, which captures the validated action onto the runtime.
 *   4. Writes a single `{ id, type: 'supervisor_action', ... }` NDJSON line
 *      back to Java.
 *
 * Each pair_supervisor combination holds its own runtime; closing it disposes
 * the SDK query and removes the entry.
 */

import { loadClaudeSdk, loadZod, isClaudeSdkAvailable } from '../utils/sdk-loader.js';
import { AsyncStream } from '../utils/async-stream.js';
import { summarizeEvent } from '../services/supervisor/event-summarizer.js';
import {
    buildSupervisorMcpServer,
    QUALIFIED_EMIT_ACTION,
    SUPERVISOR_MCP_NAME,
} from '../services/supervisor/supervisor-tools.js';

const DEFAULT_MODEL = 'claude-haiku-4-5-20251001';

/** @type {Map<string, SupervisorRuntime>} */
const runtimes = new Map();

class SupervisorRuntime {
    constructor({ pairId, supervisorId, name, model, systemPrompt, allowedTools }) {
        this.pairId = pairId;
        this.supervisorId = supervisorId;
        this.name = name || supervisorId;
        this.model = model || DEFAULT_MODEL;
        this.systemPrompt = systemPrompt;
        this.allowedTools = Array.isArray(allowedTools) ? allowedTools : [];
        this.inputStream = new AsyncStream();
        /** @type {AsyncGenerator | null} */
        this.query = null;
        this.disposed = false;
        /** Serialize concurrent postEvent calls to avoid interleaved turns. */
        this.busy = Promise.resolve();
        /**
         * Filled by the emit_action tool handler during the current turn.
         * Reset at the start of each postEvent and read after the turn ends.
         * @type {{action: string, reason: string, payload: object} | null}
         */
        this.lastCapturedAction = null;
    }
}

function key(pairId, supervisorId) {
    return `${pairId}:${supervisorId}`;
}

/**
 * Compose the system prompt for the Supervisor LLM. Always emits the
 * tool-based action contract; the agent's `description` field is the
 * user-defined persona portion.
 */
function buildSystemPrompt({ name, description, planContent, specContent }) {
    const hasPlan = planContent && planContent.trim().length > 0;
    const sections = [
        description?.trim() || `你是名为 ${name} 的 Supervisor。`,
    ];

    if (hasPlan) {
        sections.push(
            '',
            '# 当前任务方案（plan.md，已锁定，不可修改）',
            planContent.trim()
        );
    } else {
        sections.push(
            '',
            '# 当前任务方案',
            '尚未提供。等待用户在右侧输入框给出任务描述。',
            '在用户提供任务前，**保持沉默**——除非收到 user_input 事件，否则调用 emit_action 时使用 action="wait"。'
        );
    }

    if (specContent && specContent.trim()) {
        sections.push(
            '',
            '# 项目适用规范 / 技能包',
            '以下是当前项目启用的规范包。主 AI 每次产出后，把这些当作 review 检查项；',
            '违反时立即通过 emit_action(action="inject_prompt") 反馈具体违反点（引用规范名 + 文件:行号）。',
            '',
            specContent.trim()
        );
    }

    sections.push(
        '',
        '# 输出格式（强约束）',
        '每一轮决策必须：',
        '1) 先输出简短自然语言段：用 💭/✓/⚠️/⚡/→ 等符号描述观察、判断、决策（可省略）。',
        '2) **必须调用 `emit_action` 工具**结束本轮。一轮只能调用一次；调用成功后立即结束本轮。',
        '',
        '`emit_action` 字段说明：',
        '- action: 必填，枚举 inject_prompt / retry_with_hint / approve_and_continue / escalate_to_human / request_amendment / wait',
        '- reason: 强烈建议，1-2 句决策原因',
        '- inject_prompt / retry_with_hint 需要 prompt（注入给主 AI 的内容）',
        '- retry_with_hint 可选 wait_seconds（延迟秒数）',
        '- escalate_to_human 需要 question，可选 choices / context_files',
        '- request_amendment 建议带 proposal',
        '- approve_and_continue 可选 mark_step_complete（步骤序号）',
        '- wait 无额外字段',
        '',
        '若不确定下一步，调用 `emit_action(action="wait")`——**绝不可以只输出文字不调用工具**。',
        '',
        '# 行为约束',
        '- 方案 plan.md 是标准答案。主 AI 不能擅自偏离；偏离时升级用户（escalate_to_human）或要求修正（inject_prompt）。',
        '- 一轮只调用一次 emit_action。',
        '- 不要执行其它工具——你只有 emit_action 一个工具可用。'
    );

    return sections.join('\n');
}

/**
 * Start a new supervisor session.
 */
export async function startSupervisorSession(params) {
    if (!isClaudeSdkAvailable()) {
        throw new Error('Claude SDK not installed; supervisor cannot start.');
    }

    const {
        pairId,
        supervisorId,
        name,
        description,
        planContent,
        specContent,
        model,
        allowedTools,
    } = params || {};

    if (!pairId || !supervisorId) {
        throw new Error('supervisor.start requires pairId and supervisorId');
    }

    const k = key(pairId, supervisorId);
    if (runtimes.has(k)) {
        // Idempotent: if already alive, return early.
        process.stdout.write(`[supervisor] session already running: ${k}\n`);
        return { alreadyRunning: true };
    }

    const systemPrompt = buildSystemPrompt({ name, description, planContent, specContent });

    const [sdk, zod] = await Promise.all([loadClaudeSdk(), loadZod()]);
    const queryFn = sdk?.query;
    if (typeof queryFn !== 'function') {
        throw new Error('Claude SDK does not expose query() function');
    }

    const runtime = new SupervisorRuntime({
        pairId,
        supervisorId,
        name,
        model,
        systemPrompt,
        allowedTools,
    });

    // Build the in-process MCP server. The handler captures the validated
    // action onto the runtime; collectAssistantTurn reads it after the turn.
    const supervisorMcpServer = buildSupervisorMcpServer(sdk, zod, (action) => {
        runtime.lastCapturedAction = action;
    });

    // Allow only emit_action by default; callers may opt-in to extra tools.
    const allowedToolList = [QUALIFIED_EMIT_ACTION, ...runtime.allowedTools];

    // SDK options. Supervisor judgment-only: no project-scoped settings, no
    // file checkpointing. We do still pass a cwd because the SDK requires one.
    const cwd = process.env.IDEA_PROJECT_PATH || process.env.PROJECT_PATH || process.cwd();
    runtime.query = queryFn({
        prompt: runtime.inputStream,
        options: {
            cwd,
            model: runtime.model,
            maxTurns: 100,
            // The SDK accepts a string-or-object systemPrompt. Use a string here so the
            // claude_code preset is NOT activated — Supervisor must obey OUR persona,
            // not Claude Code's default agent instructions.
            systemPrompt: runtime.systemPrompt,
            mcpServers: { [SUPERVISOR_MCP_NAME]: supervisorMcpServer },
            allowedTools: allowedToolList,
            // Defensive allowlist: pre-approve emit_action, deny everything else
            // even if it slips into allowedTools by mistake.
            canUseTool: async (toolName) => {
                if (toolName === QUALIFIED_EMIT_ACTION) {
                    return { behavior: 'allow' };
                }
                if (runtime.allowedTools.includes(toolName)) {
                    return { behavior: 'allow' };
                }
                return {
                    behavior: 'deny',
                    message: `Supervisor sessions may only call ${QUALIFIED_EMIT_ACTION}.`,
                };
            },
        },
    });

    runtimes.set(k, runtime);
    process.stdout.write(`[supervisor] started: ${k} (model=${runtime.model})\n`);
    return { started: true, key: k };
}

/**
 * Post an event to the supervisor; wait for its next ACTION; emit a NDJSON
 * line tagged with the current request id, so Java can demux the response.
 *
 * The returned promise resolves once the supervisor turn ends or fails.
 */
export async function postEventToSupervisor(params) {
    const { pairId, supervisorId, event } = params || {};
    if (!pairId || !supervisorId) {
        throw new Error('supervisor.postEvent requires pairId and supervisorId');
    }

    const runtime = runtimes.get(key(pairId, supervisorId));
    if (!runtime || runtime.disposed) {
        // Stable prefix + code: Java EventBus matches on this to trigger a lazy
        // supervisor.start + retry after the daemon process has been restarted
        // (its in-memory `runtimes` Map is empty on a fresh process, but the
        // Java-side PairSession still thinks the supervisor is alive).
        const err = new Error(
            `SUPERVISOR_NOT_FOUND supervisor session not found or disposed: ${pairId}:${supervisorId}`
        );
        err.code = 'SUPERVISOR_NOT_FOUND';
        throw err;
    }

    // Serialize per-runtime so concurrent postEvent calls don't interleave turns.
    const prev = runtime.busy;
    let release;
    runtime.busy = new Promise((resolve) => { release = resolve; });

    try {
        await prev;
        const summary = summarizeEvent(event);

        // Reset per-turn capture before enqueueing the next user message.
        runtime.lastCapturedAction = null;

        // Enqueue the summarized event as a user message in the SDK input stream.
        // Schema must match what the Claude Agent SDK expects (see persistent-query-service.js).
        runtime.inputStream.enqueue({
            type: 'user',
            session_id: '',
            parent_tool_use_id: null,
            message: {
                role: 'user',
                content: [{ type: 'text', text: summary }],
            },
        });

        const turn = await collectAssistantTurn(runtime);

        const wrapper = buildActionWrapper({
            pairId,
            supervisorId,
            assistantText: turn.assistantText,
            reasoningText: turn.reasoningText,
            capturedAction: runtime.lastCapturedAction,
        });

        // Emit a single NDJSON event line that the daemon-tagged stdout wraps
        // with the active request id. Java consumers see:
        //   { "id": "<reqId>", "line": "[SUPERVISOR_ACTION] {...}" }
        process.stdout.write('[SUPERVISOR_ACTION] ' + JSON.stringify(wrapper) + '\n');

        return { ok: true };
    } finally {
        release();
    }
}

/**
 * Stop and dispose a supervisor session.
 */
export async function stopSupervisorSession(params) {
    const { pairId, supervisorId } = params || {};
    if (!pairId || !supervisorId) {
        throw new Error('supervisor.stop requires pairId and supervisorId');
    }

    const k = key(pairId, supervisorId);
    const runtime = runtimes.get(k);
    if (!runtime) {
        return { alreadyStopped: true };
    }

    runtime.disposed = true;
    try {
        runtime.inputStream.done();
    } catch { /* ignore */ }
    try {
        if (typeof runtime.query?.return === 'function') {
            await runtime.query.return();
        }
    } catch { /* ignore */ }
    runtimes.delete(k);
    process.stdout.write(`[supervisor] stopped: ${k}\n`);
    return { stopped: true };
}

/**
 * Stop every supervisor — used at daemon shutdown.
 */
export async function stopAllSupervisorSessions() {
    const keys = Array.from(runtimes.keys());
    for (const k of keys) {
        const [pairId, supervisorId] = k.split(':', 2);
        try {
            await stopSupervisorSession({ pairId, supervisorId });
        } catch { /* ignore */ }
    }
}

/**
 * Drain SDK messages until the next assistant turn ends.
 *
 * The model's structured ACTION is delivered via the emit_action tool handler
 * (which writes into runtime.lastCapturedAction). What we collect here is the
 * surrounding context for the UI:
 *   - assistantText  — concatenated `text` blocks (the model's visible prose
 *                       prelude/coda around the tool call)
 *   - reasoningText  — concatenated `thinking` / reasoning blocks (model's
 *                       hidden chain of thought, present only when reasoning
 *                       effort is enabled and the model supports it)
 */
async function collectAssistantTurn(runtime) {
    const textBuf = [];
    const reasoningBuf = [];
    while (true) {
        if (runtime.disposed) {
            throw new Error('Supervisor runtime disposed mid-turn');
        }
        let next;
        try {
            next = await runtime.query.next();
        } catch (err) {
            throw new Error('Supervisor SDK iteration failed: ' + (err?.message ?? String(err)));
        }
        if (next?.done) break;

        const msg = next.value;
        if (!msg) continue;

        if (msg.type === 'assistant' && msg.message?.content) {
            for (const block of msg.message.content) {
                if (!block || typeof block !== 'object') continue;
                if (block.type === 'text' && typeof block.text === 'string') {
                    textBuf.push(block.text);
                } else if (block.type === 'thinking') {
                    // SDK reasoning content block.
                    const t = typeof block.thinking === 'string' ? block.thinking
                              : typeof block.text === 'string' ? block.text : '';
                    if (t) reasoningBuf.push(t);
                } else if (block.type === 'redacted_thinking') {
                    // Redacted by Anthropic policy — show a marker so the UI doesn't lie.
                    reasoningBuf.push('[redacted reasoning]');
                }
                // tool_use blocks are dispatched by the SDK to the tool handler,
                // which captures the action onto runtime.lastCapturedAction.
            }
        } else if (msg.type === 'result') {
            break;
        }
        // tool_result / system messages: silently consume.
    }
    return {
        assistantText: textBuf.join('').trim(),
        reasoningText: reasoningBuf.join('\n').trim(),
    };
}

/**
 * Build the wrapper object the Java side (ActionRouter) expects. Falls back
 * to a {action:'wait'} downgrade when the model never called emit_action so
 * the UI still surfaces a card and the dispatcher does not dead-lock.
 */
function buildActionWrapper({ pairId, supervisorId, assistantText, reasoningText, capturedAction }) {
    if (capturedAction) {
        return {
            pairId,
            supervisorId,
            naturalText: assistantText,
            reasoningText,
            action: capturedAction,
            parseError: null,
            rawText: JSON.stringify(capturedAction),
        };
    }

    return {
        pairId,
        supervisorId,
        naturalText: assistantText,
        reasoningText,
        action: {
            action: 'wait',
            reason: '(downgraded) supervisor did not call emit_action this turn',
            payload: {},
        },
        parseError: 'no_tool_use',
        rawText: assistantText,
    };
}

/**
 * Diagnostic — useful for daemon shutdown hooks.
 */
export function getActiveSupervisorCount() {
    return runtimes.size;
}
