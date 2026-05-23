import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { ToolInput, ToolResultBlock } from '../../types';
import MarkdownBlock from '../MarkdownBlock';

/**
 * Renders the supervisor's emit_action call as an inline card on the chat
 * stream. The action's full payload (the prompt being injected, the question
 * being escalated, the retry hint, etc.) is preserved verbatim and surfaced
 * via an expandable section — replacing the previous 60-character summary
 * that hid the actual instructions sent to the main AI.
 *
 * Dispatched by ContentBlockRenderer when {@code block.name ===
 * 'mcp__supervisor__emit_action'}.
 */

type ActionType = 'inject_prompt' | 'retry_with_hint' | 'approve_and_continue' | 'escalate_to_human' | 'wait' | string;

interface SupervisorActionBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  toolId?: string;
}

interface RenderedAction {
  /** Short label shown in the card header (translated). */
  label: string;
  /** Compact one-line summary shown next to the label. */
  headline: string;
  /** Full content rendered in the expanded section (markdown). May be empty. */
  fullText: string;
  /** Visual variant class — drives card border/background colour. */
  variant: 'inject' | 'retry' | 'approve' | 'escalate' | 'wait';
  /** Codicon name for the card icon. */
  icon: string;
}

function asString(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function renderAction(
  type: ActionType,
  payload: Record<string, unknown>,
  reason: string,
  t: TFunction
): RenderedAction {
  const trim = (s: string, n = 80) => (s.length > n ? s.slice(0, n) + '…' : s);

  if (type === 'inject_prompt') {
    const prompt = asString(payload.prompt);
    return {
      label: t('pairLayout.action.inject', { defaultValue: 'Inject prompt' }),
      headline: trim(prompt) || reason,
      fullText: prompt,
      variant: 'inject',
      icon: 'codicon-arrow-left',
    };
  }
  if (type === 'retry_with_hint') {
    const hint = asString(payload.hint) || asString(payload.message);
    const wait = typeof payload.wait_seconds === 'number' ? payload.wait_seconds : undefined;
    const headlineParts: string[] = [];
    if (typeof wait === 'number') headlineParts.push(`wait ${wait}s`);
    if (hint) headlineParts.push(trim(hint, 60));
    return {
      label: t('pairLayout.action.retry', { defaultValue: 'Retry with hint' }),
      headline: headlineParts.join(' · ') || reason || 'auto-recover',
      fullText: hint,
      variant: 'retry',
      icon: 'codicon-refresh',
    };
  }
  if (type === 'escalate_to_human') {
    const question = asString(payload.question) || asString(payload.proposal);
    const choices = Array.isArray(payload.choices) ? payload.choices : [];
    let detail = question;
    if (choices.length > 0) {
      const lines = choices.map((c, idx) => {
        if (typeof c === 'string') return `${String.fromCharCode(65 + idx)}. ${c}`;
        const obj = c as { id?: string; label?: string; description?: string };
        const id = obj.id || String.fromCharCode(65 + idx);
        const label = obj.label || '';
        return obj.description ? `${id}. ${label} — ${obj.description}` : `${id}. ${label}`;
      });
      detail = (detail ? detail + '\n\n' : '') + lines.join('\n');
    }
    return {
      label: t('pairLayout.action.escalate', { defaultValue: 'Escalate to human' }),
      headline: trim(question) || reason,
      fullText: detail,
      variant: 'escalate',
      icon: 'codicon-warning',
    };
  }
  if (type === 'approve_and_continue') {
    return {
      label: t('pairLayout.action.approve', { defaultValue: 'Approve and continue' }),
      headline: reason,
      fullText: '',
      variant: 'approve',
      icon: 'codicon-check',
    };
  }
  if (type === 'wait') {
    const wait = typeof payload.wait_seconds === 'number' ? payload.wait_seconds : undefined;
    return {
      label: t('pairLayout.action.wait', { defaultValue: 'Wait' }),
      headline: wait ? `wait ${wait}s` : reason,
      fullText: '',
      variant: 'wait',
      icon: 'codicon-watch',
    };
  }
  return {
    label: type,
    headline: reason,
    fullText: '',
    variant: 'approve',
    icon: 'codicon-symbol-event',
  };
}

const SupervisorActionBlock = ({ input }: SupervisorActionBlockProps) => {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const type = asString(input?.action) as ActionType;
  const reason = asString(input?.reason);
  const payload = (input?.payload && typeof input.payload === 'object'
    ? input.payload
    : {}) as Record<string, unknown>;

  const rendered = renderAction(type, payload, reason, t);
  const hasFull = rendered.fullText.trim().length > 0;
  const hasReason = reason.trim().length > 0;

  return (
    <div className={`supervisor-action-card supervisor-action-${rendered.variant}`}>
      <div
        className="supervisor-action-head"
        onClick={hasFull ? () => setExpanded((v) => !v) : undefined}
        style={{ cursor: hasFull ? 'pointer' : 'default' }}
      >
        <span className={`codicon ${rendered.icon} supervisor-action-icon`} />
        <span className="supervisor-action-label">{rendered.label}</span>
        {rendered.headline && (
          <span className="supervisor-action-headline" title={rendered.headline}>
            {rendered.headline}
          </span>
        )}
        {hasFull && (
          <span className="supervisor-action-chevron">{expanded ? '▼' : '▶'}</span>
        )}
      </div>
      {hasReason && !expanded && (
        <div className="supervisor-action-reason">{reason}</div>
      )}
      {expanded && hasFull && (
        <div className="supervisor-action-body">
          {hasReason && (
            <div className="supervisor-action-reason">{reason}</div>
          )}
          <MarkdownBlock content={rendered.fullText} />
        </div>
      )}
    </div>
  );
};

export default SupervisorActionBlock;
