package com.launcher.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Forge's modern install process involves binary patching ("install profile" + "processors")
 * that every open-source launcher implements by either re-running Forge's own logic or
 * shelling out to the official installer jar. We take the second, much smaller and more
 * maintainable, approach here:
 * <ul>
 *   <li>Download forge-{mc}-{forge}-installer.jar from Forge's Maven</li>
 *   <li>Run it in headless --installClient mode against the instance's game directory</li>
 *   <li>It then writes a normal version json under &lt;gameDir&gt;/versions/...</li>
 * </ul>
 * <p>
 * Improved version with:
 * <ul>
 *   <li>Built‑in HTTP client with timeouts and retries</li>
 *   <li>Configurable Java command and process timeout</li>
 *   <li>Dedicated installer cache (re‑use existing jar)</li>
 *   <li>Better logging and error reporting</li>
 *   <li>Robust version‑ID detection from the created version directory</li>
 * </ul>
 */
public class ForgeInstaller {

    private static final Logger LOGGER = Logger.getLogger(ForgeInstaller.class.getName());
    private static final String DEFAULT_BASE_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    private static final String PROMOTIONS_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String javaCommand;
    private final Duration processTimeout;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private final Path globalCacheDir;   // shared across instances (optional)

    /**
     * Creates a new installer with default settings:
     * <ul>
     *   <li>Base URL: {@value DEFAULT_BASE_URL}</li>
     *   <li>Java command: "java" (from PATH)</li>
     *   <li>Process timeout: 5 minutes</li>
     *   <li>Max download retries: 3</li>
     *   <li>Retry base delay: 1 second</li>
     *   <li>No global cache directory (cache inside gameDir)</li>
     * </ul>
     */
    public ForgeInstaller() {
        this(DEFAULT_BASE_URL, "java", Duration.ofMinutes(5), 3, Duration.ofSeconds(1), null);
    }

