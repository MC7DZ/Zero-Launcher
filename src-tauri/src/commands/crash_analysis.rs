//! Turns the raw console output of a crashed instance into a human-readable
//! diagnosis plus a list of one-click fixes, shown by the frontend's crash
//! dialog. Entirely offline/heuristic — no network calls — so it works the
//! moment the game exits, using pattern matching against known messages
//! from Fabric, Quilt, Forge, and NeoForge, plus the installed mod list.
use crate::commands::mods::list_mods_in_dir;
use crate::models::ModInfo;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrashFix {
    /// "disable_mod" | "delete_mod" | "install_mod" | "update_mod" | "delete_folder" | "open_url" | "increase_memory" | "info"
    pub kind: String,
    pub label: String,
    pub detail: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mod_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mod_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub folder: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrashReport {
    pub version_id: String,
    pub instance_name: String,
    pub title: String,
    pub category: String,
    pub signature: String,
    pub fixes: Vec<CrashFix>,
}

/// Returns `None` if this doesn't look like an actual crash (clean exit,
/// user closed the window normally, etc.) — the caller should fall back to
/// its regular "game exited" message in that case.
pub fn analyze(
    version_id: &str,
    instance_name: &str,
    game_dir: &PathBuf,
    loader: &str,
    exit_code: Option<i32>,
    log_lines: &[String],
) -> Option<CrashReport> {
    let text = log_lines.join("\n");
    let text_lower = text.to_lowercase();

    let looks_like_crash = matches!(exit_code, Some(c) if c != 0)
        || text_lower.contains("minecraft crash report")
        || text_lower.contains("exception in thread")
        || text_lower.contains("unhandled exception")
        || text_lower.contains("a fatal error has been detected")
        || text_lower.contains("loading crashed");

    if !looks_like_crash {
        return None;
    }

    let mods = list_mods_in_dir(game_dir);
    let enabled_mods: Vec<&ModInfo> = mods.iter().filter(|m| m.enabled).collect();

    // 1. Duplicate mods — two enabled jars that are clearly the same mod
    //    (same cleaned name, or one is an "(1)"/"copy" duplicate filename).
    if let Some(fixes) = find_duplicate_mods(&enabled_mods) {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Duplicate mod detected".to_string(),
            category: "duplicate_mod".to_string(),
            signature: extract_signature(&text, 6),
            fixes,
        });
    }

    // 2. Missing/incompatible dependency (Fabric/Quilt/Forge/NeoForge all
    //    print fairly recognizable "missing dependency" blocks).
    if let Some(mut fixes) = find_missing_dependencies(&text) {
        let title = match fixes.len() {
            1 => format!("Missing dependency: {}", fixes[0].mod_name.clone().unwrap_or_default()),
            n => format!("{n} missing dependencies"),
        };
        // The same "potential solution" block sometimes also asks to bump
        // Minecraft itself (e.g. "Replace 'Minecraft' 26.1 with version
        // 26.2.") alongside the missing mods — surface that too instead of
        // silently dropping it.
        if let Some(mc_fix) = find_minecraft_version_mismatch(&text) {
            fixes.push(mc_fix);
        }
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title,
            category: "missing_dependency".to_string(),
            signature: extract_signature(&text, 8),
            fixes,
        });
    }

    // 3. Out of memory.
    if text_lower.contains("outofmemoryerror") || text_lower.contains("java heap space") {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Ran out of memory".to_string(),
            category: "out_of_memory".to_string(),
            signature: extract_signature(&text, 4),
            fixes: vec![CrashFix {
                kind: "increase_memory".to_string(),
                label: "Allocate more RAM to this instance".to_string(),
                detail: "The JVM ran out of heap space. Raising the max memory in Settings → Java usually fixes this — try adding 1-2 GB more than it currently has.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: None,
                url: None,
            }],
        });
    }

    // 3b. Missing `xrandr` binary — LWJGL2 (used by every pre-1.13
    //    version, e.g. 1.8.9/1.12.2 Forge) shells out to the `xrandr`
    //    command to enumerate display modes on Linux, even under Wayland
    //    (via XWayland). When it's not installed, that call returns
    //    nothing and LWJGL2 throws exactly this signature. Most Wayland
    //    desktops don't ship `xrandr` by default, so this is by far the
    //    most common reason an old instance won't launch there. This is
    //    normally caught before launch even starts (see `launch_minecraft`),
    //    but stays here too as a fallback for anything that reaches the
    //    game process anyway.
    if text.contains("LinuxDisplay.getAvailableDisplayModes") && text_lower.contains("arrayindexoutofboundsexception") {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Missing `xrandr` — required by this Minecraft version".to_string(),
            category: "missing_xrandr".to_string(),
            signature: extract_signature(&text, 6),
            fixes: vec![CrashFix {
                kind: "info".to_string(),
                label: "Install the `xrandr` package".to_string(),
                detail: "This version uses LWJGL2, which calls the `xrandr` command-line tool to list display modes — even under Wayland, via XWayland. If `xrandr` isn't installed, it crashes immediately with this error.\n\nInstall it for your distribution:\n\u{2022} Arch / CachyOS / Manjaro: sudo pacman -S xorg-xrandr\n\u{2022} Debian / Ubuntu / Linux Mint: sudo apt install x11-xserver-utils\n\u{2022} Fedora / RHEL / CentOS: sudo dnf install xrandr\n\u{2022} openSUSE: sudo zypper install xrandr\n\u{2022} Alpine Linux: sudo apk add xrandr\n\u{2022} Void Linux: sudo xbps-install -S xrandr\n\nThis isn't a launcher or install problem — no reinstall needed.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: None,
                url: None,
            }],
        });
    }

    // 3c. Forge missing generated processor libraries / zlib-ng checksum failure.
    //     ModLauncher fails when local processor jars (srg.jar, extra.jar, client.jar)
    //     were never generated due to Forge installer failing on zlib-ng distros.
    if text.contains("Invalid paths argument, contained no existing paths")
        && (text.contains("-srg.jar") || text.contains("-extra.jar") || text.contains("-client.jar"))
    {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Incomplete Forge Installation (Missing Generated Libraries)".to_string(),
            category: "corrupted_forge_install".to_string(),
            signature: extract_signature(&text, 6),
            fixes: vec![CrashFix {
                kind: "info".to_string(),
                label: "Fix zlib conflict and re-install Forge".to_string(),
                detail: "Forge is missing generated internal libraries because the installer failed during setup. On Linux distributions using `zlib-ng` (like Arch Linux, CachyOS, or Fedora), Forge's installer aborts on checksum checks.\n\nTo resolve this on your Linux distribution:\n\u{2022} Arch / CachyOS / Manjaro: sudo pacman -S zlib lib32-zlib (accept replacing zlib-ng)\n\u{2022} Debian / Ubuntu / Linux Mint: sudo apt install zlib1g zlib1g:i386\n\u{2022} Fedora / RHEL / CentOS: sudo dnf install zlib zlib.i686\n\u{2022} openSUSE: sudo zypper install libz1 libz1-32bit\n\u{2022} Alpine Linux: sudo apk add zlib\n\u{2022} Void Linux: sudo xbps-install -S zlib\n\nAfter installing standard zlib, delete and re-create this Forge instance.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: None,
                url: None,
            }],
        });
    }

    // 4a. Mixin/class-loading failures where the log itself names the mod
    //     responsible (Fabric prints "... from mod <id>" on mixin-target
    //     lines). This looks superficially like the generic "stale cache"
    //     symptom below (ClassNotFoundException, Mixin target not found),
    //     but when the offending mod is named explicitly it's almost always
    //     that mod being built against a different Minecraft version's
    //     internal class/mapping names — deleting the cache won't fix that,
    //     it just regenerates the same failure next launch. Check this
    //     first so named cases don't get misdiagnosed as cache corruption.
    let runtime_incompatible = find_runtime_incompatible_mods(&text, &enabled_mods);
    if !runtime_incompatible.is_empty() {
        let fixes: Vec<CrashFix> = runtime_incompatible
            .iter()
            .flat_map(|m| {
                vec![
                    CrashFix {
                        kind: "disable_mod".to_string(),
                        label: format!("Disable {}", m.name),
                        detail: format!(
                            "\"{}\" references game classes that don't exist in this Minecraft version — it's likely built for a different version.",
                            m.file_name
                        ),
                        mod_path: Some(m.path.clone()),
                        mod_name: Some(m.name.clone()),
                        folder: None,
                        url: None,
                    },
                    CrashFix {
                        kind: "update_mod".to_string(),
                        label: format!("Check for an update to {}", m.name),
                        detail: "Looks up a version matching your Minecraft/loader version on Modrinth and installs it, replacing the current file.".to_string(),
                        mod_path: Some(m.path.clone()),
                        mod_name: Some(m.name.clone()),
                        folder: None,
                        url: Some(format!("https://modrinth.com/mods?q={}", urlencoding_lite(&m.name))),
                    },
                ]
            })
            .collect();
        let title = match runtime_incompatible.len() {
            1 => format!("\"{}\" is likely built for a different Minecraft version", runtime_incompatible[0].name),
            n => format!("{n} mods are likely built for a different Minecraft version"),
        };
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title,
            category: "mod_version_mismatch".to_string(),
            signature: extract_signature(&text, 6),
            fixes,
        });
    }

    // 4b. Known "stale cache" weirdness — corrupted remap/mixin caches that
    //    are fixed by deleting a specific folder so the loader regenerates
    //    it on next launch. This is the classic ".fabric folder" fix. Only
    //    reached once 4a has ruled out a specifically-named culprit mod.
    if let Some(fixes) = find_stale_cache_fix(&text_lower, loader, game_dir) {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Corrupted mod loader cache".to_string(),
            category: "corrupted_cache".to_string(),
            signature: extract_signature(&text, 6),
            fixes,
        });
    }

    // 5a. Fabric/Quilt print their own suggested fix when the whole pack
    //     just needs a different Minecraft version, e.g.:
    //       "Replace 'Minecraft' (minecraft) 26.1 with version 26.2."
    //     When present, that's the real fix — surfacing 40+ "disable mod"
    //     buttons instead (case 5b below) is technically accurate but not
    //     what the user should actually do.
    if let Some(fix) = find_minecraft_version_mismatch(&text) {
        // Also offer the "disable everything that's incompatible" path as
        // a fallback for anyone who doesn't want to bump their Minecraft
        // version (e.g. it's pinned by a modpack) — reuses the same
        // per-mod fixes as case 5b so the frontend groups them into one
        // "disable all" card underneath the primary suggestion.
        let mut fixes = vec![fix];
        let incompatible = find_incompatible_mods(&text, &enabled_mods);
        fixes.extend(incompatible.iter().map(|m| CrashFix {
            kind: "disable_mod".to_string(),
            label: format!("Disable {}", m.name),
            detail: format!(
                "\"{}\" appears incompatible with this Minecraft/loader version.",
                m.file_name
            ),
            mod_path: Some(m.path.clone()),
            mod_name: Some(m.name.clone()),
            folder: None,
            url: None,
        }));

        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Wrong Minecraft version for these mods".to_string(),
            category: "minecraft_version_mismatch".to_string(),
            signature: extract_signature(&text, 8),
            fixes,
        });
    }

    // 5b. Multiple mods that look incompatible with this Minecraft/loader
    //    version (as opposed to a single mod crashing — that's handled by
    //    the culprit-mod search below). Anything mentioned near the word
    //    "incompatible" (or Fabric's per-mod "wrong version" phrasing)
    //    counts; the frontend groups these into one list instead of a
    //    separate card per mod.
    let incompatible = find_incompatible_mods(&text, &enabled_mods);
    if incompatible.len() >= 2 {
        let fixes: Vec<CrashFix> = incompatible
            .iter()
            .map(|m| CrashFix {
                kind: "disable_mod".to_string(),
                label: format!("Disable {}", m.name),
                detail: format!(
                    "\"{}\" appears incompatible with this Minecraft/loader version.",
                    m.file_name
                ),
                mod_path: Some(m.path.clone()),
                mod_name: Some(m.name.clone()),
                folder: None,
                url: None,
            })
            .collect();
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: "Incompatible mods detected".to_string(),
            category: "incompatible_mods".to_string(),
            signature: extract_signature(&text, 8),
            fixes,
        });
    }

    // 6. Try to blame a specific mod by looking for its name/id in the
    //    stack trace, and offer to disable it or check for an update.
    if let Some((culprit, fixes)) = find_culprit_mod(&text, &enabled_mods) {
        return Some(CrashReport {
            version_id: version_id.to_string(),
            instance_name: instance_name.to_string(),
            title: format!("\"{culprit}\" likely caused this crash"),
            category: "mod_crash".to_string(),
            signature: extract_signature(&text, 8),
            fixes,
        });
    }

    // 7. Fallback: generic crash, no specific culprit identified.
    Some(CrashReport {
        version_id: version_id.to_string(),
        instance_name: instance_name.to_string(),
        title: "The game crashed".to_string(),
        category: "unknown".to_string(),
        signature: extract_signature(&text, 10),
        fixes: vec![
            CrashFix {
                kind: "info".to_string(),
                label: "No specific cause identified".to_string(),
                detail: "Try disabling recently added mods one at a time, or check the full log for the exact error.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: None,
                url: None,
            },
            CrashFix {
                kind: "open_url".to_string(),
                label: "Search this error online".to_string(),
                detail: "Opens a web search for the crash signature above.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: None,
                url: Some(format!(
                    "https://www.google.com/search?q={}",
                    urlencoding_lite(&first_meaningful_line(&text))
                )),
            },
        ],
    })
}

