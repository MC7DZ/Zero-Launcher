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

    /** Default per-instance game directory (used unless the user picks a custom directory for that instance). */
    public static Path defaultInstanceDir(String instanceId) {
        return launcherRoot().resolve("instances").resolve(instanceId);
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
        Path p1 = versionsDir().resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(p1)) return p1;

        Path p2 = getDefaultMinecraftPath().resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(p2)) return p2;

        if (gameDir != null) {
            Path p3 = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(p3)) return p3;
        }
        return null;
    }
}