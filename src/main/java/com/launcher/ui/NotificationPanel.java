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
        this.panelBg = Main.hexToColor(settings.notificationBgColor, new Color(19, 19, 26));
        this.borderColor = Main.hexToColor("#3c3c46", new Color(60, 60, 70));
        this.accent = switch (type) {
            case SUCCESS -> Main.hexToColor(settings.accentColor, new Color(16, 185, 129));
            case WARNING -> Main.hexToColor("#f59e0b", new Color(245, 158, 11));
            case ERROR   -> Main.hexToColor("#ef4444", new Color(239, 68, 68));
            case INFO    -> Main.hexToColor("#9ca3af", new Color(156, 163, 175));
        };

        setOpaque(false);
        setLayout(new BorderLayout(16, 0));
        setBorder(new EmptyBorder(18, 20, 18, 16));

        // Create a custom icon badge
        JPanel iconPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40));
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        iconPanel.setOpaque(false);
        iconPanel.setPreferredSize(new Dimension(36, 36));
        iconPanel.setLayout(new BorderLayout());

        JLabel iconLabel = new JLabel(switch (type) {
            case SUCCESS -> "\u2714";
            case WARNING -> "\u26A0";
            case ERROR   -> "\u2715";
            case INFO    -> "\u2139";
        }, SwingConstants.CENTER);
        iconLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        iconLabel.setForeground(accent);
        iconPanel.add(iconLabel, BorderLayout.CENTER);

        JPanel westWrap = new JPanel(new GridBagLayout());
        westWrap.setOpaque(false);
        westWrap.add(iconPanel);
        add(westWrap, BorderLayout.WEST);

        JPanel textCol = new JPanel();
        textCol.setOpaque(false);
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));

        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        JLabel titleLabel = new JLabel("<html><b>" + escape(title) + "</b></html>");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(titleLabel);

        if (message != null && !message.isBlank()) {
            FontMetrics fm = getFontMetrics(new Font("SansSerif", Font.PLAIN, 13));
            int textW = SwingUtilities.computeStringWidth(fm, message);
            int wrapW = Math.min(420, Math.max(250, textW + 30));
            JLabel msgLabel = new JLabel("<html><body style='width: " + wrapW + "px;'>" + escape(message).replace("\n", "<br>") + "</body></html>");
            msgLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            msgLabel.setForeground(Main.hexToColor("#a1a1aa", new Color(161, 161, 170)));
            msgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(Box.createVerticalStrut(4));
            textCol.add(msgLabel);
        }
        
        JPanel centerWrap = new JPanel(new GridBagLayout());
        centerWrap.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;
        centerWrap.add(textCol, gbc);
        add(centerWrap, BorderLayout.CENTER);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setFont(new Font("SansSerif", Font.PLAIN, 14));
        closeBtn.setForeground(Main.hexToColor("#71717a", new Color(113, 113, 122)));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setToolTipText("Dismiss");
        closeBtn.addActionListener(e -> { if (listener != null) listener.onDismissRequested(this); });
        
        JPanel closeWrap = new JPanel(new GridBagLayout());
        closeWrap.setOpaque(false);
        GridBagConstraints gbcClose = new GridBagConstraints();
        gbcClose.anchor = GridBagConstraints.NORTH;
        gbcClose.weighty = 1.0;
        closeWrap.add(closeBtn, gbcClose);
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
    /** Sets alpha without triggering a repaint; the parent NotificationCenter batches the repaint. */
    public void setAlphaQuiet(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
    }

    public void setLifeProgress(float p) {
        this.lifeProgress = Math.max(0f, Math.min(1f, p));
        repaint();
    }
    /** Sets progress without triggering a repaint; the parent NotificationCenter batches the repaint. */
    public void setLifeProgressQuiet(float p) {
        this.lifeProgress = Math.max(0f, Math.min(1f, p));
    }

    public void setShowProgress(boolean show) {
        this.showProgress = show;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 320), Math.max(d.height, 70));
    }

    private java.awt.image.BufferedImage getFrostedBackground() {
        try {
            Container parent = getParent();
            while (parent != null) {
                if (parent.getClass().getSimpleName().equals("GradientBackgroundPane")) {
                    java.lang.reflect.Method m = parent.getClass().getDeclaredMethod("getSnapshot");
                    m.setAccessible(true);
                    return (java.awt.image.BufferedImage) m.invoke(parent);
                }
                parent = parent.getParent();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.SrcOver.derive(alpha));

        int w = getWidth(), h = getHeight();
        Shape roundedShape = new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, 24, 24);

        java.awt.image.BufferedImage fullBg = getFrostedBackground();
        java.awt.image.BufferedImage blurred = null;

        if (fullBg != null) {
            Container lp = getParent();
            while (lp != null && !lp.getClass().getSimpleName().equals("GradientBackgroundPane")) {
                lp = lp.getParent();
            }
            if (lp != null) {
                Point origin = SwingUtilities.convertPoint(this, 0, 0, lp);
                int fw = fullBg.getWidth(), fh = fullBg.getHeight();
                int sx = Math.max(0, Math.min(origin.x, fw - 1));
                int sy = Math.max(0, Math.min(origin.y, fh - 1));
                int sw = Math.max(1, Math.min(w, fw - sx));
                int sh = Math.max(1, Math.min(h, fh - sy));

                if (sw > 0 && sh > 0) {
                    java.awt.image.BufferedImage crop = fullBg.getSubimage(sx, sy, sw, sh);
                    int blurDivisor = 12; // increased for more blur
                    int thumbW = Math.max(1, sw / blurDivisor);
                    int thumbH = Math.max(1, sh / blurDivisor);
                    Image thumb = crop.getScaledInstance(thumbW, thumbH, Image.SCALE_AREA_AVERAGING);
                    
                    blurred = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    Graphics2D bg2 = blurred.createGraphics();
                    bg2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    bg2.drawImage(thumb, 0, 0, sw, sh, null);
                    bg2.dispose();
                }
            }
        }

        String style = SettingsManager.getInstance().getSettings().notificationStyle;
        boolean isFrosted = "Frosted Glass".equals(style);
        boolean isOutline = "Minimal Outline".equals(style);

        if (isFrosted && blurred != null) {
            Shape oldClip = g2.getClip();
            g2.clip(roundedShape);
            g2.drawImage(blurred, 0, 0, null);
            g2.setColor(new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 140)); // more transparent
            g2.fillRect(0, 0, w, h);
            g2.setClip(oldClip);
        } else {
            if (isOutline) {
                g2.setColor(new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 220));
            } else {
                g2.setColor(panelBg);
            }
            g2.fill(roundedShape);
        }

        if (isOutline) {
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(2f));
        } else {
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.draw(roundedShape);

        Shape oldClipGlobal = g2.getClip();
        g2.clip(roundedShape);

        if (!isOutline) {
            // Left accent stripe
            g2.setColor(accent);
            g2.fillRoundRect(0, 0, 4, h - 1, 16, 16);
            g2.fillRect(3, 0, 2, h - 1);
        }

        if (showProgress) {
            int barWidth = (int) (w * lifeProgress);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 160));
            g2.fillRoundRect(0, h - 3, barWidth, 3, 3, 3);
        }

        g2.setClip(oldClipGlobal);
        g2.dispose();
        super.paintComponent(g);
    }
}
