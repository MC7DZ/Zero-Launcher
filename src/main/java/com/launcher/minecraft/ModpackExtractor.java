package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts Modrinth modpack archives (.mrpack) into a target directory.
 * <p>
 * Handles:
 * <ul>
 *   <li>Parsing {@code modrinth.index.json}</li>
 *   <li>Extracting {@code overrides/} and {@code client-overrides/}</li>
 *   <li>Downloading all indexed files with optional hash verification (SHA1)</li>
 *   <li>Parallel downloads with configurable concurrency and retries</li>
 * </ul>
 * <p>
 * Improved version with:
 * <ul>
 *   <li>Built‑in HTTP client with timeouts and retries</li>
 *   <li>Atomic counters for thread‑safe progress</li>
 *   <li>Optional hash verification (SHA1)</li>
 *   <li>Configurable concurrency, retries, and timeouts</li>
 *   <li>Better error handling and logging</li>
 *   <li>Clean resource management</li>
 * </ul>
 */
public class ModpackExtractor {

    private static final Logger LOGGER = Logger.getLogger(ModpackExtractor.class.getName());

    private final HttpClient httpClient;
    private final int maxConcurrentDownloads;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private final Duration downloadTimeout;
    private final boolean verifyHashes;

    /**
     * Creates a new extractor with default settings:
     * <ul>
     *   <li>Max concurrent downloads: 6</li>
     *   <li>Max retries: 3</li>
     *   <li>Retry base delay: 1 second</li>
     *   <li>Download timeout: 30 seconds</li>
     *   <li>Hash verification: enabled</li>
     * </ul>
     */
    public ModpackExtractor() {
        this(6, 3, Duration.ofSeconds(1), Duration.ofSeconds(30), true);
    }

