import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { PairAlert } from './PairContext';
import styles from './style.module.less';

/** How long a toast stays before auto-dismissing (ms). */
const AUTO_DISMISS_MS = 9000;

interface SupervisorAlertToastProps {
  alerts: PairAlert[];
  onDismiss: (id: string) => void;
}

/**
 * 2026-05-31: bottom-right stack of non-blocking supervisor alerts (autonomy
 * C1/C2 fallbacks / informational escalations). Each toast auto-dismisses; the
 * user can also close it manually. Blocking decisions use EscalateDialog (a
 * modal) instead — this is purely an audit affordance and never pauses the run.
 */
export default function SupervisorAlertToast({ alerts, onDismiss }: SupervisorAlertToastProps) {
  if (alerts.length === 0) return null;
  return (
    <div className={styles.alertToastStack}>
      {alerts.map((alert) => (
        <ToastItem key={alert.id} alert={alert} onDismiss={onDismiss} />
      ))}
    </div>
  );
}

function ToastItem({ alert, onDismiss }: { alert: PairAlert; onDismiss: (id: string) => void }) {
  const { t } = useTranslation();

  useEffect(() => {
    const id = setTimeout(() => onDismiss(alert.id), AUTO_DISMISS_MS);
    return () => clearTimeout(id);
  }, [alert.id, onDismiss]);

  const isHard = alert.severity === 'alert' || alert.category === 'C2';
  const headline = alert.question || alert.reason
    || t('pairLayout.alert.defaultHeadline', '监督者记录了一次自决');
  const detail = alert.fallbackChoice;

  return (
    <div className={`${styles.alertToast} ${isHard ? styles.alertToastHard : ''}`} role="status">
      <span className={`codicon codicon-${isHard ? 'warning' : 'info'} ${styles.alertToastIcon}`} />
      <div className={styles.alertToastContent}>
        <div className={styles.alertToastTitleRow}>
          <span className={styles.alertToastTitle}>
            {t('pairLayout.alert.title', '监督者提醒')}
          </span>
          {alert.category && (
            <span className={styles.alertToastBadge}>{alert.category}</span>
          )}
        </div>
        <div className={styles.alertToastHeadline}>{headline}</div>
        {detail && (
          <div className={styles.alertToastDetail}>
            {t('pairLayout.alert.fallback', '采取的应对：{{choice}}', { choice: detail })}
          </div>
        )}
      </div>
      <button
        type="button"
        className={styles.alertToastClose}
        onClick={() => onDismiss(alert.id)}
        aria-label={t('pairLayout.alert.dismiss', '关闭')}
      >
        <span className="codicon codicon-close" />
      </button>
    </div>
  );
}
