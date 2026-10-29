const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('firefoxGX', {
  navigate: (url) => ipcRenderer.invoke('browser:navigate', url),
  newTab: (url) => ipcRenderer.invoke('browser:new-tab', url),
  activateTab: (id) => ipcRenderer.invoke('browser:activate-tab', id),
  closeTab: (id) => ipcRenderer.invoke('browser:close-tab', id),
  download: () => ipcRenderer.invoke('browser:download'),
  openDownloads: () => ipcRenderer.invoke('downloads:open'),
  setProxy: (value) => ipcRenderer.invoke('browser:proxy', value),
  saveAuroraKey: (key) => ipcRenderer.invoke('aurora:save-key', key),
  hasAuroraKey: () => ipcRenderer.invoke('aurora:has-key'),
  askAurora: (prompt) => ipcRenderer.invoke('aurora:ask', prompt),
  history: (direction) => ipcRenderer.invoke('browser:history', direction),
  reload: () => ipcRenderer.invoke('browser:reload'),
  home: () => ipcRenderer.invoke('browser:home'),
  setBounds: (rect) => ipcRenderer.invoke('browser:bounds', rect),
  listExtensions: () => ipcRenderer.invoke('extensions:list'),
  addExtensionFolder: () => ipcRenderer.invoke('extensions:add-folder'),
  importFgxe: () => ipcRenderer.invoke('extensions:import-fgxe'),
  publishFgxe: () => ipcRenderer.invoke('extensions:publish-fgxe'),
  scanChromeExtensions: () => ipcRenderer.invoke('extensions:scan-chrome'),
  addChromeExtension: (key) => ipcRenderer.invoke('extensions:add-chrome', key),
  removeExtension: (id) => ipcRenderer.invoke('extensions:remove', id),
  openExtensionPopup: (id) => ipcRenderer.invoke('extensions:open-popup', id),
  onState: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on('browser:state', listener);
    return () => ipcRenderer.removeListener('browser:state', listener);
  },
  onTabs: (callback) => { const listener = (_event, items) => callback(items); ipcRenderer.on('browser:tabs', listener); return () => ipcRenderer.removeListener('browser:tabs', listener); },
  onExtensions: (callback) => {
    const listener = (_event, extensions) => callback(extensions);
    ipcRenderer.on('extensions:list', listener);
    return () => ipcRenderer.removeListener('extensions:list', listener);
  }
});
