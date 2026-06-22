/**
 * Global window interface extensions for IDEA plugin communication
 */
interface Window {
  /**
   * Send message to Java backend
   */
  sendToJava?: (message: string) => void;

  /**
   * Get clipboard file path from Java
   */
  getClipboardFilePath?: () => Promise<string>;

  /**
   * Handle file path(s) dropped from Java (supports batch files)
   */
  handleFilePathFromJava?: (filePathInput: string | string[]) => void;

  /**
   * Update messages from backend
   */
  updateMessages?: (json: string, sequence?: string | number) => void;

  /**
   * Patch a single message UUID without re-sending the full message list.
   */
  patchMessageUuid?: (content: string, uuid: string) => void;

  /**
   * Update status message
   */
  updateStatus?: (text: string) => void;

  /**
   * Show loading indicator
   */
  showLoading?: (value: string | boolean) => void;

  /**
   * Show thinking status
   */
  showThinkingStatus?: (value: string | boolean) => void;

  /**
   * Show conversation summary/compaction notice
   */
  showSummary?: (summary: string) => void;

  /**
   * Set history data
   */
  setHistoryData?: (data: any) => void;

  /**
   * Export session data callback
   */
  onExportSessionData?: (json: string) => void;

  /**
   * Clear all messages
   */
  clearMessages?: () => void;

  /**
   * Add error message
   */
  addErrorMessage?: (message: string) => void;

  /**
   * Add single history message (used for Codex session loading)
   */
  addHistoryMessage?: (message: any) => void;

  /**
   * History load complete callback - invoked when history messages finish loading.
   * Triggers Markdown re-rendering to fix incorrect rendering on first history load.
   */
  historyLoadComplete?: () => void;

  /**
   * Add user message to chat (used for external Quick Fix feature)
   * Immediately shows the user's message in the chat UI before AI response
   */
  addUserMessage?: (content: string) => void;

  /**
   * Set current session ID (for rewind feature)
   */
  setSessionId?: (sessionId: string) => void;

  /**
   * Add toast notification (called from backend)
   */
  addToast?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;

  /**
   * Usage statistics update callback
   */
  onUsageUpdate?: (json: string) => void;

  /**
   * Reasoning effort echo from server — the daemon confirms which effort tier
   * it actually applied to the Claude SDK options.effort field.
   */
  onReasoningEffortApplied?: (effort: string) => void;

  /**
   * Structured Claude API error classification from the bridge layer.
   * Known codes: 'LONG_CONTEXT_NOT_ENTITLED'.
   */
  onClaudeErrorCode?: (code: string) => void;

  /**
   * Mode changed callback
   */
  onModeChanged?: (mode: string) => void;

  /**
   * Mode received callback - backend pushes the permission mode (called during window initialization)
   */
  onModeReceived?: (mode: string) => void;

  /**
   * Model changed callback
   */
  onModelChanged?: (modelId: string) => void;

  /**
   * Model confirmed callback - called after the backend confirms the model was set successfully
   * @param modelId The confirmed model ID
   * @param provider The current provider
   */
  onModelConfirmed?: (modelId: string, provider: string) => void;

  /**
   * Show permission dialog
   */
  showPermissionDialog?: (json: string) => void;

  /**
   * Show AskUserQuestion dialog
   */
  showAskUserQuestionDialog?: (json: string) => void;

  /**
   * Show PlanApproval dialog
   */
  showPlanApprovalDialog?: (json: string) => void;

  /**
   * Add selection info (file and line numbers) - auto-tracked, only updates ContextBar
   */
  addSelectionInfo?: (selectionInfo: string) => void;

  /**
   * Add code snippet to input box - manually triggered, inserts a code snippet tag into the input box
   */
  addCodeSnippet?: (selectionInfo: string) => void;

  /**
   * Insert code snippet at cursor position - registered by ChatInputBox
   */
  insertCodeSnippetAtCursor?: (selectionInfo: string) => void;

