//! Discord webhook crash reporting.
//!
//! `crashlog.rs` already makes sure every way the launcher can die leaves a
//! report file in `<data_dir>/logs` (`panic-*`, `fatal-signal-*`,
//! `unclean-exit-*`), and `watchdog.rs` detects UI freezes. This module gets
//! those reports off the user's machine and into a Discord channel:
//!
//! * **Crashes** (panic / native signal / OOM-kill / power loss) can't be
//!   uploaded by the dying process — sockets and allocators aren't safe in a
//!   signal handler and `panic = "abort"` kills the process right after the
//!   hook. So the report file is written as before and uploaded by the *next*
//!   launcher start ([`start`] → [`send_pending`]). A `<report>.sent` marker
//!   file stops it from being uploaded twice.
//! * **UI freezes** happen in a live process, so [`report_freeze`] uploads
//!   immediately (rate limited).
//!
//! Each upload is one Discord message: an embed with the headline facts
//! (version, OS, CPU, RAM, GPU, WebView, session type, packaging, uptime...)
//! plus attachments: `report.txt` (the crash report with backtrace and
//! breadcrumbs), `environment.txt` (full hardware / OS / env-var / launcher
//! state dump), `settings.txt` (non-sensitive launcher settings) and the log
//! of the run that crashed, plus `crash-report-<id>.zip` bundling all of those
//! with a `report-summary.txt` holding the same information as the embed.
//!
//! Privacy: everything is run through [`Scrubber`] before it leaves the
//! machine — home directory and username, account names/UUIDs, access
//! tokens, JWTs, e-mail addresses and public IPs are replaced — regardless
//! of the user's own log-redaction settings. Nothing is sent unless a valid
//! Discord webhook URL is configured and "Send crash reports" is on.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::time::{Duration, SystemTime};

use serde_json::{json, Value};
use tauri::{AppHandle, Manager};

use crate::crashlog;
use crate::logger;
use crate::network;
use crate::state::AppState;

const SRC: &str = "CrashReport";

/// Webhook compiled in at build time (`ZL_CRASH_WEBHOOK=... cargo build`).
/// The Settings field overrides it. Never commit a real URL to the repo.

/// The webhook used when the Settings field is blank: the build-time
/// `ZL_CRASH_WEBHOOK` if one was given, else none (reporting stays off).
fn default_webhook() -> &'static str {
    crate::config::crash_webhook().unwrap_or("")
}

/// Cap per attached file (Discord allows ~10 MB per message for webhooks).
const MAX_LOG_BYTES: usize = 4 * 1024 * 1024;
const MAX_REPORT_BYTES: usize = 1024 * 1024;
/// Report files older than this are marked as sent without being uploaded.
const MAX_REPORT_AGE: Duration = Duration::from_secs(14 * 24 * 3600);
/// Max pending reports uploaded per launcher start.
const MAX_PENDING_PER_START: usize = 5;
/// Freeze reports: at most this many per session, at least this far apart.
const MAX_FREEZES_PER_SESSION: u32 = 3;
const MIN_FREEZE_GAP_SECS: u64 = 300;
const MAX_FRONTEND_FAILS_PER_SESSION: u32 = 2;

static FREEZES_SENT: AtomicU32 = AtomicU32::new(0);
static FRONTEND_FAILS_SENT: AtomicU32 = AtomicU32::new(0);
static LAST_FREEZE_UNIX: AtomicU64 = AtomicU64::new(0);

/// `unclean-exit-*` reports are deliberately NOT uploaded: they are also
/// written when the user force-quits, shuts down or logs out while the
/// launcher runs, so they are mostly noise. They still land in `logs/`.
const REPORT_PREFIXES: &[&str] = &["panic-", "fatal-signal-", "freeze-"];

// ───────────────────────────── configuration ─────────────────────────────

/// Accepts only real Discord webhook URLs so a tampered settings file can't
/// turn the reporter into "POST the user's logs to any server".
pub fn is_valid_webhook(url: &str) -> bool {
    let Ok(u) = url::Url::parse(url.trim()) else {
        return false;
    };
    if u.scheme() != "https" {
        return false;
    }
    let host_ok = matches!(
        u.host_str(),
        Some("discord.com") | Some("discordapp.com") | Some("ptb.discord.com") | Some("canary.discord.com")
    );
    let segs: Vec<&str> = u
        .path_segments()
        .map(|s| s.filter(|p| !p.is_empty()).collect())
        .unwrap_or_default();
    // /api/webhooks/<id>/<token>  or  /api/v10/webhooks/<id>/<token>
    let shape_ok = match segs.as_slice() {
        ["api", "webhooks", id, tok] | ["api", _, "webhooks", id, tok] => {
            id.chars().all(|c| c.is_ascii_digit()) && !tok.is_empty()
        }
        _ => false,
    };
    host_ok && shape_ok
}

fn configured_url(app: &AppHandle) -> Option<String> {
    let state = app.try_state::<AppState>()?;
    let s = state.settings.lock().unwrap_or_else(|e| e.into_inner());
    if !s.crash_report_enabled {
        return None;
    }
    let url = if s.crash_webhook_url.trim().is_empty() {
        default_webhook().trim().to_string()
    } else {
        s.crash_webhook_url.trim().to_string()
    };
    if is_valid_webhook(&url) {
        Some(url)
    } else {
        None
    }
}

