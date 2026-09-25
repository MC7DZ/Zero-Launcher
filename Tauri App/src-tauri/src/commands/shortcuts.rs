use std::fs;
use std::path::{Path, PathBuf};
use tauri::{Manager, State};
use crate::state::AppState;

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
    })
}

#[tauri::command]
pub async fn get_cli_launch_args(state: State<'_, AppState>) -> Result<Option<CliLaunchArgs>, String> {
    Ok(state.cli_launch_args.lock().unwrap().take())
}

#[tauri::command]
pub fn hide_main_window(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.hide();
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
    state: State<'_, AppState>,
    instance_id: String,
    shortcut_name: String,
    account_id: Option<String>,
    offline: bool,
    desktop: bool,
    app_menu: bool,
) -> Result<String, String> {
    if !desktop && !app_menu {
        return Err("Please select at least one shortcut location (Desktop or Search / App Menu).".to_string());
    }

    let instance = {
        let instances = state.instances.lock().unwrap();
        instances
            .iter()
            .find(|i| i.version_id == instance_id)
            .cloned()
            .ok_or_else(|| format!("Instance '{}' not found", instance_id))?
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
    let _ = fs::write(&png_icon_path, png_bytes);

    let ico_icon_path = icons_dir.join(format!("{}.ico", safe_instance_id));
    let ico_bytes = png_to_ico(png_bytes);
    let _ = fs::write(&ico_icon_path, ico_bytes);

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
             StartupWMClass=zerolauncher\n\
             StartupNotify=true\n",
            target_exe.display(),
            instance_id,
            png_icon_path.display()
        );

        if desktop {
            if let Some(desktop_dir) = dirs::desktop_dir() {
                let _ = fs::create_dir_all(&desktop_dir);
                let shortcut_path = desktop_dir.join(format!("{safe_title}.desktop"));
                if fs::write(&shortcut_path, &desktop_entry).is_ok() {
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
                if fs::write(&shortcut_path, &desktop_entry).is_ok() {
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
                    .map_err(|e| format!("Failed to create Desktop shortcut: {e}"))?;
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
                    .map_err(|e| format!("Failed to create Start Menu shortcut: {e}"))?;
                created_locations.push("Start Menu");
            }
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        return Err("Shortcut creation is currently supported on Windows and Linux.".to_string());
    }

    if created_locations.is_empty() {
        Err("Failed to create shortcut at the selected locations.".to_string())
    } else {
        Ok(format!("Shortcut created successfully on {}", created_locations.join(" and ")))
    }
}
