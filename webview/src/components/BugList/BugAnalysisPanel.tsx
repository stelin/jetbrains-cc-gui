import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sendBridgeEvent } from '../../utils/bridge';
import { buildPrefillMulti } from '../../utils/bugPrefill';
import { ContentBlockRenderer } from '../MessageItem/ContentBlockRenderer';
import type { ClaudeContentBlock, ToolResultBlock } from '../../types';
import type { YunxiaoBug } from '../../hooks/useYunxiaoBugs';
import type { BugSnapshot, ClarityItem, GroupItem, StreamSegment, UseBugAnalysis } from '../../hooks/useBugAnalysis';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * BugAnalysisPanel
 *
 * 缺陷「AI 分析」Tab 的展示面板(设计 §5/§6)。按 analysis.state.status
 * 渲染四态:idle 空态 / running 分析中(逐 bug 进度 + 退化兜底) /
 * done 结果(① 明确性清单 ② 关联分组,单页上下两段) / error 兜底原文。
 *
 * 分组派单复用 utils/bugPrefill 的 buildPrefillMulti:把分组 members
 * (serialNumber[])映射回 state.snapshot,再拼预填文案开监督者/会话 Tab。
 * 状态/动作来自 App 级 useBugAnalysis(本组件只消费,不持有)。
 * ──────────────────────────────────────────────────────────────── */

interface BugAnalysisPanelProps {
  /** App 级 useBugAnalysis 返回值(state + cancel/reAnalyze 等动作)。 */
  analysis: UseBugAnalysis;
  /** 云效设置里配置的追加文案,透传给 buildPrefillMulti。 */
  appendPrompt: string;
}

/** 维度 → 图标。 */
const DIMENSION_ICON: Record<GroupItem['dimension'], string> = {
  page: '📄',
  api: '🔌',
  feature: '🧩',
};

/** 维度 → i18n label key。 */
const DIMENSION_LABEL_KEY: Record<GroupItem['dimension'], string> = {
  page: 'bugAnalysis.dimPage',
  api: 'bugAnalysis.dimApi',
  feature: 'bugAnalysis.dimFeature',
};

/** BugSnapshot → buildPrefillMulti 所需的最小 YunxiaoBug(只用到 identifier/serialNumber/subject/status)。 */
const snapshotToBug = (s: BugSnapshot): YunxiaoBug => ({
  identifier: s.identifier,
  serialNumber: s.serialNumber,
  subject: s.subject,
  status: s.status,
});

/**
 * 归一化缺陷编号用于「结果编号 ↔ 快照」对齐:去掉可能的「BUG-」前缀(大小写不敏感)+去空白。
 * 模型回的 result.bugs/groups.members 常带「BUG-」前缀(如 "BUG-SBKL-712"),而快照里是裸编号
 * (如 "SBKL-712");不归一就会:① 清单显示双前缀「BUG-BUG-…」② members→snapshot 命中失败 →
 * groupSnapshots/selectedSnapshots 恒空 → 建监督者/建会话按钮全被禁用。
 */
const normSerial = (s: string): string => (s || '').trim().replace(/^bug-/i, '');

