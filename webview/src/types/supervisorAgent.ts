/**
 * Supervisor Agent configuration.
 * Persistent persona that can be attached to a session for autonomous monitoring,
 * scheduling, and skill-based review of the main AI.
 */
export interface SupervisorAgent {
  /** Unique identifier */
  id: string;
  /** Display name (max 30 chars) */
  name: string;
  /** Role + rules definition; used directly as system prompt body */
  description: string;
  /** Claude model (default: opus-4-7) */
  model?: string;
  /**
   * Per-agent default for the right-pane 1M-context toggle. PairContext seeds
   * the global toggle from this on first activation; once the user toggles
   * manually the persisted value wins.
   */
  defaultLongContext?: boolean;
  /**
   * Per-agent default reasoning-effort tier. PairContext seeds
   * {@link SelectedSupervisor.role}=coordinator's reasoning entry from this
   * on first activation; once the user picks a tier the per-agent value wins.
   * Stored as the bare tier id (matches `ReasoningEffort` on the web side).
   */
  defaultReasoning?: string;
  /** Whether this is a system built-in persona */
  builtIn?: boolean;
  /** Creation timestamp */
  createdAt?: number;
  /** Last update timestamp */
  updatedAt?: number;
}

/**
 * Payload pushed from Java to window.updateSupervisorAgents.
 */
export interface SupervisorAgentListPayload {
  agents: SupervisorAgent[];
  defaultAgentId: string | null;
  /**
   * v3 global auto-compact threshold (% of context window). Defaults to 70
   * on the Java side; UI exposes this as a slider/number input.
   */
  autoCompactThreshold?: number;
}

/**
 * Operation result returned via window.supervisorAgentOperationResult.
 */
export interface SupervisorAgentOperationResult {
  success: boolean;
  operation?: 'add' | 'update' | 'delete';
  error?: string;
}

/**
 * Role of a supervisor in a running pair session.
 * - coordinator: the primary scheduler (one and only one per pair)
 * - reviewer: secondary skill reviewer (0..N per pair)
 */
export type SupervisorRole = 'coordinator' | 'reviewer';

/**
 * Selected supervisor instance bound to a session.
 */
export interface SelectedSupervisor {
  agentId: string;
  name: string;
  role: SupervisorRole;
  /** Default model from agent config; runtime override is tracked in PairContext. */
  model?: string;
  /** Carried through from {@link SupervisorAgent.defaultLongContext} so PairContext
   * can seed the 1M toggle on first activation. */
  defaultLongContext?: boolean;
  /** Carried through from {@link SupervisorAgent.defaultReasoning} so PairContext
   * can seed the reasoning tier on first activation. */
  defaultReasoning?: string;
}
