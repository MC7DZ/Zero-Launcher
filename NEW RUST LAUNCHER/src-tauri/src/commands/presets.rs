use std::path::{Path, PathBuf};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::commands::discover::{modrinth_get_download_url};

/// One mod entry inside a bundled preset's JSON.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PresetMod {
    pub name: String,
    #[serde(default, alias = "fileName")]
    pub file_name: Option<String>,
    #[serde(default, alias = "modrinthId")]
    pub modrinth_id: Option<String>,
}

/// A bundled preset, resolved from `<preset folder>/*.json` (+ optional
/// `icon.png` and `config/` folder sitting next to it). Mirrors the Java
/// client's `BundledPreset` / preset export format 1:1 so presets exported
/// by that client (or bundled here) both work unmodified.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PresetInfo {
    /// Folder name under `presets/` — used to re-identify this preset in
    /// later calls (apply_preset, get_preset_icon).
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub preset_type: String,
    pub description: String,
    pub mod_loaders: Vec<String>,
    pub mods: Vec<PresetMod>,
    pub has_config: bool,
    pub has_icon: bool,
}

#[derive(Debug, Deserialize)]
struct RawPresetJson {
    #[serde(default)]
    #[serde(rename = "presetName")]
    preset_name: Option<String>,
    #[serde(default)]
    #[serde(rename = "presetType")]
    preset_type: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    #[serde(rename = "modLoaders")]
    mod_loaders: Vec<String>,
    #[serde(default)]
    mods: Vec<PresetMod>,
}

/// Resolve the `presets/` root directory — bundled as a resource in release
/// builds, read straight from the source tree in dev. Same pattern as
/// `resolve_background_path` in logs.rs.
fn presets_root(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(resource_path) = app
        .path()
        .resolve("presets", tauri::path::BaseDirectory::Resource)
    {
        if resource_path.exists() {
            return Some(resource_path);
        }
    }

    let dev_path = Path::new(env!("CARGO_MANIFEST_DIR")).join("presets");
    if dev_path.exists() {
        return Some(dev_path);
    }

    None
}

fn folder_for_id<'a>(root: &Path, id: &str) -> PathBuf {
    root.join(id)
}

/// Scan `presets/` for bundled preset folders, each expected to contain
/// exactly one `*.json` preset file and optionally an `icon.png` and a
/// `config/` subfolder.
#[tauri::command]
pub async fn list_presets(app: AppHandle) -> Result<Vec<PresetInfo>, String> {
    let Some(root) = presets_root(&app) else {
        return Ok(Vec::new());
    };

    let mut out = Vec::new();
    let entries = std::fs::read_dir(&root).map_err(|e| format!("Failed to read presets dir: {e}"))?;

    let mut folders: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    folders.sort();

    for folder in folders {
        let json_file = match std::fs::read_dir(&folder) {
            Ok(rd) => rd
                .flatten()
                .map(|e| e.path())
                .find(|p| p.extension().map(|e| e == "json").unwrap_or(false)),
            Err(_) => None,
        };
        let Some(json_file) = json_file else { continue };

        let text = match std::fs::read_to_string(&json_file) {
            Ok(t) => t,
            Err(_) => continue,
        };
        let raw: RawPresetJson = match serde_json::from_str(&text) {
            Ok(r) => r,
            Err(_) => continue,
        };

        let folder_name = folder
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();

        out.push(PresetInfo {
            id: folder_name.clone(),
            name: raw.preset_name.unwrap_or_else(|| folder_name.clone()),
            preset_type: raw.preset_type.unwrap_or_default(),
            description: raw.description.unwrap_or_default(),
            mod_loaders: raw.mod_loaders,
            mods: raw.mods,
            has_config: folder.join("config").is_dir(),
            has_icon: folder.join("icon.png").is_file(),
        });
    }

    Ok(out)
}

/// Return the absolute path to a preset's `icon.png` (frontend loads it via
/// the `asset://` protocol / `convertFileSrc`), or an error if it has none.
#[tauri::command]
pub async fn get_preset_icon_path(app: AppHandle, preset_id: String) -> Result<String, String> {
    let root = presets_root(&app).ok_or_else(|| "Presets not found".to_string())?;
    let icon = folder_for_id(&root, &preset_id).join("icon.png");
    if icon.is_file() {
        Ok(icon.to_string_lossy().to_string())
    } else {
        Err("No icon for this preset".to_string())
    }
}

