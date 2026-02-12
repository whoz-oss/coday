import type { CodayOptions, CodayLogger } from '@coday/model'
import { CodayEvent, MessageEvent } from '@coday/model'
import type { ThreadService } from './thread.service'
import { PromptService } from './prompt.service'
import { validateInterval } from '@coday/utils'
import { filter } from 'rxjs'

// ThreadCodayManager is in apps/server, so we use a type-only import to avoid circular dependencies
// The actual instance will be injected via initialize()
type ThreadCodayManager = any

/**
 * PromptExecutionService - Unified prompt execution logic
 *
 * This service handles prompt execution in all contexts:
 * - Direct execution (user triggers in UI)
 * - Scheduled execution (via SchedulerService)
 * - Webhook execution (via HTTP API)
 *
 * It creates thread-based Coday instances and manages their lifecycle.
 */
export class PromptExecutionService {
  private threadCodayManager?: ThreadCodayManager
  private threadService?: ThreadService
  private codayOptions?: CodayOptions
  private logger?: CodayLogger

  constructor(private readonly promptService: PromptService) {}

  /**
   * Initialize execution dependencies
   * Called after server initialization with required services
   */
  initialize(
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
   * Resolve which thread to use based on threadLifetime configuration
   *
   * Logic:
   * - No threadLifetime: Create new thread (default behavior)
   * - With threadLifetime: Reuse activeThreadId if within lifetime, otherwise create new and update activeThreadId
   */
  private async resolveThreadId(prompt: any, projectName: string, username: string, title?: string): Promise<string> {
    // No threadLifetime: always create new thread (default behavior)
    if (!prompt.threadLifetime) {
      const thread = await this.threadService!.createThread(projectName, username, title)
      console.log(`[PROMPT_EXEC] Created new thread: ${thread.id} (no threadLifetime)`)
      return thread.id
    }

    // Validate threadLifetime format
    if (!validateInterval(prompt.threadLifetime)) {
      throw new Error(
        `Invalid threadLifetime format: ${prompt.threadLifetime}. Use format like '2min', '5h', '14d', '1M'`
      )
    }

    // Check if we have an active thread
    if (!prompt.activeThreadId) {
      // No active thread: create new one and update prompt
      const thread = await this.threadService!.createThread(projectName, username, title)
      await this.updatePromptActiveThread(prompt.id, projectName, thread.id)
      console.log(`[PROMPT_EXEC] Created new thread: ${thread.id} (no activeThreadId)`)
      return thread.id
    }

    // Check if active thread still exists
    const activeThread = await this.threadService!.getThread(projectName, prompt.activeThreadId)
    if (!activeThread) {
      // Active thread deleted: create new one and update prompt
      const thread = await this.threadService!.createThread(projectName, username, title)
      await this.updatePromptActiveThread(prompt.id, projectName, thread.id)
      console.log(`[PROMPT_EXEC] Created new thread: ${thread.id} (activeThread not found)`)
      return thread.id
    }

    // Check if active thread is still within lifetime
    const lifetimeMs = this.parseLifetimeToMs(prompt.threadLifetime)
    const threadAge = Date.now() - new Date(activeThread.createdDate).getTime()

    if (threadAge > lifetimeMs) {
      // Thread expired: create new one and update prompt
      const thread = await this.threadService!.createThread(projectName, username, title)
      await this.updatePromptActiveThread(prompt.id, projectName, thread.id)
      console.log(`[PROMPT_EXEC] Created new thread: ${thread.id} (expired: ${threadAge}ms > ${lifetimeMs}ms)`)
      return thread.id
    }

    // Thread is still valid: reuse it
    console.log(`[PROMPT_EXEC] Reusing active thread: ${prompt.activeThreadId} (age: ${threadAge}ms / ${lifetimeMs}ms)`)
    return prompt.activeThreadId
  }

  /**
   * Parse threadLifetime string to milliseconds
   * Format: '2min', '5h', '14d', '1M'
   */
  private parseLifetimeToMs(lifetime: string): number {
    const match = lifetime.match(/^(\d+)(min|h|d|M)$/)
    if (!match || !match[1] || !match[2]) {
      throw new Error(`Invalid threadLifetime format: ${lifetime}`)
    }

    const value = parseInt(match[1], 10)
    const unit = match[2]

    switch (unit) {
      case 'min':
        return value * 60 * 1000
      case 'h':
        return value * 60 * 60 * 1000
      case 'd':
        return value * 24 * 60 * 60 * 1000
      case 'M':
        // Approximate: 30 days per month
        return value * 30 * 24 * 60 * 60 * 1000
      default:
        throw new Error(`Unknown unit: ${unit}`)
    }
  }

  /**
   * Update the activeThreadId in the prompt
   */
  private async updatePromptActiveThread(promptId: string, projectName: string, threadId: string): Promise<void> {
    try {
      await this.promptService.update(projectName, promptId, { activeThreadId: threadId }, 'system')
      console.log(`[PROMPT_EXEC] Updated prompt ${promptId} activeThreadId to ${threadId}`)
    } catch (error) {
      console.error(`[PROMPT_EXEC] Failed to update prompt activeThreadId:`, error)
      // Non-blocking: execution continues even if update fails
    }
  }

  /**
   * Process commands with parameter interpolation
   *
   * Rules:
   * 1. String parameter:
   *    - If {{PARAMETERS}} present → replace in ALL commands
   *    - If no placeholders → append to FIRST command only
   *    - If other {{key}} placeholders → Error
   * 2. Object parameter:
   *    - Replace all {{key}} with values from object
   * 3. Undefined:
   *    - Commands used as-is
   * 4. Final validation:
   *    - If any {{...}} remain → Throw with list of missing keys
   */
  private processCommands(commands: string[], parameters: Record<string, unknown> | string | undefined): string[] {
    let processed: string[]

    if (typeof parameters === 'string') {
      // String parameter mode
      const hasParametersPlaceholder = commands.some((cmd) => /\{\{PARAMETERS\}\}/.test(cmd))
      const hasOtherPlaceholders = commands.some((cmd) => /\{\{(?!PARAMETERS\}\})\w+\}\}/.test(cmd))

      if (hasOtherPlaceholders) {
        throw new Error(
          'Prompt contains structured placeholders ({{key}}). Use an object parameter instead of a string.'
        )
      }

      if (hasParametersPlaceholder) {
        // Replace {{PARAMETERS}} in ALL commands
        processed = commands.map((cmd) => cmd.replace(/\{\{PARAMETERS\}\}/g, parameters))
      } else {
        // No placeholders → Append to first command only
        processed = commands.map((cmd, index) => (index === 0 ? `${cmd} ${parameters}`.trim() : cmd))
      }
    } else if (typeof parameters === 'object' && parameters !== null) {
      // Object parameter mode - structured interpolation
      processed = commands.map((command) => {
        let processedCommand = command
        Object.entries(parameters).forEach(([key, value]) => {
          const placeholder = `{{${key}}}`
          processedCommand = processedCommand.replace(new RegExp(placeholder, 'g'), String(value))
        })
        return processedCommand
      })
    } else {
      // No parameters - use commands as-is
      processed = [...commands]
    }

