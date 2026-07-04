package com.launcher.model;

public class LauncherSettings {
    // Appearance
    public String accentColor      = "#10b981";   // emerald green accent
    public String bgColor          = "#0a0a0f";   // near-black base
    public String panelBgColor     = "#13131a";   // slightly lighter panel
    public String textColor        = "#e2e2ea";   // off-white text
    public String logBgColor       = "#060608";   // very dark console bg
    public String fontFamily       = "Inter";
    public boolean useBackgroundImage   = false;
    public String backgroundImagePath   = "";
    public boolean enableBlurEffect     = false;
    public int blurStrength             = 10;     // GaussianBlur radius (1–40)

    // Behavior
    /** Minimize the launcher while Minecraft is running — game opens in its own OS window. */
    public boolean minimizeOnLaunch     = true;
    /** Close the launcher entirely after Minecraft launches (saves RAM). */
    public boolean closeAfterLaunch     = false;
    /** Keep the log console visible while Minecraft is running. */
    public boolean showConsoleOnLaunch  = true;
    public boolean scanOnStartup        = true;
    public boolean showHiddenInstances  = false;
    /** Automatically check every instance's mods for available updates when the launcher starts. */
    public boolean checkModUpdatesOnStartup = true;

    // Performance
    /** Default RAM in GB for new instances. */
    public int defaultRamGb             = 3;
    /** Extra JVM arguments appended to every launch command. */
    public String extraJvmArgs          = "";
    public String javaPath              = "";
    public String jvmArgs               = "";

    // Window size (0 = use defaults 960×660)
    public int launcherWidth            = 0;
    public int launcherHeight           = 0;

    // Privacy & Security
    public boolean hideUsername         = false;
    public boolean redactPaths          = false;
    public boolean redactTokens         = true;
    public boolean clearSessionOnExit   = false;

    // Discord RPC
    // Zero Launcher ships with its own bundled Discord Application ID (just like
    // the official discord-rpc example app does) so nobody has to create a
    // Discord application or paste in an App ID to get Rich Presence working —
    // it's entirely client-side and works out of the box.
    public boolean enableDiscordRpc     = true;
    public boolean showServerOnRpc      = true;
    public String privateServersIps     = "";
    public String customDiscordRpcImage = "minecraft_image.png";
    /** Display name shown as the Rich Presence image tooltip / branding text. */
    public String customDiscordRpcName  = "Zero Launcher";
    @Deprecated
    public String discordAppId          = ""; // No longer used/shown in the UI — kept only so old settings.json files still parse.

    // ── Private servers ──────────────────────────────────────────────────────
    // privateServersIps is intentionally a single, global comma-separated list
    // rather than per-instance: marking a server private applies to it across
    // every instance, since it's the same real-world server regardless of
    // which instance connects to it.

    /** True if the given server address is in the private list (case-insensitive). */
    public boolean isServerPrivate(String ip) {
        if (ip == null || ip.isBlank()) return false;
        for (String entry : privateServerList()) {
            if (ip.trim().equalsIgnoreCase(entry)) return true;
        }
        return false;
    }

    /** Adds a server address to the global private list, if not already present. */
    public void addPrivateServer(String ip) {
        if (ip == null || ip.isBlank()) return;
        String trimmed = ip.trim();
        if (isServerPrivate(trimmed)) return;
        java.util.List<String> list = privateServerList();
        list.add(trimmed);
        privateServersIps = String.join(", ", list);
    }

    /** Removes a server address from the global private list. */
    public void removePrivateServer(String ip) {
        if (ip == null || ip.isBlank()) return;
        java.util.List<String> list = privateServerList();
        list.removeIf(entry -> entry.equalsIgnoreCase(ip.trim()));
        privateServersIps = String.join(", ", list);
    }

    /** Returns the private server list as a clean, trimmed list of entries. */
    public java.util.List<String> privateServerList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (privateServersIps == null || privateServersIps.isBlank()) return out;
        for (String entry : privateServersIps.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
