import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { createJiraFieldMapping } from './jira-field-mapper'
import { AsyncAssistantToolFactory } from '../async-assistant-tool-factory'
import { searchJiraIssuesWithAI } from './search-jira-issues'
import { addJiraComment } from './add-jira-comment'
import { retrieveJiraIssue } from './retrieve-jira-issue'
import { countJiraIssues } from './count-jira-issues'

export class JiraTools extends AsyncAssistantToolFactory {
  private jiraFieldMappingDescription: any = null;

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
    
    // Instead of generating field mapping at initialization, create a function to do it on demand

        // Add a function to initialize Jira field mapping only when needed
    const initializeJiraFieldMappingFunction: FunctionTool<{
      maxResults?: number
    }> = {
      type: 'function',
      function: {
        name: 'initializeJiraFieldMapping',
        description: 'Initialize Jira field mapping. This must be called before using searchJiraIssues or countJiraIssues functions.',
        parameters: {
          type: 'object',
          properties: {
            maxResults: { 
              type: 'number', 
              description: 'Optional: Maximum number of issues to use for mapping (default: 50)' 
            },
          },
        },
        parse: JSON.parse,
        function: async ({ maxResults = 50 }) => {
          try {
            const { description } = await createJiraFieldMapping(
              jiraBaseUrl,
              jiraApiToken,
              jiraUsername,
              this.interactor,
              maxResults
            )
            this.jiraFieldMappingDescription = description;
            return { success: true, message: 'Jira field mapping initialized successfully' };
          } catch (error) {
            return { 
              success: false, 
              message: `Failed to initialize Jira field mapping: ${error}. Please try again or contact support.` 
            };
          }
        },
      },
    };

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
          IMPORTANT: 
          - You MUST call initializeJiraFieldMapping first before using this function
          - Always expose the details of your research to the user to avoid misunderstanding (the fields you fetch, the jql request, if there is a next page token...)
          - Always explicitly calling out the JQL URL 
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
              ${this.jiraFieldMappingDescription?.jqlResearchDescription || 'Call initializeJiraFieldMapping first to get field descriptions'}`,
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
                - Custom field values: here is a mapping of all customfield available with a description ${this.jiraFieldMappingDescription?.customFields || 'Call initializeJiraFieldMapping first to get field descriptions'}.
                  Example flow:
                    1. The user request mention squad or client
                    2. The fields must include customfield_10595 and customfield_10564
                Default: ["basic"] preset`,
            },
          },
        },
        parse: JSON.parse,
        function: ({ jql, nextPageToken, maxResults, fields }) => {
          if (!this.jiraFieldMappingDescription) {
            return Promise.resolve({
              error: true,
              message: 'Jira field mapping not initialized. Please call initializeJiraFieldMapping first.'
            });
          }
          
          return searchJiraIssuesWithAI({
            jql,
            nextPageToken,
            maxResults,
            fields,
            jiraBaseUrl,
            jiraApiToken,
            jiraUsername,
            interactor: this.interactor,
          });
        },
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

    const countJiraIssuesFunction: FunctionTool<{
      jql: string
    }> = {
      type: 'function',
      function: {
        name: 'countJiraIssues',
        description: `Count the number of jira issues matching the jql. Always expose the jql to the user
          IMPORTANT: 
          - You MUST call initializeJiraFieldMapping first before using this function
          - Always explicitly calling out the JQL URL
        `,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: `JQL query for counting issues. Available fields:
              ${this.jiraFieldMappingDescription?.jqlResearchDescription || 'Call initializeJiraFieldMapping first to get field descriptions'}`,
            },
          },
        },
        parse: JSON.parse,
        function: ({ jql }) => {
          if (!this.jiraFieldMappingDescription) {
            return Promise.resolve({
              error: true,
              message: 'Jira field mapping not initialized. Please call initializeJiraFieldMapping first.'
            });
          }
          
          return countJiraIssues(jql, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor);
        },
      },
    }

    result.push(initializeJiraFieldMappingFunction)
    result.push(retrieveJiraTicketFunction)
    result.push(searchJiraIssuesFunction)
    result.push(addCommentFunction)
    result.push(countJiraIssuesFunction)

    return result
  }
}
