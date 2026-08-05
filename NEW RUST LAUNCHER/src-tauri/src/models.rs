use serde::{Deserialize, Serialize};

// ── Account ──────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountInfo {
    pub id: String,
    pub username: String,
    pub account_type: String, // "offline" or "microsoft"
    pub is_active: bool,
}

// ── Download / install progress ────────────────────────────────────────────

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
    /// Name of the file currently being processed.
    pub current_file: String,
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
pub struct LauncherSettings {
    pub game_directory: String,
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

    // Appearance
    #[serde(default = "default_theme_mode")]
    pub theme_mode: String,
    // Deprecated: superseded by accent_color_dark / accent_color_light below.
    // Kept (with its old default) purely so the frontend can migrate a
    // pre-existing single accent color into the new per-theme fields; the
    // backend itself no longer reads it for rendering.
    #[serde(default = "default_accent_color")]
    pub accent_color: String,
    #[serde(default = "default_accent_color_dark")]
    pub accent_color_dark: String,
    #[serde(default = "default_accent_color_light")]
    pub accent_color_light: String,
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
    #[serde(default = "default_true")]
    pub restore_launcher_on_game_close: bool,
    #[serde(default = "default_true")]
    pub enable_system_tray: bool,
    #[serde(default)]
    pub close_after_launch: bool,
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
    #[serde(default = "default_true")]
    pub download_threads_auto: bool,
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
}

fn default_true() -> bool { true }
fn default_music_volume() -> u32 { 50 }
fn default_music_switch_behavior() -> String { "pause".to_string() }
fn default_music_lower_percent() -> u32 { 30 }
fn default_theme_mode() -> String { "system".to_string() }
fn default_accent_color() -> String { "#10b981".to_string() }
fn default_accent_color_dark() -> String { "#B7B7B7".to_string() }
fn default_accent_color_light() -> String { "#1A1A1A".to_string() }
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
fn default_threads() -> u32 { 8 }
fn default_rpc_custom_state() -> String { "In Zero Launcher".to_string() }
fn default_rpc_app_id() -> String { "1131048770109460500".to_string() }

/// Platform-aware default game directory.
///
/// - Linux: `~/.minecraft` (the same path the vanilla/official launcher uses).
/// - Windows/macOS/other: `~/Zero Launcher`.
pub fn default_game_directory() -> std::path::PathBuf {
    if cfg!(target_os = "linux") {
        dirs::home_dir()
            .map(|d| d.join(".minecraft"))
            .unwrap_or_else(|| std::path::PathBuf::from(".minecraft"))
    } else {
        dirs::home_dir()
            .map(|d| d.join("Zero Launcher"))
            .unwrap_or_else(|| std::path::PathBuf::from("Zero Launcher"))
    }
}

impl Default for LauncherSettings {
    fn default() -> Self {
        let default_dir = default_game_directory();

        Self {
            game_directory: default_dir.to_string_lossy().to_string(),
            jvm_args: String::new(),
            max_ram_mb: 4096,
            min_ram_mb: 512,
            java_path: None,

            theme_mode: default_theme_mode(),
            accent_color: default_accent_color(),
            accent_color_dark: default_accent_color_dark(),
            accent_color_light: default_accent_color_light(),
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
            close_after_launch: false,
            show_console_on_launch: true,
            scan_on_startup: false,
            show_hidden_instances: false,
            smooth_scrolling: true,
            check_mod_updates_on_startup: true,
            refresh_discover_on_launch: true,
            auto_refresh_mods_on_version_load_fail: true,
            confirm_destructive_actions: true,
            auto_apply_instance_filters_in_discover: true,
            notify_on_auto_mod_updates: true,

            default_ram_gb: 3,
            launcher_max_ram_mb: 500,
            enable_launcher_max_ram: true,
            extra_jvm_args: String::new(),
            download_threads_auto: true,
            download_threads: 8,

            launcher_width: 1400,
            launcher_height: 800,
            start_maximized: true,
            default_minecraft_dir: String::new(),

            hide_username: false,
            redact_paths: true,
            redact_tokens: true,
            clear_session_on_exit: false,
            hide_launch_command: true,

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
