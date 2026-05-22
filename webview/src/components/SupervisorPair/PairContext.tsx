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
   */
  sendUserInputToSupervisor: (text: string) => void;
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
    }
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

  const sendUserInputToSupervisor = useCallback((text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const pid = pairIdRef.current ?? '';
    sendToJava(`pair_send_user_input:${JSON.stringify({ pairId: pid, text: trimmed })}`);
    // Optimistically render the message in the right pane so the user sees
    // it immediately. The actual Supervisor response arrives via
    // window.onPairActionEvent.
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
  }, [selected]);

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
    };

    window.onPairActionEvent = (json: string) => {
      try {
        const evt = JSON.parse(json);
        const agentId: string | undefined = evt?.supervisorId;
        if (!agentId) return;

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
    respondToEscalate: () => { /* no-op */ },
    dismissEscalate: () => { /* no-op */ },
    registerInjectPromptHandler: () => { /* no-op */ },
    sendUserInputToSupervisor: () => { /* no-op */ },
  };
}
