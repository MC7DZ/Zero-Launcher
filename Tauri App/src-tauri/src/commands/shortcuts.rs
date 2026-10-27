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


// ─────────────────────────────────────────────────────────────────────────
// Shortcut model + registry
//
// Every shortcut the launcher creates is remembered in
// `<install_dir>/shortcuts.json` so Settings → Shortcuts can list, edit and
// delete them. On Linux, `.desktop` files that target an instance but are
// missing from the registry (made by older versions) are imported on the fly.
// ─────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ShortcutRecord {
    pub id: String,
    pub instance_id: String,
    pub name: String,
    #[serde(default)]
    pub account_id: Option<String>,
    #[serde(default)]
    pub offline: bool,
    #[serde(default)]
    pub desktop_path: Option<String>,
    #[serde(default)]
    pub app_menu_path: Option<String>,
}

/// What the Settings → Shortcuts panel receives.
#[derive(Debug, Clone, serde::Serialize)]
pub struct ShortcutInfo {
    pub id: String,
    pub instance_id: String,
    pub name: String,
    pub account_id: Option<String>,
    pub offline: bool,
    pub desktop: bool,
    pub app_menu: bool,
    pub desktop_path: Option<String>,
    pub app_menu_path: Option<String>,
    pub instance_exists: bool,
    pub instance_name: String,
    pub minecraft_version: String,
    pub loader: String,
}

fn registry_path() -> PathBuf {
    crate::first_run_setup::install_dir().join("shortcuts.json")
}

fn registry_load() -> Vec<ShortcutRecord> {
    let Ok(text) = fs::read_to_string(registry_path()) else { return Vec::new() };
    serde_json::from_str::<Vec<ShortcutRecord>>(&text).unwrap_or_default()
}

fn registry_save(records: &[ShortcutRecord]) {
    let path = registry_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(records) {
        let _ = fs::write(path, text);
    }
}

fn new_shortcut_id() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("{:x}", nanos)
}

fn safe_instance_key(instance_id: &str) -> String {
    instance_id
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect()
}

fn sanitize_title(shortcut_name: &str, instance_name: &str, instance_id: &str) -> String {
    let cleaned: String = shortcut_name
        .chars()
        .map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' || c == '_' { c } else { '_' })
        .collect();
    if cleaned.trim().is_empty() {
        if !instance_name.trim().is_empty() {
            instance_name.trim().to_string()
        } else {
            instance_id.to_string()
        }
    } else {
        cleaned.trim().to_string()
    }
}

#[cfg(target_os = "linux")]
fn shell_split(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut in_q = false;
    let mut has = false;
    for c in s.chars() {
        match c {
            '"' => {
                in_q = !in_q;
                has = true;
            }
            c if c.is_whitespace() && !in_q => {
                if has || !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                    has = false;
                }
            }
            c => cur.push(c),
        }
    }
    if has || !cur.is_empty() {
        out.push(cur);
    }
    out
}

/// Reads a `.desktop` file we (or an older version) made. Returns
/// `(name, launch args)` or `None` if it isn't one of ours.
#[cfg(target_os = "linux")]
fn parse_desktop_file(path: &Path) -> Option<(String, CliLaunchArgs)> {
    let text = fs::read_to_string(path).ok()?;
    let mut name = None;
    let mut exec = None;
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("Name=") {
            name.get_or_insert_with(|| v.trim().to_string());
        } else if let Some(v) = line.strip_prefix("Exec=") {
            exec.get_or_insert_with(|| v.trim().to_string());
        }
    }
    let tokens = shell_split(&exec?);
    let args = parse_cli_launch_args(&tokens)?;
    let name = name.filter(|n| !n.is_empty()).unwrap_or_else(|| args.version_id.clone());
    Some((name, args))
}

#[cfg(target_os = "linux")]
fn linux_apps_dir() -> Option<PathBuf> {
    dirs::home_dir().map(|h| h.join(".local/share/applications"))
}

