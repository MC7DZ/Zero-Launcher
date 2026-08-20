//! Modpack import — drag/drop a `.mrpack` (Modrinth) or `.zip` (CurseForge
//! or a plain "mods+config+resourcepacks" export) onto the launcher and get
//! back a normal, fully-installed instance: the right Minecraft version and
//! loader are installed exactly like a manual "Create Instance" would, then
//! the pack's own content (mods, config, resourcepacks, saves, ...) is laid
//! on top of that instance's game directory.
//!
//! Loosely modeled on https://github.com/KTrain5169/unpacker — same overall
//! flow (read the pack's manifest → install version/loader → download each
//! referenced mod file → drop in the pack's `overrides/`), reimplemented
//! here so it can reuse this launcher's own installer/instance machinery
//! (shared `versions/`, per-instance directories, download-progress events,
//! etc.) instead of standing up a separate one.

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
    /// Set when the pack was CurseForge-format: this launcher can install
    /// the correct version/loader and copy the pack's `overrides/`, but
    /// can't resolve CurseForge project/file IDs to download URLs without
    /// a CurseForge API key, so mods referenced only by ID (not bundled
    /// directly in the zip) were skipped.
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
    /// Every pack file actively downloading right now (real, from the same
    /// parallel byte-level downloader "Create Instance" uses) — lets the
    /// frontend show this exactly like an instance install: several files
    /// in flight at once, each with its own live percent.
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

/// Map a `modrinth.index.json` dependency key to this launcher's own
/// `loader` string (`InstallRequestPayload.loader` / `InstalledInstance.loader`).
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

#[derive(Debug, Deserialize)]
struct CurseForgeFileRef {
    #[serde(rename = "projectID")]
    #[allow(dead_code)]
    project_id: u64,
    #[serde(rename = "fileID")]
    #[allow(dead_code)]
    file_id: u64,
}

/// CurseForge's `modLoaders[].id` looks like `"forge-47.2.20"` or
/// `"fabric-0.15.7"` — split into (loader, version).
fn split_curseforge_loader(id: &str) -> (String, String) {
    if let Some((name, ver)) = id.split_once('-') {
        let loader = match name {
            "forge" => "forge",
            "fabric" => "fabric",
            "quilt" => "quilt",
            "neoforge" => "neoforge",
            other => other,
        };
        (loader.to_string(), ver.to_string())
    } else {
        (id.to_string(), "latest".to_string())
    }
}

// ── Real, parallel, byte-level pack-file downloader (same engine "Create
// Instance" uses for libraries/assets) ─────────────────────────────────────

/// Reports [`ProgressEvent`]s from [`execute_plan`] as the same rich,
/// byte-level `modpack-import-progress` events "Create Instance" emits as
/// `download-progress` — several files in flight at once, each with a real
/// percent, plus aggregate speed/ETA. Mirrors the reporter closure in
/// `commands::minecraft::install_minecraft` so the modpack extractor's
/// progress reads exactly like an instance install, not a fake/simulated
/// one.
struct PackDownloadReporter<'a> {
    app: &'a tauri::AppHandle,
    total: u32,
    tasks_done: u32,
    /// Labels currently downloading, in the order they started.
    active_labels: Vec<String>,
    label_display: std::collections::HashMap<String, String>,
    per_label_received: std::collections::HashMap<String, u64>,
    per_label_total: std::collections::HashMap<String, u64>,
    /// Every label that got a `TaskStarted` but never a matching
    /// `TaskFinished` — `execute_plan` only ever surfaces the *first*
    /// error it hits (workers keep pulling other files after one fails),
    /// so this diff is how we recover the full non-fatal failed-file list
    /// the way the old sequential loop used to track directly.
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

    /// Labels that started downloading but never finished — the pack
    /// files that failed (or were still in flight when a fatal, non-file
    /// error aborted the plan early).
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
            // Modpack installs don't run a loader installer jar directly
            // (that happens via the normal Minecraft install path), so
            // there's nothing extra to do with these lines here.
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

        // Throttle UI emits to ~10/sec, same as the instance installer.
        if now.duration_since(self.last_emit).as_millis() < 100 {
            return;
        }
        self.last_emit = now;
        self.emit(false);
    }
}

