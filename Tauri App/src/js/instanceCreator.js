import newInstanceBgImg from '../assets/new-instance-bg.jpg';

/**
 * Initializes the "Choose a type" / New Instance Creator section:
 * - Fullscreen background with screenshot
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

  if (creatorBackdrop && newInstanceBgImg) {
    creatorBackdrop.style.backgroundImage = `url("${newInstanceBgImg}")`;
  }

  let selectedCreatorType = 'minecraft-instance';

  const creatorDescriptions = {
    'minecraft-instance': 'Pick a loader and version, then add mods yourself. Has its own game folder.',
    'oneclient': 'The most bleeding edge performance, QoL mods and world hosting in one instance. Shares configs, worlds, and packs with your other OneClient instances, or gets its own folder.',
    'modpack': 'Install a pack from Modrinth or CurseForge, or a file you downloaded. Has its own game folder.',
    'import': 'Open an instance someone exported from OneClient, or bring one over from another launcher. Has its own game folder.'
  };

  function openCreatorSection() {
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