/// Adds records for launcher `.desktop` files that aren't in the registry yet.
/// Returns true if anything was added.
#[cfg(target_os = "linux")]
fn import_linux_shortcuts(records: &mut Vec<ShortcutRecord>) -> bool {
    let apps_dir = linux_apps_dir();
    let mut dirs_to_scan: Vec<PathBuf> = Vec::new();
    if let Some(d) = dirs::desktop_dir() {
        dirs_to_scan.push(d);
    }
    if let Some(d) = apps_dir.clone() {
        dirs_to_scan.push(d);
    }

    let mut changed = false;
    for dir in dirs_to_scan {
        let Ok(rd) = fs::read_dir(&dir) else { continue };
        for entry in rd.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("desktop") {
                continue;
            }
            let path_str = path.to_string_lossy().to_string();
            let known = records.iter().any(|r| {
                r.desktop_path.as_deref() == Some(path_str.as_str())
                    || r.app_menu_path.as_deref() == Some(path_str.as_str())
            });
            if known {
                continue;
            }
            let Some((name, args)) = parse_desktop_file(&path) else { continue };
            let is_app_menu = apps_dir.as_deref() == path.parent();

            // Merge with a sibling record (same target/options) if its slot is free.
            let sibling = records.iter_mut().find(|r| {
                r.instance_id == args.version_id
                    && r.name == name
                    && r.account_id == args.account_id
                    && r.offline == args.offline
                    && if is_app_menu { r.app_menu_path.is_none() } else { r.desktop_path.is_none() }
            });
            if let Some(r) = sibling {
                if is_app_menu {
                    r.app_menu_path = Some(path_str);
                } else {
                    r.desktop_path = Some(path_str);
                }
            } else {
                records.push(ShortcutRecord {
                    id: new_shortcut_id(),
                    instance_id: args.version_id,
                    name,
                    account_id: args.account_id,
                    offline: args.offline,
                    desktop_path: if is_app_menu { None } else { Some(path_str.clone()) },
                    app_menu_path: if is_app_menu { Some(path_str) } else { None },
                });
            }
            changed = true;
        }
    }
    changed
}

/// Drops registry entries whose files were deleted outside the launcher.
fn prune_missing(records: &mut Vec<ShortcutRecord>) -> bool {
    let mut changed = false;
    for r in records.iter_mut() {
        if let Some(p) = &r.desktop_path {
            if !Path::new(p).exists() {
                r.desktop_path = None;
                changed = true;
            }
        }
        if let Some(p) = &r.app_menu_path {
            if !Path::new(p).exists() {
                r.app_menu_path = None;
                changed = true;
            }
        }
    }
    let before = records.len();
    records.retain(|r| r.desktop_path.is_some() || r.app_menu_path.is_some());
    changed || records.len() != before
}

/// Merges records that describe the same shortcut but were split per location
/// (one with only a Desktop file, another with only an App Menu file).
fn merge_split_records(records: &mut Vec<ShortcutRecord>) -> bool {
    let mut changed = false;
    let mut i = 0;
    while i < records.len() {
        let mut j = i + 1;
        while j < records.len() {
            let same = records[i].instance_id == records[j].instance_id
                && records[i].name == records[j].name
                && records[i].account_id == records[j].account_id
                && records[i].offline == records[j].offline
                && ((records[i].desktop_path.is_none() && records[j].app_menu_path.is_none()
                    && records[j].desktop_path.is_some() && records[i].app_menu_path.is_some())
                    || (records[i].app_menu_path.is_none() && records[j].desktop_path.is_none()
                        && records[j].app_menu_path.is_some() && records[i].desktop_path.is_some())
                    // exact duplicate pointing at the same files
                    || (records[i].desktop_path == records[j].desktop_path
                        && records[i].app_menu_path == records[j].app_menu_path));
            if same {
                let other = records.remove(j);
                if records[i].desktop_path.is_none() {
                    records[i].desktop_path = other.desktop_path;
                }
                if records[i].app_menu_path.is_none() {
                    records[i].app_menu_path = other.app_menu_path;
                }
                changed = true;
            } else {
                j += 1;
            }
        }
        i += 1;
    }
    changed
}

