const { app, BrowserWindow, WebContentsView, ipcMain, session, dialog, shell, safeStorage } = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { inflateRawSync } = require('node:zlib');

app.setName('FireFox GX');

let window;
let browser;
let browserSession;
let browserBounds = { x: 242, y: 72, width: 0, height: 0 };
let browserActive = false;
const chromeCandidates = new Map();
const tabs = new Map();
let activeTabId = '';

function storeFile() {
  return path.join(app.getPath('userData'), 'extensions.json');
}

function safetyScan(packageData) {
  const findings = [];
  if (!packageData || packageData.format !== 'FGXE' || packageData.version !== 1 || !packageData.manifest || !packageData.files) throw new Error('This is not a supported .fgxe package.');
  const permissions = [...(packageData.manifest.permissions || []), ...(packageData.manifest.host_permissions || [])];
  if (permissions.some((p) => ['<all_urls>', 'debugger', 'nativeMessaging', 'management'].includes(p))) findings.push('Broad or privileged permissions require extra review.');
  for (const [name, source] of Object.entries(packageData.files)) {
    if (typeof source !== 'string' || name.startsWith('/') || name.includes('..') || name.includes('\\')) throw new Error('Package has an unsafe file path.');
    if (/\beval\s*\(|\bnew\s+Function\s*\(|https?:\/\/[^\s"']+\.js\b/i.test(source)) findings.push(`${name}: dynamic or remote code pattern detected.`);
  }
  return findings;
}

function writeSafeJson(file, data) { return fs.writeFile(file, JSON.stringify(data), 'utf8'); }

function makeTab(url = '') {
  const id = crypto.randomUUID();
  const view = new WebContentsView({ webPreferences: { session: browserSession, contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true } });
  tabs.set(id, view);
  window.contentView.addChildView(view);
  for (const event of ['did-navigate', 'did-navigate-in-page', 'page-title-updated', 'did-start-loading', 'did-stop-loading', 'did-fail-load']) view.webContents.on(event, () => publishBrowserState());
  view.webContents.setWindowOpenHandler(({ url: target }) => { newTab(target); return { action: 'deny' }; });
  view.webContents.on('will-navigate', (event, target) => { if (!/^https?:\/\//i.test(target) && target !== 'about:blank') event.preventDefault(); });
  if (url) view.webContents.loadURL(url).catch(() => {});
  activateTab(id);
  return id;
}

function newTab(url = '') { return makeTab(url); }
function activateTab(id) {
  if (!tabs.has(id)) return false;
  if (activeTabId && tabs.has(activeTabId)) tabs.get(activeTabId).setVisible(false);
  activeTabId = id; browser = tabs.get(id); browserActive = !!browser.webContents.getURL();
  browser.setBounds(browserBounds); browser.setVisible(browserActive); publishBrowserState(); publishTabs(); return true;
}
function publishTabs() {
  if (!window || window.isDestroyed()) return;
  window.webContents.send('browser:tabs', [...tabs].map(([id, view]) => ({ id, title: view.webContents.getTitle() || 'New tab', url: view.webContents.getURL(), active: id === activeTabId })));
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

async function unpackXpi(sourceFile, destination) {
  const archive = await fs.readFile(sourceFile);
  const end = archive.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0) throw new Error('Invalid Firefox add-on archive.');
  const count = archive.readUInt16LE(end + 10), centralOffset = archive.readUInt32LE(end + 16);
  let cursor = centralOffset, total = 0;
  for (let index = 0; index < count; index++) {
    if (archive.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Invalid archive directory.');
    const flags = archive.readUInt16LE(cursor + 8), method = archive.readUInt16LE(cursor + 10), compressed = archive.readUInt32LE(cursor + 20), expanded = archive.readUInt32LE(cursor + 24);
    const nameLength = archive.readUInt16LE(cursor + 28), extraLength = archive.readUInt16LE(cursor + 30), commentLength = archive.readUInt16LE(cursor + 32), local = archive.readUInt32LE(cursor + 42);
    const name = archive.toString('utf8', cursor + 46, cursor + 46 + nameLength).replace(/\\/g, '/');
    if (flags & 1 || name.startsWith('/') || name.includes(':') || name.split('/').includes('..')) throw new Error('Firefox add-on contains an unsafe entry.');
    const mode = archive.readUInt32LE(cursor + 38) >>> 16; if ((mode & 0o170000) === 0o120000) throw new Error('Symbolic links are not allowed in add-ons.');
    total += expanded; if (total > 64 * 1024 * 1024) throw new Error('Firefox add-on exceeds the 64 MB unpacked limit.');
    if (!name.endsWith('/')) {
      const localName = archive.readUInt16LE(local + 26), localExtra = archive.readUInt16LE(local + 28), offset = local + 30 + localName + localExtra;
      const raw = archive.subarray(offset, offset + compressed); const data = method === 0 ? raw : method === 8 ? inflateRawSync(raw) : null;
      if (!data || data.length !== expanded) throw new Error('Unsupported or damaged Firefox add-on archive.');
      const target = path.resolve(destination, name); if (!target.startsWith(`${destination}${path.sep}`)) throw new Error('Unsafe add-on path.');
      await fs.mkdir(path.dirname(target), { recursive: true }); await fs.writeFile(target, data);
    }
    cursor += 46 + nameLength + extraLength + commentLength;
  }
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
  const firefoxRoot = process.env.APPDATA && path.join(process.env.APPDATA, 'Mozilla', 'Firefox', 'Profiles');
  if (firefoxRoot) {
    let firefoxProfiles = [];
    try { firefoxProfiles = (await fs.readdir(firefoxRoot, { withFileTypes: true })).filter((entry) => entry.isDirectory()).map((entry) => entry.name); } catch {}
    for (const profile of firefoxProfiles) {
      const extensionsDir = path.join(firefoxRoot, profile, 'extensions'); let files = [];
      try { files = await fs.readdir(extensionsDir, { withFileTypes: true }); } catch { continue; }
      for (const file of files.filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.xpi'))) {
        const temp = path.join(app.getPath('temp'), `fgx-firefox-${crypto.randomUUID()}`);
        try {
          await fs.mkdir(temp, { recursive: true }); await unpackXpi(path.join(extensionsDir, file.name), temp);
          const details = await inspectExtension(temp); const key = `firefox:${profile}:${file.name}`;
          chromeCandidates.set(key, temp); found.push({ key, id: file.name, profile: `Firefox · ${profile}`, ...details });
        } catch (error) { await fs.rm(temp, { recursive: true, force: true }); console.warn(`Skipped Firefox extension ${file.name}: ${error.message}`); }
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
    active: browserActive, tabId: activeTabId
  });
}

function loadInBrowser(url) {
  const extensionMatch = /^chrome-extension:\/\/([a-p]{32})\//.exec(url);
  const trustedExtensionPage = extensionMatch && browserSession.extensions.getExtension(extensionMatch[1]);
  if (!browser || (!/^https?:\/\//i.test(url) && !trustedExtensionPage)) return false;
  browserActive = true;
  applyBrowserBounds();
  browser.webContents.loadURL(url).catch(() => publishBrowserState());
  publishTabs();
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

  makeTab();
  window.loadFile('index.html');
  window.webContents.on('did-finish-load', publishTabs);
  window.on('resize', applyBrowserBounds);
  window.on('closed', () => { browser = null; window = null; });
}

ipcMain.handle('browser:navigate', (event, url) => {
  if (!window || event.sender !== window.webContents || typeof url !== 'string') return false;
  return loadInBrowser(url);
});
ipcMain.handle('browser:new-tab', (event, url) => { if (!window || event.sender !== window.webContents) return false; return newTab(typeof url === 'string' ? url : ''); });
ipcMain.handle('browser:activate-tab', (event, id) => { if (!window || event.sender !== window.webContents) return false; return activateTab(id); });
ipcMain.handle('browser:close-tab', (event, id) => {
  if (!window || event.sender !== window.webContents || !tabs.has(id)) return false;
  const view = tabs.get(id); window.contentView.removeChildView(view); view.webContents.close(); tabs.delete(id);
  if (!tabs.size) newTab(); else if (activeTabId === id) activateTab([...tabs.keys()].at(-1));
  publishTabs(); return true;
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
ipcMain.handle('extensions:import-fgxe', async (event) => {
  if (!window || event.sender !== window.webContents) return { ok: false };
  const picked = await dialog.showOpenDialog(window, { title: 'Import FireFox GX extension package', properties: ['openFile'], filters: [{ name: 'FireFox GX Extension', extensions: ['fgxe'] }] });
  if (picked.canceled || !picked.filePaths[0]) return { ok: false, cancelled: true };
  let temp;
  try {
    const packageData = JSON.parse(await fs.readFile(picked.filePaths[0], 'utf8'));
    const findings = safetyScan(packageData);
    const approvals = await dialog.showMessageBox(window, { type: findings.length ? 'warning' : 'info', title: 'Extension safety review', message: findings.length ? 'Safety review found items to inspect.' : 'Safety review passed basic checks.', detail: [...findings, `Name: ${packageData.manifest.name || 'Extension'}`, `Permissions: ${(packageData.manifest.permissions || []).join(', ') || 'none'}`, 'This review is heuristic; only load extensions you trust.'].join('\n'), buttons: ['Cancel', 'Continue to permission review'], defaultId: 0, cancelId: 0 });
    if (approvals.response !== 1) return { ok: false, cancelled: true, findings };
    temp = path.join(app.getPath('temp'), `fgxe-${crypto.randomUUID()}`); await fs.mkdir(temp, { recursive: true });
    for (const [relative, value] of Object.entries(packageData.files)) { const out = path.resolve(temp, relative); if (!out.startsWith(`${temp}${path.sep}`)) throw new Error('Unsafe package path.'); await fs.mkdir(path.dirname(out), { recursive: true }); const match = /^data:application\/octet-stream;base64,(.*)$/s.exec(value); await fs.writeFile(out, match ? Buffer.from(match[1], 'base64') : value, 'utf8'); }
    const installed = await installExtension(temp); return { ...installed, findings };
  } catch (error) { return { ok: false, error: error.message }; }
  finally { if (temp) await fs.rm(temp, { recursive: true, force: true }); }
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

ipcMain.handle('extensions:publish-fgxe', async (event) => {
  if (!window || event.sender !== window.webContents) return { ok: false };
  const result = await dialog.showOpenDialog(window, { title: 'Choose an extension folder to package', properties: ['openDirectory'] });
  if (result.canceled || !result.filePaths[0]) return { ok: false, cancelled: true };
  try {
    const root = result.filePaths[0], manifest = JSON.parse(await fs.readFile(path.join(root, 'manifest.json'), 'utf8'));
    const files = {};
    const walk = async (dir, prefix = '') => { for (const entry of await fs.readdir(dir, { withFileTypes: true })) { const rel = path.posix.join(prefix, entry.name); if (entry.isDirectory()) await walk(path.join(dir, entry.name), rel); else { const buffer = await fs.readFile(path.join(dir, entry.name)); files[rel] = /.(js|css|json|html|txt|svg)$/i.test(entry.name) ? buffer.toString('utf8') : `data:application/octet-stream;base64,${buffer.toString('base64')}`; } } };
    await walk(root); const packageData = { format: 'FGXE', version: 1, manifest, files };
    const findings = safetyScan(packageData);
    if (findings.length) { const choice = await dialog.showMessageBox(window, { type: 'warning', title: 'Safety review', message: 'Review findings before packaging', detail: findings.join('\n'), buttons: ['Cancel', 'Package anyway'], defaultId: 0, cancelId: 0 }); if (choice.response !== 1) return { ok: false, cancelled: true, findings }; }
    const save = await dialog.showSaveDialog(window, { title: 'Publish FireFox GX extension package', defaultPath: `${(manifest.name || 'extension').replace(/[^\w-]/g, '-')}.fgxe`, filters: [{ name: 'FireFox GX Extension', extensions: ['fgxe'] }] });
    if (save.canceled || !save.filePath) return { ok: false, cancelled: true };
    await writeSafeJson(save.filePath, packageData); return { ok: true, path: save.filePath, findings };
  } catch (error) { return { ok: false, error: error.message }; }
});
ipcMain.handle('browser:download', async (event) => { if (!window || event.sender !== window.webContents) return false; await shell.openPath(app.getPath('downloads')); return true; });
ipcMain.handle('browser:proxy', async (event, value) => { if (!window || event.sender !== window.webContents || typeof value !== 'string') return false; try { const parsed = new URL(value); if (!['http:', 'https:', 'socks4:', 'socks5:'].includes(parsed.protocol) || !parsed.hostname || !parsed.port) return false; await browserSession.setProxy({ proxyRules: `${parsed.protocol}//${parsed.hostname}:${parsed.port}` }); await browserSession.closeAllConnections(); return true; } catch { return false; } });
ipcMain.handle('downloads:open', async (event) => { if (!window || event.sender !== window.webContents) return false; await shell.openPath(app.getPath('downloads')); return true; });
ipcMain.handle('aurora:save-key', async (event, key) => { if (!window || event.sender !== window.webContents || typeof key !== 'string') return false; const target = path.join(app.getPath('userData'), 'aurora-key.bin'); await fs.writeFile(target, safeStorage.encryptString(key)); return true; });
ipcMain.handle('aurora:has-key', async (event) => { if (!window || event.sender !== window.webContents) return false; try { await fs.access(path.join(app.getPath('userData'), 'aurora-key.bin')); return safeStorage.isEncryptionAvailable(); } catch { return false; } });
ipcMain.handle('aurora:ask', async (event, prompt) => {
  if (!window || event.sender !== window.webContents || typeof prompt !== 'string') return '';
  try { const raw = await fs.readFile(path.join(app.getPath('userData'), 'aurora-key.bin')); const key = safeStorage.decryptString(raw); const response = await fetch('https://api.groq.com/openai/v1/chat/completions', { method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ model: 'openai/gpt-oss-120b', messages: [{ role: 'user', content: prompt }], max_tokens: 600 }) }); if (!response.ok) throw new Error(`Groq returned ${response.status}`); const data = await response.json(); return data.choices?.[0]?.message?.content || 'No answer returned.'; } catch (error) { return `Aurora: ${error.message}`; }
});

app.whenReady().then(async () => {
  browserSession = session.fromPartition('persist:firefox-gx');
  browserSession.on('will-download', (_event, item) => { const target = path.join(app.getPath('downloads'), item.getFilename()); item.setSavePath(target); });
  await loadSavedExtensions();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
