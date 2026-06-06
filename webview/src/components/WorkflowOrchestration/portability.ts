/**
 * Workflow import/export — pure, IDE-free helpers (testable in isolation).
 *
 * Export produces a portable envelope ({@link WF_KIND}/{@link WF_VERSION}) that
 * denormalizes each node's supervisor to a NAME (LLM-friendly, cross-machine),
 * dropping machine-local bits (id / updatedAt / canvas positions). Import is
 * lenient (accepts the envelope, a bare definition, or an LLM reply wrapped in
 * ``` fences), then NORMALIZES into a loadable draft: regenerates the id,
 * resolves supervisors by id→name, clamps/strips fields, de-dupes node names and
 * drops dangling deps — collecting human-readable warnings along the way.
 *
 * See docs/workflow/import-export-plan.md.
 */
import type { WorkflowDefinition, WorkflowNode } from './types';
import type { SupervisorAgent } from '../../types/supervisorAgent';
import { uid } from './types';

export const WF_KIND = 'cc-gui.workflow';
export const WF_VERSION = 1;

/** One example, shared by the export shape docs and the rules dialog. */
export const EXAMPLE_JSON = `{
  "kind": "cc-gui.workflow",
  "version": 1,
  "workflow": {
    "name": "方案双写 + 接口测试",
    "maxConcurrency": 2,
    "nodes": [
      { "name": "方案A", "supervisorName": "Code Supervisor",
        "plan": "实现方案 A。", "model": "claude-opus-4-8", "reasoning": "high",
        "dependsOn": [], "delayMode": "none" },
      { "name": "方案B", "supervisorName": "Code Supervisor",
        "plan": "实现方案 B。", "dependsOn": [] },
      { "name": "接口测试", "supervisorName": "Code Supervisor",
        "plan": "联调 A、B 的接口。", "dependsOn": ["方案A", "方案B"],
        "delayMode": "relative", "delayMinutes": 5 }
    ]
  }
}`;

function clampInt(v: unknown, lo: number, hi: number): number {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return lo;
  return Math.max(lo, Math.min(hi, n));
}

// ─── export ─────────────────────────────────────────────────────────────

/** Serialize a definition to the portable envelope JSON (pretty-printed). */
export function buildExportEnvelope(def: WorkflowDefinition, agents: SupervisorAgent[]): string {
  const nameById = new Map(agents.map((a) => [a.id, a.name]));
  const nodes = (def.nodes || []).map((n) => {
    const out: Record<string, unknown> = { name: n.name };
    const supName = n.supervisorId ? nameById.get(n.supervisorId) : undefined;
    if (supName) out.supervisorName = supName;
    if (n.supervisorId) out.supervisorId = n.supervisorId; // optional exact-match aid
    if (n.plan && n.plan.trim()) out.plan = n.plan;
    if (n.planPath && n.planPath.trim()) out.planPath = n.planPath;
    if (n.model) out.model = n.model;
    if (n.longContext) out.longContext = true;
    if (n.reasoning) out.reasoning = n.reasoning;
    out.dependsOn = [...(n.dependsOn || [])];
    if (n.delayMode && n.delayMode !== 'none') {
      out.delayMode = n.delayMode;
      if (n.delayMode === 'relative' && n.delayMinutes != null) out.delayMinutes = n.delayMinutes;
      if (n.delayMode === 'absolute' && n.scheduledAt != null) out.scheduledAt = n.scheduledAt;
    }
    return out;
  });
  const envelope = {
    kind: WF_KIND,
    version: WF_VERSION,
    workflow: {
      name: def.name,
      maxConcurrency: def.maxConcurrency ?? 2,
      nodes,
    },
  };
  return JSON.stringify(envelope, null, 2);
}

// ─── import: parse ──────────────────────────────────────────────────────

export type ParseResult =
  | { ok: true; workflow: Record<string, unknown>; versionWarn?: string }
  | { ok: false; error: string };

/**
 * Parse pasted text into a raw workflow object. Lenient: tolerates ``` fences /
 * surrounding prose by slicing the first `{` … last `}`, and accepts either the
 * envelope ({@code {kind,version,workflow}}) or a bare definition ({@code {name,nodes}}).
 */
