import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
  SelectedSupervisor,
} from '../../../types/supervisorAgent';
import { usePairContext } from '../../SupervisorPair/PairContext';
import PickerDialog from './PickerDialog';
import styles from './style.module.less';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

interface SupervisorToggleProps {
  /**
   * Notified when the user enables / changes / disables supervisor on this session.
   * In Phase A (UI-only) consumers can simply log or update local state.
   * Phase B will wire to PairSessionManager via `pair:start` / `pair:stop`.
   */
  onChange?: (selected: SelectedSupervisor[]) => void;
  /** Optional callback to navigate to settings → supervisor tab. */
  onOpenManager?: () => void;
}

/**
 * Self-contained toggle button + picker dialog for enabling Supervisor on the session.
 * Owns its own data fetching (agents list, default id) and the selected state.
 * Mount it anywhere in the input area; no prop drilling required.
 */
export default function SupervisorToggle({ onChange, onOpenManager }: SupervisorToggleProps) {
  const { t } = useTranslation();

  // Pair state lives at the top-level provider so PairLayout can react to it.
  const { selected, setSelected, openManager: openManagerFromCtx } = usePairContext();
  const effectiveOpenManager = onOpenManager ?? openManagerFromCtx;

  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultId, setDefaultId] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

  const onChangeRef = useRef(onChange);
  useEffect(() => { onChangeRef.current = onChange; }, [onChange]);

  // Subscribe to supervisor-agents list pushes (chained with any existing callback).
  useEffect(() => {
    const previous = window.updateSupervisorAgents;
    window.updateSupervisorAgents = (jsonStr: string) => {
      // Forward to whichever was registered before us (settings panel, etc.)
      previous?.(jsonStr);
      try {
        const payload: SupervisorAgentListPayload = JSON.parse(jsonStr);
        setAgents(payload.agents || []);
        setDefaultId(payload.defaultAgentId ?? null);
      } catch {
        // ignore
      }
    };

    // Initial fetch
    sendToJava('get_supervisor_agents:');

    return () => {
      window.updateSupervisorAgents = previous;
    };
  }, []);

  const openPicker = useCallback(() => {
    // refresh in case user added/removed in settings during this session
    sendToJava('get_supervisor_agents:');
    setPickerOpen(true);
  }, []);

  const handleConfirm = useCallback((agent: SupervisorAgent) => {
    const next: SelectedSupervisor[] = [{
      agentId: agent.id,
      name: agent.name,
      role: 'coordinator',
      model: agent.model,
    }];
    setSelected(next);
    setPickerOpen(false);
    // Ask Java to start the Pair on the daemon. The Supervisor starts with an
    // EMPTY plan; the user will describe the task in the right-pane input box.
    try {
      sendToJava(`pair_start:${JSON.stringify({ agentId: agent.id })}`);
    } catch { /* ignore */ }
    onChangeRef.current?.(next);
  }, [setSelected]);

  const handleDisable = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    // Stop the active pair on the daemon. PairContext keeps the pairId opaque
    // here; we let Java map the active pair via PairSessionManager.findByMainSession,
    // so the webview only needs to signal intent.
    try {
      sendToJava(`pair_stop:${JSON.stringify({ pairId: '' })}`);
    } catch { /* ignore */ }
    setSelected([]);
    onChangeRef.current?.([]);
  }, [setSelected]);

  const handleManagerOpen = useCallback(() => {
    setPickerOpen(false);
    effectiveOpenManager();
  }, [effectiveOpenManager]);

  const isActive = selected.length > 0;
  const activeName = selected[0]?.name ?? '';
  const buttonLabel = isActive ? activeName : t('chatInput.supervisor.label');
  const tooltip = isActive
    ? t('chatInput.supervisor.activeTooltip', { name: activeName })
    : t('chatInput.supervisor.openTooltip');

  return (
    <div className={styles.toggleWrapper}>
      <button
        type="button"
        className={`${styles.toggleButton} ${isActive ? styles.active : ''}`}
        onClick={openPicker}
        title={tooltip}
      >
        <span className={`codicon codicon-eye ${styles.toggleIcon}`} />
        <span className={styles.toggleLabel}>{buttonLabel}</span>
        {isActive ? (
          <span
            className={`codicon codicon-close ${styles.closeIcon}`}
            onClick={handleDisable}
            title={t('chatInput.supervisor.disable')}
          />
        ) : (
          <span className={`codicon codicon-chevron-down ${styles.chevron}`} />
        )}
      </button>

      <PickerDialog
        open={pickerOpen}
        agents={agents}
        defaultId={defaultId}
        initialSelectedId={selected[0]?.agentId ?? null}
        onCancel={() => setPickerOpen(false)}
        onConfirm={handleConfirm}
        onOpenManager={handleManagerOpen}
      />
    </div>
  );
}
