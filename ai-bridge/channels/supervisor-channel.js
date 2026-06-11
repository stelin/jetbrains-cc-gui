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
import { setupSupervisorAuth, buildCliEnv, loadClaudeSettings } from '../config/api-config.js';
import { mapModelIdToSdkName, resolveModelFromSettings, setModelEnvironmentVariables } from '../utils/model-utils.js';
import { AsyncStream } from '../utils/async-stream.js';
import { estimateTokensFromChars } from '../utils/usage-utils.js';
import { summarizeEvent } from '../services/supervisor/event-summarizer.js';
import {
    buildSupervisorMcpServer,
    QUALIFIED_EMIT_ACTION,
    SUPERVISOR_MCP_NAME,
    EMIT_ACTION_TOOL_NAME,
} from '../services/supervisor/supervisor-tools.js';
import {
    buildEmitPlanTool,
    QUALIFIED_EMIT_PLAN,
} from '../services/supervisor/plan-tools.js';
import {
    buildUpdateStateTool,
    QUALIFIED_UPDATE_STATE,
} from '../services/supervisor/update-state-tool.js';
// 2026-06-01: MCP self-check for test/bug supervisors. Reused as-is from the
// main-AI MCP status module — same ~/.claude.json source the main AI reads,
// so the supervisor sees exactly the servers added via `claude mcp add`.
import {
    loadMcpServersConfig,
    getMcpServersStatus,
    getMcpServerTools,
} from '../services/claude/mcp-status/index.js';

const DEFAULT_MODEL = 'claude-haiku-4-5-20251001';

// Transient API-error retry (2026-06-10): the supervisor's model API can return
// an empty/malformed HTTP 200 (a proxy/gateway hiccup) that the SDK surfaces as
// "API Error: ..." assistant content with no captured action/plan. The main AI
// already retries these (message-sender.js AUTO_RETRY, maxRetries:2); the
// supervisor did not, so a transient blip surfaced a raw error and stalled the
// turn. Mirror the main AI: re-prompt a couple of times before downgrading.
const MAX_TURN_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 800;
function isTransientApiError(text) {
    if (typeof text !== 'string' || !text) return false;
    return /API Error:|empty or malformed response|proxy or gateway intercepting/i.test(text);
}

// v3: read-only file tools granted to the supervisor so it can perform
// in-turn review (Glob to locate produced files, Read to inspect contents,
// Grep to flag TODO/FIXME/stub functions). Write/Edit/Bash remain forbidden
// — supervisors decide, the main AI edits.
const SUPERVISOR_READ_TOOLS = ['Read', 'Glob', 'Grep'];

/**
 * Convert a single `~/.claude.json` server config into an SDK mcpServers entry.
 * stdio: { command, args?, env? }; remote: { type:'sse'|'http', url, headers? }.
 */
function toSdkMcpServerSpec(config) {
    if (config && typeof config.url === 'string' && config.url) {
        return {
            type: config.type || 'sse',
            url: config.url,
            ...(config.headers ? { headers: config.headers } : {}),
        };
    }
    return {
        type: 'stdio',
        command: config.command,
        ...(Array.isArray(config.args) ? { args: config.args } : {}),
        ...(config.env && typeof config.env === 'object' ? { env: config.env } : {}),
    };
}

/**
 * Load ALL `claude mcp add` servers (~/.claude.json) and prepare them for a
 * supervisor session that opted in via `mcpAccess`:
 *   - sdkServers: entries to merge into query().options.mcpServers
 *   - allowedServerNames: server-name set used by canUseTool to allow mcp__<name>__*
 *   - promptSection: a "可用 MCP" system-prompt block (name + connection status +
 *     tool names) for the supervisor's first-run self-check.
 * Connection status comes from the MCP handshake (no active SELECT/PING probe).
 * Every step is guarded: any failure (or an overall 8s timeout on the
 * status/tools probe) degrades to a best-effort result so the supervisor
 * always starts.
 */
