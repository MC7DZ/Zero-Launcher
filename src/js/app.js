/* ═══════════════════════════════════════════════════════════════════
   ZeroLauncher — App Logic (single-file, no page modules)
   ═══════════════════════════════════════════════════════════════════ */

// ── Tauri API ──
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ── Loader icons (bundled by Vite) ──
import iconVanilla from '../assets/loaders/vanilla.png';
import iconFabric from '../assets/loaders/fabric.png';
import iconForge from '../assets/loaders/forge.png';
import iconNeoforge from '../assets/loaders/neoforge.png';
import iconQuilt from '../assets/loaders/quilt.png';

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
// into view. Content and listeners stay intact throughout.
function enableCardCulling(container, cardSelector, options = {}) {
  if (!container || container.dataset.cullingEnabled === '1') return;
  container.dataset.cullingEnabled = '1';

  // The real bug: IntersectionObserver defaults to the browser viewport as
  // its root, but every scrollable list here lives inside an absolutely
  // positioned `.tab-page` (or, for the instance list, itself) with its own
  // `overflow-y: auto`. Comparing against the viewport meant cards scrolled
  // out of that inner container still counted as "visible" (the container
  // itself was on-screen), so nothing ever got culled. Root must be the
  // actual scrolling ancestor.
  const scrollRoot = options.root || container.closest('.tab-page') || container;
  const rootMargin = options.rootMargin || '250px 0px';

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      entry.target.classList.toggle('is-culled', !entry.isIntersecting);
    });
  }, { root: scrollRoot, rootMargin, threshold: 0 });

  const observeAll = () => {
    container.querySelectorAll(cardSelector).forEach(card => {
      if (card.dataset.cullObserved !== '1') {
        card.dataset.cullObserved = '1';
        observer.observe(card);
      }
    });
  };
  observeAll();

  new MutationObserver(observeAll).observe(container, { childList: true });

  // The other real bug: `scrollRoot` is a `.tab-page`, which gets
  // `display: none` while its tab isn't active. A root with no layout box
  // has no viewport to intersect against, so every observed card gets
  // reported as non-intersecting and is marked `.is-culled` while hidden —
  // that's expected. The problem is that some WebViews don't reliably fire
  // a fresh IntersectionObserver callback the moment that root regains a
  // layout box (tab becomes active again), so cards can stay stuck
  // `.is-culled` — losing their blur/shadow — even though they're back on
  // screen. Stash what's needed to force a recheck, and remember it on the
  // container so a tab-activation hook can call it.
  container._cullRefresh = () => {
    container.querySelectorAll(cardSelector).forEach(card => {
      observer.unobserve(card);
      card.classList.remove('is-culled');
      delete card.dataset.cullObserved;
    });
    observeAll();
  };
}

