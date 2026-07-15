package com.launcher.model;

/**
 * Represents a single mod JAR file found in an instance's mods/ directory.
 * Used by the Mods tab to display mod information and update status.
 */
public class ModEntry {
    public String fileName;       // e.g. "sodium-0.5.8.jar" (or "sodium-0.5.8.jar.disabled" if disabled)
    public String filePath;       // absolute path
    public long fileSize;         // bytes
    public String sha1Hash;       // computed SHA-1 hash
    public String modrinthId;     // Modrinth project ID if identified, null otherwise
    public String projectName;    // Human-readable project name from Modrinth
    public String currentVersion; // current version string
    public String latestVersion;  // latest available version string
    public String updateUrl;      // download URL for the update, null if up-to-date
    public String updateFileName; // filename of the updated mod jar
    public String iconUrl;        // Modrinth project icon URL, null if unknown
    public String status;         // "Checking…", "Up to date", "Update available", "Unknown"
    public boolean disabled;      // true if the jar is currently suffixed ".disabled" on disk
    public java.util.List<String> loaders; // mod loaders this jar's Modrinth version supports (e.g. "fabric", "forge"), null if unidentified
    public java.util.List<String> gameVersions; // Minecraft versions this jar's Modrinth version supports, null if unidentified

    /**
     * Returns true if this mod is known to be INCOMPATIBLE with the given loader and/or
     * Minecraft version — i.e. we successfully identified the mod's supported loaders/versions
     * and neither list contains the instance's loader/version. Returns false (not flagged) when
     * the mod couldn't be identified, since we can't know either way.
     */
    public boolean isIncompatibleWith(String loaderName, String mcVersion) {
        boolean loaderMismatch = loaders != null && !loaders.isEmpty()
                && loaderName != null && !loaderName.isBlank()
                && loaders.stream().noneMatch(l -> l.equalsIgnoreCase(loaderName));
        boolean versionMismatch = gameVersions != null && !gameVersions.isEmpty()
                && mcVersion != null && !mcVersion.isBlank()
                && gameVersions.stream().noneMatch(v -> v.equalsIgnoreCase(mcVersion));
        return loaderMismatch || versionMismatch;
    }

    public ModEntry() {
        this.status = "Checking…";
    }

    public ModEntry(String fileName, String filePath, long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.status = "Checking…";
        this.disabled = isDisabledFileName(fileName);
    }

    /** Returns true if the given on-disk file name marks a disabled mod. */
    public static boolean isDisabledFileName(String name) {
        return name != null && name.toLowerCase().endsWith(".jar.disabled");
    }

    /** Returns a human-readable file size string. */
    public String formattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
    }

    /** Returns the display name: project name if known, otherwise the file name. */
    public String displayName() {
        return (projectName != null && !projectName.isBlank()) ? projectName : displayFileName();
    }

    /** Returns the on-disk file name with any trailing ".disabled" suffix stripped, for display. */
    public String displayFileName() {
        if (fileName == null) return null;
        return disabled && fileName.toLowerCase().endsWith(".disabled")
                ? fileName.substring(0, fileName.length() - ".disabled".length())
                : fileName;
    }
}
