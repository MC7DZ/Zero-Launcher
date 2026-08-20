use std::path::PathBuf;
use std::error::Error as _;
use std::sync::OnceLock;
use std::time::Duration;
use serde::{Deserialize, Serialize};

const MODRINTH_API: &str = "https://api.modrinth.com/v2";
const USER_AGENT: &str = "ZeroLauncher/1.0 (discover-tab)";

// A single shared client (connection pooling + DNS cache) instead of a new
// one per call. `hickory-dns` (pure-Rust resolver, see Cargo.toml) is used
// instead of the OS resolver: on some Windows setups the async system
// resolver used by hyper's default GaiResolver intermittently fails with
// "failed to lookup address information" (firewall/AV interception, VPN
// split-tunnel DNS, IPv6-only misconfiguration, etc.) even though the OS
// itself can resolve the name fine for other apps.
fn modrinth_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .hickory_dns(true)
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            // See vendor/mc-launcher-core's http.rs client() for why:
            // forces IPv4 so a broken/non-routable IPv6 setup can't stall
            // requests waiting on a dead address before falling back.
            .local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

/// Runs a request-building closure with a couple of short retries. DNS
/// lookups/connects are the flakiest part of a request, so this absorbs a
/// transient failure instead of surfacing an error on the first hiccup.
async fn send_with_retry(
    build: impl Fn(&reqwest::Client) -> reqwest::RequestBuilder,
) -> Result<reqwest::Response, reqwest::Error> {
    let client = modrinth_client();
    let mut attempt = 0;
    loop {
        match build(client).send().await {
            Ok(resp) => return Ok(resp),
            Err(e) if attempt < 2 && (e.is_connect() || e.is_timeout()) => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(400 * attempt as u64)).await;
            }
            Err(e) => return Err(e),
        }
    }
}

// ── Search ──────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub follows: u64,
    pub project_type: String, // "mod" | "resourcepack"
    pub categories: Vec<String>,
    pub display_categories: Vec<String>,
    pub license: Option<String>,
    pub client_side: Option<String>, // "required" | "optional" | "unsupported"
    pub server_side: Option<String>,
    pub date_modified: Option<String>,
    pub latest_version: Option<String>,
    pub versions: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DiscoverSearchResult {
    pub hits: Vec<DiscoverHit>,
    pub total_hits: u64,
    pub offset: u64,
    pub limit: u64,
}

#[derive(Deserialize)]
struct RawSearchHit {
    project_id: String,
    slug: String,
    title: String,
    description: String,
    author: String,
    icon_url: Option<String>,
    downloads: u64,
    #[serde(default)]
    follows: u64,
    project_type: String,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    display_categories: Vec<String>,
    license: Option<String>,
    client_side: Option<String>,
    server_side: Option<String>,
    date_modified: Option<String>,
    latest_version: Option<String>,
    #[serde(default)]
    versions: Vec<String>,
}

#[derive(Deserialize)]
struct RawSearchResponse {
    hits: Vec<RawSearchHit>,
    total_hits: u64,
    offset: u64,
    limit: u64,
}

