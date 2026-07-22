/**
 * useTaskSteps
 *
 * Surfaces the current turn's ordinary tool activity as "task steps" so the
 * 任务 tab shows live status even for PLAIN tasks — ones that use neither
 * TodoWrite (a plan), a Task/Agent subagent, nor a Workflow.
 *
 * Why: a normal task emits NO dedicated status events (verified against SDK
 * 0.3.198: no tool_progress / system:status / task_* for plain foreground tool
 * calls). Its only "status" signal is the tool_use blocks in the message stream
 * + their tool_results. So — like useSubagents — this is purely message-derived
 * (no Java/daemon plumbing). Each ordinary tool call becomes a TodoItem-shaped
 * step (running until its tool_result arrives, then completed), letting it reuse
 * the existing TodoList rendering as a fallback for the 任务 tab.
 *
 * Excluded (they have their own home / would be noise): TodoWrite (that IS the
 * plan), Task/Agent/spawn_agent subagents (子代理 tab), Workflow (子代理 tab),
 * and transient internal tools.
 */
import { useMemo } from 'react';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock, TodoItem } from '../types';
import { normalizeToolName, isTransientInternalToolName } from '../utils/toolConstants';

interface UseTaskStepsParams {
  messages: ClaudeMessage[];
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[];
  findToolResult: (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null;
}

// Tools that must NOT appear as generic steps (handled by other tabs, or noise).
const STEP_EXCLUDED = new Set([
  'todowrite', 'todo_write', 'update_plan', 'updateplan',
  'task', 'agent', 'spawn_agent',
  'workflow',
  'exitplanmode', 'exit_plan_mode',
]);

function basename(p: string): string {
  const s = p.replace(/[/\\]+$/, '');
  const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
  return i >= 0 ? s.slice(i + 1) : s;
}

/** Build a compact human label like `Read package.json` or `Bash: ls -1 /tmp`. */
function stepLabel(rawName: string, input: Record<string, unknown> | undefined): string {
  const name = rawName || 'tool';
  const str = (k: string): string | undefined =>
    typeof input?.[k] === 'string' ? (input[k] as string) : undefined;

  const fp = str('file_path') || str('path') || str('notebook_path');
  if (fp) return `${name}: ${basename(fp)}`;
  const cmd = str('command');
  if (cmd) return `${name}: ${cmd.replace(/\s+/g, ' ').trim().slice(0, 60)}`;
  const pat = str('pattern') || str('query');
  if (pat) return `${name}: ${pat.slice(0, 60)}`;
  const url = str('url');
  if (url) return `${name}: ${url.slice(0, 60)}`;
  return name;
}

export function extractTaskStepsFromMessages(
  messages: ClaudeMessage[],
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[],
  findToolResult: (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null,
): TodoItem[] {
  const steps: TodoItem[] = [];

  messages.forEach((message, messageIndex) => {
    if (message.type !== 'assistant') return;
    const blocks = getContentBlocks(message);

    blocks.forEach((block) => {
      if (block.type !== 'tool_use') return;
      const raw = block.name ?? '';
      const norm = normalizeToolName(raw);
      if (STEP_EXCLUDED.has(norm) || isTransientInternalToolName(raw)) return;

      const input =
        block.input && typeof block.input === 'object'
          ? (block.input as Record<string, unknown>)
          : undefined;

      // A step is "running" until its tool_result lands, then "completed".
      // (TodoItem has no error state; the chat surfaces failures in detail.)
      const result = findToolResult(block.id, messageIndex);
      steps.push({
        id: String(block.id ?? `step-${messageIndex}-${steps.length}`),
        content: stepLabel(raw, input),
        status: result ? 'completed' : 'in_progress',
      });
    });
  });

  return steps;
}

export function useTaskSteps({ messages, getContentBlocks, findToolResult }: UseTaskStepsParams): TodoItem[] {
  return useMemo(
    () => extractTaskStepsFromMessages(messages, getContentBlocks, findToolResult),
    [messages, getContentBlocks, findToolResult],
  );
}
