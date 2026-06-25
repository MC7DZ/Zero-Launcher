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

    public String jvmArgs = "-Xmx2G -Xms512M";
    public String javaPath;

    public boolean installed = false;
    public long createdAt = System.currentTimeMillis();

    public Instance() {}

    public Instance(String name, String mcVersion, ModLoaderType loader, String loaderVersion) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.mcVersion = mcVersion;
        this.modLoader = loader;
        this.modLoaderVersion = loaderVersion;
    }
}
