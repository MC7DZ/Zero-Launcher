use std::io::Read;
use std::path::PathBuf;
use serde::Serialize;
use tauri::State;
use crate::models::ModInfo;
use crate::state::AppState;

/// List all mods in the mods directory for a given game directory.
#[tauri::command]
pub async fn list_mods(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<Vec<ModInfo>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());
    Ok(list_mods_in_dir(&game_dir))
}

/// Synchronous helper shared with `crash_analysis` (which runs off the
/// background task that waits on the game process, not a tauri command).
pub fn list_mods_in_dir(game_dir: &PathBuf) -> Vec<ModInfo> {
    let mods_dir = game_dir.join("mods");
    if !mods_dir.exists() {
        return Vec::new();
    }

    let entries = match std::fs::read_dir(&mods_dir) {
        Ok(e) => e,
        Err(_) => return Vec::new(),
    };

    let mut mods = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();
        let file_name = path.file_name().unwrap_or_default().to_string_lossy().to_string();

        // Only process .jar and .jar.disabled files
        let is_jar = file_name.ends_with(".jar");
        let is_disabled = file_name.ends_with(".jar.disabled");
        if !is_jar && !is_disabled {
            continue;
        }

        let enabled = !is_disabled;
        let mod_info = read_mod_metadata(&path, &file_name, enabled);
        mods.push(mod_info);
    }

    mods.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    mods
}

/// Toggle a mod (or resource/shader pack) between enabled and disabled by
/// adding/removing a trailing `.disabled` suffix. Generic over file
/// extension so it works for `.jar` mods as well as `.zip` resource packs
/// and shader packs.
#[tauri::command]
pub async fn toggle_mod(path: String) -> Result<ModInfo, String> {
    let src = PathBuf::from(&path);
    if !src.exists() {
        return Err("File not found".to_string());
    }

    let file_name = src.file_name().unwrap_or_default().to_string_lossy().to_string();

    let (dst, enabled) = if let Some(base) = file_name.strip_suffix(".disabled") {
        // Enable: remove .disabled suffix
        (src.with_file_name(base), true)
    } else {
        // Disable: add .disabled suffix
        (src.with_file_name(format!("{file_name}.disabled")), false)
    };

    std::fs::rename(&src, &dst)
        .map_err(|e| format!("Failed to toggle: {e}"))?;

    let new_name = dst.file_name().unwrap_or_default().to_string_lossy().to_string();
    Ok(read_mod_metadata(&dst, &new_name, enabled))
}

// ── Resource Packs & Shader Packs ───────────────────────────────────────────
// Resource packs and shader packs are managed the same way mods are (list,
// enable/disable via renaming, delete, open folder, drag-and-drop install,
// hash-based update checks against Modrinth) — these just point at a
// different subfolder/extension and use a different metadata reader.

/// Maps a content "kind" string from the frontend to its folder name inside
/// an instance directory.
fn pack_folder_name(kind: &str) -> &'static str {
    match kind {
        "shaderpack" => "shaderpacks",
        _ => "resourcepacks",
    }
}

/// List all resource packs or shader packs in the given instance directory.
/// `kind` is `"resourcepack"` or `"shaderpack"`.
#[tauri::command]
pub async fn list_packs(
    state: State<'_, AppState>,
    directory: Option<String>,
    kind: String,
) -> Result<Vec<ModInfo>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());
    Ok(list_packs_in_dir(&game_dir, &kind))
}

fn list_packs_in_dir(game_dir: &PathBuf, kind: &str) -> Vec<ModInfo> {
    let packs_dir = game_dir.join(pack_folder_name(kind));
    if !packs_dir.exists() {
        return Vec::new();
    }

    let entries = match std::fs::read_dir(&packs_dir) {
        Ok(e) => e,
        Err(_) => return Vec::new(),
    };

    let mut packs = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();
        let file_name = path.file_name().unwrap_or_default().to_string_lossy().to_string();

        let is_zip = file_name.to_lowercase().ends_with(".zip");
        let is_disabled = file_name.to_lowercase().ends_with(".zip.disabled");
        if !is_zip && !is_disabled {
            continue;
        }

        let enabled = !is_disabled;
        packs.push(read_pack_metadata(&path, &file_name, enabled, kind));
    }

    packs.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    packs
}

