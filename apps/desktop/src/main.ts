import type { BrowserWindow as BrowserWindowType, App, IpcMainInvokeEvent } from 'electron'
import type { ChildProcess } from 'child_process'
import {
  log,
  initLogger,
  findExecutable,
  checkApiKey,
  saveApiKeyToUserYaml,
  setupStorageHandlers,
  setupEnv,
  startCodayServer,
  stopCodayServer,
  isServerResponsive,
  createLoadingWindow,
  createMainWindow,
  showErrorDialog,
  registerResizeHandler,
  removeResizeHandler,
  registerApiKeyHandlers,
  removeApiKeyHandlers,
  parseDeepLink,
  navigateToDeepLink,
} from '@coday/desktop-core'

const { app, BrowserWindow, dialog, ipcMain, Menu } = require('electron') as {
  app: App & { isQuitting?: boolean }
  BrowserWindow: typeof import('electron').BrowserWindow
  dialog: typeof import('electron').dialog
  ipcMain: typeof import('electron').ipcMain
  Menu: typeof import('electron').Menu
}
const { join } = require('path') as typeof import('path')
const fs = require('fs') as typeof import('fs')

// ---------------------------------------------------------------------------
// App-specific constants
// ---------------------------------------------------------------------------

const PROTOCOL_NAME = 'coday'
const CODAY_PORT = '3049'
const SERVER_ARGS = ['--yes', '@whoz-oss/coday-web', '--base-url=coday://', '--multi']
const PREFERENCES_KEY_SETUP_DONE = 'setupDone'

const STORAGE_FILE = join(app.getPath('userData'), 'preferences.json')
const LOG_FILE = join(app.getPath('userData'), 'coday-desktop.log')

// ---------------------------------------------------------------------------
// Module state
// ---------------------------------------------------------------------------

let mainWindow: BrowserWindowType | null = null
let serverProcess: ChildProcess | null = null
let serverUrl: string | null = null
let pendingDeepLink: string | null = null
let isSetupWindowOpen = false
let isPreferencesWindowOpen = false
let storageHandlersRegistered = false
let isInitializing = false
let setupWindowRef: BrowserWindowType | null = null

// ---------------------------------------------------------------------------
// Logger init
// ---------------------------------------------------------------------------

initLogger(app, 'coday-desktop.log')

// ---------------------------------------------------------------------------
// Setup state helpers
// ---------------------------------------------------------------------------

/**
 * Returns true if the setup wizard has never been completed.
 * We detect this by the absence of the 'setupDone' flag in preferences.json.
 * If an Anthropic API key is already present (env var or user.yaml), we also
 * consider setup done so existing users are not prompted again.
 */
function needsSetup(): boolean {
  // If setup was explicitly completed before, skip
  try {
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      const storage = JSON.parse(data)
      if (storage[PREFERENCES_KEY_SETUP_DONE] === 'true') return false
    }
  } catch {
    // ignore, fall through
  }

  // If an Anthropic API key is already configured (env or user.yaml), skip setup
  if (checkApiKey('anthropic', 'ANTHROPIC_API_KEY').configured) return false

  return true
}

/**
 * Mark setup as completed in preferences.json.
 */
function markSetupDone(): void {
  try {
    let storage: Record<string, string> = {}
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      storage = JSON.parse(data)
    }
    storage[PREFERENCES_KEY_SETUP_DONE] = 'true'
    const dir = join(STORAGE_FILE, '..')
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
    log('INFO', 'Setup marked as done')
  } catch (error) {
    log('ERROR', 'Failed to mark setup as done:', error)
  }
}

// ---------------------------------------------------------------------------
// Setup window
// ---------------------------------------------------------------------------

/**
 * Show the first-launch setup window.
 * Only asks for AI API keys — no workspace path (desktop is multi-project).
 * Resolves when the user completes or dismisses setup.
 */