/// Grabs the most relevant chunk of the log to show as the crash "signature"
/// — prefers lines around "Caused by"/"Exception" since that's usually the
/// real error, falling back to the last few lines.
fn extract_signature(text: &str, max_lines: usize) -> String {
    let lines: Vec<&str> = text.lines().collect();
    if let Some(idx) = lines.iter().position(|l| {
        let l = l.to_lowercase();
        l.contains("caused by") || l.contains("exception") || l.contains("crash report")
    }) {
        let start = idx.saturating_sub(1);
        let end = (start + max_lines).min(lines.len());
        return lines[start..end].join("\n");
    }
    let start = lines.len().saturating_sub(max_lines);
    lines[start..].join("\n")
}

fn first_meaningful_line(text: &str) -> String {
    text.lines()
        .find(|l| l.to_lowercase().contains("exception") || l.to_lowercase().contains("error"))
        .unwrap_or("Minecraft crash")
        .chars()
        .take(120)
        .collect()
}

fn urlencoding_lite(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_alphanumeric() { c.to_string() } else { "+".to_string() })
        .collect::<String>()
}

/// Strips version numbers/suffixes so "sodium-0.5.8.jar" and
/// "sodium-0.5.11.jar" are recognized as the same mod.
fn normalized_mod_key(m: &ModInfo) -> String {
    let base = m
        .file_name
        .trim_end_matches(".jar.disabled")
        .trim_end_matches(".jar")
        .to_lowercase();
    // Drop a trailing "-<version>" / "_<version>"-looking segment and any
    // " (1)", " copy" duplicate-file suffix a file manager might add.
    let no_copy = base
        .replace(" (1)", "")
        .replace(" (2)", "")
        .replace("-copy", "")
        .replace(" copy", "");
    let mut parts: Vec<&str> = no_copy.split(|c| c == '-' || c == '_').collect();
    while let Some(last) = parts.last() {
        if last.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
            parts.pop();
        } else {
            break;
        }
    }
    if parts.is_empty() {
        no_copy
    } else {
        parts.join("-")
    }
}

