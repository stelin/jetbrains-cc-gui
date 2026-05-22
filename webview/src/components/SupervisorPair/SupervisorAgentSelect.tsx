import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
} from '../../types/supervisorAgent';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

interface SupervisorAgentSelectProps {
  /** Currently active supervisor's agentId. */
  value: string;
  /** Called when user picks a different supervisor. */
  onChange: (agent: SupervisorAgent) => void;
  /** Optional: open Settings → Supervisor tab from the dropdown footer. */
  onOpenManager?: () => void;
}

/**
 * Inline supervisor picker shown in the right-pane chat input. Visually and
 * behaviorally mirrors the main {@code ModelSelect} / {@code ReasoningSelect}:
 * a {@code .selector-button} chip that opens a {@code .selector-dropdown}
 * directly above it. No modal, no portal.
 */
export default function SupervisorAgentSelect({
  value,
  onChange,
  onOpenManager,
}: SupervisorAgentSelectProps) {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultId, setDefaultId] = useState<string | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Subscribe to supervisor-agents list pushes (chained so the settings panel
  // and SupervisorToggle in the header still receive the same updates).
  useEffect(() => {
    const previous = window.updateSupervisorAgents;
    window.updateSupervisorAgents = (jsonStr: string) => {
      previous?.(jsonStr);
      try {
        const payload: SupervisorAgentListPayload = JSON.parse(jsonStr);
        setAgents(payload.agents || []);
        setDefaultId(payload.defaultAgentId ?? null);
      } catch {
        /* ignore */
      }
    };
    sendToJava('get_supervisor_agents:');
    return () => {
      window.updateSupervisorAgents = previous;
    };
  }, []);

  // Close on outside click (mirrors ModelSelect's pattern).
  useEffect(() => {
    if (!isOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setIsOpen((v) => !v);
  }, []);

  const handlePick = useCallback(
    (agent: SupervisorAgent) => {
      onChange(agent);
      setIsOpen(false);
    },
    [onChange]
  );

  const currentName = agents.find((a) => a.id === value)?.name ?? value;

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        ref={buttonRef}
        type="button"
        className="selector-button"
        onClick={handleToggle}
        title={t('pairLayout.composer.agentChip.tooltip', 'Click to switch Supervisor')}
      >
        <span className="codicon codicon-eye" />
        <span className="selector-button-text">{currentName}</span>
        <span
          className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`}
          style={{ fontSize: '10px', marginLeft: '2px' }}
        />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{
            position: 'absolute',
            bottom: '100%',
            left: 0,
            marginBottom: '4px',
            zIndex: 10000,
          }}
        >
          {agents.length === 0 ? (
            <div className="selector-option" style={{ opacity: 0.7, cursor: 'default' }}>
              {t('chatInput.supervisor.noAgentsConfigured')}
            </div>
          ) : (
            agents.map((agent) => {
              const isSelected = agent.id === value;
              const isDefault = agent.id === defaultId;
              return (
                <div
                  key={agent.id}
                  className={`selector-option ${isSelected ? 'selected' : ''}`}
                  onClick={() => handlePick(agent)}
                  title={agent.description}
                >
                  <span className="codicon codicon-eye" />
                  <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0 }}>
                    <span>{agent.name}</span>
                    {isDefault && (
                      <span className="model-description">
                        {t('chatInput.supervisor.defaultBadge', 'default')}
                      </span>
                    )}
                  </div>
                  {isSelected && <span className="codicon codicon-check check-mark" />}
                </div>
              );
            })
          )}

          {onOpenManager && (
            <>
              <div className="selector-divider" />
              <div
                className="selector-option"
                onClick={() => {
                  onOpenManager();
                  setIsOpen(false);
                }}
              >
                <span className="codicon codicon-settings-gear" />
                <span>{t('chatInput.supervisor.manage', 'Manage agents')}</span>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
