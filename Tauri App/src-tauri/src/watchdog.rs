//! UI freeze watchdog.
//!
//! The frontend (`src/js/debug.js`) sends a tiny `watchdog_heartbeat` call
//! roughly once a second from a plain `setInterval`. If the webview's main
//! thread is blocked (infinite loop, huge synchronous render, a deadlocked
//! WebKit process, ...) those timers stop firing and the heartbeats stop
//! arriving here.
//!
//! A dedicated OS thread (`ui-watchdog`) polls the time since the last
//! heartbeat. When it exceeds [`FREEZE_THRESHOLD_MS`] (5 seconds) while the
//! window is actually visible, the watchdog:
//!
//!   1. logs a detailed ERROR (what the UI was last doing, pending IPC
//!      calls, how many reloads already happened, ...),
//!   2. shows a native desktop notification,
//!   3. reloads the main webview (`WebviewWindow::reload`, which is a native
//!      call and therefore works even when the page's JS is frozen).
//!
//! Safety nets so it never makes things worse:
//!   - hidden / minimized windows are never judged (WebKit throttles or
//!     pauses timers there) and get a fresh grace period when shown again;
//!   - if the watchdog thread itself woke up very late, the machine was
//!     suspended, so the heartbeat clock is reset instead of reloading;
//!   - after a reload the page gets [`STARTUP_GRACE_MS`] to send its first
//!     heartbeat;
//!   - if several reloads in a row don't bring the UI back, the launcher
//!     relaunches itself (once per process). Game processes are spawned
//!     detached, so a running Minecraft survives that.
//!
//! Disable with Settings → Developer → "Auto-reload when the UI freezes",
//! or with the environment variable `ZL_DISABLE_WATCHDOG=1` (handy when you
//! want to pause in the devtools debugger without being reloaded).

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager};

use crate::logger;
use crate::state::AppState;

const SRC: &str = "WATCHDOG";

/// No heartbeat for this long (while visible) == frozen.
pub const FREEZE_THRESHOLD_MS: u64 = 5_000;
/// After startup / a reload the page gets this long to send its first beat.
const STARTUP_GRACE_MS: u64 = 20_000;
/// How often the watchdog thread wakes up.
const POLL_INTERVAL_MS: u64 = 500;
/// Only start doing (slightly more expensive) window checks after this long.
const SUSPECT_AFTER_MS: u64 = 2_000;
/// If the watchdog's own tick took longer than this, the system slept.
const SUSPEND_GAP_MS: u64 = 4_000;
/// A heartbeat arriving later than this is logged as a "UI stall".
const STALL_LOG_MS: u64 = 1_500;
/// Reloads in a row that failed to bring the UI back before we relaunch.
const MAX_FAILED_RELOADS: u32 = 3;
/// UI has to stay healthy this long for the failed-reload counter to reset.
const HEALTHY_RESET_MS: u64 = 30_000;

static EPOCH: OnceLock<Instant> = OnceLock::new();
static LAST_BEAT_MS: AtomicU64 = AtomicU64::new(0);
static LOAD_MS: AtomicU64 = AtomicU64::new(0);
static HAS_BEAT: AtomicBool = AtomicBool::new(false);
static BEAT_COUNT: AtomicU64 = AtomicU64::new(0);
static FAILED_RELOADS: AtomicU32 = AtomicU32::new(0);
static TOTAL_RELOADS: AtomicU32 = AtomicU32::new(0);
static AWAITING_RECOVERY: AtomicBool = AtomicBool::new(false);
static RELAUNCHED_ONCE: AtomicBool = AtomicBool::new(false);
/// Set once the localhost reachability probe ran for the current page load
/// (reset on every reload) so we probe/report once, not every poll.
static LOCALHOST_PROBED: AtomicBool = AtomicBool::new(false);
/// How long a never-loaded http(s) page waits before we probe its server.
const LOCALHOST_PROBE_AFTER_MS: u64 = 5_000;
static LAST_CONTEXT: Mutex<String> = Mutex::new(String::new());

fn now_ms() -> u64 {
    EPOCH.get_or_init(Instant::now).elapsed().as_millis() as u64
}

