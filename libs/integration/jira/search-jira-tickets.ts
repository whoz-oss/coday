import axios from 'axios'
import {Jira, JiraFields, JiraIssue, JiraSearchParams} from './jira'

// Search Jira Tickets
export async function searchJiraTickets({
  jql,
  jiraBaseUrl,
  jiraApiToken,
  jiraUsername,
  interactor,
}: JiraSearchParams & { jql: string }): Promise<any[]> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly configured')
  }

  try {
    interactor.displayText(`Searching JIRA tickets with query: ${jql}...`)

    const response: { data: Jira } = await axios.get(`${jiraBaseUrl}/rest/api/2/search`, {
      params: {
        jql,
        maxResults: 50,
      },
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    interactor.displayText(`... found ${response.data.total} tickets.`)

    // Robust ticket mapping
    return response.data.issues.map((issue: JiraIssue) => ({
      key: issue.key,
      summary: issue.fields.summary,
      status: issue.fields.status?.name || 'Unknown',
      priority: issue.fields.priority?.name || 'Undefined',
      additionalFields: Object.keys(issue.fields)
        .filter((key): key is keyof JiraFields => key.startsWith('customfield_'))
        .reduce((acc: Record<string, any>, key: keyof JiraFields) => {
          const value = issue.fields[key as keyof JiraFields]
          if (value !== undefined && value !== null) {
            acc[key] = value
          }
          return acc
        }, {}),
    }))
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
}: JiraSearchParams & { jql: string }): Promise<any[]> {

  try {
    // Use the existing searchJiraTickets tool to perform the actual search
    return await searchJiraTickets({jql: jql, jiraBaseUrl, jiraApiToken, jiraUsername, interactor})
  } catch (error) {
    console.error('Jira Search Error:', error)
    throw error
  }
}
