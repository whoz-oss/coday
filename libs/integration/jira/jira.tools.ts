import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { searchJiraIssuesWithAI } from './search-jira-issues'
import { addJiraComment } from './add-jira-comment'
import { retrieveJiraIssue } from './retrieve-jira-issue'
import { countJiraIssues } from './count-jira-issues'
import { createJiraIssue } from './create-jira-issue'
import { linkJiraIssues } from './link-jira-issues'
import { JiraService } from './jira.service'
import { validateJqlOperators } from './jira.helpers'
import { CreateJiraIssueRequest } from './jira'
// JiraCustomFieldHelper is used in createJiraIssue.ts

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
           c) Use the query key and only the allowed operators to create the jql.
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
      request: Partial<CreateJiraIssueRequest>
    }> = {
      type: 'function',
      function: {
        name: 'createJiraIssue',
        description: 'Create a new Jira issue/ticket without asking for more information from the user, directly call the function to create the ticket',
        parameters: {
          type: 'object',
          properties: {
            request: {
              type: 'object',
              description: 'Request object containing all issue fields',
              properties: {
                projectKey: { type: 'string', description: 'Project key where the issue will be created' },
                summary: { type: 'string', description: 'Summary/title of the issue' },
                description: { type: 'string', description: 'Detailed description of the issue' },
                issuetype: { type: 'string', description: 'Type of issue' },
                assignee: { type: 'string', description: 'User ID of the assignee' },
                reporter: { type: 'string', description: 'User ID of the reporter' },
                priority: { type: 'string', description: 'Priority of the issue (e.g., "High", "Medium", "Low")' },
                labels: { type: 'array', items: { type: 'string' }, description: 'Labels to attach to the issue' },
                components: { type: 'array', items: { type: 'string' }, description: 'Components to associate with the issue' },
                fixVersions: { type: 'array', items: { type: 'string' }, description: 'Fix versions to associate with the issue' },
                duedate: { type: 'string', description: 'Due date in YYYY-MM-DD format' },
                parent: { 
                  type: 'object', 
                  properties: {
                    key: { type: 'string', description: 'The issue key of the parent' }
                  },
                  description: 'Parent issue for this issue (directly establishes parent-child relationship)'
                },
                linkedIssues: {
                  type: 'array',
                  items: {
                    type: 'object',
                    properties: {
                      key: { type: 'string', description: 'The key of the issue to link' },
                      linkType: { type: 'string', description: 'The type of link to create (default: "is part of" for epics, "relates to" for other issue types)' },
                      isEpicLink: { type: 'boolean', description: 'Whether to use Epic Link field if available (default: true for epics)' }
                    }
                  },
                  description: 'Issues to link to this issue after creation (especially useful for epics)'
                }
              }
            }
          },
        },
        parse: JSON.parse,
        function: async ({ request }) => {
          try {
            // Check if this is a retry with a previous partial request
            if (request.error && request.partialRequest) {
              // Extract the partial request and error message
              const partialRequest = request.partialRequest;
              const previousError = request.error || 'Previous attempt failed';

              this.interactor.displayText(`Retrying Jira issue creation with saved information. Previous error: ${previousError}`);

              // Use the partial request for the retry
              return await createJiraIssue(partialRequest, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor);
            }

            // Normal flow - create the issue with the provided request
            return await createJiraIssue(request, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor);
          } catch (error) {
            this.interactor.error(`Error in createJiraIssue function: ${error}`);
            throw error;
          }
        },
      },
    }

    const linkIssuesFunction: FunctionTool<{
      inwardIssueKey: string,
      outwardIssueKey: string,
      linkType: string,
      comment?: string,
      isEpicLink?: boolean
    }> = {
      type: 'function',
      function: {
        name: 'linkJiraIssues',
        description: 'Link two Jira issues with a specified relationship type',
        parameters: {
          type: 'object',
          properties: {
            inwardIssueKey: { type: 'string', description: 'The issue key that is the source of the link (inward issue)' },
            outwardIssueKey: { type: 'string', description: 'The issue key that is the target of the link (outward issue)' },
            linkType: { type: 'string', description: 'The type of link to create between issues (e.g., "relates to", "blocks", "is blocked by")' },
            comment: { type: 'string', description: 'Optional comment to add when creating the link' },
            isEpicLink: { type: 'boolean', description: 'Set to true to create an Epic-Issue relationship. This will attempt to use the Epic Link field if available, falling back to standard issue linking if not.' }
          }
        },
        parse: JSON.parse,
        function: ({ inwardIssueKey, outwardIssueKey, linkType, comment, isEpicLink }) =>
          linkJiraIssues(
            { inwardIssueKey, outwardIssueKey, linkType, comment, isEpicLink },
            jiraBaseUrl,
            jiraApiToken,
            jiraUsername,
            this.interactor
          )
      }
    }

    result.push(retrieveJiraTicketFunction)
    result.push(searchJiraIssuesFunction)
    result.push(addCommentFunction)
    result.push(countJiraIssuesFunction)
    result.push(createIssueFunction)
    result.push(linkIssuesFunction)

    return result
  }
}
