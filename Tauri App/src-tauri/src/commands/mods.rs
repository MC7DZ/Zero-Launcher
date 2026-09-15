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

// ── Worlds ───────────────────────────────────────────────────────────────
// Worlds live in `saves/` inside an instance's game directory.
// Each world directory typically has a `level.dat` (GZip-compressed NBT),
// an optional `icon.png` (world thumbnail), and chunk/region files.
// We parse level.dat to extract the in-game display name, game mode,
// last played timestamp, and Minecraft version, following PrismLauncher's logic.

#[derive(Serialize)]
pub struct WorldInfo {
    pub folder_name: String,
    pub name: String,
    pub path: String,
    pub last_played_ms: u64,
    pub size_bytes: u64,
    pub game_mode: String,
    pub version: String,
    pub hardcore: bool,
    pub has_icon: bool,
}

#[tauri::command]
pub async fn list_worlds(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<Vec<WorldInfo>, String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let saves_dir = game_dir.join("saves");
    if !saves_dir.exists() {
        return Ok(Vec::new());
    }

    let entries = match std::fs::read_dir(&saves_dir) {
        Ok(e) => e,
        Err(_) => return Ok(Vec::new()),
    };

    let mut worlds = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }

        let folder_name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
        let level_dat_path = path.join("level.dat");
        if !level_dat_path.exists() {
            continue;
        }

        let meta = match path.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };

        // Check icon.png
        let has_icon = path.join("icon.png").exists();

        // Calculate world size (sum top-level region and data files/folders)
        let size_bytes = calc_world_size(&path).unwrap_or(meta.len());

        // Parse level.dat
        let (display_name, last_played_ms, game_mode, version, hardcore) = parse_level_dat(&level_dat_path)
            .unwrap_or_else(|| {
                let default_name = folder_name.clone();
                let modified = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                (default_name, modified, "Unknown".to_string(), String::new(), false)
            });

        worlds.push(WorldInfo {
            folder_name,
            name: if display_name.trim().is_empty() { path.file_name().unwrap().to_string_lossy().to_string() } else { display_name },
            path: path.to_string_lossy().to_string(),
            last_played_ms,
            size_bytes,
            game_mode,
            version,
            hardcore,
            has_icon,
        });
    }

    // Sort newest last-played first
    worlds.sort_by(|a, b| b.last_played_ms.cmp(&a.last_played_ms));
    Ok(worlds)
}

/// Helper to estimate world directory size without deep expensive recursion
fn calc_world_size(dir: &PathBuf) -> Option<u64> {
    let mut total: u64 = 0;
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            if let Ok(m) = entry.metadata() {
                if m.is_file() {
                    total += m.len();
                } else if m.is_dir() {
                    // One level deeper for region and entities
                    if let Ok(sub) = std::fs::read_dir(&p) {
                        for se in sub.flatten() {
                            if let Ok(sm) = se.metadata() {
                                total += sm.len();
                            }
                        }
                    }
                }
            }
        }
    }
    Some(total)
}

