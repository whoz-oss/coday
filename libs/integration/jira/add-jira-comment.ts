import {Interactor} from '../../model'

/**
 * Add a comment to a Jira ticket using the Jira REST API
 */
export async function addJiraComment(
  ticketId: string,
  comment: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor,
  internal: boolean = true
): Promise<void> {
  const url = `${jiraBaseUrl}/rest/api/2/issue/${ticketId}/comment`
  const auth = Buffer.from(`${jiraUsername}:${jiraApiToken}`).toString('base64')

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        body: comment,
        ...(internal && {
          properties: [
            {
              key: 'sd.public.comment',
              value: {
                internal: true
              }
            }
          ]
        })
      }),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(`Failed to add comment to Jira ticket ${ticketId}: ${error}`)
    }

    interactor.displayText(`Successfully added comment to Jira ticket ${ticketId}`)
  } catch (error) {
    interactor.error(`Error adding comment to Jira ticket ${ticketId}: ${error}`)
    throw error
  }
}
