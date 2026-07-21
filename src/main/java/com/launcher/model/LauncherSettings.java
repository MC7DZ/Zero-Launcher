package com.launcher.model;

public class LauncherSettings {
    /** Bumped whenever one of the built-in default values below is changed in a launcher
     *  update. SettingsManager compares this against CURRENT_DEFAULTS_VERSION on load: any
     *  field whose saved value still matches its *old* default gets migrated to the *new*
     *  default (a user who customized that field is left untouched, since their value won't
     *  match the old default). Do not hand-edit this on existing settings.json files. */
    public int defaultsVersion = 1;

    // Appearance
    public String accentColor      = "#fa0404";   // Rem Color <3
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
    public String backgroundImageFit    = "Cover"; // Cover, Contain, Stretch, Center, Tile
    public int backgroundImageDim       = 35;      // 0-100, darkening overlay strength
    public boolean backgroundImageTint  = false;   // wash the dim overlay with the accent color
    public boolean backgroundImageVignette = true; // subtle edge darkening for depth
    /** Fake "frosted glass" transparency: blends panels with the background behind them.
     *  Independent of blur — you can have one without the other. */
    public boolean enableTransparency   = true;
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
    public boolean scanOnStartup        = false;
    public boolean showHiddenInstances  = false;
    /** ID of the instance that was selected the last time the launcher was open;
     *  used to auto-select it again the next time the launcher starts. */
    public String lastSelectedInstanceId = null;
    /** Animate mouse-wheel scrolling instead of jumping instantly. */
    public boolean smoothScrolling      = true;
    /** Automatically check every instance's mods for available updates when the launcher starts. */
    public boolean checkModUpdatesOnStartup = true;
    /** Automatically refresh the Discover tab (trending mods/resource packs) when the launcher starts. */
    public boolean refreshDiscoverOnLaunch = true;
    /** If a game launch fails because its version data couldn't be loaded, automatically re-scan
     *  that instance's mods and resource packs, in case stale/corrupt files were the cause. */
    public boolean autoRefreshModsOnVersionLoadFail = true;
    /** Show a confirmation popout before destructive actions (deleting an instance, a mod, or
     *  resetting all settings). When off, those actions run immediately with no prompt. */
    public boolean confirmDestructiveActions = true;

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
    public boolean hideLaunchCommand    = true;

    // Discord RPC
    public boolean enableDiscordRpc     = true;
    // ── General ──
    public boolean rpcShowInLauncher    = true;
    public boolean rpcShowInstanceName  = true;
    public boolean rpcShowMinecraftVersion = true;
    public boolean rpcShowServerIp      = false;
    public boolean rpcShowGameState     = true;
    public String  rpcCustomStateText   = "In Zero Launcher";
    // ── Launcher tab visibility ──
    public boolean rpcShowLauncherActivity = true;
    public boolean rpcShowTabInstances  = true;
    public boolean rpcShowTabMods       = true;
    public boolean rpcShowTabDiscover   = true;
    public boolean rpcShowTabPresets    = true;
    public boolean rpcShowTabSettings   = true;
    // ── In-game state visibility ──
    public boolean rpcShowStateLaunching   = true;
    public boolean rpcShowStateMainMenu    = true;
    public boolean rpcShowStateSingleplayer = true;
    public boolean rpcShowStateMultiplayer  = true;
    // ── Advanced ──
    public String rpcAppId = "1131048770109460500"; // default minecraft app id

    // ── Developer ────────────────────────────────────────────────────────────
    public boolean unlockDevStuff       = false;
    /** When on, verbose diagnostic messages (WindowDebug, etc.) are printed to the console. */
    public boolean debugMode            = false;
    public String privateServersIps     = "";
    public String customDiscordRpcImage = "minecraft_image";
    /** Display name shown as the Rich Presence image tooltip / branding text. */
    public String customDiscordRpcName  = "Minecraft";
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
