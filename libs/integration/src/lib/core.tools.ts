import { AssistantToolFactory, Interactor, CodayTool, CommandContext } from '@coday/model'

export class CoreTools extends AssistantToolFactory {
  static readonly TYPE = 'CORE' as const

  constructor(
    interactor: Interactor,
    instanceName: string,
    config: any,
    private readonly baseUrl?: string
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    return [
      {
        type: 'function',
        function: {
          name: `${this.name}_get_context_info`,
          description: 'Get current contextual information including time, date, username, and conversation URL',
          parameters: {
            type: 'object',
            properties: {},
          },
          parse: JSON.parse,
          function: async () => {
            const now = new Date()
            const threadId = context.aiThread?.id ?? 'no-thread'
            const projectName = context.project.name ?? 'no-project'

            const info: any = {
              currentTime: now.toISOString(),
              currentDate: now.toISOString().split('T')[0],
              dayOfWeek: now.toLocaleDateString('en-US', { weekday: 'long' }),
              username: context.username,
              threadId: threadId,
            }

            // Add conversation URL if baseUrl is configured
            if (this.baseUrl) {
              // Build complete URL with baseUrl from options
              // For custom protocols (e.g., coday://), baseUrl ends with '://' so we don't add a slash
              // For HTTP URLs (e.g., http://localhost:3000), we add a slash
              const baseUrl = this.baseUrl
              const separator = baseUrl.endsWith('://') ? '' : '/'
              info.conversationUrl = `${baseUrl}${separator}project/${projectName}/thread/${threadId}`
            }

            return JSON.stringify(info, null, 2)
          },
        },
      },
      {
        type: 'function',
        function: {
          name: 'wait',
          description: `Pause execution for a specified number of seconds.
Useful when an operation needs time to complete before the next step: waiting for a process to start,
an external service to become ready, a file to be written, etc.
Maximum wait time is 300 seconds (5 minutes).`,
          parameters: {
            type: 'object',
            properties: {
              seconds: {
                type: 'number',
                description: 'Number of seconds to wait (max 300).',
              },
            },
          },
          parse: JSON.parse,
          function: async ({ seconds }: { seconds: number }) => {
            const capped = Math.min(Math.max(1, Math.round(seconds)), 300)
            if (capped !== seconds) {
              this.interactor.warn(`Wait time capped to ${capped}s (requested: ${seconds}s)`)
            }
            await new Promise((resolve) => setTimeout(resolve, capped * 1000))
            return `Waited ${capped}s`
          },
        },
      },
    ]
  }

  override async kill(): Promise<void> {
    // No cleanup needed
  }
}
