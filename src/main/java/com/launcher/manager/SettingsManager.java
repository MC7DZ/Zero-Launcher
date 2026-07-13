package com.launcher.manager;

import com.launcher.model.LauncherSettings;
import com.launcher.util.JsonUtil;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsManager {
    private static SettingsManager instance;
    private LauncherSettings settings;
    private final Path settingsFile;

    /** Bump this (and add a case in migrateDefaults()) any time a built-in default value
     *  in LauncherSettings is changed. Existing users who never touched that setting will
     *  have it silently updated to the new default the next time the launcher starts; users
     *  who customized it keep their own value. */
    private static final int CURRENT_DEFAULTS_VERSION = 2;

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
        migrateDefaults();
    }

    /** Carries settings that are still on an old built-in default forward to the current
     *  default, one version step at a time, then records that the migration ran so it never
     *  re-applies (and never overwrites a value the user deliberately changed). */
    private void migrateDefaults() {
        boolean changed = false;

        if (settings.defaultsVersion < 2) {
            // v1 -> v2: default accent color changed from emerald green to cyan.
            if ("#10b981".equalsIgnoreCase(settings.accentColor)) {
                settings.accentColor = "#00cccc";
            }
        }

        if (settings.defaultsVersion < CURRENT_DEFAULTS_VERSION) {
            settings.defaultsVersion = CURRENT_DEFAULTS_VERSION;
            changed = true;
        }

        if (changed) {
            save();
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

    /** Resets all settings back to their defaults and persists them. */
    public void resetToDefaults() {
        settings = new LauncherSettings();
        save();
    }
}
