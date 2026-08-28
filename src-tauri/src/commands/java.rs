//! Smart Java detection & management.
//!
//! This module is responsible for everything Java-related that the
//! launcher needs beyond just "run `java`":
//!
//! - Listing every Java install we can find, both system-installed ones
//!   (via [`mc_launcher_core`]'s own scanner) and ones this launcher
//!   previously downloaded itself into the managed `java/` folder.
//! - Figuring out which Java **major version** a given Minecraft version
//!   actually needs (using the launcher core's compatibility hints and the
//!   version manifest's own `javaVersion` field).
//! - "Smart Java Detection": picking the best already-installed Java for an
//!   instance, and — if nothing suitable is installed — downloading a
//!   matching build automatically from Azul's Zulu builds and extracting it
//!   into the managed folder so it's available next time without asking
//!   the user to do anything.

use std::env;
use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};

use mc_launcher_core::compatibility::apply_compatibility;
use mc_launcher_core::core::version::VersionJson;
use mc_launcher_core::platform::Platform;
use mc_launcher_core::prelude::CompatibilityPolicy;
use mc_launcher_core::utils::java::{find_system_java_versions_information, get_java_information};

use crate::logger;
use crate::state::AppState;

/// A single detected (or previously downloaded) Java installation.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JavaInstallation {
    /// Java "home" directory (the folder containing `bin/`).
    pub path: String,
    /// Full path to the `java` / `java.exe` executable itself.
    pub executable: String,
    /// Raw version string as reported by `java -version` (e.g. "17.0.9").
    pub version: String,
    /// Parsed major version (8, 11, 17, 21, ...).
    pub major: i32,
    pub is_64bit: bool,
    /// "system" (found on disk / in PATH-like locations) or "managed"
    /// (downloaded by this launcher into its own `java/` folder).
    pub source: String,
}

/// Progress updates emitted while Smart Java Detection is downloading a
/// missing runtime, so the UI can show something better than a frozen
/// "Launching..." state.
#[derive(Debug, Clone, Serialize)]
pub struct JavaProgressInfo {
    pub major: i32,
    /// "checking" | "downloading" | "extracting" | "done" | "error"
    pub stage: String,
    pub message: String,
    pub percent: f64,
}

fn emit_progress(app: &AppHandle, major: i32, stage: &str, message: &str, percent: f64) {
    let _ = app.emit(
        "java-install-progress",
        &JavaProgressInfo {
            major,
            stage: stage.to_string(),
            message: message.to_string(),
            percent,
        },
    );
}

/// Root folder where auto-downloaded Java runtimes are kept, one
/// subdirectory per major version (e.g. `java/17`, `java/21`).
///
/// - Windows: `%APPDATA%/Zero Launcher/java`
/// - Linux / macOS: `~/Zero Launcher/java`
pub fn managed_java_root() -> PathBuf {
    if cfg!(target_os = "windows") {
        dirs::config_dir()
            .map(|d| d.join("Zero Launcher").join("java"))
            .unwrap_or_else(|| PathBuf::from("Zero Launcher/java"))
    } else {
        dirs::home_dir()
            .map(|d| d.join("Zero Launcher").join("java"))
            .unwrap_or_else(|| PathBuf::from("Zero Launcher/java"))
    }
}

/// Parses a Java version string into just its major version number.
/// Handles both the old `1.8.0_392` style and the modern `17.0.9` style.
fn parse_major(version: &str) -> i32 {
    let v = version.trim();
    let leading_digits = |s: &str| -> Option<i32> {
        let num: String = s.chars().take_while(|c| c.is_ascii_digit()).collect();
        if num.is_empty() {
            None
        } else {
            num.parse().ok()
        }
    };
    if let Some(rest) = v.strip_prefix("1.") {
        leading_digits(rest).unwrap_or(8)
    } else {
        leading_digits(v).unwrap_or(0)
    }
}

