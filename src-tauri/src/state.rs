use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use crate::models::{AccountInfo, LauncherSettings, LogEntry, InstalledInstance, RunningInstanceInfo};

/// Central application state, managed by Tauri.
/// All fields are wrapped in Mutex for thread-safe access from commands.
pub struct AppState {
    pub accounts: Mutex<Vec<AccountInfo>>,
    pub settings: Mutex<LauncherSettings>,
    pub logs: Mutex<Vec<LogEntry>>,
    pub instances: Mutex<Vec<InstalledInstance>>,
    /// version_ids of instances the user chose to "Hide" instead of delete
    /// (currently only reachable from the vanilla-instance delete-dependency
    /// warning). Hidden instances stay fully installed on disk and any
    /// modded instance that depends on them keeps working — they're just
    /// left out of the main Instances list, and only listed (with an
    /// Unhide button) under Settings → Hidden Instances.
    /// Persisted alongside `instances.json` in the same `versions/` folder,
    /// so it travels with the `.minecraft` folder like the instance list does.
    pub hidden_instances: Mutex<Vec<String>>,
    /// Instances launched this session, keyed by version_id. An entry stays
    /// after the process exits (with `running: false`) so its console
    /// history remains reachable until the launcher restarts.
    pub running_instances: Mutex<HashMap<String, RunningInstanceInfo>>,
    /// Per-instance console output, keyed by version_id.
    pub instance_logs: Mutex<HashMap<String, Vec<LogEntry>>>,
    pub data_dir: PathBuf,
    /// Set while the user has paused the in-progress download/install.
    pub download_paused: AtomicBool,
    /// Set when the user requests the in-progress download/install to stop.
    pub download_cancelled: AtomicBool,
    /// Cancellation flags for "generic" downloads that aren't the single
    /// tracked instance install — mod downloads/updates, dependency
    /// installs, discover-tab downloads, Java runtime downloads, etc.
    /// Each such download is given its own id by the frontend (so several
    /// can run and be cancelled independently, each shown as its own card
    /// in the downloads menu) and registers/checks its flag here.
    pub generic_cancels: Mutex<HashMap<String, Arc<AtomicBool>>>,
    /// Open handle to `<data_dir>/logs/latest.log`, appended to on every
    /// log line for the lifetime of this run. `None` if the log file
    /// couldn't be opened (never fatal — logging still works in-memory and
    /// in the UI console, it just won't be persisted to disk that run).
    pub log_file: Mutex<Option<std::fs::File>>,
    /// Device-code sign-in currently in progress (if any) — set by
    /// `microsoft_device_code_start`, read/cleared by
    /// `microsoft_device_code_poll`. Only one device-code sign-in can be
    /// in flight at a time.
    pub device_code_session: Mutex<Option<String>>,
    /// In-memory cache of valid Minecraft access tokens / login sessions keyed by account id.
    /// Prevents repeated and concurrent OAuth refreshes that burn refresh tokens or rate limit.
    pub msa_session_cache: Mutex<HashMap<String, CachedMsaSession>>,
    /// Mutex ensuring only one Microsoft OAuth refresh occurs at a time globally.
    pub msa_refresh_lock: Arc<tokio::sync::Mutex<()>>,
    /// Last time the frontend reported the user actively doing something in
    /// the launcher window (mouse movement, clicks, typing) — see the
    /// `report_activity` command. Used by "Close launcher when game
    /// starts" → "Make it smart" to wait until the user has actually
    /// stepped away before closing the window out from under them.
    pub last_activity_at: Mutex<std::time::Instant>,
    /// Last advancement/goal/challenge log line seen per running instance
    /// (version_id -> (line, when)), used to dedupe the `game-advancement`
    /// event. Some mod loaders and singleplayer setups print the same
    /// "has made the advancement" announcement to the game log more than
    /// once in quick succession (e.g. once from the server thread and
    /// again as it's echoed back to the client) — without this, each of
    /// those would tick the live Game Advancements counter up separately
    /// for what is really a single advancement.
    pub recent_advancement_lines: Mutex<HashMap<String, (String, std::time::Instant)>>,
}

#[derive(Clone)]
pub struct CachedMsaSession {
    pub login: mc_launcher_core::types::microsoft_types::CompleteLoginResponse,
    pub expires_at: std::time::Instant,
}

