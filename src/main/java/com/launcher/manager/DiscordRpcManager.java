package com.launcher.manager;

import com.launcher.model.Instance;
import com.launcher.model.LauncherSettings;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;

/**
 * Manages Discord Rich Presence using MinnDevelopment java-discord-rpc.
 * This is the exact method used by major Minecraft clients.
 */
public class DiscordRpcManager {

    private static final String APP_ID = "1528905372625146066";

    private static DiscordRpcManager instance;

    private volatile boolean connected = false;
    private volatile long startTime;
    private long gameStartTime = 0;

    // ── Game state tracking ───────────────────────────────────────────────────
    public enum GameState { IDLE, LAUNCHING, MAIN_MENU, SINGLEPLAYER, MULTIPLAYER }
    private volatile GameState currentKind = GameState.IDLE;
    private volatile String currentInstanceId;
    private volatile String currentInstanceName;
    private volatile String currentVersion;
    private volatile String currentServerIp;

    // ── Launcher tab tracking ─────────────────────────────────────────────────
    private volatile String currentLauncherTab = "Instances";

    private Thread callbackThread;
    private volatile boolean running = false;

    private DiscordRpcManager() {}

    public static synchronized DiscordRpcManager getInstance() {
        if (instance == null) instance = new DiscordRpcManager();
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void init() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        if (!settings.enableDiscordRpc) {
            shutdown();
            return;
        }
        if (connected || running) return;

        String appIdToUse = (settings.rpcAppId != null && !settings.rpcAppId.trim().isEmpty())
                ? settings.rpcAppId.trim() : APP_ID;

        System.out.println("[DiscordRPC] Initializing with App ID: " + appIdToUse);
        try {
            DiscordEventHandlers handlers = new DiscordEventHandlers();
            handlers.ready = (user) -> {
                System.out.println("[DiscordRPC] Connected! User: " + user.username + "#" + user.discriminator);
                connected = true;
                startTime = System.currentTimeMillis() / 1000;
                reapplyPresence();
            };
            handlers.disconnected = (errorCode, message) -> {
                System.out.println("[DiscordRPC] Disconnected: (" + errorCode + ") " + message);
                connected = false;
            };

            DiscordRPC.INSTANCE.Discord_Initialize(appIdToUse, handlers, true, "");
            running = true;

            // Start the callback thread needed by the native library
            callbackThread = new Thread(() -> {
                while (running) {
                    DiscordRPC.INSTANCE.Discord_RunCallbacks();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }, "DiscordRPC-Callback-Thread");
            callbackThread.setDaemon(true);
            callbackThread.start();
            
            // Push initial presence immediately like VanillaRPC does
            pushIdle();

        } catch (Throwable ex) {
            System.out.println("[DiscordRPC] Failed to initialise with App ID " + appIdToUse + ": " + ex.getMessage());
            if (!appIdToUse.equals(APP_ID)) {
                // Retry once with the built-in default so RPC still comes up, but do NOT
                // touch the user's saved rpcAppId setting — a transient failure (Discord
                // not running yet, app not verified, network hiccup, etc.) should never
                // silently discard the icon style / custom App ID the user chose.
                System.out.println("[DiscordRPC] Retrying with default App ID (setting left untouched): " + APP_ID);
                running = false;
                connected = false;
                appIdToUse = APP_ID;
                try {
                    DiscordEventHandlers handlers = new DiscordEventHandlers();
                    handlers.ready = (user) -> {
                        System.out.println("[DiscordRPC] Connected! User: " + user.username + "#" + user.discriminator);
                        connected = true;
                        startTime = System.currentTimeMillis() / 1000;
                        reapplyPresence();
                    };
                    handlers.disconnected = (errorCode, message) -> {
                        System.out.println("[DiscordRPC] Disconnected: (" + errorCode + ") " + message);
                        connected = false;
                    };
                    DiscordRPC.INSTANCE.Discord_Initialize(appIdToUse, handlers, true, "");
                    running = true;
                    callbackThread = new Thread(() -> {
                        while (running) {
                            DiscordRPC.INSTANCE.Discord_RunCallbacks();
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {}
                        }
                    }, "DiscordRPC-Callback-Thread");
                    callbackThread.setDaemon(true);
                    callbackThread.start();
                    pushIdle();
                } catch (Throwable ex2) {
                    System.out.println("[DiscordRPC] Fallback init also failed: " + ex2.getMessage());
                    connected = false;
                    running = false;
                }
            } else {
                ex.printStackTrace();
                connected = false;
                running = false;
            }
        }
    }

    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        connected = false;
        try {
            DiscordRPC.INSTANCE.Discord_Shutdown();
        } catch (Exception ignored) {}
        
        currentKind = GameState.IDLE;
        currentInstanceId = null;
        currentInstanceName = null;
        currentVersion = null;
        currentServerIp = null;
        System.out.println("[DiscordRPC] Shutdown complete.");
    }

