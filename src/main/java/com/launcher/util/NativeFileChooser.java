package com.launcher.util;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opens the operating system's own file/folder picker instead of Swing's
 * themed {@link JFileChooser}, so "Browse…" buttons look and feel exactly
 * like Explorer / Finder / the native GTK-KDE picker the user already knows.
 * <p>
 * Strategy per OS:
 * <ul>
 *   <li><b>Windows</b> — shells out to PowerShell and drives
 *       {@code System.Windows.Forms}' OpenFileDialog / SaveFileDialog /
 *       FolderBrowserDialog, which <i>is</i> the real Windows Explorer picker.</li>
 *   <li><b>macOS</b> — uses AWT's {@link FileDialog}, which is natively
 *       backed by Finder's picker on this platform (including folder mode
 *       via the {@code apple.awt.fileDialogForDirectories} hint).</li>
 *   <li><b>Linux</b> — tries {@code zenity}, then {@code kdialog}, whichever
 *       is on PATH, which use the desktop's native-looking picker.</li>
 * </ul>
 * If none of the above is available (headless environment, missing tool,
 * etc.) every method falls back to {@link JFileChooser} so the feature never
 * breaks — it just loses the "native chrome" in that edge case.
 */
public final class NativeFileChooser {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");

    private NativeFileChooser() {}

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /** Native "Open File" dialog. {@code description}/{@code extensions} may be null for "All files". Returns null if cancelled. */
    public static File openFile(Component parent, String title, String description, String... extensions) {
        return openFile(parent, title, null, description, extensions);
    }

    /** Same as {@link #openFile(Component, String, String, String...)} but starts in {@code initialDir} if it exists. */
    public static File openFile(Component parent, String title, File initialDir, String description, String... extensions) {
        try {
            if (IS_WINDOWS) {
                File f = windowsDialog(buildWindowsOpenScript(title, initialDir, description, extensions));
                if (f != null || wasWindowsShellAvailable) return f;
            } else if (IS_MAC) {
                return macOpenFile(parent, title, initialDir, extensions);
            } else {
                File f = linuxDialog(title, LinuxMode.OPEN_FILE, initialDir, extensions);
                if (f != null || wasLinuxToolAvailable) return f;
            }
        } catch (Exception ignored) {
            // fall through to Swing fallback below
        }
        return fallbackOpenFile(parent, title, initialDir, description, extensions);
    }

    /** Native "Open Folder" dialog. Returns null if cancelled. */
    public static File openDirectory(Component parent, String title) {
        try {
            if (IS_WINDOWS) {
                File f = windowsDialog(buildWindowsFolderScript(title));
                if (f != null || wasWindowsShellAvailable) return f;
            } else if (IS_MAC) {
                return macOpenDirectory(parent, title);
            } else {
                File f = linuxDialog(title, LinuxMode.OPEN_DIR, null, null);
                if (f != null || wasLinuxToolAvailable) return f;
            }
        } catch (Exception ignored) {
            // fall through to Swing fallback below
        }
        return fallbackOpenDirectory(parent, title);
    }