/// Search Modrinth for mods or resource packs, optionally filtered by loader,
/// Minecraft version, categories, client/server environment, license, and
/// open-source-only — mirrors the Discover tab's search bar and filter
/// dropdowns.
#[tauri::command]
pub async fn discover_search(
    query: String,
    project_type: String, // "mod" | "resourcepack"
    loader: Option<String>,
    game_version: Option<String>,
    categories: Option<Vec<String>>,
    environment: Option<String>, // "client" | "server"
    license: Option<String>,
    open_source_only: Option<bool>,
    page: u32,
    limit: u32,
) -> Result<DiscoverSearchResult, String> {
    let mut facets: Vec<Vec<String>> = vec![vec![format!("project_type:{}", project_type)]];
    if let Some(l) = loader.filter(|l| !l.is_empty() && l != "any") {
        facets.push(vec![format!("categories:{}", l.to_lowercase())]);
    }
    if let Some(v) = game_version.filter(|v| !v.is_empty()) {
        facets.push(vec![format!("versions:{}", v)]);
    }
    if let Some(cats) = categories.filter(|c| !c.is_empty()) {
        facets.push(
            cats.into_iter()
                .map(|c| format!("categories:{}", c.to_lowercase()))
                .collect(),
        );
    }
    if let Some(env) = environment.filter(|e| !e.is_empty() && e != "any") {
        let side = if env == "client" { "client_side" } else { "server_side" };
        facets.push(vec![format!("{side}:required"), format!("{side}:optional")]);
    }
    if let Some(lic) = license.filter(|l| !l.is_empty()) {
        facets.push(vec![format!("license:{}", lic)]);
    }
    if open_source_only.unwrap_or(false) {
        facets.push(vec!["open_source:true".to_string()]);
    }
    let facets_json = serde_json::to_string(&facets).map_err(|e| e.to_string())?;

    let offset = (page.saturating_sub(1)) as u64 * limit as u64;

    let resp = send_with_retry(|client| {
        client
            .get(format!("{MODRINTH_API}/search"))
            .query(&[
                ("query", query.as_str()),
                ("facets", facets_json.as_str()),
                ("limit", &limit.to_string()),
                ("offset", &offset.to_string()),
                ("index", "relevance"),
            ])
    })
    .await
    .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Modrinth search failed: HTTP {}", resp.status()));
    }

    let raw: RawSearchResponse = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse Modrinth response: {e}"))?;

    Ok(DiscoverSearchResult {
        hits: raw
            .hits
            .into_iter()
            .map(|h| DiscoverHit {
                project_id: h.project_id,
                slug: h.slug,
                title: h.title,
                description: h.description,
                author: h.author,
                icon_url: h.icon_url,
                downloads: h.downloads,
                follows: h.follows,
                project_type: h.project_type,
                categories: h.categories,
                display_categories: h.display_categories,
                license: h.license,
                client_side: h.client_side,
                server_side: h.server_side,
                date_modified: h.date_modified,
                latest_version: h.latest_version,
                versions: h.versions,
            })
            .collect(),
        total_hits: raw.total_hits,
        offset: raw.offset,
        limit: raw.limit,
    })
}

// ── Versions ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverFile {
    pub url: String,
    pub filename: String,
    pub primary: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverVersion {
    pub id: String,
    pub version_number: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<DiscoverFile>,
    #[serde(default)]
    pub dependencies: Vec<DiscoverDependency>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverDependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub dependency_type: String, // "required" | "optional" | "incompatible" | "embedded"
}

#[derive(Deserialize)]
struct RawFile {
    url: String,
    filename: String,
    primary: bool,
}

#[derive(Deserialize)]
struct RawDependency {
    project_id: Option<String>,
    version_id: Option<String>,
    dependency_type: String,
}

#[derive(Deserialize)]
struct RawVersion {
    id: String,
    version_number: String,
    game_versions: Vec<String>,
    loaders: Vec<String>,
    files: Vec<RawFile>,
    #[serde(default)]
    dependencies: Vec<RawDependency>,
}

/// List downloadable versions of a project, optionally narrowed to a loader
/// and Minecraft version (used to populate the version picker on a Discover
/// card before download).
#[tauri::command]
pub async fn discover_get_versions(
    project_id: String,
    loader: Option<String>,
    game_version: Option<String>,
) -> Result<Vec<DiscoverVersion>, String> {
    let url = format!("{MODRINTH_API}/project/{project_id}/version");
    let mut query: Vec<(&str, String)> = Vec::new();
    if let Some(l) = loader.filter(|l| !l.is_empty() && l != "any") {
        query.push(("loaders", format!("[\"{}\"]", l.to_lowercase())));
    }
    if let Some(v) = game_version.filter(|v| !v.is_empty()) {
        query.push(("game_versions", format!("[\"{v}\"]")));
    }

    let resp = send_with_retry(|client| {
        let mut req = client.get(&url);
        if !query.is_empty() {
            req = req.query(&query);
        }
        req
    })
    .await
    .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch versions: HTTP {}", resp.status()));
    }

    let raw: Vec<RawVersion> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse version list: {e}"))?;

    Ok(raw
        .into_iter()
        .map(|v| DiscoverVersion {
            id: v.id,
            version_number: v.version_number,
            game_versions: v.game_versions,
            loaders: v.loaders,
            files: v
                .files
                .into_iter()
                .map(|f| DiscoverFile {
                    url: f.url,
                    filename: f.filename,
                    primary: f.primary,
                })
                .collect(),
            dependencies: v
                .dependencies
                .into_iter()
                .map(|d| DiscoverDependency {
                    project_id: d.project_id,
                    version_id: d.version_id,
                    dependency_type: d.dependency_type,
                })
                .collect(),
        })
        .collect())
}