/// Decompress and extract basic metadata from level.dat (GZip NBT)
fn parse_level_dat(path: &PathBuf) -> Option<(String, u64, String, String, bool)> {
    let file = std::fs::File::open(path).ok()?;
    let mut decoder = flate2::read::GzDecoder::new(file);
    let mut buffer = Vec::new();
    decoder.read_to_end(&mut buffer).ok()?;

    let mut level_name = None;
    let mut last_played = None;
    let mut game_type_code = None;
    let mut version_name = None;
    let mut hardcore = false;

    // Scan the raw decompressed NBT buffer for well-known tags
    // 1. LevelName (TAG_String: 0x08 followed by 2-byte name len "LevelName" then 2-byte value len)
    if let Some(pos) = find_subsequence(&buffer, b"LevelName") {
        let val_pos = pos + 9;
        if val_pos + 2 <= buffer.len() {
            let len = u16::from_be_bytes([buffer[val_pos], buffer[val_pos + 1]]) as usize;
            let str_start = val_pos + 2;
            if str_start + len <= buffer.len() {
                if let Ok(s) = std::str::from_utf8(&buffer[str_start..str_start + len]) {
                    level_name = Some(s.to_string());
                }
            }
        }
    }

    // 2. LastPlayed (TAG_Long: 0x04 followed by "LastPlayed" then 8-byte big-endian i64)
    if let Some(pos) = find_subsequence(&buffer, b"LastPlayed") {
        let val_pos = pos + 10;
        if val_pos + 8 <= buffer.len() {
            let lp = i64::from_be_bytes(buffer[val_pos..val_pos + 8].try_into().unwrap());
            if lp > 0 {
                last_played = Some(lp as u64);
            }
        }
    }

    // 3. GameType (TAG_Int: 0x03 followed by "GameType" then 4-byte big-endian i32)
    if let Some(pos) = find_subsequence(&buffer, b"GameType") {
        let val_pos = pos + 8;
        if val_pos + 4 <= buffer.len() {
            let gt = i32::from_be_bytes(buffer[val_pos..val_pos + 4].try_into().unwrap());
            game_type_code = Some(gt);
        }
    }

    // 4. hardcore (TAG_Byte: 0x01 followed by "hardcore" then 1 byte)
    if let Some(pos) = find_subsequence(&buffer, b"hardcore") {
        let val_pos = pos + 8;
        if val_pos < buffer.len() {
            hardcore = buffer[val_pos] != 0;
        }
    }

    // 5. Version name: under "Version" compound look for "Name" (e.g. "1.20.4")
    if let Some(vpos) = find_subsequence(&buffer, b"Version") {
        if let Some(npos) = find_subsequence(&buffer[vpos..std::cmp::min(vpos + 200, buffer.len())], b"Name") {
            let abs_pos = vpos + npos + 4;
            if abs_pos + 2 <= buffer.len() {
                let len = u16::from_be_bytes([buffer[abs_pos], buffer[abs_pos + 1]]) as usize;
                let str_start = abs_pos + 2;
                if str_start + len <= buffer.len() {
                    if let Ok(s) = std::str::from_utf8(&buffer[str_start..str_start + len]) {
                        version_name = Some(s.to_string());
                    }
                }
            }
        }
    }

    let mode_str = match game_type_code {
        Some(0) => if hardcore { "Hardcore".to_string() } else { "Survival".to_string() },
        Some(1) => "Creative".to_string(),
        Some(2) => "Adventure".to_string(),
        Some(3) => "Spectator".to_string(),
        _ => "Survival".to_string(),
    };

    Some((
        level_name.unwrap_or_default(),
        last_played.unwrap_or_else(|| {
            path.metadata()
                .ok()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0)
        }),
        mode_str,
        version_name.unwrap_or_default(),
        hardcore,
    ))
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

// ── Servers ───────────────────────────────────────────────────────────────
// Saved multiplayer servers live in `servers.dat` in the game directory.
// It is an uncompressed NBT file (TAG_Compound containing a TAG_List "servers"
// of TAG_Compound entries, each with "name", "ip", optional "icon" base64 png).

#[derive(Serialize, Clone, Debug)]
pub struct ServerInfo {
    pub name: String,
    pub ip: String,
    pub icon_base64: Option<String>,
    pub accept_textures: Option<u8>,
}

#[tauri::command]
pub async fn list_servers(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<Vec<ServerInfo>, String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let servers_file = game_dir.join("servers.dat");
    if !servers_file.exists() {
        return Ok(Vec::new());
    }

    let bytes = match std::fs::read(&servers_file) {
        Ok(b) => b,
        Err(e) => return Err(format!("Could not read servers.dat: {e}")),
    };

    Ok(parse_servers_dat(&bytes))
}

fn read_nbt_string(data: &[u8], offset: &mut usize) -> Option<String> {
    if *offset + 2 > data.len() {
        return None;
    }
    let len = u16::from_be_bytes([data[*offset], data[*offset + 1]]) as usize;
    *offset += 2;
    if *offset + len > data.len() {
        return None;
    }
    let s = String::from_utf8_lossy(&data[*offset..*offset + len]).to_string();
    *offset += len;
    Some(s)
}

