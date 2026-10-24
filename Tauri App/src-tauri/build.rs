//! Build script: loads build-time configuration from `.env` (project root or
//! `src-tauri/`) and exposes it to the compiler, so `option_env!` in
//! `src/config.rs` can see it. Real process environment variables win over
//! `.env` values. See `.env.example` for the full list.

use std::{collections::HashMap, fs, path::Path};

const KEYS: &[&str] = &[
    "ZL_CURSEFORGE_API_KEY",
    "ZL_ELYBY_CLIENT_ID",
    "ZL_ELYBY_CLIENT_SECRET",
    "ZL_AZURE_CLIENT_ID",
    "ZL_CRASH_WEBHOOK",
];

fn parse_env_file(path: &Path) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let Ok(text) = fs::read_to_string(path) else { return out };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let line = line.strip_prefix("export ").unwrap_or(line);
        let Some((k, v)) = line.split_once('=') else { continue };
        let v = v.trim();
        let v = if v.len() >= 2
            && ((v.starts_with('"') && v.ends_with('"')) || (v.starts_with('\'') && v.ends_with('\'')))
        {
            &v[1..v.len() - 1]
        } else {
            v
        };
        out.insert(k.trim().to_string(), v.to_string());
    }
    out
}

fn main() {
    // Later files override earlier ones: root .env, then src-tauri/.env.
    let mut file_vars = HashMap::new();
    for p in ["../.env", ".env"] {
        println!("cargo:rerun-if-changed={p}");
        file_vars.extend(parse_env_file(Path::new(p)));
    }

    for key in KEYS {
        println!("cargo:rerun-if-env-changed={key}");
        if std::env::var_os(key).is_none() {
            if let Some(v) = file_vars.get(*key) {
                println!("cargo:rustc-env={key}={v}");
            }
        }
    }

    tauri_build::build()
}
