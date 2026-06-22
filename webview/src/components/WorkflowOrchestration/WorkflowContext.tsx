import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../../utils/bridge';
import { hasCycle } from './layout';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
} from '../../types/supervisorAgent';
import {
  type WorkflowDefinition,
  type WorkflowExecution,
  type WorkflowNode,
  type WorkflowEscalation,
  type WorkflowCapabilities,
  type WorkflowState,
  createWorkflow,
  createNode,
  uid,
} from './types';
import { buildExportEnvelope, parseImport, normalizeImported } from './portability';

/**
 * Front-end source of truth for workflow definitions + the (Java-driven) live
 * execution. Definitions are persisted to localStorage so the editor is fully
 * usable before the Java orchestration backend lands (see ui-implementation.md
 * §1 — "backend implemented in a separate plan later"). Once the backend
 * pushes window.onWorkflowDefinitions, that becomes authoritative.
 */

const LS_KEY = 'cc-gui.workflows.v1';
const LS_SEEDED = 'cc-gui.workflows.seeded.v1';

interface WorkflowContextValue {
  // data
  definitions: WorkflowDefinition[];
  selectedId: string | null;
  draft: WorkflowDefinition | null;
  execution: WorkflowExecution | null;
  /** Per-workflow latest execution state (id → state), for left-list badges. */
  executionStatuses: Record<string, WorkflowState>;
  /** Per-node effective last-active instant (nodeName → epoch ms), for silent-time display. */
  nodeActivity: Record<string, number>;
  agents: SupervisorAgent[];
  escalations: WorkflowEscalation[];
  capabilities: WorkflowCapabilities;
  // selection / editing (local draft)
  selectWorkflow(id: string | null): void;
  newWorkflow(): void;
  updateDraft(patch: Partial<WorkflowDefinition>): void;
  upsertNode(node: WorkflowNode, originalName?: string): void;
  removeNode(name: string): void;
  addNode(): WorkflowNode;
  // persistence / run (→ Java)
  /** Validate + persist the draft. Returns false (and toasts why) when invalid. */
  saveDraft(): boolean;
  deleteWorkflow(id: string): void;
  /**
   * Serialize a definition to the portable export-envelope JSON (empty string if
   * not found). `agents` is passed in by the caller (WorkflowView owns the reliable
   * supervisor list; the context-level chain can be clobbered on navigation).
   */
  exportWorkflowJson(id: string, agents: SupervisorAgent[]): string;
  /** Import pasted JSON as an UNSAVED draft (user reviews + saves). `agents` for supervisor remap. */
  importWorkflowDraft(text: string, agents: SupervisorAgent[]): { ok: boolean; warnings: string[]; error?: string };
  /** Load a prebuilt definition as an UNSAVED draft (e.g. from the bug list); user reviews + saves/runs. */
  loadDraft(def: WorkflowDefinition): void;
  runWorkflow(id: string): void;
  abortWorkflow(): void;
  /** Resume a PAUSED (restored-after-restart) execution — opens windows + pumps safe frontier. */
  resumeWorkflow(id: string): void;
  /** Re-dispatch a stuck/interrupted node. 'auto' = adaptive; 'restart' = force full re-launch. */
  redispatchNode(name: string, mode?: 'auto' | 'restart'): void;
  /** Persist the global node-liveness freeze threshold (minutes; 0 = disabled). */
  setFreezeThreshold(minutes: number): void;
  /** Re-pull authoritative state from Java (definitions + capabilities + live run). */
  refreshState(): void;
  jumpToNode(name: string): void;
  openReport(name: string): void;
  dismissEscalation(key: string): void;
  // helpers
  agentName(id: string): string;
  isRunning: boolean;
  /** Execution restored after restart, awaiting one-click 「恢复运行」. */
  isPaused: boolean;
  runningOf(id: string): boolean;
  /** Draft exists as a persisted definition (in the left list). */
  isSaved: boolean;
  /** Draft has unsaved edits vs its persisted definition (or is brand new). */
  isDirty: boolean;
}