fn find_duplicate_mods(enabled_mods: &[&ModInfo]) -> Option<Vec<CrashFix>> {
    use std::collections::HashMap;
    let mut groups: HashMap<String, Vec<&ModInfo>> = HashMap::new();
    for m in enabled_mods {
        groups.entry(normalized_mod_key(m)).or_default().push(*m);
    }
    for (_, group) in groups {
        if group.len() < 2 {
            continue;
        }
        // Keep whichever has the "highest" version string lexically as a
        // rough heuristic, offer to remove the other(s).
        let mut sorted = group.clone();
        sorted.sort_by(|a, b| b.version.cmp(&a.version));
        let keep = sorted[0];
        let mut fixes = Vec::new();
        for dup in &sorted[1..] {
            fixes.push(CrashFix {
                kind: "delete_mod".to_string(),
                label: format!("Remove duplicate: {}", dup.file_name),
                detail: format!(
                    "\"{}\" and \"{}\" both provide the same mod. Keeping the newer one ({}) and removing this one usually fixes duplicate-mod-id crashes.",
                    dup.file_name, keep.file_name, keep.version
                ),
                mod_path: Some(dup.path.clone()),
                mod_name: Some(dup.name.clone()),
                folder: None,
                url: None,
            });
        }
        if !fixes.is_empty() {
            return Some(fixes);
        }
    }
    None
}

