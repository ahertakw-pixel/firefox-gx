if (!window.firefoxGX && window.AndroidBridge) {
  const stateListeners = [];
  const extensionListeners = [];
  window.__firefoxGXState = (state) => stateListeners.forEach((listener) => listener(state));
  window.__firefoxGXExtensions = (extensions) => extensionListeners.forEach((listener) => listener(extensions));
  window.__firefoxGXImportResult = (result) => window.__firefoxGXImportCallback?.(result);
  window.firefoxGX = {
    platform: 'android',
    extensionPopupSupported: false,
    navigate: (url) => window.AndroidBridge.navigate(url),
    newTab: (url) => window.AndroidBridge.newTab(url || ''),
    activateTab: (id) => window.AndroidBridge.activateTab(id),
    closeTab: (id) => window.AndroidBridge.closeTab(id),
    download: () => window.AndroidBridge.download(),
    openDownloads: () => Promise.resolve(window.AndroidBridge.openDownloads()),
    setProxy: (value) => Promise.resolve(window.AndroidBridge.setProxy(value)),
    saveAuroraKey: (key) => Promise.resolve(window.AndroidBridge.saveAuroraKey(key)),
    hasAuroraKey: () => Promise.resolve(window.AndroidBridge.hasAuroraKey()),
    askAurora: (prompt) => Promise.resolve(window.AndroidBridge.askAurora(prompt)),
    history: (direction) => window.AndroidBridge.history(direction),
    reload: () => window.AndroidBridge.reload(),
    home: () => window.AndroidBridge.home(),
    setBounds: (rect) => window.AndroidBridge.setBounds(rect.x, rect.y, rect.width, rect.height),
    listExtensions: () => Promise.resolve(JSON.parse(window.AndroidBridge.getExtensionsJson())),
    addExtensionFolder: () => new Promise((resolve) => {
      window.__firefoxGXImportCallback = resolve;
      window.AndroidBridge.importExtensionZip();
    }),
    importFgxe: () => new Promise((resolve) => { window.__firefoxGXImportCallback = resolve; window.AndroidBridge.importFgxe(); }),
    publishFgxe: () => Promise.resolve(window.AndroidBridge.publishFgxe()),
    scanChromeExtensions: () => Promise.resolve([]),
    addChromeExtension: () => Promise.resolve({ ok: false, error: 'Chrome mobile does not provide desktop extension folders. Import an unpacked extension ZIP.' }),
    removeExtension: (id) => Promise.resolve(window.AndroidBridge.removeExtension(id)),
    openExtensionPopup: () => Promise.resolve(false),
    onTabs: (callback) => { window.__firefoxGXTabs = callback; callback([{ id: 'android-main', title: 'New tab', url: '', active: true }]); },
    onState: (callback) => stateListeners.push(callback),
    onExtensions: (callback) => extensionListeners.push(callback)
  };
  document.addEventListener('DOMContentLoaded', () => window.AndroidBridge.reportUiReady());
}

const address = document.getElementById('address');
const heroInput = document.getElementById('heroInput');
const startPage = document.getElementById('startPage');
const extensionsPage = document.getElementById('extensionsPage');
const devPage = document.getElementById('devPage');
const toolsPage = document.getElementById('toolsPage');
const browserbar = document.querySelector('.browserbar');
const themePopover = document.getElementById('themePopover');
const themeOptions = [...document.querySelectorAll('[data-theme-choice]')];
const themes = ['neon', 'arcade', 'frost', 'ember'];
let chromeFound = [];
let activeUiTabId = '';

function resolveQuery(input) {
  const value = input.trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value)) return value;
  if (/^(localhost|127\.0\.0\.1)(:\d+)?(\/.*)?$/i.test(value) || /^(\[[\da-f:]+\])(:\d+)?(\/.*)?$/i.test(value)) return `http://${value}`;
  if (/^[\w-]+\.[a-z]{2,}(:\d+)?(\/\S*)?$/i.test(value) && !/\s/.test(value)) return `https://${value}`;
  return `https://www.google.com/search?q=${encodeURIComponent(value)}`;
}

function navigate(input) {
  const command = input.trim().replace(/\s+/g, '').toLowerCase();
  if (command === '://extensions' || command === 'extensions://') return showExtensions();
  if (command === '://devextensions' || command === 'devextensions://') return showDevExtensions();
  if (command === '://tools' || command === 'tools://') return showTools();
  const url = resolveQuery(input);
  if (!url) return;
  showStartPage();
  address.value = url;
  window.firefoxGX.navigate(url);
}

function syncBrowserBounds() {
  const mainRect = document.querySelector('.main').getBoundingClientRect();
  const top = document.getElementById('tabStrip').getBoundingClientRect().bottom;
  window.firefoxGX.setBounds({ x: mainRect.left, y: top, width: mainRect.width, height: window.innerHeight - top });
}

