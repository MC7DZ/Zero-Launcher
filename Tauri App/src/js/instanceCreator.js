/**
 * Initializes the "Choose a type" / New Instance Creator section:
 * - Downloads / caches background pictures from GitHub into "<Zero Launcher directory>/assets/background/":
 *     - minecraft-wilderness-bound.jpg (Minecraft instance, Import)
 *     - oneclient.jpeg (OneClient)
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
    'oneclient': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/oneclient.jpeg',
    'modpack': 'https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/modpacks.jpg'
  };

  const cachedUrls = {
    'minecraft': '',
    'oneclient': '',
    'modpack': ''
  };

  const preloadedImages = {
    'minecraft': null,
    'oneclient': null,
    'modpack': null
  };

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
    return 'minecraft';
  }

  let activeLayer = 'a'; // 'a' or 'b'
  let currentImageKey = '';
  let transitionToken = 0;

  async function transitionBackground(key) {
    if (!key || key === currentImageKey) return;
    const thisToken = ++transitionToken;

    const url = await resolveImageSrc(key);
    // If a newer switch occurred while awaiting, abort
    if (thisToken !== transitionToken) return;
    if (!layerA || !layerB) return;

    // Ensure the image bitmap is decoded in memory before swapping
    if (!preloadedImages[key]) {
      await preloadImageElement(url);
      if (thisToken !== transitionToken) return;
    }

    currentImageKey = key;

    if (activeLayer === 'a') {
      layerB.style.backgroundImage = `url("${url}")`;
      layerB.style.opacity = '1';
      layerA.style.opacity = '0';
      activeLayer = 'b';
    } else {
      layerA.style.backgroundImage = `url("${url}")`;
      layerA.style.opacity = '1';
      layerB.style.opacity = '0';
      activeLayer = 'a';
    }
  }

  // Pre-load all three images immediately in the background
  ['minecraft', 'oneclient', 'modpack'].forEach(async (key) => {
    const url = await resolveImageSrc(key);
    const img = await preloadImageElement(url);
    preloadedImages[key] = img;
    if (key === 'minecraft' && layerA && !layerA.style.backgroundImage) {
      layerA.style.backgroundImage = `url("${url}")`;
      currentImageKey = 'minecraft';
    }
  });

  let currentStep = 1; // 1: Choose a type, 2: Choose a game folder
  let selectedCreatorType = 'minecraft-instance';
  let selectedFolderType = 'isolated'; // 'shared', 'isolated' (default in middle), 'custom'
  let customFolderPath = '';

  const creatorDescriptions = {
    'minecraft-instance': 'Pick a loader and version, then add mods yourself. Has its own game folder.',
    'oneclient': 'The most bleeding edge performance, QoL mods and world hosting in one instance. Shares configs, worlds, and packs with your other OneClient instances, or gets its own folder.',
    'modpack': 'Install a pack from Modrinth or CurseForge, or a file you downloaded. Has its own game folder.',
    'import': 'Open an instance someone exported from OneClient, or bring one over from another launcher. Has its own game folder.'
  };

  const folderDescriptions = {
    'shared': 'Uses the standard root game directory (~/.minecraft). All saves and packs are shared.',
    'isolated': 'Gets its own folder under !Instances. Worlds, settings and mods stay separate and clean.',
    'custom': 'Uses a custom folder of your choice on disk.'
  };

  // Step DOM elements
  const step1Elements = document.querySelectorAll('.creator-step-1-el');
  const step2Elements = document.querySelectorAll('.creator-step-2-el');
  const btnCloseStep2 = document.getElementById('btn-creator-close-step2');
  const btnBack = document.getElementById('btn-creator-back');
  const folderOptionCards = document.querySelectorAll('#creator-list-step-2 .creator-option-card');
  const customPathBox = document.getElementById('creator-custom-path-box');
  const customPathInput = document.getElementById('creator-custom-path-input');
  const customPathError = document.getElementById('creator-custom-path-error');
  const btnBrowseCustom = document.getElementById('btn-creator-browse-custom');
  const sharedPathHint = document.getElementById('creator-shared-path-hint');

  function setStep(step) {
    currentStep = step;
    if (step === 1) {
      step1Elements.forEach(el => el.classList.remove('hidden'));
      step2Elements.forEach(el => el.classList.add('hidden'));
      if (creatorFooterInfo) {
        creatorFooterInfo.textContent = creatorDescriptions[selectedCreatorType] || '';
      }
    } else if (step === 2) {
      step1Elements.forEach(el => el.classList.add('hidden'));
      step2Elements.forEach(el => el.classList.remove('hidden'));
      updateFolderStepUI();
    }
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

    if (creatorFooterInfo) {
      creatorFooterInfo.textContent = folderDescriptions[selectedFolderType] || '';
    }
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
      return false;
    }
    customPathError?.classList.add('hidden');
    if (btnNext) btnNext.disabled = false;
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
    setStep(1);
  });

  function openCreatorSection() {
    setStep(1);
    transitionBackground(typeToImageKey(selectedCreatorType));

    if (newInstanceSection) {
      newInstanceSection.classList.remove('creator-hidden');
    }
    if (topBar) {
      topBar.classList.add('top-bar-creator-hidden');
    }
  }

  function closeCreatorSection() {
    if (newInstanceSection) {
      newInstanceSection.classList.add('creator-hidden');
    }
    if (topBar) {
      topBar.classList.remove('top-bar-creator-hidden');
    }
    setStep(1);
  }

  btnNewInstance?.addEventListener('click', () => {
    openCreatorSection();
  });

  creatorOptionCards.forEach(card => {
    // Click: select option and transition background
    card.addEventListener('click', () => {
      if (card.classList.contains('disabled')) return;
      creatorOptionCards.forEach(c => c.classList.remove('active'));
      card.classList.add('active');
      selectedCreatorType = card.dataset.type || 'minecraft-instance';
      if (creatorFooterInfo && creatorDescriptions[selectedCreatorType]) {
        creatorFooterInfo.textContent = creatorDescriptions[selectedCreatorType];
      }
      transitionBackground(typeToImageKey(selectedCreatorType));
    });

    // Hover: preview background image smoothly
    card.addEventListener('mouseenter', () => {
      if (card.classList.contains('disabled')) return;
      const hoverType = card.dataset.type;
      transitionBackground(typeToImageKey(hoverType));
    });
  });

  // When cursor leaves the options list, return to currently selected card's background
  creatorOptionsList?.addEventListener('mouseleave', () => {
    transitionBackground(typeToImageKey(selectedCreatorType));
  });

  btnClose?.addEventListener('click', closeCreatorSection);
  btnCloseStep2?.addEventListener('click', closeCreatorSection);
  btnCancel?.addEventListener('click', closeCreatorSection);

  btnNext?.addEventListener('click', async () => {
    // If on Step 1 and selected option is 'minecraft-instance', advance to Step 2: Choose a game folder
    if (currentStep === 1 && selectedCreatorType === 'minecraft-instance') {
      setStep(2);
      return;
    }

    // If on Step 2 (Folder selection for Minecraft instance)
    if (currentStep === 2) {
      if (selectedFolderType === 'custom' && !validateCustomPath()) {
        return;
      }
      closeCreatorSection();
      if (typeof onProceedWithMinecraftInstance === 'function') {
        await onProceedWithMinecraftInstance({
          name: '',
          folderType: selectedFolderType,
          customPath: customFolderPath
        });
      }
      return;
    }

    // Other types
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
