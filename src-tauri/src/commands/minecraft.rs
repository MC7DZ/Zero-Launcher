use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::time::Instant;
use tauri::{Emitter, Manager, State};
use tokio::io::{AsyncBufReadExt, BufReader};
use crate::logger;
use crate::models::*;
use crate::state::AppState;

/// Internal panic payload used to unwind out of `install_with_progress`
/// when the user cancels an in-progress download. The library's
/// `ProgressReporter::report` has no return value, so this is the only
/// way to interrupt the (synchronous, blocking) install from inside the
/// progress callback.
const CANCEL_MARKER: &str = "__zerolauncher_download_cancelled__";

// ── Instance-named version folders ─────────────────────────────────────────
//
// By default `mc-launcher-core` names a loader install's `versions/<id>`
// folder after the loader itself (e.g. `fabric-loader-0.19.3-26.1`), which
// is meaningless once you have more than one instance. The functions below
// let a loader install's on-disk folder be renamed to match its instance's
// display name instead, and let a brand-new instance reuse (copy) another
// instance's already-downloaded files when it needs the exact same
// (minecraft_version, loader, loader_version) combo instead of downloading
// them again.
//
// Vanilla installs are deliberately left alone: their folder is just the
// Minecraft version itself (e.g. `1.21.1`), which is already meaningful,
// and every loader install's version json depends on that folder existing
// under its original name (`inheritsFrom`) — renaming it would break every
// modded instance built on top of it.

/// Turn a display name into a filesystem-safe version/folder id.
fn sanitize_instance_folder_name(name: &str) -> String {
    let cleaned: String = name
        .trim()
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c => c,
        })
        .collect();
    let cleaned = cleaned.trim().trim_matches('.').to_string();
    if cleaned.is_empty() {
        "instance".to_string()
    } else {
        cleaned
    }
}

/// A folder name based on `base` that doesn't collide with anything already
/// under `versions_dir`, appending " (2)", " (3)", ... as needed.
/// `ignore` (if given) is a folder name allowed to "collide" — used when
/// renaming a folder that may already be sitting at the target name.
fn unique_version_folder_name(versions_dir: &Path, base: &str, ignore: Option<&str>) -> String {
    let mut candidate = base.to_string();
    let mut n = 2;
    loop {
        if Some(candidate.as_str()) == ignore || !versions_dir.join(&candidate).exists() {
            return candidate;
        }
        candidate = format!("{base} ({n})");
        n += 1;
    }
}

/// Rewrite a version json's `id` field (and its `jar` field, but only when
/// that field was self-referencing the old id — loader jsons that inherit
/// their jar from vanilla point `jar`/`inheritsFrom` at the *vanilla*
/// version and must stay untouched) to `new_id`, then rename the json/jar
/// files themselves from `<old_id>.*` to `<new_id>.*`.
fn repoint_version_json(dir: &Path, old_id: &str, new_id: &str) {
    let old_json = dir.join(format!("{old_id}.json"));
    let new_json = dir.join(format!("{new_id}.json"));
    if old_json.is_file() {
        if let Ok(contents) = std::fs::read_to_string(&old_json) {
            if let Ok(mut parsed) = serde_json::from_str::<serde_json::Value>(&contents) {
                if let Some(obj) = parsed.as_object_mut() {
                    obj.insert("id".to_string(), serde_json::Value::String(new_id.to_string()));
                    if obj.get("jar").and_then(|v| v.as_str()) == Some(old_id) {
                        obj.insert("jar".to_string(), serde_json::Value::String(new_id.to_string()));
                    }
                }
                if let Ok(new_contents) = serde_json::to_string_pretty(&parsed) {
                    let _ = std::fs::write(&new_json, new_contents);
                }
            }
        }
        if old_json != new_json {
            let _ = std::fs::remove_file(&old_json);
        }
    }

    let old_jar = dir.join(format!("{old_id}.jar"));
    let new_jar = dir.join(format!("{new_id}.jar"));
    if old_jar.is_file() && old_jar != new_jar {
        let _ = std::fs::rename(&old_jar, &new_jar);
    }
}

/// Rename `versions/<old_id>` to `versions/<new_id>` in place, fixing up the
/// json/jar inside it. No-op if the ids match or the source doesn't exist.
fn rename_version_folder(versions_dir: &Path, old_id: &str, new_id: &str) -> std::io::Result<()> {
    if old_id == new_id {
        return Ok(());
    }
    let old_dir = versions_dir.join(old_id);
    if !old_dir.is_dir() {
        return Ok(());
    }
    let new_dir = versions_dir.join(new_id);
    std::fs::rename(&old_dir, &new_dir)?;
    repoint_version_json(&new_dir, old_id, new_id);
    Ok(())
}

