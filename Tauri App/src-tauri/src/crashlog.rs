//! Crash forensics: make sure that *if the launcher dies, the log says how*.
//!
//! Three layers, because a crash can happen in three different ways:
//!
//! 1. **Rust panic** (release builds use `panic = "abort"`, so a panic kills
//!    the whole process instantly). The panic hook writes a full report
//!    (message, location, thread, backtrace, memory, recent IPC/UI
//!    breadcrumbs, tail of `latest.log`) to `logs/panic-<time>.log` *before*
//!    the abort happens.
//! 2. **Native crash** (SIGSEGV / SIGBUS / SIGILL / SIGFPE / SIGABRT from
//!    WebKitGTK, GTK, GPU drivers, libc, stack overflow...). A signal handler
//!    (Linux) writes `logs/fatal-signal-<time>.log` and then chains to the
//!    previous handler so normal crash behaviour is unchanged.
//! 3. **Anything else** (OOM kill, `kill -9`, power loss, a handler that could
//!    not run). A `logs/session.state` file is rewritten every 2 s with
//!    `clean=false` and only flipped to `clean=true` on a normal exit. If the
//!    next start finds `clean=false`, it writes `logs/unclean-exit-<time>.log`
//!    containing the last known state and the tail of the previous run's log.
//!
//! Everything here is best-effort and must never itself cause a crash, so no
//! `unwrap()`, and only `try_lock()` inside the crash paths.

use std::collections::VecDeque;
use std::fmt::Write as _;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write as _};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

static LOGS_DIR: OnceLock<PathBuf> = OnceLock::new();
static STARTED: OnceLock<Instant> = OnceLock::new();
static STARTED_AT: OnceLock<String> = OnceLock::new();
static BREADCRUMBS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static REPORTING: AtomicBool = AtomicBool::new(false);
static CLEAN_EXIT: AtomicBool = AtomicBool::new(false);

const MAX_BREADCRUMBS: usize = 120;
const LOG_TAIL_LINES: usize = 250;

// ── breadcrumbs ──────────────────────────────────────────────────────────────

/// Remember "what was the app doing" (IPC calls, window/run events, ...).
/// The last [`MAX_BREADCRUMBS`] are dumped into every crash report.
pub fn note(kind: &str, msg: &str) {
    let msg: String = msg.chars().take(300).collect();
    let line = format!(
        "{} [{}] {}",
        chrono::Local::now().format("%H:%M:%S%.3f"),
        kind,
        msg
    );
    if let Ok(mut q) = BREADCRUMBS.lock() {
        if q.len() >= MAX_BREADCRUMBS {
            q.pop_front();
        }
        q.push_back(line);
    }
}

// ── report building ─────────────────────────────────────────────────────────

fn proc_status_summary() -> String {
    #[cfg(target_os = "linux")]
    {
        let mut out = String::new();
        if let Ok(s) = fs::read_to_string("/proc/self/status") {
            for l in s.lines() {
                if l.starts_with("VmSize")
                    || l.starts_with("VmRSS")
                    || l.starts_with("VmPeak")
                    || l.starts_with("VmSwap")
                    || l.starts_with("Threads")
                {
                    let _ = write!(out, "{} ", l.split_whitespace().collect::<Vec<_>>().join(" "));
                }
            }
        }
        if let Ok(rd) = fs::read_dir("/proc/self/fd") {
            let _ = write!(out, "open_fds={}", rd.count());
        }
        if let Ok(s) = fs::read_to_string("/proc/loadavg") {
            let _ = write!(out, " loadavg={}", s.trim());
        }
        if let Ok(s) = fs::read_to_string("/proc/meminfo") {
            for l in s.lines().take(3) {
                let _ = write!(out, " | {}", l.split_whitespace().collect::<Vec<_>>().join(" "));
            }
        }
        out
    }
    #[cfg(not(target_os = "linux"))]
    {
        "n/a on this OS".to_string()
    }
}

fn tail_of_latest_log(lines: usize) -> String {
    let Some(dir) = LOGS_DIR.get() else {
        return "(logs dir not known yet)".to_string();
    };
    tail_of_file(&dir.join("latest.log"), lines)
}

