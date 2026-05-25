import { useMemo, useState } from 'react';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

const formatTs = (ts: number) => {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
};

/**
 * Periodic-event strip between the autonomy controls and the decision timeline.
 *
 * <p>Receives non-actionable notices (currently only supervisor monitor
 * health-check heartbeats) that previously were pushed into the supervisor
 * chat as forced no-op user messages — that path interrupted in-flight
 * thinking. By rendering them here instead, the user sees the heartbeat
 * history without disturbing the supervisor.
 */
export default function PeriodicNoticeStrip() {
  const { notices } = usePairContext();
  const [open, setOpen] = useState(false);

  const list = useMemo(() => notices.slice().reverse(), [notices]);

  if (notices.length === 0) return null;
  const latest = list[0];

  return (
    <div className={styles.noticeStrip}>
      <button
        className={styles.noticeToggle}
        onClick={() => setOpen((o) => !o)}
        title={open ? '收起' : '展开'}
      >
        <span className={styles.noticeChevron}>{open ? '▼' : '▶'}</span>
        <span className={styles.noticeStripLabel}>
          周期事件 ({notices.length})
        </span>
        <span className={styles.noticeLatest}>
          {formatTs(latest.ts)} · {latest.message}
        </span>
      </button>
      {open && (
        <ul className={styles.noticeList}>
          {list.map((n, idx) => (
            <li
              key={`${n.ts}_${idx}`}
              className={styles.noticeItem}
              data-kind={n.kind}
              title={n.details ? JSON.stringify(n.details) : undefined}
            >
              <span className={styles.noticeItemTs}>{formatTs(n.ts)}</span>
              <span className={styles.noticeItemKind}>{n.kind}</span>
              <span className={styles.noticeItemMessage}>{n.message}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
