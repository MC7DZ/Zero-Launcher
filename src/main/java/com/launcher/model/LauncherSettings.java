package com.launcher.model;

public class LauncherSettings {
    // Appearance
    public String accentColor      = "#10b981";   // emerald green accent
    public String bgColor          = "#0a0a0f";   // near-black base
    public String panelBgColor     = "#13131a";   // slightly lighter panel
    public String textColor        = "#e2e2ea";   // off-white text
    public String logBgColor       = "#060608";   // very dark console bg
    public String fontFamily       = "SansSerif";
    /** Absolute paths to user-added custom font files (.ttf/.otf), comma-separated.
     *  Registered at startup and appended to the Font Family choices so they persist
     *  across restarts. */
    public String customFontPaths  = "";
    /** Which preset gradient backdrop to paint behind the UI. One of: Default, Midnight,
     *  Sunset, Forest, Ocean, Monochrome, Accent Glow. */
    public String backgroundStyle  = "Default";
    public String headerBgColor         = "#111116";
    public String searchBgColor         = "#1a1a24";
    public String notificationBgColor   = "#13131A";
    public String notificationStyle     = "Minimal Outline";
    public boolean useBackgroundImage   = false;
    public String backgroundImagePath   = "";
    /** Fake "frosted glass" transparency: blends panels with the background behind them.
     *  Independent of blur — you can have one without the other. */
    public boolean enableTransparency   = true;
    public int transparencyStrength     = 20;     // 1–100, higher = more see-through
    /** Softens/blurs the painted background (gradient glow, or the background image if one
     *  is set). Independent of transparency. */
    public boolean enableBlurEffect     = false;
    public int blurStrength             = 10;     // blur radius, 1–40

    // Behavior
    /** Minimize the launcher while Minecraft is running — game opens in its own OS window. */
    public boolean minimizeOnLaunch     = false;
    /** Restore the launcher window automatically when the Minecraft instance closes. */
    public boolean restoreLauncherOnGameClose = true;
    /** Show an icon in the system tray. */
    public boolean enableSystemTray     = true;
    /** Close the launcher entirely after Minecraft launches (saves RAM). */
    public boolean closeAfterLaunch     = false;
    /** Keep the log console visible while Minecraft is running. */
    public boolean showConsoleOnLaunch  = true;
    /** Whether the log console panel is currently shown or collapsed (toggled from the top bar). */
    public boolean logConsoleVisible    = false;
    public boolean scanOnStartup        = true;
    public boolean showHiddenInstances  = false;
    /** Animate mouse-wheel scrolling instead of jumping instantly. */
    public boolean smoothScrolling      = true;
    /** Automatically check every instance's mods for available updates when the launcher starts. */
    public boolean checkModUpdatesOnStartup = true;
    /** Automatically refresh the Discover tab (trending mods/resource packs) when the launcher starts. */
    public boolean refreshDiscoverOnLaunch = true;
    /** If a game launch fails because its version data couldn't be loaded, automatically re-scan
     *  that instance's mods and resource packs, in case stale/corrupt files were the cause. */
    public boolean autoRefreshModsOnVersionLoadFail = true;

    // Performance
    /** Default RAM in GB for new instances. */
    public int defaultRamGb             = 3;
    /** Max RAM (in MB) the launcher application itself (not the game) is allowed to use. Requires a restart to take effect. */
    public int launcherMaxRamMb         = 500;
    /** Whether the Max RAM for Launcher limit above is actually enforced. When off, the
     *  launcher JVM runs with no explicit -Xmx cap regardless of launcherMaxRamMb's value. */
    public boolean enableLauncherMaxRam = true;
    /** Extra JVM arguments appended to every launch command. */
    public String extraJvmArgs          = "";
    public String javaPath              = "";
    public String jvmArgs               = "";

    // Window size (0 = use defaults 960×660)
    public int launcherWidth            = 0;
    public int launcherHeight           = 0;
    /** Use a custom in-app title bar (frameless window) instead of the OS window decorations. */
    public boolean useCustomTitleBar    = true;
    /** Always launch the launcher window maximized instead of at the saved/default size. */
    public boolean startMaximized       = true;

    // Privacy & Security
    public boolean hideUsername         = false;
    public boolean redactPaths          = true;
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

    /** Returns the user-added custom font file paths as a clean, trimmed list. */
    public java.util.List<String> customFontPathList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (customFontPaths == null || customFontPaths.isBlank()) return out;
        for (String entry : customFontPaths.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /** Adds a custom font file path to the list, if not already present. */
    public void addCustomFontPath(String path) {
        if (path == null || path.isBlank()) return;
        java.util.List<String> list = customFontPathList();
        if (list.contains(path)) return;
        list.add(path);
        customFontPaths = String.join(",", list);
    }
}
