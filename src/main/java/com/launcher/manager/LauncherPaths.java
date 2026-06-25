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
            root = Paths.get(appData != null ? appData : System.getProperty("user.home"), "MCLauncher");
        } else if (os.contains("mac")) {
            root = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "MCLauncher");
        } else {
            // Linux / other unix
            root = Paths.get(System.getProperty("user.home"), ".mclauncher");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {}
        return root;
    }

    public static Path accountsFile() {
        return launcherRoot().resolve("accounts.json");
    }

    public static Path instancesFile() {
        return launcherRoot().resolve("instances.json");
    }

    /** Shared cache for downloaded libraries/assets/version jars across ALL instances (saves disk + bandwidth). */
    public static Path sharedCache() {
        return launcherRoot().resolve("cache");
    }

    public static Path librariesDir() {
        return sharedCache().resolve("libraries");
    }

    public static Path assetsDir() {
        return sharedCache().resolve("assets");
    }

    public static Path versionsDir() {
        return sharedCache().resolve("versions");
    }

    /** Default per-instance game directory (used unless the user picks a custom directory for that instance). */
    public static Path defaultInstanceDir(String instanceId) {
        return launcherRoot().resolve("instances").resolve(instanceId);
    }
}
