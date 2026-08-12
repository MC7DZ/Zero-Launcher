use tauri::{AppHandle, Manager, State};
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

/// Get log entries, optionally filtered by level and source.
#[tauri::command]
pub async fn get_logs(
    state: State<'_, AppState>,
    level: Option<String>,
    source: Option<String>,
    count: Option<usize>,
) -> Result<Vec<LogEntry>, String> {
    let logs = state.logs.lock().unwrap();
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
    state.logs.lock().unwrap().clear();
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
    std::fs::create_dir_all(&dir).map_err(|e| format!("Failed to create logs folder: {e}"))?;
    open::that(&dir).map_err(|e| format!("Failed to open logs folder: {e}"))
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
    let logs = state.logs.lock().unwrap();
    let content: String = logs
        .iter()
        .map(|l| format!("[{}] [{}] [{}] {}", l.timestamp, l.level, l.source, l.message))
        .collect::<Vec<_>>()
        .join("\n");

    std::fs::write(&path, content)
        .map_err(|e| format!("Failed to export logs: {e}"))?;

    Ok(())
}
