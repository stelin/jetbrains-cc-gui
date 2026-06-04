import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SupervisorAgent } from '../../types/supervisorAgent';
import { ModelSelect, ReasoningSelect } from '../ChatInputBox/selectors';
import { strip1MContextSuffix, type ReasoningEffort } from '../ChatInputBox/types';
import type { WorkflowNode, NodeRuntime } from './types';
import { statusMeta } from './NodeCard';
import styles from './style.module.less';

const DEFAULT_MODEL = 'claude-opus-4-8';

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
  /** Remove an upstream dependency (edges are drawn on the canvas). */
  onRemoveDep: (dep: string) => void;
  onOpenSupervisorManager?: () => void;
  /** True when another node already uses this name (node name = unique tab name). */
  isNameTaken?: (name: string) => boolean;
}

export default function NodeDrawer({
  node, agents, runtime, readOnly, onChange, onDelete, onClose, onJump, onOpenReport, onRemoveDep, onOpenSupervisorManager, isNameTaken,
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

  const meta = statusMeta(runtime?.status);
  const supervisorName = agents.find((a) => a.id === node.supervisorId)?.name ?? node.supervisorId ?? '—';
  const effModel = node.model || DEFAULT_MODEL;

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