const MAX_LOG_ENTRIES: usize = 10_000;
const MAX_INSTANCE_LOG_ENTRIES: usize = 5_000;

impl AppState {
    pub fn new(data_dir: PathBuf) -> Self {
        let mut settings = Self::load_settings_with_migration(&data_dir);
        // Older builds could persist a relative or otherwise bogus
        // game_directory (e.g. plain ".minecraft" resolved against the
        // process's current working directory, which — when launched via
        // `tauri dev` — is the project folder, not the user's home). If
        // what's on disk isn't an absolute path, fall back to the correct
        // platform default instead of silently installing into the project.
        if settings.game_directory.trim().is_empty()
            || !PathBuf::from(&settings.game_directory).is_absolute()
        {
            settings.game_directory = crate::models::default_game_directory()
                .to_string_lossy()
                .to_string();
        }
        let mut accounts = Self::load_json::<Vec<AccountInfo>>(&data_dir, "accounts.json")
            .unwrap_or_default();
        // Defensive normalization: older builds (or a hand-edited
        // accounts.json) could leave more than one account marked active
        // at once. Only the first one found wins; everything else is
        // corrected on load so the UI can never show two "in use" badges.
        crate::models::normalize_single_active_account(&mut accounts);

        // Instances are tracked per-game-directory, stored alongside the
        // actual version files at <game_directory>/versions/instances.json,
        // rather than in the app's own config folder — this way they travel
        // with the .minecraft folder itself and survive reinstalls of the
        // launcher.
        let versions_dir = settings.resolved_game_directory().join("versions");
        std::fs::create_dir_all(&versions_dir).ok();
        let instances = Self::load_json::<Vec<InstalledInstance>>(&versions_dir, "instances.json")
            .unwrap_or_default();
        let hidden_instances = Self::load_json::<Vec<String>>(&versions_dir, "hidden_instances.json")
            .unwrap_or_default();

        let log_file = crate::logger::init_log_file(&data_dir);

        Self {
            accounts: Mutex::new(accounts),
            settings: Mutex::new(settings),
            logs: Mutex::new(Vec::with_capacity(1000)),
            instances: Mutex::new(instances),
            hidden_instances: Mutex::new(hidden_instances),
            running_instances: Mutex::new(HashMap::new()),
            instance_logs: Mutex::new(HashMap::new()),
            data_dir,
            download_paused: AtomicBool::new(false),
            download_cancelled: AtomicBool::new(false),
            generic_cancels: Mutex::new(HashMap::new()),
            log_file: Mutex::new(log_file),
            device_code_session: Mutex::new(None),
            msa_session_cache: Mutex::new(HashMap::new()),
            msa_refresh_lock: Arc::new(tokio::sync::Mutex::new(())),
            last_activity_at: Mutex::new(std::time::Instant::now()),
            recent_advancement_lines: Mutex::new(HashMap::new()),
        }
    }

    /// True if the given generic download id has been cancelled. Also
    /// returns true (i.e. "treat as cancelled") for any id that hasn't been
    /// registered — that path shouldn't normally be hit since callers
    /// register the id before checking it.
    #[allow(dead_code)]
    pub fn is_generic_download_cancelled(&self, id: &str) -> bool {
        self.generic_cancels
            .lock()
            .unwrap()
            .get(id)
            .map(|flag| flag.load(std::sync::atomic::Ordering::Relaxed))
            .unwrap_or(false)
    }

    /// Returns `true` if this advancement/goal/challenge log line for the
    /// given instance is a fresh one that should be counted, or `false` if
    /// it's a near-duplicate of the last one seen for that same instance
    /// within the last few seconds (and so should be ignored). Records the
    /// line either way so the next call has something to compare against.
    pub fn record_advancement_line(&self, version_id: &str, line: &str) -> bool {
        const DEDUPE_WINDOW: std::time::Duration = std::time::Duration::from_secs(5);
        let now = std::time::Instant::now();
        let mut recent = self.recent_advancement_lines.lock().unwrap();
        if let Some((last_line, last_seen)) = recent.get(version_id) {
            if last_line == line && now.duration_since(*last_seen) < DEDUPE_WINDOW {
                return false;
            }
        }
        recent.insert(version_id.to_string(), (line.to_string(), now));
        true
    }

