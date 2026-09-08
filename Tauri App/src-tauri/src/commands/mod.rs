pub mod accounts;
pub mod crash_analysis;
pub mod discover;
pub mod java;
pub mod logs;
pub mod minecraft;
pub mod modpack;
pub mod mods;
pub mod msa;
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

/// Reliably opens a directory in the operating system's native file manager.
///
/// On Linux:
/// - Strips / restores AppImage library environment variables (`LD_LIBRARY_PATH`, `LD_PRELOAD`)
///   so system file managers (Dolphin, Nautilus, Thunar, GIO) don't crash due to AppImage library conflicts.
/// - Tries multiple fallback desktop launchers: `xdg-open`, `gio open`, `dolphin`, `nautilus`,
///   `thunar`, `pcmanfm`, `pcmanfm-qt`, `nemo`, and falls back to `open::that_detached` / `open::that`.
/// On Windows: invokes `explorer`.
/// On macOS: invokes `open`.
pub fn open_folder_in_file_manager(path: &std::path::Path) -> Result<(), String> {
    let _ = std::fs::create_dir_all(path);
    let canonical = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());

    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(&canonical)
            .spawn()
            .map_err(|e| format!("Failed to open folder in Windows Explorer: {e}"))?;
        return Ok(());
    }

    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(&canonical)
            .spawn()
            .map_err(|e| format!("Failed to open folder on macOS: {e}"))?;
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    {
        let spawn_cmd = |prog: &str, args: &[&std::ffi::OsStr]| -> bool {
            let mut cmd = std::process::Command::new(prog);
            cmd.args(args);
            if std::env::var_os("APPIMAGE").is_some() || std::env::var_os("APPDIR").is_some() {
                if let Some(orig) = std::env::var_os("LD_LIBRARY_PATH_ORIG") {
                    cmd.env("LD_LIBRARY_PATH", orig);
                } else {
                    cmd.env_remove("LD_LIBRARY_PATH");
                }
                cmd.env_remove("LD_PRELOAD");
            }
            cmd.stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null());
            cmd.spawn().is_ok()
        };

        let path_os = canonical.as_os_str();

        // 1. xdg-open
        if spawn_cmd("xdg-open", &[path_os]) {
            return Ok(());
        }

        // 2. gio open
        let open_arg = std::ffi::OsStr::new("open");
        if spawn_cmd("gio", &[open_arg, path_os]) {
            return Ok(());
        }

        // 3. Desktop file managers directly
        for fm in &["dolphin", "nautilus", "thunar", "pcmanfm", "pcmanfm-qt", "nemo"] {
            if spawn_cmd(fm, &[path_os]) {
                return Ok(());
            }
        }

        // 4. Crate fallback
        open::that_detached(&canonical)
            .or_else(|_| open::that(&canonical))
            .map_err(|e| format!("Failed to open folder: {e}"))?;
        Ok(())
    }

    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        open::that(&canonical).map_err(|e| format!("Failed to open folder: {e}"))
    }
}

/// Open the root "Zero Launcher" data folder (instances, accounts,
/// settings, Java runtimes, etc.) in the system file manager.
#[tauri::command]
pub fn open_launcher_folder(state: State<'_, AppState>) -> Result<(), String> {
    open_folder_in_file_manager(&state.data_dir)
}

/// Open an instance's game directory in the system file manager.
#[tauri::command]
pub fn open_instance_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<(), String> {
    let dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());
    open_folder_in_file_manager(&dir)
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

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
pub struct SystemInfo {
    pub os_name: String,
    pub arch: String,
    pub launcher_version: String,
}

#[tauri::command]
pub fn get_system_info() -> SystemInfo {
    let mut os_name = "Linux".to_string();

    #[cfg(target_os = "linux")]
    {
        if let Ok(content) = std::fs::read_to_string("/etc/os-release") {
            for line in content.lines() {
                if let Some(val) = line.strip_prefix("PRETTY_NAME=") {
                    let cleaned = val.trim_matches('"').trim_matches('\'').trim();
                    if !cleaned.is_empty() {
                        os_name = cleaned.to_string();
                        break;
                    }
                } else if let Some(val) = line.strip_prefix("NAME=") {
                    let cleaned = val.trim_matches('"').trim_matches('\'').trim();
                    if !cleaned.is_empty() && (os_name == "Linux" || os_name == "linux") {
                        os_name = cleaned.to_string();
                    }
                }
            }
        }
        if os_name.eq_ignore_ascii_case("linux") {
            os_name = "Linux".to_string();
        }
    }

    #[cfg(target_os = "windows")]
    {
        os_name = "Windows".to_string();
    }

    #[cfg(target_os = "macos")]
    {
        os_name = "macOS".to_string();
    }

    let arch_raw = std::env::consts::ARCH;
    let arch_display = match arch_raw {
        "x86_64" => "x64 (64-bit)",
        "aarch64" => "ARM64 (64-bit)",
        "x86" => "x86 (32-bit)",
        "arm" => "ARM (32-bit)",
        other => other,
    };

    let target_os = match std::env::consts::OS {
        "linux" => "Linux",
        "windows" => "Windows",
        "macos" => "macOS",
        other => other,
    };

    SystemInfo {
        os_name,
        arch: format!("{} ({})", arch_display, target_os),
        launcher_version: env!("CARGO_PKG_VERSION").to_string(),
    }
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

#[tauri::command]
pub fn window_minimize(window: tauri::Window) -> Result<(), String> {
    window.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_toggle_maximize(window: tauri::Window) -> Result<(), String> {
    if window.is_maximized().unwrap_or(false) {
        window.unmaximize().map_err(|e| e.to_string())
    } else {
        window.maximize().map_err(|e| e.to_string())
    }
}

#[tauri::command]
pub fn window_close(window: tauri::Window) -> Result<(), String> {
    window.close().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_is_maximized(window: tauri::Window) -> Result<bool, String> {
    window.is_maximized().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_center(window: tauri::Window) -> Result<(), String> {
    window.center().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_toggle_fullscreen(window: tauri::Window) -> Result<bool, String> {
    let is_fs = window.is_fullscreen().unwrap_or(false);
    window.set_fullscreen(!is_fs).map_err(|e| e.to_string())?;
    Ok(!is_fs)
}

#[tauri::command]
pub fn window_toggle_always_on_top(window: tauri::Window) -> Result<bool, String> {
    // There is no is_always_on_top in some platforms, but we can track or toggle
    static ALWAYS_ON_TOP: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    let next_state = !ALWAYS_ON_TOP.load(std::sync::atomic::Ordering::Relaxed);
    window.set_always_on_top(next_state).map_err(|e| e.to_string())?;
    ALWAYS_ON_TOP.store(next_state, std::sync::atomic::Ordering::Relaxed);
    Ok(next_state)
}

/// Explicitly releases unused memory pages back to the operating system
#[tauri::command]
pub fn trim_memory() {
    #[cfg(target_os = "linux")]
    unsafe {
        extern "C" {
            fn malloc_trim(pad: usize) -> i32;
        }
        malloc_trim(0);
    }
}
