import { useMemo, useState } from 'react';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

interface DecisionEntry {
  ts: number;
  action: string;
  reason?: string;
  result?: string;
  confidence?: 'high' | 'medium' | 'low';
  category?: 'A' | 'B' | 'C1' | 'C2' | 'C3';
  severity?: 'info' | 'warn' | 'alert';
  chosenCandidate?: string;
  stepId?: number;
  autoMode?: boolean;
  payload?: Record<string, unknown>;
}

type SeverityFilter = 'all' | 'info' | 'warn' | 'alert';
type CategoryFilter = 'all' | 'A' | 'B' | 'C1' | 'C2' | 'C3';

const ISO_FMT = (ts: number) => {
  const d = new Date(ts);
  return d.toLocaleString();
};

/** Scrollable list of recent supervisor decisions (B+ tier auto-recorded). */
export default function DecisionTimeline() {
  const { pairStatus } = usePairContext();
  const [open, setOpen] = useState(false);
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>('all');
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>('all');

  const decisions: DecisionEntry[] = useMemo(() => {
    const raw = (pairStatus as unknown as { recentDecisions?: DecisionEntry[] })?.recentDecisions;
    if (!Array.isArray(raw)) return [];
    return raw;
  }, [pairStatus]);

  const filtered = useMemo(() => {
    return decisions.filter((d) => {
      if (severityFilter !== 'all' && (d.severity ?? 'info') !== severityFilter) return false;
      if (categoryFilter !== 'all' && d.category !== categoryFilter) return false;
      return true;
    }).slice().reverse();
  }, [decisions, severityFilter, categoryFilter]);

  if (decisions.length === 0) return null;

  return (
    <div className={styles.decisionTimeline}>
      <button
        className={styles.timelineToggle}
        onClick={() => setOpen((o) => !o)}
      >
        {open ? '▼' : '▶'} 决策时间线 ({decisions.length})
      </button>
      {open && (
        <>
          <div className={styles.timelineFilters}>
            <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value as SeverityFilter)}>
              <option value="all">全部</option>
              <option value="info">info</option>
              <option value="warn">warn</option>
              <option value="alert">alert</option>
            </select>
            <select value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value as CategoryFilter)}>
              <option value="all">全部</option>
              <option value="A">A</option>
              <option value="B">B</option>
              <option value="C1">C1</option>
              <option value="C2">C2</option>
              <option value="C3">C3</option>
            </select>
          </div>
          <ul className={styles.timelineList}>
            {filtered.map((d, idx) => (
              <li key={idx} className={styles.timelineItem} data-severity={d.severity ?? 'info'}>
                <div className={styles.itemHeader}>
                  <span className={styles.itemTs}>{ISO_FMT(d.ts)}</span>
                  <span className={styles.itemAction}>{d.action}</span>
                  {d.category && <span className={styles.itemCategory}>{d.category}</span>}
                  {d.confidence && <span className={styles.itemConfidence}>{d.confidence}</span>}
                  {d.stepId != null && <span className={styles.itemStep}>step {d.stepId}</span>}
                  {d.autoMode === false && <span className={styles.itemUserMark}>user</span>}
                </div>
                {d.reason && <div className={styles.itemReason}>{d.reason}</div>}
                {d.chosenCandidate && (
                  <div className={styles.itemChosen}>chose: {d.chosenCandidate}</div>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
