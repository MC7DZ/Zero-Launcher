package com.launcher.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class JsonUtil {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> T fromString(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    public static <T> T readFile(Path path, Class<T> type, T fallback) {
        try {
            if (!Files.exists(path)) return fallback;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) return fallback;
            T value = GSON.fromJson(content, type);
            return value != null ? value : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }

    public static void writeFile(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path, e);
        }
    }
}