function showSetupWindow(): Promise<void> {
  return new Promise<void>((resolve) => {
    const setupWindow = new BrowserWindow({
      width: 580,
      height: 480,
      minWidth: 580,
      minHeight: 400,
      frame: false,
      resizable: false,
      useContentSize: true,
      transparent: true,
      icon: join(__dirname, 'icon.png'),
      webPreferences: {
        preload: join(__dirname, 'preload.js'),
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: false,
      },
    })

    registerResizeHandler(ipcMain, BrowserWindow)
    registerApiKeyHandlers(ipcMain)

    // IPC handler: user confirmed setup
    ipcMain.handle('setup:confirm', () => {
      log('INFO', 'Setup confirmed')
      markSetupDone()
      removeResizeHandler(ipcMain)
      removeApiKeyHandlers(ipcMain)
      ipcMain.removeHandler('setup:confirm')
      setupWindow.close()
      resolve()
    })

    setupWindowRef = setupWindow

    // If user closes the window without confirming, proceed anyway
    setupWindow.on('closed', () => {
      setupWindowRef = null
      removeResizeHandler(ipcMain)
      removeApiKeyHandlers(ipcMain)
      ipcMain.removeHandler('setup:confirm')
      resolve()
    })

    void setupWindow.loadFile(join(__dirname, 'setup.html'))
  })
}

/**
 * Show the setup window, guarding against multiple concurrent opens.
 */
async function runSetupIfNeeded(): Promise<void> {
  if (isSetupWindowOpen) {
    log('WARN', 'Setup window already open, ignoring request')
    return
  }
  isSetupWindowOpen = true
  try {
    await showSetupWindow()
  } finally {
    isSetupWindowOpen = false
  }
}

// ---------------------------------------------------------------------------
// Preferences window
// ---------------------------------------------------------------------------

/**
 * Show the preferences window for managing AI API keys.
 */
function showPreferencesWindow(): void {
  if (isPreferencesWindowOpen) {
    log('WARN', 'Preferences window already open, ignoring request')
    return
  }

  isPreferencesWindowOpen = true
  log('INFO', 'Opening preferences window')

  const preferencesWindow = new BrowserWindow({
    width: 580,
    height: 480,
    minWidth: 580,
    minHeight: 400,
    frame: false,
    resizable: false,
    useContentSize: true,
    transparent: true,
    icon: join(__dirname, 'icon.png'),
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: false,
    },
  })

  registerResizeHandler(ipcMain, BrowserWindow)

  ipcMain.handle('preferences:close', () => {
    preferencesWindow.close()
  })

  ipcMain.handle('setup:saveAnthropicApiKey', (_event: IpcMainInvokeEvent, apiKey: string) => {
    saveApiKeyToUserYaml('anthropic', apiKey)
  })

  ipcMain.handle('setup:saveOpenAiApiKey', (_event: IpcMainInvokeEvent, apiKey: string) => {
    saveApiKeyToUserYaml('openai', apiKey)
  })

  preferencesWindow.on('closed', () => {
    removeResizeHandler(ipcMain)
    ipcMain.removeHandler('preferences:close')
    ipcMain.removeHandler('setup:saveAnthropicApiKey')
    ipcMain.removeHandler('setup:saveOpenAiApiKey')
    isPreferencesWindowOpen = false
    log('INFO', 'Preferences window closed')
  })

  void preferencesWindow.loadFile(join(__dirname, 'preferences.html'))
}

// ---------------------------------------------------------------------------
// Application menu
// ---------------------------------------------------------------------------

/**
 * Setup native macOS application menu with Preferences entry.
 */
function setupApplicationMenu(): void {
  const template = [
    {
      label: 'Coday',
      submenu: [
        { role: 'about', label: 'About Coday' },
        { type: 'separator' },
        {
          label: 'Preferences...',
          accelerator: 'CmdOrCtrl+,',
          click: () => showPreferencesWindow(),
        },
        { type: 'separator' },
        { role: 'services', label: 'Services' },
        { type: 'separator' },
        { role: 'hide', label: 'Hide Coday' },
        { role: 'hideOthers', label: 'Hide Others' },
        { role: 'unhide', label: 'Show All' },
        { type: 'separator' },
        { role: 'quit', label: 'Quit Coday' },
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo', label: 'Undo' },
        { role: 'redo', label: 'Redo' },
        { type: 'separator' },
        { role: 'cut', label: 'Cut' },
        { role: 'copy', label: 'Copy' },
        { role: 'paste', label: 'Paste' },
        { role: 'selectAll', label: 'Select All' },
      ],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload', label: 'Reload' },
        { role: 'toggleDevTools', label: 'Toggle Developer Tools' },
      ],
    },
    {
      label: 'Window',
      submenu: [
        { role: 'minimize', label: 'Minimize' },
        { role: 'zoom', label: 'Zoom' },
        { type: 'separator' },
        { role: 'front', label: 'Bring All to Front' },
      ],
    },
  ]

  const menu = Menu.buildFromTemplate(template as any)
  Menu.setApplicationMenu(menu)
  log('INFO', 'Application menu setup complete')
}