async function buildSupervisorExternalMcp(cwd) {
    const out = { sdkServers: {}, allowedServerNames: new Set(), allowedTools: [], promptSection: '' };
    let servers = [];
    try {
        servers = await loadMcpServersConfig(cwd);
    } catch (e) {
        process.stderr.write(`[supervisor] loadMcpServersConfig failed: ${e?.message}\n`);
        return out;
    }
    if (!Array.isArray(servers) || servers.length === 0) return out;

    for (const { name, config } of servers) {
        try {
            out.sdkServers[name] = toSdkMcpServerSpec(config);
            out.allowedServerNames.add(name);
            // SDK allowedTools is an availability allowlist (Read/Glob/Grep are
            // listed there for the same reason). `mcp__<server>` is the
            // server-wildcard form (see permission-mode.js) — allows all of a
            // server's tools. Without this the model can't see/call them even
            // though canUseTool would permit.
            out.allowedTools.push(`mcp__${name}`);
        } catch (e) {
            process.stderr.write(`[supervisor] skip MCP ${name}: ${e?.message}\n`);
        }
    }

    // Connection status + tool names, bounded by an overall timeout so a hung
    // MCP server can never block supervisor startup.
    const rows = await Promise.race([
        (async () => {
            let statusList = [];
            try { statusList = await getMcpServersStatus(cwd); } catch { statusList = []; }
            const statusByName = new Map((statusList || []).map((s) => [s.name, s]));
            const acc = [];
            for (const { name, config } of servers) {
                const st = statusByName.get(name);
                const connected = st && st.status === 'connected';
                let toolNames = [];
                if (connected) {
                    try {
                        const t = await getMcpServerTools(name, config);
                        const arr = (t && t.tools) ? t.tools : (Array.isArray(t) ? t : []);
                        toolNames = arr.map((x) => `mcp__${name}__${(x && x.name) || x}`);
                    } catch { /* tools/list failure is non-fatal */ }
                }
                acc.push(connected
                    ? `- ${name} [connected] 工具: ${toolNames.join(', ') || '(tools/list 未返回)'}`
                    : `- ${name} [unavailable${st && st.error ? ': ' + st.error : ''}]`);
            }
            return acc;
        })(),
        new Promise((resolve) => setTimeout(() => resolve(null), 8000)),
    ]);

    const statusRows = rows || servers.map((s) => `- ${s.name} [status unknown: 探测超时]`);
    out.promptSection = [
        '',
        '# 可用 MCP（首轮自检用）',
        '以下是已挂载到你的 MCP server 及连接状态（连通性来自 MCP 握手，非数据探活）。',
        '**首轮必须**把本段原样列给用户看，并核对预期的 MySQL/Redis 是否在且 connected；',
        '不一致时按 persona 规则记录并降级，不要问人、不要阻塞。',
        '需要看数据辅助诊断/修复/核验时，调用对应 `mcp__<server>__<tool>`（用于查/核验，禁止写库）。',
        '',
        ...statusRows,
    ].join('\n');
    return out;
}

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

// 'ultra' is Claude Code's "ultracode" session setting, NOT an SDK effort
// level: it sends xhigh to the model AND enables dynamic workflow orchestration.
// Only meaningful on an xhigh-capable model (Opus 4.8).
const ULTRACODE_SETTINGS = { ultracode: true, enableWorkflows: true };

function normalizeReasoningEffort(value) {
    const e = typeof value === 'string' ? value.trim() : '';
    if (!e) return null;
    if (SUPPORTED_EFFORT_LEVELS.has(e)) return e;
    process.stdout.write(
        `[supervisor] ⚠️ unsupported reasoningEffort value: ${JSON.stringify(value)} — falling back to SDK default\n`
    );
    return null;
}

// Translate the UI reasoning tier into the SDK shape. 'ultra' → xhigh effort +
// ultracode/workflow settings; everything else passes through normalizeReasoningEffort.
function resolveEffortAndSettings(rawEffort) {
    const e = typeof rawEffort === 'string' ? rawEffort.trim() : '';
    if (e === 'ultra') {
        return { effort: 'xhigh', settings: ULTRACODE_SETTINGS };
    }
    return { effort: normalizeReasoningEffort(rawEffort), settings: null };
}

/** @type {Map<string, SupervisorRuntime>} */
const runtimes = new Map();

