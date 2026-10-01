slint::include_modules!();

mod deps;
mod downloader;
mod shortcuts;

use std::path::{Path, PathBuf};
use std::process::Command;
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use tokio::sync::oneshot;

fn default_install_dir() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        let mut dir = dirs::data_dir().unwrap_or_else(|| PathBuf::from(r"C:\"));
        dir.push("Zero Launcher");
        dir
    }
    #[cfg(not(target_os = "windows"))]
    {
        let mut dir = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
        dir.push("Zero Launcher");
        dir
    }
}

/// Spawns the executable safely across platforms without relying on desktop environment opener restrictions
fn spawn_target_executable(target: &Path) {
    #[cfg(target_os = "windows")]
    {
        let _ = Command::new(target).spawn();
    }

    #[cfg(target_os = "linux")]
    {
        let _ = Command::new(target).spawn();
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let main_window = AppWindow::new()?;
    let weak_window = main_window.as_weak();

    // Default configuration values
    main_window.set_install_appimage(true);
    main_window.set_create_desktop_shortcut(true);
    main_window.set_create_menu_shortcut(true);
    main_window.set_check_system_deps(true);

    let logs_model = Rc::new(VecModel::<LogItem>::default());
    main_window.set_logs(ModelRc::new(logs_model.clone()));

    // Shared state for target executable path
    let installed_exe_path: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));

    // Cancel / Close handler
    {
        let weak = weak_window.clone();
        main_window.on_cancel_or_close(move || {
            if let Some(w) = weak.upgrade() {
                let _ = w.hide();
            }
            std::process::exit(0);
        });
    }

    // Drag window handler (frameless window moving)
    {
        let weak = weak_window.clone();
        main_window.on_drag_window(move |delta_x: f32, delta_y: f32| {
            if let Some(w) = weak.upgrade() {
                let win = w.window();
                let scale = win.scale_factor();
                let current_pos = win.position();
                let new_x = current_pos.x + (delta_x * scale) as i32;
                let new_y = current_pos.y + (delta_y * scale) as i32;
                win.set_position(slint::PhysicalPosition::new(new_x, new_y));
            }
        });
    }

    // Launch installed app handler
    {
        let installed_path_ref = installed_exe_path.clone();
        main_window.on_launch_app(move || {
            if let Some(target) = installed_path_ref.lock().unwrap().clone() {
                if target.exists() {
                    spawn_target_executable(&target);
                }
            }
            std::process::exit(0);
        });
    }

    // Shared channel: install task sends a Sender here; UI callback fires it with user's answer
    let webview2_tx: Arc<Mutex<Option<oneshot::Sender<bool>>>> = Arc::new(Mutex::new(None));

    // WebView2 confirmation answer from user (Install = true, Skip = false)
    {
        let wv2_tx_ref = webview2_tx.clone();
        main_window.on_webview2_confirm(move |should_install| {
            if let Ok(mut guard) = wv2_tx_ref.lock() {
                if let Some(tx) = guard.take() {
                    let _ = tx.send(should_install);
                }
            }
        });
    }

    // Start installation flow
    {
        let weak = weak_window.clone();
        let installed_path_ref = installed_exe_path.clone();
        let wv2_tx_ref = webview2_tx.clone();

        main_window.on_start_install(move || {
            let Some(ui) = weak.upgrade() else { return };

            let install_path = default_install_dir();
            let install_binary = ui.get_install_appimage();
            let create_desktop = ui.get_create_desktop_shortcut();
            let create_menu = ui.get_create_menu_shortcut();
            let check_deps = ui.get_check_system_deps();

            // Transition to Installing step
            ui.set_current_step(1);
            ui.set_status_text("Preparing environment...".into());
            ui.set_progress_value(0.05);
            ui.set_progress_label("5%".into());

            // Clear previous logs via new VecModel
            ui.set_logs(ModelRc::new(Rc::new(VecModel::<LogItem>::default())));

            let weak_task = weak.clone();
            let installed_ref = installed_path_ref.clone();
            #[allow(unused_variables)]
            let wv2_tx_task = wv2_tx_ref.clone();

            tokio::spawn(async move {
                let update_ui_status = {
                    let weak_task = weak_task.clone();
                    move |status: &str, p: f32, label: &str| {
                        let weak_task = weak_task.clone();
                        let status = status.to_string();
                        let label = label.to_string();
                        let _ = slint::invoke_from_event_loop(move || {
                            if let Some(w) = weak_task.upgrade() {
                                w.set_status_text(status.into());
                                w.set_progress_value(p);
                                w.set_progress_label(label.into());
                            }
                        });
                    }
                };

                let log_ui = {
                    let weak_task = weak_task.clone();
                    move |msg: &str, level: &str| {
                        let weak_task = weak_task.clone();
                        let msg = msg.to_string();
                        let level = level.to_string();
                        let _ = slint::invoke_from_event_loop(move || {
                            if let Some(w) = weak_task.upgrade() {
                                let model_rc = w.get_logs();
                                if let Some(vec_model) = model_rc.as_any().downcast_ref::<VecModel<LogItem>>() {
                                    vec_model.push(LogItem {
                                        message: msg.into(),
                                        level: level.into(),
                                    });
                                }
                            }
                        });
                    }
                };

                log_ui(&format!("Target directory: {}", install_path.display()), "info");

                // 1. Check & Install Dependencies (Linux / Windows)
                if check_deps {
                    update_ui_status("Checking system dependencies...", 0.10, "10%");
                    log_ui("Checking system dependencies...", "info");

                    #[cfg(target_os = "linux")]
                    {
                        let deps = deps::check_linux_dependencies();
                        let mut missing = Vec::new();
                        for d in deps {
                            if d.installed {
                                log_ui(&format!("✓ Found: {}", d.name), "ok");
                            } else {
                                log_ui(&format!("! Missing: {} (package: {})", d.name, d.package_name), "info");
                                missing.push(d.package_name);
                            }
                        }

                        if !missing.is_empty() {
                            update_ui_status("Installing required dependencies...", 0.15, "15%");
                            log_ui(&format!("Installing missing dependencies: {}", missing.join(", ")), "info");
                            log_ui("Your system will now securely ask for authorization...", "info");

                            let log_clone = log_ui.clone();
                            if let Err(e) = deps::install_missing_linux_packages(&missing, move |m| log_clone(m, "info")) {
                                log_ui(&format!("Warning: {}", e), "info");
                            } else {
                                log_ui("Dependencies successfully configured.", "ok");
                            }
                        } else {
                            log_ui("All required dependencies are satisfied.", "ok");
                        }
                    }

                    #[cfg(target_os = "windows")]
                    {
                        let deps = deps::check_windows_dependencies();
                        let mut webview2_missing = false;

                        for d in &deps {
                            if d.installed {
                                log_ui(&format!("✓ Found: {}", d.name), "ok");
                            } else {
                                log_ui(&format!("! Missing: {}", d.name), "info");
                                if d.package_name == "Microsoft.EdgeWebView2" {
                                    webview2_missing = true;
                                }
                            }
                        }

                        if webview2_missing {
                            // Ask user via dialog before downloading
                            log_ui("WebView2 Runtime is missing. Asking for your confirmation...", "info");

                            let (tx, rx) = oneshot::channel::<bool>();
                            {
                                let mut guard = wv2_tx_task.lock().unwrap();
                                *guard = Some(tx);
                            }

                            // Show the confirmation dialog overlay
                            let weak_dialog = weak_task.clone();
                            let _ = slint::invoke_from_event_loop(move || {
                                if let Some(w) = weak_dialog.upgrade() {
                                    w.set_webview2_dialog_visible(true);
                                }
                            });

                            // Wait for user's choice (blocks the async task, not the UI thread)
                            let should_install = rx.await.unwrap_or(false);

                            if should_install {
                                update_ui_status("Downloading WebView2 from Microsoft...", 0.15, "15%");
                                let log_clone = log_ui.clone();
                                match deps::download_and_install_webview2(&install_path, move |m| log_clone(m, "info")).await {
                                    Ok(()) => {
                                        log_ui("WebView2 Runtime is ready.", "ok");
                                    }
                                    Err(e) => {
                                        log_ui(&format!("WebView2 install warning: {e}"), "info");
                                        log_ui("Continuing setup — you may need to install WebView2 manually.", "info");
                                    }
                                }
                            } else {
                                log_ui("WebView2 install skipped. Zero Launcher may not work without it.", "info");
                            }
                        }
                    }
                }


                // Ensure installation folder exists
                if let Err(e) = std::fs::create_dir_all(&install_path) {
                    let err = format!("Failed to create directory {}: {e}", install_path.display());
                    log_ui(&err, "error");
                    let weak = weak_task.clone();
                    let _ = slint::invoke_from_event_loop(move || {
                        if let Some(w) = weak.upgrade() {
                            w.set_current_step(3);
                            w.set_error_message(err.into());
                        }
                    });
                    return;
                }

                #[cfg(target_os = "windows")]
                let default_binary_name = "ZeroLauncher.exe";
                #[cfg(not(target_os = "windows"))]
                let default_binary_name = "ZeroLauncher.AppImage";

                let target_file = install_path.join(default_binary_name);

                // 2. Fetch & Download Latest Release Binary (if selected)
                if install_binary {
                    update_ui_status("Checking latest Zero Launcher release...", 0.25, "25%");
                    log_ui("Querying updates manifest...", "info");

                    let release = match downloader::resolve_latest_release().await {
                        Ok(r) => {
                            log_ui(&format!("Found latest release: v{}", r.version), "ok");
                            r
                        }
                        Err(e) => {
                            log_ui(&format!("Error checking release: {e}"), "error");
                            let weak = weak_task.clone();
                            let err = format!("Could not retrieve release info: {e}");
                            let _ = slint::invoke_from_event_loop(move || {
                                if let Some(w) = weak.upgrade() {
                                    w.set_current_step(3);
                                    w.set_error_message(err.into());
                                }
                            });
                            return;
                        }
                    };

                    let target_download_file = install_path.join(&release.filename);
                    *installed_ref.lock().unwrap() = Some(target_download_file.clone());

                    update_ui_status("Downloading Zero Launcher...", 0.30, "30%");
                    log_ui(&format!("Downloading {}...", release.filename), "info");

                    let weak_dl = weak_task.clone();
                    let dl_res = downloader::download_file_with_progress(
                        &release.download_url,
                        &target_download_file,
                        move |downloaded, total| {
                            if let Some(tot) = total {
                                if tot > 0 {
                                    let ratio = downloaded as f32 / tot as f32;
                                    let p = 0.30 + (ratio * 0.55); // 30% to 85%
                                    let percent = (p * 100.0) as u32;
                                    let status_msg = format!("Downloading Zero Launcher ({:.1}/{:.1} MB)",
                                        downloaded as f64 / 1_048_576.0,
                                        tot as f64 / 1_048_576.0);
                                    let weak = weak_dl.clone();
                                    let label = format!("{}%", percent);
                                    let _ = slint::invoke_from_event_loop(move || {
                                        if let Some(w) = weak.upgrade() {
                                            w.set_status_text(status_msg.into());
                                            w.set_progress_value(p);
                                            w.set_progress_label(label.into());
                                        }
                                    });
                                }
                            }
                        },
                    ).await;

                    if let Err(e) = dl_res {
                        let err = format!("Download failed: {e}");
                        log_ui(&err, "error");
                        let weak = weak_task.clone();
                        let _ = slint::invoke_from_event_loop(move || {
                            if let Some(w) = weak.upgrade() {
                                w.set_current_step(3);
                                w.set_error_message(err.into());
                            }
                        });
                        return;
                    }

                    log_ui("Binary downloaded successfully.", "ok");
                } else {
                    *installed_ref.lock().unwrap() = Some(target_file.clone());
                }

                // 3. Create Shortcuts
                update_ui_status("Creating desktop & menu shortcuts...", 0.90, "90%");
                log_ui("Creating application shortcuts...", "info");

                #[cfg(target_os = "linux")]
                {
                    if let Err(e) = shortcuts::create_linux_shortcuts(&target_file, create_desktop, create_menu) {
                        log_ui(&format!("Shortcut warning: {e}"), "info");
                    } else {
                        log_ui("Linux shortcuts created successfully.", "ok");
                    }
                }

                #[cfg(target_os = "windows")]
                {
                    if let Err(e) = shortcuts::create_windows_shortcuts(&target_file, create_desktop, create_menu) {
                        log_ui(&format!("Shortcut warning: {e}"), "info");
                    } else {
                        log_ui("Windows shortcuts created successfully.", "ok");
                    }
                }

                // 4. Complete Setup
                update_ui_status("Setup complete!", 1.0, "100%");
                log_ui("Setup finished successfully!", "ok");

                tokio::time::sleep(tokio::time::Duration::from_millis(600)).await;

                let weak = weak_task.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(w) = weak.upgrade() {
                        w.set_current_step(2);
                    }
                });
            });
        });
    }

    main_window.run()?;
    Ok(())
}