// Call when a `.tab-page` (or any ancestor) becomes visible again, to force
// any culled cards inside it to be re-checked immediately instead of
// potentially staying stuck invisible. See enableCardCulling() above.
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
  getAccounts: () => invoke('list_accounts'),
  addOfflineAccount: (username) => invoke('add_offline_account', { username }),
  removeAccount: (id) => invoke('remove_account', { id }),
  setActiveAccount: (id) => invoke('set_active_account', { id }),
  getSettings: () => invoke('get_settings'),
  updateSettings: (settings) => invoke('save_settings', { settings }),
  getMusicDir: () => invoke('get_music_dir'),
  openMusicFolder: () => invoke('open_music_folder'),
  openLauncherFolder: () => invoke('open_launcher_folder'),
  getLauncherVersion: () => invoke('get_launcher_version'),
  checkForUpdate: () => invoke('check_for_update'),
  downloadUpdate: (url) => invoke('download_update', { url }),
  installUpdate: (downloadedPath) => invoke('install_update', { downloadedPath }),
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
  launchGame: (versionId) => invoke('launch_minecraft', { versionId }),
  getInstalledInstances: () => invoke('get_installed_instances'),
  removeInstance: (versionId) => invoke('remove_instance', { versionId }),
  updateInstance: (versionId, name, loaderVersion) =>
    invoke('update_instance', { versionId, name, loaderVersion }),
  deleteInstalledVersion: (versionId, directory) => invoke('delete_installed_version', { versionId, directory: directory || null }),
  scanMinecraftVersions: (directory) => invoke('scan_minecraft_versions', { directory: directory || null }),
  getHiddenInstances: () => invoke('get_hidden_instances'),
  hideInstance: (versionId) => invoke('hide_instance', { versionId }),
  unhideInstance: (versionId) => invoke('unhide_instance', { versionId }),
  getDependentInstances: (versionId) => invoke('get_dependent_instances', { versionId }),
  listJavaInstallations: () => invoke('list_java_installations'),
  onJavaInstallProgress: (cb) => listen('java-install-progress', cb),
  listMods: (gameDir) => invoke('list_mods', { directory: gameDir }),
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
  updateDiscordPresence: (tab, playingInstance, mcVersion) =>
    invoke('update_discord_presence', { tab, playingInstance, mcVersion }),
  onLog: (cb) => listen('log', cb),
  onDownloadProgress: (cb) => listen('download-progress', cb),
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
  discoverGetGameVersions: () => invoke('discover_get_game_versions'),
  discoverGetCategories: (projectType) => invoke('discover_get_categories', { projectType }),
  discoverGetResolutions: (projectType) => invoke('discover_get_resolutions', { projectType }),
  discoverGetLicenses: () => invoke('discover_get_licenses'),
  cacheModIcon: (url) => invoke('cache_mod_icon', { url }),
  identifyModsByHash: (hashes) => invoke('identify_mods_by_hash', { hashes }),
  discoverGetProjectsBatch: (ids) => invoke('discover_get_projects_batch', { ids }),
  listPresets: () => invoke('list_presets'),
  getPresetIconPath: (presetId) => invoke('get_preset_icon_path', { presetId }),
  resolvePresetModUrl: (modrinthId, loader, mcVersion) =>
    invoke('resolve_preset_mod_url', { modrinthId, loader: loader || null, mcVersion: mcVersion || null }),
  getPresetInstalledMods: (presetId, directory) =>
    invoke('get_preset_installed_mods', { presetId, directory }),
  applyPresetConfig: (presetId, directory) =>
    invoke('apply_preset_config', { presetId, directory }),
  onInstanceLog: (cb) => listen('instance-log', cb),
  onRunningInstancesChanged: (cb) => listen('running-instances-changed', cb),
  openInstanceConsoleWindow: async (versionId, name) => {
    const { WebviewWindow } = window.__TAURI__.webviewWindow;
    const label = 'console-' + versionId.replace(/[^a-zA-Z0-9_-]/g, '_');
    const existing = await WebviewWindow.getByLabel(label);
    if (existing) {
      await existing.setFocus();
      return;
    }
    const url = 'console.html?instance=' + encodeURIComponent(versionId) + '&name=' + encodeURIComponent(name || versionId);
    new WebviewWindow(label, {
      url,
      title: 'Console — ' + (name || versionId),
      width: 820,
      height: 560,
      minWidth: 480,
      minHeight: 320,
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

function showToast(message, type = 'info', title, actions) {
  if (!title) {
    title = type === 'success' ? 'Success' : type === 'error' ? 'Error' : type === 'warning' ? 'Warning' : 'Info';
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
  t.innerHTML = `
    <div class="toast-accent-stripe"></div>
    <div class="toast-main">
      <div class="toast-icon-badge"><span class="toast-icon">${icon}</span></div>
      <div class="toast-content">
        <div class="toast-title">${title}</div>
        ${message ? `<div class="toast-message">${message}</div>` : ''}
        ${actionsHtml}
      </div>
      <button class="toast-close" aria-label="Dismiss notification">\u2715</button>
    </div>
    <div class="toast-progress"><div class="toast-progress-bar"></div></div>
  `;
  c.appendChild(t);

  const entry = { el: t, remaining: duration, total: duration, paused: false, removed: false };
  activeToasts.push(entry);

  // Close button
  t.querySelector('.toast-close').addEventListener('click', () => dismissToast(entry));

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

  // Hover pauses countdown
  t.addEventListener('mouseenter', () => { entry.paused = true; });
  t.addEventListener('mouseleave', () => { entry.paused = false; });

  // Animate progress bar
  const bar = t.querySelector('.toast-progress-bar');
  let lastTime = performance.now();

  function tick(now) {
    if (entry.removed) return;
    const dt = now - lastTime;
    lastTime = now;
    if (!entry.paused) {
      entry.remaining -= dt;
      const pct = Math.max(0, (entry.remaining / entry.total) * 100);
      bar.style.width = pct + '%';
      if (entry.remaining <= 0) {
        dismissToast(entry);
        return;
      }
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
}

function dismissToast(entry) {
  if (entry.removed) return;
  entry.removed = true;
  entry.el.classList.add('toast-fade-out');
  setTimeout(() => {
    entry.el.remove();
    activeToasts = activeToasts.filter(e => e !== entry);
    // Drain queue
    while (activeToasts.length < TOAST_MAX_VISIBLE && toastQueue.length > 0) {
      const next = toastQueue.shift();
      spawnToast(next.message, next.type, next.title, next.actions);
    }
  }, 220);
}
window.showToast = showToast;

// ══════════════════════════════════════════════════════════════════
// TABS
// ══════════════════════════════════════════════════════════════════
function initTabs() {
  document.querySelectorAll('.pill-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.pill-tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const tabId = btn.dataset.tab;
      document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
      const page = document.getElementById('tab-' + tabId);
      if (page) {
        page.classList.add('active');
        refreshCardCullingIn(page);
      }

      // Lazy-load data when switching
      if (tabId === 'mods') {
        loadModInstances().then(() => loadMods()).catch(() => {});
      }
      if (tabId === 'discover') initDiscoverTabIfNeeded();
      if (tabId === 'presets') initPresetsTabIfNeeded();
      if (tabId === 'settings') { loadSettings(); renderHiddenInstancesSettings(); }

      // Update Discord RPC
      const tabName = tabId.charAt(0).toUpperCase() + tabId.slice(1);
      api.updateDiscordPresence(tabName, null, null).catch(() => { });
    });
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

async function refreshAccountUI() {
  try {
    const accounts = await api.getAccounts();
    const active = accounts.find(a => a.is_active);
    const accountNameEl = document.getElementById('account-name');
    if (accountNameEl) {
      accountNameEl.textContent = active ? maskUsernameForDisplay(active.username) : 'No account';
    }

    const list = document.getElementById('modal-account-list');
    if (!list) return;
    list.innerHTML = '';

    if (accounts.length === 0) {
      list.innerHTML = '<div class="empty-state" style="height:70px"><span>No accounts added yet</span></div>';
      return;
    }

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
      `;

      const initial = (acc.username || 'A').charAt(0).toUpperCase();
      const shownName = escapeHtml(maskUsernameForDisplay(acc.username));
      item.innerHTML = `
        <div style="display:flex; align-items:center; gap:12px;">
          <div style="width:32px; height:32px; border-radius:50%; background:var(--accent); color:#fff; display:flex; align-items:center; justify-content:center; font-weight:700; font-size:14px;">${initial}</div>
          <div>
            <div style="font-weight:600; font-size:14px; color:var(--text);">${shownName}</div>
            <div style="font-size:11px; color:var(--text-muted);">${acc.is_active ? '<span style="color:var(--accent); font-weight:700;">● Active Account</span>' : 'Offline Account'}</div>
          </div>
        </div>
        <div style="display:flex; gap:6px;">
          ${!acc.is_active ? `<button class="btn-secondary btn-sm btn-select-account" data-id="${acc.id}">Select</button>` : ''}
          <button class="btn-danger-outline btn-sm btn-delete-account" data-id="${acc.id}" title="Remove Account">✕</button>
        </div>
      `;
      list.appendChild(item);
    });

    // Event handlers for account item buttons
    list.querySelectorAll('.btn-select-account').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
          await api.setActiveAccount(btn.dataset.id);
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

function initAccountDropdown() {
  const accountBtn = document.getElementById('account-btn');
  const modalOverlay = document.getElementById('account-modal-overlay');
  const closeBtn = document.getElementById('btn-close-account-modal');
  const doneBtn = document.getElementById('btn-done-account-modal');
  const createBtn = document.getElementById('btn-modal-add-account');
  const usernameInput = document.getElementById('modal-new-username');

  // Open modal on account top-bar button click
  if (accountBtn && modalOverlay) {
    accountBtn.addEventListener('click', () => {
      modalOverlay.classList.remove('hidden');
      refreshAccountUI();
      if (usernameInput) usernameInput.focus();
    });
  }

  // Close modal functions
  const closeModal = () => {
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
const RING_CIRCUMFERENCE = 2 * Math.PI * 15.5;

// Shared "download" glyph (an arrow into a tray) used anywhere an update /
// download action needs an icon instead of the old ⬇️ emoji — the per-mod
// update button, the toolbar "Update All" button, etc.
const DOWNLOAD_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 4v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M6.5 11.5 12 17l5.5-5.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M5 20h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
// Up-arrow-to-tray / down-arrow-to-tray icons used on the Export/Import Mods
// buttons and overlay titles — same stroke style as DOWNLOAD_ICON_SVG above.
const EXPORT_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 15V3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M7.5 7.5 12 3l4.5 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const IMPORT_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M7.5 10.5 12 15l4.5-4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
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
  const ringFill = document.getElementById('dl-ring-fill');
  const title = document.getElementById('dl-widget-title');
  const sub = document.getElementById('dl-widget-sub');
  const countBadge = document.getElementById('dl-widget-count');
  const cardsContainer = document.getElementById('dl-cards');
  const cardTemplate = document.getElementById('dl-card-template');
  if (!widget || !cardTemplate) return;

  btn.addEventListener('click', () => {
    panel.classList.toggle('hidden');
  });

  // ── Files window — one small overlay shared by every card, showing the
  // list of individual files that download/install process has touched
  // (each just a name + status: downloading / completed / failed). ──
  const filesOverlay = document.getElementById('dl-files-overlay');
  const filesList = document.getElementById('dl-files-list');
  const filesTitle = document.getElementById('dl-files-title');
  function renderFilesList(card) {
    filesTitle.textContent = `Files — ${card.titleText || 'Download'}`;
    const wasNearTop = filesList.scrollTop <= 4;
    filesList.innerHTML = '';
    if (!card.files.length) {
      const empty = document.createElement('div');
      empty.className = 'dl-files-empty';
      empty.textContent = 'No file breakdown available for this download.';
      filesList.appendChild(empty);
      return;
    }
    // Newest/most-recently-touched file first (top), oldest at the
    // bottom — so whatever the download is doing right now is always
    // the first thing visible without having to scroll.
    [...card.files].reverse().forEach(f => {
      const row = document.createElement('div');
      row.className = 'dl-file-row dl-file-' + f.status;
      const top = document.createElement('div');
      top.className = 'dl-file-row-top';
      const name = document.createElement('span');
      name.className = 'dl-file-name';
      name.textContent = f.name;
      const status = document.createElement('span');
      status.className = 'dl-file-status';
      status.textContent = f.status === 'completed' ? 'Done' : f.status === 'failed' ? 'Failed' : f.status === 'pending' ? 'Waiting' : 'Downloading…';
      top.appendChild(name);
      top.appendChild(status);
      const track = document.createElement('div');
      track.className = 'dl-file-track';
      const bar = document.createElement('div');
      bar.className = 'dl-file-bar';
      track.appendChild(bar);
      row.appendChild(top);
      row.appendChild(track);
      filesList.appendChild(row);
    });
    // Only follow new activity to the top automatically if the person was
    // already up there — if they've scrolled down to look at earlier
    // files, leave them where they are instead of yanking the list back.
    if (wasNearTop) filesList.scrollTop = 0;
  }
  let filesWindowCardId = null;
  function openFilesWindow(card) {
    filesWindowCardId = card.id;
    renderFilesList(card);
    filesOverlay.classList.remove('hidden');
  }
  function refreshFilesWindowIfOpen(id) {
    if (filesWindowCardId !== id || filesOverlay.classList.contains('hidden')) return;
    const card = cards.get(id);
    if (card) renderFilesList(card);
  }
  const closeFilesBtn = document.getElementById('btn-close-dl-files');
  if (closeFilesBtn) closeFilesBtn.addEventListener('click', () => filesOverlay.classList.add('hidden'));

  function fileStart(id, name) {
    const card = cards.get(id);
    if (!card || !name) return;
    const pending = card.files.find(f => f.name === name && f.status === 'pending');
    if (pending) {
      pending.status = 'downloading';
    } else if (!card.files.find(f => f.name === name && f.status === 'downloading')) {
      card.files.push({ name, status: 'downloading' });
    }
    refreshFilesWindowIfOpen(id);
  }
  function fileDone(id, name, success) {
    const card = cards.get(id);
    if (!card || !name) return;
    const entry = [...card.files].reverse().find(f => f.name === name && f.status === 'downloading');
    if (entry) entry.status = success ? 'completed' : 'failed';
    else card.files.push({ name, status: success ? 'completed' : 'failed' });
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
    (names || []).forEach(name => {
      if (!name) return;
      if (card.files.some(f => f.name === name)) return;
      card.files.push({ name, status: 'pending' });
    });
    refreshFilesWindowIfOpen(id);
  }

  // id -> card state. Each card is one download/install process; several
  // can exist at once (an instance install plus one or more mod updates,
  // for instance), each rendered as its own entry in dl-cards.
  const cards = new Map();

  function showWidget() {
    clearTimeout(dlHideTimer);
    widget.classList.remove('hidden', 'dl-leaving');
  }

  function hideWidgetIfEmpty() {
    if (cards.size > 0) return;
    clearTimeout(dlHideTimer);
    dlHideTimer = setTimeout(() => {
      widget.classList.add('dl-leaving');
      panel.classList.add('dl-panel-leaving');
      setTimeout(() => {
        widget.classList.add('hidden');
        widget.classList.remove('dl-leaving');
        panel.classList.add('hidden');
        panel.classList.remove('dl-panel-leaving');
      }, 260);
    }, 250);
  }

  // Recomputes the collapsed floating button (icon/ring/title/sub/count)
  // from whichever cards are currently active.
  function refreshSummary() {
    const list = Array.from(cards.values());
    if (list.length === 0) return;
    countBadge.textContent = String(list.length);
    countBadge.classList.toggle('hidden', list.length <= 1);

    const primary = list.find(c => c.status === 'error') || list.find(c => c.status === 'paused') || list[0];
    title.textContent = primary.titleText;
    sub.textContent = primary.subText;
    widget.classList.toggle('dl-paused', primary.status === 'paused');

    const determinate = list.filter(c => c.percent !== null && c.status === 'downloading');
    if (determinate.length > 0) {
      const avg = determinate.reduce((s, c) => s + c.percent, 0) / determinate.length;
      ringFill.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - avg / 100));
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
    card.refs.cancelBtn.addEventListener('click', async () => {
      if (!card.onCancel) return;
      card.refs.cancelBtn.disabled = true;
      card.refs.cancelBtn.textContent = 'Cancelling…';
      try {
        await card.onCancel();
      } catch (e) {
        showToast('Failed to cancel download: ' + e, 'error');
        card.refs.cancelBtn.disabled = false;
        card.refs.cancelBtn.textContent = '✕ Cancel';
      }
    });
    card.refs.filesBtn.addEventListener('click', () => openFilesWindow(card));
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
  function beginInstanceInstallPlaceholder(id, label) {
    let card = cards.get(id);
    if (!card) {
      card = createCard(id);
      card.onPause = async () => {
        if (card.status === 'paused') await api.resumeDownload();
        else await api.pauseDownload();
      };
      card.onCancel = async () => { await api.cancelDownload(); };
    }
    card.status = 'downloading';
    card.percent = null;
    card.files = [];
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
    r.bar.style.width = pct + '%';
    r.percent.textContent = Math.round(pct) + '%';

    // Real concurrent file list from the backend (several files download
    // at once) — fall back to the single current_file for older payload
    // shapes so this doesn't break if a stale build sends one.
    const activeList = (p.active_files && p.active_files.length)
      ? p.active_files
      : (p.current_file ? [p.current_file] : []);

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
    // upstream) is marked completed. This replaces the old logic, which
    // assumed only one file was ever downloading at a time and broke once
    // the downloader started fetching several files in parallel.
    if (p.status === 'downloading') {
      const activeNow = new Set(activeList);
      const activeBefore = card.activeFileNames || new Set();
      activeNow.forEach((name) => {
        if (!activeBefore.has(name)) fileStart(INSTANCE_INSTALL_CARD_ID, name);
      });
      activeBefore.forEach((name) => {
        if (!activeNow.has(name)) fileDone(INSTANCE_INSTALL_CARD_ID, name, true);
      });
      card.activeFileNames = activeNow;
    }
    refreshFilesWindowIfOpen(INSTANCE_INSTALL_CARD_ID);

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
    card.files = [];
    card.status = 'downloading';
    card.cancelled = false;
    card.percent = opts.determinate ? 0 : null;
    card.el.classList.remove('dl-card-paused', 'dl-card-error', 'dl-card-cancelled');
    card.el.classList.add('dl-card-no-pause');
    card.el.classList.add('dl-card-no-stats');
    card.el.classList.toggle('dl-card-indeterminate', !opts.determinate);
    if (opts.determinate) card.refs.bar.style.width = '0%';
    card.titleText = titleText;
    card.subText = subText || '';
    card.refs.title.textContent = titleText;
    card.refs.stage.textContent = subText || '';
    card.refs.pill.textContent = 'Downloading';
    card.onPause = null;
    card.onCancel = opts.onCancel || (async () => {
      card.cancelled = true;
      await api.cancelGenericDownload(id);
    });
    refreshSummary();
  }

  function updateGenericDownload(id, titleText, subText, percent) {
    const card = cards.get(id);
    if (!card) return;
    if (titleText) { card.titleText = titleText; card.refs.title.textContent = titleText; }
    if (subText !== undefined) { card.subText = subText; card.refs.stage.textContent = subText; }
    if (percent !== undefined && percent !== null) {
      card.el.classList.remove('dl-card-indeterminate');
      const pct = Math.max(0, Math.min(100, percent));
      card.percent = pct;
      card.refs.bar.style.width = pct + '%';
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

  dlWidgetGeneric = {
    begin: beginGenericDownload,
    update: updateGenericDownload,
    end: endGenericDownload,
    isCancelled: isGenericDownloadCancelled,
    beginInstanceInstall: beginInstanceInstallPlaceholder,
    failInstanceInstall,
    fileStart,
    fileDone,
    seedFiles,
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

let draggedInstanceId = null;

function renderInstanceList() {
  const list = document.getElementById('instance-list');
  const instances = getVisibleInstances();
  list.innerHTML = '';
  if (instances.length === 0) {
    list.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_EMPTY_BOX_SVG}</span><span>No instances yet</span></div>`;
    return;
  }
  const favId = getFavoriteInstance();
  instances.forEach(inst => {
    const card = document.createElement('div');
    const isFav = inst.version_id === favId;
    card.className = 'instance-card'
      + (inst.version_id === selectedInstanceId ? ' selected' : '')
      + (isFav ? ' favorited' : '');
    card.dataset.versionId = inst.version_id;
    // The favorited instance is pinned and shouldn't be dragged out of the
    // top spot; everything else can be freely reordered.
    card.draggable = !isFav;
    const loaderStr = loaderLabel(inst.loader);
    card.innerHTML = `
      <div class="inst-icon"><img src="${loaderIcon(inst.loader)}" alt="${loaderStr}" draggable="false" /></div>
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
    card.addEventListener('click', () => selectInstance(inst.version_id));
    const troubleshootBtn = card.querySelector('.inst-troubleshoot-btn');
    if (troubleshootBtn) {
      troubleshootBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        showInstanceTroubleshootWindow(inst);
      });
    }
    const favoriteBtn = card.querySelector('.inst-favorite-btn');
    if (favoriteBtn) {
      favoriteBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        const currentlyFav = getFavoriteInstance() === inst.version_id;
        setFavoriteInstance(currentlyFav ? null : inst.version_id);
        renderInstanceList();
        showToast(currentlyFav
          ? `"${inst.name || inst.version_id}" unpinned`
          : `"${inst.name || inst.version_id}" pinned to top`, 'success');
      });
    }
    const hideBtn = card.querySelector('.inst-hide-btn');
    if (hideBtn) {
      hideBtn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        try {
          await api.hideInstance(inst.version_id);
          if (getFavoriteInstance() === inst.version_id) setFavoriteInstance(null);
          await refreshInstances();
          if (selectedInstanceId === inst.version_id) {
            selectedInstanceId = null;
            selectInstance(null);
          }
          renderInstanceList();
          renderHiddenInstancesSettings();
          showToast(`"${inst.name || inst.version_id}" hidden — unhide it anytime from Settings`, 'success');
        } catch (e) {
          showToast('Failed to hide instance: ' + e, 'error');
        }
      });
    }

    // Drag-and-drop reordering. The favorited card (draggable=false) can't
    // be picked up, but other cards can still be dropped above/below it —
    // it'll simply snap back to the top on the next render either way.
    card.addEventListener('dragstart', (ev) => {
      draggedInstanceId = inst.version_id;
      card.classList.add('dragging');
      ev.dataTransfer.effectAllowed = 'move';
      try { ev.dataTransfer.setData('text/plain', inst.version_id); } catch (e) { /* ignore */ }
    });
    card.addEventListener('dragend', () => {
      draggedInstanceId = null;
      card.classList.remove('dragging');
      list.querySelectorAll('.instance-card.drag-over').forEach(el => el.classList.remove('drag-over', 'drag-over-below'));
    });
    card.addEventListener('dragover', (ev) => {
      if (!draggedInstanceId || draggedInstanceId === inst.version_id) return;
      ev.preventDefault();
      ev.dataTransfer.dropEffect = 'move';
      const rect = card.getBoundingClientRect();
      const before = (ev.clientY - rect.top) < rect.height / 2;
      list.querySelectorAll('.instance-card.drag-over').forEach(el => el.classList.remove('drag-over', 'drag-over-below'));
      card.classList.add('drag-over');
      card.classList.toggle('drag-over-below', !before);
    });
    card.addEventListener('drop', (ev) => {
      ev.preventDefault();
      card.classList.remove('drag-over', 'drag-over-below');
      if (!draggedInstanceId || draggedInstanceId === inst.version_id) return;
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

    list.appendChild(card);
  });
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

setInterval(updateSelectedInstancePlaytimeDisplay, 1000);

function selectInstance(id) {
  selectedInstanceId = id;
  renderInstanceList();
  const inst = getInstances().find(i => i.version_id === id);
  const nameEl = document.getElementById('detail-name');
  const verEl = document.getElementById('detail-version');
  const loaderEl = document.getElementById('info-loader');
  const dirEl = document.getElementById('info-dir');
  const playtimeEl = document.getElementById('info-playtime');
  const playBtn = document.getElementById('btn-play');
  const iconEl = document.getElementById('detail-icon');

  if (!inst) {
    nameEl.textContent = 'No instance selected';
    verEl.textContent = '';
    loaderEl.textContent = '—';
    dirEl.textContent = '—';
    if (playtimeEl) playtimeEl.textContent = '—';
    playBtn.disabled = true;
    if (iconEl) iconEl.innerHTML = '';
    syncInstanceSelectionAcrossTabs().catch(() => {});
    return;
  }

  const loaderStr = (inst.loader && inst.loader !== 'vanilla') ? loaderLabel(inst.loader) : null;
  nameEl.textContent = (inst.name || inst.version_id) + (inst.missing_jar ? ' (incomplete)' : '');
  verEl.textContent = inst.version_id + (loaderStr ? '  •  ' + loaderStr : '');
  loaderEl.textContent = loaderStr || 'Vanilla';
  dirEl.textContent = inst.directory || (settings ? settings.game_directory : '—');
  updateSelectedInstancePlaytimeDisplay();
  playBtn.disabled = !!inst.missing_jar;
  if (iconEl) iconEl.innerHTML = `<img src="${loaderIcon(inst.loader)}" alt="${loaderLabel(inst.loader)}" draggable="false" />`;
  // Refresh mod-update data for whatever instance is now selected — its
  // loader/game-version can differ completely from whatever was selected
  // before, so a check that already ran for a previous instance must never
  // be shown here. Clear the in-memory info immediately so the UI doesn't
  // flash stale "update available" buttons from the previous instance
  // while the (possibly shared-directory) check re-runs in the background.
  modUpdateInfo = modUpdateInfoByDir.get(modsCacheKey(inst, inst.directory || (settings ? settings.game_directory : ''))) || new Map();
  refreshUpdateButtonsOnVisibleCards();
  // `syncInstanceSelectionAcrossTabs` is what actually updates the Mods
  // tab's own instance dropdown to match `id` — wait for that to finish
  // before checking for updates, otherwise `getModsTargetInstance()` could
  // still read the dropdown's stale previous value and check the wrong
  // instance (the exact bug this is fixing).
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
}

// Actually deletes an instance's files + tracked entry (shared by the plain
// confirm() path and the "Delete Anyway" button on the vanilla-dependency
// warning).
async function performInstanceDelete(versionId, inst) {
  try {
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
  showToast(`Reinstalling ${inst.name || inst.version_id}…`, 'info');
  if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, inst.minecraft_version);
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
  // Play
  document.getElementById('btn-play').addEventListener('click', async () => {
    if (!selectedInstanceId) return;
    const btn = document.getElementById('btn-play');
    btn.disabled = true;
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
      const launchPromise = api.launchGame(selectedInstanceId);
      launchPromise.then(() => {
        if (timedOut) {
          showToast(`"${(inst && (inst.name || inst.version_id)) || selectedInstanceId}" launched (after a delay)`, 'success');
          refreshRunningInstances();
        }
      }).catch(() => { });

      const TIMEOUT_MS = 5000;
      const timeoutPromise = new Promise((_, reject) => {
        const check = () => {
          setTimeout(() => {
            if (javaInstallInProgress) {
              // Still downloading/extracting Java — don't give up, just
              // keep waiting and re-check shortly.
              check();
              return;
            }
            timedOut = true;
            reject(new Error('Launch timed out after 5 seconds'));
          }, TIMEOUT_MS);
        };
        check();
      });

      await Promise.race([launchPromise, timeoutPromise]);
      showToast('Game launched!', 'success');
    } catch (e) {
      if (timedOut) {
        showToast('Launch timed out — the instance may be broken', 'error');
        showCrashTroubleshootWindow(inst, 'Launch timed out after 5 seconds. This usually means the instance is broken or missing files.');
      } else {
        showToast('Launch failed: ' + e, 'error');
        showCrashTroubleshootWindow(inst, e);
      }
    }
    btn.disabled = false;
    delete btn.dataset.launching;
    await refreshRunningInstances();
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

    if (!confirm(`Delete "${(inst && (inst.name || inst.version_id)) || selectedInstanceId}"? This permanently removes its folder from .minecraft/versions and cannot be undone.`)) return;
    await performInstanceDelete(selectedInstanceId, inst);
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

  // New Instance overlay
  const overlay = document.getElementById('new-instance-overlay');
  document.getElementById('btn-new-instance').addEventListener('click', async () => {
    overlay.classList.remove('hidden');
    await loadMcVersions();
    await initInstanceDirField();
  });
  document.getElementById('btn-cancel-new-instance').addEventListener('click', () => overlay.classList.add('hidden'));
  document.getElementById('btn-cancel-install-form').addEventListener('click', () => overlay.classList.add('hidden'));

  // Install
  document.getElementById('btn-start-install').addEventListener('click', installInstance);

  // Vanilla has no loader version to configure, so hide that tile entirely
  // when it's selected instead of leaving a meaningless field on screen.
  const instLoaderSelect = document.getElementById('inst-loader');
  const instLoaderVersionTile = document.getElementById('inst-loader-version-tile');
  function syncLoaderVersionVisibility() {
    const isVanilla = (instLoaderSelect.value || 'Vanilla').toLowerCase() === 'vanilla';
    instLoaderVersionTile.classList.toggle('hidden', isVanilla);
  }
  if (instLoaderSelect) {
    instLoaderSelect.addEventListener('change', syncLoaderVersionVisibility);
    syncLoaderVersionVisibility();
  }

  // Edit Instance overlay
  const editOverlay = document.getElementById('edit-instance-overlay');
  const editLoaderVersionTile = document.getElementById('edit-inst-loader-version-tile');
  const editMcVersionSelect = document.getElementById('edit-inst-mc-version');
  const editLoaderSelect = document.getElementById('edit-inst-loader');

  function syncEditLoaderVersionVisibility() {
    const isVanilla = (editLoaderSelect.value || 'Vanilla').toLowerCase() === 'vanilla';
    editLoaderVersionTile.classList.toggle('hidden', isVanilla);
  }
  if (editLoaderSelect) {
    editLoaderSelect.addEventListener('change', syncEditLoaderVersionVisibility);
  }

  document.getElementById('btn-edit-instance').addEventListener('click', async () => {
    if (!selectedInstanceId) return;
    const inst = getInstances().find(i => i.version_id === selectedInstanceId);
    if (!inst) return;

    document.getElementById('edit-inst-name').value = inst.name || inst.version_id;
    editLoaderSelect.value = loaderLabel(inst.loader);
    document.getElementById('edit-inst-loader-version').value = (inst.loader_version && inst.loader_version !== 'latest') ? inst.loader_version : '';
    document.getElementById('edit-inst-dir').textContent = inst.directory || (settings ? settings.game_directory : '—');
    editMcVersionSelect.innerHTML = '<option>Loading…</option>';

    syncEditLoaderVersionVisibility();

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
    let newLoader = (editLoaderSelect.value || 'vanilla').toLowerCase();
    const loaderVersion = document.getElementById('edit-inst-loader-version').value.trim() || 'latest';

    const versionOrLoaderChanged =
      newMcVersion !== (inst.minecraft_version || inst.version_id) ||
      newLoader !== (inst.loader || 'vanilla').toLowerCase();

    const saveBtn = document.getElementById('btn-save-edit-instance');
    saveBtn.disabled = true;

    try {
      if (!versionOrLoaderChanged) {
        // Nothing that requires a reinstall changed — just update the
        // lightweight metadata like before.
        await api.updateInstance(selectedInstanceId, name || null, loaderVersion || 'latest');
      } else {
        // Minecraft version and/or loader changed — this instance needs
        // to be reinstalled. If the exact same version/loader is already
        // downloaded for another instance, install_minecraft will reuse
        // those files instead of re-downloading them.
        closeEditInstanceOverlay();
        showToast(`Reinstalling ${name || inst.name} as ${newMcVersion} (${loaderLabel(newLoader)})…`, 'info');
        if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, newMcVersion);
        const newInstance = await api.installVersion(newMcVersion, newLoader, loaderVersion, inst.directory, name || inst.name, inst.version_id);
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

  function syncDirRowVisibility() {
    dirPathRow.classList.toggle('hidden', !dirCustomRadio.checked);
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
  dirPathRow.classList.add('hidden');
  dirPathInput.value = '';

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

async function installInstance() {
  const mcVersion = document.getElementById('inst-mc-version').value;
  let loader = document.getElementById('inst-loader').value || 'vanilla';
  let loaderVersion = document.getElementById('inst-loader-version').value.trim() || 'latest';
  if (!mcVersion) { showToast('Version required', 'error'); return; }
  if (loader.toLowerCase() === 'vanilla') loader = 'vanilla';

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

  const btn = document.getElementById('btn-start-install');
  btn.disabled = true;

  // Close the form right away — the floating download widget (bottom-left)
  // tracks progress from here, so the user is free to keep using the app.
  document.getElementById('new-instance-overlay').classList.add('hidden');
  showToast(`Installing ${name}…`, 'info');
  if (dlWidgetGeneric) dlWidgetGeneric.beginInstanceInstall(INSTANCE_INSTALL_CARD_ID, mcVersion);

  try {
    const result = await api.installVersion(mcVersion, loader, loaderVersion, directory, name);

    // A brand-new instance should never come up hidden — but its
    // version_id can coincide with an entry that was previously hidden
    // (e.g. a bare vanilla version auto-hidden because it only existed to
    // satisfy a modded instance's dependency, or one the user hid earlier).
    // Since this was just explicitly installed, make sure it's visible.
    if (result && result.version_id) {
      try { await api.unhideInstance(result.version_id); } catch (e) { /* not hidden — fine */ }
    }

    await refreshInstances();

    showToast('Instance installed!', 'success');
    renderInstanceList();
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

function buildModCard(mod, directory, preservedIconHtml) {
  const card = document.createElement('div');
  card.className = 'glass-card mod-card' + (!mod.enabled ? ' disabled' : '');
  card.dataset.path = mod.path || '';
  card.dataset.name = (mod.name || mod.file_name || '').toLowerCase();
  const badges = (mod.loader || '').split(',').map(l => l.trim()).filter(Boolean).map(l => `<span class="loader-badge ${l.toLowerCase()}">${l}</span>`).join(' ');
  card.innerHTML = `
    <div class="mod-info">
      <div class="mod-icon loading">${ICON_UNKNOWN_SVG}</div>
      <div class="mod-meta">
        <div class="mod-name">${mod.name}</div>
        <div class="mod-desc">${mod.description ? (mod.description.length > 140 ? mod.description.slice(0,137) + '...' : mod.description) : ''}</div>
        <div class="mod-version">${mod.version}${badges ? ' ' + badges : ''}</div>
      </div>
    </div>
    <div class="mod-actions">
      <label class="mod-toggle-wrap">
        <input type="checkbox" ${mod.enabled ? 'checked' : ''} data-path="${mod.path}" class="mod-toggle-input">
        <span class="mod-toggle-slider"></span>
      </label>
      <button class="btn-update-mod" data-path="${mod.path}" title="Update to latest version" type="button">${DOWNLOAD_ICON_SVG}</button>
      <button class="btn-danger-pill btn-sm btn-delete-mod" data-path="${mod.path}" title="Delete mod">🗑</button>
    </div>
  `;

  // Same method as the Java client's known-hash carry-over in refreshModsView:
  // if this mod's icon was already resolved before this rebuild, re-apply that
  // result directly instead of resetting to the "loading" placeholder and
  // re-running the whole resolve/fetch pipeline. Without this, the periodic
  // mods auto-refresh (every MODS_AUTO_REFRESH_MS) rebuilds every card from
  // scratch, which is what made already-loaded icons appear to "unload" and
  // reload on a timer even though nothing about the mod had changed.
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

  // Update button only appears when a prior "Check Updates" / "Update All"
  // pass found a newer version available for this specific mod — the card
  // itself also gets a glowing accent outline as an at-a-glance signal.
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
    if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading…', `Updating ${modLabel}…`);
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

  // Card click selects/unselects (ignore clicks on action controls)
  card.addEventListener('click', (e) => {
    if (e.target.closest('.mod-actions')) return;
    card.classList.toggle('selected');
    updateDeleteSelectedState();
  });

  // Toggle enable/disable in place — no full grid reload, so the card list
  // doesn't flash/disappear on every click.
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

  // Delete just removes this one card — again, no full reload/flash.
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

  return card;
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

async function loadMods() {
  if (!settings) return;
  const grid = document.getElementById('mods-grid');
  const countEl = document.getElementById('mods-count');
  const deleteSelectedBtn = document.getElementById('btn-delete-selected-mods');

  // Only show a "Loading…" placeholder on the very first load for this
  // directory — subsequent refreshes (e.g. after Check Updates) swap the
  // grid content in one go so the list doesn't blank out and flash.
  const isFirstLoad = grid.children.length === 0;
  if (isFirstLoad) grid.innerHTML = '<div class="empty-state"><span>Loading mods…</span></div>';

  const targetInstance = getModsTargetInstance();
  const directory = targetInstance ? (targetInstance.directory || settings.game_directory) : settings.game_directory;
  modUpdateInfo = modUpdateInfoByDir.get(modsCacheKey(targetInstance, directory)) || new Map();
  refreshUpdateAllButtonState();

  // Snapshot which mod icons are already resolved (loaded image or a
  // confirmed "no icon" fallback) before we tear down the grid. Every prior
  // auto-refresh tick rebuilt every card from scratch, which threw this
  // state away and made icons flicker back to the loading spinner every
  // MODS_AUTO_REFRESH_MS even though the mod itself hadn't changed at all.
  const preservedIcons = new Map();
  grid.querySelectorAll('.mod-card').forEach(card => {
    const path = card.dataset.path;
    const iconEl = card.querySelector('.mod-icon');
    if (path && iconEl && !iconEl.classList.contains('loading')) {
      preservedIcons.set(path, iconEl.innerHTML);
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
      mods.forEach(mod => frag.appendChild(buildModCard(mod, directory, preservedIcons.get(mod.path))));
    }
    grid.innerHTML = '';
    grid.appendChild(frag);

    if (deleteSelectedBtn) deleteSelectedBtn.classList.toggle('hidden', mods.length === 0);
    updateModsCount();
    filterMods();
    // Same off-screen "cull the blur, keep the listeners" treatment already
    // used for the instance list, Discover grid, and Settings cards — the
    // mods grid was the one long, blur-heavy list that didn't have it, so
    // scrolling a big modlist repainted every card's backdrop-filter even
    // for the ones off-screen.
    enableCardCulling(grid, '.mod-card');
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

  // The webview's native browser drag/drop behavior (opening the dropped
  // file as if it were navigated to) fights with Tauri's own drag-drop
  // handling and can take the whole window down. Swallow it everywhere,
  // unconditionally, before Tauri's handling ever runs.
  ['dragover', 'drop'].forEach(evt => {
    window.addEventListener(evt, (e) => e.preventDefault());
  });

  // Tauri emits window-level drag events with the full OS file paths —
  // no browser File objects involved, so this works for arbitrarily large
  // jars without reading them into memory on the frontend at all.
  let dragActive = false;
  const setDragActive = (on) => {
    dragActive = on;
    if (overlay) overlay.classList.toggle('active', on && getActiveTabId() === 'mods');
  };

  listen('tauri://drag-enter', () => setDragActive(true));
  listen('tauri://drag-over', () => { if (!dragActive) setDragActive(true); });
  listen('tauri://drag-leave', () => setDragActive(false));
  listen('tauri://drag-drop', (event) => {
    setDragActive(false);
    const paths = (event && event.payload && event.payload.paths) || [];
    if (getActiveTabId() !== 'mods') {
      if (paths.some(p => p.toLowerCase().endsWith('.jar'))) {
        showToast('Switch to the Mods tab to drop mod files', 'info');
      }
      return;
    }
    installDroppedModPaths(paths);
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
        if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, `Updating ${up.length} mod(s)…`, 'Starting…');
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
      if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading dependencies…', 'Checking mods…');
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
      if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Fixing mods…', 'Installing missing dependencies…');

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
  type: 'mod',       // 'mod' | 'resourcepack'
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
};

let presetsInstanceSelectWired = false;

function initPresetsTabIfNeeded() {
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
  if (!presetsState.loaded) {
    presetsState.loaded = true;
    loadPresets();
  }
}

function populatePresetsInstanceSelect() {
  const sel = document.getElementById('presets-instance-select');
  if (!sel) return;
  const desired = sel.value;
  const instances = getInstances();
  sel.innerHTML = '<option value="">Select a target instance…</option>';
  instances.forEach(inst => {
    const opt = document.createElement('option');
    opt.value = inst.version_id;
    opt.textContent = inst.name || inst.version_id;
    sel.appendChild(opt);
  });
  if (desired && instances.some(i => i.version_id === desired)) sel.value = desired;
  else if (selectedInstanceId && instances.some(i => i.version_id === selectedInstanceId)) sel.value = selectedInstanceId;
}

async function loadPresets() {
  const grid = document.getElementById('presets-grid');
  if (!grid) return;
  grid.innerHTML = '<div class="empty-state"><span>Loading presets…</span></div>';
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
    grid.innerHTML = '<div class="empty-state"><span>No bundled presets found</span></div>';
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

    const pillRow = document.createElement('div');
    pillRow.className = 'preset-pill-row';
    pillRow.style.marginTop = '6px';
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
    top.appendChild(titleCol);
    card.appendChild(top);

    if (preset.description) {
      const desc = document.createElement('div');
      desc.className = 'preset-card-desc';
      desc.textContent = preset.description;
      card.appendChild(desc);
    }

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
    actions.appendChild(targetLabel);

    const applyBtn = document.createElement('button');
    applyBtn.className = 'btn-accent btn-sm';
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
  applyPresetState = null;
}

function initApplyPresetOverlayEvents() {
  document.getElementById('btn-close-apply-preset').addEventListener('click', closeApplyPresetOverlay);
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
      dlWidgetGeneric.begin(dlId, `Applying ${preset.name}`, `0 / ${selected.length} mods`, { determinate: true });
      dlWidgetGeneric.seedFiles(dlId, selected.map(r => r.name));
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
      dlWidgetGeneric.begin(dlId, `Importing mods into ${targetInstance.name || targetInstance.version_id}`, `0 / ${selected.length} mods`, { determinate: true });
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
    applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
    performDiscoverSearch();
    return;
  }
  // Already loaded from an earlier visit to this tab — but the selected
  // instance may have changed while Discover wasn't the active tab (that
  // sync only runs for the active tab; see `syncInstanceSelectionAcrossTabs`),
  // so re-sync the target/filters now rather than showing whatever the
  // previously-selected instance last searched for.
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

// When an instance is picked (either via the Discover tab's own "Targeted
// Instance" dropdown, or by selecting an instance elsewhere in the app
// while the Discover tab is open), auto-select that instance's loader and
// Minecraft version in the Loader/Game Version filters — so the search
// results only show things compatible with what's actually selected,
// without the user having to set those filters by hand every time.
// Gated behind a settings toggle since some people would rather the
// filters stay untouched. Returns true if it actually changed anything.
function applyInstanceFiltersToDiscover(inst) {
  if (!inst) return false;
  if (settings && settings.auto_apply_instance_filters_in_discover === false) return false;

  let changed = false;

  // Loader isn't a resourcepack facet — only auto-set it while browsing mods.
  if (discoverState.type === 'mod') {
    const loaderValue = (inst.loader || 'vanilla').toLowerCase();
    const nextLoader = DISCOVER_LOADERS.some(l => l.value === loaderValue) ? loaderValue : 'any';
    if (discoverState.loader !== nextLoader) {
      discoverState.loader = nextLoader;
      changed = true;
    }
  }

  const gameVersion = inst.minecraft_version || '';
  if (gameVersion && discoverState.gameVersion !== gameVersion) {
    discoverState.gameVersion = gameVersion;
    changed = true;
  }

  if (changed) {
    discoverState.page = 1;
    updateDiscoverFilterButtonStates();
  }
  return changed;
}

function showDiscoverSkeletons() {
  const grid = document.getElementById('discover-results');
  if (!grid) return;
  grid.innerHTML = '';
  grid.classList.toggle('view-list', discoverState.view === 'list');
  const count = discoverState.pageSize;
  for (let i = 0; i < count; i++) {
    const sk = document.createElement('div');
    sk.className = 'discover-skeleton';
    sk.style.animationDelay = `${Math.min(i, count) * 30}ms`;
    sk.innerHTML = `
      <div class="discover-skeleton-icon"></div>
      <div class="discover-skeleton-body">
        <div class="discover-skeleton-line w-60"></div>
        <div class="discover-skeleton-line w-35 dim"></div>
        <div class="discover-skeleton-line w-90 dim"></div>
        <div class="discover-skeleton-line w-75 dim"></div>
        <div class="discover-skeleton-line w-40 pill"></div>
      </div>
    `;
    grid.appendChild(sk);
  }
}

function updateDiscoverPagination() {
  const prevBtn = document.getElementById('discover-prev-page');
  const nextBtn = document.getElementById('discover-next-page');
  const info = document.getElementById('discover-page-info');
  const totalPages = Math.max(1, Math.ceil(discoverState.totalHits / discoverState.pageSize));
  if (info) info.textContent = `Page ${discoverState.page} of ${totalPages}`;
  if (prevBtn) prevBtn.disabled = discoverState.page <= 1;
  if (nextBtn) nextBtn.disabled = discoverState.page >= totalPages;
}

async function performDiscoverSearch() {
  const grid = document.getElementById('discover-results');
  if (!grid) return;
  showDiscoverSkeletons();

  // Loader/Environment are mod-only facets on Modrinth's side (resourcepacks
  // have neither) — never send them while browsing resourcepacks, even if
  // stale state somehow lingers, or every resourcepack search would come
  // back empty.
  const isModSearch = discoverState.type === 'mod';
  const loaderFilter = (isModSearch && discoverState.loader !== 'any') ? discoverState.loader : null;
  // Previously this silently fell back to the selected instance's exact
  // Minecraft version whenever no Game Version filter was explicitly chosen,
  // even though the Game Version filter button still showed "any" with no
  // active-filter indicator. That invisible extra facet is what made the
  // search bar seem to "always" return no results — any instance whose
  // tracked version didn't line up exactly with Modrinth's version tags (or
  // just wasn't the version most mods list first) silently zeroed out every
  // search. The Game Version dropdown is the one place this filter should be
  // applied from now — respect only what's actually visible/selected there.
  const gameVersion = discoverState.gameVersion || null;
  // Resolution is really just a category under a different header on
  // Modrinth's side (see discover_get_resolutions), so it's sent as part
  // of the same `categories` facet list rather than as its own param.
  const categoriesFilter = discoverState.resolution
    ? [...discoverState.categories, discoverState.resolution]
    : discoverState.categories;

  try {
    const result = await api.discoverSearch(
      discoverState.query,
      discoverState.type,
      loaderFilter,
      gameVersion,
      categoriesFilter,
      (isModSearch && discoverState.environment !== 'any') ? discoverState.environment : null,
      discoverState.license || null,
      discoverState.openSourceOnly,
      discoverState.page,
      discoverState.pageSize
    );
    discoverState.totalHits = result.total_hits || 0;
    renderDiscoverResults(result.hits || []);
    updateDiscoverPagination();
  } catch (e) {
    grid.innerHTML = `<div class="empty-state"><span style="color:var(--danger)">${discoverEscape(String(e))}</span></div>`;
  }
}

function renderDiscoverResults(hits) {
  const grid = document.getElementById('discover-results');
  grid.innerHTML = '';
  grid.classList.toggle('view-list', discoverState.view === 'list');
  if (hits.length === 0) {
    grid.innerHTML = `<div class="empty-state"><span class="empty-icon">${ICON_SEARCH_EMPTY_SVG}</span><span>No results found</span></div>`;
    return;
  }
  hits.forEach((hit, i) => grid.appendChild(buildDiscoverCard(hit, i)));
  enableCardCulling(grid, '.discover-card');
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

function buildDiscoverCard(hit, index = 0) {
  const card = document.createElement('div');
  card.className = 'discover-card discover-card-enter';
  // Staggered fade/slide-in, capped so a long page doesn't take forever to
  // finish animating. Runs once on creation only — see the CSS comment on
  // `.discover-card-enter` for why this can't live on `.discover-card`
  // itself (culling toggling would keep restarting it while scrolling).
  card.style.animationDelay = `${Math.min(index, 12) * 35}ms`;
  card.addEventListener('animationend', () => {
    card.classList.remove('discover-card-enter');
    card.style.animationDelay = '';
  }, { once: true });

  const iconHtml = hit.icon_url
    ? `<img src="${discoverEscape(hit.icon_url)}" alt="" draggable="false" loading="lazy" />`
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

  card.innerHTML = `
    <div class="discover-card-icon">${iconHtml}</div>
    <div class="discover-card-main">
      <div class="discover-card-heading">
        <div class="discover-card-title" title="${discoverEscape(hit.title)}">${discoverEscape(hit.title)}</div>
        ${hit.license ? `<span class="discover-card-badge">${discoverEscape(hit.license)}</span>` : ''}
        ${sideLabel ? `<span class="discover-card-badge discover-card-badge-side ${sideVariant}">${discoverEscape(sideLabel)}</span>` : ''}
      </div>
      <div class="discover-card-author">by ${discoverEscape(hit.author)}</div>
      <div class="discover-card-desc">${discoverEscape(hit.description)}</div>
      ${tagsHtml ? `<div class="discover-card-tags">${tagsHtml}</div>` : ''}
    </div>
    <div class="discover-card-stats">
      <span class="discover-card-stat" title="Downloads">⬇ ${formatDiscoverCount(hit.downloads)}</span>
      <span class="discover-card-stat" title="Followers">♥ ${formatDiscoverCount(hit.follows)}</span>
      ${updatedLabel ? `<span class="discover-card-stat discover-card-stat-dim" title="Last updated">↻ ${discoverEscape(updatedLabel)}</span>` : ''}
    </div>
    <div class="discover-card-actions">
      <select class="input-field discover-version-select" data-project-id="${discoverEscape(hit.project_id)}">
        <option value="">Loading versions…</option>
      </select>
      <button class="btn-accent btn-sm discover-download-btn" data-project-id="${discoverEscape(hit.project_id)}" data-project-type="${discoverEscape(hit.project_type)}" disabled>Download</button>
      <button class="btn-pill btn-sm discover-retry-btn hidden" title="Retry loading versions">⟳ Retry</button>
    </div>
  `;

  const versionSelect = card.querySelector('.discover-version-select');
  const downloadBtn = card.querySelector('.discover-download-btn');
  const retryBtn = card.querySelector('.discover-retry-btn');

  loadDiscoverCardVersions(hit, versionSelect, downloadBtn, retryBtn);

  downloadBtn.addEventListener('click', () => downloadDiscoverSelection(hit, versionSelect, downloadBtn));
  retryBtn.addEventListener('click', () => {
    retryBtn.classList.add('hidden');
    versionSelect.innerHTML = '<option value="">Loading versions…</option>';
    loadDiscoverCardVersions(hit, versionSelect, downloadBtn, retryBtn);
  });

  return card;
}

// A version is only judged against a target instance if one is actually
// selected — with no target there's nothing to be incompatible *with*, so
// everything is shown as compatible. Mods are checked against both game
// version and loader; resourcepacks only have a game version to match
// against (Modrinth resourcepacks aren't loader-specific).
function isDiscoverVersionCompatible(version, hit, target) {
  if (!target) return true;
  if (target.minecraft_version && !version.game_versions.includes(target.minecraft_version)) {
    return false;
  }
  if (hit.project_type === 'mod') {
    const loader = (target.loader || 'vanilla').toLowerCase();
    if (loader !== 'vanilla') {
      const loaders = (version.loaders || []).map(l => l.toLowerCase());
      if (loaders.length && !loaders.includes(loader)) return false;
    }
  }
  return true;
}

async function loadDiscoverCardVersions(hit, versionSelect, downloadBtn, retryBtn) {
  const target = currentDiscoverTargetInstance();
  // Always fetch the full version list rather than asking Modrinth to
  // filter it down — filtering server-side made incompatible versions
  // vanish from the dropdown entirely (so e.g. picking "Any version" in
  // the search filter would surface a resourcepack, but its own version
  // picker still only showed versions matching the target instance,
  // often leaving nothing at all). Now every version is listed; ones that
  // don't match the targeted instance are just labelled/colored instead
  // of hidden, so they're still there to pick if the user wants them.
  if (retryBtn) retryBtn.classList.add('hidden');

  try {
    const versions = await api.discoverGetVersions(hit.project_id, null, null);
    if (!versions || versions.length === 0) {
      versionSelect.innerHTML = '<option value="">No versions available</option>';
      downloadBtn.disabled = true;
      return;
    }
    versionSelect.innerHTML = '';
    versions.forEach(v => {
      const primaryFile = v.files.find(f => f.primary) || v.files[0];
      if (!primaryFile) return;
      const opt = document.createElement('option');
      opt.value = v.id;
      const latestGameVersion = v.game_versions[v.game_versions.length - 1] || '';
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
    // Prefer a compatible version as the default selection when there is
    // one, so the dropdown doesn't default to an incompatible version just
    // because it happened to be newest — the user can still pick one of
    // the (Incompatible) options manually if they want to.
    const firstCompatible = Array.from(versionSelect.options).find(o => !o.dataset.incompatible);
    if (firstCompatible) versionSelect.value = firstCompatible.value;
    downloadBtn.disabled = versionSelect.options.length === 0;
  } catch (e) {
    versionSelect.innerHTML = '<option value="">Failed to load versions</option>';
    downloadBtn.disabled = true;
    if (retryBtn) retryBtn.classList.remove('hidden');
  }
}

async function downloadDiscoverSelection(hit, versionSelect, downloadBtn) {
  if (!settings) settings = await api.getSettings();
  const target = currentDiscoverTargetInstance();
  const directory = target ? (target.directory || settings.game_directory) : settings.game_directory;

  const opt = versionSelect.selectedOptions[0];
  if (!opt || !opt.dataset.fileUrl) {
    showToast('No version selected', 'error');
    return;
  }

  if (opt.dataset.incompatible) {
    const targetLabel = target ? (target.name || target.version_id) : 'the targeted instance';
    const proceed = confirm(`This version doesn't match ${targetLabel} and is marked (Incompatible). Download it anyway?`);
    if (!proceed) return;
  }

  downloadBtn.disabled = true;
  const originalText = downloadBtn.textContent;
  downloadBtn.textContent = 'Downloading…';
  const dlId = genDlId('discover-download');
  if (dlWidgetGeneric) dlWidgetGeneric.begin(dlId, 'Downloading…', hit.title);

  try {
    await trackedDiscoverDownload(directory, hit.project_type, opt.dataset.fileUrl, opt.dataset.fileName, dlId);
    showToast(`${hit.title} downloaded`, 'success');
    downloadBtn.textContent = 'Downloaded ✓';
    if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, true, `${hit.title} downloaded`);
    setTimeout(() => { downloadBtn.textContent = originalText; downloadBtn.disabled = false; }, 1500);
  } catch (e) {
    const cancelled = dlWidgetGeneric && dlWidgetGeneric.isCancelled(dlId);
    if (!cancelled) showToast(String(e), 'error');
    if (dlWidgetGeneric) dlWidgetGeneric.end(dlId, false);
    downloadBtn.textContent = originalText;
    downloadBtn.disabled = false;
  }
}

function initDiscover() {
  const queryInput = document.getElementById('discover-query');
  const searchBtn = document.getElementById('discover-search-btn');
  const refreshBtn = document.getElementById('discover-refresh-btn');
  const targetSelect = document.getElementById('discover-target-instance');
  const prevBtn = document.getElementById('discover-prev-page');
  const nextBtn = document.getElementById('discover-next-page');

  const runSearch = () => {
    discoverState.query = queryInput.value.trim();
    discoverState.page = 1;
    performDiscoverSearch();
  };

  searchBtn.addEventListener('click', runSearch);
  queryInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });
  refreshBtn.addEventListener('click', () => performDiscoverSearch());

  targetSelect.addEventListener('change', (event) => {
    const value = event.target.value;
    discoverState.page = 1;
    if (value && value !== selectedInstanceId) {
      selectInstance(value);
    } else {
      const inst = value ? getInstances().find(i => i.version_id === value) : null;
      applyInstanceFiltersToDiscover(inst);
      performDiscoverSearch();
    }
  });

  document.querySelectorAll('.discover-segment').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.discover-segment').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      discoverState.type = btn.dataset.type;
      discoverState.page = 1;
      // Category options differ per project type (e.g. resourcepacks don't
      // have Fabric/Forge-style categories), and Loader/Environment/
      // Resolution simply don't apply to the type being switched away
      // from, so clear all of it rather than carrying over filters that
      // no longer mean anything for what's now selected.
      discoverState.categories = [];
      discoverState.loader = 'any';
      discoverState.environment = 'any';
      discoverState.resolution = '';
      discoverCloseAllPanels();
      updateDiscoverFilterVisibility();
      updateDiscoverFilterButtonStates();
      renderDiscoverCategoryPanel(); // reload options for the new project type
      // Same auto-fill mods already get: re-apply the targeted instance's
      // Minecraft version to the Game Version filter for this type too.
      applyInstanceFiltersToDiscover(currentDiscoverTargetInstance());
      performDiscoverSearch();
    });
  });

  initDiscoverFilters();

  prevBtn.addEventListener('click', () => {
    if (discoverState.page > 1) {
      discoverState.page -= 1;
      performDiscoverSearch();
    }
  });
  nextBtn.addEventListener('click', () => {
    discoverState.page += 1;
    performDiscoverSearch();
  });

  // Reflect the restored view (grid/list) in the toggle buttons + grid class.
  document.querySelectorAll('.discover-view-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.view === discoverState.view);
  });
  const initialGrid = document.getElementById('discover-results');
  if (initialGrid) initialGrid.classList.toggle('view-list', discoverState.view === 'list');

  document.querySelectorAll('.discover-view-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.dataset.view === discoverState.view) return;
      document.querySelectorAll('.discover-view-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      discoverState.view = btn.dataset.view;
      const grid = document.getElementById('discover-results');
      if (grid) grid.classList.toggle('view-list', discoverState.view === 'list');
      saveDiscoverPrefs();
    });
  });

  initDiscoverPageSizeDropdown();
}

