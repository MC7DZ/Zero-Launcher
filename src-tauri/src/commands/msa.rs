//! Microsoft account sign-in.
//!
//! The actual OAuth/Xbox Live/XSTS/Minecraft-services plumbing already
//! lives in the vendored `mc_launcher_core::auth::microsoft_account`
//! module — this file just wires it up to a real UI.
//!
//! Sign-in uses the device-code flow: Microsoft gives us a short code and
//! a URL (microsoft.com/link) for the user to open in *any* browser —
//! their normal one, or on their phone — and enter the code there. There
//! is deliberately no embedded webview/popup for the login page itself:
//! embedded webviews are frequently flagged by Microsoft's bot/anti-automation
//! checks (missing passkey support, broken 2FA prompts, and — if the flow
//! gets far enough to look "successful" but is actually silently rejected —
//! malformed/rejected responses later in the chain, e.g. Minecraft services
//! returning something other than a normal login response). The device-code
//! flow sidesteps all of that by using a real, fully up-to-date browser for
//! the actual sign-in. The frontend polls `microsoft_device_code_poll` on a
//! timer until it resolves.
//!
//! Uses this launcher's own registered Azure AD app ("Application (client)
//! ID") for sign-in, so users don't need to register or paste their own.

use tauri::State;

use mc_launcher_core::auth::microsoft_account as msa;
use mc_launcher_core::types::microsoft_types::CompleteLoginResponse;

use crate::models::AccountInfo;
use crate::state::AppState;

/// This launcher's registered Azure AD "Application (client) ID".
pub const AZURE_CLIENT_ID: &str = "1b1eda1c-5d4b-4231-8adb-af52aebfb170";

/// Saves (or replaces) the account for a completed Microsoft login.
fn save_microsoft_login(state: &State<'_, AppState>, login: &CompleteLoginResponse) -> AccountInfo {
    let mut accounts = state.accounts.lock().unwrap();
    // Signing in again with the same Microsoft profile replaces the old
    // entry (refreshed token) instead of duplicating it.
    let existing_was_active = accounts
        .iter()
        .any(|a| a.account_type == "microsoft" && a.mc_uuid.as_deref() == Some(login.id.as_str()) && a.is_active);
    accounts.retain(|a| {
        !(a.account_type == "microsoft" && a.mc_uuid.as_deref() == Some(login.id.as_str()))
    });

    let make_active = existing_was_active || accounts.is_empty();
    if make_active {
        for a in accounts.iter_mut() {
            a.is_active = false;
        }
    }

    let acc = AccountInfo {
        id: uuid::Uuid::new_v4().to_string(),
        username: login.name.clone(),
        account_type: "microsoft".to_string(),
        is_active: make_active,
        mc_uuid: Some(login.id.clone()),
        ms_refresh_token: Some(login.refresh_token.clone()),
        needs_reauth: false,
    };
    accounts.push(acc.clone());
    drop(accounts);
    state.save_accounts();

    // Cache the valid login session (valid for ~24 hours)
    state.msa_session_cache.lock().unwrap().insert(
        acc.id.clone(),
        crate::state::CachedMsaSession {
            login: login.clone(),
            expires_at: std::time::Instant::now() + std::time::Duration::from_secs(82_800),
        },
    );

    acc
}

// ── Device-code sign-in ──────────────────────────────────────────────────

/// Starts a device-code sign-in and returns the code/URL to show the user.
#[tauri::command]
pub async fn microsoft_device_code_start(
    state: State<'_, AppState>,
) -> Result<msa::DeviceCodeStart, String> {
    let start = tokio::task::spawn_blocking(|| {
        msa::start_device_code(AZURE_CLIENT_ID).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| format!("Device sign-in failed to start: {e}"))??;

    *state.device_code_session.lock().unwrap() = Some(start.device_code.clone());
    Ok(start)
}

/// Polls once to check whether the user has finished a device-code
/// sign-in. Returns `Ok(None)` while still pending (call again after the
/// `interval` from the start response), or `Ok(Some(account))` once
/// signed in and saved. Errors (expired code, declined, etc.) clear the
/// session and are returned as `Err`.
#[tauri::command]
pub async fn microsoft_device_code_poll(
    state: State<'_, AppState>,
) -> Result<Option<AccountInfo>, String> {
    let device_code = state
        .device_code_session
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "No device sign-in in progress.".to_string())?;

    let poll_result = tokio::task::spawn_blocking(move || {
        match msa::poll_device_code_token(AZURE_CLIENT_ID, &device_code) {
            Ok(msa::DeviceCodePoll::Pending) => Ok(None),
            Ok(msa::DeviceCodePoll::Success(token)) => Ok(Some(token)),
            Err(e) => Err(e.to_string()),
        }
    })
    .await
    .map_err(|e| format!("Poll task failed: {e}"))?;

    match poll_result {
        Ok(None) => Ok(None),
        Ok(Some(token)) => {
            *state.device_code_session.lock().unwrap() = None;
            let login = tokio::task::spawn_blocking(move || {
                msa::complete_login_from_token(token).map_err(|e| e.to_string())
            })
            .await
            .map_err(|e| format!("Login task failed: {e}"))??;
            let account = save_microsoft_login(&state, &login);
            Ok(Some(account))
        }
        Err(e) => {
            *state.device_code_session.lock().unwrap() = None;
            Err(e)
        }
    }
}

/// Cancels an in-progress device-code sign-in (e.g. the user closed the
/// dialog before finishing).
#[tauri::command]
pub async fn microsoft_device_code_cancel(state: State<'_, AppState>) -> Result<(), String> {
    *state.device_code_session.lock().unwrap() = None;
    Ok(())
}

