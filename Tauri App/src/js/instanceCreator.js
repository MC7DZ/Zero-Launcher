import iconVanilla from '../assets/loaders/vanilla.png';
import iconFabric from '../assets/loaders/fabric.png';
import iconForge from '../assets/loaders/forge.png';
import iconNeoforge from '../assets/loaders/neoforge.png';
import iconQuilt from '../assets/loaders/quilt.png';

/**
 * Initializes the "Choose a type" / New Instance Creator section:
 * - Downloads / caches background pictures from GitHub into "<Zero Launcher directory>/assets/background/":
 *     - minecraft-wilderness-bound.jpg (Minecraft instance, Import)
 *     - oneclient.jpg (OneClient)
 *     - modpacks.jpg (Modpack)
 * - Smooth cross-fade animation when switching or hovering over instance types
 * - Hides the top-bar and custom titlebar elements while active
 * - Matches the native Zero Launcher UI design system (panels, borders, radii, fonts, accent colors)
 *
 * @param {Object} options
 * @param {Function} options.onProceedWithMinecraftInstance Callback when proceeding with Minecraft/OneClient instance
 */
export function initInstanceCreator({ onProceedWithMinecraftInstance } = {}) {
  const newInstanceSection = document.getElementById('new-instance-section');
  const layerA = document.querySelector('.creator-bg-layer-a');
  const layerB = document.querySelector('.creator-bg-layer-b');
  const topBar = document.getElementById('top-bar');
  const btnNewInstance = document.getElementById('btn-new-instance');
  const btnClose = document.getElementById('btn-creator-close');
  const btnCancel = document.getElementById('btn-creator-cancel');
  const btnNext = document.getElementById('btn-creator-next');
  const creatorOptionCards = document.querySelectorAll('.creator-option-card');
  const creatorOptionsList = document.querySelector('.creator-options-list');
  const creatorFooterInfo = document.getElementById('creator-footer-info');

  const remoteUrls = {
    'minecraft': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-wilderness-bound.jpg',
    'oneclient': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/oneclient.jpg',
    'modpack': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/modpacks.jpg',
    'minecarft-tiny-takeover.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecarft-tiny-takeover.png',
    'minecraft-chaos-cubed.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-chaos-cubed.png',
    'minecraft-chase-the-skies.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-chase-the-skies.png',
    'minecraft-mounts-of-mayhem.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-mounts-of-mayhem.png',
    'minecraft-the-copper-age.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-the-copper-age.png',
    'minecraft-nether-update.png': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-nether-update.png',
    'minecraft-wilderness-bound.jpg': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-wilderness-bound.jpg'
  };

  const minecraftBackgrounds = [
    'minecraft-wilderness-bound.jpg',
    'minecarft-tiny-takeover.png',
    'minecraft-chaos-cubed.png',
    'minecraft-chase-the-skies.png',
    'minecraft-mounts-of-mayhem.png',
    'minecraft-the-copper-age.png',
    'minecraft-nether-update.png'
  ];

  let minecraftBgIndex = 0;
  let mcSlideshowInterval = null;

  const cachedUrls = {};
  const preloadedImages = {};

  function preloadImageElement(url) {
    return new Promise((resolve) => {
      if (!url) return resolve(null);
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(img);
      img.src = url;
    });
  }

  async function resolveImageSrc(key) {
    if (cachedUrls[key]) return cachedUrls[key];
    try {
      const invoke = window.__TAURI__?.core?.invoke;
      const convertFileSrc = window.__TAURI__?.core?.convertFileSrc;
      if (invoke) {
        const localPath = await invoke('ensure_creator_background', { name: key });
        if (localPath) {
          const url = convertFileSrc ? convertFileSrc(localPath) : localPath;
          cachedUrls[key] = url;
          // Preload into browser image cache
          preloadImageElement(url).then(img => { preloadedImages[key] = img; });
          return url;
        }
      }
    } catch (e) {
      console.warn(`Failed to resolve background image '${key}':`, e);
    }
    const fallback = remoteUrls[key] || remoteUrls['minecraft'];
    cachedUrls[key] = fallback;
    preloadImageElement(fallback).then(img => { preloadedImages[key] = img; });
    return fallback;
  }

  function typeToImageKey(type) {
    if (type === 'oneclient') return 'oneclient';
    if (type === 'modpack') return 'modpack';
    return minecraftBackgrounds[minecraftBgIndex] || 'minecraft-wilderness-bound.jpg';
  }

  function startMinecraftSlideshow() {
    stopMinecraftSlideshow();
    mcSlideshowInterval = setInterval(() => {
      // Only rotate when the selected instance type is minecraft-instance (all its steps 1-4)
      if (selectedCreatorType === 'minecraft-instance' && !newInstanceSection?.classList.contains('creator-hidden') && !newInstanceSection?.classList.contains('creator-closing')) {
        minecraftBgIndex = (minecraftBgIndex + 1) % minecraftBackgrounds.length;
        const nextKey = minecraftBackgrounds[minecraftBgIndex];
        transitionBackground(nextKey);
      }
    }, 5000);
  }

  function stopMinecraftSlideshow() {
    if (mcSlideshowInterval) {
      clearInterval(mcSlideshowInterval);
      mcSlideshowInterval = null;
    }
  }

  let activeLayer = 'a'; // 'a' or 'b'
  let currentImageKey = '';
  let transitionToken = 0;

  async function transitionBackground(key, force = false) {
    if (!key || (!force && key === currentImageKey)) return;
    const thisToken = ++transitionToken;

    const url = await resolveImageSrc(key);
    // If a newer switch occurred while awaiting, abort
    if (thisToken !== transitionToken) return;
    if (!layerA || !layerB) return;

    // Ensure the image bitmap is decoded in memory before swapping
    if (!preloadedImages[key] || force) {
      await preloadImageElement(url);
      if (thisToken !== transitionToken) return;
    }

    currentImageKey = key;

    // Cross-fade: simultaneously fade out the outgoing layer and fade in the incoming layer.
    // Both layers transition smoothly with CSS opacity (0.75s ease) for a true cross-fade.
    if (activeLayer === 'a') {
      layerB.style.backgroundImage = `url("${url}")`;
      layerB.style.zIndex = '2';
      layerA.style.zIndex = '1';
      layerB.style.opacity = '1';
      layerA.style.opacity = '0';
      activeLayer = 'b';
    } else {
      layerA.style.backgroundImage = `url("${url}")`;
      layerA.style.zIndex = '2';
      layerB.style.zIndex = '1';
      layerA.style.opacity = '1';
      layerB.style.opacity = '0';
      activeLayer = 'a';
    }
  }

  // Listen for background image updates from GitHub
  try {
    const listen = window.__TAURI__?.event?.listen;
    if (listen) {
      listen('creator-background-updated', async (event) => {
        const payload = event?.payload || {};
        const key = payload.key || payload.fileName;
        if (!key) return;

        const convertFileSrc = window.__TAURI__?.core?.convertFileSrc;
        const freshUrl = (convertFileSrc ? convertFileSrc(payload.path) : payload.path) + `?t=${Date.now()}`;

        cachedUrls[key] = freshUrl;
        delete preloadedImages[key];

        const img = await preloadImageElement(freshUrl);
        if (img) preloadedImages[key] = img;

        // If the updated image matches the currently visible background, cross-fade to it seamlessly!
        const currentActiveKey = typeToImageKey(selectedCreatorType);
        if (currentActiveKey === key || payload.fileName === currentActiveKey) {
          transitionBackground(key, true);
        }
      });
    }
  } catch (e) {
    console.warn('Failed to listen for creator-background-updated:', e);
  }

  // Pre-load all images in the background
  ['oneclient', 'modpack', ...minecraftBackgrounds].forEach(async (key) => {
    const url = await resolveImageSrc(key);
    const img = await preloadImageElement(url);
    preloadedImages[key] = img;
    if (key === minecraftBackgrounds[0] && layerA && !layerA.style.backgroundImage) {
      layerA.style.backgroundImage = `url("${url}")`;
      layerA.style.opacity = '1';
      layerA.style.zIndex = '2';
      if (layerB) {
        layerB.style.opacity = '0';
        layerB.style.zIndex = '1';
      }
      currentImageKey = key;
    }
  });

  let currentStep = 1; // 1: Choose a type, 2: Choose a game folder, 3: Choose a version
  let selectedCreatorType = 'minecraft-instance';
  let selectedFolderType = ''; // Initially unselected until Step 2 or defaulted
  let customFolderPath = '';
  let selectedMcVersion = '';
  let selectedLoader = 'Fabric';
  let selectedLoaderVersion = 'latest';
  let loaderVersionsCache = {};
  let versionsManifest = [];
  let isVersionsLoaded = false;

  const creatorDescriptions = {
    'minecraft-instance': 'Set up a custom vanilla or modded profile with your choice of loader and version.',
    'oneclient': 'Optimized all-in-one client featuring built-in performance tweaks, essential QoL mods, and seamless world hosting.',
    'modpack': 'Browse and install complete community modpacks directly from Modrinth, CurseForge, or a local package.',
    'import': 'Import existing instances or backups from other launchers and external archive files.'
  };

  const folderDescriptions = {
    'shared': 'Stores data in the default global Minecraft directory (~/.minecraft), sharing worlds and configs across profiles.',
    'isolated': 'Keeps all saves, configurations, and mods strictly contained in an independent instance directory.',
    'custom': 'Specify an exact directory on your storage drive to house this instance.'
  };

  // Step DOM elements
  const mainTitle = document.getElementById('creator-main-title');
  const mainSubtitle = document.getElementById('creator-main-subtitle');
  const bodyViewport = document.getElementById('creator-body-viewport');
  const stepTrack = document.getElementById('creator-step-track');
  const panelStep1 = document.getElementById('creator-panel-step-1');
  const panelStep2 = document.getElementById('creator-panel-step-2');
  const panelStep3 = document.getElementById('creator-panel-step-3');
  const panelStep4 = document.getElementById('creator-panel-step-4');
  const panelStep5 = document.getElementById('creator-panel-step-5');
  const btnBack = document.getElementById('btn-creator-back');
  const folderOptionCards = document.querySelectorAll('#creator-list-step-2 .creator-option-card');
  const customPathBox = document.getElementById('creator-custom-path-box');
  const customPathInput = document.getElementById('creator-custom-path-input');
  const customPathError = document.getElementById('creator-custom-path-error');
  const btnBrowseCustom = document.getElementById('btn-creator-browse-custom');
  const sharedPathHint = document.getElementById('creator-shared-path-hint');

  // Step 5 DOM elements
  const instNameInput = document.getElementById('creator-inst-name');
  const btnAdvancedToggle = document.getElementById('btn-creator-advanced-toggle');
  const advancedPanel = document.getElementById('creator-advanced-panel');
  const javaSelect = document.getElementById('creator-java-select');
  const minRamInput = document.getElementById('creator-min-ram');
  const maxRamInput = document.getElementById('creator-max-ram');
  const jvmArgsInput = document.getElementById('creator-jvm-args');
  const alwaysOfflineCheckbox = document.getElementById('creator-always-offline');
  const skipAssetsWrap = document.getElementById('creator-skip-assets-wrap');
  const skipAssetsCheckbox = document.getElementById('creator-skip-assets');

  // Step 3 version list elements
  const versionSearchInput = document.getElementById('creator-version-search');
  const versionTypeSelect = document.getElementById('creator-version-type-select');
  const versionsScrollable = document.getElementById('creator-versions-scrollable');

  // Step 4 loader elements
  const loaderCards = document.querySelectorAll('.creator-loader-card');
  const loaderVersionBox = document.getElementById('creator-loader-version-box');
  const loaderVersionTitle = document.getElementById('creator-loader-version-title');
  const loaderVersionSelect = document.getElementById('creator-loader-version-select');
  const iconImgFabric = document.getElementById('creator-icon-fabric');
  const iconImgForge = document.getElementById('creator-icon-forge');
  const iconImgNeoforge = document.getElementById('creator-icon-neoforge');
  const iconImgQuilt = document.getElementById('creator-icon-quilt');
  const iconImgVanilla = document.getElementById('creator-icon-vanilla');

  if (iconImgFabric) iconImgFabric.src = iconFabric;
  if (iconImgForge) iconImgForge.src = iconForge;
  if (iconImgNeoforge) iconImgNeoforge.src = iconNeoforge;
  if (iconImgQuilt) iconImgQuilt.src = iconQuilt;
  if (iconImgVanilla) iconImgVanilla.src = iconVanilla;

  // Live Summary Panel Elements
  const summaryPanel = document.getElementById('creator-summary-panel');
  const summaryTitle = document.getElementById('creator-summary-title');
  const summarySubtitle = document.getElementById('creator-summary-subtitle');
  const summaryType = document.getElementById('creator-summary-type');
  const summaryVersion = document.getElementById('creator-summary-version');
  const summaryFolder = document.getElementById('creator-summary-folder');
  const summaryLoader = document.getElementById('creator-summary-loader');

  function updateSummaryPanel() {
    if (!summaryPanel) return;

    // Type label: "minecraft instance" or "oneclient" or "-"
    let typeLabel = '-';
    if (selectedCreatorType === 'minecraft-instance') {
      typeLabel = 'minecraft instance';
    } else if (selectedCreatorType === 'oneclient') {
      typeLabel = 'oneclient';
    } else if (selectedCreatorType === 'modpack') {
      typeLabel = 'modpack';
    }

    // Version label: only if chosen/active, else "-"
    const vName = selectedMcVersion || '-';

    // Folder label: only if chosen (e.g. step 2 onwards), else "-"
    let folderLabel = '-';
    if (currentStep >= 2 || selectedFolderType) {
      if (selectedFolderType === 'isolated') folderLabel = 'Isolated';
      else if (selectedFolderType === 'shared') folderLabel = 'Shared';
      else if (selectedFolderType === 'custom') folderLabel = 'Custom';
    }

    // Loader label: "-" if not yet chosen or e.g. "Fabric" or "Fabric 0.20.1"
    let loaderLabel = '-';
    if (currentStep >= 4 || selectedLoader) {
      if (selectedLoader === 'Vanilla') {
        loaderLabel = 'Vanilla';
      } else if (selectedLoader) {
        loaderLabel = (selectedLoaderVersion && selectedLoaderVersion !== 'latest')
          ? `${selectedLoader} ${selectedLoaderVersion}`
          : selectedLoader;
      }
    }

    // Header Title and Subtitle
    if (summaryTitle) {
      if (vName !== '-' && loaderLabel !== '-') {
        summaryTitle.textContent = `${vName} ${loaderLabel}`;
      } else if (vName !== '-') {
        summaryTitle.textContent = vName;
      } else if (typeLabel !== '-') {
        summaryTitle.textContent = typeLabel;
      } else {
        summaryTitle.textContent = '-';
      }
    }

    if (summarySubtitle) {
      if (vName !== '-' && loaderLabel !== '-') {
        summarySubtitle.textContent = `${vName} · ${loaderLabel}`;
      } else if (vName !== '-') {
        summarySubtitle.textContent = vName;
      } else {
        summarySubtitle.textContent = '-';
      }
    }

    if (summaryType) summaryType.textContent = typeLabel;
    if (summaryVersion) summaryVersion.textContent = vName;
    if (summaryFolder) summaryFolder.textContent = folderLabel;
    if (summaryLoader) summaryLoader.textContent = loaderLabel;
  }

  // Smoothly morph text with fade-out / fade-in
  function morphText(el, newText) {
    if (!el || el.textContent === newText) return;
    el.classList.add('creator-text-changing');
    setTimeout(() => {
      el.textContent = newText;
      el.classList.remove('creator-text-changing');
    }, 120);
  }

  // Smoothly adjust viewport height to match active step panel
  function syncViewportHeight() {
    if (!bodyViewport) return;
    let targetPanel = panelStep1;
    if (currentStep === 2) targetPanel = panelStep2;
    else if (currentStep === 3) targetPanel = panelStep3;
    else if (currentStep === 4) targetPanel = panelStep4;
    else if (currentStep === 5) targetPanel = panelStep5;

    if (targetPanel) {
      const h = targetPanel.offsetHeight;
      if (h > 0) {
        bodyViewport.style.height = `${h}px`;
      }
    }
  }

  window.addEventListener('resize', () => {
    if (newInstanceSection && !newInstanceSection.classList.contains('creator-hidden')) {
      syncViewportHeight();
    }
  });

  function formatDate(isoStr) {
    if (!isoStr) return '';
    try {
      const d = new Date(isoStr);
      if (isNaN(d.getTime())) return '';
      return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    } catch {
      return '';
    }
  }

  async function loadVersions() {
    if (isVersionsLoaded && versionsManifest.length > 0) {
      renderVersionsList();
      return;
    }
    try {
      if (versionsScrollable) {
        versionsScrollable.innerHTML = '<div class="creator-version-loading">Fetching versions…</div>';
      }
      const resp = await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json');
      const data = await resp.json();
      versionsManifest = data.versions || [];
      isVersionsLoaded = true;

      if (!selectedMcVersion) {
        const firstRelease = versionsManifest.find(v => v.type === 'release');
        selectedMcVersion = firstRelease ? firstRelease.id : (versionsManifest[0]?.id || '1.21.1');
      }
      renderVersionsList();
    } catch (e) {
      console.warn('Failed to load version manifest:', e);
      if (versionsScrollable) {
        versionsScrollable.innerHTML = '<div class="creator-version-empty">Could not load versions list.</div>';
      }
    }
  }

  function renderVersionsList() {
    if (!versionsScrollable) return;
    const search = (versionSearchInput?.value || '').trim().toLowerCase();
    const filterType = versionTypeSelect?.value || 'release';

    // No artificial limit - display all available versions matching filter
    const filtered = versionsManifest.filter(v => {
      if (filterType !== 'all' && v.type !== filterType) {
        return false;
      }
      if (search && !v.id.toLowerCase().includes(search)) {
        return false;
      }
      return true;
    });

    if (filtered.length === 0) {
      versionsScrollable.innerHTML = '<div class="creator-version-empty">No versions match your search.</div>';
      syncViewportHeight();
      return;
    }

    // High performance DOM creation using DocumentFragment
    const frag = document.createDocumentFragment();
    filtered.forEach(v => {
      const card = document.createElement('div');
      card.className = `creator-version-card ${v.id === selectedMcVersion ? 'active' : ''}`;
      card.dataset.version = v.id;

      const dateFormatted = formatDate(v.releaseTime || v.time);

      card.innerHTML = `
        <div class="creator-version-left">
          <div class="creator-radio-indicator"></div>
          <span class="creator-version-id">${v.id}</span>
        </div>
        <span class="creator-version-date">${dateFormatted}</span>
      `;

      card.addEventListener('click', () => {
        versionsScrollable.querySelectorAll('.creator-version-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');
        selectedMcVersion = v.id;
        morphText(creatorFooterInfo, `${selectedMcVersion} downloads the first time you launch it.`);
        updateSummaryPanel();
      });

      frag.appendChild(card);
    });

    versionsScrollable.innerHTML = '';
    versionsScrollable.appendChild(frag);

    syncViewportHeight();
  }



  versionSearchInput?.addEventListener('input', () => {
    renderVersionsList();
  });

  versionTypeSelect?.addEventListener('change', () => {
    renderVersionsList();
  });

  async function fetchLoaderVersions(loader, mcVersion) {
    const l = (loader || '').toLowerCase();
    const mc = (mcVersion || '').trim();
    if (!l || l === 'vanilla' || !mc) return [];

    const key = `${l}|${mc}`;
    if (loaderVersionsCache[key]) return loaderVersionsCache[key];

    try {
      const invoke = window.__TAURI__?.core?.invoke;
      if (invoke) {
        const list = await invoke('get_loader_versions', { loader: l, minecraftVersion: mc });
        if (Array.isArray(list)) {
          loaderVersionsCache[key] = list;
          return list;
        }
      }
    } catch (e) {
      console.warn(`Failed to fetch loader versions for ${l} ${mc}:`, e);
    }
    return [];
  }

  async function updateLoaderStepUI() {
    // 1. Fetch available versions for all loaders in parallel for the current Minecraft version
    const loadersToCheck = ['Fabric', 'Forge', 'NeoForge', 'Quilt'];
    const versionResults = await Promise.all(
      loadersToCheck.map(ldr => fetchLoaderVersions(ldr, selectedMcVersion))
    );

    const availableLoaders = new Set(['Vanilla']);
    loadersToCheck.forEach((ldr, idx) => {
      const builds = versionResults[idx];
      if (builds && builds.length > 0) {
        availableLoaders.add(ldr);
      }
    });

    // 2. If the current selectedLoader is not available for this version, fallback to an available loader
    // Preference: Fabric -> Quilt -> Forge -> NeoForge -> Vanilla
    if (!availableLoaders.has(selectedLoader)) {
      if (availableLoaders.has('Fabric')) {
        selectedLoader = 'Fabric';
      } else if (availableLoaders.has('Quilt')) {
        selectedLoader = 'Quilt';
      } else if (availableLoaders.has('Forge')) {
        selectedLoader = 'Forge';
      } else if (availableLoaders.has('NeoForge')) {
        selectedLoader = 'NeoForge';
      } else {
        selectedLoader = 'Vanilla';
      }
      selectedLoaderVersion = 'latest';
    }

    // 3. Mark loader cards as active or disabled
    loaderCards.forEach(c => {
      const ldr = c.dataset.loader;
      const isAvailable = availableLoaders.has(ldr);
      c.classList.toggle('disabled', !isAvailable);
      c.classList.toggle('active', ldr === selectedLoader);
    });

    if (selectedLoader === 'Vanilla') {
      loaderVersionBox?.classList.add('hidden');
      morphText(creatorFooterInfo, 'Vanilla Minecraft has no loader build to select.');
      updateSummaryPanel();
      setTimeout(() => syncViewportHeight(), 30);
      return;
    }

    loaderVersionBox?.classList.remove('hidden');
    if (loaderVersionTitle) {
      loaderVersionTitle.textContent = `${selectedLoader} version`;
    }

    // Populate dropdown with versions for the selected loader
    const versions = await fetchLoaderVersions(selectedLoader, selectedMcVersion);
    if (loaderVersionSelect) {
      if (versions && versions.length > 0) {
        const frag = document.createDocumentFragment();
        const latestOpt = document.createElement('option');
        latestOpt.value = 'latest';
        latestOpt.textContent = `Latest (${versions[0]})`;
        frag.appendChild(latestOpt);

        versions.forEach(v => {
          const opt = document.createElement('option');
          opt.value = v;
          opt.textContent = v;
          frag.appendChild(opt);
        });

        loaderVersionSelect.innerHTML = '';
        loaderVersionSelect.appendChild(frag);
        loaderVersionSelect.value = (selectedLoaderVersion && (selectedLoaderVersion === 'latest' || versions.includes(selectedLoaderVersion)))
          ? selectedLoaderVersion
          : 'latest';
      } else {
        loaderVersionSelect.innerHTML = '<option value="latest">Latest</option>';
        loaderVersionSelect.value = 'latest';
      }
    }

    const displayedVer = (selectedLoaderVersion && selectedLoaderVersion !== 'latest')
      ? selectedLoaderVersion
      : (versions && versions[0] ? versions[0] : 'latest');

    morphText(creatorFooterInfo, `${selectedLoader} ${displayedVer}`);
    updateSummaryPanel();
    setTimeout(() => syncViewportHeight(), 30);
  }

  loaderCards.forEach(card => {
    card.addEventListener('click', () => {
      if (card.classList.contains('disabled')) return;
      selectedLoader = card.dataset.loader || 'Fabric';
      selectedLoaderVersion = 'latest';
      updateLoaderStepUI();
    });
  });

  loaderVersionSelect?.addEventListener('change', () => {
    selectedLoaderVersion = loaderVersionSelect.value;
    const displayedVer = (selectedLoaderVersion && selectedLoaderVersion !== 'latest')
      ? selectedLoaderVersion
      : 'latest';
    morphText(creatorFooterInfo, `${selectedLoader} ${displayedVer}`);
    updateSummaryPanel();
  });

  async function populateCreatorJavaDropdown() {
    if (!javaSelect) return;
    javaSelect.innerHTML = `
      <option value="">Use Settings (Default)</option>
      <option value="__smart__">✦ Smart Java Detection (Auto)</option>
    `;
    let installs = [];
    try {
      if (window.__TAURI__?.invoke) {
        installs = await window.__TAURI__.invoke('list_java_installations');
      }
    } catch (_) {
      installs = [];
    }
    (installs || []).forEach(inst => {
      const opt = document.createElement('option');
      opt.value = inst.path;
      const ver = inst.version ? `Java ${inst.version}` : 'Java';
      const arch = inst.arch ? ` (${inst.arch})` : '';
      opt.textContent = `${ver}${arch} — ${inst.path}`;
      javaSelect.appendChild(opt);
    });
    javaSelect.value = '';
  }

  btnAdvancedToggle?.addEventListener('click', () => {
    const isExpanded = btnAdvancedToggle.getAttribute('aria-expanded') === 'true';
    btnAdvancedToggle.setAttribute('aria-expanded', String(!isExpanded));
    advancedPanel?.classList.toggle('hidden', isExpanded);
    syncViewportHeight();
  });

  function setStep(step) {
    currentStep = step;
    stepTrack?.classList.remove('step-1-active', 'step-2-active', 'step-3-active', 'step-4-active', 'step-5-active');

    // Skip assets toggle only shows on the final creation step
    if (skipAssetsWrap) {
      if (step === 5) {
        skipAssetsWrap.classList.remove('hidden');
      } else {
        skipAssetsWrap.classList.add('hidden');
      }
    }

    if (step === 1) {
      stepTrack?.classList.add('step-1-active');
      btnCancel?.classList.remove('hidden');
      btnBack?.classList.add('hidden');
      if (btnNext) {
        btnNext.textContent = 'Next';
        btnNext.disabled = false;
      }

      morphText(mainTitle, 'Choose Instance Type');
      morphText(mainSubtitle, 'Select how you want to build this profile. Mods and configurations can be customized at any time.');
      morphText(creatorFooterInfo, creatorDescriptions[selectedCreatorType] || '');
      requestAnimationFrame(() => syncViewportHeight());
    } else if (step === 2) {
      stepTrack?.classList.add('step-2-active');
      btnCancel?.classList.add('hidden');
      btnBack?.classList.remove('hidden');
      if (btnNext) {
        btnNext.textContent = 'Next';
        btnNext.disabled = false;
      }

      morphText(mainTitle, 'Installation Directory');
      morphText(mainSubtitle, 'Define where this instance will store its worlds, mods, resource packs, and config files.');
      updateFolderStepUI();
      requestAnimationFrame(() => syncViewportHeight());
    } else if (step === 3) {
      stepTrack?.classList.add('step-3-active');
      btnCancel?.classList.add('hidden');
      btnBack?.classList.remove('hidden');
      if (btnNext) {
        btnNext.textContent = 'Next';
        btnNext.disabled = false;
      }

      morphText(mainTitle, 'Minecraft Version');
      morphText(mainSubtitle, 'Select from official releases or toggle the filter to access snapshots, experimental builds, and legacy versions.');
      morphText(creatorFooterInfo, `${selectedMcVersion || 'This version'} will be verified and downloaded automatically.`);
      loadVersions();
      requestAnimationFrame(() => syncViewportHeight());
    } else if (step === 4) {
      stepTrack?.classList.add('step-4-active');
      btnCancel?.classList.add('hidden');
      btnBack?.classList.remove('hidden');
      if (btnNext) {
        btnNext.textContent = 'Next';
        btnNext.disabled = false;
      }

      morphText(mainTitle, 'Mod Loader');
      morphText(mainSubtitle, `Select a mod loader for ${selectedMcVersion || 'your chosen version'}, or pick Vanilla for an unmodified game experience.`);
      updateLoaderStepUI();
      requestAnimationFrame(() => syncViewportHeight());
    } else if (step === 5) {
      stepTrack?.classList.add('step-5-active');
      btnCancel?.classList.add('hidden');
      btnBack?.classList.remove('hidden');
      if (btnNext) {
        btnNext.textContent = 'Create instance';
        btnNext.disabled = false;
      }

      // Default instance name placeholder based on chosen version and loader
      const defaultName = `${selectedLoader === 'Vanilla' ? 'Vanilla' : selectedLoader} ${selectedMcVersion || ''}`.trim();
      if (instNameInput && !instNameInput.value) {
        instNameInput.placeholder = defaultName || '26.3 Fabric';
      }

      morphText(mainTitle, 'Configure & Finalize');
      morphText(mainSubtitle, 'Give your instance a name and optionally tweak Java and memory settings.');
      morphText(creatorFooterInfo, 'All instance assets and configurations are saved locally on your device.');
      populateCreatorJavaDropdown();
      requestAnimationFrame(() => syncViewportHeight());
    }

    updateSummaryPanel();
  }

  function updateFolderStepUI() {
    folderOptionCards.forEach(c => {
      c.classList.toggle('active', c.dataset.folder === selectedFolderType);
    });

    if (selectedFolderType === 'custom') {
      customPathBox?.classList.remove('hidden');
      if (customPathInput && !customPathInput.value && customFolderPath) {
        customPathInput.value = customFolderPath;
      }
      validateCustomPath();
    } else {
      customPathBox?.classList.add('hidden');
      customPathError?.classList.add('hidden');
      if (btnNext) btnNext.disabled = false;
    }

    morphText(creatorFooterInfo, folderDescriptions[selectedFolderType] || '');
    updateSummaryPanel();
    setTimeout(() => syncViewportHeight(), 30);
  }

  function validateCustomPath() {
    if (selectedFolderType !== 'custom') {
      if (btnNext) btnNext.disabled = false;
      return true;
    }
    const val = (customPathInput?.value || customFolderPath || '').trim();
    if (!val) {
      customPathError?.classList.remove('hidden');
      if (btnNext) btnNext.disabled = true;
      syncViewportHeight();
      return false;
    }
    customPathError?.classList.add('hidden');
    if (btnNext) btnNext.disabled = false;
    syncViewportHeight();
    return true;
  }

  customPathInput?.addEventListener('input', () => {
    customFolderPath = customPathInput.value.trim();
    validateCustomPath();
  });

  btnBrowseCustom?.addEventListener('click', async () => {
    try {
      const openDialog = window.__TAURI__?.dialog?.open;
      if (openDialog) {
        const picked = await openDialog({ directory: true, multiple: false });
        if (picked) {
          const path = Array.isArray(picked) ? picked[0] : picked;
          if (customPathInput) customPathInput.value = path;
          customFolderPath = path;
          validateCustomPath();
        }
      }
    } catch (e) {
      console.warn('Folder browse error:', e);
    }
  });

  folderOptionCards.forEach(card => {
    card.addEventListener('click', () => {
      selectedFolderType = card.dataset.folder || 'isolated';
      updateFolderStepUI();
    });
  });

  btnBack?.addEventListener('click', () => {
    if (currentStep === 5) {
      setStep(4);
    } else if (currentStep === 4) {
      setStep(3);
    } else if (currentStep === 3) {
      setStep(2);
    } else if (currentStep === 2) {
      setStep(1);
    }
  });

  function openCreatorSection() {
    selectedLoader = 'Fabric';
    selectedLoaderVersion = 'latest';
    if (!selectedFolderType) selectedFolderType = 'isolated';
    if (skipAssetsCheckbox) skipAssetsCheckbox.checked = true;
    if (alwaysOfflineCheckbox) alwaysOfflineCheckbox.checked = false;
    if (instNameInput) instNameInput.value = '';
    if (btnAdvancedToggle && advancedPanel) {
      btnAdvancedToggle.setAttribute('aria-expanded', 'false');
      advancedPanel.classList.add('hidden');
    }
    if (!selectedMcVersion && versionsManifest.length > 0) {
      const firstRelease = versionsManifest.find(v => v.type === 'release');
      selectedMcVersion = firstRelease ? firstRelease.id : (versionsManifest[0]?.id || '1.21.1');
    }
    setStep(1);
    transitionBackground(typeToImageKey(selectedCreatorType));
    if (selectedCreatorType === 'minecraft-instance') {
      startMinecraftSlideshow();
    } else {
      stopMinecraftSlideshow();
    }

    if (closeTimeout) {
      clearTimeout(closeTimeout);
      closeTimeout = null;
    }

    document.body.classList.add('creator-active');

    // Pause heavy background systems (Canvas particle loop, WebGL 3D skin viewers)
    if (typeof BG !== 'undefined' && BG.pause) {
      try { BG.pause(); } catch (e) {}
    }
    if (typeof skinMiniPreviewInstance !== 'undefined' && skinMiniPreviewInstance) {
      skinMiniPreviewInstance.renderPaused = true;
    }

    if (newInstanceSection) {
      newInstanceSection.classList.remove('creator-closing');
      newInstanceSection.classList.remove('creator-hidden');
    }
    if (topBar) {
      topBar.classList.add('top-bar-creator-hidden');
    }
    setTimeout(() => syncViewportHeight(), 10);
    // Background load versions manifest if not loaded yet so default version is ready
    if (!isVersionsLoaded) {
      loadVersions();
    }
  }

  let closeTimeout = null;

  function closeCreatorSection() {
    if (!newInstanceSection || newInstanceSection.classList.contains('creator-hidden') || newInstanceSection.classList.contains('creator-closing')) {
      return;
    }
    stopMinecraftSlideshow();
    if (hoverBgTimer) {
      clearTimeout(hoverBgTimer);
      hoverBgTimer = null;
    }
    if (topBar) {
      topBar.classList.remove('top-bar-creator-hidden');
    }
    newInstanceSection.classList.add('creator-closing');

    // Defer hiding and step reset until the 360ms exit animation completes
    if (closeTimeout) clearTimeout(closeTimeout);
    closeTimeout = setTimeout(() => {
      document.body.classList.remove('creator-active');
      if (newInstanceSection) {
        newInstanceSection.classList.remove('creator-closing');
        newInstanceSection.classList.add('creator-hidden');
      }
      // Resume background engine and 3D player skin preview
      if (typeof BG !== 'undefined' && BG.resume) {
        try { BG.resume(); } catch (e) {}
      }
      if (typeof showSkinMiniPreview === 'function') {
        try { showSkinMiniPreview(); } catch (e) {}
      } else if (typeof skinMiniPreviewInstance !== 'undefined' && skinMiniPreviewInstance) {
        skinMiniPreviewInstance.renderPaused = false;
      }
      setStep(1);
      closeTimeout = null;
    }, 360);
  }

  btnNewInstance?.addEventListener('click', () => {
    openCreatorSection();
  });

  let hoverBgTimer = null;

  creatorOptionCards.forEach(card => {
    // Click: select option immediately and transition background
    card.addEventListener('click', () => {
      if (card.classList.contains('disabled')) return;
      if (hoverBgTimer) {
        clearTimeout(hoverBgTimer);
        hoverBgTimer = null;
      }
      creatorOptionCards.forEach(c => c.classList.remove('active'));
      card.classList.add('active');
      selectedCreatorType = card.dataset.type || 'minecraft-instance';
      if (creatorFooterInfo && creatorDescriptions[selectedCreatorType]) {
        creatorFooterInfo.textContent = creatorDescriptions[selectedCreatorType];
      }
      if (selectedCreatorType === 'minecraft-instance') {
        startMinecraftSlideshow();
      } else {
        stopMinecraftSlideshow();
      }
      transitionBackground(typeToImageKey(selectedCreatorType));
      updateSummaryPanel();
    });

    // Hover: preview background image only after hovering for more than 1 second (1000ms)
    // Also pause the 5-second slideshow while hovering
    card.addEventListener('mouseenter', () => {
      if (card.classList.contains('disabled')) return;
      if (hoverBgTimer) {
        clearTimeout(hoverBgTimer);
        hoverBgTimer = null;
      }
      const hoverType = card.dataset.type;
      // Pause slideshow while cursor is exploring other options
      stopMinecraftSlideshow();

      hoverBgTimer = setTimeout(() => {
        transitionBackground(typeToImageKey(hoverType));
      }, 1000);
    });

    card.addEventListener('mouseleave', () => {
      if (hoverBgTimer) {
        clearTimeout(hoverBgTimer);
        hoverBgTimer = null;
      }
    });
  });

  // When cursor leaves the options list, return to currently selected card's background and resume slideshow
  creatorOptionsList?.addEventListener('mouseleave', () => {
    if (hoverBgTimer) {
      clearTimeout(hoverBgTimer);
      hoverBgTimer = null;
    }
    transitionBackground(typeToImageKey(selectedCreatorType));
    if (selectedCreatorType === 'minecraft-instance') {
      startMinecraftSlideshow();
    }
  });

  btnClose?.addEventListener('click', closeCreatorSection);
  btnCancel?.addEventListener('click', closeCreatorSection);

  btnNext?.addEventListener('click', async () => {
    // Step 1 -> Step 2
    if (currentStep === 1 && selectedCreatorType === 'minecraft-instance') {
      if (!selectedFolderType) selectedFolderType = 'isolated';
      setStep(2);
      return;
    }

    // Step 2 -> Step 3: Choose a version
    if (currentStep === 2 && selectedCreatorType === 'minecraft-instance') {
      if (selectedFolderType === 'custom' && !validateCustomPath()) {
        return;
      }
      setStep(3);
      return;
    }

    // Step 3 -> Step 4: Choose a mod loader
    if (currentStep === 3 && selectedCreatorType === 'minecraft-instance') {
      setStep(4);
      return;
    }

    // Step 4 -> Step 5: Name the instance & advanced options
    if (currentStep === 4 && selectedCreatorType === 'minecraft-instance') {
      setStep(5);
      return;
    }

    // Step 5: Create and install the instance
    if (currentStep === 5 && selectedCreatorType === 'minecraft-instance') {
      const typedName = instNameInput?.value?.trim();
      const defaultName = `${selectedLoader === 'Vanilla' ? 'Vanilla' : selectedLoader} ${selectedMcVersion || ''}`.trim();
      const finalName = typedName || defaultName || 'Instance';

      const minRamVal = minRamInput?.value ? parseInt(minRamInput.value, 10) : null;
      const maxRamVal = maxRamInput?.value ? parseInt(maxRamInput.value, 10) : null;
      const javaPathVal = javaSelect?.value || '';
      const jvmArgsVal = jvmArgsInput?.value?.trim() || '';
      const alwaysOfflineVal = !!alwaysOfflineCheckbox?.checked;
      const skipAssetsVal = !!skipAssetsCheckbox?.checked;

      closeCreatorSection();
      if (typeof onProceedWithMinecraftInstance === 'function') {
        await onProceedWithMinecraftInstance({
          name: finalName,
          folderType: selectedFolderType,
          customPath: customFolderPath,
          version: selectedMcVersion,
          loader: selectedLoader,
          loaderVersion: selectedLoader === 'Vanilla' ? '' : (selectedLoaderVersion || 'latest'),
          javaPath: javaPathVal,
          minRam: minRamVal,
          maxRam: maxRamVal,
          jvmArgs: jvmArgsVal,
          alwaysOffline: alwaysOfflineVal,
          skipAssets: skipAssetsVal
        });
      }
      return;
    }

    // Other types (OneClient, Modpack)
    closeCreatorSection();
    if (selectedCreatorType === 'oneclient') {
      if (typeof onProceedWithMinecraftInstance === 'function') {
        await onProceedWithMinecraftInstance({
          name: 'OneClient',
          folderType: 'isolated',
          customPath: ''
        });
      }
    } else if (selectedCreatorType === 'modpack') {
      const discoverModpackBtn = document.getElementById('discover-type-modpack');
      const discoverTabBtn = document.querySelector('.pill-tab[data-tab="discover"]');
      if (discoverTabBtn) {
        discoverTabBtn.click();
        if (discoverModpackBtn) {
          setTimeout(() => discoverModpackBtn.click(), 120);
        }
      }
    }
  });

  return {
    openCreatorSection,
    closeCreatorSection,
    setStep,
    getSelectedType: () => selectedCreatorType,
    getSelectedFolderType: () => selectedFolderType,
    getCustomFolderPath: () => customFolderPath
  };
}
