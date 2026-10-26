const { app, BrowserWindow, WebContentsView, ipcMain, session, dialog } = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');

app.setName('FireFox GX');

let window;
let browser;
let browserSession;
let browserBounds = { x: 242, y: 72, width: 0, height: 0 };
let browserActive = false;
const chromeCandidates = new Map();

function storeFile() {
  return path.join(app.getPath('userData'), 'extensions.json');
}

async function inspectExtension(extensionPath) {
  const absolutePath = path.resolve(extensionPath);
  const manifest = JSON.parse(await fs.readFile(path.join(absolutePath, 'manifest.json'), 'utf8'));
  if (![2, 3].includes(manifest.manifest_version)) throw new Error('Only Manifest V2 and Manifest V3 extensions are supported.');
  return {
    path: absolutePath,
    name: manifest.name || path.basename(absolutePath),
    version: manifest.version || 'unknown',
    manifestVersion: manifest.manifest_version,
    description: manifest.description || '',
    permissions: [...new Set([...(manifest.permissions || []), ...(manifest.host_permissions || [])])],
    popup: manifest.action?.default_popup || manifest.browser_action?.default_popup || ''
  };
}

async function persistExtensions() {
  const records = browserSession.extensions.getAllExtensions().map(({ id, path: extensionPath }) => ({ id, path: extensionPath }));
  await fs.mkdir(app.getPath('userData'), { recursive: true });
  await fs.writeFile(storeFile(), JSON.stringify(records, null, 2), 'utf8');
}

async function listExtensions() {
  return Promise.all(browserSession.extensions.getAllExtensions().map(async (extension) => {
    let details = {};
    try { details = await inspectExtension(extension.path); } catch {}
    return {
      id: extension.id,
      path: extension.path,
      name: extension.name || details.name || 'Chrome extension',
      version: extension.version || details.version || '',
      manifestVersion: details.manifestVersion || 0,
      description: details.description || '',
      permissions: details.permissions || [],
      popup: details.popup || ''
    };
  }));
}

async function announceExtensions() {
  if (window && !window.isDestroyed()) window.webContents.send('extensions:list', await listExtensions());
}

async function installExtension(extensionPath) {
  const source = await inspectExtension(extensionPath);
  const safeName = source.name.replace(/[^a-z0-9_-]+/gi, '-').replace(/^-+|-+$/g, '').slice(0, 48) || 'extension';
  const pathHash = crypto.createHash('sha256').update(source.path.toLowerCase()).digest('hex').slice(0, 10);
  const managedRoot = path.join(app.getPath('userData'), 'extensions');
  const managedPath = path.join(managedRoot, `${safeName}-${source.version}-${pathHash}`);
  await fs.mkdir(managedRoot, { recursive: true });
  try { await fs.access(path.join(managedPath, 'manifest.json')); }
  catch { await fs.cp(source.path, managedPath, { recursive: true }); }
  const details = await inspectExtension(managedPath);
  const extension = await browserSession.extensions.loadExtension(details.path, { allowFileAccess: false });
  await persistExtensions();
  await announceExtensions();
  return {
    ok: true,
    name: extension.name || details.name,
    id: extension.id,
    version: extension.version || details.version,
    manifestVersion: details.manifestVersion,
    permissions: details.permissions
  };
}

async function loadSavedExtensions() {
  let records = [];
  try { records = JSON.parse(await fs.readFile(storeFile(), 'utf8')); } catch {}
  if (!Array.isArray(records)) return;
  for (const record of records) {
    if (!record || typeof record.path !== 'string') continue;
    try {
      const details = await inspectExtension(record.path);
      await browserSession.extensions.loadExtension(details.path, { allowFileAccess: false });
    } catch (error) { console.warn(`Could not restore extension: ${error.message}`); }
  }
}

async function scanChromeProfiles() {
  chromeCandidates.clear();
  const chromeRoot = process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'User Data');
  if (!chromeRoot) return [];
  let profiles = [];
  try {
    profiles = (await fs.readdir(chromeRoot, { withFileTypes: true }))
      .filter((entry) => entry.isDirectory() && (entry.name === 'Default' || entry.name.startsWith('Profile ')))
      .map((entry) => entry.name);
  } catch { return []; }
  const alreadyLoaded = new Set((await listExtensions()).map((extension) => extension.id));
  const seen = new Set(alreadyLoaded);
  const found = [];
  for (const profile of profiles) {
    let entries = [];
    try { entries = await fs.readdir(path.join(chromeRoot, profile, 'Extensions'), { withFileTypes: true }); } catch { continue; }
    for (const entry of entries.filter((item) => item.isDirectory() && !seen.has(item.name))) {
      const idFolder = path.join(chromeRoot, profile, 'Extensions', entry.name);
      let versions = [];
      try { versions = await fs.readdir(idFolder, { withFileTypes: true }); } catch { continue; }
      for (const version of versions.filter((item) => item.isDirectory()).reverse()) {
        const extensionPath = path.join(idFolder, version.name);
        try {
          const details = await inspectExtension(extensionPath);
          const key = `${profile}:${entry.name}`;
          chromeCandidates.set(key, extensionPath);
          found.push({ key, id: entry.name, profile, ...details });
          seen.add(entry.name);
          break;
        } catch {}
      }
    }
  }
  return found;
}

function applyBrowserBounds() {
  if (!browser || !window) return;
  browser.setBounds(browserBounds);
  browser.setVisible(browserActive);
}

function publishBrowserState() {
  if (!browser || !window || window.isDestroyed()) return;
  window.webContents.send('browser:state', {
    url: browser.webContents.getURL(),
    title: browser.webContents.getTitle(),
    canGoBack: browser.webContents.navigationHistory.canGoBack(),
    canGoForward: browser.webContents.navigationHistory.canGoForward(),
    loading: browser.webContents.isLoading(),
    active: browserActive
  });
}

