import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { SelectedSupervisor, SupervisorAgent } from '../../types/supervisorAgent';
import type { ClaudeMessage, ClaudeContentOrResultBlock, ToolResultBlock } from '../../types';
import type { ReasoningEffort } from '../ChatInputBox/types';
import { apply1MContextSuffix, strip1MContextSuffix } from '../ChatInputBox/types';

/**
 * One queued user message for a supervisor. Holds raw text + the structured
 * @-path attachments the composer extracted so the auto-flush effect can
 * re-emit the original send via sendUserInputToSupervisor unchanged.
 */
export interface QueuedSupervisorMessage {
  id: string;
  text: string;
  attachments?: Array<{ path: string }>;
  queuedAt: number;
}

/**
 * Token usage snapshot for the supervisor's TokenIndicator. Computed by Java
 * (UsagePushService.broadcast) using the same formula and context-limit table
 * as the main AI, then forwarded to PairContext via the
 * {@code cc-gui:supervisor-usage} CustomEvent.
 */
export interface SupervisorUsage {
  model: string;
  totalPromptTokens: number;
  maxTokens?: number;
  percentage?: number;
}

/**
 * Phase 2 (2026-05-24): structured status snapshot pushed by the Java
 * PairStatusPusher. Fields match {@code PairStatusSnapshot.toJson()} on the
 * Java side — keep schema-aligned.
 *
 * <p>Two distinct channels feed the right-pane indicators:
 * <ul>
 *   <li>{@link SupervisorUsage} — coarse token-rollup (input + output) computed
 *       from each SDK message, drives the existing TokenIndicator.</li>
 *   <li>{@link PairStatusSnapshot} — SDK's real {@code getContextUsage} ratio +
 *       monitor state + alerts, drives the new PairStatusBar.</li>
 * </ul>
 */
export interface PairStatusSnapshot {
  pairId: string;
  generation: number;
  state?: 'IDLE' | 'MAIN_TURN' | 'TICK' | 'ROTATING' | 'PLAN_TRANSITIONING';
  health?: 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY';
  supervisorContextRatio?: number;  // 0..1
  supervisorUsedTokens?: number;
  supervisorContextLimit?: number;
  compactCount: number;
  /** 2026-05-25: cumulative supervisor rotations performed for this pair.
   *  Optional because older Java builds (pre-2026-05-25) don't emit it. */
  supervisorRotationCount?: number;
  /** 2026-05-25: cumulative main-AI rotations performed for this pair. */
  mainAiRotationCount?: number;
  /** 2026-05-25: cumulative main-AI auto-compactions observed for this pair. */
  mainAiCompactCount?: number;
  lastActivityAgoMs?: number;
  pendingEvents: number;
  totalDroppedEvents: number;
  tickCount: number;
  lastTickStartMs: number;
  lastTickEndMs: number;
  recentAlerts: Array<{
    ts: number;
    severity: 'INFO' | 'WARN' | 'ERROR';
    message: string;
  }>;
  /** Contract State Machine v3 (2026-05-25): recent coordinator events
   *  (plan transitions / contract issue/discharge/retry/escalate/cancel /
   *  dispatcher wake). Surfaced to CoordinatorEventStrip. Capped at 15 by
   *  the Java pusher; omitted entirely when empty. */
  recentCoordinatorEvents?: Array<{
    ts: number;
    source: 'PLAN' | 'CONTRACT' | 'GUARD' | 'DISPATCHER';
    type: string;
    message: string;
    detail?: string;
  }>;
  /** Contract State Machine v3 (2026-05-25): live activity counters from
   *  ContractRegistry. Surfaced to SessionCountStrip second row so the
   *  operator sees the system moving even when long-running counters
   *  (rotation / compaction) stay at 0. */
  openContractCount?: number;
  totalIssuedContracts?: number;
  totalRetriedContracts?: number;
  totalDischargedContracts?: number;
  totalEscalatedContracts?: number;
  /** 2026-05-28: authoritative Plan.PlanState name. Drives the supervisor
   *  Stop-button gate (enabled only while 'ACTIVE'). Omitted when no plan
   *  exists yet or on pre-2026-05-28 Java builds. */
  planState?: 'INIT' | 'ACTIVE' | 'WAITING' | 'DONE' | 'ABORTED';
  /** 2026-05-28: active sub-state when planState === 'ACTIVE'. */
  planSubState?: 'EXECUTING' | 'PENDING_DISCHARGE' | 'PENDING_DECISION';
}

/**
 * One entry in the PeriodicNoticeStrip — a non-actionable system notice
 * (currently only supervisor monitor health-check heartbeats). Kept out of
 * the supervisor chat history so receiving one does NOT interrupt the
 * supervisor's in-flight thinking.
 */
export interface PairNotice {
  ts: number;
  kind: string;
  message: string;
  details?: Record<string, unknown>;
}

/**
 * Shape for an escalate request surfaced to the user by ActionRouter.
 */
export interface EscalateRequest {
  id: string;
  pairId?: string;
  supervisorId?: string;
  supervisorName?: string;
  reason?: string;
  question?: string;
  choices?: Array<{ id: string; label: string; description?: string }>;
  contextFiles?: string[];
  kind?: 'amendment_request';
  proposal?: string;
  /**
   * 2026-05-31: whether the escalate modal blocks dismissal (user must answer).
   * Java only routes genuinely-blocking escalations to the modal, so this is
   * true unless a payload explicitly opts out with {@code blocking: false}.
   */
  blocking?: boolean;
  stats?: {
    auto_recover_count?: number;
    escalate_count?: number;
    decision_count?: number;
    decision_review_flag_count?: number;
    review_reject_count?: number;
    verify_fail_count?: number;
  };
  steps?: Array<{ index?: number; status?: string }>;
}

/**
 * 2026-05-31: a non-blocking supervisor alert (autonomy-mode C1/C2 fallback,
 * or an informational escalation). Surfaced as an auto-dismissing toast — the
 * supervisor does NOT pause for it; it's an audit affordance the user can read
 * or ignore. Distinct from {@link EscalateRequest}, which blocks.
 */
export interface PairAlert {
  id: string;
  ts: number;
  severity?: string;   // 'warn' (C1) | 'alert' (C2)
  category?: string;   // 'C1' | 'C2'
  reason?: string;
  question?: string;
  fallbackChoice?: string;
}

