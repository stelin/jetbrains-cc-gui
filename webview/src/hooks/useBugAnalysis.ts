import { useCallback, useEffect, useRef, useState } from 'react';
import { sendBridgeEvent } from '../utils/bridge';

/* ──────────────────────────────────────────────────────────────────
 * useBugAnalysis
 *
 * 缺陷「AI 分析」的状态机。**现用于独立分离窗口**(BugAnalysisStandalone):
 * 批量选中缺陷后,Java 开一个脱离 IDE 的 JFrame + 独立 webview,该 webview
 * 引导渲染 BugAnalysisStandalone 并自动 start()。窗口关闭即销毁,数据不留 ——
 * 因此**不做任何持久化**(localStorage 已移除)。
 *
 * IPC(Java→):
 *   - window.onBugAnalysisProgress(多次)  逐 bug 进度
 *   - window.onBugAnalysisResult(一次,终态)
 *   - window.onBugAnalysisStream(多次)   实时分析过程(思考/正文/工具)
 * 这些回调推到【本窗口】的 browser(Java 侧 BugAnalysisFrame 自带 HandlerContext)。
 * ──────────────────────────────────────────────────────────────── */

export type AnalysisStatus = 'idle' | 'running' | 'done' | 'error';

/** 提交分析时的 bug 快照(重新分析 & 分组派单都靠它,与列表实时数据解耦)。 */
export interface BugSnapshot {
  identifier: string;
  serialNumber: string;
  subject: string;
  status: string;
}

/** 单个 bug 的明确性判定。 */
export interface ClarityItem {
  serialNumber: string;
  identifier: string;
  subject: string;
  clarity: 'clear' | 'unclear';
  reason?: string;
  /** 仅 unclear 时有值:还缺哪些信息才能定位。 */
  missing?: string[];
}

/** 一个关联分组(M1:一个 bug 归一个主组)。 */
export interface GroupItem {
  dimension: 'page' | 'api' | 'feature';
  label: string;
  /** serialNumber[] —— 映射回 snapshot 后再拼派单 prefill。 */
  members: string[];
  rootCauseGuess?: string;
}

export interface AnalysisResult {
  bugs: ClarityItem[];
  groups: GroupItem[];
}

/** 工具结果内容(与 SDK tool_result.content 对齐:纯文本或文本块数组)。 */
export type ToolResultContent = string | Array<{ type?: string; text?: string }>;

/**
 * 实时「分析过程」流的一段(分析中只读直播,**渲染成与普通会话一致**的结构化块;不持久化)。
 *
 * - thinking/content:累计文本(同类相邻 delta 合并)→ 渲染为思考块 / Markdown 正文。
 * - tool_use:协调者的一次工具调用(query_bug_details / Task 子智能体 / 其它),携带
 *   name + input → 复用聊天的工具卡片组件渲染(GenericToolBlock / TaskExecutionBlock …)。
 * - tool_result:某次工具调用的输出,按 id 关联回对应 tool_use 卡片(显示「工具调用输出」)。
 */
export type StreamSegment =
  | { kind: 'thinking'; text: string }
  | { kind: 'content'; text: string }
  | { kind: 'tool_use'; id: string; name: string; input: Record<string, unknown> }
  | { kind: 'tool_result'; id: string; content?: ToolResultContent; isError?: boolean };

export interface AnalysisProgress {
  total: number;
  /** 已完成的 bug identifier。 */
  doneIds: string[];
  /** 正在分析的 identifier(null = 无)。 */
  currentId: string | null;
  /** 兜底:已观察到的工具调用次数(工具事件解析不到时改用它显示进度)。 */
  toolCalls: number;
}

