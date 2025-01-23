import { retrieveJiraTicket } from './retrieve-jira-ticket'
import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { generateFieldMapping } from './jira-field-mapper'
import { AsyncAssistantToolFactory } from '../async-assistant-tool-factory'
import { searchJiraTicketsWithAI } from './search-jira-tickets'
import { addJiraComment } from './add-jira-comment'

export class JiraTools extends AsyncAssistantToolFactory {
  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }

  protected async buildAsyncTools(context: CommandContext): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    if (!this.integrationService.hasIntegration('JIRA')) {
      return result
    }

    const jiraBaseUrl = this.integrationService.getApiUrl('JIRA')
    const jiraUsername = this.integrationService.getUsername('JIRA')
    const jiraApiToken = this.integrationService.getApiKey('JIRA')
    if (!(jiraBaseUrl && jiraUsername && jiraApiToken)) {
      return result
    }

    // Generate custom field mapping during initialization
    // Increased to 500 tickets for more comprehensive mapping
    const { description: customFieldMappingDescription } = await generateFieldMapping(
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      this.interactor,
      50
    )

    const retrieveTicket = ({ ticketId }: { ticketId: string }) => {
      return retrieveJiraTicket(ticketId, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor)
    }
    const retrieveJiraTicketFunction: FunctionTool<{
      ticketId: string
    }> = {
      type: 'function',
      function: {
        name: 'retrieveJiraTicket',
        description: 'Retrieve Jira ticket details by ticket ID.',
        parameters: {
          type: 'object',
          properties: {
            ticketId: { type: 'string', description: 'Jira ticket ID' },
          },
        },
        parse: JSON.parse,
        function: retrieveTicket,
      },
    }

    const searchJiraTicketsFunction: FunctionTool<{
      jql: string
    }> = {
      type: 'function',
      function: {
        name: 'searchJiraTickets',
        description: `Perform a flexible search across Jira tickets using a jql query based on natural language

                ${customFieldMappingDescription}`,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: 'The jql query based on natural language, enable complex research on JIRA',
            },
          },
        },
        parse: JSON.parse,
        function: ({ jql }) =>
          searchJiraTicketsWithAI({
            jql,
            jiraBaseUrl,
            jiraApiToken,
            jiraUsername,
            interactor: this.interactor,
          }),
      },
    }

    const addCommentFunction: FunctionTool<{
      ticketId: string
      comment: string
    }> = {
      type: 'function',
      function: {
        name: 'addJiraComment',
        description: 'Add a comment to a Jira ticket.',
        parameters: {
          type: 'object',
          properties: {
            ticketId: { type: 'string', description: 'Jira ticket ID' },
            comment: { type: 'string', description: 'Comment text to add to the ticket' },
          },
          // required: ['ticketId', 'comment']
        },
        parse: JSON.parse,
        function: ({ ticketId, comment }) =>
          addJiraComment(ticketId, comment, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor),
      },
    }

    result.push(retrieveJiraTicketFunction)
    result.push(searchJiraTicketsFunction)
    result.push(addCommentFunction)

    return result
  }
}
