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

use crate::logger;
use crate::network;
use crate::state::AppState;

/// Source tag used for every log line this module writes, so the log
/// viewer/`latest.log` can be filtered down to just update activity.
/// (Network-layer detail — which protocol/attempt/error — is logged under
/// the "Network" source by `network::send`; this tag covers the
/// update-system-specific steps around it.)
const LOG_SOURCE: &str = "Updater";

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
pub async fn check_for_update(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<UpdateAvailable>, String> {
    let current_version = env!("CARGO_PKG_VERSION");
    let os_key = current_os_key();
    logger::info(
        &app,
        &state,
        LOG_SOURCE,
        &format!("Checking for updates (running {current_version}, os={os_key})"),
    );

    let resp = network::send(&app, &state, "Update manifest fetch", |client| {
        client
            .get(MANIFEST_URL)
            .header("User-Agent", "ZeroLauncher-Updater")
    })
    .await
    .map_err(|e| format!("Unable to reach the update server. {e}"))?;

    let status = resp.status();
    let headers = resp.headers().clone();
    let body_text = resp.text().await.unwrap_or_default();

    if !status.is_success() {
        // Log everything we have about the failing response — status,
        // relevant headers, and the raw body (truncated so a misbehaving
        // server can't flood the log) — so a "server returned an error"
        // failure is fully diagnosable from latest.log without having to
        // reproduce it.
        let content_type = headers
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("unknown");
        let body_snippet: String = body_text.chars().take(2000).collect();
        logger::error(
            &app,
            &state,
            LOG_SOURCE,
            &format!(
                "Manifest fetch returned HTTP {status} (content-type: {content_type}, body {} bytes): {}",
                body_text.len(),
                if body_snippet.is_empty() { "<empty body>" } else { &body_snippet },
            ),
        );
        return Err(format!(
            "Update server returned HTTP {status}. {}",
            if body_snippet.is_empty() {
                "No response body.".to_string()
            } else {
                format!("Response: {body_snippet}")
            }
        ));
    }

    logger::debug(
        &app,
        &state,
        LOG_SOURCE,
        &format!("Manifest body ({} bytes): {}", body_text.len(), body_text.chars().take(4000).collect::<String>()),
    );

    let manifest: UpdateManifest = serde_json::from_str(&body_text).map_err(|e| {
        let body_snippet: String = body_text.chars().take(2000).collect();
        let msg = format!(
            "Update manifest was not valid JSON: {e}. Raw body ({} bytes): {}",
            body_text.len(),
            if body_snippet.is_empty() { "<empty body>" } else { &body_snippet },
        );
        logger::error(&app, &state, LOG_SOURCE, &msg);
        format!("Update manifest was not valid JSON: {e}")
    })?;

    let entry = match os_key {
        "windows" => manifest.windows,
        _ => manifest.linux,
    };
    let Some(entry) = entry else {
        logger::info(
            &app,
            &state,
            LOG_SOURCE,
            &format!("Manifest has no entry for os={os_key}; nothing to update"),
        );
        return Ok(None);
    };

    if version_is_newer(&entry.version, current_version) {
        logger::info(
            &app,
            &state,
            LOG_SOURCE,
            &format!("Update available: {current_version} -> {}", entry.version),
        );
        Ok(Some(UpdateAvailable {
            version: entry.version,
            url: entry.url,
            size_mb: entry.size_mb,
            changelog: entry.changelog,
        }))
    } else {
        logger::info(
            &app,
            &state,
            LOG_SOURCE,
            &format!(
                "Already up to date (running {current_version}, manifest has {})",
                entry.version
            ),
        );
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
    std::fs::create_dir_all(&updates_dir).map_err(|e| {
        let msg = format!("Failed to create updates folder: {e}");
        logger::error(&app, &state, LOG_SOURCE, &msg);
        msg
    })?;

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
    // Downloaded into a `.part` sidecar and only renamed to the real name on
    // success, so a half-downloaded file is never mistaken for a complete
    // one (e.g. by `install_update`'s `is_file()` check after a crash mid-
    // download). Also doubles as the resume checkpoint: if this file exists
    // from a previous failed attempt, we pick up where it left off instead
    // of re-downloading bytes we already have — the main thing that helps
    // on slow/unstable connections, where restarting from zero on every
    // interruption can mean the download never finishes.
    let part_path = updates_dir.join(format!("{file_name}.part"));

    let already_have: u64 = std::fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);

    logger::info(
        &app,
        &state,
        LOG_SOURCE,
        &format!(
            "Starting download: {url} -> {}{}",
            dest_path.display(),
            if already_have > 0 {
                format!(" (resuming from {:.1} MB)", already_have as f64 / 1_048_576.0)
            } else {
                String::new()
            }
        ),
    );

    let response = network::send(&app, &state, "Update download", |client| {
        let req = client.get(&url).header("User-Agent", "ZeroLauncher-Updater");
        if already_have > 0 {
            req.header("Range", format!("bytes={already_have}-"))
        } else {
            req
        }
    })
    .await
    .map_err(|e| format!("Unable to reach the download server. {e}"))?;

    let status = response.status();
    // A server that doesn't support Range requests replies 200 (whole file)
    // even though we asked for a range — in that case our partial bytes
    // don't line up with what's coming, so start over instead of corrupting
    // the file by appending mismatched data.
    let resuming = already_have > 0 && status.as_u16() == 206;
    if already_have > 0 && !resuming {
        logger::debug(
            &app,
            &state,
            LOG_SOURCE,
            &format!("Server returned HTTP {status} for a range request; restarting download from 0"),
        );
        let _ = std::fs::remove_file(&part_path);
    }
    let already_have = if resuming { already_have } else { 0 };

    if !status.is_success() && status.as_u16() != 206 {
        let headers = response.headers().clone();
        let body_text = response.text().await.unwrap_or_default();
        let content_type = headers
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("unknown");
        let body_snippet: String = body_text.chars().take(2000).collect();
        logger::error(
            &app,
            &state,
            LOG_SOURCE,
            &format!(
                "Download start returned HTTP {status} for {url} (content-type: {content_type}, body {} bytes): {}",
                body_text.len(),
                if body_snippet.is_empty() { "<empty body>" } else { &body_snippet },
            ),
        );
        return Err(format!(
            "Download server returned HTTP {status}. {}",
            if body_snippet.is_empty() {
                "No response body.".to_string()
            } else {
                format!("Response: {body_snippet}")
            }
        ));
    }

    // On a 206 (partial content) response, Content-Length is only the size
    // of the *remaining* bytes — add back what we already have on disk to
    // get the true total for progress reporting.
    let total_bytes = response.content_length().map(|remaining| remaining + already_have);
    logger::info(
        &app,
        &state,
        LOG_SOURCE,
        &match total_bytes {
            Some(t) => format!("Download size: {:.1} MB", t as f64 / 1_048_576.0),
            None => "Download size: unknown (no Content-Length header)".to_string(),
        },
    );
    let mut downloaded_bytes: u64 = already_have;
    // Logged at 10% increments (in addition to the UI's continuous
    // `update-download-progress` events) so a stalled/slow download shows
    // up clearly in latest.log without flooding it every chunk.
    let mut last_logged_decile: u64 = if let Some(t) = total_bytes.filter(|t| *t > 0) {
        downloaded_bytes * 10 / t
    } else {
        0
    };
    // The UI progress bar only needs updates a few times a second, not on
    // every TCP chunk — on a fast connection that chunk loop can run
    // thousands of times a second, and firing an IPC event every time just
    // burns CPU on both sides for no visible benefit. Emit at most ~15/sec.
    let mut last_progress_emit = std::time::Instant::now();
    const PROGRESS_EMIT_INTERVAL: std::time::Duration = std::time::Duration::from_millis(66);

    // Buffered so each incoming chunk doesn't force its own write() syscall
    // — chunks get batched into fewer, larger disk writes instead.
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(resuming)
        .write(true)
        .truncate(!resuming)
        .open(&part_path)
        .map_err(|e| {
            let msg = format!("Failed to create update file: {e}");
            logger::error(&app, &state, LOG_SOURCE, &msg);
            msg
        })?;
    let mut file = std::io::BufWriter::with_capacity(256 * 1024, file);

    let mut response = response;
    loop {
        let chunk = match response.chunk().await {
            Ok(Some(c)) => c,
            Ok(None) => break,
            Err(e) => {
                let _ = file.flush();
                logger::error(
                    &app,
                    &state,
                    LOG_SOURCE,
                    &format!(
                        "Download interrupted after {:.1} MB: {e}. Partial file kept at {} for resume on retry.",
                        downloaded_bytes as f64 / 1_048_576.0,
                        part_path.display(),
                    ),
                );
                return Err(format!("Download interrupted: {e}"));
            }
        };
        file.write_all(&chunk).map_err(|e| {
            let msg = format!("Failed to write update file: {e}");
            logger::error(&app, &state, LOG_SOURCE, &msg);
            msg
        })?;
        downloaded_bytes += chunk.len() as u64;

        if let Some(total) = total_bytes.filter(|t| *t > 0) {
            let decile = (downloaded_bytes * 10 / total).min(10);
            if decile > last_logged_decile {
                last_logged_decile = decile;
                logger::debug(
                    &app,
                    &state,
                    LOG_SOURCE,
                    &format!(
                        "Downloaded {:.1}/{:.1} MB ({}%)",
                        downloaded_bytes as f64 / 1_048_576.0,
                        total as f64 / 1_048_576.0,
                        decile * 10,
                    ),
                );
            }
        }

        if last_progress_emit.elapsed() >= PROGRESS_EMIT_INTERVAL {
            last_progress_emit = std::time::Instant::now();
            let _ = app.emit(
                "update-download-progress",
                UpdateProgress {
                    downloaded_bytes,
                    total_bytes,
                },
            );
        }
    }

    file.flush().map_err(|e| {
        let msg = format!("Failed to flush update file: {e}");
        logger::error(&app, &state, LOG_SOURCE, &msg);
        msg
    })?;
    drop(file);

    // Always emit one final, exact progress update — the throttling above
    // can otherwise leave the UI stuck a few percent short of 100%.
    let _ = app.emit(
        "update-download-progress",
        UpdateProgress {
            downloaded_bytes,
            total_bytes,
        },
    );

    std::fs::rename(&part_path, &dest_path).map_err(|e| {
        let msg = format!("Failed to finalize downloaded update file: {e}");
        logger::error(&app, &state, LOG_SOURCE, &msg);
        msg
    })?;

    logger::info(
        &app,
        &state,
        LOG_SOURCE,
        &format!(
            "Download complete: {:.1} MB written to {}",
            downloaded_bytes as f64 / 1_048_576.0,
            dest_path.display()
        ),
    );

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(&dest_path)
            .map_err(|e| {
                let msg = format!("Failed to read update file: {e}");
                logger::error(&app, &state, LOG_SOURCE, &msg);
                msg
            })?
            .permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&dest_path, perms).map_err(|e| {
            let msg = format!("Failed to make update file executable: {e}");
            logger::error(&app, &state, LOG_SOURCE, &msg);
            msg
        })?;
        logger::debug(&app, &state, LOG_SOURCE, "Marked downloaded update file executable");
    }

    Ok(dest_path.to_string_lossy().to_string())
}