// ---------------------------------------------------------------------------
// Deep-link handling
// ---------------------------------------------------------------------------

function handleDeepLink(url: string): void {
  log('INFO', 'Handling deeplink:', url)
  try {
    const parsed = parseDeepLink(url, PROTOCOL_NAME)
    if (!parsed) {
      log('WARN', 'Invalid deeplink format:', url)
      return
    }
    const { projectName, threadId } = parsed
    log('INFO', 'Parsed deeplink - project:', projectName, 'thread:', threadId)

    if (!serverUrl || !mainWindow) {
      log('WARN', 'Server/window not ready yet, storing deeplink for later')
      pendingDeepLink = url
      return
    }

    navigateToDeepLink(mainWindow, serverUrl, projectName, threadId)
    pendingDeepLink = null
  } catch (error) {
    log('ERROR', 'Error handling deeplink:', error)
  }
}

// ---------------------------------------------------------------------------
// Window creation
// ---------------------------------------------------------------------------

function createWindow(): void {
  if (!serverUrl) {
    log('ERROR', 'Cannot create window: server URL not initialized')
    return
  }

  log('INFO', 'Creating main window with server URL:', serverUrl)

  const iconPath = join(__dirname, 'icon.png')
  const preloadPath = join(__dirname, 'preload.js')
  const desktopCss = fs.readFileSync(join(__dirname, 'desktop.css'), 'utf8')

  mainWindow = createMainWindow(
    {
      BrowserWindow,
      ipcMain,
      serverUrl,
      iconPath,
      preloadPath,
      title: 'Coday',
      onWindowCreated: (win) => {
        win.webContents.on('did-finish-load', () => {
          void win.webContents.executeJavaScript(`document.body.classList.add('desktop');`)
          void win.webContents.insertCSS(desktopCss)
        })
      },
    },
    () => {
      if (pendingDeepLink) {
        log('INFO', 'Processing pending deeplink:', pendingDeepLink)
        handleDeepLink(pendingDeepLink)
      }
    }
  )

  // Setup menu after window is created
  setupApplicationMenu()

  mainWindow.on('closed', () => {
    log('INFO', 'Main window closed')
    mainWindow = null
  })
}

// ---------------------------------------------------------------------------
// Initialize
// ---------------------------------------------------------------------------

async function initialize(): Promise<void> {
  if (isInitializing) {
    log('INFO', 'Initialization already in progress, ignoring duplicate call')
    if (setupWindowRef && !setupWindowRef.isDestroyed()) {
      if (setupWindowRef.isMinimized()) setupWindowRef.restore()
      setupWindowRef.show()
      setupWindowRef.focus()
    }
    return
  }
  isInitializing = true
  let loadingWindow: BrowserWindowType | null = null

  try {
    log('INFO', 'Initializing Coday...')

    // Setup storage handlers (once)
    if (!storageHandlersRegistered) {
      storageHandlersRegistered = true
      setupStorageHandlers(ipcMain, STORAGE_FILE, LOG_FILE)
    }

    // First-launch setup: prompt for AI API keys if not yet configured
    if (needsSetup()) {
      log('INFO', 'First launch detected, showing setup window')
      await runSetupIfNeeded()
      log('INFO', 'Setup complete')
    }

    // Show loading window
    loadingWindow = createLoadingWindow(BrowserWindow, join(__dirname, 'icon.png'), join(__dirname, 'loader.html'))

    // Find node/npx
    const nodePath = findExecutable('node')
    if (!nodePath) {
      const error: any = new Error(
        'Node.js not found. Please install Node.js from https://nodejs.org/ or use a package manager like Homebrew.'
      )
      error.userFacing = true
      throw error
    }
    log('INFO', 'Using node at:', nodePath)

    const npxPath = findExecutable('npx')
    if (!npxPath) {
      const error: any = new Error(
        'npx not found. Please ensure Node.js and npm are properly installed.\n\n' +
          'You can install Node.js from https://nodejs.org/ or use a package manager like Homebrew.\n\n' +
          'After installation, verify with: npx --version'
      )
      error.userFacing = true
      throw error
    }
    log('INFO', 'Found npx at:', npxPath)

    // Start the Coday server
    const result = await startCodayServer({
      appName: 'Coday',
      port: CODAY_PORT,
      npxPath,
      serverArgs: SERVER_ARGS,
      npxCwd: app.getPath('temp'),
      env: setupEnv(),
    })
    serverUrl = result.serverUrl
    serverProcess = result.serverProcess

    // Create the main window
    createWindow()

    // Close loading window after main window is shown
    if (mainWindow) {
      mainWindow.once('ready-to-show', () => {
        if (loadingWindow) {
          loadingWindow.close()
          loadingWindow = null
        }
      })
    }
  } catch (error) {
    log('ERROR', 'Failed to initialize application:', error)
    if (loadingWindow) loadingWindow.close()

    const errorMessage = error instanceof Error ? error.message : String(error)
    const isUserFacing = error && typeof error === 'object' && (error as any).userFacing

    if (isUserFacing) {
      showErrorDialog(dialog, 'Coday - Startup Error', errorMessage)
    } else {
      showErrorDialog(
        dialog,
        'Coday - Unexpected Error',
        `An unexpected error occurred while starting Coday:\n\n${errorMessage}\n\nPlease check the console logs for more details.`
      )
    }

    app.quit()
  } finally {
    isInitializing = false
  }
}

