use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::time::Instant;
use serde::{Deserialize, Serialize};
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
/// Within the current stage we blend "tasks finished in this stage" with
/// how far the files *currently* in flight have gotten, then scale that
/// fraction into the stage's fixed slice of the overall bar (see
/// `stage_weight`). This keeps the bar moving at a believable, steady pace
/// instead of jumping around as new tasks are discovered.
///
/// `in_flight_frac_sum` is the sum of `received / total` across every file
/// currently downloading whose size is known (0 for files still waiting on
/// a `Content-Length`, and 0 overall if none of them have reported a size
/// yet) — with several files downloading at once, this is a sum rather
/// than a single file's fraction, so multiple large in-flight files each
/// still visibly nudge the bar instead of only the most recently-started
/// one mattering.
fn overall_percent(
    stage: &mc_launcher_core::progress::InstallStage,
    tasks_started: u64,
    tasks_done: u64,
    in_flight_frac_sum: f64,
) -> f64 {
    let weight = stage_weight(stage);
    let offset = stage_offset(stage);

    let stage_frac = if tasks_started == 0 {
        0.0
    } else {
        let base = tasks_done as f64 / tasks_started as f64;
        // Blend in-flight files' progress as a fraction of "one more task"
        // each, so large files (e.g. the client jar, a big mod loader
        // installer) still move the bar smoothly instead of sitting still
        // until they land.
        (base + (in_flight_frac_sum.max(0.0) / (tasks_started.max(tasks_done + 1)) as f64)).clamp(0.0, 1.0)
    };

    (offset + stage_frac * weight).clamp(0.0, 99.5)
}

