//! Install request and result types used by [`crate::launcher::Launcher`].

use std::path::PathBuf;

use crate::loader::common::LoaderSpec;

/// Describes the profile that should be installed.
///
/// A request always starts from a Minecraft version. Setting [`loader`] asks the
/// installer to create or run the corresponding loader profile for that
/// Minecraft version.
///
/// [`loader`]: InstallRequest::loader
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallRequest {
    /// Vanilla Minecraft version, such as `1.20.1`.
    pub minecraft_version: String,
    /// Optional loader profile to install on top of the Minecraft version.
    pub loader: Option<LoaderSpec>,
    /// Java runtime policy for installers that need to execute Java.
    pub java: JavaInstallPolicy,
    /// Explicit path to a `java`/`java.exe` executable to run loader
    /// installers (Forge/NeoForge) with. When `None`, install code falls
    /// back to best-effort detection (`JAVA_HOME`, `/etc/alternatives/java`,
    /// then `PATH`) which may not find a runtime this launcher itself
    /// downloaded into its own managed folder. Callers that already know
    /// about a managed/bundled JRE should set this so the installer doesn't
    /// have to guess.
    pub java_executable: Option<PathBuf>,
}

impl InstallRequest {
    /// Creates a vanilla install request for the given Minecraft version.
    pub fn vanilla(version: impl Into<String>) -> Self {
        Self {
            minecraft_version: version.into(),
            loader: None,
            java: JavaInstallPolicy::Auto,
            java_executable: None,
        }
    }
}

/// Controls how install code should handle Java runtime needs.
///
/// The current high-level facade does not bundle Java. `Auto` is retained as
/// the default policy for future runtime management and compatibility with the
/// public request shape.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JavaInstallPolicy {
    /// Allow the launcher core to manage Java if a future implementation can do so.
    Auto,
    /// Never install or manage Java automatically.
    Never,
}

/// Result returned after an install completes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallResult {
    /// Version/profile id that should be loaded and launched.
    pub version_id: String,
}
