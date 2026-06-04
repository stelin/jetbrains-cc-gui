import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useWorkflowContext } from './WorkflowContext';
import type { SupervisorAgent, SupervisorAgentListPayload } from '../../types/supervisorAgent';
import type { WorkflowNode } from './types';
import { forbiddenDeps } from './layout';
import WorkflowList from './WorkflowList';
import DagCanvas from './DagCanvas';
import NodeDrawer from './NodeDrawer';
import styles from './style.module.less';

interface WorkflowViewProps {
  onClose: () => void;
  onOpenSupervisorManager?: () => void;
}

/**
 * Full-page workflow editor (replaces the old modal). Rendered as a ViewMode
 * sibling of Settings/History so it gets the whole tool-window surface. The run
 * monitor lights up in-place (no separate modal).
 */
export default function WorkflowView({ onClose, onOpenSupervisorManager }: WorkflowViewProps) {
  const { t } = useTranslation();
  const {
    definitions, selectedId, draft, execution, isRunning, runningOf, capabilities,
    isSaved, isDirty,
    selectWorkflow, newWorkflow, updateDraft, upsertNode, removeNode, addNode,
    saveDraft, deleteWorkflow, runWorkflow, abortWorkflow, refreshState, jumpToNode, openReport,
  } = useWorkflowContext();

  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  useEffect(() => { setSelectedNode(null); }, [draft?.id]);

  // Re-pull the authoritative run state every time the workflow page opens. The
  // provider's one-shot mount fetch can miss the live execution (bridge not
  // ready at app start, or the run began in another tab/webview), which left the
  // page showing "Editing" — and any Run rejected with "已有工作流在运行" — while a
  // workflow was actually RUNNING. By now the bridge is ready, so requestList()
  // re-syncs the running execution (mirrors the agents re-fetch below).
  useEffect(() => { refreshState(); }, [refreshState]);

  // Own the supervisor-agents subscription here: this view is mounted exactly
  // when the editor is visible, so its chained handler is the active one and a
  // fresh fetch reliably populates the list (the global chain can get clobbered
  // when ChatHeader's SupervisorToggle unmounts on navigation).
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  useEffect(() => {
    const prev = window.updateSupervisorAgents;
    window.updateSupervisorAgents = (json: string) => {
      prev?.(json);
      try { setAgents((JSON.parse(json) as SupervisorAgentListPayload).agents || []); } catch { /* ignore */ }
    };
    if (window.sendToJava) window.sendToJava('get_supervisor_agents:');
    return () => { window.updateSupervisorAgents = prev; };
  }, []);
  const agentNameLocal = useCallback((id: string) => agents.find((a) => a.id === id)?.name ?? id ?? '', [agents]);

  const running = !!draft && runningOf(draft.id);
  const lockedByOther = isRunning && !!draft && execution?.workflowId !== draft.id;
  const runningName = definitions.find((d) => d.id === execution?.workflowId)?.name ?? '';
  // Must be saved (in the left list) with no pending edits before running.
  const canRun = !!draft && isSaved && !isDirty && draft.nodes.length > 0 && !lockedByOther && !running;
  const canSave = !!draft && !running && isDirty;

  const handleNodeChange = useCallback((updated: WorkflowNode, originalName: string) => {
    upsertNode(updated, originalName);
    if (updated.name !== originalName) setSelectedNode(updated.name);
  }, [upsertNode]);

  const handleAddNode = useCallback(() => {
    const n = addNode();
    setSelectedNode(n.name);
  }, [addNode]);

  const handleMoveNode = useCallback((name: string, x: number, y: number) => {
    const n = draft?.nodes.find((node) => node.name === name);
    if (n) upsertNode({ ...n, posX: x, posY: y }, name);
  }, [draft, upsertNode]);

  // Canvas connect: edge from→to means `to depends on from` (data flows from→to).
  const handleConnect = useCallback((fromName: string, toName: string) => {
    if (!draft || fromName === toName) return;
    const target = draft.nodes.find((n) => n.name === toName);
    if (!target || target.dependsOn.includes(fromName)) return;
    if (forbiddenDeps(toName, draft.nodes).has(fromName)) return; // would create a cycle
    upsertNode({ ...target, dependsOn: [...target.dependsOn, fromName] }, toName);
  }, [draft, upsertNode]);

  const handleDeleteEdge = useCallback((parent: string, child: string) => {
    const target = draft?.nodes.find((n) => n.name === child);
    if (target) upsertNode({ ...target, dependsOn: target.dependsOn.filter((d) => d !== parent) }, child);
  }, [draft, upsertNode]);

  const handleSave = useCallback(() => {
    saveDraft();   // validates + persists + toasts (success or the reason it failed)
  }, [saveDraft]);

  const handleRun = useCallback(() => {
    // Run is gated on a clean, saved draft — no implicit save here.
    if (!draft || !canRun) return;
    runWorkflow(draft.id);
  }, [draft, canRun, runWorkflow]);

  const stateLabel = running
    ? t(`workflow.state.${(execution?.state ?? 'RUNNING').toLowerCase()}`, execution?.state ?? 'RUNNING')
    : t('workflow.state.editing', 'Editing');

  const currentNode = draft?.nodes.find((n) => n.name === selectedNode) ?? null;

  return (
    <div className={styles.page}>
      {/* top bar */}
      <div className={styles.pageHeader}>
        <div className={styles.headerLeft}>
          <button className={styles.backBtn} onClick={onClose}>
            <span className="codicon codicon-arrow-left" /> {t('common.back', 'Back')}
          </button>
          <span className={`codicon codicon-git-merge ${styles.headerIcon}`} />
          {draft ? (
            <input
              className={styles.titleInput}
              value={draft.name}
              disabled={running}
              maxLength={40}
              onChange={(e) => updateDraft({ name: e.target.value })}
            />
          ) : (
            <span className={styles.title}>{t('workflow.title', 'Workflow Orchestration')}</span>
          )}
          <span className={`${styles.stateBadge} ${running ? styles.stateBadgeRunning : ''}`}>{stateLabel}</span>
          {!running && draft && isDirty && (
            <span className={styles.dirtyBadge} title={t('workflow.unsavedHint', '有未保存的修改，保存后才能运行')}>
              {t('workflow.unsaved', '未保存')}
            </span>
          )}
        </div>
        <div className={styles.headerActions}>
          <label className={styles.concurrency}>
            {t('workflow.concurrency.label', 'Concurrency')}
            {running ? (
              <span className={styles.concurrencyValue}>{execution?.concurrency ?? draft?.maxConcurrency ?? 2}</span>
            ) : (
              <select
                value={draft?.maxConcurrency ?? 2}
                disabled={!draft}
                onChange={(e) => { const n = Number(e.target.value); updateDraft({ maxConcurrency: n }); }}
              >
                {Array.from({ length: Math.max(1, capabilities.maxConcurrency) }, (_, i) => i + 1).map((n) => (
                  <option key={n} value={n}>{n}</option>
                ))}
              </select>
            )}
          </label>
          {running ? (
            <button className={styles.dangerBtn} onClick={abortWorkflow}>
              <span className="codicon codicon-debug-stop" /> {t('workflow.abort', 'Abort')}
            </button>
          ) : (
            <>
              <button
                className={styles.secondaryBtn}
                onClick={handleSave}
                disabled={!canSave}
                title={!draft
                  ? ''
                  : isDirty ? t('workflow.save', 'Save') : t('workflow.saveHintClean', '没有未保存的修改')}
              >
                <span className="codicon codicon-save" /> {t('workflow.save', 'Save')}
              </button>
              <button
                className={styles.runBtn}
                onClick={handleRun}
                disabled={!canRun}
                title={lockedByOther
                  ? t('workflow.lockedRunning', 'Another workflow is running')
                  : (!isSaved || isDirty) ? t('workflow.runNeedsSave', '请先保存工作流') : ''}
              >
                <span className="codicon codicon-play" /> {t('workflow.run', 'Run')}
              </button>
              {isRunning && (
                <button
                  className={styles.dangerBtn}
                  onClick={abortWorkflow}
                  title={t('workflow.stopRunningHint', '停止正在运行的工作流：{{name}}', { name: runningName })}
                >
                  <span className="codicon codicon-debug-stop" /> {t('workflow.stop', 'Stop')}
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {/* body: list | canvas | inspector */}
      <div className={styles.pageBody}>
        <WorkflowList
          definitions={definitions}
          selectedId={selectedId}
          runningId={isRunning ? (execution?.workflowId ?? null) : null}
          onSelect={selectWorkflow}
          onNew={newWorkflow}
          onDelete={deleteWorkflow}
        />

        {draft ? (
          <DagCanvas
            draft={draft}
            execution={execution}
            showStatus={running}
            selectedNode={selectedNode}
            agentName={agentNameLocal}
            onSelectNode={setSelectedNode}
            onJumpNode={jumpToNode}
            onAddNode={handleAddNode}
            onMoveNode={handleMoveNode}
            onConnect={handleConnect}
            onDeleteEdge={handleDeleteEdge}
          />
        ) : (
          <div className={styles.emptyHint}>
            <span className="codicon codicon-git-merge" />
            <div>{t('workflow.pickOrCreate', 'Select or create a workflow')}</div>
          </div>
        )}

        {/* always-docked inspector */}
        <div className={styles.inspector}>
          {currentNode && draft ? (
            <NodeDrawer
              node={currentNode}
              agents={agents}
              runtime={running ? execution?.nodes[currentNode.name] : null}
              readOnly={running}
              onChange={handleNodeChange}
              onDelete={() => { removeNode(currentNode.name); setSelectedNode(null); }}
              onClose={() => setSelectedNode(null)}
              onJump={() => jumpToNode(currentNode.name)}
              onOpenReport={() => openReport(currentNode.name)}
              onRemoveDep={(dep) => handleDeleteEdge(dep, currentNode.name)}
              onOpenSupervisorManager={onOpenSupervisorManager}
              isNameTaken={(name) => draft.nodes.some((n) => n.name !== currentNode.name && n.name === name)}
            />
          ) : (
            <div className={styles.inspectorEmpty}>
              <span className="codicon codicon-inspect" />
              <div>{t('workflow.inspectorEmpty', 'Select a node to edit')}</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
