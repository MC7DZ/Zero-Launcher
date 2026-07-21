package com.launcher.util;

import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Temporary diagnostic helper for tracking down the "Show Launcher" /
 * minimize-to-tray invisible-window bug.
 * <p>
 * Every call logs a timestamped line to stderr AND to
 * {@code <launcherRoot>/window-debug.log}, so the state can be inspected even
 * when the window itself is invisible/unreachable and there's no console
 * attached (e.g. launched by double-clicking the jar).
 * <p>
 * Safe to leave in permanently — it's cheap and only writes on the handful of
 * show/hide transitions, not every frame. Can be ripped out once the bug is
 * confirmed fixed.
 */
public final class WindowDebug {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static Path logFile;

    private WindowDebug() {}

    /**
     * Whether the user has enabled "Debug Mode" in Settings > Developer.
     * Read live (not cached) so toggling the setting takes effect immediately
     * without needing a restart. Defensive against SettingsManager not being
     * initialized yet (e.g. very early startup logging).
     */
    private static boolean isDebugModeEnabled() {
        try {
            return com.launcher.manager.SettingsManager.getInstance().getSettings().debugMode;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized Path logFile() {
        if (logFile == null) {
            try {
                Path root = com.launcher.manager.LauncherPaths.launcherRoot();
                logFile = root.resolve("window-debug.log");
            } catch (Exception e) {
                // Fall back to a temp-dir file if launcherRoot() itself is broken —
                // we especially don't want the debug logger to be the reason nothing
                // gets logged.
                logFile = Path.of(System.getProperty("java.io.tmpdir", "."), "zerolauncher-window-debug.log");
            }
        }
        return logFile;
    }

    /** Logs a plain tagged message, e.g. WindowDebug.log("hideToTray", "called"). */
    public static void log(String tag, String message) {
        write("[" + tag + "] " + message);
    }

    /**
     * Logs a full state dump of the window: visible/showing/displayable,
     * extended-state flags decoded, bounds, screen device, and whether it's
     * actually inside the visible bounds of any connected monitor (a window
     * left positioned off-screen after a monitor got unplugged/resolution
     * changed is another classic cause of "invisible window").
     */
    public static void dumpState(String tag, Window w) {
        if (w == null) {
            write("[" + tag + "] window reference is null");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(tag).append("] ");
        sb.append("visible=").append(w.isVisible());
        sb.append(" showing=").append(w.isShowing());
        sb.append(" displayable=").append(w.isDisplayable());
        sb.append(" opacity=").append(w.getOpacity());
        sb.append(" bounds=").append(w.getBounds());
        sb.append(" alwaysOnTop=").append(w.isAlwaysOnTop());

        if (w instanceof Frame f) {
            int state = f.getExtendedState();
            sb.append(" extendedState=").append(decodeExtendedState(state));
        }

        try {
            var gc = w.getGraphicsConfiguration();
            if (gc != null) {
                sb.append(" device=").append(gc.getDevice().getIDstring());
                Rectangle screenBounds = gc.getBounds();
                Rectangle winBounds = w.getBounds();
                boolean onScreen = screenBounds.intersects(winBounds);
                sb.append(" screenBounds=").append(screenBounds);
                sb.append(" intersectsScreen=").append(onScreen);
                if (!onScreen) {
                    sb.append(" *** WINDOW BOUNDS DO NOT INTERSECT ITS OWN GRAPHICS DEVICE - LIKELY OFF-SCREEN ***");
                }
            } else {
                sb.append(" device=null (window has no GraphicsConfiguration — not attached to any screen!)");
            }
        } catch (Exception e) {
            sb.append(" device=<error: ").append(e).append(">");
        }

        // Check ALL connected screens, not just the one the window thinks it's on —
        // catches the "window remembers a monitor that's no longer connected" case.
        try {
            var envDevices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            Rectangle winBounds = w.getBounds();
            boolean onAnyScreen = false;
            for (var d : envDevices) {
                if (d.getDefaultConfiguration().getBounds().intersects(winBounds)) {
                    onAnyScreen = true;
                    break;
                }
            }
            sb.append(" onAnyConnectedScreen=").append(onAnyScreen);
            if (!onAnyScreen) {
                sb.append(" *** WINDOW IS OFF EVERY CONNECTED SCREEN ***");
            }
        } catch (Exception ignored) {}

        write(sb.toString());
    }

    public static void logException(String tag, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write("[" + tag + "] EXCEPTION: " + sw);
    }

    private static String decodeExtendedState(int state) {
        StringBuilder sb = new StringBuilder();
        sb.append(state).append(" (");
        boolean any = false;
        if ((state & Frame.ICONIFIED) != 0) { sb.append("ICONIFIED"); any = true; }
        if ((state & Frame.MAXIMIZED_HORIZ) != 0) { if (any) sb.append("|"); sb.append("MAXIMIZED_HORIZ"); any = true; }
        if ((state & Frame.MAXIMIZED_VERT) != 0) { if (any) sb.append("|"); sb.append("MAXIMIZED_VERT"); any = true; }
        if (!any) sb.append("NORMAL");
        sb.append(")");
        return sb.toString();
    }

    private static synchronized void write(String message) {
        String line = "[" + LocalTime.now().format(TS) + "] [thread=" + Thread.currentThread().getName() + "] " + message;
        if (isDebugModeEnabled()) {
            System.err.println(line);
        }
        try {
            Files.writeString(logFile(), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort — console output above is the fallback.
        }
    }
}
