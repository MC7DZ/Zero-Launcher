package com.launcher.minecraft;

import com.google.gson.JsonArray;
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
        if (!versionJson.has("inheritsFrom")) {
            return versionJson;
        }
        String parentId = versionJson.get("inheritsFrom").getAsString();
        log.accept("Resolving parent version " + parentId + " ...");
        
        JsonObject parent = null;
        Path localJsonPath = LauncherPaths.findLocalVersionJson(parentId, null);
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
                throw new IOException("Could not find parent version '" + parentId + "' in Mojang's version manifest or local files");
            }
            parent = manifest.fetchVersionJson(parentUrl);
        }

        parent = resolveInheritance(parent, log); // parent might itself inherit (rare, but be safe)
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

        // Libraries: append child's on top of parent's.
        JsonArray libs = result.has("libraries") ? result.getAsJsonArray("libraries") : new JsonArray();
        if (child.has("libraries")) libs.addAll(child.getAsJsonArray("libraries"));
        result.add("libraries", libs);

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

    public ResolvedVersion installAndResolve(JsonObject versionJson, Path nativesDir, Consumer<String> log)
            throws IOException, InterruptedException {

        ResolvedVersion resolved = new ResolvedVersion();
        resolved.id = versionJson.has("id") ? versionJson.get("id").getAsString() : "unknown";
        resolved.mainClass = versionJson.get("mainClass").getAsString();

        Files.createDirectories(nativesDir);

        // --- client jar ---
        String jarId = versionJson.has("clientJarId") ? versionJson.get("clientJarId").getAsString() : resolved.id;
        Path jarPath = LauncherPaths.versionsDir().resolve(jarId).resolve(jarId + ".jar");
        if (!Files.exists(jarPath)) {
            // Check default .minecraft/versions
            Path localJar = LauncherPaths.getDefaultMinecraftPath().resolve("versions").resolve(jarId).resolve(jarId + ".jar");
            if (Files.exists(localJar)) {
                jarPath = localJar;
            } else if (versionJson.has("downloads") && versionJson.getAsJsonObject("downloads").has("client")) {
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
            int count = 0;
            for (var el : libraries) {
                JsonObject lib = el.getAsJsonObject();
                if (!rulesAllow(lib)) continue;
                count++;
                processLibrary(lib, resolved, nativesDir, log);
            }
            log.accept("Resolved " + count + " libraries.");
        }

        // --- asset index + assets ---
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            resolved.assetIndexId = assetIndex.get("id").getAsString();
            downloadAssets(assetIndex, log);
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

    private void processLibrary(JsonObject lib, ResolvedVersion resolved, Path nativesDir, Consumer<String> log)
            throws IOException, InterruptedException {
        // 1. Modern Mojang downloads structure
        if (lib.has("downloads")) {
            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String url = artifact.get("url").getAsString();
                Path dest = LauncherPaths.librariesDir().resolve(path);
                if (!Files.exists(dest) || Files.size(dest) == 0) {
                    HttpUtil.downloadToFile(url, dest);
                }
                resolved.classpath.add(dest);
            }

            if (downloads.has("classifiers")) {
                String classifierKey = nativeClassifierKeyForCurrentOs();
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifierKey != null && classifiers.has(classifierKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                    String path = nativeArtifact.get("path").getAsString();
                    String url = nativeArtifact.get("url").getAsString();
                    Path dest = LauncherPaths.librariesDir().resolve(path);
                    if (!Files.exists(dest) || Files.size(dest) == 0) {
                        HttpUtil.downloadToFile(url, dest);
                    }
                    extractNatives(dest, nativesDir);
                }
            }
            return;
        }

        // 2. Maven coordinate format (common for Fabric/Forge/local custom libraries)
        if (lib.has("name")) {
            String name = lib.get("name").getAsString();
            String path = mavenToPath(name);
            if (path == null) return;

            // Determine download URL (default to Mojang's libraries maven repository if not specified)
            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String downloadUrl = baseUrl + path;

            Path dest = LauncherPaths.librariesDir().resolve(path);
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
                resolved.classpath.add(dest);
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

    private void downloadAssets(JsonObject assetIndexRef, Consumer<String> log) throws IOException, InterruptedException {
        String id = assetIndexRef.get("id").getAsString();
        String url = assetIndexRef.get("url").getAsString();
        Path indexPath = LauncherPaths.assetsDir().resolve("indexes").resolve(id + ".json");
        if (!Files.exists(indexPath)) {
            HttpUtil.downloadToFile(url, indexPath);
        }
        JsonObject indexJson = JsonUtil.parse(Files.readString(indexPath)).getAsJsonObject();
        JsonObject objects = indexJson.getAsJsonObject("objects");

        List<Runnable> tasks = new ArrayList<>();
        int total = objects.size();
        int[] done = {0};
        for (var entry : objects.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            String sub = hash.substring(0, 2);
            Path dest = LauncherPaths.assetsDir().resolve("objects").resolve(sub).resolve(hash);
            tasks.add(() -> {
                try {
                    if (!Files.exists(dest) || Files.size(dest) != obj.get("size").getAsLong()) {
                        String assetUrl = "https://resources.download.minecraft.net/" + sub + "/" + hash;
                        HttpUtil.downloadToFile(assetUrl, dest);
                    }
                } catch (Exception e) {
                    log.accept("Asset download failed (" + entry.getKey() + "): " + e.getMessage());
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
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        for (Runnable t : tasks) pool.submit(t);
        pool.shutdown();
        try {
            pool.awaitTermination(2, java.util.concurrent.TimeUnit.HOURS);
        } catch (InterruptedException ignored) {}
    }

    private boolean rulesAllow(JsonObject lib) {
        if (!lib.has("rules")) return true;
        boolean allowed = false;
        for (var el : lib.getAsJsonArray("rules")) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) {
                    matches = os.get("name").getAsString().equals(currentOsKey());
                }
            }
            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
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
