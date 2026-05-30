import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  REASONING_LEVELS,
  EFFORT_SUPPORTED_CLAUDE_MODELS,
  MAX_EFFORT_CLAUDE_MODELS,
  XHIGH_EFFORT_CLAUDE_MODELS,
  strip1MContextSuffix,
  type ReasoningEffort,
} from '../types';

interface ReasoningSelectProps {
  value: ReasoningEffort;
  onChange: (effort: ReasoningEffort) => void;
  disabled?: boolean;
  selectedModel?: string;
  currentProvider?: string;
}

/**
 * ReasoningSelect - Reasoning Effort Selector
 * Visibility and available levels depend on the selected model:
 * - Codex: low/medium/high/xhigh
 * - Claude Opus 4.7: low/medium/high/xhigh/max
 * - Claude Opus 4.6 / Sonnet 4.6: low/medium/high/max
 * - Claude Haiku 4.5 / legacy models: hidden
 */
export const ReasoningSelect = ({ value, onChange, disabled, selectedModel, currentProvider }: ReasoningSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Reasoning effort depends on the model family, not the context window
  // size. Strip the [1m] suffix before consulting the support sets so the
  // picker keeps working when the 1M toggle is on — otherwise selecting 1M
  // on Opus 4.7 / Sonnet 4.6 would render `selectedModel` as
  // `claude-opus-4-7[1m]` etc., miss the set, and the selector would
  // disappear (the user reported this as "1M opened → reasoning gone").
  const normalizedModel = selectedModel ? strip1MContextSuffix(selectedModel) : selectedModel;

  const isVisible =
    currentProvider !== 'claude' ||
    !normalizedModel ||
    EFFORT_SUPPORTED_CLAUDE_MODELS.has(normalizedModel);

  const availableLevels = REASONING_LEVELS.filter(level => {
    if (currentProvider !== 'claude') {
      return level.id !== 'max';
    }
    if (!normalizedModel) return true;
    if (level.id === 'xhigh') return XHIGH_EFFORT_CLAUDE_MODELS.has(normalizedModel);
    if (level.id === 'max') return MAX_EFFORT_CLAUDE_MODELS.has(normalizedModel);
    return true;
  });

  // Fallback chain when the persisted `value` isn't available on the current
  // model: prefer 'max' first so the default 'xhigh' degrades to 'max' (not
  // 'high') on models without xhigh support like Opus 4.6 / Sonnet 4.6.
  // 'xhigh' is the secondary preference for Codex (no max but xhigh exists);
  // [length-2] keeps the historical "one notch below the top" behaviour as
  // the last resort.
  const currentLevel =
    availableLevels.find(l => l.id === value) ||
    availableLevels.find(l => l.id === 'max') ||
    availableLevels.find(l => l.id === 'xhigh') ||
    availableLevels[availableLevels.length - 2] ||
    availableLevels[0];

  useEffect(() => {
    if (!isVisible || availableLevels.some(level => level.id === value)) {
      return;
    }
    if (currentLevel) {
      onChange(currentLevel.id);
    }
  }, [availableLevels, currentLevel, isVisible, onChange, value]);

  const getReasoningText = (levelId: ReasoningEffort, field: 'label' | 'description') => {
    const key = `reasoning.${levelId}.${field}`;
    const fallback = REASONING_LEVELS.find(l => l.id === levelId)?.[field] || levelId;
    return t(key, { defaultValue: fallback });
  };

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (disabled) return;
    setIsOpen(!isOpen);
  }, [isOpen, disabled]);

  const handleSelect = useCallback((effort: ReasoningEffort) => {
    onChange(effort);
    setIsOpen(false);
  }, [onChange]);

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

  if (!isVisible) return null;

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        disabled={disabled}
        title={t('reasoning.title', { defaultValue: 'Select reasoning depth' })}
      >
        <span className="codicon codicon-lightbulb" />
        <span className="selector-button-text">{getReasoningText(currentLevel.id, 'label')}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={{ fontSize: '10px', marginLeft: '2px' }} />
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
          {availableLevels.map((level) => (
            <div
              key={level.id}
              className={`selector-option ${level.id === value ? 'selected' : ''}`}
              onClick={() => handleSelect(level.id)}
              title={getReasoningText(level.id, 'description')}
            >
              <span className={`codicon ${level.icon}`} />
              <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
                <span>{getReasoningText(level.id, 'label')}</span>
                <span className="mode-description">{getReasoningText(level.id, 'description')}</span>
              </div>
              {level.id === value && (
                <span className="codicon codicon-check check-mark" />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ReasoningSelect;
