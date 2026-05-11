import type { TFunction } from 'i18next';
import type { ReasoningEffort } from '../components/ChatInputBox/types';

/**
 * Format an effort code with localized label, e.g.
 *   zh: "超高(max)"
 *   en: "Max"   (English label same as code → omit parens)
 *
 * Falls back to the raw code if no translation is registered.
 */
export function formatEffortLabel(effort: ReasoningEffort, t: TFunction): string {
  const code = effort;
  const label = t(`reasoning.${code}.label`, { defaultValue: code });

  if (typeof label !== 'string' || label.length === 0) return code;
  // Dedupe when label equals the raw code (case-insensitive) — common for English.
  if (label.toLowerCase() === code.toLowerCase()) return label;
  return `${label}(${code})`;
}

/** Format output token count in CLI style: 1234 → "1.2k", 100 → "100" */
export function formatTokenCount(n: number | undefined): string {
  if (typeof n !== 'number' || !Number.isFinite(n) || n < 0) return '0';
  if (n < 1000) return String(n);
  const k = n / 1000;
  return k >= 10 ? `${Math.round(k)}k` : `${k.toFixed(1)}k`;
}
