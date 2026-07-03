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

    /**
     * Searches Modrinth for mods. Kept for backward compatibility; delegates to
     * {@link #searchProjects(String, String, String, String)}.
     */
    public JsonArray searchMods(String query, String loader, String gameVersion) throws Exception {
        return searchProjects(query, "mod", loader, gameVersion);
    }

    /**
     * Searches Modrinth for projects of a given type — used by the Discover section
     * to browse both mods and resource packs.
     *
     * @param query       free-text search query
     * @param projectType "mod" or "resourcepack"
     * @param loader      mod loader (e.g. "fabric", "forge"); ignored for resource packs
     * @param gameVersion target Minecraft version, or null/blank for no filter
     */
    public JsonArray searchProjects(String query, String projectType, String loader, String gameVersion) throws Exception {
        return searchProjectsPage(query, projectType, loader, gameVersion, 0, 30).getAsJsonArray("hits");
    }

    /** Default page size used by the Discover tab's pagination controls. */
    public static final int DISCOVER_PAGE_SIZE = 20;

    /**
     * Same as {@link #searchProjects(String, String, String, String)}, but supports paging
     * through results (used by the Discover tab's "Page N" controls) and returns the full
     * Modrinth search response object — including {@code hits}, {@code total_hits}, and
     * {@code offset} — instead of just the hits array.
     *
     * @param offset zero-based index of the first result to return
     * @param limit  maximum number of results to return (Modrinth allows up to 100)
     */
    public JsonObject searchProjectsPage(String query, String projectType, String loader, String gameVersion,
                                          int offset, int limit) throws Exception {
        StringBuilder url = new StringBuilder(MODRINTH_API + "/search?");
        url.append("query=").append(java.net.URLEncoder.encode(query == null ? "" : query, "UTF-8"));

        JsonArray facets = new JsonArray();

        JsonArray projectTypeFacet = new JsonArray();
        projectTypeFacet.add("project_type:" + (projectType == null ? "mod" : projectType));
        facets.add(projectTypeFacet);

        // Loader filtering only makes sense for mods — resource packs aren't loader-specific.
        if ("mod".equals(projectType) && loader != null && !loader.isBlank()) {
            JsonArray loaderFacet = new JsonArray();
            loaderFacet.add("categories:" + loader.toLowerCase());
            facets.add(loaderFacet);
        }

        // Neither mods nor resource packs are filtered by game version anymore — every result
        // is shown, and cards flag whether the listed versions actually cover the instance's
        // Minecraft version instead of Modrinth silently hiding mismatches from the list.
        // Loader compatibility is still enforced for mods, since a Fabric jar simply won't
        // load under Forge (unlike a version mismatch, which is often survivable).

        url.append("&facets=").append(java.net.URLEncoder.encode(facets.toString(), "UTF-8"));
        url.append("&limit=").append(Math.max(1, Math.min(limit, 100)));
        url.append("&offset=").append(Math.max(0, offset));

        String response = HttpUtil.getString(url.toString());
        return JsonUtil.parse(response).getAsJsonObject();
    }

    /**
     * Fetches versions for a project and finds the best matching primary file.
     * Kept for backward compatibility; delegates to the project-type-aware overload.
     */
    public String getDownloadUrlForProject(String projectId, String loader, String gameVersion) throws Exception {
        return getDownloadUrlForProject(projectId, "mod", loader, gameVersion);
    }

    /**
     * Fetches versions for a project and finds the best matching primary file,
     * for either mods or resource packs.
     */
    public String getDownloadUrlForProject(String projectId, String projectType, String loader, String gameVersion) throws Exception {
        StringBuilder url = new StringBuilder(MODRINTH_API + "/project/" + projectId + "/version?");
        if ("mod".equals(projectType) && loader != null && !loader.isBlank()) {
            url.append("loaders=").append(java.net.URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]", "UTF-8"));
        }
        // Game version is no longer used as a hard filter here — see searchProjectsPage for why.
        // This just fetches every version so the UI can pick from (and flag) the full list.

        String response = HttpUtil.getString(url.toString());
        JsonArray versions = JsonUtil.parse(response).getAsJsonArray();
        if (versions.isEmpty()) return null;

        // Pick the newest version (first in array usually)
        JsonObject bestVersion = versions.get(0).getAsJsonObject();
        JsonArray files = bestVersion.getAsJsonArray("files");

        for (JsonElement fileEl : files) {
            JsonObject fileObj = fileEl.getAsJsonObject();
            if (fileObj.has("primary") && fileObj.get("primary").getAsBoolean()) {
                return fileObj.get("url").getAsString();
            }
        }

        if (!files.isEmpty()) {
            return files.get(0).getAsJsonObject().get("url").getAsString();
        }

        return null;
    }

    /**
     * Lists every available version of a project compatible with the given loader/game version,
     * newest first — used by the Discover tab's "Version" picker so the user can choose exactly
     * which release to install instead of always getting the newest matching one.
     */
    public JsonArray listVersions(String projectId, String projectType, String loader, String gameVersion) throws Exception {
        StringBuilder url = new StringBuilder(MODRINTH_API + "/project/" + projectId + "/version?");
        if ("mod".equals(projectType) && loader != null && !loader.isBlank()) {
            url.append("loaders=").append(java.net.URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]", "UTF-8"));
        }
        // Game version is no longer used as a hard filter — every version is listed, and the
        // Discover card flags whether the instance's version is actually among the ones it
        // supports, rather than Modrinth silently omitting mismatched versions from the list.
        String response = HttpUtil.getString(url.toString());
        return JsonUtil.parse(response).getAsJsonArray();
    }

    /** Finds the primary downloadable file's URL and file name within a single Modrinth version object. */
    public static String[] primaryFileOf(JsonObject versionObj) {
        JsonArray files = versionObj.getAsJsonArray("files");
        if (files == null || files.isEmpty()) return null;
        JsonObject chosen = null;
        for (JsonElement fileEl : files) {
            JsonObject fileObj = fileEl.getAsJsonObject();
            if (fileObj.has("primary") && fileObj.get("primary").getAsBoolean()) {
                chosen = fileObj;
                break;
            }
        }
        if (chosen == null) chosen = files.get(0).getAsJsonObject();
        return new String[] { chosen.get("url").getAsString(), chosen.get("filename").getAsString() };
    }

    /**
     * Finds every REQUIRED Modrinth dependency referenced by the given mods' matching versions,
     * skipping any dependency project that's already among {@code mods}. Used by the Mods tab's
     * "Install Dependencies" button.
     * <p>
     * For each identified mod, the version whose {@code version_number} matches the mod's
     * {@link ModEntry#currentVersion} is inspected for its {@code dependencies} array (falling
     * back to the newest listed version if no exact match is found); entries with
     * {@code dependency_type == "required"} are collected as candidates to install.
     *
     * @return an ordered map of dependency project ID -&gt; human-readable project title
     */
    public java.util.LinkedHashMap<String, String> findMissingRequiredDependencies(
            List<ModEntry> mods, String loader, String gameVersion, Consumer<String> log) {

        java.util.Set<String> installedIds = new java.util.HashSet<>();
        for (ModEntry m : mods) if (m.modrinthId != null) installedIds.add(m.modrinthId);

        java.util.LinkedHashSet<String> missingIds = new java.util.LinkedHashSet<>();

        for (ModEntry mod : mods) {
            if (mod.modrinthId == null) continue;
            try {
                JsonArray versions = listVersions(mod.modrinthId, "mod", loader, gameVersion);
                JsonObject match = null;
                for (JsonElement vEl : versions) {
                    JsonObject v = vEl.getAsJsonObject();
                    String vn = v.has("version_number") && !v.get("version_number").isJsonNull()
                            ? v.get("version_number").getAsString() : null;
                    if (mod.currentVersion != null && mod.currentVersion.equals(vn)) {
                        match = v;
                        break;
                    }
                }
                if (match == null && !versions.isEmpty()) match = versions.get(0).getAsJsonObject();
                if (match == null || !match.has("dependencies") || !match.get("dependencies").isJsonArray()) continue;

                for (JsonElement depEl : match.getAsJsonArray("dependencies")) {
                    JsonObject dep = depEl.getAsJsonObject();
                    if (!dep.has("dependency_type") || dep.get("dependency_type").isJsonNull()) continue;
                    if (!"required".equals(dep.get("dependency_type").getAsString())) continue;
                    if (!dep.has("project_id") || dep.get("project_id").isJsonNull()) continue;
                    String depId = dep.get("project_id").getAsString();
                    if (installedIds.contains(depId)) continue;
                    missingIds.add(depId);
                }
            } catch (Exception e) {
                log.accept("Could not check dependencies for " + mod.displayName() + ": " + e.getMessage());
            }
        }

        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (missingIds.isEmpty()) return result;

        // Resolve human-readable names for the missing dependencies in one batch call.
        try {
            StringBuilder idsParam = new StringBuilder("[");
            boolean first = true;
            for (String id : missingIds) {
                if (!first) idsParam.append(",");
                idsParam.append("\"").append(id).append("\"");
                first = false;
            }
            idsParam.append("]");
            String response = HttpUtil.getString(
                    MODRINTH_API + "/projects?ids=" + java.net.URLEncoder.encode(idsParam.toString(), "UTF-8"));
            JsonArray projects = JsonUtil.parse(response).getAsJsonArray();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (JsonElement el : projects) {
                JsonObject p = el.getAsJsonObject();
                String id = p.get("id").getAsString();
                String title = p.has("title") && !p.get("title").isJsonNull() ? p.get("title").getAsString() : id;
                names.put(id, title);
            }
            for (String id : missingIds) result.put(id, names.getOrDefault(id, id));
        } catch (Exception e) {
            log.accept("Could not fetch dependency names: " + e.getMessage());
            for (String id : missingIds) result.put(id, id);
        }
        return result;
    }
}
