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
/// Tuned for downloading many small-to-medium files concurrently, but also
/// used for large mod/modpack files that can take well over a minute to
/// transfer:
/// - A connect timeout so a single unreachable/black-holed host fails fast
///   instead of hanging a worker (and, with the old batch downloader, the
///   whole batch) indefinitely.
/// - Deliberately *no* overall per-request timeout. reqwest's `.timeout()`
///   caps the *entire* request lifetime (connect + full body transfer), not
///   just idle/stalled time — so a large file that is still actively
///   downloading, just slowly (big mods, big modpack updates, a slow
///   connection), would get aborted the instant it crossed that deadline
///   even though bytes were still arriving. That previously showed up as
///   big files reliably failing to install while small ones worked fine.
///   The connect timeout below plus the caller's chunk-level retry/backoff
///   logic (`net::download`) is what protects against truly stalled
///   connections instead.
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
        .pool_max_idle_per_host(32)
        .pool_idle_timeout(Duration::from_secs(30))
        // Deliberately *not* pinning `local_address` to IPv4 here anymore.
        // reqwest/hyper (0.12+) already implements Happy Eyeballs (RFC
        // 8305): when a host resolves to both IPv4 and IPv6 addresses it
        // races connection attempts across both families and uses
        // whichever answers first, with a short delay before it even
        // starts the second race — so a dead/blackholed IPv6 route no
        // longer stalls the request. Forcing IPv4 here used to *break*
        // IPv6-only networks outright (some mobile carriers, some ISPs,
        // CGNAT-only setups with no usable public IPv4 route at all), so
        // it did more harm than the stall it was meant to avoid. Letting
        // the client pick whichever family actually works is what makes
        // this function work on any network.
        .build()?)
}

/// Number of attempts made for a single metadata fetch before giving up.
/// Mirrors the retry budget used by the file downloader
/// ([`crate::net::download`]) so a single dropped connection to a metadata
/// host (e.g. `piston-meta.mojang.com`) doesn't fail the whole install.
const MAX_FETCH_ATTEMPTS: u32 = 5;

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