/// Open the resource packs or shader packs folder in the system file manager.
#[tauri::command]
pub async fn open_packs_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
    kind: String,
) -> Result<(), String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let packs_dir = game_dir.join(pack_folder_name(&kind));
    crate::commands::open_folder_in_file_manager(&packs_dir)
}

/// Validate and copy one or more dropped/browsed `.zip` files into an
/// instance's `resourcepacks`/`shaderpacks` folder. Resource packs are
/// required to actually contain a `pack.mcmeta` so a random zip isn't
/// silently accepted; shader packs have no single universal manifest across
/// OptiFine/Iris shader authors, so any zip is accepted there.
#[tauri::command]
pub async fn install_pack_files(
    state: State<'_, AppState>,
    paths: Vec<String>,
    directory: Option<String>,
    kind: String,
) -> Result<Vec<ModInstallResult>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let packs_dir = game_dir.join(pack_folder_name(&kind));
    std::fs::create_dir_all(&packs_dir)
        .map_err(|e| format!("Failed to create directory: {e}"))?;

    let mut results = Vec::with_capacity(paths.len());

    for src_path_str in paths {
        let src = PathBuf::from(&src_path_str);
        let source_name = src
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| src_path_str.clone());

        if !source_name.to_lowercase().ends_with(".zip") {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some("Not a .zip file".to_string()),
                mod_info: None,
            });
            continue;
        }

        if !src.is_file() {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some("File not found".to_string()),
                mod_info: None,
            });
            continue;
        }

        if kind == "resourcepack" {
            if let Err(reason) = validate_resourcepack_zip(&src) {
                results.push(ModInstallResult { source_name, success: false, reason: Some(reason), mod_info: None });
                continue;
            }
        } else if let Err(reason) = validate_generic_zip(&src) {
            results.push(ModInstallResult { source_name, success: false, reason: Some(reason), mod_info: None });
            continue;
        }

        let dest = unique_destination_ext(&packs_dir, &source_name, ".zip");
        if let Err(e) = std::fs::copy(&src, &dest) {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some(format!("Failed to copy file: {e}")),
                mod_info: None,
            });
            continue;
        }

        let dest_name = dest.file_name().unwrap_or_default().to_string_lossy().to_string();
        let pack_info = read_pack_metadata(&dest, &dest_name, true, &kind);
        results.push(ModInstallResult { source_name, success: true, reason: None, mod_info: Some(pack_info) });
    }

    Ok(results)
}

fn validate_resourcepack_zip(path: &PathBuf) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Couldn't read file: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| "Not a valid zip file".to_string())?;
    if archive.by_name("pack.mcmeta").is_ok() {
        Ok(())
    } else {
        Err("No pack.mcmeta found — this doesn't look like a resource pack".to_string())
    }
}

fn validate_generic_zip(path: &PathBuf) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Couldn't read file: {e}"))?;
    zip::ZipArchive::new(file).map_err(|_| "Not a valid zip file".to_string())?;
    Ok(())
}

/// Same collision-avoidance as `unique_destination`, but for an arbitrary
/// extension instead of assuming `.jar`.
fn unique_destination_ext(dir: &std::path::Path, name: &str, ext: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let stem = name.strip_suffix(ext).unwrap_or(name);
    for n in 2..1000 {
        let alt = dir.join(format!("{stem} ({n}){ext}"));
        if !alt.exists() {
            return alt;
        }
    }
    dir.join(format!("{stem}-{}{ext}", std::process::id()))
}

