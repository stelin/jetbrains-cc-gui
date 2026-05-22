import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export type SupervisorActionType =
  | 'inject_prompt'
  | 'retry_with_hint'
  | 'approve_and_continue'
  | 'escalate_to_human'
  | 'wait';

interface ActionCardProps {
  type: SupervisorActionType;
  /** Brief summary line (e.g. injected prompt preview, retry wait seconds, escalate reason). */
  summary?: string;
  /** Optional secondary text shown below the summary. */
  detail?: string;
}

/**
 * Visual card rendered inside a SupervisorSubPanel when the agent emits an ACTION.
 * Phase A: pure presentation — Phase B wires real ACTION payloads here.
 */
export default function ActionCard({ type, summary, detail }: ActionCardProps) {
  const { t } = useTranslation();

  const meta = {
    inject_prompt:        { cls: styles.inject,   icon: 'codicon-arrow-left',  labelKey: 'pairLayout.action.inject' },
    retry_with_hint:      { cls: styles.retry,    icon: 'codicon-refresh',     labelKey: 'pairLayout.action.retry' },
    approve_and_continue: { cls: styles.approve,  icon: 'codicon-check',       labelKey: 'pairLayout.action.approve' },
    escalate_to_human:    { cls: styles.escalate, icon: 'codicon-warning',     labelKey: 'pairLayout.action.escalate' },
    wait:                 { cls: styles.approve,  icon: 'codicon-watch',       labelKey: 'pairLayout.action.wait' },
  }[type];

  return (
    <div className={`${styles.actionCard} ${meta.cls}`}>
      <span className={`codicon ${meta.icon} ${styles.actionCardIcon}`} />
      <div className={styles.actionCardBody}>
        <div className={styles.actionCardLabel}>
          {t(meta.labelKey)}
          {summary ? `: ${summary}` : ''}
        </div>
        {detail && <div className={styles.actionCardDetail}>{detail}</div>}
      </div>
    </div>
  );
}
