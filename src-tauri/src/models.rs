use serde::{Deserialize, Serialize};

// ── Account ──────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AccountInfo {
    pub id: String,
    pub username: String,
    pub account_type: String, // "offline" or "microsoft"
    pub is_active: bool,
    /// Minecraft profile UUID — set for Microsoft accounts, used to launch
    /// with the real profile identity instead of a random offline UUID.
    #[serde(default)]
    pub mc_uuid: Option<String>,
    /// Microsoft OAuth refresh token — set for Microsoft accounts. Used to
    /// silently obtain a fresh Minecraft access token at launch time
    /// (Xbox Live/XSTS/Minecraft services tokens are short-lived, so we
    /// don't persist an access token, just what's needed to mint one).
    #[serde(default)]
    pub ms_refresh_token: Option<String>,
    /// Set when a Microsoft sign-in attempt (at launch or via "Verify")
    /// fails — e.g. the refresh token was revoked or expired. Lets the UI
    /// flag the account as needing attention (yellow account button) until
    /// the user signs in again. Never set for offline accounts.
    #[serde(default)]
    pub needs_reauth: bool,
}

/// Ensures at most one account is marked `is_active`. If more than one is
/// found (e.g. from a hand-edited `accounts.json`, or an older buggy
/// build), the first is kept active and every other one is corrected to
/// inactive. Idempotent and cheap, so it's safe to call defensively
/// anywhere accounts are loaded or mutated.
pub fn normalize_single_active_account(accounts: &mut [AccountInfo]) {
    let mut seen_active = false;
    for account in accounts.iter_mut() {
        if account.is_active {
            if seen_active {
                account.is_active = false;
            } else {
                seen_active = true;
            }
        }
    }
}

// ── Download / install progress ────────────────────────────────────────────

