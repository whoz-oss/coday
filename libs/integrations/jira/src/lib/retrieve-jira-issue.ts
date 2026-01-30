import axios from 'axios'

export async function retrieveJiraIssue(
  ticketId: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string
): Promise<any> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly set')
  }

  try {
    const response = await axios.get(`${jiraBaseUrl}/rest/api/2/issue/${ticketId}`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    return response.data
  } catch (error: any) {
    return `Failed to retrieve Jira ticket with ID ${ticketId}: ${error.message}`
  }
}