// ── Custom "cards per page" dropdown ─────────────────────────────────────
function initDiscoverPageSizeDropdown() {
  const dropdown = document.getElementById('discover-pagesize-dropdown');
  const btn = document.getElementById('discover-pagesize-btn');
  const panel = document.getElementById('discover-pagesize-panel');
  const label = document.getElementById('discover-pagesize-label');
  if (!dropdown || !btn || !panel || !label) return;

  const syncSelected = () => {
    label.textContent = `${discoverState.pageSize} / page`;
    panel.querySelectorAll('.discover-pagesize-option').forEach(opt => {
      opt.classList.toggle('selected', parseInt(opt.dataset.size, 10) === discoverState.pageSize);
    });
  };
  syncSelected();

  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    const willOpen = !panel.classList.contains('open');
    document.querySelectorAll('.discover-filter-panel').forEach(p => p.classList.remove('open'));
    document.querySelectorAll('.discover-filter-btn').forEach(b => b.classList.remove('active'));
    panel.classList.toggle('open', willOpen);
    btn.classList.toggle('active', willOpen);
  });

  panel.querySelectorAll('.discover-pagesize-option').forEach(opt => {
    opt.addEventListener('click', (e) => {
      e.stopPropagation();
      const size = parseInt(opt.dataset.size, 10) || DISCOVER_DEFAULT_PAGE_SIZE;
      if (size !== discoverState.pageSize) {
        discoverState.pageSize = size;
        discoverState.page = 1;
        syncSelected();
        saveDiscoverPrefs();
        performDiscoverSearch();
      }
      panel.classList.remove('open');
      btn.classList.remove('active');
    });
  });

  document.addEventListener('click', (e) => {
    if (!e.target.closest('#discover-pagesize-dropdown')) {
      panel.classList.remove('open');
      btn.classList.remove('active');
    }
  });
}

