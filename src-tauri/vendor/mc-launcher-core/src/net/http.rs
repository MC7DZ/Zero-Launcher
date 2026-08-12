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

/// Fetches a URL as text.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the request fails, returns an error
/// status, or the body cannot be decoded as text.
pub fn get_text(url: &str) -> Result<String> {
    Ok(client()?.get(url).send()?.error_for_status()?.text()?)
}

/// Fetches a URL and decodes the JSON body.
///
/// # Errors
///
/// Returns [`crate::LauncherError`] if the request fails, returns an error
/// status, or the body cannot be decoded as `T`.
pub fn get_json<T>(url: &str) -> Result<T>
where
    T: serde::de::DeserializeOwned,
{
    Ok(client()?.get(url).send()?.error_for_status()?.json()?)
}
