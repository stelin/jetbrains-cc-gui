import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SelectedSupervisor } from '../../types/supervisorAgent';
import ActionCard, { type SupervisorActionType } from './ActionCard';
import styles from './style.module.less';

/**
 * One log entry rendered in a sub-panel. Phase A: empty arrays for live sessions;
 * dev preview can pass synthetic samples to verify rendering.
 * Phase B: populated from Event Bus stream coming through Java.
 */
export interface SupervisorLogEntry {
  id: string;
  /** kind decides icon + color */
  kind: 'think' | 'reasoning' | 'ok' | 'warn' | 'error' | 'action' | 'user';
  /** Free text content (for think/reasoning/ok/warn/error/user kinds). */
  text?: string;
  /** Action payload (only when kind === 'action'). */
  action?: {
    type: SupervisorActionType;
    summary?: string;
    detail?: string;
  };
  /**
   * Optional grouping key: entries with the same {@code groupKey} belong to
   * the same Supervisor "turn" and render inside a single message bubble.
   * (reasoning + think + action emitted from one {@code [SUPERVISOR_ACTION]}).
   */
  groupKey?: string;
}

interface SupervisorSubPanelProps {
  supervisor: SelectedSupervisor;
  entries: SupervisorLogEntry[];
  /** Counts shown in header. Optional. */
  stats?: {
    autoRecover?: number;
    escalate?: number;
  };
  /** Whether this supervisor is currently awaiting daemon output. */
  thinking?: boolean;
  defaultExpanded?: boolean;
}

/**
 * Rendering mode 1: a user-authored message bubble (right-aligned, accented).
 * Rendering mode 2: a Supervisor "turn" — natural-language reasoning text +
 *   an optional trailing action card. Multiple raw entries (think + action)
 *   are grouped into one visual bubble so the right pane reads as a chat.
 * Rendering mode 3: orphan status notes (ok/warn/error) without a turn group.
 */
type MessageGroup =
  | { kind: 'user'; id: string; text: string }
  | {
      kind: 'supervisor';
      id: string;
      /** Foldable model reasoning (SDK thinking block) */
      reasoning?: string;
      /** Visible natural-language conclusion (bubble body) */
      text: string;
      /** Trailing action card */
      action?: SupervisorLogEntry['action'];
    }
  | { kind: 'status'; id: string; iconKind: 'ok' | 'warn' | 'error'; text: string };

/**
 * Group entries into renderable messages.
 *
 * Algorithm:
 *  - user entries → one user bubble each
 *  - status (ok/warn/error) → centered note
 *  - reasoning / think / action entries: merged by {@code groupKey} so a single
 *    Supervisor turn produces ONE message bubble carrying reasoning + text + action.
 *  - For entries without groupKey (legacy / orphan), each maps to its own bubble.
 */
function groupEntries(entries: SupervisorLogEntry[]): MessageGroup[] {
  const groups: MessageGroup[] = [];
  const supervisorByKey: Record<string, MessageGroup & { kind: 'supervisor' }> = {};

  for (const e of entries) {
    if (e.kind === 'user') {
      groups.push({ kind: 'user', id: e.id, text: e.text ?? '' });
      continue;
    }
    if (e.kind === 'ok' || e.kind === 'warn' || e.kind === 'error') {
      groups.push({ kind: 'status', id: e.id, iconKind: e.kind, text: e.text ?? '' });
      continue;
    }
    if (e.kind === 'reasoning' || e.kind === 'think' || e.kind === 'action') {
      const key = e.groupKey ?? e.id;
      let bubble = supervisorByKey[key];
      if (!bubble) {
        bubble = { kind: 'supervisor', id: key, text: '' };
        supervisorByKey[key] = bubble;
        groups.push(bubble);
      }
      if (e.kind === 'reasoning') {
        bubble.reasoning = (bubble.reasoning ? bubble.reasoning + '\n' : '') + (e.text ?? '');
      } else if (e.kind === 'think') {
        bubble.text = bubble.text ? bubble.text + '\n' + (e.text ?? '') : (e.text ?? '');
      } else if (e.kind === 'action' && e.action) {
        bubble.action = e.action;
      }
    }
  }
  return groups;
}

