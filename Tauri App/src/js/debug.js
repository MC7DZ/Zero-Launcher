/* ═══════════════════════════════════════════════════════════════════
   ZeroLauncher — frontend diagnostics
   ───────────────────────────────────────────────────────────────────
   Imported FIRST by app.js / console.js / launching.js. It:

   1. Wraps Tauri's `invoke` and `listen` (exported below) so every IPC
      call is timed and every event is logged.
   2. Mirrors console.log/info/debug/warn/error, uncaught errors, promise
      rejections, failed resource loads and CSP violations into the
      launcher's log (`logs/latest.log`, source "UI").
   3. Logs breadcrumbs: clicks, special key presses, form changes,
      focus/visibility/online/resize/page lifecycle, WebGL context loss,
      fetch() calls.
   4. Watches for main-thread stalls (timer drift + long-task observer),
      measures FPS, and samples DOM size / memory every 10 s.

   Handy in the devtools console:
      __ZL_DEBUG.freeze(8000)   // block the UI for 8 s (to see stall logging)
      __ZL_DEBUG.dump()         // current pending IPC calls, lag, queue, ...
   ═══════════════════════════════════════════════════════════════════ */

const TAURI = window.__TAURI__;
const rawInvoke = TAURI.core.invoke;
const rawListen = TAURI.event.listen;

const WINDOW_LABEL = (() => {
  try {
    return TAURI.webviewWindow.getCurrentWebviewWindow().label || 'unknown';
  } catch (_) {
    return 'unknown';
  }
})();

// Original console methods (kept so we never recurse into our own capture).
const orig = {
  log: console.log.bind(console),
  info: console.info.bind(console),
  debug: console.debug.bind(console),
  warn: console.warn.bind(console),
  error: console.error.bind(console),
};

/* ────────────────────────── log shipping ────────────────────────── */

const queue = [];
let debugOn = true;          // Rust tells us whether DEBUG lines are recorded
let flushTimer = null;
let flushing = false;
let droppedByRateLimit = 0;
let rateWindowStart = 0;
let rateWindowCount = 0;
const MAX_LINES_PER_SECOND = 300;

function enqueue(level, message) {
  if (level === 'DEBUG' && !debugOn) return;

  const now = performance.now();
  if (now - rateWindowStart > 1000) {
    if (droppedByRateLimit > 0) {
      queue.push({ level: 'WARN', message: `[${WINDOW_LABEL}] log rate limit: dropped ${droppedByRateLimit} UI log lines in the last second` });
      droppedByRateLimit = 0;
    }
    rateWindowStart = now;
    rateWindowCount = 0;
  }
  if (++rateWindowCount > MAX_LINES_PER_SECOND) {
    droppedByRateLimit++;
    return;
  }

  queue.push({ level, message: `[${WINDOW_LABEL}] ${message}` });
  if (queue.length >= 100) {
    flush();
  } else if (!flushTimer) {
    flushTimer = setTimeout(flush, 500);
  }
}

async function flush() {
  if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
  if (flushing || queue.length === 0) return;
  flushing = true;
  const lines = queue.splice(0, 200);
  try {
    const keepDebug = await rawInvoke('frontend_log_batch', { lines });
    debugOn = !!keepDebug;
  } catch (e) {
    orig.warn('[debug.js] could not ship UI logs to the backend:', e);
  } finally {
    flushing = false;
    if (queue.length > 0 && !flushTimer) flushTimer = setTimeout(flush, 200);
  }
}

/** Log from app code: zlog('info'|'debug'|'warn'|'error', message) */
export function zlog(level, message) {
  enqueue(String(level).toUpperCase(), String(message));
}
const dbg = (m) => enqueue('DEBUG', m);
const wrn = (m) => enqueue('WARN', m);
const err = (m) => enqueue('ERROR', m);
const inf = (m) => enqueue('INFO', m);

/* ────────────────────────── formatting helpers ────────────────────────── */

function clip(s, n = 400) {
  s = String(s);
  return s.length > n ? s.slice(0, n) + `… (${s.length} chars)` : s;
}

function fmtArg(a) {
  if (a instanceof Error) return a.stack || `${a.name}: ${a.message}`;
  if (typeof a === 'string') return a;
  if (a === undefined) return 'undefined';
  if (typeof a === 'function') return `[function ${a.name || 'anonymous'}]`;
  if (a instanceof Element) return describeEl(a);
  try {
    const seen = new WeakSet();
    return JSON.stringify(a, (k, v) => {
      if (typeof v === 'object' && v !== null) {
        if (seen.has(v)) return '[circular]';
        seen.add(v);
      }
      if (typeof v === 'function') return `[fn ${v.name || ''}]`;
      return v;
    });
  } catch (_) {
    return String(a);
  }
}

