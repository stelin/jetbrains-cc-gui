import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import {
  ensureSanitizingProxy,
  __resetSanitizingProxyForTest,
  sanitizeMessagesBody
} from './sanitizing-proxy.js';

test.afterEach(() => __resetSanitizingProxyForTest());

/** Start a throwaway upstream that records the last request and responds 200. */
function startFakeUpstream(handler) {
  const captured = { bodies: [], headers: [], paths: [] };
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      captured.bodies.push(Buffer.concat(chunks).toString('utf8'));
      captured.headers.push(req.headers);
      captured.paths.push(req.url);
      handler(req, res);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, captured }));
  });
}

function post(url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(body, 'utf8');
    const req = http.request(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': data.length, ...headers }
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    req.end(data);
  });
}

// ---------------------------------------------------------------------------
// sanitizeMessagesBody (pure)
// ---------------------------------------------------------------------------

test('sanitizeMessagesBody strips thinking blocks and keeps role parity', () => {
  const body = {
    model: 'gpt-5.6',
    messages: [
      { role: 'user', content: [{ type: 'text', text: 'q' }] },
      { role: 'assistant', content: [
        { type: 'thinking', thinking: 'secret', signature: 'gAAA123' },
        { type: 'text', text: 'a' },
        { type: 'tool_use', id: 't1', name: 'Read', input: {} }
      ] },
      { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: 'ok' }] },
      { role: 'assistant', content: [{ type: 'reasoning', encrypted_content: 'gAAA999' }] }
    ]
  };
  const changed = sanitizeMessagesBody(body);
  assert.equal(changed, true);
  assert.deepEqual(body.messages[1].content, [
    { type: 'text', text: 'a' },
    { type: 'tool_use', id: 't1', name: 'Read', input: {} }
  ]);
  // thinking-only assistant message becomes a blank text block (role preserved)
  assert.deepEqual(body.messages[3].content, [{ type: 'text', text: ' ' }]);
});

test('sanitizeMessagesBody is a no-op for thinking-free bodies', () => {
  const body = { messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }] };
  assert.equal(sanitizeMessagesBody(body), false);
  assert.equal(sanitizeMessagesBody({}), false);
  assert.equal(sanitizeMessagesBody(null), false);
});

// ---------------------------------------------------------------------------
// Proxy end-to-end
// ---------------------------------------------------------------------------

test('proxy strips thinking blocks from POST /v1/messages and forwards', async () => {
  const upstream = await startFakeUpstream((req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end('data: {"type":"message_start"}\n\n');
  });
  const proxyUrl = await ensureSanitizingProxy(`http://127.0.0.1:${upstream.port}`);

  const requestBody = JSON.stringify({
    model: 'gpt-5.6',
    stream: true,
    messages: [
      { role: 'user', content: [{ type: 'text', text: 'q' }] },
      { role: 'assistant', content: [
        { type: 'thinking', thinking: 'secret', signature: 'gAAA...' },
        { type: 'text', text: 'a' }
      ] }
    ]
  });

  const res = await post(`${proxyUrl}/v1/messages`, requestBody, { 'x-api-key': 'k', 'anthropic-version': '2023-06-01' });

  assert.equal(res.status, 200);
  assert.match(res.body, /message_start/);

  const forwarded = JSON.parse(upstream.captured.bodies[0]);
  assert.deepEqual(forwarded.messages[1].content, [{ type: 'text', text: 'a' }]);
  // auth + version headers preserved
  assert.equal(upstream.captured.headers[0]['x-api-key'], 'k');
  assert.equal(upstream.captured.headers[0]['anthropic-version'], '2023-06-01');
  upstream.server.close();
});

test('proxy forwards non-messages paths untouched', async () => {
  const upstream = await startFakeUpstream((req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"ok":true}');
  });
  const proxyUrl = await ensureSanitizingProxy(`http://127.0.0.1:${upstream.port}`);

  const raw = '{"messages":[{"role":"assistant","content":[{"type":"thinking","thinking":"x","signature":"gAAA"}]}]}';
  const res = await post(`${proxyUrl}/v1/models`, raw);
  assert.equal(res.status, 200);
  // untouched: the thinking block is still in the forwarded body
  assert.equal(upstream.captured.bodies[0], raw);
  upstream.server.close();
});

test('proxy streams SSE responses back unmodified', async () => {
  const events = 'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hel"}}\n\n'
    + 'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}\n\n';
  const upstream = await startFakeUpstream((req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
    res.write(events.split('\n\n')[0] + '\n\n');
    setImmediate(() => res.end(events.split('\n\n')[1] + '\n\n'));
  });
  const proxyUrl = await ensureSanitizingProxy(`http://127.0.0.1:${upstream.port}`);

  const res = await post(`${proxyUrl}/v1/messages`, '{"messages":[]}');
  assert.equal(res.status, 200);
  assert.equal(res.headers['content-type'], 'text/event-stream');
  assert.match(res.body, /"hel"/);
  assert.match(res.body, /"lo"/);
  upstream.server.close();
});

test('proxy returns 502 JSON when upstream is unreachable', async () => {
  // Point at a port nothing listens on.
  const proxyUrl = await ensureSanitizingProxy('http://127.0.0.1:1');
  const res = await post(`${proxyUrl}/v1/messages`, '{"messages":[]}');
  assert.equal(res.status, 502);
  const body = JSON.parse(res.body);
  assert.equal(body.type, 'error');
});
