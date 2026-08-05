package com.launcher.manager;

import com.google.gson.reflect.TypeToken;
import com.launcher.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists identified mods' Modrinth metadata (project name, icon URL, description) to a
 * small JSON file in the launcher's shared cache folder (".zerolauncher/cache/mod_metadata.json"
 * on Linux, equivalent app-data cache folder elsewhere — see {@link LauncherPaths#sharedCache()}).
 * <p>
 * This lets the Mods tab show a mod's name, icon, and description immediately on the next scan
 * without re-hitting the Modrinth API for mods it has already identified before.
 */
public class ModMetadataCache {

    /** One cached entry per Modrinth project ID. */
    public static class Entry {
        public String projectName;
        public String iconUrl;
        public String description;

        public Entry() {}

        public Entry(String projectName, String iconUrl, String description) {
            this.projectName = projectName;
            this.iconUrl = iconUrl;
            this.description = description;
        }
    }

    private static ModMetadataCache instance;

    private final Path cacheFile;
    private Map<String, Entry> entries;

    private ModMetadataCache() {
        this.cacheFile = LauncherPaths.sharedCache().resolve("mod_metadata.json");
        load();
    }

    public static synchronized ModMetadataCache getInstance() {
        if (instance == null) {
            instance = new ModMetadataCache();
        }
        return instance;
    }

    private void load() {
        entries = new HashMap<>();
        try {
            if (!Files.exists(cacheFile)) return;
            String content = Files.readString(cacheFile, StandardCharsets.UTF_8);
            if (content.isBlank()) return;
            java.lang.reflect.Type type = new TypeToken<HashMap<String, Entry>>() {}.getType();
            Map<String, Entry> parsed = JsonUtil.GSON.fromJson(content, type);
            if (parsed != null) entries.putAll(parsed);
        } catch (Exception ignored) {
            // Corrupt/missing cache file — just start fresh, this is best-effort.
        }
    }

    private synchronized void save() {
        try {
            JsonUtil.writeFile(cacheFile, entries);
        } catch (Exception ignored) {
            // Cache is best-effort; failures here shouldn't break mod identification.
        }
    }

    /** Returns the cached entry for a Modrinth project ID, or null if not cached. */
    public synchronized Entry get(String projectId) {
        if (projectId == null) return null;
        return entries.get(projectId);
    }

    /** Stores/updates the cached entry for a Modrinth project ID and persists to disk. */
    public synchronized void put(String projectId, String projectName, String iconUrl, String description) {
        if (projectId == null) return;
        entries.put(projectId, new Entry(projectName, iconUrl, description));
        save();
    }
}