fn clog(app: &AppHandle, level: &str, msg: &str) {
    match app.try_state::<AppState>() {
        Some(state) => match level {
            "WARN" => logger::warn(app, &state, SRC, msg),
            "ERROR" => logger::error(app, &state, SRC, msg),
            _ => logger::info(app, &state, SRC, msg),
        },
        None => eprintln!("[{level}] [{SRC}] {msg}"),
    }
}

// ───────────────────────────── scrubbing ─────────────────────────────

/// Removes personal data and credentials from text that leaves the machine.
pub struct Scrubber {
    /// (needle, replacement), applied in order, longest needles first.
    literals: Vec<(String, &'static str)>,
}

const SECRET_KEYS: &[&str] = &[
    "accesstoken", "access_token", "refreshtoken", "refresh_token", "id_token", "idtoken",
    "token", "password", "passwd", "secret", "cookie", "authorization", "clientsecret",
    "client_secret", "apikey", "api_key", "xuid", "userproperties",
];
const SECRET_FLAGS: &[&str] = &["--accesstoken", "--session", "--userproperties", "--uuid", "--xuid", "bearer"];

impl Scrubber {
    pub fn new(app: &AppHandle) -> Self {
        let mut lit: Vec<(String, &'static str)> = Vec::new();
        let mut push = |s: String, r: &'static str| {
            if s.trim().chars().count() >= 3 {
                lit.push((s, r));
            }
        };
        for var in ["USERPROFILE", "HOME"] {
            if let Ok(h) = std::env::var(var) {
                push(h.clone(), "<home>");
                push(h.replace('\\', "/"), "<home>");
            }
        }
        if let Some(h) = dirs::home_dir() {
            push(h.to_string_lossy().to_string(), "<home>");
        }
        for var in ["USER", "USERNAME", "LOGNAME"] {
            if let Ok(u) = std::env::var(var) {
                if u.chars().count() >= 4 {
                    push(u, "<user>");
                }
            }
        }
        if let Some(state) = app.try_state::<AppState>() {
            if let Ok(accts) = state.accounts.lock() {
                for a in accts.iter() {
                    push(a.username.clone(), "<player>");
                    if let Some(u) = &a.mc_uuid {
                        push(u.clone(), "<uuid>");
                        push(u.replace('-', ""), "<uuid>");
                    }
                }
            }
        }
        // Longest first so "/home/alice" wins over "alice".
        lit.sort_by(|a, b| b.0.len().cmp(&a.0.len()));
        lit.dedup_by(|a, b| a.0 == b.0);
        Self { literals: lit }
    }

    pub fn scrub(&self, text: &str) -> String {
        let mut out = text.to_string();
        for (needle, rep) in &self.literals {
            if out.contains(needle.as_str()) {
                out = out.replace(needle.as_str(), rep);
            }
        }
        let mut res = String::with_capacity(out.len());
        for line in out.split_inclusive('\n') {
            let (body, nl) = match line.strip_suffix('\n') {
                Some(b) => (b, "\n"),
                None => (line, ""),
            };
            res.push_str(&scrub_line(body));
            res.push_str(nl);
        }
        res
    }
}

fn scrub_line(line: &str) -> String {
    let line = redact_key_values(line);
    let mut out: Vec<String> = Vec::new();
    let mut redact_next = false;
    for word in line.split(' ') {
        if redact_next && !word.is_empty() {
            out.push("[REDACTED]".to_string());
            redact_next = false;
            continue;
        }
        let core = word.trim_matches(|c: char| matches!(c, '"' | '\'' | ',' | ';' | '(' | ')' | '[' | ']' | '<' | '>'));
        if SECRET_FLAGS.contains(&core.to_ascii_lowercase().as_str()) {
            redact_next = true;
            out.push(word.to_string());
            continue;
        }
        if core.starts_with("eyJ") && core.matches('.').count() >= 2 {
            out.push(word.replace(core, "[REDACTED_JWT]"));
            continue;
        }
        if looks_like_email(core) {
            out.push(word.replace(core, "[EMAIL]"));
            continue;
        }
        if let Some(rep) = public_ip_replacement(core) {
            out.push(word.replace(core, rep));
            continue;
        }
        out.push(word.to_string());
    }
    out.join(" ")
}

fn looks_like_email(s: &str) -> bool {
    let Some(at) = s.find('@') else { return false };
    at > 0
        && s[at + 1..].contains('.')
        && !s.contains('/')
        && !s.contains('\\')
        && s[at + 1..].chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
}

fn public_ip_replacement(s: &str) -> Option<&'static str> {
    let host = s.split(':').next().unwrap_or(s);
    let ip: std::net::Ipv4Addr = host.parse().ok()?;
    if ip.is_loopback() || ip.is_unspecified() || ip.is_private() || ip.is_link_local() {
        None
    } else {
        Some("<ip>")
    }
}

