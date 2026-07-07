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
 * Talks to the Fabric Meta API – no separate installer jar needed.
 * <p>
 * Improved version with:
 * <ul>
 *   <li>Built‑in HTTP client with configurable timeouts</li>
 *   <li>Automatic retries with exponential backoff</li>
 *   <li>In‑memory caching of loader version lists</li>
 *   <li>Recommendation of the latest stable loader</li>
 *   <li>Proper error handling and logging</li>
 * </ul>
 */
public class FabricInstaller {

    private static final Logger LOGGER = Logger.getLogger(FabricInstaller.class.getName());
    private static final String DEFAULT_BASE_URL = "https://meta.fabricmc.net/v2";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final int maxRetries;
    private final Duration retryBaseDelay;

    // Cache loader version lists per Minecraft version (thread‑safe)
    private final Map<String, List<String>> loaderCache = new ConcurrentHashMap<>();

    /**
     * Creates a new installer with default settings:
     * <ul>
     *   <li>Base URL: {@value DEFAULT_BASE_URL}</li>
     *   <li>Connect/read timeouts: 10 seconds</li>
     *   <li>Max retries: 3</li>
     *   <li>Base retry delay: 1 second</li>
     * </ul>
     */
    public FabricInstaller() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * Creates a new installer with a custom base URL.
     */
    public FabricInstaller(String baseUrl) {
        this(baseUrl,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                3,
                Duration.ofSeconds(1));
    }

    /**
     * Full constructor for complete customisation.
     */
    public FabricInstaller(String baseUrl, HttpClient httpClient, int maxRetries, Duration retryBaseDelay) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        this.maxRetries = maxRetries;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay, "retryBaseDelay must not be null");
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Returns a list of all available loader versions for the given Minecraft version.
     * Results are cached for subsequent calls.
     *
     * @param mcVersion the Minecraft version (e.g. "1.20.1")
     * @return a non‑null, possibly empty list of loader version strings
     * @throws FabricApiException if the API request fails after all retries
     * @throws IllegalArgumentException if mcVersion is null or blank
     */
    public List<String> fetchLoaderVersions(String mcVersion) throws FabricApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        // Check cache first
        List<String> cached = loaderCache.get(mcVersion);
        if (cached != null) {
            return cached;
        }
        // Fetch and store
        List<String> versions = fetchLoaderVersionsUncached(mcVersion);
        loaderCache.put(mcVersion, versions);
        return versions;
    }

    /**
     * Fetches the full version profile JSON for a specific loader version.
     * The returned JSON already contains an "inheritsFrom" field pointing to {@code mcVersion}.
     *
     * @param mcVersion     the Minecraft version
     * @param loaderVersion the Fabric loader version
     * @return a {@link JsonObject} representing the profile
     * @throws FabricApiException if the API request fails
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public JsonObject fetchProfileJson(String mcVersion, String loaderVersion) throws FabricApiException {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion must not be null or blank");
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IllegalArgumentException("loaderVersion must not be null or blank");
        }
        String url = baseUrl + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String json = fetchWithRetries(url);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Recommends a loader version for the given Minecraft version.
     * Currently returns the latest version from the API (last element in the list).
     * Override this method to implement a more sophisticated recommendation strategy.
     *
     * @param mcVersion the Minecraft version
     * @return the recommended loader version string
     * @throws FabricApiException if no versions are available or the API fails
     */
    public String fetchRecommendedLoaderVersion(String mcVersion) throws FabricApiException {
        List<String> versions = fetchLoaderVersions(mcVersion);
        if (versions.isEmpty()) {
            throw new FabricApiException("No loader versions found for Minecraft " + mcVersion);
        }
        // The API returns newest first, so the first is the latest.
        return versions.get(0);
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private List<String> fetchLoaderVersionsUncached(String mcVersion) throws FabricApiException {
        String url = baseUrl + "/versions/loader/" + mcVersion;
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

    private String fetchWithRetries(String url) throws FabricApiException {
        int attempt = 0;
        while (true) {
            try {
                return fetchOnce(url);
            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new FabricApiException("Failed to fetch " + url + " after " + (attempt + 1) + " attempts", e);
                }
                long delay = retryBaseDelay.toMillis() * (long) Math.pow(2, attempt);
                LOGGER.log(Level.WARNING, "Request to {0} failed (attempt {1}), retrying in {2}ms",
                        new Object[]{url, attempt + 1, delay});
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new FabricApiException("Retry interrupted for " + url, ie);
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
     * Thrown when the Fabric Meta API cannot be reached or returns an unexpected response.
     */
    public static class FabricApiException extends Exception {
        public FabricApiException(String message) {
            super(message);
        }

        public FabricApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------------
    // Example usage (optional)
    // ------------------------------------------------------------------------

    public static void main(String[] args) {
        try {
            FabricInstaller installer = new FabricInstaller();
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