fn read_preset_json(root: &Path, preset_id: &str) -> Result<(PathBuf, RawPresetJson), String> {
    let folder = folder_for_id(root, preset_id);
    if !folder.is_dir() {
        return Err(format!("Preset \"{preset_id}\" not found"));
    }
    let json_file = std::fs::read_dir(&folder)
        .map_err(|e| format!("Failed to read preset folder: {e}"))?
        .flatten()
        .map(|e| e.path())
        .find(|p| p.extension().map(|e| e == "json").unwrap_or(false))
        .ok_or_else(|| "Preset JSON not found".to_string())?;
    let raw: RawPresetJson = serde_json::from_str(
        &std::fs::read_to_string(&json_file).map_err(|e| format!("Failed to read preset: {e}"))?,
    )
    .map_err(|e| format!("Failed to parse preset: {e}"))?;
    Ok((folder, raw))
}

#[derive(Debug, Clone, Serialize)]
pub struct PresetModUrl {
    pub url: String,
    pub file_name: String,
}

/// Resolve the actual download URL + filename for one mod inside a bundled
/// preset, by its Modrinth project id. The frontend streams the file down
/// itself afterwards (via `discover_download`, reusing its per-chunk
/// cancellation and the shared downloads widget) instead of the backend
/// downloading everything in one un-cancellable blocking call, so applying a
/// preset now shows real per-mod progress and cancels immediately.
#[tauri::command]
pub async fn resolve_preset_mod_url(
    modrinth_id: String,
    loader: Option<String>,
    mc_version: Option<String>,
) -> Result<Option<PresetModUrl>, String> {
    let loader_lower = loader.as_deref().map(|l| l.to_lowercase());
    match modrinth_get_download_url(&modrinth_id, loader_lower.as_deref(), mc_version.as_deref()).await? {
        Some((url, file_name)) => Ok(Some(PresetModUrl { url, file_name })),
        None => Ok(None),
    }
}

/// Which of a preset's mods (matched by their expected file name) are
/// already present in the target instance's `mods/` folder, so the frontend
/// can mark them "already installed" and skip downloading them.
#[tauri::command]
pub async fn get_preset_installed_mods(
    app: AppHandle,
    preset_id: String,
    directory: String,
) -> Result<Vec<String>, String> {
    let root = presets_root(&app).ok_or_else(|| "Presets not found".to_string())?;
    let (_, raw) = read_preset_json(&root, &preset_id)?;

    let mods_dir = PathBuf::from(&directory).join("mods");
    let installed: std::collections::HashSet<String> = std::fs::read_dir(&mods_dir)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.file_name().to_string_lossy().to_lowercase())
                .collect()
        })
        .unwrap_or_default();

    Ok(raw
        .mods
        .iter()
        .filter(|m| {
            m.file_name
                .as_ref()
                .map(|f| installed.contains(&f.to_lowercase()))
                .unwrap_or(false)
        })
        .map(|m| m.name.clone())
        .collect())
}

/// Copies a preset's bundled `config/` folder into `<directory>/config`, if
/// it has one. Split out from mod downloading (now done by the frontend
/// itself, see `resolve_preset_mod_url`) since this part is fast and local
/// and doesn't need progress/cancellation.
#[tauri::command]
pub async fn apply_preset_config(
    app: AppHandle,
    preset_id: String,
    directory: String,
) -> Result<bool, String> {
    let root = presets_root(&app).ok_or_else(|| "Presets not found".to_string())?;
    let folder = folder_for_id(&root, &preset_id);
    if !folder.is_dir() {
        return Err(format!("Preset \"{preset_id}\" not found"));
    }
    let config_src = folder.join("config");
    if !config_src.is_dir() {
        return Ok(false);
    }
    copy_dir_recursive(&config_src, &PathBuf::from(&directory).join("config"))?;
    Ok(true)
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    for entry in std::fs::read_dir(src).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let ty = entry.file_type().map_err(|e| e.to_string())?;
        let dst_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dst_path)?;
        } else {
            std::fs::copy(entry.path(), &dst_path).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}
