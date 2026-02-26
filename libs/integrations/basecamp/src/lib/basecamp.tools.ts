import { UserService } from '@coday/service'
import { AssistantToolFactory } from '@coday/model'
import { BasecampOAuth } from './basecamp-oauth'
import { Interactor } from '@coday/model'
import { IntegrationService } from '@coday/service'
import { OAuthCallbackEvent } from '@coday/model'
import { CommandContext } from '@coday/model'
import { CodayTool } from '@coday/model'
import { FunctionTool } from '@coday/model'
import { listBasecampProjects } from './list-projects'
import { getBasecampMessageBoard } from './get-message-board'
import { getBasecampMessages } from './get-messages'
import { getBasecampMessage } from './get-message'

export class BasecampTools extends AssistantToolFactory {
  static readonly TYPE = 'BASECAMP' as const

  private oauth: BasecampOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    private readonly userService: UserService,
    instanceName: string,
    config?: any
  ) {
    super(interactor, instanceName, config)
  }

  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (this.oauth) {
      await this.oauth.handleCallback(event)
    }
  }

  protected async buildTools(context: CommandContext): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!this.integrationService.hasIntegration(this.name)) {
      return result
    }

    const config = this.integrationService.getIntegration(this.name)
    if (!config) {
      return result
    }

    // For OAuth, username is the clientId of the app and apiKey its clientSecret (masked)
    const clientId = config.username
    const clientSecret = config.apiKey

    // Note: for local dev, use http://localhost:300x/oauth/callback
    const redirectUri = config.oauth2?.redirect_uri
    if (!redirectUri) {
      this.interactor.displayText('Basecamp integration requires redirectUri')
      return result
    }

    if (!clientId || !clientSecret) {
      this.interactor.displayText('Basecamp integration requires clientId (username) and clientSecret (apiKey)')
      return result
    }

    const projectName = context.project.name

    this.oauth = new BasecampOAuth(clientId, clientSecret, redirectUri, this.interactor, this.userService, projectName)

    const listProjectsTool: FunctionTool<{ page?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}__listProjects`,
        description:
          'List all projects in the connected Basecamp account. Will prompt for OAuth authentication if not already connected. ' +
          'Basecamp uses geared pagination: page 1 returns 15 results, page 2 returns 30, page 3 returns 50, and page 4+ return 100 results each. ' +
          'Use the page parameter to navigate through results.',
        parameters: {
          type: 'object',
          properties: {
            page: {
              type: 'number',
              description: 'Page number to retrieve (optional). If not provided, returns page 1.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ page }) => listBasecampProjects(this.oauth!, page),
      },
    }

    result.push(listProjectsTool)

    const getMessageBoardTool: FunctionTool<{ projectId: number }> = {
      type: 'function',
      function: {
        name: `${this.name}__getMessageBoard`,
        description:
          'Get the message board ID for a Basecamp project. You need this ID to retrieve messages from the project.',
        parameters: {
          type: 'object',
          properties: {
            projectId: {
              type: 'number',
              description: 'The project ID (from listBasecampProjects)',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ projectId }) => getBasecampMessageBoard(this.oauth!, projectId),
      },
    }

    result.push(getMessageBoardTool)

    const getMessagesTool: FunctionTool<{ projectId: number; messageBoardId: number; page?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}__getMessages`,
        description:
          'List all messages in a Basecamp message board. Returns a summary of each message with title, author, date, and preview. ' +
          'Basecamp uses geared pagination: page 1 returns 15 results, page 2 returns 30, page 3 returns 50, and page 4+ return 100 results each. ' +
          'Use the page parameter to navigate through results.',
        parameters: {
          type: 'object',
          properties: {
            projectId: {
              type: 'number',
              description: 'The project ID (from listBasecampProjects)',
            },
            messageBoardId: {
              type: 'number',
              description: 'The message board ID (from getBasecampMessageBoard)',
            },
            page: {
              type: 'number',
              description: 'Page number to retrieve (optional). If not provided, returns page 1.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ projectId, messageBoardId, page }) =>
          getBasecampMessages(this.oauth!, projectId, messageBoardId, page),
      },
    }

    result.push(getMessagesTool)

    const getMessageTool: FunctionTool<{ projectId: number; messageId: number }> = {
      type: 'function',
      function: {
        name: `${this.name}__getMessage`,
        description:
          'Get the full content of a specific Basecamp message, including title, author, date, and complete text content.',
        parameters: {
          type: 'object',
          properties: {
            projectId: {
              type: 'number',
              description: 'The project ID (from listBasecampProjects)',
            },
            messageId: {
              type: 'number',
              description: 'The message ID (from getBasecampMessages)',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ projectId, messageId }) => getBasecampMessage(this.oauth!, projectId, messageId),
      },
    }

    result.push(getMessageTool)

    return result
  }
}
