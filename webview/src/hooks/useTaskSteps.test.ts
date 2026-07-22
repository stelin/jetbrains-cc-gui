import { describe, it, expect } from 'vitest';
import { extractTaskStepsFromMessages } from './useTaskSteps';

// Minimal stand-ins for ClaudeMessage/ContentBlock/ToolResult; the extractor only
// reads .type/.name/.id/.input and whether findToolResult returns something.
function run(blocks: any[], resultsFor: Record<string, { is_error?: boolean }>) {
  const messages = [{ type: 'assistant', _blocks: blocks }] as any[];
  const getContentBlocks = (m: any) => m._blocks ?? [];
  const findToolResult = (id?: string) => (id && resultsFor[id] ? (resultsFor[id] as any) : null);
  return extractTaskStepsFromMessages(messages as any, getContentBlocks, findToolResult as any);
}

describe('extractTaskStepsFromMessages', () => {
  it('turns ordinary tool calls into labelled steps with running/completed status', () => {
    const steps = run(
      [
        { type: 'tool_use', id: 't1', name: 'Read', input: { file_path: '/a/b/package.json' } },
        { type: 'tool_use', id: 't2', name: 'Bash', input: { command: 'ls -1   /tmp' } },
        { type: 'text', text: 'ignored' },
      ],
      { t1: {} }, // t1 has a result -> completed; t2 has none -> in_progress
    );
    expect(steps).toEqual([
      { id: 't1', content: 'Read: package.json', status: 'completed' },
      { id: 't2', content: 'Bash: ls -1 /tmp', status: 'in_progress' }, // whitespace collapsed
    ]);
  });

  it('excludes TodoWrite / subagents / workflow / transient internal tools', () => {
    const steps = run(
      [
        { type: 'tool_use', id: 'a', name: 'TodoWrite', input: { todos: [] } },
        { type: 'tool_use', id: 'b', name: 'Task', input: { subagent_type: 'general-purpose' } },
        { type: 'tool_use', id: 'c', name: 'Workflow', input: {} },
        { type: 'tool_use', id: 'd', name: 'multi_tool_use.parallel', input: {} },
        { type: 'tool_use', id: 'e', name: 'Grep', input: { pattern: 'foo' } }, // kept
      ],
      {},
    );
    expect(steps.map((s) => s.id)).toEqual(['e']);
    expect(steps[0].content).toBe('Grep: foo');
  });

  it('normalizes mcp-prefixed names for exclusion and labels URLs/paths', () => {
    const steps = run(
      [
        { type: 'tool_use', id: 'u', name: 'WebFetch', input: { url: 'https://example.com/very/long/path/that/exceeds/sixty/characters/for/sure/xxxx' } },
        { type: 'tool_use', id: 'p', name: 'Edit', input: { file_path: 'src/App.tsx' } },
        { type: 'tool_use', id: 'x', name: 'mcp__todo__todowrite', input: {} }, // normalized -> todowrite -> excluded
      ],
      { u: {}, p: {} },
    );
    expect(steps.map((s) => s.id)).toEqual(['u', 'p']);
    expect(steps[1].content).toBe('Edit: App.tsx');
    expect(steps[0].content.length).toBeLessThanOrEqual('WebFetch: '.length + 60);
  });

  it('falls back to the tool name when no recognizable target field exists', () => {
    const steps = run([{ type: 'tool_use', id: 'z', name: 'SomeTool', input: { weird: 1 } }], {});
    expect(steps).toEqual([{ id: 'z', content: 'SomeTool', status: 'in_progress' }]);
  });
});
