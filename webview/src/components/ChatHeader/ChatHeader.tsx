import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';

import { BackIcon } from '../Icons';
import { usePairContext } from '../SupervisorPair/PairContext';

export interface ChatHeaderProps {
  currentView: 'chat' | 'history' | 'settings' | 'workflow';
  sessionTitle: string;
  t: TFunction;
  onBack: () => void;
  onNewSession: () => void;
  onNewTab: () => void;
  onHistory: () => void;
  onSettings: () => void;
  onTitleChange?: (newTitle: string) => void;
  titleEditable?: boolean;
  /** Open the supervisor workflow orchestration dialog (header toolbar button). */
  onOpenWorkflow?: () => void;
  /**
   * Open a brand-new tab that is born as a supervisor session ("新监督者标签页").
   * Supervised sessions only ever live in their own dedicated tab — a normal
   * chat can no longer be converted to supervised in place — so this always
   * creates a fresh tab (the agent picker then auto-opens inside it). Always
   * enabled regardless of the current tab's kind.
   */
  onNewSupervised?: () => void;
  /**
   * Whether the current tab has any messages. Reserved for the session-kind
   * guard; currently informational only.
   */
  hasMessages?: boolean;
}

export function ChatHeader({
  currentView,
  sessionTitle,
  t,
  onBack,
  onNewSession,
  onNewTab,
  onHistory,
  onSettings,
  onTitleChange,
  titleEditable = false,
  onOpenWorkflow,
  onNewSupervised,
}: ChatHeaderProps): React.ReactElement | null {
  // Session-kind refactor: this webview is one tab; isPairActive tells whether
  // the current tab is a supervised session.
  const { isPairActive } = usePairContext();
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState('');
  // Guidance popup shown when the user clicks the new-session button that does
  // not match the current tab's kind.
  const [guidance, setGuidance] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!titleEditable) {
      setEditing(false);
    }
  }, [titleEditable]);

  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [editing]);

  const startEditing = useCallback(() => {
    if (!titleEditable || !onTitleChange) return;
    setEditValue(sessionTitle);
    setEditing(true);
  }, [titleEditable, onTitleChange, sessionTitle]);

  const commitEdit = useCallback(() => {
    setEditing(false);
    const trimmed = editValue.trim().slice(0, 50);
    if (trimmed && trimmed !== sessionTitle && onTitleChange) {
      onTitleChange(trimmed);
    }
  }, [editValue, sessionTitle, onTitleChange]);

  const cancelEdit = useCallback(() => {
    setEditing(false);
  }, []);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      commitEdit();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      cancelEdit();
    }
  }, [commitEdit, cancelEdit]);

  const handleBlur = useCallback((e: React.FocusEvent<HTMLInputElement>) => {
    // If focus moves to save/cancel button inside edit container, let that button handle it
    const editContainer = e.currentTarget.closest('.session-title-edit-mode');
    if (editContainer && editContainer.contains(e.relatedTarget as Node)) {
      return;
    }
    commitEdit();
  }, [commitEdit]);

  // A tab is bound to its session kind (born-at-birth): a supervised tab can't
  // create a plain session in place. New supervised sessions always open in
  // their own dedicated tab via the "新监督者标签页" button below.
  const newSessionBlocked = isPairActive;                       // supervised tab → no normal here

  const handleNewSessionClick = useCallback(() => {
    if (isPairActive) {
      setGuidance(t('header.kindGuard.supervisedTab',
        '当前是监督者会话。监督者会话与普通会话是两类独立会话，不能在同一标签里互相切换。如需普通会话，请点右侧的"新建标签页"开一个新标签。'));
      return;
    }
    onNewSession();
  }, [isPairActive, onNewSession, t]);

  if (currentView === 'settings' || currentView === 'workflow') {
    return null;
  }

  return (
    <>
    <div className="header">
      <div className="header-left">
        {currentView === 'history' ? (
          <button className="back-button" onClick={onBack} data-tooltip={t('common.back')}>
            <BackIcon /> {t('common.back')}
          </button>
        ) : editing ? (
          <div className="session-title-edit-mode" onClick={(e) => e.stopPropagation()}>
            <input
              ref={inputRef}
              type="text"
              className="session-title-input"
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onKeyDown={handleKeyDown}
              onBlur={handleBlur}
              maxLength={50}
              spellCheck={false}
              aria-label="Session title"
            />
            <button className="session-title-save-btn" onClick={commitEdit} aria-label="Save title">
              <span className="codicon codicon-check" />
            </button>
            <button className="session-title-cancel-btn" onClick={cancelEdit} aria-label="Cancel editing">
              <span className="codicon codicon-close" />
            </button>
          </div>
        ) : (
          <div className="session-title-wrapper">
            <div className="session-title">
              {sessionTitle}
            </div>
            {titleEditable && (
              <button className="session-title-edit-btn" onClick={startEditing} aria-label="Edit session title">
                <span className="codicon codicon-edit" />
              </button>
            )}
          </div>
        )}
      </div>
      <div className="header-right">
        {currentView === 'chat' && (
          <>
            <button
              className="icon-button"
              onClick={handleNewSessionClick}
              data-tooltip={t('common.newSession')}
              style={newSessionBlocked ? { opacity: 0.45 } : undefined}
            >
              <span className="codicon codicon-plus" />
            </button>
            {onNewSupervised && (
              <button
                className="icon-button"
                onClick={onNewSupervised}
                data-tooltip={t('common.newSupervisedTab', '新监督者标签页')}
              >
                <span className="codicon codicon-eye" />
              </button>
            )}
            <button
              className="icon-button"
              onClick={onNewTab}
              data-tooltip={t('common.newTab')}
            >
              <span className="codicon codicon-split-horizontal" />
            </button>
            <button
              className="icon-button"
              onClick={onHistory}
              data-tooltip={t('common.history')}
            >
              <span className="codicon codicon-history" />
            </button>
            {onOpenWorkflow && (
              <button
                className="icon-button"
                onClick={onOpenWorkflow}
                data-tooltip={t('workflow.entryTooltip', '监督者编排工作流')}
              >
                <span className="codicon codicon-git-merge" />
              </button>
            )}
            <button
              className="icon-button"
              onClick={onSettings}
              data-tooltip={t('common.settings')}
            >
              <span className="codicon codicon-settings-gear" />
            </button>
          </>
        )}
      </div>
    </div>
    {guidance && (
      <div className="modal-overlay" onClick={() => setGuidance(null)}>
        <div className="modal-content" onClick={(e) => e.stopPropagation()}>
          <h3>{t('header.kindGuard.title', '操作受限')}</h3>
          <p style={{ lineHeight: 1.6 }}>{guidance}</p>
          <div className="modal-actions">
            <button className="modal-btn modal-btn-cancel" onClick={() => setGuidance(null)}>
              {t('common.gotIt', '我知道了')}
            </button>
            <button
              className="modal-btn"
              style={{ background: 'var(--vscode-button-background, #0e639c)', color: 'var(--vscode-button-foreground, #fff)' }}
              onClick={() => { setGuidance(null); onNewTab(); }}
            >
              {t('header.kindGuard.openNewTab', '新建标签页')}
            </button>
          </div>
        </div>
      </div>
    )}
    </>
  );
}