    /// Get (or create) the cancellation flag for a generic download id.
    pub fn generic_cancel_flag(&self, id: &str) -> Arc<AtomicBool> {
        self.generic_cancels
            .lock()
            .unwrap()
            .entry(id.to_string())
            .or_insert_with(|| Arc::new(AtomicBool::new(false)))
            .clone()
    }

    /// Called by a generic download when it finishes. Only clears the flag
    /// if it *wasn't* cancelled — a cancelled flag is left in place so that
    /// a batch operation reusing the same id for its next item (e.g.
    /// updating several mods back-to-back under one card) immediately sees
    /// the cancellation instead of silently continuing.
    pub fn finish_generic_download(&self, id: &str) {
        let mut map = self.generic_cancels.lock().unwrap();
        if let Some(flag) = map.get(id) {
            if !flag.load(std::sync::atomic::Ordering::Relaxed) {
                map.remove(id);
            }
        }
    }

    /// Fully clear a generic download id's cancellation state (used once a
    /// card is truly done, so a later, unrelated download can't reuse a
    /// stale id and be immediately treated as cancelled).
    #[allow(dead_code)]
    pub fn clear_generic_download(&self, id: &str) {
        self.generic_cancels.lock().unwrap().remove(id);
    }

    /// Add a log entry to the ring buffer.
    pub fn push_log(&self, entry: LogEntry) {
        let mut logs = self.logs.lock().unwrap();
        if logs.len() >= MAX_LOG_ENTRIES {
            logs.remove(0);
        }
        logs.push(entry);
    }

    /// Add a console line to a specific instance's console buffer (used by
    /// the per-instance console windows).
    pub fn push_instance_log(&self, version_id: &str, entry: LogEntry) {
        let mut logs = self.instance_logs.lock().unwrap();
        let buf = logs.entry(version_id.to_string()).or_insert_with(Vec::new);
        if buf.len() >= MAX_INSTANCE_LOG_ENTRIES {
            buf.remove(0);
        }
        buf.push(entry);
    }

    /// Persist accounts to disk.
    pub fn save_accounts(&self) {
        let accounts = self.accounts.lock().unwrap();
        Self::save_json(&self.data_dir, "accounts.json", &*accounts);
    }

    /// Persist settings to disk.
    pub fn save_settings_to_disk(&self) {
        let settings = self.settings.lock().unwrap();
        Self::save_json(&self.data_dir, "settings.json", &*settings);
    }

    /// Persist instances to disk, inside `<game_directory>/versions/instances.json`.
    pub fn save_instances(&self) {
        let instances = self.instances.lock().unwrap();
        let versions_dir = {
            let settings = self.settings.lock().unwrap();
            settings.resolved_game_directory().join("versions")
        };
        std::fs::create_dir_all(&versions_dir).ok();
        Self::save_json(&versions_dir, "instances.json", &*instances);
    }

    /// Persist the hidden-instances list to disk, inside
    /// `<game_directory>/versions/hidden_instances.json`.
    pub fn save_hidden_instances(&self) {
        let hidden = self.hidden_instances.lock().unwrap();
        let versions_dir = {
            let settings = self.settings.lock().unwrap();
            settings.resolved_game_directory().join("versions")
        };
        std::fs::create_dir_all(&versions_dir).ok();
        Self::save_json(&versions_dir, "hidden_instances.json", &*hidden);
    }

    /// Persist the currently-running instances to
    /// `<data_dir>/running_instances.json` (the "Zero Launcher" folder).
    ///
    /// This is what lets a running game survive the launcher fully quitting
    /// and restarting: the game process itself is launched detached (see
    /// `minecraft::launch_minecraft`), so it keeps running on its own, and
    /// this file is how the *next* launcher process finds out it's there —
    /// which instance, what pid, and when it started (so playtime can be
    /// recalculated) — without any other IPC between the two.
    ///
    /// Only entries that are still actually `running` are written; ones
    /// that have already exited are dropped from the file (their console
    /// history only needs to live in-memory for the session that saw them
    /// exit).
    pub fn save_running_instances(&self) {
        let running = self.running_instances.lock().unwrap();
        let live: Vec<&RunningInstanceInfo> = running.values().filter(|i| i.running).collect();
        let path = self.data_dir.join("running_instances.json");
        match serde_json::to_string_pretty(&live) {
            Ok(data) => {
                let _ = std::fs::write(&path, data);
            }
            Err(_) => {}
        }
    }

