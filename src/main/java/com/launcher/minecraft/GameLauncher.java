package com.launcher.minecraft;

import com.launcher.manager.LauncherPaths;
import com.launcher.model.Account;
import com.launcher.model.Instance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GameLauncher {

    /**
     * Tracks WM_CLASS values for which a .desktop entry has already been
     * installed during this JVM session, so we don't repeat the disk I/O and
     * cache-refresh commands on every launch.
     */
    private static final Set<String> installedWmClasses = ConcurrentHashMap.newKeySet();

    public Process launch(Instance instance, Path gameDir, Path nativesDir, ResolvedVersion version,
                          Account account, Consumer<String> log) throws IOException {

        // ── GNOME taskbar icon fix for the Minecraft window ─────────────────
        // GNOME resolves the taskbar/dash icon by matching the window's WM_CLASS
        // against an installed .desktop file's StartupWMClass, then uses that
        // file's Icon=. It completely ignores the icon set via setIconImage().
        //
        // AWT derives WM_CLASS from the main class name automatically (e.g.
        // "net.minecraft.client.main.Main" → "net-minecraft-client-main-Main").
        // We install a small .desktop file whose StartupWMClass matches whatever
        // Java will use for *this* launch, pointing Icon= at a Minecraft icon we
        // extract to ~/.local/share/icons/. This is a no-op on non-Linux systems
        // and after the first successful install for a given main class.
        try {
            ensureMinecraftDesktopEntry(version.mainClass, log);
        } catch (Throwable ignored) {
            // Never let this block game launch.
        }

        String javaBin = (instance.javaPath != null && !instance.javaPath.isBlank())
                ? instance.javaPath
                : "java"; // relies on PATH; the README explains how to point at a specific JDK instead

        String classpath = String.join(System.getProperty("path.separator"),
                version.classpath.stream().map(p -> p.toAbsolutePath().toString()).toList());

        Map<String, String> placeholders = buildPlaceholders(instance, gameDir, nativesDir, version, account, classpath);

        List<String> command = new ArrayList<>();
        command.add(javaBin);

        // Add RAM configuration converting MB to GB
        int ramGb = instance.ramMb / 1024;
        command.add("-Xmx" + ramGb + "G");
        command.add("-Xms512M");

        // user-supplied JVM args (memory, etc.)
        for (String arg : instance.jvmArgs.trim().split("\\s+")) {
            if (!arg.isBlank()) command.add(arg);
        }
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        // loader/version-specific jvm args from the version json, if any
        for (String arg : version.extraJvmArgs) {
            command.add(substitute(arg, placeholders));
        }

        command.add("-cp");
        command.add(classpath);
        command.add(version.mainClass);

        if (version.legacyMinecraftArguments != null) {
            for (String token : version.legacyMinecraftArguments.split(" ")) {
                command.add(substitute(token, placeholders));
            }
        } else {
            for (String arg : version.extraGameArgs) {
                command.add(substitute(arg, placeholders));
            }
            // Ensure the essentials are present even if a loader's arg list omitted them.
            ensureArg(command, "--username", account.username);
            ensureArg(command, "--uuid", stripDashes(account.uuid));
            ensureArg(command, "--accessToken", "0");
            ensureArg(command, "--userType", "legacy");
            ensureArg(command, "--version", version.id);
            ensureArg(command, "--gameDir", gameDir.toAbsolutePath().toString());
            ensureArg(command, "--assetsDir", LauncherPaths.assetsDir(gameDir).toAbsolutePath().toString());
            if (version.assetIndexId != null) ensureArg(command, "--assetIndex", version.assetIndexId);
        }

        if (!com.launcher.manager.SettingsManager.getInstance().getSettings().hideLaunchCommand) {
            log.accept("Launch command: " + String.join(" ", command));
        } else {
            log.accept("Launch command: [HIDDEN]");
        }

        java.nio.file.Files.createDirectories(gameDir);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private void ensureArg(List<String> command, String flag, String value) {
        if (command.contains(flag)) return;
        command.add(flag);
        command.add(value);
    }

    private Map<String, String> buildPlaceholders(Instance instance, Path gameDir, Path nativesDir,
                                                  ResolvedVersion version, Account account, String classpath) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("auth_player_name", account.username);
        m.put("version_name", version.id);
        m.put("game_directory", gameDir.toAbsolutePath().toString());
        m.put("assets_root", LauncherPaths.assetsDir(gameDir).toAbsolutePath().toString());
        m.put("assets_index_name", version.assetIndexId != null ? version.assetIndexId : "legacy");
        m.put("auth_uuid", stripDashes(account.uuid));
        m.put("auth_access_token", "0");
        m.put("user_type", "legacy");
        m.put("user_properties", "{}");
        m.put("auth_session", "0");
        m.put("version_type", "release");
        m.put("natives_directory", nativesDir.toAbsolutePath().toString());
        m.put("launcher_name", "Zero Launcher");
        m.put("launcher_version", "1.0.0");
        m.put("classpath", classpath);
        m.put("auth_xuid", "0");
        m.put("clientid", "0");
        return m;
    }

    private String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        for (var e : placeholders.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    private String stripDashes(String uuid) {
        return uuid == null ? "00000000000000000000000000000000" : uuid.replace("-", "");
    }

    // ─── GNOME taskbar icon support ──────────────────────────────────────────

    /**
     * Derives the WM_CLASS value that AWT/X11 will use for a given main class.
     * Java's X11 toolkit replaces dots with dashes:
     * {@code "net.minecraft.client.main.Main"} → {@code "net-minecraft-client-main-Main"}.
     */
    private static String wmClassFromMainClass(String mainClass) {
        return mainClass.replace('.', '-');
    }

    /**
     * Installs a {@code .desktop} file + Minecraft icon under the user's
     * {@code ~/.local/share/} directories so that GNOME can resolve the
     * Minecraft window's taskbar/dash icon.
     * <p>
     * GNOME ignores {@code setIconImage()} entirely and instead matches the
     * window's X11 WM_CLASS against installed {@code .desktop} files'
     * {@code StartupWMClass=} values. Without a matching entry the window
     * shows the generic "unknown application" icon.
     * <p>
     * This is a no-op on non-Linux systems, and skips redundant writes when the
     * installed files are already up to date.
     */
    private static void ensureMinecraftDesktopEntry(String mainClass, Consumer<String> log) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }

        String wmClass = wmClassFromMainClass(mainClass);
        if (!installedWmClasses.add(wmClass)) {
            return; // Already installed this session.
        }

        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return;
        }

        // Sanitize the WM_CLASS to a safe filename fragment.
        String safeId = wmClass.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        String desktopFileName = "minecraft-" + safeId + ".desktop";
        String iconName = "minecraft-" + safeId;

        java.io.File desktopDir = new java.io.File(home, ".local/share/applications");
        java.io.File iconDir = new java.io.File(home, ".local/share/icons/hicolor/128x128/apps");
        java.io.File desktopFile = new java.io.File(desktopDir, desktopFileName);
        java.io.File iconFile = new java.io.File(iconDir, iconName + ".png");

        String desiredContent = "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Minecraft\n" +
                "Comment=Minecraft (launched via Zero Launcher)\n" +
                "Exec=true\n" +            // Placeholder — the game is launched by the launcher, not this entry.
                "Icon=" + iconName + "\n" +
                "Terminal=false\n" +
                "Categories=Game;\n" +
                "StartupWMClass=" + wmClass + "\n" +
                "NoDisplay=true\n";         // Don't clutter the app menu — this exists only for icon resolution.

        try {
            boolean desktopUpToDate = desktopFile.isFile()
                    && desiredContent.equals(Files.readString(desktopFile.toPath()));
            boolean iconUpToDate = iconFile.isFile() && iconFile.length() > 0;

            if (desktopUpToDate && iconUpToDate) {
                return; // Already installed correctly.
            }

            Files.createDirectories(desktopDir.toPath());
            Files.createDirectories(iconDir.toPath());

            if (!iconUpToDate) {
                try (InputStream in = GameLauncher.class.getResourceAsStream("/com/launcher/minecraft_image.png")) {
                    if (in != null) {
                        Files.copy(in, iconFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            if (!desktopUpToDate) {
                Files.writeString(desktopFile.toPath(), desiredContent);
                try {
                    Files.setPosixFilePermissions(desktopFile.toPath(),
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (Throwable ignored) {
                    // Non-POSIX filesystem — execute bit isn't required for GNOME's lookup.
                }
            }

            // Nudge GNOME/desktop caches to pick up the change immediately.
            runQuietly("update-desktop-database", desktopDir.getAbsolutePath());
            runQuietly("gtk-update-icon-cache",
                    new java.io.File(home, ".local/share/icons/hicolor").getAbsolutePath());

            log.accept("Installed Minecraft .desktop entry for GNOME taskbar icon (WM_CLASS=" + wmClass + ")");
        } catch (Throwable t) {
            // Best-effort — never block game launch.
            log.accept("Could not install Minecraft .desktop entry: " + t.getMessage());
        }
    }

    private static void runQuietly(String command, String arg) {
        try {
            new ProcessBuilder(command, arg)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Throwable ignored) {
        }
    }
}