/// Resolve the best download URL + filename for a Modrinth project, filtered
/// by loader/game version when given. Used by the Presets tab to install a
/// preset's mods without going through the Discover UI. Prefers the primary
/// file of the newest matching version; returns `Ok(None)` if nothing
/// compatible was found (not an error — the caller treats this as "skip").
pub(crate) async fn modrinth_get_download_url(
    project_id: &str,
    loader: Option<&str>,
    game_version: Option<&str>,
) -> Result<Option<(String, String)>, String> {
    let url = format!("{MODRINTH_API}/project/{project_id}/version");
    let mut query: Vec<(&str, String)> = Vec::new();
    if let Some(l) = loader.filter(|l| !l.is_empty()) {
        query.push(("loaders", format!("[\"{}\"]", l.to_lowercase())));
    }
    if let Some(v) = game_version.filter(|v| !v.is_empty()) {
        query.push(("game_versions", format!("[\"{v}\"]")));
    }

    let resp = send_with_retry(|client| {
        let mut req = client.get(&url);
        if !query.is_empty() {
            req = req.query(&query);
        }
        req
    })
    .await
    .map_err(|e| format!("Failed to reach Modrinth: {e}"))?;

    if !resp.status().is_success() {
        // Loader/version filter matched nothing — treat as "not found"
        // rather than a hard error so the caller can skip this one mod.
        return Ok(None);
    }

    let raw: Vec<RawVersion> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse version list: {e}"))?;

    for v in raw {
        if let Some(f) = v.files.iter().find(|f| f.primary).or_else(|| v.files.first()) {
            return Ok(Some((f.url.clone(), f.filename.clone())));
        }
    }
    Ok(None)
}

/// Fetch a single Modrinth project's basic info by id/slug — used to resolve
/// a dependency's project_id into a name/title so it can be searched for
/// and matched against already-installed mods.
#[tauri::command]
pub async fn discover_get_project(project_id: String) -> Result<DiscoverHit, String> {
    let url = format!("{MODRINTH_API}/project/{project_id}");
    let resp = send_with_retry(|client| client.get(&url))
        .await
        .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch project: HTTP {}", resp.status()));
    }

    #[derive(Deserialize)]
    struct RawProject {
        id: String,
        slug: String,
        title: String,
        description: String,
        team: Option<String>,
        icon_url: Option<String>,
        downloads: Option<u64>,
        followers: Option<u64>,
        project_type: String,
        #[serde(default)]
        categories: Vec<String>,
        license: Option<RawProjectLicense>,
        client_side: Option<String>,
        server_side: Option<String>,
        #[serde(default)]
        versions: Vec<String>,
    }
    #[derive(Deserialize)]
    struct RawProjectLicense {
        id: String,
    }

    let raw: RawProject = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse project: {e}"))?;

    Ok(DiscoverHit {
        project_id: raw.id,
        slug: raw.slug,
        title: raw.title,
        description: raw.description,
        author: raw.team.unwrap_or_default(),
        icon_url: raw.icon_url,
        downloads: raw.downloads.unwrap_or(0),
        follows: raw.followers.unwrap_or(0),
        project_type: raw.project_type,
        categories: raw.categories.clone(),
        display_categories: Vec::new(),
        license: raw.license.map(|l| l.id),
        client_side: raw.client_side,
        server_side: raw.server_side,
        date_modified: None,
        latest_version: None,
        versions: raw.versions,
    })
}

