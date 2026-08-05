/* ═══════════════════════════════════════════════════════════════════
   Per-instance console window.
   Opened by the main window's "Running Instances" panel — one of these
   windows exists per instance the user has clicked into, filtered to only
   that instance's console output via the `instance-log` event's
   `version_id` field.
   ═══════════════════════════════════════════════════════════════════ */

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const params = new URLSearchParams(window.location.search);
const versionId = params.get('instance') || '';
const displayName = params.get('name') || versionId;

// ── Theme sync ──
// This window loads main.css but is a separate document, so it needs its
// own copy of the same dark/light resolution the main window does.
const THEME_PRESETS = {
  dark: { bg_color: '#0a0a0f', panel_bg_color: '#13131a', text_color: '#e2e2ea', log_bg_color: '#060608', header_bg_color: '#111116' },
  light: { bg_color: '#f3f3f6', panel_bg_color: '#ffffff', text_color: '#1c1c22', log_bg_color: '#eef0f3', header_bg_color: '#ffffff' },
};
const systemThemeQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;

function hexToRgba(hex, alpha) {
  hex = (hex || '').replace('#', '');
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
  const r = parseInt(hex.slice(0, 2), 16) || 0;
  const g = parseInt(hex.slice(2, 4), 16) || 0;
  const b = parseInt(hex.slice(4, 6), 16) || 0;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

async function applyTheme() {
  let settings = {};
  try { settings = await invoke('get_settings'); } catch (e) { /* fall back to defaults */ }

  const mode = (settings && settings.theme_mode) || 'system';
  const effective = mode === 'light' ? 'light' : mode === 'dark' ? 'dark' : (systemThemeQuery && systemThemeQuery.matches ? 'light' : 'dark');
  const preset = THEME_PRESETS[effective];
  const root = document.documentElement;

  // Each theme has its own accent field; fall back to the legacy single
  // field (older settings payloads) and then to a neutral default.
  const accentDefaults = { light: '#1A1A1A', dark: '#B7B7B7' };
  const accent = (effective === 'light'
    ? (settings && settings.accent_color_light)
    : (settings && settings.accent_color_dark))
    || (settings && settings.accent_color)
    || accentDefaults[effective];

  root.setAttribute('data-theme', effective);
  root.style.setProperty('--overlay-rgb', effective === 'light' ? '15, 15, 20' : '255, 255, 255');
  // Console window background is fixed per-theme (not user-customizable
  // via bg_color) — #e9eaed in light mode, #141416 in dark mode.
  root.style.setProperty('--console-window-bg', effective === 'light' ? '#e9eaed' : '#141416');
  root.style.setProperty('--accent', accent);
  root.style.setProperty('--accent-dim', hexToRgba(accent, 0.15));
  root.style.setProperty('--accent-glow', hexToRgba(accent, 0.35));
  root.style.setProperty('--bg', (settings && settings.bg_color) || preset.bg_color);
  root.style.setProperty('--panel', hexToRgba((settings && settings.panel_bg_color) || preset.panel_bg_color, 0.95));
  root.style.setProperty('--panel-solid', hexToRgba((settings && settings.panel_bg_color) || preset.panel_bg_color, 0.97));
  // Text color intentionally ignores settings.text_color here (unlike --bg
  // above) and always uses the resolved theme's own preset. The console
  // window's background is fixed per-theme regardless of the user's
  // custom colors (see --console-window-bg below), so a custom text color
  // tuned against a *different* background — e.g. a light color left over
  // from dark mode — could land as unreadably-light text on the light
  // theme's light background. Always-correct contrast beats color
  // customization for this one window.
  root.style.setProperty('--text', preset.text_color);
  root.style.setProperty('--text-muted', hexToRgba(preset.text_color, 0.55));
  root.style.setProperty('--log-bg', (settings && settings.log_bg_color) || preset.log_bg_color);
}

if (systemThemeQuery) systemThemeQuery.addEventListener('change', applyTheme);
applyTheme();

// All entries we've ever seen, kept around so search/level filters can be
// re-applied without losing history.
const entries = [];
let autoScroll = true;
let activeLevels = new Set(['INFO', 'WARN', 'ERROR', 'DEBUG']);
let searchTerm = '';

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str ?? '';
  return div.innerHTML;
}

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Wraps matches of the current search term in <mark> so they're easy to
// spot, without touching anything but the message text.
function highlight(text) {
  if (!searchTerm) return escapeHtml(text);
  const escaped = escapeHtml(text);
  const re = new RegExp(escapeRegex(escapeHtml(searchTerm)), 'ig');
  return escaped.replace(re, (m) => `<mark>${m}</mark>`);
}

