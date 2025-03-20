import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { searchJiraIssuesWithAI } from './search-jira-issues'
import { addJiraComment } from './add-jira-comment'
import { retrieveJiraIssue } from './retrieve-jira-issue'
import { jiraFieldMappingCache } from './jira-field-mapping-cache'

export class JiraTools extends AssistantToolFactory {
  name = 'JIRA'

  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }

  protected async buildTools(context: CommandContext): Promise<CodayTool[]> {
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

    // Get field mapping from cache service with 8-hour TTL
    const { description: customFieldMappingDescription } = await jiraFieldMappingCache.getMappingForInstance(
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
          PAGINATION:
          - First call: omit nextPageToken
          - If more results available:
            * Current page results are displayed
            * User is prompted for next page
            * If confirmed, call again with provided nextPageToken
          
          QUERY FORMATS:
          1. JQL Queries (filtering):
             - Custom fields use cf[] format
             - Example: cf[10582] = "Data Intelligence"
             - Case sensitive
             - Multiple conditions: use AND, OR
             - Example: cf[10582] = "Data Intelligence" AND status = "Open"
          2. Fields Selection (retrieving):
            - Custom fields use customfield_ format
            - Example: ["basic", "customfield_10582"]

          ERROR HANDLING:
            - Invalid JQL returns error message
            - Invalid token returns new search
            - Exceeded max results warns user,
        `,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: `JQL query for searching issues. Available fields:
              ${customFieldMappingDescription.jqlResearchDescription}`,
            },
            nextPageToken: {
              type: 'string',
              description: 'Token to fetch next page. Omit for first page. Case sensitive. Returns error if invalid.',
            },
            maxResults: {
              type: 'number',
              description:
                'Optional: Maximum number of results to return per page (default: 50, max: 100). Reduce this if context becomes too large.',
            },
            fields: {
              type: 'array',
              items: { type: 'string' },
              description: `Optional: Specify which fields to return.
                Presets available:
                - ["minimal"]: key, summary
                - ["basic"]: key, summary, status
                - ["detailed"]: key, summary, status, description, priority
                - ["dates"]: created, updated, duedate
                - ["navigation"]: key, parent, subtasks, issuelinks
                - ["tracking"]: assignee, reporter, created, updated, status
                Usage options:
                - Use presets: e.g., ["basic", "dates"] combines both presets
                - Individual fields: e.g., ["key", "summary", "status"]
                - Special values: ["*all"] for all fields, ["*navigable"] for navigable fields
                - Custom field values: here is a mapping of all customfield available with a description ${customFieldMappingDescription.customFields}.
                  Example flow:
                    1. The user request mention squad or client
                    2. The fields must include customfield_10595 and customfield_10564
                Default: ["basic"] preset`,
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
