import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  IntegrationConfig,
  Interactor,
} from '@coday/model'
import { MessagingGatewayService } from '@coday/service'

/**
 * MessagingTools — provides the MESSAGING__reply tool.
 *
 * This tool allows agents to send replies back to the originating messaging
 * platform (Slack, Discord, …) without being coupled to a specific platform.
 * The gateway routes the message to the right connector based on the `source`
 * field, which is provided by the inbound prompt.
 */
export class MessagingTools extends AssistantToolFactory {
  static readonly TYPE = 'MESSAGING' as const

  constructor(
    interactor: Interactor,
    private readonly messagingGateway: MessagingGatewayService,
    instanceName: string,
    config: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(_context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const replyTool: FunctionTool<{ source: string; channel: string; text: string; thread_ts?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__reply`,
        description:
          'Send a reply to a messaging platform (Slack, Discord, …). ' +
          'Use the source, channel, and optional thread_ts from the inbound event context provided in the prompt. ' +
          'Always prefer replying in the same thread when thread_ts is available.',
        parameters: {
          type: 'object',
          properties: {
            source: {
              type: 'string',
              description: 'Messaging platform identifier, e.g. "SLACK", "DISCORD". Provided in the prompt.',
            },
            channel: {
              type: 'string',
              description: 'Channel or conversation ID to send the reply to. Provided in the prompt.',
            },
            text: {
              type: 'string',
              description: 'Message text to send. Use platform-appropriate markdown.',
            },
            thread_ts: {
              type: 'string',
              description:
                'Optional: thread timestamp to reply in a thread. Use only when provided in the prompt context.',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { source: string; channel: string; text: string; thread_ts?: string }) => {
          try {
            const replyContext: Record<string, string> = { channel: params.channel }
            if (params.thread_ts) replyContext.thread_ts = params.thread_ts

            await this.messagingGateway.sendMessage(params.source, replyContext, params.text)
            return `Message sent to ${params.source} channel ${params.channel}${
              params.thread_ts ? ` (thread: ${params.thread_ts})` : ''
            }`
          } catch (error) {
            const msg = error instanceof Error ? error.message : String(error)
            this.interactor.error(`[MESSAGING] Failed to send message: ${msg}`)
            return `Error sending message: ${msg}`
          }
        },
      },
    }

    return [replyTool]
  }
}
