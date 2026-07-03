package com.launcher.model;

import java.util.UUID;

public class Instance {
    public String id;
    public String name;
    public String mcVersion;
    public ModLoaderType modLoader = ModLoaderType.VANILLA;
    public String modLoaderVersion;

    public boolean useCustomDirectory = false;
    public String customDirectoryPath;

    // Changed from ramGb to ramMb to match UI logic (MB allocation)
    public int ramMb = 3072; // Default to 3GB (3072 MB)
    public String jvmArgs = "-XX:+UseG1GC -XX:-UseAdaptiveSizePolicy";
    public String javaPath;

    public boolean installed = false;
    public boolean hidden = false;
    public long createdAt = System.currentTimeMillis();
    public long playTimeSeconds = 0;

    // Instance image (absolute path to a user-selected image, or null to use default)
    public String imagePath;

    // Modpack installation fields
    public String modpackFilePath;       // absolute path to the .mrpack or .zip file
    public String modpackInstallPath;    // directory where the modpack is installed

    // Last known multiplayer server this instance connected to (host:port or host).
    // Populated by scanning the game log while Minecraft is running. Used by the
    // Discord RPC "connected server" section and by the Settings > Server panel.
    public String lastServerIp;
    public long lastServerConnectedAt = 0L;

    public Instance() {}

    public Instance(String name, String mcVersion, ModLoaderType loader, String loaderVersion) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.mcVersion = mcVersion;
        this.modLoader = loader;
        this.modLoaderVersion = loaderVersion;
    }
}