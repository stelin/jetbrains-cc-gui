import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../../types';
import type { SelectedSupervisor } from '../../types/supervisorAgent';
import { MessageList } from '../MessageList';
import { useSupervisorMessageHelpers } from './useSupervisorMessageHelpers';
import styles from './style.module.less';

interface SupervisorSubPanelProps {
  supervisor: SelectedSupervisor;
  /** SDK-shaped conversation history for this supervisor. */
  messages: ClaudeMessage[];
  /** Counts shown in header. Optional. */
  stats?: {
    autoRecover?: number;
    escalate?: number;
  };
  /** True while the daemon is producing the current turn. */
  thinking?: boolean;
  /** True while the latest assistant message is still streaming SDK blocks. */
  streaming?: boolean;
  defaultExpanded?: boolean;
}

/**
 * A single supervisor's chat-style pane. Reuses the main-AI MessageList
 * pipeline (MessageItem → ContentBlockRenderer → tool blocks) so the
 * supervisor's text, thinking, tool calls, action card, decision card and
 * compaction notice all render with the same fidelity as the main AI dialog.
 *
 * The header (role badge, name, stats, collapse toggle) is kept lightweight
 * since the pane already lives inside the SupervisorPane container.
 */
export default function SupervisorSubPanel({
  supervisor,
  messages,
  stats,
  thinking = false,
  streaming = false,
  defaultExpanded = true,
}: SupervisorSubPanelProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(defaultExpanded);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const helpers = useSupervisorMessageHelpers(messages);
  const isCoordinator = supervisor.role === 'coordinator';

  // Stable loading start time — captured the moment `thinking` flips to true
  // and reset when it flips back. Without this, the WaitingIndicator's
  // elapsed-time counter would restart on every render.
  const [loadingStartTime, setLoadingStartTime] = useState<number | null>(null);
  useEffect(() => {
    if (thinking) {
      setLoadingStartTime((prev) => prev ?? Date.now());
    } else {
      setLoadingStartTime(null);
    }
  }, [thinking]);

  // Auto-scroll on new messages (matches main AI scroll behaviour).
  useEffect(() => {
    if (!expanded) return;
    const node = messagesEndRef.current;
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }, [messages, expanded]);

  return (
    <div className={styles.subPanel}>
      <div className={styles.subPanelHeader} onClick={() => setExpanded(!expanded)}>
        <span className={`codicon ${expanded ? 'codicon-chevron-down' : 'codicon-chevron-right'}`} />
        <span className={`${styles.subPanelRoleBadge} ${isCoordinator ? '' : styles.reviewer}`}>
          {isCoordinator ? t('pairLayout.coordinator') : t('pairLayout.reviewer')}
        </span>
        <span className={styles.subPanelName}>{supervisor.name}</span>
        {stats && (
          <span className={styles.subPanelStats}>
            {(stats.autoRecover ?? 0)}/{(stats.escalate ?? 0)}
          </span>
        )}
      </div>

      {expanded && (
        <div className={styles.supervisorMessageListWrapper}>
          {messages.length === 0 && !thinking ? (
            <div className={styles.placeholderHint}>
              {t('pairLayout.composerPrompt')}
            </div>
          ) : (
            <MessageList
              messages={messages}
              streamingActive={streaming}
              isThinking={thinking}
              loading={thinking}
              loadingStartTime={loadingStartTime}
              t={t}
              getMessageText={helpers.getMessageText}
              getContentBlocks={helpers.getContentBlocks}
              findToolResult={helpers.findToolResult}
              extractMarkdownContent={helpers.extractMarkdownContent}
              messagesEndRef={messagesEndRef}
            />
          )}
        </div>
      )}
    </div>
  );
}
