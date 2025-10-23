import type { ContextBridge, IpcRenderer } from 'electron'

const { contextBridge, ipcRenderer } = require('electron') as {
  contextBridge: ContextBridge
  ipcRenderer: IpcRenderer
}

/**
 * Preload script for Coday Desktop
 *
 * This script runs in a privileged context and can expose
 * safe APIs to the renderer process through contextBridge.
 *
 * Currently, the Coday web interface doesn't require any
 * special Electron APIs, so this is minimal. It can be
 * extended in the future if needed.
 */

interface CodayDesktopStorageAPI {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<boolean>
  remove(key: string): Promise<boolean>
  clear(): Promise<boolean>
}

interface CodayDesktopAPI {
  platform: NodeJS.Platform
  version: string
  storage: CodayDesktopStorageAPI
}

// Expose app version, platform info, and storage API
contextBridge.exposeInMainWorld('codayDesktop', {
  platform: process.platform,
  version: process.env['npm_package_version'] ?? 'unknown',
  storage: {
    get: (key: string) => ipcRenderer.invoke('storage:get', key),
    set: (key: string, value: string) => ipcRenderer.invoke('storage:set', key, value),
    remove: (key: string) => ipcRenderer.invoke('storage:remove', key),
    clear: () => ipcRenderer.invoke('storage:clear'),
  } as CodayDesktopStorageAPI,
} as CodayDesktopAPI)

console.log('Coday Desktop preload script loaded')

// Extend the Window interface for TypeScript
declare global {
  interface Window {
    codayDesktop: CodayDesktopAPI
  }
}
