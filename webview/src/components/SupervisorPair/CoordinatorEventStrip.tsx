import { useMemo, useState } from 'react';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

/**
 * Contract State Machine v3 (2026-05-25): live strip of coordinator events
 * (plan transitions / contract issue·discharge·retry·escalate·cancel /
 * dispatcher wake). Sourced from {@code pairStatus.recentCoordinatorEvents}
 * pushed by Java's PairStatusPusher on each event.
 *
 * <p>Visibility: shows whenever a Pair is active. Empty state renders a
 * single muted "等待协调者事件…" placeholder so the operator knows the strip
 * is live but no events have happened yet (a hard push will land as soon as
 * the first contract is issued / plan is created).
 *
 * <p>UX:
 * <ul>
 *   <li>Compact horizontal log: latest 5 events visible, click "展开" to see
 *       all 15.</li>
 *   <li>Each entry: HH:MM:SS source-icon message (detail in tooltip).</li>
 *   <li>Color-coded by source (plan=blue, contract=green, guard=orange,
 *       dispatcher=purple).</li>
 * </ul>
 */
type CoordinatorEvent = NonNullable<
  ReturnType<typeof usePairContext>['pairStatus']
>['recentCoordinatorEvents'] extends Array<infer T> | undefined ? T : never;

const SOURCE_LABEL: Record<string, string> = {
  PLAN: 'Plan',
  CONTRACT: 'Ctr',
  GUARD: 'Guard',
  DISPATCHER: 'Disp',
};

const SOURCE_CLASS: Record<string, string> = {
  PLAN: styles.coordEventSourcePlan,
  CONTRACT: styles.coordEventSourceContract,
  GUARD: styles.coordEventSourceGuard,
  DISPATCHER: styles.coordEventSourceDispatcher,
};

function formatTime(ts: number): string {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

export default function CoordinatorEventStrip() {
  const { pairStatus, isPairActive } = usePairContext();
  const [expanded, setExpanded] = useState(false);

  // Newest at end → show in reverse so latest is leftmost.
  const events = useMemo<CoordinatorEvent[]>(() => {
    const raw = pairStatus?.recentCoordinatorEvents ?? [];
    return [...raw].reverse();
  }, [pairStatus?.recentCoordinatorEvents]);

  if (!isPairActive) return null;

  const visibleCount = expanded ? events.length : Math.min(5, events.length);
  const visible = events.slice(0, visibleCount);
  const hidden = events.length - visibleCount;

  return (
    <div className={styles.coordEventStrip}>
      <span className={styles.coordEventTitle} title="协调者运行事件(Plan / Contract / Guard / Dispatcher)">
        协调者
      </span>
      {visible.length === 0 ? (
        <span className={styles.coordEventEmpty}>等待协调者事件…</span>
      ) : (
        <div className={styles.coordEventScroll}>
          {visible.map((e, idx) => (
            <span
              key={`${e.ts}-${idx}`}
              className={styles.coordEventEntry}
              title={`${formatTime(e.ts)}  ${e.source}  ${e.type}${e.detail ? '\n' + e.detail : ''}`}
            >
              <span className={styles.coordEventTime}>{formatTime(e.ts)}</span>
              <span className={`${styles.coordEventSource} ${SOURCE_CLASS[e.source] ?? ''}`}>
                {SOURCE_LABEL[e.source] ?? e.source}
              </span>
              <span className={styles.coordEventMessage}>{e.message}</span>
            </span>
          ))}
        </div>
      )}
      {events.length > 5 && (
        <button
          type="button"
          className={styles.coordEventToggle}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? '收起' : `展开 (+${hidden})`}
        </button>
      )}
    </div>
  );
}
