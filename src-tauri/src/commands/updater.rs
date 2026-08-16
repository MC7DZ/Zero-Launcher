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
#[tauri::command]
pub async fn download_update(
    app: AppHandle,
    state: State<'_, AppState>,
    url: String,
) -> Result<String, String> {
    let updates_dir = state.data_dir.join("updates");
    std::fs::create_dir_all(&updates_dir)
        .map_err(|e| format!("Failed to create updates folder: {e}"))?;

    let file_name = url
        .rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or(if cfg!(target_os = "windows") {
            "ZeroLauncher-Update.exe"
        } else {
            "ZeroLauncher-Update.AppImage"
        });
    let dest_path = updates_dir.join(file_name);

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
/// running exe/AppImage. Used by the "you can grab the exe/AppImage from
/// here and scan it yourself" trust note in the update window, so people
/// who don't trust the launcher can find the actual file to run through
/// VirusTotal themselves.
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

/// Replace the currently-running executable/AppImage with the downloaded
/// update. `relaunch` controls whether the app starts itself back up
/// afterwards — this is the "Relaunch after update installs" toggle in the
/// update window, off by default. Note that on Windows the running process
/// always has to exit for the file swap to happen (Windows won't let you
/// overwrite a running .exe), so `relaunch: false` there still closes the
/// app — it just skips the "start it back up" step. On Linux the file can
/// be swapped while still running, so with `relaunch: false` the app simply
/// keeps running on the old code in memory and returns normally; the new
/// version takes effect next time it's launched.
#[tauri::command]
pub fn install_update(downloaded_path: String, relaunch: bool) -> Result<(), String> {
    let downloaded_path = PathBuf::from(downloaded_path);
    if !downloaded_path.is_file() {
        return Err("Downloaded update file is missing.".to_string());
    }

    #[cfg(target_os = "windows")]
    {
        install_update_windows(&downloaded_path, relaunch)
    }
    #[cfg(not(target_os = "windows"))]
    {
        install_update_linux(&downloaded_path, relaunch)
    }
}

/// Windows can't overwrite a running .exe, so a tiny helper batch script is
/// spawned (detached from us) that waits a moment for this process to fully
/// exit, moves the downloaded file over the current exe, optionally
/// relaunches it, then deletes itself. We exit right after spawning it
/// either way, since the move can't happen until we're gone.
#[cfg(target_os = "windows")]
fn install_update_windows(downloaded_path: &std::path::Path, relaunch: bool) -> Result<(), String> {
    use std::os::windows::process::CommandExt;

    let current_exe =
        std::env::current_exe().map_err(|e| format!("Failed to locate running exe: {e}"))?;

    let start_line = if relaunch {
        format!("start \"\" \"{current}\"\r\n", current = current_exe.display())
    } else {
        String::new()
    };
    let script_path = std::env::temp_dir().join("zerolauncher_update.bat");
    let script = format!(
        "@echo off\r\n\
         timeout /t 2 /nobreak > NUL\r\n\
         move /Y \"{new}\" \"{current}\"\r\n\
         {start_line}\
         del \"%~f0\"\r\n",
        new = downloaded_path.display(),
        current = current_exe.display(),
        start_line = start_line,
    );
    std::fs::write(&script_path, script)
        .map_err(|e| format!("Failed to write updater script: {e}"))?;

    const CREATE_NO_WINDOW: u32 = 0x08000000;
    std::process::Command::new("cmd")
        .args(["/C", &script_path.to_string_lossy()])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Failed to launch updater: {e}"))?;

    std::process::exit(0);
}

/// Linux (including AppImage) allows replacing a file that's currently
/// executing — the running process keeps its old inode open until it
/// exits, and the path just points at the new file from then on. So we can
/// swap the file directly with no helper script, and don't have to exit
/// unless the caller actually asked to relaunch.
#[cfg(not(target_os = "windows"))]
fn install_update_linux(downloaded_path: &std::path::Path, relaunch: bool) -> Result<(), String> {
    // Prefer $APPIMAGE (the real AppImage path) when running as an
    // AppImage — `current_exe()` there resolves into the temporary
    // squashfs mount, not the actual file on disk.
    let current_exe = std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok())
        .ok_or_else(|| "Failed to locate the running executable.".to_string())?;

    // `rename` is atomic and works even while the old file is running, but
    // only within the same filesystem — fall back to copy+remove for the
    // (rarer) case where the update was downloaded to a different device.
    if std::fs::rename(downloaded_path, &current_exe).is_err() {
        std::fs::copy(downloaded_path, &current_exe)
            .map_err(|e| format!("Failed to replace current executable: {e}"))?;
        let _ = std::fs::remove_file(downloaded_path);
    }

    use std::os::unix::fs::PermissionsExt;
    if let Ok(metadata) = std::fs::metadata(&current_exe) {
        let mut perms = metadata.permissions();
        perms.set_mode(0o755);
        let _ = std::fs::set_permissions(&current_exe, perms);
    }

    if !relaunch {
        // File is swapped; the currently-running process just keeps going
        // on the old code until the user quits and starts it again.
        return Ok(());
    }

    #[allow(unused_mut)]
    let mut relaunch_cmd = std::process::Command::new(&current_exe);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        relaunch_cmd.creation_flags(CREATE_NO_WINDOW);
    }
    relaunch_cmd
        .spawn()
        .map_err(|e| format!("Failed to relaunch after update: {e}"))?;

    std::process::exit(0);
}
