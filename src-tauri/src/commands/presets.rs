use std::path::{Path, PathBuf};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};

use crate::commands::discover::modrinth_get_download_url;
use crate::state::AppState;

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

#[derive(Debug, Deserialize)]
struct GithubRepoInfo {
    default_branch: String,
}

#[derive(Debug, Deserialize)]
struct GithubTreeResponse {
    tree: Vec<GithubTreeEntry>,
    truncated: bool,
}

#[derive(Debug, Deserialize)]
struct GithubTreeEntry {
    path: String,
    #[serde(rename = "type")]
    entry_type: String,
}

const GITHUB_OWNER: &str = "MC7DZ";
const GITHUB_REPO: &str = "ZeroLauncher-Updates";
const PRESETS_PREFIX: &str = "presets/";

/// Old approach made one GitHub Contents-API call *per subfolder* — mod
/// configs nest deeply (config/spark/tmp-client, config/.puzzle_cache,
/// etc., easily 15-20+ folders per preset), so a single sync could burn
/// through unauthenticated GitHub's 60-requests/hour limit partway
/// through and silently leave presets half-downloaded or missing with no
/// error surfaced anywhere. The Git Trees API returns the *entire* repo
/// file listing in one shot (`recursive=1`), so the whole sync now costs
/// exactly 2 API calls (resolve default branch, fetch tree) no matter how
/// deep the folders go — file contents themselves are then pulled from
/// raw.githubusercontent.com, which isn't subject to that same limit.
async fn fetch_github_preset_tree(client: &reqwest::Client) -> Result<(String, Vec<String>), String> {
    let repo_url = format!("https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}");
    let repo_resp = client
        .get(&repo_url)
        .header("User-Agent", "ZeroLauncher/2.1.0 (https://github.com/MC7DZ/ZeroLauncher)")
        .send()
        .await
        .map_err(|e| format!("GitHub repo request failed: {e}"))?;
    if !repo_resp.status().is_success() {
        return Err(format!("GitHub API HTTP {} while resolving default branch", repo_resp.status()));
    }
    let repo_info: GithubRepoInfo = repo_resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse GitHub repo info: {e}"))?;

    let tree_url = format!(
        "https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}/git/trees/{}?recursive=1",
        repo_info.default_branch
    );
    let tree_resp = client
        .get(&tree_url)
        .header("User-Agent", "ZeroLauncher/2.1.0 (https://github.com/MC7DZ/ZeroLauncher)")
        .send()
        .await
        .map_err(|e| format!("GitHub tree request failed: {e}"))?;
    if !tree_resp.status().is_success() {
        return Err(format!("GitHub API HTTP {} while fetching repo tree", tree_resp.status()));
    }
    let tree: GithubTreeResponse = tree_resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse GitHub tree: {e}"))?;

    if tree.truncated {
        // Repo has grown too large for a single non-truncated tree response.
        // Not expected at current preset repo size, but fail loudly rather
        // than silently syncing a partial preset set if it ever happens.
        return Err("GitHub repo tree response was truncated — too many files for one request".to_string());
    }

    Ok((
        repo_info.default_branch,
        tree.tree
            .into_iter()
            .filter(|e| e.entry_type == "blob" && e.path.starts_with(PRESETS_PREFIX))
            .map(|e| e.path)
            .collect(),
    ))
}

async fn download_github_file(
    client: &reqwest::Client,
    branch: &str,
    repo_path: &str,
    dest: &Path,
) -> Result<(), String> {
    // Skip re-downloading if file already exists and is not empty.
    if dest.is_file() && dest.metadata().map(|m| m.len() > 0).unwrap_or(false) {
        return Ok(());
    }
    if let Some(parent) = dest.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let raw_url = format!(
        "https://raw.githubusercontent.com/{GITHUB_OWNER}/{GITHUB_REPO}/{branch}/{repo_path}"
    );
    let resp = client
        .get(&raw_url)
        .header("User-Agent", "ZeroLauncher/2.1.0")
        .send()
        .await
        .map_err(|e| format!("Failed to fetch {repo_path}: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {} for {repo_path}", resp.status()));
    }
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read body for {repo_path}: {e}"))?;
    std::fs::write(dest, &bytes).map_err(|e| format!("Failed to write {}: {e}", dest.display()))?;
    Ok(())
}

