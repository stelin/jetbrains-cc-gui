import { emitAccumulatedUsage, mergeUsage, estimateTokensFromChars, emitLiveUsage } from '../../utils/usage-utils.js';
import { truncateErrorContent, truncateToolResultBlock } from './message-output-filter.js';
import { createStreamDeltaTracker } from './stream-delta-normalizer.js';

export function emitUsageTag(msg) {
  if (msg.type === 'assistant' && msg.message?.usage) {
    const {
      input_tokens = 0,
      output_tokens = 0,
      cache_creation_input_tokens = 0,
      cache_read_input_tokens = 0
    } = msg.message.usage;
    console.log('[USAGE]', JSON.stringify({
      input_tokens,
      output_tokens,
      cache_creation_input_tokens,
      cache_read_input_tokens
    }));
  }
}

export function createTurnState(requestContext, runtime) {
  return {
    streamingEnabled: requestContext.streamingEnabled,
    streamStarted: false,
    streamEnded: false,
    hasStreamEvents: false,
    lastAssistantContent: '',
    lastThinkingContent: '',
    finalSessionId: requestContext.requestedSessionId || runtime?.sessionId || '',
    accumulatedUsage: null,
    // Live output-token estimate (2026-05-28): chars streamed this turn +
    // last emit timestamp for throttling. Drives the CLI-style ticking counter
    // since message_delta usage alone is too sparse (see emitLiveUsage).
    streamedOutputChars: 0,
    lastUsageEmitMs: 0,
    // Per-content-block delta normalization: drops cumulative / replayed
    // deltas from non-compliant gateways (see stream-delta-normalizer.js).
    deltaTracker: createStreamDeltaTracker(),
    // Turn-restart detection: set on message_start, cleared when the assistant
    // message for that API response is processed. A message_start arriving
    // while still in flight means the CLI retried a failed stream and is
    // re-streaming the whole response (whole-message duplication otherwise).
    messageInFlight: false
  };
}

export function processStreamEvent(msg, turnState) {
  const event = msg.event;
  if (!event) return;

  if (event.type === 'message_start') {
    // Retry re-stream detection: a message_start while the previous response
    // never completed (no assistant message cleared the flag) means the CLI is
    // re-streaming the whole turn after a mid-stream failure. Reset the turn
    // accumulators + delta tracker and re-emit [STREAM_START] so the Java
    // handler and the webview reset the streaming bubble instead of appending
    // the replayed text onto the partial one (whole-message duplication).
    if (turnState.messageInFlight &&
        (turnState.lastAssistantContent.length > 0 || turnState.lastThinkingContent.length > 0)) {
      console.log('[RETRY] mid-turn stream restart detected — resetting streaming bubble');
      turnState.lastAssistantContent = '';
      turnState.lastThinkingContent = '';
      turnState.deltaTracker?.reset('text');
      turnState.deltaTracker?.reset('thinking');
      if (turnState.streamingEnabled && turnState.streamStarted) {
        process.stdout.write('[STREAM_START]\n');
      }
    }
    turnState.messageInFlight = true;
  }

  if (event.type === 'message_start' && event.message?.usage) {
    turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.message.usage);
  }

  if (event.type === 'message_delta' && event.usage) {
    turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.usage);
    emitAccumulatedUsage(turnState.accumulatedUsage);
  }

  if (event.type === 'content_block_start') {
    // Cumulative/replay detection is per-block: gateway counters restart here.
    turnState.deltaTracker?.reset(event.content_block?.type);
  }

  if (event.type === 'content_block_delta' && event.delta) {
    let chunk = '';
    if (event.delta.type === 'text_delta' && event.delta.text) {
      const novel = turnState.deltaTracker
        ? turnState.deltaTracker.normalize('text', event.delta.text)
        : event.delta.text;
      if (novel) {
        process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(novel)}\n`);
        turnState.lastAssistantContent += novel;
        chunk = novel;
      }
    } else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
      const novel = turnState.deltaTracker
        ? turnState.deltaTracker.normalize('thinking', event.delta.thinking)
        : event.delta.thinking;
      if (novel) {
        process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(novel)}\n`);
        turnState.lastThinkingContent += novel;
        chunk = novel;
      }
    }
    // Live token estimate: both text and thinking count toward output_tokens.
    // Emit a throttled [USAGE] so the "↓ N tokens" counter ticks during the
    // turn (incl. the thinking phase) instead of jumping once at message_delta.
    if (chunk) {
      turnState.streamedOutputChars += chunk.length;
      const now = Date.now();
      if (now - turnState.lastUsageEmitMs >= 150) {
        turnState.lastUsageEmitMs = now;
        emitLiveUsage(turnState.accumulatedUsage, estimateTokensFromChars(turnState.streamedOutputChars));
      }
    }
  }
}