/// Downloads every referenced pack file through the same parallel,
/// byte-level, checksum-verifying downloader "Create Instance" uses for
/// libraries/assets (`mc_launcher_core::net::download::execute_plan`),
/// instead of the old one-file-at-a-time, whole-file-in-memory fetch. Runs
/// on a blocking thread (the downloader is synchronous), same as the base
/// install. Returns the paths (relative, pack-internal) of any file that
/// failed after retries — non-fatal, the instance is still usable.
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

    // Files with no download URL at all can never succeed — surface them
    // immediately without going through the downloader.
    let mut failed: Vec<String> = files
        .iter()
        .filter(|f| f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported"))
        .filter(|f| f.downloads.is_empty())
        .map(|f| f.path.clone())
        .collect();
    if !failed.is_empty() {
        logger::warn(
            app,
            state,
            "MODPACK",
            &format!("{} pack file(s) have no download URL, skipping", failed.len()),
        );
    }

    let total = plan.tasks.len() as u32;
    logger::info(
        app,
        state,
        "MODPACK",
        &format!("Downloading {total} pack file(s) (up to 24 in parallel)…"),
    );
    emit_progress(app, "downloading", format!("Downloading {total} pack files…"), 0, total, false, None);

    if plan.tasks.is_empty() {
        return Ok(failed);
    }

    let app_owned = app.clone();
    let plan_result = tokio::task::spawn_blocking(move || {
        let mut reporter = PackDownloadReporter::new(&app_owned, total);
        let result = execute_plan(&plan, &mut reporter);
        // Always emit one final snapshot so the UI's last frame reflects
        // reality (matches/skips it would otherwise miss under the ~10/sec
        // throttle) before whatever comes next (extracting overrides).
        reporter.emit(true);
        (result, reporter.failed_labels())
    })
    .await
    .map_err(|e| format!("Pack download task failed: {e}"))?;

    let (result, mut failed_from_reporter) = plan_result;
    failed.append(&mut failed_from_reporter);

    if let Err(e) = result {
        // execute_plan only surfaces the *first* file's error message (see
        // PackDownloadReporter's doc comment) — everything else it
        // touched either finished or shows up in `failed`. Non-fatal: log
        // it and keep going with whatever did succeed.
        logger::warn(app, state, "MODPACK", &format!("Some pack files failed to download: {e}"));
    }

    if !failed.is_empty() {
        logger::warn(
            app,
            state,
            "MODPACK",
            &format!("{} pack file(s) failed to download and were skipped", failed.len()),
        );
    }

    Ok(failed)
}

// ── Zip helpers ──────────────────────────────────────────────────────────

/// Recursively copy every entry under `src_prefix/` (a folder inside the
/// zip, e.g. `"overrides/"`) into `dest_root` on disk, stripping the prefix.
fn extract_zip_folder(
    archive: &mut ZipArchive<std::fs::File>,
    src_prefix: &str,
    dest_root: &Path,
) -> Result<u32, String> {
    let mut copied = 0u32;
    let prefix = if src_prefix.ends_with('/') {
        src_prefix.to_string()
    } else {
        format!("{src_prefix}/")
    };
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_string();
        if !name.starts_with(&prefix) || name == prefix {
            continue;
        }
        let rel = &name[prefix.len()..];
        if rel.is_empty() {
            continue;
        }
        let out_path = dest_root.join(rel);
        if entry.is_dir() {
            std::fs::create_dir_all(&out_path).map_err(|e| e.to_string())?;
            continue;
        }
        if let Some(parent) = out_path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf).map_err(|e| e.to_string())?;
        std::fs::write(&out_path, &buf).map_err(|e| e.to_string())?;
        copied += 1;
    }
    Ok(copied)
}

