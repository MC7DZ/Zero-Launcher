//! Microsoft account login helpers.
//!
//! The flow is split into small steps so desktop launchers can control browser
//! handling, redirect capture, token storage, and refresh scheduling. Use
//! [`get_secure_login_data`] for PKCE-enabled sign-in, then pass the returned
//! verifier to [`complete_login`] after the redirect URL yields an auth code.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::{distr::Alphanumeric, Rng};
use reqwest::blocking::Client;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use url::Url;

use crate::{
    types::microsoft_types::{
        AuthorizationTokenResponse, CompleteLoginResponse, MinecraftAuthenticateResponse,
        MinecraftProfileResponse, MinecraftStoreResponse, XBLResponse, XSTSResponse,
    },
    utils::helper::get_user_agent,
};

const AUTH_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const DEVICE_CODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const SCOPE: &str = "XboxLive.signin offline_access";

/// Turns a `reqwest::Error` from a failed *send* (i.e. the request never got
/// a response at all — DNS, TLS/certificate, connect, or timeout failure)
/// into a message that actually says which of those it was, instead of
/// reqwest's generic top-level "error sending request for url (...)" that
/// hides the real cause in a `source()` chain nothing was printing.
fn describe_send_error(e: reqwest::Error) -> Box<dyn std::error::Error> {
    let mut parts = vec![e.to_string()];
    let mut source = std::error::Error::source(&e);
    while let Some(s) = source {
        parts.push(s.to_string());
        source = s.source();
    }
    // Cheap heuristics on the assembled chain so the message is actionable
    // without the user having to interpret raw TLS/DNS internals themselves.
    let joined = parts.join(" -> ");
    let hint = if joined.contains("dns error") || joined.contains("failed to lookup address") {
        "\n\nThis looks like a DNS resolution failure — check your internet connection, VPN, or DNS settings."
    } else if joined.contains("certificate") || joined.contains("InvalidCertificate") || joined.contains("UnknownIssuer") {
        "\n\nThis looks like a TLS certificate validation failure — often caused by an antivirus/firewall that intercepts HTTPS traffic (SSL/TLS scanning), a corporate proxy, or your system clock being wrong. Try disabling HTTPS scanning in your antivirus, or check that your system date/time is correct."
    } else if joined.contains("timed out") || joined.contains("timeout") {
        "\n\nThe request timed out — check your internet connection, or whether a firewall/VPN is blocking login.microsoftonline.com."
    } else if joined.contains("Connection refused") || joined.contains("connect error") {
        "\n\nThe connection was refused/blocked — check your firewall, proxy, or VPN settings for login.microsoftonline.com."
    } else {
        ""
    };
    format!("Could not reach Microsoft's sign-in servers: {joined}{hint}").into()
}

/// Builds the blocking HTTP client used for every Microsoft/Xbox/Minecraft
/// services call in this module. Binds to an IPv4-only local address so
/// that machines with a broken/absent IPv6 route (common on some home and
/// mobile networks) don't waste the connection attempt on an unreachable
/// IPv6 address before falling back — some environments' IPv6-then-IPv4
/// fallback handling is less reliable than tools like `curl`, and forcing
/// IPv4 here sidesteps that class of failure entirely. Falls back to a
/// plain default client if, for whatever reason, building the IPv4-bound
/// one fails.
fn http_client() -> Client {
    Client::builder()
        .connect_timeout(std::time::Duration::from_secs(3))
        .timeout(std::time::Duration::from_secs(6))
        .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
        .build()
        .unwrap_or_else(|_| {
            Client::builder()
                .connect_timeout(std::time::Duration::from_secs(3))
                .timeout(std::time::Duration::from_secs(6))
                .build()
                .unwrap_or_else(|_| Client::new())
        })
}

/// Response from starting a device-code sign-in — the code and URL to show
/// the user, plus polling parameters.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DeviceCodeStart {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
    pub message: String,
}

/// Outcome of polling a device-code sign-in.
pub enum DeviceCodePoll {
    /// User hasn't finished signing in yet — caller should wait `interval`
    /// seconds and poll again.
    Pending,
    /// Signed in successfully.
    Success(AuthorizationTokenResponse),
}

