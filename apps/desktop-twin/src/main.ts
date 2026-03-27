import type { BrowserWindow as BrowserWindowType, App, IpcMainInvokeEvent } from 'electron'
import { execSync, type ChildProcess } from 'child_process'

const { app, BrowserWindow, dialog, ipcMain, Menu, session, protocol } = require('electron') as {
  app: App & { isQuitting?: boolean }
  BrowserWindow: typeof import('electron').BrowserWindow
  dialog: typeof import('electron').dialog
  ipcMain: typeof import('electron').ipcMain
  Menu: typeof import('electron').Menu
  session: typeof import('electron').session
  protocol: typeof import('electron').protocol
}
const { spawn } = require('child_process') as typeof import('child_process')
const { join } = require('path') as typeof import('path')
const fs = require('fs') as typeof import('fs')

let mainWindow: BrowserWindowType | null = null
let serverProcess: ChildProcess | null = null
let serverUrl: string | null | undefined = null
let pendingDeepLink: string | null = null
let isSetupWindowOpen = false
let isPreferencesWindowOpen = false
let storageHandlersRegistered = false

const PROTOCOL_NAME = 'coday-twin'
const DEFAULT_TWIN_PROJECT_PATH = join(app.getPath('home'), 'CodayTwin')
const PREFERENCES_KEY_TWIN_PATH = 'twinProjectPath'

// Storage file path
const STORAGE_FILE = join(app.getPath('userData'), 'preferences.json')
const PREFERENCES_KEY_SLACK_ENABLED = 'slackEnabled'
const PREFERENCES_KEY_SLACK_WEBHOOK_URL = 'slackWebhookUrl'
const PREFERENCES_KEY_SLACK_USER_ID = 'slackUserId'

// Log file path for packaged app
const LOG_FILE = join(app.getPath('userData'), 'coday-twin-desktop.log')

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
    const logHeader = `\n\n${'='.repeat(80)}\nCodayTwin Desktop Log - Started at ${new Date().toISOString()}\n${'='.repeat(80)}\n`
    fs.appendFileSync(LOG_FILE, logHeader, 'utf8')
    log('INFO', 'Log file initialized at:', LOG_FILE)
  } catch (error) {
    console.error('Failed to initialize log file:', error)
  }
}

/**
 * Find node/npx executable using 'which' via a login shell, with fallback to
 * keg-only Homebrew versioned installs (e.g. node@24, node@22...)
 */
function findExecutable(executable: 'node' | 'npx'): string {
  const { execSync } = require('child_process') as typeof import('child_process')
  const { existsSync, readdirSync } = require('fs') as typeof import('fs')

  // Try login shells (-l) so ~/.zprofile, ~/.bash_profile etc. are sourced
  for (const cmd of [`/bin/zsh -lc 'which ${executable}'`, `/bin/bash -lc 'which ${executable}'`]) {
    try {
      const result = execSync(cmd, { encoding: 'utf8' }).trim()
      if (result && existsSync(result)) {
        log('INFO', `${executable} found: ${result}`)
        return result
      }
    } catch {
      // try next
    }
  }

  // Fallback: scan Homebrew opt for keg-only versioned node installs (e.g. node@24)
  for (const brewPrefix of ['/opt/homebrew/opt', '/usr/local/opt']) {
    if (!existsSync(brewPrefix)) continue
    try {
      const entries = readdirSync(brewPrefix)
        .filter((e) => /^node(@\d+)?$/.test(e))
        .sort()
        .reverse() // prefer highest version
      for (const entry of entries) {
        const candidate = `${brewPrefix}/${entry}/bin/${executable}`
        if (existsSync(candidate)) {
          log('INFO', `${executable} found via Homebrew keg-only: ${candidate}`)
          return candidate
        }
      }
    } catch {
      // continue
    }
  }

  throw new Error(
    `${executable} not found. Please install Node.js from https://nodejs.org/ or use a package manager like Homebrew.`
  )
}

/**
 * Read the stored twin project path from preferences.
 * Returns null if not yet configured (first launch).
 */
function getStoredTwinPath(): string | null {
  try {
    if (!fs.existsSync(STORAGE_FILE)) return null
    const data = fs.readFileSync(STORAGE_FILE, 'utf8')
    const storage = JSON.parse(data)
    return storage[PREFERENCES_KEY_TWIN_PATH] ?? null
  } catch {
    return null
  }
}

interface SlackConfig {
  enabled: boolean
  webhookUrl: string
  userId: string
}

/**
 * Read Slack notification config from preferences.
 */