/// Copy `versions/<src_id>` to `versions/<dst_id>` (used to reuse an
/// already-downloaded loader install for a new instance instead of
/// downloading it again), fixing up the copied json/jar.
fn copy_version_folder(versions_dir: &Path, src_id: &str, dst_id: &str) -> std::io::Result<()> {
    let src_dir = versions_dir.join(src_id);
    if !src_dir.is_dir() {
        return Err(std::io::Error::new(std::io::ErrorKind::NotFound, "source version folder missing"));
    }
    let dst_dir = versions_dir.join(dst_id);
    copy_dir_recursive(&src_dir, &dst_dir)?;
    repoint_version_json(&dst_dir, src_id, dst_id);
    Ok(())
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_recursive(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}

/// Fixed weight (in percentage points, summing to 100) given to each coarse
/// install stage. The installer never tells us the total task/byte count for
/// the whole run up front, so instead of one global "tasks done / tasks
/// started" ratio (which shrinks every time a new task starts and made the
/// old bar/ETA behave erratically), each stage gets its own fixed budget of
/// the overall bar and we track completion *within* that stage only.
fn stage_weight(stage: &mc_launcher_core::progress::InstallStage) -> f64 {
    use mc_launcher_core::progress::InstallStage as S;
    match stage {
        S::ResolveVersion => 2.0,
        S::DownloadLibraries => 25.0,
        S::DownloadAssets => 45.0,
        S::InstallRuntime => 15.0,
        S::ExtractNatives => 5.0,
        S::LoaderInstall => 6.0,
        S::Verify => 2.0,
    }
}

/// How many percentage points of `stage_weight` come *before* this stage,
/// i.e. the running total of every earlier stage's weight.
fn stage_offset(stage: &mc_launcher_core::progress::InstallStage) -> f64 {
    use mc_launcher_core::progress::InstallStage as S;
    let order = [
        S::ResolveVersion,
        S::DownloadLibraries,
        S::DownloadAssets,
        S::InstallRuntime,
        S::ExtractNatives,
        S::LoaderInstall,
        S::Verify,
    ];
    let mut offset = 0.0;
    for s in order.iter() {
        if s == stage {
            break;
        }
        offset += stage_weight(s);
    }
    offset
}

/// Best-effort overall completion estimate (0-100), monotonically
/// non-decreasing across a single install run.
///
/// Within the current stage we blend "tasks finished in this stage" with the
/// current file's own byte progress, then scale that fraction into the
/// stage's fixed slice of the overall bar (see `stage_weight`). This keeps
/// the bar moving at a believable, steady pace instead of jumping around
/// as new tasks are discovered.
fn overall_percent(
    stage: &mc_launcher_core::progress::InstallStage,
    tasks_started: u64,
    tasks_done: u64,
    current_received: u64,
    current_total: Option<u64>,
) -> f64 {
    let weight = stage_weight(stage);
    let offset = stage_offset(stage);

    let stage_frac = if tasks_started == 0 {
        0.0
    } else {
        let base = tasks_done as f64 / tasks_started as f64;
        let current_frac = match current_total {
            Some(total) if total > 0 => (current_received as f64 / total as f64).clamp(0.0, 1.0),
            _ => 0.0,
        };
        // Blend in the in-flight file's progress as a fraction of "one more
        // task", so large single files (e.g. the client jar) still move the
        // bar smoothly instead of sitting still until the whole file lands.
        (base + (current_frac.max(0.0) / (tasks_started.max(tasks_done + 1)) as f64)).clamp(0.0, 1.0)
    };

    (offset + stage_frac * weight).clamp(0.0, 99.5)
}

/// Fetch the Mojang version manifest.
#[tauri::command]
pub async fn get_available_versions() -> Result<Vec<VersionInfo>, String> {
    let manifest: VersionManifest = reqwest::get(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
    )
    .await
    .map_err(|e| format!("Failed to fetch versions: {e}"))?
    .json()
    .await
    .map_err(|e| format!("Failed to parse versions: {e}"))?;

    Ok(manifest.versions)
}

/// Scan a `.minecraft`-style game directory's `versions/` folder and report
/// what's actually installed on disk. Works for any launcher's directory
/// layout (vanilla, or this launcher's own installs) since it just reads
/// the standard `versions/<id>/<id>.json` + `<id>.jar` structure.
#[tauri::command]
pub async fn scan_minecraft_versions(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<Vec<LocalVersionInfo>, String> {
    let game_dir = directory
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(&state.settings.lock().unwrap().game_directory));

    let versions_dir = game_dir.join("versions");
    if !versions_dir.is_dir() {
        return Ok(Vec::new());
    }

    let entries = std::fs::read_dir(&versions_dir)
        .map_err(|e| format!("Failed to read versions directory: {e}"))?;

    let mut found = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let id = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) => n.to_string(),
            None => continue,
        };

        let json_path = path.join(format!("{id}.json"));
        if !json_path.is_file() {
            // Not a real version folder (no metadata json) — skip it.
            continue;
        }

        // Try to pull out the actual Minecraft version this entry targets,
        // and use `inheritsFrom` (present on modded/loader versions) to spot
        // that this isn't a bare vanilla entry. Loader versions (Forge,
        // Fabric, Quilt, NeoForge, ...) almost never ship their own
        // `<id>.jar` — they inherit the jar from the vanilla version they're
        // built on top of, referenced either via the top-level `jar` field
        // or, absent that, via `inheritsFrom` itself. Checking only for
        // `<id>.jar` therefore incorrectly flagged every loader install as
        // "incomplete" even when it installed cleanly.
        let (minecraft_version, inherits_from, jar_target) = match std::fs::read_to_string(&json_path) {
            Ok(contents) => {
                let parsed: serde_json::Value = serde_json::from_str(&contents).unwrap_or_default();
                let inherits = parsed.get("inheritsFrom").and_then(|v| v.as_str()).map(String::from);
                let mc_ver = parsed
                    .get("id")
                    .and_then(|v| v.as_str())
                    .filter(|_| inherits.is_none())
                    .map(String::from)
                    .or_else(|| inherits.clone());
                // The `jar` field (when present) names the version whose jar
                // should actually be used; fall back to `inheritsFrom`, then
                // finally to this version's own id (the vanilla/no-loader case).
                let jar_field = parsed.get("jar").and_then(|v| v.as_str()).map(String::from);
                let jar_target = jar_field.or_else(|| inherits.clone()).unwrap_or_else(|| id.clone());
                (mc_ver, inherits, jar_target)
            }
            Err(_) => (None, None, id.clone()),
        };

        // The jar can live either in this version's own folder or in the
        // folder of whatever version it inherits its jar from.
        let has_jar = path.join(format!("{id}.jar")).is_file()
            || versions_dir
                .join(&jar_target)
                .join(format!("{jar_target}.jar"))
                .is_file();

        let lower_id = id.to_lowercase();
        let loader = if inherits_from.is_some() {
            if lower_id.contains("fabric") {
                "fabric"
            } else if lower_id.contains("quilt") {
                "quilt"
            } else if lower_id.contains("neoforge") {
                "neoforge"
            } else if lower_id.contains("forge") {
                "forge"
            } else {
                "unknown"
            }
        } else {
            "vanilla"
        }
        .to_string();

        found.push(LocalVersionInfo {
            id,
            has_jar,
            minecraft_version,
            loader,
            path: path.to_string_lossy().to_string(),
        });
    }

    found.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(found)
}

/// List every distinct (minecraft_version, loader, loader_version) already
/// installed for a tracked instance, anywhere on disk. Pulled straight from
/// in-memory state — no network or filesystem I/O — so the frontend can
/// populate a version picker with "already downloaded" entries instantly,
/// before (or even without) waiting on the full Mojang manifest fetch.
#[tauri::command]
pub async fn get_cached_versions(state: State<'_, AppState>) -> Result<Vec<CachedVersionInfo>, String> {
    let instances = state.instances.lock().unwrap();
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for inst in instances.iter() {
        let key = (inst.minecraft_version.clone(), inst.loader.clone(), inst.loader_version.clone());
        if seen.insert(key) {
            out.push(CachedVersionInfo {
                minecraft_version: inst.minecraft_version.clone(),
                loader: inst.loader.clone(),
                loader_version: inst.loader_version.clone(),
            });
        }
    }
    Ok(out)
}