  /**
   * Clear selection info
   */
  clearSelectionInfo?: () => void;

  /**
   * File list result callback (for file reference provider)
   */
  onFileListResult?: (json: string) => void;

  /**
   * Update MCP servers list
   */
  updateMcpServers?: (json: string) => void;

  /**
   * Update MCP server connection status
   */
  updateMcpServerStatus?: (json: string) => void;

  /**
   * Update MCP server tools list
   */
  updateMcpServerTools?: (json: string) => void;

  mcpServerToggled?: (json: string) => void;

  /**
   * Update Codex MCP servers list (from ~/.codex/config.toml)
   */
  updateCodexMcpServers?: (json: string) => void;

  /**
   * Update Codex MCP server connection status
   */
  updateCodexMcpServerStatus?: (json: string) => void;

  /**
   * Codex MCP server toggled callback
   */
  codexMcpServerToggled?: (json: string) => void;

  /**
   * Codex MCP server added callback
   */
  codexMcpServerAdded?: (json: string) => void;

  /**
   * Codex MCP server updated callback
   */
  codexMcpServerUpdated?: (json: string) => void;

  /**
   * Codex MCP server deleted callback
   */
  codexMcpServerDeleted?: (json: string) => void;

  /**
   * Update providers list
   */
  updateProviders?: (json: string) => void;

  /**
   * Update active provider
   */
  updateActiveProvider?: (providerId: string) => void;

  updateThinkingEnabled?: (json: string) => void;

  /**
   * Update streaming enabled setting
   */
  updateStreamingEnabled?: (json: string) => void;

  /**
   * Update Codex sandbox mode setting
   */
  updateCodexSandboxMode?: (json: string) => void;

  /**
   * Update send shortcut setting
   */
  updateSendShortcut?: (json: string) => void;

  /**
   * Update auto open file enabled setting
   */
  updateAutoOpenFileEnabled?: (json: string) => void;

  /**
   * Update commit AI prompt configuration
   */
  updateCommitPrompt?: (json: string) => void;

  /**
   * Update sound notification configuration
   */
  updateSoundNotificationConfig?: (json: string) => void;

  /**
   * Update AI commit generation enabled state
   */
  updateCommitGenerationEnabled?: (json: string) => void;

  /**
   * Update status bar widget enabled state
   */
  updateStatusBarWidgetEnabled?: (json: string) => void;

  /**
   * Update current Claude config
   */
  updateCurrentClaudeConfig?: (json: string) => void;

  /**
   * Show error message
   */
  showError?: (message: string) => void;

  /**
   * Show switch success message
   */
  showSwitchSuccess?: (message: string) => void;

  /**
   * Update Node.js path
   */
  updateNodePath?: (path: string) => void;

  /**
   * Update working directory configuration
   */
  updateWorkingDirectory?: (json: string) => void;

  /**
   * Show success message
   */
  showSuccess?: (message: string) => void;

  /**
   * Show success message with i18n key
   */
  showSuccessI18n?: (i18nKey: string) => void;

  /**
   * Update skills list
   */
  updateSkills?: (json: string) => void;

  /**
   * Skill import result callback
   */
  skillImportResult?: (json: string) => void;

  /**
   * Skill delete result callback
   */
  skillDeleteResult?: (json: string) => void;

  /**
   * Skill toggle result callback
   */
  skillToggleResult?: (json: string) => void;

  /**
   * Update usage statistics
   */
  updateUsageStatistics?: (json: string) => void;

  /**
   * Pending usage statistics before component mounts
   */
  __pendingUsageStatistics?: string;

  /**
   * Update slash commands list (from SDK)
   */
  updateSlashCommands?: (json: string) => void;

  /**
   * Update dollar commands list (for $ autocomplete)
   */
  updateDollarCommands?: (json: string) => void;

  /**
   * Pending dollar commands payload before callback registration
   */
  __pendingDollarCommands?: string;

  /**
   * Pending slash commands payload before provider initialization
   */
  __pendingSlashCommands?: string;

