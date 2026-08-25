//! Modpack import — drag/drop a `.mrpack` (Modrinth) or `.zip` (CurseForge
//! or a plain "mods+config+resourcepacks" export) onto the launcher and get
//! back a normal, fully-installed instance: the right Minecraft version and
//! loader are installed exactly like a manual "Create Instance" would, then
//! the pack's own content (mods, config, resourcepacks, saves, ...) is laid
//! on top of that instance's game directory.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Instant;
use serde::{Deserialize, Serialize};
use tauri::{Emitter, State};
use zip::ZipArchive;

use mc_launcher_core::net::download::{execute_plan, Checksum, DownloadPlan, DownloadTask};
use mc_launcher_core::progress::{ProgressEvent, ProgressReporter};

use crate::commands::minecraft::install_minecraft;
use crate::logger;
use crate::models::{InstallRequestPayload, InstalledInstance};
use crate::state::AppState;

const CURSEFORGE_API_KEY: &str = "$2a$10$bL4bIL5pUWqfcO7KQtnMReakwtfHbNKh6v1uTpKlzhwoueEJQnPnm";

// ── Request / response shapes ──────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
pub struct ModpackImportPayload {
    /// Full path to the dropped `.mrpack`/`.zip` file.
    pub file_path: String,
    /// Display name for the new instance.
    pub instance_name: String,
    /// `true` => use `custom_directory` verbatim. `false` => auto-compute a
    /// separated `<default minecraft dir>/!Instances/<name>` folder, same
    /// scheme "Create Instance" uses for "Separated folder".
    pub use_custom_directory: bool,
    #[serde(default)]
    pub custom_directory: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ModpackImportResult {
    pub instance: InstalledInstance,
    pub pack_name: Option<String>,
    /// Files the pack referenced but that failed to download (non-fatal —
    /// the instance is still created and playable).
    pub failed_files: Vec<String>,
    /// Number of CurseForge mods that could not be downloaded automatically.
    pub unresolved_curseforge_mods: usize,
}

#[derive(Debug, Clone, Serialize, Default)]
struct ActiveFileProgress {
    name: String,
    percent: Option<f64>,
}

#[derive(Debug, Clone, Serialize)]
struct ModpackImportProgress {
    id: String,
    stage: String,
    message: String,
    current: u32,
    total: u32,
    percent: f64,
    complete: bool,
    error: Option<String>,
    #[serde(default)]
    active_files: Vec<ActiveFileProgress>,
    #[serde(default)]
    downloaded_bytes: u64,
    #[serde(default)]
    speed_bps: f64,
    #[serde(default)]
    eta_seconds: Option<u64>,
}

const PROGRESS_EVENT: &str = "modpack-import-progress";
const PROGRESS_ID: &str = "modpack-import";

fn emit_progress(
    app: &tauri::AppHandle,
    stage: &str,
    message: String,
    current: u32,
    total: u32,
    complete: bool,
    error: Option<String>,
) {
    let percent = if total > 0 {
        (current as f64 / total as f64) * 100.0
    } else if complete {
        100.0
    } else {
        0.0
    };
    let _ = app.emit(
        PROGRESS_EVENT,
        &ModpackImportProgress {
            id: PROGRESS_ID.to_string(),
            stage: stage.to_string(),
            message,
            current,
            total,
            percent,
            complete,
            error,
            active_files: Vec::new(),
            downloaded_bytes: 0,
            speed_bps: 0.0,
            eta_seconds: None,
        },
    );
}

// ── Modrinth `.mrpack` index (modrinth.index.json) ─────────────────────────

#[derive(Debug, Deserialize)]
struct ModrinthIndex {
    #[serde(default)]
    name: Option<String>,
    #[serde(default, rename = "versionId")]
    #[allow(dead_code)]
    version_id: Option<String>,
    dependencies: std::collections::HashMap<String, String>,
    files: Vec<ModrinthFile>,
}

#[derive(Debug, Deserialize)]
struct ModrinthFile {
    path: String,
    downloads: Vec<String>,
    #[serde(default)]
    env: Option<ModrinthEnv>,
    #[serde(default)]
    hashes: Option<ModrinthFileHashes>,
    #[serde(rename = "fileSize", default)]
    #[allow(dead_code)]
    file_size: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct ModrinthFileHashes {
    #[serde(default)]
    sha1: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ModrinthEnv {
    #[serde(default)]
    client: Option<String>,
}

fn mrpack_loader_key(dependencies: &std::collections::HashMap<String, String>) -> Option<(String, String)> {
    for (key, loader_name) in [
        ("fabric-loader", "fabric"),
        ("quilt-loader", "quilt"),
        ("forge", "forge"),
        ("neoforge", "neoforge"),
    ] {
        if let Some(version) = dependencies.get(key) {
            return Some((loader_name.to_string(), version.clone()));
        }
    }
    None
}

// ── CurseForge `manifest.json` ──────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct CurseForgeManifest {
    #[serde(default)]
    name: Option<String>,
    minecraft: CurseForgeMinecraft,
    #[serde(default)]
    files: Vec<CurseForgeFileRef>,
    #[serde(default = "default_overrides_folder")]
    overrides: String,
}

fn default_overrides_folder() -> String {
    "overrides".to_string()
}

#[derive(Debug, Deserialize)]
struct CurseForgeMinecraft {
    version: String,
    #[serde(rename = "modLoaders", default)]
    mod_loaders: Vec<CurseForgeModLoader>,
}

#[derive(Debug, Deserialize)]
struct CurseForgeModLoader {
    id: String,
    #[serde(default)]
    primary: bool,
}

#[derive(Debug, Clone, Deserialize)]
struct CurseForgeFileRef {
    #[serde(rename = "projectID")]
    #[allow(dead_code)]
    project_id: u64,
    #[serde(rename = "fileID")]
    file_id: u64,
}

#[derive(Debug, Deserialize)]
struct CurseForgeBatchFilesResponse {
    data: Vec<CurseForgeResolvedFile>,
}

#[derive(Debug, Deserialize)]
struct CurseForgeResolvedFile {
    id: u64,
    #[serde(rename = "fileName")]
    file_name: String,
    #[serde(rename = "downloadUrl")]
    download_url: Option<String>,
}

fn split_curseforge_loader(id: &str) -> (String, String) {
    let lower = id.trim().to_lowercase();
    if let Some((name, ver)) = lower.split_once('-') {
        let loader = match name {
            "forge" => "forge",
            "fabric" => "fabric",
            "quilt" => "quilt",
            "neoforge" => "neoforge",
            other => other,
        };
        (loader.to_string(), ver.to_string())
    } else {
        (lower, "latest".to_string())
    }
}

fn urlencode_file_name(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect::<String>().replace("+", "%20")
}

// ── Real, parallel pack-file downloader ───────────────────────────────────

struct PackDownloadReporter<'a> {
    app: &'a tauri::AppHandle,
    total: u32,
    tasks_done: u32,
    active_labels: Vec<String>,
    label_display: std::collections::HashMap<String, String>,
    per_label_received: std::collections::HashMap<String, u64>,
    per_label_total: std::collections::HashMap<String, u64>,
    started_not_finished: std::collections::HashSet<String>,
    cumulative_bytes: u64,
    speed_window_start: Instant,
    speed_window_bytes: u64,
    speed_bps: f64,
    last_emit: Instant,
    start: Instant,
}

impl<'a> PackDownloadReporter<'a> {
    fn new(app: &'a tauri::AppHandle, total: u32) -> Self {
        let now = Instant::now();
        Self {
            app,
            total,
            tasks_done: 0,
            active_labels: Vec::new(),
            label_display: std::collections::HashMap::new(),
            per_label_received: std::collections::HashMap::new(),
            per_label_total: std::collections::HashMap::new(),
            started_not_finished: std::collections::HashSet::new(),
            cumulative_bytes: 0,
            speed_window_start: now,
            speed_window_bytes: 0,
            speed_bps: 0.0,
            last_emit: now,
            start: now,
        }
    }

