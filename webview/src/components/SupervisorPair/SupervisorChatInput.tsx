import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
  SelectedSupervisor,
} from '../../types/supervisorAgent';
import type { ModelInfo, ReasoningEffort } from '../ChatInputBox/types';
import { ModelSelect } from '../ChatInputBox/selectors/ModelSelect';
import { ReasoningSelect } from '../ChatInputBox/selectors/ReasoningSelect';
import { SUPERVISOR_MODELS } from '../settings/SupervisorSection/templates';
import PickerDialog from '../ChatInputBox/SupervisorToggle/PickerDialog';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

/**
 * Convert SUPERVISOR_MODELS (settings template format) to ModelInfo[] so it
 * can be fed to the reused {@code ModelSelect} component.
 */
const SUPERVISOR_MODEL_INFOS: ModelInfo[] = SUPERVISOR_MODELS.map((m) => ({
  id: m.id,
  label: m.label,
}));

interface SupervisorChatInputProps {
  /** The active supervisor this input addresses. */
  supervisor: SelectedSupervisor;
}

/**
 * Right-pane chat input — visual twin of the main ChatInputBox but scoped to
 * the active Supervisor. Reuses the `.chat-input-box` card chrome, the shared
 * `ModelSelect` / `ReasoningSelect` selectors, and the global `submit-button`
 * styling so both columns feel like the same surface.
 *
 * Differences from the main input:
 *  - No `/` command / `#` agent / `!` prompt / `$` command completions.
 *  - No provider switching (Supervisor is always Claude).
 *  - Model list is locked to {@link SUPERVISOR_MODELS}.
 *  - An "Agent" chip on the left lets the user switch the active supervisor.
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
  } = usePairContext();

  const [draft, setDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Picker state for the agent chip. Subscribe to the supervisor-agents list
  // exactly like SupervisorToggle does — both surfaces need fresh data.
  const [pickerOpen, setPickerOpen] = useState(false);
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultAgentId, setDefaultAgentId] = useState<string | null>(null);

  useEffect(() => {
    const previous = window.updateSupervisorAgents;
    window.updateSupervisorAgents = (jsonStr: string) => {
      previous?.(jsonStr);
      try {
        const payload: SupervisorAgentListPayload = JSON.parse(jsonStr);
        setAgents(payload.agents || []);
        setDefaultAgentId(payload.defaultAgentId ?? null);
      } catch {
        /* ignore */
      }
    };
    sendToJava('get_supervisor_agents:');
    return () => {
      window.updateSupervisorAgents = previous;
    };
  }, []);

  const handleSubmit = useCallback(() => {
    const text = draft.trim();
    if (!text) return;
    sendUserInputToSupervisor(text);
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

  // Drag-drop: same handling as the previous composer. Accept everything, then
  // pull the first path-shaped MIME and insert as `@path ` at the caret.
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

  // Switch which supervisor is active without leaving the right pane.
  // Mirrors SupervisorToggle.handleConfirm: stop the current pair on the
  // daemon, swap the selected list, then start a fresh pair with the new
  // agent. Phase B Java side will reconcile the pair lifecycle.
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
      setPickerOpen(false);
    },
    [setSelected]
  );

  // Effective model = runtime override OR agent default OR first supervisor model.
  const effectiveModel =
    modelOverrideByAgentId[supervisor.agentId] ||
    supervisor.model ||
    SUPERVISOR_MODEL_INFOS[0]?.id ||
    'claude-haiku-4-5-20251001';
  const effectiveReasoning: ReasoningEffort =
    reasoningByAgentId[supervisor.agentId] ?? 'medium';

  const handleModelChange = useCallback(
    (modelId: string) => {
      // If user picks the agent's configured default, clear the override so
      // the chip stops showing the "overridden" hint.
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
      className={`chat-input-box ${styles.supervisorInput}`}
      data-provider="claude"
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      <div className={styles.composerTargetLabel}>
        <span className="codicon codicon-eye" />
        <span>{t('pairLayout.composer.targetLabel', 'To Supervisor')}</span>
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
          rows={3}
          spellCheck={false}
        />
      </div>

      <div className="button-area" data-provider="claude">
        <div className="button-area-left">
          {/* Active supervisor chip — click to open the picker and switch. */}
          <button
            type="button"
            className="selector-button"
            onClick={() => setPickerOpen(true)}
            title={t('pairLayout.composer.agentChip.tooltip', 'Switch supervisor')}
          >
            <span className="codicon codicon-eye" />
            <span className="selector-button-text">{supervisor.name}</span>
            <span
              className="codicon codicon-chevron-down"
              style={{ fontSize: '10px', marginLeft: '2px' }}
            />
          </button>

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

      <PickerDialog
        open={pickerOpen}
        agents={agents}
        defaultId={defaultAgentId}
        initialSelectedId={supervisor.agentId}
        onCancel={() => setPickerOpen(false)}
        onConfirm={handleSwitchAgent}
      />
    </div>
  );
}
