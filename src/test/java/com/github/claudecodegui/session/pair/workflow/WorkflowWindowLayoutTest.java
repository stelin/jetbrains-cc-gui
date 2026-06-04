package com.github.claudecodegui.session.pair.workflow;

import org.junit.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for the cockpit tiling math ({@code docs/workflow/cockpit-plan.md}
 * §2.1 / §11). Covers grid shape, full coverage of the area, the 2-node
 * left/right split, and no-overlap between tiles.
 */
public class WorkflowWindowLayoutTest {

    @Test
    public void gridShapesMatchPlan() {
        assertArr(new int[]{1, 1}, WorkflowWindowLayout.grid(1));
        assertArr(new int[]{1, 2}, WorkflowWindowLayout.grid(2));   // left / right
        assertArr(new int[]{2, 2}, WorkflowWindowLayout.grid(3));
        assertArr(new int[]{2, 2}, WorkflowWindowLayout.grid(4));
        assertArr(new int[]{2, 3}, WorkflowWindowLayout.grid(5));
        assertArr(new int[]{2, 3}, WorkflowWindowLayout.grid(6));
        // beyond 6 → ceil(sqrt) grid, still covers all tiles
        int[] g7 = WorkflowWindowLayout.grid(7);
        assertTrue("rows*cols must hold all tiles", g7[0] * g7[1] >= 7);
    }

    @Test
    public void twoNodesSplitLeftRight() {
        Rectangle area = new Rectangle(0, 0, 1000, 800);
        List<WorkflowWindowLayout.Slot> slots = WorkflowWindowLayout.tile(area, 2);
        assertEquals(2, slots.size());
        // one full-height left half, one full-height right half
        assertEquals(0, slots.get(0).x());
        assertEquals(800, slots.get(0).h());
        assertEquals(500, slots.get(1).x());
        assertEquals(800, slots.get(1).h());
        // together they cover the full width
        assertEquals(1000, slots.get(1).x() + slots.get(1).w());
    }

    @Test
    public void tilesCoverAreaWithoutGapOnRightAndBottom() {
        Rectangle area = new Rectangle(100, 50, 1003, 807);   // odd sizes → remainder handling
        for (int n = 1; n <= 6; n++) {
            List<WorkflowWindowLayout.Slot> slots = WorkflowWindowLayout.tile(area, n);
            assertEquals(n, slots.size());
            int rightEdge = area.x;
            int bottomEdge = area.y;
            for (WorkflowWindowLayout.Slot s : slots) {
                assertTrue("slot inside area x", s.x() >= area.x);
                assertTrue("slot inside area y", s.y() >= area.y);
                assertTrue("positive width", s.w() > 0);
                assertTrue("positive height", s.h() > 0);
                rightEdge = Math.max(rightEdge, s.x() + s.w());
                bottomEdge = Math.max(bottomEdge, s.y() + s.h());
            }
            assertEquals("right edge reaches area right", area.x + area.width, rightEdge);
            assertEquals("bottom edge reaches area bottom", area.y + area.height, bottomEdge);
        }
    }

    @Test
    public void tilesDoNotOverlap() {
        Rectangle area = new Rectangle(0, 0, 1200, 900);
        List<WorkflowWindowLayout.Slot> slots = WorkflowWindowLayout.tile(area, 5);
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                assertTrue("tiles " + i + " and " + j + " overlap",
                        !rectsOverlap(slots.get(i), slots.get(j)));
            }
        }
    }

    @Test
    public void degenerateInputsAreSafe() {
        assertTrue(WorkflowWindowLayout.tile(new Rectangle(0, 0, 100, 100), 0).isEmpty());
        assertTrue(WorkflowWindowLayout.tile(new Rectangle(0, 0, 0, 0), 3).isEmpty());
    }

    private static boolean rectsOverlap(WorkflowWindowLayout.Slot a, WorkflowWindowLayout.Slot b) {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w()
                && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    private static void assertArr(int[] expected, int[] actual) {
        assertEquals("rows", expected[0], actual[0]);
        assertEquals("cols", expected[1], actual[1]);
    }
}