    /// Load whatever was last persisted to `running_instances.json`. Used
    /// once at startup, before we know which (if any) of those pids are
    /// still actually alive — the caller is responsible for checking that
    /// and discarding stale entries.
    pub fn load_persisted_running_instances(data_dir: &PathBuf) -> Vec<RunningInstanceInfo> {
        Self::load_json::<Vec<RunningInstanceInfo>>(data_dir, "running_instances.json")
            .unwrap_or_default()
    }

    /// Re-load the tracked instance list from the current game directory's
    /// `versions/instances.json`. Call this after the game directory changes
    /// (e.g. the user updates it in Settings) so the instance list reflects
    /// whichever `.minecraft` folder is now active.
    pub fn reload_instances_for_current_dir(&self) {
        let versions_dir = {
            let settings = self.settings.lock().unwrap();
            settings.resolved_game_directory().join("versions")
        };
        std::fs::create_dir_all(&versions_dir).ok();
        let loaded = Self::load_json::<Vec<InstalledInstance>>(&versions_dir, "instances.json")
            .unwrap_or_default();
        *self.instances.lock().unwrap() = loaded;
        let loaded_hidden = Self::load_json::<Vec<String>>(&versions_dir, "hidden_instances.json")
            .unwrap_or_default();
        *self.hidden_instances.lock().unwrap() = loaded_hidden;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fn load_json<T: serde::de::DeserializeOwned>(dir: &PathBuf, file: &str) -> Option<T> {
        let path = dir.join(file);
        let data = std::fs::read_to_string(&path).ok()?;
        serde_json::from_str(&data).ok()
    }

    /// Loads settings.json, migrating a pre-existing per-theme
    /// `accent_color_dark` field (from when the launcher had separate
    /// dark/light accent colors) back into the single `accent_color` field
    /// now that there's only one theme. Files already on the single-field
    /// format are left untouched.
    fn load_settings_with_migration(data_dir: &PathBuf) -> LauncherSettings {
        let path = data_dir.join("settings.json");
        let data = match std::fs::read_to_string(&path) {
            Ok(d) => d,
            Err(_) => return LauncherSettings::default(),
        };
        let mut settings: LauncherSettings = match serde_json::from_str(&data) {
            Ok(s) => s,
            Err(_) => return LauncherSettings::default(),
        };
        if let Ok(raw) = serde_json::from_str::<serde_json::Value>(&data) {
            if let Some(legacy_dark) = raw.get("accent_color_dark").and_then(|v| v.as_str()) {
                if !legacy_dark.is_empty() {
                    settings.accent_color = legacy_dark.to_string();
                }
            }

            // Migrate the old `restore_launcher_on_game_close` /
            // `enable_system_tray` booleans into the new `on_game_close` /
            // `on_launcher_close` Window Behavior fields, but only the
            // first time a settings.json saved before those fields existed
            // is read — once `on_game_close` is present on disk it's the
            // source of truth and this is skipped entirely.
            if raw.get("on_game_close").is_none() {
                settings.on_game_close = if settings.restore_launcher_on_game_close {
                    "show".to_string()
                } else {
                    "none".to_string()
                };
            }
            if raw.get("on_launcher_close").is_none() {
                settings.on_launcher_close = if settings.enable_system_tray {
                    "tray".to_string()
                } else {
                    "close".to_string()
                };
            }
        }
        settings
    }

    fn save_json<T: serde::Serialize>(dir: &PathBuf, file: &str, value: &T) {
        let path = dir.join(file);
        if let Ok(data) = serde_json::to_string_pretty(value) {
            let _ = std::fs::write(&path, data);
        }
    }

    /// Persists the main screen's "Global Stats" panel to
    /// `<data_dir>/stats.json` (the "Zero Launcher" folder), so the panel
    /// can show last-known values immediately on the next launch instead
    /// of appearing to reset while Mods Installed / Game Advancements are
    /// rescanned from disk.
    pub fn save_global_stats(&self, stats: &crate::models::GlobalStats) {
        Self::save_json(&self.data_dir, "stats.json", stats);
    }

    /// Loads the last-persisted Global Stats snapshot, if any (e.g. first
    /// run, or the file was deleted).
    pub fn load_global_stats(&self) -> Option<crate::models::GlobalStats> {
        Self::load_json(&self.data_dir, "stats.json")
    }
}
