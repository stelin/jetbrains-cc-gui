import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import SupervisorPane from './SupervisorPane';
import EscalateDialog, { type EscalateChoice } from './EscalateDialog';
import { usePairContext } from './PairContext';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

interface PairLayoutProps {
  children: ReactNode;
  status?: {
    runningStep?: number;
    totalSteps?: number;
    autoRecoverCount?: number;
    escalateCount?: number;
  };
}

/**
 * Wrap the main chat area to switch between single-pane (no supervisor) and
 * double-pane (supervisor active) layouts.
 *
 * Phase A (current): right-pane shows placeholder/waiting states.
 * Phase B: right-pane receives real-time log entries from Java.
 */
export default function PairLayout({ children, status }: PairLayoutProps) {
  const { t } = useTranslation();
  const {
    isPairActive,
    messagesByAgentId,
    pendingEscalate,
    respondToEscalate,
    dismissEscalate,
    selected,
  } = usePairContext();

  // Resizable divider: persist user's preferred right-pane width via localStorage.
  // Width is stored as % so resize-window naturally rescales both panes.
  const STORAGE_KEY = 'cc-gui.pairLayout.rightPaneWidthPct';
  const MIN_PCT = 15;
  const MAX_PCT = 70;
  const [rightWidthPct, setRightWidthPct] = useState<number>(() => {
    if (typeof window === 'undefined') return 50;
    const stored = window.localStorage.getItem(STORAGE_KEY);
    const parsed = stored ? parseFloat(stored) : NaN;
    return Number.isFinite(parsed) && parsed >= MIN_PCT && parsed <= MAX_PCT ? parsed : 50;
  });
  // Mirror the current pct in a ref so the global mouse listeners (mounted
  // once) can persist the latest value without re-binding on every tick.
  const rightWidthPctRef = useRef(rightWidthPct);
  useEffect(() => { rightWidthPctRef.current = rightWidthPct; }, [rightWidthPct]);

  const containerRef = useRef<HTMLDivElement>(null);
  const draggingRef = useRef(false);
  const [isDragging, setIsDragging] = useState(false);

  const onMouseDownDivider = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    draggingRef.current = true;
    setIsDragging(true);
    // Block text selection and switch cursor while dragging anywhere on the page.
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
  }, []);

  // Bind global mouse listeners exactly once; they read state via refs to
  // avoid the listener-thrashing pattern that made dragging feel sticky.
  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!draggingRef.current || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      if (rect.width <= 0) return;
      const relX = e.clientX - rect.left;
      const rightPxFromMouse = rect.width - relX;
      let pct = (rightPxFromMouse / rect.width) * 100;
      if (pct < MIN_PCT) pct = MIN_PCT;
      if (pct > MAX_PCT) pct = MAX_PCT;
      setRightWidthPct(pct);
    };
    const onUp = () => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      setIsDragging(false);
      document.body.style.userSelect = '';
      document.body.style.cursor = '';
      try {
        window.localStorage.setItem(STORAGE_KEY, String(rightWidthPctRef.current));
      } catch { /* ignore */ }
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, []);

  // Double-click the divider → reset to default 50 %.
  const onDoubleClickDivider = useCallback(() => {
    setRightWidthPct(50);
    try { window.localStorage.setItem(STORAGE_KEY, '50'); } catch { /* ignore */ }
  }, []);

  // Resolve which supervisor name should appear in the escalate-dialog title.
  const escalateSupervisorName =
    pendingEscalate?.supervisorName
    ?? selected.find((s) => s.agentId === pendingEscalate?.supervisorId)?.name
    ?? selected[0]?.name
    ?? 'Supervisor';

  const escalateChoices: EscalateChoice[] = pendingEscalate?.choices ?? [];

  if (!isPairActive) {
    return (
      <>
        {children}
        {pendingEscalate && (
          <EscalateDialog
            open={true}
            supervisorName={escalateSupervisorName}
            reason={pendingEscalate.reason ?? ''}
            question={pendingEscalate.question}
            choices={escalateChoices}
            stats={pendingEscalate.stats}
            steps={pendingEscalate.steps}
            onSelect={(choiceId) => respondToEscalate(choiceId)}
            onCancel={dismissEscalate}
          />
        )}
      </>
    );
  }

  return (
    <div className={styles.pairLayout} ref={containerRef}>
      <div
        className={styles.leftPane}
        // Use width % rather than flex-basis so it tracks the container as the
        // window resizes — flex-basis on a flex item plus an inner flex layout
        // double-applies the percentage and shrinks the right pane to ~16%.
        style={{ width: `${100 - rightWidthPct}%` }}
      >
        {children}
      </div>
      <div
        className={`${styles.divider} ${isDragging ? styles.dividerActive : ''}`}
        onMouseDown={onMouseDownDivider}
        onDoubleClick={onDoubleClickDivider}
        role="separator"
        aria-orientation="vertical"
        aria-valuenow={Math.round(rightWidthPct)}
        aria-valuemin={MIN_PCT}
        aria-valuemax={MAX_PCT}
        title={t('pairLayout.divider.tooltip', 'Drag to resize · double-click to reset')}
      >
        {/* Visual grip — three dots — so users see this is interactive. */}
        <span className={styles.dividerGrip} />
      </div>
      <div className={styles.rightPaneWrapper} style={{ width: `${rightWidthPct}%` }}>
        <SupervisorPane messagesByAgentId={messagesByAgentId} status={status} />
      </div>

      {pendingEscalate && (
        <EscalateDialog
          open={true}
          supervisorName={escalateSupervisorName}
          reason={pendingEscalate.reason ?? t('pairLayout.escalate.defaultReason', 'Decision required')}
          question={pendingEscalate.question}
          choices={escalateChoices}
          stats={pendingEscalate.stats}
          steps={pendingEscalate.steps}
          onSelect={(choiceId) => respondToEscalate(choiceId)}
          onCancel={dismissEscalate}
        />
      )}
    </div>
  );
}
