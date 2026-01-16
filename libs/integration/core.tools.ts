import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { CommandContext, Interactor } from '../model'
import { CodayServices } from '../coday-services'

export class CoreTools extends AssistantToolFactory {
  name = 'CORE'

  constructor(
    interactor: Interactor,
    private services: CodayServices
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
            }

            // Add conversation URL if baseUrl is configured
            if (this.services.options?.baseUrl) {
              // Build complete URL with baseUrl from options
              const baseUrl = this.services.options.baseUrl.endsWith('/')
                ? this.services.options.baseUrl.slice(0, -1)
                : this.services.options.baseUrl
              info.conversationUrl = `${baseUrl}/project/${projectName}/thread/${threadId}`
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