fn skip_nbt_tag(data: &[u8], offset: &mut usize, tag_type: u8) -> bool {
    match tag_type {
        1 => { // TAG_Byte
            *offset += 1;
            *offset <= data.len()
        }
        2 => { // TAG_Short
            *offset += 2;
            *offset <= data.len()
        }
        3 | 5 => { // TAG_Int, TAG_Float
            *offset += 4;
            *offset <= data.len()
        }
        4 | 6 => { // TAG_Long, TAG_Double
            *offset += 8;
            *offset <= data.len()
        }
        7 => { // TAG_Byte_Array
            if *offset + 4 > data.len() { return false; }
            let len = i32::from_be_bytes(data[*offset..*offset + 4].try_into().unwrap()) as usize;
            *offset += 4 + len;
            *offset <= data.len()
        }
        8 => { // TAG_String
            read_nbt_string(data, offset).is_some()
        }
        9 => { // TAG_List
            if *offset + 5 > data.len() { return false; }
            let elem_type = data[*offset];
            let count = i32::from_be_bytes(data[*offset + 1..*offset + 5].try_into().unwrap());
            *offset += 5;
            if count > 0 {
                for _ in 0..count {
                    if !skip_nbt_tag(data, offset, elem_type) {
                        return false;
                    }
                }
            }
            true
        }
        10 => { // TAG_Compound
            while *offset < data.len() {
                let t = data[*offset];
                *offset += 1;
                if t == 0 { // TAG_End
                    return true;
                }
                if read_nbt_string(data, offset).is_none() {
                    return false;
                }
                if !skip_nbt_tag(data, offset, t) {
                    return false;
                }
            }
            false
        }
        11 => { // TAG_Int_Array
            if *offset + 4 > data.len() { return false; }
            let len = i32::from_be_bytes(data[*offset..*offset + 4].try_into().unwrap()) as usize;
            *offset += 4 + len * 4;
            *offset <= data.len()
        }
        12 => { // TAG_Long_Array
            if *offset + 4 > data.len() { return false; }
            let len = i32::from_be_bytes(data[*offset..*offset + 4].try_into().unwrap()) as usize;
            *offset += 4 + len * 8;
            *offset <= data.len()
        }
        _ => false,
    }
}

fn parse_servers_dat(data: &[u8]) -> Vec<ServerInfo> {
    let mut servers = Vec::new();
    if data.is_empty() || data[0] != 10 {
        return servers; // Must start with root TAG_Compound (0x0A)
    }

    let mut offset = 1;
    // Skip root compound name
    if read_nbt_string(data, &mut offset).is_none() {
        return servers;
    }

    // Iterate root tags looking for TAG_List "servers"
    while offset < data.len() {
        let tag_type = data[offset];
        offset += 1;
        if tag_type == 0 {
            break; // Root TAG_End
        }

        let tag_name = match read_nbt_string(data, &mut offset) {
            Some(n) => n,
            None => break,
        };

        if tag_type == 9 && tag_name == "servers" {
            // Found TAG_List "servers"
            if offset + 5 > data.len() {
                break;
            }
            let elem_type = data[offset];
            let count = i32::from_be_bytes(data[offset + 1..offset + 5].try_into().unwrap());
            offset += 5;

            if elem_type == 10 && count > 0 {
                for _ in 0..count {
                    // Parse each individual server TAG_Compound strictly
                    let mut s_name = String::new();
                    let mut s_ip = String::new();
                    let mut s_icon = None;
                    let mut s_accept_textures = None;

                    while offset < data.len() {
                        let field_type = data[offset];
                        offset += 1;
                        if field_type == 0 {
                            break; // Compound TAG_End
                        }

                        let field_name = match read_nbt_string(data, &mut offset) {
                            Some(n) => n,
                            None => break,
                        };

                        match (field_type, field_name.as_str()) {
                            (8, "name") => {
                                if let Some(val) = read_nbt_string(data, &mut offset) {
                                    s_name = val;
                                }
                            }
                            (8, "ip") => {
                                if let Some(val) = read_nbt_string(data, &mut offset) {
                                    s_ip = val;
                                }
                            }
                            (8, "icon") => {
                                if let Some(val) = read_nbt_string(data, &mut offset) {
                                    if !val.trim().is_empty() {
                                        let formatted = if val.starts_with("data:image") {
                                            val
                                        } else {
                                            format!("data:image/png;base64,{val}")
                                        };
                                        s_icon = Some(formatted);
                                    }
                                }
                            }
                            (1, "acceptTextures") => {
                                if offset < data.len() {
                                    s_accept_textures = Some(data[offset]);
                                    offset += 1;
                                }
                            }
                            _ => {
                                if !skip_nbt_tag(data, &mut offset, field_type) {
                                    break;
                                }
                            }
                        }
                    }

                    if !s_ip.trim().is_empty() {
                        servers.push(ServerInfo {
                            name: if s_name.trim().is_empty() { s_ip.clone() } else { s_name },
                            ip: s_ip,
                            icon_base64: s_icon,
                            accept_textures: s_accept_textures,
                        });
                    }
                }
            }
            break;
        } else {
            if !skip_nbt_tag(data, &mut offset, tag_type) {
                break;
            }
        }
    }

    servers
}

