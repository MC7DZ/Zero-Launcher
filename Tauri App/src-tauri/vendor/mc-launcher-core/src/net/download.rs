//! Download plans and execution.

use std::{
    fs::{self, File},
    io::{Read, Write},
    path::PathBuf,
    sync::{atomic::{AtomicUsize, Ordering}, mpsc::Sender},
};

use crate::{
    io::hash::sha1_file,
    progress::{ProgressEvent, ProgressReporter, SkipReason},
    LauncherError, Result,
};

/// How many files to download at once, in steady state.
///
/// This is a *worker pool* size, not a batch size — workers pull the next
/// pending file the instant they finish their current one, so a single slow
/// file never blocks the rest of the pool from making progress. Sized close
/// to what other fast launchers (e.g. Prism Launcher) use for asset/library
/// downloads; most files here are small, so we're bound by round-trip
/// latency and connection count far more than local bandwidth or CPU.
const MAX_CONCURRENCY: usize = 24;

/// Supported checksum validation methods for downloaded files.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Checksum {
    /// SHA-1 checksum.
    Sha1(String),
    /// SHA-256 checksum.
    Sha256(String),
}

/// One file download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DownloadTask {
    /// Source URL.
    pub url: String,
    /// Additional mirrors tried, in order, if `url` 404s. Used for
    /// bare Fabric/Quilt-style library coordinates whose loader profile
    /// doesn't say which repo actually hosts them — some (e.g. legacy
    /// `net.minecraft:launchwrapper`) live on Maven Central or Mojang's
    /// own library host rather than `maven.fabricmc.net`.
    pub fallback_urls: Vec<String>,
    /// Destination path.
    pub destination: PathBuf,
    /// Optional checksum used for skip and validation decisions.
    pub checksum: Option<Checksum>,
    /// Human-readable task label reported in progress events.
    pub label: String,
}

/// A batch of download tasks.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DownloadPlan {
    /// Tasks to execute in order.
    pub tasks: Vec<DownloadTask>,
}

