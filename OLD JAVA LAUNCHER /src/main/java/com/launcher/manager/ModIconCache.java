package com.launcher.manager;

import com.launcher.util.HttpUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists downloaded mod icon images to disk in the launcher's shared cache folder
 * (".zerolauncher/cache/mod_icons/" on Linux, equivalent app-data cache folder elsewhere —
 * see {@link LauncherPaths#sharedCache()}), keyed by a hash of the icon's source URL.
 * <p>
 * Combined with {@link ModMetadataCache} (which caches names/versions/descriptions), this
 * means the Mods tab can render everything — icon, name, version, description — straight
 * from local disk on subsequent launches, without re-downloading anything from the network
 * unless an icon isn't cached yet or the cached file is unreadable/corrupt.
 */
public class ModIconCache {

    private static ModIconCache instance;

    private final Path dir;
    private final ConcurrentHashMap<String, byte[]> memCache = new ConcurrentHashMap<>();

    private ModIconCache() {
        this.dir = LauncherPaths.sharedCache().resolve("mod_icons");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // Best-effort; if this fails we just won't be able to persist to disk.
        }
    }

    public static synchronized ModIconCache getInstance() {
        if (instance == null) {
            instance = new ModIconCache();
        }
        return instance;
    }

    /**
     * Returns the icon's raw image bytes, loading instantly from the local on-disk cache
     * when available. Only reaches out to the network when the icon hasn't been cached yet
     * (or the cached file has gone missing/corrupt).
     */
    public byte[] getOrFetch(String url) throws IOException, InterruptedException {
        byte[] mem = memCache.get(url);
        if (mem != null) return mem;

        Path file = fileFor(url);
        if (Files.exists(file)) {
            try {
                byte[] onDisk = Files.readAllBytes(file);
                if (onDisk.length > 0) {
                    memCache.put(url, onDisk);
                    return onDisk;
                }
            } catch (IOException ignored) {
                // Fall through and re-download if the cached file can't be read.
            }
        }

        byte[] fetched = HttpUtil.getBytes(url);
        memCache.put(url, fetched);
        try {
            Files.write(file, fetched);
        } catch (IOException ignored) {
            // Cache is best-effort; failing to persist shouldn't break icon loading.
        }
        return fetched;
    }

    private Path fileFor(String url) {
        return dir.resolve(hash(url) + ".img");
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Extremely unlikely (SHA-1 is always available); fall back to a simple hash.
            return Integer.toHexString(s.hashCode());
        }
    }
}
