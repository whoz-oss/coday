import type { BrowserWindow as BrowserWindowType, App, IpcMainInvokeEvent } from 'electron'
import { execSync, type ChildProcess } from 'child_process'

const { app, BrowserWindow, dialog, ipcMain } = require('electron') as {
  app: App
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

// Storage file path
const STORAGE_FILE = join(app.getPath('userData'), 'preferences.json')

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
    console.log('Starting Coday server...')
    console.log('App packaged:', app.isPackaged)

    // Determine if we're in development or production
    const isDev = process.env['NODE_ENV'] === 'development' || !app.isPackaged

    let command: string
    let args: string[]

    if (isDev) {
      // Development: use npx to run the web package from workspace
      console.log('Development mode: checking for npx')

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

      command = npxPath
      args = ['--yes', '@whoz-oss/coday-web', '--no_auth']
    } else {
      // Production: find node and run the installed package
      console.log('Production mode: finding node executable')

      const nodePath = findNodeExecutable()

      if (!nodePath) {
        const error = new Error(
          'Node.js not found. Please install Node.js from https://nodejs.org/ or use a package manager like Homebrew.'
        )
        ;(error as any).userFacing = true
        throw error
      }

      console.log('Using node at:', nodePath)

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

      console.log('Found npx at:', npxPath)
      command = npxPath
      args = ['--yes', '@whoz-oss/coday-web', '--no_auth']
    }

    console.log('Spawning:', command, args.join(' '))

    // Set up environment
    const env = { ...process.env }
    try {
      const userPath = execSync('/usr/bin/env echo $PATH', { shell: '/bin/zsh' }).toString().trim()
      env['PATH'] = `${userPath}:/usr/local/bin:/opt/homebrew/bin:${process.env['PATH']}`
    } catch {
      env['PATH'] = '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'
    }

    env['NO_AUTH'] = 'true'

    // Start the server
    serverProcess = spawn(command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
      shell: isDev, // Only use shell in development
    })

    return new Promise<void>((resolve, reject) => {
      let serverReady = false

      if (serverProcess!.stdout) {
        serverProcess!.stdout.on('data', (data: Buffer) => {
          const output = data.toString()
          console.log('[Server]:', output)

          // Look for "Server is running on http://localhost:xxxx"
          const serverUrlMatch = output.match(/Server is running on (http:\/\/localhost:\d+)/)
          if (serverUrlMatch) {
            serverUrl = serverUrlMatch[1]
            console.log('Detected server URL:', serverUrl)

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
          console.error('[Server Error]:', data.toString())
        })
      }

      serverProcess!.on('error', (error: Error) => {
        console.error('Failed to start server:', error)
        const wrappedError: any = new Error(`Failed to start Coday server: ${error.message}`)
        wrappedError.userFacing = true
        reject(wrappedError)
      })

      serverProcess!.on('exit', (code: number | null) => {
        console.log('Server process exited with code:', code)
        serverProcess = null
      })

      // Fallback timeout in case we don't detect server ready message
      setTimeout(() => {
        if (!serverProcess) {
          console.error('Server process does not exists')
          const error: any = new Error('Failed to find server process.')
          error.userFacing = true
          reject(error)
        }
        if (!serverProcess) {
          console.error('Server process does not exists')
          const error: any = new Error('Failed to find server process.')
          error.userFacing = true
          reject(error)
        }
        if (!serverReady) {
          console.error('Server start timeout reached - could not detect server URL')
          const error: any = new Error(
            'Failed to start server: timeout waiting for server URL. The server may be taking too long to start.'
          )
          error.userFacing = true
          reject(error)
        }
      }, 10000) // 30 seconds timeout for npx to download and start
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
    console.log('Stopping Coday server...')
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
 * Create the main Electron window
 */
function createWindow(): void {
  if (!serverUrl) {
    console.error('Cannot create window: server URL not initialized')
    return
  }

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

  // Load the Coday web interface
  void mainWindow.loadURL(serverUrl)

  // Show window when ready
  mainWindow.once('ready-to-show', () => {
    if (mainWindow) {
      mainWindow.show()
    }
  })

  // Open DevTools in development
  if (process.env['NODE_ENV'] === 'development' && mainWindow) {
    mainWindow.webContents.openDevTools()
  }

  mainWindow.on('closed', () => {
    mainWindow = null
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
      console.error('Failed to read storage:', error)
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
      console.error('Failed to write storage:', error)
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
      console.error('Failed to remove from storage:', error)
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
      console.error('Failed to clear storage:', error)
      return false
    }
  })

  console.log('Storage handlers initialized')
}

/**
 * Initialize the application
 */
async function initialize(): Promise<void> {
  let loadingWindow: BrowserWindowType | null = null

  try {
    console.log('Initializing Coday Desktop...')

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
    console.error('Failed to initialize application:', error)
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

// App event handlers
void app.whenReady().then(() => void initialize())

app.on('window-all-closed', () => {
  stopCodayServer()
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
  }
})

app.on('before-quit', () => {
  stopCodayServer()
})

// Handle uncaught exceptions
process.on('uncaughtException', (error: Error) => {
  console.error('Uncaught exception:', error)
  showErrorDialog(
    'Coday Desktop - Fatal Error',
    `A fatal error occurred:\n\n${error.message}\n\nThe application will now close.`
  )
  stopCodayServer()
  app.quit()
})
