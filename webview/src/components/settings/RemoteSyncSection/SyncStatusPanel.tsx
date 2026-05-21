import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export type SyncState =
  | 'disabled'
  | 'stopped'
  | 'starting'
  | 'watching'
  | 'syncing'
  | 'disconnected'
  | 'conflict'
  | 'error';

export interface SyncStatus {
  state: SyncState;
  sessionName?: string;
  stagingProgress: number;
  stagingTotal: number;
  conflicts: number;
  message?: string;
  updatedAt: number;
}

interface Props {
  status: SyncStatus;
  onStop: () => void;
  onPause: () => void;
  onResume: () => void;
  enabled: boolean;
}

const ICON: Record<SyncState, string> = {
  disabled:     '⚪',
  stopped:      '⊘',
  starting:     '🟡',
  watching:     '🟢',
  syncing:      '🔵',
  disconnected: '🔴',
  conflict:     '⚠️',
  error:        '❌',
};

const COLOUR_CLASS: Record<SyncState, string> = {
  disabled:     styles.stateMuted,
  stopped:      styles.stateMuted,
  starting:     styles.stateProgress,
  watching:     styles.stateOk,
  syncing:      styles.stateProgress,
  disconnected: styles.stateErr,
  conflict:     styles.stateWarn,
  error:        styles.stateErr,
};

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

export function SyncStatusPanel({ status, onStop, onPause, onResume, enabled }: Props) {
  const { t } = useTranslation();
  if (!enabled) {
    return null;
  }
  const isRunning = status.state !== 'disabled' && status.state !== 'stopped';
  const text = buildText(status, t);

  return (
    <div className={styles.card}>
      <div className={styles.cardHeader}>
        <span className={styles.cardTitle}>{t('settings.remoteSync.status.title')}</span>
        <span className={`${styles.statusRow} ${COLOUR_CLASS[status.state]}`}>
          {ICON[status.state]} {text}
        </span>
      </div>
      {status.state === 'syncing' && status.stagingTotal > 0 && (
        <>
          <div className={styles.progressBar}>
            <div
              className={styles.progressFill}
              style={{ width: `${Math.round((status.stagingProgress / status.stagingTotal) * 100)}%` }}
            />
          </div>
          <div className={styles.progressMeta}>
            <span>{formatBytes(status.stagingProgress)} / {formatBytes(status.stagingTotal)}</span>
          </div>
        </>
      )}
      {status.message && (
        <div className={styles.path}>{status.message}</div>
      )}
      <div className={styles.actions}>
        {isRunning ? (
          <>
            <button type="button" className={styles.btn} onClick={onPause}
              disabled={status.state !== 'watching' && status.state !== 'syncing'}>
              {t('settings.remoteSync.status.pause')}
            </button>
            <button type="button" className={styles.btn} onClick={onStop}>
              {t('settings.remoteSync.status.stop')}
            </button>
          </>
        ) : (
          <button type="button" className={styles.btn} onClick={onResume}
            disabled={status.state === 'disabled'}>
            {t('settings.remoteSync.status.resume')}
          </button>
        )}
      </div>
    </div>
  );
}

function buildText(s: SyncStatus, t: (k: string, opts?: Record<string, unknown>) => string): string {
  switch (s.state) {
    case 'disabled':     return t('settings.remoteSync.status.disabled');
    case 'stopped':      return t('settings.remoteSync.status.stopped');
    case 'starting':     return t('settings.remoteSync.status.starting');
    case 'watching':     return t('settings.remoteSync.status.watching');
    case 'syncing':      return t('settings.remoteSync.status.syncing');
    case 'disconnected': return t('settings.remoteSync.status.disconnected');
    case 'conflict':     return t('settings.remoteSync.status.conflict', { n: s.conflicts });
    case 'error':        return t('settings.remoteSync.status.error');
    default:             return String(s.state);
  }
}
