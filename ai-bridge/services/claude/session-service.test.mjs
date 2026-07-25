import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

import {
  isEncryptedContentVerificationError,
  sanitizeSessionLines
} from './session-service.js';

function sha1(line) {
  return createHash('sha1').update(line, 'utf8').digest('hex');
}

function assistantLine(content) {
  return JSON.stringify({ type: 'assistant', message: { role: 'assistant', content } });
}

function userLine(text) {
  return JSON.stringify({ type: 'user', message: { role: 'user', content: [{ type: 'text', text }] } });
}

// ---------------------------------------------------------------------------
// isEncryptedContentVerificationError
// ---------------------------------------------------------------------------

test('classifier matches the real gateway 400 text', () => {
  const text = 'API Error: 400 The encrypted content gAAA...qZM= could not be verified. '
    + 'Reason: Encrypted content could not be decrypted or parsed.';
  assert.equal(isEncryptedContentVerificationError(text), true);
});

test('classifier matches the reason phrase alone', () => {
  assert.equal(
    isEncryptedContentVerificationError('Reason: Encrypted content could not be decrypted or parsed.'),
    true
  );
});

test('classifier rejects unrelated errors', () => {
  assert.equal(isEncryptedContentVerificationError('API request failed'), false);
  assert.equal(isEncryptedContentVerificationError('API Error: 400 prompt is too long'), false);
  assert.equal(isEncryptedContentVerificationError('API Error: 429 rate limit exceeded'), false);
  assert.equal(isEncryptedContentVerificationError(null), false);
  assert.equal(isEncryptedContentVerificationError(undefined), false);
  assert.equal(isEncryptedContentVerificationError(''), false);
});

// ---------------------------------------------------------------------------
// sanitizeSessionLines — thinking strip
// ---------------------------------------------------------------------------

test('strips thinking/redacted_thinking/reasoning blocks from assistant lines', () => {
  const line = assistantLine([
    { type: 'thinking', thinking: 'secret', signature: 'gAAA123' },
    { type: 'text', text: 'visible answer' },
    { type: 'redacted_thinking', data: 'gAAA456' },
    { type: 'reasoning', encrypted_content: 'gAAA789' }
  ]);
  const { lines, changed } = sanitizeSessionLines([line]);
  assert.equal(changed, true);
  const msg = JSON.parse(lines[0]);
  assert.deepEqual(msg.message.content, [{ type: 'text', text: 'visible answer' }]);
});

test('substitutes a placeholder for thinking-only assistant turns', () => {
  const line = assistantLine([{ type: 'thinking', thinking: 'only thought', signature: 'gAAA' }]);
  const { lines, changed } = sanitizeSessionLines([line]);
  assert.equal(changed, true);
  const msg = JSON.parse(lines[0]);
  assert.deepEqual(msg.message.content, [{ type: 'text', text: ' ' }]);
});

test('leaves user lines and thinking-free assistant lines untouched', () => {
  const user = userLine('hello');
  const assistant = assistantLine([{ type: 'text', text: 'hi' }]);
  const { lines, changed } = sanitizeSessionLines([user, assistant]);
  assert.equal(changed, false);
  assert.deepEqual(lines, [user, assistant]);
});

test('keeps malformed and blank lines as-is', () => {
  const { lines, changed } = sanitizeSessionLines(['', 'not json', '   ']);
  assert.equal(changed, false);
  assert.deepEqual(lines, ['', 'not json', '   ']);
});

// ---------------------------------------------------------------------------
// sanitizeSessionLines — failed-turn truncation (hash verified)
// ---------------------------------------------------------------------------

test('truncates lines appended after a verified snapshot boundary', () => {
  const history = [userLine('q1'), assistantLine([{ type: 'text', text: 'a1' }])];
  const failedTurn = [
    userLine('q2'),
    assistantLine([{ type: 'thinking', thinking: 't', signature: 'gAAA' }, { type: 'text', text: 'a2' }])
  ];
  const all = [...history, ...failedTurn];

  const { lines, changed, truncated } = sanitizeSessionLines(all, {
    truncateToLineCount: 2,
    expectedLastLineHash: sha1(history[1])
  });

  assert.equal(truncated, true);
  assert.equal(changed, true);
  assert.deepEqual(lines, history);
});

test('skips truncation when the snapshot boundary hash mismatches (external rewrite)', () => {
  const lines = [
    userLine('q1'),
    assistantLine([{ type: 'thinking', thinking: 't', signature: 'gAAA' }, { type: 'text', text: 'a1' }]),
    userLine('q2')
  ];

  const { lines: out, truncated } = sanitizeSessionLines(lines, {
    truncateToLineCount: 2,
    expectedLastLineHash: sha1('some other line')
  });

  assert.equal(truncated, false);
  // Thinking strip still applies to the retained lines.
  assert.equal(out.length, 3);
  assert.deepEqual(JSON.parse(out[1]).message.content, [{ type: 'text', text: 'a1' }]);
});

test('no truncation when the file did not grow past the snapshot', () => {
  const history = [userLine('q1')];
  const { lines, changed, truncated } = sanitizeSessionLines(history, {
    truncateToLineCount: 1,
    expectedLastLineHash: sha1(history[0])
  });
  assert.equal(truncated, false);
  assert.equal(changed, false);
  assert.deepEqual(lines, history);
});

test('null repair option preserves legacy strip-only behaviour', () => {
  const line = assistantLine([{ type: 'thinking', thinking: 't', signature: 'gAAA' }]);
  const { truncated } = sanitizeSessionLines([line], null);
  assert.equal(truncated, false);
});
