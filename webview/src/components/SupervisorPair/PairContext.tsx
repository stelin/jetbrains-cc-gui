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
import type { SupervisorLogEntry } from './SupervisorSubPanel';
import type { SupervisorActionType } from './ActionCard';
import type { ReasoningEffort } from '../ChatInputBox/types';

/**
 * Shape for an escalate request surfaced to the user by ActionRouter.
 * Mirrors {@code escalate_to_human} payload from the supervisor.
 */
export interface EscalateRequest {
  /** Set by ActionRouter to ensure each dialog instance is unique even if payload repeats. */
  id: string;
  pairId?: string;
  supervisorId?: string;
  supervisorName?: string;
  reason?: string;
  question?: string;
  choices?: Array<{ id: string; label: string; description?: string }>;
  contextFiles?: string[];
  /** When non-empty, ActionRouter raised an amendment request rather than a regular escalate. */
  kind?: 'amendment_request';
  proposal?: string;
  /**
   * v3: session stats snapshot attached when ActionRouter dispatches a normal
   * escalate (verification at end-of-plan). Rendered as the summary header in
   * EscalateDialog so the user can see how the session went at a glance.
   */
  stats?: {
    auto_recover_count?: number;
    escalate_count?: number;
    decision_count?: number;
    decision_review_flag_count?: number;
    review_reject_count?: number;
    verify_fail_count?: number;
  };
  /** v3: step-level progress snapshot, paired with `stats`. */
  steps?: Array<{ index?: number; status?: string }>;
}

