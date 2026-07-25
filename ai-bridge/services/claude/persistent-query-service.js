/**
 * Persistent query service for daemon mode.
 * Keeps Claude Query processes alive across turns to reduce per-request latency.
 */

import { isCustomBaseUrl, loadClaudeSettings, setupApiKey, buildCliEnv } from '../../config/api-config.js';
import { selectWorkingDirectory } from '../../utils/path-utils.js';
import {
  mapModelIdToSdkName,
  resolveModelFromSettings,
  setModelEnvironmentVariables
} from '../../utils/model-utils.js';
import { canUseTool } from '../../permission-handler.js';
import { buildContentBlocks, loadAttachments } from './attachment-service.js';
import { buildIDEContextPrompt } from '../system-prompts.js';
import { buildQuickFixPrompt } from '../quickfix-prompts.js';
import { registerActiveQueryResult, removeSession } from './message-service.js';
import { normalizePermissionMode } from './permission-mode.js';
import { sanitizeSessionFileForResume, captureSessionFileSnapshot, isEncryptedContentVerificationError } from './session-service.js';
import { truncateString } from './message-output-filter.js';
import {
  beginRuntimeTurn,
  cleanupStaleAnonymousRuntimes,
  cleanupStaleSessionRuntimes,
  disposeRuntime,
  registerRuntimeSession,
  acquireRuntime,
  buildRuntimeSignature,
  endRuntimeTurn,
  resetCachedQueryFn,
  setCachedQueryFn,
  touchRuntime,
} from './runtime-lifecycle.js';
import {
  SESSION_CLEANUP_INTERVAL_MS,
  clearActiveTurnRuntime,
  clearActiveTurnRuntimeIf,
  getActiveTurnRuntime,
  getAllRuntimes,
  getRuntimeForSession,
  getSnapshot,
  resetRegistryState,
  setActiveTurnRuntime,
} from './runtime-registry.js';
import {
  createTurnState,
  emitUsageTag,
  processMessageContent,
  processStreamEvent,
  processToolResultMessages,
  shouldOutputMessage,
} from './stream-event-processor.js';

const SUPPORTED_EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

// 'ultra' is Claude Code's "ultracode" session setting, NOT an SDK effort
// level: it sends xhigh to the model AND enables dynamic workflow
// orchestration. Only meaningful on an xhigh-capable model (Opus 4.8).
// https://code.claude.com/docs/en/model-config#adjust-effort-level
const ULTRACODE_SETTINGS = { ultracode: true, enableWorkflows: true };

function normalizeReasoningEffort(value) {
  const e = typeof value === 'string' ? value.trim() : '';
  if (!e) return null;
  if (SUPPORTED_EFFORT_LEVELS.has(e)) return e;
  console.warn(`[REASONING_EFFORT] ⚠️ unsupported effort value received: ${JSON.stringify(value)} — falling back to SDK default`);
  return null;
}

// Translate the UI reasoning tier into the SDK shape. 'ultra' → xhigh effort +
// ultracode/workflow settings; everything else passes through normalizeReasoningEffort.
function resolveEffortAndSettings(rawEffort) {
  const e = typeof rawEffort === 'string' ? rawEffort.trim() : '';
  if (e === 'ultra') {
    return { effort: 'xhigh', settings: ULTRACODE_SETTINGS, ultracode: true };
  }
  return { effort: normalizeReasoningEffort(rawEffort), settings: null, ultracode: false };
}

function resolveThinkingTokens(params, settings) {
  const alwaysThinkingEnabled = settings?.alwaysThinkingEnabled ?? true;
  const configuredMax = settings?.maxThinkingTokens
    || parseInt(process.env.MAX_THINKING_TOKENS || '0', 10)
    || 10000;

  if (params.disableThinking === true) return 0;
  if (alwaysThinkingEnabled) return configuredMax;
  return undefined;
}

function resolveStreamingEnabled(params, settings) {
  return params.streaming != null
    ? !!params.streaming
    : (settings?.streamingEnabled ?? false);
}

// ---------------------------------------------------------------------------
// Background-task "keep the turn open" support (2026-07-13)
//
// ultracode Workflows (and any run_in_background task) settle AFTER the turn's
// `result`. Empirically (SDK 0.3.198) the SDK wakes the session on its own when
// such a task settles: it emits `task_notification` + a full follow-up turn on
// the SAME query iterator, with no extra input from us. The old loop broke on
// the first `result`, so everything after it (the workflow's completion + the
// model's follow-up) was never drained — the chat only ever saw the "I'll wait
// for the workflow" turn and then went silent. Fix: keep draining past an
// intermediate `result` while any background task is still in flight.
//
// In-flight tracking, from the `system/task_*` stream messages:
//   task_started            -> add(task_id)
//   task_notification       -> delete(task_id)   (settled: completed/failed/stopped)
//   task_updated {terminal} -> delete(task_id)   (completed/failed/killed)
// A foreground subagent settles (task_notification + terminal task_updated)
// BEFORE its own turn's `result`, so a normal turn's set is empty at `result`:
// no added latency, and no risk of hanging a plain turn.
const TERMINAL_TASK_STATUSES = new Set(['completed', 'failed', 'killed', 'stopped']);

