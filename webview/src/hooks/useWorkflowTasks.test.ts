import { describe, it, expect } from 'vitest';
import { applyTaskEvent } from './useWorkflowTasks';

// Event shapes taken verbatim from a real SDK 0.3.198 workflow run
// (task_type=local_workflow; per-agent labels arrive via task_progress.last_tool_name).
const started = {
  type: 'system', subtype: 'task_started', task_id: 'woq7i1l0s',
  task_type: 'local_workflow', workflow_name: 'panelprobe',
  description: 'Minimal probe: two parallel agents',
};
const progB = { type: 'system', subtype: 'task_progress', task_id: 'woq7i1l0s', last_tool_name: 'agentB', description: 'Probe: agentB' };
const progA = { type: 'system', subtype: 'task_progress', task_id: 'woq7i1l0s', last_tool_name: 'agentA', description: 'Probe: agentA' };
const progA2 = { type: 'system', subtype: 'task_progress', task_id: 'woq7i1l0s', last_tool_name: 'agentA', description: 'Probe: agentA' };
const updatedDone = { type: 'system', subtype: 'task_updated', task_id: 'woq7i1l0s', patch: { status: 'completed', end_time: 1 } };
const notif = { type: 'system', subtype: 'task_notification', task_id: 'woq7i1l0s', status: 'completed', summary: 'Dynamic workflow completed' };

function fold(events: any[]) {
  return events.reduce((m, e) => applyTaskEvent(m, e), new Map());
}

describe('applyTaskEvent', () => {
  it('creates a running workflow entry on task_started(local_workflow)', () => {
    const m = fold([started]);
    const t = m.get('woq7i1l0s');
    expect(t).toBeDefined();
    expect(t.name).toBe('panelprobe');
    expect(t.status).toBe('running');
    expect(t.agents).toEqual([]);
  });

  it('reconstructs distinct agent labels from task_progress.last_tool_name', () => {
    const m = fold([started, progB, progA, progA2]);
    const t = m.get('woq7i1l0s');
    expect(t.agents).toEqual(['agentB', 'agentA']); // distinct, order of first-seen
    expect(t.currentAgent).toBe('agentA');
    expect(t.status).toBe('running');
  });

  it('marks completed on terminal task_updated', () => {
    const t = fold([started, progA, updatedDone]).get('woq7i1l0s');
    expect(t.status).toBe('completed');
  });

  it('finalizes via task_notification (status + summary, clears currentAgent)', () => {
    const t = fold([started, progB, notif]).get('woq7i1l0s');
    expect(t.status).toBe('completed');
    expect(t.summary).toBe('Dynamic workflow completed');
    expect(t.currentAgent).toBeUndefined();
  });

  it('maps failed/killed to error status', () => {
    const failed = { ...updatedDone, patch: { status: 'failed' } };
    expect(fold([started, failed]).get('woq7i1l0s').status).toBe('error');
    const killed = { ...updatedDone, patch: { status: 'killed' } };
    expect(fold([started, killed]).get('woq7i1l0s').status).toBe('error');
  });

  it('IGNORES non-workflow tasks (foreground subagents / background bash)', () => {
    const agentStart = { type: 'system', subtype: 'task_started', task_id: 'S1', task_type: 'local_agent', subagent_type: 'general-purpose' };
    const bashStart = { type: 'system', subtype: 'task_started', task_id: 'B1', task_type: 'local_bash' };
    const bashNotif = { type: 'system', subtype: 'task_notification', task_id: 'B1', status: 'completed' };
    const m = fold([agentStart, bashStart, bashNotif]);
    expect(m.size).toBe(0); // none tracked -> no duplicates with message-derived 子代理
  });

  it('is a no-op for malformed / non-task events', () => {
    const base = fold([started]);
    expect(applyTaskEvent(base, { subtype: 'task_progress' } as any)).toBe(base); // no task_id
    expect(applyTaskEvent(base, { type: 'system', subtype: 'init', task_id: 'x' } as any)).toBe(base); // not task_*
  });
});
