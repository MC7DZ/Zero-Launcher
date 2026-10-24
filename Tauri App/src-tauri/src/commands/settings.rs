use tauri::State;
use crate::logger;
use crate::models::LauncherSettings;
use crate::state::AppState;

/// Get current launcher settings.
#[tauri::command]
pub async fn get_settings(
    state: State<'_, AppState>,
) -> Result<LauncherSettings, String> {
    let settings = state.settings.lock().unwrap_or_else(|e| e.into_inner()).clone();
    Ok(settings)
}

/// Save launcher settings.
#[tauri::command]
pub async fn save_settings(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    mut settings: LauncherSettings,
) -> Result<(), String> {
    // Guard against a corrupted/stale `settings` object making it back here
    // with a non-absolute `game_directory` (e.g. the frontend's in-memory
    // settings never loaded successfully and got left at `""`, then got
    // sent back on some unrelated save). Blank is fine on disk — it just
    // means "use the platform default" — but anything non-empty MUST be
    // absolute, otherwise every `<game_directory>/versions/...` path built
    // from it downstream resolves relative to the process's current
    // working directory (the exe's own folder when launched by double
    // click) instead of the real Minecraft folder.
    // Clean up whatever the frontend handed back (typed text, or a native
    // folder picker result) before validating/storing it — Linux portal
    // file choosers in particular can return a `file://` URI instead of a
    // plain path, and if that ever slips through unsanitized it gets
    // permanently baked into every future modpack/instance directory this
    // setting is used to build. See `sanitize_user_path`.
    settings.game_directory = crate::models::sanitize_user_path(&settings.game_directory);

    if !settings.game_directory.trim().is_empty()
        && !std::path::Path::new(settings.game_directory.trim()).is_absolute()
    {
        let current = state.settings.lock().unwrap_or_else(|e| e.into_inner());
        settings.game_directory = current.game_directory.clone();
    }

    // Debug: which settings actually changed (names always, values only for
    // booleans/numbers so nothing path- or text-like leaks into the log).
    {
        let old_val = serde_json::to_value(&*state.settings.lock().unwrap_or_else(|e| e.into_inner())).ok();
        let new_val = serde_json::to_value(&settings).ok();
        if let (Some(serde_json::Value::Object(o)), Some(serde_json::Value::Object(n))) = (old_val, new_val) {
            let mut changes: Vec<String> = Vec::new();
            for (k, nv) in &n {
                if o.get(k) != Some(nv) {
                    let shown = match nv {
                        serde_json::Value::Bool(_) | serde_json::Value::Number(_) => format!(
                            "{} -> {}",
                            o.get(k).map(|v| v.to_string()).unwrap_or_else(|| "unset".to_string()),
                            nv
                        ),
                        _ => "changed".to_string(),
                    };
                    changes.push(format!("{k}: {shown}"));
                }
            }
            let msg = if changes.is_empty() {
                "save_settings: nothing changed".to_string()
            } else {
                format!("save_settings: {} setting(s) changed — {}", changes.len(), changes.join("; "))
            };
            logger::debug(&app, &state, "SETTINGS", &msg);
        }
    }

    let dir_changed = {
        let current = state.settings.lock().unwrap_or_else(|e| e.into_inner());
        current.game_directory != settings.game_directory
    };
    {
        let mut current = state.settings.lock().unwrap_or_else(|e| e.into_inner());
        *current = settings;
    }
    state.save_settings_to_disk();
    if dir_changed {
        // Load whichever instances.json lives in the newly selected
        // game directory's versions/ folder instead of keeping the old one.
        state.reload_instances_for_current_dir();
    }
    Ok(())
}

/// Update Discord Rich Presence status.
#[tauri::command]
pub async fn update_discord_presence(
    state: State<'_, AppState>,
    rpc_state: State<'_, crate::discord_rpc::DiscordRpcState>,
    tab: String,
    playing_instance: Option<String>,
    mc_version: Option<String>,
) -> Result<(), String> {
    let settings = state.settings.lock().unwrap_or_else(|e| e.into_inner()).clone();
    if let Ok(mut rpc) = rpc_state.0.lock() {
        rpc.update_presence(&settings, &tab, playing_instance.as_deref(), mc_version.as_deref());
    }
    Ok(())
}

/// Get the platform default .minecraft directory path.
#[tauri::command]
pub async fn get_default_minecraft_dir() -> Result<String, String> {
    let dir = crate::models::default_game_directory();
    Ok(dir.to_string_lossy().to_string())
}

/// Called by the frontend whenever the user does something in the
/// launcher window (mouse movement, clicks, keystrokes — throttled on the
/// JS side so this doesn't fire on every single event). Powers "Close
/// launcher when game starts" → "Make it smart": the launch flow waits
/// for a stretch of time with no calls to this before it actually closes
/// the window, so it won't yank the launcher away while the user is still
/// in the middle of doing something in it.
#[tauri::command]
pub async fn report_activity(state: State<'_, AppState>) -> Result<(), String> {
    *state.last_activity_at.lock().unwrap_or_else(|e| e.into_inner()) = std::time::Instant::now();
    Ok(())
}


/// Reads a wallpaper image from disk and returns it as a `data:` URL.
/// Used as a fallback when the webview can't load the file through Tauri's
/// asset protocol (scope/path quirks on some platforms).
#[tauri::command]
pub async fn read_background_image(path: String) -> Result<String, String> {
    use base64::Engine;
    const MAX_BYTES: u64 = 40 * 1024 * 1024;

    let p = std::path::Path::new(&path);
    let meta = std::fs::metadata(p).map_err(|e| format!("Cannot open image: {e}"))?;
    if !meta.is_file() {
        return Err("Not a file".to_string());
    }
    if meta.len() > MAX_BYTES {
        return Err("Image is larger than 40 MB".to_string());
    }
    let bytes = std::fs::read(p).map_err(|e| format!("Cannot read image: {e}"))?;
    let ext = p
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_ascii_lowercase())
        .unwrap_or_default();
    let mime = match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "avif" => "image/avif",
        _ => "application/octet-stream",
    };
    let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
    Ok(format!("data:{mime};base64,{encoded}"))
}
