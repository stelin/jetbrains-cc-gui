/**
 * Event Summarizer.
 *
 * Java's PairSession EventBus forwards filtered Main-AI events to a Supervisor
 * session as compact, structured summaries — not raw NDJSON streams. The
 * summarizer here is the canonical formatter on the daemon side: it normalizes
 * incoming event objects to a small human/LLM-friendly text payload that the
 * Supervisor LLM consumes as a `user` message.
 *
 * Token-saving rules:
 *   - tool_use individual events: NOT forwarded (rolled up into turn_end)
 *   - content_delta: NOT forwarded
 *   - turn_end / error / idle_timeout / off_plan_detected / step_verify_*: forwarded
 *
 * The Java EventFilter is the source of truth for "what to forward". This
 * module is the source of truth for "how the forwarded payload looks".
 */

/**
 * @param {object} event
 * @param {string} event.type - 'turn_end' | 'error' | 'idle_timeout' | 'off_plan_detected'
 *                              | 'verify_result' | 'review_result' | 'human_response'
 * @param {object} [event.payload]
 * @returns {string} markdown-flavored block to be sent as a user message
 */
/**
 * Planning directive prepended to a task-bearing event (user_input / start)
 * when Java flags it via {@code payload.planningRequired} (set when no plan
 * exists yet). Tells the supervisor to produce its structured plan via emit_plan
 * before anything else. See
 * docs/supervisor/supervisor-plan-generation-and-report-spill-design.md.
 */
const PLANNING_DIRECTIVE_LINES = [
  '## [PLANNING_REQUIRED]',
  '这是本任务的首条任务事件。你的**第一步**：调用 `emit_plan` 把任务拆成有序步骤，',
  '每步写明 owner（默认 MAIN_AI）和**验收标准**（之后据此 Read 真实产物核验，不是听主 AI 自述）。',
  '只做结构化、不发明目标。emit_plan 之后可在同一轮 `emit_action(inject_prompt)` 派发第 1 步。',
  '计划随后锁定：要改走 `emit_action(request_amendment)`，不要再次 emit_plan。',
  '',
];

/**
 * Resume directive prepended to the first event after a restart that restored a
 * non-terminal plan (Java sets {@code payload.resuming}). Encodes the IN_PROGRESS
 * reconciliation rule: trust DONE, skip TODO, re-verify the in-progress step.
 */
const RESUME_DIRECTIVE_LINES = [
  '## [RESUME] 你在恢复一个已有进度的计划',
  '核验规则：**已完成(DONE)步**信任、不复查；**未开始(TODO)步**不查；',
  '**正在执行(IN_PROGRESS)步必须对账**——先 Read 现场、对照该步验收标准判断实际完成度（可能已部分完成），',
  '再决定续做剩余 / approve_and_continue(mark_step_complete) / inject_prompt 重做。',
  '可 Read `plan.md` 看完整计划与进度，不要凭记忆臆断。',
  '',
];

export function summarizeEvent(event) {
  if (!event || typeof event !== 'object') {
    return '## EVENT [unknown]\n(empty event)';
  }

  const type = event.type || 'unknown';
  const p = event.payload || {};
  const elapsed = typeof p.elapsedSeconds === 'number'
    ? `T+${p.elapsedSeconds}s`
    : (p.timestamp ? new Date(p.timestamp).toISOString() : 'now');

  const body = summarizeEventBody(type, p, elapsed);
  // P5 resume: prepend the IN_PROGRESS reconciliation directive on the first
  // event after a restart that restored a non-terminal plan (Java sets resuming).
  if (p && p.resuming) {
    return RESUME_DIRECTIVE_LINES.join('\n') + '\n\n' + body;
  }
  return body;
}

function summarizeEventBody(type, p, elapsed) {
  switch (type) {
    case 'turn_end':
      return formatTurnEnd(p, elapsed);
    case 'error':
      return formatError(p, elapsed);
    case 'idle_timeout':
      return formatIdle(p, elapsed);
    case 'off_plan_detected':
      return formatOffPlan(p, elapsed);
    case 'verify_result':
      return formatVerify(p, elapsed);
    case 'review_result':
      return formatReview(p, elapsed);
    case 'human_response':
      return formatHumanResponse(p, elapsed);
    case 'user_input':
      return formatUserInput(p, elapsed);
    case 'start':
      return formatStart(p, elapsed);
    case 'composite_summary':
      return formatComposite(p, elapsed);
    default:
      return `## EVENT [${elapsed} | ${type}]\n${safeJson(p)}`;
  }
}

