//! Download plans and execution.

use std::{
    fs::{self, File},
    io,
    path::PathBuf,
};

use crate::{
    io::hash::sha1_file,
    progress::{ProgressEvent, ProgressReporter, SkipReason},
    LauncherError, Result,
};

/// How many files to download at once. Once a batch of this many finishes,
/// the next batch of this many starts — files are no longer fetched one at
/// a time.
const BATCH_SIZE: usize = 10;

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
    if !task.destination.is_file() {
        return Ok(false);
    }

    match &task.checksum {
        Some(Checksum::Sha1(expected)) => Ok(sha1_file(&task.destination)? == *expected),
        Some(Checksum::Sha256(_)) => Ok(false),
        None => Ok(true),
    }
}

/// Download one task to disk, verifying its checksum if it has one. Doesn't
/// touch the reporter — the caller reports `TaskStarted`/`TaskFinished`.
fn fetch_task(client: &reqwest::blocking::Client, task: &DownloadTask) -> Result<()> {
    if let Some(parent) = task.destination.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut response = client.get(&task.url).send()?.error_for_status()?;
    let mut file = File::create(&task.destination)?;
    io::copy(&mut response, &mut file)?;

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

/// Executes a download plan in batches of [`BATCH_SIZE`].
///
/// Existing files with matching checksums are skipped up front. The
/// remaining tasks are grouped into chunks of `BATCH_SIZE`; each chunk is
/// downloaded with one thread per file, and the next chunk only starts once
/// every file in the current one has finished (or failed).
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

    // Every batch reports through this channel so `reporter` is only ever
    // touched from this (the caller's) thread, even though the actual
    // transfers happen on worker threads.
    enum Msg {
        Started { label: String, path: PathBuf },
        Finished { label: String },
        Failed(LauncherError),
    }

    for chunk in pending.chunks(BATCH_SIZE) {
        let (tx, rx) = std::sync::mpsc::channel::<Msg>();

        std::thread::scope(|scope| {
            let client = &client;
            for task in chunk {
                let tx = tx.clone();
                scope.spawn(move || {
                    let _ = tx.send(Msg::Started {
                        label: task.label.clone(),
                        path: task.destination.clone(),
                    });
                    match fetch_task(client, task) {
                        Ok(()) => {
                            let _ = tx.send(Msg::Finished {
                                label: task.label.clone(),
                            });
                        }
                        Err(e) => {
                            let _ = tx.send(Msg::Failed(e));
                        }
                    }
                });
            }
            // Drop our own sender so `rx` closes once this batch's workers
            // (each holding a clone) are all done.
            drop(tx);

            let mut first_error = None;
            for msg in rx {
                match msg {
                    Msg::Started { label, path } => {
                        reporter.report(ProgressEvent::TaskStarted { label, path })
                    }
                    Msg::Finished { label } => {
                        reporter.report(ProgressEvent::TaskFinished { label })
                    }
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
        })?;
        // Next iteration only starts now that every file in this batch of
        // up to BATCH_SIZE has installed.
    }
    Ok(())
}