    /**
     * Full constructor for complete customisation.
     *
     * @param maxConcurrentDownloads maximum number of parallel downloads
     * @param maxRetries             number of retries on download failure
     * @param retryBaseDelay         initial delay between retries (exponential backoff)
     * @param downloadTimeout        timeout for each download request
     * @param verifyHashes           whether to verify SHA1 hashes (if present in index)
     */
    public ModpackExtractor(int maxConcurrentDownloads, int maxRetries,
                            Duration retryBaseDelay, Duration downloadTimeout,
                            boolean verifyHashes) {
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.maxRetries = maxRetries;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay);
        this.downloadTimeout = Objects.requireNonNull(downloadTimeout);
        this.verifyHashes = verifyHashes;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Extracts a Modrinth modpack (.mrpack) into the given install directory.
     *
     * @param modpackFile absolute path to the .mrpack file
     * @param installDir  directory to extract into (will be created if needed)
     * @param log         progress/error callback (may be null)
     * @throws IOException if extraction fails
     */
    public void extract(Path modpackFile, Path installDir, Consumer<String> log) throws IOException {
        if (modpackFile == null || installDir == null) {
            throw new IllegalArgumentException("modpackFile and installDir must not be null");
        }
        String fileName = modpackFile.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".mrpack")) {
            throw new IOException("Unsupported format: " + fileName + " (only .mrpack is supported)");
        }

        Files.createDirectories(installDir);
        logIf(log, "Extracting Modrinth modpack: " + modpackFile.getFileName());
        extractMrpack(modpackFile, installDir, log);
        logIf(log, "Modrinth modpack extraction complete.");
    }

    // ------------------------------------------------------------------------
    // .mrpack processing
    // ------------------------------------------------------------------------

    private void extractMrpack(Path mrpackFile, Path installDir, Consumer<String> log) throws IOException {
        try (ZipFile zip = new ZipFile(mrpackFile.toFile())) {
            // 1. Read and parse modrinth.index.json
            JsonObject index = readIndexJson(zip, log);
            if (index == null) {
                throw new IOException("modrinth.index.json not found in the archive");
            }

            // 2. Extract overrides/ and client-overrides/
            extractOverridesDir(zip, "overrides/", installDir, log);
            extractOverridesDir(zip, "client-overrides/", installDir, log);

            // 3. Download files listed in the index
            if (index.has("files")) {
                downloadIndexedFiles(index.getAsJsonArray("files"), installDir, log);
            }
        }
    }

    private JsonObject readIndexJson(ZipFile zip, Consumer<String> log) throws IOException {
        ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
        if (indexEntry == null) {
            logIf(log, "WARNING: No modrinth.index.json found");
            return null;
        }
        try (InputStream is = zip.getInputStream(indexEntry)) {
            String json = new String(is.readAllBytes());
            logIf(log, "Parsed modrinth.index.json");
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }

    /**
     * Extracts entries under a given prefix directory from the zip into the install dir,
     * stripping the prefix from the path.
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
            if (!target.startsWith(installDir)) {
                LOGGER.warning("Skipping potential zip-slip: " + name);
                continue;
            }

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
            logIf(log, "Extracted " + count + " files from " + prefix);
        }
    }

    // ------------------------------------------------------------------------
    // Parallel download of indexed files
    // ------------------------------------------------------------------------

    private void downloadIndexedFiles(JsonArray files, Path installDir, Consumer<String> log) {
        if (files == null || files.isEmpty()) {
            logIf(log, "No files to download.");
            return;
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (var el : files) {
            JsonObject fileObj = el.getAsJsonObject();
            String path = fileObj.get("path").getAsString();
            Path dest = installDir.resolve(path).normalize();
            if (!dest.startsWith(installDir)) {
                LOGGER.warning("Skipping file outside install dir: " + path);
                continue;
            }

            // Get download URLs (use first one)
            JsonArray downloads = fileObj.has("downloads") ? fileObj.getAsJsonArray("downloads") : null;
            if (downloads == null || downloads.isEmpty()) {
                logIf(log, "WARNING: No download URL for " + path + " — skipping");
                continue;
            }
            String url = downloads.get(0).getAsString();

            // Expected size and hash.
            // NOTE: Modrinth's index format nests the hash under "hashes": { "sha1": ..., "sha512": ... },
            // not a top-level "hash" field. Reading "hash" directly always returned null, silently
            // disabling hash verification for every file.
            long expectedSize = fileObj.has("fileSize") ? fileObj.get("fileSize").getAsLong() : -1;
            String expectedSha1 = null;
            if (fileObj.has("hashes") && fileObj.get("hashes").isJsonObject()) {
                JsonObject hashes = fileObj.getAsJsonObject("hashes");
                if (hashes.has("sha1")) {
                    expectedSha1 = hashes.get("sha1").getAsString();
                }
            } else if (fileObj.has("hash")) {
                expectedSha1 = fileObj.get("hash").getAsString();
            }

            tasks.add(new DownloadTask(url, dest, expectedSize, expectedSha1));
        }

        if (tasks.isEmpty()) {
            logIf(log, "No downloadable files.");
            return;
        }

        logIf(log, "Downloading " + tasks.size() + " modpack files…");

        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(maxConcurrentDownloads, tasks.size()));

        for (DownloadTask task : tasks) {
            pool.submit(() -> {
                try {
                    downloadWithRetries(task);
                    int completed = done.incrementAndGet();
                    if (completed % 10 == 0 || completed == tasks.size()) {
                        logIf(log, "Downloaded " + completed + "/" + tasks.size() + " files");
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logIf(log, "Failed to download " + task.dest.getFileName() + ": " + e.getMessage());
                }
            });
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow();
                logIf(log, "Download pool did not finish in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            logIf(log, "Download interrupted.");
        }

        if (failed.get() > 0) {
            logIf(log, "WARNING: " + failed.get() + " file(s) failed to download.");
        }
        logIf(log, "Mod downloads complete: " + done.get() + " succeeded, " + failed.get() + " failed.");
    }

    // ------------------------------------------------------------------------
    // Download logic with retries and hash verification
    // ------------------------------------------------------------------------

    private void downloadWithRetries(DownloadTask task) throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            try {
                downloadFile(task);
                return;
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new IOException("Failed after " + (attempt + 1) + " attempts for " + task.url, e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Download of {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{task.url, attempt + 1, delay});
                Thread.sleep(delay);
                attempt++;
            }
        }
    }

    private void downloadFile(DownloadTask task) throws IOException, InterruptedException {
        Path dest = task.dest;
        String url = task.url;

        // Skip if file already exists and size matches (and optionally hash matches)
        if (Files.exists(dest)) {
            if (task.expectedSize > 0 && Files.size(dest) != task.expectedSize) {
                // Size mismatch, delete and re-download
                Files.delete(dest);
            } else if (verifyHashes && task.expectedSha1 != null && !task.expectedSha1.isEmpty()) {
                String actualSha1 = computeSha1(dest);
                if (actualSha1.equalsIgnoreCase(task.expectedSha1)) {
                    LOGGER.fine("File " + dest + " already exists with matching hash, skipping.");
                    return;
                } else {
                    Files.delete(dest);
                }
            } else {
                // No verification, assume existing file is valid
                LOGGER.fine("File " + dest + " already exists, skipping.");
                return;
            }
        }

        // Ensure parent directory exists
        Files.createDirectories(dest.getParent());

        // Download
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(downloadTimeout)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(dest, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            Files.deleteIfExists(dest);
            throw new IOException("HTTP " + status + " for " + url);
        }

        // Verify size
        if (task.expectedSize > 0 && Files.size(dest) != task.expectedSize) {
            Files.deleteIfExists(dest);
            throw new IOException("Size mismatch: expected " + task.expectedSize +
                    ", got " + Files.size(dest) + " for " + url);
        }

        // Verify SHA1 hash if requested
        if (verifyHashes && task.expectedSha1 != null && !task.expectedSha1.isEmpty()) {
            String actualSha1 = computeSha1(dest);
            if (!actualSha1.equalsIgnoreCase(task.expectedSha1)) {
                Files.deleteIfExists(dest);
                throw new IOException("SHA1 mismatch for " + url + ": expected " +
                        task.expectedSha1 + ", got " + actualSha1);
            }
        }

        LOGGER.fine("Downloaded " + url + " to " + dest);
    }

    private String computeSha1(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Helper classes and methods
    // ------------------------------------------------------------------------

    private static class DownloadTask {
        final String url;
        final Path dest;
        final long expectedSize;
        final String expectedSha1;

        DownloadTask(String url, Path dest, long expectedSize, String expectedSha1) {
            this.url = url;
            this.dest = dest;
            this.expectedSize = expectedSize;
            this.expectedSha1 = expectedSha1;
        }
    }

    private void logIf(Consumer<String> log, String msg) {
        if (log != null) {
            log.accept(msg);
        }
        LOGGER.info(msg);
    }

    // ------------------------------------------------------------------------
    // Example usage (optional)
    // ------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ModpackExtractor extractor = new ModpackExtractor();
        Path modpack = Path.of("my_modpack.mrpack");
        Path install = Path.of("./instance");
        extractor.extract(modpack, install, System.out::println);
    }
}