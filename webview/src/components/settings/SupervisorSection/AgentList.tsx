import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SupervisorAgent } from '../../../types/supervisorAgent';
import styles from './style.module.less';

interface AgentListProps {
  agents: SupervisorAgent[];
  selectedId: string | null;
  defaultId: string | null;
  onSelect: (id: string) => void;
  onAdd: () => void;
}

export default function AgentList({
  agents,
  selectedId,
  defaultId,
  onSelect,
  onAdd,
}: AgentListProps) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return agents;
    return agents.filter(a =>
      a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q)
    );
  }, [agents, query]);

  return (
    <div className={styles.listColumn}>
      <div className={styles.listToolbar}>
        <input
          className={styles.searchBox}
          placeholder={t('settings.supervisor.searchPlaceholder')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button
          className={styles.addButton}
          onClick={onAdd}
          title={t('settings.supervisor.newAgent')}
        >
          <span className="codicon codicon-add" />
        </button>
      </div>
      <div className={styles.list}>
        {filtered.length === 0 ? (
          <div className={styles.emptyHint}>
            {agents.length === 0
              ? t('settings.supervisor.noAgents')
              : t('settings.supervisor.noMatches')}
          </div>
        ) : (
          filtered.map((agent) => {
            const isSelected = selectedId === agent.id;
            const isDefault = defaultId === agent.id;
            return (
              <div
                key={agent.id}
                className={`${styles.listItem} ${isSelected ? styles.selected : ''}`}
                onClick={() => onSelect(agent.id)}
              >
                <span className={`codicon codicon-eye ${styles.listItemIcon}`} />
                <div className={styles.listItemBody}>
                  <div className={styles.listItemName}>{agent.name}</div>
                  <div className={styles.listItemMeta}>
                    {agent.model || 'haiku'}
                    {isDefault && (
                      <>
                        {' · '}
                        <span className={`${styles.badge} ${styles.defaultBadge}`}>
                          {t('settings.supervisor.defaultBadge')}
                        </span>
                      </>
                    )}
                    {agent.builtIn && (
                      <>
                        {' · '}
                        <span className={`${styles.badge} ${styles.builtInBadge}`}>
                          {t('settings.supervisor.builtInBadge')}
                        </span>
                      </>
                    )}
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