/// `"accessToken":"abc"`, `token=abc`, `password: abc` ... → value replaced.
fn redact_key_values(line: &str) -> String {
    let lower = line.to_ascii_lowercase();
    let bytes = line.as_bytes();
    let mut out = String::with_capacity(line.len());
    let mut i = 0;
    'outer: while i < line.len() {
        if line.is_char_boundary(i) {
            for key in SECRET_KEYS {
                if lower[i..].starts_with(key) {
                    let mut j = i + key.len();
                    while j < bytes.len() && (bytes[j] == b'"' || bytes[j] == b'\'') {
                        j += 1;
                    }
                    while j < bytes.len() && bytes[j] == b' ' {
                        j += 1;
                    }
                    if j < bytes.len() && (bytes[j] == b':' || bytes[j] == b'=') {
                        j += 1;
                        while j < bytes.len() && (bytes[j] == b' ' || bytes[j] == b'"' || bytes[j] == b'\'') {
                            j += 1;
                        }
                        let start = j;
                        while j < bytes.len()
                            && !matches!(bytes[j], b' ' | b'"' | b'\'' | b',' | b'}' | b'&' | b';' | b')' | b']')
                        {
                            j += 1;
                        }
                        if j > start {
                            out.push_str(&line[i..start]);
                            out.push_str("[REDACTED]");
                            i = j;
                            continue 'outer;
                        }
                    }
                }
            }
        }
        let ch = line[i..].chars().next().unwrap_or(' ');
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn tail_bytes(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_string();
    }
    let mut start = s.len() - max;
    while !s.is_char_boundary(start) {
        start += 1;
    }
    format!("[... {} bytes cut from the start ...]\n{}", start, &s[start..])
}

// ───────────────────────────── environment ─────────────────────────────