    // Final validation: check for remaining placeholders
    const remainingPlaceholders = new Set<string>()
    processed.forEach((cmd) => {
      const matches = cmd.match(/\{\{(\w+)\}\}/g)
      if (matches) {
        matches.forEach((match) => remainingPlaceholders.add(match))
      }
    })

    if (remainingPlaceholders.size > 0) {
      const missingKeys = Array.from(remainingPlaceholders)
        .map((p) => p.replace(/[{}]/g, ''))
        .join(', ')
      throw new Error(`Missing required parameters: ${missingKeys}`)
    }

    return processed
  }

  /**
   * Execute a prompt with given parameters and user context
   *
   * @param promptId - ID of the prompt to execute
   * @param parameters - Parameters for prompt execution:
   *   - Record<string, unknown>: Structured parameters for {{key}} interpolation
   *   - string: Simple parameter for {{PARAMETERS}} or append to first command
   *   - undefined: No parameters (commands executed as-is)
   * @param username - User identity for execution (determines permissions)
   * @param executionMode - Context of execution: 'direct', 'scheduled', or 'webhook'
   * @param options - Additional execution options
   * @returns Object with threadId and optionally lastEvent (if awaitFinalAnswer)
   */
  async executePrompt(
    promptId: string,
    parameters: Record<string, unknown> | string | undefined,
    username: string,
    executionMode: 'direct' | 'scheduled' | 'webhook',
    options?: {
      title?: string
      awaitFinalAnswer?: boolean
      projectName?: string // Optional: specify project name (for scheduler context)
    }
  ): Promise<{ threadId: string; lastEvent?: MessageEvent }> {
    // Verify execution is initialized
    if (!this.threadCodayManager || !this.threadService || !this.codayOptions || !this.logger) {
      throw new Error('PromptExecutionService not initialized. Call initialize() first.')
    }

    const { title, awaitFinalAnswer = false, projectName: contextProjectName } = options || {}

    // Load prompt configuration
    // If projectName provided in options (scheduler context), use it directly
    let prompt: any
    let projectName: string

    if (contextProjectName) {
      // Use provided project name (from scheduler context)
      projectName = contextProjectName
      prompt = await this.promptService.get(projectName, promptId)
      if (!prompt) {
        throw new Error(`Prompt ${promptId} not found in project ${projectName}`)
      }
    } else {
      // Search for prompt across all projects
      const result = await this.promptService.getById(promptId)
      if (!result) {
        throw new Error(`Prompt not found: ${promptId}`)
      }
      prompt = result.prompt
      projectName = result.projectName
    }

    // For webhook execution, verify webhookEnabled flag
    if (executionMode === 'webhook' && !prompt.webhookEnabled) {
      throw new Error(`Prompt ${promptId} is not enabled for webhook execution`)
    }

    // Validate prompt has commands
    if (!prompt.commands || prompt.commands.length === 0) {
      throw new Error('Prompt has no commands configured')
    }

    // Process commands with parameters
    const prompts = this.processCommands(prompt.commands, parameters)

    if (!username) {
      throw new Error('Username is required')
    }

    // Determine thread to use based on threadLifetime
    const threadId = await this.resolveThreadId(prompt, projectName, username, title)

    // Configure one-shot Coday instance with automatic prompts
    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true, // Creates isolated instance that terminates after processing
      project: projectName, // Target project for the AI agent interaction
      thread: threadId, // Use the newly created thread
      prompts: prompts, // User prompts
    }

    console.log(
      `[PROMPT_EXEC] Creating instance for ${executionMode} execution with ${prompts.length} prompts:`,
      prompts
    )

    // Create a thread-based Coday instance for this prompt (without SSE connection)
    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, oneShotOptions)

    // IMPORTANT: Prepare Coday without starting run() to subscribe to events first
    // Because interactor.events is a Subject (not ReplaySubject), we must subscribe BEFORE run() emits events
    instance.prepareCoday()
    const interactor = instance.coday!.interactor

    // Log successful prompt initiation
    const logData = {
      project: projectName,
      title: title ?? 'Untitled',
      username,
      clientId: threadId, // Use threadId as clientId for log correlation
      promptCount: prompts.length,
      awaitFinalAnswer: !!awaitFinalAnswer,
      promptName: prompt.name,
      promptId: prompt.id,
      executionMode,
    }

    // Use appropriate logger based on execution mode
    if (executionMode === 'webhook') {
      this.logger.logWebhook(logData)
    } else {
      // For direct and scheduled, we could add new log methods or reuse webhook
      this.logger.logWebhook({ ...logData, webhookName: prompt.name, webhookUuid: prompt.id })
    }

    if (awaitFinalAnswer) {
      // Synchronous mode: wait for completion
      // Collect all assistant messages during the run
      const assistantMessages: MessageEvent[] = []
      const subscription = interactor.events
        .pipe(
          filter((event: CodayEvent) => {
            console.log(
              `[PROMPT_EXEC] Received event type: ${event.type}, role: ${event instanceof MessageEvent ? event.role : 'N/A'}`
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
          error: `Prompt execution failed: ${errorMessage}`,
          username,
          project: projectName,
          clientId: threadId,
        })
        console.error('[PROMPT_EXEC] Error waiting for prompt completion:', error)

        // Cleanup on error
        await this.threadCodayManager.cleanup(threadId)

        throw error
      }
    } else {
      // Asynchronous mode: return immediately with thread ID
      // Start Coday run in background
      instance.coday!.run().catch((error: unknown) => {
        console.error('[PROMPT_EXEC] Error during prompt Coday run:', error)
      })

      // Schedule cleanup after a reasonable timeout (e.g., 5 minutes)
      setTimeout(
        () => {
          this.threadCodayManager!.cleanup(threadId).catch((error: unknown) => {
            console.error('[PROMPT_EXEC] Error cleaning up prompt thread after timeout:', error)
          })
        },
        5 * 60 * 1000
      ) // 5 minutes

      return { threadId }
    }
  }
}
