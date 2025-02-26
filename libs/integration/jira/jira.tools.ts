import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { createJiraFieldMapping } from './jira-field-mapper'
import { AsyncAssistantToolFactory } from '../async-assistant-tool-factory'
import { searchJiraIssuesWithAI } from './search-jira-issues'
import { addJiraComment } from './add-jira-comment'
import { retrieveJiraIssue } from './retrieve-jira-issue'

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
    const { description: customFieldMappingDescription } = await createJiraFieldMapping(
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      this.interactor,
      50
    )

    const retrieveIssue = ({ ticketId }: { ticketId: string }) => {
      return retrieveJiraIssue(ticketId, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor)
    }
    const retrieveJiraTicketFunction: FunctionTool<{
      ticketId: string
    }> = {
      type: 'function',
      function: {
        name: 'retrieveJiraIssue',
        description: 'Retrieve Jira issue details by ticket ID.',
        parameters: {
          type: 'object',
          properties: {
            ticketId: { type: 'string', description: 'Jira issue ID' },
          },
        },
        parse: JSON.parse,
        function: retrieveIssue,
      },
    }

    const searchJiraIssuesFunction: FunctionTool<{
      jql: string
      nextPageToken?: string | undefined
      maxResults: number
      fields: string[]
    }> = {
      type: 'function',
      function: {
        name: 'searchJiraIssues',
        description: `Perform a flexible search across Jira issues using a jql query based on natural language.
        Pagination handling:
        1. When the response includes a nextPageToken:  
        - Display current page results
        - Ask user if they want to see more results   
        - Only if confirmed, use the nextPageToken to fetch next page
        2. Example flow:   
        a. First search: call without nextPageToken   
        b. If more results exist:      
        - Use queryUser tool to ask for confirmation      
        - If user confirms, make another call with the nextPageToken
        The following mapping provides all the keys you can use to perform the jql request. Do not invent key, all is provided here:
        ${customFieldMappingDescription.jqlResearchDescription}`,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: 'The jql query based on natural language, enable complex research on JIRA',
            },
            nextPageToken: {
              type: 'string',
              description: 'Optional token to fetch the next page of results',
            },
            maxResults: {
              type: 'number',
              description:
                'Optional: Maximum number of results to return per page (default: 50, max: 100). Reduce this if context becomes too large.',
            },
            fields: {
              type: 'array',
              items: { type: 'string' },
              description:
                'Optional: Specify which fields to return.\n\n' +
                'Presets available:\n' +
                '- ["minimal"]: key, summary\n' +
                '- ["basic"]: key, summary, status\n' +
                '- ["detailed"]: key, summary, status, description, priority\n' +
                '- ["dates"]: created, updated, duedate\n' +
                '- ["navigation"]: key, parent, subtasks, issuelinks\n' +
                '- ["tracking"]: assignee, reporter, created, updated, status\n\n' +
                'Usage options:\n' +
                '- Use presets: e.g., ["basic", "dates"] combines both presets\n' +
                '- Individual fields: e.g., ["key", "summary", "status"]\n' +
                '- Special values: ["*all"] for all fields, ["*navigable"] for navigable fields\n\n' +
                'Default: ["basic"] preset',
            },
          },
        },
        parse: JSON.parse,
        function: ({ jql, nextPageToken, maxResults, fields }) =>
          searchJiraIssuesWithAI({
            jql,
            nextPageToken,
            maxResults,
            fields,
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
    result.push(searchJiraIssuesFunction)
    result.push(addCommentFunction)

    return result
  }
}
