package com.github.claudecodegui.ui.detached;

import com.github.claudecodegui.session.pair.workflow.NodeStatus;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A single workflow node's floating window (the "cockpit" tile, see
 * {@code docs/workflow/cockpit-plan.md} §2.2). Unlike a tool-window tab — where
 * only the selected tab realizes its JCEF webview — a visible {@code JFrame}
 * realizes its webview immediately, so every concurrently-running node's
 * supervisor→main-AI inject path can drain.
 *
 * <p>Lifecycle: opened (placeholder) when the workflow starts → a live
 * {@link ClaudeChatWindow} is {@link #attach attached} when the engine schedules
 * the node → stays open after the node finishes (records are kept). It never
 * steals focus on show ({@link #setAutoRequestFocus}(false)); only an explicit
 * {@link #bringToFront()} (jump / escalation) raises it.
 */
public final class WorkflowNodeFrame extends JFrame {

    private static final Logger LOG = Logger.getInstance(WorkflowNodeFrame.class);

    private final Project project;
    private final String nodeName;
    private final String agentLabel;
    private final Disposable frameDisposable = Disposer.newDisposable("WorkflowNodeFrame");

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel placeholder = new JLabel("", SwingConstants.CENTER);

    private volatile ClaudeChatWindow chatWindow;   // null until attach()
    private volatile NodeStatus status = NodeStatus.PENDING;
    private volatile boolean disposed = false;
    private Runnable onUserClose = () -> { };

    public WorkflowNodeFrame(@NotNull Project project, @NotNull String nodeName, String agentLabel) {
        super();
        this.project = project;
        this.nodeName = nodeName;
        this.agentLabel = agentLabel == null ? "" : agentLabel;

        setIcon();
        placeholder.setText(waitingText(""));
        placeholder.setBorder(JBUI.Borders.empty(24));
        root.add(placeholder, BorderLayout.CENTER);
        setContentPane(root);

        // Do not steal focus when shown (the user is mid-IDE; N windows pop at once).
        setAutoRequestFocus(false);
        setFocusableWindowState(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                try { onUserClose.run(); } catch (Exception ex) {
                    LOG.warn("[WorkflowNodeFrame] onUserClose failed: " + ex.getMessage());
                }
            }
        });

        applyStatusTitleAndBorder();
        applyIdeThemeAndSubscribe();
    }

    private void setIcon() {
        try {
            java.net.URL iconUrl = getClass().getResource("/icons/logo-16.png");
            if (iconUrl != null) setIconImage(new ImageIcon(iconUrl).getImage());
        } catch (Exception e) {
            LOG.warn("[WorkflowNodeFrame] icon load failed: " + e.getMessage());
        }
    }

    /** Set the placeholder text shown before the node starts (e.g. waiting upstream). */
    public void setPlaceholder(String text) {
        invokeOnEdt(() -> placeholder.setText(text == null || text.isBlank() ? waitingText("") : text));
    }

    /** Register the close funnel (cockpit wires this to {@code onNodeTabClosed}). */
    public void setOnUserClose(Runnable r) {
        this.onUserClose = r != null ? r : () -> { };
    }

    /** The live chat window once attached, else null. */
    public ClaudeChatWindow getChatWindow() {
        return chatWindow;
    }

    public String getNodeName() {
        return nodeName;
    }

    /**
     * Swap the placeholder for the node's live {@link ClaudeChatWindow} content.
     * Because the frame is already visible, adding the JCEF component realizes
     * the webview (→ {@code pair_webview_ready} → inject drain). EDT only.
     */
    public void attach(@NotNull ClaudeChatWindow win) {
        invokeOnEdt(() -> {
            if (disposed) return;
            this.chatWindow = win;
            root.removeAll();
            root.add(win.getContent(), BorderLayout.CENTER);
            root.revalidate();
            root.repaint();
            LOG.info("[WorkflowNodeFrame] attached chat window to node " + nodeName);
        });
    }

    /** Update the visual status (title prefix + border colour). EDT-safe. */
    public void setStatus(NodeStatus s) {
        if (s == null) return;
        this.status = s;
        invokeOnEdt(this::applyStatusTitleAndBorder);
    }

    /** Position/size this window. EDT-safe. */
    public void place(int x, int y, int w, int h) {
        invokeOnEdt(() -> setBounds(x, y, w, h));
    }

    /**
     * Raise this window to the front (jump / escalation). This is the ONE place
     * we intentionally request focus, and it briefly flashes the border so the
     * user's eye catches which node needs them.
     */
    public void bringToFront() {
        invokeOnEdt(() -> {
            if (disposed) return;
            setAutoRequestFocus(true);
            if ((getExtendedState() & Frame.ICONIFIED) != 0) setExtendedState(Frame.NORMAL);
            toFront();
            requestFocus();
            flashBorder();
        });
    }

    private void applyStatusTitleAndBorder() {
        setTitle(statusGlyph() + " 节点「" + nodeName + "」"
                + (agentLabel.isEmpty() ? "" : " · " + agentLabel));
        root.setBorder(BorderFactory.createLineBorder(statusColor(), JBUI.scale(2)));
        root.repaint();
    }

    private String statusGlyph() {
        switch (status) {
            case RUNNING: return "▶";
            case WAITING_HUMAN: return "⚠";
            case DONE: return "✓";
            case ABORTED: return "■";
            default: return "•";
        }
    }

    private Color statusColor() {
        switch (status) {
            case RUNNING: return new JBColor(new Color(0x2F80ED), new Color(0x4C9AFF));
            case WAITING_HUMAN: return new JBColor(new Color(0xE5534B), new Color(0xF2766E));
            case DONE: return new JBColor(new Color(0x2E9E5B), new Color(0x57C77F));
            case ABORTED: return JBColor.GRAY;
            default: return JBColor.border();   // PENDING / READY
        }
    }

    private void flashBorder() {
        final Color hi = new JBColor(new Color(0xF2A33C), new Color(0xF2A33C));
        final Color base = statusColor();
        final int[] n = {0};
        Timer t = new Timer(180, null);
        t.addActionListener(e -> {
            boolean on = n[0] % 2 == 0;
            root.setBorder(BorderFactory.createLineBorder(on ? hi : base, JBUI.scale(on ? 3 : 2)));
            root.repaint();
            if (++n[0] >= 4) {
                ((Timer) e.getSource()).stop();
                applyStatusTitleAndBorder();
            }
        });
        t.setRepeats(true);
        t.start();
    }

    private void applyIdeThemeAndSubscribe() {
        updateThemeColors();
        ApplicationManager.getApplication().getMessageBus().connect(frameDisposable)
                .subscribe(LafManagerListener.TOPIC, new LafManagerListener() {
                    @Override public void lookAndFeelChanged(@NotNull LafManager source) {
                        invokeOnEdt(WorkflowNodeFrame.this::updateThemeColors);
                    }
                });
    }

    private void updateThemeColors() {
        if (!isDisplayable()) return;
        Color bg = UIUtil.getPanelBackground();
        root.setBackground(bg);
        placeholder.setForeground(UIUtil.getInactiveTextColor());
        applyStatusTitleAndBorder();
        root.repaint();
    }

    private String waitingText(String detail) {
        return "<html><div style='text-align:center'>节点「" + nodeName + "」<br/>"
                + (detail == null || detail.isBlank() ? "排队中…" : detail) + "</div></html>";
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        Disposer.dispose(frameDisposable);
        root.removeAll();
        // Mirror the tab disposer: disposing the chat window reaps its Pair +
        // daemon (stopPairsOwnedBy(windowId)). Null in the placeholder (never
        // started) case — nothing to reap then.
        ClaudeChatWindow win = this.chatWindow;
        if (win != null) {
            try { win.dispose(); } catch (Exception e) {
                LOG.warn("[WorkflowNodeFrame] chat window dispose failed: " + e.getMessage());
            }
        }
        super.dispose();
    }

    private static void invokeOnEdt(Runnable r) {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null) { r.run(); return; }
        if (app.isDispatchThread()) r.run(); else app.invokeLater(r);
    }
}
