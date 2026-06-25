package com.launcher.manager;

import com.google.gson.reflect.TypeToken;
import com.launcher.model.Instance;
import com.launcher.util.JsonUtil;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InstanceManager {
    private List<Instance> instances;

    public InstanceManager() {
        load();
    }

    private void load() {
        Type type = new TypeToken<ArrayList<Instance>>(){}.getType();
        try {
            var path = LauncherPaths.instancesFile();
            String content = java.nio.file.Files.exists(path) ? java.nio.file.Files.readString(path) : "[]";
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

    public void remove(Instance instance) {
        instances.removeIf(i -> i.id.equals(instance.id));
        save();
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
}
