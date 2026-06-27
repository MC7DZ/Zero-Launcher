package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * NeoForge installer — mirrors the ForgeInstaller approach:
 * download the official installer jar from NeoForge's Maven and run it
 * in headless {@code --install-client} mode against the instance game directory.
 *
 * NeoForge Maven: https://maven.neoforged.net/releases/net/neoforged/neoforge/
 * Version list API: https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge
 */
public class NeoForgeInstaller {

    private static final String VERSIONS_API =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    private static final String MAVEN_BASE =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge";

    /**
     * Fetches available NeoForge versions for the given Minecraft version.
     * NeoForge version strings start with the minor MC version (e.g. "21.1.x" for MC 1.21.1).
     */
    public List<String> fetchVersions(String mcVersion) throws IOException, InterruptedException {
        String body = HttpUtil.getString(VERSIONS_API);
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("versions");

        // Derive the prefix: "1.21.1" → "21.1.", "1.20.4" → "20.4."
        String prefix = derivePrefix(mcVersion);

        List<String> result = new ArrayList<>();
        for (var el : arr) {
            String v = el.getAsString();
            if (v.startsWith(prefix)) result.add(0, v); // newest first
        }
        // Fall back to all versions if nothing matched
        if (result.isEmpty()) {
            for (var el : arr) result.add(0, el.getAsString());
        }
        return result;
    }

    /**
     * Downloads and runs the NeoForge installer against {@code gameDir}.
     * Requires a working {@code java} on PATH.
     *
     * @return the version id NeoForge registered (e.g. "neoforge-21.1.77")
     *         so it can be loaded from {@code <gameDir>/versions/<id>/<id>.json} afterwards.
     */
    public String installClient(String mcVersion, String neoForgeVersion, Path gameDir,
                                Consumer<String> log)
            throws IOException, InterruptedException {

        Files.createDirectories(gameDir);

        String installerUrl = MAVEN_BASE + "/" + neoForgeVersion
                + "/neoforge-" + neoForgeVersion + "-installer.jar";

        Path cacheDir = gameDir.resolve(".neoforge-installer-cache");
        Files.createDirectories(cacheDir);
        Path installerJar = cacheDir.resolve("neoforge-" + neoForgeVersion + "-installer.jar");

        if (!Files.exists(installerJar)) {
            log.accept("Downloading NeoForge installer for " + neoForgeVersion + " …");
            HttpUtil.downloadToFile(installerUrl, installerJar);
        }

        log.accept("Running NeoForge installer (this may take a minute) …");
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", installerJar.toAbsolutePath().toString(),
                "--install-client", gameDir.toAbsolutePath().toString());
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (var reader = proc.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept("[neoforge-installer] " + line);
            }
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("NeoForge installer exited with code " + exit);
        }

        // Locate the version directory the installer created.
        Path versionsDir = gameDir.resolve("versions");
        if (Files.exists(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                var match = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().contains("neoforge"))
                        .findFirst();
                if (match.isPresent()) {
                    return match.get().getFileName().toString();
                }
            }
        }
        throw new IOException(
                "NeoForge installer finished but no neoforge version directory was found under "
                        + versionsDir);
    }

    /** Reads the version JSON the installer wrote so GameInstaller can process it. */
    public JsonObject loadGeneratedVersionJson(Path gameDir, String versionId) throws IOException {
        Path jsonPath = gameDir.resolve("versions")
                .resolve(versionId)
                .resolve(versionId + ".json");
        return JsonUtil.parse(Files.readString(jsonPath)).getAsJsonObject();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a Minecraft version string to the NeoForge version prefix.
     * "1.21.1"  → "21.1."
     * "1.20.4"  → "20.4."
     * "1.21"    → "21."
     */
    private String derivePrefix(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length >= 3) {
            return parts[1] + "." + parts[2] + ".";
        } else if (parts.length == 2) {
            return parts[1] + ".";
        }
        return "";
    }
}
