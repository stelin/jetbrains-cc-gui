/**
 * Loopback sanitizing proxy for third-party Claude-protocol gateways.
 *
 * Why this exists: gateways that speak the Anthropic Messages protocol but run
 * other models (GPT-5.x) encrypt/sign assistant thinking blocks under their
 * CURRENT key/instance and can only verify what they themselves produced. When
 * requests are routed to a different instance (round-robin, redeploy, key
 * rotation), the replayed signed block fails verification mid-turn:
 *   400 The encrypted content gAAA... could not be verified.
 * The SDK must preserve thinking blocks across a turn per the Messages API, so
 * there is no supported client-side switch to stop the replay. The only place
 * that sees EVERY API request — including the tool-use loop's in-memory
 * context that never touches the session file — is the HTTP layer. This proxy
 * is that layer: a 127.0.0.1-only server that the CLI child process talks to
 * instead of the gateway directly.
 *
 * Behaviour per request:
 *  - POST /v1/messages (+ /v1/messages/count_tokens): buffer the JSON body,
 *    strip thinking / redacted_thinking / reasoning blocks from every message
 *    (substituting a blank text block for thinking-only assistant messages, so
 *    role parity is preserved), forward the sanitized body.
 *  - Everything else (GETs, /v1/models, other paths, non-JSON bodies): forward
 *    untouched.
 * Responses are always streamed back unmodified (SSE included) — only the
 * request path is rewritten.
 *
 * Lifecycle: lazily created singleton bound to the daemon process; each CLI
 * spawn takes a client ref via buildCliEnv() and releases it via
 * releaseSanitizingProxy(). The server closes when the last client releases
 * (daemon exit closes it anyway).
 */

import http from 'node:http';
import https from 'node:https';

const STRIPPED_BLOCK_TYPES = new Set(['thinking', 'redacted_thinking', 'reasoning']);
const SANITIZED_PATHS = ['/v1/messages', '/v1/messages/count_tokens'];
const MAX_BODY_BYTES = 64 * 1024 * 1024;

let proxyState = null; // { server, port, clientCount, closeTimer }
// Daemon-side daemons are long-lived and may run several CLI children
// concurrently; close the proxy only after it has had no clients for a while
// (avoids churn when sequential turns gap briefly between spawns).
const IDLE_CLOSE_MS = 60 * 1000;

/**
 * Remove thinking/redacted_thinking/reasoning blocks from one message object.
 * Returns true when anything was stripped.
 */
function stripFromMessage(msg) {
  if (!msg || !Array.isArray(msg.content)) return false;
  const stripped = msg.content.filter(
    (block) => !block || !STRIPPED_BLOCK_TYPES.has(block.type)
  );
  if (stripped.length === msg.content.length) return false;
  // An empty assistant content array is itself an API error — substitute a
  // blank text block for thinking-only messages to keep role parity.
  msg.content = stripped.length > 0 ? stripped : [{ type: 'text', text: ' ' }];
  return true;
}

/**
 * Sanitize a parsed /v1/messages request body in place.
 * @returns {boolean} true when any block was stripped
 */
export function sanitizeMessagesBody(body) {
  if (!body || typeof body !== 'object' || !Array.isArray(body.messages)) {
    return false;
  }
  let changed = false;
  for (const msg of body.messages) {
    if (stripFromMessage(msg)) changed = true;
  }
  // Some gateway shapes carry signed thinking in a system content array too;
  // strip it there as well (system is never a tool-use participant, so dropping
  // a thinking block cannot break role parity the way an assistant slot can).
  if (Array.isArray(body.system)) {
    const stripped = body.system.filter(
      (block) => !block || !STRIPPED_BLOCK_TYPES.has(block.type)
    );
    if (stripped.length !== body.system.length) {
      body.system = stripped;
      changed = true;
    }
  }
  return changed;
}

function shouldSanitize(req) {
  if (req.method !== 'POST') return false;
  const path = (req.url || '').split('?')[0];
  return SANITIZED_PATHS.some((p) => path === p || path.endsWith(p));
}

function hopByHopHeadersStripped(headers) {
  const out = { ...headers };
  delete out.host;
  delete out['content-length'];
  delete out['accept-encoding']; // we forward identity bodies only
  return out;
}