fn tail_of_file(path: &Path, lines: usize) -> String {
    let Ok(mut f) = fs::File::open(path) else {
        return format!("(could not open {})", path.display());
    };
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    let start = len.saturating_sub(256 * 1024);
    let _ = f.seek(SeekFrom::Start(start));
    let mut buf = Vec::new();
    let _ = f.read_to_end(&mut buf);
    let text = String::from_utf8_lossy(&buf);
    let all: Vec<&str> = text.lines().collect();
    let from = all.len().saturating_sub(lines);
    all[from..].join("\n")
}

fn uptime_secs() -> u64 {
    STARTED.get().map(|s| s.elapsed().as_secs()).unwrap_or(0)
}

fn build_report(title: &str, detail: &str) -> String {
    let mut r = String::new();
    let _ = writeln!(r, "================ ZERO LAUNCHER CRASH REPORT ================");
    let _ = writeln!(r, "event      : {title}");
    let _ = writeln!(r, "time       : {}", chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f"));
    let _ = writeln!(r, "version    : {}", env!("CARGO_PKG_VERSION"));
    let _ = writeln!(
        r,
        "platform   : os={} arch={} pid={}",
        std::env::consts::OS,
        std::env::consts::ARCH,
        std::process::id()
    );
    let _ = writeln!(r, "started    : {}", STARTED_AT.get().map(|s| s.as_str()).unwrap_or("?"));
    let _ = writeln!(r, "uptime     : {}s", uptime_secs());
    let cur = std::thread::current();
    let _ = writeln!(r, "thread     : {:?} ({:?})", cur.name().unwrap_or("<unnamed>"), cur.id());
    let _ = writeln!(r, "memory     : {}", proc_status_summary());
    let _ = writeln!(r, "detail     :\n{detail}");

    let _ = writeln!(r, "\n---------------- last events (oldest first) ----------------");
    match BREADCRUMBS.try_lock() {
        Ok(q) => {
            for l in q.iter() {
                let _ = writeln!(r, "{l}");
            }
        }
        Err(_) => {
            let _ = writeln!(r, "(breadcrumb buffer busy)");
        }
    }

    let _ = writeln!(r, "\n---------------- backtrace ----------------");
    let _ = writeln!(r, "{}", std::backtrace::Backtrace::force_capture());

    let _ = writeln!(r, "\n---------------- last {LOG_TAIL_LINES} lines of latest.log ----------------");
    let _ = writeln!(r, "{}", tail_of_latest_log(LOG_TAIL_LINES));
    let _ = writeln!(r, "================ END OF REPORT ================");
    r
}

fn write_report(prefix: &str, report: &str) {
    // 1. stderr (visible when launched from a terminal / journald)
    eprintln!("{report}");

    let Some(dir) = LOGS_DIR.get() else {
        return;
    };
    let _ = fs::create_dir_all(dir);

    // 2. a dedicated, never-rotated file
    let name = format!("{prefix}-{}.log", chrono::Local::now().format("%Y%m%d-%H%M%S"));
    let _ = fs::write(dir.join(name), report);

    // 3. also into latest.log so it sits right after the lines that led to it
    if let Ok(mut f) = fs::OpenOptions::new().append(true).open(dir.join("latest.log")) {
        let _ = writeln!(f, "\n{report}");
        let _ = f.flush();
        let _ = f.sync_all();
    }
}

// ── layer 1: panic hook ─────────────────────────────────────────────────────

/// Call first thing in `run()`. Safe to call before the data dir is known:
/// the report then only goes to stderr.
pub fn install_panic_hook() {
    // Full backtraces for everything, including std's own default hook.
    if std::env::var_os("RUST_BACKTRACE").is_none() {
        std::env::set_var("RUST_BACKTRACE", "full");
    }
    STARTED.get_or_init(Instant::now);
    STARTED_AT.get_or_init(|| chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string());

    let default = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        if !REPORTING.swap(true, Ordering::SeqCst) {
            let loc = info
                .location()
                .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
                .unwrap_or_else(|| "unknown location".into());
            let msg = if let Some(s) = info.payload().downcast_ref::<&str>() {
                (*s).to_string()
            } else if let Some(s) = info.payload().downcast_ref::<String>() {
                s.clone()
            } else {
                "non-string panic payload".to_string()
            };
            let report = build_report("RUST PANIC", &format!("message : {msg}\nlocation: {loc}"));
            write_report("panic", &report);
            // Let the next panic (dev builds with unwinding) be reported too.
            REPORTING.store(false, Ordering::SeqCst);
        }
        default(info);
    }));
}

