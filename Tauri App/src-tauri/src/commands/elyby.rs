//! Ely.by account sign-in and skin lookup.
//!
//! Ely.by (https://ely.by) runs a Yggdrasil-compatible auth server at
//! authserver.ely.by — the same protocol Mojang's legacy (pre-Microsoft)
//! auth server used, so a plain username+password login works exactly
//! like an old Mojang login: POST credentials once, get back a long-lived
//! `accessToken` + `clientToken` pair, and refresh that pair in place at
//! launch time instead of asking for the password again.
//!
//! Getting the *game* to actually show Ely.by skins/capes (rather than
//! just logging the launcher's own UI into an Ely.by account) additionally
//! requires authlib-injector: a small Java agent that redirects the game's
//! session-server lookups from Mojang to Ely.by. `ensure_authlib_injector`
//! downloads and caches that jar; `minecraft.rs` adds it as a `-javaagent`
//! only when the account launching is an "elyby" account.
//!
//! Skin/cape *editing* still happens on ely.by's own site — their skin
//! upload API is OAuth2-only (authorization-code flow through a browser),
//! which doesn't fit a password-based login, so this launcher links out to
//! https://ely.by/skins for that rather than half-implementing OAuth. What
//! this module *does* give the launcher itself is a live read of whatever
//! skin/cape is currently set, for the account switcher / preview panel.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, State};

use crate::models::AccountInfo;
use crate::state::AppState;

const AUTH_SERVER: &str = "https://authserver.ely.by";
const TEXTURES_ENDPOINT: &str = "https://skinsystem.ely.by/textures";

// ── Login ────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct YggdrasilProfile {
    id: String,
    name: String,
}

#[derive(Debug, Deserialize)]
struct YggdrasilAuthResponse {
    #[serde(rename = "accessToken")]
    access_token: String,
    #[serde(rename = "clientToken")]
    client_token: String,
    #[serde(rename = "selectedProfile")]
    selected_profile: Option<YggdrasilProfile>,
}

#[derive(Debug, Deserialize)]
struct YggdrasilError {
    #[serde(rename = "errorMessage")]
    error_message: Option<String>,
    error: Option<String>,
}

fn plain_client() -> Result<reqwest::Client, String> {
    // Dual-stack, no pinned address family — matches the pattern used for
    // Mojang skin uploads elsewhere in this file's sibling module.
    reqwest::Client::builder()
        .build()
        .map_err(|e| format!("Failed to build HTTP client: {e}"))
}

async fn parse_yggdrasil_error(response: reqwest::Response) -> String {
    let status = response.status();
    match response.json::<YggdrasilError>().await {
        Ok(err) => err
            .error_message
            .or(err.error)
            .unwrap_or_else(|| format!("Ely.by returned HTTP {status}")),
        Err(_) => format!("Ely.by returned HTTP {status}"),
    }
}

/// Log in to Ely.by with a username/email + password and save (or
/// replace) the resulting account, exactly the same way Microsoft
/// sign-in does. The password is sent once, directly to
/// authserver.ely.by over HTTPS, and is never stored — only the
/// resulting access/client token pair is persisted.
#[tauri::command]
pub async fn elyby_login(
    state: State<'_, AppState>,
    username: String,
    password: String,
) -> Result<AccountInfo, String> {
    let username = username.trim().to_string();
    if username.is_empty() {
        return Err("Username or email cannot be empty".to_string());
    }
    if password.is_empty() {
        return Err("Password cannot be empty".to_string());
    }

    let client = plain_client()?;
    let client_token = uuid::Uuid::new_v4().to_string();

    let response = client
        .post(format!("{AUTH_SERVER}/auth/authenticate"))
        .json(&serde_json::json!({
            "username": username,
            "password": password,
            "clientToken": client_token,
            "requestUser": false,
        }))
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by: {e}"))?;

    if !response.status().is_success() {
        return Err(parse_yggdrasil_error(response).await);
    }

    let auth: YggdrasilAuthResponse = response
        .json()
        .await
        .map_err(|e| format!("Unexpected response from Ely.by: {e}"))?;

    let profile = auth.selected_profile.ok_or_else(|| {
        "This Ely.by account has no Minecraft profile (nickname) set up yet — \
         create one at https://ely.by first."
            .to_string()
    })?;

    let mut accounts = state.accounts.lock().unwrap();
    let existing_was_active = accounts
        .iter()
        .any(|a| a.account_type == "elyby" && a.mc_uuid.as_deref() == Some(profile.id.as_str()) && a.is_active);
    accounts.retain(|a| !(a.account_type == "elyby" && a.mc_uuid.as_deref() == Some(profile.id.as_str())));

    let make_active = existing_was_active || accounts.is_empty();
    if make_active {
        for a in accounts.iter_mut() {
            a.is_active = false;
        }
    }

    let account = AccountInfo {
        id: uuid::Uuid::new_v4().to_string(),
        username: profile.name,
        account_type: "elyby".to_string(),
        is_active: make_active,
        mc_uuid: Some(profile.id),
        ms_refresh_token: None,
        needs_reauth: false,
        elyby_access_token: Some(auth.access_token),
        elyby_client_token: Some(auth.client_token),
        elyby_oauth_refresh_token: None,
    };

    accounts.push(account.clone());
    drop(accounts);
    state.save_accounts();

    Ok(account)
}