function trackBackgroundTask(pending, msg) {
  if (msg?.type !== 'system' || !msg.task_id) return;
  if (msg.subtype === 'task_started') {
    pending.add(msg.task_id);
  } else if (msg.subtype === 'task_notification') {
    pending.delete(msg.task_id);
  } else if (msg.subtype === 'task_updated' && TERMINAL_TASK_STATUSES.has(msg.patch?.status)) {
    pending.delete(msg.task_id);
  }
}

// Backstop: if we're parked waiting for a background task and the stream goes
// silent for this long, stop waiting so the UI is never locked forever. A real
// workflow emits task_progress far more often than this, so it never trips; the
// Stop button (abort -> disposeRuntime) is the primary escape hatch. Override
// with AI_BRIDGE_WORKFLOW_SILENCE_MS.
const WORKFLOW_SILENCE_TIMEOUT_MS = (() => {
  const raw = parseInt(process.env.AI_BRIDGE_WORKFLOW_SILENCE_MS || '', 10);
  return Number.isFinite(raw) && raw > 0 ? raw : 15 * 60 * 1000;
})();

// Pair mode: the load-bearing instruction that makes the main AI call
// report_turn_completion. Appended to the system prompt only when the request
// carries a pair-context marker. Ported from ai-bridge-server so local mode
// behaves the same as remote.
const PAIR_MODE_SYSTEM_PROMPT_APPEND = [
  '# Supervisor Pair 模式约束',
  '',
  '你现在在 Supervisor Pair 协作模式下工作。supervisor 是你的协作者(类似 PM / 架构师),通过 `inject_prompt` 派任务,你执行后必须**结构化汇报**结果。',
  '',
  '## 强约束:每个 turn 必调 `report_turn_completion`',
  '',
  '在每个 turn 结束前,你**必须**调用 `mcp__main__report_turn_completion` 工具汇报本轮工作。',
  '这是硬性要求:只要 supervisor 通过 inject_prompt 给你派了任务,**无论本轮是分析 / 读取 / 调研还是编码**,turn 结束前都必须调用一次 report_turn_completion——**分析类任务也要调**(结论写进 `summary`,产出或被分析的文件写进 `deliverables`;本轮没改任何文件时 `deliverables` 给空数组即可)。',
  '**禁止**以纯文本结论结尾而不调用该工具:那样 supervisor 收不到结构化回执(selfAssessment / deliverables),只能走降级兜底,review 分诊会失真。唯一可不调的情形:本轮你要向 supervisor 反问澄清——此时改用澄清渠道,而不是停在纯文本提问。',
  '',
  '工具入参:',
  '- `summary`: 1-2 句任务级摘要',
  '- `deliverables`: 你新增/修改的文件清单(相对项目根 POSIX 路径)+ 每个文件 `change` 描述 + 可选 `confidence`',
  '- `verifications` (可选): 你跑过的命令 + pass/fail + 失败时 stderrTail (<=1KB)',
  '- `selfAssessment` (必填):',
  '  - `confidence`: high / medium / low',
  '  - `concerns`: 你自己觉得不踏实的具体点(空数组表示完全自信)',
  '  - `suggestedReview` (可选): 建议 supervisor 重点 review 哪里 (如 "user_dao.go:42-58")',
  '',
  '## confidence 怎么定',
  '- **high**: 做完 + verifications 全 pass + 没有 unaddressed concerns → supervisor 信任直接通过',
  '- **medium**: 主流程对了但某些边界没验证 → supervisor 会自己 Read 验证',
  '- **low**: 你按指令做了但心里没底 → supervisor 必派 code reviewer 子 agent 重 review',
  '',
  '**不要骗 supervisor** — 故意报 high 但实际有问题,后续会被发现,trust 降级到全部强制 reviewer。',
  '',
  '## inject_prompt 收到后',
  'supervisor 派来的 inject_prompt 是结构化任务派单,含 `objective` + `expectedDeliverables` (期望产出路径) + `acceptanceCriteria` (验收标准)。如果含 `spilledPath` (>8KB 指令存在磁盘文件), 先 Read 该路径再执行。',
  '',
  '你的 `deliverables` 应覆盖 `expectedDeliverables` 中所有路径(可以多但不能少)。',
  '',
  '## 你内部的子 agent',
  '你**可以**派 Task 子 agent (并行/隔离),这是你的内部行为不需要向 supervisor 解释。**但子 agent 的产出也算你的 deliverables**——子 agent 改的文件,你的 `deliverables` 数组也要列。',
  '',
  '## turn 收尾规则',
  '每个 turn 必须以下三者之一结尾,**禁止**以"请发送..."、"我将立即..."、"等待..."、"请告诉我..."等等待型语句结束:',
  '1. **至少一个 tool_use** (实际执行了操作)',
  '2. **`mcp__main__report_turn_completion`** 调用 (显式汇报完成 / 阻塞 / 需澄清)',
  '3. 显式的澄清请求',
  '违反此规则会被系统检测为"对话漂移",立即触发重推消息,影响协作效率。',
  '',
  'supervisor 在 plan 全部 step 完成后会调 `emit_action(complete_plan)` 收尾——你不需要"等下个 tick 检测 isComplete",你的最后一轮 report_turn_completion 就是收尾。',
].join('\n');

