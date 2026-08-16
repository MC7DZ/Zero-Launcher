//! Self-update system.
//!
//! Reads a small JSON manifest hosted on GitHub (raw.githubusercontent.com
//! works well for this — it's just a plain file in your repo) that lists the
//! latest version + download link per OS. If the manifest's version is newer
//! than the version currently running, the frontend offers to download and
//! install it.
//!
//! ── Manifest format ─────────────────────────────────────────────────────
//! Host a `version.json` file (name doesn't matter) in a GitHub repo with
//! this shape:
//!
//! ```json
//! {
//!   "windows": {
//!     "version": "1.3.0",
//!     "url": "https://github.com/you/repo/releases/download/v1.3.0/ZeroLauncher-Setup.exe",
//!     "size_mb": 45.2,
//!     "changelog": ["Fixed crash on launch", "Faster mod downloads"]
//!   },
//!   "linux": {
//!     "version": "1.3.0",
//!     "url": "https://github.com/you/repo/releases/download/v1.3.0/ZeroLauncher.AppImage",
//!     "size_mb": 48.7,
//!     "changelog": ["Fixed crash on launch", "Faster mod downloads"]
//!   }
//! }
//! ```
//!
//! `size_mb` is only used for the "(45.2 MB)" text in the update prompt —
//! it's fine to leave it out or slightly wrong.
//!
//! `changelog` is optional too. Give it a short list of plain-text bullet
//! points describing what's new in that version — the update prompt shows
//! them under a "What's new" heading. Leave it out (or empty) and the
//! prompt just skips that section.
//!
//! Then set [`MANIFEST_URL`] below to the *raw* URL of that file, e.g.
//! `https://raw.githubusercontent.com/you/repo/main/version.json`.
//! That's the only line you need to edit to point this at your repo.

use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, State};

use crate::state::AppState;

/// ── EDIT ME ──────────────────────────────────────────────────────────────
/// Raw URL of the JSON manifest described above. Use the "raw" GitHub URL
/// (raw.githubusercontent.com), not the normal github.com page URL.
const MANIFEST_URL: &str =
    "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/version.json";
/// ─────────────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct OsUpdateEntry {
    version: String,
    url: String,
    #[serde(default)]
    size_mb: Option<f64>,
    /// Short plain-text bullet points describing what changed in this
    /// version. Optional — an absent or empty list just means the update
    /// prompt won't show a "What's new" section.
    #[serde(default)]
    changelog: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct UpdateManifest {
    windows: Option<OsUpdateEntry>,
    linux: Option<OsUpdateEntry>,
}

/// What the frontend gets back when an update is available.
#[derive(Debug, Serialize, Clone)]
pub struct UpdateAvailable {
    pub version: String,
    pub url: String,
    pub size_mb: Option<f64>,
    pub changelog: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
struct UpdateProgress {
    downloaded_bytes: u64,
    total_bytes: Option<u64>,
}

/// Very small semver-ish comparator: splits on '.', compares numeric parts
/// left to right, missing parts treated as 0. Good enough for "1.2.0" style
/// versions; non-numeric parts (e.g. "1.2.0-beta") compare that segment as
/// 0 rather than failing outright.
fn version_is_newer(remote: &str, current: &str) -> bool {
    let parse = |v: &str| -> Vec<u64> {
        v.trim_start_matches('v')
            .split('.')
            .map(|part| {
                part.chars()
                    .take_while(|c| c.is_ascii_digit())
                    .collect::<String>()
                    .parse::<u64>()
                    .unwrap_or(0)
            })
            .collect()
    };
    let r = parse(remote);
    let c = parse(current);
    for i in 0..r.len().max(c.len()) {
        let rv = r.get(i).copied().unwrap_or(0);
        let cv = c.get(i).copied().unwrap_or(0);
        if rv != cv {
            return rv > cv;
        }
    }
    false
}

fn current_os_key() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    }
}

/// Check the manifest for a newer version than the one currently running.
/// Returns `Ok(None)` (not an error) if there's no manifest entry for this
/// OS, or the manifest version isn't newer — the frontend just does nothing
/// in that case. Network/parse failures ARE returned as `Err` so callers
/// can choose to ignore them quietly on a background startup check.
#[tauri::command]
pub async fn check_for_update() -> Result<Option<UpdateAvailable>, String> {
    let resp = reqwest::Client::new()
        .get(MANIFEST_URL)
        .header("User-Agent", "ZeroLauncher-Updater")
        .send()
        .await
        .map_err(|e| format!("Failed to reach update server: {e}"))?
        .error_for_status()
        .map_err(|e| format!("Update server returned an error: {e}"))?;

    let manifest: UpdateManifest = resp
        .json()
        .await
        .map_err(|e| format!("Update manifest was not valid JSON: {e}"))?;

    let entry = match current_os_key() {
        "windows" => manifest.windows,
        _ => manifest.linux,
    };
    let Some(entry) = entry else { return Ok(None) };

    let current_version = env!("CARGO_PKG_VERSION");
    if version_is_newer(&entry.version, current_version) {
        Ok(Some(UpdateAvailable {
            version: entry.version,
            url: entry.url,
            size_mb: entry.size_mb,
            changelog: entry.changelog,
        }))
    } else {
        Ok(None)
    }
}