// ── OAuth2 sign-in (recommended — password never touches the launcher) ────
//
// Ely.by's own docs recommend this over the password grant above:
// https://docs.ely.by/en/oauth.html. The user is sent to their normal
// browser to log in on ely.by's own site; Ely.by redirects back to a
// short-lived local (127.0.0.1-only) HTTP listener with an auth code,
// which we exchange for a token server-side. The launcher's own UI never
// sees the password.
//
// Requires a "Website" app registered at
// https://account.ely.by/dev/applications/new with its Redirect URI set
// to `http://127.0.0.1:<OAUTH_REDIRECT_PORT>/callback` — fill in the
// resulting clientId/clientSecret below. Without that, OAuth sign-in
// can't work (Ely.by requires exact-match registered clients — there's no
// anonymous/public-client mode), and `elyby_oauth_start` returns a clear
// error saying so rather than silently failing.
//
// Note on skins: no matter which sign-in method is used, Ely.by exposes
// no scope or endpoint for a third-party app to *upload* a skin —
// `account_info`/`account_email`/`offline_access`/`minecraft_server_session`
// are the only scopes that exist. Skins are still only ever edited at
// https://ely.by/skins; OAuth here is about not handling the user's
// password, not about unlocking skin management.

/// Fill these in after registering an app at
/// https://account.ely.by/dev/applications/new (type "Website", redirect
/// URI `http://127.0.0.1:38621/callback` to match `OAUTH_REDIRECT_PORT`
/// below). Left blank, `elyby_oauth_start` fails with a message
/// explaining why instead of silently misbehaving.
const ELYBY_OAUTH_CLIENT_ID: &str = match option_env!("ELYBY_CLIENT_ID") {
    Some(v) => v,
    None => "",
};
const ELYBY_OAUTH_CLIENT_SECRET: &str = match option_env!("ELYBY_CLIENT_SECRET") {
    Some(v) => v,
    None => "",
};
/// Must exactly match the port baked into the redirect URI registered
/// above. Picked high/uncommon to avoid colliding with anything else the
/// user might have running locally.
const OAUTH_REDIRECT_PORT: u16 = 38621;
const OAUTH_SCOPES: &str = "account_info offline_access minecraft_server_session";
const OAUTH_AUTHORIZE_URL: &str = "https://account.ely.by/oauth2/v1";
const OAUTH_TOKEN_URL: &str = "https://account.ely.by/api/oauth2/v1/token";
const OAUTH_ACCOUNT_INFO_URL: &str = "https://account.ely.by/api/account/v1/info";

fn oauth_redirect_uri() -> String {
    format!("http://127.0.0.1:{OAUTH_REDIRECT_PORT}/callback")
}

enum OauthCallbackResult {
    Pending,
    Code(String),
    Error(String),
}