    fn emit(&self, complete: bool) {
        let active_files: Vec<ActiveFileProgress> = self
            .active_labels
            .iter()
            .map(|l| {
                let name = self.label_display.get(l).cloned().unwrap_or_else(|| l.clone());
                let percent = self.per_label_total.get(l).filter(|t| **t > 0).map(|total| {
                    let received = self.per_label_received.get(l).copied().unwrap_or(0);
                    (received as f64 / *total as f64 * 100.0).clamp(0.0, 100.0)
                });
                ActiveFileProgress { name, percent }
            })
            .collect();

        let percent = if self.total > 0 {
            (self.tasks_done as f64 / self.total as f64 * 100.0).clamp(0.0, 100.0)
        } else {
            0.0
        };
        let elapsed = self.start.elapsed().as_secs_f64();
        let eta = if percent >= 1.0 && percent < 99.5 && elapsed > 1.0 {
            let total_estimated = elapsed / (percent / 100.0);
            Some((total_estimated - elapsed).max(0.0).round() as u64)
        } else {
            None
        };

        let message = if complete {
            "Downloaded pack files".to_string()
        } else if active_files.len() > 1 {
            format!("Downloading {} files…", active_files.len())
        } else if let Some(f) = active_files.first() {
            format!("Downloading {}…", f.name)
        } else {
            "Downloading pack files…".to_string()
        };

        let _ = self.app.emit(
            PROGRESS_EVENT,
            &ModpackImportProgress {
                id: PROGRESS_ID.to_string(),
                stage: "downloading".to_string(),
                message,
                current: self.tasks_done,
                total: self.total,
                percent,
                complete: false,
                error: None,
                active_files,
                downloaded_bytes: self.cumulative_bytes,
                speed_bps: self.speed_bps,
                eta_seconds: eta,
            },
        );
    }

