import { AssistantToolFactory } from './assistant-tool-factory'
import { Interactor } from '@coday/model/interactor'
import { CommandContext } from '@coday/model/command-context'
import { CodayTool } from '@coday/model/coday-tool'

export class CoreTools extends AssistantToolFactory {
  name = 'CORE'

  constructor(
    interactor: Interactor,
    private readonly services: CodayServices
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
            if (this.services.options?.baseUrl) {
              // Build complete URL with baseUrl from options
              // For custom protocols (e.g., coday://), baseUrl ends with '://' so we don't add a slash
              // For HTTP URLs (e.g., http://localhost:3000), we add a slash
              const baseUrl = this.services.options.baseUrl
              const separator = baseUrl.endsWith('://') ? '' : '/'
              info.conversationUrl = `${baseUrl}${separator}project/${projectName}/thread/${threadId}`
            }

            return JSON.stringify(info, null, 2)
          },
        },
      },
    ]
  }

  async kill(): Promise<void> {
    // No cleanup needed
  }
}