/// Read metadata for a resource pack or shader pack zip. Resource packs
/// carry a standard `pack.mcmeta` (description + pack_format); shader packs
/// have no equivalent standard, so they fall back straight to the filename.
/// Both still get a SHA-1 hash computed so the same Modrinth
/// hash-identification flow used for mod icons/updates works for packs too.
fn read_pack_metadata(path: &PathBuf, file_name: &str, enabled: bool, kind: &str) -> ModInfo {
    let sha1 = compute_sha1(path);
    let clean_name = file_name
        .trim_end_matches(".zip.disabled")
        .trim_end_matches(".zip")
        .replace('-', " ")
        .replace('_', " ");

    let mut name = clean_name.clone();
    let mut version = String::new();
    let mut description = String::new();

    if kind == "resourcepack" {
        if let Ok(file) = std::fs::File::open(path) {
            if let Ok(mut archive) = zip::ZipArchive::new(file) {
                if let Ok(mut entry) = archive.by_name("pack.mcmeta") {
                    let mut contents = String::new();
                    if entry.read_to_string(&mut contents).is_ok() {
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&contents) {
                            let pack = &json["pack"];
                            if let Some(desc) = pack["description"].as_str() {
                                description = desc.to_string();
                            }
                            if let Some(fmt) = pack["pack_format"].as_i64() {
                                version = format!("Format {fmt}");
                            }
                        }
                    }
                }
            }
        }
    }

    if name.trim().is_empty() {
        name = clean_name;
    }

    ModInfo {
        file_name: file_name.to_string(),
        name,
        version,
        description,
        loader: String::new(),
        enabled,
        path: path.to_string_lossy().to_string(),
        sha1,
    }
}

/// Delete a mod file.
#[tauri::command]
pub async fn delete_mod(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    if p.exists() {
        std::fs::remove_file(&p).map_err(|e| format!("Failed to delete mod: {e}"))?;
    }
    Ok(())
}

// ── Screenshots ──────────────────────────────────────────────────────────
// Screenshots are just files in `screenshots/` inside an instance's
// directory — no metadata to parse, so this is a much thinner slice than
// mods/packs: list with basic file info (for sorting newest-first) and
// reuse `delete_mod`/`open_folder_in_file_manager` for delete/open since
// both are already fully generic over path.

#[derive(Serialize)]
pub struct ScreenshotInfo {
    pub file_name: String,
    pub path: String,
    /// Milliseconds since the Unix epoch, used to sort newest-first.
    pub modified_ms: u64,
    pub size_bytes: u64,
}

#[tauri::command]
pub async fn list_screenshots(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<Vec<ScreenshotInfo>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());
    let dir = game_dir.join("screenshots");
    if !dir.exists() {
        return Ok(Vec::new());
    }

    let entries = std::fs::read_dir(&dir).map_err(|e| format!("Failed to read screenshots folder: {e}"))?;
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let file_name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
        let lower = file_name.to_lowercase();
        if !(lower.ends_with(".png") || lower.ends_with(".jpg") || lower.ends_with(".jpeg")) {
            continue;
        }
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let modified_ms = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        out.push(ScreenshotInfo {
            file_name,
            path: path.to_string_lossy().to_string(),
            modified_ms,
            size_bytes: meta.len(),
        });
    }

    // Newest first, matching how the vanilla client's screenshots folder is
    // usually browsed.
    out.sort_by(|a, b| b.modified_ms.cmp(&a.modified_ms));
    Ok(out)
}

/// Open the screenshots folder in the system file manager.
#[tauri::command]
pub async fn open_screenshots_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<(), String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let dir = game_dir.join("screenshots");
    crate::commands::open_folder_in_file_manager(&dir)
}

/// Read a screenshot's raw bytes and hand them back as a base64 data URL.
///
/// The grid used to point an `<img>` straight at the file via
/// `convertFileSrc`/the `asset://` protocol, the same way mod icons and
/// cached skins do. The difference is that those are always read from a
/// path *we* created inside the app's own data dir (ASCII, no spaces,
/// no surprises), while a screenshot's path is whatever the user's actual
/// instance directory happens to be — which can contain spaces, unicode
/// (non-English Windows usernames are common), or live on a drive/mount
/// the asset protocol's scope doesn't resolve as cleanly. That combination
/// is what showed up as "the card is there but the picture never loads".
/// Reading the bytes ourselves and building a `data:` URL sidesteps the
/// asset protocol entirely, so it doesn't matter what the path looks like.
#[tauri::command]
pub async fn read_screenshot_image(path: String) -> Result<String, String> {
    let file_path = PathBuf::from(&path);
    let bytes = tokio::task::spawn_blocking(move || std::fs::read(&file_path))
        .await
        .map_err(|e| format!("Failed to read screenshot: {e}"))?
        .map_err(|e| format!("Failed to read screenshot: {e}"))?;

    let mime = match path.rsplit('.').next().map(|e| e.to_ascii_lowercase()) {
        Some(ext) if ext == "jpg" || ext == "jpeg" => "image/jpeg",
        _ => "image/png",
    };

    use base64::Engine;
    let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
    Ok(format!("data:{mime};base64,{encoded}"))
}

