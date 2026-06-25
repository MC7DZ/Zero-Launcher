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

    public Instance() {}

    public Instance(String name, String mcVersion, ModLoaderType loader, String loaderVersion) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.mcVersion = mcVersion;
        this.modLoader = loader;
        this.modLoaderVersion = loaderVersion;
    }
}