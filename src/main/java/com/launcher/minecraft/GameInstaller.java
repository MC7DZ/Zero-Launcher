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

        String id = versionJson.has("id") ? versionJson.get("id").getAsString() : "unknown";

        // Fast path: if we already fully resolved this exact version once before and every file
        // it points at (client jar + every library on the classpath) is still present on disk,
        // reuse that result instead of re-walking/re-checking the whole "libraries" array again.
        // This is what turns every subsequent launch of an already-installed instance from a
        // full library scan back into a couple of stat() calls.
        ResolvedVersion cached = loadCachedResolved(gameDir, id);
        if (cached != null && cachedResolvedStillValid(cached, versionJson)) {
            log.accept("Using cached resolved version for \"" + id + "\" (" + cached.classpath.size()
                    + " classpath entries) \u2014 skipping library re-scan.");
            return cached;
        }

        ResolvedVersion resolved = new ResolvedVersion();
        resolved.id = id;
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
            ExecutorService libraryPool = com.launcher.util.DownloadConcurrency.newDownloadPool("lib-dl", toProcess.size());
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

        saveResolvedCache(gameDir, resolved);
        return resolved;
    }

    /** Where the cached, already-resolved classpath for a version id lives. Kept next to the
     *  version JSON itself so it travels with the instance and is easy to blow away by hand
     *  (delete the file, or the whole version folder) if something ever looks stale. */
    private Path resolvedCachePath(Path gameDir, String versionId) {
        return LauncherPaths.versionsDir(gameDir).resolve(versionId).resolve(versionId + ".resolved-cache.json");
    }

    private void saveResolvedCache(Path gameDir, ResolvedVersion resolved) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", resolved.id);
            obj.addProperty("mainClass", resolved.mainClass);
            if (resolved.assetIndexId != null) obj.addProperty("assetIndexId", resolved.assetIndexId);
            if (resolved.legacyMinecraftArguments != null) {
                obj.addProperty("legacyMinecraftArguments", resolved.legacyMinecraftArguments);
            }
            JsonArray cp = new JsonArray();
            for (Path p : resolved.classpath) cp.add(p.toAbsolutePath().toString());
            obj.add("classpath", cp);
            JsonArray gameArgs = new JsonArray();
            for (String s : resolved.extraGameArgs) gameArgs.add(s);
            obj.add("extraGameArgs", gameArgs);
            JsonArray jvmArgs = new JsonArray();
            for (String s : resolved.extraJvmArgs) jvmArgs.add(s);
            obj.add("extraJvmArgs", jvmArgs);

            Path cachePath = resolvedCachePath(gameDir, resolved.id);
            Files.createDirectories(cachePath.getParent());
            Files.writeString(cachePath, JsonUtil.GSON.toJson(obj), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Caching is a pure optimization - never let a failure to write it fail the launch.
        }
    }

    private ResolvedVersion loadCachedResolved(Path gameDir, String versionId) {
        try {
            Path cachePath = resolvedCachePath(gameDir, versionId);
            if (!Files.exists(cachePath)) return null;
            JsonObject obj = JsonUtil.parse(Files.readString(cachePath)).getAsJsonObject();

            ResolvedVersion resolved = new ResolvedVersion();
            resolved.id = obj.has("id") ? obj.get("id").getAsString() : versionId;
            if (!obj.has("mainClass")) return null;
            resolved.mainClass = obj.get("mainClass").getAsString();
            if (obj.has("assetIndexId")) resolved.assetIndexId = obj.get("assetIndexId").getAsString();
            if (obj.has("legacyMinecraftArguments")) {
                resolved.legacyMinecraftArguments = obj.get("legacyMinecraftArguments").getAsString();
            }
            if (obj.has("classpath")) {
                for (var el : obj.getAsJsonArray("classpath")) {
                    resolved.classpath.add(Path.of(el.getAsString()));
                }
            }
            if (obj.has("extraGameArgs")) {
                for (var el : obj.getAsJsonArray("extraGameArgs")) resolved.extraGameArgs.add(el.getAsString());
            }
            if (obj.has("extraJvmArgs")) {
                for (var el : obj.getAsJsonArray("extraJvmArgs")) resolved.extraJvmArgs.add(el.getAsString());
            }
            return resolved;
        } catch (Exception e) {
            return null; // corrupt/unreadable cache - fall through to a full resolve
        }
    }

    /** A cached resolve is only trustworthy if (a) every file it points at is still there, and
     *  (b) it actually contains as many classpath entries as the version JSON currently declares.
     *  (b) matters because a cache written before a library-resolution bug was fixed (e.g. a
     *  library that silently failed to download and got skipped) would otherwise look "valid"
     *  forever - every entry it *does* reference exists on disk, it's just missing one it should
     *  have had. Recomputing the expected count from the version JSON itself (no I/O, no
     *  downloads) and comparing catches that case and forces a real re-resolve instead. */
    private boolean cachedResolvedStillValid(ResolvedVersion resolved, JsonObject versionJson) {
        if (resolved.classpath.isEmpty()) return false;
        for (Path p : resolved.classpath) {
            try {
                if (!Files.exists(p) || Files.size(p) == 0) return false;
            } catch (IOException e) {
                return false;
            }
        }
        int expected = expectedClasspathEntryCount(versionJson);
        return resolved.classpath.size() >= expected;
    }

    /** Counts how many classpath entries the version JSON *should* resolve to: the client jar
     *  (if one is declared/expected) plus every library that isn't a natives-only classifier
     *  entry and whose rules allow it on this OS. Pure JSON inspection, no filesystem/network. */
    private int expectedClasspathEntryCount(JsonObject versionJson) {
        int count = 0;
        boolean hasClientDownload = versionJson.has("downloads")
                && versionJson.getAsJsonObject("downloads").has("client");
        // Every version we resolve either has its own client jar entry or inherits one via
        // clientJarId - either way exactly one client jar belongs on the classpath.
        if (hasClientDownload || versionJson.has("clientJarId") || versionJson.has("id")) count += 1;

        if (versionJson.has("libraries")) {
            for (var el : versionJson.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                if (!rulesAllow(lib)) continue;

                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) count += 1;
                    // classifiers-only entries (natives) contribute 0 to the classpath
                } else if (lib.has("name") && !lib.get("name").isJsonNull()) {
                    String[] parts = lib.get("name").getAsString().split(":");
                    boolean isNativeClassifier = parts.length > 3 && parts[3].startsWith("natives-");
                    if (!isNativeClassifier) count += 1;
                }
            }
        }
        return count;
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
                    String expectedSha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
                    Path dest = LauncherPaths.librariesDir(gameDir).resolve(path);
                    ensureValidLibraryFile(dest, url, expectedSha1, log);
                    synchronized (resolved.classpath) {
                        resolved.classpath.add(dest);
                    }
                }
            }

            if (downloads.has("classifiers")) {
                String classifierKey = nativeClassifierKeyForCurrentOs();
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifierKey != null && classifiers != null && classifiers.has(classifierKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                    if (!nativeArtifact.has("path") || !nativeArtifact.has("url")) {
                        log.accept("WARNING: Skipping a native library with an incomplete classifier entry (missing path/url).");
                    } else {
                        String path = nativeArtifact.get("path").getAsString();
                        String url = nativeArtifact.get("url").getAsString();
                        String expectedSha1 = nativeArtifact.has("sha1") ? nativeArtifact.get("sha1").getAsString() : null;
                        Path dest = LauncherPaths.librariesDir(gameDir).resolve(path);
                        ensureValidLibraryFile(dest, url, expectedSha1, log);
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
            try {
                ensureValidLibraryFile(dest, downloadUrl, null, log);
            } catch (IOException e) {
                throw new IOException("Failed to obtain a valid copy of library: " + name + " from " + downloadUrl
                        + ". Error: " + e.getMessage(), e);
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

    /** Makes sure {@code dest} is an intact copy of the library before we ever hand it back to
     *  be put on the classpath. This is the fix for a nasty failure mode: a jar left behind
     *  half-written by an earlier crashed/interrupted install (nonzero size, but truncated or
     *  otherwise corrupt) used to pass the old "exists and is non-empty" check forever and get
     *  silently reused - added to the classpath, but unreadable by the JVM's URLClassLoader,
     *  producing a bare NoClassDefFoundError at game launch with nothing in the install log to
     *  explain why. Now: if we have a known-good sha1, trust that; otherwise fall back to
     *  actually opening the file as a zip. Either way, a bad existing file gets deleted and
     *  redownloaded (once) instead of silently poisoning the classpath. */
    private void ensureValidLibraryFile(Path dest, String url, String expectedSha1, Consumer<String> log)
            throws IOException, InterruptedException {
        if (Files.exists(dest) && Files.size(dest) > 0 && isValidLibraryFile(dest, expectedSha1)) {
            return; // already have a good copy, nothing to do
        }
        if (Files.exists(dest)) {
            log.accept("Existing library file looks corrupt/incomplete, redownloading: " + dest.getFileName());
            Files.deleteIfExists(dest);
        }
        HttpUtil.downloadToFile(url, dest);
        if (!Files.exists(dest) || Files.size(dest) == 0) {
            throw new IOException("Library download produced no file: " + dest);
        }
        if (!isValidLibraryFile(dest, expectedSha1)) {
            Files.deleteIfExists(dest);
            throw new IOException("Downloaded library failed integrity check (corrupt download or bad mirror): " + url);
        }
    }

    private boolean isValidLibraryFile(Path file, String expectedSha1) {
        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            try {
                String actual = sha1Hex(file);
                return expectedSha1.equalsIgnoreCase(actual);
            } catch (Exception e) {
                return false;
            }
        }
        // No checksum to compare against (common for bare Maven-coordinate libraries) - the best
        // we can do without one is confirm it's actually a readable zip/jar, which is enough to
        // catch the truncated/half-written-file case that caused the silent classpath poisoning.
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.entries().hasMoreElements() || zip.size() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private String sha1Hex(Path file) throws IOException, java.security.NoSuchAlgorithmException {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
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

        // Parallelism is now configurable (Settings > Performance > Download Threads) instead
        // of a hardcoded pool size, so slower connections/machines or people on metered/limited
        // bandwidth can turn it down, and people on fast fiber + NVMe can turn it up.
        ExecutorService pool = com.launcher.util.DownloadConcurrency.newDownloadPool("asset-dl", tasks.size());
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