// ---------------------------------------------------------------------------
// Protocol registration & single instance
// ---------------------------------------------------------------------------

if (process.defaultApp) {
  if (process.argv.length >= 2) {
    app.setAsDefaultProtocolClient(PROTOCOL_NAME, process.execPath, [require('path').resolve(process.argv[1])])
  }
} else {
  app.setAsDefaultProtocolClient(PROTOCOL_NAME)
}
log('INFO', 'Registered protocol handler for:', PROTOCOL_NAME)

// Handle deeplinks on macOS
app.on('open-url', (event, url) => {
  event.preventDefault()
  log('INFO', 'Received deeplink (macOS):', url)
  handleDeepLink(url)
})

// Handle single instance lock for Windows/Linux deeplinks
const gotTheLock = app.requestSingleInstanceLock()
if (!gotTheLock) {
  log('INFO', 'Another instance is already running, quitting')
  app.quit()
} else {
  app.on('second-instance', (_event: any, commandLine: any) => {
    log('INFO', 'Second instance detected, command line:', commandLine)
    const url = commandLine.find((arg: any) => arg.startsWith(`${PROTOCOL_NAME}://`))
    if (url) {
      log('INFO', 'Found deeplink in second instance:', url)
      handleDeepLink(url)
    }
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.show()
      mainWindow.focus()
    }
  })
}

// ---------------------------------------------------------------------------
// App lifecycle
// ---------------------------------------------------------------------------

void app.whenReady().then(() => void initialize())

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    stopCodayServer(serverProcess)
    app.quit()
  }
})

app.on('activate', async () => {
  log('INFO', 'App activated')

  if (isInitializing) {
    log('INFO', 'Initialization in progress, focusing setup window if open')
    if (setupWindowRef && !setupWindowRef.isDestroyed()) {
      if (setupWindowRef.isMinimized()) setupWindowRef.restore()
      setupWindowRef.show()
      setupWindowRef.focus()
    }
    return
  }

  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    log('INFO', 'Showing existing window')
  } else if (serverUrl && serverProcess) {
    log('INFO', 'Checking if server is still responsive...')
    const isResponsive = await isServerResponsive(serverUrl)
    if (isResponsive) {
      log('INFO', 'Server is responsive, recreating window')
      createWindow()
    } else {
      log('WARN', 'Server not responsive, reinitializing')
      stopCodayServer(serverProcess)
      serverProcess = null
      serverUrl = null
      void initialize()
    }
  } else {
    log('INFO', 'No window or server, reinitializing')
    void initialize()
  }
})

app.on('before-quit', () => {
  log('INFO', 'App quitting, stopping server')
  app.isQuitting = true
  stopCodayServer(serverProcess)
})

process.on('uncaughtException', (error: Error) => {
  log('ERROR', 'Uncaught exception:', error)
  showErrorDialog(
    dialog,
    'Coday - Fatal Error',
    `A fatal error occurred:\n\n${error.message}\n\nThe application will now close.`
  )
  stopCodayServer(serverProcess)
  app.quit()
})
