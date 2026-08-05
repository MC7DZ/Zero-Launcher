package com.launcher.ui;

import javax.swing.*;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * Adds inertia-free but eased "smooth scrolling" to a JScrollPane: instead of
 * the vertical scrollbar jumping straight to the target position on every
 * mouse-wheel notch, it animates toward it over a handful of frames.
 *
 * Usage: just call {@code SmoothScroll.install(myScrollPane);} right after
 * building the JScrollPane — everything else (adding it to a container,
 * setting its border, etc.) works exactly as before.
 */
public final class SmoothScroll {

    private static final int STEP_MS = 12;
    private static final float EASE = 0.28f; // fraction of remaining distance covered per frame
    private static final int SETTLE_PX = 1; // stop animating once this close to the target

    private SmoothScroll() {
    }

    public static void install(JScrollPane pane) {
        install(pane, 16);
    }

    /**
     * @param pane          the scroll pane to animate
     * @param wheelUnitStep how many pixels one notch of the mouse wheel moves
     */
    public static void install(JScrollPane pane, int wheelUnitStep) {
        // We drive the scrollbar ourselves, so turn off JScrollPane's own
        // built-in (instant) wheel handling to avoid double-scrolling.
        pane.setWheelScrollingEnabled(false);

        JScrollBar bar = pane.getVerticalScrollBar();
        bar.setUnitIncrement(wheelUnitStep);

        int[] target = { bar.getValue() };
        javax.swing.Timer[] animTimer = { null };

        MouseWheelListener listener = e -> {
            if (!pane.isShowing()) {
                return;
            }
            JScrollBar vbar = pane.getVerticalScrollBar();
            int max = vbar.getMaximum() - vbar.getModel().getExtent();
            int min = vbar.getMinimum();

            int rotation = e.getWheelRotation();
            int delta = rotation * wheelUnitStep * Math.max(1, e.getScrollAmount());
            target[0] = Math.max(min, Math.min(max, target[0] + delta));

            boolean smoothEnabled = com.launcher.manager.SettingsManager.getInstance()
                    .getSettings().smoothScrolling;
            if (!smoothEnabled) {
                if (animTimer[0] != null && animTimer[0].isRunning()) {
                    animTimer[0].stop();
                }
                vbar.setValue(target[0]);
                return;
            }

            if (animTimer[0] != null && animTimer[0].isRunning()) {
                return; // already animating toward the (now-updated) target
            }
            animTimer[0] = new javax.swing.Timer(STEP_MS, null);
            animTimer[0].addActionListener(ev -> {
                int current = vbar.getValue();
                int diff = target[0] - current;
                if (Math.abs(diff) <= SETTLE_PX) {
                    vbar.setValue(target[0]);
                    ((javax.swing.Timer) ev.getSource()).stop();
                    return;
                }
                vbar.setValue(current + Math.round(diff * EASE));
            });
            animTimer[0].start();
        };

        pane.addMouseWheelListener(listener);
    }
}