/// Install a Minecraft version with optional mod loader.
#[tauri::command]
pub async fn install_minecraft(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    payload: InstallRequestPayload,
) -> Result<InstalledInstance, String> {
    use mc_launcher_core::prelude::*;

    // The default `.minecraft`-style directory — this is always where the
    // actual game files (`versions/`, `libraries/`, `assets/`) get
    // installed, no matter what custom path is chosen below. That means
    // the jar + loader (e.g. the version and `fabric-loader-...` folders
    // under `versions/`) always end up in the same default Minecraft
    // directory, exactly as if the user had installed straight into the
    // default location — and every instance that uses the same
    // version/loader combo shares those files instead of re-downloading
    // them into each custom folder.
    let minecraft_dir = PathBuf::from(&state.settings.lock().unwrap().game_directory);

    // The instance's own "game directory" — where saves, mods,
    // resourcepacks, config, and logs for *this* instance live. This is
    // the custom path the user picked, or the default directory if none
    // was given (in which case it's the same directory as `minecraft_dir`
    // above).
    let game_dir = if let Some(ref dir) = payload.directory {
        PathBuf::from(dir)
    } else {
        minecraft_dir.clone()
    };

    // Create both directories if needed.
    std::fs::create_dir_all(&minecraft_dir)
        .map_err(|e| format!("Failed to create Minecraft directory: {e}"))?;
    std::fs::create_dir_all(&game_dir)
        .map_err(|e| format!("Failed to create game directory: {e}"))?;

    logger::info(&app, &state, "LAUNCHER", &format!(
        "Installing Minecraft {} with {} loader...",
        payload.minecraft_version, payload.loader
    ));
    if game_dir != minecraft_dir {
        logger::info(&app, &state, "LAUNCHER", &format!(
            "Version files (jar/loader) will be shared from {} — mods, resourcepacks, saves, and config for this instance will live in {}",
            minecraft_dir.display(), game_dir.display()
        ));
    }

    // If this install is actually replacing an existing instance (its
    // Minecraft version and/or loader changed, so it has to be
    // reinstalled), free up that old instance's version folder and tracked
    // entry *now* — before picking a folder name for the new install —
    // so the new install can reclaim the exact same instance-named folder
    // instead of finding it "taken" and falling back to "<name> (2)".
    if let Some(old_id) = payload.old_version_id.clone() {
        let old_is_loader = {
            let instances = state.instances.lock().unwrap();
            instances
                .iter()
                .find(|i| i.version_id == old_id)
                .map(|i| !(i.loader.trim().is_empty() || i.loader.eq_ignore_ascii_case("vanilla")))
        };
        // Vanilla folders are shared infrastructure other instances may
        // depend on (see the comment above `reuse_source_id` below) —
        // never delete those, only an old *loader* instance's own folder.
        if old_is_loader == Some(true) {
            let old_dir = minecraft_dir.join("versions").join(&old_id);
            if old_dir.is_dir() {
                let _ = std::fs::remove_dir_all(&old_dir);
            }
        }
        {
            let mut instances = state.instances.lock().unwrap();
            instances.retain(|i| i.version_id != old_id);
        }
        state.save_instances();
    }

    let mc_version = payload.minecraft_version.clone();
    let loader_type = payload.loader.clone();
    let loader_ver = payload.loader_version.clone();
    // The installer (`Launcher`) is always rooted at the shared default
    // Minecraft directory, never the custom instance directory — this is
    // what makes `versions/`, `libraries/`, and `assets/` land in the
    // default `.minecraft` folder regardless of where the instance's own
    // game directory is.
    let dir = minecraft_dir.clone();

    // Separate clones kept alive for use *after* the spawn_blocking closure
    // below (which moves `mc_version` / `loader_type` into itself).
    let mc_version_outer = mc_version.clone();
    let loader_type_outer = loader_type.clone();

    // Reset any stale pause/cancel flags from a previous run before we start.
    state.download_paused.store(false, Ordering::Relaxed);
    state.download_cancelled.store(false, Ordering::Relaxed);

    let download_id = uuid::Uuid::new_v4().to_string();
    let label = if loader_type == "vanilla" || loader_type.is_empty() {
        mc_version.clone()
    } else {
        format!("{mc_version} ({loader_type})")
    };

    // This instance's eventual display name — used below to name its
    // version folder, so decide it now rather than after the install.
    let display_name = payload.name.clone().filter(|n| !n.trim().is_empty()).unwrap_or_else(|| label.clone());
    let versions_dir = minecraft_dir.join("versions");
    let is_loader = !(loader_type == "vanilla" || loader_type.is_empty());

    // If another tracked instance already has this exact (minecraft_version,
    // loader, loader_version) combo downloaded, copy its version folder
    // instead of hitting the network again. Vanilla is excluded: its folder
    // is shared, unrenamed infrastructure that every loader install depends
    // on, so it never needs (or gets) a duplicate copy.
    let reuse_source_id: Option<String> = if is_loader {
        let instances = state.instances.lock().unwrap();
        instances
            .iter()
            .find(|i| {
                i.minecraft_version == mc_version
                    && i.loader == loader_type
                    && i.loader_version == loader_ver
                    && versions_dir.join(&i.version_id).is_dir()
            })
            .map(|i| i.version_id.clone())
    } else {
        None
    };

    if let Some(src_id) = reuse_source_id {
        let target_id = unique_version_folder_name(
            &versions_dir,
            &sanitize_instance_folder_name(&display_name),
            None,
        );
        let versions_dir_for_copy = versions_dir.clone();
        let src_id_for_copy = src_id.clone();
        let target_id_for_copy = target_id.clone();
        let copy_result = tokio::task::spawn_blocking(move || {
            copy_version_folder(&versions_dir_for_copy, &src_id_for_copy, &target_id_for_copy)
        })
        .await
        .map_err(|e| format!("Copy task failed: {e}"))?;

        match copy_result {
            Ok(()) => {
                logger::info(&app, &state, "LAUNCHER", &format!(
                    "Reusing already-downloaded {} ({}) from '{}' for '{}' — no download needed",
                    mc_version, loader_type, src_id, target_id
                ));

                let info = DownloadProgressInfo {
                    id: download_id.clone(),
                    label: label.clone(),
                    minecraft_version: mc_version.clone(),
                    loader: loader_type.clone(),
                    stage: "Completed".to_string(),
                    current_file: String::new(),
                    downloaded_bytes: 0,
                    total_bytes: None,
                    percent: 100.0,
                    speed_bps: 0.0,
                    eta_seconds: Some(0),
                    status: "completed".to_string(),
                    message: Some("Reused existing install".to_string()),
                };
                let _ = app.emit("download-progress", &info);

                let instance = InstalledInstance {
                    name: display_name,
                    version_id: target_id,
                    minecraft_version: payload.minecraft_version.clone(),
                    loader: payload.loader.clone(),
                    loader_version: payload.loader_version.clone(),
                    directory: game_dir.to_string_lossy().to_string(),
                    minecraft_directory: minecraft_dir.to_string_lossy().to_string(),
                    installed_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
                };

                {
                    let mut instances = state.instances.lock().unwrap();
                    instances.retain(|i| i.version_id != instance.version_id);
                    instances.push(instance.clone());
                }
                state.save_instances();

                // Best-effort Java setup, same as the normal install path.
                {
                    use mc_launcher_core::prelude::*;
                    let dir_for_java = minecraft_dir.clone();
                    let vid_for_java = instance.version_id.clone();
                    let version_for_java = tokio::task::spawn_blocking(move || {
                        Launcher::new(&dir_for_java).load_version(&vid_for_java)
                    })
                    .await
                    .ok()
                    .and_then(|r| r.ok());

                    if let Some(version) = version_for_java {
                        if let Err(e) = crate::commands::java::ensure_java_for_version(&app, &state, &version).await {
                            logger::warn(&app, &state, "LAUNCHER", &format!(
                                "Couldn't prepare Java for {} yet (will retry at launch): {e}",
                                instance.version_id
                            ));
                        }
                    }
                }

                return Ok(instance);
            }
            Err(e) => {
                logger::warn(&app, &state, "LAUNCHER", &format!(
                    "Failed to reuse existing install ({e}), downloading fresh instead"
                ));
                // Fall through to the normal download path below.
            }
        }
    }

    let app_for_progress = app.clone();
    let id_for_progress = download_id.clone();
    let label_for_progress = label.clone();
    let mc_version_for_progress = mc_version.clone();
    let loader_for_progress = loader_type.clone();
    // Shared byte counter so the final "completed" event (emitted *after*
    // the spawn_blocking install closure returns) can report the real total
    // downloaded instead of hardcoding 0.
    let total_bytes_downloaded_outer = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
    let total_bytes_downloaded_for_progress = total_bytes_downloaded_outer.clone();

    // Run blocking install in a separate thread
    let install_outcome = tokio::task::spawn_blocking(move || {
        let launcher = Launcher::new(&dir);

        let build_loader = || match loader_type.as_str() {
            "fabric" => Some(LoaderSpec::Fabric {
                version: if loader_ver == "latest" {
                    LoaderVersion::LatestStable
                } else {
                    LoaderVersion::Exact(loader_ver.clone())
                },
            }),
            "quilt" => Some(LoaderSpec::Quilt {
                version: if loader_ver == "latest" {
                    LoaderVersion::LatestStable
                } else {
                    LoaderVersion::Exact(loader_ver.clone())
                },
            }),
            "forge" => Some(LoaderSpec::Forge {
                version: if loader_ver == "latest" {
                    LoaderVersion::LatestStable
                } else {
                    LoaderVersion::Exact(loader_ver.clone())
                },
            }),
            "neoforge" => Some(LoaderSpec::NeoForge {
                version: if loader_ver == "latest" {
                    LoaderVersion::LatestStable
                } else {
                    LoaderVersion::Exact(loader_ver.clone())
                },
            }),
            _ => None, // vanilla
        };

        let build_request = || InstallRequest {
            minecraft_version: mc_version.clone(),
            loader: build_loader(),
            java: JavaInstallPolicy::Auto,
        };

        // ── Progress tracking state, captured by the reporter closure ──
        use mc_launcher_core::progress::InstallStage;
        let mut current_stage = InstallStage::ResolveVersion;
        let mut current_stage_label = String::from("Preparing");
        let mut current_file = String::new();
        let mut current_task_received: u64 = 0;
        let mut current_task_total: Option<u64> = None;
        let mut per_label_last_received: HashMap<String, u64> = HashMap::new();
        let mut cumulative_bytes: u64 = 0;
        // Reset every time we enter a new stage, since each stage now owns
        // its own fixed slice of the overall bar (see `stage_weight`).
        let mut tasks_started: u64 = 0;
        let mut tasks_done: u64 = 0;
        let mut speed_window_start = Instant::now();
        let mut speed_window_bytes: u64 = 0;
        let mut speed_bps: f64 = 0.0;
        let mut last_emit = Instant::now();
        // Overall elapsed-time-vs-percent extrapolation is far more robust
        // than a per-file byte estimate (most files are far too small/fast
        // to ever report a meaningful "total" before they finish), and it
        // naturally accounts for every stage, not just raw downloads.
        let install_start = Instant::now();
        // The bar must never move backwards, so remember the highest
        // percent we've reported so far and never drop below it.
        let mut best_percent: f64 = 0.0;
        // Shared with the code *after* `install_with_progress` returns (via
        // `total_bytes_downloaded_outer`, cloned before this closure took
        // ownership of everything else), so the final "completed" event can
        // report the real total instead of hardcoding 0 bytes downloaded.
        let total_bytes_downloaded = total_bytes_downloaded_for_progress;

        let mut reporter = move |event: mc_launcher_core::progress::ProgressEvent| {
            use mc_launcher_core::progress::ProgressEvent as PE;

            let app_state = app_for_progress.state::<AppState>();

            // Cancellation check (also unblocks a paused download).
            if app_state.download_cancelled.load(Ordering::Relaxed) {
                std::panic::panic_any(CANCEL_MARKER.to_string());
            }
            // Genuine pause: block this thread (the library's own download
            // loop) until resumed or cancelled, so no bytes move while paused.
            while app_state.download_paused.load(Ordering::Relaxed) {
                let info = DownloadProgressInfo {
                    id: id_for_progress.clone(),
                    label: label_for_progress.clone(),
                    minecraft_version: mc_version_for_progress.clone(),
                    loader: loader_for_progress.clone(),
                    stage: current_stage_label.clone(),
                    current_file: current_file.clone(),
                    downloaded_bytes: cumulative_bytes,
                    total_bytes: current_task_total,
                    percent: best_percent.max(overall_percent(&current_stage, tasks_started, tasks_done, current_task_received, current_task_total)),
                    speed_bps: 0.0,
                    eta_seconds: None,
                    status: "paused".to_string(),
                    message: None,
                };
                let _ = app_for_progress.emit("download-progress", &info);
                std::thread::sleep(std::time::Duration::from_millis(250));
                if app_state.download_cancelled.load(Ordering::Relaxed) {
                    std::panic::panic_any(CANCEL_MARKER.to_string());
                }
            }

            match event {
                PE::StageStarted { stage } => {
                    current_stage = stage.clone();
                    current_stage_label = format!("{stage:?}");
                    // Each stage owns its own slice of the overall bar, so
                    // task counters restart clean instead of diluting into
                    // an ever-growing global denominator.
                    tasks_started = 0;
                    tasks_done = 0;
                    current_task_received = 0;
                    current_task_total = None;
                }
                PE::TaskStarted { label, path } => {
                    tasks_started += 1;
                    current_file = path
                        .file_name()
                        .map(|f| f.to_string_lossy().to_string())
                        .unwrap_or(label);
                    current_task_received = 0;
                    current_task_total = None;
                }
                PE::TaskSkipped { .. } => {
                    tasks_done += 1;
                }
                PE::TaskFinished { .. } => {
                    tasks_done += 1;
                    current_task_received = 0;
                    current_task_total = None;
                }
                PE::BytesReceived { label, received, total } => {
                    let prev = per_label_last_received.insert(label.clone(), received).unwrap_or(0);
                    let delta = received.saturating_sub(prev);
                    cumulative_bytes += delta;
                    total_bytes_downloaded.fetch_add(delta, Ordering::Relaxed);
                    current_task_received = received;
                    current_task_total = total;
                }
            }

            // Update a smoothed throughput sample roughly every 500ms.
            let now = Instant::now();
            if now.duration_since(speed_window_start).as_millis() >= 500 {
                let elapsed = now.duration_since(speed_window_start).as_secs_f64().max(0.001);
                speed_bps = (cumulative_bytes.saturating_sub(speed_window_bytes)) as f64 / elapsed;
                speed_window_start = now;
                speed_window_bytes = cumulative_bytes;
            }

            // Throttle UI emits to ~10/sec so we don't flood IPC.
            if now.duration_since(last_emit).as_millis() < 100 {
                return;
            }
            last_emit = now;

            let raw_percent = overall_percent(&current_stage, tasks_started, tasks_done, current_task_received, current_task_total);
            // Never let the bar (or anything derived from it, like ETA)
            // move backwards — clamp to the best we've seen so far.
            best_percent = best_percent.max(raw_percent);
            let percent = best_percent;

            // Extrapolate remaining time from elapsed time vs. percent
            // complete, rather than the current file's own byte total —
            // most files are small enough to finish before they ever
            // report a "total", which used to leave the ETA stuck on "—"
            // for almost the whole install.
            let elapsed = install_start.elapsed().as_secs_f64();
            let eta = if percent >= 1.0 && percent < 99.5 && elapsed > 1.0 {
                let total_estimated = elapsed / (percent / 100.0);
                Some((total_estimated - elapsed).max(0.0).round() as u64)
            } else {
                None
            };

            let info = DownloadProgressInfo {
                id: id_for_progress.clone(),
                label: label_for_progress.clone(),
                minecraft_version: mc_version_for_progress.clone(),
                loader: loader_for_progress.clone(),
                stage: current_stage_label.clone(),
                current_file: current_file.clone(),
                downloaded_bytes: cumulative_bytes,
                total_bytes: current_task_total,
                percent,
                speed_bps,
                eta_seconds: eta,
                status: "downloading".to_string(),
                message: None,
            };
            let _ = app_for_progress.emit("download-progress", &info);
        };

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            // Mojang's metadata/asset servers occasionally hiccup on a single
            // request (DNS blip, connection reset, brief timeout) — retry a
            // couple of times on what look like transient network errors
            // before giving up, instead of failing the whole install over
            // one flaky request.
            const MAX_ATTEMPTS: u32 = 3;
            let mut attempt: u32 = 0;
            loop {
                attempt += 1;
                let request = build_request();
                match launcher.install_with_progress(request, &mut reporter) {
                    Ok(v) => return Ok(v),
                    Err(e) => {
                        let msg = e.to_string();
                        let looks_transient = msg.contains("network error")
                            || msg.contains("error sending request")
                            || msg.contains("timed out")
                            || msg.contains("connection")
                            || msg.contains("dns");
                        if looks_transient && attempt < MAX_ATTEMPTS {
                            std::thread::sleep(std::time::Duration::from_secs(2 * attempt as u64));
                            continue;
                        }
                        return Err(e);
                    }
                }
            }
        }));

        match result {
            Ok(inner) => inner.map_err(|e| format!("Minecraft install failed: {e}")),
            Err(payload) => {
                if payload.downcast_ref::<String>().map(|s| s.as_str() == CANCEL_MARKER).unwrap_or(false) {
                    Err("__CANCELLED__".to_string())
                } else {
                    std::panic::resume_unwind(payload)
                }
            }
        }
    })
    .await
    .map_err(|e| format!("Install task failed: {e}"))?;

    let install_result = match install_outcome {
        Ok(r) => r,
        Err(e) if e == "__CANCELLED__" => {
            let info = DownloadProgressInfo {
                id: download_id.clone(),
                label: label.clone(),
                minecraft_version: mc_version_outer.clone(),
                loader: loader_type_outer.clone(),
                stage: "Cancelled".to_string(),
                current_file: String::new(),
                downloaded_bytes: 0,
                total_bytes: None,
                percent: 0.0,
                speed_bps: 0.0,
                eta_seconds: None,
                status: "cancelled".to_string(),
                message: Some("Installation cancelled".to_string()),
            };
            let _ = app.emit("download-progress", &info);
            logger::info(&app, &state, "LAUNCHER", "Installation cancelled by user");
            return Err("Installation cancelled".to_string());
        }
        Err(e) => {
            let info = DownloadProgressInfo {
                id: download_id.clone(),
                label: label.clone(),
                minecraft_version: mc_version_outer.clone(),
                loader: loader_type_outer.clone(),
                stage: "Error".to_string(),
                current_file: String::new(),
                downloaded_bytes: 0,
                total_bytes: None,
                percent: 0.0,
                speed_bps: 0.0,
                eta_seconds: None,
                status: "error".to_string(),
                message: Some(e.clone()),
            };
            let _ = app.emit("download-progress", &info);
            return Err(e);
        }
    };

    {
        let info = DownloadProgressInfo {
            id: download_id.clone(),
            label: label.clone(),
            minecraft_version: mc_version_outer.clone(),
            loader: loader_type_outer.clone(),
            stage: "Completed".to_string(),
            current_file: String::new(),
            downloaded_bytes: total_bytes_downloaded_outer.load(Ordering::Relaxed),
            total_bytes: None,
            percent: 100.0,
            speed_bps: 0.0,
            eta_seconds: Some(0),
            status: "completed".to_string(),
            message: None,
        };
        let _ = app.emit("download-progress", &info);
    }

    // Give the freshly-downloaded loader folder a human-friendly name that
    // matches this instance instead of leaving it as e.g.
    // `fabric-loader-0.19.3-26.1`. Vanilla installs keep their version_id
    // as-is (see the comment above `reuse_source_id`).
    let final_version_id = if is_loader {
        let fresh_id = install_result.version_id.clone();
        let target_id = unique_version_folder_name(
            &versions_dir,
            &sanitize_instance_folder_name(&display_name),
            Some(fresh_id.as_str()),
        );
        let versions_dir_for_rename = versions_dir.clone();
        let fresh_id_for_rename = fresh_id.clone();
        let target_id_for_rename = target_id.clone();
        let rename_result = tokio::task::spawn_blocking(move || {
            rename_version_folder(&versions_dir_for_rename, &fresh_id_for_rename, &target_id_for_rename)
        })
        .await
        .map_err(|e| format!("Rename task failed: {e}"))?;

        match rename_result {
            Ok(()) => target_id,
            Err(e) => {
                logger::warn(&app, &state, "LAUNCHER", &format!(
                    "Couldn't rename install folder to '{}' ({e}), keeping '{}'",
                    target_id, fresh_id
                ));
                fresh_id
            }
        }
    } else {
        install_result.version_id.clone()
    };

    let instance = InstalledInstance {
        name: display_name,
        version_id: final_version_id,
        minecraft_version: payload.minecraft_version.clone(),
        loader: payload.loader.clone(),
        loader_version: payload.loader_version.clone(),
        directory: game_dir.to_string_lossy().to_string(),
        minecraft_directory: minecraft_dir.to_string_lossy().to_string(),
        installed_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
    };

    // Save instance to state
    {
        let mut instances = state.instances.lock().unwrap();
        // Replace if same version_id exists
        instances.retain(|i| i.version_id != instance.version_id);
        instances.push(instance.clone());
    }
    state.save_instances();

    logger::info(&app, &state, "LAUNCHER", &format!(
        "✓ Successfully installed {}",
        instance.version_id
    ));

    // Best-effort: make sure a suitable Java is ready for this instance
    // right away (downloading one via Smart Java Detection if needed),
    // instead of waiting until the user hits Play. Failures here don't
    // fail the install — launch will simply retry Java setup then.
    {
        // Version files (and thus the version JSON we need to load here)
        // live under `minecraft_dir`, not the instance's own `game_dir`.
        let dir_for_java = minecraft_dir.clone();
        let vid_for_java = instance.version_id.clone();
        let version_for_java = tokio::task::spawn_blocking(move || {
            Launcher::new(&dir_for_java).load_version(&vid_for_java)
        })
        .await
        .ok()
        .and_then(|r| r.ok());

        if let Some(version) = version_for_java {
            if let Err(e) = crate::commands::java::ensure_java_for_version(&app, &state, &version).await {
                logger::warn(&app, &state, "LAUNCHER", &format!(
                    "Couldn't prepare Java for {} yet (will retry at launch): {e}",
                    instance.version_id
                ));
            }
        }
    }

    Ok(instance)
}

