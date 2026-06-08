/**
 * Silent-time (node liveness) display helpers — shared by the canvas node card
 * and the supervisor panel. See docs/workflow/node-liveness-watchdog-plan.md §6.
 */

/** Below this idle the node counts as "active" → show 0:00 in the ok colour. */
export const ACTIVE_EPS_MS = 15_000;

/** ms → "M:SS" (or "H:MM:SS" past an hour). */
export function formatIdle(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const p = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}

/** epoch ms → "YYYY-MM-DD HH:MM:SS" (local time). Empty for null/0. */
export function formatYmdHms(ms?: number | null): string {
  if (!ms || ms <= 0) return '';
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** Elapsed duration in minutes (1 decimal), e.g. "1.5 分". <1min still shows decimals. */
export function formatElapsedMin(ms: number): string {
  return `${(Math.max(0, ms) / 60_000).toFixed(1)} 分`;
}

export type IdleLevel = 'ok' | 'warn' | 'danger';

/** Colour bucket by idle/threshold ratio (active → ok). thresholdMs<=0 → ratio-free. */
export function idleLevel(idleMs: number, thresholdMs: number): IdleLevel {
  if (idleMs < ACTIVE_EPS_MS) return 'ok';
  if (thresholdMs <= 0) return 'warn';
  const r = idleMs / thresholdMs;
  if (r < 0.5) return 'ok';
  if (r < 0.85) return 'warn';
  return 'danger';
}
