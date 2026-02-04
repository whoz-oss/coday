import type { PromptExecutionService } from './prompt-execution.service'
import type { PromptService } from './prompt.service'

/**
 * WebhookService - Delegates webhook execution to PromptExecutionService
 *
 * ARCHITECTURE:
 * - This service is a thin wrapper around PromptExecutionService
 * - It validates that prompts have webhookEnabled = true
 * - It delegates all execution logic to PromptExecutionService
 * - Kept for backward compatibility and injection into Coday runtime
 *
 * MIGRATION NOTE:
 * This service previously managed webhooks as separate entities.
 * Now webhooks are simply prompts with webhookEnabled=true.
 * All CRUD operations are handled by PromptService.
 * This service only handles execution delegation.
 */
export class WebhookService {
  private promptExecutionService?: PromptExecutionService
  private promptService?: PromptService

  constructor() {
    // Empty constructor - services will be injected via initializeExecution
  }

  /**
   * Initialize webhook execution dependencies
   * Called after server initialization with required services
   */
  initializeExecution(promptExecutionService: PromptExecutionService, promptService: PromptService): void {
    this.promptExecutionService = promptExecutionService
    this.promptService = promptService
    console.log('[WEBHOOK] WebhookService initialized with PromptExecutionService delegation')
  }

  /**
   * Execute a webhook by delegating to PromptExecutionService
   *
   * This method is called by the /api/webhooks/:promptId/execute endpoint
   * and by agents that need to trigger webhooks programmatically.
   *
   * @param promptId - ID of the prompt to execute (must have webhookEnabled=true)
   * @param parameters - Parameters to pass to the prompt execution
   * @param username - Username executing the webhook
   * @param title - Optional title for the thread
   * @param awaitFinalAnswer - Whether to wait for completion (default: false)
   * @returns Promise<{ threadId: string, lastEvent?: any }>
   */
  async executeWebhook(
    promptId: string,
    parameters: Record<string, unknown>,
    username: string,
    title?: string,
    awaitFinalAnswer: boolean = false
  ): Promise<{ threadId: string; lastEvent?: any }> {
    // Verify execution is initialized
    if (!this.promptExecutionService || !this.promptService) {
      throw new Error('WebhookService not initialized. Call initializeExecution() first.')
    }

    console.log(`[WEBHOOK] Delegating execution to PromptExecutionService for prompt ${promptId}`)

    // Delegate to PromptExecutionService (it will validate webhookEnabled)
    return this.promptExecutionService.executePrompt(promptId, parameters, username, 'webhook', {
      title,
      awaitFinalAnswer,
    })
  }

  /**
   * DEPRECATED: Get webhook by UUID
   * This method is kept for backward compatibility with old code that might call it.
   * New code should use PromptService.getById() directly.
   *
   * @deprecated Use PromptService.getById() instead
   */
  async getByUuid(promptId: string): Promise<{ webhook: any; projectName: string } | null> {
    if (!this.promptService) {
      throw new Error('WebhookService not initialized')
    }

    console.warn('[WEBHOOK] DEPRECATED: getByUuid() called. Use PromptService.getById() instead.')

    const result = await this.promptService.getById(promptId)
    if (!result) {
      return null
    }

    // Return in old format for backward compatibility
    return {
      webhook: result.prompt,
      projectName: result.projectName,
    }
  }
}
