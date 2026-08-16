use tauri::State;
use crate::state::AppState;

/// Sanitize a username into a filesystem-safe file stem. Minecraft
/// usernames are already alphanumeric/underscore, but this strips
/// anything else defensively before it touches disk.
fn safe_name(username: &str) -> String {
    username
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
        .collect::<String>()
}

/// Downloads the given render URL (an mc-heads.net full-body render for
/// the account's current skin) and caches it to
/// `<Zero Launcher data dir>/skins/<username>.png`, overwriting any
/// previously cached skin for that username. This is what lets the
/// Dressing Room show a real, offline-available thumbnail for "skins
/// that have been used" instead of re-hitting the network (or showing a
/// stale skin) every time it's opened.
#[tauri::command]
pub async fn cache_account_skin(
    state: State<'_, AppState>,
    username: String,
    render_url: String,
) -> Result<String, String> {
    let name = safe_name(&username);
    if name.is_empty() {
        return Err("Invalid username".to_string());
    }

    let skins_dir = state.data_dir.join("skins");
    std::fs::create_dir_all(&skins_dir)
        .map_err(|e| format!("Failed to create skins folder: {e}"))?;

    let resp = reqwest::get(&render_url)
        .await
        .map_err(|e| format!("Failed to download skin render: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Skin render request failed: {}", resp.status()));
    }
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read skin render: {e}"))?;

    let path = skins_dir.join(format!("{name}.png"));
    std::fs::write(&path, &bytes).map_err(|e| format!("Failed to write cached skin: {e}"))?;

    Ok(path.to_string_lossy().to_string())
}

/// Lists every skin currently cached on disk, newest-modified first, so
/// the Dressing Room can populate its Skins grid entirely from the local
/// cache (no network round-trip) once accounts have been used at least
/// once each.
#[tauri::command]
pub fn list_cached_skins(state: State<'_, AppState>) -> Result<Vec<CachedSkin>, String> {
    let skins_dir = state.data_dir.join("skins");
    if !skins_dir.exists() {
        return Ok(Vec::new());
    }

    let mut entries: Vec<(String, String, std::time::SystemTime)> = Vec::new();
    for entry in std::fs::read_dir(&skins_dir).map_err(|e| format!("Failed to read skins folder: {e}"))? {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("png") {
            continue;
        }
        let Some(username) = path.file_stem().and_then(|s| s.to_str()) else { continue };
        let modified = entry
            .metadata()
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        entries.push((username.to_string(), path.to_string_lossy().to_string(), modified));
    }

    entries.sort_by(|a, b| b.2.cmp(&a.2));

    Ok(entries
        .into_iter()
        .map(|(username, path, _)| CachedSkin { username, path })
        .collect())
}

#[derive(serde::Serialize)]
pub struct CachedSkin {
    pub username: String,
    pub path: String,
}
