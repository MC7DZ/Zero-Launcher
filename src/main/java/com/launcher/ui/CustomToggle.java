package com.launcher.ui;

import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;
import com.launcher.Main;

import javax.swing.*;
import javax.swing.plaf.basic.BasicCheckBoxUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A JCheckBox drop-in replacement that renders as a rounded pill toggle
 * switch instead of the default OS checkbox. Since it extends JCheckBox,
 * every existing call site (setSelected / isSelected / addActionListener /
 * setEnabled / text label) keeps working unchanged.
 */
public class CustomToggle extends JCheckBox {

    private static final int TRACK_WIDTH = 38;
    private static final int TRACK_HEIGHT = 20;
    private static final int GAP = 8;

    private float animProgress; // 0 = off, 1 = on
    private Timer animTimer;

    public CustomToggle() {
        this("");
    }

    public CustomToggle(String text) {
        this(text, false);
    }

    public CustomToggle(String text, boolean selected) {
        super(text, selected);
        animProgress = selected ? 1f : 0f;
        setUI(new CustomToggleUI());
        setOpaque(false);
        setFocusPainted(false);
        setIconTextGap(GAP);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(new Font("SansSerif", Font.PLAIN, 12));
        applyTextColor();

        addItemListener(e -> animateTo(isSelected()));
    }

    private void applyTextColor() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        setForeground(Main.hexToColor(s.textColor, new Color(226, 226, 234)));
    }

    private void animateTo(boolean on) {
        if (animTimer != null && animTimer.isRunning()) {
            animTimer.stop();
        }
        float target = on ? 1f : 0f;
        animTimer = new Timer(12, e -> {
            float step = 0.18f;
            if (Math.abs(animProgress - target) <= step) {
                animProgress = target;
                ((Timer) e.getSource()).stop();
            } else {
                animProgress += (target > animProgress) ? step : -step;
            }
            repaint();
        });
        animTimer.start();
    }

    private Color accentColor() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        return Main.hexToColor(s.accentColor, new Color(16, 185, 129));
    }

    /**
     * Custom UI that paints a pill-shaped track + sliding knob in place of
     * the default checkbox icon, while delegating text layout to Swing.
     */
    private class CustomToggleUI extends BasicCheckBoxUI {
        @Override
        public synchronized void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            icon = new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    Color onColor = accentColor();
                    Color offColor = new Color(255, 255, 255, isEnabled() ? 45 : 20);
                    Color trackColor = blend(offColor, new Color(onColor.getRed(), onColor.getGreen(),
                            onColor.getBlue(), isEnabled() ? 255 : 90), animProgress);

                    RoundRectangle2D track = new RoundRectangle2D.Float(x, y + (getIconHeight() - TRACK_HEIGHT) / 2f,
                            TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT);
                    g.setColor(trackColor);
                    g.fill(track);

                    g.setColor(new Color(255, 255, 255, 30));
                    g.draw(track);

                    int knobD = TRACK_HEIGHT - 4;
                    float travel = TRACK_WIDTH - knobD - 4;
                    int knobX = (int) (x + 2 + travel * animProgress);
                    int knobY = y + (getIconHeight() - knobD) / 2;
                    g.setColor(isEnabled() ? Color.WHITE : new Color(255, 255, 255, 130));
                    g.fillOval(knobX, knobY, knobD, knobD);

                    g.dispose();
                }

                @Override
                public int getIconWidth() {
                    return TRACK_WIDTH;
                }

                @Override
                public int getIconHeight() {
                    return TRACK_HEIGHT + 2;
                }
            };
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            applyTextColor();
            super.paint(g, c);
        }

        private Color blend(Color a, Color b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
            int gg = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
            int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
            int al = (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t);
            return new Color(r, gg, bl, al);
        }
    }
}