// ── Discover filter dropdowns (Game Version / Loader / Category /
// Environment / License / Advanced) ─────────────────────────────────────
const DISCOVER_LOADERS = [
  { value: 'any', label: 'Any loader' },
  { value: 'fabric', label: 'Fabric' },
  { value: 'forge', label: 'Forge' },
  { value: 'neoforge', label: 'NeoForge' },
  { value: 'quilt', label: 'Quilt' },
];

function discoverOpenPanel(name) {
  document.querySelectorAll('.discover-filter-panel').forEach(p => {
    p.classList.toggle('open', p.dataset.panel === name);
  });
  document.querySelectorAll('.discover-filter-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.filter === name);
  });
}

function discoverCloseAllPanels() {
  document.querySelectorAll('.discover-filter-panel').forEach(p => p.classList.remove('open'));
  document.querySelectorAll('.discover-filter-btn').forEach(b => b.classList.remove('active'));
}

function updateDiscoverFilterButtonStates() {
  const setCount = (filterName, hasValue, countText) => {
    const btn = document.querySelector(`.discover-filter-btn[data-filter="${filterName}"]`);
    if (!btn) return;
    btn.classList.toggle('has-value', hasValue);
    const badge = btn.querySelector('.discover-filter-count');
    if (badge) {
      if (countText) { badge.textContent = countText; badge.hidden = false; }
      else { badge.hidden = true; }
    }
  };
  setCount('version', !!discoverState.gameVersion, discoverState.gameVersion || '');
  const loaderLabelText = DISCOVER_LOADERS.find(l => l.value === discoverState.loader);
  setCount('loader', discoverState.loader !== 'any', discoverState.loader !== 'any' ? (loaderLabelText ? loaderLabelText.label : discoverState.loader) : '');
  setCount('category', discoverState.categories.length > 0, discoverState.categories.length ? String(discoverState.categories.length) : '');
  setCount('resolution', !!discoverState.resolution, discoverState.resolution || '');
  setCount('environment', discoverState.environment !== 'any', '');
  setCount('license', !!discoverState.license, '');
  setCount('advanced', discoverState.openSourceOnly, '');

  const anyActive = discoverState.gameVersion || discoverState.loader !== 'any' ||
    discoverState.categories.length > 0 || discoverState.environment !== 'any' ||
    discoverState.resolution || discoverState.license || discoverState.openSourceOnly;
  const resetBtn = document.getElementById('discover-filters-reset');
  if (resetBtn) resetBtn.hidden = !anyActive;
}

