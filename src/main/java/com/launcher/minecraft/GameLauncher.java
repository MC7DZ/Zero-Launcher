package com.launcher.minecraft;

import com.launcher.manager.LauncherPaths;
import com.launcher.model.Account;
import com.launcher.model.AccountType;
import com.launcher.model.Instance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class GameLauncher {

    public Process launch(Instance instance, Path gameDir, Path nativesDir, ResolvedVersion version,
                           Account account, Consumer<String> log) throws IOException {

        String javaBin = (instance.javaPath != null && !instance.javaPath.isBlank())
                ? instance.javaPath
                : "java"; // relies on PATH; the README explains how to point at a specific JDK instead

        String classpath = String.join(System.getProperty("path.separator"),
                version.classpath.stream().map(p -> p.toAbsolutePath().toString()).toList());

        Map<String, String> placeholders = buildPlaceholders(instance, gameDir, nativesDir, version, account, classpath);

        List<String> command = new ArrayList<>();
        command.add(javaBin);

        // user-supplied JVM args (memory, etc.)
        for (String arg : instance.jvmArgs.trim().split("\\s+")) {
            if (!arg.isBlank()) command.add(arg);
        }
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        // loader/version-specific jvm args from the version json, if any
        for (String arg : version.extraJvmArgs) {
            command.add(substitute(arg, placeholders));
        }

        command.add("-cp");
        command.add(classpath);
        command.add(version.mainClass);

        if (version.legacyMinecraftArguments != null) {
            for (String token : version.legacyMinecraftArguments.split(" ")) {
                command.add(substitute(token, placeholders));
            }
        } else {
            for (String arg : version.extraGameArgs) {
                command.add(substitute(arg, placeholders));
            }
            // Ensure the essentials are present even if a loader's arg list omitted them.
            ensureArg(command, "--username", account.username);
            ensureArg(command, "--uuid", stripDashes(account.uuid));
            ensureArg(command, "--accessToken", account.type == AccountType.MICROSOFT ? account.mcAccessToken : "0");
            ensureArg(command, "--userType", account.type == AccountType.MICROSOFT ? "msa" : "legacy");
            ensureArg(command, "--version", version.id);
            ensureArg(command, "--gameDir", gameDir.toAbsolutePath().toString());
            ensureArg(command, "--assetsDir", LauncherPaths.assetsDir().toAbsolutePath().toString());
            if (version.assetIndexId != null) ensureArg(command, "--assetIndex", version.assetIndexId);
        }

        log.accept("Launch command: " + String.join(" ", command));

        java.nio.file.Files.createDirectories(gameDir);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private void ensureArg(List<String> command, String flag, String value) {
        if (command.contains(flag)) return;
        command.add(flag);
        command.add(value);
    }

    private Map<String, String> buildPlaceholders(Instance instance, Path gameDir, Path nativesDir,
                                                    ResolvedVersion version, Account account, String classpath) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("auth_player_name", account.username);
        m.put("version_name", version.id);
        m.put("game_directory", gameDir.toAbsolutePath().toString());
        m.put("assets_root", LauncherPaths.assetsDir().toAbsolutePath().toString());
        m.put("assets_index_name", version.assetIndexId != null ? version.assetIndexId : "legacy");
        m.put("auth_uuid", stripDashes(account.uuid));
        m.put("auth_access_token", account.type == AccountType.MICROSOFT ? account.mcAccessToken : "0");
        m.put("user_type", account.type == AccountType.MICROSOFT ? "msa" : "legacy");
        m.put("user_properties", "{}");
        m.put("auth_session", account.type == AccountType.MICROSOFT ? account.mcAccessToken : "0");
        m.put("version_type", "release");
        m.put("natives_directory", nativesDir.toAbsolutePath().toString());
        m.put("launcher_name", "MCLauncher");
        m.put("launcher_version", "1.0.0");
        m.put("classpath", classpath);
        m.put("auth_xuid", "0");
        m.put("clientid", "0");
        return m;
    }

    private String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        for (var e : placeholders.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    private String stripDashes(String uuid) {
        return uuid == null ? "00000000000000000000000000000000" : uuid.replace("-", "");
    }
}