fn to_installation(info: mc_launcher_core::types::JavaInformation) -> JavaInstallation {
    let major = parse_major(&info.version);
    let managed_root = managed_java_root();
    let source = if Path::new(&info.path).starts_with(&managed_root) {
        "managed"
    } else {
        "system"
    };
    JavaInstallation {
        path: info.path,
        executable: info.java_path,
        version: info.version,
        major,
        is_64bit: info.is_64bit,
        source: source.to_string(),
    }
}

/// Lists every Java installation we can find: common system locations
/// (`/usr/lib/jvm`, `C:\Program Files\Java`, ...) plus anything previously
/// auto-downloaded into the managed `java/` folder. Used to populate the
/// Settings dropdown and to power Smart Java Detection.
#[tauri::command]
pub async fn list_java_installations() -> Result<Vec<JavaInstallation>, String> {
    let managed_root = managed_java_root();
    let _ = fs::create_dir_all(&managed_root);

    let found = tokio::task::spawn_blocking(move || {
        find_system_java_versions_information(Some(vec![managed_root]))
    })
    .await
    .map_err(|e| format!("Java scan failed: {e}"))?;

    let mut list: Vec<JavaInstallation> = found.into_iter().map(to_installation).collect();

    // Dedupe by the *canonicalized* executable path. Different scan roots
    // (system locations, symlinks like `default-java`, PATH, the managed
    // folder) can all point at the same underlying JDK, and those paths
    // won't necessarily end up adjacent after sorting — so a plain
    // `dedup_by` (which only removes consecutive duplicates) misses most of
    // them. Canonicalizing first also collapses `/usr/bin/java` ->
    // `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`-style symlink chains.
    let mut seen = std::collections::HashSet::new();
    list.retain(|j| {
        let key = fs::canonicalize(&j.executable)
            .unwrap_or_else(|_| PathBuf::from(&j.executable));
        seen.insert(key)
    });

    list.sort_by(|a, b| b.major.cmp(&a.major).then_with(|| a.path.cmp(&b.path)));
    Ok(list)
}

/// Determines the Java major version a given (already-loaded) Minecraft
/// version needs. Prefers the launcher core's own compatibility hint
/// (which accounts for special cases, e.g. legacy LWJGL2 versions needing
/// Java 8 on Apple Silicon), falls back to the version manifest's own
/// `javaVersion` field, and finally assumes Java 8 for very old versions
/// that predate that field entirely.
pub fn required_java_major(version: &VersionJson) -> i32 {
    let platform = Platform::current();
    let compat = apply_compatibility(version, platform, CompatibilityPolicy::Auto);
    if let Some(hint) = compat.java_runtime {
        return hint.major_version;
    }
    version
        .java_version
        .as_ref()
        .map(|j| j.major_version)
        .unwrap_or(8)
}

fn jvm_os_string() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

fn jvm_arch_string() -> &'static str {
    match env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "aarch64",
        "x86" => "x86",
        other => other,
    }
}

fn archive_extension() -> &'static str {
    if cfg!(target_os = "windows") {
        "zip"
    } else {
        "tar.gz"
    }
}

#[derive(Debug, Deserialize)]
struct AzulPackage {
    download_url: String,
    #[serde(default)]
    name: String,
}