/// One file currently downloading, with its own real byte-level progress
/// when the server reported a content length for it.
#[derive(Debug, Clone, Serialize)]
pub struct ActiveFileProgress {
    /// Human-readable filename shown in the UI.
    pub name: String,
    /// 0-100 completion for this specific file, when known. `None` when the
    /// server didn't report a content length for it (e.g. chunked
    /// responses) — the UI falls back to an indeterminate indicator for
    /// that file only.
    pub percent: Option<f64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DownloadProgressInfo {
    /// Unique id for this install/download run.
    pub id: String,
    /// Friendly label, e.g. "1.20.1 (fabric)".
    pub label: String,
    pub minecraft_version: String,
    pub loader: String,
    /// Coarse stage name, e.g. "Client", "Libraries", "Assets", "Natives", "Loader".
    pub stage: String,
    /// Name of the file currently being processed (kept for older frontend
    /// builds / backward compat — the most recently-started active file).
    pub current_file: String,
    /// Every file actively downloading right now, in the order each one
    /// started (up to the downloader's concurrency limit). With the
    /// parallel downloader, several files are in flight at once — this is
    /// the real list, `current_file` above only ever showed the latest one.
    pub active_files: Vec<ActiveFileProgress>,
    pub downloaded_bytes: u64,
    pub total_bytes: Option<u64>,
    /// Best-effort overall completion percentage (0-100).
    pub percent: f64,
    /// Current throughput in bytes/sec.
    pub speed_bps: f64,
    /// Estimated seconds remaining, when it can be estimated.
    pub eta_seconds: Option<u64>,
    /// "downloading" | "paused" | "completed" | "cancelled" | "error"
    pub status: String,
    pub message: Option<String>,
}

// ── Settings ─────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LaunchVerifyStatus {
    /// Which instance this status is for — the frontend only shows it
    /// while that instance is the selected one.
    pub version_id: String,
    /// `true` while a pre-launch verify/repair pass is running; `false`
    /// once it's finished (or was skipped for an offline launch), which is
    /// also the frontend's signal to start its "did the launch hang?"
    /// timeout — that timeout deliberately excludes however long this
    /// pass took, since it can legitimately run for a while on a slow
    /// connection when files need re-downloading.
    pub active: bool,
    /// Short status line shown next to the Play button, e.g. "Checking
    /// libraries...", "Installing missing assets...".
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LauncherSettings {
    pub game_directory: String,
    /// "Auto Check For Launcher Updates (Recommended)" - off by default.
    /// When off, the launcher shows a one-time-per-decision prompt on
    /// startup asking the user to allow/deny it (unless they've ticked
    /// "don't ask me again").
    #[serde(default)]
    pub auto_check_launcher_updates: bool,
    /// Set once the user ticks "Don't ask me again" on the auto-update
    /// prompt, regardless of which button they pressed - stops the
    /// startup prompt from appearing again.
    #[serde(default)]
    pub update_prompt_dont_ask_again: bool,
    pub jvm_args: String,
    pub max_ram_mb: u32,
    pub min_ram_mb: u32,
    /// Java selection for launching instances.
    ///
    /// - `None` / empty string: "Smart Java Detection" (the default) — the
    ///   launcher figures out which Java major version each instance needs
    ///   and automatically picks (or downloads) a matching install.
    /// - `Some(path)`: an explicit override chosen from the Settings
    ///   dropdown. Can be either a Java home directory or a direct path to
    ///   the `java`/`java.exe` executable itself.
    #[serde(default)]
    pub java_path: Option<String>,

    /// "Always Launch Offline" — set from the gear menu next to Play.
    /// When true, every launch skips the pre-launch libraries/assets
    /// verification pass and starts the game immediately from whatever is
    /// already on disk, the same as picking "Launch Offline" for that one
    /// launch but without having to pick it every time.
    #[serde(default)]
    pub always_launch_offline: bool,

    // Appearance
    #[serde(default = "default_accent_color")]
    pub accent_color: String,
    #[serde(default = "default_bg_color")]
    pub bg_color: String,
    #[serde(default = "default_panel_bg_color")]
    pub panel_bg_color: String,
    #[serde(default = "default_text_color")]
    pub text_color: String,
    #[serde(default = "default_log_bg_color")]
    pub log_bg_color: String,
    #[serde(default = "default_font_family")]
    pub font_family: String,
    #[serde(default)]
    pub custom_font_paths: String,
    #[serde(default = "default_background_style")]
    pub background_style: String,
    #[serde(default = "default_true")]
    pub enable_background_animation: bool,
    #[serde(default = "default_bg_anim_style")]
    pub background_animation_style: String,
    #[serde(default = "default_speed")]
    pub background_animation_speed: f64,
    #[serde(default = "default_bg_anim_intensity")]
    pub background_animation_intensity: f64,
    #[serde(default = "default_fps")]
    pub background_animation_fps: u32,
    #[serde(default = "default_header_bg")]
    pub header_bg_color: String,
    #[serde(default = "default_search_bg")]
    pub search_bg_color: String,
    #[serde(default = "default_notif_bg")]
    pub notification_bg_color: String,
    #[serde(default = "default_notif_style")]
    pub notification_style: String,
    #[serde(default)]
    pub use_background_image: bool,
    #[serde(default)]
    pub background_image_path: String,
    #[serde(default = "default_bg_fit")]
    pub background_image_fit: String,
    #[serde(default = "default_bg_dim")]
    pub background_image_dim: u32,
    #[serde(default)]
    pub background_image_tint: bool,
    #[serde(default = "default_true")]
    pub background_image_vignette: bool,
    #[serde(default = "default_true")]
    pub enable_transparency: bool,
    #[serde(default)]
    pub enable_blur_effect: bool,
    #[serde(default = "default_blur_strength")]
    pub blur_strength: u32,

    // Behavior
    #[serde(default)]
    pub minimize_on_launch: bool,
    /// Deprecated in favor of `on_game_close`, kept only so
    /// `load_settings_with_migration` can carry an old value forward the
    /// first time a pre-migration settings.json is read.
    #[serde(default = "default_true")]
    pub restore_launcher_on_game_close: bool,
    #[serde(default = "default_true")]
    pub enable_system_tray: bool,
    #[serde(default = "default_true")]
    pub close_after_launch: bool,
    #[serde(default)]
    pub tray_notification_shown: bool,

    // ── Window Behavior ─────────────────────────────────────────────────
    // What the launcher does around a game instance's lifecycle and its
    // own window, gathered in one place (surfaced in Settings as its own
    // "Window Behavior" card).
    /// What happens to the launcher window when a running Minecraft
    /// instance closes: "show" (bring the launcher window back and focus
    /// it), "quit" (quit the launcher too), or "none" (leave it as-is —
    /// stays hidden if it was hidden, stays open if it was open).
    #[serde(default = "default_on_game_close")]
    pub on_game_close: String,
    /// What happens when the user closes the launcher's main window:
    /// "tray" (hide to the system tray icon instead of quitting — only
    /// takes effect if `enable_system_tray` is also on) or "close" (quit
    /// the launcher outright). Previously this only ever hid to tray, and
    /// only while a game was running.
    #[serde(default = "default_on_launcher_close")]
    pub on_launcher_close: String,
    /// When on, closing the main window always hides to tray (per
    /// `on_launcher_close`) even when no game instance is running, so the
    /// launcher stays quickly reachable in the background instead of only
    /// doing that while a game is active.
    #[serde(default)]
    pub always_hide_to_tray: bool,

    #[serde(default = "default_true")]
    pub show_console_on_launch: bool,
    #[serde(default)]
    pub scan_on_startup: bool,
    #[serde(default)]
    pub show_hidden_instances: bool,
    #[serde(default = "default_true")]
    pub smooth_scrolling: bool,
    #[serde(default = "default_true")]
    pub check_mod_updates_on_startup: bool,
    #[serde(default = "default_true")]
    pub refresh_discover_on_launch: bool,
    #[serde(default = "default_true")]
    pub auto_refresh_mods_on_version_load_fail: bool,
    #[serde(default = "default_true")]
    pub confirm_destructive_actions: bool,
    #[serde(default = "default_true")]
    pub sound_effects_enabled: bool,
    #[serde(default = "default_true")]
    pub auto_apply_instance_filters_in_discover: bool,
    #[serde(default = "default_true")]
    pub notify_on_auto_mod_updates: bool,


    // Performance
    #[serde(default = "default_ram_gb")]
    pub default_ram_gb: u32,
    #[serde(default = "default_launcher_max_ram")]
    pub launcher_max_ram_mb: u32,
    #[serde(default = "default_true")]
    pub enable_launcher_max_ram: bool,
    #[serde(default)]
    pub extra_jvm_args: String,
    /// Concurrent (multi-threaded) downloads: how many mod/file downloads
    /// are allowed to run in parallel at once (instance installs, preset
    /// application, mod imports, dependency installs, etc). `true` (the
    /// default) uses `download_threads` below, currently fixed at 3 as a
    /// safe default; setting this to `false` lets the user pick their own
    /// count via `download_threads` in Settings -> Performance & Java.
    #[serde(default = "default_true")]
    pub download_threads_auto: bool,
    /// Number of files that can download simultaneously when
    /// `download_threads_auto` is turned off. Clamped to 1-16 by the UI.
    #[serde(default = "default_threads")]
    pub download_threads: u32,

    // Window
    #[serde(default)]
    pub launcher_width: u32,
    #[serde(default)]
    pub launcher_height: u32,
    #[serde(default = "default_true")]
    pub start_maximized: bool,
    #[serde(default)]
    pub default_minecraft_dir: String,

    // Privacy & Security
    #[serde(default)]
    pub hide_username: bool,
    #[serde(default = "default_true")]
    pub redact_paths: bool,
    #[serde(default = "default_true")]
    pub redact_tokens: bool,
    #[serde(default)]
    pub clear_session_on_exit: bool,
    #[serde(default = "default_true")]
    pub hide_launch_command: bool,

    // Accounts
    /// Azure AD "Application (client) ID" the user registered for Microsoft
    /// sign-in. Each launcher needs its own registered Azure app to use
    /// Microsoft/Xbox Live authentication — see the note next to the field
    /// in Settings → Accounts.
    #[serde(default)]
    pub microsoft_client_id: String,

    // Discord RPC
    #[serde(default = "default_true")]
    pub enable_discord_rpc: bool,
    #[serde(default = "default_true")]
    pub rpc_show_in_launcher: bool,
    #[serde(default = "default_true")]
    pub rpc_show_instance_name: bool,
    #[serde(default = "default_true")]
    pub rpc_show_minecraft_version: bool,
    #[serde(default)]
    pub rpc_show_server_ip: bool,
    #[serde(default)]
    pub rpc_show_game_state: bool,
    #[serde(default = "default_rpc_custom_state")]
    pub rpc_custom_state_text: String,
    #[serde(default = "default_rpc_app_id")]
    pub rpc_app_id: String,

    // Launcher tab visibility
    #[serde(default = "default_true")]
    pub rpc_show_launcher_activity: bool,
    #[serde(default = "default_true")]
    pub rpc_tab_instances: bool,
    #[serde(default = "default_true")]
    pub rpc_tab_mods: bool,
    #[serde(default = "default_true")]
    pub rpc_tab_settings: bool,
    #[serde(default = "default_true")]
    pub rpc_tab_logs: bool,

    // In-game state visibility
    #[serde(default = "default_true")]
    pub rpc_state_launching: bool,
    #[serde(default = "default_true")]
    pub rpc_state_main_menu: bool,
    #[serde(default = "default_true")]
    pub rpc_state_singleplayer: bool,
    #[serde(default = "default_true")]
    pub rpc_state_multiplayer: bool,

    // Developer
    #[serde(default)]
    pub unlock_dev_stuff: bool,
    #[serde(default)]
    pub debug_mode: bool,
    #[serde(default)]
    pub private_servers_ips: String,

    // Music
    /// Master on/off toggle for the background music player.
    #[serde(default)]
    pub music_enabled: bool,
    /// 0-100.
    #[serde(default = "default_music_volume")]
    pub music_volume: u32,
    /// "pause" | "continue" | "lower" — what happens to playback when the
    /// launcher window loses focus (e.g. switching to another launcher).
    #[serde(default = "default_music_switch_behavior")]
    pub music_switch_behavior: String,
    /// 0-100. Only used when `music_switch_behavior` is "lower" — how much
    /// quieter the music gets while the window is unfocused.
    #[serde(default = "default_music_lower_percent")]
    pub music_lower_percent: u32,
    /// File names (relative to `Zero Launcher/music/`) the user has
    /// unchecked in the music library — everything else found in that
    /// folder is treated as enabled.
    #[serde(default)]
    pub music_disabled_tracks: Vec<String>,

    // Experimental
    /// The "we looked through your log and here's a likely cause" popup
    /// after a crash. Heuristic-based (string/pattern matching against the
    /// log, not an exhaustive rules engine), so it can misdiagnose — off by
    /// default until it's more reliable; people can opt in from Settings.
    #[serde(default)]
    pub enable_crash_analysis: bool,
    /// Automatically pop open the per-instance console window as soon as a
    /// launch is kicked off, instead of waiting for the user to find it
    /// under Running Instances. Handy for watching what a slow-starting
    /// instance is doing instead of staring at "LAUNCHING…".
    #[serde(default)]
    pub auto_open_console_on_launch: bool,

    // 3D Skin Standee Settings
    #[serde(default = "default_skin_animation")]
    pub skin_animation: String,
    #[serde(default = "default_skin_speed")]
    pub skin_speed: f64,
    #[serde(default = "default_skin_facing")]
    pub skin_facing: String,
    #[serde(default = "default_skin_cape_key")]
    pub skin_cape_key: String,
    #[serde(default = "default_skin_equip_type")]
    pub skin_equip_type: String,
    #[serde(default)]
    pub skin_anonymous_skin: bool,
    #[serde(default)]
    pub skin_anonymous_nametag: bool,

    // Setup Wizard
    #[serde(default, rename = "Finished_setup")]
    pub finished_setup_upper: bool,
    #[serde(default)]
    pub setup_finished: bool,
}

fn default_skin_animation() -> String { "walk".to_string() }
fn default_skin_speed() -> f64 { 0.5 }
fn default_skin_facing() -> String { "left".to_string() }
fn default_skin_cape_key() -> String { "migrator".to_string() }
fn default_skin_equip_type() -> String { "cape".to_string() }

fn default_true() -> bool { true }
fn default_on_game_close() -> String { "show".to_string() }
fn default_on_launcher_close() -> String { "tray".to_string() }
fn default_music_volume() -> u32 { 50 }
fn default_music_switch_behavior() -> String { "pause".to_string() }
fn default_music_lower_percent() -> u32 { 30 }
fn default_accent_color() -> String { "#B7B7B7".to_string() }
fn default_bg_color() -> String { "#0a0a0f".to_string() }
fn default_panel_bg_color() -> String { "#13131a".to_string() }
fn default_text_color() -> String { "#e2e2ea".to_string() }
fn default_log_bg_color() -> String { "#060608".to_string() }
fn default_font_family() -> String { "JetBrains Mono, Fira Code, Consolas, Monaco, monospace".to_string() }
fn default_background_style() -> String { "Default".to_string() }
fn default_bg_anim_style() -> String { "Waves".to_string() }
fn default_speed() -> f64 { 1.0 }
fn default_fps() -> u32 { 60 }
fn default_header_bg() -> String { "#111116".to_string() }
fn default_search_bg() -> String { "#1a1a24".to_string() }
fn default_notif_bg() -> String { "#13131a".to_string() }
fn default_notif_style() -> String { "Minimal Outline".to_string() }
fn default_bg_fit() -> String { "Cover".to_string() }
fn default_bg_dim() -> u32 { 35 }
fn default_bg_anim_intensity() -> f64 { 1.0 }
fn default_blur_strength() -> u32 { 10 }
fn default_ram_gb() -> u32 { 3 }
fn default_launcher_max_ram() -> u32 { 500 }
fn default_threads() -> u32 { 3 }
fn default_rpc_custom_state() -> String { "In Zero Launcher".to_string() }
fn default_rpc_app_id() -> String { "1131048770109460500".to_string() }

/// Platform-aware default game directory, used whenever the
/// Settings → Performance & Java "Minecraft Game Directory" field is left
/// blank.
///
/// - Linux: `~/.minecraft` (the same path the vanilla/official launcher uses).
/// - Windows: `%appdata%/.minecraft` (same as the vanilla launcher).
/// - macOS/other: `~/.minecraft` as a reasonable fallback.
pub fn default_game_directory() -> std::path::PathBuf {
    if cfg!(target_os = "windows") {
        dirs::config_dir()
            .map(|d| d.join(".minecraft"))
            .unwrap_or_else(|| std::path::PathBuf::from(".minecraft"))
    } else {
        dirs::home_dir()
            .map(|d| d.join(".minecraft"))
            .unwrap_or_else(|| std::path::PathBuf::from(".minecraft"))
    }
}

impl LauncherSettings {
    /// The directory that actually holds `versions/`, `libraries/`,
    /// `assets/`, etc. — i.e. `game_directory` if the user set one in
    /// Settings → Performance & Java, otherwise the platform default
    /// (`~/.minecraft` on Linux, `%appdata%/.minecraft` on Windows).
    ///
    /// `game_directory` itself is left blank by default and stays blank on
    /// disk until the user explicitly picks a folder — this is only ever
    /// used to resolve where to actually read/write files.
    pub fn resolved_game_directory(&self) -> std::path::PathBuf {
        let trimmed = self.game_directory.trim();
        if trimmed.is_empty() || !std::path::Path::new(trimmed).is_absolute() {
            default_game_directory()
        } else {
            std::path::PathBuf::from(trimmed)
        }
    }
}

impl Default for LauncherSettings {
    fn default() -> Self {
        Self {
            // Blank by default - resolved on demand via
            // `resolved_game_directory()` so the Settings field shows an
            // empty box (with the platform default as a placeholder)
            // until the user chooses their own folder.
            game_directory: String::new(),
            auto_check_launcher_updates: false,
            update_prompt_dont_ask_again: false,
            jvm_args: String::new(),
            max_ram_mb: 4096,
            min_ram_mb: 512,
            java_path: None,
            always_launch_offline: false,

            accent_color: default_accent_color(),
            bg_color: default_bg_color(),
            panel_bg_color: default_panel_bg_color(),
            text_color: default_text_color(),
            log_bg_color: default_log_bg_color(),
            font_family: default_font_family(),
            custom_font_paths: String::new(),
            background_style: default_background_style(),
            enable_background_animation: true,
            background_animation_style: default_bg_anim_style(),
            background_animation_speed: 1.0,
            background_animation_intensity: 1.0,
            background_animation_fps: 60,
            header_bg_color: default_header_bg(),
            search_bg_color: default_search_bg(),
            notification_bg_color: default_notif_bg(),
            notification_style: default_notif_style(),
            use_background_image: false,
            background_image_path: String::new(),
            background_image_fit: default_bg_fit(),
            background_image_dim: 35,
            background_image_tint: false,
            background_image_vignette: true,
            enable_transparency: true,
            enable_blur_effect: false,
            blur_strength: 10,

            minimize_on_launch: false,
            restore_launcher_on_game_close: true,
            enable_system_tray: true,
            close_after_launch: true,
            tray_notification_shown: false,
            on_game_close: default_on_game_close(),
            on_launcher_close: default_on_launcher_close(),
            always_hide_to_tray: false,
            show_console_on_launch: true,
            scan_on_startup: false,
            show_hidden_instances: false,
            smooth_scrolling: true,
            check_mod_updates_on_startup: true,
            refresh_discover_on_launch: true,
            auto_refresh_mods_on_version_load_fail: true,
            confirm_destructive_actions: true,
            sound_effects_enabled: true,
            auto_apply_instance_filters_in_discover: true,
            notify_on_auto_mod_updates: true,

            default_ram_gb: 3,
            launcher_max_ram_mb: 500,
            enable_launcher_max_ram: true,
            extra_jvm_args: String::new(),
            download_threads_auto: true,
            download_threads: 3,

            launcher_width: 1400,
            launcher_height: 800,
            start_maximized: true,
            default_minecraft_dir: String::new(),

            hide_username: false,
            redact_paths: true,
            redact_tokens: true,
            clear_session_on_exit: false,
            hide_launch_command: true,

            microsoft_client_id: String::new(),

            enable_discord_rpc: true,
            rpc_show_in_launcher: true,
            rpc_show_instance_name: true,
            rpc_show_minecraft_version: true,
            rpc_show_server_ip: false,
            rpc_show_game_state: false,
            rpc_custom_state_text: default_rpc_custom_state(),
            rpc_app_id: default_rpc_app_id(),
            rpc_show_launcher_activity: false,
            rpc_tab_instances: true,
            rpc_tab_mods: true,
            rpc_tab_settings: true,
            rpc_tab_logs: true,
            rpc_state_launching: false,
            rpc_state_main_menu: false,
            rpc_state_singleplayer: false,
            rpc_state_multiplayer: false,

            unlock_dev_stuff: false,
            debug_mode: false,
            private_servers_ips: String::new(),

            music_enabled: false,
            music_volume: default_music_volume(),
            music_switch_behavior: default_music_switch_behavior(),
            music_lower_percent: default_music_lower_percent(),
            music_disabled_tracks: Vec::new(),

            enable_crash_analysis: false,
            auto_open_console_on_launch: false,

            skin_animation: default_skin_animation(),
            skin_speed: default_skin_speed(),
            skin_facing: default_skin_facing(),
            skin_cape_key: default_skin_cape_key(),
            skin_equip_type: default_skin_equip_type(),
            skin_anonymous_skin: false,
            skin_anonymous_nametag: false,

            finished_setup_upper: false,
            setup_finished: false,
        }
    }
}

// ── Mod Info ─────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModInfo {
    pub file_name: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub loader: String,
    pub enabled: bool,
    pub path: String,
    // SHA-1 of the jar's bytes. Same method as the Java client's ModEntry/
    // ModUpdateService: identify the mod on Modrinth by exact file hash
    // (via /version_files) instead of a fuzzy text search on its display
    // name, which is what let mods like Cloth Config slip through with no
    // icon (name-based search doesn't reliably resolve to the right project,
    // or any project at all, for every mod).
    pub sha1: Option<String>,
}

