// First-run install step.
//
// The launcher is normally handed to people as a loose file (an .AppImage
// on Linux, an .exe on Windows) that they run from wherever they happened
// to download it - Downloads, Desktop, a USB stick, etc. That's fragile
// (no icon to click, breaks if the download gets moved/deleted) so the
// very first time the app runs from somewhere else, we:
//
//   1. show a small "setting up" window,
//   2. copy the running executable into its permanent home
//      (Linux: ~/Zero Launcher, Windows: %APPDATA%/Zero Launcher),
//   3. create a proper shortcut/launcher entry pointing at that copy.
//
// Step 1-3 are skipped whenever the exe/AppImage is already running from
// that exact location — there's nothing to copy and the existing shortcut
// already points at the right place.
//
// This must run before the rest of app setup (tray, main window, etc.)
// so call `run_first_time_setup` as the very first thing in `run()`.

use std::fs;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

fn install_dir() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        let mut dir = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        dir.push("Zero Launcher");
        dir
    }
    #[cfg(not(target_os = "windows"))]
    {
        let mut dir = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
        dir.push("Zero Launcher");
        dir
    }
}

fn target_exe_name() -> &'static str {
    #[cfg(target_os = "windows")]
    {
        "ZeroLauncher.exe"
    }
    #[cfg(not(target_os = "windows"))]
    {
        "ZeroLauncher.AppImage"
    }
}

fn needs_setup() -> bool {
    if !is_relevant_build() {
        return false;
    }
    // The only thing that actually matters is "is the running exe/AppImage
    // already sitting where it's supposed to live" - if it is, there's
    // nothing to copy and the shortcut from a previous run already points
    // at the right place, so there's no reason to touch anything. This is
    // deliberately not marker-file based: a stray leftover marker (or one
    // that's missing) shouldn't be what decides whether shortcuts get
    // rewritten - the actual file location is the source of truth.
    match source_exe_path() {
        Some(src) => src != install_dir().join(target_exe_name()),
        None => false,
    }
}

#[cfg(target_os = "linux")]
fn is_relevant_build() -> bool {
    // APPIMAGE is only set by the AppImage runtime when it launches
    // itself - a plain dev build or a `cargo run` won't have it, so we
    // leave those alone.
    std::env::var_os("APPIMAGE").is_some()
}

#[cfg(target_os = "windows")]
fn is_relevant_build() -> bool {
    true
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
fn is_relevant_build() -> bool {
    false
}

/// The real file to copy. On Linux, `current_exe()` points inside the
/// temporary squashfs mount the AppImage runtime creates, not the actual
/// .AppImage on disk - the `APPIMAGE` env var it sets has the real path.
fn source_exe_path() -> Option<PathBuf> {
    #[cfg(target_os = "linux")]
    {
        if let Some(p) = std::env::var_os("APPIMAGE") {
            return Some(PathBuf::from(p));
        }
    }
    std::env::current_exe().ok()
}

const SETUP_HTML: &str = r#"<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  html,body{margin:0;height:100%;background:#111318;color:#eee;
    font-family:-apple-system,Segoe UI,sans-serif;
    display:flex;align-items:center;justify-content:center;text-align:center}
  .box{padding:2rem}
  h1{font-size:1.05rem;margin:0 0 .5rem;font-weight:600}
  p{color:#9aa0aa;font-size:.8rem;margin:0}
  .spin{width:26px;height:26px;border:3px solid #2a2d35;border-top-color:#6cc0ff;
    border-radius:50%;margin:0 auto 1rem;animation:s .8s linear infinite}
  @keyframes s{to{transform:rotate(360deg)}}
</style></head>
<body><div class="box">
  <div class="spin"></div>
  <h1>Setting up Zero Launcher&hellip;</h1>
  <p>Installing files and creating a shortcut.<br>This only happens once.</p>
</div></body></html>"#;

/// Entry point - call before anything else in `run()`.
pub fn run_first_time_setup(app: &AppHandle) {
    if !needs_setup() {
        return;
    }

    let setup_html_path = std::env::temp_dir().join("zerolauncher_setup.html");
    let mut window_shown = false;
    if fs::write(&setup_html_path, SETUP_HTML).is_ok() {
        if let Ok(url) = url::Url::from_file_path(&setup_html_path) {
            if WebviewWindowBuilder::new(app, "setup", WebviewUrl::External(url))
                .title("Zero Launcher Setup")
                .inner_size(360.0, 200.0)
                .resizable(false)
                .minimizable(false)
                .maximizable(false)
                .center()
                .build()
                .is_ok()
            {
                window_shown = true;
            }
        }
    }

    let result = perform_install(app);

    if window_shown {
        if let Some(win) = app.get_webview_window("setup") {
            let _ = win.close();
        }
    }

    match result {
        Ok(()) => {}
        Err(e) => {
            // Not fatal - just means no shortcut got created this time.
            // Since this is no longer marker-based, it'll simply be
            // retried next launch (the exe still won't be in its target
            // spot) instead of silently failing forever.
            eprintln!("[first_run_setup] setup failed: {e}");
        }
    }
}

fn perform_install(app: &AppHandle) -> Result<(), String> {
    let src = source_exe_path().ok_or("could not determine running executable path")?;
    let dir = install_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("could not create {}: {e}", dir.display()))?;
    let dest = dir.join(target_exe_name());

    if src != dest {
        #[cfg(target_os = "linux")]
        {
            let _ = fs::remove_file(&dest);
        }
        #[cfg(target_os = "windows")]
        {
            if dest.exists() {
                let old_path = dest.with_extension("exe.old");
                let _ = fs::remove_file(&old_path);
                let _ = fs::rename(&dest, &old_path);
                let _ = fs::remove_file(&dest);
            }
        }

        fs::copy(&src, &dest).map_err(|e| format!("copy to {} failed: {e}", dest.display()))?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(meta) = fs::metadata(&dest) {
                let mut perm = meta.permissions();
                perm.set_mode(perm.mode() | 0o755);
                let _ = fs::set_permissions(&dest, perm);
            }
        }

        // Linux can unlink the file it's currently executing from (the
        // running process keeps its in-memory mapping), so we can clean
        // up the original download and leave only the installed copy.
        #[cfg(target_os = "linux")]
        {
            let _ = fs::remove_file(&src);
        }
        // Windows keeps the exe file locked while it's running, so the
        // original can't be removed here - it's safe for the user to
        // delete it by hand afterwards. Only the copy in
        // %APPDATA%\Zero Launcher is what the new shortcuts point to.
    }

    #[cfg(target_os = "linux")]
    create_linux_shortcut(&dest)?;
    #[cfg(target_os = "windows")]
    create_windows_shortcuts(&dest)?;
    let _ = app; // silence unused-var warning on platforms that don't need it

    Ok(())
}

