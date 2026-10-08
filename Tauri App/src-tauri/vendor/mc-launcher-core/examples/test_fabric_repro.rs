use std::path::PathBuf;
use mc_launcher_core::prelude::*;

fn main() {
    let target_dir = PathBuf::from("/tmp/mc_test_install_118");
    let _ = std::fs::remove_dir_all(&target_dir);
    std::fs::create_dir_all(&target_dir).unwrap();
    let launcher = Launcher::new(&target_dir);
    let req = InstallRequest {
        minecraft_version: "1.18".to_string(),
        loader: Some(LoaderSpec::Fabric {
            version: LoaderVersion::LatestStable,
        }),
        java: JavaInstallPolicy::Auto,
        java_executable: None,
    };
    let mut reporter = mc_launcher_core::progress::NoopReporter;
    println!("Starting install...");
    match launcher.install_with_progress(req, &mut reporter) {
        Ok(res) => println!("Success: {:?}", res),
        Err(e) => {
            println!("Install error (Debug): {:?}", e);
            println!("Install error (Display): {}", e);
        }
    }
}
