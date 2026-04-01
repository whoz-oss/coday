import type { IpcMainInvokeEvent } from 'electron'
import { checkApiKey, saveApiKeyToUserYaml } from './ai-config'
import { log } from './logger'

/**
 * Register the four API-key IPC handlers used by setup and preferences windows.
 *  - setup:checkAnthropicApiKey
 *  - setup:saveAnthropicApiKey
 *  - setup:checkOpenAiApiKey
 *  - setup:saveOpenAiApiKey
 */
export function registerApiKeyHandlers(ipcMain: typeof import('electron').ipcMain): void {
  // Remove before re-registering to stay idempotent (safe against double-calls)
  ipcMain.removeHandler('setup:checkAnthropicApiKey')
  ipcMain.removeHandler('setup:saveAnthropicApiKey')
  ipcMain.removeHandler('setup:checkOpenAiApiKey')
  ipcMain.removeHandler('setup:saveOpenAiApiKey')

  ipcMain.handle('setup:checkAnthropicApiKey', () => checkApiKey('anthropic', 'ANTHROPIC_API_KEY'))

  ipcMain.handle('setup:saveAnthropicApiKey', (_event: IpcMainInvokeEvent, apiKey: string) => {
    saveApiKeyToUserYaml('anthropic', apiKey)
  })

  ipcMain.handle('setup:checkOpenAiApiKey', () => checkApiKey('openai', 'OPENAI_API_KEY'))

  ipcMain.handle('setup:saveOpenAiApiKey', (_event: IpcMainInvokeEvent, apiKey: string) => {
    saveApiKeyToUserYaml('openai', apiKey)
  })

  log('INFO', 'API key IPC handlers registered')
}

/**
 * Remove the four API-key IPC handlers.
 */
export function removeApiKeyHandlers(ipcMain: typeof import('electron').ipcMain): void {
  ipcMain.removeHandler('setup:checkAnthropicApiKey')
  ipcMain.removeHandler('setup:saveAnthropicApiKey')
  ipcMain.removeHandler('setup:checkOpenAiApiKey')
  ipcMain.removeHandler('setup:saveOpenAiApiKey')
}