pub async fn sync_presets_from_github_internal(data_dir: PathBuf, app: Option<AppHandle>) -> Result<usize, String> {
    let presets_dir = data_dir.join("presets");
    std::fs::create_dir_all(&presets_dir).map_err(|e| format!("Failed to create presets dir: {e}"))?;

    let client = reqwest::Client::builder()
        // See vendor/mc-launcher-core's http.rs client() for why: forces
        // IPv4 so a broken/non-routable IPv6 setup can't stall requests.
        .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
        .build()
        .map_err(|e| e.to_string())?;

    // 2 API calls total (resolve default branch + fetch the whole repo
    // tree), regardless of how many presets or nested config folders
    // exist — see fetch_github_preset_tree for why this replaced the old
    // one-API-call-per-subfolder walk.
    let (branch, paths) = fetch_github_preset_tree(&client).await?;

    // Group every file path by its immediate preset folder name
    // (presets/<Name>/...) so we can report progress per-preset like
    // before, and know which folders exist upstream at all.
    let mut by_preset: std::collections::BTreeMap<String, Vec<String>> = std::collections::BTreeMap::new();
    for path in &paths {
        let rest = &path[PRESETS_PREFIX.len()..];
        if let Some(slash) = rest.find('/') {
            let preset_name = &rest[..slash];
            by_preset.entry(preset_name.to_string()).or_default().push(path.clone());
        }
    }

    let total = by_preset.len();
    if let Some(ref a) = app {
        use tauri::Emitter;
        #[derive(serde::Serialize, Clone)]
        struct SyncStartPayload { total: usize }
        let _ = a.emit("preset-sync-start", SyncStartPayload { total });
    }

    let mut count = 0;
    for (preset_name, files) in by_preset {
        let target_folder = presets_dir.join(&preset_name);
        let _ = std::fs::create_dir_all(&target_folder);

        // Download every file in this preset. One file failing (e.g. a
        // transient network hiccup) no longer silently drops the whole
        // preset the way an early Err from the old recursive walk did —
        // each file is independent, so a single bad file just leaves that
        // one file missing instead of the entire preset.
        let mut ok_count = 0usize;
        for repo_path in &files {
            let rel = &repo_path[PRESETS_PREFIX.len() + preset_name.len() + 1..];
            let dest = target_folder.join(rel);
            match download_github_file(&client, &branch, repo_path, &dest).await {
                Ok(()) => ok_count += 1,
                Err(e) => {
                    eprintln!("Preset sync: failed to download {repo_path}: {e}");
                }
            }
        }

        if ok_count > 0 {
            count += 1;
            if let Some(ref a) = app {
                use tauri::Emitter;
                #[derive(serde::Serialize, Clone)]
                struct SyncedPayload { name: String, done: usize, total: usize }
                let _ = a.emit("preset-synced", SyncedPayload {
                    name: preset_name.clone(),
                    done: count,
                    total,
                });
            }
        }
    }

    // Emit a final "done" event
    if let Some(ref a) = app {
        use tauri::Emitter;
        #[derive(serde::Serialize, Clone)]
        struct SyncDonePayload { done: usize, total: usize }
        let _ = a.emit("preset-sync-done", SyncDonePayload { done: count, total });
    }

    Ok(count)
}

/// Explicit command to trigger sync of presets from GitHub repository.
#[tauri::command]
pub async fn sync_presets(state: State<'_, AppState>, app: AppHandle) -> Result<usize, String> {
    sync_presets_from_github_internal(state.data_dir.clone(), Some(app)).await
}

/// Resolve the `presets/` root directory — prioritizing `Zero Launcher/presets/` (downloaded from GitHub),
/// falling back to bundled resources or dev tree if user folder is empty.
fn presets_root(state: &AppState, app: &AppHandle) -> PathBuf {
    let user_presets = state.data_dir.join("presets");
    if user_presets.is_dir() {
        if let Ok(rd) = std::fs::read_dir(&user_presets) {
            if rd.flatten().any(|e| e.path().is_dir()) {
                return user_presets;
            }
        }
    }

    if let Ok(resource_path) = app.path().resolve("presets", tauri::path::BaseDirectory::Resource) {
        if resource_path.exists() {
            return resource_path;
        }
    }

    let dev_path = Path::new(env!("CARGO_MANIFEST_DIR")).join("presets");
    if dev_path.exists() {
        return dev_path;
    }

    user_presets
}

fn folder_for_id<'a>(root: &Path, id: &str) -> PathBuf {
    root.join(id)
}

/// Helper function to scan presets folder and parse preset JSONs
fn read_local_presets(state: &AppState, app: &AppHandle) -> Vec<PresetInfo> {
    let root = presets_root(state, app);
    let mut out = Vec::new();
    let entries = match std::fs::read_dir(&root) {
        Ok(e) => e,
        Err(_) => return Vec::new(),
    };

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

    out
}

/// Scan local `presets/` for preset folders and return them instantly.
/// Does NOT trigger any GitHub sync — call sync_presets separately for that.
#[tauri::command]
pub async fn list_presets(state: State<'_, AppState>, app: AppHandle) -> Result<Vec<PresetInfo>, String> {
    Ok(read_local_presets(&state, &app))
}

/// Same as list_presets. Kept for backward-compat with JS callers in event handlers.
#[tauri::command]
pub async fn get_local_presets(state: State<'_, AppState>, app: AppHandle) -> Result<Vec<PresetInfo>, String> {
    Ok(read_local_presets(&state, &app))
}

/// Return the absolute path to a preset's `icon.png` (frontend loads it via
/// the `asset://` protocol / `convertFileSrc`), or an error if it has none.
#[tauri::command]
pub async fn get_preset_icon_path(
    state: State<'_, AppState>,
    app: AppHandle,
    preset_id: String,
) -> Result<String, String> {
    let root = presets_root(&state, &app);
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

/// Resolve the actual download URL + filename for one mod inside a preset, by its Modrinth project id.
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
/// already present in the target instance's `mods/` folder.
#[tauri::command]
pub async fn get_preset_installed_mods(
    state: State<'_, AppState>,
    app: AppHandle,
    preset_id: String,
    directory: String,
) -> Result<Vec<String>, String> {
    let root = presets_root(&state, &app);
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

/// Copies a preset's bundled `config/` folder into `<directory>/config`, if it has one.
#[tauri::command]
pub async fn apply_preset_config(
    state: State<'_, AppState>,
    app: AppHandle,
    preset_id: String,
    directory: String,
) -> Result<bool, String> {
    let root = presets_root(&state, &app);
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
