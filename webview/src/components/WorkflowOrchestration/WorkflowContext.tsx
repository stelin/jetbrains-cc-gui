import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { sendBridgeEvent } from '../../utils/bridge';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
} from '../../types/supervisorAgent';
import {
  type WorkflowDefinition,
  type WorkflowExecution,
  type WorkflowNode,
  type WorkflowEscalation,
  createWorkflow,
  createNode,
  uid,
} from './types';

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
  agents: SupervisorAgent[];
  escalations: WorkflowEscalation[];
  // selection / editing (local draft)
  selectWorkflow(id: string | null): void;
  newWorkflow(): void;
  updateDraft(patch: Partial<WorkflowDefinition>): void;
  upsertNode(node: WorkflowNode, originalName?: string): void;
  removeNode(name: string): void;
  addNode(): WorkflowNode;
  // persistence / run (→ Java)
  saveDraft(): void;
  deleteWorkflow(id: string): void;
  runWorkflow(id: string): void;
  abortWorkflow(): void;
  jumpToNode(name: string): void;
  openReport(name: string): void;
  dismissEscalation(key: string): void;
  // helpers
  agentName(id: string): string;
  isRunning: boolean;
  runningOf(id: string): boolean;
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

export function WorkflowProvider({ children }: { children: React.ReactNode }) {
  const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<WorkflowDefinition | null>(null);
  const [execution, setExecution] = useState<WorkflowExecution | null>(null);
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [escalations, setEscalations] = useState<WorkflowEscalation[]>([]);

  const definitionsRef = useRef(definitions);
  definitionsRef.current = definitions;

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
    setDraft(defs[0] ? structuredCloneSafe(defs[0]) : null);
  }, []);

  // ── Java → JS callbacks (chained; backend authoritative when present) ─
  useEffect(() => {
    const prevDefs = window.onWorkflowDefinitions;
    const prevExec = window.onWorkflowExecutionUpdate;
    const prevEsc = window.onWorkflowEscalation;
    const prevOp = window.onWorkflowOperationResult;
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
    window.onWorkflowExecutionUpdate = (json: string) => {
      prevExec?.(json);
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
      // Result handling is best-effort; failures surface via the run/save UI.
    };
    window.updateSupervisorAgents = (json: string) => {
      prevAgents?.(json);
      try {
        const payload = JSON.parse(json) as SupervisorAgentListPayload;
        setAgents(payload.agents || []);
      } catch { /* ignore */ }
    };

    // Ask Java for current state (no-op until backend exists).
    sendBridgeEvent('workflow_list');
    if (window.sendToJava) window.sendToJava('get_supervisor_agents:');

    return () => {
      window.onWorkflowDefinitions = prevDefs;
      window.onWorkflowExecutionUpdate = prevExec;
      window.onWorkflowEscalation = prevEsc;
      window.onWorkflowOperationResult = prevOp;
      window.updateSupervisorAgents = prevAgents;
    };
  }, []);

  // ── selection / draft editing ───────────────────────────────────────
  const selectWorkflow = useCallback((id: string | null) => {
    setSelectedId(id);
    const def = definitionsRef.current.find((d) => d.id === id) ?? null;
    setDraft(def ? structuredCloneSafe(def) : null);
  }, []);

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
  const saveDraft = useCallback(() => {
    setDraft((d) => {
      if (!d) return d;
      const saved = { ...d, updatedAt: Date.now() };
      setDefinitions((prev) => {
        const exists = prev.some((x) => x.id === saved.id);
        const next = exists ? prev.map((x) => (x.id === saved.id ? saved : x)) : [...prev, saved];
        persistDefinitions(next);
        return next;
      });
      setSelectedId(saved.id);
      sendBridgeEvent('workflow_save', JSON.stringify(saved));
      return saved;
    });
  }, []);

  const deleteWorkflow = useCallback((id: string) => {
    setDefinitions((prev) => {
      const next = prev.filter((x) => x.id !== id);
      persistDefinitions(next);
      return next;
    });
    sendBridgeEvent('workflow_delete', JSON.stringify({ id }));
    setSelectedId((cur) => {
      if (cur !== id) return cur;
      const remaining = definitionsRef.current.filter((x) => x.id !== id);
      const nextId = remaining[0]?.id ?? null;
      setDraft(nextId ? structuredCloneSafe(remaining[0]) : null);
      return nextId;
    });
  }, []);

  const runWorkflow = useCallback((id: string) => {
    sendBridgeEvent('workflow_run', JSON.stringify({ id }));
  }, []);

  const abortWorkflow = useCallback(() => {
    sendBridgeEvent('workflow_abort', JSON.stringify({}));
  }, []);

  const jumpToNode = useCallback((name: string) => {
    sendBridgeEvent('workflow_jump_node', JSON.stringify({ nodeName: name }));
  }, []);

  const openReport = useCallback((name: string) => {
    sendBridgeEvent('workflow_open_report', JSON.stringify({ nodeName: name }));
  }, []);

  const dismissEscalation = useCallback((key: string) => {
    setEscalations((q) => q.filter((e) => e.key !== key));
  }, []);

  const agentName = useCallback(
    (id: string) => agents.find((a) => a.id === id)?.name ?? id ?? '',
    [agents],
  );

  const isRunning = execution?.state === 'RUNNING';
  const runningOf = useCallback(
    (id: string) => isRunning && execution?.workflowId === id,
    [isRunning, execution],
  );

  const value = useMemo<WorkflowContextValue>(() => ({
    definitions, selectedId, draft, execution, agents, escalations,
    selectWorkflow, newWorkflow, updateDraft, upsertNode, removeNode, addNode,
    saveDraft, deleteWorkflow, runWorkflow, abortWorkflow, jumpToNode, openReport,
    dismissEscalation, agentName, isRunning, runningOf,
  }), [
    definitions, selectedId, draft, execution, agents, escalations,
    selectWorkflow, newWorkflow, updateDraft, upsertNode, removeNode, addNode,
    saveDraft, deleteWorkflow, runWorkflow, abortWorkflow, jumpToNode, openReport,
    dismissEscalation, agentName, isRunning, runningOf,
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
