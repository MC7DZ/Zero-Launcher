//! Shared IPv4/IPv6 handling for every outbound HTTP request the launcher
//! makes (update checks/downloads, Discover/mod downloads, skin uploads,
//! Java downloads, Microsoft sign-in helper calls, etc.).
//!
//! Backs the Settings → Network → "IP Protocol" option:
//! - `"automatic"` (default): try IPv4 first; if that doesn't connect
//!   within the timeout, fall back to IPv6. Covers both people on
//!   IPv4-only networks and people whose only IPv4 route is broken/absent
//!   (some VPNs, some misconfigured dual-stack setups) without ever
//!   requiring them to touch a setting.
//! - `"ipv4"`: only ever try IPv4. For networks/firewalls where IPv6
//!   traffic is actively blackholed rather than just absent, so it
//!   doesn't need to wait out a connect timeout on every request.
//! - `"ipv6"`: only ever try IPv6. For IPv6-only networks (some mobile
//!   carriers, some ISPs, CGNAT setups with no usable public IPv4 route)
//!   where trying IPv4 first would just be a guaranteed-to-fail first
//!   attempt on every request.
//!
//! This intentionally does NOT rely on reqwest/hyper's built-in Happy
//! Eyeballs alone, because that races both families with no user-visible
//! knob — someone who already knows their IPv6 is dead (or their IPv4 is)
//! has no way to skip the doomed attempt. Pinning `local_address` per
//! family here gives that control back, while `"automatic"`'s
//! try-v4-then-v6 fallback keeps things working with zero configuration
//! for everyone else.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::time::Duration;

use tauri::AppHandle;

use crate::logger;
use crate::state::AppState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkMode {
    Automatic,
    Ipv4Only,
    Ipv6Only,
}

impl NetworkMode {
    pub fn from_setting(s: &str) -> Self {
        match s {
            "ipv4" => NetworkMode::Ipv4Only,
            "ipv6" => NetworkMode::Ipv6Only,
            // Unknown/empty values (older settings.json, hand-edited file)
            // fall back to the safe default rather than erroring.
            _ => NetworkMode::Automatic,
        }
    }

    fn label(self) -> &'static str {
        match self {
            NetworkMode::Automatic => "automatic (IPv4 first, then IPv6)",
            NetworkMode::Ipv4Only => "IPv4-only",
            NetworkMode::Ipv6Only => "IPv6-only",
        }
    }
}

/// Reads the user's current Network setting out of `AppState`. Never
/// fails — a poisoned settings lock or missing value just means
/// "automatic", the same as a fresh install.
pub fn current_mode(state: &AppState) -> NetworkMode {
    state
        .settings
        .lock()
        .map(|s| NetworkMode::from_setting(&s.network_mode))
        .unwrap_or(NetworkMode::Automatic)
}

fn client_pinned_to(family: Option<IpAddr>, connect_timeout: Duration) -> reqwest::Client {
    let mut builder = reqwest::Client::builder().connect_timeout(connect_timeout);
    if let Some(addr) = family {
        builder = builder.local_address(addr);
    }
    builder.build().unwrap_or_else(|_| reqwest::Client::new())
}

/// Number of attempts made per address family before giving up on it (or,
/// for `"automatic"`, before moving on to the other family).
const ATTEMPTS_PER_FAMILY: u32 = 2;

