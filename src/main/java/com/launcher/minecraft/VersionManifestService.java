package com.launcher.minecraft;

import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class VersionManifestService {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** id -> version json url, in manifest order (latest first). */
    public Map<String, String> fetchVersionUrls() throws IOException, InterruptedException {
        String body = HttpUtil.getString(MANIFEST_URL);
        JsonObject root = JsonUtil.parse(body).getAsJsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        for (var el : root.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            result.put(v.get("id").getAsString(), v.get("url").getAsString());
        }
        return result;
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
