use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use tauri::Emitter;
use crate::models::LogEntry;
use crate::state::AppState;

/// Everything under `<data_dir>/logs`:
/// - `latest.log` — plain-text log for the launcher run currently in
///   progress. Every `logger::log()` call (INFO/WARN/ERROR, and DEBUG when
///   enabled) is appended here as it happens, not just what's shown in the
///   in-app console — this is meant to be the one file to grab when
///   something needs to be diagnosed after the fact.
/// - `<date>-<n>.log.gz` — previous runs' `latest.log`, gzip-compressed and
///   archived the moment a new run starts, mirroring the scheme Minecraft's
///   own launcher and log4j config use for `.minecraft/logs`.
const LOGS_DIR: &str = "logs";
const LATEST_LOG: &str = "latest.log";

/// Called once at startup (from [`AppState::new`]) before any other log
/// line is written. Archives the previous run's `latest.log` (if any) into
/// a timestamped, gzip-compressed file in the same folder, then opens a
/// fresh `latest.log` for this run.
///
/// Never fatal: if anything here fails (permissions, disk full, etc.) this
/// returns `None` and the launcher keeps running with in-memory/UI logging
/// only — a missing log file should never stop the launcher from starting.
pub fn init_log_file(data_dir: &Path) -> Option<File> {
    let logs_dir = data_dir.join(LOGS_DIR);
    if fs::create_dir_all(&logs_dir).is_err() {
        return None;
    }

    let latest_path = logs_dir.join(LATEST_LOG);
    if latest_path.is_file() {
        archive_previous_log(&logs_dir, &latest_path);
    }

    OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&latest_path)
        .ok()
}

/// Compresses `latest_path` into `logs/<YYYY-MM-DD>-<n>.log.gz` (picking the
/// next free `n` for that date, so multiple runs in one day don't clobber
/// each other) and removes the now-archived plain file.
fn archive_previous_log(logs_dir: &Path, latest_path: &Path) {
    let Ok(mut contents) = fs::read(latest_path) else {
        return;
    };
    if contents.is_empty() {
        let _ = fs::remove_file(latest_path);
        return;
    }

    let date = chrono::Local::now().format("%Y-%m-%d").to_string();
    let mut n = 1u32;
    let archive_path = loop {
        let candidate = logs_dir.join(format!("{date}-{n}.log.gz"));
        if !candidate.exists() {
            break candidate;
        }
        n += 1;
    };

    use flate2::write::GzEncoder;
    use flate2::Compression;

    if let Ok(file) = File::create(&archive_path) {
        let mut encoder = GzEncoder::new(file, Compression::default());
        if encoder.write_all(&contents).and_then(|_| encoder.finish().map(|_| ())).is_ok() {
            let _ = fs::remove_file(latest_path);
        }
    }
    // Zero out our in-memory copy — nothing else needs it and this avoids
    // holding the whole previous log's bytes around longer than necessary.
    contents.clear();
}

/// Appends one already-formatted log line (plus a trailing newline) to
/// `latest.log`, if the file is open. Silently does nothing on write
/// failure — logging to disk is best-effort and must never be allowed to
/// interrupt the launcher's actual work.
fn append_to_file(state: &AppState, line: &str) {
    if let Ok(mut guard) = state.log_file.lock() {
        if let Some(file) = guard.as_mut() {
            let _ = writeln!(file, "{line}");
        }
    }
}

/// Log a message to the in-memory ring buffer, `logs/latest.log` on disk,
/// and the frontend console — all three see every entry (subject to the
/// DEBUG-mode gate in [`debug`]).
pub fn log(
    app: &tauri::AppHandle,
    state: &AppState,
    level: &str,
    source: &str,
    message: &str,
) {
    let redacted = redact_paths(state, message);
    let entry = LogEntry::new(level, source, &redacted);

    let file_line = format!(
        "{} [{:<5}] [{}]: {}",
        chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
        entry.level,
        entry.source,
        entry.message,
    );
    append_to_file(state, &file_line);

    state.push_log(entry.clone());
    let _ = app.emit("log-entry", &entry);
}

/// Path to `logs/latest.log` for a given data dir, without needing an
/// `AppState`/file handle — used by the `open logs folder` / `get log path`
/// frontend commands.
pub fn logs_dir(data_dir: &Path) -> PathBuf {
    data_dir.join(LOGS_DIR)
}

/// Reads the current `latest.log` contents (used by a "view latest log"
/// command, e.g. for the in-app log viewer or a "copy log to clipboard"
/// button). Returns an empty string if the file doesn't exist yet.
pub fn read_latest_log(data_dir: &Path) -> String {
    let path = logs_dir(data_dir).join(LATEST_LOG);
    let mut buf = String::new();
    if let Ok(mut f) = File::open(path) {
        let _ = f.read_to_string(&mut buf);
    }
    buf
}

/// Convenience helpers
pub fn info(app: &tauri::AppHandle, state: &AppState, source: &str, msg: &str) {
    log(app, state, "INFO", source, msg);
}

