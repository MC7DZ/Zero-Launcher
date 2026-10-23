use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use crate::models::LauncherSettings;

pub struct DiscordRpcManager {
    client: Option<DiscordIpcClient>,
    start_time: i64,
    /// The app_id the current client was connected with — used to detect
    /// when the user changes the App ID in settings so we can reconnect.
    connected_app_id: String,
}

impl DiscordRpcManager {
    pub fn new() -> Self {
        let start_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);

        Self {
            client: None,
            start_time,
            connected_app_id: String::new(),
        }
    }

    /// Gracefully clear the activity and disconnect the Discord IPC socket.
    /// Must be called before the process exits — otherwise the pipe is torn
    /// down by the OS without Discord ever being told to clear the activity,
    /// which is why the "Playing ZeroLauncher" status could keep showing in
    /// Discord after the launcher window had already closed.
    pub fn shutdown(&mut self) {
        if let Some(ref mut client) = self.client {
            let _ = client.clear_activity();
            let _ = client.close();
        }
        self.client = None;
        self.connected_app_id.clear();
    }

    pub fn update_presence(&mut self, settings: &LauncherSettings, tab: &str, playing_instance: Option<&str>, mc_version: Option<&str>) {
        if !settings.enable_discord_rpc {
            if let Some(ref mut client) = self.client {
                let _ = client.close();
            }
            self.client = None;
            self.connected_app_id.clear();
            return;
        }

        let app_id = if settings.rpc_app_id.is_empty() {
            "1528905372625146066"
        } else {
            settings.rpc_app_id.as_str()
        };

        // If the App ID changed (user updated it in Settings), drop the old
        // client so we reconnect with the new one below.
        if self.client.is_some() && self.connected_app_id != app_id {
            if let Some(ref mut c) = self.client { let _ = c.close(); }
            self.client = None;
            self.connected_app_id.clear();
        }

        if self.client.is_none() {
            let mut client = DiscordIpcClient::new(app_id);
            if client.connect().is_ok() {
                self.connected_app_id = app_id.to_string();
                self.client = Some(client);
            }
        }

        if let Some(ref mut client) = self.client {
            let mut details = String::new();
            let mut state = String::new();

            if let Some(inst) = playing_instance {
                if settings.rpc_show_instance_name {
                    details = format!("Playing {}", inst);
                } else {
                    details = "In Game".to_string();
                }

                if settings.rpc_show_minecraft_version {
                    if let Some(ver) = mc_version {
                        state = ver.to_string();
                    }
                }
            } else {
                if settings.rpc_show_in_launcher {
                    let show_tab = match tab.to_lowercase().as_str() {
                        "instances" => settings.rpc_tab_instances,
                        "mods" => settings.rpc_tab_mods,
                        "settings" => settings.rpc_tab_settings,
                        "logs" => settings.rpc_tab_logs,
                        _ => true,
                    };

                    if settings.rpc_show_launcher_activity && show_tab {
                        details = format!("Browsing {}", tab);
                    }
                    if !settings.rpc_custom_state_text.is_empty() {
                        state = settings.rpc_custom_state_text.clone();
                    }
                }
            }

            let mut activity = activity::Activity::new()
                .assets(activity::Assets::new().large_image("minecraft_image").large_text("Minecraft"))
                .timestamps(activity::Timestamps::new().start(self.start_time));

            if !details.is_empty() {
                activity = activity.details(&details);
            }
            if !state.is_empty() {
                activity = activity.state(&state);
            }

            // If set_activity fails the connection dropped (Discord closed /
            // restarted). Tear down the dead client so the next update_presence
            // call will reconnect from scratch.
            if client.set_activity(activity).is_err() {
                let _ = client.close();
                self.client = None;
                self.connected_app_id.clear();
            }
        }
    }
}

pub struct DiscordRpcState(pub Mutex<DiscordRpcManager>);

