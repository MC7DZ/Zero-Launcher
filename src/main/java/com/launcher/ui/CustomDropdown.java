package com.launcher.ui;

import com.launcher.Main;
import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

public class CustomDropdown<T> extends JPanel {
    private final JLabel label;
    private final List<T> items = new ArrayList<>();
    private T selectedItem;
    private final JLayeredPane layeredPane;
    private final Main.GradientBackgroundPane backdrop;
    private Main.RoundedPanel popover;
    private final List<ActionListener> listeners = new ArrayList<>();
    private final String title;
    
    // For rendering custom text, override this or rely on toString()
    public String renderItem(T item) {
        return item == null ? "" : item.toString();
    }

    public CustomDropdown(JLayeredPane layeredPane, Main.GradientBackgroundPane backdrop, String title) {
        this.layeredPane = layeredPane;
        this.backdrop = backdrop;
        this.title = title;
        
        setOpaque(false);
        setLayout(new BorderLayout());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        
        Main.RoundedPanel buttonBg = new Main.RoundedPanel(16, new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 120), new Color(255, 255, 255, 30));
        buttonBg.setLayout(new BorderLayout());
        buttonBg.setBorder(new EmptyBorder(6, 12, 6, 12));
        
        label = new JLabel();
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setForeground(textColor);
        
        JLabel arrow = new JLabel(" ▾");
        arrow.setFont(new Font("SansSerif", Font.BOLD, 12));
        arrow.setForeground(new Color(255, 255, 255, 120));
        
        buttonBg.add(label, BorderLayout.CENTER);
        buttonBg.add(arrow, BorderLayout.EAST);
        add(buttonBg, BorderLayout.CENTER);
        
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                togglePopover();
            }
        });
    }

    public void addItem(T item) {
        items.add(item);
        if (selectedItem == null) {
            setSelectedItem(item);
        }
    }

    public void setSelectedItem(T item) {
        this.selectedItem = item;
        label.setText(renderItem(item));
        for (ActionListener l : listeners) {
            l.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "selectionChanged"));
        }
    }

    public T getSelectedItem() {
        return selectedItem;
    }

    public void addActionListener(ActionListener l) {
        listeners.add(l);
    }

    private void togglePopover() {
        if (popover != null && popover.isVisible()) {
            hidePopover();
        } else {
            showPopover();
        }
    }

    private void showPopover() {
        if (popover == null) {
            buildPopover();
        }
        
        Point loc = SwingUtilities.convertPoint(this, 0, getHeight(), layeredPane);
        int width = Math.max(getWidth(), 200);
        int height = Math.min(items.size() * 32 + 50, 300);
        
        int x = Math.max(0, Math.min(loc.x, layeredPane.getWidth() - width - 8));
        int y = loc.y + 4;
        if (y + height > layeredPane.getHeight() - 8) {
            y = loc.y - getHeight() - height - 4; // Show above
        }
        
        popover.setBounds(x, y, width, height);
        
        Rectangle target = popover.getBounds();
        int slide = 10;
        popover.setBounds(target.x, target.y - slide, target.width, target.height);
        popover.setAlpha(0f);
        layeredPane.setLayer(popover, JLayeredPane.POPUP_LAYER);
        popover.setVisible(true);
        
        long start = System.currentTimeMillis();
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / 150f);
            float eased = 1 - (1 - p) * (1 - p);
            popover.setAlpha(eased);
            popover.setBounds(target.x, target.y - Math.round(slide * (1 - eased)), target.width, target.height);
            if (p >= 1f) {
                popover.setBounds(target);
                t.stop();
            }
        });
        t.start();
    }

    private void hidePopover() {
        if (popover == null || !popover.isVisible()) return;
        Rectangle from = popover.getBounds();
        float startAlpha = popover.getAlpha();
        long start = System.currentTimeMillis();
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / 120f);
            popover.setAlpha(startAlpha * (1 - p));
            popover.setBounds(from.x, from.y - Math.round(8 * p), from.width, from.height);
            if (p >= 1f) {
                t.stop();
                popover.setVisible(false);
                popover.setAlpha(1f);
            }
        });
        t.start();
    }

    private void buildPopover() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        
        popover = new Main.RoundedPanel(14, new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 245), 
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
        popover.setFrostedGlass(backdrop, 6, new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 160));
        popover.setLayout(new BorderLayout());
        
        if (title != null && !title.isBlank()) {
            JLabel titleLbl = new JLabel("  " + title);
            titleLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            titleLbl.setForeground(accent);
            titleLbl.setBorder(new EmptyBorder(8, 8, 8, 8));
            popover.add(titleLbl, BorderLayout.NORTH);
        }
        
        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        
        for (T item : items) {
            JPanel row = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (Boolean.TRUE.equals(getClientProperty("hovered"))) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30));
                        g2.fillRoundRect(4, 0, getWidth() - 8, getHeight(), 8, 8);
                        g2.dispose();
                    } else if (item.equals(selectedItem)) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(255, 255, 255, 10));
                        g2.fillRoundRect(4, 0, getWidth() - 8, getHeight(), 8, 8);
                        g2.dispose();
                    }
                    super.paintComponent(g);
                }
            };
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            row.setBorder(new EmptyBorder(6, 14, 6, 14));
            
            JLabel nameLbl = new JLabel(renderItem(item));
            nameLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            nameLbl.setForeground(item.equals(selectedItem) ? accent : textColor);
            row.add(nameLbl, BorderLayout.CENTER);
            
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.TRUE);
                    row.repaint();
                }
                @Override
                public void mouseExited(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.FALSE);
                    row.repaint();
                }
                @Override
                public void mouseClicked(MouseEvent e) {
                    hidePopover();
                    setSelectedItem(item);
                    listPanel.repaint();
                }
            });
            listPanel.add(row);
        }
        
        JScrollPane scroll = new JScrollPane(listPanel);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        popover.add(scroll, BorderLayout.CENTER);
        
        layeredPane.add(popover, JLayeredPane.POPUP_LAYER);
        
        layeredPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (popover != null && popover.isVisible() && !popover.getBounds().contains(e.getPoint())) {
                    hidePopover();
                }
            }
        });
    }
}