class SupervisorRuntime {
    constructor({ pairId, supervisorId, name, model, reasoningEffort, ultracodeSettings, systemPrompt, allowedTools }) {
        this.pairId = pairId;
        this.supervisorId = supervisorId;
        this.name = name || supervisorId;
        this.model = model || DEFAULT_MODEL;
        this.reasoningEffort = reasoningEffort || null;
        this.ultracodeSettings = ultracodeSettings || null;
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
         * Filled by the emit_plan tool handler when the supervisor produces its
         * (one-time) structured plan this turn. Reset at the start of each
         * postEvent; read after the turn to emit a [SUPERVISOR_PLAN] line. Null
         * on every non-planning turn (which is almost all of them).
         * @type {{steps: Array, rationale: string} | null}
         */
        this.lastCapturedPlan = null;
        /**
         * v4 unified pipeline: turnId assigned at the start of postEvent. Used by
         * streamSdkMessage so each `[SUPERVISOR_MSG]` line carries the same id
         * as the wrapper's terminating `[SUPERVISOR_ACTION]`. null between
         * turns; non-null while a turn is collecting messages.
         * @type {string | null}
         */
        this.currentTurnId = null;
        /**
         * Session resume (2026-06-05): the SDK-assigned session_id, captured from
         * the first message that carries one (init / assistant / result). Persisted
         * by Java (via the [SUPERVISOR_SESSION] line) so a later restart can pass it
         * back as `resumeSessionId` to continue this supervisor's transcript.
         * @type {string | null}
         */
        this.sessionId = null;
        /**
         * The session_id this runtime was asked to resume (null = fresh start).
         * On first captured session_id we compare against this: if they differ the
         * SDK did NOT continue the requested session (resume not honoured) and we
         * emit [SUPERVISOR_RESUME_MISS] so Java can fall back (SR5/SR6).
         * @type {string | null}
         */
        this.requestedResumeId = null;
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
        '# 规划协议（新任务第一步，最优先）',
        '处理一个新任务时，你的**第一个动作**是调用 `emit_plan` 产出结构化计划：',
        '- 把任务（上面的「当前任务方案」，或用户在输入框给出的任务）拆成有序步骤；',
        '- 每步写明 owner（默认 MAIN_AI）与**验收标准** acceptanceCriteria——你之后据此 Read 真实产物逐条核验，'
            + '**不是**听主 AI 自述说做了就算；',
        '- **只做结构化，不发明目标**：目标以外部任务/方案为准。',
        '收到带 `[PLANNING_REQUIRED]` 的事件时，**必须先 emit_plan**，之后可在同一轮 `emit_action(inject_prompt)` 派发第 1 步。',
        '计划一旦 emit 即**锁定**：要调整走 `emit_action(request_amendment)`，**不要再次 emit_plan**。',
        '若你已为当前任务产出过 plan（对话历史里有），**不要重复 emit_plan**，直接按计划监督。',
        '',
        '# 续跑核验（恢复已有进度的计划时）',
        '当你在恢复一个已有进度的计划（事件里带恢复提示，或 plan.md 显示已有进度）：',
        '- **已完成(DONE)步**：信任，不复查；**未开始(TODO)步**：不查；',
        '- **正在执行(IN_PROGRESS)步：必须对账**——先 Read 现场、对照该步验收标准判断它实际做到哪了'
            + '（崩溃前可能已部分完成），再决定：续做剩余 / 已达标则 approve_and_continue(mark_step_complete) / 偏差大则 inject_prompt 重做。',
        '- 计划与进度可 Read `plan.md`（系统按你的计划自动渲染），不要凭记忆臆断。',
        '',
        '# 推进前置条件（铁律，任何时候都成立）',
        '推进下一步 / `approve_and_continue` 标完成 / `complete_plan` 收尾，前提是：',
        '**当前步已经收到主 AI 对该步的回复（该步的 turn_report / turn_end 事件），且你已 Read 真实产物核验通过。**',
        '把每一步钉成三态，禁止凭记忆把"派过"当成"做完了"：',
        '- 已派发(dispatched)：你调了 `inject_prompt` 派单，但**还没**看到该步的 turn_report；',
        '- 已回复(replied)：你已收到该步的 turn_report / turn_end；',
        '- 已完成(done)：已回复 + 你据验收标准 Read 真实产物核验通过。',
        '每次准备推进前，先就**当前步**自问：它的 turn_report 到了吗？',
        '- 当前步 = TODO / 未开始（从未派发过）→ 这是"首次派单(dispatch)"，不是"推进(advance)"：'
            + '**直接正常派发它**（`emit_action(inject_prompt)`，带 objective + acceptanceCriteria），'
            + '**不需要、也等不到它的 turn_report 才派**。上面的 turn_report 前置校验只适用于"已派发、等待复核/标完成"的步骤。'
            + '（系统也会在该派下一步时给你 [DISPATCH_NEXT_STEP] 提示——照它派，别 wait。）',
        '- 到了 → 走 review 协议（Read 核验）→ `approve_and_continue` / `complete_plan`；',
        '- **没到（派了但主 AI 没回复）→ 禁止推进下一步、禁止标完成 / 收尾**。改用 `inject_prompt` '
            + '**重新下发当前步**，指令里要求主 AI **先自查这一步已做了哪些、还差哪些（可能已部分完成），'
            + '把剩余补完，再 report_turn_completion**；收到该步回复并核验通过后，才进入下一步。',
        '绝不凭"我记得派过 / 我以为做完了"推进——唯一的推进依据是"收到了该步的回复事件"。'
            + '这条与上面「续跑核验」同源，只是它在任何时候都成立，不限于恢复场景。',
        '',
        '# 你可用的只读工具（必读）',
        '除 `emit_action` / `emit_plan` / `update_state` 外，你拥有 **只读** 文件工具：`Read`、`Glob`、`Grep`。',
        '- `update_state`：把 A/B 级自决或新发现的硬约束记进记忆（decisionAppend / constraintAdd）；'
            + '**计划进度不用你写**，系统按你的 plan 自动投影到 plan.md。',
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
        '- escalate_to_human 需要 question，可选 choices / context_files / blocking',
        '  · blocking=true 表示必须等用户当面拍板（前端弹出阻塞式对话框，用户必选一项或填一句才能继续），仅在「不拿到用户决定就无法继续」时设；',
        '    带了 choices 的升级会被自动视为 blocking；纯告知类（用户可事后再看）不要设 blocking，让它走非阻塞提示。',
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
        // 2026-06-01: when true (seeded on bug/unit-test/api-test supervisors),
        // attach ALL `claude mcp add` servers to this supervisor session.
        mcpAccess,
        // Session resume (2026-06-05): non-null → continue this supervisor's prior
        // SDK transcript instead of starting fresh (SR4). The SDK loads the
        // session_id's .jsonl from ~/.claude/projects/<cwd-hash>/. Honoured-or-not
        // is self-checked via the captured session_id (SR5).
        resumeSessionId,
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

    let systemPrompt = buildSystemPrompt({ name, description, planContent, specContent });

    // 2026-06-01: opt-in MCP access. Only supervisors with mcpAccess=true get
    // the user's `claude mcp add` servers attached + a "可用 MCP" self-check
    // section appended to their system prompt. Other supervisors are untouched.
    let externalMcp = { sdkServers: {}, allowedServerNames: new Set(), allowedTools: [], promptSection: '' };
    if (mcpAccess) {
        externalMcp = await buildSupervisorExternalMcp(
            process.env.IDEA_PROJECT_PATH || process.env.PROJECT_PATH || process.cwd()
        );
        if (externalMcp.promptSection) {
            systemPrompt = systemPrompt + '\n' + externalMcp.promptSection;
        }
    }

    const [sdk, zod] = await Promise.all([loadClaudeSdk(), loadZod()]);
    const queryFn = sdk?.query;
    if (typeof queryFn !== 'function') {
        throw new Error('Claude SDK does not expose query() function');
    }

    const { effort: resolvedEffort, settings: resolvedUltracodeSettings } = resolveEffortAndSettings(reasoningEffort);
    const runtime = new SupervisorRuntime({
        pairId,
        supervisorId,
        name,
        model,
        reasoningEffort: resolvedEffort,
        ultracodeSettings: resolvedUltracodeSettings,
        systemPrompt,
        allowedTools,
    });
    // Session resume (2026-06-05): remember what we asked to resume so the turn
    // loop can self-check whether the SDK honoured it (SR5).
    runtime.requestedResumeId = (typeof resumeSessionId === 'string' && resumeSessionId.trim())
        ? resumeSessionId.trim() : null;

    // Build the in-process MCP server. The handlers capture the validated
    // action / plan onto the runtime; collectAssistantTurn + postEvent read them
    // after the turn. emit_plan is built here (not inside buildSupervisorMcpServer)
    // and passed in as an extra tool, keeping the plan-tools → supervisor-tools
    // dependency one-way (no circular import).
    const z = zod?.z ?? zod?.default?.z ?? zod;
    const emitPlanTool = buildEmitPlanTool(sdk, z, (plan) => {
        runtime.lastCapturedPlan = plan;
    });
    const updateStateTool = buildUpdateStateTool(sdk, z);
    const supervisorMcpServer = buildSupervisorMcpServer(
        sdk,
        zod,
        (action) => { runtime.lastCapturedAction = action; },
        [emitPlanTool, updateStateTool]
    );

    // Allow emit_action + read-only file tools by default; callers may opt-in
    // to extra tools. Read/Glob/Grep are required by the v3 supervisor prompt
    // to perform in-turn code review (see SUPERVISOR_READ_TOOLS comment).
    const allowedToolList = [
        QUALIFIED_EMIT_ACTION,
        QUALIFIED_EMIT_PLAN,
        QUALIFIED_UPDATE_STATE,
        ...SUPERVISOR_READ_TOOLS,
        ...runtime.allowedTools,
        // 2026-06-01: attached `claude mcp add` servers (empty unless mcpAccess).
        ...externalMcp.allowedTools,
    ];

    // Credentials + CLI identity. The supervisor runs its OWN Claude SDK session
    // in the same daemon, but — unlike the main-AI channel (message-sender.js) —
    // it never set up auth. So when a supervisor turn runs BEFORE any main-AI
    // request (composer mode: the user talks to the supervisor directly), the
    // global process.env has no ANTHROPIC_* creds and the SDK 403s ("Request not
    // allowed"). setupSupervisorAuth() populates process.env: a configured Claude
    // provider when present, ELSE the active Codex provider's base_url+key (when
    // it's a unified / Anthropic-compatible proxy) so local-mode supervisor + main
    // AI can share ONE provider config. Then buildCliEnv() snapshots the creds +
    // CLI identity into the SDK child. Best-effort — if nothing resolves, the same
    // 403 surfaces (no regression).
    try {
        setupSupervisorAuth();
    } catch (e) {
        process.stderr.write(`[supervisor] setupSupervisorAuth failed (relying on existing env): ${e?.message || e}\n`);
    }

    // Model resolution — mirror the main-AI channel (message-sender.js:446-450).
    // The supervisor's configured model is a Claude tier id (e.g. claude-opus-4-8),
    // but a unified proxy only knows the user's mapped name (settings.env
    // ANTHROPIC_DEFAULT_OPUS_MODEL = gpt-5.5). Passing the raw id makes the SDK send
    // "claude-opus-4-8" verbatim → the proxy 422s "model not found". So:
    //   1) mapModelIdToSdkName → the SDK tier selector ('opus'/'sonnet'/'haiku');
    //   2) setModelEnvironmentVariables stages the mapped concrete model into
    //      ANTHROPIC_DEFAULT_*_MODEL (snapshotted below by buildCliEnv).
    // The SDK then substitutes the tier → the proxy's real model. No-op when the
    // model id isn't an Anthropic tier or no mapping is configured.
    const supSettings = loadClaudeSettings();
    const sdkModelName = mapModelIdToSdkName(runtime.model);
    const resolvedSupModel = resolveModelFromSettings(runtime.model, supSettings?.env);
    setModelEnvironmentVariables(resolvedSupModel, runtime.model);
    process.stdout.write(`[supervisor] model ${runtime.model} → sdk='${sdkModelName}' api='${resolvedSupModel}'\n`);

    // SDK options. Supervisor judgment-only: no project-scoped settings, no
    // file checkpointing. We do still pass a cwd because the SDK requires one.
    const cwd = process.env.IDEA_PROJECT_PATH || process.env.PROJECT_PATH || process.cwd();
    runtime.query = queryFn({
        prompt: runtime.inputStream,
        options: {
            cwd,
            // Snapshot the provider creds (setupSupervisorAuth) + the model-alias
            // env (setModelEnvironmentVariables) + CLI identity into the SDK child —
            // same as the main-AI channel.
            env: buildCliEnv(),
            // SDK tier selector ('opus'/'sonnet'/'haiku'); the concrete model is
            // resolved from ANTHROPIC_DEFAULT_*_MODEL staged above.
            model: sdkModelName,
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
            // Session resume (2026-06-05): continue the prior transcript when asked.
            // The SDK replays the session_id's history (which already embeds this
            // persona/systemPrompt), so re-passing systemPrompt above is redundant
            // but harmless. If the SDK ignores resume for streaming-input/custom
            // systemPrompt sessions, the captured session_id won't match and we
            // emit [SUPERVISOR_RESUME_MISS] so Java can fall back (SR5/SR6).
            ...(runtime.requestedResumeId && { resume: runtime.requestedResumeId }),
            // 2026-05-24: forward the reasoning tier resolved by Java
            // (PairSession.reasoningEffort or agent's defaultReasoning) as
            // {@code options.effort}. The SDK applies the same low/medium/
            // high/xhigh/max scale it uses for the main AI. Omitted (effort
            // = null) leaves whatever SDK default is in play.
            ...(runtime.reasoningEffort && { effort: runtime.reasoningEffort }),
            // 2026-05-30: the 'ultra' tier additionally enables ultracode +
            // dynamic workflow orchestration via the inline settings layer
            // (effort is already xhigh above). Only set on Opus 4.8.
            ...(runtime.ultracodeSettings && { settings: runtime.ultracodeSettings }),
            mcpServers: {
                [SUPERVISOR_MCP_NAME]: supervisorMcpServer,
                // 2026-06-01: attached `claude mcp add` servers (empty unless mcpAccess).
                ...externalMcp.sdkServers,
            },
            allowedTools: allowedToolList,
            // Defensive allowlist: pre-approve emit_action, deny everything else
            // even if it slips into allowedTools by mistake.
            canUseTool: async (toolName) => {
                if (toolName === QUALIFIED_EMIT_ACTION || toolName === QUALIFIED_EMIT_PLAN
                        || toolName === QUALIFIED_UPDATE_STATE) {
                    return { behavior: 'allow' };
                }
                if (SUPERVISOR_READ_TOOLS.includes(toolName)) {
                    return { behavior: 'allow' };
                }
                if (runtime.allowedTools.includes(toolName)) {
                    return { behavior: 'allow' };
                }
                // 2026-06-01: full access to tools of attached `claude mcp add`
                // servers (mcp__<server>__<tool>). Decision: allow all tools
                // (no read-only filter); persona text constrains writes.
                if (toolName.startsWith('mcp__')) {
                    const serverSeg = toolName.split('__')[1];
                    if (externalMcp.allowedServerNames.has(serverSeg)) {
                        return { behavior: 'allow' };
                    }
                }
                return {
                    behavior: 'deny',
                    message: `Supervisor sessions may only call ${QUALIFIED_EMIT_ACTION}, read-only file tools (Read/Glob/Grep), or attached MCP tools.`,
                };
            },
        },
    });

    runtimes.set(k, runtime);
    process.stdout.write(
        `[supervisor] started: ${k} (model=${runtime.model}, effort=${runtime.reasoningEffort || 'sdk-default'}`
        + `, resume=${runtime.requestedResumeId || 'none'})\n`
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
        runtime.lastCapturedPlan = null;

        // v4 unified pipeline: assign a turnId so streamed SDK messages and the
        // closing [SUPERVISOR_ACTION] wrapper can be correlated on the webview
        // side (entries with the same turnId become one supervisor bubble).
        const turnId = `t_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
        runtime.currentTurnId = turnId;

        // Enqueue the summarized event as a user message in the SDK input stream.
        // Schema must match what the Claude Agent SDK expects (see persistent-query-service.js).
        const enqueueUserText = (text) => {
            runtime.inputStream.enqueue({
                type: 'user',
                session_id: '',
                parent_tool_use_id: null,
                message: {
                    role: 'user',
                    content: [{ type: 'text', text }],
                },
            });
        };
        enqueueUserText(summary);

        let turn = await collectAssistantTurn(runtime);

        // Transient API-error retry: an empty/malformed HTTP 200 from the model
        // endpoint surfaces as "API Error: ..." assistant text with no captured
        // action/plan. Re-prompt up to MAX_TURN_RETRIES before falling through to
        // the downgrade — mirrors the main AI's AUTO_RETRY so a proxy/gateway blip
        // doesn't show a raw error or stall the supervisor.
        for (let attempt = 1;
             attempt <= MAX_TURN_RETRIES
                 && !runtime.lastCapturedAction && !runtime.lastCapturedPlan
                 && isTransientApiError(turn.assistantText);
             attempt++) {
            console.error(
                `[supervisor] transient API error (retry ${attempt}/${MAX_TURN_RETRIES}) `
                + `pair=${pairId}: ${(turn.assistantText || '').slice(0, 140).replace(/\n/g, ' ')}`
            );
            await new Promise((r) => setTimeout(r, RETRY_BASE_DELAY_MS * attempt));
            if (runtime.disposed) break;
            enqueueUserText(
                '上一次响应为空或异常（API/网关返回空 200）。请忽略该错误，重新对上面的事件做出决策并调用相应工具收尾。'
            );
            turn = await collectAssistantTurn(runtime);
        }

        // emit_plan side channel: if the supervisor produced its structured plan
        // this turn, forward it to Java FIRST (before [SUPERVISOR_ACTION]) so
        // PlanStateMachine.onPlanCreated runs before any same-turn inject_prompt
        // is routed — step-0 dispatch then lands on the real plan, not a synthetic
        // one. Tagged with the same turnId as the closing action wrapper.
        if (runtime.lastCapturedPlan) {
            try {
                process.stdout.write('[SUPERVISOR_PLAN] ' + JSON.stringify({
                    pairId,
                    supervisorId,
                    turnId,
                    steps: runtime.lastCapturedPlan.steps,
                    rationale: runtime.lastCapturedPlan.rationale || '',
                }) + '\n');
            } catch (e) {
                console.error('[supervisor] failed to emit [SUPERVISOR_PLAN]: '
                    + (e?.message || String(e)));
            }
        }

        const wrapper = buildActionWrapper({
            pairId,
            supervisorId,
            assistantText: turn.assistantText,
            reasoningText: turn.reasoningText,
            capturedAction: runtime.lastCapturedAction,
        });
        wrapper.turnId = turnId;
        // A planning turn that emitted a plan but no action is valid — don't
        // surface the "(downgraded) no emit_action" parse error; convert to a
        // clean wait so the UI shows no spurious error card.
        if (wrapper.parseError === 'no_tool_use' && runtime.lastCapturedPlan) {
            wrapper.action = { action: 'wait', reason: 'plan emitted; awaiting first dispatch', payload: {} };
            wrapper.parseError = null;
        }
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
 * Interrupt the currently-running supervisor turn.
 *
 * Two-stage stop, mirroring the remote daemon (ai-bridge-server) so local and
 * remote modes behave identically:
 *   1) Query.interrupt() — graceful, preserves the SDK session/context (the
 *      SDK documents interrupt as valid only in streaming-input mode, which
 *      the supervisor channel always uses).
 *   2) query.close() hard-stop fallback — if interrupt() does not actually
 *      settle the turn within a short grace window (a turn wedged in
 *      extended-thinking stays blocked inside `await query.next()`), tear the
 *      transport down like the main-AI abort does. close() rejects the blocked
 *      next() -> collectAssistantTurn unwinds -> postEvent rejects -> the
 *      daemon writes the request's done line -> Java fires onPairThinking(false)
 *      and the thinking spinner clears.
 */
export async function interruptSupervisor(params) {
    const { pairId, supervisorId } = params || {};
    if (!pairId || !supervisorId) {
        throw new Error('supervisor.interrupt requires pairId and supervisorId');
    }
    const k = key(pairId, supervisorId);
    const runtime = runtimes.get(k);
    if (!runtime || runtime.disposed) {
        // Nothing to interrupt — already over/disposed. Success from the
        // caller's POV: emit a result line so Java settles the request and
        // clears the spinner. Do NOT throw SUPERVISOR_NOT_FOUND: interrupt is
        // fire-and-forget and an absent runtime means "already stopped".
        process.stdout.write('[SUPERVISOR_INTERRUPT_RESULT] ' + JSON.stringify({
            pairId, supervisorId, ts: Date.now(),
            interrupted: false, forceStopped: false, error: 'SUPERVISOR_NOT_FOUND',
        }) + '\n');
        return { ok: true };
    }

    // Snapshot the in-flight turn's barrier BEFORE touching anything. postEvent
    // reassigns runtime.busy per turn and clears runtime.currentTurnId in its
    // finally, so these let us tell whether the turn actually ended after
    // interrupt() — not just whether interrupt() resolved.
    const busyAtCall = runtime.busy;
    const turnWasActive = runtime.currentTurnId != null;
    let interrupted = false;
    let forceStopped = false;
    let error = null;

    // 1) Graceful interrupt — preserves the SDK session/context.
    if (typeof runtime.query?.interrupt === 'function') {
        try {
            await Promise.race([
                runtime.query.interrupt(),
                new Promise((_, reject) => setTimeout(
                    () => reject(new Error('INTERRUPT_TIMEOUT')), 3_000
                )),
            ]);
            interrupted = true;
        } catch (e) {
            error = e?.message || String(e);
        }
    } else {
        error = 'SDK does not expose Query.interrupt';
    }

    // 2) Confirm the turn actually SETTLED. interrupt() resolving only means it
    //    was accepted; a wedged turn can still be blocked inside query.next().
    //    Wait on the captured busy barrier (released by postEvent's finally)
    //    for a short grace period.
    let settled = !turnWasActive || runtime.currentTurnId == null;
    if (!settled) {
        settled = await Promise.race([
            busyAtCall.then(() => true, () => true),
            new Promise((r) => setTimeout(() => r(false), 2_500)),
        ]);
    }

    // 3) Hard-stop fallback — force the transport down like the main-AI abort.
    //    Deleting the runtime is safe: the next postEvent hits the
    //    SUPERVISOR_NOT_FOUND lazy-restart path and recreates it.
    if (!settled) {
        forceStopped = true;
        runtime.disposed = true;
        try { runtime.inputStream.done(); } catch { /* ignore */ }
        try {
            if (typeof runtime.query?.close === 'function') {
                runtime.query.close();
            } else if (typeof runtime.query?.return === 'function') {
                await Promise.race([
                    runtime.query.return(),
                    new Promise((r) => setTimeout(r, 2_000)),
                ]);
            }
        } catch (e) {
            error = error || (e?.message || String(e));
        }
        runtimes.delete(k);
    }

    process.stdout.write('[SUPERVISOR_INTERRUPT_RESULT] ' + JSON.stringify({
        pairId, supervisorId, ts: Date.now(), interrupted, forceStopped, error,
    }) + '\n');
    return { ok: true };
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

        // Session resume (2026-06-05): capture the SDK-assigned session_id the
        // first time any message carries one, forward it to Java for persistence
        // ([SUPERVISOR_SESSION]), and verify a requested resume was honoured
        // ([SUPERVISOR_RESUME_MISS] when the SDK started a different session).
        if (msg.session_id && runtime.sessionId !== msg.session_id) {
            const firstCapture = runtime.sessionId === null;
            runtime.sessionId = msg.session_id;
            if (firstCapture) {
                process.stdout.write('[SUPERVISOR_SESSION] ' + JSON.stringify({
                    pairId: runtime.pairId,
                    supervisorId: runtime.supervisorId,
                    sessionId: msg.session_id,
                }) + '\n');
                if (runtime.requestedResumeId && runtime.requestedResumeId !== msg.session_id) {
                    process.stdout.write('[SUPERVISOR_RESUME_MISS] ' + JSON.stringify({
                        pairId: runtime.pairId,
                        supervisorId: runtime.supervisorId,
                        requested: runtime.requestedResumeId,
                        actual: msg.session_id,
                    }) + '\n');
                }
            }
        }

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

/**
 * Session resume (2026-06-05): the SDK-assigned session_id for a live supervisor
 * runtime, or null if not started / not yet captured (it's filled on the first
 * turn). Used by the resume spike/integration test and as a Java-side fallback
 * read path. Accepts either ({pairId, supervisorId}) or positional args.
 */
export function getSupervisorSessionId(pairId, supervisorId) {
    if (pairId && typeof pairId === 'object') {
        supervisorId = pairId.supervisorId;
        pairId = pairId.pairId;
    }
    const runtime = runtimes.get(key(pairId, supervisorId));
    return runtime ? (runtime.sessionId || null) : null;
}
