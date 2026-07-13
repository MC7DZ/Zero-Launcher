package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launcher.manager.LauncherPaths;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads/resolves a (possibly mod-loader) version json into something launchable.
 * Handles the "inheritsFrom" chain shared by Fabric profiles and Forge-generated version jsons,
 * so the same code path installs vanilla, Fabric and Forge.
 */
public class GameInstaller {

    private final VersionManifestService manifest = new VersionManifestService();

    /** Follows inheritsFrom (if any) and merges libraries/arguments/mainClass/assetIndex with the parent. */
    public JsonObject resolveInheritance(JsonObject versionJson, Consumer<String> log) throws IOException, InterruptedException {
        return resolveInheritance(versionJson, null, log);
    }

    /** Same as {@link #resolveInheritance(JsonObject, Consumer)} but also looks for/saves the parent
     *  version JSON inside the instance's own game directory when one is provided (custom-path instances). */
    public JsonObject resolveInheritance(JsonObject versionJson, Path gameDir, Consumer<String> log) throws IOException, InterruptedException {
        return resolveInheritance(versionJson, gameDir, log, new java.util.HashSet<>());
    }

    private JsonObject resolveInheritance(JsonObject versionJson, Path gameDir, Consumer<String> log,
                                           java.util.Set<String> seenIds) throws IOException, InterruptedException {
        if (!versionJson.has("inheritsFrom")) {
            return versionJson;
        }
        String selfId = versionJson.has("id") ? versionJson.get("id").getAsString() : null;
        if (selfId != null && !seenIds.add(selfId)) {
            // We've already visited this version id in this chain - "inheritsFrom" forms a cycle.
            throw new IOException("Version '" + selfId + "' has a circular \"inheritsFrom\" chain "
                    + "(it ends up inheriting from itself). HOW TO FIX: delete/reinstall this version's "
                    + "folder under \"versions/" + selfId + "\" (and, for custom-path instances, the "
                    + "instance's own \"<instance folder>.json\") so the launcher regenerates a clean "
                    + "version JSON instead of a corrupted one that points back to itself.");
        }
        String parentId = versionJson.get("inheritsFrom").getAsString();
        log.accept("Resolving parent version " + parentId + " ...");
        
        JsonObject parent = null;
        Path localJsonPath = LauncherPaths.findLocalVersionJson(parentId, gameDir);
        if (localJsonPath != null) {
            log.accept("Found local parent version JSON at " + localJsonPath);
            try {
                String jsonContent = Files.readString(localJsonPath);
                parent = JsonUtil.parse(jsonContent).getAsJsonObject();
            } catch (Exception e) {
                log.accept("Failed to read local parent version JSON: " + e.getMessage() + ", falling back to network");
            }
        }

        if (parent == null) {
            var urls = manifest.fetchVersionUrls();
            String parentUrl = urls.get(parentId);
            if (parentUrl == null) {
                throw new IOException("Could not find parent version '" + parentId + "' in Mojang's version "
                        + "manifest or local files. HOW TO FIX: check your internet connection, then try "
                        + "reinstalling this version/modpack so the launcher can fetch a fresh copy of '"
                        + parentId + "'.");
            }
            parent = manifest.fetchVersionJson(parentUrl);
            
            try {
                Path savePath = LauncherPaths.versionsDir(gameDir).resolve(parentId).resolve(parentId + ".json");
                Files.createDirectories(savePath.getParent());
                Files.writeString(savePath, JsonUtil.GSON.toJson(parent), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.accept("Failed to save parent version JSON locally: " + e.getMessage());
            }
        }
        
        parent = resolveInheritance(parent, gameDir, log, seenIds); // parent might itself inherit (rare, but be safe)
        return merge(parent, versionJson);
    }

    private JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject result = parent.deepCopy();

        // Store the original/parent version ID that actually contains the client jar downloads
        if (parent.has("clientJarId")) {
            result.add("clientJarId", parent.get("clientJarId"));
        } else if (parent.has("id")) {
            result.add("clientJarId", parent.get("id"));
        }

        // Child fully overrides simple scalar fields when present.
        for (String field : new String[]{"mainClass", "id", "assetIndex", "assets", "type", "downloads"}) {
            if (child.has(field)) result.add(field, child.get(field));
        }

        // Libraries: append child's on top of parent's, then de-duplicate by Maven
        // coordinate (group:artifact[:classifier], ignoring version). Without this, a
        // library that both the vanilla game and a mod loader depend on - at different
        // versions - ends up on the classpath twice (e.g. vanilla's asm-9.6.jar next to
        // Fabric Loader's asm-9.10.1.jar), which crashes at startup with
        // "duplicate ASM classes found on classpath". The child's (loader's) version wins
        // since it was declared most recently and typically needs the newer one.
        JsonArray libs = result.has("libraries") ? result.getAsJsonArray("libraries") : new JsonArray();
        if (child.has("libraries")) libs.addAll(child.getAsJsonArray("libraries"));
        result.add("libraries", dedupeLibraries(libs));

        // Arguments (modern 1.13+ shape): concatenate game/jvm arrays.
        if (parent.has("arguments") || child.has("arguments")) {
            JsonObject args = parent.has("arguments") ? parent.getAsJsonObject("arguments").deepCopy() : new JsonObject();
            if (child.has("arguments")) {
                JsonObject childArgs = child.getAsJsonObject("arguments");
                for (String key : new String[]{"game", "jvm"}) {
                    JsonArray combined = args.has(key) ? args.getAsJsonArray(key) : new JsonArray();
                    if (childArgs.has(key)) combined.addAll(childArgs.getAsJsonArray(key));
                    args.add(key, combined);
                }
            }
            result.add("arguments", args);
        }

        // Legacy pre-1.13 single-string argument format.
        if (child.has("minecraftArguments")) {
            result.add("minecraftArguments", child.get("minecraftArguments"));
        }

        result.remove("inheritsFrom");
        return result;
    }