// ── layer 3: session marker ─────────────────────────────────────────────────

fn session_body(clean: bool) -> String {
    let last = match BREADCRUMBS.try_lock() {
        Ok(q) => {
            let skip = q.len().saturating_sub(15);
            q.iter().skip(skip).map(|l| format!("  {l}")).collect::<Vec<_>>().join("\n")
        }
        Err(_) => String::new(),
    };
    format!(
        "clean={clean}\npid={}\nversion={}\nstarted={}\nlast_beat={}\nuptime_s={}\nmem={}\nlast_events:\n{last}\n",
        std::process::id(),
        env!("CARGO_PKG_VERSION"),
        STARTED_AT.get().map(|s| s.as_str()).unwrap_or("?"),
        chrono::Local::now().format("%Y-%m-%d %H:%M:%S"),
        uptime_secs(),
        proc_status_summary(),
    )
}

fn write_session(clean: bool) {
    let Some(dir) = LOGS_DIR.get() else {
        return;
    };
    let tmp = dir.join("session.state.tmp");
    if fs::write(&tmp, session_body(clean)).is_ok() {
        let _ = fs::rename(&tmp, dir.join("session.state"));
    }
}

fn check_previous_session(dir: &Path) {
    let Ok(prev) = fs::read_to_string(dir.join("session.state")) else {
        return;
    };
    if !prev.lines().any(|l| l.trim() == "clean=false") {
        return;
    }
    // Another instance may legitimately still be running.
    if let Some(pid) = prev
        .lines()
        .find_map(|l| l.strip_prefix("pid="))
        .and_then(|p| p.trim().parse::<u32>().ok())
    {
        #[cfg(target_os = "linux")]
        {
            if pid != std::process::id() && Path::new(&format!("/proc/{pid}")).exists() {
                return;
            }
        }
        let _ = pid;
    }
    let mut r = String::new();
    let _ = writeln!(r, "================ PREVIOUS RUN DID NOT EXIT CLEANLY ================");
    let _ = writeln!(
        r,
        "The launcher was gone without a normal shutdown (crash, kill -9, OOM kill, power loss,\n\
         or a native crash whose handler could not run). Last recorded state:\n"
    );
    let _ = writeln!(r, "{prev}");
    let _ = writeln!(r, "---------------- tail of previous latest.log ----------------");
    let _ = writeln!(r, "{}", tail_of_file(&dir.join("latest.log"), LOG_TAIL_LINES));
    let name = format!("unclean-exit-{}.log", chrono::Local::now().format("%Y%m%d-%H%M%S"));
    let _ = fs::write(dir.join(name), &r);
    eprintln!("{r}");
}

/// Call once the data dir is known and BEFORE `AppState::new` (which archives
/// the previous `latest.log`).
pub fn install_files(data_dir: &Path) {
    let dir = data_dir.join("logs");
    let _ = fs::create_dir_all(&dir);
    STARTED.get_or_init(Instant::now);
    STARTED_AT.get_or_init(|| chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string());

    check_previous_session(&dir);
    let _ = LOGS_DIR.set(dir);
    write_session(false);

    let _ = std::thread::Builder::new()
        .name("crashlog-heartbeat".into())
        .spawn(|| loop {
            std::thread::sleep(Duration::from_secs(2));
            if CLEAN_EXIT.load(Ordering::SeqCst) {
                break;
            }
            write_session(false);
        });

    #[cfg(target_os = "linux")]
    sig::install();

    note("CRASHLOG", "panic hook, signal handlers and session marker installed");
}