/// Starts a device-code sign-in: Microsoft returns a short code plus a URL
/// (typically microsoft.com/link) for the user to enter it on any browser
/// or device — no embedded webview needed. Poll with
/// [`poll_device_code_token`] using the returned `device_code` until the
/// user finishes.
///
/// # Errors
///
/// Returns an error if the HTTP request fails or Microsoft rejects the
/// request (e.g. bad client ID).
pub fn start_device_code(client_id: &str) -> Result<DeviceCodeStart, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("scope", SCOPE);

    let client = http_client();
    let res = client
        .post(DEVICE_CODE_URL)
        .form(&parameters)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("user-agent", get_user_agent())
        .send().map_err(describe_send_error)?;

    let body = res.text()?;

    if let Ok(start) = serde_json::from_str::<DeviceCodeStart>(&body) {
        return Ok(start);
    }

    if let Some(desc) = extract_oauth_error(&body) {
        return Err(format!("Could not start device sign-in: {desc}").into());
    }

    Err(format!("Microsoft returned an unexpected response: {body}").into())
}

/// Polls Microsoft once to check whether the user has completed a
/// device-code sign-in started with [`start_device_code`]. Call this on a
/// timer at the `interval` returned by that function until it returns
/// [`DeviceCodePoll::Success`] or an error (e.g. expired or denied).
///
/// # Errors
///
/// Returns an error if the HTTP request fails, the code expired, the user
/// declined, or Microsoft otherwise rejects the request. `authorization_pending`
/// (the normal "still waiting" case) is returned as `Ok(DeviceCodePoll::Pending)`,
/// not an error.
pub fn poll_device_code_token(
    client_id: &str,
    device_code: &str,
) -> Result<DeviceCodePoll, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    parameters.insert("device_code", device_code);

    let client = http_client();
    let res = client
        .post(TOKEN_URL)
        .form(&parameters)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("user-agent", get_user_agent())
        .send().map_err(describe_send_error)?;

    let body = res.text()?;

    if let Ok(token_response) = serde_json::from_str::<AuthorizationTokenResponse>(&body) {
        return Ok(DeviceCodePoll::Success(token_response));
    }

    #[derive(serde::Deserialize)]
    struct OAuthError {
        error: Option<String>,
        error_description: Option<String>,
    }

    if let Ok(err) = serde_json::from_str::<OAuthError>(&body) {
        match err.error.as_deref() {
            Some("authorization_pending") | Some("slow_down") => return Ok(DeviceCodePoll::Pending),
            Some("expired_token") => {
                return Err("The device code expired before you finished signing in — start over.".into())
            }
            Some("authorization_declined") => {
                return Err("Sign-in was declined.".into())
            }
            _ => {
                let code = err.error.unwrap_or_default();
                let desc = err.error_description.unwrap_or_default();
                let first_line = desc.lines().next().unwrap_or(&desc);
                return Err(format!("Microsoft sign-in failed ({code}): {first_line}").into());
            }
        }
    }

    Err(format!("Microsoft returned an unexpected response: {body}").into())
}

fn extract_oauth_error(body: &str) -> Option<String> {
    #[derive(serde::Deserialize)]
    struct OAuthError {
        error: Option<String>,
        error_description: Option<String>,
    }
    let err: OAuthError = serde_json::from_str(body).ok()?;
    let code = err.error?;
    let desc = err.error_description.unwrap_or_default();
    let first_line = desc.lines().next().unwrap_or(&desc);
    Some(format!("{code}: {first_line}"))
}


/// Builds a Microsoft OAuth login URL without PKCE.
///
/// New applications should prefer [`get_secure_login_data`].
pub fn get_login_url(client_id: &str, redirect_uri: &str) -> String {
    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("response_type", "code");
    parameters.insert("redirect_uri", redirect_uri);
    parameters.insert("response_mode", "query");
    parameters.insert("scope", SCOPE);

    let url = Url::parse(AUTH_URL).expect("Invalid AUTH_URL");
    let url_with_query = url
        .join(&("?".to_owned() + &serde_urlencoded::to_string(parameters).unwrap()))
        .expect("Failed to build URL");

    url_with_query.to_string()
}

