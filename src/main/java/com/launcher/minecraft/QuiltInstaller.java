package com.launcher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the Quilt Meta API — no separate installer jar needed, unlike Forge/NeoForge.
 * Quilt's profile JSON already contains an {@code inheritsFrom} pointing at the vanilla
 * version, so it plugs directly into the same {@link GameInstaller} chain used by Fabric.
 *
 * Meta API docs: https://meta.quiltmc.org/
 */
public class QuiltInstaller {

    private static final String META_BASE = "https://meta.quiltmc.org/v3";

    /**
     * Returns the available Quilt Loader versions for the given Minecraft version,
     * newest first (the API returns them newest-first already).
     */
    public List<String> fetchLoaderVersions(String mcVersion)
            throws IOException, InterruptedException {
        String body = HttpUtil.getString(META_BASE + "/versions/loader/" + mcVersion);
        JsonArray arr = JsonUtil.parse(body).getAsJsonArray();
        List<String> versions = new ArrayList<>();
        for (var el : arr) {
            versions.add(
                    el.getAsJsonObject()
                      .getAsJsonObject("loader")
                      .get("version")
                      .getAsString());
        }
        return versions;
    }

    /**
     * Fetches the ready-to-merge version profile JSON for a specific
     * Minecraft + Quilt Loader combination.
     * The returned object already has {@code "inheritsFrom": mcVersion}.
     */
    public JsonObject fetchProfileJson(String mcVersion, String loaderVersion)
            throws IOException, InterruptedException {
        String url = META_BASE + "/versions/loader/"
                + mcVersion + "/" + loaderVersion + "/profile/json";
        String body = HttpUtil.getString(url);
        return JsonUtil.parse(body).getAsJsonObject();
    }
}
