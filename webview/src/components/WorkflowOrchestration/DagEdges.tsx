import type { WorkflowNode, WorkflowExecution } from './types';
import { type Placed, CARD_W, CARD_H } from './layout';
import styles from './style.module.less';

interface DagEdgesProps {
  nodes: WorkflowNode[];
  byName: Map<string, Placed>;
  width: number;
  height: number;
  execution: WorkflowExecution | null;
  editable: boolean;
  onDeleteEdge: (parent: string, child: string) => void;
  /** Live rubber-band while connecting (canvas-local coords). */
  connectLine?: { x1: number; y1: number; x2: number; y2: number } | null;
}

function bezier(x1: number, y1: number, x2: number, y2: number): string {
  const dx = Math.max(32, (x2 - x1) / 2);
  return `M ${x1},${y1} C ${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
}

/** SVG edge layer: one cubic bezier per (parent → child) dependency. */
export default function DagEdges({
  nodes, byName, width, height, execution, editable, onDeleteEdge, connectLine,
}: DagEdgesProps) {
  const groups: React.ReactNode[] = [];
  for (const node of nodes) {
    const child = byName.get(node.name);
    if (!child) continue;
    for (const dep of node.dependsOn) {
      const parent = byName.get(dep);
      if (!parent) continue;
      const x1 = parent.x + CARD_W;
      const y1 = parent.y + CARD_H / 2;
      const x2 = child.x;
      const y2 = child.y + CARD_H / 2;
      const d = bezier(x1, y1, x2, y2);
      const ps = execution?.nodes[dep]?.status;
      const cs = execution?.nodes[node.name]?.status;
      const active = ps === 'DONE' && (cs === 'READY' || cs === 'RUNNING');
      const mx = (x1 + x2) / 2;
      const my = (y1 + y2) / 2;
      groups.push(
        <g key={`${dep}->${node.name}`} className={styles.edgeGroup}>
          <path d={d} className={styles.edgeHit} fill="none" />
          <path d={d} className={active ? styles.edgeActive : styles.edge} markerEnd="url(#wf-arrow)" fill="none" />
          {editable && (
            <g
              className={styles.edgeDelete}
              transform={`translate(${mx},${my})`}
              onClick={(e) => { e.stopPropagation(); onDeleteEdge(dep, node.name); }}
            >
              <circle r="8" />
              <path d="M-3,-3 L3,3 M3,-3 L-3,3" />
            </g>
          )}
        </g>,
      );
    }
  }
  return (
    <svg className={styles.edges} width={width} height={height}>
      <defs>
        <marker id="wf-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 Z" className={styles.arrowHead} />
        </marker>
      </defs>
      {groups}
      {connectLine && (
        <path d={bezier(connectLine.x1, connectLine.y1, connectLine.x2, connectLine.y2)} className={styles.connectLine} fill="none" />
      )}
    </svg>
  );
}
