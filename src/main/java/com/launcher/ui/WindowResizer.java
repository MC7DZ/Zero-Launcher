package com.launcher.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Adds OS-style edge/corner drag-to-resize to an undecorated {@link JFrame}.
 * <p>
 * Undecorated windows lose the native resize handles, so this attaches mouse
 * listeners directly to a "margin" panel: a plain {@link JPanel} whose border
 * is exactly {@code margin} pixels wide and whose single child fills the
 * interior. Since the child never covers the border area, mouse events inside
 * that strip are delivered to the margin panel itself, which is all this class
 * needs to detect which edge the cursor is near and resize accordingly.
 */
public final class WindowResizer {

    private enum Edge { N, S, E, W, NE, NW, SE, SW, NONE }

    private final JFrame frame;
    private final JComponent marginPanel;
    private final int margin;

    private Edge activeEdge = Edge.NONE;
    private Rectangle startBounds;
    private Point startMouseOnScreen;

    public WindowResizer(JFrame frame, JComponent marginPanel, int margin) {
        this.frame = frame;
        this.marginPanel = marginPanel;
        this.margin = margin;

        MouseAdapter handler = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateCursor(e.getPoint()); }

            @Override
            public void mousePressed(MouseEvent e) {
                activeEdge = edgeAt(e.getPoint());
                startBounds = frame.getBounds();
                startMouseOnScreen = e.getLocationOnScreen();
                if (activeEdge != Edge.NONE && frame instanceof com.launcher.Main m) {
                    m.beginWindowAdjust();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (activeEdge == Edge.NONE || isMaximized()) return;
                resizeTo(e.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean wasResizing = activeEdge != Edge.NONE;
                activeEdge = Edge.NONE;
                if (wasResizing && frame instanceof com.launcher.Main m) {
                    m.endWindowAdjust();
                    // Undecorated frames get no OS-level help repainting the newly
                    // exposed area while the native window is being resized, so a
                    // stale/black frame from mid-drag can otherwise persist until some
                    // unrelated event happens to trigger a repaint. Force one now that
                    // the drag has actually stopped.
                    forceSyncRepaint();
                }
            }
        };
        marginPanel.addMouseListener(handler);
        marginPanel.addMouseMotionListener(handler);
    }

    private boolean isMaximized() {
        return (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private Edge edgeAt(Point p) {
        if (isMaximized()) return Edge.NONE;
        boolean west = p.x <= margin;
        boolean east = p.x >= marginPanel.getWidth() - margin;
        boolean north = p.y <= margin;
        boolean south = p.y >= marginPanel.getHeight() - margin;

        if (north && west) return Edge.NW;
        if (north && east) return Edge.NE;
        if (south && west) return Edge.SW;
        if (south && east) return Edge.SE;
        if (north) return Edge.N;
        if (south) return Edge.S;
        if (west) return Edge.W;
        if (east) return Edge.E;
        return Edge.NONE;
    }

    private void updateCursor(Point p) {
        if (activeEdge != Edge.NONE) return; // mid-drag; don't fight the drag cursor
        Edge edge = edgeAt(p);
        int cursorType = switch (edge) {
            case N -> Cursor.N_RESIZE_CURSOR;
            case S -> Cursor.S_RESIZE_CURSOR;
            case E -> Cursor.E_RESIZE_CURSOR;
            case W -> Cursor.W_RESIZE_CURSOR;
            case NE -> Cursor.NE_RESIZE_CURSOR;
            case NW -> Cursor.NW_RESIZE_CURSOR;
            case SE -> Cursor.SE_RESIZE_CURSOR;
            case SW -> Cursor.SW_RESIZE_CURSOR;
            case NONE -> Cursor.DEFAULT_CURSOR;
        };
        marginPanel.setCursor(Cursor.getPredefinedCursor(cursorType));
    }

    private void resizeTo(Point mouseOnScreen) {
        int dx = mouseOnScreen.x - startMouseOnScreen.x;
        int dy = mouseOnScreen.y - startMouseOnScreen.y;

        int x = startBounds.x, y = startBounds.y, w = startBounds.width, h = startBounds.height;
        Dimension min = frame.getMinimumSize();

        switch (activeEdge) {
            case E, NE, SE -> w = startBounds.width + dx;
            default -> { }
        }
        switch (activeEdge) {
            case W, NW, SW -> { w = startBounds.width - dx; x = startBounds.x + dx; }
            default -> { }
        }
        switch (activeEdge) {
            case S, SE, SW -> h = startBounds.height + dy;
            default -> { }
        }
        switch (activeEdge) {
            case N, NE, NW -> { h = startBounds.height - dy; y = startBounds.y + dy; }
            default -> { }
        }

        if (w < min.width) {
            if (x != startBounds.x) x = startBounds.x + (startBounds.width - min.width);
            w = min.width;
        }
        if (h < min.height) {
            if (y != startBounds.y) y = startBounds.y + (startBounds.height - min.height);
            h = min.height;
        }

        frame.setBounds(x, y, w, h);

        // Undecorated windows don't get the native window-manager repaint that
        // decorated frames receive while resizing: the OS grows/shrinks the real
        // window instantly, but Swing's normal repaint is asynchronous and gets
        // coalesced, so the newly exposed region is left showing black (growing)
        // or stale content from before the resize ("ghosting", shrinking) for a
        // visible stretch of the drag. Forcing a synchronous repaint on every
        // step closes that gap.
        forceSyncRepaint();
    }

    /** Synchronously repaints the frame's content right now, instead of letting Swing's
     *  normal asynchronous/coalesced repaint queue decide when to get to it. Needed
     *  because this is an undecorated window: there's no native window-manager repaint
     *  to paper over the gap between a native resize and Swing's next scheduled repaint. */
    private void forceSyncRepaint() {
        frame.validate();
        JRootPane root = frame.getRootPane();
        root.paintImmediately(0, 0, root.getWidth(), root.getHeight());
        // Flushes any buffered native drawing calls to the screen right away instead of
        // waiting for the toolkit's own batching — matters most on Linux/X11, harmless
        // elsewhere.
        Toolkit.getDefaultToolkit().sync();
    }
}