function setActiveNav(id) {
  document.querySelectorAll('.nav-item').forEach((item) => item.classList.toggle('active', item.id === id));
}

function showStartPage() {
  extensionsPage.hidden = devPage.hidden = toolsPage.hidden = true;
  startPage.hidden = false;
  setActiveNav('startNav');
  window.firefoxGX.home();
  syncBrowserBounds();
}

function showExtensions() {
  devPage.hidden = toolsPage.hidden = true;
  extensionsPage.hidden = false;
  startPage.hidden = true;
  setActiveNav('extensionsNav');
  window.firefoxGX.home();
  window.firefoxGX.listExtensions().then(renderInstalledExtensions);
  syncBrowserBounds();
}

function showDevExtensions() { extensionsPage.hidden = toolsPage.hidden = startPage.hidden = true; devPage.hidden = false; window.firefoxGX.home(); syncBrowserBounds(); }
function showTools() { extensionsPage.hidden = devPage.hidden = startPage.hidden = true; toolsPage.hidden = false; window.firefoxGX.home(); syncBrowserBounds(); }

function renderTabs(items) {
  const strip = document.getElementById('tabStrip');
  strip.querySelectorAll('.browser-tab').forEach((el) => el.remove());
  for (const item of items) {
    const tab = document.createElement('button'); tab.className = `browser-tab${item.active ? ' active' : ''}`; tab.title = item.url || 'New tab';
    const title = document.createElement('span'); title.textContent = item.title || 'New tab';
    const close = document.createElement('i'); close.textContent = '×'; close.title = 'Close tab';
    close.addEventListener('click', (event) => { event.stopPropagation(); window.firefoxGX.closeTab(item.id); });
    tab.append(title, close); tab.addEventListener('click', () => window.firefoxGX.activateTab(item.id)); strip.insertBefore(tab, document.getElementById('newTabButton'));
  }
}

function setTheme(theme) {
  if (!themes.includes(theme)) return;
  document.body.dataset.theme = theme;
  localStorage.setItem('firefox-gx-theme', theme);
  themeOptions.forEach((button) => button.classList.toggle('selected', button.dataset.themeChoice === theme));
}

function addPermissionLine(container, permissions) {
  const line = document.createElement('small');
  line.className = 'permission-line';
  line.textContent = permissions?.length ? `Permissions: ${permissions.slice(0, 4).join(', ')}${permissions.length > 4 ? ` +${permissions.length - 4} more` : ''}` : 'No declared permissions';
  container.append(line);
}

function renderChromeExtensions(items) {
  chromeFound = items;
  const container = document.getElementById('chromeResults');
  const count = document.getElementById('chromeCount');
  count.textContent = String(items.length);
  container.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.innerHTML = '<span class="empty-glyph">⌕</span><b>No compatible profile extensions found</b><small>Install an extension in Chrome or Firefox desktop, or import an unpacked folder/package.</small>';
    container.append(empty);
    return;
  }
  for (const item of items) {
    const card = document.createElement('article');
    card.className = 'extension-row';
    const mark = document.createElement('span');
    mark.className = 'extension-mark';
    mark.textContent = item.name.slice(0, 1).toUpperCase();
    const info = document.createElement('div');
    info.className = 'extension-row-info';
    const title = document.createElement('b');
    title.textContent = item.name;
    const meta = document.createElement('small');
    meta.textContent = `v${item.version} · ${String(item.profile).startsWith('Firefox') ? item.profile : `Chrome · ${item.profile}`}`;
    info.append(title, meta);
    addPermissionLine(info, item.permissions);
    const button = document.createElement('button');
    button.className = 'row-button';
    button.textContent = 'ADD';
    button.addEventListener('click', async () => {
      button.disabled = true;
      button.textContent = 'ADDING…';
      const result = await window.firefoxGX.addChromeExtension(item.key);
      if (!result.ok) {
        button.disabled = false;
        button.textContent = 'RETRY';
        document.getElementById('scanStatus').textContent = result.error || 'Could not add this extension.';
      } else {
        button.textContent = 'ADDED';
        button.classList.add('done');
        chromeFound = chromeFound.filter((candidate) => candidate.key !== item.key);
        document.getElementById('scanStatus').textContent = `${result.name} is now loaded in FireFox GX.`;
        renderChromeExtensions(chromeFound);
      }
    });
    card.append(mark, info, button);
    container.append(card);
  }
}

