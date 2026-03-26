import type { CodayOptions, CodayLogger, MessagingInboundEvent } from '@coday/model'
import type { ThreadService } from './thread.service'

// ThreadCodayManager is in apps/server, so we use a type-only import to avoid circular dependencies
// The actual instance will be injected via initialize()
type ThreadCodayManager = any

/**
 * MessagingGatewayService - Handles inbound events from external messaging platforms
 *
 * Receives pre-resolved MessagingInboundEvent objects from platform connectors
 * (Slack, Discord, Teams, …), creates an isolated one-shot Coday thread, and
 * fires the agent in the background so it can respond via messaging tools.
 */
export class MessagingGatewayService {
  private threadCodayManager?: ThreadCodayManager
  private threadService?: ThreadService
  private codayOptions?: CodayOptions
  private logger?: CodayLogger

  /**
   * Initialize execution dependencies.
   * Called after server initialization with required services.
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
   * Handle an inbound messaging event.
   *
   * Validates the event, creates a new thread, builds a one-shot Coday instance
   * and fires it in the background. Returns immediately with the threadId.
   *
   * @param event - Pre-resolved inbound event from the platform connector
   * @returns { threadId } — identifier of the created thread
   */
  async handleEvent(event: MessagingInboundEvent): Promise<{ threadId: string }> {
    if (!this.threadCodayManager || !this.threadService || !this.codayOptions || !this.logger) {
      throw new Error('MessagingGatewayService not initialized. Call initialize() first.')
    }

    const { source, username, message, projectName, replyContext, eventType, conversationContext } = event

    if (!source || !username || !message || !projectName) {
      throw new Error('Missing required fields: source, username, message, projectName')
    }

    // Create a dedicated thread for this interaction
    const thread = await this.threadService.createThread(projectName, username)
    const threadId = thread.id

    // Build the prompt that will be sent to the agent
    const prompt = [
      `You received a message from ${source}.`,
      `User: ${username}`,
      eventType ? `Event type: ${eventType}` : null,
      `Reply context: ${JSON.stringify(replyContext)}`,
      conversationContext ? `\nRecent conversation context:\n${conversationContext}\n` : null,
      `User message: ${message}`,
      `\nRespond to this message using the appropriate messaging tools. Use the reply context to post your response in the right place.`,
    ]
      .filter(Boolean)
      .join('\n')

    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true,
      project: projectName,
      thread: threadId,
      prompts: [prompt],
    }

    // Create instance without an SSE connection (fire-and-forget)
    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, oneShotOptions)

    instance.prepareCoday()

    // Start agent in the background — it will reply via messaging tools
    instance.coday!.run().catch((error: unknown) => {
      console.error('[MESSAGING_GATEWAY] Error during agent run:', error)
    })

    // Schedule cleanup after 10 minutes
    setTimeout(
      () => {
        this.threadCodayManager!.cleanup(threadId).catch((error: unknown) => {
          console.error('[MESSAGING_GATEWAY] Error cleaning up thread after timeout:', error)
        })
      },
      10 * 60 * 1000
    )

    return { threadId }
  }
}