pub fn warn(app: &tauri::AppHandle, state: &AppState, source: &str, msg: &str) {
    log(app, state, "WARN", source, msg);
}

pub fn error(app: &tauri::AppHandle, state: &AppState, source: &str, msg: &str) {
    log(app, state, "ERROR", source, msg);
}

/// Verbose diagnostic logging — only actually recorded/emitted when the
/// user has "Debug Logging Mode" enabled in Settings → Privacy & Developer.
/// This is the real switch that setting controls: with it off, DEBUG
/// entries are skipped entirely (never stored, never sent to the
/// frontend), keeping the console/log file free of internal noise.
pub fn debug(app: &tauri::AppHandle, state: &AppState, source: &str, msg: &str) {
    let enabled = state
        .settings
        .lock()
        .map(|s| s.debug_mode)
        .unwrap_or(false);
    if !enabled {
        return;
    }
    log(app, state, "DEBUG", source, msg);
}

/// Same as [`log`], but also pushes the entry into `version_id`'s own
/// console history and fires `instance-log` for it — so a per-instance
/// console window (or the running-instances panel opened right after
/// pressing Play) sees this line immediately, instead of only picking up
/// output from the point the game process itself is spawned onward.
///
/// Meant for the handful of launch-time messages (Java setup, file
/// verification, xrandr checks, etc.) that happen before there's an
/// actual game process to capture stdout/stderr from.
pub fn log_for_instance(
    app: &tauri::AppHandle,
    state: &AppState,
    version_id: &str,
    level: &str,
    source: &str,
    message: &str,
) {
    log(app, state, level, source, message);

    let redacted = redact_paths(state, message);
    let entry = crate::models::LogEntry::new(level, source, &redacted);
    state.push_instance_log(version_id, entry.clone());
    let _ = app.emit(
        "instance-log",
        &crate::models::InstanceLogEvent { version_id: version_id.to_string(), entry },
    );
}

/// [`log_for_instance`] at INFO level.
pub fn info_for_instance(app: &tauri::AppHandle, state: &AppState, version_id: &str, source: &str, msg: &str) {
    log_for_instance(app, state, version_id, "INFO", source, msg);
}

/// [`log_for_instance`] at WARN level.
pub fn warn_for_instance(app: &tauri::AppHandle, state: &AppState, version_id: &str, source: &str, msg: &str) {
    log_for_instance(app, state, version_id, "WARN", source, msg);
}

/// [`log_for_instance`] at ERROR level.
pub fn error_for_instance(app: &tauri::AppHandle, state: &AppState, version_id: &str, source: &str, msg: &str) {
    log_for_instance(app, state, version_id, "ERROR", source, msg);
}

/// Redacts absolute filesystem paths (which usually embed the OS
/// username, e.g. `C:\Users\Alice\...` or `/home/alice/...`) from a log
/// line when "Redact Full File Paths in Logs" is enabled. Any occurrence
/// of the launcher's own data dir or game directory is collapsed down to
/// a stable placeholder so the rest of the path (which is still useful
/// for debugging) stays intact.
pub fn redact_paths(state: &AppState, text: &str) -> String {
    let enabled = state
        .settings
        .lock()
        .map(|s| s.redact_paths)
        .unwrap_or(true);
    if !enabled {
        return text.to_string();
    }

    let mut out = text.to_string();
    if let Ok(settings) = state.settings.lock() {
        let game_dir = settings.game_directory.clone();
        if !game_dir.trim().is_empty() {
            out = out.replace(&game_dir, "<game_dir>");
        }
    }
    if let Some(home) = dirs_home() {
        out = out.replace(&home, "<home>");
    }
    out
}

fn dirs_home() -> Option<String> {
    std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .ok()
}

/// Redacts sensitive auth/session tokens from a string before it's ever
/// written to a log line, when the user has "Redact Auth Tokens in Logs"
/// enabled (it's on by default). Handles the common shapes tokens show up
/// in launch/JVM argument lists: `--accessToken <value>`,
/// `--session <value>`, and bare `Bearer <value>` strings.
pub fn redact_sensitive(state: &AppState, text: &str) -> String {
    let redact_on = state
        .settings
        .lock()
        .map(|s| s.redact_tokens)
        .unwrap_or(true);
    if !redact_on {
        return text.to_string();
    }

    let sensitive_flags = ["--accesstoken", "--session", "--userproperties", "bearer"];
    let parts: Vec<&str> = text.split_whitespace().collect();
    let mut out: Vec<String> = Vec::with_capacity(parts.len());
    let mut redact_next = false;
    for part in parts {
        if redact_next {
            out.push("[REDACTED]".to_string());
            redact_next = false;
            continue;
        }
        if sensitive_flags.contains(&part.to_lowercase().as_str()) {
            out.push(part.to_string());
            redact_next = true;
            continue;
        }
        out.push(part.to_string());
    }
    out.join(" ")
}