export interface BugAnalysisState {
  status: AnalysisStatus;
  /** 当前分析所属项目(进度/结果帧的 projectId 守卫据此防串台)。 */
  projectId: string;
  /** 本次/上次分析的 bug 快照。 */
  snapshot: BugSnapshot[];
  progress: AnalysisProgress;
  result: AnalysisResult | null;
  /** 解析失败兜底原文(error 态有值)。 */
  raw: string | null;
  model: string;
  reasoning: string;
  /** 最大并发子智能体数(3/4/5)。协调者据此分批并发分析。 */
  concurrency: number;
  analyzedAt: number | null;
  error: string | null;
  /** 实时分析过程(仅 running 期间填充,终态/重置清空,不持久化)。 */
  stream: StreamSegment[];
}

export interface UseBugAnalysis {
  state: BugAnalysisState;
  /** 设定当前项目(切到新项目即清空显示)。 */
  bindProject: (projectId: string) => void;
  /** 发起分析:置 running + 初始化进度 + 发 analyze_bugs。可显式带 projectId / concurrency(独立窗口首启用)。 */
  start: (snapshot: BugSnapshot[], model: string, reasoning: string, projectId?: string, concurrency?: number) => void;
  /** 复用 state.snapshot 重跑(即使列表数据已变也不受影响)。 */
  reAnalyze: () => void;
  /** 取消进行中的分析,回到空态。 */
  cancel: () => void;
  /** 清显示(回到当前项目空态)。 */
  clear: () => void;
}

const EMPTY_PROGRESS: AnalysisProgress = { total: 0, doneIds: [], currentId: null, toolCalls: 0 };

const INITIAL_STATE: BugAnalysisState = {
  status: 'idle',
  projectId: '',
  snapshot: [],
  progress: EMPTY_PROGRESS,
  result: null,
  raw: null,
  model: '',
  reasoning: '',
  concurrency: 3,
  analyzedAt: null,
  error: null,
  stream: [],
};

/** 某项目的空态(保留 projectId)。 */
function freshState(projectId: string): BugAnalysisState {
  return { ...INITIAL_STATE, projectId };
}

// ── IPC 回调载荷 ──────────────────────────────────────────────────
interface ProgressPayload {
  projectId?: string;
  total?: number;
  doneIds?: string[];
  currentId?: string | null;
  toolCalls?: number;
}

interface ResultPayload {
  ok?: boolean;
  projectId?: string;
  model?: string;
  reasoning?: string;
  result?: AnalysisResult;
  raw?: string | null;
  error?: string;
}

