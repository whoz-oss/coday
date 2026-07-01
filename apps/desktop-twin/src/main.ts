import type { BrowserWindow as BrowserWindowType, App, IpcMainInvokeEvent } from 'electron'
import type { ChildProcess } from 'child_process'
import {
  log,
  initLogger,
  findExecutable,
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

const { app, BrowserWindow, dialog, ipcMain, Menu, session, protocol } = require('electron') as {
  app: App & { isQuitting?: boolean }
  BrowserWindow: typeof import('electron').BrowserWindow
  dialog: typeof import('electron').dialog
  ipcMain: typeof import('electron').ipcMain
  Menu: typeof import('electron').Menu
  session: typeof import('electron').session
  protocol: typeof import('electron').protocol
}
const { join } = require('path') as typeof import('path')
const fs = require('fs') as typeof import('fs')

// ---------------------------------------------------------------------------
// App-specific constants
// ---------------------------------------------------------------------------

const PROTOCOL_NAME = 'coday-twin'
const CODAY_TWIN_PORT = '3050'
const SERVER_ARGS = ['--yes', '@whoz-oss/coday-web', '--base-url=coday-twin://', '--coday_project=CodayTwin']
const DEFAULT_TWIN_PROJECT_PATH = join(app.getPath('home'), 'CodayTwin')
const PREFERENCES_KEY_TWIN_PATH = 'twinProjectPath'
const PREFERENCES_KEY_SLACK_ENABLED = 'slackEnabled'
const PREFERENCES_KEY_SLACK_WEBHOOK_URL = 'slackWebhookUrl'
const PREFERENCES_KEY_SLACK_USER_ID = 'slackUserId'
const PREFERENCES_KEY_GOOGLE_CLIENT_ID = 'googleClientId'
const PREFERENCES_KEY_GOOGLE_CLIENT_SECRET = 'googleClientSecret'

const STORAGE_FILE = join(app.getPath('userData'), 'preferences.json')
const LOG_FILE = join(app.getPath('userData'), 'coday-twin-desktop.log')

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

initLogger(app, 'coday-twin-desktop.log')

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

interface GoogleCalendarConfig {
  clientId: string
  clientSecret: string
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
 * Read Google Calendar OAuth2 config from preferences.
 */
function getStoredGoogleCalendarConfig(): GoogleCalendarConfig {
  try {
    if (!fs.existsSync(STORAGE_FILE)) return { clientId: '', clientSecret: '' }
    const data = fs.readFileSync(STORAGE_FILE, 'utf8')
    const storage = JSON.parse(data)
    return {
      clientId: storage[PREFERENCES_KEY_GOOGLE_CLIENT_ID] ?? '',
      clientSecret: storage[PREFERENCES_KEY_GOOGLE_CLIENT_SECRET] ?? '',
    }
  } catch {
    return { clientId: '', clientSecret: '' }
  }
}

/**
 * Save Google Calendar OAuth2 config to preferences.
 */
function storeGoogleCalendarConfig(config: GoogleCalendarConfig): void {
  try {
    let storage: Record<string, string> = {}
    if (fs.existsSync(STORAGE_FILE)) {
      const data = fs.readFileSync(STORAGE_FILE, 'utf8')
      storage = JSON.parse(data)
    }
    storage[PREFERENCES_KEY_GOOGLE_CLIENT_ID] = config.clientId
    storage[PREFERENCES_KEY_GOOGLE_CLIENT_SECRET] = config.clientSecret
    const dir = join(STORAGE_FILE, '..')
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(STORAGE_FILE, JSON.stringify(storage, null, 2), 'utf8')
    log('INFO', 'Google Calendar config stored')
  } catch (error) {
    log('ERROR', 'Failed to store Google Calendar config:', error)
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
  const vaultExists = fs.existsSync(vaultPath) && fs.readdirSync(vaultPath).length > 0

  log(
    'INFO',
    vaultExists ? 'Vault directory exists, ensuring coday/ folder is present:' : 'Initializing vault at:',
    vaultPath
  )
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
    if (!vaultExists) {
      // Brand-new vault: copy the full template (all dirs)
      log('INFO', 'Copying full vault template from:', templateDir)
      copyDirRecursive(templateDir, vaultPath)
    } else {
      // Pre-existing vault: only copy the coday/ subfolder, non-destructively
      const codayTemplateSrc = join(templateDir, 'coday')
      if (fs.existsSync(codayTemplateSrc)) {
        log('INFO', 'Copying coday/ subfolder non-destructively from:', codayTemplateSrc)
        copyDirNonDestructive(codayTemplateSrc, join(vaultPath, 'coday'))
      }
    }

    // Rename coday.template.yaml to coday.yaml and substitute placeholders
    // (applies for both new and pre-existing vaults; guarded by coday.yaml absence)
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
 * Recursively copy a directory, skipping files that already exist at the destination.
 * Directories are always created if missing. Existing files are never overwritten.
 */
function copyDirNonDestructive(src: string, dest: string): void {
  fs.mkdirSync(dest, { recursive: true })
  const entries = fs.readdirSync(src, { withFileTypes: true })
  for (const entry of entries) {
    // Skip template files — they are meant to be processed, not copied raw
    if (entry.name.endsWith('.template.yaml') || entry.name.endsWith('.template.yml')) continue
    const srcPath = join(src, entry.name)
    const destPath = join(dest, entry.name)
    if (entry.isDirectory()) {
      copyDirNonDestructive(srcPath, destPath)
    } else if (!fs.existsSync(destPath)) {
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
 * Replace every occurrence of `oldValue` with `newValue` in `content`,
 * but only within lines that also contain `contextSubstring`.
 * This avoids accidental replacement of the same string in unrelated parts of the file.
 */
function replaceInContext(content: string, oldValue: string, newValue: string, contextSubstring: string): string {
  return content
    .split('\n')
    .map((line) => {
      if (line.includes(contextSubstring) && line.includes(oldValue)) {
        return line.split(oldValue).join(newValue)
      }
      return line
    })
    .join('\n')
}

/**
 * Apply Slack config into the live coday.yaml inside the vault.
 * previousConfig is used as a hint for the old value, but the function also
 * reads the current file content to discover the actual stored value, making
 * it robust against manual edits and stale preferences.json data.
 *
 * Replacement strategy (in priority order):
 *   1. The value currently embedded in the file (extracted via regex from the curl command)
 *   2. The value from previousConfig (what preferences.json remembers)
 *   3. The original placeholder string (SLACK_NOTIFY_WEBHOOK_URL / SLACK_USER_ID)
 *
 * The webhook URL replacement is scoped to lines containing "curl -X POST" to
 * avoid accidentally corrupting unrelated YAML content.
 * The userId replacement is global (it appears in both parametersDescription and
 * the command line, and both occurrences should be updated).
 */
function applySlackConfigToVault(config: SlackConfig, previousConfig: SlackConfig): void {
  const vaultPath = getResolvedTwinPath()
  const codayYaml = join(vaultPath, 'coday', 'coday.yaml')

  if (!fs.existsSync(codayYaml)) {
    log('WARN', 'applySlackConfigToVault: coday.yaml not found at', codayYaml)
    return
  }

  try {
    let content = fs.readFileSync(codayYaml, 'utf8')
    let changed = false

    if (config.webhookUrl) {
      // Discover the webhook URL currently embedded in the curl command line
      const curlMatch = /curl -X POST (\S+)/.exec(content)
      const valueInFile = curlMatch ? curlMatch[1]! : null

      // Build candidate list: current file value → previous stored value → placeholder
      const candidates = [
        ...new Set([valueInFile, previousConfig.webhookUrl, 'SLACK_NOTIFY_WEBHOOK_URL'].filter(Boolean)),
      ] as string[]

      for (const candidate of candidates) {
        if (candidate !== config.webhookUrl && content.includes(candidate)) {
          // Scope replacement to lines containing the curl command to avoid
          // accidentally replacing the same string elsewhere in the YAML
          const updated = replaceInContext(content, candidate, config.webhookUrl, 'curl -X POST')
          if (updated !== content) {
            content = updated
            changed = true
            log('INFO', `Replaced webhook URL (was "${candidate}") in coday.yaml curl command`)
            break
          }
          // Fallback: full replace if the curl context scope found nothing
          // (handles edge cases where the template format differs)
          content = content.split(candidate).join(config.webhookUrl)
          changed = true
          log('INFO', `Replaced webhook URL (was "${candidate}") in coday.yaml (full replace fallback)`)
          break
        }
      }
    }

    if (config.userId) {
      // The user ID appears in both parametersDescription and the command line.
      // Both occurrences should be updated — a global replace is correct here.
      // Discover the userId currently in the parametersDescription block.
      const userIdMatch = /slackUserId:\s*(\S+)/.exec(content)
      const userIdInFile = userIdMatch ? userIdMatch[1]! : null

      const candidates = [
        ...new Set([userIdInFile, previousConfig.userId, 'SLACK_USER_ID'].filter(Boolean)),
      ] as string[]

      for (const candidate of candidates) {
        if (candidate !== config.userId && content.includes(candidate)) {
          content = content.split(candidate).join(config.userId)
          changed = true
          log('INFO', `Replaced userId (was "${candidate}") in coday.yaml`)
          break
        }
      }
    }

    if (changed) {
      fs.writeFileSync(codayYaml, content, 'utf8')
      log('INFO', 'Slack config applied to coday.yaml')
    } else {
      log('INFO', 'No changes needed in coday.yaml for Slack config')
    }
  } catch (error) {
    log('ERROR', 'Failed to apply Slack config to coday.yaml:', error)
  }
}

/**
 * Apply Google Calendar credentials into an existing project.yaml.
 * Uses text-based substitution to safely replace placeholder values or
 * update existing credentials without disturbing the rest of the YAML structure.
 * Handles two cases:
 *   1. Placeholder values (GOOGLE_OAUTH2_CLIENT_ID / GOOGLE_OAUTH2_CLIENT_SECRET) → replaced in-place
 *   2. Previously stored credentials → replaced with new values
 */
function applyGoogleCalendarConfigToProject(config: GoogleCalendarConfig, previousConfig: GoogleCalendarConfig): void {
  const os = require('os') as typeof import('os')
  const configFile = join(os.homedir(), '.coday', 'projects', 'CodayTwin', 'project.yaml')

  if (!fs.existsSync(configFile)) {
    log('WARN', 'applyGoogleCalendarConfigToProject: project.yaml not found, skipping')
    return
  }

  try {
    let content = fs.readFileSync(configFile, 'utf8')
    let changed = false

    if (config.clientId) {
      const candidates = [...new Set([previousConfig.clientId, 'GOOGLE_OAUTH2_CLIENT_ID'].filter(Boolean))]
      for (const candidate of candidates) {
        if (content.includes(candidate) && candidate !== config.clientId) {
          content = content.replaceAll(candidate, config.clientId)
          changed = true
          log('INFO', `Replaced "${candidate}" with new clientId in project.yaml`)
          break
        }
      }
    }

    if (config.clientSecret) {
      const candidates = [...new Set([previousConfig.clientSecret, 'GOOGLE_OAUTH2_CLIENT_SECRET'].filter(Boolean))]
      for (const candidate of candidates) {
        if (content.includes(candidate) && candidate !== config.clientSecret) {
          content = content.replaceAll(candidate, config.clientSecret)
          changed = true
          log('INFO', `Replaced "${candidate}" with new clientSecret in project.yaml`)
          break
        }
      }
    }

    if (changed) {
      fs.writeFileSync(configFile, content, 'utf8')
      log('INFO', 'Google Calendar credentials applied to project.yaml')
    } else {
      log('INFO', 'No changes needed in project.yaml for Google Calendar config')
    }
  } catch (error) {
    log('ERROR', 'Failed to apply Google Calendar config to project.yaml:', error)
  }
}

/**
 * Ensure the user.yaml file for the current OS user contains:
 *   projects:
 *     CodayTwin:
 *       defaultAgent: Twin
 *
 * The function is idempotent: if the value is already set it is not changed.
 * It manipulates the YAML file as text to avoid losing comments / formatting.
 */
function ensureDefaultAgentInUserConfig(): void {
  const os = require('os') as typeof import('os')
  const username = os.userInfo().username
  const sanitizedUsername = username.replace(/[^a-zA-Z0-9]/g, '_')
  const userDir = join(os.homedir(), '.coday', 'users', sanitizedUsername)
  const userConfigPath = join(userDir, 'user.yaml')

  try {
    fs.mkdirSync(userDir, { recursive: true })

    if (!fs.existsSync(userConfigPath)) {
      // Create a minimal user.yaml with the defaultAgent already set
      const content = [
        'version: 1',
        'projects:',
        '  CodayTwin:',
        '    integration: {}',
        '    defaultAgent: Twin',
        '',
      ].join('\n')
      fs.writeFileSync(userConfigPath, content, 'utf8')
      log('INFO', 'Created user.yaml with CodayTwin defaultAgent: Twin')
      return
    }

    const raw = fs.readFileSync(userConfigPath, 'utf8')
    const lines = raw.split('\n')

    // State machine to locate / patch the relevant section
    let inProjects = false
    let inCodayTwin = false
    const agentIndent = '    ' // four-space indent for its children
    let defaultAgentFound = false
    let codayTwinLineIdx = -1
    let insertAfterIdx = -1 // last line index inside the CodayTwin block

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]!

      // Detect top-level "projects:" key
      if (/^projects:\s*$/.test(line)) {
        inProjects = true
        inCodayTwin = false
        continue
      }

      // Leaving the projects section (another top-level key)
      if (inProjects && /^\S/.test(line) && !line.startsWith('#')) {
        inProjects = false
        inCodayTwin = false
      }

      if (inProjects) {
        // Detect "  CodayTwin:" (two-space indented project entry)
        if (/^  CodayTwin:\s*$/.test(line)) {
          inCodayTwin = true
          codayTwinLineIdx = i
          insertAfterIdx = i
          continue
        }

        // Detect another project key at the same indent level → leave CodayTwin block
        if (inCodayTwin && /^  \S/.test(line) && !/^    /.test(line)) {
          inCodayTwin = false
        }

        if (inCodayTwin) {
          insertAfterIdx = i
          // Check if defaultAgent is already set
          if (/^    defaultAgent:\s*Twin\s*$/.test(line)) {
            defaultAgentFound = true
          }
        }
      }
    }

    if (defaultAgentFound) {
      log('INFO', 'user.yaml already has defaultAgent: Twin for CodayTwin — no change needed')
      return
    }

    const result = [...lines]

    if (codayTwinLineIdx === -1) {
      // No CodayTwin entry at all — ensure projects section exists then append
      const projectsIdx = result.findIndex((l) => /^projects:\s*$/.test(l))
      if (projectsIdx === -1) {
        // No projects section either — append both
        if (result[result.length - 1] !== '') result.push('')
        result.push('projects:')
        result.push('  CodayTwin:')
        result.push('    integration: {}')
        result.push('    defaultAgent: Twin')
      } else {
        // Find the end of the projects section and insert there
        let endOfProjects = projectsIdx + 1
        while (endOfProjects < result.length) {
          const l = result[endOfProjects]!
          // A non-empty, non-comment line at column 0 ends the projects block
          if (/^\S/.test(l) && !l.startsWith('#')) break
          endOfProjects++
        }
        result.splice(endOfProjects, 0, '  CodayTwin:', '    integration: {}', '    defaultAgent: Twin')
      }
    } else {
      // CodayTwin block exists but defaultAgent is missing — insert after the last line of the block
      result.splice(insertAfterIdx + 1, 0, `${agentIndent}defaultAgent: Twin`)
    }

    let output = result.join('\n')
    if (!output.endsWith('\n')) output += '\n'
    fs.writeFileSync(userConfigPath, output, 'utf8')
    log('INFO', 'Wrote defaultAgent: Twin for CodayTwin into user.yaml')
  } catch (error) {
    log('ERROR', 'Failed to set defaultAgent in user.yaml:', error)
  }
}

/**
 * Ensure the Coday project config exists for the Twin workspace.
 * Creates ~/.coday/projects/CodayTwin/project.yaml if it doesn't exist.
 * Prefers the bundled project.template.yaml; falls back to a minimal config.
 * Never overwrites an existing project.yaml (only applies credential substitutions).
 */
function ensureTwinProjectConfig(twinProjectPath: string): void {
  const os = require('os') as typeof import('os')
  const configDir = join(os.homedir(), '.coday', 'projects', 'CodayTwin')
  const configFile = join(configDir, 'project.yaml')

  // Ensure directory exists
  fs.mkdirSync(configDir, { recursive: true })

  if (!fs.existsSync(configFile)) {
    log('INFO', 'Creating Twin project config at:', configFile)

    // Try to find bundled project.template.yaml
    const templatePaths = [
      join(process.resourcesPath ?? '', 'project.template.yaml'),
      join(__dirname, '..', 'macos', 'project.template.yaml'),
      join(__dirname, '..', '..', 'macos', 'project.template.yaml'),
    ]

    let templateContent: string | null = null
    for (const tp of templatePaths) {
      if (fs.existsSync(tp)) {
        templateContent = fs.readFileSync(tp, 'utf8')
        log('INFO', 'Using project template from:', tp)
        break
      }
    }

    if (templateContent !== null) {
      // Substitute path placeholder
      templateContent = templateContent.replace(/^path:.*$/m, `path: "${twinProjectPath}"`)
      log('INFO', 'Substituted TWIN_PROJECT_PATH with:', twinProjectPath)

      // Substitute Google credentials if already stored
      const googleConfig = getStoredGoogleCalendarConfig()
      if (googleConfig.clientId) {
        templateContent = templateContent.replaceAll('GOOGLE_OAUTH2_CLIENT_ID', googleConfig.clientId)
        log('INFO', 'Substituted GOOGLE_OAUTH2_CLIENT_ID in new project.yaml')
      }
      if (googleConfig.clientSecret) {
        templateContent = templateContent.replaceAll('GOOGLE_OAUTH2_CLIENT_SECRET', googleConfig.clientSecret)
        log('INFO', 'Substituted GOOGLE_OAUTH2_CLIENT_SECRET in new project.yaml')
      }

      fs.writeFileSync(configFile, templateContent, 'utf8')
      log('INFO', 'Twin project config created from template')
    } else {
      log('INFO', 'No project template found, writing minimal config')
      // Fallback: write minimal project config
      const config = ['version: 1', `path: "${twinProjectPath}"`, 'storage:', '  type: file', 'agents: []', ''].join(
        '\n'
      )
      fs.writeFileSync(configFile, config, 'utf8')
      log('INFO', 'Twin project config created successfully')
    }
  } else {
    log('INFO', 'Twin project config already exists at:', configFile)

    // Always update the path line to reflect the currently configured twin project path.
    try {
      const existingContent = fs.readFileSync(configFile, 'utf8')
      const updatedContent = existingContent.replace(/^path:.*$/m, `path: "${twinProjectPath}"`)
      if (updatedContent !== existingContent) {
        fs.writeFileSync(configFile, updatedContent, 'utf8')
        log('INFO', 'Updated path in existing project.yaml to:', twinProjectPath)
      } else {
        log('INFO', 'Path in project.yaml already correct, no update needed')
      }
    } catch (error) {
      log('ERROR', 'Failed to update path in existing project.yaml:', error)
    }

    // Apply Google credentials to existing file if they are stored
    const googleConfig = getStoredGoogleCalendarConfig()
    if (googleConfig.clientId || googleConfig.clientSecret) {
      // Pass the stored config as both current and previous so the function
      // can detect whether the placeholder or an old value needs replacing.
      // previousConfig is left empty: the function will fall back to the
      // placeholder string (GOOGLE_OAUTH2_CLIENT_ID) as the candidate.
      applyGoogleCalendarConfigToProject(googleConfig, { clientId: '', clientSecret: '' })
    }
  }

  // Ensure defaultAgent: Twin is set in user.yaml for the CodayTwin project
  ensureDefaultAgentInUserConfig()

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

// ---------------------------------------------------------------------------
// Setup window
// ---------------------------------------------------------------------------

/**
 * Show the first-launch setup window and wait for the user to choose a path.
 * Returns the chosen twin project path.
 */
function showSetupWindow(): Promise<string> {
  return new Promise<string>((resolve) => {
    const setupWindow = new BrowserWindow({
      width: 580,
      height: 520,
      minWidth: 580,
      minHeight: 400,
      frame: false,
      resizable: true,
      useContentSize: true,
      transparent: true,
      center: true,
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

    // IPC handler: open native folder picker
    // Remove before registering to stay idempotent (safe against double-calls)
    ipcMain.removeHandler('setup:browseFolder')
    ipcMain.handle('setup:browseFolder', async () => {
      // Ensure the setup window has focus so the native dialog appears on top (macOS)
      if (!setupWindow.isDestroyed()) {
        setupWindow.focus()
      }
      const result = await dialog.showOpenDialog(setupWindow, {
        title: 'Choose CodayTwin folder location',
        defaultPath: app.getPath('home'),
        properties: ['openDirectory', 'createDirectory'],
      })
      if (result.canceled || result.filePaths.length === 0) return null
      return result.filePaths[0]!!
    })

    // IPC handler: user confirmed setup
    ipcMain.handle(
      'setup:confirm',
      async (
        _event,
        customPath: string | null,
        slackConfig: SlackConfig | null,
        googleConfig: GoogleCalendarConfig | null
      ) => {
        const chosenPath = customPath ?? DEFAULT_TWIN_PROJECT_PATH
        log('INFO', 'Setup confirmed with path:', chosenPath)

        storeTwinPath(chosenPath)
        if (slackConfig !== null && slackConfig !== undefined) storeSlackConfig(slackConfig)
        if (googleConfig && (googleConfig.clientId || googleConfig.clientSecret)) {
          storeGoogleCalendarConfig(googleConfig)
          log('INFO', 'Google Calendar config stored from setup')
        }

        removeResizeHandler(ipcMain)
        removeApiKeyHandlers(ipcMain)
        ipcMain.removeHandler('setup:browseFolder')
        ipcMain.removeHandler('setup:confirm')

        setupWindow.close()
        resolve(chosenPath)
      }
    )

    setupWindowRef = setupWindow

    // If user closes the window without confirming, use default
    setupWindow.on('closed', () => {
      setupWindowRef = null
      removeResizeHandler(ipcMain)
      removeApiKeyHandlers(ipcMain)
      ipcMain.removeHandler('setup:browseFolder')
      ipcMain.removeHandler('setup:confirm')
      ipcMain.removeHandler('setup:getSlackConfig')
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

// ---------------------------------------------------------------------------
// Preferences window
// ---------------------------------------------------------------------------

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
    width: 580,
    height: 600,
    minWidth: 580,
    minHeight: 300,
    frame: false,
    resizable: true,
    useContentSize: true,
    transparent: true,
    center: true,
    icon: join(__dirname, 'icon.png'),
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: false,
    },
  })

  registerResizeHandler(ipcMain, BrowserWindow)

  ipcMain.handle('preferences:getCurrentPath', () => {
    return getResolvedTwinPath()
  })

  ipcMain.handle('preferences:browseFolder', async () => {
    // Ensure the preferences window has focus so the native dialog appears on top (macOS)
    if (!preferencesWindow.isDestroyed()) {
      preferencesWindow.focus()
    }
    const result = await dialog.showOpenDialog(preferencesWindow, {
      title: 'Choose new CodayTwin folder location',
      defaultPath: app.getPath('home'),
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled || result.filePaths.length === 0) return null
    return result.filePaths[0]!!
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
    const previousSlackConfig = getStoredSlackConfig()
    storeSlackConfig(config)
    if (config.webhookUrl || config.userId) {
      applySlackConfigToVault(config, previousSlackConfig)
    }
    log('INFO', 'Slack config saved')
  })

  ipcMain.handle('preferences:getGoogleCalendarConfig', (): GoogleCalendarConfig => {
    return getStoredGoogleCalendarConfig()
  })

  ipcMain.handle(
    'preferences:saveGoogleCalendarConfig',
    async (_event: IpcMainInvokeEvent, config: GoogleCalendarConfig) => {
      const previousGoogleConfig = getStoredGoogleCalendarConfig()
      storeGoogleCalendarConfig(config)
      applyGoogleCalendarConfigToProject(config, previousGoogleConfig)
      log('INFO', 'Google Calendar config saved')
    }
  )

  preferencesWindow.on('closed', () => {
    ipcMain.removeHandler('preferences:getCurrentPath')
    ipcMain.removeHandler('preferences:browseFolder')
    ipcMain.removeHandler('preferences:save')
    ipcMain.removeHandler('preferences:close')
    ipcMain.removeHandler('preferences:getSlackConfig')
    ipcMain.removeHandler('preferences:saveSlackConfig')
    ipcMain.removeHandler('preferences:getGoogleCalendarConfig')
    ipcMain.removeHandler('preferences:saveGoogleCalendarConfig')
    removeResizeHandler(ipcMain)
    isPreferencesWindowOpen = false
    log('INFO', 'Preferences window closed')
  })

  void preferencesWindow.loadFile(join(__dirname, 'preferences.html'))
}

// ---------------------------------------------------------------------------
// Application menu
// ---------------------------------------------------------------------------

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
// Favicon scheme / interceptor (twin-specific)
// ---------------------------------------------------------------------------

/**
 * Register a custom 'coday-asset' protocol and an HTTP interceptor so that
 * favicon / logo requests from the Angular client are served from the local
 * bundled icon.png.
 *
 * registerFaviconScheme() must be called BEFORE app.whenReady().
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

  // Intercept HTTP requests for known favicon / logo paths
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
  const desktopTwinCss = fs.readFileSync(join(__dirname, 'desktop-twin.css'), 'utf8')

  mainWindow = createMainWindow(
    {
      BrowserWindow,
      ipcMain,
      serverUrl,
      iconPath,
      preloadPath,
      title: 'CodayTwin',
      onWindowCreated: (win) => {
        // Flag the renderer as running inside desktop-twin by adding a CSS class
        // on <body>. Angular SCSS can then use `body.desktop-twin` selectors for
        // app-specific overrides without touching the shared client source.
        win.webContents.on('did-finish-load', () => {
          void win.webContents.executeJavaScript(`document.body.classList.add('desktop-twin');`)
          void win.webContents.insertCSS(desktopTwinCss)
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
    log('INFO', 'Initializing CodayTwin Desktop...')

    // Setup storage handlers (once)
    if (!storageHandlersRegistered) {
      storageHandlersRegistered = true
      setupStorageHandlers(ipcMain, STORAGE_FILE, LOG_FILE)
    }

    // First-launch setup: let user choose the vault path
    if (needsSetup()) {
      log('INFO', 'First launch detected, showing setup window')
      await resetAndShowSetup()
      log('INFO', 'Setup complete, twin path:', getResolvedTwinPath())
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

    const twinProjectPath = getResolvedTwinPath()

    // Ensure vault directory and Coday project config exist
    initializeVault(twinProjectPath)
    ensureTwinProjectConfig(twinProjectPath)

    // Re-apply Slack config to coday.yaml on every startup.
    // initializeVault only substitutes placeholders when coday.yaml is first created
    // from the template, so if the vault already existed when Slack was configured
    // (e.g. after re-setup or a partial first-launch), the values would otherwise
    // remain as placeholders. This mirrors the Google Calendar re-application
    // pattern in ensureTwinProjectConfig.
    const slackConfig = getStoredSlackConfig()
    if (slackConfig.enabled && (slackConfig.webhookUrl || slackConfig.userId)) {
      applySlackConfigToVault(slackConfig, { enabled: false, webhookUrl: '', userId: '' })
    }

    // Start the CodayTwin server
    const result = await startCodayServer({
      appName: 'CodayTwin',
      port: CODAY_TWIN_PORT,
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
      showErrorDialog(dialog, 'CodayTwin - Startup Error', errorMessage)
    } else {
      showErrorDialog(
        dialog,
        'CodayTwin - Unexpected Error',
        `An unexpected error occurred while starting CodayTwin:\n\n${errorMessage}\n\nPlease check the console logs for more details.`
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

void app.whenReady().then(() => {
  // Register favicon interceptor before any window is created
  registerFaviconInterceptor()
  void initialize()
})

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
    'CodayTwin - Fatal Error',
    `A fatal error occurred:\n\n${error.message}\n\nThe application will now close.`
  )
  stopCodayServer(serverProcess)
  app.quit()
})
