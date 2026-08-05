package com.launcher.ui;

import com.launcher.Main;
import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * A JSpinner drop-in replacement (for numeric settings like RAM or window
 * size) that paints a rounded, themed pill background with slim custom
 * up/down arrow buttons instead of the default OS spinner chrome. Extends
 * JSpinner, so every existing call site (getValue/setValue/
 * addChangeListener/setEnabled) keeps working unchanged.
 */
public class CustomSpinner extends JSpinner {

    private static final int ARC = 10;
    private boolean focused = false;

    public CustomSpinner(SpinnerModel model) {
        super(model);
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setUI(new CustomSpinnerUI());
        styleEditor();
    }

    private void styleEditor() {
        LauncherSettings s = SettingsManager.getInstance().getSettings();
        Color textColor = Main.hexToColor(s.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(s.accentColor, new Color(16, 185, 129));

        if (getEditor() instanceof DefaultEditor editor) {
            JFormattedTextField field = editor.getTextField();
            field.setOpaque(false);
            field.setBorder(new EmptyBorder(4, 10, 4, 4));
            field.setForeground(textColor);
            field.setCaretColor(textColor);
            field.setSelectionColor(accent);
            field.setSelectedTextColor(Color.WHITE);
            field.setFont(new Font("SansSerif", Font.PLAIN, 12));
            editor.setOpaque(false);
            field.addFocusListener(new FocusAdapter() {
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

        Color border = focused ? accentColor() : new Color(255, 255, 255, 35);
        g2.setStroke(new BasicStroke(focused ? 1.4f : 1f));
        g2.setColor(border);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, ARC, ARC));

        g2.dispose();
        super.paintComponent(g);
    }

    /**
     * Suppresses default spinner border/background painting and swaps the
     * stock up/down buttons for slim, themed arrow buttons.
     */
    private class CustomSpinnerUI extends BasicSpinnerUI {
        @Override
        protected Component createNextButton() {
            JButton btn = arrowButton("▲");
            installArrowMouse(btn, true);
            return btn;
        }

        @Override
        protected Component createPreviousButton() {
            JButton btn = arrowButton("▼");
            installArrowMouse(btn, false);
            return btn;
        }

        private void installArrowMouse(JButton btn, boolean next) {
            btn.addActionListener(e -> {
                Object v = next ? spinner.getNextValue() : spinner.getPreviousValue();
                if (v != null) {
                    spinner.setValue(v);
                }
            });
        }

        private JButton arrowButton(String label) {
            JButton btn = new JButton(label);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 8));
            btn.setForeground(new Color(255, 255, 255, 150));
            btn.setContentAreaFilled(false);
            btn.setBorderPainted(false);
            btn.setFocusPainted(false);
            btn.setOpaque(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setPreferredSize(new Dimension(16, 10));
            return btn;
        }

        @Override
        protected JComponent createEditor() {
            JComponent editor = super.createEditor();
            editor.setOpaque(false);
            editor.setBorder(new EmptyBorder(0, 0, 0, 0));
            return editor;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            // no-op: background is painted by CustomSpinner#paintComponent;
            // children (editor + arrow buttons) are painted separately by
            // Swing's normal Container.paintChildren pass.
        }
    }
}
