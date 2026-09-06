//! Loader profile writing and installer process helpers.

use std::{
    fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::mpsc,
    thread,
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

/// Creates minimal, valid `launcher_profiles.json` and `launcher_profiles_microsoft_store.json`
/// files in `minecraft_dir` if they don't already exist or lack the `profiles` key.
/// Matching Nitrolaunch's installer preparation.
fn ensure_launcher_profiles_json(minecraft_dir: &Path) -> Result<()> {
    fs::create_dir_all(minecraft_dir)?;
    for filename in &["launcher_profiles.json", "launcher_profiles_microsoft_store.json"] {
        let path = minecraft_dir.join(filename);
        let needs_write = match fs::read(&path) {
            Ok(existing) => match serde_json::from_slice::<Value>(&existing) {
                Ok(value) => value.get("profiles").is_none(),
                Err(_) => true,
            },
            Err(_) => true,
        };
        if needs_write {
            let minimal = serde_json::json!({
                "profiles": {},
                "settings": {},
                "version": 3,
            });
            fs::write(&path, serde_json::to_vec_pretty(&minimal)?)?;
        }
    }
    Ok(())
}

/// Runs a Java-based loader installer, ignoring its output as it streams
/// (still captured for the error message on failure — see
/// [`run_loader_installer_with_output`]).
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the installer process cannot be
/// started or exits with a non-zero status.
pub fn run_loader_installer(invocation: &InstallerInvocation) -> Result<()> {
    run_loader_installer_with_output(invocation, &mut |_line: String| {})
}

/// Runs a Java-based loader installer, invoking `on_line` with each line of
/// its output (stdout and stderr, merged in the order they arrive) as soon
/// as it's written, instead of only becoming available once the process
/// exits. Forge/NeoForge installers can legitimately run for a couple of
/// minutes (they download and patch their own libraries independently of
/// this launcher's own downloader) with no other feedback otherwise — the
/// install isn't actually hung, but nothing said so.
///
/// The full output is still buffered internally regardless of `on_line`, so
/// a failure comes back with the installer's actual diagnostic output
/// attached — that's almost always what actually explains a Forge/NeoForge
/// install failure (missing/incompatible Java, a corrupted download, a
/// profile it doesn't like, etc.), not just the bare exit code.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the installer process cannot be
/// started or exits with a non-zero status.
pub fn run_loader_installer_with_output(
    invocation: &InstallerInvocation,
    on_line: &mut dyn FnMut(String),
) -> Result<()> {
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
    // Java ends up resolving it. Explicitly running it from the installer
    // jar's own directory ensures write access to write the log file.
    let work_dir = invocation.installer_path.parent();
    if let Some(dir) = work_dir {
        cmd.current_dir(dir);
    }

    cmd.args(installer_command_args(invocation));
    cmd.stdin(Stdio::null());
    let mut child = cmd.spawn()?;

    // Merge stdout and stderr into one ordered stream of lines via a
    // channel fed by two reader threads, so `on_line` sees output as the
    // process actually produces it rather than only after it exits. Also
    // accumulate everything for the error message below, same as before.
    let (tx, rx) = mpsc::channel::<String>();
    let mut readers = Vec::new();
    if let Some(stdout) = child.stdout.take() {
        let tx = tx.clone();
        readers.push(thread::spawn(move || {
            for line in BufReader::new(stdout).lines().map_while(|l| l.ok()) {
                let _ = tx.send(line);
            }
        }));
    }
    if let Some(stderr) = child.stderr.take() {
        let tx = tx.clone();
        readers.push(thread::spawn(move || {
            for line in BufReader::new(stderr).lines().map_while(|l| l.ok()) {
                let _ = tx.send(line);
            }
        }));
    }
    // Drop our own sender so `rx` iteration ends once both reader threads
    // finish (each holds its own clone, dropped when its thread returns).
    drop(tx);

    let mut combined = String::new();
    for line in rx {
        combined.push_str(&line);
        combined.push('\n');
        on_line(line);
    }
    for reader in readers {
        let _ = reader.join();
    }

    let status = child.wait()?;

    if status.success() {
        Ok(())
    } else {
        let trimmed = combined.trim();

        // Check if an installer log file was produced (e.g. `<installer_name>.log` or `<jar>.log`)
        let log_file_contents = work_dir.and_then(|dir| {
            let file_name = invocation.installer_path.file_name()?.to_string_lossy();
            let log_candidates = [
                dir.join(format!("{file_name}.log")),
                dir.join(format!("{}.log", file_name.trim_end_matches(".jar"))),
                dir.join("installer.log"),
            ];
            for candidate in &log_candidates {
                if let Ok(text) = fs::read_to_string(candidate) {
                    if !text.trim().is_empty() {
                        return Some(text);
                    }
                }
            }
            None
        });

        // Keep only the tail — installer logs can run long and the actual
        // error is almost always in the last handful of lines — and format
        // it so it reads naturally appended after the summary message.
        const MAX_OUTPUT_CHARS: usize = 4000;
        let tail: String = if let Some(ref log_text) = log_file_contents {
            let log_trimmed = log_text.trim();
            if log_trimmed.chars().count() > MAX_OUTPUT_CHARS {
                let skip = log_trimmed.chars().count() - MAX_OUTPUT_CHARS;
                format!("…{}", log_trimmed.chars().skip(skip).collect::<String>())
            } else {
                log_trimmed.to_string()
            }
        } else if trimmed.chars().count() > MAX_OUTPUT_CHARS {
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

        // Check for specific known installer failure patterns:
        // 1. zlib-ng hash mismatch on Linux (CachyOS, Arch, Fedora)
        // 2. Old Swing GUI-only installer without CLI support
        let has_processor_fail = trimmed.contains("Processor failed, invalid outputs")
            || log_file_contents.as_ref().map(|s| s.contains("Processor failed, invalid outputs")).unwrap_or(false);

        let hint = if has_processor_fail {
            "\nForge installer processor failed due to a checksum mismatch on generated library files (this happens on Linux distributions using zlib-ng instead of standard zlib, such as CachyOS or Arch).\n\
             To fix this, install standard zlib for your distribution:\n\
             \u{2022} Arch / CachyOS / Manjaro: sudo pacman -S zlib lib32-zlib (accept replacing zlib-ng)\n\
             \u{2022} Debian / Ubuntu / Linux Mint: sudo apt install zlib1g zlib1g:i386\n\
             \u{2022} Fedora / RHEL / CentOS: sudo dnf install zlib zlib.i686\n\
             \u{2022} openSUSE: sudo zypper install libz1 libz1-32bit\n\
             \u{2022} Alpine Linux: sudo apk add zlib\n\
             \u{2022} Void Linux: sudo xbps-install -S zlib\n\
             Then re-run the install."
        } else if trimmed.contains("UnrecognizedOptionException") && trimmed.contains("installClient") {
            "\nThis Forge build's installer is too old to support automatic/headless install \
             (it only offers a graphical installer). Automatic install currently isn't \
             supported for this specific version."
        } else {
            ""
        };

        Err(LauncherError::InstallerFailed {
            loader: invocation.loader,
            status: status.code(),
            output: format!("{hint}{formatted_output}"),
        })
    }
}