  /**
   * Pending session ID before App component mounts (for rewind feature)
   */
  __pendingSessionId?: string;

  /**
   * Apply IDEA editor font configuration (called from Java backend)
   * @param config Font configuration object containing fontFamily, fontSize, lineSpacing, fallbackFonts
   */
  applyIdeaFontConfig?: (config: {
    fontFamily: string;
    fontSize: number;
    lineSpacing: number;
    fallbackFonts?: string[];
  }) => void;

  /**
   * Pending font config before applyIdeaFontConfig is registered
   */
  __pendingFontConfig?: {
    fontFamily: string;
    fontSize: number;
    lineSpacing: number;
    fallbackFonts?: string[];
  };

  /**
   * Apply effective plugin UI font configuration (called from Java backend)
   */
  applyUiFontConfig?: (config: import('./types/uiFontConfig').UiFontConfig | string) => void;

  /**
   * Pending effective UI font config before applyUiFontConfig is registered
   */
  __pendingUiFontConfig?: import('./types/uiFontConfig').UiFontConfig;

  /**
   * Apply IDEA language configuration (called from Java backend)
   * @param config Language configuration object containing language code and IDEA locale
   */
  applyIdeaLanguageConfig?: (config: {
    language: string;
    ideaLocale?: string;
  }) => void;

  /**
   * Pending language config before applyIdeaLanguageConfig is registered
   */
  __pendingLanguageConfig?: {
    language: string;
    ideaLocale?: string;
  };

  /**
   * Update enhanced prompt result (for prompt enhancer feature)
   */
  updateEnhancedPrompt?: (result: string) => void;

  /**
   * Update session title (called when session title changes)
   */
  updateSessionTitle?: (title: string) => void;

  /**
   * Editor font config received callback - receives IDEA editor font configuration
   */
  onEditorFontConfigReceived?: (json: string) => void;

  /**
   * Effective UI font config received callback
   */
  onUiFontConfigReceived?: (json: string) => void;

  /**
   * IDE theme received callback - receives IDE theme configuration
   */
  onIdeThemeReceived?: (json: string) => void;

  /**
   * IDE theme changed callback - invoked when the IDE theme changes
   */
  onIdeThemeChanged?: (json: string) => void;

  /**
   * Update agents list
   */
  updateAgents?: (json: string) => void;

  /**
   * Agent operation result callback
   */
  agentOperationResult?: (json: string) => void;

  /**
   * Agent import preview result callback
   */
  agentImportPreviewResult?: (json: string) => void;

  /**
   * Agent import result callback
   */
  agentImportResult?: (json: string) => void;

  /**
   * Update supervisor agents list
   */
  updateSupervisorAgents?: (json: string) => void;

  /**
   * Supervisor agent operation result callback
   */
  supervisorAgentOperationResult?: (json: string) => void;

  /**
   * Single supervisor agent fetched
   */
  onSupervisorAgentReceived?: (json: string) => void;

  /**
   * Default supervisor agent fetched / changed
   */
  onDefaultSupervisorAgentReceived?: (json: string) => void;
  onDefaultSupervisorAgentChanged?: (json: string) => void;

  // Supervisor workflow orchestration callbacks (Java → JS).
  // See docs/workflow/ui-implementation.md §9.2. Backend lands in a later plan;
  // until then the editor runs off localStorage and these are no-ops.
  onWorkflowDefinitions?: (json: string) => void;      // WorkflowDefinition[]
  onWorkflowStatuses?: (json: string) => void;         // { [wfId]: WorkflowState }
  onWorkflowNodeActivity?: (json: string) => void;     // { [nodeName]: effectiveLastActiveAt(epoch) }
  onWorkflowAutoResume?: (json: string) => void;       // { nodeName, attempt, idleMinutes } — soft self-heal notice
  onWorkflowExecutionUpdate?: (json: string) => void;  // WorkflowExecution (full snapshot)
  onWorkflowEscalation?: (json: string) => void;       // { nodeName, reason }
  onWorkflowOperationResult?: (json: string) => void;  // { success, operation?, error? }
  onWorkflowCapabilities?: (json: string) => void;     // { mode, maxConcurrency }

