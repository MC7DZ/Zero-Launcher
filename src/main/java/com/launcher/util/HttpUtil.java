package com.launcher.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

public final class HttpUtil {

    private static final String USER_AGENT = "minecraft-launcher/1.0";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // --- Helper for consistent request headers ---
    private static HttpRequest.Builder baseRequestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT);
    }

    // --- Standard HTTP Requests ---
    public static String getString(String url) throws IOException, InterruptedException {
        HttpRequest req = baseRequestBuilder(url).GET().build();
        return sendAndValidate(req, HttpResponse.BodyHandlers.ofString());
    }

    public static String getStringAuthorized(String url, String bearerToken) throws IOException, InterruptedException {
        HttpRequest req = baseRequestBuilder(url)
                .header("Authorization", "Bearer " + bearerToken)
                .GET().build();
        return sendAndValidate(req, HttpResponse.BodyHandlers.ofString());
    }

    public static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = baseRequestBuilder(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return sendAndValidate(req, HttpResponse.BodyHandlers.ofString());
    }

    public static String postFormRaw(String url, String formBody) throws IOException, InterruptedException {
        HttpRequest req = baseRequestBuilder(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // --- Validation Logic ---
    private static <T> T sendAndValidate(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        HttpResponse<T> resp = CLIENT.send(req, handler);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Request to " + req.uri() + " failed with HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    // --- File Downloads ---
    public static void downloadFile(String url, Path target, Consumer<String> statusCallback) throws IOException, InterruptedException {
        if (statusCallback != null) statusCallback.accept("Downloading " + target.getFileName() + "...");
        downloadToFile(url, target);
    }

    public static void downloadToFile(String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".part");

        for (int i = 0; i < 3; i++) {
            try {
                HttpRequest req = baseRequestBuilder(url).timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<Path> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(tmp));

                if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                if (i == 2) throw e;
                Thread.sleep(1000); // Wait longer between retries
            }
        }
    }

    public static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}