// Peel a leading <!--pair-context:{...}--> marker (prepended by Java's
// SessionSendService.prependPairContextMarker) out of the raw systemPromptAppend
// IPC string into a structured pairContext object. No marker → pairContext null.
const PAIR_CTX_MARKER_RE = /^<!--pair-context:({[\s\S]+?})-->\r?\n?/;
function extractInlinedPairContext(systemPromptAppendRaw) {
  if (typeof systemPromptAppendRaw !== 'string' || systemPromptAppendRaw.length === 0) {
    return { pairContext: null, cleaned: systemPromptAppendRaw };
  }
  const m = systemPromptAppendRaw.match(PAIR_CTX_MARKER_RE);
  if (!m) return { pairContext: null, cleaned: systemPromptAppendRaw };
  let parsed = null;
  try { parsed = JSON.parse(m[1]); } catch (_) { /* malformed, ignore */ }
  return { pairContext: parsed, cleaned: systemPromptAppendRaw.slice(m[0].length) };
}

function buildSystemPromptAppend(params) {
  const openedFiles = params.openedFiles || null;
  const agentPrompt = params.agentPrompt || null;
  if (openedFiles && openedFiles.isQuickFix) {
    return buildQuickFixPrompt(openedFiles, params.message || '');
  }
  return buildIDEContextPrompt(openedFiles, agentPrompt);
}

function buildQueryOptions(workingDirectory, sdkModelName, permissionMode, maxThinkingTokens, streamingEnabled, systemPromptAppend, requestedSessionId, reasoningEffort, windowId, extraSettings, cliEnv) {
  // Close over windowId so AskUserQuestion's file-IPC request can be tagged
  // with the originating tab. The Java-side PermissionService uses this to
  // decide whether the tab is currently in supervisor pair mode (no popup)
  // or normal mode (popup). When windowId is null, the Java side falls back
  // to a project-wide pair check.
  const wrappedCanUseTool = (toolName, input, opts = {}) =>
    canUseTool(toolName, input, { ...opts, _windowId: windowId || null });
  return {
    cwd: workingDirectory,
    permissionMode,
    model: sdkModelName,
    maxTurns: 100,
    enableFileCheckpointing: true,
    env: cliEnv,
    ...(maxThinkingTokens !== undefined && { maxThinkingTokens }),
    ...(reasoningEffort && { effort: reasoningEffort }),
    ...(extraSettings && { settings: extraSettings }),
    ...(streamingEnabled && { includePartialMessages: true }),
    additionalDirectories: Array.from(
      new Set(
        [workingDirectory, process.env.IDEA_PROJECT_PATH, process.env.PROJECT_PATH].filter(Boolean)
      )
    ),
    canUseTool: wrappedCanUseTool,
    settingSources: ['user', 'project', 'local'],
    systemPrompt: {
      type: 'preset',
      preset: 'claude_code',
      ...(systemPromptAppend && { append: systemPromptAppend })
    },
    ...(requestedSessionId && { resume: requestedSessionId })
  };
}

async function buildUserMessage(params, withAttachments, requestedSessionId) {
  if (withAttachments) {
    const attachments = await loadAttachments({ attachments: params.attachments || [] });
    const contentBlocks = buildContentBlocks(attachments, params.message || '');
    return {
      type: 'user',
      session_id: requestedSessionId || '',
      parent_tool_use_id: null,
      message: { role: 'user', content: contentBlocks }
    };
  }

  const userText = (params.message || '').trim() || '[Empty message]';
  return {
    type: 'user',
    session_id: requestedSessionId || '',
    parent_tool_use_id: null,
    message: { role: 'user', content: [{ type: 'text', text: userText }] }
  };
}