fn load_synced_registry(app: &tauri::AppHandle, state: &AppState) -> Vec<ShortcutRecord> {
    let mut records = registry_load();
    let mut changed = false;
    #[cfg(target_os = "linux")]
    {
        if import_linux_shortcuts(&mut records) {
            logger::info(app, state, SRC, "Imported existing .desktop shortcuts into the shortcut registry");
            changed = true;
        }
    }
    if merge_split_records(&mut records) {
        logger::debug(app, state, SRC, "Merged split shortcut records (one card per shortcut)");
        changed = true;
    }
    if prune_missing(&mut records) {
        logger::debug(app, state, SRC, "Pruned shortcut registry entries whose files no longer exist");
        changed = true;
    }
    if changed {
        registry_save(&records);
    }
    records
}

#[cfg(target_os = "linux")]
fn refresh_desktop_database() {
    if let Some(apps_dir) = linux_apps_dir() {
        std::thread::spawn(move || {
            let _ = std::process::Command::new("update-desktop-database").arg(&apps_dir).status();
        });
    }
}

fn remove_file_logged(app: &tauri::AppHandle, state: &AppState, path: &str) -> bool {
    let p = Path::new(path);
    if !p.exists() {
        logger::debug(app, state, SRC, &format!("Shortcut file already gone: {path}"));
        return false;
    }
    match fs::remove_file(p) {
        Ok(()) => {
            logger::info(app, state, SRC, &format!("Deleted shortcut file {path}"));
            true
        }
        Err(e) => {
            logger::error(app, state, SRC, &format!("Could not delete shortcut file {path}: {e}"));
            false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Writing shortcut files
// ─────────────────────────────────────────────────────────────────────────

struct WriteRequest<'a> {
    id: &'a str,
    instance: &'a crate::models::InstalledInstance,
    instance_id: &'a str,
    title: &'a str,
    account_id: Option<&'a str>,
    offline: bool,
    want_desktop: bool,
    want_app_menu: bool,
    old_desktop: Option<&'a str>,
    old_app_menu: Option<&'a str>,
}

struct WriteResult {
    desktop: Option<PathBuf>,
    app_menu: Option<PathBuf>,
}

/// Writes (or rewrites) the shortcut files for one shortcut and removes
/// files for locations that are no longer wanted or were renamed.
fn write_shortcut_files(
    app: &tauri::AppHandle,
    state: &AppState,
    req: &WriteRequest<'_>,
) -> Result<WriteResult, String> {
    let target_exe = get_launcher_exe();
    logger::debug(app, state, SRC, &format!("Shortcut target exe: {}", target_exe.display()));

    // Icon (instance icon.png, else loader icon)
    let instance = req.instance;
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
    let key = safe_instance_key(req.instance_id);
    let icons_dir = crate::first_run_setup::install_dir().join("icons").join("instances");
    let _ = fs::create_dir_all(&icons_dir);
    let png_icon_path = icons_dir.join(format!("{key}.png"));
    if let Err(e) = fs::write(&png_icon_path, png_bytes) {
        logger::warn(app, state, SRC, &format!("Could not write shortcut icon png {}: {e}", png_icon_path.display()));
    }
    let ico_icon_path = icons_dir.join(format!("{key}.ico"));
    if let Err(e) = fs::write(&ico_icon_path, png_to_ico(png_bytes)) {
        logger::warn(app, state, SRC, &format!("Could not write shortcut icon ico {}: {e}", ico_icon_path.display()));
    }

    // CLI arguments
    let mut extra_args = String::new();
    if let Some(acc) = req.account_id {
        if !acc.trim().is_empty() {
            extra_args.push_str(&format!(" --account \"{}\"", acc.trim()));
        }
    }
    if req.offline {
        extra_args.push_str(" --offline");
    }

    let mut result = WriteResult { desktop: None, app_menu: None };
    let title = req.title;

    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::PermissionsExt;

        let desktop_entry = format!(
            "[Desktop Entry]\n\
             Type=Application\n\
             Name={title}\n\
             Comment=Launch {title} directly with Zero Launcher\n\
             Exec=\"{}\" --launch-instance \"{}\"{extra_args}\n\
             Icon={}\n\
             Terminal=false\n\
             Categories=Game;\n\
             StartupNotify=true\n",
            target_exe.display(),
            req.instance_id,
            png_icon_path.display()
        );

        let write_one = |path: &Path, label: &str, trust: bool| -> bool {
            match fs::write(path, &desktop_entry) {
                Ok(()) => {
                    logger::info(app, state, SRC, &format!("Wrote {label} shortcut {}", path.display()));
                    if let Ok(meta) = fs::metadata(path) {
                        let mut perm = meta.permissions();
                        perm.set_mode(perm.mode() | 0o755);
                        let _ = fs::set_permissions(path, perm);
                    }
                    if trust {
                        let _ = std::process::Command::new("gio")
                            .args(["set", &path.to_string_lossy(), "metadata::trusted", "true"])
                            .status();
                    }
                    true
                }
                Err(e) => {
                    logger::error(app, state, SRC, &format!("Failed to write {label} shortcut {}: {e}", path.display()));
                    false
                }
            }
        };

        // Desktop
        if req.want_desktop {
            if let Some(desktop_dir) = dirs::desktop_dir() {
                let _ = fs::create_dir_all(&desktop_dir);
                let target = desktop_dir.join(format!("{title}.desktop"));
                if let Some(old) = req.old_desktop {
                    if Path::new(old) != target.as_path() {
                        remove_file_logged(app, state, old);
                    }
                }
                if write_one(&target, "Desktop", true) {
                    result.desktop = Some(target);
                }
            }
        } else if let Some(old) = req.old_desktop {
            remove_file_logged(app, state, old);
        }

        // App menu — keep the existing file name when there is one
        if req.want_app_menu {
            if let Some(apps_dir) = linux_apps_dir() {
                let _ = fs::create_dir_all(&apps_dir);
                let target = match req.old_app_menu {
                    Some(old) => PathBuf::from(old),
                    None => apps_dir.join(format!("zerolauncher-instance-{key}-{}.desktop", req.id)),
                };
                if write_one(&target, "App Menu", false) {
                    result.app_menu = Some(target);
                }
            }
        } else if let Some(old) = req.old_app_menu {
            remove_file_logged(app, state, old);
        }

        refresh_desktop_database();
    }

    #[cfg(target_os = "windows")]
    {
        use mslnk::ShellLink;

        let args = format!("--launch-instance \"{}\"{extra_args}", req.instance_id);
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

        let make_lnk = |path: &Path, label: &str| -> Result<(), String> {
            let mut link = ShellLink::new(&target_exe).map_err(|e| e.to_string())?;
            link.set_arguments(Some(args.clone()));
            link.set_working_dir(Some(working_dir.clone()));
            if let Some(ref icon) = icon_loc {
                link.set_icon_location(Some(icon.clone()));
            }
            link.create_lnk(path).map_err(|e| {
                logger::error(app, state, SRC, &format!("Failed to create {label} .lnk {}: {e}", path.display()));
                format!("Failed to create {label} shortcut: {e}")
            })?;
            logger::info(app, state, SRC, &format!("Wrote {label} shortcut {}", path.display()));
            Ok(())
        };

        if req.want_desktop {
            if let Some(desktop_dir) = dirs::desktop_dir() {
                let _ = fs::create_dir_all(&desktop_dir);
                let target = desktop_dir.join(format!("{title}.lnk"));
                if let Some(old) = req.old_desktop {
                    if Path::new(old) != target.as_path() {
                        remove_file_logged(app, state, old);
                    }
                }
                make_lnk(&target, "Desktop")?;
                result.desktop = Some(target);
            }
        } else if let Some(old) = req.old_desktop {
            remove_file_logged(app, state, old);
        }

        if req.want_app_menu {
            if let Some(appdata) = dirs::data_dir() {
                let start_menu = appdata.join("Microsoft\\Windows\\Start Menu\\Programs");
                let _ = fs::create_dir_all(&start_menu);
                let target = start_menu.join(format!("{title}.lnk"));
                if let Some(old) = req.old_app_menu {
                    if Path::new(old) != target.as_path() {
                        remove_file_logged(app, state, old);
                    }
                }
                make_lnk(&target, "Start Menu")?;
                result.app_menu = Some(target);
            }
        } else if let Some(old) = req.old_app_menu {
            remove_file_logged(app, state, old);
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        let _ = (&target_exe, &png_icon_path, &ico_icon_path, &extra_args, title);
        return Err("Shortcut creation is currently supported on Windows and Linux.".to_string());
    }

    #[allow(unreachable_code)]
    Ok(result)
}

fn find_instance(state: &AppState, instance_id: &str) -> Option<crate::models::InstalledInstance> {
    state
        .instances
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .find(|i| i.version_id == instance_id)
        .cloned()
}

/// Called whenever an instance's `version_id` changes (it was renamed or
/// reinstalled under a new folder name). Shortcuts launch an instance *by that
/// id*, so without this they would be left pointing at an instance that no
/// longer exists ("The instance ... no longer exists"). Rewrites each affected
/// shortcut's files and registry record to use `new_id`.
pub fn rekey_instance_shortcuts(app: &tauri::AppHandle, state: &AppState, old_id: &str, new_id: &str) {
    if old_id == new_id {
        return;
    }
    let Some(instance) = find_instance(state, new_id) else { return };
    let mut records = registry_load();
    let mut changed = false;
    for rec in records.iter_mut() {
        if rec.instance_id != old_id {
            continue;
        }
        let rec_id = rec.id.clone();
        let rec_name = rec.name.clone();
        let rec_account = rec.account_id.clone();
        let rec_offline = rec.offline;
        let old_desktop = rec.desktop_path.clone();
        let old_app_menu = rec.app_menu_path.clone();
        let written = write_shortcut_files(app, state, &WriteRequest {
            id: &rec_id,
            instance: &instance,
            instance_id: new_id,
            title: &rec_name,
            account_id: rec_account.as_deref(),
            offline: rec_offline,
            want_desktop: old_desktop.is_some(),
            want_app_menu: old_app_menu.is_some(),
            old_desktop: old_desktop.as_deref(),
            old_app_menu: old_app_menu.as_deref(),
        });
        match written {
            Ok(w) => {
                rec.instance_id = new_id.to_string();
                if old_desktop.is_some() {
                    rec.desktop_path = w.desktop.map(|p| p.to_string_lossy().to_string()).or(old_desktop);
                }
                if old_app_menu.is_some() {
                    rec.app_menu_path = w.app_menu.map(|p| p.to_string_lossy().to_string()).or(old_app_menu);
                }
                changed = true;
                logger::info(app, state, SRC, &format!(
                    "Shortcut {rec_id} now launches '{new_id}' (was '{old_id}')"
                ));
            }
            Err(e) => {
                logger::warn(app, state, SRC, &format!(
                    "Could not re-point shortcut {rec_id} from '{old_id}' to '{new_id}': {e}"
                ));
            }
        }
    }
    if changed {
        registry_save(&records);
        // The generated icon of the old id is no longer used by anything.
        if !records.iter().any(|r| r.instance_id == old_id) {
            let key = safe_instance_key(old_id);
            let icons_dir = crate::first_run_setup::install_dir().join("icons").join("instances");
            for ext in ["png", "ico"] {
                let _ = fs::remove_file(icons_dir.join(format!("{key}.{ext}")));
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────────────

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

    let instance = find_instance(&state, &instance_id).ok_or_else(|| {
        logger::error(&app, &state, SRC, &format!("Create shortcut failed: instance '{instance_id}' not found"));
        format!("Instance '{}' not found", instance_id)
    })?;

    let title = sanitize_title(&shortcut_name, &instance.name, &instance_id);
    let id = new_shortcut_id();
    let account = account_id.as_deref().map(str::trim).filter(|a| !a.is_empty());

    // Sync the registry BEFORE writing the new files. Otherwise the Linux import pass
    // finds the freshly written .desktop files, registers them on its own, and the
    // record pushed below becomes a duplicate (one card per location).
    let mut records = load_synced_registry(&app, &state);

    let written = write_shortcut_files(&app, &state, &WriteRequest {
        id: &id,
        instance: &instance,
        instance_id: &instance_id,
        title: &title,
        account_id: account,
        offline,
        want_desktop: desktop,
        want_app_menu: app_menu,
        old_desktop: None,
        old_app_menu: None,
    })?;

    let mut created_locations = Vec::new();
    if written.desktop.is_some() {
        created_locations.push("Desktop");
    }
    if written.app_menu.is_some() {
        created_locations.push(if cfg!(target_os = "windows") { "Start Menu" } else { "Search / App Menu" });
    }
    if created_locations.is_empty() {
        logger::error(&app, &state, SRC, "Shortcut creation produced no files at the selected locations");
        return Err("Failed to create shortcut at the selected locations.".to_string());
    }

    records.push(ShortcutRecord {
        id,
        instance_id: instance_id.clone(),
        name: title,
        account_id: account.map(|a| a.to_string()),
        offline,
        desktop_path: written.desktop.map(|p| p.to_string_lossy().to_string()),
        app_menu_path: written.app_menu.map(|p| p.to_string_lossy().to_string()),
    });
    registry_save(&records);

    logger::info(&app, &state, SRC, &format!("Shortcut created for '{instance_id}' on {}", created_locations.join(" and ")));
    Ok(format!("Shortcut created successfully on {}", created_locations.join(" and ")))
}

/// Everything Settings → Shortcuts needs to show.
#[tauri::command]
pub async fn list_shortcuts(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Vec<ShortcutInfo>, String> {
    let records = load_synced_registry(&app, &state);
    let mut out: Vec<ShortcutInfo> = records
        .into_iter()
        .map(|r| {
            let inst = find_instance(&state, &r.instance_id);
            ShortcutInfo {
                instance_exists: inst.is_some(),
                instance_name: inst
                    .as_ref()
                    .map(|i| if i.name.trim().is_empty() { i.version_id.clone() } else { i.name.clone() })
                    .unwrap_or_else(|| r.instance_id.clone()),
                minecraft_version: inst.as_ref().map(|i| i.minecraft_version.clone()).unwrap_or_default(),
                loader: inst.as_ref().map(|i| i.loader.clone()).unwrap_or_default(),
                desktop: r.desktop_path.is_some(),
                app_menu: r.app_menu_path.is_some(),
                id: r.id,
                instance_id: r.instance_id,
                name: r.name,
                account_id: r.account_id,
                offline: r.offline,
                desktop_path: r.desktop_path,
                app_menu_path: r.app_menu_path,
            }
        })
        .collect();
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    logger::debug(&app, &state, SRC, &format!("list_shortcuts: {} shortcut(s)", out.len()));
    Ok(out)
}

/// Changes a shortcut's name, account, offline flag and/or locations.
#[tauri::command]
pub async fn update_shortcut(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
    shortcut_name: String,
    account_id: Option<String>,
    offline: bool,
    desktop: bool,
    app_menu: bool,
) -> Result<String, String> {
    logger::info(&app, &state, SRC, &format!(
        "Updating shortcut {id}: name='{shortcut_name}' account={account_id:?} offline={offline} desktop={desktop} app_menu={app_menu}"
    ));
    if !desktop && !app_menu {
        return Err("Pick at least one location, or delete the shortcut instead.".to_string());
    }

    let mut records = load_synced_registry(&app, &state);
    let pos = records.iter().position(|r| r.id == id).ok_or_else(|| {
        logger::error(&app, &state, SRC, &format!("Update failed: shortcut {id} not found"));
        "That shortcut no longer exists.".to_string()
    })?;
    let rec = records[pos].clone();

    let instance = match find_instance(&state, &rec.instance_id) {
        Some(i) => i,
        None => {
            // The instance is gone. Rewriting a shortcut needs the instance, but
            // *removing* a location (e.g. the Delete button on one location of a
            // shortcut that exists on Desktop + App Menu) does not — so allow that
            // instead of failing with "no longer exists".
            let adds_location = (desktop && rec.desktop_path.is_none())
                || (app_menu && rec.app_menu_path.is_none());
            if adds_location {
                logger::warn(&app, &state, SRC, &format!("Update failed: instance '{}' missing", rec.instance_id));
                return Err(format!("The instance \"{}\" no longer exists — delete this shortcut instead.", rec.instance_id));
            }
            logger::info(&app, &state, SRC, &format!(
                "Instance '{}' is missing — only removing the unselected location(s) of shortcut {id}",
                rec.instance_id
            ));
            let mut trimmed = rec.clone();
            if !desktop {
                if let Some(p) = rec.desktop_path.as_deref() {
                    remove_file_logged(&app, &state, p);
                }
                trimmed.desktop_path = None;
            }
            if !app_menu {
                if let Some(p) = rec.app_menu_path.as_deref() {
                    remove_file_logged(&app, &state, p);
                }
                trimmed.app_menu_path = None;
            }
            records[pos] = trimmed;
            registry_save(&records);
            #[cfg(target_os = "linux")]
            refresh_desktop_database();
            return Ok("Shortcut location removed".to_string());
        }
    };

    let title = sanitize_title(&shortcut_name, &instance.name, &rec.instance_id);
    let account = account_id.as_deref().map(str::trim).filter(|a| !a.is_empty());

    let written = write_shortcut_files(&app, &state, &WriteRequest {
        id: &rec.id,
        instance: &instance,
        instance_id: &rec.instance_id,
        title: &title,
        account_id: account,
        offline,
        want_desktop: desktop,
        want_app_menu: app_menu,
        old_desktop: rec.desktop_path.as_deref(),
        old_app_menu: rec.app_menu_path.as_deref(),
    })?;

    if written.desktop.is_none() && written.app_menu.is_none() {
        return Err("Could not write the shortcut at the selected locations.".to_string());
    }

    records[pos] = ShortcutRecord {
        id: rec.id,
        instance_id: rec.instance_id,
        name: title,
        account_id: account.map(|a| a.to_string()),
        offline,
        desktop_path: written.desktop.map(|p| p.to_string_lossy().to_string()),
        app_menu_path: written.app_menu.map(|p| p.to_string_lossy().to_string()),
    };
    registry_save(&records);
    logger::info(&app, &state, SRC, &format!("Shortcut {id} updated"));
    Ok("Shortcut updated".to_string())
}

/// Deletes one shortcut (all of its files) by id.
#[tauri::command]
pub async fn delete_shortcut(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<usize, String> {
    logger::info(&app, &state, SRC, &format!("Deleting shortcut {id}"));
    let mut records = load_synced_registry(&app, &state);
    let Some(pos) = records.iter().position(|r| r.id == id) else {
        logger::warn(&app, &state, SRC, &format!("Delete: shortcut {id} not found"));
        return Err("That shortcut no longer exists.".to_string());
    };
    let rec = records.remove(pos);
    let mut removed = 0usize;
    for p in [rec.desktop_path.as_deref(), rec.app_menu_path.as_deref()].into_iter().flatten() {
        if remove_file_logged(&app, &state, p) {
            removed += 1;
        }
    }
    registry_save(&records);
    #[cfg(target_os = "linux")]
    refresh_desktop_database();
    logger::info(&app, &state, SRC, &format!("Shortcut {id} deleted ({removed} file(s))"));
    Ok(removed)
}

/// Deletes every shortcut that launches `instance_id` (used by the
/// "instance not found" prompt). Returns how many files were removed.
#[tauri::command]
pub async fn delete_instance_shortcuts(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
) -> Result<usize, String> {
    logger::info(&app, &state, SRC, &format!("Deleting shortcuts for missing/removed instance '{instance_id}'"));

    let mut records = load_synced_registry(&app, &state);
    let mut removed = 0usize;
    records.retain(|r| {
        if r.instance_id != instance_id {
            return true;
        }
        for p in [r.desktop_path.as_deref(), r.app_menu_path.as_deref()].into_iter().flatten() {
            if remove_file_logged(&app, &state, p) {
                removed += 1;
            }
        }
        false
    });
    registry_save(&records);

    // Icons generated for this shortcut are no longer needed either.
    let key = safe_instance_key(&instance_id);
    let icons_dir = crate::first_run_setup::install_dir().join("icons").join("instances");
    for ext in ["png", "ico"] {
        let _ = fs::remove_file(icons_dir.join(format!("{key}.{ext}")));
    }

    #[cfg(target_os = "linux")]
    {
        if removed > 0 {
            refresh_desktop_database();
        }
    }

    logger::info(&app, &state, SRC, &format!("Shortcut cleanup for '{instance_id}' finished: {removed} file(s) removed"));
    Ok(removed)
}