function renderInstalledExtensions(items) {
  const container = document.getElementById('installedExtensions');
  document.getElementById('installedCount').textContent = String(items.length);
  container.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.innerHTML = '<span class="empty-glyph">✳</span><b>No extensions added yet</b><small>Add from Chrome or load an unpacked folder to get started.</small>';
    container.append(empty);
    return;
  }
  for (const item of items) {
    const card = document.createElement('article');
    card.className = 'extension-row installed-row';
    const mark = document.createElement('span');
    mark.className = 'extension-mark active-mark';
    mark.textContent = item.name.slice(0, 1).toUpperCase();
    const info = document.createElement('div');
    info.className = 'extension-row-info';
    const title = document.createElement('b');
    title.textContent = item.name;
    const meta = document.createElement('small');
    meta.textContent = `v${item.version} · Manifest ${item.manifestVersion || '—'}`;
    info.append(title, meta);
    addPermissionLine(info, item.permissions);
    const actions = document.createElement('div');
    actions.className = 'row-actions';
    if (item.popup && window.firefoxGX.extensionPopupSupported !== false) {
      const open = document.createElement('button');
      open.className = 'row-button small-row-button';
      open.textContent = 'OPEN';
      open.title = 'Open extension popup in this browser view';
      open.addEventListener('click', () => window.firefoxGX.openExtensionPopup(item.id));
      actions.append(open);
    }
    const remove = document.createElement('button');
    remove.className = 'remove-button';
    remove.textContent = 'REMOVE';
    remove.addEventListener('click', async () => {
      if (await window.firefoxGX.removeExtension(item.id)) document.getElementById('scanStatus').textContent = `${item.name} removed from FireFox GX.`;
    });
    actions.append(remove);
    card.append(mark, info, actions);
    container.append(card);
  }
}

document.getElementById('addressForm').addEventListener('submit', (event) => {
  event.preventDefault();
  navigate(address.value);
});
document.getElementById('heroSearch').addEventListener('submit', (event) => {
  event.preventDefault();
  navigate(heroInput.value);
});
document.querySelectorAll('[data-url]').forEach((button) => button.addEventListener('click', () => navigate(button.dataset.url)));
document.querySelectorAll('[data-action="home"], #home, #brandHome').forEach((button) => button.addEventListener('click', showStartPage));
document.getElementById('extensionsNav').addEventListener('click', showExtensions);
document.getElementById('newTabButton').addEventListener('click', () => { showStartPage(); window.firefoxGX.newTab(''); });
document.getElementById('downloadButton').addEventListener('click', () => window.firefoxGX.download());
document.getElementById('auroraButton').addEventListener('click', showTools);
document.getElementById('importFgxe').addEventListener('click', async () => { const r = await window.firefoxGX.importFgxe(); if (r.ok) document.getElementById('scanStatus').textContent = `${r.name} imported after safety review.`; else if (!r.cancelled) document.getElementById('scanStatus').textContent = r.error || 'Package import cancelled.'; });
document.getElementById('publishFgxe').addEventListener('click', async () => { const r = await window.firefoxGX.publishFgxe(); document.getElementById('scanStatus').textContent = r.ok ? `Published package: ${r.path}` : r.error || 'Package publishing cancelled.'; });
document.getElementById('devImport').addEventListener('click', () => document.getElementById('addFolder').click());
document.getElementById('devPublish').addEventListener('click', () => document.getElementById('publishFgxe').click());
document.getElementById('proxyApply').addEventListener('click', async () => { const status = document.getElementById('proxyStatus'); status.textContent = await window.firefoxGX.setProxy(document.getElementById('proxyUrl').value) ? 'Proxy applied to browser traffic.' : 'Proxy settings are not supported on this build.'; });
document.getElementById('openDownloads').addEventListener('click', () => window.firefoxGX.openDownloads());
document.getElementById('saveGroqKey').addEventListener('click', async () => { const key = document.getElementById('groqKey').value.trim(); if (!key) return; document.getElementById('auroraStatus').textContent = await window.firefoxGX.saveAuroraKey(key) ? 'Saved locally.' : 'Could not save this key.'; document.getElementById('groqKey').value = ''; });
document.getElementById('askAurora').addEventListener('click', async () => { const answer = document.getElementById('auroraAnswer'); answer.hidden = false; answer.textContent = 'Aurora is thinking…'; answer.textContent = await window.firefoxGX.askAurora(document.getElementById('auroraPrompt').value); });
document.getElementById('back').addEventListener('click', () => window.firefoxGX.history('back'));
document.getElementById('forward').addEventListener('click', () => window.firefoxGX.history('forward'));
document.getElementById('reload').addEventListener('click', () => window.firefoxGX.reload());
document.getElementById('chooseTheme').addEventListener('click', () => themePopover.classList.toggle('open'));
document.getElementById('closeThemes').addEventListener('click', () => themePopover.classList.remove('open'));
document.getElementById('themeCycle').addEventListener('click', () => {
  const index = themes.indexOf(document.body.dataset.theme);
  setTheme(themes[(index + 1) % themes.length]);
});
document.getElementById('scanChrome').addEventListener('click', async () => {
  const status = document.getElementById('scanStatus');
  const button = document.getElementById('scanChrome');
  if (window.firefoxGX.platform === 'android') {
    status.textContent = 'Android does not expose Chrome or Firefox private profile folders. Use Import .FGXE or choose an extension ZIP.';
    return;
  }
  button.disabled = true;
  status.textContent = 'Scanning Chrome and Firefox profiles…';
  try {
    const items = await window.firefoxGX.scanChromeExtensions();
    renderChromeExtensions(items);
    status.textContent = items.length ? `Found ${items.length} extension${items.length === 1 ? '' : 's'} across Chrome and Firefox profiles.` : 'No compatible extensions found in desktop browser profiles.';
  } catch {
    status.textContent = 'Could not read Chrome profiles on this device.';
  } finally { button.disabled = false; }
});
document.getElementById('addFolder').addEventListener('click', async () => {
  const button = document.getElementById('addFolder');
  button.disabled = true;
  if (window.firefoxGX.platform === 'android') button.textContent = 'CHOOSE ZIP…';
  else button.textContent = 'LOADING…';
  const result = await window.firefoxGX.addExtensionFolder();
  button.disabled = false;
  button.innerHTML = window.firefoxGX.platform === 'android' ? '<svg viewBox="0 0 24 24"><path d="M12 5v14m-7-7h14"/></svg>IMPORT ZIP' : '<svg viewBox="0 0 24 24"><path d="M12 5v14m-7-7h14"/></svg>ADD EXTENSION';
  if (result.ok) document.getElementById('scanStatus').textContent = `${result.name} loaded into FireFox GX.`;
  else if (!result.cancelled) document.getElementById('scanStatus').textContent = result.error || 'Could not load that extension folder.';
});
themeOptions.forEach((button) => button.addEventListener('click', () => setTheme(button.dataset.themeChoice)));
setTheme(localStorage.getItem('firefox-gx-theme') || 'neon');

