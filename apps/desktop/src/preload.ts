import type { ContextBridge, IpcRenderer } from 'electron'
import type { CodayDesktopAPI } from '@coday/desktop-core'
import { registerCodayDesktopApi } from '@coday/desktop-core'

const { contextBridge, ipcRenderer } = require('electron') as {
  contextBridge: ContextBridge
  ipcRenderer: IpcRenderer
}

/**
 * Preload script for Coday Desktop
 *
 * This script runs in a privileged context and can expose
 * safe APIs to the renderer process through contextBridge.
 */

/**
 * Setup and preferences API — exposed as window.electronAPI.
 * Used by setup.html and preferences.html.
 * No workspace path management (desktop is multi-project, managed by ~/.coday).
 */
interface CodayDesktopSetupAPI {
  /** Setup wizard: confirm setup complete (no path, no slack, no google) */
  confirmSetup(): Promise<void>
  /** Check whether an Anthropic API key is already configured */
  checkAnthropicApiKey(): Promise<{ configured: boolean; source: string | null }>
  /** Persist an Anthropic API key to user.yaml */
  saveAnthropicApiKey(apiKey: string): Promise<void>
  /** Check whether an OpenAI API key is already configured */
  checkOpenAiApiKey(): Promise<{ configured: boolean; source: string | null }>
  /** Persist an OpenAI API key to user.yaml */
  saveOpenAiApiKey(apiKey: string): Promise<void>
  /** Preferences: close the preferences window */
  preferencesClose(): Promise<void>
  /** Resize the setup/preferences window to fit its content */
  resizeToContent(height: number): Promise<void>
}

// Expose shared app version, platform info, storage API, and logs API
registerCodayDesktopApi(contextBridge, ipcRenderer)

// Expose setup / preferences APIs (used by setup.html and preferences.html)
contextBridge.exposeInMainWorld('electronAPI', {
  confirmSetup: () => ipcRenderer.invoke('setup:confirm'),
  checkAnthropicApiKey: () => ipcRenderer.invoke('setup:checkAnthropicApiKey'),
  saveAnthropicApiKey: (apiKey: string) => ipcRenderer.invoke('setup:saveAnthropicApiKey', apiKey),
  checkOpenAiApiKey: () => ipcRenderer.invoke('setup:checkOpenAiApiKey'),
  saveOpenAiApiKey: (apiKey: string) => ipcRenderer.invoke('setup:saveOpenAiApiKey', apiKey),
  preferencesClose: () => ipcRenderer.invoke('preferences:close'),
  resizeToContent: (height: number) => ipcRenderer.invoke('window:resizeToContent', height),
} as CodayDesktopSetupAPI)

console.log('Coday Desktop preload script loaded')

// Extend the Window interface for TypeScript
declare global {
  interface Window {
    codayDesktop: CodayDesktopAPI
    electronAPI: CodayDesktopSetupAPI
  }
}
