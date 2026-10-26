const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('firefoxGX', {
  navigate: (url) => ipcRenderer.invoke('browser:navigate', url),
  history: (direction) => ipcRenderer.invoke('browser:history', direction),
  reload: () => ipcRenderer.invoke('browser:reload'),
  home: () => ipcRenderer.invoke('browser:home'),
  setBounds: (rect) => ipcRenderer.invoke('browser:bounds', rect),
  listExtensions: () => ipcRenderer.invoke('extensions:list'),
  addExtensionFolder: () => ipcRenderer.invoke('extensions:add-folder'),
  scanChromeExtensions: () => ipcRenderer.invoke('extensions:scan-chrome'),
  addChromeExtension: (key) => ipcRenderer.invoke('extensions:add-chrome', key),
  removeExtension: (id) => ipcRenderer.invoke('extensions:remove', id),
  openExtensionPopup: (id) => ipcRenderer.invoke('extensions:open-popup', id),
  onState: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on('browser:state', listener);
    return () => ipcRenderer.removeListener('browser:state', listener);
  },
  onExtensions: (callback) => {
    const listener = (_event, extensions) => callback(extensions);
    ipcRenderer.on('extensions:list', listener);
    return () => ipcRenderer.removeListener('extensions:list', listener);
  }
});
