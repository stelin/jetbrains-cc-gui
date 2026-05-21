import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ensureRemoteSyncBridge,
  SYNC_FULL_STATE_EVENT,
  sendToJava,
} from '../../lib/remoteSyncBridge';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * SyncStatusBar (floating banner above the StatusPanel)
 *
 * - Hidden entirely when remote sync is disabled.
 * - One-line current status + path row at the top.
 * - Last 5 status transitions in a mini-console below — gives the user
 *   a sense of activity even when nothing is moving right now.
 * - State arrives every 2 s from MutagenMonitor polling
 *   `mutagen sync list --long <name>`.
 * ──────────────────────────────────────────────────────────────── */

type SyncState =
  | 'disabled'
  | 'stopped'
  | 'starting'
  | 'watching'
  | 'syncing'
  | 'disconnected'
  | 'conflict'
  | 'error';

interface SyncStatus {
  state: SyncState;
  stagingProgress?: number;
  stagingTotal?: number;
  conflicts?: number;
  message?: string;
  updatedAt?: number;
  dirCount?: number;
  fileCount?: number;
  fileBytes?: number;
}

interface SyncConfig {
  enabled: boolean;
  name?: string;
  localPath?: string;
  remoteUser?: string;
  remoteHost?: string;
  remotePath?: string;
}

interface FullStatePayload {
  config?: SyncConfig;
  syncStatus?: SyncStatus;
}

interface LogEntry {
  time: string;
  icon: string;
  text: string;
  colorClass: string;
  signature: string;
}

const MAX_LOG = 5;

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

