import test from 'node:test';
import assert from 'node:assert/strict';

import { createStreamDeltaTracker } from './stream-delta-normalizer.js';

/** Feed deltas through a tracker (with optional block resets) and join emitted output. */
function run(tracker, steps) {
  let out = '';
  for (const step of steps) {
    if (step.reset) {
      tracker.reset(step.reset);
    } else {
      out += tracker.normalize(step.type, step.delta);
    }
  }
  return out;
}

test('passes incremental (compliant) deltas through unchanged', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'thinking' },
    { type: 'thinking', delta: '第一行\n' },
    { type: 'thinking', delta: '第二行\n' },
    { type: 'thinking', delta: '第三行\n' },
  ]);
  assert.equal(out, '第一行\n第二行\n第三行\n');
});

test('collapses cumulative deltas to their novel suffix', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'thinking' },
    { type: 'thinking', delta: '第一行\n' },
    { type: 'thinking', delta: '第一行\n第二行\n' },
    { type: 'thinking', delta: '第一行\n第二行\n第三行\n' },
  ]);
  assert.equal(out, '第一行\n第二行\n第三行\n');
});

test('drops a full-block replay at block end', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'text' },
    { type: 'text', delta: 'The quick brown fox' },
    { type: 'text', delta: ' jumps over' },
    { type: 'text', delta: 'The quick brown fox jumps over' },
  ]);
  assert.equal(out, 'The quick brown fox jumps over');
});

test('drops a long tail re-send', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'text' },
    { type: 'text', delta: 'abcdefghij' },
    { type: 'text', delta: 'klmnopqrst' },
    { type: 'text', delta: 'klmnopqrst' }, // exact repeat of last chunk (>= 8 chars)
  ]);
  assert.equal(out, 'abcdefghijklmnopqrst');
});

test('passes short repeated tokens through (legit increments)', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'text' },
    { type: 'text', delta: 'ok' },
    { type: 'text', delta: 'ok' }, // short repeat — must NOT be dropped
    { type: 'text', delta: '\n\n' },
    { type: 'text', delta: '\n\n' },
  ]);
  assert.equal(out, 'okok\n\n\n\n');
});

test('resets per content block (cumulative counters restart per block)', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'thinking' },
    { type: 'thinking', delta: 'T1思考内容\n' },
    // New thinking block — its first delta may coincidentally equal the old
    // accumulator length-wise, but must never be treated as a replay of it.
    { reset: 'thinking' },
    { type: 'thinking', delta: 'T1思考内容\n' },
    { type: 'thinking', delta: 'T1思考内容\n第二段\n' }, // cumulative of block 2
  ]);
  assert.equal(out, 'T1思考内容\nT1思考内容\n第二段\n');
});

test('text and thinking trackers are independent', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'thinking' },
    { type: 'thinking', delta: '分析一下\n' },
    { reset: 'text' },
    { type: 'text', delta: '分析一下\n' }, // same text in a different block type — keep
  ]);
  assert.equal(out, '分析一下\n分析一下\n');
});

test('mixed incremental + cumulative stream stays consistent', () => {
  const tracker = createStreamDeltaTracker();
  const out = run(tracker, [
    { reset: 'thinking' },
    { type: 'thinking', delta: 'A' },
    { type: 'thinking', delta: 'B' },
    { type: 'thinking', delta: 'ABC' },   // cumulative after increments
    { type: 'thinking', delta: 'D' },     // incremental again
    { type: 'thinking', delta: 'ABCDE' }, // cumulative again
  ]);
  assert.equal(out, 'ABCDE');
});