struct EnvInfo {
    /// Short facts for the embed.
    fields: Vec<(&'static str, String)>,
    /// Everything, for environment.txt.
    text: String,
}

fn run_cmd(prog: &str, args: &[&str]) -> Option<String> {
    let mut cmd = std::process::Command::new(prog);
    cmd.args(args)
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let out = cmd.output().ok()?;
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

#[cfg(target_os = "linux")]
fn kv_from_file(path: &str, key: &str) -> Option<String> {
    std::fs::read_to_string(path).ok()?.lines().find_map(|l| {
        let (k, v) = l.split_once(':').or_else(|| l.split_once('='))?;
        (k.trim() == key).then(|| v.trim().trim_matches('"').to_string())
    })
}

fn kib_to_human(kib: u64) -> String {
    format!("{:.1} GiB", kib as f64 / 1024.0 / 1024.0)
}

/// OS, CPU, RAM, GPU. Blocking (runs a few short helper commands).
fn hardware_info() -> (String, String, String, String, String) {
    let mut os = format!("{} ({})", std::env::consts::OS, std::env::consts::FAMILY);
    let mut cpu = "unknown".to_string();
    let mut ram = "unknown".to_string();
    let mut gpu = "unknown".to_string();
    let mut extra = String::new();

    #[cfg(target_os = "linux")]
    {
        if let Some(n) = kv_from_file("/etc/os-release", "PRETTY_NAME") {
            os = n;
        }
        if let Ok(v) = std::fs::read_to_string("/proc/version") {
            let _ = writeln!(extra, "kernel: {}", v.trim());
        }
        if let Some(n) = kv_from_file("/proc/cpuinfo", "model name") {
            cpu = n;
        }
        let mem = |k: &str| kv_from_file("/proc/meminfo", k).and_then(|v| v.split_whitespace().next().and_then(|n| n.parse::<u64>().ok()));
        if let Some(t) = mem("MemTotal") {
            ram = format!("{} total, {} available", kib_to_human(t), mem("MemAvailable").map(kib_to_human).unwrap_or_else(|| "?".into()));
        }
        if let Some(s) = mem("SwapTotal") {
            let _ = writeln!(extra, "swap: {} total, {} free", kib_to_human(s), mem("SwapFree").map(kib_to_human).unwrap_or_else(|| "?".into()));
        }
        if let Some(l) = run_cmd("lspci", &["-mm"]) {
            let g: Vec<String> = l
                .lines()
                .filter(|l| l.contains("VGA compatible") || l.contains("3D controller") || l.contains("Display controller"))
                .map(|l| l.replace('"', "").split_whitespace().collect::<Vec<_>>().join(" "))
                .collect();
            if !g.is_empty() {
                gpu = g.join(" | ");
            }
        }
        if let Ok(v) = std::fs::read_to_string("/proc/driver/nvidia/version") {
            let _ = writeln!(extra, "nvidia driver: {}", v.lines().next().unwrap_or("").trim());
        }
        if let Some(l) = run_cmd("sh", &["-c", "ls /sys/class/drm 2>/dev/null | grep -v '-' | tr '\\n' ' '"]) {
            let _ = writeln!(extra, "drm devices: {l}");
        }
        if let Some(l) = run_cmd("uptime", &["-p"]) {
            let _ = writeln!(extra, "system uptime: {l}");
        }
    }
    #[cfg(target_os = "windows")]
    {
        let script = "$o=Get-CimInstance Win32_OperatingSystem; 'OS='+$o.Caption+' '+$o.Version+' build '+$o.BuildNumber; \
                      'CPU='+(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name); \
                      'RAM='+[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)+' GiB total, '+[math]::Round($o.FreePhysicalMemory/1MB,1)+' GiB free'; \
                      'GPU='+((Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name+' (driver '+$_.DriverVersion+')' }) -join ' | ')";
        if let Some(out) = run_cmd("powershell", &["-NoProfile", "-NonInteractive", "-Command", script]) {
            for l in out.lines() {
                if let Some(v) = l.strip_prefix("OS=") { os = v.trim().to_string(); }
                else if let Some(v) = l.strip_prefix("CPU=") { cpu = v.trim().to_string(); }
                else if let Some(v) = l.strip_prefix("RAM=") { ram = v.trim().to_string(); }
                else if let Some(v) = l.strip_prefix("GPU=") { gpu = v.trim().to_string(); }
            }
        }
    }
    #[cfg(target_os = "macos")]
    {
        if let Some(v) = run_cmd("sw_vers", &[]) {
            os = v.lines().map(|l| l.split_whitespace().collect::<Vec<_>>().join(" ")).collect::<Vec<_>>().join(", ");
        }
        if let Some(v) = run_cmd("sysctl", &["-n", "machdep.cpu.brand_string"]) { cpu = v; }
        if let Some(v) = run_cmd("sysctl", &["-n", "hw.memsize"]).and_then(|v| v.parse::<u64>().ok()) {
            ram = format!("{:.1} GiB total", v as f64 / 1024.0 / 1024.0 / 1024.0);
        }
        if let Some(v) = run_cmd("system_profiler", &["SPDisplaysDataType"]) {
            let g: Vec<String> = v.lines().filter(|l| l.contains("Chipset Model")).map(|l| l.trim().to_string()).collect();
            if !g.is_empty() { gpu = g.join(" | "); }
        }
    }

    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0);
    cpu = format!("{cpu} ({cores} threads)");
    (os, cpu, ram, gpu, extra)
}

fn packaging() -> String {
    let mut p: Vec<&str> = Vec::new();
    if std::env::var_os("APPIMAGE").is_some() { p.push("AppImage"); }
    if std::path::Path::new("/.flatpak-info").exists() { p.push("Flatpak"); }
    if std::env::var_os("SNAP").is_some() { p.push("Snap"); }
    if std::env::var_os("container").is_some() { p.push("container"); }
    if p.is_empty() { "native / installed".to_string() } else { p.join("+") }
}

/// Non-sensitive launcher settings: booleans and numbers only (plus the
/// network mode), so no paths, names, IPs or URLs can sneak in.
fn settings_summary(app: &AppHandle) -> String {
    let Some(state) = app.try_state::<AppState>() else {
        return "(state not available)".into();
    };
    let v = serde_json::to_value(&*state.settings.lock().unwrap_or_else(|e| e.into_inner())).unwrap_or(Value::Null);
    let mut lines: Vec<String> = Vec::new();
    if let Value::Object(map) = v {
        let mut keys: Vec<_> = map.keys().cloned().collect();
        keys.sort();
        for k in keys {
            match &map[&k] {
                Value::Bool(_) | Value::Number(_) => lines.push(format!("{k} = {}", map[&k])),
                Value::String(s) if k == "network_mode" => lines.push(format!("{k} = {s}")),
                Value::String(s) if !s.is_empty() && (k.contains("dir") || k.contains("path")) => {
                    lines.push(format!("{k} = <set>"))
                }
                _ => {}
            }
        }
    }
    lines.join("\n")
}

fn launcher_state_summary(app: &AppHandle) -> String {
    let mut s = String::new();
    let Some(state) = app.try_state::<AppState>() else {
        return s;
    };
    let _ = writeln!(s, "data dir free space: {}", free_space(&state.data_dir));
    if let Ok(accts) = state.accounts.lock() {
        let count = |t: &str| accts.iter().filter(|a| a.account_type == t).count();
        let _ = writeln!(
            s,
            "accounts: {} total (microsoft={}, elyby={}, offline={})",
            accts.len(), count("microsoft"), count("elyby"), count("offline")
        );
    }
    if let Ok(inst) = state.instances.lock() {
        let _ = writeln!(s, "instances: {}", inst.len());
        for i in inst.iter().take(40) {
            let _ = writeln!(s, "  - mc={} loader={} {}", i.minecraft_version, i.loader, i.loader_version);
        }
    }
    if let Ok(run) = state.running_instances.lock() {
        let _ = writeln!(s, "running instances at report time: {}", run.len());
        for (id, info) in run.iter().take(10) {
            let _ = writeln!(s, "  - {id} pid={:?}", info.pid);
        }
    }
    s
}

fn free_space(dir: &Path) -> String {
    #[cfg(unix)]
    {
        if let Some(out) = run_cmd("df", &["-Pk", &dir.to_string_lossy()]) {
            if let Some(avail) = out.lines().nth(1).and_then(|l| l.split_whitespace().nth(3)).and_then(|n| n.parse::<u64>().ok()) {
                return kib_to_human(avail);
            }
        }
    }
    let _ = dir;
    "unknown".into()
}

fn env_var_dump() -> String {
    let mut vars: Vec<(String, String)> = std::env::vars()
        .filter(|(k, _)| {
            const EXACT: &[&str] = &[
                "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "DESKTOP_SESSION", "WAYLAND_DISPLAY", "DISPLAY",
                "LANG", "LC_ALL", "TZ", "SHELL", "APPIMAGE", "SNAP", "container", "GDK_BACKEND", "RUST_BACKTRACE",
            ];
            const PREFIX: &[&str] = &["WEBKIT_", "GDK_", "GTK_", "LIBGL_", "MESA_", "__GL", "__NV", "ZL_", "MALLOC_", "WAYLAND_", "QT_", "DRI_"];
            EXACT.contains(&k.as_str()) || PREFIX.iter().any(|p| k.starts_with(p))
        })
        .collect();
    vars.sort();
    vars.into_iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("\n")
}

fn gather_environment(app: &AppHandle, crash_version: Option<&str>) -> EnvInfo {
    let (os, cpu, ram, gpu, hw_extra) = hardware_info();
    let webview = tauri::webview_version().unwrap_or_else(|e| format!("unknown ({e})"));
    let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_else(|_| "n/a".into());
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_else(|_| "n/a".into());
    let cur_ver = env!("CARGO_PKG_VERSION");
    let version = match crash_version {
        Some(v) if v != cur_ver => format!("{v} (crashed) → {cur_ver} (now)"),
        _ => cur_ver.to_string(),
    };
    let build = if cfg!(debug_assertions) { "debug" } else { "release" };

    let mut fields = vec![
        ("Launcher", format!("{version} ({build})")),
        ("OS", format!("{os} · {}", std::env::consts::ARCH)),
        ("CPU", cpu.clone()),
        ("RAM", ram.clone()),
        ("GPU", gpu.clone()),
        ("WebView", webview.clone()),
        ("Session / Desktop", format!("{session} / {desktop}")),
        ("Packaging", packaging()),
    ];
    fields.retain(|(_, v)| !v.trim().is_empty());

    let mut t = String::new();
    let _ = writeln!(t, "== LAUNCHER ==");
    let _ = writeln!(t, "version: {version}\nbuild: {build}\ntauri: {}\nwebview: {webview}\npackaging: {}", tauri::VERSION, packaging());
    let _ = writeln!(t, "exe: {}", std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_default());
    let _ = writeln!(t, "\n== SYSTEM ==");
    let _ = writeln!(t, "os: {os}\narch: {}\ncpu: {cpu}\nram: {ram}\ngpu: {gpu}", std::env::consts::ARCH);
    let _ = writeln!(t, "{hw_extra}");
    let _ = writeln!(t, "== ENVIRONMENT VARIABLES (filtered) ==\n{}", env_var_dump());
    let _ = writeln!(t, "\n== LAUNCHER STATE ==\n{}", launcher_state_summary(app));
    let _ = writeln!(t, "== LOG FILES ==\n{}", log_dir_listing());
    EnvInfo { fields, text: t }
}

fn log_dir_listing() -> String {
    let Some(dir) = crashlog::logs_dir() else { return "(unknown)".into() };
    let mut rows: Vec<(SystemTime, String)> = std::fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let m = e.metadata().ok()?;
            Some((m.modified().ok()?, format!("{} ({} B)", e.file_name().to_string_lossy(), m.len())))
        })
        .collect();
    rows.sort_by(|a, b| b.0.cmp(&a.0));
    rows.into_iter().take(25).map(|r| r.1).collect::<Vec<_>>().join("\n")
}

