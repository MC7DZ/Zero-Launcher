package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Extracts modpack archives (.mrpack and .zip) into a target directory.
 * <p>
 * <b>.mrpack (Modrinth)</b>: parses {@code modrinth.index.json}, extracts
 * {@code overrides/} and {@code client-overrides/} directories, and downloads
 * any mods listed in the index with download URLs.
 * <p>
 * <b>.zip (generic)</b>: extracts the full archive contents into the target directory.
 */
public class ModpackExtractor {

    /**
     * Extracts a modpack file into the given install directory.
     *
     * @param modpackFile absolute path to the .mrpack or .zip file
     * @param installDir  directory to extract into (will be created if needed)
     * @param log         progress/error callback
     */
    public void extract(Path modpackFile, Path installDir, Consumer<String> log) throws IOException {
        Files.createDirectories(installDir);

        String fileName = modpackFile.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".mrpack")) {
            extractMrpack(modpackFile, installDir, log);
        } else if (fileName.endsWith(".zip")) {
            extractZip(modpackFile, installDir, log);
        } else {
            throw new IOException("Unsupported modpack format: " + fileName
                    + " (expected .mrpack or .zip)");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  .mrpack (Modrinth modpack)
    // ═══════════════════════════════════════════════════════════════════════════

    private void extractMrpack(Path mrpackFile, Path installDir, Consumer<String> log) throws IOException {
        log.accept("Extracting Modrinth modpack: " + mrpackFile.getFileName());

        try (ZipFile zip = new ZipFile(mrpackFile.toFile())) {
            // 1. Read and parse modrinth.index.json
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            JsonObject index = null;
            if (indexEntry != null) {
                try (InputStream is = zip.getInputStream(indexEntry)) {
                    String json = new String(is.readAllBytes());
                    index = JsonUtil.parse(json).getAsJsonObject();
                }
                log.accept("Parsed modrinth.index.json");
            } else {
                log.accept("WARNING: No modrinth.index.json found — extracting as plain zip");
                extractZipContents(zip, installDir, log);
                return;
            }

            // 2. Extract overrides/ → installDir
            extractOverridesDir(zip, "overrides/", installDir, log);

            // 3. Extract client-overrides/ → installDir (client-side files)
            extractOverridesDir(zip, "client-overrides/", installDir, log);

            // 4. Download files listed in the index
            if (index.has("files")) {
                downloadIndexedFiles(index.getAsJsonArray("files"), installDir, log);
            }
        }

        log.accept("Modrinth modpack extraction complete.");
    }

    /**
     * Extracts entries under a given prefix directory (e.g. "overrides/") from
     * the zip into the install dir, stripping the prefix from the path.
     */
    private void extractOverridesDir(ZipFile zip, String prefix, Path installDir, Consumer<String> log) throws IOException {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || name.equals(prefix)) continue;

            String relativePath = name.substring(prefix.length());
            Path target = installDir.resolve(relativePath).normalize();

            // Zip-slip protection
            if (!target.startsWith(installDir)) continue;

            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        if (count > 0) {
            log.accept("Extracted " + count + " files from " + prefix);
        }
    }

    /**
     * Downloads files listed in the modrinth.index.json "files" array.
     * Each entry has: path, hashes, downloads[], fileSize.
     */
    private void downloadIndexedFiles(JsonArray files, Path installDir, Consumer<String> log) {
        if (files == null || files.isEmpty()) return;

        int total = files.size();
        log.accept("Downloading " + total + " modpack files…");

        List<Runnable> tasks = new ArrayList<>();
        int[] done = {0};
        int[] failed = {0};

        for (JsonElement el : files) {
            JsonObject fileObj = el.getAsJsonObject();
            String path = fileObj.get("path").getAsString();
            Path dest = installDir.resolve(path).normalize();

            // Zip-slip protection
            if (!dest.startsWith(installDir)) continue;

            // Get download URL (first available)
            JsonArray downloads = fileObj.has("downloads") ? fileObj.getAsJsonArray("downloads") : null;
            if (downloads == null || downloads.isEmpty()) {
                log.accept("WARNING: No download URL for " + path + " — skipping");
                continue;
            }
            String url = downloads.get(0).getAsString();

            // Get expected file size for verification
            long expectedSize = fileObj.has("fileSize") ? fileObj.get("fileSize").getAsLong() : -1;

            tasks.add(() -> {
                try {
                    // Skip if already downloaded and size matches
                    if (Files.exists(dest) && expectedSize > 0 && Files.size(dest) == expectedSize) {
                        synchronized (done) {
                            done[0]++;
                        }
                        return;
                    }

                    Files.createDirectories(dest.getParent());
                    HttpUtil.downloadToFile(url, dest);

                    synchronized (done) {
                        done[0]++;
                        if (done[0] % 10 == 0 || done[0] == total) {
                            log.accept("Downloaded " + done[0] + "/" + total + " files");
                        }
                    }
                } catch (Exception e) {
                    synchronized (failed) {
                        failed[0]++;
                    }
                    log.accept("Failed to download " + path + ": " + e.getMessage());
                }
            });
        }

        // Parallel downloads with modest thread pool
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (Runnable task : tasks) pool.submit(task);
        pool.shutdown();
        try {
            pool.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (failed[0] > 0) {
            log.accept("WARNING: " + failed[0] + " file(s) failed to download.");
        }
        log.accept("Mod downloads complete: " + done[0] + " succeeded, " + failed[0] + " failed.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  .zip (generic modpack)
    // ═══════════════════════════════════════════════════════════════════════════

    private void extractZip(Path zipFile, Path installDir, Consumer<String> log) throws IOException {
        log.accept("Extracting ZIP modpack: " + zipFile.getFileName());
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            extractZipContents(zip, installDir, log);
        }
        log.accept("ZIP modpack extraction complete.");
    }

    /**
     * Extracts all entries from a ZipFile into the install directory.
     */
    private void extractZipContents(ZipFile zip, Path installDir, Consumer<String> log) throws IOException {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            // Skip metadata directories
            if (name.startsWith("META-INF/") || name.startsWith("__MACOSX/")) continue;

            Path target = installDir.resolve(name).normalize();

            // Zip-slip protection
            if (!target.startsWith(installDir)) continue;

            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        log.accept("Extracted " + count + " files.");
    }
}
