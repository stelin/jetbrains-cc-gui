/**
 * Focus-aware router for IDE-originated file drops.
 *
 * <p>Java's {@code WebviewInitializer} catches OS-level drops on the JBCef
 * Swing component and dispatches them via {@code window.handleFilePathFromJava}.
 * Historically only the main AI's {@code ChatInputBox} registered that callback,
 * so dragging a file from the IDE Project Tool Window onto the supervisor pane
 * still inserted into the main AI input — visually confusing.
 *
 * <p>This module tracks which chat input was most-recently focused and lets
 * non-main inputs register a {@code (paths) => void} handler. The main AI's
 * {@code handleFilePathFromJava} consults {@link tryDispatchExternalDrop} at
 * the top of its implementation: if it returns true the drop was claimed and
 * the main AI bails out, otherwise it inserts as before. Default focus is
 * {@code 'main'} so behaviour is unchanged when no supervisor is active.
 *
 * <p>OS-level drops that target the supervisor textarea directly via HTML
 * drop events continue to work through the textarea's own {@code onDrop}
 * handler — this router only fixes the Java-mediated path that lacks
 * cursor-target information.
 */

export type ChatInputId = 'main' | 'supervisor';

type DropHandler = (paths: string[]) => void;

let lastFocusedId: ChatInputId = 'main';
const handlers = new Map<ChatInputId, DropHandler>();

/**
 * Mark a chat input as the most-recently focused. Call from each input's
 * {@code onFocus} event. Never reset on blur — "last focused wins" semantics
 * survive transient blurs (clicking a dropdown, the Send button, etc.).
 */
export function markChatInputFocused(id: ChatInputId): void {
  lastFocusedId = id;
}

/**
 * Register a drop handler for {@code id}. Returns the unregister cleanup —
 * call from a {@code useEffect} return to drop the handler on unmount.
 */
export function registerChatInputDropHandler(
  id: ChatInputId,
  handler: DropHandler
): () => void {
  handlers.set(id, handler);
  return () => {
    if (handlers.get(id) === handler) handlers.delete(id);
  };
}

/**
 * Try to route an externally-sourced file drop to a non-main handler.
 * Returns true if a non-main handler accepted; the main caller should
 * return early to avoid double-insertion. Returns false otherwise
 * (no non-main handler registered, or the last-focused input is the
 * main one).
 */
export function tryDispatchExternalDrop(paths: string[]): boolean {
  if (paths.length === 0) return false;
  if (lastFocusedId === 'main') return false;
  const handler = handlers.get(lastFocusedId);
  if (!handler) return false;
  try {
    handler(paths);
    return true;
  } catch (e) {
    console.error('[chatInputDropRouter] handler failed:', e);
    return false;
  }
}

/** Test-only helper. Reset module state between tests. */
export function __resetChatInputDropRouterForTest(): void {
  lastFocusedId = 'main';
  handlers.clear();
}
