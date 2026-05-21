import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export interface MutagenSdkStatus {
  installed: boolean;
  installedVersion?: string;
  installedPath?: string;
  targetVersion: string;
  downloading: boolean;
  progress: number;
  total: number;
  error?: string;
  downloadUrl?: string;
}

interface Props {
  sdk: MutagenSdkStatus;
  onDownload: () => void;
  onCancel: () => void;
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

export function MutagenSdkCard({ sdk, onDownload, onCancel }: Props) {
  const { t } = useTranslation();

  if (sdk.downloading) {
    const pct = sdk.total > 0 ? Math.round((sdk.progress / sdk.total) * 100) : 0;
    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.cardTitle}>{t('settings.remoteSync.sdk.title')}</span>
          <span className={styles.statusDownloading}>
            {t('settings.remoteSync.sdk.statusDownloading')}
          </span>
        </div>
        {sdk.downloadUrl && (
          <div className={styles.path}>
            {t('settings.remoteSync.sdk.downloadUrl')}: {sdk.downloadUrl}
          </div>
        )}
        <div className={styles.progressBar}>
          <div className={styles.progressFill} style={{ width: `${pct}%` }} />
        </div>
        <div className={styles.progressMeta}>
          <span>{pct}%</span>
          <span>
            {formatBytes(sdk.progress)}
            {sdk.total > 0 ? ` / ${formatBytes(sdk.total)}` : ''}
          </span>
        </div>
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onCancel}>
            {t('common.cancel')}
          </button>
        </div>
      </div>
    );
  }

  if (sdk.installed) {
    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.cardTitle}>{t('settings.remoteSync.sdk.title')}</span>
          <span className={styles.statusOk}>
            ✓ {t('settings.remoteSync.sdk.statusInstalled', { version: sdk.installedVersion })}
          </span>
        </div>
        {sdk.installedPath && <div className={styles.path}>{sdk.installedPath}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onDownload}>
            {t('settings.remoteSync.sdk.reinstall')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.card}>
      <div className={styles.cardHeader}>
        <span className={styles.cardTitle}>{t('settings.remoteSync.sdk.title')}</span>
        <span className={styles.statusMissing}>
          ✗ {t('settings.remoteSync.sdk.statusMissing')}
        </span>
      </div>
      <div className={styles.path}>
        {t('settings.remoteSync.sdk.intro')}
      </div>
      {sdk.downloadUrl && (
        <div className={styles.path}>
          {t('settings.remoteSync.sdk.downloadUrl')}: {sdk.downloadUrl}
        </div>
      )}
      {sdk.error && <div className={styles.errorText}>{sdk.error}</div>}
      <div className={styles.actions}>
        <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={onDownload}>
          {t('settings.remoteSync.sdk.download', { version: sdk.targetVersion })}
        </button>
      </div>
    </div>
  );
}