/// Fetch the Mojang version manifest.
#[tauri::command]
pub async fn get_available_versions() -> Result<Vec<VersionInfo>, String> {
    let client = reqwest::Client::builder()
        .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
        .connect_timeout(std::time::Duration::from_secs(10))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());
    let manifest: VersionManifest = client
        .get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        .send()
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
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

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
    let minecraft_dir = state.settings.lock().unwrap().resolved_game_directory();

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

    // ── Ensure a known-good managed Java is ready *before* we run a
    // Forge/NeoForge installer jar ────────────────────────────────────────
    //
    // Forge/NeoForge installers run their own "processor" step that
    // recompresses parts of the vanilla client jar and checks the result
    // against precomputed hashes. Some Linux distros ship `zlib-ng-compat`
    // as the system libz, which the JVM's zip handling picks up and which
    // produces different (but still valid) compressed bytes than stock
    // zlib — the installer's processor then sees a "hash mismatch" even
    // though nothing is actually corrupt (see
    // https://github.com/MinecraftForge/Installer/issues/80). Azul's Zulu
    // builds — what this launcher's own Smart Java Detection downloads —
    // bundle their own zlib and aren't affected, but that managed JRE was
    // previously only ensured to exist *after* install finished (to get
    // ready for the next launch), not before running the loader installer
    // itself. Do it here too so Forge/NeoForge always run on a JRE that
    // can't hit this class of bug, instead of whatever system Java happens
    // to be first on PATH/JAVA_HOME.
    let is_loader_install = !(loader_type == "vanilla" || loader_type.is_empty());
    let ensured_java_executable: Option<PathBuf> = if is_loader_install {
        let mc_version_for_java = mc_version.clone();
        let vanilla_version = tokio::task::spawn_blocking(move || {
            mc_launcher_core::install::client::fetch_vanilla_version(&mc_version_for_java)
        })
        .await
        .ok()
        .and_then(|r| r.ok());

        match vanilla_version {
            Some(version) => {
                match crate::commands::java::ensure_java_for_version(&app, &state, &version, false).await {
                    Ok(path) => Some(path),
                    Err(e) => {
                        logger::warn(&app, &state, "LAUNCHER", &format!(
                            "Couldn't prepare a managed Java for the {loader_type} installer ({e}) — falling back to autodetection, which may hit https://github.com/MinecraftForge/Installer/issues/80 on some distros"
                        ));
                        crate::commands::java::find_any_managed_java()
                    }
                }
            }
            None => crate::commands::java::find_any_managed_java(),
        }
    } else {
        None
    };

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
                    active_files: Vec::new(),
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
                    total_playtime_seconds: 0,
                    last_played_at: None,
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
                        if let Err(e) = crate::commands::java::ensure_java_for_version(&app, &state, &version, false).await {
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
            // Prefer the managed JRE we resolved (and downloaded if
            // necessary) up front, before this closure started, so
            // Forge/NeoForge installers run on a known-good Java instead
            // of risking a system Java linked against zlib-ng, which is
            // known to produce hash mismatches during Forge's install
            // processor step (see
            // https://github.com/MinecraftForge/Installer/issues/80).
            // Falls back to whatever's already in the managed folder if
            // that upfront resolution didn't find/download anything.
            java_executable: ensured_java_executable
                .clone()
                .or_else(crate::commands::java::find_any_managed_java),
        };

        // ── Progress tracking state, captured by the reporter closure ──
        use mc_launcher_core::progress::InstallStage;
        let mut current_stage = InstallStage::ResolveVersion;
        let mut current_stage_label = String::from("Preparing");
        let mut current_file = String::new();
        let mut current_task_received: u64 = 0;
        let mut current_task_total: Option<u64> = None;
        let mut per_label_last_received: HashMap<String, u64> = HashMap::new();
        // Every file actively downloading right now (started, not yet
        // finished), in start order — the parallel downloader can have up
        // to its worker-pool size of these in flight at once. Paired with
        // `label_display` to turn the download plan's internal `label`
        // into the human-readable filename the UI actually shows.
        let mut active_labels: Vec<String> = Vec::new();
        let mut label_display: HashMap<String, String> = HashMap::new();
        // Total byte size per active file, when the server reported one,
        // so we can blend how far *all* in-flight files have gotten (not
        // just the most recently-started one) into the percent estimate.
        let mut per_label_total: HashMap<String, u64> = HashMap::new();
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
                let active_files_display: Vec<ActiveFileProgress> = active_labels
                    .iter()
                    .map(|l| {
                        let name = label_display.get(l).cloned().unwrap_or_else(|| l.clone());
                        let percent = per_label_total.get(l).filter(|t| **t > 0).map(|total| {
                            let received = per_label_last_received.get(l).copied().unwrap_or(0);
                            (received as f64 / *total as f64 * 100.0).clamp(0.0, 100.0)
                        });
                        ActiveFileProgress { name, percent }
                    })
                    .collect();
                let in_flight_frac_sum: f64 = active_labels
                    .iter()
                    .filter_map(|l| {
                        let total = per_label_total.get(l)?;
                        if *total == 0 {
                            return None;
                        }
                        let received = per_label_last_received.get(l).copied().unwrap_or(0);
                        Some((received as f64 / *total as f64).clamp(0.0, 1.0))
                    })
                    .sum();
                let info = DownloadProgressInfo {
                    id: id_for_progress.clone(),
                    label: label_for_progress.clone(),
                    minecraft_version: mc_version_for_progress.clone(),
                    loader: loader_for_progress.clone(),
                    stage: current_stage_label.clone(),
                    current_file: current_file.clone(),
                    active_files: active_files_display,
                    downloaded_bytes: cumulative_bytes,
                    total_bytes: current_task_total,
                    percent: best_percent.max(overall_percent(&current_stage, tasks_started, tasks_done, in_flight_frac_sum)),
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
                    let display = path
                        .file_name()
                        .map(|f| f.to_string_lossy().to_string())
                        .unwrap_or_else(|| label.clone());
                    current_file = display.clone();
                    label_display.insert(label.clone(), display);
                    if !active_labels.contains(&label) {
                        active_labels.push(label.clone());
                    }
                    current_task_received = 0;
                    current_task_total = None;
                }
                PE::TaskSkipped { .. } => {
                    tasks_done += 1;
                }
                PE::TaskFinished { label } => {
                    tasks_done += 1;
                    active_labels.retain(|l| l != &label);
                    label_display.remove(&label);
                    per_label_total.remove(&label);
                    current_task_received = 0;
                    current_task_total = None;
                }
                PE::BytesReceived { label, received, total } => {
                    let prev = per_label_last_received.insert(label.clone(), received).unwrap_or(0);
                    let delta = received.saturating_sub(prev);
                    cumulative_bytes += delta;
                    total_bytes_downloaded.fetch_add(delta, Ordering::Relaxed);
                    if let Some(t) = total {
                        per_label_total.insert(label.clone(), t);
                    }
                    current_task_received = received;
                    current_task_total = total;
                }
                // Live output from the Forge/NeoForge installer jar — this
                // is the one stage that isn't a file download at all (the
                // installer manages its own libraries/patching), so
                // without this the progress bar/status line would just sit
                // there with nothing to show for a couple of minutes.
                // Surface it straight to the log console instead, so it
                // reads like any other in-progress output.
                PE::InstallerOutputLine { line } => {
                    if !line.trim().is_empty() {
                        logger::info(&app_for_progress, &app_for_progress.state::<AppState>(), "LOADER-INSTALL", &line);
                    }
                    current_file = line;
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

            let active_files_display: Vec<ActiveFileProgress> = active_labels
                .iter()
                .map(|l| {
                    let name = label_display.get(l).cloned().unwrap_or_else(|| l.clone());
                    let percent = per_label_total.get(l).filter(|t| **t > 0).map(|total| {
                        let received = per_label_last_received.get(l).copied().unwrap_or(0);
                        (received as f64 / *total as f64 * 100.0).clamp(0.0, 100.0)
                    });
                    ActiveFileProgress { name, percent }
                })
                .collect();
            let in_flight_frac_sum: f64 = active_labels
                .iter()
                .filter_map(|l| {
                    let total = per_label_total.get(l)?;
                    if *total == 0 {
                        return None;
                    }
                    let received = per_label_last_received.get(l).copied().unwrap_or(0);
                    Some((received as f64 / *total as f64).clamp(0.0, 1.0))
                })
                .sum();

            let raw_percent = overall_percent(&current_stage, tasks_started, tasks_done, in_flight_frac_sum);
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
                active_files: active_files_display,
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
                active_files: Vec::new(),
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
            // The frontend only ever shows a short toast/status line for
            // install failures — the *reason* (installer output, network
            // error, etc.) lives in `e` and would otherwise only reach the
            // user if they thought to screenshot a tooltip. Put the full
            // detail in the log (console + logs/latest.log) every time, so
            // "check the logs" is always a real answer, not just for
            // errors someone happened to also print elsewhere.
            logger::error(&app, &state, "LAUNCHER", &format!(
                "Install failed for {} {} ({}): {e}",
                mc_version_outer, loader_type_outer, label
            ));
            let info = DownloadProgressInfo {
                id: download_id.clone(),
                label: label.clone(),
                minecraft_version: mc_version_outer.clone(),
                loader: loader_type_outer.clone(),
                stage: "Error".to_string(),
                current_file: String::new(),
                active_files: Vec::new(),
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
            active_files: Vec::new(),
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
        total_playtime_seconds: 0,
                    last_played_at: None,
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
            if let Err(e) = crate::commands::java::ensure_java_for_version(&app, &state, &version, false).await {
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
    // `None` defers to the "Always Launch Offline" setting; `Some(_)` is an
    // explicit per-launch choice from the gear menu (▶ PLAY vs Launch
    // Offline), which always wins over the saved setting for that one launch.
    offline: Option<bool>,
) -> Result<(), String> {
    use mc_launcher_core::prelude::*;

    let offline = offline.unwrap_or_else(|| state.settings.lock().unwrap().always_launch_offline);

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
    let (username, active_account_id, active_account_type) = {
        let accounts = state.accounts.lock().unwrap();
        let account = accounts
            .iter()
            .find(|a| a.is_active)
            .ok_or_else(|| "No active account. Please add an account first.".to_string())?;
        (account.username.clone(), account.id.clone(), account.account_type.clone())
    };

    // Microsoft accounts need a fresh Minecraft access token minted right
    // before launch (Xbox Live/XSTS/Minecraft-services tokens are
    // short-lived) — this also rotates and persists the refresh token.
    // Offline accounts skip straight past this with an empty token.
    let mc_account = if active_account_type == "microsoft" {
        let (login, _updated) = crate::commands::msa::refresh_microsoft_login(&state, &active_account_id)
            .await
            .map_err(|e| format!("Microsoft sign-in failed: {e}"))?;
        mc_launcher_core::account::Account::Microsoft {
            username: login.name,
            uuid: login.id,
            access_token: login.access_token,
        }
    } else {
        mc_launcher_core::account::Account::offline(&username)
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
        let default_dir = state.settings.lock().unwrap().resolved_game_directory();
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
            total_playtime_seconds: 0,
                    last_played_at: None,
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

    // Register this instance as "running" and give it a fresh console
    // *now* — right as Play is pressed — instead of only once the game
    // process actually spawns further down. `pid: None` marks it as still
    // starting up (Java setup / file verification can take a little while,
    // especially the first time). This means the running-instances button
    // and its console are available immediately, so nothing that happens
    // between now and the process actually starting is invisible. The
    // in-memory map is updated with the real pid once the process spawns;
    // deliberately not persisted to `running_instances.json` until then, so
    // a launcher restart during this window doesn't mistake a
    // not-yet-started launch for a still-running one.
    state.instance_logs.lock().unwrap().insert(version_id.clone(), Vec::new());
    {
        let mut running_instances = state.running_instances.lock().unwrap();
        running_instances.insert(
            version_id.clone(),
            RunningInstanceInfo {
                version_id: version_id.clone(),
                name: inst_name.clone(),
                minecraft_version: inst_mc_version.clone(),
                loader: inst_loader.clone(),
                pid: None,
                started_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
                running: true,
            },
        );
    }
    // Record "last played" right as Play is pressed (not only once the
    // process actually spawns), so it reflects the moment the user chose
    // to launch even if setup/verification takes a while.
    {
        let mut instances = state.instances.lock().unwrap();
        if let Some(inst) = instances.iter_mut().find(|i| i.version_id == version_id) {
            inst.last_played_at = Some(chrono::Local::now().to_rfc3339());
        }
    }
    state.save_instances();
    let _ = app.emit("running-instances-changed", ());

    logger::info_for_instance(&app, &state, &version_id, "LAUNCHER", &format!(
        "Launching {} as {}...", version_id, username
    ));

    // If anything below fails before the game process actually spawns,
    // this flips the "starting" entry registered above back off instead
    // of leaving a phantom instance stuck showing as Running forever.
    // Cheap to call more than once and a no-op once the real pid lands
    // (see the `pid.is_none()` check) — called from every fallible step
    // between here and the actual spawn.
    let fail_cleanup = || {
        let mut running = state.running_instances.lock().unwrap();
        if let Some(info) = running.get_mut(&version_id) {
            if info.pid.is_none() {
                info.running = false;
            }
        }
        drop(running);
        let _ = app.emit("running-instances-changed", ());
    };

    let vid = version_id.clone();
    // `mc_dir` is where `versions/`, `libraries/`, and `assets/` live (the
    // shared default Minecraft directory) — this is what `Launcher` needs
    // to find and read the version's files, regardless of where this
    // instance's own game directory is.
    let mc_dir = minecraft_dir.clone();
    let dir = game_dir.clone();
    let account_for_launch = mc_account;

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
    .map_err(|e| { fail_cleanup(); format!("Launch task failed: {e}") })?
    .map_err(|e| { fail_cleanup(); e })?;

    // "Play" verifies libraries/assets/the client jar before every launch
    // and repairs anything missing or corrupted (e.g. a checksum mismatch
    // from a bad download earlier); "Launch Offline" — either chosen for
    // this one launch or via "Always Launch Offline" — skips this and
    // starts immediately from whatever's already on disk, no network
    // needed. `install_version_files` is the same pass the installer runs,
    // so anything already valid is skipped instantly; only missing/broken
    // files actually hit the network.
    if !offline {
        logger::info_for_instance(&app, &state, &vid, "LAUNCHER", &format!(
            "Verifying libraries/assets for {} before launch...", vid
        ));
        let status_id = vid.clone();
        let _ = app.emit("launch-verify-status", &LaunchVerifyStatus {
            version_id: status_id.clone(),
            active: true,
            message: "Checking libraries & assets…".to_string(),
        });

        let mc_dir_for_verify = mc_dir.clone();
        let version_for_verify = version.clone();
        let app_for_verify = app.clone();
        let vid_for_verify = vid.clone();
        // Unique id so a verify pass never collides with a concurrent
        // instance install's own download card — reuses the same
        // `DownloadProgressInfo`/`download-progress` event the installer
        // uses, so when this pass actually has to fetch something it shows
        // up in the Downloads widget exactly like any other install.
        let download_id = format!("launch-verify-{vid}");
        let verify_result = tokio::task::spawn_blocking(move || {
            let mut downloading_started = false;
            let mut downloaded_bytes: u64 = 0;
            let mut per_label_received: HashMap<String, u64> = HashMap::new();
            let mut current_stage = "Checking files".to_string();
            let mut last_status_emit = std::time::Instant::now() - std::time::Duration::from_secs(1);

            let mut reporter = |event: ProgressEvent| {
                match &event {
                    ProgressEvent::StageStarted { stage } => {
                        current_stage = match stage {
                            mc_launcher_core::progress::InstallStage::DownloadLibraries => "Checking libraries",
                            mc_launcher_core::progress::InstallStage::DownloadAssets => "Checking assets",
                            mc_launcher_core::progress::InstallStage::ExtractNatives => "Extracting natives",
                            mc_launcher_core::progress::InstallStage::Verify => "Verifying files",
                            _ => "Checking files",
                        }.to_string();
                        // Only worth telling the user about while nothing's
                        // actually downloading yet — once real downloads are
                        // in progress the per-file status below is more
                        // useful than the stage name.
                        if !downloading_started && last_status_emit.elapsed().as_millis() > 150 {
                            last_status_emit = std::time::Instant::now();
                            let _ = app_for_verify.emit("launch-verify-status", &LaunchVerifyStatus {
                                version_id: vid_for_verify.clone(),
                                active: true,
                                message: format!("{current_stage}…"),
                            });
                        }
                    }
                    ProgressEvent::TaskStarted { label, .. } => {
                        if !downloading_started {
                            downloading_started = true;
                            let _ = app_for_verify.emit("launch-verify-status", &LaunchVerifyStatus {
                                version_id: vid_for_verify.clone(),
                                active: true,
                                message: "Installing missing libraries/assets…".to_string(),
                            });
                        }
                        let info = DownloadProgressInfo {
                            id: download_id.clone(),
                            label: vid_for_verify.clone(),
                            minecraft_version: vid_for_verify.clone(),
                            loader: String::new(),
                            stage: current_stage.clone(),
                            current_file: label.clone(),
                            active_files: vec![ActiveFileProgress { name: label.clone(), percent: None }],
                            downloaded_bytes,
                            total_bytes: None,
                            percent: 0.0,
                            speed_bps: 0.0,
                            eta_seconds: None,
                            status: "downloading".to_string(),
                            message: None,
                        };
                        let _ = app_for_verify.emit("download-progress", &info);
                    }
                    ProgressEvent::BytesReceived { label, received, total } => {
                        let prev = per_label_received.insert(label.clone(), *received).unwrap_or(0);
                        downloaded_bytes = downloaded_bytes.saturating_add(received.saturating_sub(prev));
                        if last_status_emit.elapsed().as_millis() > 100 {
                            last_status_emit = std::time::Instant::now();
                            let pct = total.map(|t| if t > 0 { (*received as f64 / t as f64 * 100.0).clamp(0.0, 100.0) } else { 0.0 });
                            let info = DownloadProgressInfo {
                                id: download_id.clone(),
                                label: vid_for_verify.clone(),
                                minecraft_version: vid_for_verify.clone(),
                                loader: String::new(),
                                stage: current_stage.clone(),
                                current_file: label.clone(),
                                active_files: vec![ActiveFileProgress { name: label.clone(), percent: pct }],
                                downloaded_bytes,
                                total_bytes: None,
                                percent: pct.unwrap_or(0.0),
                                speed_bps: 0.0,
                                eta_seconds: None,
                                status: "downloading".to_string(),
                                message: None,
                            };
                            let _ = app_for_verify.emit("download-progress", &info);
                        }
                    }
                    ProgressEvent::TaskFinished { label } => {
                        if downloading_started {
                            let info = DownloadProgressInfo {
                                id: download_id.clone(),
                                label: vid_for_verify.clone(),
                                minecraft_version: vid_for_verify.clone(),
                                loader: String::new(),
                                stage: current_stage.clone(),
                                current_file: label.clone(),
                                active_files: Vec::new(),
                                downloaded_bytes,
                                total_bytes: None,
                                percent: 0.0,
                                speed_bps: 0.0,
                                eta_seconds: None,
                                status: "downloading".to_string(),
                                message: None,
                            };
                            let _ = app_for_verify.emit("download-progress", &info);
                        }
                    }
                    ProgressEvent::TaskSkipped { .. } => {}
                    // This reporter only ever wraps `install_version_files`
                    // (library/asset verification before launch), which
                    // never emits installer output — but the match still
                    // needs to be exhaustive against the shared enum.
                    ProgressEvent::InstallerOutputLine { .. } => {}
                }
            };
            let result = mc_launcher_core::install::client::install_version_files(
                &version_for_verify,
                &mc_dir_for_verify,
                &mut reporter,
            );
            (result, downloading_started, downloaded_bytes)
        })
        .await
        .map_err(|e| { fail_cleanup(); format!("Launch verification task failed: {e}") })?;

        let (result, downloading_started, downloaded_bytes) = verify_result;

        // Whatever happens next (success, failure, or nothing downloaded
        // at all), the verify phase is over — this is the frontend's cue
        // to stop showing the status line and to start the "is the launch
        // actually hung?" timeout, which deliberately never runs during
        // this pass.
        let _ = app.emit("launch-verify-status", &LaunchVerifyStatus {
            version_id: status_id.clone(),
            active: false,
            message: String::new(),
        });
        if downloading_started {
            logger::info_for_instance(&app, &state, &vid, "LAUNCHER", &format!(
                "Downloaded {} bytes of missing/updated libraries & assets", downloaded_bytes
            ));
            let final_status = if result.is_ok() { "completed" } else { "error" };
            let _ = app.emit("download-progress", &DownloadProgressInfo {
                id: format!("launch-verify-{vid}"),
                label: vid.clone(),
                minecraft_version: vid.clone(),
                loader: String::new(),
                stage: "Done".to_string(),
                current_file: String::new(),
                active_files: Vec::new(),
                downloaded_bytes,
                total_bytes: None,
                percent: 100.0,
                speed_bps: 0.0,
                eta_seconds: Some(0),
                status: final_status.to_string(),
                message: if result.is_ok() { None } else { Some("Verification failed".to_string()) },
            });
        }

        result.map_err(|e| { fail_cleanup(); format!(
            "Couldn't verify game files before launch (no connection, or a mirror is down): {e}. \
             Use the gear icon next to Play to Launch Offline instead."
        ) })?;
    }

    // Check if the user cancelled the launch during verification
    {
        let is_running = state.running_instances.lock().unwrap()
            .get(&vid)
            .map(|i| i.running)
            .unwrap_or(false);
        if !is_running {
            fail_cleanup();
            return Err("Launch cancelled by user".to_string());
        }
    }

    // This version's own libraries tell us definitively whether it uses
    // LWJGL2 (pre-1.13, groupId `org.lwjgl.lwjgl`, artifact `lwjgl`)
    // rather than LWJGL3 — cheaper and more reliable than guessing from
    // the Minecraft version string, since some loaders (old Forge builds)
    // shift which libraries are pulled in. Checked here, right after
    // `version` loads and before it's moved into the launch-command
    // closure further down.
    let uses_lwjgl2 = version.libraries.iter().any(|lib| {
        lib.name.starts_with("org.lwjgl.lwjgl:lwjgl:")
            || lib.name.starts_with("org.lwjgl:lwjgl:2.")
    });

    // On Linux, LWJGL2 always shells out to the `xrandr` binary to list
    // display modes — even when the compositor is Wayland, since it goes
    // through XWayland's X11 protocol either way. If `xrandr` isn't
    // installed the call comes back empty and LWJGL2 crashes immediately
    // with an opaque ArrayIndexOutOfBoundsException deep in native code.
    // Most Wayland desktops (GNOME, KDE, Hyprland, Sway, etc.) don't ship
    // it by default, so this is the single most common reason an old
    // Forge/vanilla instance won't start on modern Linux. Catch it here
    // with a clear, actionable message instead of letting the game crash
    // and only explaining it after the fact in the crash dialog.
    #[cfg(target_os = "linux")]
    if uses_lwjgl2 {
        // Cached after the first check so relaunching the same (or another)
        // LWJGL2 instance later in this session doesn't shell out to
        // `which` again every single time — `xrandr`'s presence can't
        // change without a launcher restart anyway. This is the only place
        // in the launcher that ever touches `xrandr`/`which` at all: LWJGL3
        // versions (i.e. every current/modern Minecraft version) skip this
        // whole block entirely via `uses_lwjgl2` above.
        let has_xrandr = std::process::Command::new("which")
            .arg("xrandr")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if !has_xrandr {
            fail_cleanup();
            return Err(
                "This version needs the `xrandr` command, which isn't installed. \
                 It's a small system package (not part of this launcher) — install it for your distribution:\n\
                 \u{2022} Arch / CachyOS / Manjaro: sudo pacman -S xorg-xrandr\n\
                 \u{2022} Debian / Ubuntu / Linux Mint: sudo apt install x11-xserver-utils\n\
                 \u{2022} Fedora / RHEL / CentOS: sudo dnf install xrandr\n\
                 \u{2022} openSUSE: sudo zypper install xrandr\n\
                 \u{2022} Alpine Linux: sudo apk add xrandr\n\
                 \u{2022} Void Linux: sudo xbps-install -S xrandr\n\
                 This is needed even under Wayland, since this old Minecraft version (LWJGL2) always talks to X11/XWayland for display info."
                    .to_string(),
            );
        }
    }

    // Smart Java Detection: use the user's manually-selected Java if one is
    // set in Settings, otherwise figure out which Java major version this
    // instance needs, reuse a matching install if we have one, or download
    // it automatically (via Azul) into the managed java/ folder — unless
    // this is an offline launch, in which case a missing Java fails fast
    // with a clear message instead of silently reaching out to the
    // network anyway (which is exactly what "Launch Offline" is supposed
    // to avoid).
    let java_executable = crate::commands::java::ensure_java_for_version(&app, &state, &version, offline)
        .await
        .map_err(|e| { fail_cleanup(); format!("Java setup failed: {e}") })?;

    // Check if the user cancelled the launch during Java setup
    {
        let is_running = state.running_instances.lock().unwrap()
            .get(&vid)
            .map(|i| i.running)
            .unwrap_or(false);
        if !is_running {
            fail_cleanup();
            return Err("Launch cancelled by user".to_string());
        }
    }

    logger::info_for_instance(&app, &state, &version_id, "LAUNCHER", &format!(
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
                    account: account_for_launch,
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
    .map_err(|e| { fail_cleanup(); format!("Launch task failed: {e}") })?
    .map_err(|e| { fail_cleanup(); e })?;

    // Build JVM arguments
    let mut args: Vec<String> = Vec::new();
    args.push(format!("-Xmx{}m", max_ram));
    args.push(format!("-Xms{}m", min_ram));
    args.push("-Ddiscordfix=net.minecraft.client.main.Main".to_string());
    if !jvm_args_str.is_empty() {
        args.extend(jvm_args_str.split_whitespace().map(String::from));
    }
    args.extend(launch_cmd.args.clone());

    // Debug-only, and subject to two Privacy settings:
    //  - "Hide Launch Command from Logs" skips this line entirely
    //  - "Redact Auth Tokens in Logs" strips the offline session's
    //    --accessToken/--uuid values out of what's left
    let (hide_cmd, auto_open_console) = state.settings.lock()
        .map(|s| (s.hide_launch_command, s.auto_open_console_on_launch))
        .unwrap_or((true, false));
    let command_line = format!(
        "Command: {} {}",
        launch_cmd.executable.display(),
        logger::redact_sensitive(&state, &args.join(" "))
    );
    if !hide_cmd {
        logger::debug(&app, &state, "LAUNCHER", &command_line);
    }
    // "Auto-open console when launching" is an explicit ask to see
    // everything about this launch, including the command — so it always
    // shows in that instance's own console regardless of the Privacy
    // settings above. Deliberately does NOT go through the shared
    // launcher log file/main log viewer (unlike `info_for_instance`) —
    // only this instance's own console gets it, so "Hide Launch Command
    // from Logs" still holds everywhere else. Auth tokens are still
    // redacted per "Redact Auth Tokens in Logs" via `redact_sensitive`
    // either way.
    if auto_open_console {
        let entry = crate::models::LogEntry::new("INFO", "LAUNCHER", &command_line);
        state.push_instance_log(&version_id, entry.clone());
        let _ = app.emit(
            "instance-log",
            &crate::models::InstanceLogEvent { version_id: version_id.clone(), entry },
        );
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
        // CREATE_BREAKAWAY_FROM_JOB: some environments (including, in
        // practice, some WebView2/Tauri setups) put the launcher process
        // in a Windows Job Object that's configured to kill every process
        // in the job when the job's last handle closes. Without breaking
        // away, quitting the launcher could take the game process down
        // with it even though we never asked it to. Breaking away means
        // the game process is only ever ended by the OS/user/itself, not
        // as a side effect of the launcher exiting.
        const CREATE_BREAKAWAY_FROM_JOB: u32 = 0x01000000;
        launch_command.creation_flags(CREATE_NO_WINDOW | CREATE_BREAKAWAY_FROM_JOB);
    }
    #[cfg(unix)]
    {
        // Put the game in its own process group so it isn't tied to the
        // launcher's — same intent as CREATE_BREAKAWAY_FROM_JOB above:
        // the launcher quitting (or its window/session ending) shouldn't
        // signal or take down the game process with it. tokio's
        // `Command::process_group` is inherent (no extra trait import
        // needed, unlike std's `CommandExt`).
        launch_command.process_group(0);
        // Set argv[0] to "Minecraft" so system monitors (GNOME System
        // Monitor, KSysGuard, htop, etc.) that read /proc/<pid>/comm or
        // argv[0] show "Minecraft" rather than "java" or the launcher's
        // own process name. This is purely cosmetic — the actual binary
        // executed is still the Java JVM resolved above.
        launch_command.arg0("Minecraft");

        // Strip AppImage and desktop launch tracking environment variables
        // so that Linux desktop environments, docks, and system monitors do
        // NOT associate the spawned Minecraft JVM process with ZeroLauncher's
        // AppImage container or launcher icon.
        launch_command.env_remove("APPIMAGE");
        launch_command.env_remove("APPDIR");
        launch_command.env_remove("ARGV0");
        launch_command.env_remove("OWD");
        launch_command.env_remove("DESKTOP_STARTUP_ID");
        launch_command.env_remove("GIO_LAUNCHED_DESKTOP_FILE");
        launch_command.env_remove("GIO_LAUNCHED_DESKTOP_FILE_PID");
        launch_command.env_remove("BAMF_DESKTOP_FILE_HINT");
    }
    let mut child = launch_command
        .spawn()
        .map_err(|e| { fail_cleanup(); format!("Failed to start game: {e}") })?;

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
    // Console history for this run was already initialized right when the
    // instance was registered as "starting" earlier in this function —
    // don't reset it here, or every log line from Java setup/file
    // verification would be wiped right as the game actually starts.
    // Persist immediately: if the launcher is quit right after this (the
    // game process itself keeps running, detached — see the spawn flags
    // above), the next launch needs `running_instances.json` on disk to
    // know this instance is still out there.
    state.save_running_instances();
    let _ = app.emit("running-instances-changed", ());

    // Settings → Window Behavior → "When launching a game". These were
    // previously only ever saved/loaded from Settings and never actually
    // applied anywhere.
    {
        let (close_on_launch, minimize_on_launch) = {
            let s = state.settings.lock().unwrap();
            (s.close_after_launch, s.minimize_on_launch)
        };
        if let Some(window) = app.get_webview_window("main") {
            if close_on_launch {
                // "Close" here means the launcher's own window goes away,
                // same as the user closing it themselves — so it's still
                // governed by the On Launcher Close setting (hide to tray
                // vs. quit outright) rather than always force-quitting.
                let _ = window.close();
            } else if minimize_on_launch {
                let _ = window.minimize();
            }
        }
    }

    logger::info_for_instance(&app, &state, &version_id, "LAUNCHER", "Game process started!");

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

        // Flip this instance's running flag off (entry stays for history),
        // and add this session's elapsed time to the instance's total
        // playtime before we lose track of when it started.
        let started_at_for_playtime = {
            let mut running = state_done.running_instances.lock().unwrap();
            let info = running.get_mut(&vid_done);
            let started_at = info.as_ref().map(|i| i.started_at.clone());
            if let Some(info) = info {
                info.running = false;
            }
            started_at
        };
        if let Some(started_at) = started_at_for_playtime {
            accumulate_playtime(&state_done, &vid_done, &started_at);
        }
        // Drop it from the persisted file too — it's no longer something a
        // future launch needs to rediscover.
        state_done.save_running_instances();
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

        let crashed = if let Some(report) = crate::commands::crash_analysis::analyze(
            &vid_done,
            &name_done,
            &game_dir_done,
            &loader_done,
            exit_code,
            &log_lines,
        ) {
            let _ = app_done.emit("game-crashed", &report);
            true
        } else {
            false
        };

        // Settings → Window Behavior → "On Minecraft Close". Skipped for
        // the "quit" action specifically when the game crashed — closing
        // the launcher out from under a crash report before the user's
        // even seen it would defeat the point of showing one.
        if !(crashed && state_done.settings.lock().unwrap().on_game_close == "quit") {
            apply_on_game_close_action(&app_done);
        }
    });

    Ok(())
}

/// Add this session's elapsed time to an instance's cumulative
/// `total_playtime_seconds` and persist `instances.json`. `started_at` is
/// the `RunningInstanceInfo.started_at` timestamp recorded when the
/// session began (format: `%Y-%m-%d %H:%M:%S`, local time). Called once,
/// right as a session's game process is discovered to have exited —
/// whichever launcher process happens to witness that (a live session's
/// own wait, or a rehydrated pid watcher after a launcher restart).
fn accumulate_playtime(state: &AppState, version_id: &str, started_at: &str) {
    let started = match chrono::NaiveDateTime::parse_from_str(started_at, "%Y-%m-%d %H:%M:%S") {
        Ok(dt) => dt,
        Err(_) => return,
    };
    let elapsed_secs = (chrono::Local::now().naive_local() - started)
        .num_seconds()
        .max(0) as u64;
    let mut instances = state.instances.lock().unwrap();
    if let Some(inst) = instances.iter_mut().find(|i| i.version_id == version_id) {
        inst.total_playtime_seconds = inst.total_playtime_seconds.saturating_add(elapsed_secs);
    }
    drop(instances);
    state.save_instances();
}

/// Apply the Settings → Window Behavior "On Minecraft Close" action, once
/// an instance's game process has actually exited. Called from both the
/// normal in-session exit path (`launch_minecraft`'s background wait) and
/// the rehydrated-pid watcher (`spawn_external_pid_watcher`), since a game
/// closing should behave the same whichever launcher process happened to
/// witness it.
fn apply_on_game_close_action(app: &tauri::AppHandle) {
    let state = app.state::<AppState>();
    let action = state.settings.lock().unwrap().on_game_close.clone();
    match action.as_str() {
        "show" => {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }
        "quit" => {
            app.exit(0);
        }
        // "none" (or anything unrecognized): leave the window exactly as
        // it is — don't show it if it was hidden, don't touch it if open.
        _ => {}
    }
}

/// True if a process with the given pid is currently alive. Used at startup
/// to check which pids from a persisted `running_instances.json` (written
/// by a previous, now-exited launcher process) are actually still running
/// the detached game process versus stale leftovers from a game that has
/// since closed.
pub fn is_pid_running(pid: u32) -> bool {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        match std::process::Command::new("tasklist")
            .args(["/FI", &format!("PID eq {pid}"), "/NH"])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
        {
            Ok(out) => String::from_utf8_lossy(&out.stdout).contains(&pid.to_string()),
            Err(_) => false,
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        // Signal 0 doesn't actually send a signal — it just checks whether
        // we're allowed to signal the pid, which fails distinctly if it
        // doesn't exist.
        std::process::Command::new("kill")
            .args(["-0", &pid.to_string()])
            .output()
            .map(|out| out.status.success())
            .unwrap_or(false)
    }
}

/// Poll a (not-our-child) pid until it exits, then flip its instance to
/// `running: false` and update the persisted file/frontend accordingly.
/// Used only for instances rehydrated from `running_instances.json` at
/// startup, since those processes weren't spawned by *this* launcher
/// process and so can't be awaited directly with `Child::wait`.
pub fn spawn_external_pid_watcher(app: tauri::AppHandle, version_id: String, pid: u32) {
    // `tokio::spawn` requires already being inside a running Tokio
    // reactor. This is called from Tauri's `setup()` closure, which runs
    // *before* Tauri has entered its async runtime on that thread, so
    // `tokio::spawn` panics there ("there is no reactor running"). Tauri's
    // own `async_runtime::spawn` is safe to call from anywhere — it hands
    // the future to the runtime Tauri manages internally regardless of
    // what context it's called from — so use that instead.
    tauri::async_runtime::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
            if !is_pid_running(pid) {
                break;
            }
        }
        let state = app.state::<AppState>();
        let started_at_for_playtime = {
            let mut running = state.running_instances.lock().unwrap();
            let info = running.get_mut(&version_id);
            let started_at = info.as_ref().map(|i| i.started_at.clone());
            if let Some(info) = info {
                info.running = false;
            }
            started_at
        };
        if let Some(started_at) = started_at_for_playtime {
            accumulate_playtime(&state, &version_id, &started_at);
        }
        state.save_running_instances();
        let _ = app.emit("running-instances-changed", ());
        // No crash-report machinery here (this instance's console history
        // isn't available to this launcher process — it was launched by a
        // previous one), so just apply Window Behavior directly.
        apply_on_game_close_action(&app);
    });
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
/// PID captured at launch time. If still in pre-launch preparation, gracefully
/// cancels the launch task.
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

    // If there is no process ID yet, the instance is in the pre-launch phase
    // (verifying libraries/assets, downloading Java, resolving metadata).
    // Gracefully cancel the pre-launch startup and mark the instance as stopped.
    let pid = match pid {
        Some(p) => p,
        None => {
            state
                .generic_cancel_flag(&format!("launch-verify-{version_id}"))
                .store(true, std::sync::atomic::Ordering::Relaxed);
            state
                .generic_cancel_flag(&version_id)
                .store(true, std::sync::atomic::Ordering::Relaxed);

            let _ = app.emit("launch-verify-status", &LaunchVerifyStatus {
                version_id: version_id.clone(),
                active: false,
                message: String::new(),
            });

            if let Some(info) = state.running_instances.lock().unwrap().get_mut(&version_id) {
                info.running = false;
            }
            state.save_running_instances();
            let _ = app.emit("running-instances-changed", ());
            logger::info(&app, &state, "LAUNCHER", &format!(
                "Cancelled launch preparation for {}", version_id
            ));
            return Ok(());
        }
    };

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
            state.save_running_instances();
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
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

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

/// Deletes an instance's own data folder — mods, worlds/saves, config,
/// resourcepacks, shaderpacks, screenshots, logs, options.txt, etc. — i.e.
/// everything under `InstalledInstance.directory`. This is opt-in and
/// separate from `delete_installed_version` above, which only removes the
/// shared `versions/<id>` folder.
///
/// `minecraft_directory` is the instance's shared `.minecraft`-style
/// directory (versions/libraries/assets), passed so this can refuse to run
/// when `directory` *is* that shared directory — true for every
/// default-location instance, and for any instance saved before the
/// directory/minecraft_directory split existed. Deleting the shared
/// directory would wipe every other instance's data too, so this is always
/// blocked, both here and by the frontend disabling the toggle for it.
#[tauri::command]
pub async fn delete_instance_data(
    state: State<'_, AppState>,
    directory: String,
    minecraft_directory: Option<String>,
) -> Result<(), String> {
    if directory.trim().is_empty() {
        return Err("No instance directory to delete".to_string());
    }

    let target = PathBuf::from(&directory);
    if !target.is_dir() {
        // Nothing to delete — already gone, treat as success.
        return Ok(());
    }
    let canonical_target = std::fs::canonicalize(&target)
        .map_err(|e| format!("Failed to resolve instance directory: {e}"))?;

    // Figure out the shared directory this instance's core files live in,
    // falling back to the globally configured default game directory when
    // the instance predates the directory split (same fallback used
    // elsewhere for `minecraft_dir()`).
    let shared_dir = match minecraft_directory.filter(|d| !d.trim().is_empty()) {
        Some(d) => PathBuf::from(d),
        None => state.settings.lock().unwrap().resolved_game_directory(),
    };
    if let Ok(canonical_shared) = std::fs::canonicalize(&shared_dir) {
        if canonical_target == canonical_shared {
            return Err(
                "Refusing to delete: this instance shares its data folder with your main .minecraft directory".to_string()
            );
        }
    }

    std::fs::remove_dir_all(&canonical_target)
        .map_err(|e| format!("Failed to delete instance data folder: {e}"))?;

    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LinuxZlibCheckResult {
    pub has_conflict: bool,
    pub distro: String,
}

/// Checks whether the Linux system is running `zlib-ng` / `zlib-ng-compat` instead of standard `zlib`.
/// Used before Forge/NeoForge installations to warn users of hash verification issues.
#[tauri::command]
pub fn check_linux_zlib_conflict() -> LinuxZlibCheckResult {
    #[cfg(target_os = "linux")]
    {
        // 1. Check if pacman reports zlib-ng or zlib-ng-compat installed
        let has_pacman_zlib_ng = std::process::Command::new("pacman")
            .args(["-Q", "zlib-ng-compat"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
            || std::process::Command::new("pacman")
                .args(["-Q", "zlib-ng"])
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .map(|s| s.success())
                .unwrap_or(false);

        // 2. Check if libz-ng files exist
        let has_libz_ng_file = [
            "/usr/lib/libz-ng.so",
            "/usr/lib64/libz-ng.so",
            "/usr/lib/libz-ng.so.1",
            "/usr/lib64/libz-ng.so.1",
            "/usr/lib/x86_64-linux-gnu/libz-ng.so",
            "/usr/lib/x86_64-linux-gnu/libz-ng.so.1",
        ]
        .iter()
        .any(|p| std::path::Path::new(p).exists());

        // 3. Inspect symlink or ELF bytes of libz.so.1
        let has_zlib_ng_in_libz = [
            "/usr/lib/libz.so.1",
            "/usr/lib64/libz.so.1",
            "/lib64/libz.so.1",
            "/usr/lib/x86_64-linux-gnu/libz.so.1",
        ]
        .iter()
        .any(|p| {
            if let Ok(target) = std::fs::read_link(p) {
                if target.to_string_lossy().contains("zlib-ng") {
                    return true;
                }
            }
            if let Ok(bytes) = std::fs::read(p) {
                if bytes.windows(7).any(|w| w == b"zlib-ng") {
                    return true;
                }
            }
            false
        });

        let distro = std::fs::read_to_string("/etc/os-release")
            .ok()
            .and_then(|content| {
                for line in content.lines() {
                    if let Some(id) = line.strip_prefix("ID=") {
                        return Some(id.trim_matches('"').to_string());
                    }
                }
                None
            })
            .unwrap_or_else(|| "linux".to_string());

        let has_conflict = has_pacman_zlib_ng || has_libz_ng_file || has_zlib_ng_in_libz;
        LinuxZlibCheckResult {
            has_conflict,
            distro,
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        LinuxZlibCheckResult {
            has_conflict: false,
            distro: String::new(),
        }
    }
}

/// Automatically installs required Linux system packages (e.g. standard `zlib` or `xrandr`)
/// using `pkexec` (PolicyKit) to prompt for system privileges safely.
#[tauri::command]
pub async fn install_linux_package(package_type: String) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let is_arch = std::process::Command::new("which").arg("pacman").output().map(|o| o.status.success()).unwrap_or(false);
        let is_debian = std::process::Command::new("which").arg("apt-get").output().map(|o| o.status.success()).unwrap_or(false);
        let is_fedora = std::process::Command::new("which").arg("dnf").output().map(|o| o.status.success()).unwrap_or(false);
        let is_zypper = std::process::Command::new("which").arg("zypper").output().map(|o| o.status.success()).unwrap_or(false);
        let is_apk = std::process::Command::new("which").arg("apk").output().map(|o| o.status.success()).unwrap_or(false);
        let is_xbps = std::process::Command::new("which").arg("xbps-install").output().map(|o| o.status.success()).unwrap_or(false);

        let has_pkexec = std::process::Command::new("which").arg("pkexec").output().map(|o| o.status.success()).unwrap_or(false);
        if !has_pkexec {
            return Err("pkexec (PolicyKit) was not found on your system. Please install the package manually using your terminal.".to_string());
        }

        let commands_to_try: Vec<(&str, Vec<&str>)> = match package_type.as_str() {
            "zlib" => {
                if is_arch {
                    vec![
                        ("sh", vec!["-c", "printf 'y\\ny\\ny\\ny\\n' | pacman -S zlib lib32-zlib || (pacman -Rdd --noconfirm zlib-ng-compat lib32-zlib-ng-compat 2>/dev/null; pacman -S --noconfirm zlib lib32-zlib || pacman -S --noconfirm zlib)"]),
                    ]
                } else if is_debian {
                    vec![
                        ("apt-get", vec!["install", "-y", "zlib1g", "zlib1g:i386"]),
                        ("apt-get", vec!["install", "-y", "zlib1g"]),
                    ]
                } else if is_fedora {
                    vec![
                        ("dnf", vec!["install", "-y", "zlib", "zlib.i686"]),
                        ("dnf", vec!["install", "-y", "zlib"]),
                    ]
                } else if is_zypper {
                    vec![
                        ("zypper", vec!["install", "-y", "libz1", "libz1-32bit"]),
                        ("zypper", vec!["install", "-y", "libz1"]),
                    ]
                } else if is_apk {
                    vec![("apk", vec!["add", "zlib"])]
                } else if is_xbps {
                    vec![("xbps-install", vec!["-y", "-S", "zlib"])]
                } else {
                    return Err("No supported package manager detected on this Linux system.".to_string());
                }
            }
            "xrandr" => {
                if is_arch {
                    vec![
                        ("pacman", vec!["-S", "--noconfirm", "xorg-xrandr"]),
                        ("sh", vec!["-c", "printf 'y\\ny\\n' | pacman -S xorg-xrandr"]),
                    ]
                } else if is_debian {
                    vec![("apt-get", vec!["install", "-y", "x11-xserver-utils"])]
                } else if is_fedora {
                    vec![("dnf", vec!["install", "-y", "xrandr"])]
                } else if is_zypper {
                    vec![("zypper", vec!["install", "-y", "xrandr"])]
                } else if is_apk {
                    vec![("apk", vec!["add", "xrandr"])]
                } else if is_xbps {
                    vec![("xbps-install", vec!["-y", "-S", "xrandr"])]
                } else {
                    return Err("No supported package manager detected on this Linux system.".to_string());
                }
            }
            _ => return Err("Unknown package type requested.".to_string()),
        };

        let mut last_error = String::new();
        for (bin, args) in commands_to_try {
            let mut full_args = vec![bin];
            full_args.extend(args);

            let res = tokio::task::spawn_blocking(move || {
                std::process::Command::new("pkexec")
                    .args(&full_args)
                    .output()
            })
            .await
            .map_err(|e| format!("Task execution failed: {e}"))?;

            match res {
                Ok(output) if output.status.success() => return Ok(()),
                Ok(output) => {
                    let err = String::from_utf8_lossy(&output.stderr).to_string();
                    let out = String::from_utf8_lossy(&output.stdout).to_string();
                    let combined = format!("{out}\n{err}").trim().to_string();
                    last_error = if combined.is_empty() {
                        "Authentication was cancelled or installation failed.".to_string()
                    } else {
                        combined
                    };
                }
                Err(e) => {
                    last_error = format!("Failed to launch pkexec: {e}");
                }
            }
        }

        Err(format!("Installation failed: {last_error}"))
    }
    #[cfg(not(target_os = "linux"))]
    {
        Err("Package installation is only supported on Linux.".to_string())
    }
}

/// Recursively sum the size in bytes of every file under `path`. Best-effort:
/// unreadable entries (permissions, races with the game writing files) are
/// skipped rather than failing the whole walk.
fn dir_size_bytes(path: &std::path::Path) -> u64 {
    let mut total = 0u64;
    let entries = match std::fs::read_dir(path) {
        Ok(e) => e,
        Err(_) => return 0,
    };
    for entry in entries.flatten() {
        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        if metadata.is_dir() {
            total = total.saturating_add(dir_size_bytes(&entry.path()));
        } else {
            total = total.saturating_add(metadata.len());
        }
    }
    total
}

/// Total size on disk for one instance, in bytes: its game directory
/// (saves/mods/config/resourcepacks) plus its shared `versions/` +
/// `libraries/` + `assets/` directory, without double-counting when both
/// point at the same folder (the common case for non-"separated" installs).
#[tauri::command]
pub async fn get_instance_disk_size(version_id: String, state: State<'_, AppState>) -> Result<u64, String> {
    let (dir, mc_dir) = {
        let instances = state.instances.lock().unwrap();
        let inst = instances
            .iter()
            .find(|i| i.version_id == version_id)
            .ok_or_else(|| "Instance not found".to_string())?;
        (inst.directory.clone(), inst.minecraft_dir())
    };
    tokio::task::spawn_blocking(move || {
        let dir_path = std::path::PathBuf::from(&dir);
        let size = dir_size_bytes(&dir_path);
        if mc_dir != dir {
            let mc_path = std::path::PathBuf::from(&mc_dir);
            size.saturating_add(dir_size_bytes(&mc_path))
        } else {
            size
        }
    })
    .await
    .map_err(|e| format!("Failed to compute instance size: {e}"))
}
