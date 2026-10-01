use std::fs;
use std::path::Path;

const ICON_PNG_BYTES: &[u8] = include_bytes!("../assets/icon.png");

#[cfg(target_os = "linux")]
pub fn create_linux_shortcuts(
    exe_path: &Path,
    create_desktop: bool,
    create_menu: bool,
) -> Result<(), String> {
    let install_dir = exe_path.parent().ok_or("Cannot get install directory")?;

    // 1. Copy icon to install directory
    let icon_dest = install_dir.join("icon.png");
    let _ = fs::write(&icon_dest, ICON_PNG_BYTES);

    // 2. Install icon into standard XDG hicolor directories
    if let Some(data_dir) = dirs::data_dir() {
        let hicolor_dir = data_dir.join("icons/hicolor/128x128/apps");
        if fs::create_dir_all(&hicolor_dir).is_ok() {
            let _ = fs::write(hicolor_dir.join("zerolauncher.png"), ICON_PNG_BYTES);
            let _ = fs::write(hicolor_dir.join("com.zerolauncher.app.png"), ICON_PNG_BYTES);
            let _ = fs::write(hicolor_dir.join("ZeroLauncher.png"), ICON_PNG_BYTES);
        }
    }

    let icon_str = if icon_dest.exists() {
        icon_dest.to_string_lossy().to_string()
    } else {
        "zerolauncher".to_string()
    };

    let desktop_entry_content = format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name=Zero Launcher\n\
         GenericName=Minecraft Launcher\n\
         Comment=Zero Launcher - Fast & Lightweight Minecraft Launcher\n\
         Exec=\"{}\"\n\
         Icon={}\n\
         Terminal=false\n\
         Categories=Game;\n\
         StartupWMClass=zerolauncher\n\
         StartupNotify=true\n",
        exe_path.display(),
        icon_str
    );

    // 3. Applications Menu Shortcut
    if create_menu {
        if let Some(home) = dirs::home_dir() {
            let apps_dir = home.join(".local/share/applications");
            let _ = fs::create_dir_all(&apps_dir);

            let desktop_files = [
                apps_dir.join("com.zerolauncher.app.desktop"),
                apps_dir.join("zerolauncher.desktop"),
                apps_dir.join("ZeroLauncher.desktop"),
            ];

            for df in &desktop_files {
                write_executable_file(df, &desktop_entry_content);
            }

            // Nudge desktop database
            let apps_dir_clone = apps_dir.clone();
            std::thread::spawn(move || {
                let _ = std::process::Command::new("update-desktop-database")
                    .arg(&apps_dir_clone)
                    .status();
            });
        }
    }

    // 4. Desktop Shortcut
    if create_desktop {
        if let Some(desktop_dir) = dirs::desktop_dir() {
            if fs::create_dir_all(&desktop_dir).is_ok() {
                let desktop_shortcut = desktop_dir.join("Zero Launcher.desktop");
                write_executable_file(&desktop_shortcut, &desktop_entry_content);

                // Set trusted attribute on GNOME
                let _ = std::process::Command::new("gio")
                    .args(["set", &desktop_shortcut.to_string_lossy(), "metadata::trusted", "true"])
                    .status();
            }
        }
    }

    Ok(())
}

#[cfg(target_os = "linux")]
fn write_executable_file(path: &Path, content: &str) {
    if fs::write(path, content).is_ok() {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(path) {
            let mut perm = meta.permissions();
            perm.set_mode(perm.mode() | 0o755);
            let _ = fs::set_permissions(path, perm);
        }
    }
}

#[cfg(target_os = "windows")]
pub fn create_windows_shortcuts(
    exe_path: &Path,
    create_desktop: bool,
    create_menu: bool,
) -> Result<(), String> {
    use mslnk::ShellLink;

    let install_dir = exe_path.parent().ok_or("Cannot get install directory")?;
    let icon_ico_bytes = include_bytes!("../assets/icon.ico");
    let icon_path = install_dir.join("icon.ico");
    let _ = fs::write(&icon_path, icon_ico_bytes);

    let working_dir = install_dir.to_string_lossy().to_string();
    let icon_str = if icon_path.exists() {
        Some(icon_path.to_string_lossy().to_string())
    } else {
        None
    };

    let mut links_to_make = Vec::new();

    if create_desktop {
        if let Some(desktop) = dirs::desktop_dir() {
            links_to_make.push(desktop.join("Zero Launcher.lnk"));
        }
    }

    if create_menu {
        if let Some(appdata) = dirs::data_dir() {
            let programs = appdata.join(r"Microsoft\Windows\Start Menu\Programs");
            let _ = fs::create_dir_all(&programs);
            links_to_make.push(programs.join("Zero Launcher.lnk"));
        }
    }

    for link_path in links_to_make {
        let mut sl = ShellLink::new(exe_path).map_err(|e| e.to_string())?;
        sl.set_working_dir(Some(working_dir.clone()));
        if let Some(ref ico) = icon_str {
            sl.set_icon_location(Some(ico.clone()));
        }
        sl.create_lnk(&link_path).map_err(|e| e.to_string())?;
    }

    Ok(())
}