/** Structure only — never values. Safe for IPC results / event payloads. */
function shape(v, depth = 0) {
  if (v === null || v === undefined) return String(v);
  if (Array.isArray(v)) return `[${v.length} items]`;
  const t = typeof v;
  if (t === 'string') return `"string(${v.length})"`;
  if (t === 'number' || t === 'boolean') return String(v);
  if (t === 'object') {
    const keys = Object.keys(v);
    if (depth >= 1) return `{${keys.length} keys}`;
    return `{${keys.slice(0, 10).join(',')}${keys.length > 10 ? ',…' : ''}}`;
  }
  return t;
}

function describeEl(el) {
  if (!el || !el.tagName) return String(el);
  let s = el.tagName.toLowerCase();
  if (el.id) s += '#' + el.id;
  if (typeof el.className === 'string' && el.className.trim()) {
    s += '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.');
  }
  for (const attr of ['data-tab', 'data-page', 'data-view', 'data-section', 'data-action', 'data-id', 'data-version', 'name', 'type']) {
    const v = el.getAttribute && el.getAttribute(attr);
    if (v) s += `[${attr}=${clip(v, 40)}]`;
  }
  const label = (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'))) || '';
  const text = (label || el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
  if (text) s += ` "${clip(text, 30)}"`;
  return s;
}

/* ────────────────────────── invoke wrapper ────────────────────────── */

const QUIET_COMMANDS = new Set([
  'frontend_log_batch', 'get_logs', 'window_is_maximized',
  'play_click_sound', 'report_activity',
]);
const SLOW_IPC_MS = 2000;

let ipcSeq = 0;
const pending = new Map(); // id -> { cmd, t0 }
let ipcTotal = 0;
let ipcFailed = 0;

export function invoke(cmd, args, options) {
  if (QUIET_COMMANDS.has(cmd)) return rawInvoke(cmd, args, options);

  const id = ++ipcSeq;
  const t0 = performance.now();
  pending.set(id, { cmd, t0 });
  ipcTotal++;

  let p;
  try {
    p = rawInvoke(cmd, args, options);
  } catch (e) {
    pending.delete(id);
    ipcFailed++;
    wrn(`invoke ${cmd} threw synchronously: ${fmtArg(e)}`);
    throw e;
  }
  return p.then(
    (res) => {
      pending.delete(id);
      const ms = Math.round(performance.now() - t0);
      if (ms >= SLOW_IPC_MS) wrn(`SLOW IPC: ${cmd} took ${ms} ms (result ${shape(res)})`);
      else dbg(`← ${cmd} ok in ${ms} ms (result ${shape(res)})`);
      return res;
    },
    (e) => {
      pending.delete(id);
      ipcFailed++;
      const ms = Math.round(performance.now() - t0);
      wrn(`✗ ${cmd} FAILED after ${ms} ms: ${clip(fmtArg(e), 600)}`);
      throw e;
    },
  );
}

/* ────────────────────────── listen wrapper ────────────────────────── */

const QUIET_EVENTS = new Set(['log-entry', 'instance-log']);
const eventThrottle = new Map(); // name -> { last, skipped }

export function listen(eventName, handler, options) {
  const wrapped = (e) => {
    if (!QUIET_EVENTS.has(eventName)) {
      const now = performance.now();
      const st = eventThrottle.get(eventName) || { last: 0, skipped: 0 };
      if (now - st.last >= 300) {
        dbg(`event "${eventName}" payload=${shape(e && e.payload)}${st.skipped ? ` (+${st.skipped} similar suppressed)` : ''}`);
        st.last = now;
        st.skipped = 0;
      } else {
        st.skipped++;
      }
      eventThrottle.set(eventName, st);
    }
    try {
      return handler(e);
    } catch (ex) {
      err(`event handler for "${eventName}" threw: ${fmtArg(ex)}`);
      throw ex;
    }
  };
  return rawListen(eventName, wrapped, options).then((unlisten) => {
    dbg(`listening to event "${eventName}"`);
    return unlisten;
  });
}

/* ────────────────────────── console / error capture ────────────────────────── */

let inCapture = false;
function capture(level, args) {
  if (inCapture) return;
  inCapture = true;
  try {
    enqueue(level, 'console: ' + clip(args.map(fmtArg).join(' '), 3000));
  } catch (_) { /* never break console */ } finally {
    inCapture = false;
  }
}
console.log = (...a) => { orig.log(...a); capture('DEBUG', a); };
console.info = (...a) => { orig.info(...a); capture('DEBUG', a); };
console.debug = (...a) => { orig.debug(...a); capture('DEBUG', a); };
console.warn = (...a) => { orig.warn(...a); capture('WARN', a); };
console.error = (...a) => { orig.error(...a); capture('ERROR', a); };

window.addEventListener('error', (e) => {
  if (e.target && e.target !== window && e.target.tagName) {
    // <img>/<script>/<link> failed to load
    const src = e.target.currentSrc || e.target.src || e.target.href || '';
    wrn(`resource failed to load: <${e.target.tagName.toLowerCase()}> ${clip(src, 200)}`);
    return;
  }
  err(`UNCAUGHT ERROR: ${e.message} at ${e.filename}:${e.lineno}:${e.colno}\n${e.error && e.error.stack ? e.error.stack : ''}`);
}, true);

window.addEventListener('unhandledrejection', (e) => {
  err(`UNHANDLED PROMISE REJECTION: ${fmtArg(e.reason)}`);
});

document.addEventListener('securitypolicyviolation', (e) => {
  wrn(`CSP violation: ${e.violatedDirective} blocked ${clip(e.blockedURI, 200)}`);
});

/* ────────────────────────── breadcrumbs ────────────────────────── */

let lastClick = { desc: '(none yet)', at: 0 };

document.addEventListener('click', (e) => {
  const target = e.target && e.target.closest
    ? (e.target.closest('button, a, [role="button"], [role="tab"], input, select, label, .instance-card, [data-tab], [data-page], [data-action]') || e.target)
    : e.target;
  const desc = describeEl(target);
  lastClick = { desc, at: performance.now() };
  dbg(`click ${desc} at (${Math.round(e.clientX)},${Math.round(e.clientY)})`);
}, true);

document.addEventListener('contextmenu', (e) => dbg(`contextmenu on ${describeEl(e.target)}`), true);

const LOGGED_KEYS = /^(Escape|Enter|Tab|Delete|Backspace|F\d{1,2}|ArrowUp|ArrowDown|PageUp|PageDown)$/;
document.addEventListener('keydown', (e) => {
  // Never log plain characters (could be a password / account name).
  if (LOGGED_KEYS.test(e.key) || e.ctrlKey || e.metaKey || e.altKey) {
    const mods = [e.ctrlKey && 'Ctrl', e.metaKey && 'Meta', e.altKey && 'Alt', e.shiftKey && 'Shift'].filter(Boolean).join('+');
    const key = (e.ctrlKey || e.metaKey || e.altKey) && e.key.length === 1 ? e.key.toUpperCase() : e.key;
    dbg(`key ${mods ? mods + '+' : ''}${key} in ${describeEl(document.activeElement)}`);
  }
}, true);

document.addEventListener('change', (e) => {
  const t = e.target;
  if (!t || !t.tagName) return;
  const tag = t.tagName.toLowerCase();
  const type = (t.type || '').toLowerCase();
  let value;
  if (type === 'checkbox' || type === 'radio') value = `checked=${t.checked}`;
  else if (tag === 'select' || type === 'range' || type === 'number' || type === 'color') value = `value=${clip(t.value, 60)}`;
  else value = `(text, ${String(t.value || '').length} chars — not logged)`;
  dbg(`change ${describeEl(t)} ${value}`);
}, true);

document.addEventListener('drop', (e) => {
  const n = e.dataTransfer && e.dataTransfer.files ? e.dataTransfer.files.length : 0;
  dbg(`drop on ${describeEl(e.target)} (${n} files)`);
}, true);

document.addEventListener('visibilitychange', () => dbg(`visibilitychange → ${document.visibilityState}`));
window.addEventListener('focus', () => dbg('window focus'));
window.addEventListener('blur', () => dbg('window blur'));
window.addEventListener('online', () => inf('network: browser went ONLINE'));
window.addEventListener('offline', () => wrn('network: browser went OFFLINE'));
window.addEventListener('pagehide', (e) => { dbg(`pagehide (persisted=${e.persisted})`); flush(); });
window.addEventListener('pageshow', (e) => dbg(`pageshow (persisted=${e.persisted})`));
window.addEventListener('beforeunload', () => { dbg('beforeunload'); flush(); });

let resizeTimer = null;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => dbg(`resize → ${window.innerWidth}x${window.innerHeight} @${window.devicePixelRatio}x`), 300);
});

