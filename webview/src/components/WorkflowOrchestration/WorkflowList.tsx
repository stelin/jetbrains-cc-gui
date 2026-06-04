import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition } from './types';
import styles from './style.module.less';

interface WorkflowListProps {
  definitions: WorkflowDefinition[];
  selectedId: string | null;
  runningId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
}

export default function WorkflowList({
  definitions, selectedId, runningId, onSelect, onNew, onDelete,
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
                {runningId === def.id && (
                  <span className={styles.runningBadge}>
                    <span className={styles.runningDot} /> {t('workflow.state.running', 'Running')}
                  </span>
                )}
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