/// Open the mods folder in the system file manager.
#[tauri::command]
pub async fn open_mods_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<(), String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let mods_dir = game_dir.join("mods");
    crate::commands::open_folder_in_file_manager(&mods_dir)
}

/// Result of trying to install a single dropped/browsed file as a mod.
#[derive(Serialize)]
pub struct ModInstallResult {
    /// Original file name of the dropped file, for matching back up to the
    /// drag-drop payload on the frontend.
    pub source_name: String,
    pub success: bool,
    /// Human-readable reason, only set when `success` is false.
    pub reason: Option<String>,
    /// The installed mod's metadata, only set when `success` is true.
    pub mod_info: Option<ModInfo>,
}

/// Validate and copy one or more dropped/browsed `.jar` files into an
/// instance's mods folder. Each file is opened as a zip and checked for a
/// recognized loader manifest (`fabric.mod.json`, `quilt.mod.json`, or
/// `META-INF/mods.toml`) before it's accepted — anything else (a random
/// jar, a non-jar file, a corrupt zip) is rejected without touching disk.
/// Name collisions get a numeric suffix rather than overwriting.
#[tauri::command]
pub async fn install_mod_files(
    state: State<'_, AppState>,
    paths: Vec<String>,
    directory: Option<String>,
) -> Result<Vec<ModInstallResult>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let mods_dir = game_dir.join("mods");
    std::fs::create_dir_all(&mods_dir)
        .map_err(|e| format!("Failed to create mods directory: {e}"))?;

    let mut results = Vec::with_capacity(paths.len());

    for src_path_str in paths {
        let src = PathBuf::from(&src_path_str);
        let source_name = src
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| src_path_str.clone());

        if !source_name.to_lowercase().ends_with(".jar") {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some("Not a .jar file".to_string()),
                mod_info: None,
            });
            continue;
        }

        if !src.is_file() {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some("File not found".to_string()),
                mod_info: None,
            });
            continue;
        }

        match validate_mod_jar(&src) {
            Ok(()) => {}
            Err(reason) => {
                results.push(ModInstallResult { source_name, success: false, reason: Some(reason), mod_info: None });
                continue;
            }
        }

        let dest = unique_destination(&mods_dir, &source_name);
        if let Err(e) = std::fs::copy(&src, &dest) {
            results.push(ModInstallResult {
                source_name,
                success: false,
                reason: Some(format!("Failed to copy file: {e}")),
                mod_info: None,
            });
            continue;
        }

        let dest_name = dest.file_name().unwrap_or_default().to_string_lossy().to_string();
        let mod_info = read_mod_metadata(&dest, &dest_name, true);
        results.push(ModInstallResult { source_name, success: true, reason: None, mod_info: Some(mod_info) });
    }

    Ok(results)
}

/// Opens a jar as a zip archive and confirms it contains a manifest for a
/// loader we recognize. This is the "is it actually a mod" check — it
/// intentionally doesn't just trust the `.jar` extension.
fn validate_mod_jar(path: &PathBuf) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Couldn't read file: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| "Not a valid jar/zip file".to_string())?;

    let has_manifest = archive.by_name("fabric.mod.json").is_ok()
        || archive.by_name("quilt.mod.json").is_ok()
        || archive.by_name("META-INF/mods.toml").is_ok()
        // Older Forge (1.12 and earlier) uses mcmod.info instead of mods.toml.
        || archive.by_name("mcmod.info").is_ok();

    if has_manifest {
        Ok(())
    } else {
        Err("No Fabric, Quilt, or Forge mod manifest found inside the jar".to_string())
    }
}