#[cfg(target_os = "linux")]
fn create_linux_shortcut(exe_path: &Path) -> Result<(), String> {
    let icon_path = install_dir().join("icon.png");
    if fs::write(&icon_path, include_bytes!("../icons/128x128.png")).is_err() {
        // Non-fatal - the shortcut will just fall back to a generic icon.
    }

    let apps_dir = dirs::home_dir()
        .map(|h| h.join(".local/share/applications"))
        .ok_or("no home directory")?;
    fs::create_dir_all(&apps_dir).map_err(|e| e.to_string())?;

    let icon_line = if icon_path.exists() {
        format!("Icon={}\n", icon_path.display())
    } else {
        String::new()
    };

    let desktop_entry = format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name=Zero Launcher\n\
         Comment=Zero Launcher - Minecraft launcher\n\
         Exec=\"{}\"\n\
         {icon_line}\
         Terminal=false\n\
         Categories=Game;\n\
         StartupWMClass=ZeroLauncher\n",
        exe_path.display()
    );

    let desktop_file = apps_dir.join("zerolauncher.desktop");
    fs::write(&desktop_file, desktop_entry).map_err(|e| e.to_string())?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(&desktop_file) {
            let mut perm = meta.permissions();
            perm.set_mode(perm.mode() | 0o755);
            let _ = fs::set_permissions(&desktop_file, perm);
        }
    }

    // Best-effort nudge so DEs that cache the app list pick it up right
    // away instead of after the next login.
    let _ = std::process::Command::new("update-desktop-database")
        .arg(&apps_dir)
        .status();

    Ok(())
}

#[cfg(target_os = "windows")]
fn create_windows_shortcuts(exe_path: &Path) -> Result<(), String> {
    use mslnk::ShellLink;

    let mut link_paths: Vec<PathBuf> = Vec::new();
    if let Some(desktop) = dirs::desktop_dir() {
        link_paths.push(desktop.join("Zero Launcher.lnk"));
    }
    if let Some(appdata_roaming) = dirs::data_dir() {
        let start_menu = appdata_roaming.join("Microsoft\\Windows\\Start Menu\\Programs");
        let _ = fs::create_dir_all(&start_menu);
        link_paths.push(start_menu.join("Zero Launcher.lnk"));
    }

    if link_paths.is_empty() {
        return Err("could not resolve Desktop/Start Menu folders".into());
    }

    let working_dir = exe_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .to_string_lossy()
        .to_string();

    for link_path in link_paths {
        let mut link = ShellLink::new(exe_path).map_err(|e| e.to_string())?;
        link.set_working_dir(Some(working_dir.clone()));
        link.create_lnk(&link_path)
            .map_err(|e| format!("creating {} failed: {e}", link_path.display()))?;
    }

    Ok(())
}