    public boolean isConnected() { return connected; }

    // ══════════════════════════════════════════════════════════════════════════
    //  LAUNCHER TAB TRACKING
    // ══════════════════════════════════════════════════════════════════════════

    /** Called by Main.java whenever the user switches launcher tabs. */
    public void updateLauncherTab(String tabName) {
        currentLauncherTab = tabName != null ? tabName : "Instances";
        if (currentKind == GameState.IDLE) {
            pushIdle();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRESENCE UPDATES
    // ══════════════════════════════════════════════════════════════════════════

    public void updateIdle() {
        currentKind = GameState.IDLE;
        currentInstanceId = null;
        currentInstanceName = null;
        currentVersion = null;
        currentServerIp = null;
        pushIdle();
    }

    public void updateLaunching(Instance instance, String version) {
        currentKind = GameState.LAUNCHING;
        currentInstanceId = instance != null ? instance.id : null;
        currentInstanceName = instance != null ? instance.name : null;
        currentVersion = version;
        currentServerIp = null;
        gameStartTime = System.currentTimeMillis() / 1000;
        pushPlaying();
    }

    public void updatePlaying(Instance instance, String version) {
        currentKind = GameState.MAIN_MENU;
        currentInstanceId = instance != null ? instance.id : null;
        currentInstanceName = instance != null ? instance.name : null;
        currentVersion = version;
        currentServerIp = null;
        pushPlaying();
    }

    public void updateGameState(GameState state) {
        if (state == GameState.IDLE) {
            updateIdle();
            return;
        }
        if (state == GameState.MAIN_MENU || state == GameState.SINGLEPLAYER) {
            currentServerIp = null;
        }
        currentKind = state;
        if (state == GameState.MULTIPLAYER) pushPlayingServer();
        else pushPlaying();
    }

    public void updatePlayingServer(Instance instance, String version, String serverIp) {
        currentKind = GameState.MULTIPLAYER;
        currentInstanceId = instance != null ? instance.id : null;
        currentInstanceName = instance != null ? instance.name : null;
        currentVersion = version;
        currentServerIp = serverIp;
        pushPlayingServer();
    }

    /** Re-sends the last known presence — called after reconnect or settings change. */
    public void reapplyPresence() {
        switch (currentKind) {
            case LAUNCHING, MAIN_MENU, SINGLEPLAYER -> pushPlaying();
            case MULTIPLAYER             -> pushPlayingServer();
            default                      -> pushIdle();
        }
    }

    // ── Getters for UI ────────────────────────────────────────────────────────
    public String  getCurrentInstanceId()   { return currentInstanceId; }
    public String  getCurrentInstanceName() { return currentInstanceName; }
    public String  getCurrentServerIp()     { return currentServerIp; }
    public boolean isOnServer()             { return currentKind == GameState.MULTIPLAYER && currentServerIp != null; }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRESENCE BUILDERS
    // ══════════════════════════════════════════════════════════════════════════

    private void pushIdle() {
        if (!running || !SettingsManager.getInstance().getSettings().enableDiscordRpc) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        
        if (!settings.rpcShowInLauncher) {
            DiscordRPC.INSTANCE.Discord_ClearPresence();
            return;
        }

        // Build the details line based on which launcher tabs are enabled
        String details = buildLauncherTabDetails(settings);
        
        DiscordRichPresence presence = new DiscordRichPresence();
        presence.state = settings.rpcCustomStateText != null && !settings.rpcCustomStateText.isEmpty()
                ? settings.rpcCustomStateText : null;
        presence.details = details;
        presence.startTimestamp = startTime;
        presence.largeImageKey = imageKey(settings);
        presence.largeImageText = settings.customDiscordRpcName != null ? settings.customDiscordRpcName : "Minecraft";
        
        send(presence);
    }

    /** Builds a details string like "Browsing Instances" based on the current tab and toggles. */
    private String buildLauncherTabDetails(LauncherSettings s) {
        if (!s.rpcShowLauncherActivity) return null;
        
        // Show which specific tab the user is on
        String tab = currentLauncherTab;
        if (tab == null) tab = "Instances";
        
        // Check if this specific tab is enabled to be shown
        boolean show = switch (tab.trim()) {
            case "Instances" -> s.rpcShowTabInstances;
            case "Mods"      -> s.rpcShowTabMods;
            case "Discover"  -> s.rpcShowTabDiscover;
            case "Presets"   -> s.rpcShowTabPresets;
            case "Settings"  -> s.rpcShowTabSettings;
            default          -> true;
        };
        
        if (show) {
            return "Browsing " + tab.trim();
        } else {
            return null;
        }
    }

    private void pushPlaying() {
        if (!running || !SettingsManager.getInstance().getSettings().enableDiscordRpc) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        
        String details = "In Game";
        if (settings.rpcShowInstanceName && currentInstanceName != null) {
            details = "Playing " + currentInstanceName;
        }
        
        String state = "";
        if (settings.rpcShowGameState) {
            boolean showThisState = switch (currentKind) {
                case LAUNCHING    -> settings.rpcShowStateLaunching;
                case MAIN_MENU    -> settings.rpcShowStateMainMenu;
                case SINGLEPLAYER -> settings.rpcShowStateSingleplayer;
                case MULTIPLAYER  -> settings.rpcShowStateMultiplayer;
                default           -> true;
            };
            if (showThisState) {
                if (currentKind == GameState.LAUNCHING) state = "Launching Game...";
                else if (currentKind == GameState.SINGLEPLAYER) state = "In Singleplayer";
                else if (currentKind == GameState.MULTIPLAYER) state = "In Multiplayer";
                else state = "In Main Menu";
            }
        }
        
        // Always show Minecraft version if they didn't explicitly ask for game state OR if state is empty
        if (state.isEmpty() && settings.rpcShowMinecraftVersion && currentVersion != null) {
            state = currentVersion;
        }

        DiscordRichPresence presence = new DiscordRichPresence();
        presence.state = state.isEmpty() ? null : state;
        presence.details = details;
        presence.startTimestamp = gameStartTime > 0 ? gameStartTime : startTime;
        presence.largeImageKey = imageKey(settings);
        presence.largeImageText = settings.customDiscordRpcName != null ? settings.customDiscordRpcName : "Minecraft";

        send(presence);
    }

    private void pushPlayingServer() {
        if (!running || !SettingsManager.getInstance().getSettings().enableDiscordRpc) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        
        if (!settings.rpcShowServerIp || !settings.rpcShowStateMultiplayer) {
            pushPlaying();
            return;
        }

        String details = "In Game";
        if (settings.rpcShowInstanceName && currentInstanceName != null) {
            details = "Playing " + currentInstanceName;
        }

        String state = "";
        if (settings.rpcShowGameState) {
            boolean showThisState = switch (currentKind) {
                case LAUNCHING    -> settings.rpcShowStateLaunching;
                case MAIN_MENU    -> settings.rpcShowStateMainMenu;
                case SINGLEPLAYER -> settings.rpcShowStateSingleplayer;
                case MULTIPLAYER  -> settings.rpcShowStateMultiplayer;
                default           -> true;
            };
            if (showThisState) {
                if (currentKind == GameState.LAUNCHING) state = "Launching Game...";
                else if (currentKind == GameState.SINGLEPLAYER) state = "In Singleplayer";
                else if (currentKind == GameState.MULTIPLAYER) state = "In Multiplayer";
                else state = "In Main Menu";
            }
        }
        
        // Always show Minecraft version if they didn't explicitly ask for game state OR if state is empty
        if (state.isEmpty() && settings.rpcShowMinecraftVersion && currentVersion != null) {
            state = currentVersion;
        }

        DiscordRichPresence presence = new DiscordRichPresence();
        presence.state = state.isEmpty() ? null : state;
        presence.details = details;
        presence.startTimestamp = gameStartTime > 0 ? gameStartTime : startTime;
        presence.largeImageKey = imageKey(settings);
        presence.largeImageText = settings.customDiscordRpcName != null ? settings.customDiscordRpcName : "Minecraft";

        send(presence);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private boolean ready() {
        return running && connected
                && SettingsManager.getInstance().getSettings().enableDiscordRpc;
    }

    private String imageKey(LauncherSettings settings) {
        if (settings.customDiscordRpcImage == null || settings.customDiscordRpcImage.isEmpty()) {
            return "minecraft_image";
        }
        String key = settings.customDiscordRpcImage;
        return "minecraft_image.png".equals(key) ? "minecraft_image" : key;
    }

    private void send(DiscordRichPresence presence) {
        try {
            DiscordRPC.INSTANCE.Discord_UpdatePresence(presence);
            System.out.println("[DiscordRPC] Presence updated: " + currentKind);
        } catch (Exception ex) {
            System.out.println("[DiscordRPC] Failed to send presence: " + ex.getMessage());
        }
    }
}