  // Pair lifecycle callbacks (Phase B):
  onPairStarted?: (json: string) => void;
  onPairStopped?: (json: string) => void;
  onPairOperationError?: (json: string) => void;
  onPairActionEvent?: (json: string) => void;
  onPairInjectPrompt?: (json: string) => void;
  onPairEscalate?: (json: string) => void;
  onPairThinking?: (json: string) => void;
  /**
   * Protocol v2 (2026-05-24): non-blocking alert from supervisor record_alert.
   * Payload: { pairId, supervisorId, severity, category, fallback_choice, ... }.
   * Non-modal — the webview should show a toast / banner and let the user
   * review the decision in the timeline asynchronously. Replaces the legacy
   * {@link onPairEscalate} modal for autonomy-mode C1/C2 decisions.
   */
  onPairAlert?: (json: string) => void;
  /**
   * Phase 2 (2026-05-24): per-pair status panel snapshot. Payload matches
   * PairStatusSnapshot.toJson — see Java side for field schema. Pushed by
   * PairStatusPusher; throttled to ~1s and coalesced server-side, so the
   * handler can render directly without further debouncing.
   */
  onPairStatusUpdate?: (json: string) => void;
  /**
   * Periodic-event notice (e.g. supervisor monitor health-check heartbeat).
   * Non-actionable and intentionally OUT-of-band of the supervisor chat —
   * routed to the PeriodicNoticeStrip so the user can see "monitor is alive,
   * no new events" without interrupting in-flight supervisor thinking.
   * Payload: { ts, kind, message, details? }.
   */
  onPairNotice?: (json: string) => void;
  /**
   * v4 unified pipeline: one raw SDK message streamed by the daemon during a
   * supervisor turn. Payload: { pairId, supervisorId, turnId, message: <SDK msg> }.
   * Each call delivers exactly one assistant/user/system/result frame the
   * supervisor's Claude SDK query produced — same shape as the main AI's
   * messages, so the webview can map content blocks (text, thinking,
   * tool_use, tool_result) into pane entries directly.
   *
   * <p>Supervisor token-usage snapshots do NOT come through here — they share
   * the main-AI {@link onUsageUpdate} channel with {@code scope: "supervisor"}
   * and are re-dispatched as a {@code cc-gui:supervisor-usage} CustomEvent
   * for PairContext to consume.
   */
  onSupervisorMessage?: (json: string) => void;

  /**
   * Batched variant of {@link onSupervisorMessage}. Payload is a JSON array
   * of the same envelope shape as {@link onSupervisorMessage}. Used by the
   * Java-side {@code SupervisorMessageBatcher} to coalesce IPC traffic during
   * a supervisor turn so JCEF's EDT doesn't get saturated and trip the
   * webview-stall watchdog.
   */
  onSupervisorMessageBatch?: (json: string) => void;

  /**
   * Session-resume history load (the supervisor analogue of {@code updateMessages}
   * for the main AI). Payload: {@code { supervisorId, sessionId, frames: [...] }}
   * where {@code frames} are raw transcript envelopes read from the supervisor's
   * SDK {@code .jsonl}. Unlike the live {@link onSupervisorMessageBatch} append
   * path, this is AUTHORITATIVE and idempotent: the handler rebuilds the full
   * historical message list and merges it ahead of any live (post-resume)
   * messages, so a reopened/reloaded supervisor pane re-renders its prior
   * conversation exactly the way the main-AI pane restores from its transcript.
   */
  onSupervisorHistoryLoad?: (json: string) => void;

  /**
   * 2026-05-28: live per-turn output-token estimate for a supervisor, driving
   * the supervisor pane's WaitingIndicator "↓ N tokens" counter while it thinks.
   * Payload: {@code { pairId, supervisorId, turnId, outputTokens }}.
   */
  onSupervisorLiveUsage?: (json: string) => void;