document.addEventListener('webglcontextlost', (e) => wrn(`WebGL context LOST on ${describeEl(e.target)}`), true);
document.addEventListener('webglcontextrestored', (e) => inf(`WebGL context restored on ${describeEl(e.target)}`), true);

/* ────────────────────────── fetch() logging ────────────────────────── */

if (typeof window.fetch === 'function') {
  const origFetch = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const t0 = performance.now();
    let url = '';
    try {
      url = typeof input === 'string' ? input : (input && input.url) || String(input);
      url = url.split('?')[0];
    } catch (_) { /* ignore */ }
    const method = (init && init.method) || (input && input.method) || 'GET';
    return origFetch(input, init).then(
      (res) => {
        const ms = Math.round(performance.now() - t0);
        (res.ok ? dbg : wrn)(`fetch ${method} ${clip(url, 160)} → ${res.status} in ${ms} ms`);
        return res;
      },
      (e) => {
        wrn(`fetch ${method} ${clip(url, 160)} FAILED after ${Math.round(performance.now() - t0)} ms: ${fmtArg(e)}`);
        throw e;
      },
    );
  };
}

/* ────────────────────────── page-load report ────────────────────────── */

try {
  const nav = performance.getEntriesByType('navigation')[0];
  const navType = nav ? nav.type : 'unknown';
  const line = `page script started: window='${WINDOW_LABEL}' url=${location.pathname}${location.search} navigation=${navType} ua=${navigator.userAgent}`;
  (navType === 'reload' ? wrn : inf)(navType === 'reload' ? line + '  ← PAGE WAS RELOADED' : line);
  dbg(`env: screen=${screen.width}x${screen.height}@${window.devicePixelRatio}x viewport=${window.innerWidth}x${window.innerHeight} lang=${navigator.language} tz=${Intl.DateTimeFormat().resolvedOptions().timeZone} cpus=${navigator.hardwareConcurrency} deviceMemory=${navigator.deviceMemory || 'n/a'}GB online=${navigator.onLine}`);
} catch (_) { /* ignore */ }