    /** Keeps only the last-declared entry for each Maven coordinate (group:artifact[:classifier]),
     *  discarding earlier duplicates that differ only by version. Order of first appearance is
     *  preserved so the classpath ordering doesn't change unnecessarily. */
    private JsonArray dedupeLibraries(JsonArray libs) {
        java.util.LinkedHashMap<String, JsonElement> byKey = new java.util.LinkedHashMap<>();
        List<JsonElement> unkeyed = new ArrayList<>(); // entries we can't safely key (keep all of these)
        for (JsonElement el : libs) {
            if (!el.isJsonObject()) { unkeyed.add(el); continue; }
            String key = libraryDedupeKey(el.getAsJsonObject());
            if (key == null) {
                unkeyed.add(el);
            } else {
                byKey.put(key, el); // last write wins, but keeps original position
            }
        }
        JsonArray result = new JsonArray();
        for (JsonElement el : unkeyed) result.add(el);
        for (JsonElement el : byKey.values()) result.add(el);
        return result;
    }

    private String libraryDedupeKey(JsonObject lib) {
        if (!lib.has("name") || lib.get("name").isJsonNull()) return null;
        String[] parts = lib.get("name").getAsString().split(":");
        if (parts.length < 3) return null; // not a well-formed group:artifact:version coordinate
        String key = parts[0] + ":" + parts[1];
        if (parts.length > 3) key += ":" + parts[3]; // classifier (e.g. natives-linux) keeps its own slot
        return key;
    }

