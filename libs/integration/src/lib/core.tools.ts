import { AssistantToolFactory, Interactor, CodayTool, CommandContext } from '@coday/model'

export class CoreTools extends AssistantToolFactory {
  name = 'CORE'

  constructor(
    interactor: Interactor,
    private readonly baseUrl?: string
  ) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    return [
      {
        type: 'function',
        function: {
          name: 'get_context_info',
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
          description: `Wait for a specified number of seconds before continuing.
Useful after starting a process to give it time to initialize before reading logs or checking status.
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
