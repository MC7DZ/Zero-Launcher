/**
 * Initializes the "Choose a type" / New Instance Creator section:
 * - Downloads / caches background picture from:
 *   https://github.com/MC7DZ/ZeroLauncher-Updates/blob/main/pictures/minecraft-wilderness-bound.jpg
 *   into "<Zero Launcher directory>/assets/background/minecraft-wilderness-bound.jpg"
 * - Hides the top-bar and custom titlebar elements while active
 * - Matches the native Zero Launcher UI design system (panels, borders, radii, fonts, accent colors)
 *
 * @param {Object} options
 * @param {Function} options.onProceedWithMinecraftInstance Callback when proceeding with Minecraft/OneClient instance
 */
export function initInstanceCreator({ onProceedWithMinecraftInstance } = {}) {
  const newInstanceSection = document.getElementById('new-instance-section');
  const creatorBackdrop = document.querySelector('.new-instance-bg-backdrop');
  const topBar = document.getElementById('top-bar');
  const btnNewInstance = document.getElementById('btn-new-instance');
  const btnClose = document.getElementById('btn-creator-close');
  const btnCancel = document.getElementById('btn-creator-cancel');
  const btnNext = document.getElementById('btn-creator-next');
  const creatorOptionCards = document.querySelectorAll('.creator-option-card');
  const creatorFooterInfo = document.getElementById('creator-footer-info');

  async function loadCreatorBackground() {
    try {
      const invoke = window.__TAURI__?.core?.invoke;
      const convertFileSrc = window.__TAURI__?.core?.convertFileSrc;
      if (invoke) {
        const localPath = await invoke('ensure_creator_background');
        if (localPath && creatorBackdrop) {
          const url = convertFileSrc ? convertFileSrc(localPath) : localPath;
          creatorBackdrop.style.backgroundImage = `url("${url}")`;
          return;
        }
      }
    } catch (e) {
      console.warn('Failed to fetch creator background from backend, falling back to direct URL:', e);
    }

    // Direct fallback if backend download fails or in browser dev preview
    if (creatorBackdrop) {
      creatorBackdrop.style.backgroundImage = 'url("https://raw.githubusercontent.com/MC7DZ/ZeroLauncher-Updates/main/pictures/minecraft-wilderness-bound.jpg")';
    }
  }

  // Preload/download the background image
  loadCreatorBackground();

  let selectedCreatorType = 'minecraft-instance';

  const creatorDescriptions = {
    'minecraft-instance': 'Pick a loader and version, then add mods yourself. Has its own game folder.',
    'oneclient': 'The most bleeding edge performance, QoL mods and world hosting in one instance. Shares configs, worlds, and packs with your other OneClient instances, or gets its own folder.',
    'modpack': 'Install a pack from Modrinth or CurseForge, or a file you downloaded. Has its own game folder.',
    'import': 'Open an instance someone exported from OneClient, or bring one over from another launcher. Has its own game folder.'
  };

  function openCreatorSection() {
    // Re-verify image loaded in case first attempt was offline
    if (creatorBackdrop && !creatorBackdrop.style.backgroundImage) {
      loadCreatorBackground();
    }
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
  }

  btnNewInstance?.addEventListener('click', () => {
    openCreatorSection();
  });

  creatorOptionCards.forEach(card => {
    card.addEventListener('click', () => {
      if (card.classList.contains('disabled')) return;
      creatorOptionCards.forEach(c => c.classList.remove('active'));
      card.classList.add('active');
      selectedCreatorType = card.dataset.type || 'minecraft-instance';
      if (creatorFooterInfo && creatorDescriptions[selectedCreatorType]) {
        creatorFooterInfo.textContent = creatorDescriptions[selectedCreatorType];
      }
    });
  });

  btnClose?.addEventListener('click', closeCreatorSection);
  btnCancel?.addEventListener('click', closeCreatorSection);

  btnNext?.addEventListener('click', async () => {
    closeCreatorSection();
    if (selectedCreatorType === 'minecraft-instance') {
      if (typeof onProceedWithMinecraftInstance === 'function') {
        await onProceedWithMinecraftInstance('');
      }
    } else if (selectedCreatorType === 'oneclient') {
      if (typeof onProceedWithMinecraftInstance === 'function') {
        await onProceedWithMinecraftInstance('OneClient');
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
    getSelectedType: () => selectedCreatorType
  };
}
