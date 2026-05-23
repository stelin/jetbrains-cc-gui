import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export interface EscalateChoice {
  id: string;
  label: string;
  description?: string;
}

export interface EscalateStats {
  auto_recover_count?: number;
  escalate_count?: number;
  decision_count?: number;
  decision_review_flag_count?: number;
  review_reject_count?: number;
  verify_fail_count?: number;
}

export interface EscalateStep {
  index?: number;
  status?: string;
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
  /**
   * v3: session stats + step snapshot. When present, render a summary header
   * above the question so the user sees the session's overall health before
   * deciding (especially useful for end-of-plan verification escalates).
   */
  stats?: EscalateStats;
  steps?: EscalateStep[];
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
  stats,
  steps,
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

  // Session summary row — rendered when stats are attached (v3 verification
  // escalates). Skipped silently for amendment-request / mid-session escalates
  // that arrive without stats.
  const hasStats = stats && (
    typeof stats.decision_count === 'number'
    || typeof stats.auto_recover_count === 'number'
    || typeof stats.review_reject_count === 'number'
  );
  const stepsDone = Array.isArray(steps)
    ? steps.filter((s) => s?.status === 'done').length
    : 0;
  const stepsTotal = Array.isArray(steps) ? steps.length : 0;

  return (
    <div className={styles.escalateBackdrop} onClick={onCancel}>
      <div
        className={styles.escalateDialog}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className={styles.escalateTitle}>
          <span className="codicon codicon-warning" />
          {t('pairLayout.escalate.title', { name: supervisorName })}
        </div>
        {hasStats && (
          <div className={styles.escalateSummary}>
            {stepsTotal > 0 && (
              <div className={styles.escalateSummaryRow}>
                <span className={styles.escalateSummaryLabel}>
                  {t('pairLayout.escalate.summary.stepsCompleted', { done: stepsDone, total: stepsTotal })}
                </span>
              </div>
            )}
            {typeof stats?.decision_count === 'number' && stats.decision_count > 0 && (
              <div className={styles.escalateSummaryRow}>
                <span className={styles.escalateSummaryLabel}>
                  {t('pairLayout.escalate.summary.decisionTotal', { count: stats.decision_count })}
                </span>
                {typeof stats.decision_review_flag_count === 'number' && stats.decision_review_flag_count > 0 && (
                  <span className={styles.escalateSummaryFlag}>
                    {t('pairLayout.escalate.summary.decisionReviewFlag', { count: stats.decision_review_flag_count })}
                  </span>
                )}
              </div>
            )}
            {typeof stats?.auto_recover_count === 'number' && stats.auto_recover_count > 0 && (
              <div className={styles.escalateSummaryRow}>
                <span className={styles.escalateSummaryLabel}>
                  {t('pairLayout.escalate.summary.autoRecover', { count: stats.auto_recover_count })}
                </span>
              </div>
            )}
            {typeof stats?.review_reject_count === 'number' && stats.review_reject_count > 0 && (
              <div className={styles.escalateSummaryRow}>
                <span className={styles.escalateSummaryLabel}>
                  {t('pairLayout.escalate.summary.reviewReject', { count: stats.review_reject_count })}
                </span>
              </div>
            )}
          </div>
        )}
        {reason && <div className={styles.escalateReason}>{reason}</div>}
        {question && <div className={styles.escalateBody}>{question}</div>}
        <div className={styles.escalateChoices}>
          {choices.map((choice, idx) => (
            <button
              key={choice.id}
              type="button"
              className={styles.escalateChoice}
              onClick={() => onSelect(choice.id)}
            >
              <span className={styles.escalateChoiceBadge}>
                {choice.id || String.fromCharCode(65 + idx)}
              </span>
              <span className={styles.escalateChoiceContent}>
                <span className={styles.escalateChoiceLabel}>{choice.label}</span>
                {choice.description && (
                  <span className={styles.escalateChoiceDescription}>{choice.description}</span>
                )}
              </span>
              <span className={`codicon codicon-chevron-right ${styles.escalateChoiceChevron}`} />
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
