use tauri::{AppHandle, Emitter, Manager, State};
use crate::models::LogEntry;
use crate::state::AppState;

/// Resolve the absolute filesystem path to a file inside the app's
/// `backgrounds` folder (bundled as a resource in release builds, read
/// straight from the source tree in dev). Returns an error if it isn't found.
#[tauri::command]
pub async fn resolve_background_path(
    app: AppHandle,
    name: String,
) -> Result<String, String> {
    // Release builds: backgrounds/ is bundled as a resource next to the app.
    if let Ok(resource_path) = app
        .path()
        .resolve(format!("backgrounds/{name}"), tauri::path::BaseDirectory::Resource)
    {
        if resource_path.exists() {
            return Ok(resource_path.to_string_lossy().to_string());
        }
    }

    // Dev builds: fall back to the source tree (src-tauri/backgrounds).
    let dev_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("backgrounds")
        .join(&name);
    if dev_path.exists() {
        return Ok(dev_path.to_string_lossy().to_string());
    }

    Err(format!("Background file not found: {name}"))
}

pub fn resolve_creator_bg_target(key: &str) -> (String, String) {
    let lower = key.to_ascii_lowercase();
    match lower.as_str() {
        "oneclient" => (
            "oneclient.jpg".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/oneclient.jpg".to_string(),
        ),
        "modpack" | "modpacks" => (
            "modpacks.jpg".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/modpacks.jpg".to_string(),
        ),
        k if k.ends_with(".png") || k.ends_with(".jpg") || k.ends_with(".jpeg") => {
            (
                k.to_string(),
                format!("https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/{k}"),
            )
        }
        "tiny-takeover" | "minecraft-tiny-takeover" => (
            "minecarft-tiny-takeover.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecarft-tiny-takeover.png".to_string(),
        ),
        "chaos-cubed" | "minecraft-chaos-cubed" => (
            "minecraft-chaos-cubed.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-chaos-cubed.png".to_string(),
        ),
        "chase-the-skies" | "minecraft-chase-the-skies" => (
            "minecraft-chase-the-skies.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-chase-the-skies.png".to_string(),
        ),
        "mounts-of-mayhem" | "minecraft-mounts-of-mayhem" => (
            "minecraft-mounts-of-mayhem.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-mounts-of-mayhem.png".to_string(),
        ),
        "the-copper-age" | "minecraft-the-copper-age" => (
            "minecraft-the-copper-age.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-the-copper-age.png".to_string(),
        ),
        "trails-and-tales" | "minecraft-trails-and-tales" => (
            "minecraft-trails-and-tales.png".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-trails-and-tales.png".to_string(),
        ),
        _ => (
            "minecraft-wilderness-bound.jpg".to_string(),
            "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-wilderness-bound.jpg".to_string(),
        ),
    }
}

async fn download_or_update_bg(
    app: &AppHandle,
    state: &AppState,
    key: &str,
    file_name: &str,
    url: &str,
) -> Result<String, String> {
    let bg_dir = state.data_dir.join("assets").join("background");
    let bg_file = bg_dir.join(file_name);
    let etag_file = bg_dir.join(format!("{file_name}.etag"));

    let file_exists = bg_file.exists() && std::fs::metadata(&bg_file).map(|m| m.len()).unwrap_or(0) > 0;
    let cached_etag = if file_exists {
        std::fs::read_to_string(&etag_file).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
    } else {
        None
    };

    std::fs::create_dir_all(&bg_dir)
        .map_err(|e| format!("Failed to create background directory: {e}"))?;

    let req_url = url.to_string();
    let if_none_match = cached_etag.clone();

    let resp = crate::network::send(app, state, &format!("creator background ({file_name})"), move |c| {
        let mut r = c.get(&req_url);
        if let Some(ref tag) = if_none_match {
            r = r.header("If-None-Match", tag.as_str());
        }
        r
    })
    .await?;

    if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
        // Image matches GitHub! Nothing to update.
        return Ok(bg_file.to_string_lossy().to_string());
    }

    if !resp.status().is_success() {
        if file_exists {
            // Keep using existing cached file if network fails or returns error
            return Ok(bg_file.to_string_lossy().to_string());
        }
        return Err(format!("Failed to download creator background: HTTP {}", resp.status()));
    }

    let new_etag = resp
        .headers()
        .get(reqwest::header::ETAG)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read background image stream: {e}"))?;

    let tmp_file = bg_file.with_extension("part");
    std::fs::write(&tmp_file, &bytes)
        .map_err(|e| format!("Failed to write temporary background image: {e}"))?;
    std::fs::rename(&tmp_file, &bg_file)
        .map_err(|e| format!("Failed to finalize background image: {e}"))?;

    if let Some(tag) = new_etag {
        let _ = std::fs::write(&etag_file, tag.as_bytes());
    }

    let _ = app.emit("creator-background-updated", serde_json::json!({
        "key": key,
        "fileName": file_name,
        "path": bg_file.to_string_lossy().to_string()
    }));

    Ok(bg_file.to_string_lossy().to_string())
}

