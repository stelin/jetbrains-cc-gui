import { useMemo, useState } from 'react';
import type { ClaudeMessage } from '../../types';
import styles from './style.module.less';

interface SubagentEntry {
  toolUseId: string;
  taskSubject: string;
  taskDescription?: string;
  startedAt: number;
  finishedAt?: number;
  status: 'running' | 'done' | 'error';
  result?: string;
}

interface SubagentPanelProps {
  agentId: string;
  messages: ClaudeMessage[];
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;

/** Extract Task tool_use + tool_result pairs from a supervisor's message stream
 *  to render its outstanding/finished subagents as cards.
 *
 *  PairContext stores messages as {@code raw: { content: [...] }} (flat shape
 *  after blocks are extracted from the SDK envelope by processSupervisorEnvelope).
 *  Some upstream paths preserve the nested {@code raw.message.content} SDK
 *  shape, so we fall back to that for forward compatibility. */
function extractSubagents(messages: ClaudeMessage[]): SubagentEntry[] {
  const byId = new Map<string, SubagentEntry>();
  for (const m of messages) {
    if (!isObject(m.raw)) continue;
    const raw = m.raw as Record<string, unknown>;
    let content = raw.content;
    if (!Array.isArray(content) && isObject(raw.message)) {
      content = (raw.message as Record<string, unknown>).content;
    }
    if (!Array.isArray(content)) continue;
    for (const block of content) {
      if (!isObject(block)) continue;
      const type = block.type;
      if (type === 'tool_use' && block.name === 'Task') {
        const id = String(block.id ?? '');
        if (!id) continue;
        const input = isObject(block.input) ? block.input : {};
        const subject = String(input.subject ?? input.description ?? input.prompt ?? '(no subject)').slice(0, 80);
        const description = typeof input.description === 'string' ? input.description : undefined;
        if (!byId.has(id)) {
          byId.set(id, {
            toolUseId: id,
            taskSubject: subject,
            taskDescription: description,
            startedAt: typeof m.timestamp === 'string' ? Date.parse(m.timestamp) : Date.now(),
            status: 'running',
          });
        }
      } else if (type === 'tool_result') {
        const id = String(block.tool_use_id ?? '');
        const entry = byId.get(id);
        if (!entry) continue;
        const isError = block.is_error === true;
        entry.status = isError ? 'error' : 'done';
        entry.finishedAt = typeof m.timestamp === 'string' ? Date.parse(m.timestamp) : Date.now();
        if (typeof block.content === 'string') {
          entry.result = block.content.slice(0, 200);
        } else if (Array.isArray(block.content)) {
          const texts = block.content
            .filter((c) => isObject(c) && c.type === 'text' && typeof c.text === 'string')
            .map((c) => (c as { text: string }).text);
          if (texts.length > 0) entry.result = texts.join('\n').slice(0, 200);
        }
      }
    }
  }
  return Array.from(byId.values());
}

/** Per-supervisor panel listing the Task subagents the supervisor dispatched
 *  during the current session. Auto-extracted from message stream — no extra IPC. */
export default function SubagentPanel({ agentId: _agentId, messages }: SubagentPanelProps) {
  const subagents = useMemo(() => extractSubagents(messages), [messages]);
  const [open, setOpen] = useState(false);

  if (subagents.length === 0) return null;

  const running = subagents.filter((s) => s.status === 'running').length;
  const done = subagents.filter((s) => s.status === 'done').length;
  const err = subagents.filter((s) => s.status === 'error').length;

  return (
    <div className={styles.subagentPanel}>
      <button className={styles.subagentToggle} onClick={() => setOpen((o) => !o)}>
        {open ? '▼' : '▶'} 子代理 (running {running} / done {done}{err > 0 ? ` / err ${err}` : ''})
      </button>
      {open && (
        <ul className={styles.subagentList}>
          {subagents.map((s) => (
            <li key={s.toolUseId} className={styles.subagentItem} data-status={s.status}>
              <div className={styles.subagentHeader}>
                <span className={styles.subagentStatus}>{s.status}</span>
                <span className={styles.subagentSubject}>{s.taskSubject}</span>
                {s.finishedAt && (
                  <span className={styles.subagentDuration}>
                    {Math.round((s.finishedAt - s.startedAt) / 1000)}s
                  </span>
                )}
              </div>
              {s.result && <div className={styles.subagentResult}>{s.result}</div>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
