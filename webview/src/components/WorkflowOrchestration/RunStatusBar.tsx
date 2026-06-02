import { useTranslation } from 'react-i18next';
import { useWorkflowContext } from './WorkflowContext';
import styles from './style.module.less';

interface RunStatusBarProps {
  onExpand: () => void;
}

/**
 * Non-modal strip shown while a workflow is RUNNING (ui-implementation.md §8.1).
 * Lets the user keep working inside node tabs instead of being trapped in the
 * modal dialog.
 */
export default function RunStatusBar({ onExpand }: RunStatusBarProps) {
  const { t } = useTranslation();
  const { execution, isRunning, abortWorkflow, definitions } = useWorkflowContext();

  if (!isRunning || !execution) return null;

  const nodes = Object.values(execution.nodes);
  const total = nodes.length;
  const done = nodes.filter((n) => n.status === 'DONE').length;
  const waiting = nodes.filter((n) => n.status === 'WAITING_HUMAN').length;
  const name = definitions.find((d) => d.id === execution.workflowId)?.name ?? execution.workflowId;

  const handleAbort = () => {
    if (window.confirm(t('workflow.confirmAbort', 'Abort the running workflow? Already-opened tabs are kept.'))) {
      abortWorkflow();
    }
  };

  return (
    <div className={styles.statusBar}>
      <span className="codicon codicon-sync" />
      <span className={styles.statusBarText}>
        <span className={styles.statusBarName}>{name}</span>
        {' · '}
        {t('workflow.runBar', '{{done}}/{{total}} done · concurrency {{conc}}', {
          done, total, conc: execution.concurrency,
        })}
        {waiting > 0 && (
          <span className={styles.warnPill}>
            <span className="codicon codicon-warning" /> {waiting} {t('workflow.status.waitingHuman', 'Needs you')}
          </span>
        )}
      </span>
      <span className={styles.statusBarActions}>
        <button className={styles.linkBtn} onClick={onExpand}>{t('workflow.expand', 'Expand')}</button>
        <button className={styles.dangerLinkBtn} onClick={handleAbort}>{t('workflow.abort', 'Abort')}</button>
      </span>
    </div>
  );
}