function getStoredSlackConfig(): SlackConfig {
  try {
    if (!fs.existsSync(STORAGE_FILE)) return { enabled: false, webhookUrl: '', userId: '' }
    const data = fs.readFileSync(STORAGE_FILE, 'utf8')
    const storage = JSON.parse(data)
    return {
      enabled: storage[PREFERENCES_KEY_SLACK_ENABLED] === 'true',
      webhookUrl: storage[PREFERENCES_KEY_SLACK_WEBHOOK_URL] ?? '',
      userId: storage[PREFERENCES_KEY_SLACK_USER_ID] ?? '',
    }
  } catch {
    return { enabled: false, webhookUrl: '', userId: '' }
  }
}

/**
 * Save Slack notification config to preferences.
 */
function storeSlackConfig(config: SlackConfig): void {
  try {
    let storage: Record<string, string> = {}
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      storage = JSON.parse(data)
    }
    storage[PREFERENCES_KEY_SLACK_ENABLED] = String(config.enabled)
    storage[PREFERENCES_KEY_SLACK_WEBHOOK_URL] = config.webhookUrl
    storage[PREFERENCES_KEY_SLACK_USER_ID] = config.userId
    const dir = join(STORAGE_FILE, '..')
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
    log('INFO', 'Slack config stored, enabled:', config.enabled)
  } catch (error) {
    log('ERROR', 'Failed to store Slack config:', error)
  }
}

/**
 * Save the twin project path to preferences.
 */
function storeTwinPath(twinPath: string): void {
  try {
    let storage: Record<string, string> = {}
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      storage = JSON.parse(data)
    }
    storage[PREFERENCES_KEY_TWIN_PATH] = twinPath
    const dir = join(STORAGE_FILE, '..')
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
    log('INFO', 'Twin path stored:', twinPath)
  } catch (error) {
    log('ERROR', 'Failed to store twin path:', error)
  }
}

/**
 * Check if first-launch setup is needed.
 * Returns true if no twin path has been configured yet.
 */
function needsSetup(): boolean {
  return getStoredTwinPath() === null
}

/**
 * Get the resolved twin project path (from preferences or default).
 */
function getResolvedTwinPath(): string {
  return getStoredTwinPath() ?? DEFAULT_TWIN_PROJECT_PATH
}

/**
 * Initialize the vault directory structure at the given path.
 * Copies from the bundled vault-template if available, otherwise creates a basic structure.
 */
function initializeVault(vaultPath: string): void {
  if (fs.existsSync(vaultPath) && fs.readdirSync(vaultPath).length > 0) {
    log('INFO', 'Vault directory already exists and is not empty:', vaultPath)
    return
  }

  log('INFO', 'Initializing vault at:', vaultPath)
  fs.mkdirSync(vaultPath, { recursive: true })

  // Try to find bundled vault-template (in packaged app)
  const templatePaths = [
    join(process.resourcesPath ?? '', 'vault-template'),
    join(__dirname, '..', 'macos', 'vault-template'),
    join(__dirname, '..', '..', 'macos', 'vault-template'),
  ]

  let templateDir: string | null = null
  for (const tp of templatePaths) {
    if (fs.existsSync(tp)) {
      templateDir = tp
      break
    }
  }

  if (templateDir) {
    log('INFO', 'Copying vault template from:', templateDir)
    copyDirRecursive(templateDir, vaultPath)

    // Rename coday.template.yaml to coday.yaml and substitute placeholders
    const templateYaml = join(vaultPath, 'coday', 'coday.template.yaml')
    const targetYaml = join(vaultPath, 'coday', 'coday.yaml')
    if (fs.existsSync(templateYaml) && !fs.existsSync(targetYaml)) {
      let content = fs.readFileSync(templateYaml, 'utf8')
      const slackConfig = getStoredSlackConfig()
      if (slackConfig.enabled && slackConfig.webhookUrl) {
        content = content.replaceAll('SLACK_NOTIFY_WEBHOOK_URL', slackConfig.webhookUrl)
        log('INFO', 'Substituted SLACK_NOTIFY_WEBHOOK_URL in coday.yaml')
      }
      if (slackConfig.enabled && slackConfig.userId) {
        content = content.replaceAll('SLACK_USER_ID', slackConfig.userId)
        log('INFO', 'Substituted SLACK_USER_ID in coday.yaml')
      }
      fs.writeFileSync(targetYaml, content, 'utf8')
      fs.unlinkSync(templateYaml)
      log('INFO', 'Created coday.yaml from template')
    }
  } else {
    log('INFO', 'No vault template found, creating basic structure')
    // Create basic structure
    for (const dir of ['.obsidian', 'inbox', 'notes', 'projects']) {
      fs.mkdirSync(join(vaultPath, dir), { recursive: true })
    }
    fs.writeFileSync(join(vaultPath, 'README.md'), '# CodayTwin Vault\n\nYour digital twin workspace.\n', 'utf8')
    fs.writeFileSync(
      join(vaultPath, '.obsidian', 'app.json'),
      JSON.stringify({ alwaysUpdateLinks: true, newFileLocation: 'folder', newFileFolderPath: 'inbox' }, null, 2),
      'utf8'
    )
    fs.writeFileSync(join(vaultPath, 'coday.yml'), 'description: "CodayTwin digital workspace"\n', 'utf8')
  }

  log('INFO', 'Vault initialized at:', vaultPath)
}

