import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition, WorkflowExecution } from './types';
import { layout, type Placed, CARD_W, CARD_H, PAD } from './layout';
import DagEdges from './DagEdges';
import NodeCard from './NodeCard';
import styles from './style.module.less';

interface DagCanvasProps {
  draft: WorkflowDefinition;
  execution: WorkflowExecution | null;
  showStatus: boolean;
  selectedNode: string | null;
  agentName: (id: string) => string;
  onSelectNode: (name: string) => void;
  onJumpNode: (name: string) => void;
  onAddNode: () => void;
  onMoveNode: (name: string, x: number, y: number) => void;
  /** Create dependency: `to` depends on `from` (data flows from→to). */
  onConnect: (fromName: string, toName: string) => void;
  onDeleteEdge: (parent: string, child: string) => void;
}

export default function DagCanvas({
  draft, execution, showStatus, selectedNode, agentName,
  onSelectNode, onJumpNode, onAddNode, onMoveNode, onConnect, onDeleteEdge,
}: DagCanvasProps) {
  const { t } = useTranslation();
  const auto = useMemo(() => layout(draft.nodes), [draft.nodes]);
  const editable = !showStatus; // arrange + connect only while editing

  const canvasRef = useRef<HTMLDivElement>(null);

  // node drag
  const [dragPos, setDragPos] = useState<{ name: string; x: number; y: number } | null>(null);
  const dragRef = useRef<{ name: string; startX: number; startY: number; origX: number; origY: number; curX: number; curY: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);

  // edge connect
  const [connect, setConnect] = useState<{ from: string; x1: number; y1: number; x2: number; y2: number } | null>(null);
  const connectRef = useRef<string | null>(null);

  const effPos = (name: string): Placed => {
    if (dragPos && dragPos.name === name) return { name, x: dragPos.x, y: dragPos.y, level: 0 };
    const node = draft.nodes.find((n) => n.name === name);
    if (node && node.posX != null && node.posY != null) return { name, x: node.posX, y: node.posY, level: 0 };
    return auto.byName.get(name) ?? { name, x: PAD, y: PAD, level: 0 };
  };

  const placed = draft.nodes.map((n) => effPos(n.name));
  const byName = new Map(placed.map((p) => [p.name, p]));
  const width = Math.max(auto.width, PAD + Math.max(0, ...placed.map((p) => p.x + CARD_W)) + PAD);
  const height = Math.max(auto.height, PAD + Math.max(0, ...placed.map((p) => p.y + CARD_H)) + PAD);

  const toLocal = (clientX: number, clientY: number) => {
    const el = canvasRef.current;
    if (!el) return { x: clientX, y: clientY };
    const r = el.getBoundingClientRect();
    return { x: clientX - r.left, y: clientY - r.top };
  };

  // ── node drag ────────────────────────────────────────────────────────
  const onCardPointerDown = (e: React.PointerEvent, name: string) => {
    if (!editable) return;
    const origin = effPos(name);
    dragRef.current = { name, startX: e.clientX, startY: e.clientY, origX: origin.x, origY: origin.y, curX: origin.x, curY: origin.y, moved: false };
    try { (e.currentTarget as Element).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    const onMove = (ev: PointerEvent) => {
      const d = dragRef.current;
      if (!d) return;
      const dx = ev.clientX - d.startX;
      const dy = ev.clientY - d.startY;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) d.moved = true;
      d.curX = Math.max(0, d.origX + dx);
      d.curY = Math.max(0, d.origY + dy);
      setDragPos({ name: d.name, x: d.curX, y: d.curY });
    };
    const onUp = () => {
      const d = dragRef.current;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      if (d && d.moved) { suppressClick.current = true; onMoveNode(d.name, d.curX, d.curY); }
      dragRef.current = null;
      setDragPos(null);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  // ── edge connect ─────────────────────────────────────────────────────
  const onPortPointerDown = (e: React.PointerEvent, fromName: string) => {
    if (!editable) return;
    const p = effPos(fromName);
    const x1 = p.x + CARD_W;
    const y1 = p.y + CARD_H / 2;
    connectRef.current = fromName;
    const start = toLocal(e.clientX, e.clientY);
    setConnect({ from: fromName, x1, y1, x2: start.x, y2: start.y });
    const onMove = (ev: PointerEvent) => {
      const l = toLocal(ev.clientX, ev.clientY);
      setConnect((c) => (c ? { ...c, x2: l.x, y2: l.y } : c));
    };
    const onUp = (ev: PointerEvent) => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      const from = connectRef.current;
      connectRef.current = null;
      setConnect(null);
      const el = document.elementFromPoint(ev.clientX, ev.clientY) as Element | null;
      const target = el?.closest('[data-node-name]')?.getAttribute('data-node-name') ?? null;
      if (from && target && from !== target) onConnect(from, target);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const handleClick = (name: string) => {
    if (suppressClick.current) { suppressClick.current = false; return; }
    onSelectNode(name);
  };

  return (
    <div className={styles.canvasWrap}>
      <div className={styles.canvasToolbar}>
        <span className={styles.canvasHint}>
          {t('workflow.canvasHint', '{{n}} node(s) · {{e}} edge(s)', {
            n: draft.nodes.length,
            e: draft.nodes.reduce((s, n) => s + n.dependsOn.length, 0),
          })}
          {editable && draft.nodes.length > 1 && (
            <span className={styles.connectTip}> · {t('workflow.connectTip', 'drag the right dot of a node onto another to link them')}</span>
          )}
        </span>
        <button className={styles.addNodeBtn} onClick={onAddNode}>
          <span className="codicon codicon-add" /> {t('workflow.addNode', 'Add node')}
        </button>
      </div>

      {draft.nodes.length === 0 ? (
        <div className={styles.emptyHint}>
          <span className="codicon codicon-git-merge" />
          <div>{t('workflow.emptyCanvas', 'No nodes yet. Click "Add node" to start.')}</div>
        </div>
      ) : (
        <div className={styles.canvasScroll}>
          <div className={styles.canvas} style={{ width, height }} ref={canvasRef}>
            <DagEdges
              nodes={draft.nodes}
              byName={byName}
              width={width}
              height={height}
              execution={showStatus ? execution : null}
              editable={editable}
              onDeleteEdge={onDeleteEdge}
              connectLine={connect}
            />
            {placed.map((p) => (
              <NodeCard
                key={p.name}
                name={p.name}
                supervisorName={agentName(draft.nodes.find((n) => n.name === p.name)?.supervisorId ?? '')}
                status={showStatus ? execution?.nodes[p.name]?.status : undefined}
                showStatus={showStatus}
                selected={selectedNode === p.name}
                draggable={editable}
                dragging={dragPos?.name === p.name}
                showPorts={editable}
                x={p.x}
                y={p.y}
                onClick={() => handleClick(p.name)}
                onDoubleClick={() => onJumpNode(p.name)}
                onPointerDown={(e) => onCardPointerDown(e, p.name)}
                onOutPointerDown={(e) => onPortPointerDown(e, p.name)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