/// Finds a `java` executable inside this launcher's own managed `java/`
/// folder, if one has been downloaded before (via Smart Java Detection or
/// a previous install). Used so installer-running code (Forge/NeoForge)
/// can point straight at a known-good runtime instead of guessing from the
/// environment (`PATH`/`JAVA_HOME`), which frequently isn't set up at all
/// for a launcher that manages its own JREs — that gap was the cause of
/// `io error: No such file or directory (os error 2)` when running the
/// loader installer jar.
///
/// Picks the newest-looking managed install if more than one is present
/// (managed folders are named by major version, e.g. `21`, `17`), since a
/// newer JRE is more likely to satisfy a loader installer's own
/// requirements. Returns `None` if nothing has been downloaded yet — in
/// that case the caller should fall back to system detection.
pub fn find_any_managed_java() -> Option<PathBuf> {
    let root = managed_java_root();
    let mut entries: Vec<PathBuf> = fs::read_dir(&root)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    // Sort so higher major-version folder names (e.g. "21" over "17") are
    // tried first; falls back gracefully to lexical order for anything
    // that isn't a bare number.
    entries.sort_by_key(|p| {
        p.file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| n.parse::<i32>().ok())
            .unwrap_or(0)
    });
    entries.reverse();
    for dir in entries {
        if let Some(home) = find_java_home(&dir, 4) {
            let exe_name = if cfg!(target_os = "windows") { "java.exe" } else { "java" };
            let exe = home.join("bin").join(exe_name);
            if exe.is_file() {
                return Some(exe);
            }
        }
    }
    None
}

/// Walks up to `max_depth` directories looking for a `bin/java(.exe)`, so
/// we can find the actual Java home inside an extracted archive regardless
/// of how many wrapper folders it's nested in.
fn find_java_home(root: &Path, max_depth: u32) -> Option<PathBuf> {
    let java_exe_name = if cfg!(target_os = "windows") {
        "java.exe"
    } else {
        "java"
    };
    if root.join("bin").join(java_exe_name).is_file() {
        return Some(root.to_path_buf());
    }
    if max_depth == 0 {
        return None;
    }
    if let Ok(entries) = fs::read_dir(root) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                if let Some(found) = find_java_home(&path, max_depth - 1) {
                    return Some(found);
                }
            }
        }
    }
    None
}

/// Recursive directory copy, used only as a fallback for the rare case
/// where a plain rename fails (e.g. crossing filesystems/drives).
fn copy_dir_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let dest_path = dst.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_all(&entry.path(), &dest_path)?;
        } else {
            fs::copy(entry.path(), &dest_path)?;
        }
    }
    Ok(())
}