/**
 * Recursively copy a directory.
 */
function copyDirRecursive(src: string, dest: string): void {
  fs.mkdirSync(dest, { recursive: true })
  const entries = fs.readdirSync(src, { withFileTypes: true })
  for (const entry of entries) {
    const srcPath = join(src, entry.name)
    const destPath = join(dest, entry.name)
    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, destPath)
    } else {
      fs.copyFileSync(srcPath, destPath)
    }
  }
}

/**
 * Copy scheduler YAML files from src to dest, rewriting the createdBy field
 * to match the current OS username so the user can see and manage them.
 */
function copySchedulersWithOwner(src: string, dest: string): void {
  const os = require('os') as typeof import('os')
  const username = os.userInfo().username
  const sanitizedUsername = username.replace(/[^a-zA-Z0-9]/g, '_')

  fs.mkdirSync(dest, { recursive: true })
  const entries = fs.readdirSync(src, { withFileTypes: true })
  for (const entry of entries) {
    if (entry.isDirectory()) continue
    if (!entry.name.endsWith('.yml')) continue
    const srcPath = join(src, entry.name)
    const destPath = join(dest, entry.name)
    try {
      let content = fs.readFileSync(srcPath, 'utf8')
      // Replace the createdBy field value with the current OS username
      content = content.replace(/^(createdBy:\s*).*$/m, `$1${sanitizedUsername}`)
      fs.writeFileSync(destPath, content, 'utf8')
    } catch (error) {
      log('WARN', `Failed to copy scheduler ${entry.name}:`, error)
    }
  }
}

/**
 * Ensure the Coday project config exists for the Twin workspace.
 * Creates ~/.coday/projects/CodayTwin/project.yaml if it doesn't exist,
 * pointing to the resolved twin project path.
 */
function ensureTwinProjectConfig(twinProjectPath: string): void {
  const os = require('os') as typeof import('os')
  const configDir = join(os.homedir(), '.coday', 'projects', 'CodayTwin')
  const configFile = join(configDir, 'project.yaml')

  // Ensure directory exists
  fs.mkdirSync(configDir, { recursive: true })

  if (!fs.existsSync(configFile)) {
    log('INFO', 'Creating Twin project config at:', configFile)

    // Write minimal project config
    const config = ['version: 1', `path: "${twinProjectPath}"`, 'storage:', '  type: file', 'agents: []', ''].join('\n')

    fs.writeFileSync(configFile, config, 'utf8')
    log('INFO', 'Twin project config created successfully')
  } else {
    log('INFO', 'Twin project config already exists at:', configFile)
  }

  // Copy schedulers from macos/schedulers into the project config dir
  const schedulersDest = join(configDir, 'schedulers')
  if (!fs.existsSync(schedulersDest)) {
    const schedulersSrcCandidates = [
      join(process.resourcesPath ?? '', 'schedulers'),
      join(__dirname, '..', 'macos', 'schedulers'),
      join(__dirname, '..', '..', 'macos', 'schedulers'),
    ]
    let schedulersSrc: string | null = null
    for (const candidate of schedulersSrcCandidates) {
      if (fs.existsSync(candidate)) {
        schedulersSrc = candidate
        break
      }
    }
    if (schedulersSrc) {
      log('INFO', 'Copying schedulers from:', schedulersSrc, 'to:', schedulersDest)
      copySchedulersWithOwner(schedulersSrc, schedulersDest)
      log('INFO', 'Schedulers copied successfully')
    } else {
      log('WARN', 'No schedulers source directory found, skipping schedulers copy')
    }
  } else {
    log('INFO', 'Schedulers directory already exists at:', schedulersDest)
  }
}

/**
 * Show the first-launch setup window and wait for the user to choose a path.
 * Returns the chosen twin project path.
 */
