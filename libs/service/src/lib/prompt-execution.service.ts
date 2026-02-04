import type { CodayOptions, CodayLogger } from '@coday/model'
import { CodayEvent, MessageEvent } from '@coday/model'
import type { ThreadService } from './thread.service'
import { PromptService } from './prompt.service'
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
   * Execute a prompt with given parameters and user context
   *
   * @param promptId - ID of the prompt to execute
   * @param parameters - Parameters for prompt execution (placeholder values, etc.)
   * @param username - User identity for execution (determines permissions)
   * @param executionMode - Context of execution: 'direct', 'scheduled', or 'webhook'
   * @param options - Additional execution options
   * @returns Object with threadId and optionally lastEvent (if awaitFinalAnswer)
   */
  async executePrompt(
    promptId: string,
    parameters: Record<string, unknown>,
    username: string,
    executionMode: 'direct' | 'scheduled' | 'webhook',
    options?: {
      title?: string
      awaitFinalAnswer?: boolean
    }
  ): Promise<{ threadId: string; lastEvent?: MessageEvent }> {
    // Verify execution is initialized
    if (!this.threadCodayManager || !this.threadService || !this.codayOptions || !this.logger) {
      throw new Error('PromptExecutionService not initialized. Call initialize() first.')
    }

    const { title, awaitFinalAnswer = false } = options || {}

    // Load prompt configuration
    const result = await this.promptService.getById(promptId)
    if (!result) {
      throw new Error(`Prompt not found: ${promptId}`)
    }

    const { prompt, projectName } = result

    // For webhook execution, verify webhookEnabled flag
    if (executionMode === 'webhook' && !prompt.webhookEnabled) {
      throw new Error(`Prompt ${promptId} is not enabled for webhook execution`)
    }

    // Validate prompt has commands
    if (!prompt.commands || prompt.commands.length === 0) {
      throw new Error('Prompt has no commands configured')
    }

    // Replace placeholders in template commands
    const prompts = prompt.commands.map((command) => {
      let processedCommand = command
      // Simple string replacement for placeholders like {{key}}
      Object.entries(parameters).forEach(([key, value]) => {
        const placeholder = `{{${key}}}`
        processedCommand = processedCommand.replace(new RegExp(placeholder, 'g'), String(value))
      })
      return processedCommand
    })

    if (!username) {
      throw new Error('Username is required')
    }

    // Create a new thread for the prompt execution
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
