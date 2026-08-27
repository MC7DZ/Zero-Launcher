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
        // Force outbound connections to bind from an IPv4 local address.
        // On a machine where IPv6 is "up" but not actually routable (no
        // working default route, blackholed by a router/VPN, etc.), a
        // plain client tries the IPv6 address(es) a host resolves to
        // first and, depending on OS/network stack behavior, that attempt
        // can take a long time to fail instead of erroring out instantly
        // — stalling every download/verify call on it before falling
        // back to IPv4. Binding the local address to IPv4 makes any IPv6
        // candidate address fail immediately (address-family mismatch,
        // not a timeout), so the client moves straight to the working
        // IPv4 address with no delay. Harmless on machines with working
        // IPv6, since IPv4 connectivity is required either way.
        .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
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