const COLOR: Record<SyncState, string> = {
  disabled:     styles.colMuted,
  stopped:      styles.colMuted,
  starting:     styles.colProgress,
  watching:     styles.colWatching,
  syncing:      styles.colSyncing,
  disconnected: styles.colErr,
  conflict:     styles.colWarn,
  error:        styles.colErr,
};

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : 1)}${units[i]}`;
}

function timeAgo(updatedAt?: number): string {
  if (!updatedAt) return '';
  const diff = Math.max(0, Math.floor((Date.now() - updatedAt) / 1000));
  if (diff < 5) return 'just now';
  if (diff < 60) return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  return `${Math.floor(diff / 3600)}h ago`;
}

function shortenPath(path: string, maxLen = 32): string {
  if (!path) return '';
  if (path.length <= maxLen) return path;
  return '…' + path.slice(-(maxLen - 1));
}

function nowHms(): string {
  const d = new Date();
  return [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map((n) => String(n).padStart(2, '0'))
    .join(':');
}

function buildSignature(s: SyncStatus): string {
  // Only "meaningful" fields. updatedAt is excluded so heartbeats don't log.
  return [
    s.state,
    s.fileCount || 0,
    s.fileBytes || 0,
    s.dirCount || 0,
    s.stagingProgress || 0,
    s.stagingTotal || 0,
    s.conflicts || 0,
    s.message || '',
  ].join('|');
}

function buildLogEntry(
  s: SyncStatus,
  prev: SyncStatus | null,
  t: (k: string, opts?: Record<string, unknown>) => string,
): LogEntry | null {
  const state = s.state;
  const inv = inventoryString(s);
  let text: string;
  switch (state) {
    case 'watching':
      text = `${t('chatHeader.sync.watching')}${inv ? ' · ' + inv : ''}`;
      break;
    case 'syncing':
      if (s.stagingTotal && s.stagingTotal > 0) {
        text = `${t('chatHeader.sync.syncing')} ${formatBytes(s.stagingProgress || 0)} / ${formatBytes(s.stagingTotal)}`;
      } else {
        text = t('chatHeader.sync.syncing');
      }
      break;
    case 'starting':
      text = t('chatHeader.sync.starting');
      break;
    case 'stopped':
      text = t('chatHeader.sync.stopped');
      break;
    case 'disconnected':
      text = `${t('chatHeader.sync.disconnected')}${s.message ? ' · ' + s.message : ''}`;
      break;
    case 'conflict':
      text = t('chatHeader.sync.conflict', { n: s.conflicts || 0 });
      break;
    case 'error':
      text = `${t('chatHeader.sync.error')}: ${s.message || ''}`;
      break;
    default:
      return null;
  }
  // Augment with a "Δ files" hint when the file count moved.
  if (prev && state === 'watching' && (s.fileCount || 0) !== (prev.fileCount || 0)) {
    const delta = (s.fileCount || 0) - (prev.fileCount || 0);
    text = `${text} (${delta > 0 ? '+' : ''}${delta} 文件)`;
  }
  return {
    time: nowHms(),
    icon: ICON[state],
    text,
    colorClass: COLOR[state],
    signature: buildSignature(s),
  };
}

function inventoryString(s: SyncStatus): string {
  if (!s.fileCount) return '';
  const parts: string[] = [];
  parts.push(`${s.fileCount} 文件`);
  if (s.fileBytes) parts.push(formatBytes(s.fileBytes));
  return parts.join(' / ');
}

export function SyncStatusBar() {
  const { t } = useTranslation();
  const [enabled, setEnabled] = useState(false);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [config, setConfig] = useState<SyncConfig>({ enabled: false });
  const [log, setLog] = useState<LogEntry[]>([]);
  const [, setTick] = useState(0);
  const lastSignature = useRef<string>('');
  const lastStatus = useRef<SyncStatus | null>(null);

  useEffect(() => {
    ensureRemoteSyncBridge();
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<FullStatePayload>).detail;
      if (!detail) return;
      if (detail.config) {
        setEnabled(!!detail.config.enabled);
        setConfig(detail.config);
      }
      if (detail.syncStatus) {
        setStatus(detail.syncStatus);
        const sig = buildSignature(detail.syncStatus);
        // Only log when something genuinely changed — state, file count,
        // progress, conflict count, or message. Repeated identical polls
        // (the "I'm still watching" heartbeat) are silently dropped.
        if (sig !== lastSignature.current) {
          const entry = buildLogEntry(detail.syncStatus, lastStatus.current, t);
          if (entry) {
            setLog((prev) => [...prev, entry].slice(-MAX_LOG));
          }
          lastSignature.current = sig;
          lastStatus.current = detail.syncStatus;
        }
      }
    };
    window.addEventListener(SYNC_FULL_STATE_EVENT, handler);
    sendToJava('get_remote_sync_state:');
    return () => window.removeEventListener(SYNC_FULL_STATE_EVENT, handler);
  }, [t]);

  // Tick once a second so "X s ago" stays fresh even when no new status is
  // arriving — gives the user proof that the panel is alive.
  useEffect(() => {
    const id = setInterval(() => setTick((n) => (n + 1) % 1000000), 1000);
    return () => clearInterval(id);
  }, []);

  if (!enabled) return null;

  const state: SyncState = status?.state || 'starting';
  const text = buildText(state, status, t);

  const showProgress = state === 'syncing'
    && status?.stagingTotal != null && status.stagingTotal > 0;
  const pct = showProgress
    ? Math.round(((status?.stagingProgress || 0) / (status?.stagingTotal || 1)) * 100)
    : 0;

  const remoteLabel = config.remoteUser && config.remoteHost
    ? `${config.remoteUser}@${config.remoteHost}:${config.remotePath || ''}`
    : '';

  const inventory = inventoryString(status || ({} as SyncStatus));

  // Hide the log when nothing has ever changed; show as soon as we've seen
  // at least one event.
  const showLog = log.length >= 1;

  return (
    <div className={styles.banner} title={status?.message || text}>
      <div className={styles.row}>
        <span className={styles.icon}>{ICON[state]}</span>
        <span className={`${styles.text} ${COLOR[state]}`}>{text}</span>
        {showProgress && (
          <span className={styles.detail}>
            {formatBytes(status?.stagingProgress || 0)} / {formatBytes(status?.stagingTotal || 0)}
          </span>
        )}
        {!showProgress && inventory && (
          <span className={styles.detail}>{inventory}</span>
        )}
        {!showProgress && !inventory && status?.message && (
          <span className={styles.detail}>{status.message}</span>
        )}
        <span className={styles.timeAgo}>{timeAgo(status?.updatedAt)}</span>
      </div>
      {showProgress && (
        <div className={styles.progressBar}>
          <div className={styles.progressFill} style={{ width: `${pct}%` }} />
        </div>
      )}
      {(config.localPath || remoteLabel) && (
        <div className={styles.pathRow}>
          <span className={styles.pathSeg} title={config.localPath}>
            {shortenPath(config.localPath || '')}
          </span>
          <span className={styles.arrow}>↔</span>
          <span className={styles.pathSeg} title={remoteLabel}>
            {shortenPath(remoteLabel)}
          </span>
        </div>
      )}
      {showLog && (
        <div className={styles.console}>
          {log.map((e, i) => (
            <div className={styles.consoleLine} key={i}>
              <span className={styles.consoleTime}>{e.time}</span>
              <span className={`${styles.consoleIcon} ${e.colorClass}`}>{e.icon}</span>
              <span className={`${styles.consoleText} ${e.colorClass}`}>{e.text}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function buildText(
  state: SyncState,
  s: SyncStatus | null,
  t: (k: string, opts?: Record<string, unknown>) => string,
): string {
  switch (state) {
    case 'disabled':     return t('chatHeader.sync.stopped');
    case 'stopped':      return t('chatHeader.sync.stopped');
    case 'starting':     return t('chatHeader.sync.starting');
    case 'watching':     return t('chatHeader.sync.watching');
    case 'syncing':      return t('chatHeader.sync.syncing');
    case 'disconnected': return t('chatHeader.sync.disconnected');
    case 'conflict':     return t('chatHeader.sync.conflict', { n: s?.conflicts || 0 });
    case 'error':        return t('chatHeader.sync.error');
    default:             return String(state);
  }
}
