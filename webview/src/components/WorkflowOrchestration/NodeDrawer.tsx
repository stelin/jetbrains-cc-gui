import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ConfigProvider, DatePicker, TimePicker, theme as antdTheme } from 'antd';
import dayjs from 'dayjs';
import type { SupervisorAgent } from '../../types/supervisorAgent';
import { ModelSelect, ReasoningSelect } from '../ChatInputBox/selectors';
import { strip1MContextSuffix, type ReasoningEffort } from '../ChatInputBox/types';
import type { WorkflowNode, NodeRuntime } from './types';
import { statusMeta } from './NodeCard';
import styles from './style.module.less';

const DEFAULT_MODEL = 'claude-opus-4-8';

/** epoch ms → human local string, e.g. "2026/6/5 02:00:00". */
function fmtLocal(ms?: number | null): string {
  if (!ms) return '';
  try { return new Date(ms).toLocaleString(); } catch { return ''; }
}

/** remaining ms → "H:MM:SS" (or "M:SS" under an hour). Empty when not positive. */
function fmtCountdown(ms: number): string {
  if (ms <= 0) return '';
  const total = Math.ceil(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const p = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}

/** The webview mirrors the IDE theme onto <html data-theme>; default to dark. */
function isDarkTheme(): boolean {
  return (document.documentElement.getAttribute('data-theme') || 'dark') !== 'light';
}

interface NodeDrawerProps {
  node: WorkflowNode;
  agents: SupervisorAgent[];
  runtime?: NodeRuntime | null;
  readOnly: boolean;
  onChange: (node: WorkflowNode, originalName: string) => void;
  onDelete: () => void;
  onClose: () => void;
  onJump: () => void;
  onOpenReport: () => void;
  /** Re-dispatch this node. 'auto' = adaptive (re-kick live pair / re-launch); 'restart' = force re-launch. */
  onRedispatch?: (mode: 'auto' | 'restart') => void;
  /** Remove an upstream dependency (edges are drawn on the canvas). */
  onRemoveDep: (dep: string) => void;
  onOpenSupervisorManager?: () => void;
  /** True when another node already uses this name (node name = unique tab name). */
  isNameTaken?: (name: string) => boolean;
  /** Execution is PAUSED (restored after restart) — re-dispatch needs 「恢复运行」 first (DN10). */
  isPaused?: boolean;
}

export default function NodeDrawer({
  node, agents, runtime, readOnly, onChange, onDelete, onClose, onJump, onOpenReport, onRedispatch, onRemoveDep, onOpenSupervisorManager, isNameTaken, isPaused,
}: NodeDrawerProps) {
  const { t } = useTranslation();

  // Only the name needs a local buffer (commit on blur so typing doesn't rename
  // on every keystroke). Every other field reads straight off `node` and commits
  // by merging a patch onto the LATEST node — so canvas-set fields (posX/posY)
  // are never clobbered by an editor commit.
  const [nameInput, setNameInput] = useState(node.name);
  const [planMode, setPlanMode] = useState<'inline' | 'file'>(node.planPath ? 'file' : 'inline');
  const [nameError, setNameError] = useState<string | null>(null);
  const planRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    setNameInput(node.name);
    setPlanMode(node.planPath ? 'file' : 'inline');
    setNameError(null);
  }, [node.name]); // eslint-disable-line react-hooks/exhaustive-deps

  // Live clock — ticks once a second only while this node is SCHEDULED (waiting
  // for its start time), so the 执行时机 countdown stays current.
  const scheduledAtMs = runtime?.status === 'SCHEDULED' ? (runtime?.scheduledStartAt ?? null) : null;
  const [nowTs, setNowTs] = useState(() => Date.now());
  useEffect(() => {
    if (!scheduledAtMs) return undefined;
    setNowTs(Date.now());
    const id = window.setInterval(() => setNowTs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [scheduledAtMs]);
  const remainMs = scheduledAtMs ? scheduledAtMs - nowTs : 0;

  const commit = (patch: Partial<WorkflowNode>) => {
    if (readOnly) return;
    onChange({ ...node, ...patch }, node.name);
  };

  const commitName = () => {
    const newName = nameInput.trim();
    if (!newName) { setNameError(t('workflow.err.nameRequired', 'Name is required')); setNameInput(node.name); return; }
    if (newName === node.name) { setNameError(null); return; }
    if (isNameTaken?.(newName)) {
      // Reject the rename: a duplicate name would collapse two nodes onto one
      // another on the canvas (name = node identity / tab name).
      setNameError(t('workflow.err.nameDup', '名称已被使用'));
      setNameInput(node.name);
      return;
    }
    setNameError(null);
    onChange({ ...node, name: newName }, node.name);
  };

  // ── task textarea: accept dropped files (insert @path), like the main input ──
  const insertPaths = (paths: string[]) => {
    const cleaned = paths.map((p) => p.replace(/^file:\/\//, '').trim()).filter(Boolean);
    if (cleaned.length === 0) return;
    const insertion = cleaned.map((p) => (p.startsWith('@') ? p : `@${p}`)).join(' ') + ' ';
    const ta = planRef.current;
    const cur = node.plan ?? '';
    if (ta && document.activeElement === ta) {
      const s = ta.selectionStart;
      const e = ta.selectionEnd;
      commit({ plan: cur.slice(0, s) + insertion + cur.slice(e) });
      requestAnimationFrame(() => {
        const n = planRef.current;
        if (n) { n.focus(); const c = s + insertion.length; n.setSelectionRange(c, c); }
      });
    } else {
      const sep = cur.length > 0 && !/\s$/.test(cur) ? ' ' : '';
      commit({ plan: cur + sep + insertion });
    }
  };
  const onPlanDragOver = (e: React.DragEvent<HTMLTextAreaElement>) => {
    e.preventDefault(); e.stopPropagation(); e.dataTransfer.dropEffect = 'copy';
  };
  const onPlanDrop = (e: React.DragEvent<HTMLTextAreaElement>) => {
    e.preventDefault(); e.stopPropagation();
    let raw = e.dataTransfer?.getData('text/plain') ?? '';
    if (!raw.trim()) raw = e.dataTransfer?.getData('text/uri-list') ?? '';
    raw = raw.trim();
    if (!raw) return;
    const paths = raw.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
    insertPaths(paths);
  };

  // ── OSR (off-screen) drop path ────────────────────────────────────────────
  // In OSR mode (Linux / JCEF off-screen rendering) an OS file drop is captured
  // by Java's AWT DropTarget and delivered via window.handleFilePathFromJava — it
  // never reaches the page as a DOM drop, so onPlanDrop above never fires. The
  // main/supervisor inputs cover this through chatInputDropRouter, but both that
  // router AND the handleFilePathFromJava registration (useGlobalCallbacks) live
  // on the chat view's ChatInputBox, which is UNMOUNTED while the workflow editor
  // view is active — so in OSR mode the drop was silently lost here. Install our
  // own handler while this drawer is open so the OSR path reaches the task
  // textarea too; the DOM onPlanDrop still covers non-OSR (macOS/Windows). A ref
  // keeps the closure pointed at the latest insertPaths without re-installing.
  const insertPathsRef = useRef(insertPaths);
  insertPathsRef.current = insertPaths;
  useEffect(() => {
    if (readOnly) return;
    const prev = window.handleFilePathFromJava;
    window.handleFilePathFromJava = (input: string | string[]) => {
      let paths: string[];
      if (Array.isArray(input)) {
        paths = input;
      } else if (typeof input === 'string') {
        // Java passes a JS array directly, but tolerate a JSON-encoded string.
        try {
          const parsed: unknown = JSON.parse(input);
          paths = Array.isArray(parsed) ? (parsed as string[]) : [input];
        } catch {
          paths = [input];
        }
      } else {
        return;
      }
      insertPathsRef.current(paths.filter((p) => typeof p === 'string' && !!p.trim()));
    };
    return () => { window.handleFilePathFromJava = prev; };
  }, [readOnly]);

  const meta = statusMeta(runtime?.status);
  const supervisorName = agents.find((a) => a.id === node.supervisorId)?.name ?? node.supervisorId ?? '—';
  const effModel = node.model || DEFAULT_MODEL;

  // Re-dispatch is for live (RUNNING) or interrupted/escalated (WAITING_HUMAN) nodes,
  // and only once the workflow is actually scheduling (not PAUSED — DN10). A node
  // with a live pairId would be re-kicked by 'auto', so it also gets a force-restart.
  const canRedispatch = !isPaused
    && (runtime?.status === 'RUNNING' || runtime?.status === 'WAITING_HUMAN');
  const hasLivePair = runtime?.status === 'RUNNING' && !!runtime?.pairId;

  return (
    <div className={styles.drawer}>
      <div className={styles.drawerHeader}>
        <span className={styles.drawerTitle}>{t('workflow.node.title', 'Node')}: {node.name}</span>
        <button className={styles.iconBtn} onClick={onClose} title={t('common.close', 'Close')}>
          <span className="codicon codicon-close" />
        </button>
      </div>

      <div className={styles.drawerBody}>
        {readOnly && runtime && (
          <div className={`${styles.runtimeBox} ${meta.cls}`}>
            <span className={`codicon ${meta.icon}`} /> {t(meta.i18nKey, '')}
            {runtime.escalationReason && <div className={styles.runtimeReason}>{runtime.escalationReason}</div>}
          </div>
        )}

        <label className={styles.label}>{t('workflow.node.name', 'Name (= tab name)')}</label>
        <input
          className={styles.input}
          value={nameInput}
          maxLength={30}
          disabled={readOnly}
          onChange={(e) => setNameInput(e.target.value)}
          onBlur={commitName}
        />
        {nameError && <div className={styles.fieldError}>{nameError}</div>}

        <label className={styles.label}>{t('workflow.node.supervisor', 'Supervisor')}</label>
        {readOnly ? (
          <span className={styles.readonlyVal}>{supervisorName}</span>
        ) : (
          <>
            <select
              className={styles.input}
              value={node.supervisorId}
              onChange={(e) => commit({ supervisorId: e.target.value })}
            >
              <option value="">{t('workflow.node.pickSupervisor', 'Select a supervisor…')}</option>
              {agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            {onOpenSupervisorManager && (
              <button className={styles.manageLink} onClick={onOpenSupervisorManager}>
                <span className="codicon codicon-settings-gear" /> {t('chatInput.supervisor.manage', 'Manage agents')}
              </button>
            )}
          </>
        )}

        {/* upstream dependencies — edited by drawing edges on the canvas */}
        <label className={styles.label}>{t('workflow.node.dependsOn', 'Depends on (upstream)')}</label>
        {node.dependsOn.length === 0 ? (
          <div className={styles.depHint}>{t('workflow.node.connectFromCanvas', 'Drag from an upstream node\'s right dot onto this node to link.')}</div>
        ) : (
          <div className={styles.chips}>
            {node.dependsOn.map((dep) => (
              <span key={dep} className={styles.chip}>
                {dep}
                {!readOnly && (
                  <span className={styles.chipRemove} title={t('common.delete', 'Remove')} onClick={() => onRemoveDep(dep)}>
                    <span className="codicon codicon-close" />
                  </span>
                )}
              </span>
            ))}
          </div>
        )}

        {/* task source — the input box mirrors the main chat input (drop files to insert @path) */}
        <label className={styles.label}>{t('workflow.node.planSource', 'Task source')}</label>
        <div className={styles.inlineRow}>
          <label className={styles.checkInline}>
            <input type="radio" name="planmode" checked={planMode === 'inline'} disabled={readOnly}
              onChange={() => { setPlanMode('inline'); commit({ planPath: undefined }); }} />
            {t('workflow.node.planInline', 'Inline text')}
          </label>
          <label className={styles.checkInline}>
            <input type="radio" name="planmode" checked={planMode === 'file'} disabled={readOnly}
              onChange={() => setPlanMode('file')} />
            {t('workflow.node.planFile', 'Reference file')}
          </label>
        </div>
        {planMode === 'inline' ? (
          <div className={styles.taskInputBox}>
            <textarea
              ref={planRef}
              className={styles.taskTextarea}
              value={node.plan}
              rows={9}
              disabled={readOnly}
              placeholder={t('workflow.node.planPlaceholder', 'The task this node hands to its supervisor…')}
              onChange={(e) => commit({ plan: e.target.value })}
              onDragOver={readOnly ? undefined : onPlanDragOver}
              onDrop={readOnly ? undefined : onPlanDrop}
            />
            {!readOnly && <div className={styles.taskInputHint}>{t('workflow.node.dropFiles', 'Drop files to insert @path')}</div>}
          </div>
        ) : (
          <input className={styles.input} value={node.planPath ?? ''} disabled={readOnly} placeholder="/path/to/plan.md"
            onChange={(e) => commit({ planPath: e.target.value || undefined })} />
        )}

        {/* model + reasoning chips — same selectors as the main input (figure 2),
            placed right under the task input. */}
        <div className={styles.selectorBar}>
          {readOnly ? (
            <span className={styles.readonlyVal}>{effModel}{node.longContext ? ' · 1M' : ''}{node.reasoning ? ` · ${node.reasoning}` : ''}</span>
          ) : (
            <>
              <ModelSelect
                value={effModel}
                onChange={(m) => commit({ model: strip1MContextSuffix(m) })}
                currentProvider="claude"
                longContextEnabled={!!node.longContext}
                onLongContextChange={(en) => commit({ longContext: en })}
              />
              <ReasoningSelect
                value={(node.reasoning || 'high') as ReasoningEffort}
                onChange={(r) => commit({ reasoning: r })}
                selectedModel={effModel}
                currentProvider="claude"
              />
            </>
          )}
        </div>

        {/* 执行时机 (D25): 立即 / 上游完成后延迟 N 分钟 / 指定具体时间。本地时区。 */}
        <label className={styles.label}>{t('workflow.node.timing.label', '执行时机')}</label>
        {readOnly ? (
          <span className={styles.readonlyVal}>
            {node.delayMode === 'relative'
              ? t('workflow.node.timing.relativeRO', '延迟 {{m}} 分钟', { m: node.delayMinutes ?? 0 })
              : node.delayMode === 'absolute'
                ? t('workflow.node.timing.absoluteRO', '定时 {{at}}', { at: fmtLocal(node.scheduledAt) })
                : t('workflow.node.timing.immediate', '立即执行')}
            {scheduledAtMs && (
              <span className={styles.countdown}>
                {' · '}
                {remainMs > 0
                  ? t('workflow.node.timing.countdown', '{{left}} 后执行（{{at}}）', { left: fmtCountdown(remainMs), at: fmtLocal(scheduledAtMs) })
                  : t('workflow.node.timing.startingSoon', '即将开始…')}
              </span>
            )}
          </span>
        ) : (
          <>
            <div className={styles.inlineRow}>
              <label className={styles.checkInline}>
                <input type="radio" name="timingmode" checked={(node.delayMode ?? 'none') === 'none'}
                  onChange={() => commit({ delayMode: 'none', delayMinutes: undefined, scheduledAt: undefined })} />
                {t('workflow.node.timing.immediate', '立即执行')}
              </label>
              <label className={styles.checkInline}>
                <input type="radio" name="timingmode" checked={node.delayMode === 'relative'}
                  onChange={() => commit({ delayMode: 'relative', delayMinutes: node.delayMinutes ?? 0, scheduledAt: undefined })} />
                {t('workflow.node.timing.relative', '延迟执行')}
              </label>
              <label className={styles.checkInline}>
                <input type="radio" name="timingmode" checked={node.delayMode === 'absolute'}
                  onChange={() => commit({ delayMode: 'absolute', delayMinutes: undefined })} />
                {t('workflow.node.timing.absolute', '指定时间')}
              </label>
            </div>
            {node.delayMode === 'relative' && (
              <div className={styles.inlineRow}>
                <input className={styles.input} type="number" min={0} max={300} value={node.delayMinutes ?? 0}
                  onChange={(e) => commit({ delayMinutes: Math.max(0, Math.min(300, Number(e.target.value) || 0)) })} />
                <span className={styles.readonlyVal}>{t('workflow.node.timing.minutes', '分钟（上游完成后，0–300）')}</span>
              </div>
            )}
            {node.delayMode === 'absolute' && (
              // Date + time as two compact pickers — a single showTime popup is too
              // wide for the narrow tool-window (its time column renders off-screen).
              <ConfigProvider theme={{ algorithm: isDarkTheme() ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm }}>
                <div className={styles.inlineRow}>
                  <DatePicker
                    format="YYYY-MM-DD"
                    style={{ flex: 1 }}
                    placeholder={t('workflow.node.timing.pickDate', '选择日期')}
                    value={node.scheduledAt ? dayjs(node.scheduledAt) : null}
                    onChange={(d) => {
                      if (!d) { commit({ scheduledAt: undefined }); return; }
                      const base = node.scheduledAt ? dayjs(node.scheduledAt) : dayjs();
                      commit({ scheduledAt: d.hour(base.hour()).minute(base.minute()).second(0).millisecond(0).valueOf() });
                    }}
                  />
                  <TimePicker
                    format="HH:mm"
                    minuteStep={5}
                    style={{ width: 116 }}
                    placeholder={t('workflow.node.timing.pickTime', '选择时间')}
                    value={node.scheduledAt ? dayjs(node.scheduledAt) : null}
                    onChange={(tm) => {
                      if (!tm) return;
                      const base = node.scheduledAt ? dayjs(node.scheduledAt) : dayjs();
                      commit({ scheduledAt: base.hour(tm.hour()).minute(tm.minute()).second(0).millisecond(0).valueOf() });
                    }}
                  />
                </div>
              </ConfigProvider>
            )}
          </>
        )}
      </div>

      <div className={styles.drawerFooter}>
        {readOnly ? (
          <>
            <button className={styles.secondaryBtn} onClick={onJump}>
              <span className="codicon codicon-go-to-file" /> {t('workflow.jumpToTab', 'Open tab')}
            </button>
            {runtime?.status === 'DONE' && (
              <button className={styles.secondaryBtn} onClick={onOpenReport}>
                <span className="codicon codicon-output" /> {t('workflow.viewReport', 'Report')}
              </button>
            )}
            {/* Re-dispatch a stuck/interrupted node (D21/DN10). Hidden while PAUSED —
                resume the workflow first. */}
            {canRedispatch && onRedispatch && (
              <button
                className={styles.secondaryBtn}
                onClick={() => {
                  if (window.confirm(t('workflow.redispatch.confirm', '将在该节点原有对话中继续未完成的任务（保留历史、断点续跑，不会重开窗口）。确认继续？'))) {
                    onRedispatch('auto');
                  }
                }}
                title={t('workflow.redispatch.tip', '在原对话窗口里继续未完成的任务（保留历史）；窗口已关闭时才新开')}
              >
                <span className="codicon codicon-refresh" /> {t('workflow.redispatch.label', '继续/重新下发')}
              </button>
            )}
            {/* DN12 (optional): a live pair would only be re-kicked by 'auto' — offer a
                heavier force-restart for a wedged-but-alive supervisor. */}
            {canRedispatch && hasLivePair && onRedispatch && (
              <button
                className={styles.secondaryBtn}
                onClick={() => {
                  if (window.confirm(t('workflow.redispatch.confirmRestart', '强制重启会终止当前监督者并重新创建，确认继续？'))) {
                    onRedispatch('restart');
                  }
                }}
              >
                <span className="codicon codicon-debug-restart" /> {t('workflow.redispatch.restart', '强制重启节点')}
              </button>
            )}
            {isPaused && (runtime?.status === 'RUNNING' || runtime?.status === 'WAITING_HUMAN') && (
              <span className={styles.runtimeReason}>{t('workflow.redispatch.needResume', '请先点「恢复运行」')}</span>
            )}
          </>
        ) : (
          <button className={styles.dangerBtn} onClick={onDelete}>
            <span className="codicon codicon-trash" /> {t('workflow.node.delete', 'Delete')}
          </button>
        )}
      </div>
    </div>
  );
}
