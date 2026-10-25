use std::fs;
use std::path::{Path, PathBuf};
use tauri::{Manager, State};
use crate::logger;
use crate::state::AppState;

const SRC: &str = "SHORTCUT";

pub const ICON_VANILLA: &[u8] = include_bytes!("../../icons/loaders/vanilla.png");
pub const ICON_FABRIC: &[u8] = include_bytes!("../../icons/loaders/fabric.png");
pub const ICON_FORGE: &[u8] = include_bytes!("../../icons/loaders/forge.png");
pub const ICON_NEOFORGE: &[u8] = include_bytes!("../../icons/loaders/neoforge.png");
pub const ICON_QUILT: &[u8] = include_bytes!("../../icons/loaders/quilt.png");
pub const ICON_FALLBACK: &[u8] = include_bytes!("../../icons/shortcut.png");

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct CliLaunchArgs {
    pub version_id: String,
    pub account_id: Option<String>,
    pub offline: bool,
    /// Filled in by the backend (see [`resolve_instance_found`]) before the
    /// args are handed to the frontend. `false` means the shortcut points
    /// at an instance that no longer exists, so the launcher must NOT try
    /// to start it and should offer to delete the shortcut instead.
    #[serde(default = "default_true")]
    pub instance_found: bool,
}

fn default_true() -> bool {
    true
}

pub fn parse_cli_launch_args(args: &[String]) -> Option<CliLaunchArgs> {
    let mut version_id = None;
    let mut account_id = None;
    let mut offline = false;

    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == "--launch-instance" {
            if let Some(val) = iter.next() {
                let trimmed = val.trim().trim_matches('"').trim_matches('\'').to_string();
                if !trimmed.is_empty() {
                    version_id = Some(trimmed);
                }
            }
        } else if arg.starts_with("--launch-instance=") {
            let val = &arg["--launch-instance=".len()..];
            let trimmed = val.trim().trim_matches('"').trim_matches('\'').to_string();
            if !trimmed.is_empty() {
                version_id = Some(trimmed);
            }
        } else if arg == "--account" {
            if let Some(val) = iter.next() {
                let trimmed = val.trim().trim_matches('"').trim_matches('\'').to_string();
                if !trimmed.is_empty() {
                    account_id = Some(trimmed);
                }
            }
        } else if arg.starts_with("--account=") {
            let val = &arg["--account=".len()..];
            let trimmed = val.trim().trim_matches('"').trim_matches('\'').to_string();
            if !trimmed.is_empty() {
                account_id = Some(trimmed);
            }
        } else if arg == "--offline" {
            offline = true;
        }
    }

    version_id.map(|vid| CliLaunchArgs {
        version_id: vid,
        account_id,
        offline,
        instance_found: true,
    })
}

/// Checks the launcher's loaded instance list for the shortcut's target and
/// records the result in `args.instance_found`. Logs what it decided.
pub fn resolve_instance_found(app: &tauri::AppHandle, mut args: CliLaunchArgs) -> CliLaunchArgs {
    if let Some(state) = app.try_state::<AppState>() {
        let found = {
            let instances = state.instances.lock().unwrap_or_else(|e| e.into_inner());
            instances.iter().any(|i| i.version_id == args.version_id)
        };
        args.instance_found = found;
        if found {
            logger::debug(app, &state, SRC, &format!(
                "Shortcut target '{}' found (account={:?}, offline={})",
                args.version_id, args.account_id, args.offline
            ));
        } else {
            logger::warn(app, &state, SRC, &format!(
                "Shortcut target '{}' NOT found in instance list — launch skipped, user will be asked whether to delete the shortcut",
                args.version_id
            ));
        }
    }
    args
}

