use std::path::PathBuf;
use tauri::State;
use crate::state::AppState;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkinItem {
    pub id: String,
    pub name: String,
    pub path: String,
    pub is_custom: bool,
    pub modified: u64,
}

fn safe_name(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_alphanumeric() || c == '_' || c == '-' { c } else { '_' })
        .collect::<String>()
}

fn display_name(stem: &str) -> String {
    let cleaned = stem.replace('_', " ").replace('-', " ");
    let words: Vec<String> = cleaned
        .split_whitespace()
        .map(|w| {
            let mut chars = w.chars();
            match chars.next() {
                None => String::new(),
                Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
            }
        })
        .collect();
    if words.is_empty() {
        "Custom Skin".to_string()
    } else {
        words.join(" ")
    }
}

/// List all skins stored in the `<data_dir>/skins/` directory.
#[tauri::command]
pub fn list_skins(state: State<'_, AppState>) -> Result<Vec<SkinItem>, String> {
    let skins_dir = state.data_dir.join("skins");
    if !skins_dir.exists() {
        std::fs::create_dir_all(&skins_dir).ok();
        return Ok(Vec::new());
    }

    let mut list = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&skins_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() && path.extension().and_then(|e| e.to_str()).map(|e| e.eq_ignore_ascii_case("png")).unwrap_or(false) {
                if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
                    let modified = entry
                        .metadata()
                        .and_then(|m| m.modified())
                        .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs();

                    list.push(SkinItem {
                        id: stem.to_string(),
                        name: display_name(stem),
                        path: path.to_string_lossy().to_string(),
                        is_custom: true,
                        modified,
                    });
                }
            }
        }
    }

    list.sort_by(|a, b| b.modified.cmp(&a.modified));
    Ok(list)
}

/// Import a skin .png file into `<data_dir>/skins/`.
#[tauri::command]
pub fn import_skin(
    state: State<'_, AppState>,
    source_path: String,
    name: Option<String>,
) -> Result<SkinItem, String> {
    let src = PathBuf::from(&source_path);
    if !src.exists() || !src.is_file() {
        return Err("Source skin file does not exist".to_string());
    }

    let bytes = std::fs::read(&src).map_err(|e| format!("Failed to read skin file: {e}"))?;
    if bytes.len() < 8 || &bytes[0..8] != b"\x89PNG\r\n\x1a\n" {
        return Err("The selected file is not a valid PNG image".to_string());
    }
    if bytes.len() > 10 * 1024 * 1024 {
        return Err("Skin file is too large (max 10MB)".to_string());
    }

    let skins_dir = state.data_dir.join("skins");
    std::fs::create_dir_all(&skins_dir).map_err(|e| format!("Failed to create skins directory: {e}"))?;

    let raw_name = name
        .filter(|n| !n.trim().is_empty())
        .or_else(|| src.file_stem().and_then(|s| s.to_str()).map(|s| s.to_string()))
        .unwrap_or_else(|| "skin".to_string());

    let base_stem = safe_name(&raw_name);
    let mut final_stem = base_stem.clone();
    let mut counter = 1;
    while skins_dir.join(format!("{final_stem}.png")).exists() {
        final_stem = format!("{base_stem}_{counter}");
        counter += 1;
    }

    let dest_path = skins_dir.join(format!("{final_stem}.png"));
    std::fs::write(&dest_path, &bytes).map_err(|e| format!("Failed to save skin: {e}"))?;

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    Ok(SkinItem {
        id: final_stem.clone(),
        name: display_name(&final_stem),
        path: dest_path.to_string_lossy().to_string(),
        is_custom: true,
        modified: now,
    })
}

/// Delete a skin from `<data_dir>/skins/`.
#[tauri::command]
pub fn delete_skin(state: State<'_, AppState>, path_or_id: String) -> Result<(), String> {
    let skins_dir = state.data_dir.join("skins");
    let target = if path_or_id.ends_with(".png") {
        PathBuf::from(&path_or_id)
    } else {
        skins_dir.join(format!("{}.png", safe_name(&path_or_id)))
    };

    let skins_canonical = skins_dir.canonicalize().map_err(|e| e.to_string())?;
    if let Ok(target_canonical) = target.canonicalize() {
        if target_canonical.starts_with(&skins_canonical) {
            std::fs::remove_file(&target_canonical).map_err(|e| format!("Failed to delete skin: {e}"))?;
            return Ok(());
        }
    }

    Err("Invalid skin path or file does not exist".to_string())
}