// ── Log Entry ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    pub timestamp: String,
    pub level: String,
    pub source: String,
    pub message: String,
}

impl LogEntry {
    pub fn new(level: &str, source: &str, message: &str) -> Self {
        Self {
            timestamp: chrono::Local::now().format("%H:%M:%S%.3f").to_string(),
            level: level.to_string(),
            source: source.to_string(),
            message: message.to_string(),
        }
    }
}

// ── Version Info ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionInfo {
    pub id: String,
    #[serde(rename = "type")]
    pub version_type: String,
    #[serde(rename = "releaseTime", default)]
    pub release_time: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: LatestVersion,
    pub versions: Vec<VersionInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LatestVersion {
    pub release: String,
    pub snapshot: String,
}

// ── Installed Instance ───────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledInstance {
    /// User-facing display name (defaults to the version/loader label if
    /// none was given at install time).
    #[serde(default)]
    pub name: String,
    pub version_id: String,
    pub minecraft_version: String,
    pub loader: String,
    pub loader_version: String,
    /// The instance's own "game directory" — where saves, mods,
    /// resourcepacks, config, and logs for this instance live. This is the
    /// custom path the user picked at install time, or the default game
    /// directory if none was given.
    pub directory: String,
    /// Where this instance's `versions/`, `libraries/`, and `assets/`
    /// actually live on disk. These core game files always live in the
    /// default `.minecraft`-style directory (the same place a vanilla
    /// install would put them), regardless of what custom `directory` was
    /// chosen above, so they're downloaded once and shared by every
    /// instance instead of being duplicated per custom install path.
    ///
    /// `#[serde(default)]` + the empty-string fallback keeps this
    /// backward-compatible with `instances.json` files saved before this
    /// field existed: any instance missing it just falls back to using its
    /// own `directory` as before (the old, non-split behavior).
    #[serde(default)]
    pub minecraft_directory: String,
    pub installed_at: String,
    /// Cumulative time this instance's game process has actually been
    /// running, in seconds, summed across every session (added to once
    /// per session, when that session's process exits — see
    /// `commands::minecraft::accumulate_playtime`). Does **not** include
    /// time from a session that's still in progress; the frontend adds
    /// that live, on top of this, for an instance that's currently
    /// running.
    #[serde(default)]
    pub total_playtime_seconds: u64,
    /// When this instance was last launched (RFC 3339 timestamp), set right
    /// as Play is pressed — see `commands::minecraft::launch_minecraft`.
    /// `None` for an instance that's never been launched.
    #[serde(default)]
    pub last_played_at: Option<String>,
}