// ── Mod identification by file hash ─────────────────────────────────────────
// Same method as the Java client's ModUpdateService.identifyMods: identify an
// installed mod jar on Modrinth by the exact SHA-1 hash of its bytes (via the
// `/version_files` batch endpoint) instead of a fuzzy text search on its
// display name. Hash lookups are exact, so a mod like "Cloth Config API" —
// whose name-based search could miss or mismatch — now resolves reliably.

#[derive(Debug, Clone, Serialize)]
pub struct ModHashLookup {
    pub project_id: Option<String>,
    pub version_number: Option<String>,
}

#[derive(Deserialize)]
struct RawVersionFileEntry {
    project_id: Option<String>,
    version_number: Option<String>,
}

/// Batch-identifies mod jars by SHA-1 hash. Returns a map of hash -> lookup
/// result for every hash Modrinth recognized; hashes with no match are simply
/// absent from the returned map (a genuine "not on Modrinth" miss).
#[tauri::command]
pub async fn identify_mods_by_hash(
    hashes: Vec<String>,
) -> Result<std::collections::HashMap<String, ModHashLookup>, String> {
    if hashes.is_empty() {
        return Ok(std::collections::HashMap::new());
    }

    let body = serde_json::json!({
        "hashes": hashes,
        "algorithm": "sha1",
    });

    let resp = send_with_retry(|client| {
        client
            .post(format!("{MODRINTH_API}/version_files"))
            .json(&body)
    })
    .await
    .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Modrinth version_files lookup failed: HTTP {}", resp.status()));
    }

    let raw: std::collections::HashMap<String, RawVersionFileEntry> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse version_files response: {e}"))?;

    Ok(raw
        .into_iter()
        .map(|(hash, v)| {
            (
                hash,
                ModHashLookup {
                    project_id: v.project_id,
                    version_number: v.version_number,
                },
            )
        })
        .collect())
}

#[derive(Debug, Clone, Serialize)]
pub struct DiscoverProjectSummary {
    pub id: String,
    pub title: String,
    pub icon_url: Option<String>,
    pub description: String,
}

#[derive(Deserialize)]
struct RawProjectSummary {
    id: String,
    title: String,
    icon_url: Option<String>,
    description: String,
}

/// Batch-fetches title/icon/description for a set of Modrinth project IDs —
/// same purpose as the Java client's fetchProjectNames (GET /projects?ids=).
/// Used to resolve mod icons after identify_mods_by_hash returns project IDs.
#[tauri::command]
pub async fn discover_get_projects_batch(
    ids: Vec<String>,
) -> Result<Vec<DiscoverProjectSummary>, String> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }

    let ids_json = serde_json::to_string(&ids).map_err(|e| e.to_string())?;

    let resp = send_with_retry(|client| {
        client
            .get(format!("{MODRINTH_API}/projects"))
            .query(&[("ids", ids_json.as_str())])
    })
    .await
    .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Modrinth projects lookup failed: HTTP {}", resp.status()));
    }

    let raw: Vec<RawProjectSummary> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse projects response: {e}"))?;

    Ok(raw
        .into_iter()
        .map(|p| DiscoverProjectSummary {
            id: p.id,
            title: p.title,
            icon_url: p.icon_url,
            description: p.description,
        })
        .collect())
}

// ── Download ────────────────────────────────────────────────────────────────