/**
 * 2026-06-11 (next-step dispatch fix): rendered when Java flags
 * payload.nextStepToDispatch — the plan is in PENDING_DECISION with an
 * UNDISPATCHED next TODO step and nothing in flight (the supervisor just
 * approved the previous step). The post-approve wake otherwise carries an empty
 * batch whose text says "emit_action wait", so the supervisor never dispatches
 * the next step. This block replaces that with an explicit "dispatch it now"
 * directive, framed as a first-dispatch (not an advance) so it never waits for a
 * turn_report nobody can produce.
 */
function buildNextStepDispatchLines(ns) {
  const idx = Number.isFinite(ns.index) ? ns.index : null;
  const total = Number.isFinite(ns.total) ? ns.total : null;
  const title = (typeof ns.title === 'string' && ns.title) ? ns.title : null;
  const label = idx != null
    ? `step ${idx}${total != null ? '/' + total : ''}${title ? ` 「${title}」` : ''}`
    : '下一步';
  const lines = [
    '## [DISPATCH_NEXT_STEP] 上一步已通过——请派发下一步（不要 wait）',
    `计划尚未完成：${label} **尚未派发**，且当前没有在途的主 AI 任务。`,
    '**本轮必须用 `emit_action(inject_prompt)` 派发它**（带 objective + acceptanceCriteria），不要 emit_action(wait)。',
    '这是"首次派单(dispatch)"而非"推进(advance)"——它从未派发过，不需要也等不到它的 turn_report 才派。',
  ];
  if (Array.isArray(ns.acceptanceCriteria) && ns.acceptanceCriteria.length > 0) {
    lines.push('该步验收标准（派单时带给主 AI，之后据此 Read 真实产物核验）：');
    for (const c of ns.acceptanceCriteria) lines.push(`  - ${c}`);
  }
  return lines;
}

function formatComposite(p, elapsed) {
  const events = Array.isArray(p.events) ? p.events : [];
  const nextStep = (p.nextStepToDispatch && typeof p.nextStepToDispatch === 'object')
    ? p.nextStepToDispatch : null;
  const dropped = Number.isFinite(p.droppedSincePrevious) ? p.droppedSincePrevious : 0;
  const tick = Number.isFinite(p.tick) ? p.tick : '?';
  const urgent = Number.isFinite(p.urgentCount) ? p.urgentCount : 0;
  const windowSec = (p.batchStartMs && p.batchEndMs)
    ? Math.max(0, Math.round((p.batchEndMs - p.batchStartMs) / 1000))
    : null;
  const isHealthCheck = p.healthCheck === true;
  const generationBanner = typeof p.generationBanner === 'string' && p.generationBanner.length > 0
    ? p.generationBanner : null;

  const headerParts = [`## BATCH [${elapsed} | tick #${tick}]`];
  if (windowSec != null) headerParts.push(`window=${windowSec}s`);
  headerParts.push(`events=${events.length}`);
  if (urgent > 0) headerParts.push(`urgent=${urgent}`);
  if (dropped > 0) headerParts.push(`⚠ dropped=${dropped}`);
  if (isHealthCheck) headerParts.push('health_check');
  const header = headerParts.join(' | ');

  if (events.length === 0) {
    const lines = [header, ''];
    if (generationBanner) {
      lines.push('## [NEW_GENERATION_BANNER]', generationBanner, '');
    }
    if (nextStep) {
      // Post-approve wake with an undispatched next step: dispatch, do NOT wait.
      lines.push(...buildNextStepDispatchLines(nextStep));
    } else if (dropped > 0) {
      lines.push(
        `本批次没有新事件,但有 ${dropped} 个事件在等待期间被丢弃 (ring overflow)。`,
        '如果担心遗漏,可调用 Read/Grep 复查仍在推进的文件状态。否则请调用 emit_action(action="wait")。',
      );
    } else {
      lines.push(
        '本批次没有新事件 (健康检查 tick)。',
        '**必须**调用 `emit_action(action="wait", reason="健康检查无事件")` 结束本轮 — 仅输出思考文本会触发 SUPERVISOR_POST_EVENT_TIMEOUT。',
      );
    }
    return lines.join('\n');
  }

  const sections = [header, ''];
  if (generationBanner) {
    sections.push(
      '## [NEW_GENERATION_BANNER]',
      generationBanner,
      '你刚接管这个 pair。在你的 handoff 文档产生到现在的这段时间里, 主 AI 又跑了下面 ' + events.length + ' 个事件,',
      '其中部分可能在你的认知之前发生但你不知道。请先按 handoff 文档里的 anchoredFacts 校对, 再做决策。',
      '',
    );
  }
  if (dropped > 0) {
    sections.push(`> ⚠️ 注意:有 ${dropped} 个较早事件在缓冲区溢出时被丢弃。`,
                  '> 下面是仍保留在缓冲区里的最新事件,按时间顺序。', '');
  }
  for (let i = 0; i < events.length; i++) {
    const child = events[i];
    sections.push(`---  child #${i + 1}  ---`);
    sections.push(summarizeEvent(child));
    sections.push('');
  }
  sections.push(
    '',
    '## 批处理决策提示',
    '以上是过去 ~' + (windowSec ?? '?') + 's 内主 AI 的事件流。你可以:',
    '- 对最关键的事件做出单一 action (inject_prompt / escalate / approve_and_continue / wait)',
    '- 如果多个事件互相关联,综合后给一条 action,不要 emit 多次',
    '- 仍需遵守 review 协议:涉及 modified_in_plan 文件必须 Read 验证后再决策',
    '- **必须**以 emit_action 工具调用结束本轮,只输出文字会触发 120s 超时'
  );
  if (nextStep) {
    // A next TODO step is already waiting to be dispatched (e.g. you approved the
    // previous step). Once you finish handling the events above, dispatch it.
    sections.push('', ...buildNextStepDispatchLines(nextStep));
  }
  return sections.join('\n');
}

