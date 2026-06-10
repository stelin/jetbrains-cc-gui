import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../../types';
import SupervisorSubPanel from './SupervisorSubPanel';
import { usePairContext } from './PairContext';
import SupervisorChatInput from './SupervisorChatInput';
import PairStatusBar from './PairStatusBar';
// Phase 5 (2026-05-24): autonomy-mode UI surfaces.
import AutonomyToggle from './AutonomyToggle';
import DecisionTimeline from './DecisionTimeline';
import AlertNotifier from './AlertNotifier';
// Periodic-event strip (supervisor monitor heartbeat etc.). Lives between the
// autonomy controls and the decision timeline so non-actionable notices don't
// pollute the supervisor chat.
import PeriodicNoticeStrip from './PeriodicNoticeStrip';
// 2026-05-25: rotation/compaction counters for both sides; sits between
// AutonomyToggle and PeriodicNoticeStrip.
import CoordinatorEventStrip from './CoordinatorEventStrip';
import SessionCountStrip from './SessionCountStrip';
import SupervisorTimeStrip from './SupervisorTimeStrip';
// Q3 (2026-05-24): tabbed status panel mirroring main-AI StatusPanel (任务 / 子代理).
import SupervisorStatusPanel from './SupervisorStatusPanel';
// 2026-05-25 (FUNDAMENTAL FIX): manual interrupt button replaces wall-clock auto-cancel.
import { sendBridgeEvent } from '../../utils/bridge';
import { focusChatInput } from '../../utils/chatInputDropRouter';
import styles from './style.module.less';
// Global StatusPanel chrome (tabs + popover container) re-used by the
// supervisor side so the visual treatment matches main AI's pane exactly.
import '../StatusPanel/StatusPanel.less';

interface SupervisorPaneProps {
  /**
   * Per-supervisor message stream keyed by agentId. Each list is rendered
   * through the main-AI MessageList pipeline.
   */
  messagesByAgentId?: Record<string, ClaudeMessage[]>;
  /** Global pair stats shown at the top (e.g. step progress, auto-recover counts). */
  status?: {
    runningStep?: number;
    totalSteps?: number;
    autoRecoverCount?: number;
    escalateCount?: number;
    elapsedSeconds?: number;
  };
}