fn last_context() -> String {
    LAST_CONTEXT
        .lock()
        .map(|c| c.clone())
        .unwrap_or_default()
}

// ── logging helpers (never panic, work before/without AppState) ──────────────

fn wlog(app: &AppHandle, level: &str, msg: &str) {
    match app.try_state::<AppState>() {
        Some(state) => match level {
            "DEBUG" => logger::debug(app, &state, SRC, msg),
            "WARN" => logger::warn(app, &state, SRC, msg),
            "ERROR" => logger::error(app, &state, SRC, msg),
            _ => logger::info(app, &state, SRC, msg),
        },
        None => eprintln!("[{level}] [{SRC}] {msg}"),
    }
}

fn watchdog_enabled(app: &AppHandle) -> bool {
    if std::env::var_os("ZL_DISABLE_WATCHDOG").is_some() {
        return false;
    }
    match app.try_state::<AppState>() {
        // try_lock: if some other thread is holding the settings mutex
        // (possibly the very reason the app is stuck) we must not block too.
        Some(state) => state
            .settings
            .try_lock()
            .map(|s| s.ui_watchdog_enabled)
            .unwrap_or(true),
        None => true,
    }
}

/// If the main window points at an http(s) server (the Vite dev server in
/// `tauri dev`), checks whether anything is listening there.
/// Returns `Some((url, error))` when the server is NOT reachable.
fn probe_frontend_server(window: &tauri::WebviewWindow) -> Option<(String, String)> {
    use std::net::{TcpStream, ToSocketAddrs};
    let url = window.url().ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    let host = url.host_str()?.to_string();
    let port = url.port_or_known_default()?;
    let addrs = match (host.as_str(), port).to_socket_addrs() {
        Ok(a) => a.collect::<Vec<_>>(),
        Err(e) => return Some((url.to_string(), format!("could not resolve {host}: {e}"))),
    };
    let mut last_err = String::from("no addresses resolved");
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, Duration::from_secs(2)) {
            Ok(_) => return None,
            Err(e) => last_err = format!("{addr}: {e}"),
        }
    }
    Some((url.to_string(), last_err))
}

fn notify(app: &AppHandle, title: &str, body: &str) {
    use tauri_plugin_notification::NotificationExt;
    if let Err(e) = app.notification().builder().title(title).body(body).show() {
        wlog(app, "WARN", &format!("Could not show desktop notification: {e}"));
    }
}

// ── heartbeat ────────────────────────────────────────────────────────────────

/// Called by the frontend about once a second.
///
/// `async` on purpose: async commands run on the tokio runtime instead of
/// the main thread, so this measures "is the *page's JS* alive" and is not
/// itself delayed by other work on the main thread.
#[tauri::command]
pub async fn watchdog_heartbeat(
    app: AppHandle,
    seq: u64,
    context: Option<String>,
) -> Result<(), String> {
    record_beat(&app, seq, context);
    Ok(())
}

fn record_beat(app: &AppHandle, seq: u64, context: Option<String>) {
    let now = now_ms();
    let prev = LAST_BEAT_MS.swap(now, Ordering::SeqCst);
    let had_beat = HAS_BEAT.swap(true, Ordering::SeqCst);
    let n = BEAT_COUNT.fetch_add(1, Ordering::SeqCst) + 1;

    if let Some(ctx) = context.as_ref() {
        if let Ok(mut guard) = LAST_CONTEXT.lock() {
            *guard = ctx.clone();
        }
    }
    let ctx = context.unwrap_or_default();

    if !had_beat {
        let since_load = now.saturating_sub(LOAD_MS.load(Ordering::SeqCst));
        if AWAITING_RECOVERY.swap(false, Ordering::SeqCst) {
            wlog(
                app,
                "INFO",
                &format!(
                    "UI recovered: first heartbeat {since_load} ms after reload (seq={seq}, total reloads this session: {})",
                    TOTAL_RELOADS.load(Ordering::SeqCst)
                ),
            );
        } else {
            wlog(
                app,
                "DEBUG",
                &format!("First heartbeat from UI {since_load} ms after startup (seq={seq})"),
            );
        }
        return;
    }

    let gap = now.saturating_sub(prev);
    if gap > STALL_LOG_MS {
        wlog(
            app,
            "WARN",
            &format!("UI stall: heartbeat arrived {gap} ms late (seq={seq}) [{ctx}]"),
        );
    } else if n % 60 == 0 {
        wlog(app, "DEBUG", &format!("heartbeat #{n} ok (gap {gap} ms) [{ctx}]"));
    }
}

