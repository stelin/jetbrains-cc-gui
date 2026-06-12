import { useCallback, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, HistoryData, HistoryKind } from '../types';
import { sendBridgeEvent, sendToJava } from '../utils/bridge';

type ViewMode = 'chat' | 'history' | 'settings' | 'workflow' | 'bug-list';

type ToastType = 'info' | 'success' | 'warning' | 'error';

const createSessionTransitionToken = () =>
  `transition-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

interface UseSessionManagementOptions {
  messages: ClaudeMessage[];
  loading: boolean;
  historyData: HistoryData | null;
  currentSessionId: string | null;
  setHistoryData: (data: HistoryData | null) => void;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setCurrentView: (view: ViewMode) => void;
  setCurrentSessionId: (id: string | null) => void;
  setCustomSessionTitle: (title: string | null) => void;
  setUsagePercentage: (percent: number) => void;
  setUsageUsedTokens: (tokens: number | undefined) => void;
  setUsageMaxTokens: (tokens: number | undefined) => void;
  setStatus: (status: string) => void;
  setLoading: (loading: boolean) => void;
  setIsThinking: (thinking: boolean) => void;
  setStreamingActive: (active: boolean) => void;
  clearToasts: () => void;
  addToast: (message: string, type?: ToastType) => void;
  t: TFunction;
}

interface UseSessionManagementReturn {
  showNewSessionConfirm: boolean;
  showInterruptConfirm: boolean;
  suppressNextStatusToastRef: React.MutableRefObject<boolean>;
  createNewSession: () => void;
  forceCreateNewSession: () => void;
  /**
   * Session-kind refactor: reset the main-chat UI for a brand-new supervised
   * session (interrupt if loading + clear messages/transition). The actual
   * `session_create_supervised` IPC is sent by PairContext.createSupervisedSession;
   * this only resets the left-pane chat so the new session starts clean.
   */
  beginSupervisedSession: () => void;
  handleConfirmNewSession: () => void;
  handleCancelNewSession: () => void;
  handleConfirmInterrupt: () => void;
  handleCancelInterrupt: () => void;
  loadHistorySession: (
    sessionId: string,
    opts?: { containerId?: string; kind?: HistoryKind; title?: string }
  ) => void;
  /**
   * Open a history session in a NEW tab (instead of replacing the current tab's
   * session). Used by the history list so normal AND supervised sessions both
   * open fresh, correct-kind tabs. Workflow still opens its own top-level view.
   */
  openHistoryInNewTab: (
    sessionId: string,
    opts?: { containerId?: string; kind?: HistoryKind; title?: string }
  ) => void;
  deleteHistorySession: (sessionId: string) => void;
  exportHistorySession: (sessionId: string, title: string) => void;
  toggleFavoriteSession: (sessionId: string) => void;
  updateHistoryTitle: (sessionId: string, newTitle: string) => void;
}

/**
 * Hook for managing session operations (create, load, delete, export, etc.)
 */
export function useSessionManagement({
  messages,
  loading,
  historyData,
  currentSessionId,
  setHistoryData,
  setMessages,
  setCurrentView,
  setCurrentSessionId,
  setCustomSessionTitle,
  setUsagePercentage,
  setUsageUsedTokens,
  setUsageMaxTokens,
  setStatus,
  setLoading: setLoadingState,
  setIsThinking,
  setStreamingActive,
  clearToasts,
  addToast,
  t,
}: UseSessionManagementOptions): UseSessionManagementReturn {
  const [showNewSessionConfirm, setShowNewSessionConfirm] = useState(false);
  const [showInterruptConfirm, setShowInterruptConfirm] = useState(false);
  const pendingActionRef = useRef<'newSession' | null>(null);
  const suppressNextStatusToastRef = useRef(false);
  const transitionTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historyDataRef = useRef(historyData);
  historyDataRef.current = historyData;

  const beginSessionTransition = useCallback((nextSessionId: string | null, nextTitle: string | null) => {
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = createSessionTransitionToken();
    // Use the single cleanup entry point exposed by useWindowCallbacks.
    // This clears both React state AND internal streaming refs in one shot.
    if (typeof (window as any).__resetTransientUiState === 'function') {
      (window as any).__resetTransientUiState();
    } else {
      // Fallback if useWindowCallbacks hasn't mounted yet (e.g. during SSR/tests)
      clearToasts();
      setStatus('');
      setLoadingState(false);
      setIsThinking(false);
      setStreamingActive(false);
    }
    setMessages([]);
    setCurrentSessionId(nextSessionId);
    setCustomSessionTitle(nextTitle);
    setUsagePercentage(0);
    setUsageUsedTokens(undefined);
    setUsageMaxTokens(undefined);

    // FIX: Safety timeout to auto-release the session transition guard.
    // If the backend's historyLoadComplete signal is lost (e.g., JCEF IPC failure
    // during webview reload, or a backend error that prevents the callback),
    // __sessionTransitioning would remain true permanently, silently dropping ALL
    // message callbacks (updateMessages, onContentDelta, onStreamStart, etc.).
    // This makes the webview appear "dead" while the backend continues working.
    if (transitionTimeoutRef.current !== null) {
      clearTimeout(transitionTimeoutRef.current);
    }
    const token = window.__sessionTransitionToken;
    transitionTimeoutRef.current = setTimeout(() => {
      transitionTimeoutRef.current = null;
      if (window.__sessionTransitioning && window.__sessionTransitionToken === token) {
        console.warn('[SessionManagement] Transition guard timed out — auto-releasing');
        window.__sessionTransitioning = false;
        window.__sessionTransitionToken = null;
      }
    }, 15_000); // 15 seconds — generous enough for slow history loads
  }, [clearToasts, setStatus, setLoadingState, setIsThinking, setStreamingActive, setMessages, setCurrentSessionId, setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens]);

  // Create new session
  const createNewSession = useCallback(() => {
    // [FIX] Prioritize loading check - if AI is responding, must interrupt first
    // This prevents creating new session without stopping the current conversation
    if (loading) {
      // If loading (AI is responding), show interrupt confirmation
      pendingActionRef.current = 'newSession';
      setShowInterruptConfirm(true);
    } else if (messages.length > 0) {
      // If there are messages but not loading, show new session confirmation
      pendingActionRef.current = 'newSession';
      setShowNewSessionConfirm(true);
    } else {
      // If empty and not loading, directly create new session
      beginSessionTransition(null, null);
      sendBridgeEvent('create_new_session');
    }
  }, [beginSessionTransition, messages.length, loading]);

  // Force create new session (no confirmation, used by /clear /new /reset commands)
  const forceCreateNewSession = useCallback(() => {
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
  }, [beginSessionTransition, loading]);

  // Session-kind refactor: reset the left-pane chat for a new supervised
  // session. No create IPC here — PairContext.createSupervisedSession sends
  // `session_create_supervised`; this just clears messages/transition so the
  // new session's main AI starts from a clean slate.
  const beginSupervisedSession = useCallback(() => {
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
  }, [beginSessionTransition, loading]);

  // Confirm new session
  const handleConfirmNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    // [FIX] Safety check: if loading started while dialog was open, send interrupt first
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
    pendingActionRef.current = null;
  }, [beginSessionTransition, loading]);

  // Cancel new session
  const handleCancelNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    pendingActionRef.current = null;
  }, []);

  // Confirm interrupt
  const handleConfirmInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    // Send interrupt signal and create new session
    sendBridgeEvent('interrupt_session');
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
    pendingActionRef.current = null;
  }, [beginSessionTransition]);

  // Cancel interrupt
  const handleCancelInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    pendingActionRef.current = null;
  }, []);

  // Load history session.
  // Session-kind refactor: normal sessions route by sessionId (unchanged);
  // supervised/workflow route by persistent containerId via a JSON load_session
  // payload, and workflow restores switch to the workflow view.
  const loadHistorySession = useCallback((
    sessionId: string,
    opts?: { containerId?: string; kind?: HistoryKind; title?: string }
  ) => {
    // [FIX] Send interrupt signal if AI is responding
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }

    const title = opts?.title
      ?? historyDataRef.current?.sessions?.find(s => s.sessionId === sessionId)?.title
      ?? null;

    if (opts?.containerId && opts.kind && opts.kind !== 'normal') {
      beginSessionTransition(opts.containerId, title);
      sendToJava('load_session', { containerId: opts.containerId, kind: opts.kind });
      setCurrentView(opts.kind === 'workflow' ? 'workflow' : 'chat');
      return;
    }

    beginSessionTransition(sessionId, title);
    sendBridgeEvent('load_session', sessionId);
    setCurrentView('chat');
  }, [beginSessionTransition, loading, setCurrentView]);

  // Session-kind refactor: open a history session in a NEW tab. Both the normal
  // and supervised lists route here so each open spawns a fresh tab of the
  // matching kind (no more "this is a normal tab, open a supervisor tab first"
  // guard). Java creates the tab, then its webview runs loadHistorySession
  // in-place via window.onRequestLoadHistory. Workflow keeps its existing
  // top-level cockpit behaviour (loaded in place, switches the workflow view).
  const openHistoryInNewTab = useCallback((
    sessionId: string,
    opts?: { containerId?: string; kind?: HistoryKind; title?: string }
  ) => {
    const kind: HistoryKind = opts?.kind ?? 'normal';
    if (kind === 'workflow') {
      loadHistorySession(sessionId, opts);
      return;
    }
    const payload: Record<string, unknown> = { sessionId, kind };
    if (opts?.containerId) payload.containerId = opts.containerId;
    if (opts?.title) payload.title = opts.title;
    // Two-arg form: sendToJava appends ":<payload>" itself. Passing the whole
    // "type:json" as one arg double-encodes to `...:{json}:{}`, whose trailing
    // `:{}` makes Java's JsonParser.parseString throw → handler bails → no tab.
    sendToJava('open_history_in_new_tab', payload);
  }, [loadHistorySession]);

  // Delete history session
  const deleteHistorySession = useCallback((sessionId: string) => {
    // Send delete request to Java backend
    sendBridgeEvent('delete_session', sessionId);

    // Immediately update frontend state, remove session from history list
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.filter(s => s.sessionId !== sessionId);
      const deletedSession = historyData.sessions.find(s => s.sessionId === sessionId);
      const updatedTotal = (historyData.total || 0) - (deletedSession?.messageCount || 0);

      setHistoryData({
        ...historyData,
        sessions: updatedSessions,
        total: updatedTotal
      });

      // If deleted session is current session, clear messages and reset state
      if (sessionId === currentSessionId) {
        // [FIX] Send interrupt signal if AI is responding
        if (loading) {
          sendBridgeEvent('interrupt_session');
        }
        beginSessionTransition(null, null);
        // Set flag to suppress next updateStatus toast
        suppressNextStatusToastRef.current = true;
        sendBridgeEvent('create_new_session');
      }

      // Show success toast
      addToast(t('history.sessionDeleted'), 'success');
    }
  }, [historyData, currentSessionId, loading, setHistoryData, setMessages, setCurrentSessionId, setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, addToast, t]);

  // Export history session
  const exportHistorySession = useCallback((sessionId: string, title: string) => {
    const exportData = JSON.stringify({ sessionId, title });
    sendBridgeEvent('export_session', exportData);
  }, []);

  // Toggle favorite status
  const toggleFavoriteSession = useCallback((sessionId: string) => {
    // Send favorite toggle request to backend
    sendBridgeEvent('toggle_favorite', sessionId);

    // Immediately update frontend state
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map(session => {
        if (session.sessionId === sessionId) {
          const isFavorited = !session.isFavorited;
          return {
            ...session,
            isFavorited,
            favoritedAt: isFavorited ? Date.now() : undefined
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions
      });

      // Show toast
      const session = historyData.sessions.find(s => s.sessionId === sessionId);
      if (session?.isFavorited) {
        addToast(t('history.unfavorited'), 'success');
      } else {
        addToast(t('history.favorited'), 'success');
      }
    }
  }, [historyData, setHistoryData, addToast, t]);

  // Update session title
  const updateHistoryTitle = useCallback((sessionId: string, newTitle: string) => {
    // Send update title request to backend
    const updateData = JSON.stringify({ sessionId, customTitle: newTitle });
    sendBridgeEvent('update_title', updateData);

    // Immediately update frontend state
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map(session => {
        if (session.sessionId === sessionId) {
          return {
            ...session,
            title: newTitle
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions
      });

      // Show success toast
      addToast(t('history.titleUpdated'), 'success');
    }
  }, [historyData, setHistoryData, addToast, t]);

  return {
    showNewSessionConfirm,
    showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession,
    forceCreateNewSession,
    beginSupervisedSession,
    handleConfirmNewSession,
    handleCancelNewSession,
    handleConfirmInterrupt,
    handleCancelInterrupt,
    loadHistorySession,
    openHistoryInNewTab,
    deleteHistorySession,
    exportHistorySession,
    toggleFavoriteSession,
    updateHistoryTitle,
  };
}
