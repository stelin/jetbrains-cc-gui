import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../../types';
import SubagentList from '../StatusPanel/SubagentList';
import { useSubagents } from '../../hooks/useSubagents';
import { useSupervisorMessageHelpers } from './useSupervisorMessageHelpers';
import { usePairContext } from './PairContext';
import styles from './style.module.less';

interface SupervisorStatusPanelProps {
  /** Supervisor's full message stream (used for Task subagent extraction). */
  messages: ClaudeMessage[];
}

type Tab = 'tasks' | 'subagents';

interface InjectTaskEntry {
  ts: number;
  directiveId?: string;
  objective?: string;
  status: 'pending' | 'applied' | 'lost' | 'unknown';
}

/**
 * Q3 (2026-05-24): tabbed status panel for the supervisor pane, modelled on
 * the main-AI {@code StatusPanel}. Two tabs:
 *
 * - 任务 (Tasks):   inject_prompt directives the supervisor sent to the main
 *                   AI, sourced from {@code pairStatus.recentDecisions}. Lets
 *                   the user audit "what did I tell the main AI to do".
 * - 子代理 (Subagents): SDK Task subagents the supervisor itself dispatched
 *                   (general-purpose, review_reader, etc), extracted from the
 *                   coordinator's message stream.
 *
 * The third main-AI tab (编辑 / file changes) is intentionally omitted —
 * supervisors are decision-makers and never write files directly.
 */
const SupervisorStatusPanel = memo(({ messages }: SupervisorStatusPanelProps) => {
  const { t } = useTranslation();
  const { pairStatus } = usePairContext();
  const [openPopover, setOpenPopover] = useState<Tab | null>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  // Subagent extraction — re-uses main-AI hook with supervisor-scoped adapters.
  const helpers = useSupervisorMessageHelpers(messages);
  const subagents = useSubagents({
    messages,
    getContentBlocks: helpers.getContentBlocks,
    findToolResult: helpers.findToolResult,
  });

  // inject_prompt task list, derived from recentDecisions. Each inject_prompt
  // decision becomes one task entry; we look it up by directiveId so the
  // "applied" / "lost" status from later decisions overrides the initial one.
  const tasks: InjectTaskEntry[] = useMemo(() => {
    const recent = (pairStatus as unknown as {
      recentDecisions?: Array<{
        ts: number;
        action: string;
        payload?: { directiveId?: string; objective?: string };
      }>;
    })?.recentDecisions;
    if (!Array.isArray(recent)) return [];
    const byId = new Map<string, InjectTaskEntry>();
    const noIdList: InjectTaskEntry[] = [];
    for (const d of recent) {
      if (d.action !== 'inject_prompt' && d.action !== 'retry_with_hint'
          && d.action !== 'directive_lost' && d.action !== 'directive_applied') continue;
      const directiveId = d.payload?.directiveId;
      const objective = d.payload?.objective;
      if (d.action === 'inject_prompt' || d.action === 'retry_with_hint') {
        const entry: InjectTaskEntry = {
          ts: d.ts,
          directiveId,
          objective,
          status: 'pending',
        };
        if (directiveId) byId.set(directiveId, entry);
        else noIdList.push(entry);
      } else if (d.action === 'directive_applied' && directiveId) {
        const existing = byId.get(directiveId);
        if (existing) existing.status = 'applied';
      } else if (d.action === 'directive_lost' && directiveId) {
        const existing = byId.get(directiveId);
        if (existing) existing.status = 'lost';
      }
    }
    return [...byId.values(), ...noIdList].sort((a, b) => b.ts - a.ts);
  }, [pairStatus]);

  const subagentCount = subagents.length;
  const taskCount = tasks.length;
  const pendingTaskCount = tasks.filter((t) => t.status === 'pending').length;
  const lostTaskCount = tasks.filter((t) => t.status === 'lost').length;
  const runningSubagentCount = subagents.filter((s) => s.status === 'running').length;
  const doneSubagentCount = subagents.filter((s) => s.status === 'completed').length;

  // Click-outside closes popover (matches main-AI StatusPanel behaviour).
  useEffect(() => {
    if (!openPopover) return;
    const handle = (event: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(event.target as Node)) {
        setOpenPopover(null);
      }
    };
    document.addEventListener('mousedown', handle);
    return () => document.removeEventListener('mousedown', handle);
  }, [openPopover]);

  const handleTabClick = useCallback((tab: Tab) => {
    setOpenPopover((prev) => (prev === tab ? null : tab));
  }, []);

  // Tab strip is ALWAYS rendered (matches main-AI StatusPanel behaviour: empty
  // counts hide the badge but the tab itself stays clickable). Earlier we
  // self-hid when both buckets were empty — that diverged from main AI and
  // left the supervisor pane looking inconsistent right after session start.

  const renderPopover = () => {
    if (openPopover === 'tasks') {
      if (tasks.length === 0) {
        return <div className={styles.statusEmpty}>{t('pairLayout.statusPanel.noTasks',
          { defaultValue: '尚未派发任务给主 AI。' })}</div>;
      }
      return (
        <ul className={styles.supervisorTaskList}>
          {tasks.map((task, idx) => (
            <li
              key={task.directiveId ?? `noid_${idx}`}
              className={styles.supervisorTaskItem}
              data-status={task.status}
            >
              <span className={styles.supervisorTaskStatus}>{task.status}</span>
              <span className={styles.supervisorTaskObjective}
                    title={task.objective ?? ''}>
                {task.objective || `directive ${task.directiveId?.slice(0, 8) ?? '?'}`}
              </span>
            </li>
          ))}
        </ul>
      );
    }
    if (openPopover === 'subagents') {
      return <SubagentList subagents={subagents} />;
    }
    return null;
  };

  return (
    <div className={`status-panel ${styles.supervisorStatusPanel}`} ref={popoverRef}>
      <div className="status-panel-tabs">
        <div
          className={`status-panel-tab ${openPopover === 'tasks' ? 'active' : ''}`}
          onClick={() => handleTabClick('tasks')}
        >
          <span className="codicon codicon-checklist" />
          <span className="tab-label">{t('pairLayout.statusPanel.tasksTab',
            { defaultValue: '任务' })}</span>
          {taskCount > 0 && (
            <span className="tab-progress">
              {taskCount - pendingTaskCount - lostTaskCount}/{taskCount}
            </span>
          )}
          {lostTaskCount > 0 && (
            <span className={styles.supervisorTaskBadgeLost} title="directive_lost">
              !{lostTaskCount}
            </span>
          )}
        </div>

        <div
          className={`status-panel-tab ${openPopover === 'subagents' ? 'active' : ''}`}
          onClick={() => handleTabClick('subagents')}
        >
          <span className="codicon codicon-hubot" />
          <span className="tab-label">{t('pairLayout.statusPanel.subagentsTab',
            { defaultValue: '子代理' })}</span>
          {subagentCount > 0 && (
            <span className="tab-progress">
              {doneSubagentCount}/{subagentCount}
            </span>
          )}
          {runningSubagentCount > 0 && (
            <span className="codicon codicon-loading status-panel-tab-loading" />
          )}
        </div>
      </div>

      {openPopover && (
        <div className="status-panel-popover">
          {renderPopover()}
        </div>
      )}
    </div>
  );
});

SupervisorStatusPanel.displayName = 'SupervisorStatusPanel';

export default SupervisorStatusPanel;
