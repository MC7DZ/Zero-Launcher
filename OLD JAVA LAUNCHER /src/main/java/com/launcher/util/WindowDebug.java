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
 * Central diagnostic logger, gated by "Debug Mode" in Settings > Developer.
 * <p>
 * Started out purely for tracking down the "Show Launcher" / minimize-to-tray
 * invisible-window bug (hence the class name and the window-specific
 * {@link #dumpState}), and has since become the general-purpose debug logger
 * for the whole launcher — networking (every HTTP request/response/retry,
 * download start/finish/failure — see HttpUtil), rendering/visuals (Java2D
 * pipeline configuration, screen/graphics device info, UI-scale and theme
 * changes — see UiScaleManager and Main.applyTheme), and window state.
 * <p>
 * Every call logs a timestamped line to stderr (only when Debug Mode is on —
 * see {@link #isEnabled()}) AND to {@code <launcherRoot>/debug.log}
 * (always, so a report can be pulled after the fact even if the user forgot
 * to enable Debug Mode before hitting an issue), so state can be inspected
 * even when the window itself is invisible/unreachable and there's no
 * console attached (e.g. launched by double-clicking the jar).
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
     * <p>
     * Public so hot paths (e.g. per-HTTP-request logging) can skip building a
     * log message entirely when debugging is off, instead of paying for the
     * string work and then throwing it away inside write().
     */
    public static boolean isEnabled() {
        return isDebugModeEnabled();
    }

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
                logFile = root.resolve("debug.log");
            } catch (Exception e) {
                // Fall back to a temp-dir file if launcherRoot() itself is broken —
                // we especially don't want the debug logger to be the reason nothing
                // gets logged.
                logFile = Path.of(System.getProperty("java.io.tmpdir", "."), "zerolauncher-debug.log");
            }
        }
        return logFile;
    }

    /** Logs a plain tagged message, e.g. WindowDebug.log("hideToTray", "called"). */
    public static void log(String tag, String message) {
        write("[" + tag + "] " + message);
    }

    /** Tagged convenience wrapper for networking diagnostics — requests, responses, retries, downloads. */
    public static void network(String tag, String message) {
        write("[net:" + tag + "] " + message);
    }

    /** Tagged convenience wrapper for rendering/visual diagnostics — pipeline config, UI scale, theme changes. */
    public static void visual(String tag, String message) {
        write("[gfx:" + tag + "] " + message);
    }

    /**
     * Drop-in wrapper around {@link java.net.http.HttpClient#send} that logs the request/
     * response (method, URL, status, elapsed time) to {@link #network} when Debug Mode is
     * on, and is otherwise a plain passthrough. Lets call sites that build their own
     * HttpClient directly (the mod-loader installers, which need per-instance retry/base-URL
     * config) opt into the same network diagnostics as HttpUtil with a one-line change:
     * {@code httpClient.send(req, handler)} -> {@code WindowDebug.loggedSend(httpClient, req, handler)}.
     */
    public static <T> java.net.http.HttpResponse<T> loggedSend(
            java.net.http.HttpClient client, java.net.http.HttpRequest req,
            java.net.http.HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        boolean debug = isEnabled();
        long start = debug ? System.nanoTime() : 0L;
        if (debug) {
            network("request", req.method() + " " + req.uri());
        }
        try {
            java.net.http.HttpResponse<T> resp = client.send(req, handler);
            if (debug) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                network("response", req.method() + " " + req.uri()
                        + " -> HTTP " + resp.statusCode() + " in " + elapsedMs + "ms");
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (debug) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                network("error", req.method() + " " + req.uri()
                        + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + " after " + elapsedMs + "ms");
            }
            throw e;
        }
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