function showSetupWindow(): Promise<string> {
  return new Promise<string>((resolve) => {
    const setupWindow = new BrowserWindow({
      width: 560,
      height: 300,
      frame: false,
      resizable: false,
      useContentSize: true,
      transparent: true,
      icon: join(__dirname, 'icon.png'),
      webPreferences: {
        preload: join(__dirname, 'preload.js'),
        nodeIntegration: false,
        contextIsolation: true,
      },
    })

    // IPC handler: resize window to fit content
    ipcMain.handle('window:resizeToContent', (_event: IpcMainInvokeEvent, contentHeight: number) => {
      const win = BrowserWindow.fromWebContents(_event.sender)
      if (win) {
        const [w = 560] = win.getContentSize()
        win.setContentSize(w, Math.ceil(contentHeight), false)
      }
    })

    // IPC handler: open native folder picker
    ipcMain.handle('setup:browseFolder', async () => {
      const result = await dialog.showOpenDialog(setupWindow, {
        title: 'Choose CodayTwin folder location',
        defaultPath: app.getPath('home'),
        properties: ['openDirectory', 'createDirectory'],
      })
      if (result.canceled || result.filePaths.length === 0) return null
      // Append CodayTwin to the selected directory
      return join(result.filePaths[0]!!, 'CodayTwin')
    })

    // IPC handler: check if Anthropic API key is configured
    ipcMain.handle('setup:checkAnthropicApiKey', (): { configured: boolean; source: string | null } => {
      // Check environment variable first
      if (process.env['ANTHROPIC_API_KEY']) {
        return { configured: true, source: 'env' }
      }

      // Check user.yaml
      const os = require('os') as typeof import('os')
      const username = os.userInfo().username
      const sanitizedUsername = username.replace(/[^a-zA-Z0-9]/g, '_')
      const userConfigPath = join(os.homedir(), '.coday', 'users', sanitizedUsername, 'user.yaml')

      try {
        if (fs.existsSync(userConfigPath)) {
          const content = fs.readFileSync(userConfigPath, 'utf8')
          // Parse line by line to handle all YAML structures correctly
          const lines = content.split('\n')
          let inAiArray = false
          let inAnthropicEntry = false

          for (const line of lines) {
            // ai: [] is an empty inline array — no entries
            if (/^ai:\s*\[\s*\]/.test(line)) {
              inAiArray = false
              continue
            }

            // Detect start of ai array (block form)
            if (/^ai:\s*$/.test(line) || /^ai:\s*#/.test(line)) {
              inAiArray = true
              continue
            }

            // If we hit another top-level key, we've left the ai section
            if (inAiArray && /^\S/.test(line) && !line.startsWith('#')) {
              inAiArray = false
              inAnthropicEntry = false
            }

            if (inAiArray) {
              // Detect list entry: "  - name: anthropic"
              if (/^\s+-\s+name:\s*anthropic\s*$/.test(line)) {
                inAnthropicEntry = true
                continue
              }
              // Detect start of a different list entry
              if (/^\s+-\s/.test(line) && inAnthropicEntry) {
                inAnthropicEntry = false
              }
              // Look for apiKey within the anthropic entry
              if (inAnthropicEntry && /^\s+apiKey:\s*\S+/.test(line)) {
                return { configured: true, source: 'user.yaml' }
              }
            }
          }
        }
      } catch (error) {
        log('ERROR', 'Failed to check user config for API key:', error)
      }

      return { configured: false, source: null }
    })

    // IPC handler: save Anthropic API key to user.yaml
    ipcMain.handle('setup:saveAnthropicApiKey', (_event: IpcMainInvokeEvent, apiKey: string): void => {
      const os = require('os') as typeof import('os')
      const username = os.userInfo().username
      const sanitizedUsername = username.replace(/[^a-zA-Z0-9]/g, '_')
      const userDir = join(os.homedir(), '.coday', 'users', sanitizedUsername)
      const userConfigPath = join(userDir, 'user.yaml')

      try {
        fs.mkdirSync(userDir, { recursive: true })

        if (fs.existsSync(userConfigPath)) {
          const content = fs.readFileSync(userConfigPath, 'utf8')
          const lines = content.split('\n')
          const result: string[] = []
          let inAiArray = false
          let inAnthropicEntry = false
          let anthropicHandled = false
          let aiSectionFound = false
          let apiKeyWritten = false

          for (let i = 0; i < lines.length; i++) {
            const line = lines[i]!

            // Handle "ai: []" — replace with block form including our entry
            if (/^ai:\s*\[\s*\]/.test(line)) {
              result.push('ai:')
              result.push('  - name: anthropic')
              result.push(`    apiKey: ${apiKey}`)
              aiSectionFound = true
              anthropicHandled = true
              apiKeyWritten = true
              continue
            }

            // Detect start of ai array (block form)
            if (/^ai:\s*$/.test(line) || /^ai:\s*#/.test(line)) {
              inAiArray = true
              aiSectionFound = true
              result.push(line)
              continue
            }

            // If we hit another top-level key while in ai section, check if we need to add anthropic
            if (inAiArray && /^\S/.test(line) && !line.startsWith('#')) {
              if (!anthropicHandled) {
                // Add anthropic entry at end of ai array
                result.push('  - name: anthropic')
                result.push(`    apiKey: ${apiKey}`)
                anthropicHandled = true
                apiKeyWritten = true
              }
              inAiArray = false
              inAnthropicEntry = false
            }

            if (inAiArray) {
              // Detect "  - name: anthropic"
              if (/^\s+-\s+name:\s*anthropic\s*$/.test(line)) {
                inAnthropicEntry = true
                anthropicHandled = true
                result.push(line)
                continue
              }
              // Detect start of a different list entry — if we were in anthropic and didn't write apiKey, add it
              if (/^\s+-\s/.test(line) && inAnthropicEntry) {
                if (!apiKeyWritten) {
                  result.push(`    apiKey: ${apiKey}`)
                  apiKeyWritten = true
                }
                inAnthropicEntry = false
              }
              // Replace existing apiKey in anthropic entry
              if (inAnthropicEntry && /^\s+apiKey:/.test(line)) {
                result.push(`    apiKey: ${apiKey}`)
                apiKeyWritten = true
                continue
              }
            }

            result.push(line)
          }

          // Handle edge case: anthropic entry was the last in the file without apiKey
          if (inAnthropicEntry && !apiKeyWritten) {
            result.push(`    apiKey: ${apiKey}`)
            apiKeyWritten = true
            anthropicHandled = true
          }

          // Handle edge case: ai array was the last section and no anthropic was found
          if (inAiArray && !anthropicHandled) {
            result.push('  - name: anthropic')
            result.push(`    apiKey: ${apiKey}`)
            anthropicHandled = true
          }

          // If no ai section existed at all, append one
          if (!aiSectionFound) {
            if (result.length > 0 && result[result.length - 1] !== '') {
              result.push('')
            }
            result.push('ai:')
            result.push('  - name: anthropic')
            result.push(`    apiKey: ${apiKey}`)
          }

          // Write back, ensuring file ends with newline
          let output = result.join('\n')
          if (!output.endsWith('\n')) {
            output += '\n'
          }
          fs.writeFileSync(userConfigPath, output, 'utf8')
          log('INFO', 'Saved Anthropic API key to user config')
        } else {
          // Create new user.yaml
          const newConfig = `version: 2\nai:\n  - name: anthropic\n    apiKey: ${apiKey}\n`
          fs.writeFileSync(userConfigPath, newConfig, 'utf8')
          log('INFO', 'Created new user config with Anthropic API key')
        }
      } catch (error) {
        log('ERROR', 'Failed to save Anthropic API key:', error)
        throw error
      }
    })

    // IPC handler: user confirmed setup
    ipcMain.handle('setup:confirm', async (_event, customPath: string | null, slackConfig: SlackConfig | null) => {
      const chosenPath = customPath ?? DEFAULT_TWIN_PROJECT_PATH
      log('INFO', 'Setup confirmed with path:', chosenPath)

      // Store the path
      storeTwinPath(chosenPath)

      // Store Slack config if provided
      if (slackConfig !== null && slackConfig !== undefined) {
        storeSlackConfig(slackConfig)
        // Apply to project.yaml after vault is initialized (done later in initialize())
      }

      // Clean up IPC handlers
      ipcMain.removeHandler('setup:browseFolder')
      ipcMain.removeHandler('setup:confirm')
      ipcMain.removeHandler('setup:checkAnthropicApiKey')
      ipcMain.removeHandler('setup:saveAnthropicApiKey')

      // Close setup window
      setupWindow.close()

      resolve(chosenPath)
    })

    // If user closes the window without confirming, use default
    setupWindow.on('closed', () => {
      ipcMain.removeHandler('window:resizeToContent')
      ipcMain.removeHandler('setup:browseFolder')
      ipcMain.removeHandler('setup:confirm')
      ipcMain.removeHandler('setup:checkAnthropicApiKey')
      ipcMain.removeHandler('setup:saveAnthropicApiKey')
      ipcMain.removeHandler('setup:getSlackConfig')
      // If not yet resolved (user closed without confirming), use default
      const storedPath = getStoredTwinPath()
      if (!storedPath) {
        storeTwinPath(DEFAULT_TWIN_PROJECT_PATH)
        resolve(DEFAULT_TWIN_PROJECT_PATH)
      }
    })

    void setupWindow.loadFile(join(__dirname, 'setup.html'))
  })
}

