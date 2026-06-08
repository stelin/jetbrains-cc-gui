import { useEffect, useState } from 'react';
import { usePairContext } from './PairContext';
import { formatYmdHms, formatElapsedMin } from '../WorkflowOrchestration/idle';
import styles from './style.module.less';

/**
 * 2026-06-06: supervisor session timing strip — 开始 / 结束 / 耗时.
 *
 * <p>Placed directly under {@code SessionCountStrip} (the Sup/Main counters) and
 * before {@code DecisionTimeline}. While the supervisor is running the elapsed
 * time ticks live ({@code now - start}); once its owning workflow node reaches
 * DONE the Java side sets {@code supervisorFinishedAt} and it freezes at
 * {@code finish - start} (单位:分). Hidden until the first snapshot carries a
 * start time (so composer pairs before their first turn show nothing).
 */
export default function SupervisorTimeStrip() {
  const { pairStatus, isPairActive } = usePairContext();
  const startedAt = pairStatus?.supervisorStartedAt;
  const finishedAt = pairStatus?.supervisorFinishedAt;
  const [nowTs, setNowTs] = useState(() => Date.now());

  const running = !!startedAt && !finishedAt;
  useEffect(() => {
    if (!running) return undefined;
    const id = window.setInterval(() => setNowTs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [running]);

  if (!isPairActive || !startedAt) return null;
  const elapsedMs = Math.max(0, (finishedAt ?? nowTs) - startedAt);

  return (
    <div className={styles.supervisorTimeStrip}>
      <span className={styles.supTimeItem} title="Supervisor 开始时间">
        <span className={styles.supTimeLabel}>开始</span>
        <span className={styles.supTimeVal}>{formatYmdHms(startedAt)}</span>
      </span>
      <span className={styles.supTimeDot}>·</span>
      <span className={styles.supTimeItem} title="Supervisor 结束时间">
        <span className={styles.supTimeLabel}>结束</span>
        <span className={styles.supTimeVal}>{finishedAt ? formatYmdHms(finishedAt) : '运行中'}</span>
      </span>
      <span className={styles.supTimeDot}>·</span>
      <span className={styles.supTimeItem} title="Supervisor 耗时（单位：分）">
        <span className={styles.supTimeLabel}>耗时</span>
        <span className={styles.supTimeVal}>{formatElapsedMin(elapsedMs)}</span>
      </span>
    </div>
  );
}
