mod commands;
mod discord_rpc;
mod logger;
mod models;
mod state;

use state::AppState;
use discord_rpc::{DiscordRpcManager, DiscordRpcState};
use std::sync::Mutex;
use std::path::PathBuf;
use tauri::Manager;
use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState};
use tauri::image::Image;
use tauri::WindowEvent;

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let mut data_dir = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
            data_dir.push("Zero Launcher");
            std::fs::create_dir_all(&data_dir).ok();

            // Initialize app state with persistence in ~/Zero Launcher
            let state = AppState::new(data_dir);
            app.manage(state);
            app.manage(DiscordRpcState(Mutex::new(DiscordRpcManager::new())));

            // ── System tray ──
            let show_hide_item = MenuItemBuilder::with_id("show_hide", "Show / Hide")
                .build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "Quit ZeroLauncher")
                .build(app)?;
            let tray_menu = MenuBuilder::new(app)
                .item(&show_hide_item)
                .separator()
                .item(&quit_item)
                .build()?;

            let tray_icon = Image::from_bytes(include_bytes!("../icons/tray.png"))?;

            TrayIconBuilder::with_id("main-tray")
                .icon(tray_icon)
                .menu(&tray_menu)
                .show_menu_on_left_click(false)
                .tooltip("ZeroLauncher")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show_hide" => {
                        if let Some(window) = app.get_webview_window("main") {
                            if window.is_visible().unwrap_or(false) {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                    "quit" => {
                        if let Some(rpc_state) = app.try_state::<DiscordRpcState>() {
                            rpc_state.0.lock().unwrap().shutdown();
                        }
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            if window.is_visible().unwrap_or(false) {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .build(app)?;

            // Closing the main window only hides it to the tray if a game
            // instance is still running and tray behavior is enabled.
            let app_handle = app.handle();
            if let Some(window) = app_handle.get_webview_window("main") {
                let window_clone = window.clone();
                let app_handle_clone = app_handle.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        let state = app_handle_clone.state::<AppState>();
                        let settings = state.settings.lock().unwrap();
                        let running = state.running_instances.lock().unwrap()
                            .values()
                            .any(|info| info.running);
                        if running && settings.enable_system_tray {
                            api.prevent_close();
                            let _ = window_clone.hide();
                        }
                    }
                });
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::cancel_generic_download,
            // Minecraft
            commands::minecraft::get_available_versions,
            commands::minecraft::get_cached_versions,
            commands::minecraft::scan_minecraft_versions,
            commands::minecraft::install_minecraft,
            commands::minecraft::launch_minecraft,
            commands::minecraft::get_installed_instances,
            commands::minecraft::remove_instance,
            commands::minecraft::update_instance,
            commands::minecraft::delete_installed_version,
            commands::minecraft::get_hidden_instances,
            commands::minecraft::hide_instance,
            commands::minecraft::unhide_instance,
            commands::minecraft::get_dependent_instances,
            commands::minecraft::pause_download,
            commands::minecraft::resume_download,
            commands::minecraft::cancel_download,
            commands::minecraft::get_running_instances,
            commands::minecraft::get_instance_console_logs,
            commands::minecraft::kill_instance,
            // Java
            commands::java::list_java_installations,
            // Accounts
            commands::accounts::add_offline_account,
            commands::accounts::remove_account,
            commands::accounts::list_accounts,
            commands::accounts::set_active_account,
            // Mods
            commands::mods::list_mods,
            commands::mods::toggle_mod,
            commands::mods::delete_mod,
            commands::mods::open_mods_folder,
            commands::mods::install_mod_files,
            // Presets
            commands::presets::list_presets,
            commands::presets::get_preset_icon_path,
            commands::presets::resolve_preset_mod_url,
            commands::presets::get_preset_installed_mods,
            commands::presets::apply_preset_config,
            // Discover
            commands::discover::discover_search,
            commands::discover::discover_get_versions,
            commands::discover::discover_get_project,
            commands::discover::discover_download,
            commands::discover::discover_get_game_versions,
            commands::discover::discover_get_categories,
            commands::discover::discover_get_licenses,
            commands::discover::cache_mod_icon,
            commands::discover::identify_mods_by_hash,
            commands::discover::discover_get_projects_batch,
            // Settings
            commands::settings::get_settings,
            commands::settings::save_settings,
            commands::settings::update_discord_presence,
            commands::settings::get_default_minecraft_dir,
            // Music
            commands::music::get_music_dir,
            commands::music::open_music_folder,
            commands::music::list_music_files,
            commands::music::read_music_file,
            commands::mods::delete_instance_subpath,
            // Logs
            commands::logs::get_logs,
            commands::logs::clear_logs,
            commands::logs::export_logs,
            commands::logs::resolve_background_path,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            // Fires on every way the app can shut down (last window closed,
            // app.exit(0) from the tray "Quit" item, OS session end, etc).
            // Without this the Discord IPC pipe was only ever torn down by
            // the OS killing the process — Discord wouldn't reliably notice
            // in time and kept showing "Playing ZeroLauncher" after the
            // launcher had already closed.
            if let tauri::RunEvent::Exit = event {
                if let Some(rpc_state) = app_handle.try_state::<DiscordRpcState>() {
                    rpc_state.0.lock().unwrap().shutdown();
                }

                // Privacy → "Clear Account Session on Exit": deactivate
                // (but don't delete) every account so nothing is left
                // signed in for the next person to open the launcher and
                // land on. On next launch the account list is intact, just
                // with no active selection, so the app prompts to pick one.
                if let Some(state) = app_handle.try_state::<AppState>() {
                    let clear_on_exit = state.settings.lock()
                        .map(|s| s.clear_session_on_exit)
                        .unwrap_or(false);
                    if clear_on_exit {
                        let mut accounts = state.accounts.lock().unwrap();
                        for acc in accounts.iter_mut() {
                            acc.is_active = false;
                        }
                        drop(accounts);
                        state.save_accounts();
                    }
                }
            }
        });
}