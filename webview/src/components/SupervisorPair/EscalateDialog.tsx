import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

/** Stable id for the synthetic "Other / custom answer" option. */
const OTHER_CHOICE_ID = '__other__';
/** Cap free-text input to keep the payload bounded. */
const MAX_INPUT_LENGTH = 2000;

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
  /**
   * 2026-05-31: when true (the default for supervisor escalations), the dialog
   * is blocking — it cannot be dismissed via Esc or backdrop click, the user
   * must pick a choice or type an answer. Pass false to allow dismissal.
   */
  blocking?: boolean;
  /**
   * Called with the resolved answer: the chosen choice's {@code id} for an
   * enumerated pick, or the raw free-text the user typed when they selected
   * "Other" / there were no choices. The free-text becomes the supervisor's
   * {@code choice} verbatim.
   */
  onSelect: (value: string) => void;
  onCancel: () => void;
}

/**
 * Modal dialog presented when a Supervisor escalates a decision to the user.
 *
 * <p>Modelled on AskUserQuestionDialog: enumerated choices plus an always-present
 * "Other" option that reveals a free-text box; when the escalation carries no
 * choices at all, the dialog degrades to a single input box. Blocking by default
 * — the user must answer (no Esc / backdrop dismissal) — so a genuine
 * "I need the human" is never silently skipped.
 */
export default function EscalateDialog({
  open,
  supervisorName,
  reason,
  question,
  choices,
  stats,
  steps,
  blocking = true,
  onSelect,
  onCancel,
}: EscalateDialogProps) {
  const { t } = useTranslation();

  const hasChoices = Array.isArray(choices) && choices.length > 0;
  // A blocking dialog may not be dismissed; non-blocking can Esc / backdrop-out.
  const isBlocking = blocking !== false;

  // Selected enumerated choice id, or OTHER_CHOICE_ID for the free-text path.
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [otherText, setOtherText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Reset selection whenever a fresh escalation opens (keyed on question text +
  // choice count so re-opening with new content starts clean). With no choices
  // we go straight into free-text mode.
  useEffect(() => {
    if (!open) return;
    setSelectedId(hasChoices ? null : OTHER_CHOICE_ID);
    setOtherText('');
  }, [open, question, hasChoices]);

  // Esc to cancel — only when the dialog is non-blocking.
  useEffect(() => {
    if (!open || isBlocking) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, isBlocking, onCancel]);

  // Auto-focus the textarea when the free-text path becomes active.
  const isOther = selectedId === OTHER_CHOICE_ID;
  useEffect(() => {
    if (open && isOther) {
      const id = setTimeout(() => inputRef.current?.focus(), 0);
      return () => clearTimeout(id);
    }
    return undefined;
  }, [open, isOther]);

  if (!open) return null;

  const trimmed = otherText.trim();
  const canSubmit = hasChoices
    ? (isOther ? trimmed.length > 0 : selectedId !== null)
    : trimmed.length > 0;

  const handleSubmit = () => {
    if (!canSubmit) return;
    if (hasChoices && selectedId && selectedId !== OTHER_CHOICE_ID) {
      onSelect(selectedId);
    } else {
      // "Other" or choice-less free input → the typed text IS the answer.
      onSelect(trimmed);
    }
  };

  const handleBackdrop = () => {
    if (!isBlocking) onCancel();
  };

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

  const renderRadio = (active: boolean) => (
    <span
      className={`codicon codicon-${active ? 'circle-filled' : 'circle-outline'} ${styles.escalateRadio}`}
    />
  );

  return (
    <div className={styles.escalateBackdrop} onClick={handleBackdrop}>
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
        <div className={styles.escalateScroll}>
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

        {hasChoices && (
          <div className={styles.escalateChoices}>
            {choices.map((choice, idx) => {
              const active = selectedId === choice.id;
              return (
                <button
                  key={choice.id}
                  type="button"
                  className={`${styles.escalateChoice} ${active ? styles.escalateChoiceSelected : ''}`}
                  onClick={() => setSelectedId(choice.id)}
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
                  {renderRadio(active)}
                </button>
              );
            })}

            {/* Always-present "Other" option — reveals the free-text box. */}
            <button
              type="button"
              className={`${styles.escalateChoice} ${isOther ? styles.escalateChoiceSelected : ''}`}
              onClick={() => setSelectedId(OTHER_CHOICE_ID)}
            >
              <span className={styles.escalateChoiceBadge}>
                <span className="codicon codicon-edit" />
              </span>
              <span className={styles.escalateChoiceContent}>
                <span className={styles.escalateChoiceLabel}>
                  {t('pairLayout.escalate.otherOption', '其他')}
                </span>
                <span className={styles.escalateChoiceDescription}>
                  {t('pairLayout.escalate.otherOptionDesc', '输入自定义回复')}
                </span>
              </span>
              {renderRadio(isOther)}
            </button>
          </div>
        )}

        {/* Free-text box: shown when "Other" is picked, or when there are no
            choices at all (degrade to a single input box). */}
        {(isOther || !hasChoices) && (
          <textarea
            ref={inputRef}
            className={styles.escalateInput}
            value={otherText}
            onChange={(e) => setOtherText(e.target.value.slice(0, MAX_INPUT_LENGTH))}
            placeholder={t('pairLayout.escalate.inputPlaceholder', '请输入您的回复…')}
            rows={3}
            maxLength={MAX_INPUT_LENGTH}
          />
        )}
        </div>

        <div className={styles.escalateActions}>
          {!isBlocking && (
            <button
              type="button"
              className={styles.escalateCancel}
              onClick={onCancel}
            >
              {t('pairLayout.escalate.cancel', '取消')}
            </button>
          )}
          <button
            type="button"
            className={styles.escalateSubmit}
            onClick={handleSubmit}
            disabled={!canSubmit}
          >
            {t('pairLayout.escalate.submit', '提交')}
          </button>
        </div>
      </div>
    </div>
  );
}
