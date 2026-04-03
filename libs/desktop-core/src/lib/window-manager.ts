import type { BrowserWindow as BrowserWindowType, IpcMainInvokeEvent } from 'electron'
import { log } from './logger'

export interface MainWindowConfig {
  BrowserWindow: typeof import('electron').BrowserWindow
  ipcMain: typeof import('electron').ipcMain
  serverUrl: string
  iconPath: string
  preloadPath: string
  title: string
  /** Optional hook called after the window is created (e.g. for CSS injection) */
  onWindowCreated?: (win: BrowserWindowType) => void
}

/**
 * Create a frameless, transparent loading/splash window.
 */
export function createLoadingWindow(
  BrowserWindow: typeof import('electron').BrowserWindow,
  iconPath: string,
  loaderHtmlPath: string
): BrowserWindowType {
  const win = new BrowserWindow({
    width: 500,
    height: 300,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    icon: iconPath,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  })
  void win.loadFile(loaderHtmlPath)
  return win
}

/**
 * Create the main application window hosting the Coday web server URL.
 * All shared behaviours are wired here:
 *  - Navigation guard (will-navigate)
 *  - Reload recovery (did-fail-load)
 *  - Keyboard reload interception (Cmd+R / F5)
 *  - macOS close-to-hide
 *  - ready-to-show
 * An optional `onWindowCreated` callback receives the window for app-specific
 * additions (e.g. CSS injection in desktop-twin).
 */
export function createMainWindow(
  config: MainWindowConfig,
  pendingDeepLinkHandler: (() => void) | null
): BrowserWindowType {
  const { BrowserWindow, serverUrl, iconPath, preloadPath, title, onWindowCreated } = config

  // Size the window to ~85% of the available work area, with sensible min/max bounds
  const { screen } = require('electron') as typeof import('electron')
  const { width: screenW, height: screenH } = screen.getPrimaryDisplay().workAreaSize
  const winWidth = Math.min(Math.max(Math.round(screenW * 0.85), 1024), 1800)
  const winHeight = Math.min(Math.max(Math.round(screenH * 0.85), 700), 1200)
  log('INFO', `Creating main window at ${winWidth}x${winHeight} (screen work area: ${screenW}x${screenH})`)

  const win = new BrowserWindow({
    width: winWidth,
    height: winHeight,
    icon: iconPath,
    webPreferences: {
      preload: preloadPath,
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
    },
    title,
    show: false,
  })

  // Prevent navigation away from the Coday server
  win.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith(serverUrl)) {
      log('WARN', 'Blocked navigation to external URL:', url)
      event.preventDefault()
    }
  })

  // Handle failed loads by reloading the correct URL
  win.webContents.on('did-fail-load', (_, errorCode, errorDescription, validatedURL) => {
    log('ERROR', 'Failed to load:', validatedURL, 'Error:', errorCode, errorDescription)
    log('INFO', 'Reloading server URL:', serverUrl)
    void win.loadURL(serverUrl)
  })

  // Intercept reload attempts to ensure we reload the root URL
  win.webContents.on('before-input-event', (event, input) => {
    if ((input.meta && input.key.toLowerCase() === 'r') || input.key === 'F5') {
      if (input.type === 'keyDown') {
        event.preventDefault()
        log('INFO', 'Intercepted reload, reloading root URL:', serverUrl)
        void win.loadURL(serverUrl)
      }
    }
  })

  // Call app-specific hook (e.g. CSS injection)
  if (onWindowCreated) {
    onWindowCreated(win)
  }

  void win.loadURL(serverUrl)

  win.once('ready-to-show', () => {
    win.show()
    if (pendingDeepLinkHandler) {
      pendingDeepLinkHandler()
    }
  })

  if (process.env['NODE_ENV'] === 'development') {
    win.webContents.openDevTools()
  }

  // On macOS, prevent the window from actually closing — just hide it
  const appRef = require('electron').app as typeof import('electron').app & { isQuitting?: boolean }
  win.on('close', (event) => {
    if (process.platform === 'darwin' && !appRef.isQuitting) {
      event.preventDefault()
      win.hide()
      log('INFO', 'Window hidden (not closed) on macOS')
    }
  })

  return win
}

/**
 * Show a native OS error dialog.
 */
export function showErrorDialog(dialog: typeof import('electron').dialog, title: string, message: string): void {
  dialog.showErrorBox(title, message)
}

/**
 * Register the resize-to-content IPC handler used by setup/preferences windows.
 * Must be removed when the window closes.
 */
export function registerResizeHandler(
  ipcMain: typeof import('electron').ipcMain,
  BrowserWindow: typeof import('electron').BrowserWindow
): void {
  // Remove before re-registering to stay idempotent (safe against double-calls)
  ipcMain.removeHandler('window:resizeToContent')
  ipcMain.handle('window:resizeToContent', (_event: IpcMainInvokeEvent, contentHeight: number) => {
    const win = BrowserWindow.fromWebContents(_event.sender)
    if (win) {
      const { screen } = require('electron') as typeof import('electron')
      const { height: screenH } = screen.getPrimaryDisplay().workAreaSize
      // Cap to 90% of the work area so the window never goes off-screen
      const maxH = Math.round(screenH * 0.9)
      const [w = 580] = win.getContentSize()
      const targetH = Math.min(Math.ceil(contentHeight), maxH)
      win.setContentSize(w, targetH, false)
      win.center()
    }
  })
}

/**
 * Remove the resize-to-content IPC handler.
 */
export function removeResizeHandler(ipcMain: typeof import('electron').ipcMain): void {
  ipcMain.removeHandler('window:resizeToContent')
}