    fn failed_labels(&self) -> Vec<String> {
        self.started_not_finished.iter().cloned().collect()
    }
}

impl<'a> ProgressReporter for PackDownloadReporter<'a> {
    fn report(&mut self, event: ProgressEvent) {
        match event {
            ProgressEvent::StageStarted { .. } => {}
            ProgressEvent::TaskStarted { label, path } => {
                let display = path
                    .file_name()
                    .map(|f| f.to_string_lossy().to_string())
                    .unwrap_or_else(|| label.clone());
                self.label_display.insert(label.clone(), display);
                if !self.active_labels.contains(&label) {
                    self.active_labels.push(label.clone());
                }
                self.started_not_finished.insert(label);
            }
            ProgressEvent::TaskSkipped { label, .. } => {
                self.tasks_done += 1;
                self.started_not_finished.remove(&label);
            }
            ProgressEvent::TaskFinished { label } => {
                self.tasks_done += 1;
                self.active_labels.retain(|l| l != &label);
                self.label_display.remove(&label);
                self.per_label_total.remove(&label);
                self.per_label_received.remove(&label);
                self.started_not_finished.remove(&label);
            }
            ProgressEvent::BytesReceived { label, received, total } => {
                let prev = self.per_label_received.insert(label.clone(), received).unwrap_or(0);
                let delta = received.saturating_sub(prev);
                self.cumulative_bytes += delta;
                if let Some(t) = total {
                    self.per_label_total.insert(label, t);
                }
            }
            ProgressEvent::InstallerOutputLine { .. } => {}
        }

        let now = Instant::now();
        if now.duration_since(self.speed_window_start).as_millis() >= 500 {
            let elapsed = now.duration_since(self.speed_window_start).as_secs_f64().max(0.001);
            self.speed_bps =
                (self.cumulative_bytes.saturating_sub(self.speed_window_bytes)) as f64 / elapsed;
            self.speed_window_start = now;
            self.speed_window_bytes = self.cumulative_bytes;
        }

        if now.duration_since(self.last_emit).as_millis() < 100 {
            return;
        }
        self.last_emit = now;
        self.emit(false);
    }
}

async fn download_pack_files(
    app: &tauri::AppHandle,
    state: &AppState,
    files: &[ModrinthFile],
    game_dir: &Path,
) -> Result<Vec<String>, String> {
    let mut plan = DownloadPlan::default();
    for f in files {
        if f.env.as_ref().and_then(|e| e.client.as_deref()) == Some("unsupported") {
            continue;
        }
        let Some(url) = f.downloads.first() else { continue };
        let checksum = f
            .hashes
            .as_ref()
            .and_then(|h| h.sha1.clone())
            .map(Checksum::Sha1);
        plan.tasks.push(DownloadTask {
            url: url.clone(),
            fallback_urls: f.downloads.iter().skip(1).cloned().collect(),
            destination: game_dir.join(&f.path),
            checksum,
            label: f.path.clone(),
        });
    }

    let mut failed: Vec<String> = files
        .iter()
        .filter(|f| f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported"))
        .filter(|f| f.downloads.is_empty())
        .map(|f| f.path.clone())
        .collect();

    let total = plan.tasks.len() as u32;
    logger::info(
        app,
        state,
        "MODPACK",
        &format!("Downloading {total} Modrinth pack file(s) in parallel…"),
    );
    emit_progress(app, "downloading", format!("Downloading {total} pack files…"), 0, total, false, None);

    if plan.tasks.is_empty() {
        return Ok(failed);
    }

    let app_owned = app.clone();
    let plan_result = tokio::task::spawn_blocking(move || {
        let mut reporter = PackDownloadReporter::new(&app_owned, total);
        let result = execute_plan(&plan, &mut reporter);
        reporter.emit(true);
        (result, reporter.failed_labels())
    })
    .await
    .map_err(|e| format!("Pack download task failed: {e}"))?;

    let (result, mut failed_from_reporter) = plan_result;
    failed.append(&mut failed_from_reporter);

    if let Err(e) = result {
        logger::warn(app, state, "MODPACK", &format!("Some pack files failed to download: {e}"));
    }

    Ok(failed)
}

async fn download_curseforge_pack_files(
    app: &tauri::AppHandle,
    state: &AppState,
    files: &[CurseForgeFileRef],
    game_dir: &Path,
) -> Result<Vec<String>, String> {
    if files.is_empty() {
        return Ok(Vec::new());
    }

    let mods_dir = game_dir.join("mods");
    let _ = std::fs::create_dir_all(&mods_dir);

    let client = reqwest::Client::builder()
        .user_agent("Zero-Launcher/1.0")
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());

    let file_ids: Vec<u64> = files.iter().map(|f| f.file_id).collect();
    let mut resolved_files: Vec<CurseForgeResolvedFile> = Vec::new();
    let mut unresolved_ids: Vec<u64> = Vec::new();

    emit_progress(app, "resolving", "Resolving CurseForge mods metadata…".into(), 0, file_ids.len() as u32, false, None);
    logger::info(app, state, "MODPACK", &format!("Resolving {} CurseForge mod(s) via API…", file_ids.len()));

    for chunk in file_ids.chunks(200) {
        let payload = serde_json::json!({ "fileIds": chunk });
        match client
            .post("https://api.curseforge.com/v1/mods/files")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("x-api-key", CURSEFORGE_API_KEY)
            .json(&payload)
            .send()
            .await
        {
            Ok(resp) if resp.status().is_success() => {
                if let Ok(data) = resp.json::<CurseForgeBatchFilesResponse>().await {
                    let mut found_ids = std::collections::HashSet::new();
                    for item in data.data {
                        found_ids.insert(item.id);
                        resolved_files.push(item);
                    }
                    for id in chunk {
                        if !found_ids.contains(id) {
                            unresolved_ids.push(*id);
                        }
                    }
                } else {
                    unresolved_ids.extend(chunk.iter().copied());
                }
            }
            Err(e) => {
                logger::warn(app, state, "MODPACK", &format!("CurseForge API batch query error: {e}"));
                unresolved_ids.extend(chunk.iter().copied());
            }
            Ok(resp) => {
                let status = resp.status();
                logger::warn(app, state, "MODPACK", &format!("CurseForge API returned HTTP {status}"));
                unresolved_ids.extend(chunk.iter().copied());
            }
        }
    }

    let mut plan = DownloadPlan::default();
    for f in &resolved_files {
        let fallback_edge = format!(
            "https://edge.forgecdn.net/files/{}/{}/{}",
            f.id / 1000,
            f.id % 1000,
            urlencode_file_name(&f.file_name)
        );
        let primary_url = f.download_url.clone().unwrap_or_else(|| fallback_edge.clone());
        let mut fallbacks = Vec::new();
        if primary_url != fallback_edge {
            fallbacks.push(fallback_edge);
        }
        fallbacks.push(format!(
            "https://mediafilez.forgecdn.net/files/{}/{}/{}",
            f.id / 1000,
            f.id % 1000,
            urlencode_file_name(&f.file_name)
        ));

        plan.tasks.push(DownloadTask {
            url: primary_url,
            fallback_urls: fallbacks,
            destination: mods_dir.join(&f.file_name),
            checksum: None,
            label: f.file_name.clone(),
        });
    }

    let mut failed: Vec<String> = unresolved_ids.into_iter().map(|id| format!("fileID:{id}")).collect();

    let total = plan.tasks.len() as u32;
    logger::info(
        app,
        state,
        "MODPACK",
        &format!("Downloading {total} CurseForge mod(s) in parallel…"),
    );
    emit_progress(app, "downloading", format!("Downloading {total} mod files…"), 0, total, false, None);

    if plan.tasks.is_empty() {
        return Ok(failed);
    }

    let app_owned = app.clone();
    let plan_result = tokio::task::spawn_blocking(move || {
        let mut reporter = PackDownloadReporter::new(&app_owned, total);
        let result = execute_plan(&plan, &mut reporter);
        reporter.emit(true);
        (result, reporter.failed_labels())
    })
    .await
    .map_err(|e| format!("CurseForge download task failed: {e}"))?;

    let (result, mut failed_from_reporter) = plan_result;
    failed.append(&mut failed_from_reporter);

    if let Err(e) = result {
        logger::warn(app, state, "MODPACK", &format!("Some CurseForge mod files failed to download: {e}"));
    }

    Ok(failed)
}

// ── Zip helpers ──────────────────────────────────────────────────────────

/// Recursively copy every entry under `src_prefix/` into `dest_root` on disk (case-insensitively).
fn extract_zip_folder(
    archive: &mut ZipArchive<std::fs::File>,
    src_prefix: &str,
    dest_root: &Path,
) -> Result<u32, String> {
    let mut copied = 0u32;
    let clean_prefix = src_prefix.replace('\\', "/").trim_matches('/').to_string();
    let prefix = if clean_prefix.is_empty() {
        String::new()
    } else {
        format!("{clean_prefix}/")
    };
    let prefix_lower = prefix.to_lowercase();

    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let raw_name = entry.name().replace('\\', "/");
        let name = raw_name.trim_start_matches("./");
        let name_lower = name.to_lowercase();

        if !prefix_lower.is_empty() {
            if !name_lower.starts_with(&prefix_lower) || name_lower == prefix_lower {
                continue;
            }
        }

        let rel = if prefix.is_empty() {
            name
        } else {
            let cut_len = prefix.len();
            if name.len() >= cut_len {
                &name[cut_len..]
            } else {
                continue;
            }
        };

        let clean_rel = rel.trim_start_matches('/');
        if clean_rel.is_empty() || clean_rel.contains("..") {
            continue;
        }

        let out_path = dest_root.join(clean_rel);
        if entry.is_dir() || name.ends_with('/') {
            let _ = std::fs::create_dir_all(&out_path);
            continue;
        }
        if let Some(parent) = out_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let mut buf = Vec::new();
        if entry.read_to_end(&mut buf).is_ok() {
            if std::fs::write(&out_path, &buf).is_ok() {
                copied += 1;
            }
        }
    }
    Ok(copied)
}

