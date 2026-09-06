//! Native install path for old-format ("v1") Forge/NeoForge installer jars.
//!
//! Before Forge's installer gained headless CLI support (roughly the MC
//! 1.12.2 era — the "new" installer format with `spec`/`processors`/`data`),
//! `install_profile.json` inside the installer jar used a much simpler
//! schema: a `versionInfo` object (the actual version profile to write out)
//! plus an `install` object describing one extra artifact — the "universal"
//! jar — embedded inside the installer jar itself, along with the Maven
//! coordinate it should be copied to under `libraries/`. There are no
//! post-install "processors" to run (that's the newer-format concept, and
//! it needs a JVM to run external deobfuscation/patch tools); the whole
//! install is just "extract that one jar, write the version profile" — no
//! JVM invocation required at all.
//!
//! This is the same approach Prism Launcher and MultiMC use for installers
//! this old: the official jar for them is GUI-only and simply cannot be
//! driven headlessly, so the install steps it would have performed are
//! reimplemented directly instead of trying to invoke it.

use std::{
    fs,
    io::Read,
    path::Path,
};

use serde_json::Value;
use zip::ZipArchive;

use crate::{
    core::{maven::MavenCoordinate, version::VersionJson},
    install::loader::write_loader_profile,
    LauncherError, Result,
};

/// Returns `true` if `installer_path` uses the old (`v1`) install-profile
/// schema — a top-level `versionInfo` object and no `processors` array —
/// rather than the modern schema. Cheap: only reads `install_profile.json`
/// out of the jar, nothing is extracted or written.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the jar or its `install_profile.json`
/// cannot be read at all (a jar that simply lacks this file, rather than
/// having an unrecognized schema, is treated as an error since that means
/// it isn't a Forge/NeoForge installer we understand either way).
pub fn is_legacy_installer(installer_path: impl AsRef<Path>) -> Result<bool> {
    let profile = read_install_profile(installer_path.as_ref())?;
    Ok(profile.get("versionInfo").is_some() && profile.get("processors").is_none())
}

fn read_install_profile(installer_path: &Path) -> Result<Value> {
    let file = fs::File::open(installer_path)?;
    let mut archive = ZipArchive::new(file)?;
    let mut entry = archive
        .by_name("install_profile.json")
        .map_err(|_| LauncherError::MissingField {
            context: "Forge/NeoForge installer jar".to_string(),
            field: "install_profile.json".to_string(),
        })?;
    let mut contents = String::new();
    entry.read_to_string(&mut contents)?;
    drop(entry);
    Ok(serde_json::from_str(&contents)?)
}

/// Installs an old-format Forge/NeoForge installer jar natively: extracts
/// the embedded universal jar into `libraries/` at its Maven path and
/// writes the embedded version profile to `versions/<id>/<id>.json` — the
/// same two effects running the real (GUI) installer's "Install Client"
/// button would have had, without needing a display or a JVM at all.
///
/// Returns the installed version id, taken from the profile's own `id`
/// field, so the caller can load and use it exactly like any other
/// installed version.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if `install_profile.json` is missing
/// required fields, the embedded artifact can't be found inside the jar,
/// or the profile can't be written to disk.
pub fn install_legacy_forge(
    installer_path: impl AsRef<Path>,
    minecraft_dir: impl AsRef<Path>,
) -> Result<String> {
    let installer_path = installer_path.as_ref();
    let minecraft_dir = minecraft_dir.as_ref();
    let profile = read_install_profile(installer_path)?;

    let install = profile
        .get("install")
        .ok_or_else(|| LauncherError::MissingField {
            context: "install_profile.json".to_string(),
            field: "install".to_string(),
        })?;
    let file_path = install
        .get("filePath")
        .and_then(Value::as_str)
        .ok_or_else(|| LauncherError::MissingField {
            context: "install_profile.json install".to_string(),
            field: "filePath".to_string(),
        })?;
    let maven_path = install
        .get("path")
        .and_then(Value::as_str)
        .ok_or_else(|| LauncherError::MissingField {
            context: "install_profile.json install".to_string(),
            field: "path".to_string(),
        })?;

    let coordinate = MavenCoordinate::parse(maven_path)?;
    let destination = minecraft_dir
        .join("libraries")
        .join(coordinate.artifact_path());
    extract_entry(installer_path, file_path, &destination)?;

    let version_info = profile
        .get("versionInfo")
        .ok_or_else(|| LauncherError::MissingField {
            context: "install_profile.json".to_string(),
            field: "versionInfo".to_string(),
        })?;
    let version_json: VersionJson = serde_json::from_value(version_info.clone())?;
    let version_id = version_json
        .id
        .clone()
        .ok_or_else(|| LauncherError::MissingField {
            context: "legacy loader profile".to_string(),
            field: "id".to_string(),
        })?;
    write_loader_profile(minecraft_dir, &version_json)?;

    Ok(version_id)
}

/// Extracts one named entry from `installer_path` (a zip/jar) to
/// `destination`, creating parent directories as needed. Some installer
/// jars record the embedded resource's path with a leading slash in
/// `install_profile.json` even though it's stored without one — both forms
/// are tried.
fn extract_entry(installer_path: &Path, entry_name: &str, destination: &Path) -> Result<()> {
    let file = fs::File::open(installer_path)?;
    let mut archive = ZipArchive::new(file)?;

    let candidates = [
        entry_name.trim_start_matches('/').to_string(),
        entry_name.to_string(),
    ];
    let mut bytes: Option<Vec<u8>> = None;
    for name in &candidates {
        if let Ok(mut entry) = archive.by_name(name) {
            let mut buf = Vec::new();
            entry.read_to_end(&mut buf)?;
            bytes = Some(buf);
            break;
        }
    }
    let bytes = bytes.ok_or_else(|| LauncherError::MissingField {
        context: "Forge/NeoForge installer jar".to_string(),
        field: entry_name.to_string(),
    })?;

    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(destination, bytes)?;
    Ok(())
}
