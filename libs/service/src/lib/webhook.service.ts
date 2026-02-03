import * as path from 'node:path'
import * as os from 'node:os'
import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { readYamlFile, writeYamlFile } from '@coday/utils'
import type { CodayOptions, CodayLogger } from '@coday/model'
import { CodayEvent, MessageEvent } from '@coday/model'
import type { ThreadService } from './thread.service'

// ThreadCodayManager is in apps/server, so we use a type-only import to avoid circular dependencies
// The actual instance will be injected via initializeExecution()
type ThreadCodayManager = any
import { filter } from 'rxjs'

export interface Webhook {
  uuid: string
  name: string
  createdBy: string
  createdAt: Date
  commandType: 'free' | 'template'
  commands?: string[]
}

/**
 * WebhookService - Manages webhook CRUD operations and execution
 *
 * NEW ARCHITECTURE:
 * - Webhooks are stored per project: ~/.coday/projects/{projectName}/webhooks/{uuid}.yml
 * - Each webhook is owned by a user (createdBy field)
 * - Access control: owner OR CODAY_ADMIN group members can access
 * - Execution endpoint remains project-agnostic (/api/webhooks/:uuid/execute)
 */
export class WebhookService {
  private threadCodayManager?: ThreadCodayManager
  private threadService?: ThreadService
  private codayOptions?: CodayOptions
  private logger?: CodayLogger
  private readonly codayConfigDir: string