export function BugAnalysisPanel({ analysis, appendPrompt }: BugAnalysisPanelProps) {
  const { t } = useTranslation();
  const { state } = analysis;

  // 底部跨组勾选:本地 Set<serialNumber>,默认全不选。仅 done 态用到,
  // 但 hooks 必须无条件声明在顶部。
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // 实时「分析过程」自动滚到底(像普通会话的流式输出)。stream/状态变化即贴底。
  const streamRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = streamRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [state.stream, state.status]);

  // 思考块折叠态(默认展开,便于实时看协调者推理);按 segment 下标记忆。
  const [collapsedThinking, setCollapsedThinking] = useState<Record<number, boolean>>({});
  const toggleThinking = useCallback((i: number) => {
    setCollapsedThinking((prev) => ({ ...prev, [i]: !prev[i] }));
  }, []);

  // tool_result 段按 id 收成表,供工具卡片查「工具调用输出」(与聊天的 findToolResult 同构)。
  const toolResults = useMemo(() => {
    const m = new Map<string, ToolResultBlock>();
    state.stream.forEach((s) => {
      if (s.kind === 'tool_result' && s.id) {
        m.set(s.id, { type: 'tool_result', tool_use_id: s.id, content: s.content, is_error: s.isError });
      }
    });
    return m;
  }, [state.stream]);

  const findStreamToolResult = useCallback(
    (toolId: string | undefined): ToolResultBlock | null => (toolId ? toolResults.get(toolId) ?? null : null),
    [toolResults],
  );

  // 一段流 → 一个聊天内容块(tool_result 不单独渲染,而是附到对应 tool_use 卡片)。
  const segmentToBlock = (seg: StreamSegment): ClaudeContentBlock | null => {
    switch (seg.kind) {
      case 'thinking':
        return { type: 'thinking', thinking: seg.text, text: seg.text };
      case 'content':
        return { type: 'text', text: seg.text };
      case 'tool_use':
        return { type: 'tool_use', id: seg.id, name: seg.name, input: seg.input };
      default:
        return null;
    }
  };

  // 最后一个「可渲染」段的下标(用于流式光标/思考标题)。
  const lastRenderableIndex = useMemo(() => {
    for (let i = state.stream.length - 1; i >= 0; i -= 1) {
      if (state.stream[i].kind !== 'tool_result') return i;
    }
    return -1;
  }, [state.stream]);

  // 快照按归一化编号建表,供「结果编号 → 快照」鲁棒命中(忽略 BUG- 前缀差异)。
  const snapshotBySerial = useMemo(() => {
    const m = new Map<string, BugSnapshot>();
    state.snapshot.forEach((s) => m.set(normSerial(s.serialNumber), s));
    return m;
  }, [state.snapshot]);

  // 所有分组里出现过的(归一化)serialNumber 去重 —— 底部「全选」与勾选集合都以归一化为键。
  const allSerials = useMemo(() => {
    const set = new Set<string>();
    (state.result?.groups || []).forEach((g) => g.members.forEach((m) => set.add(normSerial(m))));
    return Array.from(set);
  }, [state.result]);

  // 选中集合映射回 snapshot(底部派单用);selected 存归一化编号,故按归一化比对。
  const selectedSnapshots = useMemo(
    () => state.snapshot.filter((s) => selected.has(normSerial(s.serialNumber))),
    [state.snapshot, selected],
  );

  // members(serialNumber[])→ BugSnapshot[](按归一化编号命中,丢弃找不到的)。
  const membersToSnapshots = (members: string[]): BugSnapshot[] =>
    members
      .map((sn) => snapshotBySerial.get(normSerial(sn)))
      .filter((s): s is BugSnapshot => !!s);

  const toggleSelected = (sn: string) => {
    const key = normSerial(sn);
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleSelectAll = () => {
    const allSelected = allSerials.length > 0 && allSerials.every((sn) => selected.has(sn));
    setSelected(allSelected ? new Set() : new Set(allSerials));
  };

  // ── 派单(§6,与现有批量按钮同款 IPC)──────────────────────────────
  const dispatchSupervisor = (bugs: BugSnapshot[]) => {
    if (bugs.length === 0) return;
    sendBridgeEvent(
      'create_new_supervised_tab',
      JSON.stringify({
        agentId: 'bug-supervisor',
        initialComposerText: buildPrefillMulti(bugs.map(snapshotToBug), appendPrompt),
      }),
    );
  };

  const dispatchSession = (bugs: BugSnapshot[]) => {
    if (bugs.length === 0) return;
    sendBridgeEvent(
      'create_new_tab',
      JSON.stringify({ initialComposerText: buildPrefillMulti(bugs.map(snapshotToBug), appendPrompt) }),
    );
  };

  // ── idle(§5.1)──────────────────────────────────────────────────
  const renderIdle = () => (
    <div className={styles.analysisEmpty}>
      <div className={styles.analysisEmptyIcon}>🔬</div>
      <div className={styles.analysisEmptyHint}>{t('bugAnalysis.emptyHint')}</div>
    </div>
  );

  // ── running(§5.2)───────────────────────────────────────────────
  const renderRunning = () => {
    const { progress, snapshot } = state;
    // 退化兜底:工具事件解析不到逐 bug 标记(doneIds/currentId 都空)但确有工具调用。
    const degraded = progress.doneIds.length === 0 && !progress.currentId && progress.toolCalls > 0;
    const pct = progress.total > 0 ? (progress.doneIds.length / progress.total) * 100 : 0;
    return (
      <div className={styles.analysisRunning}>
        <div className={styles.analysisHeader}>
          <span className={styles.analysisHeaderInfo}>
            {t('bugAnalysis.header', { model: state.model, reasoning: state.reasoning })}
          </span>
          <button type="button" className={styles.analysisCancelBtn} onClick={analysis.cancel}>
            {t('bugAnalysis.cancel')}
          </button>
        </div>

        {degraded ? (
          <div className={styles.analysisProgressWrap}>
            <div className={styles.analysisProgressBar}>
              <div className={styles.analysisProgressIndeterminate} />
            </div>
            <div className={styles.analysisProgressText}>
              {t('bugAnalysis.toolCalls', { n: progress.toolCalls })}
            </div>
          </div>
        ) : (
          <>
            <div className={styles.analysisProgressWrap}>
              <div className={styles.analysisProgressBar}>
                <div className={styles.analysisProgressFill} style={{ width: `${pct}%` }} />
              </div>
              <div className={styles.analysisProgressText}>
                {t('bugAnalysis.analyzing', { done: progress.doneIds.length, total: progress.total })}
              </div>
            </div>

            <div className={styles.analysisBugRows}>
              {snapshot.map((b) => {
                const done = progress.doneIds.includes(b.identifier);
                const current = b.identifier === progress.currentId;
                const mark = done ? '✓' : current ? '⟳' : '◌';
                const markClass = done ? styles.markDone : current ? styles.markCurrent : styles.markQueued;
                return (
                  <div key={b.identifier || b.serialNumber} className={styles.analysisBugRow}>
                    <span className={`${styles.analysisBugMark} ${markClass}`}>{mark}</span>
                    <span className={styles.analysisBugSerial}>BUG-{b.serialNumber}</span>
                    <span className={styles.analysisBugSubject} title={b.subject}>
                      {b.subject}
                    </span>
                    {current && <span className={styles.analysisBugHint}>{t('bugAnalysis.fetchingDetail')}</span>}
                    {!done && !current && <span className={styles.analysisBugHint}>{t('bugAnalysis.queued')}</span>}
                  </div>
                );
              })}
            </div>
          </>
        )}

        {/* 实时分析过程(只读直播,复用聊天的消息块组件:思考块 / Markdown 正文 /
            工具调用卡片(含输出)/ 子智能体 Task 状态 —— 与普通对话框一致)*/}
        <div className={styles.streamSection}>
          <div className={styles.streamTitle}>{t('bugAnalysis.processTitle')}</div>
          <div className={styles.streamFeed} ref={streamRef}>
            {state.stream.length === 0 ? (
              <div className={styles.streamWaiting}>{t('bugAnalysis.streamWaiting')}</div>
            ) : (
              <div className="message assistant">
                <div className="message-content">
                  {state.stream.map((seg, i) => {
                    const block = segmentToBlock(seg);
                    if (!block) return null; // tool_result 段:已并入对应工具卡片
                    const isLastBlock = i === lastRenderableIndex;
                    return (
                      <div key={i} className="content-block">
                        <ContentBlockRenderer
                          block={block}
                          messageIndex={0}
                          messageType="assistant"
                          isStreaming={state.status === 'running' && isLastBlock}
                          isThinkingExpanded={!collapsedThinking[i]}
                          isThinking={false}
                          isLastMessage
                          isLastBlock={isLastBlock}
                          t={t}
                          onToggleThinking={() => toggleThinking(i)}
                          findToolResult={findStreamToolResult}
                        />
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        </div>

        <div className={styles.analysisIsolatedHint}>ⓘ {t('bugAnalysis.isolatedHint')}</div>
      </div>
    );
  };

  // ── done(§5.3)──────────────────────────────────────────────────
  const renderClarityItem = (b: ClarityItem, isClear: boolean) => (
    <div key={b.serialNumber || b.identifier} className={styles.clarityItem}>
      <div className={styles.clarityItemTitle}>
        <span className={styles.clarityItemIcon}>{isClear ? '✅' : '⚠️'}</span>
        <span className={styles.clarityItemSerial}>BUG-{normSerial(b.serialNumber)}</span>
        <span className={styles.clarityItemSubject} title={b.subject}>
          {b.subject}
        </span>
      </div>
      {b.reason && (
        <div className={styles.clarityItemReason}>
          {isClear ? t('bugAnalysis.basis') : t('bugAnalysis.reason')}：{b.reason}
        </div>
      )}
      {!isClear && b.missing && b.missing.length > 0 && (
        <div className={styles.clarityItemMissing}>
          {t('bugAnalysis.missing')}：{b.missing.join(' · ')}
        </div>
      )}
    </div>
  );

  const renderGroup = (g: GroupItem, idx: number) => {
    const groupSnapshots = membersToSnapshots(g.members);
    return (
      <div key={`${g.dimension}-${idx}`} className={styles.groupCard}>
        <div className={styles.groupCardHeader}>
          <span className={styles.groupCardDim}>
            {DIMENSION_ICON[g.dimension]} {t(DIMENSION_LABEL_KEY[g.dimension])}
          </span>
          {g.label && <span className={styles.groupCardLabel}>· {g.label}</span>}
          <span className={styles.groupCardCount}>{g.members.length}</span>
        </div>

        <div className={styles.groupMembers}>
          {g.members.map((sn) => {
            const key = normSerial(sn);
            const snap = snapshotBySerial.get(key);
            return (
              <label key={sn} className={styles.groupMember}>
                <input type="checkbox" checked={selected.has(key)} onChange={() => toggleSelected(sn)} />
                <span className={styles.groupMemberSerial}>BUG-{key}</span>
                {snap?.subject && (
                  <span className={styles.groupMemberSubject} title={snap.subject}>
                    {snap.subject}
                  </span>
                )}
              </label>
            );
          })}
        </div>

        {g.rootCauseGuess && (
          <div className={styles.groupRootCause}>
            {t('bugAnalysis.rootCause')}：{g.rootCauseGuess}
          </div>
        )}

        <div className={styles.groupActions}>
          <button
            type="button"
            className={styles.groupDispatchBtn}
            disabled={groupSnapshots.length === 0}
            onClick={() => dispatchSupervisor(groupSnapshots)}
          >
            {t('bugAnalysis.batchSupervisor')}
          </button>
          <button
            type="button"
            className={styles.groupDispatchBtn}
            disabled={groupSnapshots.length === 0}
            onClick={() => dispatchSession(groupSnapshots)}
          >
            {t('bugAnalysis.batchSession')}
          </button>
        </div>
      </div>
    );
  };

  const renderDone = () => {
    const result = state.result || { bugs: [], groups: [] };
    // unclear 排前。
    const unclear = result.bugs.filter((b) => b.clarity === 'unclear');
    const clear = result.bugs.filter((b) => b.clarity === 'clear');
    return (
      <div className={styles.analysisDone}>
        <div className={styles.analysisHeader}>
          <span className={styles.analysisHeaderInfo}>
            {t('bugAnalysis.header', { model: state.model, reasoning: state.reasoning })}
          </span>
          <span className={styles.analysisStat}>
            {t('bugAnalysis.stat', { n: result.bugs.length, clear: clear.length, unclear: unclear.length })}
          </span>
          <button type="button" className={styles.analysisReanalyzeBtn} onClick={analysis.reAnalyze}>
            {t('bugAnalysis.reAnalyze')}
          </button>
        </div>

        {/* ① 明确性清单 */}
        <div className={styles.analysisSection}>
          <div className={styles.analysisSectionTitle}>① {t('bugAnalysis.sectionClarity')}</div>
          {unclear.length > 0 && (
            <div className={styles.clarityGroup}>
              <div className={styles.clarityGroupTitle}>
                ⚠️ {t('bugAnalysis.unclear')} ({unclear.length})
              </div>
              {unclear.map((b) => renderClarityItem(b, false))}
            </div>
          )}
          {clear.length > 0 && (
            <div className={styles.clarityGroup}>
              <div className={styles.clarityGroupTitle}>
                ✅ {t('bugAnalysis.clear')} ({clear.length})
              </div>
              {clear.map((b) => renderClarityItem(b, true))}
            </div>
          )}
        </div>

        {/* ② 关联分组 */}
        <div className={styles.analysisSection}>
          <div className={styles.analysisSectionTitle}>② {t('bugAnalysis.sectionGroups')}</div>
          {result.groups.map((g, i) => renderGroup(g, i))}
        </div>

        {/* 底部跨组勾选派单 */}
        <div className={styles.analysisDispatchBar}>
          <button type="button" className={styles.analysisSelectAllBtn} onClick={toggleSelectAll}>
            {t('bugAnalysis.selectAll')}
          </button>
          <button
            type="button"
            className={styles.analysisDispatchBtn}
            disabled={selectedSnapshots.length === 0}
            onClick={() => dispatchSupervisor(selectedSnapshots)}
          >
            {t('bugAnalysis.dispatchSelectedSupervisor')}
          </button>
          <button
            type="button"
            className={styles.analysisDispatchBtn}
            disabled={selectedSnapshots.length === 0}
            onClick={() => dispatchSession(selectedSnapshots)}
          >
            {t('bugAnalysis.dispatchSelectedSession')}
          </button>
        </div>
      </div>
    );
  };

  // ── error(§5.4)─────────────────────────────────────────────────
  const renderError = () => (
    <div className={styles.analysisError}>
      <div className={styles.analysisErrorHeader}>
        <span className={styles.analysisErrorMsg}>⚠️ {t('bugAnalysis.parseFailed')}</span>
        <button type="button" className={styles.analysisReanalyzeBtn} onClick={analysis.reAnalyze}>
          {t('bugAnalysis.reAnalyze')}
        </button>
      </div>
      <pre className={styles.analysisRawText}>{state.raw}</pre>
    </div>
  );

  const renderBody = () => {
    switch (state.status) {
      case 'running':
        return renderRunning();
      case 'done':
        return renderDone();
      case 'error':
        return renderError();
      case 'idle':
      default:
        return renderIdle();
    }
  };

  return <div className={styles.analysisPanel}>{renderBody()}</div>;
}

export default BugAnalysisPanel;
