import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SupervisorAgent } from '../../../types/supervisorAgent';
import {
  DESCRIPTION_TEMPLATES,
  SUPERVISOR_MODELS,
  NAME_MAX_LENGTH,
  DESCRIPTION_MAX_LENGTH,
} from './templates';
import styles from './style.module.less';

export interface AgentDraft {
  id?: string;
  name: string;
  description: string;
  model: string;
}

interface AgentDetailFormProps {
  agent: SupervisorAgent | null;
  isCreatingNew: boolean;
  isDefault: boolean;
  onSave: (draft: AgentDraft) => void;
  onDelete: () => void;
  onSetDefault: () => void;
  onCancel: () => void;
}

const EMPTY_DRAFT: AgentDraft = {
  name: '',
  description: '',
  model: 'claude-haiku-4-5-20251001',
};

export default function AgentDetailForm({
  agent,
  isCreatingNew,
  isDefault,
  onSave,
  onDelete,
  onSetDefault,
  onCancel,
}: AgentDetailFormProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<AgentDraft>(EMPTY_DRAFT);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (isCreatingNew) {
      setDraft(EMPTY_DRAFT);
    } else if (agent) {
      setDraft({
        id: agent.id,
        name: agent.name,
        description: agent.description,
        model: agent.model || 'claude-haiku-4-5-20251001',
      });
    }
    setDirty(false);
  }, [agent, isCreatingNew]);

  const setField = <K extends keyof AgentDraft>(key: K, value: AgentDraft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setDirty(true);
  };

  const handleInsertTemplate = (body: string) => {
    setField('description', body);
  };

  const handleSave = () => {
    if (!draft.name.trim()) return;
    if (!draft.description.trim()) return;
    onSave(draft);
    setDirty(false);
  };

  const canSave =
    dirty &&
    draft.name.trim().length > 0 &&
    draft.name.trim().length <= NAME_MAX_LENGTH &&
    draft.description.trim().length > 0 &&
    draft.description.length <= DESCRIPTION_MAX_LENGTH;

  return (
    <div className={styles.detailColumn}>
      <div className={styles.detailHeader}>
        <h4 className={styles.detailTitle}>
          {isCreatingNew
            ? t('settings.supervisor.newAgent')
            : t('settings.supervisor.editAgent')}
        </h4>
        {!isCreatingNew && agent && (
          <div className={styles.detailActions}>
            {!isDefault && (
              <button
                className={styles.secondaryButton}
                onClick={onSetDefault}
                title={t('settings.supervisor.setAsDefaultTooltip')}
              >
                <span className="codicon codicon-star" /> {t('settings.supervisor.setAsDefault')}
              </button>
            )}
          </div>
        )}
      </div>

      <div className={styles.formGroup}>
        <label className={styles.formLabel}>
          {t('settings.supervisor.fields.name')}
        </label>
        <input
          className={styles.formInput}
          value={draft.name}
          maxLength={NAME_MAX_LENGTH}
          onChange={(e) => setField('name', e.target.value)}
          placeholder={t('settings.supervisor.fields.namePlaceholder')}
        />
        <div className={styles.charCount}>
          {draft.name.length} / {NAME_MAX_LENGTH}
        </div>
      </div>

      <div className={styles.formGroup}>
        <label className={styles.formLabel}>
          {t('settings.supervisor.fields.description')}
        </label>
        <div className={styles.templateBar}>
          <span className={styles.templateLabel}>
            {t('settings.supervisor.templates.insertTemplate')}:
          </span>
          {DESCRIPTION_TEMPLATES.map((tpl) => (
            <button
              key={tpl.id}
              className={styles.templateButton}
              onClick={() => handleInsertTemplate(tpl.body)}
              title={t(tpl.labelKey)}
            >
              {t(tpl.labelKey)}
            </button>
          ))}
        </div>
        <textarea
          className={styles.formTextarea}
          value={draft.description}
          maxLength={DESCRIPTION_MAX_LENGTH}
          onChange={(e) => setField('description', e.target.value)}
          placeholder={t('settings.supervisor.fields.descriptionPlaceholder')}
          spellCheck={false}
        />
        <div className={styles.charCount}>
          {draft.description.length} / {DESCRIPTION_MAX_LENGTH}
        </div>
      </div>

      <div className={styles.formGroup}>
        <label className={styles.formLabel}>
          {t('settings.supervisor.fields.model')}
        </label>
        <select
          className={styles.formSelect}
          value={draft.model}
          onChange={(e) => setField('model', e.target.value)}
        >
          {SUPERVISOR_MODELS.map((m) => (
            <option key={m.id} value={m.id}>{m.label}</option>
          ))}
        </select>
      </div>

      <div className={styles.detailFooter}>
        {!isCreatingNew && agent && !agent.builtIn && (
          <button className={styles.dangerButton} onClick={onDelete}>
            <span className="codicon codicon-trash" /> {t('common.delete')}
          </button>
        )}
        <button className={styles.secondaryButton} onClick={onCancel} disabled={!dirty && !isCreatingNew}>
          {t('common.cancel')}
        </button>
        <button className={styles.primaryButton} onClick={handleSave} disabled={!canSave}>
          {t('common.save')}
        </button>
      </div>
    </div>
  );
}
