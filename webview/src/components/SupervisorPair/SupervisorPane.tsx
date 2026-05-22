import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import SupervisorSubPanel, { type SupervisorLogEntry } from './SupervisorSubPanel';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

interface SupervisorPaneProps {
  /**
   * Per-supervisor log entries keyed by agentId.
   * Phase A: caller can pass {} (empty), each panel shows the "waiting" placeholder.
   * Phase B: populated by EventBus stream → ActionRouter response from Java.
   */
  entriesByAgentId?: Record<string, SupervisorLogEntry[]>;
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
  entriesByAgentId = {},
  status,
}: SupervisorPaneProps) {
  const { t } = useTranslation();
  const { selected, setSelected, sendUserInputToSupervisor, thinkingByAgentId } = usePairContext();
  const [draft, setDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSubmit = useCallback(() => {
    const text = draft.trim();
    if (!text) return;
    sendUserInputToSupervisor(text);
    setDraft('');
    // Re-focus for fast follow-up.
    requestAnimationFrame(() => textareaRef.current?.focus());
  }, [draft, sendUserInputToSupervisor]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter to submit, Shift+Enter for newline (mirrors main chat input default).
    // Skip submission while an IME composition is in progress (e.g. typing CJK).
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSubmit();
    }
  }, [handleSubmit]);

  // Drop a file path from the IDE editor / project tree into the composer.
  // Matches main ChatInputBox.usePasteAndDrop: unconditionally accept the
  // drag, then on drop read whichever MIME has a usable absolute path.
  const handleDragOver = useCallback((e: React.DragEvent<HTMLElement>) => {
    // Unconditional preventDefault — without this, the IDE-injected drop
    // is rejected before we ever see a `drop` event. IDEA's drag uses
    // multiple custom MIME types so we don't try to whitelist them.
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'copy';
  }, []);

  const handleDrop = useCallback((e: React.DragEvent<HTMLElement>) => {
    e.preventDefault();
    e.stopPropagation();

    // Try the most common path-carrying MIME types in order.
    // - text/plain        : IDE editor tab drags emit the absolute path here
    // - text/uri-list     : project tree drags emit file:// URIs here
    // - application/x-… : some IDEA versions emit custom types — ignore
    let raw = e.dataTransfer?.getData('text/plain') ?? '';
    if (!raw.trim()) {
      const uri = e.dataTransfer?.getData('text/uri-list') ?? '';
      if (uri.trim()) raw = uri;
    }
    raw = raw.trim();
    if (!raw) return;

    // text/uri-list may contain multiple lines; take the first non-comment one
    // and strip the file:// prefix so we end up with a real path.
    const firstLine = raw.split('\n').map((l) => l.trim()).find((l) => l && !l.startsWith('#')) ?? raw;
    const filePath = firstLine.replace(/^file:\/\//, '');

    // Add @ prefix unless already present, plus a trailing space so the
    // Supervisor LLM clearly sees it as a reference token.
    const insertion = (filePath.startsWith('@') ? filePath : `@${filePath}`) + ' ';

    const ta = textareaRef.current;
    if (!ta) {
      setDraft((prev) => prev + insertion);
      return;
    }
    const start = ta.selectionStart ?? ta.value.length;
    const end = ta.selectionEnd ?? ta.value.length;
    const before = ta.value.slice(0, start);
    const after = ta.value.slice(end);
    const nextValue = before + insertion + after;
    setDraft(nextValue);
    requestAnimationFrame(() => {
      if (textareaRef.current) {
        const caret = start + insertion.length;
        textareaRef.current.focus();
        textareaRef.current.setSelectionRange(caret, caret);
      }
    });
  }, []);

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
            entries={entriesByAgentId[coordinator.agentId] ?? []}
            thinking={thinkingByAgentId[coordinator.agentId] ?? false}
            defaultExpanded
          />
        )}
        {reviewers.map((rev) => (
          <SupervisorSubPanel
            key={rev.agentId}
            supervisor={rev}
            entries={entriesByAgentId[rev.agentId] ?? []}
            thinking={thinkingByAgentId[rev.agentId] ?? false}
            defaultExpanded={reviewers.length <= 2}
          />
        ))}
      </div>

      {/* Composer: user → Supervisor (free-form coordination message). */}
      <div
        className={styles.composer}
        // Capture drop on the wrapper too: lets the user drop anywhere in the
        // composer area, not only inside the textarea's exact bounds.
        onDragOver={handleDragOver}
        onDrop={handleDrop}
      >
        <textarea
          ref={textareaRef}
          className={styles.composerInput}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
          placeholder={t('pairLayout.composer.placeholder')}
          rows={3}
          spellCheck={false}
        />
        <div className={styles.composerActions}>
          <span className={styles.composerHint}>
            {t('pairLayout.composer.hint')}
          </span>
          <button
            className={styles.composerSendButton}
            onClick={handleSubmit}
            disabled={draft.trim().length === 0}
            title={t('pairLayout.composer.send')}
          >
            <span className="codicon codicon-send" />
          </button>
        </div>
      </div>
    </div>
  );
}
