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
  project: string
  createdBy: string
  createdAt: Date
  commandType: 'free' | 'template'
  commands?: string[]
}

export class WebhookService {
  private webhooksDir: string
  private threadCodayManager?: ThreadCodayManager
  private threadService?: ThreadService
  private codayOptions?: CodayOptions
  private logger?: CodayLogger

  constructor(codayConfigPath?: string) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.webhooksDir = path.join(codayConfigPath ?? defaultConfigPath, 'webhooks')

    // Ensure the webhooks directory exists
    mkdirSync(this.webhooksDir, { recursive: true })
  }

  /**
   * Creates a new webhook with generated UUID and timestamp
   */
  async create(webhook: Omit<Webhook, 'uuid' | 'createdAt'>): Promise<Webhook> {
    try {
      // Generate proper UUID v4
      const uuid = randomUUID()

      const newWebhook: Webhook = {
        ...webhook,
        uuid,
        createdAt: new Date(),
      }

      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)

      // Check if file already exists (highly unlikely but defensive)
      if (existsSync(filePath)) {
        throw new Error(`Webhook with UUID ${uuid} already exists`)
      }

      writeYamlFile(filePath, newWebhook)
      return newWebhook
    } catch (error) {
      throw new Error(`Failed to create webhook: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  /**
   * Retrieves a webhook by UUID
   */
  async get(uuid: string): Promise<Webhook | null> {
    try {
      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)
      const webhook = readYamlFile<Webhook>(filePath)

      if (!webhook) {
        return null
      }

      // Ensure createdAt is a Date object
      if (webhook.createdAt && typeof webhook.createdAt === 'string') {
        webhook.createdAt = new Date(webhook.createdAt)
      }

      return webhook
    } catch (error) {
      console.error(`Failed to get webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Updates an existing webhook
   */
  async update(uuid: string, updates: Partial<Webhook>): Promise<Webhook | null> {
    try {
      const existing = await this.get(uuid)
      if (!existing) {
        return null
      }

      // Prevent changing UUID and createdAt
      const { uuid: _, createdAt: __, ...allowedUpdates } = updates

      const updatedWebhook: Webhook = {
        ...existing,
        ...allowedUpdates,
      }

      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)
      writeYamlFile(filePath, updatedWebhook)

      return updatedWebhook
    } catch (error) {
      console.error(`Failed to update webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Deletes a webhook by UUID
   */
  async delete(uuid: string): Promise<boolean> {
    try {
      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)

      if (!existsSync(filePath)) {
        return false
      }

      unlinkSync(filePath)
      return true
    } catch (error) {
      console.error(`Failed to delete webhook ${uuid}:`, error)
      return false
    }
  }

  /**
   * Lists all webhooks
   */
  async list(): Promise<Webhook[]> {
    try {
      if (!existsSync(this.webhooksDir)) {
        return []
      }

      const files = readdirSync(this.webhooksDir)
      const webhookFiles = files.filter((file) => file.endsWith('.yml'))

      const webhooks: Webhook[] = []

      for (const file of webhookFiles) {
        const uuid = file.replace('.yml', '')
        const webhook = await this.get(uuid)
        if (webhook) {
          webhooks.push(webhook)
        }
      }

      // Sort by creation date (newest first)
      return webhooks.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
    } catch (error) {
      console.error('Failed to list webhooks:', error)
      return []
    }
  }

  /**
   * Returns the webhooks directory path
   */
  getWebhooksDir(): string {
    return this.webhooksDir
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

    // Load webhook configuration
    const webhook = await this.get(webhookUuid)
    if (!webhook) {
      throw new Error(`Webhook not found: ${webhookUuid}`)
    }

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

    const project = webhook.project

    if (!project) {
      throw new Error('Webhook project not configured')
    }

    if (!username) {
      throw new Error('Username is required')
    }

    // Create a new thread for the webhook
    const thread = await this.threadService.createThread(project, username, title)
    const threadId = thread.id

    // Configure one-shot Coday instance with automatic prompts
    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true, // Creates isolated instance that terminates after processing
      project, // Target project for the AI agent interaction
      thread: threadId, // Use the newly created thread
      prompts: prompts, // User prompts
    }

    console.log(`[WEBHOOK] Creating webhook instance with ${prompts.length} prompts:`, prompts)

    // Create a thread-based Coday instance for this webhook (without SSE connection)
    const instance = this.threadCodayManager.createWithoutConnection(threadId, project, username, oneShotOptions)

    // IMPORTANT: Prepare Coday without starting run() to subscribe to events first
    // Because interactor.events is a Subject (not ReplaySubject), we must subscribe BEFORE run() emits events
    instance.prepareCoday()
    const interactor = instance.coday!.interactor

    // Log successful webhook initiation
    const logData = {
      project,
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
          project,
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
