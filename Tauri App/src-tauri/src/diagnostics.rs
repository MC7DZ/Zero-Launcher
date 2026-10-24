//! Verbose diagnostics: everything here logs at DEBUG level, so it only shows
//! up when "Debug Logging Mode" is on (it is on by default now — see
//! `logger::is_debug_enabled`).
//!
//! - [`trace_invoke`]        — logs every IPC command the frontend calls
//! - [`trace_window_event`]  — window focus/resize/move/close/drag-drop/...
//! - [`trace_page_load`]     — webview page load started/finished
//! - [`trace_run_event`]     — app-level lifecycle events
//! - [`log_startup_environment`] — one big dump of environment + settings
//! - [`start_sampler`]       — periodic RAM / thread / queue snapshot
//! - [`frontend_log_batch`]  — sink for the JS side (`src/js/debug.js`)

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use serde_json::Value;
use tauri::{AppHandle, Manager, State};

use crate::logger;
use crate::state::AppState;

/// Commands that fire constantly and would drown everything else.
const QUIET_COMMANDS: &[&str] = &[
        "frontend_log_batch",
    "get_logs",
    "window_is_maximized",
    "play_click_sound",
    "report_activity",
];

/// Argument names whose values are never written to the log.
const SENSITIVE_KEYS: &[&str] = &[
    "token", "pass", "secret", "auth", "cookie", "session", "refresh", "code", "key", "password",
];

static EPOCH: OnceLock<Instant> = OnceLock::new();
static IPC_TOTAL: AtomicU64 = AtomicU64::new(0);
static LAST_MOVE_LOG: AtomicU64 = AtomicU64::new(0);
static LAST_RESIZE_LOG: AtomicU64 = AtomicU64::new(0);

fn now_ms() -> u64 {
    EPOCH.get_or_init(Instant::now).elapsed().as_millis() as u64
}

/// true at most once per `min_ms` for a given slot.
fn throttle(slot: &AtomicU64, min_ms: u64) -> bool {
    let now = now_ms();
    let last = slot.load(Ordering::Relaxed);
    if now.saturating_sub(last) >= min_ms {
        slot.store(now, Ordering::Relaxed);
        true
    } else {
        false
    }
}

fn truncate(s: &str, max: usize) -> String {
    let count = s.chars().count();
    if count <= max {
        s.to_string()
    } else {
        let head: String = s.chars().take(max).collect();
        format!("{head}… ({count} chars)")
    }
}

fn summarize_value(key: &str, v: &Value, depth: u8) -> String {
    let lk = key.to_ascii_lowercase();
    if SENSITIVE_KEYS.iter().any(|s| lk.contains(s)) {
        return "<redacted>".to_string();
    }
    match v {
        Value::Null => "null".to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        Value::String(s) => format!("{:?}", truncate(s, 80)),
        Value::Array(a) => format!("[{} items]", a.len()),
        Value::Object(m) => {
            if depth >= 1 {
                format!("{{{} keys}}", m.len())
            } else {
                let mut parts: Vec<String> = m
                    .iter()
                    .take(12)
                    .map(|(k, val)| format!("{k}={}", summarize_value(k, val, depth + 1)))
                    .collect();
                if m.len() > 12 {
                    parts.push(format!("…+{} more", m.len() - 12));
                }
                format!("{{{}}}", parts.join(", "))
            }
        }
    }
}

// ── IPC tracing ──────────────────────────────────────────────────────────────

/// Called from the wrapped `invoke_handler` in `lib.rs` for every command.
pub fn trace_invoke(invoke: &tauri::ipc::Invoke<tauri::Wry>) {
    let cmd = invoke.message.command();
    let n = IPC_TOTAL.fetch_add(1, Ordering::Relaxed) + 1;
    if QUIET_COMMANDS.contains(&cmd) {
        return;
    }
    let webview = invoke.message.webview_ref();
    let app = webview.app_handle();
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };

    let args = match invoke.message.payload() {
        tauri::ipc::InvokeBody::Json(Value::Object(m)) => {
            if m.is_empty() {
                String::new()
            } else {
                m.iter()
                    .map(|(k, v)| format!("{k}={}", summarize_value(k, v, 0)))
                    .collect::<Vec<_>>()
                    .join(", ")
            }
        }
        tauri::ipc::InvokeBody::Json(other) => summarize_value("", other, 0),
        tauri::ipc::InvokeBody::Raw(b) => format!("<{} raw bytes>", b.len()),
    };

    logger::debug(
        app,
        &state,
        "IPC",
        &format!("#{n} → {cmd}({args}) [window '{}']", webview.label()),
    );
}