/// Launch a Minecraft instance.
#[tauri::command]
pub async fn launch_minecraft(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    version_id: String,
) -> Result<(), String> {
    use mc_launcher_core::prelude::*;

    // Only block re-launching the *same* instance while it's already
    // running; different instances are allowed to run side by side, each
    // tracked independently with its own console.
    {
        let running_instances = state.running_instances.lock().unwrap();
        if running_instances
            .get(&version_id)
            .map(|i| i.running)
            .unwrap_or(false)
        {
            return Err("This instance is already running".to_string());
        }
    }

    // Get active account
    let username = {
        let accounts = state.accounts.lock().unwrap();
        accounts
            .iter()
            .find(|a| a.is_active)
            .map(|a| a.username.clone())
            .ok_or_else(|| "No active account. Please add an account first.".to_string())?
    };

    // Use whichever directory this instance was actually installed into (if
    // we're tracking it), so a custom per-instance directory is respected;
    // otherwise fall back to the globally configured game directory.
    let already_tracked = {
        let instances = state.instances.lock().unwrap();
        instances.iter().any(|i| i.version_id == version_id)
    };

    // `game_dir` is the instance's own directory (mods/saves/resourcepacks/
    // config/logs — a custom path if one was chosen at install time).
    // `minecraft_dir` is where `versions/`, `libraries/`, and `assets/`
    // actually live, which is always the default Minecraft directory for
    // any instance installed after the split, and the same as `game_dir`
    // for older/untracked instances.
    let (game_dir, minecraft_dir) = {
        let instances = state.instances.lock().unwrap();
        instances
            .iter()
            .find(|i| i.version_id == version_id)
            .map(|i| (PathBuf::from(&i.directory), PathBuf::from(i.minecraft_dir())))
    }
    .unwrap_or_else(|| {
        let default_dir = PathBuf::from(&state.settings.lock().unwrap().game_directory);
        (default_dir.clone(), default_dir)
    });

    // If this instance isn't in our tracked list yet (e.g. it was installed
    // by another launcher, dropped into `versions/` manually, or otherwise
    // only ever showed up via the on-disk scan), register it now so it
    // persists in `instances.json` like any other install.
    if !already_tracked {
        let version_json_path = minecraft_dir.join("versions").join(&version_id).join(format!("{version_id}.json"));
        let (minecraft_version, loader) = match std::fs::read_to_string(&version_json_path) {
            Ok(contents) => {
                let parsed: serde_json::Value = serde_json::from_str(&contents).unwrap_or_default();
                let inherits = parsed.get("inheritsFrom").and_then(|v| v.as_str()).map(String::from);
                let mc_ver = parsed
                    .get("id")
                    .and_then(|v| v.as_str())
                    .filter(|_| inherits.is_none())
                    .map(String::from)
                    .or_else(|| inherits.clone())
                    .unwrap_or_else(|| version_id.clone());
                let lower_id = version_id.to_lowercase();
                let loader = if inherits.is_some() {
                    if lower_id.contains("fabric") {
                        "fabric"
                    } else if lower_id.contains("quilt") {
                        "quilt"
                    } else if lower_id.contains("neoforge") {
                        "neoforge"
                    } else if lower_id.contains("forge") {
                        "forge"
                    } else {
                        "unknown"
                    }
                } else {
                    "vanilla"
                }
                .to_string();
                (mc_ver, loader)
            }
            Err(_) => (version_id.clone(), "unknown".to_string()),
        };

        let new_instance = InstalledInstance {
            name: version_id.clone(),
            version_id: version_id.clone(),
            minecraft_version,
            loader,
            loader_version: String::new(),
            directory: game_dir.to_string_lossy().to_string(),
            minecraft_directory: minecraft_dir.to_string_lossy().to_string(),
            installed_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
        };

        {
            let mut instances = state.instances.lock().unwrap();
            instances.retain(|i| i.version_id != new_instance.version_id);
            instances.push(new_instance);
        }
        state.save_instances();

        logger::info(&app, &state, "LAUNCHER", &format!(
            "Registered previously untracked instance {} in instances.json",
            version_id
        ));
    }
    let jvm_args_str = state.settings.lock().unwrap().jvm_args.clone();
    let max_ram = state.settings.lock().unwrap().max_ram_mb;
    let min_ram = state.settings.lock().unwrap().min_ram_mb;

    // Snapshot the instance's display info now, for the running-instances
    // list and per-instance console window title.
    let (inst_name, inst_mc_version, inst_loader) = {
        let instances = state.instances.lock().unwrap();
        instances
            .iter()
            .find(|i| i.version_id == version_id)
            .map(|i| {
                (
                    if i.name.trim().is_empty() { i.version_id.clone() } else { i.name.clone() },
                    i.minecraft_version.clone(),
                    i.loader.clone(),
                )
            })
            .unwrap_or_else(|| (version_id.clone(), version_id.clone(), "unknown".to_string()))
    };

    logger::info(&app, &state, "LAUNCHER", &format!(
        "Launching {} as {}...", version_id, username
    ));

    let vid = version_id.clone();
    // `mc_dir` is where `versions/`, `libraries/`, and `assets/` live (the
    // shared default Minecraft directory) — this is what `Launcher` needs
    // to find and read the version's files, regardless of where this
    // instance's own game directory is.
    let mc_dir = minecraft_dir.clone();
    let dir = game_dir.clone();
    let user = username.clone();

    // Load the version metadata first (blocking, but fast — just reads the
    // version json off disk). We need it before we can figure out which
    // Java this instance actually requires.
    let mc_dir_for_version = mc_dir.clone();
    let vid_for_version = vid.clone();
    let version = tokio::task::spawn_blocking(move || {
        let launcher = Launcher::new(&mc_dir_for_version);
        launcher
            .load_version(&vid_for_version)
            .map_err(|e| format!("Failed to load version: {e}"))
    })
    .await
    .map_err(|e| format!("Launch task failed: {e}"))??;

    // Smart Java Detection: use the user's manually-selected Java if one is
    // set in Settings, otherwise figure out which Java major version this
    // instance needs, reuse a matching install if we have one, or download
    // it automatically (via Azul) into the managed java/ folder.
    let java_executable = crate::commands::java::ensure_java_for_version(&app, &state, &version)
        .await
        .map_err(|e| format!("Java setup failed: {e}"))?;
    logger::info(&app, &state, "LAUNCHER", &format!(
        "Using Java: {}", java_executable.display()
    ));

    // Build launch command in blocking context
    let launch_cmd = tokio::task::spawn_blocking(move || {
        // Rooted at `mc_dir` so libraries/assets/natives are resolved from
        // the shared default Minecraft directory...
        let launcher = Launcher::new(&mc_dir);
        launcher
            .build_launch_command_from_version(
                &version,
                LaunchOptions {
                    account: Account::offline(&user),
                    java_executable: Some(java_executable),
                    // ...while `--gameDir` (and the process's working
                    // directory, set further down) points at `dir`, this
                    // instance's own directory, so mods, saves,
                    // resourcepacks, config, and logs live there — the
                    // default location, or the custom path chosen at
                    // install time.
                    game_directory: Some(dir.clone()),
                    ..Default::default()
                },
            )
            .map_err(|e| format!("Failed to build launch command: {e}"))
    })
    .await
    .map_err(|e| format!("Launch task failed: {e}"))??;

    // Build JVM arguments
    let mut args: Vec<String> = Vec::new();
    args.push(format!("-Xmx{}m", max_ram));
    args.push(format!("-Xms{}m", min_ram));
    if !jvm_args_str.is_empty() {
        args.extend(jvm_args_str.split_whitespace().map(String::from));
    }
    args.extend(launch_cmd.args.clone());

    // Debug-only, and subject to two Privacy settings:
    //  - "Hide Launch Command from Logs" skips this line entirely
    //  - "Redact Auth Tokens in Logs" strips the offline session's
    //    --accessToken/--uuid values out of what's left
    let hide_cmd = state.settings.lock().map(|s| s.hide_launch_command).unwrap_or(true);
    if !hide_cmd {
        logger::debug(&app, &state, "LAUNCHER", &format!(
            "Command: {} {}",
            launch_cmd.executable.display(),
            logger::redact_sensitive(&state, &args.join(" "))
        ));
    }

    // Spawn the game process. Intentionally use `game_dir` (the instance's
    // own root, e.g. `.minecraft`) as the working directory instead of
    // whatever `launch_cmd.working_dir` reports — Minecraft creates
    // saves/, resourcepacks/, mods/, logs/, config/, etc. relative to the
    // process's current directory, and those belong alongside `versions/`
    // at the instance root, not inside `versions/<id>/`.
    let mut launch_command = tokio::process::Command::new(&launch_cmd.executable);
    launch_command
        .args(&args)
        .current_dir(&game_dir)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        launch_command.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = launch_command
        .spawn()
        .map_err(|e| format!("Failed to start game: {e}"))?;

    // Mark this instance as running (kept in the map after exit too, with
    // `running: false`, so its console history stays reachable this
    // session).
    let pid = child.id();
    let name_done = inst_name.clone();
    let loader_done = inst_loader.clone();
    {
        let mut running_instances = state.running_instances.lock().unwrap();
        running_instances.insert(
            version_id.clone(),
            RunningInstanceInfo {
                version_id: version_id.clone(),
                name: inst_name,
                minecraft_version: inst_mc_version,
                loader: inst_loader,
                pid,
                started_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
                running: true,
            },
        );
    }
    // Fresh console for this run.
    state.instance_logs.lock().unwrap().insert(version_id.clone(), Vec::new());
    let _ = app.emit("running-instances-changed", ());

    logger::info(&app, &state, "LAUNCHER", "Game process started!");

    // Capture stdout
    if let Some(stdout) = child.stdout.take() {
        let app_out = app.clone();
        let vid_out = version_id.clone();
        tokio::spawn(async move {
            let reader = BufReader::new(stdout);
            let mut lines = reader.lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let entry = LogEntry::new("INFO", "GAME", &line);
                let state_out = app_out.state::<AppState>();
                state_out.push_instance_log(&vid_out, entry.clone());
                let _ = app_out.emit("log-entry", &entry);
                let _ = app_out.emit("instance-log", &InstanceLogEvent { version_id: vid_out.clone(), entry });
            }
        });
    }

    // Capture stderr
    if let Some(stderr) = child.stderr.take() {
        let app_err = app.clone();
        let vid_err = version_id.clone();
        tokio::spawn(async move {
            let reader = BufReader::new(stderr);
            let mut lines = reader.lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let entry = LogEntry::new("ERROR", "GAME", &line);
                let state_err = app_err.state::<AppState>();
                state_err.push_instance_log(&vid_err, entry.clone());
                let _ = app_err.emit("log-entry", &entry);
                let _ = app_err.emit("instance-log", &InstanceLogEvent { version_id: vid_err.clone(), entry });
            }
        });
    }

    // Wait for process in background
    let app_done = app.clone();
    let vid_done = version_id.clone();
    let game_dir_done = game_dir.clone();
    tokio::spawn(async move {
        let status = child.wait().await;
        let exit_code = match &status {
            Ok(s) => s.code(),
            Err(_) => None,
        };
        let msg = match status {
            Ok(s) => format!("Game exited with status: {s}"),
            Err(e) => format!("Game process error: {e}"),
        };
        let entry = LogEntry::new("INFO", "LAUNCHER", &msg);
        let state_done = app_done.state::<AppState>();
        state_done.push_instance_log(&vid_done, entry.clone());
        let _ = app_done.emit("log-entry", &entry);
        let _ = app_done.emit("instance-log", &InstanceLogEvent { version_id: vid_done.clone(), entry });

        // Flip this instance's running flag off (entry stays for history).
        if let Some(info) = state_done.running_instances.lock().unwrap().get_mut(&vid_done) {
            info.running = false;
        }
        let _ = app_done.emit("running-instances-changed", ());
        let _ = app_done.emit("game-exited", &msg);

        // Try to diagnose a crash from this instance's console history.
        // Entirely heuristic/offline — see crash_analysis.rs.
        let log_lines: Vec<String> = state_done
            .instance_logs
            .lock()
            .unwrap()
            .get(&vid_done)
            .map(|entries| entries.iter().map(|e| e.message.clone()).collect())
            .unwrap_or_default();

        if let Some(report) = crate::commands::crash_analysis::analyze(
            &vid_done,
            &name_done,
            &game_dir_done,
            &loader_done,
            exit_code,
            &log_lines,
        ) {
            let _ = app_done.emit("game-crashed", &report);
        }
    });

    Ok(())
}

