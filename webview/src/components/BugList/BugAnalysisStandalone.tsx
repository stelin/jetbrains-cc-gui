import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useBugAnalysis, type BugSnapshot } from '../../hooks/useBugAnalysis';
import BugAnalysisPanel from './BugAnalysisPanel';
import { ModelSelect } from '../ChatInputBox/selectors/ModelSelect';
import { ReasoningSelect } from '../ChatInputBox/selectors/ReasoningSelect';
import {
  apply1MContextSuffix,
  has1MContextSuffix,
  normalizeClaudeModelId,
  strip1MContextSuffix,
  type ReasoningEffort,
} from '../ChatInputBox/types';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * BugAnalysisStandalone
 *
 * 独立分离窗口(脱离 IDE 的 JFrame)里的根组件。main.tsx 在检测到
 * window.__BUG_ANALYSIS_BOOT__ 时渲染本组件(而非整个 App)。
 *
 * 两段式:① 配置(选模型+思考深度,默认与发起会话一致)+【开始分析】按钮;
 * 点击后才 start() → ② 复用 BugAnalysisPanel 渲染分析中/结果。数据全在内存,
 * 窗口关闭即销毁(无持久化)。analyze/cancel 经 window.sendToJava 走【本窗口】
 * Java 桥(BugAnalysisFrame),结果/进度/直播推回本窗口。
 * ──────────────────────────────────────────────────────────────── */

export interface BugAnalysisBoot {
  projectId: string;
  bugs: BugSnapshot[];
  model: string;
  reasoning: string;
  /** 云效设置里配置的追加文案,透传给分组派单的 prefill。 */
  appendPrompt?: string;
}

export function BugAnalysisStandalone({ projectId, bugs, model, reasoning, appendPrompt }: BugAnalysisBoot) {
  const { t } = useTranslation();
  const analysis = useBugAnalysis();
  const [started, setStarted] = useState(false);

  // 默认与发起会话一致:boot.model 可能带 [1m] 后缀 → 拆成 基础 id + 长上下文开关。
  const [selModel, setSelModel] = useState(
    () => strip1MContextSuffix(normalizeClaudeModelId(model)) || 'claude-sonnet-4-6',
  );
  const [longContext, setLongContext] = useState(() => has1MContextSuffix(model));
  const [selReasoning, setSelReasoning] = useState<ReasoningEffort>(() => (reasoning as ReasoningEffort) || 'high');
  // 最大并发子智能体数:3/4/5,默认 3(协调者据此分批并发分析,批内并发、批间串行)。
  const [concurrency, setConcurrency] = useState(3);

  // 点【开始分析】才发起(此时 window.sendToJava 早已注入,无需再等桥)。
  const handleStart = () => {
    if (started) return;
    setStarted(true);
    analysis.start(bugs, apply1MContextSuffix(selModel, longContext), selReasoning, projectId, concurrency);
  };

  if (!started) {
    return (
      <div className={styles.standaloneRoot}>
        <div className={styles.configScreen}>
          <div className={styles.configTitle}>🔬 {t('bugAnalysis.configTitle', { n: bugs.length })}</div>

          <div className={styles.configBugList}>
            {bugs.map((b) => (
              <div key={b.identifier || b.serialNumber} className={styles.configBugRow}>
                <span className={styles.configBugSerial}>BUG-{b.serialNumber}</span>
                <span className={styles.configBugSubject} title={b.subject}>
                  {b.subject}
                </span>
              </div>
            ))}
          </div>

          <div className={styles.configControls}>
            <span className={styles.configLabel}>{t('bugAnalysis.configHint')}</span>
            <ModelSelect
              value={selModel}
              onChange={(m) => setSelModel(strip1MContextSuffix(normalizeClaudeModelId(m)))}
              currentProvider="claude"
              longContextEnabled={longContext}
              onLongContextChange={setLongContext}
              openUpward={false}
            />
            <ReasoningSelect
              value={selReasoning}
              onChange={setSelReasoning}
              selectedModel={selModel}
              currentProvider="claude"
              openUpward={false}
            />
            <span className={styles.configLabel}>{t('bugAnalysis.concurrencyLabel')}</span>
            <div className={styles.concurrencyGroup}>
              {[3, 4, 5].map((n) => (
                <button
                  key={n}
                  type="button"
                  className={`${styles.concurrencyBtn} ${concurrency === n ? styles.concurrencyBtnActive : ''}`}
                  onClick={() => setConcurrency(n)}
                >
                  {n}
                </button>
              ))}
            </div>
          </div>

          <button
            type="button"
            className={styles.configStartBtn}
            disabled={bugs.length === 0}
            onClick={handleStart}
          >
            {t('bugAnalysis.startBtn')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.standaloneRoot}>
      <BugAnalysisPanel analysis={analysis} appendPrompt={appendPrompt || ''} />
    </div>
  );
}

export default BugAnalysisStandalone;