// Loader/Environment only make sense for mods (loaders load mods; a
// resourcepack has no client/server-required split the way a mod does),
// and Resolution only makes sense for resourcepacks (mods aren't shipped
// in texture resolutions) — show only the filter chips that apply to
// whichever project type is currently selected.
function updateDiscoverFilterVisibility() {
  document.querySelectorAll('.discover-filter[data-types]').forEach(el => {
    const types = el.dataset.types.split(',');
    el.hidden = !types.includes(discoverState.type);
  });
}

async function renderDiscoverVersionPanel() {
  const list = document.getElementById('discover-version-list');
  if (!list) return;
  if (!discoverTagCache.gameVersions) {
    list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Loading…</div>`;
    try {
      discoverTagCache.gameVersions = await api.discoverGetGameVersions();
    } catch (e) {
      list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Failed to load versions</div>`;
      return;
    }
  }
  const search = (document.getElementById('discover-version-search').value || '').trim().toLowerCase();
  const versions = discoverTagCache.gameVersions.filter(v => !search || v.version.toLowerCase().includes(search));
  const rows = [{ version: '', label: 'Any version' }, ...versions.map(v => ({ version: v.version, label: v.version }))];
  list.innerHTML = '';
  rows.forEach(r => {
    const btn = document.createElement('button');
    btn.className = 'discover-filter-option' + (discoverState.gameVersion === r.version ? ' selected' : '');
    btn.textContent = r.label;
    btn.addEventListener('click', () => {
      discoverState.gameVersion = r.version;
      discoverState.page = 1;
      updateDiscoverFilterButtonStates();
      discoverCloseAllPanels();
      performDiscoverSearch();
    });
    list.appendChild(btn);
  });
}

