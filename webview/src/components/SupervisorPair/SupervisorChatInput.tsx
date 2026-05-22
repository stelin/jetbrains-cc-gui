import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SelectedSupervisor,
  SupervisorAgent,
} from '../../types/supervisorAgent';
import type { ModelInfo, ReasoningEffort } from '../ChatInputBox/types';
import { ModelSelect } from '../ChatInputBox/selectors/ModelSelect';
import { ReasoningSelect } from '../ChatInputBox/selectors/ReasoningSelect';
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
    setSelected,
    openManager,
  } = usePairContext();

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
  // stop the running pair on the daemon, swap the selected list, then start a
  // fresh pair with the new agent.
  const handleSwitchAgent = useCallback(
    (agent: SupervisorAgent) => {
      const next: SelectedSupervisor[] = [
        {
          agentId: agent.id,
          name: agent.name,
          role: 'coordinator',
          model: agent.model,
        },
      ];
      try {
        sendToJava(`pair_stop:${JSON.stringify({ pairId: '' })}`);
      } catch {
        /* ignore */
      }
      setSelected(next);
      try {
        sendToJava(`pair_start:${JSON.stringify({ agentId: agent.id })}`);
      } catch {
        /* ignore */
      }
    },
    [setSelected]
  );

  const effectiveModel =
    modelOverrideByAgentId[supervisor.agentId] ||
    supervisor.model ||
    SUPERVISOR_MODEL_INFOS[0]?.id ||
    'claude-haiku-4-5-20251001';
  const effectiveReasoning: ReasoningEffort =
    reasoningByAgentId[supervisor.agentId] ?? 'medium';

  const handleModelChange = useCallback(
    (modelId: string) => {
      setSupervisorModel(supervisor.agentId, modelId === supervisor.model ? null : modelId);
    },
    [setSupervisorModel, supervisor.agentId, supervisor.model]
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