/// List every instance launched this session — both currently running and
/// ones that have since exited (kept around so their console stays
/// reachable). Sorted with running instances first, most recently started.
#[tauri::command]
pub async fn get_running_instances(
    state: State<'_, AppState>,
) -> Result<Vec<RunningInstanceInfo>, String> {
    let map = state.running_instances.lock().unwrap();
    let mut list: Vec<RunningInstanceInfo> = map.values().cloned().collect();
    list.sort_by(|a, b| {
        b.running
            .cmp(&a.running)
            .then_with(|| b.started_at.cmp(&a.started_at))
    });
    Ok(list)
}

/// Forcibly terminate a running instance's game process, identified by the
/// PID captured at launch time.
#[tauri::command]
pub async fn kill_instance(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    version_id: String,
) -> Result<(), String> {
    let (pid, was_running) = {
        let map = state.running_instances.lock().unwrap();
        match map.get(&version_id) {
            Some(info) => (info.pid, info.running),
            None => return Err("Instance is not tracked".to_string()),
        }
    };

    if !was_running {
        return Err("Instance is not currently running".to_string());
    }

    let pid = pid.ok_or_else(|| "No process id recorded for this instance".to_string())?;

    #[cfg(target_os = "windows")]
    let kill_result = {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        std::process::Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/F", "/T"])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
    };

    #[cfg(not(target_os = "windows"))]
    let kill_result = std::process::Command::new("kill")
        .args(["-9", &pid.to_string()])
        .output();

    match kill_result {
        Ok(output) if output.status.success() => {
            logger::info(&app, &state, "LAUNCHER", &format!(
                "Killed instance {} (pid {})", version_id, pid
            ));
            if let Some(info) = state.running_instances.lock().unwrap().get_mut(&version_id) {
                info.running = false;
            }
            let _ = app.emit("running-instances-changed", ());
            Ok(())
        }
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr).to_string();
            Err(format!("Failed to kill process: {}", stderr.trim()))
        }
        Err(e) => Err(format!("Failed to run kill command: {e}")),
    }
}

