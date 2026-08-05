use tauri::Emitter;
use crate::models::LogEntry;
use crate::state::AppState;

/// Log a message to both the in-memory ring buffer and emit it to the frontend.
pub fn log(
    app: &tauri::AppHandle,
    state: &AppState,
    level: &str,
    source: &str,
    message: &str,
) {
    let redacted = redact_paths(state, message);
    let entry = LogEntry::new(level, source, &redacted);
    state.push_log(entry.clone());
    let _ = app.emit("log-entry", &entry);
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