export default function SupervisorPane({
  messagesByAgentId = {},
  status,
}: SupervisorPaneProps) {
  const { t } = useTranslation();
  const { selected, thinkingByAgentId, streamingByAgentId, pairId, pairStatus } = usePairContext();

  if (selected.length === 0) return null;

  const coordinator = selected.find((s) => s.role === 'coordinator');
  const reviewers = selected.filter((s) => s.role === 'reviewer');

  // 2026-05-25 (FUNDAMENTAL FIX): "supervisor is currently producing a turn"
  // — drives whether the Stop button is enabled. We check the coordinator's
  // thinking flag (set true when EventBus.forward enters; cleared on action
  // dispatch or transport_error). Streaming alone is not enough — between
  // SDK frames the streaming flag may dip while the turn is still active.
  const coordinatorBusy = Boolean(
    coordinator && (thinkingByAgentId[coordinator.agentId]
      || streamingByAgentId[coordinator.agentId])
  );

  // 2026-05-28: the Stop button must be enabled across the whole window where a
  // pause is meaningful — including "the supervisor finished its turn but the
  // Liveness guard is still counting down toward a system_takeover", which the
  // thinking/streaming flags miss. The authoritative signal is the plan state:
  // while ACTIVE the supervisor may act OR the guard may fire, so interrupting
  // is meaningful; WAITING (already paused) / DONE / ABORTED have nothing to
  // stop. Fall back to the old thinking||streaming heuristic when planState is
  // unknown (no plan yet, or a pre-2026-05-28 Java build that doesn't push it).
  const planState = pairStatus?.planState;
  const interruptible = planState !== undefined ? planState === 'ACTIVE' : coordinatorBusy;

  const handleInterruptSupervisor = () => {
    if (!pairId) return;
    sendBridgeEvent('pair_supervisor_interrupt', JSON.stringify({ pairId }));
    // Pausing means "stop so I can add context" — pull focus into the composer
    // so the user can start typing their supplement right away.
    requestAnimationFrame(() => focusChatInput('supervisor'));
  };

  return (
    <div className={styles.rightPane}>
      <div className={styles.paneHeader}>
        <div className={styles.headerTitle}>
          <span className={styles.statusDot} />
          <span>{t('pairLayout.paneTitle')}</span>
        </div>
        <div className={styles.headerActions}>
          {/* 2026-05-25 (FUNDAMENTAL FIX): manual interrupt. 2026-05-28: gated on
              plan ACTIVE (see `interruptible`) so the user can also pause during
              the post-turn Liveness-guard countdown, not just while the
              supervisor is visibly thinking/streaming. */}
          {/* Session-kind refactor: the close button is gone — a supervised
              session is supervised-by-birth, so the pane is intrinsic to it
              (closing = closing the whole session/tab). Interrupt (and
              server-side rotation) remain. */}
          <button
            className={styles.iconButton}
            title={t('pairLayout.interruptSupervisor',
              { defaultValue: '中断 Supervisor 当前轮' })}
            onClick={handleInterruptSupervisor}
            disabled={!interruptible || !pairId}
          >
            <span className="codicon codicon-debug-stop" />
          </button>
        </div>
      </div>

      {status && (
        <div className={styles.paneToolbar}>
          {typeof status.runningStep === 'number' && typeof status.totalSteps === 'number' && (
            <span>
              {t('pairLayout.stepProgress', { current: status.runningStep, total: status.totalSteps })}
            </span>
          )}
          {typeof status.autoRecoverCount === 'number' && (
            <span>· {t('pairLayout.stats.autoRecover', { count: status.autoRecoverCount })}</span>
          )}
          {typeof status.escalateCount === 'number' && (
            <span>· {t('pairLayout.stats.escalate', { count: status.escalateCount })}</span>
          )}
        </div>
      )}

      {/* Phase 2 (2026-05-24): supervisor monitor + context-usage status bar. */}
      <PairStatusBar />

      {/* Phase 5 (2026-05-24): autonomy controls + decision timeline. Both
          self-hide when pair isn't running or has no data. */}
      <div className={styles.autonomyControls}>
        <AutonomyToggle />
      </div>
      <CoordinatorEventStrip />
      <SessionCountStrip />
      <SupervisorTimeStrip />
      <PeriodicNoticeStrip />
      <DecisionTimeline />

      <div className={styles.subPanelList}>
        {coordinator && (
          <SupervisorSubPanel
            key={coordinator.agentId}
            supervisor={coordinator}
            messages={messagesByAgentId[coordinator.agentId] ?? []}
            thinking={thinkingByAgentId[coordinator.agentId] ?? false}
            streaming={streamingByAgentId[coordinator.agentId] ?? false}
            defaultExpanded
          />
        )}
        {reviewers.map((rev) => (
          <SupervisorSubPanel
            key={rev.agentId}
            supervisor={rev}
            messages={messagesByAgentId[rev.agentId] ?? []}
            thinking={thinkingByAgentId[rev.agentId] ?? false}
            streaming={streamingByAgentId[rev.agentId] ?? false}
            defaultExpanded={reviewers.length <= 2}
          />
        ))}
      </div>

      {/* Q3 (2026-05-24): tab strip showing supervisor's tasks + subagents.
          Position mirrors main-AI StatusPanel: sits between the chat scroll
          area and the input box. Tabs always render (matches main-AI), badges
          appear only when the bucket has data. */}
      {coordinator && (
        <SupervisorStatusPanel
          messages={messagesByAgentId[coordinator.agentId] ?? []}
        />
      )}

      {coordinator && (
        <div className={styles.inputWrapper}>
          <SupervisorChatInput supervisor={coordinator} />
        </div>
      )}

      {/* Non-blocking toast layer for record_alert events. Lives inside the
          pane so it's torn down with the pair (no stale listeners). */}
      <AlertNotifier />
    </div>
  );
}
