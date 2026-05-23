import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../../types';
import SupervisorSubPanel from './SupervisorSubPanel';
import { usePairContext } from './PairContext';
import SupervisorChatInput from './SupervisorChatInput';
import styles from './style.module.less';

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
  const { selected, setSelected, thinkingByAgentId, streamingByAgentId } = usePairContext();

  if (selected.length === 0) return null;

  const coordinator = selected.find((s) => s.role === 'coordinator');
  const reviewers = selected.filter((s) => s.role === 'reviewer');

  return (
    <div className={styles.rightPane}>
      <div className={styles.paneHeader}>
        <div className={styles.headerTitle}>
          <span className={styles.statusDot} />
          <span>{t('pairLayout.paneTitle')}</span>
        </div>
        <div className={styles.headerActions}>
          <button
            className={styles.iconButton}
            title={t('pairLayout.closePane')}
            onClick={() => setSelected([])}
          >
            <span className="codicon codicon-close" />
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

      {coordinator && (
        <div className={styles.inputWrapper}>
          <SupervisorChatInput supervisor={coordinator} />
        </div>
      )}
    </div>
  );
}