// ── watchdog thread ──────────────────────────────────────────────────────────

pub fn start(app: AppHandle) {
    // Start the clocks now.
    let now = now_ms();
    LOAD_MS.store(now, Ordering::SeqCst);
    LAST_BEAT_MS.store(now, Ordering::SeqCst);

    let spawn = std::thread::Builder::new()
        .name("ui-watchdog".into())
        .spawn(move || run(app));
    if let Err(e) = spawn {
        eprintln!("[WATCHDOG] failed to start watchdog thread: {e}");
    }
}

fn run(app: AppHandle) {
    wlog(
        &app,
        "INFO",
        &format!(
            "UI watchdog started (freeze threshold {} ms, startup grace {} ms, poll {} ms)",
            FREEZE_THRESHOLD_MS, STARTUP_GRACE_MS, POLL_INTERVAL_MS
        ),
    );

    let mut last_tick = Instant::now();
    let mut was_hidden = false;

    loop {
        std::thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));

        let tick_now = Instant::now();
        let tick_gap = tick_now.duration_since(last_tick).as_millis() as u64;
        last_tick = tick_now;

        let now = now_ms();

        // System was suspended / this thread was starved: the page's timers
        // were frozen along with everything else. Not a UI freeze.
        if tick_gap > SUSPEND_GAP_MS {
            wlog(
                &app,
                "WARN",
                &format!("Watchdog tick took {tick_gap} ms (system suspend or heavy starvation) — resetting heartbeat clock instead of judging the UI"),
            );
            LAST_BEAT_MS.store(now, Ordering::SeqCst);
            if !HAS_BEAT.load(Ordering::SeqCst) {
                LOAD_MS.store(now, Ordering::SeqCst);
            }
            continue;
        }

        if !watchdog_enabled(&app) {
            LAST_BEAT_MS.store(now, Ordering::SeqCst);
            continue;
        }

        let has_beat = HAS_BEAT.load(Ordering::SeqCst);
        let last_beat = LAST_BEAT_MS.load(Ordering::SeqCst);
        let load_ms = LOAD_MS.load(Ordering::SeqCst);
        let beat_age = now.saturating_sub(last_beat);

        // Healthy for long enough → forgive earlier failed reloads.
        if has_beat
            && beat_age < SUSPECT_AFTER_MS
            && now.saturating_sub(load_ms) > HEALTHY_RESET_MS
            && FAILED_RELOADS.load(Ordering::SeqCst) != 0
        {
            wlog(&app, "DEBUG", "UI healthy for 30s — failed-reload counter reset");
            FAILED_RELOADS.store(0, Ordering::SeqCst);
        }

        // How long have we gone without hearing from the page?
        let (silent_for, limit) = if has_beat {
            (beat_age, FREEZE_THRESHOLD_MS)
        } else {
            (now.saturating_sub(load_ms), STARTUP_GRACE_MS)
        };

        if silent_for < SUSPECT_AFTER_MS {
            continue;
        }

        // Page never started and it's served over http(s) (dev server):
        // find out whether the server is simply not there. Independent of
        // window visibility — a dead server is a dead server.
        if !has_beat
            && silent_for >= LOCALHOST_PROBE_AFTER_MS
            && !LOCALHOST_PROBED.swap(true, Ordering::SeqCst)
        {
            if let Some(window) = app.get_webview_window("main") {
                match probe_frontend_server(&window) {
                    Some((url, err)) => {
                        wlog(
                            &app,
                            "ERROR",
                            &format!("FRONTEND SERVER UNREACHABLE: the page at {url} never started and the server did not answer ({err}). Is `npm run dev` running?"),
                        );
                        notify(
                            &app,
                            "ZeroLauncher can't load its UI",
                            &format!("Nothing is answering at {url}. Start the dev server (npm run dev) and relaunch. A report was saved."),
                        );
                        crate::crashreport::report_frontend_failure(
                            &app,
                            &format!(
                                "message : Frontend failed to load — server not reachable\nurl : {url}\nprobe error : {err}\nsilent for : {silent_for} ms\nbeats received : {}\nvisible : {:?}  focused : {:?}\nhint : `npm run dev` (Vite, port 1420) is probably not running or crashed",
                                BEAT_COUNT.load(Ordering::SeqCst),
                                window.is_visible().ok(),
                                window.is_focused().ok(),
                            ),
                        );
                    }
                    None => wlog(
                        &app,
                        "DEBUG",
                        "Frontend server probe: reachable (or not http) — page is just slow or its JS is not running",
                    ),
                }
            }
        }

        // Something looks off — check whether the window is even supposed to
        // be running timers. (Window getters talk to the main thread; if it
        // is blocked they fail/time out, which we treat as "visible".)
        let Some(window) = app.get_webview_window("main") else {
            continue;
        };
        let visible = window.is_visible().unwrap_or(true);
        let minimized = window.is_minimized().unwrap_or(false);
        if !visible || minimized {
            if !was_hidden {
                wlog(
                    &app,
                    "DEBUG",
                    &format!("Main window hidden/minimized (visible={visible}, minimized={minimized}) — watchdog paused"),
                );
            }
            was_hidden = true;
            LAST_BEAT_MS.store(now, Ordering::SeqCst);
            if !has_beat {
                LOAD_MS.store(now, Ordering::SeqCst);
            }
            continue;
        }
        if was_hidden {
            was_hidden = false;
            wlog(&app, "DEBUG", "Main window visible again — giving the UI a fresh grace period");
            LAST_BEAT_MS.store(now, Ordering::SeqCst);
            if !has_beat {
                LOAD_MS.store(now, Ordering::SeqCst);
            }
            continue;
        }

        if silent_for >= limit {
            handle_freeze(&app, &window, silent_for, !has_beat);
        } else if silent_for >= SUSPECT_AFTER_MS && silent_for % 1000 < POLL_INTERVAL_MS {
            // Roughly once a second while we are waiting.
            wlog(
                &app,
                "WARN",
                &format!(
                    "No UI heartbeat for {silent_for} ms (will reload at {limit} ms) [{}]",
                    last_context()
                ),
            );
        }
    }
}

