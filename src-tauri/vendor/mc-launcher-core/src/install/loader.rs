//! Loader profile writing and installer process helpers.

use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use serde_json::Value;

use crate::{core::version::VersionJson, loader::LoaderKind, LauncherError, Result};

/// Returns the standard local path for a loader profile JSON.
pub fn loader_profile_path(minecraft_dir: impl AsRef<Path>, version_id: &str) -> PathBuf {
    minecraft_dir
        .as_ref()
        .join("versions")
        .join(version_id)
        .join(format!("{version_id}.json"))
}

/// Writes a loader profile JSON to `<minecraft_dir>/versions/<id>/<id>.json`.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the profile has no `id`, the directory
/// cannot be created, or the profile cannot be serialized.
pub fn write_loader_profile(
    minecraft_dir: impl AsRef<Path>,
    profile: &VersionJson,
) -> Result<PathBuf> {
    let version_id = profile
        .id
        .as_deref()
        .ok_or_else(|| LauncherError::MissingField {
            context: "loader profile".to_string(),
            field: "id".to_string(),
        })?;
    let path = loader_profile_path(minecraft_dir, version_id);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(&path, serde_json::to_vec_pretty(profile)?)?;
    Ok(path)
}

/// Process inputs for a Java-based loader installer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallerInvocation {
    /// Loader family being installed.
    pub loader: LoaderKind,
    /// Java executable used to run the installer jar.
    pub java_executable: PathBuf,
    /// Downloaded installer jar path.
    pub installer_path: PathBuf,
    /// Minecraft directory passed to the installer.
    pub minecraft_dir: PathBuf,
}

/// Builds the argument list used to run a loader installer jar.
pub fn installer_command_args(invocation: &InstallerInvocation) -> Vec<String> {
    vec![
        "-jar".to_string(),
        invocation.installer_path.to_string_lossy().to_string(),
        "--installClient".to_string(),
        invocation.minecraft_dir.to_string_lossy().to_string(),
    ]
}

/// Creates a minimal, valid `launcher_profiles.json` in `minecraft_dir` if
/// one doesn't already exist *or* the existing one isn't something the
/// Forge/NeoForge installer will accept (empty, corrupted, or missing the
/// `profiles` key it checks for). A file that looks like real launcher
/// data — valid JSON with a `profiles` object, whatever else it does or
/// doesn't contain — is left completely alone, so this never clobbers an
/// actual Mojang launcher install sharing the same directory.
fn ensure_launcher_profiles_json(minecraft_dir: &Path) -> Result<()> {
    let path = minecraft_dir.join("launcher_profiles.json");
    if let Ok(existing) = fs::read(&path) {
        if let Ok(value) = serde_json::from_slice::<Value>(&existing) {
            if value.get("profiles").is_some() {
                return Ok(());
            }
        }
    }
    fs::create_dir_all(minecraft_dir)?;
    let minimal = serde_json::json!({
        "profiles": {},
        "settings": {},
        "version": 3,
    });
    fs::write(&path, serde_json::to_vec_pretty(&minimal)?)?;
    Ok(())
}

/// Runs a Java-based loader installer.
///
/// Captures the installer's stdout and stderr (merged, in the order the
/// process wrote them) instead of letting them go to the launcher's own
/// inherited streams, so a failure comes back with the installer's actual
/// diagnostic output attached — that's almost always what actually
/// explains a Forge/NeoForge install failure (missing/incompatible Java,
/// a corrupted download, a profile it doesn't like, etc.), not just the
/// bare exit code.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the installer process cannot be
/// started or exits with a non-zero status.
pub fn run_loader_installer(invocation: &InstallerInvocation) -> Result<()> {
    // The official Forge/NeoForge installer refuses to run at all if it
    // doesn't find a `launcher_profiles.json` in the target directory —
    // it treats that file's presence as proof "you've run the [Mojang]
    // launcher first" (its own error message), even though nothing about
    // the actual install needs it. Since this launcher never creates that
    // file (it's a Mojang-launcher artifact, not something we use), every
    // headless install would otherwise fail this check. A minimal valid
    // one satisfies it.
    ensure_launcher_profiles_json(&invocation.minecraft_dir)?;

    #[allow(unused_mut)]
    let mut cmd = Command::new(&invocation.java_executable);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.stdout(std::process::Stdio::piped());
    cmd.stderr(std::process::Stdio::piped());

    // Forge/NeoForge installers write their own log file (e.g.
    // `<jar-name>.log`) next to themselves, resolved against the process's
    // current working directory if the jar path isn't absolute in however
    // Java ends up resolving it. Left unset, the installer inherits
    // *this* process's cwd — which, running from an AppImage or other
    // mounted/packaged launcher, can be a read-only mount, causing the
    // installer to fail opening its log file before it even gets to the
    // actual install (`FileNotFoundException: ... (Read-only file
    // system)`). Explicitly running it from the installer jar's own
    // directory — which we know is writable, since we just downloaded the
    // jar there — avoids that regardless of what directory launched us.
    if let Some(dir) = invocation.installer_path.parent() {
        cmd.current_dir(dir);
    }

    let output = cmd.args(installer_command_args(invocation)).output()?;

    if output.status.success() {
        Ok(())
    } else {
        let mut combined = String::new();
        combined.push_str(&String::from_utf8_lossy(&output.stdout));
        combined.push_str(&String::from_utf8_lossy(&output.stderr));
        let trimmed = combined.trim();

        // Keep only the tail — installer logs can run long and the actual
        // error is almost always in the last handful of lines — and format
        // it so it reads naturally appended after the summary message.
        const MAX_OUTPUT_CHARS: usize = 4000;
        let tail: String = if trimmed.chars().count() > MAX_OUTPUT_CHARS {
            let skip = trimmed.chars().count() - MAX_OUTPUT_CHARS;
            format!("…{}", trimmed.chars().skip(skip).collect::<String>())
        } else {
            trimmed.to_string()
        };

        let formatted_output = if tail.is_empty() {
            String::new()
        } else {
            format!("\n--- installer output ---\n{tail}")
        };

        // This specific installer jar predates Forge adding headless CLI
        // support at all (roughly pre-1.12.2) — it's a GUI-only Swing
        // installer, so `--installClient` isn't a bug on our end, it's
        // genuinely not a flag that build understands. Surface that in
        // plain language up front, since the raw stack trace alone
        // (`UnrecognizedOptionException`) doesn't make that obvious.
        let hint = if trimmed.contains("UnrecognizedOptionException") && trimmed.contains("installClient") {
            "\nThis Forge build's installer is too old to support automatic/headless install \
             (it only offers a graphical installer). Automatic install currently isn't \
             supported for this specific version."
        } else {
            ""
        };

        Err(LauncherError::InstallerFailed {
            loader: invocation.loader,
            status: output.status.code(),
            output: format!("{hint}{formatted_output}"),
        })
    }
}
