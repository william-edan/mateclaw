import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('mateclawDesktop', {
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
})

ipcRenderer.on('startup-error', (_event, message: string) => {
  window.dispatchEvent(new CustomEvent('mateclaw-startup-error', { detail: message }))
})

ipcRenderer.on('startup-warning', (_event, message: string) => {
  window.dispatchEvent(new CustomEvent('mateclaw-startup-warning', { detail: message }))
})