impl InstalledInstance {
    /// The directory that actually holds `versions/`, `libraries/`, and
    /// `assets/` for this instance — `minecraft_directory` if set, falling
    /// back to `directory` for instances saved before the split existed.
    pub fn minecraft_dir(&self) -> String {
        if self.minecraft_directory.trim().is_empty() {
            self.directory.clone()
        } else {
            self.minecraft_directory.clone()
        }
    }
}

// ── Locally Scanned Versions ──────────────────────────────────────────────────

/// A Minecraft version folder found on disk under `<game_dir>/versions/`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalVersionInfo {
    /// Folder / version id, e.g. "1.21.1" or "fabric-loader-0.16.5-1.21.1".
    pub id: String,
    /// Whether a matching `<id>.jar` file was found alongside the json.
    pub has_jar: bool,
    /// The Minecraft version this entry targets, if discoverable in its json
    /// (falls back to `id` when it can't be determined, e.g. modded loaders
    /// that reference a parent version instead of stating one directly).
    pub minecraft_version: Option<String>,
    /// Loader type guessed from the version id ("vanilla", "fabric",
    /// "forge", "quilt", "neoforge", or "unknown").
    pub loader: String,
    /// Absolute path to this version's folder.
    pub path: String,
}

// ── Cached (already downloaded) Versions ──────────────────────────────────────

