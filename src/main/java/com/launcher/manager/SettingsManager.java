package com.launcher.manager;

import com.launcher.model.LauncherSettings;
import com.launcher.util.JsonUtil;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsManager {
    private static SettingsManager instance;
    private LauncherSettings settings;
    private final Path settingsFile;

    public SettingsManager() {
        this.settingsFile = LauncherPaths.launcherRoot().resolve("settings.json");
        load();
    }

    public static synchronized SettingsManager getInstance() {
        if (instance == null) {
            instance = new SettingsManager();
        }
        return instance;
    }

    public void load() {
        try {
            if (Files.exists(settingsFile)) {
                String content = Files.readString(settingsFile);
                settings = JsonUtil.GSON.fromJson(content, LauncherSettings.class);
            }
        } catch (Exception e) {
            settings = new LauncherSettings();
        }
        if (settings == null) {
            settings = new LauncherSettings();
        }
    }

    public void save() {
        try {
            JsonUtil.writeFile(settingsFile, settings);
        } catch (Exception e) {
            System.err.println("Error saving settings: " + e.getMessage());
        }
    }

    public LauncherSettings getSettings() {
        return settings;
    }
}