/// Finds every mod the loader says still needs installing and returns one
/// fix per mod (as opposed to the old version, which stopped at the first
/// match) — real logs regularly list several missing dependencies at once,
/// e.g. Fabric's "potential solution" block:
///   - Install sodium, version 0.9.0 or later.
///   - Install cloth-config, any version.
///   - Install fabric-api, any version.
/// Each fix offers both a Modrinth search link and a one-click auto-install
/// (frontend resolves `mod_name` against Modrinth using the instance's
/// loader/game version and downloads the best match — see `install_mod` in
/// applyCrashFix on the JS side).
fn find_missing_dependencies(text: &str) -> Option<Vec<CrashFix>> {
    let mut fixes: Vec<CrashFix> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    // Preferred source: Fabric/Quilt's own "potential solution" lines,
    // which list every missing mod by id in one place, e.g.:
    //   " - Install sodium, version 0.9.0 or later."
    //   " - Install cloth-config, any version."
    for line in text.lines() {
        let l = line.trim();
        if !l.starts_with('-') {
            continue;
        }
        let ll = l.to_lowercase();
        if !ll.starts_with("- install") {
            continue;
        }
        let after = l["- install".len()..].trim_start_matches(':').trim();
        let (modid, version_desc) = match after.split_once(',') {
            Some((id, rest)) => (id.trim(), rest.trim().trim_end_matches('.').trim()),
            None => (after.trim_end_matches('.').trim(), ""),
        };
        if modid.is_empty() || !seen.insert(modid.to_lowercase()) {
            continue;
        }
        let version_desc = if version_desc.is_empty() { "any version" } else { version_desc };
        fixes.push(CrashFix {
            kind: "install_mod".to_string(),
            label: format!("Find & install \"{modid}\""),
            detail: format!(
                "This modpack needs \"{modid}\" ({version_desc}) installed as well, but it wasn't found in the mods folder."
            ),
            mod_path: None,
            mod_name: Some(modid.to_string()),
            folder: None,
            url: Some(format!("https://modrinth.com/mods?q={}", urlencoding_lite(modid))),
        });
    }
    if !fixes.is_empty() {
        return Some(fixes);
    }

    // Fallback: older/less structured phrasings, one match at a time —
    // these loaders don't print a clean list like Fabric's, so we can only
    // reliably pull whichever single mention appears first.
    for line in text.lines() {
        let l = line.trim();
        let ll = l.to_lowercase();

        if ll.contains("which is missing") || ll.contains("requires") && ll.contains("missing") {
            if let Some(name) = extract_between(l, "requires", ", which is missing")
                .or_else(|| extract_between(l, "requires", "which is missing"))
            {
                let clean = name
                    .trim()
                    .trim_start_matches("any version of")
                    .trim_start_matches("version")
                    .trim()
                    .to_string();
                return Some(vec![CrashFix {
                    kind: "install_mod".to_string(),
                    label: format!("Find & install \"{clean}\""),
                    detail: format!("This modpack needs \"{clean}\" installed as well, but it wasn't found in the mods folder."),
                    mod_path: None,
                    mod_name: Some(clean.clone()),
                    folder: None,
                    url: Some(format!("https://modrinth.com/mods?q={}", urlencoding_lite(&clean))),
                }]);
            }
        }

        if ll.contains("actual version:") && ll.contains("missing") {
            if let Some(modid) = extract_between(l, "Mod ID: '", "'") {
                return Some(vec![CrashFix {
                    kind: "install_mod".to_string(),
                    label: format!("Find & install \"{modid}\""),
                    detail: "A required dependency mod is missing from the mods folder.".to_string(),
                    mod_path: None,
                    mod_name: Some(modid.clone()),
                    folder: None,
                    url: Some(format!("https://modrinth.com/mods?q={}", urlencoding_lite(&modid))),
                }]);
            }
        }
    }
    None
}

