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

/// Command-line flag the relocated copy is launched with, followed by the
/// path of the original download it should delete once it's up and running
/// from its permanent home. Only ever set by `perform_install` itself.
const CLEANUP_SOURCE_FLAG: &str = "--zl-cleanup-source";

/// If this process was just relaunched by `perform_install` after being
/// copied into place, finish the job: delete the original exe it was
/// copied from. Safe to call unconditionally - it's a no-op unless the
/// special flag is present, and it never removes anything other than the
/// exact path that was passed to it.
fn cleanup_previous_source_if_requested() {
    let mut args = std::env::args_os();
    while let Some(arg) = args.next() {
        if arg == CLEANUP_SOURCE_FLAG {
            if let Some(old_path) = args.next() {
                let old_path = PathBuf::from(old_path);
                // Best-effort: the old file may already be gone, or briefly
                // still locked right after the previous process exited -
                // a few short retries covers that without noticeably
                // delaying startup.
                for _ in 0..10 {
                    if !old_path.exists() || fs::remove_file(&old_path).is_ok() {
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(150));
                }
            }
            break;
        }
    }
}

/// Entry point - call before anything else in `run()`.
pub fn run_first_time_setup(app: &AppHandle) {
    cleanup_previous_source_if_requested();

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
        // Windows keeps the exe file locked while it's running, so it
        // can't be deleted from here. Instead, once the shortcuts are set
        // up below, we launch the copy we just made (passing it the
        // original's path so *it* can delete that file once we've exited
        // and released the lock), then exit this process. The user ends
        // up with only the installed copy running, same as on Linux.
    }

    #[cfg(target_os = "linux")]
    create_linux_shortcut(&dest)?;
    #[cfg(target_os = "windows")]
    create_windows_shortcuts(&dest)?;
    let _ = app; // silence unused-var warning on platforms that don't need it

    #[cfg(target_os = "windows")]
    if src != dest {
        relaunch_from_installed_copy(&dest, &src)?;
    }

    Ok(())
}

/// Windows only: start the newly-installed copy (telling it, via
/// `CLEANUP_SOURCE_FLAG`, to delete the original download once it's up),
/// then exit this process immediately so the original exe's file lock is
/// released and the copy can remove it.
#[cfg(target_os = "windows")]
fn relaunch_from_installed_copy(dest: &Path, src: &Path) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    std::process::Command::new(dest)
        .arg(CLEANUP_SOURCE_FLAG)
        .arg(src)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("failed to launch installed copy at {}: {e}", dest.display()))?;

    std::process::exit(0);
}

