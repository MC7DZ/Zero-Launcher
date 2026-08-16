//! Blocking HTTP helpers with the crate user agent.

use std::time::Duration;

use reqwest::blocking::Client;

use crate::Result;

/// Returns the user agent used by crate-managed HTTP requests.
pub fn user_agent() -> String {
    format!("mc-launcher-core/{}", env!("CARGO_PKG_VERSION"))
}

/// Builds a blocking reqwest client.
///
/// Tuned for downloading many small-to-medium files concurrently:
/// - A connect timeout so a single unreachable/black-holed host fails fast
///   instead of hanging a worker (and, with the old batch downloader, the
///   whole batch) indefinitely.
/// - A per-request timeout as a backstop against a connection that stalls
///   mid-transfer.
/// - A pool of idle keep-alive connections per host sized for our worker
///   pool, so workers reuse TCP/TLS connections instead of renegotiating a
///   fresh handshake for every single file.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the client cannot be constructed.
pub fn client() -> Result<Client> {
    Ok(Client::builder()
        .user_agent(user_agent())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .pool_max_idle_per_host(32)
        .pool_idle_timeout(Duration::from_secs(30))
        .build()?)
}

/// Number of attempts made for a single metadata fetch before giving up.
/// Mirrors the retry budget used by the file downloader
/// ([`crate::net::download`]) so a single dropped connection to a metadata
/// host (e.g. `piston-meta.mojang.com`) doesn't fail the whole install.
const MAX_FETCH_ATTEMPTS: u32 = 3;

/// Returns whether an error looks like a transient network failure worth
/// retrying, rather than a real HTTP/decode error.
fn looks_transient(err: &crate::LauncherError) -> bool {
    let msg = err.to_string();
    msg.contains("timed out")
        || msg.contains("timeout")
        || msg.contains("connection")
        || msg.contains("error sending request")
        || msg.contains("dns")
        || msg.contains("reset")
}

/// Runs `f` up to [`MAX_FETCH_ATTEMPTS`] times, with a short backoff between
/// attempts, retrying only on errors that look transient.
fn with_retry<T>(mut f: impl FnMut() -> Result<T>) -> Result<T> {
    let mut attempt = 0;
    loop {
        attempt += 1;
        match f() {
            Ok(v) => return Ok(v),
            Err(e) if attempt < MAX_FETCH_ATTEMPTS && looks_transient(&e) => {
                std::thread::sleep(Duration::from_millis(300 * attempt as u64));
                continue;
            }
            Err(e) => return Err(e),
        }
    }
}

/// Fetches a URL as text.
///
/// Retries a few times on transient network errors (connect failures,
/// timeouts, resets) before giving up, the same as the file downloader.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the request fails, returns an error
/// status, or the body cannot be decoded as text.
pub fn get_text(url: &str) -> Result<String> {
    with_retry(|| Ok(client()?.get(url).send()?.error_for_status()?.text()?))
}

/// Fetches a URL and decodes the JSON body.
///
/// Retries a few times on transient network errors (connect failures,
/// timeouts, resets) before giving up, the same as the file downloader.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the request fails, returns an error
/// status, or the body cannot be decoded as `T`.
pub fn get_json<T>(url: &str) -> Result<T>
where
    T: serde::de::DeserializeOwned,
{
    with_retry(|| Ok(client()?.get(url).send()?.error_for_status()?.json()?))
}