/// Call on `RunEvent::Exit`.
pub fn mark_clean_exit() {
    CLEAN_EXIT.store(true, Ordering::SeqCst);
    write_session(true);
}

// ── layer 2: native signals (Linux) ─────────────────────────────────────────

#[cfg(target_os = "linux")]
mod sig {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    const SIGNALS: [libc::c_int; 5] = [libc::SIGSEGV, libc::SIGBUS, libc::SIGILL, libc::SIGFPE, libc::SIGABRT];
    static OLD_HANDLER: [AtomicUsize; 5] = [
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
    ];
    static OLD_FLAGS: [AtomicUsize; 5] = [
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
        AtomicUsize::new(0),
    ];

    fn sig_name(s: libc::c_int) -> &'static str {
        match s {
            libc::SIGSEGV => "SIGSEGV (invalid memory access / stack overflow)",
            libc::SIGBUS => "SIGBUS (bad memory mapping)",
            libc::SIGILL => "SIGILL (illegal instruction)",
            libc::SIGFPE => "SIGFPE (arithmetic error)",
            libc::SIGABRT => "SIGABRT (abort() — often a Rust panic with panic=abort, or glibc/GTK fatal error)",
            _ => "unknown",
        }
    }

    extern "C" fn handler(sig: libc::c_int, info: *mut libc::siginfo_t, ctx: *mut libc::c_void) {
        unsafe {
            // If anything below deadlocks (e.g. crash happened inside malloc),
            // the kernel still kills us after 5 s instead of hanging forever.
            libc::alarm(5);
        }
        if !REPORTING.swap(true, Ordering::SeqCst) {
            let addr = if info.is_null() { 0 } else { unsafe { (*info).si_addr() as usize } };
            let detail = format!(
                "signal       : {} ({})\nfault address: 0x{:x}\n\
                 This is a NATIVE crash, not a Rust panic. Typical causes: WebKitGTK/GTK/GPU driver bug,\n\
                 stack overflow in a worker thread, or memory corruption. Check the 'thread' line above.",
                sig,
                sig_name(sig),
                addr
            );
            let report = build_report(&format!("FATAL SIGNAL {sig}"), &detail);
            write_report("fatal-signal", &report);
        }

        // Chain to whatever was installed before us (e.g. Rust's stack-overflow handler).
        if let Some(i) = SIGNALS.iter().position(|s| *s == sig) {
            let h = OLD_HANDLER[i].load(Ordering::SeqCst);
            let fl = OLD_FLAGS[i].load(Ordering::SeqCst);
            if h > 1 {
                unsafe {
                    if fl & (libc::SA_SIGINFO as usize) != 0 {
                        let f: extern "C" fn(libc::c_int, *mut libc::siginfo_t, *mut libc::c_void) =
                            std::mem::transmute(h);
                        f(sig, info, ctx);
                    } else {
                        let f: extern "C" fn(libc::c_int) = std::mem::transmute(h);
                        f(sig);
                    }
                }
            }
        }
        // SA_RESETHAND already restored the default action: returning re-raises
        // the fault (SEGV/BUS/ILL/FPE) or lets abort() finish, so the process
        // still dies exactly as it would have (and still produces a core dump).
    }

    pub fn install() {
        for (i, &s) in SIGNALS.iter().enumerate() {
            unsafe {
                let mut new: libc::sigaction = std::mem::zeroed();
                new.sa_sigaction = handler as *const () as usize;
                new.sa_flags = libc::SA_SIGINFO | libc::SA_RESETHAND | libc::SA_ONSTACK;
                libc::sigemptyset(&mut new.sa_mask);
                let mut old: libc::sigaction = std::mem::zeroed();
                if libc::sigaction(s, &new, &mut old) == 0 {
                    OLD_HANDLER[i].store(old.sa_sigaction as usize, Ordering::SeqCst);
                    OLD_FLAGS[i].store(old.sa_flags as usize, Ordering::SeqCst);
                }
            }
        }
    }
}
