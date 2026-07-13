package com.launcher.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the local machine for installed Java runtimes/JDKs so the user can pick
 * one from a dropdown instead of typing a path by hand. Works on both Windows
 * and Linux by probing the common install locations used by the major
 * distributions (Adoptium/Temurin, Zulu, Microsoft, Oracle, Corretto, package
 * managers, IDE-managed JDKs, SDKMAN, etc.) plus JAVA_HOME and PATH.
 */
public final class JavaInstallationFinder {

    private JavaInstallationFinder() {
    }

    /** A single discovered Java runtime. */
    public static final class JavaInstallation {
        public final String displayName; // e.g. "Java 21.0.3 (Temurin) - /usr/lib/jvm/temurin-21-jdk"
        public final String javaExecutablePath; // full path to the java/java.exe binary

        public JavaInstallation(String displayName, String javaExecutablePath) {
            this.displayName = displayName;
            this.javaExecutablePath = javaExecutablePath;
        }

        @Override
        public String toString() {
            return displayName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JavaInstallation)) return false;
            return javaExecutablePath.equals(((JavaInstallation) o).javaExecutablePath);
        }

        @Override
        public int hashCode() {
            return javaExecutablePath.hashCode();
        }
    }

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    /**
     * Scans common installation directories (and JAVA_HOME/PATH) for the current
     * OS and returns a de-duplicated, sorted list of discovered Java runtimes.
     * This does spawn short-lived "java -version" processes to label each entry,
     * so callers should invoke it off the Event Dispatch Thread.
     */
    public static List<JavaInstallation> findInstallations() {
        Map<String, JavaInstallation> found = new LinkedHashMap<>(); // key = canonical exe path

        List<Path> candidateHomes = new ArrayList<>();

        // 1. JAVA_HOME
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.isBlank()) {
            candidateHomes.add(Path.of(javaHomeEnv));
        }

        // 2. The JVM currently running this launcher
        String runningJavaHome = System.getProperty("java.home");
        if (runningJavaHome != null && !runningJavaHome.isBlank()) {
            candidateHomes.add(Path.of(runningJavaHome));
        }

        // 3. Well-known install directories
        if (IS_WINDOWS) {
            candidateHomes.addAll(scanWindowsDirs());
        } else {
            candidateHomes.addAll(scanLinuxDirs());
        }

        // 4. A "java"/"java.exe" resolvable directly on PATH
        Path onPath = findOnPath();
        if (onPath != null) {
            candidateHomes.add(onPath.getParent() != null ? onPath.getParent().getParent() : onPath);
        }

        for (Path home : candidateHomes) {
            try {
                if (home == null) continue;
                File exe = resolveJavaExecutable(home);
                if (exe == null || !exe.isFile()) continue;
                String canonical = exe.getCanonicalPath();
                if (found.containsKey(canonical)) continue;
                String version = probeVersion(exe);
                String vendor = guessVendor(home.toString());
                String display = buildDisplayName(version, vendor, canonical);
                found.put(canonical, new JavaInstallation(display, canonical));
            } catch (IOException ignored) {
                // Unreadable / inaccessible directory - skip it
            }
        }

        List<JavaInstallation> result = new ArrayList<>(found.values());
        result.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        return result;
    }

    private static File resolveJavaExecutable(Path home) {
        if (home == null) return null;
        File exe = new File(home.toFile(), IS_WINDOWS ? "bin/java.exe" : "bin/java");
        return exe.isFile() ? exe : null;
    }

    private static List<Path> scanWindowsDirs() {
        List<Path> homes = new ArrayList<>();
        String[] roots = {
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramW6432"),
                "C:\\Program Files",
                "C:\\Program Files (x86)"
        };
        String[] vendorDirs = {
                "Java",
                "Eclipse Adoptium",
                "Eclipse Foundation",
                "Zulu",
                "Microsoft\\jdk",
                "Microsoft",
                "Amazon Corretto",
                "BellSoft\\LibericaJDK",
                "AdoptOpenJDK",
                "RedHat"
        };
        for (String root : roots) {
            if (root == null || root.isBlank()) continue;
            for (String vendorDir : vendorDirs) {
                addSubdirsAsHomes(Path.of(root, vendorDir), homes);
            }
        }

        // JDKs managed by IDEs (IntelliJ) live under the user's home directory
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            addSubdirsAsHomes(Path.of(userHome, ".jdks"), homes);
            addSubdirsAsHomes(Path.of(userHome, "scoop", "apps"), homes);
        }

        // Common Chocolatey / manual install location
        addSubdirsAsHomes(Path.of("C:\\", "java"), homes);

        return homes;
    }

    private static List<Path> scanLinuxDirs() {
        List<Path> homes = new ArrayList<>();

        addSubdirsAsHomes(Path.of("/usr/lib/jvm"), homes);
        addSubdirsAsHomes(Path.of("/usr/java"), homes);
        addSubdirsAsHomes(Path.of("/opt/java"), homes);
        addSubdirsAsHomes(Path.of("/opt/jdk"), homes);
        addSubdirsAsHomes(Path.of("/opt"), homes, "jdk", "java", "graalvm", "temurin", "zulu", "corretto", "liberica");

        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            addSubdirsAsHomes(Path.of(userHome, ".sdkman", "candidates", "java"), homes);
            addSubdirsAsHomes(Path.of(userHome, ".jdks"), homes); // IntelliJ-managed JDKs
            homes.add(Path.of(userHome, ".sdkman", "candidates", "java", "current"));
        }

        // Snap-packaged JDKs
        addSubdirsAsHomes(Path.of("/snap"), homes, "openjdk");

        return homes;
    }

    /** Adds every immediate subdirectory of {@code dir} as a candidate JDK home. */
    private static void addSubdirsAsHomes(Path dir, List<Path> out) {
        addSubdirsAsHomes(dir, out, (String[]) null);
    }

    /**
     * Adds every immediate subdirectory of {@code dir} as a candidate JDK home,
     * optionally filtered to only those whose folder name contains one of
     * {@code nameContainsAnyOf} (case-insensitive).
     */
    private static void addSubdirsAsHomes(Path dir, List<Path> out, String... nameContainsAnyOf) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isDirectory(p)) continue;
                if (nameContainsAnyOf != null && nameContainsAnyOf.length > 0) {
                    String name = p.getFileName().toString().toLowerCase();
                    boolean matches = false;
                    for (String needle : nameContainsAnyOf) {
                        if (name.contains(needle.toLowerCase())) {
                            matches = true;
                            break;
                        }
                    }
                    if (!matches) continue;
                }
                out.add(p);
                // Some layouts nest one level deeper, e.g. /opt/openjdk/<version>
                try (DirectoryStream<Path> nested = Files.newDirectoryStream(p)) {
                    for (Path np : nested) {
                        if (Files.isDirectory(np)) out.add(np);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            // Directory not accessible - just skip it
        }
    }

    /** Resolves "java"/"java.exe" using the PATH environment variable, like a shell would. */
    private static Path findOnPath() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String exeName = IS_WINDOWS ? "java.exe" : "java";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            File candidate = new File(dir, exeName);
            if (candidate.isFile()) {
                return candidate.toPath();
            }
        }
        return null;
    }

    private static String guessVendor(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("temurin") || lower.contains("adoptium")) return "Temurin";
        if (lower.contains("zulu")) return "Zulu";
        if (lower.contains("corretto")) return "Corretto";
        if (lower.contains("liberica")) return "Liberica";
        if (lower.contains("graalvm")) return "GraalVM";
        if (lower.contains("microsoft")) return "Microsoft";
        if (lower.contains("openjdk")) return "OpenJDK";
        if (lower.contains("oracle")) return "Oracle";
        return null;
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"([^\"]+)\"");

    /** Runs {@code java -version} against the given executable and parses the version string. */
    private static String probeVersion(File javaExe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            Matcher m = VERSION_PATTERN.matcher(out.toString());
            if (m.find()) {
                return m.group(1);
            }
        } catch (IOException | InterruptedException ignored) {
            // Couldn't determine version - not fatal, we'll just show the path
        }
        return null;
    }

    private static String buildDisplayName(String version, String vendor, String path) {
        StringBuilder sb = new StringBuilder("Java");
        if (version != null) sb.append(' ').append(version);
        if (vendor != null) sb.append(" (").append(vendor).append(')');
        sb.append(" — ").append(path);
        return sb.toString();
    }
}