function entryMatches(entry) {
  if (!activeLevels.has(entry.level)) return false;
  if (!searchTerm) return true;
  const haystack = `${entry.source} ${entry.message}`.toLowerCase();
  return haystack.includes(searchTerm.toLowerCase());
}

function isNearBottom(viewer) {
  return viewer.scrollHeight - viewer.scrollTop < viewer.clientHeight + 100;
}

function updateEmptyState() {
  const empty = document.getElementById('console-empty');
  if (!empty) return;
  empty.classList.toggle('hidden', entries.length > 0);
}

function buildEntryEl(entry) {
  const div = document.createElement('div');
  div.className = 'log-entry';
  div.dataset.level = entry.level;
  div.innerHTML = `<span class="log-time">[${entry.timestamp}]</span><span class="log-level-${entry.level}">[${entry.level}]</span> <span class="log-source">[${escapeHtml(entry.source)}]</span> ${highlight(entry.message ?? '')}`;
  if (!entryMatches(entry)) div.classList.add('log-hidden');
  return div;
}

function appendLine(entry, viewer) {
  entries.push(entry);
  updateEmptyState();
  const wasNearBottom = isNearBottom(viewer);
  const jumpBtn = document.getElementById('console-jump-btn');

  viewer.appendChild(buildEntryEl(entry));

  if (autoScroll && wasNearBottom) {
    viewer.scrollTop = viewer.scrollHeight;
    if (jumpBtn) jumpBtn.classList.remove('visible');
  } else if (jumpBtn) {
    jumpBtn.classList.add('visible');
  }
}

// Re-renders every entry from scratch — used whenever a filter or the
// search term changes, since visibility depends on both together.
function reapplyFilters(viewer) {
  Array.from(viewer.querySelectorAll('.log-entry')).forEach((el, i) => {
    const entry = entries[i];
    if (!entry) return;
    el.innerHTML = `<span class="log-time">[${entry.timestamp}]</span><span class="log-level-${entry.level}">[${entry.level}]</span> <span class="log-source">[${escapeHtml(entry.source)}]</span> ${highlight(entry.message ?? '')}`;
    el.classList.toggle('log-hidden', !entryMatches(entry));
  });
}

function setRunningIndicator(running) {
  const dot = document.getElementById('console-status-dot');
  const sub = document.getElementById('console-subtitle');
  if (dot) dot.classList.toggle('is-running', running);
  if (sub) sub.textContent = running ? 'Running' : 'Not running';
}

function visibleLogText() {
  return entries
    .filter(entryMatches)
    .map(e => `[${e.timestamp}] [${e.level}] [${e.source}] ${e.message ?? ''}`)
    .join('\n');
}

