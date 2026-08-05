package com.launcher.util;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Window;

/**
 * Applies a user-controlled UI scale (50%-300%, adjustable at runtime from a Settings
 * slider) on top of whatever HiDPI scaling FlatLaf/the OS already applied.
 *
 * <p>IMPORTANT: this deliberately does NOT call {@link SwingUtilities#updateComponentTreeUI}.
 * That call re-resolves every component's UI delegate from UIManager, which sounds right
 * for a "rescale everything" pass, but it silently discards any UI delegate a component
 * installed for itself — e.g. this codebase's {@code CustomComboBox}/{@code CustomToggle}
 * install their own {@code ThemedComboBoxUI}/{@code CustomToggleUI} in their constructor.
 * updateComponentTreeUI overwrites those with FlatLaf's stock defaults, which is exactly
 * why those components turned flat gray the first time this was tried. It also tears down
 * and reinstalls the UI of every component currently under the mouse — including the
 * slider being dragged — which aborts the drag gesture mid-motion.
 *
 * <p>Instead this walks the live component tree and rescales each component's own font in
 * place (preserving family/style, and each component's size *relative* to the others —
 * an 11pt label and a 13pt label both grow by the same ratio), which every Swing/FlatLaf
 * component reflows from via revalidate(), without touching anyone's UI delegate.
 *
 * <p>Usage:
 * <pre>
 *   UiScaleManager.captureBaseline();     // once, right after UIManager.setLookAndFeel(...)
 *   UiScaleManager.apply(settings.uiScalePercent); // once at startup, and again on slider change
 * </pre>
 */
public final class UiScaleManager {

    private static final int MIN_PERCENT = 50;
    private static final int MAX_PERCENT = 300;

    /** The "defaultFont" size FlatLaf installed before any user scaling was applied. */
    private static float baselineFontSize = 13f;
    private static String baselineFontName = Font.SANS_SERIF;
    private static int baselineFontStyle = Font.PLAIN;
    private static boolean baselineCaptured = false;

    private static int currentPercent = 100;
    /** The scale factor already reflected in every live component's current font. */
    private static float appliedFactor = 1f;

    private UiScaleManager() {
    }

    /**
     * Records the current UIManager "defaultFont" as the 100% baseline. Must be called
     * once, immediately after the look and feel is installed and before {@link #apply}
     * is ever called — otherwise a later apply() would scale an already-scaled font.
     */
    public static synchronized void captureBaseline() {
        if (baselineCaptured) return;
        Font f = UIManager.getFont("defaultFont");
        if (f != null) {
            baselineFontSize = f.getSize2D();
            baselineFontName = f.getName();
            baselineFontStyle = f.getStyle();
        }
        baselineCaptured = true;
    }

    public static int getCurrentPercent() {
        return currentPercent;
    }

    public static int clamp(int percent) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    /**
     * Applies the given scale percentage (100 = normal) to every open window, live,
     * without requiring an application restart and without disturbing any component's
     * custom UI delegate. Safe to call repeatedly (e.g. from a slider's change listener)
     * — always computed from the captured 100% baseline, so repeated calls never compound.
     */
    public static synchronized void apply(int percent) {
        if (!baselineCaptured) captureBaseline();
        int clamped = clamp(percent);
        if (clamped == currentPercent) {
            // No-op: avoids a redundant full component-tree walk + repaint when called
            // multiple times with the same effective value (e.g. the startup apply()
            // matching an already-current 100%, or a debounce timer firing after the
            // value settled back to where it started).
            return;
        }
        if (WindowDebug.isEnabled()) {
            WindowDebug.visual("scale", currentPercent + "% -> " + clamped + "%"
                    + " (baseline=" + baselineFontSize + "pt " + baselineFontName + ")");
        }
        currentPercent = clamped;
        float newFactor = currentPercent / 100f;

        // Ratio to apply on top of whatever's already reflected in live component fonts —
        // NOT newFactor itself, since components' *current* sizes already include the
        // previously-applied factor.
        float ratio = newFactor / appliedFactor;

        // So newly-created components (dialogs opened after this point, etc.) also pick
        // up the new scale from the start.
        UIManager.put("defaultFont", new FontUIResource(
                baselineFontName, baselineFontStyle, Math.round(baselineFontSize * newFactor)));

        // Best-effort: nudge FlatLaf's own scale-aware metrics (padding/arcs/icon sizes
        // for components it fully controls) if this FlatLaf version exposes the API
        // publicly. This does not touch any component's UI delegate itself, so it's safe
        // to combine with the font-based pass below.
        tryUpdateFlatLafUserScaleFactor(newFactor);

        rescaleAllWindows(ratio);
        appliedFactor = newFactor;
    }

    private static void tryUpdateFlatLafUserScaleFactor(float factor) {
        try {
            Class<?> uiScaleClass = Class.forName("com.formdev.flatlaf.util.UIScale");
            uiScaleClass.getMethod("setUserScaleFactor", float.class).invoke(null, factor);
        } catch (Throwable ignored) {
            // Not present/public in this FlatLaf version — font-based scaling still applies.
        }
    }

    private static void rescaleAllWindows(float ratio) {
        Runnable r = () -> {
            for (Window w : Window.getWindows()) {
                if (!w.isDisplayable()) continue;
                rescaleRecursively(w, ratio);
                w.revalidate();
                w.repaint();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Scales this component's own font by {@code ratio} (preserving family/style), then recurses into children. */
    private static void rescaleRecursively(Component comp, float ratio) {
        if (comp == null) return;
        Font f = comp.getFont();
        if (f != null) {
            int newSize = Math.round(f.getSize2D() * ratio);
            if (newSize != f.getSize()) {
                comp.setFont(f.deriveFont((float) newSize));
            }
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                rescaleRecursively(child, ratio);
            }
        }
    }
}