interface PairContextValue {
  /** Currently active supervisors on this session. Empty array = Pair disabled. */
  selected: SelectedSupervisor[];
  /** Replace the active supervisor list. Pass [] to disable Pair mode. */
  setSelected: (next: SelectedSupervisor[]) => void;
  /** Whether double-pane layout should render. */
  isPairActive: boolean;
  /** Open the settings → Supervisor tab. Registered by the App-level host. */
  openManager: () => void;
  /** Register the openManager handler (called once by App.tsx). */
  registerOpenManager: (fn: () => void) => void;
  /**
   * Log entries keyed by Supervisor agent id, populated from
   * window.onPairActionEvent stream. Consumed by SupervisorPane.
   */
  entriesByAgentId: Record<string, SupervisorLogEntry[]>;
  /** Backing pair id (populated when Java replies with window.onPairStarted). */
  pairId: string | null;
  /** Optional escalate request to surface as a modal dialog. Null = no dialog. */
  pendingEscalate: EscalateRequest | null;
  /**
   * Map of supervisor agentId → "is thinking" boolean. True from the moment
   * an event is forwarded to the daemon until an ACTION arrives. Drives the
   * loading spinner in the right pane.
   */
  thinkingByAgentId: Record<string, boolean>;
  /**
   * Map of supervisor agentId → runtime model override. Set via the composer
   * model picker; takes precedence over the agent's persisted default for the
   * current pair session only (does NOT mutate settings).
   * Empty/missing entry = use the agent's configured default.
   */
  modelOverrideByAgentId: Record<string, string>;
  /**
   * Set (or clear, with `null`) the runtime model override for a supervisor in
   * the current pair. Also notifies Java so the next turn uses the new model.
   */
  setSupervisorModel: (agentId: string, model: string | null) => void;
  /**
   * Map of supervisor agentId → runtime reasoning effort override (low/medium/
   * high/xhigh/max). Like the model override, this only lives for the duration
   * of the current pair session — it does not persist back to agent config.
   */
  reasoningByAgentId: Record<string, ReasoningEffort>;
  /** Set the runtime reasoning effort for a supervisor in the current pair. */
  setSupervisorReasoning: (agentId: string, effort: ReasoningEffort) => void;
  /** Send the user's choice for the active escalate dialog. */
  respondToEscalate: (choice: string, note?: string) => void;
  /** Dismiss the active escalate dialog (no choice sent). */
  dismissEscalate: () => void;
  /**
   * Register a handler for {@code onPairInjectPrompt}. Whoever owns the chat
   * input (App.tsx) sets this once; the supervisor pipeline will call it when
   * the Supervisor emits an inject_prompt action.
   */
  registerInjectPromptHandler: (
    fn: (pairId: string, supervisorId: string, prompt: string) => void
  ) => void;
  /**
   * Send a free-form text message from the user to the Supervisor (NOT to
   * the main AI). Used by the right-pane composer to direct/coordinate the
   * Supervisor — e.g. "the design doc is at docs/plans/foo.md, coordinate
   * the main AI to implement it".
   *
   * {@code attachments} carries the structured local-path references the
   * composer extracted from `@<path>` tokens in {@code text}. Java translates
   * each `path` local→remote via {@code PathMapper} and substitutes the same
   * `@<localPath>` occurrences in the text body before forwarding to the
   * daemon, so the Supervisor only ever sees remote paths in its context.
   */
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

/**
 * Top-level provider for the Supervisor Pair UI.
 * Also owns the window.onPair* callback subscriptions so any component within
 * the provider can react to lifecycle and action stream events.
 */
export function PairProvider({ children }: PairProviderProps) {
  const [selected, setSelectedState] = useState<SelectedSupervisor[]>([]);
  const [entriesByAgentId, setEntriesByAgentId] = useState<Record<string, SupervisorLogEntry[]>>({});
  const [pairId, setPairId] = useState<string | null>(null);
  const [pendingEscalate, setPendingEscalate] = useState<EscalateRequest | null>(null);
  const [thinkingByAgentId, setThinkingByAgentId] = useState<Record<string, boolean>>({});
  const [modelOverrideByAgentId, setModelOverrideByAgentId] = useState<Record<string, string>>({});
  const [reasoningByAgentId, setReasoningByAgentId] = useState<Record<string, ReasoningEffort>>({});

  const openManagerRef = useRef<() => void>(() => { /* not registered yet */ });
  const injectHandlerRef = useRef<((pairId: string, supervisorId: string, prompt: string) => void) | null>(null);
  // Stable ref for current pairId, used inside callbacks that close over old state.
  const pairIdRef = useRef<string | null>(null);
  useEffect(() => { pairIdRef.current = pairId; }, [pairId]);

  const setSelected = useCallback((next: SelectedSupervisor[]) => {
    setSelectedState(next);
    if (next.length === 0) {
      // User disabled — clear transient state.
      setEntriesByAgentId({});
      setPairId(null);
      setPendingEscalate(null);
      setThinkingByAgentId({});
      setModelOverrideByAgentId({});
      setReasoningByAgentId({});
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
    // Notify Java so the next turn for this supervisor uses the new model.
    // `model: ""` signals "fall back to the agent default".
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

  const appendEntry = useCallback((agentId: string, entry: SupervisorLogEntry) => {
    setEntriesByAgentId((prev) => {
      const list = prev[agentId] ? [...prev[agentId], entry] : [entry];
      // Cap per-agent history at 500 entries to avoid memory blow-up on long runs.
      const trimmed = list.length > 500 ? list.slice(list.length - 500) : list;
      return { ...prev, [agentId]: trimmed };
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
      // Optimistically render the message in the right pane so the user sees
      // it immediately. We show the ORIGINAL local-path text — that's what the
      // user typed and expects to see; Java does the remote translation on
      // the way to the daemon only.
      const firstAgentId = selected[0]?.agentId;
      if (firstAgentId) {
        setEntriesByAgentId((prev) => {
          const next = prev[firstAgentId] ? [...prev[firstAgentId]] : [];
          next.push({
            id: `u_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            kind: 'user',
            text: trimmed,
          });
          const trimmedList = next.length > 500 ? next.slice(next.length - 500) : next;
          return { ...prev, [firstAgentId]: trimmedList };
        });
      }
    },
    [selected]
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

    window.onPairStarted = (json: string) => {
      try {
        const o = JSON.parse(json) as { pairId?: string };
        if (o.pairId) setPairId(o.pairId);
      } catch { /* ignore */ }
    };

    window.onPairStopped = () => {
      setPairId(null);
      setEntriesByAgentId({});
      setPendingEscalate(null);
      setModelOverrideByAgentId({});
      setReasoningByAgentId({});
    };

    window.onPairActionEvent = (json: string) => {
      try {
        const evt = JSON.parse(json);
        const agentId: string | undefined = evt?.supervisorId;
        if (!agentId) return;

        // v3: a decision_record event carries a single self-decision entry
        // outside the normal action stream. Each one is rendered as its own
        // standalone card (not grouped under a supervisor message bubble).
        if (evt?.kind === 'decision_record' && evt.decision && typeof evt.decision === 'object') {
          appendEntry(agentId, {
            id: `d_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            kind: 'decision',
            decision: evt.decision,
          });
          return;
        }

        const baseId = `m_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
        // Each turn is rendered as ONE supervisor message bubble carrying:
        //   - reasoning (foldable, when present)
        //   - natural-language conclusion (the bubble body)
        //   - one ACTION card (small badge under the bubble)
        // We emit them as separate entries with the same `groupKey` so the
        // renderer can collapse them into a single visual message.
        const reasoningText = typeof evt.reasoningText === 'string' ? evt.reasoningText.trim() : '';
        const naturalText   = typeof evt.naturalText   === 'string' ? evt.naturalText.trim()   : '';
        const groupKey = baseId;

        if (reasoningText) {
          appendEntry(agentId, {
            id: `${baseId}_r`,
            kind: 'reasoning',
            text: reasoningText,
            groupKey,
          });
        }
        if (naturalText) {
          appendEntry(agentId, {
            id: `${baseId}_t`,
            kind: 'think',
            text: naturalText,
            groupKey,
          });
        }

        // Action card.
        const actionType: SupervisorActionType | undefined = evt?.action?.action;
        if (actionType) {
          const payload = evt.action.payload ?? {};
          const reason: string = evt.action.reason ?? '';
          let summary: string | undefined;
          let detail: string | undefined = reason || undefined;
          if (actionType === 'inject_prompt' && typeof payload.prompt === 'string') {
            summary = payload.prompt.length > 60 ? payload.prompt.slice(0, 60) + '…' : payload.prompt;
          } else if (actionType === 'retry_with_hint') {
            const w = payload.wait_seconds;
            summary = typeof w === 'number' ? `wait ${w}s` : 'auto-recover';
          } else if (actionType === 'escalate_to_human' && typeof payload.question === 'string') {
            summary = payload.question.length > 60 ? payload.question.slice(0, 60) + '…' : payload.question;
          }
          appendEntry(agentId, {
            id: `${baseId}_a`,
            kind: 'action',
            action: { type: actionType, summary, detail },
            groupKey,
          });
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
        setThinkingByAgentId((prev) => {
          if ((prev[o.supervisorId!] ?? false) === !!o.thinking) return prev;
          return { ...prev, [o.supervisorId!]: !!o.thinking };
        });
      } catch { /* ignore */ }
    };

    return () => {
      window.onPairStarted = prevStarted;
      window.onPairStopped = prevStopped;
      window.onPairActionEvent = prevAction;
      window.onPairInjectPrompt = prevInject;
      window.onPairEscalate = prevEscalate;
      window.onPairOperationError = prevOpError;
      window.onPairThinking = prevThinking;
    };
  }, [appendEntry]);

  const value = useMemo<PairContextValue>(
    () => ({
      selected,
      setSelected,
      isPairActive: selected.length > 0,
      openManager,
      registerOpenManager,
      entriesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
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
      entriesByAgentId,
      pairId,
      pendingEscalate,
      thinkingByAgentId,
      modelOverrideByAgentId,
      setSupervisorModel,
      reasoningByAgentId,
      setSupervisorReasoning,
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
    entriesByAgentId: {},
    pairId: null,
    pendingEscalate: null,
    thinkingByAgentId: {},
    modelOverrideByAgentId: {},
    setSupervisorModel: () => { /* no-op */ },
    reasoningByAgentId: {},
    setSupervisorReasoning: () => { /* no-op */ },
    respondToEscalate: () => { /* no-op */ },
    dismissEscalate: () => { /* no-op */ },
    registerInjectPromptHandler: () => { /* no-op */ },
    sendUserInputToSupervisor: (
      _text: string,
      _attachments?: Array<{ path: string }>
    ) => { /* no-op */ },
  };
}
