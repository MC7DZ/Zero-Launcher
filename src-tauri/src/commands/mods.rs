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

/// Toggle a mod between enabled (.jar) and disabled (.jar.disabled).
#[tauri::command]
pub async fn toggle_mod(path: String) -> Result<ModInfo, String> {
    let src = PathBuf::from(&path);
    if !src.exists() {
        return Err("Mod file not found".to_string());
    }

    let file_name = src.file_name().unwrap_or_default().to_string_lossy().to_string();

    let (dst, enabled) = if file_name.ends_with(".jar.disabled") {
        // Enable: remove .disabled suffix
        let new_name = file_name.trim_end_matches(".disabled");
        (src.with_file_name(new_name), true)
    } else if file_name.ends_with(".jar") {
        // Disable: add .disabled suffix
        (src.with_file_name(format!("{file_name}.disabled")), false)
    } else {
        return Err("Not a valid mod file".to_string());
    };

    std::fs::rename(&src, &dst)
        .map_err(|e| format!("Failed to toggle mod: {e}"))?;

    let new_name = dst.file_name().unwrap_or_default().to_string_lossy().to_string();
    Ok(read_mod_metadata(&dst, &new_name, enabled))
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

/// Open the mods folder in the system file manager.
#[tauri::command]
pub async fn open_mods_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<(), String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let mods_dir = game_dir.join("mods");
    std::fs::create_dir_all(&mods_dir)
        .map_err(|e| format!("Failed to create mods directory: {e}"))?;

    open::that(&mods_dir).map_err(|e| format!("Failed to open folder: {e}"))?;
    Ok(())
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
    let loader_id = extract_toml_value(&contents, "loaderVersion").unwrap_or_default();

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