async function buildRequestContext(params, withAttachments) {
  setupApiKey();

  const baseUrl = process.env.ANTHROPIC_BASE_URL || process.env.ANTHROPIC_API_URL || '';
  if (isCustomBaseUrl(baseUrl)) {
    console.debug('[DEBUG] Custom Base URL detected');
  }

  const requestedSessionId = (typeof params.sessionId === 'string' && params.sessionId.trim() !== '')
    ? params.sessionId.trim()
    : null;
  const runtimeSessionEpoch = (typeof params.runtimeSessionEpoch === 'string' && params.runtimeSessionEpoch.trim() !== '')
    ? params.runtimeSessionEpoch.trim()
    : null;

  const workingDirectory = selectWorkingDirectory(params.cwd || null);
  try {
    process.chdir(workingDirectory);
  } catch (error) {
    console.error('[WARNING] Failed to change process.cwd():', error.message);
  }

  const settings = loadClaudeSettings();
  const modelId = params.model || null;
  const sdkModelName = mapModelIdToSdkName(modelId);
  const resolvedModel = resolveModelFromSettings(modelId, settings?.env);
  setModelEnvironmentVariables(resolvedModel, modelId);

  const permissionMode = normalizePermissionMode(params.permissionMode);
  const streamingEnabled = resolveStreamingEnabled(params, settings);
  const { effort: normalizedReasoningEffort, settings: ultracodeSettings } = resolveEffortAndSettings(params.reasoningEffort);
  // effort 与 maxThinkingTokens 互斥
  const maxThinkingTokens = normalizedReasoningEffort
    ? undefined
    : resolveThinkingTokens(params, settings);
  // Pair mode: Java prepends a <!--pair-context:{...}--> marker onto the
  // systemPromptAppend IPC field. Peel it into a structured pairContext; when
  // present, append the load-bearing "must call report_turn_completion" rule so
  // the main AI reliably calls the tool, and signal createRuntime to mount the
  // mcp__main MCP server. Non-Pair sends see no marker → unchanged behaviour.
  const { pairContext } = extractInlinedPairContext(params.systemPromptAppend);
  const pairId = (pairContext && typeof pairContext.pairId === 'string' && pairContext.pairId.length > 0)
    ? pairContext.pairId : null;
  const baseSystemPromptAppend = buildSystemPromptAppend(params);
  const systemPromptAppend = pairId
    ? [baseSystemPromptAppend, PAIR_MODE_SYSTEM_PROMPT_APPEND].filter(Boolean).join('\n\n')
    : baseSystemPromptAppend;

  if (ultracodeSettings) {
    console.log(`[REASONING_EFFORT] ✓ persistent buildRequestContext applied ULTRACODE (effort=xhigh + settings.ultracode/enableWorkflows) (model=${sdkModelName ?? modelId ?? 'default'}, maxThinkingTokens disabled due to mutex)`);
  } else if (normalizedReasoningEffort) {
    console.log(`[REASONING_EFFORT] ✓ persistent buildRequestContext applied options.effort=${normalizedReasoningEffort} (model=${sdkModelName ?? modelId ?? 'default'}, maxThinkingTokens disabled due to mutex)`);
  } else {
    console.log(`[REASONING_EFFORT] ⊝ persistent buildRequestContext: no effort set (model=${sdkModelName ?? modelId ?? 'default'}, maxThinkingTokens=${maxThinkingTokens ?? 'undefined'}, raw=${JSON.stringify(params.reasoningEffort ?? null)})`);
  }

  // Tab identity stamped by Java (ClaudeRequestParamsBuilder). When absent
  // (e.g. system-level senders like GitCommitMessageService) we pass null
  // and Java's PermissionService falls back to a project-wide pair check.
  const windowId = (typeof params.windowId === 'string' && params.windowId.trim() !== '')
    ? params.windowId.trim()
    : null;

  const options = buildQueryOptions(
    workingDirectory, sdkModelName, permissionMode,
    maxThinkingTokens, streamingEnabled, systemPromptAppend, requestedSessionId,
    normalizedReasoningEffort, windowId, ultracodeSettings,
    await buildCliEnv()
  );

  // Third-party Claude-protocol gateways (GPT-5.x) can only verify the encrypted
  // reasoning content they produced under their current key/instance. The SDK's
  // native resume replays the persisted session JSONL verbatim — including those
  // encrypted thinking blocks — so after a gateway key rotation / instance switch
  // every resume of that session fails with:
  //   400 The encrypted content gAAA... could not be verified.
  // Strip the blocks from the session file before the CLI reads it (custom base
  // URL only; the official API verifies signatures fine). Skipped while a live
  // runtime owns the session: its in-memory context is authoritative and an
  // in-place rewrite could race the CLI's appends.
  if (requestedSessionId && isCustomBaseUrl(baseUrl) && !getRuntimeForSession(requestedSessionId)) {
    sanitizeSessionFileForResume(requestedSessionId, workingDirectory);
  }

  const userMessage = await buildUserMessage(params, withAttachments, requestedSessionId);

  const runtimeSignature = buildRuntimeSignature(
    options, systemPromptAppend, streamingEnabled, runtimeSessionEpoch, pairId
  );
  console.log('[LIFECYCLE] buildRequestContext sessionId=' + (requestedSessionId || '(new)')
    + ' epoch=' + (runtimeSessionEpoch || '(none)')
    + ' signature=' + runtimeSignature
    + (pairId ? ' pairId=' + pairId : ''));

  return {
    requestedSessionId,
    runtimeSessionEpoch,
    streamingEnabled,
    options,
    userMessage,
    sdkModelName,
    permissionMode,
    maxThinkingTokens,
    runtimeSignature,
    windowId,
    // Pair mode: consumed by createRuntime to mount the mcp__main MCP server
    // (report_turn_completion). Null for normal sends.
    pairContext: pairId
      ? { pairId, activeDirectiveId: (typeof pairContext.activeDirectiveId === 'string' ? pairContext.activeDirectiveId : null) }
      : null,
  };
}