/// Download a skin texture from URL and save it to `<data_dir>/skins/<name>.png`.
#[tauri::command]
pub async fn cache_skin_texture(
    state: State<'_, AppState>,
    name: String,
    texture_url: String,
) -> Result<SkinItem, String> {
    let stem = safe_name(&name);
    if stem.is_empty() {
        return Err("Invalid skin name".to_string());
    }

    let skins_dir = state.data_dir.join("skins");
    std::fs::create_dir_all(&skins_dir).map_err(|e| format!("Failed to create skins directory: {e}"))?;

    let resp = reqwest::get(&texture_url)
        .await
        .map_err(|e| format!("Failed to download skin texture: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Skin download failed: HTTP {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| format!("Failed to read skin response: {e}"))?;

    let dest = skins_dir.join(format!("{stem}.png"));
    std::fs::write(&dest, &bytes).map_err(|e| format!("Failed to write skin file: {e}"))?;

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    Ok(SkinItem {
        id: stem.clone(),
        name: display_name(&stem),
        path: dest.to_string_lossy().to_string(),
        is_custom: true,
        modified: now,
    })
}

// Backwards compatibility wrappers
#[derive(serde::Serialize)]
pub struct CachedSkin {
    pub username: String,
    pub path: String,
}

#[tauri::command]
pub async fn cache_account_skin(
    state: State<'_, AppState>,
    username: String,
    render_url: String,
) -> Result<String, String> {
    let item = cache_skin_texture(state, username, render_url).await?;
    Ok(item.path)
}

#[tauri::command]
pub fn list_cached_skins(state: State<'_, AppState>) -> Result<Vec<CachedSkin>, String> {
    let skins = list_skins(state)?;
    Ok(skins
        .into_iter()
        .map(|s| CachedSkin {
            username: s.id,
            path: s.path,
        })
        .collect())
}

/// Upload an active or specified Microsoft account's skin to official Mojang/Microsoft servers.
#[tauri::command]
pub async fn upload_skin_to_mojang(
    state: State<'_, AppState>,
    account_id: Option<String>,
    skin_path: String,
    variant: Option<String>,
) -> Result<(), String> {
    // 1. Find the target account ID
    let target_id = {
        let accounts = state.accounts.lock().unwrap();
        let acc = match account_id {
            Some(ref id) => accounts.iter().find(|a| &a.id == id),
            None => accounts.iter().find(|a| a.is_active),
        };
        match acc {
            Some(a) => {
                if a.account_type != "microsoft" {
                    return Err("Skin upload to Mojang servers is only supported for Microsoft accounts. Offline accounts use local skins.".to_string());
                }
                a.id.clone()
            }
            None => return Err("No active Microsoft account found to upload skin to.".to_string()),
        }
    };

    // 2. Get Minecraft access token (cached or fresh)
    let (mut login_data, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
        .await
        .map_err(|e| format!("Authentication failed: {e}"))?;

    // 3. Read the skin PNG file bytes
    let skin_file = PathBuf::from(&skin_path);
    if !skin_file.exists() || !skin_file.is_file() {
        return Err("Skin file does not exist on disk".to_string());
    }
    let skin_bytes = std::fs::read(&skin_file)
        .map_err(|e| format!("Failed to read skin file: {e}"))?;

    if skin_bytes.len() < 8 || &skin_bytes[0..8] != b"\x89PNG\r\n\x1a\n" {
        return Err("Invalid PNG image file".to_string());
    }

    // 4. Determine skin variant ("classic" or "slim")
    let skin_variant = variant
        .filter(|v| v == "slim" || v == "classic")
        .unwrap_or_else(|| "classic".to_string());

    // 5. Send multipart/form-data request to Mojang API
    let client = reqwest::Client::builder()
        .build()
        .map_err(|e| format!("Failed to build HTTP client: {e}"))?;

    let make_form = || {
        reqwest::multipart::Form::new()
            .text("variant", skin_variant.clone())
            .part(
                "file",
                reqwest::multipart::Part::bytes(skin_bytes.clone())
                    .file_name("skin.png")
                    .mime_str("image/png")
                    .unwrap(),
            )
    };

    let mut response = client
        .post("https://api.minecraftservices.com/minecraft/profile/skins")
        .header("Authorization", format!("Bearer {}", login_data.access_token))
        .multipart(make_form())
        .send()
        .await
        .map_err(|e| format!("Failed to send skin upload request: {e}"))?;

    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        state.msa_session_cache.lock().unwrap().remove(&target_id);
        let (refreshed, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
            .await
            .map_err(|e| format!("Authentication failed: {e}"))?;
        login_data = refreshed;
        response = client
            .post("https://api.minecraftservices.com/minecraft/profile/skins")
            .header("Authorization", format!("Bearer {}", login_data.access_token))
            .multipart(make_form())
            .send()
            .await
            .map_err(|e| format!("Failed to send skin upload request: {e}"))?;
    }

    if response.status().is_success() {
        Ok(())
    } else {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        Err(format!("Mojang server returned HTTP {status}: {body}"))
    }
}

/// Reset active or specified Microsoft account's skin on official Mojang servers back to default.
#[tauri::command]
pub async fn reset_mojang_skin(
    state: State<'_, AppState>,
    account_id: Option<String>,
) -> Result<(), String> {
    let target_id = {
        let accounts = state.accounts.lock().unwrap();
        let acc = match account_id {
            Some(ref id) => accounts.iter().find(|a| &a.id == id),
            None => accounts.iter().find(|a| a.is_active),
        };
        match acc {
            Some(a) => {
                if a.account_type != "microsoft" {
                    return Err("Skin reset on Mojang servers is only supported for Microsoft accounts.".to_string());
                }
                a.id.clone()
            }
            None => return Err("No active Microsoft account found.".to_string()),
        }
    };

    let (mut login_data, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
        .await
        .map_err(|e| format!("Authentication failed: {e}"))?;

    let client = reqwest::Client::builder()
        .build()
        .map_err(|e| format!("Failed to build HTTP client: {e}"))?;

    let mut response = client
        .delete("https://api.minecraftservices.com/minecraft/profile/skins/active")
        .header("Authorization", format!("Bearer {}", login_data.access_token))
        .send()
        .await
        .map_err(|e| format!("Failed to reset skin on Mojang servers: {e}"))?;

    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        state.msa_session_cache.lock().unwrap().remove(&target_id);
        let (refreshed, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
            .await
            .map_err(|e| format!("Authentication failed: {e}"))?;
        login_data = refreshed;
        response = client
            .delete("https://api.minecraftservices.com/minecraft/profile/skins/active")
            .header("Authorization", format!("Bearer {}", login_data.access_token))
            .send()
            .await
            .map_err(|e| format!("Failed to reset skin on Mojang servers: {e}"))?;
    }

    if response.status().is_success() {
        Ok(())
    } else {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        Err(format!("Mojang server returned HTTP {status}: {body}"))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CapeInfo {
    pub id: String,
    pub state: String,
    pub url: String,
    pub alias: Option<String>,
}

#[derive(Debug, Deserialize)]
struct MojangProfileResponse {
    #[serde(default)]
    capes: Vec<CapeInfo>,
}

/// Fetch official capes owned by the Microsoft account
#[tauri::command]
pub async fn get_account_capes(
    state: State<'_, AppState>,
    account_id: Option<String>,
) -> Result<Vec<CapeInfo>, String> {
    let target_id = {
        let accounts = state.accounts.lock().unwrap();
        let acc = match account_id {
            Some(ref id) => accounts.iter().find(|a| &a.id == id),
            None => accounts.iter().find(|a| a.is_active),
        };
        match acc {
            Some(a) => {
                if a.account_type != "microsoft" {
                    return Ok(Vec::new());
                }
                a.id.clone()
            }
            None => return Ok(Vec::new()),
        }
    };

    let (mut login_data, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
        .await
        .map_err(|e| format!("Authentication failed: {e}"))?;

    let client = reqwest::Client::builder()
        .build()
        .map_err(|e| e.to_string())?;

    let mut resp = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .header("Authorization", format!("Bearer {}", login_data.access_token))
        .send()
        .await
        .map_err(|e| format!("Failed to fetch profile capes: {e}"))?;

    if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
        state.msa_session_cache.lock().unwrap().remove(&target_id);
        let (refreshed, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
            .await
            .map_err(|e| format!("Authentication failed: {e}"))?;
        login_data = refreshed;
        resp = client
            .get("https://api.minecraftservices.com/minecraft/profile")
            .header("Authorization", format!("Bearer {}", login_data.access_token))
            .send()
            .await
            .map_err(|e| format!("Failed to fetch profile capes: {e}"))?;
    }

    if !resp.status().is_success() {
        return Err(format!("Mojang profile API returned HTTP {}", resp.status()));
    }

    let profile: MojangProfileResponse = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse profile response: {e}"))?;

    Ok(profile.capes)
}

#[derive(Debug, Serialize)]
struct EquipCapePayload {
    #[serde(rename = "capeId")]
    cape_id: String,
}

/// Equip or unequip a cape on official Mojang servers.
#[tauri::command]
pub async fn equip_mojang_cape(
    state: State<'_, AppState>,
    account_id: Option<String>,
    cape_id: Option<String>,
) -> Result<(), String> {
    let target_id = {
        let accounts = state.accounts.lock().unwrap();
        let acc = match account_id {
            Some(ref id) => accounts.iter().find(|a| &a.id == id),
            None => accounts.iter().find(|a| a.is_active),
        };
        match acc {
            Some(a) => {
                if a.account_type != "microsoft" {
                    return Err("Capes can only be equipped for Microsoft accounts.".to_string());
                }
                a.id.clone()
            }
            None => return Err("No active Microsoft account found.".to_string()),
        }
    };

    let (mut login_data, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
        .await
        .map_err(|e| format!("Authentication failed: {e}"))?;

    let client = reqwest::Client::builder()
        .build()
        .map_err(|e| e.to_string())?;

    if let Some(id) = cape_id {
        let payload = EquipCapePayload { cape_id: id.clone() };
        let mut resp = client
            .put("https://api.minecraftservices.com/minecraft/profile/capes/active")
            .header("Authorization", format!("Bearer {}", login_data.access_token))
            .json(&payload)
            .send()
            .await
            .map_err(|e| format!("Failed to equip cape: {e}"))?;

        if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
            state.msa_session_cache.lock().unwrap().remove(&target_id);
            let (refreshed, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
                .await
                .map_err(|e| format!("Authentication failed: {e}"))?;
            login_data = refreshed;
            resp = client
                .put("https://api.minecraftservices.com/minecraft/profile/capes/active")
                .header("Authorization", format!("Bearer {}", login_data.access_token))
                .json(&payload)
                .send()
                .await
                .map_err(|e| format!("Failed to equip cape: {e}"))?;
        }

        if resp.status().is_success() {
            Ok(())
        } else {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            Err(format!("Mojang server returned HTTP {status}: {body}"))
        }
    } else {
        let mut resp = client
            .delete("https://api.minecraftservices.com/minecraft/profile/capes/active")
            .header("Authorization", format!("Bearer {}", login_data.access_token))
            .send()
            .await
            .map_err(|e| format!("Failed to unequip cape: {e}"))?;

        if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
            state.msa_session_cache.lock().unwrap().remove(&target_id);
            let (refreshed, _) = crate::commands::msa::refresh_microsoft_login(&state, &target_id)
                .await
                .map_err(|e| format!("Authentication failed: {e}"))?;
            login_data = refreshed;
            resp = client
                .delete("https://api.minecraftservices.com/minecraft/profile/capes/active")
                .header("Authorization", format!("Bearer {}", login_data.access_token))
                .send()
                .await
                .map_err(|e| format!("Failed to unequip cape: {e}"))?;
        }

        if resp.status().is_success()
            || resp.status() == reqwest::StatusCode::NO_CONTENT
            || resp.status() == reqwest::StatusCode::NOT_FOUND
        {
            Ok(())
        } else {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            Err(format!("Mojang server returned HTTP {status}: {body}"))
        }
    }
}