window.firefoxGX.onState((state) => {
  if (!state) return;
  const switchedToBlankTab = !!state.tabId && !!activeUiTabId && state.tabId !== activeUiTabId && !state.url;
  if (state.tabId) activeUiTabId = state.tabId;
  if (switchedToBlankTab) showStartPage();
  if (state.active && state.url && state.url !== 'about:blank' && !state.url.startsWith('chrome-extension://')) {
    address.value = state.url;
    startPage.hidden = true;
    extensionsPage.hidden = devPage.hidden = toolsPage.hidden = true;
    setActiveNav('');
  }
  document.title = state.title ? `${state.title} — FireFox GX` : 'FireFox GX';
  document.getElementById('back').disabled = !state.canGoBack;
  document.getElementById('forward').disabled = !state.canGoForward;
  document.getElementById('reload').classList.toggle('loading', state.loading);
  document.getElementById('secureIcon').classList.toggle('insecure', state.url.startsWith('http://'));
});
window.firefoxGX.onExtensions(renderInstalledExtensions);
window.firefoxGX.onTabs(renderTabs);
window.firefoxGX.listExtensions().then(renderInstalledExtensions);
window.firefoxGX.hasAuroraKey().then((saved) => { if (saved) document.getElementById('auroraStatus').textContent = 'Key already stored securely on this device.'; });

if (window.firefoxGX.platform === 'android') {
  const addButton = document.getElementById('addFolder');
  addButton.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 5v14m-7-7h14"/></svg>IMPORT ZIP';
  document.getElementById('scanStatus').textContent = 'Import extension ZIP or .fgxe packages from your device.';
  document.querySelector('.manager-heading p').textContent = 'Import a ZIP containing an unpacked Chrome extension. FireFox GX can run compatible page scripts on websites.';
  document.querySelector('.extension-note span:last-child').innerHTML = '<b>Mobile compatibility is limited.</b> Android WebView cannot run all Chrome extension APIs. This port imports ZIPs and injects compatible content scripts and styles into matching websites; service workers, toolbar actions, and many extension APIs are not supported. Only import extensions you trust.';
  document.getElementById('proxyApply').disabled = true;
  document.getElementById('openDownloads').disabled = true;
}

const resizeObserver = new ResizeObserver(syncBrowserBounds);
resizeObserver.observe(document.querySelector('.shell'));
window.addEventListener('resize', syncBrowserBounds);
window.addEventListener('load', syncBrowserBounds);
setTimeout(syncBrowserBounds, 150);
