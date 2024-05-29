import axios from 'axios'

interface JiraTicket {
  id: string
  key: string
  fields: {
    summary: string
    description: string
    [key: string]: any
  }
}

export async function retrieveJiraTicket(ticketId: string, jiraBaseUrl: string, jiraApiToken: string, jiraUsername: string): Promise<JiraTicket> {

  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira environment variables are not set')
  }

  try {
    const response = await axios.get(
      `${jiraBaseUrl}/rest/api/2/issue/${ticketId}`,
      {
        auth: {
          username: jiraUsername,
          password: jiraApiToken
        }
      }
    )

    return response.data
  } catch (error: any) {
    throw new Error(
      `Failed to retrieve Jira ticket with ID ${ticketId}: ${error.message}`
    )
  }
}