// Background cleanup of idle session runtimes, decoupled from the request hot path.
// Runs every 5 minutes instead of on every acquireRuntime call to avoid O(n) scans.
const _sessionCleanupTimer = setInterval(async () => {
  await cleanupStaleSessionRuntimes({ registerActiveQueryResult, removeSession });
}, SESSION_CLEANUP_INTERVAL_MS);
// unref() so the timer does not prevent natural process exit
_sessionCleanupTimer.unref();

async function executeTurn(runtime, requestContext, turnMeta) {
  if (!runtime || runtime.closed) {
    const err = new Error('Runtime is closed');
    err.runtimeTerminated = true;
    throw err;
  }

  setActiveTurnRuntime(runtime);
  console.log('[LIFECYCLE] executeTurn sessionId=' + (requestContext.requestedSessionId || runtime.sessionId || '(new)')
    + ' epoch=' + (requestContext.runtimeSessionEpoch || runtime.runtimeSessionEpoch || '(none)'));

  const turnState = createTurnState(requestContext, runtime);
  if (turnMeta) {
    turnMeta.state = turnState;
  }

  // Snapshot the persisted session file BEFORE this turn appends to it. If the
  // turn dies on a poisoned-context 400 (unverifiable encrypted thinking), the
  // repair path in sendInternal drops exactly this turn's appended lines and
  // retries against the pre-turn history. Null for brand-new sessions (no file
  // yet) and silently absent on any read failure — repair degrades gracefully.
  turnState.sessionFileSnapshot = captureSessionFileSnapshot(
    requestContext.requestedSessionId || runtime.sessionId || null,
    requestContext.options?.cwd || null
  );

  // Background-task "keep the turn open" state (see helpers above). While any
  // background task (ultracode Workflow / run_in_background) is in flight, an
  // incoming `result` is NOT the end of the turn — we keep draining so the
  // completion notification + follow-up turn reach the UI.
  const pendingBackgroundTasks = new Set();
  let silenceTimer = null;
  const clearSilenceWatchdog = () => {
    if (silenceTimer) { clearTimeout(silenceTimer); silenceTimer = null; }
  };
  const rearmSilenceWatchdog = () => {
    clearSilenceWatchdog();
    if (pendingBackgroundTasks.size === 0) return;
    silenceTimer = setTimeout(() => {
      silenceTimer = null;
      console.error('[WORKFLOW_WAIT_TIMEOUT] ' + JSON.stringify({
        pending: Array.from(pendingBackgroundTasks),
        silenceMs: WORKFLOW_SILENCE_TIMEOUT_MS,
      }));
      // Close the query so the parked query.next() unwinds and the turn ends.
      disposeRuntime(runtime, { removeSession }).catch(() => {});
    }, WORKFLOW_SILENCE_TIMEOUT_MS);
    silenceTimer.unref?.();
  };

  try {
    beginRuntimeTurn(runtime);
    console.log('[MESSAGE_START]');
    runtime.inputStream.enqueue(requestContext.userMessage);

    while (true) {
      let next;
      try {
        next = await runtime.query.next();
      } catch (error) {
        const wrapped = new Error(error?.message || String(error));
        wrapped.runtimeTerminated = true;
        throw wrapped;
      }

      if (next.done) {
        const err = new Error('Claude session stream ended unexpectedly');
        err.runtimeTerminated = true;
        throw err;
      }

      touchRuntime(runtime);
      const msg = next.value;

      if (turnState.streamingEnabled && !turnState.streamStarted) {
        process.stdout.write('[STREAM_START]\n');
        turnState.streamStarted = true;
      }

      if (msg?.type === 'stream_event' && turnState.streamingEnabled) {
        turnState.hasStreamEvents = true;
        processStreamEvent(msg, turnState);
        continue;
      }

      // Update in-flight background-task bookkeeping, then (re)arm the silence
      // backstop while anything is still outstanding.
      trackBackgroundTask(pendingBackgroundTasks, msg);
      rearmSilenceWatchdog();

      // An intermediate `result` — the turn "ended" but a background task is
      // still running. Do NOT forward it (the client would finalize the turn)
      // and do NOT break: the SDK re-invokes the model when the task settles, so
      // we keep draining and the follow-up turn + its own final `result` arrive.
      if (msg?.type === 'result' && !msg.is_error && pendingBackgroundTasks.size > 0) {
        console.error('[WORKFLOW_WAIT] ' + JSON.stringify({ pending: pendingBackgroundTasks.size }));
        continue;
      }

      // Error results end the turn with an exception. Classify the poisoned-context
      // 400 (encrypted thinking replayed to a gateway that can no longer verify it)
      // BEFORE any forwarding: sendInternal auto-repairs + retries that failure
      // transparently, so its raw upstream text must NOT be rendered into the chat.
      // Other error results keep the legacy behaviour (forward, then throw).
      if (msg?.type === 'result' && msg.is_error) {
        const errText = [msg.result, msg.message, ...(Array.isArray(msg.errors) ? msg.errors : [])]
          .filter((part) => typeof part === 'string' && part.length > 0)
          .join('; ') || 'API request failed';
        const stderrTail = Array.isArray(runtime.stderrLines)
          ? runtime.stderrLines.slice(-10).join('\n')
          : '';
        const resultError = new Error(errText);
        if (isEncryptedContentVerificationError(`${errText}\n${stderrTail}`)) {
          resultError.encryptedContentError = true;
          throw resultError;
        }
        if (shouldOutputMessage(msg, turnState)) {
          console.log('[MESSAGE]', JSON.stringify(msg));
        }
        throw resultError;
      }

      if (shouldOutputMessage(msg, turnState)) {
        console.log('[MESSAGE]', JSON.stringify(msg));
      }

      processMessageContent(msg, turnState);
      // Emit usage tag for assistant messages.
      // IMPORTANT: This is the authoritative source for token usage, NOT the accumulatedUsage.
      // The assistant message's usage field contains the correct cumulative total.
      // In streaming mode, this overwrites any intermediate [USAGE] values sent during streaming.
      // The Java backend (ClaudeMessageHandler.handleAssistantMessage) relies on this for correct totals.
      emitUsageTag(msg);
      processToolResultMessages(msg);

      if (msg?.type === 'system' && msg.session_id) {
        turnState.finalSessionId = msg.session_id;
        console.log('[SESSION_ID]', msg.session_id);
        registerRuntimeSession(runtime, msg.session_id, { registerActiveQueryResult, removeSession });
      }

      if (msg?.type === 'result') {
        // Error results were already thrown above; reaching here means success.
        break;
      }
    }

    if (turnState.streamingEnabled && turnState.streamStarted && !turnState.streamEnded) {
      // NOTE: Do NOT emit accumulatedUsage at stream end.
      // The assistant message's usage (sent via emitUsageTag above) is the authoritative final value.
      // Emitting accumulatedUsage here would send a redundant or potentially stale value.
      process.stdout.write('[STREAM_END]\n');
      turnState.streamEnded = true;
    }

    const finalSessionId = turnState.finalSessionId || runtime.sessionId || requestContext.requestedSessionId || '';
    if (finalSessionId) {
      registerRuntimeSession(runtime, finalSessionId, { registerActiveQueryResult, removeSession });
    }

    console.log('[MESSAGE_END]');
    console.log(JSON.stringify({
      success: true,
      sessionId: finalSessionId
    }));
  } finally {
    clearSilenceWatchdog();
    endRuntimeTurn(runtime);
    // Only clear if this runtime still owns the pointer (not cleared by abort)
    clearActiveTurnRuntimeIf(runtime);
  }
}

