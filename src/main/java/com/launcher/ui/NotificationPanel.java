package com.launcher.ui;

import com.launcher.Main;
import com.launcher.model.LauncherSettings;
import com.launcher.manager.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A single toast card. Purely a "dumb" visual component: all timing, animation
 * scheduling and layout decisions live in {@link NotificationCenter}. This class only
 * knows how to render itself at a given alpha/progress and report user intent
 * (hover, manual close) back to its owner.
 */
public class NotificationPanel extends JPanel {

    public enum Type { INFO, SUCCESS, WARNING, ERROR }

    public interface Listener {
        void onDismissRequested(NotificationPanel panel);
        void onHoverChanged(NotificationPanel panel, boolean hovering);
    }

    private final Type type;
    private final Color accent;
    private final Color panelBg;
    private final Color borderColor;

    private float alpha = 0f;
    /** 1.0 = full time remaining, 0.0 = about to expire. Drives the thin progress bar. */
    private float lifeProgress = 1f;
    private boolean showProgress = true;

    public NotificationPanel(Type type, String title, String message, Listener listener) {
        this.type = type;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        this.panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        this.borderColor = Main.hexToColor("#3c3c46", new Color(60, 60, 70));
        this.accent = switch (type) {
            case SUCCESS -> Main.hexToColor(settings.accentColor, new Color(16, 185, 129));
            case WARNING -> Main.hexToColor("#f59e0b", new Color(245, 158, 11));
            case ERROR   -> Main.hexToColor("#ef4444", new Color(239, 68, 68));
            case INFO    -> Main.hexToColor("#9ca3af", new Color(156, 163, 175));
        };

        setOpaque(false);
        setLayout(new BorderLayout(10, 0));
        setBorder(new EmptyBorder(10, 12, 10, 10));

        JLabel iconLabel = new JLabel(switch (type) {
            case SUCCESS -> "\u2714";
            case WARNING -> "\u26A0";
            case ERROR   -> "\u2715";
            case INFO    -> "\u2139";
        });
        iconLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        iconLabel.setForeground(accent);
        iconLabel.setBorder(new EmptyBorder(0, 6, 0, 8));
        add(iconLabel, BorderLayout.WEST);

        JPanel textCol = new JPanel();
        textCol.setOpaque(false);
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));

        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        JLabel titleLabel = new JLabel("<html><b>" + escape(title) + "</b></html>");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(titleLabel);

        if (message != null && !message.isBlank()) {
            JLabel msgLabel = new JLabel("<html><body style='width: 200px;'>" + escape(message).replace("\n", "<br>") + "</body></html>");
            msgLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            msgLabel.setForeground(Main.hexToColor("#9ca3af", new Color(156, 163, 175)));
            msgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(Box.createVerticalStrut(3));
            textCol.add(msgLabel);
        }
        add(textCol, BorderLayout.CENTER);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        closeBtn.setForeground(Main.hexToColor("#9ca3af", new Color(156, 163, 175)));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setToolTipText("Dismiss");
        closeBtn.addActionListener(e -> { if (listener != null) listener.onDismissRequested(this); });
        JPanel closeWrap = new JPanel(new BorderLayout());
        closeWrap.setOpaque(false);
        closeWrap.add(closeBtn, BorderLayout.NORTH);
        add(closeWrap, BorderLayout.EAST);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (listener != null) listener.onHoverChanged(NotificationPanel.this, true); }
            @Override public void mouseExited(MouseEvent e)  { if (listener != null) listener.onHoverChanged(NotificationPanel.this, false); }
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public Type getType() { return type; }

    public float getAlpha() { return alpha; }
    public void setAlpha(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
        repaint();
    }

    public void setLifeProgress(float p) {
        this.lifeProgress = Math.max(0f, Math.min(1f, p));
        repaint();
    }

    public void setShowProgress(boolean show) {
        this.showProgress = show;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 260), Math.max(d.height, 52));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.SrcOver.derive(alpha));

        g2.setColor(panelBg);
        g2.fillRoundRect(0, 0, getWidth(), getHeight() - 1, 10, 10);

        g2.setColor(borderColor);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 2, 10, 10);

        // Left accent stripe, clipped to the rounded shape.
        g2.setColor(accent);
        g2.fillRoundRect(0, 0, 3, getHeight() - 1, 10, 10);
        g2.fillRect(2, 0, 3, getHeight() - 1);

        if (showProgress) {
            int barWidth = (int) (getWidth() * lifeProgress);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 160));
            g2.fillRoundRect(0, getHeight() - 3, barWidth, 3, 3, 3);
        }

        g2.dispose();
        super.paintComponent(g);
    }
}
