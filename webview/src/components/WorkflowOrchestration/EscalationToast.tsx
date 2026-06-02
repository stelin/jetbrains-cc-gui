import { useTranslation } from 'react-i18next';
import { useWorkflowContext } from './WorkflowContext';
import styles from './style.module.less';

/**
 * Non-modal "node needs you" alert (ui-implementation.md §8.2). Driven by
 * window.onWorkflowEscalation. Visible even when the management dialog is closed.
 */
export default function EscalationToast() {
  const { t } = useTranslation();
  const { escalations, dismissEscalation, jumpToNode } = useWorkflowContext();

  if (escalations.length === 0) return null;

  return (
    <div className={styles.toastWrap}>
      {escalations.map((e) => (
        <div key={e.key} className={styles.toast}>
          <span className={`codicon codicon-warning ${styles.toastIcon}`} />
          <div className={styles.toastBody}>
            <div className={styles.toastTitle}>
              {t('workflow.escalation', 'Node "{{node}}" needs you', { node: e.nodeName })}
            </div>
            {e.reason && <div className={styles.toastReason}>{e.reason}</div>}
            <div className={styles.toastActions}>
              <button
                className={styles.toastBtn}
                onClick={() => { jumpToNode(e.nodeName); dismissEscalation(e.key); }}
              >
                <span className="codicon codicon-go-to-file" /> {t('workflow.jumpToTab', 'Open tab')}
              </button>
              <button className={styles.toastBtnGhost} onClick={() => dismissEscalation(e.key)}>
                {t('common.ok', 'Got it')}
              </button>
            </div>
          </div>
          <button className={styles.toastClose} onClick={() => dismissEscalation(e.key)}>
            <span className="codicon codicon-close" />
          </button>
        </div>
      ))}
    </div>
  );
}