const WorkflowContext = createContext<WorkflowContextValue | null>(null);

export function useWorkflowContext(): WorkflowContextValue {
  const ctx = useContext(WorkflowContext);
  if (!ctx) throw new Error('useWorkflowContext must be used within <WorkflowProvider>');
  return ctx;
}

function loadDefinitions(): WorkflowDefinition[] {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) return JSON.parse(raw) as WorkflowDefinition[];
  } catch { /* ignore */ }
  return [];
}

function persistDefinitions(defs: WorkflowDefinition[]) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(defs));
  } catch { /* ignore */ }
}

/** One example workflow seeded on first run so the canvas isn't empty. */
function seedExample(): WorkflowDefinition {
  const wf = createWorkflow('方案双写 + 接口测试');
  wf.nodes = [
    { name: '方案A', supervisorId: '', plan: '实现方案 A。', dependsOn: [] },
    { name: '方案B', supervisorId: '', plan: '实现方案 B。', dependsOn: [] },
    { name: '接口测试', supervisorId: '', plan: '对方案 A、B 产出的接口做联调测试。', dependsOn: ['方案A', '方案B'] },
  ];
  return wf;
}

type ToastType = 'info' | 'success' | 'warning' | 'error';
type AddToast = (message: string, type?: ToastType) => void;

/**
 * Full-definition validation gate (run before persisting). The user's rule:
 * a workflow only saves when every node is complete. Returns the first problem
 * so the toast can point at the exact node.
 */
function validateWorkflowDef(def: WorkflowDefinition, t: TFunction): { ok: boolean; error?: string } {
  if (!def.name || !def.name.trim()) {
    return { ok: false, error: t('workflow.validate.nameRequired', '请填写工作流名称') };
  }
  if (!def.nodes || def.nodes.length === 0) {
    return { ok: false, error: t('workflow.validate.noNodes', '工作流至少需要一个节点') };
  }
  const names = new Set<string>();
  for (const n of def.nodes) {
    if (!n.name || !n.name.trim()) {
      return { ok: false, error: t('workflow.validate.nodeNameRequired', '存在未命名的节点') };
    }
    if (names.has(n.name)) {
      return { ok: false, error: t('workflow.validate.nodeNameDup', '存在重名节点：{{name}}', { name: n.name }) };
    }
    names.add(n.name);
  }
  for (const n of def.nodes) {
    if (!n.supervisorId) {
      return { ok: false, error: t('workflow.validate.supervisorRequired', '节点「{{name}}」未选择监督者', { name: n.name }) };
    }
    const hasPlan = (n.plan && n.plan.trim()) || (n.planPath && n.planPath.trim());
    if (!hasPlan) {
      return { ok: false, error: t('workflow.validate.planRequired', '节点「{{name}}」缺少任务内容', { name: n.name }) };
    }
    for (const dep of n.dependsOn || []) {
      if (!names.has(dep)) {
        return { ok: false, error: t('workflow.validate.depMissing', '节点「{{name}}」依赖了不存在的节点：{{dep}}', { name: n.name, dep }) };
      }
    }
    // execution timing (D25/D28)
    if (n.delayMode === 'relative') {
      const m = n.delayMinutes;
      if (m == null || Number.isNaN(m) || m < 0 || m > 300) {
        return { ok: false, error: t('workflow.validate.delayRange', '节点「{{name}}」延迟需在 0–300 分钟之间', { name: n.name }) };
      }
    } else if (n.delayMode === 'absolute' && !n.scheduledAt) {
      return { ok: false, error: t('workflow.validate.scheduledRequired', '节点「{{name}}」未选择定时执行时间', { name: n.name }) };
    }
  }
  if (hasCycle(def.nodes)) {
    return { ok: false, error: t('workflow.validate.cycle', '依赖关系中存在环，请检查连线') };
  }
  return { ok: true };
}