/// Ely.by OAuth2 sign-in in progress. Lives in `AppState` between
/// `elyby_oauth_start` and `elyby_oauth_poll`.
pub struct ElybyOauthSession {
    expected_state: String,
    result: std::sync::Arc<std::sync::Mutex<OauthCallbackResult>>,
    cancel: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

/// Parses `?key=value&key2=value2` (already stripped of the leading `?`)
/// into a map, percent-decoding via the `url` crate's form_urlencoded so
/// we don't have to hand-roll it.
fn parse_query_string(query: &str) -> std::collections::HashMap<String, String> {
    url::form_urlencoded::parse(query.as_bytes())
        .into_owned()
        .collect()
}

/// Runs on a background OS thread (not a tokio task — this does a
/// blocking `accept()`) for the lifetime of one sign-in attempt: binds
/// the loopback callback port, waits (polling non-blockingly so `cancel`
/// and a 5-minute timeout both work) for Ely.by's redirect, parses it,
/// serves a friendly "you can close this tab" page, and writes the
/// outcome into `result`.
fn spawn_oauth_callback_listener(
    expected_state: String,
    result: std::sync::Arc<std::sync::Mutex<OauthCallbackResult>>,
    cancel: std::sync::Arc<std::sync::atomic::AtomicBool>,
) {
    std::thread::spawn(move || {
        use std::io::{Read, Write};
        use std::sync::atomic::Ordering;

        // Build the listening socket manually via socket2 so we can set
        // SO_REUSEADDR before binding. Plain std::net::TcpListener::bind
        // doesn't expose that option, so a second sign-in attempt shortly
        // after the first can fail with "Address already in use" while the
        // OS still has the previous (fully closed) connection sitting in
        // TIME_WAIT — even though nothing is actually listening anymore.
        let listener = (|| -> std::io::Result<std::net::TcpListener> {
            let addr: std::net::SocketAddr = (std::net::Ipv4Addr::LOCALHOST, OAUTH_REDIRECT_PORT).into();
            let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
            socket.set_reuse_address(true)?;
            socket.bind(&addr.into())?;
            socket.listen(128)?;
            Ok(socket.into())
        })();
        let listener = match listener {
            Ok(l) => l,
            Err(e) => {
                *result.lock().unwrap() = OauthCallbackResult::Error(format!(
                    "Could not start the local sign-in listener on port {OAUTH_REDIRECT_PORT} ({e}). \
                     Another program (maybe another copy of this launcher) may already be using it."
                ));
                return;
            }
        };
        if listener.set_nonblocking(true).is_err() {
            *result.lock().unwrap() =
                OauthCallbackResult::Error("Could not configure the local sign-in listener.".to_string());
            return;
        }

        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(300);
        loop {
            if cancel.load(Ordering::Relaxed) {
                return;
            }
            if std::time::Instant::now() > deadline {
                *result.lock().unwrap() =
                    OauthCallbackResult::Error("Sign-in timed out — please try again.".to_string());
                return;
            }

            let (mut stream, _) = match listener.accept() {
                Ok(pair) => pair,
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(200));
                    continue;
                }
                Err(e) => {
                    *result.lock().unwrap() =
                        OauthCallbackResult::Error(format!("Local sign-in listener error: {e}"));
                    return;
                }
            };

            let mut buf = [0u8; 8192];
            let n = stream.read(&mut buf).unwrap_or(0);
            let request = String::from_utf8_lossy(&buf[..n]);
            let request_line = request.lines().next().unwrap_or("");
            let path_and_query = request_line.split_whitespace().nth(1).unwrap_or("");
            let query = path_and_query.split_once('?').map(|(_, q)| q).unwrap_or("");
            let params = parse_query_string(query);

            let outcome = if let Some(err) = params.get("error") {
                let msg = params
                    .get("error_message")
                    .cloned()
                    .unwrap_or_else(|| err.clone());
                OauthCallbackResult::Error(msg)
            } else if let Some(code) = params.get("code") {
                match params.get("state") {
                    Some(got) if *got == expected_state => OauthCallbackResult::Code(code.clone()),
                    _ => OauthCallbackResult::Error(
                        "Sign-in response failed a security check (state mismatch) — please try again."
                            .to_string(),
                    ),
                }
            } else {
                OauthCallbackResult::Error(
                    "Ely.by's sign-in response was missing the expected parameters.".to_string(),
                )
            };

            let is_error = matches!(outcome, OauthCallbackResult::Error(_));
            let body = if is_error {
                "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:64px;\">\
                 <h2>Sign-in didn't complete</h2>\
                 <p>You can close this tab and try again in the launcher.</p></body></html>"
            } else {
                "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:64px;\">\
                 <h2>Signed in!</h2>\
                 <p>You can close this tab and return to the launcher.</p></body></html>"
            };
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();

            *result.lock().unwrap() = outcome;
            return;
        }
    });
}

