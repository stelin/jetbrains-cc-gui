package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DagValidator} (coding-plan §11 / §22): cycle,
 * self-dependency, dangling dependency, unknown supervisorId, and the happy
 * topological path.
 */
public class DagValidatorTest {

    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList("sup", "sup2"));

    private static WorkflowNode node(String name, String supervisorId, String... deps) {
        WorkflowNode n = new WorkflowNode();
        n.name = name;
        n.supervisorId = supervisorId;
        n.dependsOn = new java.util.ArrayList<>(Arrays.asList(deps));
        return n;
    }

    private static WorkflowDefinition def(WorkflowNode... nodes) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.id = "wf1";
        d.name = "test";
        d.nodes = new java.util.ArrayList<>(Arrays.asList(nodes));
        return d;
    }

    @Test
    public void acceptsValidDiamondTopology() {
        // A, B -> C (A,B roots; C joins both)
        WorkflowDefinition d = def(
                node("A", "sup"),
                node("B", "sup"),
                node("C", "sup2", "A", "B"));
        assertNull("valid DAG should pass", DagValidator.validate(d, KNOWN));
    }

    @Test
    public void acceptsLinearChain() {
        WorkflowDefinition d = def(
                node("A", "sup"),
                node("B", "sup", "A"),
                node("C", "sup", "B"));
        assertNull(DagValidator.validate(d, KNOWN));
    }

    @Test
    public void rejectsSelfDependency() {
        WorkflowDefinition d = def(node("A", "sup", "A"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("依赖自身"));
    }

    @Test
    public void rejectsDanglingDependency() {
        WorkflowDefinition d = def(
                node("A", "sup"),
                node("B", "sup", "ghost"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("不存在的节点"));
    }

    @Test
    public void rejectsUnknownSupervisorId() {
        WorkflowDefinition d = def(node("A", "unknown-sup"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("监督者不存在"));
    }

    @Test
    public void rejectsBlankSupervisorId() {
        WorkflowDefinition d = def(node("A", "  "));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("未指定监督者"));
    }

    @Test
    public void rejectsTwoNodeCycle() {
        WorkflowDefinition d = def(
                node("A", "sup", "B"),
                node("B", "sup", "A"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("环") || err.contains("循环"));
    }

    @Test
    public void rejectsLongerCycleAndNamesParticipants() {
        // Root R feeds a 3-node cycle A→C→B→A. Kahn drains R, then stalls on the
        // cycle, so the topological pass reaches the participant-naming branch
        // (a rootless cycle would trip the earlier "no entry node" guard).
        WorkflowDefinition d = def(
                node("R", "sup"),
                node("A", "sup", "R", "C"),
                node("B", "sup", "A"),
                node("C", "sup", "B"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("A") && err.contains("B") && err.contains("C"));
    }

    @Test
    public void rejectsDuplicateNames() {
        WorkflowDefinition d = def(
                node("A", "sup"),
                node("A", "sup"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("重复"));
    }

    @Test
    public void rejectsBlankName() {
        WorkflowDefinition d = def(node("", "sup"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
        assertTrue(err, err.contains("名称为空"));
    }

    @Test
    public void rejectsEmptyWorkflow() {
        assertNotNull(DagValidator.validate(def(), KNOWN));
        assertNotNull(DagValidator.validate(null, KNOWN));
    }

    @Test
    public void skipsSupervisorMembershipWhenKnownSetEmpty() {
        // Empty/null known set → membership check skipped (validator still
        // catches structural problems, but any supervisorId is accepted).
        WorkflowDefinition d = def(node("A", "any-id"));
        assertNull(DagValidator.validate(d, Collections.emptySet()));
        assertNull(DagValidator.validate(d, null));
    }

    @Test
    public void rejectsGraphWithNoRoot() {
        // Pure cycle: every node has an in-edge → no in-degree-0 entry.
        WorkflowDefinition d = def(
                node("A", "sup", "B"),
                node("B", "sup", "A"));
        String err = DagValidator.validate(d, KNOWN);
        assertNotNull(err);
    }

    @Test
    public void toleratesNullDependsOnList() {
        WorkflowNode a = node("A", "sup");
        a.dependsOn = null;   // gson may omit the field
        List<String> deps = a.dependsOnSafe();
        assertTrue(deps.isEmpty());
        assertNull(DagValidator.validate(def(a), KNOWN));
    }
}
