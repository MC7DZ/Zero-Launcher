package com.launcher.ui;

import com.launcher.Main;
import com.launcher.model.LauncherSettings;
import com.launcher.manager.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public final class NotificationCenter {

    public enum Type { INFO, SUCCESS, WARNING, ERROR }

    private static final List<JWindow> activeToasts = new ArrayList<>();
    private final JFrame owner;

    public NotificationCenter(JFrame owner) {
        this.owner = owner;
    }

    public void info(String title, String message)    { show(Type.INFO, title, message); }
    public void success(String title, String message)  { show(Type.SUCCESS, title, message); }
    public void warning(String title, String message)  { show(Type.WARNING, title, message); }
    public void error(String title, String message)    { show(Type.ERROR, title, message); }

    public void show(Type type, String title, String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            display(type, title, message);
        } else {
            SwingUtilities.invokeLater(() -> display(type, title, message));
        }
    }

    private synchronized void display(Type type, String title, String message) {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));

        Color accentVar = switch (type) {
            case SUCCESS -> accent;
            case WARNING -> new Color(245, 158, 11);
            case ERROR   -> new Color(239, 68, 68);
            case INFO    -> new Color(156, 163, 175);
        };

        String icon = switch (type) {
            case SUCCESS -> "✔";
            case WARNING -> "⚠";
            case ERROR   -> "✕";
            case INFO    -> "ℹ";
        };

        JWindow toast = new JWindow(owner);
        toast.setType(Window.Type.POPUP);
        toast.setFocusableWindowState(false);

        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(panelBg);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70), 1, true),
                new EmptyBorder(10, 12, 10, 10)
        ));

        // Stripe
        JPanel stripe = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(3, 40);
            }
        };
        stripe.setBackground(accentVar);
        panel.add(stripe, BorderLayout.WEST);

        // Center Content (Icon + Texts)
        JPanel contentPanel = new JPanel(new BorderLayout(8, 0));
        contentPanel.setBackground(panelBg);

        JLabel iconLabel = new JLabel(icon);
        iconLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        iconLabel.setForeground(accentVar);
        contentPanel.add(iconLabel, BorderLayout.WEST);

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setBackground(panelBg);

        JLabel titleLabel = new JLabel("<html><b>" + title + "</b></html>");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        titleLabel.setForeground(textColor);
        textCol.add(titleLabel);

        if (message != null && !message.isBlank()) {
            JLabel msgLabel = new JLabel("<html><body style='width: 200px;'>" + message.replace("\n", "<br>") + "</body></html>");
            msgLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            msgLabel.setForeground(new Color(156, 163, 175));
            textCol.add(Box.createVerticalStrut(3));
            textCol.add(msgLabel);
        }
        contentPanel.add(textCol, BorderLayout.CENTER);
        panel.add(contentPanel, BorderLayout.CENTER);

        toast.setContentPane(panel);
        toast.pack();

        int toastWidth = 320;
        int toastHeight = toast.getHeight();
        toast.setSize(toastWidth, toastHeight);

        // Position toast
        activeToasts.add(0, toast);
        repositionToasts();

        toast.setVisible(true);

        // Auto dismiss timer
        int duration = (type == Type.ERROR) ? 7000 : 5000;
        Timer timer = new Timer(duration, e -> removeToast(toast));
        timer.setRepeats(false);
        timer.start();

        // Click to dismiss
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                timer.stop();
                removeToast(toast);
            }
        });
    }

    private synchronized void removeToast(JWindow toast) {
        toast.dispose();
        activeToasts.remove(toast);
        repositionToasts();
    }

    private synchronized void repositionToasts() {
        if (owner == null) return;
        Point ownerLoc = owner.getLocationOnScreen();
        int ownerWidth = owner.getWidth();
        int startX = ownerLoc.x + ownerWidth - 320 - 16;
        int startY = ownerLoc.y + 60;

        int currentY = startY;
        for (JWindow t : activeToasts) {
            t.setLocation(startX, currentY);
            currentY += t.getHeight() + 8;
        }
    }
}