fn generate_pkce_data() -> (String, String, String) {
    let mut rng = rand::rng();
    let chars: Vec<char> = (0..128)
        .map(|_| match rng.random_range(0..64) {
            0 => '-',
            1 => '_',
            _ => rng.sample(Alphanumeric) as char,
        })
        .collect();
    let code_verifier: String = chars.iter().collect();

    let digest = Sha256::digest(code_verifier.as_bytes());
    let code_challenge = URL_SAFE_NO_PAD.encode(digest);
    code_challenge.trim_end_matches('=').to_string();
    let code_challenge_method = "S256".to_string();

    (code_verifier, code_challenge, code_challenge_method)
}

/// Generates a random OAuth state token.
pub fn generate_state() -> String {
    let mut rng = rand::rng();
    let chars: Vec<char> = (0..16)
        .map(|_| match rng.random_range(0..64) {
            0 => '-',
            1 => '_',
            _ => rng.sample(Alphanumeric) as char,
        })
        .collect();
    let state: String = chars.iter().collect();
    state
}

/// Builds a PKCE-enabled login URL, state token, and code verifier.
///
/// The returned tuple is `(login_url, state, code_verifier)`. Store the verifier
/// until the redirect is received, then pass it to [`complete_login`].
pub fn get_secure_login_data(
    client_id: &str,
    redirect_uri: &str,
    state: Option<&str>,
) -> (String, String, String) {
    let (code_verifier, code_challenge, code_challenge_method) = generate_pkce_data();

    let state = match state {
        Some(s) => s.to_string(),
        None => generate_state(),
    };

    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("response_type", "code");
    parameters.insert("redirect_uri", redirect_uri);
    parameters.insert("response_mode", "query");
    parameters.insert("scope", SCOPE);
    parameters.insert("state", &state);
    parameters.insert("code_challenge", &code_challenge);
    parameters.insert("code_challenge_method", &code_challenge_method);
    let url = Url::parse(AUTH_URL).expect("Invalid AUTH_URL");
    let login_url = url
        .join(&("?".to_owned() + &serde_urlencoded::to_string(parameters).unwrap()))
        .expect("Failed to build URL");
    (login_url.to_string(), state, code_verifier)
}

/// Returns true when a redirect URL contains a `code` query parameter.
pub fn url_contains_auth_code(url: &str) -> bool {
    if let Ok(parsed) = Url::parse(url) {
        if let Some(qs) = parsed.query() {
            let query_pairs: Vec<_> = qs.split('&').collect();
            for pair in query_pairs {
                let parts: Vec<_> = pair.split('=').collect();
                if let [key, _] = parts[..] {
                    if key == "code" {
                        return true;
                    }
                }
            }
        }
    }
    false
}

/// Extracts the raw `code` query parameter from a redirect URL.
pub fn get_auth_code_from_url(url: &str) -> Option<String> {
    if let Ok(parsed) = Url::parse(url) {
        if let Some(qs) = parsed.query() {
            let query_pairs: HashMap<_, _> = qs
                .split('&')
                .filter_map(|s| {
                    let mut split = s.split('=');
                    let key = split.next()?;
                    let value = split.next()?;
                    Some((key, value.to_string()))
                })
                .collect();
            if let Some(code) = query_pairs.get("code") {
                return Some(code.clone());
            }
        }
    }
    None
}

/// Parses a redirect URL and validates the optional state value.
///
/// # Errors
///
/// Returns an error when the URL has no auth code or the state value does not
/// match the expected state.
pub fn parse_auth_code_url(
    url: &str,
    state: Option<String>,
) -> Result<String, Box<dyn std::error::Error>> {
    if let Ok(parsed) = Url::parse(url) {
        if let Some(qs) = parsed.query() {
            let query_pairs: HashMap<_, _> = qs
                .split('&')
                .filter_map(|s| {
                    let mut split = s.split('=');
                    let key = split.next()?;
                    let value = split.next()?;
                    Some((key, value.to_string()))
                })
                .collect();
            if state.is_some() {
                if state != query_pairs.get("state").cloned() {
                    return Err("state not equal.".into());
                }
            }
            if let Some(code) = query_pairs.get("code") {
                return Ok(code.clone());
            }
        }
    }
    Err("parse_auth_code_url error.".into())
}

