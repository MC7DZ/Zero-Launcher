package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Talks to the Quilt Meta API – no separate installer jar needed, unlike Forge/NeoForge.
 * Quilt's profile JSON already contains an {@code inheritsFrom} pointing at the vanilla
 * version, so it plugs directly into the same {@link GameInstaller} chain used by Fabric.
 * <p>
 * Improved version with:
 * <ul>
 *   <li>Built‑in HTTP client with configurable timeouts</li>
 *   <li>Automatic retries with exponential backoff</li>
 *   <li>In‑memory caching of loader version lists</li>
 *   <li>Recommendation of the latest loader</li>
 *   <li>Proper error handling and logging</li>
 * </ul>
 *
 * Meta API docs: https://meta.quiltmc.org/v3
 */
public class QuiltInstaller {

    private static final Logger LOGGER = Logger.getLogger(QuiltInstaller.class.getName());
    private static final String DEFAULT_META_BASE = "https://meta.quiltmc.org/v3";

    private final HttpClient httpClient;
    private final String metaBase;
    private final int maxRetries;
    private final Duration retryBaseDelay;

    // Cache loader version lists per Minecraft version (thread‑safe)
    private final Map<String, List<String>> loaderCache = new ConcurrentHashMap<>();

    /**
     * Creates a new installer with default settings:
     * <ul>
     *   <li>Meta base URL: {@value DEFAULT_META_BASE}</li>
     *   <li>Connect/read timeouts: 10 seconds</li>
     *   <li>Max retries: 3</li>
     *   <li>Base retry delay: 1 second</li>
     * </ul>
     */
    public QuiltInstaller() {
        this(DEFAULT_META_BASE);
    }

    /**
     * Creates a new installer with a custom meta base URL.
     */
    public QuiltInstaller(String metaBase) {
        this(metaBase,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                3,
                Duration.ofSeconds(1));
    }

    /**
     * Full constructor for complete customisation.
     *
     * @param metaBase       base URL of the Quilt Meta API (e.g. "https://meta.quiltmc.org/v3")
     * @param httpClient     the HTTP client to use
     * @param maxRetries     number of retries on failure
     * @param retryBaseDelay initial delay between retries (exponential backoff)
     */
    public QuiltInstaller(String metaBase, HttpClient httpClient, int maxRetries, Duration retryBaseDelay) {
        this.metaBase = Objects.requireNonNull(metaBase, "metaBase must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        this.maxRetries = maxRetries;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay, "retryBaseDelay must not be null");
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Returns the available Quilt Loader versions for the given Minecraft version,
     * newest first (the API returns them newest-first already).
     * Results are cached for subsequent calls.
     *
     * @param mcVersion the Minecraft version (e.g. "1.20.1")
     * @return a non‑null, possibly empty list of loader version strings
     * @throws QuiltApiException if the API request fails after all retries
     * @throws IllegalArgumentException if mcVersion is null or blank
     */
    public List<String> fetchLoaderVersions(String mcVersion) throws QuiltApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        return loaderCache.computeIfAbsent(mcVersion, v -> {
            try {
                return fetchLoaderVersionsUncached(v);
            } catch (QuiltApiException e) {
                // Wrap in RuntimeException because computeIfAbsent cannot throw checked exceptions
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Fetches the ready-to-merge version profile JSON for a specific
     * Minecraft + Quilt Loader combination.
     * The returned object already has {@code "inheritsFrom": mcVersion}.
     *
     * @param mcVersion     the Minecraft version
     * @param loaderVersion the Quilt Loader version
     * @return a {@link JsonObject} representing the profile
     * @throws QuiltApiException if the API request fails
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public JsonObject fetchProfileJson(String mcVersion, String loaderVersion) throws QuiltApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IllegalArgumentException("loaderVersion must not be null or blank");
        }
        String url = metaBase + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String json = fetchWithRetries(url);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Recommends a loader version for the given Minecraft version.
     * Returns the first (latest) version from the list.
     *
     * @param mcVersion the Minecraft version
     * @return the recommended loader version string
     * @throws QuiltApiException if no versions are available or the API fails
     */
    public String fetchRecommendedLoaderVersion(String mcVersion) throws QuiltApiException {
        List<String> versions = fetchLoaderVersions(mcVersion);
        if (versions.isEmpty()) {
            throw new QuiltApiException("No loader versions found for Minecraft " + mcVersion);
        }
        return versions.get(0);
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private List<String> fetchLoaderVersionsUncached(String mcVersion) throws QuiltApiException {
        String url = metaBase + "/versions/loader/" + mcVersion;
        String json = fetchWithRetries(url);
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<String> versions = StreamSupport.stream(array.spliterator(), false)
                .map(el -> el.getAsJsonObject()
                        .getAsJsonObject("loader")
                        .get("version")
                        .getAsString())
                .collect(Collectors.toList());
        LOGGER.log(Level.FINE, "Fetched {0} loader versions for {1}", new Object[]{versions.size(), mcVersion});
        return Collections.unmodifiableList(versions);
    }

    private String fetchWithRetries(String url) throws QuiltApiException {
        int attempt = 0;
        while (true) {
            try {
                return fetchOnce(url);
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new QuiltApiException("Failed to fetch " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Request to {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new QuiltApiException("Retry interrupted for " + url, ie);
                }
                attempt++;
            }
        }
    }

    private String fetchOnce(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
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

    // ------------------------------------------------------------------------
    // Custom exception
    // ------------------------------------------------------------------------

    /**
     * Thrown when the Quilt Meta API cannot be reached or returns an unexpected response.
     */
    public static class QuiltApiException extends Exception {
        public QuiltApiException(String message) {
            super(message);
        }

        public QuiltApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------------
    // Example usage (optional)
    // ------------------------------------------------------------------------

    public static void main(String[] args) {
        try {
            QuiltInstaller installer = new QuiltInstaller();
            String mc = "1.20.1";
            String recommended = installer.fetchRecommendedLoaderVersion(mc);
            System.out.println("Recommended loader for " + mc + ": " + recommended);

            JsonObject profile = installer.fetchProfileJson(mc, recommended);
            System.out.println("Profile inherits from: " + profile.get("inheritsFrom").getAsString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}