fn handle_freeze(
    app: &AppHandle,
    window: &tauri::WebviewWindow,
    silent_for_ms: u64,
    never_loaded: bool,
) {
    let failed = FAILED_RELOADS.fetch_add(1, Ordering::SeqCst) + 1;
    let total = TOTAL_RELOADS.fetch_add(1, Ordering::SeqCst) + 1;
    let beats = BEAT_COUNT.load(Ordering::SeqCst);
    let ctx = last_context();

    wlog(
        app,
        "ERROR",
        &format!(
            "UI FREEZE detected: {} for {silent_for_ms} ms. beats_received={beats}, failed_reloads_in_a_row={failed}, total_reloads={total}, last_ui_context=[{ctx}], window_url={:?}, focused={:?}, maximized={:?}",
            if never_loaded { "page never sent a heartbeat after (re)load" } else { "no heartbeat" },
            window.url().map(|u| u.to_string()).ok(),
            window.is_focused().ok(),
            window.is_maximized().ok(),
        ),
    );

    // Ship a report (environment + logs) to the crash webhook, if configured.
    crate::crashreport::report_freeze(
        app,
        &format!(
            "message : UI froze — {} for {silent_for_ms} ms\nlast ui context : {ctx}\nbeats received : {beats}\nfailed reloads in a row : {failed}\ntotal reloads this session : {total}\nwindow url : {:?}\nfocused : {:?}  maximized : {:?}",
            if never_loaded { "page never sent a heartbeat after (re)load" } else { "no heartbeat" },
            window.url().map(|u| u.to_string()).ok(),
            window.is_focused().ok(),
            window.is_maximized().ok(),
        ),
    );

    // Too many failed reloads in a row → last resort: relaunch the process.
    if failed > MAX_FAILED_RELOADS {
        hard_relaunch(app, failed);
        // If we get here, relaunch was refused (already done once) — back off.
        let now = now_ms();
        LAST_BEAT_MS.store(now, Ordering::SeqCst);
        LOAD_MS.store(now, Ordering::SeqCst);
        HAS_BEAT.store(false, Ordering::SeqCst);
        FAILED_RELOADS.store(0, Ordering::SeqCst);
        return;
    }

    let secs = silent_for_ms as f64 / 1000.0;
    notify(
        app,
        "ZeroLauncher stopped responding",
        &format!("The launcher UI was frozen for {secs:.0} seconds. Reloading it now — running games are not affected."),
    );

    wlog(app, "INFO", &format!("Reloading main webview (attempt {failed}/{MAX_FAILED_RELOADS})…"));
    match window.reload() {
        Ok(()) => wlog(app, "DEBUG", "webview.reload() dispatched"),
        Err(e) => {
            wlog(app, "ERROR", &format!("webview.reload() failed: {e} — trying navigate to current URL"));
            if let Ok(url) = window.url() {
                if let Err(e2) = window.navigate(url) {
                    wlog(app, "ERROR", &format!("webview.navigate() failed too: {e2}"));
                }
            }
        }
    }

    // Start the grace period for the fresh page.
    LOCALHOST_PROBED.store(false, Ordering::SeqCst);
    let now = now_ms();
    LOAD_MS.store(now, Ordering::SeqCst);
    LAST_BEAT_MS.store(now, Ordering::SeqCst);
    HAS_BEAT.store(false, Ordering::SeqCst);
    AWAITING_RECOVERY.store(true, Ordering::SeqCst);
}

