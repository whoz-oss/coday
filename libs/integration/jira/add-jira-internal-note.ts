import { Interactor } from '@coday/model'

/**
 * Add an internal note to a Jira ticket using the Jira REST API
 * Internal notes are only visible to agents and not to customers
 */
export async function addJiraInternalNote(
  ticketId: string,
  note: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
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
        body: note,
        properties: [
          {
            key: 'sd.public.comment',
            value: {
              internal: true,
            },
          },
        ],
      }),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(`Failed to add internal note to Jira ticket ${ticketId}: ${error}`)
    }

    interactor.displayText(`Successfully added internal note to Jira ticket ${ticketId}`)
  } catch (error) {
    interactor.error(`Error adding internal note to Jira ticket ${ticketId}: ${error}`)
    throw error
  }
}
