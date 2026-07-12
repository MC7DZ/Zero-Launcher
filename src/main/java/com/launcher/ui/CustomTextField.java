package com.launcher.ui;

import com.launcher.Main;
import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTextFieldUI;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * A JTextField drop-in replacement that paints a rounded, themed pill
 * background instead of the default OS text field chrome, with an
 * accent-colored ring on focus. Extends JTextField, so every existing
 * call site (getText/setText/addActionListener/addFocusListener/
 * setPreferredSize) keeps working unchanged.
 */
public class CustomTextField extends JTextField {

    private static final int ARC = 10;

    private boolean focused = false;

    public CustomTextField() {
        this("");
    }

    public CustomTextField(String text) {
        super(text);
        setOpaque(false);
        setBorder(new EmptyBorder(4, 10, 4, 10));
        setUI(new CustomTextFieldUI());
        setFont(new Font("SansSerif", Font.PLAIN, 12));
        setSelectionColor(accentColor());
        setSelectedTextColor(Color.WHITE);
        setCaretColor(textColor());
        setForeground(textColor());

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

        LauncherSettings s = SettingsManager.getInstance().getSettings();
        Color panelBg = Main.hexToColor(s.panelBgColor, new Color(19, 19, 26));
        Color fill = new Color(
                Math.min(255, panelBg.getRed() + 12),
                Math.min(255, panelBg.getGreen() + 12),
                Math.min(255, panelBg.getBlue() + 12),
                isEnabled() ? 200 : 100);

        g2.setColor(fill);
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1f, getHeight() - 1f, ARC, ARC));

        Color border = focused
                ? accentColor()
                : new Color(255, 255, 255, 35);
        g2.setStroke(new BasicStroke(focused ? 1.4f : 1f));
        g2.setColor(border);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, ARC, ARC));

        g2.dispose();
        super.paintComponent(g);
    }

    /**
     * Suppresses the default Swing text field border/background painting so
     * only our rounded pill (drawn in paintComponent) shows through.
     */
    private static class CustomTextFieldUI extends BasicTextFieldUI {
        @Override
        protected void paintBackground(Graphics g) {
            // no-op: background is handled by CustomTextField#paintComponent
        }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            JTextComponent editor = (JTextComponent) c;
            editor.setOpaque(false);
        }
    }
}
