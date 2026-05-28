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
import { estimateTokensFromChars } from '../utils/usage-utils.js';
import { summarizeEvent } from '../services/supervisor/event-summarizer.js';
import {
    buildSupervisorMcpServer,
    QUALIFIED_EMIT_ACTION,
    SUPERVISOR_MCP_NAME,
    EMIT_ACTION_TOOL_NAME,
} from '../services/supervisor/supervisor-tools.js';

const DEFAULT_MODEL = 'claude-haiku-4-5-20251001';

// v3: read-only file tools granted to the supervisor so it can perform
// in-turn review (Glob to locate produced files, Read to inspect contents,
// Grep to flag TODO/FIXME/stub functions). Write/Edit/Bash remain forbidden
// — supervisors decide, the main AI edits.
const SUPERVISOR_READ_TOOLS = ['Read', 'Glob', 'Grep'];

// 2026-05-25 (FUNDAMENTAL FIX): wall-clock caps on the active path were
// CONFLATING "stuck" with "slow but progressing". See ai-bridge-server's
// supervisor-channel.js for the full design rationale. Summary:
//   - active path now has NO wall-clock cap by default
//   - liveness comes from SDK-internal timeouts, user-driven supervisor.interrupt,
//     and daemon-process-death detection via Java's IPC layer
//   - env knobs below are kept as emergency rollback (set to a positive ms
//     value to re-enable wall-clock cap on that stage; default unset → no cap)
const QUERY_NEXT_TIMEOUT_MS = readEnvMsOrZero('SUPERVISOR_QUERY_TIMEOUT_MS');
const QUERY_NEXT_TIMEOUT_AFTER_TOOL_USE_MS = readEnvMsOrZero('SUPERVISOR_QUERY_TIMEOUT_AFTER_TOOL_USE_MS');
const QUERY_NEXT_TIMEOUT_AFTER_COMPACT_MS = readEnvMsOrZero('SUPERVISOR_QUERY_TIMEOUT_AFTER_COMPACT_MS');