// Pattern matches Anthropic API rejection when 1M context beta is requested
// without the entitlement (paid credits / Tier 4). The exact phrase has been
// stable; we keep it case-insensitive and forgiving to minor wording shifts.
const LONG_CONTEXT_NOT_ENTITLED_PATTERN = /usage credits.*required.*long\s*context|long\s*context.*requires?.*credits/i;

function detectClaudeErrorCode(messageText) {
  if (typeof messageText !== 'string' || !messageText) return null;
  if (LONG_CONTEXT_NOT_ENTITLED_PATTERN.test(messageText)) {
    return 'LONG_CONTEXT_NOT_ENTITLED';
  }
  return null;
}

function emitSendError(runtime, error, requestContext) {
  const payload = {
    success: false,
    error: error?.message || String(error),
    details: {}
  };

  if (error?.code) payload.details.code = error.code;
  if (error?.stack) payload.details.stack = truncateString(error.stack, 2000);

  if (runtime?.stderrLines?.length) {
    const sdkErrorText = runtime.stderrLines.slice(-10).join('\n');
    payload.error = `SDK-STDERR:\n\`\`\`\n${sdkErrorText}\n\`\`\`\n\n${payload.error}`;
    payload.details.sdkError = sdkErrorText;
  }

  payload.error = truncateString(payload.error, 2500);

  // Classify well-known API errors so the UI can self-correct (e.g. auto-disable
  // 1M context toggle when the account lacks the entitlement) without showing
  // the raw upstream wording.
  const claudeErrorCode = detectClaudeErrorCode(payload.error);
  if (claudeErrorCode) {
    payload.code = claudeErrorCode;
  }

  console.error('[SEND_ERROR]', JSON.stringify(payload));
  console.log('[SEND_ERROR]', JSON.stringify(payload));
  console.log(JSON.stringify(payload));
}