export function parseImport(text: string): ParseResult {
  const raw = (text || '').trim();
  if (!raw) return { ok: false, error: '内容为空' };
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start === -1 || end === -1 || end < start) return { ok: false, error: '未找到 JSON 对象' };
  let obj: unknown;
  try {
    obj = JSON.parse(raw.slice(start, end + 1));
  } catch (e) {
    return { ok: false, error: 'JSON 解析失败: ' + (e instanceof Error ? e.message : String(e)) };
  }
  if (!obj || typeof obj !== 'object') return { ok: false, error: '不是有效的 JSON 对象' };
  const root = obj as Record<string, unknown>;
  const workflow = (root.workflow ?? root) as Record<string, unknown>;
  if (!workflow || typeof workflow !== 'object' || !Array.isArray(workflow.nodes)) {
    return { ok: false, error: '不是有效的工作流定义（缺少 nodes 数组）' };
  }
  let versionWarn: string | undefined;
  if (typeof root.version === 'number' && root.version > WF_VERSION) {
    versionWarn = `定义版本 ${root.version} 高于当前支持的 ${WF_VERSION}，已尽力解析`;
  }
  return { ok: true, workflow, versionWarn };
}

// ─── import: normalize ──────────────────────────────────────────────────

export interface NormalizeResult {
  def: WorkflowDefinition;
  warnings: string[];
}

/**
 * Turn a parsed raw workflow into a loadable {@link WorkflowDefinition} draft:
 * fresh id, supervisors resolved by id→name (else blanked + warned), timing
 * clamped, canvas positions dropped, node names de-duped, dangling deps removed.
 */
export function normalizeImported(
  raw: Record<string, unknown>,
  agents: SupervisorAgent[],
  existingNames: string[],
): NormalizeResult {
  const warnings: string[] = [];
  const byId = new Map(agents.map((a) => [a.id, a]));
  const byName = new Map(agents.map((a) => [a.name.trim().toLowerCase(), a]));

  // workflow name (unique vs existing)
  let name = (typeof raw.name === 'string' ? raw.name.trim() : '') || '导入的工作流';
  const taken = new Set(existingNames);
  if (taken.has(name)) {
    let cand = `${name}（导入）`;
    let i = 1;
    while (taken.has(cand)) { i += 1; cand = `${name}（导入${i}）`; }
    warnings.push(`已存在同名工作流，重命名为「${cand}」`);
    name = cand;
  }

  const maxConcurrency = clampInt(typeof raw.maxConcurrency === 'number' ? raw.maxConcurrency : 2, 1, 99);

  const rawNodes = Array.isArray(raw.nodes) ? raw.nodes : [];
  const seen = new Set<string>();
  const nodes: WorkflowNode[] = [];

  for (const item of rawNodes) {
    if (!item || typeof item !== 'object') continue;
    const rn = item as Record<string, unknown>;

    let nodeName = (typeof rn.name === 'string' ? rn.name.trim() : '') || '节点';
    if (seen.has(nodeName)) {
      let i = 2;
      while (seen.has(`${nodeName}${i}`)) i += 1;
      warnings.push(`节点重名，「${nodeName}」重命名为「${nodeName}${i}」`);
      nodeName = `${nodeName}${i}`;
    }
    seen.add(nodeName);

    // supervisor: id-match → name-match → blank + warn
    let supervisorId = '';
    const rnSupId = typeof rn.supervisorId === 'string' ? rn.supervisorId : '';
    const rnSupName = typeof rn.supervisorName === 'string' ? rn.supervisorName : '';
    if (rnSupId && byId.has(rnSupId)) {
      supervisorId = rnSupId;
    } else if (rnSupName && byName.has(rnSupName.trim().toLowerCase())) {
      supervisorId = byName.get(rnSupName.trim().toLowerCase())!.id;
    } else {
      const label = rnSupName || rnSupId;
      warnings.push(label
        ? `节点「${nodeName}」的监督者「${label}」本机不存在，请重新选择`
        : `节点「${nodeName}」未指定监督者，请选择`);
    }

    // timing
    let delayMode: WorkflowNode['delayMode'] = 'none';
    let delayMinutes: number | undefined;
    let scheduledAt: number | undefined;
    if (rn.delayMode === 'relative') {
      delayMode = 'relative';
      delayMinutes = clampInt(typeof rn.delayMinutes === 'number' ? rn.delayMinutes : 0, 0, 300);
    } else if (rn.delayMode === 'absolute') {
      if (typeof rn.scheduledAt === 'number') {
        delayMode = 'absolute';
        scheduledAt = rn.scheduledAt;
        warnings.push(`节点「${nodeName}」使用了绝对时间，请确认是否仍合适`);
      } else {
        warnings.push(`节点「${nodeName}」定时为绝对时间但缺少时间，已改为立即执行`);
      }
    }

    const node: WorkflowNode = {
      name: nodeName,
      supervisorId,
      plan: typeof rn.plan === 'string' ? rn.plan : '',
      dependsOn: Array.isArray(rn.dependsOn) ? rn.dependsOn.filter((x): x is string => typeof x === 'string') : [],
    };
    if (typeof rn.planPath === 'string' && rn.planPath.trim()) {
      node.planPath = rn.planPath.trim();
      warnings.push(`节点「${nodeName}」引用了本地文件路径，跨机器可能失效`);
    }
    if (typeof rn.model === 'string' && rn.model) node.model = rn.model;
    if (rn.longContext) node.longContext = true;
    if (typeof rn.reasoning === 'string' && rn.reasoning) node.reasoning = rn.reasoning;
    if (delayMode !== 'none') node.delayMode = delayMode;
    if (delayMinutes != null) node.delayMinutes = delayMinutes;
    if (scheduledAt != null) node.scheduledAt = scheduledAt;
    nodes.push(node);
  }

  // drop self / dangling deps
  const validNames = new Set(nodes.map((n) => n.name));
  for (const n of nodes) {
    const before = n.dependsOn.length;
    n.dependsOn = n.dependsOn.filter((d) => d !== n.name && validNames.has(d));
    if (n.dependsOn.length !== before) warnings.push(`节点「${n.name}」的部分依赖无效，已移除`);
  }

  const def: WorkflowDefinition = {
    id: uid('wf'),
    name,
    nodes,
    maxConcurrency,
    updatedAt: Date.now(),
  };
  return { def, warnings };
}