async function init() {
  document.getElementById('console-title').textContent = displayName;
  document.title = 'Console — ' + displayName;

  const viewer = document.getElementById('console-viewer');
  const jumpBtn = document.getElementById('console-jump-btn');

  updateEmptyState();

  // Reflect current running state (best-effort — this instance may have
  // already exited by the time the window opens).
  try {
    const running = await invoke('get_running_instances');
    const match = running.find(i => i.version_id === versionId);
    setRunningIndicator(!!(match && match.running));
  } catch (e) {
    console.error('Failed to load running state', e);
  }

  // Load whatever console output was captured before this window opened.
  try {
    const history = await invoke('get_instance_console_logs', { versionId });
    history.forEach(entry => appendLine(entry, viewer));
    viewer.scrollTop = viewer.scrollHeight;
    if (jumpBtn) jumpBtn.classList.remove('visible');
  } catch (e) {
    console.error('Failed to load console history', e);
  }

  // Stream new lines live, filtered to this instance only.
  await listen('instance-log', (event) => {
    const payload = event.payload;
    if (!payload || payload.version_id !== versionId) return;
    appendLine(payload.entry, viewer);
  });

  await listen('running-instances-changed', async () => {
    try {
      const running = await invoke('get_running_instances');
      const match = running.find(i => i.version_id === versionId);
      setRunningIndicator(!!(match && match.running));
    } catch (e) { /* ignore */ }
  });

  // ── Toolbar: search ──
  const searchInput = document.getElementById('console-search');
  let searchDebounce;
  searchInput.addEventListener('input', () => {
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(() => {
      searchTerm = searchInput.value.trim();
      reapplyFilters(viewer);
    }, 120);
  });

  // ── Toolbar: level chips ──
  document.querySelectorAll('.lvl-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const level = chip.dataset.level;
      if (activeLevels.has(level)) {
        // Don't allow turning every chip off — that would just be a blank
        // viewer with no way back short of a reload.
        if (activeLevels.size === 1) return;
        activeLevels.delete(level);
        chip.classList.remove('active');
      } else {
        activeLevels.add(level);
        chip.classList.add('active');
      }
      reapplyFilters(viewer);
    });
  });

  // ── Toolbar: auto-scroll ──
  const autoScrollToggle = document.getElementById('console-autoscroll-toggle');
  autoScrollToggle.addEventListener('change', () => {
    autoScroll = autoScrollToggle.checked;
    if (autoScroll) {
      viewer.scrollTop = viewer.scrollHeight;
      if (jumpBtn) jumpBtn.classList.remove('visible');
    }
  });

  // Manual scrolling turns auto-scroll off automatically, same as any
  // terminal — and back on once the user scrolls back to the bottom.
  viewer.addEventListener('scroll', () => {
    const atBottom = isNearBottom(viewer);
    if (atBottom && jumpBtn) jumpBtn.classList.remove('visible');
    if (atBottom !== autoScroll) {
      autoScroll = atBottom;
      autoScrollToggle.checked = atBottom;
    }
  });

  if (jumpBtn) {
    jumpBtn.addEventListener('click', () => {
      viewer.scrollTop = viewer.scrollHeight;
      autoScroll = true;
      autoScrollToggle.checked = true;
      jumpBtn.classList.remove('visible');
    });
  }

  // ── Header actions ──
  document.getElementById('btn-clear-console').addEventListener('click', () => {
    entries.length = 0;
    viewer.querySelectorAll('.log-entry').forEach(el => el.remove());
    updateEmptyState();
  });

  document.getElementById('btn-copy-console').addEventListener('click', async () => {
    const text = visibleLogText();
    try {
      await navigator.clipboard.writeText(text);
      flashButton('btn-copy-console', 'Copied!');
    } catch (e) {
      console.error('Copy failed', e);
    }
  });

  document.getElementById('btn-save-console').addEventListener('click', async () => {
    const text = visibleLogText();
    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const safeName = displayName.replace(/[^a-z0-9-_]+/gi, '_');
    a.href = url;
    a.download = `${safeName || 'console'}-log.txt`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    flashButton('btn-save-console', 'Saved!');
  });
}

function flashButton(id, label) {
  const btn = document.getElementById(id);
  if (!btn) return;
  const original = btn.textContent;
  btn.textContent = label;
  btn.disabled = true;
  setTimeout(() => {
    btn.textContent = original;
    btn.disabled = false;
  }, 1100);
}

document.addEventListener('DOMContentLoaded', init);