#[cfg(target_os = "linux")]
fn create_linux_shortcut(exe_path: &Path) -> Result<(), String> {
    // The plain (no-background) logo is what shows up anywhere the app is
    // *running* — taskbar, alt-tab, tray, the live window icon — since all
    // of those resolve through ensure_linux_xdg_icons() below. The
    // background/"shortcut" variant is reserved for the double-click icon
    // file on the Desktop (see further down), which is a distinct static
    // shortcut rather than something reflecting the running app.
    //
    // This is also refreshed unconditionally on every single launch (see
    // ensure_linux_xdg_icons, called from lib.rs) — not just here, which
    // only runs the *first* time the exe gets copied into its permanent
    // home. Otherwise, updating icons/shortcut.png in a new build and
    // shipping it to someone who already has the launcher installed would
    // silently do nothing: needs_setup() is false because the exe is
    // already in the right place, so this function would never re-run and
    // the old icon bytes baked into ~/Zero Launcher/icon.png would linger
    // forever.
    let icon_path = ensure_linux_xdg_icons();

    let apps_dir = dirs::home_dir()
        .map(|h| h.join(".local/share/applications"))
        .ok_or("no home directory")?;
    fs::create_dir_all(&apps_dir).map_err(|e| e.to_string())?;

    let icon_str = match &icon_path {
        Some(p) if p.exists() => p.to_string_lossy().to_string(),
        _ => "zerolauncher".to_string(),
    };

    let desktop_entry = format!(
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

    // Write both standard names so Wayland compositors matching either
    // `com.zerolauncher.app` or `zerolauncher` find the desktop entry and icon.
    let desktop_files = [
        apps_dir.join("com.zerolauncher.app.desktop"),
        apps_dir.join("zerolauncher.desktop"),
        apps_dir.join("ZeroLauncher.desktop"),
    ];

    for df in &desktop_files {
        write_and_mark_executable(df, &desktop_entry);
    }

    // Best-effort nudge so DEs that cache the app list pick it up right away
    let _ = std::process::Command::new("update-desktop-database")
        .arg(&apps_dir)
        .status();

    // Also drop a launcher icon straight onto the user's Desktop, same as
    // the Windows build does with a .lnk in %USERPROFILE%\Desktop. Not
    // every distro has a Desktop folder (headless/minimal setups), so this
    // is skipped quietly if one can't be found.
    if let Some(desktop_dir) = dirs::desktop_dir() {
        if fs::create_dir_all(&desktop_dir).is_ok() {
            // This one gets its own icon file — the background variant —
            // since it's a distinct double-click shortcut icon sitting on
            // the desktop background, not something the taskbar/alt-tab
            // ever reads. The two apps_dir entries above (which DO drive
            // taskbar/alt-tab, via app_id → desktop-file → Icon=
            // resolution) use the plain no-background logo instead.
            let desktop_icon_bytes = include_bytes!("../icons/shortcut.png");
            let desktop_icon_path = install_dir().join("desktop-icon.png");
            let desktop_icon_str = if fs::write(&desktop_icon_path, desktop_icon_bytes).is_ok() {
                desktop_icon_path.to_string_lossy().to_string()
            } else {
                icon_str.clone()
            };
            let desktop_folder_entry = desktop_entry.replacen(
                &format!("Icon={}\n", icon_str),
                &format!("Icon={}\n", desktop_icon_str),
                1,
            );

            let desktop_shortcut = desktop_dir.join("Zero Launcher.desktop");
            write_and_mark_executable(&desktop_shortcut, &desktop_folder_entry);

            // GNOME/Nautilus refuses to treat a new .desktop file on the
            // Desktop as a trusted launcher (shows "Untrusted Application
            // Launcher" until right-clicked  -> Allow Launching) unless its
            // "trusted" metadata is set. This is best-effort; other file
            // managers (Dolphin, Thunar, etc.) don't need it.
            let _ = std::process::Command::new("gio")
                .args(["set", &desktop_shortcut.to_string_lossy(), "metadata::trusted", "true"])
                .status();
        }
    }

    Ok(())
}

/// Write a `.desktop` file's contents and mark it executable (required for
/// it to be treated as a launcher rather than opened as plain text).
#[cfg(target_os = "linux")]
fn write_and_mark_executable(path: &Path, contents: &str) {
    if fs::write(path, contents).is_ok() {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(path) {
            let mut perm = meta.permissions();
            perm.set_mode(perm.mode() | 0o755);
            let _ = fs::set_permissions(path, perm);
        }
    }
}

/// Writes the current icon bytes (icons/shortcut.png, baked into the
/// binary at compile time) to every place a Linux desktop environment
/// might read it from: the literal path the .desktop `Icon=` line points
/// to, plus the XDG hicolor icon theme so lookups by app id/name resolve
/// too. This is what the taskbar and alt-tab actually end up showing —
/// Wayland has no per-window icon protocol, so compositors resolve a
/// running window's icon by matching its app_id back to an installed
/// .desktop file and reading that file's `Icon=`. Uses the plain logo (no
/// background), matching the tray icon and the live window icon set in
/// lib.rs, so the app looks the same everywhere it shows up while running.
/// Called unconditionally on *every* launch (from lib.rs setup), so a
/// rebuilt icon always reaches an already-installed copy of the launcher —
/// not just on first install. Returns the literal icon.png path so callers
/// building the desktop entry's `Icon=` line can reuse it.
#[cfg(target_os = "linux")]
pub fn ensure_linux_xdg_icons() -> Option<PathBuf> {
    let icon_bytes = include_bytes!("../icons/icon.png");

    let icon_path = install_dir().join("icon.png");
    let wrote_icon_path = fs::write(&icon_path, icon_bytes).is_ok();

    if let Some(data_dir) = dirs::data_dir() {
        let hicolor_dir = data_dir.join("icons/hicolor/128x128/apps");
        if fs::create_dir_all(&hicolor_dir).is_ok() {
            let _ = fs::write(hicolor_dir.join("zerolauncher.png"), icon_bytes);
            let _ = fs::write(hicolor_dir.join("com.zerolauncher.app.png"), icon_bytes);
            let _ = fs::write(hicolor_dir.join("ZeroLauncher.png"), icon_bytes);
        }
        // Nudge GTK/GNOME's icon cache so it doesn't keep serving a
        // previously-cached version of an icon file that just changed
        // out from under it. Best-effort: this cache dir/tool isn't
        // present on every distro (e.g. it's a no-op on pure-Wayland
        // GNOME setups that don't use the old gdk-pixbuf icon cache), and
        // failing quietly here is fine either way since it's just a
        // freshness optimization, not something the app depends on.
        let _ = std::process::Command::new("gtk-update-icon-cache")
            .args(["-f", "-t"])
            .arg(data_dir.join("icons/hicolor"))
            .status();
    }

    if wrote_icon_path {
        Some(icon_path)
    } else {
        None
    }
}

#[cfg(target_os = "windows")]
pub fn ensure_windows_shortcuts() {
    let icon_bytes = include_bytes!("../icons/shortcut.ico");
    let icon_path = install_dir().join("desktop-icon.ico");
    let _ = fs::write(&icon_path, icon_bytes);

    let target_exe = install_dir().join(target_exe_name());
    if target_exe.exists() {
        let _ = create_windows_shortcuts(&target_exe);
    }
}

#[cfg(target_os = "windows")]
fn create_windows_shortcuts(exe_path: &Path) -> Result<(), String> {
    use mslnk::ShellLink;

    let icon_bytes = include_bytes!("../icons/shortcut.ico");
    let icon_path = install_dir().join("desktop-icon.ico");
    let _ = fs::write(&icon_path, icon_bytes);

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

    let icon_loc = if icon_path.exists() {
        Some(icon_path.to_string_lossy().to_string())
    } else {
        None
    };

    for link_path in link_paths {
        let mut link = ShellLink::new(exe_path).map_err(|e| e.to_string())?;
        link.set_working_dir(Some(working_dir.clone()));
        if let Some(ref icon) = icon_loc {
            link.set_icon_location(Some(icon.clone()));
        }
        link.create_lnk(&link_path)
            .map_err(|e| format!("creating {} failed: {e}", link_path.display()))?;
    }

    Ok(())
}