/// Full console output captured so far for a given instance (used to
/// populate a newly-opened console window before it starts receiving live
/// `instance-log` events).
#[tauri::command]
pub async fn get_instance_console_logs(
    state: State<'_, AppState>,
    version_id: String,
) -> Result<Vec<LogEntry>, String> {
    let logs = state.instance_logs.lock().unwrap();
    Ok(logs.get(&version_id).cloned().unwrap_or_default())
}

/// Pause the currently running download/install, if any.
#[tauri::command]
pub async fn pause_download(state: State<'_, AppState>) -> Result<(), String> {
    state.download_paused.store(true, Ordering::Relaxed);
    Ok(())
}

/// Resume a previously paused download/install.
#[tauri::command]
pub async fn resume_download(state: State<'_, AppState>) -> Result<(), String> {
    state.download_paused.store(false, Ordering::Relaxed);
    Ok(())
}

/// Cancel the currently running download/install, if any.
#[tauri::command]
pub async fn cancel_download(state: State<'_, AppState>) -> Result<(), String> {
    state.download_cancelled.store(true, Ordering::Relaxed);
    // Unblock a paused loop so it can observe the cancellation immediately.
    state.download_paused.store(false, Ordering::Relaxed);
    Ok(())
}

/// Get list of installed instances.
#[tauri::command]
pub async fn get_installed_instances(
    state: State<'_, AppState>,
) -> Result<Vec<InstalledInstance>, String> {
    let instances = state.instances.lock().unwrap().clone();
    Ok(instances)
}

