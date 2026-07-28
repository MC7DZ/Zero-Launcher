package com.launcher.util;

import com.launcher.manager.DownloadManager;
import com.launcher.manager.LauncherPaths;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Auto-installs a Java runtime when the launcher can't find one it needs to start an instance.
 * <p>
 * Downloads Azul Zulu builds of OpenJDK (chosen because they ship as plain zip/tar.gz archives
 * with no installer/admin-rights required, unlike Oracle's) and unpacks them into
 * {@code <launcher root>/java versions/java-<major>/}, e.g.
 * {@code ~/.zerolauncher/java versions/java-17} on Linux or
 * {@code %appdata%\Zero Launcher\java versions\java-17} on Windows. Once installed a version is
 * reused on every future launch, so this only runs once per major version per machine.
 */
public final class JavaInstaller {

    private JavaInstaller() {}

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    // Java 8 is no longer offered through Azul's public metadata API in a way that's simple to
    // query reliably, so we pin the known-good Zulu 8 build the launcher was built against
    // (this is also exactly what's needed for legacy LWJGL2 versions like 1.12.2 and earlier).
    private static final String JAVA8_WINDOWS_URL =
            "https://cdn.azul.com/zulu/bin/zulu8.96.0.19-ca-jdk8.0.502-win_x64.zip";
    private static final String JAVA8_LINUX_URL =
            "https://cdn.azul.com/zulu/bin/zulu8.96.0.19-ca-jdk8.0.502-linux_x64.tar.gz";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the path to a {@code java}/{@code java.exe} executable for the requested major
     * version (8, 17, 21, ...), installing it first if it isn't already present under the
     * launcher's "java versions" folder. Safe to call repeatedly - later calls for an already
     * installed version just return the cached path immediately.
     *
     * @throws IOException if downloading or extracting the runtime fails.
     */
    public static synchronized String ensureJava(int majorVersion, Consumer<String> log) throws IOException {
        Path installDir = LauncherPaths.javaVersionsDir().resolve("java-" + majorVersion);
        Path existing = findJavaExecutable(installDir);
        if (existing != null) {
            return existing.toString();
        }

        if (log != null) {
            log.accept("No Java " + majorVersion + " installation found - downloading one automatically "
                    + "(this only happens once)...");
        }

        String downloadUrl = resolveDownloadUrl(majorVersion);
        boolean isZip = downloadUrl.endsWith(".zip");
        Path archive = Files.createTempFile("zerolauncher-java-" + majorVersion + "-", isZip ? ".zip" : ".tar.gz");

        String dlId = DownloadManager.getInstance().start("Java " + majorVersion + " runtime");
        try {
            DownloadManager.getInstance().bindThread(dlId);
            downloadWithProgress(downloadUrl, archive, dlId, log);

            DownloadManager.getInstance().update(dlId, "Extracting...", 100);
            if (log != null) log.accept("Extracting Java " + majorVersion + "...");

            Files.createDirectories(installDir);
            if (isZip) {
                extractZip(archive, installDir);
            } else {
                extractTarGz(archive, installDir);
            }
            flattenSingleSubfolder(installDir);
            makeExecutableRecursively(installDir);

            Path exe = findJavaExecutable(installDir);
            if (exe == null) {
                throw new IOException("Downloaded Java " + majorVersion + " archive but couldn't find a java executable inside it.");
            }

            DownloadManager.getInstance().finish(dlId, "Java " + majorVersion + " installed");
            if (log != null) log.accept("Java " + majorVersion + " installed to " + installDir);
            return exe.toString();
        } catch (IOException | InterruptedException e) {
            DownloadManager.getInstance().fail(dlId, "Failed: " + e.getMessage());
            // Don't leave a half-extracted install around to be mistaken for a good one next time.
            deleteRecursively(installDir);
            if (e instanceof IOException io) throw io;
            throw new IOException("Java download interrupted", e);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** Same as {@link #ensureJava(int, Consumer)} but never throws - returns null on failure so
     *  callers can fall back to whatever behavior they had before this existed. */
    public static String ensureJavaQuietly(int majorVersion, Consumer<String> log) {
        try {
            return ensureJava(majorVersion, log);
        } catch (Exception e) {
            if (log != null) log.accept("Automatic Java " + majorVersion + " install failed: " + e.getMessage());
            return null;
        }
    }

    // ── URL resolution ──────────────────────────────────────────────────

    private static String resolveDownloadUrl(int majorVersion) throws IOException {
        if (majorVersion == 8) {
            return IS_WINDOWS ? JAVA8_WINDOWS_URL : JAVA8_LINUX_URL;
        }
        // For everything else, ask Azul's Metadata API for the current "latest" build so we
        // don't have to keep hardcoded URLs (which go stale every time Azul ships an update)
        // in sync by hand.
        String os = IS_WINDOWS ? "windows" : "linux";
        String archiveType = IS_WINDOWS ? "zip" : "tar.gz";
        String api = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=" + majorVersion
                + "&os=" + os
                + "&arch=x64"
                + "&archive_type=" + archiveType
                + "&java_package_type=jdk"
                + "&javafx_bundled=false"
                + "&release_status=ga"
                + "&availability_types=CA"
                + "&latest=true"
                + "&page=1&page_size=1";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Azul metadata API returned HTTP " + resp.statusCode());
            }
            Matcher m = Pattern.compile("\"download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            if (m.find()) {
                return m.group(1);
            }
            throw new IOException("Azul metadata API response didn't contain a download_url for Java " + majorVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving Java " + majorVersion + " download URL", e);
        }
    }

    // ── Download ─────────────────────────────────────────────────────────

    private static void downloadWithProgress(String url, Path target, String dlId, Consumer<String> log)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "zerolauncher/1.0")
                .timeout(Duration.ofMinutes(10))
                .GET().build();
        HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Failed to download Java runtime: HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        long downloaded = 0;
        try (InputStream in = new BufferedInputStream(resp.body());
             java.io.OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (DownloadManager.getInstance().isCancelled(dlId)) {
                    throw new InterruptedException("Download cancelled");
                }
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = (int) Math.min(99, (downloaded * 100) / total);
                    DownloadManager.getInstance().update(dlId,
                            "Downloading... " + (downloaded / (1024 * 1024)) + "MB / " + (total / (1024 * 1024)) + "MB", pct);
                } else {
                    DownloadManager.getInstance().update(dlId, "Downloading... " + (downloaded / (1024 * 1024)) + "MB");
                }
            }
        }
    }

    // ── Extraction ───────────────────────────────────────────────────────

    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = safeResolve(destDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    /** No pure-Java tar.gz reader in the JDK standard library, so we shell out to {@code tar},
     *  which is present on essentially every Linux/macOS system (this path is never used on
     *  Windows - see {@link #resolveDownloadUrl}, which requests a .zip there instead). */
    private static void extractTarGz(Path tarGzFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGzFile.toAbsolutePath().toString(),
                "-C", destDir.toAbsolutePath().toString(), "--strip-components=0");
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            proc.getInputStream().readAllBytes(); // drain to avoid the process blocking on a full pipe
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("tar exited with code " + code + " while extracting Java runtime");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }
    }

    /** Guards against zip-slip (entries whose name tries to escape the destination directory). */
    private static Path safeResolve(Path destDir, String entryName) throws IOException {
        Path resolved = destDir.resolve(entryName).normalize();
        if (!resolved.startsWith(destDir.normalize())) {
            throw new IOException("Zip entry outside target directory: " + entryName);
        }
        return resolved;
    }

    /** Zulu archives contain a single top-level folder (e.g. {@code zulu17...-linux_x64/}). Move
     *  its contents up a level so the java executable ends up directly at
     *  {@code java versions/java-<major>/bin/java} regardless of Azul's exact folder naming. */
    private static void flattenSingleSubfolder(Path installDir) throws IOException {
        try (var stream = Files.list(installDir)) {
            var children = stream.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                Path nested = children.get(0);
                try (var nestedStream = Files.list(nested)) {
                    for (Path child : nestedStream.toList()) {
                        Files.move(child, installDir.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.delete(nested);
            }
        }
    }

    private static void makeExecutableRecursively(Path installDir) {
        if (IS_WINDOWS) return; // Windows doesn't use POSIX exec bits.
        Path bin = installDir.resolve("bin");
        if (!Files.isDirectory(bin)) return;
        try (var stream = Files.list(bin)) {
            for (Path exe : stream.toList()) {
                try {
                    Files.setPosixFilePermissions(exe, EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
                } catch (IOException ignored) {
                    // Non-POSIX filesystem or permission issue - not fatal, launch may still work.
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Path findJavaExecutable(Path installDir) {
        Path direct = installDir.resolve("bin").resolve(IS_WINDOWS ? "java.exe" : "java");
        if (Files.isRegularFile(direct)) return direct;
        // Handle the case where a previous run left the nested Zulu folder unflattened.
        if (Files.isDirectory(installDir)) {
            try (var stream = Files.walk(installDir, 3)) {
                return stream.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().equals(IS_WINDOWS ? "java.exe" : "java")
                                && p.getParent().getFileName().toString().equals("bin"))
                        .findFirst().orElse(null);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }
}