/// Append a server to the instance's servers.dat file.
/// If servers.dat does not exist, creates a valid uncompressed NBT file.
#[tauri::command]
pub async fn add_server(
    state: State<'_, AppState>,
    directory: Option<String>,
    name: String,
    ip: String,
) -> Result<(), String> {
    let name = name.trim().to_string();
    let ip = ip.trim().to_string();

    if ip.is_empty() {
        return Err("Server address cannot be empty".to_string());
    }

    let display_name = if name.is_empty() { ip.clone() } else { name };

    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let servers_file = game_dir.join("servers.dat");

    // Build the NBT byte representation of a single server compound:
    // TAG_Compound (element in list, no name prefix)
    //   TAG_String "ip": [ip]
    //   TAG_String "name": [display_name]
    // TAG_End (0x00)
    let mut entry_bytes = Vec::new();
    
    // Tag: ip
    entry_bytes.push(0x08); // TAG_String
    entry_bytes.extend_from_slice(&(2u16).to_be_bytes()); // name length = 2
    entry_bytes.extend_from_slice(b"ip");
    let ip_bytes = ip.as_bytes();
    entry_bytes.extend_from_slice(&(ip_bytes.len() as u16).to_be_bytes());
    entry_bytes.extend_from_slice(ip_bytes);

    // Tag: name
    entry_bytes.push(0x08); // TAG_String
    entry_bytes.extend_from_slice(&(4u16).to_be_bytes()); // name length = 4
    entry_bytes.extend_from_slice(b"name");
    let name_bytes = display_name.as_bytes();
    entry_bytes.extend_from_slice(&(name_bytes.len() as u16).to_be_bytes());
    entry_bytes.extend_from_slice(name_bytes);

    // TAG_End
    entry_bytes.push(0x00);

    if !servers_file.exists() {
        // Create brand new servers.dat:
        // TAG_Compound "" (root)
        //   TAG_List "servers" of TAG_Compound (type 10), count 1
        //     [entry_bytes]
        // TAG_End (root)
        let mut new_file_bytes = Vec::new();
        new_file_bytes.push(0x0a); // TAG_Compound
        new_file_bytes.extend_from_slice(&(0u16).to_be_bytes()); // root name length = 0
        
        new_file_bytes.push(0x09); // TAG_List
        new_file_bytes.extend_from_slice(&(7u16).to_be_bytes()); // name len = 7
        new_file_bytes.extend_from_slice(b"servers");
        new_file_bytes.push(0x0a); // list element type = TAG_Compound
        new_file_bytes.extend_from_slice(&(1i32).to_be_bytes()); // count = 1
        new_file_bytes.extend_from_slice(&entry_bytes);
        new_file_bytes.push(0x00); // TAG_End for root compound

        std::fs::write(&servers_file, new_file_bytes).map_err(|e| format!("Failed to create servers.dat: {e}"))?;
        return Ok(());
    }

    // Existing file: read and insert entry
    let mut data = std::fs::read(&servers_file).map_err(|e| format!("Failed to read servers.dat: {e}"))?;

    // Find TAG_List "servers"
    // Pattern: 0x09 (TAG_List), 0x00, 0x07, 's', 'e', 'r', 'v', 'e', 'r', 's'
    let list_needle = [0x09, 0x00, 0x07, b's', b'e', b'r', b'v', b'e', b'r', b's'];
    if let Some(pos) = data.windows(list_needle.len()).position(|w| w == list_needle) {
        let count_offset = pos + list_needle.len() + 1; // +1 to skip list element type (0x0a)
        if count_offset + 4 <= data.len() {
            let current_count = i32::from_be_bytes(data[count_offset..count_offset + 4].try_into().unwrap());
            let new_count = current_count + 1;
            data[count_offset..count_offset + 4].copy_from_slice(&new_count.to_be_bytes());

            // Insert entry right after list header
            let insert_pos = count_offset + 4;
            data.splice(insert_pos..insert_pos, entry_bytes);

            std::fs::write(&servers_file, data).map_err(|e| format!("Failed to update servers.dat: {e}"))?;
            return Ok(());
        }
    }

    // Fallback if list structure was not recognized: re-create fresh list with existing servers + new server
    let mut existing_servers = parse_servers_dat(&data);
    existing_servers.push(ServerInfo {
        name: display_name,
        ip,
        icon_base64: None,
        accept_textures: None,
    });

    let mut new_file_bytes = Vec::new();
    new_file_bytes.push(0x0a); // TAG_Compound
    new_file_bytes.extend_from_slice(&(0u16).to_be_bytes());
    new_file_bytes.push(0x09);
    new_file_bytes.extend_from_slice(&(7u16).to_be_bytes());
    new_file_bytes.extend_from_slice(b"servers");
    new_file_bytes.push(0x0a);
    new_file_bytes.extend_from_slice(&(existing_servers.len() as i32).to_be_bytes());

    for s in &existing_servers {
        new_file_bytes.push(0x08);
        new_file_bytes.extend_from_slice(&(2u16).to_be_bytes());
        new_file_bytes.extend_from_slice(b"ip");
        let s_ip = s.ip.as_bytes();
        new_file_bytes.extend_from_slice(&(s_ip.len() as u16).to_be_bytes());
        new_file_bytes.extend_from_slice(s_ip);

        new_file_bytes.push(0x08);
        new_file_bytes.extend_from_slice(&(4u16).to_be_bytes());
        new_file_bytes.extend_from_slice(b"name");
        let s_name = s.name.as_bytes();
        new_file_bytes.extend_from_slice(&(s_name.len() as u16).to_be_bytes());
        new_file_bytes.extend_from_slice(s_name);

        new_file_bytes.push(0x00);
    }
    new_file_bytes.push(0x00);

    std::fs::write(&servers_file, new_file_bytes).map_err(|e| format!("Failed to write servers.dat: {e}"))
}