// ── Window / webview / app events ───────────────────────────────────────────

pub fn trace_window_event(window: &tauri::Window, event: &tauri::WindowEvent) {
    // Moved/Resized fire dozens of times per second while dragging.
    match event {
        tauri::WindowEvent::Moved(_) if !throttle(&LAST_MOVE_LOG, 400) => return,
        tauri::WindowEvent::Resized(_) if !throttle(&LAST_RESIZE_LOG, 400) => return,
        _ => {}
    }
    let app = window.app_handle();
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    logger::debug(
        app,
        &state,
        "WINDOW",
        &format!("window '{}': {:?}", window.label(), event),
    );
}

pub fn trace_page_load(
    webview: &tauri::Webview<tauri::Wry>,
    payload: &tauri::webview::PageLoadPayload<'_>,
) {
    let app = webview.app_handle();
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    let phase = match payload.event() {
        tauri::webview::PageLoadEvent::Started => "STARTED",
        tauri::webview::PageLoadEvent::Finished => "FINISHED",
    };
    logger::debug(
        app,
        &state,
        "WEBVIEW",
        &format!(
            "page load {phase}: webview '{}' url={}",
            webview.label(),
            payload.url()
        ),
    );
}

pub fn trace_run_event(app: &AppHandle, event: &tauri::RunEvent) {
    // MainEventsCleared fires on every loop iteration — never log it.
    if matches!(event, tauri::RunEvent::MainEventsCleared) {
        return;
    }
    // Window events are already covered by `trace_window_event`.
    if matches!(event, tauri::RunEvent::WindowEvent { .. }) {
        return;
    }
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    logger::debug(app, &state, "APP", &format!("run event: {:?}", event));
}

// ── Startup dump ────────────────────────────────────────────────────────────

pub fn log_startup_environment(app: &AppHandle) {
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    let d = |msg: String| logger::debug(app, &state, "STARTUP", &msg);

    d(format!(
        "ZeroLauncher v{} starting — os={} arch={} family={} pid={}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH,
        std::env::consts::FAMILY,
        std::process::id()
    ));
    d(format!(
        "exe={:?} cwd={:?} args={:?}",
        std::env::current_exe().ok(),
        std::env::current_dir().ok(),
        std::env::args().skip(1).collect::<Vec<_>>()
    ));
    d(format!("data_dir={:?}", state.data_dir));
    d(format!(
        "debug build={} cpu_threads={}",
        cfg!(debug_assertions),
        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0)
    ));

    for key in [
        "WAYLAND_DISPLAY",
        "DISPLAY",
        "XDG_SESSION_TYPE",
        "XDG_CURRENT_DESKTOP",
        "GDK_BACKEND",
        "APPIMAGE",
        "LANG",
        "WEBKIT_DISABLE_COMPOSITING_MODE",
        "WEBKIT_DISABLE_DMABUF_RENDERER",
        "WEBKIT_DMABUF_RENDERER_FORCE_SHM",
        "WEBKIT_FORCE_COMPOSITING_MODE",
        "WEBKIT_USE_SINGLE_WEB_PROCESS",
        "WEBKIT_MEMORY_PRESSURE_RELIEF",
        "MALLOC_ARENA_MAX",
        "MALLOC_TRIM_THRESHOLD_",
        "MALLOC_MMAP_THRESHOLD_",
        "ZL_DEBUG",
    ] {
        d(format!(
            "env {key}={}",
            std::env::var(key).unwrap_or_else(|_| "<unset>".to_string())
        ));
    }

    // Build the text while holding the lock, then RELEASE it before logging:
    // the logger itself locks settings, so logging with the guard held
    // would deadlock.
    let settings_line = state.settings.try_lock().ok().map(|s| {
        format!(
            "settings: debug_mode={} redact_paths={} redact_tokens={} game_directory={:?} network_mode={:?} enable_system_tray={} on_launcher_close={:?} always_hide_to_tray={} clear_session_on_exit={}",
            s.debug_mode, s.redact_paths, s.redact_tokens, s.game_directory, s.network_mode,
            s.enable_system_tray, s.on_launcher_close, s.always_hide_to_tray, s.clear_session_on_exit,
        )
    });
    if let Some(line) = settings_line {
        d(line);
    }

    d(format!(
        "loaded state: accounts={} instances={} hidden_instances={} running_instances={}",
        state.accounts.try_lock().map(|g| g.len()).unwrap_or(0),
        state.instances.try_lock().map(|g| g.len()).unwrap_or(0),
        state.hidden_instances.try_lock().map(|g| g.len()).unwrap_or(0),
        state.running_instances.try_lock().map(|g| g.len()).unwrap_or(0),
    ));
}