function formatUserInput(p, elapsed) {
  const text = (p.text || '').trim();
  const head = p.planningRequired ? PLANNING_DIRECTIVE_LINES : [];
  return [
    ...head,
    `## USER MESSAGE [${elapsed}]`,
    '',
    text,
    '',
    '上面是用户直接对你（Supervisor）的指令。它通常包含：',
    '- 编码方案在哪里（路径 / 引用文档）',
    '- 编码要求和规范描述',
    '- 期望你协调主 AI 完成的任务',
    '',
    '请按你的职责理解后行动。常见决策：',
    '- 任务清楚 → 输出 inject_prompt 让主 AI 开始（payload.prompt 写明第一步要做什么）',
    '- 任务模糊 / 缺关键信息 → escalate_to_human 反问用户',
    '- 用户在调整你的策略（例如"以后宽松点"）→ wait（沉默接受），下次决策时遵守',
  ].join('\n');
}

function formatStart(p, elapsed) {
  const head = p.planningRequired ? PLANNING_DIRECTIVE_LINES : [];
  return [
    ...head,
    `## EVENT [${elapsed} | start]`,
    `主 AI 会话已就绪。当前步骤 = ${p.currentStep ?? 1}/${p.totalSteps ?? '?'}`,
    p.currentStepTitle ? `下一步: ${p.currentStepTitle}` : '',
    '',
    '请决策（首次启动可输出 inject_prompt 把第一步指令发给主 AI）。',
  ].filter(Boolean).join('\n');
}

function formatTurnEnd(p, elapsed) {
  const lines = [
    `## EVENT [${elapsed} | turn_end]`,
    `step: ${p.step ?? '?'} (${p.stepTitle ?? '?'})`,
  ];
  if (Array.isArray(p.toolUses) && p.toolUses.length > 0) {
    lines.push('tool_uses:');
    for (const tu of p.toolUses) {
      const ok = tu.ok === false ? '✗' : '✓';
      const path = tu.path ? ` ${tu.path}` : '';
      lines.push(`  - ${tu.tool}${path} ${ok}`);
    }
  }
  if (Array.isArray(p.modifiedFilesInPlan) && p.modifiedFilesInPlan.length > 0) {
    lines.push(`modified_in_plan: [${p.modifiedFilesInPlan.join(', ')}]`);
  }
  if (Array.isArray(p.modifiedFilesOffPlan) && p.modifiedFilesOffPlan.length > 0) {
    lines.push(`modified_off_plan: [${p.modifiedFilesOffPlan.join(', ')}]  ⚠️`);
  }
  if (typeof p.durationMs === 'number') {
    lines.push(`duration_ms: ${p.durationMs}`);
  }
  lines.push(
    '',
    '## 必做（review 协议）',
    '在 emit_action 之前，**必须**对 modified_in_plan 中的文件至少调用一次 `Read`（关键段即可）。',
    '若主 AI 自述"加了 X / 已处理 Y"，必须用 `Grep` 验证是否真的存在，不可只看自然语言相信。',
    '跳过文件工具直接 emit_action 视为协议违例。'
  );
  return lines.join('\n');
}