interface PairContextValue {
  /** Currently active supervisors on this session. Empty array = Pair disabled. */
  selected: SelectedSupervisor[];
  setSelected: (next: SelectedSupervisor[]) => void;
  /**
   * Canonical entry point for activating a supervisor. Combines:
   *   1. {@link setSelected} (seeds the per-agent 1M / reasoning defaults)
   *   2. resolution of the current right-pane composer state (effective
   *      model with [1m] suffix when applicable, effort tier from
   *      per-agent map or agent.defaultReasoning)
   *   3. a single {@code pair_start} IPC carrying all three resolved
   *      values, so Java's PairSessionManager hands the daemon the right
   *      model + effort on the very first {@code bridge.startWithHandoff()}.
   * Without this helper, callers used to send only {@code agentId} and the
   * daemon SDK was born at 200k / SDK-default effort — see the 2026-05-24
   * 1M-context bug report.
   */
  startSupervisorPair: (agent: SupervisorAgent) => void;
  isPairActive: boolean;
  openManager: () => void;
  registerOpenManager: (fn: () => void) => void;
  /**
   * Messages keyed by Supervisor agent id. Each list mirrors a main-AI chat
   * stream — same {@link ClaudeMessage} shape, same {@link ClaudeContentBlock}
   * content blocks — so the right pane reuses the main AI's MessageList /
   * MessageItem / ContentBlockRenderer pipeline without translation.
   */
  messagesByAgentId: Record<string, ClaudeMessage[]>;
  pairId: string | null;
  pendingEscalate: EscalateRequest | null;
  thinkingByAgentId: Record<string, boolean>;
  /**
   * Map of supervisor agentId → true while the current turn's assistant
   * message is still being streamed. Used by MessageList/MessageItem to drive
   * the auto-expanded thinking block and streaming cursor.
   */
  streamingByAgentId: Record<string, boolean>;
  /**
   * 2026-05-28: live per-turn generated-output token count keyed by supervisor
   * agentId, fed by the daemon's [SUPERVISOR_USAGE] stream. Drives the
   * supervisor pane's WaitingIndicator "↓ N tokens"; reset when the turn ends.
   */
  liveOutputTokensByAgentId: Record<string, number>;
  modelOverrideByAgentId: Record<string, string>;
  setSupervisorModel: (agentId: string, model: string | null) => void;
  reasoningByAgentId: Record<string, ReasoningEffort>;
  setSupervisorReasoning: (agentId: string, effort: ReasoningEffort) => void;
  longContextEnabled: boolean;
  setLongContextEnabled: (enabled: boolean) => void;
  usageByAgentId: Record<string, SupervisorUsage>;
  /**
   * Phase 2: latest status snapshot pushed from Java PairStatusPusher.
   * Null until the first push (typically after the monitor's first
   * health-check tick, ~2.5min after pair start unless an urgent event /
   * compaction surfaces sooner).
   */
  pairStatus: PairStatusSnapshot | null;
  /**
   * Periodic system notices (health-check heartbeats etc.) rendered on the
   * PeriodicNoticeStrip. Capped at MAX_NOTICES in-memory; not persisted.
   */
  notices: PairNotice[];
  /**
   * 2026-05-31: non-blocking supervisor alerts (C1/C2). Rendered as
   * auto-dismissing toasts by SupervisorAlertToast.
   */
  alerts: PairAlert[];
  dismissAlert: (id: string) => void;
  respondToEscalate: (choice: string, note?: string) => void;
  dismissEscalate: () => void;
  registerInjectPromptHandler: (
    fn: (pairId: string, supervisorId: string, prompt: string, directiveId?: string) => void
  ) => void;
  sendUserInputToSupervisor: (
    text: string,
    attachments?: Array<{ path: string }>
  ) => void;
  /**
   * Per-agent user-input queue. Populated when the user submits while the
   * supervisor is mid-turn (thinkingByAgentId[id] === true); auto-flushed
   * head-first on the true→false transition by an effect inside the
   * provider. Cleared whole on pair stop / no agents selected so a stopped
   * pair never resurrects a stale queue.
   */
  queueByAgentId: Record<string, QueuedSupervisorMessage[]>;
  enqueueSupervisorMessage: (
    agentId: string,
    text: string,
    attachments?: Array<{ path: string }>
  ) => void;
  dequeueSupervisorMessage: (agentId: string, id: string) => void;
  /**
   * Per-supervisor composer draft. Lifted into the (always-mounted) provider so
   * the typed-but-unsent text survives navigating away from the chat view
   * (Settings / Workflow / History) and back — the SupervisorChatInput itself
   * unmounts on navigation, so local state would be lost. Cleared on pair stop.
   */
  draftByAgentId: Record<string, string>;
  setSupervisorDraft: (agentId: string, value: string | ((prev: string) => string)) => void;
  // Phase 5 (2026-05-24): autonomy-mode toggle. Read from pairStatus when
  // present; locally cached so the AutonomyToggle has an optimistic value
  // before the next status push round-trip.
  autonomyMode: 'strict' | 'mixed' | 'full' | undefined;
  setAutonomyMode: (mode: 'strict' | 'mixed' | 'full') => void;
}

const PairContext = createContext<PairContextValue | null>(null);

interface PairProviderProps {
  children: ReactNode;
}

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

/** Cap per-agent message history to bound memory on long runs. */
const MAX_MESSAGES_PER_AGENT = 500;

/** Cap the periodic-notice strip's in-memory history. Notices are not persisted. */
const MAX_NOTICES = 200;

/** Cap concurrent non-blocking alert toasts on screen. */
const MAX_ALERTS = 5;

/**
 * Stable numeric hash for a string turn id, so messages can carry the
 * main-AI-compatible `__turnId: number` field. Used by MessageList for
 * streaming-isolation logic — collisions are not catastrophic (would only
 * cause two unrelated turns to refuse to merge), and the input space
 * (timestamp + random suffix) gives effectively zero collision rate in
 * practice.
 */
