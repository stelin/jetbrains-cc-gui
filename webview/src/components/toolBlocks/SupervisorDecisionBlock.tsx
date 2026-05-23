import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ToolInput, ToolResultBlock } from '../../types';

/**
 * Renders a single supervisor self-decision record as a foldable card.
 * Dispatched by ContentBlockRenderer when {@code block.name ===
 * 'mcp__supervisor__decision_record'}; the decision payload (step / category
 * / plan_excerpt / ambiguity / choice / rationale / scope / review_flag) is
 * carried verbatim on {@code block.input}.
 *
 * A {@code review_flag === true} decision (grey-zone category B call) gets a
 * warning border so the human reviewer can spot it later when scrubbing the
 * session.
 */

interface SupervisorDecisionBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  toolId?: string;
}

interface Decision {
  step: number;
  category: 'A' | 'B' | string;
  plan_excerpt?: string;
  ambiguity?: string;
  choice?: string;
  rationale?: string;
  scope?: string;
  review_flag?: boolean;
}

function pickDecision(input: ToolInput | undefined): Decision | null {
  if (!input || typeof input !== 'object') return null;
  const d = input as Record<string, unknown>;
  if (typeof d.step !== 'number') return null;
  return {
    step: d.step,
    category: (typeof d.category === 'string' ? d.category : 'A') as Decision['category'],
    plan_excerpt: typeof d.plan_excerpt === 'string' ? d.plan_excerpt : '',
    ambiguity: typeof d.ambiguity === 'string' ? d.ambiguity : '',
    choice: typeof d.choice === 'string' ? d.choice : '',
    rationale: typeof d.rationale === 'string' ? d.rationale : '',
    scope: typeof d.scope === 'string' ? d.scope : '',
    review_flag: d.review_flag === true,
  };
}

const SupervisorDecisionBlock = ({ input }: SupervisorDecisionBlockProps) => {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const decision = pickDecision(input);
  if (!decision) return null;

  const flag = decision.review_flag === true;
  const scopeLabel = decision.scope
    ? t(`pairLayout.decision.scopeValue.${decision.scope}`, { defaultValue: decision.scope })
    : '';
  const categoryLabel = t(`pairLayout.decision.category.${decision.category}`, {
    defaultValue: decision.category,
  });

  return (
    <div className={`supervisor-decision-card ${flag ? 'supervisor-decision-flagged' : ''}`}>
      <div className="supervisor-decision-head" onClick={() => setExpanded((v) => !v)}>
        <span className="supervisor-decision-icon">{flag ? '⚠️' : '📝'}</span>
        <span className="supervisor-decision-step">
          {t('pairLayout.decision.step', { n: decision.step, defaultValue: `Step ${decision.step}` })}
        </span>
        <span className={`supervisor-decision-badge ${flag ? 'supervisor-decision-badge-flag' : ''}`}>
          {categoryLabel}
        </span>
        <span className="supervisor-decision-choice" title={decision.choice}>
          {decision.choice}
        </span>
        <span className="supervisor-decision-chevron">{expanded ? '▼' : '▶'}</span>
      </div>
      {expanded && (
        <div className="supervisor-decision-body">
          <DecisionField
            label={t('pairLayout.decision.planExcerpt', 'Plan excerpt')}
            value={decision.plan_excerpt}
          />
          <DecisionField
            label={t('pairLayout.decision.ambiguity', 'Ambiguity')}
            value={decision.ambiguity}
          />
          <DecisionField
            label={t('pairLayout.decision.choice', 'Choice')}
            value={decision.choice}
          />
          <DecisionField
            label={t('pairLayout.decision.rationale', 'Rationale')}
            value={decision.rationale}
          />
          <DecisionField
            label={t('pairLayout.decision.scope', 'Scope')}
            value={scopeLabel}
          />
          {flag && (
            <div className="supervisor-decision-flag-note">
              <span className="codicon codicon-warning" />
              <span>
                {t('pairLayout.decision.reviewFlag', 'Marked for human review.')}
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

function DecisionField({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div className="supervisor-decision-row">
      <span className="supervisor-decision-field-label">{label}</span>
      <span className="supervisor-decision-field-value">{value}</span>
    </div>
  );
}

export default SupervisorDecisionBlock;