/// Exchanges an OAuth authorization code for Microsoft access and refresh tokens.
///
/// Pass the PKCE verifier returned by [`get_secure_login_data`] when using the
/// secure login flow.
///
/// # Errors
///
/// Returns an error if the HTTP request fails, or if Microsoft rejects the
/// exchange (expired/used code, wrong client ID, redirect URI mismatch,
/// etc.) — the error message explains why instead of a raw JSON-decode
/// failure.
pub fn get_authorization_token(
    client_id: &str,
    client_secret: Option<&str>,
    redirect_uri: &str,
    auth_code: &str,
    code_verifier: Option<&str>,
) -> Result<AuthorizationTokenResponse, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("scope", SCOPE);
    parameters.insert("code", auth_code);
    parameters.insert("redirect_uri", redirect_uri);
    parameters.insert("grant_type", "authorization_code");

    if let Some(secret) = client_secret {
        parameters.insert("client_secret", secret);
    }

    if let Some(verifier) = code_verifier {
        parameters.insert("code_verifier", verifier);
    }

    let client = http_client();
    let res = client
        .post(TOKEN_URL)
        .form(&parameters)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("user-agent", get_user_agent())
        .send().map_err(describe_send_error)?;

    parse_token_response(res)
}

/// Refreshes Microsoft OAuth tokens using a refresh token.
///
/// # Errors
///
/// Returns an error if the HTTP request fails, or if Microsoft rejects the
/// refresh (expired/revoked token, wrong client ID, etc.) — the error
/// message explains why instead of a raw JSON-decode failure.
pub fn refresh_authorization_token(
    client_id: &str,
    client_secret: Option<&str>,
    refresh_token: &str,
) -> Result<AuthorizationTokenResponse, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert("client_id", client_id);
    parameters.insert("scope", SCOPE);
    parameters.insert("refresh_token", refresh_token);
    parameters.insert("grant_type", "refresh_token");

    if let Some(secret) = client_secret {
        parameters.insert("client_secret", secret);
    }

    let client = http_client();
    let res = client
        .post("https://login.live.com/oauth20_token.srf")
        .form(&parameters)
        .header("user-agent", get_user_agent())
        .send().map_err(describe_send_error)?;

    parse_token_response(res)
}

/// Shared response handling for both the initial code exchange and the
/// refresh-token exchange: reads the body once, tries the success shape,
/// and if that fails, tries to pull out the standard OAuth `error` /
/// `error_description` fields so failures are explainable instead of a
/// raw decode error.
fn parse_token_response(
    res: reqwest::blocking::Response,
) -> Result<AuthorizationTokenResponse, Box<dyn std::error::Error>> {
    let body = res.text()?;

    if let Ok(token_response) = serde_json::from_str::<AuthorizationTokenResponse>(&body) {
        return Ok(token_response);
    }

    #[derive(serde::Deserialize)]
    struct OAuthError {
        error: Option<String>,
        error_description: Option<String>,
    }

    if let Ok(err) = serde_json::from_str::<OAuthError>(&body) {
        if err.error.is_some() || err.error_description.is_some() {
            let code = err.error.unwrap_or_default();
            let desc = err.error_description.unwrap_or_default();
            // Microsoft's error_description is a long multi-line block with
            // a doc link tacked on — keep just the first line, which has
            // the actual message (e.g. "AADSTS70000: ...").
            let first_line = desc.lines().next().unwrap_or(&desc);
            return Err(format!(
                "Microsoft sign-in failed ({code}): {first_line}"
            )
            .into());
        }
    }

    Err(format!("Microsoft returned an unexpected response: {body}").into())
}

/// Authenticates a Microsoft access token with Xbox Live.
///
/// # Errors
///
/// Returns an error if the Xbox Live request or response decoding fails.
pub fn authenticate_with_xbl(
    access_token: &str,
) -> Result<XBLResponse, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert(
        "Properties",
        json!({
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": format!("d={}", access_token),
        }),
    );
    parameters.insert("RelyingParty", "http://auth.xboxlive.com".into());
    parameters.insert("TokenType", "JWT".into());

    let client = http_client();
    let res = client
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .json(&parameters)
        .header("Content-Type", "application/json")
        .header("user-agent", get_user_agent())
        .header("Accept", "application/json")
        .send().map_err(describe_send_error)?;

    let xbl_response: XBLResponse = res.json()?;
    Ok(xbl_response)
}