    /** Native "Save File" dialog. Returns null if cancelled. */
    public static File saveFile(Component parent, String title, String defaultFileName, String description, String... extensions) {
        try {
            if (IS_WINDOWS) {
                File f = windowsDialog(buildWindowsSaveScript(title, defaultFileName, description, extensions));
                if (f != null || wasWindowsShellAvailable) return f;
            } else if (IS_MAC) {
                return macSaveFile(parent, title, defaultFileName);
            } else {
                File f = linuxDialog(title, LinuxMode.SAVE_FILE, null, extensions);
                if (f != null || wasLinuxToolAvailable) return f;
            }
        } catch (Exception ignored) {
            // fall through to Swing fallback below
        }
        return fallbackSaveFile(parent, title, defaultFileName, description, extensions);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WINDOWS — PowerShell + System.Windows.Forms (the real Explorer picker)
    // ══════════════════════════════════════════════════════════════════════

    private static volatile boolean wasWindowsShellAvailable = true;

    private static String psFilter(String description, String[] extensions) {
        if (extensions == null || extensions.length == 0) {
            return "All files (*.*)|*.*";
        }
        StringBuilder pattern = new StringBuilder();
        for (String ext : extensions) {
            if (pattern.length() > 0) pattern.append(';');
            pattern.append("*.").append(ext.replace(".", ""));
        }
        String label = (description != null && !description.isBlank()) ? description : "Supported files";
        return label + " (" + pattern + ")|" + pattern + "|All files (*.*)|*.*";
    }

    private static String buildWindowsOpenScript(String title, File initialDir, String description, String[] extensions) {
        return "Add-Type -AssemblyName System.Windows.Forms;" +
                "$f = New-Object System.Windows.Forms.OpenFileDialog;" +
                "$f.Title = '" + escapePs(title) + "';" +
                "$f.Filter = '" + escapePs(psFilter(description, extensions)) + "';" +
                (initialDir != null && initialDir.isDirectory()
                        ? "$f.InitialDirectory = '" + escapePs(initialDir.getAbsolutePath()) + "';" : "") +
                "$f.Multiselect = $false;" +
                "if ($f.ShowDialog() -eq 'OK') { Write-Output $f.FileName }";
    }

    private static String buildWindowsSaveScript(String title, String defaultFileName, String description, String[] extensions) {
        return "Add-Type -AssemblyName System.Windows.Forms;" +
                "$f = New-Object System.Windows.Forms.SaveFileDialog;" +
                "$f.Title = '" + escapePs(title) + "';" +
                "$f.Filter = '" + escapePs(psFilter(description, extensions)) + "';" +
                (defaultFileName != null && !defaultFileName.isBlank()
                        ? "$f.FileName = '" + escapePs(defaultFileName) + "';" : "") +
                "if ($f.ShowDialog() -eq 'OK') { Write-Output $f.FileName }";
    }

    private static String buildWindowsFolderScript(String title) {
        return "Add-Type -AssemblyName System.Windows.Forms;" +
                "$f = New-Object System.Windows.Forms.FolderBrowserDialog;" +
                "$f.Description = '" + escapePs(title) + "';" +
                "$f.ShowNewFolderButton = $true;" +
                "if ($f.ShowDialog() -eq 'OK') { Write-Output $f.SelectedPath }";
    }

    private static String escapePs(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static File windowsDialog(String psScript) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", psScript);
        pb.redirectErrorStream(false);
        Process process;
        try {
            process = pb.start();
        } catch (Exception ex) {
            wasWindowsShellAvailable = false;
            throw ex;
        }
        String line;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            line = reader.readLine();
        }
        process.waitFor();
        wasWindowsShellAvailable = true;
        if (line == null || line.isBlank()) return null;
        return new File(line.trim());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  macOS — native AWT FileDialog (backed by Finder's real picker)
    // ══════════════════════════════════════════════════════════════════════

    private static File macOpenFile(Component parent, String title, File initialDir, String[] extensions) {
        FileDialog fd = new FileDialog(frameFor(parent), title, FileDialog.LOAD);
        if (initialDir != null && initialDir.isDirectory()) fd.setDirectory(initialDir.getAbsolutePath());
        if (extensions != null && extensions.length > 0) {
            fd.setFilenameFilter((dir, name) -> matchesExtension(name, extensions));
        }
        fd.setVisible(true);
        return toFile(fd);
    }

    private static File macSaveFile(Component parent, String title, String defaultFileName) {
        FileDialog fd = new FileDialog(frameFor(parent), title, FileDialog.SAVE);
        if (defaultFileName != null) fd.setFile(defaultFileName);
        fd.setVisible(true);
        return toFile(fd);
    }

    private static File macOpenDirectory(Component parent, String title) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        try {
            FileDialog fd = new FileDialog(frameFor(parent), title, FileDialog.LOAD);
            fd.setVisible(true);
            return toFile(fd);
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false");
        }
    }

    private static File toFile(FileDialog fd) {
        String dir = fd.getDirectory();
        String file = fd.getFile();
        if (dir == null || file == null) return null;
        return new File(dir, file);
    }

    private static Frame frameFor(Component parent) {
        Window w = (parent == null) ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        if (w instanceof Frame f) return f;
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LINUX — zenity / kdialog (whichever is on PATH)
    // ══════════════════════════════════════════════════════════════════════

    private enum LinuxMode { OPEN_FILE, OPEN_DIR, SAVE_FILE }

    private static volatile boolean wasLinuxToolAvailable = true;

    private static File linuxDialog(String title, LinuxMode mode, File initialDir, String[] extensions) throws Exception {
        if (commandExists("zenity")) {
            return runLinuxTool(zenityCommand(title, mode, initialDir, extensions));
        }
        if (commandExists("kdialog")) {
            return runLinuxTool(kdialogCommand(title, mode, initialDir));
        }
        wasLinuxToolAvailable = false;
        return null;
    }

    private static String[] zenityCommand(String title, LinuxMode mode, File initialDir, String[] extensions) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("zenity");
        cmd.add("--file-selection");
        cmd.add("--title=" + title);
        if (mode == LinuxMode.OPEN_DIR) cmd.add("--directory");
        if (mode == LinuxMode.SAVE_FILE) cmd.add("--save");
        if (initialDir != null && initialDir.isDirectory()) {
            cmd.add("--filename=" + initialDir.getAbsolutePath() + File.separator);
        }
        if (extensions != null && extensions.length > 0) {
            StringBuilder pattern = new StringBuilder();
            for (String ext : extensions) {
                if (pattern.length() > 0) pattern.append(' ');
                pattern.append("*.").append(ext.replace(".", ""));
            }
            cmd.add("--file-filter=" + pattern);
        }
        return cmd.toArray(new String[0]);
    }

    private static String[] kdialogCommand(String title, LinuxMode mode, File initialDir) {
        String flag = switch (mode) {
            case OPEN_DIR -> "--getexistingdirectory";
            case SAVE_FILE -> "--getsavefilename";
            default -> "--getopenfilename";
        };
        String startDir = (initialDir != null && initialDir.isDirectory())
                ? initialDir.getAbsolutePath() : System.getProperty("user.home");
        return new String[]{"kdialog", flag, startDir, "--title", title};
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static File runLinuxTool(String[] command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        String line;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            line = reader.readLine();
        }
        process.waitFor();
        wasLinuxToolAvailable = true;
        if (line == null || line.isBlank()) return null;
        return new File(line.trim());
    }

    private static boolean matchesExtension(String name, String[] extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext.toLowerCase(Locale.ROOT).replace(".", ""))) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SWING FALLBACK — only used if the OS-native path genuinely can't run
    // ══════════════════════════════════════════════════════════════════════

    private static File fallbackOpenFile(Component parent, String title, File initialDir, String description, String[] extensions) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (initialDir != null && initialDir.isDirectory()) fc.setCurrentDirectory(initialDir);
        if (extensions != null && extensions.length > 0) {
            fc.setFileFilter(new FileNameExtensionFilter(
                    description != null ? description : "Supported files", extensions));
        }
        return (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) ? fc.getSelectedFile() : null;
    }

    private static File fallbackOpenDirectory(Component parent, String title) {
        JFileChooser dc = new JFileChooser();
        dc.setDialogTitle(title);
        dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return (dc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) ? dc.getSelectedFile() : null;
    }

    private static File fallbackSaveFile(Component parent, String title, String defaultFileName, String description, String[] extensions) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        if (defaultFileName != null) fc.setSelectedFile(new File(defaultFileName));
        if (extensions != null && extensions.length > 0) {
            fc.setFileFilter(new FileNameExtensionFilter(
                    description != null ? description : "Supported files", extensions));
        }
        return (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) ? fc.getSelectedFile() : null;
    }
}
