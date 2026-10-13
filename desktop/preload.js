const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('goob', {
  getState: () => ipcRenderer.invoke('goob:get-state'),
  pickStorage: () => ipcRenderer.invoke('goob:pick-storage'),
  onTunnelUrl: (cb) => ipcRenderer.on('tunnel-url', (_e, url) => cb(url)),
});