  /**
   * Re-emitted by Java after the webview reloads (e.g. WebviewWatchdog
   * triggered) so the supervisor pane can rebind to a pair that is still
   * alive on the Java/daemon side. Payload mirrors the SelectedSupervisor
   * shape plus pairId, so PairContext can restore selected + pairId in one
   * shot without going back through the picker.
   */
  onPairResume?: (json: string) => void;

  /**
   * Session-kind refactor (containerId model): pushed by Java right after a
   * `session_create_supervised` (or workflow node) container is registered and
   * its pair started. Payload mirrors onPairStarted plus the persistent
   * containerId: { containerId, kind, pairId?, agentId?, agentName?, model?,
   * defaultLongContext?, defaultReasoning? }. PairContext uses it to seed
   * containerId + selected without going through the (removed) runtime toggle.
   */
  onSessionCreated?: (json: string) => void;

  /**
   * Born-at-birth supervisor tab ("新监督者标签页"): pushed once by Java on the
   * first `frontend_ready` of a tab created via `create_new_supervised_tab`, so
   * the fresh tab auto-opens the supervisor agent picker.
   */
  onRequestNewSupervised?: () => void;

  /**
   * Directed variant of {@link onRequestNewSupervised}: pushed once by Java on
   * the first `frontend_ready` of a tab created via `create_new_supervised_tab`
   * WITH a payload. Carries a JSON string `{ agentId, initialComposerText? }` so
   * the fresh tab skips the picker, creates the named supervised session, and
   * prefills (but does not send) the composer draft.
   */
  onRequestNewSupervisedWith?: (json: string) => void;

  /**
   * History-in-new-tab: pushed once by Java on the first `frontend_ready` of a
   * tab created via `open_history_in_new_tab`. Carries JSON `{sessionId,
   * containerId?, kind}` so the fresh tab loads that session in-place.
   */
  onRequestLoadHistory?: (json: string) => void;

  /**
   * Composer prefill: pushed once by Java on the first `frontend_ready` of a
   * normal tab created with prefill text (云效「建会话」). Seeds the input (unsent).
   */
  onRequestComposerPrefill?: (text: string) => void;

  /**
   * Workflow-node main-AI seed: pushed by Java on every `frontend_ready` of a
   * workflow node window. Payload `{ model?, longContextEnabled?, reasoningEffort? }`
   * is the node's configured model + thinking depth — applied to the MAIN AI (left
   * pane) composer so both legs (main AI + supervisor) match the node config.
   */
  onWorkflowNodeMainAi?: (json: string) => void;

  /**
   * Session-kind refactor: supervised-session history list (read from the
   * SessionRegistry manifests, kind=supervised, parents excluded). Payload is
   * the same HistoryData shape as setHistoryData but each session carries
   * { containerId, kind:'supervised', agentId }.
   */
  onSupervisedHistory?: (json: string) => void;

  /**
   * Session-kind refactor: workflow-run history list (kind=workflow containers).
   * Payload is HistoryData-shaped; each session carries
   * { containerId, kind:'workflow', childCount }.
   */
  onWorkflowHistory?: (json: string) => void;

  /**
   * Update prompts list
   */
  updatePrompts?: (json: string) => void;

  /**
   * Update global prompts list
   */
  updateGlobalPrompts?: (json: string) => void;

  /**
   * Update project prompts list
   */
  updateProjectPrompts?: (json: string) => void;

  /**
   * Update project info
   */
  updateProjectInfo?: (json: string) => void;

  /**
   * Prompt operation result callback
   */
  promptOperationResult?: (json: string) => void;

  /**
   * Prompt import preview result callback
   */
  promptImportPreviewResult?: (json: string) => void;

  /**
   * Prompt import result callback
   */
  promptImportResult?: (json: string) => void;

  /**
   * Selected agent received callback - receives the currently selected agent during initialization
   */
  onSelectedAgentReceived?: (json: string) => void;

