import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SupervisorAgent } from '../../../types/supervisorAgent';
import styles from './style.module.less';

interface PickerDialogProps {
  open: boolean;
  agents: SupervisorAgent[];
  defaultId: string | null;
  /** Currently active supervisor id (for re-open). null = none active. */
  initialSelectedId?: string | null;
  onCancel: () => void;
  onConfirm: (agent: SupervisorAgent) => void;
  onOpenManager?: () => void;
}

export default function PickerDialog({
  open,
  agents,
  defaultId,
  initialSelectedId,
  onCancel,
  onConfirm,
  onOpenManager,
}: PickerDialogProps) {
  const { t } = useTranslation();

  // Single-select: one supervisor per session.
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    if (initialSelectedId && agents.some(a => a.id === initialSelectedId)) {
      setSelectedId(initialSelectedId);
    } else if (defaultId && agents.some(a => a.id === defaultId)) {
      setSelectedId(defaultId);
    } else if (agents.length > 0) {
      setSelectedId(agents[0].id);
    } else {
      setSelectedId(null);
    }
  }, [open, defaultId, initialSelectedId, agents]);

  // Esc to close
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, onCancel]);

  const handleConfirm = () => {
    if (!selectedId) return;
    const agent = agents.find(a => a.id === selectedId);
    if (agent) onConfirm(agent);
  };

  if (!open) return null;

  return (
    <div className={styles.backdrop} onClick={onCancel}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.dialogHeader}>
          <h3 className={styles.dialogTitle}>
            {t('chatInput.supervisor.pickerTitle')}
          </h3>
          <button className={styles.iconButton} onClick={onCancel} title={t('common.close')}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className={styles.dialogBody}>
          {agents.length === 0 ? (
            <div style={{ padding: 24, textAlign: 'center', fontSize: 12, opacity: 0.7 }}>
              {t('chatInput.supervisor.noAgentsConfigured')}
            </div>
          ) : (
            agents.map((agent) => {
              const isChecked = selectedId === agent.id;
              return (
                <div
                  key={agent.id}
                  className={`${styles.agentRow} ${isChecked ? styles.selected : ''}`}
                  onClick={() => setSelectedId(agent.id)}
                >
                  <div className={`${styles.radioBox} ${isChecked ? styles.checkedRadio : ''}`}>
                    {isChecked && <span className={styles.radioDot} />}
                  </div>
                  <div className={styles.agentMeta}>
                    <div className={styles.agentName}>
                      <span>{agent.name}</span>
                      {agent.id === defaultId && (
                        <span className={styles.defaultBadge}>
                          {t('chatInput.supervisor.defaultBadge')}
                        </span>
                      )}
                    </div>
                    <div className={styles.agentDescription}>
                      {agent.description}
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>

        <div className={styles.dialogHint}>
          {t('chatInput.supervisor.pickerHint')}
        </div>

        <div className={styles.dialogFooter}>
          {onOpenManager && (
            <button className={styles.manageLink} onClick={onOpenManager}>
              <span className="codicon codicon-settings-gear" /> {t('chatInput.supervisor.manage')}
            </button>
          )}
          <div className={styles.footerActions}>
            <button className={styles.secondaryButton} onClick={onCancel}>
              {t('common.cancel')}
            </button>
            <button
              className={styles.primaryButton}
              onClick={handleConfirm}
              disabled={!selectedId}
            >
              {t('common.enable')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