export function useBugAnalysis(): UseBugAnalysis {
  const [state, setState] = useState<BugAnalysisState>(INITIAL_STATE);

  // 镜像最新 state,供 window 回调读取(回调只注册一次,projectId 守卫也靠它)。
  const stateRef = useRef(state);
  stateRef.current = state;

  // window 回调注册(挂载一次,卸载删除)。
  useEffect(() => {
    window.onBugAnalysisProgress = (json: string) => {
      try {
        const p = JSON.parse(json) as ProgressPayload;
        if ((p.projectId || '') !== stateRef.current.projectId) return; // 串台守卫
        setState((prev) => {
          if (prev.status !== 'running') return prev; // 终态后迟到进度忽略
          return {
            ...prev,
            progress: {
              total: typeof p.total === 'number' ? p.total : prev.progress.total,
              doneIds: Array.isArray(p.doneIds) ? p.doneIds : prev.progress.doneIds,
              currentId: p.currentId ?? null,
              toolCalls: typeof p.toolCalls === 'number' ? p.toolCalls : prev.progress.toolCalls,
            },
          };
        });
      } catch {
        // 忽略损坏的进度帧。
      }
    };

    window.onBugAnalysisResult = (json: string) => {
      try {
        const p = JSON.parse(json) as ResultPayload;
        const cur = stateRef.current;
        if ((p.projectId || '') !== cur.projectId) return; // 串台守卫
        const analyzedAt = Date.now();
        if (p.ok) {
          const result: AnalysisResult = p.result || { bugs: [], groups: [] };
          setState((prev) => ({
            ...prev,
            status: 'done',
            result,
            raw: null,
            error: null,
            model: p.model || prev.model,
            reasoning: p.reasoning || prev.reasoning,
            analyzedAt,
          }));
        } else {
          setState((prev) => ({
            ...prev,
            status: 'error',
            result: null,
            raw: p.raw ?? null,
            error: p.error || 'analysis failed',
            analyzedAt,
          }));
        }
      } catch {
        // 忽略损坏的终态帧。
      }
    };

    // 实时分析过程(思考/正文 delta + 结构化工具调用/结果)。仅 running 期间累积;
    // thinking/content 同类相邻 delta 合并,tool_use/tool_result 离散自成一段。
    // projectId 守卫 + status==='running' 守卫防串台/迟到帧。
    window.onBugAnalysisStream = (json: string) => {
      try {
        const p = JSON.parse(json) as {
          projectId?: string;
          kind?: string;
          text?: string;
          id?: string;
          name?: string;
          input?: Record<string, unknown>;
          content?: ToolResultContent;
          is_error?: boolean;
        };
        if ((p.projectId || '') !== stateRef.current.projectId) return;
        const kind = p.kind;
        setState((prev) => {
          if (prev.status !== 'running') return prev;
          const segs = prev.stream;
          if (kind === 'thinking' || kind === 'content') {
            const text = p.text || '';
            if (!text) return prev; // 空文本无意义
            const last = segs[segs.length - 1];
            if (last && last.kind === kind) {
              return { ...prev, stream: [...segs.slice(0, -1), { kind, text: last.text + text }] };
            }
            return { ...prev, stream: [...segs, { kind, text }] };
          }
          if (kind === 'tool_use') {
            return {
              ...prev,
              stream: [...segs, { kind, id: p.id || '', name: p.name || '', input: p.input || {} }],
            };
          }
          if (kind === 'tool_result') {
            return {
              ...prev,
              stream: [...segs, { kind, id: p.id || '', content: p.content, isError: p.is_error }],
            };
          }
          return prev; // 未知 kind 忽略
        });
      } catch {
        // 忽略损坏的流帧。
      }
    };

    return () => {
      delete window.onBugAnalysisProgress;
      delete window.onBugAnalysisResult;
      delete window.onBugAnalysisStream;
    };
  }, []);

  const bindProject = useCallback((projectId: string) => {
    if (stateRef.current.projectId === projectId) return;
    setState(freshState(projectId));
  }, []);

  const start = useCallback(
    (snapshot: BugSnapshot[], model: string, reasoning: string, projectId?: string, concurrency?: number) => {
      const pid = projectId ?? stateRef.current.projectId;
      const conc = concurrency ?? stateRef.current.concurrency ?? 3;
      setState((prev) => ({
        ...prev,
        projectId: pid,
        status: 'running',
        snapshot,
        model,
        reasoning,
        concurrency: conc,
        progress: { total: snapshot.length, doneIds: [], currentId: null, toolCalls: 0 },
        result: null,
        raw: null,
        error: null,
        analyzedAt: null,
        stream: [],
      }));
      sendBridgeEvent(
        'analyze_bugs',
        JSON.stringify({ projectId: pid, bugs: snapshot, model, reasoningEffort: reasoning, concurrency: conc }),
      );
    },
    [],
  );

  const reAnalyze = useCallback(() => {
    const { snapshot, model, reasoning, projectId, concurrency } = stateRef.current;
    if (snapshot.length === 0) return;
    start(snapshot, model, reasoning, projectId, concurrency);
  }, [start]);

  const cancel = useCallback(() => {
    const projectId = stateRef.current.projectId;
    sendBridgeEvent('cancel_bug_analysis', JSON.stringify({ projectId }));
    setState(freshState(projectId));
  }, []);

  const clear = useCallback(() => {
    setState((prev) => freshState(prev.projectId));
  }, []);

  return { state, bindProject, start, reAnalyze, cancel, clear };
}