export function processMessageContent(msg, turnState) {
  if (msg.type !== 'assistant') return;
  // The API response completed — a subsequent message_start is a new agent-loop
  // iteration, not a retry re-stream.
  turnState.messageInFlight = false;
  const content = msg.message?.content;

  if (Array.isArray(content)) {
    for (const block of content) {
      if (block.type === 'text') {
        const currentText = block.text || '';
        // Send delta if content grew, regardless of hasStreamEvents
        // This ensures conservative sync works correctly and prevents content loss
        // (especially important for markdown tables which need complete row structures)
        if (turnState.streamingEnabled && currentText.length > turnState.lastAssistantContent.length) {
          const delta = currentText.substring(turnState.lastAssistantContent.length);
          if (delta) {
            process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
          }
          turnState.lastAssistantContent = currentText;
        } else if (!turnState.streamingEnabled) {
          console.log('[CONTENT]', truncateErrorContent(currentText));
        }
      } else if (block.type === 'thinking') {
        const thinkingText = block.thinking || block.text || '';
        // Send delta if thinking grew, regardless of hasStreamEvents
        if (turnState.streamingEnabled && thinkingText.length > turnState.lastThinkingContent.length) {
          const delta = thinkingText.substring(turnState.lastThinkingContent.length);
          if (delta) {
            process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
          }
          turnState.lastThinkingContent = thinkingText;
        } else if (!turnState.streamingEnabled) {
          console.log('[THINKING]', thinkingText);
        }
      }
    }
  } else if (typeof content === 'string') {
    // Send delta if content grew, regardless of hasStreamEvents
    if (turnState.streamingEnabled && content.length > turnState.lastAssistantContent.length) {
      const delta = content.substring(turnState.lastAssistantContent.length);
      if (delta) {
        process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
      }
      turnState.lastAssistantContent = content;
    } else if (!turnState.streamingEnabled) {
      console.log('[CONTENT]', truncateErrorContent(content));
    }
  }
}

export function processToolResultMessages(msg) {
  if (msg.type !== 'user') return;
  const content = msg.message?.content ?? msg.content;
  if (!Array.isArray(content)) return;
  for (const block of content) {
    if (block.type === 'tool_result') {
      console.log('[TOOL_RESULT]', JSON.stringify(truncateToolResultBlock(block)));
    }
  }
}

export function shouldOutputMessage(msg, turnState) {
  // Always output non-assistant messages
  if (msg.type !== 'assistant') {
    return true;
  }

  // For assistant messages:
  // - In streaming mode: output if has tool_use OR always output for conservative sync
  //   (needed to prevent content loss - Java layer's handleAssistantMessage performs
  //   conservative sync which catches any missed deltas)
  // - In non-streaming mode: always output
  if (!turnState.streamingEnabled) {
    return true;
  }

  // Always output assistant messages for conservative sync (prevents delta loss)
  // The Java layer uses the full assistant JSON to fill any gaps in streaming content
  return true;
}