fn extract_between(s: &str, start: &str, end: &str) -> Option<String> {
    let start_lower = s.to_lowercase();
    let start_marker = start.to_lowercase();
    let sidx = start_lower.find(&start_marker)? + start.len();
    let rest = &s[sidx..];
    let eidx = rest.to_lowercase().find(&end.to_lowercase())?;
    Some(rest[..eidx].to_string())
}

/// Finds enabled mods explicitly named as the source of a Mixin failure,
/// e.g.:
///   "@Mixin target ... was not found c2me-opts-dfc.mixins.json:... from mod c2me-opts-dfc"
/// Fabric appends "from mod <id>" to these lines whenever it can attribute
/// the failing mixin to a specific mod — when present, that's a far more
/// reliable signal than the generic stale-cache heuristic, since it names
/// the exact culprit instead of guessing.
fn find_runtime_incompatible_mods<'a>(text: &str, enabled_mods: &[&'a ModInfo]) -> Vec<&'a ModInfo> {
    let mut found: Vec<&ModInfo> = Vec::new();
    for line in text.lines() {
        let ll = line.to_lowercase();
        if !(ll.contains("was not found") && ll.contains("from mod ")) {
            continue;
        }
        let Some(idx) = ll.rfind("from mod ") else { continue };
        let named_id = ll[idx + "from mod ".len()..].trim();
        if named_id.len() < 2 {
            continue;
        }
        for m in enabled_mods {
            let slug = mod_id_slug(&m.file_name);
            let is_match = named_id == slug
                || (slug.len() >= 3 && (named_id.contains(&slug) || slug.contains(named_id)));
            if is_match && !found.iter().any(|f| f.file_name == m.file_name) {
                found.push(*m);
            }
        }
    }
    found
}

