use std::process::Command;

#[derive(Debug, Clone)]
pub struct DepCheckResult {
    pub name: String,
    pub installed: bool,
    pub package_name: String,
}

/// Returns true if `bin` can be found on PATH (or in the usual sbin dirs).
#[cfg(target_os = "linux")]
fn have(bin: &str) -> bool {
    let mut dirs: Vec<std::path::PathBuf> = std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).collect())
        .unwrap_or_default();
    for extra in ["/usr/sbin", "/sbin", "/usr/local/bin", "/usr/bin", "/bin"] {
        dirs.push(extra.into());
    }
    dirs.iter().any(|d| d.join(bin).is_file())
}

/// Detects the Linux distribution package manager.
/// Uses /etc/os-release (ID / ID_LIKE) as a hint first, then falls back to whatever is installed.
#[cfg(target_os = "linux")]
pub fn detect_package_manager() -> Option<&'static str> {
    let release = std::fs::read_to_string("/etc/os-release")
        .or_else(|_| std::fs::read_to_string("/usr/lib/os-release"))
        .unwrap_or_default()
        .to_lowercase();

    let hinted = |ids: &[&str]| {
        release
            .lines()
            .filter(|l| l.starts_with("id=") || l.starts_with("id_like="))
            .any(|l| {
                l.split(|c: char| c == '=' || c == '"' || c == ' ' || c == '\'')
                    .any(|w| ids.contains(&w))
            })
    };

    let candidates: [(&'static str, &str, &[&str]); 6] = [
        ("apt", "apt-get", &["debian", "ubuntu", "linuxmint", "pop", "elementary", "kali", "raspbian", "zorin"]),
        ("dnf", "dnf", &["fedora", "rhel", "centos", "rocky", "almalinux", "nobara"]),
        ("pacman", "pacman", &["arch", "manjaro", "endeavouros", "cachyos", "garuda", "steamos"]),
        ("zypper", "zypper", &["suse", "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles"]),
        ("xbps", "xbps-install", &["void"]),
        ("apk", "apk", &["alpine", "postmarketos"]),
    ];

    // Prefer the manager that matches the distro family
    for (name, bin, ids) in candidates.iter() {
        if hinted(*ids) && have(bin) {
            return Some(*name);
        }
    }
    // Otherwise whatever exists
    for (name, bin, _) in candidates.iter() {
        if have(bin) {
            return Some(*name);
        }
    }
    None
}

/// One logical dependency with the package names used by each package manager.
/// Names are listed in order of preference; the first one the repo actually offers is used.
#[cfg(target_os = "linux")]
struct Dep {
    name: &'static str,
    libs: &'static [&'static str],
    apt: &'static [&'static str],
    dnf: &'static [&'static str],
    pacman: &'static [&'static str],
    zypper: &'static [&'static str],
    xbps: &'static [&'static str],
    apk: &'static [&'static str],
}

#[cfg(target_os = "linux")]
const LINUX_DEPS: &[Dep] = &[
    Dep {
        name: "WebKitGTK (Webview Runtime)",
        libs: &[
            "libwebkit2gtk-4.1.so", "libwebkit2gtk-4.1.so.0",
            "libwebkit2gtk-4.0.so", "libwebkit2gtk-4.0.so.37",
        ],
        apt: &["libwebkit2gtk-4.1-0", "libwebkit2gtk-4.0-37"],
        dnf: &["webkit2gtk4.1", "webkit2gtk4.0"],
        pacman: &["webkit2gtk-4.1", "webkit2gtk"],
        zypper: &["libwebkit2gtk-4_1-0", "libwebkit2gtk-4_0-37"],
        xbps: &["webkit2gtk"],
        apk: &["webkit2gtk-4.1", "webkit2gtk"],
    },
    Dep {
        name: "libfuse2 (AppImage Runtime)",
        libs: &["libfuse.so.2", "libfuse.so"],
        apt: &["libfuse2t64", "libfuse2"],
        dnf: &["fuse-libs", "fuse2"],
        pacman: &["fuse2"],
        zypper: &["libfuse2", "fuse-libs"],
        xbps: &["fuse"],
        apk: &["fuse"],
    },
    Dep {
        name: "libappindicator (System Tray)",
        libs: &["libayatana-appindicator3.so.1", "libappindicator3.so.1"],
        apt: &["libayatana-appindicator3-1", "libappindicator3-1"],
        dnf: &["libayatana-appindicator-gtk3", "libappindicator-gtk3"],
        pacman: &["libayatana-appindicator", "libappindicator-gtk3"],
        zypper: &["libayatana-appindicator3-1", "libappindicator3-1"],
        xbps: &["libayatana-appindicator"],
        apk: &["libayatana-appindicator"],
    },
    Dep {
        name: "GTK3 Runtime",
        libs: &["libgtk-3.so.0", "libgtk-3.so"],
        apt: &["libgtk-3-0t64", "libgtk-3-0"],
        dnf: &["gtk3"],
        pacman: &["gtk3"],
        zypper: &["libgtk-3-0", "gtk3"],
        xbps: &["gtk+3"],
        apk: &["gtk+3.0"],
    },
];

#[cfg(target_os = "linux")]
fn names_for<'a>(dep: &'a Dep, pm: &str) -> &'a [&'static str] {
    match pm {
        "apt" => dep.apt,
        "dnf" => dep.dnf,
        "pacman" => dep.pacman,
        "zypper" => dep.zypper,
        "xbps" => dep.xbps,
        "apk" => dep.apk,
        _ => &[],
    }
}