// ───────────────────────────── logs ─────────────────────────────

fn read_gz(path: &Path) -> Option<String> {
    use std::io::Read;
    let f = std::fs::File::open(path).ok()?;
    let mut dec = flate2::read::GzDecoder::new(f);
    let mut buf = Vec::new();
    dec.read_to_end(&mut buf).ok()?;
    Some(String::from_utf8_lossy(&buf).to_string())
}

/// The log of the run a report belongs to. `latest.log` for a live process;
/// for a report from a previous run, the archive created at this start's
/// `AppState::new` (the first `*.log.gz` not older than the report).
fn log_for_report(report_path: Option<&Path>) -> Option<String> {
    let dir = crashlog::logs_dir()?;
    let Some(rp) = report_path else {
        return std::fs::read_to_string(dir.join("latest.log"))
            .or_else(|_| std::fs::read(dir.join("latest.log")).map(|b| String::from_utf8_lossy(&b).to_string()))
            .ok();
    };
    let rtime = std::fs::metadata(rp).and_then(|m| m.modified()).ok()?;
    let mut archives: Vec<(SystemTime, PathBuf)> = std::fs::read_dir(&dir)
        .ok()?
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().ends_with(".log.gz"))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    archives.sort_by(|a, b| a.0.cmp(&b.0));
    let slack = Duration::from_secs(5);
    let pick = archives.iter().find(|(t, _)| *t + slack >= rtime)?;
    read_gz(&pick.1)
}

// ───────────────────────────── building + sending ─────────────────────────────

fn kind_of(name: &str) -> (&'static str, u32) {
    if name.starts_with("panic-") {
        ("💥 Rust panic", 0xE5484D)
    } else if name.starts_with("fatal-signal-") {
        ("☠️ Native crash (fatal signal)", 0xE5484D)
    } else if name.starts_with("unclean-exit-") {
        ("⚠️ Unclean exit (killed / OOM / power loss)", 0xF59E0B)
    } else if name.starts_with("freeze-") {
        ("🧊 UI freeze", 0xEAB308)
    } else if name.starts_with("frontend-") {
        ("🔌 Frontend failed to load (localhost unreachable)", 0xE5484D)
    } else {
        ("🧪 Test report", 0x3B82F6)
    }
}