/// Exchanges an Xbox Live token for an XSTS token.
///
/// # Errors
///
/// Returns an error if the HTTP request fails, or if Xbox Live rejects the
/// sign-in (e.g. no Xbox profile, needs adult verification, region-banned,
/// or child account not in a family) — in which case the error message
/// explains why instead of a raw JSON-decode failure.
pub fn authenticate_with_xsts(xbl_token: &str) -> Result<XSTSResponse, Box<dyn std::error::Error>> {
    let mut parameters = HashMap::new();
    parameters.insert(
        "Properties",
        json!({
            "SandboxId": "RETAIL",
            "UserTokens": [xbl_token],
        }),
    );
    parameters.insert("RelyingParty", "rp://api.minecraftservices.com/".into());
    parameters.insert("TokenType", "JWT".into());

    let client = http_client();
    let res = client
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .json(&parameters)
        .header("Content-Type", "application/json")
        .header("user-agent", get_user_agent())
        .header("Accept", "application/json")
        .send().map_err(describe_send_error)?;

    let body = res.text()?;

    if let Ok(xsts_response) = serde_json::from_str::<XSTSResponse>(&body) {
        return Ok(xsts_response);
    }

    // Not a success shape — Xbox Live returned an error object instead.
    // Try to pull out the well-known XErr code so we can explain what
    // actually went wrong, rather than surfacing a raw decode error.
    #[derive(serde::Deserialize)]
    struct XstsError {
        #[serde(rename = "XErr")]
        xerr: Option<u64>,
        #[serde(rename = "Message")]
        message: Option<String>,
    }

    if let Ok(err) = serde_json::from_str::<XstsError>(&body) {
        let explanation = match err.xerr {
            Some(2148916233) => {
                "This Microsoft account has no Xbox profile. Sign in once at \
                 https://www.xbox.com to create one, then try again."
            }
            Some(2148916235) => "Xbox Live is not available in this account's region/country.",
            Some(2148916236) | Some(2148916237) => {
                "This account needs adult verification on the Xbox website before it can sign in."
            }
            Some(2148916238) => {
                "This is a child account that isn't part of a Microsoft family. Add it to a \
                 family group at https://account.microsoft.com/family, then try again."
            }
            _ => "",
        };
        let code_str = err
            .xerr
            .map(|c| format!(" (XErr {c})"))
            .unwrap_or_default();
        let msg = err.message.unwrap_or_default();
        return Err(format!(
            "Xbox Live sign-in failed{code_str}.{}{}",
            if explanation.is_empty() { "" } else { " " },
            if explanation.is_empty() { msg.as_str() } else { explanation }
        )
        .into());
    }

    Err(format!("Xbox Live returned an unexpected response: {body}").into())
}


/// Exchanges XSTS identity data for a Minecraft services access token.
///
/// # Errors
///
/// Returns an error if the HTTP request fails, or if Minecraft services
/// rejects/errors the login — in which case the error message explains why
/// instead of a raw JSON-decode failure.
pub fn authenticate_with_minecraft(
    userhash: &str,
    xsts_token: &str,
) -> Result<MinecraftAuthenticateResponse, Box<dyn std::error::Error>> {
    let parameters = json!({
        "identityToken": format!("XBL3.0 x={};{}", userhash, xsts_token),
    });

    let client = http_client();
    let res = client
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .json(&parameters)
        .header("Content-Type", "application/json")
        .header("user-agent", get_user_agent())
        .header("Accept", "application/json")
        .send().map_err(describe_send_error)?;

    let status = res.status();
    let body = res.text()?;

    // On success this is a MinecraftAuthenticateResponse. On failure it's a
    // differently-shaped error object (or, occasionally, an HTML error page
    // from a gateway/CDN in front of the API) — trying to force that into
    // MinecraftAuthenticateResponse is exactly what produced the old
    // "error decoding response body" message instead of a real reason.
    if let Ok(minecraft_response) =
        serde_json::from_str::<MinecraftAuthenticateResponse>(&body)
    {
        return Ok(minecraft_response);
    }

    #[derive(serde::Deserialize)]
    struct MinecraftError {
        #[serde(rename = "errorMessage")]
        error_message: Option<String>,
        error: Option<String>,
    }

    if let Ok(err) = serde_json::from_str::<MinecraftError>(&body) {
        let msg = err
            .error_message
            .or(err.error)
            .unwrap_or_else(|| "no error message given".to_string());
        return Err(format!(
            "Minecraft services login failed ({status}): {msg}"
        )
        .into());
    }

    Err(format!(
        "Minecraft services returned an unexpected {status} response \
         (not valid JSON — likely a temporary outage or a request that \
         was blocked before reaching the API): {}",
        if body.len() > 300 { &body[..300] } else { &body }
    )
    .into())
}

