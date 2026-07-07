package com.launcher.manager;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class InstanceManager {
    private List<Instance> instances;

    public InstanceManager() {
        load();
        if (SettingsManager.getInstance().getSettings().scanOnStartup) {
            scanMinecraftFolder(); // Scan for existing installations on startup
        }
    }

    private void load() {
        Type type = new TypeToken<ArrayList<Instance>>(){}.getType();
        try {
            var path = LauncherPaths.instancesFile();
            String content = Files.exists(path) ? Files.readString(path) : "[]";
            instances = JsonUtil.GSON.fromJson(content, type);
        } catch (Exception e) {
            instances = new ArrayList<>();
        }
        if (instances == null) instances = new ArrayList<>();
    }

    public void save() {
        JsonUtil.writeFile(LauncherPaths.instancesFile(), instances);
    }

    public List<Instance> getInstances() {
        return instances;
    }

    public void add(Instance instance) {
        instances.add(instance);
        save();
    }

    public void update(Instance updatedInstance) {
        for (int i = 0; i < instances.size(); i++) {
            if (instances.get(i).id.equals(updatedInstance.id)) {
                instances.set(i, updatedInstance);
                save();
                return;
            }
        }
        // If not found, add it as a new instance
        add(updatedInstance);
    }

    public void remove(Instance instance) {
        boolean isScanned = false;
        try {
            if (instance.useCustomDirectory && instance.customDirectoryPath != null) {
                java.nio.file.Path path = java.nio.file.Path.of(instance.customDirectoryPath);
                if (path.equals(LauncherPaths.getDefaultMinecraftPath())) {
                    isScanned = true;
                }
            }
        } catch (Exception ignored) {}

        if (isScanned) {
            // Scanned instances are marked hidden so scanner does not re-add them
            for (Instance i : instances) {
                if (i.id.equals(instance.id)) {
                    i.hidden = true;
                    save();
                    return;
                }
            }
        } else {
            instances.removeIf(i -> i.id.equals(instance.id));
            save();
        }
    }

    public Optional<Instance> findById(String id) {
        return instances.stream().filter(i -> i.id.equals(id)).findFirst();
    }

    /** Resolves the actual game directory on disk, honoring the per-instance default/custom choice. */
    public Path resolveGameDir(Instance instance) {
        if (instance.useCustomDirectory && instance.customDirectoryPath != null && !instance.customDirectoryPath.isBlank()) {
            return Path.of(instance.customDirectoryPath);
        }
        return LauncherPaths.defaultInstanceDir(instance.id);
    }

    private void scanMinecraftFolder() {
        Path minecraftPath = LauncherPaths.getDefaultMinecraftPath(); // Using the public static method from LauncherPaths
        Path versionsPath = minecraftPath.resolve("versions");

        if (!Files.exists(versionsPath) || !Files.isDirectory(versionsPath)) {
            return; // No versions folder found
        }

        try (Stream<Path> versionDirs = Files.list(versionsPath)) {
            versionDirs.filter(Files::isDirectory)
                    .forEach(versionDir -> {
                        String versionId = versionDir.getFileName().toString();
                        Path versionJsonFile = versionDir.resolve(versionId + ".json");

                        if (Files.exists(versionJsonFile)) {
                            try {
                                String jsonContent = Files.readString(versionJsonFile);
                                JsonObject versionJson = JsonUtil.parse(jsonContent).getAsJsonObject();
                                String mcVersion = versionJson.has("id") ? versionJson.get("id").getAsString() : versionId;

                                // Check if an instance for this version and path already exists
                                boolean exists = instances.stream().anyMatch(
                                        inst -> inst.mcVersion.equals(mcVersion) &&
                                                inst.useCustomDirectory &&
                                                Path.of(inst.customDirectoryPath).equals(minecraftPath)
                                );

                                if (!exists) {
                                    Instance scannedInstance = new Instance(
                                            "Minecraft " + mcVersion, // Default name
                                            mcVersion,
                                            ModLoaderType.VANILLA, // Assume vanilla for scanned instances
                                            null
                                    );
                                    scannedInstance.id = UUID.randomUUID().toString(); // Assign a new ID
                                    scannedInstance.useCustomDirectory = true;
                                    scannedInstance.customDirectoryPath = minecraftPath.toAbsolutePath().toString();
                                    scannedInstance.installed = true; // Mark as installed since it exists
                                    instances.add(scannedInstance);
                                }
                            } catch (IOException e) {
                                System.err.println("Error reading version JSON for " + versionId + ": " + e.getMessage());
                            }
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error scanning .minecraft versions folder: " + e.getMessage());
        }
        save(); // Save any newly added instances
    }

    public Path resolveNativesDir(Instance instance) {
        return resolveGameDir(instance).resolve("natives");
    }
}