async function sendInternal(params, withAttachments) {
  const safeParams = params || {};
  const turnMeta = { state: null };
  let runtime = null;
  let requestContext = null;
  try {
    requestContext = await buildRequestContext(safeParams, withAttachments);
    runtime = await acquireRuntime(requestContext, { registerActiveQueryResult, removeSession });
    await executeTurn(runtime, requestContext, turnMeta);
  } catch (error) {
    // Only clear if this runtime still owns the pointer (not cleared by abort)
    clearActiveTurnRuntimeIf(runtime);

    // Safety-net classification: the 400 can also surface as a thrown iterator
    // error (query.next()) or a runtime-creation failure rather than a result
    // message — check the stderr tail as well before deciding repair is moot.
    if (error && !error.encryptedContentError) {
      const stderrTail = Array.isArray(runtime?.stderrLines)
        ? runtime.stderrLines.slice(-10).join('\n')
        : '';
      if (isEncryptedContentVerificationError(`${error?.message || error}\n${stderrTail}`)) {
        error.encryptedContentError = true;
      }
    }

    // Poisoned-context self-repair. The API rejected replayed encrypted
    // thinking (400 "The encrypted content ... could not be verified") — the
    // live runtime keeps that block in its in-memory context and the persisted
    // session file carries it too, so an unrepaired session fails EVERY later
    // send the same way (the runtime was previously kept alive on result
    // errors, permanently bricking the session). Repair: dispose the poisoned
    // runtime, strip the unverifiable blocks from the session file (dropping
    // this turn's partial lines when a pre-turn snapshot exists), then retry
    // the send once on a fresh runtime.
    if (error?.encryptedContentError && !safeParams.__encryptedContentRetried) {
      const resumeSessionId = await repairEncryptedContentFailure({ runtime, requestContext, turnMeta });
      const retryParams = { ...safeParams, __encryptedContentRetried: true };
      if (resumeSessionId) {
        retryParams.sessionId = resumeSessionId;
      }
      console.log('[ENCRYPTED_CONTENT_RETRY] retrying send once after session repair (resume='
        + (resumeSessionId || '(unchanged)') + ')');
      await sendInternal(retryParams, withAttachments);
      return;
    }

    if (turnMeta.state?.streamingEnabled && turnMeta.state?.streamStarted && !turnMeta.state?.streamEnded) {
      // NOTE: Do NOT emit accumulatedUsage at stream end, even on error.
      // If an assistant message was received, emitUsageTag already sent the correct usage.
      // If no assistant message was received, the usage would be incomplete anyway.
      process.stdout.write('[STREAM_END]\n');
      turnMeta.state.streamEnded = true;
    }
    emitSendError(runtime, error, requestContext);
    // Dispose runtimes that can no longer serve future turns: process death
    // (runtimeTerminated) or a poisoned in-memory context that failed even
    // after the repair retry above (encryptedContentError). Disposal lets the
    // next send rebuild from the (repaired) session file.
    if (runtime && !runtime.closed && (error?.runtimeTerminated || error?.encryptedContentError)) {
      await disposeRuntime(runtime, { removeSession });
    }
  }
}

/**
 * Dispose the runtime whose in-memory context holds the unverifiable encrypted
 * block and repair the persisted session file so a follow-up send can resume.
 *
 * @returns {Promise<string|null>} sessionId to resume on the retry, or null to
 *   keep the retry's original routing (e.g. when the failure predates any
 *   persisted session, or the repair itself failed).
 */
