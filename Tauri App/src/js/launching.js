import iconVanilla  from '../assets/loaders/vanilla.png';
import iconFabric   from '../assets/loaders/fabric.png';
import iconForge    from '../assets/loaders/forge.png';
import iconNeoforge from '../assets/loaders/neoforge.png';
import iconQuilt    from '../assets/loaders/quilt.png';

const LOADER_ICONS = {
  vanilla:  iconVanilla,
  fabric:   iconFabric,
  forge:    iconForge,
  neoforge: iconNeoforge,
  quilt:    iconQuilt,
};

function getLoaderIcon(loader) {
  const key = (loader || 'vanilla').toLowerCase();
  return LOADER_ICONS[key] || LOADER_ICONS.vanilla;
}

document.addEventListener('DOMContentLoaded', async () => {
  const invoke    = window.__TAURI__?.core?.invoke;
  const listen    = window.__TAURI__?.event?.listen;
  const currentWin = window.__TAURI__?.webviewWindow?.getCurrentWebviewWindow
    ? window.__TAURI__.webviewWindow.getCurrentWebviewWindow()
    : null;

  const params    = new URLSearchParams(window.location.search);
  const versionId = params.get('instance') || '';
  const name      = params.get('name')     || versionId;
  const loader    = params.get('loader')   || 'vanilla';
  const mc        = params.get('mc')       || '';

  // DOM refs
  const titleEl  = document.getElementById('mini-title');
  const subEl    = document.getElementById('mini-sub');
  const statusEl = document.getElementById('mini-status');
  const iconImg  = document.getElementById('mini-icon-img');
  const closeBtn = document.getElementById('btn-mini-close');
  const logArea  = document.getElementById('log-area');

  // Initial content
  if (titleEl) titleEl.textContent = name;
  if (subEl)   subEl.textContent = mc ? `Minecraft ${mc}` : 'Launching…';
  if (iconImg) { iconImg.src = getLoaderIcon(loader); iconImg.alt = loader; }

  // ── helpers ──────────────────────────────────────────────────────

  let lastStatus   = '';
  let currentEntry = null; // the "current" log entry element (bright)

  /** Push a new step into the log and update the top status bar. */
  const setStatus = (text, state = 'loading') => {
    if (!text || text === lastStatus) return;
    lastStatus = text;

    // Status bar
    if (statusEl) {
      statusEl.textContent = text;
      statusEl.className = 'status-text' + (state === 'error' ? ' error' : '');
    }

    // Log: dim previous current entry, add new one
    if (logArea) {
      if (currentEntry) currentEntry.className = 'log-entry ok';
      const el = document.createElement('div');
      el.className = state === 'error' ? 'log-entry fail' : 'log-entry current';
      el.textContent = text;
      logArea.appendChild(el);
      if (state !== 'error') currentEntry = el;
      logArea.scrollTop = logArea.scrollHeight;
    }
  };

  /**
   * Update (or create) a pinned "download" entry that shows per-file progress.
   * Uses a dedicated element identified by a data attribute so we overwrite
   * it instead of spamming the log with every byte event.
   */
  let downloadEntry = null;
  const setDownloadProgress = (file, pct) => {
    if (!logArea) return;
    const shortFile = file ? file.split('/').pop().split('\\').pop() : '';
    const pctStr = pct != null ? ` (${Math.round(pct)}%)` : '';
    const text = shortFile ? `Downloading ${shortFile}${pctStr}` : `Downloading…${pctStr}`;

    if (!downloadEntry) {
      downloadEntry = document.createElement('div');
      downloadEntry.className = 'log-entry current';
      if (currentEntry) currentEntry.className = 'log-entry ok';
      currentEntry = downloadEntry;
      logArea.appendChild(downloadEntry);
    }
    downloadEntry.textContent = text;
    logArea.scrollTop = logArea.scrollHeight;
  };

  const clearDownloadEntry = () => {
    downloadEntry = null;
  };

  // Close helper
  const closeSelf = () => {
    try {
      if (currentWin?.close) currentWin.close().catch(() => {});
      else window.close();
    } catch (_) { window.close(); }
  };

  closeBtn?.addEventListener('click', closeSelf);

  // Game started
  let hasStarted = false;
  const markGameStarted = () => {
    if (hasStarted) return;
    hasStarted = true;
    clearDownloadEntry();
    setStatus('Game started, please wait a bit (1–30 secs)', 'done');
    if (subEl) subEl.textContent = 'Running';
    setTimeout(closeSelf, 2000);
  };

  setStatus('Preparing launch');

  // ── events ───────────────────────────────────────────────────────
  if (typeof listen !== 'function') return;

  // Verify-phase status (stage names + "installing" notice)
  listen('launch-verify-status', (evt) => {
    if (!evt?.payload) return;
    const { version_id, message, active } = evt.payload;
    if (version_id && version_id !== versionId) return;
    if (active && message) setStatus(message);
  });

  // Per-step status (Java, building command, spawning, etc.)
  listen('launch-status-update', (evt) => {
    if (!evt?.payload) return;
    const { version_id, message } = evt.payload;
    if (version_id && version_id !== versionId) return;
    if (message) {
      clearDownloadEntry();
      setStatus(message);
    }
  });

  // Per-file download progress from the verify/download pass
  listen('download-progress', (evt) => {
    if (!evt?.payload) return;
    const { id, status, current_file, percent } = evt.payload;
    // Only handle this instance's own verify pass
    if (id && id !== `launch-verify-${versionId}`) return;

    if (status === 'completed' || status === 'error') {
      clearDownloadEntry();
      if (status === 'error') {
        setStatus('Some files failed to download — launching offline', 'loading');
      }
      return;
    }
    // Show per-file progress
    setDownloadProgress(current_file, percent);
  });

  // Game process confirmed running
  listen('instance-process-started', (evt) => {
    if (evt && (evt.payload === versionId || !evt.payload)) markGameStarted();
  });

  listen('running-instances-changed', async () => {
    if (typeof invoke !== 'function') return;
    const running = await invoke('get_running_instances').catch(() => []);
    const inst = Array.isArray(running) ? running.find(r => r.version_id === versionId) : null;
    if (inst?.running && inst?.pid) markGameStarted();
  });

  listen('launch-failed', (evt) => {
    const msg = evt?.payload ? String(evt.payload) : 'Launch failed';
    if (logArea) {
      if (currentEntry) currentEntry.className = 'log-entry fail';
      const el = document.createElement('div');
      el.className = 'log-entry fail';
      el.textContent = msg;
      logArea.appendChild(el);
      logArea.scrollTop = logArea.scrollHeight;
    }
    setStatus(msg, 'error');
  });

  // Signal app.js that all listeners are registered — it waits for this
  // before calling launchGame so no events are missed due to timing.
  if (window.__TAURI__?.event?.emit) {
    window.__TAURI__.event.emit('mini-window-ready', versionId).catch(() => {});
  }
});