/// A distinct (minecraft_version, loader, loader_version) combination that's
/// already installed for at least one tracked instance — i.e. something a
/// new instance install could reuse from disk instead of downloading.
/// Built straight from in-memory state, so it's available instantly without
/// touching the network (unlike the full version manifest).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedVersionInfo {
    pub minecraft_version: String,
    pub loader: String,
    pub loader_version: String,
}

// ── Install Request (Frontend → Backend) ─────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstallRequestPayload {
    pub minecraft_version: String,
    pub loader: String,          // "vanilla", "fabric", "quilt", "forge", "neoforge"
    pub loader_version: String,  // "latest" or specific version
    pub directory: Option<String>,
    /// Optional user-chosen display name for this instance.
    #[serde(default)]
    pub name: Option<String>,
    /// When this install is actually a reinstall of an existing instance
    /// (its Minecraft version and/or loader changed), the version_id it's
    /// replacing. Letting the backend know this is happening lets it free
    /// up the old instance-named version folder *before* picking a name
    /// for the new one, so the new install can reclaim the exact same
    /// folder name instead of getting suffixed with "(2)".
    #[serde(default)]
    pub old_version_id: Option<String>,
}

// ── Install Progress ─────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstallProgress {
    pub stage: String,
    pub message: String,
    pub complete: bool,
    pub error: Option<String>,
}

// ── Game Status ──────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GameStatus {
    pub running: bool,
    pub version_id: Option<String>,
    pub pid: Option<u32>,
}

// ── Running / Ran Instances ─────────────────────────────────────────────────

/// Tracks a launched instance for the lifetime of the launcher session, so
/// the UI can show which instances are currently running (and which ones
/// were run earlier this session) along with a dedicated console for each.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunningInstanceInfo {
    pub version_id: String,
    pub name: String,
    pub minecraft_version: String,
    pub loader: String,
    pub pid: Option<u32>,
    pub started_at: String,
    /// True while the process is alive; flips to false on exit but the
    /// entry is kept so the console history stays reachable.
    pub running: bool,
}

/// A single console line tagged with the instance it belongs to, emitted on
/// the `instance-log` event so per-instance console windows can filter to
/// just their own output.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstanceLogEvent {
    pub version_id: String,
    pub entry: LogEntry,
}