/// Downloads a chosen file into the given instance's directory: mods (.jar)
/// go into `mods/`, resource packs (.zip) go into `resourcepacks/`.
///
/// `download_id`, when provided, is the frontend-assigned id for this
/// download's card in the downloads menu. It's checked between chunks so
/// the transfer can be aborted mid-flight if the user hits Cancel — and is
/// checked once up front too, so a download that reuses an id already
/// marked cancelled (e.g. the next item in a batch after the user
/// cancelled) aborts immediately instead of starting.
#[tauri::command]
pub async fn discover_download(
    directory: String,
    project_type: String,
    file_url: String,
    file_name: String,
    download_id: Option<String>,
    state: tauri::State<'_, crate::state::AppState>,
) -> Result<String, String> {
    use std::io::Write;

    let base_dir = PathBuf::from(&directory);
    let target_dir = if project_type == "resourcepack" {
        base_dir.join("resourcepacks")
    } else {
        base_dir.join("mods")
    };

    std::fs::create_dir_all(&target_dir)
        .map_err(|e| format!("Failed to create target directory: {e}"))?;

    let cancel_flag = download_id
        .as_ref()
        .map(|id| state.generic_cancel_flag(id));

    let is_cancelled = || {
        cancel_flag
            .as_ref()
            .map(|f| f.load(std::sync::atomic::Ordering::Relaxed))
            .unwrap_or(false)
    };

    let cleanup = |state: &crate::state::AppState| {
        if let Some(id) = &download_id {
            state.finish_generic_download(id);
        }
    };

    if is_cancelled() {
        cleanup(&state);
        return Err("Download cancelled".to_string());
    }

    let mut resp = send_with_retry(|client| client.get(&file_url))
        .await
        .map_err(|e| {
            cleanup(&state);
            format!("Download failed: {e}")
        })?;

    if !resp.status().is_success() {
        let status = resp.status();
        cleanup(&state);
        return Err(format!("Download failed: HTTP {}", status));
    }

    let dest = target_dir.join(&file_name);
    let mut file = std::fs::File::create(&dest).map_err(|e| {
        cleanup(&state);
        format!("Failed to create file: {e}")
    })?;

    loop {
        if is_cancelled() {
            drop(file);
            let _ = std::fs::remove_file(&dest);
            cleanup(&state);
            return Err("Download cancelled".to_string());
        }
        match resp.chunk().await {
            Ok(Some(chunk)) => {
                if let Err(e) = file.write_all(&chunk) {
                    cleanup(&state);
                    return Err(format!("Failed to save file: {e}"));
                }
            }
            Ok(None) => break,
            Err(e) => {
                cleanup(&state);
                return Err(format!("Failed to read download: {e}"));
            }
        }
    }

    cleanup(&state);
    Ok(dest.to_string_lossy().to_string())
}

// ── Persistent icon cache ────────────────────────────────────────────────
// Mirrors the Java client's ModIconCache: icon bytes are saved to disk once
// (keyed by a hash of the source URL) and served straight from disk on every
// later call, instead of the frontend hitting a remote <img src> — and
// therefore Modrinth — again on every app launch/list re-render. That
// on-disk persistence (not just caching the resolved URL) is what actually
// fixes icons "bugging out": a previously-loaded icon now always renders
// instantly from local disk, even if Modrinth is slow/unreachable/rate-
// limiting at the moment a mod list re-renders.
#[tauri::command]
pub async fn cache_mod_icon(
    app: tauri::AppHandle,
    url: String,
) -> Result<String, String> {
    use std::hash::{Hash, Hasher};
    use tauri::Manager;

    if url.is_empty() {
        return Err("Empty icon URL".to_string());
    }

    let state = app.state::<crate::state::AppState>();
    let cache_dir = state.data_dir.join("cache").join("mod_icons");
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("Failed to create icon cache dir: {e}"))?;

    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    url.hash(&mut hasher);
    let hash = format!("{:016x}", hasher.finish());

    // Keep the original extension (falls back to .img) purely so the cached
    // file still opens fine in a file browser — the frontend doesn't care.
    let ext = url
        .rsplit('/')
        .next()
        .unwrap_or("")
        .rsplit('.')
        .next()
        .filter(|e| e.len() <= 5 && e.chars().all(|c| c.is_ascii_alphanumeric()))
        .unwrap_or("img");
    let file = cache_dir.join(format!("{hash}.{ext}"));

    // Cache hit — instant, no network involved.
    if file.exists() {
        if let Ok(meta) = std::fs::metadata(&file) {
            if meta.len() > 0 {
                return Ok(file.to_string_lossy().to_string());
            }
        }
    }

    // Cache miss — fetch once and persist so every later call is a hit.
    let resp = send_with_retry(|client| client.get(&url))
        .await
        .map_err(|e| format!("Failed to fetch icon: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch icon: HTTP {}", resp.status()));
    }

    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read icon: {e}"))?;

    std::fs::write(&file, &bytes).map_err(|e| format!("Failed to save icon: {e}"))?;

    Ok(file.to_string_lossy().to_string())
}

