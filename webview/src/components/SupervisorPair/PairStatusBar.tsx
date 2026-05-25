import { useMemo } from 'react';
import { usePairContext, type PairStatusSnapshot } from './PairContext';
import styles from './style.module.less';

/**
 * Phase 2 (2026-05-24): compact status bar rendered above the supervisor
 * panes. Shows monitor state, SDK-real context ratio, compactions, pending
 * event count, and the most recent alert. Stays out of the way when nothing
 * notable is happening — collapses to a single-line summary unless health is
 * non-HEALTHY or context ratio is high.
 *
 * <p>Receives state from {@link PairStatusSnapshot} pushed by the Java
 * {@code PairStatusPusher} on the {@code window.onPairStatusUpdate} channel.
 * No polling; React re-renders only when the snapshot reference changes.
 */
export default function PairStatusBar() {
  const { pairStatus, isPairActive } = usePairContext();

  const visible = isPairActive && pairStatus != null;

  const ratioPct = useMemo(() => {
    if (!pairStatus?.supervisorContextRatio) return null;
    return Math.round(pairStatus.supervisorContextRatio * 100);
  }, [pairStatus?.supervisorContextRatio]);

  const lastAlert = useMemo(() => {
    if (!pairStatus?.recentAlerts || pairStatus.recentAlerts.length === 0) return null;
    return pairStatus.recentAlerts[pairStatus.recentAlerts.length - 1];
  }, [pairStatus?.recentAlerts]);

  if (!visible || !pairStatus) return null;

  const stateLabel = pairStatus.state ?? 'IDLE';
  const healthLabel = pairStatus.health ?? 'HEALTHY';
  const stateDotClass =
    pairStatus.state === 'TICK' ? styles.statusDotTick :
    pairStatus.state === 'ROTATING' ? styles.statusDotRotating :
    pairStatus.state === 'MAIN_TURN' ? styles.statusDotMain :
    styles.statusDotIdle;
  const healthClass =
    pairStatus.health === 'UNHEALTHY' ? styles.healthBad :
    pairStatus.health === 'DEGRADED' ? styles.healthWarn :
    styles.healthOk;

  return (
    <div className={styles.pairStatusBar} data-pair-id={pairStatus.pairId}>
      <span className={`${styles.statusDot} ${stateDotClass}`} />
      <span className={styles.pairStateLabel}>{stateLabel.toLowerCase()}</span>
      <span className={`${styles.pairHealthLabel} ${healthClass}`}>
        {healthLabel.toLowerCase()}
      </span>

      {ratioPct != null && (
        <span className={styles.pairCtxRatio} title="SDK getContextUsage()">
          ctx {ratioPct}%
        </span>
      )}

      {pairStatus.compactCount > 0 && (
        <span className={styles.pairCompactCount} title="Auto-compactions in this session">
          cx{pairStatus.compactCount}
        </span>
      )}

      {pairStatus.pendingEvents > 0 && (
        <span className={styles.pairPending} title="Events queued for next monitor tick">
          q{pairStatus.pendingEvents}
        </span>
      )}

      {pairStatus.totalDroppedEvents > 0 && (
        <span className={styles.pairDropped} title="Events dropped due to ring overflow">
          ↓{pairStatus.totalDroppedEvents}
        </span>
      )}

      <span className={styles.pairTickCount} title="Monitor tick counter">
        t{pairStatus.tickCount}
      </span>

      {lastAlert && (
        <span
          className={`${styles.pairLastAlert} ${
            lastAlert.severity === 'ERROR' ? styles.alertError :
            lastAlert.severity === 'WARN'  ? styles.alertWarn :
            styles.alertInfo
          }`}
          title={new Date(lastAlert.ts).toLocaleTimeString()}
        >
          {lastAlert.message}
        </span>
      )}
    </div>
  );
}
