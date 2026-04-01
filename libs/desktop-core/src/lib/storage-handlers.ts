import { log } from './logger'
import type { IpcMain } from 'electron'

const fs = require('fs') as typeof import('fs')

/**
 * Register IPC handlers for preferences.json storage and log file access.
 * Guards against double-registration.
 */
export function setupStorageHandlers(ipcMain: IpcMain, storageFile: string, logFile: string): void {
  // Get storage data
  ipcMain.handle('storage:get', (_event, key: string): string | null => {
    try {
      if (!fs.existsSync(storageFile)) return null
      const data = fs.readFileSync(storageFile, 'utf8')
      const storage = JSON.parse(data)
      return storage[key] ?? null
    } catch (error) {
      log('ERROR', 'Failed to read storage:', error)
      return null
    }
  })

  // Set storage data
  ipcMain.handle('storage:set', (_event, key: string, value: string): boolean => {
    try {
      let storage: Record<string, string> = {}
      if (fs.existsSync(storageFile)) {
        const data = fs.readFileSync(storageFile, 'utf8')
        storage = JSON.parse(data)
      }
      storage[key] = value
      fs.writeFileSync(storageFile, JSON.stringify(storage, null, 2), 'utf8')
      return true
    } catch (error) {
      log('ERROR', 'Failed to write storage:', error)
      return false
    }
  })

  // Remove storage data
  ipcMain.handle('storage:remove', (_event, key: string): boolean => {
    try {
      if (!fs.existsSync(storageFile)) return true
      const data = fs.readFileSync(storageFile, 'utf8')
      const storage = JSON.parse(data)
      delete storage[key]
      fs.writeFileSync(storageFile, JSON.stringify(storage, null, 2), 'utf8')
      return true
    } catch (error) {
      log('ERROR', 'Failed to remove from storage:', error)
      return false
    }
  })

  // Clear all storage
  ipcMain.handle('storage:clear', (): boolean => {
    try {
      if (fs.existsSync(storageFile)) fs.unlinkSync(storageFile)
      return true
    } catch (error) {
      log('ERROR', 'Failed to clear storage:', error)
      return false
    }
  })

  // Get log file path
  ipcMain.handle('logs:getPath', (): string => logFile)

  // Open logs folder
  ipcMain.handle('logs:openFolder', (): void => {
    const { shell } = require('electron') as typeof import('electron')
    shell.showItemInFolder(logFile)
  })

  log('INFO', 'Storage handlers initialized')
}