fn field_after(report: &str, label: &str) -> Option<String> {
    report.lines().find_map(|l| {
        let (k, v) = l.split_once(':')?;
        (k.trim() == label).then(|| v.trim().to_string())
    })
}

fn detail_block(report: &str) -> String {
    if let Some(i) = report.find("detail     :") {
        let rest = &report[i + "detail     :".len()..];
        let end = rest.find("\n---------------- last events").unwrap_or(rest.len());
        let d = rest[..end].trim();
        if !d.is_empty() {
            return d.chars().take(1500).collect();
        }
    }
    report.chars().take(800).collect()
}

fn anon_id() -> String {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    dirs::home_dir().map(|p| p.to_string_lossy().to_string()).unwrap_or_default().hash(&mut h);
    std::env::consts::OS.hash(&mut h);
    format!("{:08x}", h.finish() as u32)
}

/// Zips the summary plus every attached `.txt` file into one archive.
fn build_zip(summary: &[u8], files: &[(String, Vec<u8>)]) -> Result<Vec<u8>, String> {
    use std::io::Write;
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mut zw = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    zw.start_file("report-summary.txt", opts).map_err(|e| e.to_string())?;
    zw.write_all(summary).map_err(|e| e.to_string())?;
    for (name, data) in files {
        if !name.ends_with(".txt") {
            continue;
        }
        zw.start_file(name.as_str(), opts).map_err(|e| e.to_string())?;
        zw.write_all(data).map_err(|e| e.to_string())?;
    }
    let cursor = zw.finish().map_err(|e| e.to_string())?;
    Ok(cursor.into_inner())
}

async fn upload(app: &AppHandle, url: &str, embed: Value, files: Vec<(String, Vec<u8>)>) -> Result<(), String> {
    let payload = json!({
        "username": "ZeroLauncher Crash Reporter",
        "embeds": [embed],
        "allowed_mentions": { "parse": [] },
    })
    .to_string();
    let Some(state) = app.try_state::<AppState>() else {
        return Err("launcher state not ready".into());
    };

    for attempt in 0..2 {
        let resp = network::send(app, &state, "crash report upload", |c| {
            let mut form = reqwest::multipart::Form::new().text("payload_json", payload.clone());
            for (i, (name, data)) in files.iter().enumerate() {
                form = form.part(
                    format!("files[{i}]"),
                    reqwest::multipart::Part::bytes(data.clone()).file_name(name.clone()),
                );
            }
            c.post(url).timeout(Duration::from_secs(60)).multipart(form)
        })
        .await?;

        let status = resp.status();
        if status.is_success() {
            return Ok(());
        }
        let body = resp.text().await.unwrap_or_default();
        if status.as_u16() == 429 && attempt == 0 {
            let wait = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|v| v.get("retry_after").and_then(|r| r.as_f64()))
                .unwrap_or(2.0)
                .clamp(0.5, 30.0);
            tokio::time::sleep(Duration::from_secs_f64(wait)).await;
            continue;
        }
        let short: String = body.chars().take(200).collect();
        return Err(format!("Discord answered HTTP {status}: {short}"));
    }
    Err("rate limited by Discord".into())
}