/// Does the zip contain any entry under `folder/`? Used to detect a plain
/// (non-mrpack, non-CurseForge) export that just bundles `mods/`,
/// `config/`, `resourcepacks/`, `saves/` at its root.
fn zip_has_folder(archive: &mut ZipArchive<std::fs::File>, folder: &str) -> bool {
    let prefix = format!("{folder}/");
    (0..archive.len()).any(|i| {
        archive
            .by_index(i)
            .map(|e| e.name().starts_with(&prefix))
            .unwrap_or(false)
    })
}

fn read_zip_entry_string(archive: &mut ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let mut entry = archive.by_name(name).ok()?;
    let mut s = String::new();
    entry.read_to_string(&mut s).ok()?;
    Some(s)
}

// ── Main command ─────────────────────────────────────────────────────────

/// Sniff a dropped modpack's manifest without extracting/installing
/// anything, so the UI can show "1.20.1 · Fabric · 87 mods" in the
/// confirmation dialog before the user commits to a name/location.
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

    // Generic zip: no manifest, just a folder dump. Report what we can.
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
        // Generic zip: no manifest to read version/loader from. Install
        // won't happen here — caller is expected to have already offered
        // the user a version/loader picker for this case (the frontend
        // falls back to that when preview_modpack reports "generic").
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
    let default_dir = PathBuf::from(&state.settings.lock().unwrap().game_directory);
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

    // ── Install the base game + loader (shared versions/libraries/assets,
    // exactly like a manual "Create Instance") ─────────────────────────
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

    // ── Lay the pack's own content on top of the freshly-installed
    // instance directory ────────────────────────────────────────────────
    let mut failed_files = Vec::new();
    let mut unresolved_curseforge_mods = 0usize;

    if let Some(idx) = mrpack_index {
        let downloaded_failures = download_pack_files(&app, &state, &idx.files, &game_dir).await?;
        failed_files.extend(downloaded_failures);

        // overrides / client-overrides — extracted after the file list so
        // any pack-provided config that overlaps a downloaded file's own
        // folder still ends up in the right place.
        let total = idx.files.len() as u32;
        emit_progress(&app, "extracting", "Copying pack files…".into(), total, total, false, None);
        logger::info(&app, &state, "MODPACK", "Copying pack overrides (configs, resourcepacks, etc.)…");
        let overrides_copied = extract_zip_folder(&mut archive, "overrides", &game_dir).unwrap_or(0);
        let client_overrides_copied = extract_zip_folder(&mut archive, "client-overrides", &game_dir).unwrap_or(0);
        logger::info(
            &app,
            &state,
            "MODPACK",
            &format!("Copied {} override file(s)", overrides_copied + client_overrides_copied),
        );
    } else if let Some(manifest) = cf_manifest {
        emit_progress(&app, "extracting", "Copying pack files…".into(), 0, 0, false, None);
        logger::info(&app, &state, "MODPACK", "Copying pack overrides (configs, resourcepacks, etc.)…");
        let overrides_copied = extract_zip_folder(&mut archive, &manifest.overrides, &game_dir).unwrap_or(0);
        logger::info(&app, &state, "MODPACK", &format!("Copied {overrides_copied} override file(s)"));
        // CurseForge's `files[]` only carries numeric project/file IDs —
        // resolving those to a downloadable jar needs the CurseForge API
        // (and an API key this launcher doesn't have configured), so those
        // are surfaced to the user instead of silently missing.
        unresolved_curseforge_mods = manifest.files.len();
        if unresolved_curseforge_mods > 0 {
            logger::warn(
                &app,
                &state,
                "MODPACK",
                &format!("{unresolved_curseforge_mods} CurseForge mod(s) need to be added manually (no API key configured)"),
            );
        }
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