const STATUS_ICONS: Record<'ok' | 'warn' | 'error', { icon: string; cls: string }> = {
  ok:    { icon: 'codicon-check', cls: styles.eventOk },
  warn:  { icon: 'codicon-bell',  cls: styles.eventWarn },
  error: { icon: 'codicon-error', cls: styles.eventError },
};

/**
 * Foldable Supervisor message bubble. Mirrors the main-AI message render:
 *  - "💭 Thinking" header (collapsed by default) containing the SDK reasoning
 *  - Visible conclusion text in a bubble
 *  - Optional ACTION card below the bubble
 */
function SupervisorBubble(props: {
  supervisorName: string;
  reasoning?: string;
  text: string;
  action?: SupervisorLogEntry['action'];
}) {
  const { t } = useTranslation();
  const [reasoningExpanded, setReasoningExpanded] = useState(false);
  const hasReasoning = !!props.reasoning && props.reasoning.length > 0;

  return (
    <div className={`${styles.chatMessage} ${styles.supervisorMessage}`}>
      <div className={styles.avatar} title={props.supervisorName}>
        <span className="codicon codicon-eye" />
      </div>
      <div className={styles.bubbleSupervisorWrapper}>
        {hasReasoning && (
          <div className={styles.thinkingFold}>
            <div
              className={styles.thinkingFoldHeader}
              onClick={() => setReasoningExpanded((v) => !v)}
            >
              <span className={styles.thinkingFoldTitle}>
                {t('common.thinking', 'Thinking')}
              </span>
              <span className={styles.thinkingFoldChevron}>
                {reasoningExpanded ? '▼' : '▶'}
              </span>
            </div>
            {reasoningExpanded && (
              <div className={styles.thinkingFoldBody}>
                {props.reasoning}
              </div>
            )}
          </div>
        )}
        {props.text && (
          <div className={styles.bubbleSupervisor}>{props.text}</div>
        )}
        {props.action && (
          <ActionCard
            type={props.action.type}
            summary={props.action.summary}
            detail={props.action.detail}
          />
        )}
      </div>
    </div>
  );
}

export default function SupervisorSubPanel({
  supervisor,
  entries,
  stats,
  thinking = false,
  defaultExpanded = true,
}: SupervisorSubPanelProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(defaultExpanded);

  // Elapsed-time counter for the thinking indicator.
  const [thinkingElapsedSec, setThinkingElapsedSec] = useState(0);
  useEffect(() => {
    if (!thinking) {
      setThinkingElapsedSec(0);
      return;
    }
    const start = Date.now();
    const timer = window.setInterval(() => {
      setThinkingElapsedSec(Math.floor((Date.now() - start) / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [thinking]);

  const groups = useMemo(() => groupEntries(entries), [entries]);
  const isCoordinator = supervisor.role === 'coordinator';

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

      <div className={`${styles.chatBody} ${expanded ? '' : styles.collapsed}`}>
        {groups.length === 0 && !thinking && (
          <div className={styles.placeholderHint}>
            {t('pairLayout.composerPrompt')}
          </div>
        )}

        {groups.map((g) => {
          if (g.kind === 'user') {
            return (
              <div key={g.id} className={`${styles.chatMessage} ${styles.userMessage}`}>
                <div className={styles.bubbleUser}>{g.text}</div>
                <div className={styles.avatar}>
                  <span className="codicon codicon-account" />
                </div>
              </div>
            );
          }
          if (g.kind === 'status') {
            const meta = STATUS_ICONS[g.iconKind];
            return (
              <div key={g.id} className={styles.statusNote}>
                <span className={`codicon ${meta.icon} ${meta.cls}`} />
                <span>{g.text}</span>
              </div>
            );
          }
          // supervisor
          return (
            <SupervisorBubble
              key={g.id}
              supervisorName={supervisor.name}
              reasoning={g.reasoning}
              text={g.text}
              action={g.action}
            />
          );
        })}

        {thinking && (
          <div className={`${styles.chatMessage} ${styles.supervisorMessage}`}>
            <div className={styles.avatar}>
              <span className="codicon codicon-eye" />
            </div>
            <div className={styles.bubbleSupervisorWrapper}>
              <div className={`${styles.bubbleSupervisor} ${styles.bubbleThinking}`}>
                <span className={`codicon codicon-loading codicon-modifier-spin ${styles.thinkingSpinner}`} />
                <span>{t('pairLayout.thinking', { seconds: thinkingElapsedSec })}</span>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
