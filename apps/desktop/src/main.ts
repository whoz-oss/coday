import type { BrowserWindow as BrowserWindowType, App, IpcMainInvokeEvent } from 'electron'
import { execSync, type ChildProcess } from 'child_process'

const { app, BrowserWindow, dialog, ipcMain } = require('electron') as {
  app: App & { isQuitting?: boolean }
  BrowserWindow: typeof import('electron').BrowserWindow
  dialog: typeof import('electron').dialog
  ipcMain: typeof import('electron').ipcMain
}
const { spawn } = require('child_process') as typeof import('child_process')
const { join } = require('path') as typeof import('path')
const fs = require('fs') as typeof import('fs')

let mainWindow: BrowserWindowType | null = null
let serverProcess: ChildProcess | null = null
let serverUrl: string | null | undefined = null
let pendingDeepLink: string | null = null

const PROTOCOL_NAME = 'coday'

// Storage file path
const STORAGE_FILE = join(app.getPath('userData'), 'preferences.json')

// Log file path for packaged app
const LOG_FILE = join(app.getPath('userData'), 'coday-desktop.log')

/**
 * Enhanced logging that writes to both console and log file
 */
function log(level: 'INFO' | 'ERROR' | 'WARN', ...args: any[]): void {
  const timestamp = new Date().toISOString()
  const message = args.map((arg) => (typeof arg === 'object' ? JSON.stringify(arg, null, 2) : String(arg))).join(' ')
  const logLine = `[${timestamp}] [${level}] ${message}\n`

  // Always write to console
  if (level === 'ERROR') {
    console.error(...args)
  } else if (level === 'WARN') {
    console.warn(...args)
  } else {
    console.log(...args)
  }

  // Write to log file in packaged app
  if (app.isPackaged) {
    try {
      fs.appendFileSync(LOG_FILE, logLine, 'utf8')
    } catch (error) {
      console.error('Failed to write to log file:', error)
    }
  }
}

// Initialize log file
if (app.isPackaged) {
  try {
    const logHeader = `\n\n${'='.repeat(80)}\nCoday Desktop Log - Started at ${new Date().toISOString()}\n${'='.repeat(80)}\n`
    fs.appendFileSync(LOG_FILE, logHeader, 'utf8')
    log('INFO', 'Log file initialized at:', LOG_FILE)
  } catch (error) {
    console.error('Failed to initialize log file:', error)
  }
}

/**
 * Find node executable by checking common installation locations
 */
function findNodeExecutable(): string | null {
  const { execSync } = require('child_process') as typeof import('child_process')
  const { existsSync } = require('fs') as typeof import('fs')

  // Common node installation paths on macOS
  const possiblePaths = [
    '/usr/local/bin/node',
    '/opt/homebrew/bin/node', // Apple Silicon Homebrew
    '/usr/bin/node',
    '/opt/local/bin/node', // MacPorts
  ]

  // Check if NVM is installed
  const homeDir = process.env['HOME']
  if (homeDir) {
    const nvmDir = join(homeDir, '.nvm')
    if (existsSync(nvmDir)) {
      try {
        // Try to get the default node from NVM
        const nvmNodePath = execSync('. ~/.nvm/nvm.sh && nvm which default', {
          encoding: 'utf8',
          shell: '/bin/bash',
        }).trim()
        if (nvmNodePath && existsSync(nvmNodePath)) {
          possiblePaths.unshift(nvmNodePath)
        }
      } catch (e) {
        // NVM command failed, continue with other paths
      }
    }
  }

  // Try each possible path
  for (const nodePath of possiblePaths) {
    if (existsSync(nodePath)) {
      console.log('Found node at:', nodePath)
      return nodePath
    }
  }

  // Try using 'which node' as last resort
  try {
    const whichNode = execSync('which node', { encoding: 'utf8' }).trim()
    if (whichNode && existsSync(whichNode)) {
      console.log('Found node via which:', whichNode)
      return whichNode
    }
  } catch (e) {
    // which command failed
  }

  return null
}

/**
 * Check if npx is available
 */