/// Known "regenerate this folder" fixes for weird, hard-to-explain loader
/// bugs — the classic example being Fabric's `.fabric` remap-cache folder
/// getting corrupted after a mod/Java update and causing bizarre
/// `NoSuchMethodError`/`ClassNotFoundException`/Mixin crashes that have
/// nothing to do with the mod's actual code.
fn find_stale_cache_fix(text_lower: &str, loader: &str, game_dir: &PathBuf) -> Option<Vec<CrashFix>> {
    let looks_stale = text_lower.contains("mixin apply failed")
        || text_lower.contains("mixinapplicatorstandard")
        || text_lower.contains("nosuchmethoderror")
        || text_lower.contains("noclassdeffounderror")
        || text_lower.contains("failed to remap");

    if !looks_stale {
        return None;
    }

    let loader_lower = loader.to_lowercase();
    let mut fixes = Vec::new();

    if loader_lower.contains("fabric") || loader_lower.contains("quilt") {
        let fabric_cache = game_dir.join(".fabric");
        if fabric_cache.exists() {
            fixes.push(CrashFix {
                kind: "delete_folder".to_string(),
                label: "Delete the .fabric cache folder".to_string(),
                detail: "Fabric Loader caches remapped mod classes in a `.fabric` folder. After a Minecraft/Java/mod update this cache can go stale and cause weird crashes unrelated to any mod's actual code. Deleting it is safe — Fabric rebuilds it automatically on next launch.".to_string(),
                mod_path: None,
                mod_name: None,
                folder: Some(".fabric".to_string()),
                url: None,
            });
        }
    }

    let mixin_out = game_dir.join(".mixin.out");
    if mixin_out.exists() {
        fixes.push(CrashFix {
            kind: "delete_folder".to_string(),
            label: "Delete the .mixin.out debug folder".to_string(),
            detail: "Leftover Mixin debug output can occasionally confuse the loader after a crash. Safe to delete; it's regenerated as needed.".to_string(),
            mod_path: None,
            mod_name: None,
            folder: Some(".mixin.out".to_string()),
            url: None,
        });
    }

    if fixes.is_empty() {
        None
    } else {
        Some(fixes)
    }
}