/// Returns whether an existing destination file can be reused.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if checksum calculation fails.
pub fn should_skip_existing(task: &DownloadTask) -> Result<bool> {
    if let Ok(meta) = task.destination.metadata() {
        if meta.is_file() && meta.len() > 0 {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Size of each chunk read from the response body before it's written to
/// disk and reported as a `Msg::Bytes` progress update. Small enough that
/// even modest files report a handful of updates (so the bar/ETA/speed
/// stay live instead of jumping straight from 0% to 100% on file
/// completion), large enough that it doesn't turn a fast connection into a
/// flood of channel messages.
const CHUNK_SIZE: usize = 64 * 1024;

/// Download one task to disk, verifying its checksum if it has one.
/// Streams the response in [`CHUNK_SIZE`] chunks and sends a `Msg::Bytes`
/// update after each one, so the caller's progress reporter sees real,
/// live byte-level progress for every file — not just "started"/"finished"
/// with nothing in between. Doesn't report `TaskStarted`/`TaskFinished`
/// itself — the caller does that around this call.
fn fetch_task(client: &reqwest::blocking::Client, url: &str, task: &DownloadTask, tx: &Sender<Msg>) -> Result<()> {
    if let Some(parent) = task.destination.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut response = client.get(url).send()?.error_for_status()?;
    let total = response.content_length();
    let mut file = File::create(&task.destination)?;

    let mut buf = [0u8; CHUNK_SIZE];
    let mut received: u64 = 0;
    loop {
        let n = response.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        received += n as u64;
        let _ = tx.send(Msg::Bytes {
            label: task.label.clone(),
            received,
            total,
        });
    }

    if let Some(Checksum::Sha1(expected)) = &task.checksum {
        let actual = sha1_file(&task.destination)?;
        if actual != *expected {
            return Err(LauncherError::ChecksumMismatch {
                path: task.destination.clone(),
                expected: expected.clone(),
                actual,
            });
        }
    }
    Ok(())
}

/// Retries a single file this many times on transient network errors before
/// giving up on it. Keeps one flaky connection from failing an otherwise
/// healthy install.
const MAX_TASK_ATTEMPTS: u32 = 5;

fn looks_transient(err: &LauncherError) -> bool {
    let msg = err.to_string();
    msg.contains("timed out")
        || msg.contains("timeout")
        || msg.contains("connection")
        || msg.contains("error sending request")
        || msg.contains("dns")
        || msg.contains("reset")
}

/// Returns whether an error is an HTTP 404, worth trying the next mirror
/// for rather than retrying the same URL.
fn looks_not_found(err: &LauncherError) -> bool {
    err.to_string().contains("404")
}

/// Returns whether an error is a checksum mismatch — almost always a
/// corrupted/truncated transfer (a flaky mirror, a proxy that mangled the
/// response, a connection that dropped mid-write) rather than a permanent
/// problem with the file itself. Worth a clean re-download on the same URL
/// before giving up on it.
fn looks_checksum_mismatch(err: &LauncherError) -> bool {
    matches!(err, LauncherError::ChecksumMismatch { .. })
}

fn fetch_task_with_retry(client: &reqwest::blocking::Client, task: &DownloadTask, tx: &Sender<Msg>) -> Result<()> {
    // Try the primary URL, then each configured mirror in turn — a mirror
    // is only worth trying on a 404 (wrong repo for this coordinate), not
    // on every transient error, since each mirror already gets its own
    // full transient-error retry budget below.
    let mut last_err = None;
    for url in std::iter::once(task.url.as_str()).chain(task.fallback_urls.iter().map(String::as_str)) {
        let mut attempt = 0;
        loop {
            attempt += 1;
            match fetch_task(client, url, task, tx) {
                Ok(()) => return Ok(()),
                // Transient errors (timeouts, dropped connections, DNS blips)
                // get the full retry budget on this same URL.
                Err(e) if attempt < MAX_TASK_ATTEMPTS && looks_transient(&e) => {
                    std::thread::sleep(std::time::Duration::from_millis(300 * attempt as u64));
                    continue;
                }
                // A checksum mismatch means the bytes we got don't match
                // what the file is supposed to be. The bad copy is already
                // on disk at this point (fetch_task wrote it before
                // hashing) — remove it so a stale corrupt file never lingers
                // if every retry below also happens to fail, then try a
                // fresh download on the same URL. Most mismatches are a one-
                // off transfer glitch and succeed on the very next attempt.
                // (`last_err` isn't set on this retrying branch — it gets
                // overwritten by the next attempt's outcome either way, and
                // only the final exhausted-retries branch below needs it.)
                Err(e) if attempt < MAX_TASK_ATTEMPTS && looks_checksum_mismatch(&e) => {
                    let _ = fs::remove_file(&task.destination);
                    std::thread::sleep(std::time::Duration::from_millis(300 * attempt as u64));
                    continue;
                }
                Err(e) if looks_checksum_mismatch(&e) => {
                    let _ = fs::remove_file(&task.destination);
                    last_err = Some(e);
                    break;
                }
                // 404s are usually permanent (wrong coordinate/version), but
                // some hosts (e.g. Maven mirrors) briefly 404 while an
                // upload propagates, so give this URL a few tries too
                // before moving on to the next mirror.
                Err(e) if attempt < MAX_TASK_ATTEMPTS && looks_not_found(&e) => {
                    std::thread::sleep(std::time::Duration::from_millis(300 * attempt as u64));
                    continue;
                }
                Err(e) if looks_not_found(&e) => {
                    last_err = Some(e);
                    break;
                }
                Err(e) => return Err(e),
            }
        }
    }
    Err(last_err.expect("at least one URL was tried"))
}

/// Every worker reports through this channel so the caller's `reporter` is
/// only ever touched from the caller's own thread, even though transfers
/// themselves happen on worker threads.
enum Msg {
    Started { label: String, path: PathBuf },
    Bytes { label: String, received: u64, total: Option<u64> },
    Finished { label: String },
    Failed(LauncherError),
}

/// Executes a download plan with a continuous worker pool of up to
/// [`MAX_CONCURRENCY`] downloads in flight at once.
///
/// Existing files with matching checksums are skipped up front. The
/// remaining tasks are placed in a shared queue; each worker thread pulls
/// the next task the moment it finishes its current one — there is no
/// batch boundary, so a single slow or stalled file never idles the rest
/// of the pool while it finishes. Individual files get a few retries on
/// transient network errors before the whole plan is considered failed.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] for network, filesystem, or checksum
/// failures.
pub fn execute_plan(plan: &DownloadPlan, reporter: &mut dyn ProgressReporter) -> Result<()> {
    let mut pending: Vec<&DownloadTask> = Vec::with_capacity(plan.tasks.len());
    for task in &plan.tasks {
        if should_skip_existing(task)? {
            reporter.report(ProgressEvent::TaskSkipped {
                label: task.label.clone(),
                reason: if task.checksum.is_some() {
                    SkipReason::ChecksumMatched
                } else {
                    SkipReason::FileExistsWithoutChecksum
                },
            });
        } else {
            pending.push(task);
        }
    }

    if pending.is_empty() {
        return Ok(());
    }

    let client = super::http::client()?;
    let worker_count = MAX_CONCURRENCY.min(pending.len());
    let next_index = AtomicUsize::new(0);

    let (tx, rx) = std::sync::mpsc::channel::<Msg>();

    std::thread::scope(|scope| {
        let client = &client;
        let pending = &pending;
        let next_index = &next_index;

        for _ in 0..worker_count {
            let tx = tx.clone();
            scope.spawn(move || loop {
                // Continuous work-stealing: grab the next unclaimed index
                // rather than waiting on a fixed-size batch, so a slow file
                // in one "slot" never blocks other workers from moving on.
                let i = next_index.fetch_add(1, Ordering::Relaxed);
                let Some(task) = pending.get(i) else {
                    break;
                };

                let _ = tx.send(Msg::Started {
                    label: task.label.clone(),
                    path: task.destination.clone(),
                });
                match fetch_task_with_retry(client, task, &tx) {
                    Ok(()) => {
                        let _ = tx.send(Msg::Finished {
                            label: task.label.clone(),
                        });
                    }
                    Err(e) => {
                        let _ = tx.send(Msg::Failed(e));
                        // Keep pulling other files even after one fails —
                        // we still want to surface every real error, but no
                        // reason to stall files that would otherwise succeed.
                    }
                }
            });
        }
        // Drop our own sender so `rx` closes once every worker (each
        // holding a clone) has exited.
        drop(tx);

        let mut first_error = None;
        for msg in rx {
            match msg {
                Msg::Started { label, path } => {
                    reporter.report(ProgressEvent::TaskStarted { label, path })
                }
                Msg::Bytes { label, received, total } => {
                    reporter.report(ProgressEvent::BytesReceived { label, received, total })
                }
                Msg::Finished { label } => reporter.report(ProgressEvent::TaskFinished { label }),
                Msg::Failed(e) => {
                    if first_error.is_none() {
                        first_error = Some(e);
                    }
                }
            }
        }
        match first_error {
            Some(e) => Err(e),
            None => Ok(()),
        }
    })
}
