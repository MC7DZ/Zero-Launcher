use tauri::State;
use crate::models::LauncherSettings;
use crate::state::AppState;

/// Get current launcher settings.
#[tauri::command]
pub async fn get_settings(
    state: State<'_, AppState>,
) -> Result<LauncherSettings, String> {
    let settings = state.settings.lock().unwrap().clone();
    Ok(settings)
}

/// Save launcher settings.
#[tauri::command]
pub async fn save_settings(
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
    if !settings.game_directory.trim().is_empty()
        && !std::path::Path::new(settings.game_directory.trim()).is_absolute()
    {
        let current = state.settings.lock().unwrap();
        settings.game_directory = current.game_directory.clone();
    }

    let dir_changed = {
        let current = state.settings.lock().unwrap();
        current.game_directory != settings.game_directory
    };
    {
        let mut current = state.settings.lock().unwrap();
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
    let settings = state.settings.lock().unwrap().clone();
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
    *state.last_activity_at.lock().unwrap() = std::time::Instant::now();
    Ok(())
}
