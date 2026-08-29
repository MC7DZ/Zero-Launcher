/* ═══════════════════════════════════════════════════════════════════
   ZeroLauncher — App Logic (single-file, no page modules)
   ═══════════════════════════════════════════════════════════════════ */

// ── Tauri API ──
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ── Linux / WebKitGTK Performance Optimization Engine ──
if (
  /Linux|X11/i.test(navigator.userAgent) ||
  (/WebKit/i.test(navigator.userAgent) && !/Chrome/i.test(navigator.userAgent))
) {
  document.documentElement.classList.add('is-webkit-gtk', 'is-linux');
  if (document.body) {
    document.body.classList.add('is-webkit-gtk', 'is-linux');
  } else {
    document.addEventListener('DOMContentLoaded', () => {
      document.body.classList.add('is-webkit-gtk', 'is-linux');
    });
  }
}

// ── Loader icons (bundled by Vite) ──
import iconVanilla from '../assets/loaders/vanilla.png';
import iconFabric from '../assets/loaders/fabric.png';
import iconForge from '../assets/loaders/forge.png';
import iconNeoforge from '../assets/loaders/neoforge.png';
import iconQuilt from '../assets/loaders/quilt.png';
import javaIconSvgRaw from '../assets/icons/java.svg?raw';
import defaultOfflineSkin from '../assets/default-offline-skin.png';
// "Unknown" placeholder skin — a question-mark-textured skin, bundled
// locally (not hotlinked) so it always loads reliably regardless of
// network access. Used anywhere the app shows a stand-in for "no real
// skin" (Anonymous Skin mode, offline fallback).
import unknownSkin from '../assets/unknown-skin.png';
// ── Native Click Sound Engine ──
let lastClickSoundTime = 0;

function playClickSound() {
  if (settings && settings.sound_effects_enabled === false) return;
  const now = performance.now();
  if (now - lastClickSoundTime < 25) return; // Prevent double-trigger distortion
  lastClickSoundTime = now;

  try {
    invoke('play_click_sound').catch(() => {});
  } catch (_) {}
}

function initClickSoundListener() {
  const handleInteraction = (e) => {
    const target = e.target;
    if (!target) return;
    const interactive = target.closest(
      'button, .btn-play, .btn-play-gear, .pill-tab, .skin-anim-btn, .skin-speed-btn, ' +
      '.dressing-room-tab, .troubleshoot-option-btn, .color-swatch, .color-live-chip, ' +
      '.overlay-close, input[type="checkbox"], input[type="radio"], select, ' +
      '.inst-hide-btn, .inst-fav-btn, .inst-favorite-btn, .inst-troubleshoot-btn, .instance-card, ' +
      '.preset-card, .mod-card, .discover-card, .accordion-section, .account-button, ' +
      '.btn-ghost, .btn-accent, .btn-secondary, .btn-danger, .btn-danger-outline, ' +
      '.btn-outline, .btn-small, .btn-sm, .full-palette-card, .quick-palette-plate, ' +
      'a[href], .tab-page nav button, [role="button"], [role="tab"], [role="checkbox"]'
    );
    if (interactive && !interactive.disabled && !interactive.classList.contains('disabled')) {
      playClickSound();
    }
  };

  document.addEventListener('pointerdown', handleInteraction, { capture: true, passive: true });
}

// ── Custom Confirmation Modal for Instance Actions (Delete / Hide) ──
const INSTANCE_CONFIRM_SVGS = {
  delete: `<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6"/>`,
  hide: `<path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/>`,
};

// Optional "also delete this instance's own data folder" toggle, shown on
// the Delete Instance confirm modal only. `onConfirm` receives whether the
// toggle was on so the caller can act on it — it's never read directly off
// the DOM outside this module.
//
// `dataToggle.disabled` (and a reason) is used for instances whose data
// folder *is* the shared default `.minecraft` folder: turning it on there
// would wipe every instance's data, not just this one, so the checkbox is
// force-disabled and forced off rather than merely defaulting to off.
function showInstanceConfirmModal({ type, title, message, confirmText, isDanger, dataToggle, onConfirm }) {
  const overlay = document.getElementById('instance-confirm-overlay');
  const heading = document.getElementById('instance-confirm-heading');
  const icon = document.getElementById('instance-confirm-icon');
  const desc = document.getElementById('instance-confirm-desc');
  const actionBtn = document.getElementById('btn-instance-confirm-action');
  const cancelBtn = document.getElementById('btn-instance-confirm-cancel');
  const closeBtn = document.getElementById('btn-close-instance-confirm');
  const toggleWrap = document.getElementById('instance-confirm-data-toggle-wrap');
  const toggleRow = document.getElementById('instance-confirm-data-toggle-row');
  const toggleChk = document.getElementById('instance-confirm-data-checkbox');
  const toggleSub = document.getElementById('instance-confirm-data-toggle-sub');

  if (!overlay || !actionBtn) return;

  heading.textContent = title || 'Confirm Action';
  icon.innerHTML = INSTANCE_CONFIRM_SVGS[type] || INSTANCE_CONFIRM_SVGS.delete;
  if (isDanger) {
    icon.style.color = 'var(--danger, #ef4444)';
    actionBtn.className = 'btn-danger';
  } else {
    icon.style.color = 'var(--accent, #3b82f6)';
    actionBtn.className = 'btn-accent';
  }
  desc.textContent = message || '';
  actionBtn.textContent = confirmText || 'Confirm';

  // Always defaults to off — this only ever turns it back off explicitly,
  // never leaves a previous modal's "on" state lingering into this one.
  if (dataToggle && dataToggle.show) {
    toggleWrap.classList.remove('hidden');
    toggleChk.checked = false;
    toggleChk.disabled = !!dataToggle.disabled;
    toggleRow.classList.toggle('disabled', !!dataToggle.disabled);
    toggleSub.textContent = dataToggle.disabled
      ? (dataToggle.disabledReason || "Not available for this instance.")
      : (dataToggle.reason || "Mods, worlds, saves, configs and screenshots in this instance's own folder — permanently.");
  } else {
    toggleWrap.classList.add('hidden');
    toggleChk.checked = false;
    toggleChk.disabled = false;
    toggleRow.classList.remove('disabled');
  }

  const cleanup = () => {
    overlay.classList.add('hidden');
    actionBtn.onclick = null;
    cancelBtn.onclick = null;
    closeBtn.onclick = null;
  };

  cancelBtn.onclick = cleanup;
  closeBtn.onclick = cleanup;
  actionBtn.onclick = async () => {
    // Disabled implies "always off" regardless of what's checked.
    const deleteData = !!(dataToggle && dataToggle.show && !dataToggle.disabled && toggleChk.checked);
    cleanup();
    if (onConfirm) await onConfirm(deleteData);
  };

  overlay.classList.remove('hidden');
}

// ── 3D Skin Viewer (skin3d) ──
import {
  Render,
  IdleAnimation,
  WalkingAnimation,
  RunningAnimation,
  WaveAnimation,
  CrouchAnimation,
  FlyingAnimation,
  HitAnimation,
  NameTagObject
} from 'skin3d';

// Default test skin
const SHOW_SKIN_URL = 'https://s.namemc.com/i/ab729cae898846de.png';
const SHOW_SKIN_NAMEMC_URL = 'https://namemc.com/skin/ab729cae898846de';

const LOADER_ICONS = {
  vanilla: iconVanilla,
  fabric: iconFabric,
  forge: iconForge,
  neoforge: iconNeoforge,
  quilt: iconQuilt,
};

// Set while Smart Java Detection is actively downloading/extracting a
// missing Java runtime (updated by the `java-install-progress` listener
// set up in initSettings). The launch-button timeout below watches this so
// it doesn't declare a launch "timed out" just because Java is still being
// installed in the background.
let javaInstallInProgress = false;

// Set while a pre-launch libraries/assets verify pass (see
// `launch-verify-status` events from the backend) is actively running for
// whichever instance is currently launching. Mirrors `javaInstallInProgress`
// above: the launch-button timeout watches this too, so a slow verify pass
// (re-downloading a broken library, say) is never mistaken for a hung
// launch — the timeout only starts counting once this goes back to false.
let launchVerifyInProgress = false;

function loaderIcon(loader) {
  const key = (loader || 'vanilla').toLowerCase();
  return LOADER_ICONS[key] || LOADER_ICONS.vanilla;
}

function loaderLabel(loader) {
  const key = (loader || 'vanilla').toLowerCase();
  if (key === 'neoforge') return 'NeoForge';
  if (key === 'vanilla') return 'Vanilla';
  return key.charAt(0).toUpperCase() + key.slice(1);
}

// ══════════════════════════════════════════════════════════════════
// FALLBACK / EMPTY-STATE ICONS — plain inline SVG, no emoji anywhere.
// Each one is contextual to where it's used rather than one glyph
// reused everywhere: "?" for an unresolved/unknown item icon, and a
// different simple line icon per kind of "nothing here" state.
// ══════════════════════════════════════════════════════════════════
// A mod/project icon that couldn't be found or failed to load — "this is
// something, we just don't know its picture".
const ICON_UNKNOWN_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6"/><path d="M9.4 9.4a2.6 2.6 0 1 1 3.6 3.4c-.6.5-1 .9-1 1.7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/><circle cx="12" cy="16.9" r="0.95" fill="currentColor"/></svg>';
// Empty list of items (no mods installed, no instances yet).
const ICON_EMPTY_BOX_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M3.5 8.3 12 4l8.5 4.3V16L12 20.3 3.5 16V8.3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M3.9 8.1 12 12.4l8.1-4.3" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M12 12.4V20.3" stroke="currentColor" stroke-width="1.6"/></svg>';
// No search/filter results found.
const ICON_SEARCH_EMPTY_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="10.3" cy="10.3" r="6.3" stroke="currentColor" stroke-width="1.8"/><path d="m19.3 19.3-4.2-4.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';
// Nothing hidden / nothing to show that's "off" by choice.
const ICON_EYE_OFF_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M1.5 12C3 9 7 5 12 5s9 4 10.5 7c-1.5 3-5.5 7-10.5 7S3 15 1.5 12Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><circle cx="12" cy="12" r="3.1" stroke="currentColor" stroke-width="1.6"/></svg>';
// No music files found in the music folder.
const ICON_MUSIC_EMPTY_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M9 18V5.5L20 4v12.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/><circle cx="6.5" cy="18" r="2.5" stroke="currentColor" stroke-width="1.6"/><circle cx="17.5" cy="16.5" r="2.5" stroke="currentColor" stroke-width="1.6"/></svg>';
// Something failed to load.
const ICON_WARNING_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3.5 22 20H2L12 3.5Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M12 10v4.3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>';

// ══════════════════════════════════════════════════════════════════
// CARD CULLING — cheap "lightweight paint" mode for offscreen cards
// ══════════════════════════════════════════════════════════════════
// Long lists (Discover results, the instance list, settings cards) each use
// backdrop-filter blur, shadows, gradients, etc. per card. Repainting all of
// that for cards scrolled out of view is wasted GPU work. Rather than
// unmounting cards (which would drop their event listeners — e.g. the
// Discover download button), an IntersectionObserver just toggles a
// `.is-culled` class that strips the expensive-to-repaint visual effects
// while a card is off-screen, and restores them the moment it scrolls back
function enableCardCulling(container, cardSelector, options = {}) {
  if (!container) return;

  if (container._cullObserver) {
    container._cullObserver.disconnect();
    container._cullObserver = null;
  }

  const root = options.root || null;
  const rootMargin = options.rootMargin || '300px 0px';

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      const card = entry.target;
      if (entry.isIntersecting) {
        if (card.classList.contains('is-culled')) {
          card.classList.remove('is-culled');
          card.style.minHeight = '';
        }
        if (card._triggerLoad) {
          card._triggerLoad();
        }
      } else {
        if (!card.classList.contains('is-culled')) {
          const h = card.offsetHeight;
          if (h > 40) {
            card.style.minHeight = `${h}px`;
          }
          card.classList.add('is-culled');
        }
      }
    });
  }, {
    root,
    rootMargin,
    threshold: 0,
  });

  container._cullObserver = observer;
  container.dataset.cullingEnabled = '1';

  const cards = container.querySelectorAll(cardSelector);
  cards.forEach(card => observer.observe(card));

  container._cullRefresh = () => {
    const currentCards = container.querySelectorAll(cardSelector);
    currentCards.forEach(card => {
      observer.unobserve(card);
      observer.observe(card);
    });
  };
}

function refreshCardCullingIn(root) {
  if (!root) return;
  if (root.dataset && root.dataset.cullingEnabled === '1' && root._cullRefresh) {
    root._cullRefresh();
  }
  root.querySelectorAll('[data-culling-enabled="1"]').forEach(el => {
    if (el._cullRefresh) el._cullRefresh();
  });
}

const api = {
  playClickSound: () => invoke('play_click_sound'),
  reportActivity: () => invoke('report_activity'),
  getAccounts: () => invoke('list_accounts'),
  addOfflineAccount: (username) => invoke('add_offline_account', { username }),
  removeAccount: (id) => invoke('remove_account', { id }),
  setActiveAccount: (id) => invoke('set_active_account', { id }),
  microsoftDeviceCodeStart: () => invoke('microsoft_device_code_start'),
  microsoftDeviceCodePoll: () => invoke('microsoft_device_code_poll'),
  microsoftDeviceCodeCancel: () => invoke('microsoft_device_code_cancel'),
  refreshMicrosoftAccount: (id) => invoke('refresh_microsoft_account', { id }),
  refreshAllMicrosoftAccounts: () => invoke('refresh_all_microsoft_accounts'),
  cacheAccountSkin: (username, renderUrl) => invoke('cache_account_skin', { username, renderUrl }),
  listCachedSkins: () => invoke('list_cached_skins'),
  listSkins: () => invoke('list_skins'),
  importSkin: (sourcePath, name) => invoke('import_skin', { sourcePath, name }),
  deleteSkin: (pathOrId) => invoke('delete_skin', { pathOrId }),
  cacheSkinTexture: (name, textureUrl) => invoke('cache_skin_texture', { name, textureUrl }),
  uploadSkinToMojang: (skinPath, variant, accountId) => invoke('upload_skin_to_mojang', { skinPath, variant: variant || null, accountId: accountId || null }),
  resetMojangSkin: (accountId) => invoke('reset_mojang_skin', { accountId: accountId || null }),
  getAccountCapes: (accountId) => invoke('get_account_capes', { accountId: accountId || null }),
  equipMojangCape: (capeId, accountId) => invoke('equip_mojang_cape', { capeId: capeId || null, accountId: accountId || null }),
  syncPresets: () => invoke('sync_presets'),
  getSettings: () => invoke('get_settings'),
  updateSettings: (settings) => invoke('save_settings', { settings }),
  getMusicDir: () => invoke('get_music_dir'),
  openMusicFolder: () => invoke('open_music_folder'),
  openLauncherFolder: () => invoke('open_launcher_folder'),
  getLauncherVersion: () => invoke('get_launcher_version'),
  checkForUpdate: () => invoke('check_for_update'),
  downloadUpdate: (url) => invoke('download_update', { url }),
  installUpdate: (downloadedPath, relaunch) => invoke('install_update', { downloadedPath, relaunch }),
  openCurrentExeFolder: () => invoke('open_current_exe_folder'),
  listMusicFiles: () => invoke('list_music_files'),
  readMusicFile: (fileName) => invoke('read_music_file', { fileName }),
  getAvailableVersions: () => invoke('get_available_versions'),
  getCachedVersions: () => invoke('get_cached_versions'),
  installVersion: (minecraftVersion, loader, loaderVersion, directory, name, oldVersionId) =>
    invoke('install_minecraft', {
      payload: {
        minecraft_version: minecraftVersion || '',
        loader: (loader || 'vanilla').toLowerCase(),
        loader_version: loaderVersion || 'latest',
        directory: directory || null,
        name: name || null,
        old_version_id: oldVersionId || null,
      }
    }),
  launchGame: (versionId, offline) => invoke('launch_minecraft', { versionId, offline: offline ?? null }),
  updateInstance: (versionId, name, loaderVersion, javaPath, minRamMb, maxRamMb, jvmArgs) =>
    invoke('update_instance', {
      versionId,
      name: name || null,
      loaderVersion: loaderVersion || null,
      javaPath: javaPath !== undefined ? (javaPath || null) : null,
      minRamMb: minRamMb ? Number(minRamMb) : null,
      maxRamMb: maxRamMb ? Number(maxRamMb) : null,
      jvmArgs: jvmArgs !== undefined ? (jvmArgs || null) : null,
    }),
  deleteInstalledVersion: (versionId, directory) => invoke('delete_installed_version', { versionId, directory: directory || null }),
  deleteInstanceData: (directory, minecraftDirectory) => invoke('delete_instance_data', { directory, minecraftDirectory: minecraftDirectory || null }),
  scanMinecraftVersions: (directory) => invoke('scan_minecraft_versions', { directory: directory || null }),
  getHiddenInstances: () => invoke('get_hidden_instances'),
  hideInstance: (versionId) => invoke('hide_instance', { versionId }),
  unhideInstance: (versionId) => invoke('unhide_instance', { versionId }),
  getDependentInstances: (versionId) => invoke('get_dependent_instances', { versionId }),
  listJavaInstallations: () => invoke('list_java_installations'),
  installManagedJava: (major) => invoke('install_managed_java', { major }),
  deleteManagedJava: (major) => invoke('delete_managed_java', { major }),
  getManagedJavaRootPath: () => invoke('get_managed_java_root_path'),
  openManagedJavaDir: () => invoke('open_managed_java_dir'),
  addCustomJavaPath: (path) => invoke('add_custom_java_path', { path }),
  removeCustomJavaPath: (path) => invoke('remove_custom_java_path', { path }),
  onJavaInstallProgress: (cb) => listen('java-install-progress', cb),
  listMods: (gameDir) => invoke('list_mods', { directory: gameDir }),
  countAdvancements: (gameDir) => invoke('count_advancements', { directory: gameDir }),
  loadGlobalStats: () => invoke('load_global_stats'),
  saveGlobalStats: (stats) => invoke('save_global_stats', { stats }),
  // Backend only needs the mod file's path — it flips .jar <-> .jar.disabled
  // based on the current filename, so gameDir/enable aren't sent.
  toggleMod: (gameDir, modId, enable) => invoke('toggle_mod', { path: modId }),
  deleteMod: (gameDir, modId) => invoke('delete_mod', { path: modId }),
  deleteInstanceSubpath: (gameDir, relativePath) => invoke('delete_instance_subpath', { gameDir, relativePath }),
  onGameCrashed: (cb) => listen('game-crashed', cb),
  openModsFolder: (gameDir) => invoke('open_mods_folder', { directory: gameDir }),
  installModFiles: (paths, gameDir) => invoke('install_mod_files', { paths, directory: gameDir }),
  exportModsList: (path, content) => invoke('export_mods_list', { path, content }),
  readModsListFile: (path) => invoke('read_mods_list_file', { path }),
  getLogs: (level, source) => invoke('get_logs', { level, source }),
  clearLogs: () => invoke('clear_logs'),
  openDevtools: () => invoke('open_devtools'),
  updateDiscordPresence: (tab, playingInstance, mcVersion) =>
    invoke('update_discord_presence', { tab, playingInstance, mcVersion }),
  onLog: (cb) => listen('log', cb),
  onDownloadProgress: (cb) => listen('download-progress', cb),
  onLaunchVerifyStatus: (cb) => listen('launch-verify-status', cb),
  previewModpack: (filePath) => invoke('preview_modpack', { filePath }),
  importModpack: (filePath, instanceName, useCustomDirectory, customDirectory) =>
    invoke('import_modpack', {
      payload: {
        file_path: filePath,
        instance_name: instanceName,
        use_custom_directory: !!useCustomDirectory,
        custom_directory: customDirectory || null,
      },
    }),
  onModpackImportProgress: (cb) => listen('modpack-import-progress', cb),
  onGenericDownloadProgress: (cb) => listen('generic-download-progress', cb),
  pauseDownload: () => invoke('pause_download'),
  resumeDownload: () => invoke('resume_download'),
  cancelDownload: () => invoke('cancel_download'),
  // Cancels a "generic" download (mod download/update, dependency install,
  // Java runtime download, etc.) by the id its card was given.
  cancelGenericDownload: (downloadId) => invoke('cancel_generic_download', { downloadId }),
  getDefaultMcDir: () => invoke('get_default_minecraft_dir'),
  getRunningInstances: () => invoke('get_running_instances'),
  killInstance: (versionId) => invoke('kill_instance', { versionId }),
  getInstanceConsoleLogs: (versionId) => invoke('get_instance_console_logs', { versionId }),
  checkLinuxZlibConflict: () => invoke('check_linux_zlib_conflict'),
  installLinuxPackage: (packageType) => invoke('install_linux_package', { packageType }),
  discoverSearch: (query, projectType, loader, gameVersion, categories, environment, license, openSourceOnly, page, limit) =>
    invoke('discover_search', {
      query, projectType,
      loader: loader || null,
      gameVersion: gameVersion || null,
      categories: (categories && categories.length) ? categories : null,
      environment: environment || null,
      license: license || null,
      openSourceOnly: !!openSourceOnly,
      page, limit,
    }),
  discoverGetVersions: (projectId, loader, gameVersion) =>
    invoke('discover_get_versions', { projectId, loader: loader || null, gameVersion: gameVersion || null }),
  discoverGetProject: (projectId) => invoke('discover_get_project', { projectId }),
  discoverDownload: (directory, projectType, fileUrl, fileName, downloadId) =>
    invoke('discover_download', { directory, projectType, fileUrl, fileName, downloadId: downloadId || null }),
  // Modpacks aren't dropped into a folder like a mod/resourcepack — the
  // file is fetched to a scratch path first, then handed to
  // previewModpack/importModpack exactly like a dragged-in .mrpack/.zip.
  discoverDownloadToTemp: (fileUrl, fileName) =>
    invoke('discover_download_to_temp', { fileUrl, fileName }),
  discoverGetGameVersions: () => invoke('discover_get_game_versions'),
  discoverGetCategories: (projectType) => invoke('discover_get_categories', { projectType }),
  discoverGetResolutions: (projectType) => invoke('discover_get_resolutions', { projectType }),
  discoverGetLicenses: () => invoke('discover_get_licenses'),
  cacheModIcon: (url) => invoke('cache_mod_icon', { url }),
  identifyModsByHash: (hashes) => invoke('identify_mods_by_hash', { hashes }),
  discoverGetProjectsBatch: (ids) => invoke('discover_get_projects_batch', { ids }),
  listPresets: () => invoke('list_presets'),
  getLocalPresets: () => invoke('get_local_presets'),
  syncPresets: () => invoke('sync_presets'),
  onPresetSynced: (cb) => listen('preset-synced', cb),
  onPresetSyncStart: (cb) => listen('preset-sync-start', cb),
  onPresetSyncDone: (cb) => listen('preset-sync-done', cb),
  getPresetIconPath: (presetId) => invoke('get_preset_icon_path', { presetId }),
  resolvePresetModUrl: (modrinthId, loader, mcVersion) =>
    invoke('resolve_preset_mod_url', { modrinthId, loader: loader || null, mcVersion: mcVersion || null }),
  getPresetInstalledMods: (presetId, directory) =>
    invoke('get_preset_installed_mods', { presetId, directory }),
  applyPresetConfig: (presetId, directory) =>
    invoke('apply_preset_config', { presetId, directory }),
  onInstanceLog: (cb) => listen('instance-log', cb),
  onRunningInstancesChanged: (cb) => listen('running-instances-changed', cb),
  onGameAdvancement: (cb) => listen('game-advancement', cb),
  openInstanceConsoleWindow: async (versionId, name) => {
    const { WebviewWindow } = window.__TAURI__.webviewWindow;
    const label = 'console-' + versionId.replace(/[^a-zA-Z0-9_-]/g, '_');
    try {
      const existing = await WebviewWindow.getByLabel(label);
      if (existing) {
        try {
          if (existing.unminimize) await existing.unminimize();
          if (existing.show) await existing.show();
          if (existing.setFocus) await existing.setFocus();
        } catch (focusErr) {
          console.warn('Could not focus existing console window:', focusErr);
        }
        return;
      }
    } catch (checkErr) {
      console.warn('Error checking existing console window:', checkErr);
    }
    const url = 'console.html?instance=' + encodeURIComponent(versionId) + '&name=' + encodeURIComponent(name || versionId);
    new WebviewWindow(label, {
      url,
      title: 'Console — ' + (name || versionId),
      width: 820,
      height: 560,
      minWidth: 480,
      minHeight: 320,
      focus: true,
    });
  },
};

// ── State ──
let settings = null;
let selectedInstanceId = null;

// ── Toast ──
// ── Toast / Notification System (matches Zero-Launcher NotificationPanel) ──
const TOAST_DURATION = 5000;
const TOAST_ERROR_DURATION = 8000;
// Toasts with an action button (e.g. "Retry") stay up longer so there's
// actually time to click before it auto-dismisses.
const TOAST_ACTION_DURATION = 12000;
const TOAST_MAX_VISIBLE = 4;
const toastQueue = [];
let activeToasts = [];

// Tracks whether the launcher window currently has OS focus. Error toasts
// are held back while the window isn't focused (minimized, alt-tabbed
// away, on another desktop/monitor while playing) and released the
// moment focus comes back, rather than popping up — and possibly
// stealing attention or getting dismissed unseen — while nobody's
// actually looking at the launcher. Non-error toasts (success/info/
// warning) aren't held back; those are for things the user just did.
let windowHasFocus = typeof document !== 'undefined' ? document.hasFocus() : true;
if (typeof window !== 'undefined') {
  window.addEventListener('focus', () => {
    windowHasFocus = true;
    drainToastQueue();
  });
  window.addEventListener('blur', () => { windowHasFocus = false; });
}

function drainToastQueue() {
  // Walk the queue in order, but skip over (leave in place) any error
  // toast while the window is unfocused rather than stopping at it — a
  // success/info toast queued behind a held-back error should still show.
  let i = 0;
  while (activeToasts.length < TOAST_MAX_VISIBLE && i < toastQueue.length) {
    const next = toastQueue[i];
    if (next.type === 'error' && !windowHasFocus) {
      i += 1;
      continue;
    }
    toastQueue.splice(i, 1);
    spawnToast(next.message, next.type, next.title, next.actions);
  }
}

function showToast(message, type = 'info', title, actions) {
  if (!title) {
    title = type === 'success' ? 'Success' : type === 'error' ? 'Error' : type === 'warning' ? 'Warning' : 'Info';
  }
  if (type === 'error' && !windowHasFocus) {
    // Held until the window regains focus (see drainToastQueue). Still
    // queued — not dropped — so the user sees it as soon as they're back,
    // it just won't visibly interrupt/pop up while they're away.
    toastQueue.push({ message, type, title, actions });
    return;
  }
  if (activeToasts.length >= TOAST_MAX_VISIBLE) {
    toastQueue.push({ message, type, title, actions });
    return;
  }
  spawnToast(message, type, title, actions);
}

function spawnToast(message, type, title, actions) {
  const c = document.getElementById('toast-container');
  // Give toasts with an action button extra time so there's a real chance
  // to click it before the auto-dismiss timer fires.
  const duration = (actions && actions.length) ? TOAST_ACTION_DURATION
    : type === 'error' ? TOAST_ERROR_DURATION : TOAST_DURATION;

  const iconMap = { success: '\u2714', error: '\u2715', warning: '\u26A0', info: '\u2139' };
  const icon = iconMap[type] || iconMap.info;

  // Notification Style setting — same 5 looks as before, remapped onto the
  // lightweight base markup (no icon badge, no progress bar element, no
  // animation): the class only ever changes flat background/border/radius.
  const styleMap = {
    'glass':      'toast-style-glass',
    'neon':       'toast-style-neon',
    'solid card': 'toast-style-solid-card',
    'pill':       'toast-style-pill',
    'minimal':    'toast-style-minimal',
    // legacy compat
    'minimal outline': 'toast-style-minimal',
    'frosted glass':   'toast-style-glass',
    'solid':           'toast-style-solid-card',
  };
  const rawStyle = (settings && settings.notification_style) ? settings.notification_style.toLowerCase() : 'glass';
  const styleClass = styleMap[rawStyle] || 'toast-style-glass';

  const t = document.createElement('div');
  t.className = `toast toast-${type} ${styleClass}`;
  const actionsHtml = (actions && actions.length)
    ? `<div class="toast-actions">${actions.map((a, i) => `<button class="toast-action-btn" data-action-index="${i}">${escapeHtml(a.label)}</button>`).join('')}</div>`
    : '';
  // Copy button — only on error toasts, so the exact error text (URLs,
  // status codes, etc.) can be pasted elsewhere without retyping it.
  const copyHtml = type === 'error'
    ? `<button class="toast-copy" title="Copy error details" aria-label="Copy error details">⧉</button>`
    : '';
  t.innerHTML = `
    <span class="toast-icon">${icon}</span>
    <div class="toast-content">
      <div class="toast-title">${title}</div>
      ${message ? `<div class="toast-message">${message}</div>` : ''}
      ${actionsHtml}
    </div>
    <div class="toast-controls">
      <button class="toast-close" aria-label="Dismiss notification">\u2715</button>
      ${copyHtml}
    </div>
  `;
  c.appendChild(t);

  // Enter: flip the opacity class next frame so the browser actually
  // transitions it instead of starting already-visible. One-shot
  // transition, not a running animation — nothing left compositing once
  // it settles.
  requestAnimationFrame(() => t.classList.add('toast-visible'));

  const entry = { el: t, removed: false, timer: null };
  activeToasts.push(entry);

  // Close button
  t.querySelector('.toast-close').addEventListener('click', () => dismissToast(entry));

  // Copy button (error toasts only) — copies title + message as plain text.
  const copyBtn = t.querySelector('.toast-copy');
  if (copyBtn) {
    copyBtn.addEventListener('click', async () => {
      const text = message ? `${title}: ${message}` : title;
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
        } else {
          // Fallback for contexts without the async Clipboard API.
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          ta.remove();
        }
        copyBtn.classList.add('toast-copy-done');
        copyBtn.textContent = '✓';
        setTimeout(() => {
          copyBtn.classList.remove('toast-copy-done');
          copyBtn.textContent = '⧉';
        }, 1400);
      } catch {
        showToast('Could not copy to clipboard.', 'warning');
      }
    });
  }

  // Action buttons (e.g. the version-list error's "Refresh") — run the
  // caller's handler, then dismiss the toast like a normal interaction.
  if (actions && actions.length) {
    t.querySelectorAll('.toast-action-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const action = actions[Number(btn.dataset.actionIndex)];
        if (action && typeof action.onClick === 'function') action.onClick();
        dismissToast(entry);
      });
    });
  }

  // Auto-dismiss: a single plain JS timer instead of a multi-second CSS
  // animation. No countdown bar to paint/composite for the toast's whole
  // lifetime — this is the main lag fix, since several toasts previously
  // meant several concurrently-animating layers for 5-8s each. Hovering
  // pauses it (clearTimeout) and restarts on leave, same end behavior as
  // before without any per-frame work.
  const startTimer = () => {
    entry.timer = setTimeout(() => dismissToast(entry), duration);
  };
  startTimer();
  t.addEventListener('mouseenter', () => { if (entry.timer) { clearTimeout(entry.timer); entry.timer = null; } });
  t.addEventListener('mouseleave', () => { if (!entry.removed && !entry.timer) startTimer(); });
}

function dismissToast(entry) {
  if (entry.removed) return;
  entry.removed = true;
  if (entry.timer) clearTimeout(entry.timer);
  entry.el.classList.remove('toast-visible');
  setTimeout(() => {
    entry.el.remove();
    activeToasts = activeToasts.filter(e => e !== entry);
    drainToastQueue();
  }, 130);
}
window.showToast = showToast;

// ══════════════════════════════════════════════════════════════════
// TABS
// ══════════════════════════════════════════════════════════════════
// Bumped on every tab switch. Each click captures the token at the time
// it fired; anything that finishes later (a mod list load, a Discover
// search, etc.) checks its captured token against this before touching
// the DOM. That's what stops rapid switching from looking like it "keeps
// going" after you stop — a fast 1→2→3→4 no longer lets tab 1's slow
// network response land on top of tab 4 a second later, it just quietly
// no-ops instead.
let latestTabToken = 0;

function initTabs() {
  document.querySelectorAll('.pill-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      const tabId = btn.dataset.tab;

      // Re-clicking the tab you're already on used to re-run its entire
      // load chain (mod list refetch, Discord RPC ping, etc.) for no
      // reason — harmless individually, but it's exactly the kind of
      // extra in-flight work that piles up during rapid clicking. Skip it.
      if (btn.classList.contains('active')) return;

      const myToken = ++latestTabToken;

      document.querySelectorAll('.pill-tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
      const page = document.getElementById('tab-' + tabId);
      if (page) {
        page.classList.add('active');
        refreshCardCullingIn(page);
      }
      // Lazy-load data when switching
      if (tabId === 'instances') {
        if (!skinMiniPreviewInstance) {
          initSkinMiniPreview();
        } else {
          skinMiniPreviewInstance.renderPaused = false;
          resizeSkinMiniPreview();
          updateSkinMiniPreview();
        }
      } else {
        // Pause 3D WebGL render loop when not viewing Instances tab to ensure silky smooth scrolling
        if (skinMiniPreviewInstance) {
          skinMiniPreviewInstance.renderPaused = true;
        }
      }
      if (tabId === 'mods') {
        showModsTabLoading();
        loadModInstances().then(() => {
          // Bail if the user has already switched to another tab since
          // this chain started — don't render mod data into a tab that
          // isn't (or is no longer) the one being looked at.
          if (myToken !== latestTabToken) return;
          return loadMods();
        }).catch(() => {}).finally(() => {
          if (myToken !== latestTabToken) return;
          hideModsTabLoading();
        });
      }
      if (tabId === 'discover') {
        initDiscoverTabIfNeeded();
      }

      if (tabId === 'presets') initPresetsTabIfNeeded();

      // Update Discord RPC — skip if superseded by a later switch already.
      if (myToken === latestTabToken) {
        const tabName = tabId.charAt(0).toUpperCase() + tabId.slice(1);
        api.updateDiscordPresence(tabName, null, null).catch(() => { });
      }
    });
  });

  // Global Keybinds: Alt + 1..4 for fast Tab Switching, Alt + 5 / Alt + S for Settings Modal
  window.addEventListener('keydown', (e) => {
    if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
      const tabMap = {
        '1': 'instances',
        '2': 'mods',
        '3': 'discover',
        '4': 'presets',
      };
      const targetTab = tabMap[e.key];
      if (targetTab) {
        e.preventDefault();
        const tabBtn = document.querySelector(`.pill-tab[data-tab="${targetTab}"]`);
        if (tabBtn) tabBtn.click();
      } else if (e.key === '5' || e.key.toLowerCase() === 's') {
        e.preventDefault();
        const overlay = document.getElementById('settings-modal-overlay');
        if (overlay && !overlay.classList.contains('hidden')) {
          closeSettingsModal();
        } else {
          openSettingsModal();
        }
      }
    }
  });
}

// ══════════════════════════════════════════════════════════════════
// ACCOUNTS SYSTEM & MODAL MANAGER
// ══════════════════════════════════════════════════════════════════

// Privacy → "Hide Username in UI": masks the real username everywhere it's
// displayed (header pill, account switcher modal) without touching the
// actual value used to launch the game or anything sent to the backend.
// Keeps the first character so multiple accounts stay distinguishable.
function maskUsernameForDisplay(name) {
  if (!name) return name;
  if (!settings || !settings.hide_username) return name;
  const visible = name.charAt(0);
  return visible + '•'.repeat(Math.max(3, name.length - 1));
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str ?? '';
  return div.innerHTML;
}

// Re-renders anything currently showing a username so a live toggle of
// "Hide Username in UI" takes effect immediately, without waiting for the
// next unrelated refresh.
function applyUsernamePrivacy() {
  refreshAccountUI().catch(() => {});
}

// Shows one of: the account list + "add account" button (accounts exist),
// or the empty-state choice screen (no accounts yet). Also used to reset
// the modal back to its default view whenever it's (re)opened.
function showAccountView(view) {
  const map = {
    list: 'account-list-section',
    empty: 'account-empty-state',
    msa: 'account-msa-section',
    offline: 'account-offline-section',
  };
  Object.values(map).forEach(id => {
    const el = document.getElementById(id);
    if (el) el.classList.add('hidden');
  });
  const target = document.getElementById(map[view]);
  if (target) target.classList.remove('hidden');
}

// Accounts Manager auto-refresh: while the modal is open, silently
// re-validate every saved Microsoft account's session in the background
// and re-render the list, so an account that expires (or gets fixed by
// signing in on another device) shows up-to-date without the user having
// to manually hit Verify or close/reopen the modal.
let accountManagerAutoRefreshTimer = null;
const ACCOUNT_MANAGER_AUTO_REFRESH_MS = 60_000;

function startAccountManagerAutoRefresh() {
  stopAccountManagerAutoRefresh();
  accountManagerAutoRefreshTimer = setInterval(async () => {
    try {
      await api.refreshAllMicrosoftAccounts();
    } catch (_) {
      // Best-effort — a failed refresh just leaves needs_reauth set on
      // whichever account it was, which the next refreshAccountUI() call
      // will surface normally.
    }
    refreshAccountUI().catch(() => {});
  }, ACCOUNT_MANAGER_AUTO_REFRESH_MS);
}

function stopAccountManagerAutoRefresh() {
  if (accountManagerAutoRefreshTimer) {
    clearInterval(accountManagerAutoRefreshTimer);
    accountManagerAutoRefreshTimer = null;
  }
}

// Small type-indicator icons for the account list — a Microsoft/Xbox-style
// glyph for online accounts, a "no signal" glyph for offline ones (they
// never touch the network to authenticate).
const ACCOUNT_TYPE_ICON_MICROSOFT = `<svg viewBox="0 0 24 24" width="12" height="12" style="flex-shrink:0;"><rect x="2" y="2" width="9" height="9" fill="#f35325"/><rect x="13" y="2" width="9" height="9" fill="#81bc06"/><rect x="2" y="13" width="9" height="9" fill="#05a6f0"/><rect x="13" y="13" width="9" height="9" fill="#ffba08"/></svg>`;
const ACCOUNT_TYPE_ICON_OFFLINE = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;"><line x1="1" y1="1" x2="23" y2="23"/><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/><path d="M10.71 5.05A16 16 0 0 1 22.58 9"/><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><line x1="12" y1="20" x2="12.01" y2="20"/></svg>`;

async function refreshAccountUI() {
  try {
    renderInstanceList();
    updateSkinMiniPreview().catch(() => {});

    const accounts = await api.getAccounts();
    const active = accounts.find(a => a.is_active);
    const accountNameEl = document.getElementById('account-name');
    if (accountNameEl) {
      accountNameEl.textContent = active
        ? (shouldUseUnknownNametag(active) ? 'Unknown' : maskUsernameForDisplay(active.username))
        : 'No account';
    }

    // If the active Microsoft account's session expired (or something else
    // went wrong signing it back in), flag the header account button so
    // it's obvious at a glance that it needs attention before it'll work.
    const accountBtnEl = document.getElementById('account-btn');
    if (accountBtnEl) {
      const needsReauth = !!(active && active.needs_reauth);
      accountBtnEl.classList.toggle('account-button-needs-reauth', needsReauth);
      accountBtnEl.title = needsReauth
        ? 'Microsoft sign-in expired — click to sign in again'
        : '';
    }

    const headerImg = document.getElementById('account-header-avatar-img');
    const headerFallback = document.getElementById('account-header-fallback');
    if (headerImg && headerFallback) {
      if (active && !shouldUseUnknownProfilePic(active)) {
        const headKey = encodeURIComponent(active.mc_uuid || active.username || 'MHF_Steve');
        headerImg.src = `https://mc-heads.net/avatar/${headKey}/32`;
        headerImg.classList.remove('hidden');
        headerFallback.style.display = 'none';
        headerImg.onerror = () => {
          headerImg.classList.add('hidden');
          headerFallback.style.display = '';
        };
      } else {
        // No active account, or the account's Profile Picture / Nametag is set to
        // "Use Unknown" — show the black "?" placeholder instead of
        // fetching/displaying the real head render.
        headerImg.classList.add('hidden');
        headerImg.removeAttribute('src');
        headerFallback.style.display = '';
      }
    }

    const list = document.getElementById('modal-account-list');
    if (!list) return;
    list.innerHTML = '';

    if (accounts.length === 0) {
      showAccountView('empty');
      return;
    }

    showAccountView('list');

    accounts.forEach(acc => {
      const item = document.createElement('div');
      item.className = 'glass-card' + (acc.is_active ? ' active' : '');
      item.style.cssText = `
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 10px 14px;
        border-radius: 8px;
        background: ${acc.is_active ? 'var(--accent-dim)' : 'rgba(255,255,255,0.04)'};
        border: 1px solid ${acc.is_active ? 'var(--accent)' : 'rgba(255,255,255,0.08)'};
        position: relative;
        overflow: hidden;
      `;

      const useUnknownTag = shouldUseUnknownNametag(acc);
      const useUnknownPic = shouldUseUnknownProfilePic(acc);
      const initial = (useUnknownTag || useUnknownPic) ? '?' : (acc.username || 'A').charAt(0).toUpperCase();
      const shownName = escapeHtml(useUnknownTag ? 'Unknown' : maskUsernameForDisplay(acc.username));
      const isMsa = acc.account_type === 'microsoft';
      // Player-head avatar: keyed by the real Minecraft UUID when we have one
      // (Microsoft accounts), otherwise by username (offline accounts get
      // whatever skin — Steve/Alex — that name resolves to). Skipped
      // entirely when this account's Profile Picture / Nametag is set to "Use
      // Unknown" — shows the black "?" placeholder instead.
      const headKey = encodeURIComponent(acc.mc_uuid || acc.username || 'MHF_Steve');
      const headUrl = `https://mc-heads.net/avatar/${headKey}/64`;
      const needsReauth = !!acc.needs_reauth;
      // Expired sessions get a subtle animated amber accent (CSS-only,
      // opacity/transform based so it stays cheap to paint) plus a
      // matching "Sign-in expired" tag next to the account name — no more
      // masked cracked-glass texture layer.
      item.classList.toggle('account-item-reauth', needsReauth);
      item.classList.toggle('account-item-clickable', !acc.is_active);
      item.dataset.id = acc.id;
      item.innerHTML = `
        <div style="display:flex; align-items:center; gap:12px; position:relative; z-index:1;">
          <div class="account-avatar">
            <span class="account-avatar-fallback${useUnknownPic ? ' account-avatar-fallback-mc' : ''}">${useUnknownPic ? '?' : initial}</span>
            ${useUnknownPic ? '' : `<img src="${headUrl}" alt="" loading="lazy" onerror="this.remove()" onload="this.previousElementSibling.style.display='none'" />`}
          </div>
          <div>
            <div style="display:flex; align-items:center; gap:8px; font-size:14px;">
              <span style="font-weight:600; color:var(--text);">${shownName}</span>
              ${needsReauth ? '<span class="account-reauth-tag">⚠ Sign-in expired</span>' : ''}
            </div>
            <div style="display:flex; align-items:center; gap:5px; font-size:11px; color:var(--text-muted); margin-top:2px;">
              ${isMsa ? ACCOUNT_TYPE_ICON_MICROSOFT : ACCOUNT_TYPE_ICON_OFFLINE}
              <span>${isMsa ? 'Microsoft Account' : 'Offline Account'}</span>
              ${(!needsReauth && acc.is_active) ? '<span style="color:var(--accent); font-weight:700; margin-left:4px;">● In Use</span>' : ''}
            </div>
          </div>
        </div>
        <div style="display:flex; gap:6px; position:relative; z-index:1;">
          <button class="btn-danger-outline btn-sm btn-delete-account" data-id="${acc.id}" title="Remove Account">✕</button>
        </div>
      `;
      list.appendChild(item);
    });

    // Clicking anywhere on a non-active account's card selects it (the
    // delete button stops propagation below, so it's excluded). Already-
    // active cards aren't clickable — there's nothing to switch to.
    list.querySelectorAll('.account-item-clickable').forEach(cardEl => {
      cardEl.addEventListener('click', async () => {
        try {
          await api.setActiveAccount(cardEl.dataset.id);
          await refreshAccountUI();
          showToast('Switched account successfully', 'success');
        } catch (err) {
          showToast('Failed to switch account: ' + err, 'error');
        }
      });
    });

    list.querySelectorAll('.btn-delete-account').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
          await api.removeAccount(btn.dataset.id);
          await refreshAccountUI();
          showToast('Account removed', 'info');
        } catch (err) {
          showToast('Failed to remove account: ' + err, 'error');
        }
      });
    });

  } catch (e) {
    console.error('Failed to load accounts UI', e);
  }
}

// Reusable Microsoft device-code sign-in flow. Both the Accounts Manager
// modal and the setup wizard's Account step need the exact same
// request-code / poll / cancel behavior, just wired to different DOM
// elements — this factory keeps that logic in one place instead of
// duplicated per caller.
function createDeviceSignInFlow({ methodChoiceEl, devicePanelEl, deviceCodeEl, deviceStatusEl, onSuccess }) {
  let devicePollTimer = null;
  let deviceVerificationUri = '';

  function stopDevicePolling() {
    if (devicePollTimer) {
      clearTimeout(devicePollTimer);
      devicePollTimer = null;
    }
  }

  function setDeviceStatus(text, isError) {
    if (!deviceStatusEl) return;
    deviceStatusEl.innerHTML = isError
      ? escapeHtml(text)
      : `<span class="msa-device-spinner" style="width:10px; height:10px; border-radius:50%; border:2px solid var(--accent); border-top-color:transparent; display:inline-block; animation: msa-spin 0.8s linear infinite;"></span> ${escapeHtml(text)}`;
    deviceStatusEl.style.color = isError ? 'var(--danger, #ef4444)' : 'var(--text-muted)';
  }

  async function pollDeviceCode(intervalSeconds) {
    try {
      const account = await api.microsoftDeviceCodePoll();
      if (account) {
        stopDevicePolling();
        await onSuccess(account);
        return;
      }
      devicePollTimer = setTimeout(() => pollDeviceCode(intervalSeconds), intervalSeconds * 1000);
    } catch (e) {
      stopDevicePolling();
      setDeviceStatus(String(e), true);
      showToast('Microsoft sign-in failed: ' + e, 'error');
    }
  }

  if (deviceCodeEl && !deviceCodeEl.dataset.copyBound) {
    deviceCodeEl.dataset.copyBound = '1';
    deviceCodeEl.style.cursor = 'pointer';
    deviceCodeEl.title = 'Click to copy code';
    deviceCodeEl.addEventListener('click', async () => {
      const code = (deviceCodeEl.textContent || '').trim();
      if (code && code !== '— — — — —') {
        try {
          if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(code);
          } else {
            throw new Error('Clipboard API not available');
          }
          showToast(`Copied code "${code}" to clipboard!`, 'info');
        } catch (_) {
          const ta = document.createElement('textarea');
          ta.value = code;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          try {
            document.execCommand('copy');
            showToast(`Copied code "${code}" to clipboard!`, 'info');
          } catch (e) {
            showToast(`Failed to copy code: ${e}`, 'error');
          }
          document.body.removeChild(ta);
        }
      }
    });
  }

  async function start() {
    if (!devicePanelEl) return;
    if (methodChoiceEl) methodChoiceEl.classList.add('hidden');
    devicePanelEl.classList.remove('hidden');
    if (deviceCodeEl) deviceCodeEl.textContent = '— — — — —';
    setDeviceStatus('Requesting a code…', false);
    try {
      const startRes = await api.microsoftDeviceCodeStart();
      deviceVerificationUri = startRes.verification_uri || 'https://microsoft.com/link';
      if (deviceCodeEl) {
        deviceCodeEl.textContent = startRes.user_code;
        deviceCodeEl.title = 'Click to copy code';
      }
      setDeviceStatus('Waiting for you to sign in…', false);
      const interval = Math.max(startRes.interval || 5, 3);
      devicePollTimer = setTimeout(() => pollDeviceCode(interval), interval * 1000);
    } catch (e) {
      setDeviceStatus(String(e), true);
      showToast('Could not start device sign-in: ' + e, 'error');
    }
  }

  function cancel() {
    stopDevicePolling();
    api.microsoftDeviceCodeCancel().catch(() => {});
    if (devicePanelEl) devicePanelEl.classList.add('hidden');
    if (methodChoiceEl) methodChoiceEl.classList.remove('hidden');
  }

  function openVerificationLink() {
    const url = deviceVerificationUri || 'https://microsoft.com/link';
    if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
      window.__TAURI__.shell.open(url);
    } else {
      window.open(url, '_blank');
    }
  }

  return { start, cancel, openVerificationLink, stopDevicePolling };
}

function initAccountDropdown() {
  const accountBtn = document.getElementById('account-btn');
  const modalOverlay = document.getElementById('account-modal-overlay');
  const closeBtn = document.getElementById('btn-close-account-modal');
  const doneBtn = document.getElementById('btn-done-account-modal');
  const createBtn = document.getElementById('btn-modal-add-account');
  const usernameInput = document.getElementById('modal-new-username');
  const showAddBtn = document.getElementById('btn-show-add-account');
  const choiceMsaBtn = document.getElementById('btn-choice-msa');
  const choiceOfflineBtn = document.getElementById('btn-choice-offline');
  const backBtns = document.querySelectorAll('.btn-back-to-choices');

  // Device-code sign-in elements
  const methodChoice = document.getElementById('msa-method-choice');
  const devicePanel = document.getElementById('msa-device-panel');
  const deviceLoginBtn = document.getElementById('btn-msa-device-login');
  const deviceOpenBtn = document.getElementById('btn-msa-device-open');
  const deviceCancelBtn = document.getElementById('btn-msa-device-cancel');

  const deviceFlow = createDeviceSignInFlow({
    methodChoiceEl: methodChoice,
    devicePanelEl: devicePanel,
    deviceCodeEl: document.getElementById('msa-device-code'),
    deviceStatusEl: document.getElementById('msa-device-status'),
    onSuccess: async (account) => {
      await refreshAccountUI();
      showToast(`Signed in as ${account.username || 'Microsoft account'}!`, 'success');
    },
  });

  function startDeviceCodeSignIn() {
    return deviceFlow.start();
  }

  function backToMethodChoice() {
    deviceFlow.cancel();
  }
  // Open modal on account top-bar button click
  if (accountBtn && modalOverlay) {
    accountBtn.addEventListener('click', () => {
      modalOverlay.classList.remove('hidden');
      refreshAccountUI();
      startAccountManagerAutoRefresh();
    });
  }

  // "+ Add Account" (shown once accounts already exist) -> same choice screen
  if (showAddBtn) {
    showAddBtn.addEventListener('click', () => showAccountView('empty'));
  }

  if (choiceMsaBtn) {
    choiceMsaBtn.addEventListener('click', () => {
      backToMethodChoice();
      showAccountView('msa');
    });
  }

  if (choiceOfflineBtn) {
    choiceOfflineBtn.addEventListener('click', () => {
      showAccountView('offline');
      if (usernameInput) usernameInput.focus();
    });
  }

  backBtns.forEach(btn => {
    btn.addEventListener('click', async () => {
      backToMethodChoice();
      const accounts = await api.getAccounts().catch(() => []);
      showAccountView(accounts.length === 0 ? 'empty' : 'list');
    });
  });

  if (deviceLoginBtn) {
    deviceLoginBtn.addEventListener('click', startDeviceCodeSignIn);
  }

  if (deviceOpenBtn) {
    deviceOpenBtn.addEventListener('click', () => deviceFlow.openVerificationLink());
  }

  if (deviceCancelBtn) {
    deviceCancelBtn.addEventListener('click', backToMethodChoice);
  }

  // Close modal functions
  const closeModal = () => {
    deviceFlow.stopDevicePolling();
    stopAccountManagerAutoRefresh();
    if (modalOverlay) modalOverlay.classList.add('hidden');
  };

  if (closeBtn) closeBtn.addEventListener('click', closeModal);
  if (doneBtn) doneBtn.addEventListener('click', closeModal);

  // Close on backdrop click
  if (modalOverlay) {
    modalOverlay.addEventListener('click', (e) => {
      if (e.target === modalOverlay) closeModal();
    });
  }

  // Create account handler
  async function handleCreateAccount() {
    if (!usernameInput) return;
    const username = usernameInput.value.trim();
    if (!username) {
      showToast('Please enter a username', 'warning');
      return;
    }
    if (username.length > 16) {
      showToast('Username must be 16 characters or less', 'warning');
      return;
    }

    try {
      await api.addOfflineAccount(username);
      usernameInput.value = '';
      await refreshAccountUI();
      showToast(`Account "${username}" created!`, 'success');
    } catch (err) {
      showToast(String(err), 'error');
    }
  }

  if (createBtn) {
    createBtn.addEventListener('click', (e) => {
      e.preventDefault();
      handleCreateAccount();
    });
  }

  if (usernameInput) {
    usernameInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleCreateAccount();
      }
    });
  }
}

// ══════════════════════════════════════════════════════════════════
// FLOATING DOWNLOAD WIDGET (bottom-left)
// ══════════════════════════════════════════════════════════════════
// Shared "download" glyph (an arrow into a tray) used anywhere an update /
// download action needs an icon instead of the old ⬇️ emoji — the per-mod
// update button, the toolbar "Update All" button, etc.
const DOWNLOAD_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 4v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M6.5 11.5 12 17l5.5-5.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M5 20h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
// Up-arrow-to-tray / down-arrow-to-tray icons used on the Export/Import Mods
// buttons and overlay titles — same stroke style as DOWNLOAD_ICON_SVG above.
const EXPORT_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 15V3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M7.5 7.5 12 3l4.5 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const IMPORT_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M7.5 10.5 12 15l4.5-4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

// ── Download-card type icons ────────────────────────────────────────
// A card's left-hand icon reflects *what* is being downloaded: an actual
// mod/resourcepack/modpack/preset/loader picture when one is known, or
// one of these flat glyphs otherwise. Kept in the same stroke style as
// the rest of the app's line icons (see DOWNLOAD_ICON_SVG above).
const DL_ICON_UPDATE_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M20 12a8 8 0 1 1-2.6-5.9" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M20 4v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const DL_ICON_WRENCH_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M14.7 6.3a4 4 0 0 0-5.4 4.7L4 16.3V20h3.7l5.3-5.3a4 4 0 0 0 4.7-5.4l-2.8 2.8-2-2 2.8-2.8Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round"/></svg>';
const DL_ICON_PRESET_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M6 4.5h9.5L19 8v11.5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M8.5 10.5h7M8.5 14h7M8.5 17.5h4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
const DL_ICON_PACKAGE_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M3.5 8.3 12 4l8.5 4.3V16L12 20.3 3.5 16V8.3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M3.9 8.1 12 12.4l8.1-4.3M12 12.4V20.3" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>';
// Smart Java Detection uses the real Java coffee-cup mark (brand art, not
// a stroke glyph) so it reads as "this is Java" at a glance.
const DL_ICON_JAVA_SVG = javaIconSvgRaw;
const DL_ICON_GENERIC_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 4v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M6.5 11.5 12 17l5.5-5.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M5 20h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';

// Sets a download card's left icon. `opts.icon` picks the glyph family;
// `opts.iconUrl` (mod/modpack/discover-hit art) takes priority when
// present and falls back to the glyph if the image fails to load.
function setDlCardIcon(iconEl, opts) {
  opts = opts || {};
  iconEl.classList.remove('dl-card-icon-brand');
  if (opts.iconUrl) {
    const img = document.createElement('img');
    img.alt = '';
    img.draggable = false;
    img.loading = 'lazy';
    img.addEventListener('error', () => { setDlCardIcon(iconEl, { icon: opts.icon || 'mod' }); }, { once: true });
    img.src = opts.iconUrl;
    iconEl.innerHTML = '';
    iconEl.appendChild(img);
    return;
  }
  switch (opts.icon) {
    case 'loader':
      iconEl.innerHTML = `<img src="${opts.loaderIconUrl || ''}" alt="" draggable="false" />`;
      return;
    case 'update':
      iconEl.innerHTML = DL_ICON_UPDATE_SVG; return;
    case 'wrench':
      iconEl.innerHTML = DL_ICON_WRENCH_SVG; return;
    case 'preset':
      iconEl.innerHTML = DL_ICON_PRESET_SVG; return;
    case 'java':
      iconEl.classList.add('dl-card-icon-brand');
      iconEl.innerHTML = DL_ICON_JAVA_SVG; return;
    case 'mod':
      iconEl.innerHTML = DL_ICON_PACKAGE_SVG; return;
    default:
      iconEl.innerHTML = DL_ICON_GENERIC_SVG; return;
  }
}

let dlHideTimer = null;

function fmtBytes(n) {
  if (!n || n <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}
function fmtSpeed(bps) {
  if (!bps || bps <= 0) return '—';
  return fmtBytes(bps) + '/s';
}
function fmtEta(sec) {
  if (sec === null || sec === undefined) return '—';
  if (sec < 1) return '<1s';
  if (sec < 60) return `${Math.round(sec)}s`;
  const m = Math.floor(sec / 60);
  const s = Math.round(sec % 60);
  if (m < 60) return `${m}m ${s}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

// Generates a fresh unique id for a new download card (mod update,
// dependency install, etc.) — used both as the card's key in the downloads
// menu and, where relevant, as the download_id passed to the backend so
// Cancel actually aborts the in-flight transfer.
let dlIdCounter = 0;
function genDlId(prefix) {
  dlIdCounter++;
  return `${prefix}-${Date.now()}-${dlIdCounter}`;
}

// ══════════════════════════════════════════════════════════════════
// HIDDEN WINDOWS TRAY
// ══════════════════════════════════════════════════════════════════
// Some overlay "windows" (currently: the Modpack Extractor and Apply
// Preset windows) can run a task in the background — extracting a pack,
// downloading mods, etc. Closing the ✕ on one of these while its task is
// still running keeps the task going rather than cancelling it; this tray
// is how you find your way back to that window.
//
// This is driven explicitly by the two windows themselves (via
// window.hwMinimize / window.hwDone) rather than inferred from the overlay
// being hidden — both windows also auto-hide *on their own* the moment
// their task finishes successfully, which looks identical to "user closed
// it while it was running" from the DOM's point of view. Only the code
// that actually knows whether a task is in flight can tell those apart.
function initHiddenWindowsWidget() {
  const widget = document.getElementById('hidden-windows-widget');
  const btn = document.getElementById('hw-widget-btn');
  const panel = document.getElementById('hw-widget-panel');
  const sub = document.getElementById('hw-widget-sub');
  const countBadge = document.getElementById('hw-widget-count');
  const list = document.getElementById('hw-list');
  if (!widget || !btn || !panel || !list) return;

  // id -> { id, title, el }
  const minimized = new Map();

  function render() {
    const items = Array.from(minimized.values());
    widget.classList.toggle('hidden', items.length === 0);
    countBadge.classList.toggle('hidden', items.length === 0);
    countBadge.textContent = String(items.length);
    sub.textContent = items.length ? `${items.length} minimized` : '—';

    list.innerHTML = '';
    if (!items.length) {
      const empty = document.createElement('div');
      empty.className = 'hw-empty';
      empty.textContent = 'No hidden windows';
      list.appendChild(empty);
      return;
    }

    for (const item of items) {
      const row = document.createElement('div');
      row.className = 'hw-item';
      row.innerHTML = `
        <span class="hw-item-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="5" width="17" height="13" rx="2"/><path d="M3.5 9h17"/></svg>
        </span>
        <span class="hw-item-text">
          <span class="hw-item-title"></span>
          <span class="hw-item-sub">Click to reopen</span>
        </span>
        <button type="button" class="hw-item-dismiss" title="Dismiss">✕</button>
      `;
      row.querySelector('.hw-item-title').textContent = item.title;
      row.addEventListener('click', (e) => {
        if (e.target.closest('.hw-item-dismiss')) return;
        if (item.el) item.el.classList.remove('hidden');
        minimized.delete(item.id);
        panel.classList.add('hidden');
        render();
      });
      row.querySelector('.hw-item-dismiss').addEventListener('click', (e) => {
        e.stopPropagation();
        minimized.delete(item.id);
        render();
      });
      list.appendChild(row);
    }
  }

  btn.addEventListener('click', () => {
    panel.classList.toggle('hidden');
  });
  document.addEventListener('click', (e) => {
    if (!widget.contains(e.target)) panel.classList.add('hidden');
  });

  // Called by a window when it's closed while its task is still running.
  window.hwMinimize = function hwMinimize(id, title) {
    const el = document.getElementById(id);
    minimized.set(id, { id, title: title || id, el });
    render();
  };

  // Called by a window's task once it truly finishes (success, failure, or
  // cancellation) — clears the tray entry regardless of whether the window
  // is currently open or hidden, since there's nothing left running to
  // come back to.
  window.hwDone = function hwDone(id) {
    if (!minimized.has(id)) return;
    minimized.delete(id);
    render();
  };

  render();
}

// Exposed by initDownloadWidget() once the floating download widget is
// wired up, so any download/update process elsewhere in the app (mod
// downloads/updates, dependency installs, Java runtime downloads, etc.)
// can show its own card in the downloads menu, each independently
// cancellable, instead of everything fighting over one shared indicator.
// The real instance-install progress (api.onDownloadProgress) gets its own
// permanent card id and is wired up inside initDownloadWidget() itself.
let dlWidgetGeneric = null;

const INSTANCE_INSTALL_CARD_ID = '__instance-install__';

function initDownloadWidget() {
  const widget = document.getElementById('dl-widget');
  const btn = document.getElementById('dl-widget-btn');
  const panel = document.getElementById('dl-widget-panel');
  const backdrop = document.getElementById('dl-widget-backdrop');
  const edgeZone = document.getElementById('dl-edge-zone');
  const edgeHint = document.getElementById('dl-edge-hint');
  const dragGrip = document.querySelector('.dl-panel-drag-grip');
  const closeBtn = document.getElementById('dl-panel-close');
  const emptyState = document.getElementById('dl-cards-empty');
  const fillEl = document.getElementById('dl-widget-fill');
  const title = document.getElementById('dl-widget-title');
  const sub = document.getElementById('dl-widget-sub');
  const countBadge = document.getElementById('dl-widget-count');
  const cardsContainer = document.getElementById('dl-cards');
  const cardTemplate = document.getElementById('dl-card-template');
  if (!widget || !cardTemplate) return;

  // ── Open/close the drawer ────────────────────────────────────────────
  // Always transform-only (translateX) so this stays on the compositor
  // thread — no width/left animation, no backdrop-filter.
  function isPanelOpen() { return panel.classList.contains('dl-panel-open'); }

  function openPanel() {
    if (isPanelOpen()) return;
    panel.classList.remove('hidden');
    backdrop.classList.remove('hidden');
    panel.style.willChange = 'transform';
    // Force layout before adding the "open" class so the browser sees
    // the closed transform first and actually animates the transition,
    // instead of collapsing the display:none→flex and the transform
    // change into a single, un-animated paint.
    void panel.offsetWidth;
    panel.classList.add('dl-panel-open');
    backdrop.classList.add('dl-backdrop-open');
  }
  function closePanel() {
    if (!isPanelOpen() && panel.classList.contains('hidden')) return;
    panel.classList.remove('dl-panel-open');
    backdrop.classList.remove('dl-backdrop-open');
    const done = () => {
      panel.classList.add('hidden');
      backdrop.classList.add('hidden');
      panel.style.willChange = 'auto';
    };
    let settled = false;
    const onEnd = (e) => {
      if (e && e.target !== panel) return;
      if (settled) return;
      settled = true;
      panel.removeEventListener('transitionend', onEnd);
      done();
    };
    panel.addEventListener('transitionend', onEnd);
    // Fallback in case the transitionend never fires (e.g. the drawer was
    // already mid-drag with transitions disabled).
    setTimeout(() => { if (!settled) { settled = true; done(); } }, 260);
  }
  function togglePanel() { if (isPanelOpen()) closePanel(); else openPanel(); }

  btn.addEventListener('click', togglePanel);
  if (closeBtn) closeBtn.addEventListener('click', closePanel);
  if (backdrop) backdrop.addEventListener('click', closePanel);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isPanelOpen()) closePanel();
  });

  // ── Swipe/drag gesture ───────────────────────────────────────────────
  // Pointer Events unify mouse + touch + pen into one code path, which
  // keeps this cheap on WebKitGTK (no separate touchstart/mousedown
  // listeners fighting each other). touch-action:none on the edge zone
  // and the grip (set in CSS) means we never need preventDefault() calls
  // during the drag to stop page scroll.
  //
  // Important: a drag only "engages" (and starts touching the panel's
  // classes/transform) once the pointer has actually moved past a small
  // threshold in the expected direction. Without that, a plain click/tap
  // (near-zero movement) would fall through the same end-of-drag logic,
  // get judged as "not far enough to open" and get force-closed a moment
  // later — racing with, and undoing, the click handler's own open. A
  // tap that never engages a drag is left alone entirely, so the normal
  // 'click' listener on the button is the only thing that handles it.
  const DRAG_ENGAGE_THRESHOLD = 8; // px of movement before a pointerdown becomes a real drag
  const DRAG_OPEN_THRESHOLD = 60; // px of rightward drag from the edge to open
  const DRAG_CLOSE_THRESHOLD = 70; // px of leftward drag on the open drawer to close
  const DRAG_FLICK_VELOCITY = 0.5; // px/ms — a fast flick commits even if the distance threshold wasn't reached
  let drag = null; // { pointerId, startX, lastX, opening, width, engaged, target, lastT, velocity }

  // ── Hold-to-discover hint ────────────────────────────────────────────
  // Holding a pointer down near the left edge — without dragging — surfaces
  // a small "Swipe right to open" bubble at the held Y position. This is
  // the main way people find the gesture now that the tab itself only
  // shows up during an active download (see showWidget/hideWidgetIfEmpty).
  const EDGE_HOLD_DELAY = 380; // ms held still before the hint appears
  let edgeHoldTimer = null;
  function showEdgeHint(clientY) {
    if (!edgeHint) return;
    const y = Math.max(36, Math.min(window.innerHeight - 36, clientY));
    edgeHint.style.top = `${y}px`;
    edgeHint.style.willChange = 'opacity, transform';
    edgeHint.classList.add('dl-edge-hint-visible');
  }
  function hideEdgeHint() {
    clearTimeout(edgeHoldTimer);
    if (!edgeHint) return;
    edgeHint.classList.remove('dl-edge-hint-visible');
    edgeHint.style.willChange = 'auto';
  }
  function armEdgeHint(clientY) {
    clearTimeout(edgeHoldTimer);
    edgeHoldTimer = setTimeout(() => showEdgeHint(clientY), EDGE_HOLD_DELAY);
  }

  function panelWidth() {
    return panel.getBoundingClientRect().width || 380;
  }

  function engageDrag() {
    panel.classList.remove('hidden');
    backdrop.classList.remove('hidden');
    panel.classList.add('dl-panel-dragging');
    // The backdrop's opacity transition (used for click-to-close etc.) was
    // never being disabled here, so every pointermove during a drag was
    // fighting a 0.2s CSS transition trying to catch up to the finger —
    // that's what made the whole gesture feel laggy. Same fix as the panel
    // already got below: kill the transition for the duration of the drag.
    backdrop.classList.add('dl-backdrop-dragging');
    panel.style.willChange = 'transform';
    backdrop.style.willChange = 'opacity';
    drag.engaged = true;
    drag.width = panelWidth();
    try { drag.target.setPointerCapture(drag.pointerId); } catch (_) {}
  }

  function onDragStart(opening) {
    return (e) => {
      if (drag) return;
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      drag = { pointerId: e.pointerId, startX: e.clientX, lastX: e.clientX, opening, engaged: false, width: 380, target: e.currentTarget, lastT: e.timeStamp, velocity: 0 };
    };
  }
  function onDragMove(e) {
    if (!drag || e.pointerId !== drag.pointerId) return;
    const prevX = drag.lastX;
    const prevT = drag.lastT;
    drag.lastX = e.clientX;
    drag.lastT = e.timeStamp;
    // Smoothed px/ms velocity — blended rather than replaced each frame so
    // one jittery event near the end of the gesture can't dominate the
    // flick decision in onDragEnd.
    const dt = drag.lastT - prevT;
    if (dt > 0) {
      const instVelocity = (drag.lastX - prevX) / dt;
      drag.velocity = drag.velocity * 0.7 + instVelocity * 0.3;
    }
    const dx = drag.lastX - drag.startX;
    if (!drag.engaged) {
      const movingRightWay = drag.opening ? dx > DRAG_ENGAGE_THRESHOLD : dx < -DRAG_ENGAGE_THRESHOLD;
      if (!movingRightWay) return;
      engageDrag();
    }
    const base = drag.opening ? -drag.width : 0;
    const raw = base + dx;
    // Rubber-band past the fully-open/fully-closed ends instead of hard
    // clamping, so an over-eager swipe still feels alive at the limit
    // rather than snagging on an invisible wall.
    let translate;
    if (raw > 0) {
      translate = raw < drag.width ? raw * 0.35 : drag.width * 0.35;
    } else if (raw < -drag.width) {
      const over = raw + drag.width;
      translate = -drag.width + Math.max(over * 0.35, -drag.width * 0.12);
    } else {
      translate = raw;
    }
    panel.style.transform = `translate3d(${translate}px,0,0)`;
    const openness = 1 + translate / drag.width;
    backdrop.style.opacity = String(Math.max(0, Math.min(1, openness)));
  }
  function onDragEnd(e) {
    if (!drag || (e && e.pointerId !== drag.pointerId)) return;
    const dx = drag.lastX - drag.startX;
    const velocity = drag.velocity;
    const opening = drag.opening;
    const engaged = drag.engaged;
    drag = null;
    if (!engaged) return; // plain click/tap — the 'click' listener handles it
    panel.classList.remove('dl-panel-dragging');
    backdrop.classList.remove('dl-backdrop-dragging');
    panel.style.transform = '';
    panel.style.willChange = 'auto';
    backdrop.style.opacity = '';
    backdrop.style.willChange = 'auto';
    if (opening) {
      const flickOpen = velocity > DRAG_FLICK_VELOCITY;
      const flickClose = velocity < -DRAG_FLICK_VELOCITY;
      if (flickClose) closePanel();
      else if (dx > DRAG_OPEN_THRESHOLD || flickOpen) openPanel();
      else closePanel();
    } else {
      const flickClose = velocity < -DRAG_FLICK_VELOCITY;
      const flickOpen = velocity > DRAG_FLICK_VELOCITY;
      if (flickOpen) openPanel();
      else if (dx < -DRAG_CLOSE_THRESHOLD || flickClose) closePanel();
      else openPanel();
    }
  }

  if (edgeZone) {
    edgeZone.addEventListener('pointerdown', (e) => {
      if (isPanelOpen()) return;
      onDragStart(true)(e);
      armEdgeHint(e.clientY);
    });
    edgeZone.addEventListener('pointermove', (e) => {
      onDragMove(e);
      // Once it's a real drag, the hint has done its job (or wasn't
      // needed) — don't let it pop up mid-swipe.
      if (drag && drag.engaged) hideEdgeHint();
    });
    edgeZone.addEventListener('pointerup', (e) => {
      const wasEngaged = !!(drag && drag.engaged);
      hideEdgeHint();
      onDragEnd(e);
      // A plain tap in here (never became a rightward drag) — this zone
      // is now wide enough to overlap real content like sidebar buttons,
      // so forward the tap to whatever's actually underneath instead of
      // silently swallowing it.
      if (!wasEngaged) {
        edgeZone.style.pointerEvents = 'none';
        const under = document.elementFromPoint(e.clientX, e.clientY);
        edgeZone.style.pointerEvents = '';
        if (under && under !== edgeZone) under.click();
      }
    });
    edgeZone.addEventListener('pointercancel', (e) => {
      hideEdgeHint();
      onDragEnd(e);
    });
  }
  // The collapsed tab itself can also be dragged straight open, so a
  // click-and-drag from the button works the same as the edge zone.
  btn.addEventListener('pointerdown', (e) => {
    if (isPanelOpen()) return;
    onDragStart(true)(e);
    armEdgeHint(e.clientY);
  });
  btn.addEventListener('pointermove', (e) => {
    onDragMove(e);
    if (drag && drag.engaged) hideEdgeHint();
  });
  btn.addEventListener('pointerup', (e) => { hideEdgeHint(); onDragEnd(e); });
  btn.addEventListener('pointercancel', (e) => { hideEdgeHint(); onDragEnd(e); });

  if (dragGrip) {
    dragGrip.addEventListener('pointerdown', onDragStart(false));
    dragGrip.addEventListener('pointermove', onDragMove);
    dragGrip.addEventListener('pointerup', onDragEnd);
    dragGrip.addEventListener('pointercancel', onDragEnd);
  }
  // Swiping left on the drawer's header (but not the scrollable card
  // list, so it doesn't fight vertical scrolling) also closes it.
  const headingRow = panel.querySelector('.dl-panel-heading-row');
  if (headingRow) {
    headingRow.addEventListener('pointerdown', onDragStart(false));
    headingRow.addEventListener('pointermove', onDragMove);
    headingRow.addEventListener('pointerup', onDragEnd);
    headingRow.addEventListener('pointercancel', onDragEnd);
  }

  function updateEmptyState(hasCards) {
    if (emptyState) emptyState.classList.toggle('hidden', hasCards);
  }

  // ── Files window — one small overlay shared by every card, showing the
  // list of individual files that download/install process has touched
  // (each just a name + status: downloading / completed / failed). ──
  const filesOverlay = document.getElementById('dl-files-overlay');
  const filesList = document.getElementById('dl-files-list');
  const filesViewport = document.getElementById('dl-files-viewport');
  const filesTitle = document.getElementById('dl-files-title');
  const filesSpeed = document.getElementById('dl-files-speed');

  // Virtualized rendering: with a big modpack, `card.files` can easily hit
  // several hundred entries. Keeping a live DOM row per file (even a
  // reused one) meant every scroll/resize/reflow had hundreds of nodes to
  // lay out, which is what caused the lag — the fix isn't fewer updates,
  // it's fewer DOM nodes. Only the rows actually scrolled into view (plus
  // a small buffer) are ever real elements; everything else just lives in
  // `card.files` as plain data until it scrolls into range. Rows are also
  // now a plain flat list (name + underline, no per-row track/bar), which
  // trims one child element off every row versus the old bar-per-row
  // layout — fewer nodes to lay out on top of the already-cheap lookups.
  const ROW_HEIGHT = 44; // must match .dl-file-row's fixed height in CSS
  const ROW_GAP = 0;
  const ROW_STEP = ROW_HEIGHT + ROW_GAP;
  const OVERSCAN = 8; // extra rows rendered above/below the visible band

  function renderFilesList(card) {
    filesTitle.textContent = card.titleText || 'Files';
    filesSpeed.textContent = card.refs && card.refs.speed ? card.refs.speed.textContent : '—';
    if (!card.files.length) {
      filesViewport.style.height = '0px';
      filesViewport.innerHTML = '';
      const empty = document.createElement('div');
      empty.className = 'dl-files-empty';
      empty.textContent = 'No file breakdown available for this download.';
      filesViewport.appendChild(empty);
      return;
    }
    const wasNearTop = filesList.scrollTop <= 4;

    // Newest/most-recently-touched file first (top), oldest at the
    // bottom — so whatever the download is doing right now is always
    // the first thing visible without having to scroll. We used to build
    // this by copying+reversing `card.files` on every render call, which
    // is an O(n) allocation for a list that can be thousands of entries
    // long. Since we only ever need a small windowed slice (firstIndex..
    // lastIndex) for the visible rows, we instead index straight into
    // `card.files` from the end — no copy, no reversal.
    const total = card.files.length;
    const ordered = (i) => card.files[total - 1 - i];

    filesViewport.style.height = `${total * ROW_STEP - ROW_GAP}px`;

    if (!card.fileRowEls) card.fileRowEls = new Map(); // name -> {row, status, bar, index}

    const viewportHeight = filesList.clientHeight || 360;
    const scrollTop = filesList.scrollTop;
    const firstIndex = Math.max(0, Math.floor(scrollTop / ROW_STEP) - OVERSCAN);
    const lastIndex = Math.min(
      total - 1,
      Math.ceil((scrollTop + viewportHeight) / ROW_STEP) + OVERSCAN
    );

    const wantedNames = new Set();
    for (let i = firstIndex; i <= lastIndex; i++) {
      const f = ordered(i);
      wantedNames.add(f.name);
      let refs = card.fileRowEls.get(f.name);
      if (!refs) {
        // Flat row: just the filename, no per-row progress track/bar —
        // one child element instead of the old name+status+track+bar
        // structure, which keeps each row cheap to lay out even when
        // hundreds are being created/recycled per second during a big
        // install.
        const row = document.createElement('div');
        const nameRow = document.createElement('div');
        nameRow.className = 'dl-file-name-row';
        const name = document.createElement('span');
        name.className = 'dl-file-name';
        name.textContent = f.name;
        const pct = document.createElement('span');
        pct.className = 'dl-file-pct';
        nameRow.appendChild(name);
        nameRow.appendChild(pct);
        const track = document.createElement('div');
        track.className = 'dl-file-track';
        const bar = document.createElement('div');
        bar.className = 'dl-file-bar';
        track.appendChild(bar);
        row.appendChild(nameRow);
        row.appendChild(track);
        filesViewport.appendChild(row);
        refs = { row, name, track, bar, pct };
        card.fileRowEls.set(f.name, refs);
      }
      refs.row.className = 'dl-file-row dl-file-' + f.status;
      refs.row.style.top = `${i * ROW_STEP}px`;
      // Real byte-level percent when the server reported one; otherwise
      // fall back to an indeterminate sweep so the row still reads as
      // "in progress" rather than looking stalled at 0%. Completed/failed
      // rows snap to a settled full/empty bar instead of animating.
      const known = typeof f.percent === 'number';
      refs.row.classList.toggle('dl-file-indeterminate', f.status === 'downloading' && !known);
      // transform: scaleX(), not width — see .dl-file-bar in main.css for
      // why (layout-property updates on dozens of concurrently-visible
      // rows were the main source of the downloads-menu lag on WebKitGTK).
      if (f.status === 'completed') {
        refs.bar.style.transform = 'scaleX(1)';
        refs.pct.textContent = '100%';
      } else if (f.status === 'failed') {
        refs.bar.style.transform = 'scaleX(0)';
        refs.pct.textContent = 'Failed';
      } else if (f.status === 'pending') {
        refs.bar.style.transform = 'scaleX(0)';
        refs.pct.textContent = '—';
      } else if (known) {
        const p = Math.max(0, Math.min(100, f.percent));
        refs.bar.style.transform = `scaleX(${p / 100})`;
        refs.pct.textContent = `${Math.round(p)}%`;
      } else {
        refs.pct.textContent = '—';
      }
    }

    // Anything with a live row that scrolled out of the rendered band (or
    // dropped out of `card.files` entirely) gets its DOM node removed —
    // this is what keeps the node count bounded no matter how many total
    // files the download touches.
    for (const [name, refs] of card.fileRowEls) {
      if (!wantedNames.has(name)) {
        refs.row.remove();
        card.fileRowEls.delete(name);
      }
    }

    // Only follow new activity to the top automatically if the person was
    // already up there — if they've scrolled down to look at earlier
    // files, leave them where they are instead of yanking the list back.
    if (wasNearTop) filesList.scrollTop = 0;
  }

  // Scrolling changes which rows should be rendered even when nothing
  // about the data changed, so it needs its own (cheap) re-render pass —
  // rAF-throttled so a fast scroll wheel doesn't queue up redundant work.
  let filesScrollRaf = null;
  filesList.addEventListener('scroll', () => {
    if (filesScrollRaf) return;
    filesScrollRaf = requestAnimationFrame(() => {
      filesScrollRaf = null;
      if (filesWindowCardId == null) return;
      const card = cards.get(filesWindowCardId);
      if (card) renderFilesList(card);
    });
  }, { passive: true });

  let filesWindowCardId = null;
  function openFilesWindow(card) {
    filesWindowCardId = card.id;
    renderFilesList(card);
    filesOverlay.classList.remove('hidden');
  }
  let refreshFilesRaf = null;
  function refreshFilesWindowIfOpen(id) {
    if (filesWindowCardId !== id || filesOverlay.classList.contains('hidden')) return;
    if (refreshFilesRaf) return;
    refreshFilesRaf = requestAnimationFrame(() => {
      refreshFilesRaf = null;
      if (filesWindowCardId !== id || filesOverlay.classList.contains('hidden')) return;
      const card = cards.get(id);
      if (card) renderFilesList(card);
    });
  }
  const closeFilesBtn = document.getElementById('btn-close-dl-files');
  if (closeFilesBtn) closeFilesBtn.addEventListener('click', () => filesOverlay.classList.add('hidden'));

  // card.fileByName: name -> entry, kept in sync with card.files so
  // fileStart/fileProgress/fileDone never need to scan (or, worse,
  // copy+reverse) the full files array to find one entry. That scan used
  // to run on every single progress tick — with a big modpack (thousands
  // of files, several downloading in parallel with frequent byte-level
  // updates) that's thousands of O(n) array allocations per second, which
  // is what caused the lag. Lookups are now O(1) regardless of list size.
  function getFileMap(card) {
    if (!card.fileByName) card.fileByName = new Map();
    return card.fileByName;
  }
  function fileStart(id, name) {
    const card = cards.get(id);
    if (!card || !name) return;
    const map = getFileMap(card);
    const entry = map.get(name);
    if (entry && entry.status === 'pending') {
      entry.status = 'downloading';
    } else if (!entry || entry.status !== 'downloading') {
      const fresh = { name, status: 'downloading', percent: null };
      card.files.push(fresh);
      map.set(name, fresh);
    }
    refreshFilesWindowIfOpen(id);
  }
  // Updates a currently-downloading file's real byte-level percent (0-100,
  // or null when the server didn't report a content length for it). Only
  // touches the in-memory entry — the visible bar is repainted the next
  // time renderFilesList runs, same as any other file-state change.
  function fileProgress(id, name, percent) {
    const card = cards.get(id);
    if (!card || !name || typeof percent !== 'number') return;
    const entry = getFileMap(card).get(name);
    if (entry && entry.status === 'downloading') entry.percent = percent;
    refreshFilesWindowIfOpen(id);
  }
  function fileDone(id, name, success) {
    const card = cards.get(id);
    if (!card || !name) return;
    const map = getFileMap(card);
    const entry = map.get(name);
    if (entry && entry.status === 'downloading') {
      entry.status = success ? 'completed' : 'failed';
    } else {
      const fresh = { name, status: success ? 'completed' : 'failed' };
      card.files.push(fresh);
      map.set(name, fresh);
    }
    refreshFilesWindowIfOpen(id);
  }
  // Pre-populates the Files window with every file this download process
  // will eventually touch, marked "pending" (i.e. not downloaded yet) —
  // fileStart()/fileDone() above then move each one along as it's
  // actually reached, so the still-to-come files stay visible instead of
  // only appearing once their own download begins.
  function seedFiles(id, names) {
    const card = cards.get(id);
    if (!card) return;
    const map = getFileMap(card);
    (names || []).forEach(name => {
      if (!name || map.has(name)) return;
      const fresh = { name, status: 'pending' };
      card.files.push(fresh);
      map.set(name, fresh);
    });
    refreshFilesWindowIfOpen(id);
  }
  // Reconciles a card's Files-window state against the real, current set
  // of actively-downloading files reported by the backend (several files
  // in flight at once, each with its own live byte-level percent when
  // known) — shared by the instance-install listener and the modpack
  // extractor listener below so both read the same "really downloading,
  // several files at once" way instead of one being a fake/simulated
  // simplification of the other.
  function reconcileActiveFiles(id, activeFiles) {
    const card = cards.get(id);
    if (!card) return;
    const list = activeFiles || [];
    const activeNow = new Set(list.map(f => f.name));
    const activeBefore = card.activeFileNames || new Set();
    list.forEach(({ name, percent }) => {
      if (!activeBefore.has(name)) fileStart(id, name);
      fileProgress(id, name, percent);
    });
    activeBefore.forEach((name) => {
      if (!activeNow.has(name)) fileDone(id, name, true);
    });
    card.activeFileNames = activeNow;
    refreshFilesWindowIfOpen(id);
  }

  // id -> card state. Each card is one download/install process; several
  // can exist at once (an instance install plus one or more mod updates,
  // for instance), each rendered as its own entry in dl-cards.
  const cards = new Map();

  // The edge tab now only exists on screen while something is actually
  // downloading/installing — the drawer itself is still always reachable
  // via the edge-zone swipe (and its hold-hint), so nothing is lost by
  // hiding the idle tab; it's just one less permanently-visible thing.
  function showWidget() {
    clearTimeout(dlHideTimer);
    widget.classList.remove('dl-leaving');
    widget.classList.remove('hidden');
    widget.classList.add('dl-active');
  }

  function hideWidgetIfEmpty() {
    if (cards.size > 0) return;
    clearTimeout(dlHideTimer);
    dlHideTimer = setTimeout(() => {
      widget.classList.remove('dl-active', 'dl-paused', 'dl-generic');
      widget.classList.add('hidden');
      countBadge.classList.add('hidden');
      title.textContent = 'Downloads';
      sub.textContent = 'No active downloads';
      fillEl.style.setProperty('--dl-fill', '0');
      updateEmptyState(false);
    }, 250);
  }

  // Recomputes the collapsed edge tab (fill bar/count) and the drawer's
  // status line from whichever cards are currently active.
  function refreshSummary() {
    const list = Array.from(cards.values());
    updateEmptyState(list.length > 0);
    if (list.length === 0) return;
    countBadge.textContent = String(list.length);
    countBadge.classList.toggle('hidden', list.length <= 1);

    const primary = list.find(c => c.status === 'error') || list.find(c => c.status === 'paused') || list[0];
    title.textContent = primary.titleText;
    sub.textContent = list.length > 1 ? `${primary.subText} (+${list.length - 1} more)` : primary.subText;
    widget.classList.toggle('dl-paused', primary.status === 'paused');

    const determinate = list.filter(c => c.percent !== null && c.status === 'downloading');
    if (determinate.length > 0) {
      const avg = determinate.reduce((s, c) => s + c.percent, 0) / determinate.length;
      fillEl.style.setProperty('--dl-fill', String(avg / 100));
      widget.classList.remove('dl-generic');
    } else {
      widget.classList.add('dl-generic');
    }
  }

  function createCard(id) {
    const frag = cardTemplate.content.cloneNode(true);
    const el = frag.querySelector('.dl-card');
    cardsContainer.appendChild(el);
    const card = {
      id,
      el,
      status: 'downloading', // downloading | paused | completed | cancelled | error
      percent: null,
      titleText: '',
      subText: '',
      cancelled: false,
      onPause: null,
      onCancel: null,
      refs: {
        icon: el.querySelector('.dl-card-icon'),
        title: el.querySelector('.dl-card-title'),
        stage: el.querySelector('.dl-card-stage'),
        pill: el.querySelector('.dl-card-status-pill'),
        bar: el.querySelector('.dl-card-bar'),
        file: el.querySelector('.dl-card-file'),
        percent: el.querySelector('.dl-card-stat-percent'),
        speed: el.querySelector('.dl-card-stat-speed'),
        eta: el.querySelector('.dl-card-stat-eta'),
        downloaded: el.querySelector('.dl-card-stat-downloaded'),
        pauseBtn: el.querySelector('.dl-card-pause'),
        cancelBtn: el.querySelector('.dl-card-cancel'),
        filesBtn: el.querySelector('.dl-card-files'),
      },
      files: [], // [{ name, status: 'downloading'|'completed'|'failed' }] — shown in the Files window
      activeFileNames: new Set(), // names currently in p.active_files, used to diff against the next event
    };
    card.refs.pauseBtn.addEventListener('click', async () => {
      if (!card.onPause) return;
      try {
        await card.onPause();
      } catch (e) {
        showToast('Failed to update download: ' + e, 'error');
      }
    });
    card.refs.cancelBtn.addEventListener('click', () => {
      if (!card.onCancel || card.cancelling) return;
      // Instant kill: tear the card down right now instead of waiting for
      // the backend to confirm cancellation — the person clicked Cancel to
      // stop it *now*, not to watch a "Cancelling…" state resolve. The
      // actual stop signal (and best-effort cleanup of whatever was
      // already written to disk) still happens, just after the UI has
      // already moved on.
      card.cancelling = true;
      card.cancelled = true;
      removeCard(id, 0);
      Promise.resolve(card.onCancel()).catch((e) => {
        showToast('Failed to cancel download: ' + e, 'error');
      });
    });
    card.refs.filesBtn.addEventListener('click', () => {
      closePanel();
      openFilesWindow(card);
    });
    setDlCardIcon(card.refs.icon, {});
    cards.set(id, card);
    showWidget();
    return card;
  }

  function removeCard(id, delay) {
    const card = cards.get(id);
    if (!card) return;
    setTimeout(() => {
      if (!cards.has(id)) return;
      card.el.classList.add('dl-card-leaving');
      setTimeout(() => {
        card.el.remove();
        cards.delete(id);
        if (cards.size === 0) hideWidgetIfEmpty();
        else refreshSummary();
      }, 190);
    }, delay);
  }

  // ── Real instance-install progress (byte-level, pausable, cancellable) ──
  // Called immediately by the frontend the moment an install/reinstall is
  // kicked off, so the widget appears right away instead of waiting for
  // the backend's first byte-level progress event — version resolution,
  // metadata fetches, and the "reuse cached files" copy step can all take
  // a few seconds before any of that happens, and without this the button
  // looked like it was doing nothing during that whole stretch.
  function beginInstanceInstallPlaceholder(id, label, loader) {
    let card = cards.get(id);
    if (!card) {
      card = createCard(id);
      card.onPause = async () => {
        if (card.status === 'paused') await api.resumeDownload();
        else await api.pauseDownload();
      };
      card.onCancel = async () => { await api.cancelDownload(); };
    }
    setDlCardIcon(card.refs.icon, { icon: 'loader', loaderIconUrl: loaderIcon(loader) });
    card.status = 'downloading';
    card.percent = null;
    card.files = [];
    card.fileByName = new Map();
    card.activeFileNames = new Set();
    card.el.classList.remove('dl-card-paused', 'dl-card-error', 'dl-card-cancelled');
    card.el.classList.remove('dl-card-no-pause', 'dl-card-no-stats');
    card.el.classList.add('dl-card-indeterminate');
    card.titleText = 'Preparing…';
    card.subText = 'Starting install…';
    card.refs.title.textContent = label ? `Minecraft ${label}` : 'Minecraft';
    card.refs.stage.textContent = 'Preparing…';
    card.refs.file.textContent = '—';
    card.refs.speed.textContent = '—';
    card.refs.eta.textContent = '—';
    card.refs.downloaded.textContent = '—';
    card.refs.pill.textContent = 'Preparing';
    card.refs.pauseBtn.innerHTML = '⏸ Pause';
    refreshSummary();
  }

  // Real byte-level progress for every generic download (mod updates,
  // dependency installs, Fix Mods, preset apply, import mods, plain
  // Discover downloads, and Java runtime downloads) — one shared event
  // from the backend, keyed by the same download id each card already
  // uses, so a single listener here drives percent/speed/downloaded for
  // all of them instead of each feature needing its own progress plumbing.
  api.onGenericDownloadProgress((event) => {
    const p = event.payload || {};
    const card = cards.get(p.id);
    if (!card) return;
    const r = card.refs;
    if (p.total_bytes) {
      const pct = Math.max(0, Math.min(100, (p.downloaded_bytes / p.total_bytes) * 100));
      card.el.classList.remove('dl-card-indeterminate');
      card.percent = pct;
      r.bar.style.transform = `scaleX(${pct / 100})`;
      r.percent.textContent = `${Math.round(pct)}%`;
      const remaining = p.total_bytes - p.downloaded_bytes;
      r.eta.textContent = p.speed_bps > 0 ? fmtEta(remaining / p.speed_bps) : '—';
    }
    r.speed.textContent = fmtSpeed(p.speed_bps);
    r.downloaded.textContent = p.total_bytes
      ? `${fmtBytes(p.downloaded_bytes)} / ${fmtBytes(p.total_bytes)}`
      : fmtBytes(p.downloaded_bytes);
    if (p.file_name) r.file.textContent = p.file_name;
  });

  api.onDownloadProgress((event) => {
    const p = event.payload;
    let card = cards.get(INSTANCE_INSTALL_CARD_ID);
    if (!card) {
      card = createCard(INSTANCE_INSTALL_CARD_ID);
      card.onPause = async () => {
        if (card.status === 'paused') await api.resumeDownload();
        else await api.pauseDownload();
      };
      card.onCancel = async () => { await api.cancelDownload(); };
    }
    const r = card.refs;
    const paused = p.status === 'paused';
    card.status = paused ? 'paused'
      : p.status === 'completed' ? 'completed'
      : p.status === 'cancelled' ? 'cancelled'
      : p.status === 'error' ? 'error'
      : 'downloading';
    card.el.classList.toggle('dl-card-paused', paused);
    card.el.classList.toggle('dl-card-error', p.status === 'error');
    card.el.classList.toggle('dl-card-cancelled', p.status === 'cancelled');

    const pct = Math.max(0, Math.min(100, p.percent || 0));
    card.percent = pct;
    r.bar.style.transform = `scaleX(${pct / 100})`; // not width — see .dl-card-bar in main.css
    r.percent.textContent = Math.round(pct) + '%';

    // Real concurrent file list from the backend (several files download
    // at once), each with its own real byte-level percent when known —
    // fall back to the single current_file for older payload shapes so
    // this doesn't break if a stale build sends one.
    const activeFiles = (p.active_files && p.active_files.length)
      ? p.active_files
      : (p.current_file ? [{ name: p.current_file, percent: null }] : []);
    const activeList = activeFiles.map(f => f.name);

    card.titleText = paused ? 'Paused' : 'Installing…';
    card.subText = paused
      ? (p.current_file || p.stage || p.label)
      : activeList.length > 1
        ? `Downloading ${activeList.length} files…`
        : (activeList[0] || p.stage || p.label);
    r.title.textContent = `Minecraft ${p.label}`;
    r.stage.textContent = p.stage || '';
    r.file.textContent = activeList.length === 0
      ? '—'
      : activeList.length === 1
        ? activeList[0]
        : `${activeList[0]} +${activeList.length - 1} more`;
    r.speed.textContent = paused ? '—' : fmtSpeed(p.speed_bps);
    r.eta.textContent = paused ? '—' : fmtEta(p.eta_seconds);
    r.downloaded.textContent = fmtBytes(p.downloaded_bytes);
    r.pill.textContent = paused ? 'Paused' : 'Downloading';
    r.pauseBtn.innerHTML = paused ? '▶ Resume' : '⏸ Pause';

    // Reconcile the Files window against the real set of currently-active
    // files: anything newly in active_files starts downloading, anything
    // that dropped out (finished, whether via TaskFinished or TaskSkipped
    // upstream) is marked completed, and anything still in flight has its
    // real byte-level percent updated. Shared with the modpack extractor
    // listener below (see reconcileActiveFiles) so both read this exactly
    // the same way.
    if (p.status === 'downloading') {
      reconcileActiveFiles(INSTANCE_INSTALL_CARD_ID, activeFiles);
    } else {
      refreshFilesWindowIfOpen(INSTANCE_INSTALL_CARD_ID);
    }

    if (p.status === 'completed') {
      r.pill.textContent = 'Completed';
      card.titleText = 'Installed!';
      card.subText = p.label;
      card.files.forEach(f => { if (f.status === 'downloading') f.status = 'completed'; });
      removeCard(INSTANCE_INSTALL_CARD_ID, 1800);
    } else if (p.status === 'cancelled') {
      r.pill.textContent = 'Cancelled';
      card.titleText = 'Cancelled';
      removeCard(INSTANCE_INSTALL_CARD_ID, 1500);
    } else if (p.status === 'error') {
      r.pill.textContent = 'Error';
      card.titleText = 'Install failed';
      card.subText = p.message || 'Unknown error';
      card.files.forEach(f => { if (f.status === 'downloading') f.status = 'failed'; });
      removeCard(INSTANCE_INSTALL_CARD_ID, 3000);
    }
    refreshSummary();
  });

  // ── Generic downloads (mod downloads/updates, dependency installs, Java
  // runtime downloads, discover-tab downloads, etc.) ─────────────────────
  // Each gets its own id (chosen by the caller) and its own card. Unless
  // told otherwise, cancelling calls api.cancelGenericDownload(id), which
  // matches the id the backend expects for discover_download() calls and
  // Java runtime downloads made with that same id.
  function beginGenericDownload(id, titleText, subText, opts) {
    opts = opts || {};
    let card = cards.get(id);
    if (!card) card = createCard(id);
    setDlCardIcon(card.refs.icon, { icon: opts.icon, iconUrl: opts.iconUrl });
    card.files = [];
    card.fileByName = new Map();
    card.activeFileNames = new Set();
    card.status = 'downloading';
    card.cancelled = false;
    card.percent = opts.determinate ? 0 : null;
    card.el.classList.remove('dl-card-paused', 'dl-card-error', 'dl-card-cancelled');
    card.el.classList.add('dl-card-no-pause');
    // Downloads that report real byte-level stats (speed/eta/downloaded,
    // an active-file name) keep that row visible instead of hiding it —
    // used by the modpack extractor, which now reports these for real,
    // the same as an instance install.
    // Any download that can report byte-level progress shows the stats
    // row by default now (percent/speed/ETA/downloaded) — pass
    // `withStats: false` explicitly to opt a card out (e.g. a purely
    // count-based step with nothing to stream).
    card.el.classList.toggle('dl-card-no-stats', opts.withStats === false);
    card.el.classList.toggle('dl-card-no-cancel', !!opts.noCancel);
    card.el.classList.toggle('dl-card-indeterminate', !opts.determinate);
    if (opts.determinate) card.refs.bar.style.transform = 'scaleX(0)';
    card.titleText = titleText;
    card.subText = subText || '';
    card.refs.title.textContent = titleText;
    card.refs.stage.textContent = subText || '';
    card.refs.file.textContent = '—';
    card.refs.speed.textContent = '—';
    card.refs.eta.textContent = '—';
    card.refs.downloaded.textContent = '—';
    card.refs.pill.textContent = 'Downloading';
    card.onPause = null;
    card.onCancel = opts.noCancel ? null : (opts.onCancel || (async () => {
      card.cancelled = true;
      await api.cancelGenericDownload(id);
    }));
    refreshSummary();
  }

  function updateGenericDownload(id, titleText, subText, percent, stats) {
    const card = cards.get(id);
    if (!card) return;
    if (titleText) { card.titleText = titleText; card.refs.title.textContent = titleText; }
    if (subText !== undefined) { card.subText = subText; card.refs.stage.textContent = subText; }
    if (percent !== undefined && percent !== null) {
      card.el.classList.remove('dl-card-indeterminate');
      const pct = Math.max(0, Math.min(100, percent));
      card.percent = pct;
      card.refs.bar.style.transform = `scaleX(${pct / 100})`;
    }
    if (stats) {
      if (stats.file !== undefined) card.refs.file.textContent = stats.file || '—';
      if (stats.speed !== undefined) card.refs.speed.textContent = stats.speed;
      if (stats.eta !== undefined) card.refs.eta.textContent = stats.eta;
      if (stats.downloaded !== undefined) card.refs.downloaded.textContent = stats.downloaded;
    }
    refreshSummary();
  }

  function endGenericDownload(id, success, finalText) {
    const card = cards.get(id);
    if (!card) return;
    card.status = success ? 'completed' : (card.cancelled ? 'cancelled' : 'error');
    card.el.classList.toggle('dl-card-error', card.status === 'error');
    card.el.classList.toggle('dl-card-cancelled', card.status === 'cancelled');
    card.refs.pill.textContent = success ? 'Completed' : (card.cancelled ? 'Cancelled' : 'Error');
    card.titleText = finalText || (success ? 'Done' : (card.cancelled ? 'Cancelled' : 'Failed'));
    card.refs.title.textContent = card.titleText;
    removeCard(id, success ? 1500 : 2200);
  }

  function isGenericDownloadCancelled(id) {
    const card = cards.get(id);
    return !!(card && card.cancelled);
  }

  // Marks the instance-install card as failed and removes it after a
  // delay — used when installVersion() rejects *without* the backend
  // ever having emitted a "download-progress" error event first (e.g. it
  // throws during early version/metadata resolution, before any
  // byte-level progress exists). Without this the card built by
  // beginInstanceInstallPlaceholder() was left stuck on "Preparing…"
  // forever, which is why failed installs could look like nothing was
  // happening at all.
  function failInstanceInstall(id, message) {
    const card = cards.get(id);
    if (!card) return;
    card.status = 'error';
    card.el.classList.remove('dl-card-indeterminate', 'dl-card-paused', 'dl-card-cancelled');
    card.el.classList.add('dl-card-error');
    card.refs.pill.textContent = 'Error';
    card.titleText = 'Install failed';
    card.subText = message || 'Unknown error';
    card.refs.title.textContent = card.titleText;
    card.refs.stage.textContent = card.subText;
    refreshSummary();
    removeCard(id, 3000);
  }

  function setGenericDownloadIcon(id, iconUrl, fallbackIcon) {
    const card = cards.get(id);
    if (!card) return;
    setDlCardIcon(card.refs.icon, { iconUrl, icon: fallbackIcon });
  }

  dlWidgetGeneric = {
    begin: beginGenericDownload,
    update: updateGenericDownload,
    end: endGenericDownload,
    isCancelled: isGenericDownloadCancelled,
    beginInstanceInstall: beginInstanceInstallPlaceholder,
    failInstanceInstall,
    setIcon: setGenericDownloadIcon,
    fileStart,
    fileDone,
    seedFiles,
    reconcileActiveFiles,
  };
}

// ══════════════════════════════════════════════════════════════════
// INSTANCES
// ══════════════════════════════════════════════════════════════════
// Instances are tracked and persisted by the Rust backend (in
// <game_directory>/versions/instances.json), NOT inside settings — this
// keeps them from getting lost across restarts and keeps them tied to
// whichever .minecraft folder is actually configured.
let instancesCache = [];
let hiddenInstancesCache = [];

async function refreshInstances() {
  // Two sources, merged:
  //  - get_installed_instances: versions this launcher itself tracks
  //    (has a custom display name, loader_version, install timestamp, etc.)
  //  - scan_minecraft_versions: a live scan of <game_dir>/versions/ on disk,
  //    which also picks up versions that were installed by another launcher,
  //    dropped in manually, or otherwise aren't in our own tracking file.
  // Everything found by either is shown and launchable.
  let tracked = [];
  let scanned = [];
  try {
    tracked = await api.getInstalledInstances();
  } catch (e) {
    console.error('Failed to load tracked instances:', e);
  }
  try {
    scanned = await api.scanMinecraftVersions();
  } catch (e) {
    console.error('Failed to scan .minecraft/versions:', e);
  }
  try {
    hiddenInstancesCache = await api.getHiddenInstances();
  } catch (e) {
    console.error('Failed to load hidden instances:', e);
  }

  const byVersionId = new Map();
  tracked.forEach(inst => byVersionId.set(inst.version_id, inst));
  scanned.forEach(v => {
    if (byVersionId.has(v.id)) return; // already have a tracked entry with a real name
    byVersionId.set(v.id, {
      name: v.id,
      version_id: v.id,
      minecraft_version: v.minecraft_version || v.id,
      loader: v.loader || 'vanilla',
      loader_version: '',
      directory: (settings && settings.game_directory) || '',
      installed_at: '',
      missing_jar: !v.has_jar,
    });
  });

  instancesCache = Array.from(byVersionId.values());
  if (selectedInstanceId && !instancesCache.some(i => i.version_id === selectedInstanceId)) {
    selectedInstanceId = null;
  }
  return instancesCache;
}

function getInstances() {
  return instancesCache;
}

// Instances the user chose to "Hide" (currently only reachable from the
// vanilla-instance delete-dependency warning) stay fully installed and
// available everywhere else in the app — they're just left out of the main
// Instances list so it isn't cluttered with versions the user only kept
// around to satisfy a modded instance's dependency.
// Auto-hidden vanilla instances (see getVisibleInstances) can be shown
// again individually via Settings → Hidden Instances, without needing a
// global toggle. That per-instance choice is remembered here, in the
// launcher's local app storage, so it survives restarts.
const VANILLA_OVERRIDES_KEY = 'zerolauncher_shown_vanilla_overrides';
function getVanillaOverrides() {
  try {
    const raw = localStorage.getItem(VANILLA_OVERRIDES_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch (e) {
    return [];
  }
}
function addVanillaOverride(versionId) {
  const overrides = getVanillaOverrides();
  if (!overrides.includes(versionId)) {
    overrides.push(versionId);
    try { localStorage.setItem(VANILLA_OVERRIDES_KEY, JSON.stringify(overrides)); } catch (e) { /* ignore */ }
  }
}

function getVisibleInstances() {
  const vanillaOverrides = getVanillaOverrides();
  const visible = instancesCache.filter(i => {
    if (hiddenInstancesCache.includes(i.version_id)) return false;
    const isVanilla = (i.loader || 'vanilla').toLowerCase() === 'vanilla';
    // Vanilla versions the user never explicitly installed through this
    // launcher (i.e. only picked up by scanning .minecraft/versions) stay
    // hidden by default so the Instances list isn't cluttered with base
    // game files that only exist to satisfy a modded instance's dependency.
    // A vanilla instance actually installed via + New Instance always shows,
    // and so does one the user chose to unhide from Settings.
    const neverInstalledHere = !i.installed_at;
    if (isVanilla && neverInstalledHere && !vanillaOverrides.includes(i.version_id)) return false;
    return true;
  });

  // Apply the user's manual drag-and-drop ordering. Instances not yet in
  // the saved order (new ones) keep their natural relative order and are
  // appended after the ones the user has explicitly arranged.
  const order = getInstanceOrder();
  if (order.length) {
    const orderIndex = new Map(order.map((id, idx) => [id, idx]));
    visible.sort((a, b) => {
      const ai = orderIndex.has(a.version_id) ? orderIndex.get(a.version_id) : Infinity;
      const bi = orderIndex.has(b.version_id) ? orderIndex.get(b.version_id) : Infinity;
      if (ai === bi) return 0;
      return ai - bi;
    });
  }

  // Pinned/favorited instance always floats to the top of the list,
  // overriding whatever manual order the user has dragged it into.
  const favId = getFavoriteInstance();
  if (favId) {
    const favIdx = visible.findIndex(i => i.version_id === favId);
    if (favIdx > 0) {
      const [fav] = visible.splice(favIdx, 1);
      visible.unshift(fav);
    }
  }
  return visible;
}

// The single "favorited" instance, pinned to the top of the Instances list
// and highlighted with the accent glow. Only one instance can be favorited
// at a time; picking a new one replaces the old one. Persisted locally so
// it survives restarts.
const FAVORITE_INSTANCE_KEY = 'zerolauncher_favorite_instance';
function getFavoriteInstance() {
  try {
    return localStorage.getItem(FAVORITE_INSTANCE_KEY) || null;
  } catch (e) {
    return null;
  }
}
function setFavoriteInstance(versionId) {
  try {
    if (versionId) {
      localStorage.setItem(FAVORITE_INSTANCE_KEY, versionId);
    } else {
      localStorage.removeItem(FAVORITE_INSTANCE_KEY);
    }
  } catch (e) { /* ignore */ }
}

// Manual drag-and-drop order for the Instances list. Stored as an array of
// version_ids, most-preferred-position first. The favorited instance always
// gets pulled to the top on render regardless of what's saved here.
const INSTANCE_ORDER_KEY = 'zerolauncher_instance_order';
function getInstanceOrder() {
  try {
    const raw = localStorage.getItem(INSTANCE_ORDER_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch (e) {
    return [];
  }
}
function setInstanceOrder(order) {
  try { localStorage.setItem(INSTANCE_ORDER_KEY, JSON.stringify(order)); } catch (e) { /* ignore */ }
}
// Persists a new order based on the version_ids currently rendered in the
// DOM (post-drag), so future renders keep respecting it.
function saveInstanceOrderFromDOM() {
  const list = document.getElementById('instance-list');
  if (!list) return;
  const ids = Array.from(list.querySelectorAll('.instance-card'))
    .map(el => el.dataset.versionId)
    .filter(Boolean);
  setInstanceOrder(ids);
}

// ═══════════════════════════════════════════════════════════════════
// 3D Skin Viewer (Powered by skin3d)
// ═══════════════════════════════════════════════════════════════════

let currentSkinAnimName = 'walk';
let currentSkinSpeed = 0.5;
let currentSkinEquipType = 'cape';
let currentSkinCapeKey = 'migrator';
let currentSkinSource = SHOW_SKIN_URL;
let currentSkinFacing = 'left';
let currentSkinAnonSkin = false;
let currentSkinAnonTag = false;
let currentSkinAnonPic = false;
let currentSkinEquippedPath = null;
let currentSkinEquippedId = null;
let currentSkinEquippedName = null;
let currentSkinModelType = 'auto-detect';

function getFacingYaw(facing) {
  switch (facing) {
    case 'right':
      return 0.38;
    case 'camera':
      return 0.0;
    case 'left':
    default:
      return -0.38;
  }
}

const PRESET_SKINS = {
  knight: SHOW_SKIN_URL,
  steve: 'https://textures.minecraft.net/texture/414522e74cc844f44fb11f5997d826a59371f42707235cc1e62f675e38d1e',
  alex: 'https://textures.minecraft.net/texture/6e432c7c72db19463b2cf725ed14ec5e8b610c3ea9c77ad45e2c7104b2a8d',
  ninja: 'https://textures.minecraft.net/texture/2e086f67ca43b171694f479d2bbf8933b4fb3d30b91d2c67fe53c072c448bb',
};

const PRESET_CAPES = {
  none: null,
  // NOTE: these were previously corrupted (each hash was missing several
  // characters, so every single one 404'd against Mojang's texture CDN —
  // this was the real reason Cape/Elytra never showed anything at all,
  // for every account, not just offline ones). Verified 64-char hashes below.
  migrator: 'https://textures.minecraft.net/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933',
  minecon2011: 'https://textures.minecraft.net/texture/953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6',
  minecon2012: 'https://textures.minecraft.net/texture/a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6',
  minecon2013: 'https://textures.minecraft.net/texture/153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da',
  minecon2015: 'https://textures.minecraft.net/texture/b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635',
  minecon2016: 'https://textures.minecraft.net/texture/e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980',
  vanilla: 'https://textures.minecraft.net/texture/f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4',
  cherry: 'https://textures.minecraft.net/texture/afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb',
  '15th': 'https://textures.minecraft.net/texture/cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625',
};

function createSkinAnimation(name) {
  let anim = null;
  switch (name) {
    case 'idle':
      anim = new IdleAnimation();
      break;
    case 'walk':
      anim = new WalkingAnimation();
      break;
    case 'run':
      anim = new RunningAnimation();
      break;
    case 'wave':
      anim = new WaveAnimation();
      break;
    case 'crouch':
      anim = new CrouchAnimation();
      break;
    case 'fly':
      anim = new FlyingAnimation();
      break;
    case 'hit':
      anim = new HitAnimation();
      break;
    case 'none':
    default:
      anim = null;
      break;
  }
  if (anim) {
    anim.speed = currentSkinSpeed;
  }
  return anim;
}

function updateSkinModelBadge() {
  const badge = document.getElementById('skin-model-badge');
  if (!badge || !skinViewerInstance) return;
  const type = skinViewerInstance.playerObject?.skin?.modelType;
  badge.textContent = type === 'slim' ? 'Slim (3px Arms)' : 'Classic (4px Arms)';
}

async function loadSkinIntoViewer(source, modelType = currentSkinModelType) {
  if (skinViewerInstance) {
    try {
      currentSkinSource = source;
      await skinViewerInstance.loadSkin(source, { model: modelType });
      updateSkinModelBadge();
    } catch (err) {
      console.error('Failed to load skin in viewer:', err);
    }
  }
  if (skinMiniPreviewInstance && source) {
    try {
      currentMiniPreviewSkinUrl = source;
      await skinMiniPreviewInstance.loadSkin(source, { model: modelType });
      if (skinMiniPreviewInstance.playerObject) {
        skinMiniPreviewInstance.playerObject.rotation.y = getFacingYaw(currentSkinFacing);
        skinMiniPreviewInstance.playerObject.position.y = -2.2;
      }
    } catch (err) {
      console.warn('Could not sync mini preview skin:', err);
    }
  }
}

async function loadCapeIntoViewer(capeKeyOrUrl) {
  const url = PRESET_CAPES[capeKeyOrUrl] !== undefined ? PRESET_CAPES[capeKeyOrUrl] : capeKeyOrUrl;
  if (skinViewerInstance) {
    try {
      if (!url) {
        skinViewerInstance.resetCape();
        skinViewerInstance.playerObject.backEquipment = null;
      } else {
        await skinViewerInstance.loadCape(url, { backEquipment: currentSkinEquipType });
      }
    } catch (err) {
      console.error('Failed to load cape in viewer:', err);
    }
  }
  if (skinMiniPreviewInstance) {
    try {
      if (!url) {
        skinMiniPreviewInstance.resetCape();
        skinMiniPreviewInstance.playerObject.backEquipment = null;
      } else {
        await skinMiniPreviewInstance.loadCape(url, { backEquipment: currentSkinEquipType });
      }
    } catch (err) {
      console.warn('Could not sync mini preview cape:', err);
    }
  }
}

function resizeSkinViewer() {
  if (!skinViewerInstance) return;
  const container = document.getElementById('skin-viewer-canvas-container');
  if (!container) return;
  const w = container.clientWidth;
  const h = container.clientHeight;
  if (w > 0 && h > 0) {
    skinViewerInstance.setSize(w, h);
  }
}

function takeSkinScreenshot() {
  if (!skinViewerInstance) return;
  skinViewerInstance.render();
  const dataUrl = skinViewerInstance.canvas.toDataURL('image/png');
  const a = document.createElement('a');
  a.href = dataUrl;
  a.download = `minecraft-skin-${Date.now()}.png`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  showToast('Screenshot saved to downloads!', 'success');
}

function getAccountSkinSettingsKey(acc) {
  if (!acc) return 'zero_skin_acc_global';
  return 'zero_skin_acc_' + (acc.id || acc.username || 'default');
}

function loadSkinSettingsForAccount(acc, globalSettings) {
  const isOffline = !acc || acc.account_type === 'offline' || !acc.mc_uuid;
  const key = getAccountSkinSettingsKey(acc);
  let saved = null;
  try {
    const raw = localStorage.getItem(key);
    if (raw) saved = JSON.parse(raw);
  } catch (_) {}

  const g = globalSettings || settings || {};

  currentSkinAnimName = (saved && saved.animation) || g.skin_animation || 'walk';
  currentSkinSpeed = (saved && typeof saved.speed === 'number') ? saved.speed : (typeof g.skin_speed === 'number' ? g.skin_speed : 0.5);
  currentSkinFacing = (saved && saved.facing) || g.skin_facing || 'left';
  currentSkinEquipType = (saved && saved.equip) || g.skin_equip_type || 'cape';

  if (isOffline) {
    // Offline accounts default to Unknown Skin, but can switch to My Skin.
    currentSkinAnonSkin = (saved && typeof saved.anonSkin === 'boolean') ? saved.anonSkin : true;
  } else {
    // Microsoft accounts default to showing their own skin unless specifically set to anonymous
    currentSkinAnonSkin = (saved && typeof saved.anonSkin === 'boolean') ? saved.anonSkin : false;
  }

  currentSkinAnonTag = (saved && typeof saved.anonTag === 'boolean')
    ? saved.anonTag
    : (typeof g.skin_anonymous_nametag === 'boolean' ? g.skin_anonymous_nametag : false);

  // Profile Picture (the small round avatar shown in the header/account
  // list) — same "My X / Unknown X" pattern as Skin: offline accounts
  // default to the black "?" unknown picture, Microsoft accounts default
  // to their real head render, but either can switch either way.
  if (saved && typeof saved.anonPic === 'boolean') {
    currentSkinAnonPic = saved.anonPic;
  } else if (typeof g.skin_anonymous_pic === 'boolean') {
    currentSkinAnonPic = g.skin_anonymous_pic;
  } else {
    currentSkinAnonPic = isOffline;
  }

  currentSkinEquippedPath = (saved && saved.equippedSkinPath) || null;
  currentSkinEquippedId = (saved && saved.equippedSkinId) || null;
  currentSkinEquippedName = (saved && saved.equippedSkinName) || null;
}

/// Whether a given account's profile picture (header/account-list avatar)
/// should show the black "?" unknown placeholder instead of its real head
/// render. Reads the same per-account settings as the skin viewer's
/// Anonymous section, without disturbing the currently-loaded in-modal
/// state (`currentSkinAnonPic`).
function shouldUseUnknownProfilePic(acc) {
  if (!acc) return true;
  const isOffline = acc.account_type === 'offline' || !acc.mc_uuid;
  const key = getAccountSkinSettingsKey(acc);
  let saved = null;
  try {
    const raw = localStorage.getItem(key);
    if (raw) saved = JSON.parse(raw);
  } catch (_) {}
  if (saved && typeof saved.anonPic === 'boolean') return saved.anonPic;
  if (settings && typeof settings.skin_anonymous_pic === 'boolean') return settings.skin_anonymous_pic;
  return isOffline;
}

/// Whether a given account's nametag should show "Unknown" instead of its real
/// username in the accounts button and accounts manager modal list.
function shouldUseUnknownNametag(acc) {
  if (!acc) return false;
  const key = getAccountSkinSettingsKey(acc);
  let saved = null;
  try {
    const raw = localStorage.getItem(key);
    if (raw) saved = JSON.parse(raw);
  } catch (_) {}
  if (saved && typeof saved.anonTag === 'boolean') return saved.anonTag;
  if (settings && typeof settings.skin_anonymous_nametag === 'boolean') return settings.skin_anonymous_nametag;
  return false;
}

function saveSkinSettingsForAccount(acc, customSettings) {
  if (!acc) return;
  const key = getAccountSkinSettingsKey(acc);
  const data = {
    animation: currentSkinAnimName,
    speed: currentSkinSpeed,
    facing: currentSkinFacing,
    equip: currentSkinEquipType,
    anonSkin: Boolean(currentSkinAnonSkin),
    anonTag: Boolean(currentSkinAnonTag),
    anonPic: Boolean(currentSkinAnonPic),
    equippedSkinPath: currentSkinEquippedPath,
    equippedSkinId: currentSkinEquippedId,
    equippedSkinName: currentSkinEquippedName,
    ...customSettings
  };
  try {
    localStorage.setItem(key, JSON.stringify(data));
    if (acc.username) {
      localStorage.setItem('zero_skin_acc_' + acc.username, JSON.stringify(data));
    }
  } catch (_) {}
}

async function openSkinViewerModal() {
  const overlay = document.getElementById('skin-viewer-overlay');
  if (!overlay) return;
  overlay.classList.remove('hidden');

  if (skinViewerInstance) {
    skinViewerInstance.renderPaused = false;
  }

  // The standee sits fully behind this modal — no point spending GPU time
  // rendering WebGL frames nobody can see while it's covered.
  if (skinMiniPreviewInstance) {
    skinMiniPreviewInstance.renderPaused = true;
  }

  const accounts = await api.getAccounts().catch(() => []);
  const active = accounts.find(a => a.is_active);
  const isOffline = !active || active.account_type === 'offline' || !active.mc_uuid;

  // Load active account's specific skin settings
  loadSkinSettingsForAccount(active);

  // Update active facing buttons
  overlay.querySelectorAll('#skin-facing-group .skin-speed-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.facing === currentSkinFacing);
  });

  // Update active speed buttons
  overlay.querySelectorAll('#skin-speed-group .skin-speed-btn').forEach(btn => {
    btn.classList.toggle('active', parseFloat(btn.dataset.speed) === currentSkinSpeed);
  });

  // Update active animation buttons
  overlay.querySelectorAll('#skin-anim-buttons .skin-anim-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.anim === currentSkinAnimName);
  });

  // Update active equip type buttons (Cape / Elytra / None)
  overlay.querySelectorAll('#skin-equip-group .skin-speed-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.equip === currentSkinEquipType);
  });

  // Update Anonymous Skin buttons — offline accounts default to Unknown
  // Skin, but (like Microsoft accounts) can freely switch to My Skin.
  const anonSkinGroup = overlay.querySelector('#skin-anon-skin-group');
  if (anonSkinGroup) {
    const mySkinBtn = anonSkinGroup.querySelector('[data-anon-skin="false"]');
    const unkSkinBtn = anonSkinGroup.querySelector('[data-anon-skin="true"]');
    if (mySkinBtn) {
      mySkinBtn.disabled = false;
      mySkinBtn.style.opacity = '1';
      mySkinBtn.style.cursor = 'pointer';
      mySkinBtn.removeAttribute('title');
      mySkinBtn.classList.toggle('active', !currentSkinAnonSkin);
    }
    if (unkSkinBtn) {
      unkSkinBtn.classList.toggle('active', !!currentSkinAnonSkin);
    }
  }

  // Update Anonymous Nametag buttons
  const anonTagGroup = overlay.querySelector('#skin-anon-tag-group');
  if (anonTagGroup) {
    anonTagGroup.querySelectorAll('.skin-speed-btn').forEach(btn => {
      const isAnon = btn.dataset.anonTag === 'true';
      btn.classList.toggle('active', isAnon === !!currentSkinAnonTag);
    });
  }

  // Update Anonymous Profile Picture buttons
  const anonPicGroup = overlay.querySelector('#skin-anon-pic-group');
  if (anonPicGroup) {
    anonPicGroup.querySelectorAll('.skin-speed-btn').forEach(btn => {
      const isAnon = btn.dataset.anonPic === 'true';
      btn.classList.toggle('active', isAnon === !!currentSkinAnonPic);
    });
  }
}

/// Lightweight header-avatar update used while the skin viewer's Picture
/// toggle is being dragged around but not yet Applied — updates just the
/// header image/fallback from the in-memory choice, without touching
/// localStorage/settings.json (that only happens on Apply), so it can't
/// accidentally get overwritten back from stale saved data.
async function previewHeaderAvatarUnknownState(anonPic) {
  const headerImg = document.getElementById('account-header-avatar-img');
  const headerFallback = document.getElementById('account-header-fallback');
  if (!headerImg || !headerFallback) return;
  const accounts = await api.getAccounts().catch(() => []);
  const active = accounts.find(a => a.is_active);
  if (active && !anonPic) {
    const headKey = encodeURIComponent(active.mc_uuid || active.username || 'MHF_Steve');
    headerImg.src = `https://mc-heads.net/avatar/${headKey}/32`;
    headerImg.classList.remove('hidden');
    headerFallback.style.display = 'none';
    headerImg.onerror = () => {
      headerImg.classList.add('hidden');
      headerFallback.style.display = '';
    };
  } else {
    headerImg.classList.add('hidden');
    headerImg.removeAttribute('src');
    headerFallback.style.display = '';
  }
}

function closeSkinViewerModal() {
  const overlay = document.getElementById('skin-viewer-overlay');
  if (!overlay) return;
  overlay.classList.add('hidden');
  if (skinViewerInstance) {
    skinViewerInstance.renderPaused = true;
  }
  if (skinMiniPreviewInstance) {
    skinMiniPreviewInstance.renderPaused = false;
  }
}

let dressingRoomCardRenders = new Map();
let dressingRoomIntersectionObserver = null;

// Best-effort trim of a per-card skin3d Render's WebGL cost: cap the
// device pixel ratio (a 130x165 card doesn't need to render at a laptop's
// full 2x/3x retina density) and drop antialiasing on the underlying
// three.js renderer. WebKitGTK's GL path is noticeably more expensive per
// pixel than Chromium/macOS WebKit, so this matters more here than it
// would elsewhere. Wrapped defensively since skin3d doesn't officially
// document `.renderer` as public API — if a future version renames or
// removes it, this just silently no-ops instead of breaking the card.
function lightenCardRenderer(render) {
  try {
    const renderer = render && render.renderer;
    if (renderer && typeof renderer.setPixelRatio === 'function') {
      renderer.setPixelRatio(1);
    }
  } catch (_) {}
}

function setupDressingRoomObserver() {
  if (dressingRoomIntersectionObserver) {
    dressingRoomIntersectionObserver.disconnect();
  }
  const scrollContainer = document.getElementById('dressing-room-skins');
  try {
    dressingRoomIntersectionObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const card = entry.target;
        const skinId = card.dataset.skinId;
        const item = dressingRoomCardRenders.get(skinId);
        if (!item) continue;
        if (entry.isIntersecting) {
          item.isVisible = true;
          if (item.render) {
            item.render.renderPaused = false;
          } else if (item.lazyInit) {
            item.lazyInit();
          }
        } else {
          item.isVisible = false;
          if (item.render) {
            item.render.renderPaused = true;
          }
        }
      }
    }, {
      root: scrollContainer || null,
      rootMargin: '120px 0px 120px 0px',
      threshold: 0.01
    });
  } catch (err) {
    console.warn('IntersectionObserver init error:', err);
  }
}

function pauseAllDressingRoomCards() {
  for (const item of dressingRoomCardRenders.values()) {
    if (item.render) {
      item.render.renderPaused = true;
    }
  }
}

function cleanupDressingRoomCards() {
  if (dressingRoomIntersectionObserver) {
    dressingRoomIntersectionObserver.disconnect();
  }
  for (const item of dressingRoomCardRenders.values()) {
    if (item.render && typeof item.render.dispose === 'function') {
      try { item.render.dispose(); } catch (_) {}
    }
  }
  dressingRoomCardRenders.clear();
}

/// Dressing Room — 3D Wardrobe for browsing, adding, and equipping skins and capes.
async function openDressingRoomModal() {
  const overlay = document.getElementById('dressing-room-overlay');
  if (!overlay) return;
  overlay.classList.remove('hidden');

  // Pause main menu standee while Dressing Room is open
  if (skinMiniPreviewInstance) {
    skinMiniPreviewInstance.renderPaused = true;
  }

  setupDressingRoomObserver();
  await populateDressingRoomSkins();
}

function closeDressingRoomModal() {
  const overlay = document.getElementById('dressing-room-overlay');
  if (!overlay) return;
  overlay.classList.add('hidden');

  // Pause all 3D cards in the Dressing Room
  pauseAllDressingRoomCards();

  // Resume main menu standee
  showSkinMiniPreview();
}

function setDressingRoomBusy(busy, message = 'Updating Mojang servers…') {
  const overlay = document.getElementById('dressing-room-busy-overlay');
  const textEl = document.getElementById('dressing-room-busy-text');
  const modal = document.querySelector('.dressing-room-modal');
  if (textEl && message) textEl.textContent = message;
  if (overlay) {
    overlay.classList.toggle('hidden', !busy);
  }
  if (modal) {
    modal.style.pointerEvents = busy ? 'none' : 'auto';
  }
}

async function populateDressingRoomSkins() {
  const grid = document.getElementById('dressing-room-skins-grid');
  if (!grid) return;

  cleanupDressingRoomCards();
  setupDressingRoomObserver();
  grid.innerHTML = '';

  const accounts = await api.getAccounts().catch(() => []);
  const active = accounts.find(a => a.is_active);
  loadSkinSettingsForAccount(active);

  // Auto-cache active account's skin to Zero Launcher/skins/ if available
  if (active && active.username) {
    const isOffline = active.account_type === 'offline' || !active.mc_uuid;
    const remoteUrl = !isOffline ? `https://mineskin.eu/skin/${encodeURIComponent(active.username)}` : null;
    if (remoteUrl) {
      api.cacheSkinTexture(active.username, remoteUrl).catch(() => {});
    }
  }

  let skins = await api.listSkins().catch(() => []);

  if (!skins || skins.length === 0) {
    const emptyNotice = document.createElement('div');
    emptyNotice.className = 'skin-control-label';
    emptyNotice.style.gridColumn = '1 / -1';
    emptyNotice.style.padding = '24px 0';
    emptyNotice.textContent = 'No skins stored in skins folder yet. Click "Add a Skin" to import your Minecraft skin (.png)!';
    grid.appendChild(emptyNotice);
    return;
  }

  for (const skin of skins) {
    const card = document.createElement('div');
    card.className = 'dressing-room-skin-card';
    card.dataset.skinId = skin.id;
    card.dataset.skinPath = skin.path;

    const isEquipped = (currentSkinEquippedPath && (currentSkinEquippedPath === skin.path || currentSkinEquippedId === skin.id))
      || (!currentSkinEquippedPath && active && skin.id === active.username);

    if (isEquipped) {
      card.classList.add('equipped');
    }

    // Equipped badge
    const badge = document.createElement('span');
    badge.className = 'dressing-room-card-badge';
    badge.textContent = 'EQUIPPED';
    card.appendChild(badge);

    // Delete button
    const deleteBtn = document.createElement('button');
    deleteBtn.type = 'button';
    deleteBtn.className = 'dressing-room-card-delete-btn';
    deleteBtn.title = 'Delete Skin';
    deleteBtn.innerHTML = '✕';
    deleteBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      try {
        await api.deleteSkin(skin.path);
        showToast(`Deleted skin "${skin.name}"`, 'success');
        if (currentSkinEquippedPath === skin.path) {
          currentSkinEquippedPath = null;
          currentSkinEquippedId = null;
          currentSkinEquippedName = null;
          saveSkinSettingsForAccount(active, {
            equippedSkinPath: null,
            equippedSkinId: null,
            equippedSkinName: null,
          });
          updateSkinMiniPreview();
        }
        await populateDressingRoomSkins();
      } catch (err) {
        showToast(`Failed to delete skin: ${err}`, 'error');
      }
    });
    card.appendChild(deleteBtn);

    // Canvas container
    const canvasWrap = document.createElement('div');
    canvasWrap.className = 'dressing-room-card-canvas-wrap';
    const canvas = document.createElement('canvas');
    canvas.className = 'dressing-room-card-canvas';
    canvasWrap.appendChild(canvas);
    card.appendChild(canvasWrap);

    // Skin label
    const nameLabel = document.createElement('span');
    nameLabel.className = 'dressing-room-skin-name';
    nameLabel.textContent = skin.name || skin.id;
    nameLabel.title = skin.name || skin.id;
    card.appendChild(nameLabel);

    // Click handler -> equip skin (only equips after server confirms)
    card.addEventListener('click', async () => {
      if (card.classList.contains('equipped')) return;

      const isMsa = active && active.account_type === 'microsoft' && active.mc_uuid;

      if (isMsa) {
        setDressingRoomBusy(true, `Uploading skin "${skin.name}" to Mojang servers…`);
        try {
          await api.uploadSkinToMojang(skin.path, 'classic', active.id);
          showToast(`Skin "${skin.name}" accepted by Mojang and equipped!`, 'success');
        } catch (err) {
          console.warn('Mojang skin upload rejected:', err);
          showToast(`Mojang rejected skin upload: ${err}`, 'error');
          setDressingRoomBusy(false);
          return; // Do NOT equip locally if rejected by server
        }
        setDressingRoomBusy(false);
      }

      // Apply locally once confirmed
      currentSkinEquippedPath = skin.path;
      currentSkinEquippedId = skin.id;
      currentSkinEquippedName = skin.name;
      currentSkinAnonSkin = false;

      saveSkinSettingsForAccount(active, {
        equippedSkinPath: skin.path,
        equippedSkinId: skin.id,
        equippedSkinName: skin.name,
        anonSkin: false,
      });

      // Update equipped class across cards
      grid.querySelectorAll('.dressing-room-skin-card').forEach(c => c.classList.remove('equipped'));
      card.classList.add('equipped');

      // Update main menu 3D player standee
      const assetUrl = window.__TAURI__.core.convertFileSrc(skin.path);
      if (skinMiniPreviewInstance) {
        try {
          currentMiniPreviewSkinUrl = assetUrl;
          await skinMiniPreviewInstance.loadSkin(assetUrl, { model: 'auto-detect' });
        } catch (err) {
          console.warn('Could not sync mini preview skin:', err);
        }
      }

      if (!isMsa) {
        showToast(`Equipped skin "${skin.name}"`, 'success');
      }
    });

    // 3D Player Viewer initialization (Lazy loaded per card when visible).
    // This used to also be called eagerly right below, which meant every
    // single skin card spun up its own WebGL context + render loop the
    // instant the grid was built, regardless of whether it was ever
    // scrolled into view. WebKitGTK handles many concurrent GL contexts
    // far worse than Chromium/WebKit-on-macOS does, so a wardrobe with
    // 20-30 skins was opening 20-30 live contexts at once — that's the
    // main source of the lag. Now init only happens via the
    // IntersectionObserver below (which already fires immediately for
    // any card visible at the time it's observed, so on-screen cards
    // still render right away — off-screen ones just don't, until scrolled
    // into the 120px margin).
    const skinAssetUrl = window.__TAURI__.core.convertFileSrc(skin.path);
    let cardRender = null;
    const initCardRender = async () => {
      if (cardRender) return;
      try {
        canvas.addEventListener('webglcontextlost', (e) => e.preventDefault(), false);
        const anim = new IdleAnimation();
        anim.speed = 1.0;
        cardRender = new Render({
          canvas: canvas,
          width: 130,
          height: 165,
          fov: 42,
          zoom: 0.88,
          preserveDrawingBuffer: false,
          enableControls: false,
          enableRotation: false,
          allowZoom: false,
          animation: anim,
        });
        if (cardRender.controls) {
          cardRender.controls.enabled = false;
          cardRender.controls.enableRotate = false;
          cardRender.controls.enableZoom = false;
          cardRender.controls.enablePan = false;
        }
        if (cardRender.playerWrapper) {
          cardRender.playerWrapper.position.y = -1.2;
        }
        lightenCardRenderer(cardRender);
        await cardRender.loadSkin(skinAssetUrl, { model: 'auto-detect' });

        const entry = dressingRoomCardRenders.get(skin.id);
        if (entry) {
          entry.render = cardRender;
          if (!entry.isVisible) {
            cardRender.renderPaused = true;
          }
        }
      } catch (err) {
        console.warn('Failed to load 3D skin for card:', skin.name, err);
      }
    };

    dressingRoomCardRenders.set(skin.id, {
      render: null,
      canvas,
      card,
      lazyInit: initCardRender,
      isVisible: true,
    });

    grid.appendChild(card);
    if (dressingRoomIntersectionObserver) {
      dressingRoomIntersectionObserver.observe(card);
    }
  }
}

async function populateDressingRoomCapes() {
  const grid = document.getElementById('dressing-room-capes-grid');
  if (!grid) return;

  // Same reasoning as populateDressingRoomSkins: without this, switching
  // from the Skins tab to Capes tab left every skin card's WebGL context
  // alive underneath while a full new set of cape contexts spun up on top
  // of them — effectively doubling the live GL context count for as long
  // as the wardrobe stayed open. Tear down whatever the previous tab had
  // before building this one.
  cleanupDressingRoomCards();
  setupDressingRoomObserver();
  grid.innerHTML = '';

  const accounts = await api.getAccounts().catch(() => []);
  const active = accounts.find(a => a.is_active);
  const isOffline = !active || active.account_type === 'offline' || !active.mc_uuid;

  if (isOffline) {
    const note = document.createElement('div');
    note.className = 'dressing-room-empty-center';
    note.innerHTML = `
      <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="margin-bottom:10px; opacity:0.6;"><circle cx="12" cy="12" r="10"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
      <span>Sign in with a Microsoft account to view and equip your official Mojang capes.</span>
    `;
    grid.appendChild(note);
    return;
  }

  // Centered Loading State
  const loading = document.createElement('div');
  loading.className = 'dressing-room-empty-center';
  loading.innerHTML = `
    <span class="msa-device-spinner" style="width:28px; height:28px; border-radius:50%; border:3px solid var(--accent); border-top-color:transparent; display:inline-block; animation: msa-spin 0.8s linear infinite; margin-bottom:12px;"></span>
    <span>Fetching official Mojang capes…</span>
  `;
  grid.appendChild(loading);

  let capes = [];
  try {
    capes = await api.getAccountCapes(active.id);
  } catch (err) {
    console.warn('Failed to fetch capes from Mojang:', err);
    grid.innerHTML = '';
    const errNotice = document.createElement('div');
    errNotice.className = 'dressing-room-empty-center';
    errNotice.innerHTML = `
      <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="var(--danger, #ef4444)" stroke-width="1.5" style="margin-bottom:10px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
      <span>Could not load capes from Mojang: ${escapeHtml(String(err))}</span>
    `;
    grid.appendChild(errNotice);
    return;
  }

  grid.innerHTML = '';

  const activeCape = (capes || []).find(c => c.state === 'ACTIVE');

  // 1. "None / Unequip" Card
  const noneCard = document.createElement('div');
  noneCard.className = 'dressing-room-skin-card';
  if (!activeCape) {
    noneCard.classList.add('equipped');
  }

  const noneBadge = document.createElement('span');
  noneBadge.className = 'dressing-room-card-badge';
  noneBadge.textContent = 'EQUIPPED';
  noneCard.appendChild(noneBadge);

  const noneCanvasWrap = document.createElement('div');
  noneCanvasWrap.className = 'dressing-room-card-canvas-wrap';
  noneCanvasWrap.style.display = 'flex';
  noneCanvasWrap.style.flexDirection = 'column';
  noneCanvasWrap.style.alignItems = 'center';
  noneCanvasWrap.style.justifyContent = 'center';
  noneCanvasWrap.style.color = 'var(--text-muted)';
  noneCanvasWrap.innerHTML = `
    <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" style="opacity:0.6;"><circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/></svg>
    <span style="font-size:11px; margin-top:8px; opacity:0.8;">No Cape</span>
  `;
  noneCard.appendChild(noneCanvasWrap);

  const noneLabel = document.createElement('span');
  noneLabel.className = 'dressing-room-skin-name';
  noneLabel.textContent = 'None (Unequip)';
  noneCard.appendChild(noneLabel);

  noneCard.addEventListener('click', async () => {
    if (noneCard.classList.contains('equipped')) return;

    setDressingRoomBusy(true, 'Removing cape on Mojang servers…');
    try {
      await api.equipMojangCape(null, active.id);
      showToast('Cape unequipped on Mojang servers!', 'success');
    } catch (err) {
      console.warn('Failed to unequip cape:', err);
      showToast(`Failed to unequip cape on Mojang: ${err}`, 'error');
      setDressingRoomBusy(false);
      return; // Do NOT update equipped UI if rejected by server
    }
    setDressingRoomBusy(false);

    grid.querySelectorAll('.dressing-room-skin-card').forEach(c => c.classList.remove('equipped'));
    noneCard.classList.add('equipped');

    // Force clear cape on 3D player standee
    if (skinMiniPreviewInstance) {
      skinMiniPreviewInstance.loadCape(null).catch(() => {});
      if (skinMiniPreviewInstance.playerObject) {
        if (skinMiniPreviewInstance.playerObject.cape) skinMiniPreviewInstance.playerObject.cape.visible = false;
        if (skinMiniPreviewInstance.playerObject.elytra) skinMiniPreviewInstance.playerObject.elytra.visible = false;
      }
    }
  });

  grid.appendChild(noneCard);

  // 2. Render each owned cape
  if (!capes || capes.length === 0) {
    return;
  }

  for (const cape of capes) {
    const card = document.createElement('div');
    card.className = 'dressing-room-skin-card';
    card.dataset.capeId = cape.id;

    const isEquipped = cape.state === 'ACTIVE';
    if (isEquipped) {
      card.classList.add('equipped');
    }

    // Equipped badge
    const badge = document.createElement('span');
    badge.className = 'dressing-room-card-badge';
    badge.textContent = 'EQUIPPED';
    card.appendChild(badge);

    // Cape preview container
    const canvasWrap = document.createElement('div');
    canvasWrap.className = 'dressing-room-card-canvas-wrap';
    const canvas = document.createElement('canvas');
    canvas.className = 'dressing-room-card-canvas';
    canvasWrap.appendChild(canvas);
    card.appendChild(canvasWrap);

    // Cape label
    const nameLabel = document.createElement('span');
    nameLabel.className = 'dressing-room-skin-name';
    nameLabel.textContent = cape.alias || 'Minecraft Cape';
    nameLabel.title = cape.alias || 'Minecraft Cape';
    card.appendChild(nameLabel);

    // Click handler -> Equip Cape on Mojang servers (only updates after server confirms)
    card.addEventListener('click', async () => {
      if (card.classList.contains('equipped')) return;

      setDressingRoomBusy(true, `Equipping "${cape.alias || 'Cape'}" on Mojang servers…`);
      try {
        await api.equipMojangCape(cape.id, active.id);
        showToast(`Equipped "${cape.alias || 'Cape'}" on Mojang servers!`, 'success');
      } catch (err) {
        console.warn('Failed to equip cape on Mojang:', err);
        showToast(`Failed to equip cape on Mojang: ${err}`, 'error');
        setDressingRoomBusy(false);
        return; // Do NOT update equipped UI if rejected by server
      }
      setDressingRoomBusy(false);

      grid.querySelectorAll('.dressing-room-skin-card').forEach(c => c.classList.remove('equipped'));
      card.classList.add('equipped');

      if (skinMiniPreviewInstance) {
        skinMiniPreviewInstance.loadCape(cape.url).catch(() => {});
        if (skinMiniPreviewInstance.playerObject) {
          if (skinMiniPreviewInstance.playerObject.cape) skinMiniPreviewInstance.playerObject.cape.visible = true;
        }
      }
    });

    grid.appendChild(card);

    // 3D cape viewer — now lazy via the shared IntersectionObserver instead
    // of firing for every cape the instant the tab opens. This mirrors the
    // skins tab fix: previously every cape card opened its own WebGL
    // context immediately (and there wasn't even an observer wired up for
    // this grid at all), so a wardrobe with many capes meant that many
    // concurrent GL contexts — a major lag source on WebKitGTK.
    const capeKey = `cape-${cape.id}`;
    card.dataset.skinId = capeKey; // key the shared observer looks up by
    let capeRender = null;
    const initCapeRender = async () => {
      if (capeRender) return;
      try {
        canvas.addEventListener('webglcontextlost', (e) => e.preventDefault(), false);
        const anim = new IdleAnimation();
        anim.speed = 0.8;
        capeRender = new Render({
          canvas: canvas,
          width: 130,
          height: 165,
          fov: 42,
          zoom: 0.88,
          preserveDrawingBuffer: false,
          enableControls: false,
          enableRotation: false,
          animation: anim,
        });
        lightenCardRenderer(capeRender);

        // Rotate scene 180 degrees so the back/cape is fully displayed
        if (capeRender.playerObject) {
          capeRender.playerObject.rotation.y = Math.PI;
        }

        // Load skin and cape
        const skinUrl = currentMiniPreviewSkinUrl || `https://mineskin.eu/skin/${encodeURIComponent(active.username)}`;
        await capeRender.loadSkin(skinUrl, { model: 'auto-detect' }).catch(() => {});
        await capeRender.loadCape(cape.url).catch(() => {});

        const entry = dressingRoomCardRenders.get(capeKey);
        if (entry) {
          entry.render = capeRender;
          if (!entry.isVisible) {
            capeRender.renderPaused = true;
          }
        }
      } catch (err) {
        console.warn('Could not initialize cape card 3D viewer:', err);
      }
    };

    dressingRoomCardRenders.set(capeKey, {
      render: null,
      canvas,
      card,
      lazyInit: initCapeRender,
      isVisible: true,
    });

    if (dressingRoomIntersectionObserver) {
      dressingRoomIntersectionObserver.observe(card);
    }
  }
}

function initDressingRoomUI() {
  const overlay = document.getElementById('dressing-room-overlay');
  if (!overlay) return;

  // Close handlers
  document.getElementById('btn-close-dressing-room')?.addEventListener('click', closeDressingRoomModal);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeDressingRoomModal();
  });

  // Tab switching (Skins / Capes)
  const tabs = document.getElementById('dressing-room-tabs');
  if (tabs) {
    tabs.querySelectorAll('.dressing-room-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        const target = tab.dataset.dressingTab;
        tabs.querySelectorAll('.dressing-room-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        overlay.querySelectorAll('.dressing-room-section').forEach(sec => {
          sec.classList.toggle('active', sec.dataset.dressingSection === target);
        });
        if (target === 'capes') {
          populateDressingRoomCapes();
        }
      });
    });
  }

  // "Add a Skin" button
  const addBtn = document.getElementById('btn-dressing-add-skin');
  const fileInput = document.getElementById('dressing-skin-file-input');

  const handleImport = async (sourcePath) => {
    if (!sourcePath) return;
    try {
      const imported = await api.importSkin(sourcePath);
      showToast(`Added skin "${imported.name}"!`, 'success');

      // Automatically equip the newly imported skin
      const accounts = await api.getAccounts().catch(() => []);
      const active = accounts.find(a => a.is_active);
      if (active) {
        currentSkinEquippedPath = imported.path;
        currentSkinEquippedId = imported.id;
        currentSkinEquippedName = imported.name;
        currentSkinAnonSkin = false;
        saveSkinSettingsForAccount(active, {
          equippedSkinPath: imported.path,
          equippedSkinId: imported.id,
          equippedSkinName: imported.name,
          anonSkin: false,
        });
        updateSkinMiniPreview();

        // Debounced & serialized sync to Mojang servers if this is a Microsoft account
        syncSkinToMojangDebounced(imported.path, imported.name, active);
      }
      await populateDressingRoomSkins();
    } catch (err) {
      showToast(`Failed to add skin: ${err}`, 'error');
    }
  };

  if (addBtn && !addBtn.dataset.bound) {
    addBtn.dataset.bound = '1';
    addBtn.addEventListener('click', async (e) => {
      e.preventDefault();
      try {
        let selected = null;
        if (window.__TAURI__ && window.__TAURI__.dialog && window.__TAURI__.dialog.open) {
          selected = await window.__TAURI__.dialog.open({
            title: 'Select Minecraft Skin (.png)',
            filters: [{ name: 'Minecraft Skin (*.png)', extensions: ['png'] }],
            multiple: false,
          });
        }
        if (selected) {
          await handleImport(selected);
        } else if (!window.__TAURI__ || !window.__TAURI__.dialog) {
          fileInput?.click();
        }
      } catch (err) {
        showToast(`Failed to open file dialog: ${err}`, 'error');
      }
    });
  }

  // Fallback file input change
  if (fileInput && !fileInput.dataset.bound) {
    fileInput.dataset.bound = '1';
    fileInput.addEventListener('change', async () => {
      const file = fileInput.files && fileInput.files[0];
      if (!file) return;
      if (file.path) {
        await handleImport(file.path);
      }
      fileInput.value = '';
    });
  }

  // Capes note link
  document.getElementById('dressing-room-editskin-link')?.addEventListener('click', async () => {
    const url = 'https://www.minecraft.net/en-us/msaprofile/mygames/editskin';
    if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
      await window.__TAURI__.shell.open(url);
    } else {
      window.open(url, '_blank');
    }
  });
}

function initSkinViewerUI() {
  const overlay = document.getElementById('skin-viewer-overlay');
  if (!overlay) return;

  // Close handlers
  document.getElementById('btn-close-skin-viewer')?.addEventListener('click', closeSkinViewerModal);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeSkinViewerModal();
  });

  // Animation selection buttons
  overlay.querySelectorAll('#skin-anim-buttons .skin-anim-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const animName = btn.dataset.anim;
      overlay.querySelectorAll('#skin-anim-buttons .skin-anim-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinAnimName = animName;
    });
  });

  // Speed selection buttons
  overlay.querySelectorAll('#skin-speed-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const speed = parseFloat(btn.dataset.speed) || 1.0;
      overlay.querySelectorAll('#skin-speed-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinSpeed = speed;
    });
  });

  // Facing direction buttons (Left / Right / Camera)
  overlay.querySelectorAll('#skin-facing-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const facing = btn.dataset.facing || 'left';
      overlay.querySelectorAll('#skin-facing-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinFacing = facing;
      // Apply immediately so the preview reflects the choice right away —
      // previously this only took effect after hitting Apply, which made
      // the buttons look broken/unresponsive.
      if (skinMiniPreviewInstance && skinMiniPreviewInstance.playerObject) {
        skinMiniPreviewInstance.playerObject.rotation.y = getFacingYaw(currentSkinFacing);
      }
    });
  });

  // Back equipment type buttons (Cape / Elytra / None)
  overlay.querySelectorAll('#skin-equip-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const equip = btn.dataset.equip || 'cape';
      overlay.querySelectorAll('#skin-equip-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinEquipType = equip;
      // Apply immediately so the preview reflects the choice right away —
      // previously this only took effect after hitting Apply.
      if (skinMiniPreviewInstance) {
        try {
          const accounts = await api.getAccounts().catch(() => []);
          const active = accounts.find(a => a.is_active);
          const isOffline = !active || active.account_type === 'offline' || !active.mc_uuid;
          if (equip === 'none') {
            await skinMiniPreviewInstance.loadCape(null);
          } else if (isOffline) {
            await skinMiniPreviewInstance.loadCape(PRESET_CAPES.vanilla, { backEquipment: equip });
          } else {
            await loadEquippedCapeOnStandee(equip);
          }
        } catch (_) {}
      }
    });
  });

  // Anonymous Skin buttons (My Skin / Unknown Skin) — freely switchable
  // for both offline and Microsoft accounts.
  overlay.querySelectorAll('#skin-anon-skin-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      overlay.querySelectorAll('#skin-anon-skin-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinAnonSkin = (btn.dataset.anonSkin === 'true');
    });
  });

  // Anonymous Nametag buttons (My Nametag / Unknown Nametag)
  overlay.querySelectorAll('#skin-anon-tag-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      overlay.querySelectorAll('#skin-anon-tag-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinAnonTag = (btn.dataset.anonTag === 'true');
    });
  });

  // Anonymous Profile Picture buttons (Use My Picture / Use Unknown) — only
  // update the header avatar preview here; don't call refreshAccountUI()
  // (it reloads settings from storage and would stomp this unsaved change
  // before Apply gets a chance to persist it).
  overlay.querySelectorAll('#skin-anon-pic-group .skin-speed-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      overlay.querySelectorAll('#skin-anon-pic-group .skin-speed-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSkinAnonPic = (btn.dataset.anonPic === 'true');
      previewHeaderAvatarUnknownState(currentSkinAnonPic).catch(() => {});
    });
  });

  // Apply Button — sync all settings to standee preview, save per account and globally, and close modal
  document.getElementById('btn-skin-apply')?.addEventListener('click', async () => {
    const accounts = await api.getAccounts().catch(() => []);
    const active = accounts.find(a => a.is_active);

    // Save per-account settings
    saveSkinSettingsForAccount(active);

    if (skinMiniPreviewInstance) {
      // Sync animation & speed
      const anim = createSkinAnimation(currentSkinAnimName);
      if (anim) anim.speed = currentSkinSpeed;
      skinMiniPreviewInstance.animation = anim;
      skinMiniPreviewInstance.renderPaused = false;

      // Sync facing angle & position
      if (skinMiniPreviewInstance.playerObject) {
        skinMiniPreviewInstance.playerObject.rotation.y = getFacingYaw(currentSkinFacing);
      }
      if (skinMiniPreviewInstance.playerWrapper) {
        skinMiniPreviewInstance.playerWrapper.position.y = -1.5;
      }
    }

    // Save to settings.json
    try {
      const settings = await api.getSettings().catch(() => ({}));
      settings.skin_animation = currentSkinAnimName;
      settings.skin_speed = currentSkinSpeed;
      settings.skin_facing = currentSkinFacing;
      settings.skin_equip_type = currentSkinEquipType;
      settings.skin_anonymous_skin = currentSkinAnonSkin;
      settings.skin_anonymous_nametag = currentSkinAnonTag;
      settings.skin_anonymous_pic = currentSkinAnonPic;
      await api.saveSettings(settings);
    } catch (err) {
      console.warn('Could not save skin settings to settings.json:', err);
    }

    // Refresh standee + header avatar with new skin/tag/picture settings
    await refreshAccountUI();

    closeSkinViewerModal();
    showToast('Skin settings saved for this account!', 'success');
  });
}

// ═══════════════════════════════════════════════════════════════════
// Non-Interactive 3D Skin Mini Preview (Instance Details Pane)
// ═══════════════════════════════════════════════════════════════════

let skinMiniPreviewInstance = null;
let skinMiniPreviewResizeObserver = null;
let currentMiniPreviewSkinUrl = null;

async function initSkinMiniPreview() {
  const canvas = document.getElementById('skin-mini-preview-canvas');
  const wrap = document.getElementById('skin-mini-preview-wrap');
  if (!canvas || !wrap || skinMiniPreviewInstance) return;

  const accounts = await api.getAccounts().catch(() => []);
  const active = accounts.find(a => a.is_active);
  const globalSettings = await api.getSettings().catch(() => null);

  // Load active account's skin settings
  loadSkinSettingsForAccount(active, globalSettings);

  const w = wrap.clientWidth || 440;
  const h = wrap.clientHeight || 560;

  const anim = createSkinAnimation(currentSkinAnimName);
  if (anim) anim.speed = currentSkinSpeed;

  // Bound once per canvas element (which persists across reinits, since
  // reinit just fetches the same DOM node again) rather than re-added
  // every time this function runs — otherwise a canvas that's been
  // rebuilt a few times ends up with several stacked listeners all firing
  // on the same event.
  if (!canvas.dataset.contextLostBound) {
    canvas.dataset.contextLostBound = '1';
    canvas.addEventListener('webglcontextlost', (e) => {
      e.preventDefault();
      // A tray restore can cause the underlying GPU surface to get torn
      // down more than once in quick succession while the OS is still
      // settling the window into place — a single one-shot rebuild right
      // after unhide isn't always the last time it happens, which is why
      // the model could come back briefly and then go blank again. So
      // instead of only reinitializing from the tray-restore hook, any
      // loss of this canvas's context — whenever it happens — schedules
      // its own rebuild directly.
      scheduleMiniPreviewRecover();
    }, false);
  }

  skinMiniPreviewInstance = new Render({
    canvas: canvas,
    width: w,
    height: h,
    fov: 40,
    zoom: 0.78,
    preserveDrawingBuffer: true,
    enableControls: false,
    enableRotation: false,
    allowZoom: false,
    enableFXAA: true,
    animation: anim,
  });

  // Explicitly ensure OrbitControls cannot be interacted with
  if (skinMiniPreviewInstance.controls) {
    skinMiniPreviewInstance.controls.enabled = false;
    skinMiniPreviewInstance.controls.enableRotate = false;
    skinMiniPreviewInstance.controls.enableZoom = false;
    skinMiniPreviewInstance.controls.enablePan = false;
  }

  // Set facing angle and center character with ample margin in all directions
  if (skinMiniPreviewInstance.playerObject) {
    skinMiniPreviewInstance.playerObject.rotation.y = getFacingYaw(currentSkinFacing);
  }
  if (skinMiniPreviewInstance.playerWrapper) {
    skinMiniPreviewInstance.playerWrapper.position.y = -1.5;
  }

  // The standee itself is no longer clickable — use the dedicated
  // "3D Skin Settings" button next to the account button instead.
  const skinSettingsBtn = document.getElementById('btn-open-skin-settings');
  if (skinSettingsBtn && !skinSettingsBtn.dataset.bound) {
    skinSettingsBtn.dataset.bound = '1';
    skinSettingsBtn.addEventListener('click', (e) => {
      e.preventDefault();
      openSkinViewerModal();
    });
  }

  // Clicking the 3D player model itself opens the Dressing Room.
  if (wrap && !wrap.dataset.dressingBound) {
    wrap.dataset.dressingBound = '1';
    wrap.addEventListener('click', (e) => {
      e.preventDefault();
      openDressingRoomModal();
    });
  }

  // ResizeObserver for dynamic layout changes
  if (typeof ResizeObserver !== 'undefined') {
    skinMiniPreviewResizeObserver = new ResizeObserver(() => {
      resizeSkinMiniPreview();
    });
    skinMiniPreviewResizeObserver.observe(wrap);
  }

  updateSkinMiniPreview();
}

// Brings the 3D player model (the "standee" preview in the Instances
// view's player card) back — the exact one-liner `closeDressingRoomModal`
// uses to make the model reappear once the Dressing Room overlay closes.
// Pulled out into its own function so the tray-restore path below can
// reuse this same "make the player appear" logic instead of duplicating
// (or diverging from) it.
function showSkinMiniPreview() {
  if (skinMiniPreviewInstance) {
    skinMiniPreviewInstance.renderPaused = false;
  }
}

// Called when the Rust side tells us the main window was just restored
// from the system tray (or re-focused after single-instance re-launch).
// Hiding the window natively — not a browser tab switch — is where the 3D
// player model was going blank: the WebGL context can get lost while the
// window is off-screen, and `isContextLost()` doesn't reliably report that
// on every GPU/driver (WebKitGTK on Linux in particular), so we can't fully
// trust it to decide whether a rebuild is needed. The modal viewer only
// exists while its overlay is open, so it's fine to keep using the lighter
// resume/wait-then-reinit path — but the standee preview (the one on the
// main screen, which is what was actually going blank) is unconditionally
// unloaded and reloaded from scratch: `reinitSkinMiniPreview(true)` throws
// away the old <canvas> element and Render instance entirely and builds a
// brand new one, which is more certain to come back clean than trying to
// resuscitate whatever WebGL context the window came back with.
function resumeSkinViewersAfterShow() {
  const tryResume = (getInstance, reinit) => {
    const inst = getInstance();
    if (!inst) return;
    try {
      inst.renderPaused = false;
      const ctx = inst.renderer?.getContext?.();
      if (ctx && ctx.isContextLost && ctx.isContextLost()) {
        setTimeout(() => {
          const stillLost = ctx.isContextLost && ctx.isContextLost();
          if (stillLost) reinit();
        }, 1500);
      } else {
        try { inst.render(); } catch (e) {}
      }
    } catch (e) {}
  };

  // NOTE: this used to be wrapped in nested requestAnimationFrame() calls
  // to wait for the window to finish compositing before touching WebGL.
  // That was the actual bug: rAF callbacks are frozen by the browser
  // whenever the page is (or still briefly reads as) not visible, which a
  // just-restored tray window often still does for a tick — so the whole
  // resume chain sat there never firing until something *else* (an actual
  // OS-level visibility flip, like alt-tabbing) unblocked the rAF queue and
  // let the plain `visibilitychange` handler's simpler `renderPaused =
  // false` get a chance to run instead. setTimeout isn't gated by page
  // visibility the same way, so it actually runs when this function is
  // called instead of silently waiting on a frame that isn't coming.
  setTimeout(() => {
    resizeSkinViewer();
    tryResume(() => skinViewerInstance, () => { /* modal viewer: user will reopen it */ });

    // Standee preview: unload it (dispose the old Render instance + swap
    // in a brand-new <canvas>) and load it back in, every time the window
    // comes back from the tray — rather than betting on `isContextLost()`,
    // which is exactly the kind of detection that's unreliable on
    // WebKitGTK. It's a small, cheap model, so doing this unconditionally
    // is fine.
    reinitSkinMiniPreview(true);
    BG.requestRedraw();
  }, 60);
}

// Debounced entry point for rebuilding the standee preview after its WebGL
// context is lost. Used both by the canvas's own 'webglcontextlost'
// listener (context can die at any point, not just around a tray restore)
// and by the tray-restore flow below. Debounced because a single restore
// can sometimes fire the loss event more than once in quick succession as
// the OS finishes settling the window — without this, that could kick off
// several overlapping rebuilds instead of one clean one.
let miniPreviewRecoverTimer = null;
function scheduleMiniPreviewRecover(delay = 300) {
  if (miniPreviewRecoverTimer) clearTimeout(miniPreviewRecoverTimer);
  miniPreviewRecoverTimer = setTimeout(() => {
    miniPreviewRecoverTimer = null;
    reinitSkinMiniPreview();
  }, delay);
}

// Swaps the standee's <canvas> element for a brand-new one with the same
// id — a true "unload" rather than just discarding the Render instance
// while reusing the same canvas/context. A cloned-and-replaced node has no
// history: no stuck context-lost flag, no listeners carried over from a
// context that half-recovered, nothing. Used for the tray-restore path,
// where "just recreate the Render object" alone wasn't enough to stop the
// model from going blank again shortly after coming back.
function hardResetSkinMiniPreviewCanvas() {
  const oldCanvas = document.getElementById('skin-mini-preview-canvas');
  if (!oldCanvas || !oldCanvas.parentNode) return;
  const freshCanvas = document.createElement('canvas');
  freshCanvas.id = 'skin-mini-preview-canvas';
  oldCanvas.parentNode.replaceChild(freshCanvas, oldCanvas);
}

// Hard fallback for `resumeSkinViewersAfterShow`: fully destroys and
// rebuilds the standee preview's renderer. Only reached if the WebGL
// context is still lost a couple of frames after the window comes back —
// i.e. it's not going to recover on its own.
// `hardReset` additionally throws away the <canvas> element itself (see
// `hardResetSkinMiniPreviewCanvas` above) instead of just recreating the
// Render object on the same node — used after a tray restore, where a
// same-canvas rebuild was observed to work for a moment and then go blank
// again.
function reinitSkinMiniPreview(hardReset = false) {
  const savedSkin = currentSkinSource;
  const savedModel = currentSkinModelType;
  try { skinMiniPreviewInstance?.dispose?.(); } catch (e) {}
  skinMiniPreviewInstance = null;
  if (skinMiniPreviewResizeObserver) {
    try { skinMiniPreviewResizeObserver.disconnect(); } catch (e) {}
    skinMiniPreviewResizeObserver = null;
  }
  if (hardReset) hardResetSkinMiniPreviewCanvas();
  initSkinMiniPreview().then(() => {
    if (savedSkin) loadSkinIntoViewer(savedSkin, savedModel);
  }).catch(() => {});
}

let skinMiniPreviewResizeRaf = null;
function resizeSkinMiniPreview() {
  if (!skinMiniPreviewInstance) return;
  if (skinMiniPreviewResizeRaf) cancelAnimationFrame(skinMiniPreviewResizeRaf);
  skinMiniPreviewResizeRaf = requestAnimationFrame(() => {
    const wrap = document.getElementById('skin-mini-preview-wrap');
    if (!wrap) return;
    const w = wrap.clientWidth;
    const h = wrap.clientHeight;
    if (w > 0 && h > 0 && skinMiniPreviewInstance) {
      skinMiniPreviewInstance.setSize(w, h);
    }
  });
}

async function loadEquippedCapeOnStandee(equipType) {
  // Capes/elytra are intentionally never shown on the 3D player model
  // preview — always keep the standee's back equipment cleared regardless
  // of what equip type was requested.
  if (!skinMiniPreviewInstance) return;
  try {
    await skinMiniPreviewInstance.loadCape(null);
  } catch (e) {}
}

async function updateSkinMiniPreview() {
  if (!skinMiniPreviewInstance) {
    initSkinMiniPreview();
    return;
  }
  try {
    const accounts = await api.getAccounts().catch(() => []);
    const active = accounts.find(a => a.is_active);

    // Load active account's individual skin settings
    loadSkinSettingsForAccount(active);

    const isOffline = !active || active.account_type === 'offline' || !active.mc_uuid;

    const useUnknownSkin = Boolean(currentSkinAnonSkin);
    const useUnknownTag = Boolean(currentSkinAnonTag);

    // Skin resolution:
    // 1) If anonymous skin is on -> unknownSkin placeholder
    // 2) If account has an equipped custom skin -> load local file via convertFileSrc
    // 3) Else if Microsoft account -> mineskin.eu/skin/{username}
    // 4) Fallback -> defaultOfflineSkin
    let skinUrl = unknownSkin;
    if (!useUnknownSkin) {
      if (currentSkinEquippedPath) {
        skinUrl = window.__TAURI__.core.convertFileSrc(currentSkinEquippedPath);
      } else if (active && active.username && !isOffline) {
        skinUrl = `https://mineskin.eu/skin/${encodeURIComponent(active.username)}`;
      } else if (active && active.username) {
        skinUrl = `https://mineskin.eu/skin/${encodeURIComponent(active.username)}`;
      } else {
        skinUrl = defaultOfflineSkin;
      }
    } else {
      skinUrl = unknownSkin;
    }

    // Nametag resolution: Check anonymous setting
    let displayName = 'Unknown';
    if (useUnknownTag) {
      displayName = 'Unknown';
    } else if (active && active.username) {
      displayName = active.username;
    } else {
      displayName = 'Unknown';
    }

    // Cleanly replace floating compact Minecraft-font NameTag
    try {
      if (skinMiniPreviewInstance.nameTag && skinMiniPreviewInstance.nameTag.parent) {
        skinMiniPreviewInstance.nameTag.parent.remove(skinMiniPreviewInstance.nameTag);
      }
    } catch (_) {}

    skinMiniPreviewInstance.nameTag = new NameTagObject(displayName, {
      font: '28px Minecraft, monospace',
      textStyle: '#ffffff',
      backgroundStyle: 'rgba(0, 0, 0, 0.65)',
      margin: [4, 8, 4, 8],
      height: 2.5,
      repaintAfterLoaded: true,
    });
    if (skinMiniPreviewInstance.nameTag) {
      skinMiniPreviewInstance.nameTag.position.y = 19.5;
    }

    // Force load skin
    currentMiniPreviewSkinUrl = skinUrl;
    try {
      await skinMiniPreviewInstance.loadSkin(skinUrl, { model: 'auto-detect' });
      // Auto cache account skin texture if remote
      if (!useUnknownSkin && !currentSkinEquippedPath && active && active.username && !isOffline) {
        api.cacheSkinTexture(active.username, `https://mineskin.eu/skin/${encodeURIComponent(active.username)}`).catch(() => {});
      }
    } catch (skinErr) {
      console.warn('Could not load skin, falling back to default:', skinErr);
      await skinMiniPreviewInstance.loadSkin(defaultOfflineSkin, { model: 'auto-detect' });
    }

    if (skinMiniPreviewInstance.playerWrapper) {
      skinMiniPreviewInstance.playerWrapper.position.y = -1.5;
    }

    // Always ensure animation is synced to active account's setting.
    // NOTE: assigning a new animation resets the player object's pose
    // (including rotation), so facing must be (re)applied *after* this —
    // setting it before was the reason Facing kept snapping back to
    // "Camera" whenever the preview reloaded (e.g. after hitting Apply).
    const anim = createSkinAnimation(currentSkinAnimName);
    if (anim) anim.speed = currentSkinSpeed;
    skinMiniPreviewInstance.animation = anim;
    skinMiniPreviewInstance.renderPaused = false;

    // Load equipped cape onto mini preview
    try {
      if (!useUnknownSkin && !isOffline && active && active.mc_uuid) {
        api.getAccountCapes(active.id).then(capes => {
          const activeCape = (capes || []).find(c => c.state === 'ACTIVE');
          if (activeCape && activeCape.url) {
            skinMiniPreviewInstance.loadCape(activeCape.url).catch(() => {});
          } else {
            skinMiniPreviewInstance.loadCape(null).catch(() => {});
          }
        }).catch(() => {
          skinMiniPreviewInstance.loadCape(null).catch(() => {});
        });
      } else {
        await skinMiniPreviewInstance.loadCape(null);
      }
    } catch (_) {}

    // Apply facing last, once skin/animation/cape are all settled, so
    // nothing downstream can reset the pose out from under it.
    if (skinMiniPreviewInstance.playerObject) {
      skinMiniPreviewInstance.playerObject.rotation.y = getFacingYaw(currentSkinFacing);
    }
  } catch (err) {
    console.warn('Could not update mini skin preview:', err);
  }
}

let draggedInstanceId = null;
let instanceListDelegationBound = false;

function initInstanceListDelegation() {
  if (instanceListDelegationBound) return;
  const list = document.getElementById('instance-list');
  if (!list) return;
  instanceListDelegationBound = true;

  // Single delegated click handler for all cards and action buttons
  list.addEventListener('click', (ev) => {
    const card = ev.target.closest('.instance-card');
    if (!card) return;
    const versionId = card.dataset.versionId;
    if (!versionId) return;

    const inst = instancesCache.find(i => i.version_id === versionId) || { version_id: versionId, name: versionId };

    // Troubleshoot button clicked
    const troubleshootBtn = ev.target.closest('.inst-troubleshoot-btn');
    if (troubleshootBtn) {
      ev.stopPropagation();
      showInstanceTroubleshootWindow(inst);
      return;
    }

    // Favorite / Pin button clicked
    const favoriteBtn = ev.target.closest('.inst-favorite-btn');
    if (favoriteBtn) {
      ev.stopPropagation();
      const currentlyFav = getFavoriteInstance() === versionId;
      setFavoriteInstance(currentlyFav ? null : versionId);
      renderInstanceList();
      showToast(currentlyFav
        ? `"${inst.name || versionId}" unpinned`
        : `"${inst.name || versionId}" pinned to top`, 'success');
      return;
    }

    // Hide button clicked
    const hideBtn = ev.target.closest('.inst-hide-btn');
    if (hideBtn) {
      ev.stopPropagation();
      const instName = inst.name || versionId;
      const doHide = async () => {
        try {
          await api.hideInstance(versionId);
          if (getFavoriteInstance() === versionId) setFavoriteInstance(null);
          await refreshInstances();
          if (selectedInstanceId === versionId) {
            selectedInstanceId = null;
            selectInstance(null);
          }
          renderInstanceList();
          renderHiddenInstancesSettings();
          showToast(`"${instName}" hidden — unhide it anytime in Settings (⚙) → Performance & Java`, 'success');
        } catch (e) {
          showToast('Failed to hide instance: ' + e, 'error');
        }
      };

      if (settings && settings.confirm_destructive_actions === false) {
        doHide();
      } else {
        showInstanceConfirmModal({
          type: 'hide',
          title: 'Hide Instance',
          message: `Hide "${instName}" from your instances list? You can unhide and manage it anytime in Settings (⚙) → Performance & Java → Hidden Instances.`,
          confirmText: 'Hide Instance',
          isDanger: false,
          onConfirm: doHide
        });
      }
      return;
    }

    // Card selected
    selectInstance(versionId);
  });

  // Delegated drag-and-drop
  list.addEventListener('dragstart', (ev) => {
    const card = ev.target.closest('.instance-card');
    if (!card || card.dataset.pinned === 'true') return;
    draggedInstanceId = card.dataset.versionId;
    card.classList.add('dragging');
    ev.dataTransfer.effectAllowed = 'move';
    try { ev.dataTransfer.setData('text/plain', draggedInstanceId); } catch (e) { /* ignore */ }
  });

  list.addEventListener('dragend', () => {
    draggedInstanceId = null;
    list.querySelectorAll('.instance-card.dragging, .instance-card.drag-over').forEach(el => {
      el.classList.remove('dragging', 'drag-over', 'drag-over-below');
    });
  });

  list.addEventListener('dragover', (ev) => {
    if (!draggedInstanceId) return;
    const card = ev.target.closest('.instance-card');
    if (!card || card.dataset.versionId === draggedInstanceId) return;
    ev.preventDefault();
    ev.dataTransfer.dropEffect = 'move';
    const rect = card.getBoundingClientRect();
    const before = (ev.clientY - rect.top) < rect.height / 2;
    list.querySelectorAll('.instance-card.drag-over').forEach(el => el.classList.remove('drag-over', 'drag-over-below'));
    card.classList.add('drag-over');
    card.classList.toggle('drag-over-below', !before);
  });

  list.addEventListener('drop', (ev) => {
    ev.preventDefault();
    const card = ev.target.closest('.instance-card');
    list.querySelectorAll('.instance-card.drag-over').forEach(el => el.classList.remove('drag-over', 'drag-over-below'));
    if (!card || !draggedInstanceId || card.dataset.versionId === draggedInstanceId) return;

    const rect = card.getBoundingClientRect();
    const before = (ev.clientY - rect.top) < rect.height / 2;
    const draggedCard = list.querySelector(`.instance-card[data-version-id="${CSS.escape(draggedInstanceId)}"]`);
    if (!draggedCard) return;

    if (before) {
      list.insertBefore(draggedCard, card);
    } else {
      list.insertBefore(draggedCard, card.nextSibling);
    }
    saveInstanceOrderFromDOM();
    renderInstanceList();
  });
}

function renderInstanceList() {
  const list = document.getElementById('instance-list');
  if (!list) return;
  initInstanceListDelegation();

  const instances = getVisibleInstances();
  if (instances.length === 0) {
    list.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_EMPTY_BOX_SVG}</span><span>No instances yet</span></div>`;
    return;
  }

  const favId = getFavoriteInstance();
  const fragment = document.createDocumentFragment();

  for (let i = 0; i < instances.length; i++) {
    const inst = instances[i];
    const card = document.createElement('div');
    const isFav = inst.version_id === favId;
    card.className = 'instance-card'
      + (inst.version_id === selectedInstanceId ? ' selected' : '')
      + (isFav ? ' favorited' : '');
    card.dataset.versionId = inst.version_id;
    card.dataset.pinned = isFav ? 'true' : 'false';
    card.draggable = !isFav;

    const loaderStr = loaderLabel(inst.loader);
    card.innerHTML = `
      <div class="inst-icon"><img src="${loaderIcon(inst.loader)}" alt="${loaderStr}" draggable="false" loading="lazy" /></div>
      <div class="inst-body">
        <div class="inst-header">
          <div class="inst-name">${inst.name || inst.version_id}${inst.missing_jar ? ' <span class="sv-warn">⚠</span>' : ''}</div>
          <div class="inst-actions">
            <button type="button" class="inst-hide-btn" title="Hide" aria-label="Hide ${inst.name || inst.version_id}">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                <circle cx="12" cy="12" r="3"></circle>
              </svg>
            </button>
            <button type="button" class="inst-troubleshoot-btn" title="Troubleshoot" aria-label="Troubleshoot ${inst.name || inst.version_id}">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L2 19v3h3l7.3-7.3a4 4 0 0 0 5.4-5.4l-2.8 2.8-2-2 2.8-2.8z"></path>
              </svg>
            </button>
            <button type="button" class="inst-favorite-btn${isFav ? ' active' : ''}" title="${isFav ? 'Unfavorite' : 'Favorite'}" aria-label="${isFav ? 'Unfavorite' : 'Favorite'} ${inst.name || inst.version_id}">
              <svg viewBox="0 0 24 24" fill="${isFav ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon>
              </svg>
            </button>
          </div>
        </div>
        <div class="inst-version">${inst.version_id}  •  ${loaderStr}</div>
      </div>
    `;
    fragment.appendChild(card);
  }

  list.replaceChildren(fragment);
  enableCardCulling(list, '.instance-card', { root: list });
}


function formatPlaytime(totalSecondsInput) {
  let totalSeconds = Math.max(0, Math.floor(totalSecondsInput || 0));
  const MINUTE = 60, HOUR = 3600, DAY = 86400, MONTH = 30 * DAY;
  const months = Math.floor(totalSeconds / MONTH); totalSeconds -= months * MONTH;
  const days = Math.floor(totalSeconds / DAY); totalSeconds -= days * DAY;
  const hours = Math.floor(totalSeconds / HOUR); totalSeconds -= hours * HOUR;
  const minutes = Math.floor(totalSeconds / MINUTE); totalSeconds -= minutes * MINUTE;
  const seconds = totalSeconds;
  const parts = [];
  if (months) parts.push(`${months}mo`);
  if (days) parts.push(`${days}d`);
  if (hours) parts.push(`${hours}h`);
  if (minutes) parts.push(`${minutes}m`);
  // Always show seconds unless there's already a coarser unit and it'd
  // just be visual noise on an otherwise-long duration.
  if (seconds || parts.length === 0) parts.push(`${seconds}s`);
  return parts.join(' ');
}

// Compact single-unit label for chart axis gridlines — "2h", "45m", "3d" —
// deliberately terser than formatPlaytime() (which lists every unit) since
// this has to fit next to a thin horizontal line without crowding it.
function formatPlaytimeAxis(totalSeconds) {
  const s = Math.max(0, Math.round(totalSeconds || 0));
  if (s >= 86400) return `${Math.round(s / 86400)}d`;
  if (s >= 3600) {
    const h = s / 3600;
    return `${h % 1 === 0 ? h : h.toFixed(1)}h`;
  }
  if (s >= 60) return `${Math.round(s / 60)}m`;
  return `${s}s`;
}

// Human-friendly "Last Played" label: relative for anything recent, a plain
// date once it's more than a week ago. `iso` is the RFC 3339 timestamp
// stored in `last_played_at` (set the moment Play is pressed).
function formatLastPlayed(iso) {
  if (!iso) return 'Never';
  const then = new Date(iso).getTime();
  if (isNaN(then)) return 'Never';
  const diffSec = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (diffSec < 60) return 'Just now';
  if (diffSec < 3600) { const m = Math.floor(diffSec / 60); return `${m} minute${m === 1 ? '' : 's'} ago`; }
  if (diffSec < 86400) { const h = Math.floor(diffSec / 3600); return `${h} hour${h === 1 ? '' : 's'} ago`; }
  const days = Math.floor(diffSec / 86400);
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days} days ago`;
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

// Recomputes and displays the selected instance's Play Time, including —
// if it's currently running — live elapsed time for the in-progress
// session on top of its already-accumulated total (which only updates on
// disk once that session actually ends).
function updateSelectedInstancePlaytimeDisplay() {
  const playtimeEl = document.getElementById('info-playtime');
  if (!playtimeEl || !selectedInstanceId) return;
  const inst = getInstances().find(i => i.version_id === selectedInstanceId);
  if (!inst) return;
  let seconds = inst.total_playtime_seconds || 0;
  const running = (runningInstancesCache || []).find(r => r.version_id === selectedInstanceId && r.running);
  if (running && running.started_at) {
    // started_at is "YYYY-MM-DD HH:MM:SS" in the launcher's local time.
    const startedMs = new Date(running.started_at.replace(' ', 'T')).getTime();
    if (!isNaN(startedMs)) {
      seconds += Math.max(0, Math.floor((Date.now() - startedMs) / 1000));
    }
  }
  playtimeEl.textContent = formatPlaytime(seconds) + (running ? ' (playing now)' : '');
}

// ── Play Time analytics panel ────────────────────────────────────────────
// Turns the selected instance's `playtime_history` ({ "YYYY-MM-DD": seconds })
// into ordered chart buckets for one of four ranges, then renders them as a
// small CSS bar chart with a couple of derived "fun facts" underneath.
let playtimeChartRange = 'week';

function ptDayKey(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function buildPlaytimeBuckets(history, range) {
  const hist = history || {};
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  if (range === 'week' || range === 'month') {
    const days = range === 'week' ? 7 : 30;
    const buckets = [];
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(d.getDate() - i);
      const key = ptDayKey(d);
      buckets.push({
        label: range === 'week' ? d.toLocaleDateString(undefined, { weekday: 'short' }) : String(d.getDate()),
        full: d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }),
        seconds: hist[key] || 0,
        isToday: i === 0,
      });
    }
    return buckets;
  }

  if (range === 'year') {
    const buckets = [];
    for (let i = 11; i >= 0; i--) {
      const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
      let seconds = 0;
      for (const key in hist) {
        const kd = new Date(key + 'T00:00:00');
        if (kd.getFullYear() === d.getFullYear() && kd.getMonth() === d.getMonth()) seconds += hist[key];
      }
      buckets.push({
        label: d.toLocaleDateString(undefined, { month: 'short' }),
        full: d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }),
        seconds,
        isToday: i === 0,
      });
    }
    return buckets;
  }

  // "All time" needs at least one instant to anchor on when there's no
  // history yet — fall back to the year view's last-12-months skeleton so
  // the chart always has real calendar labels, even before any data exists.
  if (Object.keys(hist).length === 0) return buildPlaytimeBuckets(hist, 'year');

  const years = {};
  for (const key in hist) years[key.slice(0, 4)] = (years[key.slice(0, 4)] || 0) + hist[key];
  const sortedYears = Object.keys(years).sort();
  if (sortedYears.length <= 1) return buildPlaytimeBuckets(history, 'year');
  return sortedYears.map(y => ({ label: y, full: y, seconds: years[y], isToday: false }));
}

// Tracks the instance-list "shape" (version ids, joined) a global mod count
// was last computed for, so switching the selected instance doesn't
// re-trigger a full re-scan of every instance's mods folder, but installing/
// removing an instance does.
let playtimeGlobalModsCountFor = null;
// Same shape-key caching as Mods Installed, but tracked separately since
// this one is also force-invalidated whenever a play session ends (see
// `api.onRunningInstancesChanged` below) — advancements can rack up
// mid-session in a way mod counts never do, so "the instance list hasn't
// changed" isn't a good enough reason to skip a recount here.
let playtimeGlobalAdvancementsCountFor = null;
// Game Advancements should only ever go up — a rescan finding fewer
// completed advancements than we've already seen almost always means the
// scan caught an instance mid-write, ran against an empty/just-refreshed
// instance list, or hit a directory that's temporarily unreadable, not
// that advancements were actually lost. This tracks the highest count
// we've ever confirmed (from stats.json on load, a live game-advancement
// tick, or a rescan) so nothing downstream is ever allowed to display or
// persist a lower number.
let advancementsFloor = 0;

// Left-side "Global Stats" list — deliberately independent of whichever
// instance is selected on the right; these four are summed across every
// installed instance. Total Launches and Total Play Time are free reads off
// data already in `instancesCache`; Mods Installed and Game Advancements
// each need one call per instance (listing the mods folder / scanning
// saves for completed advancements), so they're fetched once and cached
// until there's a real reason to recount.
//
// The panel is also mirrored to `<Zero Launcher folder>/stats.json` (see
// `api.loadGlobalStats`/`api.saveGlobalStats`) purely as a last-known-value
// cache: Total Launches/Total Play Time already live safely on each
// instance's own persisted record and Mods Installed/Game Advancements are
// always freshly rescanned from disk, so nothing here is the source of
// truth — but without the cache the panel visibly blanks out to "—"/"…"
// for a moment right after every launcher restart while those rescans are
// still in flight, which reads as the stats having been lost.
let globalStatsPersistedLoadAttempted = false;
function loadPersistedGlobalStatsOnce() {
  if (globalStatsPersistedLoadAttempted || !api.loadGlobalStats) return;
  globalStatsPersistedLoadAttempted = true;
  api.loadGlobalStats().then(stats => {
    if (!stats) return;
    const launchesEl = document.getElementById('playtime-stat-launches');
    const modsEl = document.getElementById('playtime-stat-mods');
    const totalEl = document.getElementById('playtime-stat-total');
    const advEl = document.getElementById('playtime-stat-advancements');
    const isPlaceholder = (el) => el && (el.textContent === '—' || el.textContent === '' || el.textContent === '…');
    // Only ever fills in a stat that hasn't already been given a real
    // value yet — this is a placeholder for the moment between startup
    // and the real recount finishing, never a replacement for it.
    if (isPlaceholder(launchesEl)) launchesEl.textContent = String(stats.total_launches || 0);
    if (isPlaceholder(totalEl)) {
      totalEl.textContent = stats.total_playtime_seconds > 0 ? formatPlaytime(stats.total_playtime_seconds) : '0s';
    }
    if (isPlaceholder(modsEl)) modsEl.textContent = String(stats.mods_installed || 0);
    advancementsFloor = Math.max(advancementsFloor, stats.game_advancements || 0);
    if (isPlaceholder(advEl)) advEl.textContent = String(advancementsFloor);
  }).catch(() => {});
}

// Writes the panel's current values out to stats.json. Skips saving while
// Mods Installed/Game Advancements are still mid-rescan (showing '…') so a
// transient placeholder never overwrites a good cached value with 0.
function persistGlobalStats() {
  if (!api.saveGlobalStats) return;
  const modsEl = document.getElementById('playtime-stat-mods');
  const advEl = document.getElementById('playtime-stat-advancements');
  if ((modsEl && modsEl.textContent === '…') || (advEl && advEl.textContent === '…')) return;
  const parseCount = (id) => {
    const el = document.getElementById(id);
    const n = el ? parseInt(el.textContent, 10) : NaN;
    return Number.isNaN(n) ? null : n;
  };
  const launches = parseCount('playtime-stat-launches');
  const mods = parseCount('playtime-stat-mods');
  const advancements = parseCount('playtime-stat-advancements');
  if (launches === null && mods === null && advancements === null) return;
  const totalSeconds = getInstances().reduce((s, i) => s + (i.total_playtime_seconds || 0), 0);
  api.saveGlobalStats({
    total_launches: launches ?? 0,
    total_playtime_seconds: totalSeconds,
    mods_installed: mods ?? 0,
    game_advancements: advancements ?? 0,
  }).catch(() => {});
}

function renderGlobalPlaytimeStats() {
  loadPersistedGlobalStatsOnce();
  const launchesEl = document.getElementById('playtime-stat-launches');
  const modsEl = document.getElementById('playtime-stat-mods');
  const totalEl = document.getElementById('playtime-stat-total');
  const advEl = document.getElementById('playtime-stat-advancements');
  const all = getInstances();

  if (launchesEl) {
    const totalLaunches = all.reduce((s, i) => s + (i.launch_count || 0), 0);
    launchesEl.textContent = String(totalLaunches);
  }
  if (totalEl) {
    const totalSeconds = all.reduce((s, i) => s + (i.total_playtime_seconds || 0), 0);
    totalEl.textContent = totalSeconds > 0 ? formatPlaytime(totalSeconds) : '0s';
  }
  if (modsEl) {
    const shapeKey = all.map(i => i.version_id).sort().join(',');
    if (all.length === 0) {
      modsEl.textContent = '0';
      playtimeGlobalModsCountFor = shapeKey;
      persistGlobalStats();
    } else if (playtimeGlobalModsCountFor !== shapeKey) {
      playtimeGlobalModsCountFor = shapeKey;
      modsEl.textContent = '…';
      Promise.all(all.map(i => api.listMods(i.directory).catch(() => [])))
        .then(lists => {
          // Only apply if the instance list hasn't changed shape again
          // while these were in flight.
          if (playtimeGlobalModsCountFor === shapeKey) {
            const total = lists.reduce((s, mods) => s + (mods || []).length, 0);
            modsEl.textContent = String(total);
            persistGlobalStats();
          }
        })
        .catch(() => {
          if (playtimeGlobalModsCountFor === shapeKey) modsEl.textContent = '—';
        });
    }
  }
  if (advEl) {
    // Includes the running-instances-changed generation counter (bumped
    // any time a session finishes — see below) so a session ending forces
    // a recount even though the instance list itself didn't change shape.
    const shapeKey = all.map(i => i.version_id).sort().join(',') + '|' + playtimeAdvancementsGeneration;
    if (all.length === 0) {
      // No instances currently loaded — this fires both for a genuinely
      // empty launcher AND for the brief window before refreshInstances()
      // has resolved, and those two cases are indistinguishable here. Never
      // trust it enough to drop a known-higher count to 0: show/persist the
      // floor instead, which is '0' for a truly fresh install anyway.
      advEl.textContent = String(advancementsFloor);
      playtimeGlobalAdvancementsCountFor = shapeKey;
      persistGlobalStats();
    } else if (playtimeGlobalAdvancementsCountFor !== shapeKey) {
      playtimeGlobalAdvancementsCountFor = shapeKey;
      advEl.textContent = '…';
      Promise.all(all.map(i => api.countAdvancements(i.directory).catch(() => 0)))
        .then(counts => {
          if (playtimeGlobalAdvancementsCountFor === shapeKey) {
            const total = counts.reduce((s, c) => s + (c || 0), 0);
            advancementsFloor = Math.max(advancementsFloor, total);
            advEl.textContent = String(advancementsFloor);
            persistGlobalStats();
          }
        })
        .catch(() => {
          if (playtimeGlobalAdvancementsCountFor === shapeKey) advEl.textContent = '—';
        });
    }
  }
}

// Bumped every time a play session ends (the pid watcher/process-exit path
// that's already tracking playtime for us), so the Game Advancements stat
// gets recounted after every session instead of only when instances are
// installed/removed.
let playtimeAdvancementsGeneration = 0;

function renderPlaytimeChart() {
  const chartEl = document.getElementById('playtime-chart');
  const totalEl = document.getElementById('playtime-range-total');
  const streakEl = document.getElementById('playtime-streak');
  const bestDayEl = document.getElementById('playtime-best-day');
  const avgEl = document.getElementById('playtime-daily-avg');
  if (!chartEl) return;

  renderGlobalPlaytimeStats();

  const inst = getInstances().find(i => i.version_id === selectedInstanceId);
  const history = inst ? (inst.playtime_history || {}) : {};
  const hasAnyData = Object.keys(history).some(k => history[k] > 0);

  // Always build real, dated buckets — even with zero seconds everywhere —
  // so the chart shows an actual calendar grid (this week's day names, this
  // year's months, etc.) instead of just going blank when there's no data.
  const buckets = buildPlaytimeBuckets(history, playtimeChartRange);
  const rangeTotal = buckets.reduce((s, b) => s + b.seconds, 0);

  if (totalEl) totalEl.textContent = hasAnyData ? `${formatPlaytime(rangeTotal)} in range` : 'Nothing played yet';

  // Current streak: consecutive played days ending today (or yesterday, so
  // a streak doesn't reset to zero the moment you haven't opened it yet
  // today).
  if (streakEl) {
    if (!hasAnyData) {
      streakEl.textContent = '0 days';
    } else {
      let streak = 0;
      const d = new Date();
      d.setHours(0, 0, 0, 0);
      if (!history[ptDayKey(d)]) d.setDate(d.getDate() - 1);
      while (history[ptDayKey(d)]) {
        streak++;
        d.setDate(d.getDate() - 1);
      }
      streakEl.textContent = streak > 0 ? `${streak} day${streak === 1 ? '' : 's'}` : '0 days';
    }
  }

  // Best single day across the instance's whole history (not just the
  // selected range) — a nice constant to compare the current range against.
  if (bestDayEl) {
    if (!hasAnyData) {
      bestDayEl.textContent = 'None yet';
    } else {
      const bestSecs = Math.max(0, ...Object.values(history));
      bestDayEl.textContent = bestSecs > 0 ? formatPlaytime(bestSecs) : 'None yet';
    }
  }

  // Average per *active* day in the selected range, so a chart with a lot
  // of zero days doesn't make the average look artificially tiny.
  if (avgEl) {
    const activeDays = buckets.filter(b => b.seconds > 0).length;
    avgEl.textContent = activeDays > 0 ? formatPlaytime(rangeTotal / activeDays) : '—';
  }

  // Render the bar grid unconditionally — real dated columns with empty
  // (near-zero) tracks when a bucket has no play time — then, only if the
  // *whole* instance has never been played, lay a small hint over the top
  // instead of hiding the chart.
  // Round the axis ceiling up to a "nice" number of hours/minutes so the
  // gridlines read as real time markers instead of an arbitrary fraction of
  // whatever the tallest bar happens to be — this is most of what made the
  // chart feel like empty air with one bar floating in it.
  const rawMax = Math.max(...buckets.map(b => b.seconds), 1);
  const niceSteps = [300, 600, 900, 1800, 3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600, 24 * 3600, 2 * 86400, 5 * 86400, 10 * 86400, 30 * 86400];
  let axisMax = niceSteps.find(s => s * 4 >= rawMax) || rawMax * 1.2;
  axisMax = Math.max(axisMax, rawMax * 1.05, 60);
  const maxSeconds = axisMax;
  const n = buckets.length;
  // Thin out labels once there'd be too many to read (month/all-time views).
  const labelEvery = n <= 12 ? 1 : Math.ceil(n / 10);

  const wrap = document.createElement('div');
  wrap.className = 'playtime-chart-inner';

  // Horizontal gridlines with time labels (0%, 25%, 50%, 75%, 100% of the
  // "nice" axis max) — gives the chart a real sense of scale instead of a
  // few bars floating in a mostly-empty box.
  const grid = document.createElement('div');
  grid.className = 'playtime-grid';
  [1, 0.75, 0.5, 0.25, 0].forEach(frac => {
    const line = document.createElement('div');
    line.className = 'playtime-grid-line' + (frac === 0 ? ' baseline' : '');
    const label = document.createElement('span');
    label.className = 'playtime-grid-label';
    label.textContent = frac === 0 ? '0m' : formatPlaytimeAxis(maxSeconds * frac);
    line.appendChild(label);
    grid.appendChild(line);
  });
  wrap.appendChild(grid);

  const bars = document.createElement('div');
  bars.className = 'playtime-bars';
  buckets.forEach((b, i) => {
    const col = document.createElement('div');
    col.className = 'playtime-bar-col' + (b.isToday ? ' today' : '');
    col.title = `${b.full}: ${b.seconds > 0 ? formatPlaytime(b.seconds) : 'No play time'}`;

    const track = document.createElement('div');
    track.className = 'playtime-bar-track';
    const fill = document.createElement('div');
    fill.className = 'playtime-bar-fill' + (b.seconds > 0 ? '' : ' empty');
    // A hairline-height "stub" for empty days keeps every column visible
    // as part of the grid, rather than disappearing entirely.
    const pct = b.seconds > 0 ? Math.max(4, (b.seconds / maxSeconds) * 100) : 16;
    fill.style.height = pct + '%';
    track.appendChild(fill);
    col.appendChild(track);

    const label = document.createElement('span');
    label.className = 'playtime-bar-label';
    label.textContent = (i % labelEvery === 0) ? b.label : '';
    col.appendChild(label);

    bars.appendChild(col);
  });
  wrap.appendChild(bars);

  if (!hasAnyData) {
    const hint = document.createElement('div');
    hint.className = 'playtime-empty-hint';
    hint.innerHTML = '<span class="hero-empty-label">No play sessions yet — hit ▶ Play to start tracking</span>';
    wrap.appendChild(hint);
  }

  chartEl.replaceChildren(wrap);
}

function initPlaytimeRangeButtons() {
  const buttons = document.querySelectorAll('.playtime-range-btn');
  buttons.forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.classList.contains('active')) return;
      buttons.forEach(b => { b.classList.remove('active'); b.setAttribute('aria-selected', 'false'); });
      btn.classList.add('active');
      btn.setAttribute('aria-selected', 'true');
      playtimeChartRange = btn.dataset.range;
      renderPlaytimeChart();
    });
  });
}
initPlaytimeRangeButtons();

// ── "Make it smart" activity tracking ────────────────────────────────────
// Pings the backend at most once every few seconds while the user is doing
// anything in the launcher window (mouse movement, clicks, scrolling,
// typing). The launch flow reads this timestamp to decide whether the
// window has actually gone idle before closing it — see Settings →
// Window Behavior → "Close launcher when game starts" → "Make it smart",
// and `commands::minecraft::launch_minecraft` on the Rust side.
(function initActivityTracking() {
  const THROTTLE_MS = 4000;
  let lastSent = 0;
  const ping = () => {
    const now = Date.now();
    if (now - lastSent < THROTTLE_MS) return;
    lastSent = now;
    api.reportActivity().catch(() => {});
  };
  ['mousemove', 'mousedown', 'keydown', 'wheel', 'touchstart'].forEach(evt => {
    window.addEventListener(evt, ping, { passive: true, capture: true });
  });
  // Count as activity right away so a freshly opened launcher doesn't read
  // as having been idle since process start.
  ping();
})();

function updatePlayGearEnabled() {
  const gearBtn = document.getElementById('btn-play-options');
  const playBtn = document.getElementById('btn-play');
  if (gearBtn && playBtn) gearBtn.disabled = playBtn.disabled;
}

setInterval(updateSelectedInstancePlaytimeDisplay, 1000);

function selectInstance(id) {
  selectedInstanceId = id;

  // Update selected highlight in place without re-rendering or reloading DOM/images (fixes flicker)
  const list = document.getElementById('instance-list');
  if (list) {
    const cards = list.querySelectorAll('.instance-card');
    for (let i = 0; i < cards.length; i++) {
      cards[i].classList.toggle('selected', cards[i].dataset.versionId === id);
    }
  }

  const inst = getInstances().find(i => i.version_id === id);
  const nameEl = document.getElementById('detail-name');
  const verEl = document.getElementById('detail-version');
  const gameVerEl = document.getElementById('info-game-version');
  const loaderEl = document.getElementById('info-loader');
  const dirEl = document.getElementById('info-dir');
  const playtimeEl = document.getElementById('info-playtime');
  const lastPlayedEl = document.getElementById('info-last-played');
  const playBtn = document.getElementById('btn-play');
  const iconEl = document.getElementById('detail-icon');

  if (!inst) {
    nameEl.textContent = 'No instance selected';
    verEl.textContent = '';
    if (gameVerEl) gameVerEl.textContent = '—';
    loaderEl.textContent = '—';
    dirEl.textContent = '—';
    if (playtimeEl) playtimeEl.textContent = '—';
    if (lastPlayedEl) lastPlayedEl.textContent = '—';
    playBtn.disabled = true;
    updatePlayGearEnabled();
    if (iconEl) iconEl.innerHTML = '';
    document.getElementById('play-status-text')?.classList.add('hidden');
    renderPlaytimeChart();
    syncInstanceSelectionAcrossTabs().catch(() => {});
    return;
  }

  const loaderStr = (inst.loader && inst.loader !== 'vanilla') ? loaderLabel(inst.loader) : null;
  nameEl.textContent = (inst.name || inst.version_id) + (inst.missing_jar ? ' (incomplete)' : '');
  verEl.textContent = inst.version_id + (loaderStr ? '  •  ' + loaderStr : '');
  if (gameVerEl) gameVerEl.textContent = inst.minecraft_version || inst.version_id || '—';
  loaderEl.textContent = loaderStr || 'Vanilla';
  dirEl.textContent = inst.directory || (settings ? settings.game_directory : '—');
  if (lastPlayedEl) lastPlayedEl.textContent = formatLastPlayed(inst.last_played_at);
  updateSelectedInstancePlaytimeDisplay();
  renderPlaytimeChart();
  playBtn.disabled = !!inst.missing_jar;
  updatePlayGearEnabled();
  document.getElementById('play-status-text')?.classList.add('hidden');

  if (iconEl) {
    const newSrc = loaderIcon(inst.loader);
    const existingImg = iconEl.querySelector('img');
    if (existingImg && existingImg.getAttribute('src') === newSrc) {
      // Icon already matches — do not replace DOM to prevent image flicker
    } else {
      iconEl.innerHTML = `<img src="${newSrc}" alt="${loaderLabel(inst.loader)}" draggable="false" />`;
    }
  }

  // Refresh mod-update data for whatever instance is now selected
  modUpdateInfo = modUpdateInfoByDir.get(modsCacheKey(inst, inst.directory || (settings ? settings.game_directory : ''))) || new Map();
  refreshUpdateButtonsOnVisibleCards();

  syncInstanceSelectionAcrossTabs()
    .catch(() => {})
    .then(() => checkSelectedInstanceForUpdates())
    .catch(e => console.error('Update check failed', e));
}

function getActiveTabId() {
  const active = document.querySelector('.pill-tab.active');
  return active ? active.dataset.tab : null;
}

function getModsTargetInstance() {
  const sel = document.getElementById('mods-instance-select');
  const selectedId = sel && sel.value ? sel.value : selectedInstanceId;
  return selectedId ? getInstances().find(i => i.version_id === selectedId) : null;
}

function getModsTargetDirectory() {
  const inst = getModsTargetInstance();
  if (inst && inst.directory) return inst.directory;
  return settings ? settings.game_directory : '';
}

function selectOptionIfAvailable(select, value) {
  if (!select || !value) return;
  const option = Array.from(select.options).find(o => o.value === value);
  if (option) select.value = value;
}

async function syncInstanceSelectionAcrossTabs() {
  await loadModInstances();
  populateDiscoverInstanceSelect();
  if (getActiveTabId() === 'mods') {
    await loadMods();
  }
  if (getActiveTabId() === 'discover' && discoverState.loaded) {
    // Read the Discover tab's own dropdown back (rather than recomputing
    // from `selectedInstanceId` directly) now that it always follows the
    // main selection — keeps this in sync with whatever `initDiscover`'s
    // own change handler considers the current target.
    discoverState.syncedInstanceId = selectedInstanceId;
    applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
    performDiscoverSearch();
  }
  if (getActiveTabId() === 'presets' && presetsState.loaded) {
    presetsState.syncedInstanceId = selectedInstanceId;
    populatePresetsInstanceSelect();
    renderPresets();
  }
}

// Instance data (mods/worlds/saves/etc) lives at inst.directory, separate
// from the shared versions/libraries/assets in inst.minecraft_directory —
// see InstalledInstance in models.rs. Deleting it is only ever safe when
// that directory isn't also the shared default .minecraft folder, which
// is true for every default-location instance and any instance saved
// before the directory split existed (empty minecraft_directory).
function instanceDataDeletionIsUnsafe(inst) {
  if (!inst) return true;
  const ownDir = (inst.directory || '').trim();
  if (!ownDir) return true;
  const sharedDir = (inst.minecraft_directory || (settings && settings.game_directory) || '').trim();
  if (!sharedDir) return true;
  return ownDir === sharedDir;
}

// Actually deletes an instance's files + tracked entry (shared by the plain
// confirm() path and the "Delete Anyway" button on the vanilla-dependency
// warning). `deleteData` additionally wipes the instance's own data folder
// (mods/worlds/saves/config/screenshots) — always false unless the user
// explicitly opted in via the confirm modal's toggle.
async function performInstanceDelete(versionId, inst, deleteData) {
  try {
    if (deleteData && inst && !instanceDataDeletionIsUnsafe(inst)) {
      try {
        await api.deleteInstanceData(inst.directory, inst.minecraft_directory);
      } catch (e) {
        console.error('Failed to delete instance data folder:', e);
        showToast('Instance deleted, but its data folder could not be removed: ' + e, 'error');
      }
    }
    await api.deleteInstalledVersion(versionId, inst && inst.directory);
    await refreshInstances();
    if (selectedInstanceId === versionId) {
      selectedInstanceId = null;
      selectInstance(null);
    }
    renderInstanceList();
    showToast('Instance deleted', 'success');
  } catch (e) {
    showToast('Failed to delete instance: ' + e, 'error');
  }
}

// Shows the "other instances need this vanilla instance" warning, listing
// every dependent modded instance, with Cancel / Delete Anyway / Hide.
function showVanillaDeleteWarning(inst, dependents) {
  const overlay = document.getElementById('vanilla-delete-warning-overlay');
  const intro = document.getElementById('vanilla-delete-warning-intro');
  const list = document.getElementById('vanilla-delete-warning-list');
  const name = (inst && (inst.name || inst.version_id)) || 'This instance';

  overlay.dataset.targetId = (inst && inst.version_id) || '';
  intro.textContent = `"${name}" is a vanilla instance that the following instance${dependents.length === 1 ? '' : 's'} need${dependents.length === 1 ? 's' : ''} to run:`;
  list.innerHTML = '';
  dependents.forEach(d => {
    const li = document.createElement('li');
    li.textContent = `${d.name || d.version_id} (${d.minecraft_version} • ${loaderLabel(d.loader)})`;
    list.appendChild(li);
  });
  overlay.classList.remove('hidden');
}

// Shows the crash/troubleshoot window when a launch fails. Right now this
// fires for any launch error, but it's especially aimed at the classic
// "Failed to load version: io error: No such file or directory" case,
// which almost always means the instance's version files are missing or
// corrupt and a reinstall will fix it.
function showCrashTroubleshootWindow(inst, error) {
  const overlay = document.getElementById('crash-troubleshoot-overlay');
  if (!overlay) return;
  const nameEl = document.getElementById('crash-troubleshoot-instance-name');
  const detailsEl = document.getElementById('crash-troubleshoot-details');
  const name = (inst && (inst.name || inst.version_id)) || 'This instance';

  nameEl.textContent = `"${name}"`;
  detailsEl.textContent = String(error);
  overlay.dataset.targetId = (inst && inst.version_id) || '';
  overlay.classList.remove('hidden');
}

// Reinstalls the instance shown in the crash/troubleshoot window using the
// same version/loader it already has, then reselects it once done.
async function troubleshootReinstallInstance(id) {
  const inst = getInstances().find(i => i.version_id === id);
  if (!inst) {
    showToast('Could not find that instance to reinstall', 'error');
    return;
  }
  const zlibOk = await confirmZlibIfConflict(inst.loader);
  if (!zlibOk) return;
  showToast(`Reinstalling ${inst.name || inst.version_id}…`, 'info');
  if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, inst.minecraft_version, inst.loader);
  try {
    const newInstance = await api.installVersion(
      inst.minecraft_version,
      inst.loader || 'vanilla',
      inst.loader_version || 'latest',
      inst.directory,
      inst.name
    );
    if (newInstance.version_id !== inst.version_id) {
      try {
        await api.deleteInstalledVersion(inst.version_id, inst.directory);
      } catch (e) {
        console.warn('Could not clean up old instance version:', e);
      }
    }
    await refreshInstances();
    renderInstanceList();
    selectInstance(newInstance.version_id);
    showToast('Instance reinstalled — try launching it again', 'success');
  } catch (e) {
    if (dlWidgetGeneric) dlWidgetGeneric.failInstanceInstall(INSTANCE_INSTALL_CARD_ID, String(e));
    showToast('Reinstall failed: ' + e, 'error');
  }
}

function initCrashTroubleshootWindow() {
  const overlay = document.getElementById('crash-troubleshoot-overlay');
  if (!overlay) return;
  const close = () => overlay.classList.add('hidden');
  document.getElementById('btn-close-crash-troubleshoot').addEventListener('click', close);
  document.getElementById('btn-crash-troubleshoot-no').addEventListener('click', close);
  document.getElementById('btn-crash-troubleshoot-yes').addEventListener('click', async () => {
    const id = overlay.dataset.targetId;
    close();
    if (id) await troubleshootReinstallInstance(id);
  });
}

// Shows the per-instance troubleshoot window opened via the 🔧 button on
// an instance card. Currently offers a single option: reinstall this
// instance's Minecraft version (with its loader/loader version).
function showInstanceTroubleshootWindow(inst) {
  const overlay = document.getElementById('instance-troubleshoot-overlay');
  if (!overlay) return;
  const nameEl = document.getElementById('instance-troubleshoot-name');
  nameEl.textContent = (inst && (inst.name || inst.version_id)) || '';
  overlay.dataset.targetId = (inst && inst.version_id) || '';
  overlay.classList.remove('hidden');
}

function initInstanceTroubleshootWindow() {
  const overlay = document.getElementById('instance-troubleshoot-overlay');
  if (!overlay) return;
  const close = () => overlay.classList.add('hidden');
  document.getElementById('btn-close-instance-troubleshoot').addEventListener('click', close);
  document.getElementById('btn-instance-troubleshoot-reinstall').addEventListener('click', async () => {
    const id = overlay.dataset.targetId;
    close();
    if (id) await troubleshootReinstallInstance(id);
  });
}

function initInstanceActions() {
  // Show Skin (optional fallback if present)
  document.getElementById('btn-show-skin')?.addEventListener('click', () => {
    openSkinViewerModal();
  });

  // Play
  async function launchSelectedInstance(offlineOverride) {
    if (!selectedInstanceId) return;
    const btn = document.getElementById('btn-play');
    btn.disabled = true;
    updatePlayGearEnabled();
    btn.dataset.launching = '1';
    btn.innerHTML = `<svg class="btn-play-hourglass" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M6 2h12"></path>
      <path d="M6 22h12"></path>
      <path d="M6 2c0 4.5 5.5 5.5 5.5 8s-5.5 3.5-5.5 8"></path>
      <path d="M18 2c0 4.5-5.5 5.5-5.5 8s5.5 3.5 5.5 8"></path>
    </svg> LAUNCHING…`;
    const inst = getInstances().find(i => i.version_id === selectedInstanceId);

    // If a launch doesn't resolve within 5s (usually a broken/corrupt
    // instance hanging on startup), stop waiting and let the user retry or
    // troubleshoot instead of leaving the button stuck on "LAUNCHING…"
    // forever. The real launch call keeps running in the background; if it
    // eventually succeeds after we've already timed out, we still pick that
    // up and refresh the running-instances list.
    //
    // Exception: the very first launch of a Minecraft version often needs
    // Smart Java Detection to download a matching JDK first, which can take
    // well over 5 seconds on a slow connection. While a Java install is
    // actively in progress (tracked via the `java-install-progress` events)
    // we keep extending the wait instead of declaring the launch broken —
    // otherwise the UI would "cancel" and show the crash-troubleshoot
    // window in the middle of a perfectly normal Java download.
    let timedOut = false;
    try {
      if (inst) {
        api.updateDiscordPresence('Instances', inst.name || inst.version_id, inst.minecraft_version).catch(() => { });
      }
      // If the user wants eyes on the log from the very first click, pop
      // the console open now — before we even know if the launch will
      // succeed — so slow startups (Java download, asset verification,
      // etc.) are visible the whole way through instead of just showing
      // "LAUNCHING…" with no detail.
      if (settings && settings.auto_open_console_on_launch) {
        openInstanceConsole(selectedInstanceId, inst && (inst.name || inst.version_id));
      }

      // `undefined` here means "no explicit per-launch choice" — the
      // backend falls back to the saved "Always Launch Offline" setting.
      const launchPromise = api.launchGame(selectedInstanceId, offlineOverride);
      launchPromise.then(() => {
        if (timedOut) {
          showToast(`"${(inst && (inst.name || inst.version_id)) || selectedInstanceId}" launched (after a delay)`, 'success');
          refreshRunningInstances();
        }
      }).catch(() => { });

      const TIMEOUT_MS = 20000;
      const timeoutPromise = new Promise((_, reject) => {
        const check = () => {
          setTimeout(() => {
            if (javaInstallInProgress || launchVerifyInProgress) {
              // Still downloading/extracting Java, or still checking/
              // repairing libraries & assets — don't give up, just keep
              // waiting and re-check shortly. The 20s window itself only
              // starts being "spent" once both of these clear.
              check();
              return;
            }
            timedOut = true;
            reject(new Error('Launch timed out after 20 seconds'));
          }, TIMEOUT_MS);
        };
        check();
      });

      await Promise.race([launchPromise, timeoutPromise]);
      showToast('Game launched!', 'success');
    } catch (e) {
      if (timedOut) {
        showToast('Launch timed out — the instance may be slow to start or broken', 'error');
        showCrashTroubleshootWindow(inst, 'Launch timed out after 20 seconds. This usually means the instance is broken or missing files.');
      } else {
        const errStr = String(e || '');
        if (errStr.includes('xrandr') || errStr.includes('xorg-xrandr')) {
          showXrandrWarningModal(() => {
            document.getElementById('btn-play')?.click();
          });
        } else {
          showToast('Launch failed: ' + e, 'error');
          showCrashTroubleshootWindow(inst, e);
        }
      }
    }
    btn.disabled = false;
    updatePlayGearEnabled();
    delete btn.dataset.launching;
    await refreshRunningInstances();
  }

  window.launchSelectedInstance = launchSelectedInstance;
  document.getElementById('btn-play').addEventListener('click', () => launchSelectedInstance(undefined));

  // Gear menu: Launch Offline (one-off) + Always Launch Offline (persisted).
  const gearBtn = document.getElementById('btn-play-options');
  const popover = document.getElementById('play-options-popover');
  const alwaysOfflineChk = document.getElementById('chk-always-offline');

  function closePlayOptionsPopover() {
    popover.classList.add('hidden');
    gearBtn.classList.remove('is-open');
    gearBtn.setAttribute('aria-expanded', 'false');
  }
  function openPlayOptionsPopover() {
    if (alwaysOfflineChk) alwaysOfflineChk.checked = !!(settings && settings.always_launch_offline);
    popover.classList.remove('hidden');
    gearBtn.classList.add('is-open');
    gearBtn.setAttribute('aria-expanded', 'true');
  }

  gearBtn?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (gearBtn.disabled) return;
    if (popover.classList.contains('hidden')) openPlayOptionsPopover();
    else closePlayOptionsPopover();
  });
  document.addEventListener('click', (e) => {
    if (!popover || popover.classList.contains('hidden')) return;
    if (!popover.contains(e.target) && e.target !== gearBtn) closePlayOptionsPopover();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closePlayOptionsPopover();
  });

  document.getElementById('btn-launch-offline')?.addEventListener('click', () => {
    closePlayOptionsPopover();
    launchSelectedInstance(true);
  });

  alwaysOfflineChk?.addEventListener('change', () => {
    if (!settings) return;
    settings.always_launch_offline = alwaysOfflineChk.checked;
    saveSettingsNow();
  });

  // Delete
  document.getElementById('btn-delete-instance').addEventListener('click', async () => {
    if (!selectedInstanceId) return;
    const inst = getInstances().find(i => i.version_id === selectedInstanceId);
    const isVanilla = !inst || !inst.loader || inst.loader.toLowerCase() === 'vanilla';

    if (isVanilla) {
      let dependents = [];
      try {
        dependents = await api.getDependentInstances(selectedInstanceId);
      } catch (e) {
        console.error('Failed to check dependent instances:', e);
      }
      if (dependents.length > 0) {
        showVanillaDeleteWarning(inst, dependents);
        return;
      }
    }

    const instName = (inst && (inst.name || inst.version_id)) || selectedInstanceId;
    const doDelete = async (deleteData) => {
      await performInstanceDelete(selectedInstanceId, inst, deleteData);
    };

    if (settings && settings.confirm_destructive_actions === false) {
      // Skip-confirmation users never see the toggle, so this path never
      // touches instance data — only ever the version folder.
      await doDelete(false);
    } else {
      const dataUnsafe = instanceDataDeletionIsUnsafe(inst);
      showInstanceConfirmModal({
        type: 'delete',
        title: 'Delete Instance',
        message: `Delete "${instName}"? This permanently removes its folder from .minecraft/versions and cannot be undone.`,
        confirmText: 'Delete Instance',
        isDanger: true,
        dataToggle: {
          show: true,
          disabled: dataUnsafe,
          disabledReason: "Not available — this instance stores its data in your main .minecraft folder, shared with other instances.",
        },
        onConfirm: doDelete
      });
    }
  });

  // Vanilla-instance delete dependency warning: Cancel / Delete Anyway / Hide
  const vanillaWarningOverlay = document.getElementById('vanilla-delete-warning-overlay');
  const closeVanillaWarning = () => vanillaWarningOverlay.classList.add('hidden');
  document.getElementById('btn-close-vanilla-delete-warning').addEventListener('click', closeVanillaWarning);
  document.getElementById('btn-vanilla-delete-cancel').addEventListener('click', closeVanillaWarning);

  document.getElementById('btn-vanilla-delete-anyway').addEventListener('click', async () => {
    const id = vanillaWarningOverlay.dataset.targetId;
    const inst = getInstances().find(i => i.version_id === id);
    closeVanillaWarning();
    if (!id) return;
    await performInstanceDelete(id, inst);
  });

  document.getElementById('btn-vanilla-delete-hide').addEventListener('click', async () => {
    const id = vanillaWarningOverlay.dataset.targetId;
    const inst = getInstances().find(i => i.version_id === id);
    closeVanillaWarning();
    if (!id) return;
    try {
      await api.hideInstance(id);
      await refreshInstances();
      if (selectedInstanceId === id) {
        selectedInstanceId = null;
        selectInstance(null);
      }
      renderInstanceList();
      renderHiddenInstancesSettings();
      showToast(`"${(inst && (inst.name || inst.version_id)) || id}" hidden — unhide it anytime from Settings`, 'success');
    } catch (e) {
      showToast('Failed to hide instance: ' + e, 'error');
    }
  });

  // New Instance overlay (Hyprland Floating Islands)
  const overlay = document.getElementById('new-instance-overlay');
  const instLoaderInput = document.getElementById('inst-loader');
  const instLoaderVersionTile = document.getElementById('inst-loader-version-tile');
  const btnToggleNewAdvanced = document.getElementById('btn-toggle-new-advanced');
  const newInstAdvancedIsland = document.getElementById('new-inst-advanced-island');
  const instJavaSelect = document.getElementById('inst-java-select');
  const instMinRamInput = document.getElementById('inst-min-ram');
  const instMaxRamInput = document.getElementById('inst-max-ram');
  const instJvmArgsInput = document.getElementById('inst-jvm-args');
  const instNameInput = document.getElementById('inst-name');
  const instLoaderVersionInput = document.getElementById('inst-loader-version');

  // Initialize loader button icons for New Instance
  const newLoaderImgVanilla = document.getElementById('new-loader-img-vanilla');
  if (newLoaderImgVanilla) newLoaderImgVanilla.src = iconVanilla;
  const newLoaderImgFabric = document.getElementById('new-loader-img-fabric');
  if (newLoaderImgFabric) newLoaderImgFabric.src = iconFabric;
  const newLoaderImgForge = document.getElementById('new-loader-img-forge');
  if (newLoaderImgForge) newLoaderImgForge.src = iconForge;
  const newLoaderImgNeoforge = document.getElementById('new-loader-img-neoforge');
  if (newLoaderImgNeoforge) newLoaderImgNeoforge.src = iconNeoforge;
  const newLoaderImgQuilt = document.getElementById('new-loader-img-quilt');
  if (newLoaderImgQuilt) newLoaderImgQuilt.src = iconQuilt;

  function setNewInstanceLoader(loader) {
    const norm = (loader || 'Fabric').trim();
    if (instLoaderInput) instLoaderInput.value = norm;

    const btns = document.querySelectorAll('#new-inst-loader-buttons .hypr-loader-btn');
    btns.forEach(btn => {
      const match = btn.getAttribute('data-loader').toLowerCase() === norm.toLowerCase();
      btn.classList.toggle('active', match);
    });

    const isVanilla = norm.toLowerCase() === 'vanilla';
    if (instLoaderVersionTile) {
      instLoaderVersionTile.classList.toggle('hidden', isVanilla);
    }
  }

  const newLoaderBtns = document.querySelectorAll('#new-inst-loader-buttons .hypr-loader-btn');
  newLoaderBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const loader = btn.getAttribute('data-loader');
      setNewInstanceLoader(loader);
    });
  });

  async function populateNewJavaDropdown() {
    if (!instJavaSelect) return;
    instJavaSelect.innerHTML = `
      <option value="">Use Settings (Default)</option>
      <option value="__smart__">✦ Smart Java Detection (Auto)</option>
    `;
    let installs = _lastJavaInstallations;
    if (!installs || installs.length === 0) {
      try {
        installs = await api.listJavaInstallations();
        _lastJavaInstallations = installs || [];
      } catch (_) {
        installs = [];
      }
    }
    (installs || []).forEach(inst => {
      const opt = document.createElement('option');
      opt.value = inst.path;
      opt.textContent = javaOptionLabel(inst);
      instJavaSelect.appendChild(opt);
    });
    instJavaSelect.value = '';
  }

  if (btnToggleNewAdvanced && newInstAdvancedIsland) {
    btnToggleNewAdvanced.addEventListener('click', () => {
      const isExpanded = btnToggleNewAdvanced.getAttribute('aria-expanded') === 'true';
      btnToggleNewAdvanced.setAttribute('aria-expanded', String(!isExpanded));
      newInstAdvancedIsland.classList.toggle('hidden', isExpanded);
    });
  }

  document.getElementById('btn-new-instance').addEventListener('click', async () => {
    if (instNameInput) instNameInput.value = '';
    setNewInstanceLoader('Fabric');
    if (instLoaderVersionInput) instLoaderVersionInput.value = '';
    if (instMinRamInput) instMinRamInput.value = '';
    if (instMaxRamInput) instMaxRamInput.value = '';
    if (instJvmArgsInput) instJvmArgsInput.value = '';
    if (btnToggleNewAdvanced && newInstAdvancedIsland) {
      btnToggleNewAdvanced.setAttribute('aria-expanded', 'false');
      newInstAdvancedIsland.classList.add('hidden');
    }
    populateNewJavaDropdown();
    overlay.classList.remove('hidden');
    await loadMcVersions();
    await initInstanceDirField();
  });
  document.getElementById('btn-cancel-new-instance').addEventListener('click', () => overlay.classList.add('hidden'));
  document.getElementById('btn-cancel-install-form').addEventListener('click', () => overlay.classList.add('hidden'));

  // Install
  document.getElementById('btn-start-install').addEventListener('click', installInstance);

  // Edit Instance overlay
  const editOverlay = document.getElementById('edit-instance-overlay');
  const editLoaderVersionTile = document.getElementById('edit-inst-loader-version-tile');
  const editMcVersionSelect = document.getElementById('edit-inst-mc-version');
  const editLoaderInput = document.getElementById('edit-inst-loader');
  const editAdvancedIsland = document.getElementById('edit-inst-advanced-island');
  const btnToggleEditAdvanced = document.getElementById('btn-toggle-edit-advanced');
  const btnCopyEditDir = document.getElementById('btn-copy-edit-dir');
  const editInstSubtitle = document.getElementById('edit-inst-subtitle');
  const editJavaSelect = document.getElementById('edit-inst-java-select');
  const editMinRamInput = document.getElementById('edit-inst-min-ram');
  const editMaxRamInput = document.getElementById('edit-inst-max-ram');
  const editJvmArgsInput = document.getElementById('edit-inst-jvm-args');

  // Initialize loader button icons
  const editLoaderImgVanilla = document.getElementById('edit-loader-img-vanilla');
  if (editLoaderImgVanilla) editLoaderImgVanilla.src = iconVanilla;
  const editLoaderImgFabric = document.getElementById('edit-loader-img-fabric');
  if (editLoaderImgFabric) editLoaderImgFabric.src = iconFabric;
  const editLoaderImgForge = document.getElementById('edit-loader-img-forge');
  if (editLoaderImgForge) editLoaderImgForge.src = iconForge;
  const editLoaderImgNeoforge = document.getElementById('edit-loader-img-neoforge');
  if (editLoaderImgNeoforge) editLoaderImgNeoforge.src = iconNeoforge;
  const editLoaderImgQuilt = document.getElementById('edit-loader-img-quilt');
  if (editLoaderImgQuilt) editLoaderImgQuilt.src = iconQuilt;

  function setEditLoader(loader) {
    const norm = (loader || 'Vanilla').trim();
    if (editLoaderInput) editLoaderInput.value = norm;

    const btns = document.querySelectorAll('#edit-inst-loader-buttons .hypr-loader-btn');
    btns.forEach(btn => {
      const match = btn.getAttribute('data-loader').toLowerCase() === norm.toLowerCase();
      btn.classList.toggle('active', match);
    });

    const isVanilla = norm.toLowerCase() === 'vanilla';
    if (editLoaderVersionTile) {
      editLoaderVersionTile.classList.toggle('hidden', isVanilla);
    }
  }

  const editLoaderBtns = document.querySelectorAll('#edit-inst-loader-buttons .hypr-loader-btn');
  editLoaderBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const loader = btn.getAttribute('data-loader');
      setEditLoader(loader);
    });
  });

  async function populateEditJavaDropdown(selectedJava) {
    if (!editJavaSelect) return;
    editJavaSelect.innerHTML = `
      <option value="">Use Settings (Default)</option>
      <option value="__smart__">✦ Smart Java Detection (Auto)</option>
    `;
    let installs = _lastJavaInstallations;
    if (!installs || installs.length === 0) {
      try {
        installs = await api.listJavaInstallations();
        _lastJavaInstallations = installs || [];
      } catch (_) {
        installs = [];
      }
    }
    (installs || []).forEach(inst => {
      const opt = document.createElement('option');
      opt.value = inst.path;
      opt.textContent = javaOptionLabel(inst);
      editJavaSelect.appendChild(opt);
    });
    editJavaSelect.value = selectedJava || '';
  }

  if (btnToggleEditAdvanced && editAdvancedIsland) {
    btnToggleEditAdvanced.addEventListener('click', () => {
      const isExpanded = btnToggleEditAdvanced.getAttribute('aria-expanded') === 'true';
      btnToggleEditAdvanced.setAttribute('aria-expanded', String(!isExpanded));
      editAdvancedIsland.classList.toggle('hidden', isExpanded);
    });
  }

  if (btnCopyEditDir) {
    btnCopyEditDir.addEventListener('click', () => {
      const dirText = document.getElementById('edit-inst-dir').textContent;
      if (dirText && dirText !== '—') {
        navigator.clipboard.writeText(dirText).then(() => {
          btnCopyEditDir.textContent = 'Copied!';
          setTimeout(() => { btnCopyEditDir.textContent = 'Copy'; }, 1500);
        }).catch(err => {
          console.error('Failed to copy directory:', err);
        });
      }
    });
  }

  document.getElementById('btn-edit-instance').addEventListener('click', async () => {
    if (!selectedInstanceId) return;
    const inst = getInstances().find(i => i.version_id === selectedInstanceId);
    if (!inst) return;

    document.getElementById('edit-inst-name').value = inst.name || inst.version_id;
    if (editInstSubtitle) {
      editInstSubtitle.textContent = inst.name || inst.version_id;
    }
    setEditLoader(loaderLabel(inst.loader));
    document.getElementById('edit-inst-loader-version').value = (inst.loader_version && inst.loader_version !== 'latest') ? inst.loader_version : '';
    document.getElementById('edit-inst-dir').textContent = inst.directory || (settings ? settings.game_directory : '—');
    
    // Per-instance advanced options
    if (editMinRamInput) editMinRamInput.value = inst.min_ram_mb || '';
    if (editMaxRamInput) editMaxRamInput.value = inst.max_ram_mb || '';
    if (editJvmArgsInput) editJvmArgsInput.value = inst.jvm_args || '';
    populateEditJavaDropdown(inst.java_path);

    editMcVersionSelect.innerHTML = '<option>Loading…</option>';

    // Collapse advanced options by default on open
    if (btnToggleEditAdvanced && editAdvancedIsland) {
      btnToggleEditAdvanced.setAttribute('aria-expanded', 'false');
      editAdvancedIsland.classList.add('hidden');
    }

    // Show the overlay right away — fetching the Minecraft version list can
    // take a moment on the first open (network round trip for the Mojang
    // manifest), and waiting on that before showing anything made "Edit"
    // feel slow. Populate the dropdown in the background instead.
    editOverlay.classList.remove('hidden');
    loadMcVersions(editMcVersionSelect, inst.minecraft_version || inst.version_id).catch(e => {
      console.error('Failed to load MC versions for edit overlay:', e);
    });
  });
  function closeEditInstanceOverlay() { editOverlay.classList.add('hidden'); }
  document.getElementById('btn-cancel-edit-instance').addEventListener('click', closeEditInstanceOverlay);
  document.getElementById('btn-cancel-edit-instance-2').addEventListener('click', closeEditInstanceOverlay);
  document.getElementById('btn-save-edit-instance').addEventListener('click', async () => {
    if (!selectedInstanceId) return;
    const inst = getInstances().find(i => i.version_id === selectedInstanceId);
    if (!inst) return;

    const name = document.getElementById('edit-inst-name').value.trim();
    const newMcVersion = editMcVersionSelect.value;
    let newLoader = ((editLoaderInput ? editLoaderInput.value : 'Vanilla') || 'Vanilla').toLowerCase();
    const loaderVersion = document.getElementById('edit-inst-loader-version').value.trim() || 'latest';
    
    const javaPath = editJavaSelect ? editJavaSelect.value : '';
    const minRamMb = editMinRamInput && editMinRamInput.value ? parseInt(editMinRamInput.value) : null;
    const maxRamMb = editMaxRamInput && editMaxRamInput.value ? parseInt(editMaxRamInput.value) : null;
    const jvmArgs = editJvmArgsInput ? editJvmArgsInput.value.trim() : '';

    const versionOrLoaderChanged =
      newMcVersion !== (inst.minecraft_version || inst.version_id) ||
      newLoader !== (inst.loader || 'vanilla').toLowerCase();

    const saveBtn = document.getElementById('btn-save-edit-instance');
    saveBtn.disabled = true;

    try {
      if (!versionOrLoaderChanged) {
        // Nothing that requires a reinstall changed — update metadata & advanced settings
        await api.updateInstance(selectedInstanceId, name || null, loaderVersion || 'latest', javaPath, minRamMb, maxRamMb, jvmArgs);
      } else {
        // Minecraft version and/or loader changed — reinstall with new version
        closeEditInstanceOverlay();
        showToast(`Reinstalling ${name || inst.name} as ${newMcVersion} (${loaderLabel(newLoader)})…`, 'info');
        if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, newMcVersion, newLoader);
        const newInstance = await api.installVersion(newMcVersion, newLoader, loaderVersion, inst.directory, name || inst.name, inst.version_id);
        // Persist advanced settings onto the newly created instance record
        try {
          await api.updateInstance(newInstance.version_id, name || null, loaderVersion || 'latest', javaPath, minRamMb, maxRamMb, jvmArgs);
        } catch (_) {}
        // Remove the old version's files/tracking now that the new one is in place,
        // as long as it didn't just overwrite itself (same version_id/dir).
        if (newInstance.version_id !== inst.version_id) {
          try {
            await api.deleteInstalledVersion(inst.version_id, inst.directory);
          } catch (e) {
            console.warn('Could not clean up old instance version:', e);
          }
        }
        await refreshInstances();
        renderInstanceList();
        selectInstance(newInstance.version_id);
        showToast('Instance reinstalled', 'success');
        saveBtn.disabled = false;
        return;
      }
      await refreshInstances();
      renderInstanceList();
      selectInstance(selectedInstanceId);
      closeEditInstanceOverlay();
      showToast('Instance updated', 'success');
    } catch (e) {
      if (dlWidgetGeneric) dlWidgetGeneric.failInstanceInstall(INSTANCE_INSTALL_CARD_ID, String(e));
      showToast('Failed to update instance: ' + e, 'error');
    } finally {
      saveBtn.disabled = false;
    }
  });

  // Directory choice (default vs separated vs custom)
  const dirDefaultRadio = document.getElementById('inst-dir-default');
  const dirSeparatedRadio = document.getElementById('inst-dir-separated');
  const dirCustomRadio = document.getElementById('inst-dir-custom');
  const dirPathRow = document.getElementById('inst-dir-path-row');
  const dirPathInput = document.getElementById('inst-dir-path');
  const dirBrowseBtn = document.getElementById('inst-dir-browse');
  const dirSeparatedCard = document.getElementById('dir-opt-separated-card');
  const dirDefaultCard = document.getElementById('dir-opt-default-card');
  const dirCustomCard = document.getElementById('dir-opt-custom-card');

  function syncDirRowVisibility() {
    if (dirPathRow) dirPathRow.classList.toggle('hidden', !dirCustomRadio.checked);
    if (dirSeparatedCard) dirSeparatedCard.classList.toggle('active', !!dirSeparatedRadio.checked);
    if (dirDefaultCard) dirDefaultCard.classList.toggle('active', !!dirDefaultRadio.checked);
    if (dirCustomCard) dirCustomCard.classList.toggle('active', !!dirCustomRadio.checked);
  }
  if (dirDefaultRadio && dirCustomRadio) {
    dirDefaultRadio.addEventListener('change', syncDirRowVisibility);
    dirCustomRadio.addEventListener('change', syncDirRowVisibility);
  }
  if (dirSeparatedRadio) {
    dirSeparatedRadio.addEventListener('change', syncDirRowVisibility);
  }
  if (dirBrowseBtn) {
    dirBrowseBtn.addEventListener('click', async () => {
      try {
        const picked = await window.__TAURI__.dialog.open({ directory: true, multiple: false });
        if (picked) {
          dirPathInput.value = Array.isArray(picked) ? picked[0] : picked;
          dirCustomRadio.checked = true;
          syncDirRowVisibility();
        }
      } catch (e) {
        showToast('Could not open folder picker: ' + e, 'error');
      }
    });
  }
}

let defaultMcDirCache = null;
async function initInstanceDirField() {
  const dirDefaultRadio = document.getElementById('inst-dir-default');
  const dirSeparatedRadio = document.getElementById('inst-dir-separated');
  const dirCustomRadio = document.getElementById('inst-dir-custom');
  const dirPathRow = document.getElementById('inst-dir-path-row');
  const dirPathInput = document.getElementById('inst-dir-path');
  const defaultLabel = document.getElementById('inst-dir-default-path');
  const dirSeparatedCard = document.getElementById('dir-opt-separated-card');
  const dirDefaultCard = document.getElementById('dir-opt-default-card');
  const dirCustomCard = document.getElementById('dir-opt-custom-card');
  if (!dirDefaultRadio) return;

  // Reset every time the form is (re)opened. If the user already has at
  // least one instance, "Separated folder" is picked by default so a new
  // instance's mods/saves/config can't collide with an existing one's —
  // otherwise (first-ever instance) there's nothing to collide with yet,
  // so the plain default directory is simplest.
  const hasExistingInstances = getInstances().length > 0;
  dirDefaultRadio.checked = !hasExistingInstances;
  if (dirSeparatedRadio) dirSeparatedRadio.checked = hasExistingInstances;
  dirCustomRadio.checked = false;
  if (dirPathRow) dirPathRow.classList.add('hidden');
  if (dirPathInput) dirPathInput.value = '';

  if (dirSeparatedCard) dirSeparatedCard.classList.toggle('active', hasExistingInstances);
  if (dirDefaultCard) dirDefaultCard.classList.toggle('active', !hasExistingInstances);
  if (dirCustomCard) dirCustomCard.classList.remove('active');

  try {
    if (!defaultMcDirCache) defaultMcDirCache = await api.getDefaultMcDir();
    if (defaultLabel) defaultLabel.textContent = defaultMcDirCache;
  } catch (e) {
    if (defaultLabel) defaultLabel.textContent = '~/.minecraft';
  }
}

let mcVersionsLoaded = false;
let mcVersionsCache = null; // cached raw manifest list so multiple selects don't refetch

// Fetch which Minecraft versions are already downloaded for *some* instance
// (any loader/directory). This reads only in-memory state on the backend —
// no network call — so it resolves near-instantly, even with no internet.
async function getCachedMcVersionSet() {
  try {
    const cached = await api.getCachedVersions();
    return new Set((cached || []).map(c => c.minecraft_version));
  } catch (e) {
    console.error('Error fetching cached versions:', e);
    return new Set();
  }
}

// Render a version <select>'s options, splitting already-downloaded
// versions into their own group at the top and visually marking them
// (checkmark + accent color) so they're easy to tell apart from versions
// that still need to be downloaded.
function renderMcVersionOptions(sel, versionList, cachedSet, selectedValue) {
  sel.innerHTML = '';
  const seen = new Set();

  const downloadedGroup = document.createElement('optgroup');
  downloadedGroup.label = '✓ Already Downloaded';
  const remainingGroup = document.createElement('optgroup');
  remainingGroup.label = 'Available to Download';

  const makeOption = (id, isCached) => {
    const opt = document.createElement('option');
    opt.value = id;
    opt.textContent = isCached ? `✓ ${id}` : id;
    if (isCached) {
      opt.style.color = 'var(--accent, #6ee7b7)';
      opt.style.fontWeight = '600';
    }
    return opt;
  };

  versionList.forEach(v => {
    if (seen.has(v.id)) return;
    seen.add(v.id);
    const isCached = cachedSet.has(v.id);
    (isCached ? downloadedGroup : remainingGroup).appendChild(makeOption(v.id, isCached));
  });

  // Cached versions might not be in the (possibly not-yet-loaded, or
  // release-filtered) manifest list — e.g. a snapshot, or before the
  // network fetch has completed. Show them regardless.
  cachedSet.forEach(id => {
    if (!seen.has(id)) {
      seen.add(id);
      downloadedGroup.appendChild(makeOption(id, true));
    }
  });

  if (downloadedGroup.childElementCount) sel.appendChild(downloadedGroup);
  if (remainingGroup.childElementCount) sel.appendChild(remainingGroup);
  if (selectedValue && seen.has(selectedValue)) sel.value = selectedValue;
}

async function loadMcVersions(selectEl, selectedValue) {
  const sel = selectEl || document.getElementById('inst-mc-version');

  // Step 1 — show already-downloaded versions immediately. This needs no
  // network round trip, so it renders right away (and still works with no
  // internet connection at all).
  const cachedSet = await getCachedMcVersionSet();
  if (cachedSet.size > 0) {
    renderMcVersionOptions(sel, [], cachedSet, selectedValue);
  } else {
    sel.innerHTML = '<option>Fetching versions…</option>';
  }

  // Step 2 — fetch (or reuse) the full Mojang manifest and merge it in,
  // still keeping the downloaded ones marked and grouped at the top.
  try {
    let versionList = mcVersionsCache;
    if (!versionList) {
      const data = await api.getAvailableVersions();
      if (Array.isArray(data)) {
        versionList = data;
      } else if (data && Array.isArray(data.versions)) {
        versionList = data.versions;
      } else {
        versionList = [];
      }
      mcVersionsCache = versionList;
    }

    if (!versionList || versionList.length === 0) {
      if (cachedSet.size === 0) sel.innerHTML = '<option>No versions found</option>';
      return;
    }

    const releases = versionList.filter(v => v.type === 'release' || !v.type);
    const listToRender = (releases.length > 0 ? releases : versionList).slice(0, 100);

    // If we're pre-selecting a version (e.g. editing an existing instance)
    // that isn't in the trimmed/release list (an old snapshot, for
    // example), add it so the select doesn't silently jump to a different
    // version than what's actually installed.
    if (selectedValue && !listToRender.some(v => v.id === selectedValue) && !cachedSet.has(selectedValue)) {
      listToRender.unshift({ id: selectedValue });
    }

    renderMcVersionOptions(sel, listToRender, cachedSet, selectedValue);
    mcVersionsLoaded = true;
  } catch (e) {
    console.error('Error fetching Minecraft versions:', e);
    if (cachedSet.size === 0) {
      sel.innerHTML = `<option>Failed to load (${e})</option>`;
    } else {
      showToast(
        'Could not reach the version list — showing already-downloaded versions only',
        'error',
        null,
        [{ label: 'Refresh', onClick: () => { mcVersionsCache = null; loadMcVersions(sel, selectedValue); } }]
      );
    }
  }
}

function confirmZlibIfConflict(loader) {
  return new Promise(async (resolve) => {
    const l = (loader || '').toLowerCase();
    if (l !== 'forge' && l !== 'neoforge') {
      return resolve(true);
    }
    try {
      const res = await api.checkLinuxZlibConflict();
      if (!res || !res.has_conflict) {
        return resolve(true);
      }
    } catch {
      return resolve(true);
    }

    const overlay = document.getElementById('zlib-warning-overlay');
    if (!overlay) return resolve(true);

    const close = () => {
      overlay.classList.add('hidden');
      cleanup();
    };

    const handleProceed = () => {
      close();
      resolve(true);
    };

    const handleCancel = () => {
      close();
      resolve(false);
    };

    const autoBtn = document.getElementById('btn-zlib-warning-autoinstall');
    const handleAutoInstall = async () => {
      if (autoBtn) {
        autoBtn.disabled = true;
        autoBtn.textContent = 'Installing…';
      }
      showToast('Authenticating with system to install standard zlib…', 'info');
      try {
        await api.installLinuxPackage('zlib');
        showToast('Standard zlib installed successfully!', 'success');
        close();
        resolve(true);
      } catch (err) {
        showToast(String(err), 'error');
        if (autoBtn) {
          autoBtn.disabled = false;
          autoBtn.innerHTML = `
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
            </svg>
            Install for Me
          `;
        }
      }
    };

    const cleanup = () => {
      document.getElementById('btn-close-zlib-warning')?.removeEventListener('click', handleCancel);
      document.getElementById('btn-zlib-warning-cancel')?.removeEventListener('click', handleCancel);
      document.getElementById('btn-zlib-warning-proceed')?.removeEventListener('click', handleProceed);
      autoBtn?.removeEventListener('click', handleAutoInstall);
    };

    document.getElementById('btn-close-zlib-warning')?.addEventListener('click', handleCancel);
    document.getElementById('btn-zlib-warning-cancel')?.addEventListener('click', handleCancel);
    document.getElementById('btn-zlib-warning-proceed')?.addEventListener('click', handleProceed);
    autoBtn?.addEventListener('click', handleAutoInstall);

    overlay.querySelectorAll('.btn-copy-zlib-cmd').forEach(btn => {
      btn.onclick = () => {
        const cmd = btn.dataset.cmd;
        if (cmd) {
          navigator.clipboard.writeText(cmd).then(() => {
            showToast('Copied command to clipboard!', 'info');
          });
        }
      };
    });

    overlay.classList.remove('hidden');
  });
}

function showXrandrWarningModal(onRetry) {
  const overlay = document.getElementById('xrandr-warning-overlay');
  if (!overlay) return;

  const close = () => {
    overlay.classList.add('hidden');
    cleanup();
  };

  const autoBtn = document.getElementById('btn-xrandr-warning-autoinstall');
  const handleAutoInstall = async () => {
    if (autoBtn) {
      autoBtn.disabled = true;
      autoBtn.textContent = 'Installing…';
    }
    showToast('Authenticating with system to install xrandr…', 'info');
    try {
      await api.installLinuxPackage('xrandr');
      showToast('xrandr installed successfully!', 'success');
      close();
      if (typeof onRetry === 'function') onRetry();
    } catch (err) {
      showToast(String(err), 'error');
      if (autoBtn) {
        autoBtn.disabled = false;
        autoBtn.innerHTML = `
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
          </svg>
          Install for Me
        `;
      }
    }
  };

  const cleanup = () => {
    document.getElementById('btn-close-xrandr-warning')?.removeEventListener('click', close);
    document.getElementById('btn-xrandr-warning-cancel')?.removeEventListener('click', close);
    autoBtn?.removeEventListener('click', handleAutoInstall);
  };

  document.getElementById('btn-close-xrandr-warning')?.addEventListener('click', close);
  document.getElementById('btn-xrandr-warning-cancel')?.addEventListener('click', close);
  autoBtn?.addEventListener('click', handleAutoInstall);

  overlay.querySelectorAll('.btn-copy-xrandr-cmd').forEach(btn => {
    btn.onclick = () => {
      const cmd = btn.dataset.cmd;
      if (cmd) {
        navigator.clipboard.writeText(cmd).then(() => {
          showToast('Copied command to clipboard!', 'info');
        });
      }
    };
  });

  overlay.classList.remove('hidden');
}

async function installInstance() {
  const mcVersion = document.getElementById('inst-mc-version').value;
  let loader = document.getElementById('inst-loader').value || 'vanilla';
  let loaderVersion = document.getElementById('inst-loader-version').value.trim() || 'latest';
  if (!mcVersion) { showToast('Version required', 'error'); return; }
  if (loader.toLowerCase() === 'vanilla') loader = 'vanilla';

  const btn = document.getElementById('btn-start-install');
  btn.disabled = true;

  const zlibOk = await confirmZlibIfConflict(loader);
  if (!zlibOk) {
    btn.disabled = false;
    return;
  }

  // No name typed — fall back to "{Loader} {Minecraft version}", e.g.
  // "Fabric 1.21.1" or "Vanilla 1.20.4", instead of blocking install.
  const typedName = document.getElementById('inst-name').value.trim();
  const name = typedName || `${loaderLabel(loader)} ${mcVersion}`;

  const useCustomDir = document.getElementById('inst-dir-custom').checked;
  const useSeparatedDir = document.getElementById('inst-dir-separated').checked;
  let directory = null; // null => backend uses the default/global Minecraft directory
  if (useCustomDir) {
    directory = document.getElementById('inst-dir-path').value.trim() || null;
  } else if (useSeparatedDir) {
    // Same mechanism as a custom directory, just auto-computed: puts this
    // instance's mods/saves/config in their own folder under
    // <default minecraft dir>/!Instances/<name>/ so a fresh instance can
    // never inherit or collide with another instance's mods.
    const baseDir = defaultMcDirCache || (settings && settings.game_directory) || '';
    const safeName = name.replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'Instance';
    directory = baseDir ? `${baseDir}/!Instances/${safeName}` : null;
  }

  const javaPath = instJavaSelect ? instJavaSelect.value : '';
  const minRamMb = instMinRamInput && instMinRamInput.value ? parseInt(instMinRamInput.value) : null;
  const maxRamMb = instMaxRamInput && instMaxRamInput.value ? parseInt(instMaxRamInput.value) : null;
  const jvmArgs = instJvmArgsInput ? instJvmArgsInput.value.trim() : '';

  // Close the form right away — the floating download widget (bottom-left)
  // tracks progress from here, so the user is free to keep using the app.
  document.getElementById('new-instance-overlay').classList.add('hidden');
  showToast(`Installing ${name}…`, 'info');
  if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, mcVersion, loader);

  try {
    const result = await api.installVersion(mcVersion, loader, loaderVersion, directory, name);

    // A brand-new instance should never come up hidden — but its
    // version_id can coincide with an entry that was previously hidden
    // (e.g. a bare vanilla version auto-hidden because it only existed to
    // satisfy a modded instance's dependency, or one the user hid earlier).
    // Since this was just explicitly installed, make sure it's visible.
    if (result && result.version_id) {
      try { await api.unhideInstance(result.version_id); } catch (e) { /* not hidden — fine */ }
      if (javaPath || minRamMb || maxRamMb || jvmArgs) {
        try {
          await api.updateInstance(result.version_id, name, loaderVersion, javaPath, minRamMb, maxRamMb, jvmArgs);
        } catch (e) {
          console.warn('Could not set initial instance settings:', e);
        }
      }
    }

    await refreshInstances();

    showToast('Instance installed!', 'success');
    renderInstanceList();
    if (result && result.version_id) {
      selectInstance(result.version_id);
    }
  } catch (e) {
    if (String(e).toLowerCase().includes('cancel')) {
      showToast('Installation cancelled', 'info');
    } else {
      if (dlWidgetGeneric) dlWidgetGeneric.failInstanceInstall(INSTANCE_INSTALL_CARD_ID, String(e));
      showToast('Install failed: ' + e, 'error');
    }
  } finally {
    btn.disabled = false;
  }
}

// ══════════════════════════════════════════════════════════════════
// MODS
// ══════════════════════════════════════════════════════════════════
async function loadModInstances() {
  if (!settings) settings = await api.getSettings();
  const sel = document.getElementById('mods-instance-select');
  const instances = getInstances();
  const desiredInstance = selectedInstanceId;
  sel.innerHTML = '<option value="">Select an instance…</option>';
  instances.forEach(inst => {
    const opt = document.createElement('option');
    opt.value = inst.version_id;
    opt.textContent = inst.name || inst.version_id;
    if (inst.version_id === desiredInstance) opt.selected = true;
    sel.appendChild(opt);
  });
}

// How often the mods list quietly refreshes itself while the Mods tab is
// open (replaces the old manual Refresh button).
const MODS_AUTO_REFRESH_MS = 20000;

// Which installed mods currently have an update available, keyed by the
// mod's file path — for the directory currently shown in the Mods tab.
// Populated by "Check Updates" / "Update All" (and the launch-time
// background check) and consumed by buildModCard to show/hide the per-mod
// green Update button.
let modUpdateInfo = new Map();
// Same info, but kept per-instance (keyed by version_id, not directory —
// two instances can point at the same mods folder yet want different
// loader/game-version, so keying by directory alone let one instance's
// results leak into another's) so switching the "Targeted Instance"
// dropdown (or the background launch check finishing later) doesn't lose
// what's already been found for other instances.
let modUpdateInfoByDir = new Map();

// Cache key for `modUpdateInfoByDir`: instance-scoped when we have a
// tracked instance (so two instances sharing a mods folder never see each
// other's results), falling back to the raw directory for the untracked
// "no instance selected" case.
function modsCacheKey(instance, directory) {
  return instance && instance.version_id ? `inst:${instance.version_id}` : `dir:${directory}`;
}
// True while a Check Updates / Update All pass (manual or the automatic
// one run at launch) is in flight — Update All is disabled meanwhile so it
// can't race a check that hasn't finished yet.
let modsUpdateCheckBusy = false;

function setUpdateButtonsBusy(busy) {
  modsUpdateCheckBusy = busy;
  const checkBtn = document.getElementById('btn-check-updates');
  if (checkBtn) checkBtn.disabled = busy;
  refreshUpdateAllButtonState();
}

// Update All should only be clickable when there's actually something to
// update — busy (a check is running) or an empty result both mean there's
// nothing useful it could do right now.
function refreshUpdateAllButtonState() {
  const updateAllBtn = document.getElementById('btn-update-all-mods');
  if (!updateAllBtn) return;
  updateAllBtn.disabled = modsUpdateCheckBusy || modUpdateInfo.size === 0;
}



// Runs the update check for one instance's mod directory and stores the
// result, refreshing the on-card Update buttons in place if that directory
// happens to be the one currently shown in the Mods tab.
async function checkUpdatesForDirectory(directory, instance) {
  const up = await checkUpdatesForMods(directory, instance);
  const map = new Map();
  for (const u of up) {
    if (u.mod && u.mod.path) map.set(u.mod.path, u);
  }
  const key = modsCacheKey(instance, directory);
  modUpdateInfoByDir.set(key, map);
  if (modsCacheKey(getModsTargetInstance(), getModsTargetDirectory()) === key) {
    modUpdateInfo = map;
    refreshUpdateButtonsOnVisibleCards();
  }
  return up;
}

function refreshUpdateButtonsOnVisibleCards() {
  const grid = document.getElementById('mods-grid');
  refreshUpdateAllButtonState();
  if (!grid) return;
  grid.querySelectorAll('.mod-card').forEach(card => {
    const btn = card.querySelector('.btn-update-mod');
    const hasUpdate = modUpdateInfo.has(card.dataset.path);
    if (btn) btn.classList.toggle('has-update', hasUpdate);
    card.classList.toggle('mod-update-glow', hasUpdate);
  });
}

// Checks mod updates for the currently selected/targeted instance only —
// not every configured instance. Runs automatically at launch and whenever
// the selected instance changes. Update All / Check Updates stay disabled
// for the run so nothing tries to update before the check has finished.
// This is the *automatic* check (as opposed to the manual "Check Updates"
// button, which has its own toasts) — it stays quiet while running and
// only speaks up if it actually finds something, so it doesn't nag with a
// "Checking…" / "No updates found" toast every time an instance is picked
// or the app starts. The "found updates" notification itself can be turned
// off entirely via Settings > Behavior.
async function checkSelectedInstanceForUpdates() {
  if (modsUpdateCheckBusy) return;
  const target = getModsTargetInstance();
  const directory = getModsTargetDirectory();
  if (!directory) return;
  setUpdateButtonsBusy(true);
  try {
    const up = await checkUpdatesForDirectory(directory, target);
    if (up.length > 0 && (!settings || settings.notify_on_auto_mod_updates !== false)) {
      showToast(`${up.length} mod update${up.length === 1 ? '' : 's'} available`, 'info');
    }
  } catch (e) {
    console.error('Update check failed for', (target && (target.name || target.version_id)) || directory, e);
  } finally {
    setUpdateButtonsBusy(false);
  }
}

// Cache of resolved mod icon URLs, keyed by the mod jar's SHA-1 hash (same
// identity Modrinth itself uses), so we don't re-hit Modrinth for every mod
// on every refresh. `url: null` means "looked it up, nothing found" (still
// cached, so we don't keep retrying a genuine miss forever).
// Bumped to v3: previously this was keyed by normalized mod *name* and
// resolved via a fuzzy text search, which is exactly what let mods like
// Cloth Config end up with no icon — the search didn't reliably land on the
// right Modrinth project (or any project) for every display name. Keying by
// hash and resolving through Modrinth's /version_files endpoint (the same
// method the Java client's ModUpdateService uses) is an exact match instead.
let modIconCache = {};
try { modIconCache = JSON.parse(localStorage.getItem('zerolauncher-mod-icon-cache-v3') || '{}'); } catch { modIconCache = {}; }
function saveModIconCache() {
  try { localStorage.setItem('zerolauncher-mod-icon-cache-v3', JSON.stringify(modIconCache)); } catch { /* ignore */ }
}

// Cache of Modrinth project metadata (currently just icon_url), keyed by
// project ID. Several installed mod jars can resolve to the same project
// (e.g. across Minecraft versions), so this avoids re-fetching identical
// project data once any one of them has been looked up — mirrors the Java
// client's ModMetadataCache.
let projectIconCache = {};
try { projectIconCache = JSON.parse(localStorage.getItem('zerolauncher-project-icon-cache-v1') || '{}'); } catch { projectIconCache = {}; }
function saveProjectIconCache() {
  try { localStorage.setItem('zerolauncher-project-icon-cache-v1', JSON.stringify(projectIconCache)); } catch { /* ignore */ }
}

async function applyModIcon(iconEl, url) {
  if (!iconEl || !iconEl.isConnected) return;
  iconEl.classList.remove('loading');
  let src = url;
  // Same method as the Java client's ModIconCache: persist the icon's raw
  // bytes to disk once (keyed by a hash of the URL) so every later render
  // reads a local file instead of the browser hitting the network via
  // <img src> again — that on-disk persistence (not just caching the
  // resolved URL, which is all this used to do) is what actually stops
  // icons from going missing/flaky: a previously-loaded icon now always has
  // a local copy to render from, even if Modrinth is slow or rate-limiting.
  try {
    const localPath = await api.cacheModIcon(url);
    if (localPath) {
      const convert = window.__TAURI__.core.convertFileSrc;
      src = convert ? convert(localPath) : url;
    }
  } catch (e) {
    // Disk-cache round trip failed (e.g. first-ever fetch of this icon
    // failed) — fall back to the remote URL so it can still show this once.
  }
  if (!iconEl.isConnected) return;
  const img = document.createElement('img');
  img.alt = '';
  img.loading = 'lazy';
  // If the icon URL 404s or the request otherwise fails (this is what made
  // icons "disappear" — a broken <img> renders as blank), fall back to the
  // placeholder glyph instead of leaving an empty box.
  img.addEventListener('error', () => {
    if (!iconEl.isConnected) return;
    iconEl.innerHTML = ICON_UNKNOWN_SVG;
    iconEl.classList.add('icon-fallback');
  });
  img.src = src;
  iconEl.innerHTML = '';
  iconEl.appendChild(img);
}

// A miss (no icon found) is cached too, so we don't keep re-querying for
// mods Modrinth genuinely doesn't have — but only for a while. Without a
// TTL, a transient failure (rate limit, network hiccup handled as a "miss")
// would get cached as a permanent miss and that mod's icon would never
// load again, which is the "some images never/eventually stop loading" bug.
const MOD_ICON_MISS_TTL_MS = 6 * 60 * 60 * 1000; // 6 hours

// Modrinth's /version_files endpoint accepts a batch of hashes in one call —
// chunk large mod lists so a single request body/response doesn't get huge.
const MOD_HASH_BATCH_SIZE = 200;

function chunkArray(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

// Icon lookups used to fire one fuzzy Modrinth text search per mod card,
// which is both slow (one request per mod, throttled/staggered to avoid
// rate limits) and unreliable (a name-based search doesn't always land on
// the right project — or any project — which is exactly why some mods,
// like Cloth Config, ended up with no icon at all).
//
// Same method as the Java client's ModUpdateService.identifyMods /
// fetchProjectNames: batch-identify every queued mod by the exact SHA-1
// hash of its jar (via /version_files), then batch-fetch icon/title/
// description for the resulting project IDs (via /projects?ids=). Both
// calls cover the whole mods list in one or two round trips instead of one
// request per mod, and hash matching is exact instead of fuzzy.
let modIconQueue = [];
let modIconFlushScheduled = false;

function scheduleIconResolve(mod, iconEl) {
  modIconQueue.push({ mod, iconEl });
  if (!modIconFlushScheduled) {
    modIconFlushScheduled = true;
    // Flush on the next microtask so every card built in the same loadMods()
    // pass (a synchronous forEach) ends up in a single batch instead of one
    // request per card.
    queueMicrotask(flushModIconQueue);
  }
}

async function flushModIconQueue() {
  const jobs = modIconQueue;
  modIconQueue = [];
  modIconFlushScheduled = false;
  if (jobs.length === 0) return;

  const now = Date.now();
  // Jobs we still need to resolve this pass, keyed by hash so multiple mod
  // cards sharing the same jar hash (e.g. duplicate installs) only need one
  // lookup. Mods without a hash (metadata read failed on the backend) can't
  // be identified this way and are simply left on the fallback glyph.
  const byHash = new Map(); // hash -> [{mod, iconEl}, ...]
  for (const job of jobs) {
    const hash = job.mod && job.mod.sha1;
    if (!hash) {
      applyFallbackIcon(job.iconEl);
      continue;
    }
    const cached = modIconCache[hash];
    if (cached && typeof cached === 'object') {
      if (cached.url) {
        applyModIcon(job.iconEl, cached.url);
        continue;
      }
      if (cached.ts && (now - cached.ts) < MOD_ICON_MISS_TTL_MS) {
        applyFallbackIcon(job.iconEl);
        continue;
      }
      // Stale miss — worth a fresh retry instead of trusting it forever.
    }
    if (!byHash.has(hash)) byHash.set(hash, []);
    byHash.get(hash).push(job);
  }

  const hashesToLookup = Array.from(byHash.keys());
  if (hashesToLookup.length === 0) return;

  // Step 1: identify each jar hash on Modrinth -> project ID.
  const hashToProjectId = new Map();
  try {
    for (const chunk of chunkArray(hashesToLookup, MOD_HASH_BATCH_SIZE)) {
      const result = await api.identifyModsByHash(chunk);
      for (const hash of chunk) {
        const entry = result && result[hash];
        if (entry && entry.project_id) hashToProjectId.set(hash, entry.project_id);
      }
    }
  } catch (e) {
    // Whole batch failed (network/rate-limit) — leave everything on the
    // fallback glyph this pass; the next auto-refresh will retry since
    // nothing gets cached as a miss here.
    hashesToLookup.forEach(hash => byHash.get(hash).forEach(job => applyFallbackIcon(job.iconEl)));
    return;
  }

  // Any hash Modrinth didn't recognize at all is a genuine miss — cache it
  // (with a TTL) so we don't keep re-querying for a jar that just isn't on
  // Modrinth.
  for (const hash of hashesToLookup) {
    if (!hashToProjectId.has(hash)) {
      modIconCache[hash] = { url: null, ts: now };
      byHash.get(hash).forEach(job => applyFallbackIcon(job.iconEl));
    }
  }

  // Step 2: batch-fetch icon URLs for every distinct project ID we don't
  // already have cached, then apply to every mod that resolved to it.
  const distinctProjectIds = Array.from(new Set(hashToProjectId.values()));
  const uncachedProjectIds = distinctProjectIds.filter(id => !(id in projectIconCache));
  if (uncachedProjectIds.length > 0) {
    try {
      for (const chunk of chunkArray(uncachedProjectIds, MOD_HASH_BATCH_SIZE)) {
        const projects = await api.discoverGetProjectsBatch(chunk);
        (projects || []).forEach(p => { projectIconCache[p.id] = p.icon_url || null; });
        // Any requested ID absent from the response genuinely has no project
        // data back (deleted/private project) — cache as no-icon too so it
        // isn't refetched every pass.
        chunk.forEach(id => { if (!(id in projectIconCache)) projectIconCache[id] = null; });
      }
      saveProjectIconCache();
    } catch (e) {
      // Leave uncached IDs alone; jobs for them fall through to the fallback
      // glyph below and will retry on a later pass.
    }
  }

  for (const [hash, projectId] of hashToProjectId.entries()) {
    const iconUrl = projectIconCache[projectId] || null;
    modIconCache[hash] = { url: iconUrl, ts: now };
    byHash.get(hash).forEach(job => {
      if (iconUrl) applyModIcon(job.iconEl, iconUrl);
      else applyFallbackIcon(job.iconEl);
    });
  }
  saveModIconCache();
}

function applyFallbackIcon(iconEl) {
  if (!iconEl || !iconEl.isConnected) return;
  iconEl.classList.remove('loading');
  iconEl.innerHTML = ICON_UNKNOWN_SVG;
  iconEl.classList.add('icon-fallback');
}

let modVirtualObserver = null;

function getModVirtualObserver() {
  if (!modVirtualObserver) {
    const root = document.getElementById('tab-mods');
    modVirtualObserver = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        const card = entry.target;
        if (entry.isIntersecting) {
          if (!card._isRendered) {
            renderModCardContent(card);
          }
        } else {
          if (card._isRendered) {
            unloadModCardContent(card);
          }
        }
      });
    }, {
      root: root || null,
      rootMargin: '180px 0px',
      threshold: 0,
    });
  }
  return modVirtualObserver;
}

function renderModCardContent(card) {
  const mod = card._mod;
  const directory = card._directory;
  const preservedIconHtml = card._preservedIconHtml;
  if (!mod || card._isRendered) return;
  card._isRendered = true;
  card.classList.remove('is-unloaded');

  const badges = (mod.loader || '').split(',').map(l => l.trim()).filter(Boolean).map(l => `<span class="loader-badge ${l.toLowerCase()}">${l}</span>`).join(' ');
  card.innerHTML = `
    <div class="mod-info">
      <div class="mod-icon loading">${ICON_UNKNOWN_SVG}</div>
      <div class="mod-meta">
        <div class="mod-name">${discoverEscape(mod.name || '')}</div>
        <div class="mod-desc">${mod.description ? (mod.description.length > 140 ? discoverEscape(mod.description.slice(0,137)) + '...' : discoverEscape(mod.description)) : ''}</div>
        <div class="mod-version">${discoverEscape(mod.version || '')}${badges ? ' ' + badges : ''}</div>
      </div>
    </div>
    <div class="mod-actions">
      <label class="mod-toggle-wrap">
        <input type="checkbox" ${mod.enabled ? 'checked' : ''} data-path="${discoverEscape(mod.path)}" class="mod-toggle-input">
        <span class="mod-toggle-slider"></span>
      </label>
      <button class="btn-update-mod" data-path="${discoverEscape(mod.path)}" title="Update to latest version" type="button">${DOWNLOAD_ICON_SVG}</button>
      <button class="btn-danger-pill btn-sm btn-delete-mod" data-path="${discoverEscape(mod.path)}" title="Delete mod">🗑</button>
    </div>
  `;

  const iconEl = card.querySelector('.mod-icon');
  if (preservedIconHtml) {
    iconEl.classList.remove('loading');
    iconEl.innerHTML = preservedIconHtml;
    if (!preservedIconHtml.includes('<img')) {
      iconEl.classList.add('icon-fallback');
    }
  } else {
    scheduleIconResolve(mod, iconEl);
  }

  const updateBtn = card.querySelector('.btn-update-mod');
  if (modUpdateInfo.has(mod.path)) {
    updateBtn.classList.add('has-update');
    card.classList.add('mod-update-glow');
  }
  updateBtn.addEventListener('click', async (ev) => {
    ev.stopPropagation();
    const info = modUpdateInfo.get(card.dataset.path);
    if (!info) return;
    const oldPath = card.dataset.path;
    const modLabel = mod.name || mod.file_name;
    const originalIcon = updateBtn.innerHTML;
    updateBtn.disabled = true;
    updateBtn.classList.add('is-downloading');
    updateBtn.innerHTML = '<span class="mini-spinner"></span>';
    const dlId = genDlId('mod-update');
    if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading…', `Updating ${modLabel}…`, { icon: 'update' });
    showToast(`Updating ${modLabel}…`, 'info');
    let ok = false;
    try {
      await trackedDiscoverDownload(directory, 'mod', info.file.url, info.file.filename, dlId);
      await deleteOldModFileIfReplaced(directory, oldPath, info.file.filename);
      modUpdateInfo.delete(oldPath);
      card.classList.remove('mod-update-glow');
      showToast(`Updated ${modLabel}`, 'success');
      ok = true;
      await loadMods();
    } catch (e) {
      const cancelled = dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);
      if (!cancelled) showToast('Update failed: ' + e, 'error');
      updateBtn.disabled = false;
      updateBtn.classList.remove('is-downloading');
      updateBtn.innerHTML = originalIcon;
    } finally {
      if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, ok, ok ? `Updated ${modLabel}` : undefined);
    }
  });

  const toggleInput = card.querySelector('.mod-toggle-input');
  toggleInput.addEventListener('change', async () => {
    const wantEnabled = toggleInput.checked;
    toggleInput.disabled = true;
    try {
      const updated = await api.toggleMod(directory, mod.path, wantEnabled);
      mod.enabled = updated && typeof updated.enabled === 'boolean' ? updated.enabled : wantEnabled;
      if (updated && updated.path) {
        mod.path = updated.path;
        card.dataset.path = updated.path;
        toggleInput.dataset.path = updated.path;
        card.querySelector('.btn-delete-mod').dataset.path = updated.path;
      }
      card.classList.toggle('disabled', !mod.enabled);
    } catch (e) {
      showToast(String(e), 'error');
      toggleInput.checked = !wantEnabled;
    } finally {
      toggleInput.disabled = false;
    }
  });

  card.querySelector('.btn-delete-mod').addEventListener('click', async (ev) => {
    ev.stopPropagation();
    if (!confirm('Delete this mod?')) return;
    try {
      await api.deleteMod(directory, card.dataset.path);
      card.remove();
      updateModsCount();
      updateDeleteSelectedState();
      showToast('Mod deleted', 'success');
    } catch (e) { showToast(String(e), 'error'); }
  });
}

function unloadModCardContent(card) {
  if (!card._isRendered) return;
  const iconEl = card.querySelector('.mod-icon');
  if (iconEl && !iconEl.classList.contains('loading')) {
    card._preservedIconHtml = iconEl.innerHTML;
  }
  card._isRendered = false;
  card.classList.add('is-unloaded');
  card.innerHTML = '';
}

function createVirtualModCard(mod, directory, preservedIconHtml) {
  const card = document.createElement('div');
  card.className = 'glass-card mod-card is-unloaded' + (!mod.enabled ? ' disabled' : '');
  card.dataset.path = mod.path || '';
  card.dataset.name = (mod.name || mod.file_name || '').toLowerCase();
  card._mod = mod;
  card._directory = directory;
  card._preservedIconHtml = preservedIconHtml;
  card._isRendered = false;

  card.addEventListener('click', (e) => {
    if (e.target.closest('.mod-actions')) return;
    card.classList.toggle('selected');
    updateDeleteSelectedState();
  });

  getModVirtualObserver().observe(card);
  return card;
}

function refreshModsVirtualCards() {
  const grid = document.getElementById('mods-grid');
  if (grid && modVirtualObserver) {
    grid.querySelectorAll('.mod-card').forEach(card => {
      modVirtualObserver.unobserve(card);
      modVirtualObserver.observe(card);
    });
  }
}

function unloadAllModsVirtualCards() {
  const grid = document.getElementById('mods-grid');
  if (grid) {
    grid.querySelectorAll('.mod-card').forEach(card => {
      unloadModCardContent(card);
    });
  }
}

function buildModCard(mod, directory, preservedIconHtml) {
  return createVirtualModCard(mod, directory, preservedIconHtml);
}

function updateDeleteSelectedState() {
  const grid = document.getElementById('mods-grid');
  const deleteSelectedBtn = document.getElementById('btn-delete-selected-mods');
  if (!grid || !deleteSelectedBtn) return;
  const selected = grid.querySelectorAll('.mod-card.selected').length;
  deleteSelectedBtn.classList.toggle('hidden', selected === 0);
  deleteSelectedBtn.textContent = `Delete Selected (${selected})`;
}

function updateModsCount() {
  const grid = document.getElementById('mods-grid');
  const countEl = document.getElementById('mods-count');
  if (!grid || !countEl) return;
  const total = grid.querySelectorAll('.mod-card').length;
  const visible = grid.querySelectorAll('.mod-card:not(.search-hidden)').length;
  const targetInstance = getModsTargetInstance();
  const label = targetInstance ? ` for ${targetInstance.name || targetInstance.version_id}` : '';
  const countText = total === 0
    ? '0 mods' + label
    : `${visible === total ? total : visible + ' / ' + total} mod${total !== 1 ? 's' : ''}${label}`;
  countEl.textContent = countText;
}

function filterMods() {
  const grid = document.getElementById('mods-grid');
  const searchInput = document.getElementById('mods-search');
  if (!grid || !searchInput) return;
  const query = searchInput.value.trim().toLowerCase();
  grid.querySelectorAll('.mod-card').forEach(card => {
    const matches = !query || (card.dataset.name || '').includes(query);
    card.classList.toggle('search-hidden', !matches);
    card.style.display = matches ? '' : 'none';
  });
  updateModsCount();
}

let modsTabLoadingHideTimer = null;
function showModsTabLoading() {
  const overlay = document.getElementById('mods-tab-loading');
  if (!overlay) return;
  if (modsTabLoadingHideTimer) { clearTimeout(modsTabLoadingHideTimer); modsTabLoadingHideTimer = null; }
  overlay.setAttribute('aria-hidden', 'false');
  overlay.classList.add('visible');
}
function hideModsTabLoading() {
  const overlay = document.getElementById('mods-tab-loading');
  if (!overlay) return;
  // Small minimum-visible delay so a near-instant load doesn't flash the
  // spinner in and out — feels calmer than an abrupt appear/disappear.
  modsTabLoadingHideTimer = setTimeout(() => {
    overlay.classList.remove('visible');
    overlay.setAttribute('aria-hidden', 'true');
  }, 120);
}

async function loadMods() {
  if (!settings) return;
  const grid = document.getElementById('mods-grid');
  const countEl = document.getElementById('mods-count');
  const deleteSelectedBtn = document.getElementById('btn-delete-selected-mods');

  const isFirstLoad = grid.children.length === 0;
  if (isFirstLoad) grid.innerHTML = '<div class="empty-state"><span>Loading mods…</span></div>';

  const targetInstance = getModsTargetInstance();
  const directory = targetInstance ? (targetInstance.directory || settings.game_directory) : settings.game_directory;
  modUpdateInfo = modUpdateInfoByDir.get(modsCacheKey(targetInstance, directory)) || new Map();
  refreshUpdateAllButtonState();

  const preservedIcons = new Map();
  grid.querySelectorAll('.mod-card').forEach(card => {
    const path = card.dataset.path;
    const iconEl = card.querySelector('.mod-icon');
    if (path && iconEl && !iconEl.classList.contains('loading')) {
      preservedIcons.set(path, iconEl.innerHTML);
    } else if (path && card._preservedIconHtml) {
      preservedIcons.set(path, card._preservedIconHtml);
    }
  });

  try {
    const mods = await api.listMods(directory);
    const frag = document.createDocumentFragment();
    if (mods.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.innerHTML = `<span class="empty-icon">${ICON_EMPTY_BOX_SVG}</span><span>No mods found</span>`;
      frag.appendChild(empty);
    } else {
      mods.forEach(mod => frag.appendChild(createVirtualModCard(mod, directory, preservedIcons.get(mod.path))));
    }
    grid.innerHTML = '';
    grid.appendChild(frag);

    if (deleteSelectedBtn) deleteSelectedBtn.classList.toggle('hidden', mods.length === 0);
    updateModsCount();
    filterMods();
  } catch (e) {
    grid.innerHTML = `<div class="empty-state"><span style="color:var(--danger)">${e}</span></div>`;
    if (countEl) countEl.textContent = '';
    if (deleteSelectedBtn) deleteSelectedBtn.classList.add('hidden');
  }
}

function initMods() {
  const modsInstanceSel = document.getElementById('mods-instance-select');
  if (modsInstanceSel) {
    modsInstanceSel.addEventListener('change', (event) => {
      const selected = event.target.value;
      if (selected && selected !== selectedInstanceId) {
        selectInstance(selected);
      } else {
        loadMods();
      }
      checkSelectedInstanceForUpdates().catch(e => console.error('Update check failed', e));
    });
  }
  const searchInput = document.getElementById('mods-search');
  if (searchInput) {
    searchInput.addEventListener('input', filterMods);
  }
  document.getElementById('btn-open-mods').addEventListener('click', async () => {
    if (!settings) return;
    const directory = getModsTargetDirectory();
    try { await api.openModsFolder(directory); }
    catch (e) { showToast('Failed to open folder', 'error'); }
  });

  const exportModsBtn = document.getElementById('btn-export-mods');
  if (exportModsBtn) exportModsBtn.addEventListener('click', () => openExportModsOverlay());

  const importModsBtn = document.getElementById('btn-import-mods');
  if (importModsBtn) importModsBtn.addEventListener('click', () => startImportMods());

  const deleteSelectedBtn = document.getElementById('btn-delete-selected-mods');
  if (deleteSelectedBtn) {
    deleteSelectedBtn.addEventListener('click', async () => {
      const selected = Array.from(document.querySelectorAll('.mod-card.selected'));
      if (selected.length === 0) return;
      if (!confirm(`Delete ${selected.length} selected mod(s)?`)) return;
      const paths = selected.map(card => card.dataset.path).filter(Boolean);
      try {
        await Promise.all(paths.map(path => api.deleteMod(path)));
        await loadMods();
        showToast(`Deleted ${paths.length} mod(s)`, 'success');
      } catch (e) {
        showToast(String(e), 'error');
      }
    });
  }

  // Replaces the old manual Refresh button — the mods list now keeps
  // itself up to date on its own while the Mods tab is open.
  setInterval(() => {
    if (getActiveTabId() === 'mods') loadMods();
  }, MODS_AUTO_REFRESH_MS);

  initModsDragDrop();
}

// ── Drag & drop / browse-to-install mods ────────────────────────────────
// Every dropped or browsed path is sent to the backend, which opens each
// jar as a zip and only accepts it if it actually contains a Fabric,
// Quilt, or Forge mod manifest — junk files are rejected before anything
// touches disk.
async function installDroppedModPaths(paths) {
  if (!settings || !paths || paths.length === 0) return;
  const directory = getModsTargetDirectory();
  if (!directory) {
    showToast('Select an instance before adding mods', 'warning');
    return;
  }

  const jarPaths = paths.filter(p => p.toLowerCase().endsWith('.jar'));
  const rejectedUpfront = paths.length - jarPaths.length;
  if (jarPaths.length === 0) {
    showToast('Only .jar files can be added as mods', 'error');
    return;
  }

  try {
    const results = await api.installModFiles(jarPaths, directory);
    const installed = results.filter(r => r.success);
    const rejected = results.filter(r => !r.success);

    if (installed.length > 0) {
      await loadMods();
      // Give freshly-installed cards a brief highlight so it's obvious
      // which ones just landed, especially when several were dropped at once.
      const installedPaths = new Set(installed.map(r => r.modInfo?.path || r.mod_info?.path).filter(Boolean));
      document.querySelectorAll('.mod-card').forEach(card => {
        if (installedPaths.has(card.dataset.path)) {
          card.classList.add('mod-just-installed');
          setTimeout(() => card.classList.remove('mod-just-installed'), 1400);
        }
      });
      const names = installed.map(r => (r.modInfo || r.mod_info)?.name || r.sourceName || r.source_name).join(', ');
      showToast(
        installed.length === 1 ? `Added ${names}` : `Added ${installed.length} mods: ${names}`,
        'success'
      );
    }

    if (rejected.length > 0) {
      const lines = rejected.map(r => `${r.sourceName || r.source_name} — ${r.reason || 'not a recognized mod'}`);
      showToast(
        rejected.length === 1
          ? lines[0]
          : `${rejected.length} file(s) skipped:\n${lines.join('\n')}`,
        'error',
        rejected.length === 1 ? 'Not a mod' : 'Some files skipped'
      );
    }

    if (rejectedUpfront > 0) {
      showToast(`Ignored ${rejectedUpfront} non-.jar file(s)`, 'warning');
    }
  } catch (e) {
    showToast('Failed to install mod(s): ' + e, 'error');
  }
}

function initModsDragDrop() {
  const overlay = document.getElementById('mods-drag-overlay');
  const modpackOverlay = document.getElementById('modpack-drag-overlay');

  // The webview's native browser drag/drop behavior (opening the dropped
  // file as if it were navigated to) fights with Tauri's own drag-drop
  // handling and can take the whole window down. Swallow it everywhere,
  // unconditionally, before Tauri's handling ever runs.
  ['dragover', 'drop'].forEach(evt => {
    window.addEventListener(evt, (e) => e.preventDefault());
  });

  // Tauri emits window-level drag events with the full OS file paths —
  // no browser File objects involved, so this works for arbitrarily large
  // jars/archives without reading them into memory on the frontend at all.
  //
  // Which overlay lights up while the mouse hovers depends on what's being
  // dragged (Tauri v2 includes `paths` on drag-enter/drag-over, not just
  // on drop): a .zip/.mrpack always shows the modpack extractor prompt,
  // regardless of which tab is open; a .jar only shows the mods-tab
  // overlay, and only while that tab is active.
  let dragActive = false;
  const dragIsModpack = (paths) => (paths || []).some(p => isModpackFile(p));
  const setDragActive = (on, paths) => {
    dragActive = on;
    const modpack = on && dragIsModpack(paths);
    if (modpackOverlay) modpackOverlay.classList.toggle('active', modpack);
    if (overlay) overlay.classList.toggle('active', on && !modpack && getActiveTabId() === 'mods');
  };

  listen('tauri://drag-enter', (e) => setDragActive(true, e && e.payload && e.payload.paths));
  listen('tauri://drag-over', (e) => setDragActive(true, e && e.payload && e.payload.paths));
  listen('tauri://drag-leave', () => setDragActive(false));
  listen('tauri://drag-drop', (event) => {
    setDragActive(false);
    const paths = (event && event.payload && event.payload.paths) || [];

    const modpackPaths = paths.filter(isModpackFile);
    if (modpackPaths.length > 0) {
      // A modpack archive takes priority even if other files were dropped
      // alongside it — only the first one is imported at a time.
      openModpackImportOverlay(modpackPaths[0]);
      return;
    }

    if (getActiveTabId() !== 'mods') {
      if (paths.some(p => p.toLowerCase().endsWith('.jar'))) {
        showToast('Switch to the Mods tab to drop mod files', 'info');
      }
      return;
    }
    installDroppedModPaths(paths);
  });
}

// ── Modpack Extractor (drag/drop a .mrpack or CurseForge/generic .zip) ────
// Reads the pack's manifest (Modrinth `modrinth.index.json` or CurseForge
// `manifest.json`), installs the right Minecraft version + loader exactly
// like a normal "Create Instance" would, then lays the pack's own mods,
// config, resourcepacks, and saves on top of that instance's directory.
function isModpackFile(path) {
  const lower = (path || '').toLowerCase();
  return lower.endsWith('.mrpack') || lower.endsWith('.zip');
}

let modpackImportState = null; // { filePath, preview }
// True only while api.importModpack() is actually in flight — lets
// closeModpackImportOverlay() tell "closed while extracting" (minimize to
// the hidden-windows tray) apart from "closed because it's done"
// (nothing to reopen).
let modpackImportTaskRunning = false;
// This extractor's own card id in the downloads widget — gets exactly the
// same treatment (real byte-level percent, speed/ETA, per-file Files/log
// window) as the instance-install card, since the backend now downloads
// pack files through the same parallel downloader "Create Instance" uses.
const MODPACK_IMPORT_CARD_ID = '__modpack-import__';

async function openModpackImportOverlay(filePath) {
  const overlayEl = document.getElementById('modpack-import-overlay');
  const summaryText = document.getElementById('modpack-import-summary-text');
  const nameInput = document.getElementById('modpack-inst-name');
  const progressWrap = document.getElementById('modpack-import-progress-wrap');
  const confirmBtn = document.getElementById('btn-confirm-modpack-import');
  if (!overlayEl) return;

  modpackImportState = { filePath, preview: null };
  nameInput.value = '';
  progressWrap.classList.add('hidden');
  confirmBtn.disabled = false;
  summaryText.textContent = 'Reading modpack…';
  document.getElementById('modpack-dir-separated').checked = true;
  document.getElementById('modpack-dir-custom').checked = false;
  document.getElementById('modpack-dir-path').value = '';
  document.getElementById('modpack-dir-path-row').classList.add('hidden');
  overlayEl.classList.remove('hidden');

  try {
    const preview = await api.previewModpack(filePath);
    modpackImportState.preview = preview;
    const fileName = filePath.split(/[\\/]/).pop();
    const guessedName = preview.name || fileName.replace(/\.(mrpack|zip)$/i, '');
    nameInput.value = guessedName;

    if (preview.format === 'generic') {
      summaryText.textContent = `${fileName} — no modpack manifest found (plain .zip). Only recognized folders (mods, config, resourcepacks, saves) will be extracted; you'll need to set the Minecraft version/loader yourself afterward.`;
    } else {
      const loaderPart = preview.loader && preview.loader !== 'vanilla'
        ? `${loaderLabel(preview.loader)}${preview.loader_version ? ' ' + preview.loader_version : ''}`
        : 'Vanilla';
      const formatLabel = preview.format === 'mrpack' ? 'Modrinth' : 'CurseForge';
      summaryText.textContent = `${formatLabel} modpack — ${preview.minecraft_version || '?'} · ${loaderPart} · ${preview.file_count} file(s)`;
    }
  } catch (e) {
    summaryText.textContent = 'Could not read this file: ' + e;
    confirmBtn.disabled = true;
  }
}

function closeModpackImportOverlay() {
  document.getElementById('modpack-import-overlay').classList.add('hidden');
  if (modpackImportTaskRunning) {
    // Extraction/install is still running in the background — keep the
    // window reachable from the hidden-windows tray instead of losing it.
    if (window.hwMinimize) window.hwMinimize('modpack-import-overlay', 'Modpack Extractor');
  } else {
    // Nothing running (never started, or already finished) — make sure
    // there's no stale tray entry left over from an earlier minimize.
    if (window.hwDone) window.hwDone('modpack-import-overlay');
  }
  modpackImportState = null;
}

async function confirmModpackImport() {
  if (!modpackImportState) return;
  const nameInput = document.getElementById('modpack-inst-name');
  const name = nameInput.value.trim();
  if (!name) { showToast('Instance name is required', 'error'); return; }

  const useCustomDir = document.getElementById('modpack-dir-custom').checked;
  const customDir = document.getElementById('modpack-dir-path').value.trim();
  if (useCustomDir && !customDir) { showToast('Choose a custom directory', 'error'); return; }

  const confirmBtn = document.getElementById('btn-confirm-modpack-import');
  const progressWrap = document.getElementById('modpack-import-progress-wrap');
  const statusEl = document.getElementById('modpack-import-status');
  const barEl = document.getElementById('modpack-import-bar');
  confirmBtn.disabled = true;
  progressWrap.classList.remove('hidden');
  statusEl.textContent = 'Starting…';
  barEl.style.transform = 'scaleX(0)';

  // Minimize/hide the modpack extractor to the hidden windows tray immediately
  modpackImportTaskRunning = true;
  document.getElementById('modpack-import-overlay').classList.add('hidden');
  if (window.hwMinimize) window.hwMinimize('modpack-import-overlay', 'Modpack Extractor');

  const loader = modpackImportState.preview && modpackImportState.preview.loader;
  const zlibOk = await confirmZlibIfConflict(loader);
  if (!zlibOk) {
    modpackImportTaskRunning = false;
    if (window.hwDone) window.hwDone('modpack-import-overlay');
    confirmBtn.disabled = false;
    showToast('Modpack installation cancelled', 'info');
    return;
  }

  showToast(`Importing modpack "${name}"…`, 'info');

  // Give this extractor run its own card in the downloads widget, exactly
  // like kicking off an instance install does — same real percent/speed/
  // ETA, and a "Files" button opening the same live per-file log/status
  // view (this *is* the "log inspect" — every file the backend's real
  // downloader touches shows up here as it's reached, not a static line).
  if (dlWidgetGeneric) {
    dlWidgetGeneric.begin(MODPACK_IMPORT_CARD_ID, `Modpack: ${name}`, 'Reading modpack…', {
      determinate: true,
      withStats: true,
      noCancel: true, // extraction isn't cancellable mid-run yet; use the window's own Cancel to minimize it instead
      icon: 'mod',
    });
  }

  const unlisten = await api.onModpackImportProgress((e) => {
    const p = e.payload || {};
    const pct = Math.max(0, Math.min(100, p.percent || 0));
    statusEl.textContent = p.message || p.stage || '';
    barEl.style.transform = `scaleX(${pct / 100})`;

    if (!dlWidgetGeneric) return;
    const activeFiles = p.active_files || [];
    const fileLabel = activeFiles.length === 0
      ? ''
      : activeFiles.length === 1
        ? activeFiles[0].name
        : `${activeFiles[0].name} +${activeFiles.length - 1} more`;
    dlWidgetGeneric.update(
      MODPACK_IMPORT_CARD_ID,
      `Modpack: ${name}`,
      p.message || p.stage || '',
      pct,
      {
        file: fileLabel,
        speed: p.speed_bps ? fmtSpeed(p.speed_bps) : '—',
        eta: p.eta_seconds != null ? fmtEta(p.eta_seconds) : '—',
        downloaded: p.downloaded_bytes ? fmtBytes(p.downloaded_bytes) : '—',
      }
    );
    // Real concurrent per-file breakdown — same reconciliation the
    // instance-install card uses, so "Files" here shows exactly what the
    // downloader is doing right now instead of a single static status.
    if (p.stage === 'downloading') {
      dlWidgetGeneric.reconcileActiveFiles(MODPACK_IMPORT_CARD_ID, activeFiles);
    }
  });

  try {
    const result = await api.importModpack(
      modpackImportState.filePath,
      name,
      useCustomDir,
      useCustomDir ? customDir : null
    );
    modpackImportTaskRunning = false;
    closeModpackImportOverlay();
    await refreshInstances();
    renderInstanceList();
    let msg = `Imported "${name}"`;
    if (result.failed_files && result.failed_files.length > 0) {
      msg += ` — ${result.failed_files.length} file(s) failed to download`;
    }
    if (result.unresolved_curseforge_mods) {
      msg += ` — ${result.unresolved_curseforge_mods} CurseForge mod(s) need to be added manually (via Discover)`;
    }
    showToast(msg, result.failed_files && result.failed_files.length > 0 ? 'warning' : 'success');
    if (dlWidgetGeneric) {
      dlWidgetGeneric.end(MODPACK_IMPORT_CARD_ID, true, `Imported "${name}"`);
    }
  } catch (e) {
    modpackImportTaskRunning = false;
    // Extraction failed — leave the window's own error state as-is for
    // whenever it's reopened, but there's nothing running anymore, so
    // drop it from the hidden-windows tray if it was minimized there.
    if (window.hwDone) window.hwDone('modpack-import-overlay');
    statusEl.textContent = 'Failed: ' + e;
    showToast('Failed to import modpack: ' + e, 'error');
    confirmBtn.disabled = false;
    if (dlWidgetGeneric) {
      dlWidgetGeneric.end(MODPACK_IMPORT_CARD_ID, false, 'Import failed');
    }
  } finally {
    if (typeof unlisten === 'function') unlisten();
  }
}

function initModpackImportOverlay() {
  const overlayEl = document.getElementById('modpack-import-overlay');
  if (!overlayEl) return;

  document.getElementById('btn-cancel-modpack-import').addEventListener('click', closeModpackImportOverlay);
  document.getElementById('btn-cancel-modpack-import-form').addEventListener('click', closeModpackImportOverlay);
  document.getElementById('btn-confirm-modpack-import').addEventListener('click', confirmModpackImport);

  const dirCustomRadio = document.getElementById('modpack-dir-custom');
  const dirSeparatedRadio = document.getElementById('modpack-dir-separated');
  const dirPathRow = document.getElementById('modpack-dir-path-row');
  const dirPathInput = document.getElementById('modpack-dir-path');
  const dirBrowseBtn = document.getElementById('modpack-dir-browse');

  const syncDirRowVisibility = () => dirPathRow.classList.toggle('hidden', !dirCustomRadio.checked);
  dirCustomRadio.addEventListener('change', syncDirRowVisibility);
  dirSeparatedRadio.addEventListener('change', syncDirRowVisibility);

  dirBrowseBtn.addEventListener('click', async () => {
    try {
      const picked = await window.__TAURI__.dialog.open({ directory: true, multiple: false });
      if (picked) {
        dirPathInput.value = Array.isArray(picked) ? picked[0] : picked;
        dirCustomRadio.checked = true;
        syncDirRowVisibility();
      }
    } catch (e) {
      showToast('Could not open folder picker: ' + e, 'error');
    }
  });
}

// ── Discover → "Install in custom directory" mini modal ───────────────────
// Opened from a modpack card's 3-dot menu. Just a directory field — the
// pack's own name is used as the instance name, same as a plain Download
// click, so there's no separate name/summary step to confirm here.
let discoverModpackDirState = null; // { hit, opt, downloadBtn }

function openDiscoverModpackDirOverlay(hit, versionSelect, downloadBtn) {
  const overlayEl = document.getElementById('discover-modpack-dir-overlay');
  if (!overlayEl) return;

  discoverModpackDirState = { hit, versionSelect, downloadBtn };
  document.getElementById('discover-modpack-dir-name').textContent = hit.title || '';
  document.getElementById('discover-modpack-dir-path').value = '';
  document.getElementById('discover-modpack-dir-progress-wrap').classList.add('hidden');
  document.getElementById('btn-confirm-discover-modpack-dir').disabled = false;
  overlayEl.classList.remove('hidden');
}

function closeDiscoverModpackDirOverlay() {
  document.getElementById('discover-modpack-dir-overlay').classList.add('hidden');
  discoverModpackDirState = null;
}

async function confirmDiscoverModpackDirInstall() {
  if (!discoverModpackDirState) return;
  const dirInput = document.getElementById('discover-modpack-dir-path');
  const customDir = dirInput.value.trim();
  if (!customDir) { showToast('Choose a custom directory', 'error'); return; }

  const { hit, versionSelect, downloadBtn } = discoverModpackDirState;
  const confirmBtn = document.getElementById('btn-confirm-discover-modpack-dir');
  const progressWrap = document.getElementById('discover-modpack-dir-progress-wrap');
  const statusEl = document.getElementById('discover-modpack-dir-status');
  const barEl = document.getElementById('discover-modpack-dir-bar');

  const target = currentDiscoverTargetInstance();
  const opt = await resolveDiscoverDownloadOption(hit, versionSelect, null, target);
  if (!opt) return;

  confirmBtn.disabled = true;
  progressWrap.classList.remove('hidden');
  statusEl.textContent = 'Starting…';
  barEl.style.transform = 'scaleX(0)';

  // Mirror the modpack extractor's own progress bar while this mini modal
  // is open, in addition to the shared downloads-widget card — closing the
  // modal (below) doesn't cancel the install, it just stops watching it.
  const unlisten = await api.onModpackImportProgress((e) => {
    const p = e.payload || {};
    const pct = Math.max(0, Math.min(100, p.percent || 0));
    statusEl.textContent = p.message || p.stage || '';
    barEl.style.transform = `scaleX(${pct / 100})`;
  });

  try {
    await installDiscoverModpack(hit, opt, downloadBtn, customDir);
  } finally {
    if (typeof unlisten === 'function') unlisten();
    closeDiscoverModpackDirOverlay();
  }
}

function initDiscoverModpackDirOverlay() {
  const overlayEl = document.getElementById('discover-modpack-dir-overlay');
  if (!overlayEl) return;

  document.getElementById('btn-cancel-discover-modpack-dir').addEventListener('click', closeDiscoverModpackDirOverlay);
  document.getElementById('btn-cancel-discover-modpack-dir-form').addEventListener('click', closeDiscoverModpackDirOverlay);
  document.getElementById('btn-confirm-discover-modpack-dir').addEventListener('click', confirmDiscoverModpackDirInstall);

  document.getElementById('discover-modpack-dir-browse').addEventListener('click', async () => {
    try {
      const picked = await window.__TAURI__.dialog.open({ directory: true, multiple: false });
      if (picked) {
        document.getElementById('discover-modpack-dir-path').value = Array.isArray(picked) ? picked[0] : picked;
      }
    } catch (e) {
      showToast('Could not open folder picker: ' + e, 'error');
    }
  });
}

// Reads the user's configured download concurrency (Settings → Performance
// & Java → Concurrent Downloads). Automatic mode uses 3, matching the
// backend's own default; manual mode is clamped to 1-16 the same way
// collectSettingsFromUI() clamps it when saving.
function getDownloadConcurrency() {
  if (!settings || settings.download_threads_auto !== false) return 3;
  const n = parseInt(settings.download_threads, 10);
  if (!Number.isFinite(n)) return 3;
  return Math.min(16, Math.max(1, n));
}

// Runs `worker(item, index)` over `items` with at most `concurrency` calls
// in flight at once, and resolves once every item has settled (success or
// failure — a failing worker should catch its own error if the caller needs
// to keep going, same as the sequential `for` loops this replaces). Order of
// completion isn't preserved, but each worker still knows its own index.
async function runWithConcurrency(items, concurrency, worker) {
  if (!items || items.length === 0) return;
  const limit = Math.max(1, Math.min(concurrency || 1, items.length));
  let nextIndex = 0;
  async function runNext() {
    while (nextIndex < items.length) {
      const i = nextIndex++;
      try {
        await worker(items[i], i);
      } catch (e) {
        // Workers are expected to handle their own errors (as the existing
        // sequential loops did); swallow here only as a last-resort guard
        // so one rejected promise can't stop the rest of the pool.
        console.error('Concurrent download task failed', e);
      }
    }
  }
  const runners = Array.from({ length: limit }, () => runNext());
  await Promise.all(runners);
}

// ── Mods toolbar actions (Check / Update / Fix) ─────────────────────────
// Thin wrapper around api.discoverDownload that also records the file in
// the download widget's per-card Files list (when a dlId + widget exist),
// so every process that downloads mods/files through here gets that
// breakdown for free without each call site managing it individually.
async function trackedDiscoverDownload(directory, projectType, fileUrl, fileName, dlId, displayName) {
  const trackedName = displayName || fileName;
  if (dlId && dlWidgetGeneric && dlWidgetGeneric.fileStart) dlWidgetGeneric.fileStart(dlId, trackedName);
  try {
    const result = await api.discoverDownload(directory, projectType, fileUrl, fileName, dlId);
    if (dlId && dlWidgetGeneric && dlWidgetGeneric.fileDone) dlWidgetGeneric.fileDone(dlId, trackedName, true);
    return result;
  } catch (e) {
    if (dlId && dlWidgetGeneric && dlWidgetGeneric.fileDone) dlWidgetGeneric.fileDone(dlId, trackedName, false);
    throw e;
  }
}

async function gatherModsForDirectory(directory) {
  try {
    return await api.listMods(directory);
  } catch (e) {
    showToast('Failed to list mods: ' + e, 'error');
    return [];
  }
}

// After downloading a mod's new version, the old jar is still sitting in the
// mods folder under its old filename (a new download doesn't overwrite it
// unless the filename happens to match exactly) — this removes it so an
// "update" doesn't just leave both versions installed side by side.
async function deleteOldModFileIfReplaced(directory, oldPath, newFilename) {
  if (!oldPath) return;
  const oldFilename = oldPath.split(/[/\\]/).pop();
  if (oldFilename === newFilename) return; // same file, nothing stale to remove
  try {
    await api.deleteMod(directory, oldPath);
  } catch (e) {
    console.error('Failed to remove old mod version', oldPath, e);
  }
}

// Identifies each installed mod by the SHA-1 hash of its jar (the same
// method Modrinth's own /version_files endpoint is built for, and what
// identify_mods_by_hash already uses for icons) rather than a fuzzy
// name search. This matters for two reasons that were previously causing
// real bugs:
//   1. A text search for "mod.name" can land on the wrong Modrinth project
//      (or the right project's page ranked below an unrelated one with a
//      similar name), so the "latest version" being compared against
//      wasn't necessarily even the same mod that's installed.
//   2. Comparing that possibly-wrong "latest" against the version string
//      baked into the jar's own metadata (fabric.mod.json / mods.toml) is
//      unreliable on its own too — some builds ship literal placeholders
//      like "${version}" or inconsistent "v" prefixes there.
// Hash identification pins the exact installed file to its exact Modrinth
// project + version, so the comparison is between two values Modrinth
// itself reports for that project — which is what actually fixes updated
// mods still showing as outdated on the next check.
async function checkUpdatesForMods(directory, instance) {
  const mods = await gatherModsForDirectory(directory);
  const withHash = mods.filter(m => m.sha1);
  if (withHash.length === 0) return [];

  const loaderFilter = instance && instance.loader ? instance.loader.toLowerCase() : null;
  const gameVersion = instance && instance.minecraft_version ? instance.minecraft_version : null;

  let lookup;
  try {
    lookup = await api.identifyModsByHash(withHash.map(m => m.sha1));
  } catch (e) {
    console.error('Hash identification failed', e);
    return [];
  }

  const updatable = [];
  for (const mod of withHash) {
    const match = lookup[mod.sha1];
    // No match means Modrinth doesn't recognize this exact file (not on
    // Modrinth, or a build we can't confidently pin) — skip rather than
    // fall back to a guess that could point at the wrong project.
    if (!match || !match.project_id) continue;
    try {
      const versions = await api.discoverGetVersions(match.project_id, loaderFilter, gameVersion);
      if (!versions || versions.length === 0) continue;
      const latest = versions[0];
      const primary = (latest.files && latest.files.find(f => f.primary)) || (latest.files && latest.files[0]);
      if (!primary) continue;
      const installedVersionNumber = String(match.version_number || '').trim();
      const latestVersionNumber = String(latest.version_number || '').trim();
      if (installedVersionNumber !== latestVersionNumber) {
        updatable.push({ mod, project: { project_id: match.project_id }, latest, file: primary });
      }
    } catch (e) {
      // ignore per-mod errors
    }
  }
  return updatable;
}

// Strips version numbers / mc-version / loader tags so "sodium-fabric-mc1.20.1-0.5.8.jar"
// and "sodium-0.5.9.jar" normalize to the same key for dedupe grouping.
function normalizeModKey(name) {
  return String(name || '')
    .toLowerCase()
    .replace(/\.jar(\.disabled)?$/, '')
    .replace(/\b(fabric|forge|neoforge|quilt)\b/g, '')
    .replace(/\bmc ?1\.\d+(\.\d+)?\b/g, '')
    .replace(/v?\d+(\.\d+){1,3}[a-z0-9-]*/gi, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

// Best-effort semantic-ish version compare: higher numeric segments win,
// falling back to string comparison for non-numeric version strings.
function compareModVersions(a, b) {
  const pa = String(a || '').match(/\d+/g);
  const pb = String(b || '').match(/\d+/g);
  if (pa && pb) {
    const len = Math.max(pa.length, pb.length);
    for (let i = 0; i < len; i++) {
      const na = parseInt(pa[i] || '0', 10);
      const nb = parseInt(pb[i] || '0', 10);
      if (na !== nb) return na - nb;
    }
    return 0;
  }
  return String(a || '').localeCompare(String(b || ''));
}

// Groups installed mods with matching normalized names, keeps the newest
// version enabled, and deletes the rest.
async function dedupeMods(directory) {
  const mods = await gatherModsForDirectory(directory);
  const groups = new Map();
  for (const mod of mods) {
    if (!mod.enabled) continue; // don't touch mods already disabled
    const key = normalizeModKey(mod.name || mod.file_name);
    if (!key) continue;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(mod);
  }

  let deletedCount = 0;
  for (const group of groups.values()) {
    if (group.length < 2) continue;
    group.sort((a, b) => compareModVersions(a.version, b.version));
    const keep = group[group.length - 1];
    for (const mod of group) {
      if (mod === keep) continue;
      try {
        await api.deleteMod(directory, mod.path);
        deletedCount++;
      } catch (e) {
        console.error('Failed to delete duplicate mod', mod.file_name, e);
      }
    }
  }
  return deletedCount;
}

// For each installed mod, resolve it on Modrinth, look at its best-matching
// version's required dependencies, and download any that aren't already
// present in the mods folder.
async function installMissingDependencies(directory, instance, downloadId) {
  const mods = await gatherModsForDirectory(directory);
  const loaderFilter = instance && instance.loader ? instance.loader.toLowerCase() : null;
  const gameVersion = instance && instance.minecraft_version ? instance.minecraft_version : null;
  const installedKeys = new Set(mods.map(m => normalizeModKey(m.name || m.file_name)));

  let installedCount = 0;
  const alreadyQueued = new Set();

  const cancelled = () => downloadId && dlWidgetGeneric && dlWidgetGeneric.isCancelled(downloadId);

  // Resolving each mod's dependencies (and downloading any missing ones)
  // is independent per mod, so several mods are processed at once
  // (Settings → Performance & Java → Concurrent Downloads, default 3)
  // instead of strictly one-at-a-time. `alreadyQueued`'s check-then-add is
  // still safe here even though several workers share it: JS never
  // preempts between the `.has()` check and the `.add()` call below since
  // there's no `await` between them, so two mods can never both start
  // downloading the same shared dependency.
  await runWithConcurrency(mods, getDownloadConcurrency(), async (mod) => {
    if (cancelled()) return;
    try {
      const res = await api.discoverSearch(mod.name || '', 'mod', loaderFilter, gameVersion, null, null, null, false, 1, 1);
      if (!res || !res.hits || res.hits.length === 0) return;
      const hit = res.hits[0];
      const versions = await api.discoverGetVersions(hit.project_id, loaderFilter, gameVersion);
      if (!versions || versions.length === 0) return;
      const latest = versions[0];
      const required = (latest.dependencies || []).filter(d => d.dependency_type === 'required' && d.project_id);

      for (const dep of required) {
        if (cancelled()) break;
        if (alreadyQueued.has(dep.project_id)) continue;
        alreadyQueued.add(dep.project_id);

        let depProject;
        try {
          depProject = await api.discoverGetProject(dep.project_id);
        } catch (e) {
          continue; // couldn't resolve this dependency, skip it
        }
        const depKey = normalizeModKey(depProject.title);
        if (installedKeys.has(depKey)) continue; // already have it

        const depVersions = await api.discoverGetVersions(dep.project_id, loaderFilter, gameVersion);
        if (!depVersions || depVersions.length === 0) continue;
        const depLatest = depVersions[0];
        const primary = (depLatest.files || []).find(f => f.primary) || (depLatest.files || [])[0];
        if (!primary) continue;

        if (downloadId && dlWidgetGeneric) {
          dlWidgetGeneric.update(downloadId, undefined, `Downloading ${depProject.title}…`);
        }
        await trackedDiscoverDownload(directory, 'mod', primary.url, primary.filename, downloadId);
        installedKeys.add(depKey);
        installedCount++;
      }
    } catch (e) {
      console.error('Dependency check failed for', mod.name, e);
    }
  });
  return installedCount;
}

function openFixPanelRelative(btn, panel) {
  if (!btn || !panel) return;
  const rect = btn.getBoundingClientRect();
  panel.style.left = `${rect.left}px`;
  panel.style.top = `${rect.bottom + 8}px`;
}

// ── Fix Mods actions ──────────────────────────────────────────────
// Each of these is a standalone async function (used directly by its own
// toolbar button, and also chained together by "Fix Mods" below) so the
// primary Fix Mods action isn't just forwarding a click to another button
// — it used to `updateAllBtn.click()`, which silently did nothing whenever
// that button happened to be disabled (e.g. no update check had run yet).

async function runDisableIncompatible() {
  const target = getModsTargetInstance();
  if (!target) { showToast('No instance selected', 'warning'); return 0; }
  const directory = getModsTargetDirectory();
  const mods = await gatherModsForDirectory(directory);
  let disabled = 0;
  for (const mod of mods) {
    try {
      const modLoader = (mod.loader || '').toLowerCase();
      const instLoader = (target.loader || '').toLowerCase();
      if (!modLoader || !instLoader) continue;
      if (modLoader !== 'unknown' && instLoader !== '' && !modLoader.includes(instLoader)) {
        await api.toggleMod(directory, mod.path, false);
        disabled++;
      }
    } catch (e) { console.error(e); }
  }
  return disabled;
}

async function runInstallDeps(dlId) {
  const target = getModsTargetInstance();
  const directory = getModsTargetDirectory();
  return installMissingDependencies(directory, target, dlId);
}

async function runDedupe() {
  const directory = getModsTargetDirectory();
  return dedupeMods(directory);
}

async function runCheckUpdates() {
  const target = getModsTargetInstance();
  const directory = getModsTargetDirectory();
  return checkUpdatesForDirectory(directory, target);
}

async function runUpdateAll(up, dlId) {
  const directory = getModsTargetDirectory();
  const updateAllBtn = document.getElementById('btn-update-all-mods');
  const updateAllIcon = updateAllBtn && updateAllBtn.querySelector('.update-icon');
  if (updateAllBtn) updateAllBtn.classList.add('is-downloading');
  if (updateAllIcon) updateAllIcon.innerHTML = '<span class="mini-spinner"></span>';
  let ok = 0;
  try {
    for (let i = 0; i < up.length; i++) {
      if (dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId)) break;
      const u = up[i];
      const modLabel = (u.mod && (u.mod.name || u.mod.file_name)) || u.file.filename;
      if (dlWidgetGeneric) {
        dlWidgetGeneric.update(dlId, undefined, `Mod ${i + 1} of ${up.length}: ${modLabel}`, (i / up.length) * 100);
      }
      try {
        await trackedDiscoverDownload(directory, 'mod', u.file.url, u.file.filename, dlId, modLabel);
        await deleteOldModFileIfReplaced(directory, u.mod.path, u.file.filename);
        modUpdateInfo.delete(u.mod.path);
        ok++;
      } catch (e) {
        console.error('Download failed', e);
      }
    }
  } finally {
    if (updateAllBtn) updateAllBtn.classList.remove('is-downloading');
    if (updateAllIcon) updateAllIcon.innerHTML = DOWNLOAD_ICON_SVG;
  }
  return ok;
}

document.addEventListener('DOMContentLoaded', () => {
  const checkBtn = document.getElementById('btn-check-updates');
  const updateAllBtn = document.getElementById('btn-update-all-mods');
  const fixBtn = document.getElementById('btn-fix-mods');
  const fixInline = document.getElementById('fix-mods-inline');
  const fixInstallDeps = document.getElementById('fix-install-deps');
  const fixDedupe = document.getElementById('fix-dedupe');
  const fixDisableIncompatible = document.getElementById('fix-disable-incompatible');

  if (checkBtn) {
    checkBtn.addEventListener('click', async () => {
      if (modsUpdateCheckBusy) return;
      setUpdateButtonsBusy(true);
      showToast('Checking for updates…', 'info');
      try {
        const up = await runCheckUpdates();
        showToast(up.length === 0 ? 'No updates found' : `${up.length} update(s) available`, up.length === 0 ? 'success' : 'info');
        // Reload so each updatable mod's card shows its green Update button.
        await loadMods();
      } finally {
        setUpdateButtonsBusy(false);
      }
    });
  }

  if (updateAllBtn) {
    updateAllBtn.addEventListener('click', async () => {
      // Can't kick off Update All while a check (manual or the launch-time
      // automatic one) is still running — wait for it to finish first.
      if (modsUpdateCheckBusy) return;
      const target = getModsTargetInstance();
      const directory = getModsTargetDirectory();
      setUpdateButtonsBusy(true);
      try {
        // Reuse a check that already ran for this directory (manual "Check
        // Updates", the auto-check on selecting the instance, etc.) instead
        // of unconditionally re-scanning every mod again — that redundant
        // rescan was the "checks for updates even though I already checked"
        // behavior. Only run a fresh check if nothing's been checked yet.
        let up;
        const existing = modUpdateInfoByDir.get(modsCacheKey(target, directory));
        if (existing && existing.size > 0) {
          up = Array.from(existing.values());
        } else {
          showToast('Checking for updates…', 'info');
          up = await runCheckUpdates();
        }
        if (up.length === 0) {
          showToast('No updates found', 'success');
          return;
        }
        showToast(`Updating ${up.length} mod(s) (may take a while)...`, 'info');
        const dlId = genDlId('mods-update-all');
        if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, `Updating ${up.length} mod(s)…`, 'Starting…', { icon: 'update' });
        if (dlWidgetGeneric) dlWidgetGeneric.seedFiles(dlId, up.map(u => (u.mod && (u.mod.name || u.mod.file_name)) || u.file.filename));
        let ok = 0;
        try {
          ok = await runUpdateAll(up, dlId);
        } finally {
          if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, ok > 0, `Updated ${ok} of ${up.length} mod(s)`);
        }
        await loadMods();
        showToast(`Updated ${ok} of ${up.length} mod(s)`, ok > 0 ? 'success' : 'error');
      } finally {
        setUpdateButtonsBusy(false);
      }
    });
  }

  if (fixBtn && fixInline) {
    fixBtn.addEventListener('click', (e) => {
      const open = fixInline.classList.toggle('open');
      fixBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
      fixInline.setAttribute('aria-hidden', open ? 'false' : 'true');
    });
    document.addEventListener('click', (e) => {
      if (!fixInline.contains(e.target) && e.target !== fixBtn && e.target !== document.getElementById('btn-fix-mods-run')) {
        fixInline.classList.remove('open');
        fixBtn.setAttribute('aria-expanded', 'false');
        fixInline.setAttribute('aria-hidden', 'true');
      }
    });
  }

  if (fixDisableIncompatible) {
    fixDisableIncompatible.addEventListener('click', async () => {
      if (fixInline) { fixInline.classList.remove('open'); fixInline.setAttribute('aria-hidden', 'true'); }
      const disabled = await runDisableIncompatible();
      await loadMods();
      showToast(`Disabled ${disabled} incompatible mod(s)`, disabled > 0 ? 'success' : 'info');
    });
  }

  if (fixInstallDeps) {
    fixInstallDeps.addEventListener('click', async () => {
      if (fixInline) { fixInline.classList.remove('open'); fixInline.setAttribute('aria-hidden', 'true'); }
      showToast('Checking for missing dependencies…', 'info');
      const dlId = genDlId('mods-deps');
      if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading dependencies…', 'Checking mods…', { icon: 'wrench' });
      let installed = 0;
      try {
        installed = await runInstallDeps(dlId);
        if (installed === 0) {
          showToast('No missing dependencies found', 'success');
        } else {
          showToast(`Installed ${installed} missing dependenc${installed === 1 ? 'y' : 'ies'}`, 'success');
        }
        await loadMods();
      } catch (e) {
        const cancelled = dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);
        if (!cancelled) showToast('Failed to install dependencies: ' + e, 'error');
      } finally {
        if (dlWidgetGeneric) {
          dlWidgetGeneric.end(dlId, true, installed > 0 ? `Installed ${installed} dependenc${installed === 1 ? 'y' : 'ies'}` : 'Done');
        }
      }
    });
  }
  if (fixDedupe) {
    fixDedupe.addEventListener('click', async () => {
      if (fixInline) { fixInline.classList.remove('open'); fixInline.setAttribute('aria-hidden', 'true'); }
      try {
        const removed = await runDedupe();
        if (removed === 0) {
          showToast('No duplicate mods found', 'success');
        } else {
          showToast(`Deleted ${removed} duplicate mod(s)`, 'success');
        }
        await loadMods();
      } catch (e) {
        showToast('Failed to deduplicate mods: ' + e, 'error');
      }
    });
  }

  // "Fix Mods" — runs the full sequence: install missing dependencies,
  // delete duplicate mod files, disable mods that are for the wrong loader,
  // then check for updates and, if any are found, install them too. Each
  // step's own button only does that one step; this one chains all of them
  // so a single click gets an instance's mods folder into a clean state.
  const fixRunBtn = document.getElementById('btn-fix-mods-run');
  if (fixRunBtn) {
    fixRunBtn.addEventListener('click', async () => {
      if (fixInline) { fixInline.classList.remove('open'); fixInline.setAttribute('aria-hidden', 'true'); }
      if (modsUpdateCheckBusy) return;
      const directory = getModsTargetDirectory();
      if (!directory) { showToast('No instance selected', 'warning'); return; }

      fixRunBtn.disabled = true;
      const originalLabel = fixRunBtn.textContent;
      setUpdateButtonsBusy(true);
      const dlId = genDlId('fix-mods');
      if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Fixing mods…', 'Installing missing dependencies…', { icon: 'wrench' });

      try {
        fixRunBtn.textContent = 'Installing deps…';
        const depsInstalled = await runInstallDeps(dlId).catch(e => { console.error(e); return 0; });

        fixRunBtn.textContent = 'Deduplicating…';
        if (dlWidgetGeneric) dlWidgetGeneric.update(dlId, undefined, 'Removing duplicate mods…');
        const deduped = await runDedupe().catch(e => { console.error(e); return 0; });

        fixRunBtn.textContent = 'Disabling incompatible…';
        if (dlWidgetGeneric) dlWidgetGeneric.update(dlId, undefined, 'Disabling incompatible mods…');
        const disabled = await runDisableIncompatible().catch(e => { console.error(e); return 0; });

        fixRunBtn.textContent = 'Checking updates…';
        if (dlWidgetGeneric) dlWidgetGeneric.update(dlId, undefined, 'Checking for updates…');
        const up = await runCheckUpdates().catch(e => { console.error(e); return []; });

        let updated = 0;
        if (up.length > 0) {
          fixRunBtn.textContent = `Updating ${up.length}…`;
          updated = await runUpdateAll(up, dlId).catch(e => { console.error(e); return 0; });
        }

        await loadMods();

        const parts = [];
        if (depsInstalled > 0) parts.push(`installed ${depsInstalled} dependenc${depsInstalled === 1 ? 'y' : 'ies'}`);
        if (deduped > 0) parts.push(`removed ${deduped} duplicate${deduped === 1 ? '' : 's'}`);
        if (disabled > 0) parts.push(`disabled ${disabled} incompatible mod${disabled === 1 ? '' : 's'}`);
        if (updated > 0) parts.push(`updated ${updated} mod${updated === 1 ? '' : 's'}`);
        const msg = parts.length ? `Fixed mods: ${parts.join(', ')}.` : 'Everything already looked good — nothing to fix.';
        showToast(msg, 'success', 'Fix Mods');
        if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, true, 'Done');
      } catch (e) {
        showToast('Failed to fix mods: ' + e, 'error');
        if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, false, 'Failed');
      } finally {
        fixRunBtn.disabled = false;
        fixRunBtn.textContent = originalLabel;
        setUpdateButtonsBusy(false);
      }
    });
  }
});

// ══════════════════════════════════════════════════════════════════
// DISCOVER (Modrinth browser)
// ══════════════════════════════════════════════════════════════════
const DISCOVER_DEFAULT_PAGE_SIZE = 20;
const DISCOVER_PAGE_SIZES = [10, 20, 40, 60, 100];
const DISCOVER_PREFS_KEY = 'zerolauncher-discover-prefs';

function loadDiscoverPrefs() {
  try {
    const raw = localStorage.getItem(DISCOVER_PREFS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch { return {}; }
}

function saveDiscoverPrefs() {
  try {
    localStorage.setItem(DISCOVER_PREFS_KEY, JSON.stringify({
      view: discoverState.view,
      pageSize: discoverState.pageSize,
    }));
  } catch { /* storage unavailable, ignore */ }
}

const discoverPrefs = loadDiscoverPrefs();

let discoverState = {
  query: '',
  type: 'mod',       // 'modpack' | 'mod' | 'resourcepack'
  loader: 'any',
  gameVersion: '',        // '' = any
  categories: [],         // selected category slugs
  environment: 'any',     // 'any' | 'client' | 'server' (mods only)
  resolution: '',         // '' = any, else e.g. "16x-32x" (resourcepacks only)
  license: '',            // '' = any, else SPDX short id
  openSourceOnly: false,
  targetInstance: null, // { minecraft_version, loader } or null
  page: 1,
  pageSize: DISCOVER_PAGE_SIZES.includes(discoverPrefs.pageSize) ? discoverPrefs.pageSize : DISCOVER_DEFAULT_PAGE_SIZE,
  view: discoverPrefs.view === 'list' ? 'list' : 'grid',       // 'grid' | 'list'
  totalHits: 0,
  loaded: false,
  syncedInstanceId: null, // last selectedInstanceId the tab's filters/search were refreshed for
};

// Lazily-fetched Modrinth tag data (game versions / categories / licenses),
// cached so re-opening a filter dropdown doesn't refetch every time.
let discoverTagCache = {
  gameVersions: null,      // DiscoverGameVersion[]
  categoriesByType: {},    // { mod: DiscoverCategory[], resourcepack: DiscoverCategory[] }
  resolutions: null,       // DiscoverCategory[] (resourcepacks only)
  licenses: null,          // DiscoverLicense[]
};

function discoverEscape(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

// ══════════════════════════════════════════════════════════════════
// PRESETS
// ══════════════════════════════════════════════════════════════════
const presetsState = {
  loaded: false,
  presets: [],
  syncedInstanceId: undefined,
  syncTotal: 0,
  syncDone: 0,
  syncing: false,
};

let presetsInstanceSelectWired = false;

function initPresetsTabIfNeeded() {
  presetsState.syncedInstanceId = selectedInstanceId;
  populatePresetsInstanceSelect();
  if (!presetsInstanceSelectWired) {
    presetsInstanceSelectWired = true;
    const sel = document.getElementById('presets-instance-select');
    // Re-render whenever the target instance changes, so every preset
    // card's Apply button (and each card's own "target" state) reflects
    // whichever instance is currently selected here, instead of only
    // ever picking up the value that happened to be selected the one
    // time the presets grid was originally built.
    if (sel) sel.addEventListener('change', () => renderPresets());
  }

  // Wire progress-bar events once
  if (!presetsState.listening) {
    presetsState.listening = true;

    // Sync started: show banner in indeterminate mode until first preset arrives
    api.onPresetSyncStart((event) => {
      const total = event.payload?.total || 0;
      presetsState.syncTotal = total;
      presetsState.syncDone = 0;
      presetsState.syncing = true;
      const banner = document.getElementById('preset-sync-banner');
      const bar = document.getElementById('preset-sync-bar');
      const label = document.getElementById('preset-sync-label');
      const countEl = document.getElementById('preset-sync-count');
      if (!banner) return;
      banner.classList.remove('hidden', 'is-done');
      bar.classList.toggle('is-indeterminate', total === 0);
      bar.style.width = '0%';
      label.textContent = 'Syncing presets from GitHub…';
      countEl.textContent = total > 0 ? `0 / ${total}` : '';
    });

    // One preset finished: advance bar
    api.onPresetSynced(async (event) => {
      const { done = 0, total = 0 } = event.payload || {};
      presetsState.syncDone = done;
      presetsState.syncTotal = total;
      const bar = document.getElementById('preset-sync-bar');
      const countEl = document.getElementById('preset-sync-count');
      if (bar && total > 0) {
        bar.classList.remove('is-indeterminate');
        bar.style.width = `${Math.round((done / total) * 100)}%`;
      }
      if (countEl && total > 0) countEl.textContent = `${done} / ${total}`;

      // Use getLocalPresets (NOT listPresets) — listPresets spawns a new background
      // sync which would create an infinite event loop and hide new presets.
      try {
        const presets = await api.getLocalPresets();
        presetsState.presets = presets || [];
        const tab = document.getElementById('tab-presets');
        if (tab && tab.classList.contains('active')) renderPresets();
      } catch (_) {}
    });

    // All presets done: complete bar, do a final read of all local presets
    // to catch any that were added, then fade the banner out.
    api.onPresetSyncDone(async (event) => {
      const { done = 0, total = 0 } = event.payload || {};
      presetsState.syncing = false;
      const banner = document.getElementById('preset-sync-banner');
      const bar = document.getElementById('preset-sync-bar');
      const label = document.getElementById('preset-sync-label');
      const countEl = document.getElementById('preset-sync-count');
      if (bar && total > 0) {
        bar.classList.remove('is-indeterminate');
        bar.style.width = '100%';
      }
      if (label) label.textContent = 'Presets up to date!';
      if (countEl && total > 0) countEl.textContent = `${done} / ${total}`;

      // Final authoritative read — picks up every preset now on disk,
      // including newly added ones that only appeared during this sync.
      try {
        const presets = await api.getLocalPresets();
        presetsState.presets = presets || [];
        const tab = document.getElementById('tab-presets');
        if (tab && tab.classList.contains('active')) renderPresets();
      } catch (_) {}

      // Fade banner out after a short delay
      setTimeout(() => {
        if (banner) banner.classList.add('is-done');
      }, 1400);
    });
  }

  if (!presetsState.loaded) {
    presetsState.loaded = true;
    loadPresets();
  } else if (presetsState.syncedInstanceId !== selectedInstanceId) {
    // Already loaded from an earlier visit, but the selected instance may
    // have changed while Presets wasn't the active tab (that live sync
    // only runs for the active tab; see `syncInstanceSelectionAcrossTabs`)
    // — this catches re-entering the tab after switching elsewhere.
    renderPresets();
  }

  // Every time the Presets window/tab is opened: show whatever is on disk
  // instantly (above), then silently diff against GitHub in the background
  // and pull down anything missing. onPresetSynced/onPresetSyncDone above
  // merge new presets into presetsState + re-render as they land, so this
  // is fire-and-forget — no loading blocker, no need to wait on it here.
  triggerBackgroundPresetSync();
}

// Kicks off a GitHub preset sync in the background, guarded so we never
// have two overlapping syncs running (e.g. rapidly switching tabs).
async function triggerBackgroundPresetSync() {
  if (presetsState.syncing) return;
  presetsState.syncing = true;
  try {
    await api.syncPresets();
  } catch (_) {
    // Offline / GitHub unreachable — silently keep whatever is local.
  } finally {
    presetsState.syncing = false;
  }
}

function populatePresetsInstanceSelect() {
  const sel = document.getElementById('presets-instance-select');
  if (!sel) return;
  const instances = getInstances();
  sel.innerHTML = '<option value="">Select a target instance…</option>';
  instances.forEach(inst => {
    const opt = document.createElement('option');
    opt.value = inst.version_id;
    opt.textContent = inst.name || inst.version_id;
    // Always follow the globally selected instance, same as Mods/Discover
    // — Presets targets whatever instance is currently selected rather
    // than remembering its own separate choice across visits.
    if (inst.version_id === selectedInstanceId) opt.selected = true;
    sel.appendChild(opt);
  });
}

async function loadPresets() {
  const grid = document.getElementById('presets-grid');
  if (!grid) return;
  try {
    const presets = await api.listPresets();
    presetsState.presets = presets || [];
    renderPresets();
  } catch (e) {
    grid.innerHTML = `<div class="empty-state"><span>Failed to load presets: ${discoverEscape(String(e))}</span></div>`;
  }
}

function currentPresetsTargetInstance() {
  const sel = document.getElementById('presets-instance-select');
  const id = sel ? sel.value : '';
  if (!id) return null;
  return getInstances().find(i => i.version_id === id) || null;
}

function presetLoadersCompatible(presetLoader, targetLoader) {
  if (!presetLoader || !targetLoader) return false;
  const p = presetLoader.toUpperCase();
  const t = targetLoader.toUpperCase();
  if (p === t) return true;
  return p === 'FABRIC' && t === 'QUILT';
}

function renderPresets() {
  const grid = document.getElementById('presets-grid');
  if (!grid) return;
  const presets = presetsState.presets;
  if (!presets.length) {
    grid.innerHTML = '<div class="empty-state"><span>No presets found in Zero Launcher/presets</span></div>';
    return;
  }
  grid.innerHTML = '';

  // Split presets into sections by their presetType (e.g. "Performance",
  // "Quality Of Life") instead of one flat grid, so related presets are
  // grouped together. Untyped presets fall into a shared "Other" section.
  const groups = new Map();
  presets.forEach(preset => {
    const key = (preset.type && preset.type.trim()) ? preset.type.trim() : 'Other';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(preset);
  });

  const orderedKeys = Array.from(groups.keys()).sort((a, b) => {
    if (a === 'Other') return 1;
    if (b === 'Other') return -1;
    return a.localeCompare(b);
  });

  orderedKeys.forEach(key => {
    const section = document.createElement('div');
    section.className = 'preset-type-section';

    const header = document.createElement('div');
    header.className = 'preset-type-header';
    const groupPresets = groups.get(key);
    header.innerHTML = `<span class="preset-type-name">${discoverEscape(key)}</span><span class="preset-type-count">${groupPresets.length}</span>`;
    section.appendChild(header);

    const sectionGrid = document.createElement('div');
    sectionGrid.className = 'presets-grid';
    groupPresets.forEach(preset => sectionGrid.appendChild(buildPresetCard(preset)));
    section.appendChild(sectionGrid);

    grid.appendChild(section);
  });
}

function buildPresetCard(preset) {
    const card = document.createElement('div');
    card.className = 'preset-card';

    const top = document.createElement('div');
    top.className = 'preset-card-top';

    const iconWrap = document.createElement('div');
    iconWrap.className = 'preset-card-icon';
    top.appendChild(iconWrap);
    loadPresetIconInto(iconWrap, preset.id);

    const titleCol = document.createElement('div');
    titleCol.style.flex = '1';
    titleCol.style.minWidth = '0';
    const title = document.createElement('div');
    title.className = 'preset-card-title';
    title.textContent = preset.name;
    titleCol.appendChild(title);

    // Compact single-line meta row (pills first, then the description as
    // trailing muted text) — matches the instance list's name+meta layout
    // instead of a multi-line card body.
    const pillRow = document.createElement('div');
    pillRow.className = 'preset-pill-row';
    (preset.mod_loaders || []).forEach(l => {
      const p = document.createElement('span');
      p.className = 'preset-pill';
      p.textContent = l;
      pillRow.appendChild(p);
    });
    const modCountPill = document.createElement('span');
    modCountPill.className = 'preset-pill';
    const modCount = (preset.mods || []).length;
    modCountPill.textContent = `${modCount} mod${modCount !== 1 ? 's' : ''}`;
    pillRow.appendChild(modCountPill);
    if (preset.has_config) {
      const p = document.createElement('span');
      p.className = 'preset-pill config';
      p.textContent = 'Includes config';
      pillRow.appendChild(p);
    }
    titleCol.appendChild(pillRow);

    if (preset.description) {
      const desc = document.createElement('div');
      desc.className = 'preset-card-desc';
      desc.textContent = preset.description;
      desc.title = preset.description;
      titleCol.appendChild(desc);
    }

    top.appendChild(titleCol);
    card.appendChild(top);

    const actions = document.createElement('div');
    actions.className = 'preset-card-actions';

    // Reflects whichever instance is currently picked in the presets tab's
    // instance selector — re-rendered on every "change" of that selector
    // (see initPresetsTabIfNeeded), so this always names the actual target,
    // never a stale one left over from whenever the card was first built.
    const target = currentPresetsTargetInstance();
    const targetLabel = document.createElement('span');
    targetLabel.className = 'preset-card-target';
    targetLabel.textContent = target ? `→ ${target.name || target.version_id}` : 'No instance selected';
    targetLabel.title = targetLabel.textContent;
    actions.appendChild(targetLabel);

    const applyBtn = document.createElement('button');
    applyBtn.className = 'btn-accent';
    applyBtn.textContent = 'Apply';
    applyBtn.disabled = !target;
    applyBtn.addEventListener('click', () => {
      const currentTarget = currentPresetsTargetInstance();
      if (!currentTarget) {
        showToast('Select a target instance first', 'warning', 'No instance selected');
        return;
      }
      openApplyPresetOverlay(preset, currentTarget);
    });
    actions.appendChild(applyBtn);
    card.appendChild(actions);

    return card;
}

async function loadPresetIconInto(container, presetId) {
  try {
    const path = await api.getPresetIconPath(presetId);
    if (!path) return;
    const convert = window.__TAURI__.core.convertFileSrc;
    const src = convert ? convert(path) : path;
    if (!container.isConnected) return;
    const img = document.createElement('img');
    img.alt = '';
    img.loading = 'lazy';
    img.src = src;
    container.innerHTML = '';
    container.appendChild(img);
  } catch (e) {
    // No icon for this preset — leave the placeholder background.
  }
}

// ── Apply Preset overlay ──
let applyPresetState = null; // { preset, targetInstance, checkboxRows: [{name, modrinthId, checkbox, statusEl}] }
// True only while mods/config are actually being applied — same purpose as
// modpackImportTaskRunning above.
let applyPresetTaskRunning = false;

function setPresetModStatus(row, status, label) {
  row.statusEl.className = 'apply-preset-mod-status ' + status;
  row.statusEl.textContent = label;
}

function openApplyPresetOverlay(preset, targetInstance) {
  applyPresetState = { preset, targetInstance, rows: [] };

  document.getElementById('apply-preset-title').textContent = `Apply Preset — ${preset.name}`;

  const targetLoader = (targetInstance.loader || 'vanilla').toUpperCase();
  const presetLoaders = preset.mod_loaders || [];
  const loaderMatches = presetLoaders.length === 0
    || presetLoaders.some(l => presetLoadersCompatible(l, targetLoader));

  const info = document.getElementById('apply-preset-info');
  info.innerHTML = `→ Target: <strong>${discoverEscape(targetInstance.name || targetInstance.version_id)}</strong> (MC ${discoverEscape(targetInstance.minecraft_version || '?')}, ${discoverEscape(targetLoader)})` +
    (!loaderMatches ? `<span class="warn">This preset targets ${discoverEscape(presetLoaders.join('/'))} — mods below are unchecked by default since they won't work on ${discoverEscape(targetLoader)}.</span>` : '');

  const list = document.getElementById('apply-preset-mod-list');
  list.innerHTML = '';
  (preset.mods || []).forEach(mod => {
    const row = document.createElement('div');
    row.className = 'apply-preset-mod-row';

    const label = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    const hasId = !!mod.modrinth_id;
    cb.checked = hasId && loaderMatches;
    cb.disabled = !hasId;
    label.appendChild(cb);
    const nameSpan = document.createElement('span');
    nameSpan.textContent = mod.name;
    label.appendChild(nameSpan);
    row.appendChild(label);

    const statusEl = document.createElement('span');
    statusEl.className = 'apply-preset-mod-status';
    row.appendChild(statusEl);

    const rowObj = { name: mod.name, modrinthId: mod.modrinth_id, checkbox: cb, statusEl };
    if (!hasId) {
      setPresetModStatus(rowObj, 'skipped', 'Skipped — no Modrinth ID');
    } else {
      setPresetModStatus(rowObj, 'waiting', 'Waiting');
    }
    applyPresetState.rows.push(rowObj);
    list.appendChild(row);
  });

  const configRow = document.getElementById('apply-preset-config-row');
  const useConfigCb = document.getElementById('apply-preset-use-config');
  configRow.classList.toggle('hidden', !preset.has_config);
  useConfigCb.checked = true;

  document.getElementById('apply-preset-progress').classList.add('hidden');
  const confirmBtn = document.getElementById('btn-apply-preset-confirm');
  confirmBtn.disabled = false;
  confirmBtn.textContent = 'Apply Preset';

  document.getElementById('apply-preset-overlay').classList.remove('hidden');

  // Mark mods already present in the target instance's mods folder so
  // they're unchecked and skipped instead of being redownloaded.
  api.getPresetInstalledMods(preset.id, targetInstance.directory).then(names => {
    if (!applyPresetState || applyPresetState.preset !== preset) return; // overlay moved on
    const already = new Set(names || []);
    applyPresetState.rows.forEach(r => {
      if (!already.has(r.name)) return;
      r.checkbox.checked = false;
      r.checkbox.disabled = true;
      setPresetModStatus(r, 'already_installed', 'Already installed');
    });
  }).catch(() => {});
}

function closeApplyPresetOverlay() {
  document.getElementById('apply-preset-overlay').classList.add('hidden');
  if (applyPresetTaskRunning) {
    if (window.hwMinimize) window.hwMinimize('apply-preset-overlay', 'Apply Preset');
  } else {
    if (window.hwDone) window.hwDone('apply-preset-overlay');
  }
  applyPresetState = null;
}

function initApplyPresetOverlayEvents() {
  document.getElementById('btn-close-apply-preset').addEventListener('click', closeApplyPresetOverlay);
  const cancelBtn = document.getElementById('btn-cancel-apply-preset');
  if (cancelBtn) cancelBtn.addEventListener('click', closeApplyPresetOverlay);
  document.getElementById('btn-preset-select-all').addEventListener('click', () => {
    if (!applyPresetState) return;
    applyPresetState.rows.forEach(r => { if (!r.checkbox.disabled) r.checkbox.checked = true; });
  });
  document.getElementById('btn-preset-select-none').addEventListener('click', () => {
    if (!applyPresetState) return;
    applyPresetState.rows.forEach(r => { r.checkbox.checked = false; });
  });
  document.getElementById('btn-apply-preset-confirm').addEventListener('click', async () => {
    if (!applyPresetState) return;
    const { preset, targetInstance, rows } = applyPresetState;
    const selected = rows.filter(r => r.checkbox.checked && !r.checkbox.disabled);
    const useConfigCb = document.getElementById('apply-preset-use-config');
    const useConfig = !document.getElementById('apply-preset-config-row').classList.contains('hidden') && useConfigCb.checked;

    if (!selected.length && !useConfig) {
      showToast('Select at least one mod or enable the config toggle', 'warning', 'Nothing to apply');
      return;
    }

    const confirmBtn = document.getElementById('btn-apply-preset-confirm');
    confirmBtn.disabled = true;
    confirmBtn.textContent = 'Applying…';
    applyPresetTaskRunning = true;
    const progress = document.getElementById('apply-preset-progress');
    const progressLabel = document.getElementById('apply-preset-progress-label');
    progress.classList.remove('hidden');

    // Each preset gets its own card in the shared downloads widget, so the
    // transfer shows real per-mod progress and — since each mod is streamed
    // down via the same chunk-checked discoverDownload() used elsewhere —
    // hitting Cancel there stops it within a fraction of a second instead of
    // waiting for a whole (previously un-cancellable) backend call to finish.
    const dlId = `preset-${preset.id}-${Date.now()}`;
    const directory = targetInstance.directory;
    const loader = (targetInstance.loader && targetInstance.loader !== 'vanilla') ? targetInstance.loader : null;
    const mcVersion = targetInstance.minecraft_version;

    if (dlWidgetGeneric) {
      // No custom onCancel — the default already does exactly what's
      // needed: flag the card as cancelled (so isCancelled(dlId) below sees
      // it immediately) and call cancelGenericDownload(dlId), the same id
      // each mod's discoverDownload() call is checking.
      dlWidgetGeneric.begin(dlId, `Applying ${preset.name}`, `0 / ${selected.length} mods`, { determinate: true, icon: 'preset' });
      dlWidgetGeneric.seedFiles(dlId, selected.map(r => r.name));
      // Swap the flat preset glyph for the preset's own icon once it
      // resolves, if it has one — same lookup the preset cards themselves
      // use, so this stays visually consistent with the rest of the app.
      api.getPresetIconPath(preset.id).then((path) => {
        if (!path) return;
        const convert = window.__TAURI__.core.convertFileSrc;
        dlWidgetGeneric.setIcon(dlId, convert ? convert(path) : path, 'preset');
      }).catch(() => {});
    }
    const cancelled = () => dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);

    selected.forEach(r => setPresetModStatus(r, 'waiting', 'Queued'));

    let installedCount = 0;
    let failedCount = 0;
    let wasCancelled = false;
    let settledCount = 0;

    // Multiple mods download at once (Settings → Performance & Java →
    // Concurrent Downloads, default 3) instead of strictly one-at-a-time —
    // each still gets its own row/status update and counts toward the same
    // shared progress bar as they finish, in whatever order that happens.
    await runWithConcurrency(selected, getDownloadConcurrency(), async (r) => {
      if (cancelled()) { wasCancelled = true; setPresetModStatus(r, 'skipped', 'Cancelled'); return; }

      setPresetModStatus(r, 'downloading', 'Downloading…');

      try {
        const info = await api.resolvePresetModUrl(r.modrinthId, loader, mcVersion);
        if (cancelled()) { wasCancelled = true; setPresetModStatus(r, 'skipped', 'Cancelled'); return; }
        if (!info) {
          failedCount++;
          setPresetModStatus(r, 'failed', 'No compatible version found');
          return;
        }
        await trackedDiscoverDownload(directory, 'mod', info.url, info.file_name, dlId, r.name);
        installedCount++;
        setPresetModStatus(r, 'installed', 'Downloaded');
      } catch (e) {
        if (cancelled() || String(e).toLowerCase().includes('cancel')) {
          wasCancelled = true;
          setPresetModStatus(r, 'skipped', 'Cancelled');
        } else {
          failedCount++;
          setPresetModStatus(r, 'failed', String(e));
        }
      } finally {
        settledCount++;
        progressLabel.textContent = `Applying preset… (${settledCount} / ${selected.length})`;
        if (dlWidgetGeneric) {
          dlWidgetGeneric.update(dlId, null, `${settledCount} / ${selected.length} mods`, (settledCount / selected.length) * 100);
        }
      }
    });

    let configCopied = false;
    let configError = null;
    if (useConfig && !cancelled()) {
      try {
        configCopied = await api.applyPresetConfig(preset.id, directory);
      } catch (e) {
        configError = String(e);
      }
    }

    if (dlWidgetGeneric) {
      dlWidgetGeneric.update(dlId, null, `${installedCount} / ${selected.length} mods`, 100);
      dlWidgetGeneric.end(dlId, !wasCancelled && failedCount === 0, wasCancelled ? 'Cancelled' : undefined);
    }

    if (wasCancelled) {
      showToast('Preset application cancelled', 'info', 'Cancelled');
    } else {
      let msg = selected.length ? `Installed ${installedCount} of ${selected.length} mod(s).` : '';
      if (configCopied) msg += (msg ? '\n' : '') + 'Copied the recommended mods config.';
      if (configError) msg += (msg ? '\n' : '') + `Failed to copy config: ${configError}`;

      if (failedCount === 0 && !configError) {
        showToast(msg || 'Preset applied', 'success', 'Preset applied');
      } else {
        showToast(msg || 'Preset applied with issues', 'warning', 'Preset applied with issues');
      }
    }

    loadModInstances().then(() => loadMods()).catch(() => {});
    confirmBtn.disabled = false;
    confirmBtn.textContent = 'Apply Preset';
    applyPresetTaskRunning = false;
    // Task is done — if this window was minimized to the tray, clear that
    // entry now rather than waiting for the auto-close below (which is
    // purely cosmetic timing and shouldn't gate the tray).
    if (window.hwDone) window.hwDone('apply-preset-overlay');
    setTimeout(closeApplyPresetOverlay, wasCancelled ? 300 : 900);
  });
}

// ── Export Mods overlay ──
// Same JSON schema as the Java launcher's Export Mods feature
// (launcherVersion / instanceName / mcVersion / modLoader / modLoaderVersion
// / mods[{name, fileName, modrinthId, modrinthUrl}]) so files exported from
// either launcher can be imported into the other.
let exportModsState = null; // { instance, rows: [{name, fileName, modrinthId, checkbox}] }

function openExportModsOverlay() {
  const inst = getModsTargetInstance();
  if (!inst) {
    showToast('Select an instance first', 'error', 'No instance');
    return;
  }
  const directory = getModsTargetDirectory();

  gatherModsForDirectory(directory).then(async (mods) => {
    if (!mods || mods.length === 0) {
      showToast('This instance has no mods to export.', 'info', 'No mods');
      return;
    }

    // Resolve Modrinth IDs by hash up front (same "known-hash" identification
    // the Java client keeps around on currentModEntries) so each row can show
    // whether it will actually be exportable/importable.
    const withHash = mods.filter(m => m.sha1);
    let lookup = {};
    if (withHash.length) {
      try { lookup = await api.identifyModsByHash(withHash.map(m => m.sha1)); }
      catch (e) { console.error('Hash identification failed', e); }
    }

    exportModsState = { instance: inst, rows: [] };

    const loaderStr = (inst.loader && inst.loader !== 'vanilla') ? inst.loader.toUpperCase() : 'VANILLA';
    const info = document.getElementById('export-mods-info');
    info.innerHTML = `MC ${discoverEscape(inst.minecraft_version || '?')} &nbsp;│&nbsp; ${discoverEscape(loaderStr)}${inst.loader_version && inst.loader_version !== 'latest' ? ' ' + discoverEscape(inst.loader_version) : ''}`;

    const list = document.getElementById('export-mods-list');
    list.innerHTML = '';
    mods.forEach(mod => {
      const match = mod.sha1 ? lookup[mod.sha1] : null;
      const modrinthId = match && match.project_id ? match.project_id : null;
      const fileName = (mod.path || '').split(/[/\\]/).pop() || mod.name;
      const name = mod.name || fileName;

      const row = document.createElement('div');
      row.className = 'apply-preset-mod-row';

      const label = document.createElement('label');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = true;
      label.appendChild(cb);
      const nameSpan = document.createElement('span');
      nameSpan.textContent = name;
      label.appendChild(nameSpan);
      row.appendChild(label);

      if (modrinthId) {
        const link = document.createElement('span');
        link.className = 'export-mods-mod-link';
        link.textContent = `modrinth.com/mod/${modrinthId}`;
        row.appendChild(link);
      } else {
        const statusEl = document.createElement('span');
        statusEl.className = 'apply-preset-mod-status skipped';
        statusEl.textContent = 'No Modrinth link';
        row.appendChild(statusEl);
      }

      exportModsState.rows.push({ name, fileName, modrinthId, checkbox: cb });
      list.appendChild(row);
    });

    document.getElementById('export-mods-overlay').classList.remove('hidden');
  });
}

function closeExportModsOverlay() {
  document.getElementById('export-mods-overlay').classList.add('hidden');
  exportModsState = null;
}

function initExportModsOverlayEvents() {
  document.getElementById('btn-close-export-mods').addEventListener('click', closeExportModsOverlay);
  const cancelBtn = document.getElementById('btn-cancel-export-mods');
  if (cancelBtn) cancelBtn.addEventListener('click', closeExportModsOverlay);
  document.getElementById('btn-export-mods-select-all').addEventListener('click', () => {
    if (!exportModsState) return;
    exportModsState.rows.forEach(r => { r.checkbox.checked = true; });
  });
  document.getElementById('btn-export-mods-select-none').addEventListener('click', () => {
    if (!exportModsState) return;
    exportModsState.rows.forEach(r => { r.checkbox.checked = false; });
  });
  document.getElementById('btn-export-mods-confirm').addEventListener('click', async () => {
    if (!exportModsState) return;
    const { instance, rows } = exportModsState;
    const selected = rows.filter(r => r.checkbox.checked);
    if (selected.length === 0) {
      showToast('Select at least one mod to export.', 'warning', 'Nothing selected');
      return;
    }

    const safeName = (instance.name || instance.version_id || 'instance').replace(/[\\/:*?"<>|]/g, '_');
    let savePath;
    try {
      savePath = await window.__TAURI__.dialog.save({
        title: 'Save Mod List As',
        defaultPath: `${safeName}_mods.json`,
        filters: [{ name: 'JSON Files', extensions: ['json'] }],
      });
    } catch (e) {
      showToast('Failed to open save dialog: ' + e, 'error');
      return;
    }
    if (!savePath) return;
    if (!savePath.toLowerCase().endsWith('.json')) savePath += '.json';

    const loaderStr = (instance.loader && instance.loader !== 'vanilla') ? instance.loader.toUpperCase() : 'VANILLA';
    const root = {
      launcherVersion: 'Zero Launcher',
      instanceName: instance.name || instance.version_id,
      mcVersion: instance.minecraft_version,
      modLoader: loaderStr,
    };
    if (instance.loader_version && instance.loader_version !== 'latest') root.modLoaderVersion = instance.loader_version;
    root.mods = selected.map(r => {
      const m = { name: r.name, fileName: r.fileName };
      if (r.modrinthId) {
        m.modrinthId = r.modrinthId;
        m.modrinthUrl = `https://modrinth.com/mod/${r.modrinthId}`;
      }
      return m;
    });

    try {
      await api.exportModsList(savePath, JSON.stringify(root, null, 2));
      showToast(`Exported ${selected.length} mod(s) to ${savePath.split(/[/\\]/).pop()}`, 'success', 'Mods exported');
      closeExportModsOverlay();
    } catch (e) {
      showToast(String(e), 'error', 'Export failed');
    }
  });
}

// ── Import Mods overlay ──
let importModsState = null; // { targetInstance, rows: [{name, modrinthId, checkbox, statusEl}] }

function setImportModStatus(row, status, label) {
  row.statusEl.className = 'apply-preset-mod-status ' + status;
  row.statusEl.textContent = label;
}

async function startImportMods() {
  const inst = getModsTargetInstance();
  if (!inst) {
    showToast('Select a target instance first', 'error', 'No instance');
    return;
  }

  let filePath;
  try {
    filePath = await window.__TAURI__.dialog.open({
      title: 'Select Mod List JSON',
      multiple: false,
      filters: [{ name: 'JSON Files', extensions: ['json'] }],
    });
  } catch (e) {
    showToast('Failed to open file dialog: ' + e, 'error');
    return;
  }
  if (!filePath) return;

  let root;
  try {
    const content = await api.readModsListFile(filePath);
    root = JSON.parse(content);
  } catch (e) {
    showToast('Could not parse mod list: ' + e, 'error', 'Invalid file');
    return;
  }

  openImportModsOverlay(inst, root, filePath.split(/[/\\]/).pop());
}

function openImportModsOverlay(targetInstance, root, fileName) {
  importModsState = { targetInstance, rows: [] };

  document.getElementById('import-mods-title').innerHTML = `${IMPORT_ICON_SVG}<span>Import Mods — ${discoverEscape(fileName)}</span>`;

  const srcInstance = root.instanceName || 'Unknown';
  const srcVersion = root.mcVersion || '?';
  const srcLoader = root.modLoader || 'VANILLA';
  const srcLoaderVer = root.modLoaderVersion || '';
  const targetLoaderStr = (targetInstance.loader && targetInstance.loader !== 'vanilla') ? targetInstance.loader.toUpperCase() : 'VANILLA';

  const info = document.getElementById('import-mods-info');
  info.innerHTML = `Source: ${discoverEscape(srcInstance)} &nbsp;│&nbsp; MC ${discoverEscape(srcVersion)} &nbsp;│&nbsp; ${discoverEscape(srcLoader)} ${discoverEscape(srcLoaderVer)}<br>` +
    `→ Target: <strong>${discoverEscape(targetInstance.name || targetInstance.version_id)}</strong> (MC ${discoverEscape(targetInstance.minecraft_version || '?')}, ${discoverEscape(targetLoaderStr)})`;

  const list = document.getElementById('import-mods-list');
  list.innerHTML = '';
  const mods = Array.isArray(root.mods) ? root.mods : [];
  mods.forEach(mod => {
    const name = mod.name || 'Unknown mod';
    const modrinthId = mod.modrinthId || null;

    const row = document.createElement('div');
    row.className = 'apply-preset-mod-row';

    const label = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = !!modrinthId;
    cb.disabled = !modrinthId;
    label.appendChild(cb);
    const nameSpan = document.createElement('span');
    nameSpan.textContent = name;
    label.appendChild(nameSpan);
    row.appendChild(label);

    const statusEl = document.createElement('span');
    statusEl.className = 'apply-preset-mod-status';
    row.appendChild(statusEl);

    const rowObj = { name, modrinthId, checkbox: cb, statusEl };
    if (!modrinthId) {
      setImportModStatus(rowObj, 'skipped', 'Skipped — no Modrinth link');
    } else {
      setImportModStatus(rowObj, 'waiting', 'Waiting');
    }
    importModsState.rows.push(rowObj);
    list.appendChild(row);
  });

  document.getElementById('import-mods-progress').classList.add('hidden');
  const confirmBtn = document.getElementById('btn-import-mods-confirm');
  confirmBtn.disabled = false;
  confirmBtn.textContent = 'Import Selected';

  document.getElementById('import-mods-overlay').classList.remove('hidden');

  // Mark mods already installed in the target instance so they're
  // unchecked and skipped instead of being redownloaded.
  gatherModsForDirectory(targetInstance.directory).then(async (existing) => {
    if (!importModsState || importModsState.targetInstance !== targetInstance) return;
    const withHash = (existing || []).filter(m => m.sha1);
    if (!withHash.length) return;
    let lookup = {};
    try { lookup = await api.identifyModsByHash(withHash.map(m => m.sha1)); }
    catch (e) { return; }
    const installedIds = new Set(Object.values(lookup).map(v => v && v.project_id).filter(Boolean));
    importModsState.rows.forEach(r => {
      if (!r.modrinthId || !installedIds.has(r.modrinthId)) return;
      r.checkbox.checked = false;
      r.checkbox.disabled = true;
      setImportModStatus(r, 'already_installed', 'Already installed');
    });
  }).catch(() => {});
}

function closeImportModsOverlay() {
  document.getElementById('import-mods-overlay').classList.add('hidden');
  importModsState = null;
}

function initImportModsOverlayEvents() {
  document.getElementById('btn-close-import-mods').addEventListener('click', closeImportModsOverlay);
  const cancelBtn = document.getElementById('btn-cancel-import-mods');
  if (cancelBtn) cancelBtn.addEventListener('click', closeImportModsOverlay);
  document.getElementById('btn-import-mods-select-all').addEventListener('click', () => {
    if (!importModsState) return;
    importModsState.rows.forEach(r => { if (!r.checkbox.disabled) r.checkbox.checked = true; });
  });
  document.getElementById('btn-import-mods-select-none').addEventListener('click', () => {
    if (!importModsState) return;
    importModsState.rows.forEach(r => { r.checkbox.checked = false; });
  });
  document.getElementById('btn-import-mods-confirm').addEventListener('click', async () => {
    if (!importModsState) return;
    const { targetInstance, rows } = importModsState;
    const selected = rows.filter(r => r.checkbox.checked && !r.checkbox.disabled);
    const skippedNoId = rows.filter(r => !r.modrinthId).map(r => r.name);
    if (selected.length === 0) {
      showToast('No downloadable mods selected.', 'warning', 'Nothing to import');
      return;
    }

    const confirmBtn = document.getElementById('btn-import-mods-confirm');
    confirmBtn.disabled = true;
    confirmBtn.textContent = 'Importing...';
    const progress = document.getElementById('import-mods-progress');
    const progressLabel = document.getElementById('import-mods-progress-label');
    progress.classList.remove('hidden');

    const dlId = `import-mods-${targetInstance.version_id}-${Date.now()}`;
    const directory = targetInstance.directory;
    const loader = (targetInstance.loader && targetInstance.loader !== 'vanilla') ? targetInstance.loader : null;
    const mcVersion = targetInstance.minecraft_version;

    if (dlWidgetGeneric) {
      dlWidgetGeneric.begin(dlId, `Importing mods into ${targetInstance.name || targetInstance.version_id}`, `0 / ${selected.length} mods`, { determinate: true, icon: 'mod' });
      dlWidgetGeneric.seedFiles(dlId, selected.map(r => r.name));
    }
    const cancelled = () => dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);

    selected.forEach(r => setImportModStatus(r, 'waiting', 'Queued'));

    let installedCount = 0;
    let failedCount = 0;
    let wasCancelled = false;
    let settledCount = 0;

    // Multiple mods download at once (Settings → Performance & Java →
    // Concurrent Downloads, default 3) instead of strictly one-at-a-time.
    await runWithConcurrency(selected, getDownloadConcurrency(), async (r) => {
      if (cancelled()) { wasCancelled = true; setImportModStatus(r, 'skipped', 'Cancelled'); return; }

      setImportModStatus(r, 'downloading', 'Downloading…');

      try {
        const urlInfo = await api.resolvePresetModUrl(r.modrinthId, loader, mcVersion);
        if (cancelled()) { wasCancelled = true; setImportModStatus(r, 'skipped', 'Cancelled'); return; }
        if (!urlInfo) {
          failedCount++;
          setImportModStatus(r, 'failed', 'No compatible version found');
          return;
        }
        await trackedDiscoverDownload(directory, 'mod', urlInfo.url, urlInfo.file_name, dlId, r.name);
        installedCount++;
        setImportModStatus(r, 'installed', 'Downloaded');
      } catch (e) {
        if (cancelled() || String(e).toLowerCase().includes('cancel')) {
          wasCancelled = true;
          setImportModStatus(r, 'skipped', 'Cancelled');
        } else {
          failedCount++;
          setImportModStatus(r, 'failed', String(e));
        }
      } finally {
        settledCount++;
        progressLabel.textContent = `Importing… (${settledCount} / ${selected.length})`;
        if (dlWidgetGeneric) {
          dlWidgetGeneric.update(dlId, null, `${settledCount} / ${selected.length} mods`, (settledCount / selected.length) * 100);
        }
      }
    });

    if (dlWidgetGeneric) {
      dlWidgetGeneric.update(dlId, null, `${installedCount} / ${selected.length} mods`, 100);
      dlWidgetGeneric.end(dlId, !wasCancelled && failedCount === 0, wasCancelled ? 'Cancelled' : undefined);
    }

    if (wasCancelled) {
      showToast('Mod import cancelled', 'info', 'Cancelled');
    } else {
      let msg = `Installed ${installedCount} of ${selected.length} mod(s).`;
      if (skippedNoId.length) msg += `\nSkipped (couldn't identify mod): ${skippedNoId.join(', ')}`;
      if (failedCount > 0) msg += `\n${failedCount} failed.`;
      if (failedCount === 0) {
        showToast(msg, 'success', 'Import complete');
      } else {
        showToast(msg, 'warning', 'Import finished with issues');
      }
    }

    loadModInstances().then(() => loadMods()).catch(() => {});
    confirmBtn.disabled = false;
    confirmBtn.textContent = 'Import Selected';
    setTimeout(closeImportModsOverlay, wasCancelled ? 300 : 900);
  });
}

function initDiscoverTabIfNeeded() {
  if (!discoverState.loaded) {
    discoverState.loaded = true;
    discoverState.syncedInstanceId = selectedInstanceId;
    populateDiscoverInstanceSelect();
    populateDiscoverGameVersions();
    populateDiscoverCategories();
    populateDiscoverResolutions();
    populateDiscoverLicenses();
    applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
    performDiscoverSearch();
    return;
  }
  if (discoverState.syncedInstanceId !== selectedInstanceId) {
    discoverState.syncedInstanceId = selectedInstanceId;
    populateDiscoverInstanceSelect();
    applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
    performDiscoverSearch();
  }
}

function populateDiscoverInstanceSelect() {
  const sel = document.getElementById('discover-target-instance');
  if (!sel) return;
  const instances = getInstances();
  sel.innerHTML = '<option value="">Any instance / version…</option>';
  instances.forEach(inst => {
    const opt = document.createElement('option');
    opt.value = inst.version_id;
    opt.textContent = inst.name || inst.version_id;
    if (inst.version_id === selectedInstanceId) opt.selected = true;
    sel.appendChild(opt);
  });
}

function currentDiscoverTargetInstance() {
  const sel = document.getElementById('discover-target-instance');
  if (!sel || !sel.value) return null;
  return getInstances().find(i => i.version_id === sel.value) || null;
}

function applyInstanceFiltersToDiscover(inst) {
  if (!inst) return false;
  if (settings && settings.auto_apply_instance_filters_in_discover === false) return false;

  let changed = false;

  if (discoverState.type === 'mod') {
    const loaderValue = (inst.loader || 'vanilla').toLowerCase();
    const nextLoader = ['fabric', 'forge', 'neoforge', 'quilt'].includes(loaderValue) ? loaderValue : 'any';
    if (discoverState.loader !== nextLoader) {
      discoverState.loader = nextLoader;
      updateDiscoverLoaderPillsUI();
      changed = true;
    }
  }

  const gameVersion = inst.minecraft_version || '';
  // Modpacks install a whole new instance (their own Minecraft version +
  // loader), so auto-copying the *targeted* instance's version here would
  // just get overwritten by the pack anyway — leave Game Version alone and
  // let the user pick it themselves when browsing modpacks.
  if (discoverState.type !== 'modpack' && gameVersion && discoverState.gameVersion !== gameVersion) {
    discoverState.gameVersion = gameVersion;
    const gvSelect = document.getElementById('discover-game-version-select');
    if (gvSelect) gvSelect.value = gameVersion;
    changed = true;
  }

  if (changed) {
    discoverState.page = 1;
  }
  return changed;
}

function showDiscoverSkeletons() {
  const grid = document.getElementById('discover-results');
  if (!grid) return;
  grid.innerHTML = '';
  const count = 8;
  for (let i = 0; i < count; i++) {
    const sk = document.createElement('div');
    sk.className = 'discover-skeleton';
    sk.innerHTML = `
      <div class="discover-skeleton-icon"></div>
      <div class="discover-skeleton-body">
        <div class="discover-skeleton-line w-60"></div>
        <div class="discover-skeleton-line w-35"></div>
        <div class="discover-skeleton-line w-90"></div>
        <div class="discover-skeleton-line w-75"></div>
        <div class="discover-skeleton-line w-40"></div>
      </div>
    `;
    grid.appendChild(sk);
  }
}

function updateDiscoverPagination() {
  const prevBtn = document.getElementById('discover-prev-page');
  const nextBtn = document.getElementById('discover-next-page');
  const info = document.getElementById('discover-page-info');
  const countLabel = document.getElementById('discover-results-count');
  const totalPages = Math.max(1, Math.ceil(discoverState.totalHits / discoverState.pageSize));
  
  if (info) info.textContent = `Page ${discoverState.page} of ${totalPages}`;
  if (prevBtn) prevBtn.disabled = discoverState.page <= 1;
  if (nextBtn) nextBtn.disabled = discoverState.page >= totalPages;
  const typeLabel = discoverState.type === 'modpack' ? 'modpacks' : discoverState.type === 'mod' ? 'mods' : 'resourcepacks';
  if (countLabel) countLabel.textContent = `${formatDiscoverCount(discoverState.totalHits)} ${typeLabel}`;
}

async function performDiscoverSearch() {
  const grid = document.getElementById('discover-results');
  if (!grid) return;
  showDiscoverSkeletons();

  // Modpacks carry a loader and client/server side on Modrinth just like
  // mods do, so both filters need to actually reach the search call here —
  // not just be visible in the sidebar (that's the segment-click handler's
  // job; this is what makes the selected values do anything).
  const wantsLoaderEnv = discoverState.type === 'mod' || discoverState.type === 'modpack';
  const loaderFilter = (wantsLoaderEnv && discoverState.loader !== 'any') ? discoverState.loader : null;
  const gameVersion = discoverState.gameVersion || null;
  const categoriesFilter = (discoverState.type === 'resourcepack' && discoverState.resolution)
    ? [...discoverState.categories, discoverState.resolution]
    : (discoverState.categories.length > 0 ? discoverState.categories : null);

  try {
    const result = await api.discoverSearch(
      discoverState.query,
      discoverState.type,
      loaderFilter,
      gameVersion,
      categoriesFilter,
      (wantsLoaderEnv && discoverState.environment !== 'any') ? discoverState.environment : null,
      discoverState.license || null,
      discoverState.openSourceOnly,
      discoverState.page,
      discoverState.pageSize
    );
    // If the user has since switched away from Discover, don't spend time
    // rendering results into a grid nobody's looking at right now — the
    // data's already cached in discoverState for whenever they come back.
    const discoverTab = document.getElementById('tab-discover');
    if (!discoverTab || !discoverTab.classList.contains('active')) return;
    discoverState.totalHits = result.total_hits || 0;
    renderDiscoverResults(result.hits || []);
    updateDiscoverPagination();
  } catch (e) {
    grid.innerHTML = `<div class="empty-state"><span style="color:var(--danger)">${discoverEscape(String(e))}</span></div>`;
  }
}

function renderDiscoverResults(hits) {
  const grid = document.getElementById('discover-results');
  if (!grid) return;
  grid.innerHTML = '';
  if (hits.length === 0) {
    grid.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_SEARCH_EMPTY_SVG}</span><span>No results found</span></div>`;
    return;
  }
  const frag = document.createDocumentFragment();
  hits.forEach(hit => frag.appendChild(buildDiscoverCard(hit)));
  grid.appendChild(frag);
}

function formatDiscoverCount(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
  return String(n);
}

function formatDiscoverRelativeDate(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const diffMs = Date.now() - then;
  const day = 86400000;
  const days = Math.floor(diffMs / day);
  if (days < 1) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days}d ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo ago`;
  const years = Math.floor(days / 365);
  return `${years}y ago`;
}

function discoverSideLabel(hit) {
  const c = hit.client_side, s = hit.server_side;
  const req = (v) => v === 'required';
  const opt = (v) => v === 'optional';
  if (req(c) && req(s)) return 'Client + Server';
  if (req(c) && !req(s) && !opt(s)) return 'Client only';
  if (req(s) && !req(c) && !opt(c)) return 'Server only';
  if ((req(c) || opt(c)) && (req(s) || opt(s))) return 'Client + Server';
  if (req(c) || opt(c)) return 'Client';
  if (req(s) || opt(s)) return 'Server';
  return '';
}

function discoverSideBadgeVariant(label) {
  if (!label) return '';
  const l = label.toLowerCase();
  if (l.includes('+')) return 'discover-card-badge-both';
  if (l.includes('client')) return 'discover-card-badge-client';
  if (l.includes('server')) return 'discover-card-badge-server';
  return '';
}

const DISCOVER_LOADER_TAGS = new Set(['fabric', 'forge', 'neoforge', 'quilt']);

function discoverTagVariant(tag) {
  const t = (tag || '').toLowerCase();
  if (DISCOVER_LOADER_TAGS.has(t)) return `discover-card-tag--${t}`;
  if (t === 'client') return 'discover-card-tag--client';
  if (t === 'server') return 'discover-card-tag--server';
  return '';
}

const discoverVersionCache = new Map();

async function fetchProjectVersions(projectId) {
  if (discoverVersionCache.has(projectId)) {
    return discoverVersionCache.get(projectId);
  }
  const versions = await api.discoverGetVersions(projectId, null, null);
  discoverVersionCache.set(projectId, versions || []);
  return versions || [];
}

async function populateVersionSelect(hit, versionSelect, downloadBtn) {
  if (versionSelect._loaded) return;
  versionSelect._loaded = true;
  versionSelect.innerHTML = '<option value="">Loading versions…</option>';
  try {
    const versions = await fetchProjectVersions(hit.project_id);
    if (!versions || versions.length === 0) {
      versionSelect.innerHTML = '<option value="">No versions available</option>';
      if (downloadBtn) downloadBtn.disabled = true;
      return;
    }
    const target = currentDiscoverTargetInstance();
    versionSelect.innerHTML = '';

    versions.forEach(v => {
      const primaryFile = (v.files && v.files.find(f => f.primary)) || (v.files && v.files[0]);
      if (!primaryFile) return;
      const opt = document.createElement('option');
      opt.value = v.id;
      const latestGameVersion = (v.game_versions && v.game_versions[v.game_versions.length - 1]) || '';
      const compatible = isDiscoverVersionCompatible(v, hit, target);
      opt.textContent = compatible
        ? `${v.version_number} (${latestGameVersion})`
        : `${v.version_number} (${latestGameVersion}) — Incompatible`;
      if (!compatible) {
        opt.style.color = 'var(--danger)';
        opt.dataset.incompatible = '1';
      }
      opt.dataset.fileUrl = primaryFile.url;
      opt.dataset.fileName = primaryFile.filename;
      versionSelect.appendChild(opt);
    });

    const firstCompatible = Array.from(versionSelect.options).find(o => !o.dataset.incompatible);
    if (firstCompatible) versionSelect.value = firstCompatible.value;
    if (downloadBtn) downloadBtn.disabled = false;
  } catch (e) {
    versionSelect.innerHTML = '<option value="">Failed to load versions</option>';
    versionSelect._loaded = false;
  }
}

let discoverVirtualObserver = null;

function getDiscoverVirtualObserver() {
  if (!discoverVirtualObserver) {
    const root = document.querySelector('.discover-scroll-area') || document.getElementById('tab-discover');
    discoverVirtualObserver = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        const card = entry.target;
        if (entry.isIntersecting) {
          if (!card._isRendered) {
            renderCardContent(card);
          }
        } else {
          if (card._isRendered) {
            unloadCardContent(card);
          }
        }
      });
    }, {
      root: root || null,
      rootMargin: '180px 0px',
      threshold: 0,
    });
  }
  return discoverVirtualObserver;
}

function renderCardContent(card) {
  const hit = card._hit;
  if (!hit || card._isRendered) return;
  card._isRendered = true;
  card.classList.remove('is-unloaded');

  const iconHtml = hit.icon_url
    ? `<img src="${discoverEscape(hit.icon_url)}" alt="" draggable="false" loading="lazy" decoding="async" />`
    : ICON_UNKNOWN_SVG;

  const sideLabel = discoverSideLabel(hit);
  const sideVariant = discoverSideBadgeVariant(sideLabel);
  const updatedLabel = formatDiscoverRelativeDate(hit.date_modified);
  const loaderTags = (hit.categories || []).filter(c => DISCOVER_LOADER_TAGS.has((c || '').toLowerCase()));
  const contentCats = (hit.display_categories && hit.display_categories.length ? hit.display_categories : hit.categories) || [];
  const otherTags = contentCats.filter(c => !DISCOVER_LOADER_TAGS.has((c || '').toLowerCase()));
  const allTags = [...loaderTags, ...otherTags.slice(0, 3)];
  const capitalize = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  const tagsHtml = allTags.map(c => `<span class="discover-card-tag ${discoverTagVariant(c)}">${discoverEscape(capitalize(c))}</span>`).join('');

  const isModpack = hit.project_type === 'modpack';
  card.innerHTML = `
    <div class="discover-card-top">
      <div class="discover-card-icon">${iconHtml}</div>
      <div class="discover-card-info">
        <span class="discover-card-title" title="${discoverEscape(hit.title)}">${discoverEscape(hit.title)}</span>
        <div class="discover-card-author">by ${discoverEscape(hit.author)}</div>
      </div>
    </div>
    <div class="discover-card-desc">${discoverEscape(hit.description || '')}</div>
    <div class="discover-card-meta">
      <div class="discover-card-stats">
        <span>⬇ ${formatDiscoverCount(hit.downloads)}</span>
        <span>♥ ${formatDiscoverCount(hit.follows)}</span>
        ${updatedLabel ? `<span>↻ ${discoverEscape(updatedLabel)}</span>` : ''}
      </div>
      ${tagsHtml ? `<div class="discover-card-tags">${tagsHtml}</div>` : ''}
    </div>
    <div class="discover-card-footer">
      <select class="input-field discover-version-select" data-project-id="${discoverEscape(hit.project_id)}">
        <option value="__latest__">✦ Latest Compatible Version</option>
      </select>
      ${isModpack ? `<button class="discover-card-menu-btn" type="button" title="More options" aria-label="More options">⋯</button>` : ''}
      <button class="btn-accent btn-sm discover-download-btn" data-project-id="${discoverEscape(hit.project_id)}">${isModpack ? 'Install' : 'Download'}</button>
    </div>
  `;

  const versionSelect = card.querySelector('.discover-version-select');
  const downloadBtn = card.querySelector('.discover-download-btn');
  const menuBtn = card.querySelector('.discover-card-menu-btn');

  versionSelect.addEventListener('focus', () => populateVersionSelect(hit, versionSelect, downloadBtn));
  versionSelect.addEventListener('mousedown', () => populateVersionSelect(hit, versionSelect, downloadBtn));
  downloadBtn.addEventListener('click', () => downloadDiscoverSelection(hit, versionSelect, downloadBtn));

  if (menuBtn) {
    menuBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const rect = menuBtn.getBoundingClientRect();
      showCustomMenu(rect.left, rect.bottom + 4, [
        {
          type: 'item',
          label: 'Install in custom directory',
          onClick: () => openDiscoverModpackDirOverlay(hit, versionSelect, downloadBtn),
        },
      ]);
    });
  }
}

function unloadCardContent(card) {
  if (!card._isRendered) return;
  card._isRendered = false;
  card.classList.add('is-unloaded');
  card.innerHTML = '';
}

function createVirtualDiscoverCard(hit) {
  const card = document.createElement('div');
  card.className = 'discover-card is-unloaded';
  card.dataset.projectId = hit.project_id;
  card._hit = hit;
  card._isRendered = false;

  getDiscoverVirtualObserver().observe(card);
  return card;
}

function buildDiscoverCard(hit) {
  return createVirtualDiscoverCard(hit);
}

function isDiscoverVersionCompatible(version, hit, target) {
  if (!target) return true;
  if (target.minecraft_version && version.game_versions && !version.game_versions.includes(target.minecraft_version)) {
    return false;
  }
  if (hit.project_type === 'mod' || hit.project_type === 'modpack') {
    const loader = (target.loader || 'vanilla').toLowerCase();
    if (loader !== 'vanilla') {
      const loaders = (version.loaders || []).map(l => l.toLowerCase());
      if (loaders.length && !loaders.includes(loader)) return false;
    }
  }
  return true;
}

// Shared by both the plain mod/resourcepack download path and the modpack
// install path below: makes sure a compatible version is selected, confirms
// with the user if they picked an incompatible one anyway, and hands back
// the chosen file's <option> (or null, after already toasting/resetting the
// button, if there's nothing usable to download).
async function resolveDiscoverDownloadOption(hit, versionSelect, downloadBtn, target) {
  if (!versionSelect._loaded || versionSelect.value === '__latest__') {
    if (downloadBtn) downloadBtn.disabled = true;
    const oldText = downloadBtn ? downloadBtn.textContent : '';
    if (downloadBtn) downloadBtn.textContent = 'Checking…';
    await populateVersionSelect(hit, versionSelect, downloadBtn);
    if (downloadBtn) downloadBtn.textContent = oldText;
  }

  const opt = versionSelect.selectedOptions[0];
  if (!opt || !opt.dataset.fileUrl) {
    showToast('No version available for download', 'error');
    if (downloadBtn) downloadBtn.disabled = false;
    return null;
  }

  if (opt.dataset.incompatible) {
    const targetLabel = target ? (target.name || target.version_id) : 'the targeted instance';
    const proceed = confirm(`This version doesn't match ${targetLabel} and is marked (Incompatible). Download it anyway?`);
    if (!proceed) {
      if (downloadBtn) downloadBtn.disabled = false;
      return null;
    }
  }

  return opt;
}

async function downloadDiscoverSelection(hit, versionSelect, downloadBtn) {
  if (!settings) settings = await api.getSettings();
  const target = currentDiscoverTargetInstance();
  const directory = target ? (target.directory || settings.game_directory) : settings.game_directory;

  const opt = await resolveDiscoverDownloadOption(hit, versionSelect, downloadBtn, target);
  if (!opt) return;

  // Modpacks aren't dropped into a mods/resourcepacks folder — they go
  // through the same install-a-whole-instance pipeline the Modpack
  // Extractor uses (download → preview → install), always into their own
  // separate folder for a plain click. "Install in custom directory" from
  // the 3-dot menu is the only way to change that destination.
  if (hit.project_type === 'modpack') {
    await installDiscoverModpack(hit, opt, downloadBtn, null);
    return;
  }

  if (downloadBtn) downloadBtn.disabled = true;
  const originalText = downloadBtn ? downloadBtn.textContent : 'Download';
  if (downloadBtn) downloadBtn.textContent = 'Downloading…';
  const dlId = genDlId('discover-download');
  if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading…', hit.title, { icon: 'mod', iconUrl: hit.icon_url });

  try {
    await trackedDiscoverDownload(directory, hit.project_type, opt.dataset.fileUrl, opt.dataset.fileName, dlId);
    showToast(`${hit.title} downloaded`, 'success');
    if (downloadBtn) downloadBtn.textContent = 'Downloaded ✓';
    if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, true, `${hit.title} downloaded`);
    setTimeout(() => {
      if (downloadBtn) {
        downloadBtn.textContent = originalText;
        downloadBtn.disabled = false;
      }
    }, 1500);
  } catch (e) {
    const cancelled = dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);
    if (cancelled) {
      showToast('Download cancelled', 'info');
    } else {
      showToast('Download failed: ' + e, 'error');
      if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, false, `Failed: ${e}`);
    }
    if (downloadBtn) {
      downloadBtn.textContent = originalText;
      downloadBtn.disabled = false;
    }
  }
}

// True only while a Discover-triggered modpack install is actually running
// (download → preview → import) — the backend's modpack-import-progress
// event is a single global channel (same one the Modpack Extractor uses),
// so only one modpack install can be in flight at a time regardless of
// where it was started from.
let discoverModpackInstallRunning = false;

// Installs a modpack straight from a Discover card: downloads the chosen
// file to a scratch path, then runs it through the exact same
// preview/import pipeline as a dragged-in .mrpack/.zip. `customDirectory`
// is null for a plain Download click (always a separate folder, named
// after the pack) or a path when triggered via the 3-dot menu's "Install
// in custom directory".
async function installDiscoverModpack(hit, opt, downloadBtn, customDirectory) {
  if (discoverModpackInstallRunning) {
    showToast('Another modpack install is already in progress', 'info');
    return;
  }

  if (downloadBtn) downloadBtn.disabled = true;
  const originalText = downloadBtn ? downloadBtn.textContent : 'Install';
  if (downloadBtn) downloadBtn.textContent = 'Downloading…';

  discoverModpackInstallRunning = true;
  const dlId = genDlId('discover-modpack-install');
  if (dlWidgetGeneric) {
    dlWidgetGeneric.begin(dlId, `Modpack: ${hit.title}`, 'Downloading…', {
      determinate: true,
      withStats: true,
      noCancel: true,
      icon: 'mod',
      iconUrl: hit.icon_url,
    });
  }

  let unlisten = null;
  try {
    const tempPath = await api.discoverDownloadToTemp(opt.dataset.fileUrl, opt.dataset.fileName);

    if (downloadBtn) downloadBtn.textContent = 'Installing…';
    if (dlWidgetGeneric) dlWidgetGeneric.update(dlId, `Modpack: ${hit.title}`, 'Reading modpack…', 0, {});

    const preview = await api.previewModpack(tempPath);
    const zlibOk = await confirmZlibIfConflict(preview && preview.loader);
    if (!zlibOk) {
      showToast('Modpack installation cancelled', 'info');
      if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, false, 'Cancelled');
      return;
    }

    unlisten = await api.onModpackImportProgress((e) => {
      const p = e.payload || {};
      const pct = Math.max(0, Math.min(100, p.percent || 0));
      if (!dlWidgetGeneric) return;
      const activeFiles = p.active_files || [];
      const fileLabel = activeFiles.length === 0
        ? ''
        : activeFiles.length === 1
          ? activeFiles[0].name
          : `${activeFiles[0].name} +${activeFiles.length - 1} more`;
      dlWidgetGeneric.update(dlId, `Modpack: ${hit.title}`, p.message || p.stage || '', pct, {
        file: fileLabel,
        speed: p.speed_bps ? fmtSpeed(p.speed_bps) : '—',
        eta: p.eta_seconds != null ? fmtEta(p.eta_seconds) : '—',
        downloaded: p.downloaded_bytes ? fmtBytes(p.downloaded_bytes) : '—',
      });
      if (p.stage === 'downloading') {
        dlWidgetGeneric.reconcileActiveFiles(dlId, activeFiles);
      }
    });

    const name = (preview && preview.name) || hit.title;
    const result = await api.importModpack(tempPath, name, !!customDirectory, customDirectory || null);

    await refreshInstances();
    renderInstanceList();

    let msg = `Installed "${name}"`;
    if (result.failed_files && result.failed_files.length > 0) {
      msg += ` — ${result.failed_files.length} file(s) failed to download`;
    }
    if (result.unresolved_curseforge_mods) {
      msg += ` — ${result.unresolved_curseforge_mods} CurseForge mod(s) need to be added manually (via Discover)`;
    }
    showToast(msg, result.failed_files && result.failed_files.length > 0 ? 'warning' : 'success');
    if (downloadBtn) downloadBtn.textContent = 'Installed ✓';
    if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, true, msg);
    setTimeout(() => {
      if (downloadBtn) {
        downloadBtn.textContent = originalText;
        downloadBtn.disabled = false;
      }
    }, 1500);
  } catch (e) {
    showToast('Failed to install modpack: ' + e, 'error');
    if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, false, `Failed: ${e}`);
    if (downloadBtn) {
      downloadBtn.textContent = originalText;
      downloadBtn.disabled = false;
    }
  } finally {
    discoverModpackInstallRunning = false;
    if (typeof unlisten === 'function') unlisten();
  }
}

async function populateDiscoverGameVersions() {
  const gvSelect = document.getElementById('discover-game-version-select');
  if (!gvSelect) return;
  if (!discoverTagCache.gameVersions) {
    try {
      discoverTagCache.gameVersions = await api.discoverGetGameVersions();
    } catch {
      discoverTagCache.gameVersions = [];
    }
  }
  gvSelect.innerHTML = '<option value="">All Versions</option>';
  discoverTagCache.gameVersions.forEach(v => {
    if (v.version_type === 'release' || !v.version_type) {
      const opt = document.createElement('option');
      opt.value = v.version;
      opt.textContent = v.version;
      if (discoverState.gameVersion === v.version) opt.selected = true;
      gvSelect.appendChild(opt);
    }
  });
}

async function populateDiscoverCategories() {
  const box = document.getElementById('discover-categories-list');
  if (!box) return;
  const type = discoverState.type;
  if (!discoverTagCache.categoriesByType[type]) {
    try {
      discoverTagCache.categoriesByType[type] = await api.discoverGetCategories(type);
    } catch {
      discoverTagCache.categoriesByType[type] = [];
    }
  }
  const cats = discoverTagCache.categoriesByType[type] || [];
  box.innerHTML = '';
  cats.forEach(c => {
    const label = document.createElement('label');
    label.className = 'discover-category-item';
    const checked = discoverState.categories.includes(c.name);
    label.innerHTML = `<input type="checkbox" value="${discoverEscape(c.name)}" ${checked ? 'checked' : ''}/> <span>${discoverEscape(c.name)}</span>`;
    label.querySelector('input').addEventListener('change', () => {
      discoverState.categories = Array.from(box.querySelectorAll('input[type="checkbox"]:checked')).map(i => i.value);
      discoverState.page = 1;
      performDiscoverSearch();
    });
    box.appendChild(label);
  });
}

async function populateDiscoverResolutions() {
  const resSelect = document.getElementById('discover-resolution-select');
  if (!resSelect) return;
  if (!discoverTagCache.resolutions) {
    try {
      discoverTagCache.resolutions = await api.discoverGetResolutions('resourcepack');
    } catch {
      discoverTagCache.resolutions = [];
    }
  }
  resSelect.innerHTML = '<option value="">Any Resolution</option>';
  (discoverTagCache.resolutions || []).forEach(r => {
    const opt = document.createElement('option');
    opt.value = r.name;
    opt.textContent = r.name;
    if (discoverState.resolution === r.name) opt.selected = true;
    resSelect.appendChild(opt);
  });
}

async function populateDiscoverLicenses() {
  const licSelect = document.getElementById('discover-license-select');
  if (!licSelect) return;
  if (!discoverTagCache.licenses) {
    try {
      discoverTagCache.licenses = await api.discoverGetLicenses();
    } catch {
      discoverTagCache.licenses = [];
    }
  }
  licSelect.innerHTML = '<option value="">Any License</option><option value="__opensource__">✦ Open Source Only</option>';
  (discoverTagCache.licenses || []).forEach(l => {
    const opt = document.createElement('option');
    opt.value = l.short;
    opt.textContent = l.name;
    if (!discoverState.openSourceOnly && discoverState.license === l.short) opt.selected = true;
    licSelect.appendChild(opt);
  });
  if (discoverState.openSourceOnly) {
    licSelect.value = '__opensource__';
  }
}

function updateDiscoverLoaderPillsUI() {
  document.querySelectorAll('#discover-loader-pills .discover-loader-pill').forEach(pill => {
    pill.classList.toggle('active', pill.dataset.loader === discoverState.loader);
  });
}

function initDiscover() {
  const queryInput = document.getElementById('discover-query');
  const searchBtn = document.getElementById('discover-search-btn');
  const targetSelect = document.getElementById('discover-target-instance');
  const gvSelect = document.getElementById('discover-game-version-select');
  const resSelect = document.getElementById('discover-resolution-select');
  const envSelect = document.getElementById('discover-environment-select');
  const licSelect = document.getElementById('discover-license-select');
  const resetBtn = document.getElementById('discover-filters-reset');
  const prevBtn = document.getElementById('discover-prev-page');
  const nextBtn = document.getElementById('discover-next-page');

  const runSearch = () => {
    discoverState.query = (queryInput ? queryInput.value : '').trim();
    discoverState.page = 1;
    performDiscoverSearch();
  };

  if (searchBtn) searchBtn.addEventListener('click', runSearch);
  if (queryInput) queryInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });

  if (targetSelect) {
    targetSelect.addEventListener('change', (e) => {
      const val = e.target.value;
      discoverState.page = 1;
      if (val && val !== selectedInstanceId) {
        selectInstance(val);
      } else {
        const inst = val ? getInstances().find(i => i.version_id === val) : null;
        applyInstanceFiltersToDiscover(inst);
        performDiscoverSearch();
      }
    });
  }

  if (gvSelect) {
    gvSelect.addEventListener('change', (e) => {
      discoverState.gameVersion = e.target.value;
      discoverState.page = 1;
      performDiscoverSearch();
    });
  }

  if (resSelect) {
    resSelect.addEventListener('change', (e) => {
      discoverState.resolution = e.target.value;
      discoverState.page = 1;
      performDiscoverSearch();
    });
  }

  document.querySelectorAll('#discover-loader-pills .discover-loader-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      discoverState.loader = pill.dataset.loader;
      updateDiscoverLoaderPillsUI();
      discoverState.page = 1;
      performDiscoverSearch();
    });
  });

  if (envSelect) {
    envSelect.addEventListener('change', (e) => {
      discoverState.environment = e.target.value;
      discoverState.page = 1;
      performDiscoverSearch();
    });
  }

  if (licSelect) {
    licSelect.addEventListener('change', (e) => {
      const val = e.target.value;
      if (val === '__opensource__') {
        discoverState.openSourceOnly = true;
        discoverState.license = '';
      } else {
        discoverState.openSourceOnly = false;
        discoverState.license = val;
      }
      discoverState.page = 1;
      performDiscoverSearch();
    });
  }

  document.querySelectorAll('.discover-segment').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.discover-segment').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      discoverState.type = btn.dataset.type;
      discoverState.page = 1;
      discoverState.categories = [];
      discoverState.loader = 'any';
      discoverState.environment = 'any';
      discoverState.resolution = '';

      // Modpacks pick their own Minecraft version by nature of what's in
      // the pack — don't carry over whatever Game Version was left selected
      // from browsing Mods/Resourcepacks, and don't let the target
      // instance auto-fill it either (see applyInstanceFiltersToDiscover).
      // Leave it on "All Versions" until the user deliberately narrows it.
      if (discoverState.type === 'modpack') {
        discoverState.gameVersion = '';
        const gvSelect = document.getElementById('discover-game-version-select');
        if (gvSelect) gvSelect.value = '';
      }

      const loaderSection = document.getElementById('filter-section-loader');
      const envSection = document.getElementById('filter-section-env');
      const resSection = document.getElementById('filter-section-resolution');

      // Modpacks carry a loader (fabric/forge/etc.) and a client/server
      // side just like mods do on Modrinth, so they get the same filters —
      // only resourcepacks (Resolution) and plain mods vs modpacks (which
      // both want Loader + Environment) differ here.
      const wantsLoaderEnv = discoverState.type === 'mod' || discoverState.type === 'modpack';
      if (loaderSection) loaderSection.style.display = wantsLoaderEnv ? 'flex' : 'none';
      if (envSection) envSection.style.display = wantsLoaderEnv ? 'flex' : 'none';
      if (resSection) resSection.style.display = discoverState.type === 'resourcepack' ? 'flex' : 'none';

      updateDiscoverLoaderPillsUI();
      populateDiscoverCategories();
      if (discoverState.type === 'resourcepack') populateDiscoverResolutions();
      applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
      performDiscoverSearch();
    });
  });

  if (resetBtn) {
    resetBtn.addEventListener('click', () => {
      discoverState.query = '';
      if (queryInput) queryInput.value = '';
      discoverState.gameVersion = '';
      if (gvSelect) gvSelect.value = '';
      discoverState.loader = 'any';
      updateDiscoverLoaderPillsUI();
      discoverState.categories = [];
      document.querySelectorAll('#discover-categories-list input[type="checkbox"]').forEach(i => { i.checked = false; });
      discoverState.resolution = '';
      if (resSelect) resSelect.value = '';
      discoverState.environment = 'any';
      if (envSelect) envSelect.value = 'any';
      discoverState.license = '';
      discoverState.openSourceOnly = false;
      if (licSelect) licSelect.value = '';
      discoverState.page = 1;
      performDiscoverSearch();
    });
  }

  if (prevBtn) {
    prevBtn.addEventListener('click', () => {
      if (discoverState.page > 1) {
        discoverState.page -= 1;
        performDiscoverSearch();
      }
    });
  }

  if (nextBtn) {
    nextBtn.addEventListener('click', () => {
      discoverState.page += 1;
      performDiscoverSearch();
    });
  }
}


// ══════════════════════════════════════════════════════════════════
// SETTINGS
// ══════════════════════════════════════════════════════════════════

// Color preset applied on load — dark is the only theme.
const THEME_PRESETS = {
  dark: {
    bg_color: '#121212',
    panel_bg_color: '#1b1b1b',
    text_color: '#e2e2ea',
    log_bg_color: '#0a0a0a',
    notification_bg_color: '#1b1b1b',
    header_bg_color: '#1b1b1b',
  },
};

const ACCENT_DEFAULT = '#B7B7B7';
const KNOWN_LEGACY_DEFAULT_ACCENTS = new Set(['#10b981', '#1a1a1a', '#b7b7b7']);

// One-time migration from the old single `accent_color` field (and the
// former per-theme accent_color_dark field) to the single accent field.
// Safe to call repeatedly — it's a no-op once the field exists.
function ensureAccentFields() {
  if (!settings) return;
  if (settings.accent_color === undefined) {
    const legacy = settings.accent_color_dark || settings.accent_color;
    const hadCustomAccent = legacy && !KNOWN_LEGACY_DEFAULT_ACCENTS.has(legacy.toLowerCase());
    settings.accent_color = hadCustomAccent ? legacy : ACCENT_DEFAULT;
  }
}

function currentAccentColor() {
  if (!settings) return ACCENT_DEFAULT;
  return settings.accent_color || ACCENT_DEFAULT;
}

const RECOMMENDED_ACCENT_COLORS = [
  { name: 'Silver (Default)', hex: '#B7B7B7' },
  { name: 'Pure White', hex: '#FFFFFF' },
  { name: 'Blue Diamond', hex: '#00D2FF' },
  { name: 'Azure Blue', hex: '#007FFF' },
  { name: 'Crimson Red', hex: '#DC143C' },
  { name: 'Gold Yellow', hex: '#FFD700' },
  { name: 'Ice Cyan', hex: '#38BDF8' },
  { name: 'Soft Mint', hex: '#34D399' },
  { name: 'Lavender', hex: '#A78BFA' },
  { name: 'Pastel Sakura', hex: '#F472B6' },
  { name: 'Pastel Peach', hex: '#FB923C' },
  { name: 'Butter Gold', hex: '#FDE047' },
  { name: 'Sky Blue', hex: '#60A5FA' },
  { name: 'Neon Emerald', hex: '#10B981' },
  { name: 'Sapphire', hex: '#3B82F6' },
  { name: 'Amethyst', hex: '#8B5CF6' },
  { name: 'Amber Glow', hex: '#F59E0B' },
  { name: 'Vibrant Teal', hex: '#14B8A6' },
  { name: 'Ruby Red', hex: '#EF4444' },
  { name: 'Coral Rose', hex: '#FB7185' },
];

const RECOMMENDED_BG_COLORS = [
  { name: 'Default Dark', hex: '#0A0A0F' },
  { name: 'Pitch Black (OLED)', hex: '#000000' },
  { name: 'Deep Slate', hex: '#0D1117' },
  { name: 'Tokyo Charcoal', hex: '#16161E' },
  { name: 'Midnight Purple', hex: '#120D1C' },
  { name: 'Deep Navy', hex: '#0B0F19' },
  { name: 'Deep Emerald', hex: '#0A140F' },
  { name: 'Warm Espresso', hex: '#140D0A' },
  { name: 'Abyss Blue', hex: '#0F172A' },
  { name: 'Catppuccin Mocha', hex: '#1E1E2E' },
  { name: 'Nord Dark', hex: '#2E3440' },
  { name: 'Muted Steel', hex: '#1F2937' },
  { name: 'Cyber Slate', hex: '#24283B' },
  { name: 'Warm Taupe', hex: '#262322' },
  { name: 'Cloud Light Slate', hex: '#334155' },
];

const THEME_PALETTES = [
  { id: 'blue-diamond', name: 'Blue Diamond', accent: '#00D2FF', bg: '#0B0F19', tag: 'Top Pick • Cyberpunk Electric' },
  { id: 'sakura-midnight', name: 'Sakura Midnight', accent: '#F472B6', bg: '#120D1C', tag: 'Top Pick • Midnight Pastel' },
  { id: 'royal-gold', name: 'Royal Gold', accent: '#FFD700', bg: '#0A0A0F', tag: 'Top Pick • Luxury Obsidian' },
  { id: 'azure-slate', name: 'Azure Slate', accent: '#007FFF', bg: '#0D1117', tag: 'Popular • Modern Blue' },
  { id: 'crimson-void', name: 'Crimson Void', accent: '#DC143C', bg: '#050508', tag: 'Popular • Intense Gaming' },
  { id: 'cyber-emerald', name: 'Cyber Emerald', accent: '#10B981', bg: '#0A140F', tag: 'Featured • Matrix Forest' },
  { id: 'ice-glacier', name: 'Ice Glacier', accent: '#38BDF8', bg: '#0F172A', tag: 'Featured • Crystal Frost' },
  { id: 'catppuccin-mocha', name: 'Catppuccin Mocha', accent: '#CBA6F7', bg: '#1E1E2E', tag: 'Aesthetic • Pastel Slate' },
  { id: 'tokyo-neon', name: 'Tokyo Neon', accent: '#BB9AF7', bg: '#16161E', tag: 'Anime • Tokyo Charcoal' },
  { id: 'nord-frost', name: 'Nord Frost', accent: '#88C0D0', bg: '#2E3440', tag: 'Nordic • Arctic Minimal' },
  { id: 'warm-espresso', name: 'Warm Espresso', accent: '#FB923C', bg: '#140D0A', tag: 'Warm • Coffee Amber' },
  { id: 'pitch-oled', name: 'Pitch OLED', accent: '#FFFFFF', bg: '#000000', tag: 'True Black • High Contrast' },
  { id: 'zero-silver', name: 'Zero Silver', accent: '#B7B7B7', bg: '#0A0A0F', tag: 'Default • Metallic Sleek' },
  { id: 'soft-mint', name: 'Soft Mint', accent: '#34D399', bg: '#0A140F', tag: 'Fresh • Pastel Green' },
  { id: 'deep-amethyst', name: 'Deep Amethyst', accent: '#8B5CF6', bg: '#0F0A19', tag: 'Magic • Violet Glow' },
];

function isPaletteActive(palette) {
  if (!palette || !settings) return false;
  const curAccent = currentAccentColor().toUpperCase();
  const curBg = ((settings && settings.bg_color) || '#0A0A0F').toUpperCase();
  return curAccent === palette.accent.toUpperCase() && curBg === palette.bg.toUpperCase();
}

function applyThemePalette(palette, shouldPersist = true) {
  if (!palette) return;
  applyAccentColorLive(palette.accent);
  applyBgColorLive(palette.bg);
  applyThemeFromSettings();
  renderFullThemePalettes();
  if (shouldPersist) {
    saveSettingsNow();
  }
}

function renderPaletteSkeletonHtml(pal) {
  const hex = pal.accent;
  const bg = pal.bg;
  return `
    <div class="palette-ui-skeleton" style="background-color:${bg}; border-color:${hex}40;">
      <div class="skeleton-topbar">
        <div class="skeleton-traffic-lights">
          <div class="skeleton-traffic-dot" style="background-color:${hex};"></div>
          <div class="skeleton-traffic-dot" style="background-color:${hex}80;"></div>
        </div>
        <div class="skeleton-topbar-line" style="background-color:${hex}50;"></div>
      </div>
      <div class="skeleton-body">
        <div class="skeleton-sidebar">
          <div class="skeleton-nav-dot" style="background-color:${hex};"></div>
          <div class="skeleton-nav-dot" style="background-color:${hex}60;"></div>
          <div class="skeleton-nav-dot" style="background-color:${hex}30;"></div>
        </div>
        <div class="skeleton-content">
          <div class="skeleton-card-wireframe" style="background-color:${hex}15; border-color:${hex}35;">
            <div class="skeleton-mini-bar" style="background-color:${hex}70; width:10px;"></div>
            <div class="skeleton-hero-btn" style="background-color:${hex};"></div>
          </div>
          <div class="skeleton-sub-lines">
            <div class="skeleton-mini-bar" style="background-color:${hex}40;"></div>
            <div class="skeleton-mini-bar" style="background-color:${hex}25;"></div>
          </div>
        </div>
      </div>
    </div>
  `;
}

function renderQuickThemePalettes() {
  const containers = [
    document.getElementById('quick-palettes-container'),
    document.getElementById('setup-quick-palettes-container'),
  ];
  const quickList = THEME_PALETTES.slice(0, 4);

  containers.forEach(container => {
    if (!container) return;
    container.innerHTML = '';
    quickList.forEach(pal => {
      const active = isPaletteActive(pal);
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `quick-palette-plate ${active ? 'active' : ''}`;
      btn.title = `${pal.name} (${pal.tag})`;
      btn.innerHTML = `
        ${renderPaletteSkeletonHtml(pal)}
        <span class="quick-palette-name">${pal.name}</span>
      `;
      btn.onclick = () => applyThemePalette(pal, true);
      container.appendChild(btn);
    });
  });
}

function renderFullThemePalettes() {
  const grid = document.getElementById('full-palettes-grid');
  if (!grid) return;
  grid.innerHTML = '';

  THEME_PALETTES.forEach(pal => {
    const active = isPaletteActive(pal);
    const card = document.createElement('button');
    card.type = 'button';
    card.className = `full-palette-card ${active ? 'active' : ''}`;
    card.innerHTML = `
      ${renderPaletteSkeletonHtml(pal)}
      <div class="full-palette-info">
        <div class="full-palette-title">${pal.name}</div>
        <div class="full-palette-subtitle">${pal.tag}</div>
      </div>
      ${active ? `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="var(--accent)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>` : ''}
    `;
    card.onclick = () => {
      applyThemePalette(pal, true);
      renderFullThemePalettes();
    };
    grid.appendChild(card);
  });
}

function openThemePalettesModal() {
  const overlay = document.getElementById('theme-palettes-overlay');
  if (!overlay) return;
  renderFullThemePalettes();
  overlay.classList.remove('hidden');

  const close = () => overlay.classList.add('hidden');
  const closeBtn = document.getElementById('btn-close-palettes-modal');
  const doneBtn = document.getElementById('btn-done-palettes-modal');
  if (closeBtn) closeBtn.onclick = close;
  if (doneBtn) doneBtn.onclick = close;
}

function getRecentColors(type) {
  const key = type === 'bg' ? 'zero_recent_bg_colors' : 'zero_recent_accent_colors';
  try {
    const raw = localStorage.getItem(key);
    if (raw) return JSON.parse(raw);
  } catch (_) {}
  return [];
}

function addRecentColor(type, hex) {
  if (!hex || !/^#[0-9a-f]{6}$/i.test(hex)) return;
  const key = type === 'bg' ? 'zero_recent_bg_colors' : 'zero_recent_accent_colors';
  const norm = hex.toUpperCase();
  let list = getRecentColors(type).filter(c => c.toUpperCase() !== norm);
  list.unshift(norm);
  if (list.length > 10) list = list.slice(0, 10);
  try {
    localStorage.setItem(key, JSON.stringify(list));
  } catch (_) {}
}

function hexToRgbValues(hex) {
  let c = (hex || '').replace('#', '');
  if (c.length === 3) c = c.split('').map(x => x + x).join('');
  if (c.length !== 6) return [183, 183, 183];
  const num = parseInt(c, 16);
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255];
}

function rgbToHex(r, g, b) {
  const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));
  return '#' + [clamp(r), clamp(g), clamp(b)].map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase();
}

function openCustomColorPicker({ title, initialColor, type = 'accent', onApply, onLiveChange }) {
  const overlay = document.getElementById('custom-color-picker-overlay');
  if (!overlay) return;

  const titleEl = document.getElementById('custom-color-picker-title');
  const chipEl = document.getElementById('custom-color-live-chip');
  const nativeInp = document.getElementById('custom-color-native-input');
  const hexInp = document.getElementById('custom-color-hex-input');
  const rInp = document.getElementById('custom-color-r-input');
  const gInp = document.getElementById('custom-color-g-input');
  const bInp = document.getElementById('custom-color-b-input');
  const recGrid = document.getElementById('custom-color-recommended-grid');
  const recSec = document.getElementById('custom-color-recent-section');
  const recList = document.getElementById('custom-color-recent-grid');
  const applyBtn = document.getElementById('btn-color-picker-apply');
  const cancelBtn = document.getElementById('btn-color-picker-cancel');
  const closeBtn = document.getElementById('btn-close-color-picker');

  let currentColor = (initialColor || (type === 'bg' ? '#0A0A0F' : '#B7B7B7')).toUpperCase();
  if (!currentColor.startsWith('#')) currentColor = '#' + currentColor;

  if (titleEl) {
    titleEl.innerHTML = `
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color:var(--accent);">
        <path d="M12 3C7.03 3 3 6.58 3 11c0 2.76 2.24 4 4 4h1.5a1.5 1.5 0 0 1 1.5 1.5c0 .55-.22 1-.5 1.4-.3.42-.5.9-.5 1.35 0 1.5 1.5 2.75 3 2.75 5 0 9-4.03 9-9s-4.03-9-9-9Z"/>
      </svg>
      ${title || 'Color Picker'}
    `;
  }

  const syncUI = (hex, skipInputs = false) => {
    currentColor = hex.toUpperCase();
    if (chipEl) chipEl.style.backgroundColor = currentColor;
    if (nativeInp && nativeInp.value.toUpperCase() !== currentColor) {
      try { nativeInp.value = currentColor; } catch (_) {}
    }
    if (!skipInputs) {
      if (hexInp) hexInp.value = currentColor;
      const [r, g, b] = hexToRgbValues(currentColor);
      if (rInp) rInp.value = r;
      if (gInp) gInp.value = g;
      if (bInp) bInp.value = b;
    }
    overlay.querySelectorAll('.color-swatch-item').forEach(swatch => {
      swatch.classList.toggle('active', (swatch.dataset.hex || '').toUpperCase() === currentColor);
    });
    if (typeof onLiveChange === 'function') {
      onLiveChange(currentColor);
    }
  };

  // Render recommended swatches
  const recPalette = type === 'bg' ? RECOMMENDED_BG_COLORS : RECOMMENDED_ACCENT_COLORS;
  if (recGrid) {
    recGrid.innerHTML = '';
    recPalette.forEach(item => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `color-swatch-item ${item.hex.toUpperCase() === currentColor ? 'active' : ''}`;
      btn.style.backgroundColor = item.hex;
      btn.title = `${item.name} (${item.hex})`;
      btn.dataset.hex = item.hex;
      btn.onclick = () => syncUI(item.hex);
      recGrid.appendChild(btn);
    });
  }

  // Render recent colors
  const recent = getRecentColors(type);
  if (recList && recSec) {
    recList.innerHTML = '';
    if (recent.length > 0) {
      recSec.style.display = '';
      recent.forEach(hex => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `color-swatch-item ${hex.toUpperCase() === currentColor ? 'active' : ''}`;
        btn.style.backgroundColor = hex;
        btn.title = hex;
        btn.dataset.hex = hex;
        btn.onclick = () => syncUI(hex);
        recList.appendChild(btn);
      });
    } else {
      recSec.style.display = 'none';
    }
  }

  syncUI(currentColor);

  nativeInp.oninput = (e) => syncUI(e.target.value);
  hexInp.oninput = () => {
    let val = hexInp.value.trim();
    if (!val.startsWith('#')) val = '#' + val;
    if (/^#[0-9a-f]{6}$/i.test(val)) {
      syncUI(val, true);
      const [r, g, b] = hexToRgbValues(val);
      if (rInp) rInp.value = r;
      if (gInp) gInp.value = g;
      if (bInp) bInp.value = b;
    }
  };

  const onRgbInput = () => {
    const r = parseInt(rInp.value) || 0;
    const g = parseInt(gInp.value) || 0;
    const b = parseInt(bInp.value) || 0;
    const hex = rgbToHex(r, g, b);
    if (hexInp) hexInp.value = hex;
    syncUI(hex, true);
  };
  if (rInp) rInp.oninput = onRgbInput;
  if (gInp) gInp.oninput = onRgbInput;
  if (bInp) bInp.oninput = onRgbInput;

  const close = () => {
    overlay.classList.add('hidden');
  };

  const handleCancel = () => {
    if (typeof onLiveChange === 'function') {
      onLiveChange(initialColor);
    }
    close();
  };

  const handleApply = () => {
    addRecentColor(type, currentColor);
    if (typeof onApply === 'function') {
      onApply(currentColor);
    }
    close();
  };

  if (closeBtn) closeBtn.onclick = handleCancel;
  if (cancelBtn) cancelBtn.onclick = handleCancel;
  if (applyBtn) applyBtn.onclick = handleApply;

  overlay.classList.remove('hidden');
}

function applyAccentColorLive(hex) {
  if (!hex) return;
  if (settings) settings.accent_color = hex;
  const root = document.documentElement;
  root.style.setProperty('--accent', hex);
  root.style.setProperty('--accent-dim', hexToRgba(hex, 0.15));
  root.style.setProperty('--accent-glow', hexToRgba(hex, 0.35));
  if (typeof BG !== 'undefined' && BG._staticKey) {
    BG._staticKey = '';
    BG.requestRedraw();
  }
}

function applyBgColorLive(hex) {
  if (!hex) return;
  if (settings) settings.bg_color = hex;
  const root = document.documentElement;
  root.style.setProperty('--bg', hex);
  root.style.setProperty('--bg-darker', darkenColor(hex, 0.4));
  if (typeof BG !== 'undefined' && BG._staticKey) {
    BG._staticKey = '';
    BG.requestRedraw();
  }
}

async function loadSettings() {
  try {
    // Only fetch from backend if we haven't loaded yet
    if (!settings) {
      settings = await api.getSettings();
    }
    populateSettingsUI();
  } catch (e) { showToast('Failed to load settings', 'error'); }
}

function populateSettingsUI() {
  if (!settings) return;

  ensureAccentFields();

  document.getElementById('setting-notif-style').value = settings.notification_style || 'Glass';

  // Background & Animation
  document.getElementById('setting-bg-style').value = settings.background_style || 'Default';
  document.getElementById('setting-bg-anim-style').value = settings.background_animation_style || 'Waves';
  document.getElementById('setting-bg-anim-speed').value = settings.background_animation_speed || 1.0;
  document.getElementById('setting-bg-anim-fps').value = settings.background_animation_fps || 60;
  document.getElementById('setting-bg-anim-enable').checked = settings.enable_background_animation !== false;
  document.getElementById('setting-transparency').checked = settings.enable_transparency !== false;

  // Background Image
  document.getElementById('setting-use-bg-image').checked = !!settings.use_background_image;
  document.getElementById('setting-bg-image-path').value = settings.background_image_path || '';
  document.getElementById('setting-bg-image-fit').value = settings.background_image_fit || 'Cover';
  document.getElementById('setting-bg-image-dim').value = settings.background_image_dim ?? 20;
  document.getElementById('setting-bg-image-brightness').value = settings.background_image_brightness ?? 100;
  document.getElementById('setting-bg-image-blur').value = settings.background_image_blur ?? 0;
  document.getElementById('setting-bg-image-tint').checked = !!settings.background_image_tint;
  document.getElementById('setting-bg-image-vignette').checked = settings.background_image_vignette !== false;
  updateBackgroundImagePreview(settings.background_image_path || '');

  // Font
  document.getElementById('setting-font-family').value = settings.font_family || 'JetBrains Mono, Fira Code, Consolas, Monaco, monospace';

  // Behavior
  document.getElementById('setting-close-on-launch').checked = !!settings.close_after_launch;
  document.getElementById('setting-smart-close-on-launch').checked = settings.smart_close_on_launch !== false;
  document.getElementById('setting-minimize-on-launch').checked = !!settings.minimize_on_launch;
  document.getElementById('setting-on-game-close').value = settings.on_game_close || 'show';
  document.getElementById('setting-system-tray').checked = settings.enable_system_tray !== false;
  document.getElementById('setting-on-launcher-close').value = settings.on_launcher_close || 'tray';
  document.getElementById('setting-always-hide-to-tray').checked = !!settings.always_hide_to_tray;
  updateWindowBehaviorRowVisibility();
  updateSmartCloseRowEnabled();
  document.getElementById('setting-mod-updates-startup').checked = settings.check_mod_updates_on_startup !== false;
  const clickSoundsEl = document.getElementById('setting-click-sounds');
  if (clickSoundsEl) clickSoundsEl.checked = settings.sound_effects_enabled !== false;
  document.getElementById('setting-confirm-destructive').checked = settings.confirm_destructive_actions !== false;
  const autoApplyFiltersEl = document.getElementById('setting-auto-apply-instance-filters');
  if (autoApplyFiltersEl) autoApplyFiltersEl.checked = settings.auto_apply_instance_filters_in_discover !== false;
  const smoothScrollingEl = document.getElementById('setting-smooth-scrolling');
  if (smoothScrollingEl) smoothScrollingEl.checked = settings.smooth_scrolling !== false;
  const notifyAutoUpdatesEl = document.getElementById('setting-notify-auto-mod-updates');
  if (notifyAutoUpdatesEl) notifyAutoUpdatesEl.checked = settings.notify_on_auto_mod_updates !== false;
  const autoCheckLauncherUpdatesEl = document.getElementById('setting-auto-check-launcher-updates');
  if (autoCheckLauncherUpdatesEl) autoCheckLauncherUpdatesEl.checked = settings.auto_check_launcher_updates === true;

  // Performance & Java
  const elGameDir = document.getElementById('setting-game-dir');
  if (elGameDir) {
    elGameDir.value = settings.game_directory || '';
    // Blank = use the platform default (~/.minecraft on Linux,
    // %appdata%/.minecraft on Windows) - show it as a placeholder so it's
    // clear what's actually in effect without pre-filling the field.
    api.getDefaultMcDir().then(dir => {
      if (dir) elGameDir.placeholder = `Default (${dir})`;
    }).catch(() => {});
  }
  const elMinRam = document.getElementById('setting-min-ram');
  if (elMinRam) elMinRam.value = settings.min_ram_mb || 512;
  const elMaxRam = document.getElementById('setting-max-ram');
  if (elMaxRam) elMaxRam.value = settings.max_ram_mb || 4096;
  populateJavaDropdown(settings.java_path || '');
  const elJvmArgs = document.getElementById('setting-jvm-args');
  if (elJvmArgs) elJvmArgs.value = settings.jvm_args || '';

  // Concurrent downloads
  const elDlThreadsAuto = document.getElementById('setting-download-threads-auto');
  const elDlThreads = document.getElementById('setting-download-threads');
  const elDlThreadsValue = document.getElementById('download-threads-value');
  const elDlThreadsWrap = document.getElementById('download-threads-manual-wrap');
  if (elDlThreadsAuto) elDlThreadsAuto.checked = settings.download_threads_auto !== false;
  if (elDlThreads) elDlThreads.value = settings.download_threads || 3;
  if (elDlThreadsValue) elDlThreadsValue.textContent = String(settings.download_threads || 3);
  if (elDlThreadsWrap) elDlThreadsWrap.style.opacity = (settings.download_threads_auto !== false) ? '0.5' : '1';
  if (elDlThreadsWrap) elDlThreadsWrap.style.pointerEvents = (settings.download_threads_auto !== false) ? 'none' : 'auto';

  // Discord RPC
  document.getElementById('setting-enable-discord-rpc').checked = settings.enable_discord_rpc !== false;
  document.getElementById('setting-rpc-show-in-launcher').checked = settings.rpc_show_in_launcher !== false;
  document.getElementById('setting-rpc-show-instance').checked = settings.rpc_show_instance_name !== false;
  document.getElementById('setting-rpc-show-version').checked = settings.rpc_show_minecraft_version !== false;
  document.getElementById('setting-rpc-show-server-ip').checked = !!settings.rpc_show_server_ip;
  document.getElementById('setting-rpc-show-game-state').checked = !!settings.rpc_show_game_state;
  document.getElementById('setting-rpc-custom-state').value = settings.rpc_custom_state_text || 'In Zero Launcher';

  const appIdSel = document.getElementById('setting-rpc-app-id');
  const customAppIdInp = document.getElementById('setting-rpc-custom-appid');
  const customAppIdWrap = document.getElementById('rpc-custom-appid-wrap');
  const currentAppId = settings.rpc_app_id || '1131048770109460500';
  const knownAppIds = ['1528905372625146066', '1131048770109460500', '1528907493265375382'];

  if (appIdSel) {
    if (knownAppIds.includes(currentAppId)) {
      appIdSel.value = currentAppId;
      if (customAppIdWrap) customAppIdWrap.classList.add('hidden');
    } else {
      appIdSel.value = 'custom';
      if (customAppIdInp) customAppIdInp.value = currentAppId;
      if (customAppIdWrap) customAppIdWrap.classList.remove('hidden');
    }
  }

  document.getElementById('setting-rpc-show-launcher-activity').checked = settings.rpc_show_launcher_activity !== false;
  document.getElementById('setting-rpc-tab-instances').checked = settings.rpc_tab_instances !== false;
  document.getElementById('setting-rpc-tab-mods').checked = settings.rpc_tab_mods !== false;
  document.getElementById('setting-rpc-tab-settings').checked = settings.rpc_tab_settings !== false;
  document.getElementById('setting-rpc-tab-logs').checked = settings.rpc_tab_logs !== false;

  document.getElementById('setting-rpc-state-launching').checked = settings.rpc_state_launching !== false;
  document.getElementById('setting-rpc-state-main-menu').checked = settings.rpc_state_main_menu !== false;
  document.getElementById('setting-rpc-state-singleplayer').checked = settings.rpc_state_singleplayer !== false;
  document.getElementById('setting-rpc-state-multiplayer').checked = settings.rpc_state_multiplayer !== false;

  // Privacy & Developer
  const hideUsernameEl = document.getElementById('setting-hide-username');
  if (hideUsernameEl) hideUsernameEl.checked = !!settings.hide_username;
  const clearSessionChk = document.getElementById('setting-clear-session-on-exit');
  if (clearSessionChk) clearSessionChk.checked = !!settings.clear_session_on_exit;
  document.getElementById('setting-redact-tokens').checked = settings.redact_tokens !== false;
  const redactPathsChk = document.getElementById('setting-redact-paths');
  if (redactPathsChk) redactPathsChk.checked = settings.redact_paths !== false;
  const hideLaunchCmdChk = document.getElementById('setting-hide-launch-command');
  if (hideLaunchCmdChk) hideLaunchCmdChk.checked = settings.hide_launch_command !== false;
  document.getElementById('setting-debug-mode').checked = !!settings.debug_mode;
  const crashAnalysisChk = document.getElementById('setting-crash-analysis');
  if (crashAnalysisChk) crashAnalysisChk.checked = !!settings.enable_crash_analysis;
  const autoOpenConsoleChk = document.getElementById('setting-auto-open-console');
  if (autoOpenConsoleChk) autoOpenConsoleChk.checked = !!settings.auto_open_console_on_launch;

  applyThemeFromSettings();
  applyUsernamePrivacy();

  const settingsModal = document.getElementById('settings-modal-overlay');
  if (settingsModal) enableCardCulling(settingsModal, '.glass-card');
}

// ── Apply all appearance settings to CSS custom properties & DOM overlays ──
function applyThemeFromSettings() {
  if (!settings) return;
  const root = document.documentElement;

  const preset = THEME_PRESETS.dark;

  // Colors — these fields have no picker UI anywhere in the app, so the
  // backend always serializes them at their defaults. Using
  // `settings.X || preset.X` meant `settings.X` was never falsy and the
  // preset never actually won, so always derive from the preset until
  // per-field pickers exist.
  ensureAccentFields();
  const accent = currentAccentColor();
  const baseBg = (settings && settings.bg_color) || preset.bg_color || '#0a0a0f';
  root.style.setProperty('--accent', accent);
  root.style.setProperty('--bg', baseBg);
  root.style.setProperty('--bg-darker', darkenColor(baseBg, 0.4));

  // Sync previews & editable HEX inputs in Settings
  const accentPrev = document.getElementById('setting-accent-preview');
  const accentHexInput = document.getElementById('setting-accent-hex-input');
  if (accentPrev) accentPrev.style.backgroundColor = accent;
  if (accentHexInput && document.activeElement !== accentHexInput) accentHexInput.value = accent.toUpperCase();

  const bgPrev = document.getElementById('setting-bg-color-preview');
  const bgHexInput = document.getElementById('setting-bg-hex-input');
  if (bgPrev) bgPrev.style.backgroundColor = baseBg;
  if (bgHexInput && document.activeElement !== bgHexInput) bgHexInput.value = baseBg.toUpperCase();

  // Sync previews & editable HEX inputs in Setup Wizard
  const setupAccentPrev = document.getElementById('setup-accent-preview');
  const setupAccentHexInput = document.getElementById('setup-accent-hex-input');
  if (setupAccentPrev) setupAccentPrev.style.backgroundColor = accent;
  if (setupAccentHexInput && document.activeElement !== setupAccentHexInput) setupAccentHexInput.value = accent.toUpperCase();

  const setupBgPrev = document.getElementById('setup-bg-color-preview');
  const setupBgHexInput = document.getElementById('setup-bg-hex-input');
  if (setupBgPrev) setupBgPrev.style.backgroundColor = baseBg;
  if (setupBgHexInput && document.activeElement !== setupBgHexInput) setupBgHexInput.value = baseBg.toUpperCase();

  // Sync active states on Theme Palettes quick plates
  renderQuickThemePalettes();

  // High-performance crystal transparency (no slow backdrop blur on WebKitGTK)
  const isTransparent = settings && settings.enable_transparency !== false;
  root.classList.toggle('transparent-ui', isTransparent);

  if (isTransparent) {
    root.style.setProperty('--panel', 'linear-gradient(180deg, rgba(34, 34, 34, 0.65) 0%, rgba(20, 20, 20, 0.45) 100%)');
    root.style.setProperty('--panel-solid', 'rgba(27, 27, 27, 0.85)');
    root.style.setProperty('--header-bg', 'linear-gradient(180deg, rgba(34, 34, 34, 0.8) 0%, rgba(22, 22, 22, 0.65) 100%)');
  } else {
    root.style.setProperty('--panel', 'linear-gradient(180deg, #222222 0%, #1a1a1a 100%)');
    root.style.setProperty('--panel-solid', '#1b1b1b');
    root.style.setProperty('--header-bg', 'linear-gradient(180deg, #222222 0%, #191919 100%)');
  }

  root.style.setProperty('--text', preset.text_color);
  root.style.setProperty('--text-muted', hexToRgba(preset.text_color, 0.6));
  root.style.setProperty('--log-bg', preset.log_bg_color);
  const notifHex = preset.notification_bg_color;
  root.style.setProperty('--notif-bg', notifHex);
  root.style.setProperty('--notif-bg-rgb', hexToRgbTriplet(notifHex));

  // Accent derived
  root.style.setProperty('--accent-dim', hexToRgba(accent, 0.15));
  root.style.setProperty('--accent-glow', hexToRgba(accent, 0.35));

  // Font
  const font = settings.font_family || 'JetBrains Mono, Fira Code, Consolas, Monaco, monospace';
  root.style.setProperty('--font', `'${font}', monospace`);

  // Blur
  if (settings.enable_blur_effect) {
    root.style.setProperty('--blur-amount', (settings.blur_strength || 10) + 'px');
  } else {
    root.style.setProperty('--blur-amount', '0px');
  }

  // Background Image Handling (delegated to BG engine)
  if (typeof BG !== 'undefined') {
    BG.applyBackgroundImage();
    updateBackgroundImagePreview(settings.background_image_path || '');
  }
}

function updateBackgroundImagePreview(path) {
  const img = document.getElementById('setting-bg-image-thumbnail');
  const label = document.getElementById('setting-bg-image-name');
  const preview = document.getElementById('setting-bg-image-preview');
  if (!img || !label || !preview) return;

  const selectedText = document.getElementById('setting-bg-image-selected-text');

  if (!path) {
    label.textContent = '';
    img.src = '';
    img.style.filter = 'none';
    preview.classList.add('hidden');
    if (selectedText) selectedText.textContent = 'No image selected';
    return;
  }

  const filename = path.replace(/^.*[\\/]/, '');
  label.textContent = filename;
  if (selectedText) selectedText.textContent = filename;
  let url = path;
  if (!/^(https?|data|blob):/.test(url)) {
    try {
      const convert = window.__TAURI__.core.convertFileSrc;
      if (convert) url = convert(url);
    } catch (e) {
      console.error('Failed to convert background image preview path', e);
    }
  }
  img.src = url;
  const brightness = document.getElementById('setting-bg-image-brightness')?.value || 100;
  const blur = document.getElementById('setting-bg-image-blur')?.value || 0;
  const tint = document.getElementById('setting-bg-image-tint')?.checked;
  const styleParts = [`brightness(${brightness}%)`, `blur(${blur}px)`];
  if (tint) styleParts.push('sepia(0.35) hue-rotate(120deg) saturate(1.35)');
  img.style.filter = styleParts.join(' ');
  preview.classList.remove('hidden');
}

// Color utility: hex -> rgba string
function hexToRgba(hex, alpha) {
  hex = hex.replace('#', '');
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
  const r = parseInt(hex.slice(0, 2), 16) || 0;
  const g = parseInt(hex.slice(2, 4), 16) || 0;
  const b = parseInt(hex.slice(4, 6), 16) || 0;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

// Color utility: hex -> "r, g, b" triplet (for building rgba() at variable
// alpha in CSS via rgba(var(--x-rgb), 0.5))
function hexToRgbTriplet(hex) {
  hex = (hex || '').replace('#', '');
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
  const r = parseInt(hex.slice(0, 2), 16) || 0;
  const g = parseInt(hex.slice(2, 4), 16) || 0;
  const b = parseInt(hex.slice(4, 6), 16) || 0;
  return `${r}, ${g}, ${b}`;
}

// Color utility: darken a hex color
function darkenColor(hex, factor) {
  hex = hex.replace('#', '');
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
  const r = Math.round(Math.max(0, (parseInt(hex.slice(0, 2), 16) || 0) * (1 - factor)));
  const g = Math.round(Math.max(0, (parseInt(hex.slice(2, 4), 16) || 0) * (1 - factor)));
  const b = Math.round(Math.max(0, (parseInt(hex.slice(4, 6), 16) || 0) * (1 - factor)));
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

// Color utility: HSL to RGB tuple
function hslToRgb(h, s, l) {
  let r, g, b;
  if (s === 0) {
    r = g = b = l;
  } else {
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    r = hue2rgb(p, q, h + 1 / 3);
    g = hue2rgb(p, q, h);
    b = hue2rgb(p, q, h - 1 / 3);
  }
  return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)];
}

function hue2rgb(p, q, t) {
  if (t < 0) t += 1;
  if (t > 1) t -= 1;
  if (t < 1 / 6) return p + (q - p) * 6 * t;
  if (t < 1 / 2) return q;
  if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
  return p;
}

// ── Auto-save helpers ──
let _settingsSaveTimer = null;

function collectSettingsFromUI() {
  if (!settings) return;
  const prevFinishedSetup = settings.Finished_setup;
  const prevSetupFinished = settings.setup_finished;
  // Appearance: Colors
  settings.accent_color = currentAccentColor();
  settings.bg_color = (settings && settings.bg_color) || '#0a0a0f';

  // When the setup wizard is visible, its dropdowns are the source of truth
  // for these three fields. The main settings modal dropdowns are hidden/unpopulated
  // at that point and would silently reset user choices back to defaults.
  const setupTabActive = document.getElementById('tab-setup')?.classList.contains('active');
  if (setupTabActive) {
    const setupNotif = document.getElementById('setup-notif-style');
    const setupBgStyle = document.getElementById('setup-bg-style');
    const setupBgAnim = document.getElementById('setup-bg-anim-style');
    if (setupNotif) settings.notification_style = setupNotif.value;
    if (setupBgStyle) settings.background_style = setupBgStyle.value;
    if (setupBgAnim) settings.background_animation_style = setupBgAnim.value;
  } else {
    settings.notification_style = document.getElementById('setting-notif-style').value;
    // Appearance: Background & Animation
    settings.background_style = document.getElementById('setting-bg-style').value;
    settings.background_animation_style = document.getElementById('setting-bg-anim-style').value;
  }
  settings.background_animation_speed = parseFloat(document.getElementById('setting-bg-anim-speed').value) || 1.0;
  settings.background_animation_intensity = parseFloat(document.getElementById('setting-bg-anim-intensity').value) || 1.0;
  settings.background_animation_fps = parseInt(document.getElementById('setting-bg-anim-fps').value) || 60;
  settings.enable_background_animation = document.getElementById('setting-bg-anim-enable').checked;
  settings.enable_transparency = document.getElementById('setting-transparency').checked;

  // Appearance: Background Image
  settings.use_background_image = document.getElementById('setting-use-bg-image').checked;
  settings.background_image_path = document.getElementById('setting-bg-image-path').value;
  settings.background_image_fit = document.getElementById('setting-bg-image-fit').value;
  const imageDim = parseInt(document.getElementById('setting-bg-image-dim').value);
  settings.background_image_dim = Number.isFinite(imageDim) ? imageDim : 20;
  const imageBrightness = parseInt(document.getElementById('setting-bg-image-brightness').value);
  settings.background_image_brightness = Number.isFinite(imageBrightness) ? imageBrightness : 100;
  const imageBlur = parseInt(document.getElementById('setting-bg-image-blur').value);
  settings.background_image_blur = Number.isFinite(imageBlur) ? imageBlur : 0;
  settings.background_image_tint = document.getElementById('setting-bg-image-tint').checked;
  settings.background_image_vignette = document.getElementById('setting-bg-image-vignette').checked;

  // Appearance: Font
  settings.font_family = document.getElementById('setting-font-family').value;

  // Appearance: Music
  const musicEnabledEl = document.getElementById('setting-music-enabled');
  if (musicEnabledEl) settings.music_enabled = musicEnabledEl.checked;
  const musicVolumeEl = document.getElementById('setting-music-volume');
  if (musicVolumeEl) settings.music_volume = parseInt(musicVolumeEl.value) || 0;
  const musicBehaviorEl = document.getElementById('setting-music-switch-behavior');
  if (musicBehaviorEl) settings.music_switch_behavior = musicBehaviorEl.value;
  const musicLowerEl = document.getElementById('setting-music-lower-percent');
  if (musicLowerEl) settings.music_lower_percent = parseInt(musicLowerEl.value) || 0;

  // Behavior
  settings.close_after_launch = document.getElementById('setting-close-on-launch').checked;
  // Sub-option only takes effect while its parent toggle is on, but its own
  // saved value should reflect the checkbox regardless — no need to force
  // it back to the default just because the parent's currently off.
  settings.smart_close_on_launch = document.getElementById('setting-smart-close-on-launch').checked;
  settings.minimize_on_launch = document.getElementById('setting-minimize-on-launch').checked;
  settings.on_game_close = document.getElementById('setting-on-game-close').value;
  settings.enable_system_tray = document.getElementById('setting-system-tray').checked;
  settings.on_launcher_close = document.getElementById('setting-on-launcher-close').value;
  settings.always_hide_to_tray = document.getElementById('setting-always-hide-to-tray').checked;
  settings.check_mod_updates_on_startup = document.getElementById('setting-mod-updates-startup').checked;
  const clickSoundsElCollect = document.getElementById('setting-click-sounds');
  if (clickSoundsElCollect) settings.sound_effects_enabled = clickSoundsElCollect.checked;
  settings.confirm_destructive_actions = document.getElementById('setting-confirm-destructive').checked;
  const autoApplyFiltersElCollect = document.getElementById('setting-auto-apply-instance-filters');
  if (autoApplyFiltersElCollect) settings.auto_apply_instance_filters_in_discover = autoApplyFiltersElCollect.checked;
  const smoothScrollingElCollect = document.getElementById('setting-smooth-scrolling');
  if (smoothScrollingElCollect) settings.smooth_scrolling = smoothScrollingElCollect.checked;
  const notifyAutoUpdatesElCollect = document.getElementById('setting-notify-auto-mod-updates');
  if (notifyAutoUpdatesElCollect) settings.notify_on_auto_mod_updates = notifyAutoUpdatesElCollect.checked;
  const autoCheckLauncherUpdatesElCollect = document.getElementById('setting-auto-check-launcher-updates');
  if (autoCheckLauncherUpdatesElCollect) settings.auto_check_launcher_updates = autoCheckLauncherUpdatesElCollect.checked;

  // Performance & Java
  const elGameDir = document.getElementById('setting-game-dir');
  if (elGameDir) settings.game_directory = elGameDir.value;
  const elMinRam = document.getElementById('setting-min-ram');
  if (elMinRam) settings.min_ram_mb = parseInt(elMinRam.value) || 512;
  const elMaxRam = document.getElementById('setting-max-ram');
  if (elMaxRam) settings.max_ram_mb = parseInt(elMaxRam.value) || 4096;
  const elJavaSelect = document.getElementById('setting-java-select');
  if (elJavaSelect && elJavaSelect.value !== '__browse__') {
    const jp = elJavaSelect.value.trim();
    settings.java_path = jp || null;
  }
  const elJvmArgs = document.getElementById('setting-jvm-args');
  if (elJvmArgs) settings.jvm_args = elJvmArgs.value.trim();
  const elDlThreadsAutoCollect = document.getElementById('setting-download-threads-auto');
  if (elDlThreadsAutoCollect) settings.download_threads_auto = elDlThreadsAutoCollect.checked;
  const elDlThreadsCollect = document.getElementById('setting-download-threads');
  if (elDlThreadsCollect) settings.download_threads = Math.min(16, Math.max(1, parseInt(elDlThreadsCollect.value) || 3));

  // Discord RPC
  settings.enable_discord_rpc = document.getElementById('setting-enable-discord-rpc').checked;
  settings.rpc_show_in_launcher = document.getElementById('setting-rpc-show-in-launcher').checked;
  settings.rpc_show_instance_name = document.getElementById('setting-rpc-show-instance').checked;
  settings.rpc_show_minecraft_version = document.getElementById('setting-rpc-show-version').checked;
  settings.rpc_show_server_ip = document.getElementById('setting-rpc-show-server-ip').checked;
  settings.rpc_show_game_state = document.getElementById('setting-rpc-show-game-state').checked;
  settings.rpc_custom_state_text = document.getElementById('setting-rpc-custom-state').value.trim();

  const appIdSelVal = document.getElementById('setting-rpc-app-id').value;
  if (appIdSelVal === 'custom') {
    settings.rpc_app_id = document.getElementById('setting-rpc-custom-appid').value.trim();
  } else {
    settings.rpc_app_id = appIdSelVal;
  }

  settings.rpc_show_launcher_activity = document.getElementById('setting-rpc-show-launcher-activity').checked;
  settings.rpc_tab_instances = document.getElementById('setting-rpc-tab-instances').checked;
  settings.rpc_tab_mods = document.getElementById('setting-rpc-tab-mods').checked;
  settings.rpc_tab_settings = document.getElementById('setting-rpc-tab-settings').checked;
  settings.rpc_tab_logs = document.getElementById('setting-rpc-tab-logs').checked;

  settings.rpc_state_launching = document.getElementById('setting-rpc-state-launching').checked;
  settings.rpc_state_main_menu = document.getElementById('setting-rpc-state-main-menu').checked;
  settings.rpc_state_singleplayer = document.getElementById('setting-rpc-state-singleplayer').checked;
  settings.rpc_state_multiplayer = document.getElementById('setting-rpc-state-multiplayer').checked;

  // Privacy & Developer
  const hideUsernameEl2 = document.getElementById('setting-hide-username');
  if (hideUsernameEl2) settings.hide_username = hideUsernameEl2.checked;
  const clearSessionChk2 = document.getElementById('setting-clear-session-on-exit');
  if (clearSessionChk2) settings.clear_session_on_exit = clearSessionChk2.checked;
  settings.redact_tokens = document.getElementById('setting-redact-tokens').checked;
  const redactPathsChk2 = document.getElementById('setting-redact-paths');
  if (redactPathsChk2) settings.redact_paths = redactPathsChk2.checked;
  const hideLaunchCmdChk2 = document.getElementById('setting-hide-launch-command');
  if (hideLaunchCmdChk2) settings.hide_launch_command = hideLaunchCmdChk2.checked;
  settings.debug_mode = document.getElementById('setting-debug-mode').checked;
  applyUsernamePrivacy();

  // Experimental
  const crashAnalysisChk = document.getElementById('setting-crash-analysis');
  if (crashAnalysisChk) settings.enable_crash_analysis = crashAnalysisChk.checked;
  const autoOpenConsoleChk = document.getElementById('setting-auto-open-console');
  if (autoOpenConsoleChk) settings.auto_open_console_on_launch = autoOpenConsoleChk.checked;

  // Preserve Setup Wizard status
  if (prevFinishedSetup !== undefined) settings.Finished_setup = prevFinishedSetup;
  if (prevSetupFinished !== undefined) settings.setup_finished = prevSetupFinished;
}

async function saveSettingsNow() {
  if (!settings) return;
  const prevDir = settings.game_directory;
  collectSettingsFromUI();
  applyThemeFromSettings();
  try {
    await api.updateSettings(settings);
    if (settings.game_directory !== prevDir) {
      await refreshInstances();
      renderInstanceList();
    }
  } catch (e) { showToast('Failed to save settings: ' + e, 'error'); }
}

function saveSettingsDebounced() {
  clearTimeout(_settingsSaveTimer);
  _settingsSaveTimer = setTimeout(saveSettingsNow, 500);
}

// Cache of the last-fetched Java installation list, keyed by home path,
// so we can re-select the right option after a refresh without re-fetching.
let _lastJavaInstallations = [];

function javaOptionLabel(inst) {
  const kind = inst.source === 'managed' ? ' (downloaded)' : '';
  const bits = inst.is_64bit === false ? ' 32-bit' : '';
  return `Java ${inst.major} — ${inst.version}${bits}${kind}`;
}

// Adds (or updates) a one-off "custom path" option for a manually-browsed
// Java executable/home that isn't part of the auto-detected list, and
// selects it.
function addCustomJavaOption(path, select) {
  const sel = document.getElementById('setting-java-select');
  if (!sel) return;
  const existing = Array.from(sel.options).find(o => o.value === path);
  if (!existing) {
    const opt = document.createElement('option');
    opt.value = path;
    opt.textContent = `Custom: ${path}`;
    // Insert right before the "Browse..." option (always last).
    const browseOpt = Array.from(sel.options).find(o => o.value === '__browse__');
    if (browseOpt) sel.insertBefore(opt, browseOpt);
    else sel.appendChild(opt);
  }
  if (select) sel.value = path;
}

const MANAGED_JAVA_TARGETS = [
  { major: 25, label: 'Java 25' },
  { major: 21, label: 'Java 21' },
  { major: 17, label: 'Java 17' },
  { major: 16, label: 'Java 16' },
  { major: 8, label: 'Java 8' },
];

let _managedJavaInstalling = {}; // major -> { stage, message, percent }

async function renderJavaManager() {
  const cardsContainer = document.getElementById('java-managed-cards-container');
  const customContainer = document.getElementById('java-custom-cards-container');
  const systemContainer = document.getElementById('java-system-list-container');
  const rootPathLabel = document.getElementById('java-managed-root-path-label');
  if (!cardsContainer) return;

  try {
    const rootPath = await api.getManagedJavaRootPath();
    if (rootPathLabel) rootPathLabel.textContent = rootPath;
  } catch (_) {}

  let installs = [];
  try {
    installs = await api.listJavaInstallations();
    _lastJavaInstallations = installs || [];
  } catch (e) {
    installs = _lastJavaInstallations || [];
  }

  // 1. Render Official Managed Runtimes List
  cardsContainer.innerHTML = '';
  MANAGED_JAVA_TARGETS.forEach(target => {
    const installed = (installs || []).find(i => i.major === target.major && i.source === 'managed');
    const isInstalling = _managedJavaInstalling[target.major];

    const row = document.createElement('div');
    row.className = `java-row-item ${installed ? 'installed' : ''}`;

    let actionsHtml = '';

    if (isInstalling) {
      actionsHtml = `
        <div style="display:flex; align-items:center; gap:8px;">
          <span style="font-size:12px; color:var(--accent);">${escapeHtml(isInstalling.stage || 'Installing…')}</span>
          <button type="button" class="btn-java-action" disabled>Installing…</button>
        </div>
      `;
    } else if (installed) {
      actionsHtml = `<span class="java-installed-label">Installed</span>`;
    } else {
      actionsHtml = `
        <button type="button" class="btn-java-action btn-install-java" data-major="${target.major}">
          Install
        </button>
      `;
    }

    row.innerHTML = `
      <div class="java-row-info" style="display:flex; flex-direction:row; align-items:center; gap:10px;">
        <span class="java-num-icon">${target.major}</span>
        <div class="java-row-title">
          <span>${escapeHtml(target.label)}</span>
        </div>
      </div>
      <div class="java-row-actions">
        ${actionsHtml}
      </div>
    `;

    cardsContainer.appendChild(row);
  });

  // Attach Managed List Listeners
  cardsContainer.querySelectorAll('.btn-install-java').forEach(btn => {
    btn.addEventListener('click', async () => {
      const major = parseInt(btn.getAttribute('data-major'));
      if (!major) return;
      btn.disabled = true;
      btn.textContent = 'Installing…';
      _managedJavaInstalling[major] = { stage: 'Downloading', message: `Downloading Java ${major}…`, percent: 10 };
      renderJavaManager();
      try {
        await api.installManagedJava(major);
        showToast(`Java ${major} successfully installed!`, 'success');
      } catch (e) {
        showToast(`Failed to install Java ${major}: ${e}`, 'error');
      } finally {
        delete _managedJavaInstalling[major];
        await renderJavaManager();
        await populateJavaDropdown(settings && settings.java_path);
      }
    });
  });

  // 2. Render Custom Javas List
  if (customContainer) {
    customContainer.innerHTML = '';
    const customInstalls = (installs || []).filter(i => i.source === 'custom');
    if (customInstalls.length === 0) {
      customContainer.innerHTML = `
        <div style="font-size:12px; color:var(--text-muted); padding:10px 14px; background:rgba(255,255,255,0.02); border:1px dashed var(--panel-border); border-radius:6px;">
          No custom Java installations added yet. Click "Browse for Java…" above to select an installed Java path.
        </div>
      `;
    } else {
      customInstalls.forEach(inst => {
        const item = document.createElement('div');
        item.className = 'java-row-item';
        item.innerHTML = `
          <div class="java-row-info" style="display:flex; flex-direction:row; align-items:center; gap:10px;">
            <span class="java-num-icon">${inst.major || '?'}</span>
            <div class="java-row-title">
              <span>Java ${inst.major} (Custom)</span>
            </div>
          </div>
          <div class="java-row-actions">
            <button type="button" class="btn-java-action btn-remove-custom-java" data-path="${escapeHtml(inst.path)}">
              Remove
            </button>
          </div>
        `;
        customContainer.appendChild(item);
      });

      customContainer.querySelectorAll('.btn-remove-custom-java').forEach(btn => {
        btn.addEventListener('click', async () => {
          const path = btn.getAttribute('data-path');
          if (!path) return;
          try {
            await api.removeCustomJavaPath(path);
            showToast('Custom Java path removed.', 'info');
          } catch (e) {
            showToast(`Could not remove Java path: ${e}`, 'error');
          } finally {
            await renderJavaManager();
            await populateJavaDropdown(settings && settings.java_path);
          }
        });
      });
    }
  }

  // 3. Render Detected System Javas List
  if (systemContainer) {
    systemContainer.innerHTML = '';
    const systemInstalls = (installs || []).filter(i => i.source === 'system');
    if (systemInstalls.length === 0) {
      systemContainer.innerHTML = `
        <div style="font-size:12px; color:var(--text-muted); padding:8px 12px; opacity:0.7;">
          No standard system JREs found in PATH or standard system directories.
        </div>
      `;
    } else {
      systemInstalls.forEach(inst => {
        const item = document.createElement('div');
        item.className = 'java-row-item';
        item.innerHTML = `
          <div class="java-row-info" style="display:flex; flex-direction:row; align-items:center; gap:10px;">
            <span class="java-num-icon">${inst.major || '?'}</span>
            <div class="java-row-title">
              <span>Java ${inst.major}</span>
            </div>
          </div>
          <div class="java-row-actions">
            <span style="font-size:11px; opacity:0.6; padding:4px 8px;">System</span>
          </div>
        `;
        systemContainer.appendChild(item);
      });
    }
  }
}

// Fetches every detected Java installation (system + previously downloaded)
// and rebuilds the Settings dropdown, preserving `currentValue` as the
// selected option (adding it as a "custom" entry if it isn't in the list).
async function populateJavaDropdown(currentValue, showFeedback) {
  const sel = document.getElementById('setting-java-select');
  if (!sel) return;

  let installs = [];
  try {
    installs = await api.listJavaInstallations();
    _lastJavaInstallations = installs || [];
  } catch (e) {
    if (showFeedback) showToast('Could not scan for Java installs: ' + e, 'error');
    installs = _lastJavaInstallations;
  }

  sel.innerHTML = '';
  const smartOpt = document.createElement('option');
  smartOpt.value = '';
  smartOpt.textContent = '✦ Smart Java Detection (Recommended)';
  sel.appendChild(smartOpt);

  (installs || []).forEach(inst => {
    const opt = document.createElement('option');
    opt.value = inst.path;
    opt.textContent = javaOptionLabel(inst);
    sel.appendChild(opt);
  });

  const browseOpt = document.createElement('option');
  browseOpt.value = '__browse__';
  browseOpt.textContent = 'Browse for custom Java…';
  sel.appendChild(browseOpt);

  const wanted = currentValue || '';
  const matches = Array.from(sel.options).some(o => o.value === wanted);
  if (wanted && !matches) {
    addCustomJavaOption(wanted, true);
  } else {
    sel.value = wanted;
  }

  if (showFeedback) {
    const count = (installs || []).length;
    showToast(`Found ${count} Java installation${count === 1 ? '' : 's'}.`, 'success');
  }
}

function initSettings() {
  // All checkboxes and selects save immediately on change
  const immediateIds = [
    'setting-bg-style', 'setting-bg-anim-style', 'setting-notif-style',
    'setting-bg-anim-speed', 'setting-bg-anim-intensity', 'setting-bg-anim-fps',
    'setting-bg-anim-enable', 'setting-transparency',
    'setting-use-bg-image', 'setting-bg-image-fit',
    'setting-bg-image-dim', 'setting-bg-image-brightness', 'setting-bg-image-blur',
    'setting-bg-image-tint', 'setting-bg-image-vignette',
    'setting-font-family',
    'setting-close-on-launch', 'setting-smart-close-on-launch', 'setting-minimize-on-launch',
    'setting-on-game-close', 'setting-system-tray',
    'setting-on-launcher-close', 'setting-always-hide-to-tray',
    'setting-mod-updates-startup', 'setting-confirm-destructive',
    'setting-auto-apply-instance-filters',
    'setting-smooth-scrolling', 'setting-notify-auto-mod-updates', 'setting-auto-check-launcher-updates',
    'setting-enable-discord-rpc',
    'setting-rpc-show-in-launcher', 'setting-rpc-show-instance', 'setting-rpc-show-version',
    'setting-rpc-show-server-ip', 'setting-rpc-show-game-state',
    'setting-rpc-app-id',
    'setting-rpc-show-launcher-activity',
    'setting-rpc-tab-instances', 'setting-rpc-tab-mods',
    'setting-rpc-tab-settings', 'setting-rpc-tab-logs',
    'setting-rpc-state-launching', 'setting-rpc-state-main-menu',
    'setting-rpc-state-singleplayer', 'setting-rpc-state-multiplayer',
    'setting-clear-session-on-exit',
    'setting-redact-tokens', 'setting-redact-paths', 'setting-hide-launch-command',
    'setting-debug-mode',
    'setting-crash-analysis',
    'setting-auto-open-console',
  ];
  immediateIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('change', saveSettingsNow);
  });

  // Notification style gets its own live preview: fire a toast in the new
  // style right away so the change is obvious without waiting for some
  // other action to trigger a notification later.
  const notifStyleEl = document.getElementById('setting-notif-style');
  if (notifStyleEl) {
    notifStyleEl.addEventListener('change', () => {
      const styleName = notifStyleEl.value;
      if (settings) settings.notification_style = styleName;
      showToast(`Notifications will now look like this (${styleName}).`, 'success', 'Notification style changed');
    });
  }

  // Text, number, and range inputs use debounced save
  const debouncedIds = [
    'setting-bg-anim-speed', 'setting-bg-anim-fps',
    'setting-bg-image-path',
    'setting-game-dir', 'setting-min-ram', 'setting-max-ram',
    'setting-jvm-args', 'setting-download-threads',
    'setting-rpc-custom-state', 'setting-rpc-custom-appid',
  ];
  debouncedIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('input', saveSettingsDebounced);
  });

  // App ID select dropdown listener for Custom input visibility
  const appIdSel = document.getElementById('setting-rpc-app-id');
  if (appIdSel) {
    appIdSel.addEventListener('change', () => {
      const wrap = document.getElementById('rpc-custom-appid-wrap');
      if (wrap) {
        if (appIdSel.value === 'custom') wrap.classList.remove('hidden');
        else wrap.classList.add('hidden');
      }
    });
  }

  // Java Version dropdown
  const javaSelectEl = document.getElementById('setting-java-select');
  if (javaSelectEl) {
    javaSelectEl.addEventListener('change', async () => {
      if (javaSelectEl.value === '__browse__') {
        try {
          const picked = await window.__TAURI__.dialog.open({ multiple: false });
          if (picked) {
            const path = Array.isArray(picked) ? picked[0] : picked;
            addCustomJavaOption(path, true);
            if (settings) settings.java_path = path;
            saveSettingsNow();
          } else {
            // User cancelled the picker — fall back to whatever was
            // actually selected before (usually Smart Detection).
            javaSelectEl.value = (settings && settings.java_path) || '';
          }
        } catch (e) {
          showToast('Could not open Java file picker: ' + e, 'error');
          javaSelectEl.value = (settings && settings.java_path) || '';
        }
        return;
      }
      saveSettingsNow();
    });
  }

  // Concurrent downloads: live-update the readout as the slider moves, and
  // dim/lock the manual slider whenever "Automatic" is checked.
  const dlThreadsAutoEl = document.getElementById('setting-download-threads-auto');
  const dlThreadsEl = document.getElementById('setting-download-threads');
  const dlThreadsValueEl = document.getElementById('download-threads-value');
  const dlThreadsWrapEl = document.getElementById('download-threads-manual-wrap');
  if (dlThreadsEl && dlThreadsValueEl) {
    dlThreadsEl.addEventListener('input', () => {
      dlThreadsValueEl.textContent = dlThreadsEl.value;
    });
  }
  if (dlThreadsAutoEl) {
    dlThreadsAutoEl.addEventListener('change', () => {
      if (dlThreadsWrapEl) {
        dlThreadsWrapEl.style.opacity = dlThreadsAutoEl.checked ? '0.5' : '1';
        dlThreadsWrapEl.style.pointerEvents = dlThreadsAutoEl.checked ? 'none' : 'auto';
      }
      collectSettingsFromUI();
      saveSettingsNow();
    });
  }

  const refreshJavaBtn = document.getElementById('btn-refresh-java');
  if (refreshJavaBtn) {
    refreshJavaBtn.addEventListener('click', async () => {
      const prevValue = javaSelectEl ? javaSelectEl.value : '';
      refreshJavaBtn.disabled = true;
      const prevLabel = refreshJavaBtn.textContent;
      refreshJavaBtn.textContent = '⟳ Scanning…';
      try {
        await populateJavaDropdown(prevValue, true);
        await renderJavaManager();
      } finally {
        refreshJavaBtn.disabled = false;
        refreshJavaBtn.textContent = prevLabel;
      }
    });
  }

  // Java Manager Panel Event Handlers
  const btnRefreshJavaManager = document.getElementById('btn-refresh-java-manager');
  if (btnRefreshJavaManager) {
    btnRefreshJavaManager.addEventListener('click', async () => {
      btnRefreshJavaManager.disabled = true;
      try {
        await renderJavaManager();
        await populateJavaDropdown(settings && settings.java_path, true);
      } finally {
        btnRefreshJavaManager.disabled = false;
      }
    });
  }

  const btnOpenManagedDir = document.getElementById('btn-open-managed-java-dir');
  if (btnOpenManagedDir) btnOpenManagedDir.addEventListener('click', () => api.openManagedJavaDir());

  const btnOpenJavaDirLink = document.getElementById('btn-open-java-dir-link');
  if (btnOpenJavaDirLink) btnOpenJavaDirLink.addEventListener('click', () => api.openManagedJavaDir());

  const btnAddCustomJava = document.getElementById('btn-add-custom-java');
  if (btnAddCustomJava) {
    btnAddCustomJava.addEventListener('click', async () => {
      try {
        const picked = await window.__TAURI__.dialog.open({ multiple: false, title: 'Select Java Executable or Home Directory' });
        if (picked) {
          const path = Array.isArray(picked) ? picked[0] : picked;
          const added = await api.addCustomJavaPath(path);
          showToast(`Added Java ${added.major} (v${added.version})!`, 'success');
          await renderJavaManager();
          await populateJavaDropdown(path);
        }
      } catch (e) {
        showToast('Could not add custom Java: ' + e, 'error');
      }
    });
  }

  // Show progress while Smart Java Detection downloads a missing runtime —
  // gets its own card in the downloads menu (id must match the backend's
  // `java-<major>` cancel-flag id so Cancel actually aborts the transfer).
  const javaWidgetActive = {};
  const javaInstallActive = {};
  api.onJavaInstallProgress((event) => {
    const p = event.payload || {};
    const major = p.major;
    const dlId = `java-${major}`;

    if (p.stage === 'done' || p.stage === 'error') {
      delete javaInstallActive[major];
      javaInstallInProgress = Object.keys(javaInstallActive).length > 0;

      if (javaWidgetActive[major]) {
        delete javaWidgetActive[major];
        if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, p.stage !== 'error', p.message);
      } else {
        showToast(p.message, p.stage === 'error' ? 'error' : 'success', 'Smart Java Detection');
      }
      if (p.stage === 'done' && javaSelectEl) populateJavaDropdown(javaSelectEl.value);
      return;
    }

    javaInstallActive[major] = true;
    javaInstallInProgress = true;

    if (!javaWidgetActive[major]) {
      javaWidgetActive[major] = true;
      if (dlWidgetGeneric) {
        dlWidgetGeneric.begin(dlId, 'Smart Java Detection', p.message, { determinate: true, icon: 'java' });
      }
    }
    if (dlWidgetGeneric) dlWidgetGeneric.update(dlId, undefined, p.message, p.percent);
  });

  // Background image file picker
  const bgImageBrowseBtn = document.getElementById('btn-browse-bg-image');
  const bgImageClearBtn = document.getElementById('btn-clear-bg-image');
  if (bgImageBrowseBtn) {
    bgImageBrowseBtn.addEventListener('click', async () => {
      try {
        const picked = await window.__TAURI__.dialog.open({
          multiple: false,
          filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'] }],
        });
        if (picked) {
          const path = Array.isArray(picked) ? picked[0] : picked;
          document.getElementById('setting-bg-image-path').value = path;
          document.getElementById('setting-use-bg-image').checked = true;
          updateBackgroundImagePreview(path);
          collectSettingsFromUI();
          applyThemeFromSettings();
          saveSettingsNow();
        }
      } catch (e) {
        showToast('Could not open image picker: ' + e, 'error');
      }
    });
  }
  if (bgImageClearBtn) {
    bgImageClearBtn.addEventListener('click', () => {
      document.getElementById('setting-bg-image-path').value = '';
      document.getElementById('setting-use-bg-image').checked = false;
      updateBackgroundImagePreview('');
      collectSettingsFromUI();
      applyThemeFromSettings();
      saveSettingsNow();
    });
  }

  const bgPreviewInputs = [
    'setting-bg-image-fit',
    'setting-bg-image-dim',
    'setting-bg-image-brightness',
    'setting-bg-image-blur',
    'setting-bg-image-tint',
    'setting-bg-image-vignette',
  ];
  bgPreviewInputs.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      el.addEventListener('change', () => {
        updateBackgroundImagePreview(document.getElementById('setting-bg-image-path').value || '');
        collectSettingsFromUI();
        applyThemeFromSettings();
      });
      el.addEventListener('input', () => {
        updateBackgroundImagePreview(document.getElementById('setting-bg-image-path').value || '');
        collectSettingsFromUI();
        applyThemeFromSettings();
      });
    }
  });

  // Color pickers in Settings: Accent Color & Background Base Color
  document.getElementById('btn-open-accent-picker')?.addEventListener('click', () => {
    openCustomColorPicker({
      title: 'Accent Color',
      type: 'accent',
      initialColor: currentAccentColor(),
      onLiveChange: (hex) => {
        applyAccentColorLive(hex);
      },
      onApply: (hex) => {
        applyAccentColorLive(hex);
        applyThemeFromSettings();
        saveSettingsNow();
      }
    });
  });

  // Editable Hex Input for Accent Color
  const settingAccentHexInput = document.getElementById('setting-accent-hex-input');
  if (settingAccentHexInput) {
    const handleHex = () => {
      let val = settingAccentHexInput.value.trim();
      if (!val.startsWith('#')) val = '#' + val;
      if (/^#[0-9a-f]{6}$/i.test(val)) {
        applyAccentColorLive(val);
      }
    };
    settingAccentHexInput.addEventListener('input', handleHex);
    settingAccentHexInput.addEventListener('change', () => {
      handleHex();
      saveSettingsNow();
    });
  }

  // Theme Palettes "More Palettes" button
  document.getElementById('btn-browse-all-palettes')?.addEventListener('click', () => {
    openThemePalettesModal();
  });

  document.getElementById('btn-reset-accent-color')?.addEventListener('click', () => {
    applyAccentColorLive(ACCENT_DEFAULT);
    applyThemeFromSettings();
    saveSettingsNow();
    showToast('Accent color reset to default silver', 'info');
  });

  document.getElementById('btn-open-bg-color-picker')?.addEventListener('click', () => {
    openCustomColorPicker({
      title: 'Background Base Color',
      type: 'bg',
      initialColor: (settings && settings.bg_color) || '#0A0A0F',
      onLiveChange: (hex) => {
        applyBgColorLive(hex);
      },
      onApply: (hex) => {
        applyBgColorLive(hex);
        applyThemeFromSettings();
        saveSettingsNow();
      }
    });
  });

  // Editable Hex Input for Background Base Color
  const settingBgHexInput = document.getElementById('setting-bg-hex-input');
  if (settingBgHexInput) {
    const handleHex = () => {
      let val = settingBgHexInput.value.trim();
      if (!val.startsWith('#')) val = '#' + val;
      if (/^#[0-9a-f]{6}$/i.test(val)) {
        applyBgColorLive(val);
        applyThemeFromSettings();
        saveSettingsDebounced();
      }
    };
    settingBgHexInput.addEventListener('input', handleHex);
    settingBgHexInput.addEventListener('change', () => {
      handleHex();
      saveSettingsNow();
    });
  }

  document.getElementById('btn-reset-bg-color')?.addEventListener('click', () => {
    applyBgColorLive('#0A0A0F');
    applyThemeFromSettings();
    saveSettingsNow();
    showToast('Background base color reset to default dark', 'info');
  });

  // Hidden instances (Settings → Performance & Java)
  renderHiddenInstancesSettings();

  // Reopen Setup Wizard
  const reopenSetupBtn = document.getElementById('btn-reopen-setup');
  if (reopenSetupBtn) {
    reopenSetupBtn.addEventListener('click', () => {
      openSetupWizard(true);
    });
  }

  // Current Launcher Version (About & Initial Setup card)
  const launcherVersionEl = document.getElementById('settings-launcher-version');
  if (launcherVersionEl) {
    api.getLauncherVersion()
      .then(v => { launcherVersionEl.textContent = `v${v}`; })
      .catch(() => { launcherVersionEl.textContent = 'unknown'; });
  }

  // Open Zero Launcher Folder (About & Initial Setup card)
  const openLauncherFolderBtn = document.getElementById('btn-open-launcher-folder');
  if (openLauncherFolderBtn) {
    openLauncherFolderBtn.addEventListener('click', async () => {
      try {
        await api.openLauncherFolder();
      } catch (e) {
        console.error('Failed to open Zero Launcher folder:', e);
      }
    });
  }

  // Reset All Settings
  const resetBtn = document.getElementById('btn-reset-settings');
  if (resetBtn) {
    resetBtn.addEventListener('click', async () => {
      if (!confirm('Reset all settings to default? This cannot be undone.')) return;
      try {
        // Send default settings
        settings = await api.getSettings();
        // Reset by saving an empty object which defaults everything.
        // NOTE: `game_directory` is deliberately NOT included here. It's
        // not a UI preference, it's the on-disk location of the user's
        // instances/mods (<game_directory>/versions/instances.json). If
        // it were reset to '', save_settings() sees the directory as
        // "changed" and reloads instances from the default .minecraft
        // folder instead, making the user's real instances vanish from
        // the UI (they're still on disk, just no longer pointed at).
        const defaultSettings = {
          accent_color: ACCENT_DEFAULT,
          font_family: 'JetBrains Mono, Fira Code, Consolas, Monaco, monospace',
          background_style: 'Default',
          background_animation_style: 'Waves',
          background_animation_speed: 1.0,
          background_animation_intensity: 1.0,
          background_animation_fps: 60,
          enable_background_animation: true,
          enable_transparency: true,
          use_background_image: false,
          background_image_path: '',
          background_image_fit: 'Cover',
          background_image_dim: 20,
          background_image_brightness: 100,
          background_image_blur: 0,
          background_image_tint: false,
          background_image_vignette: true,
          notification_style: 'Minimal Outline',
          close_after_launch: true,
          minimize_on_launch: false,
          on_game_close: 'show',
          enable_system_tray: true,
          on_launcher_close: 'tray',
          always_hide_to_tray: false,
          check_mod_updates_on_startup: true,
          sound_effects_enabled: true,
          confirm_destructive_actions: true,
          min_ram_mb: 512,
          max_ram_mb: 4096,
          java_path: null,
          jvm_args: '',
          download_threads_auto: true,
          download_threads: 3,
          enable_discord_rpc: true,
          rpc_show_in_launcher: true,
          rpc_show_instance_name: true,
          rpc_show_minecraft_version: true,
          rpc_show_server_ip: false,
          rpc_show_game_state: false,
          rpc_custom_state_text: 'In Zero Launcher',
          rpc_app_id: '1131048770109460500',
          rpc_show_launcher_activity: false,
          rpc_tab_instances: true,
          rpc_tab_mods: true,
          rpc_tab_settings: true,
          rpc_tab_logs: true,
          rpc_state_launching: false,
          rpc_state_main_menu: false,
          rpc_state_singleplayer: false,
          rpc_state_multiplayer: false,
          hide_username: false,
          redact_tokens: true,
          debug_mode: false,
          enable_crash_analysis: false,
          auto_open_console_on_launch: false,
        };
        // Preserve non-UI fields from current settings
        Object.assign(settings, defaultSettings);
        await api.updateSettings(settings);
        populateSettingsUI();
        applyThemeFromSettings();
        BG.applyBackgroundImage();
        showToast('Settings reset to defaults', 'success');
      } catch (e) {
        showToast('Failed to reset settings: ' + e, 'error');
      }
    });
  }

  // Settings Modal Open/Close Buttons
  const openSettingsBtn = document.getElementById('btn-open-settings-modal');
  if (openSettingsBtn) {
    openSettingsBtn.addEventListener('click', () => {
      const overlay = document.getElementById('settings-modal-overlay');
      if (overlay && !overlay.classList.contains('hidden')) {
        closeSettingsModal();
      } else {
        openSettingsModal();
      }
    });
  }

  const closeSettingsBtn = document.getElementById('btn-close-settings-modal');
  if (closeSettingsBtn) {
    closeSettingsBtn.addEventListener('click', () => {
      closeSettingsModal();
    });
  }

  // Close modal when clicking outside on overlay backdrop
  const settingsOverlay = document.getElementById('settings-modal-overlay');
  if (settingsOverlay) {
    settingsOverlay.addEventListener('click', (e) => {
      if (e.target === settingsOverlay) {
        closeSettingsModal();
      }
    });
  }

  // Sidebar Tab Switching (Left navigation pane)
  const navBtns = document.querySelectorAll('.settings-modal-sidebar .settings-nav-btn');
  const savedActiveSection = localStorage.getItem('zero_settings_last_section') || 'appearance';
  switchSettingsSection(savedActiveSection);

  navBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetSection = btn.dataset.section;
      if (targetSection) {
        switchSettingsSection(targetSection);
      }
    });
  });
}

function openSettingsModal(targetSection) {
  const overlay = document.getElementById('settings-modal-overlay');
  if (!overlay) return;
  overlay.classList.remove('hidden');
  loadSettings();
  renderHiddenInstancesSettings();
  renderJavaManager();
  if (targetSection) {
    switchSettingsSection(targetSection);
  }
}

function closeSettingsModal() {
  const overlay = document.getElementById('settings-modal-overlay');
  if (overlay) overlay.classList.add('hidden');
}

function switchSettingsSection(sectionName) {
  const navBtns = document.querySelectorAll('.settings-modal-sidebar .settings-nav-btn');
  const panels = document.querySelectorAll('.settings-modal-content .settings-tab-panel');
  if (!navBtns.length || !panels.length) return;

  let found = false;
  navBtns.forEach(btn => {
    const isTarget = btn.dataset.section === sectionName;
    btn.classList.toggle('active', isTarget);
    if (isTarget) found = true;
  });

  // Fallback to first if sectionName not matched
  if (!found && navBtns.length > 0) {
    navBtns[0].classList.add('active');
    sectionName = navBtns[0].dataset.section;
  }

  panels.forEach(panel => {
    panel.classList.toggle('active', panel.id === `settings-panel-${sectionName}`);
  });

  if (sectionName) {
    localStorage.setItem('zero_settings_last_section', sectionName);
  }
}

/// Render the "Hidden Instances" list in Settings → Performance & Java —
/// every instance that's missing from the main Instances list, whether the
/// user hid it manually (currently only reachable from the vanilla-instance
/// delete-dependency warning) or it was auto-hidden for being a vanilla
/// version never installed through this launcher — each with an Unhide
/// button.
async function renderHiddenInstancesSettings() {
  const listEl = document.getElementById('hidden-instances-list');
  if (!listEl) return;

  try {
    hiddenInstancesCache = await api.getHiddenInstances();
  } catch (e) {
    console.error('Failed to load hidden instances:', e);
  }

  const allInstances = getInstances();
  const vanillaOverrides = getVanillaOverrides();
  const autoHiddenVanilla = allInstances.filter(i => {
    if (hiddenInstancesCache.includes(i.version_id)) return false; // already listed as manual
    const isVanilla = (i.loader || 'vanilla').toLowerCase() === 'vanilla';
    return isVanilla && !i.installed_at && !vanillaOverrides.includes(i.version_id);
  });

  if (!hiddenInstancesCache.length && !autoHiddenVanilla.length) {
    listEl.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_EYE_OFF_SVG}</span><span>No hidden instances</span></div>`;
    return;
  }

  listEl.innerHTML = '';

  const makeRow = (versionId, inst, isAuto) => {
    const row = document.createElement('div');
    row.className = 'instance-card hidden-instance-card';
    const loaderStr = inst ? loaderLabel(inst.loader) : null;
    const metaBits = [loaderStr, inst && inst.minecraft_version ? inst.minecraft_version : null]
      .filter(Boolean).join(' • ');
    row.innerHTML = `
      <div class="inst-icon"><img src="${loaderIcon(inst ? inst.loader : 'vanilla')}" alt="${loaderStr || 'Vanilla'}" draggable="false" /></div>
      <div class="inst-text">
        <div class="inst-name">${(inst && (inst.name || inst.version_id)) || versionId}</div>
        <div class="inst-version">${metaBits || versionId}${isAuto ? ' • auto-hidden vanilla version' : ''}</div>
      </div>
      <button type="button" class="btn-secondary btn-sm btn-unhide-instance">Unhide</button>
    `;
    row.querySelector('.btn-unhide-instance').addEventListener('click', async () => {
      try {
        if (isAuto) {
          addVanillaOverride(versionId);
        } else {
          await api.unhideInstance(versionId);
        }
        await refreshInstances();
        renderInstanceList();
        await renderHiddenInstancesSettings();
        showToast(`"${(inst && (inst.name || inst.version_id)) || versionId}" unhidden`, 'success');
      } catch (e) {
        showToast('Failed to unhide instance: ' + e, 'error');
      }
    });
    listEl.appendChild(row);
  };

  hiddenInstancesCache.forEach(versionId => {
    const inst = allInstances.find(i => i.version_id === versionId);
    makeRow(versionId, inst, false);
  });
  autoHiddenVanilla.forEach(inst => makeRow(inst.version_id, inst, true));
}

// ══════════════════════════════════════════════════════════════════
// RUNNING INSTANCES WIDGET
// ══════════════════════════════════════════════════════════════════
let runningInstancesCache = [];

function runningCount() {
  return runningInstancesCache.filter(i => i.running).length;
}

function updatePlayButtonRunningState() {
  const btn = document.getElementById('btn-play');
  if (!btn) return;
  const count = runningCount();
  // Preserve the "LAUNCHING…" label while a launch is in flight; only
  // touch the idle "PLAY" label here.
  if (btn.dataset.launching === '1') return;

  btn.classList.toggle('is-running', count > 0);
  if (count > 0) {
    btn.innerHTML = `▶ PLAY <span class="btn-play-count">➤ ${count}</span>`;
  } else {
    btn.textContent = '▶  PLAY';
  }
}

// Each running instance gets its own centered button. Clicking a button
// opens a small dropdown with two actions: "Logs" (opens the console
// window) and "Kill Instance" (force-stops the game process).
let openRiDropdownId = null;

function closeRiDropdown() {
  openRiDropdownId = null;
  document.querySelectorAll('.ri-btn.is-open, .ri-overflow-row.is-open').forEach(b => b.classList.remove('is-open'));
  document.querySelectorAll('.ri-dropdown').forEach(d => {
    d.classList.add('ri-dropdown-closing');
    d.addEventListener('animationend', () => d.remove(), { once: true });
  });
}

// Dropdown markup shared by both the per-instance "Running" pill and the
// "+N" overflow rows — same two actions, same launcher-style look, just a
// different anchor element.
function buildRiDropdownActions(inst, onClosed) {
  const dropdown = document.createElement('div');
  dropdown.className = 'ri-dropdown';
  dropdown.innerHTML = `
    <button class="ri-dropdown-item ri-dropdown-logs" type="button">
      <span class="ri-dropdown-item-icon">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none"><rect x="2" y="2.5" width="12" height="11" rx="1.6" stroke="currentColor" stroke-width="1.4"/><path d="M4.5 6h7M4.5 8.5h7M4.5 11h4.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
      </span>
      <span class="ri-dropdown-item-label">View Logs</span>
    </button>
    <button class="ri-dropdown-item ri-dropdown-kill" type="button">
      <span class="ri-dropdown-item-icon">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none"><path d="M5 5l6 6M11 5l-6 6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
      </span>
      <span class="ri-dropdown-item-label">Kill Instance</span>
    </button>
  `;

  dropdown.querySelector('.ri-dropdown-logs').addEventListener('click', (e) => {
    e.stopPropagation();
    openInstanceConsole(inst.version_id, inst.name || inst.version_id);
    onClosed();
  });
  dropdown.querySelector('.ri-dropdown-kill').addEventListener('click', async (e) => {
    e.stopPropagation();
    onClosed();
    try {
      await api.killInstance(inst.version_id);
      showToast(`Killed ${inst.name || inst.version_id}`, 'success');
    } catch (err) {
      showToast('Failed to kill instance: ' + err, 'error');
    }
    refreshRunningInstances();
  });

  return dropdown;
}

function openRiDropdown(anchorBtn, inst) {
  closeRiDropdown();
  openRiDropdownId = inst.version_id;
  anchorBtn.classList.add('is-open');
  anchorBtn.appendChild(buildRiDropdownActions(inst, closeRiDropdown));
}

// ── Overflow menu ("+N") ──
// Anchors a stacked list of the running instances that didn't fit on the
// single line — one row per instance, each opening the same launcher-style
// logs/kill dropdown as the inline pills.
let openRiOverflow = false;

function closeRiOverflowMenu() {
  openRiOverflow = false;
  closeRiDropdown();
  const moreBtn = document.querySelector('.ri-btn-more.is-open');
  if (moreBtn) moreBtn.classList.remove('is-open');
  document.querySelectorAll('.ri-overflow-menu').forEach(m => {
    m.classList.add('ri-dropdown-closing');
    m.addEventListener('animationend', () => m.remove(), { once: true });
  });
}

function openRiOverflowMenu(moreBtn, hiddenInstances) {
  closeRiDropdown();
  openRiOverflow = true;
  moreBtn.classList.add('is-open');

  const menu = document.createElement('div');
  menu.className = 'ri-overflow-menu';
  hiddenInstances.forEach(inst => {
    const row = document.createElement('div');
    row.className = 'ri-overflow-row';
    row.dataset.versionId = inst.version_id;
    row.innerHTML = `
      <span class="ri-btn-icon"><img src="${loaderIcon(inst.loader)}" alt="" draggable="false" /></span>
      <span class="ri-btn-label"><span class="ri-btn-status">Running</span> <span class="ri-btn-name">${inst.name || inst.version_id}</span></span>
      <span class="ri-btn-caret">⌄</span>
    `;
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      if (openRiDropdownId === inst.version_id) {
        closeRiDropdown();
        return;
      }
      closeRiDropdown();
      openRiDropdownId = inst.version_id;
      row.classList.add('is-open');
      const dd = buildRiDropdownActions(inst, closeRiDropdown);
      dd.classList.add('ri-dropdown-in-overflow');
      row.appendChild(dd);
    });
    menu.appendChild(row);
  });

  moreBtn.appendChild(menu);
}

function renderRunningInstancesPanel() {
  const container = document.getElementById('ri-buttons');
  if (!container) return;

  const running = runningInstancesCache.filter(i => i.running);

  if (running.length === 0) {
    closeRiDropdown();
    closeRiOverflowMenu();
    container.innerHTML = `<span id="ri-empty-label" class="hero-empty-label">No instances launched</span>`;
    return;
  }

  const emptyLabel = document.getElementById('ri-empty-label');
  if (emptyLabel) emptyLabel.remove();

  const runningIds = new Set(running.map(i => i.version_id));

  // Remove buttons for instances that stopped, animating them out first.
  Array.from(container.querySelectorAll('.ri-btn:not(.ri-btn-more)')).forEach(child => {
    const id = child.dataset.versionId;
    if (id && !runningIds.has(id)) {
      child.classList.add('ri-btn-leaving');
      child.addEventListener('animationend', () => child.remove(), { once: true });
    }
  });

  running.forEach(inst => {
    let btn = container.querySelector(`.ri-btn[data-version-id="${CSS.escape(inst.version_id)}"]`);
    const isNew = !btn;
    if (isNew) {
      btn = document.createElement('div');
      btn.dataset.versionId = inst.version_id;
      btn.className = 'ri-btn ri-btn-entering';
      btn.addEventListener('animationend', () => btn.classList.remove('ri-btn-entering'), { once: true });
    }
    btn.dataset.name = inst.name || inst.version_id;
    btn.classList.toggle('is-open', openRiDropdownId === inst.version_id);
    btn.innerHTML = `
      <span class="ri-btn-icon"><img src="${loaderIcon(inst.loader)}" alt="" draggable="false" /></span>
      <span class="ri-btn-label"><span class="ri-btn-status">Running</span> <span class="ri-btn-name">${inst.name || inst.version_id}</span></span>
      <span class="ri-btn-caret">⌄</span>
    `;
    btn.onclick = (e) => {
      e.stopPropagation();
      if (openRiDropdownId === inst.version_id) {
        closeRiDropdown();
      } else {
        openRiDropdown(btn, inst);
      }
    };
    if (isNew) container.appendChild(btn);
  });

  if (openRiDropdownId && !running.some(i => i.version_id === openRiDropdownId)) {
    closeRiDropdown();
  }

  scheduleRiOverflowLayout();
}

// ── Single-line overflow layout ──
// The panel is one line only: every running-instance pill stays on one row
// and whatever doesn't fit collapses into a single "+N" button instead of
// wrapping or overflowing.
//
// WebKitGTK note: this used to measure each pill with getBoundingClientRect()
// in a loop, which forces a synchronous layout on every call. That's cheap on
// most engines but visibly janky on WebKitGTK's slower layout path, and it
// was firing on a ResizeObserver watching the buttons row — which can retrigger
// from incidental size changes elsewhere on the page (selecting an instance,
// the Play button changing state, etc.), not just real window resizes. Widths
// are now estimated with a <canvas> text measurement instead (no layout/reflow
// at all, since the pill font is monospace this is exact enough), and the
// layout pass only re-runs on an actual window resize (debounced) or when the
// running-instance list itself changes.
let _riMeasureCtx = null;
function measureRiPillWidth(name) {
  if (!_riMeasureCtx) _riMeasureCtx = document.createElement('canvas').getContext('2d');
  _riMeasureCtx.font = "700 12.5px 'JetBrains Mono', 'Fira Code', 'Consolas', 'Monaco', monospace";
  const nameWidth = _riMeasureCtx.measureText(name).width;
  // Fixed chrome: icon (22) + icon gap (9) + "Running" label (~52) + label gap
  // (5) + caret gap/width (~13) + horizontal padding (14+8) + a couple px slop.
  const CHROME_WIDTH = 22 + 9 + 52 + 5 + 13 + 14 + 8 + 4;
  return Math.min(140, nameWidth) + CHROME_WIDTH; // name itself clamps at 140px via CSS
}

let riOverflowRafId = null;
function scheduleRiOverflowLayout() {
  if (riOverflowRafId != null) return;
  riOverflowRafId = requestAnimationFrame(() => {
    riOverflowRafId = null;
    layoutRunningInstancesOverflow();
  });
}

function layoutRunningInstancesOverflow() {
  const container = document.getElementById('ri-buttons');
  if (!container) return;

  const existingMore = container.querySelector('.ri-btn-more');
  if (existingMore) existingMore.remove();
  if (openRiOverflow) closeRiOverflowMenu();

  const buttons = Array.from(container.querySelectorAll('.ri-btn:not(.ri-btn-more):not(.ri-btn-leaving)'));
  buttons.forEach(b => { b.style.display = ''; });
  if (buttons.length === 0) return;

  // Single unavoidable read: how much room the panel actually has. Everything
  // else below is pure arithmetic on canvas-measured (reflow-free) widths.
  const containerWidth = container.clientWidth;
  if (!containerWidth) return; // not visible/laid out yet

  const gap = 8;
  const widths = buttons.map(b => measureRiPillWidth(b.dataset.name || ''));
  const totalWidth = widths.reduce((a, w) => a + w, 0) + gap * (widths.length - 1);
  if (totalWidth <= containerWidth) return; // everything fits, nothing to collapse

  const moreBtnWidth = 52; // pill width estimate for "+N", fixed so it never needs its own measure pass
  const budget = containerWidth - moreBtnWidth - gap;

  let used = 0;
  let fitCount = 0;
  for (let i = 0; i < widths.length; i++) {
    const next = used + widths[i] + (i > 0 ? gap : 0);
    if (next > budget) break;
    used = next;
    fitCount++;
  }
  fitCount = Math.max(1, fitCount);

  const hidden = buttons.slice(fitCount);
  if (hidden.length === 0) return;
  hidden.forEach(b => { b.style.display = 'none'; });

  const more = document.createElement('button');
  more.type = 'button';
  more.className = 'ri-btn ri-btn-more';
  more.textContent = `+${hidden.length}`;
  more.title = `${hidden.length} more running instance${hidden.length === 1 ? '' : 's'}`;
  more.addEventListener('click', (e) => {
    e.stopPropagation();
    if (openRiOverflow) {
      closeRiOverflowMenu();
      return;
    }
    const hiddenIds = new Set(hidden.map(b => b.dataset.versionId));
    const hiddenInstances = runningInstancesCache.filter(i => i.running && hiddenIds.has(i.version_id));
    openRiOverflowMenu(more, hiddenInstances);
  });
  container.appendChild(more);
}

// Only a real window resize re-runs the overflow layout — not a
// ResizeObserver on the buttons row, which used to retrigger from unrelated
// layout shifts elsewhere on the page (selecting an instance, the Play
// button relabeling itself, etc.) and caused visible lag on WebKitGTK.
let riResizeDebounceId = null;
function initRiOverflowResizeObserver() {
  if (initRiOverflowResizeObserver._bound) return;
  initRiOverflowResizeObserver._bound = true;
  window.addEventListener('resize', () => {
    clearTimeout(riResizeDebounceId);
    riResizeDebounceId = setTimeout(scheduleRiOverflowLayout, 120);
  });
}

async function refreshRunningInstances() {
  try {
    runningInstancesCache = await api.getRunningInstances();
  } catch (e) {
    console.error('Failed to load running instances', e);
  }
  renderRunningInstancesPanel();
  updatePlayButtonRunningState();
  // A session ending is exactly when its accumulated total_playtime_seconds
  // changes on disk, so the Play Time row ought to pick that up — but
  // refreshInstances() does a live filesystem scan (scanMinecraftVersions)
  // plus several IPC round-trips and then rebuilds the whole instance list,
  // which is noticeably slow on WebKitGTK. That used to be awaited right
  // here, so every single Play click blocked on a full disk rescan before
  // the UI could respond — the "huge lag" on launch. It's not needed for
  // the running-instances UI itself (that's already updated above from the
  // cheap getRunningInstances() call), so let it run in the background
  // instead of holding up this function's caller.
  refreshInstances()
    .then(() => { updateSelectedInstancePlaytimeDisplay(); renderPlaytimeChart(); })
    .catch((e) => console.error('Failed to refresh instances after running-instances change', e));
  updateSelectedInstancePlaytimeDisplay();
}

async function openInstanceConsole(versionId, name) {
  try {
    await api.openInstanceConsoleWindow(versionId, name);
  } catch (e) {
    showToast('Failed to open console: ' + e, 'error');
  }
}

function initRunningInstancesWidget() {
  const container = document.getElementById('ri-buttons');
  if (!container) return;

  document.addEventListener('click', (e) => {
    if (openRiOverflow && !e.target.closest('.ri-btn-more')) {
      closeRiOverflowMenu();
      return;
    }
    if (openRiDropdownId && !e.target.closest('.ri-btn') && !e.target.closest('.ri-overflow-row')) {
      closeRiDropdown();
    }
  });

  // Live updates: the backend fires this whenever an instance starts or
  // stops, so the buttons/Play button stay in sync without polling.
  api.onRunningInstancesChanged(() => {
    refreshRunningInstances();
    // A running instance just started or (more importantly here) finished
    // a session — advancements may have changed, so force the Game
    // Advancements stat to recount next render rather than trusting its
    // shape-based cache.
    playtimeAdvancementsGeneration++;
    renderGlobalPlaytimeStats();
  });

  // Live updates: the backend watches each running instance's game log and
  // fires this the instant it sees an advancement/goal/challenge line, so
  // the Game Advancements stat ticks up immediately instead of waiting for
  // the session to end and the save files to be rescanned. The eventual
  // rescan (triggered above via playtimeAdvancementsGeneration once the
  // session ends) still runs and is the source of truth — this is purely
  // for instant feedback while playing.
  api.onGameAdvancement(() => {
    const advEl = document.getElementById('playtime-stat-advancements');
    if (advEl) {
      const current = parseInt(advEl.textContent, 10);
      if (!Number.isNaN(current)) {
        advancementsFloor = Math.max(advancementsFloor, current + 1);
        advEl.textContent = String(advancementsFloor);
        persistGlobalStats();
      }
    }
  });

  initRiOverflowResizeObserver();
  refreshRunningInstances();
}

// ══════════════════════════════════════════════════════════════════
// BACKGROUND ENGINE — Complete rewrite
// ══════════════════════════════════════════════════════════════════

const BG = {
  canvas: null,
  ctx: null,
  animId: null,
  phase: 0,
  lastTime: 0,
  particles: [],
  orbs: [],

  // Set while a frame is scheduled via requestAnimationFrame, so callers
  // (resize, settings changes, visibility) know whether they need to kick
  // the loop back on or can just let the already-scheduled frame pick up
  // the change.
  _scheduled: false,

  init() {
    this.canvas = document.getElementById('bg-canvas');
    if (!this.canvas) return;
    this.ctx = this.canvas.getContext('2d', { alpha: true });
    this.resize();
    window.addEventListener('resize', () => { this.resize(); this.requestRedraw(); });
    // Pause entirely while the window is minimized/hidden/unfocused instead
    // of continuing to redraw an animation nobody can see — this is the
    // single biggest idle CPU/GPU cost the launcher had. Resume on return.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        if (this.animId) cancelAnimationFrame(this.animId);
        this.animId = null;
        this._scheduled = false;
        if (skinMiniPreviewInstance) skinMiniPreviewInstance.renderPaused = true;
        if (skinViewerInstance) skinViewerInstance.renderPaused = true;
      } else {
        this.requestRedraw();
        const skinModalOpen = !document.getElementById('skin-viewer-overlay')?.classList.contains('hidden');
        const dressingOpen = !document.getElementById('dressing-room-overlay')?.classList.contains('hidden');
        if (skinViewerInstance && skinModalOpen) skinViewerInstance.renderPaused = false;
        if (skinMiniPreviewInstance && !skinModalOpen && !dressingOpen) {
          skinMiniPreviewInstance.renderPaused = false;
        }
      }
    });
    // `document.hidden`/`visibilitychange` is what drives the pause above,
    // but it's unreliable for a *natively* hidden window (tray hide isn't a
    // tab switch): on some platforms/GPU drivers the WebGL context is
    // actually torn down while the window is off-screen, and by the time
    // `visibilitychange` fires again the context may not have finished
    // coming back yet, so just flipping `renderPaused` off can be a no-op.
    // The Rust side emits this explicit event on every tray/dock restore
    // (see `launcher-shown` in lib.rs) as a second, more reliable signal to
    // hard-resume the 3D preview(s) — see `resumeSkinViewersAfterShow`.
    // Tray/dock restores get an extra beat before we touch WebGL: unlike a
    // plain tab switch (where the context is usually still warm and the
    // visibilitychange handler above resumes it right away), a window
    // that's been natively hidden needs a bit longer for the OS to finish
    // showing/compositing it before poking the renderer does any good.
    listen('launcher-shown', () => setTimeout(() => resumeSkinViewersAfterShow(), 2000));
    this.createParticles();
    this.createOrbs();
    this.loop(0);
  },

  // Ensures a frame is scheduled without stacking duplicate rAF loops —
  // used after the loop has gone idle (static scene, or paused for
  // visibility) and something needs to be redrawn once.
  requestRedraw() {
    if (document.hidden) return;
    if (this._scheduled) return;
    this._scheduled = true;
    this.animId = requestAnimationFrame((t) => this.loop(t));
  },

  resize() {
    if (!this.canvas) return;
    const oldW = this.canvas.width || window.innerWidth;
    const oldH = this.canvas.height || window.innerHeight;
    this.canvas.width = window.innerWidth;
    this.canvas.height = window.innerHeight;
    const newW = this.canvas.width;
    const newH = this.canvas.height;
    // Rescale particle positions to fill the new canvas area
    if (oldW > 0 && oldH > 0) {
      this.particles.forEach(p => {
        p.x = (p.x / oldW) * newW;
        p.baseX = (p.baseX / oldW) * newW;
        p.y = (p.y / oldH) * newH;
      });
    }
  },

  createParticles() {
    this.particles = [];
    for (let i = 0; i < 40; i++) {
      this.particles.push({
        x: Math.random() * window.innerWidth,
        y: Math.random() * window.innerHeight,
        baseX: Math.random() * window.innerWidth,
        size: 1.2 + Math.random() * 3.8,
        alpha: Math.random() * 0.5,
        alphaDir: (Math.random() * 0.008 + 0.002) * (Math.random() > 0.5 ? 1 : -1),
        vy: 0.12 + Math.random() * 0.35,
        driftX: (Math.random() - 0.5) * 0.25,
        swayPhase: Math.random() * Math.PI * 2,
        swaySpeed: 0.008 + Math.random() * 0.018,
        swayAmp: 10 + Math.random() * 22,
      });
    }
  },

  createOrbs() {
    // Reused for both 'Orbs' (legacy) and 'Aurora' band data.
    // Three bands evenly spread vertically with staggered phases.
    this.orbs = [
      { xFrac: 0.5, yFrac: 0.25, radius: 160, phase: 0,                    speed: 0.18 },
      { xFrac: 0.5, yFrac: 0.55, radius: 180, phase: Math.PI * 0.66,       speed: 0.13 },
      { xFrac: 0.5, yFrac: 0.78, radius: 140, phase: Math.PI * 1.33,       speed: 0.22 },
    ];
  },

  hexToRgb(hex) {
    hex = (hex || '#10b981').replace('#', '');
    if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
    return [
      parseInt(hex.substring(0, 2), 16) || 0,
      parseInt(hex.substring(2, 4), 16) || 0,
      parseInt(hex.substring(4, 6), 16) || 0,
    ];
  },

  getChromaColor(timestamp) {
    const s = settings || {};
    const speed = s.chroma_speed || 5;
    const brightness = (s.chroma_brightness ?? 100) / 100;
    const palette = s.chroma_palette || 'Rainbow';

    const t = (timestamp * 0.0002 * speed) % 1;

    let r = 0, g = 0, b = 0;

    if (palette === 'Neon') {
      // Cyan -> Pink -> Purple -> Cyan
      const h = (t * 3) % 3;
      if (h < 1) { r = Math.round(255 * h); g = 240; b = 255; }
      else if (h < 2) { r = 255; g = Math.round(240 * (2 - h)); b = 255; }
      else { r = Math.round(255 * (3 - h)); g = 0; b = 255; }
    } else if (palette === 'Cyber') {
      // Yellow -> Magenta -> Cyan
      const h = (t * 3) % 3;
      if (h < 1) { r = 255; g = Math.round(255 * (1 - h)); b = Math.round(255 * h); }
      else if (h < 2) { r = Math.round(255 * (2 - h)); g = Math.round(255 * (h - 1)); b = 255; }
      else { r = Math.round(255 * (h - 2)); g = 255; b = Math.round(255 * (3 - h)); }
    } else if (palette === 'Fire') {
      // Red -> Orange -> Blue
      const h = (t * 3) % 3;
      if (h < 1) { r = 255; g = Math.round(140 * h); b = 0; }
      else if (h < 2) { r = Math.round(255 * (2 - h)); g = Math.round(140 * (2 - h)); b = Math.round(255 * (h - 1)); }
      else { r = Math.round(255 * (h - 2)); g = 0; b = Math.round(255 * (3 - h)); }
    } else if (palette === 'Pastel') {
      // Soft Pastel HSL
      const hue = t * 360;
      const rgb = hslToRgb(hue / 360, 0.7, 0.75);
      r = rgb[0]; g = rgb[1]; b = rgb[2];
    } else {
      // Rainbow HSL
      const hue = t * 360;
      const rgb = hslToRgb(hue / 360, 1.0, 0.5);
      r = rgb[0]; g = rgb[1]; b = rgb[2];
    }

    r = Math.round(r * brightness);
    g = Math.round(g * brightness);
    b = Math.round(b * brightness);

    return [r, g, b];
  },

  loop(timestamp) {
    this._scheduled = false;
    const s = settings || {};
    const enabled = s.enable_background_animation !== false;
    const ctx = this.ctx;
    const canvas = this.canvas;

    if (!ctx || !canvas) {
      this.requestRedraw();
      return;
    }

    // FPS throttle
    const targetFps = s.background_animation_fps || 60;
    const interval = 1000 / targetFps;
    if (this.lastTime) {
      const dt = timestamp - this.lastTime;
      if (dt < interval) {
        this._scheduled = true;
        this.animId = requestAnimationFrame((t) => this.loop(t));
        return;
      }
    }
    this.lastTime = timestamp;

    // Nothing on screen actually moves this frame (animation disabled, or
    // the "Nothing" animation style) — draw it once and stop rescheduling
    // instead of clearing + repainting a static gradient at 60fps forever.
    // Matches the same condition the "Animations" block below uses to
    // decide whether it draws anything. requestRedraw() wakes the loop back
    // up the moment anything relevant changes (resize, theme/settings, tab
    // regaining focus).
    const isStaticFrame = !enabled || (s.background_animation_style || 'Waves') === 'Nothing';

    const W = canvas.width;
    const H = canvas.height;

    let r = 16, g = 185, b = 129;
    if (s.enable_chroma) {
      const chromaRgb = this.getChromaColor(timestamp);
      r = chromaRgb[0]; g = chromaRgb[1]; b = chromaRgb[2];
      const hexStr = `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
      const root = document.documentElement;
      root.style.setProperty('--accent', hexStr);
      root.style.setProperty('--accent-dim', `rgba(${r}, ${g}, ${b}, 0.15)`);
      root.style.setProperty('--accent-glow', `rgba(${r}, ${g}, ${b}, 0.35)`);
    } else {
      const accent = currentAccentColor();
      const rgb = this.hexToRgb(accent);
      r = rgb[0]; g = rgb[1]; b = rgb[2];
    }

    const bgStyle = s.background_style || 'Default';
    const animStyle = s.background_animation_style || 'Waves';
    const speed = s.background_animation_speed || 1.0;
    // "Nothing" is a flat, single-color background — no gradient — dark
    // gray. It only applies when there's no custom image background (that
    // already has its own flat base and takes priority).
    const isNothing = bgStyle === 'Nothing' && !s.use_background_image;
    const imageBgBoost = s.use_background_image ? 3.2 : 1;
    const intensity = s.background_animation_intensity ?? 1.0;
    const aBoost = imageBgBoost * intensity;

    // ── Clear Canvas ──
    ctx.clearRect(0, 0, W, H);

    if (!s.use_background_image) {
      // The base gradient + glow(s) only actually change when size, accent
      // color, or background style change — not every frame. They were
      // being rebuilt from scratch (1 linear + up to 2 radial gradients,
      // each requiring color-stop math and a full-canvas fill) on every
      // single animation frame even though the result was pixel-identical
      // to the previous one nearly all the time. Now that static layer is
      // rendered once into an offscreen canvas and just blitted with
      // drawImage() — a plain pixel copy — until something it actually
      // depends on changes. Same pixels on screen, far less canvas work
      // per frame while an animation (Waves/Orbs/Fireflies/Particles) is
      // running.
      const baseColor = (s && s.bg_color) || '#0a0a0f';
      const staticKey = `${W}x${H}|${r},${g},${b}|${bgStyle}|${aBoost}|${isNothing}|${baseColor}`;
      if (this._staticKey !== staticKey) {
        this._staticKey = staticKey;
        if (!this._staticCanvas) this._staticCanvas = document.createElement('canvas');
        const sc = this._staticCanvas;
        if (sc.width !== W || sc.height !== H) { sc.width = W; sc.height = H; }
        const sctx = sc.getContext('2d');
        sctx.clearRect(0, 0, W, H);

        if (isNothing) {
          // Flat solid fill, no gradient at all.
          sctx.fillStyle = baseColor;
          sctx.fillRect(0, 0, W, H);
        } else {
          // ── Base gradient ──
          const darkerColor = darkenColor(baseColor, 0.4);
          const grad = sctx.createLinearGradient(0, 0, 0, H);
          grad.addColorStop(0, baseColor);
          grad.addColorStop(1, darkerColor);
          sctx.fillStyle = grad;
          sctx.fillRect(0, 0, W, H);

          // ── Background style glows ──
          let glows = [[0.18, -0.05, 0.9, 0.18]];
          if (bgStyle === 'Midnight') glows = [[0.82, 1.05, 0.8, 0.21]];
          else if (bgStyle === 'Sunset') glows = [[0.05, 0.15, 0.7, 0.22], [0.95, 0.85, 0.7, 0.16]];
          else if (bgStyle === 'Forest') glows = [[0.5, 1.0, 1.1, 0.19]];
          else if (bgStyle === 'Ocean') glows = [[0.5, -0.1, 1.3, 0.18]];
          else if (bgStyle === 'Monochrome') glows = [];
          else if (bgStyle === 'Nothing') glows = [];
          else if (bgStyle === 'Accent Glow') glows = [[0.5, 0.4, 1.4, 0.32]];

          glows.forEach(([xr, yr, rr, a]) => {
            const cx = W * xr, cy = H * yr;
            const rad = Math.max(W, H) * rr * 0.6;
            const rg = sctx.createRadialGradient(cx, cy, 0, cx, cy, rad);
            rg.addColorStop(0, `rgba(${r},${g},${b},${Math.min(1, a * aBoost)})`);
            rg.addColorStop(1, `rgba(${r},${g},${b},0)`);
            sctx.fillStyle = rg;
            sctx.fillRect(0, 0, W, H);
          });
        }
      }
      ctx.drawImage(this._staticCanvas, 0, 0);
    }

    // ── Animations ──
    if (enabled && animStyle !== 'Nothing') {
      this.phase += 0.015 * speed;

      if (animStyle === 'Waves') {
        ctx.save();
        for (let i = 0; i < 3; i++) {
          ctx.beginPath();
          const off = this.phase + i * 1.5;
          ctx.moveTo(0, H);
          for (let x = 0; x <= W; x += 20) {
            const y = H * (0.62 + i * 0.09) + Math.sin(x * 0.003 + off) * 45 + Math.cos(x * 0.001 + off) * 25;
            ctx.lineTo(x, y);
          }
          ctx.lineTo(W, H);
          ctx.closePath();
          ctx.fillStyle = `rgba(${r},${g},${b},${Math.min(1, (0.025 + i * 0.018) * aBoost)})`;
          ctx.fill();
        }
        ctx.restore();


      } else if (animStyle === 'Fireflies') {
        // Draw soft glow halos for a dreamy firefly effect
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        this.particles.forEach(p => {
          p.swayPhase += p.swaySpeed * speed;
          p.baseX += p.driftX * 0.15 * speed;
          p.x = p.baseX + Math.sin(p.swayPhase) * p.swayAmp;
          p.y += Math.cos(p.swayPhase * 0.5) * 0.25 * speed;
          // Organic pulsing
          const pulse = 0.5 + 0.5 * Math.sin(p.swayPhase * 1.3 + p.size);
          p.alpha += p.alphaDir * speed * 0.7;
          if (p.alpha > 0.9 || p.alpha < 0.03) p.alphaDir = -p.alphaDir;
          const a = Math.max(0, p.alpha * pulse);
          if (p.x < -30 || p.x > W + 30 || p.y < -30 || p.y > H + 30) {
            p.baseX = p.x = Math.random() * W;
            p.y = Math.random() * H;
            p.alpha = 0.05;
          }
          // Outer soft glow
          const glowRadius = p.size * 10;
          const grd = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, glowRadius);
          grd.addColorStop(0, `rgba(${r},${g},${b},${Math.min(1, a * 0.6 * aBoost)})`);
          grd.addColorStop(0.3, `rgba(${r},${g},${b},${Math.min(1, a * 0.2 * aBoost)})`);
          grd.addColorStop(1, `rgba(${r},${g},${b},0)`);
          ctx.fillStyle = grd;
          ctx.fillRect(p.x - glowRadius, p.y - glowRadius, glowRadius * 2, glowRadius * 2);
          // Bright core
          const coreRadius = p.size * 0.8;
          const core = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, coreRadius);
          core.addColorStop(0, `rgba(255,255,255,${Math.min(1, a * 0.9 * aBoost)})`);
          core.addColorStop(1, `rgba(${r},${g},${b},0)`);
          ctx.fillStyle = core;
          ctx.fillRect(p.x - coreRadius, p.y - coreRadius, coreRadius * 2, coreRadius * 2);
        });
        ctx.restore();


      } else {
        // Particles (float up) with glow and constellation lines
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        // Update positions first
        this.particles.forEach(p => {
          p.y -= p.vy * speed;
          p.swayPhase += p.swaySpeed * speed;
          p.baseX += p.driftX * 0.1 * speed;
          p.x = p.baseX + Math.sin(p.swayPhase) * p.swayAmp;
          p.alpha += p.alphaDir * speed;
          if (p.alpha > 0.7) p.alphaDir = -Math.abs(p.alphaDir);
          if (p.y < -10 || p.alpha < 0) {
            p.baseX = p.x = Math.random() * W;
            p.y = H + 10;
            p.alpha = 0.05;
            p.alphaDir = Math.abs(p.alphaDir);
          }
        });
        // Draw faint constellation lines between nearby particles
        const maxDist = 110;
        for (let i = 0; i < this.particles.length; i++) {
          const a = this.particles[i];
          if (a.alpha < 0.05) continue;
          for (let j = i + 1; j < this.particles.length; j++) {
            const b = this.particles[j];
            if (b.alpha < 0.05) continue;
            const dx = a.x - b.x, dy = a.y - b.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < maxDist) {
              const lineAlpha = (1 - dist / maxDist) * Math.min(a.alpha, b.alpha) * 0.35 * aBoost;
              ctx.beginPath();
              ctx.moveTo(a.x, a.y);
              ctx.lineTo(b.x, b.y);
              ctx.strokeStyle = `rgba(${r},${g},${b},${Math.min(1, lineAlpha)})`;
              ctx.lineWidth = 0.6;
              ctx.stroke();
            }
          }
        }
        // Draw glowing particles
        this.particles.forEach(p => {
          const a = Math.max(0, p.alpha);
          // Soft glow
          const glowR = p.size * 5;
          const grd = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, glowR);
          grd.addColorStop(0, `rgba(${r},${g},${b},${Math.min(1, a * 0.5 * aBoost)})`);
          grd.addColorStop(1, `rgba(${r},${g},${b},0)`);
          ctx.fillStyle = grd;
          ctx.fillRect(p.x - glowR, p.y - glowR, glowR * 2, glowR * 2);
          // Bright core dot
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size * 0.6, 0, Math.PI * 2);
          ctx.fillStyle = `rgba(255,255,255,${Math.min(1, a * 0.8 * aBoost)})`;
          ctx.fill();
        });
        ctx.restore();
      }
    }

    if (!isStaticFrame) {
      this._scheduled = true;
      this.animId = requestAnimationFrame((t) => this.loop(t));
    }
  },

  // ── Background Image Support ──
  applyBackgroundImage() {
    const s = settings || {};
    const imgLayer = document.getElementById('bg-image-layer');
    const dimLayer = document.getElementById('bg-overlay-dim');
    const vigLayer = document.getElementById('bg-overlay-vignette');

    if (imgLayer) {
      if (s.use_background_image && s.background_image_path) {
        let url = s.background_image_path;
        // Local filesystem paths need to go through Tauri's asset protocol —
        // a bare file:// URL is blocked by the webview in production builds.
        if (!/^(https?|data|blob):/.test(url)) {
          try {
            const convert = window.__TAURI__.core.convertFileSrc;
            if (convert) url = convert(url);
          } catch (e) {
            console.error('Failed to convert background image path', e);
          }
        }
        imgLayer.style.backgroundImage = `url("${url}")`;
        const fitMap = { Cover: 'cover', Contain: 'contain', Stretch: '100% 100%', Center: 'auto', Tile: 'auto' };
        imgLayer.style.backgroundSize = fitMap[s.background_image_fit] || 'cover';
        imgLayer.style.backgroundRepeat = s.background_image_fit === 'Tile' ? 'repeat' : 'no-repeat';
        imgLayer.style.backgroundPosition = s.background_image_fit === 'Center' ? 'center center' : 'center';
        const brightness = s.background_image_brightness ?? 100;
        const blur = s.background_image_blur ?? 0;
        const tint = s.background_image_tint ? 'sepia(0.5) hue-rotate(120deg) saturate(1.5)' : '';
        imgLayer.style.filter = `brightness(${brightness}%) blur(${blur}px) ${tint}`.trim();
        imgLayer.style.opacity = '1';
      } else {
        imgLayer.style.backgroundImage = 'none';
        imgLayer.style.opacity = '0';
      }
    }

    if (dimLayer) {
      dimLayer.style.opacity = (s.use_background_image) ? ((s.background_image_dim ?? 35) / 100).toString() : '0';
    }

    if (vigLayer) {
      vigLayer.style.opacity = (s.use_background_image && s.background_image_vignette) ? '1' : '0';
    }

    // Every settings/theme change routes through here, so this is the one
    // place that needs to nudge the canvas loop awake if it had gone idle
    // (static scene) — otherwise a color/style change made while animation
    // is "Nothing" wouldn't repaint until something else woke it up.
    this.requestRedraw();
  },
};

// ══════════════════════════════════════════════════════════════════
// BACKGROUND MUSIC
// ══════════════════════════════════════════════════════════════════
// Plays whatever's enabled in Zero Launcher/music/ one track after another
// while the launcher is in the foreground. What happens when the launcher
// loses focus (e.g. the user alt-tabs to another launcher) is controlled by
// settings.music_switch_behavior: "pause" fades out and pauses, "continue"
// keeps playing untouched, and "lower" fades down to music_lower_percent%
// of the normal volume instead of stopping.
const MUSIC = {
  audioEl: null,
  tracks: [],           // enabled MusicTrackInfo[] making up the current playlist
  currentIndex: -1,
  currentObjectUrl: null,  // Blob URL for the currently-loaded track, revoked on the next track/stop
  windowFocused: true,
  fadeTimer: null,
  consecutiveFailures: 0,

  init() {
    this.audioEl = document.getElementById('bg-music-player');
    if (!this.audioEl) return;
    this.audioEl.addEventListener('ended', () => this.playNext());

    window.addEventListener('blur', () => this.onWindowBlur());
    window.addEventListener('focus', () => this.onWindowFocus());

    // Some webviews refuse audio.play() until the page has seen a real user
    // gesture, even for a desktop app — if that happens, the very first
    // click/keypress anywhere in the launcher retries starting the music.
    const retryOnGesture = () => {
      if (settings && settings.music_enabled && this.audioEl && this.audioEl.paused && this.tracks.length > 0) {
        this.play();
      }
    };
    ['pointerdown', 'keydown'].forEach(evt => {
      document.addEventListener(evt, retryOnGesture, { passive: true });
    });

    this.refreshPlaylist().then(() => {
      if (settings && settings.music_enabled) this.play();
    });
  },

  // Re-reads the track list from disk/settings (enabled ones only) and
  // keeps the currently-playing track going if it's still in the list.
  async refreshPlaylist() {
    let files = [];
    try {
      files = await api.listMusicFiles();
    } catch (e) {
      console.error('Failed to list music files:', e);
    }
    const currentPath = this.tracks[this.currentIndex] ? this.tracks[this.currentIndex].path : null;
    this.tracks = files.filter(t => t.enabled);
    if (currentPath) {
      const idx = this.tracks.findIndex(t => t.path === currentPath);
      this.currentIndex = idx;
    }
  },

  targetVolume() {
    if (!settings) return 0.5;
    return Math.max(0, Math.min(100, settings.music_volume ?? 50)) / 100;
  },

  loweredVolume() {
    const pct = Math.max(0, Math.min(100, settings ? (settings.music_lower_percent ?? 30) : 30));
    return this.targetVolume() * (pct / 100);
  },

  fadeTo(volume, durationMs) {
    if (!this.audioEl) return;
    clearInterval(this.fadeTimer);
    const steps = 16;
    const stepMs = Math.max(16, durationMs / steps);
    const start = this.audioEl.volume;
    const delta = volume - start;
    let i = 0;
    this.fadeTimer = setInterval(() => {
      i++;
      const v = start + delta * (i / steps);
      this.audioEl.volume = Math.max(0, Math.min(1, v));
      if (i >= steps) {
        clearInterval(this.fadeTimer);
        this.audioEl.volume = Math.max(0, Math.min(1, volume));
      }
    }, stepMs);
  },

  // Maps file extension -> MIME type for the Blob we hand to <audio>.
  _mimeFor(fileName) {
    const ext = (fileName.split('.').pop() || '').toLowerCase();
    return {
      mp3: 'audio/mpeg',
      wav: 'audio/wav',
      ogg: 'audio/ogg',
      flac: 'audio/flac',
      m4a: 'audio/mp4',
      mp4: 'audio/mp4',
      aac: 'audio/aac',
    }[ext] || 'application/octet-stream';
  },

  async play() {
    if (!this.audioEl || !settings || !settings.music_enabled || this.tracks.length === 0) return;
    if (this.currentIndex < 0 || this.currentIndex >= this.tracks.length) this.currentIndex = 0;
    const track = this.tracks[this.currentIndex];
    if (!track) return;

    // NOTE: we deliberately do NOT use window.__TAURI__.core.convertFileSrc
    // here. On Linux, WebKitGTK's media backend frequently fails to stream
    // audio through Tauri's custom asset:// protocol — <audio>.play() rejects
    // with NotSupportedError for every track regardless of format/codec,
    // even though the same files play fine in VLC. Reading the file's raw
    // bytes ourselves and handing the <audio> element a Blob URL sidesteps
    // that protocol entirely and is reliable across platforms.
    let bytes;
    try {
      bytes = await api.readMusicFile(track.file_name);
    } catch (e) {
      console.warn(`Music: couldn't read "${track.file_name}" (${e}), skipping to the next track.`);
      this.consecutiveFailures++;
      if (this.consecutiveFailures >= this.tracks.length) {
        console.error('Music: none of the enabled tracks could be played — check that they\'re valid audio files.');
        showToast('None of your music files could be played — check they\'re valid audio files', 'error');
        this.consecutiveFailures = 0;
        return;
      }
      this.playNext();
      return;
    }

    if (this.currentObjectUrl) {
      URL.revokeObjectURL(this.currentObjectUrl);
      this.currentObjectUrl = null;
    }
    const blob = new Blob([new Uint8Array(bytes)], { type: this._mimeFor(track.file_name) });
    const src = URL.createObjectURL(blob);
    this.currentObjectUrl = src;

    this.audioEl.src = src;
    this.audioEl.dataset.playingPath = track.path;
    const behavior = settings.music_switch_behavior || 'pause';
    this.audioEl.volume = (!this.windowFocused && behavior === 'lower') ? this.loweredVolume() : this.targetVolume();
    this.audioEl.play().then(() => {
      this.consecutiveFailures = 0;
    }).catch(e => {
      if (e.name === 'NotAllowedError') {
        // Autoplay blocked by the webview until a real user gesture happens
        // — leave it paused here; the pointerdown/keydown listener below
        // will retry once that gesture occurs, no need to skip tracks.
        console.warn('Music: autoplay blocked, will retry on first click/keypress.');
        return;
      }
      console.warn(`Music: couldn't play "${track.file_name}" (${e.name}: ${e.message}), skipping to the next track.`);
      this.consecutiveFailures++;
      if (this.consecutiveFailures >= this.tracks.length) {
        console.error('Music: none of the enabled tracks could be played — check that they\'re valid audio files.');
        showToast('None of your music files could be played — check they\'re valid audio files', 'error');
        this.consecutiveFailures = 0;
        return;
      }
      this.playNext();
    });
  },

  playNext() {
    if (this.tracks.length === 0) return;
    this.currentIndex = (this.currentIndex + 1) % this.tracks.length;
    this.play();
  },

  pause() {
    if (this.audioEl) this.audioEl.pause();
  },

  // Master on/off toggle (Settings → Appearance → Background Music).
  setEnabled(enabled) {
    if (!settings) return;
    settings.music_enabled = enabled;
    if (enabled) {
      this.play();
    } else {
      this.fadeTo(0, 350);
      setTimeout(() => this.pause(), 380);
    }
  },

  // Live volume update while dragging the slider.
  applyVolume() {
    if (!this.audioEl || !settings) return;
    const behavior = settings.music_switch_behavior || 'pause';
    const v = (!this.windowFocused && behavior === 'lower') ? this.loweredVolume() : this.targetVolume();
    this.audioEl.volume = v;
  },

  onWindowBlur() {
    this.windowFocused = false;
    if (!settings || !settings.music_enabled || !this.audioEl || this.audioEl.paused) return;
    const behavior = settings.music_switch_behavior || 'pause';
    if (behavior === 'pause') {
      this.fadeTo(0, 500);
      setTimeout(() => { if (!this.windowFocused) this.pause(); }, 520);
    } else if (behavior === 'lower') {
      this.fadeTo(this.loweredVolume(), 500);
    }
    // "continue" — do nothing, keep playing at full volume.
  },

  onWindowFocus() {
    this.windowFocused = true;
    if (!settings || !settings.music_enabled) return;
    const behavior = settings.music_switch_behavior || 'pause';
    if (behavior === 'pause') {
      if (this.audioEl && this.audioEl.paused) {
        this.audioEl.volume = 0;
        this.audioEl.play().catch(() => { });
      }
      this.fadeTo(this.targetVolume(), 500);
    } else if (behavior === 'lower') {
      this.fadeTo(this.targetVolume(), 500);
    }
  },
};

// Renders the toggleable track list inside the "Manage Music Library"
// overlay, matching the Instances list's row style.
async function renderMusicLibraryList() {
  const listEl = document.getElementById('music-library-list');
  if (!listEl) return;
  listEl.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_MUSIC_EMPTY_SVG}</span><span>Loading…</span></div>`;

  let files = [];
  try {
    files = await api.listMusicFiles();
  } catch (e) {
    listEl.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_WARNING_SVG}</span><span>Failed to load music folder</span></div>`;
    return;
  }

  if (files.length === 0) {
    listEl.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_MUSIC_EMPTY_SVG}</span><span>No music files yet — drop some into Zero Launcher/music/</span></div>`;
    return;
  }

  listEl.innerHTML = '';
  files.forEach(track => {
    const row = document.createElement('div');
    row.className = 'instance-card music-track-row';
    row.innerHTML = `
      <div class="inst-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M9 18V5l12-2v13"></path>
          <circle cx="6" cy="18" r="3"></circle>
          <circle cx="18" cy="16" r="3"></circle>
        </svg>
      </div>
      <div class="inst-text">
        <div class="inst-name">${track.file_name}</div>
      </div>
      <label class="toggle-row">
        <input type="checkbox" class="music-track-toggle" ${track.enabled ? 'checked' : ''} />
        <span class="toggle-slider"></span>
      </label>
    `;
    row.querySelector('.music-track-toggle').addEventListener('change', async (ev) => {
      if (!settings) return;
      const disabled = new Set(settings.music_disabled_tracks || []);
      if (ev.target.checked) disabled.delete(track.file_name);
      else disabled.add(track.file_name);
      settings.music_disabled_tracks = Array.from(disabled);
      try {
        await api.updateSettings(settings);
        await MUSIC.refreshPlaylist();
        if (!MUSIC.audioEl || MUSIC.audioEl.paused) MUSIC.play();
      } catch (e) {
        showToast('Failed to update music library: ' + e, 'error');
      }
    });
    listEl.appendChild(row);
  });
}

function initMusicSettings() {
  const enabledEl = document.getElementById('setting-music-enabled');
  const volumeEl = document.getElementById('setting-music-volume');
  const behaviorEl = document.getElementById('setting-music-switch-behavior');
  const lowerRow = document.getElementById('setting-music-lower-row');
  const lowerEl = document.getElementById('setting-music-lower-percent');

  function syncLowerRowVisibility() {
    if (lowerRow) lowerRow.classList.toggle('hidden', !behaviorEl || behaviorEl.value !== 'lower');
  }

  if (enabledEl) {
    enabledEl.addEventListener('change', () => {
      MUSIC.setEnabled(enabledEl.checked);
      saveSettingsNow();
    });
  }
  if (volumeEl) {
    volumeEl.addEventListener('input', () => {
      if (settings) settings.music_volume = parseInt(volumeEl.value) || 0;
      MUSIC.applyVolume();
      saveSettingsDebounced();
    });
  }
  if (behaviorEl) {
    behaviorEl.addEventListener('change', () => {
      if (settings) settings.music_switch_behavior = behaviorEl.value;
      syncLowerRowVisibility();
      saveSettingsNow();
    });
  }
  if (lowerEl) {
    lowerEl.addEventListener('input', () => {
      if (settings) settings.music_lower_percent = parseInt(lowerEl.value) || 0;
      saveSettingsDebounced();
    });
  }
  syncLowerRowVisibility();

  const overlay = document.getElementById('music-library-overlay');
  const openBtn = document.getElementById('btn-open-music-library');
  const closeBtn = document.getElementById('btn-close-music-library');
  const closeBtn2 = document.getElementById('btn-close-music-library-2');
  const openFolderBtn = document.getElementById('btn-open-music-folder');
  const refreshBtn = document.getElementById('btn-refresh-music-library');

  if (openBtn && overlay) {
    openBtn.addEventListener('click', () => {
      overlay.classList.remove('hidden');
      renderMusicLibraryList();
    });
  }
  const closeMusicLibrary = () => overlay && overlay.classList.add('hidden');
  if (closeBtn) closeBtn.addEventListener('click', closeMusicLibrary);
  if (closeBtn2) closeBtn2.addEventListener('click', closeMusicLibrary);
  if (openFolderBtn) {
    openFolderBtn.addEventListener('click', async () => {
      try {
        await api.openMusicFolder();
      } catch (e) {
        showToast('Failed to open music folder: ' + e, 'error');
      }
    });
  }
  if (refreshBtn) {
    refreshBtn.addEventListener('click', async () => {
      refreshBtn.disabled = true;
      try {
        await renderMusicLibraryList();
        await MUSIC.refreshPlaylist();
        if (settings && settings.music_enabled && (!MUSIC.audioEl || MUSIC.audioEl.paused)) {
          MUSIC.play();
        }
        showToast('Music library refreshed', 'success');
      } catch (e) {
        showToast('Failed to refresh music library: ' + e, 'error');
      } finally {
        refreshBtn.disabled = false;
      }
    });
  }
}

function populateMusicSettingsUI() {
  if (!settings) return;
  const enabledEl = document.getElementById('setting-music-enabled');
  if (enabledEl) enabledEl.checked = !!settings.music_enabled;
  const volumeEl = document.getElementById('setting-music-volume');
  if (volumeEl) volumeEl.value = settings.music_volume ?? 50;
  const behaviorEl = document.getElementById('setting-music-switch-behavior');
  if (behaviorEl) behaviorEl.value = settings.music_switch_behavior || 'pause';
  const lowerEl = document.getElementById('setting-music-lower-percent');
  if (lowerEl) lowerEl.value = settings.music_lower_percent ?? 30;
  const lowerRow = document.getElementById('setting-music-lower-row');
  if (lowerRow) lowerRow.classList.toggle('hidden', (settings.music_switch_behavior || 'pause') !== 'lower');
}

// ══════════════════════════════════════════════════════════════════
// WINDOW BEHAVIOR SETTINGS
// ══════════════════════════════════════════════════════════════════

// The "On Launcher Close" select and "Always hide to tray" toggle only
// mean anything when the tray icon itself is enabled — hide them
// otherwise instead of leaving controls on screen that silently do
// nothing.
function updateWindowBehaviorRowVisibility() {
  const trayEnabled = document.getElementById('setting-system-tray').checked;
  const closeRow = document.getElementById('setting-on-launcher-close-row');
  const alwaysRow = document.getElementById('setting-always-hide-to-tray-row');
  if (closeRow) closeRow.classList.toggle('hidden', !trayEnabled);
  if (alwaysRow) alwaysRow.classList.toggle('hidden', !trayEnabled);
}

// "Make it smart" is a sub-option of "Close launcher when game starts" —
// it only means anything (and can only be turned on) while that parent
// toggle is on, so lock it whenever the parent is off.
function updateSmartCloseRowEnabled() {
  const parentEl = document.getElementById('setting-close-on-launch');
  const smartRow = document.getElementById('setting-smart-close-row');
  const smartEl = document.getElementById('setting-smart-close-on-launch');
  if (!parentEl || !smartRow || !smartEl) return;
  const enabled = parentEl.checked;
  smartRow.classList.toggle('disabled', !enabled);
  smartEl.disabled = !enabled;
}

function initWindowBehaviorSettings() {
  const trayEl = document.getElementById('setting-system-tray');
  if (trayEl) {
    trayEl.addEventListener('change', () => {
      updateWindowBehaviorRowVisibility();
      saveSettingsNow();
    });
  }
  const onGameCloseEl = document.getElementById('setting-on-game-close');
  if (onGameCloseEl) onGameCloseEl.addEventListener('change', saveSettingsNow);
  const onLauncherCloseEl = document.getElementById('setting-on-launcher-close');
  if (onLauncherCloseEl) onLauncherCloseEl.addEventListener('change', saveSettingsNow);
  const alwaysHideEl = document.getElementById('setting-always-hide-to-tray');
  if (alwaysHideEl) alwaysHideEl.addEventListener('change', saveSettingsNow);
  const closeOnLaunchEl = document.getElementById('setting-close-on-launch');
  if (closeOnLaunchEl) {
    closeOnLaunchEl.addEventListener('change', () => {
      updateSmartCloseRowEnabled();
      saveSettingsNow();
    });
  }
  const smartCloseEl = document.getElementById('setting-smart-close-on-launch');
  if (smartCloseEl) smartCloseEl.addEventListener('change', saveSettingsNow);
}

// ══════════════════════════════════════════════════════════════════
// FIRST-TIME SETUP WIZARD
// ══════════════════════════════════════════════════════════════════
let currentSetupStep = 1;

// Shows one of the setup wizard's Account-step sub-views: the
// Microsoft/Offline choice, the Microsoft device-code panel, or the
// offline username form. Mirrors showAccountView() but for the wizard's
// own element ids (it can't reuse the modal's ids directly since both
// can theoretically exist in the DOM at once).
function showSetupAccountView(view) {
  const map = {
    choice: 'setup-account-choice',
    msa: 'setup-account-msa-section',
    offline: 'setup-account-offline-section',
  };
  Object.values(map).forEach(id => {
    const el = document.getElementById(id);
    if (el) el.classList.add('hidden');
  });
  const target = document.getElementById(map[view]);
  if (target) target.classList.remove('hidden');
}

let setupDeviceFlow = null;

function initSetupWizard() {
  const prevBtn = document.getElementById('btn-setup-prev');
  const nextBtn = document.getElementById('btn-setup-next');
  const skipStepBtn = document.getElementById('btn-setup-skip-step');
  const skipAllBtn = document.getElementById('btn-setup-skip-all');

  // ── Account step: Microsoft sign-in + offline account ──────────────
  const setupChoiceMsaBtn = document.getElementById('setup-btn-choice-msa');
  const setupChoiceOfflineBtn = document.getElementById('setup-btn-choice-offline');
  const setupBackBtns = document.querySelectorAll('.setup-btn-back-to-choices');
  const setupDeviceLoginBtn = document.getElementById('setup-btn-msa-device-login');
  const setupDeviceOpenBtn = document.getElementById('setup-btn-msa-device-open');
  const setupDeviceCancelBtn = document.getElementById('setup-btn-msa-device-cancel');
  const setupOfflineCreateBtn = document.getElementById('setup-btn-offline-create');
  const setupUsernameInput = document.getElementById('setup-username');

  setupDeviceFlow = createDeviceSignInFlow({
    methodChoiceEl: document.getElementById('setup-msa-method-choice'),
    devicePanelEl: document.getElementById('setup-msa-device-panel'),
    deviceCodeEl: document.getElementById('setup-msa-device-code'),
    deviceStatusEl: document.getElementById('setup-msa-device-status'),
    onSuccess: async (account) => {
      await refreshAccountUI();
      showToast(`Signed in as ${account.username || 'Microsoft account'}!`, 'success');
      showSetupAccountView('choice');
      updateSetupAccountExistingMsg();
    },
  });

  if (setupChoiceMsaBtn) {
    setupChoiceMsaBtn.addEventListener('click', () => {
      showSetupAccountView('msa');
    });
  }

  if (setupChoiceOfflineBtn) {
    setupChoiceOfflineBtn.addEventListener('click', () => {
      showSetupAccountView('offline');
      if (setupUsernameInput) setupUsernameInput.focus();
    });
  }

  setupBackBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      setupDeviceFlow.cancel();
      showSetupAccountView('choice');
    });
  });

  if (setupDeviceLoginBtn) {
    setupDeviceLoginBtn.addEventListener('click', () => setupDeviceFlow.start());
  }

  if (setupDeviceOpenBtn) {
    setupDeviceOpenBtn.addEventListener('click', () => setupDeviceFlow.openVerificationLink());
  }

  if (setupDeviceCancelBtn) {
    setupDeviceCancelBtn.addEventListener('click', () => {
      setupDeviceFlow.cancel();
      showSetupAccountView('choice');
    });
  }

  async function createSetupOfflineAccount() {
    const username = setupUsernameInput ? setupUsernameInput.value.trim() : '';
    if (!username) {
      showToast('Please enter a username', 'warning');
      return;
    }
    if (username.length > 16) {
      showToast('Username must be 16 characters or less', 'warning');
      return;
    }
    try {
      await api.addOfflineAccount(username);
      if (setupUsernameInput) setupUsernameInput.value = '';
      await refreshAccountUI();
      showToast(`Account "${username}" created!`, 'success');
      showSetupAccountView('choice');
      updateSetupAccountExistingMsg();
    } catch (err) {
      showToast('Failed to create account: ' + err, 'error');
    }
  }

  if (setupOfflineCreateBtn) {
    setupOfflineCreateBtn.addEventListener('click', (e) => {
      e.preventDefault();
      createSetupOfflineAccount();
    });
  }

  if (setupUsernameInput) {
    setupUsernameInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        createSetupOfflineAccount();
      }
    });
  }

  if (prevBtn) {
    prevBtn.addEventListener('click', () => {
      if (currentSetupStep > 1) {
        showSetupStep(currentSetupStep - 1);
      }
    });
  }

  if (nextBtn) {
    nextBtn.addEventListener('click', async () => {
      try {
        const success = await handleSetupStepSubmit(currentSetupStep);
        if (success) {
          if (currentSetupStep < 3) {
            showSetupStep(currentSetupStep + 1);
          } else {
            await finishSetupWizard();
          }
        }
      } catch (err) {
        console.error('Setup error:', err);
        showToast('Setup error: ' + (err.message || err), 'error');
      }
    });
  }

  if (skipStepBtn) {
    skipStepBtn.addEventListener('click', async () => {
      try {
        if (currentSetupStep < 3) {
          showSetupStep(currentSetupStep + 1);
        } else {
          await finishSetupWizard();
        }
      } catch (err) {
        showToast('Setup error: ' + (err.message || err), 'error');
      }
    });
  }

  if (skipAllBtn) {
    skipAllBtn.addEventListener('click', async () => {
      try {
        await finishSetupWizard();
      } catch (err) {
        showToast('Setup error: ' + (err.message || err), 'error');
      }
    });
  }

  // Live listeners for Step 1 Color Pickers
  document.getElementById('setup-btn-accent-picker')?.addEventListener('click', () => {
    openCustomColorPicker({
      title: 'Accent Color',
      type: 'accent',
      initialColor: currentAccentColor(),
      onLiveChange: (hex) => {
        applyAccentColorLive(hex);
        applyThemeFromSettings();
      },
      onApply: (hex) => {
        applyAccentColorLive(hex);
        applyThemeFromSettings();
        saveSettingsDebounced();
      }
    });
  });

  // Theme Palettes "More Palettes" button in Setup Wizard
  document.getElementById('setup-btn-browse-palettes')?.addEventListener('click', () => {
    openThemePalettesModal();
  });

  const setupAccentHexInput = document.getElementById('setup-accent-hex-input');
  if (setupAccentHexInput) {
    const handleHex = () => {
      let val = setupAccentHexInput.value.trim();
      if (!val.startsWith('#')) val = '#' + val;
      if (/^#[0-9a-f]{6}$/i.test(val)) {
        applyAccentColorLive(val);
        applyThemeFromSettings();
        saveSettingsDebounced();
      }
    };
    setupAccentHexInput.addEventListener('input', handleHex);
    setupAccentHexInput.addEventListener('change', handleHex);
  }

  document.getElementById('setup-btn-reset-accent')?.addEventListener('click', () => {
    applyAccentColorLive(ACCENT_DEFAULT);
    applyThemeFromSettings();
    saveSettingsDebounced();
  });

  document.getElementById('setup-btn-bg-color-picker')?.addEventListener('click', () => {
    openCustomColorPicker({
      title: 'Background Base Color',
      type: 'bg',
      initialColor: (settings && settings.bg_color) || '#0A0A0F',
      onLiveChange: (hex) => {
        applyBgColorLive(hex);
        applyThemeFromSettings();
      },
      onApply: (hex) => {
        applyBgColorLive(hex);
        applyThemeFromSettings();
        saveSettingsDebounced();
      }
    });
  });

  const setupBgHexInput = document.getElementById('setup-bg-hex-input');
  if (setupBgHexInput) {
    const handleHex = () => {
      let val = setupBgHexInput.value.trim();
      if (!val.startsWith('#')) val = '#' + val;
      if (/^#[0-9a-f]{6}$/i.test(val)) {
        applyBgColorLive(val);
        applyThemeFromSettings();
        saveSettingsDebounced();
      }
    };
    setupBgHexInput.addEventListener('input', handleHex);
    setupBgHexInput.addEventListener('change', handleHex);
  }

  document.getElementById('setup-btn-reset-bg-color')?.addEventListener('click', () => {
    applyBgColorLive('#0A0A0F');
    applyThemeFromSettings();
    saveSettingsDebounced();
  });

  const liveThemeInputs = [
    'setup-notif-style',
    'setup-bg-style',
    'setup-bg-anim-style'
  ];

  liveThemeInputs.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      const handler = () => {
        if (!settings) settings = {};
        const newNotif = document.getElementById('setup-notif-style').value;
        const notifChanged = settings.notification_style !== newNotif;
        settings.notification_style = newNotif;
        settings.background_style = document.getElementById('setup-bg-style').value;
        settings.background_animation_style = document.getElementById('setup-bg-anim-style').value;

        applyThemeFromSettings();
        saveSettingsDebounced();

        if (notifChanged && id === 'setup-notif-style') {
          showToast(`Notification style changed to ${newNotif}!`, 'info');
        }
      };
      el.addEventListener('change', handler);
      if (el.tagName === 'INPUT') el.addEventListener('input', handler);
    }
  });

}

async function openSetupWizard(force = false) {
  const isFinished = settings && (settings.Finished_setup === true || settings.setup_finished === true || settings.finished_setup_upper === true);
  if (!force && isFinished) return;

  closeSettingsModal();

  // Hide main navigation bar during setup process
  const tabBar = document.getElementById('tab-bar');
  if (tabBar) tabBar.classList.add('hidden');

  // Switch tab page to setup
  document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.pill-tab').forEach(b => b.classList.remove('active'));
  const page = document.getElementById('tab-setup');
  if (page) page.classList.add('active');

  showSetupStep(1);

  // Populate current settings into step 1 inputs
  if (settings) {
    const notifSel = document.getElementById('setup-notif-style');
    if (notifSel) notifSel.value = settings.notification_style || 'Minimal Outline';
    const accentCol = document.getElementById('setup-accent');
    if (accentCol) accentCol.value = settings.accent_color || ACCENT_DEFAULT;
    const bgStyleSel = document.getElementById('setup-bg-style');
    if (bgStyleSel) bgStyleSel.value = settings.background_style || 'Default';
    const bgAnimSel = document.getElementById('setup-bg-anim-style');
    if (bgAnimSel) bgAnimSel.value = settings.background_animation_style || 'Waves';
  }

  // Reset step 2 to the choice screen and refresh its "already set up" message
  showSetupAccountView('choice');
  await updateSetupAccountExistingMsg();
}

async function updateSetupAccountExistingMsg() {
  try {
    const accounts = await api.getAccounts();
    const existingMsg = document.getElementById('setup-account-existing-msg');
    if (existingMsg) {
      if (accounts && accounts.length > 0) {
        existingMsg.classList.remove('hidden');
      } else {
        existingMsg.classList.add('hidden');
      }
    }
  } catch (e) {}
}

function showSetupStep(step) {
  currentSetupStep = step;
  const subtitleEl = document.getElementById('setup-step-subtitle');
  const dots = document.querySelectorAll('.setup-sdot');
  const pages = document.querySelectorAll('.setup-step-page');
  const fillEl = document.getElementById('setup-stepper-fill');
  const prevBtn = document.getElementById('btn-setup-prev');
  const nextBtn = document.getElementById('btn-setup-next');

  const subtitles = {
    1: 'Step 1 of 3 — Appearance',
    2: 'Step 2 of 3 — Account',
    3: 'Step 3 of 3 — Tour'
  };
  if (subtitleEl) subtitleEl.textContent = subtitles[step] || '';

  if (fillEl) {
    const pct = step === 1 ? 0 : step === 2 ? 50 : 100;
    fillEl.style.width = pct + '%';
  }

  dots.forEach(dot => {
    const dStep = parseInt(dot.dataset.step);
    dot.classList.toggle('active', dStep === step);
    dot.classList.toggle('completed', dStep < step);
  });

  pages.forEach((page, idx) => {
    page.classList.toggle('active', idx + 1 === step);
  });

  if (prevBtn) prevBtn.classList.toggle('hidden', step === 1);
  if (nextBtn) {
    if (step === 3) {
      nextBtn.innerHTML = `Finish Setup <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>`;
    } else {
      nextBtn.innerHTML = `Next Step <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>`;
    }
  }
}

async function handleSetupStepSubmit(step) {
  if (step === 1) {
    if (!settings) settings = {};
    settings.notification_style = document.getElementById('setup-notif-style').value;
    settings.accent_color = currentAccentColor();
    settings.bg_color = (settings && settings.bg_color) || '#0a0a0f';
    settings.background_style = document.getElementById('setup-bg-style').value;
    settings.background_animation_style = document.getElementById('setup-bg-anim-style').value;

    populateSettingsUI();
    applyThemeFromSettings();
    await saveSettingsNow();
    return true;
  }

  if (step === 2) {
    // Account creation is handled directly by the Microsoft sign-in flow
    // and the "Create Offline Account" button now, not by this submit
    // step — this just makes sure something was actually set up (or
    // already existed) before moving on.
    if (setupDeviceFlow) setupDeviceFlow.stopDevicePolling();

    let hasExistingAccount = false;
    try {
      const accounts = await api.getAccounts();
      if (accounts && accounts.length > 0) hasExistingAccount = true;
    } catch (e) {}

    if (!hasExistingAccount) {
      showToast('Sign in with Microsoft or create an offline account first', 'warning');
      return false;
    }
    return true;
  }

  // Step 3 is just the closing tour/finish screen now — nothing to submit,
  // instances are created afterward from the normal Instances tab.
  return true;
}

async function finishSetupWizard() {
  if (setupDeviceFlow) setupDeviceFlow.cancel();
  try {
    if (!settings) settings = {};
    settings.Finished_setup = true;
    settings.setup_finished = true;
    await api.updateSettings(settings);
  } catch (e) {
    console.error('Failed to save setup completion state:', e);
    showToast('Warning: Failed to save setup status: ' + e, 'warning');
  }

  // Restore navigation bar
  const tabBar = document.getElementById('tab-bar');
  if (tabBar) tabBar.classList.remove('hidden');

  // Switch to Instances tab
  document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.pill-tab').forEach(b => b.classList.remove('active'));

  const instTabBtn = document.querySelector('.pill-tab[data-tab="instances"]');
  const instPage = document.getElementById('tab-instances');
  if (instTabBtn) instTabBtn.classList.add('active');
  if (instPage) instPage.classList.add('active');

  showToast('Setup complete! Welcome to Zero Launcher.', 'success');
}

function initCustomTitlebar() {
  const minBtn = document.getElementById('titlebar-minimize');
  const maxBtn = document.getElementById('titlebar-maximize');
  const closeBtn = document.getElementById('titlebar-close');
  const titlebar = document.getElementById('custom-titlebar');
  const dragArea = titlebar?.querySelector('.titlebar-drag-area');

  const doMinimize = async () => {
    try { await invoke('window_minimize'); } catch (_) {
      try { await window.__TAURI__?.window?.getCurrentWindow?.()?.minimize(); } catch (_) {}
    }
  };

  const doToggleMaximize = async () => {
    try { await invoke('window_toggle_maximize'); } catch (_) {
      try { await window.__TAURI__?.window?.getCurrentWindow?.()?.toggleMaximize(); } catch (_) {}
    }
  };

  const doClose = async () => {
    try { await invoke('window_close'); } catch (_) {
      try { await window.__TAURI__?.window?.getCurrentWindow?.()?.close(); } catch (_) {}
    }
  };

  if (minBtn) minBtn.addEventListener('click', doMinimize);
  if (maxBtn) maxBtn.addEventListener('click', doToggleMaximize);
  if (closeBtn) closeBtn.addEventListener('click', doClose);

  // Drag via startDragging (works reliably in WebKitGTK where data-tauri-drag-region can be flaky)
  if (dragArea) {
    dragArea.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return; // only left button drags
      if (e.target.closest('.titlebar-controls')) return;
      e.preventDefault();
      try {
        const win = window.__TAURI__?.window?.getCurrentWindow?.();
        if (win?.startDragging) win.startDragging();
      } catch (_) {}
    });
  }

  // Double click drag area to toggle maximize
  if (dragArea) {
    dragArea.addEventListener('dblclick', (e) => {
      if (e.target.closest('.titlebar-controls')) return;
      doToggleMaximize();
    });
  }

  // Right-click on titlebar triggers custom rich window & launcher actions menu
  if (titlebar) {
    titlebar.addEventListener('contextmenu', async (e) => {
      if (e.target.closest('.titlebar-controls')) return;
      e.preventDefault();
      e.stopPropagation();

      let isMax = false;
      try {
        isMax = await invoke('window_is_maximized');
      } catch (_) {}

      const items = [
        // ── Window Management ──
        {
          label: isMax ? 'Restore' : 'Maximize',
          onClick: () => doToggleMaximize(),
          isPrimary: true
        },
        {
          label: 'Minimize',
          onClick: () => doMinimize()
        },
        {
          label: 'Always on Top',
          onClick: async () => {
            try {
              const onTop = await invoke('window_toggle_always_on_top');
              showToast(onTop ? 'Window pinned Always on Top' : 'Window unpinned', 'info');
            } catch (_) {}
          }
        },
        {
          label: 'Toggle Fullscreen',
          onClick: async () => {
            try { await invoke('window_toggle_fullscreen'); } catch (_) {}
          }
        },
        {
          label: 'Center Window',
          onClick: async () => {
            try { await invoke('window_center'); } catch (_) {}
          }
        },
        { type: 'divider' },

        // ── Quick Navigation ──
        {
          label: 'Instances',
          onClick: () => {
            const tab = document.querySelector('.pill-tab[data-tab="instances"]');
            if (tab) tab.click();
          }
        },
        {
          label: 'Mods',
          onClick: () => {
            const tab = document.querySelector('.pill-tab[data-tab="mods"]');
            if (tab) tab.click();
          }
        },
        {
          label: 'Discover',
          onClick: () => {
            const tab = document.querySelector('.pill-tab[data-tab="discover"]');
            if (tab) tab.click();
          }
        },
        {
          label: 'Settings',
          onClick: () => {
            const btn = document.getElementById('btn-open-settings-modal');
            if (btn) btn.click();
          }
        },
        {
          label: 'Java Manager',
          onClick: () => {
            const btn = document.getElementById('btn-open-settings-modal');
            if (btn) btn.click();
            setTimeout(() => {
              const javaTab = document.querySelector('.settings-tab-btn[data-panel="java-manager"]');
              if (javaTab) javaTab.click();
            }, 100);
          }
        },
        { type: 'divider' },

        // ── Utilities & Dev ──
        {
          label: 'Open Data Folder',
          onClick: () => {
            if (api.openLauncherFolder) api.openLauncherFolder().catch(() => {});
          }
        },
        {
          label: 'Reload UI',
          onClick: () => window.location.reload()
        },
        {
          label: 'Inspect (DevTools)',
          onClick: () => {
            if (api.openDevtools) api.openDevtools().catch(() => {});
          }
        },
        { type: 'divider' },

        // ── Close ──
        {
          label: 'Close',
          onClick: () => doClose(),
          isDanger: true
        }
      ];

      showCustomMenu(e.clientX, e.clientY, items);
    });
  }
}

// ══════════════════════════════════════════════════════════════════
// BOOT
// ══════════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', async () => {
  initCustomTitlebar();
  initClickSoundListener();
  initTabs();
  initAccountDropdown();
  initDownloadWidget();
  initHiddenWindowsWidget();
  initInstanceActions();
  initSkinViewerUI();
  initDressingRoomUI();
  initSkinMiniPreview();
  initCrashTroubleshootWindow();
  initInstanceTroubleshootWindow();
  initMods();
  initModpackImportOverlay();
  initDiscoverModpackDirOverlay();
  initDiscover();
  initSettings();
  initMusicSettings();
  initWindowBehaviorSettings();
  initRunningInstancesWidget();
  initApplyPresetOverlayEvents();
  initExportModsOverlayEvents();
  initImportModsOverlayEvents();
  initSetupWizard();
  initCustomContextMenu();

  // Load initial data
  try {
    settings = await api.getSettings();
  } catch (e) {
    console.error('Settings load failed', e);
    settings = { game_directory: '' };
  }

  BG.init();
  populateSettingsUI();
  populateMusicSettingsUI();
  BG.applyBackgroundImage();
  MUSIC.init();

  await refreshAccountUI();
  await refreshInstances();
  renderInstanceList();
  if (getInstances().length > 0) {
    // Prefer the favorited instance on startup, if one is set and still
    // exists — falls back to the first instance otherwise (same as before).
    const favId = getFavoriteInstance();
    const favInstance = favId ? getInstances().find(inst => inst.version_id === favId) : null;
    selectInstance(favInstance ? favInstance.version_id : getInstances()[0].version_id);
  }

  // Auto-open Setup Wizard on first launch if Finished_setup is not true
  const isFinished = settings && (settings.Finished_setup === true || settings.setup_finished === true || settings.finished_setup_upper === true);
  if (!isFinished) {
    openSetupWizard(false);
  }

  // Check the currently selected instance for mod updates in the background
  // — Update All stays disabled until this finishes so it can't run against
  // a stale check.
  checkSelectedInstanceForUpdates().catch(e => console.error('Startup update check failed', e));

  initCrashDialog();
  initLaunchVerifyStatus();
  initAutoUpdate();
  initUpdateConsentPrompt();
});

// ═══════════════════════════════════════════════════════════════════════
// AUTO-UPDATE CONSENT PROMPT
//
// Shown on startup only while "Auto Check For Launcher Updates" (off by
// default) hasn't been turned on and the user hasn't ticked "don't ask
// again". Allow turns the setting on; Deny leaves it off — either way,
// checking "don't ask me again" stops the prompt from coming back.
// ═══════════════════════════════════════════════════════════════════════
function initUpdateConsentPrompt() {
  if (!settings) return;
  if (settings.auto_check_launcher_updates === true) return;
  if (settings.update_prompt_dont_ask_again === true) return;
  // Don't stack this on top of the first-run Setup Wizard — it's jarring
  // to have an update-consent popup appear over onboarding. It'll show on
  // the next normal launch once setup is finished instead.
  const isSetupFinished = settings.Finished_setup === true || settings.setup_finished === true || settings.finished_setup_upper === true;
  if (!isSetupFinished) return;

  const overlay = document.getElementById('update-consent-overlay');
  if (!overlay) return;

  const dontAskEl = document.getElementById('update-consent-dont-ask');
  const denyBtn = document.getElementById('btn-update-consent-deny');
  const allowBtn = document.getElementById('btn-update-consent-allow');

  const respond = async (allow) => {
    const dontAskAgain = !!(dontAskEl && dontAskEl.checked);
    settings.auto_check_launcher_updates = allow;
    if (dontAskAgain) settings.update_prompt_dont_ask_again = true;
    const elToggle = document.getElementById('setting-auto-check-launcher-updates');
    if (elToggle) elToggle.checked = allow;
    overlay.classList.add('hidden');
    try {
      await api.updateSettings(settings);
    } catch (e) {
      console.error('Failed to save update preference', e);
    }
    if (allow) {
      // They just opted in — go ahead and run the check now instead of
      // waiting for the next launch.
      api.checkForUpdate().then((update) => {
        if (update && typeof window.__ZL_showUpdatePrompt === 'function') {
          window.__ZL_showUpdatePrompt(update);
        }
      }).catch(() => {});
    }
  };

  denyBtn.addEventListener('click', () => respond(false));
  allowBtn.addEventListener('click', () => respond(true));

  overlay.classList.remove('hidden');
}

// ═══════════════════════════════════════════════════════════════════════
// AUTO-UPDATE
//
// On startup, silently asks the backend to check the update manifest (see
// commands/updater.rs for the manifest format and where to point it at
// your repo). If a newer version is out, shows a "new version available"
// prompt; on "Yes" it downloads the OS-appropriate file (with a progress
// bar) and asks the backend to install it, which replaces the running
// exe/AppImage and relaunches the app.
// ═══════════════════════════════════════════════════════════════════════

function initAutoUpdate() {
  if (!api.checkForUpdate) return;

  const overlay = document.getElementById('update-available-overlay');
  if (!overlay) return;

  const promptView = document.getElementById('update-prompt-view');
  const progressView = document.getElementById('update-progress-view');
  const readyView = document.getElementById('update-ready-view');
  const progressBar = document.getElementById('update-progress-bar');
  const progressPct = document.getElementById('update-progress-pct');
  const progressLabel = document.getElementById('update-progress-label');
  const readyCountdown = document.getElementById('update-ready-countdown');
  const readyOkayBtn = document.getElementById('btn-update-ready-okay');
  const readyReopenBtn = document.getElementById('btn-update-ready-reopen');
  const closeBtn = document.getElementById('btn-close-update-overlay');
  const noBtn = document.getElementById('btn-update-no');
  const yesBtn = document.getElementById('btn-update-yes');
  const relaunchToggle = document.getElementById('setting-update-relaunch');
  const virusTotalLink = document.getElementById('link-virustotal');
  const openFolderBtn = document.getElementById('btn-open-update-folder');
  const copyLinkBtn = document.getElementById('btn-copy-update-link');

  // Off by default — installing without relaunching lets people finish
  // whatever they're doing (or scan the freshly-swapped file) before the
  // new version actually starts.
  if (relaunchToggle) relaunchToggle.checked = false;

  const close = () => {
    overlay.classList.add('hidden');
    clearReadyCountdown();
  };
  closeBtn.addEventListener('click', close);
  noBtn.addEventListener('click', close);

  if (virusTotalLink) {
    virusTotalLink.addEventListener('click', async (e) => {
      e.preventDefault();
      const url = virusTotalLink.dataset.url;
      if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
        await window.__TAURI__.shell.open(url);
      } else {
        window.open(url, '_blank');
      }
    });
  }

  if (openFolderBtn) {
    openFolderBtn.addEventListener('click', async () => {
      try {
        await api.openCurrentExeFolder();
      } catch (e) {
        showToast('Failed to open folder: ' + e, 'error');
      }
    });
  }

  if (copyLinkBtn) {
    copyLinkBtn.addEventListener('click', async () => {
      const url = pendingUpdate && pendingUpdate.url;
      if (!url) {
        showToast('No download link available yet', 'error');
        return;
      }
      try {
        await navigator.clipboard.writeText(url);
        showToast('Download link copied', 'success');
      } catch (e) {
        showToast('Failed to copy link: ' + e, 'error');
      }
    });
  }

  let pendingUpdate = null;
  let unlistenProgress = null;
  let downloadedUpdatePath = null;
  let readyCountdownTimer = null;

  const showUpdatePrompt = (update) => {
    pendingUpdate = update;
    document.getElementById('update-version-text').textContent = `v${update.version}`;
    document.getElementById('update-size-text').textContent =
      update.size_mb ? `${update.size_mb.toFixed(1)} MB` : 'size unknown';
    // "What's new" section — only shown when the manifest actually gave us
    // changelog bullets for this version; otherwise the prompt looks the
    // same as before (just version + size + actions).
    const changelogEl = document.getElementById('update-changelog');
    const changelogList = document.getElementById('update-changelog-list');
    const notes = Array.isArray(update.changelog) ? update.changelog.filter(Boolean) : [];
    if (notes.length) {
      changelogList.innerHTML = '';
      notes.forEach((note) => {
        const li = document.createElement('li');
        li.textContent = note;
        changelogList.appendChild(li);
      });
      changelogEl.classList.remove('hidden');
    } else {
      changelogEl.classList.add('hidden');
    }
    promptView.classList.remove('hidden');
    progressView.classList.add('hidden');
    readyView.classList.add('hidden');
    closeBtn.style.visibility = 'visible';
    overlay.classList.remove('hidden');
  };
  window.__ZL_showUpdatePrompt = showUpdatePrompt;

  const clearReadyCountdown = () => {
    if (readyCountdownTimer) { clearInterval(readyCountdownTimer); readyCountdownTimer = null; }
  };

  // Actually performs the swap (and optional restart) via the backend.
  // Called either automatically (relaunch toggle on, after the 5s window)
  // or manually from the ready view's buttons.
  const doInstall = async (relaunch) => {
    clearReadyCountdown();
    readyOkayBtn.disabled = true;
    readyReopenBtn.disabled = true;
    readyCountdown.classList.remove('hidden');
    readyCountdown.textContent = relaunch ? 'Reopening…' : 'Installing…';
    try {
      await api.installUpdate(downloadedUpdatePath, relaunch);
      // Only reached when relaunch was off and the platform didn't need to
      // exit to finish the swap (Linux/macOS).
      readyCountdown.textContent = 'Update installed — restart the app to use the new version.';
      readyOkayBtn.disabled = false;
      readyOkayBtn.textContent = 'Close';
      readyReopenBtn.classList.add('hidden');
    } catch (e) {
      console.error('Update install failed:', e);
      readyCountdown.textContent = `Update failed: ${e}`;
      readyOkayBtn.disabled = false;
      readyReopenBtn.disabled = false;
    }
  };

  // Shown right after the download finishes. If "relaunch automatically"
  // is on, this counts down 5 seconds and then installs + restarts on its
  // own (Reopen Now still lets them skip the wait). If it's off, the
  // window just sits there until they click Okay (install, stay on the
  // old code until they restart it themselves) or Reopen Now (install and
  // restart immediately).
  const showUpdateReady = (relaunch) => {
    progressView.classList.add('hidden');
    readyView.classList.remove('hidden');
    closeBtn.style.visibility = 'hidden';
    readyOkayBtn.disabled = false;
    readyReopenBtn.disabled = false;
    readyReopenBtn.classList.remove('hidden');
    readyOkayBtn.textContent = 'Okay';

    if (relaunch) {
      let secondsLeft = 5;
      readyCountdown.classList.remove('hidden');
      readyCountdown.textContent = `Reopening automatically in ${secondsLeft}s…`;
      readyOkayBtn.classList.add('hidden');
      clearReadyCountdown();
      readyCountdownTimer = setInterval(() => {
        secondsLeft -= 1;
        if (secondsLeft <= 0) {
          clearReadyCountdown();
          doInstall(true);
          return;
        }
        readyCountdown.textContent = `Reopening automatically in ${secondsLeft}s…`;
      }, 1000);
    } else {
      readyCountdown.classList.add('hidden');
      readyOkayBtn.classList.remove('hidden');
    }
  };

  readyOkayBtn.addEventListener('click', () => {
    if (readyOkayBtn.textContent === 'Close') {
      close();
      return;
    }
    doInstall(false);
  });
  readyReopenBtn.addEventListener('click', () => doInstall(true));

  yesBtn.addEventListener('click', async () => {
    if (!pendingUpdate) return;
    promptView.classList.add('hidden');
    progressView.classList.remove('hidden');
    progressBar.style.width = '0%';
    progressPct.textContent = '0%';
    progressLabel.textContent = 'Downloading update…';
    closeBtn.style.visibility = 'hidden';

    try {
      unlistenProgress = await listen('update-download-progress', (event) => {
        const { downloaded_bytes, total_bytes } = event.payload || {};
        if (total_bytes) {
          const pct = Math.min(100, Math.round((downloaded_bytes / total_bytes) * 100));
          progressBar.style.width = `${pct}%`;
          progressPct.textContent = `${pct}%`;
        } else {
          // No content-length header — show bytes downloaded instead of a %.
          progressPct.textContent = `${(downloaded_bytes / 1048576).toFixed(1)} MB`;
        }
      });

      downloadedUpdatePath = await api.downloadUpdate(pendingUpdate.url);

      if (unlistenProgress) { unlistenProgress(); unlistenProgress = null; }

      const relaunch = !!(relaunchToggle && relaunchToggle.checked);
      showUpdateReady(relaunch);
    } catch (e) {
      console.error('Update download failed:', e);
      progressLabel.textContent = `Update failed: ${e}`;
      closeBtn.style.visibility = 'visible';
      if (unlistenProgress) { unlistenProgress(); unlistenProgress = null; }
    }
  });

  // Only runs the silent background check if the user has actually
  // opted in (Settings → Auto Check For Launcher Updates). The manual
  // "Check for Updates" button below always works regardless.
  if (settings && settings.auto_check_launcher_updates === true) {
    api.checkForUpdate()
      .then((update) => {
        if (!update) return;
        showUpdatePrompt(update);
      })
      .catch((e) => {
        // Silent — a failed background version check shouldn't interrupt
        // startup (no internet, manifest URL not set up yet, etc.).
        console.warn('Update check failed:', e);
      });
  }

  // Manual "Check for Updates" button (Settings → About & Initial Setup),
  // with an inline status line for the result.
  const checkBtn = document.getElementById('btn-check-for-updates');
  const checkBtnIcon = document.getElementById('btn-check-for-updates-icon');
  const statusEl = document.getElementById('update-check-status');
  const statusIcon = document.getElementById('update-check-status-icon');
  const statusText = document.getElementById('update-check-status-text');

  const ICONS = {
    uptodate: '<path d="M20 6 9 17l-5-5"/>',
    available: '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/>',
    error: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16h.01"/>',
  };

  const setStatus = (kind, text) => {
    statusEl.classList.remove('hidden', 'is-checking', 'is-uptodate', 'is-available', 'is-error');
    statusEl.classList.add(`is-${kind}`);
    statusText.textContent = text;
    statusIcon.innerHTML = ICONS[kind] || '';
  };

  if (checkBtn) {
    checkBtn.addEventListener('click', async () => {
      checkBtn.disabled = true;
      checkBtnIcon.classList.add('is-spinning');
      statusEl.classList.remove('hidden', 'is-uptodate', 'is-available', 'is-error');
      statusEl.classList.add('is-checking');
      statusText.textContent = 'Checking for updates…';
      statusIcon.innerHTML = ICONS.available;

      try {
        const update = await api.checkForUpdate();
        if (update) {
          setStatus('available', `Update available — v${update.version}`);
          showUpdatePrompt(update);
        } else {
          const current = await api.getLauncherVersion().catch(() => null);
          setStatus('uptodate', current ? `You're up to date (v${current})` : "You're up to date");
        }
      } catch (e) {
        console.error('Manual update check failed:', e);
        setStatus('error', 'Could not check for updates. Check your connection and try again.');
      } finally {
        checkBtn.disabled = false;
        checkBtnIcon.classList.remove('is-spinning');
      }
    });
  }
}

// ═══════════════════════════════════════════════════════════════════════
// CRASH DIALOG
//
// Listens for the backend's "game-crashed" event (emitted from
// crash_analysis.rs whenever an instance's process exits in a way that
// looks like a real crash rather than a normal quit) and shows a
// system-error-style dialog with a diagnosis and one-click fixes: disabling
// or removing a culprit/duplicate mod, deleting a stale cache folder
// (e.g. Fabric's `.fabric` remap cache), opening a search for a missing
// dependency or mod update, or bumping allocated memory.
// ═══════════════════════════════════════════════════════════════════════

function initLaunchVerifyStatus() {
  if (!api.onLaunchVerifyStatus) return;
  const textEl = document.getElementById('play-status-text');
  if (!textEl) return;

  api.onLaunchVerifyStatus((event) => {
    const p = event.payload;
    launchVerifyInProgress = !!p.active;
    // Only show the line while it's for whichever instance is currently
    // selected — a verify pass for a different (background) launch
    // shouldn't repaint text next to a Play button for something else.
    if (p.active && p.version_id === selectedInstanceId) {
      textEl.textContent = p.message || 'Checking libraries & assets…';
      textEl.classList.remove('hidden');
    } else if (!p.active && p.version_id === selectedInstanceId) {
      textEl.classList.add('hidden');
    }
  });
}

function initCrashDialog() {
  if (!api.onGameCrashed) return;
  const overlay = document.getElementById('crash-overlay');
  if (!overlay) return;

  const close = () => overlay.classList.add('hidden');
  document.getElementById('btn-crash-close').addEventListener('click', close);
  document.getElementById('btn-crash-dismiss').addEventListener('click', close);

  document.getElementById('btn-crash-view-log').addEventListener('click', async () => {
    const versionId = overlay.dataset.versionId;
    const name = overlay.dataset.instanceName;
    if (versionId && api.openInstanceConsoleWindow) {
      await api.openInstanceConsoleWindow(versionId, name);
    }
  });

  // Always shown on a real crash — no "likely cause" analysis, no settings
  // gate. The backend still runs its heuristics (see crash_analysis.rs) to
  // decide *whether* this exit looked like a crash at all, but the popup
  // itself intentionally says nothing more than "Your Game Crashed".
  api.onGameCrashed((event) => {
    showCrashDialog(event.payload);
  });
}

function showCrashDialog(report) {
  if (!report) return;
  const overlay = document.getElementById('crash-overlay');
  if (!overlay) return;

  overlay.dataset.versionId = report.version_id || '';
  overlay.dataset.instanceName = report.instance_name || '';

  overlay.classList.remove('hidden');
}

// Above this many affected mods, listing every single one starts to feel
// like spam rather than something useful to review — so we collapse straight
// to one "fix everything" button instead of a per-item list.
const CRASH_GROUP_LIST_THRESHOLD = 6;

function crashGroupCardCopy(category, count) {
  if (category === 'incompatible_mods') {
    return {
      title: 'Disable incompatible mods',
      detail: `These ${count} mods appear incompatible with this Minecraft/loader version:`,
      detailCollapsed: `${count} installed mods appear incompatible with this Minecraft/loader version.`,
      bulkLabel: `Disable all ${count}`,
    };
  }
  if (category === 'minecraft_version_mismatch') {
    return {
      title: 'Or: disable the incompatible mods instead',
      detail: `If you'd rather not change your Minecraft version, these ${count} mods can be disabled instead:`,
      detailCollapsed: `If you'd rather not change your Minecraft version, ${count} mods can be disabled instead.`,
      bulkLabel: `Disable all ${count}`,
    };
  }
  if (category === 'duplicate_mod') {
    return {
      title: 'Remove duplicate mods',
      detail: `These ${count} mod files look like duplicates of each other:`,
      detailCollapsed: `${count} mod files look like duplicates of each other.`,
      bulkLabel: `Remove all ${count}`,
    };
  }
  if (category === 'missing_dependency') {
    return {
      title: 'Install missing mods',
      detail: `This modpack needs these ${count} mods installed as well:`,
      detailCollapsed: `This modpack needs ${count} more mods installed.`,
      bulkLabel: `Install all ${count}`,
    };
  }
  if (category === 'mod_version_mismatch') {
    return {
      title: 'Disable mods built for a different version',
      detail: `These ${count} mods reference game internals that don't exist in this Minecraft version:`,
      detailCollapsed: `${count} mods reference game internals that don't exist in this Minecraft version.`,
      bulkLabel: `Disable all ${count}`,
    };
  }

  return {
    title: 'Disable these mods',
    detail: `${count} mods are involved:`,
    detailCollapsed: `${count} mods are involved.`,
    bulkLabel: `Disable all ${count}`,
  };
}

function buildGroupedModFixCard(category, groupFixes) {
  const copy = crashGroupCardCopy(category, groupFixes.length);
  const showList = groupFixes.length <= CRASH_GROUP_LIST_THRESHOLD;

  const row = document.createElement('div');
  row.className = 'crash-fix crash-fix-group';

  const info = document.createElement('div');
  info.className = 'crash-fix-info';

  const label = document.createElement('div');
  label.className = 'crash-fix-label';
  label.textContent = copy.title;

  const detail = document.createElement('div');
  detail.className = 'crash-fix-detail';
  detail.textContent = showList ? copy.detail : copy.detailCollapsed;

  info.appendChild(label);
  info.appendChild(detail);

  // Few mods: show each one with its own button, same as before.
  // Many mods: skip the list entirely, just offer the bulk button below —
  // scrolling through 40+ near-identical rows isn't actually useful.
  let list = null;
  if (showList) {
    list = document.createElement('div');
    list.className = 'crash-fix-mod-list';

    groupFixes.forEach(fix => {
      const item = document.createElement('div');
      item.className = 'crash-fix-mod-row';

      const name = document.createElement('span');
      name.className = 'crash-fix-mod-name';
      name.textContent = fix.mod_name || fix.label || 'Unknown mod';

      const btn = document.createElement('button');
      btn.className = 'btn-secondary btn-xs';
      btn.textContent = crashFixButtonText(fix.kind);
      btn.addEventListener('click', async () => {
        try {
          await applyCrashFix(fix);
          item.classList.add('crash-fix-mod-done');
          btn.textContent = 'Done ✓';
        } catch (e) {
          showToast('Fix failed: ' + e, 'error');
        }
      });

      item.appendChild(name);
      item.appendChild(btn);
      list.appendChild(item);
    });

    info.appendChild(list);
  }

  const actionCol = document.createElement('div');
  actionCol.className = 'crash-fix-action';
  const bulkBtn = document.createElement('button');
  bulkBtn.className = 'btn-secondary btn-sm';
  bulkBtn.textContent = copy.bulkLabel;
  bulkBtn.addEventListener('click', async () => {
    bulkBtn.disabled = true;
    bulkBtn.textContent = 'Working…';
    let failed = 0;
    if (list) {
      // Per-item rows are already wired up to applyCrashFix — reuse them
      // so the UI (checkmarks, per-row error toasts) stays in sync.
      for (const modRow of list.querySelectorAll('.crash-fix-mod-row:not(.crash-fix-mod-done) button')) {
        modRow.click();
        await new Promise(r => setTimeout(r, 60));
      }
    } else {
      // No per-item rows were rendered (too many mods) — apply every fix
      // directly instead.
      for (const fix of groupFixes) {
        try {
          await applyCrashFix(fix);
        } catch (e) {
          failed++;
        }
      }
    }
    bulkBtn.textContent = failed ? `Done, ${failed} failed` : 'Done ✓';
    row.classList.add('crash-fix-done');
  });
  actionCol.appendChild(bulkBtn);

  row.appendChild(info);
  row.appendChild(actionCol);
  return row;
}

function buildSingleFixCard(fix) {
  const row = document.createElement('div');
  row.className = 'crash-fix';

  const info = document.createElement('div');
  info.className = 'crash-fix-info';
  const label = document.createElement('div');
  label.className = 'crash-fix-label';
  label.textContent = fix.label || '';
  const detail = document.createElement('div');
  detail.className = 'crash-fix-detail';
  detail.textContent = fix.detail || '';
  info.appendChild(label);
  info.appendChild(detail);

  const actionCol = document.createElement('div');
  actionCol.className = 'crash-fix-action';

  if (fix.kind !== 'info') {
    const btn = document.createElement('button');
    btn.className = 'btn-secondary btn-sm';
    btn.textContent = crashFixButtonText(fix.kind);
    btn.addEventListener('click', async () => {
      try {
        await applyCrashFix(fix);
        row.classList.add('crash-fix-done');
        btn.textContent = 'Done ✓';
      } catch (e) {
        showToast('Fix failed: ' + e, 'error');
      }
    });
    actionCol.appendChild(btn);
  }

  row.appendChild(info);
  row.appendChild(actionCol);
  return row;
}

function crashFixButtonText(kind) {
  switch (kind) {
    case 'disable_mod': return 'Disable';
    case 'delete_mod': return 'Remove';
    case 'install_mod': return 'Install';
    case 'update_mod': return 'Update';
    case 'delete_folder': return 'Delete folder';
    case 'open_url': return 'Open';
    case 'increase_memory': return 'Open Settings';
    default: return 'Apply';
  }
}

async function applyCrashFix(fix) {
  switch (fix.kind) {
    case 'disable_mod': {
      if (!fix.mod_path) return;
      await api.toggleMod(null, fix.mod_path, false);
      showToast(`Disabled ${fix.mod_name || 'mod'}`, 'success');
      return;
    }
    case 'delete_mod': {
      if (!fix.mod_path) return;
      await api.deleteMod(null, fix.mod_path);
      showToast(`Removed ${fix.mod_name || 'mod file'}`, 'success');
      return;
    }
    case 'install_mod': {
      if (!fix.mod_name) return;
      const overlay = document.getElementById('crash-overlay');
      const versionId = overlay && overlay.dataset.versionId;
      const inst = versionId ? getInstances().find(i => i.version_id === versionId) : null;
      const directory = inst ? inst.directory : null;
      const loaderFilter = inst && inst.loader ? inst.loader.toLowerCase() : null;
      const gameVersion = inst && inst.minecraft_version ? inst.minecraft_version : null;

      if (!directory) {
        // Can't resolve where to put the file — fall back to just opening
        // the Modrinth search so the user can grab it manually.
        if (fix.url) {
          if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
            await window.__TAURI__.shell.open(fix.url);
          } else {
            window.open(fix.url, '_blank');
          }
        }
        return;
      }

      try {
        const res = await api.discoverSearch(fix.mod_name, 'mod', loaderFilter, gameVersion, null, null, null, false, 1, 1);
        const hit = res && res.hits && res.hits[0];
        if (!hit) throw new Error('not found on Modrinth');

        const versions = await api.discoverGetVersions(hit.project_id, loaderFilter, gameVersion);
        const latest = versions && versions[0];
        if (!latest) throw new Error('no matching version for this Minecraft/loader');

        const primary = (latest.files || []).find(f => f.primary) || (latest.files || [])[0];
        if (!primary) throw new Error('no downloadable file on that version');

        await api.discoverDownload(directory, 'mod', primary.url, primary.filename);
        showToast(`Installed ${hit.title || fix.mod_name}`, 'success');
      } catch (e) {
        // Auto-install couldn't pin an exact match (wrong version, not on
        // Modrinth, etc.) — open the search page instead so it's never a
        // dead end for the user.
        showToast(`Couldn't auto-install ${fix.mod_name} (${e.message || e}) — opening Modrinth search instead`, 'error');
        if (fix.url) {
          if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
            await window.__TAURI__.shell.open(fix.url);
          } else {
            window.open(fix.url, '_blank');
          }
        }
      }
      return;
    }
    case 'update_mod': {
      if (!fix.mod_name) return;
      const overlay = document.getElementById('crash-overlay');
      const versionId = overlay && overlay.dataset.versionId;
      const inst = versionId ? getInstances().find(i => i.version_id === versionId) : null;
      const directory = inst ? inst.directory : null;
      const loaderFilter = inst && inst.loader ? inst.loader.toLowerCase() : null;
      const gameVersion = inst && inst.minecraft_version ? inst.minecraft_version : null;

      const openFallbackUrl = async () => {
        if (!fix.url) return;
        if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
          await window.__TAURI__.shell.open(fix.url);
        } else {
          window.open(fix.url, '_blank');
        }
      };

      if (!directory) {
        await openFallbackUrl();
        return;
      }

      try {
        const res = await api.discoverSearch(fix.mod_name, 'mod', loaderFilter, gameVersion, null, null, null, false, 1, 1);
        const hit = res && res.hits && res.hits[0];
        if (!hit) throw new Error('not found on Modrinth');

        const versions = await api.discoverGetVersions(hit.project_id, loaderFilter, gameVersion);
        const latest = versions && versions[0];
        if (!latest) throw new Error('no matching version for this Minecraft/loader');

        const primary = (latest.files || []).find(f => f.primary) || (latest.files || [])[0];
        if (!primary) throw new Error('no downloadable file on that version');

        await api.discoverDownload(directory, 'mod', primary.url, primary.filename);

        // Remove the old jar so we don't end up with two versions of the
        // same mod both enabled at once.
        if (fix.mod_path && fix.mod_path !== primary.filename) {
          try {
            await api.deleteMod(directory, fix.mod_path);
          } catch (e) {
            console.error('Failed to remove old version after update', fix.mod_path, e);
          }
        }

        showToast(`Updated ${hit.title || fix.mod_name}`, 'success');
      } catch (e) {
        showToast(`Couldn't auto-update ${fix.mod_name} (${e.message || e}) — opening Modrinth search instead`, 'error');
        await openFallbackUrl();
      }
      return;
    }
    case 'delete_folder': {
      const overlay = document.getElementById('crash-overlay');
      const gameDir = getInstanceGameDir(overlay && overlay.dataset.versionId);
      if (!fix.folder || !gameDir) return;
      await api.deleteInstanceSubpath(gameDir, fix.folder);
      showToast(`Deleted ${fix.folder} — it will be regenerated next launch`, 'success');
      return;
    }
    case 'open_url': {
      if (!fix.url) return;
      if (window.__TAURI__ && window.__TAURI__.shell && window.__TAURI__.shell.open) {
        await window.__TAURI__.shell.open(fix.url);
      } else {
        window.open(fix.url, '_blank');
      }
      return;
    }
    case 'increase_memory': {
      document.getElementById('crash-overlay').classList.add('hidden');
      const settingsTabBtn = document.querySelector('[data-tab="settings"]');
      if (settingsTabBtn) settingsTabBtn.click();
      return;
    }
    default:
      return;
  }
}

// Looks up the instance's on-disk directory from the cached instance list,
// used by the "delete stale cache folder" fix which needs an absolute path
// to scope its deletion to.
function getInstanceGameDir(versionId) {
  if (!versionId) return null;
  const inst = getInstances().find((i) => i.version_id === versionId);
  return inst ? inst.directory : null;
}

// ══════════════════════════════════════════════════════════════════
// CUSTOM CONTEXT MENU (Lightweight & WebKitGTK Optimized)
// ══════════════════════════════════════════════════════════════════
// showCustomMenu()/closeCustomMenu() are the shared rendering/positioning
// engine — used both by the global right-click handler below and by any
// plain button that wants a small dropdown (e.g. the Discover modpack
// card's 3-dot menu) instead of duplicating this per call site.
function closeCustomMenu() {
  const menuEl = document.getElementById('custom-context-menu');
  const itemsContainer = document.getElementById('ctx-menu-items');
  if (menuEl && !menuEl.classList.contains('hidden')) {
    menuEl.classList.add('hidden');
    if (itemsContainer) itemsContainer.innerHTML = '';
  }
}

// items: [{ type: 'item', label, onClick, isPrimary, isDanger } | { type: 'divider' }]
function showCustomMenu(x, y, items) {
  const menuEl = document.getElementById('custom-context-menu');
  const itemsContainer = document.getElementById('ctx-menu-items');
  if (!menuEl || !itemsContainer) return;

  itemsContainer.innerHTML = '';
  items.forEach((item) => {
    if (item.type === 'divider') {
      const div = document.createElement('div');
      div.className = 'ctx-divider';
      itemsContainer.appendChild(div);
    } else {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'ctx-item' + (item.isPrimary ? ' ctx-primary' : '') + (item.isDanger ? ' ctx-danger' : '');
      btn.textContent = item.label;
      btn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        closeCustomMenu();
        item.onClick();
      });
      itemsContainer.appendChild(btn);
    }
  });

  menuEl.classList.remove('hidden');
  const menuWidth = Math.max(210, menuEl.offsetWidth || 230);
  const menuHeight = items.length * 32 + 16;

  let posX = x;
  let posY = y;

  if (posX + menuWidth > window.innerWidth) {
    posX = Math.max(8, window.innerWidth - menuWidth - 8);
  }
  if (posY + menuHeight > window.innerHeight) {
    posY = Math.max(8, window.innerHeight - menuHeight - 8);
  }

  menuEl.style.left = `${posX}px`;
  menuEl.style.top = `${posY}px`;
}

function initCustomContextMenu() {
  const menuEl = document.getElementById('custom-context-menu');
  const itemsContainer = document.getElementById('ctx-menu-items');
  if (!menuEl || !itemsContainer) return;

  // Prevent default browser context menu everywhere and render custom items
  document.addEventListener('contextmenu', (e) => {
    // Let the titlebar handle its own context menu independently
    if (e.target.closest('#custom-titlebar')) return;
    e.preventDefault();

    const mouseX = e.clientX;
    const mouseY = e.clientY;

    const instanceCard = e.target.closest('.instance-card');
    const modCard = e.target.closest('.mod-card');
    const discoverCard = e.target.closest('.discover-card');

    const items = [];

    const addItem = (label, onClick, { isPrimary = false, isDanger = false } = {}) => {
      items.push({ type: 'item', label, onClick, isPrimary, isDanger });
    };
    const addDivider = () => {
      if (items.length > 0 && items[items.length - 1].type !== 'divider') {
        items.push({ type: 'divider' });
      }
    };

    // The custom menu below replaces the browser's native one everywhere
    // (see the preventDefault above), which also silently swallowed the
    // native "Copy" entry — so selecting log text (in the Logs tab or the
    // instance console) had no way to actually get copied. Add it back
    // explicitly whenever there's an active text selection.
    const selectedText = window.getSelection()?.toString() || '';
    if (selectedText.trim()) {
      addItem('Copy', () => {
        navigator.clipboard.writeText(selectedText).catch(() => {});
      }, { isPrimary: true });
      addDivider();
    }

    if (instanceCard) {
      const versionId = instanceCard.dataset.versionId;
      if (versionId) {
        addItem('Play', async () => {
          selectInstance(versionId);
          if (typeof window.launchSelectedInstance === 'function') {
            await window.launchSelectedInstance(false);
          } else {
            document.getElementById('btn-play')?.click();
          }
        }, { isPrimary: true });

        addItem('Play Offline', async () => {
          selectInstance(versionId);
          if (typeof window.launchSelectedInstance === 'function') {
            await window.launchSelectedInstance(true);
          }
        });

        addItem('Open Content', () => {
          selectInstance(versionId);
          const modsTab = document.querySelector('.pill-tab[data-tab="mods"]');
          if (modsTab) modsTab.click();
        });
      }
    } else if (modCard) {
      const modPath = modCard.dataset.path;
      const mod = modCard._mod;
      const hasUpdate = modPath && modUpdateInfo && modUpdateInfo.has(modPath);

      if (hasUpdate) {
        addItem('Update Mod', () => {
          const updateBtn = modCard.querySelector('.btn-update-mod');
          if (updateBtn) updateBtn.click();
        }, { isPrimary: true });
      }

      const isEnabled = mod ? mod.enabled : !modCard.classList.contains('disabled');
      addItem(isEnabled ? 'Disable' : 'Enable', () => {
        const toggleInput = modCard.querySelector('.mod-toggle-input');
        if (toggleInput) {
          toggleInput.checked = !toggleInput.checked;
          toggleInput.dispatchEvent(new Event('change'));
        }
      });

      addItem('Delete', async () => {
        if (!confirm('Delete this mod?')) return;
        const targetInstance = getModsTargetInstance();
        const dir = (targetInstance ? (targetInstance.directory || settings.game_directory) : (settings ? settings.game_directory : ''));
        
        // Remove from DOM immediately
        modCard.remove();
        updateModsCount();
        updateDeleteSelectedState();
        showToast('Mod deleted', 'success');

        try {
          await api.deleteMod(dir, modPath);
        } catch (e) {
          showToast('Failed to delete mod: ' + e, 'error');
          loadMods();
        }
      }, { isDanger: true });
    } else if (discoverCard) {
      const hit = discoverCard._hit;
      addItem('Install', () => {
        const downloadBtn = discoverCard.querySelector('.discover-download-btn');
        if (downloadBtn) {
          downloadBtn.click();
        } else if (hit) {
          const versionSelect = discoverCard.querySelector('.discover-version-select');
          downloadDiscoverSelection(hit, versionSelect, null);
        }
      }, { isPrimary: true });
    }

    // Always include global Reload and Inspect at the bottom
    addDivider();

    addItem('Reload', () => {
      window.location.reload();
    });

    addItem('Inspect', () => {
      if (api.openDevtools) {
        api.openDevtools().catch(() => {});
      }
    });

    // Render + position via the shared helper.
    showCustomMenu(mouseX, mouseY, items);
  });

  // Global dismiss handlers on any outside interaction
  window.addEventListener('pointerdown', (e) => {
    if (!menuEl.classList.contains('hidden') && !menuEl.contains(e.target)) {
      closeCustomMenu();
    }
  }, true);

  window.addEventListener('mousedown', (e) => {
    if (!menuEl.classList.contains('hidden') && !menuEl.contains(e.target)) {
      closeCustomMenu();
    }
  }, true);

  window.addEventListener('wheel', (e) => {
    if (!menuEl.classList.contains('hidden') && !menuEl.contains(e.target)) {
      closeCustomMenu();
    }
  }, { capture: true, passive: true });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !menuEl.classList.contains('hidden')) {
      closeCustomMenu();
    }
  });

  window.addEventListener('resize', closeCustomMenu);
  window.addEventListener('blur', closeCustomMenu);
}