/// Looks for Fabric/Quilt's own "potential solution" line, e.g.:
///   " - Replace 'Minecraft' (minecraft) 26.1 with version 26.2."
/// This is printed whenever the loader figures out that swapping one
/// component's version (almost always Minecraft itself) would resolve
/// every dependency conflict at once — a much better fix than disabling
/// every mod that mentioned it.
fn find_minecraft_version_mismatch(text: &str) -> Option<CrashFix> {
    for line in text.lines() {
        let l = line.trim();
        if !l.starts_with('-') || !l.to_lowercase().contains("replace") {
            continue;
        }
        // "Replace 'Minecraft' (minecraft) 26.1 with version 26.2."
        let name = extract_between(l, "Replace '", "'")?;
        let after_name = {
            let idx = l.find("with version")?;
            l[idx + "with version".len()..].trim().trim_end_matches('.').to_string()
        };
        let from_version = {
            // Between the closing paren of the id and " with version"
            let idx_close = l.find(')')?;
            let idx_with = l.find("with version")?;
            l[idx_close + 1..idx_with].trim().to_string()
        };
        return Some(CrashFix {
            kind: "info".to_string(),
            label: format!("Update {name} to {after_name}"),
            detail: format!(
                "Every incompatible mod in this pack requires {name} {after_name}, but this instance is running {name} {from_version}. Change the Minecraft version in this instance's settings to {after_name} instead of disabling mods — they should all work once the version matches."
            ),
            mod_path: None,
            mod_name: None,
            folder: None,
            url: None,
        });
    }
    None
}

/// Best-effort mod-id guess from a jar's filename, e.g.
/// "betterblockentities-1.3.8-beta.1+mc26.2.jar" -> "betterblockentities".
/// Cutting at the first digit is far more reliable than matching the whole
/// stem (which includes the mod's version and rarely lines up character-
/// for-character with how the crash log renders it) or the display name
/// (which is often a short acronym like "BBE" that the log also prints,
/// but which the old `len() > 3` guard used to exclude).
fn mod_id_slug(file_name: &str) -> String {
    let stem = file_name
        .trim_end_matches(".jar.disabled")
        .trim_end_matches(".jar");
    // Cut at the first "-<digit>"/"_<digit>" boundary — that reliably marks
    // where the version starts for the vast majority of jar filenames.
    // Cutting at the first digit anywhere (the old approach) breaks for ids
    // that contain digits themselves, e.g. "c2me-opts-dfc" or
    // "3dskinlayers", which would otherwise get chopped down to nothing.
    let chars: Vec<char> = stem.chars().collect();
    for i in 0..chars.len() {
        if (chars[i] == '-' || chars[i] == '_')
            && chars.get(i + 1).map(|c| c.is_ascii_digit()).unwrap_or(false)
        {
            return chars[..i].iter().collect::<String>().to_lowercase();
        }
    }
    stem.to_lowercase()
}

