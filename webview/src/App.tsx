import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryView from './components/history/HistoryView';
import BugListView from './components/BugList/BugListView';
import SettingsView from './components/settings';
import type { SettingsTab } from './components/settings/SettingsSidebar';
import { sendBridgeEvent } from './utils/bridge';
import { ChatInputBox } from './components/ChatInputBox';
import { preloadSlashCommands, forceRefreshPrompts } from './components/ChatInputBox/providers';
import {
  useScrollBehavior,
  useDialogManagement,
  useSessionManagement,
  useStreamingMessages,
  useWindowCallbacks,
  useRewindHandlers,
  useHistoryLoader,
  useFileChanges,
  useSubagents,
  useMessageQueue,
  useThemeInit,
  useContextActions,
  useMessageProcessing,
  useMessageSender,
  useFileChangesManagement,
  useModelProviderState,
} from './hooks';
import { useWorkflowTasks } from './hooks/useWorkflowTasks';
import { useTaskSteps } from './hooks/useTaskSteps';
import {
  NEW_SESSION_COMMANDS,
  RESUME_COMMANDS,
  PLAN_COMMANDS,
} from './hooks/useMessageSender';
import type { ContextInfo, ViewMode } from './hooks';
import { formatTime } from './utils/helpers';
import { extractMarkdownContent } from './utils/copyUtils';
import { applyDiffTheme, getStoredDiffTheme } from './utils/diffTheme';
import { extractTodosFromToolUse } from './utils/todoToolNormalization';
import {
  finalizeSubagentsForSettledTurn,
  finalizeTodosForSettledTurn,
  sliceLatestConversationTurn,
} from './utils/turnScope';
import type { Attachment, ChatInputBoxHandle, ReasoningEffort } from './components/ChatInputBox/types';
import { StatusPanel, StatusPanelErrorBoundary } from './components/StatusPanel';
import { SyncStatusBar } from './components/SyncStatusBar';
import { ToastContainer, type ToastMessage } from './components/Toast';
import { ScrollControl } from './components/ScrollControl';
import { ChatHeader } from './components/ChatHeader';
import { WelcomeScreen } from './components/WelcomeScreen';
import { MessageList } from './components/MessageList';
import { MessageAnchorRail } from './components/MessageAnchorRail';
import { FILE_MODIFY_TOOL_NAMES, isToolName } from './utils/toolConstants';
import type { RewindableMessage } from './components/RewindSelectDialog';
import { AppDialogs } from './components/AppDialogs';
import { PairProvider, PairLayout, usePairContext } from './components/SupervisorPair';
import NewSupervisedDialog from './components/SupervisorPair/NewSupervisedDialog';
import type { SupervisorAgent } from './types/supervisorAgent';
import { WorkflowProvider, WorkflowView, RunStatusBar, EscalationToast } from './components/WorkflowOrchestration';
import { APP_VERSION } from './version/version';
import type {
  ClaudeMessage,
  HistoryData,
  HistoryKind,
  ToolResultBlock,
} from './types';

const DEFAULT_STATUS = 'ready';

/**
 * Inner bridge that wires the Pair context to the outer App handlers:
 *   - openManager → open Settings → Supervisor tab
 *   - injectPromptHandler → execute a fake-user message as if the user typed it
 *
 * Protocol v2 (2026-05-24):
 *   - inject prompt goes through {@code handleSubmit} (queue-aware) instead of
 *     {@code executeMessage} (immediate) so a busy main AI doesn't get two
 *     concurrent sends interleaved in the daemon. The second send just queues.
 *   - directiveId from supervisor is acked back via {@code pair_directive_ack}
 *     IPC: "received" right after handleSubmit returns, "applied" when the
 *     main AI's loading state turns false (handled by an outer effect — see
 *     directiveLoadingWatcher).
 *
 * Must be mounted inside <PairProvider> so it can read the context via hook.
 */