    public ResolvedVersion installAndResolve(JsonObject versionJson, Path gameDir, Path nativesDir, Consumer<String> log)
            throws IOException, InterruptedException {

        ResolvedVersion resolved = new ResolvedVersion();
        resolved.id = versionJson.has("id") ? versionJson.get("id").getAsString() : "unknown";
        if (!versionJson.has("mainClass") || versionJson.get("mainClass").isJsonNull()) {
            throw new IOException("Version JSON for '" + resolved.id
                    + "' is missing a \"mainClass\" entry. The downloaded/cached version file is incomplete or "
                    + "corrupted \u2014 try removing the version's folder and reinstalling.");
        }
        resolved.mainClass = versionJson.get("mainClass").getAsString();

        Files.createDirectories(nativesDir);

        // --- client jar ---
        String jarId = versionJson.has("clientJarId") ? versionJson.get("clientJarId").getAsString() : resolved.id;
        Path jarPath = LauncherPaths.versionsDir(gameDir).resolve(jarId).resolve(jarId + ".jar");
        if (!Files.exists(jarPath)) {
            // Check default .minecraft/versions
            Path localJar = LauncherPaths.getDefaultMinecraftPath().resolve("versions").resolve(jarId).resolve(jarId + ".jar");
            if (Files.exists(localJar)) {
                jarPath = localJar;
            } else if (versionJson.has("downloads") && versionJson.getAsJsonObject("downloads").has("client")
                    && versionJson.getAsJsonObject("downloads").getAsJsonObject("client").has("url")) {
                JsonObject clientDl = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
                String url = clientDl.get("url").getAsString();
                log.accept("Downloading client jar (" + jarId + ".jar) ...");
                HttpUtil.downloadToFile(url, jarPath);
            } else {
                log.accept("WARNING: No client jar found for " + jarId + " and no download URL available.");
            }
        }
        if (Files.exists(jarPath)) {
            resolved.classpath.add(jarPath);
        }

        // --- libraries ---
        if (versionJson.has("libraries")) {
            JsonArray libraries = versionJson.getAsJsonArray("libraries");
            List<JsonObject> toProcess = new ArrayList<>();
            for (var el : libraries) {
                JsonObject lib = el.getAsJsonObject();
                if (rulesAllow(lib)) toProcess.add(lib);
            }
            // Libraries were previously downloaded one at a time, which made installs (especially
            // for loaders with 50-100+ libraries) far slower than they needed to be. Downloading
            // them concurrently - the same approach already used for assets below - cuts install
            // time down drastically. resolved.classpath is a plain ArrayList, so additions to it
            // (and native-jar extraction, which touches shared files) are synchronized.
            List<Exception> libraryErrors = java.util.Collections.synchronizedList(new ArrayList<>());
            ExecutorService libraryPool = Executors.newFixedThreadPool(8);
            for (JsonObject lib : toProcess) {
                libraryPool.submit(() -> {
                    try {
                        processLibrary(lib, gameDir, resolved, nativesDir, log);
                    } catch (Exception e) {
                        libraryErrors.add(e);
                    }
                });
            }
            libraryPool.shutdown();
            try {
                libraryPool.awaitTermination(30, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (!libraryErrors.isEmpty()) {
                Exception first = libraryErrors.get(0);
                if (first instanceof IOException) throw (IOException) first;
                if (first instanceof InterruptedException) throw (InterruptedException) first;
                throw new IOException("One or more library downloads failed: " + first.getMessage(), first);
            }
            log.accept("Resolved " + toProcess.size() + " libraries.");
        }

        // --- asset index + assets ---
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            resolved.assetIndexId = assetIndex.get("id").getAsString();
            downloadAssets(assetIndex, gameDir, log);
        }

        // --- arguments ---
        if (versionJson.has("arguments")) {
            JsonObject args = versionJson.getAsJsonObject("arguments");
            collectStringArgs(args, "game", resolved.extraGameArgs);
            collectStringArgs(args, "jvm", resolved.extraJvmArgs);
        } else if (versionJson.has("minecraftArguments")) {
            resolved.legacyMinecraftArguments = versionJson.get("minecraftArguments").getAsString();
        }

        return resolved;
    }

    private void collectStringArgs(JsonObject args, String key, List<String> out) {
        if (!args.has(key)) return;
        for (var el : args.getAsJsonArray(key)) {
            // Conditional/object-form entries (feature-gated args like demo mode, custom resolution)
            // are intentionally skipped - they aren't required for a normal launch.
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
    }

    private void processLibrary(JsonObject lib, Path gameDir, ResolvedVersion resolved, Path nativesDir, Consumer<String> log)
            throws IOException, InterruptedException {
        // 1. Modern Mojang downloads structure
        if (lib.has("downloads")) {
            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                if (!artifact.has("path") || !artifact.has("url")) {
                    log.accept("WARNING: Skipping a library with an incomplete \"artifact\" entry (missing path/url).");
                } else {
                    String path = artifact.get("path").getAsString();
                    String url = artifact.get("url").getAsString();
                    Path dest = LauncherPaths.librariesDir(gameDir).resolve(path);
                    if (!Files.exists(dest) || Files.size(dest) == 0) {
                        HttpUtil.downloadToFile(url, dest);
                    }
                    synchronized (resolved.classpath) {
                        resolved.classpath.add(dest);
                    }
                }
            }

            if (downloads.has("classifiers")) {
                String classifierKey = nativeClassifierKeyForCurrentOs();
                JsonObject classifiers = downloads.getAsJsonObject(classifierKey);
                if (classifierKey != null && classifiers.has(classifierKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                    if (!nativeArtifact.has("path") || !nativeArtifact.has("url")) {
                        log.accept("WARNING: Skipping a native library with an incomplete classifier entry (missing path/url).");
                    } else {
                        String path = nativeArtifact.get("path").getAsString();
                        String url = nativeArtifact.get("url").getAsString();
                        Path dest = LauncherPaths.librariesDir(gameDir).resolve(path);
                        if (!Files.exists(dest) || Files.size(dest) == 0) {
                            HttpUtil.downloadToFile(url, dest);
                        }
                        extractNatives(dest, nativesDir);
                    }
                }
            }
            return;
        }

        // 2. Maven coordinate format (common for Fabric/Forge/local custom libraries)
        if (lib.has("name") && !lib.get("name").isJsonNull()) {
            String name = lib.get("name").getAsString();
            String path = mavenToPath(name);
            if (path == null) return;

            // Determine download URL (default to Mojang's libraries maven repository if not specified)
            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String downloadUrl = baseUrl + path;

            Path dest = LauncherPaths.librariesDir(gameDir).resolve(path);
            if (!Files.exists(dest) || Files.size(dest) == 0) {
                log.accept("Downloading library: " + name);
                try {
                    HttpUtil.downloadToFile(downloadUrl, dest);
                } catch (Exception e) {
                    log.accept("Failed to download library: " + name + " from " + downloadUrl + ". Error: " + e.getMessage());
                }
            }

            // Extract natives if it is a native library classifier
            String[] parts = name.split(":");
            if (parts.length > 3 && parts[3].startsWith("natives-")) {
                if (parts[3].equals(nativeClassifierKeyForCurrentOs())) {
                    extractNatives(dest, nativesDir);
                }
            } else {
                synchronized (resolved.classpath) {
                    resolved.classpath.add(dest);
                }
            }
        }
    }

    private String mavenToPath(String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private void extractNatives(Path nativeJar, Path nativesDir) throws IOException {
        try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF")) continue;
                Path out = nativesDir.resolve(name).normalize();
                if (!out.startsWith(nativesDir)) continue; // zip-slip guard
                Files.createDirectories(out.getParent());
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void downloadAssets(JsonObject assetIndexRef, Path gameDir, Consumer<String> log) throws IOException, InterruptedException {
        if (!assetIndexRef.has("id") || !assetIndexRef.has("url")) {
            throw new IOException("Version JSON's \"assetIndex\" entry is missing \"id\" or \"url\" \u2014 "
                    + "the version file appears incomplete or corrupted.");
        }
        String id = assetIndexRef.get("id").getAsString();
        String url = assetIndexRef.get("url").getAsString();
        Path indexPath = LauncherPaths.assetsDir(gameDir).resolve("indexes").resolve(id + ".json");
        if (!Files.exists(indexPath)) {
            HttpUtil.downloadToFile(url, indexPath);
        }
        JsonObject indexJson = JsonUtil.parse(Files.readString(indexPath)).getAsJsonObject();
        JsonObject objects = indexJson.getAsJsonObject("objects");

        List<Runnable> tasks = new ArrayList<>();
        int total = objects.size();
        int[] done = {0};
        List<Exception> downloadErrors = java.util.Collections.synchronizedList(new ArrayList<>()); // Thread-safe list for errors

        for (var entry : objects.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            if (!obj.has("hash") || !obj.has("size")) {
                log.accept("Skipping asset entry \"" + entry.getKey() + "\": missing hash/size in asset index.");
                synchronized (done) { done[0]++; }
                continue;
            }
            String hash = obj.get("hash").getAsString();
            String sub = hash.substring(0, 2);
            Path dest = LauncherPaths.assetsDir(gameDir).resolve("objects").resolve(sub).resolve(hash);
            tasks.add(() -> {
                try {
                    if (!Files.exists(dest) || Files.size(dest) != obj.get("size").getAsLong()) {
                        String assetUrl = "https://resources.download.minecraft.net/" + sub + "/" + hash;
                        HttpUtil.downloadToFile(assetUrl, dest);
                    }
                } catch (Exception e) {
                    log.accept("Asset download failed (" + entry.getKey() + "): " + e.getMessage());
                    downloadErrors.add(e); // Add exception to the list
                }
                synchronized (done) {
                    done[0]++;
                    if (done[0] % 50 == 0 || done[0] == total) {
                        log.accept("Downloading assets: " + done[0] + "/" + total);
                    }
                }
            });
        }

        // Modest parallelism - asset CDN handles concurrent downloads fine, and this is the slowest step.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (Runnable t : tasks) pool.submit(t);
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); // Restore interrupt status
        }

        // If any download failed, re-throw the first error to indicate failure
        if (!downloadErrors.isEmpty()) {
            Exception firstError = downloadErrors.get(0);
            if (firstError instanceof IOException) {
                throw (IOException) firstError;
            } else if (firstError instanceof InterruptedException) {
                throw (InterruptedException) firstError;
            } else {
                throw new IOException("One or more asset downloads failed: " + firstError.getMessage(), firstError);
            }
        }
    }

    private boolean rulesAllow(JsonObject lib) {
        if (!lib.has("rules")) return true;
        boolean allowed = false;
        for (var el : lib.getAsJsonArray("rules")) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").isJsonNull()) {
                    matches = os.get("name").getAsString().equals(currentOsKey());
                }
            }
            if (matches) {
                allowed = rule.has("action") && !rule.get("action").isJsonNull()
                        && rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    private static String currentOsKey() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private static String nativeClassifierKeyForCurrentOs() {
        String os = currentOsKey();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean is64 = arch.contains("64");
        return switch (os) {
            case "windows" -> is64 ? "natives-windows" : "natives-windows-32";
            case "osx" -> "natives-macos";
            default -> "natives-linux";
        };
    }
}