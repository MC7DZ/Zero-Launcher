use std::process::Command;

#[derive(Debug, Clone)]
pub struct DepCheckResult {
    pub name: String,
    pub installed: bool,
    pub package_name: String,
}

/// Detects the Linux distribution package manager
#[cfg(target_os = "linux")]
pub fn detect_package_manager() -> Option<&'static str> {
    if Command::new("apt-get").arg("--version").output().is_ok() {
        Some("apt")
    } else if Command::new("pacman").arg("--version").output().is_ok() {
        Some("pacman")
    } else if Command::new("dnf").arg("--version").output().is_ok() {
        Some("dnf")
    } else if Command::new("zypper").arg("--version").output().is_ok() {
        Some("zypper")
    } else {
        None
    }
}

/// Checks required runtime libraries on Linux (WebKitGTK, libfuse2, libappindicator, etc.)
#[cfg(target_os = "linux")]
pub fn check_linux_dependencies() -> Vec<DepCheckResult> {
    let mut results = Vec::new();

    // 1. WebKitGTK (required by Tauri wry)
    let has_webkit = check_library_exists(&[
        "libwebkit2gtk-4.1.so",
        "libwebkit2gtk-4.1.so.0",
        "libwebkit2gtk-4.0.so",
        "libwebkit2gtk-4.0.so.37",
    ]);
    results.push(DepCheckResult {
        name: "WebKitGTK (Webview Runtime)".into(),
        installed: has_webkit,
        package_name: if is_apt_package_available("libwebkit2gtk-4.1-0") {
            "libwebkit2gtk-4.1-0".into()
        } else {
            "libwebkit2gtk-4.0-37".into()
        },
    });

    // 2. FUSE / libfuse2 (Required for AppImages to mount and execute)
    let has_fuse = check_library_exists(&[
        "libfuse.so.2",
        "libfuse.so",
    ]);
    results.push(DepCheckResult {
        name: "libfuse2 (AppImage Runtime)".into(),
        installed: has_fuse,
        package_name: "libfuse2".into(),
    });

    // 3. AppIndicator / Ayatana (For system tray icon)
    let has_indicator = check_library_exists(&[
        "libayatana-appindicator3.so.1",
        "libappindicator3.so.1",
    ]);
    results.push(DepCheckResult {
        name: "libappindicator (System Tray)".into(),
        installed: has_indicator,
        package_name: "libayatana-appindicator3-1".into(),
    });

    // 4. GTK 3
    let has_gtk = check_library_exists(&[
        "libgtk-3.so.0",
        "libgtk-3.so",
    ]);
    results.push(DepCheckResult {
        name: "GTK3 Runtime".into(),
        installed: has_gtk,
        package_name: "libgtk-3-0".into(),
    });

    // 5. curl / wget
    let has_curl = Command::new("curl").arg("--version").output().is_ok();
    results.push(DepCheckResult {
        name: "curl (HTTP tool)".into(),
        installed: has_curl,
        package_name: "curl".into(),
    });

    results
}

#[cfg(target_os = "linux")]
fn check_library_exists(lib_names: &[&str]) -> bool {
    let search_paths = [
        "/usr/lib/x86_64-linux-gnu",
        "/lib/x86_64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
        "/lib64",
        "/lib",
    ];

    for path in &search_paths {
        for name in lib_names {
            let full_path = std::path::Path::new(path).join(name);
            if full_path.exists() {
                return true;
            }
        }
    }

    // Fallback: test ldconfig -p
    if let Ok(output) = Command::new("ldconfig").arg("-p").output() {
        let stdout = String::from_utf8_lossy(&output.stdout);
        for name in lib_names {
            if stdout.contains(name) {
                return true;
            }
        }
    }

    false
}

