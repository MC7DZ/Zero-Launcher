use std::fs::File;
use std::io::Write;
use std::path::Path;
use futures_util::StreamExt;
use serde::Deserialize;

const MANIFEST_URL: &str = "https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/version.json";
const GITHUB_REPO_LATEST_API: &str = "https://api.github.com/repos/MC7DZ/ZeroLauncher-Updates/releases/latest";

#[derive(Debug, Deserialize)]
pub struct OsUpdateEntry {
    pub version: String,
    pub url: String,
    #[allow(dead_code)]
    pub size_mb: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateManifest {
    #[allow(dead_code)]
    pub windows: Option<OsUpdateEntry>,
    #[allow(dead_code)]
    pub linux: Option<OsUpdateEntry>,
}

#[derive(Debug, Clone)]
pub struct ReleaseInfo {
    pub version: String,
    pub download_url: String,
    pub filename: String,
}

pub async fn resolve_latest_release() -> Result<ReleaseInfo, String> {
    let client = reqwest::Client::builder()
        .user_agent("ZeroLauncher-Setup")
        .build()
        .map_err(|e| e.to_string())?;

    // Try manifest first
    if let Ok(resp) = client.get(MANIFEST_URL).send().await {
        if resp.status().is_success() {
            if let Ok(manifest) = resp.json::<UpdateManifest>().await {
                #[cfg(target_os = "windows")]
                if let Some(entry) = manifest.windows {
                    return Ok(ReleaseInfo {
                        version: entry.version,
                        download_url: entry.url,
                        filename: "ZeroLauncher.exe".into(),
                    });
                }

                #[cfg(not(target_os = "windows"))]
                if let Some(entry) = manifest.linux {
                    return Ok(ReleaseInfo {
                        version: entry.version,
                        download_url: entry.url,
                        filename: "ZeroLauncher.AppImage".into(),
                    });
                }
            }
        }
    }

    // Fallback: GitHub Releases API
    let resp = client.get(GITHUB_REPO_LATEST_API).send().await
        .map_err(|e| format!("Could not reach update server: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("Update check returned status {}", resp.status()));
    }

    let json: serde_json::Value = resp.json().await
        .map_err(|e| format!("Failed to parse release JSON: {e}"))?;

    let tag = json.get("tag_name").and_then(|v| v.as_str()).unwrap_or("latest").to_string();
    let assets = json.get("assets").and_then(|v| v.as_array()).ok_or("No release assets found")?;

    #[cfg(target_os = "windows")]
    {
        for asset in assets {
            let name = asset.get("name").and_then(|v| v.as_str()).unwrap_or("");
            if name.ends_with(".exe") {
                let url = asset.get("browser_download_url").and_then(|v| v.as_str()).unwrap_or("");
                return Ok(ReleaseInfo {
                    version: tag,
                    download_url: url.into(),
                    filename: "ZeroLauncher.exe".into(),
                });
            }
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        for asset in assets {
            let name = asset.get("name").and_then(|v| v.as_str()).unwrap_or("");
            if name.ends_with(".AppImage") {
                let url = asset.get("browser_download_url").and_then(|v| v.as_str()).unwrap_or("");
                return Ok(ReleaseInfo {
                    version: tag,
                    download_url: url.into(),
                    filename: "ZeroLauncher.AppImage".into(),
                });
            }
        }
    }

    Err("Could not find a compatible launcher binary in latest release".into())
}

pub async fn download_file_with_progress<F>(
    url: &str,
    destination: &Path,
    mut on_progress: F,
) -> Result<(), String>
where
    F: FnMut(u64, Option<u64>),
{
    let client = reqwest::Client::builder()
        .user_agent("ZeroLauncher-Setup")
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client.get(url).send().await.map_err(|e| format!("Failed to send download request: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Download failed with HTTP {}", resp.status()));
    }

    let total_size = resp.content_length();
    let mut downloaded: u64 = 0;

    let part_path = destination.with_extension("downloading");
    let mut file = File::create(&part_path).map_err(|e| format!("Failed to create output file: {e}"))?;

    let mut stream = resp.bytes_stream();
    while let Some(chunk_res) = stream.next().await {
        let chunk = chunk_res.map_err(|e| format!("Download stream error: {e}"))?;
        file.write_all(&chunk).map_err(|e| format!("File write error: {e}"))?;
        downloaded += chunk.len() as u64;
        on_progress(downloaded, total_size);
    }

    file.flush().map_err(|e| format!("Failed to flush file: {e}"))?;
    drop(file);

    // Make executable on Unix
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = std::fs::metadata(&part_path) {
            let mut perm = meta.permissions();
            perm.set_mode(0o755);
            let _ = std::fs::set_permissions(&part_path, perm);
        }
    }

    if destination.exists() {
        let _ = std::fs::remove_file(destination);
    }

    std::fs::rename(&part_path, destination).map_err(|e| format!("Failed to finalize downloaded binary: {e}"))?;

    Ok(())
}
