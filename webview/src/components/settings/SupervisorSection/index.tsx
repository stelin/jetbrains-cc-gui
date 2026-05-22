import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
  SupervisorAgentOperationResult,
} from '../../../types/supervisorAgent';
import AgentList from './AgentList';
import AgentDetailForm, { type AgentDraft } from './AgentDetailForm';
import styles from './style.module.less';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

interface SupervisorSectionProps {
  onSuccess?: (message: string) => void;
  onError?: (message: string) => void;
}

const NEW_DRAFT_ID = '__new__';

export default function SupervisorSection({ onSuccess, onError }: SupervisorSectionProps) {
  const { t } = useTranslation();

  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultId, setDefaultId] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const onSuccessRef = useRef(onSuccess);
  const onErrorRef = useRef(onError);
  useEffect(() => {
    onSuccessRef.current = onSuccess;
    onErrorRef.current = onError;
  }, [onSuccess, onError]);

  // Initial load + register window callbacks
  useEffect(() => {
    const previousUpdate = window.updateSupervisorAgents;
    const previousOpResult = window.supervisorAgentOperationResult;

    window.updateSupervisorAgents = (jsonStr: string) => {
      try {
        const payload: SupervisorAgentListPayload = JSON.parse(jsonStr);
        setAgents(payload.agents || []);
        setDefaultId(payload.defaultAgentId ?? null);
        setLoaded(true);
      } catch (e) {
        // ignore malformed payload
      }
    };

    window.supervisorAgentOperationResult = (jsonStr: string) => {
      try {
        const result: SupervisorAgentOperationResult = JSON.parse(jsonStr);
        if (result.success) {
          const operation = result.operation || 'update';
          onSuccessRef.current?.(
            operation === 'add'
              ? t('settings.supervisor.toast.addSuccess')
              : operation === 'delete'
              ? t('settings.supervisor.toast.deleteSuccess')
              : t('settings.supervisor.toast.updateSuccess')
          );
        } else {
          onErrorRef.current?.(result.error || t('settings.supervisor.toast.opFailed'));
        }
      } catch (e) {
        // ignore
      }
    };

    sendToJava('get_supervisor_agents:');

    return () => {
      window.updateSupervisorAgents = previousUpdate;
      window.supervisorAgentOperationResult = previousOpResult;
    };
  }, [t]);

  const selectedAgent = useMemo(
    () => agents.find((a) => a.id === selectedId) ?? null,
    [agents, selectedId]
  );
  const isCreatingNew = selectedId === NEW_DRAFT_ID;

  const handleSelect = useCallback((id: string) => setSelectedId(id), []);
  const handleAdd = useCallback(() => setSelectedId(NEW_DRAFT_ID), []);

  const handleSave = useCallback((draft: AgentDraft) => {
    if (isCreatingNew) {
      const newId = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : 'sup_' + Date.now().toString(36);
      const payload = {
        id: newId,
        name: draft.name.trim(),
        description: draft.description,
        model: draft.model,
      };
      sendToJava(`add_supervisor_agent:${JSON.stringify(payload)}`);
      setSelectedId(newId);
    } else if (selectedAgent) {
      const payload = {
        id: selectedAgent.id,
        updates: {
          name: draft.name.trim(),
          description: draft.description,
          model: draft.model,
        },
      };
      sendToJava(`update_supervisor_agent:${JSON.stringify(payload)}`);
    }
  }, [isCreatingNew, selectedAgent]);

  const handleDelete = useCallback(() => {
    if (!selectedAgent || selectedAgent.builtIn) return;
    if (!window.confirm(t('settings.supervisor.deleteConfirm', { name: selectedAgent.name }))) {
      return;
    }
    sendToJava(`delete_supervisor_agent:${JSON.stringify({ id: selectedAgent.id })}`);
    setSelectedId(null);
  }, [selectedAgent, t]);

  const handleSetDefault = useCallback(() => {
    if (!selectedAgent) return;
    sendToJava(`set_default_supervisor_agent:${JSON.stringify({ id: selectedAgent.id })}`);
  }, [selectedAgent]);

  const handleCancel = useCallback(() => {
    if (isCreatingNew) {
      setSelectedId(null);
    } else if (selectedAgent) {
      // re-load via state reset; the form's useEffect will reset its draft on agent prop change
      setSelectedId(selectedAgent.id);
    }
  }, [isCreatingNew, selectedAgent]);

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div>
          <h3 className={styles.title}>{t('settings.supervisor.title')}</h3>
          <p className={styles.subtitle}>{t('settings.supervisor.subtitle')}</p>
        </div>
        <div className={styles.headerActions}>
          <button className={styles.addButton} onClick={handleAdd}>
            <span className="codicon codicon-add" />
            {t('settings.supervisor.newAgent')}
          </button>
        </div>
      </div>

      <div className={styles.body}>
        <AgentList
          agents={agents}
          selectedId={selectedId}
          defaultId={defaultId}
          onSelect={handleSelect}
          onAdd={handleAdd}
        />
        {selectedId ? (
          <AgentDetailForm
            agent={selectedAgent}
            isCreatingNew={isCreatingNew}
            isDefault={selectedAgent?.id === defaultId}
            onSave={handleSave}
            onDelete={handleDelete}
            onSetDefault={handleSetDefault}
            onCancel={handleCancel}
          />
        ) : (
          <div className={styles.detailColumn}>
            <div className={styles.emptyDetail}>
              <span className="codicon codicon-eye" />
              <div>
                {loaded && agents.length === 0
                  ? t('settings.supervisor.noAgentsCallToAction')
                  : t('settings.supervisor.selectAgentToEdit')}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