window.addEventListener('DOMContentLoaded', () => dbg(`DOMContentLoaded at ${Math.round(performance.now())} ms`));
window.addEventListener('load', () => dbg(`window load at ${Math.round(performance.now())} ms`));

/* ────────────────────────── stall / perf monitors ────────────────────────── */

let maxLagSinceBeat = 0;
let lastTick = performance.now();
const LAG_WARN_MS = 300;

setInterval(() => {
  const now = performance.now();
  const lag = now - lastTick - 100;
  lastTick = now;
  if (lag > maxLagSinceBeat) maxLagSinceBeat = lag;
  if (lag > LAG_WARN_MS && !document.hidden) {
    wrn(`MAIN THREAD BLOCKED ~${Math.round(lag)} ms (last click: ${lastClick.desc}, pending IPC: ${pending.size})`);
  }
}, 100);

try {
  if (typeof PerformanceObserver !== 'undefined' && PerformanceObserver.supportedEntryTypes
      && PerformanceObserver.supportedEntryTypes.includes('longtask')) {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (entry.duration >= 200) wrn(`long task: ${Math.round(entry.duration)} ms (started ${Math.round(entry.startTime)} ms)`);
      }
    }).observe({ entryTypes: ['longtask'] });
    dbg('long-task observer active');
  } else {
    dbg('long-task observer not supported here — relying on timer-drift monitor');
  }
} catch (_) { /* ignore */ }

// FPS over 5 s windows (rAF stops while hidden, which is fine).
let frames = 0;
let fpsStart = performance.now();
function frameTick(now) {
  frames++;
  if (now - fpsStart >= 5000) {
    const fps = (frames * 1000) / (now - fpsStart);
    (fps < 20 ? wrn : dbg)(`fps ≈ ${fps.toFixed(1)} over ${Math.round(now - fpsStart)} ms`);
    frames = 0;
    fpsStart = now;
  }
  requestAnimationFrame(frameTick);
}
requestAnimationFrame(frameTick);

setInterval(() => {
  try {
    const nodes = document.getElementsByTagName('*').length;
    const mem = performance.memory
      ? ` heap=${(performance.memory.usedJSHeapSize / 1048576).toFixed(1)}/${(performance.memory.totalJSHeapSize / 1048576).toFixed(1)}MB`
      : '';
    dbg(`stats: dom_nodes=${nodes}${mem} pending_ipc=${pending.size} ipc_total=${ipcTotal} ipc_failed=${ipcFailed} queued_logs=${queue.length} hidden=${document.hidden}`);
  } catch (_) { /* ignore */ }
}, 10000);

/* ────────────────────────── devtools helpers ────────────────────────── */

window.__ZL_DEBUG = {
  invoke, listen, zlog, flush,
  /** Block the UI thread for `ms`. */
  freeze(ms = 8000) {
    wrn(`__ZL_DEBUG.freeze(${ms}) — deliberately blocking the UI thread`);
    flush();
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
  },
  dump() {
    return {
      window: WINDOW_LABEL,
      pendingIpc: [...pending.values()].map((p) => ({ cmd: p.cmd, ms: Math.round(performance.now() - p.t0) })),
      ipcTotal, ipcFailed, queued: queue.length, debugOn, lastClick,
    };
  },
};

export default window.__ZL_DEBUG;
