import { useTranslation } from 'react-i18next';
import type { NodeStatus } from './types';
import { CARD_W, CARD_H } from './layout';
import { formatIdle, idleLevel, ACTIVE_EPS_MS, formatYmdHms, formatElapsedMin } from './idle';
import styles from './style.module.less';

interface StatusMeta {
  cls: string;
  icon: string;
  i18nKey: string;
  fallback: string;
}

export function statusMeta(status: NodeStatus | undefined): StatusMeta {
  switch (status) {
    case 'SCHEDULED':
      return { cls: styles.statusScheduled, icon: 'codicon-watch', i18nKey: 'workflow.status.scheduled', fallback: 'Scheduled' };
    case 'READY':
      return { cls: styles.statusReady, icon: 'codicon-clock', i18nKey: 'workflow.status.ready', fallback: 'Queued' };
    case 'RUNNING':
      return { cls: styles.statusRunning, icon: 'codicon-sync', i18nKey: 'workflow.status.running', fallback: 'Running' };
    case 'WAITING_HUMAN':
      return { cls: styles.statusWaiting, icon: 'codicon-warning', i18nKey: 'workflow.status.waitingHuman', fallback: 'Needs you' };
    case 'DONE':
      return { cls: styles.statusDone, icon: 'codicon-check', i18nKey: 'workflow.status.done', fallback: 'Done' };
    case 'ABORTED':
      return { cls: styles.statusAborted, icon: 'codicon-error', i18nKey: 'workflow.status.aborted', fallback: 'Aborted' };
    case 'PENDING':
    default:
      return { cls: styles.statusPending, icon: 'codicon-circle-outline', i18nKey: 'workflow.status.pending', fallback: 'Pending' };
  }
}

interface NodeCardProps {
  name: string;
  supervisorName: string;
  status?: NodeStatus;
  selected: boolean;
  x: number;
  y: number;
  showStatus: boolean;
  /** Silent time (ms since last activity) for a RUNNING node; undefined hides it. */
  idleMs?: number;
  /** Auto-redispatch threshold (ms); 0 = watchdog off (shown without "/threshold"). */
  thresholdMs?: number;
  /** Epoch ms the node entered RUNNING (shown for running/done nodes). */
  startedAt?: number;
  /** Epoch ms the node reached DONE (shown with elapsed for done nodes). */
  finishedAt?: number;
  draggable?: boolean;
  dragging?: boolean;
  showPorts?: boolean;
  onClick: () => void;
  onDoubleClick: () => void;
  onPointerDown?: (e: React.PointerEvent) => void;
  onOutPointerDown?: (e: React.PointerEvent) => void;
}

export default function NodeCard({
  name, supervisorName, status, selected, x, y, showStatus, idleMs, thresholdMs,
  startedAt, finishedAt,
  draggable, dragging, showPorts, onClick, onDoubleClick, onPointerDown, onOutPointerDown,
}: NodeCardProps) {
  const { t } = useTranslation();
  const meta = statusMeta(status);
  // Time rows for running/completed nodes: 开始时间 always; 结束时间 + 耗时(分) when done.
  const showTimes = showStatus && (status === 'RUNNING' || status === 'DONE') && !!startedAt;
  const elapsedMs = (startedAt && finishedAt) ? Math.max(0, finishedAt - startedAt) : undefined;
  return (
    <div
      className={[
        styles.nodeCard,
        showStatus ? meta.cls : '',
        selected ? styles.nodeSelected : '',
        draggable ? styles.nodeDraggable : '',
        dragging ? styles.nodeDragging : '',
      ].filter(Boolean).join(' ')}
      style={{ left: x, top: y, width: CARD_W, height: CARD_H }}
      data-node-name={name}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      onPointerDown={onPointerDown}
      title={name}
    >
      <span className={`${styles.nodeAccent} ${showStatus ? meta.cls : ''}`} />
      <div className={styles.nodeName}>{name}</div>
      <div className={styles.nodeMeta}>
        <span className={`codicon codicon-eye ${styles.nodeMetaIcon}`} />
        <span className={styles.nodeSup}>{supervisorName || '—'}</span>
      </div>
      {showStatus && (
        <div className={styles.nodeBadge}>
          <span className={`codicon ${meta.icon} ${status === 'RUNNING' ? styles.spin : ''}`} />
          <span>{t(meta.i18nKey, meta.fallback)}</span>
          {status === 'RUNNING' && idleMs != null && (
            <span
              className={styles.nodeSilent}
              data-level={idleLevel(idleMs, thresholdMs ?? 0)}
              title={t('workflow.node.silentTip', '距上次活跃；达阈值将自动重新下发')}
            >
              {idleMs < ACTIVE_EPS_MS ? '0:00' : formatIdle(idleMs)}
              {thresholdMs && thresholdMs > 0 ? ` / ${formatIdle(thresholdMs)}` : ''}
            </span>
          )}
        </div>
      )}

      {showTimes && (
        <div className={styles.nodeTimes}>
          <div className={styles.nodeTimeRow} title={t('workflow.node.startedAt', '开始时间')}>
            <span className={`codicon codicon-debug-start ${styles.nodeTimeIcon}`} />
            <span className={styles.nodeTimeVal}>{formatYmdHms(startedAt)}</span>
          </div>
          {status === 'DONE' && finishedAt && (
            <>
              <div className={styles.nodeTimeRow} title={t('workflow.node.finishedAt', '结束时间')}>
                <span className={`codicon codicon-debug-stop ${styles.nodeTimeIcon}`} />
                <span className={styles.nodeTimeVal}>{formatYmdHms(finishedAt)}</span>
              </div>
              {elapsedMs != null && (
                <div className={styles.nodeTimeRow} title={t('workflow.node.elapsed', '耗时')}>
                  <span className={`codicon codicon-watch ${styles.nodeTimeIcon}`} />
                  <span className={styles.nodeTimeVal}>{formatElapsedMin(elapsedMs)}</span>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {showPorts && (
        <>
          {/* input port (left) — a drop target; hit-testing uses data-node-name */}
          <span className={`${styles.port} ${styles.portIn}`} title={t('workflow.portIn', 'Input (depends on upstream)')} />
          {/* output port (right) — drag from here to connect downstream */}
          <span
            className={`${styles.port} ${styles.portOut}`}
            title={t('workflow.portOut', 'Drag to a node to make it depend on this one')}
            onPointerDown={(e) => { e.stopPropagation(); onOutPointerDown?.(e); }}
          />
        </>
      )}
    </div>
  );
}
