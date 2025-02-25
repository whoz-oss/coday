import axios from 'axios'
import { Jira, JiraIssue, JiraSearchParams, SearchResponse } from './jira'
import { getLightWeightTickets } from './jira.helpers'

// Search Jira Tickets
export async function searchJiraTickets({
  request,
  jiraBaseUrl,
  jiraApiToken,
  jiraUsername,
  interactor,
}: JiraSearchParams & {
  request: { jql: string; maxResults?: number; fields?: string[] }
}): Promise<JiraIssue[]> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly configured')
  }

  try {
    interactor.displayText(`Searching JIRA tickets with query: ${request.jql}...`)

    const response: { data: Jira } = await axios.get(`${jiraBaseUrl}/rest/api/3/search`, {
      params: request,
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    const tickets = response.data.issues
    interactor.displayText(`... found ${JSON.stringify(tickets.length)} tickets.`)

    // Robust ticket mapping
    return tickets
  } catch (error: any) {
    // Comprehensive error handling
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.warn(`Ticket Search Error: ${errorMessage}`)

    // Return empty array instead of throwing an error
    return []
  }
}

// Utility function for searching with generated JQL
export async function searchJiraTicketsWithAI({
  jql,
  jiraBaseUrl,
  jiraApiToken,
  jiraUsername,
  interactor,
}: JiraSearchParams & { jql: string }): Promise<SearchResponse> {
  try {
    const tickets: JiraIssue[] = await searchJiraTickets({
      request: {
        jql,
        maxResults: 1000
      },
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      interactor,
    })

    return getLightWeightTickets(tickets)
  } catch (error) {
    console.error('Jira Search Error:', error)
    throw error
  }
}