/// Re-authenticates a saved Microsoft account with its stored refresh
/// token, persists the rotated refresh token, and returns everything
/// needed to launch (username, Minecraft profile UUID, access token) plus
/// the updated [`AccountInfo`]. Uses cached sessions to prevent refresh token
/// burning and race condition invalidations.
pub(crate) async fn refresh_microsoft_login(
    state: &State<'_, AppState>,
    id: &str,
) -> Result<(CompleteLoginResponse, AccountInfo), String> {
    // 1. Check in-memory session cache first
    {
        let cache = state.msa_session_cache.lock().unwrap();
        if let Some(cached) = cache.get(id) {
            if std::time::Instant::now() < cached.expires_at {
                let accounts = state.accounts.lock().unwrap();
                if let Some(account) = accounts.iter().find(|a| a.id == id) {
                    return Ok((cached.login.clone(), account.clone()));
                }
            }
        }
    }

    // 2. Acquire global refresh lock to serialize OAuth token exchanges
    let _guard = state.msa_refresh_lock.lock().await;

    // 3. Double-check cache in case another task refreshed it while waiting for the lock
    {
        let cache = state.msa_session_cache.lock().unwrap();
        if let Some(cached) = cache.get(id) {
            if std::time::Instant::now() < cached.expires_at {
                let accounts = state.accounts.lock().unwrap();
                if let Some(account) = accounts.iter().find(|a| a.id == id) {
                    return Ok((cached.login.clone(), account.clone()));
                }
            }
        }
    }

    let refresh_token = {
        let accounts = state.accounts.lock().unwrap();
        let account = accounts
            .iter()
            .find(|a| a.id == id)
            .ok_or_else(|| "Account not found.".to_string())?;
        if account.account_type != "microsoft" {
            return Err("Not a Microsoft account.".to_string());
        }
        match account.ms_refresh_token.clone() {
            Some(t) => t,
            None => {
                drop(accounts);
                mark_needs_reauth(state, id);
                return Err(
                    "This account has no stored refresh token — sign in again.".to_string(),
                );
            }
        }
    };
    let client_id = AZURE_CLIENT_ID.to_string();

    let login = match tokio::task::spawn_blocking(move || {
        msa::complete_refresh(&client_id, None, &refresh_token).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| format!("Refresh task failed: {e}"))?
    {
        Ok(login) => login,
        Err(e) => {
            let lower = e.to_lowercase();
            // ONLY flag needs_reauth if Microsoft permanently rejected the token
            if lower.contains("invalid_grant")
                || lower.contains("aadsts70008")
                || lower.contains("aadsts70000")
                || lower.contains("revoked")
            {
                mark_needs_reauth(state, id);
                return Err(format!("Microsoft sign-in expired — please sign in again: {e}"));
            }
            return Err(format!("Microsoft authentication error: {e}"));
        }
    };

    let mut accounts = state.accounts.lock().unwrap();
    let account = accounts
        .iter_mut()
        .find(|a| a.id == id)
        .ok_or_else(|| "Account not found.".to_string())?;
    account.username = login.name.clone();
    account.mc_uuid = Some(login.id.clone());
    account.ms_refresh_token = Some(login.refresh_token.clone());
    account.needs_reauth = false;
    let updated = account.clone();
    drop(accounts);
    state.save_accounts();

    // Cache the fresh login session
    state.msa_session_cache.lock().unwrap().insert(
        id.to_string(),
        crate::state::CachedMsaSession {
            login: login.clone(),
            expires_at: std::time::Instant::now() + std::time::Duration::from_secs(82_800),
        },
    );

    Ok((login, updated))
}

/// Flags an account as needing the user to sign in again, and persists it
/// immediately so the header account button reflects it on next refresh.
fn mark_needs_reauth(state: &State<'_, AppState>, id: &str) {
    let mut accounts = state.accounts.lock().unwrap();
    if let Some(account) = accounts.iter_mut().find(|a| a.id == id) {
        account.needs_reauth = true;
    }
    drop(accounts);
    state.save_accounts();
}

/// Frontend-facing wrapper around [`refresh_microsoft_login`] — used by the
/// "Verify" action in the accounts UI to confirm a saved account still
/// works without actually launching anything.
#[tauri::command]
pub async fn refresh_microsoft_account(
    state: State<'_, AppState>,
    id: String,
) -> Result<AccountInfo, String> {
    let (_login, updated) = refresh_microsoft_login(&state, &id).await?;
    Ok(updated)
}

/// Silently re-validates every saved Microsoft account's session (not just
/// the active one), so the Accounts Manager can auto-refresh in the
/// background and flag any that need re-authentication before the user
/// tries to switch to or launch with them. Offline accounts are skipped —
/// they have nothing to refresh. Best-effort: a failure on one account
/// (already recorded via `needs_reauth` by `refresh_microsoft_login`)
/// doesn't stop the rest from being checked.
#[tauri::command]
pub async fn refresh_all_microsoft_accounts(
    state: State<'_, AppState>,
) -> Result<Vec<AccountInfo>, String> {
    let ids: Vec<String> = {
        let accounts = state.accounts.lock().unwrap();
        accounts
            .iter()
            .filter(|a| a.account_type == "microsoft")
            .map(|a| a.id.clone())
            .collect()
    };
    for id in ids {
        let _ = refresh_microsoft_login(&state, &id).await;
    }
    Ok(state.accounts.lock().unwrap().clone())
}
