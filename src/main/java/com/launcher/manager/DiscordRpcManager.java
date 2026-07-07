package com.launcher.manager;

import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.RichPresence;
import com.launcher.model.Instance;
import com.launcher.model.LauncherSettings;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the Discord Rich Presence connection.
 * <p>
 * Rebuilt to fix the original implementation's core problem: {@code IPCClient.connect()}
 * was called directly on the JavaFX Application thread, so if Discord wasn't running
 * (or was slow to respond) the whole UI would stall on startup. This version connects
 * on a background thread and keeps retrying in the background for as long as RPC is
 * enabled, without ever blocking the UI.
 * <p>
 * It also remembers the last presence it was asked to show (idle / playing / playing on
 * a server) so that presence can be resent automatically after a reconnect, or after the
 * user changes a Discord-related setting mid-session.
 */
public class DiscordRpcManager {

    private static final long DEFAULT_APP_ID = 1124564534063689849L;
    private static final long RECONNECT_INTERVAL_SECONDS = 15;

    private static DiscordRpcManager instance;

    private volatile IPCClient client;
    private final AtomicBoolean connected  = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile String currentAppId;
    private volatile long startTime;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> reconnectTask;

    // ── Last-known presence state, so it can be replayed after a reconnect ────
    private enum PresenceKind { IDLE, PLAYING, PLAYING_SERVER }
    private volatile PresenceKind currentKind = PresenceKind.IDLE;
    private volatile String currentInstanceId;
    private volatile String currentInstanceName;
    private volatile String currentVersion;
    private volatile String currentServerIp;

    private DiscordRpcManager() {}

    public static synchronized DiscordRpcManager getInstance() {
        if (instance == null) instance = new DiscordRpcManager();
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Starts the Discord IPC connection. Never blocks.
     * <p>
     * There is no user-facing App ID setting anymore — like Discord's own
     * discord-rpc example, Zero Launcher bundles a single fixed Application ID
     * so Rich Presence works immediately for everyone, purely client-side.
     */
    public synchronized void init() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        if (!settings.enableDiscordRpc) {
            shutdown();
            return;
        }

        String appId = String.valueOf(DEFAULT_APP_ID);
        if (connected.get() && appId.equals(currentAppId)) {
            return; // already connected
        }
        if (client != null) {
            closeClient();
        }

        currentAppId = appId;
        ensureScheduler();
        connectAsync();
    }

    /** Fully tears down the connection and stops the reconnect loop. */
    public synchronized void shutdown() {
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        closeClient();
        currentKind = PresenceKind.IDLE;
        currentInstanceId = null;
        currentInstanceName = null;
        currentVersion = null;
        currentServerIp = null;
    }

    public boolean isConnected() { return connected.get(); }