/// Starts an Ely.by OAuth2 sign-in: opens a local loopback listener for
/// the redirect and returns the authorize URL for the frontend to open
/// in the user's real browser (`shell.open`, same as the Microsoft
/// device-code flow does). Call `elyby_oauth_poll` on a timer afterward.
#[tauri::command]
pub async fn elyby_oauth_start(state: State<'_, AppState>) -> Result<String, String> {
    if ELYBY_OAUTH_CLIENT_ID.is_empty() || ELYBY_OAUTH_CLIENT_SECRET.is_empty() {
        return Err(
            "Ely.by OAuth2 sign-in isn't configured yet — this build is missing its Ely.by \
             clientId/clientSecret (register one at https://account.ely.by/dev/applications/new \
             with redirect URI http://127.0.0.1:38621/callback and fill it into elyby.rs)."
                .to_string(),
        );
    }

    let expected_state = uuid::Uuid::new_v4().to_string();
    let result = std::sync::Arc::new(std::sync::Mutex::new(OauthCallbackResult::Pending));
    let cancel = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));

    spawn_oauth_callback_listener(expected_state.clone(), result.clone(), cancel.clone());

    *state.elyby_oauth_session.lock().unwrap() = Some(ElybyOauthSession {
        expected_state: expected_state.clone(),
        result,
        cancel,
    });

    let mut url = url::Url::parse(OAUTH_AUTHORIZE_URL).map_err(|e| format!("Internal URL error: {e}"))?;
    url.query_pairs_mut()
        .append_pair("client_id", ELYBY_OAUTH_CLIENT_ID)
        .append_pair("redirect_uri", &oauth_redirect_uri())
        .append_pair("response_type", "code")
        .append_pair("scope", OAUTH_SCOPES)
        .append_pair("state", &expected_state);

    Ok(url.to_string())
}