function findNpxExecutable(): string | null {
  const { execSync } = require('child_process') as typeof import('child_process')
  const { existsSync } = require('fs') as typeof import('fs')

  // First, try to find npx using 'which'
  try {
    const whichNpx = execSync('which npx', { encoding: 'utf8' }).trim()
    if (whichNpx && existsSync(whichNpx)) {
      console.log('Found npx via which:', whichNpx)
      return whichNpx
    }
  } catch (e) {
    // which command failed, continue with other methods
  }

  // Try to find npx alongside node
  const nodePath = findNodeExecutable()
  if (nodePath) {
    const { dirname } = require('path') as typeof import('path')
    const nodeDir = dirname(nodePath)
    const npxPath = join(nodeDir, 'npx')

    if (existsSync(npxPath)) {
      console.log('Found npx alongside node at:', npxPath)
      return npxPath
    }
  }

  // Common npx installation paths
  const possiblePaths = [
    '/usr/local/bin/npx',
    '/opt/homebrew/bin/npx', // Apple Silicon Homebrew
    '/usr/bin/npx',
    '/opt/local/bin/npx', // MacPorts
  ]

  for (const npxPath of possiblePaths) {
    if (existsSync(npxPath)) {
      console.log('Found npx at:', npxPath)
      return npxPath
    }
  }

  return null
}

/**
 * Start the Coday web server
 */
async function startCodayServer(): Promise<void> {
  try {
    log('INFO', 'Starting Coday server...')
    log('INFO', 'App packaged:', app.isPackaged)

    // Determine if we're in development or production
    const isDev = process.env['NODE_ENV'] === 'development' || !app.isPackaged

    if (isDev) {
      // Development: connect to local server on port 4100
      log('INFO', 'Development mode: connecting to local server on port 4100')
      serverUrl = 'http://localhost:4100'

      // Wait a bit to ensure the server is ready, then resolve
      return new Promise<void>((resolve) => {
        setTimeout(() => {
          log('INFO', 'Using development server at:', serverUrl)
          resolve()
        }, 500)
      })
    }

    // Production: find node and run the installed package
    log('INFO', 'Production mode: finding node executable')

    const nodePath = findNodeExecutable()

    if (!nodePath) {
      const error = new Error(
        'Node.js not found. Please install Node.js from https://nodejs.org/ or use a package manager like Homebrew.'
      )
      ;(error as any).userFacing = true
      throw error
    }

    log('INFO', 'Using node at:', nodePath)

    // Check for npx
    const npxPath = findNpxExecutable()

    if (!npxPath) {
      const error = new Error(
        'npx not found. Please ensure Node.js and npm are properly installed.\n\n' +
          'You can install Node.js from https://nodejs.org/ or use a package manager like Homebrew.\n\n' +
          'After installation, verify with: npx --version'
      )
      ;(error as any).userFacing = true
      throw error
    }

    log('INFO', 'Found npx at:', npxPath)
    const command = npxPath
    const args = ['--yes', '@whoz-oss/coday-web', '--base-url=coday://']

    log('INFO', 'Spawning:', command, args.join(' '))

    // Set up environment
    const env = { ...process.env }
    try {
      const userPath = execSync('/usr/bin/env echo $PATH', { shell: '/bin/zsh' }).toString().trim()
      env['PATH'] = `${userPath}:/usr/local/bin:/opt/homebrew/bin:${process.env['PATH']}`
    } catch {
      env['PATH'] = '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'
    }

    // Start the server
    serverProcess = spawn(command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
      shell: false,
    })

    return new Promise<void>((resolve, reject) => {
      let serverReady = false

      if (serverProcess!.stdout) {
        serverProcess!.stdout.on('data', (data: Buffer) => {
          const output = data.toString()
          log('INFO', '[Server]:', output)

          // Look for "Server is running on http://localhost:xxxx"
          const serverUrlMatch = output.match(/Server is running on (http:\/\/localhost:\d+)/)
          if (serverUrlMatch) {
            serverUrl = serverUrlMatch[1]
            log('INFO', 'Detected server URL:', serverUrl)

            if (!serverReady) {
              serverReady = true
              // Give the server a moment to fully initialize
              setTimeout(() => resolve(), 500)
            }
          }
        })
      }

      if (serverProcess!.stderr) {
        serverProcess!.stderr.on('data', (data: Buffer) => {
          log('ERROR', '[Server Error]:', data.toString())
        })
      }

      serverProcess!.on('error', (error: Error) => {
        log('ERROR', 'Failed to start server:', error)
        const wrappedError: any = new Error(`Failed to start Coday server: ${error.message}`)
        wrappedError.userFacing = true
        reject(wrappedError)
      })

      serverProcess!.on('exit', (code: number | null) => {
        log('INFO', 'Server process exited with code:', code)
        serverProcess = null
      })

      // Fallback timeout in case we don't detect server ready message
      setTimeout(() => {
        if (!serverProcess) {
          log('ERROR', 'Server process does not exists')
          const error: any = new Error('Failed to find server process.')
          error.userFacing = true
          reject(error)
        }
        if (!serverReady) {
          log('ERROR', 'Server start timeout reached - could not detect server URL')
          const error: any = new Error(
            'Failed to start server: timeout waiting for server URL. The server may be taking too long to start.'
          )
          error.userFacing = true
          reject(error)
        }
      }, 10000) // 10 seconds timeout for npx to download and start
    })
  } catch (error) {
    throw error
  }
}

