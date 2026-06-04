package com.github.claudecodegui.session.pair.workflow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;

import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiling math + target-screen selection for the workflow "cockpit" — the set of
 * floating node windows shown while a workflow runs (see
 * {@code docs/workflow/cockpit-plan.md}). Concurrent same-layer nodes each get
 * their own visible {@code WorkflowNodeFrame}; a visible JFrame realizes its
 * JCEF webview (unlike a tool-window tab, where only the selected tab mounts),
 * which is what lets the supervisor→main-AI inject path drain for every node.
 *
 * <p>{@link #grid(int)} / {@link #tile(Rectangle, int)} are pure and IDE-free so
 * they can be unit-tested headless; {@link #targetScreen(Project)} /
 * {@link #usableBounds(GraphicsConfiguration)} touch AWT + the IDE frame and run
 * only on the EDT during cockpit open.
 */
final class WorkflowWindowLayout {

    private WorkflowWindowLayout() { }

    /** A computed window rectangle on the target screen. */
    record Slot(int x, int y, int w, int h) { }

    /**
     * Grid dimensions for {@code n} tiles, biased wide (cols ≥ rows) so panes
     * stay readable: 1→1×1, 2→1×2 (left/right halves), 3-4→2×2, 5-6→2×3, and
     * {@code ceil(sqrt)} beyond. Returns {@code {rows, cols}}.
     */
    static int[] grid(int n) {
        if (n <= 1) return new int[]{1, 1};
        if (n == 2) return new int[]{1, 2};
        if (n <= 4) return new int[]{2, 2};
        if (n <= 6) return new int[]{2, 3};
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        return new int[]{rows, cols};
    }

    /**
     * Tile {@code n} windows into {@code area} as a grid (pure; unit-tested).
     * The last (possibly short) row stretches its columns to fill the width so
     * there's no right-edge gap — the "puzzle" look the user asked for.
     */
    static List<Slot> tile(Rectangle area, int n) {
        List<Slot> slots = new ArrayList<>(Math.max(0, n));
        if (n <= 0 || area.width <= 0 || area.height <= 0) return slots;
        int[] rc = grid(n);
        int rows = rc[0], cols = rc[1];
        int rowH = area.height / rows;
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            // How many tiles actually live on row r (last row may be short).
            int tilesInRow = Math.min(cols, n - r * cols);
            int colW = area.width / tilesInRow;
            int x = area.x + c * colW;
            int y = area.y + r * rowH;
            // Last column / last row absorb integer-division remainder.
            int w = (c == tilesInRow - 1) ? area.x + area.width - x : colW;
            int h = (r == rows - 1) ? area.y + area.height - y : rowH;
            slots.add(new Slot(x, y, w, h));
        }
        return slots;
    }

    /**
     * Pick the screen to host the cockpit: when more than one screen exists,
     * prefer a screen the IDE is NOT on (per the user's "throw node windows to
     * the secondary display" decision); otherwise the IDE's own screen.
     */
    static GraphicsConfiguration targetScreen(Project project) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();
        GraphicsDevice ideDev = null;
        try {
            Frame ideFrame = project != null ? WindowManager.getInstance().getFrame(project) : null;
            if (ideFrame != null && ideFrame.getGraphicsConfiguration() != null) {
                ideDev = ideFrame.getGraphicsConfiguration().getDevice();
            }
        } catch (Exception ignored) {
            /* headless / no IDE frame — fall through to default screen */
        }
        if (devices.length > 1 && ideDev != null) {
            for (GraphicsDevice d : devices) {
                if (!d.equals(ideDev)) return d.getDefaultConfiguration();
            }
        }
        return (ideDev != null ? ideDev : ge.getDefaultScreenDevice()).getDefaultConfiguration();
    }

    /** Screen bounds minus OS insets (taskbar / dock / menu bar). */
    static Rectangle usableBounds(GraphicsConfiguration gc) {
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                b.x + in.left,
                b.y + in.top,
                b.width - in.left - in.right,
                b.height - in.top - in.bottom);
    }

    /** Convenience: tiled slots for {@code n} nodes on the target screen (EDT). */
    static List<Slot> slotsFor(Project project, int n) {
        return tile(usableBounds(targetScreen(project)), n);
    }
}
