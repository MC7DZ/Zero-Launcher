package com.launcher.ui;

import java.awt.*;

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
