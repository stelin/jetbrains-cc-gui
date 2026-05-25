import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  RotationConfig,
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

// 2026-05-24: rotation-trigger thresholds. Supervisor + main AI share
// these defaults; the Java side reloads on every health-check tick so
// updates take effect without restarting any pair.
const DEFAULT_ROTATION_CONFIG: RotationConfig = {
  softRatio: 0.85,
  hardRatio: 0.95,
  softCompact: 3,
  hardCompact: 5,
};

export default function SupervisorSection({ onSuccess, onError }: SupervisorSectionProps) {
  const { t } = useTranslation();

  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultId, setDefaultId] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // v3: global auto-compact threshold (% of context window). Drives the
  // CLAUDE_AUTOCOMPACT_PCT_OVERRIDE env on the daemon — applies to both the
  // supervisor and the main AI sharing the same daemon process.
  const [autoCompactThreshold, setAutoCompactThreshold] = useState(70);
  const [rotationConfig, setRotationConfig] = useState<RotationConfig>(DEFAULT_ROTATION_CONFIG);

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
        if (typeof payload.autoCompactThreshold === 'number') {
          setAutoCompactThreshold(payload.autoCompactThreshold);
        }
        if (payload.rotationConfig) {
          setRotationConfig({
            softRatio: payload.rotationConfig.softRatio ?? DEFAULT_ROTATION_CONFIG.softRatio,
            hardRatio: payload.rotationConfig.hardRatio ?? DEFAULT_ROTATION_CONFIG.hardRatio,
            softCompact: payload.rotationConfig.softCompact ?? DEFAULT_ROTATION_CONFIG.softCompact,
            hardCompact: payload.rotationConfig.hardCompact ?? DEFAULT_ROTATION_CONFIG.hardCompact,
          });
        }
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

  // v3 threshold control. Debounced send to avoid flooding Java on every
  // slider tick — we wait for the user to stop dragging for 300ms.
  const persistThresholdTimer = useRef<number | null>(null);
  const handleThresholdChange = useCallback((next: number) => {
    setAutoCompactThreshold(next);
    if (persistThresholdTimer.current) {
      window.clearTimeout(persistThresholdTimer.current);
    }
    persistThresholdTimer.current = window.setTimeout(() => {
      sendToJava(`pair_set_auto_compact_threshold:${JSON.stringify({ threshold: next })}`);
      persistThresholdTimer.current = null;
    }, 300);
  }, []);

  // 2026-05-24: debounced rotation-config persistence. Local UI state
  // updates instantly; the backend write fires after 300ms of inactivity.
  // We send the whole object every time because Java's validate() runs
  // cross-field checks (soft < hard ordering).
  const persistRotationTimer = useRef<number | null>(null);
  const queueRotationConfigSave = useCallback((next: RotationConfig) => {
    if (persistRotationTimer.current) {
      window.clearTimeout(persistRotationTimer.current);
    }
    persistRotationTimer.current = window.setTimeout(() => {
      sendToJava(`set_rotation_config:${JSON.stringify(next)}`);
      persistRotationTimer.current = null;
    }, 300);
  }, []);

  const handleRotationField = useCallback(
    (field: keyof RotationConfig, value: number) => {
      setRotationConfig((prev) => {
        const next = { ...prev, [field]: value };
        queueRotationConfigSave(next);
        return next;
      });
    },
    [queueRotationConfigSave]
  );

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

      <div className={styles.globalSettings}>
        <div className={styles.globalSettingsLabel}>
          <div className={styles.globalSettingsTitle}>
            {t('settings.supervisor.autoCompact.title', '对话历史压缩阈值')}
          </div>
          <div className={styles.globalSettingsHint}>
            {t('settings.supervisor.autoCompact.hint',
               '上下文用量超过该百分比时，Claude Code 会自动压缩对话历史。该设置影响所有 Claude 会话（包括主 AI 与监督者）。')}
          </div>
        </div>
        <div className={styles.globalSettingsControl}>
          <input
            type="range"
            min={50}
            max={95}
            step={5}
            value={autoCompactThreshold}
            onChange={(e) => handleThresholdChange(Number(e.target.value))}
            className={styles.globalSettingsRange}
          />
          <span className={styles.globalSettingsValue}>{autoCompactThreshold}%</span>
        </div>
      </div>

      <div className={styles.globalSettings}>
        <div className={styles.globalSettingsLabel}>
          <div className={styles.globalSettingsTitle}>
            {t('settings.supervisor.rotation.title', '自动 Rotation 触发阈值')}
          </div>
          <div className={styles.globalSettingsHint}>
            {t('settings.supervisor.rotation.hint',
               '上下文用量比例或压缩次数达到阈值时，监督者会话与主 AI 会话都会自动 rotate（创建新会话 + 通过 handoff 文档承接上下文）。soft 仅记录 WARN 告警，hard 记录 ERROR；冷却时间统一 5 分钟。')}
          </div>
        </div>
        <div className={styles.globalSettingsControl} style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <label style={{ minWidth: 110 }}>
              {t('settings.supervisor.rotation.softRatio', 'Soft ratio')}
            </label>
            <input
              type="number"
              min={0.3}
              max={1}
              step={0.05}
              value={rotationConfig.softRatio}
              onChange={(e) => handleRotationField('softRatio', Number(e.target.value))}
              style={{ width: 80 }}
            />
            <label style={{ minWidth: 110, marginLeft: 16 }}>
              {t('settings.supervisor.rotation.hardRatio', 'Hard ratio')}
            </label>
            <input
              type="number"
              min={0.3}
              max={1}
              step={0.05}
              value={rotationConfig.hardRatio}
              onChange={(e) => handleRotationField('hardRatio', Number(e.target.value))}
              style={{ width: 80 }}
            />
          </div>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <label style={{ minWidth: 110 }}>
              {t('settings.supervisor.rotation.softCompact', 'Soft compact')}
            </label>
            <input
              type="number"
              min={1}
              max={50}
              step={1}
              value={rotationConfig.softCompact}
              onChange={(e) => handleRotationField('softCompact', Number(e.target.value))}
              style={{ width: 80 }}
            />
            <label style={{ minWidth: 110, marginLeft: 16 }}>
              {t('settings.supervisor.rotation.hardCompact', 'Hard compact')}
            </label>
            <input
              type="number"
              min={1}
              max={50}
              step={1}
              value={rotationConfig.hardCompact}
              onChange={(e) => handleRotationField('hardCompact', Number(e.target.value))}
              style={{ width: 80 }}
            />
          </div>
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
