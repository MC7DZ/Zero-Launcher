package com.launcher.minecraft;

import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Forge's modern install process involves binary patching ("install profile" + "processors")
 * that every open-source launcher (MultiMC/Prism included) implements by either re-running
 * Forge's own logic or by shelling out to the official installer jar. We take the second,
 * much smaller and more maintainable, approach here: download forge-<mc>-<forge>-installer.jar
 * from Forge's maven and run it in headless --installClient mode against the instance's
 * game directory. It then writes a normal version json under <gameDir>/versions/... which we
 * feed into the same GameInstaller used for vanilla/Fabric.
 */
public class ForgeInstaller {

    public String fetchPromotedLatest(String mcVersion, boolean recommended) throws IOException, InterruptedException {
        // promotions_slim.json maps "<mc>-recommended" / "<mc>-latest" -> forge version string
        String body = HttpUtil.getString("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        JsonObject promos = root.getAsJsonObject("promos");
        String key = mcVersion + (recommended ? "-recommended" : "-latest");
        if (!promos.has(key)) {
            key = mcVersion + "-latest"; // fall back if no "recommended" build exists for this MC version
        }
        if (!promos.has(key)) {
            throw new IOException("No Forge build found for Minecraft " + mcVersion);
        }
        return promos.get(key).getAsString();
    }

    /**
     * Downloads and runs the official Forge installer against gameDir.
     * Requires a working "java" on PATH (any JRE 8+ is fine just to run the installer).
     * Returns the version id Forge registered (e.g. "1.20.1-forge-47.2.0") so it can be loaded
     * from <gameDir>/versions/<id>/<id>.json afterwards.
     */
    public String installClient(String mcVersion, String forgeVersion, Path gameDir, Consumer<String> log)
            throws IOException, InterruptedException {

        Files.createDirectories(gameDir);
        String fullVersion = mcVersion + "-" + forgeVersion;
        String installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + fullVersion + "/forge-" + fullVersion + "-installer.jar";

        Path installerJar = gameDir.resolve(".forge-installer-cache").resolve("forge-" + fullVersion + "-installer.jar");
        if (!Files.exists(installerJar)) {
            log.accept("Downloading Forge installer for " + fullVersion + " ...");
            HttpUtil.downloadToFile(installerUrl, installerJar);
        }

        log.accept("Running Forge installer (this can take a minute) ...");
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", installerJar.toAbsolutePath().toString(),
                "--installClient", gameDir.toAbsolutePath().toString());
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (var reader = proc.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept("[forge-installer] " + line);
            }
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("Forge installer exited with code " + exit);
        }

        // Find the version directory the installer just created.
        Path versionsDir = gameDir.resolve("versions");
        try (var stream = Files.list(versionsDir)) {
            var match = stream.filter(p -> p.getFileName().toString().contains("forge"))
                    .filter(p -> p.getFileName().toString().contains(mcVersion))
                    .findFirst();
            if (match.isPresent()) {
                return match.get().getFileName().toString();
            }
        }
        throw new IOException("Forge installer finished but no forge version directory was found under " + versionsDir);
    }

    public JsonObject loadGeneratedVersionJson(Path gameDir, String versionId) throws IOException {
        Path jsonPath = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        return JsonUtil.parse(Files.readString(jsonPath)).getAsJsonObject();
    }
}
