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

/** Thin wrapper around java.net.http so the rest of the codebase doesn't repeat boilerplate. */
public class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static String getString(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "minecraft-launcher/1.0")
                .GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    public static String getStringAuthorized(String url, String bearerToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "minecraft-launcher/1.0")
                .header("Authorization", "Bearer " + bearerToken)
                .GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    public static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "minecraft-launcher/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("POST " + url + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** Returns raw body even on non-2xx; some OAuth endpoints use 4xx for "pending" style responses. */
    public static String postFormRaw(String url, String formBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "minecraft-launcher/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    public static void downloadToFile(String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "minecraft-launcher/1.0")
                .GET().build();
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".part");
        HttpResponse<Path> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed " + url + " -> HTTP " + resp.statusCode());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
