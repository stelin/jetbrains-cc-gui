import { describe, it, expect } from 'vitest';
import type { SupervisorAgent } from '../../types/supervisorAgent';
import type { WorkflowDefinition } from './types';
import {
  buildExportEnvelope,
  parseImport,
  normalizeImported,
  buildRulesText,
  WF_KIND,
  WF_VERSION,
} from './portability';

const agent = (id: string, name: string): SupervisorAgent => ({ id, name, description: '' });
const AGENTS: SupervisorAgent[] = [agent('sup-code', 'Code Supervisor'), agent('sup-test', 'Test Supervisor')];

const def = (): WorkflowDefinition => ({
  id: 'wf-local-1',
  name: '示例',
  maxConcurrency: 2,
  updatedAt: 111,
  nodes: [
    { name: 'A', supervisorId: 'sup-code', plan: '做 A', dependsOn: [], model: 'claude-opus-4-8', reasoning: 'high', posX: 10, posY: 20 },
    { name: 'B', supervisorId: 'sup-test', plan: '联调', dependsOn: ['A'], delayMode: 'relative', delayMinutes: 5 },
  ],
});

describe('buildExportEnvelope', () => {
  it('wraps in envelope, denormalizes supervisorName, drops id/positions', () => {
    const json = buildExportEnvelope(def(), AGENTS);
    const o = JSON.parse(json);
    expect(o.kind).toBe(WF_KIND);
    expect(o.version).toBe(WF_VERSION);
    expect(o.workflow.name).toBe('示例');
    expect(o.workflow.nodes[0].supervisorName).toBe('Code Supervisor');
    expect(o.workflow.nodes[0].supervisorId).toBe('sup-code');
    expect(o.workflow.nodes[0].posX).toBeUndefined();
    expect(o.workflow.nodes[0].posY).toBeUndefined();
    // no top-level workflow id / updatedAt in export
    expect(o.workflow.id).toBeUndefined();
    expect(o.workflow.updatedAt).toBeUndefined();
    expect(o.workflow.nodes[1].delayMode).toBe('relative');
    expect(o.workflow.nodes[1].delayMinutes).toBe(5);
  });
});

describe('parseImport', () => {
  it('accepts the envelope', () => {
    const r = parseImport(buildExportEnvelope(def(), AGENTS));
    expect(r.ok).toBe(true);
    if (r.ok) expect(Array.isArray(r.workflow.nodes)).toBe(true);
  });
  it('accepts a bare definition (no envelope)', () => {
    const r = parseImport('{"name":"x","nodes":[]}');
    expect(r.ok).toBe(true);
  });
  it('tolerates ``` fences / surrounding prose', () => {
    const r = parseImport('好的：\n```json\n{"name":"x","nodes":[]}\n```');
    expect(r.ok).toBe(true);
  });
  it('rejects broken JSON', () => {
    const r = parseImport('{ not json ');
    expect(r.ok).toBe(false);
  });
  it('rejects a non-workflow object', () => {
    const r = parseImport('{"foo":1}');
    expect(r.ok).toBe(false);
  });
  it('warns on a higher version', () => {
    const r = parseImport(JSON.stringify({ kind: WF_KIND, version: 999, workflow: { name: 'x', nodes: [] } }));
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.versionWarn).toBeTruthy();
  });
});

describe('normalizeImported', () => {
  it('regenerates id, resets updatedAt, strips positions', () => {
    const parsed = parseImport(buildExportEnvelope(def(), AGENTS));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    const { def: out } = normalizeImported(parsed.workflow, AGENTS, []);
    expect(out.id).not.toBe('wf-local-1');
    expect(out.id.startsWith('wf_')).toBe(true);
    expect(out.nodes[0].posX).toBeUndefined();
  });

  it('keeps supervisorId when it exists locally', () => {
    const { def: out, warnings } = normalizeImported(
      { name: 'x', nodes: [{ name: 'A', supervisorId: 'sup-code', plan: 'p', dependsOn: [] }] },
      AGENTS, [],
    );
    expect(out.nodes[0].supervisorId).toBe('sup-code');
    expect(warnings).toHaveLength(0);
  });

  it('remaps by supervisorName when id is absent/unknown', () => {
    const { def: out, warnings } = normalizeImported(
      { name: 'x', nodes: [{ name: 'A', supervisorName: 'Test Supervisor', plan: 'p', dependsOn: [] }] },
      AGENTS, [],
    );
    expect(out.nodes[0].supervisorId).toBe('sup-test');
    expect(warnings).toHaveLength(0);
  });

  it('blanks supervisor and warns when neither id nor name match', () => {
    const { def: out, warnings } = normalizeImported(
      { name: 'x', nodes: [{ name: 'A', supervisorName: 'Ghost', plan: 'p', dependsOn: [] }] },
      AGENTS, [],
    );
    expect(out.nodes[0].supervisorId).toBe('');
    expect(warnings.some((w) => w.includes('Ghost'))).toBe(true);
  });

  it('clamps relative delayMinutes to 0..300', () => {
    const { def: out } = normalizeImported(
      { name: 'x', nodes: [{ name: 'A', supervisorId: 'sup-code', plan: 'p', dependsOn: [], delayMode: 'relative', delayMinutes: 999 }] },
      AGENTS, [],
    );
    expect(out.nodes[0].delayMinutes).toBe(300);
  });

  it('falls back to immediate when absolute lacks scheduledAt', () => {
    const { def: out, warnings } = normalizeImported(
      { name: 'x', nodes: [{ name: 'A', supervisorId: 'sup-code', plan: 'p', dependsOn: [], delayMode: 'absolute' }] },
      AGENTS, [],
    );
    expect(out.nodes[0].delayMode).toBeUndefined();
    expect(warnings.some((w) => w.includes('绝对时间'))).toBe(true);
  });

  it('suffixes a colliding workflow name', () => {
    const { def: out, warnings } = normalizeImported({ name: '示例', nodes: [] }, AGENTS, ['示例']);
    expect(out.name).toBe('示例（导入）');
    expect(warnings.some((w) => w.includes('重命名'))).toBe(true);
  });

  it('de-dupes node names and drops dangling/self deps', () => {
    const { def: out, warnings } = normalizeImported(
      {
        name: 'x',
        nodes: [
          { name: 'A', supervisorId: 'sup-code', plan: 'p', dependsOn: ['A', 'Ghost'] },
          { name: 'A', supervisorId: 'sup-code', plan: 'p', dependsOn: [] },
        ],
      },
      AGENTS, [],
    );
    expect(out.nodes.map((n) => n.name)).toEqual(['A', 'A2']);
    expect(out.nodes[0].dependsOn).toEqual([]); // self + Ghost removed
    expect(warnings.length).toBeGreaterThan(0);
  });
});

describe('buildRulesText', () => {
  it('lists each agent name and includes an example + constraints', () => {
    const txt = buildRulesText(AGENTS);
    expect(txt).toContain('Code Supervisor');
    expect(txt).toContain('Test Supervisor');
    expect(txt).toContain('supervisorName');
    expect(txt).toContain('cc-gui.workflow');
    expect(txt).toContain('dependsOn');
  });
});
