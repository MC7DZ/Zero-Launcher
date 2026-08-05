pub mod accounts;
pub mod crash_analysis;
pub mod discover;
pub mod java;
pub mod logs;
pub mod minecraft;
pub mod mods;
pub mod music;
pub mod presets;
pub mod settings;

use tauri::State;
use crate::state::AppState;

/// Cancels a "generic" download by id — mod downloads/updates, dependency
/// installs, discover-tab downloads, Java runtime downloads, etc. Each of
/// these is given its own id by the frontend when it starts, so several can
/// be running (and cancelled independently) at once, each as its own card
/// in the downloads menu. Safe to call even if the id hasn't been
/// registered yet (e.g. a race between the click and the download actually
/// starting) — the flag is created in the cancelled state so the download
/// sees it immediately once it does start.
#[tauri::command]
pub fn cancel_generic_download(download_id: String, state: State<'_, AppState>) -> Result<(), String> {
    state
        .generic_cancel_flag(&download_id)
        .store(true, std::sync::atomic::Ordering::Relaxed);
    Ok(())
}