  /**
   * Selected agent changed callback - invoked after an agent is selected
   */
  onSelectedAgentChanged?: (json: string) => void;

  /**
   * Update Codex providers list
   */
  updateCodexProviders?: (json: string) => void;

  /**
   * Update active Codex provider
   */
  updateActiveCodexProvider?: (json: string) => void;

  /**
   * Update current Codex config (from ~/.codex/)
   */
  updateCurrentCodexConfig?: (json: string) => void;

// ============================================================================
  // Streaming Callbacks
  // ============================================================================

  /**
   * Stream start callback - called when streaming begins
   */
  onStreamStart?: () => void;

  /**
   * Content delta callback - called when a content delta is received
   * @param delta The content delta string
   */
  onContentDelta?: (delta: string) => void;

  /**
   * Thinking delta callback - called when a thinking delta is received
   * @param delta The thinking delta string
   */
  onThinkingDelta?: (delta: string) => void;

  /**
   * Stream end callback - called when streaming ends
   */
  onStreamEnd?: (sequence?: string | number) => void;

  /**
   * Streaming heartbeat callback - lightweight signal from backend during
   * tool execution phases to prevent the stall watchdog from falsely triggering.
   */
  onStreamingHeartbeat?: () => void;

  /**
   * Permission denied callback - called when permission is denied.
   * Marks incomplete tool calls as "interrupted".
   */
  onPermissionDenied?: () => void;

  /**
   * Set of denied tool call IDs.
   * Used by tool blocks to determine which tool calls had their permission denied by the user.
   */
  __deniedToolIds?: Set<string>;

  /**
   * Session transition suppression flag.
   * Set to true during new session creation to prevent stale callbacks from writing old messages via updateMessages.
   */
  __sessionTransitioning?: boolean;

  /**
   * Session transition token (debug/logging only).
   * Regenerated for each logical transition so callbacks can identify the active transition
   * generation in logs. NOT used for guard logic — the boolean __sessionTransitioning flag
   * is the actual guard.
   */
  __sessionTransitionToken?: string | null;

  /**
   * Resets all transient UI state (loading, streaming, toasts, refs) in one shot.
   * Called by beginSessionTransition (useSessionManagement) to synchronously
   * clear both React state AND internal refs before starting a new session.
   */
  __resetTransientUiState?: () => void;

  /**
   * Timestamp of the last streaming activity (content/thinking delta or message update).
   * Used by the stream stall watchdog to detect when the backend→frontend bridge is broken.
   */
  __lastStreamActivityAt?: number;

  /**
   * The __turnId of the most recently ended streaming turn.
   * Used by mergeConsecutiveAssistantMessages to distinguish recently-ended
   * streaming messages from true history messages and prevent incorrect merging.
   * Cleared after 5 seconds or when a new turn starts.
   * @default undefined (no recently ended turn)
   */
  __lastStreamEndedTurnId?: number;

  /**
   * Timestamp when the last streaming turn ended (via onStreamEnd).
   * Used with __lastStreamEndedTurnId to implement a time-based cleanup.
   * @default undefined (no stream end recorded)
   */
   __lastStreamEndedAt?: number;

   /**
    * Turn ID for which onStreamEnd has already been processed.
    * Used as an idempotency guard: when dual-path delivery sends onStreamEnd
    * twice (primary via flush callback + fallback via Alarm), only the first
    * arrival takes effect; the second is a no-op.
    * Cleared in onStreamStart to allow the next turn.
    * @default undefined (no processed turn)
    */
   __streamEndProcessedTurnId?: number;

   /**
   * Timestamp when the current streaming turn started.
   * Used to calculate durationMs on the assistant message when the stream ends.
   */
  __turnStartedAt?: number;

  /**
   * Interval handle for the stream stall watchdog.
   * Stored on window so re-registration of streaming callbacks clears the previous interval.
   */
  __stallWatchdogInterval?: ReturnType<typeof setInterval> | null;

