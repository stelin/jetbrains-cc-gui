/**
 * Session management service module.
 * Responsible for session persistence and history message management.
 */

import { existsSync, createReadStream, mkdirSync, readFileSync, writeFileSync, renameSync, appendFileSync, statSync } from 'fs';
import { readFile } from 'fs/promises';
import { join } from 'path';
import { randomUUID } from 'crypto';
import { createInterface } from 'readline';
import { getClaudeDir } from '../../utils/path-utils.js';

/**
 * Append a message to the JSONL history file.
 * Adds necessary metadata fields to ensure compatibility with the history reader.
 */
export function persistJsonlMessage(sessionId, cwd, obj) {
  try {
    const projectsDir = join(getClaudeDir(), 'projects');
    const sanitizedCwd = (cwd || process.cwd()).replace(/[^a-zA-Z0-9]/g, '-');
    const projectHistoryDir = join(projectsDir, sanitizedCwd);
    mkdirSync(projectHistoryDir, { recursive: true });
    const sessionFile = join(projectHistoryDir, `${sessionId}.jsonl`);

    // Add necessary metadata fields to ensure compatibility with ClaudeHistoryReader
    const enrichedObj = {
      ...obj,
      uuid: randomUUID(),
      sessionId: sessionId,
      timestamp: new Date().toISOString()
    };

    appendFileSync(sessionFile, JSON.stringify(enrichedObj) + '\n', 'utf8');
    console.log('[PERSIST] Message saved to:', sessionFile);
  } catch (e) {
    console.error('[PERSIST_ERROR]', e.message);
  }
}

/**
 * Remove thinking / redacted_thinking / reasoning blocks from a reconstructed
 * assistant content array.
 *
 * Extended-thinking blocks carry a cryptographic `signature` that is only valid
 * for the exact request that produced them. When we manually rebuild a resumed
 * conversation and replay a prior turn's thinking blocks into a fresh messages
 * array, the API rejects them with:
 *   400 messages.N.content.M: thinking or redacted_thinking blocks in the
 *   latest assistant message cannot be modified.
 * The Messages API only requires thinking blocks to be preserved unmodified
 * within the SAME turn's tool-use loop (handled by the Agent SDK), never across
 * turns — so dropping historical thinking blocks here is both safe and required
 * for this non-thinking fallback request.
 *
 * OpenAI-compatible proxies (GPT-5.x via Claude protocol) use `reasoning` blocks
 * with `encrypted_content` (Fernet-encrypted) instead of Anthropic's `thinking`
 * format. Those encrypted signatures are equally session-scoped and must be
 * stripped when resuming to avoid:
 *   400 The encrypted content gAAA...ZwgV could not be verified.
 */
function stripThinkingBlocks(content) {
  if (!Array.isArray(content)) {
    return content;
  }
  return content.filter(
    (block) => !block || (
      block.type !== 'thinking' &&
      block.type !== 'redacted_thinking' &&
      block.type !== 'reasoning'
    )
  );
}

/**
 * Strip thinking / redacted_thinking / reasoning blocks from a persisted session
 * JSONL **in place**, so an Agent-SDK resume (`options.resume`) does not replay
 * encrypted reasoning content that a third-party gateway can no longer verify.
 *
 * Background: Claude Code persists assistant thinking blocks (carrying encrypted
 * signatures — Fernet `gAAA...` payloads on OpenAI-compatible gateways) into the
 * session file and replays them verbatim when the CLI resumes the session. The
 * official Anthropic API verifies these signatures fine, but Claude-protocol-
 * compatible gateways (GPT-5.x) can only decrypt content produced under their
 * current key/instance — after key rotation or routing to a different backend
 * instance the replay fails the whole turn with:
 *   400 The encrypted content gAAA...muWJ could not be verified.
 * Once a session file contains such a block, EVERY subsequent resume of that
 * session fails. Dropping historical thinking blocks is safe: the Messages API
 * only requires them unmodified within the SAME turn's tool-use loop, never
 * across turns (see stripThinkingBlocks above).
 *
 * Only call this BEFORE a resume starts (no live runtime may own the session —
 * a running CLI appends to the file and an in-place rewrite could race it).
 * Best-effort: any failure is logged and swallowed so resume proceeds unchanged.
 *
 * @returns {boolean} true if the file was rewritten
 */
export function sanitizeSessionFileForResume(sessionId, cwd) {
  try {
    const sessionFile = resolveSessionFile(sessionId, cwd);
    if (!existsSync(sessionFile)) {
      return false;
    }

    const lines = readFileSync(sessionFile, 'utf8').split('\n');
    let changed = false;
    const rewritten = lines.map((line) => {
      if (!line.trim()) return line;
      let msg;
      try { msg = JSON.parse(line); } catch { return line; }
      const content = msg?.message?.content;
      if (msg?.type !== 'assistant' || !Array.isArray(content)) return line;
      const cleaned = stripThinkingBlocks(content);
      if (cleaned.length === content.length) return line;
      changed = true;
      // An empty assistant content array is itself an API error — substitute a
      // placeholder text block for thinking-only turns (keeps role parity and
      // the uuid/parentUuid chain intact).
      msg.message.content = cleaned.length > 0 ? cleaned : [{ type: 'text', text: ' ' }];
      return JSON.stringify(msg);
    });

    if (!changed) {
      return false;
    }
    // Atomic rewrite (tmp + rename) so a concurrent reader never sees a
    // half-written file.
    const tmpFile = `${sessionFile}.sanitize-${randomUUID()}.tmp`;
    writeFileSync(tmpFile, rewritten.join('\n'), 'utf8');
    renameSync(tmpFile, sessionFile);
    console.log('[RESUME_SANITIZE] Stripped encrypted thinking/reasoning blocks from', sessionFile);
    return true;
  } catch (e) {
    console.error('[RESUME_SANITIZE_ERROR]', e.message);
    return false;
  }
}