fn zip_has_folder(archive: &mut ZipArchive<std::fs::File>, folder: &str) -> bool {
    let clean_folder = folder.replace('\\', "/").trim_matches('/').to_string();
    let prefix_lower = format!("{}/", clean_folder.to_lowercase());
    (0..archive.len()).any(|i| {
        archive
            .by_index(i)
            .map(|e| {
                let name = e.name().replace('\\', "/");
                let name_lower = name.trim_start_matches("./").to_lowercase();
                name_lower.starts_with(&prefix_lower)
            })
            .unwrap_or(false)
    })
}

fn read_zip_entry_string(archive: &mut ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    // Try exact name first
    if let Ok(mut entry) = archive.by_name(name) {
        let mut s = String::new();
        if entry.read_to_string(&mut s).is_ok() {
            return Some(s);
        }
    }
    // Case-insensitive fallback
    let name_lower = name.to_lowercase();
    for i in 0..archive.len() {
        if let Ok(mut entry) = archive.by_index(i) {
            let raw_name = entry.name().replace('\\', "/");
            let clean = raw_name.trim_start_matches("./").to_lowercase();
            if clean == name_lower {
                let mut s = String::new();
                if entry.read_to_string(&mut s).is_ok() {
                    return Some(s);
                }
            }
        }
    }
    None
}

