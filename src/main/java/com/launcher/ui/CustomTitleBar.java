package com.launcher.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/**
 * A frameless-window replacement for the OS title bar: shows the app icon and
 * title on the left, and minimize / maximize-restore / close buttons on the
 * right, styled to match the launcher's dark theme. Also provides the usual
 * "drag the bar to move the window" and "double-click to maximize" behavior.
 * <p>
 * Only used when {@code LauncherSettings.useCustomTitleBar} is enabled, in
 * which case the owning frame is created with {@code setUndecorated(true)}.
 */
public final class CustomTitleBar extends JPanel {

    private enum Glyph { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

    private final JFrame frame;
    private final TitleBarButton maximizeBtn;
    private final JLabel titleLabel;
    private final JPanel rightButtons;

    private Point dragOffset;
    /** 0..1 fraction of the bar's width where a drag started — used to keep the same spot under the cursor when a maximized window is dragged back to normal size. */
    private double dragFraction = 0.5;

    public CustomTitleBar(JFrame frame, String title, Image icon) {
        super(new BorderLayout());
        this.frame = frame;

        setBorder(new EmptyBorder(0, 10, 0, 0));
        setPreferredSize(new Dimension(10, 36));

        // ── Left: icon + title ──────────────────────────────────────────
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        if (icon != null) {
            Image scaled = icon.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
            left.add(new JLabel(new ImageIcon(scaled)));
        }
        titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        left.add(titleLabel);
        add(left, BorderLayout.WEST);

        // ── Right: window control buttons ───────────────────────────────
        rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightButtons.setOpaque(false);

        TitleBarButton minimizeBtn = new TitleBarButton(Glyph.MINIMIZE);
        minimizeBtn.setToolTipText("Minimize");
        minimizeBtn.addActionListener(e -> frame.setExtendedState(Frame.ICONIFIED));

        maximizeBtn = new TitleBarButton(Glyph.MAXIMIZE);
        maximizeBtn.setToolTipText("Maximize");
        maximizeBtn.addActionListener(e -> toggleMaximize());

        TitleBarButton closeBtn = new TitleBarButton(Glyph.CLOSE);
        closeBtn.setToolTipText("Close");
        closeBtn.addActionListener(e -> {
            if (frame instanceof com.launcher.Main mainFrame) {
                if (mainFrame.isAnyGameRunning()) {
                    mainFrame.setVisible(false);
                    return;
                }
            }
            // Dispatch WINDOW_CLOSING so any registered WindowListener (e.g. Main's session
            // cleanup) gets to run first...
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            // ...then force the JVM down regardless, as a hard guarantee that pressing the X
            // always terminates the whole process (including the terminal it was launched
            // from), even if something upstream failed to exit on its own.
            System.exit(0);
        });

        rightButtons.add(minimizeBtn);
        rightButtons.add(maximizeBtn);
        rightButtons.add(closeBtn);
        add(rightButtons, BorderLayout.EAST);

        // Keep the maximize/restore icon in sync no matter how the window
        // state changes (double-click, drag-to-restore, or a programmatic
        // setExtendedState(NORMAL) call elsewhere in the app, e.g. after a
        // game launch returns the launcher to the foreground).
        frame.addWindowStateListener(e -> updateMaximizeIcon());

        installDragToMove();
        updateMaximizeIcon();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Behavior: drag-to-move, double-click-to-maximize
    // ══════════════════════════════════════════════════════════════════════

    private void installDragToMove() {
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragOffset = e.getPoint();
                dragFraction = clamp01(e.getX() / (double) Math.max(1, getWidth()));
                beginAdjustIfPossible();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    toggleMaximize();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragOffset != null) {
                    endAdjustIfPossible();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset == null) return;

                if (isMaximized()) {
                    // Restore first, positioning the window so the same relative
                    // spot under the cursor stays under the cursor — matches the
                    // "drag the title bar down to un-maximize" behavior people
                    // are used to from Windows/macOS.
                    Rectangle screen = frame.getGraphicsConfiguration().getBounds();
                    int w = Math.max(frame.getMinimumSize().width, Math.min(screen.width - 100, 960));
                    int h = Math.max(frame.getMinimumSize().height, Math.min(screen.height - 100, 660));

                    Point cursorOnScreen = e.getLocationOnScreen();
                    int newX = (int) (cursorOnScreen.x - w * dragFraction);
                    int newY = cursorOnScreen.y - 15;

                    frame.setExtendedState(Frame.NORMAL);
                    frame.setSize(w, h);
                    frame.setLocation(newX, newY);
                    dragOffset = new Point((int) (w * dragFraction), 15);
                    return;
                }

                Point cursorOnScreen = e.getLocationOnScreen();
                frame.setLocation(cursorOnScreen.x - dragOffset.x, cursorOnScreen.y - dragOffset.y);
            }
        };
        addMouseListener(dragHandler);
        addMouseMotionListener(dragHandler);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private void beginAdjustIfPossible() {
        if (frame instanceof com.launcher.Main m) {
            m.beginWindowAdjust();
        }
    }

    private void endAdjustIfPossible() {
        if (frame instanceof com.launcher.Main m) {
            m.endWindowAdjust();
        }
    }

    private boolean isMaximized() {
        return (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private void toggleMaximize() {
        frame.setExtendedState(isMaximized() ? Frame.NORMAL : Frame.MAXIMIZED_BOTH);
    }

    private void updateMaximizeIcon() {
        maximizeBtn.setGlyph(isMaximized() ? Glyph.RESTORE : Glyph.MAXIMIZE);
        maximizeBtn.setToolTipText(isMaximized() ? "Restore" : "Maximize");
        maximizeBtn.repaint();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Theming — called by Main.applyTheme() so the bar matches the rest of the UI
    // ══════════════════════════════════════════════════════════════════════

    public void applyColors(Color background, Color foreground) {
        setBackground(background);
        titleLabel.setForeground(foreground);
        for (Component c : rightButtons.getComponents()) {
            if (c instanceof TitleBarButton btn) btn.setIconColor(foreground);
        }
        repaint();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Window control buttons — hand-drawn glyphs so they stay crisp at any DPI
    // ══════════════════════════════════════════════════════════════════════

    private static final class TitleBarButton extends JButton {
        private Glyph glyph;
        private Color iconColor = new Color(226, 226, 234);
        private boolean hover = false;

        TitleBarButton(Glyph glyph) {
            this.glyph = glyph;
            setPreferredSize(new Dimension(46, 36));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }

        void setGlyph(Glyph glyph) { this.glyph = glyph; }
        void setIconColor(Color c) { this.iconColor = c; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            int w = getWidth(), h = getHeight();
            boolean closeButton = this.glyph == Glyph.CLOSE;

            if (hover) {
                g2.setColor(closeButton ? new Color(0xE8, 0x11, 0x23) : new Color(255, 255, 255, 28));
                g2.fillRect(0, 0, w, h);
            }

            Color stroke = (hover && closeButton) ? Color.WHITE : iconColor;
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(1.2f));

            int cx = w / 2, cy = h / 2, s = 5; // icon half-size

            switch (glyph) {
                case MINIMIZE -> g2.drawLine(cx - s, cy, cx + s, cy);
                case MAXIMIZE, RESTORE -> g2.drawRect(cx - s, cy - s, s * 2, s * 2);
                case CLOSE -> {
                    g2.drawLine(cx - s, cy - s, cx + s, cy + s);
                    g2.drawLine(cx - s, cy + s, cx + s, cy - s);
                }
            }
            g2.dispose();
        }
    }
}
