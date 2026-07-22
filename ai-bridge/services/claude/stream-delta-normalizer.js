/**
 * Normalize streaming text/thinking deltas from non-compliant gateways.
 *
 * The Anthropic Messages API streams INCREMENTAL deltas — each
 * content_block_delta carries only the newly produced chunk. Some
 * Claude-protocol-compatible gateways (GPT-5.x proxies) violate this in two
 * ways, both producing massively duplicated lines in the streaming UI
 * ("很多行内容都在流式里相同"):
 *
 *   1. CUMULATIVE deltas: every event carries the block's full text so far
 *      ("第一行", "第一行\n第二行", "第一行\n第二行\n第三行"). Forwarded
 *      verbatim, every line renders N times. The Java-side endsWith dedup
 *      cannot catch this shape because each delta is LONGER than the tail.
 *   2. FULL-BLOCK REPLAY: a final delta repeats the complete block content
 *      (common when the proxy buffers reasoning and flushes it at block end).
 *
 * The tracker records the text already emitted for the CURRENT content block
 * and returns only the novel suffix to forward ('' = drop the delta):
 *   - delta === emitted             → full replay   → ''
 *   - delta startsWith(emitted)     → cumulative    → novel suffix only
 *   - emitted endsWith(delta)       → tail re-send  → ''
 *   - otherwise                     → real increment → delta unchanged
 *
 * Shapes are only meaningful within ONE content block, so reset() must be
 * called on every content_block_start (cumulative counters restart per block).
 *
 * Known limitation: a legitimate increment that begins with the block's
 * ENTIRE text-so-far (block "哈哈", increment "哈哈哈哈") is indistinguishable
 * from a cumulative delta; we emit only the suffix and lose the overlap. That
 * periodic shape is vanishingly rare beyond the block's first characters,
 * whereas gateway replay bugs are systematic — the trade-off favors
 * normalizing. Short (< 8 chars) exact replays / tail repeats are passed
 * through because short repeated tokens ("ok ok", "\n\n") ARE legitimate
 * increments; the Java-side endsWith dedup remains as a backstop for those.
 */

const MIN_REPLAY_LEN = 8;

export function createStreamDeltaTracker() {
  const emittedByBlock = { text: '', thinking: '' };

  return {
    /** Reset the per-block accumulator on content_block_start. */
    reset(blockType) {
      if (blockType === 'text' || blockType === 'thinking') {
        emittedByBlock[blockType] = '';
      }
    },

    /**
     * @param {'text'|'thinking'} blockType
     * @param {string} delta raw delta text from the gateway
     * @returns {string} the novel suffix to emit ('' = drop)
     */
    normalize(blockType, delta) {
      if (!delta || (blockType !== 'text' && blockType !== 'thinking')) {
        return delta || '';
      }
      const emitted = emittedByBlock[blockType];
      let novel = delta;
      if (emitted) {
        if (delta.startsWith(emitted) && delta.length > emitted.length) {
          // Cumulative delta — emit only the grown tail.
          novel = delta.slice(emitted.length);
        } else if (
          delta.length >= MIN_REPLAY_LEN &&
          (delta === emitted || emitted.endsWith(delta))
        ) {
          // Full-block replay or tail re-send — drop.
          novel = '';
        }
      }
      emittedByBlock[blockType] += novel;
      return novel;
    }
  };
}