function readEnvMsOrZero(name) {
    const raw = process.env[name];
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * Wait for the SDK's next iterator result. {@code timeoutMs <= 0} (default)
 * means unbounded await. Positive values are env-opted-in rollback caps.
 */
async function nextWithOptionalTimeout(query, timeoutMs, stage = 'default') {
    if (!(timeoutMs > 0)) {
        return await query.next();
    }
    let timer;
    try {
        return await Promise.race([
            query.next(),
            new Promise((_, reject) => {
                timer = setTimeout(
                    () => reject(new Error(
                        `SUPERVISOR_QUERY_TIMEOUT after ${timeoutMs}ms (stage=${stage})`
                    )),
                    timeoutMs
                );
            }),
        ]);
    } finally {
        if (timer) clearTimeout(timer);
    }
}

// 2026-05-24: mirrors persistent-query-service.js. Supervisor used to ignore
// the reasoning tier set on the Java PairSession — the field was captured
// but never reached the SDK. Now it lands in {@code options.effort} just
// like the main-AI channel, so "max effort" actually changes the thinking
// budget on supervisor turns.
const SUPPORTED_EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

function normalizeReasoningEffort(value) {
    const e = typeof value === 'string' ? value.trim() : '';
    if (!e) return null;
    if (SUPPORTED_EFFORT_LEVELS.has(e)) return e;
    process.stdout.write(
        `[supervisor] ⚠️ unsupported reasoningEffort value: ${JSON.stringify(value)} — falling back to SDK default\n`
    );
    return null;
}

/** @type {Map<string, SupervisorRuntime>} */
const runtimes = new Map();

class SupervisorRuntime {
    constructor({ pairId, supervisorId, name, model, reasoningEffort, systemPrompt, allowedTools }) {
        this.pairId = pairId;
        this.supervisorId = supervisorId;
        this.name = name || supervisorId;
        this.model = model || DEFAULT_MODEL;
        this.reasoningEffort = reasoningEffort || null;
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
        /**
         * v4 unified pipeline: turnId assigned at the start of postEvent. Used by
         * streamSdkMessage so each `[SUPERVISOR_MSG]` line carries the same id
         * as the wrapper's terminating `[SUPERVISOR_ACTION]`. null between
         * turns; non-null while a turn is collecting messages.
         * @type {string | null}
         */
        this.currentTurnId = null;
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
        '# 你可用的只读工具（必读）',
        '除 `emit_action` 外，你拥有 **只读** 文件工具：`Read`、`Glob`、`Grep`。',
        '- 你**没有**写工具——不能调用 Edit / Write / Bash。修改代码靠 inject_prompt 让主 AI 做。',
        '- 一个 turn 内可以多次调用文件工具，最后调用 **一次** `emit_action` 收尾。',
        '',
        '# 必做：review 协议（强约束）',
        '收到 turn_end / verify_result / review_result 等"主 AI 已产出"类事件时：',
        '1) **必须**至少调用一次 `Glob` 或 `Read`（针对 modified_in_plan 文件）——不可跳过；',
        '2) 怀疑有 TODO / FIXME / 桩函数 / 假数据时，调用 `Grep` 验证；',
        '3) 完成检查后再调用 `emit_action`。',
        '**绝不可以只在自然语言里说"我读了 XXX 文件"而不真正发出 tool_use**——',
        '只信主 AI 自述、跳过文件检查直接 emit_action 视为协议违例，本轮判失败。',
        '',
        '收到 user_input / start 等"无产出"事件时，可以直接 emit_action 不调文件工具。',
        '',
        '# 输出格式（强约束）',
        '每一轮决策必须：',
        '1) （可选）先输出简短自然语言段：用 💭/✓/⚠️/⚡/→ 等符号描述观察。',
        '2) 按 review 协议调用所需文件工具（review 类事件必做）。',
        '3) **必须调用 `emit_action` 工具**结束本轮。一轮只能调用一次；调用成功后立即结束本轮。',
        '',
        '`emit_action` 字段说明：',
        '- action: 必填，枚举 inject_prompt / retry_with_hint / approve_and_continue / escalate_to_human / request_amendment / wait / complete_plan / wait_for_contract',
        '- reason: 强烈建议，1-2 句决策原因',
        '- inject_prompt / retry_with_hint 需要 prompt（注入给主 AI 的内容）',
        '- retry_with_hint 可选 wait_seconds（延迟秒数）',
        '- escalate_to_human 需要 question，可选 choices / context_files',
        '- request_amendment 建议带 proposal',
        '- approve_and_continue 可选 mark_step_complete（步骤序号）——仅用于**非最后一步**通过 review；',
        '- complete_plan: 当**所有 step 都完成、整个 plan 收尾**时用，可选 summary。系统据此把 plan 转入 DONE 并生成 COMPLETION_REPORT.md。',
        '  ⚠️ 不要再用「写一个收尾 inject_prompt 等系统检测」的老做法——那条检测路径已废弃，会导致系统误判你卡死并代派任务给主 AI。',
        '- wait_for_contract: 你在等某个已派出、仍 OPEN 的 inject_prompt 回执时用，需要 contractId。',
        '- wait 无额外字段（仅当确有 OPEN 主 AI 合同在跑、纯等待时用）',
        '',
        '若不确定下一步，调用 `emit_action(action="wait")`——**绝不可以只输出文字不调用工具**。',
        '',
        '# 行为约束',
        '- 方案 plan.md 是标准答案。主 AI 不能擅自偏离；偏离时升级用户（escalate_to_human）或要求修正（inject_prompt）。',
        '- 一轮只调用一次 emit_action。',
        '- 文件工具仅用于 review 检查产出，不要用来探查无关代码。'
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
        autoCompactThreshold,
        reasoningEffort,
    } = params || {};

    if (!pairId || !supervisorId) {
        throw new Error('supervisor.start requires pairId and supervisorId');
    }

    // Allow the JetBrains side to override the daemon-wide autocompact
    // threshold per Pair session. The CLI re-reads process.env on every
    // shouldAutoCompact() call (autoCompact.ts:40), so a late mutation here
    // takes effect on the *next* turn — both for this supervisor and for
    // the main AI sharing the same daemon (acknowledged in the design;
    // see Q1 alignment in the rollout plan).
    if (typeof autoCompactThreshold === 'number'
        && autoCompactThreshold >= 50 && autoCompactThreshold <= 95) {
        process.env.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE = String(autoCompactThreshold);
        process.stdout.write(
            `[supervisor] autocompact threshold set to ${autoCompactThreshold}%\n`
        );
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
        reasoningEffort: normalizeReasoningEffort(reasoningEffort),
        systemPrompt,
        allowedTools,
    });

    // Build the in-process MCP server. The handler captures the validated
    // action onto the runtime; collectAssistantTurn reads it after the turn.
    const supervisorMcpServer = buildSupervisorMcpServer(sdk, zod, (action) => {
        runtime.lastCapturedAction = action;
    });

    // Allow emit_action + read-only file tools by default; callers may opt-in
    // to extra tools. Read/Glob/Grep are required by the v3 supervisor prompt
    // to perform in-turn code review (see SUPERVISOR_READ_TOOLS comment).
    const allowedToolList = [
        QUALIFIED_EMIT_ACTION,
        ...SUPERVISOR_READ_TOOLS,
        ...runtime.allowedTools,
    ];

    // SDK options. Supervisor judgment-only: no project-scoped settings, no
    // file checkpointing. We do still pass a cwd because the SDK requires one.
    const cwd = process.env.IDEA_PROJECT_PATH || process.env.PROJECT_PATH || process.cwd();
    runtime.query = queryFn({
        prompt: runtime.inputStream,
        options: {
            cwd,
            model: runtime.model,
            maxTurns: 100,
            // 2026-05-28: emit partial-message stream_event frames so the
            // supervisor's WaitingIndicator can show a live "↓ N tokens" counter
            // during the turn (incl. the thinking phase). collectAssistantTurn
            // consumes these frames for the live estimate ONLY — rendering stays
            // driven by the complete assistant messages, so there's no
            // double-render. See the stream_event branch + emitSupervisorLiveUsage.
            includePartialMessages: true,
            // The SDK accepts a string-or-object systemPrompt. Use a string here so the
            // claude_code preset is NOT activated — Supervisor must obey OUR persona,
            // not Claude Code's default agent instructions.
            systemPrompt: runtime.systemPrompt,
            // 2026-05-24: forward the reasoning tier resolved by Java
            // (PairSession.reasoningEffort or agent's defaultReasoning) as
            // {@code options.effort}. The SDK applies the same low/medium/
            // high/xhigh/max scale it uses for the main AI. Omitted (effort
            // = null) leaves whatever SDK default is in play.
            ...(runtime.reasoningEffort && { effort: runtime.reasoningEffort }),
            mcpServers: { [SUPERVISOR_MCP_NAME]: supervisorMcpServer },
            allowedTools: allowedToolList,
            // Defensive allowlist: pre-approve emit_action, deny everything else
            // even if it slips into allowedTools by mistake.
            canUseTool: async (toolName) => {
                if (toolName === QUALIFIED_EMIT_ACTION) {
                    return { behavior: 'allow' };
                }
                if (SUPERVISOR_READ_TOOLS.includes(toolName)) {
                    return { behavior: 'allow' };
                }
                if (runtime.allowedTools.includes(toolName)) {
                    return { behavior: 'allow' };
                }
                return {
                    behavior: 'deny',
                    message: `Supervisor sessions may only call ${QUALIFIED_EMIT_ACTION} or read-only file tools (Read/Glob/Grep).`,
                };
            },
        },
    });

    runtimes.set(k, runtime);
    process.stdout.write(
        `[supervisor] started: ${k} (model=${runtime.model}, effort=${runtime.reasoningEffort || 'sdk-default'})\n`
    );
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

        // v4 unified pipeline: assign a turnId so streamed SDK messages and the
        // closing [SUPERVISOR_ACTION] wrapper can be correlated on the webview
        // side (entries with the same turnId become one supervisor bubble).
        const turnId = `t_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
        runtime.currentTurnId = turnId;

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
        wrapper.turnId = turnId;
        // v3 side-channel data: only `usage` remains on the wrapper. tool_use
        // and compaction blocks now flow live via [SUPERVISOR_MSG] streaming so
        // the webview can render them as they happen (and so we no longer
        // double-render them). See collectAssistantTurn / streamSdkMessage.
        if (turn.usage) {
            wrapper.usage = { model: runtime.model, ...turn.usage };
        }

        // Emit a single NDJSON event line that the daemon-tagged stdout wraps
        // with the active request id. Java consumers see:
        //   { "id": "<reqId>", "line": "[SUPERVISOR_ACTION] {...}" }
        process.stdout.write('[SUPERVISOR_ACTION] ' + JSON.stringify(wrapper) + '\n');

        // 2026-05-24 (Q4 trace): mirrors ai-bridge-server. Verifies IPC write
        // landed; pair with Java's `[INJECT_TRACE] ActionRouter.handleInjectPrompt`.
        const actionType = wrapper.action && wrapper.action.action;
        if (actionType === 'inject_prompt' || actionType === 'retry_with_hint') {
            const p = (wrapper.action && wrapper.action.payload) || {};
            console.error(
                `[INJECT_TRACE] daemon wrote [SUPERVISOR_ACTION] `
                + `action=${actionType} directiveId=${p.directiveId || '(none)'} `
                + `turnId=${wrapper.turnId || '?'} parseError=${wrapper.parseError || 'null'}`
            );
        }

        return { ok: true };
    } finally {
        runtime.currentTurnId = null;
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
    /** Tool-use blocks (Read/Glob/Grep …) plus their matching tool_result. */
    const toolEvents = [];
    /** Pending tool_use entries waiting for their tool_result by tool_use_id. */
    const pendingTools = new Map();
    /** Compaction boundary messages emitted by CLI mid-stream. */
    const compactEvents = [];
    /** Last usage snapshot we saw — taken from the final assistant or result message. */
    let lastUsage = null;
    // Diagnostic ledger for the v3 usage-stream bug investigation. We dump
    // the shape of each SDK message we see this turn (type, subtype, what
    // usage fields are present) so when the user reports "0% never moves"
    // we can post-mortem the daemon log without guessing.
    const seenTypes = [];
    // 2026-05-25 (FUNDAMENTAL FIX): per-frame timing kept only for diagnostic
    // logging. The active path waits as long as the SDK takes.
    const turnStartMs = Date.now();
    let lastFrameMs = turnStartMs;
    let prevFrameKind = 'init';
    let lastToolUseName = null;
    // Live output-token estimate (2026-05-28): chars streamed this turn (text +
    // thinking) and last-emit timestamp for throttling, plus the authoritative
    // running output_tokens from message_delta. Drives the supervisor pane's
    // live "↓ N tokens" via [SUPERVISOR_USAGE].
    let streamedOutputChars = 0;
    let lastLiveUsageEmitMs = 0;
    let liveRealOutputTokens = 0;
    while (true) {
        if (runtime.disposed) {
            throw new Error('Supervisor runtime disposed mid-turn');
        }
        let next;
        const stage = prevFrameKind === 'compact' ? 'after_compact'
            : prevFrameKind === 'tool_use' ? 'after_tool_use'
            : 'default';
        const frameTimeoutMs = stage === 'after_compact' ? QUERY_NEXT_TIMEOUT_AFTER_COMPACT_MS
            : stage === 'after_tool_use' ? QUERY_NEXT_TIMEOUT_AFTER_TOOL_USE_MS
            : QUERY_NEXT_TIMEOUT_MS;
        try {
            next = await nextWithOptionalTimeout(runtime.query, frameTimeoutMs, stage);
        } catch (err) {
            const errMsg = err?.message ?? String(err);
            if (errMsg.startsWith('SUPERVISOR_QUERY_TIMEOUT')) {
                // Env-opted-in rollback cap actually fired. Surface diagnostic.
                const elapsedMs = Date.now() - turnStartMs;
                const sinceLastFrameMs = Date.now() - lastFrameMs;
                console.error(
                    `[supervisor-diag] ENV_TIMEOUT stage=${stage} frame_cap=${frameTimeoutMs}ms `
                    + `prev_frame=${prevFrameKind}${lastToolUseName ? `(${lastToolUseName})` : ''} `
                    + `since_last_frame=${sinceLastFrameMs}ms turn_elapsed=${elapsedMs}ms `
                    + `pair=${runtime.pairId} supervisor=${runtime.supervisorId} `
                    + `seen=[${seenTypes.slice(-10).join(', ')}]`
                );
                try { if (typeof runtime.query?.return === 'function') runtime.query.return(); }
                catch { /* ignore */ }
                const e = new Error('SUPERVISOR_QUERY_TIMEOUT: ' + errMsg);
                e.code = 'SUPERVISOR_QUERY_TIMEOUT';
                throw e;
            }
            // Real SDK-layer error — bubble; no synthetic timeout error.
            throw new Error('Supervisor SDK iteration failed: ' + errMsg);
        }
        lastFrameMs = Date.now();
        if (next?.done) break;

        const msg = next.value;
        if (!msg) continue;

        // Live-usage path (2026-05-28): with includePartialMessages on, the SDK
        // yields stream_event frames. Consume them ONLY for the live output-token
        // ticker — accumulate streamed chars + track the authoritative output
        // count from message_delta, emit a throttled [SUPERVISOR_USAGE], then
        // skip the rest (no streamSdkMessage / no content extraction) so
        // rendering stays driven by the complete assistant messages below.
        if (msg.type === 'stream_event' && msg.event) {
            const ev = msg.event;
            if (ev.type === 'message_delta' && ev.usage
                    && typeof ev.usage.output_tokens === 'number') {
                liveRealOutputTokens = ev.usage.output_tokens;
            }
            if (ev.type === 'content_block_delta' && ev.delta) {
                const chunk = ev.delta.type === 'text_delta' ? (ev.delta.text || '')
                    : ev.delta.type === 'thinking_delta' ? (ev.delta.thinking || '')
                    : '';
                if (chunk) {
                    streamedOutputChars += chunk.length;
                    const now = Date.now();
                    if (now - lastLiveUsageEmitMs >= 150) {
                        lastLiveUsageEmitMs = now;
                        const est = estimateTokensFromChars(streamedOutputChars);
                        emitSupervisorLiveUsage(runtime, Math.max(liveRealOutputTokens, est));
                    }
                }
            }
            continue;
        }

        // Record this message's shape so we can debug usage extraction later.
        // Cheap (constant string concat); the dump happens once per turn.
        seenTypes.push(
            (msg.type || '?')
            + (msg.subtype ? `:${msg.subtype}` : '')
            + (msg.message?.usage ? '[u]' : '')
            + (msg.usage ? '[U]' : '')
        );

        // v4 unified pipeline: stream the raw SDK message to the webview the
        // instant it arrives. The webview converts content blocks into pane
        // entries on the fly — no need to wait for the turn to end and
        // reconstruct from a wrapper. result/system meta-frames are streamed
        // too because the webview may want to surface them (e.g. compaction).
        streamSdkMessage(runtime, msg);

        if (msg.type === 'assistant' && msg.message?.content) {
            let sawToolUse = false;
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
                } else if (block.type === 'tool_use') {
                    // Read/Glob/Grep invocations — we surface these to the UI
                    // so users can see what the supervisor inspected, matching
                    // the main AI's tool card rendering. The MCP emit_action
                    // tool is filtered out — it's an internal protocol detail,
                    // not user-facing.
                    const isMcpAction = typeof block.name === 'string'
                        && block.name.includes(EMIT_ACTION_TOOL_NAME);
                    if (!isMcpAction) {
                        const entry = {
                            id: block.id,
                            name: block.name,
                            input: block.input,
                            result: null,
                        };
                        pendingTools.set(block.id, entry);
                        toolEvents.push(entry);
                    }
                    sawToolUse = true;
                    if (typeof block.name === 'string') lastToolUseName = block.name;
                }
            }
            // Per-message usage rollup, in case the result message doesn't carry one.
            if (msg.message.usage) lastUsage = msg.message.usage;
            prevFrameKind = sawToolUse ? 'tool_use' : 'text';
        } else if (msg.type === 'user' && msg.message?.content) {
            // tool_result blocks come back as user-role messages in the SDK
            // stream. Match them to the pending tool_use by id and attach
            // a brief stringified preview (full content can be huge — we
            // cap it so the IPC line stays sensible).
            for (const block of msg.message.content) {
                if (!block || typeof block !== 'object') continue;
                if (block.type !== 'tool_result') continue;
                const pending = pendingTools.get(block.tool_use_id);
                if (!pending) continue;
                pending.result = summarizeToolResult(block);
                pendingTools.delete(block.tool_use_id);
            }
            prevFrameKind = 'tool_result';
        } else if (msg.type === 'system' && msg.subtype === 'compact_boundary') {
            // CLI auto-compaction event — the conversation history was just
            // summarised down to fit the model's context window. We pass the
            // before/after token counts to the UI so the user understands
            // why earlier turns suddenly look terser.
            compactEvents.push({
                trigger: msg.compact_metadata?.trigger || 'auto',
                preTokens: msg.compact_metadata?.pre_tokens ?? null,
            });
            prevFrameKind = 'compact';
        } else if (msg.type === 'result') {
            if (msg.usage) lastUsage = msg.usage;
            break;
        } else {
            prevFrameKind = 'other';
        }
    }
    // One-line per-turn diagnostic. Goes to daemon stderr via console.error
    // (intercepted by daemon.js and forwarded as a daemon stderr line, NOT
    // a request-tagged stdout line — so it doesn't pollute the IPC stream).
    // Read it via the IDE's daemon-stderr log when triaging "0% never moves"
    // or "no tool cards" complaints.
    const turnDurMs = Date.now() - turnStartMs;
    console.error(
        `[supervisor-diag] turn complete: dur=${turnDurMs}ms msgs=[${seenTypes.join(', ')}] `
        + `tools=${toolEvents.length} compact=${compactEvents.length} `
        + `captured_action=${runtime.lastCapturedAction ? runtime.lastCapturedAction.action : 'null'} `
        + `usage=${lastUsage ? JSON.stringify(lastUsage) : 'null'}`
    );
    if (toolEvents.length > 0) {
        // List the tool names so we can verify the supervisor is exercising
        // the protocol ("at least one Read before emit_action"). If this line
        // is missing from the daemon log, the wrapper.toolEvents was empty
        // and Java has nothing to dispatch.
        console.error(
            `[supervisor-diag] toolEvents=${toolEvents.map(t => t.name).join(',')}`
        );
    } else {
        console.error('[supervisor-diag] toolEvents=(none) — supervisor skipped review tools');
    }
    return {
        assistantText: textBuf.join('').trim(),
        reasoningText: reasoningBuf.join('\n').trim(),
        toolEvents,
        compactEvents,
        usage: lastUsage ? normaliseUsage(lastUsage) : null,
    };
}

/**
 * v4 unified pipeline: forward one raw SDK message to Java/webview as a
 * `[SUPERVISOR_MSG]` line tagged with pairId / supervisorId / turnId. The
 * payload is a thin envelope — the SDK message body is passed through
 * untouched so the webview can use the same content-block shape it already
 * handles for main-AI messages.
 *
 * Lines are written through the daemon's intercepted process.stdout, which
 * wraps them as `{ id: <reqId>, line: "[SUPERVISOR_MSG] {...}" }` NDJSON.
 * If JSON.stringify fails (e.g. circular ref in a future SDK shape), we log
 * and drop — losing a stream message must not break the turn.
 */
/**
 * 2026-05-28: emit a live output-token estimate for the supervisor turn, tagged
 * like {@link streamSdkMessage} so Java can route it by pairId/supervisorId. The
 * count is the larger of the authoritative message_delta output and the streamed
 * char estimate (never moves backward); reconciles to the real total when the
 * complete assistant message lands. Best-effort — a dropped line just skips one
 * tick of the counter, never breaks the turn.
 */
function emitSupervisorLiveUsage(runtime, outputTokens) {
    try {
        const envelope = {
            pairId: runtime.pairId,
            supervisorId: runtime.supervisorId,
            turnId: runtime.currentTurnId,
            outputTokens,
        };
        process.stdout.write('[SUPERVISOR_USAGE] ' + JSON.stringify(envelope) + '\n');
    } catch (e) {
        console.error('[supervisor-stream] failed to emit live usage: '
            + (e?.message || String(e)));
    }
}

function streamSdkMessage(runtime, msg) {
    try {
        // Tool_result blocks can be huge (a Read on a multi-MB file). Cap the
        // text content here so the IPC line stays under a few KB — matches the
        // cap we apply in summarizeToolResult for the old wrapper path.
        const envelope = {
            pairId: runtime.pairId,
            supervisorId: runtime.supervisorId,
            turnId: runtime.currentTurnId,
            message: capStreamMessage(msg),
        };
        process.stdout.write('[SUPERVISOR_MSG] ' + JSON.stringify(envelope) + '\n');
    } catch (e) {
        console.error('[supervisor-stream] failed to stream msg: '
            + (e?.message || String(e)));
    }
}

/**
 * Safety-valve cap on tool_result text in streamed messages. Originally 200KB;
 * lowered to 50KB after the remote-mode supervisor IPC investigation
 * (2026-05-24). On remote mode multiple Read tool_results land in a single
 * SSE burst, and 200KB × N per tick saturated the JCEF/EDT pipeline, which
 * tripped WebviewWatchdog and wiped the supervisor pane. 50KB still passes
 * typical Read/Glob/Grep output intact; larger payloads get a truncation
 * marker the UI's CollapsibleTextBlock surfaces gracefully.
 */
function capStreamMessage(msg) {
    if (!msg || msg.type !== 'user' || !Array.isArray(msg.message?.content)) {
        return msg;
    }
    const MAX_LEN = 50_000;
    const cappedContent = msg.message.content.map((block) => {
        if (!block || block.type !== 'tool_result') return block;
        let text = '';
        if (typeof block.content === 'string') {
            text = block.content;
        } else if (Array.isArray(block.content)) {
            text = block.content
                .filter((c) => c?.type === 'text' && typeof c.text === 'string')
                .map((c) => c.text)
                .join('\n');
        }
        if (text.length <= MAX_LEN) return block;
        const truncated = text.slice(0, MAX_LEN) + '\n…(truncated)';
        return {
            ...block,
            content: truncated,
            _totalLength: text.length,
        };
    });
    return {
        ...msg,
        message: { ...msg.message, content: cappedContent },
    };
}

/**
 * Tool-result summary used by the legacy wrapper path (only triggered when
 * the SDK stream produced no live messages — transport errors / skipped
 * emit_action). Same 50KB safety valve as capStreamMessage (lowered from
 * 200KB to match the remote-mode IPC-pressure fix).
 */
function summarizeToolResult(block) {
    const isError = block.is_error === true;
    let text = '';
    if (typeof block.content === 'string') {
        text = block.content;
    } else if (Array.isArray(block.content)) {
        text = block.content
            .filter((c) => c?.type === 'text' && typeof c.text === 'string')
            .map((c) => c.text)
            .join('\n');
    }
    const MAX_LEN = 50_000;
    const truncated = text.length > MAX_LEN;
    return {
        isError,
        preview: truncated ? text.slice(0, MAX_LEN) + '\n…(truncated)' : text,
        totalLength: text.length,
    };
}

/**
 * Normalise the SDK's usage object into a consistent shape and compute a
 * "total prompt tokens" estimate — the figure the UI percentage is keyed off.
 * Cache hits + cache writes both count as "in the window" because that's
 * what the model sees on the next turn.
 */
function normaliseUsage(usage) {
    const input = usage.input_tokens || 0;
    const cacheCreate = usage.cache_creation_input_tokens || 0;
    const cacheRead = usage.cache_read_input_tokens || 0;
    const output = usage.output_tokens || 0;
    return {
        inputTokens: input,
        outputTokens: output,
        cacheCreationInputTokens: cacheCreate,
        cacheReadInputTokens: cacheRead,
        totalPromptTokens: input + cacheCreate + cacheRead,
    };
}

/**
 * Build the wrapper object the Java side (ActionRouter) expects.
 *
 * <p>v4 unified pipeline: text and reasoning are NOT carried on the wrapper for
 * the normal path — they were already streamed live via [SUPERVISOR_MSG]. The
 * wrapper carries only the {@code action} (emit_action result) plus a turnId
 * the webview uses to group the streamed entries with the action card into
 * one bubble. Empty {@code naturalText} / {@code reasoningText} placeholders
 * are kept for backward compatibility with any consumer reading the JSON; the
 * webview ignores them when {@code parseError} is null.
 *
 * <p>If the model never calls emit_action, we still downgrade to a
 * {@code wait} action with a non-null {@code parseError} so the UI surfaces
 * a card and the dispatcher does not dead-lock. {@code rawText} keeps the
 * model's prose for debugging — it never reaches the bubble (the streamed
 * text entries already did).
 */
function buildActionWrapper({ pairId, supervisorId, assistantText, reasoningText, capturedAction }) {
    if (capturedAction) {
        return {
            pairId,
            supervisorId,
            naturalText: '',
            reasoningText: '',
            action: capturedAction,
            parseError: null,
            rawText: JSON.stringify(capturedAction),
        };
    }

    return {
        pairId,
        supervisorId,
        naturalText: '',
        reasoningText: '',
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
