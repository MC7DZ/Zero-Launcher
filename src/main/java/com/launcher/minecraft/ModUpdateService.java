package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launcher.model.ModEntry;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Service for identifying and updating mods via the Modrinth API.
 * <p>
 * Uses SHA-1 hashes of mod JARs to identify them on Modrinth, then checks
 * for newer compatible versions and can download replacements.
 */
public class ModUpdateService {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";

    /**
     * Scans a mods directory and returns a list of ModEntry objects for each .jar file.
     */
    public List<ModEntry> scanModsDir(Path modsDir) throws IOException {
        List<ModEntry> entries = new ArrayList<>();
        if (!Files.exists(modsDir) || !Files.isDirectory(modsDir)) {
            return entries;
        }
        try (Stream<Path> files = Files.list(modsDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         ModEntry entry = new ModEntry(
                                 p.getFileName().toString(),
                                 p.toAbsolutePath().toString(),
                                 Files.size(p)
                         );
                         entry.sha1Hash = computeSha1(p);
                         entries.add(entry);
                     } catch (Exception e) {
                         // Skip files we can't read
                     }
                 });
        }
        return entries;
    }

    /**
     * Batch-identifies mods by their SHA-1 hashes using the Modrinth API.
     * Fills in modrinthId, projectName, and currentVersion for identified mods.
     */
    public void identifyMods(List<ModEntry> mods, Consumer<String> log) {
        if (mods.isEmpty()) return;

        try {
            // Build the request body: { "hashes": ["hash1", "hash2", ...], "algorithm": "sha1" }
            JsonObject body = new JsonObject();
            JsonArray hashes = new JsonArray();
            for (ModEntry mod : mods) {
                if (mod.sha1Hash != null) hashes.add(mod.sha1Hash);
            }
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha1");

            String response = HttpUtil.postJson(MODRINTH_API + "/version_files", body.toString());
            JsonObject result = JsonUtil.parse(response).getAsJsonObject();

            // Response is a map of hash -> version object
            for (ModEntry mod : mods) {
                if (mod.sha1Hash == null) continue;
                if (result.has(mod.sha1Hash)) {
                    JsonObject versionObj = result.getAsJsonObject(mod.sha1Hash);
                    mod.modrinthId = versionObj.has("project_id")
                            ? versionObj.get("project_id").getAsString() : null;
                    mod.currentVersion = versionObj.has("version_number")
                            ? versionObj.get("version_number").getAsString() : null;

                    // Try to get project name
                    if (versionObj.has("name")) {
                        // The version name often includes the mod name
                        String vName = versionObj.get("name").getAsString();
                        // Use it as project name if we don't have one
                        if (mod.projectName == null) mod.projectName = vName;
                    }
                }
            }

            // Fetch project names for identified mods
            fetchProjectNames(mods, log);

        } catch (Exception e) {
            log.accept("Failed to identify mods via Modrinth: " + e.getMessage());
            for (ModEntry mod : mods) {
                if (mod.status.equals("Checking…")) mod.status = "Unknown";
            }
        }
    }

    /**
     * Fetches human-readable project names for identified mods.
     */
    private void fetchProjectNames(List<ModEntry> mods, Consumer<String> log) {
        // Collect unique project IDs
        List<String> projectIds = mods.stream()
                .filter(m -> m.modrinthId != null)
                .map(m -> m.modrinthId)
                .distinct()
                .toList();

        if (projectIds.isEmpty()) return;

        try {
            // Use the GET /projects endpoint with IDs
            StringBuilder idsParam = new StringBuilder("[");
            for (int i = 0; i < projectIds.size(); i++) {
                if (i > 0) idsParam.append(",");
                idsParam.append("\"").append(projectIds.get(i)).append("\"");
            }
            idsParam.append("]");

            String response = HttpUtil.getString(
                    MODRINTH_API + "/projects?ids=" + java.net.URLEncoder.encode(idsParam.toString(), "UTF-8"));
            JsonArray projects = JsonUtil.parse(response).getAsJsonArray();

            // Build maps of project ID -> title and project ID -> icon_url
            java.util.HashMap<String, String> nameMap = new java.util.HashMap<>();
            java.util.HashMap<String, String> iconMap = new java.util.HashMap<>();
            for (JsonElement el : projects) {
                JsonObject proj = el.getAsJsonObject();
                String id = proj.get("id").getAsString();
                String title = proj.has("title") ? proj.get("title").getAsString() : null;
                String icon = proj.has("icon_url") && !proj.get("icon_url").isJsonNull()
                        ? proj.get("icon_url").getAsString() : null;
                if (title != null) nameMap.put(id, title);
                if (icon != null) iconMap.put(id, icon);
            }

            // Apply names and icons
            for (ModEntry mod : mods) {
                if (mod.modrinthId != null) {
                    if (nameMap.containsKey(mod.modrinthId)) {
                        mod.projectName = nameMap.get(mod.modrinthId);
                    }
                    if (iconMap.containsKey(mod.modrinthId)) {
                        mod.iconUrl = iconMap.get(mod.modrinthId);
                    }
                }
            }
        } catch (Exception e) {
            log.accept("Failed to fetch project names: " + e.getMessage());
        }
    }

    /**
     * Checks for updates for identified mods using the Modrinth API.
     * Fills in latestVersion, updateUrl, and status.
     *
     * @param mods      the mod entries (must have sha1Hash and modrinthId filled)
     * @param mcVersion the target Minecraft version (e.g. "1.20.1")
     * @param loader    the mod loader name in Modrinth format (e.g. "fabric", "forge", "quilt", "neoforge")
     * @param log       progress callback
     */
    public void checkUpdates(List<ModEntry> mods, String mcVersion, String loader, Consumer<String> log) {
        // Filter to mods that were identified on Modrinth
        List<ModEntry> identified = mods.stream()
                .filter(m -> m.sha1Hash != null && m.modrinthId != null)
                .toList();

        // Mark unidentified mods
        for (ModEntry mod : mods) {
            if (mod.modrinthId == null) {
                mod.status = "Unknown";
            }
        }

        if (identified.isEmpty()) {
            log.accept("No mods identified on Modrinth — cannot check for updates.");
            return;
        }

        try {
            // Build the request body for version_files/update
            JsonObject body = new JsonObject();
            JsonArray hashes = new JsonArray();
            for (ModEntry mod : identified) {
                hashes.add(mod.sha1Hash);
            }
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha1");

            // Loaders filter
            JsonArray loaders = new JsonArray();
            if (loader != null && !loader.isBlank()) loaders.add(loader.toLowerCase());
            body.add("loaders", loaders);

            // Game versions filter
            JsonArray gameVersions = new JsonArray();
            if (mcVersion != null && !mcVersion.isBlank()) gameVersions.add(mcVersion);
            body.add("game_versions", gameVersions);

            String response = HttpUtil.postJson(MODRINTH_API + "/version_files/update", body.toString());
            JsonObject result = JsonUtil.parse(response).getAsJsonObject();

            int updatesAvailable = 0;
            for (ModEntry mod : identified) {
                if (result.has(mod.sha1Hash)) {
                    JsonObject newVersion = result.getAsJsonObject(mod.sha1Hash);
                    String newVersionNumber = newVersion.has("version_number")
                            ? newVersion.get("version_number").getAsString() : null;

                    // Check if the new version hash is different from current
                    JsonArray files = newVersion.has("files") ? newVersion.getAsJsonArray("files") : null;
                    String newHash = null;
                    String downloadUrl = null;
                    String newFileName = null;
                    if (files != null) {
                        for (JsonElement fileEl : files) {
                            JsonObject fileObj = fileEl.getAsJsonObject();
                            // Prefer the primary file
                            boolean primary = fileObj.has("primary") && fileObj.get("primary").getAsBoolean();
                            if (primary || downloadUrl == null) {
                                downloadUrl = fileObj.has("url") ? fileObj.get("url").getAsString() : null;
                                newFileName = fileObj.has("filename") ? fileObj.get("filename").getAsString() : null;
                                JsonObject hashesObj = fileObj.has("hashes") ? fileObj.getAsJsonObject("hashes") : null;
                                if (hashesObj != null && hashesObj.has("sha1")) {
                                    newHash = hashesObj.get("sha1").getAsString();
                                }
                            }
                        }
                    }

                    if (newHash != null && !newHash.equals(mod.sha1Hash)) {
                        mod.latestVersion = newVersionNumber;
                        mod.updateUrl = downloadUrl;
                        mod.updateFileName = newFileName;
                        mod.status = "Update available";
                        updatesAvailable++;
                    } else {
                        mod.status = "Up to date";
                    }
                } else {
                    // No update found for this version/loader combo
                    mod.status = "Up to date";
                }
            }

            log.accept("Update check complete: " + updatesAvailable + " update(s) available out of " + identified.size() + " identified mods.");

        } catch (Exception e) {
            log.accept("Failed to check for updates: " + e.getMessage());
            for (ModEntry mod : identified) {
                if (mod.status.equals("Checking…")) mod.status = "Error";
            }
        }
    }

    /**
     * Downloads the updated version of a mod and replaces the old file.
     *
     * @param mod     the mod entry with updateUrl set
     * @param modsDir the mods directory
     * @param log     progress callback
     * @return true if the update was successful
     */
    public boolean downloadUpdate(ModEntry mod, Path modsDir, Consumer<String> log) {
        if (mod.updateUrl == null || mod.updateUrl.isBlank()) {
            log.accept("No update URL for " + mod.displayName());
            return false;
        }

        try {
            String newFileName = (mod.updateFileName != null && !mod.updateFileName.isBlank())
                    ? mod.updateFileName : mod.fileName;
            Path newFile = modsDir.resolve(newFileName);

            log.accept("Downloading update for " + mod.displayName() + "…");
            HttpUtil.downloadToFile(mod.updateUrl, newFile);

            // Delete old file if the name changed
            Path oldFile = Path.of(mod.filePath);
            if (!oldFile.equals(newFile) && Files.exists(oldFile)) {
                Files.delete(oldFile);
                log.accept("Removed old version: " + mod.fileName);
            }

            // Update the mod entry
            mod.fileName = newFileName;
            mod.filePath = newFile.toAbsolutePath().toString();
            mod.fileSize = Files.size(newFile);
            mod.sha1Hash = computeSha1(newFile);
            mod.currentVersion = mod.latestVersion;
            mod.latestVersion = null;
            mod.updateUrl = null;
            mod.updateFileName = null;
            mod.status = "Up to date";

            log.accept("Updated " + mod.displayName() + " to " + mod.currentVersion);
            return true;

        } catch (Exception e) {
            log.accept("Failed to update " + mod.displayName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Computes the SHA-1 hash of a file, returned as a lowercase hex string.
     */
    public static String computeSha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (FileInputStream fis = new FileInputStream(file.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm not available", e);
        }
    }
}
