/**
 * Single subscription point for the Java → webview `updateRemoteSyncState`
 * callback. Multiple components (SyncStatusBar in ChatHeader, RemoteSyncSection
 * in settings) may be mounted at the same time and both need fresh state, so we
 * funnel every payload through CustomEvents instead of having each component
 * race to register the same window function.
 *
 * Initialised lazily — the first listener triggers registration.
 */

export const SYNC_FULL_STATE_EVENT = 'codemoss-remote-sync-state';
export const SYNC_STATUS_ONLY_EVENT = 'codemoss-sync-status';

interface RawPayload {
  sdk?: unknown;
  githubProxy?: string;
  config?: unknown;
  hasPassword?: boolean;
  syncStatus?: unknown;
  hostKey?: unknown;
  testResult?: unknown;
}

let installed = false;

declare global {
  interface Window {
    updateRemoteSyncState?: (json: string) => void;
  }
}

export function ensureRemoteSyncBridge(): void {
  if (installed) return;
  if (typeof window === 'undefined') return;
  installed = true;
  window.updateRemoteSyncState = (json: string) => {
    let parsed: RawPayload;
    try {
      parsed = JSON.parse(json) as RawPayload;
    } catch (e) {
      console.error('[remoteSyncBridge] parse failed', e);
      return;
    }
    // Always dispatch the full payload — settings panel consumes it whole.
    window.dispatchEvent(
      new CustomEvent<RawPayload>(SYNC_FULL_STATE_EVENT, { detail: parsed }),
    );
    // Also dispatch a status-only event so the global bar can react without
    // pulling in fields it doesn't care about.
    if (parsed.syncStatus) {
      window.dispatchEvent(
        new CustomEvent(SYNC_STATUS_ONLY_EVENT, { detail: parsed.syncStatus }),
      );
    }
  };
}

export function sendToJava(msg: string): void {
  const w = window as { sendToJava?: (m: string) => void };
  if (w.sendToJava) w.sendToJava(msg);
}