/// Any enabled mod whose name/filename/id shows up on a log line containing
/// "incompat" (matches "incompatible", "incompatibility", etc. across all
/// loaders' phrasing). Returns them in first-seen order, deduplicated.
fn find_incompatible_mods<'a>(text: &str, enabled_mods: &[&'a ModInfo]) -> Vec<&'a ModInfo> {
    let mut found: Vec<&ModInfo> = Vec::new();
    for line in text.lines() {
        let ll = line.to_lowercase();
        // Covers both the generic "incompatible"/"incompatibility" wording
        // and Fabric's per-mod dependency-resolution phrasing, e.g.:
        //   "requires any 26.2.x version of 'Minecraft', but only the
        //    wrong version is present: 26.1!"
        // which never actually contains the word "incompat".
        let is_incompat_line = ll.contains("incompat")
            || ll.contains("but only the wrong version is present")
            || (ll.contains("requires") && ll.contains("but only") && ll.contains("is present"));
        if !is_incompat_line {
            continue;
        }
        for m in enabled_mods {
            let stem = m
                .file_name
                .trim_end_matches(".jar.disabled")
                .trim_end_matches(".jar")
                .to_lowercase();
            let slug = mod_id_slug(&m.file_name);
            let is_match = (stem.len() >= 4 && ll.contains(&stem))
                || (slug.len() >= 3 && ll.contains(&slug))
                || (m.name.len() >= 3 && ll.contains(&m.name.to_lowercase()));
            if is_match && !found.iter().any(|f| f.file_name == m.file_name) {
                found.push(*m);
            }
        }
    }
    found
}

/// Best-effort: scan the crash text for any installed mod's name/file
/// stem/description appearing near an exception, and if found, offer to
/// disable it or look for an update. Picks whichever enabled mod appears
/// closest to an "Exception"/"Caused by" line.
fn find_culprit_mod(text: &str, enabled_mods: &[&ModInfo]) -> Option<(String, Vec<CrashFix>)> {
    let lines: Vec<&str> = text.lines().collect();
    let mut best: Option<(usize, &ModInfo)> = None; // (distance to nearest exception line, mod)

    let exception_line_idxs: Vec<usize> = lines
        .iter()
        .enumerate()
        .filter(|(_, l)| {
            let ll = l.to_lowercase();
            ll.contains("exception") || ll.contains("caused by") || ll.contains("error")
        })
        .map(|(i, _)| i)
        .collect();

    if exception_line_idxs.is_empty() {
        return None;
    }

    for m in enabled_mods {
        let stem = m
            .file_name
            .trim_end_matches(".jar.disabled")
            .trim_end_matches(".jar")
            .to_lowercase();
        let slug = mod_id_slug(&m.file_name);
        for (i, line) in lines.iter().enumerate() {
            let ll = line.to_lowercase();
            let is_match = (stem.len() >= 4 && ll.contains(&stem))
                || (slug.len() >= 3 && ll.contains(&slug))
                || (!m.name.is_empty() && m.name.len() >= 3 && ll.contains(&m.name.to_lowercase()));
            if is_match {
                let dist = exception_line_idxs
                    .iter()
                    .map(|e| if *e > i { e - i } else { i - e })
                    .min()
                    .unwrap_or(usize::MAX);
                if best.map(|(bd, _)| dist < bd).unwrap_or(true) {
                    best = Some((dist, *m));
                }
            }
        }
    }

    let (_, culprit) = best?;
    let fixes = vec![
        CrashFix {
            kind: "disable_mod".to_string(),
            label: format!("Disable {}", culprit.name),
            detail: format!("Turns off \"{}\" without deleting it — you can re-enable it later from the mod list.", culprit.file_name),
            mod_path: Some(culprit.path.clone()),
            mod_name: Some(culprit.name.clone()),
            folder: None,
            url: None,
        },
        CrashFix {
            kind: "update_mod".to_string(),
            label: format!("Check for an update to {}", culprit.name),
            detail: "Looks up a version matching your Minecraft/loader version on Modrinth and installs it, replacing the current file.".to_string(),
            mod_path: Some(culprit.path.clone()),
            mod_name: Some(culprit.name.clone()),
            folder: None,
            url: Some(format!("https://modrinth.com/mods?q={}", urlencoding_lite(&culprit.name))),
        },
    ];
    Some((culprit.name.clone(), fixes))
}