/// Builds and uploads one report. `report_path` is the report file on disk
/// (its run's log is attached); `None` = live process (current latest.log).
async fn send_report(
    app: &AppHandle,
    url: &str,
    kind_name: &str,
    report_text: &str,
    report_path: Option<&Path>,
) -> Result<(), String> {
    let app2 = app.clone();
    let crash_version = field_after(report_text, "version");
    let gather = tauri::async_runtime::spawn_blocking(move || {
        gather_environment(&app2, crash_version.as_deref())
    });
    let env = match tokio::time::timeout(Duration::from_secs(25), gather).await {
        Ok(Ok(e)) => e,
        _ => EnvInfo {
            fields: vec![("Launcher", env!("CARGO_PKG_VERSION").to_string()), ("OS", std::env::consts::OS.to_string())],
            text: "(environment gathering timed out or failed)".into(),
        },
    };

    let scrub = Scrubber::new(app);
    let (title, color) = kind_of(kind_name);
    let report_id = uuid::Uuid::new_v4().simple().to_string()[..8].to_string();

    let log_text = log_for_report(report_path)
        .map(|l| scrub.scrub(&tail_bytes(&l, MAX_LOG_BYTES)))
        .unwrap_or_else(|| "(no log available for this run)".into());

    let mut embed_fields: Vec<Value> = env
        .fields
        .iter()
        .map(|(n, v)| json!({ "name": n, "value": format!("`{}`", scrub.scrub(v).chars().take(1000).collect::<String>().replace('`', "'")), "inline": n != &"GPU" }))
        .collect();
    if let Some(up) = field_after(report_text, "uptime") {
        embed_fields.push(json!({ "name": "Uptime at crash", "value": format!("`{up}`"), "inline": true }));
    }
    if let Some(t) = field_after(report_text, "time") {
        embed_fields.push(json!({ "name": "Crash time (local)", "value": format!("`{t}`"), "inline": true }));
    }
    if let Some(m) = field_after(report_text, "memory") {
        let m: String = scrub.scrub(&m).chars().take(500).collect();
        embed_fields.push(json!({ "name": "Process memory", "value": format!("`{}`", m.replace('`', "'")), "inline": false }));
    }

    let detail = scrub.scrub(&detail_block(report_text)).replace("```", "'''");
    let embed = json!({
        "title": title,
        "description": format!("```\n{}\n```", detail.chars().take(1500).collect::<String>()),
        "color": color,
        "fields": embed_fields,
        "footer": { "text": format!("report {report_id} · reporter {}", anon_id()) },
        "timestamp": chrono::Utc::now().to_rfc3339(),
    });

    let mut files = vec![
        ("report.txt".to_string(), scrub.scrub(&tail_bytes(report_text, MAX_REPORT_BYTES)).into_bytes()),
        ("environment.txt".to_string(), scrub.scrub(&env.text).into_bytes()),
        ("settings.txt".to_string(), settings_summary(app).into_bytes()),
        ("launcher-log.txt".to_string(), log_text.into_bytes()),
    ];

    // A new text file with everything the Discord embed shows (title,
    // detail, every field, footer, timestamp), so the information survives
    // outside Discord too. It goes into the zip together with all the
    // attached .txt files — one download with the complete report.
    let mut summary = String::new();
    let _ = writeln!(summary, "{title}");
    let _ = writeln!(summary, "{}", "=".repeat(title.chars().count().max(8)));
    let _ = writeln!(summary, "Report ID : {report_id}");
    let _ = writeln!(summary, "Reporter  : {}", anon_id());
    let _ = writeln!(summary, "Sent at   : {}", chrono::Utc::now().to_rfc3339());
    let _ = writeln!(summary, "\n-- Detail --\n{detail}");
    let _ = writeln!(summary, "\n-- Fields --");
    for f in &embed_fields {
        let name = f.get("name").and_then(|v| v.as_str()).unwrap_or("");
        let val = f.get("value").and_then(|v| v.as_str()).unwrap_or("").trim_matches('`');
        let _ = writeln!(summary, "{name}: {val}");
    }
    let _ = writeln!(summary, "\n-- Files in this archive --");
    for (n, d) in &files {
        let _ = writeln!(summary, "{n} ({} B)", d.len());
    }
    let summary_bytes = summary.into_bytes();

    match build_zip(&summary_bytes, &files) {
        Ok(zip) => files.push((format!("crash-report-{report_id}.zip"), zip)),
        Err(e) => clog(app, "WARN", &format!("Could not build crash report zip: {e}")),
    }
    upload(app, url, embed, files).await
}

fn sent_marker(path: &Path) -> PathBuf {
    let mut s = path.as_os_str().to_os_string();
    s.push(".sent");
    PathBuf::from(s)
}

fn mark_sent(path: &Path) {
    let _ = std::fs::write(sent_marker(path), b"");
}

// ───────────────────────────── public API ─────────────────────────────

/// Call once after `AppState` is managed. Uploads reports left behind by
/// previous runs, a few seconds after startup so it never slows the UI.
pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(12)).await;
        send_pending(&app).await;
    });
}

async fn send_pending(app: &AppHandle) {
    let Some(dir) = crashlog::logs_dir() else { return };
    let url = configured_url(app);

    let mut pending: Vec<(PathBuf, SystemTime, String)> = std::fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            if !name.ends_with(".log") || !REPORT_PREFIXES.iter().any(|p| name.starts_with(p)) {
                return None;
            }
            let path = e.path();
            if sent_marker(&path).exists() {
                return None;
            }
            Some((path, e.metadata().ok()?.modified().ok()?, name))
        })
        .collect();
    if pending.is_empty() {
        return;
    }
    pending.sort_by(|a, b| a.1.cmp(&b.1));

    // Without a webhook leave the files alone: they may be uploaded later
    // once one is configured (and old ones then age out below).
    let Some(url) = url else { return };

    // A panic with panic=abort also raises SIGABRT, so the same crash leaves
    // both reports a moment apart — upload just the panic one.
    let panic_times: Vec<SystemTime> = pending.iter().filter(|p| p.2.starts_with("panic-")).map(|p| p.1).collect();
    let now = SystemTime::now();
    let mut sent = 0usize;
    for (path, mtime, name) in pending.into_iter().rev().collect::<Vec<_>>() {
        let too_old = now.duration_since(mtime).map(|d| d > MAX_REPORT_AGE).unwrap_or(false);
        let dup_abort = name.starts_with("fatal-signal-")
            && panic_times.iter().any(|t| {
                let d = if *t > mtime { t.duration_since(mtime) } else { mtime.duration_since(*t) };
                d.map(|d| d < Duration::from_secs(15)).unwrap_or(false)
            });
        if too_old || dup_abort || sent >= MAX_PENDING_PER_START {
            mark_sent(&path);
            continue;
        }
        let Ok(bytes) = std::fs::read(&path) else { continue };
        let text = String::from_utf8_lossy(&bytes).to_string();
        clog(app, "INFO", &format!("Uploading crash report {name} to the configured Discord webhook"));
        match send_report(app, &url, &name, &text, Some(&path)).await {
            Ok(()) => {
                mark_sent(&path);
                sent += 1;
                clog(app, "INFO", &format!("Crash report {name} uploaded"));
                tokio::time::sleep(Duration::from_millis(1500)).await;
            }
            Err(e) => {
                // Keep it unsent: next start tries again.
                clog(app, "WARN", &format!("Could not upload crash report {name}: {e}"));
                return;
            }
        }
    }
}

