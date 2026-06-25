package com.launcher.minecraft;

import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VersionManifestService {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static String cachedManifestBody = null;
    private static long cacheTime = 0;

    private static synchronized String getManifestBody() throws IOException, InterruptedException {
        // Cache for 5 minutes (300,000 ms)
        if (cachedManifestBody == null || System.currentTimeMillis() - cacheTime > 300000) {
            cachedManifestBody = HttpUtil.getString(MANIFEST_URL);
            cacheTime = System.currentTimeMillis();
        }
        return cachedManifestBody;
    }

    /** id -> version json url, in manifest order (latest first). */
    public Map<String, String> fetchVersionUrls() throws IOException, InterruptedException {
        String body = getManifestBody();
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        for (var el : root.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            result.put(v.get("id").getAsString(), v.get("url").getAsString());
        }
        return result;
    }

    public List<String> fetchAllVersionIds() throws IOException, InterruptedException {
        String body = getManifestBody();
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        return root.getAsJsonArray("versions").asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsString())
                .collect(Collectors.toList());
    }

    public List<String> fetchReleaseVersionIds() throws IOException, InterruptedException {
        String body = getManifestBody();
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        return root.getAsJsonArray("versions").asList().stream()
                .map(e -> e.getAsJsonObject())
                .filter(v -> v.get("type").getAsString().equals("release"))
                .map(v -> v.get("id").getAsString())
                .collect(Collectors.toList());
    }

    public String fetchLatestReleaseId() throws IOException, InterruptedException {
        String body = HttpUtil.getString(MANIFEST_URL);
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        return root.getAsJsonObject("latest").get("release").getAsString();
    }

    public JsonObject fetchVersionJson(String url) throws IOException, InterruptedException {
        String body = HttpUtil.getString(url);
        return JsonUtil.parse(body).getAsJsonObject();
    }
}