/**
 * Stop the Coday server
 */
function stopCodayServer(): void {
  if (serverProcess) {
    log('INFO', 'Stopping Coday server...')
    serverProcess.kill()
    serverProcess = null
  }
}

/**
 * Create the loading window
 */
function createLoadingWindow(): BrowserWindowType {
  const iconPath = join(__dirname, 'icon.png')

  const loadingWindow = new BrowserWindow({
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

  // Load the loader HTML
  void loadingWindow.loadFile(join(__dirname, 'loader.html'))

  return loadingWindow
}

/**
 * Check if server is still responsive
 */
async function isServerResponsive(): Promise<boolean> {
  if (!serverUrl) return false

  try {
    const http = require('http') as typeof import('http')
    const url = new URL(serverUrl)

    return new Promise<boolean>((resolve) => {
      const req = http.get(
        {
          hostname: url.hostname,
          port: url.port,
          path: '/api/health',
          timeout: 2000,
        },
        (res) => {
          resolve(res.statusCode === 200 || res.statusCode === 404) // 404 is ok, means server is up
        }
      )

      req.on('error', () => resolve(false))
      req.on('timeout', () => {
        req.destroy()
        resolve(false)
      })
    })
  } catch (error) {
    log('ERROR', 'Error checking server responsiveness:', error)
    return false
  }
}

/**
 * Create the main Electron window
 */
function createWindow(): void {
  if (!serverUrl) {
    log('ERROR', 'Cannot create window: server URL not initialized')
    return
  }

  log('INFO', 'Creating main window with server URL:', serverUrl)

  const iconPath = join(__dirname, 'icon.png')

  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    icon: iconPath,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
    },
    title: 'Coday Desktop',
    show: false, // Don't show until ready
  })

  // Prevent navigation away from the Coday server
  // This fixes the reload issue where Cmd+R would show "Something went wrong"
  mainWindow.webContents.on('will-navigate', (event, url) => {
    // Allow navigation within the same origin (server URL)
    if (!url.startsWith(serverUrl!)) {
      log('WARN', 'Blocked navigation to external URL:', url)
      event.preventDefault()
    }
  })

  // Handle failed loads by reloading the correct URL
  mainWindow.webContents.on('did-fail-load', (_, errorCode, errorDescription, validatedURL) => {
    log('ERROR', 'Failed to load:', validatedURL, 'Error:', errorCode, errorDescription)
    // Reload the main server URL if load fails
    if (mainWindow && serverUrl) {
      log('INFO', 'Reloading server URL:', serverUrl)
      void mainWindow.loadURL(serverUrl)
    }
  })

  // Intercept reload attempts to ensure we reload the root URL
  mainWindow.webContents.on('before-input-event', (event, input) => {
    // Cmd+R or F5 reload
    if ((input.meta && input.key.toLowerCase() === 'r') || input.key === 'F5') {
      if (input.type === 'keyDown' && mainWindow && serverUrl) {
        event.preventDefault()
        log('INFO', 'Intercepted reload, reloading root URL:', serverUrl)
        void mainWindow.loadURL(serverUrl)
      }
    }
  })

  // Load the Coday web interface
  void mainWindow.loadURL(serverUrl)

  // Show window when ready
  mainWindow.once('ready-to-show', () => {
    if (mainWindow) {
      mainWindow.show()

      // Handle pending deeplink if any
      if (pendingDeepLink) {
        log('INFO', 'Processing pending deeplink:', pendingDeepLink)
        handleDeepLink(pendingDeepLink)
      }
    }
  })

  // Open DevTools in development
  if (process.env['NODE_ENV'] === 'development' && mainWindow) {
    mainWindow.webContents.openDevTools()
  }

  mainWindow.on('closed', () => {
    log('INFO', 'Main window closed')
    mainWindow = null
  })

  // On macOS, prevent the window from actually closing, just hide it
  // This prevents the need to recreate the window and avoids the blank page issue
  mainWindow.on('close', (event) => {
    if (process.platform === 'darwin' && !app.isQuitting) {
      event.preventDefault()
      mainWindow?.hide()
      log('INFO', 'Window hidden (not closed) on macOS')
    }
  })
}

