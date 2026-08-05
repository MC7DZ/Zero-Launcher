package com.launcher.ui;

import com.launcher.Main;
import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * A JComboBox drop-in replacement (extends JComboBox, so getSelectedItem/
 * setSelectedItem/addItem/removeAllItems/addActionListener all keep working
 * unchanged) that matches the app's rounded, dark "popover" visual language
 * used by notifications/dropdown menus elsewhere in the launcher, instead of
 * rendering with the OS look-and-feel's flat blue selection highlight.
 */
public class CustomComboBox<T> extends JComboBox<T> {

    private static final int ARC = 10;
    private boolean focused = false;

    public CustomComboBox() {
        super();
        init();
    }

    public CustomComboBox(T[] items) {
        super(items);
        init();
    }

    public CustomComboBox(java.util.Vector<T> items) {
        super(items);
        init();
    }

    private void init() {
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(new Font("SansSerif", Font.PLAIN, 12));
        setUI(new ThemedComboBoxUI());
        setRenderer(new ThemedRenderer());

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                focused = true;
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                focused = false;
                repaint();
            }
        });
    }

    private Color panelBg() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        return Main.hexToColor(s.panelBgColor, new Color(19, 19, 26));
    }

    private Color textColor() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        return Main.hexToColor(s.textColor, new Color(226, 226, 234));
    }

    private Color accentColor() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        return Main.hexToColor(s.accentColor, new Color(16, 185, 129));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color panelBg = panelBg();
        Color fill = new Color(
                Math.min(255, panelBg.getRed() + 12),
                Math.min(255, panelBg.getGreen() + 12),
                Math.min(255, panelBg.getBlue() + 12),
                isEnabled() ? 200 : 100);

        g2.setColor(fill);
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1f, getHeight() - 1f, ARC, ARC));

        g2.dispose();
        super.paintComponent(g);

        Graphics2D border = (Graphics2D) g.create();
        border.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color borderColor = focused ? accentColor() : new Color(255, 255, 255, 35);
        border.setStroke(new BasicStroke(focused ? 1.4f : 1f));
        border.setColor(borderColor);
        border.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, ARC, ARC));
        border.dispose();
    }

    /** Renders both the closed-box value and each popup row without any OS blue highlight. */
    private class ThemedRenderer extends BasicComboBoxRenderer {
        @Override
        public Component getListCellRendererComponent(JList list, Object value, int index,
                                                        boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, false, false);
            label.setText(value == null ? "" : value.toString());
            label.setFont(new Font("SansSerif", Font.PLAIN, 12));
            label.setOpaque(false);
            label.setBorder(new EmptyBorder(6, 12, 6, 12));

            boolean inPopupList = index >= 0;
            Color accent = accentColor();
            Color text = textColor();

            if (inPopupList && isSelected) {
                // Hovered/armed row inside the open popup — accent tint, not OS blue.
                label.setForeground(accent);
                label.putClientProperty("rowFill", new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40));
            } else {
                label.setForeground(text);
                label.putClientProperty("rowFill", null);
            }
            return new RowPanel(label);
        }
    }

    /** Thin wrapper that paints the row's rounded accent highlight behind its label. */
    private static class RowPanel extends JPanel {
        private final JLabel label;

        RowPanel(JLabel label) {
            super(new BorderLayout());
            this.label = label;
            setOpaque(false);
            add(label, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Object fill = label.getClientProperty("rowFill");
            if (fill instanceof Color) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor((Color) fill);
                g2.fillRoundRect(2, 0, getWidth() - 4, getHeight(), 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Custom UI: flat themed arrow button, no default background painting, themed popup. */
    private class ThemedComboBoxUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton btn = new JButton("▾");
            btn.setFont(new Font("SansSerif", Font.BOLD, 10));
            btn.setForeground(new Color(255, 255, 255, 130));
            btn.setContentAreaFilled(false);
            btn.setBorderPainted(false);
            btn.setFocusPainted(false);
            btn.setOpaque(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return btn;
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            // no-op: background is painted by CustomComboBox#paintComponent instead.
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup popup = new BasicComboPopup(comboBox) {
                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setOpaque(false);
                    setBorderPainted(false);
                    setBorder(new EmptyBorder(6, 4, 6, 4));
                }

                @Override
                protected JScrollPane createScroller() {
                    JScrollPane scroller = super.createScroller();
                    scroller.setOpaque(false);
                    scroller.getViewport().setOpaque(false);
                    scroller.setBorder(null);
                    SmoothScroll.install(scroller);
                    scroller.getVerticalScrollBar().setUnitIncrement(16);
                    return scroller;
                }

                @Override
                protected void configureList() {
                    super.configureList();
                    list.setOpaque(false);
                    list.setSelectionBackground(new Color(0, 0, 0, 0));
                    list.setSelectionForeground(textColor());
                    list.setBackground(new Color(0, 0, 0, 0));
                    list.setFixedCellHeight(30);
                }

                @Override
                public void show() {
                    super.show();
                    // Popup windows are opaque white by default in most L&Fs when Swing
                    // has to promote the popup to a heavyweight window (e.g. it extends
                    // past the app window's bounds); make it paint-through where the
                    // platform supports per-pixel translucency so our rounded panel
                    // background renders correctly instead of showing a white box.
                    try {
                        Window w = SwingUtilities.getWindowAncestor(this);
                        if (w != null && w.getGraphicsConfiguration() != null
                                && java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                                        .getDefaultScreenDevice()
                                        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                            w.setBackground(new Color(0, 0, 0, 0));
                        }
                    } catch (Exception ignored) {
                        // Not supported on this platform/L&F — the popup will simply
                        // show a square corner behind the rounded panel, which is a
                        // harmless cosmetic fallback.
                    }
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    Color bg = panelBg();
                    Color accent = accentColor();
                    g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 245));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1f, getHeight() - 1f, 14, 14));

                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, 14, 14));

                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            return popup;
        }
    }
}