/// Opens the system file manager at the folder containing the currently
/// running exe/AppImage. Used by the "you can grab the exe/AppImage from
/// here and scan it yourself" trust note in the update window, so people
/// who don't trust the launcher can find the actual file to run through
/// VirusTotal themselves.
#[tauri::command]
pub fn open_current_exe_folder(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let current_exe = std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok())
        .ok_or_else(|| "Failed to locate the running executable.".to_string())?;

    let dir = current_exe
        .parent()
        .ok_or_else(|| "Failed to locate the executable's folder.".to_string())?;

    logger::debug(
        &app,
        &state,
        LOG_SOURCE,
        &format!("Opening file manager at {}", dir.display()),
    );
    crate::commands::open_folder_in_file_manager(dir)
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
pub fn install_update(
    app: AppHandle,
    state: State<'_, AppState>,
    downloaded_path: String,
    relaunch: bool,
) -> Result<(), String> {
    let downloaded_path = PathBuf::from(downloaded_path);
    logger::info(
        &app,
        &state,
        LOG_SOURCE,
        &format!(
            "Installing update from {} (relaunch={relaunch})",
            downloaded_path.display()
        ),
    );
    if !downloaded_path.is_file() {
        let msg = "Downloaded update file is missing.".to_string();
        logger::error(&app, &state, LOG_SOURCE, &msg);
        return Err(msg);
    }

    #[cfg(target_os = "windows")]
    let result = install_update_windows(&app, &state, &downloaded_path, relaunch);
    #[cfg(not(target_os = "windows"))]
    let result = install_update_linux(&app, &state, &downloaded_path, relaunch);

    if let Err(ref e) = result {
        logger::error(&app, &state, LOG_SOURCE, &format!("Install failed: {e}"));
    }
    result
}