/**
 * Load session history messages (used to maintain context when resuming a session).
 * Returns an array of messages in the Anthropic Messages API format.
 */
export function loadSessionHistory(sessionId, cwd) {
  try {
    const projectsDir = join(getClaudeDir(), 'projects');
    const sanitizedCwd = (cwd || process.cwd()).replace(/[^a-zA-Z0-9]/g, '-');
    const sessionFile = join(projectsDir, sanitizedCwd, `${sessionId}.jsonl`);

    if (!existsSync(sessionFile)) {
      return [];
    }

    const content = readFileSync(sessionFile, 'utf8');
    const lines = content.split('\n').filter(line => line.trim());
    const messages = [];

    for (const line of lines) {
      try {
        const msg = JSON.parse(line);
        if (msg.type === 'user' && msg.message && msg.message.content) {
          messages.push({
            role: 'user',
            content: msg.message.content
          });
        } else if (msg.type === 'assistant' && msg.message && msg.message.content) {
          const cleanedContent = stripThinkingBlocks(msg.message.content);
          // Skip turns that became empty after stripping (thinking-only turns);
          // an empty assistant content array is itself an API error.
          if (Array.isArray(cleanedContent) && cleanedContent.length === 0) {
            continue;
          }
          messages.push({
            role: 'assistant',
            content: cleanedContent
          });
        }
      } catch (e) {
        // Skip lines that fail to parse
      }
    }

    // Exclude the last user message (since we already persisted the current user message before calling this function)
    if (messages.length > 0 && messages[messages.length - 1].role === 'user') {
      messages.pop();
    }

    return messages;
  } catch (e) {
    console.error('[LOAD_HISTORY_ERROR]', e.message);
    return [];
  }
}

/**
 * Get session history messages.
 * Reads from the ~/.claude/projects/ directory.
 */
export async function getSessionMessages(sessionId, cwd = null) {
  try {
    const sessionFile = resolveSessionFile(sessionId, cwd);

    if (!existsSync(sessionFile)) {
      console.log(JSON.stringify({
        success: false,
        error: 'Session file not found'
      }));
      return;
    }

    // Read the JSONL file
    const content = await readFile(sessionFile, 'utf8');
    const messages = content
      .split('\n')
      .filter(line => line.trim())
      .map(line => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter(msg => msg !== null);

    console.log(JSON.stringify({
      success: true,
      messages
    }));

  } catch (error) {
    console.error('[GET_SESSION_ERROR]', error.message);
    console.log(JSON.stringify({
      success: false,
      error: error.message
    }));
  }
}

export async function getLatestUserMessage(sessionId, cwd = null) {
  try {
    const sessionFile = resolveSessionFile(sessionId, cwd);

    if (!existsSync(sessionFile)) {
      console.log(JSON.stringify({
        success: true,
        message: null
      }));
      return;
    }

    // Read only the tail of the file for performance on large sessions
    const TAIL_BYTES = 32 * 1024;
    const stat = statSync(sessionFile);
    const startByte = Math.max(0, stat.size - TAIL_BYTES);

    let latestUserMessage = null;
    const rl = createInterface({
      input: createReadStream(sessionFile, { encoding: 'utf8', start: startByte }),
      crlfDelay: Infinity
    });

    let firstLine = startByte > 0;
    for await (const line of rl) {
      // Skip potentially partial first line when reading from mid-file
      if (firstLine) { firstLine = false; continue; }
      if (!line.trim()) continue;
      try {
        const message = JSON.parse(line);
        if (isUserTextMessage(message)) {
          latestUserMessage = message;
        }
      } catch {
        // Ignore malformed JSONL entries
      }
    }

    console.log(JSON.stringify({
      success: true,
      message: latestUserMessage
    }));
  } catch (error) {
    console.error('[GET_LATEST_USER_ERROR]', error.message);
    console.log(JSON.stringify({
      success: false,
      error: error.message
    }));
  }
}

function isUserTextMessage(message) {
  return Boolean(
    message &&
    message.type === 'user' &&
    typeof message.uuid === 'string' &&
    extractTextContent(message)?.trim()
  );
}

function extractTextContent(message) {
  const content = message?.message?.content;
  if (!content) {
    return '';
  }

  if (typeof content === 'string') {
    return content;
  }

  if (!Array.isArray(content)) {
    return '';
  }

  return content
    .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text)
    .join('\n');
}

function resolveSessionFile(sessionId, cwd = null) {
  if (!sessionId || /[\/\\]/.test(sessionId)) {
    throw new Error('Invalid session ID');
  }
  const projectsDir = join(getClaudeDir(), 'projects');
  const sanitizedCwd = (cwd || process.cwd()).replace(/[^a-zA-Z0-9]/g, '-');
  const projectHistoryDir = join(projectsDir, sanitizedCwd);
  return join(projectHistoryDir, `${sessionId}.jsonl`);
}
