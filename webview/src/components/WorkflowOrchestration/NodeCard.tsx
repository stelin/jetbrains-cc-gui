import { useTranslation } from 'react-i18next';
import type { NodeStatus } from './types';
import { CARD_W, CARD_H } from './layout';
import { formatIdle, idleLevel, ACTIVE_EPS_MS } from './idle';
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
  draggable, dragging, showPorts, onClick, onDoubleClick, onPointerDown, onOutPointerDown,
}: NodeCardProps) {
  const { t } = useTranslation();
  const meta = statusMeta(status);
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