function renderDiscoverLoaderPanel() {
  const list = document.getElementById('discover-loader-list');
  if (!list) return;
  list.innerHTML = '';
  DISCOVER_LOADERS.forEach(l => {
    const btn = document.createElement('button');
    btn.className = 'discover-filter-option' + (discoverState.loader === l.value ? ' selected' : '');
    btn.textContent = l.label;
    btn.addEventListener('click', () => {
      discoverState.loader = l.value;
      discoverState.page = 1;
      updateDiscoverFilterButtonStates();
      discoverCloseAllPanels();
      performDiscoverSearch();
    });
    list.appendChild(btn);
  });
}

async function renderDiscoverCategoryPanel() {
  const list = document.getElementById('discover-category-list');
  if (!list) return;
  const type = discoverState.type;
  if (!discoverTagCache.categoriesByType[type]) {
    list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Loading…</div>`;
    try {
      discoverTagCache.categoriesByType[type] = await api.discoverGetCategories(type);
    } catch (e) {
      list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Failed to load categories</div>`;
      return;
    }
  }
  const cats = discoverTagCache.categoriesByType[type] || [];
  list.innerHTML = '';
  cats.forEach(c => {
    const label = document.createElement('label');
    label.className = 'discover-filter-option';
    const checked = discoverState.categories.includes(c.name);
    label.innerHTML = `<input type="checkbox" value="${discoverEscape(c.name)}" ${checked ? 'checked' : ''}/> <span>${discoverEscape(c.name)}</span>`;
    list.appendChild(label);
  });
}

async function renderDiscoverResolutionPanel() {
  const list = document.getElementById('discover-resolution-list');
  if (!list) return;
  if (!discoverTagCache.resolutions) {
    list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Loading…</div>`;
    try {
      discoverTagCache.resolutions = await api.discoverGetResolutions('resourcepack');
    } catch (e) {
      list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Failed to load resolutions</div>`;
      return;
    }
  }
  const rows = [{ name: '', label: 'Any resolution' }, ...discoverTagCache.resolutions.map(r => ({ name: r.name, label: r.name }))];
  list.innerHTML = '';
  rows.forEach(r => {
    const btn = document.createElement('button');
    btn.className = 'discover-filter-option' + (discoverState.resolution === r.name ? ' selected' : '');
    btn.textContent = r.label;
    btn.addEventListener('click', () => {
      discoverState.resolution = r.name;
      discoverState.page = 1;
      updateDiscoverFilterButtonStates();
      discoverCloseAllPanels();
      performDiscoverSearch();
    });
    list.appendChild(btn);
  });
}

function renderDiscoverEnvironmentPanel() {
  const list = document.getElementById('discover-environment-list');
  if (!list) return;
  const options = [
    { value: 'any', label: 'Any' },
    { value: 'client', label: 'Client-side' },
    { value: 'server', label: 'Server-side' },
  ];
  list.innerHTML = '';
  options.forEach(o => {
    const btn = document.createElement('button');
    btn.className = 'discover-filter-option' + (discoverState.environment === o.value ? ' selected' : '');
    btn.textContent = o.label;
    btn.addEventListener('click', () => {
      discoverState.environment = o.value;
      discoverState.page = 1;
      updateDiscoverFilterButtonStates();
      discoverCloseAllPanels();
      performDiscoverSearch();
    });
    list.appendChild(btn);
  });
}

