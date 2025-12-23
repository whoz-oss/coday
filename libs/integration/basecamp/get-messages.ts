import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampMessages(
  oauth: BasecampOAuth,
  projectId: number,
  messageBoardId: number
): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/buckets/${projectId}/message_boards/${messageBoardId}/messages.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching messages: ${response.status} ${response.statusText}`
    }

    const messages = await response.json()

    if (messages.length === 0) {
      return 'No messages found in this message board.'
    }

    const messageList = messages
      .map((msg: any) => {
        const creator = msg.creator ? msg.creator.name : 'Unknown'
        const createdAt = new Date(msg.created_at).toLocaleString()
        const commentsCount = msg.comments_count || 0

        const contentPreview = msg.content ? msg.content.replace(/<[^>]*>/g, '').substring(0, 200) : 'No content'

        return `- **${msg.title}** (ID: ${msg.id})
  - Author: ${creator}
  - Created: ${createdAt}
  - Comments: ${commentsCount}
  - Preview: ${contentPreview}${msg.content && msg.content.length > 200 ? '...' : ''}
  - URL: ${msg.app_url}`
      })
      .join('\n\n')

    return `Found ${messages.length} message(s):\n\n${messageList}`
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
