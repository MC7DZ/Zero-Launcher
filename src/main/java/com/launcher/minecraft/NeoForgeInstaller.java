package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;   // <-- MISSING IMPORT ADDED

/**
 * NeoForge installer – mirrors the ForgeInstaller approach:
 * download the official installer jar from NeoForge's Maven and run it
 * in headless {@code --install-client} mode against the instance game directory.
 * <p>
 * Improved version with:
 * <ul>
 *   <li>Built‑in HTTP client with configurable timeouts and retries</li>
 *   <li>Configurable Java command and process timeout</li>
 *   <li>Dedicated installer cache (global or per‑instance)</li>
 *   <li>Better version sorting and filtering</li>
 *   <li>Robust version‑ID detection from the created version directory</li>
 *   <li>Proper error handling and logging</li>
 * </ul>
 *
 * NeoForge Maven: https://maven.neoforged.net/releases/net/neoforged/neoforge/
 * Version list API: https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge
 */
public class NeoForgeInstaller {

    private static final Logger LOGGER = Logger.getLogger(NeoForgeInstaller.class.getName());
    private static final String DEFAULT_MAVEN_BASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge";
    private static final String DEFAULT_VERSIONS_API =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";

    private final HttpClient httpClient;
    private final String mavenBase;
    private final String versionsApiUrl;
    private final String javaCommand;
    private final Duration processTimeout;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private final Path globalCacheDir; // optional, shared across instances

    /**
     * Creates a new installer with default settings:
     * <ul>
     *   <li>Maven base: {@value DEFAULT_MAVEN_BASE}</li>
     *   <li>Versions API: {@value DEFAULT_VERSIONS_API}</li>
     *   <li>Java command: "java" (from PATH)</li>
     *   <li>Process timeout: 5 minutes</li>
     *   <li>Max download retries: 3</li>
     *   <li>Retry base delay: 1 second</li>
     *   <li>No global cache directory (cache inside gameDir)</li>
     * </ul>
     */
    public NeoForgeInstaller() {
        this(DEFAULT_MAVEN_BASE, DEFAULT_VERSIONS_API, "java",
                Duration.ofMinutes(5), 3, Duration.ofSeconds(1), null);
    }