/// Removes a server entry from servers.dat by its IP address.
#[tauri::command]
pub async fn delete_server(
    state: State<'_, AppState>,
    directory: Option<String>,
    ip: String,
) -> Result<(), String> {
    let ip = ip.trim().to_string();
    if ip.is_empty() {
        return Err("Server IP cannot be empty".to_string());
    }

    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let servers_file = game_dir.join("servers.dat");
    if !servers_file.exists() {
        return Err("servers.dat not found".to_string());
    }

    let data = std::fs::read(&servers_file).map_err(|e| format!("Failed to read servers.dat: {e}"))?;
    let mut servers = parse_servers_dat(&data);
    let before = servers.len();
    servers.retain(|s| s.ip.trim() != ip.as_str());
    if servers.len() == before {
        return Err(format!("Server '{}' not found", ip));
    }

    // Re-serialise the whole file
    let mut new_file_bytes: Vec<u8> = Vec::new();
    new_file_bytes.push(0x0a);
    new_file_bytes.extend_from_slice(&(0u16).to_be_bytes());

    new_file_bytes.push(0x09);
    new_file_bytes.extend_from_slice(&(7u16).to_be_bytes());
    new_file_bytes.extend_from_slice(b"servers");
    new_file_bytes.push(0x0a);
    new_file_bytes.extend_from_slice(&(servers.len() as i32).to_be_bytes());

    for s in &servers {
        new_file_bytes.push(0x08);
        new_file_bytes.extend_from_slice(&(2u16).to_be_bytes());
        new_file_bytes.extend_from_slice(b"ip");
        let s_ip = s.ip.as_bytes();
        new_file_bytes.extend_from_slice(&(s_ip.len() as u16).to_be_bytes());
        new_file_bytes.extend_from_slice(s_ip);

        new_file_bytes.push(0x08);
        new_file_bytes.extend_from_slice(&(4u16).to_be_bytes());
        new_file_bytes.extend_from_slice(b"name");
        let s_name = s.name.as_bytes();
        new_file_bytes.extend_from_slice(&(s_name.len() as u16).to_be_bytes());
        new_file_bytes.extend_from_slice(s_name);

        new_file_bytes.push(0x00);
    }
    new_file_bytes.push(0x00);

    std::fs::write(&servers_file, new_file_bytes)
        .map_err(|e| format!("Failed to write servers.dat: {e}"))
}