/**
 * Clear stored twin path and show setup window again.
 * Returns the newly chosen path. Guard prevents multiple windows from opening.
 */
async function resetAndShowSetup(): Promise<string> {
  if (isSetupWindowOpen) {
    log('WARN', 'Setup window already open, ignoring request')
    return getResolvedTwinPath()
  }

  isSetupWindowOpen = true
  log('INFO', 'Resetting install location, clearing stored twin path')

  try {
    let storage: Record<string, string> = {}
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      storage = JSON.parse(data)
    }
    delete storage[PREFERENCES_KEY_TWIN_PATH]
    fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
    log('INFO', 'Cleared stored twin path from preferences')
  } catch (error) {
    log('ERROR', 'Failed to clear stored twin path:', error)
  }

  try {
    const newPath = await showSetupWindow()
    log('INFO', 'User selected new install location:', newPath)
    return newPath
  } finally {
    isSetupWindowOpen = false
  }
}

/**
 * Show the preferences window for viewing and changing settings.
 */
function showPreferencesWindow(): void {
  if (isPreferencesWindowOpen) {
    log('WARN', 'Preferences window already open, ignoring request')
    return
  }

  isPreferencesWindowOpen = true
  log('INFO', 'Opening preferences window')

  const preferencesWindow = new BrowserWindow({
    width: 560,
    height: 300,
    frame: false,
    resizable: false,
    useContentSize: true,
    transparent: true,
    icon: join(__dirname, 'icon.png'),
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  })

  // IPC handler: resize window to fit content
  ipcMain.handle('window:resizeToContent', (_event: IpcMainInvokeEvent, contentHeight: number) => {
    const win = BrowserWindow.fromWebContents(_event.sender)
    if (win) {
      const [w = 560] = win.getContentSize()
      win.setContentSize(w, Math.ceil(contentHeight), false)
    }
  })

  ipcMain.handle('preferences:getCurrentPath', () => {
    return getResolvedTwinPath()
  })

  ipcMain.handle('preferences:browseFolder', async () => {
    const result = await dialog.showOpenDialog(preferencesWindow, {
      title: 'Choose new CodayTwin folder location',
      defaultPath: app.getPath('home'),
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled || result.filePaths.length === 0) return null
    return join(result.filePaths[0]!!, 'CodayTwin')
  })

  ipcMain.handle('preferences:save', async (_event: IpcMainInvokeEvent, newPath: string) => {
    log('INFO', 'Saving new workspace location:', newPath)
    storeTwinPath(newPath)
    initializeVault(newPath)
    ensureTwinProjectConfig(newPath)
    log('INFO', 'Workspace location updated successfully')

    preferencesWindow.close()

    if (mainWindow) {
      mainWindow.reload()
    }
  })

  ipcMain.handle('preferences:close', () => {
    preferencesWindow.close()
  })

  ipcMain.handle('preferences:getSlackConfig', (): SlackConfig => {
    return getStoredSlackConfig()
  })

  ipcMain.handle('preferences:saveSlackConfig', async (_event: IpcMainInvokeEvent, config: SlackConfig) => {
    log('INFO', 'Saving Slack config, enabled:', config.enabled)
    storeSlackConfig(config)
    log('INFO', 'Slack config saved')
  })

  preferencesWindow.on('closed', () => {
    ipcMain.removeHandler('preferences:getCurrentPath')
    ipcMain.removeHandler('preferences:browseFolder')
    ipcMain.removeHandler('preferences:save')
    ipcMain.removeHandler('preferences:close')
    ipcMain.removeHandler('preferences:getSlackConfig')
    ipcMain.removeHandler('preferences:saveSlackConfig')
    ipcMain.removeHandler('window:resizeToContent')
    isPreferencesWindowOpen = false
    log('INFO', 'Preferences window closed')
  })

  void preferencesWindow.loadFile(join(__dirname, 'preferences.html'))
}

/**
 * Setup native macOS application menu with Preferences entry
 */
function setupApplicationMenu(): void {
  const template = [
    {
      label: 'CodayTwin',
      submenu: [
        { role: 'about', label: 'About CodayTwin' },
        { type: 'separator' },
        {
          label: 'Preferences...',
          accelerator: 'CmdOrCtrl+,',
          click: () => {
            showPreferencesWindow()
          },
        },
        { type: 'separator' },
        { role: 'services', label: 'Services' },
        { type: 'separator' },
        { role: 'hide', label: 'Hide CodayTwin' },
        { role: 'hideOthers', label: 'Hide Others' },
        { role: 'unhide', label: 'Show All' },
        { type: 'separator' },
        { role: 'quit', label: 'Quit CodayTwin' },
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

/**
 * Start the Coday web server pointing at the Twin project path
 */
async function startCodayServer(): Promise<void> {
  try {
    log('INFO', 'Starting CodayTwin server...')
    log('INFO', 'Twin project path:', DEFAULT_TWIN_PROJECT_PATH)

    // Find node and run the installed package via npx
    log('INFO', 'Finding node executable...')

    const nodePath = findExecutable('node')

    if (!nodePath) {
      const error = new Error(
        'Node.js not found. Please install Node.js from https://nodejs.org/ or use a package manager like Homebrew.'
      )
      ;(error as any).userFacing = true
      throw error
    }

    log('INFO', 'Using node at:', nodePath)

    // Check for npx
    const npxPath = findExecutable('npx')

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

    const twinProjectPath = getResolvedTwinPath()

    // Ensure vault directory and Coday project config exist
    initializeVault(twinProjectPath)
    ensureTwinProjectConfig(twinProjectPath)

    const args = ['--yes', '@whoz-oss/coday-web', '--base-url=coday-twin://', '--coday_project=CodayTwin']

    log('INFO', 'Spawning:', npxPath, args.join(' '))
    log('INFO', 'Twin project path:', twinProjectPath)

    // Set up environment
    const env = { ...process.env }
    try {
      const userPath = execSync('/usr/bin/env echo $PATH', { shell: '/bin/zsh' }).toString().trim()
      env['PATH'] = `${userPath}:/usr/local/bin:/opt/homebrew/bin:${process.env['PATH']}`
    } catch {
      env['PATH'] = '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'
    }

    // Use a fixed port so CodayTwin is always reachable on the same address
    const CODAY_TWIN_PORT = '3050'
    env['PORT'] = CODAY_TWIN_PORT
    log('INFO', `Using fixed port: ${CODAY_TWIN_PORT}`)

    // Use a temp directory as cwd to prevent npx from resolving to local workspace packages
    const npxCwd = app.getPath('temp')
    log('INFO', 'Using npx cwd:', npxCwd)

    // Start the server
    serverProcess = spawn(npxPath, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
      cwd: npxCwd,
      shell: false,
    })

    return new Promise<void>((resolve, reject) => {
      let serverReady = false
      let settled = false

      const settle = (fn: () => void) => {
        if (!settled) {
          settled = true
          fn()
        }
      }

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
              setTimeout(() => settle(() => resolve()), 500)
            }
          }
        })
      }

      if (serverProcess!.stderr) {
        serverProcess!.stderr.on('data', (data: Buffer) => {
          const text = data.toString()
          log('ERROR', '[Server Error]:', text)

          // Detect port-in-use error signalled by the server process
          if (text.includes('PORT_IN_USE:')) {
            const portMatch = text.match(/PORT_IN_USE:.*?(Port \d+ is already in use[^\n]*)/)
            const detail = portMatch ? portMatch[1] : `Port ${env['PORT']} is already in use.`
            const portError: any = new Error(
              `${detail}\n\nPlease close any other application using this port and restart CodayTwin.`
            )
            portError.userFacing = true
            settle(() => reject(portError))
          }
        })
      }

      serverProcess!.on('error', (error: Error) => {
        log('ERROR', 'Failed to start server:', error)
        const wrappedError: any = new Error(`Failed to start CodayTwin server: ${error.message}`)
        wrappedError.userFacing = true
        settle(() => reject(wrappedError))
      })

      serverProcess!.on('exit', (code: number | null) => {
        log('INFO', 'Server process exited with code:', code)
        serverProcess = null
        // Only reject if the server exited before it was ready
        if (!serverReady) {
          const error: any = new Error(
            `Server process exited unexpectedly with code ${code}.\n\nCheck logs for details.`
          )
          error.userFacing = true
          settle(() => reject(error))
        }
      })

      // No hard timeout — the loading screen stays visible while npx downloads.
      // The promise only settles when the server URL is detected or the process exits/errors.
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
    log('INFO', 'Stopping CodayTwin server...')
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
 * Register a custom 'coday-asset' protocol and an HTTP interceptor so that
 * favicon / logo requests from the Angular client are served from the local
 * bundled icon.png.
 *
 * Strategy:
 *  1. Register a privilege-bearing custom scheme 'coday-asset' that is
 *     treated as secure and supports fetch/CORS so the renderer can load it.
 *  2. After app.whenReady(), handle 'coday-asset://icon' by reading and
 *     returning icon.png bytes directly — no file:// cross-origin issues.
 *  3. Intercept HTTP requests for known favicon paths and redirect them to
 *     'coday-asset://icon' instead of file://.
 *
 * registerFaviconScheme() must be called BEFORE app.whenReady() (scheme
 * privileges must be set before the app is ready).
 * registerFaviconInterceptor() must be called AFTER app.whenReady().
 */
function registerFaviconScheme(): void {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: 'coday-asset',
      privileges: {
        secure: true,
        standard: true,
        supportFetchAPI: true,
        corsEnabled: true,
        bypassCSP: true,
      },
    },
  ])
  log('INFO', 'coday-asset scheme registered as privileged')
}