/// Downloads a GA JDK build for `major` from Azul's Zulu metadata API for
/// the current OS/architecture, extracts it into the managed `java/`
/// folder, and returns the resulting installation. This is what "Smart
/// Java Detection" falls back to when no suitable Java is already
/// installed on the system.
async fn download_java_via_azul(app: &AppHandle, major: i32) -> Result<JavaInstallation, String> {
    emit_progress(
        app,
        major,
        "checking",
        &format!("Looking up a Java {major} build for your system..."),
        0.0,
    );

    let client = reqwest::Client::builder()
        // See vendor/mc-launcher-core's http.rs client() for why: forces
        // IPv4 so a broken/non-routable IPv6 setup can't stall the Java
        // lookup/download waiting on a dead address before falling back.
        .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());
    let query_url = format!(
        "https://api.azul.com/metadata/v1/zulu/packages/?java_version={major}&os={os}&arch={arch}&archive_type={archive}&java_package_type=jdk&javafx_bundled=false&release_status=ga&availability_types=CA&latest=true&page=1&page_size=1",
        major = major,
        os = jvm_os_string(),
        arch = jvm_arch_string(),
        archive = archive_extension(),
    );

    let packages: Vec<AzulPackage> = client
        .get(&query_url)
        .header("User-Agent", "ZeroLauncher")
        .send()
        .await
        .map_err(|e| format!("Couldn't reach Azul's Java servers: {e}"))?
        .json()
        .await
        .map_err(|e| format!("Couldn't understand Azul's Java server response: {e}"))?;

    let package = packages.into_iter().next().ok_or_else(|| {
        format!("No Java {major} build is available from Azul for this system ({}/{})", jvm_os_string(), jvm_arch_string())
    })?;

    emit_progress(
        app,
        major,
        "downloading",
        &format!("Downloading {}...", package.name),
        10.0,
    );

    // Each Java runtime download gets its own cancel-flag id (keyed by
    // major version, since only one download per major can be in flight)
    // so it shows as its own cancellable card in the downloads menu, same
    // as mod downloads/updates.
    let download_id = format!("java-{major}");
    let state = app.state::<crate::state::AppState>();
    let cancel_flag = state.generic_cancel_flag(&download_id);

    let mut resp = client
        .get(&package.download_url)
        .header("User-Agent", "ZeroLauncher")
        .send()
        .await
        .map_err(|e| {
            state.finish_generic_download(&download_id);
            format!("Failed to download Java: {e}")
        })?;

    let expected_len = resp.content_length();

    // Same throttled progress emission as discover_download — shares the
    // "generic-download-progress" event/id scheme so the frontend's one
    // listener drives every card's percent/speed/downloaded stat the same
    // way, regardless of which backend command is doing the fetching.
    let start = std::time::Instant::now();
    let mut last_emit = start;
    let mut bytes_since_emit: u64 = 0;
    let emit_every = std::time::Duration::from_millis(120);

    let mut bytes_buf: Vec<u8> = Vec::new();
    loop {
        if cancel_flag.load(Ordering::Relaxed) {
            state.finish_generic_download(&download_id);
            return Err("Java download cancelled".to_string());
        }
        match resp.chunk().await {
            Ok(Some(chunk)) => {
                bytes_since_emit += chunk.len() as u64;
                bytes_buf.extend_from_slice(&chunk);
                let now = std::time::Instant::now();
                let elapsed = now.duration_since(last_emit);
                if elapsed >= emit_every {
                    let speed_bps = bytes_since_emit as f64 / elapsed.as_secs_f64().max(0.001);
                    let _ = app.emit(
                        "generic-download-progress",
                        crate::commands::discover::GenericDownloadProgress::new(
                            download_id.clone(),
                            bytes_buf.len() as u64,
                            expected_len,
                            speed_bps,
                            package.name.clone(),
                        ),
                    );
                    last_emit = now;
                    bytes_since_emit = 0;
                }
            }
            Ok(None) => break,
            Err(e) => {
                state.finish_generic_download(&download_id);
                return Err(format!("Failed to download Java: {e}"));
            }
        }
    }
    state.finish_generic_download(&download_id);

    emit_progress(
        app,
        major,
        "extracting",
        &format!("Extracting Java {major}..."),
        60.0,
    );

    let archive_kind = archive_extension();
    let managed_root = managed_java_root();
    let final_dir = managed_root.join(major.to_string());
    let bytes_vec = bytes_buf;

    let installed_dir = tokio::task::spawn_blocking(move || -> Result<PathBuf, String> {
        fs::create_dir_all(&managed_root)
            .map_err(|e| format!("Failed to create the managed java folder: {e}"))?;

        let tmp_dir = managed_root.join(format!(".tmp_{}_{}", major, uuid::Uuid::new_v4()));
        fs::create_dir_all(&tmp_dir)
            .map_err(|e| format!("Failed to create a temporary extraction folder: {e}"))?;

        let extract_result = if archive_kind == "zip" {
            let cursor = Cursor::new(bytes_vec);
            zip::ZipArchive::new(cursor)
                .map_err(|e| format!("Failed to read the downloaded Java archive: {e}"))
                .and_then(|mut archive| {
                    archive
                        .extract(&tmp_dir)
                        .map_err(|e| format!("Failed to extract Java: {e}"))
                })
        } else {
            let cursor = Cursor::new(bytes_vec);
            let gz = flate2::read::GzDecoder::new(cursor);
            let mut archive = tar::Archive::new(gz);
            archive
                .unpack(&tmp_dir)
                .map_err(|e| format!("Failed to extract Java: {e}"))
        };

        if let Err(e) = extract_result {
            let _ = fs::remove_dir_all(&tmp_dir);
            return Err(e);
        }

        let java_home = find_java_home(&tmp_dir, 3).ok_or_else(|| {
            "The downloaded Java archive didn't contain a recognizable Java installation".to_string()
        })?;

        if final_dir.exists() {
            let _ = fs::remove_dir_all(&final_dir);
        }
        if let Some(parent) = final_dir.parent() {
            let _ = fs::create_dir_all(parent);
        }

        if fs::rename(&java_home, &final_dir).is_err() {
            // Cross-device (e.g. tmp on a different drive/mount): fall
            // back to copying instead of moving.
            copy_dir_all(&java_home, &final_dir)
                .map_err(|e| format!("Failed to install the downloaded Java: {e}"))?;
        }

        let _ = fs::remove_dir_all(&tmp_dir);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let bin_java = final_dir.join("bin").join("java");
            if let Ok(meta) = fs::metadata(&bin_java) {
                let mut perms = meta.permissions();
                perms.set_mode(perms.mode() | 0o111);
                let _ = fs::set_permissions(&bin_java, perms);
            }
        }

        Ok(final_dir)
    })
    .await
    .map_err(|e| format!("Java extraction task failed: {e}"))??;

    let info = get_java_information(&installed_dir)
        .map_err(|e| format!("Downloaded Java but couldn't verify it afterwards: {e}"))?;

    emit_progress(app, major, "done", &format!("Java {major} is ready"), 100.0);

    Ok(to_installation(info))
}

