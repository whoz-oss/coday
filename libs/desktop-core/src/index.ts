// Logger
export { initLogger, log } from './lib/logger'

// Executable discovery
export { findExecutable } from './lib/find-executable'

// AI API key helpers
export { checkApiKey, saveApiKeyToUserYaml } from './lib/ai-config'

// Storage IPC handlers
export { setupStorageHandlers } from './lib/storage-handlers'

// Environment setup
export { setupEnv } from './lib/env-setup'

// Server lifecycle
export type { ServerConfig } from './lib/server-lifecycle'
export { startCodayServer, stopCodayServer, isServerResponsive } from './lib/server-lifecycle'

// Window management
export type { MainWindowConfig } from './lib/window-manager'
export {
  createLoadingWindow,
  createMainWindow,
  showErrorDialog,
  registerResizeHandler,
  removeResizeHandler,
} from './lib/window-manager'

// Deep-link handling
export { parseDeepLink, navigateToDeepLink } from './lib/deeplink'

// Setup/preferences IPC helpers
export { registerApiKeyHandlers, removeApiKeyHandlers } from './lib/setup-ipc'

// Preload contextBridge API
export type { CodayDesktopAPI, CodayDesktopStorageAPI, CodayDesktopLogsAPI } from './lib/preload-api'
export { registerCodayDesktopApi } from './lib/preload-api'
