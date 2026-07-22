/**
 * useWorkflowTasks
 *
 * Surfaces ultracode **Workflow** runs (and, more generally, `local_workflow`
 * background tasks) into the StatusPanel's 子代理 tab.
 *
 * Why this hook exists: the StatusPanel derives its tabs purely from the message
 * list — 任务 = TodoWrite items, 子代理 = `Task`/`Agent` tool calls. A `Workflow`
 * tool call is a single tool_use with name `workflow`, matching neither, and its
 * per-agent breakdown + progress live ONLY in the SDK's `task_*` system events
 * (task_started / task_progress / task_updated / task_notification), which are
 * NOT tool calls. Those events are forwarded by the Java layer via
 * `window.onTaskEvent`; this hook aggregates them by task_id and exposes them as
 * SubagentInfo entries so the existing SubagentList renders them.
 *
 * Empirically (SDK 0.3.198) a workflow surfaces as ONE task_id: task_started
 * (task_type=local_workflow, workflow_name), a stream of task_progress whose
 * `last_tool_name`/`description` name the active agent (agentA/agentB…), then a
 * terminal task_updated{status} + task_notification. So per-agent labels are
 * reconstructed from the task_progress stream.
 */
import { useState, useEffect, useRef, useMemo } from 'react';
import type { SubagentInfo, SubagentStatus } from '../types';

interface WorkflowTaskInfo {
  taskId: string;
  /** meta.name of the workflow (e.g. 'panelprobe'), or a fallback label */
  name: string;
  /** longer description from task_started */
  description: string;
  status: SubagentStatus;
  /** distinct agent labels seen via task_progress.last_tool_name */
  agents: string[];
  /** the agent label from the most recent task_progress */
  currentAgent?: string;
  /** final summary from task_notification */
  summary?: string;
}

interface RawTaskEvent {
  type?: string;
  subtype?: string;
  task_id?: string;
  task_type?: string;
  workflow_name?: string;
  description?: string;
  last_tool_name?: string;
  status?: string;
  summary?: string;
  patch?: { status?: string };
}

const TERMINAL_OK = 'completed';
const TERMINAL_BAD = new Set(['failed', 'killed', 'stopped']);

/**
 * Fold a single task_* event into the workflow-task map (immutably). Only
 * `local_workflow` tasks are tracked — foreground subagents (local_agent) and
 * backgrounded bash already surface elsewhere / are out of scope, and tracking
 * them here would duplicate the message-derived 子代理 entries.
 */
export function applyTaskEvent(
  prev: Map<string, WorkflowTaskInfo>,
  evt: RawTaskEvent,
): Map<string, WorkflowTaskInfo> {
  const subtype = evt?.subtype;
  const taskId = evt?.task_id;
  if (!taskId || typeof subtype !== 'string' || !subtype.startsWith('task_')) return prev;

  const isWorkflowStart = subtype === 'task_started' && evt.task_type === 'local_workflow';
  const known = prev.has(taskId);
  if (!isWorkflowStart && !known) return prev; // ignore non-workflow tasks

  const next = new Map(prev);
  const cur: WorkflowTaskInfo =
    next.get(taskId) ?? { taskId, name: taskId, description: '', status: 'running', agents: [] };
  const updated: WorkflowTaskInfo = { ...cur, agents: [...cur.agents] };

  switch (subtype) {
    case 'task_started':
      updated.name = evt.workflow_name || evt.description || taskId;
      updated.description = evt.description || '';
      updated.status = 'running';
      break;
    case 'task_progress': {
      const label =
        (typeof evt.last_tool_name === 'string' && evt.last_tool_name) ||
        (typeof evt.description === 'string' && evt.description) ||
        '';
      if (label) {
        updated.currentAgent = label;
        if (!updated.agents.includes(label)) updated.agents.push(label);
      }
      break;
    }
    case 'task_updated': {
      const st = evt.patch?.status;
      if (st === TERMINAL_OK) updated.status = 'completed';
      else if (st && TERMINAL_BAD.has(st)) updated.status = 'error';
      break;
    }
    case 'task_notification':
      updated.status = evt.status === TERMINAL_OK ? 'completed' : 'error';
      if (evt.summary) updated.summary = evt.summary;
      updated.currentAgent = undefined;
      break;
    default:
      break;
  }

  next.set(taskId, updated);
  return next;
}

function toSubagentInfo(t: WorkflowTaskInfo): SubagentInfo {
  const running = t.status === 'running' && t.currentAgent ? ` · ${t.currentAgent}` : '';
  const count = t.agents.length ? ` · ${t.agents.length} agents` : '';
  return {
    id: `wf:${t.taskId}`,
    type: 'workflow',
    description: `${t.name}${running}${count}`,
    prompt: t.agents.length ? `agents: ${t.agents.join(', ')}` : t.summary || '',
    status: t.status,
    // Sort workflows ahead of message-derived subagents when merged.
    messageIndex: Number.MAX_SAFE_INTEGER,
  };
}

interface UseWorkflowTasksParams {
  /** Whether the current turn is streaming/active (used to scope to a turn). */
  streamingActive: boolean;
  /** Current claude session id (reset when the session changes). */
  sessionId: string | null;
}

export function useWorkflowTasks({ streamingActive, sessionId }: UseWorkflowTasksParams): SubagentInfo[] {
  const [tasks, setTasks] = useState<Map<string, WorkflowTaskInfo>>(() => new Map());

  // Register the bridge callback once. Java forwards each `task_*` system
  // message as a raw JSON string via window.onTaskEvent (see ClaudeMessageHandler
  // -> CallbackHandler -> SessionCallbackAdapter). Functional setState keeps this
  // registration free of streamingActive/sessionId deps.
  useEffect(() => {
    const handler = (json: string) => {
      if (!json) return;
      try {
        const evt = JSON.parse(json) as RawTaskEvent;
        setTasks((prev) => applyTaskEvent(prev, evt));
      } catch {
        // ignore malformed payloads
      }
    };
    window.onTaskEvent = handler;
    return () => {
      if (window.onTaskEvent === handler) delete window.onTaskEvent;
    };
  }, []);

  // Reset when the session changes.
  useEffect(() => {
    setTasks((prev) => (prev.size ? new Map() : prev));
  }, [sessionId]);

  // Scope to the latest turn: clear on the rising edge of streamingActive (a new
  // turn begins). During a workflow the turn stays open (streamingActive true),
  // so entries accumulate within the turn and persist (completed) until the next
  // turn starts.
  const prevStreamingRef = useRef(streamingActive);
  useEffect(() => {
    const prev = prevStreamingRef.current;
    prevStreamingRef.current = streamingActive;
    if (!prev && streamingActive) {
      setTasks((cur) => (cur.size ? new Map() : cur));
    }
  }, [streamingActive]);

  return useMemo(() => Array.from(tasks.values()).map(toSubagentInfo), [tasks]);
}
