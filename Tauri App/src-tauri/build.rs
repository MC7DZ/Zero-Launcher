fn main() {
    // Load .env (if present) so ELYBY_CLIENT_ID / ELYBY_CLIENT_SECRET reach
    // `option_env!` in elyby.rs without needing to remember to `source .env`
    // or export the vars by hand before every build. Silently does nothing
    // if .env doesn't exist (e.g. CI, or a machine that sets real env vars
    // directly) — this is a convenience, not a requirement.
    if let Ok(iter) = dotenvy::dotenv_iter() {
        for item in iter.flatten() {
            println!("cargo:rustc-env={}={}", item.0, item.1);
        }
    }
    println!("cargo:rerun-if-changed=.env");

    tauri_build::build()
}