/// Windows can't overwrite a running .exe, so a tiny helper batch script is
/// spawned (detached from us) that waits a moment for this process to fully
/// exit, moves the downloaded file over the current exe, optionally
/// relaunches it, then deletes itself. We exit right after spawning it
/// either way, since the move can't happen until we're gone.
#[cfg(target_os = "windows")]
fn install_update_windows(
    app: &AppHandle,
    state: &AppState,
    downloaded_path: &std::path::Path,
    relaunch: bool,
) -> Result<(), String> {
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

    logger::info(
        app,
        state,
        LOG_SOURCE,
        &format!(
            "Handoff script spawned (script={}); exiting so it can swap the exe{}",
            script_path.display(),
            if relaunch { " and relaunch" } else { "" },
        ),
    );
    std::process::exit(0);
}

/// Linux (including AppImage) allows replacing a file that's currently
/// executing — the running process keeps its old inode open until it
/// exits, and the path just points at the new file from then on. So we can
/// swap the file directly with no helper script, and don't have to exit
/// unless the caller actually asked to relaunch.
#[cfg(not(target_os = "windows"))]
fn install_update_linux(
    app: &AppHandle,
    state: &AppState,
    downloaded_path: &std::path::Path,
    relaunch: bool,
) -> Result<(), String> {
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
        logger::debug(
            app,
            state,
            LOG_SOURCE,
            "Rename failed (likely cross-device); falling back to copy+remove",
        );
        std::fs::copy(downloaded_path, &current_exe)
            .map_err(|e| format!("Failed to replace current executable: {e}"))?;
        let _ = std::fs::remove_file(downloaded_path);
    }
    logger::info(
        app,
        state,
        LOG_SOURCE,
        &format!("Executable replaced in place: {}", current_exe.display()),
    );

    use std::os::unix::fs::PermissionsExt;
    if let Ok(metadata) = std::fs::metadata(&current_exe) {
        let mut perms = metadata.permissions();
        perms.set_mode(0o755);
        let _ = std::fs::set_permissions(&current_exe, perms);
    }

    if !relaunch {
        // File is swapped; the currently-running process just keeps going
        // on the old code until the user quits and starts it again.
        logger::info(
            app,
            state,
            LOG_SOURCE,
            "Update installed; continuing on old code in memory until next launch (relaunch not requested)",
        );
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

    logger::info(app, state, LOG_SOURCE, "Relaunched with updated executable; exiting old process");
    std::process::exit(0);
}
