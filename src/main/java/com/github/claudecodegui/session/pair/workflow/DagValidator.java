package com.github.claudecodegui.session.pair.workflow;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backend authority for workflow-graph validity (§11). The front end's
 * {@code layout.ts:forbiddenDeps} is only a connect-time UX guard — the engine
 * never trusts it and re-validates here before running.
 *
 * <p>{@link #validate} returns {@code null} when the definition is runnable, or
 * a human-readable error message otherwise. Checks, in order:
 * <ol>
 *   <li>at least one node; every {@code name} non-blank and unique;</li>
 *   <li>each {@code dependsOn} entry references an existing node and is not a
 *       self-dependency;</li>
 *   <li>each {@code supervisorId} is non-blank and ∈ {@code knownSupervisorIds};</li>
 *   <li>the graph is acyclic (Kahn topological sort); a cycle reports the
 *       participating node names;</li>
 *   <li>at least one in-degree-0 node exists (implied by 4, kept explicit).</li>
 * </ol>
 */
public final class DagValidator {

    private DagValidator() {
    }

    @Nullable
    public static String validate(@Nullable WorkflowDefinition def, @Nullable Set<String> knownSupervisorIds) {
        if (def == null) return "工作流定义为空";
        List<WorkflowNode> nodes = def.nodesSafe();
        if (nodes.isEmpty()) return "工作流没有节点";

        Set<String> known = knownSupervisorIds == null ? java.util.Collections.emptySet() : knownSupervisorIds;

        // 1. unique, non-blank names.
        Set<String> names = new HashSet<>();
        for (WorkflowNode n : nodes) {
            if (n == null || n.name == null || n.name.trim().isEmpty()) {
                return "存在名称为空的节点";
            }
            if (!names.add(n.name)) {
                return "节点名称重复: " + n.name;
            }
        }

        // 2 + 3. dependsOn references + self-dep + supervisorId membership.
        for (WorkflowNode n : nodes) {
            if (n.supervisorId == null || n.supervisorId.trim().isEmpty()) {
                return "节点「" + n.name + "」未指定监督者(supervisorId)";
            }
            if (!known.isEmpty() && !known.contains(n.supervisorId)) {
                return "节点「" + n.name + "」的监督者不存在: " + n.supervisorId;
            }
            for (String dep : n.dependsOnSafe()) {
                if (dep == null || dep.trim().isEmpty()) {
                    return "节点「" + n.name + "」存在空依赖";
                }
                if (dep.equals(n.name)) {
                    return "节点「" + n.name + "」不能依赖自身";
                }
                if (!names.contains(dep)) {
                    return "节点「" + n.name + "」依赖了不存在的节点: " + dep;
                }
            }
            // execution timing (D25/D28): relative delay in range; absolute needs a time.
            if ("relative".equals(n.delayMode)) {
                if (n.delayMinutes == null || n.delayMinutes < 0
                        || n.delayMinutes > SupervisorWorkflowManager.MAX_DELAY_MINUTES) {
                    return "节点「" + n.name + "」延迟需在 0–"
                            + SupervisorWorkflowManager.MAX_DELAY_MINUTES + " 分钟之间";
                }
            } else if ("absolute".equals(n.delayMode) && n.scheduledAt == null) {
                return "节点「" + n.name + "」未选择定时执行时间";
            }
        }

        // 4. acyclicity via Kahn topological sort.
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (WorkflowNode n : nodes) {
            indegree.putIfAbsent(n.name, 0);
            dependents.putIfAbsent(n.name, new ArrayList<>());
        }
        for (WorkflowNode n : nodes) {
            for (String dep : n.dependsOnSafe()) {
                // edge dep -> n : n depends on dep, so dep must finish first.
                indegree.merge(n.name, 1, Integer::sum);
                dependents.get(dep).add(n.name);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        // 5. at least one root (otherwise the whole graph is a cycle).
        if (queue.isEmpty()) {
            return "工作流不存在入口节点（全部节点都有依赖，构成环）";
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : dependents.get(cur)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited < nodes.size()) {
            // Remaining nodes (indegree > 0) participate in / depend on a cycle.
            Set<String> inCycle = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> e : indegree.entrySet()) {
                if (e.getValue() > 0) inCycle.add(e.getKey());
            }
            return "工作流存在循环依赖，涉及节点: " + String.join(", ", inCycle);
        }

        return null;
    }
}