const PairAppBridge = ({
  setSettingsInitialTab,
  setCurrentView,
  handleSubmit,
  loading,
}: {
  setSettingsInitialTab: (t: SettingsTab) => void;
  setCurrentView: (v: ViewMode) => void;
  handleSubmit: (content: string, attachments?: Attachment[]) => void;
  loading: boolean;
}) => {
  const { registerOpenManager, registerInjectPromptHandler, createSupervisedSession } = usePairContext();
  // pendingDirective: directiveId whose "applied" ack is owed once loading→false.
  const pendingDirectiveRef = useRef<{ pairId: string; directiveId: string } | null>(null);

  // 需求3: directed "新监督者标签页 + 预填" entry. Java pushes this once on the
  // fresh tab's frontend_ready when create_new_supervised_tab carried a payload
  // ({agentId, initialComposerText}). Unlike onRequestNewSupervised (picker), we
  // skip the dialog: look up the named agent, then createSupervisedSession with
  // the prefill text (which lands as a composer draft via onSessionCreated — never
  // auto-sent). Backward-compatible: the no-payload path still fires
  // onRequestNewSupervised (the picker) and is untouched.
  useEffect(() => {
    window.onRequestNewSupervisedWith = (json: string) => {
      let payload: { agentId?: string; initialComposerText?: string };
      try {
        payload = JSON.parse(json) as { agentId?: string; initialComposerText?: string };
      } catch {
        return;
      }
      const agentId = payload.agentId;
      if (!agentId) return; // Java only calls this WITH an agentId; ignore otherwise.
      const draft = payload.initialComposerText;

      let settled = false;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const prev = window.updateSupervisorAgents;
      const finish = (agent?: SupervisorAgent) => {
        if (settled) return;
        settled = true;
        window.updateSupervisorAgents = prev;
        if (timer) clearTimeout(timer);
        // Found the real agent → its model/defaults; else minimal fallback
        // (Java fills model/defaults from supervisor-agents.json when omitted).
        createSupervisedSession(agent ?? ({ id: agentId, name: agentId } as SupervisorAgent), draft);
      };
      // Chain a one-shot agents-list listener, then request the list.
      window.updateSupervisorAgents = (jsonStr: string) => {
        prev?.(jsonStr);
        if (settled) return;
        try {
          const list = (JSON.parse(jsonStr).agents ?? []) as SupervisorAgent[];
          const agent = list.find((a) => a.id === agentId);
          if (agent) finish(agent);
        } catch {
          /* ignore malformed */
        }
      };
      timer = setTimeout(() => finish(undefined), 4000);
      sendBridgeEvent('get_supervisor_agents', '');
    };
    return () => {
      delete window.onRequestNewSupervisedWith;
    };
  }, [createSupervisedSession]);

  useEffect(() => {
    registerOpenManager(() => {
      setSettingsInitialTab('supervisor');
      setCurrentView('settings');
    });
  }, [registerOpenManager, setSettingsInitialTab, setCurrentView]);

  useEffect(() => {
    registerInjectPromptHandler((pairId, _supervisorId, prompt, directiveId) => {
      if (!prompt || prompt.trim().length === 0) {
        console.warn('[INJECT_TRACE] App.injectHandler skipped — empty prompt',
          { pairId, directiveId });
        // Surface as failed so the supervisor doesn't sit on a 5min timeout
        // waiting for a directive that can never apply.
        if (directiveId) {
          try {
            sendBridgeEvent('pair_directive_ack', JSON.stringify({
              pairId, directiveId, status: 'failed',
            }));
          } catch { /* best-effort */ }
        }
        return;
      }

      // 2026-05-24 (Q4 trace): observe entry + decision-relevant state. The
      // next branch (queue vs immediate execute) is decided inside handleSubmit
      // by reading `loading`, so we log that snapshot here too.
      const preview = prompt.length > 80 ? prompt.slice(0, 80).replace(/\n/g, ' ') + '…'
                                         : prompt.replace(/\n/g, ' ');
      // 2026-05-25 (intermittent-inject fix E): include handleSubmit identity in
      // the trace so we can detect if a stale-closure ref ever fires. If
      // handleSubmit is undefined we ack failed and bail loudly.
      const handleSubmitReady = typeof handleSubmit === 'function';
      console.info('[INJECT_TRACE] App.injectHandler',
        { pairId, directiveId, loading, promptLen: prompt.length, preview, handleSubmitReady });
      if (!handleSubmitReady) {
        console.error('[INJECT_TRACE] App.injectHandler — handleSubmit unavailable, acking failed',
          { pairId, directiveId });
        if (directiveId) {
          try {
            sendBridgeEvent('pair_directive_ack', JSON.stringify({
              pairId, directiveId, status: 'failed',
            }));
          } catch { /* best-effort */ }
        }
        return;
      }

      // Ack 1/2: directive received. Java's DirectiveTracker cancels the
      // fast received-timeout (3s) immediately — even if the prompt then
      // queues for a busy main AI, we no longer trigger a retry-storm.
      if (directiveId) {
        try {
          sendBridgeEvent('pair_directive_ack', JSON.stringify({
            pairId, directiveId, status: 'received',
          }));
        } catch (err) {
          console.warn('[INJECT_TRACE] App.injectHandler received-ack send failed', err);
        }
        pendingDirectiveRef.current = { pairId, directiveId };
      }

      // Queue-aware send: handleSubmit checks `loading` and routes to the
      // useMessageQueue dequeue path if main AI is busy. This replaces the
      // legacy `executeMessage(prompt, [])` call which could trigger two
      // concurrent executeTurn() runs against the same daemon runtime.
      try {
        handleSubmit(prompt, []);
      } catch (err) {
        console.error('[INJECT_TRACE] App.injectHandler handleSubmit threw',
          { pairId, directiveId, err: String(err) });
        if (directiveId) {
          try {
            sendBridgeEvent('pair_directive_ack', JSON.stringify({
              pairId, directiveId, status: 'failed',
            }));
          } catch { /* best-effort */ }
          pendingDirectiveRef.current = null;
        }
      }
    });
  }, [registerInjectPromptHandler, handleSubmit, loading]);

  // Ack 2/2: when loading flips from true → false AND we have a pending
  // directive, the main AI finished the turn that consumed it. Send "applied"
  // so the supervisor can mark the step done. We use a transition check so
  // unrelated loading=false snapshots (no inject_prompt in flight) are ignored.
  const prevLoadingRef = useRef<boolean>(loading);
  useEffect(() => {
    const prev = prevLoadingRef.current;
    prevLoadingRef.current = loading;
    if (prev && !loading) {
      const pending = pendingDirectiveRef.current;
      if (pending) {
        // 2026-05-24 (Q4 trace): final ack — main AI consumed the prompt and
        // produced a turn. Paired with Java's `DirectiveTracker.ack` log.
        console.info('[INJECT_TRACE] App.applied (loading: true→false)',
          { pairId: pending.pairId, directiveId: pending.directiveId });
        sendBridgeEvent('pair_directive_ack', JSON.stringify({
          pairId: pending.pairId, directiveId: pending.directiveId, status: 'applied',
        }));
        pendingDirectiveRef.current = null;
      }
    }
  }, [loading]);

  return null;
};