/// Download the update file to `<data_dir>/updates/`, emitting
/// `update-download-progress` events as it goes. Returns the path to the
/// downloaded file so the frontend can pass it to [`install_update`].
/// Download the update file to the cache folder (`<data_dir>/cache/`), emitting
/// `update-download-progress` events as it goes. Returns the path to the
/// downloaded file so the frontend can pass it to [`install_update`].
#[tauri::command]
pub async fn download_update(
    app: AppHandle,
    _state: State<'_, AppState>,
    url: String,
) -> Result<String, String> {
    let cache_dir = crate::first_run_setup::cache_dir();
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("Failed to create cache folder: {e}"))?;

    let file_name = url
        .rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or(if cfg!(target_os = "windows") {
            "ZeroLauncher-Update.exe"
        } else {
            "ZeroLauncher-Update.AppImage"
        });
    let dest_path = cache_dir.join(file_name);

    let response = reqwest::Client::new()
        .get(&url)
        .header("User-Agent", "ZeroLauncher-Updater")
        .send()
        .await
        .map_err(|e| format!("Failed to start download: {e}"))?
        .error_for_status()
        .map_err(|e| format!("Download server returned an error: {e}"))?;

    let total_bytes = response.content_length();
    let mut downloaded_bytes: u64 = 0;

    let mut file = std::fs::File::create(&dest_path)
        .map_err(|e| format!("Failed to create update file: {e}"))?;

    let mut response = response;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| format!("Download interrupted: {e}"))?
    {
        file.write_all(&chunk)
            .map_err(|e| format!("Failed to write update file: {e}"))?;
        downloaded_bytes += chunk.len() as u64;
        let _ = app.emit(
            "update-download-progress",
            UpdateProgress {
                downloaded_bytes,
                total_bytes,
            },
        );
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(&dest_path)
            .map_err(|e| format!("Failed to read update file: {e}"))?
            .permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&dest_path, perms)
            .map_err(|e| format!("Failed to make update file executable: {e}"))?;
    }

    Ok(dest_path.to_string_lossy().to_string())
}

/// Opens the system file manager at the folder containing the currently
/// running exe/AppImage.
#[tauri::command]
pub fn open_current_exe_folder() -> Result<(), String> {
    let current_exe = std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok())
        .ok_or_else(|| "Failed to locate the running executable.".to_string())?;

    let dir = current_exe
        .parent()
        .ok_or_else(|| "Failed to locate the executable's folder.".to_string())?;

    open::that(dir).map_err(|e| format!("Failed to open folder: {e}"))
}

/// Cleans up leftover update temporary files and old executable backups.
/// Should be called on app startup.
pub fn cleanup_updater_leftovers(data_dir: &std::path::Path) {
    let updates_dir = data_dir.join("updates");
    if let Ok(entries) = std::fs::read_dir(&updates_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let _ = std::fs::remove_file(&path);
        }
    }

    let cache_dir = crate::first_run_setup::cache_dir();
    if let Ok(entries) = std::fs::read_dir(&cache_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let _ = std::fs::remove_file(&path);
        }
    }

    if let Ok(current_exe) = std::env::current_exe() {
        if let Some(parent) = current_exe.parent() {
            let old_exe = parent.join(format!(
                "{}.old",
                current_exe.file_name().unwrap_or_default().to_string_lossy()
            ));
            if old_exe.is_file() {
                let _ = std::fs::remove_file(old_exe);
            }
        }
    }

    let temp = std::env::temp_dir();
    let _ = std::fs::remove_file(temp.join("zerolauncher_update_helper.ps1"));
    let _ = std::fs::remove_file(temp.join("zerolauncher_update_helper.bat"));
    let _ = std::fs::remove_file(temp.join("zerolauncher_update.bat"));
}

/// Launch the downloaded update from the cache folder and exit the current process.
/// The launched cached instance will detect it is running from cache, overwrite the
/// version in the Zero Launcher folder, and launch the newly installed copy.
#[tauri::command]
pub fn install_update(downloaded_path: String, _relaunch: bool) -> Result<(), String> {
    let downloaded_path = PathBuf::from(downloaded_path);
    if !downloaded_path.is_file() {
        return Err("Downloaded update file is missing.".to_string());
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = std::fs::metadata(&downloaded_path) {
            let mut perms = meta.permissions();
            perms.set_mode(0o755);
            let _ = std::fs::set_permissions(&downloaded_path, perms);
        }
    }

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        const DETACHED_PROCESS: u32 = 0x00000008;
        std::process::Command::new(&downloaded_path)
            .creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS)
            .spawn()
            .map_err(|e| format!("Failed to launch update from cache: {e}"))?;
        std::process::exit(0);
    }

    #[cfg(not(target_os = "windows"))]
    {
        let mut cmd = std::process::Command::new(&downloaded_path);
        cmd.env_remove("APPDIR");
        cmd.env_remove("APPIMAGE");
        cmd.env_remove("ARGV0");
        cmd.env_remove("OWD");
        cmd.spawn()
            .map_err(|e| format!("Failed to launch update from cache: {e}"))?;
        std::process::exit(0);
    }
}
