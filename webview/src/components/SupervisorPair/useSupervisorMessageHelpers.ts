import { useCallback, useRef } from 'react';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock } from '../../types';

/**
 * Helpers consumed by MessageList for a Supervisor's local conversation.
 *
 * Mirrors the contract App.tsx exposes to MessageList for the main AI
 * (getMessageText / getContentBlocks / findToolResult / extractMarkdownContent),
 * but scoped to a single supervisor's message array. By providing these
 * adapters we can reuse the entire MessageList → MessageItem →
 * ContentBlockRenderer pipeline without changes.
 *
 * Supervisor SDK messages arrive in `raw.content` exactly like the main AI's,
 * so the adapters are trivial filters/extractors — we deliberately skip the
 * heavier `normalizeBlocks` pass used by the main pipeline because the daemon
 * already hands us well-formed content blocks; the extra parsing (command
 * tags, task notifications, etc.) doesn't apply to supervisor turns.
 */
export function useSupervisorMessageHelpers(messages: ClaudeMessage[]) {
  const messagesRef = useRef(messages);
  messagesRef.current = messages;

  const getContentBlocks = useCallback((msg: ClaudeMessage): ClaudeContentBlock[] => {
    if (!msg.raw || typeof msg.raw === 'string') {
      if (msg.content && msg.content.trim().length > 0) {
        return [{ type: 'text', text: msg.content }];
      }
      return [];
    }
    const content = msg.raw.content ?? msg.raw.message?.content;
    if (!Array.isArray(content)) {
      if (msg.content && msg.content.trim().length > 0) {
        return [{ type: 'text', text: msg.content }];
      }
      return [];
    }
    return content.filter(
      (b): b is ClaudeContentBlock => Boolean(b) && (b as { type?: string }).type !== 'tool_result'
    );
  }, []);

  const getMessageText = useCallback(
    (msg: ClaudeMessage): string => {
      if (typeof msg.content === 'string' && msg.content.trim().length > 0) {
        return msg.content;
      }
      return getContentBlocks(msg)
        .filter((b): b is Extract<ClaudeContentBlock, { type: 'text' }> => b.type === 'text')
        .map((b) => b.text ?? '')
        .join('\n');
    },
    [getContentBlocks]
  );

  const findToolResult = useCallback(
    (toolUseId?: string, messageIndex?: number): ToolResultBlock | null => {
      if (!toolUseId || typeof messageIndex !== 'number') return null;
      const current = messagesRef.current;
      for (let i = 0; i < current.length; i += 1) {
        const candidate = current[i];
        const raw = candidate.raw;
        if (!raw || typeof raw === 'string') continue;
        const content = raw.content ?? raw.message?.content;
        if (!Array.isArray(content)) continue;
        const resultBlock = content.find(
          (block): block is ToolResultBlock =>
            Boolean(block) &&
            (block as { type?: string }).type === 'tool_result' &&
            (block as ToolResultBlock).tool_use_id === toolUseId
        );
        if (resultBlock) return resultBlock;
      }
      return null;
    },
    []
  );

  const extractMarkdownContent = useCallback(
    (msg: ClaudeMessage): string => getMessageText(msg),
    [getMessageText]
  );

  return { getContentBlocks, getMessageText, findToolResult, extractMarkdownContent };
}
