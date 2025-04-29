import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { searchJiraIssuesWithAI } from './search-jira-issues'
import { addJiraComment } from './add-jira-comment'
import { retrieveJiraIssue } from './retrieve-jira-issue'
import { countJiraIssues } from './count-jira-issues'
import { createJiraIssue } from './create-jira-issue'
import { JiraService } from './jira.service'
import { validateJqlOperators } from './jira.helpers'
import { CreateJiraIssueRequest } from './jira'

export class JiraTools extends AssistantToolFactory {
  name = 'JIRA'
  private jiraService: JiraService
  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
    this.jiraService = new JiraService(interactor, integrationService)
    console.log('constructor')
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    console.log('buildTools')
    if (!this.integrationService.hasIntegration('JIRA')) {
      return result
    }

    const jiraBaseUrl = this.integrationService.getApiUrl('JIRA')
    const jiraUsername = this.integrationService.getUsername('JIRA')
    const jiraApiToken = this.integrationService.getApiKey('JIRA')
    if (!(jiraBaseUrl && jiraUsername && jiraApiToken)) {
      return result
    }

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
        WORKFLOW:
           a) From the fieldMappingInfo get the relevant query keys.
           b) For each query key get the allowed operators.
           b) Use the query key and only the allowed operators to create the jql.
           d) Explicitly calling out the JQL URL
           e) If more results available, current page results are displayed, user is prompted for next page, if confirmed, call again with provided nextPageToken
        `,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: `JQL query for searching issues`,
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
                - Custom field values: here is a mapping of all customfield available with a description (first call to this function will return field mapping information).
                  Example flow:
                    1. The user request mention squad or client
                    2. The fields must include customfield_10595 and customfield_10564
                Default: ["basic"] preset`,
            },
          },
        },
        parse: JSON.parse,
        function: async ({ jql, nextPageToken, maxResults, fields }) => {
          // Lazy initialization happens here
          const initResult = await this.jiraService.ensureInitialized()

          // If we just initialized the service, return the field mapping info to the user
          if (initResult.isNewlyInitialized) {
            return {
              fieldMappingInfo: initResult.fieldMappingInfo,
              message: initResult.message,
            }
          }

          // Validate JQL operators before proceeding
          if (initResult.fieldMapping) {
            validateJqlOperators(jql, initResult.fieldMapping)
          }

          // Otherwise, proceed with the actual Jira search
          return searchJiraIssuesWithAI({
            jql,
            nextPageToken,
            maxResults,
            fields,
            jiraBaseUrl,
            jiraApiToken,
            jiraUsername,
            interactor: this.interactor,
          })
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
        description: `Count the number of jira issues matching the jql.
        WORKFLOW:
           a) From the fieldMappingInfo get the relevant query keys.
           b) For each query key get the allowed operators.
           b) Use the query key and only the allowed operators to create the jql.
           d) Explicitly calling out the JQL URL
        `,
        parameters: {
          type: 'object',
          properties: {
            jql: {
              type: 'string',
              description: `JQL query for counting issues`,
            },
          },
        },
        parse: JSON.parse,
        function: async ({ jql }) => {
          // Lazy initialization happens here
          const initResult = await this.jiraService.ensureInitialized()

          // If we just initialized the service, return the field mapping info to the user
          if (initResult.isNewlyInitialized) {
            return {
              fieldMappingInfo: initResult.fieldMappingInfo,
              message: initResult.message,
            }
          }

          // Validate JQL operators before proceeding
          if (initResult.fieldMapping) {
            validateJqlOperators(jql, initResult.fieldMapping)
          }

          // Otherwise, proceed with the actual count operation
          return countJiraIssues(jql, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor)
        },
      },
    }

    const createIssueFunction: FunctionTool<{
      request: CreateJiraIssueRequest
    }> = {
      type: 'function',
      function: {
        name: 'createJiraIssue',
        description: 'Create a new Jira issue with a request object containing all necessary fields.',
        parameters: {
          type: 'object',
          properties: {
            request: {
              type: 'object',
              description: 'Request object containing all issue fields',
              properties: {
                projectKey: { type: 'string', description: 'Project key where the issue will be created' },
                summary: { type: 'string', description: 'Summary/title of the issue' },
                squad: { type: 'string', description: 'Squad' },
                description: { type: 'string', description: 'Detailed description of the issue' },
                issuetype: { type: 'string', description: 'Type of issue (e.g., "Task", "Bug", "Story")' },
                assignee: { type: 'string', description: 'User ID of the assignee' },
                reporter: { type: 'string', description: 'User ID of the reporter' },
                priority: { type: 'string', description: 'Priority of the issue (e.g., "High", "Medium", "Low")' },
                labels: { type: 'array', items: { type: 'string' }, description: 'Labels to attach to the issue' },
                components: { type: 'array', items: { type: 'string' }, description: 'Components to associate with the issue' },
                fixVersions: { type: 'array', items: { type: 'string' }, description: 'Fix versions to associate with the issue' },
                duedate: { type: 'string', description: 'Due date in YYYY-MM-DD format' }
              },
              required: ['projectKey', 'summary']
            }
          },
        },
        parse: JSON.parse,
        function: ({ request }) =>
          createJiraIssue(request, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor),
      },
    }

    result.push(retrieveJiraTicketFunction)
    result.push(searchJiraIssuesFunction)
    result.push(addCommentFunction)
    result.push(countJiraIssuesFunction)
    result.push(createIssueFunction)

    return result
  }
}