// ─── rules text (for the LLM) ───────────────────────────────────────────

/** Copy-pasteable spec + dynamic local-agent list + example, for LLM generation. */
export function buildRulesText(agents: SupervisorAgent[]): string {
  const agentLines = agents.length
    ? agents.map((a) => `  - ${a.name}`).join('\n')
    : '  -（本机暂无监督者，请先在「监督者」管理中创建）';
  return [
    '请生成符合以下规则的工作流 JSON（只输出 JSON，不要额外说明）。',
    '',
    '【顶层信封】',
    '{ "kind": "cc-gui.workflow", "version": 1, "workflow": { ... } }',
    '',
    '【workflow 字段】',
    '- name: 字符串，工作流名称（必填）',
    '- maxConcurrency: 整数，最大并发（默认 2，运行时按本机上限收敛）',
    '- nodes: 节点数组（至少一个）',
    '',
    '【node 字段】',
    '- name: 字符串，节点名（工作流内唯一，必填）',
    '- supervisorName: 字符串，必须是下方“可用监督者”之一（必填）',
    '- plan: 字符串，交给监督者的任务（plan 与 planPath 至少一个）',
    '- planPath: 字符串，引用本地 .md 文件路径（可选，不建议跨机器使用）',
    '- model: 字符串，模型 id（可选，如 claude-opus-4-8）',
    '- reasoning: 字符串，推理档位（可选，如 high）',
    '- dependsOn: 字符串数组，依赖的上游节点名（必须已存在、不得成环）',
    '- delayMode: "none" | "relative" | "absolute"（执行时机，默认 none 立即）',
    '- delayMinutes: 整数 0–300，delayMode=relative 时必填（上游完成后延迟分钟数）',
    '- scheduledAt: 整数，delayMode=absolute 时必填（本地时刻的 epoch 毫秒）',
    '',
    '【约束】',
    '- 节点名唯一；dependsOn 只能引用已定义的节点名；依赖关系不能形成环。',
    '- 每个节点的 supervisorName 必须取自下方清单。',
    '',
    '【本机可用监督者】',
    agentLines,
    '',
    '【示例】',
    EXAMPLE_JSON,
  ].join('\n');
}
