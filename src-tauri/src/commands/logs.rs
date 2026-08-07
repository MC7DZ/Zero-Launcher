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