function hashTurnId(turnId: string): number {
  let h = 5381;
  for (let i = 0; i < turnId.length; i += 1) {
    h = ((h << 5) + h + turnId.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

function trimHistory(list: ClaudeMessage[]): ClaudeMessage[] {
  return list.length > MAX_MESSAGES_PER_AGENT
    ? list.slice(list.length - MAX_MESSAGES_PER_AGENT)
    : list;
}

/**
 * Whether a content block emitted on a user-role SDK message has anything for
 * ContentBlockRenderer to display. Anything else (empty `text`, unrecognised
 * SDK-internal markers, sidecar tool metadata) would land as an invisible
 * blue square in the pane, so we drop it before constructing the bubble.
 *
 * Compaction makes this matter in practice: the Claude Agent SDK injects
 * synthetic user messages around `compact_boundary` whose payload can be a
 * single empty `text` block, and we used to render one empty bubble per such
 * message — typically a pair right after auto-compact triggered.
 */
function isRenderableUserBlock(block: unknown): boolean {
  if (!block || typeof block !== 'object') return false;
  const b = block as { type?: string; text?: string; src?: string; fileName?: string; thinking?: string };
  switch (b.type) {
    case 'text':
      return typeof b.text === 'string' && b.text.trim().length > 0;
    case 'image':
      return typeof b.src === 'string' && b.src.length > 0;
    case 'attachment':
      return typeof b.fileName === 'string' && b.fileName.length > 0;
    case 'thinking':
      return (typeof b.thinking === 'string' && b.thinking.length > 0)
          || (typeof b.text === 'string' && b.text.length > 0);
    case 'tool_use':
      return true;
    default:
      return false;
  }
}

/**
 * Top-level provider for the Supervisor Pair UI.
 */
export function PairProvider({ children }: PairProviderProps) {
  const LONG_CONTEXT_KEY = 'cc-gui.supervisor.longContextEnabled';
  const [selected, setSelectedState] = useState<SelectedSupervisor[]>([]);
  const [messagesByAgentId, setMessagesByAgentId] = useState<Record<string, ClaudeMessage[]>>({});
  const [streamingByAgentId, setStreamingByAgentId] = useState<Record<string, boolean>>({});
  const [pairId, setPairId] = useState<string | null>(null);
  const [pendingEscalate, setPendingEscalate] = useState<EscalateRequest | null>(null);
  const [thinkingByAgentId, setThinkingByAgentId] = useState<Record<string, boolean>>({});
  // 2026-05-28: live per-turn output-token count per supervisor (CLI-style ticker).
  const [liveOutputTokensByAgentId, setLiveOutputTokensByAgentId] = useState<Record<string, number>>({});
  // Per-agent user-input queue. Populated while thinking=true; auto-drained
  // head-first on thinking true→false. See enqueue/dequeue/flush below.
  const [queueByAgentId, setQueueByAgentId] = useState<Record<string, QueuedSupervisorMessage[]>>({});
  const [draftByAgentId, setDraftByAgentId] = useState<Record<string, string>>({});
  const [modelOverrideByAgentId, setModelOverrideByAgentId] = useState<Record<string, string>>({});
  const [usageByAgentId, setUsageByAgentId] = useState<Record<string, SupervisorUsage>>({});
  const [longContextEnabled, setLongContextEnabledState] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    try { return window.localStorage.getItem(LONG_CONTEXT_KEY) === '1'; }
    catch { return false; }
  });
  const setLongContextEnabled = useCallback((enabled: boolean) => {
    setLongContextEnabledState(enabled);
    try { window.localStorage.setItem(LONG_CONTEXT_KEY, enabled ? '1' : '0'); }
    catch { /* ignore */ }
  }, []);
  const [reasoningByAgentId, setReasoningByAgentId] = useState<Record<string, ReasoningEffort>>({});
  // Phase 2: latest status snapshot from Java PairStatusPusher.
  const [pairStatus, setPairStatus] = useState<PairStatusSnapshot | null>(null);
  // Periodic-event strip history (e.g. health-check heartbeats). Cleared on
  // pair stop; capped at MAX_NOTICES.
  const [notices, setNotices] = useState<PairNotice[]>([]);
  // 2026-05-31: non-blocking supervisor alerts (C1/C2). Capped; cleared on stop.
  const [alerts, setAlerts] = useState<PairAlert[]>([]);
  const dismissAlert = useCallback((id: string) => {
    setAlerts((curr) => curr.filter((a) => a.id !== id));
  }, []);
  // Phase 5 (2026-05-24): optimistic autonomy mode (canonical comes from
  // pairStatus.autonomyMode each push). Initial undefined; AutonomyToggle
  // falls back to "full" (matches Java's 2026-05-25 default).
  const [autonomyMode, setAutonomyModeState] = useState<'strict' | 'mixed' | 'full' | undefined>(undefined);
  const setAutonomyMode = useCallback((mode: 'strict' | 'mixed' | 'full') => {
    setAutonomyModeState(mode);
  }, []);
  // Sync from server when a fresh status arrives.
  useEffect(() => {
    const serverMode = (pairStatus as unknown as { autonomyMode?: string } | null)?.autonomyMode;
    if (serverMode === 'strict' || serverMode === 'mixed' || serverMode === 'full') {
      setAutonomyModeState(serverMode);
    }
  }, [pairStatus]);

  const openManagerRef = useRef<() => void>(() => { /* not registered yet */ });
  // Protocol v2 (2026-05-24): added optional directiveId so the callback can
  // ack back via pair_directive_ack. Legacy registrants that ignore the 4th
  // arg keep working — TypeScript parameter widening is safe here.
  const injectHandlerRef = useRef<((pairId: string, supervisorId: string, prompt: string, directiveId?: string) => void) | null>(null);
  // 2026-05-25: in-React cold-start buffer. The PairProvider's useEffect
  // installing window.onPairInjectPrompt runs before PairAppBridge's
  // registerInjectPromptHandler effect, so an inject can arrive with no
  // attached handler — previously the optional chain silently dropped it.
  // Buffered entries are drained by registerInjectPromptHandler.
  const pendingInjectQueueRef = useRef<Array<{ pairId: string; supervisorId: string; prompt: string; directiveId?: string }>>([]);
  const pairIdRef = useRef<string | null>(null);
  useEffect(() => { pairIdRef.current = pairId; }, [pairId]);

  const setSelected = useCallback((next: SelectedSupervisor[]) => {
    setSelectedState(next);
    if (next.length === 0) {
      setMessagesByAgentId({});
      setStreamingByAgentId({});
      setPairId(null);
      setPendingEscalate(null);
      setThinkingByAgentId({});
      setModelOverrideByAgentId({});
      setReasoningByAgentId({});
      setQueueByAgentId({});
      setDraftByAgentId({});
      return;
    }
    // Seed per-agent defaults on activation:
    //   - 1M-context toggle: applied only if the user has never set the global
    //     storage key — once they've explicitly toggled it, that choice wins
    //     even when re-selecting the agent.
    //   - reasoning tier: applied only if no per-agent entry exists yet, so
    //     subsequent re-selects respect what the user picked last time.
    // Built-in code-/design-supervisor agents ship with defaultLongContext=true
    // + defaultReasoning="max", so this is what makes "enable supervisor ⇒
    // Opus 4.7 + 1M + max effort" the out-of-the-box behaviour.
    const agent = next[0];
    if (agent.defaultLongContext !== undefined) {
      try {
        const stored = window.localStorage.getItem(LONG_CONTEXT_KEY);
        if (stored === null) {
          setLongContextEnabledState(agent.defaultLongContext);
          window.localStorage.setItem(LONG_CONTEXT_KEY, agent.defaultLongContext ? '1' : '0');
        }
      } catch { /* ignore storage failures */ }
    }
    if (agent.defaultReasoning) {
      const tier = agent.defaultReasoning as ReasoningEffort;
      setReasoningByAgentId((prev) =>
        prev[agent.agentId] ? prev : { ...prev, [agent.agentId]: tier }
      );
    }
  }, []);

  const setSupervisorModel = useCallback((agentId: string, model: string | null) => {
    setModelOverrideByAgentId((prev) => {
      const next = { ...prev };
      if (model) {
        next[agentId] = model;
      } else {
        delete next[agentId];
      }
      return next;
    });
    const pid = pairIdRef.current ?? '';
    sendToJava(
      `pair_set_model:${JSON.stringify({
        pairId: pid,
        supervisorId: agentId,
        model: model ?? '',
      })}`
    );
  }, []);

  const setSupervisorReasoning = useCallback((agentId: string, effort: ReasoningEffort) => {
    setReasoningByAgentId((prev) => ({ ...prev, [agentId]: effort }));
    const pid = pairIdRef.current ?? '';
    sendToJava(
      `pair_set_reasoning:${JSON.stringify({
        pairId: pid,
        supervisorId: agentId,
        effort,
      })}`
    );
  }, []);

  // Canonical pair-launch entry point. Resolves the current right-pane
  // composer state (1M toggle + reasoning tier + model override) before
  // shipping a single pair_start payload to Java, so the daemon SDK is
  // born with the user's chosen config on the first
  // bridge.startWithHandoff() — no "must re-toggle 1M after start" round
  // trip. See the 2026-05-24 supervisor-1M bug for the failure mode.
  const startSupervisorPair = useCallback((agent: SupervisorAgent) => {
    const next: SelectedSupervisor[] = [{
      agentId: agent.id,
      name: agent.name,
      role: 'coordinator',
      model: agent.model,
      defaultLongContext: agent.defaultLongContext,
      defaultReasoning: agent.defaultReasoning,
    }];
    setSelected(next);

    // Resolve 1M flag: explicit user toggle (localStorage written) wins;
    // otherwise fall back to the agent's defaultLongContext. Mirrors the
    // seeding logic in setSelected so first activation lines up with what
    // the composer will show after this turn.
    let resolvedLongContext: boolean;
    try {
      const stored = window.localStorage.getItem(LONG_CONTEXT_KEY);
      resolvedLongContext = stored === null
        ? (agent.defaultLongContext ?? false)
        : stored === '1';
    } catch {
      resolvedLongContext = agent.defaultLongContext ?? false;
    }

    const baseModel = strip1MContextSuffix(agent.model || '');
    const effectiveModel = baseModel
      ? apply1MContextSuffix(baseModel, resolvedLongContext)
      : '';

    const effectiveReasoning: ReasoningEffort =
      reasoningByAgentId[agent.id] ??
      ((agent.defaultReasoning as ReasoningEffort | undefined) ?? 'medium');

    const payload: Record<string, unknown> = { agentId: agent.id };
    if (effectiveModel) payload.model = effectiveModel;
    payload.longContextEnabled = resolvedLongContext;
    payload.reasoningEffort = effectiveReasoning;

    try {
      sendToJava(`pair_start:${JSON.stringify(payload)}`);
    } catch { /* ignore — handler is best-effort */ }
  }, [setSelected, reasoningByAgentId]);

  const openManager = useCallback(() => {
    openManagerRef.current();
  }, []);

  const registerOpenManager = useCallback((fn: () => void) => {
    openManagerRef.current = fn;
  }, []);

  const registerInjectPromptHandler = useCallback(
    (fn: (pairId: string, supervisorId: string, prompt: string, directiveId?: string) => void) => {
      injectHandlerRef.current = fn;
      // Drain any injects that arrived before the handler attached
      // (cold-start race — see pendingInjectQueueRef field doc).
      const backlog = pendingInjectQueueRef.current;
      if (backlog.length > 0) {
        const drained = backlog.splice(0, backlog.length);
        console.info('[INJECT_TRACE] registerInjectPromptHandler draining backlog',
          { count: drained.length });
        for (const entry of drained) {
          try {
            fn(entry.pairId, entry.supervisorId, entry.prompt, entry.directiveId);
          } catch (err) {
            console.error('[INJECT_TRACE] registerInjectPromptHandler drain delivery threw',
              { directiveId: entry.directiveId, err: String(err) });
          }
        }
      }
    },
    []
  );

  /**
   * Append an entire message (user or assistant) to a supervisor's history.
   */
  const appendMessage = useCallback((agentId: string, message: ClaudeMessage) => {
    setMessagesByAgentId((prev) => {
      const list = prev[agentId] ? [...prev[agentId], message] : [message];
      return { ...prev, [agentId]: trimHistory(list) };
    });
  }, []);

  /**
   * Append content blocks to the currently-streaming assistant message for a
   * supervisor, or create one if no message for this turn exists yet. Tool
   * results emitted in user messages are appended directly so {@code
   * findToolResult} can correlate them with the corresponding {@code tool_use}.
   */
  const appendAssistantBlocks = useCallback(
    (
      agentId: string,
      turnId: number,
      blocks: ClaudeContentOrResultBlock[],
      opts?: { ensureStreaming?: boolean }
    ) => {
      if (blocks.length === 0) return;
      setMessagesByAgentId((prev) => {
        const list = prev[agentId] ? [...prev[agentId]] : [];
        let lastAssistant = -1;
        for (let i = list.length - 1; i >= 0; i -= 1) {
          if (list[i].type === 'assistant' && list[i].__turnId === turnId) {
            lastAssistant = i;
            break;
          }
        }
        if (lastAssistant >= 0) {
          const target = list[lastAssistant];
          const rawBase = (typeof target.raw === 'object' && target.raw ? target.raw : {}) as Record<string, unknown>;
          const existing = (rawBase.content as ClaudeContentOrResultBlock[] | undefined) ?? [];
          const nextRaw = { ...rawBase, content: [...existing, ...blocks] };
          list[lastAssistant] = {
            ...target,
            raw: nextRaw,
            isStreaming: opts?.ensureStreaming ?? target.isStreaming,
          };
        } else {
          list.push({
            type: 'assistant',
            raw: { content: blocks },
            isStreaming: opts?.ensureStreaming ?? true,
            __turnId: turnId,
            timestamp: new Date().toISOString(),
          });
        }
        return { ...prev, [agentId]: trimHistory(list) };
      });
    },
    []
  );

  /**
   * Attach tool_result blocks to whichever assistant message in the history
   * already contains the matching tool_use. We append the result blocks to
   * that message's raw.content so the main-AI findToolResult scan locates
   * them. Orphan tool_results (no matching tool_use — typically because
   * auto-compaction discarded the original assistant turn) are silently
   * dropped: a `tool_result` block has nothing user-renderable on its own,
   * so surfacing it as a sidecar user bubble would just render an empty
   * blue square in the pane.
   */
  const attachToolResults = useCallback(
    (agentId: string, results: ToolResultBlock[]) => {
      if (results.length === 0) return;
      setMessagesByAgentId((prev) => {
        const list = prev[agentId] ? [...prev[agentId]] : [];
        let changed = false;
        for (const r of results) {
          for (let i = list.length - 1; i >= 0; i -= 1) {
            const target = list[i];
            if (target.type !== 'assistant') continue;
            const rawObj = typeof target.raw === 'object' && target.raw ? target.raw : null;
            const content = rawObj?.content;
            if (!Array.isArray(content)) continue;
            const hasMatch = content.some(
              (b) => b && (b as { type?: string }).type === 'tool_use' && (b as { id?: string }).id === r.tool_use_id
            );
            if (hasMatch) {
              list[i] = {
                ...target,
                raw: { ...rawObj, content: [...content, r] },
              };
              changed = true;
              break;
            }
          }
        }
        if (!changed) return prev;
        return { ...prev, [agentId]: trimHistory(list) };
      });
    },
    []
  );

  /**
   * Mark whichever assistant messages match the given turnId as no longer
   * streaming. Called when the turn wraps up (action wrapper arrives or
   * thinking-off signal fires).
   */
  const endStreaming = useCallback((agentId: string, turnId: number) => {
    setMessagesByAgentId((prev) => {
      const list = prev[agentId];
      if (!list) return prev;
      let changed = false;
      const next = list.map((m) => {
        if (m.type === 'assistant' && m.__turnId === turnId && m.isStreaming) {
          changed = true;
          return { ...m, isStreaming: false };
        }
        return m;
      });
      if (!changed) return prev;
      return { ...prev, [agentId]: next };
    });
  }, []);

  const respondToEscalate = useCallback((choice: string, note?: string) => {
    const pid = pairIdRef.current;
    if (pid) {
      const payload: Record<string, unknown> = { pairId: pid, choice };
      if (note) payload.note = note;
      sendToJava(`pair_human_response:${JSON.stringify(payload)}`);
    }
    setPendingEscalate(null);
  }, []);

  const dismissEscalate = useCallback(() => {
    setPendingEscalate(null);
  }, []);

  const sendUserInputToSupervisor = useCallback(
    (text: string, attachments?: Array<{ path: string }>) => {
      const trimmed = text.trim();
      if (!trimmed) return;
      const pid = pairIdRef.current ?? '';
      const payload: Record<string, unknown> = { pairId: pid, text: trimmed };
      if (attachments && attachments.length > 0) {
        payload.attachments = attachments;
      }
      sendToJava(`pair_send_user_input:${JSON.stringify(payload)}`);
      // Optimistic local render: push a user message into the coordinator's
      // history so the right pane reflects the input immediately. We use a
      // pseudo turnId so the assistant streaming logic doesn't try to merge
      // with it.
      const firstAgentId = selected[0]?.agentId;
      if (firstAgentId) {
        appendMessage(firstAgentId, {
          type: 'user',
          raw: { content: [{ type: 'text', text: trimmed }] },
          timestamp: new Date().toISOString(),
        });
      }
    },
    [selected, appendMessage]
  );

  // Refs used by the auto-flush effect:
  //   - prevThinkingRef: detects the true→false transition without a render
  //     between the two reads (state setters and the effect both update on
  //     each tick, so a plain capture would miss back-to-back transitions).
  //   - flushingAgentsRef: guard against re-entrant flushes if the effect
  //     fires repeatedly while the dequeued message is still being sent (the
  //     setTimeout below lets thinking flip back to true before we clear
  //     this set).
  const prevThinkingRef = useRef<Record<string, boolean>>({});
  const flushingAgentsRef = useRef<Set<string>>(new Set());
  const sendUserInputRef = useRef(sendUserInputToSupervisor);
  useEffect(() => { sendUserInputRef.current = sendUserInputToSupervisor; }, [sendUserInputToSupervisor]);

  const enqueueSupervisorMessage = useCallback(
    (agentId: string, text: string, attachments?: Array<{ path: string }>) => {
      const trimmed = text.trim();
      if (!trimmed || !agentId) return;
      const entry: QueuedSupervisorMessage = {
        id: `sup-queue-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        text: trimmed,
        attachments: attachments && attachments.length > 0 ? attachments : undefined,
        queuedAt: Date.now(),
      };
      setQueueByAgentId((prev) => {
        const list = prev[agentId] ?? [];
        return { ...prev, [agentId]: [...list, entry] };
      });
    },
    []
  );

  const dequeueSupervisorMessage = useCallback(
    (agentId: string, id: string) => {
      setQueueByAgentId((prev) => {
        const list = prev[agentId];
        if (!list || list.length === 0) return prev;
        const next = list.filter((item) => item.id !== id);
        if (next.length === list.length) return prev;
        return { ...prev, [agentId]: next };
      });
    },
    []
  );

  // Persist the per-supervisor composer draft in the provider so it survives the
  // chat view unmounting on navigation. Accepts a value or an updater (the
  // composer uses the functional form for @-path splices).
  const setSupervisorDraft = useCallback(
    (agentId: string, value: string | ((prev: string) => string)) => {
      setDraftByAgentId((prev) => {
        const cur = prev[agentId] ?? '';
        const next = typeof value === 'function' ? value(cur) : value;
        if (next === cur) return prev;
        return { ...prev, [agentId]: next };
      });
    },
    []
  );

  // Auto-flush: when an agent's thinking flag flips true→false and its queue
  // is non-empty, pop the head and re-emit via sendUserInputToSupervisor. A
  // tiny setTimeout lets React commit the dequeue + lets the daemon receive
  // the previous turn's completion before we kick off the next one (otherwise
  // the EventCollector may glue the dequeued message onto the just-finished
  // tick instead of starting a fresh one).
  useEffect(() => {
    const prev = prevThinkingRef.current;
    for (const agentId of Object.keys(thinkingByAgentId)) {
      const was = prev[agentId] ?? false;
      const now = thinkingByAgentId[agentId] ?? false;
      // 2026-05-28: turn ended → clear the live output-token count so the next
      // turn's WaitingIndicator starts from a clean slate (no stale "↓ N").
      if (was && !now) {
        setLiveOutputTokensByAgentId((curr) => {
          if (curr[agentId] === undefined) return curr;
          const next = { ...curr };
          delete next[agentId];
          return next;
        });
      }
      if (was && !now && !flushingAgentsRef.current.has(agentId)) {
        const head = queueByAgentId[agentId]?.[0];
        if (head) {
          flushingAgentsRef.current.add(agentId);
          setQueueByAgentId((curr) => {
            const list = curr[agentId];
            if (!list || list.length === 0) return curr;
            return { ...curr, [agentId]: list.slice(1) };
          });
          setTimeout(() => {
            try {
              sendUserInputRef.current(head.text, head.attachments);
            } finally {
              flushingAgentsRef.current.delete(agentId);
            }
          }, 50);
        }
      }
    }
    prevThinkingRef.current = { ...thinkingByAgentId };
  }, [thinkingByAgentId, queueByAgentId]);

  // Subscribe to Java callbacks for Pair lifecycle and action stream.
  useEffect(() => {
    const prevStarted = window.onPairStarted;
    const prevStopped = window.onPairStopped;
    const prevAction = window.onPairActionEvent;
    const prevInject = window.onPairInjectPrompt;
    const prevEscalate = window.onPairEscalate;
    const prevOpError = window.onPairOperationError;
    const prevThinking = window.onPairThinking;
    const prevSupervisorMessage = window.onSupervisorMessage;
    const prevSupervisorMessageBatch = window.onSupervisorMessageBatch;
    const prevSupervisorLiveUsage = window.onSupervisorLiveUsage;
    const prevPairStatus = window.onPairStatusUpdate;
    const prevPairResume = window.onPairResume;
    // Protocol v2 (2026-05-24): non-blocking alert from record_alert.
    const prevAlert = window.onPairAlert;
    const prevNotice = window.onPairNotice;
    window.onPairAlert = (json: string) => {
      // 2026-05-31: non-blocking record_alert (C1/C2) → auto-dismissing toast.
      // Replaces the old console-only stub. The supervisor does NOT pause for
      // this; blocking decisions go through onPairEscalate (modal) instead.
      try {
        const o = JSON.parse(json);
        const alert: PairAlert = {
          id: `alert_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
          ts: typeof o.ts === 'number' ? o.ts : Date.now(),
          severity: typeof o.severity === 'string' ? o.severity : undefined,
          category: typeof o.category === 'string' ? o.category : undefined,
          reason: typeof o.reason === 'string' ? o.reason : undefined,
          question: typeof o.question === 'string' ? o.question : undefined,
          fallbackChoice: typeof o.fallback_choice === 'string' ? o.fallback_choice : undefined,
        };
        setAlerts((curr) => {
          const next = [...curr, alert];
          return next.length > MAX_ALERTS ? next.slice(next.length - MAX_ALERTS) : next;
        });
      } catch { /* ignore malformed */ }
    };

    window.onPairNotice = (json: string) => {
      try {
        const o = JSON.parse(json) as PairNotice;
        if (!o || typeof o !== 'object'
            || typeof o.ts !== 'number'
            || typeof o.kind !== 'string'
            || typeof o.message !== 'string') {
          return;
        }
        setNotices((curr) => {
          if (curr.length < MAX_NOTICES) return [...curr, o];
          return [...curr.slice(curr.length - MAX_NOTICES + 1), o];
        });
      } catch { /* ignore malformed */ }
    };

    window.onPairStarted = (json: string) => {
      try {
        const o = JSON.parse(json) as { pairId?: string };
        if (o.pairId) setPairId(o.pairId);
      } catch { /* ignore */ }
    };

    window.onPairStopped = () => {
      setPairId(null);
      setMessagesByAgentId({});
      setStreamingByAgentId({});
      setPendingEscalate(null);
      setModelOverrideByAgentId({});
      setReasoningByAgentId({});
      setUsageByAgentId({});
      setPairStatus(null);
      setNotices([]);
      setAlerts([]);
      // pair_stop is also how the SupervisorChatInput.handleSwitchAgent
      // transitions between agents, so clearing the whole queue dict here
      // covers both "supervisor restart" and "agent switch" — per design:
      // queued messages do not survive either event.
      setQueueByAgentId({});
      setDraftByAgentId({});   // drop the composer draft for the stopped/switched supervisor
    };

    /**
     * v4 unified pipeline: a tool_use envelope arrived from the daemon
     * carrying the action wrapper (and turn metadata). We render the action
     * as a synthetic tool_use block on the current turn's assistant message
     * so the UI sees it through the same ContentBlockRenderer dispatch as
     * the rest of the stream.
     *
     * Decision records ride along the wrapper with kind === 'decision_record';
     * we synthesise a tool_use block per decision so it gets its own card
     * inline with the turn.
     */
    window.onPairActionEvent = (json: string) => {
      try {
        const evt = JSON.parse(json);
        const agentId: string | undefined = evt?.supervisorId;
        if (!agentId) return;

        const turnStr: string = typeof evt.turnId === 'string' && evt.turnId
          ? evt.turnId
          : `m_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
        const turnId = hashTurnId(turnStr);

        // Decision record card.
        if (evt?.kind === 'decision_record' && evt.decision && typeof evt.decision === 'object') {
          appendAssistantBlocks(agentId, turnId, [{
            type: 'tool_use',
            id: `dec_${turnStr}_${evt.decision.step ?? Math.random().toString(36).slice(2, 5)}`,
            name: 'mcp__supervisor__decision_record',
            input: evt.decision,
          }]);
          return;
        }

        // Fallback text/reasoning surfaced by the daemon when the SDK stream
        // failed (transport error / model skipped emit_action). These don't
        // arrive via [SUPERVISOR_MSG] so we splice them here.
        if (evt?.parseError) {
          const fallbackBlocks: ClaudeContentOrResultBlock[] = [];
          const reasoningText = typeof evt.reasoningText === 'string' ? evt.reasoningText.trim() : '';
          const naturalText = typeof evt.naturalText === 'string' ? evt.naturalText.trim() : '';
          if (reasoningText) {
            fallbackBlocks.push({ type: 'thinking', thinking: reasoningText, text: reasoningText });
          }
          if (naturalText) {
            fallbackBlocks.push({ type: 'text', text: naturalText });
          }
          if (fallbackBlocks.length > 0) {
            appendAssistantBlocks(agentId, turnId, fallbackBlocks);
          }
        }

        // Action envelope → synthetic tool_use block (rendered by
        // SupervisorActionBlock). We carry the FULL payload, not a 60-char
        // summary — the card is responsible for collapse/expand.
        const actionType: string | undefined = evt?.action?.action;
        if (actionType) {
          const payload = evt.action.payload ?? {};
          const reason: string = evt.action.reason ?? '';
          appendAssistantBlocks(agentId, turnId, [{
            type: 'tool_use',
            id: `act_${turnStr}`,
            name: 'mcp__supervisor__emit_action',
            input: { action: actionType, reason, payload },
          }]);
        }

        endStreaming(agentId, turnId);
      } catch { /* ignore malformed */ }
    };

    /**
     * v4 unified pipeline: a raw SDK message arrived during a supervisor
     * turn. We funnel content blocks straight into the messages array — same
     * shape, same content blocks as the main AI. The emit_action tool_use
     * is filtered here because its result is delivered by onPairActionEvent
     * (with reason/payload normalised); rendering both would duplicate.
     *
     * 2026-05-24: Java's SupervisorMessageBatcher delivers an array of
     * envelopes via {@code window.onSupervisorMessageBatch}; both single and
     * batched paths funnel through {@code processSupervisorEnvelope} so the
     * rendering logic lives in one place.
     */
    const processSupervisorEnvelope = (evt: unknown) => {
      if (!evt || typeof evt !== 'object') return;
      const env = evt as {
        supervisorId?: string;
        turnId?: string;
        message?: {
          type?: string;
          subtype?: string;
          message?: { content?: unknown };
          compact_metadata?: { trigger?: string; pre_tokens?: number };
          parent_tool_use_id?: string | null;
        };
      };
      const agentId = env.supervisorId;
      const turnStr = typeof env.turnId === 'string' ? env.turnId : undefined;
      const msg = env.message;
      if (!agentId || !msg || typeof msg !== 'object') return;

      // 2026-05-24 (Q2 fix): SDK Task subagents deliver their internal messages
      // through the parent query's stream with `parent_tool_use_id` set to the
      // Task tool_use id. Previously these were rendered as supervisor's own
      // bubbles — the prompt text the subagent received showed up as a blue
      // right-aligned "user" bubble, and the subagent's Read/Glob calls were
      // attributed to the supervisor. That was wrong on both counts: the
      // supervisor pane should show only the supervisor's own work + the
      // parent Task tool_use card (whose tool_result already carries the
      // subagent's final answer). Skip subagent-internal frames entirely.
      if (msg.parent_tool_use_id) {
        return;
      }

      const turnId = hashTurnId(turnStr || `sm_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`);

      if (msg.type === 'assistant' && Array.isArray(msg.message?.content)) {
        const blocks: ClaudeContentOrResultBlock[] = [];
        for (const block of msg.message.content) {
          if (!block || typeof block !== 'object') continue;
          const b = block as { type?: string; name?: string };
          // Skip the action protocol envelope — onPairActionEvent renders it
          // with the normalised payload.
          if (b.type === 'tool_use' && typeof b.name === 'string'
              && b.name.includes('emit_action')) continue;
          blocks.push(block as ClaudeContentOrResultBlock);
        }
        if (blocks.length > 0) {
          appendAssistantBlocks(agentId, turnId, blocks, { ensureStreaming: true });
          setStreamingByAgentId((prev) =>
            prev[agentId] ? prev : { ...prev, [agentId]: true }
          );
        }
      } else if (msg.type === 'user' && Array.isArray(msg.message?.content)) {
        const toolResults: ToolResultBlock[] = [];
        const userBlocks: ClaudeContentOrResultBlock[] = [];
        for (const block of msg.message.content) {
          if (!block || typeof block !== 'object') continue;
          const b = block as { type?: string };
          if (b.type === 'tool_result') {
            toolResults.push(block as ToolResultBlock);
          } else if (isRenderableUserBlock(block)) {
            userBlocks.push(block as ClaudeContentOrResultBlock);
          }
        }
        if (toolResults.length > 0) {
          attachToolResults(agentId, toolResults);
        }
        if (userBlocks.length > 0) {
          // Non-tool-result content in a user message is supervisor input
          // (the prompt the daemon assembled from main-AI events) — render
          // it as a user bubble so the operator can see what the supervisor
          // was given. Blocks that ContentBlockRenderer cannot display
          // (empty text, SDK internal markers) are filtered above so they
          // do not surface as an empty blue bubble in the pane.
          setMessagesByAgentId((prev) => {
            const list = prev[agentId] ? [...prev[agentId]] : [];
            list.push({
              type: 'user',
              raw: { content: userBlocks },
              __turnId: turnId,
              timestamp: new Date().toISOString(),
            });
            return { ...prev, [agentId]: trimHistory(list) };
          });
        }
      } else if (msg.type === 'system' && msg.subtype === 'compact_boundary') {
        // Render as a synthetic tool_use so it flows through
        // ContentBlockRenderer alongside everything else.
        appendAssistantBlocks(agentId, turnId, [{
          type: 'tool_use',
          id: `compact_${turnStr ?? Date.now()}`,
          name: 'mcp__supervisor__compact_boundary',
          input: {
            trigger: msg.compact_metadata?.trigger ?? 'auto',
            preTokens: msg.compact_metadata?.pre_tokens ?? null,
          },
        }]);
      }
    };

    window.onSupervisorMessage = (json: string) => {
      try { processSupervisorEnvelope(JSON.parse(json)); }
      catch { /* ignore malformed */ }
    };

    window.onSupervisorMessageBatch = (json: string) => {
      try {
        const arr = JSON.parse(json);
        if (!Array.isArray(arr)) return;
        for (const evt of arr) processSupervisorEnvelope(evt);
      } catch { /* ignore malformed */ }
    };

    /**
     * Re-emitted by Java on frontend_ready (post webview reload) for every
     * still-active pair. Restore `selected` + `pairId` so the supervisor pane
     * re-appears without forcing the user to re-pick the agent. The
     * underlying Java PairSession and daemon supervisor session are still
     * alive — only the React state was lost.
     *
     * Uses setSelectedState (the raw setter) rather than the public
     * setSelected callback, because the latter clears messages/streaming on
     * the next.length === 0 branch and re-seeds long-context/reasoning
     * defaults — neither is appropriate when we're rehydrating mid-session.
     */
    window.onPairResume = (json: string) => {
      try {
        const o = JSON.parse(json) as {
          pairId?: string;
          agentId?: string;
          name?: string;
          model?: string;
          defaultLongContext?: boolean;
          defaultReasoning?: string;
        };
        if (!o || !o.agentId) return;
        setSelectedState([{
          agentId: o.agentId,
          name: o.name ?? o.agentId,
          role: 'coordinator',
          model: o.model,
          defaultLongContext: o.defaultLongContext,
          defaultReasoning: o.defaultReasoning,
        }]);
        if (o.pairId) setPairId(o.pairId);
      } catch { /* ignore malformed */ }
    };

    const realInjectHandler = (json: string) => {
      let parsed: { pairId: string; supervisorId: string; prompt: string; directiveId?: string } | null = null;
      try {
        parsed = JSON.parse(json);
      } catch (err) {
        console.warn('[INJECT_TRACE] webview onPairInjectPrompt parse failed', err);
        return;
      }
      if (!parsed) return;
      const handlerAttached = Boolean(injectHandlerRef.current);
      console.info('[INJECT_TRACE] webview onPairInjectPrompt',
        { pairId: parsed.pairId, directiveId: parsed.directiveId,
          promptLen: parsed.prompt?.length ?? 0, handlerAttached });

      // Buffer if the App-side handler hasn't registered yet (cold-start
      // race: PairProvider mounts before PairAppBridge runs its
      // registerInjectPromptHandler effect). Drained from registerInjectPromptHandler.
      if (!injectHandlerRef.current) {
        pendingInjectQueueRef.current.push(parsed);
        console.warn('[INJECT_TRACE] webview onPairInjectPrompt — handler not attached, buffered',
          { directiveId: parsed.directiveId, bufferedCount: pendingInjectQueueRef.current.length });
        return;
      }
      try {
        injectHandlerRef.current(parsed.pairId, parsed.supervisorId, parsed.prompt, parsed.directiveId);
      } catch (err) {
        console.error('[INJECT_TRACE] webview onPairInjectPrompt handler threw',
          { directiveId: parsed.directiveId, err: String(err) });
      }
    };

    // Drain anything the index.html stub buffered before React mounted.
    // The stub at webview/index.html intercepts callJavaScript("window.onPairInjectPrompt",...)
    // and pushes into window.__pairInjectInbox; we replay those entries here
    // (in arrival order) THEN install the real handler so a push that lands
    // mid-drain still reaches the real handler.
    const inbox = (window as unknown as { __pairInjectInbox?: string[] }).__pairInjectInbox;
    if (Array.isArray(inbox) && inbox.length > 0) {
      const drained = inbox.splice(0, inbox.length);
      console.info('[INJECT_TRACE] PairProvider draining window inbox', { count: drained.length });
      for (const json of drained) {
        try { realInjectHandler(json); }
        catch (err) { console.error('[INJECT_TRACE] PairProvider inbox drain delivery threw', err); }
      }
    }

    window.onPairInjectPrompt = realInjectHandler;

    window.onPairEscalate = (json: string) => {
      try {
        const o = JSON.parse(json);
        const payload = o?.payload ?? {};
        const choices: Array<{ id: string; label: string; description?: string }> =
          Array.isArray(payload.choices)
            ? payload.choices.map((c: unknown, idx: number) =>
                typeof c === 'string'
                  ? { id: String.fromCharCode(65 + idx), label: c }
                  : (c as { id: string; label: string; description?: string })
              )
            : [
                { id: 'A', label: 'Accept' },
                { id: 'B', label: 'Reject' },
                { id: 'C', label: 'Handle manually' },
              ];
        setPendingEscalate({
          id: `esc_${Date.now()}`,
          pairId: o?.pairId,
          supervisorId: o?.supervisorId,
          supervisorName: o?.supervisorName,
          reason: o?.reason ?? '',
          question: payload.question ?? payload.proposal ?? '',
          choices,
          contextFiles: Array.isArray(payload.context_files) ? payload.context_files : undefined,
          kind: o?.kind,
          proposal: payload.proposal,
          stats: o?.stats && typeof o.stats === 'object' ? o.stats : undefined,
          steps: Array.isArray(o?.steps) ? o.steps : undefined,
          // Java only routes blocking escalations to the modal, so default to
          // blocking unless the payload explicitly opts out.
          blocking: payload.blocking !== false,
        });
      } catch { /* ignore */ }
    };

    window.onPairOperationError = (json: string) => {
      try {
        const o = JSON.parse(json);
        console.warn('[Pair] operation error:', o);
      } catch { /* ignore */ }
    };

    // Phase 2: PairStatusPusher snapshot. Pre-throttled on the Java side, so
    // we can setState directly without further coalescing.
    window.onPairStatusUpdate = (json: string) => {
      try {
        const o = JSON.parse(json) as PairStatusSnapshot;
        if (!o || typeof o !== 'object' || !o.pairId) return;
        setPairStatus(o);
      } catch { /* ignore malformed push */ }
    };

    window.onPairThinking = (json: string) => {
      try {
        const o = JSON.parse(json) as { supervisorId?: string; thinking?: boolean };
        if (!o.supervisorId) return;
        const agentId = o.supervisorId;
        setThinkingByAgentId((prev) => {
          if ((prev[agentId] ?? false) === !!o.thinking) return prev;
          return { ...prev, [agentId]: !!o.thinking };
        });
        if (!o.thinking) {
          // Turn finished — clear the streaming flag and mark any in-flight
          // assistant messages as no-longer-streaming. We don't know the
          // exact turnId here (the daemon signals at turn boundary), so we
          // sweep the whole supervisor history; isStreaming is only ever set
          // on the latest assistant message anyway.
          setStreamingByAgentId((prev) => {
            if (!prev[agentId]) return prev;
            return { ...prev, [agentId]: false };
          });
          setMessagesByAgentId((prev) => {
            const list = prev[agentId];
            if (!list) return prev;
            let changed = false;
            const next = list.map((m) => {
              if (m.type === 'assistant' && m.isStreaming) {
                changed = true;
                return { ...m, isStreaming: false };
              }
              return m;
            });
            if (!changed) return prev;
            return { ...prev, [agentId]: next };
          });
        }
      } catch { /* ignore */ }
    };

    window.onSupervisorLiveUsage = (json: string) => {
      try {
        const o = JSON.parse(json) as { supervisorId?: string; outputTokens?: number };
        if (!o || !o.supervisorId || typeof o.outputTokens !== 'number') return;
        const agentId = o.supervisorId;
        const tokens = o.outputTokens;
        setLiveOutputTokensByAgentId((prev) =>
          prev[agentId] === tokens ? prev : { ...prev, [agentId]: tokens });
      } catch { /* ignore malformed */ }
    };

    return () => {
      window.onSupervisorMessage = prevSupervisorMessage;
      window.onSupervisorMessageBatch = prevSupervisorMessageBatch;
      window.onPairStarted = prevStarted;
      window.onPairStopped = prevStopped;
      window.onPairActionEvent = prevAction;
      window.onPairInjectPrompt = prevInject;
      window.onPairEscalate = prevEscalate;
      window.onPairOperationError = prevOpError;
      window.onPairThinking = prevThinking;
      window.onPairStatusUpdate = prevPairStatus;
      window.onPairResume = prevPairResume;
      window.onPairAlert = prevAlert;
      window.onPairNotice = prevNotice;
      window.onSupervisorLiveUsage = prevSupervisorLiveUsage;
    };
  }, [appendAssistantBlocks, attachToolResults, endStreaming]);

  // 2026-05-25 (intermittent-inject fix D): tell Java the webview side is
  // alive and PairProvider is mounted, so ActionRouter can drain anything
  // it buffered while we were cold-starting. Re-fires whenever pairId
  // changes — covers webview reload (PairProvider remounts → state lost →
  // ActionRouter still has the live pair → resending ready triggers a fresh
  // drain of whatever accumulated during the reload window).
  useEffect(() => {
    if (!pairId) return;
    try {
      sendToJava(`pair_webview_ready:${JSON.stringify({ pairId })}`);
      console.info('[INJECT_TRACE] PairProvider sent pair_webview_ready', { pairId });
    } catch (err) {
      console.warn('[INJECT_TRACE] PairProvider sending pair_webview_ready failed', err);
    }
  }, [pairId]);

  useEffect(() => {
    const onSupervisorUsage = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (!detail || typeof detail !== 'object') return;
      const agentId: string | undefined = detail.supervisorId;
      if (!agentId) return;
      const used = typeof detail.usedTokens === 'number'
        ? detail.usedTokens
        : (typeof detail.totalTokens === 'number' ? detail.totalTokens : 0);
      const maxTokens = typeof detail.maxTokens === 'number'
        ? detail.maxTokens
        : (typeof detail.limit === 'number' ? detail.limit : 0);
      const percentage = typeof detail.percentage === 'number'
        ? Math.max(0, Math.min(100, detail.percentage))
        : 0;
      setUsageByAgentId((prev) => ({
        ...prev,
        [agentId]: {
          model: typeof detail.model === 'string' ? detail.model : '',
          totalPromptTokens: used,
          maxTokens,
          percentage,
        },
      }));
    };
    window.addEventListener('cc-gui:supervisor-usage', onSupervisorUsage);
    return () => window.removeEventListener('cc-gui:supervisor-usage', onSupervisorUsage);
  }, []);

  const value = useMemo<PairContextValue>(
    () => ({
      selected,
      setSelected,
      startSupervisorPair,
      isPairActive: selected.length > 0,
      openManager,
      registerOpenManager,
      messagesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      streamingByAgentId,
      liveOutputTokensByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
      longContextEnabled,
      setLongContextEnabled,
      usageByAgentId,
      pairStatus,
      notices,
      alerts,
      dismissAlert,
      respondToEscalate,
      dismissEscalate,
      registerInjectPromptHandler,
      sendUserInputToSupervisor,
      queueByAgentId,
      enqueueSupervisorMessage,
      dequeueSupervisorMessage,
      draftByAgentId,
      setSupervisorDraft,
      autonomyMode,
      setAutonomyMode,
    }),
    [
      selected,
      setSelected,
      startSupervisorPair,
      openManager,
      registerOpenManager,
      messagesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      streamingByAgentId,
      liveOutputTokensByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
      longContextEnabled,
      setLongContextEnabled,
      usageByAgentId,
      pairStatus,
      notices,
      alerts,
      dismissAlert,
      respondToEscalate,
      dismissEscalate,
      registerInjectPromptHandler,
      sendUserInputToSupervisor,
      queueByAgentId,
      enqueueSupervisorMessage,
      dequeueSupervisorMessage,
      draftByAgentId,
      setSupervisorDraft,
      autonomyMode,
      setAutonomyMode,
    ]
  );

  return <PairContext.Provider value={value}>{children}</PairContext.Provider>;
}

/**
 * Defensive: returns a no-op shape when called outside the provider.
 */
export function usePairContext(): PairContextValue {
  const ctx = useContext(PairContext);
  if (ctx) return ctx;
  return {
    selected: [],
    setSelected: () => { /* no-op */ },
    startSupervisorPair: () => { /* no-op */ },
    isPairActive: false,
    openManager: () => { /* no-op */ },
    registerOpenManager: () => { /* no-op */ },
    messagesByAgentId: {},
    pairId: null,
    pendingEscalate: null,
    thinkingByAgentId: {},
    streamingByAgentId: {},
    liveOutputTokensByAgentId: {},
    modelOverrideByAgentId: {},
    setSupervisorModel: () => { /* no-op */ },
    reasoningByAgentId: {},
    setSupervisorReasoning: () => { /* no-op */ },
    longContextEnabled: false,
    setLongContextEnabled: () => { /* no-op */ },
    usageByAgentId: {},
    pairStatus: null,
    notices: [],
    alerts: [],
    dismissAlert: () => { /* no-op */ },
    respondToEscalate: () => { /* no-op */ },
    dismissEscalate: () => { /* no-op */ },
    registerInjectPromptHandler: () => { /* no-op */ },
    sendUserInputToSupervisor: (
      _text: string,
      _attachments?: Array<{ path: string }>
    ) => { /* no-op */ },
    queueByAgentId: {},
    enqueueSupervisorMessage: () => { /* no-op */ },
    dequeueSupervisorMessage: () => { /* no-op */ },
    draftByAgentId: {},
    setSupervisorDraft: () => { /* no-op */ },
    // Phase 5 (2026-05-24): autonomy defaults — fall back to "full" (matches
    // Java's 2026-05-25 default) so the UI doesn't show a confusing "unset"
    // state outside a Provider.
    autonomyMode: undefined,
    setAutonomyMode: () => { /* no-op */ },
  };
}