/// Downloads, caches, and automatically updates creator backdrop images from GitHub.
#[tauri::command]
pub async fn ensure_creator_background(
    app: AppHandle,
    state: State<'_, AppState>,
    name: Option<String>,
) -> Result<String, String> {
    let key = name.unwrap_or_else(|| "minecraft".to_string());
    let (file_name, url_string) = resolve_creator_bg_target(&key);

    let bg_dir = state.data_dir.join("assets").join("background");
    let bg_file = bg_dir.join(&file_name);

    if bg_file.exists() && std::fs::metadata(&bg_file).map(|m| m.len()).unwrap_or(0) > 0 {
        // Return local file immediately so the UI is instantaneous
        let local_path = bg_file.to_string_lossy().to_string();

        // In the background, check if the picture on GitHub has changed / updated
        let app_clone = app.clone();
        let key_clone = key.clone();
        let file_name_clone = file_name.clone();
        let url_clone = url_string.clone();
        tauri::async_runtime::spawn(async move {
            let app_state = app_clone.state::<AppState>();
            let _ = download_or_update_bg(&app_clone, &app_state, &key_clone, &file_name_clone, &url_clone).await;
        });

        return Ok(local_path);
    }

    // First time download
    download_or_update_bg(&app, &state, &key, &file_name, &url_string).await
}

/// Get log entries, optionally filtered by level and source.
#[tauri::command]
pub async fn get_logs(
    state: State<'_, AppState>,
    level: Option<String>,
    source: Option<String>,
    count: Option<usize>,
) -> Result<Vec<LogEntry>, String> {
    let logs = state.logs.lock().unwrap_or_else(|e| e.into_inner());
    let count = count.unwrap_or(500);

    let filtered: Vec<LogEntry> = logs
        .iter()
        .filter(|log| {
            if let Some(ref lvl) = level {
                if !lvl.is_empty() && log.level != *lvl {
                    return false;
                }
            }
            if let Some(ref src) = source {
                if !src.is_empty() && log.source != *src {
                    return false;
                }
            }
            true
        })
        .rev()
        .take(count)
        .cloned()
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();

    Ok(filtered)
}

/// Clear all log entries.
#[tauri::command]
pub async fn clear_logs(
    state: State<'_, AppState>,
) -> Result<(), String> {
    state.logs.lock().unwrap_or_else(|e| e.into_inner()).clear();
    Ok(())
}

/// Absolute path to the `logs` folder (`<data_dir>/logs`), containing
/// `latest.log` for the current run and gzip-compressed archives of past
/// runs (`<date>-<n>.log.gz`). Used by the frontend to show the path and
/// to build the "Open Logs Folder" button.
#[tauri::command]
pub async fn get_logs_folder_path(state: State<'_, AppState>) -> Result<String, String> {
    Ok(crate::logger::logs_dir(&state.data_dir).to_string_lossy().to_string())
}

/// Opens `logs` folder in the OS file explorer.
#[tauri::command]
pub async fn open_logs_folder(state: State<'_, AppState>) -> Result<(), String> {
    let dir = crate::logger::logs_dir(&state.data_dir);
    crate::commands::open_folder_in_file_manager(&dir)
}

/// Full contents of `logs/latest.log` for the current run — everything the
/// launcher has logged so far (installs, launches, Java, mods, downloads,
/// crashes, etc.), independent of the in-memory ring buffer's cap.
#[tauri::command]
pub async fn get_latest_log_contents(state: State<'_, AppState>) -> Result<String, String> {
    Ok(crate::logger::read_latest_log(&state.data_dir))
}

/// Export logs to a text file at the given path.
#[tauri::command]
pub async fn export_logs(
    state: State<'_, AppState>,
    path: String,
) -> Result<(), String> {
    let logs = state.logs.lock().unwrap_or_else(|e| e.into_inner());
    let content: String = logs
        .iter()
        .map(|l| format!("[{}] [{}] [{}] {}", l.timestamp, l.level, l.source, l.message))
        .collect::<Vec<_>>()
        .join("\n");

    std::fs::write(&path, content)
        .map_err(|e| format!("Failed to export logs: {e}"))?;

    Ok(())
}
