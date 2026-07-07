package com.launcher.ui;

import java.awt.*;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * A modified {@link FlowLayout} that correctly reports its preferred height
 * when placed inside a {@link javax.swing.JScrollPane}.
 * <p>
 * Standard {@code FlowLayout} always returns the height for a single row,
 * which prevents the scroll pane from showing a vertical scrollbar and forces
 * everything onto one line.  This subclass overrides
 * {@link #preferredLayoutSize(Container)} so that it wraps components based on
 * the container's <em>current</em> width and reports the resulting multi‑row
 * height.  The effect is a responsive "flow grid" that re-wraps whenever the
 * window is resized.
 * <p>
 * <b>Important:</b> for the wrapping to actually happen when this layout is
 * used on a panel inside a {@link javax.swing.JScrollPane}, that panel must
 * also implement {@link javax.swing.Scrollable} and have
 * {@code getScrollableTracksViewportWidth()} return {@code true} — see
 * {@link WrapLayout#wrapScrollablePanel(int, int, int)} for a ready-made
 * panel that does this. Without it, the viewport never constrains the
 * panel's width, {@link Container#getSize()} keeps reporting an oversized
 * (or zero) width, and every card ends up crammed onto a single row that
 * gets clipped instead of wrapping.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout() {
        super();
    }

    public WrapLayout(int align) {
        super(align);
    }

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    /**
     * Creates a {@link JPanel} using a centered {@link WrapLayout} that is
     * also {@link javax.swing.Scrollable} and tracks the viewport's width, so
     * it correctly wraps its children into multiple centered rows when placed
     * inside a {@link javax.swing.JScrollPane}.
     */
    public static JPanel wrapScrollablePanel(int align, int hgap, int vgap) {
        return new ScrollableWrapPanel(align, hgap, vgap);
    }

    /**
     * A {@link JPanel} that actually implements {@link javax.swing.Scrollable}
     * (plain {@code JPanel} does not), so a {@link javax.swing.JViewport}
     * forces it to match the viewport's width instead of letting it report an
     * unconstrained preferred width. That, in turn, is what makes
     * {@link WrapLayout} wrap children onto multiple rows instead of laying
     * them all out on one row that gets clipped.
     */
    private static class ScrollableWrapPanel extends JPanel implements javax.swing.Scrollable {
        ScrollableWrapPanel(int align, int hgap, int vgap) {
            super(new WrapLayout(align, hgap, vgap));
        }
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return computeSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = computeSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension computeSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;

            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            Insets insets = target.getInsets();
            int maxWidth = targetWidth - insets.left - insets.right - getHgap() * 2;
            int hgap = getHgap();
            int vgap = getVgap();

            int x = 0;
            int y = insets.top + vgap;
            int rowHeight = 0;

            int nmembers = target.getComponentCount();

            for (int i = 0; i < nmembers; i++) {
                Component m = target.getComponent(i);
                if (m.isVisible()) {
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();

                    // If adding this component exceeds maxWidth, wrap to next row
                    if (x > 0 && x + d.width > maxWidth) {
                        y += rowHeight + vgap;
                        x = 0;
                        rowHeight = 0;
                    }

                    x += d.width + hgap;
                    rowHeight = Math.max(rowHeight, d.height);
                }
            }

            y += rowHeight + vgap;

            return new Dimension(targetWidth, y + insets.bottom);
        }
    }
}