#[derive(Serialize, Clone, Debug)]
pub struct ServerPingStatus {
    pub online: bool,
    pub motd: Option<String>,
    pub players_online: Option<u64>,
    pub players_max: Option<u64>,
    pub version: Option<String>,
    pub latency_ms: Option<u64>,
    pub favicon: Option<String>,
}

fn write_varint(buf: &mut Vec<u8>, mut value: i32) {
    loop {
        if (value & !0x7F) == 0 {
            buf.push(value as u8);
            return;
        }
        buf.push(((value & 0x7F) | 0x80) as u8);
        value = ((value as u32) >> 7) as i32;
    }
}

fn read_varint_sync<R: std::io::Read>(stream: &mut R) -> Result<i32, String> {
    let mut num_read = 0;
    let mut result = 0;
    let mut buf = [0u8; 1];
    loop {
        stream.read_exact(&mut buf).map_err(|e| e.to_string())?;
        let value = buf[0] & 0b0111_1111;
        result |= (value as i32) << (7 * num_read);
        num_read += 1;
        if num_read > 5 {
            return Err("VarInt is too big".to_string());
        }
        if (buf[0] & 0b1000_0000) == 0 {
            break;
        }
    }
    Ok(result)
}

fn extract_motd_text(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Object(obj) => {
            let mut out = String::new();
            if let Some(text) = obj.get("text").and_then(|t| t.as_str()) {
                out.push_str(text);
            }
            if let Some(extra) = obj.get("extra").and_then(|e| e.as_array()) {
                for item in extra {
                    out.push_str(&extract_motd_text(item));
                }
            }
            out
        }
        serde_json::Value::Array(arr) => {
            let mut out = String::new();
            for item in arr {
                out.push_str(&extract_motd_text(item));
            }
            out
        }
        _ => String::new(),
    }
}

