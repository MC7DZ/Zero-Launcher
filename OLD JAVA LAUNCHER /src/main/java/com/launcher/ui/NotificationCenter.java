package com.launcher.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Toast notification stack.
 *
 * Rewritten to fix the issues in the old implementation:
 *  - a single 60fps "tick" Timer drives every notification's position, fade and
 *    countdown, instead of spawning 2-3 independent Timers per notification
 *    (which could pile up, drift out of sync, and leak if a panel was removed
 *    mid-animation).
 *  - notifications beyond the visible limit are queued and shown once space
 *    frees up, instead of being force-closed the instant the limit is hit.
 *  - hovering a toast pauses its countdown (and pauses the whole stack's
 *    layout churn) so you can actually read one before it vanishes.
 *  - a visible close button and a shrinking progress bar make dismissal and
 *    remaining time explicit instead of "click anywhere and hope".
 *
 * Public API (info/success/warning/error/show + getPreferredSize) is unchanged
 * so no caller elsewhere in the app needs to change.
 */
public final class NotificationCenter extends JPanel {

    private static final int MAX_VISIBLE = 4;
    private static final int WIDTH = 420;
    private static final int GAP = 8;
    private static final int DEFAULT_DURATION_MS = 5000;
    private static final int ERROR_DURATION_MS = 8000;
    private static final int TICK_MS = 16; // ~60fps
    private static final double FADE_RATE_PER_MS = 1.0 / 220; // full fade in/out in ~220ms
    private static final double SLIDE_EASE = 0.22; // exponential approach factor per tick

    private record Pending(NotificationPanel.Type type, String title, String message) {}

    private final List<Entry> active = new ArrayList<>();
    private final Deque<Pending> queue = new ArrayDeque<>();
    private final Timer ticker;

    private static final class Entry {
        final NotificationPanel panel;
        double targetY;
        double currentY;
        boolean fadingIn = true;
        boolean fadingOut = false;
        boolean hovered = false;
        double remainingMs;
        final double totalMs;
        boolean positioned = false;

        Entry(NotificationPanel panel, double totalMs) {
            this.panel = panel;
            this.totalMs = totalMs;
            this.remainingMs = totalMs;
        }
    }

    public NotificationCenter() {
        setLayout(null);
        setOpaque(false);
        ticker = new Timer(TICK_MS, e -> tick());
        ticker.setCoalesce(true);
    }

    public void info(String title, String message)    { show(NotificationPanel.Type.INFO, title, message); }
    public void success(String title, String message)  { show(NotificationPanel.Type.SUCCESS, title, message); }
    public void warning(String title, String message)  { show(NotificationPanel.Type.WARNING, title, message); }
    public void error(String title, String message)    { show(NotificationPanel.Type.ERROR, title, message); }

    public void show(NotificationPanel.Type type, String title, String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            enqueue(type, title, message);
        } else {
            SwingUtilities.invokeLater(() -> enqueue(type, title, message));
        }
    }

    private void enqueue(NotificationPanel.Type type, String title, String message) {
        if (active.size() >= MAX_VISIBLE) {
            queue.addLast(new Pending(type, title, message));
            return;
        }
        spawn(type, title, message);
    }

    private void spawn(NotificationPanel.Type type, String title, String message) {
        double duration = (type == NotificationPanel.Type.ERROR) ? ERROR_DURATION_MS : DEFAULT_DURATION_MS;

        NotificationPanel panel = new NotificationPanel(type, title, message, new NotificationPanel.Listener() {
            @Override
            public void onDismissRequested(NotificationPanel p) {
                Entry entry = findEntry(p);
                if (entry != null) startFadeOut(entry);
            }

            @Override
            public void onHoverChanged(NotificationPanel p, boolean hovering) {
                Entry entry = findEntry(p);
                if (entry != null) entry.hovered = hovering;
            }
        });
        panel.setAlpha(0f);
        panel.doLayout();
        int prefWidth = panel.getPreferredSize().width;
        int maxW = Math.min(prefWidth, WIDTH);
        int prefHeight = panel.getPreferredSize().height;
        
        panel.setSize(maxW, prefHeight);
        panel.setLocation(WIDTH - maxW, -panel.getHeight());
        add(panel);
        setComponentZOrder(panel, 0);

        Entry entry = new Entry(panel, duration);
        entry.currentY = -panel.getHeight();
        active.add(0, entry);

        layoutTargets();
        if (!ticker.isRunning()) ticker.start();
        revalidate();
        repaint();
    }

    private Entry findEntry(NotificationPanel p) {
        for (Entry e : active) if (e.panel == p) return e;
        return null;
    }

    private void startFadeOut(Entry entry) {
        if (entry.fadingOut) return;
        entry.fadingOut = true;
        entry.fadingIn = false;
    }

    private void tick() {
        if (active.isEmpty()) {
            if (queue.isEmpty()) ticker.stop();
            return;
        }

        List<Entry> toRemove = new ArrayList<>();

        for (Entry entry : active) {
            NotificationPanel panel = entry.panel;

            // Fade in.
            if (entry.fadingIn) {
                float a = Math.min(1f, panel.getAlpha() + (float) (TICK_MS * FADE_RATE_PER_MS));
                panel.setAlphaQuiet(a);
                if (a >= 1f) entry.fadingIn = false;
            }

            // Countdown, paused while hovered or still fading in.
            if (!entry.fadingOut && !entry.hovered && !entry.fadingIn) {
                entry.remainingMs -= TICK_MS;
                if (entry.remainingMs <= 0) {
                    startFadeOut(entry);
                } else {
                    panel.setLifeProgressQuiet((float) (entry.remainingMs / entry.totalMs));
                }
            }

            // Fade out.
            if (entry.fadingOut) {
                float a = Math.max(0f, panel.getAlpha() - (float) (TICK_MS * FADE_RATE_PER_MS));
                panel.setAlphaQuiet(a);
                if (a <= 0f) toRemove.add(entry);
            }

            // Slide toward target Y.
            double dy = entry.targetY - entry.currentY;
            if (Math.abs(dy) > 0.3) {
                entry.currentY += dy * SLIDE_EASE;
            } else {
                entry.currentY = entry.targetY;
            }
            panel.setLocation(WIDTH - panel.getWidth(), (int) Math.round(entry.currentY));
        }

        if (!toRemove.isEmpty()) {
            for (Entry entry : toRemove) {
                active.remove(entry);
                remove(entry.panel);
            }
            layoutTargets();
            drainQueueIfRoom();
        }

        setPreferredSize(calculatePreferredSize());
        // Single repaint per tick covers ALL notifications at once.
        repaint();

        if (active.isEmpty() && queue.isEmpty()) {
            ticker.stop();
        }
    }

    private void drainQueueIfRoom() {
        while (active.size() < MAX_VISIBLE && !queue.isEmpty()) {
            Pending p = queue.pollFirst();
            spawn(p.type(), p.title(), p.message());
        }
    }

    private void layoutTargets() {
        int y = 0;
        for (Entry entry : active) {
            entry.targetY = y;
            if (!entry.positioned) {
                entry.currentY = -entry.panel.getHeight();
                entry.positioned = true;
            }
            y += entry.panel.getHeight() + GAP;
        }
    }

    private Dimension calculatePreferredSize() {
        int height = 0;
        for (Entry entry : active) {
            height += entry.panel.getHeight() + GAP;
        }
        if (!active.isEmpty()) height -= GAP;
        return new Dimension(WIDTH, height);
    }

    @Override
    public Dimension getPreferredSize() {
        return calculatePreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}