function registerFaviconInterceptor(): void {
  const iconPath = join(__dirname, 'icon.png')

  if (!fs.existsSync(iconPath)) {
    log('WARN', 'registerFaviconInterceptor: icon.png not found at', iconPath)
    return
  }

  // Handle coday-asset://icon — serve the bundled icon.png bytes
  protocol.handle('coday-asset', (request) => {
    const url = new URL(request.url)
    if (url.hostname === 'icon') {
      try {
        const data = fs.readFileSync(iconPath)
        return new Response(data, {
          headers: { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' },
        })
      } catch (err) {
        log('ERROR', 'Failed to read icon.png:', err)
        return new Response('Not found', { status: 404 })
      }
    }
    return new Response('Not found', { status: 404 })
  })

  // Intercept HTTP requests for known favicon / logo paths and redirect to
  // our custom scheme so the renderer receives the local icon.
  session.defaultSession.webRequest.onBeforeRequest({ urls: ['http://*/*'] }, (details, callback) => {
    try {
      const url = new URL(details.url)
      const pathname = url.pathname.toLowerCase()

      const isFaviconRequest =
        pathname === '/favicon.ico' ||
        pathname === '/favicon.png' ||
        pathname.endsWith('/coday-logo.png') ||
        pathname.endsWith('/coday-logo.ico')

      if (isFaviconRequest) {
        log('INFO', 'Redirecting favicon request to local icon:', details.url)
        callback({ redirectURL: 'coday-asset://icon' })
        return
      }
    } catch {
      // Ignore URL parse errors
    }
    callback({})
  })

  log('INFO', 'Favicon interceptor registered, serving:', iconPath)
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
    title: 'CodayTwin',
    show: false, // Don't show until ready
  })

  // Setup native macOS application menu
  setupApplicationMenu()

  // Prevent navigation away from the Coday server
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith(serverUrl!)) {
      log('WARN', 'Blocked navigation to external URL:', url)
      event.preventDefault()
    }
  })

  // Handle failed loads by reloading the correct URL
  mainWindow.webContents.on('did-fail-load', (_, errorCode, errorDescription, validatedURL) => {
    log('ERROR', 'Failed to load:', validatedURL, 'Error:', errorCode, errorDescription)
    if (mainWindow && serverUrl) {
      log('INFO', 'Reloading server URL:', serverUrl)
      void mainWindow.loadURL(serverUrl)
    }
  })

  // Intercept reload attempts to ensure we reload the root URL
  mainWindow.webContents.on('before-input-event', (event, input) => {
    if ((input.meta && input.key.toLowerCase() === 'r') || input.key === 'F5') {
      if (input.type === 'keyDown' && mainWindow && serverUrl) {
        event.preventDefault()
        log('INFO', 'Intercepted reload, reloading root URL:', serverUrl)
        void mainWindow.loadURL(serverUrl)
      }
    }
  })

  // Flag the renderer as running inside desktop-twin by adding a CSS class
  // on <body>. Angular SCSS can then use `body.desktop-twin` selectors for
  // app-specific overrides without touching the shared client source.
  // Inject desktop-twin identity class and dedicated CSS overrides on every page load
  const desktopTwinCss = fs.readFileSync(join(__dirname, 'desktop-twin.css'), 'utf8')
  mainWindow.webContents.on('did-finish-load', () => {
    void mainWindow!.webContents.executeJavaScript(`document.body.classList.add('desktop-twin');`)
    void mainWindow!.webContents.insertCSS(desktopTwinCss)
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
  if (storageHandlersRegistered) {
    log('INFO', 'Storage handlers already registered, skipping')
    return
  }
  storageHandlersRegistered = true

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
    // Parse URL: coday-twin://project/my-project/thread/abc-123
    const match = url.match(/coday-twin:\/\/project\/([^/]+)\/thread\/([^/?#]+)/)

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
    log('INFO', 'Initializing CodayTwin Desktop...')

    // Setup storage handlers
    setupStorageHandlers()

    // First-launch setup: let user choose the vault path
    if (needsSetup()) {
      log('INFO', 'First launch detected, showing setup window')
      await resetAndShowSetup()
      log('INFO', 'Setup complete, twin path:', getResolvedTwinPath())
    }

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
      showErrorDialog('CodayTwin - Startup Error', errorMessage)
    } else {
      showErrorDialog(
        'CodayTwin - Unexpected Error',
        `An unexpected error occurred while starting CodayTwin:\n\n${errorMessage}\n\nPlease check the console logs for more details.`
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

// Register custom asset scheme privileges before app is ready
registerFaviconScheme()

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
void app.whenReady().then(() => {
  // Register favicon interceptor before any window is created so the
  // Angular client receives our local icon.png for all favicon requests.
  registerFaviconInterceptor()
  void initialize()
})

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
    'CodayTwin - Fatal Error',
    `A fatal error occurred:\n\n${error.message}\n\nThe application will now close.`
  )
  stopCodayServer()
  app.quit()
})
