use crate::state::AppState;
use serde::{Deserialize, Serialize};
use std::fs;
use tauri::State;

/// A single track found under `Zero Launcher/music/`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MusicTrackInfo {
    pub file_name: String,
    pub path: String,
    pub enabled: bool,
}

const AUDIO_EXTENSIONS: &[&str] = &["mp3", "wav", "ogg", "flac", "m4a", "mp4", "aac"];

fn music_dir(state: &State<'_, AppState>) -> std::path::PathBuf {
    state.data_dir.join("music")
}

/// Returns (creating it if needed) the path to `Zero Launcher/music/`.
#[tauri::command]
pub fn get_music_dir(state: State<'_, AppState>) -> Result<String, String> {
    let dir = music_dir(&state);
    fs::create_dir_all(&dir).map_err(|e| format!("Failed to create music directory: {e}"))?;
    Ok(dir.to_string_lossy().to_string())
}

/// Opens `Zero Launcher/music/` in the system file explorer so the user can
/// drop tracks into it.
#[tauri::command]
pub fn open_music_folder(state: State<'_, AppState>) -> Result<(), String> {
    let dir = music_dir(&state);
    fs::create_dir_all(&dir).map_err(|e| format!("Failed to create music directory: {e}"))?;
    open::that(&dir).map_err(|e| format!("Failed to open folder: {e}"))?;
    Ok(())
}

/// Lists every audio file in `Zero Launcher/music/`, marking which ones the
/// user has disabled (see `music_disabled_tracks` in settings).
#[tauri::command]
pub fn list_music_files(state: State<'_, AppState>) -> Result<Vec<MusicTrackInfo>, String> {
    let dir = music_dir(&state);
    fs::create_dir_all(&dir).map_err(|e| format!("Failed to create music directory: {e}"))?;

    let disabled: std::collections::HashSet<String> = state
        .settings
        .lock()
        .unwrap()
        .music_disabled_tracks
        .iter()
        .cloned()
        .collect();

    let mut tracks = Vec::new();
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let ext = path
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_lowercase();
            if !AUDIO_EXTENSIONS.contains(&ext.as_str()) {
                continue;
            }
            let file_name = match path.file_name() {
                Some(n) => n.to_string_lossy().to_string(),
                None => continue,
            };
            tracks.push(MusicTrackInfo {
                enabled: !disabled.contains(&file_name),
                file_name,
                path: path.to_string_lossy().to_string(),
            });
        }
    }
    tracks.sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));
    Ok(tracks)
}

/// Reads a music file's raw bytes so the frontend can build a Blob URL for
/// playback instead of relying on the `asset://` protocol.
///
/// WebKitGTK's media backend (used on Linux) frequently fails to stream
/// audio through Tauri's custom asset protocol — `<audio>.play()` rejects
/// with NotSupportedError for every track regardless of codec, even though
/// the same files play fine in VLC or via a Blob URL. Reading the bytes
/// directly and handing them to the frontend as a Blob sidesteps that
/// protocol entirely and works reliably across platforms.
#[tauri::command]
pub fn read_music_file(state: State<'_, AppState>, file_name: String) -> Result<Vec<u8>, String> {
    let dir = music_dir(&state);
    // Guard against path traversal: only allow bare file names that resolve
    // to a direct child of the music directory.
    let candidate = dir.join(&file_name);
    let canonical_dir = fs::canonicalize(&dir).map_err(|e| format!("Music dir error: {e}"))?;
    let canonical_file = fs::canonicalize(&candidate)
        .map_err(|e| format!("Failed to open \"{file_name}\": {e}"))?;
    if canonical_file.parent() != Some(canonical_dir.as_path()) {
        return Err("Invalid music file path".to_string());
    }
    fs::read(&canonical_file).map_err(|e| format!("Failed to read \"{file_name}\": {e}"))
}