#[cfg(target_os = "linux")]
fn is_apt_package_available(pkg: &str) -> bool {
    Command::new("apt-cache")
        .args(["show", pkg])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Runs package installation via system policykit (pkexec) just like the launcher
#[cfg(target_os = "linux")]
pub fn install_missing_linux_packages<F>(packages: &[String], mut logger: F) -> Result<(), String>
where
    F: FnMut(&str),
{
    if packages.is_empty() {
        return Ok(());
    }

    let pm = detect_package_manager().ok_or("Unsupported package manager. Please install dependencies manually.")?;
    logger(&format!("Detected package manager: {}", pm));

    let pkgs_joined = packages.join(" ");
    logger(&format!("Invoking system authorization to install: {}", pkgs_joined));

    let has_pkexec = Command::new("which").arg("pkexec").output().map(|o| o.status.success()).unwrap_or(false);
    if !has_pkexec {
        return Err("pkexec (PolicyKit) was not found on your system. Please install the packages manually: sudo ".to_string() + pm + " install " + &pkgs_joined);
    }

    let (bin, args) = match pm {
        "apt" => {
            ("apt-get", vec!["install", "-y"])
        }
        "pacman" => {
            ("pacman", vec!["-S", "--noconfirm", "--needed"])
        }
        "dnf" => {
            ("dnf", vec!["install", "-y"])
        }
        "zypper" => {
            ("zypper", vec!["install", "-y"])
        }
        _ => return Err("Unsupported package manager".into()),
    };

    let mut full_args = vec![bin];
    full_args.extend(args);
    for pkg in packages {
        full_args.push(pkg);
    }

    let output = Command::new("pkexec")
        .args(&full_args)
        .output()
        .map_err(|e| format!("Failed to launch pkexec: {e}"))?;

    if output.status.success() {
        logger("Successfully installed required system packages!");
        Ok(())
    } else {
        let err = String::from_utf8_lossy(&output.stderr).to_string();
        let out = String::from_utf8_lossy(&output.stdout).to_string();
        let combined = format!("{out}\n{err}").trim().to_string();
        let msg = if combined.is_empty() {
            "Authorization was cancelled or package installation failed.".to_string()
        } else {
            combined
        };
        Err(format!("Installation failed: {msg} (You can run 'sudo {pm} install {pkgs_joined}' manually)"))
    }
}

/// Windows dependency checker (WebView2 runtime)
#[cfg(target_os = "windows")]
pub fn check_windows_dependencies() -> Vec<DepCheckResult> {
    let mut results = Vec::new();
    let has_webview2 = check_webview2_installed();
    results.push(DepCheckResult {
        name: "Microsoft Edge WebView2 Runtime".into(),
        installed: has_webview2,
        package_name: "Microsoft.EdgeWebView2".into(),
    });
    results
}

/// Checks if WebView2 is installed by looking at known file paths and the Windows registry.
#[cfg(target_os = "windows")]
pub fn check_webview2_installed() -> bool {
    use std::path::Path;

    // Check standard install directories
    let file_paths = [
        r"C:\Program Files (x86)\Microsoft\EdgeWebView\Application",
        r"C:\Program Files\Microsoft\EdgeWebView\Application",
    ];
    for p in &file_paths {
        if Path::new(p).exists() {
            return true;
        }
    }

    // Check user-level install via registry (HKCU)
    // The key: HKCU\Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}
    // We probe it via reg.exe to avoid pulling in a winreg crate
    let reg_query = hidden(Command::new("reg"))
        .args([
            "query",
            r"HKCU\Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            "/v",
            "pv",
        ])
        .output();

    if let Ok(out) = reg_query {
        if out.status.success() {
            let stdout = String::from_utf8_lossy(&out.stdout);
            // pv value exists and is not "0.0.0.0"
            if stdout.contains("pv") && !stdout.contains("0.0.0.0") {
                return true;
            }
        }
    }

    // Check machine-level registry (HKLM)
    let reg_query_lm = hidden(Command::new("reg"))
        .args([
            "query",
            r"HKLM\Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            "/v",
            "pv",
        ])
        .output();

    if let Ok(out) = reg_query_lm {
        if out.status.success() {
            let stdout = String::from_utf8_lossy(&out.stdout);
            if stdout.contains("pv") && !stdout.contains("0.0.0.0") {
                return true;
            }
        }
    }

    false
}

/// Downloads the Microsoft Evergreen WebView2 Bootstrapper and installs it silently.
/// The bootstrapper is a small (~1.5 MB) stub that downloads and installs the full runtime.
/// install_dir is the Zero Launcher data directory (used as temp download location).
#[cfg(target_os = "windows")]
pub async fn download_and_install_webview2<F>(install_dir: &std::path::Path, mut logger: F) -> Result<(), String>
where
    F: FnMut(&str) + Send + 'static,
{
    use std::path::PathBuf;

    const BOOTSTRAPPER_URL: &str =
        "https://go.microsoft.com/fwlink/p/?LinkId=2124703";

    // Save to the Zero Launcher data dir as a temp file
    let bootstrapper_path: PathBuf = install_dir.join("MicrosoftEdgeWebview2Setup.exe");

    logger("Downloading WebView2 bootstrapper from Microsoft...");

    // Download bootstrapper
    let client = reqwest::Client::builder()
        .user_agent("Zero-Launcher-Setup/1.0")
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))?;

    let response = client
        .get(BOOTSTRAPPER_URL)
        .send()
        .await
        .map_err(|e| format!("Failed to download WebView2 bootstrapper: {e}"))?;

    if !response.status().is_success() {
        return Err(format!(
            "Server returned {} while downloading WebView2 bootstrapper",
            response.status()
        ));
    }

    let bytes = response
        .bytes()
        .await
        .map_err(|e| format!("Failed to read bootstrapper response: {e}"))?;

    std::fs::write(&bootstrapper_path, &bytes)
        .map_err(|e| format!("Failed to save bootstrapper to disk: {e}"))?;

    logger(&format!(
        "Bootstrapper saved ({} KB). Running silent install...",
        bytes.len() / 1024
    ));

    // Run the bootstrapper silently — /install /quiet /norestart
    let status = hidden(Command::new(&bootstrapper_path))
        .args(["/install", "/quiet", "/norestart"])
        .status()
        .map_err(|e| format!("Failed to run WebView2 bootstrapper: {e}"))?;

    // Clean up the bootstrapper exe regardless of result
    let _ = std::fs::remove_file(&bootstrapper_path);

    if status.success() {
        logger("WebView2 Runtime installed successfully!");
        Ok(())
    } else {
        let code = status.code().unwrap_or(-1);
        // Exit code 0 = success, 3010 = success + reboot needed
        if code == 3010 {
            logger("WebView2 installed. A system restart may be needed.");
            Ok(())
        } else {
            Err(format!(
                "WebView2 installer exited with code {code}. Try installing manually from: https://developer.microsoft.com/en-us/microsoft-edge/webview2/"
            ))
        }
    }
}

/// Prevents a console window from flashing up when spawning child processes on Windows.
#[cfg(target_os = "windows")]
fn hidden(mut cmd: Command) -> Command {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    cmd.creation_flags(CREATE_NO_WINDOW);
    cmd
}