/// List every instance the user has hidden (see `hide_instance`).
#[tauri::command]
pub async fn get_hidden_instances(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    Ok(state.hidden_instances.lock().unwrap().clone())
}

/// Hide an instance from the main Instances list without deleting anything
/// on disk. Used as the safe alternative when deleting a vanilla instance
/// that other (modded) instances still depend on — the files stay put, so
/// nothing that inherits from them breaks, and the instance can be
/// unhidden again later from Settings → Hidden Instances.
#[tauri::command]
pub async fn hide_instance(state: State<'_, AppState>, version_id: String) -> Result<(), String> {
    {
        let mut hidden = state.hidden_instances.lock().unwrap();
        if !hidden.contains(&version_id) {
            hidden.push(version_id);
        }
    }
    state.save_hidden_instances();
    Ok(())
}

/// Unhide a previously-hidden instance so it shows up in the main
/// Instances list again.
#[tauri::command]
pub async fn unhide_instance(state: State<'_, AppState>, version_id: String) -> Result<(), String> {
    {
        let mut hidden = state.hidden_instances.lock().unwrap();
        hidden.retain(|v| v != &version_id);
    }
    state.save_hidden_instances();
    Ok(())
}

/// Every other tracked instance that would need `version_id` reinstalled if
/// it were deleted — i.e. any instance on the same Minecraft version whose
/// mod loader's version JSON inherits from the vanilla client
/// (`versions/<minecraft_version>/`). Only meaningful when `version_id`
/// itself is a vanilla install; a modded instance never has dependents.
#[tauri::command]
pub async fn get_dependent_instances(
    state: State<'_, AppState>,
    version_id: String,
) -> Result<Vec<InstalledInstance>, String> {
    let instances = state.instances.lock().unwrap().clone();
    let Some(target) = instances.iter().find(|i| i.version_id == version_id) else {
        return Ok(Vec::new());
    };
    let is_vanilla = target.loader.trim().is_empty() || target.loader.eq_ignore_ascii_case("vanilla");
    if !is_vanilla {
        return Ok(Vec::new());
    }
    let target_minecraft_version = target.minecraft_version.clone();
    let dependents: Vec<InstalledInstance> = instances
        .into_iter()
        .filter(|i| {
            i.version_id != version_id
                && i.minecraft_version == target_minecraft_version
                && !(i.loader.trim().is_empty() || i.loader.eq_ignore_ascii_case("vanilla"))
        })
        .collect();
    Ok(dependents)
}