/**
 * Handle one inbound request: sanitize when applicable, forward upstream,
 * stream the upstream response back untouched.
 */
function handleRequest(upstream, clientReq, clientRes) {
  const chunks = [];
  let total = 0;
  let aborted = false;

  clientReq.on('data', (chunk) => {
    total += chunk.length;
    if (total > MAX_BODY_BYTES && !aborted) {
      aborted = true;
      clientReq.destroy();
      clientRes.writeHead(413).end('request body too large');
      return;
    }
    chunks.push(chunk);
  });

  clientReq.on('end', () => {
    if (aborted) return;
    let body = Buffer.concat(chunks);

    if (shouldSanitize(clientReq) && body.length > 0) {
      try {
        const parsed = JSON.parse(body.toString('utf8'));
        if (sanitizeMessagesBody(parsed)) {
          body = Buffer.from(JSON.stringify(parsed), 'utf8');
          console.log('[SANITIZE_PROXY] stripped thinking blocks from', clientReq.url);
        }
      } catch {
        // Non-JSON body — forward as-is.
      }
    }

    const upstreamReq = upstream.transport.request({
      protocol: upstream.protocol,
      hostname: upstream.hostname,
      port: upstream.port,
      path: clientReq.url,
      method: clientReq.method,
      headers: {
        ...hopByHopHeadersStripped(clientReq.headers),
        host: upstream.hostHeader,
        'content-length': body.length
      }
    }, (upstreamRes) => {
      clientRes.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      upstreamRes.pipe(clientRes);
    });

    upstreamReq.on('error', (err) => {
      console.error('[SANITIZE_PROXY] upstream error:', err.message);
      if (!clientRes.headersSent) {
        clientRes.writeHead(502, { 'content-type': 'application/json' });
      }
      clientRes.end(JSON.stringify({ type: 'error', error: { type: 'api_error', message: `sanitizing proxy upstream error: ${err.message}` } }));
    });

    upstreamReq.end(body);
  });

  clientReq.on('error', () => {
    // Client (CLI) went away mid-upload — nothing to forward.
  });
}

function parseUpstream(rawBaseUrl) {
  const url = new URL(rawBaseUrl);
  const isHttps = url.protocol === 'https:';
  return {
    protocol: url.protocol,
    hostname: url.hostname,
    port: url.port || (isHttps ? 443 : 80),
    hostHeader: url.host,
    transport: isHttps ? https : http
  };
}

/**
 * Start (or reuse) the loopback sanitizing proxy pointing at the given gateway.
 *
 * @param {string} upstreamBaseUrl the REAL gateway base URL (e.g. https://gw.example.com)
 * @returns {Promise<string>} loopback base URL to hand to the CLI (http://127.0.0.1:PORT)
 */
export async function ensureSanitizingProxy(upstreamBaseUrl) {
  if (proxyState) {
    proxyState.clientCount++;
    if (proxyState.closeTimer) {
      clearTimeout(proxyState.closeTimer);
      proxyState.closeTimer = null;
    }
    return `http://127.0.0.1:${proxyState.port}`;
  }

  const upstream = parseUpstream(upstreamBaseUrl);
  const server = http.createServer((req, res) => handleRequest(upstream, req, res));

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });

  const port = server.address().port;
  proxyState = { server, port, clientCount: 1, closeTimer: null };
  console.log('[SANITIZE_PROXY] listening on 127.0.0.1:' + port + ' -> ' + upstreamBaseUrl);
  return `http://127.0.0.1:${port}`;
}

/** Release one client ref; schedules server close when no clients remain. */
export function releaseSanitizingProxy() {
  if (!proxyState) return;
  proxyState.clientCount--;
  if (proxyState.clientCount <= 0 && !proxyState.closeTimer) {
    proxyState.closeTimer = setTimeout(() => {
      if (!proxyState || proxyState.clientCount > 0) return;
      const { server } = proxyState;
      proxyState = null;
      server.close(() => {});
    }, IDLE_CLOSE_MS);
    proxyState.closeTimer.unref?.();
  }
}

/** Test hook: reset all proxy state. */
export function __resetSanitizingProxyForTest() {
  if (proxyState) {
    const { server, closeTimer } = proxyState;
    if (closeTimer) clearTimeout(closeTimer);
    proxyState = null;
    server.close(() => {});
  }
}