/// Picks the first candidate package that the repositories actually provide
/// (falls back to the first name if nothing could be verified).
#[cfg(target_os = "linux")]
fn pick_package(dep: &Dep, pm: Option<&str>) -> String {
    let Some(pm) = pm else {
        return dep.apt[0].to_string();
    };
    let names = names_for(dep, pm);
    for n in names {
        if is_package_available(pm, n) {
            return (*n).to_string();
        }
    }
    names.first().copied().unwrap_or("").to_string()
}

#[cfg(target_os = "linux")]
fn is_package_available(pm: &str, pkg: &str) -> bool {
    let ok = |c: &mut Command| c.output().map(|o| o.status.success()).unwrap_or(false);
    match pm {
        "apt" => ok(Command::new("apt-cache").args(["show", pkg])),
        "dnf" => ok(Command::new("dnf").args(["-q", "info", pkg])),
        "pacman" => ok(Command::new("pacman").args(["-Si", pkg])),
        "zypper" => ok(Command::new("zypper").args(["--non-interactive", "-q", "info", pkg])),
        "xbps" => ok(Command::new("xbps-query").args(["-R", pkg])),
        "apk" => ok(Command::new("apk").args(["search", "-e", pkg])),
        _ => false,
    }
}

/// Checks required runtime libraries on Linux (WebKitGTK, libfuse2, libappindicator, etc.)
#[cfg(target_os = "linux")]
pub fn check_linux_dependencies() -> Vec<DepCheckResult> {
    let pm = detect_package_manager();
    let mut results = Vec::new();

    for dep in LINUX_DEPS {
        let installed = check_library_exists(dep.libs);
        results.push(DepCheckResult {
            name: dep.name.into(),
            installed,
            // Only query the repos for packages that are actually missing (it can be slow)
            package_name: if installed { String::new() } else { pick_package(dep, pm) },
        });
    }

    // curl
    results.push(DepCheckResult {
        name: "curl (HTTP tool)".into(),
        installed: have("curl"),
        package_name: "curl".into(),
    });

    results
}

#[cfg(target_os = "linux")]
fn check_library_exists(lib_names: &[&str]) -> bool {
    let search_paths = [
        "/usr/lib/x86_64-linux-gnu",
        "/lib/x86_64-linux-gnu",
        "/usr/lib/aarch64-linux-gnu",
        "/lib/aarch64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
        "/lib64",
        "/lib",
    ];

    for path in &search_paths {
        for name in lib_names {
            if std::path::Path::new(path).join(name).exists() {
                return true;
            }
        }
    }

    // Fallback: ldconfig -p (often lives in /sbin, which may not be on a normal user's PATH)
    for ldconfig in ["ldconfig", "/sbin/ldconfig", "/usr/sbin/ldconfig"] {
        if let Ok(output) = Command::new(ldconfig).arg("-p").output() {
            if output.status.success() {
                let stdout = String::from_utf8_lossy(&output.stdout);
                if lib_names.iter().any(|n| stdout.contains(n)) {
                    return true;
                }
            }
        }
    }

    false
}

/// The command a user can run by hand if automatic installation is not possible.
#[cfg(target_os = "linux")]
fn manual_command(pm: &str, pkgs: &str) -> String {
    match pm {
        "apt" => format!("sudo apt-get update && sudo apt-get install {pkgs}"),
        "dnf" => format!("sudo dnf install {pkgs}"),
        "pacman" => format!("sudo pacman -S --needed {pkgs}"),
        "zypper" => format!("sudo zypper install {pkgs}"),
        "xbps" => format!("sudo xbps-install -S {pkgs}"),
        "apk" => format!("doas apk add {pkgs}   (or: sudo apk add {pkgs})"),
        _ => pkgs.to_string(),
    }
}

/// Runs package installation via system policykit (pkexec) just like the launcher
#[cfg(target_os = "linux")]
pub fn install_missing_linux_packages<F>(packages: &[String], mut logger: F) -> Result<(), String>
where
    F: FnMut(&str),
{
    let packages: Vec<&String> = packages.iter().filter(|p| !p.is_empty()).collect();
    if packages.is_empty() {
        return Ok(());
    }

    let Some(pm) = detect_package_manager() else {
        let list = packages.iter().map(|p| p.as_str()).collect::<Vec<_>>().join(", ");
        return Err(format!(
            "No supported package manager found (apt, dnf, pacman, zypper, xbps, apk). \
             Immutable/atomic distros (Silverblue, SteamOS, NixOS…) need these installed another way: {list}"
        ));
    };
    logger(&format!("Detected package manager: {}", pm));

    let pkgs_joined = packages.iter().map(|p| p.as_str()).collect::<Vec<_>>().join(" ");
    logger(&format!("Invoking system authorization to install: {}", pkgs_joined));

    if !have("pkexec") {
        return Err(format!(
            "pkexec (PolicyKit) was not found on your system. Please install the packages manually: {}",
            manual_command(pm, &pkgs_joined)
        ));
    }

    let mut cmd = Command::new("pkexec");
    match pm {
        "apt" => {
            let script = format!("apt-get update -qq; apt-get install -y {pkgs_joined}");
            cmd.args(["env", "DEBIAN_FRONTEND=noninteractive", "sh", "-c", script.as_str()]);
        }
        "pacman" => { cmd.args(["pacman", "-S", "--noconfirm", "--needed"]).args(&packages); }
        "dnf" => { cmd.args(["dnf", "install", "-y"]).args(&packages); }
        "zypper" => { cmd.args(["zypper", "--non-interactive", "install"]).args(&packages); }
        "xbps" => { cmd.args(["xbps-install", "-Sy"]).args(&packages); }
        "apk" => { cmd.args(["apk", "add"]).args(&packages); }
        _ => return Err("Unsupported package manager".into()),
    }

    let output = cmd
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
        Err(format!(
            "Installation failed: {msg} (You can run '{}' manually)",
            manual_command(pm, &pkgs_joined)
        ))
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