#[tauri::command]
pub async fn get_cli_launch_args(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<CliLaunchArgs>, String> {
    let taken = state.cli_launch_args.lock().unwrap_or_else(|e| e.into_inner()).take();
    match taken {
        Some(args) => {
            logger::debug(&app, &state, SRC, &format!(
                "Frontend asked for CLI launch args — handing over version_id='{}'", args.version_id
            ));
            Ok(Some(resolve_instance_found(&app, args)))
        }
        None => {
            logger::debug(&app, &state, SRC, "Frontend asked for CLI launch args — none (normal start)");
            Ok(None)
        }
    }
}

#[tauri::command]
pub fn hide_main_window(app: tauri::AppHandle) {
    if let Some(st) = app.try_state::<AppState>() {
        logger::debug(&app, &st, SRC, "hide_main_window requested (shortcut launch, instance found)");
    }
    if let Some(w) = app.get_webview_window("main") {
        if let Err(e) = w.hide() {
            if let Some(st) = app.try_state::<AppState>() {
                logger::warn(&app, &st, SRC, &format!("hide_main_window failed: {e}"));
            }
        }
    }
}

/// Brings the main window to the front. Used when a shortcut points at a
/// missing instance and the launcher has to show its "couldn't find it"
/// prompt.
#[tauri::command]
pub fn show_main_window(app: tauri::AppHandle) {
    if let Some(st) = app.try_state::<AppState>() {
        logger::debug(&app, &st, SRC, "show_main_window requested");
    }
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn png_to_ico(png_bytes: &[u8]) -> Vec<u8> {
    let mut ico = Vec::with_capacity(22 + png_bytes.len());
    // ICONDIR (6 bytes)
    ico.extend_from_slice(&[0x00, 0x00]); // Reserved, must be 0
    ico.extend_from_slice(&[0x01, 0x00]); // Image type: 1 = ICO
    ico.extend_from_slice(&[0x01, 0x00]); // Number of images: 1

    // ICONDIRENTRY (16 bytes)
    ico.push(0); // Width: 0 means 256 or auto
    ico.push(0); // Height: 0 means 256 or auto
    ico.push(0); // Color count: 0 (>=8bpp)
    ico.push(0); // Reserved
    ico.extend_from_slice(&[0x01, 0x00]); // Color planes (1)
    ico.extend_from_slice(&[0x20, 0x00]); // Bits per pixel (32)
    ico.extend_from_slice(&(png_bytes.len() as u32).to_le_bytes()); // Size of image data
    ico.extend_from_slice(&22u32.to_le_bytes()); // Offset to image data (6 + 16 = 22)

    // Image data (PNG stream)
    ico.extend_from_slice(png_bytes);
    ico
}

fn get_launcher_exe() -> PathBuf {
    #[cfg(target_os = "linux")]
    {
        if let Some(appimage) = std::env::var_os("APPIMAGE") {
            return PathBuf::from(appimage);
        }
    }
    if let Ok(cur) = std::env::current_exe() {
        return cur;
    }
    crate::first_run_setup::install_dir().join(crate::first_run_setup::target_exe_name())
}

#[tauri::command]
pub async fn create_instance_shortcut(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
    shortcut_name: String,
    account_id: Option<String>,
    offline: bool,
    desktop: bool,
    app_menu: bool,
) -> Result<String, String> {
    logger::info(&app, &state, SRC, &format!(
        "Creating shortcut: instance='{instance_id}' name='{shortcut_name}' account={account_id:?} offline={offline} desktop={desktop} app_menu={app_menu}"
    ));
    if !desktop && !app_menu {
        logger::warn(&app, &state, SRC, "Create shortcut rejected: no location selected");
        return Err("Please select at least one shortcut location (Desktop or Search / App Menu).".to_string());
    }

    let instance = {
        let instances = state.instances.lock().unwrap_or_else(|e| e.into_inner());
        instances
            .iter()
            .find(|i| i.version_id == instance_id)
            .cloned()
            .ok_or_else(|| {
                logger::error(&app, &state, SRC, &format!("Create shortcut failed: instance '{instance_id}' not found"));
                format!("Instance '{}' not found", instance_id)
            })?
    };

    let safe_instance_id: String = instance_id
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();

    let safe_title: String = shortcut_name
        .chars()
        .map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' || c == '_' { c } else { '_' })
        .collect();
    let safe_title = if safe_title.trim().is_empty() {
        if !instance.name.trim().is_empty() {
            instance.name.trim().to_string()
        } else {
            instance_id.clone()
        }
    } else {
        safe_title.trim().to_string()
    };

    let target_exe = get_launcher_exe();
    logger::debug(&app, &state, SRC, &format!("Shortcut target exe: {}", target_exe.display()));
    let mut created_paths: Vec<PathBuf> = Vec::new();

    // 1. Resolve instance icon
    let mut custom_icon_bytes: Option<Vec<u8>> = None;
    if !instance.directory.trim().is_empty() {
        let inst_icon_path = Path::new(&instance.directory).join("icon.png");
        if inst_icon_path.exists() {
            custom_icon_bytes = fs::read(&inst_icon_path).ok();
        }
    }

    let png_bytes: &[u8] = if let Some(ref bytes) = custom_icon_bytes {
        bytes.as_slice()
    } else {
        match instance.loader.to_lowercase().as_str() {
            "fabric" => ICON_FABRIC,
            "forge" => ICON_FORGE,
            "neoforge" => ICON_NEOFORGE,
            "quilt" => ICON_QUILT,
            "vanilla" => ICON_VANILLA,
            _ => ICON_FALLBACK,
        }
    };

    let icons_dir = crate::first_run_setup::install_dir().join("icons").join("instances");
    let _ = fs::create_dir_all(&icons_dir);
    let png_icon_path = icons_dir.join(format!("{}.png", safe_instance_id));
    if let Err(e) = fs::write(&png_icon_path, png_bytes) {
        logger::warn(&app, &state, SRC, &format!("Could not write shortcut icon png {}: {e}", png_icon_path.display()));
    }

    let ico_icon_path = icons_dir.join(format!("{}.ico", safe_instance_id));
    let ico_bytes = png_to_ico(png_bytes);
    if let Err(e) = fs::write(&ico_icon_path, ico_bytes) {
        logger::warn(&app, &state, SRC, &format!("Could not write shortcut icon ico {}: {e}", ico_icon_path.display()));
    }

    // 2. Build CLI arguments
    let mut extra_args = String::new();
    if let Some(ref acc) = account_id {
        if !acc.trim().is_empty() {
            extra_args.push_str(&format!(" --account \"{}\"", acc.trim()));
        }
    }
    if offline {
        extra_args.push_str(" --offline");
    }

    let mut created_locations = Vec::new();

    // 3. Create platform-specific shortcuts
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::PermissionsExt;

        let desktop_entry = format!(
            "[Desktop Entry]\n\
             Type=Application\n\
             Name={safe_title}\n\
             Comment=Launch {safe_title} directly with Zero Launcher\n\
             Exec=\"{}\" --launch-instance \"{}\"{extra_args}\n\
             Icon={}\n\
             Terminal=false\n\
             Categories=Game;\n\
             StartupNotify=true\n",
            target_exe.display(),
            instance_id,
            png_icon_path.display()
        );

        if desktop {
            if let Some(desktop_dir) = dirs::desktop_dir() {
                let _ = fs::create_dir_all(&desktop_dir);
                let shortcut_path = desktop_dir.join(format!("{safe_title}.desktop"));
                let write_res = fs::write(&shortcut_path, &desktop_entry);
                if let Err(ref e) = write_res {
                    logger::error(&app, &state, SRC, &format!("Failed to write Desktop shortcut {}: {e}", shortcut_path.display()));
                }
                if write_res.is_ok() {
                    logger::info(&app, &state, SRC, &format!("Wrote Desktop shortcut {}", shortcut_path.display()));
                    created_paths.push(shortcut_path.clone());
                    if let Ok(meta) = fs::metadata(&shortcut_path) {
                        let mut perm = meta.permissions();
                        perm.set_mode(perm.mode() | 0o755);
                        let _ = fs::set_permissions(&shortcut_path, perm);
                    }
                    // Trust launcher in GNOME / Nautilus
                    let _ = std::process::Command::new("gio")
                        .args(["set", &shortcut_path.to_string_lossy(), "metadata::trusted", "true"])
                        .status();
                    created_locations.push("Desktop");
                }
            }
        }

        if app_menu {
            if let Some(home) = dirs::home_dir() {
                let apps_dir = home.join(".local/share/applications");
                let _ = fs::create_dir_all(&apps_dir);
                let shortcut_path = apps_dir.join(format!("zerolauncher-instance-{safe_instance_id}.desktop"));
                let write_res = fs::write(&shortcut_path, &desktop_entry);
                if let Err(ref e) = write_res {
                    logger::error(&app, &state, SRC, &format!("Failed to write App Menu shortcut {}: {e}", shortcut_path.display()));
                }
                if write_res.is_ok() {
                    logger::info(&app, &state, SRC, &format!("Wrote App Menu shortcut {}", shortcut_path.display()));
                    created_paths.push(shortcut_path.clone());
                    if let Ok(meta) = fs::metadata(&shortcut_path) {
                        let mut perm = meta.permissions();
                        perm.set_mode(perm.mode() | 0o755);
                        let _ = fs::set_permissions(&shortcut_path, perm);
                    }
                    created_locations.push("Search / App Menu");

                    let apps_dir_clone = apps_dir.clone();
                    std::thread::spawn(move || {
                        let _ = std::process::Command::new("update-desktop-database")
                            .arg(&apps_dir_clone)
                            .status();
                    });
                }
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        use mslnk::ShellLink;

        let args = format!("--launch-instance \"{}\"{extra_args}", instance_id);
        let working_dir = target_exe
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .to_string_lossy()
            .to_string();

        let icon_loc = if ico_icon_path.exists() {
            Some(ico_icon_path.to_string_lossy().to_string())
        } else {
            None
        };

        if desktop {
            if let Some(desktop_dir) = dirs::desktop_dir() {
                let _ = fs::create_dir_all(&desktop_dir);
                let shortcut_path = desktop_dir.join(format!("{safe_title}.lnk"));
                let mut link = ShellLink::new(&target_exe).map_err(|e| e.to_string())?;
                link.set_arguments(Some(args.clone()));
                link.set_working_dir(Some(working_dir.clone()));
                if let Some(ref icon) = icon_loc {
                    link.set_icon_location(Some(icon.clone()));
                }
                link.create_lnk(&shortcut_path)
                    .map_err(|e| {
                        logger::error(&app, &state, SRC, &format!("Failed to create Desktop .lnk {}: {e}", shortcut_path.display()));
                        format!("Failed to create Desktop shortcut: {e}")
                    })?;
                logger::info(&app, &state, SRC, &format!("Wrote Desktop shortcut {}", shortcut_path.display()));
                created_paths.push(shortcut_path.clone());
                created_locations.push("Desktop");
            }
        }

        if app_menu {
            if let Some(appdata) = dirs::data_dir() {
                let start_menu = appdata.join("Microsoft\\Windows\\Start Menu\\Programs");
                let _ = fs::create_dir_all(&start_menu);
                let shortcut_path = start_menu.join(format!("{safe_title}.lnk"));
                let mut link = ShellLink::new(&target_exe).map_err(|e| e.to_string())?;
                link.set_arguments(Some(args.clone()));
                link.set_working_dir(Some(working_dir.clone()));
                if let Some(ref icon) = icon_loc {
                    link.set_icon_location(Some(icon.clone()));
                }
                link.create_lnk(&shortcut_path)
                    .map_err(|e| {
                        logger::error(&app, &state, SRC, &format!("Failed to create Start Menu .lnk {}: {e}", shortcut_path.display()));
                        format!("Failed to create Start Menu shortcut: {e}")
                    })?;
                logger::info(&app, &state, SRC, &format!("Wrote Start Menu shortcut {}", shortcut_path.display()));
                created_paths.push(shortcut_path.clone());
                created_locations.push("Start Menu");
            }
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        return Err("Shortcut creation is currently supported on Windows and Linux.".to_string());
    }

    if created_locations.is_empty() {
        logger::error(&app, &state, SRC, "Shortcut creation produced no files at the selected locations");
        Err("Failed to create shortcut at the selected locations.".to_string())
    } else {
        registry_add(&instance_id, &created_paths);
        logger::info(&app, &state, SRC, &format!("Shortcut created for '{instance_id}' on {}", created_locations.join(" and ")));
        Ok(format!("Shortcut created successfully on {}", created_locations.join(" and ")))
    }
}


// ─────────────────────────────────────────────────────────────────────────
// Shortcut registry + deletion
//
// Every shortcut file we create is remembered in `<install_dir>/shortcuts.json`
// (instance version_id -> list of file paths) so a shortcut whose instance
// was deleted can be found and removed later. On Linux we additionally scan
// the Desktop and applications folders for `.desktop` files whose Exec line
// targets the instance, which also catches shortcuts made by older versions.
// ─────────────────────────────────────────────────────────────────────────

fn registry_path() -> PathBuf {
    crate::first_run_setup::install_dir().join("shortcuts.json")
}

fn registry_load() -> std::collections::HashMap<String, Vec<String>> {
    fs::read_to_string(registry_path())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn registry_save(map: &std::collections::HashMap<String, Vec<String>>) {
    let path = registry_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(map) {
        let _ = fs::write(path, text);
    }
}

fn registry_add(instance_id: &str, paths: &[PathBuf]) {
    let mut map = registry_load();
    let entry = map.entry(instance_id.to_string()).or_default();
    for p in paths {
        let s = p.to_string_lossy().to_string();
        if !entry.contains(&s) {
            entry.push(s);
        }
    }
    registry_save(&map);
}

#[cfg(target_os = "linux")]
fn scan_linux_desktop_files(instance_id: &str) -> Vec<PathBuf> {
    let needle = format!("--launch-instance \"{instance_id}\"");
    let mut dirs_to_scan: Vec<PathBuf> = Vec::new();
    if let Some(d) = dirs::desktop_dir() {
        dirs_to_scan.push(d);
    }
    if let Some(h) = dirs::home_dir() {
        dirs_to_scan.push(h.join(".local/share/applications"));
    }
    let mut found = Vec::new();
    for dir in dirs_to_scan {
        let Ok(rd) = fs::read_dir(&dir) else { continue };
        for entry in rd.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("desktop") {
                continue;
            }
            if let Ok(text) = fs::read_to_string(&path) {
                if text.contains(&needle) {
                    found.push(path);
                }
            }
        }
    }
    found
}

/// Deletes every shortcut (Desktop / app menu / Start Menu) that launches
/// `instance_id`. Returns how many files were removed.
#[tauri::command]
pub async fn delete_instance_shortcuts(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
) -> Result<usize, String> {
    logger::info(&app, &state, SRC, &format!("Deleting shortcuts for missing/removed instance '{instance_id}'"));

    let mut targets: Vec<PathBuf> = Vec::new();
    let mut map = registry_load();
    if let Some(list) = map.remove(&instance_id) {
        targets.extend(list.into_iter().map(PathBuf::from));
    }
    #[cfg(target_os = "linux")]
    targets.extend(scan_linux_desktop_files(&instance_id));

    targets.sort();
    targets.dedup();

    let mut removed = 0usize;
    for path in &targets {
        if !path.exists() {
            logger::debug(&app, &state, SRC, &format!("Shortcut already gone: {}", path.display()));
            continue;
        }
        match fs::remove_file(path) {
            Ok(()) => {
                removed += 1;
                logger::info(&app, &state, SRC, &format!("Deleted shortcut file {}", path.display()));
            }
            Err(e) => {
                logger::error(&app, &state, SRC, &format!("Could not delete shortcut {}: {e}", path.display()));
            }
        }
    }
    registry_save(&map);

    // Icons generated for this shortcut are no longer needed either.
    let safe_instance_id: String = instance_id
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    let icons_dir = crate::first_run_setup::install_dir().join("icons").join("instances");
    for ext in ["png", "ico"] {
        let _ = fs::remove_file(icons_dir.join(format!("{safe_instance_id}.{ext}")));
    }

    #[cfg(target_os = "linux")]
    {
        if removed > 0 {
            if let Some(home) = dirs::home_dir() {
                let apps_dir = home.join(".local/share/applications");
                std::thread::spawn(move || {
                    let _ = std::process::Command::new("update-desktop-database").arg(&apps_dir).status();
                });
            }
        }
    }

    logger::info(&app, &state, SRC, &format!("Shortcut cleanup for '{instance_id}' finished: {removed} file(s) removed"));
    Ok(removed)
}