/// Called by the watchdog when the UI froze. Never blocks the caller.
pub fn report_freeze(app: &AppHandle, detail: &str) {
    let now = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let last = LAST_FREEZE_UNIX.load(Ordering::SeqCst);
    if FREEZES_SENT.load(Ordering::SeqCst) >= MAX_FREEZES_PER_SESSION || (last != 0 && now.saturating_sub(last) < MIN_FREEZE_GAP_SECS) {
        return;
    }
    // Always keep the local report, even if nothing is uploaded.
    let report = crashlog::build_report_opts("UI FREEZE", detail, false);
    let path = crashlog::write_report("freeze", &report);

    let Some(url) = configured_url(app) else { return };
    FREEZES_SENT.fetch_add(1, Ordering::SeqCst);
    LAST_FREEZE_UNIX.store(now, Ordering::SeqCst);

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let name = path.as_ref().and_then(|p| p.file_name()).map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "freeze-live.log".into());
        // `None`: a live process, so attach the current latest.log.
        match send_report(&app, &url, &name, &report, None).await {
            Ok(()) => {
                if let Some(p) = &path {
                    mark_sent(p);
                }
                clog(&app, "INFO", "UI freeze report uploaded");
            }
            Err(e) => clog(&app, "WARN", &format!("Could not upload UI freeze report: {e}")),
        }
    });
}

/// Called by the watchdog when the page could not be loaded because the
/// frontend server (e.g. the dev server on `http://localhost:1420`) is not
/// reachable. Never blocks the caller.
pub fn report_frontend_failure(app: &AppHandle, detail: &str) {
    if FRONTEND_FAILS_SENT.load(Ordering::SeqCst) >= MAX_FRONTEND_FAILS_PER_SESSION {
        return;
    }
    // Always keep the local report, even if nothing is uploaded.
    let report = crashlog::build_report_opts("FRONTEND LOAD FAILED", detail, false);
    let path = crashlog::write_report("frontend", &report);

    let Some(url) = configured_url(app) else { return };
    FRONTEND_FAILS_SENT.fetch_add(1, Ordering::SeqCst);

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let name = path.as_ref().and_then(|p| p.file_name()).map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "frontend-live.log".into());
        match send_report(&app, &url, &name, &report, None).await {
            Ok(()) => {
                if let Some(p) = &path {
                    mark_sent(p);
                }
                clog(&app, "INFO", "Frontend-load-failure report uploaded");
            }
            Err(e) => clog(&app, "WARN", &format!("Could not upload frontend-load-failure report: {e}")),
        }
    });
}

/// Settings → "Send test report". Uses the URL typed in the box if given
/// (so it can be tested before saving).
#[tauri::command]
pub async fn send_test_crash_report(app: AppHandle, webhook_url: Option<String>) -> Result<String, String> {
    // Test reports are only for your own webhook: a blank field (= default
    // webhook) or the default URL itself is refused, nothing is sent.
    let url = webhook_url.map(|u| u.trim().to_string()).unwrap_or_default();
    if url.is_empty() || (!default_webhook().is_empty() && url == default_webhook()) {
        return Err("Test reports can't be sent to the default webhook. Enter your own webhook URL to test it.".into());
    }
    if !is_valid_webhook(&url) {
        return Err("That doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/<id>/<token>).".into());
    }
    let report = crashlog::build_report_opts(
        "TEST REPORT",
        "message : This is a test report sent from Settings → Privacy & Developer → Crash Reports.\nNothing crashed.",
        false,
    );
    send_report(&app, &url, "test", &report, None).await?;
    Ok("Test report sent — check your Discord channel.".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn webhook_validation() {
        assert!(is_valid_webhook("https://discord.com/api/webhooks/123456/abcDEF-_x"));
        assert!(is_valid_webhook("https://canary.discord.com/api/v10/webhooks/123/tok"));
        assert!(!is_valid_webhook("http://discord.com/api/webhooks/123/tok"));
        assert!(!is_valid_webhook("https://evil.example/api/webhooks/123/tok"));
        assert!(!is_valid_webhook("https://discord.com.evil.example/api/webhooks/123/tok"));
        assert!(!is_valid_webhook("https://discord.com/api/webhooks/abc/tok"));
        assert!(!is_valid_webhook(""));
    }

    #[test]
    fn scrubs_secrets() {
        let l = scrub_line("java --accessToken abc123 --username Steve --session xyz -Dfoo=1");
        assert!(!l.contains("abc123") && !l.contains("xyz"), "{l}");
        let l = scrub_line(r#"{"access_token":"eyJhbGciOi.payload.sig","x":1}"#);
        assert!(!l.contains("eyJ"), "{l}");
        let l = scrub_line("login for bob@example.com from 8.8.8.8:25565 and 127.0.0.1");
        assert!(l.contains("[EMAIL]") && l.contains("<ip>") && l.contains("127.0.0.1"), "{l}");
        assert_eq!(scrub_line("Loading mods from 192.168.1.5"), "Loading mods from 192.168.1.5");
        assert_eq!(scrub_line("héllo wörld ünïcode"), "héllo wörld ünïcode");
    }

    #[test]
    fn tail_is_char_safe() {
        let s = "ééééé";
        assert!(tail_bytes(s, 3).contains('é'));
    }
}