#[derive(Debug, Deserialize)]
struct OauthTokenResponse {
    access_token: String,
    refresh_token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct OauthTokenError {
    error: String,
    error_description: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ElybyAccountInfo {
    uuid: String,
    username: String,
}

async fn exchange_oauth_code(client: &reqwest::Client, code: &str) -> Result<OauthTokenResponse, String> {
    let response = client
        .post(OAUTH_TOKEN_URL)
        .form(&[
            ("client_id", ELYBY_OAUTH_CLIENT_ID),
            ("client_secret", ELYBY_OAUTH_CLIENT_SECRET),
            ("redirect_uri", &oauth_redirect_uri()),
            ("grant_type", "authorization_code"),
            ("code", code),
        ])
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by to complete sign-in: {e}"))?;

    if !response.status().is_success() {
        return Err(match response.json::<OauthTokenError>().await {
            Ok(err) => err.error_description.unwrap_or(err.error),
            Err(_) => "Ely.by rejected the sign-in.".to_string(),
        });
    }

    response
        .json()
        .await
        .map_err(|e| format!("Unexpected response from Ely.by: {e}"))
}

async fn fetch_oauth_account_info(client: &reqwest::Client, access_token: &str) -> Result<ElybyAccountInfo, String> {
    let response = client
        .get(OAUTH_ACCOUNT_INFO_URL)
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by for account info: {e}"))?;

    if !response.status().is_success() {
        return Err(format!("Ely.by returned HTTP {} while fetching account info", response.status()));
    }

    response
        .json()
        .await
        .map_err(|e| format!("Unexpected account info response from Ely.by: {e}"))
}

fn save_oauth_login(state: &State<'_, AppState>, info: &ElybyAccountInfo, refresh_token: Option<String>) -> AccountInfo {
    let mut accounts = state.accounts.lock().unwrap();
    let existing_was_active = accounts
        .iter()
        .any(|a| a.account_type == "elyby" && a.mc_uuid.as_deref() == Some(info.uuid.as_str()) && a.is_active);
    accounts.retain(|a| !(a.account_type == "elyby" && a.mc_uuid.as_deref() == Some(info.uuid.as_str())));

    let make_active = existing_was_active || accounts.is_empty();
    if make_active {
        for a in accounts.iter_mut() {
            a.is_active = false;
        }
    }

    let account = AccountInfo {
        id: uuid::Uuid::new_v4().to_string(),
        username: info.username.clone(),
        account_type: "elyby".to_string(),
        is_active: make_active,
        mc_uuid: Some(info.uuid.clone()),
        ms_refresh_token: None,
        needs_reauth: false,
        elyby_access_token: None,
        elyby_client_token: None,
        elyby_oauth_refresh_token: refresh_token,
    };
    accounts.push(account.clone());
    drop(accounts);
    state.save_accounts();
    account
}

/// Polls once to check whether the user has finished signing in via the
/// browser. Returns `Ok(None)` while still pending (call again on a
/// timer, same pattern as `microsoft_device_code_poll`), or
/// `Ok(Some(account))` once signed in, the code exchanged, and the
/// account saved.
#[tauri::command]
pub async fn elyby_oauth_poll(state: State<'_, AppState>) -> Result<Option<AccountInfo>, String> {
    enum PollOutcome {
        Pending,
        Code(String),
        Error(String),
    }

    let outcome = {
        let guard = state.elyby_oauth_session.lock().unwrap();
        let session = guard
            .as_ref()
            .ok_or_else(|| "No Ely.by sign-in in progress.".to_string())?;
        let result_guard = session.result.lock().unwrap();
        match &*result_guard {
            OauthCallbackResult::Pending => PollOutcome::Pending,
            OauthCallbackResult::Error(msg) => PollOutcome::Error(msg.clone()),
            OauthCallbackResult::Code(code) => PollOutcome::Code(code.clone()),
        }
    };

    let code = match outcome {
        PollOutcome::Pending => return Ok(None),
        PollOutcome::Error(msg) => {
            *state.elyby_oauth_session.lock().unwrap() = None;
            return Err(msg);
        }
        PollOutcome::Code(code) => code,
    };
    *state.elyby_oauth_session.lock().unwrap() = None;

    let client = plain_client()?;
    let token = exchange_oauth_code(&client, &code).await?;
    let info = fetch_oauth_account_info(&client, &token.access_token).await?;
    let account = save_oauth_login(&state, &info, token.refresh_token);
    Ok(Some(account))
}

/// Cancels an in-progress Ely.by OAuth2 sign-in (e.g. the user closed the
/// dialog before finishing) and stops the local callback listener.
#[tauri::command]
pub async fn elyby_oauth_cancel(state: State<'_, AppState>) -> Result<(), String> {
    if let Some(session) = state.elyby_oauth_session.lock().unwrap().take() {
        session.cancel.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    Ok(())
}

/// Result of a successful (re)validated Ely.by session, ready to be
/// handed to the game as `--accessToken`/`--uuid`.
pub struct ElybySession {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
}

/// Ensures the given Ely.by account's session token is still valid before
/// launch, refreshing it in place (no password re-entry) if the server
/// says it's stale. Mirrors `msa::refresh_microsoft_login`'s role for
/// Microsoft accounts. Dispatches to whichever of the two Ely.by refresh
/// flows matches how this account originally signed in.
pub async fn refresh_elyby_login(state: &State<'_, AppState>, account_id: &str) -> Result<ElybySession, String> {
    let uses_oauth = {
        let accounts = state.accounts.lock().unwrap();
        let acc = accounts
            .iter()
            .find(|a| a.id == account_id && a.account_type == "elyby")
            .ok_or("Ely.by account not found")?;
        acc.elyby_oauth_refresh_token.is_some()
    };
    if uses_oauth {
        refresh_elyby_login_oauth(state, account_id).await
    } else {
        refresh_elyby_login_password(state, account_id).await
    }
}

/// OAuth2 refresh — used for accounts that signed in via the browser.
/// Ely.by's OAuth refresh_token grant doesn't expire and mints a fresh
/// short-lived access token without ever showing a sign-in prompt again.
async fn refresh_elyby_login_oauth(state: &State<'_, AppState>, account_id: &str) -> Result<ElybySession, String> {
    let (username, uuid, refresh_token) = {
        let accounts = state.accounts.lock().unwrap();
        let acc = accounts
            .iter()
            .find(|a| a.id == account_id && a.account_type == "elyby")
            .ok_or("Ely.by account not found")?;
        (
            acc.username.clone(),
            acc.mc_uuid.clone().ok_or("Ely.by account is missing its profile UUID — please sign in again.")?,
            acc.elyby_oauth_refresh_token
                .clone()
                .ok_or("Ely.by account is missing its OAuth refresh token — please sign in again.")?,
        )
    };

    if ELYBY_OAUTH_CLIENT_ID.is_empty() || ELYBY_OAUTH_CLIENT_SECRET.is_empty() {
        return Err("Ely.by OAuth2 isn't configured on this build — can't refresh the session.".to_string());
    }

    let client = plain_client()?;
    let response = client
        .post(OAUTH_TOKEN_URL)
        .form(&[
            ("client_id", ELYBY_OAUTH_CLIENT_ID),
            ("client_secret", ELYBY_OAUTH_CLIENT_SECRET),
            ("scope", OAUTH_SCOPES),
            ("refresh_token", refresh_token.as_str()),
            ("grant_type", "refresh_token"),
        ])
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by to refresh session: {e}"))?;

    if !response.status().is_success() {
        let msg = match response.json::<OauthTokenError>().await {
            Ok(err) => err.error_description.unwrap_or(err.error),
            Err(_) => "Ely.by rejected the refresh.".to_string(),
        };
        let mut accounts = state.accounts.lock().unwrap();
        if let Some(a) = accounts.iter_mut().find(|a| a.id == account_id) {
            a.needs_reauth = true;
        }
        drop(accounts);
        state.save_accounts();
        return Err(format!("Ely.by session expired and could not be refreshed ({msg}) — please sign in again."));
    }

    let token: OauthTokenResponse = response
        .json()
        .await
        .map_err(|e| format!("Unexpected response from Ely.by: {e}"))?;

    // A refresh_token grant only returns a new refresh_token sometimes —
    // when it doesn't, the old one is still valid and must be kept.
    if let Some(new_refresh) = &token.refresh_token {
        let mut accounts = state.accounts.lock().unwrap();
        if let Some(a) = accounts.iter_mut().find(|a| a.id == account_id) {
            a.elyby_oauth_refresh_token = Some(new_refresh.clone());
            a.needs_reauth = false;
        }
        drop(accounts);
        state.save_accounts();
    }

    Ok(ElybySession { username, uuid, access_token: token.access_token })
}

/// Legacy password-grant refresh — used for accounts that signed in with
/// username+password directly in-app (the Yggdrasil-compatible flow).
async fn refresh_elyby_login_password(state: &State<'_, AppState>, account_id: &str) -> Result<ElybySession, String> {
    let (username, access_token, client_token) = {
        let accounts = state.accounts.lock().unwrap();
        let acc = accounts
            .iter()
            .find(|a| a.id == account_id && a.account_type == "elyby")
            .ok_or("Ely.by account not found")?;
        (
            acc.username.clone(),
            acc.elyby_access_token.clone().ok_or("Ely.by account is missing its session token — please sign in again.")?,
            acc.elyby_client_token.clone().ok_or("Ely.by account is missing its client token — please sign in again.")?,
        )
    };

    let client = plain_client()?;

    // Cheap validity check first — avoids mutating the token pair (and
    // rewriting accounts.json) on every single launch when it's still
    // perfectly good.
    let validate = client
        .post(format!("{AUTH_SERVER}/auth/validate"))
        .json(&serde_json::json!({ "accessToken": access_token, "clientToken": client_token }))
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by to verify session: {e}"))?;

    if validate.status().is_success() {
        let uuid = {
            let accounts = state.accounts.lock().unwrap();
            accounts
                .iter()
                .find(|a| a.id == account_id)
                .and_then(|a| a.mc_uuid.clone())
                .ok_or("Ely.by account is missing its profile UUID — please sign in again.")?
        };
        return Ok(ElybySession { username, uuid, access_token });
    }

    // Token's stale — refresh it.
    let refresh = client
        .post(format!("{AUTH_SERVER}/auth/refresh"))
        .json(&serde_json::json!({
            "accessToken": access_token,
            "clientToken": client_token,
            "requestUser": false,
        }))
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by to refresh session: {e}"))?;

    if !refresh.status().is_success() {
        let err_msg = parse_yggdrasil_error(refresh).await;
        let mut accounts = state.accounts.lock().unwrap();
        if let Some(a) = accounts.iter_mut().find(|a| a.id == account_id) {
            a.needs_reauth = true;
        }
        drop(accounts);
        state.save_accounts();
        return Err(format!(
            "Ely.by session expired and could not be refreshed ({err_msg}) — please sign in again."
        ));
    }

    let refreshed: YggdrasilAuthResponse = refresh
        .json()
        .await
        .map_err(|e| format!("Unexpected response from Ely.by: {e}"))?;

    let uuid = refreshed
        .selected_profile
        .as_ref()
        .map(|p| p.id.clone())
        .or_else(|| {
            state
                .accounts
                .lock()
                .unwrap()
                .iter()
                .find(|a| a.id == account_id)
                .and_then(|a| a.mc_uuid.clone())
        })
        .ok_or("Ely.by refresh response was missing the profile UUID")?;

    {
        let mut accounts = state.accounts.lock().unwrap();
        if let Some(a) = accounts.iter_mut().find(|a| a.id == account_id) {
            a.elyby_access_token = Some(refreshed.access_token.clone());
            a.elyby_client_token = Some(refreshed.client_token.clone());
            a.needs_reauth = false;
        }
    }
    state.save_accounts();

    Ok(ElybySession {
        username,
        uuid,
        access_token: refreshed.access_token,
    })
}

/// Signs the given Ely.by account out: invalidates its token pair
/// server-side (so it can't be replayed) and clears the locally stored
/// tokens. `remove_account` already deletes the account entry itself —
/// this is for an explicit "Sign out" action that keeps the account
/// listed but logged out.
#[tauri::command]
pub async fn elyby_logout(state: State<'_, AppState>, account_id: String) -> Result<(), String> {
    let tokens = {
        let accounts = state.accounts.lock().unwrap();
        accounts
            .iter()
            .find(|a| a.id == account_id && a.account_type == "elyby")
            .and_then(|a| a.elyby_access_token.clone().zip(a.elyby_client_token.clone()))
    };

    if let Some((access_token, client_token)) = tokens {
        let client = plain_client()?;
        let _ = client
            .post(format!("{AUTH_SERVER}/auth/invalidate"))
            .json(&serde_json::json!({ "accessToken": access_token, "clientToken": client_token }))
            .send()
            .await; // best-effort — still clear locally even if this fails
    }

    let mut accounts = state.accounts.lock().unwrap();
    if let Some(a) = accounts.iter_mut().find(|a| a.id == account_id) {
        a.elyby_access_token = None;
        a.elyby_client_token = None;
        a.elyby_oauth_refresh_token = None;
        a.needs_reauth = true;
    }
    drop(accounts);
    state.save_accounts();
    Ok(())
}

// ── Skin / cape lookup (for the "dressing room" preview) ───────────────────

#[derive(Debug, Deserialize)]
struct TexturesResponse {
    textures: Option<TextureSet>,
}

#[derive(Debug, Deserialize)]
struct TextureSet {
    #[serde(rename = "SKIN")]
    skin: Option<TextureEntry>,
    #[serde(rename = "CAPE")]
    cape: Option<TextureEntry>,
}

#[derive(Debug, Deserialize)]
struct TextureEntry {
    url: String,
    /// Content hash — the same skin url can stay identical after a
    /// re-upload, but this hash changes, so it's appended as a
    /// cache-busting query param when the frontend loads the image
    /// (otherwise the webview's HTTP cache can keep showing an old
    /// skin forever for the same url).
    hash: Option<String>,
    metadata: Option<TextureMetadata>,
}

#[derive(Debug, Deserialize)]
struct TextureMetadata {
    model: Option<String>, // "slim" when present, classic otherwise
}

#[derive(Debug, Clone, Serialize)]
pub struct ElybySkinInfo {
    pub username: String,
    pub skin_url: Option<String>,
    pub cape_url: Option<String>,
    /// Content hash of the current skin, when Ely.by provides one. Used
    /// by the frontend to build a per-version local cache filename (e.g.
    /// `elyby_<username>_<hash>.png`) so a re-uploaded skin is treated as
    /// a genuinely different file instead of overwriting/reusing one the
    /// webview might still have cached under the same name.
    pub skin_hash: Option<String>,
    /// "classic" or "slim"
    pub model: String,
}

/// Reads whatever skin/cape is *currently* set on an Ely.by profile,
/// straight from Ely.by's public texture endpoint (no login required —
/// this is the same public lookup the game itself performs once
/// authlib-injector is active). Used to populate the dressing-room
/// preview without needing a fresh password prompt.
#[tauri::command]
pub async fn get_elyby_skin_info(username: String) -> Result<ElybySkinInfo, String> {
    let username = username.trim().to_string();
    if username.is_empty() {
        return Err("Username cannot be empty".to_string());
    }
    let client = plain_client()?;
    let response = client
        .get(format!("{TEXTURES_ENDPOINT}/{username}"))
        .send()
        .await
        .map_err(|e| format!("Could not reach Ely.by: {e}"))?;

    if response.status() == reqwest::StatusCode::NO_CONTENT {
        return Ok(ElybySkinInfo {
            username,
            skin_url: None,
            cape_url: None,
            skin_hash: None,
            model: "classic".to_string(),
        });
    }
    if !response.status().is_success() {
        return Err(format!("Ely.by returned HTTP {}", response.status()));
    }

    let parsed: TexturesResponse = response
        .json()
        .await
        .map_err(|e| format!("Unexpected response from Ely.by: {e}"))?;

    let textures = parsed.textures.unwrap_or(TextureSet { skin: None, cape: None });
    let model = textures
        .skin
        .as_ref()
        .and_then(|s| s.metadata.as_ref())
        .and_then(|m| m.model.clone())
        .unwrap_or_else(|| "classic".to_string());
    let skin_hash = textures.skin.as_ref().and_then(|s| s.hash.clone());

    Ok(ElybySkinInfo {
        username,
        skin_url: textures.skin.map(|s| match s.hash {
            // Cache-bust: Ely.by's skin url commonly stays the same across
            // re-uploads (only the file contents change), so append the
            // content hash as a query param to force a genuine re-fetch
            // instead of reusing whatever was cached for that url before.
            Some(hash) if !hash.is_empty() => {
                let sep = if s.url.contains('?') { '&' } else { '?' };
                format!("{}{sep}v={hash}", s.url)
            }
            _ => s.url,
        }),
        cape_url: textures.cape.map(|c| c.url),
        skin_hash,
        model,
    })
}

// ── authlib-injector ─────────────────────────────────────────────────────

/// Latest-release metadata endpoint for authlib-injector, the Java agent
/// that makes the game fetch skins/capes/auth from Ely.by instead of
/// Mojang. Ely.by's own docs point at this same upstream project.
const AUTHLIB_INJECTOR_LATEST: &str = "https://authlib-injector.yushi.moe/artifact/latest.json";

#[derive(Debug, Deserialize)]
struct InjectorArtifact {
    download_url: String,
}

fn injector_cache_path(state: &AppState) -> PathBuf {
    state.data_dir.join("authlib-injector").join("authlib-injector.jar")
}

/// Downloads (once) and returns the local path of authlib-injector.jar,
/// caching it under `<data_dir>/authlib-injector/`. Only ever called
/// when an "elyby" account is about to be launched.
pub async fn ensure_authlib_injector(app: &AppHandle, state: &AppState) -> Result<PathBuf, String> {
    let path = injector_cache_path(state);
    if path.exists() {
        // Cheap sanity check — a zero-byte file from an interrupted
        // download shouldn't be treated as "already downloaded".
        if std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0) > 0 {
            return Ok(path);
        }
    }

    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("Failed to create authlib-injector cache dir: {e}"))?;
    }

    let meta_resp = crate::network::send(app, state, "authlib-injector metadata fetch", |c| {
        c.get(AUTHLIB_INJECTOR_LATEST)
    })
    .await?;
    let meta: InjectorArtifact = meta_resp
        .json()
        .await
        .map_err(|e| format!("Unexpected authlib-injector metadata response: {e}"))?;

    let jar_resp = crate::network::send(app, state, "authlib-injector download", |c| {
        c.get(&meta.download_url)
    })
    .await?;
    if !jar_resp.status().is_success() {
        return Err(format!("Failed to download authlib-injector: HTTP {}", jar_resp.status()));
    }
    let bytes = jar_resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read authlib-injector download: {e}"))?;

    // Write to a temp file first so a crash/interrupt mid-download never
    // leaves a corrupt jar sitting at the "final" path looking cached.
    let tmp_path = path.with_extension("jar.part");
    std::fs::write(&tmp_path, &bytes).map_err(|e| format!("Failed to save authlib-injector: {e}"))?;
    std::fs::rename(&tmp_path, &path).map_err(|e| format!("Failed to finalize authlib-injector: {e}"))?;

    Ok(path)
}
