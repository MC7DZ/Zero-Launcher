//! Build-time configuration (API keys, OAuth credentials, webhook).
//!
//! Values are read from environment variables when the launcher is *compiled*
//! (directly, or from a `.env` file via `build.rs`) — nothing secret lives in
//! the source tree. See `.env.example`.
//!
//! Note: anything compiled into a binary can be extracted by someone who has
//! the binary. For public releases, prefer a small proxy/relay for secrets.

use std::sync::RwLock;

fn non_empty(v: Option<&'static str>) -> Option<&'static str> {
    v.map(str::trim).filter(|s| !s.is_empty())
}

#[derive(Default, Clone)]
struct UserKeys {
    modrinth_token: String,
    curseforge_key: String,
    elyby_client_id: String,
    elyby_client_secret: String,
    azure_client_id: String,
}

static USER_KEYS: RwLock<UserKeys> = RwLock::new(UserKeys {
    modrinth_token: String::new(),
    curseforge_key: String::new(),
    elyby_client_id: String::new(),
    elyby_client_secret: String::new(),
    azure_client_id: String::new(),
});

fn user_keys() -> UserKeys {
    USER_KEYS.read().unwrap_or_else(|e| e.into_inner()).clone()
}

fn pick(user: String, built_in: Option<&'static str>) -> Option<String> {
    if !user.is_empty() {
        return Some(user);
    }
    non_empty(built_in).map(str::to_string)
}

pub fn set_user_api_keys(
    modrinth_token: &str,
    curseforge_key: &str,
    elyby_client_id: &str,
    elyby_client_secret: &str,
    azure_client_id: &str,
) {
    *USER_KEYS.write().unwrap_or_else(|e| e.into_inner()) = UserKeys {
        modrinth_token: modrinth_token.trim().to_string(),
        curseforge_key: curseforge_key.trim().to_string(),
        elyby_client_id: elyby_client_id.trim().to_string(),
        elyby_client_secret: elyby_client_secret.trim().to_string(),
        azure_client_id: azure_client_id.trim().to_string(),
    };
}

pub fn curseforge_api_key() -> Option<String> {
    pick(user_keys().curseforge_key, option_env!("ZL_CURSEFORGE_API_KEY"))
}

pub fn modrinth_token() -> Option<String> {
    let t = user_keys().modrinth_token;
    if t.is_empty() { None } else { Some(t) }
}

pub fn elyby_client_id() -> Option<String> {
    pick(user_keys().elyby_client_id, option_env!("ZL_ELYBY_CLIENT_ID"))
}

pub fn elyby_client_secret() -> Option<String> {
    pick(user_keys().elyby_client_secret, option_env!("ZL_ELYBY_CLIENT_SECRET"))
}

pub fn azure_client_id() -> Option<String> {
    pick(user_keys().azure_client_id, option_env!("ZL_AZURE_CLIENT_ID"))
}

/// Default Discord crash-report webhook (`ZL_CRASH_WEBHOOK`). Optional.
pub fn crash_webhook() -> Option<&'static str> {
    non_empty(option_env!("ZL_CRASH_WEBHOOK"))
}
