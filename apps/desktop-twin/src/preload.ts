import type { ContextBridge, IpcRenderer } from 'electron'
import type { CodayDesktopAPI } from '@coday/desktop-core'
import { registerCodayDesktopApi } from '@coday/desktop-core'

const { contextBridge, ipcRenderer } = require('electron') as {
  contextBridge: ContextBridge
  ipcRenderer: IpcRenderer
}

/**
 * Preload script for CodayTwin Desktop
 *
 * This script runs in a privileged context and can expose
 * safe APIs to the renderer process through contextBridge.
 */

interface SlackConfig {
  enabled: boolean
  webhookUrl: string
  userId: string
}

interface GoogleCalendarConfig {
  clientId: string
  clientSecret: string
}

interface CodayDesktopSetupAPI {
  browseFolder(): Promise<string | null>
  confirmSetup(
    customPath: string | null,
    slackConfig: SlackConfig | null,
    googleConfig: GoogleCalendarConfig | null
  ): Promise<void>
  checkAnthropicApiKey(): Promise<{ configured: boolean; source: string | null }>
  saveAnthropicApiKey(apiKey: string): Promise<void>
  checkOpenAiApiKey(): Promise<{ configured: boolean; source: string | null }>
  saveOpenAiApiKey(apiKey: string): Promise<void>
  preferencesGetCurrentPath(): Promise<string | null>
  preferencesBrowseFolder(): Promise<string | null>
  preferencesSave(newPath: string): Promise<void>
  preferencesClose(): Promise<void>
  preferencesGetSlackConfig(): Promise<SlackConfig>
  preferencesSaveSlackConfig(config: SlackConfig): Promise<void>
  preferencesGetGoogleCalendarConfig(): Promise<GoogleCalendarConfig>
  preferencesSaveGoogleCalendarConfig(config: GoogleCalendarConfig): Promise<void>
  resizeToContent(height: number): Promise<void>
}

// Expose shared app version, platform info, storage API, and logs API
registerCodayDesktopApi(contextBridge, ipcRenderer)

// Expose setup-specific APIs (used by setup.html and preferences.html)
contextBridge.exposeInMainWorld('electronAPI', {
  browseFolder: () => ipcRenderer.invoke('setup:browseFolder'),
  confirmSetup: (
    customPath: string | null,
    slackConfig: SlackConfig | null,
    googleConfig: GoogleCalendarConfig | null
  ) => ipcRenderer.invoke('setup:confirm', customPath, slackConfig, googleConfig),
  checkAnthropicApiKey: () => ipcRenderer.invoke('setup:checkAnthropicApiKey'),
  saveAnthropicApiKey: (apiKey: string) => ipcRenderer.invoke('setup:saveAnthropicApiKey', apiKey),
  checkOpenAiApiKey: () => ipcRenderer.invoke('setup:checkOpenAiApiKey'),
  saveOpenAiApiKey: (apiKey: string) => ipcRenderer.invoke('setup:saveOpenAiApiKey', apiKey),
  preferencesGetCurrentPath: () => ipcRenderer.invoke('preferences:getCurrentPath'),
  preferencesBrowseFolder: () => ipcRenderer.invoke('preferences:browseFolder'),
  preferencesSave: (newPath: string) => ipcRenderer.invoke('preferences:save', newPath),
  preferencesClose: () => ipcRenderer.invoke('preferences:close'),
  preferencesGetSlackConfig: () => ipcRenderer.invoke('preferences:getSlackConfig'),
  preferencesSaveSlackConfig: (config: SlackConfig) => ipcRenderer.invoke('preferences:saveSlackConfig', config),
  preferencesGetGoogleCalendarConfig: () => ipcRenderer.invoke('preferences:getGoogleCalendarConfig'),
  preferencesSaveGoogleCalendarConfig: (config: GoogleCalendarConfig) =>
    ipcRenderer.invoke('preferences:saveGoogleCalendarConfig', config),
  resizeToContent: (height: number) => ipcRenderer.invoke('window:resizeToContent', height),
} as CodayDesktopSetupAPI)

console.log('CodayTwin Desktop preload script loaded')

// Extend the Window interface for TypeScript
declare global {
  interface Window {
    codayDesktop: CodayDesktopAPI
    electronAPI: CodayDesktopSetupAPI
    GoogleCalendarConfig: GoogleCalendarConfig
  }
}
