/**
 * Supervisor Workflow Orchestration — front-end types.
 * Mirrors the Java-side data model in docs/workflow/prd.md §4.
 *
 * NOTE: The orchestration engine lives on the Java side
 * (SupervisorWorkflowManager). This module only models what the UI needs to
 * render and what travels over the JS↔Java bridge (docs/workflow/ui-implementation.md §9).
 */

export type NodeStatus =
  | 'PENDING'        // dependencies not yet satisfied
  | 'READY'          // deps satisfied, queued waiting for a concurrency slot
  | 'RUNNING'        // tab created + pair started
  | 'WAITING_HUMAN'  // escalated / watchdog-stuck → needs a human
  | 'DONE'           // supervisor emitted complete_plan / all steps done
  | 'ABORTED';

export type WorkflowState = 'EDITING' | 'RUNNING' | 'COMPLETED' | 'ABORTED';

export interface WorkflowNode {
  /** = tab name; unique within the workflow. */
  name: string;
  /** Reuses SupervisorAgent.id from supervisor-agents.json. */
  supervisorId: string;
  /** Inline task text (markdown). Used unless planPath is set. */
  plan: string;
  /** Optional: reference a .md file on disk instead of inline plan. */
  planPath?: string;
  /** Optional model override → StartPairParams.modelOverride. */
  model?: string;
  /** Optional 1M-context toggle → StartPairParams.longContextOverride. */
  longContext?: boolean;
  /** Optional reasoning tier (bare id) → StartPairParams.reasoningOverride. */
  reasoning?: string;
  /** Names of upstream nodes this node depends on → defines串/并行. */
  dependsOn: string[];
  /** Manual canvas position (set by dragging). When unset, auto-layout applies. */
  posX?: number;
  posY?: number;
}

export interface WorkflowDefinition {
  id: string;
  name: string;
  nodes: WorkflowNode[];
  updatedAt?: number;
}

export interface NodeRuntime {
  status: NodeStatus;
  pairId?: string | null;
  windowId?: string | null;
  completionReportPath?: string | null;
  /** Filled when status === 'WAITING_HUMAN'. */
  escalationReason?: string | null;
  /** Optional live output-token counter while RUNNING. */
  liveOutputTokens?: number;
}

export interface WorkflowExecution {
  workflowId: string;
  state: WorkflowState;
  /** keyed by node.name */
  nodes: Record<string, NodeRuntime>;
  /** Concurrency cap (= 2), pushed by Java; UI is read-only. */
  concurrency: number;
}

/** Non-modal escalation notice pushed via window.onWorkflowEscalation. */
export interface WorkflowEscalation {
  nodeName: string;
  reason?: string;
  /** client-side id for toast dedup/stacking */
  key: string;
}

// ─── factories ──────────────────────────────────────────────────────────

let seq = 0;
export function uid(prefix: string): string {
  seq += 1;
  return `${prefix}_${Date.now().toString(36)}${seq.toString(36)}`;
}

export function createNode(name: string): WorkflowNode {
  return { name, supervisorId: '', plan: '', dependsOn: [] };
}

export function createWorkflow(name: string): WorkflowDefinition {
  return { id: uid('wf'), name, nodes: [], updatedAt: Date.now() };
}
