import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SelectedSupervisor,
  SupervisorAgent,
} from '../../types/supervisorAgent';
import type {
  DropdownPosition,
  FileItem,
  ModelInfo,
  ReasoningEffort,
  TriggerQuery,
} from '../ChatInputBox/types';
import { apply1MContextSuffix, strip1MContextSuffix } from '../ChatInputBox/types';
import { ModelSelect } from '../ChatInputBox/selectors/ModelSelect';
import { ReasoningSelect } from '../ChatInputBox/selectors/ReasoningSelect';
import { TokenIndicator } from '../ChatInputBox/TokenIndicator';
import { useCompletionDropdown } from '../ChatInputBox/hooks';
import { fileReferenceProvider, fileToDropdownItem } from '../ChatInputBox/providers';
import { CompletionDropdown } from '../ChatInputBox/Dropdown';
import { MessageQueue } from '../ChatInputBox/MessageQueue';
import { SUPERVISOR_MODELS } from '../settings/SupervisorSection/templates';
import SupervisorAgentSelect from './SupervisorAgentSelect';
import { usePairContext } from './PairContext';
import {
  markChatInputFocused,
  registerChatInputDropHandler,
  registerChatInputFocusHandler,
} from '../../utils/chatInputDropRouter';
import { sendBridgeEvent } from '../../utils/bridge';
import styles from './style.module.less';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

/** Reformatted SUPERVISOR_MODELS as ModelInfo[] for the shared ModelSelect. */
const SUPERVISOR_MODEL_INFOS: ModelInfo[] = SUPERVISOR_MODELS.map((m) => ({
  id: m.id,
  label: m.label,
}));

/**
 * Match an absolute path that follows an `@` reference token, used to extract
 * structured path attachments from the free-form supervisor message body.
 *
 *  - Unix-style:    `@/Users/me/foo/bar.go`
 *  - Windows-style: `@C:\path\to\file` or `@C:/path/to/file`
 *
 * Terminator set is conservative: stops at whitespace or common punctuation
 * (parens / brackets / quotes / backticks / angle brackets), so trailing
 * markdown / code-comment punctuation is not absorbed into the path.
 */
const AT_PATH_PATTERN = /@(\/[^\s)\]}>"'`]+|[a-zA-Z]:[\\/][^\s)\]}>"'`]+)/g;

/**
 * Pull every `@<absolute-path>` reference out of the message body and return
 * the unique paths in document order. The text itself is NOT rewritten here
 * — Java translates the structured `attachments[].path` and substitutes the
 * @-tags in `text` accordingly so the daemon-side Supervisor only ever sees
 * remote paths.
 */
function extractAtPathAttachments(text: string): Array<{ path: string }> {
  const seen = new Set<string>();
  const out: Array<{ path: string }> = [];
  for (const m of text.matchAll(AT_PATH_PATTERN)) {
    const p = m[1]?.trim();
    if (!p) continue;
    if (seen.has(p)) continue;
    seen.add(p);
    out.push({ path: p });
  }
  return out;
}

/**
 * Detect an `@` file-reference trigger in a plain textarea. Mirrors the main
 * AI's contenteditable trigger detection (see `detectAtTrigger` in
 * `useTriggerDetection.ts`), minus the rendered-tag check: the supervisor
 * textarea has no file-tag DOM, just raw text.
 *
 * Walk backwards from the caret; abort on whitespace; the first `@` we hit is
 * the anchor and everything between it and the caret is the search query.
 */
function detectAtTriggerInTextarea(
  text: string,
  cursorPosition: number
): TriggerQuery | null {
  let start = cursorPosition - 1;
  while (start >= 0) {
    const char = text[start];
    if (/\s/.test(char)) return null;
    if (char === '@') {
      const query = text.slice(start + 1, cursorPosition);
      return { trigger: '@', query, start, end: cursorPosition };
    }
    start--;
  }
  return null;
}

interface SupervisorChatInputProps {
  /** The active supervisor this input addresses. */
  supervisor: SelectedSupervisor;
}