/**
 * Show error dialog to user
 */
function showErrorDialog(title: string, message: string): void {
  dialog.showErrorBox(title, message)
}

/**
 * Setup IPC handlers for storage
 */
function setupStorageHandlers(): void {
  // Get storage data
  ipcMain.handle('storage:get', (_event: IpcMainInvokeEvent, key: string): string | null => {
    try {
      if (!fs.existsSync(STORAGE_FILE)) {
        return null
      }
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      const storage = JSON.parse(data)
      return storage[key] ?? null
    } catch (error) {
      log('ERROR', 'Failed to read storage:', error)
      return null
    }
  })

  // Set storage data
  ipcMain.handle('storage:set', (_event: IpcMainInvokeEvent, key: string, value: string): boolean => {
    try {
      let storage: Record<string, string> = {}

      if (fs.existsSync(STORAGE_FILE)) {
        const data = fs.readFileSync(STORAGE_FILE, 'utf8')
        storage = JSON.parse(data)
      }

      storage[key] = value
      fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
      return true
    } catch (error) {
      log('ERROR', 'Failed to write storage:', error)
      return false
    }
  })

  // Remove storage data
  ipcMain.handle('storage:remove', (_event: IpcMainInvokeEvent, key: string): boolean => {
    try {
      if (!fs.existsSync(STORAGE_FILE)) {
        return true
      }

      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      const storage = JSON.parse(data)
      delete storage[key]
      fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
      return true
    } catch (error) {
      log('ERROR', 'Failed to remove from storage:', error)
      return false
    }
  })

  // Clear all storage
  ipcMain.handle('storage:clear', (): boolean => {
    try {
      if (fs.existsSync(STORAGE_FILE)) {
        fs.unlinkSync(STORAGE_FILE)
      }
      return true
    } catch (error) {
      log('ERROR', 'Failed to clear storage:', error)
      return false
    }
  })

  // Add handler to get log file path
  ipcMain.handle('logs:getPath', (): string => {
    return LOG_FILE
  })

  // Add handler to open logs folder
  ipcMain.handle('logs:openFolder', (): void => {
    const { shell } = require('electron') as typeof import('electron')
    shell.showItemInFolder(LOG_FILE)
  })

  log('INFO', 'Storage handlers initialized')
}

/**
 * Handle deeplink navigation
 */
