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
import type { SelectedSupervisor } from '../../types/supervisorAgent';
import type { ClaudeMessage, ClaudeContentOrResultBlock, ToolResultBlock } from '../../types';
import type { ReasoningEffort } from '../ChatInputBox/types';

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

interface PairContextValue {
  /** Currently active supervisors on this session. Empty array = Pair disabled. */
  selected: SelectedSupervisor[];
  setSelected: (next: SelectedSupervisor[]) => void;
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
  modelOverrideByAgentId: Record<string, string>;
  setSupervisorModel: (agentId: string, model: string | null) => void;
  reasoningByAgentId: Record<string, ReasoningEffort>;
  setSupervisorReasoning: (agentId: string, effort: ReasoningEffort) => void;
  longContextEnabled: boolean;
  setLongContextEnabled: (enabled: boolean) => void;
  usageByAgentId: Record<string, SupervisorUsage>;
  respondToEscalate: (choice: string, note?: string) => void;
  dismissEscalate: () => void;
  registerInjectPromptHandler: (
    fn: (pairId: string, supervisorId: string, prompt: string) => void
  ) => void;
  sendUserInputToSupervisor: (
    text: string,
    attachments?: Array<{ path: string }>
  ) => void;
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

  const openManagerRef = useRef<() => void>(() => { /* not registered yet */ });
  const injectHandlerRef = useRef<((pairId: string, supervisorId: string, prompt: string) => void) | null>(null);
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

  const openManager = useCallback(() => {
    openManagerRef.current();
  }, []);

  const registerOpenManager = useCallback((fn: () => void) => {
    openManagerRef.current = fn;
  }, []);

  const registerInjectPromptHandler = useCallback(
    (fn: (pairId: string, supervisorId: string, prompt: string) => void) => {
      injectHandlerRef.current = fn;
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
     */
    window.onSupervisorMessage = (json: string) => {
      try {
        const evt = JSON.parse(json);
        const agentId: string | undefined = evt?.supervisorId;
        const turnStr: string | undefined = typeof evt?.turnId === 'string' ? evt.turnId : undefined;
        const msg = evt?.message;
        if (!agentId || !msg || typeof msg !== 'object') return;

        const turnId = hashTurnId(turnStr || `sm_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`);

        if (msg.type === 'assistant' && Array.isArray(msg.message?.content)) {
          const blocks: ClaudeContentOrResultBlock[] = [];
          for (const block of msg.message.content) {
            if (!block || typeof block !== 'object') continue;
            // Skip the action protocol envelope — onPairActionEvent renders it
            // with the normalised payload.
            if (block.type === 'tool_use' && typeof block.name === 'string'
                && block.name.includes('emit_action')) continue;
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
            if (block.type === 'tool_result') {
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
      } catch { /* ignore malformed */ }
    };

    window.onPairInjectPrompt = (json: string) => {
      try {
        const o = JSON.parse(json) as { pairId: string; supervisorId: string; prompt: string };
        injectHandlerRef.current?.(o.pairId, o.supervisorId, o.prompt);
      } catch { /* ignore */ }
    };

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
        });
      } catch { /* ignore */ }
    };

    window.onPairOperationError = (json: string) => {
      try {
        const o = JSON.parse(json);
        console.warn('[Pair] operation error:', o);
      } catch { /* ignore */ }
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

    return () => {
      window.onSupervisorMessage = prevSupervisorMessage;
      window.onPairStarted = prevStarted;
      window.onPairStopped = prevStopped;
      window.onPairActionEvent = prevAction;
      window.onPairInjectPrompt = prevInject;
      window.onPairEscalate = prevEscalate;
      window.onPairOperationError = prevOpError;
      window.onPairThinking = prevThinking;
    };
  }, [appendAssistantBlocks, attachToolResults, endStreaming]);

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
      isPairActive: selected.length > 0,
      openManager,
      registerOpenManager,
      messagesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      streamingByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
      longContextEnabled,
      setLongContextEnabled,
      usageByAgentId,
      respondToEscalate,
      dismissEscalate,
      registerInjectPromptHandler,
      sendUserInputToSupervisor,
    }),
    [
      selected,
      setSelected,
      openManager,
      registerOpenManager,
      messagesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      streamingByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
      longContextEnabled,
      setLongContextEnabled,
      usageByAgentId,
      respondToEscalate,
      dismissEscalate,
      registerInjectPromptHandler,
      sendUserInputToSupervisor,
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
    isPairActive: false,
    openManager: () => { /* no-op */ },
    registerOpenManager: () => { /* no-op */ },
    messagesByAgentId: {},
    pairId: null,
    pendingEscalate: null,
    thinkingByAgentId: {},
    streamingByAgentId: {},
    modelOverrideByAgentId: {},
    setSupervisorModel: () => { /* no-op */ },
    reasoningByAgentId: {},
    setSupervisorReasoning: () => { /* no-op */ },
    longContextEnabled: false,
    setLongContextEnabled: () => { /* no-op */ },
    usageByAgentId: {},
    respondToEscalate: () => { /* no-op */ },
    dismissEscalate: () => { /* no-op */ },
    registerInjectPromptHandler: () => { /* no-op */ },
    sendUserInputToSupervisor: (
      _text: string,
      _attachments?: Array<{ path: string }>
    ) => { /* no-op */ },
  };
}