  constructor(codayConfigPath?: string) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.codayConfigDir = codayConfigPath ?? defaultConfigPath
  }

  /**
   * Get webhooks directory path for a specific project
   */
  private getWebhooksDir(projectName: string): string {
    return path.join(this.codayConfigDir, 'projects', projectName, 'webhooks')
  }

  /**
   * Get webhook file path
   */
  private getWebhookFilePath(projectName: string, uuid: string): string {
    return path.join(this.getWebhooksDir(projectName), `${uuid}.yml`)
  }

  /**
   * Find which project contains a webhook by UUID
   * Used by executeWebhook to locate webhook without knowing project
   *
   * @param uuid - Webhook UUID
   * @returns Project name if found, null otherwise
   */
  private findProjectForWebhook(uuid: string): string | null {
    const projectsPath = path.join(this.codayConfigDir, 'projects')

    if (!existsSync(projectsPath)) {
      return null
    }

    const projectDirs = readdirSync(projectsPath, { withFileTypes: true })
      .filter((dirent) => dirent.isDirectory())
      .map((dirent) => dirent.name)

    for (const projectName of projectDirs) {
      const webhookPath = this.getWebhookFilePath(projectName, uuid)
      if (existsSync(webhookPath)) {
        return projectName
      }
    }

    return null
  }

  /**
   * Check if a user can access a webhook (owner or CODAY_ADMIN)
   *
   * @param webhook - Webhook to check access for
   * @param username - Username requesting access
   * @returns true if user can access, false otherwise
   */
  private canAccessWebhook(webhook: Webhook, username: string): boolean {
    // Owner can always access
    if (webhook.createdBy === username) {
      return true
    }

    // Check if user is in CODAY_ADMIN group
    try {
      const userConfigPath = path.join(this.codayConfigDir, 'users', username, 'user.yml')
      const userConfig = readYamlFile<{ temp_groups?: string[] }>(userConfigPath)

      if (!userConfig) {
        return false
      }

      return userConfig.temp_groups?.includes('CODAY_ADMIN') ?? false
    } catch (error) {
      // If config doesn't exist or can't be read, user is not admin
      return false
    }
  }

  /**
   * Creates a new webhook with generated UUID and timestamp
   *
   * @param projectName - Project name where webhook will be created
   * @param webhook - Webhook data (without uuid and createdAt)
   * @returns Created webhook
   */
  async create(projectName: string, webhook: Omit<Webhook, 'uuid' | 'createdAt'>): Promise<Webhook> {
    try {
      // Generate proper UUID v4
      const uuid = randomUUID()

      const newWebhook: Webhook = {
        ...webhook,
        uuid,
        createdAt: new Date(),
      }

      const webhooksDir = this.getWebhooksDir(projectName)
      mkdirSync(webhooksDir, { recursive: true })

      const filePath = this.getWebhookFilePath(projectName, uuid)

      // Check if file already exists (highly unlikely but defensive)
      if (existsSync(filePath)) {
        throw new Error(`Webhook with UUID ${uuid} already exists`)
      }

      writeYamlFile(filePath, newWebhook)
      console.log(`[WEBHOOK] Created webhook ${uuid} in project ${projectName}`)
      return newWebhook
    } catch (error) {
      throw new Error(`Failed to create webhook: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  /**
   * Retrieves a webhook by UUID and project, with access control
   *
   * @param projectName - Project name
   * @param uuid - Webhook UUID
   * @param username - Username requesting access (optional, skips access check if not provided)
   * @returns Webhook if found and accessible, null otherwise
   */
  async get(projectName: string, uuid: string, username?: string): Promise<Webhook | null> {
    try {
      const filePath = this.getWebhookFilePath(projectName, uuid)
      const webhook = readYamlFile<Webhook>(filePath)

      if (!webhook) {
        return null
      }

      // Ensure createdAt is a Date object
      if (webhook.createdAt && typeof webhook.createdAt === 'string') {
        webhook.createdAt = new Date(webhook.createdAt)
      }

      // Access control check if username provided
      if (username && !this.canAccessWebhook(webhook, username)) {
        console.log(`[WEBHOOK] Access denied for user ${username} to webhook ${uuid}`)
        return null
      }

      return webhook
    } catch (error) {
      console.error(`Failed to get webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Get webhook by UUID without knowing project (for execution)
   * No access control - used internally by executeWebhook
   *
   * @param uuid - Webhook UUID
   * @returns Object with webhook and projectName if found, null otherwise
   */
  async getByUuid(uuid: string): Promise<{ webhook: Webhook; projectName: string } | null> {
    try {
      const projectName = this.findProjectForWebhook(uuid)
      if (!projectName) {
        return null
      }

      const webhook = await this.get(projectName, uuid)
      if (!webhook) {
        return null
      }

      return { webhook, projectName }
    } catch (error) {
      console.error(`Failed to get webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Updates an existing webhook with access control
   *
   * @param projectName - Project name
   * @param uuid - Webhook UUID
   * @param updates - Fields to update
   * @param username - Username requesting the update
   * @returns Updated webhook if successful, null if not found or access denied
   */
  async update(
    projectName: string,
    uuid: string,
    updates: Partial<Webhook>,
    username: string
  ): Promise<Webhook | null> {
    try {
      const existing = await this.get(projectName, uuid, username)
      if (!existing) {
        return null // Not found or access denied
      }

      // Prevent changing UUID, createdAt, and createdBy
      const { uuid: _, createdAt: __, createdBy: ___, ...allowedUpdates } = updates

      const updatedWebhook: Webhook = {
        ...existing,
        ...allowedUpdates,
      }

      const filePath = this.getWebhookFilePath(projectName, uuid)
      writeYamlFile(filePath, updatedWebhook)

      console.log(`[WEBHOOK] Updated webhook ${uuid} by user ${username}`)
      return updatedWebhook
    } catch (error) {
      console.error(`Failed to update webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Deletes a webhook by UUID with access control
   *
   * @param projectName - Project name
   * @param uuid - Webhook UUID
   * @param username - Username requesting deletion
   * @returns true if deleted, false if not found or access denied
   */
  async delete(projectName: string, uuid: string, username: string): Promise<boolean> {
    try {
      const existing = await this.get(projectName, uuid, username)
      if (!existing) {
        return false // Not found or access denied
      }

      const filePath = this.getWebhookFilePath(projectName, uuid)
      unlinkSync(filePath)

      console.log(`[WEBHOOK] Deleted webhook ${uuid} by user ${username}`)
      return true
    } catch (error) {
      console.error(`Failed to delete webhook ${uuid}:`, error)
      return false
    }
  }

  /**
   * Lists all webhooks for a project with access control
   *
   * @param projectName - Project name
   * @param username - Username requesting the list
   * @returns Array of webhooks the user can access
   */
  async list(projectName: string, username: string): Promise<Webhook[]> {
    try {
      const webhooksDir = this.getWebhooksDir(projectName)

      if (!existsSync(webhooksDir)) {
        return []
      }

      const files = readdirSync(webhooksDir)
      const webhookFiles = files.filter((file) => file.endsWith('.yml'))

      const webhooks: Webhook[] = []

      for (const file of webhookFiles) {
        const uuid = file.replace('.yml', '')
        const webhook = await this.get(projectName, uuid, username)
        if (webhook) {
          webhooks.push(webhook)
        }
      }

      // Sort by creation date (newest first)
      return webhooks.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
    } catch (error) {
      console.error(`Failed to list webhooks for project ${projectName}:`, error)
      return []
    }
  }

  /**
   * Initialize webhook execution dependencies
   * Called after server initialization with required services
   */
  initializeExecution(
    threadCodayManager: ThreadCodayManager,
    threadService: ThreadService,
    codayOptions: CodayOptions,
    logger: CodayLogger
  ): void {
    this.threadCodayManager = threadCodayManager
    this.threadService = threadService
    this.codayOptions = codayOptions
    this.logger = logger
  }

  /**
   * Execute a webhook
   *
   * This method is called by the /api/webhooks/:uuid/execute endpoint
   * It finds the webhook by UUID across all projects and executes it
   *
   * @param webhookUuid - UUID of the webhook to execute
   * @param parameters - Parameters to override webhook defaults (for template type)
   * @param username - Username executing the webhook
   * @param title - Optional title for the thread
   * @param awaitFinalAnswer - Whether to wait for completion (default: false)
   * @returns Promise<{ threadId: string, lastEvent?: MessageEvent }>
   */
  async executeWebhook(
    webhookUuid: string,
    parameters: Record<string, unknown>,
    username: string,
    title?: string,
    awaitFinalAnswer: boolean = false
  ): Promise<{ threadId: string; lastEvent?: MessageEvent }> {
    // Verify execution is initialized
    if (!this.threadCodayManager || !this.threadService || !this.codayOptions || !this.logger) {
      throw new Error('Webhook execution not initialized. Call initializeExecution() first.')
    }

    // Load webhook configuration (finds project automatically)
    const result = await this.getByUuid(webhookUuid)
    if (!result) {
      throw new Error(`Webhook not found: ${webhookUuid}`)
    }

    const { webhook, projectName } = result

    // Determine prompts based on webhook command type
    let prompts: string[]
    if (webhook.commandType === 'free') {
      // For 'free' type, use prompts from parameters
      const bodyPrompts = parameters.prompts as string[] | undefined
      if (!bodyPrompts || !Array.isArray(bodyPrompts) || bodyPrompts.length === 0) {
        throw new Error('Missing or invalid prompts array for free command type')
      }
      prompts = bodyPrompts
    } else if (webhook.commandType === 'template') {
      // For 'template' type, use webhook commands with placeholder replacement
      if (!webhook.commands || webhook.commands.length === 0) {
        throw new Error('Webhook has no template commands configured')
      }

      // Replace placeholders in template commands
      prompts = webhook.commands.map((command) => {
        let processedCommand = command
        // Simple string replacement for placeholders like {{key}}
        Object.entries(parameters).forEach(([key, value]) => {
          const placeholder = `{{${key}}}`
          processedCommand = processedCommand.replace(new RegExp(placeholder, 'g'), String(value))
        })
        return processedCommand
      })
    } else {
      throw new Error(`Unknown webhook command type: ${webhook.commandType}`)
    }

    if (!username) {
      throw new Error('Username is required')
    }

    // Create a new thread for the webhook
    const thread = await this.threadService.createThread(projectName, username, title)
    const threadId = thread.id

    // Configure one-shot Coday instance with automatic prompts
    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true, // Creates isolated instance that terminates after processing
      project: projectName, // Target project for the AI agent interaction
      thread: threadId, // Use the newly created thread
      prompts: prompts, // User prompts
    }

    console.log(`[WEBHOOK] Creating webhook instance with ${prompts.length} prompts:`, prompts)

    // Create a thread-based Coday instance for this webhook (without SSE connection)
    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, oneShotOptions)

    // IMPORTANT: Prepare Coday without starting run() to subscribe to events first
    // Because interactor.events is a Subject (not ReplaySubject), we must subscribe BEFORE run() emits events
    instance.prepareCoday()
    const interactor = instance.coday!.interactor

    // Log successful webhook initiation
    const logData = {
      project: projectName,
      title: title ?? 'Untitled',
      username,
      clientId: threadId, // Use threadId as clientId for log correlation
      promptCount: prompts.length,
      awaitFinalAnswer: !!awaitFinalAnswer,
      webhookName: webhook.name,
      webhookUuid: webhook.uuid,
    }
    this.logger.logWebhook(logData)

    if (awaitFinalAnswer) {
      // Synchronous mode: wait for completion
      // Collect all assistant messages during the run
      const assistantMessages: MessageEvent[] = []
      const subscription = interactor.events
        .pipe(
          filter((event: CodayEvent) => {
            console.log(
              `[WEBHOOK] Received event type: ${event.type}, role: ${event instanceof MessageEvent ? event.role : 'N/A'}`
            )
            return event instanceof MessageEvent && event.role === 'assistant' && !!event.name
          })
        )
        .subscribe((event: MessageEvent) => {
          assistantMessages.push(event)
        })

      try {
        // Now start Coday run and wait for it to complete
        await instance.coday!.run()
        subscription.unsubscribe()

        const lastEvent = assistantMessages[assistantMessages.length - 1]

        // Cleanup the thread instance after completion
        await this.threadCodayManager.cleanup(threadId)

        return { threadId, lastEvent }
      } catch (error) {
        subscription.unsubscribe()

        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        this.logger.logWebhookError({
          error: `Webhook completion failed: ${errorMessage}`,
          username,
          project: projectName,
          clientId: threadId,
        })
        console.error('[WEBHOOK] Error waiting for webhook completion:', error)

        // Cleanup on error
        await this.threadCodayManager.cleanup(threadId)

        throw error
      }
    } else {
      // Asynchronous mode: return immediately with thread ID
      // Start Coday run in background
      instance.coday!.run().catch((error: unknown) => {
        console.error('[WEBHOOK] Error during webhook Coday run:', error)
      })

      // Schedule cleanup after a reasonable timeout (e.g., 5 minutes)
      setTimeout(
        () => {
          this.threadCodayManager!.cleanup(threadId).catch((error: unknown) => {
            console.error('[WEBHOOK] Error cleaning up webhook thread after timeout:', error)
          })
        },
        5 * 60 * 1000
      ) // 5 minutes

      return { threadId }
    }
  }
}
