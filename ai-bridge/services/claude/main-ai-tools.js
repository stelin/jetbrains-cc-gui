/**
 * Main-AI side MCP server. Exposes `report_turn_completion` which the main AI
 * must call before ending each turn in Pair mode. Emits a [TURN_REPORT] NDJSON
 * line tagged with sessionId / turnId / directiveId so the Java EventBus can
 * correlate the report back to the supervisor's outstanding directive.
 *
 * Ported into the bundled (local) daemon from ai-bridge-server so Pair mode
 * works in local mode too — previously this file was missing locally, so the
 * main AI never received the tool and never notified the supervisor. Kept
 * self-contained: turnId is generated inline (no protocol-v2 dependency) and
 * the summary is emitted inline (no spill-to-file), matching the local bundle's
 * leaner dependency set.
 */

import { loadClaudeSdk, loadZod } from '../../utils/sdk-loader.js';
import { writeFileSync, mkdirSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildQueryBugDetailsTool } from '../supervisor/yunxiao-tools.js';

export const MAIN_MCP_NAME = 'main';
export const REPORT_TURN_COMPLETION_TOOL_NAME = 'report_turn_completion';

// Spill the report to a daemon-managed file once the serialized payload exceeds
// this size, so the IPC line stays small and the supervisor can Read it on
// demand. 8KB matches the inject_prompt directive-spill threshold (the reverse
// direction). See docs/supervisor/supervisor-plan-generation-and-report-spill-design.md.
const REPORT_SPILL_THRESHOLD = 8 * 1024;

function generateTurnId() {
  return `t_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Write a large turn report to a managed scratch file (NOT the project tree) and
 * return its absolute path, or null on failure (caller falls back to inline).
 * The main AI has no pairId, only a sessionId — key the path by sessionId+turnId.
 */
function writeManagedReport(sessionId, turnId, json) {
  try {
    const base = join(homedir(), '.codemoss', 'turn-reports', sessionId || 'anon');
    mkdirSync(base, { recursive: true });
    const p = join(base, `turn-${turnId}.json`);
    writeFileSync(p, json, 'utf8');
    return p;
  } catch (_) {
    try {
      const p = join(tmpdir(), `codemoss-turn-${turnId}.json`);
      writeFileSync(p, json, 'utf8');
      return p;
    } catch (__) {
      return null;
    }
  }
}

function buildSchema(z) {
  return {
    summary: z.string().describe('One- to two-sentence task-level summary of what was accomplished this turn.'),
    deliverables: z.array(z.object({
      path: z.string().describe('Project-root-relative POSIX path.'),
      change: z.string().describe('Short description of what changed in this file (<=200 chars).'),
      confidence: z.enum(['high', 'medium', 'low']).optional()
        .describe('Per-deliverable confidence; omit when unsure.'),
    })).describe('Files you created or modified this turn. Empty list if no file change (e.g. pure investigation turn).'),
    verifications: z.array(z.object({
      command: z.string(),
      pass: z.boolean(),
      stderrTail: z.string().optional().describe('Failure detail, <=1KB tail of stderr.'),
    })).optional().describe('Any verification commands you ran (build/test/lint).'),
    selfAssessment: z.object({
      confidence: z.enum(['high', 'medium', 'low'])
        .describe('Your overall confidence in this turn\'s correctness.'),
      concerns: z.array(z.string())
        .describe('Specific points you are unsure about. Empty list when fully confident.'),
      suggestedReview: z.string().optional()
        .describe('Optional: suggest where supervisor should focus review (e.g. "user_dao.go:42-58").'),
    }).describe('Required self-assessment. Supervisor uses this to triage whether to run extra review.'),
    durationMs: z.number().optional()
      .describe('Optional: this turn\'s wall-clock duration in milliseconds. ' +
        'If omitted, Java side will compute it from turn boundaries.'),
  };
}

export async function buildMainAiMcpServer(runtimeRef) {
  const [sdk, zod] = await Promise.all([loadClaudeSdk(), loadZod()]);
  const z = zod?.z ?? zod?.default?.z ?? zod;
  if (typeof sdk?.createSdkMcpServer !== 'function' || typeof sdk?.tool !== 'function') {
    throw new Error('Claude SDK does not expose createSdkMcpServer/tool');
  }

  const tools = [];

  // query_bug_details — 云效缺陷详情聚合工具。Available to the main AI in ALL modes
  // (normal chat + Pair). Credentials come from process.env.YUNXIAO_* (Java buildDaemonEnv).
  try {
    tools.push(buildQueryBugDetailsTool(sdk, zod));
  } catch (e) {
    console.error('[MAIN_AI_TOOLS] buildQueryBugDetailsTool failed:', e?.message || e);
  }

  // report_turn_completion — Pair mode only (needs a supervisor to report to).
  if (!runtimeRef || !runtimeRef.pairId) {
    return sdk.createSdkMcpServer({
      name: MAIN_MCP_NAME,
      version: '1.0.0',
      tools,
    });
  }

  const tool = sdk.tool(
    REPORT_TURN_COMPLETION_TOOL_NAME,
    'MUST be called by the main AI before ending each turn when running in Pair ' +
    'mode. Reports task-level outcome + self-assessment so the supervisor can ' +
    'triage review effort. Calling more than once per turn overwrites prior calls ' +
    '(last write wins).',
    buildSchema(z),
    async (args) => {
      const turnId = generateTurnId();
      const durationMs = typeof args.durationMs === 'number' && args.durationMs >= 0
        ? args.durationMs : 0;

      // Full payload, then decide inline-vs-spill by serialized size. When spilled,
      // the IPC line keeps only the small navigation fields (summary, deliverable
      // paths, selfAssessment) + spilledPath; the supervisor Reads the file for the
      // rest. Treated as navigation only — the supervisor still verifies real files.
      const fullPayload = {
        summary: args.summary,
        deliverables: args.deliverables || [],
        verifications: args.verifications || [],
        selfAssessment: args.selfAssessment,
        subagentSummary: null,
        // Main AI may pass its own measurement; otherwise emit 0 and let
        // the Java side compute from turn boundaries (turnStartedAt).
        durationMs,
        spilledPath: null,
      };

      let linePayload = fullPayload;
      try {
        const probe = JSON.stringify(fullPayload);
        if (probe.length > REPORT_SPILL_THRESHOLD) {
          const spilledPath = writeManagedReport(runtimeRef.sessionId, turnId, probe);
          if (spilledPath) {
            linePayload = {
              summary: args.summary,
              // Keep deliverable paths inline — small and used by the supervisor
              // to know which real files to Read (verifications spill with the file).
              deliverables: args.deliverables || [],
              verifications: [],
              selfAssessment: args.selfAssessment,
              subagentSummary: null,
              durationMs,
              spilledPath,
            };
          }
        }
      } catch (_) { /* fall back to inline on any spill/serialize error */ }

      try {
        process.stdout.write('[TURN_REPORT] ' + JSON.stringify({
          type: 'turn_report',
          sessionId: runtimeRef.sessionId || null,
          turnId,
          directiveId: runtimeRef.activeDirectiveId || null,
          ts: Date.now(),
          payload: linePayload,
        }) + '\n');
      } catch (_) { /* stdout closed */ }

      return {
        content: [{
          type: 'text',
          text: 'turn completion reported (turnId=' + turnId + ')',
        }],
      };
    }
  );
  tools.push(tool);

  return sdk.createSdkMcpServer({
    name: MAIN_MCP_NAME,
    version: '1.0.0',
    tools,
  });
}
