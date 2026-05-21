import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export interface HostKeyChallenge {
  id: string;
  host: string;
  port: number;
  keyType: string;
  fingerprintSha256: string;
}

interface Props {
  challenge: HostKeyChallenge;
  onConfirm: () => void;
  onCancel: () => void;
}

export function HostKeyDialog({ challenge, onConfirm, onCancel }: Props) {
  const { t } = useTranslation();
  return (
    <div className={styles.modalBackdrop} onClick={onCancel}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.modalTitle}>{t('settings.remoteSync.hostKey.title')}</div>
        <div className={styles.modalBody}>
          <div>
            {t('settings.remoteSync.hostKey.firstTime', {
              host: challenge.host,
              port: challenge.port,
            })}
          </div>
          <div className={styles.fingerprint}>
            <span className={styles.fingerprintLabel}>{challenge.keyType}</span>
            <code>{challenge.fingerprintSha256}</code>
          </div>
          <div className={styles.modalHint}>
            {t('settings.remoteSync.hostKey.warning')}
          </div>
        </div>
        <div className={styles.modalActions}>
          <button type="button" className={styles.btn} onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={onConfirm}>
            {t('settings.remoteSync.hostKey.trust')}
          </button>
        </div>
      </div>
    </div>
  );
}
