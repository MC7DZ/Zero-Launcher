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
    settings: LauncherSettings,
) -> Result<(), String> {
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
