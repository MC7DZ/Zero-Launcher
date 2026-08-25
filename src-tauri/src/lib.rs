mod commands;
mod discord_rpc;
mod first_run_setup;
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
    // WebKitGTK's DMA-BUF renderer is unreliable across many GPU/driver
    // combinations (especially binaries built in a generic CI environment,
    // like GitHub Actions runners) and can silently render a blank
    // black/white window instead of the UI. Disabling it forces WebKit to
    // fall back to a compositing path that works everywhere. Must be set
    // before the webview is created, so this has to happen at the very
    // start of run().
    #[cfg(target_os = "linux")]
    {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
        if std::env::var("WEBKIT_DISABLE_COMPOSITING_MODE").is_err() {
            std::env::set_var("WEBKIT_DISABLE_COMPOSITING_MODE", "0");
        }

        // Wayland compositors (and GNOME/KDE's alt-tab/taskbar in
        // particular) don't use window.set_icon() at all — there's no
        // pixmap-passing protocol like X11 has. Instead they identify a
        // window by its xdg-shell "app_id" and look up an installed
        // .desktop file with a matching id/StartupWMClass to find an icon.
        // GTK derives that app_id from GLib's "prgname" the first time a
        // window is realized, and if we never set it ourselves, GLib falls
        // back to guessing it from argv[0] — which, for a binary launched
        // out of an extracted AppImage, is some unpredictable temp mount
        // path rather than "zerolauncher". That mismatch is why alt-tab
        // was showing a generic Wayland icon instead of ours. Setting this
        // explicitly, before any window exists, makes the reported app_id
        // match the "zerolauncher.desktop" entry first_run_setup installs,
        // so the compositor can resolve our actual icon.
        glib::set_prgname(Some("zerolauncher"));
        glib::set_application_name("Zero Launcher");
    }

    tauri::Builder::default()
        // Must be the first plugin registered. If the launcher is opened
        // again while it's already running, this fires in the *existing*
        // process instead of a second instance starting up — we just show
        // and focus the window that's already there.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // Very first thing on every launch: make sure the launcher is
            // installed in its permanent home with a proper shortcut
            // before anything else (tray, main window, state) is set up.
            first_run_setup::run_first_time_setup(&app.handle().clone());

            #[cfg(target_os = "linux")]
            let _ = first_run_setup::ensure_linux_xdg_icons();
            #[cfg(target_os = "windows")]
            let _ = first_run_setup::ensure_windows_shortcuts();

            // Linux/macOS: ~/Zero Launcher
            // Windows: %APPDATA%/Zero Launcher
            // Must match `first_run_setup::install_dir()` and
            // `commands::java::java_install_dir()`, which already split on
            // platform this way — otherwise the exe/Java installs end up in
            // %APPDATA% while settings/instances/accounts end up in the
            // Windows user profile root, splitting the launcher's data
            // across two unrelated folders.
            #[cfg(target_os = "windows")]
            let mut data_dir = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
            #[cfg(not(target_os = "windows"))]
            let mut data_dir = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
            data_dir.push("Zero Launcher");
            std::fs::create_dir_all(&data_dir).ok();

            // Initialize app state with persistence in the platform data dir
            let state = AppState::new(data_dir.clone());

            // Rehydrate instances that were still running when a *previous*
            // launcher process quit. Those game processes are spawned
            // detached (see minecraft::launch_minecraft) specifically so
            // they keep playing through a launcher restart — this is the
            // other half of that: reading back what was persisted to
            // running_instances.json and, for every pid that's actually
            // still alive, restoring it as "running" and starting a
            // watcher so we notice when it eventually does exit.
            let persisted = state::AppState::load_persisted_running_instances(&data_dir);
            {
                let mut running = state.running_instances.lock().unwrap();
                for info in persisted {
                    if let Some(pid) = info.pid {
                        if commands::minecraft::is_pid_running(pid) {
                            let version_id = info.version_id.clone();
                            running.insert(version_id.clone(), info);
                            commands::minecraft::spawn_external_pid_watcher(
                                app.handle().clone(),
                                version_id,
                                pid,
                            );
                        }
                    }
                }
            }
            // Drop any entries that turned out to be stale (process no
            // longer alive) from the file itself.
            state.save_running_instances();

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
                                let _ = window.unminimize();
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
                                let _ = window.unminimize();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .build(app)?;

            // What happens when the main window is closed is governed by
            // Settings → Window Behavior ("On Launcher Close") — see the
            // CloseRequested handler below.
            let app_handle = app.handle();
            if let Some(window) = app_handle.get_webview_window("main") {
                // The window is declared in tauri.conf.json rather than
                // built here, and on Linux (GTK) that path doesn't always
                // pick up the bundle's app icon for the *window* itself —
                // only for the packaged binary/.desktop entry. Window
                // managers and things like GNOME's System Monitor / Task
                // Manager that read the live window's icon (rather than
                // resolving it through desktop-file/icon-theme lookup) fall
                // back to a generic one in that case. Setting it explicitly
                // at startup, the same icon already used for the tray and
                // the bundle, makes sure the running process shows up with
                // ZeroLauncher's actual icon and name everywhere, not just
                // in places that happen to resolve it through the app's
                // installed .desktop/exe metadata.
                if let Ok(window_icon) = Image::from_bytes(include_bytes!("../icons/icon.png")) {
                    let _ = window.set_icon(window_icon);
                }
                let window_clone = window.clone();
                let app_handle_clone = app_handle.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        let state = app_handle_clone.state::<AppState>();
                        let (should_hide, notify_tray) = {
                            let mut settings = state.settings.lock().unwrap();
                            let running = state.running_instances.lock().unwrap()
                                .values()
                                .any(|info| info.running);
                            // Window Behavior → "On Launcher Close": hide to
                            // tray instead of quitting when either a game is
                            // running (the classic case — don't lose track of
                            // it), or "always hide to tray" is on regardless
                            // of whether anything's running. Either way this
                            // only applies if the tray icon actually exists.
                            let should_hide = settings.enable_system_tray
                                && settings.on_launcher_close == "tray"
                                && (running || settings.always_hide_to_tray);
                            let notify_tray = should_hide && !settings.tray_notification_shown;
                            if notify_tray {
                                settings.tray_notification_shown = true;
                            }
                            (should_hide, notify_tray)
                        };
                        if should_hide {
                            api.prevent_close();
                            let _ = window_clone.hide();
                            if notify_tray {
                                state.save_settings_to_disk();
                                use tauri_plugin_notification::NotificationExt;
                                let _ = app_handle_clone.notification()
                                    .builder()
                                    .title("Zero Launcher")
                                    .body("Zero Launcher is running in the system tray. Click the tray icon to restore.")
                                    .show();
                            }
                        }
                    }
                });
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::cancel_generic_download,
            commands::open_launcher_folder,
            commands::get_launcher_version,
            commands::updater::check_for_update,
            commands::updater::download_update,
            commands::updater::install_update,
            commands::updater::open_current_exe_folder,
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
            commands::minecraft::check_linux_zlib_conflict,
            commands::minecraft::install_linux_package,
            // Modpack import (.mrpack / CurseForge zip drag-and-drop)
            commands::modpack::preview_modpack,
            commands::modpack::import_modpack,
            // Java
            commands::java::list_java_installations,
            // Accounts
            commands::accounts::add_offline_account,
            commands::accounts::remove_account,
            commands::accounts::list_accounts,
            commands::accounts::set_active_account,
            commands::msa::microsoft_device_code_start,
            commands::msa::microsoft_device_code_poll,
            commands::msa::microsoft_device_code_cancel,
            commands::msa::refresh_microsoft_account,
            commands::msa::refresh_all_microsoft_accounts,
            // Skin management
            commands::skins::list_skins,
            commands::skins::import_skin,
            commands::skins::delete_skin,
            commands::skins::cache_skin_texture,
            commands::skins::cache_account_skin,
            commands::skins::list_cached_skins,
            commands::skins::upload_skin_to_mojang,
            commands::skins::reset_mojang_skin,
            commands::skins::get_account_capes,
            commands::skins::equip_mojang_cape,
            // Mods
            commands::mods::list_mods,
            commands::mods::toggle_mod,
            commands::mods::delete_mod,
            commands::mods::open_mods_folder,
            commands::mods::install_mod_files,
            commands::mods::export_mods_list,
            commands::mods::read_mods_list_file,
            // Presets
            commands::presets::list_presets,
            commands::presets::get_local_presets,
            commands::presets::sync_presets,
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
            commands::discover::discover_get_resolutions,
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
            // Sound & Effects
            commands::play_click_sound,
            // Logs
            commands::logs::get_logs,
            commands::logs::clear_logs,
            commands::logs::export_logs,
            commands::logs::get_logs_folder_path,
            commands::logs::open_logs_folder,
            commands::logs::get_latest_log_contents,
            commands::logs::resolve_background_path,
            commands::open_devtools,
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