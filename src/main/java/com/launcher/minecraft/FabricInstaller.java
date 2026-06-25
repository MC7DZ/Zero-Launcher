package com.launcher.minecraft;

import com.google.gson.JsonObject;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;

/** Talks to the Fabric Meta API - no separate installer jar needed, unlike Forge. */
public class FabricInstaller {

    public List<String> fetchLoaderVersions(String mcVersion) throws IOException, InterruptedException {
        String body = HttpUtil.getString("https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
        JsonArray arr = JsonUtil.parse(body).getAsJsonArray();
        List<String> versions = new ArrayList<>();
        for (var el : arr) {
            versions.add(el.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
        }
        return versions;
    }

    /** Returns a ready-to-merge version json (it already has inheritsFrom = mcVersion). */
    public JsonObject fetchProfileJson(String mcVersion, String loaderVersion) throws IOException, InterruptedException {
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String body = HttpUtil.getString(url);
        return JsonUtil.parse(body).getAsJsonObject();
    }
}