/// Resolves which Java executable should be used to run the given
/// (already-loaded) Minecraft version, honoring the user's settings:
///
/// - If the user picked a specific Java install in Settings (anything
///   other than "Smart Java Detection"), that exact install is used as-is
///   — no auto-detection, no auto-download.
/// - Otherwise ("Smart Java Detection", the default): figure out which
///   Java major version this instance needs, look for a match among
///   system + previously-downloaded installs, and if nothing suitable is
///   found, download it automatically from Azul's Zulu builds into the
///   managed `java/` folder so it's available going forward.
pub async fn ensure_java_for_version(
    app: &AppHandle,
    state: &State<'_, AppState>,
    version: &VersionJson,
    offline: bool,
) -> Result<PathBuf, String> {
    let configured = state.settings.lock().unwrap().java_path.clone();

    if let Some(raw_path) = configured {
        let raw_path = raw_path.trim();
        if !raw_path.is_empty() {
            let p = PathBuf::from(raw_path);
            // Accept either a direct path to the `java`/`java.exe`
            // executable, or a path to the Java home directory (what the
            // Settings dropdown actually stores).
            if p.is_file() {
                return Ok(p);
            }
            let exe_name = if cfg!(target_os = "windows") { "java.exe" } else { "java" };
            let exe = p.join("bin").join(exe_name);
            if exe.is_file() {
                return Ok(exe);
            }
            return Err(format!(
                "The Java install selected in Settings ('{}') no longer exists. Pick another one, or switch back to Smart Java Detection.",
                raw_path
            ));
        }
    }

    let required_major = required_java_major(version);

    let installations = list_java_installations().await?;
    if let Some(exact) = installations.iter().find(|j| j.major == required_major) {
        return Ok(PathBuf::from(&exact.executable));
    }

    // No exact match installed: prefer downloading the exact version Java
    // itself asks for, since that's what Mojang actually tested against.
    if offline {
        return Err(format!(
            "Java {required_major} isn't installed, and this is an offline launch \
             (so it can't be downloaded automatically). Launch online once to let \
             Smart Java Detection fetch it, or install a matching Java yourself \
             in Settings."
        ));
    }
    logger::info(
        app,
        state,
        "LAUNCHER",
        &format!("No Java {required_major} found; Smart Java Detection is downloading it..."),
    );
    let downloaded = match download_java_via_azul(app, required_major).await {
        Ok(d) => d,
        Err(e) => {
            emit_progress(app, required_major, "error", &e, 0.0);
            return Err(e);
        }
    };
    Ok(PathBuf::from(&downloaded.executable))
}