    /**
     * Full constructor for complete customisation.
     *
     * @param mavenBase       base Maven URL (without trailing slash)
     * @param versionsApiUrl  URL of the versions listing API
     * @param javaCommand     path to the Java executable
     * @param processTimeout  maximum time to wait for the installer to finish
     * @param maxRetries      number of download retries
     * @param retryBaseDelay  initial delay between retries (exponential backoff)
     * @param globalCacheDir  optional directory to store installer jars (shared between instances)
     */
    public NeoForgeInstaller(String mavenBase, String versionsApiUrl, String javaCommand,
                             Duration processTimeout, int maxRetries, Duration retryBaseDelay,
                             Path globalCacheDir) {
        this.mavenBase = Objects.requireNonNull(mavenBase);
        this.versionsApiUrl = Objects.requireNonNull(versionsApiUrl);
        this.javaCommand = Objects.requireNonNull(javaCommand);
        this.processTimeout = Objects.requireNonNull(processTimeout);
        this.maxRetries = maxRetries;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay);
        this.globalCacheDir = globalCacheDir;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Fetches available NeoForge versions for the given Minecraft version.
     * NeoForge version strings start with the minor MC version (e.g. "21.1.x" for MC 1.21.1).
     * The list is sorted descending (newest first).
     *
     * @param mcVersion the Minecraft version (e.g. "1.21.1")
     * @return a non‑null, possibly empty list of version strings
     * @throws NeoForgeApiException if the API request fails after all retries
     * @throws IllegalArgumentException if mcVersion is null or blank
     */
    public List<String> fetchVersions(String mcVersion) throws NeoForgeApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        String json = fetchWithRetries(versionsApiUrl);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("versions");
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }

        String prefix = derivePrefix(mcVersion);
        List<String> allVersions = new ArrayList<>();
        for (var el : arr) {
            allVersions.add(el.getAsString());
        }

        // Filter by prefix, then sort descending (natural order, newest first)
        List<String> filtered = allVersions.stream()
                .filter(v -> v.startsWith(prefix))
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());

        // Fallback: if no match, return all versions sorted descending
        if (filtered.isEmpty()) {
            LOGGER.log(Level.WARNING, "No NeoForge versions found with prefix {0}, returning all", prefix);
            filtered = allVersions.stream()
                    .sorted(Collections.reverseOrder())
                    .collect(Collectors.toList());
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Downloads and runs the NeoForge installer against {@code gameDir}.
     * The installer jar is cached in {@code <gameDir>/.neoforge-installer-cache}
     * (or the global cache if set). The process is run with the configured Java command and timeout.
     *
     * @param mcVersion         the Minecraft version (used for logging and detection)
     * @param neoForgeVersion   the NeoForge version (e.g. "21.1.77")
     * @param gameDir           the root directory of the Minecraft instance
     * @param log               a consumer for logging progress messages (may be null)
     * @return the version id that NeoForge registered (e.g. "neoforge-21.1.77")
     * @throws NeoForgeApiException if download or process execution fails
     * @throws IOException          if filesystem operations fail
     * @throws InterruptedException if the download or process is interrupted
     */
    public String installClient(String mcVersion, String neoForgeVersion, Path gameDir,
                                Consumer<String> log)
            throws NeoForgeApiException, IOException, InterruptedException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        if (neoForgeVersion == null || neoForgeVersion.isBlank()) {
            throw new IllegalArgumentException("neoForgeVersion must not be null or blank");
        }
        if (gameDir == null) {
            throw new IllegalArgumentException("gameDir must not be null");
        }

        Path installerJar = resolveInstallerPath(neoForgeVersion, gameDir);
        if (Files.notExists(installerJar)) {
            String installerUrl = mavenBase + "/" + neoForgeVersion
                    + "/neoforge-" + neoForgeVersion + "-installer.jar";
            logIf(log, "Downloading NeoForge installer for " + neoForgeVersion + " …");
            downloadWithRetries(installerUrl, installerJar);
        } else {
            logIf(log, "Using cached NeoForge installer: " + installerJar);
        }

        ensureLauncherProfile(gameDir);

        logIf(log, "Running NeoForge installer (this may take a minute) …");
        int exitCode = runInstallerProcess(installerJar, gameDir, log);
        if (exitCode != 0) {
            throw new NeoForgeApiException("NeoForge installer exited with code " + exitCode);
        }

        // Locate the created version directory
        String versionId = detectNeoForgeVersionId(gameDir, neoForgeVersion);
        logIf(log, "NeoForge installation complete. Version ID: " + versionId);
        return versionId;
    }

    /**
     * Loads the version JSON that the NeoForge installer generated.
     *
     * @param gameDir   the game directory
     * @param versionId the version id (as returned by {@link #installClient})
     * @return the parsed {@link JsonObject}
     * @throws IOException if the JSON file cannot be read
     */
    public JsonObject loadGeneratedVersionJson(Path gameDir, String versionId) throws IOException {
        Path jsonPath = gameDir.resolve("versions")
                .resolve(versionId)
                .resolve(versionId + ".json");
        if (Files.notExists(jsonPath)) {
            throw new IOException("Version JSON not found: " + jsonPath);
        }
        String content = Files.readString(jsonPath);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    /**
     * The official NeoForge (and Forge) installer jars refuse to run in
     * {@code --install-client} mode unless the target directory already
     * looks like a real {@code .minecraft} folder — specifically, they check
     * for the presence of {@code launcher_profiles.json}. Since this launcher
     * manages its own instance/profile data and never writes that file, the
     * installer was failing with "There is no minecraft launcher profile in
     * ..., you need to run the launcher first!" (exit code 1). Writing a
     * minimal stub here satisfies that check without affecting anything else.
     */
    private void ensureLauncherProfile(Path gameDir) throws IOException {
        Path profile = gameDir.resolve("launcher_profiles.json");
        if (Files.exists(profile)) {
            return;
        }
        Files.createDirectories(gameDir);
        String stub = "{\"profiles\":{},\"settings\":{},\"version\":3}";
        Files.writeString(profile, stub);
    }

    private Path resolveInstallerPath(String neoForgeVersion, Path gameDir) throws IOException {
        Path cacheRoot = globalCacheDir != null ? globalCacheDir : gameDir.resolve(".neoforge-installer-cache");
        Path dir = cacheRoot.resolve("neoforge-installer");
        Files.createDirectories(dir);
        return dir.resolve("neoforge-" + neoForgeVersion + "-installer.jar");
    }

    private void downloadWithRetries(String url, Path target) throws NeoForgeApiException {
        int attempt = 0;
        while (true) {
            try {
                downloadFile(url, target);
                return;
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new NeoForgeApiException("Failed to download " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Download of {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new NeoForgeApiException("Download retry interrupted", ie);
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
                "--install-client",
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
                        log.accept("[neoforge-installer] " + line);
                    }
                    LOGGER.fine("[neoforge-installer] " + line);
                }
            } catch (IOException ignored) {
                // Stream closed
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = proc.waitFor(processTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("NeoForge installer timed out after " + processTimeout);
        }
        // Wait for output thread to finish reading
        outputThread.join(1000);
        return proc.exitValue();
    }

    private String detectNeoForgeVersionId(Path gameDir, String neoForgeVersion) throws IOException {
        Path versionsDir = gameDir.resolve("versions");
        if (Files.notExists(versionsDir)) {
            throw new IOException("No versions directory found after NeoForge installation");
        }

        // Expected pattern: "neoforge-21.1.77" (or maybe "neoforge-21.1.77-installer"?? but usually just neoforge-version)
        // The installer creates a directory with the exact version id, usually "neoforge-" + neoForgeVersion.
        String expected = "neoforge-" + neoForgeVersion;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            // First, try exact match
            Path exact = stream.filter(p -> p.getFileName().toString().equals(expected)).findFirst().orElse(null);
            if (exact != null) {
                return exact.getFileName().toString();
            }
        }

        // If not found, try any directory containing "neoforge" and the version
        try (Stream<Path> stream = Files.list(versionsDir)) {
            var candidates = stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains("neoforge"))
                    .filter(p -> p.getFileName().toString().contains(neoForgeVersion))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                throw new IOException("No NeoForge version directory found under " + versionsDir);
            }
            if (candidates.size() > 1) {
                LOGGER.warning("Multiple NeoForge directories found, using the first: " + candidates.get(0).getFileName());
            }
            return candidates.get(0).getFileName().toString();
        }
    }

    private String fetchWithRetries(String url) throws NeoForgeApiException {
        int attempt = 0;
        while (true) {
            try {
                return fetchOnce(url);
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new NeoForgeApiException("Failed to fetch " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Request to {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new NeoForgeApiException("Retry interrupted for " + url, ie);
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

    /**
     * Converts a Minecraft version string to the NeoForge version prefix.
     * "1.21.1"  → "21.1."
     * "1.20.4"  → "20.4."
     * "1.21"    → "21."
     * "1.20"    → "20."
     */
    private String derivePrefix(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length >= 3) {
            return parts[1] + "." + parts[2] + ".";
        } else if (parts.length == 2) {
            return parts[1] + ".";
        }
        return ""; // fallback
    }

    // ------------------------------------------------------------------------
    // Custom exception
    // ------------------------------------------------------------------------

    /**
     * Thrown when the NeoForge API or installer process fails.
     */
    public static class NeoForgeApiException extends Exception {
        public NeoForgeApiException(String message) {
            super(message);
        }

        public NeoForgeApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------------
    // Example usage (optional)
    // ------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        NeoForgeInstaller installer = new NeoForgeInstaller();
        String mc = "1.21.1";
        List<String> versions = installer.fetchVersions(mc);
        System.out.println("Available versions: " + versions.stream().limit(5).collect(Collectors.toList()));

        String latest = versions.get(0);
        Path gameDir = Path.of(".").resolve("test-neoforge-instance");
        String versionId = installer.installClient(mc, latest, gameDir, System.out::println);
        System.out.println("Installed version ID: " + versionId);

        JsonObject profile = installer.loadGeneratedVersionJson(gameDir, versionId);
        System.out.println("Inherits from: " + profile.get("inheritsFrom").getAsString());
    }
}