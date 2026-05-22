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
  /** Claude model (default: haiku) */
  model?: string;
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
}
