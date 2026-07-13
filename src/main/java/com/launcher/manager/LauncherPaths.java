package com.launcher.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves where the launcher stores its own config and where instances live by default. */
public class LauncherPaths {

    public static Path launcherRoot() {
        String os = System.getProperty("os.name").toLowerCase();
        Path root;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            root = Paths.get(appData != null ? appData : System.getProperty("user.home"), "Zero Launcher");
        } else if (os.contains("mac")) {
            root = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Zero Launcher");
        } else {
            // Linux / other unix
            root = Paths.get(System.getProperty("user.home"), ".zerolauncher");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {}
        return root;
    }

    public static Path accountsFile() {
        return launcherRoot().resolve("accounts.json");
    }

    /** Lock file used to detect whether another instance of the launcher is already running. */
    public static Path instanceLockFile() {
        return launcherRoot().resolve(".launcher.lock");
    }

    /** File the running instance writes its focus-server port to, for a second launch to read. */
    public static Path instancePortFile() {
        return launcherRoot().resolve(".launcher.port");
    }

    /** Instances list now lives inside .minecraft, same as the official launcher's launcher_profiles/instances data. */
    public static Path instancesFile() {
        return getDefaultMinecraftPath().resolve("instances.json");
    }

    /** Shared cache for downloaded libraries/assets across ALL instances (saves disk + bandwidth). */
    public static Path sharedCache() {
        return launcherRoot().resolve("cache");
    }

    public static Path librariesDir() {
        return getDefaultMinecraftPath().resolve("libraries");
    }

    public static Path assetsDir() {
        return getDefaultMinecraftPath().resolve("assets");
    }

    /** Version jars/jsons now go into .minecraft/versions, same location the official launcher uses. */
    public static Path versionsDir() {
        return getDefaultMinecraftPath().resolve("versions");
    }

    /**
     * Whether the given game directory is the default .minecraft folder (as opposed to a
     * user-configured custom instance directory).
     */
    public static boolean isDefaultMinecraftPath(Path gameDir) {
        if (gameDir == null) return true;
        Path defaultMc = getDefaultMinecraftPath().toAbsolutePath().normalize();
        return gameDir.toAbsolutePath().normalize().equals(defaultMc);
    }

    /**
     * Libraries directory for a given instance's game directory. Instances using the default
     * .minecraft folder keep sharing the global libraries cache (saves disk/bandwidth); instances
     * on a custom path get their own self-contained "libraries" folder inside that path so that
     * everything the mod loader needs lives entirely under the custom directory.
     */
    public static Path librariesDir(Path gameDir) {
        return isDefaultMinecraftPath(gameDir) ? librariesDir() : gameDir.resolve("libraries");
    }

    /** Assets directory for a given instance's game directory. See {@link #librariesDir(Path)}. */
    public static Path assetsDir(Path gameDir) {
        return isDefaultMinecraftPath(gameDir) ? assetsDir() : gameDir.resolve("assets");
    }

    /** Versions directory for a given instance's game directory. See {@link #librariesDir(Path)}. */
    public static Path versionsDir(Path gameDir) {
        return isDefaultMinecraftPath(gameDir) ? versionsDir() : gameDir.resolve("versions");
    }

    public static Path defaultInstanceDir(String instanceName) {
        String safeName = instanceName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return getDefaultMinecraftPath().resolve("instances").resolve(safeName);
    }

    public static Path getDefaultMinecraftPath() {
        String os = System.getProperty("os.name").toLowerCase();
        Path root;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            root = Paths.get(appData != null ? appData : System.getProperty("user.home"), ".minecraft");
        } else if (os.contains("mac")) {
            root = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "minecraft");
        } else {
            // Linux / other unix
            root = Paths.get(System.getProperty("user.home"), ".minecraft");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {
            // If creation fails, we still return the path, the game launch will likely fail later.
        }
        return root;
    }

    /**
     * Searches for a local version JSON file in standard locations.
     */
    public static Path findLocalVersionJson(String versionId, Path gameDir) {
        if (gameDir != null) {
            Path defaultMcDir = getDefaultMinecraftPath().toAbsolutePath().normalize();
            Path gameDirAbs = gameDir.toAbsolutePath().normalize();

            // If gameDir is not .minecraft, look for the self-contained profile. This file describes
            // the INSTANCE's own version (e.g. "1.21.4" with Fabric loader on top), not an arbitrary
            // parent version, so only treat it as a match when its declared "id" actually equals the
            // versionId being searched for. Otherwise, e.g. when resolving a Fabric profile's
            // "inheritsFrom": "1.21.4" parent, this would return the instance's own profile JSON again
            // (which itself has "inheritsFrom": "1.21.4"), causing infinite recursion.
            if (!gameDirAbs.equals(defaultMcDir)) {
                String dirName = gameDir.getFileName().toString();
                Path direct = gameDir.resolve(dirName + ".json");
                if (Files.exists(direct) && jsonIdMatches(direct, versionId)) return direct;
            }

            Path custom = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(custom)) return custom;
        }

        Path p1 = versionsDir().resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(p1)) return p1;

        Path p2 = getDefaultMinecraftPath().resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(p2)) return p2;

        return null;
    }

    /**
     * Cheaply checks whether a version JSON file's own "id" field equals the expected version id.
     * Used to stop {@link #findLocalVersionJson(String, Path)} from returning an instance's own
     * profile JSON when it is being asked for a *different* version id (e.g. one of that profile's
     * own parents), which would otherwise create an inheritance cycle.
     */
    private static boolean jsonIdMatches(Path jsonFile, String expectedId) {
        try {
            String content = Files.readString(jsonFile);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(content);
            return m.find() && m.group(1).equals(expectedId);
        } catch (IOException e) {
            return false;
        }
    }
}