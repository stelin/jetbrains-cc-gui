import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export interface EscalateChoice {
  id: string;
  label: string;
  description?: string;
}

interface EscalateDialogProps {
  open: boolean;
  /** Name of the Supervisor that triggered the escalation. */
  supervisorName: string;
  /** Short reason summary. */
  reason: string;
  /** Detailed question (e.g. main AI modified off-plan middleware/auth.go, accept?) */
  question?: string;
  choices: EscalateChoice[];
  onSelect: (choiceId: string) => void;
  onCancel: () => void;
}

/**
 * Modal dialog presented when a Supervisor escalates a decision to the user.
 * Phase A: visual skeleton, triggered by mock/dev hooks.
 * Phase B: wired to ActionRouter's `escalate_to_human` action.
 */
export default function EscalateDialog({
  open,
  supervisorName,
  reason,
  question,
  choices,
  onSelect,
  onCancel,
}: EscalateDialogProps) {
  const { t } = useTranslation();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <div className={styles.escalateBackdrop} onClick={onCancel}>
      <div className={styles.escalateDialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.escalateTitle}>
          <span className="codicon codicon-warning" />
          {t('pairLayout.escalate.title', { name: supervisorName })}
        </div>
        <div className={styles.escalateReason}>{reason}</div>
        {question && <div className={styles.escalateBody}>{question}</div>}
        <div className={styles.escalateChoices}>
          {choices.map((choice) => (
            <button
              key={choice.id}
              className={styles.escalateChoice}
              onClick={() => onSelect(choice.id)}
            >
              <strong>{choice.label}</strong>
              {choice.description && <div style={{ opacity: 0.7, marginTop: 2 }}>{choice.description}</div>}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
