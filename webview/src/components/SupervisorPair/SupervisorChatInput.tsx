import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SelectedSupervisor,
  SupervisorAgent,
} from '../../types/supervisorAgent';
import type { ModelInfo, ReasoningEffort } from '../ChatInputBox/types';
import { apply1MContextSuffix, strip1MContextSuffix } from '../ChatInputBox/types';
import { ModelSelect } from '../ChatInputBox/selectors/ModelSelect';
import { ReasoningSelect } from '../ChatInputBox/selectors/ReasoningSelect';
import { TokenIndicator } from '../ChatInputBox/TokenIndicator';
import { SUPERVISOR_MODELS } from '../settings/SupervisorSection/templates';
import SupervisorAgentSelect from './SupervisorAgentSelect';
import { usePairContext } from './PairContext';
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
  } = usePairContext();
  const usage = usageByAgentId[supervisor.agentId];

  const [draft, setDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSubmit = useCallback(() => {
    const text = draft.trim();
    if (!text) return;
    // Pull out @-tagged absolute paths so the Java side can translate them
    // local→remote before forwarding to the daemon Supervisor.
    const attachments = extractAtPathAttachments(text);
    sendUserInputToSupervisor(text, attachments);
    setDraft('');
    requestAnimationFrame(() => textareaRef.current?.focus());
  }, [draft, sendUserInputToSupervisor]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit]
  );

  const handleDragOver = useCallback((e: React.DragEvent<HTMLElement>) => {
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'copy';
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
    const firstLine =
      raw.split('\n').map((l) => l.trim()).find((l) => l && !l.startsWith('#')) ?? raw;
    const filePath = firstLine.replace(/^file:\/\//, '');
    const insertion = (filePath.startsWith('@') ? filePath : `@${filePath}`) + ' ';

    const ta = textareaRef.current;
    if (!ta) {
      setDraft((prev) => prev + insertion);
      return;
    }
    const start = ta.selectionStart ?? ta.value.length;
    const end = ta.selectionEnd ?? ta.value.length;
    const before = ta.value.slice(0, start);
    const after = ta.value.slice(end);
    const nextValue = before + insertion + after;
    setDraft(nextValue);
    requestAnimationFrame(() => {
      if (textareaRef.current) {
        const caret = start + insertion.length;
        textareaRef.current.focus();
        textareaRef.current.setSelectionRange(caret, caret);
      }
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
      <div className="input-editable-wrapper">
        <textarea
          ref={textareaRef}
          className={styles.supervisorTextarea}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
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
          <button
            className="submit-button"
            onClick={handleSubmit}
            disabled={draft.trim().length === 0}
            title={t('pairLayout.composer.send')}
          >
            <span className="codicon codicon-send" />
          </button>
        </div>
      </div>
    </div>
  );
}
