import axios from 'axios'
import { JiraSearchParams, JiraSearchResponse, LightWeightSearchResponse } from './jira'
import {generateJiraJQLUrl, getLightWeightIssues} from './jira.helpers'
import { resolveJiraFields } from './jira.helpers';
// Search Jira Issues
export async function searchJiraIssues({
  request,
  jiraBaseUrl,
  jiraApiToken,
  jiraUsername,
  interactor,
}: JiraSearchParams & {
  request: { jql: string; maxResults?: number; fields?: string[]; nextPageToken?: string | undefined }
}): Promise<JiraSearchResponse> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly configured')
  }

  const jqlUrl = generateJiraJQLUrl(jiraBaseUrl, request.jql)

  try {
    interactor.displayText(`Searching JIRA issues with query: ${request.jql}...`)

    const response: { data: JiraSearchResponse } = await axios.post(
      `${jiraBaseUrl}/rest/api/3/search/jql`,
      {
        jql: request.jql,
        maxResults: request.maxResults,
        fields: request.fields,
        nextPageToken: request.nextPageToken ?? null,
      },
      {
        auth: {
          username: jiraUsername,
          password: jiraApiToken,
        },
      }
    )

    const issues = response.data.issues
    const nextPageToken = response.data?.nextPageToken
    interactor.displayText(`... found ${JSON.stringify(issues.length)} matching jql: ${jqlUrl}. The max results is set to ${request.maxResults}`)

    return { issues: issues, nextPageToken, jqlUrl }
  } catch (error: any) {
    // Comprehensive error handling
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.warn(`Ticket Search Error: ${errorMessage} for the jql: ${jqlUrl}`)

    // Return empty array instead of throwing an error
    return { issues: [], nextPageToken: null, jqlUrl }
  }
}



export async function searchJiraIssuesWithAI({
  jql,
  nextPageToken,
  maxResults = 50,
  fields = ['basic'],
  jiraBaseUrl,
  jiraApiToken,
  jiraUsername,
  interactor,
}: JiraSearchParams & {
  jql: string
  nextPageToken?: string | undefined
  maxResults?: number
  fields?: string[]
}): Promise<LightWeightSearchResponse> {
  try {
    // Resolve fields using helper
    const resolvedFields = resolveJiraFields(fields);
    interactor.displayText(`Fetching issues and filter response on those fields: ${resolvedFields.join(', ')}`)

    // Fetch a single page of results
    const searchResults: JiraSearchResponse = await searchJiraIssues({
      request: {
        jql,
        fields: resolvedFields,
        maxResults,
        nextPageToken,
      },
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      interactor,
    })

    // If there are more pages, inform the user
    if (searchResults.nextPageToken) {
      interactor.displayText(`Additional pages of results are available. Use nextPageToken to fetch more.`)
    }

    // Convert to lightweight issues and return with the nextPageToken
    const lightWeightIssues = getLightWeightIssues(searchResults.issues)
    return {
      issues: lightWeightIssues,
      nextPageToken: searchResults.nextPageToken,
      jqlUrl: searchResults.jqlUrl
    }
  } catch (error) {
    console.error('Jira Search Error:', error)
    throw error
  }
}
