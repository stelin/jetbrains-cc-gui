import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReasoningEffort } from './ChatInputBox/types';
import { formatEffortLabel, formatTokenCount } from '../utils/formatEffort';

interface WaitingIndicatorProps {
  size?: number;
  /** Loading start timestamp (ms), used to maintain continuous timing across view switches */
  startTime?: number;
  /** Effort tier in use for the current turn (snapshot at submit time). */
  effort?: ReasoningEffort | null;
  /** Accumulated output tokens for the current turn so far. */
  outputTokens?: number;
  /**
   * Phase hint:
   *   - 'thinking' → model is still in extended thinking phase (no text deltas yet)
   *   - 'responding' → text deltas have started flowing (≈ CLI "almost done thinking")
   */
  phase?: 'thinking' | 'responding';
}

export const WaitingIndicator = ({
  size = 18,
  startTime,
  effort,
  outputTokens,
  phase,
}: WaitingIndicatorProps) => {
  const { t } = useTranslation();
  const [dotCount, setDotCount] = useState(1);
  const [elapsedSeconds, setElapsedSeconds] = useState(() => {
    if (startTime) return Math.floor((Date.now() - startTime) / 1000);
    return 0;
  });

  useEffect(() => {
    const timer = setInterval(() => {
      setDotCount(prev => (prev % 3) + 1);
    }, 500);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const timer = setInterval(() => {
      if (startTime) {
        setElapsedSeconds(Math.floor((Date.now() - startTime) / 1000));
      } else {
        setElapsedSeconds(prev => prev + 1);
      }
    }, 1000);

    return () => {
      clearInterval(timer);
    };
  }, [startTime]);

  const dots = '.'.repeat(dotCount);

  const formatElapsedTime = (seconds: number): string => {
    if (seconds < 60) {
      return `${seconds} ${t('common.seconds')}`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${t('chat.minutesAndSeconds', { minutes, seconds: remainingSeconds })}`;
  };

  // Build optional "thinking with <effort> effort" / "almost done thinking with <effort> effort" suffix.
  let effortSuffix: string | null = null;
  if (effort) {
    const effortLabel = formatEffortLabel(effort, t);
    const key = phase === 'responding'
      ? 'chat.almostDoneThinkingWithEffort'
      : 'chat.thinkingWithEffort';
    effortSuffix = t(key, {
      effort: effortLabel,
      defaultValue: phase === 'responding'
        ? `almost done thinking with ${effortLabel} effort`
        : `thinking with ${effortLabel} effort`,
    });
  }

  const tokensText = typeof outputTokens === 'number' && outputTokens > 0
    ? `↓ ${formatTokenCount(outputTokens)} tokens`
    : null;

  return (
    <div className="waiting-indicator">
      <span className="waiting-spinner" style={{ width: size, height: size }} />
      <span className="waiting-text">
        {t('chat.generatingResponse')}<span className="waiting-dots">{dots}</span>
        <span className="waiting-seconds">
          {'（'}{t('chat.elapsedTime', { time: formatElapsedTime(elapsedSeconds) })}
          {tokensText && <> · {tokensText}</>}
          {effortSuffix && <> · {effortSuffix}</>}
          {'）'}
        </span>
      </span>
    </div>
  );
};

export default WaitingIndicator;
