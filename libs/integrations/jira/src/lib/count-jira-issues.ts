import { generateJiraJQLUrl } from './jira.helpers'
import axios from 'axios'
import { Interactor } from '@coday/model/interactor'
import { JiraCount } from './jira'

export async function countJiraIssues(
  jql: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<JiraCount> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly configured')
  }
  const jqlUrl = generateJiraJQLUrl(jiraBaseUrl, jql)
  try {
    interactor.displayText(`Counting JIRA issues matching query: ${jql}...`)

    const response: { data: JiraCount } = await axios.post(
      `${jiraBaseUrl}/rest/api/3/search/approximate-count`,
      {
        jql,
      },
      {
        auth: {
          username: jiraUsername,
          password: jiraApiToken,
        },
      }
    )

    interactor.displayText(`... found ${JSON.stringify(response.data.count)} issues matching your jql: ${jqlUrl}`)

    return {
      count: response.data.count,
      jqlUrl,
    }
  } catch (error: any) {
    // Comprehensive error handling
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.warn(`Ticket Count Error: ${errorMessage} for the jql: ${jqlUrl}`)

    // Return empty array instead of throwing an error
    return { count: 0, jqlUrl }
  }
}