// ── Main command ─────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
pub struct ModpackPreview {
    pub name: Option<String>,
    pub minecraft_version: Option<String>,
    pub loader: Option<String>,
    pub loader_version: Option<String>,
    pub file_count: u32,
    pub format: String, // "mrpack" | "curseforge" | "generic"
}

#[tauri::command]
pub async fn preview_modpack(file_path: String) -> Result<ModpackPreview, String> {
    let path = PathBuf::from(&file_path);
    let file = std::fs::File::open(&path).map_err(|e| format!("Failed to open file: {e}"))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("Not a valid zip/mrpack: {e}"))?;

    if let Some(json) = read_zip_entry_string(&mut archive, "modrinth.index.json") {
        let index: ModrinthIndex =
            serde_json::from_str(&json).map_err(|e| format!("Invalid modrinth.index.json: {e}"))?;
        let (loader, loader_version) = mrpack_loader_key(&index.dependencies)
            .map(|(l, v)| (Some(l), Some(v)))
            .unwrap_or((Some("vanilla".to_string()), None));
        return Ok(ModpackPreview {
            name: index.name,
            minecraft_version: index.dependencies.get("minecraft").cloned(),
            loader,
            loader_version,
            file_count: index.files.len() as u32,
            format: "mrpack".to_string(),
        });
    }

    if let Some(json) = read_zip_entry_string(&mut archive, "manifest.json") {
        if let Ok(manifest) = serde_json::from_str::<CurseForgeManifest>(&json) {
            let primary = manifest
                .minecraft
                .mod_loaders
                .iter()
                .find(|l| l.primary)
                .or(manifest.minecraft.mod_loaders.first());
            let (loader, loader_version) = primary
                .map(|l| split_curseforge_loader(&l.id))
                .map(|(a, b)| (Some(a), Some(b)))
                .unwrap_or((Some("vanilla".to_string()), None));
            return Ok(ModpackPreview {
                name: manifest.name,
                minecraft_version: Some(manifest.minecraft.version),
                loader,
                loader_version,
                file_count: manifest.files.len() as u32,
                format: "curseforge".to_string(),
            });
        }
    }

    let has_mods = zip_has_folder(&mut archive, "mods");
    Ok(ModpackPreview {
        name: path.file_stem().map(|s| s.to_string_lossy().to_string()),
        minecraft_version: None,
        loader: None,
        loader_version: None,
        file_count: if has_mods { archive.len() as u32 } else { 0 },
        format: "generic".to_string(),
    })
}