    private void closeClient() {
        connected.set(false);
        connecting.set(false);
        IPCClient c = client;
        client = null;
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { /* already gone */ }
        }
    }

    private void ensureScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-rpc-reconnect");
            t.setDaemon(true);
            return t;
        });
        reconnectTask = scheduler.scheduleWithFixedDelay(() -> {
            LauncherSettings settings = SettingsManager.getInstance().getSettings();
            if (!settings.enableDiscordRpc) return;
            if (!connected.get() && !connecting.get()) connectAsync();
        }, RECONNECT_INTERVAL_SECONDS, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Attempts a single connection on a background thread. Safe to call repeatedly. */
    private void connectAsync() {
        if (!connecting.compareAndSet(false, true)) return;
        Thread t = new Thread(this::attemptConnect, "discord-rpc-connect");
        t.setDaemon(true);
        t.start();
    }

    private void attemptConnect() {
        try {
            IPCClient c = new IPCClient(DEFAULT_APP_ID);
            c.setListener(new IPCListener() {
                @Override public void onReady(IPCClient client) {
                    connected.set(true);
                    connecting.set(false);
                    startTime = System.currentTimeMillis() / 1000;
                    reapplyPresence();
                }

                @Override public void onClose(IPCClient client, com.google.gson.JsonObject json) {
                    connected.set(false);
                }

                @Override public void onDisconnect(IPCClient client, Throwable t) {
                    connected.set(false);
                }

                @Override public void onActivityJoinRequest(IPCClient client, String secret, com.jagrosh.discordipc.entities.User user) { }
                @Override public void onActivityJoin(IPCClient client, String secret) { }
                @Override public void onActivitySpectate(IPCClient client, String secret) { }
                @Override public void onPacketReceived(IPCClient client, com.jagrosh.discordipc.entities.Packet packet) { }
                @Override public void onPacketSent(IPCClient client, com.jagrosh.discordipc.entities.Packet packet) { }
            });
            c.connect();
            this.client = c;
        } catch (Exception ex) {
            // Discord isn't running (or the IPC pipe isn't reachable) — normal and
            // expected if the person hasn't opened Discord. The reconnect loop will
            // keep trying every RECONNECT_INTERVAL_SECONDS without touching the UI.
            connecting.set(false);
            connected.set(false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PRESENCE UPDATES
    // ══════════════════════════════════════════════════════════════════════

    public void updateIdle() {
        currentKind = PresenceKind.IDLE;
        currentInstanceId = null;
        currentInstanceName = null;
        currentVersion = null;
        currentServerIp = null;
        pushIdle();
    }

    public void updatePlaying(Instance instance, String version) {
        currentKind = PresenceKind.PLAYING;
        currentInstanceId = instance != null ? instance.id : null;
        currentInstanceName = instance != null ? instance.name : null;
        currentVersion = version;
        currentServerIp = null;
        pushPlaying();
    }

    public void updatePlayingServer(Instance instance, String version, String serverIp) {
        currentKind = PresenceKind.PLAYING_SERVER;
        currentInstanceId = instance != null ? instance.id : null;
        currentInstanceName = instance != null ? instance.name : null;
        currentVersion = version;
        currentServerIp = serverIp;
        pushPlayingServer();
    }

    /** Re-sends whatever presence was last set — used after a reconnect or a settings change. */
    public void reapplyPresence() {
        switch (currentKind) {
            case PLAYING -> pushPlaying();
            case PLAYING_SERVER -> pushPlayingServer();
            default -> pushIdle();
        }
    }

    // ── Getters for the UI (Settings > Server panel) ───────────────────────
    public String getCurrentInstanceId()   { return currentInstanceId; }
    public String getCurrentInstanceName() { return currentInstanceName; }
    public String getCurrentServerIp()     { return currentServerIp; }
    public boolean isOnServer()            { return currentKind == PresenceKind.PLAYING_SERVER && currentServerIp != null; }

    // ══════════════════════════════════════════════════════════════════════
    //  PRESENCE BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    private void pushIdle() {
        if (!ready()) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        RichPresence.Builder presence = new RichPresence.Builder();
        presence.setState("Idle in " + rpcName(settings))
                .setDetails("Browsing Instances & Mods")
                .setStartTimestamp(startTime)
                .setLargeImageWithTooltip(imageKey(settings), rpcName(settings));
        send(presence);
    }

    private void pushPlaying() {
        if (!ready()) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        RichPresence.Builder presence = new RichPresence.Builder();
        String details = currentInstanceName != null ? "Playing " + currentInstanceName : "In Game";
        presence.setState(currentVersion != null ? currentVersion : "In Game")
                .setDetails(details)
                .setStartTimestamp(System.currentTimeMillis() / 1000)
                .setLargeImageWithTooltip(imageKey(settings), rpcName(settings));
        send(presence);
    }

    private void pushPlayingServer() {
        if (!ready()) return;
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        if (!settings.showServerOnRpc) {
            pushPlaying();
            return;
        }

        String displayIp = settings.isServerPrivate(currentServerIp) ? "Private Server" : currentServerIp;

        RichPresence.Builder presence = new RichPresence.Builder();
        String details = currentInstanceName != null ? "Playing " + currentInstanceName : "In Game";
        presence.setState("Multiplayer: " + displayIp)
                .setDetails(details)
                .setStartTimestamp(System.currentTimeMillis() / 1000)
                .setLargeImageWithTooltip(imageKey(settings), rpcName(settings));
        send(presence);
    }

    private boolean ready() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        return client != null && connected.get() && settings.enableDiscordRpc;
    }

    /** The custom RPC display name, falling back to "Zero Launcher" if blank. */
    private String rpcName(LauncherSettings settings) {
        return (settings.customDiscordRpcName != null && !settings.customDiscordRpcName.isBlank())
                ? settings.customDiscordRpcName.trim() : "Zero Launcher";
    }

    private String imageKey(LauncherSettings settings) {
        return (settings.customDiscordRpcImage != null && !settings.customDiscordRpcImage.isEmpty())
                ? settings.customDiscordRpcImage : "minecraft_image.png";
    }

    /** The default bundled image key — used by the Settings UI's "Reset to Default" button. */
    public static String defaultImageKey() { return "minecraft_image.png"; }

    private void send(RichPresence.Builder presence) {
        IPCClient c = client;
        if (c == null) return;
        try {
            c.sendRichPresence(presence.build());
        } catch (Exception ex) {
            connected.set(false);
        }
    }
}