function handleDeepLink(url: string): void {
  log('INFO', 'Handling deeplink:', url)

  try {
    // Parse URL: coday://project/my-project/thread/abc-123
    const match = url.match(/coday:\/\/project\/([^/]+)\/thread\/([^/?#]+)/)

    if (!match) {
      log('WARN', 'Invalid deeplink format:', url)
      return
    }

    const [, projectName, threadId] = match
    log('INFO', 'Parsed deeplink - project:', projectName, 'thread:', threadId)

    if (!serverUrl) {
      log('WARN', 'Server not ready yet, storing deeplink for later')
      pendingDeepLink = url
      return
    }

    if (!mainWindow) {
      log('WARN', 'Main window not available, storing deeplink for later')
      pendingDeepLink = url
      return
    }

    // Build local server URL
    const targetUrl = `${serverUrl}/project/${projectName}/thread/${threadId}`
    log('INFO', 'Navigating to:', targetUrl)

    void mainWindow.loadURL(targetUrl)

    // Show and focus window
    if (mainWindow.isMinimized()) {
      mainWindow.restore()
    }
    mainWindow.show()
    mainWindow.focus()

    // Clear pending deeplink
    pendingDeepLink = null
  } catch (error) {
    log('ERROR', 'Error handling deeplink:', error)
  }
}

/**
 * Initialize the application
 */
async function initialize(): Promise<void> {
  let loadingWindow: BrowserWindowType | null = null

  try {
    log('INFO', 'Initializing Coday Desktop...')

    // Setup storage handlers
    setupStorageHandlers()

    // Show loading window
    loadingWindow = createLoadingWindow()

    // Start the Coday server
    await startCodayServer()

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
    if (loadingWindow) {
      loadingWindow.close()
    }

    // Show user-facing error dialog
    const errorMessage = error instanceof Error ? error.message : String(error)
    const isUserFacing = error && typeof error === 'object' && (error as any).userFacing

    if (isUserFacing) {
      showErrorDialog('Coday Desktop - Startup Error', errorMessage)
    } else {
      showErrorDialog(
        'Coday Desktop - Unexpected Error',
        `An unexpected error occurred while starting Coday:\n\n${errorMessage}\n\nPlease check the console logs for more details.`
      )
    }

    app.quit()
  }
}

// Register custom protocol handler
if (process.defaultApp) {
  // Development mode
  if (process.argv.length >= 2) {
    app.setAsDefaultProtocolClient(PROTOCOL_NAME, process.execPath, [require('path').resolve(process.argv[1])])
  }
} else {
  // Production mode
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

    // Look for deeplink in command line arguments
    const url = commandLine.find((arg: any) => arg.startsWith(`${PROTOCOL_NAME}://`))
    if (url) {
      log('INFO', 'Found deeplink in second instance:', url)
      handleDeepLink(url)
    }

    // Focus existing window
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore()
      }
      mainWindow.show()
      mainWindow.focus()
    }
  })
}

// App event handlers
void app.whenReady().then(() => void initialize())

app.on('window-all-closed', () => {
  // On macOS, don't quit when all windows are closed
  if (process.platform !== 'darwin') {
    stopCodayServer()
    app.quit()
  }
})

app.on('activate', async () => {
  log('INFO', 'App activated')

  // On macOS, show the window if it exists but is hidden
  if (mainWindow) {
    if (mainWindow.isMinimized()) {
      mainWindow.restore()
    }
    mainWindow.show()
    log('INFO', 'Showing existing window')
  } else if (serverUrl && serverProcess) {
    // Window was destroyed but server is still running, check if server is responsive
    log('INFO', 'Checking if server is still responsive...')
    const isResponsive = await isServerResponsive()

    if (isResponsive) {
      log('INFO', 'Server is responsive, recreating window')
      createWindow()
    } else {
      log('WARN', 'Server not responsive, reinitializing')
      stopCodayServer()
      serverUrl = null
      void initialize()
    }
  } else {
    // No window and no server, need to reinitialize
    log('INFO', 'No window or server, reinitializing')
    void initialize()
  }
})

app.on('before-quit', () => {
  log('INFO', 'App quitting, stopping server')
  app.isQuitting = true
  stopCodayServer()
})

// Handle uncaught exceptions
process.on('uncaughtException', (error: Error) => {
  log('ERROR', 'Uncaught exception:', error)
  showErrorDialog(
    'Coday Desktop - Fatal Error',
    `A fatal error occurred:\n\n${error.message}\n\nThe application will now close.`
  )
  stopCodayServer()
  app.quit()
})