/// Ping a Minecraft Java server using the standard Server List Ping (SLP) protocol.
#[tauri::command]
pub async fn ping_server(server_ip: String) -> Result<ServerPingStatus, String> {
    tokio::task::spawn_blocking(move || {
        let trimmed = server_ip.trim();
        let (host, port) = if let Some((h, p)) = trimmed.split_once(':') {
            (h.to_string(), p.parse::<u16>().unwrap_or(25565))
        } else {
            (trimmed.to_string(), 25565)
        };

        if host.is_empty() {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: None,
                favicon: None,
            });
        }

        let addr = format!("{}:{}", host, port);
        let timeout = std::time::Duration::from_millis(3500);

        let t0 = std::time::Instant::now();
        let socket_addr = match std::net::ToSocketAddrs::to_socket_addrs(&addr) {
            Ok(mut iter) => match iter.next() {
                Some(sa) => sa,
                None => {
                    return Ok(ServerPingStatus {
                        online: false,
                        motd: None,
                        players_online: None,
                        players_max: None,
                        version: None,
                        latency_ms: None,
                        favicon: None,
                    });
                }
            },
            Err(_) => {
                return Ok(ServerPingStatus {
                    online: false,
                    motd: None,
                    players_online: None,
                    players_max: None,
                    version: None,
                    latency_ms: None,
                    favicon: None,
                });
            }
        };

        let mut stream = match std::net::TcpStream::connect_timeout(&socket_addr, timeout) {
            Ok(s) => s,
            Err(_) => {
                return Ok(ServerPingStatus {
                    online: false,
                    motd: None,
                    players_online: None,
                    players_max: None,
                    version: None,
                    latency_ms: None,
                    favicon: None,
                });
            }
        };
        let latency_ms = t0.elapsed().as_millis() as u64;

        let _ = stream.set_read_timeout(Some(timeout));
        let _ = stream.set_write_timeout(Some(timeout));

        // 1. Handshake packet: packet ID 0x00, protocol version 765 (1.20.4), host, port, next state 1 (status)
        let mut handshake_payload = Vec::new();
        write_varint(&mut handshake_payload, 0x00); // packet id
        write_varint(&mut handshake_payload, 765);  // protocol version
        let host_bytes = host.as_bytes();
        write_varint(&mut handshake_payload, host_bytes.len() as i32);
        handshake_payload.extend_from_slice(host_bytes);
        handshake_payload.extend_from_slice(&port.to_be_bytes());
        write_varint(&mut handshake_payload, 1);     // next state: 1 for status

        let mut handshake_packet = Vec::new();
        write_varint(&mut handshake_packet, handshake_payload.len() as i32);
        handshake_packet.extend_from_slice(&handshake_payload);

        use std::io::Write;
        if stream.write_all(&handshake_packet).is_err() {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            });
        }

        // 2. Status request packet: length 1, packet ID 0x00
        let status_request = [0x01, 0x00];
        if stream.write_all(&status_request).is_err() {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            });
        }

        // 3. Response: Packet Length (VarInt), Packet ID (0x00), JSON string length (VarInt), JSON string bytes
        let packet_length = match read_varint_sync(&mut stream) {
            Ok(l) => l as usize,
            Err(_) => {
                return Ok(ServerPingStatus {
                    online: false,
                    motd: None,
                    players_online: None,
                    players_max: None,
                    version: None,
                    latency_ms: Some(latency_ms),
                    favicon: None,
                });
            }
        };

        if packet_length == 0 || packet_length > 1_000_000 {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            });
        }

        let _packet_id = match read_varint_sync(&mut stream) {
            Ok(id) => id,
            Err(_) => return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            }),
        };

        let json_length = match read_varint_sync(&mut stream) {
            Ok(l) => l as usize,
            Err(_) => return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            }),
        };

        if json_length == 0 || json_length > 1_000_000 {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            });
        }

        let mut json_bytes = vec![0u8; json_length];
        if stream.read_exact(&mut json_bytes).is_err() {
            return Ok(ServerPingStatus {
                online: false,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            });
        }

        let json_str = String::from_utf8_lossy(&json_bytes);
        let parsed: serde_json::Value = match serde_json::from_str(&json_str) {
            Ok(v) => v,
            Err(_) => return Ok(ServerPingStatus {
                online: true,
                motd: None,
                players_online: None,
                players_max: None,
                version: None,
                latency_ms: Some(latency_ms),
                favicon: None,
            }),
        };

        let motd = parsed.get("description").map(extract_motd_text).filter(|s| !s.trim().is_empty());
        let players_online = parsed.get("players").and_then(|p| p.get("online")).and_then(|o| o.as_u64());
        let players_max = parsed.get("players").and_then(|p| p.get("max")).and_then(|m| m.as_u64());
        let version = parsed.get("version").and_then(|v| v.get("name")).and_then(|n| n.as_str()).map(|s| s.to_string());
        let favicon = parsed.get("favicon").and_then(|f| f.as_str()).map(|s| s.to_string());

        Ok(ServerPingStatus {
            online: true,
            motd,
            players_online,
            players_max,
            version,
            latency_ms: Some(latency_ms),
            favicon,
        })
    }).await.map_err(|e| e.to_string())?
}

/// Open the saves (worlds) folder in the system file manager.
#[tauri::command]
pub async fn open_worlds_folder(
    state: State<'_, AppState>,
    directory: Option<String>,
) -> Result<(), String> {
    let game_dir = directory
        .filter(|s| !s.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| state.settings.lock().unwrap().resolved_game_directory());

    let saves_dir = game_dir.join("saves");
    if !saves_dir.exists() {
        let _ = std::fs::create_dir_all(&saves_dir);
    }
    crate::commands::open_folder_in_file_manager(&saves_dir)
}

/// Read a world's `icon.png` and return as base64 data URL
#[tauri::command]
pub async fn read_world_icon(path: String) -> Result<String, String> {
    let icon_path = PathBuf::from(&path).join("icon.png");
    if !icon_path.exists() {
        return Err("Icon not found".to_string());
    }

    let bytes = tokio::task::spawn_blocking(move || std::fs::read(&icon_path))
        .await
        .map_err(|e| format!("Failed to read world icon: {e}"))?
        .map_err(|e| format!("Failed to read world icon: {e}"))?;

    use base64::Engine;
    let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
    Ok(format!("data:image/png;base64,{encoded}"))
}

/// Delete a Minecraft world folder permanently.
#[tauri::command]
pub async fn delete_world(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    if !p.exists() {
        return Ok(()); // Already gone — treat as success
    }
    // Safety: must be a directory (worlds are always folders)
    if !p.is_dir() {
        return Err("Path is not a directory".to_string());
    }
    std::fs::remove_dir_all(&p).map_err(|e| format!("Failed to delete world: {e}"))
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