/**
 * Right-pane chat input. Visually identical to the main {@code ChatInputBox}:
 * shares the `.chat-input-box` card chrome, `.input-editable-wrapper`,
 * `.selector-button` toolbar, and `.submit-button`.
 *
 * Differences from the main input:
 *  - Plain textarea (no contenteditable, no `/ # ! $` completions).
 *  - Provider is locked to Claude; model list locked to {@link SUPERVISOR_MODELS}.
 *  - Left toolbar leads with an inline {@link SupervisorAgentSelect} for
 *    switching the active supervisor without leaving the pane.
 */
export default function SupervisorChatInput({ supervisor }: SupervisorChatInputProps) {
  const { t } = useTranslation();
  const {
    sendUserInputToSupervisor,
    modelOverrideByAgentId,
    setSupervisorModel,
    reasoningByAgentId,
    setSupervisorReasoning,
    longContextEnabled,
    setLongContextEnabled,
    usageByAgentId,
    startSupervisorPair,
    openManager,
    thinkingByAgentId,
    queueByAgentId,
    enqueueSupervisorMessage,
    dequeueSupervisorMessage,
    pairId,
  } = usePairContext();
  const usage = usageByAgentId[supervisor.agentId];
  // Busy iff the supervisor for THIS pane is mid-turn. Drives both the
  // enqueue branch in handleSubmit and the queue UI's visibility (queue is
  // hidden when length is 0 anyway, so no explicit guard needed).
  const isSupervisorBusy = thinkingByAgentId[supervisor.agentId] ?? false;
  const supervisorQueue = queueByAgentId[supervisor.agentId] ?? [];

  const [draft, setDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /**
   * `@` file-reference completion — same provider + dropdown UI as the main
   * AI, but driven by a plain-text trigger detector since the supervisor
   * composer is a `<textarea>` (no contenteditable / no rendered file tags).
   *
   * On select we splice the chosen path into the draft at the trigger anchor
   * and reposition the caret behind it. The path is still plain text — the
   * existing {@link extractAtPathAttachments} at submit time will lift it into
   * the structured `attachments[]` payload that Java translates to remote.
   */
  const fileCompletion = useCompletionDropdown<FileItem>({
    trigger: '@',
    provider: fileReferenceProvider,
    toDropdownItem: fileToDropdownItem,
    onSelect: (file, query) => {
      if (!query) return;
      const path = file.absolutePath || file.path;
      // Trailing space on files lets the user keep typing after the insert;
      // skip it for directories so they can chain another path segment.
      const replacement = file.type === 'directory' ? `@${path}` : `@${path} `;
      const cursorPos = query.start + replacement.length;

      setDraft((prev) => prev.slice(0, query.start) + replacement + prev.slice(query.end));

      requestAnimationFrame(() => {
        const ta = textareaRef.current;
        if (!ta) return;
        ta.focus();
        ta.setSelectionRange(cursorPos, cursorPos);
      });
    },
  });

  /**
   * Re-evaluate the `@` trigger against the current textarea state and either
   * open/update or close the completion dropdown. Reads the value/caret
   * directly off the DOM so it works after both controlled state writes and
   * native cursor moves (click, arrow keys) without waiting for React to
   * commit.
   */
  const detectFileTrigger = useCallback(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    const text = ta.value;
    const cursorPos = ta.selectionStart;
    const trigger = detectAtTriggerInTextarea(text, cursorPos);

    if (trigger) {
      // Anchor the dropdown to the textarea's top edge — the Dropdown
      // component aligns its bottom to `position.top`, so this floats the
      // menu just above the composer. Cheaper and more reliable than
      // mirror-div caret measurement, and the menu still tracks the input
      // the user is typing into.
      const rect = ta.getBoundingClientRect();
      const position: DropdownPosition = {
        top: rect.top,
        left: rect.left,
        width: rect.width,
        height: 0,
      };
      if (!fileCompletion.isOpen) {
        fileCompletion.open(position, trigger);
      }
      fileCompletion.updateQuery(trigger);
    } else if (fileCompletion.isOpen) {
      fileCompletion.close();
    }
  }, [fileCompletion]);

  const handleSubmit = useCallback(() => {
    const text = draft.trim();
    if (!text) return;
    // Pull out @-tagged absolute paths so the Java side can translate them
    // local→remote before forwarding to the daemon Supervisor.
    const attachments = extractAtPathAttachments(text);
    // Mid-turn: queue rather than send. The PairContext effect auto-flushes
    // the head when thinkingByAgentId flips back to false. The daemon's
    // EventCollector would buffer a direct send anyway, but the explicit UI
    // queue lets the user see / cancel pending messages and preserves the
    // typed order across overlapping submits.
    if (isSupervisorBusy) {
      enqueueSupervisorMessage(supervisor.agentId, text, attachments);
    } else {
      sendUserInputToSupervisor(text, attachments);
    }
    setDraft('');
    fileCompletion.close();
    requestAnimationFrame(() => textareaRef.current?.focus());
  }, [
    draft,
    sendUserInputToSupervisor,
    fileCompletion,
    isSupervisorBusy,
    enqueueSupervisorMessage,
    supervisor.agentId,
  ]);

  // Interrupt the supervisor's in-flight turn so the user can pause to add
  // context. Mirrors SupervisorPane's header Stop button — the Java side moves
  // the plan to WAITING (onUserPaused) and the next user_input resumes it. The
  // daemon-side supervisor.interrupt bypasses the command queue so it can settle
  // a turn that is itself holding the queue.
  const handleStop = useCallback(() => {
    if (!pairId) return;
    sendBridgeEvent('pair_supervisor_interrupt', JSON.stringify({ pairId }));
    requestAnimationFrame(() => textareaRef.current?.focus());
  }, [pairId]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // Let the dropdown intercept navigation/select keys (↑↓ Enter Tab Esc)
      // before Enter triggers submit. `handleKeyDown` returns true when it
      // consumed the event and has already called `preventDefault` on it.
      if (fileCompletion.isOpen) {
        const handled = fileCompletion.handleKeyDown(e.nativeEvent);
        if (handled) return;
      }

      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit, fileCompletion]
  );

  const handleDragOver = useCallback((e: React.DragEvent<HTMLElement>) => {
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'copy';
  }, []);

  /**
   * Insert one or more `@<path>` tokens into the draft. Used by:
   *   - {@link handleDrop} for in-browser HTML drops on the textarea
   *   - the chatInputDropRouter for IDE-originated drops dispatched by
   *     Java's WebviewInitializer (the user can drag a file from the
   *     Project Tool Window onto the JBCef component and the router
   *     will hand the path to this input when supervisor was last focused)
   *
   * Caret behaviour: insert at the current selection when the textarea is
   * focused; append at the end otherwise (router path may fire when focus
   * has drifted to the IDE source pane during the drag).
   */
  const insertFilePaths = useCallback((paths: string[]) => {
    const cleaned = paths
      .map((p) => p.replace(/^file:\/\//, '').trim())
      .filter((p) => !!p);
    if (cleaned.length === 0) return;
    const insertion =
      cleaned.map((p) => (p.startsWith('@') ? p : `@${p}`)).join(' ') + ' ';

    const ta = textareaRef.current;
    const isFocused = ta != null && document.activeElement === ta;
    const captureStart = ta && isFocused ? ta.selectionStart : null;
    const captureEnd = ta && isFocused ? ta.selectionEnd : null;

    setDraft((prev) => {
      if (captureStart == null || captureEnd == null) {
        const needsSep = prev.length > 0 && !/\s$/.test(prev);
        return prev + (needsSep ? ' ' : '') + insertion;
      }
      const before = prev.slice(0, captureStart);
      const after = prev.slice(captureEnd);
      return before + insertion + after;
    });

    requestAnimationFrame(() => {
      const node = textareaRef.current;
      if (!node) return;
      node.focus();
      if (captureStart != null) {
        const caret = captureStart + insertion.length;
        node.setSelectionRange(caret, caret);
      } else {
        const tail = node.value.length;
        node.setSelectionRange(tail, tail);
      }
    });
  }, []);

  const handleDrop = useCallback((e: React.DragEvent<HTMLElement>) => {
    e.preventDefault();
    e.stopPropagation();
    let raw = e.dataTransfer?.getData('text/plain') ?? '';
    if (!raw.trim()) {
      const uri = e.dataTransfer?.getData('text/uri-list') ?? '';
      if (uri.trim()) raw = uri;
    }
    raw = raw.trim();
    if (!raw) return;
    // Multi-select drops arrive as newline-separated paths; uri-list comments
    // start with '#'.
    const paths = raw
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#'));
    insertFilePaths(paths);
  }, [insertFilePaths]);

  // Register with the chat-input drop router so Java-mediated drops (IDE
  // Project Tool Window → JBCef) route here when supervisor was last focused.
  // HTML drops on this textarea are handled by {@link handleDrop} above;
  // the router is the catch-net for the Java DropTarget path that has no
  // cursor-target information.
  useEffect(() => {
    return registerChatInputDropHandler('supervisor', insertFilePaths);
  }, [insertFilePaths]);

  // 2026-05-28: let the supervisor pane's Stop button pull focus into this
  // composer after a pause, so the user can type their supplement immediately.
  useEffect(() => {
    return registerChatInputFocusHandler('supervisor', () => {
      textareaRef.current?.focus();
    });
  }, []);

  // Switch which supervisor is active. Mirrors SupervisorToggle.handleConfirm:
  // stop the running pair on the daemon, then start a fresh pair with the new
  // agent via startSupervisorPair — that helper resolves the right-pane
  // composer state (effective model w/ [1m], longContextEnabled, reasoning
  // tier) into the pair_start payload so the daemon SDK boots with the
  // user's chosen config on the first turn.
  const handleSwitchAgent = useCallback(
    (agent: SupervisorAgent) => {
      try {
        sendToJava(`pair_stop:${JSON.stringify({ pairId: '' })}`);
      } catch {
        /* ignore */
      }
      startSupervisorPair(agent);
    },
    [startSupervisorPair]
  );

  // The base model id (without the [1m] suffix) is what we render in the
  // dropdown; we add/remove the suffix transparently based on the 1M toggle.
  const baseEffectiveModel = strip1MContextSuffix(
    modelOverrideByAgentId[supervisor.agentId] ||
    supervisor.model ||
    SUPERVISOR_MODEL_INFOS[0]?.id ||
    'claude-haiku-4-5-20251001'
  );
  const effectiveModel = apply1MContextSuffix(baseEffectiveModel, longContextEnabled);
  const effectiveReasoning: ReasoningEffort =
    reasoningByAgentId[supervisor.agentId] ?? 'medium';

  const handleModelChange = useCallback(
    (modelId: string) => {
      // Strip the [1m] suffix if the picker happened to emit it — the toggle
      // is the source of truth for 1M; we keep the suffix attached via
      // {@code apply1MContextSuffix} on send instead.
      const baseId = strip1MContextSuffix(modelId);
      const effective = apply1MContextSuffix(baseId, longContextEnabled);
      setSupervisorModel(supervisor.agentId, effective === supervisor.model ? null : effective);
    },
    [setSupervisorModel, supervisor.agentId, supervisor.model, longContextEnabled]
  );

  const handleLongContextChange = useCallback(
    (enabled: boolean) => {
      setLongContextEnabled(enabled);
      // Propagate the new resolved model (with/without [1m]) so the daemon
      // picks it up on next supervisor restart. We re-resolve via the *base*
      // id stripped of any prior suffix to avoid double-appending.
      const effective = apply1MContextSuffix(baseEffectiveModel, enabled);
      setSupervisorModel(supervisor.agentId, effective);
    },
    [setLongContextEnabled, baseEffectiveModel, setSupervisorModel, supervisor.agentId]
  );

  const handleReasoningChange = useCallback(
    (effort: ReasoningEffort) => {
      setSupervisorReasoning(supervisor.agentId, effort);
    },
    [setSupervisorReasoning, supervisor.agentId]
  );

  return (
    <div
      className="chat-input-box"
      data-provider="claude"
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      {/* Context bar above the textarea — mirrors the main AI's ContextBar
          (TokenIndicator already renders its own percentage label, so we
          don't add a duplicate span). Left-aligned per the design call —
          the supervisor pane has no file/agent chips to occupy the left
          side, so we put the usage indicator there instead of the right
          where the main AI shows it. Always rendered; 0% placeholder
          before the first turn completes. */}
      <div className={styles.contextBar}>
        <div
          className={styles.contextUsage}
          title={usage
            ? t('pairLayout.usage.tooltip', {
                used: usage.totalPromptTokens.toLocaleString(),
                max: (usage.maxTokens ?? 0).toLocaleString(),
                defaultValue: `${usage.totalPromptTokens.toLocaleString()} / ${(usage.maxTokens ?? 0).toLocaleString()} tokens`,
              })
            : t('pairLayout.usage.placeholder', '等待首轮对话后显示用量')
          }
        >
          <TokenIndicator
            percentage={usage?.percentage ?? 0}
            usedTokens={usage?.totalPromptTokens ?? 0}
            maxTokens={usage?.maxTokens ?? 200_000}
            size={14}
          />
        </div>
      </div>
      {/* Queue strip — only renders when supervisorQueue.length > 0. Reuses
          the main AI's <MessageQueue> by adapting our {text} shape onto its
          expected {content} field. Remove button calls dequeueSupervisorMessage
          for the current agent. */}
      <MessageQueue
        queue={supervisorQueue.map((m) => ({
          id: m.id,
          content: m.text,
          attachments: undefined,
          queuedAt: m.queuedAt,
        }))}
        onRemove={(id) => dequeueSupervisorMessage(supervisor.agentId, id)}
      />
      <div className="input-editable-wrapper">
        <textarea
          ref={textareaRef}
          className={styles.supervisorTextarea}
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            // Detect synchronously off the DOM — `setDraft` is async but the
            // browser has already applied the new value/caret to the textarea,
            // so the trigger detector sees the up-to-date state.
            detectFileTrigger();
          }}
          onKeyDown={handleKeyDown}
          // Cursor-only moves (click, arrow keys) don't fire onChange, so
          // re-detect on these too. Without this, clicking back into an
          // existing `@path` wouldn't reopen the dropdown for editing.
          onKeyUp={detectFileTrigger}
          onClick={detectFileTrigger}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
          onFocus={() => markChatInputFocused('supervisor')}
          placeholder={t('pairLayout.composer.placeholder')}
          spellCheck={false}
        />
      </div>

      <div className="button-area" data-provider="claude">
        <div className="button-area-left">
          <SupervisorAgentSelect
            value={supervisor.agentId}
            onChange={handleSwitchAgent}
            onOpenManager={openManager}
          />

          <ModelSelect
            value={effectiveModel}
            onChange={handleModelChange}
            models={SUPERVISOR_MODEL_INFOS}
            currentProvider="claude"
            longContextEnabled={longContextEnabled}
            onLongContextChange={handleLongContextChange}
          />

          <ReasoningSelect
            value={effectiveReasoning}
            onChange={handleReasoningChange}
            selectedModel={effectiveModel}
            currentProvider="claude"
          />
        </div>

        <div className="button-area-right">
          {isSupervisorBusy ? (
            <button
              className="submit-button stop-button"
              onClick={handleStop}
              disabled={!pairId}
              title={t('pairLayout.interruptSupervisor', { defaultValue: '中断 Supervisor 当前轮' })}
            >
              <span className="codicon codicon-debug-stop" />
            </button>
          ) : (
            <button
              className="submit-button"
              onClick={handleSubmit}
              disabled={draft.trim().length === 0}
              title={t('pairLayout.composer.send')}
            >
              <span className="codicon codicon-send" />
            </button>
          )}
        </div>
      </div>

      {/* @ file-reference dropdown — shares the main AI's CompletionDropdown
          UI and `fileReferenceProvider` so the list, icons, keyboard model
          and Java backend wiring all stay consistent across both composers. */}
      <CompletionDropdown
        isVisible={fileCompletion.isOpen}
        position={fileCompletion.position}
        items={fileCompletion.items}
        selectedIndex={fileCompletion.activeIndex}
        loading={fileCompletion.loading}
        emptyText={t('chat.noMatchingFiles')}
        onClose={fileCompletion.close}
        onSelect={(_, index) => fileCompletion.selectIndex(index)}
        onMouseEnter={fileCompletion.handleMouseEnter}
      />
    </div>
  );
}
