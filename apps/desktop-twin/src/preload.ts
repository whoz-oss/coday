import type { ContextBridge, IpcRenderer } from 'electron'

const { contextBridge, ipcRenderer } = require('electron') as {
  contextBridge: ContextBridge
  ipcRenderer: IpcRenderer
}

/**
 * Preload script for Coday Twin Desktop
 *
 * This script runs in a privileged context and can expose
 * safe APIs to the renderer process through contextBridge.
 */

interface CodayDesktopStorageAPI {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<boolean>
  remove(key: string): Promise<boolean>
  clear(): Promise<boolean>
}

interface CodayDesktopLogsAPI {
  getPath(): Promise<string>
  openFolder(): Promise<void>
}

interface CodayDesktopSetupAPI {
  browseFolder(): Promise<string | null>
  confirmSetup(customPath: string | null): Promise<void>
  checkAnthropicApiKey(): Promise<{ configured: boolean; source: string | null }>
  saveAnthropicApiKey(apiKey: string): Promise<void>
  preferencesGetCurrentPath(): Promise<string | null>
  preferencesBrowseFolder(): Promise<string | null>
  preferencesSave(newPath: string): Promise<void>
  preferencesClose(): Promise<void>
}

interface CodayDesktopAPI {
  platform: NodeJS.Platform
  version: string
  storage: CodayDesktopStorageAPI
  logs: CodayDesktopLogsAPI
}

// Expose app version, platform info, storage API, and logs API
contextBridge.exposeInMainWorld('codayDesktop', {
  platform: process.platform,
  version: process.env['npm_package_version'] ?? 'unknown',
  storage: {
    get: (key: string) => ipcRenderer.invoke('storage:get', key),
    set: (key: string, value: string) => ipcRenderer.invoke('storage:set', key, value),
    remove: (key: string) => ipcRenderer.invoke('storage:remove', key),
    clear: () => ipcRenderer.invoke('storage:clear'),
  } as CodayDesktopStorageAPI,
  logs: {
    getPath: () => ipcRenderer.invoke('logs:getPath'),
    openFolder: () => ipcRenderer.invoke('logs:openFolder'),
  } as CodayDesktopLogsAPI,
} as CodayDesktopAPI)

// Expose setup-specific APIs (used by setup.html and preferences.html)
contextBridge.exposeInMainWorld('electronAPI', {
  browseFolder: () => ipcRenderer.invoke('setup:browseFolder'),
  confirmSetup: (customPath: string | null) => ipcRenderer.invoke('setup:confirm', customPath),
  checkAnthropicApiKey: () => ipcRenderer.invoke('setup:checkAnthropicApiKey'),
  saveAnthropicApiKey: (apiKey: string) => ipcRenderer.invoke('setup:saveAnthropicApiKey', apiKey),
  preferencesGetCurrentPath: () => ipcRenderer.invoke('preferences:getCurrentPath'),
  preferencesBrowseFolder: () => ipcRenderer.invoke('preferences:browseFolder'),
  preferencesSave: (newPath: string) => ipcRenderer.invoke('preferences:save', newPath),
  preferencesClose: () => ipcRenderer.invoke('preferences:close'),
} as CodayDesktopSetupAPI)

console.log('Coday Twin Desktop preload script loaded')

// Extend the Window interface for TypeScript
declare global {
  interface Window {
    codayDesktop: CodayDesktopAPI
    electronAPI: CodayDesktopSetupAPI
  }
}