#[tauri::command]
pub async fn import_modpack(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    payload: ModpackImportPayload,
) -> Result<ModpackImportResult, String> {
    let zip_path = PathBuf::from(&payload.file_path);
    if !zip_path.is_file() {
        return Err("Modpack file not found".to_string());
    }
    let instance_name = payload.instance_name.trim();
    if instance_name.is_empty() {
        return Err("Instance name is required".to_string());
    }

    emit_progress(&app, "reading", "Reading modpack…".into(), 0, 0, false, None);
    logger::info(&app, &state, "MODPACK", &format!("Reading modpack archive: {}", zip_path.display()));

    let file = std::fs::File::open(&zip_path).map_err(|e| format!("Failed to open file: {e}"))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("Not a valid zip/mrpack file: {e}"))?;

    let mrpack_index = read_zip_entry_string(&mut archive, "modrinth.index.json")
        .map(|json| serde_json::from_str::<ModrinthIndex>(&json))
        .transpose()
        .map_err(|e| format!("Invalid modrinth.index.json: {e}"))?;
    let cf_manifest = if mrpack_index.is_none() {
        read_zip_entry_string(&mut archive, "manifest.json")
            .and_then(|json| serde_json::from_str::<CurseForgeManifest>(&json).ok())
    } else {
        None
    };

    // ── Resolve minecraft version / loader / loader version ────────────
    let (mc_version, loader, loader_version, pack_name) = if let Some(idx) = &mrpack_index {
        let mc_version = idx
            .dependencies
            .get("minecraft")
            .cloned()
            .ok_or("Modpack is missing a Minecraft version")?;
        let (loader, loader_version) =
            mrpack_loader_key(&idx.dependencies).unwrap_or(("vanilla".to_string(), "latest".to_string()));
        (mc_version, loader, loader_version, idx.name.clone())
    } else if let Some(manifest) = &cf_manifest {
        let primary = manifest
            .minecraft
            .mod_loaders
            .iter()
            .find(|l| l.primary)
            .or(manifest.minecraft.mod_loaders.first());
        let (loader, loader_version) = primary
            .map(|l| split_curseforge_loader(&l.id))
            .unwrap_or(("vanilla".to_string(), "latest".to_string()));
        (manifest.minecraft.version.clone(), loader, loader_version, manifest.name.clone())
    } else {
        return Err(
            "This zip doesn't look like a .mrpack or CurseForge modpack (no modrinth.index.json or manifest.json found). Use \"Create Instance\" and then drop mods individually instead."
                .to_string(),
        );
    };

    logger::info(
        &app,
        &state,
        "MODPACK",
        &format!("Importing \"{}\" — {} ({} {})", instance_name, mc_version, loader, loader_version),
    );

    // ── Where this instance's mods/config/saves/resourcepacks will live ─
    let default_dir = state.settings.lock().unwrap().resolved_game_directory();
    let game_dir = if payload.use_custom_directory {
        let dir = payload
            .custom_directory
            .as_ref()
            .filter(|d| !d.trim().is_empty())
            .ok_or("A custom directory is required")?;
        PathBuf::from(dir)
    } else {
        let safe_name: String = instance_name
            .chars()
            .map(|c| if "\\/:*?\"<>|".contains(c) { ' ' } else { c })
            .collect();
        default_dir.join("!Instances").join(safe_name.trim())
    };
    std::fs::create_dir_all(&game_dir).map_err(|e| format!("Failed to create instance folder: {e}"))?;
    logger::info(&app, &state, "MODPACK", &format!("Instance directory: {}", game_dir.display()));

    // ── Install the base game + loader ─────────────────────────────────
    emit_progress(
        &app,
        "installing",
        format!("Installing Minecraft {mc_version} ({loader})…"),
        0,
        0,
        false,
        None,
    );
    let install_payload = InstallRequestPayload {
        minecraft_version: mc_version,
        loader,
        loader_version,
        directory: Some(game_dir.to_string_lossy().to_string()),
        name: Some(instance_name.to_string()),
        old_version_id: None,
    };
    let instance = install_minecraft(app.clone(), state.clone(), install_payload).await?;
    logger::info(&app, &state, "MODPACK", "Base game + loader installed, laying pack content on top…");

    // ── Re-open archive fresh for file extraction ──────────────────────
    let file = std::fs::File::open(&zip_path).map_err(|e| format!("Failed to reopen archive: {e}"))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("Failed to read archive: {e}"))?;

    let mut failed_files = Vec::new();
    let mut unresolved_curseforge_mods = 0usize;

    if let Some(idx) = mrpack_index {
        let downloaded_failures = download_pack_files(&app, &state, &idx.files, &game_dir).await?;
        failed_files.extend(downloaded_failures);

        emit_progress(&app, "extracting", "Copying pack configurations and overrides…".into(), 0, 0, false, None);
        logger::info(&app, &state, "MODPACK", "Copying pack overrides (configs, resourcepacks, etc.)…");
        
        let overrides_copied = extract_zip_folder(&mut archive, "overrides", &game_dir).unwrap_or(0);
        let client_overrides_copied = extract_zip_folder(&mut archive, "client-overrides", &game_dir).unwrap_or(0);
        let client_overrides_alt = extract_zip_folder(&mut archive, "client_overrides", &game_dir).unwrap_or(0);
        
        logger::info(
            &app,
            &state,
            "MODPACK",
            &format!("Copied {} override file(s)", overrides_copied + client_overrides_copied + client_overrides_alt),
        );
    } else if let Some(manifest) = cf_manifest {
        // Download all CurseForge mods!
        if !manifest.files.is_empty() {
            let cf_failures = download_curseforge_pack_files(&app, &state, &manifest.files, &game_dir).await?;
            unresolved_curseforge_mods = cf_failures.len();
            failed_files.extend(cf_failures);
        }

        emit_progress(&app, "extracting", "Copying pack configurations and overrides…".into(), 0, 0, false, None);
        logger::info(&app, &state, "MODPACK", "Copying pack overrides (configs, resourcepacks, etc.)…");
        
        let ov_folder = if manifest.overrides.trim().is_empty() { "overrides" } else { &manifest.overrides };
        let mut overrides_copied = extract_zip_folder(&mut archive, ov_folder, &game_dir).unwrap_or(0);
        if !ov_folder.eq_ignore_ascii_case("overrides") {
            overrides_copied += extract_zip_folder(&mut archive, "overrides", &game_dir).unwrap_or(0);
        }
        overrides_copied += extract_zip_folder(&mut archive, "client-overrides", &game_dir).unwrap_or(0);
        overrides_copied += extract_zip_folder(&mut archive, "client_overrides", &game_dir).unwrap_or(0);
        
        logger::info(&app, &state, "MODPACK", &format!("Copied {overrides_copied} override file(s)"));
    }

    logger::info(&app, &state, "MODPACK", &format!("Modpack import finished — {} file(s) failed", failed_files.len()));
    emit_progress(&app, "done", "Modpack imported".into(), 0, 0, true, None);

    Ok(ModpackImportResult {
        instance,
        pack_name,
        failed_files,
        unresolved_curseforge_mods,
    })
}

