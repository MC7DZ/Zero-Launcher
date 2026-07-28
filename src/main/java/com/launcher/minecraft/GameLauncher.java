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

        // "Settings default" (instance.javaPath blank) means exactly that: fall back to the
        // launcher-wide Java path configured in Settings > Performance, if the user set one there.
        // Previously this just used "java" on PATH unconditionally and silently ignored whatever
        // was configured in Settings, which made the "Settings default" label a lie.
        String settingsJavaPath = null;
        try {
            settingsJavaPath = com.launcher.manager.SettingsManager.getInstance().getSettings().javaPath;
        } catch (Throwable ignored) {
            // Settings not available - fall through to PATH java below.
        }

        String javaBin = (instance.javaPath != null && !instance.javaPath.isBlank())
                ? instance.javaPath
                : (settingsJavaPath != null && !settingsJavaPath.isBlank())
                        ? settingsJavaPath
                        : "java"; // Smart Java Selection: relies on PATH, auto-installs below if absent

        boolean usingPinnedJava = (instance.javaPath != null && !instance.javaPath.isBlank())
                || (settingsJavaPath != null && !settingsJavaPath.isBlank());

        // ── No Java at all? Auto-install one ──────────────────────────────────
        // If the instance doesn't pin a specific Java executable and there isn't one on PATH
        // either, don't just fail with a cryptic "Cannot run program java" IOException - download
        // a matching JDK automatically (see JavaInstaller) and use that instead. This mirrors what
        // the official launcher does with its bundled runtimes, without us having to ship one.
        if (!usingPinnedJava) {
            if (detectJavaMajorVersion(javaBin) == null) {
                int wantedMajor = (isLegacyLwjgl2(version) || isLegacyLaunchWrapper(version)) ? 8 : 21;
                log.accept("No Java installation found on this system - downloading Java " + wantedMajor
                        + " automatically...");
                String installed = com.launcher.util.JavaInstaller.ensureJavaQuietly(wantedMajor, log);
                if (installed != null) {
                    javaBin = installed;
                } else {
                    log.accept("WARNING: Automatic Java install failed. Install a JDK yourself and set it as "
                            + "this instance's Java executable (Edit Instance > Java Executable Path), or add "
                            + "\"java\" to your PATH.");
                }
            }
        }

        // ── Legacy LWJGL2 / LaunchWrapper + modern JDK guard ─────────────────
        // Two related but distinct problems show up on old (pre-1.13-ish) versions when run
        // under Java 9+:
        //   1. Versions bundling LWJGL 2 (vanilla 1.12.2 and earlier) crash on JDK 9+ with an
        //      UnsatisfiedLinkError from liblwjgl(64).so ("...libjawt.so: version
        //      `SUNWprivate_1.1' not found"): that native library was built against an old
        //      Sun/Oracle JDK's AWT native symbols, which modern OpenJDK builds no longer export.
        //   2. Old Forge/LiteLoader builds run through net.minecraft.launchwrapper.Launch, which
        //      hard-casts the *system* class loader to java.net.URLClassLoader so it can inject
        //      itself into it (Launch.java:34: "(URLClassLoader) ClassLoader.getSystemClassLoader()").
        //      Since Java 9 the system/app class loader is no longer a URLClassLoader at all, so
        //      this throws a ClassCastException immediately on startup, before Minecraft or Forge
        //      even gets to run - completely independent of whether LWJGL 2's native crash would
        //      also apply.
        // Neither of these has a JVM flag workaround - they genuinely need a Java 8 runtime. If
        // the user hasn't pinned a specific Java executable for this instance, try to steer them
        // onto an installed Java 8 automatically; otherwise at least explain clearly what's about
        // to go wrong instead of letting them puzzle over a cryptic stack trace.
        boolean needsJava8 = isLegacyLwjgl2(version) || isLegacyLaunchWrapper(version);
        if (needsJava8) {
            Integer javaMajor = detectJavaMajorVersion(javaBin);
            if (javaMajor != null && javaMajor >= 9) {
                String auto = !usingPinnedJava
                        ? findInstalledJava8()
                        : null;
                if (auto == null && !usingPinnedJava) {
                    // No Java 8 installed anywhere on the system - download one instead of just
                    // warning about the crash that's about to happen.
                    log.accept("This version needs Java 8 (legacy LWJGL2/LaunchWrapper) and isn't compatible "
                            + "with Java " + javaMajor + " - no Java 8 install was found, downloading one "
                            + "automatically...");
                    auto = com.launcher.util.JavaInstaller.ensureJavaQuietly(8, log);
                }
                if (auto != null) {
                    log.accept("Launching with Java 8 instead (" + auto
                            + "). You can pin this permanently in the instance's Java settings.");
                    javaBin = auto;
                } else {
                    log.accept("WARNING: This version needs Java 8 (legacy LWJGL2/LaunchWrapper) and is not "
                            + "compatible with Java " + javaMajor + " - it will crash on startup (either an "
                            + "UnsatisfiedLinkError from liblwjgl, or a ClassCastException in LaunchWrapper). "
                            + "Install a Java 8 runtime and set it as this instance's Java executable (Edit "
                            + "Instance > Java Executable Path, or the launcher-wide default in Settings > "
                            + "Performance).");
                }
            }
        }

        // Now that javaBin is finalized (pinned, on PATH, auto-downloaded, or swapped to a Java 8
        // fallback above), figure out exactly which major version it is so we can filter out any
        // JVM arguments it doesn't understand rather than crashing on launch.
        Integer finalJavaMajor = detectJavaMajorVersion(javaBin);

        String classpath = String.join(System.getProperty("path.separator"),
                version.classpath.stream().map(p -> p.toAbsolutePath().toString()).toList());

        // ── Pre-flight classpath sanity check for legacy LWJGL2 versions ────
        // This is the check the popular launchers effectively get "for free" because they
        // download every library from a fixed, known-good manifest and never hand-roll the
        // library list themselves. We do hand-roll it, so instead of finding out the classpath
        // was missing/broken 3 seconds into a Minecraft window (a bare NoClassDefFoundError that
        // gives no hint which jar or why), verify the actual class the game needs is loadable
        // from *something* on the classpath before we ever spawn the process. If it isn't, fail
        // here with the exact jar path(s) we expected it in, so the problem is diagnosable
        // instead of another round of "it crashed the same way again".
        if (isLegacyLwjgl2(version)) {
            String missingClass = "org/lwjgl/opengl/OpenGLException.class";
            boolean found = false;
            List<Path> lwjglLikeCandidates = new ArrayList<>();
            for (Path p : version.classpath) {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fn.contains("lwjgl") && !fn.contains("lwjgl_util") && !fn.contains("lwjgl-util")
                        && !fn.contains("platform") && !fn.contains("natives")) {
                    lwjglLikeCandidates.add(p);
                }
                if (!Files.exists(p)) continue;
                try (var zip = new java.util.zip.ZipFile(p.toFile())) {
                    if (zip.getEntry(missingClass) != null) {
                        found = true;
                        break;
                    }
                } catch (IOException ignored) {
                    // Not a readable zip/jar - can't contain the class either way.
                }
            }
            if (!found) {
                StringBuilder sb = new StringBuilder();
                sb.append("This is a legacy LWJGL2 version (\"").append(version.id).append("\"), but no jar on the ")
                  .append("resolved classpath actually contains ").append(missingClass).append(". Minecraft would ")
                  .append("crash immediately with NoClassDefFoundError if launched like this, so the launch was ")
                  .append("stopped before opening a game window.\n");
                if (lwjglLikeCandidates.isEmpty()) {
                    sb.append("No lwjgl-looking jar was found on the classpath at all - the main LWJGL library ")
                      .append("entry is missing from the resolved version JSON or failed to resolve.");
                } else {
                    sb.append("Closest candidate(s) on the classpath (present but don't contain the class, so ")
                      .append("they're either the wrong artifact or corrupt):\n");
                    for (Path p : lwjglLikeCandidates) {
                        sb.append("  - ").append(p).append(Files.exists(p) ? "" : " (file does not exist)").append("\n");
                    }
                }
                sb.append("HOW TO FIX: delete this version's folder under \"versions/").append(version.id)
                  .append("\" (and, for this instance, the parent \"versions/")
                  .append("<minecraft version>\" folder too) so the launcher re-resolves the library list from ")
                  .append("scratch, then try again.");
                throw new IOException(sb.toString());
            }
        }

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
        // Modern mod loader version JSONs (NeoForge, recent Forge/Fabric) commonly include
        // Java Platform Module System flags such as "-p <path>", "--add-modules", "--add-opens",
        // etc. Those flags simply don't exist on Java 8 - passing them causes the JVM to bail out
        // immediately with "Unrecognized option: -p" / "Could not create the Java Virtual
        // Machine", before Minecraft ever gets a chance to run. If we've determined we're
        // launching with a pre-9 JVM (e.g. the Java 8 fallback for legacy LWJGL2 above), strip
        // those flags - and their accompanying value token - out instead of blindly forwarding
        // them.
        boolean targetSupportsModules = finalJavaMajor == null || finalJavaMajor >= 9;
        List<String> resolvedExtraArgs = new ArrayList<>();
        for (String arg : version.extraJvmArgs) {
            resolvedExtraArgs.add(substitute(arg, placeholders));
        }
        for (int i = 0; i < resolvedExtraArgs.size(); i++) {
            String arg = resolvedExtraArgs.get(i);
            if (!targetSupportsModules && isModuleSystemArg(arg)) {
                if (moduleSystemArgTakesValue(arg) && i + 1 < resolvedExtraArgs.size()
                        && !resolvedExtraArgs.get(i + 1).startsWith("-")) {
                    i++; // also skip the flag's value token, e.g. "-p" "<modulepath>"
                }
                continue;
            }
            command.add(arg);
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

    /** True if {@code arg} is a JVM flag that only exists on Java 9+ (the module system flags),
     *  which older Java 8 runtimes reject outright at startup with "Unrecognized option". */
    private static boolean isModuleSystemArg(String arg) {
        if (arg == null) return false;
        String base = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
        return switch (base) {
            case "-p", "--module-path",
                 "--add-modules", "--add-opens", "--add-exports", "--add-reads",
                 "--patch-module", "--limit-modules", "--upgrade-module-path",
                 "--illegal-access", "--enable-preview", "-m", "--module",
                 "--sun-misc-unsafe-memory-access" -> true;
            default -> false;
        };
    }

    /** Whether the given module-system flag takes a separate value token after it (as opposed to
     *  using "=" inline, e.g. "--add-opens=..."), so callers know whether to also drop the next
     *  token from the argument list. */
    private static boolean moduleSystemArgTakesValue(String arg) {
        return arg != null && !arg.contains("=");
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

    /** Whether this resolved version's classpath pulls in LWJGL 2 (vanilla 1.12.2 and earlier,
     *  and old modloader builds on top of them) rather than LWJGL 3.
     *  <p>
     *  Careful: directory layout for LWJGL jars varies a lot between installers - some lay it out
     *  as {@code lwjgl/lwjgl/2.9.4-nightly-.../lwjgl-2.9.4-nightly-....jar}, old Forge installers
     *  sometimes produce {@code org/lwjgl/lwjgl/lwjgl/2.9.4-.../lwjgl-2.9.4-....jar} (yes, "lwjgl"
     *  three times), and modern LWJGL 3 uses {@code org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar}. Trying
     *  to distinguish these by directory shape is fragile and has broken twice already. The one
     *  thing that's actually reliable is the jar's own version number in its filename: only match
     *  on a "lwjgl-2.<digit>" filename token, which is true LWJGL 2 regardless of how the
     *  surrounding group/artifact folders are named.
     */
    private static final java.util.regex.Pattern LWJGL2_FILENAME =
            java.util.regex.Pattern.compile("(?:^|/)lwjgl-2\\.\\d");

    private static boolean isLegacyLwjgl2(ResolvedVersion version) {
        for (Path p : version.classpath) {
            String s = p.toString().replace('\\', '/');
            if (LWJGL2_FILENAME.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    /** True if this version launches through Mojang/Forge's old {@code LaunchWrapper}
     *  ({@code net.minecraft.launchwrapper.Launch}), used by vanilla up through ~1.12.2 and by
     *  every Forge/LiteLoader build on top of those versions. LaunchWrapper hard-casts the
     *  system class loader to {@link java.net.URLClassLoader} to bootstrap itself
     *  ({@code (URLClassLoader) ClassLoader.getSystemClassLoader()}), which throws a
     *  {@code ClassCastException} on Java 9+ where the system class loader is no longer a
     *  {@code URLClassLoader}. This is checked independently of {@link #isLegacyLwjgl2}: a
     *  Forge installer can produce a classpath layout that {@code isLegacyLwjgl2}'s filename
     *  pattern doesn't happen to match, but as long as the main class is LaunchWrapper, Java 8
     *  is still required regardless of what the LWJGL jar happens to be named. */
    private static boolean isLegacyLaunchWrapper(ResolvedVersion version) {
        return "net.minecraft.launchwrapper.Launch".equals(version.mainClass);
    }

    /** Runs {@code <javaBin> -version} and returns the major version (8, 11, 17, 21, ...), or
     *  null if it couldn't be determined (missing binary, unexpected output, etc). Handles both
     *  the old "1.8.0_412" style and the modern "17.0.9" style version strings. */
    private static Integer detectJavaMajorVersion(String javaBin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            var m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(out.toString());
            if (!m.find()) return null;
            String version = m.group(1);
            if (version.startsWith("1.")) {
                // Old scheme: "1.8.0_412" -> major version 8
                String[] parts = version.split("\\.");
                return parts.length > 1 ? Integer.parseInt(parts[1]) : null;
            } else {
                // Modern scheme: "17.0.9" -> major version 17
                String major = version.split("[.\\-+]")[0];
                return Integer.parseInt(major);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Looks for an installed Java 8 runtime to fall back on for legacy LWJGL2 versions. Returns
     *  the executable path, or null if none was found. */
    private static String findInstalledJava8() {
        try {
            for (com.launcher.util.JavaInstallationFinder.JavaInstallation install :
                    com.launcher.util.JavaInstallationFinder.findInstallations()) {
                Integer major = detectJavaMajorVersion(install.javaExecutablePath);
                if (major != null && major == 8) {
                    return install.javaExecutablePath;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}