/// Fetches Minecraft store entitlement information for an access token.
///
/// # Errors
///
/// Returns a [`reqwest::Error`] if the HTTP request or response decoding fails.
pub fn get_store_information(access_token: &str) -> Result<MinecraftStoreResponse, reqwest::Error> {
    let client = http_client();
    let res = client
        .get("https://api.minecraftservices.com/entitlements/mcstore")
        .header("Authorization", format!("Bearer {}", access_token))
        .header("user-agent", get_user_agent())
        .send()?;

    let store_response: MinecraftStoreResponse = res.json()?;
    Ok(store_response)
}

/// Fetches the Minecraft profile for an authenticated account.
///
/// # Errors
///
/// Returns an error if the profile request or response decoding fails.
pub fn get_profile(
    access_token: &str,
) -> Result<MinecraftProfileResponse, Box<dyn std::error::Error>> {
    let client = http_client();
    let res = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .header("Authorization", format!("Bearer {}", access_token))
        .header("user-agent", get_user_agent())
        .send().map_err(describe_send_error)?;

    let profile_response: MinecraftProfileResponse = res.json()?;
    Ok(profile_response)
}

/// Completes the full Microsoft-to-Minecraft login flow.
///
/// This exchanges the OAuth code, authenticates with Xbox Live and XSTS, logs in
/// to Minecraft services, and returns profile plus token data.
///
/// # Errors
///
/// Returns an error if any network step fails, the app is not permitted, or the
/// account does not own Minecraft.
pub fn complete_login(
    client_id: &str,
    client_secret: Option<&str>,
    redirect_uri: &str,
    auth_code: &str,
    code_verifier: Option<&str>,
) -> Result<CompleteLoginResponse, Box<dyn std::error::Error>> {
    let token_request = get_authorization_token(
        client_id,
        client_secret,
        redirect_uri,
        auth_code,
        code_verifier,
    )?;
    complete_login_from_token(token_request)
}

/// Finishes the Microsoft-to-Minecraft login flow starting from an already
/// obtained OAuth token response (e.g. from [`poll_device_code_token`]'s
/// `Success` case), rather than an authorization code. Shared by both the
/// popup-webview flow ([`complete_login`]) and the device-code flow.
///
/// # Errors
///
/// Returns an error if any network step fails, the app is not permitted, or
/// the account does not own Minecraft.
pub fn complete_login_from_token(
    token_request: AuthorizationTokenResponse,
) -> Result<CompleteLoginResponse, Box<dyn std::error::Error>> {
    let token = token_request.access_token.clone();

    let xbl_request = authenticate_with_xbl(&token)?;
    let xbl_token = xbl_request.token;
    let userhash = xbl_request.display_claims.xui[0].uhs.clone();

    let xsts_request = authenticate_with_xsts(&xbl_token)?;
    let xsts_token = xsts_request.token;

    let account_request = authenticate_with_minecraft(&userhash, &xsts_token)?;

    if account_request.access_token.is_empty() {
        return Err("Azure App not permitted.".into());
    }

    let access_token = account_request.access_token.clone();

    let profile = get_profile(&access_token)?;

    if profile.error == Some("NOT_FOUND".to_string()) {
        return Err("Account not own minecraft".into());
    }

    Ok(CompleteLoginResponse {
        id: profile.id,
        name: profile.name,
        access_token: account_request.access_token,
        refresh_token: token_request.refresh_token,
        skins: profile.skins,
        capes: profile.capes,
        error: profile.error,
        error_message: profile.error_message,
    })
}

