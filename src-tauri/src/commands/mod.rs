pub mod accounts;
pub mod crash_analysis;
pub mod discover;
pub mod java;
pub mod logs;
pub mod minecraft;
pub mod modpack;
pub mod mods;
pub mod msa;
pub mod music;
pub mod presets;
pub mod settings;
pub mod skins;
pub mod updater;

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

/// Open the root "Zero Launcher" data folder (instances, accounts,
/// settings, Java runtimes, etc.) in the system file manager.
#[tauri::command]
pub fn open_launcher_folder(state: State<'_, AppState>) -> Result<(), String> {
    std::fs::create_dir_all(&state.data_dir)
        .map_err(|e| format!("Failed to create Zero Launcher folder: {e}"))?;
    open::that(&state.data_dir).map_err(|e| format!("Failed to open folder: {e}"))?;
    Ok(())
}

/// Current launcher version, read from the version in this crate's
/// `Cargo.toml` (`package.version`) at build time. Shown in
/// Settings → About & Initial Setup.
/// Open WebKit / webview developer tools inspector.
#[tauri::command]
pub fn open_devtools(app: tauri::AppHandle) -> Result<(), String> {
    use tauri::Manager;
    if let Some(window) = app.get_webview_window("main") {
        window.open_devtools();
    }
    Ok(())
}

#[tauri::command]
pub fn get_launcher_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Reads the last-persisted "Global Stats" panel snapshot from
/// `<data_dir>/stats.json`, if any — used to populate the panel
/// immediately on startup rather than showing blank/zero values while
/// Mods Installed / Game Advancements are rescanned from disk.
#[tauri::command]
pub fn load_global_stats(state: State<'_, AppState>) -> Result<Option<crate::models::GlobalStats>, String> {
    Ok(state.load_global_stats())
}

/// Persists the "Global Stats" panel to `<data_dir>/stats.json`, called by
/// the frontend every time it finishes recomputing the panel's values.
#[tauri::command]
pub fn save_global_stats(stats: crate::models::GlobalStats, state: State<'_, AppState>) -> Result<(), String> {
    state.save_global_stats(&stats);
    Ok(())
}

#[cfg(target_os = "linux")]
fn play_native_click_sound() {
    static WRITTEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    let sound_path = std::env::temp_dir().join("zerolauncher-click.ogg");
    if !WRITTEN.load(std::sync::atomic::Ordering::Relaxed) || !sound_path.exists() {
        let _ = std::fs::write(&sound_path, include_bytes!("../../sounds/click.ogg"));
        WRITTEN.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    if std::process::Command::new("paplay")
        .arg(&sound_path)
        .spawn()
        .is_ok()
    {
        return;
    }
    if std::process::Command::new("pw-cat")
        .args(["-p", &sound_path.to_string_lossy()])
        .spawn()
        .is_ok()
    {
        return;
    }
    let _ = std::process::Command::new("aplay")
        .arg(&sound_path)
        .spawn();
}

#[cfg(target_os = "windows")]
fn play_native_click_sound() {
    static WAV_BYTES: &[u8] = include_bytes!("../../../src/assets/sounds/click.wav");
    extern "system" {
        fn PlaySoundA(pszSound: *const u8, hmod: *mut std::ffi::c_void, fdwSound: u32) -> i32;
    }
    // SND_ASYNC (0x1) | SND_MEMORY (0x4) | SND_NODEFAULT (0x2)
    unsafe {
        PlaySoundA(WAV_BYTES.as_ptr(), std::ptr::null_mut(), 0x0001 | 0x0004 | 0x0002);
    }
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
fn play_native_click_sound() {
    let _ = std::process::Command::new("afplay")
        .arg("/tmp/zerolauncher-click.ogg")
        .spawn();
}

#[tauri::command]
pub fn play_click_sound() {
    play_native_click_sound();
}
