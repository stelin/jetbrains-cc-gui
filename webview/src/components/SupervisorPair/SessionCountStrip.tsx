import { useEffect, useState } from 'react';
import { usePairContext } from './PairContext';
import { useWorkflowContext } from '../WorkflowOrchestration';
import { formatIdle, idleLevel, ACTIVE_EPS_MS } from '../WorkflowOrchestration/idle';
import styles from './style.module.less';

/**
 * 2026-05-25: compact strip between the autonomy controls and the periodic
 * notice strip.
 *
 * <p>Row 1: rotation (new-session) count and SDK auto-compaction count for
 * both supervisor and main AI. Rare events — usually stays at 0 during
 * normal short tests.
 *
 * <p>Row 2 (2026-05-25 v2): live activity counters from ContractRegistry.
 * Updates in real time as supervisor issues / main AI discharges contracts.
 * Gives the operator immediate feedback that the system is alive even
 * before any rotation / compaction event has happened.
 *
 * <p>Visibility: shows whenever a supervisor pair is active. Until the first
 * {@code pairStatus} snapshot arrives, all counters render as 0.
 */
export default function SessionCountStrip() {
  const { pairStatus, isPairActive } = usePairContext();
  // Workflow silent-time (D41): if this pair IS a running workflow node, show the
  // SAME idle/threshold the watchdog uses (effectiveLastActiveAt via nodeActivity).
  const { execution, nodeActivity, capabilities } = useWorkflowContext();
  const [nowTs, setNowTs] = useState(() => Date.now());

  const myPairId = pairStatus?.pairId;
  const wfNode = (execution && myPairId)
    ? Object.keys(execution.nodes).find((n) => execution.nodes[n].pairId === myPairId)
    : undefined;
  const wfRunning = !!wfNode && execution!.nodes[wfNode!].status === 'RUNNING';
  const wfActivityAt = wfNode ? nodeActivity[wfNode] : undefined;

  useEffect(() => {
    if (!wfRunning || !wfActivityAt) return undefined;
    const id = window.setInterval(() => setNowTs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [wfRunning, wfActivityAt]);

  if (!isPairActive) return null;

  const wfIdleMs = (wfRunning && wfActivityAt) ? Math.max(0, nowTs - wfActivityAt) : undefined;
  const wfThresholdMs = (capabilities.freezeThresholdMinutes ?? 0) * 60_000;

  // ─── Row 1: rare-event lifetime counters ─────────────────────────
  const supRot = pairStatus?.supervisorRotationCount ?? 0;
  const supCx = pairStatus?.compactCount ?? 0;
  const mainRot = pairStatus?.mainAiRotationCount ?? 0;
  const mainCx = pairStatus?.mainAiCompactCount ?? 0;

  // ─── Row 2: live activity (changes turn-by-turn) ─────────────────
  const tickCount = pairStatus?.tickCount ?? 0;
  const openContracts = pairStatus?.openContractCount ?? 0;
  const totalIssued = pairStatus?.totalIssuedContracts ?? 0;
  const totalRetried = pairStatus?.totalRetriedContracts ?? 0;
  const totalDischarged = pairStatus?.totalDischargedContracts ?? 0;
  const totalEscalated = pairStatus?.totalEscalatedContracts ?? 0;
  const lastActivityMs = pairStatus?.lastActivityAgoMs;

  return (
    <div className={styles.sessionCountStrip}>
      {/* Row 1: rare-event counters (rotation / compaction) */}
      <div className={styles.sessionCountRow}>
        <span className={styles.sessionCountGroup} title="Supervisor: 主动新建会话次数 · SDK 自动压缩次数">
          <span className={styles.sessionCountSide}>Sup</span>
          <span className={styles.sessionCountValue}>新建 {supRot}</span>
          <span className={styles.sessionCountDot}>·</span>
          <span className={styles.sessionCountValue}>压缩 {supCx}</span>
        </span>
        <span className={styles.sessionCountGroup} title="Main AI: 主动新建会话次数 · SDK 自动压缩次数">
          <span className={styles.sessionCountSide}>Main</span>
          <span className={styles.sessionCountValue}>新建 {mainRot}</span>
          <span className={styles.sessionCountDot}>·</span>
          <span className={styles.sessionCountValue}>压缩 {mainCx}</span>
        </span>
      </div>

      {/* Row 2: live activity counters (real-time, changes every turn) */}
      <div className={`${styles.sessionCountRow} ${styles.sessionCountLiveRow}`}>
        <span className={styles.sessionCountLiveItem} title="Supervisor monitor 总响应次数">
          Tick <strong>{tickCount}</strong>
        </span>
        <span className={styles.sessionCountDot}>·</span>
        <span
          className={styles.sessionCountLiveItem}
          title="当前 open 状态的 contract 数(主 AI 还没 discharge 的任务)"
        >
          Open <strong>{openContracts}</strong>
        </span>
        <span className={styles.sessionCountDot}>·</span>
        <span className={styles.sessionCountLiveItem} title="本次会话累计派发的 contract 数(含 retry)">
          累计 <strong>{totalIssued}</strong>
        </span>
        <span className={styles.sessionCountDot}>·</span>
        <span
          className={styles.sessionCountLiveItem}
          title="DeadlockGuard R1/R2 重推次数 — 主 AI 不响应或对话漂移触发"
        >
          重推 <strong className={totalRetried > 0 ? styles.sessionCountAccent : ''}>{totalRetried}</strong>
        </span>
        <span className={styles.sessionCountDot}>·</span>
        <span className={styles.sessionCountLiveItem} title="主 AI 完成并 discharge 的 contract 数">
          完成 <strong>{totalDischarged}</strong>
        </span>
        {totalEscalated > 0 && (
          <>
            <span className={styles.sessionCountDot}>·</span>
            <span
              className={styles.sessionCountLiveItem}
              title="R3 升级到 supervisor 决策的次数(deadlock 严重事件)"
            >
              <span className={styles.sessionCountAlert}>R3 {totalEscalated}</span>
            </span>
          </>
        )}
        <span className={styles.sessionCountSpacer} />
        {typeof lastActivityMs === 'number' && (
          <span className={styles.sessionCountLiveItem} title="距 supervisor 上次完成 turn 的时间">
            {formatAgo(lastActivityMs)} 前活跃
          </span>
        )}
        {wfIdleMs != null && (
          <>
            <span className={styles.sessionCountDot}>·</span>
            <span className={styles.sessionCountLiveItem} title="工作流静默时长 / 自动重发阈值；达阈值将自动重新下发">
              静默{' '}
              <strong className={styles.wfSilent} data-level={idleLevel(wfIdleMs, wfThresholdMs)}>
                {wfIdleMs < ACTIVE_EPS_MS ? '0:00' : formatIdle(wfIdleMs)}
                {wfThresholdMs > 0 ? ` / ${formatIdle(wfThresholdMs)}` : ''}
              </strong>
            </span>
          </>
        )}
      </div>
    </div>
  );
}

function formatAgo(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}min`;
  return `${Math.round(ms / 3_600_000)}h`;
}
