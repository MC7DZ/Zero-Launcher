//! Build-time configuration (API keys, OAuth credentials, webhook).
//!
//! Values are read from environment variables when the launcher is *compiled*
//! (directly, or from a `.env` file via `build.rs`) — nothing secret lives in
//! the source tree. See `.env.example`.
//!
//! Note: anything compiled into a binary can be extracted by someone who has
//! the binary. For public releases, prefer a small proxy/relay for secrets.

fn non_empty(v: Option<&'static str>) -> Option<&'static str> {
    v.map(str::trim).filter(|s| !s.is_empty())
}

/// CurseForge API key (`ZL_CURSEFORGE_API_KEY`). Get one at https://console.curseforge.com
pub fn curseforge_api_key() -> Option<&'static str> {
    non_empty(option_env!("ZL_CURSEFORGE_API_KEY"))
}

/// Ely.by OAuth2 client id (`ZL_ELYBY_CLIENT_ID`).
pub fn elyby_client_id() -> Option<&'static str> {
    non_empty(option_env!("ZL_ELYBY_CLIENT_ID"))
}

/// Ely.by OAuth2 client secret (`ZL_ELYBY_CLIENT_SECRET`).
pub fn elyby_client_secret() -> Option<&'static str> {
    non_empty(option_env!("ZL_ELYBY_CLIENT_SECRET"))
}

/// Azure AD application (client) id for Microsoft login (`ZL_AZURE_CLIENT_ID`).
pub fn azure_client_id() -> Option<&'static str> {
    non_empty(option_env!("ZL_AZURE_CLIENT_ID"))
}

/// Default Discord crash-report webhook (`ZL_CRASH_WEBHOOK`). Optional.
pub fn crash_webhook() -> Option<&'static str> {
    non_empty(option_env!("ZL_CRASH_WEBHOOK"))
}