  /**
   * Pending rAF handle and JSON for deferred updateMessages processing.
   * Stored on window so re-registration of message callbacks cancels stale rAFs.
   */
  __pendingUpdateRaf?: number | null;
  __pendingUpdateJson?: string | null;
  __pendingUpdateSequence?: number | null;
  __minAcceptedUpdateSequence?: number;
  /** Cancel pending rAF-deferred updateMessages (set by messageCallbacks, called by onStreamEnd). */
  __cancelPendingUpdateMessages?: () => void;

  /**
   * Rewind result callback - returns the result of a rewind operation
   */
  onRewindResult?: (json: string) => void;

  /**
   * Undo file result callback - returns the result of a single-file undo operation
   */
  onUndoFileResult?: (json: string) => void;

  /**
   * Undo all files result callback - returns the result of a batch undo operation
   */
  onUndoAllFileResult?: (json: string) => void;

  /**
   * Handle remove file from edits list - removes a file from the edits list (called when the user fully reverts changes in the diff view)
   */
  handleRemoveFileFromEdits?: (json: string) => void;

  /**
   * Handle interactive diff result - processes the result of an interactive diff action (Apply/Reject)
   * @param json JSON string containing { filePath, action, content?, error? }
   */
  handleDiffResult?: (json: string) => void;

  // ============================================================================
  // Dependency Management Callbacks
  // ============================================================================

  /**
   * Update dependency status callback
   */
  updateDependencyStatus?: (json: string) => void;

  /**
   * Dependency install progress callback
   */
  dependencyInstallProgress?: (json: string) => void;

  /**
   * Dependency install result callback
   */
  dependencyInstallResult?: (json: string) => void;

  /**
   * Dependency uninstall result callback
   */
  dependencyUninstallResult?: (json: string) => void;

  /**
   * Node environment status callback
   */
  nodeEnvironmentStatus?: (json: string) => void;

  /**
   * Trigger Node environment re-check.
   */
  checkNodeEnvironment?: () => void;

  /**
   * Trigger concurrent Node environment checks for diagnostics.
   */
  runNodeEnvironmentStressTest?: (count?: number) => void;

  /**
   * Dependency update available callback
   */
  dependencyUpdateAvailable?: (json: string) => void;

  /**
   * Dependency versions loaded callback
   */
  dependencyVersionsLoaded?: (json: string) => void;

  /**
   * Pending dependency versions payload before settings initialization
   */
  __pendingDependencyVersions?: string;

  /**
   * Pending dependency updates payload before settings initialization
   */
  __pendingDependencyUpdates?: string;

  /**
   * Pending dependency status payload before React initialization
   */
  __pendingDependencyStatus?: string;

  /**
   * Pending streaming enabled status before React initialization
   */
  __pendingStreamingEnabled?: string;

  /**
   * Pending send shortcut status before React initialization
   */
  __pendingSendShortcut?: string;

  /**
   * Pending auto open file enabled status before React initialization
   */
  __pendingAutoOpenFileEnabled?: string;

  __pendingPermissionDialogRequests?: string[];

  __pendingAskUserQuestionDialogRequests?: string[];

  __pendingPlanApprovalDialogRequests?: string[];

  /**
   * Pending updateMessages payload before React initialization
   */
  __pendingUpdateMessages?: string | { json: string; sequence?: number | null };

  /**
   * Pending status text before React initialization
   */
  __pendingStatusText?: string;

  /**
   * Pending summary text before React initialization
   */
  __pendingSummaryText?: string;

  /**
   * Pending user message before addUserMessage is registered (for Quick Fix feature)
   */
  __pendingUserMessage?: string;

  /**
   * Pending loading state before showLoading is registered (for Quick Fix feature)
   */
  __pendingLoadingState?: boolean;

  /**
   * Execute context action from IDEA shortcut (copy/cut/send)
   */
  execContextAction?: (action: string) => void;