/// Completes the full token refresh flow.
///
/// This refreshes Microsoft OAuth tokens, then repeats Xbox Live, XSTS, and
/// Minecraft services authentication to return fresh launch credentials.
///
/// # Errors
///
/// Returns an error if the refresh token is invalid, any network step fails, or
/// the account does not own Minecraft.
pub fn complete_refresh(
    client_id: &str,
    client_secret: Option<&str>,
    refresh_token: &str,
) -> Result<CompleteLoginResponse, Box<dyn std::error::Error>> {
    let token_request = refresh_authorization_token(client_id, client_secret, refresh_token)?;

    if token_request.error.is_some() {
        return Err("Invalid Refresh Token.".into());
    }

    let token = token_request.access_token;

    let xbl_request = authenticate_with_xbl(&token)?;
    let xbl_token = xbl_request.token;
    let userhash = xbl_request.display_claims.xui[0].uhs.clone();

    let xsts_request = authenticate_with_xsts(&xbl_token)?;
    let xsts_token = xsts_request.token;

    let account_request = authenticate_with_minecraft(&userhash, &xsts_token)?;
    let access_token = account_request.access_token.clone();

    let profile = get_profile(&access_token)?;

    if profile.error == Some("NOT_FOUND".to_string()) {
        return Err("Account not own minecraft".into());
    }

    Ok(CompleteLoginResponse {
        id: profile.id,
        name: profile.name,
        access_token: account_request.access_token,
        refresh_token: token_request.refresh_token,
        skins: profile.skins,
        capes: profile.capes,
        error: profile.error,
        error_message: profile.error_message,
    })
}

#[cfg(test)]
mod test {
    use super::*;

    // test with minecraft-console-client public client_id and redirecr_uri
    const CLIENT_ID: &str = "54473e32-df8f-42e9-a649-9419b0dab9d3";
    const REDIRECT_URI: &str = "https://mccteam.github.io/redirect.html";

    #[test]
    fn debug_get_login_url() {
        dbg!(get_login_url(CLIENT_ID, REDIRECT_URI));
    }

    #[test]
    fn debug_generate_pkce_data() {
        dbg!(generate_pkce_data());
    }

    #[test]
    fn debug_get_secure_login_data() {
        dbg!(get_secure_login_data(CLIENT_ID, REDIRECT_URI, None));
    }

    #[test]
    fn test_code_challenge() {
        let code_verifier: String = "7BSNrJnbWnVrx9Y3uoBEJmrd0eii9ZBEQ5AVw_j4lzIlnsxwTDLJdtaiuCdrkJZ4fVH-E3v_hP7ynwS4zIwrSVCzG7vr5MTXahwESJnsb3SFM5zpdNjj525JbjrUwctt".to_string();
        let digest = Sha256::digest(code_verifier.as_bytes());
        let code_challenge = URL_SAFE_NO_PAD.encode(digest);
        code_challenge.trim_end_matches('=').to_string();
        assert_eq!(
            code_challenge,
            "bOQuaNvcR9utb6HhxpkDuvJr4Wh83ugr_FnH4dvTg9I".to_string()
        );
        let code_verifier: String = "sL0L64E7Qk_TANBue-ejOajO7LP3dcVI64ZgsjMsfV5dMhuDoFgb0Ldb4b7U3EXqBldbZJEAMJoxE8NfFmvm2oimm2FDQhy2qPDEoWUsY60mXF1poaw5cwvnpK-dXSFB".to_string();
        let digest = Sha256::digest(code_verifier.as_bytes());
        let code_challenge = URL_SAFE_NO_PAD.encode(digest);
        code_challenge.trim_end_matches('=').to_string();
        assert_eq!(
            code_challenge,
            "Nju8uPgZTErU1OxovBkfsGwykuhtCVCE-dGGhooiD8E".to_string()
        );
    }

    #[test]
    fn test_get_auth_code_from_url() {
        let url = "https://test.example.com/test?code1=2&code=13&t=sd";
        assert_eq!(get_auth_code_from_url(url), Some("13".to_string()));
    }
}