async function renderDiscoverLicensePanel() {
  const list = document.getElementById('discover-license-list');
  if (!list) return;
  if (!discoverTagCache.licenses) {
    list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Loading…</div>`;
    try {
      discoverTagCache.licenses = await api.discoverGetLicenses();
    } catch (e) {
      list.innerHTML = `<div class="discover-filter-option" style="opacity:.6;cursor:default;">Failed to load licenses</div>`;
      return;
    }
  }
  const rows = [{ short: '', name: 'Any license' }, ...discoverTagCache.licenses];
  list.innerHTML = '';
  rows.forEach(l => {
    const btn = document.createElement('button');
    btn.className = 'discover-filter-option' + (discoverState.license === l.short ? ' selected' : '');
    btn.textContent = l.name;
    btn.addEventListener('click', () => {
      discoverState.license = l.short;
      discoverState.page = 1;
      updateDiscoverFilterButtonStates();
      discoverCloseAllPanels();
      performDiscoverSearch();
    });
    list.appendChild(btn);
  });
}

function initDiscoverFilters() {
  document.querySelectorAll('.discover-filter-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const name = btn.dataset.filter;
      const alreadyOpen = btn.classList.contains('active');
      discoverCloseAllPanels();
      if (alreadyOpen) return;
      discoverOpenPanel(name);
      if (name === 'version') renderDiscoverVersionPanel();
      if (name === 'loader') renderDiscoverLoaderPanel();
      if (name === 'category') renderDiscoverCategoryPanel();
      if (name === 'resolution') renderDiscoverResolutionPanel();
      if (name === 'environment') renderDiscoverEnvironmentPanel();
      if (name === 'license') renderDiscoverLicensePanel();
      // 'advanced' panel is static markup, nothing to render.
    });
  });

  document.addEventListener('click', (e) => {
    if (!e.target.closest('.discover-filter')) discoverCloseAllPanels();
  });

  const versionSearch = document.getElementById('discover-version-search');
  if (versionSearch) versionSearch.addEventListener('input', () => renderDiscoverVersionPanel());

  const catApply = document.getElementById('discover-category-apply');
  if (catApply) catApply.addEventListener('click', () => {
    const checked = Array.from(document.querySelectorAll('#discover-category-list input[type="checkbox"]:checked')).map(i => i.value);
    discoverState.categories = checked;
    discoverState.page = 1;
    updateDiscoverFilterButtonStates();
    discoverCloseAllPanels();
    performDiscoverSearch();
  });
  const catClear = document.getElementById('discover-category-clear');
  if (catClear) catClear.addEventListener('click', () => {
    document.querySelectorAll('#discover-category-list input[type="checkbox"]').forEach(i => { i.checked = false; });
  });

  const advOpenSource = document.getElementById('discover-adv-opensource');
  if (advOpenSource) advOpenSource.checked = discoverState.openSourceOnly;
  const advApply = document.getElementById('discover-adv-apply');
  if (advApply) advApply.addEventListener('click', () => {
    discoverState.openSourceOnly = !!document.getElementById('discover-adv-opensource').checked;
    discoverState.page = 1;
    updateDiscoverFilterButtonStates();
    discoverCloseAllPanels();
    performDiscoverSearch();
  });
  const advClear = document.getElementById('discover-adv-clear');
  if (advClear) advClear.addEventListener('click', () => {
    document.getElementById('discover-adv-opensource').checked = false;
  });

  const resetAllBtn = document.getElementById('discover-filters-reset');
  if (resetAllBtn) resetAllBtn.addEventListener('click', () => {
    discoverState.gameVersion = '';
    discoverState.loader = 'any';
    discoverState.categories = [];
    discoverState.resolution = '';
    discoverState.environment = 'any';
    discoverState.license = '';
    discoverState.openSourceOnly = false;
    discoverState.page = 1;
    updateDiscoverFilterButtonStates();
    discoverCloseAllPanels();
    performDiscoverSearch();
  });

  updateDiscoverFilterVisibility();
  updateDiscoverFilterButtonStates();
}


// ══════════════════════════════════════════════════════════════════
// SETTINGS
// ══════════════════════════════════════════════════════════════════

// Color presets applied when the user picks Dark/Light (or when "System"
// resolves to one of them). These only fill in the fields the appearance
// UI doesn't expose its own picker for yet — bg/panel/text/log/notification/
// header colors — so a real per-field picker added later would simply take
// over via the `settings.x || preset.x` fallback in applyThemeFromSettings().
const THEME_PRESETS = {
  dark: {
    bg_color: '#0a0a0f',
    panel_bg_color: '#13131a',
    text_color: '#e2e2ea',
    log_bg_color: '#060608',
    notification_bg_color: '#13131a',
    header_bg_color: '#111116',
  },
  light: {
    bg_color: '#f3f3f6',
    panel_bg_color: '#eef0f3',
    text_color: '#1c1c22',
    log_bg_color: '#eef0f3',
    notification_bg_color: '#ffffff',
    header_bg_color: '#ffffff',
  },
};

const systemThemeQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;

// Each theme keeps its own independent accent color, stored in separate
// settings fields (accent_color_dark / accent_color_light). Switching theme
// swaps which one is active — it does not overwrite the other.
const ACCENT_THEME_DEFAULTS = { light: '#1A1A1A', dark: '#B7B7B7' };
const KNOWN_LEGACY_DEFAULT_ACCENTS = new Set(['#10b981', '#1a1a1a', '#b7b7b7']);

// One-time migration from the old single `accent_color` field to the new
// per-theme fields, run whenever settings are loaded. Safe to call
// repeatedly — it's a no-op once both fields exist.
function ensureAccentFields() {
  if (!settings) return;
  if (settings.accent_color_dark === undefined) {
    const legacy = settings.accent_color;
    const hadCustomAccent = legacy && !KNOWN_LEGACY_DEFAULT_ACCENTS.has(legacy.toLowerCase());
    settings.accent_color_dark = hadCustomAccent ? legacy : ACCENT_THEME_DEFAULTS.dark;
  }
  if (settings.accent_color_light === undefined) {
    settings.accent_color_light = ACCENT_THEME_DEFAULTS.light;
  }
}

// The accent field for whichever theme is currently in effect.
function currentAccentColor() {
  if (!settings) return ACCENT_THEME_DEFAULTS.dark;
  const theme = resolveEffectiveTheme();
  return (theme === 'light' ? settings.accent_color_light : settings.accent_color_dark)
    || ACCENT_THEME_DEFAULTS[theme];
}

function resolveEffectiveTheme() {
  const mode = (settings && settings.theme_mode) || 'system';
  if (mode === 'light') return 'light';
  if (mode === 'dark') return 'dark';
  return systemThemeQuery && systemThemeQuery.matches ? 'light' : 'dark';
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
  // Theme Mode
  const themeModeSel = document.getElementById('setting-theme-mode');
  if (themeModeSel) themeModeSel.value = settings.theme_mode || 'system';

  ensureAccentFields();

  // Accent Color — one picker per theme
  const accentDarkInp = document.getElementById('setting-accent-color-dark');
  if (accentDarkInp) accentDarkInp.value = settings.accent_color_dark || ACCENT_THEME_DEFAULTS.dark;
  const accentLightInp = document.getElementById('setting-accent-color-light');
  if (accentLightInp) accentLightInp.value = settings.accent_color_light || ACCENT_THEME_DEFAULTS.light;

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
  document.getElementById('setting-minimize-on-launch').checked = !!settings.minimize_on_launch;
  document.getElementById('setting-on-game-close').value = settings.on_game_close || 'show';
  document.getElementById('setting-system-tray').checked = settings.enable_system_tray !== false;
  document.getElementById('setting-on-launcher-close').value = settings.on_launcher_close || 'tray';
  document.getElementById('setting-always-hide-to-tray').checked = !!settings.always_hide_to_tray;
  updateWindowBehaviorRowVisibility();
  document.getElementById('setting-mod-updates-startup').checked = settings.check_mod_updates_on_startup !== false;
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
  document.getElementById('setting-hide-username').checked = !!settings.hide_username;
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

  applyThemeFromSettings();
  applyUsernamePrivacy();

  const settingsTab = document.getElementById('tab-settings');
  enableCardCulling(settingsTab, '.glass-card');
}

// ── Apply all appearance settings to CSS custom properties & DOM overlays ──
function applyThemeFromSettings() {
  if (!settings) return;
  const root = document.documentElement;

  // Smooth scrolling — a plain class toggle so it's instant and doesn't
  // need any other machinery; the CSS rule only kicks in while the class
  // is present (see .smooth-scroll in main.css).
  root.classList.toggle('smooth-scroll', settings.smooth_scrolling !== false);

  const effectiveTheme = resolveEffectiveTheme();
  const preset = THEME_PRESETS[effectiveTheme];
  root.setAttribute('data-theme', effectiveTheme);

  // Colors — these fields have no picker UI anywhere in the app, so the
  // backend always serializes them at their (dark-theme) defaults. Using
  // `settings.X || preset.X` meant `settings.X` was never falsy and the
  // resolved theme's preset never actually won, so panels/text/backgrounds
  // stayed dark-themed even while on the light theme (most visible in the
  // Setup Wizard, which — unlike .glass-card/.instance-card/etc — has no
  // per-theme override of its own to compensate). Always derive from the
  // resolved preset until per-field pickers exist.
  ensureAccentFields();
  root.style.setProperty('--accent', currentAccentColor());
  root.style.setProperty('--bg', preset.bg_color);
  root.style.setProperty('--bg-darker', darkenColor(preset.bg_color, effectiveTheme === 'light' ? 0.08 : 0.4));

  // Panel background with transparency option. The light theme needs a
  // higher floor than the dark theme — at the same low alpha the animated
  // background shows through a white/gray panel far more visibly than it
  // does through a dark one, which is what was reading as "dark" instead of
  // a clean soft gray card.
  const panelAlpha = effectiveTheme === 'light'
    ? (settings.enable_transparency ? 0.82 : 0.97)
    : (settings.enable_transparency ? 0.45 : 0.95);
  root.style.setProperty('--panel', hexToRgba(preset.panel_bg_color, panelAlpha));
  root.style.setProperty('--text', preset.text_color);
  root.style.setProperty('--text-muted', hexToRgba(preset.text_color, 0.55));

  const headerAlpha = effectiveTheme === 'light'
    ? (settings.enable_transparency ? 0.82 : 0.97)
    : (settings.enable_transparency ? 0.45 : 0.95);
  root.style.setProperty('--header-bg', hexToRgba(preset.header_bg_color, headerAlpha));
  root.style.setProperty('--log-bg', preset.log_bg_color);
  const notifHex = preset.notification_bg_color;
  root.style.setProperty('--notif-bg', notifHex);
  root.style.setProperty('--notif-bg-rgb', hexToRgbTriplet(notifHex));

  // Near-opaque panel background for flyouts that need to stay legible over
  // content regardless of the transparency setting (dropdowns, modals,
  // popovers) — follows the same panel color the rest of the theme uses.
  root.style.setProperty('--panel-solid', hexToRgba(preset.panel_bg_color, 0.97));

  // Accent derived
  const accent = currentAccentColor();
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
  // Appearance: Theme
  const themeModeSel = document.getElementById('setting-theme-mode');
  if (themeModeSel) settings.theme_mode = themeModeSel.value;

  // Appearance: Colors — one accent per theme
  const accentDarkEl = document.getElementById('setting-accent-color-dark');
  if (accentDarkEl) settings.accent_color_dark = accentDarkEl.value;
  const accentLightEl = document.getElementById('setting-accent-color-light');
  if (accentLightEl) settings.accent_color_light = accentLightEl.value;
  settings.notification_style = document.getElementById('setting-notif-style').value;

  // Appearance: Background & Animation
  settings.background_style = document.getElementById('setting-bg-style').value;
  settings.background_animation_style = document.getElementById('setting-bg-anim-style').value;
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
  settings.minimize_on_launch = document.getElementById('setting-minimize-on-launch').checked;
  settings.on_game_close = document.getElementById('setting-on-game-close').value;
  settings.enable_system_tray = document.getElementById('setting-system-tray').checked;
  settings.on_launcher_close = document.getElementById('setting-on-launcher-close').value;
  settings.always_hide_to_tray = document.getElementById('setting-always-hide-to-tray').checked;
  settings.check_mod_updates_on_startup = document.getElementById('setting-mod-updates-startup').checked;
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
  settings.hide_username = document.getElementById('setting-hide-username').checked;
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
    // A previously-selected custom path that isn't in the freshly
    // detected list (e.g. it's outside the usual scan locations) — keep
    // it selectable instead of silently reverting to Smart Detection.
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
    'setting-theme-mode',
    'setting-bg-style', 'setting-bg-anim-style', 'setting-notif-style',
    'setting-bg-anim-speed', 'setting-bg-anim-intensity', 'setting-bg-anim-fps',
    'setting-bg-anim-enable', 'setting-transparency',
    'setting-use-bg-image', 'setting-bg-image-fit',
    'setting-bg-image-dim', 'setting-bg-image-brightness', 'setting-bg-image-blur',
    'setting-bg-image-tint', 'setting-bg-image-vignette',
    'setting-font-family',
    'setting-close-on-launch', 'setting-minimize-on-launch',
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
    'setting-hide-username', 'setting-clear-session-on-exit',
    'setting-redact-tokens', 'setting-redact-paths', 'setting-hide-launch-command',
    'setting-debug-mode',
    'setting-crash-analysis',
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

  // Text, number, color, and range inputs use debounced save
  const debouncedIds = [
    'setting-accent-color-dark', 'setting-accent-color-light',
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
      } finally {
        refreshJavaBtn.disabled = false;
        refreshJavaBtn.textContent = prevLabel;
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
        dlWidgetGeneric.begin(dlId, 'Smart Java Detection', p.message, { determinate: true });
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

  // Instant preview when switching Dark/Light/System — no need to wait for
  // the debounced save round-trip to see the change.
  const themeModeEl = document.getElementById('setting-theme-mode');
  if (themeModeEl) {
    themeModeEl.addEventListener('change', () => {
      collectSettingsFromUI();
      applyThemeFromSettings();
    });
  }

  // If the user's on "System", follow the OS live — no restart needed when
  // they flip their OS between light and dark. The active theme's accent
  // (and everything derived from it) swaps automatically since
  // applyThemeFromSettings() re-resolves the effective theme each time.
  if (systemThemeQuery) {
    systemThemeQuery.addEventListener('change', () => {
      applyThemeFromSettings();
    });
  }

  // Live-preview accent color as you drag either picker
  const accentDarkEl = document.getElementById('setting-accent-color-dark');
  if (accentDarkEl) {
    accentDarkEl.addEventListener('input', () => {
      collectSettingsFromUI();
      applyThemeFromSettings();
    });
  }
  const accentLightEl = document.getElementById('setting-accent-color-light');
  if (accentLightEl) {
    accentLightEl.addEventListener('input', () => {
      collectSettingsFromUI();
      applyThemeFromSettings();
    });
  }

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
          theme_mode: 'system',
          accent_color_dark: ACCENT_THEME_DEFAULTS.dark,
          accent_color_light: ACCENT_THEME_DEFAULTS.light,
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
          close_after_launch: false,
          minimize_on_launch: false,
          on_game_close: 'show',
          enable_system_tray: true,
          on_launcher_close: 'tray',
          always_hide_to_tray: false,
          check_mod_updates_on_startup: true,
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
  document.querySelectorAll('.ri-btn.is-open').forEach(b => b.classList.remove('is-open'));
  document.querySelectorAll('.ri-dropdown').forEach(d => {
    d.classList.add('ri-dropdown-closing');
    d.addEventListener('animationend', () => d.remove(), { once: true });
  });
}

function openRiDropdown(anchorBtn, inst) {
  closeRiDropdown();
  openRiDropdownId = inst.version_id;
  anchorBtn.classList.add('is-open');

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
    closeRiDropdown();
  });
  dropdown.querySelector('.ri-dropdown-kill').addEventListener('click', async (e) => {
    e.stopPropagation();
    closeRiDropdown();
    try {
      await api.killInstance(inst.version_id);
      showToast(`Killed ${inst.name || inst.version_id}`, 'success');
    } catch (err) {
      showToast('Failed to kill instance: ' + err, 'error');
    }
    refreshRunningInstances();
  });

  anchorBtn.appendChild(dropdown);
}

function renderRunningInstancesPanel() {
  const container = document.getElementById('ri-buttons');
  if (!container) return;

  const running = runningInstancesCache.filter(i => i.running);
  container.innerHTML = '';

  const runningIds = new Set(running.map(i => i.version_id));

  // Remove buttons for instances that stopped, animating them out first.
  Array.from(container.children).forEach(child => {
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
  // changes on disk — refresh the tracked instance list so the Play Time
  // row picks that up instead of only reflecting whatever was cached at
  // launch time.
  try {
    await refreshInstances();
  } catch (e) {
    console.error('Failed to refresh instances after running-instances change', e);
  }
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
    if (openRiDropdownId && !e.target.closest('.ri-btn')) {
      closeRiDropdown();
    }
  });

  // Live updates: the backend fires this whenever an instance starts or
  // stops, so the buttons/Play button stay in sync without polling.
  api.onRunningInstancesChanged(() => refreshRunningInstances());

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
    this.ctx = this.canvas.getContext('2d', { alpha: true, desynchronized: true });
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
      } else {
        this.requestRedraw();
      }
    });
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
    this.canvas.width = window.innerWidth;
    this.canvas.height = window.innerHeight;
  },

  createParticles() {
    this.particles = [];
    for (let i = 0; i < 50; i++) {
      this.particles.push({
        x: Math.random() * window.innerWidth,
        y: Math.random() * window.innerHeight,
        baseX: Math.random() * window.innerWidth,
        size: 1.5 + Math.random() * 3,
        alpha: Math.random() * 0.5,
        alphaDir: (Math.random() * 0.01 + 0.003) * (Math.random() > 0.5 ? 1 : -1),
        vy: 0.15 + Math.random() * 0.4,
        driftX: (Math.random() - 0.5) * 0.3,
        swayPhase: Math.random() * Math.PI * 2,
        swaySpeed: 0.01 + Math.random() * 0.02,
        swayAmp: 8 + Math.random() * 18,
      });
    }
  },

  createOrbs() {
    this.orbs = [];
    for (let i = 0; i < 5; i++) {
      this.orbs.push({
        xFrac: Math.random(),
        yFrac: Math.random(),
        radius: 100 + Math.random() * 160,
        phase: Math.random() * Math.PI * 2,
        speed: 0.2 + Math.random() * 0.3,
      });
    }
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
    const isLight = document.documentElement.getAttribute('data-theme') === 'light';
    // "Nothing" is a flat, single-color background — no gradient — soft
    // light gray in light mode / dark gray in dark mode. It only applies
    // when there's no custom image background (that already has its own
    // flat base and takes priority).
    const isNothing = bgStyle === 'Nothing' && !s.use_background_image;
    // Same shapes read as much fainter against a light background, so give
    // every accent-colored overlay (glows/waves/orbs/particles) more alpha
    // to punch through instead of nearly disappearing.
    const baseBoost = isLight ? 2.2 : 1;
    const imageBgBoost = s.use_background_image ? 3.2 : 1;
    const intensity = s.background_animation_intensity ?? 1.0;
    const aBoost = baseBoost * imageBgBoost * intensity;

    // ── Clear Canvas ──
    ctx.clearRect(0, 0, W, H);

    if (!s.use_background_image) {
      // The base gradient + glow(s) only actually change when size, theme,
      // accent color, or background style change — not every frame. They
      // were being rebuilt from scratch (1 linear + up to 2 radial
      // gradients, each requiring color-stop math and a full-canvas fill)
      // on every single animation frame even though the result was
      // pixel-identical to the previous one nearly all the time. Now that
      // static layer is rendered once into an offscreen canvas and just
      // blitted with drawImage() — a plain pixel copy — until something it
      // actually depends on changes. Same pixels on screen, far less canvas
      // work per frame while an animation (Waves/Orbs/Fireflies/Particles)
      // is running.
      const staticKey = `${W}x${H}|${r},${g},${b}|${isLight}|${bgStyle}|${aBoost}|${isNothing}`;
      if (this._staticKey !== staticKey) {
        this._staticKey = staticKey;
        if (!this._staticCanvas) this._staticCanvas = document.createElement('canvas');
        const sc = this._staticCanvas;
        if (sc.width !== W || sc.height !== H) { sc.width = W; sc.height = H; }
        const sctx = sc.getContext('2d');
        sctx.clearRect(0, 0, W, H);

        if (isNothing) {
          // Flat solid fill, no gradient at all.
          sctx.fillStyle = isLight ? '#e9eaed' : '#141416';
          sctx.fillRect(0, 0, W, H);
        } else {
          // ── Base gradient ──
          let grad;
          if (isLight) {
            // Diagonal, three-stop gradient with real contrast: a noticeably
            // tinted accent corner, a near-white middle, and a cool gray
            // corner — the two-stop pastel version was too close in
            // lightness to read as a gradient at all.
            grad = sctx.createLinearGradient(0, 0, W, H);
            const mix = (amt) => [
              Math.round(r + (255 - r) * amt),
              Math.round(g + (255 - g) * amt),
              Math.round(b + (255 - b) * amt),
            ];
            const [ar, ag, ab] = mix(0.22); // strong accent corner, not washed out
            const [mr, mg, mb] = mix(0.62); // tinted midpoint — no flat white
            grad.addColorStop(0, `rgb(${ar}, ${ag}, ${ab})`);
            grad.addColorStop(0.55, `rgb(${mr}, ${mg}, ${mb})`);
            grad.addColorStop(1, '#c7cedb');
          } else {
            grad = sctx.createLinearGradient(0, 0, 0, H);
            grad.addColorStop(0, '#0a0a0f');
            grad.addColorStop(1, '#060608');
          }
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

      } else if (animStyle === 'Orbs') {
        this.orbs.forEach(orb => {
          const ox = W * orb.xFrac + Math.sin(this.phase * orb.speed + orb.phase) * 60;
          const oy = H * orb.yFrac + Math.cos(this.phase * orb.speed * 0.7 + orb.phase) * 40;
          const og = ctx.createRadialGradient(ox, oy, 0, ox, oy, orb.radius);
          og.addColorStop(0, `rgba(${r},${g},${b},${Math.min(1, 0.13 * aBoost)})`);
          og.addColorStop(1, `rgba(${r},${g},${b},0)`);
          ctx.fillStyle = og;
          ctx.fillRect(0, 0, W, H);
        });

      } else if (animStyle === 'Fireflies') {
        this.particles.forEach(p => {
          p.swayPhase += p.swaySpeed * speed;
          p.baseX += p.driftX * 0.2 * speed;
          p.x = p.baseX + Math.sin(p.swayPhase) * p.swayAmp;
          p.y += Math.cos(p.swayPhase * 0.7) * 0.3 * speed;
          p.alpha += p.alphaDir * speed;
          if (p.alpha > 0.85 || p.alpha < 0.05) p.alphaDir = -p.alphaDir;
          if (p.x < -20 || p.x > W + 20 || p.y < -20 || p.y > H + 20) {
            p.baseX = p.x = Math.random() * W;
            p.y = Math.random() * H;
            p.alpha = 0.1;
          }
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
          ctx.fillStyle = `rgba(${r},${g},${b},${Math.min(1, Math.max(0, p.alpha) * aBoost)})`;
          ctx.fill();
        });

      } else {
        // Particles (float up)
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
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
          ctx.fillStyle = `rgba(${r},${g},${b},${Math.min(1, Math.max(0, p.alpha) * aBoost)})`;
          ctx.fill();
        });
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
}

// ══════════════════════════════════════════════════════════════════
// FIRST-TIME SETUP WIZARD
// ══════════════════════════════════════════════════════════════════
let currentSetupStep = 1;

function initSetupWizard() {
  const prevBtn = document.getElementById('btn-setup-prev');
  const nextBtn = document.getElementById('btn-setup-next');
  const skipStepBtn = document.getElementById('btn-setup-skip-step');
  const skipAllBtn = document.getElementById('btn-setup-skip-all');

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

  // Live listeners for Step 1 fields
  const liveThemeInputs = [
    'setup-theme-mode',
    'setup-notif-style',
    'setup-accent-dark',
    'setup-accent-light',
    'setup-bg-style',
    'setup-bg-anim-style'
  ];

  liveThemeInputs.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      const handler = () => {
        if (!settings) settings = {};
        settings.theme_mode = document.getElementById('setup-theme-mode').value;
        const newNotif = document.getElementById('setup-notif-style').value;
        const notifChanged = settings.notification_style !== newNotif;
        settings.notification_style = newNotif;
        settings.accent_color_dark = document.getElementById('setup-accent-dark').value;
        settings.accent_color_light = document.getElementById('setup-accent-light').value;
        settings.background_style = document.getElementById('setup-bg-style').value;
        settings.background_animation_style = document.getElementById('setup-bg-anim-style').value;

        applyThemeFromSettings();
        populateSettingsUI();
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
    const themeSel = document.getElementById('setup-theme-mode');
    if (themeSel) themeSel.value = settings.theme_mode || 'system';
    const notifSel = document.getElementById('setup-notif-style');
    if (notifSel) notifSel.value = settings.notification_style || 'Minimal Outline';
    const darkCol = document.getElementById('setup-accent-dark');
    if (darkCol) darkCol.value = settings.accent_color_dark || ACCENT_THEME_DEFAULTS.dark;
    const lightCol = document.getElementById('setup-accent-light');
    if (lightCol) lightCol.value = settings.accent_color_light || ACCENT_THEME_DEFAULTS.light;
    const bgStyleSel = document.getElementById('setup-bg-style');
    if (bgStyleSel) bgStyleSel.value = settings.background_style || 'Default';
    const bgAnimSel = document.getElementById('setup-bg-anim-style');
    if (bgAnimSel) bgAnimSel.value = settings.background_animation_style || 'Waves';
  }

  // Check existing accounts for step 2
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
    settings.theme_mode = document.getElementById('setup-theme-mode').value;
    settings.notification_style = document.getElementById('setup-notif-style').value;
    settings.accent_color_dark = document.getElementById('setup-accent-dark').value;
    settings.accent_color_light = document.getElementById('setup-accent-light').value;
    settings.background_style = document.getElementById('setup-bg-style').value;
    settings.background_animation_style = document.getElementById('setup-bg-anim-style').value;

    populateSettingsUI();
    applyThemeFromSettings();
    await saveSettingsNow();
    return true;
  }

  if (step === 2) {
    const usernameInp = document.getElementById('setup-username');
    const username = usernameInp ? usernameInp.value.trim() : '';

    // Check if an account already exists
    let hasExistingAccount = false;
    try {
      const accounts = await api.getAccounts();
      if (accounts && accounts.length > 0) hasExistingAccount = true;
    } catch (e) {}

    if (!username && !hasExistingAccount) {
      showToast('Please enter a username to create your first account', 'warning');
      return false;
    }

    if (username) {
      if (username.length > 16) {
        showToast('Username must be 16 characters or less', 'warning');
        return false;
      }
      try {
        await api.addOfflineAccount(username);
        await refreshAccountUI();
        showToast(`Account "${username}" created!`, 'success');
      } catch (err) {
        showToast('Failed to create account: ' + err, 'error');
        return false;
      }
    }
    return true;
  }

  // Step 3 is just the closing tour/finish screen now — nothing to submit,
  // instances are created afterward from the normal Instances tab.
  return true;
}

async function finishSetupWizard() {
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

// ══════════════════════════════════════════════════════════════════
// BOOT
// ══════════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', async () => {
  initTabs();
  initAccountDropdown();
  initDownloadWidget();
  initInstanceActions();
  initCrashTroubleshootWindow();
  initInstanceTroubleshootWindow();
  initMods();
  initDiscover();
  initSettings();
  initMusicSettings();
  initWindowBehaviorSettings();
  initRunningInstancesWidget();
  initApplyPresetOverlayEvents();
  initExportModsOverlayEvents();
  initImportModsOverlayEvents();
  initSetupWizard();

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
  const progressBar = document.getElementById('update-progress-bar');
  const progressPct = document.getElementById('update-progress-pct');
  const progressLabel = document.getElementById('update-progress-label');
  const closeBtn = document.getElementById('btn-close-update-overlay');
  const noBtn = document.getElementById('btn-update-no');
  const yesBtn = document.getElementById('btn-update-yes');

  const close = () => overlay.classList.add('hidden');
  closeBtn.addEventListener('click', close);
  noBtn.addEventListener('click', close);

  let pendingUpdate = null;
  let unlistenProgress = null;

  const showUpdatePrompt = (update) => {
    pendingUpdate = update;
    document.getElementById('update-version-text').textContent = `v${update.version}`;
    document.getElementById('update-size-text').textContent =
      update.size_mb ? `${update.size_mb.toFixed(1)} MB` : 'size unknown';
    promptView.classList.remove('hidden');
    progressView.classList.add('hidden');
    closeBtn.style.visibility = 'visible';
    overlay.classList.remove('hidden');
  };
  window.__ZL_showUpdatePrompt = showUpdatePrompt;

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

      const downloadedPath = await api.downloadUpdate(pendingUpdate.url);

      if (unlistenProgress) { unlistenProgress(); unlistenProgress = null; }
      progressLabel.textContent = 'Installing update…';
      progressBar.style.width = '100%';
      progressPct.textContent = '100%';

      // The app exits and relaunches as part of this call on success — if
      // we get a response back at all here, something went wrong.
      await api.installUpdate(downloadedPath);
    } catch (e) {
      console.error('Update failed:', e);
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

  document.getElementById('btn-crash-relaunch').addEventListener('click', async () => {
    const versionId = overlay.dataset.versionId;
    close();
    if (versionId && api.launchGame) {
      try {
        await api.launchGame(versionId);
        showToast('Relaunching instance…', 'info');
      } catch (e) {
        showToast('Failed to relaunch: ' + e, 'error');
      }
    }
  });

  api.onGameCrashed((event) => {
    // Experimental toggle — the analysis is heuristic and can misdiagnose,
    // so let people turn the popup off entirely rather than see a possibly
    // wrong "likely cause" every time a game closes unexpectedly.
    if (settings && settings.enable_crash_analysis !== true) return;
    showCrashDialog(event.payload);
  });
}

function showCrashDialog(report) {
  if (!report) return;
  const overlay = document.getElementById('crash-overlay');
  if (!overlay) return;

  overlay.dataset.versionId = report.version_id || '';
  overlay.dataset.instanceName = report.instance_name || '';

  document.getElementById('crash-titlebar-text').textContent =
    (report.instance_name || 'Instance') + ' — Not Responding';
  document.getElementById('crash-heading').textContent = report.title || 'Instance has stopped working';
  document.getElementById('crash-subheading').textContent =
    `${report.instance_name || 'The instance'} closed unexpectedly. Zero Launcher looked through its log and found a likely cause below.`;
  document.getElementById('crash-signature').textContent = report.signature || '(no log output captured)';

  const fixesLabel = document.getElementById('crash-fixes-label');
  const fixesEl = document.getElementById('crash-fixes');
  fixesEl.innerHTML = '';

  const fixes = Array.isArray(report.fixes) ? report.fixes : [];
  fixesLabel.style.display = fixes.length ? '' : 'none';

  // Fixes that act on a specific mod (disable/remove) get grouped into one
  // compact list instead of a full card each once there's more than one —
  // otherwise 5 incompatible mods would mean 5 near-identical cards.
  const groupable = fixes.filter(f => f.kind === 'disable_mod' || f.kind === 'delete_mod' || f.kind === 'install_mod');
  const rest = fixes.filter(f => f.kind !== 'disable_mod' && f.kind !== 'delete_mod' && f.kind !== 'install_mod');

  // Keep whichever kind of fix appears first in the backend's list on top —
  // e.g. "Update Minecraft" (an info card) is meant to read as the primary
  // suggestion, with "disable all incompatible mods" as the fallback below.
  const groupableCard = groupable.length > 1
    ? buildGroupedModFixCard(report.category, groupable)
    : null;
  const restCards = rest.map(fix => buildSingleFixCard(fix));
  const singleGroupCards = groupable.length <= 1 ? groupable.map(fix => buildSingleFixCard(fix)) : [];

  const isGroupableKind = k => k === 'disable_mod' || k === 'delete_mod' || k === 'install_mod';
  if (fixes.length && !isGroupableKind(fixes[0].kind)) {
    restCards.forEach(c => fixesEl.appendChild(c));
    singleGroupCards.forEach(c => fixesEl.appendChild(c));
    if (groupableCard) fixesEl.appendChild(groupableCard);
  } else {
    if (groupableCard) fixesEl.appendChild(groupableCard);
    singleGroupCards.forEach(c => fixesEl.appendChild(c));
    restCards.forEach(c => fixesEl.appendChild(c));
  }

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