// ── Periodic sampler ────────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
fn process_memory_summary() -> String {
    let Ok(text) = std::fs::read_to_string("/proc/self/status") else {
        return "n/a".to_string();
    };
    let mut parts = Vec::new();
    for line in text.lines() {
        for key in ["VmRSS:", "VmSize:", "VmSwap:", "Threads:"] {
            if let Some(rest) = line.strip_prefix(key) {
                parts.push(format!("{}{}", key.trim_end_matches(':'), format!("={}", rest.trim())));
            }
        }
    }
    parts.join(" ")
}

#[cfg(not(target_os = "linux"))]
fn process_memory_summary() -> String {
    "n/a on this OS".to_string()
}

pub fn start_sampler(app: AppHandle) {
    let spawned = std::thread::Builder::new()
        .name("diag-sampler".into())
        .spawn(move || {
            let started = Instant::now();
            loop {
                std::thread::sleep(Duration::from_secs(15));
                let Some(state) = app.try_state::<AppState>() else {
                    continue;
                };
                let log_len = state.logs.try_lock().map(|g| g.len()).unwrap_or(0);
                let running = state
                    .running_instances
                    .try_lock()
                    .map(|g| g.values().filter(|i| i.running).count())
                    .unwrap_or(0);
                let downloads = state.active_downloads.load(Ordering::SeqCst);
                let visible = app
                    .get_webview_window("main")
                    .and_then(|w| w.is_visible().ok())
                    .map(|v| v.to_string())
                    .unwrap_or_else(|| "?".to_string());
                logger::debug(
                    &app,
                    &state,
                    "SAMPLER",
                    &format!(
                        "uptime={}s mem[{}] log_buffer={} running_games={} active_downloads={} ipc_calls_total={} main_window_visible={}",
                        started.elapsed().as_secs(),
                        process_memory_summary(),
                        log_len,
                        running,
                        downloads,
                        IPC_TOTAL.load(Ordering::Relaxed),
                        visible
                    ),
                );
            }
        });
    if let Err(e) = spawned {
        eprintln!("[SAMPLER] failed to start sampler thread: {e}");
    }
}

// ── Frontend log sink ───────────────────────────────────────────────────────

#[derive(serde::Deserialize)]
pub struct FrontendLogLine {
    pub level: String,
    pub message: String,
}

/// Receives batches of log lines from `src/js/debug.js` (console output,
/// errors, perf warnings, user-action breadcrumbs, IPC timings, ...).
///
/// Returns whether DEBUG lines are currently being recorded, so the
/// frontend can stop shipping them when debug mode is off.
#[tauri::command]
pub async fn frontend_log_batch(
    app: AppHandle,
    state: State<'_, AppState>,
    lines: Vec<FrontendLogLine>,
) -> Result<bool, String> {
    for line in lines {
        let msg = truncate(&line.message, 4000);
        match line.level.to_ascii_uppercase().as_str() {
            "ERROR" => logger::error(&app, &state, "UI", &msg),
            "WARN" | "WARNING" => logger::warn(&app, &state, "UI", &msg),
            "INFO" => logger::info(&app, &state, "UI", &msg),
            _ => logger::debug(&app, &state, "UI", &msg),
        }
    }
    Ok(logger::is_debug_enabled(&state))
}