/**
 * Order-insensitive canonical form for dirty-checking. Java (Gson) and the
 * front-end emit object keys in different orders and may add/omit optional
 * fields, so a raw JSON.stringify would false-positive "dirty" after a backend
 * echo. We project only the persisted fields, in a fixed shape.
 */
function canonDef(d: WorkflowDefinition): string {
  const nodes = [...(d.nodes || [])]
    .map((n) => ({
      name: n.name,
      supervisorId: n.supervisorId || '',
      plan: n.plan || '',
      planPath: n.planPath || '',
      model: n.model || '',
      longContext: !!n.longContext,
      reasoning: n.reasoning || '',
      dependsOn: [...(n.dependsOn || [])].sort(),
      delayMode: n.delayMode || 'none',
      delayMinutes: n.delayMinutes ?? null,
      scheduledAt: n.scheduledAt ?? null,
      posX: n.posX ?? null,
      posY: n.posY ?? null,
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
  return JSON.stringify({ name: d.name || '', maxConcurrency: d.maxConcurrency ?? 2, nodes });
}

function defsEqual(a: WorkflowDefinition, b: WorkflowDefinition): boolean {
  return canonDef(a) === canonDef(b);
}

/**
 * The whole editor uses node.name as the node's identity (canvas layout, edges,
 * React keys, tab name). Duplicate names collapse onto one another — a node
 * goes invisible and the workflow can't save. This self-heals a definition with
 * dup names by suffixing the later occurrences ("X" → "X2", "X3"). Upstream
 * dependsOn references keep pointing at the first occurrence, which is the
 * sensible default. Returns whether anything changed so the caller can warn.
 */
function dedupeNodeNames(def: WorkflowDefinition): { def: WorkflowDefinition; changed: boolean } {
  const seen = new Set<string>();
  let changed = false;
  const nodes = (def.nodes || []).map((n) => {
    let name = n.name;
    if (seen.has(name)) {
      let i = 2;
      while (seen.has(`${n.name}${i}`)) i += 1;
      name = `${n.name}${i}`;
      changed = true;
    }
    seen.add(name);
    return name === n.name ? n : { ...n, name };
  });
  return { def: changed ? { ...def, nodes } : def, changed };
}

export function WorkflowProvider({ children, addToast }: { children: React.ReactNode; addToast?: AddToast }) {
  const { t } = useTranslation();
  const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<WorkflowDefinition | null>(null);
  const [execution, setExecution] = useState<WorkflowExecution | null>(null);
  const [executionStatuses, setExecutionStatuses] = useState<Record<string, WorkflowState>>({});
  const [nodeActivity, setNodeActivity] = useState<Record<string, number>>({});
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [escalations, setEscalations] = useState<WorkflowEscalation[]>([]);
  const [capabilities, setCapabilities] = useState<WorkflowCapabilities>({ mode: 'local', maxConcurrency: 2 });

  const definitionsRef = useRef(definitions);
  definitionsRef.current = definitions;

  // Latest-value refs so the once-mounted bridge callbacks always use current
  // values without re-subscribing.
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const addToastRef = useRef(addToast);
  addToastRef.current = addToast;
  const tRef = useRef(t);
  tRef.current = t;

  const notify = useCallback((message: string, type: ToastType = 'info') => {
    addToastRef.current?.(message, type);
  }, []);

  /** Send a workflow_* event to Java, logging the round-trip for diagnostics. */
  const wfSend = useCallback((event: string, content = '') => {
    // eslint-disable-next-line no-console
    console.info('[wf→java]', event, content);
    const ok = sendBridgeEvent(event, content);
    if (!ok) {
      // eslint-disable-next-line no-console
      console.warn('[wf→java] not delivered (bridge unavailable):', event);
    }
    return ok;
  }, []);

  // ── initial load (localStorage + seed) ──────────────────────────────
  useEffect(() => {
    let defs = loadDefinitions();
    if (defs.length === 0 && !localStorage.getItem(LS_SEEDED)) {
      defs = [seedExample()];
      persistDefinitions(defs);
      try { localStorage.setItem(LS_SEEDED, '1'); } catch { /* ignore */ }
    }
    setDefinitions(defs);
    setSelectedId(defs[0]?.id ?? null);
    if (defs[0]) {
      const { def, changed } = dedupeNodeNames(structuredCloneSafe(defs[0]));
      setDraft(def);
      if (changed) notify(tRef.current('workflow.dedup', '检测到重名节点，已自动重命名，请检查后保存'), 'warning');
    } else {
      setDraft(null);
    }
  }, [notify]);

  // ── Java → JS callbacks (chained; backend authoritative when present) ─
  useEffect(() => {
    const prevDefs = window.onWorkflowDefinitions;
    const prevStatuses = window.onWorkflowStatuses;
    const prevActivity = window.onWorkflowNodeActivity;
    const prevAutoResume = window.onWorkflowAutoResume;
    const prevExec = window.onWorkflowExecutionUpdate;
    const prevEsc = window.onWorkflowEscalation;
    const prevOp = window.onWorkflowOperationResult;
    const prevCaps = window.onWorkflowCapabilities;
    const prevAgents = window.updateSupervisorAgents;

    window.onWorkflowDefinitions = (json: string) => {
      prevDefs?.(json);
      try {
        const list = JSON.parse(json) as WorkflowDefinition[];
        if (Array.isArray(list)) {
          setDefinitions(list);
          persistDefinitions(list);
        }
      } catch { /* ignore */ }
    };
    window.onWorkflowStatuses = (json: string) => {
      prevStatuses?.(json);
      try {
        const m = JSON.parse(json) as Record<string, WorkflowState>;
        if (m && typeof m === 'object') setExecutionStatuses(m);
      } catch { /* ignore */ }
    };
    window.onWorkflowNodeActivity = (json: string) => {
      prevActivity?.(json);
      try {
        const m = JSON.parse(json) as Record<string, number>;
        if (m && typeof m === 'object') setNodeActivity(m);
      } catch { /* ignore */ }
    };
    window.onWorkflowAutoResume = (json: string) => {
      prevAutoResume?.(json);
      try {
        const r = JSON.parse(json) as { nodeName?: string; attempt?: number; idleMinutes?: number };
        const tt = tRef.current;
        notify(tt('workflow.autoResume', '节点「{{node}}」静默 {{min}} 分钟，已自动在原对话继续（第 {{n}} 次）', {
          node: r.nodeName ?? '', min: r.idleMinutes ?? 0, n: r.attempt ?? 1,
        }), 'info');
      } catch { /* ignore */ }
    };
    window.onWorkflowExecutionUpdate = (json: string) => {
      prevExec?.(json);
      // eslint-disable-next-line no-console
      console.info('[wf←java] onWorkflowExecutionUpdate', json);
      try {
        setExecution(JSON.parse(json) as WorkflowExecution);
      } catch { /* ignore */ }
    };
    window.onWorkflowEscalation = (json: string) => {
      prevEsc?.(json);
      try {
        const e = JSON.parse(json) as { nodeName: string; reason?: string };
        setEscalations((q) => [...q, { ...e, key: uid('esc') }]);
      } catch { /* ignore */ }
    };
    window.onWorkflowOperationResult = (json: string) => {
      prevOp?.(json);
      // eslint-disable-next-line no-console
      console.info('[wf←java] onWorkflowOperationResult', json);
      // Surface backend results so run/save/delete are never a black box.
      // (Previously a no-op — validation/lock failures were swallowed.)
      try {
        const r = JSON.parse(json) as { success?: boolean; operation?: string; error?: string };
        const tt = tRef.current;
        if (r.success === false) {
          notify(r.error || tt('workflow.opFailed', '操作失败'), 'error');
        } else if (r.success === true && r.operation === 'run') {
          notify(tt('workflow.runStarted', '工作流已启动'), 'success');
        } else if (r.success === true && r.operation === 'resume') {
          notify(tt('workflow.resumed', '已恢复运行'), 'success');
        } else if (r.success === true && r.operation === 'redispatch') {
          // Backend carries an info note (e.g. "并发已满，已排队") in `error` even on success.
          notify(r.error || tt('workflow.redispatch.sent', '已重新下发任务'), r.error ? 'info' : 'success');
        }
        // save/delete success is toasted locally (avoid duplicate toasts).
      } catch { /* ignore */ }
    };
    window.onWorkflowCapabilities = (json: string) => {
      prevCaps?.(json);
      // eslint-disable-next-line no-console
      console.info('[wf←java] onWorkflowCapabilities', json);
      try {
        const c = JSON.parse(json) as WorkflowCapabilities;
        if (c && typeof c.maxConcurrency === 'number') setCapabilities({ mode: c.mode ?? 'local', maxConcurrency: c.maxConcurrency, freezeThresholdMinutes: c.freezeThresholdMinutes });
      } catch { /* ignore */ }
    };
    window.updateSupervisorAgents = (json: string) => {
      prevAgents?.(json);
      try {
        const payload = JSON.parse(json) as SupervisorAgentListPayload;
        setAgents(payload.agents || []);
      } catch { /* ignore */ }
    };

    // Ask Java for current state (authoritative definitions + capabilities).
    wfSend('workflow_list');
    if (window.sendToJava) window.sendToJava('get_supervisor_agents:');

    return () => {
      window.onWorkflowDefinitions = prevDefs;
      window.onWorkflowStatuses = prevStatuses;
      window.onWorkflowNodeActivity = prevActivity;
      window.onWorkflowAutoResume = prevAutoResume;
      window.onWorkflowExecutionUpdate = prevExec;
      window.onWorkflowEscalation = prevEsc;
      window.onWorkflowOperationResult = prevOp;
      window.onWorkflowCapabilities = prevCaps;
      window.updateSupervisorAgents = prevAgents;
    };
  }, []);

  // ── selection / draft editing ───────────────────────────────────────
  const selectWorkflow = useCallback((id: string | null) => {
    setSelectedId(id);
    const found = definitionsRef.current.find((d) => d.id === id) ?? null;
    if (!found) { setDraft(null); return; }
    const { def, changed } = dedupeNodeNames(structuredCloneSafe(found));
    setDraft(def);
    if (changed) notify(tRef.current('workflow.dedup', '检测到重名节点，已自动重命名，请检查后保存'), 'warning');
  }, [notify]);

  const newWorkflow = useCallback(() => {
    const wf = createWorkflow('未命名工作流');
    setDraft(wf);
    setSelectedId(wf.id);
  }, []);

  const updateDraft = useCallback((patch: Partial<WorkflowDefinition>) => {
    setDraft((d) => (d ? { ...d, ...patch } : d));
  }, []);

  const upsertNode = useCallback((node: WorkflowNode, originalName?: string) => {
    setDraft((d) => {
      if (!d) return d;
      const renamed = originalName && originalName !== node.name;
      const nodes = d.nodes.map((n) => {
        let next = n;
        if (originalName ? n.name === originalName : n.name === node.name) next = node;
        // keep dependsOn references in sync on rename
        if (renamed && next.dependsOn.includes(originalName!)) {
          next = { ...next, dependsOn: next.dependsOn.map((x) => (x === originalName ? node.name : x)) };
        }
        return next;
      });
      const exists = d.nodes.some((n) => (originalName ? n.name === originalName : n.name === node.name));
      return { ...d, nodes: exists ? nodes : [...nodes, node] };
    });
  }, []);

  const removeNode = useCallback((name: string) => {
    setDraft((d) => {
      if (!d) return d;
      return {
        ...d,
        nodes: d.nodes
          .filter((n) => n.name !== name)
          .map((n) => ({ ...n, dependsOn: n.dependsOn.filter((x) => x !== name) })),
      };
    });
  }, []);

  const addNode = useCallback((): WorkflowNode => {
    const base = '新节点';
    let name = base;
    let i = 1;
    const used = new Set((draft?.nodes ?? []).map((n) => n.name));
    while (used.has(name)) { i += 1; name = `${base}${i}`; }
    const node = createNode(name);
    setDraft((d) => (d ? { ...d, nodes: [...d.nodes, node] } : d));
    return node;
  }, [draft]);

  // ── persistence / run ───────────────────────────────────────────────
  const saveDraft = useCallback((): boolean => {
    const d = draftRef.current;
    if (!d) return false;
    // Gate: a workflow only saves when the whole definition validates.
    const v = validateWorkflowDef(d, tRef.current);
    if (!v.ok) {
      notify(v.error || tRef.current('workflow.opFailed', '保存失败'), 'warning');
      return false;
    }
    const saved: WorkflowDefinition = { ...d, maxConcurrency: d.maxConcurrency ?? 2, updatedAt: Date.now() };
    setDraft(saved);
    setDefinitions((prev) => {
      const exists = prev.some((x) => x.id === saved.id);
      const next = exists ? prev.map((x) => (x.id === saved.id ? saved : x)) : [...prev, saved];
      persistDefinitions(next);
      return next;
    });
    setSelectedId(saved.id);
    wfSend('workflow_save', JSON.stringify(saved));
    notify(tRef.current('workflow.saved', '已保存'), 'success');
    return true;
  }, [notify, wfSend]);

  const deleteWorkflow = useCallback((id: string) => {
    setDefinitions((prev) => {
      const next = prev.filter((x) => x.id !== id);
      persistDefinitions(next);
      return next;
    });
    wfSend('workflow_delete', JSON.stringify({ id }));
    notify(tRef.current('workflow.deleted', '已删除'), 'success');
    setSelectedId((cur) => {
      if (cur !== id) return cur;
      const remaining = definitionsRef.current.filter((x) => x.id !== id);
      const nextId = remaining[0]?.id ?? null;
      setDraft(nextId ? structuredCloneSafe(remaining[0]) : null);
      return nextId;
    });
  }, [wfSend, notify]);

  // ── import / export (pure-frontend; export reads memory, import = draft) ──
  // `agents` is supplied by the caller (WorkflowView's reliable local list) so a
  // clobbered context-level subscription can't silently blank every supervisor.
  const exportWorkflowJson = useCallback((id: string, agentList: SupervisorAgent[]): string => {
    const def = definitionsRef.current.find((d) => d.id === id)
      ?? (draftRef.current?.id === id ? draftRef.current : null);
    return def ? buildExportEnvelope(def, agentList) : '';
  }, []);

  const importWorkflowDraft = useCallback((text: string, agentList: SupervisorAgent[]) => {
    const parsed = parseImport(text);
    if (!parsed.ok) return { ok: false, warnings: [] as string[], error: parsed.error };
    const { def, warnings } = normalizeImported(
      parsed.workflow, agentList, definitionsRef.current.map((d) => d.name),
    );
    // Load as an UNSAVED draft: new id not in `definitions` → isDirty/savable, and
    // any blanked supervisor fails validateWorkflowDef until the user fixes it.
    setDraft(def);
    setSelectedId(def.id);
    return { ok: true, warnings: parsed.versionWarn ? [parsed.versionWarn, ...warnings] : warnings };
  }, []);

  // Load a prebuilt definition (e.g. assembled from the bug list) as an UNSAVED draft.
  // The user reviews it on the canvas and decides whether to Save + Run — no implicit
  // persist/run here (mirrors importWorkflowDraft's setDraft+setSelectedId).
  const loadDraft = useCallback((def: WorkflowDefinition) => {
    setDraft(def);
    setSelectedId(def.id);
  }, []);

  const runWorkflow = useCallback((id: string) => {
    wfSend('workflow_run', JSON.stringify({ id }));
  }, [wfSend]);

  const abortWorkflow = useCallback(() => {
    wfSend('workflow_abort', JSON.stringify({}));
  }, [wfSend]);

  const resumeWorkflow = useCallback((id: string) => {
    wfSend('workflow_resume', JSON.stringify({ id }));
  }, [wfSend]);

  const redispatchNode = useCallback((name: string, mode: 'auto' | 'restart' = 'auto') => {
    wfSend('workflow_redispatch_node', JSON.stringify({ nodeName: name, mode }));
  }, [wfSend]);

  const setFreezeThreshold = useCallback((minutes: number) => {
    wfSend('workflow_set_freeze_threshold', JSON.stringify({ minutes }));
  }, [wfSend]);

  // Re-pull the authoritative snapshot from Java. The provider's one-shot mount
  // request can miss the live run (bridge not ready at app start, or the run
  // began in another tab/webview), leaving the page on "Editing" while a
  // workflow is actually RUNNING. requestList() re-pushes definitions +
  // capabilities + the running execution, so callers (e.g. opening the workflow
  // page) get a fresh state.
  const refreshState = useCallback(() => {
    wfSend('workflow_list');
  }, [wfSend]);

  const jumpToNode = useCallback((name: string) => {
    wfSend('workflow_jump_node', JSON.stringify({ nodeName: name }));
  }, [wfSend]);

  const openReport = useCallback((name: string) => {
    wfSend('workflow_open_report', JSON.stringify({ nodeName: name }));
  }, [wfSend]);

  const dismissEscalation = useCallback((key: string) => {
    setEscalations((q) => q.filter((e) => e.key !== key));
  }, []);

  const agentName = useCallback(
    (id: string) => agents.find((a) => a.id === id)?.name ?? id ?? '',
    [agents],
  );

  const isRunning = execution?.state === 'RUNNING';
  const isPaused = execution?.state === 'PAUSED';
  const runningOf = useCallback(
    (id: string) => (isRunning || isPaused) && execution?.workflowId === id,
    [isRunning, isPaused, execution],
  );

  // Saved = draft matches a persisted definition; dirty = brand-new or edited.
  const savedDef = useMemo(
    () => (draft ? definitions.find((d) => d.id === draft.id) ?? null : null),
    [draft, definitions],
  );
  const isSaved = !!savedDef;
  const isDirty = !!draft && (!savedDef || !defsEqual(draft, savedDef));

  const value = useMemo<WorkflowContextValue>(() => ({
    definitions, selectedId, draft, execution, executionStatuses, nodeActivity, agents, escalations, capabilities,
    selectWorkflow, newWorkflow, updateDraft, upsertNode, removeNode, addNode,
    saveDraft, deleteWorkflow, exportWorkflowJson, importWorkflowDraft, loadDraft, runWorkflow, abortWorkflow, resumeWorkflow, redispatchNode, setFreezeThreshold,
    refreshState, jumpToNode, openReport,
    dismissEscalation, agentName, isRunning, isPaused, runningOf, isSaved, isDirty,
  }), [
    definitions, selectedId, draft, execution, executionStatuses, nodeActivity, agents, escalations, capabilities,
    selectWorkflow, newWorkflow, updateDraft, upsertNode, removeNode, addNode,
    saveDraft, deleteWorkflow, exportWorkflowJson, importWorkflowDraft, loadDraft, runWorkflow, abortWorkflow, resumeWorkflow, redispatchNode, setFreezeThreshold,
    refreshState, jumpToNode, openReport,
    dismissEscalation, agentName, isRunning, isPaused, runningOf, isSaved, isDirty,
  ]);

  return <WorkflowContext.Provider value={value}>{children}</WorkflowContext.Provider>;
}

/** structuredClone with a JSON fallback for older webviews. */
function structuredCloneSafe<T>(obj: T): T {
  try {
    const sc = (globalThis as { structuredClone?: <U>(v: U) => U }).structuredClone;
    if (typeof sc === 'function') return sc(obj);
  } catch { /* fallthrough */ }
  return JSON.parse(JSON.stringify(obj)) as T;
}
