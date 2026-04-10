import type { ContextBridge, IpcRenderer } from 'electron'

// ---------------------------------------------------------------------------
// Shared interface types
// ---------------------------------------------------------------------------

export interface CodayDesktopStorageAPI {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<boolean>
  remove(key: string): Promise<boolean>
  clear(): Promise<boolean>
}

export interface CodayDesktopLogsAPI {
  getPath(): Promise<string>
  openFolder(): Promise<void>
}

export interface CodayDesktopAPI {
  platform: NodeJS.Platform
  version: string
  storage: CodayDesktopStorageAPI
  logs: CodayDesktopLogsAPI
}

// ---------------------------------------------------------------------------
// Registration helper
// ---------------------------------------------------------------------------

/**
 * Expose the shared `codayDesktop` object via contextBridge.
 * Called from each app's preload.ts.
 */
export function registerCodayDesktopApi(contextBridge: ContextBridge, ipcRenderer: IpcRenderer): void {
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
}
