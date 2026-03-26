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

    const { source, username, message, projectName, replyContext, conversationContext, targetAgent } = event

    if (!source || !username || !message || !projectName) {
      throw new Error('Missing required fields: source, username, message, projectName')
    }

    // Create a dedicated thread for this interaction
    const thread = await this.threadService.createThread(projectName, username)
    const threadId = thread.id

    // Build the prompt that will be sent to the agent.
    // If targetAgent is specified, prefix with @AgentName so Coday routes to the right agent.
    const promptLines = [
      `You received a mention in a ${source} channel.`,
      `Channel: ${replyContext.channel}`,
      replyContext.thread_ts ? `Thread: ${replyContext.thread_ts} (reply in this thread)` : null,
      conversationContext
        ? `\n## Conversation leading up to this mention\nRead this carefully — it contains the full discussion context, possibly with multiple participants and viewpoints:\n\n${conversationContext}\n`
        : null,
      `## The mention that triggered you\nUser ${username} said: ${message}`,
      `\n## How to respond\nRespond using SLACK__post_message with channel ${replyContext.channel}.${replyContext.thread_ts ? ` Use thread_ts ${replyContext.thread_ts} to reply in the same thread.` : ' Do NOT use thread_ts — reply directly in the channel.'}`,
      `Take into account ALL messages in the conversation above, not just the mention. If there are different opinions or a debate, acknowledge them.`,
    ]
      .filter(Boolean)
      .join('\n')

    const prompt = targetAgent ? `@${targetAgent} ${promptLines}` : promptLines

    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true,
      project: projectName,
      thread: threadId,
      prompts: [prompt],
    }

    // Create instance without an SSE connection
    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, oneShotOptions)

    instance.prepareCoday()

    // Run the agent and wait for completion so the caller knows when processing is done
    try {
      await instance.coday!.run()
    } catch (error: unknown) {
      console.error('[MESSAGING_GATEWAY] Error during agent run:', error)
    }

    // Schedule cleanup after 1 minute (agent is done, just allow some buffer)
    setTimeout(() => {
      this.threadCodayManager!.cleanup(threadId).catch((error: unknown) => {
        console.error('[MESSAGING_GATEWAY] Error cleaning up thread after timeout:', error)
      })
    }, 60 * 1000)

    return { threadId }
  }
}
