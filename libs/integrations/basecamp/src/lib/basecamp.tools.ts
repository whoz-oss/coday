import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { CommandContext, Interactor } from '../../model'
import { IntegrationService } from '../../service/integration.service'
import { BasecampOAuth } from './basecamp-oauth'
import { listBasecampProjects } from './list-projects'
import { getBasecampMessageBoard } from './get-message-board'
import { getBasecampMessages } from './get-messages'
import { getBasecampMessage } from './get-message'
import { OAuthCallbackEvent } from '@coday/coday-events'
import { UserService } from '@coday/service/user.service'

export class BasecampTools extends AssistantToolFactory {
  name = 'BASECAMP'
  private oauth: BasecampOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    private readonly userService: UserService
  ) {
    super(interactor)
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

    const listProjectsTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'listBasecampProjects',
        description:
          'List all projects in the connected Basecamp account. Will prompt for OAuth authentication if not already connected.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: async () => listBasecampProjects(this.oauth!),
      },
    }

    result.push(listProjectsTool)

    const getMessageBoardTool: FunctionTool<{ projectId: number }> = {
      type: 'function',
      function: {
        name: 'getBasecampMessageBoard',
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

    const getMessagesTool: FunctionTool<{ projectId: number; messageBoardId: number }> = {
      type: 'function',
      function: {
        name: 'getBasecampMessages',
        description:
          'List all messages in a Basecamp message board. Returns a summary of each message with title, author, date, and preview.',
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
          },
        },
        parse: JSON.parse,
        function: async ({ projectId, messageBoardId }) => getBasecampMessages(this.oauth!, projectId, messageBoardId),
      },
    }

    result.push(getMessagesTool)

    const getMessageTool: FunctionTool<{ projectId: number; messageId: number }> = {
      type: 'function',
      function: {
        name: 'getBasecampMessage',
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
