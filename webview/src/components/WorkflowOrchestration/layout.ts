/**
 * DAG auto-layout (layered / longest-path) + dependency cycle helpers.
 * Pure functions — unit-testable in isolation (see ui-implementation.md §10/§14 U10).
 */
import type { WorkflowNode } from './types';

export const CARD_W = 156;
export const CARD_H = 60;
export const COL_GAP = 72;
export const ROW_GAP = 24;
export const PAD = 16;

export interface Placed {
  name: string;
  x: number;
  y: number;
  level: number;
}

export interface LayoutResult {
  placed: Placed[];
  byName: Map<string, Placed>;
  width: number;
  height: number;
}

/** Longest-path layering: level(n) = 0 if no deps, else max(level(dep))+1. */
export function computeLevels(nodes: WorkflowNode[]): Map<string, number> {
  const byName = new Map(nodes.map((n) => [n.name, n]));
  const memo = new Map<string, number>();
  const level = (name: string, stack: Set<string>): number => {
    if (memo.has(name)) return memo.get(name)!;
    if (stack.has(name)) return 0; // cycle guard (editing prevents cycles, defensive here)
    stack.add(name);
    const deps = (byName.get(name)?.dependsOn ?? []).filter((d) => byName.has(d));
    const lv = deps.length ? Math.max(...deps.map((d) => level(d, stack) + 1)) : 0;
    stack.delete(name);
    memo.set(name, lv);
    return lv;
  };
  for (const n of nodes) level(n.name, new Set());
  return memo;
}

export function layout(nodes: WorkflowNode[]): LayoutResult {
  const levels = computeLevels(nodes);
  const cols = new Map<number, string[]>();
  for (const n of nodes) {
    const lv = levels.get(n.name) ?? 0;
    if (!cols.has(lv)) cols.set(lv, []);
    cols.get(lv)!.push(n.name);
  }
  const placed: Placed[] = [];
  let maxLevel = 0;
  let maxRows = 0;
  for (const [lv, names] of [...cols.entries()].sort((a, b) => a[0] - b[0])) {
    maxLevel = Math.max(maxLevel, lv);
    maxRows = Math.max(maxRows, names.length);
    names.forEach((name, row) => {
      placed.push({
        name,
        level: lv,
        x: PAD + lv * (CARD_W + COL_GAP),
        y: PAD + row * (CARD_H + ROW_GAP),
      });
    });
  }
  const byName = new Map(placed.map((p) => [p.name, p]));
  const width = PAD * 2 + (maxLevel + 1) * CARD_W + maxLevel * COL_GAP;
  const height = PAD * 2 + Math.max(1, maxRows) * CARD_H + Math.max(0, maxRows - 1) * ROW_GAP;
  return { placed, byName, width, height };
}

/**
 * Nodes that node `targetName` must NOT depend on (would create a cycle):
 * `targetName` itself plus everything transitively downstream of it.
 */
export function forbiddenDeps(targetName: string, nodes: WorkflowNode[]): Set<string> {
  const down = new Set<string>([targetName]);
  let grow = true;
  while (grow) {
    grow = false;
    for (const n of nodes) {
      if (down.has(n.name)) continue;
      if (n.dependsOn.some((d) => down.has(d))) {
        down.add(n.name);
        grow = true;
      }
    }
  }
  return down;
}

/** True if the node set contains a dependency cycle. */
export function hasCycle(nodes: WorkflowNode[]): boolean {
  const byName = new Map(nodes.map((n) => [n.name, n]));
  const WHITE = 0, GRAY = 1, BLACK = 2;
  const color = new Map<string, number>(nodes.map((n) => [n.name, WHITE]));
  const visit = (name: string): boolean => {
    color.set(name, GRAY);
    for (const d of byName.get(name)?.dependsOn ?? []) {
      if (!byName.has(d)) continue;
      const c = color.get(d);
      if (c === GRAY) return true;
      if (c === WHITE && visit(d)) return true;
    }
    color.set(name, BLACK);
    return false;
  };
  for (const n of nodes) {
    if (color.get(n.name) === WHITE && visit(n.name)) return true;
  }
  return false;
}