/// Sends a request built by `build`, honoring the user's Network setting,
/// and logs enough detail at every step (mode, which family is being
/// tried, DNS vs. connect vs. timeout vs. TLS vs. HTTP-status failures,
/// and the full underlying error chain) that a "couldn't reach the
/// update/mod/etc. server" failure is diagnosable straight from
/// `latest.log` without having to reproduce it.
///
/// `what` is a short human label for the log lines (e.g. `"update
/// manifest fetch"`, `"update download"`).
pub async fn send(
    app: &AppHandle,
    state: &AppState,
    what: &str,
    build: impl Fn(&reqwest::Client) -> reqwest::RequestBuilder,
) -> Result<reqwest::Response, String> {
    let mode = current_mode(state);
    let connect_timeout = Duration::from_secs(10);
    logger::info(
        app,
        state,
        "Network",
        &format!("{what}: starting (Network setting: {})", mode.label()),
    );

    let families: Vec<(&str, Option<IpAddr>)> = match mode {
        NetworkMode::Automatic => vec![
            ("IPv4", Some(IpAddr::V4(Ipv4Addr::UNSPECIFIED))),
            ("IPv6", Some(IpAddr::V6(Ipv6Addr::UNSPECIFIED))),
        ],
        NetworkMode::Ipv4Only => vec![("IPv4", Some(IpAddr::V4(Ipv4Addr::UNSPECIFIED)))],
        NetworkMode::Ipv6Only => vec![("IPv6", Some(IpAddr::V6(Ipv6Addr::UNSPECIFIED)))],
    };

    let mut last_err: Option<String> = None;

    for (family_name, family_addr) in &families {
        let client = client_pinned_to(*family_addr, connect_timeout);
        for attempt in 1..=ATTEMPTS_PER_FAMILY {
            logger::info(
                app,
                state,
                "Network",
                &format!("{what}: trying {family_name}, attempt {attempt}/{ATTEMPTS_PER_FAMILY}"),
            );
            match build(&client).send().await {
                Ok(resp) => {
                    logger::info(
                        app,
                        state,
                        "Network",
                        &format!(
                            "{what}: connected over {family_name} (attempt {attempt}) — HTTP {}",
                            resp.status()
                        ),
                    );
                    return Ok(resp);
                }
                Err(e) => {
                    // Build the fullest possible picture of *why* this
                    // failed: reqwest's Display often just says "error
                    // sending request", so walk the std::error::Error
                    // source chain (this is where the real DNS-resolver /
                    // TLS / OS-socket error text lives) and classify the
                    // failure kind reqwest itself detected.
                    let kind = if e.is_timeout() {
                        "timed out"
                    } else if e.is_connect() {
                        "connect error"
                    } else if e.is_request() {
                        "request build error"
                    } else {
                        "error"
                    };
                    let mut chain = vec![e.to_string()];
                    let mut src: Option<&(dyn std::error::Error + 'static)> =
                        std::error::Error::source(&e);
                    while let Some(s) = src {
                        chain.push(s.to_string());
                        src = s.source();
                    }
                    let detail = chain.join(" ← caused by: ");

                    logger::warn(
                        app,
                        state,
                        "Network",
                        &format!(
                            "{what}: {family_name} attempt {attempt}/{ATTEMPTS_PER_FAMILY} failed ({kind}): {detail}"
                        ),
                    );
                    last_err = Some(format!("{family_name} {kind}: {detail}"));

                    let is_transient = e.is_connect() || e.is_timeout();
                    if is_transient && attempt < ATTEMPTS_PER_FAMILY {
                        tokio::time::sleep(Duration::from_millis(400 * attempt as u64)).await;
                        continue;
                    }
                    // Not transient (e.g. a request-build error) — no
                    // point retrying this family or trying the other one.
                    if !is_transient {
                        break;
                    }
                    // Exhausted retries for this family; move to the next
                    // one (automatic mode) or fall through to the final
                    // error (single-family mode).
                    break;
                }
            }
        }
    }

    let mode_note = match mode {
        NetworkMode::Automatic => " (tried IPv4, then IPv6 — see above for both)".to_string(),
        NetworkMode::Ipv4Only => " (Network setting is pinned to IPv4-only in Settings → Network — try Automatic or IPv6 if this network doesn't have IPv4)".to_string(),
        NetworkMode::Ipv6Only => " (Network setting is pinned to IPv6-only in Settings → Network — try Automatic or IPv4 if this network doesn't have IPv6)".to_string(),
    };
    let final_msg = format!(
        "{what}: unable to reach the server after trying every configured protocol{mode_note}. Last error: {}",
        last_err.unwrap_or_else(|| "unknown error".to_string())
    );
    logger::error(app, state, "Network", &final_msg);
    Err(final_msg)
}