// ── Filter tag lookups (game versions / categories / licenses) ──────────────
// Populate the Discover tab's Game Version / Category / License dropdowns
// from Modrinth's own tag lists, instead of a hardcoded, quickly-stale set.

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverGameVersion {
    pub version: String,
    pub version_type: String, // "release" | "snapshot" | "alpha" | "beta"
}

#[derive(Deserialize)]
struct RawGameVersion {
    version: String,
    version_type: String,
}

/// Returns Minecraft release versions, newest first (snapshots/alpha/beta
/// omitted to keep the picker focused on what most players search for).
#[tauri::command]
pub async fn discover_get_game_versions() -> Result<Vec<DiscoverGameVersion>, String> {
    let resp = send_with_retry(|client| client.get(format!("{MODRINTH_API}/tag/game_version")))
        .await
        .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch game versions: HTTP {}", resp.status()));
    }

    let raw: Vec<RawGameVersion> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse game version list: {e}"))?;

    Ok(raw
        .into_iter()
        .filter(|v| v.version_type == "release")
        .map(|v| DiscoverGameVersion { version: v.version, version_type: v.version_type })
        .collect())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverCategory {
    pub name: String,
    pub project_type: String,
    pub header: String,
}

#[derive(Deserialize)]
struct RawCategory {
    name: String,
    project_type: String,
    header: String,
}

/// Returns content categories (not loaders) for the given project type, in
/// the order Modrinth itself groups them under (its `header` field).
#[tauri::command]
pub async fn discover_get_categories(project_type: String) -> Result<Vec<DiscoverCategory>, String> {
    let resp = send_with_retry(|client| client.get(format!("{MODRINTH_API}/tag/category")))
        .await
        .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch categories: HTTP {}", resp.status()));
    }

    let raw: Vec<RawCategory> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse category list: {e}"))?;

    // Loaders (fabric/forge/etc.) are technically returned under "resolutions"/
    // other headers with project_type mod too, but the loader picker already
    // covers those separately — exclude the known loader header if present.
    Ok(raw
        .into_iter()
        .filter(|c| c.project_type == project_type && c.header != "resolutions" && c.header != "performance impact")
        .map(|c| DiscoverCategory { name: c.name, project_type: c.project_type, header: c.header })
        .collect())
}

/// Returns resolution tags (16x-32x, 32x-48x, etc.) for the given project
/// type. On Modrinth these are just regular categories grouped under a
/// "resolutions" header for display, which `discover_get_categories`
/// deliberately excludes — this is the counterpart that returns *only*
/// that header, for resourcepacks' dedicated Resolution filter. The values
/// this returns are still plain category names, so they're sent to
/// `discover_search`'s `categories` facet the same way regular categories
/// are.
#[tauri::command]
pub async fn discover_get_resolutions(project_type: String) -> Result<Vec<DiscoverCategory>, String> {
    let resp = send_with_retry(|client| client.get(format!("{MODRINTH_API}/tag/category")))
        .await
        .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch categories: HTTP {}", resp.status()));
    }

    let raw: Vec<RawCategory> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse category list: {e}"))?;

    Ok(raw
        .into_iter()
        .filter(|c| c.project_type == project_type && c.header == "resolutions")
        .map(|c| DiscoverCategory { name: c.name, project_type: c.project_type, header: c.header })
        .collect())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoverLicense {
    pub short: String,
    pub name: String,
}

#[derive(Deserialize)]
struct RawLicense {
    short: String,
    name: String,
}

/// Returns Modrinth's known SPDX license list for the License filter.
#[tauri::command]
pub async fn discover_get_licenses() -> Result<Vec<DiscoverLicense>, String> {
    let resp = send_with_retry(|client| client.get(format!("{MODRINTH_API}/tag/license")))
        .await
        .map_err(|e| format!("Failed to reach Modrinth: {e} (source: {:?})", e.source()))?;

    if !resp.status().is_success() {
        return Err(format!("Failed to fetch licenses: HTTP {}", resp.status()));
    }

    let raw: Vec<RawLicense> = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse license list: {e}"))?;

    Ok(raw
        .into_iter()
        .map(|l| DiscoverLicense { short: l.short, name: l.name })
        .collect())
}