  /**
   * Clipboard read callback for paste from IDEA shortcut
   */
  onClipboardRead?: (text: string) => void;

  /**
   * Yunxiao (Alibaba Cloud DevOps) settings config callback. Payload:
   * { token, organizationId, domain }. Pushed in response to
   * `get_yunxiao_config` / `set_yunxiao_config`.
   */
  updateYunxiaoConfig?: (json: string) => void;

  /**
   * Yunxiao「测试连接」result callback. Payload: { ok, userId? , error? }.
   * Pushed in response to `yunxiao_test_connection`.
   */
  onYunxiaoTestResult?: (json: string) => void;

  /**
   * Yunxiao project-dropdown callback. Payload: { ok, projects?, error? }.
   * Pushed in response to `load_yunxiao_projects`.
   */
  onYunxiaoProjects?: (json: string) => void;

  /**
   * Yunxiao「我的缺陷」page callback. Payload: { ok, page?, hasMore?, bugs?, error? }.
   * Pushed in response to `load_yunxiao_bugs`.
   */
  onYunxiaoBugs?: (json: string) => void;

  /**
   * Yunxiao bug-detail modal callback. Payload: { ok, bugId?, detail?, error? }.
   * Pushed in response to `load_yunxiao_bug_detail`.
   */
  onYunxiaoBugDetail?: (json: string) => void;

  /**
   * Yunxiao attachment download-url callback. Payload: { ok, url?, name?, error? }.
   * Pushed in response to `download_yunxiao_attachment`; the URL is opened in the browser.
   */
  onYunxiaoAttachmentUrl?: (json: string) => void;

  /**
   * Yunxiao status-options callback. Payload: { ok, bugId?, statuses?, error? }.
   * Pushed in response to `load_yunxiao_statuses`.
   */
  onYunxiaoStatuses?: (json: string) => void;

  /**
   * Yunxiao status-update result. Payload: { ok, bugId?, statusId?, statusName?, error? }.
   * Pushed in response to `update_yunxiao_status`.
   */
  onYunxiaoStatusUpdated?: (json: string) => void;

  /** Yunxiao comment-submit result. Payload: { ok, bugId?, error? }. */
  onYunxiaoCommentAdded?: (json: string) => void;

  /** Yunxiao pasted-image upload result. Payload: { ok, markdown?, error? }. */
  onYunxiaoCommentImage?: (json: string) => void;

  /** Yunxiao org members for the comment「@」picker. Payload: { ok, query?, members?: {userId,name}[], error? }. */
  onYunxiaoMembers?: (json: string) => void;

  /** Yunxiao org members for the list 改负责人 picker (separate callback to avoid clobber). */
  onYunxiaoAssigneeMembers?: (json: string) => void;

  /** Yunxiao reassign result. Payload: { ok, bugId?, userId?, name?, error? }. */
  onYunxiaoAssigneeUpdated?: (json: string) => void;

  /**
   * 缺陷「AI 分析」进度回调（多次）。Payload:
   * { projectId, total, doneIds:[], currentId, toolCalls }. 由隔离分析会话推送，
   * useBugAnalysis 据此推进逐 bug 进度（带 projectId 守卫防切项目串台）。
   */
  onBugAnalysisProgress?: (json: string) => void;

  /**
   * 缺陷「AI 分析」终态回调（一次）。成功 Payload:
   * { ok:true, projectId, model, reasoning, result:{bugs,groups} }；
   * 失败 Payload: { ok:false, projectId, raw, error }。落地时由 useBugAnalysis 写盘。
   */
  onBugAnalysisResult?: (json: string) => void;

  /**
   * 缺陷「AI 分析」【实时过程】流（分析中只读直播，类似普通会话思考过程）。
   * Payload: { projectId, kind: 'thinking'|'content'|'tool', text }。仅 running 期间由
   * useBugAnalysis 累积渲染（同类相邻 delta 合并、tool 离散），不持久化（终态/切项目清空）。
   */
  onBugAnalysisStream?: (json: string) => void;
}