async function repairEncryptedContentFailure({ runtime, requestContext, turnMeta }) {
  try {
    const sessionId = turnMeta?.state?.finalSessionId
      || runtime?.sessionId
      || requestContext?.requestedSessionId
      || null;
    const cwd = requestContext?.options?.cwd || null;

    // The live runtime replays the unverifiable block from memory on every
    // later request — it must not serve another send.
    if (runtime && !runtime.closed) {
      await disposeRuntime(runtime, { removeSession });
    }
    if (!sessionId) {
      return null;
    }

    const snapshot = turnMeta?.state?.sessionFileSnapshot || null;
    // With a pre-turn snapshot, drop exactly this turn's appended lines
    // (hash-verified against external rewrites) in addition to stripping the
    // thinking blocks, so the retry replays clean pre-turn history. Without a
    // snapshot (file did not exist at turn start), sanitize whatever the file
    // holds now. Deliberately NOT gated on a custom base URL: the API already
    // rejected this content, so replaying it can only fail again.
    sanitizeSessionFileForResume(sessionId, cwd, snapshot
      ? { truncateToLineCount: snapshot.lineCount, expectedLastLineHash: snapshot.lastLineHash }
      : null);
    return sessionId;
  } catch (repairError) {
    console.error('[ENCRYPTED_CONTENT_REPAIR_ERROR]', repairError?.message || repairError);
    return null;
  }
}

export async function sendMessagePersistent(params = {}) {
  await sendInternal(params, false);
}

export async function sendMessageWithAttachmentsPersistent(params = {}) {
  await sendInternal(params, true);
}

export async function preconnectPersistent(params = {}) {
  const safeParams = params || {};
  const requestContext = await buildRequestContext(safeParams, false);
  console.log('[LIFECYCLE] preconnectPersistent epoch=' + (requestContext.runtimeSessionEpoch || '(none)'));
  await acquireRuntime(requestContext, { registerActiveQueryResult, removeSession });
}

export async function resetRuntimePersistent(params = {}) {
  const runtimeSessionEpoch = typeof params === 'string'
    ? params
    : (params?.runtimeSessionEpoch || null);

  console.log('[LIFECYCLE] resetRuntimePersistent targetEpoch=' + (runtimeSessionEpoch || '(all-runtimes)'));

  const runtimes = getAllRuntimes();

  for (const runtime of runtimes) {
    if (!runtimeSessionEpoch || runtime.runtimeSessionEpoch === runtimeSessionEpoch) {
      await disposeRuntime(runtime, { removeSession });
    }
  }
}

export async function abortCurrentTurn() {
  // Atomic swap: clear first to prevent double-disposal from rapid abort calls.
  // JS is single-threaded so assignment is atomic — only the first caller gets
  // a non-null runtime, subsequent callers see null and exit early.
  const runtime = getActiveTurnRuntime();
  if (!runtime) return;
  console.log('[LIFECYCLE] abortCurrentTurn epoch=' + (runtime.runtimeSessionEpoch || '(none)'));
  clearActiveTurnRuntime();

  try {
    if (!runtime.closed) {
      await disposeRuntime(runtime, { removeSession });
    }
  } catch (error) {
    // Best-effort — log but don't throw so abort always "succeeds"
    console.error('[ABORT] Failed to dispose runtime:', error.message);
  }
}

export async function shutdownPersistentRuntimes() {
  const all = getAllRuntimes();
  for (const runtime of all) {
    await disposeRuntime(runtime, { removeSession });
  }
  resetRegistryState();
  resetCachedQueryFn();
}

export const __testing = {
  async resetState() {
    await shutdownPersistentRuntimes();
    clearActiveTurnRuntime();
  },
  setQueryFn(queryFn) {
    setCachedQueryFn(queryFn);
  },
  async buildRequestContext(params = {}, withAttachments = false) {
    return buildRequestContext(params, withAttachments);
  },
  async acquireRuntime(requestContext) {
    return acquireRuntime(requestContext, { registerActiveQueryResult, removeSession });
  },
  async executeTurn(runtime, requestContext, turnMeta = null) {
    return executeTurn(runtime, requestContext, turnMeta);
  },
  async cleanupAnonymousRuntimes() {
    return cleanupStaleAnonymousRuntimes({ registerActiveQueryResult, removeSession });
  },
  async cleanupSessionRuntimes() {
    return cleanupStaleSessionRuntimes({ registerActiveQueryResult, removeSession });
  },
  async resetRuntimePersistent(params = {}) {
    return resetRuntimePersistent(params);
  },
  async abortCurrentTurn() {
    return abortCurrentTurn();
  },
  setActiveTurnRuntime(runtime) {
    setActiveTurnRuntime(runtime);
  },
  getRuntimeForSession(sessionId) {
    return getRuntimeForSession(sessionId);
  },
  getSnapshot() {
    return getSnapshot();
  }
};