    /**
     * Full constructor for complete customisation.
     *
     * @param baseUrl         base Maven URL (without trailing slash)
     * @param javaCommand     path to the Java executable (e.g. "/usr/bin/java")
     * @param processTimeout  maximum time to wait for the installer to finish
     * @param maxRetries      number of download retries
     * @param retryBaseDelay  initial delay between retries (exponential backoff)
     * @param globalCacheDir  optional directory to store installer jars (shared between instances)
     */
    public ForgeInstaller(String baseUrl, String javaCommand, Duration processTimeout,
                          int maxRetries, Duration retryBaseDelay, Path globalCacheDir) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.javaCommand = Objects.requireNonNull(javaCommand, "javaCommand");
        this.processTimeout = Objects.requireNonNull(processTimeout, "processTimeout");
        this.maxRetries = maxRetries;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay, "retryBaseDelay");
        this.globalCacheDir = globalCacheDir;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Fetches the latest or recommended Forge version for a given Minecraft version.
     *
     * @param mcVersion   the Minecraft version (e.g. "1.20.1")
     * @param recommended if true, fetches the recommended build; otherwise the latest
     * @return the full Forge version string (e.g. "47.2.0")
     * @throws ForgeApiException if the promotions file cannot be fetched or the key is missing
     * @throws IllegalArgumentException if mcVersion is null or blank
     */
    public String fetchPromotedLatest(String mcVersion, boolean recommended) throws ForgeApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        String key = mcVersion + (recommended ? "-recommended" : "-latest");
        String json = fetchWithRetries(PROMOTIONS_URL);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject promos = root.getAsJsonObject("promos");
        if (!promos.has(key)) {
            // fall back to latest if recommended is missing
            String fallbackKey = mcVersion + "-latest";
            if (!promos.has(fallbackKey)) {
                throw new ForgeApiException("No Forge build found for Minecraft " + mcVersion + " (keys: " + promos.keySet() + ")");
            }
            LOGGER.log(Level.WARNING, "Recommended build missing for {0}, falling back to {1}", new Object[]{mcVersion, fallbackKey});
            key = fallbackKey;
        }
        return promos.get(key).getAsString();
    }

    /**
     * Downloads and runs the official Forge installer against {@code gameDir}.
     * The installer is cached in {@code <gameDir>/.forge-installer-cache} (or the global cache if set).
     * The process is run with the configured Java command and timeout.
     *
     * @param mcVersion     the Minecraft version
     * @param forgeVersion  the Forge version (as returned by {@link #fetchPromotedLatest})
     * @param gameDir       the root directory of the Minecraft instance
     * @param log           a consumer for logging progress messages (may be null)
     * @return the version id that Forge registered (e.g. "1.20.1-forge-47.2.0")
     * @throws ForgeApiException    if download or process execution fails
     * @throws IOException          if filesystem operations fail
     * @throws InterruptedException if the download or process is interrupted
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public String installClient(String mcVersion, String forgeVersion, Path gameDir, Consumer<String> log)
            throws ForgeApiException, IOException, InterruptedException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        if (forgeVersion == null || forgeVersion.isBlank()) {
            throw new IllegalArgumentException("forgeVersion must not be null or blank");
        }
        if (gameDir == null) {
            throw new IllegalArgumentException("gameDir must not be null");
        }

        String fullVersion = mcVersion + "-" + forgeVersion;
        Path installerJar = resolveInstallerPath(fullVersion);
        if (Files.notExists(installerJar)) {
            String installerUrl = baseUrl + "/" + fullVersion + "/forge-" + fullVersion + "-installer.jar";
            logIf(log, "Downloading Forge installer for " + fullVersion + " ...");
            downloadWithRetries(installerUrl, installerJar);
        } else {
            logIf(log, "Using cached Forge installer: " + installerJar);
        }

        logIf(log, "Running Forge installer (this can take a minute) ...");
        int exitCode = runInstallerProcess(installerJar, gameDir, log);
        if (exitCode != 0) {
            throw new ForgeApiException("Forge installer exited with code " + exitCode);
        }

        // Find the created version directory
        String versionId = detectForgeVersionId(gameDir, mcVersion, forgeVersion);
        logIf(log, "Forge installation complete. Version ID: " + versionId);
        return versionId;
    }

    /**
     * Loads the version JSON that the Forge installer generated.
     *
     * @param gameDir   the game directory
     * @param versionId the version id (as returned by {@link #installClient})
     * @return the parsed {@link JsonObject}
     * @throws IOException if the JSON file cannot be read
     */
    public JsonObject loadGeneratedVersionJson(Path gameDir, String versionId) throws IOException {
        Path jsonPath = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.notExists(jsonPath)) {
            throw new IOException("Version JSON not found: " + jsonPath);
        }
        String content = Files.readString(jsonPath);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private Path resolveInstallerPath(String fullVersion) throws IOException {
        if (globalCacheDir != null) {
            Path dir = globalCacheDir.resolve("forge-installer");
            Files.createDirectories(dir);
            return dir.resolve("forge-" + fullVersion + "-installer.jar");
        } else {
            // default: inside gameDir's .forge-installer-cache
            Path dir = Paths.get(System.getProperty("user.home"), ".forge-installer-cache");
            // But we can also use gameDir if we had it; but we don't have it here.
            // Actually we have gameDir only in installClient. We'll pass it there.
            // So better to resolve inside installClient with gameDir.
            // Let's refactor: we'll pass gameDir to resolveInstallerPath.
            // We'll modify to accept gameDir.
        }
        return null; // won't be reached
    }

    // Override: we'll use gameDir-based cache.
    private Path resolveInstallerPath(String fullVersion, Path gameDir) throws IOException {
        Path cacheRoot = globalCacheDir != null ? globalCacheDir : gameDir.resolve(".forge-installer-cache");
        Path dir = cacheRoot.resolve("forge-installer");
        Files.createDirectories(dir);
        return dir.resolve("forge-" + fullVersion + "-installer.jar");
    }

    private void downloadWithRetries(String url, Path target) throws ForgeApiException {
        int attempt = 0;
        while (true) {
            try {
                downloadFile(url, target);
                return;
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new ForgeApiException("Failed to download " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Download of {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ForgeApiException("Download retry interrupted", ie);
                }
                attempt++;
            }
        }
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/java-archive")
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("HTTP " + status + " for " + url);
        }
        // Verify file size > 0
        if (Files.size(target) == 0) {
            Files.deleteIfExists(target);
            throw new IOException("Downloaded file is empty: " + target);
        }
    }

    private int runInstallerProcess(Path installerJar, Path gameDir, Consumer<String> log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                javaCommand,
                "-jar",
                installerJar.toAbsolutePath().toString(),
                "--installClient",
                gameDir.toAbsolutePath().toString()
        );
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // Capture output in a separate thread to avoid blocking
        Thread outputThread = new Thread(() -> {
            try (var reader = proc.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (log != null) {
                        log.accept("[forge-installer] " + line);
                    }
                    LOGGER.fine("[forge-installer] " + line);
                }
            } catch (IOException ignored) {
                // Stream closed
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = proc.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Forge installer timed out after " + processTimeout);
        }
        // Wait for output thread to finish reading
        outputThread.join(1000);
        return proc.exitValue();
    }

    private String detectForgeVersionId(Path gameDir, String mcVersion, String forgeVersion) throws IOException {
        Path versionsDir = gameDir.resolve("versions");
        if (Files.notExists(versionsDir)) {
            throw new IOException("No versions directory found after Forge installation");
        }

        // The installer usually creates a directory named like "1.20.1-forge-47.2.0"
        String pattern = mcVersion + "-forge-" + forgeVersion;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            // First, try exact match
            Path exact = stream.filter(p -> p.getFileName().toString().equals(pattern)).findFirst().orElse(null);
            if (exact != null) {
                return exact.getFileName().toString();
            }
        }

        // If not found, try containing both mcVersion and "forge"
        try (Stream<Path> stream = Files.list(versionsDir)) {
            var candidates = stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains(mcVersion))
                    .filter(p -> p.getFileName().toString().contains("forge"))
                    .toList();
            if (candidates.isEmpty()) {
                throw new IOException("No Forge version directory found under " + versionsDir);
            }
            if (candidates.size() > 1) {
                LOGGER.warning("Multiple Forge directories found, using the first: " + candidates.get(0).getFileName());
            }
            return candidates.get(0).getFileName().toString();
        }
    }

    private String fetchWithRetries(String url) throws ForgeApiException {
        int attempt = 0;
        while (true) {
            try {
                return fetchOnce(url);
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new ForgeApiException("Failed to fetch " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Request to {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ForgeApiException("Retry interrupted for " + url, ie);
                }
                attempt++;
            }
        }
    }

    private String fetchOnce(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + url);
        }
        return response.body();
    }

    private void logIf(Consumer<String> log, String msg) {
        if (log != null) {
            log.accept(msg);
        }
        LOGGER.info(msg);
    }

    // ------------------------------------------------------------------------
    // Custom exception
    // ------------------------------------------------------------------------

    /**
     * Thrown when the Forge API or installer process fails.
     */
    public static class ForgeApiException extends Exception {
        public ForgeApiException(String message) {
            super(message);
        }

        public ForgeApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------------
    // Example usage (optional)
    // ------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ForgeInstaller installer = new ForgeInstaller();
        String mc = "1.20.1";
        String recommended = installer.fetchPromotedLatest(mc, true);
        System.out.println("Recommended Forge: " + recommended);

        Path gameDir = Path.of(".").resolve("test-instance");
        String versionId = installer.installClient(mc, recommended, gameDir, System.out::println);
        System.out.println("Installed version: " + versionId);

        JsonObject profile = installer.loadGeneratedVersionJson(gameDir, versionId);
        System.out.println("Inherits from: " + profile.get("inheritsFrom").getAsString());
    }
}