import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition, WorkflowState } from './types';
import styles from './style.module.less';

interface WorkflowListProps {
  definitions: WorkflowDefinition[];
  selectedId: string | null;
  /** Per-workflow latest execution state (id → state), for status badges. */
  statuses: Record<string, WorkflowState>;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
}

/** Status → badge (icon + class + i18n). Editing/undefined renders no badge. */
function statusBadge(st: WorkflowState | undefined): { cls: string; icon: string; i18nKey: string; fallback: string } | null {
  switch (st) {
    case 'RUNNING':   return { cls: 'runningBadge',   icon: '',                    i18nKey: 'workflow.state.running',   fallback: 'Running' };
    case 'PAUSED':    return { cls: 'pausedBadge',    icon: 'codicon-debug-pause', i18nKey: 'workflow.state.paused',    fallback: 'Paused' };
    case 'COMPLETED': return { cls: 'completedBadge', icon: 'codicon-check',       i18nKey: 'workflow.state.completed', fallback: 'Completed' };
    case 'ABORTED':   return { cls: 'abortedBadge',   icon: 'codicon-circle-slash',i18nKey: 'workflow.state.aborted',   fallback: 'Aborted' };
    default:          return null;
  }
}

export default function WorkflowList({
  definitions, selectedId, statuses, onSelect, onNew, onDelete,
}: WorkflowListProps) {
  const { t } = useTranslation();
  return (
    <div className={styles.list}>
      <div className={styles.listScroll}>
        {definitions.length === 0 && (
          <div className={styles.listEmpty}>{t('workflow.noWorkflows', 'No workflows yet')}</div>
        )}
        {definitions.map((def) => (
          <div
            key={def.id}
            className={`${styles.listItem} ${selectedId === def.id ? styles.listItemActive : ''}`}
            onClick={() => onSelect(def.id)}
          >
            <div className={styles.listItemMain}>
              <div className={styles.listItemName}>{def.name}</div>
              <div className={styles.listItemMeta}>
                {(() => {
                  const b = statusBadge(statuses[def.id]);
                  if (!b) return null;
                  return (
                    <span className={styles[b.cls]}>
                      {b.cls === 'runningBadge'
                        ? <span className={styles.runningDot} />
                        : <span className={`codicon ${b.icon}`} />}
                      {' '}{t(b.i18nKey, b.fallback)}
                    </span>
                  );
                })()}
                {t('workflow.nodeCount', '{{n}} nodes', { n: def.nodes.length })}
              </div>
            </div>
            <button
              className={styles.listDeleteBtn}
              title={t('common.delete', 'Delete')}
              onClick={(e) => { e.stopPropagation(); onDelete(def.id); }}
            >
              <span className="codicon codicon-trash" />
            </button>
          </div>
        ))}
      </div>
      <button className={styles.newBtn} onClick={onNew}>
        <span className="codicon codicon-add" /> {t('workflow.newWorkflow', 'New workflow')}
      </button>
    </div>
  );
}