/// If `mods_dir/name` already exists, append " (2)", " (3)", … before the
/// extension until a free name is found.
fn unique_destination(mods_dir: &std::path::Path, name: &str) -> PathBuf {
    let candidate = mods_dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let stem = name.strip_suffix(".jar").unwrap_or(name);
    for n in 2..1000 {
        let alt = mods_dir.join(format!("{stem} ({n}).jar"));
        if !alt.exists() {
            return alt;
        }
    }
    // Extremely unlikely fallback.
    mods_dir.join(format!("{stem}-{}.jar", std::process::id()))
}

/// Delete a folder or file inside an instance's directory, used by the crash
/// dialog's "regenerate cache" fixes (e.g. removing `.fabric` so Fabric
/// Loader rebuilds its remap cache). Scoped to only allow deleting paths
/// that live inside `game_dir` to avoid any chance of an unrelated path
/// being passed in.
#[tauri::command]
pub async fn delete_instance_subpath(game_dir: String, relative_path: String) -> Result<(), String> {
    let base = PathBuf::from(&game_dir);
    let canonical_base = std::fs::canonicalize(&base)
        .map_err(|e| format!("Instance directory not found: {e}"))?;
    let target = base.join(&relative_path);
    if !target.exists() {
        // Nothing to delete — already gone, treat as success.
        return Ok(());
    }
    let canonical_target = std::fs::canonicalize(&target)
        .map_err(|e| format!("Failed to resolve path: {e}"))?;
    if !canonical_target.starts_with(&canonical_base) {
        return Err("Refusing to delete a path outside the instance directory".to_string());
    }
    if canonical_target == canonical_base {
        return Err("Refusing to delete the instance directory itself".to_string());
    }
    if canonical_target.is_dir() {
        std::fs::remove_dir_all(&canonical_target)
            .map_err(|e| format!("Failed to delete \"{relative_path}\": {e}"))?;
    } else {
        std::fs::remove_file(&canonical_target)
            .map_err(|e| format!("Failed to delete \"{relative_path}\": {e}"))?;
    }
    Ok(())
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/// Try to read mod metadata from a JAR file.
/// Falls back to using the filename if metadata can't be parsed.
fn read_mod_metadata(path: &PathBuf, file_name: &str, enabled: bool) -> ModInfo {
    let sha1 = compute_sha1(path);

    // Try reading the JAR as a zip to extract mod metadata
    if let Ok(file) = std::fs::File::open(path) {
        if let Ok(mut archive) = zip::ZipArchive::new(file) {
            // Fabric: fabric.mod.json
            if let Some(info) = try_read_fabric_mod(&mut archive) {
                return ModInfo {
                    file_name: file_name.to_string(),
                    name: info.0,
                    version: info.1,
                    description: info.2,
                    loader: "Fabric".to_string(),
                    enabled,
                    path: path.to_string_lossy().to_string(),
                    sha1,
                };
            }
            // Quilt: quilt.mod.json
            if let Some(info) = try_read_quilt_mod(&mut archive) {
                return ModInfo {
                    file_name: file_name.to_string(),
                    name: info.0,
                    version: info.1,
                    description: info.2,
                    loader: "Quilt".to_string(),
                    enabled,
                    path: path.to_string_lossy().to_string(),
                    sha1,
                };
            }
            // Forge/NeoForge: META-INF/mods.toml
            if let Some(info) = try_read_forge_mod(&mut archive) {
                return ModInfo {
                    file_name: file_name.to_string(),
                    name: info.0,
                    version: info.1,
                    description: info.2,
                    loader: info.3,
                    enabled,
                    path: path.to_string_lossy().to_string(),
                    sha1,
                };
            }
        }
    }

    // Fallback: use filename
    let clean_name = file_name
        .trim_end_matches(".jar.disabled")
        .trim_end_matches(".jar")
        .replace('-', " ")
        .replace('_', " ");

    ModInfo {
        file_name: file_name.to_string(),
        name: clean_name,
        version: "Unknown".to_string(),
        description: String::new(),
        loader: "Unknown".to_string(),
        enabled,
        path: path.to_string_lossy().to_string(),
        sha1,
    }
}

/// SHA-1 of the jar's raw bytes, hex-encoded lowercase — same algorithm and
/// format Modrinth's `/version_files` endpoint expects, and what the Java
/// client's `computeSha1` produces. Returns `None` if the file can't be read
/// rather than failing the whole metadata read.
fn compute_sha1(path: &PathBuf) -> Option<String> {
    use sha1::{Digest, Sha1};
    let bytes = std::fs::read(path).ok()?;
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let digest = hasher.finalize();
    Some(digest.iter().map(|b| format!("{:02x}", b)).collect::<String>())
}

fn try_read_fabric_mod(archive: &mut zip::ZipArchive<std::fs::File>) -> Option<(String, String, String)> {
    let mut file = archive.by_name("fabric.mod.json").ok()?;
    let mut contents = String::new();
    file.read_to_string(&mut contents).ok()?;
    let json: serde_json::Value = serde_json::from_str(&contents).ok()?;
    Some((
        json["name"].as_str().unwrap_or("Unknown").to_string(),
        json["version"].as_str().unwrap_or("?").to_string(),
        json["description"].as_str().unwrap_or("").to_string(),
    ))
}

fn try_read_quilt_mod(archive: &mut zip::ZipArchive<std::fs::File>) -> Option<(String, String, String)> {
    let mut file = archive.by_name("quilt.mod.json").ok()?;
    let mut contents = String::new();
    file.read_to_string(&mut contents).ok()?;
    let json: serde_json::Value = serde_json::from_str(&contents).ok()?;
    let loader = &json["quilt_loader"];
    let metadata = &loader["metadata"];
    Some((
        metadata["name"].as_str()
            .or_else(|| loader["id"].as_str())
            .unwrap_or("Unknown")
            .to_string(),
        loader["version"].as_str().unwrap_or("?").to_string(),
        metadata["description"].as_str().unwrap_or("").to_string(),
    ))
}

fn try_read_forge_mod(archive: &mut zip::ZipArchive<std::fs::File>) -> Option<(String, String, String, String)> {
    let mut file = archive.by_name("META-INF/mods.toml").ok()?;
    let mut contents = String::new();
    file.read_to_string(&mut contents).ok()?;

    // Simple TOML parsing for mod metadata — just extract key values
    let name = extract_toml_value(&contents, "displayName").unwrap_or_else(|| "Unknown".to_string());
    let version = extract_toml_value(&contents, "version").unwrap_or_else(|| "?".to_string());
    let description = extract_toml_value(&contents, "description").unwrap_or_default();
    let _loader_id = extract_toml_value(&contents, "loaderVersion").unwrap_or_default();

    let loader = if contents.contains("neoforge") || contents.contains("NeoForge") {
        "NeoForge".to_string()
    } else {
        "Forge".to_string()
    };

    Some((name, version, description, loader))
}

/// Writes a mod list export (or any small text payload) to an
/// already-chosen path — the save location itself is picked on the
/// frontend via the native save dialog, same flow as the Java launcher's
/// `NativeFileChooser.saveFile` + `Files.writeString`.
#[tauri::command]
pub async fn export_mods_list(path: String, content: String) -> Result<(), String> {
    std::fs::write(&path, content).map_err(|e| format!("Failed to write file: {e}"))
}

/// Reads a mod list JSON file selected via the native open dialog, so the
/// frontend can parse it and drive the Import Mods overlay — mirrors the
/// Java launcher's `Files.readString` in the Import Mods button handler.
#[tauri::command]
pub async fn read_mods_list_file(path: String) -> Result<String, String> {
    std::fs::read_to_string(&path).map_err(|e| format!("Failed to read file: {e}"))
}

/// Simple extraction of a `key = "value"` pattern from TOML text.
fn extract_toml_value(toml: &str, key: &str) -> Option<String> {
    for line in toml.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix(key) {
            let rest = rest.trim();
            if let Some(rest) = rest.strip_prefix('=') {
                let rest = rest.trim();
                if let Some(rest) = rest.strip_prefix('"') {
                    if let Some(end) = rest.find('"') {
                        return Some(rest[..end].to_string());
                    }
                }
            }
        }
    }
    None
}