const App = () => {
  const { t } = useTranslation();

  // ── Dialog management ──
  const {
    permissionDialogOpen, currentPermissionRequest, openPermissionDialog,
    handlePermissionApprove, handlePermissionApproveAlways, handlePermissionSkip,
    askUserQuestionDialogOpen, currentAskUserQuestionRequest, openAskUserQuestionDialog,
    handleAskUserQuestionSubmit, handleAskUserQuestionCancel,
    planApprovalDialogOpen, currentPlanApprovalRequest, openPlanApprovalDialog,
    handlePlanApprovalApprove, handlePlanApprovalReject,
    rewindDialogOpen, setRewindDialogOpen, currentRewindRequest, setCurrentRewindRequest,
    isRewinding, setIsRewinding, rewindSelectDialogOpen, setRewindSelectDialogOpen,
  } = useDialogManagement({ t });

  // ── Core state (shared across multiple hooks) ──
  const [messages, setMessages] = useState<ClaudeMessage[]>([]);
  const [_status, setStatus] = useState(DEFAULT_STATUS);
  const [loading, setLoading] = useState(false);
  const [loadingStartTime, setLoadingStartTime] = useState<number | null>(null);
  const [isThinking, setIsThinking] = useState(false);
  const [streamingActive, setStreamingActive] = useState(false);
  // Effort tier snapshot for the in-flight turn (held until loading ends).
  const [turnEffort, setTurnEffort] = useState<ReasoningEffort | null>(null);
  const [currentView, setCurrentView] = useState<ViewMode>('chat');
  const [settingsInitialTab, setSettingsInitialTab] = useState<SettingsTab | undefined>(undefined);
  const [historyData, setHistoryData] = useState<HistoryData | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [addModelDialogOpen, setAddModelDialogOpen] = useState(false);
  // Session-kind refactor: "new supervised session" picker visibility.
  const [showNewSupervisedDialog, setShowNewSupervisedDialog] = useState(false);
  const isFirstMountRef = useRef(true);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [customSessionTitle, setCustomSessionTitle] = useState<string | null>(null);
  const chatInputRef = useRef<ChatInputBoxHandle>(null);
  const [draftInput, setDraftInput] = useState('');

  // StatusPanel collapse state
  const userCollapsedRef = useRef(false);
  const [, forceStatusUpdate] = useState(0);

  // Changelog dialog state (show once per version update)
  const LAST_SEEN_VERSION_KEY = 'lastSeenChangelogVersion';
  const [showChangelogDialog, setShowChangelogDialog] = useState(() => {
    const lastSeen = localStorage.getItem(LAST_SEEN_VERSION_KEY);
    return lastSeen !== APP_VERSION;
  });
  const handleCloseChangelog = useCallback(() => {
    localStorage.setItem(LAST_SEEN_VERSION_KEY, APP_VERSION);
    setShowChangelogDialog(false);
  }, []);

  // Context state (active file and selection)
  const [contextInfo, setContextInfo] = useState<ContextInfo | null>(null);

  // Refs for stale closure prevention
  const currentSessionIdRef = useRef(currentSessionId);
  useEffect(() => { currentSessionIdRef.current = currentSessionId; }, [currentSessionId]);
  const customSessionTitleRef = useRef(customSessionTitle);
  useEffect(() => { customSessionTitleRef.current = customSessionTitle; }, [customSessionTitle]);

  // Message anchor node registry for anchor rail navigation
  const messageNodeMapRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const [anchorCollapsedCount, setAnchorCollapsedCount] = useState(0);
  const handleMessageNodeRef = useCallback((id: string, node: HTMLDivElement | null) => {
    if (node) { messageNodeMapRef.current.set(id, node); }
    else { messageNodeMapRef.current.delete(id); }
  }, []);

  // ── Theme & context actions ──
  useThemeInit();
  useContextActions();

  // Apply diff theme on app startup so diff styles work before opening Settings.
  useEffect(() => {
    const ideTheme = window.__INITIAL_IDE_THEME__ ?? null;
    applyDiffTheme(getStoredDiffTheme(), ideTheme);
  }, []);

  // ── Scroll behavior ──
  const {
    messagesContainerRef, messagesEndRef, inputAreaRef,
    isUserAtBottomRef, userPausedRef,
  } = useScrollBehavior({ currentView, messages, loading, streamingActive });

  // ── Streaming messages ──
  const {
    streamingContentRef, isStreamingRef, useBackendStreamingRenderRef,
    streamingMessageIndexRef, streamingTextSegmentsRef, activeTextSegmentIndexRef,
    streamingThinkingSegmentsRef, activeThinkingSegmentIndexRef,
    seenToolUseCountRef, contentUpdateTimeoutRef, thinkingUpdateTimeoutRef,
    lastContentUpdateRef, lastThinkingUpdateRef, autoExpandedThinkingKeysRef,
    streamingTurnIdRef, turnIdCounterRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
  } = useStreamingMessages();

  // ── Toast helpers ──
  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    if (message === DEFAULT_STATUS || !message) return;
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);
  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);
  const clearToasts = useCallback(() => { setToasts([]); }, []);

  // ── Model/Provider state ──
  const {
    currentProvider, selectedModel, permissionMode,
    selectedAgent, sdkStatusLoaded, currentSdkInstalled,
    currentProviderRef,
    activeProviderConfig, claudeSettingsAlwaysThinkingEnabled,
    reasoningEffort, streamingEnabledSetting, sendShortcut, autoOpenFileEnabled,
    longContextEnabled,
    usagePercentage, usageUsedTokens, usageMaxTokens, usageOutputTokens,
    setPermissionMode,
    setClaudePermissionMode, setCodexPermissionMode,
    setSelectedClaudeModel, setSelectedCodexModel,
    setProviderConfigVersion, setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled, setStreamingEnabledSetting,
    setSendShortcut, setAutoOpenFileEnabled,
    setSdkStatus, setSdkStatusLoaded, setSelectedAgent,
    setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens, setUsageOutputTokens,
    syncActiveProviderModelMapping,
    handleModeSelect, handleModelSelect, handleProviderSelect,
    handleReasoningChange, handleAgentSelect, handleToggleThinking,
    handleStreamingEnabledChange, handleSendShortcutChange,
    handleAutoOpenFileEnabledChange, handleLongContextChange,
  } = useModelProviderState({ addToast, t });

  // 缺陷「AI 分析」现在跑在独立分离窗口(BugAnalysisFrame + BugAnalysisStandalone),
  // 不再在主 App 里持有分析状态;主 App 只负责在批量时发 open_bug_analysis_window。

  // ── Global drag event interception ──
  useEffect(() => {
    const prevent = (e: DragEvent) => { e.preventDefault(); e.stopPropagation(); };
    document.addEventListener('dragover', prevent);
    document.addEventListener('drop', prevent);
    document.addEventListener('dragenter', prevent);
    return () => {
      document.removeEventListener('dragover', prevent);
      document.removeEventListener('drop', prevent);
      document.removeEventListener('dragenter', prevent);
    };
  }, []);

  // ── Slash command preloading ──
  useEffect(() => {
    preloadSlashCommands();
    forceRefreshPrompts();
    const retryTimer = setTimeout(() => { forceRefreshPrompts(); }, 1000);
    return () => clearTimeout(retryTimer);
  }, []);

  useEffect(() => {
    if (isFirstMountRef.current) { isFirstMountRef.current = false; return; }
    if (currentView === 'chat') { forceRefreshPrompts(); }
  }, [currentView]);

  // ── Session management ──
  const {
    showNewSessionConfirm, showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession, forceCreateNewSession, beginSupervisedSession,
    handleConfirmNewSession, handleCancelNewSession,
    handleConfirmInterrupt, handleCancelInterrupt,
    loadHistorySession, openHistoryInNewTab, deleteHistorySession, exportHistorySession,
    toggleFavoriteSession, updateHistoryTitle,
  } = useSessionManagement({
    messages, loading, historyData, currentSessionId,
    setHistoryData, setMessages, setCurrentView, setCurrentSessionId,
    setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    setStatus, setLoading, setIsThinking, setStreamingActive,
    clearToasts, addToast, t,
  });

  useHistoryLoader({ currentView, currentProvider });

  // ── Window callbacks (bridge communication) ──
  useWindowCallbacks({
    t, addToast, clearToasts,
    setMessages, setStatus, setLoading, setLoadingStartTime,
    setIsThinking, setStreamingActive, setHistoryData,
    setCurrentSessionId, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens, setUsageOutputTokens,
    setPermissionMode, setClaudePermissionMode, setCodexPermissionMode,
    setSelectedClaudeModel, setSelectedCodexModel,
    setProviderConfigVersion, setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled, setStreamingEnabledSetting,
    setSendShortcut, setAutoOpenFileEnabled,
    setSdkStatus, setSdkStatusLoaded,
    setIsRewinding, setRewindDialogOpen, setCurrentRewindRequest,
    setContextInfo, setSelectedAgent,
    currentProviderRef, messagesContainerRef, isUserAtBottomRef, userPausedRef,
    suppressNextStatusToastRef,
    streamingContentRef, isStreamingRef, useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingTextSegmentsRef, activeTextSegmentIndexRef,
    streamingThinkingSegmentsRef, activeThinkingSegmentIndexRef,
    seenToolUseCountRef, streamingMessageIndexRef,
    streamingTurnIdRef, turnIdCounterRef,
    lastContentUpdateRef, contentUpdateTimeoutRef,
    lastThinkingUpdateRef, thinkingUpdateTimeoutRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    syncActiveProviderModelMapping,
    handleLongContextChange,
    openPermissionDialog, openAskUserQuestionDialog, openPlanApprovalDialog,
    customSessionTitleRef, currentSessionIdRef, updateHistoryTitle,
  });

  // ── Message processing ──
  const {
    getMessageText, getContentBlocks,
    mergedMessages, sentAttachmentsRef,
  } = useMessageProcessing({ messages, currentSessionId, t });

  // Find tool result (stable ref to avoid re-renders)
  const messagesRef = useRef(messages);
  messagesRef.current = messages;
  const findToolResult = useCallback((toolUseId?: string, messageIndex?: number): ToolResultBlock | null => {
    if (!toolUseId || typeof messageIndex !== 'number') return null;
    const currentMessages = messagesRef.current;
    for (let i = 0; i < currentMessages.length; i += 1) {
      const candidate = currentMessages[i];
      const raw = candidate.raw;
      if (!raw || typeof raw === 'string') continue;
      const content = raw.content ?? raw.message?.content;
      if (!Array.isArray(content)) continue;
      const resultBlock = content.find(
        (block): block is ToolResultBlock =>
          Boolean(block) && block.type === 'tool_result' && block.tool_use_id === toolUseId,
      );
      if (resultBlock) return resultBlock;
    }
    return null;
  }, []);

  // ── Message sender ──
  // Wrap handleProviderSelect to also clear messages and input (like creating a new session)
  const wrappedHandleProviderSelect = useCallback((providerId: string) => {
    setMessages([]);
    chatInputRef.current?.clear();
    handleProviderSelect(providerId);
  }, [handleProviderSelect]);

  const {
    handleSubmit: hookHandleSubmit,
    executeMessage,
    interruptSession,
  } = useMessageSender({
    t, addToast,
    currentProvider, permissionMode, selectedAgent,
    sdkStatusLoaded, currentSdkInstalled,
    sentAttachmentsRef, chatInputRef, messagesContainerRef,
    isUserAtBottomRef, userPausedRef, isStreamingRef,
    setMessages, setLoading, setLoadingStartTime, setStreamingActive,
    setSettingsInitialTab, setCurrentView,
    forceCreateNewSession,
    handleModeSelect,
  });

  // ── Message queue ──
  const {
    queue: messageQueue,
    enqueue: enqueueMessage,
    dequeue: dequeueMessage,
  } = useMessageQueue({ isLoading: loading, onExecute: executeMessage });

  // handleSubmit with queue support (new session and local commands bypass loading check)
  const handleSubmit = useCallback((content: string, attachments?: Attachment[]) => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;
    if (!text && !hasAttachments) return;
    // Local commands work even while loading
    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      // New session commands
      if (NEW_SESSION_COMMANDS.has(command)) {
        forceCreateNewSession();
        return;
      }
      // /resume - open history view
      if (RESUME_COMMANDS.has(command)) {
        setCurrentView('history');
        return;
      }
      // /plan - switch to plan mode
      if (PLAN_COMMANDS.has(command)) {
        if (currentProvider === 'codex') {
          addToast(t('chat.planModeNotAvailableForCodex', { defaultValue: 'Plan mode is not available for Codex provider' }), 'warning');
        } else {
          handleModeSelect('plan');
          addToast(t('chat.planModeEnabled', { defaultValue: 'Plan mode enabled' }), 'info');
        }
        return;
      }
    }
    // If loading, add to queue
    if (loading) {
      // 2026-05-24 (Q4 trace): handleSubmit routes to queue branch. The eventual
      // dequeue is in `useMessageQueue` — paired log there.
      console.info('[INJECT_TRACE] App.handleSubmit → queue (loading=true)',
        { contentPreview: text.slice(0, 80), contentLen: text.length });
      enqueueMessage(content, attachments);
      return;
    }
    // 2026-05-24 (Q4 trace): handleSubmit executes immediately.
    console.info('[INJECT_TRACE] App.handleSubmit → execute',
      { contentPreview: text.slice(0, 80), contentLen: text.length });
    // Snapshot the current reasoning effort so the WaitingIndicator reflects the
    // effort actually in use for this turn (won't drift if user changes selector mid-turn).
    setTurnEffort(reasoningEffort);
    hookHandleSubmit(content, attachments);
  }, [loading, enqueueMessage, hookHandleSubmit, forceCreateNewSession, currentProvider, handleModeSelect, setCurrentView, addToast, t, reasoningEffort]);

  // Clear effort snapshot + live output-token count when the turn ends (loading
  // goes false). Resetting the output count here means the next turn starts from
  // a clean slate (the WaitingIndicator shows no stale "↓ N" during the new
  // turn's initial thinking, before its first [USAGE] arrives).
  useEffect(() => {
    if (!loading) {
      if (turnEffort !== null) setTurnEffort(null);
      if (usageOutputTokens !== undefined) setUsageOutputTokens(undefined);
    }
  }, [loading, turnEffort, usageOutputTokens, setUsageOutputTokens]);

  // Bridge callback: a freshly-created "新监督者标签页" tab is born supervised.
  // Java pushes this once on frontend_ready so the new tab auto-opens the
  // supervisor agent picker (born-at-birth — no in-place normal→supervised swap).
  useEffect(() => {
    window.onRequestNewSupervised = () => setShowNewSupervisedDialog(true);
    return () => {
      delete window.onRequestNewSupervised;
    };
  }, []);

  // Bridge callback: a normal tab created with prefill (云效「建会话」) is told, once
  // its webview is ready, what to seed the composer with (unsent). We set the draft
  // input and focus so the user can review/edit before sending.
  useEffect(() => {
    window.onRequestComposerPrefill = (text: string) => {
      if (typeof text !== 'string' || !text) return;
      // ChatInputBox is uncontrolled — useControlledValueSync skips writing the
      // `value` prop into the editor while it's focused/composing, so a plain
      // setDraftInput won't show after mount. Write straight to the editor via the
      // imperative handle (bypasses that guard), and also keep parent state in sync.
      setDraftInput(text);
      const apply = () => {
        if (chatInputRef.current) {
          chatInputRef.current.setValue(text);
          chatInputRef.current.focus();
        } else {
          // ref not attached yet on this fresh tab — retry next frame
          requestAnimationFrame(apply);
        }
      };
      requestAnimationFrame(apply);
    };
    return () => {
      delete window.onRequestComposerPrefill;
    };
  }, []);

  // Bridge callback: a tab opened via `open_history_in_new_tab` is told, once its
  // webview is ready, which session to load. We run the in-place loadHistorySession
  // here so the session loads into THIS (fresh) tab — normal or supervised alike.
  useEffect(() => {
    window.onRequestLoadHistory = (json: string) => {
      try {
        const o = JSON.parse(json) as {
          sessionId?: string;
          containerId?: string;
          kind?: HistoryKind;
          title?: string;
        };
        if (!o.sessionId && !o.containerId) return;
        loadHistorySession(o.sessionId ?? '', {
          containerId: o.containerId,
          kind: o.kind ?? 'normal',
          title: o.title,
        });
      } catch {
        /* ignore malformed */
      }
    };
    return () => {
      delete window.onRequestLoadHistory;
    };
  }, [loadHistorySession]);

  // Bridge callback: server echoes the effort tier it actually applied to the SDK
  // (parsed from the daemon's "[REASONING_EFFORT] ✓ ... applied options.effort=xxx" log).
  // This overrides our UI-snapshot so the WaitingIndicator shows the authoritative value.
  useEffect(() => {
    const VALID: ReasoningEffort[] = ['low', 'medium', 'high', 'xhigh', 'max'];
    window.onReasoningEffortApplied = (effort: string) => {
      const normalized = (typeof effort === 'string' ? effort.trim() : '') as ReasoningEffort;
      if (VALID.includes(normalized)) {
        setTurnEffort(normalized);
      }
    };
    return () => {
      delete window.onReasoningEffortApplied;
    };
  }, []);

  // Live output-token count for the in-flight turn. Prefer the streaming [USAGE]
  // value (usageOutputTokens) — it ticks during the thinking phase too, matching
  // the CLI. Fall back to scanning the latest assistant message's raw usage for
  // non-streaming mode (or before the first [USAGE] of the turn lands).
  const turnOutputTokens = useMemo<number | undefined>(() => {
    if (!loading) return undefined;
    if (typeof usageOutputTokens === 'number' && usageOutputTokens > 0) {
      return usageOutputTokens;
    }
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const m = messages[i];
      if (!m || m.type !== 'assistant') continue;
      const raw = m.raw;
      if (!raw || typeof raw === 'string') continue;
      const usage = (raw as { message?: { usage?: { output_tokens?: number } } }).message?.usage;
      if (typeof usage?.output_tokens === 'number') return usage.output_tokens;
    }
    return undefined;
  }, [loading, usageOutputTokens, messages]);

  // ── File changes management ──
  const {
    processedFiles, baseMessageIndex,
    handleUndoFile, handleDiscardAll: handleDiscardAllRaw, handleKeepAll,
  } = useFileChangesManagement({
    currentSessionId, currentSessionIdRef, messages,
    getContentBlocks, findToolResult,
  });

  const fileChanges = useFileChanges({
    messages, getContentBlocks, findToolResult,
    startFromIndex: baseMessageIndex,
  });

  const filteredFileChanges = useMemo(() => {
    if (processedFiles.length === 0) return fileChanges;
    return fileChanges.filter(fc => !processedFiles.includes(fc.filePath));
  }, [fileChanges, processedFiles]);

  const onDiscardAll = useCallback(() => {
    handleDiscardAllRaw(filteredFileChanges);
  }, [handleDiscardAllRaw, filteredFileChanges]);

  const latestTurnMessages = useMemo(() => sliceLatestConversationTurn(messages), [messages]);

  // ── Subagents ──
  const latestTurnSubagents = useSubagents({ messages: latestTurnMessages, getContentBlocks, findToolResult });
  // Live ultracode Workflow / background tasks surfaced from the SDK task_* events
  // (not tool calls in the message list). Merged ahead of message-derived Task
  // subagents so a running workflow is visible with live progress in the 子代理 tab.
  const workflowSubagents = useWorkflowTasks({ streamingActive, sessionId: currentSessionId });
  const subagents = useMemo(
    () => [...workflowSubagents, ...finalizeSubagentsForSettledTurn(latestTurnSubagents, streamingActive)],
    [workflowSubagents, latestTurnSubagents, streamingActive],
  );

  // ── Rewind handlers ──
  const {
    handleRewindConfirm, handleRewindCancel,
    handleOpenRewindSelectDialog, handleRewindSelect, handleRewindSelectCancel,
  } = useRewindHandlers({
    t, addToast, currentSessionId, mergedMessages, getMessageText,
    setCurrentRewindRequest, setRewindDialogOpen, setRewindSelectDialogOpen,
    setIsRewinding, isRewinding,
  });

  // ── Computed values ──

  // Extract todos from the latest turn only so the status panel reflects the
  // current/most-recent task, instead of accumulating historical plans forever.
  const globalTodos = useMemo(() => {
    let latestTodos: ReturnType<typeof extractTodosFromToolUse> = null;
    for (let i = latestTurnMessages.length - 1; i >= 0; i--) {
      const msg = latestTurnMessages[i];
      if (msg.type !== 'assistant') continue;
      const blocks = getContentBlocks(msg);
      for (let j = blocks.length - 1; j >= 0; j--) {
        const todos = extractTodosFromToolUse(blocks[j]);
        if (todos && todos.length > 0) {
          latestTodos = todos;
          break;
        }
      }
      if (latestTodos) {
        break;
      }
    }
    return finalizeTodosForSettledTurn(latestTodos ?? [], streamingActive);
  }, [latestTurnMessages, getContentBlocks, streamingActive]);

  // Plain-task fallback for the 任务 tab: when there is no TodoWrite plan, show
  // the current turn's ordinary tool steps (Read/Bash/Edit/…) with live status,
  // so tasks that use neither a plan, a subagent, nor a workflow still show
  // progress + breakdown instead of "暂无任务运行".
  const taskSteps = useTaskSteps({ messages: latestTurnMessages, getContentBlocks, findToolResult });
  const effectiveTodos = useMemo(
    () => (globalTodos.length > 0 ? globalTodos : finalizeTodosForSettledTurn(taskSteps, streamingActive)),
    [globalTodos, taskSteps, streamingActive],
  );

  const canRewindFromMessageIndex = (userMessageIndex: number) => {
    if (userMessageIndex < 0 || userMessageIndex >= mergedMessages.length) return false;
    const current = mergedMessages[userMessageIndex];
    if (current.type !== 'user') return false;
    if ((current.content || '').trim() === '[tool_result]') return false;
    const raw = current.raw;
    if (raw && typeof raw !== 'string') {
      const content = (raw as any).content ?? (raw as any).message?.content;
      if (Array.isArray(content) && content.some((block: any) => block && block.type === 'tool_result')) {
        return false;
      }
    }
    for (let i = userMessageIndex + 1; i < mergedMessages.length; i += 1) {
      const msg = mergedMessages[i];
      if (msg.type === 'user') break;
      const blocks = getContentBlocks(msg);
      for (const block of blocks) {
        if (block.type !== 'tool_use') continue;
        if (isToolName(block.name, FILE_MODIFY_TOOL_NAMES)) return true;
      }
    }
    return false;
  };

  const rewindableMessages = useMemo((): RewindableMessage[] => {
    if (currentProvider !== 'claude') return [];
    const result: RewindableMessage[] = [];
    for (let i = 0; i < mergedMessages.length - 1; i++) {
      if (!canRewindFromMessageIndex(i)) continue;
      const message = mergedMessages[i];
      const content = message.content || getMessageText(message);
      const timestamp = message.timestamp ? formatTime(message.timestamp) : undefined;
      const messagesAfterCount = mergedMessages.length - i - 1;
      result.push({ messageIndex: i, message, displayContent: content, timestamp, messagesAfterCount });
    }
    return result;
  }, [mergedMessages, currentProvider]);

  const statusPanelExpanded = !userCollapsedRef.current;

  const sessionTitle = useMemo(() => {
    if (customSessionTitle) return customSessionTitle;
    if (messages.length === 0) return t('common.newSession');
    const firstUserMessage = messages.find((message) => message.type === 'user');
    if (!firstUserMessage) return t('common.newSession');
    const text = getMessageText(firstUserMessage);
    return text.length > 15 ? `${text.substring(0, 15)}...` : text;
  }, [customSessionTitle, messages, t, getMessageText]);

  // ── Render ──
  return (
    <WorkflowProvider addToast={addToast}>
    <PairProvider>
      <PairAppBridge
        setSettingsInitialTab={setSettingsInitialTab}
        setCurrentView={setCurrentView}
        handleSubmit={handleSubmit}
        loading={loading}
      />
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
      <ChatHeader
        currentView={currentView}
        sessionTitle={sessionTitle}
        t={t}
        onBack={() => setCurrentView('chat')}
        onNewSession={createNewSession}
        onNewTab={() => sendBridgeEvent('create_new_tab')}
        onHistory={() => setCurrentView('history')}
        onSettings={() => {
          setSettingsInitialTab(undefined);
          setCurrentView('settings');
        }}
        titleEditable
        onTitleChange={(newTitle) => {
          setCustomSessionTitle(newTitle);
          if (currentSessionId) {
            updateHistoryTitle(currentSessionId, newTitle);
          }
        }}
        onOpenWorkflow={() => setCurrentView('workflow')}
        onBugList={() => setCurrentView('bug-list')}
        onNewSupervised={() => sendBridgeEvent('create_new_supervised_tab')}
        hasMessages={messages.length > 0}
      />

      {/* Supervisor workflow orchestration: non-modal run strip + escalation toast.
          The strip is hidden on the workflow page itself (which has its own header). */}
      {currentView !== 'workflow' && <RunStatusBar onExpand={() => setCurrentView('workflow')} />}
      <EscalationToast />

      {currentView === 'settings' ? (
        <SettingsView
          onClose={() => setCurrentView('chat')}
          initialTab={settingsInitialTab}
          currentProvider={currentProvider}
          streamingEnabled={streamingEnabledSetting}
          onStreamingEnabledChange={handleStreamingEnabledChange}
          sendShortcut={sendShortcut}
          onSendShortcutChange={handleSendShortcutChange}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
        />
      ) : currentView === 'chat' ? (
        <PairLayout>
          <div className="messages-shell">
            <MessageAnchorRail
              messages={mergedMessages}
              collapsedCount={anchorCollapsedCount}
              containerRef={messagesContainerRef}
              messageNodeMap={messageNodeMapRef}
            />
            <div className="messages-container" ref={messagesContainerRef}>
              {messages.length === 0 && (
                <WelcomeScreen
                  currentProvider={currentProvider}
                  currentModelId={selectedModel}
                  t={t}
                  onProviderChange={wrappedHandleProviderSelect}
                  onVersionClick={() => setShowChangelogDialog(true)}
                />
              )}

              <MessageList
                messages={mergedMessages}
                streamingActive={streamingActive}
                isThinking={isThinking}
                loading={loading}
                loadingStartTime={loadingStartTime}
                turnEffort={turnEffort}
                turnOutputTokens={turnOutputTokens}
                t={t}
                getMessageText={getMessageText}
                getContentBlocks={getContentBlocks}
                findToolResult={findToolResult}
                extractMarkdownContent={extractMarkdownContent}
                messagesEndRef={messagesEndRef}
                onMessageNodeRef={handleMessageNodeRef}
                onCollapsedCountChange={setAnchorCollapsedCount}
                onNavigateToProviderSettings={() => {
                  setSettingsInitialTab('providers');
                  setCurrentView('settings');
                }}
              />
            </div>
          </div>

          {/* Scroll control button */}
          <ScrollControl containerRef={messagesContainerRef} inputAreaRef={inputAreaRef} />

          <SyncStatusBar />
          <StatusPanelErrorBoundary>
            <StatusPanel
              todos={effectiveTodos}
              fileChanges={filteredFileChanges}
              subagents={subagents}
              expanded={statusPanelExpanded}
              isStreaming={streamingActive}
              onUndoFile={handleUndoFile}
              onDiscardAll={onDiscardAll}
              onKeepAll={handleKeepAll}
            />
          </StatusPanelErrorBoundary>
          <div className="input-area" ref={inputAreaRef}>
            <ChatInputBox
              ref={chatInputRef}
              isLoading={loading}
              selectedModel={selectedModel}
              permissionMode={permissionMode}
              currentProvider={currentProvider}
              usagePercentage={usagePercentage}
              usageUsedTokens={usageUsedTokens}
              usageMaxTokens={usageMaxTokens}
              showUsage={true}
              alwaysThinkingEnabled={activeProviderConfig?.settingsConfig?.alwaysThinkingEnabled ?? claudeSettingsAlwaysThinkingEnabled}
              placeholder={sendShortcut === 'cmdEnter' ? t('chat.inputPlaceholderCmdEnter') : t('chat.inputPlaceholderEnter')}
              sdkInstalled={currentSdkInstalled}
              sdkStatusLoading={!sdkStatusLoaded}
              onInstallSdk={() => {
                setSettingsInitialTab('dependencies');
                setCurrentView('settings');
              }}
              value={draftInput}
              onInput={setDraftInput}
              onSubmit={handleSubmit}
              onStop={interruptSession}
              onModeSelect={handleModeSelect}
              onModelSelect={handleModelSelect}
              onProviderSelect={wrappedHandleProviderSelect}
              reasoningEffort={reasoningEffort}
              onReasoningChange={handleReasoningChange}
              onToggleThinking={handleToggleThinking}
              streamingEnabled={streamingEnabledSetting}
              onStreamingEnabledChange={handleStreamingEnabledChange}
              sendShortcut={sendShortcut}
              selectedAgent={selectedAgent}
              onAgentSelect={handleAgentSelect}
              activeFile={contextInfo?.file}
              selectedLines={contextInfo?.startLine !== undefined && contextInfo?.endLine !== undefined
                ? (contextInfo.startLine === contextInfo.endLine
                    ? `L${contextInfo.startLine}`
                    : `L${contextInfo.startLine}-${contextInfo.endLine}`)
                : undefined}
              onClearContext={() => setContextInfo(null)}
              onOpenAgentSettings={() => {
                setSettingsInitialTab('agents');
                setCurrentView('settings');
              }}
              onOpenPromptSettings={() => {
                setSettingsInitialTab('prompts');
                setCurrentView('settings');
              }}
              onOpenModelSettings={() => {
                setAddModelDialogOpen(true);
              }}
              hasMessages={messages.length > 0}
              onRewind={handleOpenRewindSelectDialog}
              statusPanelExpanded={statusPanelExpanded}
              onToggleStatusPanel={() => { userCollapsedRef.current = !userCollapsedRef.current; forceStatusUpdate(c => c + 1); }}
              addToast={addToast}
              messageQueue={messageQueue}
              onRemoveFromQueue={dequeueMessage}
              autoOpenFileEnabled={autoOpenFileEnabled}
              onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
              longContextEnabled={longContextEnabled}
              onLongContextChange={handleLongContextChange}
            />
          </div>
        </PairLayout>
      ) : currentView === 'workflow' ? (
        <WorkflowView
          onClose={() => setCurrentView('chat')}
          onOpenSupervisorManager={() => {
            setSettingsInitialTab('supervisor');
            setCurrentView('settings');
          }}
        />
      ) : currentView === 'bug-list' ? (
        <BugListView
          onBack={() => setCurrentView('chat')}
          onOpenYunxiaoSettings={() => {
            setSettingsInitialTab('yunxiao');
            setCurrentView('settings');
          }}
          onOpenWorkflow={() => setCurrentView('workflow')}
          model={selectedModel}
          reasoning={reasoningEffort}
        />
      ) : (
        <HistoryView
          historyData={historyData}
          currentProvider={currentProvider}
          onLoadSession={openHistoryInNewTab}
          onDeleteSession={deleteHistorySession}
          onExportSession={exportHistorySession}
          onToggleFavorite={toggleFavoriteSession}
          onUpdateTitle={updateHistoryTitle}
        />
      )}

      {/* Session-kind refactor: born-at-birth supervised-session picker. */}
      <NewSupervisedDialog
        open={showNewSupervisedDialog}
        onClose={() => setShowNewSupervisedDialog(false)}
        onCreated={() => {
          beginSupervisedSession();
          setCurrentView('chat');
        }}
        onOpenManager={() => {
          setSettingsInitialTab('supervisor');
          setCurrentView('settings');
        }}
      />

      <div id="image-preview-root" />

      <AppDialogs
        t={t}
        showNewSessionConfirm={showNewSessionConfirm}
        onConfirmNewSession={handleConfirmNewSession}
        onCancelNewSession={handleCancelNewSession}
        showInterruptConfirm={showInterruptConfirm}
        onConfirmInterrupt={handleConfirmInterrupt}
        onCancelInterrupt={handleCancelInterrupt}
        permissionDialogOpen={permissionDialogOpen}
        currentPermissionRequest={currentPermissionRequest}
        onPermissionApprove={handlePermissionApprove}
        onPermissionSkip={handlePermissionSkip}
        onPermissionApproveAlways={handlePermissionApproveAlways}
        askUserQuestionDialogOpen={askUserQuestionDialogOpen}
        currentAskUserQuestionRequest={currentAskUserQuestionRequest}
        onAskUserQuestionSubmit={handleAskUserQuestionSubmit}
        onAskUserQuestionCancel={handleAskUserQuestionCancel}
        planApprovalDialogOpen={planApprovalDialogOpen}
        currentPlanApprovalRequest={currentPlanApprovalRequest}
        onPlanApprovalApprove={handlePlanApprovalApprove}
        onPlanApprovalReject={handlePlanApprovalReject}
        rewindSelectDialogOpen={rewindSelectDialogOpen}
        rewindableMessages={rewindableMessages}
        onRewindSelect={handleRewindSelect}
        onRewindSelectCancel={handleRewindSelectCancel}
        rewindDialogOpen={rewindDialogOpen}
        currentRewindRequest={currentRewindRequest}
        isRewinding={isRewinding}
        onRewindConfirm={handleRewindConfirm}
        onRewindCancel={handleRewindCancel}
        showChangelogDialog={showChangelogDialog}
        onCloseChangelog={handleCloseChangelog}
        addModelDialogOpen={addModelDialogOpen}
        onCloseAddModel={() => setAddModelDialogOpen(false)}
        currentProvider={currentProvider}
      />
    </PairProvider>
    </WorkflowProvider>
  );
};

export default App;