fn hard_relaunch(app: &AppHandle, failed: u32) {
    if RELAUNCHED_ONCE.swap(true, Ordering::SeqCst) || std::env::var_os("ZL_WATCHDOG_RELAUNCHED").is_some() {
        wlog(
            app,
            "ERROR",
            "UI still frozen after reloads and the launcher already relaunched once — giving up so we don't loop. Please restart manually and check logs/latest.log.",
        );
        notify(
            app,
            "ZeroLauncher keeps freezing",
            "Reloading did not help. Please restart the launcher and send logs/latest.log if it happens again.",
        );
        return;
    }

    wlog(
        app,
        "ERROR",
        &format!("{failed} reloads in a row did not bring the UI back — relaunching the launcher process"),
    );
    notify(
        app,
        "ZeroLauncher is restarting",
        "The UI would not recover after reloading, so the launcher is restarting. Running games stay open.",
    );
    // Give the log file / notification a moment to flush.
    std::thread::sleep(Duration::from_millis(400));

    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            wlog(app, "ERROR", &format!("Cannot relaunch: current_exe() failed: {e}"));
            RELAUNCHED_ONCE.store(false, Ordering::SeqCst);
            return;
        }
    };

    let args: Vec<std::ffi::OsString> = std::env::args_os().skip(1).collect();

    #[cfg(unix)]
    let spawn_result = {
        // Small delay so this process has released the single-instance
        // socket before the new one starts.
        let mut cmd = std::process::Command::new("sh");
        cmd.arg("-c")
            .arg("sleep 1; exec \"$0\" \"$@\"")
            .arg(&exe)
            .args(&args)
            .env("ZL_WATCHDOG_RELAUNCHED", "1");
        cmd.spawn()
    };
    #[cfg(not(unix))]
    let spawn_result = std::process::Command::new(&exe)
        .args(&args)
        .env("ZL_WATCHDOG_RELAUNCHED", "1")
        .spawn();

    match spawn_result {
        Ok(_) => {
            wlog(app, "INFO", "Replacement launcher process spawned — exiting this one");
            std::process::exit(0);
        }
        Err(e) => {
            wlog(app, "ERROR", &format!("Relaunch failed: {e}"));
            RELAUNCHED_ONCE.store(false, Ordering::SeqCst);
        }
    }
}