function loadInBrowser(url) {
  const extensionMatch = /^chrome-extension:\/\/([a-p]{32})\//.exec(url);
  const trustedExtensionPage = extensionMatch && browserSession.extensions.getExtension(extensionMatch[1]);
  if (!browser || (!/^https?:\/\//i.test(url) && !trustedExtensionPage)) return false;
  browserActive = true;
  applyBrowserBounds();
  browser.webContents.loadURL(url).catch(() => publishBrowserState());
  return true;
}

function createWindow() {
  window = new BrowserWindow({
    width: 1440,
    height: 940,
    minWidth: 980,
    minHeight: 680,
    backgroundColor: '#090b12',
    title: 'FireFox GX',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  browser = new WebContentsView({
    webPreferences: {
      session: browserSession,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });
  window.contentView.addChildView(browser);
  applyBrowserBounds();
  for (const event of ['did-navigate', 'did-navigate-in-page', 'page-title-updated', 'did-start-loading', 'did-stop-loading', 'did-fail-load']) {
    browser.webContents.on(event, publishBrowserState);
  }
  browser.webContents.setWindowOpenHandler(({ url }) => {
    loadInBrowser(url);
    return { action: 'deny' };
  });
  browser.webContents.on('will-navigate', (event, url) => {
    const extensionMatch = /^chrome-extension:\/\/([a-p]{32})\//.exec(url);
    const trustedExtensionPage = extensionMatch && browserSession.extensions.getExtension(extensionMatch[1]);
    if (!/^https?:\/\//i.test(url) && url !== 'about:blank' && !trustedExtensionPage) event.preventDefault();
  });
  window.loadFile('index.html');
  window.on('resize', applyBrowserBounds);
  window.on('closed', () => { browser = null; window = null; });
}

ipcMain.handle('browser:navigate', (event, url) => {
  if (!window || event.sender !== window.webContents || typeof url !== 'string') return false;
  return loadInBrowser(url);
});
ipcMain.handle('browser:history', (event, direction) => {
  if (!window || event.sender !== window.webContents || !browser) return false;
  const history = browser.webContents.navigationHistory;
  if (direction === 'back' && history.canGoBack()) history.goBack();
  if (direction === 'forward' && history.canGoForward()) history.goForward();
  return true;
});
ipcMain.handle('browser:reload', (event) => {
  if (!window || event.sender !== window.webContents || !browser) return false;
  browser.webContents.reload();
  return true;
});
ipcMain.handle('browser:home', (event) => {
  if (!window || event.sender !== window.webContents) return false;
  browserActive = false;
  applyBrowserBounds();
  publishBrowserState();
  return true;
});
ipcMain.handle('browser:bounds', (event, rect) => {
  if (!window || event.sender !== window.webContents || !rect) return false;
  const values = ['x', 'y', 'width', 'height'].map((key) => Number(rect[key]));
  if (!values.every(Number.isFinite)) return false;
  const [x, y, width, height] = values;
  browserBounds = { x: Math.max(0, Math.round(x)), y: Math.max(0, Math.round(y)), width: Math.max(0, Math.round(width)), height: Math.max(0, Math.round(height)) };
  applyBrowserBounds();
  return true;
});

ipcMain.handle('extensions:list', async (event) => {
  if (!window || event.sender !== window.webContents) return [];
  return listExtensions();
});
ipcMain.handle('extensions:add-folder', async (event) => {
  if (!window || event.sender !== window.webContents) return { ok: false };
  const result = await dialog.showOpenDialog(window, { title: 'Choose an unpacked Chrome extension folder', properties: ['openDirectory'] });
  if (result.canceled || !result.filePaths[0]) return { ok: false, cancelled: true };
  try { return await installExtension(result.filePaths[0]); }
  catch (error) { return { ok: false, error: error.message }; }
});
ipcMain.handle('extensions:scan-chrome', async (event) => {
  if (!window || event.sender !== window.webContents) return [];
  return scanChromeProfiles();
});
ipcMain.handle('extensions:add-chrome', async (event, key) => {
  if (!window || event.sender !== window.webContents || typeof key !== 'string') return { ok: false };
  const extensionPath = chromeCandidates.get(key);
  if (!extensionPath) return { ok: false, error: 'That Chrome extension is no longer available. Scan again.' };
  try { return await installExtension(extensionPath); }
  catch (error) { return { ok: false, error: error.message }; }
});
ipcMain.handle('extensions:remove', async (event, id) => {
  if (!window || event.sender !== window.webContents || typeof id !== 'string') return false;
  try { browserSession.extensions.removeExtension(id); await persistExtensions(); await announceExtensions(); return true; }
  catch { return false; }
});
ipcMain.handle('extensions:open-popup', async (event, id) => {
  if (!window || event.sender !== window.webContents || typeof id !== 'string') return false;
  const extension = browserSession.extensions.getExtension(id);
  if (!extension) return false;
  let details;
  try { details = await inspectExtension(extension.path); } catch { return false; }
  if (!details.popup) return false;
  const popupPath = path.resolve(extension.path, details.popup);
  if (!popupPath.startsWith(`${extension.path}${path.sep}`) && popupPath !== extension.path) return false;
  browserActive = true;
  applyBrowserBounds();
  await browser.webContents.loadURL(`chrome-extension://${id}/${path.relative(extension.path, popupPath).split(path.sep).join('/')}`);
  publishBrowserState();
  return true;
});

app.whenReady().then(async () => {
  browserSession = session.fromPartition('persist:firefox-gx');
  await loadSavedExtensions();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