function formatError(p, elapsed) {
  return [
    `## EVENT [${elapsed} | error]`,
    `step: ${p.step ?? '?'}`,
    `code: ${p.code ?? '?'}`,
    typeof p.status === 'number' ? `status: ${p.status}` : '',
    typeof p.retryAfter === 'number' ? `retry_after: ${p.retryAfter}s` : '',
    p.message ? `message: ${p.message}` : '',
    typeof p.retryCount === 'number' ? `retry_count: ${p.retryCount}` : '',
    '',
    '请按 escalation_rules 决定自愈或升级。',
  ].filter(Boolean).join('\n');
}

function formatIdle(p, elapsed) {
  return [
    `## EVENT [${elapsed} | idle_timeout]`,
    `step: ${p.step ?? '?'}`,
    `idle_seconds: ${p.idleSeconds ?? '?'}`,
    '',
    '主 AI 长时间未输出。请判断是死循环 / 正常等待 / 需介入。',
  ].join('\n');
}

function formatOffPlan(p, elapsed) {
  return [
    `## EVENT [${elapsed} | off_plan_detected]`,
    `step: ${p.step ?? '?'}`,
    `off_plan_files: [${(p.files ?? []).join(', ')}]`,
    '',
    '主 AI 修改了 plan 外文件。方案 = 标准答案，请 escalate_to_human。',
  ].join('\n');
}

function formatVerify(p, elapsed) {
  const status = p.pass ? 'PASS ✓' : 'FAIL ✗';
  const lines = [
    `## EVENT [${elapsed} | verify_result] ${status}`,
    `step: ${p.step ?? '?'}`,
    `command: ${p.command ?? '?'}`,
    `attempt: ${p.attempt ?? 1}`,
  ];
  if (!p.pass) {
    lines.push('', '## stderr', truncate(p.stderr || '(empty)', 1200));
  }
  lines.push('', p.pass
    ? '验证通过。如需 review，可发起 review_request；否则推进下一步。'
    : '验证失败。请反馈具体错误给主 AI（inject_prompt）或 escalate。');
  return lines.join('\n');
}

function formatReview(p, elapsed) {
  if (p.pass) {
    return [
      `## EVENT [${elapsed} | review_result] PASS ✓`,
      `step: ${p.step ?? '?'}`,
      `reviewer: ${p.reviewerId ?? 'self'}`,
      '',
      '所有审查规范通过。请推进下一步。',
    ].join('\n');
  }
  const lines = [
    `## EVENT [${elapsed} | review_result] FAIL ✗`,
    `step: ${p.step ?? '?'}`,
    `reviewer: ${p.reviewerId ?? 'self'}`,
    `issues:`,
  ];
  for (const issue of p.issues ?? []) {
    lines.push(`  - [${issue.rule ?? 'rule'}] ${issue.file ?? '?'}:${issue.line ?? '?'} — ${issue.message ?? ''}`);
  }
  lines.push('', '请把这些问题以 inject_prompt 反馈给主 AI。');
  return lines.join('\n');
}

function formatHumanResponse(p, elapsed) {
  return [
    `## EVENT [${elapsed} | human_response]`,
    `choice: ${p.choice ?? '?'}`,
    p.note ? `note: ${p.note}` : '',
    '',
    '用户已决策。请继续。',
  ].filter(Boolean).join('\n');
}

function safeJson(obj) {
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return String(obj);
  }
}

function truncate(s, n) {
  if (!s) return '';
  if (s.length <= n) return s;
  return s.slice(0, n) + `\n... (truncated, ${s.length - n} more chars)`;
}