/// Remove an installed instance from the tracked list.
#[tauri::command]
pub async fn remove_instance(
    state: State<'_, AppState>,
    version_id: String,
) -> Result<(), String> {
    {
        let mut instances = state.instances.lock().unwrap();
        instances.retain(|i| i.version_id != version_id);
    }
    state.save_instances();
    Ok(())
}

/// Edit an already-tracked instance's display name and/or loader version
/// override. Both fields are optional — `None` leaves the existing value
/// untouched. Used by the "Edit Instance" UI, which only exposes fields
/// that are safe to change without reinstalling (the Minecraft version
/// and mod loader themselves are baked into the installed files).
#[tauri::command]
pub async fn update_instance(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    version_id: String,
    name: Option<String>,
    loader_version: Option<String>,
) -> Result<InstalledInstance, String> {
    // Renaming a loader instance also renames its on-disk version folder
    // (`versions/<old id>` -> `versions/<new id>`) so the folder keeps
    // matching the instance's display name. Vanilla instances keep their
    // version_id as the Minecraft version — see the comment above
    // `reuse_source_id` in `install_minecraft` for why. Figure out the
    // rename up front, before touching `state.instances`, so we're never
    // left with a folder rename that succeeded but state that didn't (or
    // vice versa).
    let rename_plan: Option<(PathBuf, String, String)> = {
        let instances = state.instances.lock().unwrap();
        let inst = instances
            .iter()
            .find(|i| i.version_id == version_id)
            .ok_or_else(|| format!("Instance '{}' not found", version_id))?;
        let is_loader = !(inst.loader.trim().is_empty() || inst.loader.eq_ignore_ascii_case("vanilla"));
        match &name {
            Some(n) if is_loader && !n.trim().is_empty() && n.trim() != inst.name => {
                let versions_dir = PathBuf::from(inst.minecraft_dir()).join("versions");
                let new_id = unique_version_folder_name(
                    &versions_dir,
                    &sanitize_instance_folder_name(n.trim()),
                    Some(inst.version_id.as_str()),
                );
                if new_id != inst.version_id {
                    Some((versions_dir, inst.version_id.clone(), new_id))
                } else {
                    None
                }
            }
            _ => None,
        }
    };

    let new_version_id = if let Some((versions_dir, old_id, new_id)) = rename_plan.clone() {
        let versions_dir_for_rename = versions_dir.clone();
        let old_id_for_rename = old_id.clone();
        let new_id_for_rename = new_id.clone();
        tokio::task::spawn_blocking(move || {
            rename_version_folder(&versions_dir_for_rename, &old_id_for_rename, &new_id_for_rename)
        })
        .await
        .map_err(|e| format!("Rename task failed: {e}"))?
        .map_err(|e| format!("Failed to rename version folder: {e}"))?;

        logger::info(&app, &state, "LAUNCHER", &format!(
            "Renamed instance folder '{}' -> '{}'", old_id, new_id
        ));
        Some(new_id)
    } else {
        None
    };

    let updated = {
        let mut instances = state.instances.lock().unwrap();
        let inst = instances
            .iter_mut()
            .find(|i| i.version_id == version_id)
            .ok_or_else(|| format!("Instance '{}' not found", version_id))?;
        if let Some(n) = name {
            let trimmed = n.trim();
            if !trimmed.is_empty() {
                inst.name = trimmed.to_string();
            }
        }
        if let Some(lv) = loader_version {
            inst.loader_version = lv.trim().to_string();
        }
        if let Some(ref nvid) = new_version_id {
            inst.version_id = nvid.clone();
        }
        inst.clone()
    };
    state.save_instances();

    // Re-key everything else that was tracking the old version_id so it
    // doesn't silently go stale after the rename.
    if let Some((_, old_id, new_id)) = rename_plan {
        {
            let mut hidden = state.hidden_instances.lock().unwrap();
            for h in hidden.iter_mut() {
                if *h == old_id {
                    *h = new_id.clone();
                }
            }
        }
        state.save_hidden_instances();

        {
            let mut logs = state.instance_logs.lock().unwrap();
            if let Some(l) = logs.remove(&old_id) {
                logs.insert(new_id.clone(), l);
            }
        }
        {
            let mut running = state.running_instances.lock().unwrap();
            if let Some(r) = running.remove(&old_id) {
                running.insert(new_id, r);
            }
        }
    }

    Ok(updated)
}

/// Delete an installed version entirely: removes it from the tracked
/// instance list AND deletes its actual folder on disk
/// (`<game_dir>/versions/<version_id>`), so it also disappears from a
/// subsequent `scan_minecraft_versions` scan.
#[tauri::command]
pub async fn delete_installed_version(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    version_id: String,
    directory: Option<String>,
) -> Result<(), String> {
    // Version files live under the instance's `minecraft_directory` (the
    // shared default Minecraft directory), not necessarily its own
    // `directory` — those are the same for instances installed at the
    // default location, but differ for ones installed to a custom path.
    // Prefer that if we have this instance tracked; otherwise fall back to
    // whatever was passed in or the globally configured game directory.
    //
    // Note this deletes from the *shared* location, so if another instance
    // is using the same version/loader combo, it loses those files too —
    // the same as deleting a version from `.minecraft/versions` would
    // affect every profile that uses it in the vanilla launcher.
    let tracked_dir = {
        let instances = state.instances.lock().unwrap();
        instances
            .iter()
            .find(|i| i.version_id == version_id)
            .map(|i| i.minecraft_dir())
    };
    let game_dir = tracked_dir
        .or(directory)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(&state.settings.lock().unwrap().game_directory));

    let version_folder = game_dir.join("versions").join(&version_id);

    // Basic safety check: never delete outside a "versions" folder, and
    // never delete the versions folder itself (only a specific subfolder).
    if version_folder.file_name().is_none()
        || version_folder.parent().map(|p| p.file_name() != Some(std::ffi::OsStr::new("versions"))).unwrap_or(true)
    {
        return Err("Refusing to delete: unexpected path".to_string());
    }

    if version_folder.is_dir() {
        std::fs::remove_dir_all(&version_folder)
            .map_err(|e| format!("Failed to delete version folder: {e}"))?;
    }

    {
        let mut instances = state.instances.lock().unwrap();
        instances.retain(|i| i.version_id != version_id);
    }
    state.save_instances();

    logger::info(&app, &state, "LAUNCHER", &format!(
        "Deleted version folder for {version_id}"
    ));

    Ok(())
}