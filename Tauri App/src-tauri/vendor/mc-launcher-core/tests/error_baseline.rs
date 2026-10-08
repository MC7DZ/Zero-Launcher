use mc_launcher_core::{LauncherError, Result};

fn returns_error() -> Result<()> {
    Err(LauncherError::InvalidVersionId {
        id: "bad/version".to_string(),
    })
}

#[test]
fn launcher_error_formats_context() {
    let err = returns_error().unwrap_err();
    assert!(err.to_string().contains("bad/version"));
}

#[test]
fn transient_errors_detected_correctly() {
    use std::io::{Error, ErrorKind};
    use std::path::PathBuf;

    // Body read error wrapped in IO error
    let body_err = LauncherError::Io {
        source: Error::new(ErrorKind::Other, "request or response body error: error reading a body from connection"),
    };
    assert!(body_err.is_transient());
    assert!(!body_err.is_fatal());

    // IO connection reset
    let reset_err = LauncherError::Io {
        source: Error::new(ErrorKind::ConnectionReset, "connection reset by peer"),
    };
    assert!(reset_err.is_transient());
    assert!(!reset_err.is_fatal());

    // Checksum mismatch
    let checksum_err = LauncherError::ChecksumMismatch {
        path: PathBuf::from("dummy.jar"),
        expected: "abc".into(),
        actual: "def".into(),
    };
    assert!(checksum_err.is_transient());
    assert!(!checksum_err.is_fatal());

    // Fatal errors
    let invalid_version = LauncherError::InvalidVersionId {
        id: "1.999.0".into(),
    };
    assert!(!invalid_version.is_transient());
    assert!(invalid_version.is_fatal());

    let perm_err = LauncherError::Io {
        source: Error::new(ErrorKind::PermissionDenied, "permission denied"),
    };
    assert!(!perm_err.is_transient());
    assert!(perm_err.is_fatal());
}
